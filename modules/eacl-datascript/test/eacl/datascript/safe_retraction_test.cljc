(ns eacl.datascript.safe-retraction-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datascript.core :as core]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.mutation :as journal]
            [eacl.datascript.safe-retraction :as safe-datascript]
            [eacl.datascript.schema :as schema]
            [eacl.mutation :as mutation]
            [eacl.relationships.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]
            [eacl.schema.model :as model]))

(defn- seed-contract!
  [conn]
  (let [client (core/make-client conn {})]
    (eacl/write-schema! client contract/safe-retraction-schema)
    (ds/transact! conn
                  (mapv (fn [object] {:eacl/id (:id object)})
                        contract/safe-retraction-objects))
    (eacl/create-relationships! client contract/safe-retraction-relationships)
    client))

(defn- present?
  [db relationship]
  (let [lookup-object (fn [object]
                        (assoc object :id [:eacl/id (:id object)]))]
    (boolean
     (impl/find-one-relationship-id
      db
      (-> relationship
          (update :subject lookup-object)
          (update :resource lookup-object))))))

(defn- internal-relationship
  [relationship]
  (let [lookup-object (fn [object]
                        (assoc object :id [:eacl/id (:id object)]))]
    (-> relationship
        (update :subject lookup-object)
        (update :resource lookup-object))))

(defn- relation-eid
  [db resource-type relation-name subject-type]
  (ds/entid db [:eacl/id
                (model/->relation-id resource-type relation-name subject-type)]))

(defn- options
  []
  {:mutation-id (mutation/new-id)
   :issued-at 1700000000})

(deftest installation-is-explicit-idempotent-and-reload-aware-test
  (let [conn (schema/create-conn)]
    (is (nil? (ds/entid (ds/db conn) safe/function-ident)))
    (is (= :named (:mode (safe-datascript/support-descriptor))))
    (is (true? (:reinstall-after-restore?
                (safe-datascript/support-descriptor))))
    (is (= {:installed? true :state :absent}
           (select-keys (safe-datascript/install! conn)
                        [:installed? :state])))
    (is (fn? (:db/fn (ds/entity (ds/db conn) safe/function-ident))))
    (is (= {:installed? false :state :current}
           (select-keys (safe-datascript/install! conn)
                        [:installed? :state]))))

  (testing "recognized EACL installations upgrade"
    (let [conn (schema/create-conn)]
      (ds/transact! conn [{:db/ident safe/function-ident
                           :db/doc (str safe/function-doc-prefix " v0")
                           :db/fn (fn [_db _target _envelope] [])}])
      (is (= :upgradeable
             (safe-datascript/installation-state (ds/db conn))))
      (is (= :upgradeable (:state (safe-datascript/install! conn))))
      (is (= safe-datascript/function-doc
             (:db/doc (ds/entity (ds/db conn) safe/function-ident))))))

  (testing "unrecognized occupants are rejected"
    (let [conn (schema/create-conn)]
      (ds/transact! conn [{:db/ident safe/function-ident
                           :db/doc "Application function"
                           :db/fn (fn [_db _target _envelope] [])}])
      (let [error (try
                    (safe-datascript/install! conn)
                    nil
                    (catch #?(:clj Exception :cljs :default) error error))]
        (is (= :eacl.safe-retraction/install-conflict
               (:type (ex-data error))))
        (is (= "Application function"
               (:db/doc (ds/entity (ds/db conn)
                                   safe/function-ident))))))))

(deftest serialization-requires-explicit-function-reinstallation-test
  (let [conn (schema/create-conn)
        _ (safe-datascript/install! conn)]
    (is (true? (:reinstall-after-restore?
                (safe-datascript/support-descriptor))))
    #?(:clj
       (is (thrown? Exception
                    (ds/from-serializable
                     (ds/serializable (ds/db conn))))
           "the standard reader cannot reconstruct an arbitrary #object IFn"))))

(deftest installed-function-prewarm-is-repeatable-test
  (let [conn (schema/create-conn)
        _ (safe-datascript/install! conn)
        db (ds/db conn)
        target [:eacl/id "prewarm-missing"]
        envelope (safe/mutation-envelope target (options))
        installed-function (:db/fn (ds/entity db safe/function-ident))
        first-expansion (installed-function db target envelope)
        warmed-expansion (installed-function db target envelope)]
    (is (= [] first-expansion warmed-expansion))))

(deftest named-function-satisfies-shared-contract-test
  (let [conn (schema/create-conn)
        _client (seed-contract! conn)
        _ (safe-datascript/install! conn)
        db-before (ds/db conn)
        target-eid (ds/entid db-before [:eacl/id "target-account"])
        peer-rel (relation-eid db-before :user :peer :user)
        peer-proof-before (:eacl.relation/mutation-id
                           (ds/entity db-before peer-rel))
        deletion-options (options)
        mutation-id (:mutation-id deletion-options)]
    (ds/transact!
     conn
     (safe-datascript/retract-entity-tx-data
      [:eacl/id "target-account"] deletion-options))
    (let [after-delete (ds/db conn)
          remaining (filterv #(present? after-delete %)
                             contract/safe-retraction-relationships)
          head-after-delete (:head-id (journal/graph-state after-delete))
          _ (ds/transact!
             conn
             (safe-datascript/retract-entity-tx-data
              [:eacl/id "missing"] (options)))
          unresolved-no-op?
          (= head-after-delete (:head-id (journal/graph-state (ds/db conn))))
          ghost-relationship (nth contract/safe-retraction-relationships 2)
          {:keys [subject-id resource-id]}
          (impl/find-one-relationship-id
           (ds/db conn) (internal-relationship ghost-relationship))
          reverse-value
          (:v (first (ds/datoms (ds/db conn) :eavt resource-id
                                storage/reverse-attribute)))
          _ (ds/transact! conn [[:db.fn/retractEntity subject-id]])
          _ (ds/transact!
             conn
             (safe-datascript/retract-entity-tx-data subject-id (options)))
          ghost-preserved?
          (boolean
           (seq (ds/datoms (ds/db conn) :eavt resource-id
                           storage/reverse-attribute reverse-value)))]
      (contract/assert-safe-retraction-result!
       {:target-exists? (boolean (seq (ds/datoms after-delete :eavt target-eid)))
        :remaining-relationships remaining
        :unresolved-no-op? unresolved-no-op?
        :existing-ghost-preserved? ghost-preserved?})
      (is (= mutation-id head-after-delete))
      (is (= mutation-id
             (:eacl.mutation/id
              (ds/entity after-delete [:eacl.mutation/id mutation-id]))))
      (is (= peer-proof-before
             (:eacl.relation/mutation-id
              (ds/entity after-delete peer-rel)))
          "unrelated proofs are stable"))))

(deftest direct-function-and-portable-planner-agree-test
  (let [conn (schema/create-conn)
        _client (seed-contract! conn)
        db (ds/db conn)
        target-eid (ds/entid db [:eacl/id "target-account"])
        forward (mapv :v (ds/datoms db :eavt target-eid
                                    storage/forward-attribute))
        reverse (mapv :v (ds/datoms db :eavt target-eid
                                    storage/reverse-attribute))
        plan (safe/plan-local-halves target-eid forward reverse)
        tx-data (safe-datascript/direct-retract-entity-tx-data
                 target-eid (options))
        report (ds/with db tx-data)
        actual-peer-retractions
        (into #{}
              (comp (filter #(false? (:added %)))
                    (filter #(not= target-eid (:e %)))
                    (filter #(contains? storage/attributes (:a %)))
                    (map (fn [{:keys [e a v]}] [:db/retract e a v])))
              (:tx-data report))]
    (is (= (set (:peer-retractions plan)) actual-peer-retractions))
    (is (empty? (ds/datoms (:db-after report) :eavt target-eid)))
    (is (= 2 (count (:relation-ids plan)))
        "the mixed-direction fixture affects exactly two distinct relations")))

(deftest expansion-performs-exactly-two-endpoint-attribute-reads-test
  (let [conn (schema/create-conn)
        _client (seed-contract! conn)
        db (ds/db conn)
        target-eid (ds/entid db [:eacl/id "target-account"])
        envelope (safe/mutation-envelope target-eid (options))
        calls (atom [])
        original ds/datoms]
    (with-redefs [ds/datoms
                  (fn [& args]
                    (swap! calls conj args)
                    (apply original args))]
      (safe-datascript/retract-entity-function db target-eid envelope))
    (is (= {storage/forward-attribute 1
            storage/reverse-attribute 1}
           (frequencies
            (keep (fn [args]
                    (let [attribute (nth args 3 nil)]
                      (when (contains? storage/attributes attribute)
                        attribute)))
                  @calls))))))

(deftest unrelated-database-growth-does-not-change-expansion-work-test
  (let [conn (schema/create-conn)
        client (seed-contract! conn)
        target [:eacl/id "target-account"]
        before-db (ds/db conn)
        before-count
        (count
         (safe-datascript/retract-entity-function
          before-db target (safe/mutation-envelope target (options))))
        unrelated
        (mapv
         (fn [index]
           {:user (eacl/spice-object :user (str "bulk-user-" index))
            :account (eacl/spice-object :account
                                        (str "bulk-account-" index))})
         (range 64))]
    (ds/transact!
     conn
     (into []
           (mapcat (fn [{:keys [user account]}]
                     [{:eacl/id (:id user)} {:eacl/id (:id account)}]))
           unrelated))
    (eacl/create-relationships!
     client
     (mapv (fn [{:keys [user account]}]
             (eacl/->Relationship user :owner account))
           unrelated))
    (let [after-db (ds/db conn)
          after-count
          (count
           (safe-datascript/retract-entity-function
            after-db target (safe/mutation-envelope target (options))))]
      (is (= before-count after-count))
      (is (= 64
             (count
              (filter #(present? after-db %)
                      (mapv (fn [{:keys [user account]}]
                              (eacl/->Relationship user :owner account))
                            unrelated))))))))

(deftest self-relationship-is-handled-by-ordinary-retract-entity-test
  (let [conn (schema/create-conn)
        _client (seed-contract! conn)
        _ (safe-datascript/install! conn)
        relationship (nth contract/safe-retraction-relationships 3)
        eid (ds/entid (ds/db conn) [:eacl/id "self-folder"])
        envelope (safe/mutation-envelope eid (options))
        expansion (safe-datascript/retract-entity-function
                   (ds/db conn) eid envelope)]
    (is (present? (ds/db conn) relationship))
    (is (empty? (filter #(= :db/retract (first %)) expansion)))
    (ds/transact! conn (safe-datascript/retract-entity-tx-data eid (options)))
    (is (empty? (ds/datoms (ds/db conn) :eavt eid)))))

(def ^:private cache-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(defn- seed-cache-case!
  [conn]
  (let [client (core/make-client conn {})
        user (eacl/spice-object :user "u")
        account (eacl/spice-object :account "a")
        relationship (eacl/->Relationship user :owner account)]
    (eacl/write-schema! client cache-schema)
    (ds/transact! conn [{:eacl/id "u"} {:eacl/id "a"}])
    (eacl/create-relationship! client relationship)
    {:user user :account account :relationship relationship}))

(deftest managed-cache-observes-safe-retraction-test
  (let [conn (schema/create-conn)
        {:keys [user account]} (seed-cache-case! conn)
        _ (safe-datascript/install! conn)
        client (core/make-client conn {:coherence-authority :managed})]
    (is (true? (eacl/can? client user :admin account)))
    (is (true? (eacl/can? client user :admin account)))
    (ds/transact! conn
                  (safe-datascript/retract-entity-tx-data
                   [:eacl/id "a"] (options)))
    (is (false? (eacl/can? client user :admin account))
        "the advanced relation mutation proof invalidates the warm grant")))

(deftest certified-writer-serializes-with-safe-retraction-test
  (testing "writer first"
    (let [conn (schema/create-conn)
          client (core/make-client conn {})
          user (eacl/spice-object :user "u")
          account (eacl/spice-object :account "a")
          relationship (eacl/->Relationship user :owner account)]
      (eacl/write-schema! client cache-schema)
      (ds/transact! conn [{:eacl/id "u"} {:eacl/id "a"}])
      (safe-datascript/install! conn)
      (eacl/create-relationship! client relationship)
      (ds/transact! conn
                    (safe-datascript/retract-entity-tx-data
                     [:eacl/id "a"] (options)))
      (is (not (present? (ds/db conn) relationship)))))

  (testing "deletion first rejects tx-data calculated from the old graph"
    (let [conn (schema/create-conn)
          client (core/make-client conn {})
          user (eacl/spice-object :user "u")
          account (eacl/spice-object :account "a")
          relationship (eacl/->Relationship user :owner account)]
      (eacl/write-schema! client cache-schema)
      (ds/transact! conn [{:eacl/id "u"} {:eacl/id "a"}])
      (safe-datascript/install! conn)
      (let [stale-db (ds/db conn)
            internal (internal-relationship relationship)
            tx-data (impl/tx-update-relationship
                     stale-db {:operation :create :relationship internal})
            relation-id (impl/relationship-relation-id stale-db internal)]
        (ds/transact! conn
                      (safe-datascript/retract-entity-tx-data
                       [:eacl/id "a"] (options)))
        (let [error
              (try
                (journal/transact!
                 conn
                 {:mutation-id (mutation/new-id)
                  :calculation-db stale-db
                  :kind :relationships
                  :canonical-data {:operation :concurrency-test}
                  :relation-ids [relation-id]
                  :tx-data tx-data})
                nil
                (catch #?(:clj Exception :cljs :default) error error))]
          (is (= :eacl.mutation/concurrent-write
                 (:type (ex-data error))))
          (is (empty? (ds/datoms (ds/db conn) :eavt
                                 (ds/entid stale-db [:eacl/id "a"])))))))))

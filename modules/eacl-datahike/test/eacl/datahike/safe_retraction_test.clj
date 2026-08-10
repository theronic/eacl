(ns eacl.datahike.safe-retraction-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datahike.core :as core]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as impl]
            [eacl.datahike.mutation :as journal]
            [eacl.datahike.safe-retraction :as safe-datahike]
            [eacl.datahike.schema :as schema]
            [eacl.mutation :as mutation]
            [eacl.relationships.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]
            [eacl.schema.model :as model]))

(def modes
  [["strict schema, keyword attributes" nil :direct]
   ["strict schema, numeric attribute refs" {:attribute-refs? true} :direct]
   ["schema-on-read named function" {:schema-flexibility :read} :named]])

(defn- seed-contract!
  [conn]
  (let [client (core/make-client conn {})]
    (eacl/write-schema! client contract/safe-retraction-schema)
    (d/transact conn
                (vec
                 (map-indexed
                  (fn [index object]
                    {:db/id (- (inc index)) :eacl/id (:id object)})
                  contract/safe-retraction-objects)))
    (eacl/create-relationships! client contract/safe-retraction-relationships)
    client))

(defn- internal-relationship
  [relationship]
  (let [lookup-object (fn [object]
                        (assoc object :id [:eacl/id (:id object)]))]
    (-> relationship
        (update :subject lookup-object)
        (update :resource lookup-object))))

(defn- present?
  [db relationship]
  (boolean
   (impl/find-one-relationship-id db (internal-relationship relationship))))

(defn- relation-eid
  [db resource-type relation-name subject-type]
  (ddb/entid db [:eacl/id
                 (model/->relation-id resource-type relation-name subject-type)]))

(defn- options
  []
  {:mutation-id (mutation/new-id)
   :issued-at 1700000000})

(deftest support-is-capability-driven-and-default-schema-is-unchanged-test
  (doseq [[label config expected-mode] modes]
    (testing label
      (let [conn (schema/create-conn nil config)
            db (d/db conn)
            support (safe-datahike/support-descriptor db)]
        (is (nil? (ddb/entid db safe/function-ident)))
        (is (= expected-mode (:mode support)))
        (is (contains? #{:keyword :ref}
                       (:attribute-representation support)))
        (let [result (safe-datahike/prepare! conn)]
          (is (= expected-mode (get-in result [:support :mode])))
          (if (= :named expected-mode)
            (do
              (is (true? (:installed? result)))
              (is (= :current
                     (safe-datahike/installation-state (d/db conn))))
              (is (false? (:installed? (safe-datahike/prepare! conn)))))
            (do
              (is (= :direct (:state result)))
              (is (nil? (ddb/entid (d/db conn) safe/function-ident)))
              (let [error (try
                            (safe-datahike/install! conn)
                            nil
                            (catch Exception error error))]
                (is (= :eacl.safe-retraction/installation-unavailable
                       (:type (ex-data error))))
                (is (= :direct
                       (get-in (ex-data error) [:support :mode])))
                (is (map? (:alternative (ex-data error))))))))))))

(deftest named-installation-upgrades-and-rejects-conflicts-test
  (testing "recognized marker upgrade"
    (let [conn (schema/create-conn nil {:schema-flexibility :read})]
      (d/transact conn [{:db/ident safe/function-ident
                         :db/doc (str safe/function-doc-prefix " v0")
                         :db/fn (fn [_db _target _envelope] [])}])
      (is (= :upgradeable
             (safe-datahike/installation-state (d/db conn))))
      (is (= :upgradeable (:state (safe-datahike/install! conn))))
      (is (= safe-datahike/function-doc
             (:db/doc (d/entity (d/db conn) safe/function-ident))))))

  (testing "unrecognized occupant"
    (let [conn (schema/create-conn nil {:schema-flexibility :read})]
      (d/transact conn [{:db/ident safe/function-ident
                         :db/doc "Application function"
                         :db/fn (fn [_db _target _envelope] [])}])
      (let [error (try
                    (safe-datahike/install! conn)
                    nil
                    (catch Exception error error))]
        (is (= :eacl.safe-retraction/install-conflict
               (:type (ex-data error))))
        (is (= "Application function"
               (:db/doc (d/entity (d/db conn) safe/function-ident))))))))

(deftest named-function-round-trips-across-an-in-process-connection-test
  (let [conn (schema/create-conn nil {:schema-flexibility :read})
        _ (safe-datahike/prepare! conn)
        reconnected (d/connect (:config (d/db conn)))]
    (is (= :named
           (:mode (safe-datahike/support-descriptor (d/db reconnected)))))
    (is (= :current
           (safe-datahike/installation-state (d/db reconnected))))
    (is (fn? (:db/fn (d/entity (d/db reconnected)
                               safe/function-ident))))))

(deftest installed-function-prewarm-is-repeatable-test
  (let [conn (schema/create-conn nil {:schema-flexibility :read})
        _ (safe-datahike/install! conn)
        db (d/db conn)
        target [:eacl/id "prewarm-missing"]
        envelope (safe/mutation-envelope target (options))
        installed-function (:db/fn (d/entity db safe/function-ident))
        first-expansion (installed-function db target envelope)
        warmed-expansion (installed-function db target envelope)]
    (is (= [] first-expansion warmed-expansion))))

(deftest every-supported-mode-satisfies-the-shared-contract-test
  (doseq [[label config expected-mode] modes]
    (testing label
      (let [conn (schema/create-conn nil config)
            _client (seed-contract! conn)
            _ (safe-datahike/prepare! conn)
            db-before (d/db conn)
            target-eid (ddb/entid db-before [:eacl/id "target-account"])
            peer-rel (relation-eid db-before :user :peer :user)
            peer-proof-before (:eacl.relation/mutation-id
                               (d/entity db-before peer-rel))
            deletion-options (options)
            mutation-id (:mutation-id deletion-options)]
        (is (= expected-mode
               (:mode (safe-datahike/support-descriptor db-before))))
        (d/transact
         conn
         (safe-datahike/retract-entity-tx-data
          db-before [:eacl/id "target-account"] deletion-options))
        (let [after-delete (d/db conn)
              remaining (filterv #(present? after-delete %)
                                 contract/safe-retraction-relationships)
              head-after-delete (:head-id (journal/graph-state after-delete))
              _ (d/transact
                 conn
                 (safe-datahike/retract-entity-tx-data
                  after-delete [:eacl/id "missing"] (options)))
              unresolved-no-op?
              (= head-after-delete
                 (:head-id (journal/graph-state (d/db conn))))
              ghost-relationship (nth contract/safe-retraction-relationships 2)
              {:keys [subject-id resource-id]}
              (impl/find-one-relationship-id
               (d/db conn) (internal-relationship ghost-relationship))
              reverse-value
              (:v (first (ddb/eavt-datoms
                          (d/db conn) resource-id storage/reverse-attribute)))
              _ (d/transact conn [[:db.fn/retractEntity subject-id]])
              ghost-db (d/db conn)
              _ (d/transact
                 conn
                 (safe-datahike/retract-entity-tx-data
                  ghost-db subject-id (options)))
              ghost-preserved?
              (boolean
               (seq (ddb/eavt-datoms
                     (d/db conn) resource-id
                     storage/reverse-attribute reverse-value)))]
          (contract/assert-safe-retraction-result!
           {:target-exists? (ddb/entity-exists? after-delete target-eid)
            :remaining-relationships remaining
            :unresolved-no-op? unresolved-no-op?
            :existing-ghost-preserved? ghost-preserved?})
          (is (= mutation-id head-after-delete))
          (is (= mutation-id
                 (:eacl.mutation/id
                  (d/entity after-delete [:eacl.mutation/id mutation-id]))))
          (is (= peer-proof-before
                 (:eacl.relation/mutation-id
                  (d/entity after-delete peer-rel)))
              "unrelated relation proof remains stable"))))))

(deftest expansion-matches-portable-planner-in-both-attribute-representations-test
  (doseq [[label config _mode] (take 2 modes)]
    (testing label
      (let [conn (schema/create-conn nil config)
            _client (seed-contract! conn)
            db (d/db conn)
            target-eid (ddb/entid db [:eacl/id "target-account"])
            forward (mapv :v (ddb/eavt-datoms
                              db target-eid storage/forward-attribute))
            reverse (mapv :v (ddb/eavt-datoms
                              db target-eid storage/reverse-attribute))
            plan (safe/plan-local-halves target-eid forward reverse)
            envelope (safe/mutation-envelope target-eid (options))
            expansion (safe-datahike/retract-entity-function
                       db target-eid envelope)
            peers (into #{}
                        (filter #(and (vector? %)
                                      (= :db/retract (first %))))
                        expansion)]
        (is (= (set (:peer-retractions plan)) peers))
        (is (= 2 (count (:relation-ids plan))))))))

(deftest expansion-performs-exactly-two-endpoint-attribute-reads-test
  (doseq [[label config _mode] modes]
    (testing label
      (let [conn (schema/create-conn nil config)
            _client (seed-contract! conn)
            db (d/db conn)
            target-eid (ddb/entid db [:eacl/id "target-account"])
            envelope (safe/mutation-envelope target-eid (options))
            calls (atom [])
            original ddb/eavt-datoms]
        (with-redefs [ddb/eavt-datoms
                      (fn [& args]
                        (swap! calls conj args)
                        (apply original args))]
          (safe-datahike/retract-entity-function db target-eid envelope))
        (is (= {storage/forward-attribute 1
                storage/reverse-attribute 1}
               (frequencies (map #(nth % 2) @calls))))))))

(deftest remote-writer-is-reported-as-unsupported-test
  (let [conn (schema/create-conn)
        remote-db (assoc-in (d/db conn)
                            [:config :writer :backend]
                            :remote)
        support (safe-datahike/support-descriptor remote-db)
        error (try
                (safe-datahike/retract-entity-tx-data
                 remote-db 1 (options))
                nil
                (catch Exception error error))]
    (is (= :unsupported (:mode support)))
    (is (= :function-transport-unsafe (:reason support)))
    (is (= :eacl.safe-retraction/unsupported
           (:type (ex-data error))))))

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
    (d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
    (eacl/create-relationship! client relationship)
    {:user user :account account :relationship relationship}))

(deftest every-supported-mode-invalidates-managed-cache-test
  (doseq [[label config _mode] modes]
    (testing label
      (let [conn (schema/create-conn nil config)
            {:keys [user account]} (seed-cache-case! conn)
            _ (safe-datahike/prepare! conn)
            client (core/make-client conn {:coherence-authority :managed})]
        (is (true? (eacl/can? client user :admin account)))
        (is (true? (eacl/can? client user :admin account)))
        (let [db (d/db conn)]
          (d/transact conn
                      (safe-datahike/retract-entity-tx-data
                       db [:eacl/id "a"] (options))))
        (is (false? (eacl/can? client user :admin account))
            "the database-visible relation proof invalidates the warm grant")))))

(deftest certified-writer-serializes-with-every-supported-mode-test
  (doseq [[label config _mode] modes]
    (testing (str label ", writer first")
      (let [conn (schema/create-conn nil config)
            client (core/make-client conn {})
            user (eacl/spice-object :user "u")
            account (eacl/spice-object :account "a")
            relationship (eacl/->Relationship user :owner account)]
        (eacl/write-schema! client cache-schema)
        (d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
        (safe-datahike/prepare! conn)
        (eacl/create-relationship! client relationship)
        (let [db (d/db conn)]
          (d/transact conn
                      (safe-datahike/retract-entity-tx-data
                       db [:eacl/id "a"] (options))))
        (is (not (present? (d/db conn) relationship)))))

    (testing (str label ", deletion first")
      (let [conn (schema/create-conn nil config)
            client (core/make-client conn {})
            user (eacl/spice-object :user "u")
            account (eacl/spice-object :account "a")
            relationship (eacl/->Relationship user :owner account)]
        (eacl/write-schema! client cache-schema)
        (d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
        (safe-datahike/prepare! conn)
        (let [stale-db (d/db conn)
              internal (internal-relationship relationship)
              tx-data (impl/tx-update-relationship
                       stale-db {:operation :create :relationship internal})
              relation-id (impl/relationship-relation-id stale-db internal)]
          (d/transact conn
                      (safe-datahike/retract-entity-tx-data
                       stale-db [:eacl/id "a"] (options)))
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
                  (catch Exception error error))]
            (is (= :eacl.mutation/concurrent-write
                   (:type (ex-data error))))
            (is (not (ddb/entity-exists?
                      (d/db conn)
                      (ddb/entid stale-db [:eacl/id "a"]))))))))))

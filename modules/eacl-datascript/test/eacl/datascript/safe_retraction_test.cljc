(ns eacl.datascript.safe-retraction-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datascript.core :as core]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.safe-retraction :as safe-datascript]
            [eacl.datascript.schema :as schema]
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

(defn- internal-relationship
  [relationship]
  (let [lookup-object #(assoc % :id [:eacl/id (:id %)])]
    (-> relationship
        (update :subject lookup-object)
        (update :resource lookup-object))))

(defn- present?
  [db relationship]
  (boolean
   (impl/find-one-relationship-id db (internal-relationship relationship))))

(defn- relation-eid
  [db resource-type relation-name subject-type]
  (ds/entid db [:eacl/id
                (model/->relation-id resource-type relation-name subject-type)]))

(defn- relation-generation
  [db relation]
  (some-> (first (ds/datoms db :eavt relation :eacl/relation-version)) :v))

(deftest installation-is-explicit-idempotent-and-conflict-safe-test
  (let [conn (schema/create-conn)]
    (is (nil? (ds/entid (ds/db conn) safe/function-ident)))
    (is (= :named (:mode (safe-datascript/support-descriptor))))
    (is (= {:installed? true :state :absent}
           (select-keys (safe-datascript/install! conn)
                        [:installed? :state])))
    (is (fn? (:db/fn (ds/entity (ds/db conn) safe/function-ident))))
    (is (= {:installed? false :state :current}
           (select-keys (safe-datascript/install! conn)
                        [:installed? :state]))))
  (testing "recognized EACL markers upgrade"
    (let [conn (schema/create-conn)]
      (ds/transact! conn [{:db/ident safe/function-ident
                           :db/doc (str safe/function-doc-prefix " v0")
                           :db/fn (fn [& _] [])}])
      (is (= :upgradeable (:state (safe-datascript/install! conn))))))
  (testing "unrecognized occupants fail closed"
    (let [conn (schema/create-conn)]
      (ds/transact! conn [{:db/ident safe/function-ident
                           :db/doc "Application function"
                           :db/fn (fn [& _] [])}])
      (let [error (try
                    (safe-datascript/install! conn)
                    nil
                    (catch #?(:clj Exception :cljs :default) error error))]
        (is (= :eacl.safe-retraction/install-conflict
               (:type (ex-data error))))))))

(deftest target-only-live-retraction-removes-both-halves-and-stamps-relations-test
  (let [conn (schema/create-conn)
        _ (seed-contract! conn)
        _ (safe-datascript/install! conn)
        before (ds/db conn)
        target-eid (ds/entid before [:eacl/id "target-account"])
        owner-rel (relation-eid before :account :owner :user)
        server-rel (relation-eid before :server :account :account)
        peer-rel (relation-eid before :user :peer :user)
        generations-before
        (mapv #(relation-generation before %)
              [owner-rel server-rel peer-rel])]
    (ds/transact! conn
                  (safe-datascript/retract-entity-tx-data
                   [:eacl/id "target-account"]))
    (let [after (ds/db conn)
          remaining (filterv #(present? after %)
                             contract/safe-retraction-relationships)
          generations-after
          (mapv #(relation-generation after %)
                [owner-rel server-rel peer-rel])]
      (is (empty? (ds/datoms after :eavt target-eid)))
      (is (= (set (drop 2 contract/safe-retraction-relationships))
             (set remaining)))
      (is (not= (subvec generations-before 0 2)
                (subvec generations-after 0 2)))
      (is (= (nth generations-before 2) (nth generations-after 2))
          "an unrelated relation generation remains reusable"))))

(deftest known-retracted-numeric-eid-repairs-a-peer-only-ghost-test
  (let [conn (schema/create-conn)
        _ (seed-contract! conn)
        _ (safe-datascript/install! conn)
        relationship (nth contract/safe-retraction-relationships 2)
        before (ds/db conn)
        {:keys [subject-id resource-id]}
        (impl/find-one-relationship-id before (internal-relationship relationship))
        reverse-value (:v (first (ds/datoms before :eavt resource-id
                                             storage/reverse-attribute)))]
    (ds/transact! conn [[:db.fn/retractEntity subject-id]])
    (is (seq (ds/datoms (ds/db conn) :eavt resource-id
                        storage/reverse-attribute reverse-value)))
    (ds/transact! conn (safe-datascript/retract-entity-tx-data subject-id))
    (is (empty? (ds/datoms (ds/db conn) :eavt resource-id
                           storage/reverse-attribute reverse-value)))
    (is (= []
           (safe-datascript/retract-entity-function
            (ds/db conn) [:eacl/id "missing"])))))

(deftest multiple-and-repeated-invocations-compose-in-one-transaction-test
  (let [conn (schema/create-conn)
        _ (seed-contract! conn)
        _ (safe-datascript/install! conn)
        db (ds/db conn)
        account-eid (ds/entid db [:eacl/id "target-account"])
        folder-eid (ds/entid db [:eacl/id "self-folder"])
        invocations [[:eacl/id "target-account"]
                     [:eacl/id "self-folder"]
                     account-eid]]
    (ds/transact!
     conn
     (into [] (mapcat safe-datascript/retract-entity-tx-data) invocations))
    (is (empty? (ds/datoms (ds/db conn) :eavt account-eid)))
    (is (empty? (ds/datoms (ds/db conn) :eavt folder-eid)))
    (is (present? (ds/db conn) (nth contract/safe-retraction-relationships 2)))
    (is (present? (ds/db conn) (nth contract/safe-retraction-relationships 4)))))

(deftest direct-and-named-expansions-match-the-portable-plan-test
  (let [conn (schema/create-conn)
        _ (seed-contract! conn)
        _ (safe-datascript/install! conn)
        db (ds/db conn)
        target-eid (ds/entid db [:eacl/id "target-account"])
        plan (safe/plan-local-halves
              target-eid
              (mapv :v (ds/datoms db :eavt target-eid storage/forward-attribute))
              (mapv :v (ds/datoms db :eavt target-eid storage/reverse-attribute)))
        expansion (safe-datascript/retract-entity-function db target-eid)
        peer-ops (into #{} (filter #(= :db/retract (first %))) expansion)
        direct-after (:db-after
                      (ds/with db
                        (safe-datascript/direct-retract-entity-tx-data
                         target-eid)))]
    (is (= (set (:peer-retractions plan)) peer-ops))
    (is (= 2 (count (:relation-ids plan))))
    (is (empty? (ds/datoms direct-after :eavt target-eid)))))

(deftest component-cascade-cleans-relationships-and-protects-control-entities-test
  (let [component-schema
        {:test/component {:db/valueType :db.type/ref
                          :db/cardinality :db.cardinality/one
                          :db/isComponent true}}
        conn (schema/create-conn component-schema)
        client (seed-contract! conn)
        _ (safe-datascript/install! conn)
        child (eacl/spice-object :account "component-child")
        relationship
        (eacl/->Relationship (eacl/spice-object :user "user-1") :owner child)]
    (ds/transact! conn [{:eacl/id "component-child"}
                        {:eacl/id "component-parent"
                         :test/component [:eacl/id "component-child"]}])
    (eacl/create-relationship! client relationship)
    (let [db (ds/db conn)
          parent-eid (ds/entid db [:eacl/id "component-parent"])
          child-eid (ds/entid db [:eacl/id "component-child"])]
      (ds/transact! conn
                    (safe-datascript/retract-entity-tx-data parent-eid))
      (is (empty? (ds/datoms (ds/db conn) :eavt parent-eid)))
      (is (empty? (ds/datoms (ds/db conn) :eavt child-eid)))
      (is (not (present? (ds/db conn) relationship))))
    (let [relation (relation-eid (ds/db conn) :user :peer :user)
          error (try
                  (safe-datascript/retract-entity-function (ds/db conn) relation)
                  nil
                  (catch #?(:clj Exception :cljs :default) error error))]
      (is (= :protected-control-entity (:reason (ex-data error)))))))

(def ^:private cache-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(deftest managed-cache-and-stale-endpoint-writes-remain-correct-test
  (let [conn (schema/create-conn)
        setup-client (core/make-client conn {})
        user (eacl/spice-object :user "u")
        account (eacl/spice-object :account "a")
        relationship (eacl/->Relationship user :owner account)]
    (eacl/write-schema! setup-client cache-schema)
    (ds/transact! conn [{:eacl/id "u"} {:eacl/id "a"}])
    (safe-datascript/install! conn)
    (eacl/create-relationship! setup-client relationship)
    (let [client (core/make-client conn {:coherence-authority :managed})]
      (is (true? (eacl/can? client user :admin account)))
      (is (true? (eacl/can? client user :admin account)))
      (ds/transact! conn
                    (safe-datascript/retract-entity-tx-data [:eacl/id "a"]))
      (is (false? (eacl/can? client user :admin account))))
    (ds/transact! conn [{:eacl/id "b"}])
    (let [new-account (eacl/spice-object :account "b")
          new-relationship (eacl/->Relationship user :owner new-account)
          internal (internal-relationship new-relationship)
          planned (impl/tx-update-relationship
                   (ds/db conn) {:operation :create :relationship internal})]
      (ds/transact! conn
                    (safe-datascript/retract-entity-tx-data [:eacl/id "b"]))
      (let [error (try
                    (ds/transact! conn planned)
                    nil
                    (catch #?(:clj Exception :cljs :default) error error))]
        (is (some? error)
            "commit-time endpoint identity CAS rejects the stale plan")))))

(deftest live-expansion-work-is-independent-of-unrelated-database-size-test
  (let [conn (schema/create-conn)
        client (seed-contract! conn)
        target [:eacl/id "target-account"]
        expansion-size
        #(count (safe-datascript/retract-entity-function (ds/db conn) target))
        before (expansion-size)
        unrelated
        (mapv (fn [index]
                [(eacl/spice-object :user (str "bulk-user-" index))
                 (eacl/spice-object :account (str "bulk-account-" index))])
              (range 128))]
    (ds/transact! conn
                  (into [] (mapcat (fn [[u a]]
                                     [{:eacl/id (:id u)} {:eacl/id (:id a)}]))
                        unrelated))
    (eacl/create-relationships!
     client
     (mapv (fn [[u a]] (eacl/->Relationship u :owner a)) unrelated))
    (is (= before (expansion-size)))))

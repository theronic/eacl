(ns eacl.datahike.safe-retraction-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datahike.core :as core]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as impl]
            [eacl.datahike.safe-retraction :as safe-datahike]
            [eacl.datahike.schema :as schema]
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
  (ddb/entid db [:eacl/id
                 (model/->relation-id resource-type relation-name subject-type)]))

(defn- relation-generation
  [db relation]
  (some-> (first (ddb/eavt-datoms db relation :eacl/relation-version)) :v))

(deftest support-is-capability-driven-and-installation-is-idempotent-test
  (doseq [[label config expected-mode] modes]
    (testing label
      (let [conn (schema/create-conn nil config)
            db (d/db conn)
            support (safe-datahike/support-descriptor db)]
        (is (= expected-mode (:mode support)))
        (is (nil? (ddb/entid db safe/function-ident)))
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
              (is (nil? (ddb/entid (d/db conn) safe/function-ident)))))))))
  (testing "unrecognized named occupants fail closed"
    (let [conn (schema/create-conn nil {:schema-flexibility :read})]
      (d/transact conn [{:db/ident safe/function-ident
                         :db/doc "Application function"
                         :db/fn (fn [& _] [])}])
      (let [error (try
                    (safe-datahike/install! conn)
                    nil
                    (catch Exception error error))]
        (is (= :eacl.safe-retraction/install-conflict
               (:type (ex-data error))))))))

(deftest every-mode-retracts-both-halves-and-only-stamps-affected-relations-test
  (doseq [[label config expected-mode] modes]
    (testing label
      (let [conn (schema/create-conn nil config)
            _ (seed-contract! conn)
            _ (safe-datahike/prepare! conn)
            before (d/db conn)
            target-eid (ddb/entid before [:eacl/id "target-account"])
            owner-rel (relation-eid before :account :owner :user)
            server-rel (relation-eid before :server :account :account)
            peer-rel (relation-eid before :user :peer :user)
            generations-before
            (mapv #(relation-generation before %)
                  [owner-rel server-rel peer-rel])]
        (is (= expected-mode
               (:mode (safe-datahike/support-descriptor before))))
        (d/transact
         conn
         (safe-datahike/retract-entity-tx-data
          before [:eacl/id "target-account"]))
        (let [after (d/db conn)
              generations-after
              (mapv #(relation-generation after %)
                    [owner-rel server-rel peer-rel])]
          (is (not (ddb/entity-exists? after target-eid)))
          (is (= (set (drop 2 contract/safe-retraction-relationships))
                 (set (filterv #(present? after %)
                               contract/safe-retraction-relationships))))
          (is (not= (subvec generations-before 0 2)
                    (subvec generations-after 0 2)))
          (is (= (nth generations-before 2) (nth generations-after 2))))))))

(deftest every-mode-repairs-known-numeric-ghosts-test
  (doseq [[label config _] modes]
    (testing label
      (let [conn (schema/create-conn nil config)
            _ (seed-contract! conn)
            _ (safe-datahike/prepare! conn)
            relationship (nth contract/safe-retraction-relationships 2)
            before (d/db conn)
            {:keys [subject-id resource-id]}
            (impl/find-one-relationship-id before (internal-relationship relationship))
            reverse-value
            (:v (first (ddb/eavt-datoms before resource-id
                                        storage/reverse-attribute)))]
        (d/transact conn [[:db.fn/retractEntity subject-id]])
        (is (seq (ddb/eavt-datoms (d/db conn) resource-id
                                  storage/reverse-attribute reverse-value)))
        (let [ghost-db (d/db conn)]
          (d/transact conn
                      (safe-datahike/retract-entity-tx-data
                       ghost-db subject-id)))
        (is (empty? (ddb/eavt-datoms (d/db conn) resource-id
                                     storage/reverse-attribute reverse-value)))
        (is (= []
               (safe-datahike/retract-entity-function
                (d/db conn) [:eacl/id "missing"])))))))

(deftest multiple-and-repeated-invocations-compose-in-one-transaction-test
  (doseq [[label config _] modes]
    (testing label
      (let [conn (schema/create-conn nil config)
            _ (seed-contract! conn)
            _ (safe-datahike/prepare! conn)
            db (d/db conn)
            account-eid (ddb/entid db [:eacl/id "target-account"])
            folder-eid (ddb/entid db [:eacl/id "self-folder"])
            invocations [[:eacl/id "target-account"]
                         [:eacl/id "self-folder"]
                         account-eid]]
        (d/transact
         conn
         (into []
               (mapcat #(safe-datahike/retract-entity-tx-data db %))
               invocations))
        (is (not (ddb/entity-exists? (d/db conn) account-eid)))
        (is (not (ddb/entity-exists? (d/db conn) folder-eid)))
        (is (present? (d/db conn)
                      (nth contract/safe-retraction-relationships 2)))
        (is (present? (d/db conn)
                      (nth contract/safe-retraction-relationships 4)))))))

(deftest expansion-matches-portable-plan-in-both-attribute-representations-test
  (doseq [[label config _] (take 2 modes)]
    (testing label
      (let [conn (schema/create-conn nil config)
            _ (seed-contract! conn)
            db (d/db conn)
            target-eid (ddb/entid db [:eacl/id "target-account"])
            plan (safe/plan-local-halves
                  target-eid
                  (mapv :v (ddb/eavt-datoms
                            db target-eid storage/forward-attribute))
                  (mapv :v (ddb/eavt-datoms
                            db target-eid storage/reverse-attribute)))
            expansion (safe-datahike/retract-entity-function db target-eid)
            peers (into #{} (filter #(= :db/retract (first %))) expansion)]
        (is (= (set (:peer-retractions plan)) peers))
        (is (= 2 (count (:relation-ids plan))))))))

(deftest remote-writers-are-explicitly-unsupported-test
  (let [conn (schema/create-conn)
        remote-db (assoc-in (d/db conn) [:config :writer :backend] :remote)
        support (safe-datahike/support-descriptor remote-db)
        error (try
                (safe-datahike/retract-entity-tx-data remote-db 1)
                nil
                (catch Exception error error))]
    (is (= :unsupported (:mode support)))
    (is (= :function-transport-unsafe (:reason support)))
    (is (= :eacl.safe-retraction/unsupported (:type (ex-data error))))))

(deftest component-cascade-cleans-relationships-and-protects-control-entities-test
  (let [extra-schema
        [{:db/ident :test/component
          :db/valueType :db.type/ref
          :db/cardinality :db.cardinality/one
          :db/isComponent true}]
        conn (schema/create-conn extra-schema)
        client (seed-contract! conn)
        _ (safe-datahike/prepare! conn)
        child (eacl/spice-object :account "component-child")
        relationship
        (eacl/->Relationship (eacl/spice-object :user "user-1") :owner child)]
    (d/transact conn [{:db/id -1 :eacl/id "component-child"}
                      {:db/id -2 :eacl/id "component-parent"
                       :test/component [:eacl/id "component-child"]}])
    (eacl/create-relationship! client relationship)
    (let [db (d/db conn)
          parent-eid (ddb/entid db [:eacl/id "component-parent"])
          child-eid (ddb/entid db [:eacl/id "component-child"])]
      (d/transact conn
                  (safe-datahike/retract-entity-tx-data db parent-eid))
      (is (not (ddb/entity-exists? (d/db conn) parent-eid)))
      (is (not (ddb/entity-exists? (d/db conn) child-eid)))
      (is (not (present? (d/db conn) relationship))))
    (let [db (d/db conn)
          relation (relation-eid db :user :peer :user)
          error (try
                  (safe-datahike/retract-entity-function db relation)
                  nil
                  (catch Exception error error))]
      (is (= :protected-control-entity (:reason (ex-data error)))))))

(def ^:private cache-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(deftest managed-cache-and-stale-endpoint-writes-remain-correct-in-every-mode-test
  (doseq [[label config _] modes]
    (testing label
      (let [conn (schema/create-conn nil config)
            setup-client (core/make-client conn {})
            user (eacl/spice-object :user "u")
            account (eacl/spice-object :account "a")
            relationship (eacl/->Relationship user :owner account)]
        (eacl/write-schema! setup-client cache-schema)
        (d/transact conn [{:db/id -1 :eacl/id "u"}
                          {:db/id -2 :eacl/id "a"}])
        (safe-datahike/prepare! conn)
        (eacl/create-relationship! setup-client relationship)
        (let [client (core/make-client conn {})]
          (is (true? (eacl/can? client user :admin account)))
          (is (true? (eacl/can? client user :admin account)))
          (let [db (d/db conn)]
            (d/transact conn
                        (safe-datahike/retract-entity-tx-data
                         db [:eacl/id "a"])))
          (is (false? (eacl/can? client user :admin account))))
        (d/transact conn [{:db/id -1 :eacl/id "b"}])
        (let [new-account (eacl/spice-object :account "b")
              internal (internal-relationship
                        (eacl/->Relationship user :owner new-account))
              calculation-db (d/db conn)
              planned (impl/tx-update-relationship
                       calculation-db
                       {:operation :create :relationship internal})]
          (d/transact conn
                      (safe-datahike/retract-entity-tx-data
                       calculation-db [:eacl/id "b"]))
          (let [error (try
                        (d/transact conn planned)
                        nil
                        (catch Exception error error))]
            (is (some? error)
                "commit-time endpoint identity CAS rejects the stale plan")))))))

(deftest live-expansion-size-ignores-unrelated-database-growth-test
  (let [conn (schema/create-conn)
        client (seed-contract! conn)
        target [:eacl/id "target-account"]
        expansion-size
        #(count (safe-datahike/retract-entity-function (d/db conn) target))
        before (expansion-size)
        unrelated
        (mapv (fn [index]
                [(eacl/spice-object :user (str "bulk-user-" index))
                 (eacl/spice-object :account (str "bulk-account-" index))])
              (range 128))]
    (d/transact conn
                (vec
                 (map-indexed
                  (fn [index object]
                    {:db/id (- (inc index)) :eacl/id (:id object)})
                  (mapcat identity unrelated))))
    (eacl/create-relationships!
     client
     (mapv (fn [[u a]] (eacl/->Relationship u :owner a)) unrelated))
    (is (= before (expansion-size)))))

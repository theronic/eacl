(ns eacl.datomic.safe-retraction-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.safe-retraction :as safe-datomic]
            [eacl.datomic.schema :as schema]
            [eacl.relationships.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.endpoint-pair :as pair]))

(defn- relation-eid
  [db resource-type relation-name subject-type]
  (d/entid
   db
   [:eacl.relation/resource-type+relation-name+subject-type
    [resource-type relation-name subject-type]]))

(defn- relation-generation
  [db relation]
  (some-> (first (d/datoms db :eavt relation :eacl/relation-version)) :v))

(defn- resolve-relationship
  [db {:keys [subject relation resource]}]
  (let [subject-eid (d/entid db [:eacl/id (:id subject)])
        resource-eid (d/entid db [:eacl/id (:id resource)])
        relation-eid (relation-eid db (:type resource) relation (:type subject))]
    {:subject-eid subject-eid
     :resource-eid resource-eid
     :relation-eid relation-eid
     :forward (pair/forward-value (:type subject) relation-eid (:type resource) resource-eid)
     :reverse (pair/reverse-value (:type resource) relation-eid (:type subject) subject-eid)}))

(defn- relationship-present?
  [db relationship]
  (let [{:keys [subject-eid resource-eid forward reverse]}
        (resolve-relationship db relationship)]
    (boolean
     (and subject-eid resource-eid
          (seq (d/datoms db :eavt subject-eid storage/forward-attribute
                         forward))
          (seq (d/datoms db :eavt resource-eid storage/reverse-attribute
                         reverse))))))

(defn- seed-contract!
  [conn]
  (schema/write-schema! conn contract/safe-retraction-schema)
  @(d/transact conn
               (mapv (fn [object] {:eacl/id (:id object)})
                     contract/safe-retraction-objects))
  (doseq [relationship contract/safe-retraction-relationships]
    @(d/transact conn (impl/tx-relationship (d/db conn) relationship)))
  true)

(deftest installation-is-explicit-idempotent-versioned-and-conflict-safe-test
  (with-mem-conn [conn schema/v8-schema]
    (is (nil? (d/entid (d/db conn) safe/function-ident)))
    (is (= :named (:mode (safe-datomic/support-descriptor))))
    (is (= 64 (count safe-datomic/function-digest)))
    (is (re-find #":requires \[\]"
                 (pr-str (:db/fn safe-datomic/function-definition)))
        "the transactor function has no EACL classpath dependency")
    (is (= {:installed? true :state :absent}
           (select-keys (safe-datomic/install! conn) [:installed? :state])))
    (is (= {:installed? false :state :current}
           (select-keys (safe-datomic/install! conn) [:installed? :state]))))
  (testing "recognized EACL markers upgrade"
    (with-mem-conn [conn schema/v8-schema]
      @(d/transact
        conn
        [{:db/ident safe/function-ident
          :db/doc (str safe/function-doc-prefix " v0")
          :db/fn (d/function {:lang "clojure"
                              :params '[db target envelope]
                              :code '(do [])})}])
      (is (= :upgradeable (:state (safe-datomic/install! conn))))))
  (testing "unrecognized occupants fail closed"
    (with-mem-conn [conn schema/v8-schema]
      @(d/transact
        conn
        [{:db/ident safe/function-ident
          :db/doc "Application function"
          :db/fn (d/function {:lang "clojure"
                              :params '[db target]
                              :code '(do [])})}])
      (let [error (try
                    (safe-datomic/install! conn)
                    nil
                    (catch Exception error error))]
        (is (= :eacl.safe-retraction/install-conflict
               (:type (ex-data error))))))))

(deftest target-only-function-removes-both-halves-and-stamps-only-affected-relations-test
  (with-mem-conn [conn schema/v8-schema]
    (seed-contract! conn)
    (safe-datomic/install! conn)
    (let [before (d/db conn)
          target-eid (d/entid before [:eacl/id "target-account"])
          owner-rel (relation-eid before :account :owner :user)
          server-rel (relation-eid before :server :account :account)
          peer-rel (relation-eid before :user :peer :user)
          generations-before
          (mapv #(relation-generation before %)
                [owner-rel server-rel peer-rel])
          expansion
          (d/invoke before safe/function-ident before
                    [:eacl/id "target-account"])]
      (is (= 1 (count (filter #(= :db.fn/retractEntity (first %)) expansion))))
      (is (= 2 (count (filter #(and (= :db/add (first %))
                                    (= :eacl/relation-version (nth % 2)))
                              expansion))))
      @(d/transact conn
                   (safe-datomic/retract-entity-tx-data
                    [:eacl/id "target-account"]))
      (let [after (d/db conn)
            generations-after
            (mapv #(relation-generation after %)
                  [owner-rel server-rel peer-rel])]
        (is (empty? (d/datoms after :eavt target-eid)))
        (is (= (set (drop 2 contract/safe-retraction-relationships))
               (set (filterv #(relationship-present? after %)
                             contract/safe-retraction-relationships))))
        (is (not= (subvec generations-before 0 2)
                  (subvec generations-after 0 2)))
        (is (= (nth generations-before 2) (nth generations-after 2)))))))

(deftest known-retracted-numeric-eid-repairs-a-peer-only-ghost-test
  (with-mem-conn [conn schema/v8-schema]
    (seed-contract! conn)
    (safe-datomic/install! conn)
    (let [relationship (nth contract/safe-retraction-relationships 2)
          before (d/db conn)
          {:keys [subject-eid resource-eid reverse]}
          (resolve-relationship before relationship)]
      @(d/transact conn [[:db.fn/retractEntity subject-eid]])
      (is (seq (d/datoms (d/db conn) :eavt resource-eid
                         storage/reverse-attribute reverse)))
      @(d/transact conn (safe-datomic/retract-entity-tx-data subject-eid))
      (is (empty? (d/datoms (d/db conn) :eavt resource-eid
                            storage/reverse-attribute reverse)))
      (is (= []
             (d/invoke (d/db conn) safe/function-ident (d/db conn)
                       [:eacl/id "missing"]))))))

(deftest multiple-and-repeated-invocations-compose-in-one-transaction-test
  (with-mem-conn [conn schema/v8-schema]
    (seed-contract! conn)
    (safe-datomic/install! conn)
    (let [db (d/db conn)
          account-eid (d/entid db [:eacl/id "target-account"])
          folder-eid (d/entid db [:eacl/id "self-folder"])]
      @(d/transact
        conn
        [[:eacl.fn/retractEntity [:eacl/id "target-account"]]
         [:eacl.fn/retractEntity [:eacl/id "self-folder"]]
         [:eacl.fn/retractEntity account-eid]])
      (is (empty? (d/datoms (d/db conn) :eavt account-eid)))
      (is (empty? (d/datoms (d/db conn) :eavt folder-eid)))
      (is (relationship-present?
           (d/db conn) (nth contract/safe-retraction-relationships 2)))
      (is (relationship-present?
           (d/db conn) (nth contract/safe-retraction-relationships 4))))))

(deftest self-relationships-components-and-protected-entities-are-safe-test
  (let [extra-schema
        [{:db/ident :test/component
          :db/valueType :db.type/ref
          :db/cardinality :db.cardinality/one
          :db/isComponent true}]]
    (with-mem-conn [conn (into schema/v8-schema extra-schema)]
      (seed-contract! conn)
      (safe-datomic/install! conn)
      (let [client (core/make-client conn {})
            child (eacl/spice-object :account "component-child")
            relationship
            (eacl/->Relationship
             (eacl/spice-object :user "user-1") :owner child)]
        (let [child-tempid (d/tempid :db.part/user)]
          @(d/transact conn [{:db/id child-tempid
                              :eacl/id "component-child"}
                             {:eacl/id "component-parent"
                              :test/component child-tempid}]))
        (eacl/create-relationship! client relationship)
        (let [db (d/db conn)
              parent-eid (d/entid db [:eacl/id "component-parent"])
              child-eid (d/entid db [:eacl/id "component-child"])]
          @(d/transact conn
                       (safe-datomic/retract-entity-tx-data parent-eid))
          (is (empty? (d/datoms (d/db conn) :eavt parent-eid)))
          (is (empty? (d/datoms (d/db conn) :eavt child-eid)))
          (is (not (relationship-present? (d/db conn) relationship)))))
      (let [db (d/db conn)
            relation (relation-eid db :user :peer :user)
            error (try
                    (d/invoke db safe/function-ident db relation)
                    nil
                    (catch Exception error error))]
        (is (= :protected-control-entity (:reason (ex-data error))))))))

(def ^:private cache-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(deftest managed-cache-and-stale-endpoint-writes-remain-correct-test
  (with-mem-conn [conn schema/v8-schema]
    (schema/write-schema! conn cache-schema)
    @(d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
    (safe-datomic/install! conn)
    (let [user (eacl/spice-object :user "u")
          account (eacl/spice-object :account "a")
          relationship (eacl/->Relationship user :owner account)
          setup-client (core/make-client conn {})]
      (eacl/create-relationship! setup-client relationship)
      (let [client (core/make-client conn {})]
        (is (true? (eacl/can? client user :admin account)))
        (is (true? (eacl/can? client user :admin account)))
        @(d/transact conn
                     (safe-datomic/retract-entity-tx-data [:eacl/id "a"]))
        (is (false? (eacl/can? client user :admin account))))
      @(d/transact conn [{:eacl/id "b"}])
      (let [new-account (eacl/spice-object :account "b")
            calculation-db (d/db conn)
            planned
            (impl/tx-relationship
             calculation-db (eacl/->Relationship user :owner new-account))]
        @(d/transact conn
                     (safe-datomic/retract-entity-tx-data [:eacl/id "b"]))
        (let [error (try
                      @(d/transact conn planned)
                      nil
                      (catch Exception error error))]
          (is (some? error)
              "commit-time endpoint identity CAS rejects the stale plan"))))))

(deftest live-expansion-size-ignores-unrelated-database-growth-test
  (with-mem-conn [conn schema/v8-schema]
    (seed-contract! conn)
    (safe-datomic/install! conn)
    (let [target [:eacl/id "target-account"]
          expansion-size
          #(count (d/invoke (d/db conn) safe/function-ident (d/db conn) target))
          before (expansion-size)
          unrelated
          (mapv (fn [index]
                  [(eacl/spice-object :user (str "bulk-user-" index))
                   (eacl/spice-object :account (str "bulk-account-" index))])
                (range 128))]
      @(d/transact conn
                   (into []
                         (mapcat (fn [[u a]]
                                   [{:eacl/id (:id u)} {:eacl/id (:id a)}]))
                         unrelated))
      (let [client (core/make-client conn {})]
        (eacl/create-relationships!
         client
         (mapv (fn [[u a]] (eacl/->Relationship u :owner a)) unrelated)))
      (is (= before (expansion-size))))))

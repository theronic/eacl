(ns eacl.migrations.v7-to-v8-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.core :as eacl.datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl.base :as base]
            [eacl.datomic.schema :as schema]
            [eacl.migrations.v7-to-v8 :as migration]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.expression-persistence :as persistence]
            [eacl.schema.expression-resolver :as resolver]
            [eacl.secure-format :as secure]))

(def legacy-permission-index-schema
  [{:db/ident
    :eacl.permission/resource-type+source-relation-name+target-type+permission-name
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/source-relation-name
                    :eacl.permission/target-type
                    :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident
    :eacl.permission/resource-type+source-relation-name+target-type+target-name
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/source-relation-name
                    :eacl.permission/target-type
                    :eacl.permission/target-name]
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident
    :eacl.permission/resource-type+source-relation-name+target-type+target-name+permission-name
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/source-relation-name
                    :eacl.permission/target-type
                    :eacl.permission/target-name
                    :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}])

(def v8-only-permission-idents
  #{:eacl/permission-storage-version
    :eacl.permission/expression-payload
    :eacl.permission/resource-type+permission-name})

(def released-v7-schema
  (vec
   (concat
    (remove #(contains? v8-only-permission-idents (:db/ident %))
            schema/v7-schema)
    legacy-permission-index-schema)))

(def schema-string
  "definition user {}
   definition document {
     relation reader: user
     permission view = reader
   }")

(def flat-definitions
  [(base/Relation :document :reader :user)
   (base/Permission :document :view {:relation :reader})])

(defn- relationship-content [db]
  (->> relationship-storage/attributes
       (mapcat #(d/datoms db :aevt %))
       (mapv (juxt :e :a :v))
       (sort-by pr-str)
       vec))

(defn- relationship-digest [db]
  (secure/canonical-records-digest
   "eacl/v7-to-v8/relationship-tuples/v1"
   (relationship-content db)))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo exception
      (ex-data exception))))

(defn- final-permission-swap? [tx-data]
  (boolean
   (some #(and (map? %)
               (= 8 (:eacl/permission-storage-version %)))
         tx-data)))

(defn- populate-v7! [conn]
  @(d/transact conn flat-definitions)
  @(d/transact conn [{:eacl/id "user-1"} {:eacl/id "document-1"}])
  (let [db (d/db conn)
        user (d/entid db [:eacl/id "user-1"])
        document (d/entid db [:eacl/id "document-1"])
        relation (d/entid db [:eacl/id
                              (base/->relation-id
                               :document :reader :user)])]
    @(d/transact
      conn
      [[:db/add user relationship-storage/forward-attribute
        [:user relation :document document]]
       [:db/add document relationship-storage/reverse-attribute
        [:document relation :user user]]])))

(deftest permission-only-upgrade-preserves-v7-relationship-tuples-test
  (with-mem-conn [conn released-v7-schema]
    (populate-v7! conn)
    (let [before (relationship-content (d/db conn))
          before-digest (relationship-digest (d/db conn))
          relationship-index-reads (atom 0)
          original-datoms d/datoms
          original-index-range d/index-range]
      (is (= :flat (schema/permission-storage-shape (d/db conn))))
      (let [report
            (with-redefs
             [d/datoms
              (fn [db index & components]
                (when (some relationship-storage/attributes components)
                  (swap! relationship-index-reads inc))
                (apply original-datoms db index components))
              d/index-range
              (fn [db attribute start end]
                (when (contains? relationship-storage/attributes attribute)
                  (swap! relationship-index-reads inc))
                (original-index-range db attribute start end))]
              (migration/migrate! conn {:schema schema-string}))]
        (is (= :migrated (:status report)))
        (is (= 0 (:relationships-touched report))))
      (is (zero? @relationship-index-reads))
      (is (= before (relationship-content (d/db conn))))
      (is (= before-digest (relationship-digest (d/db conn))))
      (is (= :expression (schema/permission-storage-shape (d/db conn))))
      (is (= 8 (migration/stamped-permission-storage-version (d/db conn))))
      (is (every? nil?
                  (map #(d/entid (d/db conn) %)
                       persistence/retired-expression-attributes)))
      (let [acl (eacl.datomic/make-client conn {})]
        (is (true?
             (eacl/can? acl
                        (spice-object :user "user-1")
                        :view
                        (spice-object :document "document-1"))))))))

(deftest startup-requires-explicit-v7-permission-upgrade-test
  (with-mem-conn [conn released-v7-schema]
    (populate-v7! conn)
    (let [error (try (eacl.datomic/make-client conn {}) nil
                     (catch clojure.lang.ExceptionInfo exception
                       (ex-data exception)))]
      (is (= :eacl/permission-storage-version (:type error))))
    (is (some? (eacl.datomic/make-client
                conn {:auto-migrate-v7 {:schema schema-string}})))
    (is (= :expression (schema/permission-storage-shape (d/db conn))))))

(deftest v7-upgrade-applies-invoking-client-expression-limits-test
  (with-mem-conn [conn released-v7-schema]
    (populate-v7! conn)
    (let [failure
          (error-data
           #(migration/migrate!
             conn {:schema schema-string
                   :expression-limits {:maximum-source-nodes 0}}))]
      (is (= :eacl.schema/expression-limit (:type failure)))
      (is (= :node-count (:dimension failure)))
      (is (= :flat (schema/permission-storage-shape (d/db conn))))
      (is (nil? (d/entid (d/db conn)
                         :eacl.permission/expression-payload)))
      (is (nil? (migration/stamped-permission-storage-version
                 (d/db conn)))))))

(deftest invalid-or-relation-changing-upgrade-leaves-v7-permissions-active-test
  (doseq [candidate
          ["definition user {}
            definition document {
              relation reader: user
              permission view = missing
            }"
           "definition user {}
            definition group {}
            definition document {
              relation reader: group
              permission view = reader
            }"]]
    (with-mem-conn [conn released-v7-schema]
      (populate-v7! conn)
      (let [before (relationship-content (d/db conn))
            old-permissions (schema/read-permissions (d/db conn))
            error (try (migration/migrate! conn {:schema candidate}) nil
                       (catch clojure.lang.ExceptionInfo exception
                         (ex-data exception)))]
        (is (map? error))
        (is (= :flat (schema/permission-storage-shape (d/db conn))))
        (is (= old-permissions (schema/read-permissions (d/db conn))))
        (is (= before (relationship-content (d/db conn))))
        (is (nil? (migration/stamped-permission-storage-version
                   (d/db conn))))))))

(def ^:private two-relation-flat-definitions
  "Released-v7 rows: reader and writer relations, `view` granted by reader
  only."
  [(base/Relation :document :reader :user)
   (base/Relation :document :writer :user)
   (base/Permission :document :view {:relation :reader})])

(defn- populate-two-relation-v7! [conn]
  @(d/transact conn two-relation-flat-definitions))

(deftest semantically-divergent-replacement-schema-is-rejected-test
  (with-mem-conn [conn released-v7-schema]
    (populate-two-relation-v7! conn)
    (let [old-permissions (schema/read-permissions (d/db conn))
          error
          (error-data
           #(migration/migrate!
             conn
             {:schema "definition user {}
                       definition document {
                         relation reader: user
                         relation writer: user
                         permission view = writer
                       }"}))]
      (is (= :eacl.migration/permission-semantic-change (:type error)))
      (is (= (:type error) (:eacl/error error)))
      (is (= [[:document :view]] (:divergent-permissions error))
          "the rejection names the divergent permission")
      (is (= [] (:removed-permissions error)))
      (is (= :flat (schema/permission-storage-shape (d/db conn)))
          "the stored v7 rows stay active")
      (is (= old-permissions (schema/read-permissions (d/db conn))))
      (is (nil? (migration/stamped-permission-storage-version (d/db conn)))
          "no storage version was stamped"))))

(deftest replacement-schema-removing-a-stored-permission-is-rejected-test
  (with-mem-conn [conn released-v7-schema]
    (populate-two-relation-v7! conn)
    (let [error
          (error-data
           #(migration/migrate!
             conn
             {:schema "definition user {}
                       definition document {
                         relation reader: user
                         relation writer: user
                       }"}))]
      (is (= :eacl.migration/permission-semantic-change (:type error)))
      (is (= [[:document :view]] (:removed-permissions error)))
      (is (= :flat (schema/permission-storage-shape (d/db conn)))))))

(deftest equivalent-and-additive-replacement-schema-migrates-test
  (testing "union arms compare as a set, so declaration order is free"
    (with-mem-conn [conn released-v7-schema]
      @(d/transact conn [(base/Relation :document :reader :user)
                         (base/Relation :document :writer :user)
                         (base/Permission :document :view
                                          {:relation :reader})
                         (base/Permission :document :view
                                          {:relation :writer})])
      (let [report
            (migration/migrate!
             conn
             {:schema "definition user {}
                       definition document {
                         relation reader: user
                         relation writer: user
                         permission view = writer + reader
                       }"})]
        (is (= :migrated (:status report)))
        (is (= :expression (schema/permission-storage-shape (d/db conn)))))))
  (testing "permissions present only in the replacement are additive"
    (with-mem-conn [conn released-v7-schema]
      (populate-two-relation-v7! conn)
      (let [report
            (migration/migrate!
             conn
             {:schema "definition user {}
                       definition document {
                         relation reader: user
                         relation writer: user
                         permission view = reader
                         permission archive = writer
                       }"})]
        (is (= :migrated (:status report)))
        (is (= 8 (migration/stamped-permission-storage-version
                  (d/db conn))))))))

(deftest no-op-migration-reports-the-outcome-and-stamped-version-test
  (with-mem-conn [conn released-v7-schema]
    ;; A v7-era database: relations and a schema generation exist, no
    ;; permission rows, and no permission-storage stamp was ever written.
    @(d/transact conn [(base/Relation :document :reader :user)
                       {:eacl/id "schema-string"
                        :eacl/schema-version (d/squuid)}])
    (let [report
          (migration/migrate!
           conn
           {:schema "definition user {}
                     definition document {
                       relation reader: user
                     }"})]
      (is (= :no-op (:status report))
          "a migration that transacts nothing names the no-op outcome")
      (is (nil? (:permission-storage-version report))
          "no transaction stamped a storage version, so none is reported")
      (is (= (migration/stamped-permission-storage-version (d/db conn))
             (:permission-storage-version report))))))

(deftest mixed-permission-storage-is-rejected-test
  (with-mem-conn [conn released-v7-schema]
    (populate-v7! conn)
    @(d/transact conn schema/v7-schema)
    (let [other
          (-> "definition user {}
               definition photo {
                 relation viewer: user
                 permission see = viewer
               }"
              resolver/validate-schema
              persistence/candidate-schema
              :permissions
              first)]
      @(d/transact conn [other])
      (is (= :mixed (schema/permission-storage-shape (d/db conn))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (migration/migrate! conn {:schema schema-string})))
      (is (= :mixed (schema/permission-storage-shape (d/db conn)))))))

(deftest corrupt-legacy-row-is-rejected-before-replacement-test
  (with-mem-conn [conn released-v7-schema]
    (populate-v7! conn)
    (let [permission-id (:eacl/id (second flat-definitions))
          before-relationships (relationship-content (d/db conn))]
      @(d/transact
        conn
        [{:db/id [:eacl/id permission-id]
          :eacl.permission/target-type :not-a-valid-target-type}])
      (is (= :eacl.schema/invalid-reference
             (:type (error-data
                     #(migration/migrate!
                       conn {:schema schema-string})))))
      (is (= :flat (schema/permission-storage-shape (d/db conn))))
      (is (= before-relationships (relationship-content (d/db conn))))
      (is (nil? (migration/stamped-permission-storage-version
                 (d/db conn)))))))

(deftest conflicting-authoritative-attribute-is-rejected-before-swap-test
  (with-mem-conn [conn released-v7-schema]
    (populate-v7! conn)
    @(d/transact
      conn
      [{:db/ident :eacl.permission/expression-payload
        :db/valueType :db.type/long
        :db/cardinality :db.cardinality/one}])
    (let [before-permissions (schema/read-permissions (d/db conn))
          before-relationships (relationship-content (d/db conn))
          error (error-data
                 #(migration/migrate! conn {:schema schema-string}))]
      (is (= :eacl.migration/attribute-conflict (:type error)))
      (is (= :eacl.permission/expression-payload (:attribute error)))
      (is (= before-permissions (schema/read-permissions (d/db conn))))
      (is (= before-relationships (relationship-content (d/db conn))))
      (is (nil? (migration/stamped-permission-storage-version
                 (d/db conn)))))))

(deftest rejected-final-transaction-never-exposes-mixed-permissions-test
  (with-mem-conn [conn released-v7-schema]
    (populate-v7! conn)
    (let [original-transact d/transact
          before-permissions (schema/read-permissions (d/db conn))
          before-relationships (relationship-content (d/db conn))
          error
          (with-redefs
           [d/transact
            (fn [connection tx-data]
              (if (final-permission-swap? tx-data)
                (throw (ex-info "injected rejection"
                                {:type :test/transaction-rejected}))
                (original-transact connection tx-data)))]
            (error-data
             #(migration/migrate! conn {:schema schema-string})))]
      (is (= :test/transaction-rejected (:type error)))
      (is (= :flat (schema/permission-storage-shape (d/db conn))))
      (is (= before-permissions (schema/read-permissions (d/db conn))))
      (is (= before-relationships (relationship-content (d/db conn))))
      (is (nil? (migration/stamped-permission-storage-version
                 (d/db conn)))))))

(deftest cas-race-fails-closed-and-a-fresh-retry-succeeds-test
  (with-mem-conn [conn released-v7-schema]
    (populate-v7! conn)
    @(d/transact
      conn
      [{:eacl/id "schema-string"
        :eacl/schema-version (d/squuid)}])
    (let [original-transact d/transact
          injected? (atom false)
          before-relationships (relationship-content (d/db conn))
          error
          (with-redefs
           [d/transact
            (fn [connection tx-data]
              (when (and (final-permission-swap? tx-data)
                         (compare-and-set! injected? false true))
                @(original-transact
                  connection
                  [{:eacl/id "schema-string"
                    :eacl/schema-version (d/squuid)}]))
              (original-transact connection tx-data))]
            (error-data
             #(migration/migrate! conn {:schema schema-string})))]
      (is (= :eacl.schema/concurrent-write (:type error)))
      (is (= :flat (schema/permission-storage-shape (d/db conn))))
      (is (= before-relationships (relationship-content (d/db conn))))
      (is (nil? (migration/stamped-permission-storage-version
                 (d/db conn))))
      (is (= :migrated
             (:status
              (migration/migrate! conn {:schema schema-string}))))
      (is (= :expression (schema/permission-storage-shape (d/db conn))))
      (is (= before-relationships (relationship-content (d/db conn)))))))

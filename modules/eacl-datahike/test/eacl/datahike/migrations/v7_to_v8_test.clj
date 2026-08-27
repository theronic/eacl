(ns eacl.datahike.migrations.v7-to-v8-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.migrations.v7-to-v8 :as migration]
            [eacl.datahike.schema :as schema]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.expression-persistence :as persistence]
            [eacl.schema.expression-resolver :as resolver]
            [eacl.schema.model :as model]))

(def ^:private released-v7-schema
  (vec
   (remove #(= :eacl.permission/expression-payload (:db/ident %))
           schema/datahike-schema)))

(def ^:private schema-string
  "definition user {}
   definition server {
     relation owner: user
     relation manager: user
     permission view = owner + manager
   }")

(def ^:private divergent-schema-string
  "definition user {}
   definition server {
     relation owner: user
     relation manager: user
     permission view = manager
   }")

(defn- new-v7-conn
  [attribute-refs?]
  (let [config (assoc (update schema/default-config
                              :store merge {:id (random-uuid)})
                      :attribute-refs? attribute-refs?)]
    (d/create-database config)
    (let [conn (d/connect config)]
      (d/transact conn released-v7-schema)
      conn)))

(defn- populate-v7!
  [conn]
  (let [candidate (-> schema-string
                      resolver/validate-schema
                      persistence/candidate-schema)
        relations (mapv #(assoc % :eacl/relation-version :db/current-tx)
                        (:relations candidate))
        permissions [(model/Permission :server :view {:relation :owner})
                     (model/Permission :server :view {:relation :manager})]]
    (d/transact
     conn
     (into
      [{:eacl/id "schema-string"
        :eacl/schema-string schema-string
        :eacl/schema-generation :db/current-tx
        :eacl/schema-write-fence :db/current-tx}]
      (concat relations permissions)))
    (d/transact conn [{:eacl/id "user-1"} {:eacl/id "server-1"}])
    (let [db (d/db conn)
          user (ddb/entid db [:eacl/id "user-1"])
          server (ddb/entid db [:eacl/id "server-1"])
          relation (ddb/entid
                    db [:eacl/id (model/->relation-id
                                  :server :owner :user)])]
      (d/transact
       conn
       [[:db/add user relationship-storage/forward-attribute
         [:user relation :server server]]
        [:db/add server relationship-storage/reverse-attribute
         [:server relation :user user]]]))))

(defn- relationship-content
  [db]
  (->> relationship-storage/attributes
       (mapcat #(ddb/avet-datoms db %))
       (mapv (juxt :e :a :v))
       (sort-by pr-str)
       vec))

(defn- exception-data
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- release-and-delete!
  [conn]
  (let [config (:config (d/db conn))]
    (d/release conn)
    (d/delete-database config)))

(deftest permission-only-migration-preserves-relationships-test
  (doseq [[label attribute-refs?]
          [["keyword attributes" false]
           ["numeric attribute refs" true]]]
    (testing label
      (let [conn (new-v7-conn attribute-refs?)]
        (try
          (populate-v7! conn)
          (let [before (relationship-content (d/db conn))
                relationship-index-reads (atom 0)
                original-datoms d/datoms
                report
                (with-redefs
                 [d/datoms
                  (fn [db & args]
                    (let [components (:components (first args))]
                      (when (some relationship-storage/attributes components)
                        (swap! relationship-index-reads inc)))
                    (apply original-datoms db args))]
                  (migration/migrate! conn))]
            (is (= :migrated (:status report)))
            (is (= 2 (:permission-retractions report)))
            (is (= 1 (:permission-additions report)))
            (is (zero? (:relationships-touched report)))
            (is (zero? @relationship-index-reads))
            (is (= before (relationship-content (d/db conn))))
            (is (= :expression
                   (schema/permission-storage-shape (d/db conn))))
            (is (= 1 (count (schema/read-permissions (d/db conn)))))
            (let [client (datahike/make-client conn {})]
              (is (true?
                   (eacl/can? client
                              (spice-object :user "user-1")
                              :view
                              (spice-object :server "server-1"))))))
          (finally
            (release-and-delete! conn)))))))

(deftest startup-fails-closed-and-can-explicitly-auto-migrate-test
  (let [conn (new-v7-conn true)]
    (try
      (populate-v7! conn)
      (let [failure (exception-data #(datahike/make-client conn {}))]
        (is (= :eacl/permission-storage-version (:type failure)))
        (is (= :flat-v7 (:detected failure))))
      (is (some? (datahike/make-client conn {:auto-migrate-v7 true})))
      (is (= :expression
             (schema/permission-storage-shape (d/db conn))))
      (finally
        (release-and-delete! conn)))))

(deftest divergent-schema-is-rejected-before-additive-install-test
  (let [conn (new-v7-conn true)]
    (try
      (populate-v7! conn)
      (let [before (schema/read-permissions (d/db conn))
            failure
            (exception-data
             #(migration/migrate! conn {:schema divergent-schema-string}))]
        (is (= :eacl.migration/permission-semantic-change (:type failure)))
        (is (= :flat (schema/permission-storage-shape (d/db conn))))
        (is (= before (schema/read-permissions (d/db conn))))
        (is (nil? (ddb/entid
                   (d/db conn) :eacl.permission/expression-payload))))
      (finally
        (release-and-delete! conn)))))

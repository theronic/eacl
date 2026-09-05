(ns eacl.datahike.qualifiers
  "Prepared concrete refs; direct native writers only."
  (:require [eacl.datahike.db :as native-db]
            [datahike.api :as d]
            [eacl.datahike.db :as db]
            [eacl.datahike.backend :as backend]
            [eacl.datahike.schema :as schema]
            [eacl.relationships.staged :as staged]
            [eacl.schema.qualification-admission :as admission]))

(defn- facts [database eid] (native-db/entity-facts database eid))

(defn- entity [database eid] (native-db/entity-data database eid))

(defn- assert-entity [database {:keys [eid expected]}]
  (when-not (= expected (facts database eid)) (staged/error! :qualifier-changed-at-commit))
  [])

(defn- schema-fence [database reference-added?]
  (let [schema-id (db/entid database [:eacl/id "schema-string"])
        expected (:eacl/schema-write-fence (entity database schema-id))]
    (when-not expected (staged/error! :schema-unprepared))
    (cond-> [[:db.fn/cas schema-id (db/attr-repr database :eacl/schema-write-fence) expected expected]]
      reference-added? (conj [:db/add schema-id :eacl/schema-write-fence :db/current-tx]))))

(defn- relation-fence [database relation-id]
  (when relation-id
    (let [expected (:eacl/relation-version (entity database relation-id))]
      [[:db.fn/cas relation-id (db/attr-repr database :eacl/relation-version) expected expected]
       [:db/add relation-id :eacl/relation-version :db/current-tx]])))

(defn- fence [database relation-id reference-added?]
  (into (schema-fence database reference-added?) (relation-fence database relation-id)))

(defn read-api
  "Read-only native inputs; constructing this map never prepares or writes a store."
  []
  {:backend :datahike :entity entity :facts facts :rows db/relationship-identity-datoms
   :source backend/database-source-scope :generation schema/current-schema-generation
   :all-rows (fn [database attribute] (when (db/entid database attribute) (d/datoms database {:index :aevt :components [attribute]})))
   :relation-version-attribute :eacl/relation-version
   :revision :max-tx
   :head-guard (fn [database]
                 [:db.fn/call (fn [current]
                                (when-not (= (:max-tx database) (:max-tx current))
                                  (staged/error! :cleanup-source-changed))
                                [])])
   :qualifier-cache-scope :assertion-version
   :qualifier-version (fn [database eid] (some-> (d/datoms database {:index :eavt :components [eid :eacl.relationship-qualifier/format-version]}) first :tx))})

(defn planner-api
  "Pure native reads and transaction-data construction, safe for snapshots."
  []
  (let [tempids (atom -1000000000)]
    (merge (read-api)
           {:strategy :prepared :fence fence
            :schema-fence schema-fence :relation-fence relation-fence
           ;; Keep the eid inside a map: Datahike treats call slot three as an attribute.
            :assert-entity (fn [eid expected] [:db.fn/call assert-entity {:eid eid :expected expected}])
            :tempid #(swap! tempids dec)})))

(defn plan
  "Builds qualified transaction data from one immutable basis without writing."
  [database entries app-datoms]
  (staged/plan-batch (staged/planner (planner-api) database) database entries app-datoms))

(defn writer [conn]
  (when-not (db/direct-writer? (d/db conn)) (staged/error! :unsupported-backend))
  (schema/prepare-cache-coherence! conn)
  (staged/native-writer
   (merge (planner-api) {:snapshot #(d/db conn) :transact! #(d/transact conn %)})))

(defn publication-capability [database]
  (when (db/direct-writer? database)
    (admission/publication-descriptor :prepared)))

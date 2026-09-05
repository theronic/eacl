(ns eacl.datomic.qualifiers
  "Certified inline native tuple-ref publication for non-serving staging."
  (:require [datomic.api :as d]
            [eacl.datomic.db :as db]
            [eacl.relationships.staged :as staged]))

(defn- facts [database eid]
  (mapv (fn [datom] [(:a datom) (:v datom) (:tx datom)]) (d/datoms database :eavt eid)))

(defn- entity [database eid]
  (let [rows (facts database eid)]
    (when (seq rows)
      (reduce (fn [result [a v]]
                (let [attribute (:db/ident (d/entity database a))]
                  (if (= :eacl.relation/caveats attribute)
                    (update result attribute (fnil conj #{}) v) (assoc result attribute v))))
              {:db/id eid} rows))))

(defn- generation [database]
  (:eacl/schema-version (d/entity database [:eacl/id "schema-string"])))

(defn- schema-fence [database reference-added?]
  (let [schema-id (d/entid database [:eacl/id "schema-string"])
        expected (generation database)]
    (when-not expected (staged/error! :schema-unprepared))
    ;; One schema CAS per batch also serializes concurrent Caveat removal.
    [[:db.fn/cas schema-id :eacl/schema-version expected (if reference-added? (d/squuid) expected)]]))

(defn- relation-fence [database relation-id]
  (when relation-id
    [[:db.fn/cas relation-id :eacl/relation-version
      (:eacl/relation-version (entity database relation-id)) "datomic.tx"]]))

(defn- fence [database relation-id reference-added?]
  (into (schema-fence database reference-added?) (relation-fence database relation-id)))

(defn read-api
  "Read-only native inputs; constructing this map never prepares or writes a store."
  []
  {:backend :datomic :entity entity :facts facts :rows db/relationship-identity-datoms
   :source (fn [database] (str (.id ^datomic.Database database))) :generation generation
   :all-rows (fn [database attribute] (when (d/entid database attribute) (d/datoms database :aevt attribute)))
   :relation-version-attribute :eacl/relation-version
   :revision d/basis-t
   :head-guard (fn [database] [:eacl.fn/assert-storage-basis (d/basis-t database)])
   :qualifier-cache-scope :assertion-version
   :qualifier-version (fn [database eid] (some-> (d/datoms database :eavt eid :eacl.relationship-qualifier/format-version) first :tx))})

(defn planner-api
  "Pure native reads and transaction-data construction, safe for snapshots."
  []
  (merge (read-api)
         {:strategy :inline :fence fence
          :schema-fence schema-fence :relation-fence relation-fence
          :assert-entity (fn [eid expected] [:eacl.fn/assert-qualifier-facts eid expected])
          :tempid #(str "eacl-qualifier-" (random-uuid))}))

(defn plan
  "Builds qualified transaction data from one immutable basis without writing."
  [database entries app-datoms]
  (staged/plan-batch (staged/planner (planner-api) database) database entries app-datoms))

(defn writer [conn]
  (when-not (d/entid (d/db conn) :eacl.fn/assert-qualifier-facts) (staged/error! :schema-unprepared))
  (staged/native-writer
   (merge (planner-api)
          {:snapshot #(d/db conn)
           :generation-after-reference #(generation (:db-after %))
           :transact! #(deref (d/transact conn %))})))

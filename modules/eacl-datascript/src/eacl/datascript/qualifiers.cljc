(ns eacl.datascript.qualifiers
  "Certified prepared-reference publication for explicitly non-serving data."
  (:require [datascript.core :as ds]
            [eacl.datascript.schema :as schema]
            [eacl.relationships.staged :as staged]))

(defn- facts [database eid]
  (mapv (fn [datom] [(:a datom) (:v datom) (:tx datom)]) (ds/datoms database :eavt eid)))

(defn- entity [database eid]
  (let [rows (facts database eid)]
    (when (seq rows)
      (reduce (fn [result [a v]]
                (if (= :eacl.relation/caveats a) (update result a (fnil conj #{}) v) (assoc result a v)))
              {:db/id eid} rows))))

(defn- assert-entity [database eid expected]
  (when-not (= expected (facts database eid)) (staged/error! :qualifier-changed-at-commit))
  [])

(defn- identity-rows [database owner attribute value]
  (let [prefix (subvec value 0 4)]
    ;; DataScript vectors have no native ref-type enforcement. Retain a row
    ;; whose fifth component is malformed so the staged pair check rejects it.
    (take-while (fn [datom]
                  (let [v (:v datom)]
                    (and (= owner (:e datom)) (= attribute (:a datom))
                         (vector? v) (= 5 (count v)) (= prefix (subvec v 0 4)))))
                (ds/seek-datoms database :eavt owner attribute (conj prefix nil)))))

(defn- schema-fence [database reference-added?]
  (let [schema-id (ds/entid database [:eacl/id "schema-string"])
        expected (:eacl/schema-write-fence (entity database schema-id))]
    (when-not expected (staged/error! :schema-unprepared))
    (cond-> [[:db.fn/cas schema-id :eacl/schema-write-fence expected expected]]
      reference-added? (conj [:db/add schema-id :eacl/schema-write-fence :db/current-tx]))))

(defn- relation-fence [database relation-id]
  (when relation-id
    (let [expected (:eacl/relation-version (entity database relation-id))]
      [[:db.fn/cas relation-id :eacl/relation-version expected expected]
       [:db/add relation-id :eacl/relation-version :db/current-tx]])))

(defn- fence [database relation-id reference-added?]
  (into (schema-fence database reference-added?) (relation-fence database relation-id)))

(defn read-api
  "Read-only native inputs; constructing this map never prepares or writes a store."
  []
  {:backend :datascript :entity entity :facts facts :rows identity-rows
   :source (fn [database] (get (ds/entity database [:eacl/id "datascript-metadata"]) :eacl.datascript/source-id)) :generation schema/current-schema-generation
   :all-rows (fn [database attribute] (ds/datoms database :aevt attribute))
   :relation-version-attribute :eacl/relation-version
   :revision :max-tx
   :head-guard (fn [database]
                 [:db.fn/call (fn [current]
                                (when-not (and (= (:max-tx database) (:max-tx current)) (= (:schema database) (:schema current)))
                                  (staged/error! :cleanup-source-changed))
                                [])])
   :qualifier-cache-scope :assertion-version
   :qualifier-version (fn [database eid] (some-> (ds/datoms database :eavt eid :eacl.relationship-qualifier/format-version) first :tx))})

(defn planner-api
  "Pure native reads and transaction-data construction, safe for snapshots."
  []
  (merge (read-api)
         {:strategy :prepared :fence fence
          :schema-fence schema-fence :relation-fence relation-fence
          :assert-entity (fn [eid expected] [:db.fn/call assert-entity eid expected])
          :tempid #(str "eacl-qualifier-" (random-uuid))}))

(defn plan
  "Builds qualified transaction data from one immutable basis without writing."
  [database entries app-datoms]
  (staged/plan-batch (staged/planner (planner-api) database) database entries app-datoms))

(defn writer [conn]
  (schema/prepare-cache-coherence! conn)
  (staged/native-writer
   (merge (planner-api)
          {:snapshot #(ds/db conn) :transact! #(ds/transact! conn %)})))

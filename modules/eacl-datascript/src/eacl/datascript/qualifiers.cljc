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

(defn- fence [database relation-id reference-added?]
  (let [schema-id (ds/entid database [:eacl/id "schema-string"])
        expected (:eacl/schema-write-fence (entity database schema-id))]
    (when-not expected (staged/error! :schema-unprepared))
    (cond-> [[:db.fn/cas schema-id :eacl/schema-write-fence expected expected]]
      reference-added? (conj [:db/add schema-id :eacl/schema-write-fence :db/current-tx])
      relation-id
      (into [[:db.fn/cas relation-id :eacl/relation-version
              (:eacl/relation-version (entity database relation-id))
              (:eacl/relation-version (entity database relation-id))]
             [:db/add relation-id :eacl/relation-version :db/current-tx]]))))

(defn writer [conn]
  (schema/prepare-cache-coherence! conn)
  (staged/native-writer
    {:backend :datascript :strategy :prepared
     :snapshot #(ds/db conn) :entity entity :facts facts
     :source #(get (ds/entity % [:eacl/id "datascript-metadata"]) :eacl.datascript/source-id)
     :rows identity-rows :generation schema/current-schema-generation
     :fence fence :assert-entity (fn [eid expected] [:db.fn/call assert-entity eid expected])
     :tempid #(str "eacl-qualifier-" (random-uuid))
     :transact! #(ds/transact! conn %)}))

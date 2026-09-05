(ns eacl.datalevin.qualifiers
  "Certified inline native refs under the embedded store's write policy."
  (:require [datalevin.core :as d]
            [eacl.datalevin.db :as db]
            [eacl.datalevin.schema :as schema]
            [eacl.relationships.staged :as staged]))

(defn- facts [database eid]
  (mapv (fn [datom] [(:a datom) (:v datom) (:tx datom)]) (d/datoms database :eav eid)))

(defn- entity [database eid]
  (let [rows (facts database eid)]
    (when (seq rows)
      (reduce (fn [result [a v]]
                (if (= :eacl.relation/caveats a) (update result a (fnil conj #{}) v) (assoc result a v)))
              {:db/id eid} rows))))

(defn- assert-entity [database eid expected]
  (when-not (= expected (facts database eid)) (staged/error! :qualifier-changed-at-commit))
  [])

(defn- fence [database relation-id reference-added?]
  (let [schema-id (d/entid database [:eacl/id "schema-string"])
        expected (:eacl.datalevin/schema-write-fence (entity database schema-id))]
    (when-not expected (staged/error! :schema-unprepared))
    (cond-> [[:db.fn/cas schema-id :eacl.datalevin/schema-write-fence expected expected]]
      reference-added? (conj [:db/add schema-id :eacl.datalevin/schema-write-fence :db/current-tx])
      relation-id (into [[:db.fn/cas relation-id :eacl.datalevin/relation-generation
                          (:eacl.datalevin/relation-generation (entity database relation-id))
                          (:eacl.datalevin/relation-generation (entity database relation-id))]
                         [:db/add relation-id :eacl.datalevin/relation-generation :db/current-tx]]))))

(defn writer [conn]
  (let [token (:write-token (schema/ensure-physical-schema! conn))
        tempids (atom -1000000000)]
    (staged/native-writer
      {:backend :datalevin :strategy :inline :snapshot #(d/db conn) :entity entity :facts facts
       :source #(get (d/entity % [:eacl/id "datalevin-metadata"]) :eacl.datalevin/source-id)
       :with-snapshot (fn [f]
                        (let [snapshot (d/open-read-snapshot conn)]
                          (try (d/with-read-snapshot snapshot f)
                               (finally (d/close-read-snapshot! snapshot)))))
       :rows db/relationship-identity-datoms :generation schema/current-schema-generation :fence fence
       :assert-entity (fn [eid expected] [:db.fn/call assert-entity eid expected])
       :tempid #(swap! tempids dec)
       :transact! #(d/transact! conn % {:datalevin/write-token token})})))

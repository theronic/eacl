(ns eacl.datalevin.qualifiers
  "Certified inline native refs under the embedded store's write policy."
  (:require [eacl.datalevin.db :as native-db]
            [datalevin.core :as d]
            [eacl.datalevin.db :as db]
            [eacl.datalevin.schema :as schema]
            [eacl.relationships.staged :as staged]
            [eacl.schema.qualification-admission :as admission]))

(defn- facts [database eid] (native-db/entity-facts database eid))

(defn- entity [database eid] (native-db/entity-data database eid))

(defn- assert-entity [database eid expected]
  (when-not (= expected (facts database eid)) (staged/error! :qualifier-changed-at-commit))
  [])

(defn- schema-fence [database reference-added?]
  (let [schema-id (d/entid database [:eacl/id "schema-string"])
        expected (:eacl.datalevin/schema-write-fence (entity database schema-id))]
    (when-not expected (staged/error! :schema-unprepared))
    (cond-> [[:db.fn/cas schema-id :eacl.datalevin/schema-write-fence expected expected]]
      reference-added? (conj [:db/add schema-id :eacl.datalevin/schema-write-fence :db/current-tx]))))

(defn- relation-fence [database relation-id]
  (when relation-id
    (let [expected (:eacl.datalevin/relation-generation (entity database relation-id))]
      [[:db.fn/cas relation-id :eacl.datalevin/relation-generation expected expected]
       [:db/add relation-id :eacl.datalevin/relation-generation :db/current-tx]])))

(defn- fence [database relation-id reference-added?]
  (into (schema-fence database reference-added?) (relation-fence database relation-id)))

(defn read-api
  "Read-only native inputs; constructing this map never prepares or writes a store."
  []
  {:backend :datalevin :entity entity :facts facts :rows db/relationship-identity-datoms
   :source (fn [database] (get (d/entity database [:eacl/id "datalevin-metadata"]) :eacl.datalevin/source-id)) :generation schema/current-schema-generation
   :all-rows (fn [database attribute] (d/datoms database :ave attribute))
   :relation-version-attribute :eacl.datalevin/relation-generation
   :revision :max-tx
   :head-guard (fn [database]
                 [:db.fn/call (fn [current]
                                (when-not (= (:max-tx database) (:max-tx current))
                                  (staged/error! :cleanup-source-changed))
                                [])])
   :qualifier-cache-scope :exact-only
   :qualifier-version (fn [_database _eid] nil)})

(defn planner-api
  "Pure native reads and transaction-data construction, safe for snapshots."
  []
  (merge (read-api)
         {:strategy :inline :fence fence
          :schema-fence schema-fence :relation-fence relation-fence
          :assert-entity (fn [eid expected] [:db.fn/call assert-entity eid expected])
          :tempid #(d/tempid :db.part/user)}))

(defn plan
  "Builds qualified transaction data from one immutable basis without writing."
  ([database entries app-datoms]
   (db/with-db database
     (fn [db]
       (staged/plan-batch (staged/planner (planner-api) db) db entries app-datoms))))
  ([database entries app-datoms _source-scope]
   (plan database entries app-datoms)))

(defn writer [conn]
  (let [token (:write-token (schema/ensure-physical-schema! conn))]
    (staged/native-writer
     (merge (planner-api)
            {:snapshot #(d/db conn)
             :with-selected-snapshot db/with-db
             :with-snapshot (fn [f]
                              (let [snapshot (d/open-read-snapshot conn)]
                                (try (d/with-read-snapshot snapshot f)
                                     (finally (d/close-read-snapshot! snapshot)))))
             :transact! #(d/transact! conn % {:datalevin/write-token token})}))))

(defn publication-capability [database]
  (admission/publication-descriptor :inline))

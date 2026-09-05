(ns eacl.datahike.qualifiers
  "Prepared concrete refs; direct native writers only."
  (:require [datahike.api :as d]
            [eacl.datahike.db :as db]
            [eacl.datahike.backend :as backend]
            [eacl.datahike.schema :as schema]
            [eacl.relationships.staged :as staged]))

(defn- facts [database eid]
  (mapv (fn [datom] [(:a datom) (:v datom) (:tx datom)])
        (d/datoms database {:index :eavt :components [eid]})))

(defn- entity [database eid]
  (let [rows (facts database eid)]
    (when (seq rows)
      (reduce (fn [result [a v]]
                (let [attribute (if (keyword? a) a (:db/ident (d/entity database a)))]
                  (if (= :eacl.relation/caveats attribute)
                    (update result attribute (fnil conj #{}) v) (assoc result attribute v))))
              {:db/id eid} rows))))

(defn- assert-entity [database {:keys [eid expected]}]
  (when-not (= expected (facts database eid)) (staged/error! :qualifier-changed-at-commit))
  [])

(defn- fence [database relation-id reference-added?]
  (let [schema-id (db/entid database [:eacl/id "schema-string"])
        expected (:eacl/schema-write-fence (entity database schema-id))
        attribute #(db/attr-repr database %)]
    (when-not expected (staged/error! :schema-unprepared))
    (cond-> [[:db.fn/cas schema-id (attribute :eacl/schema-write-fence) expected expected]]
      reference-added? (conj [:db/add schema-id :eacl/schema-write-fence :db/current-tx])
      relation-id (into [[:db.fn/cas relation-id (attribute :eacl/relation-version)
                          (:eacl/relation-version (entity database relation-id))
                          (:eacl/relation-version (entity database relation-id))]
                         [:db/add relation-id :eacl/relation-version :db/current-tx]]))))

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

(defn writer [conn]
  (when-not (db/direct-writer? (d/db conn)) (staged/error! :unsupported-backend))
  (schema/prepare-cache-coherence! conn)
  (let [tempids (atom -1000000000)]
    (staged/native-writer
      (merge (read-api)
      {:strategy :prepared :snapshot #(d/db conn) :fence fence
       ;; Datahike examines tuple attributes before dispatching :db.fn/call;
       ;; an eid in argument slot three is mistaken for an attribute ref.
       :assert-entity (fn [eid expected] [:db.fn/call assert-entity {:eid eid :expected expected}])
       :tempid #(swap! tempids dec) :transact! #(d/transact conn %)}))))

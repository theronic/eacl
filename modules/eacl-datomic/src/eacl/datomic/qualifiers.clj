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

(defn- fence [database relation-id reference-added?]
  (let [schema-id (d/entid database [:eacl/id "schema-string"])
        expected (generation database)]
    (when-not expected (staged/error! :schema-unprepared))
    ;; Reference creation participates in the existing schema CAS fence. It
    ;; cannot race a Caveat removal that was checked before the reference existed.
    (cond-> [[:db.fn/cas schema-id :eacl/schema-version expected (if reference-added? (d/squuid) expected)]]
      relation-id (conj [:db.fn/cas relation-id :eacl/relation-version
                         (:eacl/relation-version (entity database relation-id)) "datomic.tx"]))))

(defn writer [conn]
  (when-not (d/entid (d/db conn) :eacl.fn/assert-qualifier-facts) (staged/error! :schema-unprepared))
  (staged/native-writer
    {:backend :datomic :strategy :inline :snapshot #(d/db conn) :entity entity :facts facts
     :source #(str (.id ^datomic.Database %))
     :rows db/relationship-identity-datoms :generation generation :fence fence
     :generation-after-reference #(generation (:db-after %))
     :assert-entity (fn [eid expected] [:eacl.fn/assert-qualifier-facts eid expected])
     :tempid #(str "eacl-qualifier-" (random-uuid))
     :transact! #(deref (d/transact conn %))}))

(ns eacl.datomic.storage
  (:require [datomic.api :as d]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.legacy-v7 :as legacy]
            [eacl.relationships.upgrade :as upgrade]))

(defn present? [db attr] (boolean (first (d/datoms db :aevt attr))))

(defn read-state [db]
  (when (d/entid db upgrade/state-attribute)
    (when-let [eid (d/entid db [:eacl/id upgrade/metadata-id])]
      (when-let [row (first (d/datoms db :eavt eid upgrade/state-attribute))]
        (assoc (upgrade/decode-state (:v row)) :expected-revision (d/tx->t (:tx row)))))))

(defn- tuple-schema-compatible? [db attr tuple-types]
  (let [definition (d/pull db '[{:db/valueType [:db/ident]} :db/tupleTypes
                               {:db/cardinality [:db/ident]} :db/index] attr)]
    (and (= :db.type/tuple (get-in definition [:db/valueType :db/ident]))
         (= tuple-types (:db/tupleTypes definition))
         (= :db.cardinality/many (get-in definition [:db/cardinality :db/ident]))
         (true? (:db/index definition)))))

(defn schema-compatible? [db attr]
  (tuple-schema-compatible? db attr storage/tuple-types))

(defn evidence [db]
  {:backend :datomic
   :version (when (d/entid db :eacl/storage-version)
              (:eacl/storage-version (d/entity db [:eacl/id upgrade/metadata-id])))
   :state (read-state db)
   :legacy? (boolean (some #(present? db %) legacy/attributes))
   :v6? (present? db :eacl.relationship/relation-name)
   :source-schema-compatible? (every? #(tuple-schema-compatible? db % legacy/tuple-types) legacy/attributes)
   :schema-compatible? (every? #(schema-compatible? db %) storage/attributes)})

(defn assert-compatible! [db] (upgrade/assert-compatible! (evidence db)))

(def basis-guard
  {:db/ident :eacl.fn/assert-storage-basis
   :db/fn (d/function
           {:lang "clojure" :params '[db expected]
            :code '(when-not (= expected (datomic.api/basis-t db))
                     (throw (ex-info "Concurrent write during Relationship storage upgrade."
                                     {:type :eacl.storage/upgrade-failed
                                      :eacl/error :eacl.storage/upgrade-failed
                                      :reason :concurrent-write})))} )})

(defn bootstrap! [conn]
  (let [db (d/db conn)
        {:keys [version state legacy? v6? schema-compatible?] :as found} (evidence db)]
    (cond
      (and (= 9 version) (= :complete (:phase state))) nil
      (and (nil? version) (nil? state) (not legacy?) (not v6?) schema-compatible?
           (not-any? #(present? db %) storage/attributes))
      @(d/transact conn (into [[:eacl.fn/assert-storage-basis (d/basis-t db)]]
                              (upgrade/bootstrap-tx (inc (d/basis-t db)))))
      :else (upgrade/assert-compatible! found))))

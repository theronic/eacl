(ns eacl.datahike.storage
  (:require [datahike.api :as d]
            [eacl.datahike.db :as db]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.legacy-v7 :as legacy]
            [eacl.relationships.upgrade :as upgrade]))

(defn present? [database attr]
  (and (db/entid database attr)
       (boolean (first (d/datoms database {:index :aevt :components [attr]})))))

(defn read-state [database]
  (when (db/entid database upgrade/state-attribute)
    (when-let [eid (db/entid database [:eacl/id upgrade/metadata-id])]
      (when-let [row (first (d/datoms database {:index :eavt :components [eid upgrade/state-attribute]}))]
        (assoc (upgrade/decode-state (:v row)) :expected-revision (:tx row))))))

(defn- tuple-schema-compatible? [database attr tuple-types]
  (let [definition (when (db/entid database attr) (d/pull database '[*] attr))]
    (and (= :db.type/tuple (let [value (:db/valueType definition)] (if (map? value) (:db/ident value) value)))
         (= tuple-types (:db/tupleTypes definition))
         (= :db.cardinality/many (let [value (:db/cardinality definition)] (if (map? value) (:db/ident value) value)))
         (true? (:db/index definition)))))

(defn schema-compatible? [database attr]
  (tuple-schema-compatible? database attr storage/tuple-types))

(defn evidence [database]
  {:backend :datahike
   :version (when (db/entid database :eacl/storage-version)
              (:eacl/storage-version (d/entity database [:eacl/id upgrade/metadata-id])))
   :state (read-state database)
   :legacy? (boolean (some #(present? database %) legacy/attributes))
   :v6? (boolean (some #(present? database %) [:eacl.relationship/relation-name :eacl.relationship/relation]))
   :source-schema-compatible? (every? #(tuple-schema-compatible? database % legacy/tuple-types) legacy/attributes)
   :schema-compatible? (every? #(schema-compatible? database %) storage/attributes)})

(defn assert-compatible! [database] (upgrade/assert-compatible! (evidence database)))

(defn bootstrap! [conn]
  (d/transact
   conn
   [[:db.fn/call
     (fn [database]
       (let [{:keys [version state legacy? v6? schema-compatible?] :as found} (evidence database)]
         (cond
           (and (= 9 version) (= :complete (:phase state))) []
           (and (nil? version) (nil? state) (not legacy?) (not v6?) schema-compatible?
                (not-any? #(present? database %) storage/attributes))
           (upgrade/bootstrap-tx (inc (:max-tx database)))
           :else (upgrade/assert-compatible! found))))]]))

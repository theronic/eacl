(ns eacl.datalevin.storage
  (:require [datalevin.core :as d]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.legacy-v7 :as legacy]
            [eacl.relationships.upgrade :as upgrade]))

(def metadata-schema
  (into {:eacl.storage/migration-generation {:db/valueType :db.type/long}}
        (map (fn [{:db/keys [ident valueType]}] [ident {:db/valueType valueType}])) upgrade/metadata-schema))

(defn present? [db attr]
  (and (contains? (d/schema db) attr)
       (= attr (:a (first (d/seek-datoms db :ave attr nil nil 1))))))

(defn read-state [db]
  (when (contains? (d/schema db) upgrade/state-attribute)
    (let [entity (d/entity db [:eacl/id upgrade/metadata-id])]
      (when-let [state (upgrade/decode-state (get entity upgrade/state-attribute))]
        (if-let [generation (:eacl.storage/migration-generation entity)]
          (assoc state :expected-revision generation)
          state)))))

(defn evidence [db]
  {:backend :datalevin
   :version (when (contains? (d/schema db) :eacl/storage-version)
              (:eacl/storage-version (d/entity db [:eacl/id upgrade/metadata-id])))
   :state (read-state db)
   :legacy? (boolean (some #(present? db %) legacy/attributes))
   :v6? (boolean (some #(present? db %) [:eacl.relationship/relation-name :eacl.relationship/relation]))
   :source-schema-compatible?
   (every? #(= {:db/valueType :db.type/tuple :db/tupleTypes legacy/tuple-types
                 :db/cardinality :db.cardinality/many :db/index true}
               (select-keys (get (d/schema db) %) [:db/valueType :db/tupleTypes :db/cardinality :db/index]))
           legacy/attributes)
   :schema-compatible?
   (every? #(= {:db/valueType :db.type/tuple :db/tupleTypes storage/tuple-types
                 :db/cardinality :db.cardinality/many :db/index true}
               (select-keys (get (d/schema db) %) [:db/valueType :db/tupleTypes :db/cardinality :db/index]))
           storage/attributes)})

(defn assert-compatible! [db] (upgrade/assert-compatible! (evidence db)))

(defn bootstrap! [conn]
  (d/transact!
   conn
   [[:db.fn/call
     (fn [db]
       (let [{:keys [version state legacy? v6? schema-compatible?] :as found} (evidence db)]
         (cond
           (and (= 9 version) (= :complete (:phase state))) []
           (and (nil? version) (nil? state) (not legacy?) (not v6?) schema-compatible?
                (not-any? #(present? db %) storage/attributes))
           (upgrade/bootstrap-tx (inc (:max-tx db)))
           :else (upgrade/assert-compatible! found))))]]))

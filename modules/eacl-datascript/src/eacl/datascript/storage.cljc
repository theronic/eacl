(ns eacl.datascript.storage
  "Bounded Relationship storage admission and explicit fresh bootstrap."
  (:require [datascript.core :as d]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.legacy-v7 :as legacy]
            [eacl.relationships.upgrade :as upgrade]))

(def metadata-schema
  (into {} (map (fn [{:db/keys [ident]}] [ident {}])) upgrade/metadata-schema))

(defn read-state [db]
  (when-let [eid (d/entid db [:eacl/id upgrade/metadata-id])]
    (when-let [row (first (d/datoms db :eavt eid upgrade/state-attribute))]
      (assoc (upgrade/decode-state (:v row)) :expected-revision (:tx row)))))

(defn evidence [db]
  {:backend :datascript
   :version (:eacl/storage-version (d/entity db [:eacl/id upgrade/metadata-id]))
   :state (read-state db)
   :legacy? (boolean (some #(first (d/datoms db :aevt %)) legacy/attributes))
   :v6? (boolean (some #(first (d/datoms db :aevt %))
                      [:eacl.relationship/relation-name :eacl.relationship/relation]))
   :source-schema-compatible?
   (every? #(= {:db/cardinality :db.cardinality/many :db/index true}
               (get (:schema db) %)) legacy/attributes)
   :schema-compatible?
   (every? #(= {:db/cardinality :db.cardinality/many :db/index true}
               (get (:schema db) %)) storage/attributes)})

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
                (not-any? #(first (d/datoms db :aevt %)) storage/attributes))
           (upgrade/bootstrap-tx (inc (:max-tx db)))
           :else (upgrade/assert-compatible! found))))]]))

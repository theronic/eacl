(ns eacl.datahike.migrations.v7-to-v9
  (:require [datahike.api :as d]
            [eacl.datahike.db :as db]
            [eacl.datahike.schema :as schema]
            [eacl.datahike.storage :as admission]
            [eacl.relationships.legacy-v7 :as legacy]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.upgrade :as upgrade]))

(defn- install! [conn]
  (let [database (d/db conn)
        definitions (concat upgrade/metadata-schema
                            (filter #(contains? storage/attributes (:db/ident %)) schema/datahike-schema))]
    (doseq [attr storage/attributes
            :when (and (db/entid database attr) (not (admission/schema-compatible? database attr)))]
      (upgrade/fail! :incompatible-schema {:attribute attr}))
    (when-let [missing (seq (remove #(db/entid database (:db/ident %)) definitions))]
      (d/transact conn missing))))

(defn- rows [database attr]
  (if (db/entid database attr) (d/datoms database {:index :aevt :components [attr]}) []))

(defn- commit! [conn before operations]
  (:db-after
   (d/transact conn [[:db.fn/call
                      (fn [current]
                        (upgrade/assert-head! {:expected-revision (:max-tx before)} (:max-tx current))
                        operations)]])))

(defn migrate!
  ([conn] (migrate! conn {}))
  ([conn options]
   (when-not (db/direct-writer? (d/db conn))
     (upgrade/fail! :unsupported-writer {:backend :datahike}))
   (upgrade/migrate!
    {:snapshot #(d/db conn) :revision :max-tx
     :evidence admission/evidence :read-state admission/read-state :install! #(install! conn)
     :read-pairs (fn [database version]
                   (let [[forward reverse] (if (= 7 version)
                                             [legacy/forward-attribute legacy/reverse-attribute]
                                             [storage/forward-attribute storage/reverse-attribute])]
                     {:forward (rows database forward) :reverse (rows database reverse)}))
     :source-batch #(take %2 (rows %1 legacy/forward-attribute))
     :entity-exists? db/entity-exists? :relation d/entity
     :relation-generation #(some-> (d/datoms %1 {:index :eavt :components [%2 :eacl/relation-version]}) first :v)
     :stamp #(vector :db/add % :eacl/relation-version :db/current-tx)
     :commit! #(commit! conn %1 %2)} options)))

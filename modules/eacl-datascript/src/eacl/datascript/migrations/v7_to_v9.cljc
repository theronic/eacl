(ns eacl.datascript.migrations.v7-to-v9
  "Explicit quiesced, restartable Relationship storage migration."
  (:require [datascript.core :as d]
            [eacl.datascript.db :as db]
            [eacl.datascript.schema :as schema]
            [eacl.datascript.storage :as admission]
            [eacl.relationships.legacy-v7 :as legacy]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.upgrade :as upgrade]))

(defn- install! [conn]
  (let [actual (:schema (d/db conn))
        target (select-keys schema/datascript-schema storage/attributes)]
    (doseq [[attr expected] target
            :let [present (get actual attr)]
            :when (and present (not= present expected))]
      (upgrade/fail! :incompatible-schema {:attribute attr}))
    (let [next-schema (merge actual admission/metadata-schema target)]
      (when-not (= actual next-schema) (d/reset-schema! conn next-schema)))))

(defn- commit! [conn before operations]
  (:db-after
   (d/transact!
    conn [[:db.fn/call
           (fn [current]
             (upgrade/assert-head! {:expected-revision (:max-tx before)} (:max-tx current))
             (when-not (= (:schema before) (:schema current))
               (upgrade/fail! :concurrent-schema-change {}))
             operations)]])))

(defn migrate!
  ([conn] (migrate! conn {}))
  ([conn options]
   (upgrade/migrate!
    {:snapshot #(d/db conn) :revision :max-tx
     :evidence admission/evidence :read-state admission/read-state
     :install! #(install! conn)
     :read-pairs (fn [database version]
                   (let [[forward reverse] (if (= 7 version)
                                             [legacy/forward-attribute legacy/reverse-attribute]
                                             [storage/forward-attribute storage/reverse-attribute])]
                     {:forward (d/datoms database :aevt forward)
                      :reverse (d/datoms database :aevt reverse)}))
     :source-batch #(take %2 (d/datoms %1 :aevt legacy/forward-attribute))
     :entity-exists? db/entity-exists? :relation d/entity
     :relation-generation #(some-> (d/datoms %1 :eavt %2 :eacl/relation-version) first :v)
     :stamp #(vector :db/add % :eacl/relation-version :db/current-tx)
     :commit! #(commit! conn %1 %2)} options)))

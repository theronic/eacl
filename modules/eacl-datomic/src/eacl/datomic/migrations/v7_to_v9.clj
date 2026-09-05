(ns eacl.datomic.migrations.v7-to-v9
  (:require [datomic.api :as d]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.storage :as admission]
            [eacl.relationships.legacy-v7 :as legacy]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.upgrade :as upgrade]))

(defn- install! [conn]
  (let [db (d/db conn)
        ;; The target runtime also needs the Caveat fields and native guarded
        ;; publication function. Install missing definitions during migration,
        ;; before the completed target storage marker can admit that runtime.
        definitions (vals (into {} (map (juxt :db/ident identity))
                                (concat upgrade/metadata-schema schema/v8-schema)))]
    (doseq [attr storage/attributes
            :when (and (d/entid db attr) (not (admission/schema-compatible? db attr)))]
      (upgrade/fail! :incompatible-schema {:attribute attr}))
    (when-let [missing (seq (remove #(d/entid db (:db/ident %)) definitions))]
      @(d/transact conn missing))))

(defn migrate!
  ([conn] (migrate! conn {}))
  ([conn options]
   (upgrade/migrate!
    {:snapshot #(d/db conn) :revision d/basis-t
     :evidence admission/evidence :read-state admission/read-state :install! #(install! conn)
     :read-pairs (fn [db version]
                   (let [[forward reverse] (if (= 7 version)
                                             [legacy/forward-attribute legacy/reverse-attribute]
                                             [storage/forward-attribute storage/reverse-attribute])]
                     {:forward (d/datoms db :aevt forward) :reverse (d/datoms db :aevt reverse)}))
     :source-batch #(take %2 (d/datoms %1 :aevt legacy/forward-attribute))
     :entity-exists? #(boolean (first (d/datoms %1 :eavt %2))) :relation d/entity
     :relation-generation #(some-> (get (d/entity %1 %2) :eacl/relation-version) :db/id d/tx->t)
     :stamp #(vector :db/add % :eacl/relation-version "datomic.tx")
     :commit! (fn [before operations]
                (:db-after @(d/transact conn (into [[:eacl.fn/assert-storage-basis (d/basis-t before)]] operations))))}
    options)))

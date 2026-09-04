(ns eacl.datalevin.migrations.v7-to-v9
  "Quiesced storage conversion through Datalevin's admitted writer and policy."
  (:require [datalevin.core :as d]
            [eacl.datalevin.fork :as fork]
            [eacl.datalevin.schema :as schema]
            [eacl.datalevin.storage :as admission]
            [eacl.relationships.legacy-v7 :as legacy]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.upgrade :as upgrade]))

(defn- rows [db attr]
  (if (contains? (d/schema db) attr) (d/datoms db :ave attr) []))

(defn migrate!
  ([conn] (migrate! conn {}))
  ([conn options]
   (let [!token (atom nil)
         install!
         (fn []
           (if-let [existing (fork/write-policy conn)]
             (let [token (:write-token (fork/install-write-policy! conn existing))]
               (d/with-write-policy-token
                conn token
                #(reset! !token (:write-token (schema/ensure-physical-schema! conn token)))))
             (reset! !token (:write-token (schema/ensure-physical-schema! conn)))))]
     (upgrade/migrate!
      {:snapshot #(d/db conn) :revision :max-tx
       :evidence admission/evidence :read-state admission/read-state :install! install!
       :read-pairs (fn [db version]
                     (let [[forward reverse] (if (= 7 version)
                                               [legacy/forward-attribute legacy/reverse-attribute]
                                               [storage/forward-attribute storage/reverse-attribute])]
                       {:forward (rows db forward) :reverse (rows db reverse)}))
       :source-batch #(take-while (fn [row] (= legacy/forward-attribute (:a row)))
                                  (d/seek-datoms %1 :ave legacy/forward-attribute nil nil %2))
       :entity-exists? #(= %2 (:e (first (d/seek-datoms %1 :eav %2 nil nil 1)))) :relation d/entity
       :relation-generation #(get (d/entity %1 %2) :eacl.datalevin/relation-generation)
       :stamp #(vector :db/add % :eacl.datalevin/relation-generation :db/current-tx)
       :commit! (fn [before operations]
                  (:db-after
                   (d/transact!
                    conn [[:db.fn/call
                           (fn [current]
                             (upgrade/assert-head! {:expected-revision (:max-tx before)} (:max-tx current))
                             (conj operations
                                   [:db/add [:eacl/id upgrade/metadata-id]
                                    :eacl.storage/migration-generation :db/current-tx]))]]
                    {:datalevin/write-token @!token})))}
      options))))

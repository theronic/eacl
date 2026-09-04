(ns eacl.exploration.caveats.native-publication
  "Disposable native stores for nested-ref allocation and retraction evidence."
  (:require [datomic.api :as dt]
            [datascript.core :as ds]
            [datahike.api :as dh]
            [datalevin.core :as dl]
            [datalevin.util :as dl-util]
            [eacl.datomic.schema :as dt-schema]
            [eacl.datascript.schema :as ds-schema]
            [eacl.datahike.schema :as dh-schema]
            [eacl.datahike.db :as dh-db]
            [eacl.datalevin.schema :as dl-schema]
            [eacl.relationships.storage :as storage]))

(def marker :eacl.relationship-qualifier/format-version)
(def marker-schema {:db/ident marker :db/valueType :db.type/long :db/cardinality :db.cardinality/one})

(defn inspect-publication [{:keys [transact! snapshot entid rows]} mode tempid]
  (transact! [{:eacl/id "probe/subject"} {:eacl/id "probe/resource"} {:eacl/id "probe/relation"}])
  (let [s (entid (snapshot) [:eacl/id "probe/subject"])
        r (entid (snapshot) [:eacl/id "probe/resource"])
        relation (entid (snapshot) [:eacl/id "probe/relation"])
        qualifier-map {:db/id tempid marker 1}
        prepared (when (= :prepared mode) (transact! [qualifier-map]))
        q (if prepared (get (:tempids prepared) tempid) tempid)
        operations (cond-> []
                     (= :inline mode) (conj qualifier-map)
                     true (conj [:db/add s storage/forward-attribute [:user relation :doc r q]]
                                [:db/add r storage/reverse-attribute [:doc relation :user s q]]))]
    (try
      (let [report (transact! operations)
            resolved (or (get (:tempids report) tempid) q)
            pair (fn [] {:forward (mapv :v (rows (snapshot) storage/forward-attribute))
                         :reverse (mapv :v (rows (snapshot) storage/reverse-attribute))})
            before (pair)
            expected {:forward [[:user relation :doc r resolved]]
                      :reverse [[:doc relation :user s resolved]]}
            _ (transact! [[:db/retractEntity resolved]])]
        {:mode mode :input-tempid (str tempid) :resolved-qid resolved
         :resolved-pair? (= expected before) :before-retract before :after-retract (pair)})
      (catch Exception e
        {:mode mode :input-tempid (str tempid) :error (ex-message e)
         :error-data (ex-data e)}))))

(defn run-backend [backend mode]
  (case backend
    :datomic
    (let [uri (str "datomic:mem://caveat-publication-" (random-uuid))
          _ (dt/create-database uri) conn (dt/connect uri)]
      (try
        (dt-schema/install! conn)
        @(dt/transact conn [marker-schema])
        (inspect-publication {:transact! #(deref (dt/transact conn %)) :snapshot #(dt/db conn)
                              :entid dt/entid :rows #(dt/datoms %1 :aevt %2)} mode "probe/q")
        (finally (dt/release conn) (dt/delete-database uri))))
    :datascript
    (let [conn (ds-schema/create-conn {marker {}})]
      (inspect-publication {:transact! #(ds/transact! conn %) :snapshot #(ds/db conn)
                            :entid ds/entid :rows #(ds/datoms %1 :aevt %2)} mode "probe/q"))
    :datahike
    (let [conn (dh-schema/create-conn [marker-schema]) config (:config (dh/db conn))]
      (try
        (inspect-publication {:transact! #(dh/transact conn %) :snapshot #(dh/db conn)
                              :entid dh-db/entid :rows #(dh/datoms %1 {:index :aevt :components [%2]})} mode -101)
        (finally (dh/release conn) (dh/delete-database config))))
    :datalevin
    (let [dir (dl-util/tmp-dir (str "caveat-publication-" (random-uuid)))
          conn (dl-schema/create-conn dir {marker {:db/valueType :db.type/long}})]
      (try
        (inspect-publication {:transact! #(dl/transact! conn %) :snapshot #(dl/db conn)
                              :entid dl/entid :rows #(dl/datoms %1 :ave %2)} mode -101)
        (finally (dl/close conn) (dl-util/delete-files dir))))))

(defn run-probes! []
  (into {} (for [backend [:datomic :datascript :datahike :datalevin]]
             [backend (mapv #(run-backend backend %) [:inline :prepared])])))

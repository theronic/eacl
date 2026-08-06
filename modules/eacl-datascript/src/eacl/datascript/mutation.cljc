(ns eacl.datascript.mutation
  "DataScript storage adapter for the shared mutation journal."
  (:require [datascript.core :as ds]
            [eacl.mutation :as mutation]))

(defn graph-state
  [db]
  (when-let [entity (ds/entity db [:eacl/id mutation/graph-entity-id])]
    (let [family-id (get entity mutation/graph-family-id-attr)
          head-id (get entity mutation/graph-head-id-attr)
          order
          (ds/q '[:find ?order .
                  :in $ ?graph-id
                  :where
                  [?graph :eacl/id ?graph-id]
                  [?graph :eacl.graph/head-order ?order]]
                db mutation/graph-entity-id)]
      (when (and family-id head-id)
        {:family-id family-id
         :head-id head-id
         :head-order order
         :max-tx (:max-tx db)}))))

(defn contains-anchor?
  [db anchor]
  (boolean (ds/entid db [mutation/mutation-id-attr anchor])))

(defn mutation-entity
  [db mutation-id]
  (some-> (ds/entity db [mutation/mutation-id-attr mutation-id])
          (select-keys
           [mutation/mutation-id-attr
            mutation/mutation-fingerprint-attr
            mutation/mutation-kind-attr
            mutation/mutation-issued-at-attr
            mutation/mutation-expires-at-attr])))

(defn relation-ids
  [db]
  (->> (ds/q '[:find [?relation ...]
                :where
                [?relation :eacl.relation/relation-name]]
              db)
       sort
       vec))

(defn ensure-migrated!
  [conn]
  (or (graph-state (ds/db conn))
      (let [_ (ds/transact! conn [{:eacl/id mutation/graph-entity-id}])
            db (ds/db conn)
            family-id (mutation/new-id)
            {:keys [tx-data]}
            (mutation/migration-data
             {:relation-ids (relation-ids db)
              :family-id family-id
              :order-value nil})]
        (try
          (ds/transact!
           conn
           (conj (vec tx-data)
                 [:db/add
                  [:eacl/id mutation/graph-entity-id]
                  mutation/graph-head-order-attr
                  :db/current-tx]))
          (catch #?(:clj Throwable :cljs :default) error
            (when-not (graph-state (ds/db conn))
              (throw error))))
        (graph-state (ds/db conn)))))

(defn mutation-transaction-data
  [db {:keys [mutation-id kind canonical-data relation-ids dependency-ids
              schema-change?
              token-ttl-seconds retention-grace-seconds]}]
  (let [{:keys [family-id head-id]} (graph-state db)]
    (conj
     (mutation/transaction-data
      {:mutation-id mutation-id
       :kind kind
       :canonical-data canonical-data
       :relation-ids relation-ids
       :dependency-ids dependency-ids
       :schema-change? schema-change?
       :order-value nil
       :family-id family-id
       :previous-head-id head-id
       :token-ttl-seconds token-ttl-seconds
       :retention-grace-seconds retention-grace-seconds})
     [:db/add
      [:eacl/id mutation/graph-entity-id]
      mutation/graph-head-order-attr
      :db/current-tx])))

(defn transact!
  "Submits a logical mutation exactly once for one caller-supplied mutation id."
  [conn {:keys [mutation-id canonical-data tx-data] :as mutation-options}]
  (ensure-migrated! conn)
  (let [db (ds/db conn)]
    (if-let [stored (mutation-entity db mutation-id)]
      (if (mutation/mutation-data-matches?
           stored mutation-id canonical-data)
        {:db-before db
         :db-after db
         :tx-data []
         :mutation-id mutation-id
         :idempotent-recovery? true}
        (throw (ex-info "EACL mutation id was reused with different data."
                        {:type :eacl.mutation/id-reused
                         :mutation-id mutation-id})))
      (let [report
            (try
              (ds/transact!
               conn
               (into (vec tx-data)
                     (mutation-transaction-data db mutation-options)))
              (catch #?(:clj Throwable :cljs :default) error
                (if-let [stored (mutation-entity (ds/db conn) mutation-id)]
                  (if (mutation/mutation-data-matches?
                       stored mutation-id canonical-data)
                    {:db-before db
                     :db-after (ds/db conn)
                     :tx-data []
                     :mutation-id mutation-id
                     :idempotent-recovery? true}
                    (throw
                     (ex-info
                      "EACL mutation id was reused with different data."
                      {:type :eacl.mutation/id-reused
                       :mutation-id mutation-id}
                      error)))
                  (throw error))))]
        (assoc report :mutation-id mutation-id)))))

(defn prune-expired!
  [conn now]
  (let [db (ds/db conn)
        current-head (:head-id (graph-state db))
        expired
        (ds/q '[:find [?mutation ...]
                :in $ ?now
                :where
                [?mutation :eacl.mutation/id ?id]
                [?mutation :eacl.mutation/expires-at ?expiry]
                [(< ?expiry ?now)]]
              db now)
        retractable
        (remove #(= current-head
                    (get (ds/entity db %) mutation/mutation-id-attr))
                expired)]
    (when (seq retractable)
      (ds/transact!
       conn
       (mapv (fn [entity-id] [:db/retractEntity entity-id])
             retractable)))
    (count retractable)))

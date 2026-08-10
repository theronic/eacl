(ns eacl.datomic.mutation
  "Datomic storage adapter for the shared mutation journal."
  (:require [datomic.api :as d]
            [eacl.datomic.mutation-schema :as mutation-schema]
            [eacl.mutation :as mutation]))

(defn ensure-schema!
  [conn]
  (let [db (d/db conn)
        missing (remove #(d/entid db (:db/ident %))
                        mutation-schema/attributes)]
    (when (seq missing)
      @(d/transact conn (vec missing)))
    (when-not (d/entid (d/db conn) [:eacl/id mutation/graph-entity-id])
      @(d/transact conn [{:eacl/id mutation/graph-entity-id}])))
  true)

(defn graph-state
  [db]
  (when-let [entity (d/entity db [:eacl/id mutation/graph-entity-id])]
    (let [family-id (get entity mutation/graph-family-id-attr)
          head-id (get entity mutation/graph-head-id-attr)
          order (get entity mutation/graph-head-order-attr)]
      (when (and family-id head-id)
        {:family-id family-id
         :head-id head-id
         :head-order
         (d/tx->t (if (number? order) order (:db/id order)))
         :basis-t (d/basis-t db)
         :database-id (str (.id ^datomic.Database db))}))))

(defn contains-anchor?
  [db anchor]
  (boolean (d/entid db [mutation/mutation-id-attr anchor])))

(defn mutation-entity
  [db mutation-id]
  (some-> (d/entity db [mutation/mutation-id-attr mutation-id])
          (select-keys
           [mutation/mutation-id-attr
            mutation/mutation-fingerprint-attr
            mutation/mutation-kind-attr
            mutation/mutation-issued-at-attr
            mutation/mutation-expires-at-attr])))

(defn relation-ids
  [db]
  (->> (d/q '[:find [?relation ...]
               :where
               [?relation :eacl.relation/relation-name]]
             db)
       sort
       vec))

(defn ensure-migrated!
  [conn]
  (ensure-schema! conn)
  (or (graph-state (d/db conn))
      (let [db (d/db conn)
            {:keys [tx-data]}
            (mutation/migration-data
             {:relation-ids (relation-ids db)
              :family-id (mutation/new-id)
              :order-value "datomic.tx"})]
        (try
          @(d/transact conn tx-data)
          (catch Throwable error
            (when-not (graph-state (d/db conn))
              (throw error))))
        (graph-state (d/db conn)))))

(defn mutation-transaction-data
  [db {:keys [mutation-id kind canonical-data relation-ids dependency-ids
              schema-change?
              token-ttl-seconds retention-grace-seconds]}]
  (let [{:keys [family-id head-id]} (graph-state db)]
    (mutation/transaction-data
     {:mutation-id mutation-id
      :kind kind
      :canonical-data canonical-data
      :relation-ids relation-ids
      :dependency-ids dependency-ids
      :schema-change? schema-change?
      :order-value "datomic.tx"
      :family-id family-id
      :previous-head-id head-id
      :token-ttl-seconds token-ttl-seconds
      :retention-grace-seconds retention-grace-seconds})))

(defn transact!
  [conn {:keys [mutation-id canonical-data tx-data calculation-db]
         :as mutation-options}]
  (ensure-migrated! conn)
  (let [submission-db (d/db conn)
        calculation-db (or calculation-db submission-db)]
    (if-let [stored (mutation-entity submission-db mutation-id)]
      (if (mutation/mutation-data-matches?
           stored mutation-id canonical-data)
        {:db-before submission-db
         :db-after submission-db
         :tx-data []
         :mutation-id mutation-id
         :idempotent-recovery? true}
        (throw (ex-info "EACL mutation id was reused with different data."
                        {:type :eacl.mutation/id-reused
                         :mutation-id mutation-id})))
      (assoc
       (try
         @(d/transact
           conn
           (into (vec tx-data)
                 (mutation-transaction-data
                  calculation-db mutation-options)))
         (catch Throwable error
           (if-let [stored (mutation-entity (d/db conn) mutation-id)]
             (if (mutation/mutation-data-matches?
                  stored mutation-id canonical-data)
               {:db-before submission-db
                :db-after (d/db conn)
                :tx-data []
                :mutation-id mutation-id
                :idempotent-recovery? true}
               (throw
                (ex-info
                 "EACL mutation id was reused with different data."
                 {:type :eacl.mutation/id-reused
                  :mutation-id mutation-id}
                 error)))
             (throw error))))
       :mutation-id mutation-id))))

(defn prune-expired!
  [conn now]
  (let [db (d/db conn)
        current-head (:head-id (graph-state db))
        expired
        (d/q '[:find [?mutation ...]
               :in $ ?now
               :where
               [?mutation :eacl.mutation/id ?id]
               [?mutation :eacl.mutation/expires-at ?expiry]
               [(< ?expiry ?now)]]
             db now)
        retractable
        (remove #(= current-head
                    (get (d/entity db %) mutation/mutation-id-attr))
                expired)]
    (when (seq retractable)
      @(d/transact
        conn
        (mapv (fn [entity-id] [:db/retractEntity entity-id])
              retractable)))
    (count retractable)))

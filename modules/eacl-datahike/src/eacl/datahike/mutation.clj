(ns eacl.datahike.mutation
  "Datahike storage adapter for the shared mutation journal."
  (:require [datahike.api :as d]
            [eacl.datahike.db :as ddb]
            [eacl.mutation :as mutation]))

(defn graph-state
  [db]
  (when-let [entity (d/entity db [:eacl/id mutation/graph-entity-id])]
    (let [family-id (get entity mutation/graph-family-id-attr)
          head-id (get entity mutation/graph-head-id-attr)
          order (get entity mutation/graph-head-order-attr)]
      (when (and family-id head-id)
        {:family-id family-id
         :head-id head-id
         :head-order (if (number? order) order (:db/id order))
         :max-tx (:max-tx db)}))))

(defn contains-anchor?
  [db anchor]
  (boolean (ddb/entid db [mutation/mutation-id-attr anchor])))

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

(defn- native-tx-data
  [db tx-data]
  (mapv
   (fn [op]
     (if (and (vector? op)
              (= :db.fn/cas (first op)))
       (assoc op 2 (ddb/attr-repr db (nth op 2)))
       op))
   tx-data))

(def ^:private migration-visibility-timeout-ms 30000)
(def ^:private migration-visibility-poll-ms 2)

(defn- cas-conflict?
  [error]
  (loop [cause error]
    (cond
      (nil? cause) false
      (= :transact/cas (:error (ex-data cause))) true
      :else (recur (.getCause ^Throwable cause)))))

(defn- await-graph-state
  [conn]
  ;; Datahike validates queued transactions before it commits the batch. A
  ;; losing migration therefore receives its CAS error just before the winning
  ;; migration's committed db becomes visible through the shared connection.
  (let [deadline
        (+ (System/nanoTime)
           (* 1000000 migration-visibility-timeout-ms))]
    (loop []
      (or
       (graph-state (d/db conn))
       (when (< (System/nanoTime) deadline)
         (Thread/sleep (long migration-visibility-poll-ms))
         (recur))))))

(defn ensure-migrated!
  [conn]
  (or (graph-state (d/db conn))
      (let [_ (d/transact conn [{:eacl/id mutation/graph-entity-id}])
            db (d/db conn)
            {:keys [tx-data]}
            (mutation/migration-data
             {:relation-ids (relation-ids db)
              :family-id (mutation/new-id)
              :order-value :db/current-tx})]
        (try
          (or
           (some-> (d/transact conn (native-tx-data db tx-data))
                   :db-after
                   graph-state)
           (graph-state (d/db conn)))
          (catch Throwable error
            (if (cas-conflict? error)
              (or (await-graph-state conn)
                  (throw error))
              (throw error)))))))

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
      :order-value :db/current-tx
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
         (d/transact
         conn
          (native-tx-data
           calculation-db
           (into (vec tx-data)
                 (mutation-transaction-data
                  calculation-db mutation-options))))
         (catch Throwable error
           (let [observed-db (d/db conn)]
             (if-let [stored (mutation-entity observed-db mutation-id)]
             (if (mutation/mutation-data-matches?
                  stored mutation-id canonical-data)
               {:db-before submission-db
                :db-after observed-db
                :tx-data []
                :mutation-id mutation-id
                :idempotent-recovery? true}
               (throw
                (ex-info
                 "EACL mutation id was reused with different data."
                 {:type :eacl.mutation/id-reused
                 :mutation-id mutation-id}
                 error)))
               (if (cas-conflict? error)
                 (throw
                  (ex-info
                   "EACL mutation was calculated from a stale Datahike graph head."
                   {:type :eacl.mutation/concurrent-write
                    :eacl/error :eacl.mutation/concurrent-write
                    :backend :datahike
                    :expected-head
                    (:head-id (graph-state calculation-db))
                    :observed-head (:head-id (graph-state observed-db))
                    :retryable? true}
                   error))
                 (throw error))))))
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
      (d/transact
       conn
       (mapv (fn [entity-id] [:db/retractEntity entity-id])
             retractable)))
    (count retractable)))

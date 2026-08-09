(ns eacl.datascript.core
  "DataScript construction shim over the shared client orchestration
  (backend-unification, D-7). Everything here is genuinely
  DataScript-specific: index access for managed stamps, the snapshot
  adapter, schema/journal installation, and transaction submission. The
  nine public operations, snapshot-context assembly, cursor plumbing, and
  cache wiring live once in eacl.client.orchestration."
  (:require [datascript.core :as ds]
            [eacl.client.orchestration :as orchestration]
            [eacl.cursor :as cursor]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.mutation :as journal]
            [eacl.datascript.schema :as schema]
            [eacl.mutation :as mutation]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.subproblem-cache :as subproblem]))

(def cursor->token cursor/cursor->token)
(def token->cursor cursor/token->cursor)

(def default-internal-cursor->spice orchestration/default-internal-cursor->spice)
(def default-spice-cursor->internal orchestration/default-spice-cursor->internal)

(defn- datom-proof
  [db entity attribute]
  (when-let [datom (first (ds/datoms db :eavt entity attribute))]
    [(:tx datom) (:v datom)]))

(defn- managed-cache-descriptor
  [db relation-ids]
  (let [relation-ids (vec (distinct relation-ids))]
    (when (seq relation-ids)
      (let [schema-eid
            (ds/entid db [:eacl/id mutation/schema-entity-id])
            schema-stamp
            (when schema-eid
              (datom-proof
               db schema-eid mutation/schema-mutation-id-attr))
            relation-stamps
            (keep
             (fn [relation-id]
               (when-let [tx
                          (datom-proof
                           db relation-id
                           mutation/relation-mutation-id-attr)]
                 [relation-id tx]))
             relation-ids)]
        (when (and (subproblem/proof-stamp? schema-stamp)
                   (= (count relation-ids)
                      (count relation-stamps))
                   (every? (comp subproblem/proof-stamp? second)
                           relation-stamps))
          {:schema-stamp schema-stamp
           :dependency-stamp
           (mapv
            (fn [[relation-id [tx mutation-id]]]
              [relation-id tx mutation-id])
            (sort-by first relation-stamps))})))))

(defn- relationship-retraction-count
  [_db tx-data]
  (count
   (filter
    (fn [{:keys [a added]}]
      (and (false? added)
           (contains? relationship-storage/attributes a)))
    tx-data)))

(def ^:private api
  {:backend-id :datascript
   :db ds/db
   :entid ds/entid
   :default-entid->object-id (fn [db eid] (:eacl/id (ds/entity db eid)))
   :snapshot-adapter datascript-backend/snapshot-adapter
   :managed-cache-descriptor managed-cache-descriptor
   :relationship-retraction-count relationship-retraction-count
   ;; Vars, not values: late binding keeps instrumentation (with-redefs in
   ;; the impl suites) and REPL redefinition visible through the shared
   ;; orchestration.
   :schema {:read-schema #'schema/read-schema
            :write-schema! #'schema/write-schema!}
   :journal {:ensure-migrated! #'journal/ensure-migrated!
             :transact! #'journal/transact!}
   :impl {:validate-relationship-operation!
          #'impl/validate-relationship-operation!
          :relationship-relation-id #'impl/relationship-relation-id
          :tx-update-relationship #'impl/tx-update-relationship
          :tx-delete-object #'impl/tx-delete-object
          :affected-relation-ids #'impl/affected-relation-ids
          :read-relationships #'impl/read-relationships}
   :extra-client-opt-keys #{:exact-snapshot-registry-size}})

(defn datascript-read-relationships
  [db opts filters]
  (orchestration/read-relationships api db opts filters))

(defn datascript-write-relationships!
  [conn opts updates]
  (orchestration/write-relationships! api conn opts updates))

(defn datascript-delete-object!
  [conn opts object]
  (orchestration/delete-object! api conn opts object))

(defn datascript-check-permission
  [db opts subject permission resource consistency]
  (orchestration/check-permission
   api db opts subject permission resource consistency))

(defn datascript-can?
  [db opts subject permission resource consistency]
  (orchestration/can? api db opts subject permission resource consistency))

(defn datascript-lookup-resources
  [db opts query]
  (orchestration/lookup-resources api db opts query))

(defn datascript-count-resources
  [db opts query]
  (orchestration/count-resources api db opts query))

(defn datascript-lookup-subjects
  [db opts query]
  (orchestration/lookup-subjects api db opts query))

(defn datascript-count-subjects
  [db opts query]
  (orchestration/count-subjects api db opts query))

(defn- require-datascript-client!
  [client fn-name]
  (when-not (orchestration/client? client :datascript)
    (throw (ex-info (str fn-name " requires a DataScript EACL client.")
                    {:type :eacl/invalid-client}))))

(defn expire-cache!
  "Expires every completed answer owned by one DataScript EACL client."
  [client]
  (require-datascript-client! client "expire-cache!")
  (orchestration/expire-cache! client))

(defn cache-stats
  "Returns private completed-cache counters for one DataScript EACL client."
  [client]
  (require-datascript-client! client "cache-stats")
  (orchestration/cache-stats client))

(defn make-client
  "Builds an IAuthorization client over a DataScript conn.

  Options (unknown keys throw :eacl/invalid-config - a silently ignored key
  means silently wrong ID coercion, audit 5):
  - :entid->object-id  (fn [db eid] external-id) - canonical.
  - :object-id->lookup-ref (fn [external-id] lookup-ref). Default: [:eacl/id id].
  - :cache - omitted creates a bounded client-private current-generation
    cache; eacl.cache/no-cache disables it; a config map bounds it.
    :coherence-authority defaults to :unknown on every backend: cached
    entries are reused only for the exact immutable database value they
    were computed on, which stays correct under raw ds/transact! writes.
    Pass :coherence-authority :managed - an explicit writer contract that
    every schema and relationship mutation goes through EACL's APIs - to
    let unchanged cache portions survive unrelated transactions.
  - :cursor-ttl-seconds - optional cursor token expiry; default nil (tokens never expire).
  - :internal-cursor->spice / :spice-cursor->internal - advanced cursor coercion overrides.
  - :exact-snapshot-registry-size - DataScript extension: bounds the opaque
    exact-snapshot identity registry used by at-exact-snapshot reads."
  [conn config-opts]
  (orchestration/make-client api conn config-opts))

(defn create-conn
  "A DataScript connection carrying EACL's schema. See
  `eacl.datascript.schema/create-conn` for the config options."
  ([] (schema/create-conn))
  ([extra-schema] (schema/create-conn extra-schema)))

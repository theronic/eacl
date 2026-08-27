(ns eacl.datascript.core
  "DataScript construction shim over the shared client orchestration
  (backend-unification, D-7). Everything here is genuinely
  DataScript-specific: index access for managed stamps, the snapshot
  adapter, schema installation, and transaction submission. The
  nine public operations, snapshot-context assembly, cursor plumbing, and
  cache wiring live once in eacl.client.orchestration."
  (:require [datascript.core :as ds]
            [eacl.client.orchestration :as orchestration]
            [eacl.cursor :as cursor]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.schema :as schema]
            [eacl.relationships.storage :as relationship-storage]))

(def cursor->token cursor/cursor->token)
(def token->cursor cursor/token->cursor)

(def default-internal-cursor->spice orchestration/default-internal-cursor->spice)
(def default-spice-cursor->internal orchestration/default-spice-cursor->internal)

(defn- relationship-retraction-count
  [_db tx-data]
  (count
   (filter
    (fn [{:keys [a added]}]
      (and (false? added)
           (contains? relationship-storage/attributes a)))
    tx-data)))

(defn- transact-native!
  [conn {:keys [tx-data]}]
  (ds/transact! conn (vec tx-data)))

(def ^:private api
  {:backend-id :datascript
   :db ds/db
   :entid ds/entid
   :default-entid->object-id (fn [db eid] (:eacl/id (ds/entity db eid)))
   :basis-adapter datascript-backend/basis-adapter
   :basis-adapter-config-keys datascript-backend/adapter-config-keys
   :source datascript-backend/source
   :basis-kind datascript-backend/basis-kind
   :database-source-scope datascript-backend/database-source-scope
   :db-native-revision
   (fn [db]
     {:revision (:max-tx db)
      :exact-locator nil})
   :native-source-id datascript-backend/connection-source-id
   :relationship-retraction-count relationship-retraction-count
   :transact! transact-native!
   ;; Vars, not values: late binding keeps instrumentation (with-redefs in
   ;; the impl suites) and REPL redefinition visible through the shared
   ;; orchestration.
   :schema {:read-schema #'schema/read-schema
            :generation #'schema/current-schema-generation
            :write-schema! #'schema/write-schema!}
   :impl {:validate-relationship-operation!
          #'impl/validate-relationship-operation!
          :relationship-relation-id #'impl/relationship-relation-id
          :tx-update-relationship #'impl/tx-update-relationship
          :tx-delete-object #'impl/tx-delete-object
          :affected-relation-ids #'impl/affected-relation-ids
          :read-relationships #'impl/read-relationships}
   :extra-client-opt-keys #{}})

(defn- require-datascript-client!
  [client fn-name]
  (when-not (orchestration/client? client :datascript)
    (throw (ex-info (str fn-name " requires a DataScript EACL client.")
                    {:type :eacl/invalid-client :eacl/error :eacl/invalid-client}))))

(defn expire-cache!
  "Rotates one DataScript client's local cache/token lifecycle."
  ([client]
   (require-datascript-client! client "expire-cache!")
   (orchestration/expire-cache! client))
  ([client source-lifecycle]
   (require-datascript-client! client "expire-cache!")
   (orchestration/expire-cache! client source-lifecycle)))

(def prepare-cache-coherence!
  "Initializes missing native cache generations on a quiesced connection.

  This does not detect or repair earlier unsupported unstamped mutations and
  is not a cache flush."
  schema/prepare-cache-coherence!)

(defn cache-stats
  "Returns private completed-cache counters for one DataScript EACL client."
  [client]
  (require-datascript-client! client "cache-stats")
  (orchestration/cache-stats client))

(defn refresh-metrics!
  "Evicts cache-only metrics; optionally recomputes structural metrics now."
  ([client]
   (require-datascript-client! client "refresh-metrics!")
   (orchestration/refresh-metrics! client))
  ([client opts]
   (require-datascript-client! client "refresh-metrics!")
   (orchestration/refresh-metrics! client opts)))

(defn make-client
  "Builds an EACL acl over a DataScript conn.

  Options (unknown keys throw :eacl/invalid-config - a silently ignored key
  means silently wrong ID coercion, audit 5):
  - :entid->object-id  (fn [db eid] external-id) - canonical.
  - :object-id->lookup-ref (fn [external-id] lookup-ref). Default: [:eacl/id id].
  - :cache - omitted creates a bounded client-private basis
    cache; eacl.cache/no-cache disables it; a config map bounds it.
    Exact hits require complete basis identity; complete native generation
    proofs may lift unchanged answers between ordinary bases in the same
    lifecycle in either revision direction. Authorization
    mutations must use EACL APIs or intact EACL-produced transaction data.
  - :cursor-ttl-seconds - optional cursor token expiry; default nil (tokens never expire).
  - :identity-immutable? - whether one internal object's public identity is
    immutable for this source lifecycle. The built-in :eacl/id codec defaults
    true; set false when IDs may be reassigned so cursors stay exact-basis-bound.
    Custom codecs must set true explicitly to enable proof-equivalent cursors.
  - :internal-cursor->spice / :spice-cursor->internal - advanced cursor coercion overrides.

  DataScript is current-basis-only across requests. It does not retain old DB
  values and rejects :at-exact-snapshot before cache access."
  [conn config-opts]
  (orchestration/make-client api conn config-opts))

(defn snapshot
  "Constructs a public snapshot over an application-owned DataScript DB."
  [acl db]
  (orchestration/direct-snapshot acl :datascript db))

(defn db
  "Returns the immutable DataScript DB wrapped by `snapshot`."
  [snapshot]
  (orchestration/snapshot-db snapshot :datascript))

(defn create-conn
  "A DataScript connection carrying EACL's schema. See
  `eacl.datascript.schema/create-conn` for the config options."
  ([] (schema/create-conn))
  ([extra-schema] (schema/create-conn extra-schema)))

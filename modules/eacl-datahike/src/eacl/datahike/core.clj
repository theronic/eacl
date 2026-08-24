(ns eacl.datahike.core
  "Datahike construction shim over the shared client orchestration
  (backend-unification, D-7). Everything here is genuinely
  Datahike-specific: index access for managed stamps (including
  attribute-refs representations), the snapshot adapter, schema
  installation, and transaction submission. The nine public operations,
  snapshot-context assembly, cursor plumbing, and cache wiring live once
  in eacl.client.orchestration."
  (:require [datahike.api :as d]
            [eacl.client.orchestration :as orchestration]
            [eacl.cursor :as cursor]
            [eacl.datahike.backend :as datahike-backend]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as impl]
            [eacl.datahike.schema :as schema]
            [eacl.relationships.storage :as relationship-storage]))

(def cursor->token cursor/cursor->token)
(def token->cursor cursor/token->cursor)

(def default-internal-cursor->spice orchestration/default-internal-cursor->spice)
(def default-spice-cursor->internal orchestration/default-spice-cursor->internal)

(defn- relationship-retraction-count
  [db tx-data]
  (let [attr-reprs
        (into
         #{}
         (map #(ddb/attr-repr db %))
         relationship-storage/attributes)]
    (count
     (filter
      (fn [{:keys [a added]}]
        (and (false? added)
             (contains? attr-reprs a)))
      tx-data))))

(defn typed-transaction-error
  "The exception carrying EACL's typed `ex-data` inside `error`'s cause chain,
  or nil. Datahike's writer executes transactions off the calling thread and
  reports a failing transaction function as a wrapper exception (an ex-info
  around the future's `ExecutionException` around the original throw), so a
  commit-time `:eacl/relationship-conflict` has to be recovered from the
  chain to keep the public write contract."
  [^Throwable error]
  (loop [candidate error]
    (when candidate
      (if (:eacl/error (ex-data candidate))
        candidate
        (recur (.getCause candidate))))))

(defn- transact-native!
  [conn {:keys [tx-data]}]
  (try
    (d/transact conn (vec tx-data))
    (catch Exception error
      (throw (or (typed-transaction-error error) error)))))

(def ^:private api
  {:backend-id :datahike
   :db d/db
   :entid ddb/entid
   :default-entid->object-id (fn [db eid] (:eacl/id (d/entity db eid)))
   :basis-adapter datahike-backend/basis-adapter
   :basis-adapter-config-keys datahike-backend/adapter-config-keys
   :source datahike-backend/source
   :basis-kind datahike-backend/basis-kind
   :database-source-scope datahike-backend/database-source-scope
   :db-native-revision
   (fn [db]
     {:revision (:max-tx db)
      :exact-locator
      (some-> (get-in db [:meta :datahike/commit-id]) str)})
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

(defn- require-datahike-client!
  [client fn-name]
  (when-not (orchestration/client? client :datahike)
    (throw (ex-info (str fn-name " requires a Datahike EACL client.")
                    {:type :eacl/invalid-client :eacl/error :eacl/invalid-client}))))

(defn expire-cache!
  "Rotates one Datahike client's local cache/token lifecycle."
  ([client]
   (require-datahike-client! client "expire-cache!")
   (orchestration/expire-cache! client))
  ([client source-lifecycle]
   (require-datahike-client! client "expire-cache!")
   (orchestration/expire-cache! client source-lifecycle)))

(def prepare-cache-coherence!
  "Initializes missing native cache generations on a quiesced connection.

  This does not detect or repair earlier unsupported unstamped mutations and
  is not a cache flush."
  schema/prepare-cache-coherence!)

(defn cache-stats
  "Returns private completed-cache counters for one Datahike EACL client."
  [client]
  (require-datahike-client! client "cache-stats")
  (orchestration/cache-stats client))

(defn make-client
  "Builds an EACL acl over a datahike conn.

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
  - :internal-cursor->spice / :spice-cursor->internal - advanced cursor coercion overrides."
  [conn config-opts]
  (orchestration/make-client api conn config-opts))

(defn snapshot
  "Constructs a public snapshot over an application-owned Datahike DB."
  [acl db]
  (orchestration/direct-snapshot acl :datahike db))

(defn db
  "Returns the immutable Datahike DB wrapped by `snapshot`."
  [snapshot]
  (orchestration/snapshot-db snapshot :datahike))

(defn create-conn
  "A datahike connection carrying EACL's schema. See
  `eacl.datahike.schema/create-conn` for the config options."
  ([] (schema/create-conn))
  ([extra-schema] (schema/create-conn extra-schema))
  ([extra-schema config] (schema/create-conn extra-schema config)))

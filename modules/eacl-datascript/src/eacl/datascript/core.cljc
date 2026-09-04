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
            [eacl.datascript.storage :as target-storage]
            [eacl.relationships.upgrade :as storage-upgrade]
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

(defn- native-with
  [db tx-data]
  (ds/with db (vec tx-data)))

(defn- normalize-report-datom
  [_db-before _db-after datom]
  {:e (:e datom)
   :a (:a datom)
   :v (:v datom)
   :tx (:tx datom)
   :added (boolean (:added datom))})

(defn- schema-entity?
  [db entity-id]
  (let [entity (ds/entity db entity-id)]
    (or (= "schema-string" (:eacl/id entity))
        (some? (:eacl.relation/relation-name entity))
        (some? (:eacl.permission/permission-name entity)))))

(defn- schema-storage-datom?
  [db-before db-after {:keys [e a]}]
  (let [attribute-namespace (namespace a)
        schema-attribute?
        (or (= a :eacl/schema-string)
            (= a :eacl/schema-generation)
            (= attribute-namespace "eacl.relation")
            (= attribute-namespace "eacl.permission")
            (and (= a :eacl/id)
                 (or (schema-entity? db-before e)
                     (schema-entity? db-after e))))]
    (and schema-attribute?
         (not= (get (ds/entity db-before e) a)
               (get (ds/entity db-after e) a)))))

(defn- relation-coordinate
  [db relation-id]
  (when relation-id
    (let [entity (ds/entity db relation-id)
          resource-type (:eacl.relation/resource-type entity)
          relation-name (:eacl.relation/relation-name entity)
          subject-type (:eacl.relation/subject-type entity)]
      (when (and resource-type relation-name subject-type)
        [:relation resource-type relation-name subject-type]))))

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
   :native-with native-with
   :normalize-report-datom normalize-report-datom
   :transaction-datom? #(= :db/txInstant (:a %))
   :schema-storage-datom? schema-storage-datom?
   :relation-version-attribute :eacl/relation-version
   :transact! transact-native!
   ;; Vars, not values: late binding keeps instrumentation (with-redefs in
   ;; the impl suites) and REPL redefinition visible through the shared
   ;; orchestration.
   :schema {:read-schema #'schema/read-schema
            :generation #'schema/current-schema-generation
            :plan-replacement #'schema/plan-schema-replacement
            :write-schema! #'schema/write-schema!}
   :impl {:validate-relationship-operation!
          #'impl/validate-relationship-operation!
          :relationship-relation-id #'impl/relationship-relation-id
          :relation-coordinate relation-coordinate
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

(defn export-cache-snapshot
  "Exports reusable authorization-cache entries as a bounded immutable value.

  Hosts persisting external bytes must authenticate and encoded-size-bound the
  envelope before deserialization. The value contains neither a DataScript DB
  nor process-local cache identity."
  [client bounds]
  (require-datascript-client! client "export-cache-snapshot")
  (orchestration/export-cache-snapshot client bounds))

(defn restore-cache-snapshot!
  "Atomically restores one trusted, authenticated cache snapshot value."
  [client snapshot bounds]
  (require-datascript-client! client "restore-cache-snapshot!")
  (orchestration/restore-cache-snapshot! client snapshot bounds))

(defn cache-content-revision
  "Returns this client's process-local reusable-cache content revision."
  [client]
  (require-datascript-client! client "cache-content-revision")
  (orchestration/cache-content-revision client))

(defn refresh-metrics!
  "Drops derived cache artifacts; optionally recomputes them immediately."
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
    proofs may lift unchanged answers into causally later ordinary bases in
    the same lifecycle. Authorization
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
  (storage-upgrade/reject-auto-migration! config-opts)
  (target-storage/assert-compatible! (ds/db conn))
  (orchestration/make-client api conn config-opts))

(defn db
  "Returns the immutable DataScript DB held by an EACL-created snapshot."
  [snapshot]
  (orchestration/snapshot-db snapshot :datascript))

(defn create-conn
  "A DataScript connection carrying EACL's schema. See
  `eacl.datascript.schema/create-conn` for the config options."
  ([] (schema/create-conn))
  ([extra-schema] (schema/create-conn extra-schema)))

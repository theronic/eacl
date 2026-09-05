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
            [eacl.datahike.qualifiers :as qualifiers]
            [eacl.datahike.migrations.v7-to-v8 :as v7-to-v8]
            [eacl.datahike.schema :as schema]
            [eacl.datahike.storage :as target-storage]
            [eacl.relationships.upgrade :as storage-upgrade]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.relationships.staged :as staged]
            [eacl.schema.expression-policy :as expression-policy]))

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

(defn- native-with
  [db tx-data]
  (d/with db (vec tx-data)))

(defn- datom-attribute
  [db-before db-after attribute]
  (if (keyword? attribute)
    attribute
    (or (:db/ident (d/entity db-after attribute))
        (:db/ident (d/entity db-before attribute)))))

(defn- normalize-report-datom
  [db-before db-after datom]
  {:e (:e datom)
   :a (datom-attribute db-before db-after (:a datom))
   :v (:v datom)
   :tx (:tx datom)
   :added (boolean (:added datom))})

(defn- schema-entity?
  [db entity-id]
  (let [entity (d/entity db entity-id)]
    (or (= "schema-string" (:eacl/id entity))
        (some? (:eacl.relation/relation-name entity))
        (some? (:eacl.permission/permission-name entity)))))

(defn- schema-storage-datom?
  [db-before db-after {:keys [e a]}]
  (let [attribute-namespace (namespace a)
        schema-attribute?
        (or (= a :eacl/schema-string)
            (= a :eacl/schema-generation)
            (= a :eacl/permission-storage-version)
            (= attribute-namespace "eacl.relation")
            (= attribute-namespace "eacl.permission")
            (and (= a :eacl/id)
                 (or (schema-entity? db-before e)
                     (schema-entity? db-after e))))]
    (and schema-attribute?
         (not= (get (d/entity db-before e) a)
               (get (d/entity db-after e) a)))))

(defn- relation-coordinate
  [db relation-id]
  (when relation-id
    (let [entity (d/entity db relation-id)
          resource-type (:eacl.relation/resource-type entity)
          relation-name (:eacl.relation/relation-name entity)
          subject-type (:eacl.relation/subject-type entity)]
      (when (and resource-type relation-name subject-type)
        [:relation resource-type relation-name subject-type]))))

(def ^:private api
  {:backend-id :datahike
   :writer-max-attempts 8
   :writer-contention? staged/prepared-contention?
   :qualified-writer #'qualifiers/writer
   :qualified-publication-capability #'qualifiers/publication-capability
   :qualified-plan #'qualifiers/plan
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
            :read-authorization-schema #'schema/read-authorization-schema
            :generation #'schema/current-schema-generation
            :plan-replacement #'schema/plan-schema-replacement
            :write-schema! #'schema/write-schema!}
   :impl {:validate-relationship-operation!
          #'impl/validate-relationship-operation!
          :relationship-publication-input #'impl/relationship-publication-input
          :relationship-relation-id #'impl/relationship-relation-id
          :relation-coordinate relation-coordinate
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

(defn export-cache-snapshot
  "Exports reusable authorization-cache entries as a bounded immutable value.

  Hosts persisting external bytes must authenticate and encoded-size-bound the
  envelope before deserialization. The value contains neither a Datahike DB nor
  process-local cache identity."
  [client bounds]
  (require-datahike-client! client "export-cache-snapshot")
  (orchestration/export-cache-snapshot client bounds))

(defn restore-cache-snapshot!
  "Atomically restores one trusted, authenticated cache snapshot value."
  [client snapshot bounds]
  (require-datahike-client! client "restore-cache-snapshot!")
  (orchestration/restore-cache-snapshot! client snapshot bounds))

(defn cache-content-revision
  "Returns this client's process-local reusable-cache content revision."
  [client]
  (require-datahike-client! client "cache-content-revision")
  (orchestration/cache-content-revision client))

(defn refresh-metrics!
  "Drops derived cache artifacts; optionally recomputes them immediately."
  ([client]
   (require-datahike-client! client "refresh-metrics!")
   (orchestration/refresh-metrics! client))
  ([client opts]
   (require-datahike-client! client "refresh-metrics!")
   (orchestration/refresh-metrics! client opts)))

(defn make-client
  "Builds an EACL v8 client over explicitly initialized Relationship storage 9.
  Storage and permission upgrades must be invoked before construction."
  [conn config-opts]
  (storage-upgrade/reject-auto-migration! config-opts)
  (target-storage/assert-compatible! (d/db conn))
  (let [expression-limits (expression-policy/normalize-client-limits (:expression-limits config-opts))]
    (v7-to-v8/assert-permission-storage-compatible! conn {:expression-limits expression-limits})
    (orchestration/make-client api conn (assoc config-opts :expression-limits expression-limits))))

(defn db
  "Returns the immutable Datahike DB held by an EACL-created snapshot."
  [snapshot]
  (orchestration/snapshot-db snapshot :datahike))

(defn create-conn
  "A datahike connection carrying EACL's schema. See
  `eacl.datahike.schema/create-conn` for the config options."
  ([] (schema/create-conn))
  ([extra-schema] (schema/create-conn extra-schema))
  ([extra-schema config] (schema/create-conn extra-schema config)))

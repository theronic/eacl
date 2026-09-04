(ns eacl.datomic.core
  "Datomic-backed EACL authorization views.

  Public reads, writes, consistency, caching, pagination, and lifecycle are
  implemented once by `eacl.client.orchestration`. This namespace contributes
  only Datomic's basis adapter, basis source, writer primitives, schema
  operations, explicit speculative application, and Datomic-specific utilities."
  (:require [datomic.api :as d]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datomic.backend :as datomic-backend]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.indexed :as indexed]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.storage :as target-storage]
            [eacl.relationships.upgrade :as storage-upgrade]
            [eacl.migrations.v7-to-v8 :as v7-to-v8]
            [eacl.migrations.v6-to-v7 :as migrations]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.expression-policy :as expression-policy]))

(def ^:private maximum-relationship-write-attempts 8)
(def ^:private delete-object-batch-size 1000)

(defn- relationship-attr-eids
  [db]
  (into #{} (keep #(d/entid db %)) relationship-storage/attributes))

(defn- relationship-retraction-count
  [db-after tx-data]
  (let [attr-eids (relationship-attr-eids db-after)]
    (count
     (filter
      (fn [{:keys [a added]}]
        (and (false? added) (contains? attr-eids a)))
      tx-data))))

(defn- datomic-cas-failure?
  [throwable]
  (loop [cause throwable]
    (when cause
      (if (= :db.error/cas-failed (:db/error (ex-data cause)))
        true
        (recur (.getCause ^Throwable cause))))))

(defn- transact!
  [conn {:keys [tx-data]}]
  @(d/transact conn (vec tx-data)))

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
            (= a :eacl/schema-version)
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

(defn- write-schema!
  [conn schema-string options expected-generation]
  (let [deltas
        (schema/write-schema!
         conn schema-string options expected-generation)]
    (assoc deltas
           :eacl.schema/db-after (:eacl.schema/db-after (meta deltas))
           :eacl.schema/no-op? (:eacl.schema/no-op? (meta deltas)))))

(def ^:private api
  {:backend-id :datomic
   :db d/db
   :entid d/entid
   :default-entid->object-id
   (fn [db eid] (:eacl/id (d/entity db eid)))
   :basis-adapter datomic-backend/basis-adapter
   :basis-adapter-config-keys datomic-backend/adapter-config-keys
   :source datomic-backend/source
   :basis-kind datomic-backend/basis-kind
   :database-source-scope datomic-backend/database-source-scope
   :native-source-id datomic-backend/connection-source-id
   :db-native-revision
   (fn [^datomic.Database db]
     (let [revision (or (.asOfT db) (d/basis-t db))]
       {:revision revision :exact-locator revision}))
   :relationship-retraction-count relationship-retraction-count
   :native-with native-with
   :normalize-report-datom normalize-report-datom
   :transaction-datom? #(= :db/txInstant (:a %))
   :schema-storage-datom? schema-storage-datom?
   :relation-version-attribute :eacl/relation-version
   :transact! transact!
   :writer-max-attempts maximum-relationship-write-attempts
   :writer-max-transaction-size delete-object-batch-size
   :writer-contention? datomic-cas-failure?
   :prepare-relationship-tx
   (fn [db tx-data]
     (impl/optimistic-relationship-tx-data
      db (impl/stamp-relation-versions tx-data)))
   :schema
   {:read-schema schema/read-schema
    :generation indexed/schema-version
    :plan-replacement schema/plan-schema-replacement
    :write-schema! write-schema!}
   :impl
   {:validate-relationship-operation!
    impl/validate-relationship-operation!
    :relationship-relation-id impl/relationship-relation-id
    :relation-coordinate relation-coordinate
    :tx-update-relationship impl/tx-update-relationship
    :tx-delete-object impl/tx-delete-object
    :tx-delete-object-stream impl/tx-delete-object-stream
    :affected-relation-ids impl/affected-relation-ids
    :read-relationships impl/read-relationships}
   :extra-client-opt-keys #{}})

(defn- require-datomic-client!
  [client fn-name]
  (when-not (orchestration/client? client :datomic)
    (throw (ex-info (str fn-name " requires a Datomic EACL client.")
                    {:type :eacl/invalid-client
                     :eacl/error :eacl/invalid-client}))))

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
  "Returns the immutable Datomic DB held by an EACL-created snapshot."
  [snapshot]
  (orchestration/snapshot-db snapshot :datomic))

(defn expire-cache!
  ([acl]
   (orchestration/expire-cache! acl))
  ([acl source-lifecycle]
   (orchestration/expire-cache! acl source-lifecycle)))

(defn cache-stats
  [acl]
  (orchestration/cache-stats acl))

(defn export-cache-snapshot
  "Exports reusable authorization-cache entries as a bounded immutable value.

  Hosts persisting external bytes must authenticate and encoded-size-bound the
  envelope before deserialization. The value contains neither a Datomic DB nor
  process-local cache identity."
  [acl bounds]
  (require-datomic-client! acl "export-cache-snapshot")
  (orchestration/export-cache-snapshot acl bounds))

(defn restore-cache-snapshot!
  "Atomically restores one trusted, authenticated cache snapshot value."
  [acl snapshot bounds]
  (require-datomic-client! acl "restore-cache-snapshot!")
  (orchestration/restore-cache-snapshot! acl snapshot bounds))

(defn cache-content-revision
  "Returns this client's process-local reusable-cache content revision."
  [acl]
  (require-datomic-client! acl "cache-content-revision")
  (orchestration/cache-content-revision acl))

(defn refresh-metrics!
  ([acl] (orchestration/refresh-metrics! acl))
  ([acl opts] (orchestration/refresh-metrics! acl opts)))

(def prepare-cache-coherence!
  "Initializes missing Datomic cache generations on a quiesced connection."
  schema/prepare-cache-coherence!)

(defn current-zed-token
  "Returns a token for one newly captured current Datomic basis."
  [acl]
  (let [selected (eacl/snapshot acl)]
    (try
      (eacl/basis-token selected)
      (finally
        (eacl/release! selected)))))

(defn basis-instant
  "Returns the Datomic transaction instant for basis `t`, or nil for nil.

  The helper captures one current basis from `acl`; it is diagnostic and not
  part of an authorization request path."
  [acl t]
  (when t
    (let [selected (eacl/snapshot acl)]
      (try
        (:db/txInstant
         (d/entity (db selected) (d/t->tx t)))
        (finally
          (eacl/release! selected))))))

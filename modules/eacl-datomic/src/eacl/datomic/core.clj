(ns eacl.datomic.core
  "Datomic-backed EACL authorization views.

  Public reads, writes, consistency, caching, pagination, and lifecycle are
  implemented once by `eacl.client.orchestration`. This namespace contributes
  only Datomic's basis adapter, basis source, writer primitives, schema
  operations, direct-snapshot accessors, and Datomic-specific utilities."
  (:require [datomic.api :as d]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datomic.backend :as datomic-backend]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.indexed :as indexed]
            [eacl.datomic.schema :as schema]
            [eacl.migrations.v6-to-v7 :as migrations]
            [eacl.relationships.storage :as relationship-storage]))

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
    :write-schema! write-schema!}
   :impl
   {:validate-relationship-operation!
    impl/validate-relationship-operation!
    :relationship-relation-id impl/relationship-relation-id
    :tx-update-relationship impl/tx-update-relationship
    :tx-delete-object impl/tx-delete-object
    :tx-delete-object-stream impl/tx-delete-object-stream
    :affected-relation-ids impl/affected-relation-ids
    :read-relationships impl/read-relationships}
   :extra-client-opt-keys #{}})

(defn make-client
  "Builds a shared EACL `Acl` over a Datomic connection.

  `:auto-migrate-v6` is consumed by the storage-compatibility gate. Every
  remaining option belongs to the uniform EACL client contract."
  [conn config-opts]
  (migrations/assert-storage-compatible!
   conn {:auto-migrate-v6 (:auto-migrate-v6 config-opts)})
  (orchestration/make-client
   api conn (dissoc config-opts :auto-migrate-v6)))

(defn snapshot
  "Constructs a public snapshot over an admissible Datomic DB value."
  [acl db]
  (orchestration/direct-snapshot acl :datomic db))

(defn db
  "Returns the immutable Datomic DB wrapped by `snapshot`."
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

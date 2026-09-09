(ns eacl.datahike.migrations.v7-to-v8
  "Permission-only released-v7 to v8 Datahike upgrade.

  Relationship storage upgrades are a separate maintenance step. This
  namespace therefore examines only bounded schema-definition rows and never
  enumerates or rewrites application relationship data."
  (:require [datahike.api :as d]
            [eacl.datahike.schema :as schema]
            [eacl.relationships.upgrade :as storage-upgrade]))

(def permission-storage-version schema/permission-storage-version)

(def stamped-permission-storage-version
  schema/stamped-permission-storage-version)

(defn migrate!
  "Upgrades released-v7 flat permissions to canonical v8 expressions.

  `:schema` is optional when the Datahike schema singleton contains its source
  string. Any supplied schema must have exactly the stored v7 relation and
  permission denotation. Returns a bounded report."
  ([conn] (migrate! conn {}))
  ([conn {:keys [schema expression-limits] :as opts}]
   (when-let [unknown (seq (remove #{:schema :expression-limits}
                                   (keys opts)))]
     (throw
      (ex-info "Unknown Datahike v7->v8 migration option."
               {:type :eacl/invalid-config
                :eacl/error :eacl/invalid-config
                :unknown-keys (vec unknown)})))
   (when-not (or (nil? schema) (string? schema))
     (throw
      (ex-info "The Datahike v7->v8 :schema option must be a schema string."
               {:type :eacl/invalid-config
                :eacl/error :eacl/invalid-config
                :key :schema})))
   (schema/migrate-v7-permissions! conn schema expression-limits)))

(defn assert-permission-storage-compatible!
  "Read-only permission admission. All conversions require explicit migrate!."
  [conn options]
  (storage-upgrade/reject-auto-migration! options)
  (let [shape (schema/permission-storage-shape (d/db conn))]
    (if (contains? #{:expression :none} shape)
      :ok
      (throw (ex-info "EACL v8 requires canonical permission storage. Run the explicit permission migration."
                      {:type :eacl/permission-storage-version
                       :eacl/error :eacl/permission-storage-version
                       :backend :datahike
                       :detected (if (= :flat shape) :flat-v7 shape)
                       :required-version permission-storage-version
                       :migration-ns 'eacl.datahike.migrations.v7-to-v8})))))

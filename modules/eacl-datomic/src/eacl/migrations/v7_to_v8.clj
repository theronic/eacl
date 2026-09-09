(ns eacl.migrations.v7-to-v8
  "Permission-only released-v7 to v8 upgrade.

  Relationship storage upgrades are a separate maintenance step. This
  namespace never enumerates or rewrites them; only bounded schema-definition
  rows are read and one atomic permission replacement is submitted."
  (:require [datomic.api :as d]
            [eacl.datomic.schema :as schema]
            [eacl.relationships.upgrade :as storage-upgrade]))

(def permission-storage-version schema/permission-storage-version)

(defn migrate!
  "Upgrades released v7 flat permissions to canonical v8 expressions.

  `:schema` is optional for union-only released-v7 databases; when present it
  is fully parsed/resolved/bounded before additive schema installation. Relation
  identities must be unchanged. Returns a bounded report and never counts or
  scans relationship tuples."
  ([conn] (migrate! conn {}))
  ([conn {:keys [schema expression-limits] :as opts}]
   (when-let [unknown (seq (remove #{:schema :expression-limits} (keys opts)))]
     (throw
      (ex-info "Unknown v7->v8 migration option."
               {:type :eacl/invalid-config
                :eacl/error :eacl/invalid-config
                :unknown-keys (vec unknown)})))
   (when-not (or (nil? schema) (string? schema))
     (throw
      (ex-info "The v7->v8 :schema option must be a schema string."
               {:type :eacl/invalid-config
                :eacl/error :eacl/invalid-config
                :key :schema})))
   (schema/migrate-v7-permissions! conn schema expression-limits)))

(defn stamped-permission-storage-version [db]
  (:eacl/permission-storage-version
   (d/entity db [:eacl/id "schema-string"])))

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
                       :backend :datomic
                       :detected (if (= :flat shape) :flat-v7 shape)
                       :required-version permission-storage-version
                       :migration-ns 'eacl.migrations.v7-to-v8})))))

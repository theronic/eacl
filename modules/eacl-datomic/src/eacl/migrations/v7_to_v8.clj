(ns eacl.migrations.v7-to-v8
  "Permission-only released-v7 to v8 upgrade.

  Released v7 relationship tuples are already the v8 relationship ABI. This
  namespace never enumerates or rewrites them; only bounded schema-definition
  rows are read and one atomic permission replacement is submitted."
  (:require [datomic.api :as d]
            [eacl.datomic.schema :as schema]))

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
  "Fails startup on released-v7 flat or mixed permissions unless the caller
  explicitly opts into the permission-only migration."
  [conn {:keys [auto-migrate-v7 expression-limits]}]
  (let [shape (schema/permission-storage-shape (d/db conn))]
    (case shape
      (:expression :none) :ok
      :flat
      (if auto-migrate-v7
        (do
          (migrate! conn (if (map? auto-migrate-v7)
                           (assoc auto-migrate-v7
                                  :expression-limits expression-limits)
                           {:expression-limits expression-limits}))
          :migrated)
        (throw
         (ex-info
          "EACL v8 requires an explicit released-v7 permission upgrade."
          {:type :eacl/permission-storage-version
           :eacl/error :eacl/permission-storage-version
           :detected :flat-v7
           :required-version permission-storage-version
           :migration-ns 'eacl.migrations.v7-to-v8})))
      :mixed
      (throw
       (ex-info
        "EACL permission storage contains mixed flat and expression rows."
        {:type :eacl/permission-storage-version
         :eacl/error :eacl/permission-storage-version
         :detected :mixed
         :required-version permission-storage-version})))))

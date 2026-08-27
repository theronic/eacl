(ns eacl.datahike.migrations.v7-to-v8
  "Permission-only released-v7 to v8 Datahike upgrade.

  Released-v7 relationship tuples already use the v8 relationship ABI. This
  namespace therefore examines only bounded schema-definition rows and never
  enumerates or rewrites application relationship data."
  (:require [datahike.api :as d]
            [eacl.datahike.schema :as schema]))

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
  "Fails startup on released-v7 flat or mixed permissions unless the caller
  explicitly opts into the bounded permission-only migration."
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
          "EACL v8 requires an explicit released-v7 Datahike permission upgrade."
          {:type :eacl/permission-storage-version
           :eacl/error :eacl/permission-storage-version
           :backend :datahike
           :detected :flat-v7
           :required-version permission-storage-version
           :migration-ns 'eacl.datahike.migrations.v7-to-v8})))

      :mixed
      (throw
       (ex-info
        "EACL Datahike permission storage contains mixed flat and expression rows."
        {:type :eacl/permission-storage-version
         :eacl/error :eacl/permission-storage-version
         :backend :datahike
         :detected :mixed
         :required-version permission-storage-version})))))

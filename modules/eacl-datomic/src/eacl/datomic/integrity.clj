(ns eacl.datomic.integrity
  "Explicit, offline integrity diagnostics.

  Nothing in this namespace runs on EACL's authorization hot path. Callers
  choose when to pay for a database scan or a schema-version read."
  (:require [eacl.datomic.qualifiers :as qualifiers]
            [eacl.relationships.qualifier-integrity :as qualifier-integrity]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.indexed :as indexed]
            [eacl.relationships.endpoint-pair :as endpoint-pair]))

(defn client-schema-status
  "Captures one current client basis and reports whether it carries EACL's
  certified schema generation.

  The shared Acl has no construction-time schema generation to become stale:
  every read derives schema state from its selected immutable basis and the
  runtime registry is generation-keyed. Consequently the old :outdated state
  is intentionally gone. This explicit diagnostic performs one ordinary
  source acquisition and never runs on an authorization hot path."
  [client]
  (let [selected (eacl/snapshot client)]
    (try
      (let [db (orchestration/snapshot-db selected :datomic)
            generation (indexed/schema-version db)
            status (if (some? generation) :current :unstamped)]
        {:status status
         :current? (= :current status)
         :cache-enabled? (some? generation)
         :client-schema-version (some-> generation str)
         :database-schema-version (some-> generation str)})
      (finally
        (eacl/release! selected)))))

(defn dangling-relationship-halves
  "Returns a lazy sequence of relationship halves whose matching peer half is
  absent. This is an offline O(number-of-relationships) database scan."
  [db]
  (impl/orphaned-relationship-halves db))

(defn dangling-relationship-report
  "Scans dangling relationship halves without retaining the scan head.

  Returns the total count, counts by half, and at most `:sample-size` examples
  (default 20). This is an offline O(number-of-relationships) operation."
  ([db]
   (dangling-relationship-report db {}))
  ([db options]
   (endpoint-pair/dangling-report
    (dangling-relationship-halves db) options)))

(defn repair-tx-data
  "Lazily converts dangling-half maps into Datomic retractions.

  Each retraction carries an :eacl/relation-version stamp for the relation it
  clears. Without it a repair would change relationship data while publishing
  nothing, and any client with result caching enabled would keep serving the
  ghost grant this repair exists to remove.

  Stays lazy: stamps are emitted inline rather than deduplicated up front,
  which is safe because they are idempotent within a transaction."
  [dangling-halves]
  (mapcat (fn [{:keys [e attr v relation-eid]}]
            [[:db/retract e attr v]
             (impl/tx-relation-version-stamp relation-eid)])
          dangling-halves))

(defn repair-tx-batches
  "Returns lazy Datomic transaction batches for all dangling halves visible in
  `db`. The default batch size is 1000 dangling halves, keeping repair memory
  bounded.

  Batches are partitioned by HALF rather than by transaction op, so a
  retraction and the :eacl/relation-version stamp that publishes it always land
  in the same transaction. Partitioning the ops instead would let a batch
  boundary fall between them, and the batch holding the bare retraction would
  change relationship data while announcing nothing.

  Each batch also carries the source database's schema-generation guard. A
  batch prepared before a concurrent relation removal therefore fails instead
  of recreating the retracted relation eid with only a version stamp."
  ([db]
   (repair-tx-batches db {}))
  ([db {:keys [batch-size] :or {batch-size 1000}}]
   (when-not (and (integer? batch-size) (pos? batch-size))
     (throw (ex-info ":batch-size must be a positive integer."
                     {:type :eacl.integrity/invalid-options :eacl/error :eacl.integrity/invalid-options
                      :batch-size batch-size})))
   (map #(impl/guard-schema-version db (repair-tx-data %))
        (partition-all batch-size (dangling-relationship-halves db)))))

(defn qualifier-proof-input
  "Captures offline qualifier, ownership, source, version, and Relation proof inputs."
  [snapshot]
  (qualifier-integrity/proof-input (qualifiers/read-api) snapshot))

(defn qualifier-report
  ([snapshot] (qualifier-report snapshot {}))
  ([snapshot options]
   (qualifier-integrity/report (qualifier-proof-input snapshot) options)))

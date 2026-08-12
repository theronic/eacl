(ns eacl.datomic.integrity
  "Explicit, offline integrity diagnostics.

  Nothing in this namespace runs on EACL's authorization hot path. Callers
  choose when to pay for a database scan or a schema-version read."
  (:require [datomic.api :as d]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.indexed :as indexed]))

(defn client-schema-status
  "Reads the connection's current :eacl/schema-version once and compares it
  with the diagnostic generation captured when `client` was created (or last
  changed through that client's write-schema!).

  This is an explicit diagnostic for detecting out-of-band schema writes. EACL
  deliberately does not perform this read for every authorization operation.
  Authorization correctness does not depend on this diagnostic: each request
  derives its proof-keyed schema generation from its selected immutable DB."
  [client]
  (let [cached-version  (some-> client :opts :diagnostic-schema-version deref)
        current-version (some-> client :conn d/db indexed/schema-version)
        status          (cond
                          (and (nil? cached-version) (nil? current-version)) :unstamped
                          (= cached-version current-version) :current
                          :else :outdated)]
    {:status status
     :current? (= :current status)
     :cache-enabled? (some? cached-version)
     :client-schema-version (some-> cached-version str)
     :database-schema-version (some-> current-version str)}))

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
  ([db {:keys [sample-size] :or {sample-size 20}}]
   (when-not (and (integer? sample-size) (not (neg? sample-size)))
     (throw (ex-info ":sample-size must be a non-negative integer."
                     {:type :eacl.integrity/invalid-options
                      :sample-size sample-size})))
   (let [{:keys [count by-half sample]}
         (reduce (fn [{:keys [count] :as report} half]
                   (cond-> (-> report
                               (assoc :count (unchecked-inc count))
                               (update-in [:by-half (:half half)] (fnil unchecked-inc 0)))
                     (< count sample-size) (update :sample conj half)))
                 {:count 0 :by-half {:forward 0 :reverse 0} :sample []}
                 (dangling-relationship-halves db))]
     {:valid? (zero? count)
      :dangling-count count
      :by-half by-half
      :sample sample})))

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
                     {:type :eacl.integrity/invalid-options
                      :batch-size batch-size})))
   (map #(impl/guard-schema-version db (repair-tx-data %))
        (partition-all batch-size (dangling-relationship-halves db)))))

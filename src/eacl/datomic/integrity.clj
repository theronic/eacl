(ns eacl.datomic.integrity
  "Explicit, offline integrity diagnostics.

  Nothing in this namespace runs on EACL's authorization hot path. Callers
  choose when to pay for a database scan or a schema-version read."
  (:require [datomic.api :as d]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.indexed :as indexed]))

(defn client-schema-status
  "Reads the connection's current :eacl/schema-version once and compares it
  with the generation captured when `client` was created (or last changed via
  that client's write-schema!).

  This is an explicit diagnostic for detecting out-of-band schema writes. EACL
  deliberately does not perform this read for every authorization operation.
  Recreate an :outdated client; do not reuse its cached permission paths."
  [client]
  (let [cached-version  (some-> client :schema-state deref :schema-version)
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
  "Lazily converts dangling-half maps into Datomic retractions."
  [dangling-halves]
  (map (fn [{:keys [e attr v]}]
         [:db/retract e attr v])
       dangling-halves))

(defn repair-tx-batches
  "Returns lazy Datomic transaction batches for all dangling halves visible in
  `db`. The default batch size is 1000, keeping repair memory bounded."
  ([db]
   (repair-tx-batches db {}))
  ([db {:keys [batch-size] :or {batch-size 1000}}]
   (when-not (and (integer? batch-size) (pos? batch-size))
     (throw (ex-info ":batch-size must be a positive integer."
                     {:type :eacl.integrity/invalid-options
                      :batch-size batch-size})))
   (map vec
        (partition-all batch-size
                       (repair-tx-data (dangling-relationship-halves db))))))

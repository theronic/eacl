(ns eacl.datahike.integrity
  "Explicit, offline relationship-integrity diagnostics.

   Nothing here runs on an authorization hot path. A full report scans both
   relationship tuple attributes and probes the peer half of every datom."
  (:require [eacl.datahike.impl :as impl]))

(defn dangling-relationship-halves
  "Returns a lazy sequence of relationship halves whose peer half is absent."
  [db]
  (impl/orphaned-relationship-halves db))

(defn dangling-relationship-report
  "Returns total and per-half dangling counts plus a bounded sample.

   `:sample-size` defaults to 20. This is an offline operation that scans every
   relationship half and performs one exact peer-half index probe per half.
   With a logarithmic persistent index lookup, its expected cost is
   O(H log D) for H relationship halves in a database of D datoms."
  ([db]
   (dangling-relationship-report db {}))
  ([db {:keys [sample-size] :or {sample-size 20}}]
   (when-not (and (integer? sample-size)
                  (not (neg? sample-size)))
     (throw
      (ex-info
       ":sample-size must be a non-negative integer."
       {:type :eacl.integrity/invalid-options
        :sample-size sample-size})))
   (let [{:keys [count by-half sample]}
         (reduce
          (fn [{:keys [count] :as report} half]
            (cond-> (-> report
                        (assoc :count (unchecked-inc count))
                        (update-in
                         [:by-half (:half half)]
                         (fnil unchecked-inc 0)))
              (< count sample-size)
              (update :sample conj half)))
          {:count 0
           :by-half {:forward 0 :reverse 0}
           :sample []}
          (dangling-relationship-halves db))]
     {:valid? (zero? count)
      :dangling-count count
      :by-half by-half
      :sample sample})))

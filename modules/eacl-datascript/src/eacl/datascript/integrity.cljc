(ns eacl.datascript.integrity
  "Read-only, offline endpoint-pair integrity diagnostics."
  (:require [eacl.datascript.impl :as impl]))

(defn dangling-relationship-halves
  "Returns a lazy sequence of relationship halves whose exact peer is absent."
  [db]
  (impl/orphaned-relationship-halves db))

(defn dangling-relationship-report
  "Returns deterministic total/per-half dangling counts and a bounded sample.
  This scan never mutates the database."
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
                        (assoc :count (inc count))
                        (update-in
                         [:by-half (:half half)]
                         (fnil inc 0)))
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

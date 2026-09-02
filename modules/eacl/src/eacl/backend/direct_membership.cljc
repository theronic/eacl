(ns eacl.backend.direct-membership
  "Closed validation and exact scalar fallback for bounded direct membership.

  This namespace owns no permission semantics. It only normalizes one physical
  direct-relation descriptor and guarantees an aligned all-or-error Boolean
  vector against the adapter's already selected immutable basis."
  (:require [eacl.backend.v8 :as backend]
            [eacl.execution :as execution]
            [eacl.exact-integer :as exact-integer]
            [eacl.request.counters :as request-counters]))

(def cache-miss ::cache-miss)

(def ^:dynamic *physical-stats*
  "Optional observation-only atom for backend-neutral dispatcher telemetry."
  nil)

(defn- add-stat! [counter amount]
  (when *physical-stats*
    (swap! *physical-stats* update counter (fnil + 0) amount))
  nil)

(defn- add-stats!
  "One observer update for a burst of counters (previously seven separate
  dynamic-var checks and CAS swaps per batch)."
  [deltas]
  (when-let [stats *physical-stats*]
    (swap! stats #(merge-with + (or % {}) deltas)))
  nil)

(def ^:private request-keys #{:descriptor :candidates :direction})
(def ^:private forward-descriptor-keys
  #{:subject-type :subject-eid :relation-eid :resource-type})
(def ^:private reverse-descriptor-keys
  #{:resource-type :resource-eid :relation-eid :subject-type})

(defn- invalid-request! [message data]
  (throw
   (ex-info
    message
    (merge {:type :eacl.backend/invalid-direct-membership-batch
            :eacl/error :eacl.backend/invalid-direct-membership-batch}
           data))))

(defn- contract-violation! [adapter obligation value]
  (throw
   (ex-info
    "Backend returned a malformed direct-membership batch."
    {:type :eacl/backend-contract-violation
     :eacl/error :eacl/backend-contract-violation
     :backend (backend/backend-id adapter)
     :operation :direct-match-many?
     :obligation obligation
     :value value})))

(defn- closed-keys?
  "Key-set equality without building a set: same size and every expected
  key present."
  [m expected]
  (and (map? m)
       (= (count m) (count expected))
       (every? #(contains? m %) expected)))

(defn- valid-probe!
  "Per-probe equivalent of the `normalize-request` checks over one probe's
  direction, descriptor, and candidate — run before cache state can
  influence work, without the singleton request map or set allocations."
  [{:keys [direction descriptor candidate]}]
  (let [descriptor-keys (case direction
                          :forward forward-descriptor-keys
                          :reverse reverse-descriptor-keys
                          nil)]
    (when-not descriptor-keys
      (invalid-request! "Direct-membership batch direction is invalid."
                        {:direction direction
                         :supported #{:forward :reverse}}))
    (when-not (closed-keys? descriptor descriptor-keys)
      (invalid-request!
       "Direct-membership descriptor has unknown or missing fields."
       {:direction direction
        :expected-keys descriptor-keys
        :actual-keys (when (map? descriptor) (set (keys descriptor)))}))
    (doseq [field [:subject-type :resource-type]]
      (when-not (keyword? (get descriptor field))
        (invalid-request! "Direct-membership descriptor types must be keywords."
                          {:field field :value (get descriptor field)})))
    (doseq [field (if (= :forward direction)
                    [:subject-eid :relation-eid]
                    [:resource-eid :relation-eid])]
      (when-not (exact-integer/natural? (get descriptor field))
        (invalid-request!
         "Direct-membership descriptor identifiers must be portable natural integers."
         {:field field :value (get descriptor field)})))
    (let [candidate-type (if (= :forward direction)
                           (:resource-type descriptor)
                           (:subject-type descriptor))]
      (when-not (and (vector? candidate)
                     (= 2 (count candidate))
                     (= candidate-type (first candidate))
                     (exact-integer/natural? (second candidate)))
        (invalid-request!
         "Direct-membership candidates must be aligned typed identifier pairs."
         {:index 0 :candidate candidate
          :expected-type candidate-type})))))

(defn normalize-request
  "Validates and returns the closed portable batch request.

  Forward descriptors fix the subject endpoint and candidates name resources;
  reverse descriptors fix the resource endpoint and candidates name subjects."
  [request]
  (when-not (and (map? request) (= request-keys (set (keys request))))
    (invalid-request!
     "Direct-membership batch request has unknown or missing fields."
     {:expected-keys request-keys
      :actual-keys (when (map? request) (set (keys request)))}))
  (let [{:keys [descriptor candidates direction]} request
        descriptor-keys (case direction
                          :forward forward-descriptor-keys
                          :reverse reverse-descriptor-keys
                          nil)]
    (when-not descriptor-keys
      (invalid-request! "Direct-membership batch direction is invalid."
                        {:direction direction
                         :supported #{:forward :reverse}}))
    (when-not (and (map? descriptor)
                   (= descriptor-keys (set (keys descriptor))))
      (invalid-request!
       "Direct-membership descriptor has unknown or missing fields."
       {:direction direction
        :expected-keys descriptor-keys
        :actual-keys (when (map? descriptor) (set (keys descriptor)))}))
    (doseq [field [:subject-type :resource-type]]
      (when-not (keyword? (get descriptor field))
        (invalid-request! "Direct-membership descriptor types must be keywords."
                          {:field field :value (get descriptor field)})))
    (doseq [field (if (= :forward direction)
                    [:subject-eid :relation-eid]
                    [:resource-eid :relation-eid])]
      (when-not (exact-integer/natural? (get descriptor field))
        (invalid-request!
         "Direct-membership descriptor identifiers must be portable natural integers."
         {:field field :value (get descriptor field)})))
    (when-not (vector? candidates)
      (invalid-request! "Direct-membership candidates must be a vector."
                        {:value-type (some-> candidates type str)}))
    (when (> (count candidates) backend/maximum-direct-membership-batch-width)
      (invalid-request!
       "Direct-membership batch exceeds the certified maximum width."
       {:width (count candidates)
        :maximum-width backend/maximum-direct-membership-batch-width}))
    (let [candidate-type (if (= :forward direction)
                           (:resource-type descriptor)
                           (:subject-type descriptor))]
      (doseq [[index candidate] (map-indexed vector candidates)]
        (when-not (and (vector? candidate)
                       (= 2 (count candidate))
                       (= candidate-type (first candidate))
                       (exact-integer/natural? (second candidate)))
          (invalid-request!
           "Direct-membership candidates must be aligned typed identifier pairs."
           {:index index :candidate candidate
            :expected-type candidate-type}))))
    (when-not (= (count candidates) (count (distinct candidates)))
      (invalid-request! "Direct-membership candidates must be distinct."
                        {:width (count candidates)}))
    request))

(defn native-batch?
  [adapter]
  (backend/supports?
   adapter :direct-membership-batch
   backend/direct-membership-batch-capability))

(defn- scalar-match [direct-match {:keys [descriptor direction]} [_type eid]]
  (if (= :forward direction)
    (direct-match
     (:subject-type descriptor) (:subject-eid descriptor)
     (:relation-eid descriptor)
     (:resource-type descriptor) eid)
    (direct-match
     (:subject-type descriptor) eid
     (:relation-eid descriptor)
     (:resource-type descriptor) (:resource-eid descriptor))))

(defn ^:no-doc direct-match-many-checked?
  [adapter request]
  (let [{:keys [candidates]} request]
    (execution/check! execution/*contract*
                      :direct-membership-batch/before
                      {:candidate-count 0})
    (if (empty? candidates)
      []
      (let [native? (native-batch? adapter)
            direct-match (when-not native?
                           (backend/direct-match-invoker adapter))
            result
            (if native?
              (vec (backend/invoke adapter :direct-match-many? request))
              (loop [index 0
                     result (transient [])]
                (if (= index (count candidates))
                  (persistent! result)
                  (do
                    (execution/check!
                     execution/*contract*
                     :direct-membership-batch/scalar-probe
                     {:candidate-count index})
                    (recur (inc index)
                           (conj! result
                                  (scalar-match direct-match request
                                                (nth candidates index))))))))
            matched-count
            (when-not native?
              (reduce (fn [total matched?]
                        (if (true? matched?) (inc total) total))
                      0
                      result))]
        (when *physical-stats*
          (add-stats! {:scalar-equivalent-predicates (count candidates)
                       :physical-subgroups 1
                       :adapter-commands (if native? 1 (count candidates))
                       :exact-seeks (if native? 0 (count candidates))
                       :galloping-reseeks 0
                       :prefix-values 0
                       :batch-overread 0}))
        (when-not native?
          (request-counters/add-commands! (count candidates))
          (request-counters/add-probes! (count candidates))
          (request-counters/add-fetched-values! matched-count)
          (add-stat! :adapter-fetched-values matched-count))
        (execution/check!
         execution/*contract*
         :direct-membership-batch/after
         {:candidate-count (count candidates)})
        (when-not (= (count candidates) (count result))
          (contract-violation!
           adapter :aligned-cardinality
           {:expected (count candidates) :actual (count result)}))
        (when-not (every? boolean? result)
          (contract-violation! adapter :aligned-boolean-vector :redacted))
        result))))

(defn direct-match-many?
  "Returns one Boolean per input candidate, or throws without returning a
  partial vector. Native and scalar execution have the same normalized input,
  selected basis, ordering, cancellation cut points, and output contract."
  [adapter request]
  (direct-match-many-checked? adapter (normalize-request request)))

(def ^:private probe-keys #{:descriptor :candidate :direction})

(defn- group-order-key [[direction descriptor]]
  (if (= :forward direction)
    [0 (str (:subject-type descriptor)) (:subject-eid descriptor)
     (:relation-eid descriptor) (str (:resource-type descriptor))]
    [1 (str (:resource-type descriptor)) (:resource-eid descriptor)
     (:relation-eid descriptor) (str (:subject-type descriptor))]))

(defn dispatch
  "Evaluates an ordered vector of physical leaf probes.

  `cache-lookup` must return a Boolean only for a proof-compatible completed
  hit and `cache-miss` otherwise. Misses are deterministically grouped by
  normalized descriptor, sorted and deduplicated for locality, split at the
  physical cap, then scattered back to the original generator order. No
  partial result escapes if any group fails."
  ([adapter probes]
   (dispatch adapter probes (constantly cache-miss)))
  ([adapter probes cache-lookup]
   (when-not (vector? probes)
     (invalid-request! "Direct-membership probes must be a vector."
                       {:value-type (some-> probes type str)}))
   (when-not (fn? cache-lookup)
     (invalid-request! "Direct-membership cache lookup must be callable."
                       {:value-type (some-> cache-lookup type str)}))
   (let [initial (vec (repeat (count probes) ::unresolved))
         {:keys [results misses cache-hits]}
         (reduce-kv
          (fn [{:keys [results misses cache-hits]} index probe]
            (when-not (closed-keys? probe probe-keys)
              (invalid-request!
               "Direct-membership probe has unknown or missing fields."
               {:index index
                :expected-keys probe-keys
                :actual-keys (when (map? probe) (set (keys probe)))}))
            ;; Establishes direction, descriptor, and typed-candidate
            ;; validity before cache state can influence work.
            (valid-probe! probe)
            (let [cached (cache-lookup probe)]
              (cond
                (boolean? cached)
                {:results (assoc results index cached)
                 :misses misses :cache-hits (inc cache-hits)}

                (= cache-miss cached)
                {:results results :cache-hits cache-hits
                 :misses (conj misses (assoc probe :index index))}

                :else
                (invalid-request!
                 "Direct-membership cache lookup returned an invalid value."
                 {:index index :value cached}))))
          {:results initial :misses [] :cache-hits 0}
          probes)
         groups (group-by (juxt :direction :descriptor) misses)
         ;; Decorate-sort: the group key vector is built once per group.
         ordered-groups (map second
                             (sort-by first compare
                                      (map (fn [entry]
                                             [(group-order-key (key entry))
                                              entry])
                                           groups)))
         completed
         (reduce
          (fn [results [[direction descriptor] entries]]
            (let [candidate->indexes
                  (reduce
                   (fn [result {:keys [candidate index]}]
                     (update result candidate (fnil conj []) index))
                   {}
                   entries)
                  candidates
                  (->> (keys candidate->indexes)
                       (map (fn [candidate]
                              [[(str (first candidate)) (second candidate)]
                               candidate]))
                       (sort-by first compare)
                       (mapv second))]
              (reduce
               (fn [results candidate-chunk]
                 (let [request {:direction direction
                                :descriptor descriptor
                                :candidates (vec candidate-chunk)}
                       decisions (direct-match-many-checked? adapter request)]
                   (reduce
                    (fn [results [candidate decision]]
                      (reduce #(assoc %1 %2 decision)
                              results
                              (get candidate->indexes candidate)))
                    results
                    (map vector candidate-chunk decisions))))
               results
               (partition-all backend/maximum-direct-membership-batch-width
                              candidates))))
          results
          ordered-groups)]
     (add-stat! :cache-hits cache-hits)
     (when (some #{::unresolved} completed)
       (contract-violation! adapter :complete-scatter :redacted))
     completed)))

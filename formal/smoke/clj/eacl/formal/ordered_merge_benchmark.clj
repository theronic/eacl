(ns eacl.formal.ordered-merge-benchmark
  "Paired regression gate for the optimized ordered EID merge.

  The generated Dafny merge remains an executable refinement oracle. It is
  deliberately not invoked per EID: this benchmark verifies that selecting a
  generated engine adds no work to the source-specialized hot merge."
  (:refer-clojure :exclude [run!])
  (:require
   [eacl.engine.v8 :as engine]
   [eacl.formal.production-kernel :as production]
   [eacl.subproblem-cache :as subproblem]))

(defn- elapsed-ms
  [f]
  (let [started (System/nanoTime)
        value (f)]
    {:value value
     :elapsed-ms
     (/ (double (- (System/nanoTime) started)) 1000000.0)}))

(defn- percentile
  [samples proportion]
  (let [ordered (vec (sort samples))
        index
        (min
         (dec (count ordered))
         (long (Math/floor (* proportion (count ordered)))))]
    (nth ordered index)))

(defn- merge-function
  []
  (deref
   (ns-resolve
    (symbol (namespace ::engine/loaded))
    'merge-eid-seqs)))

(defn- run-batch
  [merge-fn selection direction streams demand repetitions]
  (elapsed-ms
   (fn []
     (loop [iteration 0
            checksum 0]
       (if (= iteration repetitions)
         checksum
         (recur
          (inc iteration)
          (reduce
           +
           checksum
           (take
            demand
            (binding [subproblem/*engine-selection* selection]
              (merge-fn direction streams))))))))))

(defn run!
  "Runs one paired source-specialization benchmark.

  Defaults exercise a 20-value page from a 20,000-value interleaved union.
  `:repetitions` batches enough identical work into each timing sample to keep
  timer and scheduler noise from dominating."
  ([]
   (run! {}))
  ([{:keys [value-count demand repetitions warmup samples direction]
     :or {value-count 20000
          demand 20
          repetitions 1000
          warmup 20
          samples 100
          direction :asc}}]
   (when-not (contains? #{:asc :desc} direction)
     (throw
      (ex-info
       "Ordered-merge gate requires :asc or :desc."
       {:direction direction})))
   (let [merge-fn (merge-function)
         streams
         (case direction
           :asc
           [(range 0 value-count 2)
            (range 1 value-count 2)]
           :desc
           [(range (dec value-count) -1 -2)
            (range (- value-count 2) -1 -2)])
         legacy-selection :legacy-authoritative
         verified-selection
         {:mode :verified-authoritative
          :kernel production/generated-java-kernel}
         expected
         (vec
          (take
           demand
           (binding [subproblem/*engine-selection* legacy-selection]
             (merge-fn direction streams))))
         actual
         (vec
          (take
           demand
           (binding [subproblem/*engine-selection* verified-selection]
             (merge-fn direction streams))))
         legacy-times (atom [])
         verified-times (atom [])]
     (when-not (= expected actual)
       (throw
        (ex-info
         "Ordered-merge specialization changed with engine selection."
         {:demand demand
          :legacy-count (count expected)
          :verified-count (count actual)})))
     (dotimes [iteration (+ warmup samples)]
       (let [legacy-first? (even? iteration)
             [legacy-result verified-result]
             (if legacy-first?
               [(run-batch
                 merge-fn legacy-selection direction streams demand repetitions)
                (run-batch
                 merge-fn verified-selection direction streams demand repetitions)]
               (let [verified-result
                     (run-batch
                      merge-fn verified-selection direction
                      streams demand repetitions)
                     legacy-result
                     (run-batch
                      merge-fn legacy-selection direction
                      streams demand repetitions)]
                 [legacy-result verified-result]))]
         (when-not (= (:value legacy-result) (:value verified-result))
           (throw
            (ex-info
             "Ordered-merge specialization checksum diverged."
             {:iteration iteration})))
         (when (>= iteration warmup)
           (swap!
            legacy-times
            conj
            (/ (:elapsed-ms legacy-result) repetitions))
           (swap!
            verified-times
            conj
            (/ (:elapsed-ms verified-result) repetitions)))))
     (let [legacy-p50 (percentile @legacy-times 0.50)
           legacy-p95 (percentile @legacy-times 0.95)
           verified-p50 (percentile @verified-times 0.50)
           verified-p95 (percentile @verified-times 0.95)]
       {:fixture
        {:value-count value-count
         :demand demand
         :repetitions repetitions
         :warmup warmup
         :samples samples
         :direction direction
         :shape :interleaved-two-stream-union}
        :resource-dimensions
        {:backend-operations 0
         :input-values value-count
         :demanded-values demand
         :logical-output-values demand
         :host-heap-bytes :not-established
         :wall-time :benchmark-only}
        :legacy-ms @legacy-times
        :verified-selection-ms @verified-times
        :legacy-p50-ms legacy-p50
        :legacy-p95-ms legacy-p95
        :verified-selection-p50-ms verified-p50
        :verified-selection-p95-ms verified-p95
        :p50-latency-ratio (/ verified-p50 legacy-p50)
        :p95-latency-ratio (/ verified-p95 legacy-p95)}))))

(defn run-gate!
  "Runs independent trials and gates the median trial-level p95 ratio."
  ([]
   (run-gate! {}))
  ([{:keys [trials maximum-median-p95-ratio]
     :or {trials 5
          maximum-median-p95-ratio 1.05}
     :as options}]
   (when-not (and (integer? trials) (pos? trials))
     (throw
      (ex-info
       "Ordered-merge gate requires a positive trial count."
       {:trials trials})))
   (when-not (and (number? maximum-median-p95-ratio)
                  (pos? maximum-median-p95-ratio))
     (throw
      (ex-info
       "Ordered-merge gate requires a positive p95 ratio."
       {:maximum-median-p95-ratio maximum-median-p95-ratio})))
   (let [run-options
         (dissoc options :trials :maximum-median-p95-ratio)
         results (mapv (fn [_] (run! run-options)) (range trials))
         p50-ratios (mapv :p50-latency-ratio results)
         p95-ratios (mapv :p95-latency-ratio results)
         median-p95 (percentile p95-ratios 0.50)]
     {:fixture
      (assoc (:fixture (first results))
             :independent-trials trials
             :aggregation :median-of-trial-p95-ratios)
      :required
      {:maximum-median-p95-ratio maximum-median-p95-ratio
       :identical-value-and-checksum-every-sample true}
      :summary
      {:median-p50-ratio (percentile p50-ratios 0.50)
       :median-p95-ratio median-p95
       :passing-trials
       (count
        (filter #(<= % maximum-median-p95-ratio) p95-ratios))
       :status
       (if (<= median-p95 maximum-median-p95-ratio)
         :passed
         :failed)}
      :trials results})))

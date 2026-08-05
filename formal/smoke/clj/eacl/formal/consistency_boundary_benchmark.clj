(ns eacl.formal.consistency-boundary-benchmark
  "Paired wall-time gate for generated consistency decisions.

  This measures the complete captured-current orchestration used by public
  backend clients. Identity makes its same-source proof reflexive, so it does
  not perform source-scope I/O. It does not claim JVM heap, CPU, backend I/O,
  or worst-case latency bounds."
  (:refer-clojure :exclude [run!])
  (:require
   [eacl.backend.v8 :as backend]
   [eacl.consistency :as consistency]
   [eacl.formal.production-kernel :as production]
   [eacl.spicedb.consistency :as public-consistency]))

(defn- percentile
  [samples proportion]
  (let [ordered (vec (sort samples))
        index
        (min
         (dec (count ordered))
         (long (Math/floor (* proportion (count ordered)))))]
    (nth ordered index)))

(defn- benchmark-adapter
  []
  (let [self (atom nil)
        base-operations
        (into
         {}
         (map (fn [operation] [operation (fn [& _] nil)]))
         backend/required-snapshot-operations)
        adapter
        (backend/make-adapter
         {:id :consistency-benchmark
          :capabilities
          {:consistency #{:minimize-latency}
           :snapshots #{:current}
           :source #{:stable-scope :graph-head
                     :anchor-membership :order-hint :exact-locator}
           :cursor #{}
           :transactions #{}
           :cache-proofs #{}
           :runtime #{:clj}}
          :operations
          (merge
           base-operations
           {:snapshot-id (fn [] [:source nil 1])
            :source-scope
            (fn [] {:source-id "source" :branch nil})
            :graph-head
            (fn [] {:graph-anchor "head"
                    :order-hint 1
                    :exact-locator 1})
            :contains-anchor? (constantly true)
            :order-hint (constantly 1)
            :exact-locator (constantly 1)
            :select-current (fn [] @self)})})]
    (reset! self adapter)
    adapter))

(defn- run-batch
  [adapter options repetitions]
  (let [started (System/nanoTime)]
    (loop [iteration 0
           checksum 0]
      (if (= iteration repetitions)
        {:checksum checksum
         :nanoseconds-per-call
         (/ (double (- (System/nanoTime) started))
            repetitions)}
        (let [selected
              (:adapter
               (consistency/captured-current-selection
                adapter
                public-consistency/minimize-latency
                options))]
          (recur
           (inc iteration)
           (+ checksum (System/identityHashCode selected))))))))

(defn run!
  "Runs one paired trial and returns per-request wall samples.

  Alternating order reduces monotonic warmup bias. Batching keeps timer noise
  below the generated FFI cost."
  ([]
   (run! {}))
  ([{:keys [repetitions warmup samples]
     :or {repetitions 2000
          warmup 10
          samples 40}}]
   (let [adapter (benchmark-adapter)
         legacy-options
         {:coherence-authority :managed
          :engine-selection :legacy-authoritative}
         verified-options
         {:coherence-authority :managed
          :engine-selection
          {:mode :verified-authoritative
           :kernel production/generated-java-kernel}}
         legacy-times (atom [])
         verified-times (atom [])]
     (dotimes [iteration (+ warmup samples)]
       (let [legacy-first? (even? iteration)
             [legacy-result verified-result]
             (if legacy-first?
               [(run-batch adapter legacy-options repetitions)
                (run-batch adapter verified-options repetitions)]
               (let [verified-result
                     (run-batch adapter verified-options repetitions)
                     legacy-result
                     (run-batch adapter legacy-options repetitions)]
                 [legacy-result verified-result]))]
         (when-not (= (:checksum legacy-result)
                      (:checksum verified-result))
           (throw
            (ex-info
             "Consistency boundary benchmark changed the selected adapter."
             {:iteration iteration})))
         (when (>= iteration warmup)
           (swap! legacy-times conj (:nanoseconds-per-call legacy-result))
           (swap!
            verified-times
            conj
            (:nanoseconds-per-call verified-result)))))
     (let [legacy-p50 (percentile @legacy-times 0.50)
           legacy-p95 (percentile @legacy-times 0.95)
           verified-p50 (percentile @verified-times 0.50)
           verified-p95 (percentile @verified-times 0.95)]
       {:fixture
        {:path :captured-current
         :repetitions repetitions
         :warmup warmup
         :samples samples
         :response-token false}
        :logical-work
        {:capability-observations 1
         :plan-decisions 1
         :authentication-attempts 0
         :backend-selection-calls 0
         :validation-decisions 0
         :source-scope-reads 0
         :contains-anchor-calls 0
         :graph-head-reads 0
         :order-hint-reads 0
         :exact-locator-reads 0}
        :resource-qualification
        {:wall-time :host-specific-measurement
         :jvm-live-heap-bytes :not-established
         :cpu-time :not-established
         :backend-io :none-in-fixture}
        :legacy-ns @legacy-times
        :verified-ns @verified-times
        :legacy-p50-ns legacy-p50
        :legacy-p95-ns legacy-p95
        :verified-p50-ns verified-p50
        :verified-p95-ns verified-p95
        :p50-ratio (/ verified-p50 legacy-p50)
        :p95-ratio (/ verified-p95 legacy-p95)
        :p50-absolute-overhead-ns (- verified-p50 legacy-p50)
        :p95-absolute-overhead-ns (- verified-p95 legacy-p95)}))))

(defn run-gate!
  "Runs independent trials with absolute and relative median-p95 gates."
  ([]
   (run-gate! {}))
  ([{:keys [trials maximum-median-p95-ratio
            maximum-median-p95-absolute-overhead-ns]
     :or {trials 5
          maximum-median-p95-ratio 12.0
          maximum-median-p95-absolute-overhead-ns 10000.0}
     :as options}]
   (let [run-options
         (dissoc
          options
          :trials
          :maximum-median-p95-ratio
          :maximum-median-p95-absolute-overhead-ns)
         results (mapv (fn [_] (run! run-options)) (range trials))
         p50-ratios (mapv :p50-ratio results)
         p95-ratios (mapv :p95-ratio results)
         p50-overheads (mapv :p50-absolute-overhead-ns results)
         p95-overheads (mapv :p95-absolute-overhead-ns results)
         median-p95-ratio (percentile p95-ratios 0.50)
         median-p95-overhead (percentile p95-overheads 0.50)
         passed?
         (and
          (<= median-p95-ratio maximum-median-p95-ratio)
          (<= median-p95-overhead
              maximum-median-p95-absolute-overhead-ns))]
     {:fixture
      (assoc
       (:fixture (first results))
       :independent-trials trials
       :aggregation :median-of-trial-percentiles)
      :required
      {:maximum-median-p95-ratio maximum-median-p95-ratio
       :maximum-median-p95-absolute-overhead-ns
       maximum-median-p95-absolute-overhead-ns
       :identical-selected-adapter-every-call true
       :exact-logical-work-correspondence true}
      :summary
      {:median-p50-ratio (percentile p50-ratios 0.50)
       :median-p95-ratio median-p95-ratio
       :median-p50-absolute-overhead-ns
       (percentile p50-overheads 0.50)
       :median-p95-absolute-overhead-ns median-p95-overhead
       :status (if passed? :passed :failed)}
      :logical-work (:logical-work (first results))
      :resource-qualification
      (:resource-qualification (first results))
      :trials results})))

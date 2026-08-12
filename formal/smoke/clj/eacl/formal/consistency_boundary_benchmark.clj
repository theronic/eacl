(ns eacl.formal.consistency-boundary-benchmark
  "Absolute wall-time gate for the generated consistency decision boundary.

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
           :source #{:stable-scope :source-lifecycle
                     :native-revision :order-hint :exact-locator}
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
            :source-lifecycle (constantly "benchmark-lifecycle")
            :native-revision
            (constantly {:revision 1 :exact-locator 1})
            :order-hint (constantly 1)
            :exact-locator (constantly 1)
            :select-current (fn [] @self)})})]
    (reset! self adapter)
    adapter))

(defn- run-batch
  [adapter repetitions]
  (let [options
        {:decision-kernel production/default-selection}
        started (System/nanoTime)]
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
  "Returns generated-boundary per-request wall-time samples."
  ([]
   (run! {}))
  ([{:keys [repetitions warmup samples]
     :or {repetitions 2000
          warmup 10
          samples 40}}]
   (let [adapter (benchmark-adapter)
         observations
         (mapv
          (fn [_]
            (run-batch adapter repetitions))
          (range (+ warmup samples)))
         measured (subvec observations warmup)
         checksums (mapv :checksum measured)
         times (mapv :nanoseconds-per-call measured)]
     (when-not (apply = checksums)
       (throw
        (ex-info
         "Consistency boundary selected different adapters."
         {:checksums checksums})))
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
       :revision-validation-calls 0
       :native-revision-reads 0
       :order-hint-reads 0
       :exact-locator-reads 0}
      :resource-qualification
      {:wall-time :host-specific-measurement
       :jvm-live-heap-bytes :not-established
       :cpu-time :not-established
       :backend-io :none-in-fixture}
      :generated-ns times
      :generated-p50-ns (percentile times 0.50)
      :generated-p95-ns (percentile times 0.95)})))

(defn run-gate!
  "Runs independent trials with an absolute median-p95 gate."
  ([]
   (run-gate! {}))
  ([{:keys [trials maximum-median-p95-ns]
     :or {trials 5
          maximum-median-p95-ns 15000.0}
     :as options}]
   (let [run-options
         (dissoc options :trials :maximum-median-p95-ns)
         results (mapv (fn [_] (run! run-options)) (range trials))
         p95-values (mapv :generated-p95-ns results)
         median-p95 (percentile p95-values 0.50)
         passed? (<= median-p95 maximum-median-p95-ns)]
     {:fixture
      (assoc
       (:fixture (first results))
       :independent-trials trials
       :aggregation :median-of-trial-percentiles)
      :required
      {:maximum-median-p95-ns maximum-median-p95-ns
       :identical-selected-adapter-every-call true
       :exact-logical-work-correspondence true}
      :summary
      {:median-p95-ns median-p95
       :status (if passed? :passed :failed)}
      :logical-work (:logical-work (first results))
      :resource-qualification (:resource-qualification (first results))
      :trials results})))

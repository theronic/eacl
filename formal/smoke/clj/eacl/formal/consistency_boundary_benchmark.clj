(ns eacl.formal.consistency-boundary-benchmark
  "Absolute wall-time gate for the generated consistency decision boundary.

  This measures the minimize-latency source plan used before public Acl
  acquisition. The plan reads only source-static capabilities and performs no
  acquisition or source-scope I/O. Retained Snapshot reads use their separate
  assertion boundary and never enter this source-selection plan. It does not
  claim JVM heap, CPU, backend I/O, or worst-case latency bounds."
  (:refer-clojure :exclude [run!])
  (:require
   [eacl.backend.source :as source]
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

(defn- benchmark-source
  []
  (source/make-source
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
    :basis-ownership :borrowed
    :operations
    {:source-scope (constantly {:source-id "source" :branch nil})
     :source-lifecycle (constantly "benchmark-lifecycle")
     :acquire-current! (fn [& _] nil)
     :acquire-authoritative! (fn [& _] nil)
     :acquire-at-least! (fn [& _] nil)
     :acquire-exact! (fn [& _] nil)
     :release! (fn [& _] nil)}}))

(defn- run-batch
  [source repetitions]
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
        (let [action
              (consistency/selection-plan
               source
               {:mode public-consistency/minimize-latency}
               options)]
          (recur
           (inc iteration)
           (+ checksum (hash action))))))))

(defn run!
  "Returns generated-boundary per-request wall-time samples."
  ([]
   (run! {}))
  ([{:keys [repetitions warmup samples]
     :or {repetitions 2000
          warmup 10
          samples 40}}]
   (let [source (benchmark-source)
         observations
         (mapv
          (fn [_]
            (run-batch source repetitions))
          (range (+ warmup samples)))
         measured (subvec observations warmup)
         checksums (mapv :checksum measured)
         times (mapv :nanoseconds-per-call measured)]
     (when-not (apply = checksums)
       (throw
        (ex-info
         "Consistency boundary returned different plan actions."
         {:checksums checksums})))
     {:fixture
      {:path :minimize-latency-plan
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
       :exact-locator-reads 0
       :source-lifecycle-reads 0
       :snapshot-id-reads 0
       :basis-kind-reads 0}
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
       :identical-plan-action-every-call true
       :exact-logical-work-correspondence true}
      :summary
      {:median-p95-ns median-p95
       :status (if passed? :passed :failed)}
      :logical-work (:logical-work (first results))
      :resource-qualification (:resource-qualification (first results))
      :trials results})))

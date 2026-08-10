(ns eacl.formal.consistency-boundary-benchmark
  "Node/CLJS absolute gate for the generated consistency boundary."
  (:refer-clojure :exclude [run!])
  (:require
   [eacl.backend.v8 :as backend]
   [eacl.consistency :as consistency]
   [eacl.formal.production-kernel-js :as production]
   [eacl.spicedb.consistency :as public-consistency]))

(defn- percentile
  [samples proportion]
  (let [ordered (vec (sort samples))
        index
        (min
         (dec (count ordered))
         (js/Math.floor (* proportion (count ordered))))]
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
           :runtime #{:cljs}}
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
        {:coherence-authority :managed
         :decision-kernel production/default-selection}
        started (.now js/performance)]
    (loop [iteration 0
           checksum 0]
      (if (= iteration repetitions)
        {:checksum checksum
         :nanoseconds-per-call
         (/ (* 1000000.0 (- (.now js/performance) started))
            repetitions)}
        (let [selected
              (:adapter
               (consistency/captured-current-selection
                adapter
                public-consistency/minimize-latency
                options))]
          (recur (inc iteration) (+ checksum (hash selected))))))))

(defn run!
  ([]
   (run! {}))
  ([{:keys [repetitions warmup samples]
     :or {repetitions 2000
          warmup 10
          samples 40}}]
   (let [adapter (benchmark-adapter)
         observations
         (mapv
          (fn [_] (run-batch adapter repetitions))
          (range (+ warmup samples)))
         measured (subvec observations warmup)
         checksums (mapv :checksum measured)
         times (mapv :nanoseconds-per-call measured)]
     (when-not (apply = checksums)
       (throw
        (ex-info
         "CLJS consistency boundary selected different adapters."
         {:checksums checksums})))
     {:generated-ns times
      :generated-p50-ns (percentile times 0.50)
      :generated-p95-ns (percentile times 0.95)})))

(defn run-gate!
  ([]
   (run-gate! {}))
  ([{:keys [trials maximum-median-p95-ns]
     :or {trials 5
          maximum-median-p95-ns 30000.0}
     :as options}]
   (let [run-options
         (dissoc options :trials :maximum-median-p95-ns)
         results (mapv (fn [_] (run! run-options)) (range trials))
         p95-values (mapv :generated-p95-ns results)
         median-p95 (percentile p95-values 0.50)
         passed? (<= median-p95 maximum-median-p95-ns)]
     {:runtime :node-cljs
      :fixture
      {:path :captured-current
       :repetitions (or (:repetitions options) 2000)
       :warmup (or (:warmup options) 10)
       :samples (or (:samples options) 40)
       :independent-trials trials
       :response-token false}
      :required {:maximum-median-p95-ns maximum-median-p95-ns}
      :summary
      {:median-p95-ns median-p95
       :status (if passed? :passed :failed)}
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
       :javascript-heap-bytes :not-established
       :cpu-time :not-established
       :backend-io :none-in-fixture}
      :trials results})))

(defn -main
  []
  (let [result (run-gate!)]
    (println (pr-str result))
    (when (= :failed (get-in result [:summary :status]))
      (throw
       (ex-info "CLJS consistency boundary gate failed." result)))))

(set! *main-cli-fn* -main)

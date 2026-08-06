(ns eacl.formal.cursor-rebase-benchmark
  "Node/CLJS host measurements for generated current-cursor identity rebase.

  Dafny proves the logical inspection bound. This runner measures the complete
  CLJS-to-generated-JavaScript boundary. Heap deltas are deliberately named
  post-call uncollected deltas: V8 may collect during a call, so they are
  regression evidence rather than a retained-heap or peak-memory theorem."
  (:refer-clojure :exclude [run!])
  (:require
   [eacl.formal.production-kernel-js :as production]
   [eacl.verified-kernel :as verified]))

(def ^:private selection
  {:kernel production/generated-javascript-kernel})

(def ^:private cursor-rebase-chunk-items
  (verified/cursor-rebase-chunk-limit))

(def ^:private minimum-scaling-size
  (* 2 cursor-rebase-chunk-items))

(def ^:private minimum-scaling-span
  4)

(defn- percentile
  [samples proportion]
  (let [ordered (vec (sort samples))
        index
        (min
         (dec (count ordered))
         (js/Math.floor (* proportion (count ordered))))]
    (nth ordered index)))

(defn- legacy-decision
  [values bound-eid]
  (loop [ordinal 0]
    (if (= ordinal (count values))
      {:status :restarted
       :inspected-count ordinal}
      (if (= bound-eid (nth values ordinal))
        {:status :rebased
         :ordinal ordinal
         :inspected-count (inc ordinal)}
        (recur (inc ordinal))))))

(defn- heap-used
  []
  (.-heapUsed (.memoryUsage js/process)))

(defn- measured-call
  [operation]
  (when (fn? (.-gc js/global))
    (.gc js/global))
  (let [heap-before (heap-used)
        started (.now js/performance)
        value (operation)
        elapsed-ns (* 1000000.0 (- (.now js/performance) started))
        heap-after (heap-used)]
    {:value value
     :elapsed-ns elapsed-ns
     :post-call-uncollected-heap-delta
     (max 0 (- heap-after heap-before))}))

(defn- summarize
  [samples]
  {:p50-elapsed-ns
   (percentile (mapv :elapsed-ns samples) 0.50)
   :p95-elapsed-ns
   (percentile (mapv :elapsed-ns samples) 0.95)
   :p50-post-call-uncollected-heap-delta
   (percentile
    (mapv :post-call-uncollected-heap-delta samples)
    0.50)
   :p95-post-call-uncollected-heap-delta
   (percentile
    (mapv :post-call-uncollected-heap-delta samples)
    0.95)})

(defn- measure-case
  [size scenario warmup samples]
  (let [values (mapv #(* 2 %) (range size))
        bound-eid
        (case scenario
          :present-at-tail (peek values)
          :absent (inc (* 2 size)))
        input {:values values :bound-eid bound-eid}
        expected (legacy-decision values bound-eid)
        legacy #(legacy-decision values bound-eid)
        generated
        #(verified/decide-cursor-bound-rebase
          selection
          (:values input)
          (:bound-eid input))
        legacy-samples (atom [])
        generated-samples (atom [])]
    (dotimes [iteration (+ warmup samples)]
      (let [legacy-first? (even? iteration)
            [legacy-result generated-result]
            (if legacy-first?
              [(measured-call legacy)
               (measured-call generated)]
              (let [generated-result (measured-call generated)
                    legacy-result (measured-call legacy)]
                [legacy-result generated-result]))]
        (when-not (= expected
                     (:value legacy-result)
                     (:value generated-result))
          (throw
           (ex-info
            "Cursor rebase benchmark changed its semantic result."
            {:size size
             :scenario scenario
             :expected expected
             :legacy (:value legacy-result)
             :generated (:value generated-result)})))
        (when (>= iteration warmup)
          (swap! legacy-samples conj legacy-result)
          (swap! generated-samples conj generated-result))))
    {:size size
     :scenario scenario
     :expected expected
     :legacy (summarize @legacy-samples)
     :generated (summarize @generated-samples)}))

(defn- normalized-ratio
  [measurements path]
  (let [smallest (first measurements)
        largest (last measurements)
        small-per-item
        (/ (double (get-in smallest path)) (:size smallest))
        large-per-item
        (/ (double (get-in largest path)) (:size largest))]
    (/ large-per-item small-per-item)))

(defn run!
  ([]
   (run! {}))
  ([{:keys [sizes warmup samples]
     :or {sizes [32768 65536 131072]
          warmup 20
          samples 31}}]
   (let [by-scenario
         (into
          {}
          (map
           (fn [scenario]
             [scenario
              (mapv
               #(measure-case % scenario warmup samples)
               sizes)]))
          [:present-at-tail :absent])]
     {:fixture
      {:sizes sizes
       :warmup warmup
       :samples samples
       :paired-order :alternating
       :scaling-domain :multi-chunk-current-denotation
       :runtime :cljs-generated-javascript
       :node-expose-gc? (fn? (.-gc js/global))}
      :measurements by-scenario
      :normalized-scaling
      (into
       {}
       (map
        (fn [[scenario measurements]]
          [scenario
           {:p50-latency-per-item-ratio
            (normalized-ratio
             measurements
             [:generated :p50-elapsed-ns])
            :p50-post-call-heap-delta-per-item-ratio
            (normalized-ratio
             measurements
             [:generated
              :p50-post-call-uncollected-heap-delta])}]))
       by-scenario)
      :resource-qualification
      {:wall-time :host-specific-measurement
       :post-call-uncollected-heap-delta
       :host-specific-v8-regression-measurement
       :retained-live-heap :not-established
       :true-peak-heap :not-established
       :whole-process-allocation :not-established}})))

(defn- scaling-domain-valid?
  [sizes]
  (and
   (<= 2 (count sizes))
   (apply < sizes)
   (every? #(<= minimum-scaling-size %) sizes)
   (<= (* minimum-scaling-span (first sizes))
       (last sizes))))

(defn- maximum-per-item
  [by-scenario path]
  (apply
   max
   (for [[_ measurements] by-scenario
         measurement measurements]
     (/ (double (get-in measurement path))
        (:size measurement)))))

(defn run-gate!
  "Fails closed on super-linear or excessive generated Node boundary work.

  The V8 heap-delta ceiling is a regression detector only. It is not accepted
  as a retained-live-heap or true-peak bound."
  ([]
   (run-gate! {}))
  ([{:keys [maximum-normalized-latency-ratio
            maximum-normalized-heap-delta-ratio
            maximum-p50-ns-per-item
            maximum-p50-post-call-heap-delta-bytes-per-item]
     :or {maximum-normalized-latency-ratio 2.0
          maximum-normalized-heap-delta-ratio 2.0
          maximum-p50-ns-per-item 3000.0
          maximum-p50-post-call-heap-delta-bytes-per-item 2048.0}
     :as options}]
   (let [measure-options
         (dissoc
          options
          :maximum-normalized-latency-ratio
          :maximum-normalized-heap-delta-ratio
          :maximum-p50-ns-per-item
          :maximum-p50-post-call-heap-delta-bytes-per-item)
         result (run! measure-options)
         sizes (get-in result [:fixture :sizes])
         scaling-domain-passed? (scaling-domain-valid? sizes)
         by-scenario (:measurements result)
         maximum-latency
         (maximum-per-item
          by-scenario [:generated :p50-elapsed-ns])
         maximum-heap-delta
         (maximum-per-item
          by-scenario
          [:generated :p50-post-call-uncollected-heap-delta])
         scaling (vals (:normalized-scaling result))
         passed?
         (and
          scaling-domain-passed?
          (every?
           #(<= (:p50-latency-per-item-ratio %)
                maximum-normalized-latency-ratio)
           scaling)
          (every?
           #(<= (:p50-post-call-heap-delta-per-item-ratio %)
                maximum-normalized-heap-delta-ratio)
           scaling)
          (<= maximum-latency maximum-p50-ns-per-item)
          (<= maximum-heap-delta
              maximum-p50-post-call-heap-delta-bytes-per-item))]
     (assoc
      result
      :status (if passed? :passed :failed)
      :required
      {:maximum-normalized-latency-ratio
       maximum-normalized-latency-ratio
       :maximum-normalized-heap-delta-ratio
       maximum-normalized-heap-delta-ratio
       :maximum-p50-ns-per-item maximum-p50-ns-per-item
       :maximum-p50-post-call-heap-delta-bytes-per-item
       maximum-p50-post-call-heap-delta-bytes-per-item
       :minimum-scaling-size minimum-scaling-size
       :minimum-scaling-span minimum-scaling-span}
      :summary
      {:maximum-observed-p50-ns-per-item maximum-latency
       :maximum-observed-p50-post-call-heap-delta-bytes-per-item
       maximum-heap-delta
       :logical-adapter-items-per-call
       {:cljs-javascript 16384
        :proof :PageWindow.CursorRebaseAdapterChunkIsBounded}
       :scaling-domain
       {:minimum-size minimum-scaling-size
        :minimum-span minimum-scaling-span
        :status (if scaling-domain-passed? :passed :failed)}
       :status (if passed? :passed :failed)}
      :resource-qualification
      (assoc
       (:resource-qualification result)
       :inspected-identities :dafny-exact
       :adapter-input-items-per-call :dafny-bounded
       :heap-delta-gate :diagnostic-only
       :lore-analyser-contribution :none)))))

(defn -main
  []
  (let [result (run-gate!)]
    (println (pr-str result))
    (when (= :failed (:status result))
      (throw
       (js/Error.
        "Generated JavaScript cursor-rebase resource gate regressed.")))))

(set! *main-cli-fn* -main)

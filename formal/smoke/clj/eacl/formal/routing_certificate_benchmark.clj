(ns eacl.formal.routing-certificate-benchmark
  "Host-specific regression gate for the generated SCC-routing boundary.

  Dafny proves the accepted checker's exact P+2V+E logical work. This runner
  measures different dimensions: caller-thread allocation and elapsed time on
  a warmed HotSpot JVM. Input construction is deliberately outside the
  measured interval. The result makes no retained-heap, CPU, or worst-case
  latency claim."
  (:require
   [eacl.formal.production-kernel :as production]
   [eacl.verified-kernel :as verified])
  (:import
   (com.sun.management ThreadMXBean)
   (java.lang.management ManagementFactory)))

(def ^:private selection
  {:kernel production/generated-java-kernel})

(def ^:private allocation-bean
  (let [bean (ManagementFactory/getThreadMXBean)]
    (when (and (instance? ThreadMXBean bean)
               (.isThreadAllocatedMemorySupported ^ThreadMXBean bean))
      (when-not (.isThreadAllocatedMemoryEnabled ^ThreadMXBean bean)
        (.setThreadAllocatedMemoryEnabled ^ThreadMXBean bean true))
      bean)))

(defn- allocated-bytes
  []
  (when allocation-bean
    (let [value
          (.getThreadAllocatedBytes
           ^ThreadMXBean allocation-bean
           (.getId (Thread/currentThread)))]
      (when-not (neg? value)
        value))))

(defn- chain-certificate-input
  [node-count]
  (let [last-node (dec node-count)
        edges
        (conj
         (mapv
          (fn [node]
            {:head node :target (inc node)})
          (range last-node))
         {:head last-node :target last-node})]
    {:node-count node-count
     :path-descriptors
     (mapv
      (fn [{:keys [head target]}]
        {:kind :self-permission
         :head head
         :target target})
      edges)
     :edges edges
     :certificate
     {:component-root (vec (range node-count))
      :forward-parent-edge (vec (repeat node-count -1))
      :reverse-parent-edge (vec (repeat node-count -1))
      :forward-depth (vec (repeat node-count 0))
      :reverse-depth (vec (repeat node-count 0))
      :component-rank (vec (range node-count))
      :multiple-member-witness (vec (repeat node-count -1))
      :self-loop-witness-edge
      (assoc (vec (repeat node-count -1)) last-node last-node)
      :traversal (vec (repeat node-count true))
      :traversal-witness-edge
      (conj (vec (range last-node)) -1)}}))

(defn- median
  [values]
  (let [ordered (vec (sort values))]
    (nth ordered (quot (count ordered) 2))))

(defn- measure-once
  [node-count input]
  (let [allocated-before (allocated-bytes)
        started (System/nanoTime)
        decision
        (verified/decide
         selection
         :recursive-routing-certificate
         input)
        elapsed (- (System/nanoTime) started)
        allocated-after (allocated-bytes)]
    {:node-count node-count
     :status (:status decision)
     :path-checks (:path-checks decision)
     :node-checks (:node-checks decision)
     :edge-checks (:edge-checks decision)
     :elapsed-ns elapsed
     :allocated-bytes
     (when (and allocated-before allocated-after)
       (- allocated-after allocated-before))}))

(defn- size-observation
  [node-count warmup samples]
  (let [input (chain-certificate-input node-count)]
    (dotimes [_ warmup]
      (measure-once node-count input))
    (let [observations
          (mapv
           (fn [_]
             (measure-once node-count input))
           (range samples))
          elapsed (mapv :elapsed-ns observations)
          allocation (mapv :allocated-bytes observations)
          allocation-supported?
          (every? some? allocation)
          p50-elapsed (median elapsed)
          p50-allocation
          (when allocation-supported?
            (median allocation))]
      {:node-count node-count
       :path-count node-count
       :edge-count node-count
       :samples samples
       :logical-counters-exact?
       (every?
        (fn [sample]
          (and (= :accepted (:status sample))
               (= node-count (:path-checks sample))
               (= (* 2 node-count) (:node-checks sample))
               (= node-count (:edge-checks sample))))
        observations)
       :p50-elapsed-ns p50-elapsed
       :p50-ns-per-node
       (/ (double p50-elapsed) node-count)
       :allocation-supported? allocation-supported?
       :p50-allocated-bytes p50-allocation
       :p50-allocated-bytes-per-node
       (when p50-allocation
         (/ (double p50-allocation) node-count))})))

(defn run-gate!
  "Runs the warmed routing-certificate resource gate.

  The per-node ceilings are deliberately broad enough for host/JIT noise but
  narrow enough to reject accidental quadratic conversion or checker work."
  ([]
   (run-gate! {}))
  ([{:keys
     [node-counts
      warmup
      samples
      maximum-p50-allocated-bytes-per-node
      maximum-normalized-allocation-ratio
      maximum-normalized-latency-ratio]
     :or
     {node-counts [1024 4096 16384]
      ;; The generated boundary crosses enough independently JIT-compiled
      ;; methods that five calls can still leave the first measured size in
      ;; tiered compilation on a fresh hosted JVM. Keep the warmup above the
      ;; observed compilation threshold so this gate measures steady-state
      ;; scaling rather than JVM startup order.
      warmup 40
      samples 11
      maximum-p50-allocated-bytes-per-node 8192.0
      maximum-normalized-allocation-ratio 1.5
      maximum-normalized-latency-ratio 5.0}}]
   (let [observations
         (mapv
          #(size-observation % warmup samples)
          node-counts)
         normalized-allocations
         (mapv :p50-allocated-bytes-per-node observations)
         normalized-latencies
         (mapv :p50-ns-per-node observations)
         allocation-supported?
         (every? some? normalized-allocations)
         allocation-ratio
         (when allocation-supported?
           (/ (apply max normalized-allocations)
              (max 1.0 (apply min normalized-allocations))))
         latency-ratio
         (/ (apply max normalized-latencies)
            (max 1.0 (apply min normalized-latencies)))
         logical-passed?
         (every? :logical-counters-exact? observations)
         allocation-passed?
         (and allocation-supported?
              (every?
               #(<= % maximum-p50-allocated-bytes-per-node)
               normalized-allocations)
              (<= allocation-ratio
                  maximum-normalized-allocation-ratio))
         latency-passed?
         (<= latency-ratio maximum-normalized-latency-ratio)
         passed?
         (and logical-passed?
              allocation-passed?
              latency-passed?)]
     {:status (if passed? :passed :failed)
      :scope
      :generated-java-routing-certificate-boundary-input-construction-excluded
      :observations observations
      :summary
      {:logical-status (if logical-passed? :passed :failed)
       :allocation-status
       (cond
         (not allocation-supported?) :unsupported-fail-closed
         allocation-passed? :passed
         :else :failed)
       :latency-status (if latency-passed? :passed :failed)
       :normalized-allocation-ratio allocation-ratio
       :normalized-latency-ratio latency-ratio}
      :required
      {:maximum-p50-allocated-bytes-per-node
       maximum-p50-allocated-bytes-per-node
       :maximum-normalized-allocation-ratio
       maximum-normalized-allocation-ratio
       :maximum-normalized-latency-ratio
       maximum-normalized-latency-ratio}
      :qualification
      {:logical-work :dafny-proved-exact-p-plus-2v-plus-e
       :allocation :hotspot-caller-thread-measurement
       :latency :host-specific-regression-measurement
       :retained-live-heap :not-established
       :cpu-time :not-established
       :worst-case-latency :not-established}})))

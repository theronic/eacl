(ns eacl.formal.cursor-rebase-benchmark
  "Host measurements for the generated current-cursor identity rebase.

  Dafny proves the logical inspection count. This runner independently
  measures the complete Clojure-to-generated-Java boundary, including strict
  input validation and sequence conversion, so solver work cannot substitute
  for production allocation or latency evidence."
  (:refer-clojure :exclude [run!])
  (:require
   [eacl.formal.production-kernel :as production]
   [eacl.verified-kernel :as verified])
  (:import
   (com.sun.management ThreadMXBean)
   (java.lang.management ManagementFactory)))

(def ^:private allocation-bean
  (let [bean (ManagementFactory/getThreadMXBean)]
    (when (and (instance? ThreadMXBean bean)
               (.isThreadAllocatedMemorySupported ^ThreadMXBean bean))
      (when-not (.isThreadAllocatedMemoryEnabled ^ThreadMXBean bean)
        (.setThreadAllocatedMemoryEnabled ^ThreadMXBean bean true))
      bean)))

(def ^:private selection
  {:mode :verified-authoritative
   :kernel production/generated-java-kernel})

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
         (long (Math/floor (* proportion (count ordered)))))]
    (nth ordered index)))

(defn- allocated-bytes
  []
  (when allocation-bean
    (let [value
          (.getThreadAllocatedBytes
           ^ThreadMXBean allocation-bean
           (.getId (Thread/currentThread)))]
      (when-not (neg? value)
        value))))

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

(defn- measured-call
  [operation]
  (let [allocated-before (allocated-bytes)
        started (System/nanoTime)
        value (operation)
        elapsed-ns (- (System/nanoTime) started)
        allocated-after (allocated-bytes)]
    {:value value
     :elapsed-ns elapsed-ns
     :allocated-bytes
     (when (and allocated-before allocated-after)
       (- allocated-after allocated-before))}))

(defn- summarize
  [samples]
  {:p50-elapsed-ns
   (percentile (mapv :elapsed-ns samples) 0.50)
   :p95-elapsed-ns
   (percentile (mapv :elapsed-ns samples) 0.95)
   :p50-allocated-bytes
   (percentile (mapv :allocated-bytes samples) 0.50)
   :p95-allocated-bytes
   (percentile (mapv :allocated-bytes samples) 0.95)})

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
  "Measures successful-tail and absent identity scans at three answer sizes.

  The default scaling domain begins above one adapter chunk. A successful
  single-chunk decision and a multi-chunk tail decision execute different
  generated-result variants: the latter necessarily includes one or more
  intermediate `:restarted` chunk results. Comparing those domains would
  mistake that expected constant-factor transition for asymptotic growth."
  ([]
   (run! {}))
  ([{:keys [sizes warmup samples]
     :or {sizes [8192 16384 32768]
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
       :runtime :clj-generated-java}
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
            :p50-allocation-per-item-ratio
            (normalized-ratio
             measurements
             [:generated :p50-allocated-bytes])}]))
         by-scenario)})))

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
  "Fails closed on super-linear or excessive generated JVM boundary work.

  Caller-thread allocation is cumulative allocation, not retained or peak
  heap. Dafny separately proves the exact inspection count and the maximum
  logical items in one adapter chunk."
  ([]
   (run-gate! {}))
  ([{:keys [maximum-normalized-latency-ratio
            maximum-normalized-allocation-ratio
            maximum-p50-ns-per-item
            maximum-p50-allocated-bytes-per-item
            large-recovery-size
            large-recovery-warmup
            large-recovery-samples
            maximum-large-recovery-p50-elapsed-ns
            maximum-large-recovery-p50-allocated-bytes-per-item]
     :or {maximum-normalized-latency-ratio 2.0
          maximum-normalized-allocation-ratio 1.5
          maximum-p50-ns-per-item 1000.0
          maximum-p50-allocated-bytes-per-item 256.0
          large-recovery-size 1000001
          large-recovery-warmup 1
          large-recovery-samples 3
          maximum-large-recovery-p50-elapsed-ns 250000000
          maximum-large-recovery-p50-allocated-bytes-per-item 256.0}
     :as options}]
   (let [measure-options
         (dissoc
          options
          :maximum-normalized-latency-ratio
          :maximum-normalized-allocation-ratio
          :maximum-p50-ns-per-item
          :maximum-p50-allocated-bytes-per-item
          :large-recovery-size
          :large-recovery-warmup
          :large-recovery-samples
          :maximum-large-recovery-p50-elapsed-ns
          :maximum-large-recovery-p50-allocated-bytes-per-item)
         result (run! measure-options)
         sizes (get-in result [:fixture :sizes])
         scaling-domain-passed? (scaling-domain-valid? sizes)
         large-recovery
         (mapv
          #(measure-case
            large-recovery-size
            %
            large-recovery-warmup
            large-recovery-samples)
          [:present-at-tail :absent])
         by-scenario (:measurements result)
         maximum-latency
         (maximum-per-item
          by-scenario [:generated :p50-elapsed-ns])
         maximum-allocation
         (maximum-per-item
          by-scenario [:generated :p50-allocated-bytes])
         scaling (vals (:normalized-scaling result))
         large-recovery-passed?
         (every?
          (fn [measurement]
            (and
             (<= (get-in measurement
                         [:generated :p50-elapsed-ns])
                 maximum-large-recovery-p50-elapsed-ns)
             (<= (/ (double
                     (get-in measurement
                             [:generated :p50-allocated-bytes]))
                    large-recovery-size)
                 maximum-large-recovery-p50-allocated-bytes-per-item)))
          large-recovery)
         passed?
         (and
          scaling-domain-passed?
          (every?
           #(<= (:p50-latency-per-item-ratio %)
                maximum-normalized-latency-ratio)
           scaling)
          (every?
           #(<= (:p50-allocation-per-item-ratio %)
                maximum-normalized-allocation-ratio)
           scaling)
          (<= maximum-latency maximum-p50-ns-per-item)
          (<= maximum-allocation
              maximum-p50-allocated-bytes-per-item)
          large-recovery-passed?)]
     (assoc
      result
      :status (if passed? :passed :failed)
      :required
      {:maximum-normalized-latency-ratio
       maximum-normalized-latency-ratio
       :maximum-normalized-allocation-ratio
       maximum-normalized-allocation-ratio
       :maximum-p50-ns-per-item maximum-p50-ns-per-item
       :maximum-p50-allocated-bytes-per-item
       maximum-p50-allocated-bytes-per-item
       :minimum-scaling-size minimum-scaling-size
       :minimum-scaling-span minimum-scaling-span
       :maximum-large-recovery-p50-elapsed-ns
       maximum-large-recovery-p50-elapsed-ns
       :maximum-large-recovery-p50-allocated-bytes-per-item
       maximum-large-recovery-p50-allocated-bytes-per-item}
      :summary
      {:maximum-observed-p50-ns-per-item maximum-latency
       :maximum-observed-p50-allocated-bytes-per-item
       maximum-allocation
       :logical-adapter-items-per-call
       {:clj-java 4096
        :proof :PageWindow.CursorRebaseAdapterChunkIsBounded}
       :scaling-domain
       {:minimum-size minimum-scaling-size
        :minimum-span minimum-scaling-span
        :status (if scaling-domain-passed? :passed :failed)}
       :large-recovery
       {:size large-recovery-size
        :measurements large-recovery
        :status (if large-recovery-passed? :passed :failed)}
       :status (if passed? :passed :failed)}
      :resource-qualification
      {:inspected-identities :dafny-exact
       :adapter-input-items-per-call :dafny-bounded
       :caller-thread-allocation :hotspot-thread-mxbean-measurement
       :elapsed-time :host-specific-regression-measurement
       :retained-live-heap :not-established
       :true-peak-heap :not-established
       :whole-process-allocation :not-established
       :lore-analyser-contribution :none}))))

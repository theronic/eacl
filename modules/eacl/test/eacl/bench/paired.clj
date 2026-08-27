(ns eacl.bench.paired
  "Paired same-process latency/allocation harness with interleaved arms."
  (:require [clojure.string :as string])
  (:import [com.sun.management ThreadMXBean]
           [java.lang.management ManagementFactory]))

(defn- percentile
  [samples p]
  (let [ordered (vec (sort samples))
        index (min (dec (count ordered))
                   (long (Math/floor (* p (dec (count ordered))))))]
    (nth ordered index)))

(defn- summary
  [samples]
  {:min (apply min samples)
   :p50 (percentile samples 0.50)
   :p95 (percentile samples 0.95)
   :p99 (percentile samples 0.99)
   :max (apply max samples)
   :mean (/ (reduce + samples) (double (count samples)))
   :raw samples})

(defn- allocation-bean
  []
  (let [bean (ManagementFactory/getThreadMXBean)]
    (when (instance? ThreadMXBean bean)
      (let [bean ^ThreadMXBean bean]
        (when (.isThreadAllocatedMemorySupported bean)
          (when-not (.isThreadAllocatedMemoryEnabled bean)
            (.setThreadAllocatedMemoryEnabled bean true))
          bean)))))

(def ^:private default-allocation-bean (delay (allocation-bean)))

(defn- current-thread-allocated-bytes
  []
  (when-let [bean @default-allocation-bean]
    (.getThreadAllocatedBytes
     ^ThreadMXBean bean (.getId (Thread/currentThread)))))

(defn environment
  []
  {:os (System/getProperty "os.name")
   :os-version (System/getProperty "os.version")
   :architecture (System/getProperty "os.arch")
   :java-version (System/getProperty "java.version")
   :java-vendor (System/getProperty "java.vendor")
   :maximum-heap-bytes (.maxMemory (Runtime/getRuntime))
   :available-processors (.availableProcessors (Runtime/getRuntime))})

(defn host-class
  [environment]
  [(:os environment)
   (:architecture environment)
   (some-> (:java-version environment) (string/split #"\.") first)])

(defn- require-run-shape!
  [{:keys [arms warmups samples]}]
  (when-not (and (vector? arms)
                 (<= 2 (count arms))
                 (every? (fn [[arm f]] (and (keyword? arm) (fn? f))) arms)
                 (= (count arms) (count (distinct (map first arms)))))
    (throw
     (ex-info
      "Paired benchmark arms must be a vector of distinct [keyword fn] pairs."
      {:type :eacl.bench/invalid-paired-arms
       :eacl/error :eacl.bench/invalid-paired-arms})))
  (doseq [[field value] [[:warmups warmups] [:samples samples]]]
    (when-not (and (integer? value)
                   (if (= field :warmups) (not (neg? value)) (pos? value)))
      (throw
       (ex-info
        "Paired benchmark sample counts are invalid."
        {:type :eacl.bench/invalid-sample-count
         :eacl/error :eacl.bench/invalid-sample-count
         :field field
         :value value})))))

(defn- reduction
  [baseline candidate]
  (- 1.0 (/ (double candidate) (double baseline))))

(defn- comparison-result
  [arm-results {:keys [baseline candidate
                       minimum-latency-reduction
                       minimum-allocation-reduction]
                :as comparison}]
  (let [baseline-result (get arm-results baseline)
        candidate-result (get arm-results candidate)
        latency-reduction
        (reduction (get-in baseline-result [:latency-us :p50])
                   (get-in candidate-result [:latency-us :p50]))
        allocation-reduction
        (when (and (:allocated-bytes baseline-result)
                   (:allocated-bytes candidate-result))
          (reduction (get-in baseline-result [:allocated-bytes :p50])
                     (get-in candidate-result [:allocated-bytes :p50])))
        latency-pass?
        (or (nil? minimum-latency-reduction)
            (<= minimum-latency-reduction latency-reduction))
        allocation-pass?
        (or (nil? minimum-allocation-reduction)
            (and allocation-reduction
                 (<= minimum-allocation-reduction allocation-reduction)))]
    (assoc comparison
           :latency-reduction latency-reduction
           :allocation-reduction allocation-reduction
           :passed? (and latency-pass? allocation-pass?))))

(defn- ceiling-result
  [arm-result {:keys [latency-p50-us allocation-p50-bytes]}]
  (let [checks
        (cond-> {}
          latency-p50-us
          (assoc :latency-p50
                 {:actual (get-in arm-result [:latency-us :p50])
                  :maximum latency-p50-us
                  :passed?
                  (<= (get-in arm-result [:latency-us :p50])
                      latency-p50-us)})

          allocation-p50-bytes
          (assoc :allocation-p50
                 {:actual (get-in arm-result [:allocated-bytes :p50])
                  :maximum allocation-p50-bytes
                  :passed?
                  (and (:allocated-bytes arm-result)
                       (<= (get-in arm-result [:allocated-bytes :p50])
                           allocation-p50-bytes))}))]
    {:checks checks
     :passed? (every? :passed? (vals checks))}))

(defn- absolute-ceiling-result
  [environment arm-results absolute-ceilings]
  (let [actual-host-class (host-class environment)]
    (if-let [ceilings (get absolute-ceilings actual-host-class)]
      (let [results
            (into {}
                  (map (fn [[arm ceiling]]
                         [arm (ceiling-result (get arm-results arm) ceiling)]))
                  ceilings)]
        {:status :applicable
         :host-class actual-host-class
         :results results
         :passed? (every? :passed? (vals results))})
      {:status :not-applicable
       :host-class actual-host-class
       :available-host-classes (vec (keys absolute-ceilings))
       :mismatched-fields [:os :architecture :java-major]})))

(defn run-paired!
  "Runs every arm once per iteration, reversing arm order on odd iterations.

  Options support injected clocks/allocation readers for deterministic tests.
  Absolute ceilings are keyed by `[os architecture java-major]`; paired
  comparisons remain applicable on every host."
  [{:as options
    :keys [arms warmups samples comparisons absolute-ceilings
           nano-time allocated-bytes environment]
    :or {comparisons []
         absolute-ceilings {}
         nano-time #(System/nanoTime)
         allocated-bytes current-thread-allocated-bytes}}]
  (require-run-shape! options)
  (let [environment (or environment (eacl.bench.paired/environment))
        observations
        (atom
         (into {}
               (map (fn [[arm _]]
                      [arm {:latency-us []
                            :allocated-bytes []
                            :checksums []}]))
               arms))]
    (dotimes [iteration (+ warmups samples)]
      (let [ordered-arms (if (odd? iteration) (rseq arms) arms)]
        (doseq [[arm f] ordered-arms]
          (let [allocated-before (allocated-bytes)
                started (nano-time)
                value (f iteration)
                elapsed (- (nano-time) started)
                allocated-after (allocated-bytes)]
            (when (>= iteration warmups)
              (swap! observations update-in [arm :latency-us]
                     conj (/ (double elapsed) 1000.0))
              (swap! observations update-in [arm :checksums] conj (hash value))
              (when (and allocated-before allocated-after)
                (swap! observations update-in [arm :allocated-bytes]
                       conj (- allocated-after allocated-before))))))))
    (let [arm-results
          (into {}
                (map
                 (fn [[arm observation]]
                   [arm
                    (cond->
                     {:samples samples
                      :warmups warmups
                      :latency-us (summary (:latency-us observation))
                      :checksums (:checksums observation)}
                      (seq (:allocated-bytes observation))
                      (assoc :allocated-bytes
                             (summary (:allocated-bytes observation))))]))
                @observations)]
      {:format-version 1
       :environment environment
       :interleaving :alternating-forward-reverse
       :arms arm-results
       :comparisons
       (mapv #(comparison-result arm-results %) comparisons)
       :absolute-ceilings
       (absolute-ceiling-result
        environment arm-results absolute-ceilings)})))

(ns eacl.operator-engine.union-performance
  "Matched-host union-only performance qualification against a selected source
  tree. This namespace deliberately calls the frozen public-workload fixture;
  it neither writes the historical snapshot nor imports operator execution."
  (:refer-clojure :exclude [run!])
  (:require [eacl.baseline.perf :as perf]
            [eacl.bench.explorer-fixture :as fixture]))

(defn- private-value [namespace symbol]
  (var-get (ns-resolve namespace symbol)))

(defn- robust-measure
  "Batches sub-2ms operations so scheduler noise is amortized inside each
  sample. Slow counts and full enumeration remain one call per sample."
  [f _]
  (System/gc)
  (dotimes [_ 50] (f))
  (let [probe-start (System/nanoTime)
        _ (f)
        probe-nanos (- (System/nanoTime) probe-start)
        repetitions (if (< probe-nanos 2000000) 20 3)
        allocated-bytes (private-value 'eacl.baseline.perf 'allocated-bytes)
        percentile (private-value 'eacl.baseline.perf 'percentile)
        samples
        (vec
         (for [_ (range 31)]
           (let [allocated-before (allocated-bytes)
                 started (System/nanoTime)]
             (dotimes [_ repetitions] (f))
             {:nanos (quot (- (System/nanoTime) started) repetitions)
              :allocated-bytes
              (quot (- (allocated-bytes) allocated-before)
                    repetitions)})))
        nanos (vec (sort (map :nanos samples)))
        allocations (vec (sort (map :allocated-bytes samples)))]
    {:samples (count samples)
     :repetitions-per-sample repetitions
     :median-ms (/ (percentile nanos 0.5) 1e6)
     :p90-ms (/ (percentile nanos 0.9) 1e6)
     :maximum-ms (/ (peek nanos) 1e6)
     :median-allocated-bytes (percentile allocations 0.5)}))

(defn- seed [schema shape recursive?]
  ((private-value 'eacl.baseline.perf 'seed-explorer-client!)
   schema shape recursive?))

(defn- capture-suite [client fresh-client]
  ((private-value 'eacl.baseline.perf 'capture-suite)
   client fresh-client))

(defn run!
  "Runs one fresh-source campaign. The caller alternates frozen/current JVMs
  and takes the median of at least three campaign medians."
  []
  (let [acyclic (seed fixture/schema perf/perf-shape false)
        acyclic-fresh (seed fixture/schema perf/perf-shape false)
        recursive (seed fixture/recursive-schema
                        perf/recursive-perf-shape true)
        recursive-fresh (seed fixture/recursive-schema
                              perf/recursive-perf-shape true)]
    (with-redefs [eacl.baseline.perf/measure robust-measure]
      {:format-version 1
       :environment
       {:java-version (System/getProperty "java.version")
        :os (str (System/getProperty "os.name") " "
                 (System/getProperty "os.version"))
        :architecture (System/getProperty "os.arch")
        :processors (.availableProcessors (Runtime/getRuntime))
        :clojure (clojure-version)}
       :protocol
       {:campaigns-required 3
        :fast-operation-threshold-nanos 2000000
        :fast-repetitions-per-sample 20
        :slow-repetitions-per-sample 3
        :samples 31
        :warmups 50}
       :acyclic (capture-suite acyclic acyclic-fresh)
       :recursive (capture-suite recursive recursive-fresh)})))

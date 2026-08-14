(ns eacl.baseline.perf
  "CLJ latency/allocation/operation baselines for the current engines
  (stable-discovery change, task 2.4), captured through the public API on the
  DataScript backend with completed-answer caching disabled.

  Numbers are environment-specific informational baselines (the :env stamp
  records the hardware/JVM); the binding replacement gates compare like
  hardware against these medians per the benchmark protocol. Logical
  backend-scan counts are environment-independent and ARE authoritative.

  Regenerate with: (eacl.baseline.perf/capture-perf!)"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [datascript.core :as ds]
            [eacl.bench.explorer-fixture :as fixture]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine])
  (:import (java.lang.management ManagementFactory)))

(def snapshot-file "exploration/baselines/perf-clj-datascript.edn")

(def perf-shape
  "Five accounts x 400 servers = 2,000 servers, overlapping team/VPC arrows."
  (assoc fixture/default-shape :servers-per-account 400))

(def recursive-perf-shape
  (assoc fixture/populated-recursive-shape
         :accounts 40
         :teams-per-account 1
         :vpcs-per-account 1
         :servers-per-account 10
         :user-1-account-count 5
         :subaccount-count 20))

(defn- seed-explorer-client!
  [schema shape recursive?]
  (let [conn (datascript/create-conn)
        client (datascript/make-client
                conn
                {:cache {:remember-answers false}
                 :source-lifecycle "stable-discovery-perf-baseline"})]
    (eacl/write-schema! client schema)
    (ds/transact! conn (vec (fixture/object-transactions shape)))
    (doseq [batch (if recursive?
                    (fixture/populated-recursive-relationship-batches shape)
                    (fixture/relationship-batches shape))]
      (eacl/create-relationships! client (vec batch)))
    client))

(def ^:private thread-bean (ManagementFactory/getThreadMXBean))

(defn- allocated-bytes []
  (let [bean ^com.sun.management.ThreadMXBean thread-bean]
    (.getThreadAllocatedBytes bean (.getId (Thread/currentThread)))))

(defn- percentile [sorted-values fraction]
  (nth sorted-values
       (min (dec (count sorted-values))
            (long (Math/floor (* fraction (count sorted-values)))))))

(defn- measure
  "Runs f with warmup, then samples wall time and allocation per run."
  [f {:keys [warmup samples] :or {warmup 15 samples 40}}]
  (dotimes [_ warmup] (f))
  (let [results
        (vec (for [_ (range samples)]
               (let [alloc-before (allocated-bytes)
                     start (System/nanoTime)
                     _ (f)
                     elapsed (- (System/nanoTime) start)]
                 {:nanos elapsed
                  :allocated (- (allocated-bytes) alloc-before)})))
        nanos (vec (sort (map :nanos results)))
        allocs (vec (sort (map :allocated results)))]
    {:samples samples
     :median-ms (/ (percentile nanos 0.5) 1e6)
     :p90-ms (/ (percentile nanos 0.9) 1e6)
     :max-ms (/ (peek nanos) 1e6)
     :median-allocated-bytes (percentile allocs 0.5)}))

(defn- first-run
  "One instrumented first execution on a fresh client: authoritative logical
  scan/command counters plus a single informational cold-ish latency sample."
  [f]
  (let [acyclic (atom {})
        recursive (atom {})
        start (System/nanoTime)
        _ (binding [engine/*acyclic-work-stats* acyclic
                    engine/*recursive-traversal-stats* recursive]
            (f))
        elapsed (- (System/nanoTime) start)]
    {:ms (/ elapsed 1e6)
     :acyclic @acyclic
     :recursive (into {}
                      (remove (fn [[_ v]] (coll? v)))
                      @recursive)}))

(defn- full-enumeration [client query page-size]
  (loop [query (assoc query :first page-size) total 0]
    (let [{:keys [data page-info]} (eacl/lookup-resources client query)
          total (+ total (count data))]
      (if (and (:has-next-page? page-info) (:end-cursor page-info) (seq data))
        (recur (assoc query :after (:end-cursor page-info)) total)
        total))))

(defn- operations [client]
  (let [server-1 (eacl/spice-object :server (fixture/server-id 0 0))]
    {:super-user-first-page-20
     #(eacl/lookup-resources
       client (fixture/resource-query fixture/super-user :view 20))
     :user-1-first-page-20
     #(eacl/lookup-resources
       client (fixture/resource-query fixture/user-1 :view 20))
     :owner-first-page-20
     #(eacl/lookup-resources
       client (fixture/resource-query fixture/owner-0001 :view 20))
     :stranger-empty-page
     #(eacl/lookup-resources
       client (fixture/resource-query
               (eacl/spice-object :user "stranger") :view 20))
     :super-user-count
     #(eacl/count-resources
       client (fixture/count-query fixture/super-user :view))
     :point-check
     #(eacl/can? client {:subject fixture/super-user
                         :permission :view
                         :resource server-1})
     :user-1-full-enumeration-100
     #(full-enumeration
       client (dissoc (fixture/resource-query fixture/user-1 :view) :first)
       100)
     :reverse-first-page-20
     #(eacl/lookup-subjects client {:resource server-1
                                    :permission :view
                                    :subject/type :user
                                    :first 20})}))

(defn- capture-suite
  "Warm-repeat medians on `client` (client caches other than the answer tier
  may assist repeats — that is today's public repeat behavior) plus one
  first-execution run per operation on `fresh-client` for authoritative
  logical scan counts."
  [client fresh-client]
  (let [fresh-ops (operations fresh-client)]
    (into (sorted-map)
          (for [[op-key op] (operations client)]
            [op-key (assoc (measure op {})
                           :first-run (first-run (get fresh-ops op-key)))]))))

(defn capture-perf!
  []
  (let [acyclic-client (seed-explorer-client! fixture/schema perf-shape false)
        acyclic-fresh (seed-explorer-client! fixture/schema perf-shape false)
        recursive-client (seed-explorer-client!
                          fixture/recursive-schema recursive-perf-shape true)
        recursive-fresh (seed-explorer-client!
                         fixture/recursive-schema recursive-perf-shape true)
        snapshot
        {:env {:jvm (System/getProperty "java.vm.version")
               :java-version (System/getProperty "java.version")
               :os (str (System/getProperty "os.name") " "
                        (System/getProperty "os.version"))
               :arch (System/getProperty "os.arch")
               :processors (.availableProcessors (Runtime/getRuntime))
               :clojure (clojure-version)}
         :note (str "Informational current-engine baseline. Warm-repeat "
                    "latency/allocation are environment-specific; :first-run "
                    "logical scan/command counts are authoritative and "
                    "environment-independent.")
         :shapes {:acyclic perf-shape :recursive recursive-perf-shape}
         :acyclic-2k-servers (capture-suite acyclic-client acyclic-fresh)
         :recursive-populated (capture-suite recursive-client recursive-fresh)}
        file (io/file snapshot-file)]
    (io/make-parents file)
    (with-open [writer (io/writer file)]
      (binding [*out* writer]
        (println ";; Current-engine perf baseline. Regenerate via")
        (println ";; (eacl.baseline.perf/capture-perf!) — see README.md.")
        (pprint/pprint snapshot)))
    (str file)))

(ns eacl.formal.indexed-authority-benchmark
  "Reproducible paired benchmark for the indexed generated traversal boundary.

  The fixture is deliberately recursive so both clients exercise the same
  traversal-order contract. Final-answer and subproblem caches are disabled;
  schema plans are warmed before measurement. Results therefore isolate
  traversal/runtime overhead on one immutable in-memory DataScript snapshot."
  (:refer-clojure :exclude [run!])
  (:require
   [datascript.core :as ds]
   [eacl.cache :as cache]
   [eacl.core :as eacl]
   [eacl.datascript.core :as datascript]
   [eacl.engine.v8 :as engine]
   [eacl.formal.production-kernel :as production]))

(def recursive-schema
  "definition user {}
   definition folder {
     relation reader: user
     relation parent: folder
     permission read = reader + parent->read
   }")

(defn- elapsed-ms
  [f]
  (let [started (System/nanoTime)
        value (f)]
    {:value value
     :elapsed-ms
     (/ (double (- (System/nanoTime) started)) 1000000.0)}))

(defn- percentile
  [samples proportion]
  (let [ordered (vec (sort samples))
        index
        (min
         (dec (count ordered))
         (long (Math/floor (* proportion (count ordered)))))]
    (nth ordered index)))

(defn- page-ids
  [page]
  (mapv :id (:data page)))

(defn- legacy-dimensional-work
  [stats]
  {:backend-commands (:stream-fills stats 0)
   :adapter-fetched-values (:fetched-stream-datoms stats 0)
   :engine-consumed-values (:advanced-stream-datoms stats 0)
   :cumulative-enqueues (:cumulative-enqueues stats 0)
   :current-queue-depth (:current-queue-depth stats 0)
   :maximum-queue-depth (:maximum-queue-depth stats 0)
   :unique-grants (:derived-grants stats 0)
   :emitted-results (:emitted-results stats 0)
   :rule-applications (:rule-applications stats 0)
   :consumer-grant-joins (:consumer-grant-joins stats 0)
   :render-advances (:render-advances stats 0)})

(defn- generated-dimensional-work
  [stats]
  (:generated-dimensional-counters stats))

(defn- retained-logical-units-ratio
  [legacy generated]
  (if (zero? legacy)
    (if (zero? generated) 1.0 ##Inf)
    (/ (double generated) legacy)))

(defn- transact-objects!
  [conn objects]
  (ds/transact!
   conn
   (mapv (fn [object] {:eacl/id (:id object)}) objects)))

(defn- seed-recursive-chain!
  [conn writer result-count]
  (let [user (eacl/spice-object :user "benchmark-user")
        folders
        (mapv
         #(eacl/spice-object :folder (format "folder-%06d" %))
         (range result-count))]
    (eacl/write-schema! writer recursive-schema)
    (transact-objects! conn (into [user] folders))
    (eacl/create-relationships!
     writer
     (into
      [(eacl/->Relationship user :reader (first folders))]
      (map
       (fn [[parent child]]
         (eacl/->Relationship parent :parent child))
       (partition 2 1 folders))))
    {:user user
     :folders folders}))

(defn run!
  "Runs a paired legacy/generated recursive-page benchmark.

  Options:
  - `:result-count` defaults to 100000.
  - `:page-size` defaults to 20.
  - `:warmup` defaults to 50 paired iterations.
  - `:samples` defaults to 100 paired iterations.

  Returns raw samples, p50/p95 values, ratios, and dimensional engine stats.
  It throws immediately if any measured public page differs."
  ([]
   (run! {}))
  ([{:keys [result-count page-size warmup samples]
     :or {result-count 100000
          page-size 20
          warmup 50
          samples 100}}]
   (let [conn (datascript/create-conn)
         common
         {:cache cache/no-cache
          :security-key
          "01234567890123456789012345678901"}
         writer (datascript/make-client conn common)
         {:keys [user]} (seed-recursive-chain! conn writer result-count)
         legacy (datascript/make-client conn common)
         generated
         (datascript/make-client
          conn
          (assoc
           common
           :decision-kernel
           {:kernel production/generated-java-kernel}))
         query
         {:subject user
          :permission :read
          :resource/type :folder
          :first page-size}
         legacy-times (atom [])
         generated-times (atom [])
         legacy-stats (atom {})
         generated-stats (atom {})
         run-one
         (fn [client stats]
           (binding [engine/*recursive-traversal-stats* stats]
             (elapsed-ms #(eacl/lookup-resources client query))))]
     (dotimes [iteration (+ warmup samples)]
       (let [legacy-first? (even? iteration)
             [legacy-result generated-result]
             (if legacy-first?
               [(run-one legacy legacy-stats)
                (run-one generated generated-stats)]
               (let [generated-result
                     (run-one generated generated-stats)
                     legacy-result (run-one legacy legacy-stats)]
                 [legacy-result generated-result]))
             legacy-ids (page-ids (:value legacy-result))
             generated-ids (page-ids (:value generated-result))]
         (when-not (= legacy-ids generated-ids)
           (throw
            (ex-info
             "Generated recursive benchmark page diverged from legacy."
             {:iteration iteration
              :legacy-count (count legacy-ids)
              :generated-count (count generated-ids)})))
         (when (>= iteration warmup)
           (swap! legacy-times conj (:elapsed-ms legacy-result))
           (swap! generated-times conj (:elapsed-ms generated-result)))))
     (let [legacy-work (legacy-dimensional-work @legacy-stats)
           generated-work (generated-dimensional-work @generated-stats)
           legacy-retained
           (:legacy-retained-logical-units @legacy-stats 0)
           generated-retained
           (:generated-retained-logical-units @generated-stats 0)
           legacy-p50 (percentile @legacy-times 0.50)
           legacy-p95 (percentile @legacy-times 0.95)
           generated-p50 (percentile @generated-times 0.50)
           generated-p95 (percentile @generated-times 0.95)]
       (when-not (= legacy-work generated-work)
        (throw
         (ex-info
           "Generated recursive benchmark changed comparable traversal work."
           {:legacy legacy-work
            :generated generated-work})))
       {:fixture
        {:backend :datascript
         :permission-shape :recursive-chain
         :result-count result-count
         :page-size page-size
         :warmup warmup
         :samples samples
         :cache :disabled
         :schema-plan :warm}
        :legacy-ms @legacy-times
        :generated-ms @generated-times
        :legacy-p50-ms legacy-p50
        :legacy-p95-ms legacy-p95
        :generated-p50-ms generated-p50
        :generated-p95-ms generated-p95
        :p50-latency-ratio (/ generated-p50 legacy-p50)
        :p95-latency-ratio (/ generated-p95 legacy-p95)
        :p50-absolute-overhead-ms (- generated-p50 legacy-p50)
        :dimensional-work
        {:legacy legacy-work
         :generated generated-work}
        :retained-logical-units
        {:legacy legacy-retained
         :generated generated-retained
         :generated-to-legacy-ratio
         (retained-logical-units-ratio
          legacy-retained generated-retained)}
        :legacy-stats @legacy-stats
        :generated-stats @generated-stats}))))

(defn run-gate!
  "Runs independent paired trials and applies the reviewed noise rule.

  The gate uses the median of trial-level p95 ratios rather than the maximum
  sample from one process. Every trial still has to preserve the complete page
  and every dimensionally comparable traversal-work counter exactly. Retained
  logical state is gated separately because the legacy and generated engines
  intentionally use different state representations. Raw per-request samples
  remain in each trial result.

  Options are the same as `run!`, plus:
  - `:trials` defaults to 5.
  - `:maximum-median-p95-ratio` defaults to 2.0.
  - `:maximum-retained-logical-units-ratio` defaults to 1.5."
  ([]
   (run-gate! {}))
  ([{:keys [trials maximum-median-p95-ratio
            maximum-retained-logical-units-ratio]
     :or {trials 5
          maximum-median-p95-ratio 2.0
          maximum-retained-logical-units-ratio 1.5}
     :as options}]
   (when-not (and (integer? trials) (pos? trials))
     (throw
      (ex-info
       "Indexed authority gate requires a positive trial count."
       {:trials trials})))
   (when-not (and (number? maximum-median-p95-ratio)
                  (pos? maximum-median-p95-ratio))
     (throw
      (ex-info
       "Indexed authority gate requires a positive p95 ratio."
       {:maximum-median-p95-ratio maximum-median-p95-ratio})))
   (when-not (and (number? maximum-retained-logical-units-ratio)
                  (pos? maximum-retained-logical-units-ratio))
     (throw
      (ex-info
       "Indexed authority gate requires a positive retained-state ratio."
       {:maximum-retained-logical-units-ratio
        maximum-retained-logical-units-ratio})))
   (let [run-options
         (dissoc
          options
          :trials
          :maximum-median-p95-ratio
          :maximum-retained-logical-units-ratio)
         results (mapv (fn [_] (run! run-options)) (range trials))
         p50-ratios (mapv :p50-latency-ratio results)
         p95-ratios (mapv :p95-latency-ratio results)
         retained-ratios
         (mapv
          #(get-in
            % [:retained-logical-units :generated-to-legacy-ratio])
          results)
         overheads (mapv :p50-absolute-overhead-ms results)
         median-p95-ratio (percentile p95-ratios 0.50)
         maximum-retained-ratio (apply max retained-ratios)
         passed?
         (and
          (<= median-p95-ratio maximum-median-p95-ratio)
          (<= maximum-retained-ratio
              maximum-retained-logical-units-ratio))]
     {:fixture
      (assoc (:fixture (first results))
             :independent-trials trials
             :aggregation :median-of-trial-p95-ratios)
      :required
      {:maximum-median-p95-ratio maximum-median-p95-ratio
       :maximum-retained-logical-units-ratio
       maximum-retained-logical-units-ratio
       :exact-page-and-comparable-work-equality-every-trial true}
      :summary
      {:median-p50-ratio (percentile p50-ratios 0.50)
       :median-p95-ratio median-p95-ratio
       :maximum-retained-logical-units-ratio
       maximum-retained-ratio
       :median-p50-absolute-overhead-ms
       (percentile overheads 0.50)
       :passing-trials
       (count
        (filter true?
                (map
                 #(and
                   (<= %1 maximum-median-p95-ratio)
                   (<= %2 maximum-retained-logical-units-ratio))
                 p95-ratios
                 retained-ratios)))
       :status (if passed? :passed :failed)}
      :trials results})))

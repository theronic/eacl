(ns eacl.formal.authority-mode-benchmark
  "Paired public-API benchmark for legacy and verified authority.

  The fixture separates five operational shapes: direct Boolean evaluation,
  acyclic ordered lookup, recursive lookup, recursive cursor continuation, and
  a hot completed-answer cache hit.  Wall time, caller-thread allocation, and
  backend work are recorded independently; no one dimension substitutes for
  another."
  (:refer-clojure :exclude [run!])
  (:require
   [datascript.core :as ds]
   [eacl.cache :as cache]
   [eacl.core :as eacl]
   [eacl.datascript.core :as datascript]
   [eacl.engine.v8 :as engine]
   [eacl.formal.production-kernel :as production])
  (:import
   (com.sun.management ThreadMXBean)
   (java.lang.management ManagementFactory)))

(def ^:private schema-text
  "definition user {}
   definition group {
     relation member: user
     relation parent: group
     permission access = member + parent->access
   }
   definition document {
     relation reader: user
     relation owner: user
     relation group: group
     permission direct = reader
     permission acyclic = reader + owner
     permission shared = group->access
   }")

(def ^:private allocation-bean
  (let [bean (ManagementFactory/getThreadMXBean)]
    (when (and (instance? ThreadMXBean bean)
               (.isThreadAllocatedMemorySupported ^ThreadMXBean bean))
      (when-not (.isThreadAllocatedMemoryEnabled ^ThreadMXBean bean)
        (.setThreadAllocatedMemoryEnabled ^ThreadMXBean bean true))
      bean)))

(defn- user
  [index]
  (eacl/spice-object :user (format "user-%02d" index)))

(defn- group
  [index]
  (eacl/spice-object :group (format "group-%03d" index)))

(defn- document
  [index]
  (eacl/spice-object :document (format "document-%05d" index)))

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
    (let [allocated
          (.getThreadAllocatedBytes
           ^ThreadMXBean allocation-bean
           (.getId (Thread/currentThread)))]
      (when-not (neg? allocated)
        allocated))))

(defn- normalize-result
  [value]
  (if (and (map? value) (contains? value :data))
    {:data (:data value)
     :has-next-page? (get-in value [:page-info :has-next-page?])
     :has-previous-page? (get-in value [:page-info :has-previous-page?])
     :cached? (:cached? value)
     :cache-basis (:cache-basis value)}
    value))

(defn- measured-call
  [operation]
  (let [work (atom {})
        allocated-before (allocated-bytes)
        started (System/nanoTime)
        value
        (binding [engine/*backend-work-stats* work]
          (operation))
        elapsed-ns (- (System/nanoTime) started)
        allocated-after (allocated-bytes)]
    {:value (normalize-result value)
     :elapsed-ns elapsed-ns
     :allocated-bytes
     (when (and allocated-before allocated-after)
       (- allocated-after allocated-before))
     :backend-work @work}))

(defn- transact-objects!
  [connection objects]
  (ds/transact!
   connection
   (mapv (fn [object] {:eacl/id (:id object)}) objects)))

(defn- seed!
  [connection writer document-count group-depth]
  (let [users [(user 0) (user 1)]
        groups (mapv group (range group-depth))
        documents (mapv document (range document-count))]
    (eacl/write-schema! writer schema-text)
    (transact-objects!
     connection
     (vec (concat users groups documents)))
    (eacl/create-relationships!
     writer
     (vec
      (concat
       [(eacl/->Relationship (user 0) :reader (document 0))
        (eacl/->Relationship (user 0) :member (group 0))]
       (map
        #(eacl/->Relationship (user 1) :owner (document %))
        (range document-count))
       (map
        (fn [index]
          (eacl/->Relationship
           (group (dec index)) :parent (group index)))
        (range 1 group-depth))
       [(eacl/->Relationship
         (group (dec group-depth)) :parent (group 0))]
       (map
        #(eacl/->Relationship
          (group (dec group-depth)) :group (document %))
        (range document-count)))))))

(defn- client
  [connection engine-selection cache-config]
  (datascript/make-client
   connection
   {:coherence-authority :managed
    :proof-mode :mutation
    :cache cache-config
    :security-key "01234567890123456789012345678901"
    :engine-selection engine-selection}))

(defn- page-query
  [permission]
  {:subject
   (if (= :acyclic permission) (user 1) (user 0))
   :permission permission
   :resource/type :document
   :first 20})

(defn- operations
  [uncached-client cached-client]
  (let [recursive-query (page-query :shared)
        first-page
        (eacl/lookup-resources uncached-client recursive-query)
        cursor (get-in first-page [:page-info :end-cursor])
        cached-operation
        #(eacl/lookup-resources cached-client recursive-query)]
    ;; Publish and observe a completed answer before timing the hot path.
    (cached-operation)
    (cached-operation)
    {:direct
     #(eacl/can?
       uncached-client (user 0) :direct (document 0))
     :acyclic
     #(eacl/lookup-resources
       uncached-client (page-query :acyclic))
     :recursive
     #(eacl/lookup-resources
       uncached-client recursive-query)
     :cursor
     #(eacl/lookup-resources
       uncached-client (assoc recursive-query :after cursor))
     :cache-hot cached-operation}))

(defn- measure-scenario
  [legacy-operation verified-operation warmup samples]
  (let [legacy-samples (atom [])
        verified-samples (atom [])]
    (dotimes [iteration (+ warmup samples)]
      (let [legacy-first? (even? iteration)
            [legacy-result verified-result]
            (if legacy-first?
              [(measured-call legacy-operation)
               (measured-call verified-operation)]
              (let [verified-result (measured-call verified-operation)
                    legacy-result (measured-call legacy-operation)]
                [legacy-result verified-result]))]
        (when-not (= (:value legacy-result) (:value verified-result))
          (throw
           (ex-info
            "Verified authority changed a benchmark result."
            {:iteration iteration
             :legacy (:value legacy-result)
             :verified (:value verified-result)})))
        (when (>= iteration warmup)
          (swap! legacy-samples conj legacy-result)
          (swap! verified-samples conj verified-result))))
    (let [legacy-latency (mapv :elapsed-ns @legacy-samples)
          verified-latency (mapv :elapsed-ns @verified-samples)
          legacy-allocation
          (mapv :allocated-bytes @legacy-samples)
          verified-allocation
          (mapv :allocated-bytes @verified-samples)
          legacy-backend-operations
          (mapv
           #(get-in % [:backend-work :executed-backend-operations] 0)
           @legacy-samples)
          verified-backend-operations
          (mapv
           #(get-in % [:backend-work :executed-backend-operations] 0)
           @verified-samples)
          legacy-p95 (percentile legacy-latency 0.95)
          verified-p95 (percentile verified-latency 0.95)
          legacy-allocation-p95 (percentile legacy-allocation 0.95)
          verified-allocation-p95
          (percentile verified-allocation 0.95)
          legacy-backend-p95 (percentile legacy-backend-operations 0.95)
          verified-backend-p95
          (percentile verified-backend-operations 0.95)]
      {:legacy-latency-ns legacy-latency
       :verified-latency-ns verified-latency
       :legacy-allocation-bytes legacy-allocation
       :verified-allocation-bytes verified-allocation
       :legacy-backend-operations legacy-backend-operations
       :verified-backend-operations verified-backend-operations
       :legacy-p50-ns (percentile legacy-latency 0.50)
       :legacy-p95-ns legacy-p95
       :verified-p50-ns (percentile verified-latency 0.50)
       :verified-p95-ns verified-p95
       :p95-latency-ratio (/ (double verified-p95) legacy-p95)
       :p95-absolute-overhead-ns (- verified-p95 legacy-p95)
       :legacy-p95-allocation-bytes legacy-allocation-p95
       :verified-p95-allocation-bytes verified-allocation-p95
       :p95-allocation-ratio
       (/ (double verified-allocation-p95)
          (max 1 legacy-allocation-p95))
       :legacy-p95-backend-operations legacy-backend-p95
       :verified-p95-backend-operations verified-backend-p95
       :p95-backend-operation-ratio
       (/ (double verified-backend-p95)
          (max 1 legacy-backend-p95))
       :p95-backend-operation-overhead
       (- verified-backend-p95 legacy-backend-p95)})))

(defn run!
  "Runs one paired benchmark trial and returns all raw resource samples."
  ([]
   (run! {}))
  ([{:keys [document-count group-depth warmup samples]
     :or {document-count 512
          group-depth 64
          warmup 10
          samples 31}}]
   (let [connection (datascript/create-conn)
         writer
         (client connection :legacy-authoritative cache/no-cache)
         _ (seed! connection writer document-count group-depth)
         verified-selection
         {:mode :verified-authoritative
          :kernel production/generated-java-kernel}
         legacy-operations
         (operations
          (client connection :legacy-authoritative cache/no-cache)
          (client connection
                  :legacy-authoritative
                  {:remember-answers true}))
         verified-operations
         (operations
          (client connection verified-selection cache/no-cache)
          (client connection
                  verified-selection
                  {:remember-answers true}))]
     {:fixture
      {:backend :datascript
       :documents document-count
       :recursive-groups group-depth
       :page-size 20
       :warmup warmup
       :samples samples
       :paired-order :alternating}
      :scenarios
      (into
       {}
       (map
        (fn [scenario]
          [scenario
           (measure-scenario
            (get legacy-operations scenario)
            (get verified-operations scenario)
            warmup
            samples)]))
       [:direct :acyclic :recursive :cursor :cache-hot])})))

(defn run-gate!
  "Runs independent trials and applies predeclared latency/allocation gates.

  A scenario passes latency when either the ratio is at most 2x or the p95
  absolute overhead is at most 250 microseconds. The absolute clause prevents
  timer noise on very small direct checks from manufacturing a large ratio.
  Caller-thread allocation must remain within 2x or 32 KiB absolute overhead.
  Backend operations must remain within 1.05x or one extra operation. Semantic
  public results must be exactly equal on every call."
  ([]
   (run-gate! {}))
  ([{:keys [trials
            maximum-median-p95-latency-ratio
            maximum-median-p95-absolute-overhead-ns
            maximum-median-p95-allocation-ratio
            maximum-median-p95-allocation-overhead-bytes
            maximum-median-p95-backend-operation-ratio
            maximum-median-p95-backend-operation-overhead]
     :or {trials 5
          maximum-median-p95-latency-ratio 2.0
          maximum-median-p95-absolute-overhead-ns 250000
          maximum-median-p95-allocation-ratio 2.0
          maximum-median-p95-allocation-overhead-bytes 32768
          maximum-median-p95-backend-operation-ratio 1.05
          maximum-median-p95-backend-operation-overhead 1}
     :as options}]
   (let [run-options
         (dissoc
          options
          :trials
          :maximum-median-p95-latency-ratio
          :maximum-median-p95-absolute-overhead-ns
          :maximum-median-p95-allocation-ratio
          :maximum-median-p95-allocation-overhead-bytes
          :maximum-median-p95-backend-operation-ratio
          :maximum-median-p95-backend-operation-overhead)
         trials-results
         (mapv (fn [_] (run! run-options)) (range trials))
         scenarios [:direct :acyclic :recursive :cursor :cache-hot]
         summary
         (into
          {}
          (map
           (fn [scenario]
             (let [results
                   (mapv #(get-in % [:scenarios scenario])
                         trials-results)
                   latency-ratio
                   (percentile
                    (mapv :p95-latency-ratio results)
                    0.50)
                   latency-overhead
                   (percentile
                    (mapv :p95-absolute-overhead-ns results)
                    0.50)
                   allocation-ratio
                   (percentile
                    (mapv :p95-allocation-ratio results)
                    0.50)
                   allocation-overhead
                   (percentile
                    (mapv
                     #(- (:verified-p95-allocation-bytes %)
                         (:legacy-p95-allocation-bytes %))
                     results)
                    0.50)
                   backend-operation-ratio
                   (percentile
                    (mapv :p95-backend-operation-ratio results)
                    0.50)
                   backend-operation-overhead
                   (percentile
                    (mapv :p95-backend-operation-overhead results)
                    0.50)
                   latency-passed?
                   (or
                    (<= latency-ratio
                        maximum-median-p95-latency-ratio)
                    (<= latency-overhead
                        maximum-median-p95-absolute-overhead-ns))
                   allocation-passed?
                   (or
                    (<= allocation-ratio
                        maximum-median-p95-allocation-ratio)
                    (<= allocation-overhead
                        maximum-median-p95-allocation-overhead-bytes))
                   backend-operations-passed?
                   (or
                    (<= backend-operation-ratio
                        maximum-median-p95-backend-operation-ratio)
                    (<= backend-operation-overhead
                        maximum-median-p95-backend-operation-overhead))]
               [scenario
                {:median-p95-latency-ratio latency-ratio
                 :median-p95-absolute-overhead-ns latency-overhead
                 :median-p95-allocation-ratio allocation-ratio
                 :median-p95-allocation-overhead-bytes
                 allocation-overhead
                 :median-p95-backend-operation-ratio
                 backend-operation-ratio
                 :median-p95-backend-operation-overhead
                 backend-operation-overhead
                 :latency-status
                 (if latency-passed? :passed :failed)
                 :allocation-status
                 (if allocation-passed? :passed :failed)
                 :backend-operation-status
                 (if backend-operations-passed? :passed :failed)
                 :status
                 (if (and latency-passed?
                          allocation-passed?
                          backend-operations-passed?)
                   :passed
                   :failed)}])))
          scenarios)
         passed?
         (every? #(= :passed (:status %)) (vals summary))]
     {:fixture
      (assoc
       (:fixture (first trials-results))
       :independent-trials trials
       :aggregation :median-of-trial-p95-values)
      :required
      {:maximum-median-p95-latency-ratio
       maximum-median-p95-latency-ratio
       :maximum-median-p95-absolute-overhead-ns
       maximum-median-p95-absolute-overhead-ns
       :maximum-median-p95-allocation-ratio
       maximum-median-p95-allocation-ratio
       :maximum-median-p95-allocation-overhead-bytes
       maximum-median-p95-allocation-overhead-bytes
       :maximum-median-p95-backend-operation-ratio
       maximum-median-p95-backend-operation-ratio
       :maximum-median-p95-backend-operation-overhead
       maximum-median-p95-backend-operation-overhead
       :exact-public-result-equality-every-call true
       :backend-work-measured-separately true}
      :summary summary
      :trials trials-results
      :status (if passed? :passed :failed)})))

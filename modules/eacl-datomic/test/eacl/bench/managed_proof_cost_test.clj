(ns eacl.bench.managed-proof-cost-test
  "Explicit, locally reproducible ordered-generation cache measurements.

  The benchmark interleaves scalar-frontier and former full-vector key work,
  reports p50/p95 latency and allocation, records key sizes and backend calls,
  and separately measures exact hits, managed hits after unrelated commits,
  and relevant-proof misses. It is intentionally outside the normal suite."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [datascript.core :as ds]
            [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]
            [eacl.datahike.backend :as datahike-backend]
            [eacl.datahike.core :as datahike]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.datomic.backend :as datomic-backend]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.schema :as datomic-schema])
  (:import [com.sun.management ThreadMXBean]
           [java.lang.management ManagementFactory]))

(def ^:private maximum-dependency-count 256)
(def ^:private dependency-counts [0 1 8 64 256])
(def ^:private proof-calls-per-sample 200)
(def ^:private proof-warmup-samples 8)
(def ^:private proof-measurement-samples 31)
(def ^:private exact-calls-per-sample 100)
(def ^:private request-warmup-samples 5)
(def ^:private request-measurement-samples 31)
(def ^:private relevant-miss-pairs 12)

(defn- benchmark-schema
  []
  (str
   "definition user {}\n"
   "definition document {\n"
   "  relation reader: user\n"
   "  relation auditor: user\n"
   (str/join
    ""
    (map (fn [index]
           (str "  relation dep" index ": user\n"))
         (range maximum-dependency-count)))
   "  permission view = reader\n"
   "}"))

(defn- percentile
  [samples p]
  (let [ordered (vec (sort samples))
        index (min (dec (count ordered))
                   (long (Math/floor (* p (count ordered)))))]
    (nth ordered index)))

(defn- distribution
  [samples]
  {:p50 (percentile samples 0.50)
   :p95 (percentile samples 0.95)
   :sample-count (count samples)})

(defn- allocation-bean
  []
  (let [bean (ManagementFactory/getThreadMXBean)]
    (when (instance? ThreadMXBean bean)
      (let [bean ^ThreadMXBean bean]
        (when (.isThreadAllocatedMemorySupported bean)
          (when-not (.isThreadAllocatedMemoryEnabled bean)
            (.setThreadAllocatedMemoryEnabled bean true))
          bean)))))

(def ^:private allocated-memory-bean (delay (allocation-bean)))

(defn- allocated-bytes
  []
  (when-let [bean @allocated-memory-bean]
    (.getThreadAllocatedBytes ^ThreadMXBean bean
                              (.getId (Thread/currentThread)))))

(defn- measured
  [calls f]
  (let [allocated-before (allocated-bytes)
        started (System/nanoTime)
        value (f)
        elapsed (- (System/nanoTime) started)
        allocated-after (allocated-bytes)]
    {:value value
     :ns-per-call (/ (double elapsed) calls)
     :allocated-bytes-per-call
     (when (and allocated-before allocated-after)
       (/ (double (- allocated-after allocated-before)) calls))}))

(defn- key-bytes
  [value]
  (alength (.getBytes (pr-str value) "UTF-8")))

(defn- full-vector-key
  [{:keys [schema-stamp relation-stamps]}]
  {:schema-stamp schema-stamp
   :relation-stamps relation-stamps})

(defn- scalar-frontier-key
  [{:keys [schema-stamp relation-stamps]}]
  {:schema-stamp schema-stamp
   :dependency-stamp
   (reduce
    (fn [frontier [_ generation]]
      (max frontier generation))
    0
    relation-stamps)})

(defn- proof-key-sample
  [adapter relation-ids mode]
  (let [sink (volatile! 0)
        key-fn (case mode
                 :scalar scalar-frontier-key
                 :full-vector full-vector-key)]
    (measured
     proof-calls-per-sample
     (fn []
       (dotimes [_ proof-calls-per-sample]
         (let [proof (backend/invoke adapter :proof-frame relation-ids)
               key (key-fn proof)]
           (vswap! sink bit-xor (hash key))))
       @sink))))

(defn- interleaved-proof-measurement
  [adapter relation-ids]
  (dotimes [sample proof-warmup-samples]
    (doseq [mode (if (even? sample)
                   [:scalar :full-vector]
                   [:full-vector :scalar])]
      (proof-key-sample adapter relation-ids mode)))
  (let [backend-ops (atom {})
        samples
        (binding [backend/*backend-op-stats* backend-ops]
          (reduce
           (fn [result sample]
             (reduce
              (fn [result mode]
                (update result mode conj
                        (proof-key-sample adapter relation-ids mode)))
              result
              (if (even? sample)
                [:scalar :full-vector]
                [:full-vector :scalar])))
           {:scalar [] :full-vector []}
           (range proof-measurement-samples)))
        proof (backend/invoke adapter :proof-frame relation-ids)
        summarize
        (fn [mode]
          (let [mode-samples (get samples mode)]
            {:latency-ns
             (distribution (mapv :ns-per-call mode-samples))
             :allocated-bytes
             (when (every? some?
                           (map :allocated-bytes-per-call mode-samples))
               (distribution
                (mapv :allocated-bytes-per-call mode-samples)))
             :key-bytes
             (key-bytes
              ((case mode
                 :scalar scalar-frontier-key
                 :full-vector full-vector-key)
               proof))}))]
    {:dependency-count (count relation-ids)
     :scalar (summarize :scalar)
     :full-vector (summarize :full-vector)
     :backend-operations @backend-ops}))

(defn- capture-transaction-shapes!
  [transact-var f]
  (let [transactions (atom [])
        original @transact-var]
    (with-redefs-fn
      {transact-var
       (fn [connection tx-data]
         (swap! transactions conj (vec tx-data))
         (original connection tx-data))}
      f)
    @transactions))

(defn- legacy-graph-or-journal-op?
  [tx-op]
  (boolean
   (some
    (fn [value]
      (and (keyword? value)
           (contains? #{"eacl.graph" "eacl.mutation"}
                      (namespace value))))
    (tree-seq coll? seq tx-op))))

(defn- request-sample
  [backend-ops calls f]
  (binding [backend/*backend-op-stats* backend-ops]
    (measured calls f)))

(defn- exact-request-measurement
  [client demand]
  (dotimes [_ request-warmup-samples]
    (dotimes [_ exact-calls-per-sample]
      (eacl/check-permission client demand)))
  (let [backend-ops (atom {})
        samples
        (mapv
         (fn [_]
           (request-sample
            backend-ops
            exact-calls-per-sample
            (fn []
              (dotimes [_ exact-calls-per-sample]
                (when-not (:allowed? (eacl/check-permission client demand))
                  (throw (ex-info "exact benchmark changed denotation" {})))))))
         (range request-measurement-samples))]
    {:latency-ns (distribution (mapv :ns-per-call samples))
     :allocated-bytes
     (when (every? some? (map :allocated-bytes-per-call samples))
       (distribution (mapv :allocated-bytes-per-call samples)))
     :backend-operations @backend-ops}))

(defn- relevant-miss-measurement
  [client demand relationship]
  (let [backend-ops (atom {})
        samples (transient [])]
    (dotimes [_ relevant-miss-pairs]
      (eacl/delete-relationship! client relationship)
      (conj! samples
             (request-sample
              backend-ops 1
              (fn []
                (let [result (eacl/check-permission client demand)]
                  (when (or (:allowed? result) (:cached? result))
                    (throw
                     (ex-info "relevant deletion reused a cached answer"
                              {:result result})))
                  result))))
      (eacl/create-relationship! client relationship)
      (conj! samples
             (request-sample
              backend-ops 1
              (fn []
                (let [result (eacl/check-permission client demand)]
                  (when (or (not (:allowed? result)) (:cached? result))
                    (throw
                     (ex-info "relevant addition reused a cached answer"
                              {:result result})))
                  result)))))
    (let [samples (persistent! samples)]
      {:latency-ns (distribution (mapv :ns-per-call samples))
       :allocated-bytes
       (when (every? some? (map :allocated-bytes-per-call samples))
         (distribution (mapv :allocated-bytes-per-call samples)))
       :backend-operations @backend-ops})))

(defn- backend-cache-stats
  [label client]
  (case label
    :datomic (datomic/cache-stats client)
    :datahike (datahike/cache-stats client)
    :datascript (datascript/cache-stats client)))

(defn- managed-request-measurement
  [label client demand transact-objects!]
  (let [backend-ops (atom {})
        before-stats (backend-cache-stats label client)
        samples
        (mapv
         (fn [index]
           (transact-objects!
            [(eacl/spice-object
              :unrelated
              (str (name label) "-managed-sample-" index))])
           (request-sample
            backend-ops 1
            (fn []
              (let [result (eacl/check-permission client demand)]
                (when-not (and (:allowed? result) (:cached? result))
                  (throw
                   (ex-info "expected managed hit after unrelated commit"
                            {:result result})))
                result))))
         (range request-measurement-samples))
        after-stats (backend-cache-stats label client)]
    {:latency-ns (distribution (mapv :ns-per-call samples))
     :allocated-bytes
     (when (every? some? (map :allocated-bytes-per-call samples))
       (distribution (mapv :allocated-bytes-per-call samples)))
     :backend-operations @backend-ops
     :managed-hits (- (:managed-hits after-stats)
                      (:managed-hits before-stats))}))

(defn- relation-id
  [adapter relation-name]
  (:relation-id
   (first (backend/invoke adapter :relation-defs :document relation-name))))

(defn- exercise-backend!
  [{:keys [label client transact-var transact-objects! adapter close!]}]
  (let [reader (eacl/spice-object :user (str (name label) "-reader"))
        target (eacl/spice-object :document (str (name label) "-target"))
        relationship (eacl/->Relationship reader :reader target)
        demand {:subject reader :permission :view :resource target}]
    (try
      (eacl/write-schema! client (benchmark-schema))
      (transact-objects! [reader target])
      (let [[create-tx delete-tx]
            (capture-transaction-shapes!
             transact-var
             #(do
                (eacl/create-relationship! client relationship)
                (eacl/delete-relationship! client relationship)))]
        (eacl/create-relationship! client relationship)
        (let [selected (adapter)
              all-dependency-ids
              (mapv #(relation-id selected (keyword (str "dep" %)))
                    (range maximum-dependency-count))
              proof-results
              (mapv
               #(interleaved-proof-measurement
                 selected (subvec all-dependency-ids 0 %))
               dependency-counts)
              _ (eacl/check-permission client demand)
              exact (exact-request-measurement client demand)
              managed (managed-request-measurement
                       label client demand transact-objects!)
              relevant (relevant-miss-measurement
                        client demand relationship)
              result
              {:backend label
               :create-committed-datom-events (count create-tx)
               :delete-committed-datom-events (count delete-tx)
               :proof-cardinalities proof-results
               :exact-hit exact
               :managed-hit-after-unrelated-commit managed
               :relevant-proof-miss relevant}]
          (is (pos? (:create-committed-datom-events result)))
          (is (pos? (:delete-committed-datom-events result)))
          (is (zero? (get-in exact [:backend-operations :proof-frame] 0))
              (pr-str exact))
          (is (= request-measurement-samples (:managed-hits managed))
              (pr-str managed))
          (doseq [{:keys [dependency-count scalar full-vector
                         backend-operations]}
                  proof-results]
            (is (= (* 2 proof-measurement-samples proof-calls-per-sample)
                   (:proof-frame backend-operations))
                (str label " d=" dependency-count))
            (is (<= (:key-bytes scalar) 96)
                (pr-str scalar))
            (is (or (zero? dependency-count)
                    (< (:key-bytes scalar) (:key-bytes full-vector)))
                (str label " d=" dependency-count)))
          result))
      (finally
        (close!)))))

(defn- datomic-fixture
  []
  (let [uri (str "datomic:mem://eacl-proof-cost-" (random-uuid))
        _ (d/create-database uri)
        connection (d/connect uri)
        _ @(d/transact connection datomic-schema/v7-schema)
        client
        (datomic/make-client
         connection
         {:security-key "managed-proof-cost-page000000000"})]
    {:label :datomic
     :client client
     :transact-var #'d/transact
     :transact-objects!
     (fn [objects]
       @(d/transact connection
                    (mapv (fn [object] {:eacl/id (:id object)}) objects)))
     :adapter #(datomic-backend/basis-adapter
                (d/db connection)
                (select-keys (:runtime client)
                             datomic-backend/adapter-config-keys))
     :close! #(d/delete-database uri)}))

(defn- datahike-fixture
  []
  (let [connection (datahike/create-conn)
        client
        (datahike/make-client
         connection
         {:security-key "01234567890123456789012345678901"})]
    {:label :datahike
     :client client
     :transact-var #'dh/transact
     :transact-objects!
     (fn [objects]
       (dh/transact connection
                    (mapv (fn [object] {:eacl/id (:id object)}) objects)))
     :adapter #(datahike-backend/basis-adapter
                (dh/db connection)
                (select-keys (:runtime client)
                             datahike-backend/adapter-config-keys))
     :close! #(dh/release connection)}))

(defn- datascript-fixture
  []
  (let [connection (datascript/create-conn)
        client
        (datascript/make-client
         connection
         {:security-key "01234567890123456789012345678901"})]
    {:label :datascript
     :client client
     :transact-var #'ds/transact!
     :transact-objects!
     (fn [objects]
       (ds/transact! connection
                     (mapv (fn [object] {:eacl/id (:id object)}) objects)))
     :adapter #(datascript-backend/basis-adapter
                (ds/db connection)
                (select-keys (:runtime client)
                             datascript-backend/adapter-config-keys))
     :close! (constantly nil)}))

(defn- transact-concurrently!
  [client relationships]
  (let [start (promise)
        tasks
        (mapv
         (fn [relationship]
           (future
             @start
             (eacl/create-relationship! client relationship)))
         relationships)]
    (deliver start true)
    (mapv deref tasks)))

(defn- exercise-unrelated-concurrency!
  [{:keys [label client transact-var transact-objects! close!]}]
  (let [subject-a (eacl/spice-object :user (str (name label) "-concurrent-a"))
        subject-b (eacl/spice-object :user (str (name label) "-concurrent-b"))
        subject-c (eacl/spice-object :user (str (name label) "-contention-a"))
        subject-d (eacl/spice-object :user (str (name label) "-contention-b"))
        target-a (eacl/spice-object :document (str (name label) "-concurrent-a"))
        target-b (eacl/spice-object :document (str (name label) "-concurrent-b"))
        target-c (eacl/spice-object :document (str (name label) "-contention-a"))
        target-d (eacl/spice-object :document (str (name label) "-contention-b"))
        unrelated
        [(eacl/->Relationship subject-a :reader target-a)
         (eacl/->Relationship subject-b :auditor target-b)]
        same-relation
        [(eacl/->Relationship subject-c :reader target-c)
         (eacl/->Relationship subject-d :reader target-d)]]
    (try
      (eacl/write-schema! client (benchmark-schema))
      (transact-objects! [subject-a subject-b subject-c subject-d
                          target-a target-b target-c target-d])
      (let [unrelated-transactions
            (capture-transaction-shapes!
             transact-var
             #(transact-concurrently! client unrelated))
            same-relation-transactions
            (capture-transaction-shapes!
             transact-var
             #(transact-concurrently! client same-relation))
            all-transactions
            (into unrelated-transactions same-relation-transactions)
            result
            {:backend label
             :unrelated-logical-writes (count unrelated)
             :unrelated-transaction-attempts (count unrelated-transactions)
             :same-relation-logical-writes (count same-relation)
             :same-relation-transaction-attempts
             (count same-relation-transactions)
             :global-graph-or-journal-ops
             (count
              (filter legacy-graph-or-journal-op?
                      (mapcat identity all-transactions)))}]
        (is (= (:unrelated-logical-writes result)
               (:unrelated-transaction-attempts result))
            (pr-str result))
        (is (<= (:same-relation-logical-writes result)
                (:same-relation-transaction-attempts result))
            (pr-str result))
        (is (zero? (:global-graph-or-journal-ops result))
            (pr-str result))
        result)
      (finally
        (close!)))))

(defn- compact-performance-result
  [{:keys [backend proof-cardinalities exact-hit
           managed-hit-after-unrelated-commit relevant-proof-miss]}]
  {:backend backend
   :proof-cardinalities
   (mapv
    (fn [{:keys [dependency-count scalar full-vector backend-operations]}]
      {:dependency-count dependency-count
       :scalar scalar
       :full-vector full-vector
       :proof-frame-calls (:proof-frame backend-operations)})
    proof-cardinalities)
   :exact-hit exact-hit
   :managed-hit-after-unrelated-commit managed-hit-after-unrelated-commit
   :relevant-proof-miss relevant-proof-miss})

(deftest ^:benchmark cross-backend-scalar-frontier-and-request-cost-test
  (testing "scalar-frontier keys and complete request paths"
    (let [results
          (mapv (fn [fixture-fn]
                  (exercise-backend! (fixture-fn)))
                [datomic-fixture datahike-fixture datascript-fixture])]
      (println "EACL scalar-frontier performance samples"
               (pr-str (mapv compact-performance-result results)))
      (is (= [:datomic :datahike :datascript]
             (mapv :backend results))))))

(deftest ^:benchmark unrelated-relation-writers-have-no-global-retry-test
  (testing "unrelated relation writers have no journal/head serialization"
    (let [results
          (mapv (fn [fixture-fn]
                  (exercise-unrelated-concurrency! (fixture-fn)))
                [datomic-fixture datahike-fixture datascript-fixture])]
      (println "EACL unrelated writer concurrency samples" (pr-str results))
      (is (= [:datomic :datahike :datascript]
             (mapv :backend results))))))

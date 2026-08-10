(ns eacl.bench.managed-proof-cost-test
  "Heavy, explicit resource measurements for managed relation proofs.

  Planned transaction datoms, relation-proof calls, unrelated database
  cardinality, concurrency attempts, and elapsed nanoseconds are deliberately
  separate dimensions. This namespace is outside the normal suite and must be
  invoked explicitly."
  (:require [clojure.test :refer [deftest is testing]]
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
            [eacl.datomic.schema :as datomic-schema]))

(def ^:private benchmark-schema
  "definition user {}
   definition document {
     relation reader: user
     relation auditor: user
     permission view = reader
   }")

(def ^:private unrelated-edge-count 1024)
(def ^:private proof-calls-per-sample 500)
(def ^:private proof-warmup-samples 8)
(def ^:private proof-measurement-samples 31)
(def ^:private maximum-cardinality-ratio 5.0)

(defn- percentile
  [samples p]
  (let [ordered (vec (sort samples))
        index
        (min
         (dec (count ordered))
         (long (Math/floor (* p (count ordered)))))]
    (nth ordered index)))

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

(defn- proof-samples
  [adapter relation-id]
  (let [sample
        (fn []
          (let [started (System/nanoTime)]
            (dotimes [_ proof-calls-per-sample]
              (backend/invoke
               adapter :relation-proof [relation-id]))
            (/ (double (- (System/nanoTime) started))
               proof-calls-per-sample)))]
    (dotimes [_ proof-warmup-samples]
      (sample))
    (vec
     (repeatedly proof-measurement-samples sample))))

(defn- reader-proof
  [adapter]
  (let [relation-id
        (:relation-id
         (first
          (backend/invoke
           adapter :relation-defs :document :reader)))]
    {:relation-id relation-id
     :proof
     (backend/invoke adapter :relation-proof [relation-id])
     :samples-ns-per-call
     (proof-samples adapter relation-id)}))

(defn- exercise-backend!
  [{:keys [label client transact-var transact-objects! adapter close!]}]
  (let [reader
        (eacl/spice-object :user (str (name label) "-reader"))
        target
        (eacl/spice-object :document (str (name label) "-target"))
        relationship
        (eacl/->Relationship reader :reader target)]
    (try
      (eacl/write-schema! client benchmark-schema)
      (transact-objects! [reader target])
      (let [[create-tx delete-tx]
            (capture-transaction-shapes!
             transact-var
             #(do
                (eacl/create-relationship! client relationship)
                (eacl/delete-relationship! client relationship)))
            create-datom-events (count create-tx)
            delete-datom-events (count delete-tx)]
        (eacl/create-relationship! client relationship)
        (let [small (reader-proof (adapter))
              auditors
              (mapv
               #(eacl/spice-object
                 :user
                 (str (name label) "-auditor-" %))
               (range unrelated-edge-count))
              documents
              (mapv
               #(eacl/spice-object
                 :document
                 (str (name label) "-unrelated-" %))
               (range unrelated-edge-count))
              unrelated
              (mapv
               #(eacl/->Relationship %1 :auditor %2)
               auditors documents)]
          (transact-objects! (into auditors documents))
          (eacl/create-relationships! client unrelated)
          (let [large (reader-proof (adapter))
                small-p50
                (percentile (:samples-ns-per-call small) 0.50)
                large-p50
                (percentile (:samples-ns-per-call large) 0.50)
                ratio (/ large-p50 small-p50)
                result
                {:backend label
                 :logical-relationship-writes 2
                 :create-committed-datom-events create-datom-events
                 :delete-committed-datom-events delete-datom-events
                 :unrelated-edge-count unrelated-edge-count
                 :proof-calls-per-sample proof-calls-per-sample
                 :proof-measurement-samples proof-measurement-samples
                 :small-proof-p50-ns-per-call small-p50
                 :large-proof-p50-ns-per-call large-p50
                 :large-to-small-p50-ratio ratio
                 :small-proof-samples-ns-per-call
                 (:samples-ns-per-call small)
                 :large-proof-samples-ns-per-call
                 (:samples-ns-per-call large)}]
            (is (pos? create-datom-events)
                (pr-str result))
            (is (pos? delete-datom-events)
                (pr-str result))
            (is (= (:proof small) (:proof large))
                "unrelated relation growth must not change the reader proof")
            (is (<= ratio maximum-cardinality-ratio)
                (str
                 "managed relation-proof p50 scaled with unrelated graph "
                 "cardinality: "
                 (pr-str result)))
            result)))
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
         {:coherence-authority :managed
          :proof-mode :mutation
          :page-token-key "managed-proof-cost-page"
          :zed-token-key "managed-proof-cost-zed"})]
    {:label :datomic
     :client client
     :transact-var #'d/transact
     :transact-objects!
     (fn [objects]
       @(d/transact
         connection
         (mapv
          (fn [object]
            {:eacl/id (:id object)})
          objects)))
     :adapter
     #(datomic-backend/snapshot-adapter
       (d/db connection) (:opts client))
     :close!
     #(d/delete-database uri)}))

(defn- datahike-fixture
  []
  (let [connection (datahike/create-conn)
        client
        (datahike/make-client
         connection
         {:coherence-authority :managed
          :proof-mode :mutation
          :security-key "01234567890123456789012345678901"})]
    {:label :datahike
     :client client
     :transact-var #'dh/transact
     :transact-objects!
     (fn [objects]
       (dh/transact
        connection
        (mapv
         (fn [object]
           {:eacl/id (:id object)})
         objects)))
     :adapter
     #(datahike-backend/snapshot-adapter
       (dh/db connection) (:opts client))
     :close!
     #(dh/release connection)}))

(defn- datascript-fixture
  []
  (let [connection (datascript/create-conn)
        client
        (datascript/make-client
         connection
         {:coherence-authority :managed
          :proof-mode :mutation
          :security-key "01234567890123456789012345678901"})]
    {:label :datascript
     :client client
     :transact-var #'ds/transact!
     :transact-objects!
     (fn [objects]
       (ds/transact!
        connection
        (mapv
         (fn [object]
           {:eacl/id (:id object)})
         objects)))
     :adapter
     #(datascript-backend/snapshot-adapter
       (ds/db connection) (:opts client))
     :close! (constantly nil)}))

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
      (eacl/write-schema! client benchmark-schema)
      (transact-objects! [subject-a subject-b subject-c subject-d
                          target-a target-b target-c target-d])
      (let [started (System/nanoTime)
            unrelated-transactions
            (capture-transaction-shapes!
             transact-var
             #(transact-concurrently! client unrelated))
            unrelated-elapsed-ns (- (System/nanoTime) started)
            contention-started (System/nanoTime)
            same-relation-transactions
            (capture-transaction-shapes!
             transact-var
             #(transact-concurrently! client same-relation))
            same-relation-elapsed-ns
            (- (System/nanoTime) contention-started)
            all-transactions
            (into unrelated-transactions same-relation-transactions)
            result
            {:backend label
             :unrelated-logical-writes (count unrelated)
             :unrelated-transaction-attempts
             (count unrelated-transactions)
             :unrelated-elapsed-ns unrelated-elapsed-ns
             :same-relation-logical-writes (count same-relation)
             :same-relation-transaction-attempts
             (count same-relation-transactions)
             :same-relation-elapsed-ns same-relation-elapsed-ns
             :global-graph-or-journal-ops
             (count
              (filter legacy-graph-or-journal-op?
                      (mapcat identity all-transactions)))}]
        (is (= (:unrelated-logical-writes result)
               (:unrelated-transaction-attempts result))
            (str "unrelated relation writers must not retry through a shared "
                 "database-global serialization point: " (pr-str result)))
        (is (<= (:same-relation-logical-writes result)
                (:same-relation-transaction-attempts result))
            (pr-str result))
        (is (zero? (:global-graph-or-journal-ops result))
            (pr-str result))
        result)
      (finally
        (close!)))))

(deftest ^:benchmark cross-backend-write-amplification-and-proof-cost-test
  (testing "Datomic, Datahike, and DataScript retain constant-cardinality proofs"
    (let [results
          (mapv
           (fn [fixture-fn]
             (exercise-backend! (fixture-fn)))
           [datomic-fixture datahike-fixture datascript-fixture])]
      (println "EACL managed proof/write resource samples" (pr-str results))
      (is (= [:datomic :datahike :datascript]
             (mapv :backend results))))))

(deftest ^:benchmark unrelated-relation-writers-have-no-global-retry-test
  (testing "unrelated relation writers have no journal/head serialization"
    (let [results
          (mapv
           (fn [fixture-fn]
             (exercise-unrelated-concurrency! (fixture-fn)))
           [datomic-fixture datahike-fixture datascript-fixture])]
      (println "EACL unrelated writer concurrency samples" (pr-str results))
      (is (= [:datomic :datahike :datascript]
             (mapv :backend results))))))

(ns eacl.bench.managed-proof-cost-test
  "Heavy, explicit resource measurements for managed relation proofs.

  Committed datom events, relation-proof calls, graph cardinality, and elapsed
  nanoseconds are deliberately separate dimensions. This namespace is outside
  the normal suite and must be invoked explicitly."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [datascript.core :as ds]
            [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]
            [eacl.datahike.backend :as datahike-backend]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.mutation :as datahike-journal]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.mutation :as datascript-journal]
            [eacl.datomic.backend :as datomic-backend]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.mutation :as datomic-journal]
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

(defn- capture-journal-reports!
  [journal-var f]
  (let [reports (atom [])
        original @journal-var]
    (with-redefs-fn
      {journal-var
       (fn [connection options]
         (let [report (original connection options)]
           (swap! reports conj report)
           report))}
      f)
    @reports))

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
  [{:keys [label client journal-var transact-objects! adapter close!]}]
  (let [reader
        (eacl/spice-object :user (str (name label) "-reader"))
        target
        (eacl/spice-object :document (str (name label) "-target"))
        relationship
        (eacl/->Relationship reader :reader target)]
    (try
      (eacl/write-schema! client benchmark-schema)
      (transact-objects! [reader target])
      (let [[create-report delete-report]
            (capture-journal-reports!
             journal-var
             #(do
                (eacl/create-relationship! client relationship)
                (eacl/delete-relationship! client relationship)))
            create-datom-events (count (:tx-data create-report))
            delete-datom-events (count (:tx-data delete-report))]
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
     :journal-var #'datomic-journal/transact!
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
     :journal-var #'datahike-journal/transact!
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
     :journal-var #'datascript-journal/transact!
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

(deftest cross-backend-write-amplification-and-proof-cost-test
  (testing "Datomic, Datahike, and DataScript retain constant-cardinality proofs"
    (let [results
          (mapv
           (fn [fixture-fn]
             (exercise-backend! (fixture-fn)))
           [datomic-fixture datahike-fixture datascript-fixture])]
      (println "EACL managed proof/write resource samples" (pr-str results))
      (is (= [:datomic :datahike :datascript]
             (mapv :backend results))))))

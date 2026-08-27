(ns eacl.bench.datalevin-ordered-generation-test
  (:require [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]
            [eacl.datalevin.core :as datalevin]))

(def ^:private test-key "01234567890123456789012345678901")
(def ^:private maximum-closure-size 4096)

(def ^:private schema
  "definition user {}
   definition document {
     relation viewer: user
     relation editor: user
     permission view = viewer
   }")

(def regression-budgets-ms
  {:frame-zero-p95 2.0
   :frame-typical-p95 5.0
   :frame-maximum-p95 100.0
   :exact-hit-p95 5.0
   :managed-hit-p95 10.0
   :write-commit-p50 20.0
   :write-commit-p95 100.0})

(defn- percentile
  [samples fraction]
  (let [ordered (vec (sort samples))
        index (min (dec (count ordered))
                   (long (Math/floor (* fraction (count ordered)))))]
    (nth ordered index)))

(defn- measure
  [warmups samples f]
  (dotimes [_ warmups] (f))
  (let [values
        (mapv
         (fn [_]
           (let [started (System/nanoTime)]
             (f)
             (/ (double (- (System/nanoTime) started)) 1000000.0)))
         (range samples))]
    {:samples samples
     :p50-ms (percentile values 0.50)
     :p95-ms (percentile values 0.95)
     :maximum-ms (apply max values)}))

(defn- within-budgets?
  [report]
  (every?
   true?
   [(<= (get-in report [:frame :zero :p95-ms])
        (:frame-zero-p95 regression-budgets-ms))
    (<= (get-in report [:frame :typical :p95-ms])
        (:frame-typical-p95 regression-budgets-ms))
    (<= (get-in report [:frame :maximum :p95-ms])
        (:frame-maximum-p95 regression-budgets-ms))
    (<= (get-in report [:requests :exact-hit :p95-ms])
        (:exact-hit-p95 regression-budgets-ms))
    (<= (get-in report [:requests :managed-hit :p95-ms])
        (:managed-hit-p95 regression-budgets-ms))
    (<= (get-in report [:writes :policy-active :p50-ms])
        (:write-commit-p50 regression-budgets-ms))
    (<= (get-in report [:writes :policy-active :p95-ms])
        (:write-commit-p95 regression-budgets-ms))]))

(defn run-benchmark!
  []
  (let [dir (u/tmp-dir (str "eacl-datalevin-proof-bench-" (random-uuid)))
        conn (datalevin/create-conn dir)
        watermark (atom 0)
        client
        (datalevin/make-client
         conn
         {:security-key test-key
          :source-lifecycle "ordered-generation-benchmark"
          :revision-watermark watermark
          :advance-revision-watermark! #(swap! watermark max %)})]
    (try
      (eacl/write-schema! client schema)
      (d/transact!
       conn
       (mapv (fn [id] {:eacl/id id})
             ["alice" "bob" "document-1" "document-2"]))
      (let [policy (d/write-policy conn)
            token (:write-token (d/install-write-policy! conn policy))
            synthetic-relation-ids
            (vec (range 10000 (+ 10000 maximum-closure-size)))]
        (d/transact!
         conn
         (mapv
          (fn [relation-id]
            [:db/add relation-id
             :eacl.datalevin/relation-generation
             :db/current-tx])
          synthetic-relation-ids)
         {:datalevin/write-token token})
        (let [selection (source/acquire! (:source client) :current)]
          (try
            (let [adapter (source/adapter selection)
                  frame
                  {:zero
                   (measure 20 100
                            #(backend/invoke adapter :proof-frame []))
                   :typical
                   (measure 20 100
                            #(backend/invoke
                              adapter :proof-frame
                              (subvec synthetic-relation-ids 0 8)))
                   :maximum
                   (measure 2 12
                            #(backend/invoke
                              adapter :proof-frame
                              synthetic-relation-ids))}
                  alice (eacl/spice-object :user "alice")
                  bob (eacl/spice-object :user "bob")
                  document-1 (eacl/spice-object :document "document-1")
                  document-2 (eacl/spice-object :document "document-2")
                  viewer (eacl/->Relationship alice :viewer document-1)
                  editor (eacl/->Relationship bob :editor document-2)
                  demand {:subject alice
                          :permission :view
                          :resource document-1}
                  _ (eacl/create-relationship! client viewer)
                  _ (eacl/check-permission client demand)
                  exact
                  (measure 50 300
                           #(eacl/check-permission client demand))
                  present? (atom false)
                  write-samples (atom [])
                  managed-samples
                  (mapv
                   (fn [_]
                     (let [started (System/nanoTime)]
                       (if (swap! present? not)
                         (eacl/create-relationship! client editor)
                         (eacl/delete-relationship! client editor))
                       (swap! write-samples conj
                              (/ (double (- (System/nanoTime) started))
                                 1000000.0)))
                     (let [started (System/nanoTime)
                           response (eacl/check-permission client demand)
                           elapsed (/ (double (- (System/nanoTime) started))
                                      1000000.0)]
                       (when-not (:cached? response)
                         (throw
                          (ex-info "Expected a managed cache hit."
                                   {:response response})))
                       elapsed))
                   (range 100))
                  report
                  {:format-version 1
                   :benchmark :datalevin-ordered-generation
                   :maximum-closure-size maximum-closure-size
                   :regression-budgets-ms regression-budgets-ms
                   :frame frame
                   :requests
                   {:exact-hit exact
                    :managed-hit
                    {:samples (count managed-samples)
                     :p50-ms (percentile managed-samples 0.50)
                     :p95-ms (percentile managed-samples 0.95)
                     :maximum-ms (apply max managed-samples)}}
                   :writes
                   {:policy-active
                    {:samples (count @write-samples)
                     :p50-ms (percentile @write-samples 0.50)
                     :p95-ms (percentile @write-samples 0.95)
                     :maximum-ms (apply max @write-samples)}}}]
              (assoc report :passed? (within-budgets? report)))
            (finally
              (source/release! selection)))))
      (finally
        (d/close conn)
        (u/delete-files dir)))))

(deftest ^:benchmark ordered-generation-regression-budget-test
  (let [report (run-benchmark!)]
    (is (:passed? report) (pr-str report))))

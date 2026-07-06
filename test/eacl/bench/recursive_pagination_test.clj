(ns eacl.bench.recursive-pagination-test
  "Recursive pagination benchmarks for the stable frontier iterator.

   Baseline on eacl/v7 before this change (depth-1000 parent chain, internal API):
   - lookup-resources limit=50: median ~1312ms
   - count-resources limit=50: median ~1342ms

   This benchmark exercises the public API and ensures the recursive path
   remains materially faster than the old full-set closure solver."
  (:require [clojure.test :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.core :as eacl]
            [eacl.datomic.core :as spiceomic]
            [eacl.datomic.impl :as impl :refer [Relationship]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.fixtures :refer [->user ->account]]))

(def recursive-parent-schema-dsl
  "definition user {}

   definition account {
     relation parent: account
     relation reader: user

     permission read = reader + parent->read
   }")

(defn- tx-relationships
  [db relationships]
  (mapcat #(impl/tx-relationship db %) relationships))

(defn- seed-recursive-parent!
  [conn depth]
  (let [acl (spiceomic/make-client conn {})]
    @(d/transact conn schema/v6-schema)
    (eacl/write-schema! acl recursive-parent-schema-dsl)
    @(d/transact conn
                 (into [{:db/id "user-1" :eacl/id "user-1"}]
                       (map (fn [i]
                              {:db/id (str "acc-" i)
                               :eacl/id (str "acc-" i)}))
                       (range depth)))
    @(d/transact conn
                 (tx-relationships (d/db conn)
                                   (concat
                                    [(Relationship (->user "user-1") :reader (->account "acc-0"))]
                                    (for [i (range (dec depth))]
                                      (Relationship (->account (str "acc-" i))
                                                    :parent
                                                    (->account (str "acc-" (inc i))))))))
    acl))

(defn- run-timed
  [n f]
  (mapv (fn [_]
          (let [start (System/nanoTime)
                _     (f)
                end   (System/nanoTime)]
            (/ (double (- end start)) 1e6)))
        (range n)))

(defn- median
  [coll]
  (let [sorted (sort coll)
        n      (count sorted)
        mid    (quot n 2)]
    (if (odd? n)
      (nth sorted mid)
      (/ (+ (nth sorted (dec mid)) (nth sorted mid)) 2.0))))

(defn- percentile
  [coll p]
  (let [sorted (sort coll)
        idx    (min (dec (count sorted))
                    (int (Math/ceil (* (/ p 100.0) (count sorted)))))]
    (nth sorted idx)))

(def ^:private chain-depth 1000)
(def ^:private warmup-iterations 10)
(def ^:private bench-iterations 20)
(def ^:private pagination-iterations 10)
(def ^:private pages-per-run 20)
(def ^:private first-page-threshold-ms 50)
(def ^:private count-threshold-ms 50)
(def ^:private per-page-threshold-ms 10)

(deftest ^:benchmark recursive-parent-pagination-benchmark
  (testing "Recursive parent pagination performance"
    (with-mem-conn [conn []]
      (let [acl        (seed-recursive-parent! conn chain-depth)
            base-query {:subject       (->user "user-1")
                        :permission    :read
                        :resource/type :account
                        :limit         50
                        :max-depth     2000}]

        (testing "first page lookup (limit=50)"
          (run-timed warmup-iterations #(eacl/lookup-resources acl base-query))
          (let [times (run-timed bench-iterations #(eacl/lookup-resources acl base-query))
                med   (median times)
                p95   (percentile times 95)]
            (println (format "Recursive first page (limit=50): median=%.2fms, p95=%.2fms, min=%.2fms, max=%.2fms"
                             med p95 (apply min times) (apply max times)))
            (is (< med first-page-threshold-ms)
                (format "REGRESSION: recursive first page median %.2fms exceeds %dms threshold"
                        med first-page-threshold-ms))))

        (testing "count from the first page frontier (limit=50)"
          (run-timed warmup-iterations #(eacl/count-resources acl base-query))
          (let [times (run-timed bench-iterations #(eacl/count-resources acl base-query))
                med   (median times)
                p95   (percentile times 95)]
            (println (format "Recursive count (limit=50): median=%.2fms, p95=%.2fms, min=%.2fms, max=%.2fms"
                             med p95 (apply min times) (apply max times)))
            (is (< med count-threshold-ms)
                (format "REGRESSION: recursive count median %.2fms exceeds %dms threshold"
                        med count-threshold-ms))))

        (testing "multi-page pagination"
          (let [times (run-timed pagination-iterations
                                 (fn []
                                   (loop [cursor nil
                                          page   0]
                                     (when (< page pages-per-run)
                                       (let [result (eacl/lookup-resources acl (cond-> base-query
                                                                                 cursor (assoc :cursor cursor)))]
                                         (recur (:cursor result) (inc page)))))))
                med      (median times)
                per-page (/ med pages-per-run)]
            (println (format "Recursive pagination (%d pages): median=%.2fms total (%.2fms/page), min=%.2fms, max=%.2fms"
                             pages-per-run med per-page (apply min times) (apply max times)))
            (is (< per-page per-page-threshold-ms)
                (format "REGRESSION: recursive per-page median %.2fms exceeds %dms threshold"
                        per-page per-page-threshold-ms))))

        (testing "pagination set correctness"
          (let [all-results (loop [cursor nil
                                   acc    []]
                              (let [{:keys [data cursor]} (eacl/lookup-resources acl (cond-> base-query
                                                                                       cursor (assoc :cursor cursor)))
                                    acc' (into acc data)]
                                (if (and cursor (seq data))
                                  (recur cursor acc')
                                  acc')))]
            (is (= chain-depth (count all-results)))
            (is (= chain-depth (count (distinct (map :id all-results)))))))))))

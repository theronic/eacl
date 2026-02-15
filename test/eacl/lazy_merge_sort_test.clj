(ns eacl.lazy-merge-sort-test
  "Correctness tests for lazy merge sort + dedup algorithms.
   Tests the production function against a known-correct oracle (Gen 1 linear scan)."
  (:require [clojure.test :as t :refer [deftest testing is]]
            [eacl.lazy-merge-sort :as lazy-sort]
            [eacl.legacy.lazy-merge-sort :as legacy]))

;; ========== Test Data Generators ==========

(defn gen-sorted-dedupe-longs
  "Generate a sorted, deduplicated sequence of n longs starting near base."
  [base n step-mean]
  (let [rng (java.util.Random. (+ base n))]
    (->> (iterate (fn [v] (+ v 1 (.nextInt rng (* 2 step-mean)))) base)
         (take n)
         vec)))

(defn gen-sorted-seqs
  "Generate k sorted, deduplicated long sequences with controlled overlap.
   dup-rate 0.0 = disjoint, 1.0 = identical sequences."
  [k total-n dup-rate]
  (let [base 17592186000000
        per-seq (max 1 (long (/ total-n k)))
        shared-count (long (* per-seq dup-rate))
        shared (gen-sorted-dedupe-longs base shared-count 3)]
    (vec (for [i (range k)]
           (let [unique-base (+ base (* 10000000 (inc i)))
                 unique (gen-sorted-dedupe-longs unique-base (- per-seq shared-count) 3)]
             (vec (sort (distinct (concat shared unique)))))))))

(defn expected-merge
  "Compute expected result: sorted distinct union of all sequences."
  [seqs]
  (vec (sort (distinct (apply concat seqs)))))

;; ========== Oracle Comparison Tests ==========

(deftest oracle-comparison-basic
  (testing "New algorithm matches legacy oracle on basic inputs"
    (let [seqs [[1 3 5 7 9]
                [2 4 6 8 10]
                [1 2 3 4 5]]]
      (is (= (expected-merge seqs)
             (vec (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs)))
          "fold2-dedupe matches expected"))))

(deftest oracle-comparison-identity-keyfn
  (testing "identity keyfn: fold2-dedupe matches sorted-distinct oracle"
    (doseq [k [2 4 8 16]
            total-n [100 1000 10000]
            dup-rate [0.0 0.5 0.9]]
      (let [seqs (gen-sorted-seqs k total-n dup-rate)
            expected (expected-merge seqs)
            actual (vec (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs))]
        (is (= expected actual)
            (format "k=%d, n=%d, dup=%.0f%%" k total-n (* 100 dup-rate)))))))

(deftest oracle-comparison-legacy-match
  (testing "New algorithm output exactly matches legacy Gen 1 output"
    (doseq [k [2 4 8]
            total-n [100 1000 5000]]
      (let [seqs (gen-sorted-seqs k total-n 0.5)
            legacy-result (vec (legacy/lazy-merge-dedupe-sort seqs))
            new-result (vec (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs))]
        (is (= legacy-result new-result)
            (format "Legacy match: k=%d, n=%d" k total-n))))))

;; ========== Property Tests ==========

(deftest output-is-sorted
  (testing "Output is strictly ascending"
    (doseq [k [2 4 8 16]]
      (let [seqs (gen-sorted-seqs k 10000 0.5)
            result (vec (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs))]
        (is (= result (sort result))
            (format "Sorted: k=%d" k))
        (is (= result (vec (distinct result)))
            (format "Distinct: k=%d" k))))))

(deftest output-no-consecutive-duplicates
  (testing "No consecutive duplicate elements"
    (let [seqs (gen-sorted-seqs 8 10000 0.9)
          result (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs)]
      (is (= (count result) (count (dedupe result)))
          "No consecutive dupes"))))

(deftest output-strictly-ascending
  (testing "Every element is strictly greater than the previous"
    (let [seqs (gen-sorted-seqs 8 10000 0.5)
          result (vec (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs))]
      (is (every? true? (map < result (rest result)))
          "Strictly ascending"))))

(deftest output-contains-all-unique-elements
  (testing "Output contains exactly the union of all input elements"
    (let [seqs [[1 5 9 13]
                [2 5 10 13]
                [3 5 11 13]
                [4 5 12 13]]
          result (vec (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs))]
      (is (= [1 2 3 4 5 9 10 11 12 13] result)))))

;; ========== Edge Cases ==========

(deftest edge-case-empty-sequences
  (testing "Empty sequences are handled"
    (is (= [] (vec (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity []))))
    (is (= [] (vec (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity [[] []]))))
    (is (= [1 2 3] (vec (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity [[] [1 2 3] []]))))))

(deftest edge-case-single-sequence
  (testing "Single sequence returned as-is"
    (is (= [1 2 3] (vec (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity [[1 2 3]]))))))

(deftest edge-case-all-duplicates
  (testing "All sequences contain same elements"
    (let [seqs (repeat 5 [1 2 3 4 5])
          result (vec (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs))]
      (is (= [1 2 3 4 5] result)))))

(deftest edge-case-single-element-sequences
  (testing "Many single-element sequences"
    (let [seqs (map vector (shuffle (range 100)))
          result (vec (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs))]
      (is (= (vec (range 100)) result)))))

(deftest edge-case-datomic-scale-longs
  (testing "Works with Datomic-scale entity IDs"
    (let [base 17592186045500
          seqs [(range base (+ base 100) 2)
                (range (inc base) (+ base 100) 2)
                (range base (+ base 100) 3)]
          result (vec (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs))]
      (is (= result (sort (distinct (apply concat seqs))))))))

;; ========== Laziness Tests ==========

(deftest laziness-does-not-realize-all
  (testing "take N does not realize the entire input"
    (let [!realized-count (atom 0)
          make-tracking-seq (fn [s]
                              (map (fn [x]
                                     (swap! !realized-count inc)
                                     x) s))
          seqs (mapv make-tracking-seq
                     [(range 0 100000 2)
                      (range 1 100000 2)])
          _ (doall (take 50 (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs)))]
      ;; Should realize roughly ~50 elements per seq, not all 100k
      ;; Allow generous margin for chunked seqs and merge lookahead
      (is (< @!realized-count 500)
          (format "Realized %d elements (expected <500 for take 50)" @!realized-count)))))

;; ========== tracking-min Compatibility ==========

(deftest tracking-min-volatile-fires
  (testing "tracking-min side-channel works through merge"
    (let [!min (volatile! nil)
          tracking-seq (fn [v coll]
                         (map (fn [x]
                                (vswap! !min (fn [cur] (if cur (min cur v) v)))
                                x)
                           coll))
          seqs [(tracking-seq 100 [1 5 9])
                (tracking-seq 200 [2 6 10])
                (tracking-seq 300 [3 7 11])]
          result (doall (lazy-sort/lazy-fold2-merge-dedupe-sorted-by identity seqs))]
      (is (= [1 2 3 5 6 7 9 10 11] result))
      (is (= 100 @!min) "Volatile should track minimum contributor"))))

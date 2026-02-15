(ns eacl.lazy-merge-sort-bench
  "Benchmark suite for lazy merge sort + dedup algorithms.
   Run via nREPL: (require 'eacl.lazy-merge-sort-bench :reload) (run-benchmarks!)"
  (:require [criterium.core :as crit]
            [eacl.lazy-merge-sort :as lazy-sort]))

(defn gen-sorted-dedupe-longs
  "Generate a sorted, deduplicated sequence of n longs starting near base."
  [base n step-mean]
  (let [rng (java.util.Random. (+ base n))]
    (->> (iterate (fn [v] (+ v 1 (.nextInt rng (* 2 step-mean)))) base)
         (take n)
         vec)))

(defn gen-sorted-seqs
  "Generate k sorted, deduplicated long sequences with controlled overlap."
  [k total-n dup-rate]
  (let [base 17592186000000
        per-seq (max 1 (long (/ total-n k)))
        shared-count (long (* per-seq dup-rate))
        shared (gen-sorted-dedupe-longs base shared-count 3)]
    (vec (for [i (range k)]
           (let [unique-base (+ base (* 10000000 (inc i)))
                 unique (gen-sorted-dedupe-longs unique-base (- per-seq shared-count) 3)]
             (vec (sort (distinct (concat shared unique)))))))))

(defn bench-algo
  "Benchmark a merge algorithm. Returns mean execution time in microseconds."
  [algo-fn seqs page-size]
  (let [result (crit/quick-benchmark*
                 (fn [] (doall (take page-size (algo-fn identity seqs))))
                 {})]
    {:mean-us (* 1e6 (first (:mean result)))
     :std-us (* 1e6 (first (:variance result)))}))

(def benchmark-configs
  "EACL-realistic benchmark configurations."
  [{:k 2   :total-n 100000  :page-size 500  :dup-rate 0.5}
   {:k 4   :total-n 100000  :page-size 500  :dup-rate 0.5}
   {:k 8   :total-n 100000  :page-size 500  :dup-rate 0.5}
   {:k 16  :total-n 100000  :page-size 500  :dup-rate 0.5}
   {:k 8   :total-n 100000  :page-size 50   :dup-rate 0.5}
   {:k 8   :total-n 100000  :page-size 1000 :dup-rate 0.5}
   {:k 8   :total-n 800000  :page-size 1000 :dup-rate 0.5}
   {:k 8   :total-n 100000  :page-size 500  :dup-rate 0.0}
   {:k 8   :total-n 100000  :page-size 500  :dup-rate 0.9}])

(defn run-benchmarks!
  "Run all benchmarks with the given algorithm map.
   algos is a map of {name algo-fn} where algo-fn has same signature as
   lazy-fold2-merge-dedupe-sorted-by."
  ([] (run-benchmarks! {"current" lazy-sort/lazy-fold2-merge-dedupe-sorted-by}))
  ([algos]
   (println "\n====== Lazy Merge Sort Benchmarks ======\n")
   (doseq [{:keys [k total-n page-size dup-rate]} benchmark-configs]
     (let [seqs (gen-sorted-seqs k total-n dup-rate)]
       (printf "k=%-2d  n=%-6d  page=%-4d  dup=%.0f%%\n" k total-n page-size (* 100 dup-rate))
       (doseq [[algo-name algo-fn] (sort-by key algos)]
         (let [{:keys [mean-us]} (bench-algo algo-fn seqs page-size)]
           (printf "  %-20s %8.1f µs\n" algo-name mean-us)))
       (println)))
   (println "====== Done ======\n")))

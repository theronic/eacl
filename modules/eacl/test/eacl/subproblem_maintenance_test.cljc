(ns eacl.subproblem-maintenance-test
  "Deterministic op-count invariants for cache maintenance complexity.

  Pins the amortized-LRU behavior landed in commits 96eb944/dfd3ad5 so a
  regression back to O(n)-per-touch maintenance fails deterministically
  in the per-push suite, without wall-clock benchmarks (the class of
  defect previously caught only by a wall-ratio bench, and still present
  in sibling caches — see recursion-performance-gates spec):

  - LRU record retention is bounded by max(1024, 2 x entries) — the
    exact bound maybe-compact-lru enforces; touch appends at most one
    record per hit, so retained records <= compaction bound at all
    times.
  - Eviction probes are bounded by evictions plus consumed stale
    records: each probe either selects a victim or permanently consumes
    one appended record (lru-head only advances)."
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.subproblem-cache :as sub]))

(defn- tier-stats [store tier]
  (get-in (sub/stats store) [:tiers tier]))

(defn- resolve-key!
  [store tier k weight]
  (:value (sub/resolve! store tier k
                        {:weight-fn (constantly weight)}
                        (constantly (vec (range 4))))))

(deftest lru-record-retention-bound-test
  (testing "10k repeated hits on a small entry set keep lru-records <= max(1024, 2*entries)"
    (let [store (sub/store {:projection-max-weight (* 64 1024)})]
      ;; Install 16 entries, then hammer hits across them.
      (dotimes [i 16]
        (resolve-key! store :projection [:probe i] 64))
      (dotimes [n 10000]
        (resolve-key! store :projection [:probe (mod n 16)] 64))
      (let [{:keys [entries lru-records]} (tier-stats store :projection)]
        (is (= 16 entries))
        (is (<= lru-records (max 1024 (* 2 entries)))
            (str "retained lru records " lru-records
                 " exceed the compaction bound for " entries " entries"))))))

(deftest eviction-probe-bound-test
  (testing "probes <= evictions + total appended records (each probe evicts or consumes a stale record)"
    (let [store (sub/store {:projection-max-weight 1024})
          ;; Weight 128 => at most 8 resident entries; a stream of distinct
          ;; keys forces continual eviction with interleaved re-touches.
          touches (atom 0)]
      (dotimes [n 400]
        (resolve-key! store :projection [:churn n] 128)
        (swap! touches inc)
        (when (pos? n)
          ;; Re-touch a recent key so stale records accumulate.
          (resolve-key! store :projection [:churn (dec n)] 128)
          (swap! touches inc)))
      (let [{:keys [eviction-probes evictions]} (sub/stats store)]
        (is (pos? evictions) "fixture must actually evict")
        (is (<= eviction-probes (+ evictions @touches))
            (str "eviction probes " eviction-probes
                 " exceed evictions " evictions
                 " + appended records " @touches))))))

(deftest hit-cost-is-constant-count-test
  (testing "a hit performs O(1) map operations: no tier scan, no record rebuild"
    ;; Structural proxy: after compaction stabilizes, hitting one key
    ;; repeatedly grows lru-records by exactly one per hit until the
    ;; next compaction, never proportionally to entry count.
    (let [store (sub/store {:projection-max-weight (* 64 1024)})]
      (dotimes [i 64]
        (resolve-key! store :projection [:stable i] 64))
      (let [before (:lru-records (tier-stats store :projection))]
        (dotimes [_ 10]
          (resolve-key! store :projection [:stable 0] 64))
        (let [after (:lru-records (tier-stats store :projection))]
          (is (<= (- after before) 10)
              "each hit appends at most one recency record"))))))

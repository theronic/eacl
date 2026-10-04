(ns eacl.subproblem-maintenance-test
  "Maintenance invariants now delegated to the standard LRU implementation."
  (:require [clojure.test :refer [deftest is]]
            [eacl.cache.key :as cache-key]
            [eacl.subproblem-cache :as sub]))

(defn- storage-key
  [semantic]
  (cache-key/exact-denotation-key
   {:tier :denotation
    :source-lifecycle #uuid "124ca28a-c246-58d5-9ac4-fa200f0e9159"
    :abi :test-authorization-v2
    :semantic semantic
    :reuse [:basis 1]}))

(deftest resident-count-never-exceeds-standard-lru-capacity-test
  (let [capacity 8
        store (sub/store {:denotation-max-entries capacity})]
    (dotimes [n 400]
      (sub/publish! store :denotation (storage-key [:churn n])
                    {:valid? vector?}
                    (vec (range 4)))
      (when (pos? n)
        (sub/lookup!
         store :denotation (storage-key [:churn (dec n)]))))
    (is (= {:entries capacity :max-entries capacity}
           (get-in (sub/stats store) [:tiers :denotation])))))

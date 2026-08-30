(ns eacl.subproblem-maintenance-test
  "Maintenance invariants now delegated to the standard LRU implementation."
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.cache.key :as cache-key]
            [eacl.subproblem-cache :as sub]))

(defn- storage-key
  [semantic]
  (cache-key/exact-denotation-key
   {:tier :denotation
    :source-lifecycle {:source :test :lifecycle :maintenance}
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

(deftest no-homegrown-policy-state-is-exposed-test
  (let [store (sub/store {:denotation-max-entries 2})
        tier-stats (get-in (sub/stats store) [:tiers :denotation])]
    (testing "capacity is an entry count, not a logical byte estimate"
      (is (= #{:entries :max-entries} (set (keys tier-stats))))
      (is (nil? (:weight tier-stats)))
      (is (nil? (:lru-records tier-stats)))
      (is (nil? (:eviction-probes (sub/stats store)))))))

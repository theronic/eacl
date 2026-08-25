(ns eacl.operator-engine.experiments-test
  (:require [clojure.test :refer [deftest is]]
            [eacl.operator-engine.experiments :as experiments]))

(deftest deterministic-operator-experiments-self-check-test
  (let [summary
        (experiments/run-deterministic
         {:cover-trials 2000
          :anchor-trials 500
          :recursive-anchor-trials 250})]
    (is (true? (get-in summary [:cover :passed?])))
    (is (zero? (get-in summary [:anchor-gated :result-failures])))
    (is (< (get-in summary [:anchor-gated :anchor-gated-states])
           (get-in summary [:anchor-gated :any-child-states])))
    (is (zero? (get-in summary
                       [:recursive-anchor-gated :result-failures])))
    (is (zero? (get-in summary
                       [:recursive-anchor-gated
                        :arrival-order-failures])))
    (is (zero? (get-in summary
                       [:recursive-anchor-gated
                        :retained-state-failures])))
    (is (< (get-in summary
                   [:recursive-anchor-gated :anchor-parent-slots])
           (get-in summary
                   [:recursive-anchor-gated :any-child-parent-slots])))
    (is (= 21 (get-in summary
                      [:adaptive-batching :all-accepted :adaptive
                       :physical-candidates])))
    (is (= 256 (get-in summary
                       [:adaptive-batching :all-accepted :fixed-width
                        :physical-candidates])))
    (is (= (range 0 21000 1000)
           (get-in summary [:leapfrog :leapfrog :results])))
    (is (zero? (get-in summary
                       [:k-way-leapfrog :random-result-failures])))
    (is (zero? (get-in summary
                       [:k-way-leapfrog :random-bound-failures])))
    (is (= {:anchor-rounds 0
            :driver-lower-bound-seeks 0
            :operand-lower-bound-seeks 0
            :lower-bound-comparisons 0
            :accepted 0}
           (get-in summary [:k-way-leapfrog :zero-demand])))
    (is (= {:anchor-rounds 1
            :driver-lower-bound-seeks 0
            :operand-lower-bound-seeks 1
            :lower-bound-comparisons 1
            :accepted 0}
           (get-in summary [:k-way-leapfrog :early-exhaustion])))
    (is (true? (get-in summary
                       [:k-way-leapfrog :adversarial :equal-results?])))
    (is (< (get-in summary
                   [:k-way-leapfrog :adversarial :k-way
                    :anchor-rounds])
           (get-in summary
                   [:k-way-leapfrog :adversarial :sequential-binary
                    :head-comparisons])))
    (is (= 21 (get-in summary
                      [:k-way-leapfrog :adversarial :bounded-k-way
                       :accepted])))
    (is (= (range 0 21000 1000)
           (get-in summary
                   [:k-way-leapfrog :adversarial :bounded-k-way
                    :results])))
    (is (= 20000 (get-in summary [:memoization :saved-leaf-probes])))))

(ns eacl.formal.differential-runner-test
  (:require
   [#?(:clj clojure.test :cljs cljs.test)
    :refer [deftest is]]
   [eacl.formal.differential-runner :as differential]))

(deftest portable-differential-runner-test
  (let [passed
        (differential/compare-values!
         {:seed 820084
          :case-id :same
          :values [[:formal #{1 2}]
                   [:generated #{2 1}]
                   [:legacy #{1 2}]]})
        failed
        (differential/run-case
         {:seed 820085
          :case-id :different
          :implementations
          [[:formal (constantly true)]
           [:legacy (constantly false)]]})]
    (is (= :passed (:status passed)))
    (is (= :failed (:status failed)))
    (is (= 820085 (:seed failed)))
    (is (string? (:case-digest failed)))))

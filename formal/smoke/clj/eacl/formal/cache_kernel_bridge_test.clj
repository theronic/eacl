(ns eacl.formal.cache-kernel-bridge-test
  (:require
   [clojure.test :refer [deftest is]]
   [eacl.formal.cache-kernel-bridge :as formal]))

(deftest generated-cache-decision-matrix
  (is (= {:status :hit
          :value 42
          :provenance :exact-hit}
         (formal/validate {})))
  (is (= :causal-proof-lift
         (:provenance
          (formal/validate
           {:selected-graph 8
            :ancestors #{7}}))))
  (is (= :no-proof-bypass
         (:reason
          (formal/validate
           {:selected-proof nil}))))
  (is (= :no-proof-bypass
         (:reason
          (formal/validate
           {:deterministic? false}))))
  (is (= :provider-failure
         (:reason
          (formal/validate
           {:entry {:status :provider-failure}}))))
  (is (= :unauthenticated
         (:reason
          (formal/validate
           {:entry {:authenticated? false}}))))
  (is (= :scope-mismatch
         (:reason
          (formal/validate
           {:entry {:source "other"}}))))
  (is (= :future-or-sibling
         (:reason
          (formal/validate
           {:selected-graph 8
            :ancestors #{6}
            :entry {:graph 7}}))))
  (is (= :proof-mismatch
         (:reason
          (formal/validate
           {:entry {:proof "other"}})))))

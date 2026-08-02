(ns eacl.verified-kernel-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eacl.verified-kernel :as verified]))

(def valid-cache-input
  {:deterministic? true
   :dependency-scope-nonempty? true
   :expected-key "key"
   :expected-source "source"
   :selected-graph 0
   :ancestors #{1}
   :selected-proof "proof"
   :entry {:status :candidate
           :authenticated? true
           :key "key"
           :source "source"
           :graph 1
           :proof "proof"}})

(defrecord FunctionKernel [f]
  verified/DecisionKernel
  (-decide [_ operation input]
    (f operation input)))

(deftest strict-boundary-rejects-unknown-and-unsafe-values
  (let [kernel
        (->FunctionKernel
         (fn [_ _]
           {:status :hit :provenance :exact-hit}))]
    (doseq [input
            [(assoc valid-cache-input :unknown true)
             (assoc valid-cache-input
                    :selected-graph 9007199254740992)
             (assoc-in valid-cache-input [:entry :proof] 7)]]
      (is (thrown-with-msg?
           #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo)
           #"boundary"
           (verified/decide
            {:mode :verified-authoritative :kernel kernel}
            :cache-validation
            input
            #(throw (ex-info "legacy must not run" {})))))))
  (let [kernel
        (->FunctionKernel
         (fn [_ _]
           {:status :hit
            :provenance :exact-hit
            :unknown true}))]
    (is (thrown?
         #?(:clj clojure.lang.ExceptionInfo
            :cljs cljs.core.ExceptionInfo)
         (verified/decide
          {:mode :verified-authoritative :kernel kernel}
          :cache-validation
          valid-cache-input
          #(throw (ex-info "legacy must not run" {})))))))

(deftest authoritative-kernel-controls-the-result
  (let [kernel
        (->FunctionKernel
         (fn [_ _]
           {:status :miss :reason :proof-mismatch}))]
    (is (= {:status :miss :reason :proof-mismatch}
           (verified/decide
            {:mode :verified-authoritative :kernel kernel}
            :cache-validation
            valid-cache-input
            (constantly
             {:status :hit :provenance :exact-hit}))))))

(deftest shadow-never-alters-legacy-result
  (testing "disagreement"
    (let [diagnostics (atom [])
          legacy {:status :hit :provenance :exact-hit}
          kernel
          (->FunctionKernel
           (fn [_ _]
             {:status :miss :reason :proof-mismatch}))]
      (is (= legacy
             (verified/decide
              {:mode :verified-shadow
               :kernel kernel
               :report-divergence #(swap! diagnostics conj %)}
              :cache-validation
              valid-cache-input
              (constantly legacy))))
      (is (= :eacl.verification/shadow-divergence
             (:type (first @diagnostics))))
      (is (string? (:input-digest (first @diagnostics))))))
  (testing "kernel failure"
    (let [diagnostics (atom [])
          legacy {:status :miss :reason :missing}
          kernel
          (->FunctionKernel
           (fn [_ _]
             (throw (ex-info "broken provider" {}))))]
      (is (= legacy
             (verified/decide
              {:mode :verified-shadow
               :kernel kernel
               :report-divergence #(swap! diagnostics conj %)}
              :cache-validation
              valid-cache-input
              (constantly legacy))))
      (is (= :eacl.verification/shadow-kernel-failure
             (:type (first @diagnostics)))))))

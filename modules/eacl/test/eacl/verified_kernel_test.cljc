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

(def valid-authorization-input
  {:objects [{:type "user" :id "u1"}
             {:type "document" :id "d1"}]
   :schema
   {:relations [{:resource-type "document"
                 :relation "viewer"
                 :subject-type "user"}]
    :permissions [{:resource-type "document"
                   :permission "view"}]
    :definitions [{:kind :direct-relation
                   :resource-type "document"
                   :permission "view"
                   :relation "viewer"
                   :subject-type "user"}]}
   :relationships
   [{:subject {:type "user" :id "u1"}
     :relation "viewer"
     :resource {:type "document" :id "d1"}}]
   :request {:operation :can?
             :subject {:type "user" :id "u1"}
             :permission "view"
             :resource {:type "document" :id "d1"}}
   :limits {:max-derived-grants 100
            :max-advanced-datoms 100
            :max-queued-work 100}})

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

(deftest keyset-page-boundary-enforces-one-row-lookahead
  (let [input {:direction :asc
               :size 20
               :bound? false
               :realized-count 21}
        result {:take-count 20
                :reverse? false
                :has-next? true
                :has-previous? false}
        kernel (->FunctionKernel (fn [_ _] result))
        decide
        #(verified/decide
          {:mode :verified-authoritative :kernel kernel}
          :relationship-keyset-page
          %
          (constantly nil))]
    (is (= result (decide input)))
    (doseq [invalid
            [(assoc input :unknown true)
             (assoc input :size 0)
             (assoc input :realized-count 22)]]
      (is (thrown?
           #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo)
           (decide invalid))))
    (let [oversized-result-kernel
          (->FunctionKernel
           (fn [_ _]
             (assoc result :take-count 21)))]
      (is (thrown?
           #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo)
           (verified/decide
            {:mode :verified-authoritative
             :kernel oversized-result-kernel}
            :relationship-keyset-page
            input
            (constantly nil)))))))

(deftest full-authorization-boundary-is-strict
  (let [result {:status :complete
                :operation :can?
                :allowed? true
                :counters {:derived-grants 1
                           :advanced-datoms 1
                           :queued-work 1}}
        kernel (->FunctionKernel (fn [_ _] result))
        decide
        #(verified/decide
          {:mode :verified-authoritative :kernel kernel}
          :authorization-evaluation
          % (constantly nil))]
    (is (= result (decide valid-authorization-input)))
    (doseq [invalid
            [(assoc valid-authorization-input :unknown true)
             (assoc-in valid-authorization-input
                       [:objects 0 :type] "")
             (update-in valid-authorization-input
                        [:schema :definitions 0]
                        assoc :unknown true)
             (assoc-in valid-authorization-input
                       [:request :permission] :view)
             (assoc-in valid-authorization-input
                       [:objects 0 :id]
                       (apply str (repeat 65537 "x")))
             (assoc-in valid-authorization-input
                       [:limits :max-derived-grants] 0)]]
      (is (thrown?
           #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo)
           (decide invalid))))
    (let [invalid-result-kernel
          (->FunctionKernel
           (fn [_ _]
             (assoc result :raw-object {:type "user" :id "u1"})))]
      (is (thrown?
           #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo)
           (verified/decide
            {:mode :verified-authoritative
             :kernel invalid-result-kernel}
            :authorization-evaluation
            valid-authorization-input
            (constantly nil)))))))

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
      (is (= [:provenance :reason :status]
             (:changed-fields (first @diagnostics))))
      (is (= {:status :hit :provenance :exact-hit}
             (:legacy-variant (first @diagnostics))))
      (is (= {:status :miss :reason :proof-mismatch}
             (:verified-variant (first @diagnostics))))
      (is (nil? (:input-digest (first @diagnostics))))
      (is (nil? (:legacy (first @diagnostics))))
      (is (nil? (:verified (first @diagnostics))))))
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

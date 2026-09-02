(ns eacl.execution-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.execution :as execution]))

(defn- caught-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(deftest execution-contract-normalization-test
  (let [clock (atom 1000000000)
        options {:execution-timeout-ms 250
                 :recursive-traversal-limits {:max-derived-grants 99}}
        contract
        (binding [execution/*monotonic-nanos* #(deref clock)]
          (execution/normalize
           options
           :count-resources
           {:count-limit 1000}))]
    (is (= :count-resources (:operation contract)))
    (is (= :demand (:evaluation contract)))
    (is (= {:kind :count :limit 1000 :sentinel 1001}
           (:demand contract)))
    (is (= 1250000000 (:deadline-nanos contract)))
    (is (= (:recursive-traversal-limits options) (:limits contract)))
    (is (not (contains? contract :cache-attempt)))))

(deftest complete-denotation-contract-test
  (let [contract
        (binding [execution/*monotonic-nanos* (constantly 10)]
          (execution/normalize
           {:execution-timeout-ms 100}
           :can?
           {:evaluation :complete-denotation :timeout-ms 25}))]
    (is (= :complete-denotation (:evaluation contract)))
    (is (= {:kind :complete-denotation :render :boolean}
           (:demand contract)))
    (is (= 25000010 (:deadline-nanos contract)))))

(deftest invalid-controls-fail-during-normalization-test
  (doseq [[request key]
          [[{:evaluation :speculate} :evaluation]
           [{:timeout-ms 0} :timeout-ms]
           [{:timeout-ms -1} :timeout-ms]
           [{:timeout-ms 1.5} :timeout-ms]
           [{:cancellation-token (atom false)} :cancellation-token]
           [{:count-limit -1} :count-limit]]]
    (testing (pr-str request)
      (let [data
            (caught-data
             #(execution/normalize
               {:execution-timeout-ms 100}
               :count-resources
               request))]
        (is (= :eacl.execution/invalid-contract (:eacl/error data)))
        (is (= key (:key data)))))))

(deftest absolute-deadline-and-fake-clock-test
  (let [clock (atom 0)
        contract
        (binding [execution/*monotonic-nanos* #(deref clock)]
          (execution/normalize
           {:execution-timeout-ms 10}
           :can?
           {}))]
    (binding [execution/*monotonic-nanos* #(deref clock)]
      (reset! clock 9999999)
      (is (= contract (execution/check! contract :before-command)))
      (reset! clock 10000000)
      (let [data
            (caught-data
             #(execution/check!
               contract :before-command {:backend-commands 2}))]
        (is (= :eacl.execution/deadline-exceeded (:eacl/error data)))
        (is (= :can? (:operation data)))
        (is (= :before-command (:stage data)))
        (is (= {:backend-commands 2} (:consumed-work data)))))))

(deftest nested-contract-refinement-never-renews-the-request-budget-test
  (let [clock-calls (atom 0)
        token (execution/cancellation-token)
        options {:execution-timeout-ms 100
                 :aggregate-limits {:candidate-window 20}}
        outer
        (binding [execution/*monotonic-nanos*
                  #(do (swap! clock-calls inc) 1000)]
          (execution/normalize
           options :with-snapshot
           {:cancellation-token token
            :aggregate-limits {:candidate-window 10}}))
        nested
        (binding [execution/*monotonic-nanos*
                  #(throw (ex-info "deadline renewed" {}))]
          (execution/refine
           outer options :lookup-resources
           {:first 2
            :aggregate-limits {:candidate-window 3}}))
        cannot-loosen
        (execution/refine
         outer options :lookup-resources
         {:first 2
          :aggregate-limits {:candidate-window 15}})]
    (is (= 1 @clock-calls))
    (is (= (:started-nanos outer) (:started-nanos nested)))
    (is (= (:deadline-nanos outer) (:deadline-nanos nested)))
    (is (identical? token (:cancellation-token nested)))
    (is (= :lookup-resources (:operation nested)))
    (is (= {:kind :page :direction :forward :size 2 :bounded? true}
           (:demand nested)))
    (is (= 3 (get-in nested [:aggregate-limits :candidate-window])))
    (is (= 10
           (get-in cannot-loosen [:aggregate-limits :candidate-window])))
    (is (= :request-control-fixed
           (:reason
            (caught-data
             #(execution/refine
               outer options :lookup-resources
               {:first 2
                :cancellation-token
                (execution/cancellation-token)})))))))

(deftest cooperative-cancellation-token-test
  (let [clock (atom 0)
        token (execution/cancellation-token)
        contract
        (binding [execution/*monotonic-nanos* #(deref clock)]
          (execution/normalize
           {:execution-timeout-ms 100}
           :lookup-resources
           {:first 10 :cancellation-token token}))]
    (is (execution/cancellation-token? token))
    (is (false? (execution/cancelled? token)))
    (is (identical? token (:cancellation-token contract)))
    (binding [execution/*monotonic-nanos* #(deref clock)]
      (is (= contract (execution/check! contract :generated-quantum)))
      (is (true? (binding [execution/*contract* contract]
                   (execution/cache-stage-available?))))
      (is (true? (execution/cancel! token)))
      (is (true? (execution/cancel! token)) "cancellation is idempotent")
      (is (execution/cancelled? token))
      (let [data
            (caught-data
             #(execution/check!
               contract :adapter-response {:backend-commands 3}))]
        (is (= :eacl.execution/cancelled (:type data)))
        (is (= :eacl.execution/cancelled (:eacl/error data)))
        (is (= :lookup-resources (:operation data)))
        (is (= :adapter-response (:stage data)))
        (is (= {:backend-commands 3} (:consumed-work data)))
        (is (not (contains? data :cancellation-token))))
      (is (false? (execution/cache-stage-available? contract)))
      (is (false? (binding [execution/*contract* contract]
                    (execution/cache-stage-available?))))))
  (is (true? (binding [execution/*contract* nil]
               (execution/cache-stage-available?)))
      "standalone internal storage has no request eligibility to reject"))

(deftest deadline-remains-authoritative-when-both-controls-fire-test
  (let [clock (atom 0)
        token (execution/cancellation-token)
        contract
        (binding [execution/*monotonic-nanos* #(deref clock)]
          (execution/normalize
           {:execution-timeout-ms 1}
           :can?
           {:cancellation-token token}))]
    (execution/cancel! token)
    (reset! clock 1000000)
    (is (= :eacl.execution/deadline-exceeded
           (:type
            (binding [execution/*monotonic-nanos* #(deref clock)]
              (caught-data
               #(execution/check! contract :generated-quantum))))))))

(deftest cache-stage-is-available-until-the-authoritative-deadline-test
  (let [clock (atom 0)
        contract
        (binding [execution/*monotonic-nanos* #(deref clock)]
          (execution/normalize
           {:execution-timeout-ms 20}
           :can?
           {}))]
    (binding [execution/*monotonic-nanos* #(deref clock)]
      (is (true? (execution/cache-stage-available? contract)))
      (reset! clock 19999999)
      (is (true? (execution/cache-stage-available? contract)))
      (reset! clock 20000000)
      (is (false? (execution/cache-stage-available? contract))))))

(deftest public-operation-timeout-default-and-override-test
  (doseq [[operation request expected-demand]
          [[:can? {} {:kind :boolean}]
           [:lookup-resources {:first 10}
            {:kind :page :direction :forward :size 10 :bounded? true}]
           [:lookup-subjects {:last 5 :before "cursor"}
            {:kind :page :direction :backward :size 5 :bounded? true}]
           [:count-resources {:count-limit 100}
            {:kind :count :limit 100 :sentinel 101}]
           [:count-subjects {} {:kind :exact-count}]]]
    (let [defaulted
          (binding [execution/*monotonic-nanos* (constantly 0)]
            (execution/normalize
             {:execution-timeout-ms 321}
             operation
             request))
          overridden
          (binding [execution/*monotonic-nanos* (constantly 0)]
            (execution/normalize
             {:execution-timeout-ms 321}
             operation
             (assoc request :timeout-ms 123)))]
      (is (= expected-demand (:demand defaulted)) (name operation))
      (is (= 321 (:configured-timeout-ms defaulted)) (name operation))
      (is (= 123 (:configured-timeout-ms overridden)) (name operation)))))

(deftest removed-cache-attempt-is-not-request-contract-state-test
  (doseq [forbidden [{:cache-attempt {:maximum-atomic-attempts 100}}
                     {:recursive-traversal-limits
                      {:max-derived-grants 1000000}}]]
    (let [data
          (caught-data
           #(execution/normalize
             {:execution-timeout-ms 100}
             :can?
             forbidden))]
      (is (= :eacl.execution/invalid-contract (:eacl/error data)))
      (is (seq (:forbidden-keys data)))))
  (is (not
       (contains?
        (execution/normalize
         {:execution-timeout-ms 100
          :cache-attempt {:evaluation-reserve-ms 10}}
         :can?
         {})
        :cache-attempt))))

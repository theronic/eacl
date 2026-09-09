(ns eacl.authorization.result-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.result :as result]))

(deftest detailed-results-preserve-membership-and-residuals
  (doseq [end [nil 100]]
    (is (= {:allowed? true :permissionship :has-permission}
           (result/check-result (evidence/with-certificate true end true))))
    (is (= {:allowed? false :permissionship :no-permission}
           (result/check-result (evidence/with-certificate false end true))))
    (let [conditional (evidence/with-certificate
                        (evidence/conditional [:a] ["country" "age"]) end true)
          decision (result/check-result conditional)]
      (is (false? (:allowed? decision)))
      (is (= :conditional-permission (:permissionship decision)))
      (is (= ["age" "country"] (:missing-fields decision)))
      (is (= conditional (evidence/decode (:residual decision)))))))

(deftest detailed-results-preserve-authoritative-failure
  (let [value (evidence/fault :eacl.qualifier/invalid :missing-qualifier)
        data (try (result/check-result value) nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
                    (ex-data error)))]
    (is (= {:type :eacl.authorization/evaluation-failure
            :eacl/error :eacl.authorization/evaluation-failure
            :faults [[:eacl.qualifier/invalid :missing-qualifier]]}
           data))))

(deftest qualified-cache-values-retain-evidence-kind-and-reject-faults
  (doseq [value [true false (evidence/conditional [:a] ["flag"])]]
    (is (result/cache-value? (evidence/encode value))))
  (doseq [value [nil true false {} "malformed" (str (evidence/encode true) " ")
                 (evidence/encode (evidence/fault :invalid :qualifier))]]
    (is (false? (result/cache-value? value)))))

(deftest detailed-lookup-items-validate-the-whole-decision
  (let [object {:type :folder :id "folder" :relation nil}
        object? #(= object %)
        conditional (result/lookup-result object (evidence/conditional [:a] ["flag"]))
        definite (result/lookup-result object true)]
    (doseq [valid [conditional definite]]
      (is (result/lookup-result-valid? object? valid)))
    (doseq [invalid [nil true object
                     (assoc conditional :allowed? true)
                     (assoc conditional :missing-fields [])
                     (assoc conditional :residual (evidence/encode true))
                     (assoc conditional :permissionship :has-permission)
                     (assoc conditional :residual (str (:residual conditional) " "))
                     (assoc definite :residual (:residual conditional))
                     (assoc definite :permissionship :no-permission)
                     (assoc definite :object nil)
                     (result/lookup-result object false)]]
      (is (false? (result/lookup-result-valid? object? invalid))))))

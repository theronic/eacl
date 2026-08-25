(ns eacl.schema.expression-policy-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [eacl.schema.expression-limits :as limits]
            [eacl.schema.expression-policy :as policy]
            [eacl.schema.expression-resolver :as resolver]))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(deftest policy-identity-covers-every-default-test
  (is (= :eacl.permission-expression-policy/v1
         (:format policy/compatibility-value)))
  (is (= policy/schema-limits
         (:schema-limits policy/compatibility-value)))
  (is (= policy/per-permission-limits
         (:per-permission-limits policy/compatibility-value)))
  (is (= policy/aggregate-limits
         (:aggregate-limits policy/compatibility-value)))
  (is (= "-M2O117LH_9noJ7AMLb_ktCAQwn_-LDH7XQIfuUL3M4"
         policy/compatibility-digest)))

(deftest checked-in-policy-validates-complete-schema-test
  (let [result
        (resolver/validate-schema
          "definition user {}
           definition document {
             relation reader: user
             relation banned: user
             permission view = reader - banned
           }")]
    (is (= policy/compatibility-value (:expression-policy result)))
    (is (= policy/compatibility-digest
           (:expression-policy-digest result)))
    (is (= 1 (get-in result
                     [:aggregate-expression-metrics :permission-count])))))

(deftest exact-type-partition-and-aggregate-boundaries-test
  (let [maximum-types (:maximum-type-partitions
                        policy/per-permission-limits)]
    (is (= maximum-types
           (limits/check-dimension!
             :type-partition-count :maximum-type-partitions maximum-types
             policy/per-permission-limits)))
    (is (= :type-partition-count
           (:dimension
             (error-data
               #(limits/check-dimension!
                  :type-partition-count :maximum-type-partitions
                  (inc maximum-types) policy/per-permission-limits))))))
  (let [metadata
        (vec
          (repeat (:maximum-permissions policy/aggregate-limits)
                  {:source-metrics {:node-count 1}
                   :normalized-metrics {:node-count 1
                                        :child-slot-count 0
                                        :word-count 1
                                        :checkpoint-weight 32}
                   :encoded-byte-size 64}))]
    (is (= (:maximum-permissions policy/aggregate-limits)
           (:permission-count
             (limits/check-aggregate! metadata policy/aggregate-limits))))
    (is (= :permission-count
           (:dimension
             (error-data
               #(limits/check-aggregate!
                  (conj metadata (first metadata))
                  policy/aggregate-limits)))))))

(deftest unknown-policy-cannot-alter-admission-or-plan-identity-test
  (let [forged (assoc-in policy/compatibility-value
                         [:per-permission-limits :maximum-source-nodes]
                         513)
        data (error-data
               #(resolver/validate-schema "definition user {}" forged))]
    (is (= :eacl.schema/unsupported-expression-policy (:type data)))
    (is (= policy/compatibility-value (:expected data)))
    (is (= forged (:actual data)))))

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
  (is (not (contains? policy/compatibility-value :schema-limits)))
  (is (not (contains? policy/compatibility-value :per-permission-limits)))
  (is (not (contains? policy/compatibility-value :aggregate-limits)))
  (is (= policy/default-client-limits
         (policy/normalize-client-limits nil))))

(deftest checked-in-policy-validates-complete-schema-test
  (let [result
        (resolver/validate-schema
          "definition user {}
           definition document {
             relation reader: user
             relation banned: user
             permission view = reader - banned
           }")]
    (is (= policy/default-client-limits (:expression-limits result)))
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

(deftest client-local-limit-profile-is-checked-test
  (let [custom {:maximum-source-nodes 513}
        result (resolver/validate-schema "definition user {}" custom)]
    (is (= 513 (get-in result [:expression-limits :maximum-source-nodes])))
    (is (= (:maximum-source-depth policy/per-permission-limits)
           (get-in result [:expression-limits :maximum-source-depth])))
    (is (not (contains? result :expression-policy-digest))))
  (let [unknown (error-data #(policy/normalize-client-limits {:unknown 1}))
        excessive
        (error-data
         #(policy/normalize-client-limits
           {:maximum-source-depth
            (inc (:maximum-source-depth policy/hard-limit-ceilings))}))]
    (is (= :eacl/invalid-config (:type unknown)))
    (is (= [:unknown] (:unknown-keys unknown)))
    (is (= :eacl/invalid-config (:type excessive)))
    (is (= :maximum-source-depth (:limit excessive)))))

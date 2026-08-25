(ns eacl.schema.expression-limits-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.schema.expression :as expression]
            [eacl.schema.expression-limits :as limits]
            [eacl.secure-format :as secure]
            [eacl.spicedb.parser :as parser]))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(deftest source-metrics-and-exact-boundaries-test
  (let [source
        (parser/permission-expression->source-ast
          (parser/parse-permission-expression "(a + b + c) & d - e"))
        measured (limits/source-metrics source)]
    (is (= {:node-count 8 :maximum-depth 4 :direct-fan-in 3} measured))
    (is (= measured
           (limits/check-source!
             source
             {:maximum-source-nodes 8
              :maximum-source-depth 4
              :maximum-direct-fan-in 3})))
    (doseq [[limit value dimension]
            [[:maximum-source-nodes 7 :node-count]
             [:maximum-source-depth 3 :maximum-depth]
             [:maximum-direct-fan-in 2 :direct-fan-in]]]
      (is (= dimension
             (:dimension (error-data
                           #(limits/check-source! source {limit value}))))))))

(deftest normalized-dag-interns-and-canonicalizes-test
  (let [reader (expression/relation :reader [:user])
        grouped-reader (expression/relation :reader [:user] true)
        value
        (expression/expression
          :document :view
          (expression/intersection
            [(expression/union [reader grouped-reader])
             (expression/permission :active)
             (expression/permission :active)]))
        {:keys [dag metrics]} (limits/normalized-dag value)]
    (is (= :eacl.permission-expression-dag/v1 (:format dag)))
    (is (= 3 (:node-count metrics))
        "duplicate/group-only nodes collapse before interning")
    (is (= 2 (:child-slot-count metrics)))
    (is (pos? (:word-count metrics)))
    (is (= metrics
           (:metrics
             (limits/check-normalized!
               value
               {:maximum-normalized-nodes (:node-count metrics)
                :maximum-child-slots (:child-slot-count metrics)
                :maximum-words (:word-count metrics)
                :maximum-checkpoint-weight (:checkpoint-weight metrics)}))))
    (doseq [[limit dimension]
            [[:maximum-normalized-nodes :node-count]
             [:maximum-child-slots :child-slot-count]
             [:maximum-words :word-count]
             [:maximum-checkpoint-weight :checkpoint-weight]]]
      (is (= dimension
             (:dimension
               (error-data
                 #(limits/check-normalized!
                    value
                    {limit (dec (get metrics dimension))}))))))))

(deftest source-byte-limit-precedes-parse-tree-allocation-test
  (let [schema "definition user {}"]
    (is (vector? (parser/parse-schema schema
                   {:maximum-source-bytes (count schema)})))
    (is (= :source-bytes
           (:dimension
             (error-data
               #(parser/parse-schema schema
                  {:maximum-source-bytes (dec (count schema))}))))))
  (testing "UTF-8 bytes, not host character count, define the boundary"
    (let [schema "definition usér {}"
          bytes (count (secure/utf8-bytes schema))]
      (is (= :source-bytes
             (:dimension
               (error-data
                 #(parser/parse-schema schema
                    {:maximum-source-bytes (dec bytes)}))))))))

(ns eacl.schema.expression-limits-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [clojure.string :as str]
            [eacl.schema.expression :as expression]
            [eacl.schema.expression-limits :as limits]
            [eacl.schema.expression-policy :as policy]
            [eacl.schema.expression-resolver :as resolver]
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
    (is (= 3 (:child-slot-count metrics))
        "typed leaf partitions are budgeted as retained slots")
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

(deftest normalized-dag-order-and-grouping-laws-test
  (let [a (expression/relation :a [:user])
        b (expression/relation :b [:user])
        c (expression/relation :c [:user])
        left
        (expression/expression
          :document :view
          (expression/intersection
            [a (expression/intersection [b c] true)]))
        right
        (expression/expression
          :document :view
          (expression/intersection [c a b]))
        forward
        (expression/expression :document :view (expression/exclusion a b))
        reversed
        (expression/expression :document :view (expression/exclusion b a))]
    (is (= (limits/normalized-dag left)
           (limits/normalized-dag right)))
    (is (not= (limits/normalized-dag forward)
              (limits/normalized-dag reversed)))))

(deftest source-byte-limit-precedes-parse-tree-allocation-test
  (let [schema "definition user {}"]
    (is (vector? (parser/parse-schema schema
                   {:maximum-schema-source-bytes (count schema)})))
    (is (= :source-bytes
           (:dimension
             (error-data
               #(parser/parse-schema schema
                  {:maximum-schema-source-bytes (dec (count schema))}))))))
  (testing "UTF-8 bytes, not host character count, define the boundary"
    (let [schema "definition usér {}"
          bytes (count (secure/utf8-bytes schema))]
      (is (= :source-bytes
             (:dimension
               (error-data
                 #(parser/parse-schema schema
                    {:maximum-schema-source-bytes (dec bytes)}))))))))

(deftest encoded-byte-limit-precedes-codec-ceilings-test
  ;; A 12 KB schema whose one permission resolves to arrows over a 256-type
  ;; relation. Its canonical payload exceeds the codec's own 1 MiB size and
  ;; 262,144-entry ceilings, which used to escape as :eacl.format/invalid
  ;; before the default 131,072-byte comparison could run.
  (let [types (mapv #(str "t" %) (range 256))
        schema (fn [permission]
                 (str (str/join "\n" (map #(str "definition " % " {\n relation x: user\n}") types))
                      "\ndefinition user {}\ndefinition doc {\n relation r: " (str/join " | " types)
                      "\n permission p = " permission "\n}"))
        maximum (:maximum-expression-bytes policy/per-permission-limits)]
    (doseq [[label permission]
            [["beyond the codec size ceiling" (str/join " + " (repeat 128 "r->x"))]
             ["beyond the codec entry ceiling" (str/join " + " (repeat 128 "(r->x + r->x)"))]
             ["within the codec ceilings" (str/join " + " (repeat 9 "r->x"))]]]
      (testing label
        (let [data (error-data #(resolver/validate-schema (schema permission)))]
          (is (= {:type :eacl.schema/expression-limit
                  :dimension :encoded-byte-size
                  :maximum maximum}
                 (select-keys data [:type :dimension :maximum])))
          (is (and (integer? (:actual data)) (< maximum (:actual data)))))))
    (is (map? (resolver/validate-schema (schema (str/join " + " (repeat 8 "r->x")))))
        "an expression within the byte limit is admitted")))

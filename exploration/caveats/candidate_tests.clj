(ns eacl.exploration.caveats.candidate-tests
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [exoscale.cel.parser :as cel]
            [exoscale.cel.expr :as expr]))

(defn outcome [program bindings]
  (try
    (let [result (cel/eval-for program bindings {:translate-result? false})]
      (cond
        (expr/error? result) :error
        (expr/bool? result) (expr/val result)
        :else [:non-boolean (expr/typeof result)]))
    (catch Exception _ :error)))

(def integers [-9007199254740991 -100 -1 0 1 100 9007199254740991])
(def strings ["" "a" "abc" "b" "é" "π" "😀" "￿"])
(def comparisons {"==" = "!=" not= "<" < "<=" <= ">" > ">=" >=})

(deftest same-type-scalar-comparison
  (doseq [[operator expected] comparisons
          :let [program (cel/make-program (str "a " operator " b"))]
          a integers b integers]
    (is (= (expected a b) (outcome program {:a a :b b}))))
  (doseq [[operator expected] (select-keys comparisons ["==" "!="])
          :let [program (cel/make-program (str "a " operator " b"))]
          values [strings [false true]]
          a values b values]
    (is (= (expected a b) (outcome program {:a a :b b})))))

(deftest boolean-truth-tables
  (doseq [a [false true] b [false true] c [false true]
          [source expected]
          [["a && b" (and a b)]
           ["a || b" (or a b)]
           ["!(a && b)" (not (and a b))]
           ["a ? b : c" (if a b c)]
           ["a && (b || c)" (and a (or b c))]]]
    (is (= expected (outcome (cel/make-program source) {:a a :b b :c c}))))
  (doseq [[source expected]
          [["missing || true" true] ["true || missing" true]
           ["missing && false" false] ["false && missing" false]
           ["missing || false" :error] ["missing && true" :error]]]
    (is (= expected (outcome (cel/make-program source) {})))))

(deftest bounded-container-and-string-operations
  (doseq [values [integers strings]
          n (range 129)
          :let [xs (vec (take n (cycle (take 3 values))))]
          x values]
    (is (= (boolean (some #{x} xs))
           (outcome (cel/make-program "x in xs") {:x x :xs xs}))))
  (doseq [x strings
          m [{} {"a" true} {"a" false "😀" true}]]
    (is (= (contains? m x)
           (outcome (cel/make-program "x in m") {:x x :m m})))
    (is (= (if (contains? m x) (get m x) :error)
           (outcome (cel/make-program "m[x]") {:x x :m m}))))
  (doseq [[method expected] [["contains" str/includes?]
                           ["startsWith" str/starts-with?]
                           ["endsWith" str/ends-with?]]
          :let [program (cel/make-program (str "a." method "(b)"))]
          a strings b strings]
    (is (= (expected a b) (outcome program {:a a :b b})))))

(deftest timestamp-comparison
  (doseq [[operator expected] comparisons
          :let [program (cel/make-program (str "a " operator " b"))]
          a [-62135596800000 -1 0 1 253402300799999]
          b [-62135596800000 -1 0 1 253402300799999]]
    (is (= (expected a b)
           (outcome program {:a (expr/->TimestampType (java.sql.Timestamp. a))
                             :b (expr/->TimestampType (java.sql.Timestamp. b))})))))

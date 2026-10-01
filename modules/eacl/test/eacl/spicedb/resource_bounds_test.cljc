(ns eacl.spicedb.resource-bounds-test
  "Work bounds on SpiceDB's validation (`eacl.spicedb.validation`): partial
  expansion and `use typechecking` visit at most a fixed number of
  statements, and EACL's source limits run before SpiceDB's reference checks.
  The same vectors are in eacl-rust's `crates/eacl-schema/tests/resource_bounds.rs`."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.string :as str]
            [eacl.schema.expression-resolver :as resolver]))

(defn- outcome
  ([source] (outcome source nil))
  ([source limits]
   (try
     (resolver/validate-schema source limits)
     :accept
     (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
       (select-keys (ex-data e) [:type :dimension :maximum :actual-at-least :limit])))))

(defn- exceeded [dimension maximum at-least]
  {:type :eacl.schema/expression-limit
   :dimension dimension
   :maximum maximum
   :actual-at-least at-least
   :limit :maximum-schema-source-bytes})

(defn- doubling
  "`partial p_0 { <member> }` and `partial p_i { ...p_{i-1} ...p_{i-1} }`."
  [levels member]
  (str "use partial\npartial p_0 {\n" member "}\n"
       (str/join (for [i (range 1 levels)]
                   (str "partial p_" i " {\n ...p_" (dec i) "\n ...p_" (dec i) "\n}\n")))
       "definition user {}\n"))

(deftest doubling-partials-are-bounded-test
  (let [unused (doubling 40 " relation viewer: user\n")]
    (testing "unused partials are never expanded (SpiceDB copies all 2^39 members)"
      (is (= :accept (outcome unused))))
    (is (= (exceeded :partial-expansion 262144 262145)
           (outcome (str unused "definition doc {\n ...p_39\n}\n"))))
    (testing "empty partials expand to nothing, but every reference is still a visit"
      (is (= (exceeded :partial-expansion 262144 262145)
             (outcome (str (doubling 40 "") "definition doc {\n ...p_39\n}\n")))))))

(defn- shared-partial
  "A ten-relation partial used by `n` definitions: translating it visits 10
   statements, each definition 11 (its reference and the ten members)."
  [n]
  (str "use partial\npartial ppp {\n"
       (str/join (for [i (range 10)] (str " relation r_" i ": user\n")))
       "}\ndefinition user {}\n"
       (str/join (for [i (range n)] (str "definition d_" i " {\n ...ppp\n}\n")))))

(deftest expansion-budget-is-a-quarter-of-the-source-limit-test
  (let [tight {:maximum-schema-source-bytes 1000}]
    (testing "10 + 11 * 21 = 241 visits of 250"
      (is (<= (count (shared-partial 21)) 1000))
      (is (= :accept (outcome (shared-partial 21) tight))))
    (testing "10 + 11 * 22 = 252"
      (is (= (exceeded :partial-expansion 250 251) (outcome (shared-partial 22) tight))))))

(defn- annotated-chain
  "`use typechecking` over `p_0 = viewer`, `p_i = p_{i-1} + viewer`, each
   annotated: checking `p_i` visits `p_i`, `p_{i-1}`, ..., `p_0` and `viewer`."
  [n]
  (str "use typechecking\ndefinition user {}\ndefinition doc {\n relation viewer: user\n"
       " permission p_0: user = viewer\n"
       (str/join (for [i (range 1 n)] (str " permission p_" i ": user = p_" (dec i) " + viewer\n")))
       "}\n"))

(defn- typechecking-overflow
  "The running visit count when the annotation walks of `annotated-chain`
   first exceed `budget` (annotations are checked in permission-name order)."
  [n budget]
  (reduce (fn [used [_ visits]]
            (let [used (+ used visits)]
              (if (> used budget) (reduced [:exceeded used]) used)))
          0
          (sort (for [i (range n)] [(str "p_" i) (+ i 2)]))))

(deftest typechecking-visits-at-most-the-source-limit-test
  (testing "only a quadratic walk overflows a budget of the source limit"
    (doseq [n [10 60]
            :let [chain (annotated-chain n)]]
      (is (number? (typechecking-overflow n (count chain))))
      (is (= :accept (outcome chain {:maximum-schema-source-bytes (count chain)})))))
  (let [chain (annotated-chain 100)
        [_ at] (typechecking-overflow 100 (count chain))]
    (is (= (exceeded :typechecking (count chain) at)
           (outcome chain {:maximum-schema-source-bytes (count chain)})))))

(deftest source-limits-precede-reference-checks-test
  (testing "a long exclusion chain over an undefined name is a source limit,
            found before the reference check would list every leaf"
    (let [source (str "definition user {}\ndefinition doc {\n relation aaa: user\n permission ppp = "
                      (str/join " - " (repeat 20000 "zzz"))
                      "\n}\n")]
      (is (= {:type :eacl.schema/expression-limit :dimension :node-count :maximum 512}
             (select-keys (outcome source) [:type :dimension :maximum]))))))

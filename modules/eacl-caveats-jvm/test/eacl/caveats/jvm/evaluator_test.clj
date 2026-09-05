(ns eacl.caveats.jvm.evaluator-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [eacl.caveats.definition :as definition]
            [eacl.caveats.evaluator :as evaluator]
            [eacl.caveats.jvm :as jvm]
            [eacl.caveats.partial :as partial]
            [exoscale.cel.expr :as expr]
            [exoscale.cel.parser :as cel]))

(defn check [engine source parameters context bound]
  (evaluator/evaluate engine (definition/entity "check" parameters source) context bound))

(deftest qualified-corpus
  (let [engine (jvm/evaluator)
        corpus (edn/read-string (slurp (io/resource "eacl/caveats/corpus.edn")))]
    (doseq [{:keys [id source parameters context bound expected reject]} (:cases corpus)]
      (testing (name id)
        (if reject
          (is (= reject (try (definition/entity "check" parameters source) nil
                             (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))
          (is (= expected (check engine source parameters context bound))))))))

(deftest native-outcomes-are-never-host-truthiness
  (let [engine (jvm/evaluator)]
    (doseq [[result expected]
            [[(expr/error "no such key") {:outcome :error :reason :missing-map-key}]
             [(expr/error "no such overload") {:outcome :error :reason :unsupported-overload}]
             [(expr/error "unexpected") {:outcome :error :reason :evaluator-error}]
             [(expr/int 1) {:outcome :error :reason :non-boolean-result}]
             [(ex-info "truthy error" {}) {:outcome :error :reason :non-boolean-result}]
             [nil {:outcome :error :reason :non-boolean-result}]
             [(expr/bool false) {:outcome :false}]
             [(expr/bool true) {:outcome :true}]]]
      (with-redefs [cel/eval-for (fn [& _] result)]
        (is (= expected (check engine "a" {"a" :bool} {"a" true} nil)))))
    (with-redefs [cel/eval-for (fn [& _] (throw (IllegalArgumentException. "private detail")))]
      (is (= {:outcome :error :reason :evaluator-exception}
             (check engine "a" {"a" :bool} {"a" true} nil))))))

(deftest partial-context-never-invokes-native-library
  (let [engine (jvm/evaluator)]
    (with-redefs [cel/make-program (fn [& _] (throw (AssertionError. "native compile")))
                  cel/eval-for (fn [& _] (throw (AssertionError. "native eval")))]
      (is (= {:outcome :true} (check engine "a || b" {"a" :bool "b" :bool} {"a" true} nil)))
      (is (= {:outcome :conditional :missing-fields #{"b"} :residual [:param "b"]}
             (check engine "a && b" {"a" :bool "b" :bool} {"a" true} nil))))))

(deftest lowered-literals-and-canonical-values
  (let [engine (jvm/evaluator)]
    (doseq [s ["" "\\n" "\\u0061" "a\nb" "a\rb" "a\tb" "\\\\" "\"" "😀" "\u0000"]]
      (let [source (str "value == " (if (= s "\u0000") "\"\\u0000\"" (pr-str s)))]
        (is (= {:outcome :true} (check engine source {"value" :string} {"value" s} nil)))
        (is (= {:outcome :false} (check engine source {"value" :string} {"value" (str s "x")} nil)))))
    (doseq [t [-62135596800000 -1 0 1 253402300799999]]
      (is (= {:outcome :true}
             (check engine "t in ts && m[k] == t" {"t" :timestamp "ts" [:list :timestamp]
                                                   "m" [:map :string :timestamp] "k" :string}
                    {"t" [:timestamp t] "ts" [[:timestamp t]] "m" {"at" [:timestamp t]} "k" "at"} nil))))
    (is (= {:outcome :true} (check engine "i == 1" {"i" :int} {"i" 1N} nil)))
    (is (= {:outcome :error :reason :context-type}
           (check engine "a" {"a" :bool} {"a" "wrong"} {"a" true})))))

(deftest complete-engine-does-not-run-a-shadow-evaluator
  (with-redefs [partial/evaluate-prepared (fn [& _] (throw (AssertionError. "shadow evaluation")))]
    (is (= {:outcome :true} (check (jvm/evaluator) "a" {"a" :bool} {"a" true} nil)))))

(deftest complete-finite-differential
  (let [engine (jvm/evaluator)]
    (doseq [source ["a && b" "a || b" "!(a && b)" "a == b" "a != b"]
            a [false true] b [false true]
            :let [entity (definition/entity "bools" {"a" :bool "b" :bool} source)
                  {:keys [parameters plan]} (definition/decode-entity entity)]]
      (is (= (partial/evaluate parameters plan {"a" a "b" b} {})
             (evaluator/evaluate engine entity {"a" a "b" b} nil))))
    (doseq [source ["a < b" "a <= b" "a > b" "a >= b" "a == b" "a != b"]
            a [-9007199254740991 -1 0 1 9007199254740991]
            b [-9007199254740991 -1 0 1 9007199254740991]
            :let [entity (definition/entity "ints" {"a" :int "b" :int} source)
                  {:keys [parameters plan]} (definition/decode-entity entity)]]
      (is (= (partial/evaluate parameters plan {"a" a "b" b} {})
             (evaluator/evaluate engine entity {"a" a "b" b} nil))))))

(deftest capability-and-definition-identity
  (let [engine (jvm/evaluator)
        a (definition/entity "a" {"x" :bool} "x")
        b (definition/entity "a" {"x" :bool} "!x")]
    (is (= engine (evaluator/require-matching! engine evaluator/profile-fingerprint)))
    (is (= (evaluator/descriptor engine) (evaluator/descriptor (evaluator/default-evaluator))))
    (is (thrown? clojure.lang.ExceptionInfo (evaluator/require-matching! nil evaluator/profile-fingerprint)))
    (is (thrown? clojure.lang.ExceptionInfo (evaluator/require-matching! engine "another-profile")))
    (is (= {:outcome :true} (evaluator/evaluate engine a {"x" true} nil)))
    (is (= {:outcome :false} (evaluator/evaluate engine b {"x" true} nil)))
    (is (= {:outcome :true} (evaluator/evaluate engine (assoc a :db/id 456) {"x" true} nil)))
    (is (= 2 (:builds (jvm/cache-stats engine))))))

(deftest concurrent-native-program-is-binding-local
  (let [engine (jvm/evaluator)
        entity (definition/entity "shared" {"a" :int "b" :int} "a < b")
        jobs (mapv (fn [n] (future (evaluator/evaluate engine entity {"a" n "b" 12} {}))) (range 24))]
    (is (= (mapv #(hash-map :outcome (if (< % 12) :true :false)) (range 24))
           (mapv #(deref % 10000 :timeout) jobs)))
    (is (= 1 (:builds (jvm/cache-stats engine))))))

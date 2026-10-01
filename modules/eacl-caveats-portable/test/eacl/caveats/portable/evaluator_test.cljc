(ns eacl.caveats.portable.evaluator-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.string :as str]
            [eacl.caveats.definition :as definition]
            [eacl.caveats.evaluator :as caveat-evaluator]
            [eacl.caveats.portable :as portable]
            [eacl.caveats.portable.corpus :as corpus]
            [eacl.caveats.values :as values]))

(defn check [engine source parameters context bound]
  (caveat-evaluator/evaluate engine (definition/entity "check" parameters source) context bound))

(defn- rejection [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (:reason (ex-data e)))))

(deftest qualified-corpus
  (let [engine (portable/evaluator)]
    (is (some :reject corpus/cases))
    (is (some :expected corpus/cases))
    (doseq [{:keys [id source parameters context bound expected reject]} corpus/cases]
      (testing (name id)
        (if reject
          (is (= reject (rejection #(definition/entity "check" parameters source))))
          (is (= expected (check engine source parameters context bound))))))))

(def ^:private scalars
  {"flag" :bool "n" :int "limit" :int "text" :string "needle" :string
   "now" :timestamp "until" :timestamp "tags" [:list :string] "days" [:list :int]
   "times" [:list :timestamp] "m" [:map :string :bool] "counts" [:map :string :int]})

(def ^:private complete
  {"flag" false "n" 3 "limit" 5 "text" "a😀b" "needle" "😀"
   "now" [:timestamp 0] "until" [:timestamp 1] "tags" ["reader" "admin"] "days" [1 3 5]
   "times" [[:timestamp 0]] "m" {"on" true "off" false} "counts" {"a" 1}})

(deftest profile-operations-and-faults
  (let [engine (portable/evaluator)]
    (doseq [[source expected]
            [["flag || n < limit" {:outcome :true}]
             ["flag && n < limit" {:outcome :false}]
             ["!(flag) && !(!(flag))" {:outcome :false}]
             ["n <= 3 && n >= 3 && n != 4 && limit > n" {:outcome :true}]
             ["now < until && until >= now && now <= now && !(now > until)" {:outcome :true}]
             ["now == until" {:outcome :false}]
             ["now in times && !(until in times)" {:outcome :true}]
             ["\"admin\" in tags && 5 in days && !(2 in days)" {:outcome :true}]
             ["\"on\" in m && !(\"missing\" in m) && \"a\" in counts" {:outcome :true}]
             ["m.on && !(m[\"off\"]) && counts[\"a\"] == 1" {:outcome :true}]
             ["text.contains(needle) && text.startsWith(\"a\") && text.endsWith(\"b\")" {:outcome :true}]
             ["text.contains(\"\") && !(text.startsWith(needle))" {:outcome :true}]
             ["m.missing" {:outcome :error :reason :missing-map-key}]
             ["!(m[\"missing\"])" {:outcome :error :reason :missing-map-key}]
             ["m.missing == false" {:outcome :error :reason :missing-map-key}]
             ["counts.missing < 1" {:outcome :error :reason :missing-map-key}]
             ["counts.missing in days" {:outcome :error :reason :missing-map-key}]
             ["false && m.missing" {:outcome :false}]
             ["m.missing && false" {:outcome :false}]
             ["m.missing || true" {:outcome :true}]
             ["true && m.missing" {:outcome :error :reason :missing-map-key}]
             ["m.missing || m.absent" {:outcome :error :reason :missing-map-key}]]]
      (testing source
        (is (= expected (check engine source scalars complete nil)))))))

(deftest context-merge-and-residuals
  (let [engine (portable/evaluator)
        parameters {"a" :bool "b" :bool "m" [:map :string :bool] "k" :string}]
    (is (= {:outcome :true} (check engine "a" parameters nil {"a" true})))
    (is (= {:outcome :false} (check engine "a" parameters {"a" true} {"a" false})))
    (is (= {:outcome :error :reason :context-type}
           (check engine "a" parameters {"a" "wrong"} {"a" true})))
    (is (= {:outcome :error :reason :unknown-parameter}
           (check engine "a" parameters {"a" true "other" true} nil)))
    (is (= {:outcome :conditional :missing-fields #{"b"} :residual [:param "b"]}
           (check engine "a && b" parameters {"a" true} nil)))
    (is (= {:outcome :conditional :missing-fields #{"a" "b"}
            :residual [:or [:param "a"] [:param "b"]]}
           (check engine "a || b" parameters nil nil)))
    (is (= {:outcome :conditional :missing-fields #{"k"}
            :residual [:index [:literal [:map :string :bool] {"on" true}] [:param "k"]]}
           (check engine "m[k]" parameters {"m" {"on" true}} {})))
    (is (= {:outcome :error :reason :missing-map-key}
           (check engine "m[k] && a" parameters {"m" {} "k" "on"} {})))))

(deftest work-limit-is-preflighted-behind-absorbers
  (is (= {:outcome :error :reason :resource-limit}
         (check (portable/evaluator) "true || a.contains(b)" {"a" :string "b" :string}
                {"a" (apply str (repeat 2048 "x")) "b" (apply str (repeat 1024 "x"))} {}))))

(deftest host-values-evaluate-as-their-canonical-values
  (let [engine (portable/evaluator)
        folded (sorted-map-by #(compare (str/lower-case %1) (str/lower-case %2)) "A" true)
        parameters {"m" [:map :string :bool] "k" :string "unused" :bool}]
    ;; A comparator must not make "a" match the stored key "A".
    (is (= {:outcome :error :reason :missing-map-key}
           (check engine "m[k]" parameters {"m" folded "k" "a"} {})))
    (is (= {:outcome :false} (check engine "k in m" parameters {"m" folded "k" "a"} {})))
    #?(:clj
       (let [false-object (Boolean. false)
             parameters {"a" :bool "unused" :bool "i" :int}]
         (is (= {:outcome :false} (check engine "a" parameters {"a" false-object} nil)))
         (is (= {:outcome :true} (check engine "!(a)" parameters {"a" false-object} nil)))
         (is (= {:outcome :true} (check engine "i == 1" parameters {"i" 1N} nil)))))))

(deftest canonicalization-precedes-bound-context-merge
  (let [engine (portable/evaluator)
        folded #(sorted-map-by (fn [a b] (compare (str/lower-case a) (str/lower-case b))) %1 %2)
        parameters {"a" :bool "A" :bool "m" [:map :string :bool]}]
    (is (= {:outcome :false} (check engine "a" parameters (folded "a" false) {"A" true})))
    (is (= {:outcome :error :reason :missing-map-key}
           (check engine "m.a" parameters {"m" (folded "a" true)} {"m" {"A" true}})))))

(deftest capability-and-process-default
  (let [engine (portable/evaluator)]
    (is (= portable/capability (caveat-evaluator/descriptor engine)))
    (is (= engine (caveat-evaluator/require-matching! engine caveat-evaluator/profile-fingerprint)))
    (is (= values/profile-id (:profile portable/capability)))
    (is (= 1 (:capability-version portable/capability)))
    (is (seq (:fingerprint portable/capability)))
    (is (not= (:fingerprint portable/capability) caveat-evaluator/profile-fingerprint))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (caveat-evaluator/require-matching! engine "another-profile")))
    ;; ClojureScript has no other evaluator module, so requiring this one made
    ;; it the process default. On the JVM a registered module keeps priority.
    #?(:cljs (is (= portable/capability
                    (caveat-evaluator/descriptor (caveat-evaluator/default-evaluator))))
       :clj (is (some? (caveat-evaluator/require-matching! (caveat-evaluator/default-evaluator)
                                                           caveat-evaluator/profile-fingerprint))))))

(deftest retained-plans-reuse-only-complete-validated-content
  (let [engine (portable/evaluator {:max-entries 2})
        entity (definition/entity "cached" {"flag" :bool} "flag")]
    (is (= {:outcome :true} (caveat-evaluator/evaluate engine entity {"flag" true} {})))
    (is (= {:outcome :false} (caveat-evaluator/evaluate engine (assoc entity :db/id 456) {"flag" false} {})))
    (is (= {:hits 1 :builds 1 :failed-builds 0 :entries 1} (portable/cache-stats engine)))
    (is (= {:outcome :error :reason :definition-shape}
           (caveat-evaluator/evaluate engine (assoc entity :unexpected true) {"flag" true} {})))
    (doseq [changed [(dissoc entity :eacl.caveat/parameters-payload)
                     (assoc entity :eacl.caveat/parameters-payload "[:eacl.caveat/parameters 1 []]")
                     (assoc entity :eacl.caveat/parameters-payload "malformed")
                     (assoc entity :eacl.caveat/parameters-payload (apply str (repeat 16385 "x")))
                     (assoc entity :eacl.caveat/profile-version "unknown")
                     (assoc entity :eacl.caveat/name "not a name")
                     (assoc entity :eacl.caveat/expression-source "missing")]]
      (is (= :error (:outcome (caveat-evaluator/evaluate engine changed {"flag" true} {})))))
    (is (= {:outcome :false}
           (caveat-evaluator/evaluate engine (assoc entity :eacl.caveat/expression-source "!flag")
                                      {"flag" true} {})))
    (is (= {:outcome :true}
           (caveat-evaluator/evaluate engine (definition/entity "cached" {"flag" :int} "flag >= 0")
                                      {"flag" 1} {})))
    (is (<= (:entries (portable/cache-stats engine)) 2))
    (is (pos? (:failed-builds (portable/cache-stats engine))))
    (is (= {:outcome :true} (caveat-evaluator/evaluate engine entity {"flag" true} {})))))

(deftest cache-bounds-are-admitted-at-construction
  (doseq [max-entries [0 257 1.5 "2" nil]]
    (is (= :resource-limit (rejection #(portable/evaluator {:max-entries max-entries})))))
  (doseq [max-entries [1 256]]
    (is (= {:outcome :true}
           (check (portable/evaluator {:max-entries max-entries}) "a" {"a" :bool} {"a" true} nil)))))

(deftest host-faults-are-evaluator-exceptions
  (let [broken (portable/->PortableEvaluator {:store nil :counters nil})]
    (is (= {:outcome :error :reason :evaluator-exception}
           (caveat-evaluator/evaluate broken (definition/entity "x" {"a" :bool} "a") {"a" true} nil)))))

(ns eacl.caveats.partial-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [eacl.caveats.plan :as plan]
            [eacl.caveats.partial :as partial]))

(def parameters [["a" :bool] ["b" :bool] ["m" [:map :string :bool]]])

(defn evaluate [source request bound]
  (partial/evaluate parameters (:plan (plan/compile-plan source parameters)) request bound))

(deftest partial-results-and-faults
  (doseq [[source context expected]
          [["a || b" {"a" true} {:outcome :true}]
           ["a && b" {"b" false} {:outcome :false}]
           ["a || b" {"a" false} {:outcome :conditional :missing-fields #{"b"} :residual [:param "b"]}]
           ["m.enabled && a" {"m" {}} {:outcome :error :reason :missing-map-key}]
           ["m.enabled && a" {"m" {} "a" false} {:outcome :false}]
           ["a || m.enabled" {"a" true "m" {}} {:outcome :true}]
           ["a" {"a" "bad"} {:outcome :error :reason :context-type}]]]
    (is (= expected (evaluate source context {}))))
  (is (= {:outcome :true} (evaluate "a" {"a" false} {"a" true})))
  (is (= {:outcome :error :reason :context-type} (evaluate "a" {"a" "bad"} {"a" true}))))

(deftest comprehension-results-missing-fields-and-work
  (let [parameters [["inventory" [:list :string]] ["sensitive" [:list :string]] ["m" [:map :string :bool]]]
        check (fn [source request]
                (partial/evaluate parameters (:plan (plan/compile-plan source parameters)) request {}))
        nothing-sensitive "!inventory.exists(item, item in sensitive)"]
    (is (= {:outcome :false} (check nothing-sensitive {"inventory" ["keys" "passport"] "sensitive" ["passport"]})))
    (is (= {:outcome :true} (check nothing-sensitive {"inventory" ["keys"] "sensitive" ["passport"]})))
    (is (= {:outcome :true} (check nothing-sensitive {"inventory" [] "sensitive" ["passport"]})))
    (is (= {:outcome :conditional :missing-fields #{"inventory"}
            :residual [:not [:exists [:param "inventory"] "item"
                               [:in [:var "item"] [:literal [:list :string] ["passport"]]]]]}
           (check nothing-sensitive {"sensitive" ["passport"]})))
    (is (= #{"inventory"} (:missing-fields (check nothing-sensitive {})))
        "an absent range is missing on its own, as in SpiceDB")
    (is (= {:outcome :conditional :missing-fields #{"sensitive"}
            :residual [:not [:exists [:literal [:list :string] ["keys"]] "item"
                               [:in [:var "item"] [:param "sensitive"]]]]}
           (check nothing-sensitive {"inventory" ["keys"]})))
    (is (= {:outcome :true} (check "inventory.exists(x, m[x])" {"inventory" ["zz" "a"] "m" {"a" true}}))
        "a true element decides exists over another element's fault")
    (is (= {:outcome :error :reason :missing-map-key}
           (check "inventory.exists(x, m[x])" {"inventory" ["zz" "a"] "m" {"a" false}})))
    (is (= {:outcome :false} (check "inventory.all(x, m[x])" {"inventory" ["zz" "a"] "m" {"a" false}})))
    (is (= {:outcome :true} (check "m.exists(k, m[k])" {"m" {"a" false "b" true}})))
    (testing "each element of a supplied range is charged; an absent range is not iterated"
      (is (= :conditional (:outcome (check "inventory.exists(x, x in sensitive)" {"inventory" ["a"]}))))
      (is (= {:outcome :error :reason :resource-limit}
             (check "inventory.exists(x, x in sensitive)" {"inventory" ["a" "b"]}))))))

(deftest comprehension-variables-are-lexically-scoped
  (let [parameters [["xs" [:list :string]] ["ys" [:list :bool]] ["x" :string]]
        check (fn [source request]
                (partial/evaluate parameters (:plan (plan/compile-plan source parameters)) request {}))]
    (is (= {:outcome :true} (check "xs.exists(xs, xs == \"a\")" {"xs" ["b" "a"]})))
    (is (= {:outcome :false} (check "xs.exists(x, x == \"b\") && x == \"a\"" {"xs" ["c"]}))
        "a missing parameter x is not the variable x")
    (is (= {:outcome :true} (check "xs.exists(x, ys.exists(x, x))" {"xs" ["q"] "ys" [false true]})))
    (is (= {:outcome :false} (check "ys.all(b, b)" {"ys" [true false]})))))

(deftest partial-container-residuals-and-bounds
  (let [parameters [["k" :string] ["m" [:map :string :bool]]]
        expression (:plan (plan/compile-plan "m[k]" parameters))
        result (partial/evaluate parameters expression {"m" {"enabled" true}} {})]
    (is (= {:outcome :conditional :missing-fields #{"k"}
            :residual [:index [:literal [:map :string :bool] {"enabled" true}] [:param "k"]]} result))
    (is (= (:residual result) (:plan (plan/decode-plan (plan/encode-plan parameters (:residual result)))))))
  (let [parameters [["a" :string] ["b" :string]]
        expression (:plan (plan/compile-plan "true || a.contains(b)" parameters))]
    (is (= {:outcome :error :reason :resource-limit}
           (partial/evaluate parameters expression {"a" (apply str (repeat 2048 "x"))
                                                    "b" (apply str (repeat 1024 "x"))} {})))))

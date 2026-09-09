(ns eacl.caveats.partial-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
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

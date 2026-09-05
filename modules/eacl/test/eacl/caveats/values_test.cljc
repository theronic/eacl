(ns eacl.caveats.values-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [eacl.caveats.values :as values]))

(def parameters [["active" :bool] ["region" :string] ["roles" [:list :string]]
                 ["settings" [:map :string :bool]] ["until" :timestamp]])

(defn reason [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (:reason (ex-data e)))))

(deftest canonical-parameter-and-context-payloads
  (is (= parameters (values/normalize-parameters (reverse parameters))))
  (let [parameter-payload (values/encode-parameters parameters)]
    (is (= parameters (values/decode-parameters parameter-payload)))
    (is (= parameter-payload (values/encode-parameters (into {} parameters)))))
  (let [context {"active" false "region" "za" "roles" ["reader" "admin"]
                 "settings" {"😀" true "￿" false} "until" [:timestamp 0]}
        payload (values/encode-context parameters context)]
    (is (= context (values/decode-context parameters payload)))
    (is (= payload (values/encode-context parameters (into (sorted-map) context))))
    (is (< #?(:clj (.indexOf ^String payload "￿") :cljs (.indexOf payload "￿"))
           #?(:clj (.indexOf ^String payload "😀") :cljs (.indexOf payload "😀")))
        "map keys use Unicode scalar order")
    (is (= :noncanonical-payload (reason #(values/decode-context parameters (str " " payload)))))))

(deftest input-errors-are-typed-and-bounded
  (doseq [[context expected]
          [[{"unknown" true} :unknown-parameter]
           [{"active" 1} :context-type]
           [{"roles" ["reader" 1]} :context-type]
           [{"until" [:timestamp 253402300800000]} :context-type]
           [{"region" (apply str (repeat 4097 "a"))} :resource-limit]
           [{"roles" (vec (repeat 129 "a"))} :resource-limit]]]
    (is (= expected (reason #(values/encode-context parameters context)))))
  (is (= :duplicate-parameter (reason #(values/normalize-parameters [["a" :bool] ["a" :int]]))))
  (is (= :parameter-type (reason #(values/normalize-parameters [["a" [:list [:list :bool]]]]))))
  (is (= :parameter-name (reason #(values/normalize-parameters [["__eacl_internal" :bool]]))))
  (is (= :resource-limit (reason #(values/decode-context parameters (apply str (repeat 100 "[")))))))

(deftest bound-context-is-validated-before-winning
  (is (= {"region" "za" "active" false}
         (values/merge-context parameters {"region" "us" "active" false} {"region" "za"})))
  (is (= :context-type (reason #(values/merge-context parameters {"active" "bad"} {"active" true})))))

(deftest host-context-normalization-refines-the-wire-round-trip
  (let [scalars [[:bool [false true]]
                 [:int [-9007199254740991 0 9007199254740991]]
                 [:string ["" "za" "hé😀中"]]
                 [:timestamp [[:timestamp -62135596800000] [:timestamp 0] [:timestamp 253402300799999]]]]]
    (doseq [[type examples] scalars
            [type examples] [[type examples]
                             [[:list type] [[] (vec examples)]]
                             [[:map :string type] [{} (zipmap ["a" "😀" "￿"] examples)]]]
            value examples
            context [{} {"value" value}]
            parameters [[["value" type]] {"value" type}]]
      (is (= (values/decode-context parameters (values/encode-context parameters context))
             (values/normalize-context parameters context))))
    (doseq [[type examples] scalars
            :let [parameters [["a" type] ["b" type]]
                  contexts [{} {"a" (first examples)} {"a" (last examples)} {"b" (first examples)}]]
            request contexts bound contexts]
      (is (= (values/decode-context parameters (values/encode-context parameters (merge request bound)))
             (values/merge-context parameters request bound))))
    (doseq [[type examples] scalars
            invalid [nil [] {"unknown" (first examples)} {"value" #{}}]]
      (let [parameters [["value" type]]]
        (is (= (reason #(values/decode-context parameters (values/encode-context parameters invalid)))
               (reason #(values/normalize-context parameters invalid))))))))

(ns eacl.caveats.plan-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [clojure.string :as str]
            [eacl.caveats.partial :as partial]
            [eacl.caveats.plan :as plan]
            [eacl.caveats.values :as values]))

(defn reason [f]
  (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (:reason (ex-data e)))))

(deftest typed-canonical-plans
  (is (= [:or [:eq [:param "a"] [:literal :int 2]] [:eq [:param "b"] [:literal :int 6]]]
         (:plan (plan/compile-plan "a == 2 || b == 6" [["a" :int] ["b" :int]]))))
  (is (= [:and [:param "a"] [:not [:param "b"]]]
         (:plan (plan/compile-plan "a && !b" [["b" :bool] ["a" :bool]]))))
  (is (= [:eq [:index [:param "m"] [:literal :string "enabled"]] [:literal :bool true]]
         (:plan (plan/compile-plan "m.enabled == true" [["m" [:map :string :bool]]]))))
  (is (= [:contains [:param "text"] [:literal :string "\\n"]]
         (:plan (plan/compile-plan "text.contains(\"\\\\n\")" [["text" :string]]))))
  (is (= [:literal :string "😀"]
         (nth (:plan (plan/compile-plan "text == \"\\uD83D\\uDE00\"" [["text" :string]])) 2)))
  (is (= "a\n&& b" (:source (plan/compile-plan "a\r\n&& b" [["a" :bool] ["b" :bool]])))))

(deftest profile-admission-rejections
  (doseq [[source parameters expected]
          [["a" [] :unknown-parameter]
           ["1" [] :non-boolean-root]
           ["a < b" [["a" :bool] ["b" :bool]] :unsupported-overload]
           ["a == b" [["a" :int] ["b" :string]] :unsupported-overload]
           ["true == 1 < 2" [] :unsupported-overload]
           ["!!true" [] :unsupported-operation]
           ["1 + 1 == 2" [] :unsupported-operation]
           ["text.matches(\"x\")" [["text" :string]] :unsupported-operation]
           ["a ? true : false" [["a" :bool]] :unsupported-operation]
           ["9007199254740992 == 0" [] :literal-type]
           ["\"unterminated" [] :syntax-error]]]
    (is (= expected (reason #(plan/compile-plan source parameters))))))

(deftest lexical-and-plan-limits-precede-evaluation
  (is (= :resource-limit
         (reason #(plan/compile-plan (str (apply str (repeat 33 "(")) "true"
                                          (apply str (repeat 33 ")"))) []))))
  (is (= :resource-limit (reason #(plan/compile-plan (apply str (repeat 8193 " ")) []))))
  (is (= :resource-limit
         (reason #(plan/compile-plan (str/join " && " (repeat 33 "true")) [])))))

(deftest tokens-distinguish-literals-from-punctuation
  (doseq [source ["\")\" == \")\"" "\"(\" == \"(\"" "\"!\" == \"!\""
                  "\"&&\" == \"&&\"" "!(!true)" "1 < 2 == true"]]
    (is (= :bool (:result-type (plan/compile-plan source [])))))
  (doseq [source ["" "!" "true &&" "true." "(" "true[" "true.contains(" "true )"]]
    (is (= :syntax-error (reason #(plan/compile-plan source []))))))

(deftest comprehensions-resolve-names-lexically
  (let [parameters [["xs" [:list :string]] ["ys" [:list :int]] ["x" :string] ["m" [:map :string :int]]]
        compiled #(plan/compile-plan % parameters)]
    (is (= [:not [:exists [:param "xs"] "item" [:in [:var "item"] [:param "xs"]]]]
           (:plan (compiled "!xs.exists(item, item in xs)"))))
    (is (= [:and [:exists [:param "xs"] "x" [:eq [:var "x"] [:literal :string "b"]]]
            [:eq [:param "x"] [:literal :string "a"]]]
           (:plan (compiled "xs.exists(x, x == \"b\") && x == \"a\"")))
        "the variable x hides the parameter x only inside its predicate")
    (is (= [:exists [:param "xs"] "x" [:all [:param "ys"] "x" [:lt [:var "x"] [:literal :int 2]]]]
           (:plan (compiled "xs.exists(x, ys.all(x, x < 2))")))
        "an inner variable hides an outer one of the same name")
    (is (= [:all [:param "m"] "k" [:ge [:index [:param "m"] [:var "k"]] [:literal :int 0]]]
           (:plan (compiled "m.all(k, m[k] >= 0)"))))
    (is (= "eacl-cel/2" (:profile (compiled "m.all(k, m[k] >= 0)"))))
    (is (= "eacl-cel/1" (:profile (compiled "x == \"a\""))))))

(deftest comprehension-admission-rejections
  (doseq [[source expected]
          [["s.exists(c, true)" :unsupported-overload]
           ["xs.exists(x, x)" :unsupported-overload]
           ["xs.exists(x, true) && x2 == \"a\"" :unknown-parameter]
           ["xs.exists(x.y, true)" :syntax-error]
           ["xs.exists(true, true)" :syntax-error]
           ["xs.exists(in, true)" :syntax-error]
           ["xs.exists(if, true)" :syntax-error]
           ["xs.exists(__eacl_x, true)" :syntax-error]
           ["xs.exists(__result__, true)" :syntax-error]
           ["xs.exists(x, __result__)" :unsupported-operation]
           ["xs.exists(x, ys.exists(y, __result__))" :unsupported-operation]
           ["xs.exists(x)" :syntax-error]
           ["xs.exists(x, true, false)" :syntax-error]
           ["xs.exists_one(x, true)" :unsupported-operation]
           ["xs.map(x, true).exists(y, y)" :unsupported-operation]
           ["xs.filter(x, true).exists(y, true)" :unsupported-operation]]]
    (is (= expected (reason #(plan/compile-plan source [["xs" [:list :string]] ["ys" [:list :string]]
                                                        ["s" :string] ["__result__" :bool]])))
        source))
  (is (= :bool (:result-type (plan/compile-plan "__result__.exists(x, true) && \"a\" in __result__"
                                                [["__result__" [:list :string]]])))
      "outside a comprehension __result__ is an ordinary parameter"))

(def codec-cases
  [(vector [["x" :string]] [:index [:literal [:map :string :bool] {"enabled" true}] [:param "x"]])
   (vector [["x" :int]] [:in [:param "x"] [:literal [:list :int] [1 2]]])
   (vector [] [:eq [:literal :string "😀"] [:literal :string "😀"]])
   (vector [["ys" [:list :string]]]
           [:exists [:literal [:list :string] ["a"]] "x" [:all [:param "ys"] "y" [:ne [:var "x"] [:var "y"]]]])])

(def malformed-plans
  [[:param "missing"]
   [:literal :bool "true"]
   [:and [:literal :bool true]]
   [:literal :int 9007199254740992]
   [:not [:literal :bool true] [:literal :bool false]]
   [:var "x"]
   [:exists [:literal [:list :bool] [true]] "x" [:var "y"]]
   [:exists [:literal [:list :bool] [true]] "__result__" [:literal :bool true]]
   [:exists [:literal [:list :bool] [true]] [:var "x"] [:literal :bool true]]
   [:exists [:literal :bool true] "x" [:literal :bool true]]
   [:all [:literal [:list :bool] [true]] "x"]])

(deftest portable-plan-and-residual-codec
  (doseq [[parameters expression] codec-cases]
    (let [payload (plan/encode-plan parameters expression)]
      (is (= {:parameters parameters :plan expression} (plan/decode-plan payload)))
      (is (= :noncanonical-payload (reason #(plan/decode-plan (str " " payload)))))))
  (doseq [expression malformed-plans]
    (is (keyword? (reason #(plan/validate-plan [] expression)))))
  (is (= :resource-limit (reason #(plan/decode-plan (apply str (repeat 100 "[")))))))

(deftest member-literals-are-admitted-like-string-literals
  ;; `m.name` indexes with the string literal "name". Evaluation re-validates
  ;; every literal, so a member the parser admitted past the string bound
  ;; faulted with :resource-limit on every evaluation of its definition.
  (let [parameters [["m" [:map :string :bool]]]
        at-bound (apply str (repeat (:string-utf8-bytes values/limits) "a"))
        over-bound (str at-bound "a")]
    (doseq [member [at-bound over-bound]]
      (is (= (reason #(plan/compile-plan (str "m[\"" member "\"] == true") parameters))
             (reason #(plan/compile-plan (str "m." member " == true") parameters)))))
    (is (= {:offset 2 :reason :resource-limit}
           (try (plan/compile-plan (str "m." over-bound " == true") parameters) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                  (select-keys (ex-data e) [:offset :reason])))))
    (let [{:keys [plan]} (plan/compile-plan (str "m." at-bound " == true") parameters)]
      (is (= plan (plan/validate-plan parameters plan)))
      (is (= {:outcome :true} (partial/evaluate parameters plan {"m" {at-bound true}} {})))
      (is (= {:outcome :error :reason :missing-map-key}
             (partial/evaluate parameters plan {"m" {}} {}))))))

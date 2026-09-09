(ns eacl.caveats.plan-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [clojure.string :as str]
            [eacl.caveats.plan :as plan]))

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

(def codec-cases
  [(vector [["x" :string]] [:index [:literal [:map :string :bool] {"enabled" true}] [:param "x"]])
   (vector [["x" :int]] [:in [:param "x"] [:literal [:list :int] [1 2]]])
   (vector [] [:eq [:literal :string "😀"] [:literal :string "😀"]])])

(def malformed-plans
  [[:param "missing"]
   [:literal :bool "true"]
   [:and [:literal :bool true]]
   [:literal :int 9007199254740992]
   [:not [:literal :bool true] [:literal :bool false]]])

(deftest portable-plan-and-residual-codec
  (doseq [[parameters expression] codec-cases]
    (let [payload (plan/encode-plan parameters expression)]
      (is (= {:parameters parameters :plan expression} (plan/decode-plan payload)))
      (is (= :noncanonical-payload (reason #(plan/decode-plan (str " " payload)))))))
  (doseq [expression malformed-plans]
    (is (keyword? (reason #(plan/validate-plan [] expression)))))
  (is (= :resource-limit (reason #(plan/decode-plan (apply str (repeat 100 "[")))))))

(ns eacl.formal.caveats.plan-bridge
  (:require [clojure.test :refer [deftest is]]
            [eacl.formal.caveats.model :as model]
            [eacl.caveats.plan :as plan]
            [eacl.caveats.partial :as partial]))

(def parameters {"a" :bool "b" :bool "m" [:map :string :bool] "key" :string})
(def atoms [[:param "a"] [:param "b"] [:literal :bool false] [:literal :bool true]
            [:index [:param "m"] [:literal :string "enabled"]]])
(def expressions
  (vec (concat atoms (map #(vector :not %) atoms)
               (for [op [:and :or] a atoms b atoms] [op a b]))))
(def contexts
  (for [a [nil false true] b [nil false true] m [nil {} {"enabled" false} {"enabled" true}]]
    (cond-> {} (some? a) (assoc "a" a) (some? b) (assoc "b" b) (some? m) (assoc "m" m))))

(deftest exhaustive-partial-outcomes-and-work
  (doseq [expression expressions context contexts bound [{} {"a" true}]]
    (is (= (model/evaluate parameters expression context bound)
           (partial/evaluate parameters expression context bound))))
  (doseq [expression expressions context contexts]
    (is (= (model/estimate-work parameters expression context)
           (partial/estimate-work parameters expression context)))))

(def operators {:and "&&" :or "||" :eq "==" :ne "!=" :lt "<" :le "<=" :gt ">" :ge ">=" :in "in"})
(defn source [[op a b c]]
  (case op
    :literal (pr-str b)
    (:param :var) a
    :not (str "!(" (source a) ")")
    :index (str "(" (source a) ")[" (source b) "]")
    (:exists :all) (str "(" (source a) ")." (name op) "(" b ", " (source c) ")")
    (str "(" (source a) " " (operators op) " " (source b) ")")))

(deftest independently-constructed-plans-parse-and-round-trip
  (doseq [expression expressions]
    (is (= expression (:plan (plan/compile-plan (source expression) parameters)))))
  (doseq [expression expressions context contexts
          :let [result (model/evaluate parameters expression context {})]
          :when (= :conditional (:outcome result))]
    (let [residual (:residual result)]
      (is (= residual (:plan (plan/decode-plan (plan/encode-plan parameters residual))))))))

;; Comprehensions: every predicate outcome per element (see the model test's
;; tags), missing and supplied ranges and predicate fields, bound values,
;; nesting and shadowing.
(def comprehension-parameters
  {"xs" [:list :string] "ys" [:list :string] "b" :bool "m" [:map :string :bool] "x" :string})
(def tag-predicate
  [:or [:eq [:var "x"] [:literal :string "t"]]
   [:or [:and [:eq [:var "x"] [:literal :string "u"]] [:param "b"]]
    [:and [:eq [:var "x"] [:literal :string "e"]] [:index [:param "m"] [:var "x"]]]]])
(def comprehensions
  (vec (concat
        (for [op [:exists :all]] [op [:param "xs"] "x" tag-predicate])
        (for [op [:exists :all]] [:not [op [:param "xs"] "x" [:in [:var "x"] [:param "ys"]]]])
        [[:exists [:param "m"] "k" [:or [:index [:param "m"] [:var "k"]] [:param "b"]]]
         [:all [:param "m"] "k" [:eq [:var "k"] [:param "x"]]]
         [:and [:exists [:param "xs"] "x" [:eq [:var "x"] [:literal :string "t"]]] [:eq [:param "x"] [:literal :string "t"]]]
         [:exists [:param "xs"] "x" [:all [:param "ys"] "y" [:ne [:var "x"] [:var "y"]]]]
         [:exists [:param "xs"] "x" [:exists [:param "ys"] "x" [:eq [:var "x"] [:literal :string "u"]]]]
         [:or [:param "b"] [:all [:param "xs"] "x" [:index [:param "m"] [:var "x"]]]]])))
(def comprehension-contexts
  (for [xs [nil [] ["t"] ["f" "u"] ["u" "e"] ["e" "t"] ["f" "f"]]
        ys [nil [] ["u"] ["t" "f"]]
        b [nil false true]
        m [nil {} {"e" true "u" false}]
        x [nil "t"]]
    (cond-> {} (some? xs) (assoc "xs" xs) (some? ys) (assoc "ys" ys) (some? b) (assoc "b" b)
            (some? m) (assoc "m" m) (some? x) (assoc "x" x))))

(deftest exhaustive-comprehension-outcomes-and-work
  (doseq [expression comprehensions context comprehension-contexts bound [{} {"ys" ["t"]}]]
    (is (= (model/evaluate comprehension-parameters expression context bound)
           (partial/evaluate comprehension-parameters expression context bound))
        (pr-str [expression context bound])))
  (doseq [expression comprehensions context comprehension-contexts]
    (is (= (model/estimate-work comprehension-parameters expression context)
           (partial/estimate-work comprehension-parameters expression context)))))

(deftest comprehension-plans-parse-and-residuals-round-trip
  (doseq [expression comprehensions]
    (is (= expression (:plan (plan/compile-plan (source expression) comprehension-parameters))))
    (is (= "eacl-cel/2" (model/profile-of expression)
           (:profile (plan/compile-plan (source expression) comprehension-parameters)))))
  (doseq [expression comprehensions context comprehension-contexts
          :let [result (model/evaluate comprehension-parameters expression context {})]
          :when (= :conditional (:outcome result))]
    (let [residual (:residual result)]
      (is (= residual (:plan (plan/decode-plan (plan/encode-plan comprehension-parameters residual)))))
      (is (= :bool (model/plan-type comprehension-parameters residual))))))

(deftest comprehension-type-differentials
  (let [types [:bool :int :string [:list :int] [:list :string] [:map :string :bool]]
        predicates [[:literal :bool true] [:var "v"] [:eq [:var "v"] [:literal :string "a"]]
                    [:param "p"] [:eq [:var "v"] [:param "p"]] [:index [:param "p"] [:var "v"]]
                    [:param "__result__"]]]
    (doseq [op [:exists :all] range-type types p-type types predicate predicates
            variable ["v" "p" "__result__" "if" "__eacl_v"]
            :let [parameters {"r" range-type "p" p-type "__result__" :bool}
                  expression [op [:param "r"] variable predicate]
                  actual (try (plan/validate-plan parameters expression) :bool
                              (catch clojure.lang.ExceptionInfo _ :invalid))]]
      (is (= (model/plan-type parameters expression) actual) (pr-str expression)))))

(deftest profile-type-and-value-differentials
  (let [types [:bool :int :string :timestamp [:list :int] [:map :string :bool]]]
    (doseq [op [:and :or :eq :ne :lt :le :gt :ge :in :index :contains :starts-with :ends-with]
            a types b types]
      (let [parameters {"a" a "b" b}
            expression [op [:param "a"] [:param "b"]]
            actual (try (plan/node-type parameters expression)
                        (catch clojure.lang.ExceptionInfo _ :invalid))]
        (is (= (model/plan-type parameters expression) actual)))))
  (let [rng (java.util.Random. 9042026)
        types {"x" :int "xs" [:list :int] "t" :string "needle" :string "before" :timestamp "after" :timestamp}
        expressions [[:in [:param "x"] [:param "xs"]]
                     [:contains [:param "t"] [:param "needle"]]
                     [:lt [:param "before"] [:param "after"]]]]
    (dotimes [_ 1000]
      (let [context {"x" (.nextInt rng 10) "xs" (vec (repeatedly (.nextInt rng 129) #(.nextInt rng 10)))
                     "t" (apply str (repeat (.nextInt rng 100) "😀")) "needle" (if (.nextBoolean rng) "😀" "é")
                     "before" [:timestamp (- (.nextInt rng 100000) 50000)]
                     "after" [:timestamp (.nextInt rng 100000)]}
            expression (nth expressions (.nextInt rng (count expressions)))]
        (is (= (model/evaluate types expression context {}) (partial/evaluate types expression context {})))
        (is (= (model/estimate-work types expression context) (partial/estimate-work types expression context)))))))

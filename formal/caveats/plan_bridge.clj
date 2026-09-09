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
(defn source [[op a b]]
  (case op
    :literal (pr-str b)
    :param a
    :not (str "!(" (source a) ")")
    :index (str "(" (source a) ")[" (source b) "]")
    (str "(" (source a) " " (operators op) " " (source b) ")")))

(deftest independently-constructed-plans-parse-and-round-trip
  (doseq [expression expressions]
    (is (= expression (:plan (plan/compile-plan (source expression) parameters)))))
  (doseq [expression expressions context contexts
          :let [result (model/evaluate parameters expression context {})]
          :when (= :conditional (:outcome result))]
    (let [residual (:residual result)]
      (is (= residual (:plan (plan/decode-plan (plan/encode-plan parameters residual))))))))

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

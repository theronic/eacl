(ns eacl.relationships.qualifier-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.relationships.qualifier :as q]))

(defn reason [f]
  (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (:reason (ex-data e)))))

(def sparse-inputs
  [{:caveat 1}
   {:valid-until-ms 0}
   {:caveat 1 :caveat-context {}}
   {:caveat 1 :valid-until-ms 253402300799999 :caveat-context {"a" false}}])

(deftest sparse-canonical-qualifier
  (is (nil? (q/entity-data -1 {} [])))
  (is (nil? (q/normalize nil)))
  (doseq [input sparse-inputs]
    (let [entity (q/entity-data 5 input [["a" :bool]])]
      (is (= (q/normalize input [["a" :bool]]) (q/decode entity [["a" :bool]])))
      (is (= 1 (get entity q/marker-attribute)))
      (is (not (contains? entity :eacl/id)))))
  (is (= 3 (count (q/entity-data -1 {:caveat 1 :caveat-context {}} [])))))

(deftest malformed-and-missing-qualifiers-fail
  (doseq [[input expected]
          [[{:caveat-context {}} :context-without-caveat]
           [{:valid-until-ms 1.5} :qualifier-time]
           [{:valid-until-ms 253402300800000} :qualifier-time]
           [{:caveat ["lookup" 1]} :qualifier-ref]
           [{:caveats [1 2]} :qualifier-unknown-field]]]
    (is (= expected (reason #(q/normalize input)))))
  (is (= :missing-qualifier (reason #(q/decode nil []))))
  (is (= :qualifier-ref (reason #(q/decode {:db/id -1 q/marker-attribute 1 q/caveat-attribute 1} []))))
  (is (= :empty-qualifier (reason #(q/decode {q/marker-attribute 1} []))))
  (is (= :qualifier-format (reason #(q/decode {q/marker-attribute 2 q/caveat-attribute 1} [])))))

(ns eacl.formal.caveats.evaluator-bridge
  (:require [clojure.test :refer [deftest is]]
            [eacl.caveats.definition :as definition]
            [eacl.caveats.evaluator :as evaluator]
            [eacl.caveats.jvm :as jvm]
            [eacl.formal.caveats.model :as model]
            [eacl.formal.caveats.plan-bridge :as plans]))

(deftest complete-and-partial-jvm-refinement
  (let [engine (jvm/evaluator)]
    (doseq [expression plans/expressions
            :let [parameters (dissoc plans/parameters "key")
                  entity (definition/entity "finite" parameters (plans/source expression))]
            context plans/contexts bound [{} {"a" true}]]
      (is (= (model/evaluate parameters expression context bound)
             (evaluator/evaluate engine entity context bound))))))

(deftest generated-jvm-values-and-work
  (let [engine (jvm/evaluator) rng (java.util.Random. 9042026)
        parameters {"x" :int "xs" [:list :int] "text" :string "needle" :string
                    "before" :timestamp "after" :timestamp}
        cases [["x in xs" [:in [:param "x"] [:param "xs"]]]
               ["text.contains(needle)" [:contains [:param "text"] [:param "needle"]]]
               ["before < after" [:lt [:param "before"] [:param "after"]]]]
        definitions (mapv #(definition/entity "generated" parameters (first %)) cases)]
    (dotimes [_ 1000]
      (let [context {"x" (.nextInt rng 10) "xs" (vec (repeatedly (.nextInt rng 129) #(.nextInt rng 10)))
                     "text" (apply str (repeat (.nextInt rng 100) "😀")) "needle" (if (.nextBoolean rng) "😀" "é")
                     "before" [:timestamp (- (.nextInt rng 100000) 50000)] "after" [:timestamp (.nextInt rng 100000)]}
            index (.nextInt rng (count cases)) expression (second (nth cases index))]
        (is (= (model/evaluate parameters expression context {})
               (evaluator/evaluate engine (nth definitions index) context {})))))))

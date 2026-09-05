(ns eacl.formal.qualified.evidence-bridge
  (:require [clojure.test :refer [deftest is]]
            [eacl.authorization.evidence :as e]
            [eacl.authorization.evidence-test :as fixtures]
            [eacl.formal.qualified.model :as model]
            [eacl.formal.qualified.model-test :as contract]))

(defn production-for-worlds [worlds]
  (reduce (fn [result world]
            (let [term (reduce (fn [prior [i atom]]
                                 (e/combine :intersection prior
                                            (if (bit-test world i) atom (e/combine :exclusion true atom))))
                               true [[0 fixtures/x] [1 fixtures/y]])]
              (e/combine :union result term)))
          false worlds))

(defn model-value [production]
  (if (e/fault? production)
    {:fault (set (map second (second (e/value production))))}
    (model/value (fixtures/completions production [fixtures/x fixtures/y]))))

(defn model-evidence [production]
  {:value (model-value production) :end (e/valid-until production) :complete? (e/complete? production)})

(defn inputs []
  (let [values (concat (map production-for-worlds contract/subsets)
                       [(e/fault :eacl.qualifier/invalid :invalid)
                        (e/fault :eacl.caveat/evaluation :evaluator)])]
    (vec (for [v values [end complete] [[nil true] [100 true] [100 false]]]
           (e/with-certificate v end complete)))))

(deftest symbolic-evidence-refines-completion-set-denotation
  (doseq [worlds contract/subsets]
    (is (= (model/value worlds) (model-value (production-for-worlds worlds)))))
  (doseq [production (inputs)]
    (is (= production (e/decode (e/encode production)))))
  (doseq [op contract/operators a (inputs) b (inputs)]
    (let [expected (model/combine contract/universe op (model-evidence a) (model-evidence b))
          actual (e/combine op a b)
          missing (model/missing-fields contract/universe (:value expected) 2)]
      (is (= (:value expected) (model-value actual)))
      (is (= (:end expected) (e/valid-until actual)))
      (is (= (:complete? expected) (e/complete? actual)))
      (is (= (set (map ["x" "y"] missing)) (set (e/missing-fields actual)))))))

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

(deftest reproducible-compound-evidence-campaign
  ;; Independent oracle values are carried through the generated expression
  ;; graph; they are never reconstructed from a production intermediate.
  (let [seed 20260905
        random (java.util.Random. seed)
        initial (mapv (fn [production] [production (model-evidence production)]) (inputs))]
    (loop [step 0 pool initial]
      (when (< step 2000)
        (let [pick (fn [] (let [choices (if (.nextBoolean random) initial pool)]
                            (nth choices (.nextInt random (count choices)))))
              [left expected-left] (pick)
              [right expected-right] (pick)
              op (nth contract/operators (.nextInt random (count contract/operators)))
              expected (model/combine contract/universe op expected-left expected-right)
              actual (e/combine op left right)
              label (pr-str {:seed seed :step step :operation op})]
          (is (= (:value expected) (model-value actual)) label)
          (is (= (:end expected) (e/valid-until actual)) label)
          (is (= (:complete? expected) (e/complete? actual)) label)
          (is (= actual (e/decode (e/encode actual))) label)
          (recur (inc step) (conj (if (< (count pool) 128) pool (subvec pool 1))
                                  [actual expected])))))))

(ns eacl.operator-engine.counterexamples-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [eacl.operator-engine.oracle :as oracle]
            [eacl.test-support.repo :as repo]))

(def fixture-file
  "formal/fixtures/operator-engine/minimized-counterexamples.edn")

(defn- fixtures
  []
  (:counterexamples (edn/read-string (slurp (repo/file fixture-file)))))

(defn- fixture
  [id]
  (first (filter #(= id (:id %)) (fixtures))))

(deftest active-recursion-is-not-completed-false-test
  (let [{:keys [snapshot query expected]}
        (fixture :active-recursion-as-false)
        evaluation (oracle/evaluate-stratified snapshot)]
    (is (= expected
           (oracle/evaluated-check?
            evaluation (:subject query) (:permission query) (:resource query))))))

(deftest every-intersection-premise-is-required-test
  (let [{:keys [snapshot query expected]}
        (fixture :missing-intersection-premise)
        expression (get-in snapshot
                           [:permissions
                            [(first (:resource query)) (:permission query)]])]
    (is (= expected
           (oracle/check?
            snapshot (:subject query) (:permission query) (:resource query))))
    (is (true?
         (boolean
          (some #(contains?
                  (oracle/acyclic-expression-denotation
                   snapshot % (:resource query))
                  (:subject query))
                (rest expression)))))))

(deftest exclusion-operands-are-directed-test
  (let [{:keys [snapshot query expected]}
        (fixture :swapped-exclusion-operands)
        permission-key [(first (:resource query)) (:permission query)]
        [_ left right] (get-in snapshot [:permissions permission-key])
        swapped (assoc-in snapshot [:permissions permission-key]
                          [:exclusion right left])]
    (is (= expected
           (oracle/check?
            snapshot (:subject query) (:permission query) (:resource query))))
    (is (false?
         (oracle/check?
          swapped (:subject query) (:permission query) (:resource query))))))

(deftest forged-scc-components-cannot-hide-negative-cycle-test
  (let [{:keys [snapshot forged-components expected-error]}
        (fixture :unchecked-scc-certificate)
        actual (oracle/stratify snapshot)
        forged-component-of
        (into {}
              (mapcat (fn [component]
                        (map #(vector % component) component)))
              forged-components)
        forged-negative-internal?
        (boolean
         (some (fn [{:keys [from to negative?]}]
                 (and negative?
                      (= (forged-component-of from)
                         (forged-component-of to))))
               (oracle/signed-dependencies snapshot)))]
    (is (false? (:valid? actual)))
    (is (= expected-error (:error actual)))
    (is (false? forged-negative-internal?))))

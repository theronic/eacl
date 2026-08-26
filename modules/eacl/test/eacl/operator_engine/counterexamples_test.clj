(ns eacl.operator-engine.counterexamples-test
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [eacl.operator-engine.oracle :as oracle]
            [eacl.test-support.repo :as repo]))

(def fixture-file
  "exploration/operator-engine/minimized-counterexamples.edn")

(defn- fixtures
  []
  (:counterexamples (edn/read-string (slurp (repo/file fixture-file)))))

(defn- fixture
  [id]
  (first (filter #(= id (:id %)) (fixtures))))

(defn- relation-member?
  [snapshot resource relation subject]
  (contains?
   (:relationships snapshot)
   {:resource resource :relation relation :subject subject}))

(defn- active-as-false-mutant
  [snapshot evaluation-order]
  (let [memo (atom {})]
    (letfn [(evaluate-expression [resource-type resource subject active expression]
              (case (first expression)
                :relation
                (relation-member?
                 snapshot resource (second expression) subject)
                :permission
                (evaluate-permission
                 [resource-type (second expression)] resource subject active)
                :union
                (boolean
                 (some #(evaluate-expression
                         resource-type resource subject active %)
                       (rest expression)))
                :intersection
                (every? #(evaluate-expression
                          resource-type resource subject active %)
                        (rest expression))
                :exclusion
                (and (evaluate-expression
                      resource-type resource subject active
                      (second expression))
                     (not (evaluate-expression
                           resource-type resource subject active
                           (nth expression 2))))))
            (evaluate-permission [permission-key resource subject active]
              (let [state [permission-key resource subject]]
                (if (contains? @memo state)
                  (get @memo state)
                  (if (contains? active state)
                    false
                    (let [result
                          (evaluate-expression
                           (first permission-key) resource subject
                           (conj active state)
                           (get-in snapshot [:permissions permission-key]))]
                      (swap! memo assoc state result)
                      result)))))]
      (mapv
       (fn [[permission-key resource subject]]
         (evaluate-permission permission-key resource subject #{}))
       evaluation-order))))

(deftest active-recursion-is-not-completed-false-test
  (let [{:keys [snapshot evaluation-order query expected]}
        (fixture :active-recursion-as-false)
        evaluation (oracle/evaluate-stratified snapshot)]
    (is (= expected
           (oracle/evaluated-check?
            evaluation (:subject query) (:permission query) (:resource query))))
    (is (= [true false]
           (active-as-false-mutant snapshot evaluation-order)))))

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

(deftest incomplete-exclusion-right-is-not-absence-test
  (let [{:keys [left right expected]}
        (fixture :partial-right-side-absence)
        mutant-result
        (if (and (= :completed-true left)
                 (not= :completed-true right))
          :grant
          :deny)]
    (is (= :propagate-incomplete expected))
    (is (= :grant mutant-result))))

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

(deftest cursor-progress-stops-at-logical-boundary-test
  (let [{:keys [physical-batch returned expected-cursor-boundary
                expected-resume]}
        (fixture :cursor-advanced-through-batch-overread)
        correct-boundary (peek returned)
        correct-resume
        (vec (rest (drop-while #(not= correct-boundary %) physical-batch)))
        mutant-boundary (peek physical-batch)
        mutant-resume
        (vec (rest (drop-while #(not= mutant-boundary %) physical-batch)))]
    (is (= expected-cursor-boundary correct-boundary))
    (is (= expected-resume correct-resume))
    (is (not= expected-resume mutant-resume))))

(deftest typed-identity-is-part-of-every-join-key-test
  (let [{:keys [left right expected]}
        (fixture :wrong-typed-id-join-identity)
        mutant
        (set
         (for [left-value left
               right-value right
               :when (= (second left-value) (second right-value))]
           left-value))]
    (testing "correct typed intersection"
      (is (= expected (set/intersection left right))))
    (testing "the id-only mutant grants"
      (is (= #{[:user "same"]} mutant)))))

(deftest arrow-catalog-must-cover-every-intermediate-test
  (let [{:keys [resource subject intermediate catalog relationships expected]}
        (fixture :incomplete-arrow-object-catalog)
        relation? (fn [r rel s]
                    (contains? relationships
                               {:resource r :relation rel :subject s}))
        catalog-mutant
        (boolean
         (some #(and (relation? resource :parent %)
                     (relation? % :member subject))
               catalog))
        exact-existential
        (and (relation? resource :parent intermediate)
             (relation? intermediate :member subject))]
    (is (= expected exact-existential))
    (is (false? catalog-mutant))))

(deftest cache-invalidation-is-by-relation-not-dependency-sign-test
  (let [{:keys [dependencies changed expected-invalidated]}
        (fixture :cache-write-sign-mismatch)
        relation-invalidated?
        (boolean
         (some #(= (:relation %) (:relation changed)) dependencies))
        signed-mutant-invalidated? (contains? dependencies changed)]
    (is (= expected-invalidated relation-invalidated?))
    (is (false? signed-mutant-invalidated?))))

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

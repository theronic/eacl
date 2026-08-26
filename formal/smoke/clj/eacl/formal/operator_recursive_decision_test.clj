(ns eacl.formal.operator-recursive-decision-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.formal.java-operator-recursive :as generated]))

(def empty-state
  {:facts [] :completed-strata [] :pending-lower-questions []})

(def conjunction-model
  {:positive-rules
   [{:parent-expression 30 :width 2 :intersection? true :anchor-slot 0}]
   :positive-edges
   [{:child-expression 10 :parent-expression 30 :slot 0}
    {:child-expression 20 :parent-expression 30 :slot 1}]
   :strata [[10 0] [20 0] [30 0]]
   :exclusions []})

(defn- step [model state command]
  (generated/decide (assoc model :state state :command command)))

(deftest generated-anchor-gated-command-state-model
  (testing "non-anchor facts allocate no anchor state"
    (let [first-step
          (step conjunction-model empty-state
                {:kind :admit-fact
                 :fact {:expression 20 :entity-type 7 :entity-eid 42}})]
      (is (= :accepted (:status first-step)))
      (is (empty? (get-in first-step [:state :anchor-states])))
      (is (empty? (:actions first-step)))
      (testing "a late anchor initializes retained slots and derives once"
        (let [second-step
              (step conjunction-model (:state first-step)
                    {:kind :admit-fact
                     :fact {:expression 10 :entity-type 7 :entity-eid 42}})]
          (is (= [{:parent-expression 30
                   :entity-type 7 :entity-eid 42
                   :satisfied-slots [true true]
                   :satisfied-count 2}]
                 (get-in second-step [:state :anchor-states])))
          (is (= [{:kind :schedule-fact
                   :fact {:expression 30 :entity-type 7 :entity-eid 42}}]
                 (:actions second-step)))))))

  (testing "duplicate facts are idempotent"
    (let [state (assoc empty-state :facts
                       [{:expression 10 :entity-type 7 :entity-eid 42}])
          result
          (step conjunction-model state
                {:kind :admit-fact
                 :fact {:expression 10 :entity-type 7 :entity-eid 42}})]
      (is (true? (:duplicate-fact? result)))
      (is (= state (dissoc (:state result) :anchor-states)))
      (is (empty? (:actions result)))))

  (testing "typed identities do not collide"
    (let [state (assoc empty-state :facts
                       [{:expression 20 :entity-type 8 :entity-eid 42}])
          result
          (step conjunction-model state
                {:kind :admit-fact
                 :fact {:expression 10 :entity-type 7 :entity-eid 42}})]
      (is (= [true false]
             (get-in result [:state :anchor-states 0 :satisfied-slots])))
      (is (empty? (:actions result))))))

(deftest generated-exact-lower-stratum-model
  (let [model
        {:positive-rules [] :positive-edges []
         :strata [[40 1] [10 1] [20 0]]
         :exclusions
         [{:parent-expression 40 :left-expression 10
           :negative-expression 20}]}
        left {:expression 10 :entity-type 7 :entity-eid 42}
        question {:parent-expression 40 :negative-expression 20
                  :entity-type 7 :entity-eid 42
                  :parent-stratum 1 :negative-stratum 0}
        left-step (step model empty-state {:kind :admit-fact :fact left})]
    (testing "absence remains pending until the lower stratum completes"
      (is (empty? (:actions left-step)))
      (is (= [question]
             (get-in left-step [:state :pending-lower-questions])))
      (is (= {:status :rejected
              :failure {:kind :incomplete-lower-stratum
                        :question question}}
             (step model (:state left-step)
                   {:kind :resolve-exact-lower :question question}))))

    (testing "completion emits one exact question and exact absence grants"
      (let [complete
            (step model (:state left-step)
                  {:kind :complete-stratum :stratum 0})
            resolved
            (step model (:state complete)
                  {:kind :resolve-exact-lower :question question})]
        (is (= [{:kind :ask-exact-lower :question question}]
               (:actions complete)))
        (is (empty? (get-in complete [:state :pending-lower-questions])))
        (is (= [{:kind :schedule-fact
                 :fact {:expression 40 :entity-type 7 :entity-eid 42}}]
               (:actions resolved)))))

    (testing "exact negative presence denies and typed collisions do not"
      (let [complete-state
            (-> (:state left-step)
                (assoc :completed-strata [0])
                (update :facts conj
                        {:expression 20 :entity-type 7 :entity-eid 42}))]
        (is (empty?
             (:actions
              (step model complete-state
                    {:kind :resolve-exact-lower :question question}))))
        (is (= [{:kind :schedule-fact
                 :fact {:expression 40 :entity-type 8 :entity-eid 42}}]
               (:actions
                (step model
                      (update complete-state :facts conj
                              {:expression 10 :entity-type 8 :entity-eid 42})
                      {:kind :resolve-exact-lower
                       :question (assoc question :entity-type 8)}))))))))

(deftest generated-invalid-stratum-fails-closed
  (let [question {:parent-expression 40 :negative-expression 20
                  :entity-type 7 :entity-eid 42
                  :parent-stratum 0 :negative-stratum 0}
        result
        (step {:positive-rules [] :positive-edges []
               :strata [] :exclusions []}
              (assoc empty-state :completed-strata [0])
              {:kind :resolve-exact-lower :question question})]
    (is (= :rejected (:status result)))
    (is (= :invalid-lower-stratum (get-in result [:failure :kind])))))

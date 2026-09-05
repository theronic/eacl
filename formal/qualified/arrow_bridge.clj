(ns eacl.formal.qualified.arrow-bridge
  "Completing a known arrow binding against independent completion sets.
   The existing scalar machine visits the remaining bindings exactly once."
  (:require [clojure.test :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.formal.qualified.evidence-bridge :as bridge]
            [eacl.formal.qualified.model :as model]
            [eacl.operator.arrow-evidence-test :as fixtures]
            [eacl.operator.evaluator :as scalar]))

(defn semantic [layout a b time]
  (let [negative-a (evidence/combine :exclusion true a)
        values (case layout
                 0 [true a true b]
                 1 [a b b negative-a]
                 2 [a negative-a true b])]
    (into {} (map (fn [key value end]
                    [key (if (< time end) (evidence/with-certificate value end true) false)])
                  [[:via 0] [:target 0] [:via 1] [:target 1]] values [100 110 120 130]))))

(defn oracle [leaves]
  (model/compose :union
                 (model/compose :arrow (bridge/model-value (get leaves [:via 0]))
                                (bridge/model-value (get leaves [:target 0])))
                 (model/compose :arrow (bridge/model-value (get leaves [:via 1]))
                                (bridge/model-value (get leaves [:target 1])))))

(deftest seeded-arrow-completion-refines-union-of-exact-bindings
  (let [{:keys [qids] :as env} (fixtures/fixture)
        inputs (remove evidence/fault? (take-nth 3 (bridge/inputs)))]
    (doseq [permission [:view :direct_inherited]]
      (let [base (fixtures/options env permission)]
        (doseq [layout [0 1 2] a inputs b inputs]
          (let [leaves (semantic layout a b 99)
                known (evidence/combine :arrow (get leaves [:via 0]) (get leaves [:target 0]))
                result (with-redefs-fn {#'qualification/qualify (fixtures/resolver qids leaves)}
                         #(scalar/check-eids (assoc-in base [:arrow-witness :evidence] known)))]
            (is (= (oracle leaves) (bridge/model-value result)))
            (doseq [time [100 110 120]]
              (is (or (not (evidence/reusable? result 99 time))
                      (= (oracle (semantic layout a b time)) (bridge/model-value result)))))))))))

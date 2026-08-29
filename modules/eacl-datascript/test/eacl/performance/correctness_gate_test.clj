(ns eacl.performance.correctness-gate-test
  "Ordinary CI gates for the DataScript-backed performance reproductions."
  (:require [clojure.test :refer [deftest is]]
            [eacl.performance.amplification-reproduction :as reproduction]))

(deftest completed-cache-semantic-key-binds-compiler-and-value-abi-test
  (let [observation (reproduction/completed-cache-identity-probe)]
    (is (:required-identity-present? observation)
        (str "missing completed-cache compatibility identity: "
             (:missing-keys observation)))))

(deftest compatible-completed-hit-forces-no-derived-work-test
  (let [observation (reproduction/completed-cache-compatible-hit-probe)]
    (is (true? (:hit-cached? observation)))
    (is (true? (:same-answer? observation)))
    (is (= [0 0 0]
           (mapv observation [:schema-work :plan-work :proof-work]))
        (str "compatible hit forced derived work: " observation))))

(deftest completed-cache-rollout-misses-every-old-value-shape-test
  (let [observation (reproduction/completed-cache-rollout-probe)]
    (is (:same-answer? observation))
    (is (:incompatible-entry-missed? observation)
        (str "pre-rollout answer survived compatibility change: "
             observation))))

(deftest durable-cache-contracts-forbid-request-joining-test
  (let [observation (reproduction/cache-flight-contract-probe)]
    (is (:reconciled? observation)
        (str "positive flight/join requirements remain: "
             (:observations observation)))))

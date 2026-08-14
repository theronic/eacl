(ns eacl.baseline.baseline-test
  "Verifies the current engines still reproduce the frozen public-API
  baselines under exploration/baselines/ (stable-discovery change, task 2.2).

  Denotations, counts, point checks, pagination invariants, and behavioral
  outcomes are compared exactly. The informational :order vectors are also
  compared here because these snapshots freeze the CURRENT engines; once the
  stable-discovery engine routes the public API, the :order comparison moves
  to the new engine's own sequence gate and this suite is retired with the
  old engines."
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.baseline.capture :as capture]))

(defn- comparable
  "Strips fields that legitimately vary across processes (nothing today, but
  isolate the seam here)."
  [snapshot]
  snapshot)

(deftest frozen-baselines-reproduce-test
  (doseq [fixture-key (keys capture/fixtures)]
    (testing (str fixture-key)
      (let [frozen (capture/read-snapshot fixture-key)
            fresh (capture/capture-fixture fixture-key)]
        (is (some? frozen)
            (str "missing frozen snapshot for " fixture-key
                 " — run (eacl.baseline.capture/capture-all!)"))
        (when frozen
          (is (= (comparable frozen) (comparable fresh))))))))

(deftest baseline-invariants-test
  (doseq [fixture-key (keys capture/fixtures)]
    (testing (str fixture-key)
      (let [{:keys [forward]} (capture/read-snapshot fixture-key)]
        (doseq [[principal-key result] forward]
          (testing (str principal-key)
            (is (true? (:page-composition-equals-one-shot? result)))
            (is (true? (:duplicate-free? result)))
            (is (true? (:count-matches-denotation? result)))))))))

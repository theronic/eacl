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
  "The cross-engine comparable core. The snapshots froze the PREVIOUS
  engines; after the stable-discovery routing flip (task 9.1) the public
  API is the new engine, so this suite compares exactly what must be
  engine-independent — denotations, counts, points, and the structural
  pagination invariants — and retires the legacy-order and
  engine-mechanics fields (:order, :pages, :cursor-behavior,
  :stale-basis) whose replacements are certified by the stable-engine
  gates."
  [snapshot]
  (-> snapshot
      (update :forward
              (fn [forward]
                (into (sorted-map)
                      (map (fn [[k v]]
                             [k (select-keys
                                 v [:principal :denotation :count
                                    :duplicate-free?
                                    :page-composition-equals-one-shot?
                                    :count-matches-denotation?])]))
                      forward)))
      (update :reverse
              (fn [reverse]
                (into (sorted-map)
                      (map (fn [[k v]]
                             [k (select-keys v [:resource :outcome
                                                :denotation])]))
                      reverse)))
      (dissoc :cursor-behavior :stale-basis)))

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

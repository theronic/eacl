(ns eacl.characterization-fixture-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [eacl.authorization-oracle :as oracle]))

(def ^:private fixture-path
  (io/file "formal" "characterization" "v1" "eacl-engine.edn"))

(def ^:private performance-gates-path
  (io/file "formal" "verification" "performance-gates.edn"))

(defn- load-fixture
  []
  (edn/read-string (slurp fixture-path)))

(defn- resource-projection
  [grants subject permission resource-type]
  (->> grants
       (keep (fn [[candidate-subject candidate-permission resource]]
               (when (and (= subject candidate-subject)
                          (= permission candidate-permission)
                          (= resource-type (:type resource)))
                 resource)))
       (sort-by :id)
       vec))

(deftest versioned-characterization-fixture-replays-test
  (let [{:keys [fixture-format
                fixture-version
                semantics-version
                oracle-seed
                authorization-scenarios
                public-operation-scenarios]}
        (load-fixture)]
    (is (= :eacl/characterization fixture-format))
    (is (= 1 fixture-version semantics-version))
    (is (= oracle/fixture-seed oracle-seed))

    (testing "authorization scenarios exactly match the independent oracle"
      (doseq [{:keys [id expected-grants] :as scenario}
              authorization-scenarios]
        (is (= expected-grants (oracle/authorization-set scenario))
            (str "characterization mismatch: " id))))

    (testing "recursive lookup and count projections are frozen"
      (let [{:keys [expected-grants
                    expected-resource-lookup
                    expected-resource-count]}
            (first (filter #(= :recursive-scc (:id %))
                           authorization-scenarios))
            subject {:type :user :id "u1"}
            resources
            (resource-projection expected-grants subject :read :folder)]
        (is (= expected-resource-lookup resources))
        (is (= expected-resource-count (count resources)))))

    (testing "public cache, cursor, lookup/count, and typed-error behavior has evidence"
      (is (= #{:lookup-and-count
               :cursor-continuation
               :authenticated-cache
               :typed-errors}
             (set (map :id public-operation-scenarios))))
      (doseq [{:keys [id expected covered-by]} public-operation-scenarios]
        (is (seq expected) (str "missing expected result: " id))
        (is (seq covered-by) (str "missing executable evidence: " id))
        (is (every? symbol? covered-by)
            (str "evidence must name test vars: " id))
        (doseq [evidence covered-by]
          (is (var? (requiring-resolve evidence))
              (str "unresolvable characterization evidence: " evidence)))))))

(deftest quantitative-performance-gates-are-well-formed-test
  (let [{:keys [formal-pipeline
                generated-artifacts
                legacy-runtime
                memory-and-token
                shadow-rollout]}
        (edn/read-string (slurp performance-gates-path))]
    (is (< (:baseline-wall-seconds formal-pipeline)
           (:pull-request-max-seconds formal-pipeline)
           (:scheduled-max-seconds formal-pipeline)))
    (doseq [[_ {:keys [baseline-bytes] :as artifact}]
            (dissoc generated-artifacts :cutover-rule)]
      (is (pos-int? baseline-bytes))
      (is (< baseline-bytes
             (or (:foundation-max-bytes artifact)
                 Long/MAX_VALUE))))
    (is (< (get-in legacy-runtime
                   [:multipath-page :max-page-median-baseline-ms])
           (get-in legacy-runtime
                   [:multipath-page :max-page-median-max-ms])))
    (is (<= (get-in memory-and-token
                    [:cursor-token-utf8-bytes
                     :multipath-500-results-page-50])
            (get-in memory-and-token
                    [:cursor-token-utf8-bytes :regression-max])))
    (is (zero? (:unexplained-differences-max shadow-rollout)))
    (is (zero? (:false-grants-max shadow-rollout)))
    (is (= [:ci :canary :ramp :pre-authority]
           (mapv :name (:stages shadow-rollout))))
    (is (every? #(<= 0.0 (:sample-rate %) 1.0)
                (:stages shadow-rollout)))
    (is (every? #(pos-int? (:minimum-compared-operations %))
                (:stages shadow-rollout)))))

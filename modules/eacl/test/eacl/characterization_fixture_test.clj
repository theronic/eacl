(ns eacl.characterization-fixture-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [eacl.authorization-oracle :as oracle]
            [eacl.subproblem-cache :as subproblem]
            [eacl.test-support.repo :as repo]))

(def ^:private fixture-path
  (repo/file "formal" "characterization" "v1" "eacl-engine.edn"))

(def ^:private performance-gates-path
  (repo/file "formal" "verification" "performance-gates.edn"))

(defn- load-fixture
  []
  (edn/read-string (slurp fixture-path)))

(defn- available-evidence
  [evidence]
  (when (repo/evidence-namespace-available? evidence)
    (requiring-resolve evidence)))

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
          (when-let [evidence-var (available-evidence evidence)]
            (is (var? evidence-var)
                (str "unresolvable characterization evidence: "
                     evidence))))))))

(deftest quantitative-performance-gates-are-well-formed-test
  (let [{:keys [formal-pipeline
                generated-artifacts
                legacy-runtime
                generated-indexed-authority
                ordered-merge-source-specialization
                layered-subproblem-cache
                cross-backend-managed-proof
                memory-and-token
                release-performance-evaluation
                final-heavy-run
                shadow-rollout]}
        (edn/read-string (slurp performance-gates-path))
        generated-artifact-config
        (edn/read-string
         (slurp
          (repo/file
           (:machine-readable-config generated-artifacts))))]
    (is (= :host-specific-measurement
           (:wall-clock-assurance formal-pipeline)))
    (is (< 0
           (:last-observed-local-wall-seconds formal-pipeline)
           (:github-job-timeout-seconds formal-pipeline)))
    (is (pos-int?
         (:assertion-batch-time-limit-seconds formal-pipeline)))
    (is (pos-int? (:proof-effort-resource-limit formal-pipeline)))
    (is (< (get-in formal-pipeline
                   [:maximum-observed-proof-effort :resource-count])
           (:proof-effort-resource-limit formal-pipeline)))
    (is (= "bin/formal artifact-size"
           (:measurement-command generated-artifacts)))
    (is (= :after-all-generated-artifacts-are-rebuilt
           (:measurement-order generated-artifacts)))
    (is (= :reviewed-full-kernel-baseline
           (get-in generated-artifacts [:cutover-rule :status])))
    (is (.isFile
         (repo/file (:machine-readable-config generated-artifacts))))
    (is (= :uncompressed-byte-length
           (:measurement generated-artifact-config)))
    (is (= (get-in generated-artifacts
                   [:cutover-rule
                    :maximum-growth-over-reviewed-full-kernel])
           (:maximum-growth-over-reviewed-full-kernel
            generated-artifact-config)))
    (doseq [[_ {:keys [baseline-bytes maximum-bytes]}]
            (:artifacts generated-artifact-config)]
      (is (pos-int? baseline-bytes))
      (is (pos-int? maximum-bytes))
      (is (<= baseline-bytes maximum-bytes)))
    (is (< (get-in legacy-runtime
                   [:multipath-page :max-page-median-baseline-ms])
           (get-in legacy-runtime
                   [:multipath-page :max-page-median-max-ms])))
    (is (<= (get-in memory-and-token
                    [:cursor-token-utf8-bytes
                     :multipath-500-results-page-50])
            (get-in memory-and-token
                    [:cursor-token-utf8-bytes :regression-max])))
    (is (= {:payload-canonical-passes-per-encode 1
            :payload-canonical-passes-per-decode 1
            :authentication-passes-per-encode 1
            :authentication-passes-per-decode 1
            :public-relationship-continuation-decodes-per-request 1
            :framing-growth :linear}
           (:cursor-codec-work memory-and-token)))
    (is (zero? (:unexplained-differences-max shadow-rollout)))
    (is (zero? (:false-grants-max shadow-rollout)))
    (is (= [:ci :canary :ramp :pre-authority]
           (mapv :name (:stages shadow-rollout))))
    (is (every? #(<= 0.0 (:sample-rate %) 1.0)
                (:stages shadow-rollout)))
    (is (every? #(pos-int? (:minimum-compared-operations %))
                (:stages shadow-rollout)))

    (testing "each Lore resource dimension is evaluated independently"
      (let [dimensions (:dimensions release-performance-evaluation)
            required (:required-dimensions release-performance-evaluation)
            failed
            (filterv
             #(not= :passed (get-in dimensions [% :status]))
             required)]
        (is (= required (vec (distinct required))))
        (is (= [:retained-live-heap] failed))
        (is (true?
             (get-in dimensions
                     [:retained-live-heap :release-blocking])))
        (is (false?
             (:all-required-passed? release-performance-evaluation)))
        (is (= :refused
               (:release-cutover release-performance-evaluation)))))

    (testing "configured logical weight is checked without calling it heap"
      (let [store (subproblem/store)
            expected
            (:default-subproblem-cache memory-and-token)]
        (is (= {:projection (:projection-max-weight expected)
                :denotation (:denotation-max-weight expected)}
               (:budgets store)))
        (is (= (:max-inflight expected)
               (:max-inflight store)))
        (is (= (:managed-proof-max-atoms expected)
               (:managed-proof-max-atoms store)))
        (is (= :passed
               (get-in release-performance-evaluation
                       [:dimensions :entry-weight :status])))))

    (testing "proof-operation thresholds are evaluated from like dimensions"
      (let [maximum
            (get-in cross-backend-managed-proof
                    [:required :maximum-large-to-small-p50-ratio])]
        (is (true?
             (get-in cross-backend-managed-proof
                     [:required :unchanged-target-proof])))
        (doseq [[_ {:keys [p50-ratio]}]
                (:observed cross-backend-managed-proof)]
          (is (<= p50-ratio maximum)))
        (is (= :passed
               (get-in release-performance-evaluation
                       [:dimensions :proof-operations :status])))))

    (testing "throughput and latency gates use measured wall-time evidence"
      (let [page-median
            (get-in final-heavy-run
                    [:multipath-page :max-page-median-ms])
            pages-per-second (/ 1000.0 page-median)
            minimum-throughput
            (get-in legacy-runtime
                    [:multipath-page
                     :minimum-threshold-throughput-pages-per-second])]
        (is (<= page-median
                (get-in legacy-runtime
                        [:multipath-page
                         :max-page-median-max-ms])))
        (is (>= pages-per-second minimum-throughput))
        (is (true?
             (get-in final-heavy-run
                     [:recursive-4000 :thresholds-met])))
        (is (= :passed
               (get-in release-performance-evaluation
                       [:dimensions :throughput :status])))))

    (testing "shared-subgraph gates are recomputed rather than trusted"
      (let [required (:required layered-subproblem-cache)
            observed
            (get-in layered-subproblem-cache
                    [:observed :current-rerun])]
        (is (<= (/ (:layered-backend-operations observed)
                   (:baseline-backend-operations observed))
                (:maximum-backend-work-ratio required)))
        (is (<= (:p50-latency-ratio observed)
                (:maximum-p50-latency-ratio required)))
        (is (<= (:new-generation-proof-reads observed)
                (:maximum-new-generation-proof-reads required)))
        (is (<= (:hot-hit-regression-ratio observed)
                (:maximum-hot-hit-regression-ratio required)))
        (is (<= (:cache-disabled-regression-ratio observed)
                (:maximum-cache-disabled-regression-ratio required)))))

    (testing "verification-time and generated-byte gates fail closed"
      (is (true? (:timeout-is-failure formal-pipeline)))
      (is (< (:last-observed-local-wall-seconds formal-pipeline)
             (:github-job-timeout-seconds formal-pipeline)))
      (is (= :deterministic-for-locked-toolchain-and-seed
             (:resource-assurance formal-pipeline)))
      (is (< (get-in formal-pipeline
                     [:maximum-observed-proof-effort :resource-count])
             (:proof-effort-resource-limit formal-pipeline)))
      (doseq [[_ {:keys [baseline-bytes maximum-bytes]}]
              (:artifacts generated-artifact-config)]
        (is (<= baseline-bytes maximum-bytes)))
      (is (= :passed
             (get-in release-performance-evaluation
                     [:dimensions :verification-time :status])))
      (is (= :passed
             (get-in release-performance-evaluation
                     [:dimensions :generated-artifact-size :status]))))

    (testing "noise rules require independent trials and robust summaries"
      (let [indexed
            (get-in generated-indexed-authority
                    [:traversal-scope-binding-recheck])
            merge-gates
            (map
             ordered-merge-source-specialization
             [:page-prefix-gate
              :complete-consumption-gate])]
        (is (= 5 (count (:trials indexed))))
        (is (= :passed (get-in indexed [:summary :status])))
        (doseq [gate merge-gates]
          (is (= 5 (get-in gate [:fixture :independent-trials])))
          (is (= :passed (:status gate)))
          (is (<= (:median-p95-ratio gate)
                  (get-in gate
                          [:required
                           :maximum-median-p95-ratio]))))
        (is (= 5
               (get-in layered-subproblem-cache
                       [:observed :repeated-runs])))
        (is (= :passed
               (get-in release-performance-evaluation
                       [:dimensions :benchmark-noise :status])))))))

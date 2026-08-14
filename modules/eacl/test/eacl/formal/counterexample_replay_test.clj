(ns eacl.formal.counterexample-replay-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :as test :refer [deftest is testing]]
            [eacl.test-support.repo :as repo]))

(def regression-vars
  '{:EACL-FORMAL-001
    eacl.bench.pagination-test/benchmark-seeders-initialize-empty-database-test
    :EACL-FORMAL-002
    eacl.datomic.lookup-cache-test/recursive-cursors-resume-from-the-client-private-denotation-test
    :EACL-FORMAL-003
    eacl.datomic.trusted-surface-audit-test/deleted-trusted-surfaces-stay-deleted-test
    :EACL-FORMAL-004
    eacl.datomic.cache-review-regressions-test/proofless-cursor-falls-back-to-exact-snapshot-test
    :EACL-FORMAL-005
    eacl.secure-format-test/portable-cursor-expiry-boundary-test
    :EACL-FORMAL-006
    eacl.secure-format-test/authenticated-cross-runtime-vectors-test
    :EACL-FORMAL-007
    eacl.secure-format-test/canonical-portable-format-test
    :EACL-FORMAL-008
    eacl.datomic.trusted-surface-audit-test/deleted-trusted-surfaces-stay-deleted-test
    :EACL-FORMAL-009
    eacl.formal.state-trace-differential-test/generated-cache-and-cursor-state-traces-across-jvm-adapters
    :EACL-FORMAL-010
    eacl.formal.state-trace-differential-test/generated-decisions-and-source-specialized-acyclic-paths-preserve-order-and-point-locality
    :EACL-FORMAL-011
    eacl.formal.state-trace-differential-test/recursive-generated-authority-covers-complete-public-results
    :EACL-FORMAL-012
    eacl.formal.production-kernel-test/generated-java-indexed-scan-response-boundary
    :EACL-FORMAL-013
    eacl.subproblem-cache-test/independent-identical-misses-never-wait-test
    :EACL-FORMAL-014
    eacl.datomic.trusted-surface-audit-test/deleted-trusted-surfaces-stay-deleted-test
    :EACL-FORMAL-015
    eacl.subproblem-cache-test/lifecycle-detachment-prevents-late-publication-test
    :EACL-FORMAL-016
    eacl.subproblem-cache-test/lookup-never-starts-work-test
    :EACL-FORMAL-017
    eacl.subproblem-cache-test/independent-identical-misses-never-wait-test
    :EACL-FORMAL-018
    eacl.subproblem-cache-test/lifecycle-detachment-prevents-late-publication-test
    :EACL-FORMAL-019
    eacl.backend.v8-test/descending-merge-retains-maximum-eid-test
    :EACL-FORMAL-020
    eacl.backend.v8-test/generic-merge-retains-nil-key-test
    :EACL-FORMAL-021
    eacl.datomic.config-test/shared-subproblem-cache-config-is-forwarded-and-validated-test
    :EACL-FORMAL-022
    eacl.formal.state-trace-differential-test/recursive-generated-authority-covers-complete-public-results
    :EACL-FORMAL-023
    eacl.formal.state-trace-differential-test/generated-stale-cursor-error-shape
    :EACL-FORMAL-024
    eacl.formal.production-kernel-test/generated-materialized-queue-limit-is-instantaneous
    :EACL-FORMAL-025
    eacl.formal.state-trace-differential-test/generated-queue-limit-is-query-local
    :EACL-FORMAL-026
    eacl.formal.state-trace-differential-test/generated-stale-cursor-error-shape
    :EACL-FORMAL-027
    eacl.backend.v8-test/runtime-guards-reject-negative-internal-eids-test
    :EACL-FORMAL-028
    eacl.characterization-fixture-test/quantitative-performance-gates-are-well-formed-test
    :EACL-FORMAL-029
    eacl.formal.counterexample-replay-test/counterexample-corpus-is-complete-and-closed-test
    :EACL-FORMAL-030
    eacl.formal.production-kernel-test/generated-java-checks-linear-routing-certificates
    :EACL-FORMAL-031
    eacl.characterization-fixture-test/quantitative-performance-gates-are-well-formed-test
    :EACL-FORMAL-032
    eacl.datascript.contract-test/one-authority-is-the-only-production-engine-test
    :EACL-FORMAL-033
    eacl.datascript.contract-test/one-authority-is-the-only-production-engine-test
    :EACL-FORMAL-034
    eacl.characterization-fixture-test/formal-cljs-smoke-preserves-persistent-nrepl-executors-test
    :EACL-FORMAL-035
    eacl.characterization-fixture-test/generated-java-boundary-is-reflection-free-test
    :EACL-FORMAL-036
    eacl.formal.production-kernel-test/generated-java-continues-pages-from-verified-lookahead
    :EACL-FORMAL-037
    eacl.datomic.impl.indexed-test/schema-cache-carries-shared-engine-analysis-test
    :EACL-FORMAL-038
    eacl.verified-kernel-test/routing-certificate-result-is-bound-to-its-input
    :EACL-FORMAL-039
    eacl.verified-kernel-test/routing-certificate-result-is-bound-to-its-input
    :EACL-FORMAL-040
    eacl.formal.production-kernel-test/production-jvm-two-stream-merge-refines-exact-source-model
    :EACL-FORMAL-041
    eacl.datascript.contract-test/one-authority-is-the-only-production-engine-test
    :EACL-FORMAL-042
    eacl.datascript.contract-test/one-authority-is-the-only-production-engine-test
    :EACL-FORMAL-043
    eacl.formal.state-trace-differential-test/generated-decisions-and-source-specialized-acyclic-paths-preserve-order-and-point-locality
    :EACL-FORMAL-044
    eacl.datomic.recursive-cache-test/recursive-cursor-falls-back-to-exact-snapshot-after-relevant-write-test
    :EACL-FORMAL-045
    eacl.formal.java-round-trip-test/generated-java-persistent-collection-boundary
    :EACL-FORMAL-046
    eacl.engine.stable-discovery-gate-test/fingerprint-is-invariant-under-schema-clause-order-test
    :EACL-FORMAL-047
    eacl.datascript.keyset-recursion-test/order-perturbing-write-rejects-current-only-cursor-test
    :EACL-FORMAL-048
    eacl.characterization-fixture-test/formal-ci-isolates-and-stops-performance-nrepls-test
    :EACL-FORMAL-049
    eacl.characterization-fixture-test/quantitative-performance-gates-are-well-formed-test
    :EACL-FORMAL-050
    eacl.formal.state-trace-differential-test/generated-can-reuses-the-public-root-classification
    :EACL-FORMAL-051
    eacl.characterization-fixture-test/quantitative-performance-gates-are-well-formed-test
    :EACL-FORMAL-052
    eacl.datascript.consistency-v3-test/map-can-rejects-malformed-consistency-test
    :EACL-FORMAL-053
    eacl.consistency-test/public-consistency-descriptors-reject-unknown-fields-test
    :EACL-FORMAL-054
    eacl.datascript.consistency-v3-test/immutable-adapter-does-not-claim-authoritative-head-test
    :EACL-FORMAL-055
    eacl.formal.state-trace-differential-test/generated-decisions-and-source-specialized-acyclic-paths-preserve-order-and-point-locality
    :EACL-FORMAL-056
    eacl.datascript.impl-test/read-relationships-query-matrix-test
    :EACL-FORMAL-057
    eacl.formal.page-window-bridge-test/generated-page-normalization-and-window-properties
    :EACL-FORMAL-058
    eacl.formal.counterexample-replay-test/replay-entrypoint-does-not-eagerly-load-formal-only-oracles-test
    :EACL-FORMAL-059
    eacl.formal.counterexample-replay-test/clean-generated-javascript-contract-expectations-test
    :EACL-FORMAL-060
    eacl.engine.physical-route-test/exhaustion-count-test
    :EACL-FORMAL-061
    eacl.engine.stable-page-test/lookahead-survives-checkpointing-test
    :EACL-FORMAL-062
    eacl.engine.stable-discovery-gate-test/independent-oracle-equality-test
    :EACL-FORMAL-063
    eacl.engine.stable-discovery-gate-test/independent-oracle-equality-test
    :EACL-FORMAL-064
    eacl.engine.stable-page-test/page-composition-test
    :EACL-FORMAL-065
    eacl.engine.stable-reducer-test/interior-admission-keys-are-node-qualified-test
    :EACL-FORMAL-066
    eacl.engine.stable-reducer-test/frozen-baseline-denotation-differential-test
    :EACL-FORMAL-067
    eacl.datomic.recursive-cache-test/recursive-page-order-is-stable-across-scan-wave-boundaries-test})

(defn- read-edn
  [path]
  (edn/read-string (slurp path)))

(defn- schema-match?
  [schema value]
  (cond
    (= :keyword schema) (keyword? value)
    (= :integer schema) (integer? value)
    (= :string schema) (string? value)
    (= :relative-path schema)
    (and (string? value)
         (not (.isAbsolute (io/file value)))
         (not-any? #(= ".." (str %))
                   (.toPath (io/file value))))
    (set? schema) (contains? schema value)
    (and (vector? schema) (= :or (first schema)))
    (some #(schema-match? % value) (rest schema))
    (and (vector? schema) (= :vector (first schema)))
    (and (vector? value)
         (every? #(schema-match? (second schema) %) value))
    :else false))

(defn- entry-files
  []
  (->> (file-seq (repo/file "formal" "counterexamples"))
       (filter #(.isFile %))
       (filter #(= "entry.edn" (.getName %)))
       (sort-by #(.getPath %))))

(defn- available-regression
  [test-symbol]
  (when (repo/evidence-namespace-available? test-symbol)
    ;; The corpus lives in the common test tree, while generated-boundary
    ;; regressions are present only under the formal-smoke alias. A normal
    ;; module test nREPL must skip those unavailable namespaces rather than
    ;; failing merely because the repository file exists outside its classpath.
    (try
      (let [test-var (requiring-resolve test-symbol)]
        (when-not (var? test-var)
          (throw
           (ex-info
            "A recorded counterexample regression var is missing."
            {:type :eacl.formal/missing-counterexample-regression
             :test-symbol test-symbol})))
        test-var)
      (catch java.io.FileNotFoundException _
        nil))))

(deftest replay-entrypoint-does-not-eagerly-load-formal-only-oracles-test
  (let [entrypoint (slurp (repo/file "bin" "formal"))]
    (doseq [test-only-ns
            ["eacl.lazy-merge-sort"
             "eacl.backend.spi"
             "eacl.engine.indexed"]]
      (is (not (re-find
                (re-pattern
                 (str
                  "\\(require '"
                  (java.util.regex.Pattern/quote test-only-ns)
                  " :reload\\)"))
                entrypoint))
          (str
           "the ordinary replay classpath must not eagerly load test-only "
           test-only-ns)))))

(deftest clean-generated-javascript-contract-expectations-test
  (let [page-source
        (slurp
         (repo/file
          "formal" "smoke" "cljs" "eacl" "formal"
          "js_round_trip_test.cljs"))
        production-source
        (slurp
         (repo/file
          "formal" "smoke" "cljs" "eacl" "formal"
          "production_kernel_test.cljs"))
        vectors
        (read-edn
         (repo/file "formal" "cross-runtime" "vectors.edn"))]
    (is (not (re-find #"\(contains\? request :(?:limit|cursor)\)"
                      page-source))
        "the generated RawPageRequest smoke must use only current v8 fields")
    (is (= {:first-page [10 30]
            :continuation-page [20]}
           (:production-recursive-pages vectors)))
    (is (re-find
         #":production-recursive-pages \(cross-runtime-vectors\)"
         production-source)
        "CLJS recursive pages must consume the shared JVM/JS vector")))

(deftest counterexample-corpus-is-complete-and-closed-test
  (let [schema (read-edn
                (repo/file
                 "formal" "counterexamples" "ledger-schema.edn"))
        required (set (keys (:required schema)))
        entries
        (mapv
         (fn [file]
           (let [entry (read-edn file)
                 directory (.getName (.getParentFile file))]
             (is (= required
                    (set (filter #(contains? entry %) required)))
                 (.getPath file))
             (is (= (keyword directory) (:id entry)))
             (is (= :fixed (:status entry)))
             (is (seq (:closing-evidence entry)))
             (doseq [[field field-schema] (:required schema)]
               (is (schema-match? field-schema (get entry field))
                   (str (.getPath file) " invalid " field)))
             (doseq [[field field-schema] (:optional schema)
                     :when (contains? entry field)]
               (is (schema-match? field-schema (get entry field))
                   (str (.getPath file) " invalid optional " field)))
             (doseq [artifact ["fixture.edn" "expected.edn" "README.md"]]
               (is (.isFile
                    (io/file (.getParentFile file) artifact))
                   (str directory "/" artifact)))
             entry))
         (entry-files))
        manifest (read-edn
                  (repo/file "formal" "verification" "manifest.edn"))
        revision (:counterexample-corpus-revision manifest)]
    (is (= (set (keys regression-vars))
           (set (map :id entries))
           (set (:fixed revision))))
    (is (= :EACL-FORMAL-067 (:latest revision)))
    (is (= 67 (count entries)))))

(deftest replay-every-minimized-regression-test
  (let [available
        (keep
         (fn [[bug-id test-symbol]]
           (when-let [test-var (available-regression test-symbol)]
             [bug-id test-symbol test-var]))
         regression-vars)]
    (is (seq available)
        "each isolated classpath must expose some closing regressions")
    (doseq [[bug-id test-symbol test-var] available]
      (testing (name bug-id)
        (is (var? test-var) (str "missing replay " test-symbol))
        (test/test-var test-var)))))

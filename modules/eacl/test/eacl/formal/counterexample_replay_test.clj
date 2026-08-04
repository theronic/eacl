(ns eacl.formal.counterexample-replay-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :as test :refer [deftest is testing]]
            [eacl.test-support.repo :as repo]))

(def regression-vars
  '{:EACL-FORMAL-001
    eacl.bench.pagination-test/benchmark-seeders-initialize-empty-database-test
    :EACL-FORMAL-002
    eacl.datomic.lookup-cache-test/recursive-cursors-resume-private-continuations-within-client-test
    :EACL-FORMAL-003
    eacl.datomic.cache-test/authenticated-store-preserves-logical-kind-test
    :EACL-FORMAL-004
    eacl.datomic.cache-review-regressions-test/proofless-cursor-recovers-on-current-snapshot-test
    :EACL-FORMAL-005
    eacl.secure-format-test/portable-cursor-expiry-boundary-test
    :EACL-FORMAL-006
    eacl.secure-format-test/authenticated-cross-runtime-vectors-test
    :EACL-FORMAL-007
    eacl.secure-format-test/canonical-portable-format-test
    :EACL-FORMAL-008
    eacl.cache-test/proof-provider-failure-fails-closed-test
    :EACL-FORMAL-009
    eacl.formal.state-trace-differential-test/generated-cache-and-cursor-state-traces-across-jvm-adapters
    :EACL-FORMAL-010
    eacl.formal.state-trace-differential-test/generated-mode-does-not-reorder-acyclic-multipath-pages
    :EACL-FORMAL-011
    eacl.formal.state-trace-differential-test/recursive-shadow-compares-complete-public-results
    :EACL-FORMAL-012
    eacl.formal.production-kernel-test/generated-java-indexed-scan-response-boundary
    :EACL-FORMAL-013
    eacl.subproblem-cache-test/recursive-lookup-of-own-flight-is-a-miss-test
    :EACL-FORMAL-014
    eacl.subproblem-cache-test/inherited-same-key-self-bypass-acquires-a-child-slot-test
    :EACL-FORMAL-015
    eacl.subproblem-cache-test/lifecycle-selection-is-linearized-before-recursive-binding-test
    :EACL-FORMAL-016
    eacl.subproblem-cache-test/authoritative-lookup-action-precedes-storage-mutation-test
    :EACL-FORMAL-017
    eacl.subproblem-cache-test/cache-unadmitted-fallbacks-still-share-one-flight-test
    :EACL-FORMAL-018
    eacl.subproblem-cache-test/flight-removal-serializes-with-lifecycle-selection-test
    :EACL-FORMAL-019
    eacl.backend.v8-test/descending-merge-retains-maximum-eid-test
    :EACL-FORMAL-020
    eacl.backend.v8-test/generic-merge-retains-nil-key-test
    :EACL-FORMAL-021
    eacl.datomic.config-test/shared-subproblem-cache-config-is-forwarded-and-validated-test})

(defn- read-edn
  [path]
  (edn/read-string (slurp path)))

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
      (requiring-resolve test-symbol)
      (catch java.io.FileNotFoundException _
        nil))))

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
    (is (= :EACL-FORMAL-021 (:latest revision)))
    (is (= 21 (count entries)))))

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

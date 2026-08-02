(ns eacl.formal.counterexample-replay-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :as test :refer [deftest is testing]]))

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
    eacl.cache-test/proof-provider-failure-fails-closed-test})

(defn- read-edn
  [path]
  (edn/read-string (slurp path)))

(defn- entry-files
  []
  (->> (file-seq (io/file "formal/counterexamples"))
       (filter #(.isFile %))
       (filter #(= "entry.edn" (.getName %)))
       (sort-by #(.getPath %))))

(deftest counterexample-corpus-is-complete-and-closed-test
  (let [schema (read-edn "formal/counterexamples/ledger-schema.edn")
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
        manifest (read-edn "formal/verification/manifest.edn")
        revision (:counterexample-corpus-revision manifest)]
    (is (= (set (keys regression-vars))
           (set (map :id entries))
           (set (:fixed revision))))
    (is (= :EACL-FORMAL-008 (:latest revision)))
    (is (= 8 (count entries)))))

(deftest replay-every-minimized-regression-test
  (doseq [[bug-id test-symbol] regression-vars]
    (testing (name bug-id)
      (let [test-var (requiring-resolve test-symbol)]
        (is (var? test-var) (str "missing replay " test-symbol))
        (test/test-var test-var)))))

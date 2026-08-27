(ns eacl.formal.derived-metrics-cache-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [eacl.test-support.repo :as repo])
  (:import (java.math BigInteger)
           (java.security MessageDigest)))

(def report-file
  (repo/file "formal" "verification" "derived-metrics-cache.edn"))

(defn- report []
  (edn/read-string (slurp report-file)))

(defn- sha256 [file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (java.io.BufferedInputStream.
                      (java.io.FileInputStream. file))]
      (let [buffer (byte-array 16384)]
        (loop []
          (let [read (.read input buffer)]
            (when-not (= -1 read)
              (.update digest buffer 0 read)
              (recur))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(deftest derived-metrics-evidence-is-fail-closed-test
  (let [{:keys [status authority cache-contract migration performance]}
        (report)]
    (is (= :passed status))
    (is (false? (:durable-derived-metrics authority)))
    (is (false? (:durable-redundant-expression-fields authority)))
    (is (false? (:durable-admission-limits authority)))
    (is (= :immutable-client-configuration
           (:admission-limit-authority authority)))
    (is (= [:client :schema-generation :authoritative-expression-fields
            :effective-expression-limits]
           (:structural-partition cache-contract)))
    (is (= :none (:semantic-plan-influence authority)))
    (is (= :none (:authorization-result-influence authority)))
    (is (zero? (get-in cache-contract [:default-refresh :backend-reads])))
    (is (= 4096 (:maximum-relationship-entries cache-contract)))
    (is (zero? (:relationship-index-reads migration)))
    (is (true? (:relationship-digest-identical migration)))
    (is (<= (get-in performance
                    [:datascript-exact-count-metrics-overhead
                     :active-to-disabled-ratio])
            (get-in performance
                    [:datascript-exact-count-metrics-overhead
                     :release-ceiling-ratio])))
    (is (true? (get-in performance
                       [:datahike-loopback-minio
                        :all-existing-numeric-ceilings-passed])))))

(deftest derived-metrics-source-digests-are-current-test
  (doseq [[path expected] (:source-sha256 (report))]
    (testing path
      (let [file (repo/file path)]
        (is (.isFile file))
        (is (= expected (sha256 file)))))))

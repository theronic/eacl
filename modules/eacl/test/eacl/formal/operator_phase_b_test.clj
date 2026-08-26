(ns eacl.formal.operator-phase-b-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [eacl.test-support.repo :as repo])
  (:import (java.math BigInteger)
           (java.security MessageDigest)))

(def report-file
  (repo/file "formal" "verification" "operator-phase-b.edn"))

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

(deftest production-refinement-report-is-complete-test
  (let [{:keys [status proof-runs executable-runs bindings assurance]}
        (report)]
    (is (= :passed status))
    (is (every? #(= :passed (:status %)) proof-runs))
    (is (every? #(= :passed (:status %)) executable-runs))
    (is (every? zero? (mapcat (juxt :failures :errors)
                              executable-runs)))
    (is (= #{:parser-codec-signed-graph :plan :acyclic-evaluation
             :recursive-evaluation :backend-premises}
           (set (keys bindings))))
    (is (= :executable-differential-mutation-and-digest-closed
           (:production-source-refinement assurance)))
    (is (= :withheld-until-performance-and-release-gates
           (:public-operator-routing assurance)))))

(deftest production-refinement-source-digests-are-current-test
  (doseq [[path expected] (:source-digests (report))]
    (testing path
      (let [file (repo/file path)]
        (is (.isFile file))
        (is (= expected (sha256 file)))))))

(ns eacl.performance.evidence-test
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [eacl.performance.evidence :as evidence]
            [eacl.test-support.repo :as repo]))

(defn- read-edn
  [path]
  (edn/read-string (slurp path)))

(defn- fixture
  [filename]
  (repo/file "docs" "benchmarks" "results"
             "2026-08-29-eacl-performance-amplification"
             filename))

(def ^:private frozen-release-acceptance-digest
  "8eba9de7d062bc39f1c89bd462f18b003422994fe5b5ce80f3582a011552a695")

(deftest golden-evidence-is-deterministic-test
  (let [acceptance (read-edn (fixture "evidence-acceptance.edn"))
        observed (read-edn (fixture "evidence-golden.edn"))
        report (evidence/analyze acceptance observed)
        expected (read-edn (fixture "evidence-golden-report.edn"))]
    (is (= expected report))
    (is (= (:report-digest report)
           (evidence/sha256 (dissoc report :report-digest))))))

(deftest malformed-evidence-fails-closed-test
  (let [acceptance (read-edn (fixture "evidence-acceptance.edn"))
        valid (read-edn (fixture "evidence-golden.edn"))
        failure-reason
        (fn [candidate]
          (try
            (evidence/analyze acceptance candidate)
            nil
            (catch clojure.lang.ExceptionInfo error
              (:reason (ex-data error)))))]
    (testing "empty and non-finite samples"
      (is (= :invalid-samples
             (failure-reason
              (assoc-in valid [:metrics :elapsed :samples] []))))
      (is (= :invalid-samples
             (failure-reason
              (assoc-in valid [:metrics :elapsed :samples]
                        [Double/POSITIVE_INFINITY])))))
    (testing "unsupported is not encoded as zero"
      (is (= :invalid-unsupported-metric
             (failure-reason
              (assoc-in valid [:metrics :optional :allocated-bytes]
                        {:status :unsupported
                         :reason :runtime-unavailable
                         :samples [0]})))))
    (testing "raw and identity changes invalidate evidence"
      (is (= :raw-digest-mismatch
             (failure-reason (update valid :raw conj {:sample 4}))))
      (is (= :incomplete-identity
             (failure-reason
              (update valid :identity dissoc :operation-boundary))))
      (is (= :identity-mismatch
             (failure-reason
              (assoc-in valid [:identity :source :commit] "other")))))))

(deftest volatile-host-fields-do-not-enter-canonical-report-test
  (let [acceptance (read-edn (fixture "evidence-acceptance.edn"))
        valid (read-edn (fixture "evidence-golden.edn"))
        changed (-> valid
                    (assoc-in [:identity :environment :captured-at] "later")
                    (assoc-in [:identity :environment :pid] 99999)
                    (assoc-in [:identity :source :worktree-path] "/tmp/other"))]
    (is (= (evidence/analyze acceptance valid)
           (evidence/analyze acceptance changed)))))

(deftest metric-capability-records-are-complete-test
  (let [document (read-edn (fixture "metric-capabilities.edn"))
        records (:records document)
        tuples
        (mapv (fn [record]
                [(:metric record)
                 (get-in record [:instrument :id])
                 (get-in record [:instrument :runtime])
                 (get-in record [:instrument :version])])
              records)]
    (doseq [record records]
      (is (= record (evidence/validate-metric-capability! record))))
    (is (= (count tuples) (count (distinct tuples))))
    (is (some #(and (= :elapsed-nanos (:metric %))
                    (= :supported (:status %)))
              records))
    (is (some #(and (= :mandatory-core-counters (:metric %))
                    (= :supported (:status %)))
              records))))

(deftest every-canonical-decision-mutation-breaks-the-golden-test
  (let [acceptance (read-edn (fixture "evidence-acceptance.edn"))
        valid (read-edn (fixture "evidence-golden.edn"))
        expected (evidence/analyze acceptance valid)
        raw-mutant (update valid :raw conj {:sample 4})
        mutants
        {:phase (assoc valid :phase :baseline)
         :identity (assoc-in valid [:identity :runtime :jvm] "mutated-jvm")
         :raw-digest
         (assoc raw-mutant :raw-digest
                (evidence/raw-digest (:raw raw-mutant)))
         :elapsed
         (assoc-in valid [:metrics :elapsed :samples] [31 10 20])
         :mandatory
         (assoc-in valid [:metrics :mandatory :commands :samples] [4 3 3])
         :optional
         (assoc-in valid [:metrics :optional :allocated-bytes :samples]
                   [301 100 200])}]
    (doseq [[field mutant] mutants]
      (is (not= expected (evidence/analyze acceptance mutant))
          (str "canonical field mutation survived: " field)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (evidence/analyze acceptance
                                   (assoc valid :format :unknown))))))

(deftest every-retained-mechanism-has-a-multiscale-adversarial-fixture-test
  (let [ledger (read-edn (fixture "mechanism-ledger.edn"))
        coverage (read-edn (fixture "mechanism-fixture-coverage.edn"))
        outcomes (:outcomes ledger)
        outcome-ids (apply set/union (vals outcomes))
        expected-outcomes
        (group-by
         (fn [{:keys [disposition]}]
           (case disposition
             :reproduced :implemented
             :refuted :refuted
             :correctness-required :correctness-required))
         (:rows ledger))
        retained (->> (:rows ledger)
                      (remove #(= :refuted (:disposition %)))
                      (map :id)
                      set)
        rows (:rows coverage)
        covered (set (map :id rows))]
    (is (= (set (map :id (:rows ledger))) outcome-ids))
    (is (= (set (keys outcomes))
           #{:implemented :refuted :correctness-required}))
    (doseq [[outcome mechanism-ids] outcomes]
      (is (= mechanism-ids
             (set (map :id (get expected-outcomes outcome))))
          (str "incorrect final mechanism outcome: " outcome)))
    (is (= retained covered))
    (is (= (count rows) (count covered)))
    (doseq [{:keys [id generator scale-points model tolerance]} rows]
      (is (keyword? generator) (str id " lacks a generator"))
      (is (and (vector? scale-points)
               (<= 3 (count scale-points))
               (= (count scale-points) (count (distinct scale-points)))
               (every? pos-int? scale-points))
          (str id " lacks three distinct positive scale points"))
      (is (keyword? model) (str id " lacks a named model"))
      (is (and (map? tolerance) (seq tolerance))
          (str id " lacks an acceptance tolerance")))))

(deftest paired-confidence-is-seeded-directional-and-threshold-sensitive-test
  (let [options {:effect evidence/latency-reduction
                 :confidence 0.95
                 :resamples 1000
                 :seed 87231}
        baseline [100.0 110.0 90.0 105.0 95.0]
        faster [70.0 77.0 63.0 73.5 66.5]
        result (evidence/paired-confidence baseline faster options)]
    (is (= result (evidence/paired-confidence baseline faster options)))
    (is (< 0.20 (:lower result)))
    (is (< (Math/abs (- 0.30 (:estimate result))) 1.0e-12))
    (is (<= (:lower result) (:estimate result) (:upper result)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (evidence/paired-confidence [] [] options)))))

(deftest release-acceptance-is-frozen-before-candidate-evidence-test
  (let [record (read-edn (fixture "release-acceptance.edn"))]
    (is (= record (evidence/validate-release-acceptance! record)))
    (is (= frozen-release-acceptance-digest (:record-digest record)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (evidence/validate-release-acceptance!
                  (assoc-in record [:release-win :id] :substituted-lane))))))

(deftest baseline-supported-resource-lane-cannot-become-unsupported-test
  (let [baseline {:status :supported
                  :outcome :eacl.execution/resource-limit-exceeded}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid EACL performance evidence"
         (evidence/validate-lane-support!
          baseline {:status :unsupported :reason :candidate-claimed-absence})))
    (is (= {:status :supported :outcome :same-typed-failure}
           (evidence/validate-lane-support!
            baseline {:status :supported :outcome :same-typed-failure})))))

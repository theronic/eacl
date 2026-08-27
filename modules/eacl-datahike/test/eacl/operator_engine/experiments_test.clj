(ns eacl.operator-engine.experiments-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [eacl.datahike.direct-membership :as datahike-direct]
            [eacl.operator-engine.experiments :as experiments]
            [eacl.test-support.repo :as repo]))

(defn- evidence [path]
  (edn/read-string (slurp (repo/file path))))

(deftest deterministic-operator-experiments-self-check-test
  (let [summary
        (experiments/run-deterministic
         {:cover-trials 2000
          :anchor-trials 500
          :recursive-anchor-trials 250})]
    (is (true? (get-in summary [:cover :passed?])))
    (is (zero? (get-in summary [:anchor-gated :result-failures])))
    (is (< (get-in summary [:anchor-gated :anchor-gated-states])
           (get-in summary [:anchor-gated :any-child-states])))
    (is (zero? (get-in summary
                       [:recursive-anchor-gated :result-failures])))
    (is (zero? (get-in summary
                       [:recursive-anchor-gated
                        :arrival-order-failures])))
    (is (zero? (get-in summary
                       [:recursive-anchor-gated
                        :retained-state-failures])))
    (is (< (get-in summary
                   [:recursive-anchor-gated :anchor-parent-slots])
           (get-in summary
                   [:recursive-anchor-gated :any-child-parent-slots])))
    (is (= 21 (get-in summary
                      [:adaptive-batching :all-accepted :adaptive
                       :physical-candidates])))
    (is (= 256 (get-in summary
                       [:adaptive-batching :all-accepted :fixed-width
                        :physical-candidates])))
    (is (= (range 0 21000 1000)
           (get-in summary [:leapfrog :leapfrog :results])))
    (is (zero? (get-in summary
                       [:k-way-leapfrog :random-result-failures])))
    (is (zero? (get-in summary
                       [:k-way-leapfrog :random-bound-failures])))
    (is (= {:anchor-rounds 0
            :driver-lower-bound-seeks 0
            :operand-lower-bound-seeks 0
            :lower-bound-comparisons 0
            :accepted 0}
           (get-in summary [:k-way-leapfrog :zero-demand])))
    (is (= {:anchor-rounds 1
            :driver-lower-bound-seeks 0
            :operand-lower-bound-seeks 1
            :lower-bound-comparisons 1
            :accepted 0}
           (get-in summary [:k-way-leapfrog :early-exhaustion])))
    (is (true? (get-in summary
                       [:k-way-leapfrog :adversarial :equal-results?])))
    (is (< (get-in summary
                   [:k-way-leapfrog :adversarial :k-way
                    :anchor-rounds])
           (get-in summary
                   [:k-way-leapfrog :adversarial :sequential-binary
                    :head-comparisons])))
    (is (= 21 (get-in summary
                      [:k-way-leapfrog :adversarial :bounded-k-way
                       :accepted])))
    (is (= (range 0 21000 1000)
           (get-in summary
                   [:k-way-leapfrog :adversarial :bounded-k-way
                    :results])))
    (is (= 20000 (get-in summary [:memoization :saved-leaf-probes])))))

(deftest strategy-matrix-covers-dimensional-performance-risks-test
  (let [summary (experiments/strategy-matrix-experiment)]
    (is (= 108 (:intersection-cases summary)))
    (is (= 12 (:exclusion-cases summary)))
    (is (true? (get-in summary [:acceptance :no-eager-selection])))
    (is (true? (get-in summary
                       [:acceptance :all-adaptive-bounded?])))
    (is (true? (get-in summary
                       [:acceptance :warm-physical-predicates-zero?])))
    (is (every? #(<= (get-in % [:leapfrog-galloping :accepted])
                     (get-in % [:dimensions :page-demand]))
                (:intersections summary)))
    (is (every? #(<= (get-in % [:anti-join :accepted])
                     (get-in % [:dimensions :page-demand]))
                (:exclusions summary)))))

(deftest checked-performance-evidence-remains-inside-accepted-gates-test
  (let [performance
        (evidence "exploration/operator-engine/performance-qualification.edn")
        minio
        (evidence "exploration/operator-engine/minio-qualification.edn")
        union-results
        (vals (get-in performance
                      [:union-only :median-of-campaign-medians]))
        ceilings (:accepted-ceilings minio)
        bounded-pages
        (map #(get-in minio [:observed %])
             [:intersection :dense-exclusion :sparse-exclusion :arrow])]
    (is (= :accepted (:status performance)))
    (is (= :accepted (:status minio)))
    (is (every? #(<= (:latency-delta-percent %) 5.0) union-results))
    (is (every? #(<= (:allocation-delta-percent %) 5.0) union-results))
    (is (true? (get-in performance
                       [:union-only :acceptance :all-work-counters-equal])))
    (is (= datahike-direct/physical-policy-identity
           (get-in performance
                   [:datahike-physical-policy :accepted-identity])))
    (doseq [page bounded-pages]
      (is (<= (get-in page [:cold :gets])
              (:cold-bounded-page-index-gets ceilings)))
      (is (zero? (get-in page [:warm :gets])))
      (is (<= (get-in page [:adjacent :gets])
              (:adjacent-page-index-gets ceilings)))
      (is (<= (get-in page [:cold :allocated-bytes])
              (:cold-bounded-page-allocated-bytes ceilings)))
      (is (<= (get-in page [:cold :latency-nanos])
              (:cold-bounded-page-latency-nanos ceilings))))
    (is (<= (get-in minio [:observed :bounded-count :gets])
            (:bounded-count-index-gets ceilings)))
    (is (<= (get-in minio [:observed :exact-count :gets])
            (:exact-count-index-gets ceilings)))
    (is (= [:exact-count]
           (get-in minio [:measurement-contract :exhaustive])))
    (is (false? (get-in minio
                        [:measurement-contract
                         :blend-bounded-and-exhaustive])))
    (is (= :sparse-exact
           (get-in minio [:multiplier-neighborhood 4 :selected])))
    (is (= :bounded-prefix
           (get-in minio [:multiplier-neighborhood 2 :selected])))))

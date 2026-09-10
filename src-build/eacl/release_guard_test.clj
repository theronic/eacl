(ns eacl.release-guard-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [eacl.release-guard :as guard]))

(def ordinary-context
  {:event-name "push"
   :ref "refs/heads/v8.1.0"
   :ref-type "branch"
   :sha "abc123"
   :branch-sha "abc123"
   :supplied-version nil})

(deftest versioned-branch-derives-an-immutable-version
  (is (= "8.1.0" (guard/ordinary-version ordinary-context)))
  (is (= "8.0.0-SNAPSHOT"
         (guard/ordinary-version
          (assoc ordinary-context :ref "refs/heads/v8.0.0-SNAPSHOT"))))
  (doseq [context
          [(assoc ordinary-context :ref "refs/heads/main")
           (assoc ordinary-context :ref "refs/heads/release/v8.0")
           (assoc ordinary-context :ref "refs/heads/feature/v8.1.0")
           (assoc ordinary-context :ref "refs/heads/v8.0-SNAPSHOT")
           (assoc ordinary-context :ref "refs/tags/v8.1.0"
                  :ref-type "tag")
           (assoc ordinary-context :event-name "pull_request")
           (assoc ordinary-context :supplied-version "8.1.0")
           (assoc ordinary-context :branch-sha "newer")]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (guard/ordinary-version context)))))

(deftest checks-are-scoped-to-a-branch-ref
  (is (= "v8.1.0" (guard/branch-name "refs/heads/v8.1.0")))
  (is (= "release/v8.0" (guard/branch-name "refs/heads/release/v8.0")))
  (doseq [ref ["refs/tags/v8.1.0" "refs/pull/189/merge" "" nil]]
    (is (thrown? clojure.lang.ExceptionInfo (guard/branch-name ref)))))

(def check-context
  {:sha "release-sha" :branch "v8.1.0"})

(def tests-suite 11)
(def formal-suite 12)

(defn- workflow-run
  [event branch sha suite]
  {:event event
   :head_branch branch
   :head_sha sha
   :check_suite_id suite})

(def release-workflow-runs
  [(workflow-run "push" "v8.1.0" "release-sha" tests-suite)
   (workflow-run "push" "v8.1.0" "release-sha" formal-suite)])

(def formal-check-names
  #{"dafny-and-generated-boundaries"
    "temporal-models"
    "parity-corpus-and-mutations"})

(defn- check-run
  ([name sha suite]
   (check-run name sha suite "completed" "success"))
  ([name sha suite status conclusion]
   {:name name
    :head_sha sha
    :status status
    :conclusion conclusion
    :check_suite {:id suite}}))

(defn- successful-checks
  [sha]
  (mapv
   (fn [name]
     (check-run name sha (if (formal-check-names name) formal-suite tests-suite)))
   (sort guard/required-checks)))

(deftest only-the-release-branch-push-runs-are-release-evidence
  (is (= #{tests-suite formal-suite}
         (guard/release-check-suites check-context release-workflow-runs)))
  (testing "pull-request, other-branch, other-commit, and suite-less runs are ignored"
    (is (= #{tests-suite}
           (guard/release-check-suites
            check-context
            [(workflow-run "push" "v8.1.0" "release-sha" tests-suite)
             (workflow-run "pull_request" "v8.1.0" "release-sha" 21)
             (workflow-run "push" "main" "release-sha" 22)
             (workflow-run "push" "v8.1.0" "older-sha" 23)
             (workflow-run "workflow_dispatch" "v8.1.0" "release-sha" 24)
             (dissoc (workflow-run "push" "v8.1.0" "release-sha" 25)
                     :check_suite_id)])))))

(deftest same-commit-results-from-other-refs-do-not-make-the-gate-ambiguous
  (let [sha "release-sha"
        checks (successful-checks sha)
        pull-request-suite 21
        main-suite 22
        foreign-runs
        (into release-workflow-runs
              [(workflow-run "pull_request" "v8.1.0" sha pull-request-suite)
               (workflow-run "push" "main" sha main-suite)])
        foreign-checks
        (into checks
              (concat
               ;; The pull-request event verified a synthetic merge commit.
               (map #(assoc % :check_suite {:id pull-request-suite}) checks)
               ;; A same-repo pull request skips its duplicate test job.
               [(check-run "test" sha pull-request-suite "completed" "skipped")]
               ;; main was pushed to the same commit and one of its jobs failed.
               (map #(assoc % :check_suite {:id main-suite}) checks)
               [(check-run "test" sha main-suite "completed" "failure")]))]
    (is (= :ready (guard/evaluate-checks check-context foreign-runs foreign-checks false)))
    (is (= :ready (guard/evaluate-checks check-context foreign-runs foreign-checks true)))
    (testing "the foreign results also never stand in for absent release evidence"
      (is (= :pending
             (guard/evaluate-checks
              check-context foreign-runs
              (remove #(= tests-suite (get-in % [:check_suite :id]))
                      foreign-checks)
              false))))))

(deftest exact-sha-check-gate-rejects-every-ambiguous-state
  (let [sha "release-sha"
        checks (successful-checks sha)]
    (is (= :ready (guard/evaluate-checks check-context release-workflow-runs checks false)))
    (is (= :pending
           (guard/evaluate-checks check-context release-workflow-runs (pop checks) false)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (guard/evaluate-checks check-context release-workflow-runs (pop checks) true)))
    (testing "release evidence is pending until the branch push runs exist"
      (is (= :pending (guard/evaluate-checks check-context [] checks false)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (guard/evaluate-checks check-context [] checks true)))
      (is (= :pending
             (guard/evaluate-checks
              (assoc check-context :branch "v8.2.0") release-workflow-runs checks false))))
    (testing "a duplicate inside the release branch's own runs stays ambiguous"
      (is (thrown?
           clojure.lang.ExceptionInfo
           (guard/evaluate-checks
            check-context release-workflow-runs (conj checks (first checks)) false))))
    (doseq [changed [(assoc-in checks [0 :head_sha] "wrong-sha")
                     (assoc-in checks [0 :status] "in_progress")
                     (assoc-in checks [0 :conclusion] "failure")
                     (assoc-in checks [0 :conclusion] "cancelled")
                     (assoc-in checks [0 :conclusion] "timed_out")
                     (assoc-in checks [0 :conclusion] "skipped")]]
      (testing (str "rejected check state " (first changed))
        (if (= "in_progress" (:status (first changed)))
          (is (thrown? clojure.lang.ExceptionInfo
                       (guard/evaluate-checks
                        check-context release-workflow-runs changed true)))
          (is (thrown? clojure.lang.ExceptionInfo
                       (guard/evaluate-checks
                        check-context release-workflow-runs changed false))))))))

(deftest truncated-github-listings-are-never-judged
  (is (= [{:id 1} {:id 2}]
         (guard/listing {:total_count 2 :check_runs [{:id 1} {:id 2}]}
                        :check_runs)))
  (is (= [] (guard/listing {:total_count 0 :workflow_runs []} :workflow_runs)))
  (is (thrown? clojure.lang.ExceptionInfo
               (guard/listing {:total_count 3 :check_runs [{:id 1} {:id 2}]}
                              :check_runs))))

(deftest release-workflow-correlates-branch-push-runs
  (let [workflow (slurp ".github/workflows/release.yml")]
    (is (string/includes?
         workflow
         "actions/runs?head_sha=$GITHUB_SHA&event=push&branch=$GITHUB_REF_NAME"))
    (is (string/includes? workflow "commits/$GITHUB_SHA/check-runs"))
    (is (string/includes?
         workflow
         "clojure -M:release-guard checks target/release/workflow-runs.json target/release/check-runs.json"))))

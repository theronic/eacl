(ns eacl.release-guard-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [eacl.release-guard :as guard]))

(def tag-context
  {:event-name "push"
   :ref "refs/tags/v8.0.0-RC-2026-09-12"
   :ref-type "tag"})

(deftest only-explicit-version-tags-can-release
  (doseq [[tag version] [["v8.0.0" "8.0.0"]
                         ["v8.0.0-RC-2026-09-12" "8.0.0-RC-2026-09-12"]
                         ["v8.0.0-SNAPSHOT-2026-09-12" "8.0.0-SNAPSHOT"]
                         ["v8.0.0-SNAPSHOT-2026-09-12T141530Z" "8.0.0-SNAPSHOT"]]
          event ["push" "workflow_dispatch"]]
    (is (= {:version version :branch "v8.0.0-SNAPSHOT"}
           (guard/tagged-release
            (assoc tag-context :ref (str "refs/tags/" tag) :event-name event)))))
  (doseq [context [(assoc tag-context :ref "refs/heads/main" :ref-type "branch")
                   (assoc tag-context :ref "refs/heads/v8.0.0-SNAPSHOT" :ref-type "branch")
                   (assoc tag-context :ref "refs/heads/feature/example" :ref-type "branch")
                   (assoc tag-context :ref "refs/tags/v8.0.0-SNAPSHOT")
                   (assoc tag-context :ref "refs/tags/v8.0-SNAPSHOT")
                   (assoc tag-context :ref "refs/tags/v8.0.0-RC-2026-09-12/evil")
                   (assoc tag-context :event-name "pull_request")
                   (assoc tag-context :supplied-version "8.0.0")
                   (assoc tag-context :ref nil)]]
    (is (thrown? clojure.lang.ExceptionInfo (guard/tagged-release context)))))

(deftest tag-must-be-merged-and-match-the-current-release-tree
  (let [context {:sha "green-pr-head" :tag-sha "green-pr-head"
                 :source-tree "identical-tree" :branch-tree "identical-tree"
                 :ancestor? true}]
    (is (true? (guard/assert-tag-provenance! context)))
    (doseq [[key value] [[:tag-sha "moved-tag"] [:source-tree nil]
                         [:branch-tree "different-code"] [:ancestor? false] [:sha nil]]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (guard/assert-tag-provenance! (assoc context key value)))))))

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

(def tagged-workflow-runs
  (mapv (fn [run path id]
          (assoc run :path path :id id :status "completed" :conclusion "success"))
        release-workflow-runs
        [".github/workflows/test.yml" ".github/workflows/formal.yml"]
        [101 102]))

(deftest tag-publication-reuses-exact-source-ci-and-fails-fast
  (let [checks (successful-checks "release-sha")]
    (is (= :ready (guard/evaluate-tag-checks "release-sha" tagged-workflow-runs checks)))
    (testing "a green PR branch push can be reused after its head is merged"
      (is (= :ready (guard/evaluate-tag-checks
                     "release-sha"
                     (mapv #(assoc % :head_branch "feature/release") tagged-workflow-runs)
                     checks))))
    (doseq [runs [(pop tagged-workflow-runs)
                  (mapv #(assoc % :head_sha "older-sha") tagged-workflow-runs)
                  (mapv #(assoc % :event "pull_request") tagged-workflow-runs)
                  (assoc-in tagged-workflow-runs [0 :path] ".github/workflows/pretend.yml")
                  (assoc-in tagged-workflow-runs [0 :status] "in_progress")
                  (assoc-in tagged-workflow-runs [0 :conclusion] "failure")
                  (conj tagged-workflow-runs
                        (assoc (first tagged-workflow-runs) :id 200 :conclusion "failure"))]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (guard/evaluate-tag-checks "release-sha" runs checks))))
    (testing "an older failed attempt does not poison a newer successful run"
      (is (= :ready (guard/evaluate-tag-checks
                     "release-sha"
                     (conj tagged-workflow-runs
                           (assoc (first tagged-workflow-runs)
                                  :id 1 :check_suite_id 99 :conclusion "failure"))
                     (conj checks (check-run "test" "release-sha" 99 "completed" "failure"))))))
    (testing "required jobs must still succeed even if a workflow reports success"
      (is (thrown? clojure.lang.ExceptionInfo
                   (guard/evaluate-tag-checks
                    "release-sha" tagged-workflow-runs
                    (assoc-in checks [0 :conclusion] "skipped")))))))

(deftest release-workflow-is-tagged-and-reuses-tested-runtime-artifacts
  (let [workflow (slurp ".github/workflows/release.yml")
        preflight (slurp "bin/release-preflight")]
    (is (string/includes? workflow "tags: ['v[0-9]*']"))
    (is (not (string/includes? workflow "branches:")))
    (is (string/includes? workflow "environment: clojars"))
    (is (string/includes? workflow "run-id: ${{ steps.guard.outputs.tests-run-id }}"))
    (is (not (string/includes? workflow "bin/formal")))
    (is (string/includes? preflight "actions/runs?head_sha=$GITHUB_SHA&event=push"))
    (is (string/includes? preflight "clojure -M:release-guard tag-checks"))))

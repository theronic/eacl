(ns eacl.release-guard-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.release-guard :as guard]))

(def ordinary-context
  {:event-name "push"
   :ref "refs/heads/v8.1.0"
   :ref-type "branch"
   :sha "abc123"
   :branch-sha "abc123"
   :supplied-version nil})

(deftest ordinary-branch-derives-an-immutable-version
  (is (= "8.1.0" (guard/ordinary-version ordinary-context)))
  (doseq [context
          [(assoc ordinary-context :ref "refs/heads/main")
           (assoc ordinary-context :ref "refs/heads/release/v8.0")
           (assoc ordinary-context :ref "refs/heads/feature/v8.1.0")
           (assoc ordinary-context :ref "refs/tags/v8.1.0"
                                   :ref-type "tag")
           (assoc ordinary-context :event-name "pull_request")
           (assoc ordinary-context :supplied-version "8.1.0")
           (assoc ordinary-context :branch-sha "newer")]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (guard/ordinary-version context)))))

(defn- successful-checks
  [sha]
  (mapv
   (fn [name]
     {:name name
      :head_sha sha
      :status "completed"
      :conclusion "success"})
   guard/required-checks))

(deftest exact-sha-check-gate-rejects-every-ambiguous-state
  (let [sha "release-sha"
        checks (successful-checks sha)]
    (is (= :ready (guard/evaluate-checks sha checks false)))
    (is (= :pending
           (guard/evaluate-checks sha (pop checks) false)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (guard/evaluate-checks sha (pop checks) true)))
    (is (thrown?
         clojure.lang.ExceptionInfo
         (guard/evaluate-checks sha (conj checks (first checks)) false)))
    (doseq [changed [(assoc-in checks [0 :head_sha] "wrong-sha")
                     (assoc-in checks [0 :status] "in_progress")
                     (assoc-in checks [0 :conclusion] "failure")
                     (assoc-in checks [0 :conclusion] "cancelled")
                     (assoc-in checks [0 :conclusion] "timed_out")]]
      (testing (str "rejected check state " (first changed))
        (if (= "in_progress" (:status (first changed)))
          (is (thrown? clojure.lang.ExceptionInfo
                       (guard/evaluate-checks sha changed true)))
          (is (thrown? clojure.lang.ExceptionInfo
                       (guard/evaluate-checks sha changed false))))))))

(ns eacl.formal.generated-runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.formal.generated-runtime :as generated-runtime]))

(deftest generated-runtime-failure-is-actionable-and-side-effect-free
  (testing "a missing class reports the explicit source-preparation command"
    (let [failure
          (try
            (generated-runtime/assert-available!
             "eacl.test.DeliberatelyMissingGeneratedClass")
            nil
            (catch clojure.lang.ExceptionInfo exception
              exception))]
      (is (= :eacl.formal/generated-runtime-missing
             (:type (ex-data failure))))
      (is (= generated-runtime/preparation-command
             (:command (ex-data failure))))
      (is (re-find #"downloads formal tools"
                   (ex-message failure))))))

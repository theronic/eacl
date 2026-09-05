(ns eacl.datascript.security-mutation-test
  (:require [clojure.test :refer [deftest is]]
            [eacl.core :as eacl]
            [eacl.security.imports :as imports]
            [eacl.security.mutation-test :refer [detected?]]
            [eacl.datascript.contract-test :as backend-test]))

(deftest caller-contract-and-cache-trust-controls-are-killed
  (let [lookup eacl/lookup-resources
        restart (fn [client query]
                  (try (lookup client query)
                       (catch clojure.lang.ExceptionInfo error
                         (if (= :security-key-unavailable (:reason (ex-data error)))
                           (lookup client (dissoc query :after :before))
                           (throw error)))))]
    (is (detected? {#'eacl/lookup-resources restart}
                   [#'backend-test/live-security-keyring-rotation-contract-test])))
  (is (detected? {#'imports/accepted? (constantly true)}
                 [#'backend-test/live-security-keyring-rotation-contract-test])))

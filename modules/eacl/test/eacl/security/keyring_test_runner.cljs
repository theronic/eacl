(ns eacl.security.keyring-test-runner
  (:require [cljs.test :as t] [eacl.security.keyring-test] [eacl.security.format-test]
            [eacl.security.configuration-test]))
(defmethod t/report [::t/default :end-run-tests] [m]
  (set! (.-exitCode js/process) (if (t/successful? m) 0 1)))
(defn -main [] (t/run-tests 'eacl.security.keyring-test 'eacl.security.format-test 'eacl.security.configuration-test))
(set! *main-cli-fn* -main)

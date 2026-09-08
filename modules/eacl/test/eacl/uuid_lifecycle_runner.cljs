(ns eacl.uuid-lifecycle-runner
  (:require [cljs.test :as t]
            [eacl.uuid-lifecycle-test]))

(defmethod t/report [::t/default :end-run-tests] [result]
  (set! js/process.exitCode (+ (:fail result) (:error result))))

(defn -main [] (t/run-tests 'eacl.uuid-lifecycle-test))
(set! *main-cli-fn* -main)

(ns eacl.datascript.cljs-test-runner
  (:require [cljs.nodejs :as nodejs]
            [cljs.test :as t]
            [eacl.backend.v8-test]
            [eacl.cache-test]
            [eacl.causal-model-test]
            [eacl.consistency-test]
            [eacl.mutation-test]
            [eacl.secure-format-test]
            [eacl.datascript.consistency-v3-test]
            [eacl.datascript.contract-test]
            [eacl.datascript.mutation-test]))

(nodejs/enable-util-print!)

(defmethod t/report [::t/default :end-run-tests] [m]
  (let [failures (+ (:fail m 0) (:error m 0))]
    (.log js/console
      (str "EACL DataScript CLJS tests complete. failures="
           (:fail m 0)
           " errors="
           (:error m 0)))
    (js/process.exit failures)))

(defn -main []
  (t/run-tests 'eacl.backend.v8-test
               'eacl.cache-test
               'eacl.causal-model-test
               'eacl.consistency-test
               'eacl.mutation-test
               'eacl.secure-format-test
               'eacl.datascript.consistency-v3-test
               'eacl.datascript.contract-test
               'eacl.datascript.mutation-test))

(set! *main-cli-fn* -main)

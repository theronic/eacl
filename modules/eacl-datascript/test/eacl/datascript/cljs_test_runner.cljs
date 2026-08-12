(ns eacl.datascript.cljs-test-runner
  (:require [cljs.nodejs :as nodejs]
            [cljs.test :as t]
            [eacl.backend.v8-test]
            [eacl.cache-test]
            [eacl.core-test]
            [eacl.causal-model-test]
            [eacl.consistency-test]
            [eacl.engine.relationships-test]
            [eacl.execution-test]
            [eacl.proof-frame-test]
            [eacl.permission-tree-test]
            [eacl.relationships.endpoint-pair-test]
            [eacl.relay-test]
            [eacl.secure-format-test]
            [eacl.subproblem-cache-test]
            [eacl.verified-kernel-test]
            [eacl.formal.cache-strategy-adversarial-test]
            [eacl.formal.differential-runner-test]
            [eacl.formal.generators-test]
            [eacl.datascript.adapter-certification-test]
            [eacl.datascript.cache-model-test]
            [eacl.datascript.consistency-v3-test]
            [eacl.datascript.contract-test]
            [eacl.datascript.enumeration-routing-test]
            [eacl.datascript.impl-test]
            [eacl.datascript.safe-retraction-test]
            [eacl.datascript.storage-test]))

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
               'eacl.core-test
               'eacl.causal-model-test
               'eacl.consistency-test
               'eacl.engine.relationships-test
               'eacl.execution-test
               'eacl.proof-frame-test
               'eacl.permission-tree-test
               'eacl.relationships.endpoint-pair-test
               'eacl.relay-test
               'eacl.secure-format-test
               'eacl.subproblem-cache-test
               'eacl.verified-kernel-test
               'eacl.formal.cache-strategy-adversarial-test
               'eacl.formal.differential-runner-test
               'eacl.formal.generators-test
               'eacl.datascript.adapter-certification-test
               'eacl.datascript.cache-model-test
               'eacl.datascript.consistency-v3-test
               'eacl.datascript.contract-test
               'eacl.datascript.enumeration-routing-test
               'eacl.datascript.impl-test
               'eacl.datascript.safe-retraction-test
               'eacl.datascript.storage-test))

(set! *main-cli-fn* -main)

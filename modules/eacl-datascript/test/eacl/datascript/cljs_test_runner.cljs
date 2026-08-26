(ns eacl.datascript.cljs-test-runner
  (:require [cljs.nodejs :as nodejs]
            [cljs.test :as t]
            [eacl.backend.v8-test]
            [eacl.backend.direct-membership-test]
            [eacl.authorization.batch-test]
            [eacl.cache-test]
            [eacl.core-test]
            [eacl.causal-model-test]
            [eacl.consistency-test]
            [eacl.engine.relationships-test]
            [eacl.execution-test]
            [eacl.proof-frame-test]
            [eacl.permission-tree-test]
            [eacl.operator.plan-test]
            [eacl.operator.evaluator-test]
            [eacl.operator.bitmask-test]
            [eacl.operator.vector-evaluator-test]
            [eacl.operator.batch-schedule-test]
            [eacl.operator.lookup-test]
            [eacl.operator.feature-gate-test]
            [eacl.operator.recursive-test]
            [eacl.relationships.endpoint-pair-test]
            [eacl.relay-test]
            [eacl.request.context-test]
            [eacl.request.counters-test]
            [eacl.secure-format-test]
            [eacl.schema.expression-test]
            [eacl.schema.expression-limits-test]
            [eacl.schema.expression-resolver-test]
            [eacl.schema.expression-graph-test]
            [eacl.schema.expression-fuzz-test]
            [eacl.schema.expression-persistence-test]
            [eacl.schema.expression-policy-test]
            [eacl.spicedb.parser-portability-test]
            [eacl.subproblem-cache-test]
            [eacl.verified-kernel-test]
            [eacl.formal.cache-strategy-adversarial-test]
            [eacl.formal.differential-runner-test]
            [eacl.formal.executed-mutation-controls]
            [eacl.formal.generators-test]
            [eacl.datascript.adapter-certification-test]
            [eacl.datascript.batch-test]
            [eacl.datascript.cache-model-test]
            [eacl.datascript.consistency-v3-test]
            [eacl.datascript.contract-test]
            [eacl.engine.continuation-reuse-test]
            [eacl.engine.stable-page-test]
            [eacl.engine.stable-reducer-test]
            [eacl.datascript.impl-test]
            [eacl.datascript.safe-retraction-test]
            [eacl.datascript.snapshot-lifecycle-test]
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
               'eacl.backend.direct-membership-test
               'eacl.authorization.batch-test
               'eacl.cache-test
               'eacl.core-test
               'eacl.causal-model-test
               'eacl.consistency-test
               'eacl.engine.relationships-test
               'eacl.execution-test
               'eacl.proof-frame-test
               'eacl.permission-tree-test
               'eacl.operator.plan-test
               'eacl.operator.evaluator-test
               'eacl.operator.bitmask-test
               'eacl.operator.vector-evaluator-test
               'eacl.operator.batch-schedule-test
               'eacl.operator.lookup-test
               'eacl.operator.feature-gate-test
               'eacl.operator.recursive-test
               'eacl.relationships.endpoint-pair-test
               'eacl.relay-test
               'eacl.request.context-test
               'eacl.request.counters-test
               'eacl.secure-format-test
               'eacl.schema.expression-test
               'eacl.schema.expression-limits-test
               'eacl.schema.expression-resolver-test
               'eacl.schema.expression-graph-test
               'eacl.schema.expression-fuzz-test
               'eacl.schema.expression-persistence-test
               'eacl.schema.expression-policy-test
               'eacl.spicedb.parser-portability-test
               'eacl.subproblem-cache-test
               'eacl.verified-kernel-test
               'eacl.formal.cache-strategy-adversarial-test
               'eacl.formal.differential-runner-test
               'eacl.formal.executed-mutation-controls
               'eacl.formal.generators-test
               'eacl.datascript.adapter-certification-test
               'eacl.datascript.batch-test
               'eacl.datascript.cache-model-test
               'eacl.datascript.consistency-v3-test
               'eacl.datascript.contract-test
               'eacl.engine.continuation-reuse-test
               'eacl.engine.stable-page-test
               'eacl.engine.stable-reducer-test
               'eacl.datascript.impl-test
               'eacl.datascript.safe-retraction-test
               'eacl.datascript.snapshot-lifecycle-test
               'eacl.datascript.storage-test))

(set! *main-cli-fn* -main)

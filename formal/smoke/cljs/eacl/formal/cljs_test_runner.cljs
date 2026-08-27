(ns eacl.formal.cljs-test-runner
  (:require
   [cljs.nodejs :as nodejs]
   [cljs.test :as t]
   [eacl.formal.indexed-semantics-bridge-test]
   [eacl.formal.js-round-trip-test]
   [eacl.formal.operator-decision-test]
   [eacl.formal.production-kernel-test]
   [eacl.verified-kernel-test]))

(nodejs/enable-util-print!)

(defmethod t/report [::t/default :end-run-tests] [m]
  (let [failures (+ (:fail m 0) (:error m 0))]
    (.log
     js/console
     (str "EACL portable/generated-oracle CLJS semantics smoke complete. failures="
          (:fail m 0)
          " errors="
          (:error m 0)))
    (js/process.exit failures)))

(defn -main
  []
  (t/run-tests
   'eacl.formal.indexed-semantics-bridge-test
   'eacl.formal.js-round-trip-test
   'eacl.formal.operator-decision-test
   'eacl.formal.production-kernel-test
   'eacl.verified-kernel-test))

(set! *main-cli-fn* -main)

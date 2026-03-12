(ns eacl.datascript.cljs-test-runner
  (:require [cljs.nodejs :as nodejs]
            [cljs.test :as t]
            [eacl.datascript.contract-test]))

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
  (t/run-tests 'eacl.datascript.contract-test))

(set! *main-cli-fn* -main)

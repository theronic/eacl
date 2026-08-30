(ns eacl.cache.standard-lru-test-runner
  (:require [cljs.nodejs :as nodejs]
            [cljs.test :as test]
            [eacl.cache.standard-lru-test]))

(nodejs/enable-util-print!)

(defmethod test/report [::test/default :end-run-tests]
  [{:keys [fail error]}]
  (let [failures (+ (or fail 0) (or error 0))]
    (.log js/console
          (str "EACL standard LRU CLJS tests complete. failures=" failures))
    ;; Assignment survives Closure advanced compilation; direct property
    ;; access and immediate process.exit have both hidden failures in runners.
    (aset js/process "exitCode" failures)))

(defn -main
  []
  (test/run-tests 'eacl.cache.standard-lru-test))

(set! *main-cli-fn* -main)

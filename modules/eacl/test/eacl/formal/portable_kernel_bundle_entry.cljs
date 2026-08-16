(ns eacl.formal.portable-kernel-bundle-entry
  (:require [eacl.formal.production-kernel-cljs :as production]))

(defn -main []
  ;; Accessing the concrete record keeps its complete DecisionKernel and
  ;; IndexedTraversalKernel protocol method tables in the optimized audit.
  (when-not (:kernel production/default-selection)
    (throw (js/Error. "portable CLJS production kernel is missing"))))

(set! *main-cli-fn* -main)

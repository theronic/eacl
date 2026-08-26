(ns eacl.operator.cursor-scope
  "Authenticated semantic identity for operator least-path progress. The
  surrounding v8 cursor envelope version is unchanged; this digest extends
  the edge payload's current semantic scope."
  (:require [eacl.secure-format :as secure-format]))

(def scope-version 1)
(def scope-domain "eacl.operator.cursor-scope.v1")

(defn semantic-input
  "Returns the complete portable input authenticated for one operator cursor.
  `snapshot-proof-identity` is the request's complete ordered proof frame when
  available, otherwise the selected immutable snapshot identity."
  [plan cover-plan traversal snapshot-proof-identity]
  {:version scope-version
   :expression
   (mapv #(select-keys % [:permission :expression-format
                          :expression-digest :root])
         (:expressions plan))
   :signed-certificate (:dependency-certificate plan)
   :strata (:strata plan)
   :cover {:fingerprint (:fingerprint cover-plan)
           :root (:root cover-plan)
           :semantic-root (:operator-root-semantic cover-plan)}
   :anchors (:anchors plan)
   :witness {:version (get-in plan [:versions :witness])
             :programs (:witness-programs plan)}
   :predicate {:version (get-in plan [:versions :predicate])}
   :physical-policy {:version (get-in plan [:versions :physical-policy])
                     :capability (:capability-identity plan)
                     :limits (:limits plan)}
   :order (:order-contract plan)
   :plan-fingerprint (:fingerprint plan)
   :snapshot-proof snapshot-proof-identity
   :traversal traversal})

(defn digest
  [plan cover-plan traversal snapshot-proof-identity]
  (secure-format/canonical-records-digest
   scope-domain
   [[:operator-cursor-scope
     (semantic-input plan cover-plan traversal snapshot-proof-identity)]]))

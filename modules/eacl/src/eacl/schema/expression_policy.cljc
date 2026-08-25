(ns eacl.schema.expression-policy
  "Versioned, calibrated admission policy for permission expressions."
  (:require [eacl.schema.expression :as expression]
            [eacl.schema.expression-limits :as limits]
            [eacl.secure-format :as secure]))

(def policy-format :eacl.permission-expression-policy/v1)
(def operator-plan-format :eacl.operator-plan/v1)

(def schema-limits
  {:maximum-schema-source-bytes 1048576})

(def per-permission-limits
  {:maximum-source-nodes 512
   :maximum-source-depth 64
   :maximum-direct-fan-in 128
   :maximum-type-partitions 256
   :maximum-expression-bytes 131072
   :maximum-normalized-nodes 512
   :maximum-child-slots 1024
   :maximum-words 1024
   :maximum-checkpoint-weight 131072})

(def aggregate-limits
  {:maximum-permissions 1024
   :maximum-aggregate-source-nodes 16384
   :maximum-aggregate-normalized-nodes 16384
   :maximum-aggregate-child-slots 32768
   :maximum-aggregate-words 32768
   :maximum-aggregate-checkpoint-weight 8388608
   :maximum-aggregate-expression-bytes 16777216})

(def expression-limits
  (merge per-permission-limits aggregate-limits))

(def compatibility-value
  {:format policy-format
   :operator-plan-format operator-plan-format
   :expression-format expression/format-version
   :normalized-dag-format limits/normalized-dag-format
   :codec-limits expression/codec-limits
   :schema-limits schema-limits
   :per-permission-limits per-permission-limits
   :aggregate-limits aggregate-limits})

(def compatibility-digest
  (secure/canonical-digest
    "eacl/permission-expression-policy/v1"
    compatibility-value))

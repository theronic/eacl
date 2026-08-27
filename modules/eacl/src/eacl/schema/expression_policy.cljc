(ns eacl.schema.expression-policy
  "Calibrated client-local admission limits and code compatibility formats."
  (:require [eacl.exact-integer :as exact-integer]
            [eacl.schema.expression :as expression]
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

(def default-client-limits
  "The calibrated expression-admission profile used when a client supplies no
   override. This is process configuration, never durable schema data."
  (merge schema-limits expression-limits))

(def hard-limit-ceilings
  "Portable implementation ceilings. Client profiles may tune each default
   independently but cannot disable a dimension or exceed these bounds. Codec
   dimensions follow the closed codec; aggregate ceilings retain the qualified
   release bounds."
  {:maximum-schema-source-bytes 1048576
   :maximum-source-nodes 262144
   :maximum-source-depth 256
   :maximum-direct-fan-in 262144
   :maximum-type-partitions 262144
   :maximum-expression-bytes 1048576
   :maximum-normalized-nodes 262144
   :maximum-child-slots 262144
   :maximum-words 262144
   :maximum-checkpoint-weight 8388608
   :maximum-permissions 1024
   :maximum-aggregate-source-nodes 16384
   :maximum-aggregate-normalized-nodes 16384
   :maximum-aggregate-child-slots 32768
   :maximum-aggregate-words 32768
   :maximum-aggregate-checkpoint-weight 8388608
   :maximum-aggregate-expression-bytes 16777216})

(defn normalize-client-limits
  "Returns a complete immutable expression-limit profile.

   `overrides` is a flat map containing only known limit keys. Every value is
   a non-negative portable exact integer no greater than its hard ceiling."
  [overrides]
  (when-not (or (nil? overrides) (map? overrides))
    (throw
     (ex-info "EACL Config Error: :expression-limits must be a map."
              {:type :eacl/invalid-config
               :eacl/error :eacl/invalid-config
               :key :expression-limits
               :value overrides})))
  (let [overrides (or overrides {})
        unknown (vec (sort (remove (set (keys hard-limit-ceilings))
                                   (keys overrides))))]
    (when (seq unknown)
      (throw
       (ex-info "EACL Config Error: :expression-limits contains unknown keys."
                {:type :eacl/invalid-config
                 :eacl/error :eacl/invalid-config
                 :key :expression-limits
                 :unknown-keys unknown
                 :known-keys (set (keys hard-limit-ceilings))})))
    (doseq [[key value] overrides
            :let [maximum (get hard-limit-ceilings key)]]
      (when-not (and #?(:clj (integer? value)
                        :cljs (and (number? value)
                                   (js/Number.isSafeInteger value)))
                     (<= 0 value exact-integer/maximum)
                     (<= value maximum))
        (throw
         (ex-info "EACL Config Error: expression limit is outside its portable hard ceiling."
                  {:type :eacl/invalid-config
                   :eacl/error :eacl/invalid-config
                   :key :expression-limits
                   :limit key
                   :value value
                   :maximum maximum}))))
    (merge default-client-limits overrides)))

(def compatibility-value
  "Code-level formats used by runtime plan/cursor fingerprints. Tuneable
   client admission limits are deliberately absent."
  {:format policy-format
   :operator-plan-format operator-plan-format
   :expression-format expression/format-version
   :normalized-dag-format limits/normalized-dag-format
   :codec-limits expression/codec-limits})

(def compatibility-digest
  (secure/canonical-digest
   "eacl/permission-expression-policy/v1"
   compatibility-value))

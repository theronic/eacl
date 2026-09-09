(ns eacl.schema.qualification-admission
  "Schema-level qualification admission, outside the per-edge fast path."
  (:require [eacl.caveats.evaluator :as evaluator]
            [eacl.relationships.qualifier :as qualifier]
            [eacl.relationships.storage :as storage]
            [eacl.schema.relation-allowance :as allowance]))

(defn publication-descriptor
  "The certified native publication contract. A backend advertises it only
   for topologies that can execute its atomic inline or prepared-ref writer."
  [strategy]
  {:version 1 :strategy strategy :storage-version storage/version
   :qualifier-format qualifier/format-version
   :obligations #{:atomic-endpoint-pair :fresh-qualifier-reference
                  :relation-generation :identity-preconditions
                  :old-qualifier-cleanup :application-composition}})

(defn require-publication! [descriptor]
  (when-not (and (#{:inline :prepared} (:strategy descriptor))
                 (= descriptor (publication-descriptor (:strategy descriptor))))
    (throw (ex-info "Qualified authorization requires a certified native publication strategy."
                    {:type :eacl/unsupported-capability
                     :eacl/error :eacl/unsupported-capability
                     :capability :qualified-relationship-publication})))
  descriptor)

(defn schema!
  "Requires a matching evaluator for every Caveated Relation, including empty
   and unvisited Relations. Unused definitions and expiry-only schemas do not
   require an evaluator. This reads no program source and compiles nothing."
  [schema implementation publication]
  (require-publication! publication)
  (when (some #(some some? (allowance/names %)) (:relations schema))
    (evaluator/require-matching! implementation evaluator/profile-fingerprint))
  schema)

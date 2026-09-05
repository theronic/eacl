(ns eacl.schema.replacement-plan
  "Backend-neutral semantic half of schema replacement planning.

  Native adapters add storage transaction forms and committed concurrency
  guards, but both committed and speculative replacement consume this exact
  semantic certificate."
  (:require [clojure.set :as set]))

(def orphan-policies
  #{:error :retain-inert})

(defn relation-coordinate
  [{:eacl.relation/keys [resource-type relation-name subject-type]}]
  [:relation resource-type relation-name subject-type])

(defn permission-coordinate
  [{:eacl.permission/keys [resource-type permission-name]}]
  [:permission resource-type permission-name])

(defn- changed-coordinates
  [coordinate-fn {:keys [additions retractions]}]
  (into #{}
        (comp (mapcat identity) (map coordinate-fn))
        [additions retractions]))

(defn- relation-in-use!
  [relation count]
  (throw
   (ex-info
    (str "Cannot delete relation "
         (:eacl.relation/relation-name relation)
         " because it is used by " count " relationships.")
    {:type :eacl.schema/relation-in-use
     :eacl/error :eacl.schema/relation-in-use
     :relation relation
     :count count})))

(defn plan
  "Returns the canonical semantic certificate for one already-validated
  schema diff.

  `relationship-count` is used only by the default `:error` policy.
  `relationship-present?` is used only by `:retain-inert` and must be a
  bounded indexed existence probe."
  [{:keys [deltas orphan-policy relationship-count relationship-present?]
    :or {orphan-policy :error}}]
  (when-not (contains? orphan-policies orphan-policy)
    (throw
     (ex-info
      "Unsupported prospective schema orphan policy."
      {:type :eacl/invalid-option
       :eacl/error :eacl/invalid-option
       :option :orphan-policy
       :value orphan-policy
       :allowed orphan-policies})))
  (let [relation-retractions (get-in deltas [:relations :retractions])
        relationship-effects
        (changed-coordinates relation-coordinate (:relations deltas))
        schema-components
        (set/union
         relationship-effects
         (changed-coordinates permission-coordinate (:permissions deltas))
         (changed-coordinates #(vector :caveat (:eacl.caveat/name %)) (:caveats deltas)))
        removed-relations
        (into #{} (map relation-coordinate) relation-retractions)
        diagnostics
        (case orphan-policy
          :error
          (do
            (doseq [relation relation-retractions]
              (let [count (relationship-count relation)]
                (when (pos? count)
                  (relation-in-use! relation count))))
            [])

          :retain-inert
          (into []
                (keep
                 (fn [relation]
                   (when (relationship-present? relation)
                     {:type :eacl.speculative/retained-orphan-relationships
                      :relation (relation-coordinate relation)
                      :present? true})))
                relation-retractions))]
    {:deltas deltas
     :changed-schema-components schema-components
     :affected-relationships relationship-effects
     :removed-relations removed-relations
     :diagnostics diagnostics
     :orphan-policy orphan-policy}))

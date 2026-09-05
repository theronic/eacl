(ns eacl.caveats.definition
  "Canonical named Caveat definitions. No per-Relationship expression copies."
  (:require [eacl.caveats.plan :as plan]
            [eacl.caveats.values :as values]))

(def attributes
  [:eacl.caveat/name :eacl.caveat/parameters-payload
   :eacl.caveat/expression-source :eacl.caveat/profile-version])

(defn entity [name parameters source]
  (when-not (values/parameter-name? name) (values/error! :definition-name))
  (let [compiled (plan/compile-plan source parameters)]
    {:eacl.caveat/name name
     :eacl.caveat/parameters-payload (values/encode-parameters (:parameters compiled))
     :eacl.caveat/expression-source (:source compiled)
     :eacl.caveat/profile-version values/profile-id}))

(defn decode-entity [entity]
  (when-not (and (map? entity) (= (set attributes) (set (keys (dissoc entity :db/id)))))
    (values/error! :definition-shape))
  (when-not (= values/profile-id (:eacl.caveat/profile-version entity))
    (values/error! :unsupported-profile))
  (let [name (:eacl.caveat/name entity)
        parameters (values/decode-parameters (:eacl.caveat/parameters-payload entity))
        compiled (plan/compile-plan (:eacl.caveat/expression-source entity) parameters)]
    (when-not (and (values/parameter-name? name)
                  (= (:source compiled) (:eacl.caveat/expression-source entity)))
      (values/error! :definition-shape))
    (assoc compiled :name name)))

(defn entity-deletions [{:keys [additions retractions]}]
  (let [replacements (set (map :eacl.caveat/name additions))]
    (filterv #(not (contains? replacements (:eacl.caveat/name %))) retractions)))

(defn validate-replacements!
  "Checks retained qualifier references, including inert preparations, before
   schema replacement. Native writers additionally serialize the schema fence."
  [{:keys [additions retractions]} references]
  (let [replacements (into {} (map (juxt :eacl.caveat/name identity)) additions)]
    (doseq [prior retractions
            :let [name (:eacl.caveat/name prior)
                  replacement (get replacements name)
                  parameters (some-> replacement decode-entity :parameters)]
            qualifier (references name)]
      (when-not replacement
        (throw (ex-info "Cannot remove a Caveat referenced by a retained qualifier."
                        {:type :eacl.schema/caveat-in-use :eacl/error :eacl.schema/caveat-in-use
                         :caveat name :qualifier (:db/id qualifier)})))
      (when-let [payload (:eacl.relationship-qualifier/caveat-context qualifier)]
        (values/decode-context parameters payload)))))

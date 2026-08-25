(ns eacl.schema.expression-persistence
  "Canonical expression-only storage values and strict read validation."
  (:require [eacl.schema.expression :as expression]
            [eacl.schema.expression-limits :as limits]
            [eacl.schema.expression-policy :as policy]))

(def expression-attributes
  [:eacl/id
   :eacl.permission/resource-type
   :eacl.permission/permission-name
   :eacl.permission/expression-format
   :eacl.permission/expression-payload
   :eacl.permission/expression-digest
   :eacl.permission/expression-policy-digest
   :eacl.permission/source-node-count
   :eacl.permission/source-maximum-depth
   :eacl.permission/source-direct-fan-in
   :eacl.permission/encoded-byte-size
   :eacl.permission/normalized-node-count
   :eacl.permission/normalized-child-slot-count
   :eacl.permission/normalized-word-count
   :eacl.permission/normalized-checkpoint-weight])

(def legacy-flat-attributes
  #{:eacl.permission/source-relation-name
    :eacl.permission/target-type
    :eacl.permission/target-name
    :eacl.permission/full-key
    :eacl.permission/resource-type+source-relation-name+target-type+permission-name
    :eacl.permission/resource-type+source-relation-name+target-type+target-name
    :eacl.permission/resource-type+source-relation-name+target-type+target-name+permission-name})

(defn ->expression-id [resource-type permission-name]
  (str "eacl.permission-expression:" resource-type ":" permission-name))

(defn- storage-long [value]
  #?(:clj (long value)
     :cljs value))

(defn expression-entity
  [resolved-expression metadata]
  (let [resolved-expression (expression/canonicalize resolved-expression)
        {:keys [resource-type permission-name]} resolved-expression
        {:keys [source-metrics normalized-metrics encoded-byte-size]} metadata]
    {:eacl/id (->expression-id resource-type permission-name)
     :eacl.permission/resource-type resource-type
     :eacl.permission/permission-name permission-name
     :eacl.permission/expression-format expression/format-version
     :eacl.permission/expression-payload (expression/encode resolved-expression)
     :eacl.permission/expression-digest (expression/digest resolved-expression)
     :eacl.permission/expression-policy-digest policy/compatibility-digest
     :eacl.permission/source-node-count
     (storage-long (:node-count source-metrics))
     :eacl.permission/source-maximum-depth
     (storage-long (:maximum-depth source-metrics))
     :eacl.permission/source-direct-fan-in
     (storage-long (:direct-fan-in source-metrics))
     :eacl.permission/encoded-byte-size (storage-long encoded-byte-size)
     :eacl.permission/normalized-node-count
     (storage-long (:node-count normalized-metrics))
     :eacl.permission/normalized-child-slot-count
     (storage-long (:child-slot-count normalized-metrics))
     :eacl.permission/normalized-word-count
     (storage-long (:word-count normalized-metrics))
     :eacl.permission/normalized-checkpoint-weight
     (storage-long (:checkpoint-weight normalized-metrics))}))

(defn candidate-schema
  "Converts a fully validated resolver result to backend transaction values."
  [{:keys [definitions relations expressions expression-metadata]
    :as validated}]
  (when-not (= (count expressions) (count expression-metadata))
    (throw (ex-info "Expression metadata is not aligned."
             {:type :eacl.schema/invalid-expression-metadata
              :eacl/error :eacl.schema/invalid-expression-metadata})))
  (assoc validated
         :definitions definitions
         :relations relations
         :permissions (mapv expression-entity expressions
                            expression-metadata)))

(defn entity-deletions
  "Returns only retracted entities whose logical identity is absent from the
   additions. A changed expression has the same :eacl/id on both sides of a
   schema delta and must be replaced by the addition, not retracted after its
   cardinality-one attributes are updated."
  [{:keys [additions retractions]}]
  (let [replacement-ids (into #{} (map :eacl/id) additions)]
    (into []
          (remove #(contains? replacement-ids (:eacl/id %)))
          retractions)))

(defn- corrupt! [reason data]
  (throw (ex-info "Stored permission expression is corrupt or unsupported."
           (merge {:type :eacl.schema/corrupt-expression-storage
                   :eacl/error :eacl.schema/corrupt-expression-storage
                   :reason reason}
                  data))))

(defn decode-entity
  "Validates every stored field against recomputed canonical expression data."
  [entity]
  (let [legacy (seq (filter #(contains? entity %) legacy-flat-attributes))]
    (when legacy
      (corrupt! :mixed-flat-and-expression
                {:attributes (vec (sort-by str legacy))})))
  (doseq [attribute expression-attributes]
    (when-not (contains? entity attribute)
      (corrupt! :missing-field {:field attribute})))
  (when-not (= expression/format-version
               (:eacl.permission/expression-format entity))
    (corrupt! :unsupported-format
              {:format (:eacl.permission/expression-format entity)}))
  (when-not (= policy/compatibility-digest
               (:eacl.permission/expression-policy-digest entity))
    (corrupt! :unsupported-policy
              {:policy-digest
               (:eacl.permission/expression-policy-digest entity)}))
  (let [resolved
        (try
          (expression/decode (:eacl.permission/expression-payload entity))
          (catch #?(:clj Exception :cljs :default) error
            (corrupt! :invalid-payload {:codec-error (ex-data error)})))
        resource-type (:resource-type resolved)
        permission-name (:permission-name resolved)
        source-metrics (limits/source-metrics (:root resolved))
        encoded-byte-size (limits/expression-byte-size resolved)
        normalized-metrics (:metrics (limits/normalized-dag resolved))
        expected
        {:eacl/id (->expression-id resource-type permission-name)
         :eacl.permission/resource-type resource-type
         :eacl.permission/permission-name permission-name
         :eacl.permission/expression-format expression/format-version
         :eacl.permission/expression-payload (expression/encode resolved)
         :eacl.permission/expression-digest (expression/digest resolved)
         :eacl.permission/expression-policy-digest policy/compatibility-digest
         :eacl.permission/source-node-count
         (storage-long (:node-count source-metrics))
         :eacl.permission/source-maximum-depth
         (storage-long (:maximum-depth source-metrics))
         :eacl.permission/source-direct-fan-in
         (storage-long (:direct-fan-in source-metrics))
         :eacl.permission/encoded-byte-size (storage-long encoded-byte-size)
         :eacl.permission/normalized-node-count
         (storage-long (:node-count normalized-metrics))
         :eacl.permission/normalized-child-slot-count
         (storage-long (:child-slot-count normalized-metrics))
         :eacl.permission/normalized-word-count
         (storage-long (:word-count normalized-metrics))
         :eacl.permission/normalized-checkpoint-weight
         (storage-long (:checkpoint-weight normalized-metrics))}
        mismatch
        (first
          (for [[field value] expected
                :when (not= value (get entity field))]
            {:field field :expected value :actual (get entity field)}))]
    (when mismatch
      (corrupt! :field-mismatch mismatch))
    resolved))

(defn validate-entities
  "Rejects flat-only, mixed, duplicate, corrupt, or unsupported permission
   storage and returns canonical decoded expressions in typed-key order."
  [entities]
  (let [entities (vec entities)
        flat-only
        (first (filter #(not (contains? %
                              :eacl.permission/expression-format))
                       entities))]
    (when flat-only
      (corrupt! :flat-only-representation
                {:resource-type (:eacl.permission/resource-type flat-only)
                 :permission-name
                 (:eacl.permission/permission-name flat-only)}))
    (let [keys (mapv (juxt :eacl.permission/resource-type
                           :eacl.permission/permission-name)
                     entities)
          duplicate (first (sort-by pr-str
                             (for [[key n] (frequencies keys) :when (> n 1)]
                               key)))]
      (when duplicate
        (corrupt! :duplicate-expression {:permission duplicate})))
    (->> entities
         (map (fn [entity]
                {:entity entity :expression (decode-entity entity)}))
         (sort-by (juxt (comp str :resource-type :expression)
                        (comp str :permission-name :expression)))
         vec)))

(defn union-compatible-definitions
  "Projects an expression-only entity into the unchanged union plan domain.
   Intersection and exclusion deliberately have no flat projection."
  [permission-id resolved-expression]
  (let [{:keys [resource-type permission-name root]}
        (expression/canonicalize resolved-expression)]
    (letfn [(walk [node]
              (case (:op node)
                :relation
                [{:permission-id permission-id
                  :resource-type resource-type
                  :permission-name permission-name
                  :source-relation-name :self
                  :target-type :relation
                  :target-name (:name node)}]

                :permission
                [{:permission-id permission-id
                  :resource-type resource-type
                  :permission-name permission-name
                  :source-relation-name :self
                  :target-type :permission
                  :target-name (:name node)}]

                :arrow
                (let [targets (set (map (juxt :target-kind :target-name)
                                        (:partitions node)))]
                  (when-not (= 1 (count targets))
                    (corrupt! :inconsistent-arrow-partitions
                              {:permission [resource-type permission-name]}))
                  (let [[target-type target-name] (first targets)]
                    [{:permission-id permission-id
                      :resource-type resource-type
                      :permission-name permission-name
                      :source-relation-name (:relation node)
                      :target-type target-type
                      :target-name target-name}]))

                :union (vec (mapcat walk (:children node)))

                (:intersection :exclusion)
                (throw (ex-info "Operator expression requires an operator plan."
                         {:type :eacl.schema/operator-plan-required
                          :eacl/error :eacl.schema/operator-plan-required
                          :permission [resource-type permission-name]}))))]
      ;; Flat v8 permission rows were compared as sets before sealing, so
      ;; repeated union operands never reached the sealed-plan compiler as
      ;; duplicate rules. Preserve that union-only ABI while expression
      ;; storage retains the canonical source expression.
      (vec (distinct (walk root))))))

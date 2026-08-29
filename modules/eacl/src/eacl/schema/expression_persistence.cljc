(ns eacl.schema.expression-persistence
  "Canonical expression-only storage values and strict read validation."
  (:require [eacl.schema.expression :as expression]
            [eacl.schema.expression-limits :as limits]
            [eacl.schema.expression-policy :as policy]))

(def expression-attributes
  [:eacl/id
   :eacl.permission/resource-type
   :eacl.permission/permission-name
   :eacl.permission/expression-payload])

(def retired-redundant-expression-attributes
  "Experimental v8 fields duplicated by the canonical payload or local
   runtime configuration. Readers ignore them and writers never assert them."
  #{:eacl.permission/expression-format
    :eacl.permission/expression-digest
    :eacl.permission/expression-policy-digest})

(def retired-derived-metric-attributes
  "Experimental v8 attributes that were derived from the canonical payload.
   Readers and writers deliberately ignore them. Attribute definitions may
   remain installed in an already-used Datomic database because Datomic schema
   is additive, but no v8 expression transaction asserts these attributes."
  #{:eacl.permission/source-node-count
    :eacl.permission/source-maximum-depth
    :eacl.permission/source-direct-fan-in
    :eacl.permission/encoded-byte-size
    :eacl.permission/normalized-node-count
    :eacl.permission/normalized-child-slot-count
    :eacl.permission/normalized-word-count
    :eacl.permission/normalized-checkpoint-weight})

(def retired-expression-attributes
  (into retired-derived-metric-attributes
        retired-redundant-expression-attributes))

(def legacy-flat-attributes
  #{:eacl.permission/source-relation-name
    :eacl.permission/target-type
    :eacl.permission/target-name
    :eacl.permission/full-key
    :eacl.permission/resource-type+source-relation-name+target-type+permission-name
    :eacl.permission/resource-type+source-relation-name+target-type+target-name
    :eacl.permission/resource-type+source-relation-name+target-type+target-name+permission-name})

(def ^:dynamic *structural-cache*
  "Optional schema-generation-owned atom of completed expression decodes.
   The containing generation supplies the schema high-watermark; keys include
   every authoritative expression field. Retired metric datoms are excluded."
  nil)

(def ^:dynamic *expression-limits*
  "The immutable client-local admission profile bound around schema work."
  policy/default-client-limits)

(defn effective-expression-limits []
  ;; Reuse the complete immutable default profile. Non-default bindings still
  ;; cross the public normalizer so bound overrides remain validated.
  (if (identical? *expression-limits* policy/default-client-limits)
    policy/default-client-limits
    (policy/normalize-client-limits *expression-limits*)))

(defn ->expression-id [resource-type permission-name]
  (str "eacl.permission-expression:" resource-type ":" permission-name))

(defn expression-entity
  [resolved-expression _metadata]
  (let [resolved-expression (expression/canonicalize resolved-expression)
        {:keys [resource-type permission-name]} resolved-expression]
    {:eacl/id (->expression-id resource-type permission-name)
     :eacl.permission/resource-type resource-type
     :eacl.permission/permission-name permission-name
     :eacl.permission/expression-payload (expression/encode resolved-expression)}))

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

(defn- decode-entity-with-metadata-uncached
  "Validates authoritative stored fields and derives exact bounded metrics.

   Retired experimental metric datoms are intentionally ignored: the
   canonical payload is the sole authority. The returned metadata is suitable
   for a schema-generation cache and never needs durable storage."
  [entity]
  (let [legacy (seq (filter #(contains? entity %) legacy-flat-attributes))]
    (when legacy
      (corrupt! :mixed-flat-and-expression
                {:attributes (vec (sort-by str legacy))})))
  (doseq [attribute expression-attributes]
    (when-not (contains? entity attribute)
      (corrupt! :missing-field {:field attribute})))
  (let [expression-limits (effective-expression-limits)
        resolved
        (try
          (expression/decode (:eacl.permission/expression-payload entity))
          (catch #?(:clj Exception :cljs :default) error
            (corrupt! :invalid-payload {:codec-error (ex-data error)})))
        resource-type (:resource-type resolved)
        permission-name (:permission-name resolved)
        source-metrics (limits/check-source!
                        (:root resolved) expression-limits)
        {:keys [encoded-byte-size]}
        (limits/check-expression-bytes!
         resolved expression-limits)
        {:keys [dag metrics]}
        (limits/check-normalized! resolved expression-limits)
        expected
        {:eacl/id (->expression-id resource-type permission-name)
         :eacl.permission/resource-type resource-type
         :eacl.permission/permission-name permission-name
         :eacl.permission/expression-payload (expression/encode resolved)}
        mismatch
        (first
          (for [[field value] expected
                :when (not= value (get entity field))]
            {:field field :expected value :actual (get entity field)}))]
    (when mismatch
      (corrupt! :field-mismatch mismatch))
    {:expression resolved
     :metadata {:source-metrics source-metrics
                :encoded-byte-size encoded-byte-size
                :normalized-dag dag
                :normalized-metrics metrics}}))

(defrecord ^:private CompletedStructuralDecode [value])

(defn decode-entity-with-metadata
  "Returns one validated expression and its exact derived metadata.

   When a schema-generation cache is bound, a completed immutable decode is
   read first. Concurrent misses decode independently and race one bounded
   installation; failures are never installed or inherited by a peer."
  [entity]
  (if-not *structural-cache*
    (decode-entity-with-metadata-uncached entity)
    (let [key [(select-keys
                entity
                (into expression-attributes legacy-flat-attributes))
               (effective-expression-limits)]
          current (get @*structural-cache* key ::missing)]
      (cond
        (instance? CompletedStructuralDecode current)
        (:value current)

        ;; Tolerate a completed pre-rollout entry during development reload.
        (delay? current)
        @current

        (not= ::missing current)
        current

        :else
        (let [value (decode-entity-with-metadata-uncached entity)
              state @*structural-cache*]
          (when-not (contains? state key)
            (compare-and-set!
             *structural-cache* state
             (assoc state key (->CompletedStructuralDecode value))))
          value)))))

(defn clear-structural-cache!
  "Evicts a bound or explicitly supplied structural metric cache."
  ([] (clear-structural-cache! *structural-cache*))
  ([cache]
   (when cache (reset! cache {}))
   nil))

(defn decode-entity
  "Validates one stored expression and returns its canonical expression.
   Exact metrics are recomputed from the payload, never read from datoms."
  [entity]
  (:expression (decode-entity-with-metadata entity)))

(defn validate-entities
  "Rejects flat-only, mixed, duplicate, corrupt, or unsupported permission
   storage and returns canonical decoded expressions in typed-key order."
  [entities]
  (let [entities (vec entities)
        flat-only
        (first (filter #(not (contains? %
                              :eacl.permission/expression-payload))
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
    (let [decoded
          (->> entities
               (map (fn [entity]
                      (assoc (decode-entity-with-metadata entity)
                             :entity entity)))
               (sort-by (juxt (comp str :resource-type :expression)
                              (comp str :permission-name :expression)))
               vec)]
      (limits/check-aggregate! (mapv :metadata decoded)
                               (effective-expression-limits))
      decoded)))

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

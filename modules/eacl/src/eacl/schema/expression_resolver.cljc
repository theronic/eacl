(ns eacl.schema.expression-resolver
  "Complete-schema resolution for parsed permission-expression syntax.

  Resolution does not inline named permissions, so positive recursive graphs
  remain finite. It validates every reference against the complete schema and
  expands each one-hop arrow into one typed target partition per source
  relation subject type."
  (:require [eacl.schema.expression :as expression]
            [eacl.schema.expression-graph :as expression-graph]
            [eacl.schema.expression-limits :as expression-limits]
            [eacl.schema.expression-policy :as expression-policy]
            [eacl.schema.model :as model]
            [eacl.spicedb.parser :as parser]))

(defn- catalog
  [definitions]
  (into {}
        (for [[resource-type {:keys [relations permissions]}] definitions]
          [(keyword resource-type)
           {:relations
            (into {}
                  (for [[relation-name type-refs] relations]
                    [(keyword relation-name)
                     (vec (distinct (map (comp keyword :type) type-refs)))]))
            ;; Relations holding a `T:*` branch cannot be the left side of an
            ;; arrow (SpiceDB rejects them the same way).
            :wildcard-relations
            (into #{}
                  (keep (fn [[relation-name type-refs]]
                          (when (some :wildcard? type-refs)
                            (keyword relation-name))))
                  relations)
            :permissions (set (map (comp keyword :name) permissions))}])))

(defn- issue
  [type resource-type permission-name path data]
  (merge {:type type
          :resource-type resource-type
          :permission-name permission-name
          :path path}
         data))

(defn- issue-sort-key
  [{:keys [resource-type permission-name path type] :as value}]
  [(str resource-type)
   (str permission-name)
   (pr-str path)
   (str type)
   (pr-str (dissoc value :message))])

(defn- add-issue!
  [issues value]
  (swap! issues conj value)
  nil)

(declare resolve-node)

(defn- validate-relation-types!
  [definitions catalog issues limits]
  (doseq [[resource-type {:keys [relations]}] (sort-by key definitions)
          [relation-name type-refs] (sort-by key relations)
          :let [_ (expression-limits/check-dimension!
                   :type-partition-count
                   :maximum-type-partitions
                   (count (distinct (map :type type-refs)))
                   limits)]
          {:keys [type]} (sort-by :type type-refs)
          :let [resource-type (keyword resource-type)
                relation-name (keyword relation-name)
                subject-type (keyword type)]
          :when (nil? (get catalog subject-type))]
    (add-issue! issues
                (issue :type-invalid-reference resource-type nil
                       [:relation relation-name :subject-type subject-type]
                       {:relation-name relation-name
                        :subject-type subject-type
                        :expected :defined-subject-type
                        :message "Relation names an undefined subject type."}))))

(defn- resolve-identifier
  [catalog resource-type permission-name path {:keys [name grouped?]} issues]
  (let [name (keyword name)
        {:keys [relations permissions]} (get catalog resource-type)
        relation-types (get relations name)
        relation? (some? relation-types)
        permission? (contains? permissions name)]
    (cond
      (and relation? permission?)
      (add-issue! issues
                  (issue :ambiguous-reference resource-type permission-name path
                         {:name name
                          :kinds [:permission :relation]
                          :message "Reference resolves to both a relation and a permission."}))

      relation?
      (expression/relation name relation-types (boolean grouped?))

      permission?
      (expression/permission name (boolean grouped?))

      :else
      (add-issue! issues
                  (issue :missing-reference resource-type permission-name path
                         {:name name
                          :message "Reference does not name a relation or permission on the resource type."})))))

(defn- target-partition
  [catalog resource-type permission-name path target-name subject-type issues]
  (let [{:keys [relations permissions]} (get catalog subject-type)
        relation? (contains? relations target-name)
        permission? (contains? permissions target-name)]
    (cond
      (nil? (get catalog subject-type))
      (add-issue! issues
                  (issue :type-invalid-reference resource-type permission-name path
                         {:subject-type subject-type
                          :name target-name
                          :expected :defined-subject-type
                          :message "Arrow source relation names an undefined subject type."}))

      (and relation? permission?)
      (add-issue! issues
                  (issue :ambiguous-reference resource-type permission-name path
                         {:subject-type subject-type
                          :name target-name
                          :kinds [:permission :relation]
                          :message "Arrow target resolves to both a relation and a permission."}))

      relation?
      {:subject-type subject-type
       :target-kind :relation
       :target-name target-name}

      permission?
      {:subject-type subject-type
       :target-kind :permission
       :target-name target-name}

      :else
      (add-issue! issues
                  (issue :missing-reference resource-type permission-name path
                         {:subject-type subject-type
                          :name target-name
                          :message "Arrow target does not exist on the source relation subject type."})))))

(defn- resolve-arrow
  [catalog resource-type permission-name path {:keys [base target grouped?]} issues]
  (let [base (keyword base)
        target (keyword target)
        {:keys [relations permissions wildcard-relations]} (get catalog resource-type)
        subject-types (get relations base)]
    (cond
      (contains? permissions base)
      (add-issue! issues
                  (issue :type-invalid-reference resource-type permission-name path
                         {:name base
                          :expected :relation
                          :actual :permission
                          :message "Arrow base must be a relation, not a permission."}))

      (nil? subject-types)
      (add-issue! issues
                  (issue :missing-reference resource-type permission-name path
                         {:name base
                          :expected :relation
                          :message "Arrow base relation does not exist on the resource type."}))

      (contains? wildcard-relations base)
      (add-issue! issues
                  (issue :wildcard-arrow-base resource-type permission-name path
                         {:name base
                          :message (str "Relation " (name resource-type) "#" (name base)
                                        " includes a wildcard subject type: wildcard"
                                        " relations cannot be used on the left side of arrows.")}))

      :else
      (let [partitions
            (mapv (fn [subject-type]
                    (target-partition catalog resource-type permission-name
                                      (conj path :partition subject-type)
                                      target subject-type issues))
                  (sort-by str (distinct subject-types)))
            present (vec (keep identity partitions))
            target-kinds (set (map :target-kind present))]
        (cond
          (not= (count present) (count (distinct subject-types)))
          nil

          (> (count target-kinds) 1)
          (add-issue! issues
                      (issue :ambiguous-reference resource-type permission-name path
                             {:name target
                              :subject-types (vec (sort-by str (distinct subject-types)))
                              :kinds (vec (sort-by str target-kinds))
                              :message "Arrow target kind differs across source relation subject types."}))

          :else
          (expression/arrow base present (boolean grouped?)))))))

(defn- resolve-children
  [catalog resource-type permission-name path children issues]
  (mapv (fn [index child]
          (resolve-node catalog resource-type permission-name
                        (conj path :child index) child issues))
        (range)
        children))

(defn- resolve-node
  [catalog resource-type permission-name path node issues]
  (case (:op node)
    :identifier
    (resolve-identifier catalog resource-type permission-name path node issues)

    :arrow
    (resolve-arrow catalog resource-type permission-name path node issues)

    :self
    (expression/self-leaf (boolean (:grouped? node)))

    :union
    (let [children (resolve-children catalog resource-type permission-name
                                     path (:children node) issues)]
      (when (every? some? children)
        (expression/union children (boolean (:grouped? node)))))

    :intersection
    (let [children (resolve-children catalog resource-type permission-name
                                     path (:children node) issues)]
      (when (every? some? children)
        (expression/intersection children (boolean (:grouped? node)))))

    :exclusion
    (let [left (resolve-node catalog resource-type permission-name
                             (conj path :left) (:left node) issues)
          right (resolve-node catalog resource-type permission-name
                              (conj path :right) (:right node) issues)]
      (when (and left right)
        (expression/exclusion left right (boolean (:grouped? node)))))

    (add-issue! issues
                (issue :type-invalid-reference resource-type permission-name path
                       {:node node
                        :message "Parser produced an unknown permission-expression node."}))))

(defn- eacl-limitation?
  "Resolution issues of schemas SpiceDB accepts: an arrow target that some
   subject type of the source relation lacks, or a target that is a relation
   on some subject types and a permission on others."
  [{:keys [type subject-type subject-types path]}]
  (or (and (= :missing-reference type) (some? subject-type) (some #{:partition} path))
      (and (= :ambiguous-reference type) (some? subject-types))))

(defn resolve-definitions-with-metadata
  "Resolves and bounds every permission from parser/transform-schema
   definitions. Source-tree limits are checked before recursive resolved-node
   construction. Normalized DAG limits are checked immediately after the
   canonical expression is available.

   Returns expressions and aligned metadata vectors sorted by
   [resource-type permission-name]. Any reference failure rejects the complete
   candidate schema with a deterministically sorted :errors vector."
  ([definitions]
   (resolve-definitions-with-metadata definitions {}))
  ([definitions limits]
  (let [catalog (catalog definitions)
        issues (atom [])
        _ (validate-relation-types! definitions catalog issues limits)
        resolved
        (reduce
          (fn [result [resource-type permission-name parsed-expression]]
            (let [source (parser/permission-expression->source-ast
                           parsed-expression)
                  source-metrics (expression-limits/check-source!
                                   source limits)
                  root (resolve-node catalog resource-type permission-name
                                     [:root] source issues)]
              (if-not root
                result
                (let [resolved-expression
                      (expression/expression resource-type permission-name root)
                      {:keys [encoded-byte-size]}
                      (expression-limits/check-expression-bytes!
                        resolved-expression limits)
                      {:keys [dag metrics]}
                      (expression-limits/check-normalized!
                        resolved-expression limits)]
                  (conj result
                        {:expression resolved-expression
                         :source-metrics source-metrics
                         :encoded-byte-size encoded-byte-size
                         :normalized-dag dag
                         :normalized-metrics metrics})))))
          []
          (for [[resource-type {:keys [permissions]}]
                (sort-by key definitions)
                {:keys [name expression]} (sort-by :name permissions)]
            [(keyword resource-type) (keyword name) expression]))
        errors (->> @issues
                    distinct
                    (sort-by issue-sort-key)
                    vec)]
    (when (seq errors)
      (if (every? eacl-limitation? errors)
        ;; SpiceDB accepts these arrows: a target that a subject type lacks
        ;; contributes nothing there. EACL resolves every target partition.
        (throw (ex-info (str "Unsupported feature: " (:message (first errors))
                             " SpiceDB accepts this arrow; EACL requires its target on every"
                             " subject type of the source relation, with one kind.")
                        {:type :eacl.schema/unsupported-feature
                         :eacl/error :eacl.schema/unsupported-feature
                         :issues (mapv #(assoc % :type :arrow-target :reason (:type %)) errors)
                         :issue-count (count errors)}))
        (throw (ex-info "Permission-expression reference resolution failed."
                        {:type :eacl.schema/expression-resolution-failed
                         :eacl/error :eacl.schema/expression-resolution-failed
                         :errors errors
                         :error-count (count errors)}))))
    (let [metadata (mapv #(dissoc % :expression) resolved)]
      {:expressions (mapv :expression resolved)
       :metadata metadata
       :aggregate-metrics
       (expression-limits/check-aggregate! metadata limits)}))))

(defn- uses-self?
  [root]
  (loop [pending [root]]
    (if-let [node (peek pending)]
      (case (:op node)
        :self true
        (:union :intersection) (recur (into (pop pending) (:children node)))
        :exclusion (recur (conj (pop pending) (:left node) (:right node)))
        (recur (pop pending)))
      false)))

(defn- self-relations
  "The identity Relation (`expression/self-relation`) of every definition
   whose permissions use `self`, sorted by definition."
  [expressions]
  (->> expressions
       (filter (comp uses-self? :root))
       (map :resource-type)
       distinct
       (sort-by str)
       (mapv #(model/Relation % expression/self-relation %))))

(defn resolve-parse-tree
  "Validates parser-level restrictions and resolves every expression in one
   parsed candidate schema without invoking flat permission storage."
  ([parse-tree]
   (resolve-parse-tree parse-tree {}))
  ([parse-tree limits]
   (resolve-parse-tree parse-tree limits {}))
  ([parse-tree limits admission]
   (let [transformed (parser/transform-schema parse-tree limits)
         ;; Expression storage represents wildcard branches; only the legacy
         ;; flat projection (parser/->eacl-schema) keeps rejecting them.
         _ (parser/validate-eacl-restrictions
            parse-tree transformed (assoc admission :allow-wildcards? true))
         relations
         (parser/relation-entities
          transformed {:strict? (true? (:allow-caveats? admission))})
         {:keys [expressions metadata aggregate-metrics]}
         (resolve-definitions-with-metadata (:definitions transformed) limits)
         dependency-certificate
         (try
           (expression-graph/build-certificate expressions)
           (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
             (if (= :eacl.schema/unstratified-exclusion (:type (ex-data e)))
               ;; SpiceDB accepts recursion through an exclusion; EACL evaluates
               ;; exclusion only over a completed lower stratum.
               (throw (ex-info (str "Unsupported feature: " (ex-message e)
                                    " SpiceDB accepts this schema; EACL requires every"
                                    " exclusion to subtract a permission that does not depend on it.")
                               (assoc (ex-data e)
                                      :type :eacl.schema/unsupported-feature
                                      :eacl/error :eacl.schema/unsupported-feature
                                      :issues [(assoc (select-keys (ex-data e) [:negative-edge :cycle])
                                                      :type :unstratified-exclusion)]
                                      :issue-count 1)
                               e))
               (throw e))))]
     (cond-> {:definitions (mapv (comp keyword key)
                                 (sort-by key (:definitions transformed)))
              :expressions expressions
              :relations (into relations (self-relations expressions))
              :expression-metadata metadata
              :aggregate-expression-metrics aggregate-metrics
              :dependency-certificate dependency-certificate}
       (seq (:caveats transformed)) (assoc :caveats (:caveats transformed))))))

(defn validate-schema
  "Parses and validates one complete schema under a client-local checked
   expression-limit profile. Limits affect admission only and are not returned
   as durable schema fields."
  ([schema-source]
   (validate-schema schema-source nil))
  ([schema-source expression-limits]
   (validate-schema schema-source expression-limits {}))
  ([schema-source expression-limits admission]
   (let [expression-limits
         (expression-policy/normalize-client-limits expression-limits)]
     (assoc
      (resolve-parse-tree
       (parser/parse-schema
        schema-source
        (select-keys expression-limits
                     (keys expression-policy/schema-limits)))
       expression-limits admission)
      :expression-limits expression-limits))))

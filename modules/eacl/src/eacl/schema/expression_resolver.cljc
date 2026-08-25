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
                     (mapv (comp keyword :type) type-refs)]))
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
                    (count type-refs)
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
        {:keys [relations permissions]} (get catalog resource-type)
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
      (throw (ex-info "Permission-expression reference resolution failed."
                      {:type :eacl.schema/expression-resolution-failed
                       :eacl/error :eacl.schema/expression-resolution-failed
                       :errors errors
                       :error-count (count errors)})))
    (let [metadata (mapv #(dissoc % :expression) resolved)]
      {:expressions (mapv :expression resolved)
       :metadata metadata
       :aggregate-metrics
       (expression-limits/check-aggregate! metadata limits)}))))

(defn resolve-definitions
  "Resolves every permission and returns canonical expressions sorted by
   [resource-type permission-name]. The optional limits map accepts the source
   and normalized dimensions from eacl.schema.expression-limits."
  ([definitions]
   (resolve-definitions definitions {}))
  ([definitions limits]
   (:expressions (resolve-definitions-with-metadata definitions limits))))

(defn resolve-parse-tree
  "Validates parser-level restrictions and resolves every expression in one
   parsed candidate schema without invoking flat permission storage."
  ([parse-tree]
   (resolve-parse-tree parse-tree {}))
  ([parse-tree limits]
   (let [transformed (parser/transform-schema parse-tree)
         _ (parser/validate-eacl-restrictions parse-tree transformed)
         relations
         (vec
           (for [[resource-type {:keys [relations]}]
                 (sort-by key (:definitions transformed))
                 [relation-name type-refs] (sort-by key relations)
                 {:keys [type]} (sort-by :type type-refs)]
             (model/Relation (keyword resource-type)
                             (keyword relation-name)
                             (keyword type))))
         {:keys [expressions metadata aggregate-metrics]}
         (resolve-definitions-with-metadata (:definitions transformed) limits)
         dependency-certificate
         (expression-graph/build-certificate expressions)]
     {:definitions (mapv (comp keyword key)
                         (sort-by key (:definitions transformed)))
      :expressions expressions
      :relations relations
      :expression-metadata metadata
      :aggregate-expression-metrics aggregate-metrics
      :dependency-certificate dependency-certificate})))

(defn validate-schema
  "Parses and validates one complete schema under the checked-in expression
   admission policy. The returned compatibility value/digest must be included
   by expression storage and operator plan fingerprints."
  ([schema-source]
   (validate-schema schema-source expression-policy/compatibility-value))
  ([schema-source policy]
   (when-not (= expression-policy/compatibility-value policy)
     (throw (ex-info "Unsupported permission-expression policy."
              {:type :eacl.schema/unsupported-expression-policy
               :eacl/error :eacl.schema/unsupported-expression-policy
               :expected expression-policy/compatibility-value
               :actual policy})))
   (assoc
     (resolve-parse-tree
       (parser/parse-schema schema-source (:schema-limits policy))
       (merge (:per-permission-limits policy)
              (:aggregate-limits policy)))
     :expression-policy policy
     :expression-policy-digest expression-policy/compatibility-digest)))

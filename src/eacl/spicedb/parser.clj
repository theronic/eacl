(ns eacl.spicedb.parser
  "SpiceDB schema DSL parser for EACL."
  (:require [instaparse.core :as insta]
            [clojure.pprint]
            [clojure.string]
            [clojure.walk]
            [eacl.datomic.impl :as impl]))

;      primary-expr = identifier | <'('> permission-expr <')'>
;; Define the SpiceDB grammar with auto-whitespace
;; Full SpiceDB grammar - parses the complete official syntax.
;; EACL-specific restrictions are enforced during validation, not parsing.
(def spicedb-parser
  (insta/parser
    "(* Top-level schema *)
      schema = definition+

      (* Definition block *)
      definition = <'definition'> type-path <'{'> definition-body <'}'>
      definition-body = (relation | permission)*

      (* Type paths support namespacing: docs/document *)
      type-path = identifier (<'/'> identifier)*

      (* Relations *)
      relation = <'relation'> relation-name <':'> relation-type-expr
      relation-name = identifier

      (* Relation type expression: user | group#member | doc:* with caveat *)
      relation-type-expr = relation-type-ref (<'|'> relation-type-ref)*
      relation-type-ref = type-path relation-modifier? caveat-ref?
      relation-modifier = wildcard | subject-relation
      wildcard = <':'> <'*'>
      subject-relation = <'#'> identifier
      caveat-ref = <'with'> identifier

      (* Permissions *)
      permission = <'permission'> permission-name <'='> permission-expr
      <permission-name> = identifier

      (* Permission expressions with all operators *)
      permission-expr = union-expr
      union-expr = intersect-expr (<'+'> intersect-expr)*
      intersect-expr = exclusion-expr (<'&'> exclusion-expr)*
      exclusion-expr = arrow-expr (<'-'> arrow-expr)*

      (* Arrow expressions: rel->perm or rel.any(perm) or rel.all(perm) *)
      arrow-expr = arrow-func-expr | simple-arrow-expr
      simple-arrow-expr = base-expr (<'->'> base-expr)*
      arrow-func-expr = identifier <'.'> arrow-func-name <'('> identifier <')'>
      arrow-func-name = 'any' | 'all'

      (* Base expressions *)
      base-expr = nil-expr | self-expr | paren-expr | identifier
      nil-expr = <'nil'>
      self-expr = <'self'>
      paren-expr = <'('> permission-expr <')'>

      (* Identifiers - must not match keywords *)
      identifier = !('nil' | 'self' | 'definition' | 'relation' | 'permission' | 'with' | 'any' | 'all') #'[a-zA-Z_][a-zA-Z0-9_]*'"
    :auto-whitespace :standard))

;; Example SpiceDB schema
(def example-schema
  "definition user {}

   definition platform {
     relation super_admin: user
   }

   definition account {
     relation platform: platform
     relation owner: user

     permission admin = owner + platform->super_admin
     permission view = owner + admin
     permission update = admin
   }")

;; Parse the schema
(defn parse-schema [schema-str]
  (spicedb-parser schema-str))

;; Pretty print parse tree
(defn pretty-print-tree [tree]
  (clojure.pprint/pprint tree))

;; ============================================================================
;; Parse Tree Extraction Functions (for new full SpiceDB grammar)
;; ============================================================================

(defn- extract-identifier
  "Extracts the string value from an [:identifier 'name'] node."
  [node]
  (when (and (vector? node) (= :identifier (first node)))
    (second node)))

(defn- extract-type-path
  "Extracts a type path string from [:type-path [:identifier 'a'] [:identifier 'b']] -> 'a/b'"
  [node]
  (when (and (vector? node) (= :type-path (first node)))
    (->> (rest node)
      (map extract-identifier)
      (clojure.string/join "/"))))

(defn- extract-relation-type-ref
  "Extracts a relation type reference with optional modifier and caveat.
   Returns {:type 'user', :wildcard? false, :subject-relation nil, :caveat nil}"
  [node]
  (when (and (vector? node) (= :relation-type-ref (first node)))
    (let [children  (rest node)
          type-path (extract-type-path (first children))
          modifier  (some #(when (and (vector? %) (= :relation-modifier (first %))) %) children)
          caveat    (some #(when (and (vector? %) (= :caveat-ref (first %))) %) children)]
      {:type             type-path
       :wildcard?        (boolean (some #(and (vector? %) (= :wildcard (first %))) (rest modifier)))
       :subject-relation (when-let [sr (some #(when (and (vector? %) (= :subject-relation (first %))) %) (rest modifier))]
                           (extract-identifier (second sr)))
       :caveat           (when caveat (extract-identifier (second caveat)))})))

(defn- extract-relation-type-expr
  "Extracts all type refs from a relation-type-expr.
   Returns vector of type ref maps."
  [node]
  (when (and (vector? node) (= :relation-type-expr (first node)))
    (vec (map extract-relation-type-ref (rest node)))))

(defn extract-relations
  "Extract relations from definition body.
   Returns a map where each key is a relation name and value is a vector of type refs."
  [definition-body]
  (if (and (vector? definition-body) (= :definition-body (first definition-body)))
    (->> (rest definition-body)
      (filter #(and (vector? %) (= :relation (first %))))
      (map (fn [[_ rel-name-node type-expr-node]]
             (let [rel-name  (extract-identifier (second rel-name-node))
                   type-refs (extract-relation-type-expr type-expr-node)]
               [rel-name type-refs])))
      (into {}))
    {}))

(defn extract-permissions
  "Extract permissions from definition body.
   Returns a vector of {:name 'perm-name', :expression <parse-tree>}"
  [definition-body]
  (if (and (vector? definition-body) (= :definition-body (first definition-body)))
    (->> (rest definition-body)
      (filter #(and (vector? %) (= :permission (first %))))
      (map (fn [[_ perm-name-node expr]]
             {:name       (extract-identifier perm-name-node)
              :expression expr})))
    []))

(defn extract-definitions
  "Extract definitions from parse tree.
   Returns map of {type-path {:relations {...}, :permissions [...]}}"
  [parse-tree]
  (->> parse-tree
    (filter #(and (vector? %) (= :definition (first %))))
    (map (fn [[_ type-path-node definition-body]]
           (let [type-path (extract-type-path type-path-node)]
             [type-path
              {:relations   (extract-relations definition-body)
               :permissions (extract-permissions definition-body)}])))
    (into {})))

(defn transform-schema
  "Transform parse tree to intermediate representation."
  [parse-tree]
  (when (and (vector? parse-tree) (= :schema (first parse-tree)))
    {:definitions (extract-definitions (rest parse-tree))}))

;; Helper to parse expressions
(defn parse-permission-expression [expr-str]
  (let [full-schema (str "definition temp { permission test = " expr-str " }")
        parsed      (spicedb-parser full-schema)]
    (if (insta/failure? parsed)
      (do
        (println "Failed to parse expression:" expr-str)
        (println "Error:" (insta/get-failure parsed))
        nil)
      ;; Path: schema -> definition -> definition-body -> permission -> permission-expr
      (get-in parsed [1 2 1 2]))))

;; Transform expressions to a more usable format
(defn transform-expression [expr]
  (cond
    (vector? expr)
    (case (first expr)
      :union-expr (let [operands (rest expr)]
                    (if (= 1 (count operands))
                      (transform-expression (first operands))
                      {:type     :union
                       :operands (map transform-expression operands)}))
      :arrow-expr (let [parts (rest expr)]
                    (if (= 1 (count parts))
                      (transform-expression (first parts))
                      {:type :arrow
                       :base (transform-expression (first parts))
                       :path (map #(second %) (rest parts))}))
      :primary-expr (transform-expression (second expr))
      :identifier {:type :identifier :name (second expr)}
      :permission-expr (let [children (rest expr)
                             operands (filter #(not= :permission-operator (first %)) children)]
                         (if (= 1 (count operands))
                           (transform-expression (first operands))
                           {:type     :union
                            :operands (map transform-expression operands)}))
      :direct-permission {:type :identifier :name (second expr)}
      :arrow-permission {:type :arrow
                         :base {:type :identifier :name (second expr)}
                         :path [(nth expr 2)]}
      expr)
    :else expr))

;; Pretty print expressions in a readable format
(defn format-expression [expr]
  (cond
    (map? expr)
    (case (:type expr)
      :union (str "(" (clojure.string/join " + " (map format-expression (:operands expr))) ")")
      :arrow (str (format-expression (:base expr)) "->" (clojure.string/join "->" (:path expr)))
      :identifier (:name expr)
      (str expr))
    :else (str expr)))

;; Analyze a specific definition
(defn analyze-definition [schema-str def-name]
  (let [parsed (parse-schema schema-str)]
    (when-not (insta/failure? parsed)
      (let [definitions (extract-definitions (rest parsed))
            target-def  (first (filter #(= def-name (:name %)) definitions))]
        (when target-def
          (println (str "Definition: " def-name))
          (println "Relations:")
          (doseq [rel (:relations target-def)]
            (println (str "  " (:name rel) " : " (:type rel))))
          (println "Permissions:")
          (doseq [perm (:permissions target-def)]
            (println (str "  " (:name perm) " = "
                       (format-expression (transform-expression (:expression perm))))))
          target-def)))))

;; Usage examples
(defn demo []
  (println "=== Parsing SpiceDB Schema ===")

  ;; Parse the full schema
  (let [parsed (parse-schema example-schema)]
    (if (insta/failure? parsed)
      (do
        (println "Parse failed:")
        (println (insta/get-failure parsed)))
      (do
        (println "\n1. Schema structure:")
        (let [transformed (transform-schema parsed)]
          (doseq [def (:definitions transformed)]
            (println (str "- " (:name def)
                       " (" (count (:relations def)) " relations, "
                       (count (:permissions def)) " permissions)"))))

        (println "\n2. Detailed breakdown:")
        (doseq [def-name ["user" "platform" "account"]]
          (analyze-definition example-schema def-name)
          (println)))))

  (rest (get (parse-permission-expression "owner + platform->super_admin") 1))

  ;; Parse individual expressions
  (println "\n=== Parsing Individual Expressions ===")
  (doseq [expr ["owner + admin"
                "platform->super_admin"
                "owner + platform->super_admin"
                "account->admin + vpc->admin + shared_admin"]]
    (let [parsed-expr (parse-permission-expression expr)]
      (if parsed-expr
        (do
          (println (str "Expression: " expr))
          (println (str "Parsed as: " (format-expression (transform-expression parsed-expr))))
          (println (str "Structure: " (transform-expression parsed-expr)))
          (println))
        (println (str "Failed to parse: " expr)))))

  ;; Demonstrate error handling
  (println "\n=== Error Handling ===")
  (let [bad-schema "definition user { invalid syntax }"]
    (let [result (parse-schema bad-schema)]
      (if (insta/failure? result)
        (println "Parse error (expected):" (insta/get-failure result))
        (println "Unexpected success")))))

;; ============================================================================
;; EACL Validation Functions
;; Validates that parsed SpiceDB schemas conform to EACL restrictions.
;; Parsing accepts full SpiceDB syntax; validation enforces EACL limits.
;; ============================================================================

(defn- collect-parse-tree-issues
  "Walks parse tree and collects all EACL compatibility issues.
   Returns a vector of issue maps with informative error messages."
  [parse-tree]
  (let [issues (atom [])]
    (clojure.walk/postwalk
      (fn [node]
        (when (vector? node)
          (case (first node)
            ;; Check for intersection operator
            :intersect-expr
            (when (> (count (rest node)) 1)
              (swap! issues conj
                {:type     :unsupported-operator
                 :operator "&"
                 :message  "Unsupported operator: Intersection (&). EACL only supports Union (+) at this time."}))

            ;; Check for exclusion operator
            :exclusion-expr
            (when (> (count (rest node)) 1)
              (swap! issues conj
                {:type     :unsupported-operator
                 :operator "-"
                 :message  "Unsupported operator: Exclusion (-). EACL only supports Union (+) at this time."}))

            ;; Check for multi-level arrows
            :simple-arrow-expr
            (when (> (count (filter #(and (vector? %) (= :base-expr (first %))) (rest node))) 2)
              (swap! issues conj
                {:type    :multi-level-arrow
                 :message "Unsupported feature: Multi-level arrows (e.g., a->b->c). EACL only supports single-level arrows like rel->perm."}))

            ;; Check for .all() function (only .any() is implicitly supported via arrow)
            :arrow-func-expr
            (let [func-name-node (some #(when (and (vector? %) (= :arrow-func-name (first %))) %) (rest node))
                  func-name      (second func-name-node)]
              (when (= func-name "all")
                (swap! issues conj
                  {:type     :unsupported-arrow-function
                   :function "all"
                   :message  "Unsupported function: .all(). EACL only supports .any() (equivalent to -> arrow). Use rel->perm instead."})))

            ;; Check for nil expression
            :nil-expr
            (swap! issues conj
              {:type    :unsupported-keyword
               :keyword "nil"
               :message "Unsupported keyword: 'nil'. EACL does not support nil permissions."})

            ;; Check for self expression (might be supportable in future)
            :self-expr
            (swap! issues conj
              {:type    :unsupported-keyword
               :keyword "self"
               :message "Unsupported keyword: 'self'. EACL does not support self-referencing permissions."})

            ;; Check for type paths with namespaces
            :type-path
            (when (> (count (rest node)) 1)
              (swap! issues conj
                {:type    :namespaced-type
                 :message "Unsupported feature: Namespaced type paths (e.g., docs/document). Use simple type names like 'document'."}))

            ;; Default: no issue for other node types
            nil))
        node)
      parse-tree)
    @issues))

(defn- collect-relation-issues
  "Check relations for EACL compatibility issues.
   Takes the transformed schema definitions map."
  [definitions]
  (let [issues (atom [])]
    (doseq [[res-type {:keys [relations]}] definitions
            [rel-name type-refs] relations
            type-ref type-refs]
      ;; Check for wildcards
      (when (:wildcard? type-ref)
        (swap! issues conj
          {:type          :wildcard-relation
           :resource-type res-type
           :relation      rel-name
           :message       (str "Unsupported feature: Wildcard relation '" (:type type-ref) ":*' in "
                            res-type "/" rel-name ". EACL does not support public/wildcard access.")}))

      ;; Check for subject relations
      (when (:subject-relation type-ref)
        (swap! issues conj
          {:type             :subject-relation
           :resource-type    res-type
           :relation         rel-name
           :subject-relation (:subject-relation type-ref)
           :message          (str "Unsupported feature: Subject relation '" (:type type-ref) "#" (:subject-relation type-ref)
                               "' in " res-type "/" rel-name ". EACL does not support nested subject relations.")}))

      ;; Check for caveats
      (when (:caveat type-ref)
        (swap! issues conj
          {:type          :caveat
           :resource-type res-type
           :relation      rel-name
           :caveat        (:caveat type-ref)
           :message       (str "Unsupported feature: Caveat 'with " (:caveat type-ref) "' in "
                            res-type "/" rel-name ". EACL does not support conditional access via caveats.")})))
    @issues))

(defn validate-eacl-restrictions
  "Validates that a parsed SpiceDB schema conforms to EACL restrictions.
   Takes a parse tree and throws ex-info if any unsupported features are found.

   EACL restrictions:
   - Only union (+) operator allowed (no intersection &, exclusion -)
   - Only single-level arrows (no a->b->c)
   - No .all() arrow function (only implicit .any() via arrow)
   - No nil keyword
   - No self keyword
   - No namespaced type paths (docs/document)
   - No wildcards (user:*)
   - No subject relations (group#member)
   - No caveats (with caveatname)

   Returns nil if valid, throws ex-info with :issues vector if invalid."
  [parse-tree transformed-schema]
  (let [parse-issues    (collect-parse-tree-issues parse-tree)
        relation-issues (collect-relation-issues (:definitions transformed-schema))
        all-issues      (vec (concat parse-issues relation-issues))]
    (when (seq all-issues)
      (let [first-msg (:message (first all-issues))
            total     (count all-issues)
            summary   (if (= 1 total)
                        first-msg
                        (str first-msg " (and " (dec total) " more issue(s))"))]
        (throw (ex-info summary
                 {:issues      all-issues
                  :issue-count total}))))
    nil))

;; Keep old function for backwards compatibility, but delegate to new validation
(defn validate-operators
  "DEPRECATED: Use validate-eacl-restrictions instead.
   Kept for backwards compatibility."
  [parse-tree]
  (let [issues (filter #(= :unsupported-operator (:type %))
                 (collect-parse-tree-issues parse-tree))]
    (when (seq issues)
      (throw (ex-info "Unsupported operator in schema"
               {:operators (set (map :operator issues))
                :message   (str "EACL only supports union (+) operators. "
                             "Found unsupported operators. "
                             "Exclusion (-) and intersection (&) are not supported.")})))))

;; ============================================================================
;; Permission Expression Transformation
;; Converts new grammar parse tree to component list for EACL
;; ============================================================================

(defn- extract-base-expr-identifier
  "Extract identifier string from a base-expr node."
  [node]
  (when (and (vector? node) (= :base-expr (first node)))
    (let [child (second node)]
      (when (and (vector? child) (= :identifier (first child)))
        (second child)))))

(defn- transform-arrow-expr
  "Transform an arrow expression to component maps.
   Returns vector of {:type :identifier/:arrow, ...} maps."
  [node]
  (cond
    ;; Arrow function expression: rel.any(perm) or rel.all(perm)
    (and (vector? node) (= :arrow-func-expr (first node)))
    (let [children  (rest node)
          base-id   (extract-identifier (first children))
          func-node (some #(when (and (vector? %) (= :arrow-func-name (first %))) %) children)
          func-name (second func-node)
          target-id (extract-identifier (last children))]
      ;; .any() is equivalent to arrow, .all() should have been rejected by validation
      [{:type :arrow :base {:type :identifier :name base-id} :path [target-id]}])

    ;; Simple arrow expression: rel->perm or rel->perm->perm2
    (and (vector? node) (= :simple-arrow-expr (first node)))
    (let [base-exprs (filter #(and (vector? %) (= :base-expr (first %))) (rest node))
          ids        (map extract-base-expr-identifier base-exprs)]
      (if (= 1 (count ids))
        ;; Single identifier - direct permission/relation reference
        [{:type :identifier :name (first ids)}]
        ;; Arrow expression
        [{:type :arrow :base {:type :identifier :name (first ids)} :path (vec (rest ids))}]))

    ;; Wrapped arrow expr
    (and (vector? node) (= :arrow-expr (first node)))
    (transform-arrow-expr (second node))

    :else []))

(defn- transform-exclusion-expr [node]
  "Transform exclusion expression. After validation, this should only have one child."
  (when (and (vector? node) (= :exclusion-expr (first node)))
    (mapcat transform-arrow-expr (rest node))))

(defn- transform-intersect-expr [node]
  "Transform intersection expression. After validation, this should only have one child."
  (when (and (vector? node) (= :intersect-expr (first node)))
    (mapcat transform-exclusion-expr (rest node))))

(defn- transform-union-expr [node]
  "Transform union expression to flat list of components."
  (when (and (vector? node) (= :union-expr (first node)))
    (vec (mapcat transform-intersect-expr (rest node)))))

(defn- flatten-expression
  "Flatten a permission expression to a vector of component maps.
   Each component is {:type :identifier/:arrow, ...}"
  [expr]
  (when (and (vector? expr) (= :permission-expr (first expr)))
    (transform-union-expr (second expr))))

;; ============================================================================
;; Schema Info Collection
;; ============================================================================

(defn- collect-schema-info
  "Build lookup tables from transformed schema for arrow resolution."
  [definitions]
  (reduce-kv
    (fn [acc res-type {:keys [relations permissions]}]
      (let [;; Get simple type names (first type ref for each relation)
            relation-names (set (keys relations))
            ;; Map relation name to target type (first type in list)
            relation-types (into {}
                             (for [[rel-name type-refs] relations
                                   :let [first-ref (first type-refs)]
                                   :when first-ref]
                               [rel-name (:type first-ref)]))]
        (assoc acc res-type
                   {:relations          relation-names
                    :relation-types     relation-types
                    :relation-all-types relations
                    :permissions        (set (map :name permissions))})))
    {}
    definitions))

;; ============================================================================
;; Component Resolution
;; ============================================================================

(defn- resolve-component
  "Resolve a component map to EACL Permission spec.
   component: {:type :identifier/:arrow, :name '...', :base {...}, :path [...]}"
  [component resource-type schema-info]
  (case (:type component)
    :identifier
    (let [name (:name component)
          info (get schema-info resource-type)]
      (if (contains? (:relations info) name)
        {:relation (keyword name)}
        {:permission (keyword name)}))

    :arrow
    (let [base-name     (-> component :base :name)
          path-elements (:path component)
          path          (first path-elements)
          info          (get schema-info resource-type)
          target-type   (get-in info [:relation-types base-name])]
      (if-not target-type
        (throw (ex-info (str "Unknown relation for arrow base: " base-name " on " resource-type)
                 {:component component :resource-type resource-type}))
        (let [target-info        (get schema-info target-type)
              target-is-relation (contains? (:relations target-info) path)]
          {:arrow                                        (keyword base-name)
           (if target-is-relation :relation :permission) (keyword path)})))

    (throw (ex-info "Unsupported component type" {:component component}))))

;; ============================================================================
;; Main Transformation Function
;; ============================================================================

(defn ->eacl-schema
  "Convert parsed SpiceDB schema to EACL internal representation.

   Steps:
   1. Transform parse tree to intermediate representation
   2. Validate EACL restrictions (throws on unsupported features)
   3. Convert to EACL Relations and Permissions

   Returns {:relations [...] :permissions [...]}"
  [parse-tree]
  (let [transformed (transform-schema parse-tree)]
    ;; Validate EACL restrictions (parsing allows full SpiceDB, validation enforces limits)
    (validate-eacl-restrictions parse-tree transformed)

    (let [definitions (:definitions transformed)
          schema-info (collect-schema-info definitions)]
      {:relations
       (vec
         ;; Expand multi-type relations into multiple Relation entities
         (for [[res-type {:keys [relations]}] definitions
               [rel-name type-refs] relations
               type-ref type-refs
               :let [subject-type (:type type-ref)]]
           (impl/Relation (keyword res-type) (keyword rel-name) (keyword subject-type))))

       :permissions
       (vec
         (apply concat
           (for [[res-type {:keys [permissions]}] definitions
                 {:keys [name expression]} permissions]
             (let [components (flatten-expression expression)]
               (for [comp components
                     :when comp]
                 (let [spec (resolve-component comp res-type schema-info)]
                   (impl/Permission (keyword res-type) (keyword name) spec)))))))})))

(defn extract-expr [permission-exp]
  (-> permission-exp
    (get 1)
    (rest)))

;; Run the demo
(comment
  (def *defs
    (let [parse-tree (parse-schema example-schema)]
      (:definitions (transform-schema parse-tree))))

  (doall
    (for [[resource-type spec :as definition] *defs]
      (do
        (prn 'definition definition)
        (let [{:keys [relations permissions]} spec]
          (concat
            (for [{:as           relation
                   relation-name :name
                   subject-type  :type} relations]
              (do
                (prn 'relation relation)
                (impl/Relation (keyword resource-type) (keyword relation-name) (keyword subject-type))))
            (for [{:as             permission
                   permission-name :name
                   expr            :expression} permissions]
              (extract-expr expr)))))))

  (demo)
  (analyze-definition example-schema "account"))

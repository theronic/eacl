(ns eacl.spicedb.parser
  "SpiceDB schema DSL parser for EACL."
  (:require [instaparse.core :as insta]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [eacl.schema.model :as model]
            [eacl.caveats.definition :as caveat-definition]
            [eacl.secure-format :as secure]))

;      primary-expr = identifier | <'('> permission-expr <')'>
;; SpiceDB declarations are line-oriented: a relation or permission must end
;; before the next declaration or the definition's closing brace. Keep line
;; endings out of auto-whitespace so the grammar can enforce that boundary.
(def ^:private horizontal-whitespace
  (insta/parser "horizontal-whitespace = #'[ \\t\\f]+'"))

;; SpiceDB schemas may contain // line comments and /* */ block comments
;; anywhere whitespace is legal. Modelled as auto-whitespace per the
;; instaparse whitespace-or-comments idiom. [\s\S] is portable to JavaScript,
;; whose RegExp does not support Java's inline (?s) dotall flag.
(def ^:private whitespace-or-comments
  (insta/parser
    "ws-or-comments = ws | comments
     comments = comment+
     comment = #'//[^\\n\\r]*' | #'/\\*[\\s\\S]*?\\*/'
     ws = #'[ \\t\\f]+'"
    :auto-whitespace horizontal-whitespace))

;; Define the SpiceDB grammar with auto-whitespace
;; Full SpiceDB grammar - parses the complete official syntax.
;; EACL-specific restrictions are enforced during validation, not parsing.
(def spicedb-parser
  (insta/parser
    "(* Top-level schema *)
      schema = line-end* (definition | caveat-definition) (line-end* (definition | caveat-definition))* line-end*

      (* Named typed Caveats; the expression parser owns CEL profile checks. *)
      caveat-definition = <'caveat'> identifier <'('> line-end* caveat-parameters? line-end* <')'> <'{'> caveat-source <'}'>
      caveat-parameters = caveat-parameter (<','> line-end* caveat-parameter)*
      caveat-parameter = identifier caveat-type
      caveat-type = identifier (<'<'> identifier <'>'>)?
      caveat-source = #'(?:\"(?:\\\\.|[^\"\\\\])*\"|//[^\\n\\r]*|[^}\"/]|/(?!/))*'

      (* Definition block *)
      definition = <'definition'> type-path <'{'> line-end* definition-body line-end* <'}'>
      definition-body = ((relation | permission) line-end+)*
      <line-end> = <#'\\r\\n|\\n|\\r'>

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

      (* Permission expressions. EACL's public precedence is deliberately
         union-before-intersection-before-exclusion: + binds tighter than &,
         and & binds tighter than -. Repeated exclusion is folded from the
         left by the source-AST transformer. *)
      permission-expr = exclusion-expr
      exclusion-expr = intersect-expr (<'-'> intersect-expr)*
      intersect-expr = union-expr (<'&'> union-expr)*
      union-expr = arrow-expr (<'+'> arrow-expr)*

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

      (* Identifiers - must not match keywords. The keyword guard requires a
         word boundary: a bare prefix lookahead like !('all' ...) also rejected
         legal identifiers that merely START with a keyword ('allowed',
         'allocation', 'relationship', 'anytime', ...), which SpiceDB accepts. *)
      identifier = #'(?!(?:nil|self|definition|relation|permission|with|any|all)(?![a-zA-Z0-9_]))[a-zA-Z_][a-zA-Z0-9_]*'"
    :auto-whitespace whitespace-or-comments))

;; Example SpiceDB schema
;; Parse the schema
(defn parse-schema
  "Parses one schema. When :maximum-schema-source-bytes is supplied, rejects the
   source before Instaparse allocates a parse tree."
  ([schema-str]
   (parse-schema schema-str {}))
  ([schema-str {:keys [maximum-schema-source-bytes]}]
   (when-not (string? schema-str)
     (throw (ex-info "Schema source must be a string."
              {:type :eacl.schema/parse-error
               :eacl/error :eacl.schema/parse-error})))
   (when maximum-schema-source-bytes
     (when-not (and (integer? maximum-schema-source-bytes)
                    (not (neg? maximum-schema-source-bytes)))
       (throw (ex-info "Invalid schema source-byte limit."
                {:type :eacl.schema/invalid-expression-limit
                 :eacl/error :eacl.schema/invalid-expression-limit
                 :limit :maximum-schema-source-bytes
                 :value maximum-schema-source-bytes})))
     ;; UTF-16 code units are a cheap lower bound for UTF-8 bytes. Rejecting on
     ;; that bound avoids allocating a byte vector for obviously oversized
     ;; sources; the exact portable byte count is evaluated only inside it.
     (let [lower-bound (count schema-str)]
       (when (> lower-bound maximum-schema-source-bytes)
         (throw (ex-info "Schema source exceeds its byte limit."
                  {:type :eacl.schema/expression-limit
                   :eacl/error :eacl.schema/expression-limit
                   :dimension :source-bytes
                   :maximum maximum-schema-source-bytes
                   :actual-at-least lower-bound})))
       (let [actual (count (secure/utf8-bytes schema-str))]
         (when (> actual maximum-schema-source-bytes)
           (throw (ex-info "Schema source exceeds its byte limit."
                    {:type :eacl.schema/expression-limit
                     :eacl/error :eacl.schema/expression-limit
                     :dimension :source-bytes
                     :maximum maximum-schema-source-bytes
                     :actual actual}))))))
   (spicedb-parser schema-str)))

;; Pretty print parse tree
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
      (str/join "/"))))

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
   Returns a map where each key is a relation name and value is a vector of type refs.
   Throws on duplicate relation declarations (multi-type via `|` is a single declaration)."
  [definition-body]
  (if (and (vector? definition-body) (= :definition-body (first definition-body)))
    (->> (rest definition-body)
      (filter #(and (vector? %) (= :relation (first %))))
      (map (fn [[_ rel-name-node type-expr-node]]
             (let [rel-name  (extract-identifier (second rel-name-node))
                   type-refs (extract-relation-type-expr type-expr-node)]
               [rel-name type-refs])))
      (reduce (fn [acc [rel-name type-refs]]
                (if (contains? acc rel-name)
                  (throw (ex-info (str "Duplicate relation declaration: '" rel-name "'."
                                       " Declare multiple subject types once with `|`,"
                                       " e.g. `relation " rel-name ": a | b`.")
                           {:type :eacl.schema/duplicate-relation
                            :eacl/error :eacl.schema/duplicate-relation
                            :relation rel-name}))
                  (assoc acc rel-name type-refs)))
              {}))
    {}))

(defn extract-permissions
  "Extract permissions from definition body.
   Returns a vector of {:name 'perm-name', :expression <parse-tree>}.
   Throws on duplicate permission declarations: EACL evaluates every stored
   permission row with a matching name as a union, so a silently accepted
   duplicate broadens access (SpiceDB rejects duplicates at compile time)."
  [definition-body]
  (if (and (vector? definition-body) (= :definition-body (first definition-body)))
    (->> (rest definition-body)
      (filter #(and (vector? %) (= :permission (first %))))
      (map (fn [[_ perm-name-node expr]]
             {:name       (extract-identifier perm-name-node)
              :expression expr}))
      (reduce (fn [[acc seen] {perm-name :name :as permission}]
                (if (contains? seen perm-name)
                  (throw (ex-info (str "Duplicate permission declaration: '" perm-name "'."
                                       " Combine the branches into one union,"
                                       " e.g. `permission " perm-name " = a + b`.")
                           {:type :eacl.schema/duplicate-permission
                            :eacl/error :eacl.schema/duplicate-permission
                            :permission perm-name}))
                  [(conj acc permission) (conj seen perm-name)]))
              [[] #{}])
      first)
    []))

(defn extract-definitions
  "Extract definitions from parse tree.
   Returns map of {type-path {:relations {...}, :permissions [...]}}.
   Throws on duplicate definition blocks and on a permission sharing a name
   with a relation on the same definition (SpiceDB rejects both; silently
   letting the last one win produces destructive write-schema! deltas)."
  [parse-tree]
  (->> parse-tree
    (filter #(and (vector? %) (= :definition (first %))))
    (map (fn [[_ type-path-node definition-body]]
           (let [type-path   (extract-type-path type-path-node)
                 relations   (extract-relations definition-body)
                 permissions (extract-permissions definition-body)
                 collisions  (filter (set (keys relations)) (map :name permissions))]
             (when (seq collisions)
               (throw (ex-info (str "Permission and relation share a name on definition '" type-path
                                    "': " (pr-str (vec collisions)))
                        {:type :eacl.schema/name-collision
                         :eacl/error :eacl.schema/name-collision
                         :definition type-path
                         :names (vec collisions)})))
             [type-path
              {:relations   relations
               :permissions permissions}])))
    (reduce (fn [acc [type-path spec]]
              (if (contains? acc type-path)
                (throw (ex-info (str "Duplicate definition: '" type-path "'."
                                     " Each type may be defined once; merge the blocks.")
                         {:type :eacl.schema/duplicate-definition
                          :eacl/error :eacl.schema/duplicate-definition
                          :definition type-path}))
                (assoc acc type-path spec)))
            {})))

(defn extract-caveats [parse-tree]
  (->> (rest parse-tree)
       (filter #(and (vector? %) (= :caveat-definition (first %))))
       (map (fn [[_ name-node & children]]
              (let [name (extract-identifier name-node)
                    parameter-node (some #(when (= :caveat-parameters (first %)) %) children)
                    source-node (some #(when (= :caveat-source (first %)) %) children)
                    source (second source-node)
                    parameters
                    (mapv (fn [[_ parameter-name [_ type-node item-node]]]
                            (let [type (keyword (extract-identifier type-node))
                                  item (some-> item-node extract-identifier keyword)]
                              [(extract-identifier parameter-name)
                               (if item (case type :list [:list item] :map [:map :string item]
                                                   [:unsupported type item]) type)]))
                          (rest parameter-node))]
                (try (caveat-definition/entity name parameters source)
                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
                       (throw (ex-info "Invalid Caveat declaration."
                                       (assoc (ex-data error) :caveat name
                                              :source-span (insta/span source-node)) error)))))))
       (reduce (fn [by-name entity]
                 (let [name (:eacl.caveat/name entity)]
                   (when (contains? by-name name)
                     (throw (ex-info "Duplicate Caveat declaration."
                                     {:type :eacl.schema/duplicate-caveat
                                      :eacl/error :eacl.schema/duplicate-caveat :caveat name})))
                   (assoc by-name name entity)))
               (sorted-map))
       vals vec))

(defn transform-schema
  "Transform parse tree to intermediate representation.
  Throws on unexpected input; a failed parse must never coerce to an empty schema."
  [parse-tree]
  (if (and (vector? parse-tree) (= :schema (first parse-tree)))
    (let [caveats (extract-caveats parse-tree)]
      (cond-> {:definitions (extract-definitions (rest parse-tree))}
        (seq caveats) (assoc :caveats caveats)))
    (throw (ex-info "Unexpected schema parse tree; refusing to interpret as an empty schema."
             {:type :eacl.schema/parse-error
              :eacl/error :eacl.schema/parse-error
              :parse-tree parse-tree}))))

(defn staged-relation-entities
  "Non-serving schema foundation: groups plain and Caveated alternatives under
   one Relation identity. Public schema admission still rejects `with` branches."
  [{:keys [definitions caveats]}]
  (let [names (set (map :eacl.caveat/name caveats))]
    (vec
      (for [[resource-type {:keys [relations]}] (sort-by key definitions)
            [relation-name refs] (sort-by key relations)
            [subject-type alternatives] (sort-by key (group-by :type refs))]
        (let [allowances (mapv :caveat alternatives)
              qualified (sort (remove nil? allowances))]
          (when-not (= (count allowances) (count (set allowances)))
            (throw (ex-info "Duplicate Relation branch."
                            {:type :eacl.schema/duplicate-relation-branch
                             :eacl/error :eacl.schema/duplicate-relation-branch
                             :resource-type resource-type :relation relation-name :subject-type subject-type})))
          (doseq [name qualified]
            (when-not (contains? names name)
              (throw (ex-info "Relation references an undefined Caveat."
                              {:type :eacl.schema/invalid-caveat-reference
                               :eacl/error :eacl.schema/invalid-caveat-reference :caveat name}))))
          (cond-> (model/Relation (keyword resource-type) (keyword relation-name) (keyword subject-type))
            (seq qualified)
            (assoc :eacl.relation/caveats (mapv #(vector :eacl.caveat/name %) qualified)
                   :eacl.relation/allows-unqualified? (boolean (some nil? allowances)))))))))

;; Helper to parse expressions
(defn parse-permission-expression [expr-str]
  (let [full-schema (str "definition temp { permission test = " expr-str "\n}")
        parsed      (spicedb-parser full-schema)]
    (if (insta/failure? parsed)
      ;; Library fn: return nil for the caller to handle rather than
      ;; writing to stdout (the failure detail is available via
      ;; insta/get-failure on a re-parse if a caller wants it).
      nil
      ;; Path: schema -> definition -> definition-body -> permission -> permission-expr
      (get-in parsed [1 2 1 2]))))

;; Transform expressions to a more usable format
;; Pretty print expressions in a readable format
;; Analyze a specific definition
;; Usage examples
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
    (walk/postwalk
      (fn [node]
        (when (vector? node)
          (case (first node)
            ;; Check for multi-level arrows and parenthesized arrow bases/targets
            :simple-arrow-expr
            (let [base-exprs (filter #(and (vector? %) (= :base-expr (first %))) (rest node))]
              (when (> (count base-exprs) 2)
                (swap! issues conj
                  {:type    :multi-level-arrow
                   :message "Unsupported feature: Multi-level arrows (e.g., a->b->c). EACL only supports single-level arrows like rel->perm."}))
              (when (and (> (count base-exprs) 1)
                         (some #(and (vector? (second %)) (= :paren-expr (first (second %)))) base-exprs))
                (swap! issues conj
                  {:type    :paren-arrow
                   :message "Unsupported feature: Parenthesized expressions as arrow bases or targets (e.g., (a + b)->c). Arrows take a single relation base."})))

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
   - Union (+), intersection (&), and exclusion (-) are accepted
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
                 {:type        :eacl.schema/unsupported-feature
                  :eacl/error  :eacl.schema/unsupported-feature
                  :issues      all-issues
                  :issue-count total}))))
    nil))

;; ============================================================================
;; Permission Expression Transformation
;; Preserves the source expression before semantic resolution/canonicalization.
;; ============================================================================

(declare permission-expr->source)

(defn- extract-base-expr-identifier
  "Extract identifier string from a base-expr node."
  [node]
  (when (and (vector? node) (= :base-expr (first node)))
    (let [child (second node)]
      (when (and (vector? child) (= :identifier (first child)))
        (second child)))))

(defn- base-expr->source
  "Converts one base expression to a source node. Parentheses are retained as
   the :grouped? bit on the enclosed node so same-operator nesting is not lost."
  [node]
  (when-not (and (vector? node) (= :base-expr (first node)))
    (throw (ex-info "Malformed permission base expression."
             {:type :eacl.schema/malformed-permission-expression
              :eacl/error :eacl.schema/malformed-permission-expression
              :node node})))
  (let [child (second node)]
    (case (first child)
      :identifier
      {:op :identifier :name (extract-identifier child)}

      :paren-expr
      (assoc (permission-expr->source (second child)) :grouped? true)

      :nil-expr
      (throw (ex-info "Unsupported keyword: 'nil'."
               {:type :eacl.schema/unsupported-feature
                :eacl/error :eacl.schema/unsupported-feature
                :node node}))

      :self-expr
      (throw (ex-info "Unsupported keyword: 'self'."
               {:type :eacl.schema/unsupported-feature
                :eacl/error :eacl.schema/unsupported-feature
                :node node}))

      (throw (ex-info "Malformed permission base expression."
               {:type :eacl.schema/malformed-permission-expression
                :eacl/error :eacl.schema/malformed-permission-expression
                :node node})))))

(defn- arrow-expr->source
  "Converts an arrow expression to an unresolved source node. Only one-hop
   arrows are representable; validation and these defensive checks reject
   chained or parenthesized arrow endpoints."
  [node]
  (cond
    (and (vector? node) (= :arrow-func-expr (first node)))
    (let [children  (rest node)
          base-id   (extract-identifier (first children))
          func-node (some #(when (and (vector? %) (= :arrow-func-name (first %))) %) children)
          func-name (second func-node)
          target-id (extract-identifier (last children))]
      (when-not (= "any" func-name)
        (throw (ex-info "Unsupported arrow function."
                 {:type :eacl.schema/unsupported-arrow-function
                  :eacl/error :eacl.schema/unsupported-arrow-function
                  :function func-name})))
      {:op :arrow :base base-id :target target-id :syntax :any})

    (and (vector? node) (= :simple-arrow-expr (first node)))
    (let [base-exprs (filter #(and (vector? %) (= :base-expr (first %))) (rest node))]
      (case (count base-exprs)
        1 (base-expr->source (first base-exprs))
        2 (let [ids (mapv extract-base-expr-identifier base-exprs)]
            (when (some nil? ids)
              (throw (ex-info "Parenthesized expressions are not supported as arrow bases or targets."
                       {:type :eacl.schema/paren-arrow
                        :eacl/error :eacl.schema/paren-arrow
                        :node node})))
            {:op :arrow :base (first ids) :target (second ids) :syntax :arrow})
        (throw (ex-info "Multi-level arrows are not supported."
                 {:type :eacl.schema/multi-level-arrow
                  :eacl/error :eacl.schema/multi-level-arrow
                  :node node}))))

    (and (vector? node) (= :arrow-expr (first node)))
    (arrow-expr->source (second node))

    :else
    (throw (ex-info "Malformed arrow expression."
             {:type :eacl.schema/malformed-permission-expression
              :eacl/error :eacl.schema/malformed-permission-expression
              :node node}))))

(defn- union-expr->source
  [node]
  (when-not (and (vector? node) (= :union-expr (first node)))
    (throw (ex-info "Malformed union expression."
             {:type :eacl.schema/malformed-permission-expression
              :eacl/error :eacl.schema/malformed-permission-expression
              :node node})))
  (let [children (mapv arrow-expr->source (rest node))]
    (if (= 1 (count children))
      (first children)
      {:op :union :children children})))

(defn- intersect-expr->source
  [node]
  (when-not (and (vector? node) (= :intersect-expr (first node)))
    (throw (ex-info "Malformed intersection expression."
             {:type :eacl.schema/malformed-permission-expression
              :eacl/error :eacl.schema/malformed-permission-expression
              :node node})))
  (let [children (mapv union-expr->source (rest node))]
    (if (= 1 (count children))
      (first children)
      {:op :intersection :children children})))

(defn- exclusion-expr->source
  "Builds ordered binary exclusion nodes by left-folding repeated `-`."
  [node]
  (when-not (and (vector? node) (= :exclusion-expr (first node)))
    (throw (ex-info "Malformed exclusion expression."
             {:type :eacl.schema/malformed-permission-expression
              :eacl/error :eacl.schema/malformed-permission-expression
              :node node})))
  (let [[left & rights] (mapv intersect-expr->source (rest node))]
    (reduce (fn [acc right]
              {:op :exclusion :left acc :right right})
      left
      rights)))

(defn- permission-expr->source [node]
  (when-not (and (vector? node) (= :permission-expr (first node)))
    (throw (ex-info "Malformed permission expression."
             {:type :eacl.schema/malformed-permission-expression
              :eacl/error :eacl.schema/malformed-permission-expression
              :node node})))
  (exclusion-expr->source (second node)))

(defn permission-expression->source-ast
  "Converts a parsed :permission-expr node to an unresolved source AST.

   The AST preserves explicit parentheses with :grouped? and preserves ordered,
   left-associated exclusion. Identifier kind and arrow target partitions are
   resolved later against the complete schema."
  [node]
  (let [issues (collect-parse-tree-issues node)]
    (when (seq issues)
      (throw (ex-info (:message (first issues))
               {:type :eacl.schema/unsupported-feature
                :eacl/error :eacl.schema/unsupported-feature
                :issues issues
                :issue-count (count issues)})))
    (permission-expr->source node)))

(defn- operator-source-expression? [node]
  (case (:op node)
    (:intersection :exclusion) true
    :union (boolean (some operator-source-expression? (:children node)))
    false))

(defn- source-ast->components
  "Projects union-compatible source syntax into the existing flat permission
   component domain. Operator expressions have no sound flat projection."
  [node]
  (case (:op node)
    :identifier [{:type :identifier :name (:name node)}]
    :arrow [{:type :arrow
             :base {:type :identifier :name (:base node)}
             :path [(:target node)]}]
    :union (vec (mapcat source-ast->components (:children node)))
    (throw (ex-info "Operator expression cannot use flat permission storage."
             {:type :eacl.schema/operator-storage-disabled
              :eacl/error :eacl.schema/operator-storage-disabled
              :expression node}))))

(defn- flatten-expression
  "Flatten a permission expression to a vector of component maps.
   Each component is {:type :identifier/:arrow, ...}"
  [expr]
  (let [source (permission-expression->source-ast expr)]
    (when (operator-source-expression? source)
      (throw (ex-info "Operator expressions require expression-capable schema storage."
               {:type :eacl.schema/operator-storage-disabled
                :eacl/error :eacl.schema/operator-storage-disabled
                :expression source})))
    (source-ast->components source)))

;; ============================================================================
;; Schema Info Collection
;; ============================================================================

(defn- collect-schema-info
  "Build lookup tables from transformed schema for arrow resolution.
  Relation subject types are kept as full sets so arrow resolution and
  validation can never depend on declaration order."
  [definitions]
  (reduce-kv
    (fn [acc res-type {:keys [relations permissions]}]
      (assoc acc res-type
                 {:relations              (set (keys relations))
                  :relation-subject-types (into {}
                                            (for [[rel-name type-refs] relations]
                                              [rel-name (set (keep :type type-refs))]))
                  :permissions            (set (map :name permissions))}))
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
          subject-types (get-in info [:relation-subject-types base-name])]
      (if (empty? subject-types)
        (throw (ex-info (str "Unknown relation for arrow base: " base-name " on " resource-type)
                 {:type :eacl.schema/invalid-reference
                  :eacl/error :eacl.schema/invalid-reference
                  :component component :resource-type resource-type}))
        ;; The target kind must be resolved against ALL subject types of the base
        ;; relation, never just the first/last declared one — otherwise resolution
        ;; and validation become declaration-order-dependent.
        (let [kinds   (set (map (fn [subject-type]
                                  (let [target-info (get schema-info subject-type)]
                                    (cond
                                      (contains? (:relations target-info) path)   :relation
                                      (contains? (:permissions target-info) path) :permission
                                      :else                                       :missing)))
                                subject-types))
              present (disj kinds :missing)]
          (cond
            (= present #{:relation :permission})
            (throw (ex-info (str "Arrow target '" path "' resolves to a relation on some subject types of '"
                                 base-name "' and a permission on others: " (pr-str subject-types))
                     {:type :eacl.schema/mixed-arrow-target :eacl/error :eacl.schema/mixed-arrow-target
                      :component component
                      :resource-type resource-type
                      :subject-types subject-types}))

            (= present #{:relation})
            {:arrow (keyword base-name) :relation (keyword path)}

            ;; :permission on all types that have it, or missing everywhere —
            ;; construct a permission target and let validate-schema-references
            ;; produce the per-type missing-target errors.
            :else
            {:arrow (keyword base-name) :permission (keyword path)}))))

    (throw (ex-info "Unsupported component type" {:component component}))))

;; ============================================================================
;; Main Transformation Function
;; ============================================================================

(defn ->eacl-schema
  "Convert parsed SpiceDB schema to EACL internal representation.

   Steps:
   1. Reject instaparse failures (a failed parse must never become an empty schema —
      write-schema! diffs against the existing schema, so an empty result retracts everything)
   2. Transform parse tree to intermediate representation
   3. Validate EACL restrictions (throws on unsupported features)
   4. Convert to EACL Relations and Permissions

   Returns {:definitions [...] :relations [...] :permissions [...]}"
  [parse-tree]
  (when (insta/failure? parse-tree)
    (let [failure (insta/get-failure parse-tree)]
      (throw (ex-info (str "Schema parse error: " (pr-str failure))
               {:type :eacl.schema/parse-error
                :eacl/error :eacl.schema/parse-error
                :failure failure}))))
  (let [transformed (transform-schema parse-tree)]
    ;; Validate EACL restrictions (parsing allows full SpiceDB, validation enforces limits)
    (validate-eacl-restrictions parse-tree transformed)

    (let [definitions (:definitions transformed)
          schema-info (collect-schema-info definitions)]
      (cond-> {:definitions (vec (keys definitions))

       :relations
       (vec
         ;; Expand multi-type relations into multiple Relation entities
         (for [[res-type {:keys [relations]}] definitions
               [rel-name type-refs] relations
               type-ref type-refs
               :let [subject-type (:type type-ref)]]
           (model/Relation (keyword res-type) (keyword rel-name) (keyword subject-type))))

       :permissions
       (vec
         (apply concat
           (for [[res-type {:keys [permissions]}] definitions
                 {:keys [name expression]} permissions]
             (let [components (flatten-expression expression)]
               (for [comp components
                     :when comp]
                 (let [spec (resolve-component comp res-type schema-info)]
                   (model/Permission (keyword res-type) (keyword name) spec)))))))}
        (seq (:caveats transformed)) (assoc :caveats (:caveats transformed))))))

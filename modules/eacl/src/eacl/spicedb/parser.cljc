(ns eacl.spicedb.parser
  "SpiceDB schema language for EACL.

  `parse-schema` reads the SpiceDB v1.56.0 schema language exactly
  (`eacl.spicedb.syntax`, docs/spicedb-schema-compatibility.md), and
  `transform-schema` applies SpiceDB's validation (`eacl.spicedb.validation`)
  before extracting EACL's schema. EACL's own restrictions run after both, so
  `:eacl.schema/unsupported-feature` always names a feature of a valid SpiceDB
  schema."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [eacl.schema.model :as model]
            [eacl.caveats.definition :as caveat-definition]
            [eacl.secure-format :as secure]
            [eacl.spicedb.syntax :as syntax]
            [eacl.spicedb.validation :as validation]))

(defn parse-schema
  "Parses one schema to a parse tree (see `eacl.spicedb.syntax`), or throws
   `:eacl.schema/parse-error`. When :maximum-schema-source-bytes is supplied,
   rejects the source before parsing."
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
   (syntax/parse schema-str)))

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
  "Extracts a relation type reference with optional modifier, caveat and
   expiration trait. Returns {:type 'user', :wildcard? false,
   :subject-relation nil, :caveat nil, :expiration? false}"
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
       :caveat           (when caveat (extract-identifier (second caveat)))
       :expiration?      (boolean (some #(and (vector? %) (= :expiration (first %))) children))})))

(defn- extract-relation-type-expr
  "Extracts all type refs from a relation-type-expr.
   Returns vector of type ref maps. EACL permits expiring relationships on
   every relation, so branches that differ only in SpiceDB's `with expiration`
   trait (`user | user with expiration`) are one branch here; `:expiration?`
   records whether any of them declares the trait."
  [node]
  (when (and (vector? node) (= :relation-type-expr (first node)))
    (let [[order by-branch]
          (reduce (fn [[order by-branch] ref]
                    (let [branch (dissoc ref :expiration?)]
                      (if (contains? by-branch branch)
                        [order (update-in by-branch [branch :expiration?] #(or % (:expiration? ref)))]
                        [(conj order branch) (assoc by-branch branch ref)])))
                  [[] {}]
                  (map extract-relation-type-ref (rest node)))]
      (mapv by-branch order))))

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

(defn- node-of? [tag node]
  (and (vector? node) (= tag (first node))))

(defn- definition-entry
  "Builds one definition's [type-path spec], raising its own declaration
   errors: a duplicate relation or permission, or a name collision."
  [[_ type-path-node definition-body]]
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
      :permissions permissions}]))

(defn- add-definition [definitions node]
  (let [[type-path spec] (definition-entry node)]
    (when (contains? definitions type-path)
      (throw (ex-info (str "Duplicate definition: '" type-path "'."
                           " Each type may be defined once; merge the blocks.")
                      {:type :eacl.schema/duplicate-definition
                       :eacl/error :eacl.schema/duplicate-definition
                       :definition type-path})))
    (assoc definitions type-path spec)))

(defn extract-definitions
  "Extract definitions from parse tree.
   Returns map of {type-path {:relations {...}, :permissions [...]}}.
   Throws on duplicate definition blocks and on a permission sharing a name
   with a relation on the same definition (SpiceDB rejects both; silently
   letting the last one win produces destructive write-schema! deltas).
   Definitions are checked in source order, so the first failing one
   determines the error."
  [parse-tree]
  (reduce add-definition {} (filter #(node-of? :definition %) parse-tree)))

(defn- caveat-entity
  "Builds one named Caveat. Its source is the CEL expression SpiceDB compiles:
   the body verbatim from its first token to its last, so whitespace and
   comments before and after the expression are never part of it, and a
   comment inside it is (`//` is CEL; SpiceDB rejects `/* */` there)."
  [[_ name-node & children]]
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
                                  :source-span (:eacl.spicedb/span (meta source-node)))
                           error))))))

(defn- add-caveat [caveats node]
  (let [entity (caveat-entity node)
        name (:eacl.caveat/name entity)]
    (when (contains? caveats name)
      (throw (ex-info "Duplicate Caveat declaration."
                      {:type :eacl.schema/duplicate-caveat
                       :eacl/error :eacl.schema/duplicate-caveat :caveat name})))
    (assoc caveats name entity)))

(defn extract-caveats [parse-tree]
  (->> (rest parse-tree)
       (filter #(node-of? :caveat-definition %))
       (reduce add-caveat (sorted-map))
       vals vec))

(defn- unsupported-caveat-issues
  [caveats]
  (vec
   (for [{:keys [name eacl]} caveats
         :when (= :unsupported (:status eacl))]
     (merge {:type :caveat-profile
             :caveat name
             :message (str "Unsupported feature: caveat `" name "` is valid SpiceDB but outside"
                           " EACL's CEL profile ("
                           (case (:reason eacl)
                             :caveat-name (str "EACL caveat names are ASCII identifiers of up to"
                                               " 64 bytes without a `/` prefix")
                             :parameter-type (str "unsupported parameter types for "
                                                  (pr-str (:parameters eacl)))
                             (str "profile limit " (pr-str (:profile-reason eacl))))
                           "). See docs/caveats.md.")}
            (dissoc eacl :status)))))

(defn transform-schema
  "Validates a parse tree against SpiceDB's rules (`eacl.spicedb.validation`)
   and transforms it to EACL's intermediate representation. A caveat that is
   valid SpiceDB but outside EACL's CEL profile raises
   `:eacl.schema/unsupported-feature`. Throws on unexpected input; a failed
   parse must never coerce to an empty schema.

  Declarations are read once, in source order: each one is built and checked
  against the earlier ones before the next is read, so the first failing
  declaration determines the error, whatever its kind or position.

  `limits` (normalized expression limits) are the source limits checked
  before SpiceDB's reference checks and the size that bounds partial
  expansion and typechecking; see `eacl.spicedb.validation/validate`."
  ([parse-tree]
   (transform-schema parse-tree {}))
  ([parse-tree limits]
   (if (node-of? :schema parse-tree)
     (let [{:keys [tree flags] :as validated}
           (validation/validate (:eacl.spicedb/source (meta parse-tree)) parse-tree limits)
           unsupported (unsupported-caveat-issues (:caveats validated))
           _ (when (seq unsupported)
               (throw (ex-info (:message (first unsupported))
                               {:type :eacl.schema/unsupported-feature
                                :eacl/error :eacl.schema/unsupported-feature
                                :issues unsupported
                                :issue-count (count unsupported)})))
           {:keys [definitions caveats]}
           (reduce (fn [schema node]
                     (cond
                       (node-of? :definition node) (update schema :definitions add-definition node)
                       (node-of? :caveat-definition node) (update schema :caveats add-caveat node)
                       :else schema))
                   {:definitions {} :caveats (sorted-map)}
                   (rest tree))]
       (cond-> {:definitions definitions
                :parse-tree tree
                :use-flags flags}
         (seq caveats) (assoc :caveats (vec (vals caveats)))))
     (throw (ex-info "Unexpected schema parse tree; refusing to interpret as an empty schema."
                     {:type :eacl.schema/parse-error
                      :eacl/error :eacl.schema/parse-error
                      :parse-tree parse-tree})))))

(defn- caveat-refs [names]
  (mapv #(vector :eacl.caveat/name %) names))

(defn relation-entities
  "Groups every branch of one relation declaration by subject type into one
   Relation identity. The concrete branch (`user`, `user with c`) keeps its
   existing encoding; a wildcard branch (`user:*`, `user:* with c`) adds
   `:eacl.relation/allows-unqualified-wildcard?` and, for Caveated branches,
   `:eacl.relation/wildcard-caveats`. A relation with only wildcard branches
   stores `:eacl.relation/allows-unqualified? false` without concrete Caveats.

   With `:strict? true` a repeated branch fails; otherwise it collapses, which
   preserves the unqualified admission path's tolerance of `user | user`."
  [{:keys [definitions caveats]} {:keys [strict?]}]
  (let [names (set (map :eacl.caveat/name caveats))]
    (vec
     (for [[resource-type {:keys [relations]}] (sort-by key definitions)
           [relation-name refs] (sort-by key relations)
           [subject-type alternatives] (sort-by key (group-by :type refs))]
       (let [branch (fn [wildcard?]
                      (mapv :caveat (filter #(= wildcard? (boolean (:wildcard? %)))
                                            alternatives)))
             concrete (branch false)
             wildcard (branch true)]
         (when strict?
           (doseq [allowances [concrete wildcard]]
             (when-not (= (count allowances) (count (set allowances)))
               (throw (ex-info "Duplicate Relation branch."
                               {:type :eacl.schema/duplicate-relation-branch
                                :eacl/error :eacl.schema/duplicate-relation-branch
                                :resource-type resource-type :relation relation-name :subject-type subject-type})))))
         (doseq [name (remove nil? (concat concrete wildcard))]
           (when-not (contains? names name)
             (throw (ex-info "Relation references an undefined Caveat."
                             {:type :eacl.schema/invalid-caveat-reference
                              :eacl/error :eacl.schema/invalid-caveat-reference :caveat name}))))
         (let [concrete-caveats (sort (distinct (remove nil? concrete)))
               wildcard-caveats (sort (distinct (remove nil? wildcard)))]
           (cond-> (model/Relation (keyword resource-type) (keyword relation-name) (keyword subject-type))
             (seq concrete-caveats)
             (assoc :eacl.relation/caveats (caveat-refs concrete-caveats)
                    :eacl.relation/allows-unqualified? (boolean (some nil? concrete)))

             (and (empty? concrete) (seq wildcard))
             (assoc :eacl.relation/allows-unqualified? false)

             (seq wildcard)
             (assoc :eacl.relation/allows-unqualified-wildcard? (boolean (some nil? wildcard)))

             (seq wildcard-caveats)
             (assoc :eacl.relation/wildcard-caveats (caveat-refs wildcard-caveats)))))))))

(defn staged-relation-entities
  "Groups plain, Caveated and wildcard alternatives under one Relation
   identity; a repeated branch fails."
  [transformed]
  (relation-entities transformed {:strict? true}))

;; Helper to parse expressions
(defn parse-permission-expression
  "Parses one permission expression to its :permission-expr node, or nil when
   it does not parse."
  [expr-str]
  (let [full-schema (str "definition temp { permission test = " expr-str "\n}")]
    (try
      ;; Path: schema -> definition -> definition-body -> permission -> permission-expr
      (get-in (syntax/parse full-schema) [1 2 1 2])
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _
        nil))))

;; Transform expressions to a more usable format
;; Pretty print expressions in a readable format
;; Analyze a specific definition
;; Usage examples
;; ============================================================================
;; EACL Validation Functions
;; Validates that parsed SpiceDB schemas conform to EACL restrictions.
;; Parsing accepts full SpiceDB syntax; validation enforces EACL limits.
;; ============================================================================

(declare extract-base-expr-identifier)

(def ^:private reserved-arrow-base-issue
  {:type :reserved-name
   :kind :arrow-base
   :name "self"
   :message (str "Unsupported feature: a relation named 'self' as an arrow's base (self->x)."
                 " EACL's union plans name a same-resource reference with the source relation"
                 " 'self'. Rename the relation.")})

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
                       :message "Unsupported feature: Parenthesized expressions as arrow bases or targets (e.g., (a + b)->c). Arrows take a single relation base."}))
             (when (and (= 2 (count base-exprs))
                        (= "self" (extract-base-expr-identifier (first base-exprs))))
               (swap! issues conj reserved-arrow-base-issue)))

            ;; Check for .all() function (only .any() is implicitly supported via arrow)
           :arrow-func-expr
           (let [func-name-node (some #(when (and (vector? %) (= :arrow-func-name (first %))) %) (rest node))
                 func-name      (second func-name-node)]
             (when (= "self" (extract-identifier (second node)))
               (swap! issues conj reserved-arrow-base-issue))
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
  [definitions allow-caveats? allow-wildcards?]
  (let [issues (atom [])]
    (doseq [[res-type {:keys [relations]}] definitions
            [rel-name type-refs] relations
            type-ref type-refs]
      ;; Wildcards need expression storage; the legacy flat projection
      ;; (->eacl-schema) has no representation for them.
      (when (and (:wildcard? type-ref) (not allow-wildcards?))
        (swap! issues conj
               {:type          :wildcard-relation
                :resource-type res-type
                :relation      rel-name
                :message       (str "Unsupported feature: Wildcard relation '" (:type type-ref) ":*' in "
                                    res-type "/" rel-name ". Flat permission storage cannot represent wildcard access.")}))

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
      (when (and (:caveat type-ref) (not allow-caveats?))
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
   - No namespaced type paths (docs/document)
   - Wildcards (user:*) require explicit wildcard admission
     (`:allow-wildcards? true`, the expression-storage path)
   - No subject relations (group#member)
   - Caveated branches require explicit qualified schema admission
   - No relation named `self` as an arrow's base (`self->x`, without `use self`)

   Partials are expanded and unused partials ignored: the restrictions read
   the tree `transform-schema` validated (`:parse-tree`) when present.

   Returns nil if valid, throws ex-info with :issues vector if invalid."
  ([parse-tree transformed-schema]
   (validate-eacl-restrictions parse-tree transformed-schema {}))
  ([parse-tree transformed-schema {:keys [allow-caveats? allow-wildcards?]}]
   (let [parse-issues    (collect-parse-tree-issues (or (:parse-tree transformed-schema) parse-tree))
         relation-issues (collect-relation-issues (:definitions transformed-schema)
                                                  (true? allow-caveats?)
                                                  (true? allow-wildcards?))
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
     nil)))

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
      {:op :self}

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
   1. Transform the parse tree to intermediate representation, applying
      SpiceDB's validation (`transform-schema` rejects anything but a parse
      tree: a failed parse must never become an empty schema — write-schema!
      diffs against the existing schema, so an empty result retracts everything)
   2. Validate EACL restrictions (throws on unsupported features)
   3. Convert to EACL Relations and Permissions

   Returns {:definitions [...] :relations [...] :permissions [...]}"
  [parse-tree]
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

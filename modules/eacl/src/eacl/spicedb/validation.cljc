(ns eacl.spicedb.validation
  "SpiceDB v1.56.0 schema validation for `eacl.spicedb.syntax` parse trees.

  `validate` applies the rules SpiceDB's WriteSchema applies after parsing
  (docs/spicedb-schema-compatibility.md): partial expansion, name rules,
  name uniqueness, references, wildcard and subject-relation rules, caveat
  definitions, `use typechecking` annotations and permission alias cycles. It
  throws on the first rule a schema breaks, so a schema that reaches EACL's
  own restrictions is a valid SpiceDB schema.

  On success it returns `{:tree t :caveats c}`: `t` is the parse tree in the
  form EACL's schema extraction reads (partials expanded and removed, `use`
  flags and type annotations removed, caveat names and references as single
  identifiers, supported caveat parameter types as `[:caveat-type t item?]`),
  and `c` describes every caveat: `{:name :parameters :source :span :eacl
  {:status :supported|:unsupported ...}}`."
  (:require [clojure.string :as str]
            [eacl.caveats.plan :as plan]
            [eacl.caveats.values :as values]
            [eacl.spicedb.cel :as cel]
            [eacl.spicedb.lexer :as lexer]))

;; ---------------------------------------------------------------- tree access

(defn- tag [node] (when (vector? node) (first node)))

(defn- span [node] (:eacl.spicedb/span (meta node)))

(defn- identifier-text [node]
  (when (= :identifier (tag node)) (second node)))

(defn- path-text [node]
  (case (tag node)
    :type-path (str/join "/" (map second (rest node)))
    :identifier (second node)
    nil))

(defn- child [node child-tag]
  (some #(when (= child-tag (tag %)) %) (rest node)))

(defn- children [node child-tag]
  (filter #(= child-tag (tag %)) (rest node)))

;; ---------------------------------------------------------------- errors

(defn- position [source node]
  (when-let [[start] (and source (span node))]
    (let [[line column] (lexer/line-column source start)]
      {:line line :column column})))

(defn- fail!
  [type message data]
  (throw (ex-info message (merge data {:type type :eacl/error type}))))

(defn- resolution-failed!
  "SpiceDB reference failures, in the shape `eacl.schema.expression-resolver`
   reports."
  [issues]
  (fail! :eacl.schema/expression-resolution-failed
         "Permission-expression reference resolution failed."
         {:errors (vec issues) :error-count (count issues)}))

;; ---------------------------------------------------------------- names

(def ^:private segment "[a-z][a-z0-9_]{1,62}[a-z0-9]")

(def ^:private definition-name-pattern
  (re-pattern (str "(?:" segment "/)*" segment)))

(def ^:private subject-type-pattern
  ;; A subject type's prefix segments are one character shorter than a
  ;; definition's (core.proto AllowedRelation.namespace).
  (re-pattern (str "(?:[a-z][a-z0-9_]{1,61}[a-z0-9]/)*" segment)))

(def ^:private relation-name-pattern (re-pattern segment))

(defn- utf8-bytes
  "UTF-8 length; an unpaired surrogate counts as its replacement character."
  [s]
  (let [n (count s)]
    (loop [i 0 total 0]
      (if (>= i n)
        total
        (let [c #?(:clj (int (.charAt ^String s (int i))) :cljs (.charCodeAt s i))]
          (cond
            (< c 0x80) (recur (inc i) (inc total))
            (< c 0x800) (recur (inc i) (+ total 2))
            (and (<= 0xD800 c 0xDBFF) (< (inc i) n)
                 (let [d #?(:clj (int (.charAt ^String s (int (inc i)))) :cljs (.charCodeAt s (inc i)))]
                   (<= 0xDC00 d 0xDFFF)))
            (recur (+ i 2) (+ total 4))
            :else (recur (inc i) (+ total 3))))))))

(defn- name-ok? [kind s]
  (case kind
    :definition (and (<= (utf8-bytes s) 128) (boolean (re-matches definition-name-pattern s)))
    :subject-type (and (<= (utf8-bytes s) 128) (boolean (re-matches subject-type-pattern s)))
    (and (<= (utf8-bytes s) 64) (boolean (re-matches relation-name-pattern s)))))

(def ^:private name-rules
  {:definition "^([a-z][a-z0-9_]{1,62}[a-z0-9]/)*[a-z][a-z0-9_]{1,62}[a-z0-9]$, at most 128 bytes"
   :subject-type "^([a-z][a-z0-9_]{1,61}[a-z0-9]/)*[a-z][a-z0-9_]{1,62}[a-z0-9]$, at most 128 bytes"
   :name "^[a-z][a-z0-9_]{1,62}[a-z0-9]$, at most 64 bytes"})

(defn- check-name! [source kind s node]
  (when-not (name-ok? kind s)
    (fail! :eacl.schema/invalid-name
           (str "Invalid " (name kind) " name " (pr-str s) ": SpiceDB names match "
                (get name-rules kind (:name name-rules)) ".")
           (merge {:kind kind :name s :rule (get name-rules kind (:name name-rules))}
                  (position source node)))))

;; ---------------------------------------------------------------- partials

(defn- member-name [member]
  (case (tag member)
    :relation (identifier-text (second (second member)))
    :permission (identifier-text (second member))
    nil))

(defn- check-expression-names!
  "SpiceDB validates every relation name a permission expression mentions
   (computed usersets and both sides of arrows)."
  [source node]
  (when (vector? node)
    (case (tag node)
      :simple-arrow-expr
      (doseq [base (rest node)
              :let [ident (second base)]
              :when (= :identifier (tag ident))]
        (check-name! source :relation (second ident) ident))

      :arrow-func-expr
      (let [[_ base _ target] node]
        (check-name! source :relation (second base) base)
        (check-name! source :relation (second target) target))

      (doseq [c (rest node)] (check-expression-names! source c)))))

(defn- check-member-names!
  "SpiceDB's protovalidate pass over one relation or permission."
  [source member]
  (case (tag member)
    :relation
    (let [name-node (second (second member))]
      (check-name! source :relation (second name-node) name-node)
      (doseq [ref (rest (nth member 2))
              :let [path (second ref)
                    subject-relation (some-> (child ref :relation-modifier) (child :subject-relation) second)]]
        (check-name! source :subject-type (path-text path) path)
        (when subject-relation
          (check-name! source :relation (second subject-relation) subject-relation))))

    :permission
    (let [name-node (second member)]
      (check-name! source :permission (second name-node) name-node)
      (check-expression-names! source (last member)))

    nil))

(defn- expand-members
  "Translates a definition or partial body: validates each relation and
   permission, and splices compiled partials. Returns [members nil], or
   [nil missing-partial-name] when `error-on-missing?` is false."
  [source compiled body error-on-missing?]
  (loop [[member & more] (rest body) out []]
    (cond
      (nil? member) [out nil]

      (= :partial-reference (tag member))
      (let [reference (second member)
            path (second reference)]
        (if-let [members (get compiled path)]
          (recur more (into out members))
          (if error-on-missing?
            (fail! :eacl.schema/invalid-partial
                   (str "Could not find a partial named " (pr-str path) ".")
                   (merge {:reason :undefined :partial path} (position source reference)))
            [nil path])))

      :else
      (do (check-member-names! source member)
          (recur more (conj out member))))))

(defn- compile-partials
  "SpiceDB's collectPartials: resolves partials in source order, retrying a
   partial when the partial it waits for compiles. A later partial with the
   same name replaces an earlier one."
  [source partials]
  (let [compiled (volatile! {})
        waiting (volatile! {})]
    (letfn [(translate! [node]
              (let [path (path-text (second node))
                    [members missing] (expand-members source @compiled (nth node 2) false)]
                (if missing
                  (vswap! waiting update missing (fnil conj []) node)
                  (do (vswap! compiled assoc path members)
                      (let [ready (get @waiting path)]
                        (doseq [w ready] (translate! w))
                        (vswap! waiting dissoc path))))))]
      (doseq [node partials] (translate! node))
      (when (seq @waiting)
        (fail! :eacl.schema/invalid-partial
               (str "Could not resolve partials " (pr-str (vec (sort (keys @waiting))))
                    "; this may indicate a circular reference.")
               {:reason :unresolved :partials (vec (sort (keys @waiting)))}))
      @compiled)))

;; ---------------------------------------------------------------- caveats

(def ^:private basic-types
  #{"any" "bool" "bytes" "double" "duration" "int" "ipaddress" "string" "timestamp" "uint"})

(defn- caveat-type
  "SpiceDB's caveat type set. Returns {:type t :ignored-arguments? b}, where
   `t` is a keyword or [:list t] / [:map t]."
  [source type-node]
  (let [[_ name-node & arguments] type-node
        type-name (second name-node)
        translated (mapv #(caveat-type source %) arguments)]
    (cond
      (contains? #{"list" "map"} type-name)
      (if (= 1 (count translated))
        {:type [(keyword type-name) (:type (first translated))]
         :ignored-arguments? (:ignored-arguments? (first translated))}
        (fail! :eacl.caveat/invalid
               (str "Caveat type `" type-name "` requires 1 type argument; found "
                    (count translated) ".")
               (merge {:reason :type-arity :caveat-type type-name} (position source name-node))))

      (contains? basic-types type-name)
      ;; SpiceDB ignores type arguments on basic types: `int<string>` is `int`.
      {:type (keyword type-name)
       :ignored-arguments? (or (boolean (seq arguments))
                               (boolean (some :ignored-arguments? translated)))}

      :else
      (fail! :eacl.caveat/invalid
             (str "Unknown caveat parameter type `" type-name "`.")
             (merge {:reason :unknown-type :caveat-type type-name} (position source name-node))))))

(def ^:private eacl-scalar-types #{:bool :int :string :timestamp})

(defn- eacl-parameter-type
  "EACL CEL profile type for a SpiceDB parameter type, or nil."
  [{:keys [type ignored-arguments?]}]
  (when-not ignored-arguments?
    (cond
      (contains? eacl-scalar-types type) type
      (and (vector? type) (= :list (first type)) (contains? eacl-scalar-types (second type))) type
      (and (vector? type) (= :map (first type)) (contains? eacl-scalar-types (second type)))
      [:map :string (second type)]
      :else nil)))

(defn- plan-parameters [plan]
  (into #{}
        (keep #(when (and (vector? %) (= :param (first %))) (second %)))
        (tree-seq vector? rest plan)))

(defn- caveat-invalid!
  [source caveat-name node reason message data]
  (fail! :eacl.caveat/invalid
         (str "Invalid caveat `" caveat-name "`: " message)
         (merge {:reason reason :caveat caveat-name} data (position source node))))

(defn- cel-invalid-reason
  "The `:eacl.caveat/invalid` reason for a body SpiceDB's CEL compiler rejects,
   in the vocabulary of `eacl.caveats.plan` where one applies."
  [cel-reason]
  (case cel-reason
    (:syntax :character :unterminated-string :string-escape :number :int-literal
             :reserved-identifier) :syntax-error
    (:no-matching-overload :field-selection) :type-error
    :undeclared-reference :unknown-parameter
    :non-boolean-result :non-boolean-root
    cel-reason))

(def ^:private maximum-expression-bytes
  "SpiceDB's caveat expression bound (characters of the trimmed source)."
  100000)

(defn- caveat-description
  "Validates one caveat definition as SpiceDB does, then classifies it for
   EACL's CEL profile without failing on profile limits."
  [source node]
  (let [[_ name-node parameters-node source-node] node
        caveat-name (path-text name-node)
        parameter-nodes (rest parameters-node)
        expression (second source-node)
        parameters
        (mapv (fn [[_ name-ident type-node]]
                {:name (second name-ident)
                 :node name-ident
                 :type (caveat-type source type-node)})
              parameter-nodes)]
    (when-let [duplicate (some (fn [[n c]] (when (> c 1) n))
                               (frequencies (map :name parameters)))]
      (caveat-invalid! source caveat-name name-node :duplicate-parameter
                       (str "parameter `" duplicate "` is defined twice.")
                       {:parameter duplicate}))
    (doseq [{parameter :name parameter-node :node} parameters]
      (when-not (cel/referenceable-parameter? parameter)
        (caveat-invalid! source caveat-name parameter-node :parameter-name
                         (str "parameter `" parameter "` overlaps a CEL declaration or cannot be referenced in CEL.")
                         {:parameter parameter})))
    (when (> (utf8-bytes (str/trim expression)) maximum-expression-bytes)
      (caveat-invalid! source caveat-name source-node :expression-size
                       "the expression exceeds SpiceDB's 100,000 character limit." {}))
    (let [source-span {:source-span (span source-node)}
          spicedb-types (into {} (map (juxt :name (comp :type :type))) parameters)
          checked (cel/check expression spicedb-types)
          _ (when (= :invalid (:outcome checked))
              (caveat-invalid! source caveat-name source-node
                               (cel-invalid-reason (:reason checked))
                               (case (:reason checked)
                                 :non-boolean-result "the expression must result in a bool."
                                 :unused-parameter (str "parameters " (pr-str (:parameters checked)) " are unused.")
                                 "the expression is not valid CEL for its parameters.")
                               (cond-> (merge source-span
                                              (select-keys checked [:offset :parameters])
                                              {:cel-reason (:reason checked)})
                                 (:type checked) (assoc :result-type (:type checked)))))
          eacl-types (mapv (juxt :name (comp eacl-parameter-type :type)) parameters)
          compiled
          (cond
            (not (values/parameter-name? caveat-name))
            {:issue {:reason :caveat-name}}

            (some (comp nil? second) eacl-types)
            {:issue {:reason :parameter-type
                     :parameters (vec (keep #(when (nil? (second %)) (first %)) eacl-types))}}

            :else
            (try
              {:plan (:plan (plan/compile-plan expression eacl-types))}
              (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                (if (= :eacl.caveat/invalid (:type (ex-data e)))
                  {:issue (cond-> {:reason :profile :profile-reason (:reason (ex-data e))}
                            (:offset (ex-data e)) (assoc :offset (:offset (ex-data e))))}
                  (throw e)))))
          _ (when (and (:plan compiled) (= :unknown (:outcome checked)))
              ;; Only EACL's checker typed this body; SpiceDB still requires
              ;; every parameter to be referenced.
              (let [unused (sort (remove (plan-parameters (:plan compiled))
                                         (map :name parameters)))]
                (when (seq unused)
                  (caveat-invalid! source caveat-name source-node :unused-parameter
                                   (str "parameters " (pr-str (vec unused)) " are unused.")
                                   (merge source-span {:parameters (vec unused)})))))
          profile-issue (:issue compiled)]
      {:name caveat-name
       :node node
       :parameters eacl-types
       :source expression
       :span (span source-node)
       :spicedb (:outcome checked)
       :eacl (if profile-issue
               (assoc profile-issue :status :unsupported)
               {:status :supported})})))

;; ---------------------------------------------------------------- type system

(defn- relation-node? [member] (= :relation (tag member)))

(defn- type-refs [relation-member]
  (rest (nth relation-member 2)))

(defn- ref-info [ref]
  (let [modifier (child ref :relation-modifier)
        subject-relation (some-> modifier (child :subject-relation) second second)
        wildcard? (boolean (some-> modifier (child :wildcard)))
        caveat (some-> (child ref :caveat-ref) second path-text)
        expiration? (boolean (child ref :expiration))
        subject-type (path-text (second ref))]
    {:subject-type subject-type
     :subject-relation subject-relation
     :wildcard? wildcard?
     :caveat caveat
     :expiration? expiration?
     :node ref
     :source (str subject-type
                  (cond wildcard? ":*" subject-relation (str "#" subject-relation) :else "")
                  (when (or caveat expiration?)
                    (str " with " caveat (when (and caveat expiration?) " and ")
                         (when expiration? "expiration"))))}))

(defn- definition-model
  "Relations, permissions and expressions of one translated definition."
  [name members]
  {:name name
   :members members
   :relations (into {} (for [m members :when (relation-node? m)]
                         [(member-name m) (mapv ref-info (type-refs m))]))
   :permissions (into {} (for [m members :when (= :permission (tag m))]
                           [(member-name m) m]))})

(defn- references-wildcard
  "SpiceDB's referencesWildcardType: the first wildcard reachable from a
   relation through its subject types, following subject relations (a
   permission has no subject types). Checked per definition and relation; see
   the corpus' :spicedb-defects for SpiceDB's relation-name cache."
  [definitions definition relation]
  (loop [pending [[definition relation]] seen #{}]
    (if-let [[d r] (first pending)]
      (if (contains? seen [d r])
        (recur (rest pending) seen)
        (let [refs (get-in definitions [d :relations r])]
          (if-let [wildcard (some #(when (:wildcard? %) %) refs)]
            {:definition d :relation r :wildcard (:subject-type wildcard)}
            (recur (concat (rest pending)
                           (for [{:keys [subject-type subject-relation]} refs
                                 :when subject-relation]
                             [subject-type subject-relation]))
                   (conj seen [d r])))))
      nil)))

(defn expression-ir
  "SpiceDB's rewrite for a parsed permission expression. Parentheses vanish,
   and nested unions and intersections flatten into their parent as SpiceDB's
   translator flattens them; exclusion stays binary. Leaves are
   `{:op :computed :name n}`, `{:op :arrow :tupleset t :target r :function f}`
   (`f` nil, \"any\" or \"all\"), `{:op :nil}` and `{:op :self}`."
  [node]
  (let [flatten-op (fn [op children]
                     (let [irs (map expression-ir children)]
                       (if (= 1 (count irs))
                         (first irs)
                         {:op op
                          :children (vec (mapcat #(if (= op (:op %)) (:children %) [%]) irs))
                          :node node})))]
    (case (tag node)
      :permission-expr (expression-ir (second node))
      :exclusion-expr (reduce (fn [left right] {:op :exclusion :children [left right] :node node})
                              (map expression-ir (rest node)))
      :intersect-expr (flatten-op :intersection (rest node))
      :union-expr (flatten-op :union (rest node))
      :arrow-expr (expression-ir (second node))
      :simple-arrow-expr (let [[base target] (rest node)]
                           (if target
                             {:op :arrow :tupleset (second (second base)) :target (second (second target))
                              :function nil :node node}
                             (expression-ir base)))
      :arrow-func-expr (let [[_ base function target] node]
                         {:op :arrow :tupleset (second base) :target (second target)
                          :function (second function) :node node})
      :base-expr (let [inner (second node)]
                   (case (tag inner)
                     :identifier {:op :computed :name (second inner) :node node}
                     :nil-expr {:op :nil :node node}
                     :self-expr {:op :self :node node}
                     :paren-expr (expression-ir (second inner))))
      (throw (ex-info "Unexpected permission expression node." {:node node})))))

(defn- expression-leaves [node]
  (->> (tree-seq :children :children (expression-ir node))
       (filter #(contains? #{:computed :arrow} (:op %)))
       (map (fn [{:keys [op] :as leaf}]
              (case op
                :computed {:kind :computed :name (:name leaf) :node (:node leaf)}
                :arrow {:kind :arrow :tupleset (:tupleset leaf) :target (:target leaf)
                        :function (:function leaf) :node (:node leaf)})))))

(defn- leaf-paths
  "Computed usersets and arrows of a parsed expression with the paths
   `eacl.schema.expression-resolver` reports: `:child i` under a union or
   intersection, `:left`/`:right` under a (left-folded) exclusion;
   parentheses add nothing."
  [node path]
  (case (tag node)
    :permission-expr (leaf-paths (second node) path)
    :arrow-expr (leaf-paths (second node) path)
    :exclusion-expr
    (let [operands (vec (rest node))
          n (count operands)]
      (if (= 1 n)
        (leaf-paths (first operands) path)
        (mapcat (fn [j operand]
                  (leaf-paths operand
                              (-> path
                                  (into (repeat (- n 1 (max j 1)) :left))
                                  (conj (if (zero? j) :left :right)))))
                (range) operands)))
    (:intersect-expr :union-expr)
    (let [operands (rest node)]
      (if (= 1 (count operands))
        (leaf-paths (first operands) path)
        (mapcat (fn [i operand] (leaf-paths operand (conj path :child i)))
                (range) operands)))
    :simple-arrow-expr
    (let [[base target] (rest node)]
      (if target
        [{:kind :arrow :tupleset (second (second base)) :target (second (second target))
          :node node :path path}]
        (let [inner (second base)]
          (case (tag inner)
            :identifier [{:kind :computed :name (second inner) :node node :path path}]
            :paren-expr (leaf-paths (second inner) path)
            []))))
    :arrow-func-expr
    (let [[_ base function target] node]
      [{:kind :arrow :tupleset (second base) :target (second target)
        :function (second function) :node node :path path}])
    []))

(defn- issue
  "One reference issue in the shape `eacl.schema.expression-resolver` reports."
  [type definition permission path data]
  (merge {:type type
          :resource-type (keyword definition)
          :permission-name (some-> permission keyword)
          :path path}
         data))

(defn- issue-sort-key
  [{:keys [resource-type permission-name path type] :as value}]
  [(str resource-type)
   (str permission-name)
   (pr-str path)
   (str type)
   (pr-str (dissoc value :message))])

(defn- expression-issues
  "SpiceDB's rewrite checks: a computed userset names a relation or
   permission of the definition, and an arrow's left side is a relation of
   the definition that reaches no wildcard. The arrow's target is not checked."
  [definitions {:keys [name relations permissions]}]
  (for [[permission-name member] permissions
        {:keys [kind path] :as leaf} (leaf-paths (last member) [:root])
        :let [problem
              (case kind
                :computed
                (when-not (or (contains? relations (:name leaf))
                              (contains? permissions (:name leaf)))
                  (issue :missing-reference name permission-name path
                         {:name (keyword (:name leaf))
                          :message "Reference does not name a relation or permission on the resource type."}))
                :arrow
                (let [tupleset (:tupleset leaf)]
                  (cond
                    (contains? permissions tupleset)
                    (issue :type-invalid-reference name permission-name path
                           {:name (keyword tupleset) :expected :relation :actual :permission
                            :message "Arrow base must be a relation, not a permission."})

                    (not (contains? relations tupleset))
                    (issue :missing-reference name permission-name path
                           {:name (keyword tupleset) :expected :relation
                            :message "Arrow base relation does not exist on the resource type."})

                    :else
                    (when-let [w (references-wildcard definitions name tupleset)]
                      (issue :wildcard-arrow-base name permission-name path
                             {:name (keyword tupleset)
                              :wildcard-type (keyword (:wildcard w))
                              :via [(keyword (:definition w)) (keyword (:relation w))]
                              :message (str "Relation " name "#" tupleset " includes a wildcard subject"
                                            " type: wildcard relations cannot be used on the left side of arrows.")})))))]
        :when problem]
    problem))

(defn- check-declaration!
  "A definition's own errors, checked before the next declaration is read:
   a repeated relation or permission name, and a repeated subject type in one
   relation (compared by source, `user#...` as `user`)."
  [{:keys [name members relations]}]
  (when-let [duplicate (some (fn [[n c]] (when (> c 1) n))
                             (frequencies (keep member-name members)))]
    (let [kinds (set (keep #(when (= duplicate (member-name %)) (tag %)) members))]
      (cond
        (= #{:relation} kinds)
        (fail! :eacl.schema/duplicate-relation
               (str "Duplicate relation declaration: '" duplicate "'.")
               {:relation duplicate :resource-type name})
        (= #{:permission} kinds)
        (fail! :eacl.schema/duplicate-permission
               (str "Duplicate permission declaration: '" duplicate "'.")
               {:permission duplicate :resource-type name})
        :else
        (fail! :eacl.schema/name-collision
               (str "Permission and relation share a name on definition '" name "': " (pr-str [duplicate]))
               {:definition name :names [duplicate]}))))
  (doseq [m members
          :when (relation-node? m)
          :let [relation-name (member-name m)
                refs (get relations relation-name)]]
    (when-let [duplicate (some (fn [[source c]] (when (> c 1) source))
                               (frequencies (map :source refs)))]
      (fail! :eacl.schema/duplicate-relation-branch
             (str "Duplicate subject type `" duplicate "` on relation `" relation-name
                  "` of definition `" name "`.")
             {:resource-type name :relation relation-name :subject-type duplicate}))))

(defn- check-caveat-references!
  [caveat-names {:keys [name members relations]}]
  (doseq [m members
          :when (relation-node? m)
          :let [relation-name (member-name m)]
          {:keys [caveat]} (get relations relation-name)]
    (when (and caveat (not (contains? caveat-names caveat)))
      (fail! :eacl.schema/invalid-caveat-reference
             (str "Relation `" relation-name "` of definition `" name
                  "` references an undefined caveat `" caveat "`.")
             {:caveat caveat :resource-type name :relation relation-name}))))

(defn- relation-issues
  "SpiceDB's subject-type checks: a subject type is a definition, and a
   subject relation names a relation or permission of it that, on another
   definition, reaches no wildcard."
  [definitions {:keys [name relations]}]
  (for [[relation-name refs] relations
        {:keys [subject-type subject-relation wildcard?]} refs
        :let [target (get definitions subject-type)
              path [:relation (keyword relation-name) :subject-type (keyword subject-type)]
              data {:relation-name (keyword relation-name) :subject-type (keyword subject-type)}
              problem
              (cond
                (nil? target)
                (issue :type-invalid-reference name nil path
                       (assoc data :expected :defined-subject-type
                              :message "Relation names an undefined subject type."))

                (or (nil? subject-relation) wildcard?) nil

                (not (or (contains? (:relations target) subject-relation)
                         (contains? (:permissions target) subject-relation)))
                (issue :missing-reference name nil path
                       (assoc data :name (keyword subject-relation)
                              :message "Subject relation does not name a relation or permission of its subject type."))

                (= subject-type name) nil

                :else
                (when-let [w (references-wildcard definitions subject-type subject-relation)]
                  (issue :transitive-wildcard name nil path
                         (assoc data :name (keyword subject-relation)
                                :wildcard-type (keyword (:wildcard w))
                                :via [(keyword (:definition w)) (keyword (:relation w))]
                                :message (str "Subject relation " subject-type "#" subject-relation
                                              " includes a wildcard subject type: wildcard relations"
                                              " cannot be transitively included.")))))]
        :when problem]
    problem))

(defn- check-references!
  "Every reference issue of the schema, sorted as the resolver sorts them."
  [definitions models]
  (let [issues (->> models
                    (mapcat #(concat (relation-issues definitions %)
                                     (expression-issues definitions %)))
                    distinct
                    (sort-by issue-sort-key)
                    vec)]
    (when (seq issues) (resolution-failed! issues))))

(defn- terminal-types
  "SpiceDB's GetRecursiveTerminalTypesForRelation, including its shared
   visited set."
  [definitions definition relation]
  (let [seen (volatile! #{})]
    (letfn [(for-relation [d r]
              (when-not (contains? @seen [d r])
                (vswap! seen conj [d r])
                (let [model (get definitions d)]
                  (cond
                    (contains? (:relations model) r)
                    (into #{}
                          (mapcat (fn [{:keys [subject-type subject-relation wildcard?]}]
                                    (if (and subject-relation (not wildcard?))
                                      (for-relation subject-type subject-relation)
                                      [subject-type])))
                          (get-in model [:relations r]))
                    (contains? (:permissions model) r)
                    (for-expression d (last (get-in model [:permissions r])))
                    :else nil))))
            (for-expression [d node]
              (into #{}
                    (mapcat (fn [{:keys [kind name tupleset target]}]
                              (case kind
                                :computed (for-relation d name)
                                :arrow (mapcat #(for-relation % target)
                                               (sort (distinct (map :subject-type
                                                                    (get-in definitions [d :relations tupleset])))))))
                            (expression-leaves node))))]
      (for-relation definition relation))))

(defn- validate-annotations!
  [definitions {:keys [name permissions]}]
  (doseq [[permission-name member] (sort-by key permissions)
          :let [annotation (child member :type-annotation)]
          :when annotation
          :let [allowed (set (map second (rest annotation)))
                missing (sort (remove allowed (terminal-types definitions name permission-name)))]
          :when (seq missing)]
    (fail! :eacl.schema/incomplete-type-annotation
           (str "Incomplete type annotation on `" name "#" permission-name "`: `" (first missing)
                "` is reachable but not in " (pr-str (vec (sort allowed))) ".")
           {:resource-type name :permission permission-name
            :reachable (vec missing) :annotation (vec (sort allowed))})))

(defn- alias-target
  "The name a permission aliases: its rewrite is exactly one computed userset."
  [member]
  (let [ir (expression-ir (last member))]
    (when (= :computed (:op ir)) (:name ir))))

(defn- validate-alias-cycles!
  "SpiceDB's computePermissionAliases."
  [{:keys [name permissions]}]
  (let [aliases (into {} (keep (fn [[p member]]
                                 (when-let [target (alias-target member)]
                                   (when (contains? permissions target) [p target]))))
                      permissions)]
    (loop [unresolved aliases]
      (when (seq unresolved)
        (let [resolved (into {} (remove (fn [[_ target]] (contains? unresolved target)) unresolved))]
          (if (empty? resolved)
            (fail! :eacl.schema/permission-alias-cycle
                   (str "Under definition `" name "`, there exists a cycle in permissions: "
                        (str/join ", " (sort (keys unresolved))) ".")
                   {:resource-type name :permissions (vec (sort (keys unresolved)))})
            (recur (apply dissoc unresolved (keys resolved)))))))))

;; ---------------------------------------------------------------- EACL tree

(defn- eacl-ref [ref]
  (into [] (map (fn [c]
                  (if (= :caveat-ref (tag c))
                    [:caveat-ref [:identifier (path-text (second c))]]
                    c)))
        ref))

(defn- eacl-member [member]
  (case (tag member)
    :relation (let [[t n types] member]
                (with-meta [t n (into [:relation-type-expr] (map eacl-ref) (rest types))]
                  (meta member)))
    :permission (with-meta [:permission (second member) (last member)] (meta member))
    member))

(defn- eacl-caveat-node [{:keys [node parameters]}]
  (let [[_ name-node parameters-node source-node] node]
    (with-meta
      [:caveat-definition
       [:identifier (path-text name-node)]
       (into [:caveat-parameters]
             (map (fn [[_ name-ident] [_ t]]
                    [:caveat-parameter name-ident
                     (cond
                       (keyword? t) [:caveat-type [:identifier (name t)]]
                       (= :list (first t)) [:caveat-type [:identifier "list"] [:identifier (name (second t))]]
                       :else [:caveat-type [:identifier "map"] [:identifier (name (nth t 2))]])])
                  (rest parameters-node) parameters))
       source-node]
      (meta node))))

;; ---------------------------------------------------------------- entry point

(defn validate
  "Validates a parse tree against SpiceDB's rules. See the namespace docstring."
  [source tree]
  (let [items (rest tree)
        flags (set (map second (filter #(= :use-flag (tag %)) items)))
        partials (filter #(= :partial (tag %)) items)
        compiled (compile-partials source partials)
        names (volatile! {})
        claim-name!
        (fn [kind name node]
          (when-let [prior (get @names name)]
            (if (= prior kind)
              (fail! (if (= :caveat kind) :eacl.schema/duplicate-caveat :eacl.schema/duplicate-definition)
                     (str "Found name reused between multiple definitions and/or caveats: " name ".")
                     (merge {(if (= :caveat kind) :caveat :definition) name} (position source node)))
              (fail! :eacl.schema/name-collision
                     (str "A caveat and a definition share the name " (pr-str name) ".")
                     (merge {:definition name :names [name] :kinds [:caveat :definition]}
                            (position source node)))))
          (when (contains? compiled name)
            (fail! :eacl.schema/invalid-partial
                   (str "Found " (clojure.core/name kind) " with the same name as a partial: " name ".")
                   (merge {:reason :name-collision :partial name} (position source node))))
          (vswap! names assoc name kind))
        translated
        (vec
         (for [item items
               :when (contains? #{:definition :caveat-definition} (tag item))]
           (case (tag item)
             :caveat-definition
             (let [description (caveat-description source item)]
               (claim-name! :caveat (:name description) item)
               [:caveat description])

             :definition
             (let [name-node (second item)
                   name (path-text name-node)
                   [members] (expand-members source compiled (nth item 2) true)
                   _ (check-name! source :definition name name-node)
                   model (definition-model name members)]
               ;; A declaration's own errors precede its name check, and both
               ;; precede the next declaration (source-order precedence).
               (check-declaration! model)
               (claim-name! :definition name item)
               [:definition (assoc model :node item)]))))
        models (keep (fn [[kind model]] (when (= :definition kind) model)) translated)
        definitions (into {} (map (juxt :name identity)) models)
        caveats (vec (keep (fn [[kind d]] (when (= :caveat kind) d)) translated))
        caveat-names (set (map :name caveats))]
    (doseq [model models] (check-caveat-references! caveat-names model))
    (check-references! definitions models)
    (when (contains? flags "typechecking")
      (doseq [model models] (validate-annotations! definitions model)))
    (doseq [model models] (validate-alias-cycles! model))
    {:flags flags
     :caveats caveats
     :tree
     (into [:schema]
           (keep (fn [[kind value]]
                   (case kind
                     :caveat (when (= :supported (get-in value [:eacl :status]))
                               (eacl-caveat-node value))
                     :definition (let [{:keys [node members]} value
                                       [_ name-node] node]
                                   (with-meta
                                     [:definition name-node
                                      (into [:definition-body] (map eacl-member) members)]
                                     (meta node))))))
           translated)}))

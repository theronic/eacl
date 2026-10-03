(ns eacl.spicedb.validation
  "SpiceDB v1.56.0 schema validation for `eacl.spicedb.syntax` parse trees.

  `validate` applies the rules SpiceDB's WriteSchema applies after parsing
  (docs/spicedb-schema-compatibility.md): partial expansion, name rules,
  name uniqueness, references, wildcard and subject-relation rules, caveat
  definitions, `use typechecking` annotations and permission alias cycles. It
  throws on the first rule a schema breaks, so a schema that reaches EACL's
  own restrictions is a valid SpiceDB schema.

  Every step is linear in the expanded schema, and EACL's source limits run
  before SpiceDB's reference checks, which they bound. Partials expand by
  copying, so a few lines can stand for an exponential number of members
  (`partial b { ...a ...a }`, `partial c { ...b ...b }` and so on):
  translating partials and expanding definitions visit at most
  `partial-expansion-budget` statements, and `use typechecking` annotations
  at most `typechecking-budget` relations and permissions; beyond either, the
  schema is `:eacl.schema/expression-limit` (`:dimension :partial-expansion`
  or `:typechecking`). A partial that waits for another resumes where it
  stopped, unless a partial was redefined meanwhile: then it starts over, as
  SpiceDB's translator always does.

  On success it returns `{:tree t :caveats c}`: `t` is the parse tree in the
  form EACL's schema extraction reads (partials expanded and removed, `use`
  flags and type annotations removed, caveat names and references as single
  identifiers, supported caveat parameter types as `[:caveat-type t item?]`),
  and `c` describes every caveat: `{:name :parameters :source :span :eacl
  {:status :supported|:unsupported ...}}`."
  (:require [clojure.string :as str]
            [eacl.caveats.plan :as plan]
            [eacl.caveats.values :as values]
            [eacl.schema.expression-limits :as expression-limits]
            [eacl.schema.expression-policy :as expression-policy]
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
              :let [inner (second base)]]
        (if (= :identifier (tag inner))
          (check-name! source :relation (second inner) inner)
          ;; Names inside parentheses are validated too.
          (check-expression-names! source inner)))

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

(defn partial-expansion-budget
  "The statements translating partials and expanding definitions may visit: a
   quarter of :maximum-schema-source-bytes, more than a schema of that size
   holds written out without partials."
  [maximum-schema-source-bytes]
  (quot maximum-schema-source-bytes 4))

(defn typechecking-budget
  "The relations and permissions `use typechecking` may visit, summed over
   every annotated permission: :maximum-schema-source-bytes. Each annotation
   walks everything its permission reaches, as SpiceDB's does, so many
   annotations over one long chain cost their product."
  [maximum-schema-source-bytes]
  maximum-schema-source-bytes)

(defn- budget [maximum]
  {:maximum maximum :used (volatile! 0)})

(defn- visit!
  "Counts one statement of partial expansion, or `n` visits of `dimension`."
  ([b] (visit! b 1 :partial-expansion))
  ([{:keys [maximum used]} n dimension]
   (let [total (vswap! used + n)]
     (when (> total maximum)
       (fail! :eacl.schema/expression-limit
              (case dimension
                :partial-expansion "Expanding partials exceeds the schema's size limit."
                :typechecking "Checking type annotations exceeds the schema's size limit.")
              {:dimension dimension
               :maximum maximum
               :actual-at-least total
               :limit :maximum-schema-source-bytes})))))

(defn- translate-partial
  "Translates a partial's statements to segments: `[:member m]`, or
   `[:partial segments]` for a referenced partial as it was compiled then.
   Resumes `paused` when no partial was redefined since it paused (otherwise
   it starts over and sees the redefinitions). Returns `[:done segments]` or
   `[:waiting name paused]` for the first referenced partial not compiled yet."
  [source compiled statements paused redefinitions budget]
  (let [[start segments] (if (and paused (= redefinitions (:redefinitions paused)))
                           [(:next paused) (:segments paused)]
                           [0 []])]
    (loop [i start segments segments]
      (if (>= i (count statements))
        [:done segments]
        (let [statement (nth statements i)]
          (visit! budget)
          (if (= :partial-reference (tag statement))
            (let [path (second (second statement))]
              (if-let [body (get compiled path)]
                (recur (inc i) (conj segments [:partial body]))
                [:waiting path {:segments segments :next i :redefinitions redefinitions}]))
            (do (check-member-names! source statement)
                (recur (inc i) (conj segments [:member statement])))))))))

(defn- compile-partials
  "SpiceDB's collectPartials: resolves partials in source order. A partial
   whose reference is not compiled yet waits for it, and is translated again
   (before anything that waits on it in turn is released) once that partial
   compiles. A later partial with the same name replaces an earlier one.
   Returns {name segments}."
  [source partials budget]
  (let [partials (vec partials)
        statements (mapv #(vec (rest (nth % 2))) partials)
        compiled (volatile! {})
        ;; Waiting partials by the name they wait for, as indexes into `partials`.
        waiting (volatile! {})
        paused (volatile! {})
        redefinitions (volatile! 0)]
    (dotimes [first-index (count partials)]
      ;; An explicit stack of [compiled name, partials it released, next index];
      ;; `pending` is the partial to translate next, or nil (boxed, so it can be).
      (loop [pending (identity first-index) frames []]
        (let [frames
              (if (nil? pending)
                frames
                (let [node (nth partials pending)
                      resumed (get @paused pending)
                      _ (vswap! paused dissoc pending)
                      [outcome value state]
                      (translate-partial source @compiled (nth statements pending) resumed
                                         @redefinitions budget)]
                  (if (= :waiting outcome)
                    (do (vswap! paused assoc pending state)
                        (vswap! waiting update value (fnil conj []) pending)
                        frames)
                    (let [path (path-text (second node))]
                      (when (contains? @compiled path)
                        (vswap! redefinitions inc))
                      (vswap! compiled assoc path value)
                      (conj frames [path (get @waiting path []) 0])))))]
          (when-let [[path ready i] (peek frames)]
            (if (< i (count ready))
              (recur (nth ready i) (conj (pop frames) [path ready (inc i)]))
              (do (vswap! waiting dissoc path)
                  (recur nil (pop frames))))))))
    (when (seq @waiting)
      (fail! :eacl.schema/invalid-partial
             (str "Could not resolve partials " (pr-str (vec (sort (keys @waiting))))
                  "; this may indicate a circular reference.")
             {:reason :unresolved :partials (vec (sort (keys @waiting)))}))
    @compiled))

(defn- flatten-partial
  "Appends a compiled partial's members to `out`, depth first."
  [out segments budget]
  (loop [out out stack [[segments 0]]]
    (if-let [[current i] (peek stack)]
      (if (< i (count current))
        (let [[kind value] (nth current i)
              stack (conj (pop stack) [current (inc i)])]
          (visit! budget)
          (if (= :member kind)
            (recur (conj out value) stack)
            (recur out (conj stack [value 0]))))
        (recur out (pop stack)))
      out)))

(defn- expand-definition
  "A definition's members: its relations and permissions, with compiled
   partials spliced in."
  [source compiled body budget]
  (reduce (fn [out statement]
            (visit! budget)
            (if (= :partial-reference (tag statement))
              (let [reference (second statement)
                    path (second reference)]
                (if-let [segments (get compiled path)]
                  (flatten-partial out segments budget)
                  (fail! :eacl.schema/invalid-partial
                         (str "Could not find a partial named " (pr-str path) ".")
                         (merge {:reason :undefined :partial path} (position source reference)))))
              (do (check-member-names! source statement)
                  (conj out statement))))
          []
          (rest body)))

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

(def ^:private empty-queue
  #?(:clj clojure.lang.PersistentQueue/EMPTY :cljs cljs.core/PersistentQueue.EMPTY))

(defn- wildcard-index
  "SpiceDB's referencesWildcardType for every relation of the schema: the
   first wildcard reachable from a relation through its subject types,
   following subject relations (a permission has no subject types). First is
   breadth-first in subject-type order: the nearest wildcard, ties broken by
   subject-type order at each step. Computed per definition and relation (see
   the corpus' :spicedb-defects for SpiceDB's relation-name cache), for every
   relation at once in time linear in the schema. Returns
   `{[definition relation] {:definition d :relation r :wildcard t}}`."
  [definitions]
  (let [nodes (vec (for [[d {:keys [relations]}] definitions
                         [r refs] relations]
                     [d r refs]))
        n (count nodes)
        ids (into {} (map-indexed (fn [i [d r]] [[d r] i])) nodes)
        own (mapv (fn [[_ _ refs]] (some #(when (:wildcard? %) (:subject-type %)) refs)) nodes)
        ;; Successors in subject-type order.
        successors (mapv (fn [[_ _ refs]]
                           (vec (keep (fn [{:keys [subject-type subject-relation]}]
                                        (when subject-relation
                                          (get ids [subject-type subject-relation])))
                                      refs)))
                         nodes)
        predecessors (reduce-kv (fn [acc i targets]
                                  (reduce #(update %1 %2 conj i) acc targets))
                                (vec (repeat n []))
                                successors)
        sources (vec (keep-indexed (fn [i w] (when w i)) own))
        ;; Distances to the nearest wildcard, breadth-first backwards from the wildcards.
        [distance order]
        (loop [queue (into empty-queue sources)
               distance (reduce #(assoc %1 %2 0) (vec (repeat n nil)) sources)
               order (transient [])]
          (if-let [i (peek queue)]
            (let [further (inc (nth distance i))
                  [queue distance]
                  (reduce (fn [[queue distance] p]
                            (if (nil? (nth distance p))
                              [(conj queue p) (assoc distance p further)]
                              [queue distance]))
                          [(pop queue) distance]
                          (nth predecessors i))]
              (recur queue distance (conj! order i)))
            [distance (persistent! order)]))
        ;; Nearer relations first: each takes its own wildcard, or that of its
        ;; first successor one step nearer.
        found (reduce (fn [found i]
                        (assoc found i
                               (if-let [wildcard (nth own i)]
                                 (let [[d r] (nth nodes i)]
                                   {:definition d :relation r :wildcard wildcard})
                                 (let [d (nth distance i)]
                                   (some #(when (= (dec d) (nth distance %)) (get found %))
                                         (nth successors i))))))
                      {}
                      order)]
    (into {} (map (fn [[i w]] [(subvec (nth nodes i) 0 2) w])) found)))

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
  [wildcards {:keys [name relations permissions]}]
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
                    (when-let [w (get wildcards [name tupleset])]
                      (issue :wildcard-arrow-base name permission-name path
                             {:name (keyword tupleset)
                              :wildcard-type (keyword (:wildcard w))
                              :via [(keyword (:definition w)) (keyword (:relation w))]
                              :message (str "Relation " name "#" tupleset " includes a wildcard subject"
                                            " type: wildcard relations cannot be used on the left side of arrows.")})))))]
        :when problem]
    problem))

(defn- first-repeat
  "The first item, in order of first occurrence, that occurs more than once."
  [items]
  (let [counts (frequencies items)]
    (some #(when (> (get counts %) 1) %) items)))

(defn- check-declaration!
  "A definition's own errors, checked before the next declaration is read:
   a repeated relation or permission name, and a repeated subject type in one
   relation (compared by source, `user#...` as `user`)."
  [{:keys [name members relations]}]
  (when-let [duplicate (first-repeat (keep member-name members))]
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
    (when-let [duplicate (first-repeat (map :source refs))]
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
  [definitions wildcards {:keys [name relations]}]
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
                (when-let [w (get wildcards [subject-type subject-relation])]
                  (issue :transitive-wildcard name nil path
                         (assoc data :name (keyword subject-relation)
                                :wildcard-type (keyword (:wildcard w))
                                :via [(keyword (:definition w)) (keyword (:relation w))]
                                :message (str "Subject relation " subject-type "#" subject-relation
                                              " includes a wildcard subject type: wildcard relations"
                                              " cannot be transitively included.")))))]
        :when problem]
    problem))

(defn- source-ast
  "The unresolved source AST `eacl.spicedb.parser/permission-expression->source-ast`
   builds, for EACL's source limits: n-ary unions and intersections,
   left-folded binary exclusions, parentheses as nothing, and `nil`, `self`
   and `.all()` as leaves."
  [node]
  (let [n-ary (fn [op children]
                (let [children (mapv source-ast children)]
                  (if (= 1 (count children)) (first children) {:op op :children children})))]
    (case (tag node)
      :permission-expr (source-ast (second node))
      :exclusion-expr (reduce (fn [left right] {:op :exclusion :left left :right right})
                              (map source-ast (rest node)))
      :intersect-expr (n-ary :intersection (rest node))
      :union-expr (n-ary :union (rest node))
      :arrow-expr (source-ast (second node))
      :simple-arrow-expr (let [[base target] (rest node)]
                           (if target {:op :arrow} (source-ast base)))
      :arrow-func-expr {:op :arrow}
      :base-expr (let [inner (second node)]
                   (if (= :paren-expr (tag inner))
                     (source-ast (second inner))
                     {:op :identifier})))))

(defn- check-source-limits!
  "EACL's source limits ahead of SpiceDB's reference checks, in the
   resolver's order: each relation's subject-type count (definitions and
   relations by name), then each permission's source nodes, depth and direct
   fan-in (definitions and permissions by name). They bound the expressions
   every later step walks."
  [models limits]
  (let [models (sort-by :name models)]
    (doseq [{:keys [relations]} models
            [_ refs] (sort-by key relations)]
      (expression-limits/check-dimension! :type-partition-count :maximum-type-partitions
                                          (count (distinct (map :subject-type refs)))
                                          limits))
    (doseq [{:keys [permissions]} models
            [_ member] (sort-by key permissions)]
      (expression-limits/check-source! (source-ast (last member)) limits))))

(defn- check-references!
  "Every reference issue of the schema, sorted as the resolver sorts them."
  [definitions models]
  (let [wildcards (wildcard-index definitions)
        issues (->> models
                    (mapcat #(concat (relation-issues definitions wildcards %)
                                     (expression-issues wildcards %)))
                    distinct
                    (sort-by issue-sort-key)
                    vec)]
    (when (seq issues) (resolution-failed! issues))))

(defn- type-graph
  "The graph SpiceDB's GetRecursiveTerminalTypesForRelation walks, compiled
   once: a node per relation, permission or arrow target, with its subject
   types (a relation's plain and wildcard types) and the nodes it leads to (a
   relation's subject relations; a permission's computed usersets, and its
   arrows' targets on every subject type of their relation). Returns
   `{:ids {[definition relation] id} :edges [[id ...] ...] :types [[t ...] ...]}`."
  [definitions]
  (let [graph (volatile! {:ids {} :edges [] :types []})
        node! (fn [d r]
                (or (get-in @graph [:ids [d r]])
                    (let [id (count (:edges @graph))]
                      (vswap! graph #(-> %
                                         (assoc-in [:ids [d r]] id)
                                         (update :edges conj [])
                                         (update :types conj [])))
                      id)))
        edge! (fn [from d r]
                (let [to (node! d r)]
                  (vswap! graph update-in [:edges from] conj to)))
        ;; Each loop body is a function of its own: `doseq` copies its body
        ;; for every combination of chunked and unchunked bindings, which
        ;; made this one method larger than the JVM compiles.
        relation-reference!
        (fn [from {:keys [subject-type subject-relation wildcard?]}]
          (if (and subject-relation (not wildcard?))
            (edge! from subject-type subject-relation)
            (vswap! graph update-in [:types from] conj subject-type)))
        permission-leaf!
        (fn [name relations from {:keys [kind tupleset target] :as leaf}]
          (case kind
            :computed (edge! from name (:name leaf))
            :arrow (doseq [subject-type (sort (distinct (map :subject-type (get relations tupleset))))]
                     (edge! from subject-type target))))]
    (doseq [[name {:keys [relations permissions]}] definitions]
      (doseq [[relation refs] relations
              :let [from (node! name relation)]
              reference refs]
        (relation-reference! from reference))
      (doseq [[permission member] permissions
              :let [from (node! name permission)]
              leaf (expression-leaves (last member))]
        (permission-leaf! name relations from leaf)))
    @graph))

(defn- terminal-types
  "SpiceDB's GetRecursiveTerminalTypesForRelation from node `start`: every
   subject type reachable through relations, subject relations and arrows (its
   shared visited set loses none of them), and the number of nodes visited."
  [{:keys [edges types]} start]
  (loop [stack [start] seen (transient #{}) visited 0 out (transient #{})]
    (if-let [id (peek stack)]
      (let [stack (pop stack)]
        (if (contains? seen id)
          (recur stack seen visited out)
          (recur (into stack (nth edges id))
                 (conj! seen id)
                 (inc visited)
                 (reduce conj! out (nth types id)))))
      [(persistent! out) visited])))

(defn- validate-annotations!
  "SpiceDB's `use typechecking` check: every subject type an annotated
   permission reaches is in its annotation (definitions in source order,
   permissions by name)."
  [definitions models budget]
  (let [graph (type-graph definitions)]
    (doseq [{:keys [name permissions]} models
            [permission-name member] (sort-by key permissions)
            :let [annotation (child member :type-annotation)]
            :when annotation
            :let [[types visited] (terminal-types graph (get-in graph [:ids [name permission-name]]))
                  _ (visit! budget visited :typechecking)
                  allowed (set (map second (rest annotation)))
                  missing (sort (remove allowed types))]
            :when (seq missing)]
      (fail! :eacl.schema/incomplete-type-annotation
             (str "Incomplete type annotation on `" name "#" permission-name "`: `" (first missing)
                  "` is reachable but not in " (pr-str (vec (sort allowed))) ".")
             {:resource-type name :permission permission-name
              :reachable (vec missing) :annotation (vec (sort allowed))}))))

(defn- alias-target
  "The name a permission aliases: its rewrite is exactly one computed userset."
  [member]
  (let [ir (expression-ir (last member))]
    (when (= :computed (:op ir)) (:name ir))))

(defn- validate-alias-cycles!
  "SpiceDB's computePermissionAliases: an alias resolves once its target is
   resolved or no alias; what never resolves is a cycle or leads into one.
   Each alias chain is followed once."
  [{:keys [name permissions]}]
  (let [aliases (into {} (keep (fn [[p member]]
                                 (when-let [target (alias-target member)]
                                   (when (contains? permissions target) [p target]))))
                      permissions)
        states (reduce
                (fn [states start]
                  (if (contains? states start)
                    states
                    (loop [at start chain [] visiting #{}]
                      (let [settle (fn [state] (into states (map (fn [p] [p state])) chain))
                            state (get states at)]
                        (cond
                          (= :resolved state) (settle :resolved)
                          (or (= :cyclic state) (contains? visiting at)) (settle :cyclic)
                          (not (contains? aliases at)) (settle :resolved)
                          :else (recur (get aliases at) (conj chain at) (conj visiting at)))))))
                {}
                (keys aliases))
        cyclic (sort (keep (fn [[p state]] (when (= :cyclic state) p)) states))]
    (when (seq cyclic)
      (fail! :eacl.schema/permission-alias-cycle
             (str "Under definition `" name "`, there exists a cycle in permissions: "
                  (str/join ", " cyclic) ".")
             {:resource-type name :permissions (vec cyclic)}))))

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

(defn- declarations
  "Partials, every declaration in source order, and caveat references."
  [source tree budget]
  (let [items (rest tree)
        flags (set (map second (filter #(= :use-flag (tag %)) items)))
        partials (filter #(= :partial (tag %)) items)
        compiled (compile-partials source partials budget)
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
                   members (expand-definition source compiled (nth item 2) budget)
                   _ (check-name! source :definition name name-node)
                   model (definition-model name members)]
               ;; A declaration's own errors precede its name check, and both
               ;; precede the next declaration (source-order precedence).
               (check-declaration! model)
               (claim-name! :definition name item)
               [:definition (assoc model :node item)]))))
        models (vec (keep (fn [[kind model]] (when (= :definition kind) model)) translated))
        caveats (vec (keep (fn [[kind d]] (when (= :caveat kind) d)) translated))
        caveat-names (set (map :name caveats))]
    (doseq [model models] (check-caveat-references! caveat-names model))
    {:flags flags
     :models models
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

(defn validate
  "Validates a parse tree against SpiceDB's rules. See the namespace docstring.
   `limits` are the client's normalized expression limits: the source limits
   checked before the reference checks, and :maximum-schema-source-bytes for
   the expansion and typechecking budgets (EACL's default when absent)."
  ([source tree]
   (validate source tree {}))
  ([source tree limits]
   (let [source-bytes (or (:maximum-schema-source-bytes limits)
                          (:maximum-schema-source-bytes expression-policy/schema-limits))
         expansion (budget (partial-expansion-budget source-bytes))
         {:keys [flags models] :as declared} (declarations source tree expansion)
         definitions (into {} (map (juxt :name identity)) models)]
     (check-source-limits! models limits)
     (check-references! definitions models)
     (when (contains? flags "typechecking")
       (validate-annotations! definitions models (budget (typechecking-budget source-bytes))))
     (doseq [model models] (validate-alias-cycles! model))
     (dissoc declared :models))))

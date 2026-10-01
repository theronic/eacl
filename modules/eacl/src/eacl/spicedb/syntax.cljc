(ns eacl.spicedb.syntax
  "Recursive-descent parser for the SpiceDB v1.56.0 schema language.

  The parser follows SpiceDB's `pkg/schemadsl/parser` production by production
  over the tokens of `eacl.spicedb.lexer`, and stops at the first error instead
  of recovering. It returns a vector parse tree; every node SpiceDB accepts has
  a node here, whether or not EACL supports it:

    [:schema item*]
    item     = [:use-flag name]
             | [:definition type-path [:definition-body member*]]
             | [:partial type-path [:definition-body member*]]
             | [:caveat-definition type-path [:caveat-parameters param+]
                [:caveat-source text]]
    member   = [:relation [:relation-name ident] [:relation-type-expr ref+]]
             | [:permission ident [:type-annotation ident+]? expr]
             | [:partial-reference ident]
    ref      = [:relation-type-ref type-path
                [:relation-modifier [:wildcard]]?
                [:relation-modifier [:subject-relation ident]]?
                [:caveat-ref type-path]? [:expiration]?]
    param    = [:caveat-parameter ident type]
    type     = [:caveat-type ident type*]
    expr     = [:permission-expr [:exclusion-expr [:intersect-expr+]]]
               [:intersect-expr [:union-expr+]], [:union-expr [:arrow-expr+]]
               [:arrow-expr [:simple-arrow-expr base base?]]
               [:arrow-expr [:arrow-func-expr ident [:arrow-func-name f] ident]]
               base = [:base-expr ident | [:nil-expr] | [:self-expr]
                                       | [:paren-expr expr]]
    ident    = [:identifier text], type-path = [:type-path ident+]

  `user#...` is the plain subject type `user`, so it has no modifier node.
  Statements that SpiceDB compiles but rejects without looking at the rest of
  the schema fail here: chained arrows, `.f()` other than `any`/`all`,
  `with expiration and ...`, `use` after a definition or with an unknown flag,
  and every `import` (WriteSchema does not resolve imports).

  Nodes carry `:eacl.spicedb/span` metadata `[start end]` (string indices).
  Errors are `:eacl.schema/parse-error` ex-info with `:reason`, `:line`,
  `:column`, `:offset`, `:expected` and `:found`."
  (:require [clojure.string :as str]
            [eacl.spicedb.lexer :as lexer]))

(def maximum-nesting-depth
  "Deepest parenthesis or caveat type-argument nesting the parser descends.
   SpiceDB has no such bound; EACL's hard source-depth ceiling is the same."
  256)

(def use-flags (set (keys lexer/flag-keywords)))

(defn- describe [{:keys [kind text]}]
  (case kind
    :eof "end of input"
    :past-eof "end of input"
    :synthetic-semicolon "end of line"
    :identifier (str "identifier '" text "'")
    :keyword (str "keyword '" text "'")
    :string "string"
    :error (str "'" text "'")
    (str "'" text "'")))

(defn- parse-error!
  [{:keys [source]} token reason expected]
  (let [offset (:start token (count source))
        [line column] (lexer/line-column source offset)
        found (describe token)
        detail (case reason
                 :invalid-character (str "unrecognized character " found)
                 :unterminated-string "unterminated string"
                 :unterminated-comment "unterminated /* comment"
                 (str "expected " expected ", found " found))]
    (throw
     (ex-info (str "Schema parse error at line " line ", column " column ": " detail ".")
              {:type :eacl.schema/parse-error
               :eacl/error :eacl.schema/parse-error
               :reason reason
               :line line
               :column column
               :offset offset
               :expected expected
               :found found}))))

(defn- current [{:keys [tokens position]}]
  (let [i @position]
    (if (< i (count tokens))
      (nth tokens i)
      ;; SpiceDB reads a zero-valued (error-kind) token after end of input.
      {:kind :past-eof :text "" :start (count (:source (meta tokens)))})))

(defn- fail!
  "Fails at the current token. A lexical error token reports the lexer's
   reason wherever the parser meets it."
  [p expected]
  (let [token (current p)]
    (if (= :error (:kind token))
      (parse-error! p token (:reason token) expected)
      (parse-error! p token :syntax expected))))

(defn- token? [p kind]
  (= kind (:kind (current p))))

(defn- keyword-token? [p text]
  (let [{:keys [kind] :as token} (current p)]
    (and (= :keyword kind) (= text (:text token)))))

(defn- identifier-token? [p text]
  (let [{:keys [kind] :as token} (current p)]
    (and (= :identifier kind) (= text (:text token)))))

(defn- advance! [{:keys [position] :as p}]
  (let [token (current p)]
    (vswap! position inc)
    token))

(defn- try-consume! [p & kinds]
  (when (some #(token? p %) kinds)
    (advance! p)))

(defn- consume! [p kind expected]
  (or (try-consume! p kind) (fail! p expected)))

(defn- spanned [node start end]
  (with-meta node {:eacl.spicedb/span [start end]}))

(defn- previous-end [{:keys [tokens position]}]
  (let [i (dec @position)]
    (if (neg? i) 0 (:end (nth tokens i)))))

(defn- identifier! [p expected]
  (let [token (consume! p :identifier expected)]
    (spanned [:identifier (:text token)] (:start token) (:end token))))

(defn- type-path! [p]
  (let [start (:start (current p))]
    (loop [segments [(identifier! p "identifier")]]
      (if (try-consume! p :div)
        (recur (conj segments (identifier! p "identifier")))
        (spanned (into [:type-path] segments) start (previous-end p))))))

(defn- terminator? [p]
  (or (token? p :synthetic-semicolon) (token? p :semicolon) (token? p :eof)))

(defn- consume-terminator! [p]
  (when-not (terminator? p)
    (fail! p "end of statement or definition"))
  (advance! p))

;; ---------------------------------------------------------------- expressions

(declare expression!)

(defn- wrap [tag child]
  (with-meta [tag child] (meta child)))

(defn- arrow-function! [p base]
  ;; `base.any(target)` / `base.all(target)`
  (let [function (identifier! p "arrow function `any` or `all`")]
    (when-not (contains? #{"any" "all"} (second function))
      (parse-error! p {:kind :identifier :text (second function)
                       :start (first (:eacl.spicedb/span (meta function)))}
                    :arrow-function "`any` or `all`"))
    (consume! p :left-paren "'('")
    (let [target (identifier! p "relation or permission name")]
      (consume! p :right-paren "')'")
      [:arrow-func-expr base [:arrow-func-name (second function)] target])))

(defn- arrow-or-base! [p depth]
  (let [{:keys [start] :as token} (current p)]
    (cond
      (= :identifier (:kind token))
      (let [base (identifier! p "identifier")
            node
            (cond
              (try-consume! p :right-arrow)
              (let [target (if (token? p :identifier)
                             (identifier! p "identifier")
                             (fail! p "relation or permission name after '->'"))]
                [:simple-arrow-expr [:base-expr base] [:base-expr target]])

              (try-consume! p :period)
              (arrow-function! p base)

              :else [:simple-arrow-expr [:base-expr base]])]
        (when (and (not= [:simple-arrow-expr [:base-expr base]] node)
                   (or (token? p :right-arrow) (token? p :period)))
          ;; SpiceDB parses `a->b->c` and then rejects it.
          (parse-error! p (current p) :nested-arrow "end of arrow (nested arrows are not supported)"))
        (spanned [:arrow-expr node] start (previous-end p)))

      (= :left-paren (:kind token))
      (do
        (when (>= depth maximum-nesting-depth)
          (throw
           (ex-info "Schema parse error: parentheses nest too deeply."
                    {:type :eacl.schema/parse-error
                     :eacl/error :eacl.schema/parse-error
                     :reason :nesting-depth
                     :maximum maximum-nesting-depth
                     :offset start})))
        (advance! p)
        (let [inner (expression! p (inc depth))]
          (consume! p :right-paren "')'")
          (spanned [:arrow-expr [:simple-arrow-expr [:base-expr [:paren-expr inner]]]]
                   start (previous-end p))))

      (and (= :keyword (:kind token)) (= "nil" (:text token)))
      (do (advance! p)
          (spanned [:arrow-expr [:simple-arrow-expr [:base-expr [:nil-expr]]]]
                   start (previous-end p)))

      (and (= :keyword (:kind token)) (= "self" (:text token)))
      (do (advance! p)
          (spanned [:arrow-expr [:simple-arrow-expr [:base-expr [:self-expr]]]]
                   start (previous-end p)))

      :else nil)))

(defn- operand! [p depth operand-fn]
  (or (operand-fn p depth)
      (fail! p "right hand expression")))

(defn- binary-level
  "SpiceDB's left-recursive binary operator parsing for one precedence level."
  [p depth tag operator operand-fn]
  (let [start (:start (current p))]
    (when-let [first-operand (operand-fn p depth)]
      (loop [operands [first-operand]]
        (if (try-consume! p operator)
          (recur (conj operands (operand! p depth operand-fn)))
          (spanned (into [tag] operands) start (previous-end p)))))))

(defn- union-level [p depth]
  (binary-level p depth :union-expr :plus arrow-or-base!))

(defn- intersect-level [p depth]
  (binary-level p depth :intersect-expr :and union-level))

(defn- exclusion-level [p depth]
  (binary-level p depth :exclusion-expr :minus intersect-level))

(defn- expression!
  "A permission expression: `-` binds loosest, then `&`, then `+`."
  [p depth]
  (if-let [node (exclusion-level p depth)]
    (wrap :permission-expr node)
    (fail! p "permission expression")))

;; ---------------------------------------------------------------- definitions

(defn- with-traits!
  "`with cav`, `with expiration` (flag `expiration`), `with cav and expiration`.
   Returns the trait nodes."
  [p]
  (if-not (keyword-token? p "with")
    []
    (do
      (advance! p)
      (let [caveat (when-not (keyword-token? p "expiration")
                     [:caveat-ref (type-path! p)])
            continue? (or (nil? caveat)
                          (when (keyword-token? p "and") (advance! p) true))]
        (if-not continue?
          [caveat]
          (if (keyword-token? p "expiration")
            (do
              (advance! p)
              (when (keyword-token? p "and")
                (parse-error! p (current p) :expiration-order
                              "end of type (the caveat must come before expiration: `with <caveat> and expiration`)"))
              (cond-> [] caveat (conj caveat) true (conj [:expiration])))
            ;; `with cav and` followed by anything else: SpiceDB ignores the `and`.
            (cond-> [] caveat (conj caveat))))))))

(defn- relation-type-ref! [p]
  (let [start (:start (current p))
        path (type-path! p)
        modifier
        (cond
          (try-consume! p :colon)
          (do (consume! p :star "'*'")
              [[:relation-modifier [:wildcard]]])

          (try-consume! p :hash)
          (let [{:keys [kind] :as token} (current p)]
            (case kind
              :identifier [[:relation-modifier [:subject-relation (identifier! p "relation name")]]]
              ;; `user#...` is the plain subject type.
              :ellipsis (do (advance! p) [])
              (fail! p "relation name or '...'")))

          :else [])
        traits (with-traits! p)]
    (spanned (into (into [:relation-type-ref path] modifier) traits)
             start (previous-end p))))

(defn- relation! [p]
  (let [start (:start (advance! p))
        name (identifier! p "relation name")]
    (consume! p :colon "':'")
    (loop [refs [(relation-type-ref! p)]]
      (if (try-consume! p :pipe)
        (recur (conj refs (relation-type-ref! p)))
        (spanned [:relation [:relation-name name] (into [:relation-type-expr] refs)]
                 start (previous-end p))))))

(defn- type-annotation! [p]
  (loop [types [(identifier! p "type identifier in type annotation")]]
    (if (try-consume! p :pipe)
      (recur (conj types (identifier! p "type identifier after '|' in type annotation")))
      (into [:type-annotation] types))))

(defn- permission! [p]
  (let [start (:start (advance! p))
        name (identifier! p "permission name")
        annotation (when (try-consume! p :colon) (type-annotation! p))]
    (consume! p :equals "'='")
    (let [expression (expression! p 0)]
      (spanned (cond-> [:permission name]
                 annotation (conj annotation)
                 true (conj expression))
               start (previous-end p)))))

(defn- partial-reference! [p]
  (let [start (:start (advance! p))
        name (identifier! p "partial name")]
    (spanned [:partial-reference name] start (previous-end p))))

(defn- body! [p]
  (consume! p :left-brace "'{'")
  (loop [members []]
    (if (try-consume! p :right-brace)
      (into [:definition-body] members)
      (let [member (cond
                     (keyword-token? p "relation") (relation! p)
                     (keyword-token? p "permission") (permission! p)
                     (token? p :ellipsis) (partial-reference! p)
                     :else nil)]
        (consume-terminator! p)
        (recur (cond-> members member (conj member)))))))

(defn- definition! [p tag]
  (let [start (:start (advance! p))
        name (type-path! p)
        body (body! p)]
    (spanned [tag name body] start (previous-end p))))

;; ---------------------------------------------------------------- caveats

(defn- caveat-type! [p depth]
  (when (>= depth maximum-nesting-depth)
    (throw
     (ex-info "Schema parse error: caveat parameter types nest too deeply."
              {:type :eacl.schema/parse-error
               :eacl/error :eacl.schema/parse-error
               :reason :nesting-depth
               :maximum maximum-nesting-depth
               :offset (:start (current p))})))
  (let [name (identifier! p "type name")]
    (if (try-consume! p :less-than)
      (loop [children [(caveat-type! p (inc depth))]]
        (if (try-consume! p :comma)
          (recur (conj children (caveat-type! p (inc depth))))
          (do (consume! p :greater-than "'>'")
              (into [:caveat-type name] children))))
      [:caveat-type name])))

(defn- caveat-source!
  "SpiceDB's consumeCaveatExpression: every token up to the `}` that closes the
   body (braces nest; strings and comments are skipped). The CEL source is the
   text from the first token to the last. A line ending after an identifier
   or `)` is a token, so a comment between it and the last expression token is
   part of the source; whitespace and comments before the first token, and
   after the last, are not."
  [{:keys [source] :as p}]
  (loop [depth 1 first-token nil last-token nil]
    (let [{:keys [kind] :as token} (current p)]
      (cond
        (and (= :right-brace kind) (= 1 depth))
        (if first-token
          (let [end (:end (or last-token first-token))]
            (spanned [:caveat-source (subs source (:start first-token) end)]
                     (:start first-token) end))
          (parse-error! p token :missing-caveat-expression "caveat expression"))

        (contains? #{:eof :error :past-eof} kind)
        (fail! p "'}'")

        :else
        (do (advance! p)
            (recur (case kind
                     :left-brace (inc depth)
                     :right-brace (dec depth)
                     depth)
                   (or first-token token)
                   token))))))

(defn- caveat! [p]
  (let [start (:start (advance! p))
        name (type-path! p)]
    (consume! p :left-paren "'('")
    (let [parameters
          (loop [parameters []]
            (let [parameter-start (:start (current p))
                  parameter-name (identifier! p "parameter name")
                  parameter-type (caveat-type! p 0)
                  parameters (conj parameters
                                   (spanned [:caveat-parameter parameter-name parameter-type]
                                            parameter-start (previous-end p)))]
              (if (try-consume! p :comma)
                (recur parameters)
                parameters)))]
      (consume! p :right-paren "')'")
      (consume! p :left-brace "'{'")
      (let [caveat-source (caveat-source! p)]
        (consume! p :right-brace "'}'")
        (spanned [:caveat-definition name (into [:caveat-parameters] parameters) caveat-source]
                 start (previous-end p))))))

;; ---------------------------------------------------------------- top level

(defn- use-flag! [p seen-definition?]
  (let [start (:start (advance! p))
        token (current p)
        flag (cond
               (= :identifier (:kind token)) (:text (advance! p))
               (= :keyword (:kind token)) (:text (advance! p))
               :else (fail! p "use flag"))]
    (when-not (contains? use-flags flag)
      (parse-error! p token :unknown-use-flag
                    (str "one of " (str/join ", " (sort use-flags)))))
    (when seen-definition?
      (parse-error! p token :use-after-definition
                    "`use` before every definition and caveat"))
    (spanned [:use-flag flag] start (previous-end p))))

(defn- import! [p]
  (let [token (advance! p)]
    (consume! p :string "quote-delimited string")
    (parse-error! p token :import-not-allowed
                  "no `import` (imports must be compiled before writing a schema)")))

(defn parse
  "Parses SpiceDB schema source to a parse tree, or throws
   `:eacl.schema/parse-error`."
  [source]
  (let [tokens (with-meta (lexer/lex source) {:source source})
        p {:source source :tokens tokens :position (volatile! 0)}]
    (loop [items [] seen-definition? false]
      (if (token? p :eof)
        (with-meta (into [:schema] items) {:eacl.spicedb/source source})
        (do
          ;; One optional terminator between top-level items.
          (when (terminator? p) (advance! p))
          (cond
            (token? p :eof) (with-meta (into [:schema] items) {:eacl.spicedb/source source})
            (identifier-token? p "use") (recur (conj items (use-flag! p seen-definition?)) seen-definition?)
            (keyword-token? p "definition") (recur (conj items (definition! p :definition)) true)
            (keyword-token? p "caveat") (recur (conj items (caveat! p)) true)
            (keyword-token? p "partial") (recur (conj items (definition! p :partial)) seen-definition?)
            (keyword-token? p "import") (import! p)
            :else (fail! p "`definition`, `caveat`, `partial` or `use`")))))))

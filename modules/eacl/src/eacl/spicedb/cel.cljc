(ns eacl.spicedb.cel
  "Decides, where it can, whether SpiceDB v1.56.0 accepts a caveat expression.

  SpiceDB compiles each caveat body as CEL in an environment that declares only
  the caveat's parameters, requires a `bool` result, and requires every
  parameter to be referenced. EACL's CEL profile (`eacl.caveats.plan`) accepts a
  subset of that language, so a body outside the profile may still be valid
  SpiceDB. Schema admission uses `check` to tell the two apart:

  - `:invalid`: SpiceDB rejects the body (CEL syntax or type error, a non-bool
    result, or an unreferenced parameter).
  - `:valid`: SpiceDB accepts it.
  - `:unknown`: the body uses CEL this checker does not model (arithmetic,
    macros, function calls, container literals, `?:`, `null`, type values).

  The checker parses CEL's grammar for literals (bool, int, uint, double,
  string and bytes, with CEL's escapes), identifiers, `!`, `&&`, `||`, the
  relations `== != < <= > >= in`, indexing, field selection, and the string
  methods `contains`, `startsWith` and `endsWith`, and types them with CEL's
  standard overloads over SpiceDB's parameter types (`uint` is CEL `int` in
  SpiceDB, `any` is `dyn`, map keys are strings). SpiceDB's CEL environment
  does not enable cross-type numeric comparison."
  (:require [clojure.string :as str]))

(def reserved-words
  "CEL reserved identifiers; using one as an identifier is a syntax error."
  #{"as" "break" "const" "continue" "else" "for" "function" "if" "import"
    "let" "loop" "package" "namespace" "return" "var" "void" "while"})

(def literal-words #{"true" "false" "null" "in"})

(def type-identifiers
  "Identifiers CEL predeclares as type values. A parameter with one of these
   names overlaps the declaration and SpiceDB rejects the caveat."
  #{"bool" "int" "uint" "double" "string" "bytes" "list" "map" "type" "null_type"})

(def maximum-depth 256)

(defn cel-identifier? [s]
  (boolean (and (string? s) (re-matches #"[_a-zA-Z][_a-zA-Z0-9]*" s))))

(defn referenceable-parameter?
  "Whether a caveat expression can reference a parameter of this name. SpiceDB
   rejects a caveat with any other parameter name: it either overlaps a CEL
   declaration or can never be referenced, and every parameter must be."
  [s]
  (and (cel-identifier? s)
       (not (contains? reserved-words s))
       (not (contains? literal-words s))
       (not (contains? type-identifiers s))))

;; ---------------------------------------------------------------- outcomes

(defn- invalid! [reason offset & [detail]]
  (throw (ex-info "Invalid CEL." {::outcome :invalid :reason reason :offset offset :detail detail})))

(defn- unknown! [reason offset]
  (throw (ex-info "Unmodelled CEL." {::outcome :unknown :reason reason :offset offset})))

;; ---------------------------------------------------------------- lexing

(defn- code-at [s i]
  #?(:clj (int (.charAt ^String s (int i))) :cljs (.charCodeAt s i)))

(defn- digit? [c] (<= 48 c 57))
(defn- hex-digit? [c] (or (digit? c) (<= 97 c 102) (<= 65 c 70)))
(defn- octal-digit? [c] (<= 48 c 55))
(defn- ident-start? [c] (or (<= 97 c 122) (<= 65 c 90) (= c 95)))
(defn- ident-part? [c] (or (ident-start? c) (digit? c)))

(defn- parse-hex [s] #?(:clj (Long/parseLong s 16) :cljs (js/parseInt s 16)))

(defn- check-escapes!
  "CEL's escape sequences: \\\\ \\? \\\" \\' \\` \\a \\b \\f \\n \\r \\t \\v, \\xHH,
   \\uHHHH, \\UHHHHHHHH (at most U+10FFFF) and octal \\[0-3][0-7][0-7]. Bytes
   literals do not admit \\u or \\U."
  [body offset bytes?]
  (let [n (count body)]
    (loop [i 0]
      (when (< i n)
        (if (not= 92 (code-at body i))
          (recur (inc i))
          (let [e (when (< (inc i) n) (code-at body (inc i)))
                hex (fn [width]
                      (let [end (+ i 2 width)]
                        (when-not (and (<= end n)
                                       (every? #(hex-digit? (code-at body %)) (range (+ i 2) end)))
                          (invalid! :string-escape offset))
                        end))]
            (cond
              (nil? e) (invalid! :string-escape offset)
              (contains? #{92 63 34 39 96 97 98 102 110 114 116 118} e) (recur (+ i 2))
              (or (= e 120) (= e 88)) (recur (long (hex 2)))
              (= e 117) (do (when bytes? (invalid! :string-escape offset)) (recur (long (hex 4))))
              (= e 85) (do (when bytes? (invalid! :string-escape offset))
                           (let [end (hex 8)]
                             (when (> (parse-hex (subs body (+ i 2) end)) 0x10FFFF)
                               (invalid! :string-escape offset))
                             (recur (long end))))
              (<= 48 e 51) (do (when-not (and (< (+ i 3) n)
                                              (octal-digit? (code-at body (+ i 2)))
                                              (octal-digit? (code-at body (+ i 3))))
                                 (invalid! :string-escape offset))
                               (recur (+ i 4)))
              :else (invalid! :string-escape offset))))))))

(defn- read-string-literal
  "A CEL string or bytes literal at `start` (after any r/b prefixes, given as
   flags). Returns [token next-index]."
  [source start raw? bytes?]
  (let [n (count source)
        quote-char (code-at source start)
        triple? (and (< (+ start 2) n)
                     (= quote-char (code-at source (inc start)) (code-at source (+ start 2))))
        delimiter (str/join (repeat (if triple? 3 1) (char quote-char)))
        body-start (+ start (count delimiter))]
    (loop [i body-start]
      (cond
        (>= i n) (invalid! :unterminated-string start)
        (and (not raw?) (= 92 (code-at source i)))
        (recur (+ i 2))
        (and (not triple?) (contains? #{10 13} (code-at source i)))
        (invalid! :unterminated-string start)
        (= delimiter (subs source i (min n (+ i (count delimiter)))))
        (let [body (subs source body-start i)]
          (when-not raw? (check-escapes! body start bytes?))
          [{:kind :literal :type (if bytes? :bytes :string) :offset start}
           (+ i (count delimiter))])
        :else (recur (inc i))))))

(defn- big-int [digits radix]
  #?(:clj (BigInteger. ^String digits (int radix))
     :cljs (js/BigInt (if (= 16 radix) (str "0x" digits) digits))))

(def ^:private int-max (big-int "9223372036854775807" 10))
(def ^:private negative-int-max (big-int "9223372036854775808" 10))
(def ^:private uint-max (big-int "18446744073709551615" 10))

(defn- exceeds? [magnitude limit]
  #?(:clj (pos? (.compareTo ^BigInteger magnitude ^BigInteger limit))
     :cljs (> magnitude limit)))

(defn- read-number
  "CEL numeric literals: decimal or `0x` hex integers with an optional `u`/`U`
   suffix, and doubles (`1.5`, `.5`, `1e3`)."
  [source start]
  (let [n (count source)
        scan (fn [i pred] (loop [i i] (if (and (< i n) (pred (code-at source i))) (recur (inc i)) i)))]
    (if (and (< (inc start) n) (= 48 (code-at source start)) (= 120 (code-at source (inc start))))
      (let [end (scan (+ start 2) hex-digit?)]
        (when (= end (+ start 2)) (invalid! :number start))
        (let [unsigned? (and (< end n) (contains? #{117 85} (code-at source end)))]
          [{:kind :literal :type (if unsigned? :uint :int) :offset start
            :magnitude (big-int (subs source (+ start 2) end) 16)}
           (if unsigned? (inc end) end)]))
      (let [int-end (scan start digit?)
            fraction? (and (< int-end n) (= 46 (code-at source int-end))
                           (< (inc int-end) n) (digit? (code-at source (inc int-end))))
            after-fraction (if fraction? (scan (inc int-end) digit?) int-end)
            exponent? (and (< after-fraction n) (contains? #{101 69} (code-at source after-fraction)))
            end (if exponent?
                  (let [sign-end (if (and (< (inc after-fraction) n)
                                          (contains? #{43 45} (code-at source (inc after-fraction))))
                                   (+ after-fraction 2)
                                   (inc after-fraction))
                        digits-end (scan sign-end digit?)]
                    (when (= digits-end sign-end) (invalid! :number start))
                    digits-end)
                  after-fraction)]
        (if (or fraction? exponent? (= int-end start))
          [{:kind :literal :type :double :offset start} end]
          (let [unsigned? (and (< int-end n) (contains? #{117 85} (code-at source int-end)))]
            [{:kind :literal :type (if unsigned? :uint :int) :offset start
              :magnitude (big-int (subs source start int-end) 10)}
             (if unsigned? (inc int-end) int-end)]))))))

(defn- string-prefix
  "The lower-cased string prefix (`r`, `b`, `rb` or `br`) starting at `i`
   when a quote follows it, else nil."
  [source i]
  (let [n (count source)]
    (some (fn [width]
            (when (and (< (+ i width) n) (contains? #{34 39} (code-at source (+ i width))))
              (let [prefix (str/lower-case (subs source i (+ i width)))]
                (when (contains? #{"r" "b" "rb" "br"} prefix) prefix))))
          [1 2])))

(def ^:private operators
  ["&&" "||" "==" "!=" "<=" ">=" "!" "<" ">" "(" ")" "[" "]" "{" "}" "," "." "?" ":"
   "+" "-" "*" "/" "%"])

(defn- check-token-pairs!
  "Token pairs that are a CEL syntax error wherever they occur. `/` must be
   followed by an operand, and `*` never starts one, so a `/* */` comment in
   an expression is always invalid CEL."
  [tokens]
  (doseq [[a b] (partition 2 1 tokens)]
    (when (and (= "/" (:value a)) (= "*" (:value b)))
      (invalid! :syntax (:offset a))))
  tokens)

(defn- tokenize [source]
  (let [n (count source)]
    (loop [i 0 tokens []]
      (if (>= i n)
        (check-token-pairs! (conj tokens {:kind :eof :offset n}))
        (let [c (code-at source i)]
          (cond
            (contains? #{9 10 12 13 32} c) (recur (inc i) tokens)

            (and (= c 47) (< (inc i) n) (= 47 (code-at source (inc i))))
            (recur (long (loop [j i] (if (or (>= j n) (= 10 (code-at source j))) j (recur (inc j))))) tokens)

            ;; String prefixes: r (raw), b (bytes), rb and br, in either case.
            (string-prefix source i)
            (let [prefix (string-prefix source i)
                  [token next] (read-string-literal source (+ i (count prefix))
                                                    (str/includes? prefix "r")
                                                    (str/includes? prefix "b"))]
              (recur (long next) (conj tokens (assoc token :offset i))))

            (ident-start? c)
            (let [end (loop [j i] (if (and (< j n) (ident-part? (code-at source j))) (recur (inc j)) j))
                  word (subs source i end)]
              (recur (long end) (conj tokens (case word
                                        "true" {:kind :literal :type :bool :offset i}
                                        "false" {:kind :literal :type :bool :offset i}
                                        "null" {:kind :null :offset i}
                                        "in" {:kind :operator :value "in" :offset i}
                                        {:kind :identifier :value word :offset i}))))

            (or (contains? #{34 39} c))
            (let [[token next] (read-string-literal source i false false)]
              (recur (long next) (conj tokens token)))

            (or (digit? c) (and (= c 46) (< (inc i) n) (digit? (code-at source (inc i)))))
            (let [[token next] (read-number source i)]
              (recur (long next) (conj tokens token)))

            :else
            (if-let [op (some #(when (= % (subs source i (min n (+ i (count %))))) %) operators)]
              (recur (+ i (count op)) (conj tokens {:kind :operator :value op :offset i}))
              (invalid! :character i))))))))

;; ---------------------------------------------------------------- types
;; Types: :bool :int :uint :double :string :bytes :timestamp :duration
;; :ipaddress :dyn :null :type, [:list t] and [:map t] (string keys).

(defn- unify? [a b]
  (cond
    (or (= :dyn a) (= :dyn b)) true
    (and (vector? a) (vector? b)) (and (= (first a) (first b)) (unify? (second a) (second b)))
    :else (= a b)))

(def ^:private orderable #{:bool :int :uint :double :string :bytes :timestamp :duration})

(defn- boolish? [t] (contains? #{:bool :dyn} t))

(defn- opaque? [t] (contains? #{:null :type} t))

(defn- equality-type [a b offset]
  (cond
    ;; Type values compare with type values (`int != string` is valid).
    (or (= :type a) (= :type b))
    (if (or (= a b) (= :dyn a) (= :dyn b)) :bool (invalid! :no-matching-overload offset))
    ;; null compares with null and dyn; the custom ipaddress type is not modelled.
    (or (= :null a) (= :null b))
    (cond
      (or (= :ipaddress a) (= :ipaddress b)) (unknown! :null offset)
      (or (= a b) (= :dyn a) (= :dyn b)) :bool
      :else (invalid! :no-matching-overload offset))
    (unify? a b) :bool
    :else (invalid! :no-matching-overload offset)))

(defn- ordering-type [a b offset]
  (if (and (not (opaque? a))
           (not (opaque? b))
           (cond
             (= :dyn a) (or (= :dyn b) (contains? orderable b))
             (= :dyn b) (contains? orderable a)
             :else (and (= a b) (contains? orderable a))))
    :bool
    (invalid! :no-matching-overload offset)))

(defn- membership-type [a b offset]
  (cond
    (opaque? b) (invalid! :no-matching-overload offset)
    (= :dyn b) (if (opaque? a) (unknown! :null offset) :bool)
    (and (vector? b) (= :list (first b)))
    (cond
      (and (opaque? a) (= :dyn (second b))) (unknown! :null offset)
      (opaque? a) (invalid! :no-matching-overload offset)
      (unify? a (second b)) :bool
      :else (invalid! :no-matching-overload offset))
    (and (vector? b) (= :map (first b)))
    (if (and (not (opaque? a)) (unify? a :string)) :bool (invalid! :no-matching-overload offset))
    :else (invalid! :no-matching-overload offset)))

(def ^:private relation-families
  {"==" :equality "!=" :equality
   "<" :ordering "<=" :ordering ">" :ordering ">=" :ordering
   "in" :membership})

;; The operator is looked up, not switched on: `case` over these seven
;; strings compiles to a table spanning their hash codes, 13 KB of bytecode,
;; and the JVM does not compile a method that large.
(defn- relation-type [op a b offset]
  (case (get relation-families op op)
    :equality (equality-type a b offset)
    :ordering (ordering-type a b offset)
    :membership (membership-type a b offset)))

(defn- arithmetic-type
  "CEL's standard arithmetic overloads (no cross-type numeric arithmetic)."
  [op a b offset]
  (when (or (opaque? a) (opaque? b))
    (invalid! :no-matching-overload offset))
  (when (or (= :dyn a) (= :dyn b))
    (unknown! :arithmetic offset))
  (let [same (when (= a b) a)
        list-result (when (and (vector? a) (vector? b) (= :list (first a) (first b)) (unify? a b))
                      (if (= :dyn (second a)) b a))
        result
        (case op
          "+" (cond
                (contains? #{:int :uint :double :string :bytes :duration} same) same
                list-result list-result
                (= #{:timestamp :duration} (hash-set a b)) :timestamp
                :else nil)
          "-" (cond
                (contains? #{:int :uint :double :duration} same) same
                (= [:timestamp :timestamp] [a b]) :duration
                (= [:timestamp :duration] [a b]) :timestamp
                :else nil)
          ("*" "/") (when (contains? #{:int :uint :double} same) same)
          "%" (when (contains? #{:int :uint} same) same))]
    (or result (invalid! :no-matching-overload offset))))

;; ---------------------------------------------------------------- parsing + typing

(defn- parse-and-type
  [tokens parameter-types]
  (let [position (volatile! 0)
        referenced (volatile! #{})]
    (letfn [(current [] (nth tokens (min @position (dec (count tokens)))))
            (peek-token [] (nth tokens (min (inc @position) (dec (count tokens)))))
            (take! [] (let [t (current)] (vswap! position inc) t))
            (operator? [value] (let [t (current)] (and (= :operator (:kind t)) (= value (:value t)))))
            (expect! [value]
              (if (operator? value) (take!) (invalid! :syntax (:offset (current)))))
            (unmodelled-at-current! []
              (let [t (current)]
                (if (and (= :operator (:kind t)) (contains? #{"{" "}"} (:value t)))
                  (unknown! :operator (:offset t))
                  (invalid! :syntax (:offset t)))))
            (literal-type [{:keys [type magnitude offset]} negative?]
              (case type
                :int (do (when (exceeds? magnitude (if negative? negative-int-max int-max))
                           (invalid! :int-literal offset))
                         :int)
                :uint (do (when (or negative? (exceeds? magnitude uint-max))
                            (invalid! :int-literal offset))
                          :uint)
                :double :double
                (do (when negative? (invalid! :no-matching-overload offset))
                    type)))
            (primary [depth]
              (when (> depth maximum-depth) (unknown! :depth (:offset (current))))
              (let [{:keys [kind value offset] :as t} (current)]
                (cond
                  (= :literal kind) (do (take!) (literal-type t false))

                  (= :null kind) (do (take!) :null)

                  (= :identifier kind)
                  (do (take!)
                      (cond
                        (operator? "(") (unknown! :function offset)
                        (contains? reserved-words value) (invalid! :reserved-identifier offset)
                        (contains? parameter-types value)
                        (do (vswap! referenced conj value) (get parameter-types value))
                        (contains? type-identifiers value) :type
                        :else (invalid! :undeclared-reference offset)))

                  (and (= :operator kind) (= "(" value))
                  (do (take!) (let [t (expression (inc depth))] (expect! ")") t))

                  (and (= :operator kind) (= "[" value))
                  ;; A non-empty list literal whose elements share one type.
                  (do (take!)
                      (when (operator? "]") (unknown! :list-literal offset))
                      (let [elements (loop [elements [(expression (inc depth))]]
                                       (if (operator? ",")
                                         (do (take!)
                                             (if (operator? "]")
                                               elements
                                               (recur (conj elements (expression (inc depth))))))
                                         elements))
                            _ (expect! "]")
                            concrete (set (remove #{:dyn} elements))]
                        (cond
                          (some opaque? elements) (unknown! :list-literal offset)
                          (empty? concrete) [:list :dyn]
                          (and (= 1 (count concrete)) (= (count concrete) (count (set elements))))
                          [:list (first concrete)]
                          (= 1 (count concrete)) [:list :dyn]
                          :else (unknown! :list-literal offset))))

                  (and (= :operator kind) (contains? #{"{" "."} value))
                  (unknown! :operator offset)

                  :else (invalid! :syntax offset))))
            (member [depth]
              (loop [t (primary depth)]
                (cond
                  (operator? ".")
                  (let [dot (take!)
                        field (current)]
                    (when (opaque? t) (invalid! :field-selection (:offset dot)))
                    (cond
                      (= :identifier (:kind field))
                      (do (take!)
                          (if (operator? "(")
                            (let [method (:value field)]
                              (when-not (contains? #{"contains" "startsWith" "endsWith"} method)
                                (unknown! :function (:offset field)))
                              (take!)
                              (let [args (if (operator? ")")
                                           []
                                           (loop [args [(expression (inc depth))]]
                                             (if (operator? ",")
                                               (do (take!) (recur (conj args (expression (inc depth)))))
                                               args)))]
                                (expect! ")")
                                (when (some opaque? args) (invalid! :no-matching-overload (:offset dot)))
                                (if (and (contains? #{:string :dyn} t)
                                         (= 1 (count args))
                                         (contains? #{:string :dyn} (first args)))
                                  (recur :bool)
                                  (invalid! :no-matching-overload (:offset dot)))))
                            (recur (cond
                                     (= :dyn t) :dyn
                                     (and (vector? t) (= :map (first t))) (second t)
                                     :else (invalid! :field-selection (:offset dot))))))
                      :else (invalid! :syntax (:offset field))))

                  (operator? "[")
                  (let [bracket (take!)
                        k (expression (inc depth))]
                    (expect! "]")
                    (when (opaque? t) (invalid! :no-matching-overload (:offset bracket)))
                    (when (opaque? k)
                      (if (= :dyn t)
                        (unknown! :null (:offset bracket))
                        (invalid! :no-matching-overload (:offset bracket))))
                    (recur (cond
                             (= :dyn t) :dyn
                             (and (vector? t) (= :list (first t)))
                             (if (contains? #{:int :dyn} k) (second t) (invalid! :no-matching-overload (:offset bracket)))
                             (and (vector? t) (= :map (first t)))
                             (if (contains? #{:string :dyn} k) (second t) (invalid! :no-matching-overload (:offset bracket)))
                             :else (invalid! :no-matching-overload (:offset bracket)))))

                  :else t)))
            (unary [depth]
              (cond
                (operator? "!")
                (let [offset (:offset (current))
                      bangs (loop [n 0] (if (operator? "!") (do (take!) (recur (inc n))) n))
                      t (member depth)]
                  ;; CEL's parser cancels pairs of `!`.
                  (cond
                    (even? bangs) t
                    (opaque? t) (invalid! :no-matching-overload offset)
                    (boolish? t) :bool
                    :else (invalid! :no-matching-overload offset)))

                (operator? "-")
                (let [offset (:offset (current))
                      minuses (loop [n 0] (if (operator? "-") (do (take!) (recur (inc n))) n))
                      literal (current)]
                  (if (and (= :literal (:kind literal)) (contains? #{:int :uint :double} (:type literal))
                           (not (contains? #{"." "["} (:value (peek-token)))))
                    ;; CEL folds a negated numeric literal.
                    (do (take!) (literal-type literal (odd? minuses)))
                    (let [t (member depth)]
                      (cond
                        (even? minuses) t
                        (contains? #{:int :double} t) t
                        (= :dyn t) (unknown! :arithmetic offset)
                        :else (invalid! :no-matching-overload offset)))))

                :else (member depth)))
            (multiplicative [depth]
              (loop [left (unary depth)]
                (let [t (current)]
                  (if (and (= :operator (:kind t)) (contains? #{"*" "/" "%"} (:value t)))
                    (do (take!) (recur (arithmetic-type (:value t) left (unary depth) (:offset t))))
                    left))))
            (additive [depth]
              (loop [left (multiplicative depth)]
                (let [t (current)]
                  (if (and (= :operator (:kind t)) (contains? #{"+" "-"} (:value t)))
                    (do (take!) (recur (arithmetic-type (:value t) left (multiplicative depth) (:offset t))))
                    left))))
            (relation [depth]
              (loop [left (additive depth)]
                (let [t (current)]
                  (if (and (= :operator (:kind t)) (contains? #{"==" "!=" "<" "<=" ">" ">=" "in"} (:value t)))
                    (do (take!)
                        (recur (relation-type (:value t) left (additive depth) (:offset t))))
                    left))))
            (logical [depth operator operand]
              (loop [left (operand depth)]
                (if (operator? operator)
                  (let [op (take!) right (operand depth)]
                    (cond
                      (or (opaque? left) (opaque? right)) (invalid! :no-matching-overload (:offset op))
                      (and (boolish? left) (boolish? right)) (recur :bool)
                      :else (invalid! :no-matching-overload (:offset op))))
                  left)))
            (conjunction [depth] (logical depth "&&" relation))
            (disjunction [depth] (logical depth "||" conjunction))
            (expression [depth]
              (when (> depth maximum-depth) (unknown! :depth (:offset (current))))
              (let [condition (disjunction depth)]
                (if (operator? "?")
                  (let [q (take!)
                        then (expression (inc depth))
                        _ (expect! ":")
                        otherwise (expression (inc depth))]
                    (cond
                      (some opaque? [condition then otherwise]) (unknown! :null (:offset q))
                      (not (boolish? condition)) (invalid! :no-matching-overload (:offset q))
                      (or (= :dyn then) (= :dyn otherwise)) (unknown! :conditional (:offset q))
                      (unify? then otherwise) then
                      :else (invalid! :no-matching-overload (:offset q))))
                  condition)))]
      (let [result (expression 0)]
        (when-not (= :eof (:kind (current)))
          (unmodelled-at-current!))
        {:type result :referenced @referenced}))))

(defn- spicedb-type
  "SpiceDB parameter types as the checker models them. nil for a type it
   does not model."
  [t]
  (cond
    (keyword? t) (case t :uint :int :any :dyn t)
    (and (vector? t) (contains? #{:list :map} (first t)))
    (when-let [element (spicedb-type (second t))] [(first t) element])
    :else nil))

(defn check
  "Checks one caveat body. `parameter-types` maps parameter names to SpiceDB
   types: `:bool :int :uint :double :string :bytes :duration :timestamp
   :ipaddress :any`, `[:list t]` and `[:map t]` (string keys). Returns
   `{:outcome :valid}`, `{:outcome :invalid :reason r}` or
   `{:outcome :unknown :reason r}`."
  [source parameter-types]
  (try
    (let [types (into {} (map (fn [[k v]] [k (spicedb-type v)])) parameter-types)
          {:keys [type referenced]} (parse-and-type (tokenize source) types)
          unused (sort (remove referenced (keys parameter-types)))]
      (cond
        (not= :bool type) {:outcome :invalid :reason :non-boolean-result :type type}
        (seq unused) {:outcome :invalid :reason :unused-parameter :parameters (vec unused)}
        :else {:outcome :valid}))
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
      (let [{::keys [outcome] :as data} (ex-data e)]
        (if outcome
          (-> data (dissoc ::outcome) (assoc :outcome outcome))
          (throw e))))))

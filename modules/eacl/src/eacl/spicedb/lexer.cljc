(ns eacl.spicedb.lexer
  "Lexer for the SpiceDB v1.56.0 schema language.

  This follows SpiceDB's `pkg/schemadsl/lexer` token for token, including its
  statement termination and `use` flags (docs/spicedb-schema-compatibility.md):

  - Whitespace is U+0020 and U+0009 only. `\\n` and `\\r` are newlines. Every
    other character outside a token, comment or string is an unrecognized
    character.
  - A newline is a statement terminator (a `:synthetic-semicolon` token) when
    the previous significant token is an identifier, a keyword, `)`, `}` or
    `*`. Otherwise it is whitespace. Comments are not significant, so a `/* */`
    comment that spans lines never terminates a statement.
  - Words are maximal runs of `_`, Unicode letters and Unicode decimal digits,
    as SpiceDB v1.56.0's Go tables (Unicode 15.0.0) classify them
    (`eacl.spicedb.unicode`), whatever the host's Unicode version.
    `definition caveat relation permission nil with` are keywords. Before the
    first `definition` or `caveat`, `use <flag>` makes further words keywords:
    `expiration` and `and` (flag `expiration`), `self`, `typechecking`,
    `partial` and `import`.
  - Strings are `\"...\"` and `'...'` on one line, or `\"\"\"...\"\"\"` across lines.
    SpiceDB closes a `'''` string only at `\"\"\"`. There are no escapes.

  `lex` returns a vector of significant tokens `{:kind :text :start :end}`
  (`:start`/`:end` are string indices). It ends with an `:eof` token, or with an
  `:error` token carrying `:reason` when lexing fails; SpiceDB stops there too."
  (:require [eacl.spicedb.unicode :as unicode]))

(def keywords
  "Words that are always keywords."
  #{"definition" "caveat" "relation" "permission" "nil" "with"})

(def flag-keywords
  "Words that each `use` flag turns into keywords."
  {"expiration" #{"expiration" "and"}
   "typechecking" #{"typechecking"}
   "self" #{"self"}
   "partial" #{"partial"}
   "import" #{"import"}})

(def ^:private terminator-predecessors
  #{:identifier :keyword :right-brace :right-paren :star})

(defn- code-point-at [s i]
  #?(:clj (.codePointAt ^String s (int i))
     :cljs (.codePointAt s i)))

(defn- code-point-width [cp]
  (if (> cp 0xFFFF) 2 1))

(defn word-char?
  "SpiceDB's `isAlphaNumeric`: `_`, a Unicode letter (category L) or a Unicode
   decimal digit (category Nd), in SpiceDB v1.56.0's Unicode 15.0.0."
  [cp]
  (or (<= 97 cp 122) (<= 65 cp 90) (<= 48 cp 57) (= cp 95)
      (and (> cp 127) (unicode/word? cp))))

(defn- starts-with-at? [s i prefix]
  (let [end (+ i (count prefix))]
    (and (<= end (count s))
         (= prefix (subs s i end)))))

(defn- newline-code? [c]
  (or (= c 10) (= c 13)))

(defn- token [kind source start end]
  {:kind kind :text (subs source start end) :start start :end end})

(defn- error-token [reason source start end message]
  {:kind :error :reason reason :message message
   :text (subs source start end) :start start :end end})

(def ^:private two-character-tokens
  {"->" :right-arrow "||" :conditional-or "&&" :conditional-and
   "==" :equal-equal "!=" :not-equal "<=" :less-equal ">=" :greater-equal})

(def ^:private one-character-tokens
  {123 :left-brace 125 :right-brace 40 :left-paren 41 :right-paren
   43 :plus 124 :pipe 38 :and 63 :question 33 :exclamation 91 :left-bracket
   93 :right-bracket 37 :percent 60 :less-than 62 :greater-than 44 :comma
   61 :equals 58 :colon 59 :semicolon 35 :hash 42 :star 46 :period 45 :minus
   47 :div})

(defn- string-end
  "The end of the string literal starting at `start`, or nil when it does not
   close (SpiceDB's lexStringLiteral)."
  [source start]
  (let [n (count source)
        [terminator multiline? body-start]
        (cond
          (starts-with-at? source start "\"\"\"") ["\"\"\"" true (+ start 3)]
          (starts-with-at? source start "'''") ["\"\"\"" true (+ start 3)]
          (starts-with-at? source start "\"") ["\"" false (inc start)]
          :else ["'" false (inc start)])]
    (loop [i body-start]
      (cond
        (starts-with-at? source i terminator) (+ i (count terminator))
        (>= i n) nil
        (and (not multiline?) (newline-code? (code-point-at source i))) nil
        :else (recur (+ i (code-point-width (code-point-at source i))))))))

(defn- block-comment-end [source start]
  (let [close #?(:clj (.indexOf ^String source "*/" (int (+ start 2)))
                 :cljs (.indexOf source "*/" (+ start 2)))]
    (when-not (neg? close) (+ close 2))))

(defn- line-comment-end [source start]
  (let [n (count source)]
    (loop [i (+ start 2)]
      (if (or (>= i n) (newline-code? (code-point-at source i)))
        i
        (recur (inc i))))))

(defn- word-end [source start]
  (let [n (count source)]
    (loop [i start]
      (if (and (< i n) (word-char? (code-point-at source i)))
        (recur (+ i (code-point-width (code-point-at source i))))
        i))))

(defn- raw-tokens
  "SpiceDB's lexerEntrypoint: significant tokens, synthetic semicolons, and a
   final :eof or :error token."
  [source]
  (let [n (count source)]
    (loop [i 0
           ;; Kind of the last token that was neither whitespace nor a comment.
           ;; Plain newlines count: a second newline never terminates.
           last-kind nil
           out (transient [])]
      (if (>= i n)
        (persistent! (conj! out (token :eof source n n)))
        (let [c (code-point-at source i)]
          (cond
            (or (= c 32) (= c 9))
            (recur (inc i) last-kind out)

            (newline-code? c)
            (if (contains? terminator-predecessors last-kind)
              (recur (inc i) :synthetic-semicolon
                     (conj! out (token :synthetic-semicolon source i (inc i))))
              (recur (inc i) :newline out))

            (and (= c 47) (starts-with-at? source i "//"))
            (recur (long (line-comment-end source i)) last-kind out)

            (and (= c 47) (starts-with-at? source i "/*"))
            (if-let [end (block-comment-end source i)]
              (recur (long end) last-kind out)
              (persistent!
               (conj! out (error-token :unterminated-comment source i (+ i 2)
                                       "Unterminated multiline comment"))))

            (word-char? c)
            (let [end (word-end source i)
                  text (subs source i end)
                  kind (if (contains? keywords text) :keyword :identifier)]
              (recur (long end) kind (conj! out {:kind kind :text text :start i :end end})))

            (or (= c 34) (= c 39))
            (if-let [end (string-end source i)]
              (recur (long end) :string (conj! out (token :string source i end)))
              (persistent!
               (conj! out (error-token :unterminated-string source i (inc i)
                                       "Unterminated string"))))

            (starts-with-at? source i "...")
            (recur (+ i 3) :ellipsis (conj! out (token :ellipsis source i (+ i 3))))

            (and (< (inc i) n) (contains? two-character-tokens (subs source i (+ i 2))))
            (let [kind (get two-character-tokens (subs source i (+ i 2)))]
              (recur (+ i 2) kind (conj! out (token kind source i (+ i 2)))))

            (contains? one-character-tokens c)
            (let [kind (get one-character-tokens c)]
              (recur (inc i) kind (conj! out (token kind source i (inc i)))))

            :else
            (let [end (+ i (code-point-width c))]
              (persistent!
               (conj! out (error-token :invalid-character source i end
                                       "Unrecognized character"))))))))))

(defn- apply-use-flags
  "SpiceDB's FlaggableLexer: before the first `definition` or `caveat` keyword,
   an identifier following the identifier `use` that names a flag enables it;
   enabled flags turn their words into keywords from that token on."
  [tokens]
  (loop [i 0
         seen-definition? false
         after-use? false
         enabled #{}
         out (transient [])]
    (if (= i (count tokens))
      (persistent! out)
      (let [{:keys [kind text] :as t} (nth tokens i)
            identifier? (= :identifier kind)
            enable? (and identifier? (not seen-definition?) after-use?
                         (contains? flag-keywords text))
            enabled (if enable? (conj enabled text) enabled)
            after-use? (if (and identifier? (not seen-definition?))
                         (and (not after-use?) (= "use" text))
                         after-use?)
            seen-definition? (or seen-definition?
                                 (and (= :keyword kind)
                                      (contains? #{"definition" "caveat"} text)))
            t (if (and identifier?
                       (some #(contains? (get flag-keywords %) text) enabled))
                (assoc t :kind :keyword)
                t)]
        (recur (inc i) seen-definition? after-use? enabled (conj! out t))))))

(defn lex
  "Tokenizes schema source. See the namespace docstring."
  [source]
  (apply-use-flags (raw-tokens source)))

(defn line-column
  "1-based line and column of string index `offset`. `\\r\\n`, `\\n` and `\\r`
   each end a line, as SpiceDB counts them."
  [source offset]
  (let [offset (min (max 0 offset) (count source))]
    (loop [i 0 line 1 line-start 0]
      (if (>= i offset)
        [line (inc (- offset line-start))]
        (let [c (code-point-at source i)]
          (cond
            (and (= c 13) (< (inc i) (count source))
                 (= 10 (code-point-at source (inc i))))
            (if (>= (inc i) offset)
              [line (inc (- offset line-start))]
              (recur (+ i 2) (inc line) (+ i 2)))
            (newline-code? c) (recur (inc i) (inc line) (inc i))
            :else (recur (inc i) line line-start)))))))

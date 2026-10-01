(ns eacl.caveats.plan
  "Portable bounded parser and static checker for EACL CEL profile 2: profile 1
   plus the `exists` and `all` comprehension macros over lists and map keys.

   Plans are vectors. A comprehension `xs.exists(x, p)` is
   `[:exists xs \"x\" p]`; inside `p`, `x` is `[:var \"x\"]`, never a parameter.
   The parser resolves every name to the innermost enclosing comprehension
   variable of that name, or else to a parameter, so shadowing needs no rule
   at evaluation."
  (:require [clojure.string :as str]
            [eacl.caveats.values :as values]
            [eacl.exact-integer :as integer]))

(defn- code-at [s i]
  #?(:clj (int (.charAt ^String s i)) :cljs (.charCodeAt s i)))

(defn- character [code]
  #?(:clj (str (char code)) :cljs (js/String.fromCharCode code)))

(defn- ascii-letter? [c] (or (<= 65 c 90) (<= 97 c 122) (= 95 c)))
(defn- digit? [c] (<= 48 c 57))
(defn- identifier-part? [c] (or (ascii-letter? c) (digit? c)))

(defn- scan-end [source start predicate]
  (loop [i start]
    (if (and (< i (count source)) (predicate (code-at source i))) (recur (inc i)) i)))

(defn- fail! [reason offset]
  (values/error! reason {:offset offset}))

(defn- admit-string-literal!
  "Admits a source string value under the bounds every evaluation re-checks
   (`validate-plan`), failing at `offset`. A `.name` member is the string
   literal \"name\" and is admitted here too, so an admitted definition never
   faults on every evaluation."
  [value offset]
  (try (values/encode-context [["literal" :string]] {"literal" value})
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (fail! (if (= :resource-limit (:reason (ex-data e))) :resource-limit :literal-type) offset)))
  value)

(defn- read-string-token [source start]
  (loop [i (inc start) pieces []]
    (when (>= i (count source)) (fail! :syntax-error start))
    (let [c (code-at source i)]
      (cond
        (= c 34) (let [value (admit-string-literal! (apply str pieces) start)]
                   [{:kind :literal :type :string :value value :offset start} (inc i)])
        (< c 32) (fail! :syntax-error i)
        (= c 92)
        (do
          (when (>= (inc i) (count source)) (fail! :syntax-error i))
          (let [escaped (code-at source (inc i))]
            (if (= 117 escaped)
              (do
                (when (> (+ i 6) (count source)) (fail! :syntax-error i))
                (let [hex (subs source (+ i 2) (+ i 6))]
                  (when-not (re-matches #"[0-9a-fA-F]{4}" hex) (fail! :syntax-error i))
                  (recur (+ i 6) (conj pieces (character #?(:clj (Integer/parseInt hex 16)
                                                           :cljs (js/parseInt hex 16)))))))
              (let [value (case escaped 34 "\"" 92 "\\" 47 "/" 98 "\b" 102 "\f"
                                110 "\n" 114 "\r" 116 "\t" nil)]
                (when-not value (fail! :syntax-error i))
                (recur (+ i 2) (conj pieces value))))))
        :else (recur (inc i) (conj pieces (subs source i (inc i))))))))

(defn tokenize [source]
  (when-not (string? source) (fail! :syntax-error 0))
  (when (> (count source) (:source-utf8-bytes values/limits)) (fail! :resource-limit 0))
  (let [size (try (values/utf8-size source)
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ (fail! :syntax-error 0)))]
    (when (> size (:source-utf8-bytes values/limits)) (fail! :resource-limit 0)))
  (loop [i 0 tokens [] depth 0]
    (if (= i (count source))
      (conj tokens {:kind :eof :value "" :offset i})
      (let [c (code-at source i)
            pair (when (< (inc i) (count source)) (subs source i (+ i 2)))]
        (cond
          (#{9 10 13 32} c) (recur (inc i) tokens depth)
          (= "//" pair) (recur (scan-end source (+ i 2) #(not (#{10 13} %))) tokens depth)
          :else
          (let [[token next-i]
                (cond
                  (= c 34) (read-string-token source i)
                  (ascii-letter? c)
                  (let [end (scan-end source i identifier-part?) word (subs source i end)]
                    [(case word
                       "true" {:kind :literal :type :bool :value true :offset i}
                       "false" {:kind :literal :type :bool :value false :offset i}
                       "in" {:kind :operator :value word :offset i}
                       {:kind :name :value word :offset i}) end])
                  (or (digit? c) (and (= c 45) (< (inc i) (count source)) (digit? (code-at source (inc i)))))
                  (let [digits-start (if (= c 45) (inc i) i)
                        end (scan-end source digits-start digit?) text (subs source i end)]
                    (when (or (> (- end digits-start) 16)
                              (and (> (- end digits-start) 1) (= 48 (code-at source digits-start))))
                      (fail! :literal-type i))
                    (let [n #?(:clj (Long/parseLong text) :cljs (js/parseInt text 10))]
                      (when-not (integer/exact? n) (fail! :literal-type i))
                      [{:kind :literal :type :int :value n :offset i} end]))
                  (contains? #{"&&" "||" "==" "!=" "<=" ">="} pair)
                  [{:kind :operator :value pair :offset i} (+ i 2)]
                  (contains? #{33 60 62 40 41 91 93 44 46} c)
                  [{:kind :operator :value (subs source i (inc i)) :offset i} (inc i)]
                  :else (fail! :unsupported-operation i))
                new-depth (cond (not= :operator (:kind token)) depth
                                (#{"(" "["} (:value token)) (inc depth)
                                (#{")" "]"} (:value token)) (dec depth)
                                :else depth)]
            (when (or (>= (count tokens) (:tokens values/limits))
                      (> new-depth (:source-group-depth values/limits)))
              (fail! :resource-limit i))
            (when (neg? new-depth) (fail! :syntax-error i))
            (recur next-i (conj tokens token) new-depth)))))))

(defn- counted
  "Attaches node count and depth to a plan vector, counting only its child
   plans; a comprehension's variable name is not a node."
  [plan children offset]
  (let [nodes (inc (reduce + 0 (map #(or (:nodes (meta %)) 1) children)))
        depth (inc (reduce max 0 (map #(or (:depth (meta %)) 1) children)))]
    (when (or (> nodes (:plan-nodes values/limits)) (> depth (:plan-depth values/limits)))
      (fail! :resource-limit offset))
    (with-meta plan {:nodes nodes :depth depth :offset offset})))

(defn- node [op children offset]
  (counted (into [op] children) (if (#{:literal :param :var} op) [] children) offset))

(def comprehension-ops #{:exists :all})

(def ^:private macros {"exists" :exists "all" :all})

(def ^:private binary-operators
  {"||" [1 :or] "&&" [2 :and] "==" [3 :eq] "!=" [3 :ne]
   "<" [3 :lt] "<=" [3 :le] ">" [3 :gt] ">=" [3 :ge] "in" [3 :in]})

(defn- parse-tokens [tokens]
  (let [position (volatile! 0)
        ;; Comprehension variables in scope, innermost first.
        scope (volatile! ())]
    (letfn [(current [] (nth tokens (min @position (dec (count tokens)))))
            (take-token [] (let [t (current)] (vswap! position inc) t))
            (accept [s] (when (and (= :operator (:kind (current))) (= s (:value (current)))) (take-token)))
            (expect [s] (or (accept s) (fail! :syntax-error (:offset (current)))))
            (identifier [value offset]
              (cond
                (some #{value} @scope) (node :var [value] offset)
                ;; Inside a comprehension, cel-go reads `__result__` as the
                ;; macro's accumulator, not as this parameter.
                (and (seq @scope) (= "__result__" value)) (fail! :unsupported-operation offset)
                :else (node :param [value] offset)))
            (comprehension [op range offset]
              ;; `range.op(variable, predicate)`, with the opening parenthesis read.
              (let [variable (take-token)]
                (when-not (and (= :name (:kind variable)) (values/variable-name? (:value variable)))
                  (fail! :syntax-error (:offset variable)))
                (expect ",")
                (vswap! scope conj (:value variable))
                (let [predicate (expression 1)]
                  (vswap! scope pop)
                  (expect ")")
                  (counted [op range (:value variable) predicate] [range predicate] offset))))
            (primary []
              (let [{:keys [kind type value offset]} (take-token)
                    base (cond
                           (= :literal kind) (node :literal [type value] offset)
                           (= :name kind) (identifier value offset)
                           (= "(" value) (let [p (expression 1)] (expect ")") p)
                           (= "!" value) (fail! :unsupported-operation offset)
                           :else (fail! :syntax-error offset))]
                (loop [p base]
                  (cond
                    (accept "[") (let [key (expression 1)] (expect "]") (recur (node :index [p key] offset)))
                    (accept ".")
                    (let [member (take-token)]
                      (when-not (= :name (:kind member)) (fail! :syntax-error (:offset member)))
                      (if (accept "(")
                        (if-let [macro (get macros (:value member))]
                          (recur (comprehension macro p offset))
                          (let [op (get {"contains" :contains "startsWith" :starts-with "endsWith" :ends-with} (:value member))]
                            (when-not op (fail! :unsupported-operation (:offset member)))
                            (let [arg (expression 1)] (expect ")") (recur (node op [p arg] offset)))))
                        (recur (node :index [p (node :literal [:string (admit-string-literal! (:value member) (:offset member))]
                                                     (:offset member))] offset))))
                    :else p))))
            (unary []
              (if-let [bang (accept "!")] (node :not [(primary)] (:offset bang)) (primary)))
            (expression [minimum]
              (loop [left (unary)]
                (let [token (current) [precedence op] (when (= :operator (:kind token))
                                                       (get binary-operators (:value token)))]
                  (if (and precedence (>= precedence minimum))
                    (do (take-token) (recur (node op [left (expression (inc precedence))] (:offset token))))
                    left))))]
      (let [plan (expression 1)]
        (when-not (= :eof (:kind (current))) (fail! :unsupported-operation (:offset (current))))
        plan))))

(defn item-type
  "The type of a comprehension variable over a range of this type: a list's
   element type or a map's key type (string); nil for any other type."
  [range-type]
  (when (vector? range-type)
    (case (first range-type) :list (second range-type) :map :string nil)))

(defn node-type
  "The static type of a plan node. `variable-types` maps each comprehension
   variable in scope to its type."
  ([parameter-types plan] (node-type parameter-types {} plan))
  ([parameter-types variable-types [op & args :as plan]]
   (let [offset (or (:offset (meta plan)) 0)
         wrong #(fail! :unsupported-overload offset)]
     (case op
       :literal (first args)
       :param (or (get parameter-types (first args)) (fail! :unknown-parameter offset))
       :var (or (get variable-types (first args)) (fail! :malformed-plan offset))
       (:exists :all)
       (let [[range variable predicate] args
             variable-type (item-type (node-type parameter-types variable-types range))]
         (when-not variable-type (wrong))
         (if (= :bool (node-type parameter-types (assoc variable-types variable variable-type) predicate))
           :bool
           (wrong)))
       (let [types (mapv #(node-type parameter-types variable-types %) args) [a b] types]
         (case op
           :not (if (= [:bool] types) :bool (wrong))
           (:and :or) (if (= [:bool :bool] types) :bool (wrong))
           (:eq :ne) (if (and (= a b) (contains? values/scalar-types a)) :bool (wrong))
           (:lt :le :gt :ge) (if (and (= a b) (#{:int :timestamp} a)) :bool (wrong))
           (:contains :starts-with :ends-with) (if (= [:string :string] types) :bool (wrong))
           :in (if (or (= b [:list a]) (and (= a :string) (vector? b) (= :map (first b)))) :bool (wrong))
           :index (if (and (vector? a) (= :map (first a)) (= b :string)) (nth a 2) (wrong))
           (fail! :unsupported-operation offset)))))))

(defn validate-plan
  "Checks a portable Boolean plan, including typed values in partial residuals.
   Source-level container literals remain excluded by the parser. Every
   `[:var v]` must be bound by an enclosing comprehension, and no parameter
   named `__result__` may appear inside one, as the parser requires."
  [parameters plan]
  (let [parameters (values/normalize-parameters parameters)
        visited (volatile! 0)]
    (letfn [(visit [p depth scope]
              (when (or (> depth (:plan-depth values/limits))
                        (> (vswap! visited inc) (:plan-nodes values/limits)))
                (fail! :resource-limit 0))
              (when-not (and (vector? p) (<= 2 (count p) 4)) (fail! :malformed-plan 0))
              (let [[op a b c] p]
                (case op
                  :literal (do (when-not (= 3 (count p)) (fail! :malformed-plan 0))
                               (values/normalize-value a b))
                  :param (when-not (and (= 2 (count p)) (values/parameter-name? a)
                                        (not (and (seq scope) (= "__result__" a))))
                           (fail! :malformed-plan 0))
                  :var (when-not (and (= 2 (count p)) (contains? scope a))
                         (fail! :malformed-plan 0))
                  (:exists :all)
                  (do (when-not (and (= 4 (count p)) (values/variable-name? b)) (fail! :malformed-plan 0))
                      (visit a (inc depth) scope) (visit c (inc depth) (conj scope b)))
                  :not (do (when-not (= 2 (count p)) (fail! :malformed-plan 0))
                           (visit a (inc depth) scope))
                  (:and :or :eq :ne :lt :le :gt :ge :in :index :contains :starts-with :ends-with)
                  (do (when-not (= 3 (count p)) (fail! :malformed-plan 0))
                      (visit a (inc depth) scope) (visit b (inc depth) scope))
                  (fail! :unsupported-operation 0))))]
      (visit plan 1 #{})
      (when-not (= :bool (node-type (into {} parameters) plan)) (fail! :non-boolean-root 0))
      plan)))

(defn required-profile
  "The lowest profile that admits a plan: profile 2 when it has a
   comprehension, profile 1 otherwise."
  [[op & args]]
  (if (or (contains? comprehension-ops op)
          (and (not (#{:literal :param :var} op))
               (some #(= (second values/definition-profiles) (required-profile %)) args)))
    (second values/definition-profiles)
    (first values/definition-profiles)))

;; Substitution can repeat a bounded context value at every plan node. Wire
;; limits are derived from those existing bounds, including envelope overhead.
(def ^:private wire-options
  {:maximum-size (* (:plan-nodes values/limits) (+ 256 (:context-utf8-bytes values/limits)))
   :maximum-entries (* (:plan-nodes values/limits) (+ 8 (:context-total-entries values/limits)))
   :maximum-depth (+ 8 (:plan-depth values/limits))})

(defn- transform-literals [f [op & args]]
  (case op
    :literal [:literal (first args) (f (first args) (second args))]
    (:param :var) [op (first args)]
    (:exists :all) (let [[range variable predicate] args]
                     [op (transform-literals f range) variable (transform-literals f predicate)])
    (into [op] (map #(transform-literals f %) args))))

(defn encode-plan [parameters plan]
  (validate-plan parameters plan)
  (values/encode-bounded
    [:eacl.caveat/plan values/format-version values/profile-id
     (values/normalize-parameters parameters) (transform-literals values/tag-value plan)]
    wire-options))

(defn decode-plan [payload]
  (let [v (values/decode-bounded payload wire-options)]
    (when-not (and (vector? v) (= 5 (count v))
                  (= [:eacl.caveat/plan values/format-version values/profile-id] (subvec v 0 3)))
      (fail! :malformed-payload 0))
    (let [parameters (values/normalize-parameters (nth v 3))
          ;; Bound the raw wire shape before recursively decoding any literal.
          wire (nth v 4)
          visited (volatile! 0)]
      (letfn [(read-node [p depth]
                (when (or (> depth (:plan-depth values/limits))
                          (> (vswap! visited inc) (:plan-nodes values/limits)))
                  (fail! :resource-limit 0))
                (when-not (and (vector? p) (<= 2 (count p) 4)) (fail! :malformed-plan 0))
                (let [[op a b c] p]
                  (case op
                    :literal (do (when-not (= 3 (count p)) (fail! :malformed-plan 0))
                                 [:literal a (values/untag-value a b)])
                    (:param :var) p
                    (:exists :all) (do (when-not (= 4 (count p)) (fail! :malformed-plan 0))
                                       [op (read-node a (inc depth)) b (read-node c (inc depth))])
                    (into [op] (map #(read-node % (inc depth)) (rest p))))))]
        (let [plan (validate-plan parameters (read-node wire 1))]
          (when-not (= payload (encode-plan parameters plan)) (fail! :noncanonical-payload 0))
          {:parameters parameters :plan plan})))))

(defn compile-plan
  "Parses and type-checks source. `:profile` is the lowest profile that
   admits it, which a stored definition records."
  [source parameters]
  (let [parameters (values/normalize-parameters parameters)
        source (if (string? source) (str/replace source #"\r\n|\r" "\n") source)
        plan (parse-tokens (tokenize source))
        result-type (node-type (into {} parameters) plan)]
    (when-not (= :bool result-type) (fail! :non-boolean-root 0))
    {:profile (required-profile plan) :parameters parameters :source source :plan plan
     :result-type result-type :nodes (:nodes (meta plan)) :depth (:depth (meta plan))}))

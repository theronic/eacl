(ns eacl.caveats.plan
  "Portable bounded parser and static checker for EACL CEL profile 1."
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

(defn- read-string-token [source start]
  (loop [i (inc start) pieces []]
    (when (>= i (count source)) (fail! :syntax-error start))
    (let [c (code-at source i)]
      (cond
        (= c 34) (let [value (apply str pieces)]
                   (try (values/encode-context [["literal" :string]] {"literal" value})
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                          (fail! (if (= :resource-limit (:reason (ex-data e))) :resource-limit :literal-type) start)))
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

(defn- node [op children offset]
  (let [leaf? (#{:literal :param} op)
        nodes (if leaf? 1 (inc (reduce + 0 (map #(or (:nodes (meta %)) 1) children))))
        depth (if leaf? 1 (inc (reduce max 0 (map #(or (:depth (meta %)) 1) children))))]
    (when (or (> nodes (:plan-nodes values/limits)) (> depth (:plan-depth values/limits)))
      (fail! :resource-limit offset))
    (with-meta (into [op] children) {:nodes nodes :depth depth :offset offset})))

(def ^:private binary-operators
  {"||" [1 :or] "&&" [2 :and] "==" [3 :eq] "!=" [3 :ne]
   "<" [3 :lt] "<=" [3 :le] ">" [3 :gt] ">=" [3 :ge] "in" [3 :in]})

(defn- parse-tokens [tokens]
  (let [position (volatile! 0)]
    (letfn [(current [] (nth tokens (min @position (dec (count tokens)))))
            (take-token [] (let [t (current)] (vswap! position inc) t))
            (accept [s] (when (and (= :operator (:kind (current))) (= s (:value (current)))) (take-token)))
            (expect [s] (or (accept s) (fail! :syntax-error (:offset (current)))))
            (primary []
              (let [{:keys [kind type value offset]} (take-token)
                    base (cond
                           (= :literal kind) (node :literal [type value] offset)
                           (= :name kind) (node :param [value] offset)
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
                        (let [op (get {"contains" :contains "startsWith" :starts-with "endsWith" :ends-with} (:value member))]
                          (when-not op (fail! :unsupported-operation (:offset member)))
                          (let [arg (expression 1)] (expect ")") (recur (node op [p arg] offset))))
                        (recur (node :index [p (node :literal [:string (:value member)] (:offset member))] offset))))
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

(defn node-type [parameter-types [op & args :as plan]]
  (let [offset (or (:offset (meta plan)) 0)
        wrong #(fail! :unsupported-overload offset)]
    (case op
      :literal (first args)
      :param (or (get parameter-types (first args)) (fail! :unknown-parameter offset))
      (let [types (mapv #(node-type parameter-types %) args) [a b] types]
        (case op
          :not (if (= [:bool] types) :bool (wrong))
          (:and :or) (if (= [:bool :bool] types) :bool (wrong))
          (:eq :ne) (if (and (= a b) (contains? values/scalar-types a)) :bool (wrong))
          (:lt :le :gt :ge) (if (and (= a b) (#{:int :timestamp} a)) :bool (wrong))
          (:contains :starts-with :ends-with) (if (= [:string :string] types) :bool (wrong))
          :in (if (or (= b [:list a]) (and (= a :string) (vector? b) (= :map (first b)))) :bool (wrong))
          :index (if (and (vector? a) (= :map (first a)) (= b :string)) (nth a 2) (wrong))
          (fail! :unsupported-operation offset))))))

(defn validate-plan
  "Checks a portable Boolean plan, including typed values in partial residuals.
   Source-level container literals remain excluded by the parser."
  [parameters plan]
  (let [parameters (values/normalize-parameters parameters)
        visited (volatile! 0)]
    (letfn [(visit [p depth]
              (when (or (> depth (:plan-depth values/limits))
                        (> (vswap! visited inc) (:plan-nodes values/limits)))
                (fail! :resource-limit 0))
              (when-not (and (vector? p) (<= 2 (count p) 3)) (fail! :malformed-plan 0))
              (let [[op a b] p]
                (case op
                  :literal (do (when-not (= 3 (count p)) (fail! :malformed-plan 0))
                               (values/normalize-value a b))
                  :param (when-not (and (= 2 (count p)) (values/parameter-name? a))
                           (fail! :malformed-plan 0))
                  :not (do (when-not (= 2 (count p)) (fail! :malformed-plan 0))
                           (visit a (inc depth)))
                  (:and :or :eq :ne :lt :le :gt :ge :in :index :contains :starts-with :ends-with)
                  (do (when-not (= 3 (count p)) (fail! :malformed-plan 0))
                      (visit a (inc depth)) (visit b (inc depth)))
                  (fail! :unsupported-operation 0))))]
      (visit plan 1)
      (when-not (= :bool (node-type (into {} parameters) plan)) (fail! :non-boolean-root 0))
      plan)))

;; Substitution can repeat a bounded context value at every plan node. Wire
;; limits are derived from those existing bounds, including envelope overhead.
(def ^:private wire-options
  {:maximum-size (* (:plan-nodes values/limits) (+ 256 (:context-utf8-bytes values/limits)))
   :maximum-entries (* (:plan-nodes values/limits) (+ 8 (:context-total-entries values/limits)))
   :maximum-depth (+ 8 (:plan-depth values/limits))})

(defn- transform-literals [f [op & args]]
  (case op
    :literal [:literal (first args) (f (first args) (second args))]
    :param [:param (first args)]
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
                (when-not (and (vector? p) (<= 2 (count p) 3)) (fail! :malformed-plan 0))
                (let [[op a b] p]
                  (case op
                    :literal (do (when-not (= 3 (count p)) (fail! :malformed-plan 0))
                                 [:literal a (values/untag-value a b)])
                    :param p
                    (into [op] (map #(read-node % (inc depth)) (rest p))))))]
        (let [plan (validate-plan parameters (read-node wire 1))]
          (when-not (= payload (encode-plan parameters plan)) (fail! :noncanonical-payload 0))
          {:parameters parameters :plan plan})))))

(defn compile-plan [source parameters]
  (let [parameters (values/normalize-parameters parameters)
        source (if (string? source) (str/replace source #"\r\n|\r" "\n") source)
        plan (parse-tokens (tokenize source))
        result-type (node-type (into {} parameters) plan)]
    (when-not (= :bool result-type) (fail! :non-boolean-root 0))
    {:profile values/profile-id :parameters parameters :source source :plan plan
     :result-type result-type :nodes (:nodes (meta plan)) :depth (:depth (meta plan))}))

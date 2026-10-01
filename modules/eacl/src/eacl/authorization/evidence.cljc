(ns eacl.authorization.evidence
  "Bounded conditional permission evidence and temporal witness certificates.
   Timeless definite values are plain booleans. Only qualified/conditional
   values allocate evidence; no evaluator or database is invoked here.

   A value is a reduced ordered decision diagram over residual Caveat atoms
   whose terminals are Kleene's three truth values: `true`, `false`, and a
   fault `[:fault reasons]` (\"this subterm faulted; its value is unknown\").
   Composition is strong-Kleene and pointwise per completion of the atoms:
   a definite absorber decides regardless of a faulting operand (`true`
   absorbs a fault in a union, `false` in an intersection), a fault never
   absorbs, and two faults unite their reasons. The value is therefore a
   function of its operands alone, independent of evaluation order. A value
   with any fault terminal classifies as an evaluation failure."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [eacl.caveats.values :as values]
            [eacl.request.counters :as counters]))

(def format-version 1)
(def limits {:nodes 256 :depth 32 :work 65536 :missing-fields 128 :faults 32})
(def ^:private wire-limits
  {:maximum-size 4194304 :maximum-entries 16384 :maximum-depth 80})
(def ^:private atom-limits
  {:maximum-size 4194304 :maximum-entries 16384 :maximum-depth 40})

(defrecord Evidence [value valid-until-ms complete?])

(defn error! [reason]
  (throw (ex-info "Invalid or excessive authorization evidence."
                  {:type :eacl.authorization/invalid-evidence
                   :eacl/error :eacl.authorization/invalid-evidence :reason reason})))

(defn value [e] (if (boolean? e) e (:value e)))
(defn valid-until [e] (when-not (boolean? e) (:valid-until-ms e)))
(defn complete? [e] (if (boolean? e) true (:complete? e)))

(defn- fault-terminal?
  "A fault terminal `[:fault reasons]`. Decision nodes start with their atom
   vector, so a leading keyword identifies a fault without comparing it to
   a keyword literal (ClojureScript does not intern keywords)."
  [v]
  (and (vector? v) (keyword? (nth v 0 nil))))

(defn- node? [v] (and (vector? v) (vector? (nth v 0 nil))))

(defn- faulted-value?
  "True when some completion of the residual atoms reaches a fault terminal.
   Total: anything that is not a decision node or a fault terminal (a
   Boolean, or a non-evidence value such as nil) has no fault."
  [v]
  (cond
    (fault-terminal? v) true
    (not (node? v)) false
    :else
    ;; The stack holds `false` terminals too: test emptiness, not the peek.
    (loop [pending [(nth v 1) (nth v 2)]]
      (if (zero? (count pending))
        false
        (let [node (peek pending)]
          (cond
            (fault-terminal? node) true
            (node? node) (recur (conj (pop pending) (nth node 1) (nth node 2)))
            :else (recur (pop pending))))))))

(defn fault?
  "True when the value faults in some completion: an evaluation failure.
   Definite values never fault; a conditional value with a fault terminal
   fails as a whole, because its residual cannot be reported soundly."
  [e]
  (faulted-value? (value e)))

(defn has? [e] (true? (value e)))
(defn no? [e] (false? (value e)))

(defn permissionship [e]
  (cond (has? e) :has-permission
        (no? e) :no-permission
        (fault? e) :evaluation-failure
        :else :conditional-permission))

(defn- wrap [v end complete]
  (if (and (boolean? v) (nil? end) complete) v (->Evidence v end complete)))

(defn with-certificate [e end complete]
  (when-not (or (boolean? e) (instance? Evidence e)) (error! :evidence-shape))
  (when-not (and (or (nil? end) (values/valid-time? end)) (boolean? complete))
    (error! :certificate))
  (wrap (value e) end complete))

(defn before? [time end] (or (nil? end) (< time end)))
(defn reusable? [e start time]
  (and (complete? e) (not (fault? e)) (<= start time) (before? time (valid-until e))))
(defn meet [a b] (cond (nil? a) b (nil? b) a :else (min a b)))
(defn later
  "The later of two deadlines; nil (forever) is the latest."
  [a b]
  (when (and (some? a) (some? b)) (max a b)))

(defn- missing-vector [missing]
  (when-not (and (coll? missing) (seq missing)
                (<= (count missing) (:missing-fields limits))
                (every? values/parameter-name? missing))
    (error! :missing-fields))
  (vec (sort (set missing))))

(defn conditional
  "Creates one residual atom. Identity is complete canonical data containing
   the Caveat/profile, parameter types, and already-bound residual plan; it
   must not be a digest alone. Missing fields are part of the atom identity."
  [identity missing]
  (when-not (and (vector? identity) (seq identity)) (error! :atom-identity))
  (let [missing (missing-vector missing)
        key (values/encode-bounded [identity missing] atom-limits)]
    (->Evidence [[key missing] false true] nil true)))

(defn fault
  "Preserves a sanitized typed failure. Exception messages and arbitrary
   backend data are deliberately excluded from reusable evidence."
  [type reason]
  (when-not (and (keyword? type) (keyword? reason)) (error! :fault-shape))
  (->Evidence [:fault [[type reason]]] nil true))

(defn- sorted-reasons [reasons]
  (let [reasons (vec (sort-by pr-str (set reasons)))]
    (when (> (count reasons) (:faults limits)) (error! :fault-limit))
    reasons))

(defn fault-reasons
  "The sorted union of the reasons of every fault terminal of `e`, or [].
   Reasons are diagnostic: they name the faulting edges that the decision
   depends on, but the set is not part of the authorization contract."
  [e]
  (let [v (value e)]
    (cond
      (fault-terminal? v) (nth v 1)
      (not (node? v)) []
      :else
      (loop [pending [v] reasons #{}]
        (if (zero? (count pending))
          (sorted-reasons reasons)
          (let [node (peek pending)]
            (cond
              (fault-terminal? node) (recur (pop pending) (into reasons (nth node 1)))
              (node? node) (recur (conj (pop pending) (nth node 1) (nth node 2)) reasons)
              :else (recur (pop pending) reasons))))))))

(defn throw-if-fault!
  "A consumed decision that faults in some completion cannot be published.
   Preserve only the bounded, sanitized fault reasons."
  [e]
  (when (fault? e)
    (throw (ex-info "Qualified page evaluation failed."
                    {:type :eacl.authorization/evaluation-failure
                     :eacl/error :eacl.authorization/evaluation-failure
                     :faults (fault-reasons e)})))
  e)

(defn- charge! [budget]
  (when (> (vswap! budget inc) (:work limits)) (error! :work-limit)))

(defn- branch [node atom high?]
  (if (and (node? node) (= atom (nth node 0)))
    (nth node (if high? 2 1)) node))

(defn- boolean-result [op a b]
  (case op :union (or a b) :intersection (and a b)
           :arrow (and a b) :exclusion (and a (not b))))

(defn- united-fault
  "Two fault terminals: one fault whose reasons are both reason sets."
  [a b]
  (if (= a b) a [:fault (sorted-reasons (set/union (set (nth a 1)) (set (nth b 1))))]))

(defn- terminal-result
  "Strong-Kleene connective on two terminals (`true`, `false`, or a fault),
   with negation fixing a fault. A definite absorber decides; otherwise a
   fault operand faults the result, two faults uniting their reasons."
  [op a b]
  (case op
    :union
    (cond (or (true? a) (true? b)) true
          (and (fault-terminal? a) (fault-terminal? b)) (united-fault a b)
          (fault-terminal? a) a
          (fault-terminal? b) b
          :else false)
    (:intersection :arrow)
    (cond (or (false? a) (false? b)) false
          (and (fault-terminal? a) (fault-terminal? b)) (united-fault a b)
          (fault-terminal? a) a
          (fault-terminal? b) b
          :else true)
    :exclusion
    ;; a - b = a and (not b). Remaining after the absorbers: a in {true,
    ;; fault}, b in {false, fault}.
    (cond (or (false? a) (true? b)) false
          (and (fault-terminal? a) (fault-terminal? b)) (united-fault a b)
          (fault-terminal? a) a
          (fault-terminal? b) b
          :else true)))

(defn- apply-node [op a b depth budget]
  (charge! budget)
  (when (> depth (:depth limits)) (error! :depth-limit))
  (let [a-node? (node? a) b-node? (node? b)]
  (cond
      (and (not a-node?) (not b-node?)) (terminal-result op a b)
      ;; Union and intersection are idempotent. `x - x` is false only
      ;; without fault terminals: a fault minus itself is still a fault.
      (and (= a b) (not= op :exclusion)) a
      (and (= a b) (not (faulted-value? a))) false
    (and (= op :union) (or (true? a) (true? b))) true
    (and (#{:intersection :arrow} op) (or (false? a) (false? b))) false
    (and (= op :exclusion) (or (false? a) (true? b))) false
    :else
      (let [aa (when a-node? (nth a 0)) ba (when b-node? (nth b 0))
          atom (cond (nil? aa) ba (nil? ba) aa
                     (neg? (compare (first aa) (first ba))) aa :else ba)
          low (apply-node op (branch a atom false) (branch b atom false) (inc depth) budget)
          high (apply-node op (branch a atom true) (branch b atom true) (inc depth) budget)]
        (if (= low high) low [atom low high])))))

(defn- check-node-budget! [node]
  (loop [pending [[node 0]] n 0]
    (when-let [[v depth] (peek pending)]
      (when (or (>= n (:nodes limits)) (> depth (:depth limits))) (error! :node-limit))
      (recur (if (node? v)
               (conj (pop pending) [(nth v 1) (inc depth)] [(nth v 2) (inc depth)])
               (pop pending))
             (inc n)))))

(defn- combine-value [op a b]
  (let [va (value a) vb (value b)]
    (if (and (boolean? va) (boolean? vb))
      (boolean-result op va vb)
      (let [result (apply-node op va vb 0 (volatile! 0))]
      (check-node-budget! result)
        result))))

(defn- decisive-left? [op a]
  (and (complete? a) (if (= op :union) (has? a) (no? a))))

(defn- decisive-right? [op b]
  (and (complete? b) (if (#{:union :exclusion} op) (has? b) (no? b))))

(defn combine
  "Composes exactly the evidence already demanded by an operator, pointwise
   with strong-Kleene connectives. A decisive complete witness (`true` in a
   union or a subtracted operand, `false` in an intersection or an arrow's
   via/target) decides the result whatever the other operand is, including
   a fault, and keeps its own deadline. When both operands are already
   decisive, either witness keeps the result decided, so it keeps the later
   deadline: the certificate does not depend on which operand an evaluator
   read first. Otherwise the result needs both child certificates. When
   composition erases a fault, the request's `:masked-faults` meter records
   it."
  [op a b]
  (when-not (contains? #{:union :intersection :exclusion :arrow} op) (error! :operator))
  (if (and (boolean? a) (boolean? b))
    (boolean-result op a b)
    (let [v (combine-value op a b)
          left? (decisive-left? op a)
          right? (decisive-right? op b)
          end (cond (and left? right?) (later (valid-until a) (valid-until b))
                    left? (valid-until a) right? (valid-until b)
                    :else (meet (valid-until a) (valid-until b)))
          complete (cond left? true right? true :else (and (complete? a) (complete? b)))]
      (when (and (or (fault? a) (fault? b)) (not (faulted-value? v)))
        (counters/add! :masked-faults))
      (wrap v end complete))))

(defn missing-fields
  "The sorted parameter names a conditional residual still needs. Definite
   and faulted values have none: a faulted value is never reported."
  [e]
  (if (or (boolean? (value e)) (fault? e))
    []
    (loop [pending [(value e)] missing #{}]
      (if (seq pending)
        (let [node (peek pending)]
          (if (node? node)
            (let [next-missing (into missing (second (first node)))]
              (when (> (count next-missing) (:missing-fields limits)) (error! :missing-fields))
              (recur (conj (pop pending) (nth node 1) (nth node 2)) next-missing))
            (recur (pop pending) missing)))
        (vec (sort missing))))))

(defn- validate-atom! [atom]
  (when-not (and (vector? atom) (= 2 (count atom))) (error! :atom-shape))
  (let [[key missing] atom]
  (when-not (and (string? key)
                (= missing (missing-vector missing)))
    (error! :atom-shape))
  (let [decoded (values/decode-bounded key atom-limits)]
    (when-not (and (vector? decoded) (= 2 (count decoded))
                  (vector? (first decoded)) (seq (first decoded))
                  (= missing (second decoded))
                  (= key (values/encode-bounded decoded atom-limits)))
      (error! :atom-identity)))
  atom))

(defn- validate-fault! [v]
  (let [reasons (nth v 1 nil)]
    (when-not (and (= 2 (count v)) (= :fault (nth v 0)) (vector? reasons) (seq reasons)
                    (<= (count reasons) (:faults limits))
                    (every? #(and (vector? %) (= 2 (count %)) (every? keyword? %)) reasons)
                    (= reasons (vec (sort-by pr-str (set reasons)))))
      (error! :fault-shape))))

(defn- validate-value!
  "Checks one canonical value: terminals are booleans or sanitized fault
   terminals, and every decision node is reduced and ordered."
  [v]
    (loop [pending [[v nil 0]] n 0]
      (when-let [[node previous depth] (peek pending)]
        (when (or (>= n (:nodes limits)) (> depth (:depth limits))) (error! :node-limit))
      (cond
        (boolean? node)
          (recur (pop pending) (inc n))

        (fault-terminal? node)
        (do (validate-fault! node) (recur (pop pending) (inc n)))

        :else
          (do
            (when-not (and (vector? node) (= 3 (count node))) (error! :node-shape))
            (let [[atom low high] node
                  key (first (validate-atom! atom))]
              (when (or (= low high) (and previous (not (neg? (compare previous key)))))
                (error! :noncanonical-node))
            (recur (conj (pop pending) [low key (inc depth)] [high key (inc depth)]) (inc n)))))))
  v)

(def ^:private true-wire
  (values/encode-bounded [:eacl.authorization/evidence format-version true nil true] wire-limits))
(def ^:private false-wire
  (values/encode-bounded [:eacl.authorization/evidence format-version false nil true] wire-limits))

(defn encode [e]
  (cond
    (true? e) true-wire
    (false? e) false-wire
    :else
    (do
      (when-not (or (boolean? e) (instance? Evidence e)) (error! :evidence-shape))
      (when-not (boolean? (value e)) (validate-value! (value e)))
      (with-certificate e (valid-until e) (complete? e))
      (if (boolean? (value e))
        ;; This closed scalar envelope has a fixed small wire bound. It is
        ;; byte-identical to the generic encoder without a BDD walk or a
        ;; reconstructed canonical collection for every witnessed edge.
        (str "[:eacl.authorization/evidence " format-version " " (value e) " "
             (if-some [end (valid-until e)] end "nil") " " (complete? e) "]")
        (do
          (missing-fields e)
          (values/encode-bounded [:eacl.authorization/evidence format-version (value e)
                                  (valid-until e) (complete? e)] wire-limits))))))

(def ^:private scalar-wire-prefix
  (str "[:eacl.authorization/evidence " format-version " "))

(defn- boolean-token [token]
  (case token "true" true "false" false nil))

(defn- decimal-token
  "The integer a token spells, or nil. Host leniency (a sign, leading zeros)
   is harmless: `decode` keeps a candidate only when it re-encodes to the
   same payload."
  [token]
  #?(:clj (try (Long/parseLong token) (catch NumberFormatException _ nil))
     :cljs (let [n (js/parseInt token 10)] (when-not (js/isNaN n) n))))

(defn- scalar-candidate
  "Reads a closed scalar envelope, `[:eacl.authorization/evidence 1 value end
   complete]`, without the generic reader. Nil for any other payload. The
   result is only a candidate: `decode` keeps it exactly when it re-encodes to
   the payload, the same canonicality check the generic read must pass."
  [payload]
  (when (and (string? payload)
             (str/starts-with? payload scalar-wire-prefix)
             (str/ends-with? payload "]"))
    (let [start (count scalar-wire-prefix)
          value-end (str/index-of payload " " start)
          end-end (when value-end (str/index-of payload " " (inc value-end)))]
      (when end-end
        (let [v (boolean-token (subs payload start value-end))
              end-token (subs payload (inc value-end) end-end)
              end (when-not (= "nil" end-token) (decimal-token end-token))
              complete (boolean-token (subs payload (inc end-end) (dec (count payload))))]
          (when (and (some? v) (some? complete) (or (some? end) (= "nil" end-token)))
            (try (with-certificate (->Evidence v nil true) end complete)
                 (catch #?(:clj Throwable :cljs :default) _ nil))))))))

(defn- decode-generic [payload]
  (let [wire (values/decode-bounded payload wire-limits)]
    (when-not (and (vector? wire) (= 5 (count wire))
                   (= :eacl.authorization/evidence (first wire)) (= format-version (second wire)))
      (error! :wire-shape))
    (let [[_ _ v end complete] wire
          result (with-certificate (->Evidence (validate-value! v) nil true) end complete)]
      (when-not (= payload (encode result)) (error! :noncanonical-wire))
      result)))

(defn decode [payload]
  (cond
    (= true-wire payload) true
    (= false-wire payload) false
    :else
    ;; Certified definite answers are the common resident values, and every
    ;; reuse decodes one. Their closed envelope needs no generic reader: a
    ;; candidate that re-encodes to the payload is the generic read.
    (let [candidate (scalar-candidate payload)]
      (if (and (some? candidate) (= payload (encode candidate)))
        candidate
        (decode-generic payload)))))

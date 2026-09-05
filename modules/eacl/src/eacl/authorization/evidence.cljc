(ns eacl.authorization.evidence
  "Bounded conditional permission evidence and temporal witness certificates.
   Timeless definite values are plain booleans. Only qualified/conditional
   values allocate evidence; no evaluator or database is invoked here."
  (:require [clojure.set :as set]
            [eacl.caveats.values :as values]))

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
(defn fault? [e] (let [v (value e)] (and (vector? v) (= :fault (first v)))))
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

(defn- charge! [budget]
  (when (> (vswap! budget inc) (:work limits)) (error! :work-limit)))

(defn- branch [node atom high?]
  (if (and (vector? node) (= atom (first node)))
    (nth node (if high? 2 1)) node))

(defn- boolean-result [op a b]
  (case op :union (or a b) :intersection (and a b)
           :arrow (and a b) :exclusion (and a (not b))))

(defn- apply-node [op a b depth budget]
  (charge! budget)
  (when (> depth (:depth limits)) (error! :depth-limit))
  (cond
    (and (boolean? a) (boolean? b)) (boolean-result op a b)
    (= a b) (if (= op :exclusion) false a)
    (and (= op :union) (or (true? a) (true? b))) true
    (and (#{:intersection :arrow} op) (or (false? a) (false? b))) false
    (and (= op :exclusion) (or (false? a) (true? b))) false
    :else
    (let [aa (when (vector? a) (first a)) ba (when (vector? b) (first b))
          atom (cond (nil? aa) ba (nil? ba) aa
                     (neg? (compare (first aa) (first ba))) aa :else ba)
          low (apply-node op (branch a atom false) (branch b atom false) (inc depth) budget)
          high (apply-node op (branch a atom true) (branch b atom true) (inc depth) budget)]
      (if (= low high) low [atom low high]))))

(defn- check-node-budget! [node]
  (loop [pending [[node 0]] n 0]
    (when-let [[v depth] (peek pending)]
      (when (or (>= n (:nodes limits)) (> depth (:depth limits))) (error! :node-limit))
      (recur (if (boolean? v) (pop pending)
                 (conj (pop pending) [(nth v 1) (inc depth)] [(nth v 2) (inc depth)]))
             (inc n)))))

(defn- faults [e] (if (fault? e) (second (value e)) []))

(defn- combine-value [op a b]
  (if (or (fault? a) (fault? b))
    (let [reasons (vec (sort-by pr-str (set/union (set (faults a)) (set (faults b)))))]
      (when (> (count reasons) (:faults limits)) (error! :fault-limit))
      [:fault reasons])
    (let [result (apply-node op (value a) (value b) 0 (volatile! 0))]
      (check-node-budget! result)
      result)))

(defn combine
  "Composes exactly the evidence already demanded by an operator. A decisive
   complete witness can retain its own deadline; non-decisive and faulty
   outcomes need both child certificates. Faults precede Boolean absorbers."
  [op a b]
  (when-not (contains? #{:union :intersection :exclusion :arrow} op) (error! :operator))
  (if (and (boolean? a) (boolean? b))
    (boolean-result op a b)
    (let [v (combine-value op a b)
          faulted? (or (fault? a) (fault? b))
          left? (and (not faulted?) (complete? a) (if (= op :union) (has? a) (no? a)))
          right? (and (not faulted?) (complete? b) (if (#{:union :exclusion} op) (has? b) (no? b)))
          end (cond left? (valid-until a) right? (valid-until b)
                    :else (meet (valid-until a) (valid-until b)))
          complete (cond left? true right? true :else (and (complete? a) (complete? b)))]
      (wrap v end complete))))

(defn missing-fields [e]
  (if (or (boolean? (value e)) (fault? e))
    []
    (loop [pending [(value e)] missing #{}]
      (if (seq pending)
        (let [node (peek pending)]
          (if (boolean? node)
            (recur (pop pending) missing)
            (let [next-missing (into missing (second (first node)))]
              (when (> (count next-missing) (:missing-fields limits)) (error! :missing-fields))
              (recur (conj (pop pending) (nth node 1) (nth node 2)) next-missing))))
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

(defn- validate-value! [v]
  (if (and (vector? v) (= :fault (first v)))
    (let [reasons (second v)]
      (when-not (and (= 2 (count v)) (vector? reasons) (seq reasons)
                    (<= (count reasons) (:faults limits))
                    (every? #(and (vector? %) (= 2 (count %)) (every? keyword? %)) reasons)
                    (= reasons (vec (sort-by pr-str (set reasons)))))
        (error! :fault-shape)))
    (loop [pending [[v nil 0]] n 0]
      (when-let [[node previous depth] (peek pending)]
        (when (or (>= n (:nodes limits)) (> depth (:depth limits))) (error! :node-limit))
        (if (boolean? node)
          (recur (pop pending) (inc n))
          (do
            (when-not (and (vector? node) (= 3 (count node))) (error! :node-shape))
            (let [[atom low high] node
                  key (first (validate-atom! atom))]
              (when (or (= low high) (and previous (not (neg? (compare previous key)))))
                (error! :noncanonical-node))
              (recur (conj (pop pending) [low key (inc depth)] [high key (inc depth)]) (inc n))))))))
  v)

(defn encode [e]
  (when-not (or (boolean? e) (instance? Evidence e)) (error! :evidence-shape))
  (validate-value! (value e))
  (with-certificate e (valid-until e) (complete? e))
  (missing-fields e)
  (values/encode-bounded [:eacl.authorization/evidence format-version (value e)
                          (valid-until e) (complete? e)] wire-limits))

(defn decode [payload]
  (let [wire (values/decode-bounded payload wire-limits)]
    (when-not (and (vector? wire) (= 5 (count wire))
                  (= :eacl.authorization/evidence (first wire)) (= format-version (second wire)))
      (error! :wire-shape))
    (let [[_ _ v end complete] wire
          result (with-certificate (->Evidence (validate-value! v) nil true) end complete)]
      (when-not (= payload (encode result)) (error! :noncanonical-wire))
      result)))

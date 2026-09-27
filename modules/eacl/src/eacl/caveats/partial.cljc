(ns eacl.caveats.partial
  "Bounded portable partial evaluation of the admitted EACL CEL plan."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [eacl.caveats.plan :as plan]
            [eacl.caveats.values :as values]))

(defn- value-size [type value]
  (case type
    :string (values/utf8-size value)
    (:bool :int :timestamp) 1
    (if (= :list (first type))
      (reduce + 0 (map #(value-size (second type) %) value))
      (reduce-kv (fn [n k v] (+ n (values/utf8-size k) (value-size (nth type 2) v))) 0 value))))

(defn- maximum-size [type]
  (case type
    :string (:string-utf8-bytes values/limits)
    (:bool :int :timestamp) 1
    (let [item-type (if (= :list (first type)) (second type) (nth type 2))]
      (* (:container-entries values/limits)
         (+ (maximum-size item-type) (if (= :map (first type)) (:string-utf8-bytes values/limits) 0))))))

(defn- range-items
  "The values a comprehension binds: list items in order, or map keys in
   canonical order."
  [type value]
  (if (= :list (first type)) value (values/sorted-keys value)))

(defn- variable-types [variables] (update-vals variables :type))

(defn- operand-size [types context variables [op a b :as expression]]
  (cond
    (= :literal op) (value-size a b)
    (and (= :param op) (contains? context a)) (value-size (get types a) (get context a))
    (= :var op) (:size (get variables a))
    :else (maximum-size (plan/node-type types (variable-types variables) expression))))

(defn- known-range
  "A comprehension range's value when this request supplies it."
  [context [op a b]]
  (case op
    :literal {:value b}
    :param (when (contains? context a) {:value (get context a)})
    nil))

(defn- plan-nodes [[op & args]]
  (case op
    (:literal :param :var) 1
    (:exists :all) (+ 1 (plan-nodes (first args)) (plan-nodes (nth args 2)))
    (inc (reduce + 0 (map plan-nodes args)))))

(defn estimate-work
  "Conservative preflight cost, saturated at the profile limit plus one.
   Call only after plan and context admission. Includes both logical branches.

   A comprehension over a supplied range is charged for every element: its
   range once, then one unit plus the predicate's cost per element, with the
   variable at the range's largest element size. Nesting multiplies. An absent
   range is charged at its declared maximum size, like any absent operand, but
   is not iterated: its result is conditional and it only copies its predicate,
   one unit per node, into the residual. `variables` maps each comprehension
   variable in scope to its `{:type :size}`."
  ([types expression context] (estimate-work types expression context {}))
  ([types expression context variables]
   (let [[op a b c] expression
         saturated (inc (:work-units values/limits))]
     (case op
       (:literal :param :var) 1
       (:exists :all)
       (let [range-type (plan/node-type types (variable-types variables) a)
             item-type (plan/item-type range-type)
             supplied (known-range context a)
             items (when supplied (range-items range-type (:value supplied)))
             per-item (when (seq items)
                        (inc (estimate-work types c context
                                            (assoc variables b {:type item-type
                                                                :size (reduce max 0 (map #(value-size item-type %) items))}))))]
         (min saturated
              (+ 1 (estimate-work types a context variables) (operand-size types context variables a)
                 (cond per-item (* (count items) per-item)
                       supplied 0
                       :else (plan-nodes c)))))
       (let [operand-cost (case op
                            :contains (* (operand-size types context variables a) (operand-size types context variables b))
                            :in (+ (operand-size types context variables b)
                                   (* (if (= :list (first (plan/node-type types (variable-types variables) b)))
                                        (:container-entries values/limits) 1)
                                      (operand-size types context variables a)))
                            (:eq :ne :lt :le :gt :ge :index :starts-with :ends-with)
                            (+ (operand-size types context variables a) (operand-size types context variables b))
                            0)]
         (min saturated
              (+ 1 operand-cost (reduce + 0 (map #(estimate-work types % context variables) (rest expression))))))))))

(defn prepare-evaluation
  "Admits all supplied inputs before evaluation; bound context wins on merge.
   Returns portable inputs for either the partial or complete evaluator."
  [parameters expression request bound]
  (let [parameters (values/normalize-parameters parameters)
        expression (plan/validate-plan parameters expression)
        context (values/merge-context parameters request bound)
        types (into {} parameters)
        work (+ (estimate-work types expression context)
                (reduce-kv (fn [n k v] (+ n 1 (value-size (get types k) v))) 0 context))]
    (when (> work (:work-units values/limits)) (values/error! :resource-limit {:limit :work-units}))
    {:parameters parameters :types types :plan expression :context context :work work}))

(defn- known [type value] {:known value :type type})
(defn- known? [result] (contains? result :known))
(defn- residual [result]
  (if (known? result) [:literal (:type result) (:known result)] (:residual result)))

(defn- fault [results]
  (when-let [reason (first (sort (keep :error results)))] {:error reason}))

(defn- logical [op a b]
  (let [absorber (= :or op)
        matches? (fn [value result] (and (known? result) (= value (:known result))))]
    (cond
      (or (matches? absorber a) (matches? absorber b)) (known :bool absorber)
      (or (:error a) (:error b)) (fault [a b])
      (matches? (not absorber) a) b
      (matches? (not absorber) b) a
      :else {:missing (set/union (:missing a) (:missing b))
             :residual [op (residual a) (residual b)]})))

(defn- concrete [op a b]
  (let [x (:known a) y (:known b)
        ordinal #(if (= :timestamp (:type %)) (second (:known %)) (:known %))]
    (case op
      :not (known :bool (not x))
      :eq (known :bool (= x y))
      :ne (known :bool (not= x y))
      :lt (known :bool (< (ordinal a) (ordinal b)))
      :le (known :bool (<= (ordinal a) (ordinal b)))
      :gt (known :bool (> (ordinal a) (ordinal b)))
      :ge (known :bool (>= (ordinal a) (ordinal b)))
      :contains (known :bool (str/includes? x y))
      :starts-with (known :bool (str/starts-with? x y))
      :ends-with (known :bool (str/ends-with? x y))
      :in (known :bool (if (map? y) (contains? y x) (boolean (some #(= x %) y))))
      :index (if (contains? x y) (known (nth (:type a) 2) (get x y)) {:error :missing-map-key}))))

(defn- substitute
  "The plan with supplied parameters and bound variables replaced by literals.
   It evaluates nothing, so it raises no fault. A comprehension's own variable
   shadows any outer binding of that name."
  [types context variables [op a b c :as expression]]
  (case op
    :literal expression
    :param (if (contains? context a) [:literal (get types a) (get context a)] expression)
    :var (if-let [[type value] (get variables a)] [:literal type value] expression)
    (:exists :all) [op (substitute types context variables a) b
                    (substitute types context (dissoc variables b) c)]
    (into [op] (map #(substitute types context variables %) (rest expression)))))

(declare reduce-node)

(defn- comprehension
  "CEL's exists and all: a fold of `||` from false, or of `&&` from true, over
   the range, with the same absorption as the logical operators. For exists a
   true element decides the result even when another element faults; then a
   fault is the result; then a missing field makes it conditional. All is the
   same with false. An absent range is conditional on the range alone, as in
   cel-go, whose predicate is not evaluated without elements. A residual keeps
   only the undecided elements and the predicate with supplied values bound."
  [types context variables [op range variable predicate]]
  (let [decisive (= :exists op)
        range-result (reduce-node types context variables range)
        residual-predicate #(substitute types context (dissoc variables variable) predicate)]
    (cond
      (:error range-result) range-result
      (:missing range-result)
      {:missing (:missing range-result)
       :residual [op (residual range-result) variable (residual-predicate)]}
      :else
      (let [range-type (:type range-result)
            value (:known range-result)
            item-type (plan/item-type range-type)]
        (loop [pending (seq (range-items range-type value)) faults [] undecided [] missing #{}]
          ;; Test the sequence, not the item: a list<bool> item can be false.
          (if pending
            (let [item (first pending)
                  result (reduce-node types context (assoc variables variable [item-type item]) predicate)]
              (cond
                (and (known? result) (= decisive (:known result))) (known :bool decisive)
                (:error result) (recur (next pending) (conj faults result) undecided missing)
                (:missing result) (recur (next pending) faults (conj undecided item)
                                         (set/union missing (:missing result)))
                :else (recur (next pending) faults undecided missing)))
            (cond
              (seq faults) (fault faults)
              (seq undecided)
              {:missing missing
               :residual [op [:literal range-type (if (= :list (first range-type))
                                                    undecided
                                                    (select-keys value undecided))]
                          variable (residual-predicate)]}
              :else (known :bool (not decisive)))))))))

(defn- reduce-node
  "`variables` maps each comprehension variable in scope to its `[type value]`."
  [types context variables [op a b :as expression]]
  (case op
    :literal (known a b)
    :param (if (contains? context a) (known (get types a) (get context a))
               {:missing #{a} :residual expression})
    :var (let [[type value] (get variables a)] (known type value))
    (:exists :all) (comprehension types context variables expression)
    (let [children (mapv #(reduce-node types context variables %) (rest expression))
          [left right] children]
      (cond
        (#{:and :or} op) (logical op left right)
        (some :error children) (fault children)
        (some :missing children) {:missing (reduce set/union #{} (keep :missing children))
                                 :residual (into [op] (map residual children))}
        :else (concrete op left right)))))

(defn evaluate-prepared
  "Evaluates inputs returned by prepare-evaluation. Never accepts raw bindings."
  [{:keys [types plan context]}]
  (let [result (reduce-node types context {} plan)]
    (cond
      (:error result) {:outcome :error :reason (:error result)}
      (:missing result) {:outcome :conditional :missing-fields (:missing result) :residual (:residual result)}
      :else {:outcome (if (:known result) :true :false)})))

(defn evaluate [parameters expression request bound]
  (try
    (evaluate-prepared (prepare-evaluation parameters expression request bound))
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
      (if (= :eacl.caveat/invalid (:type (ex-data e)))
        {:outcome :error :reason (:reason (ex-data e))}
        (throw e)))))

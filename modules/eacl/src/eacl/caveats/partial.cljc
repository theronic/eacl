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

(defn- operand-size [types context [op a b :as expression]]
  (let [type (plan/node-type types expression)]
    (cond
      (= :literal op) (value-size type b)
      (and (= :param op) (contains? context a)) (value-size type (get context a))
      :else (maximum-size type))))

(defn estimate-work
  "Conservative preflight cost, saturated at the profile limit plus one.
   Call only after plan and context admission. Includes both logical branches."
  [types expression context]
  (let [[op a b] expression]
    (if (#{:literal :param} op)
      1
      (let [operand-cost (case op
                           :contains (* (operand-size types context a) (operand-size types context b))
                           :in (+ (operand-size types context b)
                                  (* (if (= :list (first (plan/node-type types b)))
                                       (:container-entries values/limits) 1)
                                     (operand-size types context a)))
                           (:eq :ne :lt :le :gt :ge :index :starts-with :ends-with)
                           (+ (operand-size types context a) (operand-size types context b))
                           0)]
        (min (inc (:work-units values/limits))
             (+ 1 operand-cost (reduce + 0 (map #(estimate-work types % context) (rest expression)))))))))

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

(defn- reduce-node [types context [op a b :as expression]]
  (case op
    :literal (known a b)
    :param (if (contains? context a) (known (get types a) (get context a))
               {:missing #{a} :residual expression})
    (let [children (mapv #(reduce-node types context %) (rest expression))
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
  (let [result (reduce-node types context plan)]
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

(ns eacl.authorization.context
  "One bounded public context, independent of any particular Caveat schema."
  (:refer-clojure :exclude [identity])
  (:require [eacl.caveats.values :as values]
            [eacl.exact-integer :as integer]))

(def ^:private encoding-options
  {:maximum-size (:context-utf8-bytes values/limits)
   :maximum-entries (:context-total-entries values/limits)
   :maximum-depth 8})

(deftype ^:private PreparedContext [input value identity])

(defn prepared? [context] (instance? PreparedContext context))
(defn value [context] (.-value ^PreparedContext context))
(defn identity [context] (.-identity ^PreparedContext context))

(defn prepared-for?
  "Whether this preparation belongs to the exact immutable input or its
   canonical value. Never use map equality to recognize a prepared input."
  [prepared input]
  (and (prepared? prepared)
       (or (identical? input (.-input ^PreparedContext prepared))
           (identical? input (value prepared))
           (and (map? input) (empty? input) (empty? (value prepared))))))

(defn- string! [s]
  (when-not (string? s) (values/error! :context-type))
  (when (> (count s) (:string-utf8-bytes values/limits))
    (values/error! :resource-limit {:limit :string-utf8-bytes}))
  (let [size (try (values/utf8-size s)
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _
                    (values/error! :context-type)))]
    (when (> size (:string-utf8-bytes values/limits))
      (values/error! :resource-limit {:limit :string-utf8-bytes}))))

(defn- scalar-type [v]
  (cond
    (boolean? v) :bool
    (integer/exact? v) :int
    (string? v) (do (string! v) :string)
    (and (vector? v) (= 2 (count v)) (= :timestamp (first v))
         (values/valid-time? (second v))) :timestamp
    :else nil))

(defn- value-size! [v]
  (if (scalar-type v)
    (if (vector? v) 3 1)
    (do
      (when-not (or (map? v) (vector? v)) (values/error! :context-type))
      (when (> (count v) (:container-entries values/limits))
        (values/error! :resource-limit {:limit :container-entries}))
      (when (map? v) (doseq [key (keys v)] (string! key)))
      (let [items (if (map? v) (vals v) v)
            expected (when (seq items) (scalar-type (first items)))]
        (reduce (fn [size item]
                  (when-not (and expected (= expected (scalar-type item)))
                    (values/error! :context-type))
                  (+ size (if (= :timestamp expected) 3 1)))
                (if (map? v) (inc (count v)) 1) items)))))

(def ^:private empty-context (PreparedContext. {} {} (values/encode-bounded {} encoding-options)))

(defn prepare
  "Validates all supplied fields before I/O or reuse. Declared parameter types
   are checked later by each demanded Caveat. The complete canonical identity
   includes fields that no demanded Caveat uses."
  [context]
  (when-not (map? context) (values/error! :context-type))
  (when (> (count context) (:context-total-entries values/limits))
    (values/error! :resource-limit {:limit :context-size}))
  (if (empty? context)
    empty-context
    (do
      (reduce-kv
       (fn [size key v]
         (when-not (values/parameter-name? key) (values/error! :parameter-name))
         (let [size (+ size 1 (value-size! v))]
           (when (> size (:context-total-entries values/limits))
             (values/error! :resource-limit {:limit :context-size}))
           size))
       1 context)
      (let [canonical (values/canonical-host-value context)]
        (PreparedContext. context canonical (values/encode-bounded canonical encoding-options))))))

(defn declared-parameter-index
  "Indexes Caveat declarations (`{:name n :parameters [[p type] ...]}`) by
   parameter: `{parameter {type #{caveat-name ...}}}`."
  [declarations]
  (reduce (fn [index {:keys [name parameters]}]
            (reduce (fn [index [parameter type]]
                      (update-in index [parameter type] (fnil conj (sorted-set)) name))
                    index parameters))
          {} declarations))

(defn- admits-type?
  "Whether `v` is a value of declared Caveat parameter `type`. Only a type
   mismatch is decisive here; any other admission outcome is left to
   evaluation."
  [type v]
  (try
    (values/normalize-value type v)
    true
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (not= :context-type (:reason (ex-data error))))))

(defn reject-ill-typed!
  "Fail-fast admission of a prepared request context against the Caveats a
   request can reach, indexed by `declared-parameter-index`. A supplied field
   that at least one reachable Caveat declares is rejected when its value has
   none of the types those Caveats declare for it: every evaluation of such a
   Caveat would fault on it. A field no reachable Caveat declares is ignored,
   and a value one declaration admits is accepted even if another rejects it.
   Throws `:eacl.caveat/invalid` with `:reason :context-type`, the
   `:parameter`, the declared types (`:expected`) and the declaring Caveats
   (`:caveats`), for the first such field in parameter order."
  [context index]
  (let [supplied (value context)]
    (when (and (seq supplied) (seq index))
      (doseq [parameter (sort (keys index))
              :when (contains? supplied parameter)
              :let [v (get supplied parameter)
                    types (get index parameter)]]
        (when-not (some #(admits-type? % v) (keys types))
          (values/error! :context-type
                         {:parameter parameter
                          :expected (vec (sort-by pr-str (keys types)))
                          :caveats (vec (into (sorted-set) cat (vals types)))}))))))

(defn project
  "Projects a validated request onto one admitted Caveat's parameter names."
  [context parameters]
  (let [source (value context)]
    ;; Prepared values cannot be nil; false remains a supplied Boolean binding.
    (reduce (fn [result [name _]]
              (if-some [v (get source name)] (assoc result name v) result))
            {} parameters)))

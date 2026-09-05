(ns eacl.authorization.context
  "One bounded public context, independent of any particular Caveat schema."
  (:refer-clojure :exclude [identity])
  (:require [eacl.caveats.values :as values]
            [eacl.exact-integer :as integer]))

(def ^:private encoding-options
  {:maximum-size (:context-utf8-bytes values/limits)
   :maximum-entries (:context-total-entries values/limits)
   :maximum-depth 8})

(deftype ^:private PreparedContext [value identity])

(defn prepared? [context] (instance? PreparedContext context))
(defn value [context] (.-value ^PreparedContext context))
(defn identity [context] (.-identity ^PreparedContext context))

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

(def ^:private empty-context (PreparedContext. {} (values/encode-bounded {} encoding-options)))

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
      (PreparedContext. context (values/encode-bounded context encoding-options)))))

(defn project
  "Projects a validated request onto one admitted Caveat's parameter names."
  [context parameters]
  (let [source (value context)]
    (reduce (fn [result [name _]]
              (if (contains? source name) (assoc result name (get source name)) result))
            {} parameters)))

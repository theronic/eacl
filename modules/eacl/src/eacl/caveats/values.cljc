(ns eacl.caveats.values
  "Bounded portable values for the staged EACL CEL profile. No evaluator code."
  (:require [clojure.string :as str]
            [eacl.exact-integer :as integer]
            [eacl.secure-format :as secure]))

(def profile-id "eacl-cel/1")
(def format-version 1)
(def limits
  {:source-utf8-bytes 8192 :tokens 1024 :source-group-depth 32
   :plan-depth 32 :plan-nodes 256 :parameters 32 :identifier-ascii-bytes 64
   :string-utf8-bytes 4096 :container-entries 128 :context-total-entries 1024
   :context-utf8-bytes 16384 :work-units 1048576 :program-cache-entries 256
   :program-build-concurrency 4 :integer-min integer/minimum :integer-max integer/maximum
   :timestamp-min-ms -62135596800000 :timestamp-max-ms 253402300799999})

(def scalar-types #{:bool :int :string :timestamp})
(def ^:private reserved-names
  #{"true" "false" "null" "in" "as" "break" "const" "continue" "else"
    "for" "function" "if" "import" "let" "loop" "package" "namespace"
    "return" "var" "void" "while" "bool" "int" "uint" "double" "string"
    "bytes" "list" "map" "type" "null_type" "timestamp" "duration" "dyn" "any"})

(defn error!
  ([reason] (error! reason {}))
  ([reason data]
   (throw (ex-info "Invalid EACL Caveat value."
                   (merge data {:type :eacl.caveat/invalid :eacl/error :eacl.caveat/invalid
                                :reason reason})))))

(defn utf8-size [s] (count (secure/utf8-bytes s)))

(defn parameter-name? [s]
  (and (string? s) (<= (count s) (:identifier-ascii-bytes limits))
       (boolean (re-matches #"[A-Za-z_][A-Za-z0-9_]*" s))
       (not (contains? reserved-names s)) (not (str/starts-with? s "__eacl_"))))

(defn parameter-type? [t]
  (or (contains? scalar-types t)
      (and (vector? t) (= 2 (count t)) (= :list (first t))
           (contains? scalar-types (second t)))
      (and (vector? t) (= 3 (count t)) (= [:map :string] (subvec t 0 2))
           (contains? scalar-types (nth t 2)))))

(defn normalize-parameters [parameters]
  (when-not (or (map? parameters) (sequential? parameters)) (error! :parameter-shape))
  (when (> (bounded-count (inc (:parameters limits)) parameters) (:parameters limits))
    (error! :resource-limit {:limit :parameters}))
  (let [pairs (mapv (fn [pair]
                      (when-not (and (sequential? pair) (= 2 (bounded-count 3 pair)))
                        (error! :parameter-shape))
                      (let [[name type] pair]
                        (when-not (parameter-name? name) (error! :parameter-name))
                        (when-not (parameter-type? type) (error! :parameter-type))
                        [name type])) parameters)]
    (when-not (= (count pairs) (count (set (map first pairs)))) (error! :duplicate-parameter))
    (vec (sort-by first pairs))))

(defn valid-time? [v]
  (and (integer/exact? v) (<= (:timestamp-min-ms limits) v (:timestamp-max-ms limits))))

(defn- checked-string [v]
  (when-not (string? v) (error! :context-type))
  (when (> (count v) (:string-utf8-bytes limits))
    (error! :resource-limit {:limit :string-utf8-bytes}))
  (let [size (try (utf8-size v)
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ (error! :context-type)))]
    (when (> size (:string-utf8-bytes limits)) (error! :resource-limit {:limit :string-utf8-bytes})))
  v)

(defn- scalar-order [a b]
  (let [x (secure/utf8-bytes a) y (secure/utf8-bytes b)
        nx (count x) ny (count y)]
    (loop [i 0]
      (if (= i (min nx ny))
        (compare nx ny)
        (let [c (compare (nth x i) (nth y i))]
          (if (zero? c) (recur (inc i)) c))))))

(defn- charge! [budget size]
  (vswap! budget (fn [[entries bytes]] [(inc entries) (+ bytes size)]))
  (let [[entries bytes] @budget]
    (when (or (> entries (:context-total-entries limits)) (> bytes (:context-utf8-bytes limits)))
      (error! :resource-limit {:limit :context-size}))))

(defn- encode-value [type value budget]
  (charge! budget 1)
  (case type
    :bool (if (boolean? value) [:bool value] (error! :context-type))
    :int (if (integer/exact? value) [:int value] (error! :context-type))
    :string (let [s (checked-string value)] (charge! budget (utf8-size s)) [:string s])
    :timestamp (if (and (vector? value) (= 2 (count value)) (= :timestamp (first value))
                         (valid-time? (second value))) value (error! :context-type))
    (case (first type)
      :list (do
              (when-not (vector? value) (error! :context-type))
              (when (> (count value) (:container-entries limits)) (error! :resource-limit {:limit :container-entries}))
              [:list (second type) (mapv #(encode-value (second type) % budget) value)])
      :map (do
             (when-not (map? value) (error! :context-type))
             (when (> (count value) (:container-entries limits)) (error! :resource-limit {:limit :container-entries}))
             (doseq [key (keys value)] (checked-string key) (charge! budget (utf8-size key)))
             [:map (nth type 2)
              (mapv (fn [key] [key (encode-value (nth type 2) (get value key) budget)])
                    (sort scalar-order (keys value)))])
      (error! :parameter-type))))

(def ^:private encoding-options
  {:maximum-size (:context-utf8-bytes limits) :maximum-depth 8
   :maximum-entries (:context-total-entries limits)})

(defn- encode-payload [value]
  (let [encoded (try (secure/encode-canonical value encoding-options)
                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                       (error! :resource-limit {:limit (:reason (ex-data e))})))]
    (when (> (utf8-size encoded) (:context-utf8-bytes limits)) (error! :resource-limit {:limit :context-utf8-bytes}))
    encoded))

(defn encode-parameters [parameters]
  (encode-payload [:eacl.caveat/parameters format-version (normalize-parameters parameters)]))

(defn encode-context [parameters context]
  (when-not (map? context) (error! :context-type))
  (when (> (count context) (:parameters limits)) (error! :resource-limit {:limit :parameters}))
  (let [types (into {} (normalize-parameters parameters))
        _ (doseq [key (keys context)] (when-not (contains? types key) (error! :unknown-parameter)))
        budget (volatile! [0 0])
        pairs (mapv (fn [key]
                       (charge! budget (count key))
                       [key (encode-value (get types key) (get context key) budget)])
                     (sort (keys context)))]
    (encode-payload [:eacl.caveat/context format-version pairs])))

(defn- bounded-source! [payload]
  (when-not (and (string? payload) (<= (count payload) (:context-utf8-bytes limits))
                (<= (utf8-size payload) (:context-utf8-bytes limits)))
    (error! :resource-limit {:limit :context-utf8-bytes}))
  (loop [chars (seq payload) depth 0 quoted? false escaped? false]
    (when-let [c (first chars)]
      (cond
        escaped? (recur (next chars) depth quoted? false)
        (and quoted? (= c \\)) (recur (next chars) depth true true)
        (= c \u0022) (recur (next chars) depth (not quoted?) false)
        quoted? (recur (next chars) depth true false)
        (#{\[ \{ \(} c) (if (>= depth 8) (error! :resource-limit {:limit :payload-depth})
                             (recur (next chars) (inc depth) false false))
        (#{\] \} \)} c) (recur (next chars) (dec depth) false false)
        :else (recur (next chars) depth false false)))))

(defn- decode-payload [tag payload]
  (bounded-source! payload)
  (let [v (try (secure/decode-canonical payload encoding-options)
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ (error! :malformed-payload)))]
    (when-not (and (vector? v) (= 3 (count v)) (= tag (first v))
                   (= format-version (second v)) (vector? (nth v 2)))
      (error! :malformed-payload))
    (nth v 2)))

(defn decode-parameters [payload]
  (let [pairs (normalize-parameters (decode-payload :eacl.caveat/parameters payload))]
    (when-not (= payload (encode-parameters pairs)) (error! :noncanonical-payload))
    pairs))

(defn- decode-value [type v]
  (when-not (vector? v) (error! :malformed-payload))
  (if (contains? scalar-types type)
    (do (when-not (and (= 2 (count v)) (= type (first v))) (error! :malformed-payload))
        (if (= :timestamp type) v (second v)))
    (let [list? (= :list (first type)) item-type (if list? (second type) (nth type 2))]
      (when-not (and (= 3 (count v)) (= (first type) (first v)) (= item-type (second v))
                     (vector? (nth v 2))) (error! :malformed-payload))
      (if list?
        (mapv #(decode-value item-type %) (nth v 2))
        (let [pairs (nth v 2)]
          (when-not (every? #(and (vector? %) (= 2 (count %))) pairs) (error! :malformed-payload))
          (when-not (= (count pairs) (count (set (map first pairs)))) (error! :malformed-payload))
          (into {} (map (fn [[k value]] [k (decode-value item-type value)])) pairs))))))

(defn decode-context [parameters payload]
  (let [pairs (decode-payload :eacl.caveat/context payload)
        types (into {} (normalize-parameters parameters))]
    (when-not (every? #(and (vector? %) (= 2 (count %))) pairs) (error! :malformed-payload))
    (when-not (= (count pairs) (count (set (map first pairs)))) (error! :malformed-payload))
    (let [context (into {} (map (fn [[key value]]
                                 (when-not (contains? types key) (error! :unknown-parameter))
                                 [key (decode-value (get types key) value)])) pairs)]
      (when-not (= payload (encode-context parameters context)) (error! :noncanonical-payload))
      context)))

(defn normalize-context [parameters context]
  (decode-context parameters (encode-context parameters context)))

(defn merge-context [parameters request bound]
  (normalize-context parameters (merge (normalize-context parameters request)
                                       (normalize-context parameters bound))))

(ns eacl.schema.expression
  "Closed, portable permission-expression values and their canonical v1 codec.

  These values are resolved schema data, not executable plans. Source grouping
  is retained by :grouped?, while later semantic-DAG compilation is free to
  canonicalize commutative operators without changing this diagnostic tree."
  (:require [eacl.secure-format :as secure]))

(def format-version :eacl.permission-expression/v1)
(def digest-domain "eacl/permission-expression/v1")

;; Hard codec ceilings are deliberately above the calibrated admission policy.
;; They exist so a value at an exact supported expression boundary remains
;; encodable while malformed direct codec use is still bounded.
(def codec-limits
  {:maximum-size 1048576
   :maximum-depth 256
   :maximum-entries 262144})

(def ^:private expression-keys
  #{:format :resource-type :permission-name :root})

(defn- bounded-codec-options [options]
  (reduce-kv
    (fn [result key hard-maximum]
      (let [requested (get result key hard-maximum)]
        (assoc result key
               (if (and (integer? requested) (not (neg? requested)))
                 (min requested hard-maximum)
                 requested))))
    options
    codec-limits))

(declare canonical-node)

(defn- invalid!
  [reason data]
  (throw (ex-info "Invalid EACL permission expression."
           (merge {:type :eacl.schema/invalid-permission-expression
                   :eacl/error :eacl.schema/invalid-permission-expression
                   :reason reason}
                  data))))

(defn- exact-map!
  [value expected-keys context]
  (when-not (map? value)
    (invalid! :expected-map {:context context :value value}))
  (let [actual-keys (set (keys value))]
    (when-not (= expected-keys actual-keys)
      (invalid! :unknown-or-missing-fields
        {:context context
         :expected-keys expected-keys
         :actual-keys actual-keys}))))

(defn- simple-keyword!
  [value context]
  (when-not (and (keyword? value)
                 (nil? (namespace value))
                 (not-empty (name value))
                 (not= :self value))
    (invalid! :invalid-identifier {:context context :value value}))
  value)

(defn- grouped!
  [value]
  (when-not (boolean? value)
    (invalid! :invalid-grouping-flag {:value value}))
  value)

(defn relation
  "Constructs a resolved relation leaf. Subject types are sorted and unique."
  ([name subject-types]
   (relation name subject-types false))
  ([name subject-types grouped?]
   (simple-keyword! name :relation-name)
   (grouped! grouped?)
   (when-not (and (sequential? subject-types) (seq subject-types))
     (invalid! :invalid-subject-types {:value subject-types}))
   (let [subject-types (mapv #(simple-keyword! % :subject-type) subject-types)
         canonical (vec (sort-by str (distinct subject-types)))]
     (when-not (= (count subject-types) (count canonical))
       (invalid! :duplicate-subject-type {:value subject-types}))
     {:op :relation
      :name name
      :subject-types canonical
      :grouped? grouped?})))

(defn permission
  "Constructs a same-definition named-permission reference."
  ([name]
   (permission name false))
  ([name grouped?]
   (simple-keyword! name :permission-name)
   (grouped! grouped?)
   {:op :permission :name name :grouped? grouped?}))

(defn- canonical-partition
  [partition]
  (exact-map! partition #{:subject-type :target-kind :target-name}
    :arrow-partition)
  (let [{:keys [subject-type target-kind target-name]} partition]
    (simple-keyword! subject-type :arrow-subject-type)
    (when-not (contains? #{:relation :permission} target-kind)
      (invalid! :invalid-arrow-target-kind {:value target-kind}))
    (simple-keyword! target-name :arrow-target-name)
    {:subject-type subject-type
     :target-kind target-kind
     :target-name target-name}))

(defn arrow
  "Constructs a resolved one-hop arrow with one target partition for every
   source-relation subject type. Partitions are sorted by typed identity."
  ([relation-name partitions]
   (arrow relation-name partitions false))
  ([relation-name partitions grouped?]
   (simple-keyword! relation-name :arrow-relation)
   (grouped! grouped?)
   (when-not (and (sequential? partitions) (seq partitions))
     (invalid! :invalid-arrow-partitions {:value partitions}))
   (let [partitions (mapv canonical-partition partitions)
         canonical (vec (sort-by (juxt (comp str :subject-type)
                                       (comp str :target-kind)
                                       (comp str :target-name))
                                 partitions))
         subject-types (mapv :subject-type canonical)]
     (when-not (= (count subject-types) (count (distinct subject-types)))
       (invalid! :duplicate-arrow-subject-type {:value subject-types}))
     {:op :arrow
      :relation relation-name
      :partitions canonical
      :grouped? grouped?})))

(defn- nary
  [op children grouped?]
  (grouped! grouped?)
  (when-not (and (sequential? children) (<= 2 (count children)))
    (invalid! :invalid-operator-arity {:op op :value children}))
  {:op op
   :children (mapv canonical-node children)
   :grouped? grouped?})

(defn union
  ([children]
   (union children false))
  ([children grouped?]
   (nary :union children grouped?)))

(defn intersection
  ([children]
   (intersection children false))
  ([children grouped?]
   (nary :intersection children grouped?)))

(defn exclusion
  ([left right]
   (exclusion left right false))
  ([left right grouped?]
   (grouped! grouped?)
   {:op :exclusion
    :left (canonical-node left)
    :right (canonical-node right)
    :grouped? grouped?}))

(defn canonical-node
  "Validates and reconstructs one node, rejecting every unknown or missing
   field before the value reaches storage or planning."
  [node]
  (when-not (map? node)
    (invalid! :expected-node-map {:value node}))
  (case (:op node)
    :relation
    (do
      (exact-map! node #{:op :name :subject-types :grouped?} :relation)
      (when-not (vector? (:subject-types node))
        (invalid! :invalid-subject-types {:value (:subject-types node)}))
      (relation (:name node) (:subject-types node) (:grouped? node)))

    :permission
    (do
      (exact-map! node #{:op :name :grouped?} :permission)
      (permission (:name node) (:grouped? node)))

    :arrow
    (do
      (exact-map! node #{:op :relation :partitions :grouped?} :arrow)
      (when-not (vector? (:partitions node))
        (invalid! :invalid-arrow-partitions {:value (:partitions node)}))
      (arrow (:relation node) (:partitions node) (:grouped? node)))

    :union
    (do
      (exact-map! node #{:op :children :grouped?} :union)
      (when-not (vector? (:children node))
        (invalid! :invalid-operator-children {:op :union}))
      (union (:children node) (:grouped? node)))

    :intersection
    (do
      (exact-map! node #{:op :children :grouped?} :intersection)
      (when-not (vector? (:children node))
        (invalid! :invalid-operator-children {:op :intersection}))
      (intersection (:children node) (:grouped? node)))

    :exclusion
    (do
      (exact-map! node #{:op :left :right :grouped?} :exclusion)
      (exclusion (:left node) (:right node) (:grouped? node)))

    (invalid! :unknown-node-tag {:tag (:op node)})))

(defn expression
  "Constructs one versioned resolved source expression."
  [resource-type permission-name root]
  (simple-keyword! resource-type :resource-type)
  (simple-keyword! permission-name :permission-name)
  {:format format-version
   :resource-type resource-type
   :permission-name permission-name
   :root (canonical-node root)})

(defn canonicalize
  "Validates a decoded expression and reconstructs its canonical value."
  [value]
  (exact-map! value expression-keys :permission-expression)
  (when-not (= format-version (:format value))
    (invalid! :unsupported-format {:format (:format value)}))
  (expression (:resource-type value)
              (:permission-name value)
              (:root value)))

(defn encode
  "Returns the unique portable EDN encoding of a v1 permission expression."
  ([value]
   (encode value {}))
  ([value options]
   (secure/encode-canonical (canonicalize value)
                            (bounded-codec-options options))))

(defn decode
  "Decodes only canonical v1 values. Noncanonical spelling, unknown fields,
   unknown tags, and malformed values fail closed."
  ([encoded]
   (decode encoded {}))
  ([encoded options]
   (try
     (let [options (bounded-codec-options options)
           decoded (secure/decode-canonical
                     encoded
                     (assoc options :allowed-keys expression-keys))
           value (canonicalize decoded)
           canonical (secure/encode-canonical value options)]
       (when-not (= encoded canonical)
         (invalid! :noncanonical-encoding {}))
       value)
     (catch #?(:clj Exception :cljs :default) error
       (if (= :eacl.schema/invalid-permission-expression
              (:type (ex-data error)))
         (throw error)
         (invalid! :malformed-codec
           {:format-error (:reason (ex-data error))}))))))

(defn digest
  "Returns the portable domain-separated digest of the canonical expression."
  [value]
  (secure/b64url-encode
   (secure/sha-256
    (str digest-domain "\n"
         (secure/encode-canonical (canonicalize value) codec-limits)))))

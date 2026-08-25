(ns eacl.schema.expression-limits
  "Exact source-tree and normalized semantic-DAG accounting.

  This namespace defines dimensions and enforcement, but deliberately does not
  choose production defaults. Task 3.7 calibrates and versions those values."
  (:require [eacl.schema.expression :as expression]
            [eacl.secure-format :as secure]))

(def normalized-dag-format :eacl.permission-expression-dag/v1)

(defn- invalid-limit!
  [limit value]
  (throw (ex-info "Invalid permission-expression limit."
           {:type :eacl.schema/invalid-expression-limit
            :eacl/error :eacl.schema/invalid-expression-limit
            :limit limit
            :value value})))

(defn- exceeded!
  [dimension maximum actual]
  (throw (ex-info "Permission expression exceeds its configured limit."
           {:type :eacl.schema/expression-limit
            :eacl/error :eacl.schema/expression-limit
            :dimension dimension
            :maximum maximum
            :actual actual})))

(defn- enforce!
  [metrics limits dimension limit-key]
  (when-let [maximum (get limits limit-key)]
    (when-not (and (integer? maximum) (not (neg? maximum)))
      (invalid-limit! limit-key maximum))
    (let [actual (get metrics dimension)]
      (when (> actual maximum)
        (exceeded! dimension maximum actual)))))

(defn source-metrics
  "Counts unresolved semantic source nodes iteratively. Root depth is one;
   :direct-fan-in is the largest immediate operator arity."
  [root]
  (loop [stack [[root 1]]
         node-count 0
         maximum-depth 0
         direct-fan-in 0]
    (if (empty? stack)
      {:node-count node-count
       :maximum-depth maximum-depth
       :direct-fan-in direct-fan-in}
      (let [[[node depth] & remaining] stack
            op (:op node)
            children
            (case op
              (:identifier :arrow) []
              (:union :intersection) (:children node)
              :exclusion [(:left node) (:right node)]
              (throw (ex-info "Unknown source expression node."
                       {:type :eacl.schema/invalid-permission-expression
                        :eacl/error :eacl.schema/invalid-permission-expression
                        :reason :unknown-source-node
                        :node node})))
            stack (into remaining (map #(vector % (inc depth)) children))]
        (recur stack
               (inc node-count)
               (max maximum-depth depth)
               (max direct-fan-in (count children)))))))

(defn check-source!
  "Enforces source node/depth/direct-fan-in limits before resolved expression
   or normalized DAG allocation. Returns the measured dimensions."
  [root limits]
  (let [metrics (source-metrics root)]
    (enforce! metrics limits :node-count :maximum-source-nodes)
    (enforce! metrics limits :maximum-depth :maximum-source-depth)
    (enforce! metrics limits :direct-fan-in :maximum-direct-fan-in)
    metrics))

(declare normalize-node)

(defn- node-sort-key [node]
  (secure/encode-canonical node))

(defn- normalize-commutative
  [op children]
  (let [children (mapv normalize-node children)
        children (mapcat #(if (= op (:op %)) (:children %) [%]) children)
        children (->> children
                      distinct
                      (sort-by node-sort-key)
                      vec)]
    (if (= 1 (count children))
      (first children)
      {:op op :children children})))

(defn normalize-node
  "Builds the canonical semantic node used for DAG accounting. Grouping is
   source-only; union/intersection flatten, sort, and deduplicate; exclusion
   remains ordered and binary."
  [node]
  (let [node (expression/canonical-node node)]
    (case (:op node)
      :relation
      (select-keys node [:op :name :subject-types])

      :permission
      (select-keys node [:op :name])

      :arrow
      (select-keys node [:op :relation :partitions])

      :union
      (normalize-commutative :union (:children node))

      :intersection
      (normalize-commutative :intersection (:children node))

      :exclusion
      {:op :exclusion
       :left (normalize-node (:left node))
       :right (normalize-node (:right node))})))

(defn- children
  [node]
  (case (:op node)
    (:union :intersection) (:children node)
    :exclusion [(:left node) (:right node)]
    []))

(defn- collect-nodes
  [root]
  (loop [stack [root]
         seen #{}]
    (if-let [node (peek stack)]
      (if (contains? seen node)
        (recur (pop stack) seen)
        (recur (into (pop stack) (children node)) (conj seen node)))
      seen)))

(defn- words-for [n]
  (quot (+ n 31) 32))

(defn- node-record
  [node node->id]
  (case (:op node)
    :relation [:relation (:name node) (:subject-types node)]
    :permission [:permission (:name node)]
    :arrow [:arrow (:relation node) (:partitions node)]
    :union [:union (mapv node->id (:children node))]
    :intersection [:intersection (mapv node->id (:children node))]
    :exclusion [:exclusion (node->id (:left node)) (node->id (:right node))]))

(defn normalized-dag
  "Returns a deterministic interned DAG table and exact portable dimensions."
  [resolved-expression]
  (let [resolved-expression (expression/canonicalize resolved-expression)
        root (normalize-node (:root resolved-expression))
        nodes (vec (sort-by node-sort-key (collect-nodes root)))
        node->id (zipmap nodes (range))
        records (mapv #(node-record % node->id) nodes)
        child-slot-count (reduce + 0 (map (comp count children) nodes))
        word-count (+ (words-for (count nodes))
                      (reduce + 0
                              (for [node nodes
                                    :when (contains? #{:union :intersection}
                                                     (:op node))]
                                (words-for (count (:children node))))))
        value {:format normalized-dag-format
               :root (node->id root)
               :nodes records}
        checkpoint-weight
        (count (secure/utf8-bytes (secure/encode-canonical value)))]
    {:dag value
     :metrics {:node-count (count nodes)
               :child-slot-count child-slot-count
               :word-count word-count
               :checkpoint-weight checkpoint-weight}}))

(defn check-normalized!
  "Canonicalizes and enforces normalized DAG resource dimensions."
  [resolved-expression limits]
  (let [{:keys [metrics] :as result} (normalized-dag resolved-expression)]
    (enforce! metrics limits :node-count :maximum-normalized-nodes)
    (enforce! metrics limits :child-slot-count :maximum-child-slots)
    (enforce! metrics limits :word-count :maximum-words)
    (enforce! metrics limits :checkpoint-weight :maximum-checkpoint-weight)
    result))

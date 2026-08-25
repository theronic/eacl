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

(defn check-dimension!
  "Enforces one exact scalar dimension and returns the measured value."
  [dimension limit-key actual limits]
  (enforce! {dimension actual} limits dimension limit-key)
  actual)

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

(defn- record-children
  [record]
  (case (first record)
    (:union :intersection) (second record)
    :exclusion [(second record) (nth record 2)]
    []))

(defn- intern-record
  [state record]
  (if-some [id (get (:record->id state) record)]
    [state id]
    (let [id (count (:records state))
          child-ids (record-children record)
          height (if (seq child-ids)
                   (inc (reduce max (map #(nth (:heights state) %)
                                         child-ids)))
                   0)]
      [(-> state
           (update :records conj record)
           (update :heights conj height)
           (assoc-in [:record->id record] id))
       id])))

(declare normalize-to-record)

(defn- normalize-children
  [state children]
  (reduce (fn [[state ids] child]
            (let [[state id] (normalize-to-record state child)]
              [state (conj ids id)]))
          [state []]
          children))

(defn- normalize-commutative-record
  [state op children]
  (let [[state ids] (normalize-children state children)
        ids (mapcat (fn [id]
                      (let [record (nth (:records state) id)]
                        (if (= op (first record))
                          (second record)
                          [id])))
                    ids)
        ids (vec (sort (distinct ids)))]
    (if (= 1 (count ids))
      [state (first ids)]
      (intern-record state [op ids]))))

(defn- normalize-to-record
  "One-pass structural normalization into provisional interned records."
  [state node]
  (case (:op node)
    :relation
    (intern-record state [:relation (:name node) (:subject-types node)])

    :permission
    (intern-record state [:permission (:name node)])

    :arrow
    (intern-record state [:arrow (:relation node) (:partitions node)])

    :union
    (normalize-commutative-record state :union (:children node))

    :intersection
    (normalize-commutative-record state :intersection (:children node))

    :exclusion
    (let [[state left] (normalize-to-record state (:left node))
          [state right] (normalize-to-record state (:right node))]
      (intern-record state [:exclusion left right]))

    (throw (ex-info "Unknown canonical expression node."
             {:type :eacl.schema/invalid-permission-expression
              :eacl/error :eacl.schema/invalid-permission-expression
              :reason :unknown-node-tag
              :node node}))))

(defn- rewrite-record
  [record old->new]
  (case (first record)
    (:union :intersection)
    [(first record) (vec (sort (map old->new (second record))))]

    :exclusion
    [:exclusion (old->new (second record)) (old->new (nth record 2))]

    record))

(defn- record-sort-key [record]
  (secure/encode-canonical record expression/codec-limits))

(defn- reachable-record-ids [records root]
  (loop [stack [root]
         reachable #{}]
    (if-let [id (peek stack)]
      (if (contains? reachable id)
        (recur (pop stack) reachable)
        (recur (into (pop stack) (record-children (nth records id)))
               (conj reachable id)))
      reachable)))

(defn- canonical-record-table
  "Assigns stable IDs bottom-up. Each sort key is a shallow record whose child
   IDs are already canonical, avoiding repeated full-subtree serialization."
  [{:keys [records heights]} root]
  (let [reachable (reachable-record-ids records root)]
   (loop [remaining-heights
          (sort (distinct (map #(nth heights %) reachable)))
         old->new {}
         canonical []]
    (if-let [height (first remaining-heights)]
      (let [candidates
            (->> (range (count records))
                 (filter #(and (contains? reachable %)
                               (= height (nth heights %))))
                 (map (fn [old-id]
                        (let [record (rewrite-record (nth records old-id)
                                                     old->new)]
                          [old-id record (record-sort-key record)])))
                 (sort-by #(nth % 2)))
            [old->new canonical]
            (reduce (fn [[old->new canonical] [old-id record _]]
                      [(assoc old->new old-id (count canonical))
                       (conj canonical record)])
                    [old->new canonical]
                    candidates)]
        (recur (rest remaining-heights) old->new canonical))
      {:old->new old->new :records canonical}))))

(defn- words-for [n]
  (quot (+ n 31) 32))

(defn- record-slot-count [record]
  (case (first record)
    :relation (count (nth record 2))
    :arrow (count (nth record 2))
    (count (record-children record))))

(defn normalized-dag
  "Returns a deterministic interned DAG table and exact portable dimensions."
  [resolved-expression]
  (let [resolved-expression (expression/canonicalize resolved-expression)
        [provisional root]
        (normalize-to-record {:records [] :heights [] :record->id {}}
                             (:root resolved-expression))
        {:keys [old->new records]} (canonical-record-table provisional root)
        child-slot-count (reduce + 0 (map record-slot-count records))
        word-count (+ (words-for (count records))
                      (reduce + 0
                              (for [record records
                                    :let [slots (record-slot-count record)]
                                    :when (pos? slots)]
                                (words-for slots))))
        value {:format normalized-dag-format
               :root (old->new root)
               :nodes records}
        checkpoint-weight
        (count (secure/utf8-bytes
                 (secure/encode-canonical value expression/codec-limits)))]
    {:dag value
     :metrics {:node-count (count records)
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

(defn expression-byte-size
  "Returns the exact portable UTF-8 byte size of the canonical source payload."
  [resolved-expression]
  (count (secure/utf8-bytes (expression/encode resolved-expression))))

(defn check-expression-bytes!
  [resolved-expression limits]
  (let [metrics {:encoded-byte-size
                 (expression-byte-size resolved-expression)}]
    (enforce! metrics limits :encoded-byte-size :maximum-expression-bytes)
    metrics))

(defn aggregate-metrics
  "Sums the bounded retained dimensions of aligned expression metadata."
  [metadata]
  (reduce
    (fn [result {:keys [source-metrics normalized-metrics
                        encoded-byte-size]}]
      (-> result
          (update :permission-count inc)
          (update :source-node-count + (:node-count source-metrics))
          (update :normalized-node-count + (:node-count normalized-metrics))
          (update :child-slot-count + (:child-slot-count normalized-metrics))
          (update :word-count + (:word-count normalized-metrics))
          (update :checkpoint-weight + (:checkpoint-weight normalized-metrics))
          (update :encoded-byte-size + encoded-byte-size)))
    {:permission-count 0
     :source-node-count 0
     :normalized-node-count 0
     :child-slot-count 0
     :word-count 0
     :checkpoint-weight 0
     :encoded-byte-size 0}
    metadata))

(defn check-aggregate!
  "Enforces whole-schema expression-state ceilings after every permission has
   passed its own limits. Returns exact summed dimensions."
  [metadata limits]
  (let [metrics (aggregate-metrics metadata)]
    (doseq [[dimension limit-key]
            [[:permission-count :maximum-permissions]
             [:source-node-count :maximum-aggregate-source-nodes]
             [:normalized-node-count :maximum-aggregate-normalized-nodes]
             [:child-slot-count :maximum-aggregate-child-slots]
             [:word-count :maximum-aggregate-words]
             [:checkpoint-weight :maximum-aggregate-checkpoint-weight]
             [:encoded-byte-size :maximum-aggregate-expression-bytes]]]
      (enforce! metrics limits dimension limit-key))
    metrics))

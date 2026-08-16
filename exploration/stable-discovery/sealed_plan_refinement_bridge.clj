(ns eacl.exploration.sealed-plan-refinement-bridge
  "Exploration-only executable refinement for the minimum sealed-plan design.

  The candidate generator uses 0/1 BFS plus a shortest-edge hop BFS.  An
  unrelated repeated-relaxation oracle checks every generated distance.  A
  structural validator recomputes exact rule membership, ranks, index order,
  certificate premises, and the canonical fingerprint."
  (:require [eacl.secure-format :as secure-format])
  (:import [java.util ArrayDeque Arrays Collections Random]))

(def ^:private infinity Long/MAX_VALUE)
(def ^:private fingerprint-domain "eacl.exploration.sealed-plan.v1")

(defn- canonical-key
  [value]
  (secure-format/encode-canonical value))

(defn- semantic-rule
  [rule]
  (dissoc rule :ordinal :rank))

(defn- rule-key
  [rule]
  (canonical-key (semantic-rule rule)))

(defn- normalize-rules
  [rules]
  (->> rules
       (reduce (fn [by-key rule]
                 (assoc by-key (rule-key rule) (semantic-rule rule)))
               {})
       (sort-by key)
       (map-indexed (fn [ordinal [_ rule]]
                      (assoc rule :ordinal ordinal)))
       vec))

(defn- rule-nodes
  [rule]
  (cond-> [(:node rule)]
    (:target-node rule) (conj (:target-node rule))))

(defn- plan-nodes
  [root rules]
  (->> rules
       (mapcat rule-nodes)
       (into #{root})
       (sort-by canonical-key)
       vec))

(defn- permission-edge-cost
  [rule]
  (case (:rule rule)
    :self-permission 0
    :arrow-permission 1
    nil))

(defn- local-read-cost
  [rule]
  (case (:rule rule)
    :relation 1
    :self-permission 0
    :arrow-relation 2
    :arrow-permission 1
    (throw (ex-info "Unknown sealed rule kind." {:rule rule}))))

(defn- permission-edges
  [rules node-index]
  (->> rules
       (keep (fn [{:keys [node target-node ordinal] :as rule}]
               (when-let [cost (permission-edge-cost rule)]
                 {:from (get node-index target-node)
                  :to (get node-index node)
                  :cost cost
                  :rule-ordinal ordinal})))
       (map-indexed (fn [edge-index edge]
                      (assoc edge :edge-index edge-index)))
       vec))

(defn- reverse-adjacency
  [node-count edges]
  (reduce (fn [adjacency edge]
            (update adjacency (:to edge) conj edge))
          (vec (repeat node-count []))
          edges))

(defn- outgoing-adjacency
  [node-count edges]
  (reduce (fn [adjacency edge]
            (update adjacency (:from edge) conj edge))
          (vec (repeat node-count []))
          edges))

(defn- zero-one-distances
  [node-count root-index edges]
  (let [distance (long-array node-count)
        queue (ArrayDeque.)
        reverse (reverse-adjacency node-count edges)
        relaxations (volatile! 0)]
    (Arrays/fill distance infinity)
    (aset-long distance root-index 0)
    (.addFirst queue root-index)
    (while (not (.isEmpty queue))
      (let [to (int (.removeFirst queue))
            to-distance (aget distance to)]
        (doseq [{:keys [from cost]} (nth reverse to)]
          (vswap! relaxations inc)
          (let [candidate (+ to-distance cost)]
            (when (< candidate (aget distance from))
              (aset-long distance from candidate)
              (if (zero? cost)
                (.addFirst queue from)
                (.addLast queue from)))))))
    {:distance (vec distance)
     :relaxations @relaxations}))

(defn- shortest-edge?
  [distance {:keys [from to cost]}]
  (= (nth distance from)
     (+ cost (nth distance to))))

(defn- shortest-hop-counts
  [node-count root-index edges distance]
  (let [hops (int-array node-count)
        queue (ArrayDeque.)
        reverse-shortest
        (reverse-adjacency
         node-count
         (filterv #(shortest-edge? distance %) edges))]
    (Arrays/fill hops -1)
    (aset-int hops root-index 0)
    (.addLast queue root-index)
    (while (not (.isEmpty queue))
      (let [to (int (.removeFirst queue))]
        (doseq [{:keys [from]} (nth reverse-shortest to)]
          (when (= -1 (aget hops from))
            (aset-int hops from (inc (aget hops to)))
            (.addLast queue from)))))
    (vec hops)))

(defn- generate-certificate
  [node-count root-index edges]
  (let [{:keys [distance]}
        (zero-one-distances node-count root-index edges)
        _ (when (some #{infinity} distance)
            (throw (ex-info "A compiled permission node cannot reach the root."
                            {:distance distance :root-index root-index})))
        hops (shortest-hop-counts node-count root-index edges distance)
        outgoing (outgoing-adjacency node-count edges)
        witnesses
        (mapv
         (fn [node]
           (if (= node root-index)
             {:witness-edge (count edges)}
             (or
              (->> (nth outgoing node)
                   (filter #(and (shortest-edge? distance %)
                                 (= (nth hops node)
                                    (inc (nth hops (:to %))))))
                   (sort-by :rule-ordinal)
                   first
                   ((fn [edge]
                      (when edge
                        {:witness-edge (:edge-index edge)}))))
              (throw (ex-info "No decreasing shortest-path witness exists."
                              {:node node
                               :distance distance
                               :hops hops
                               :outgoing (nth outgoing node)})))))
         (range node-count))]
    {:distance distance
     :witness-edge (mapv :witness-edge witnesses)
     :hops hops}))

(defn- bellman-ford-distances
  [node-count root-index edges]
  (loop [distance (assoc (vec (repeat node-count infinity)) root-index 0)
         pass 0]
    (let [next-distance
          (reduce
           (fn [current {:keys [from to cost]}]
             (let [to-distance (nth current to)]
               (if (= infinity to-distance)
                 current
                 (update current from min (+ cost to-distance)))))
           distance
           edges)]
      (cond
        (= distance next-distance) distance
        (>= pass node-count)
        (throw (ex-info "Independent distance oracle did not converge."
                        {:node-count node-count :edges edges}))
        :else (recur next-distance (inc pass))))))

(defn- valid-certificate?
  [node-count root-index edges certificate]
  (let [{:keys [distance witness-edge hops]}
        certificate
        outgoing (outgoing-adjacency node-count edges)]
    (and
     (pos? node-count)
     (<= 0 root-index (dec node-count))
     (= node-count
        (count distance)
        (count witness-edge)
        (count hops))
     (every? #(and (integer? %) (<= 0 % (dec node-count))) distance)
     (every? #(and (integer? %) (<= 0 % (count edges))) witness-edge)
     (every? #(and (integer? %) (<= 0 % (dec node-count))) hops)
     (zero? (nth distance root-index))
     (= (count edges) (nth witness-edge root-index))
     (zero? (nth hops root-index))
     (every?
      (fn [{:keys [from to cost]}]
        (<= (nth distance from)
            (+ cost (nth distance to))))
      edges)
     (every?
      (fn [node]
        (if (= node root-index)
          true
          (let [witness (nth witness-edge node)
                edge (when (< witness (count edges))
                       (nth edges witness))]
            (and
             edge
             (= node (:from edge))
             (pos? (nth hops node))
             (= (nth distance node)
                (+ (:cost edge) (nth distance (:to edge))))
             (= (nth hops node) (inc (nth hops (:to edge))))
             (some #{edge} (nth outgoing node))))))
      (range node-count)))))

(defn- rank-rules
  [rules distance node-index]
  (mapv (fn [rule]
          (assoc rule :rank
                 (+ (local-read-cost rule)
                    (nth distance (get node-index (:node rule))))))
        rules))

(defn- index-rules
  [rules key-fn include?]
  (->> rules
       (filter include?)
       (group-by key-fn)
       (into {}
             (map (fn [[key values]]
                    [key (vec (sort-by (juxt :rank :ordinal) values))])))))

(defn- expected-indexes
  [rules]
  {:forward-seeds
   (index-rules
    rules
    #(case (:rule %)
       :relation (:subject-type %)
       :arrow-relation (:target-subject-type %))
    #(contains? #{:relation :arrow-relation} (:rule %)))
   :forward-consumers
   (index-rules rules :target-node
                #(contains? #{:self-permission :arrow-permission} (:rule %)))
   :reverse-rules
   (index-rules rules :node (constantly true))})

(defn- plan-body
  [rules root]
  (let [rules (normalize-rules rules)
        nodes (plan-nodes root rules)
        node-index (zipmap nodes (range))
        root-index (get node-index root)
        edges (permission-edges rules node-index)
        certificate
        (generate-certificate (count nodes) root-index edges)
        rules (rank-rules rules (:distance certificate) node-index)]
    {:version 1
     :root root
     :root-index root-index
     :nodes nodes
     :rules rules
     :edges edges
     :certificate certificate
     :indexes (expected-indexes rules)}))

(defn- plan-records
  [{:keys [version root root-index nodes rules edges certificate indexes]}]
  (concat
   [[:header version root root-index (count nodes) (count rules)]]
   (map-indexed (fn [index node] [:node index node]) nodes)
   (map (fn [rule] [:rule (:ordinal rule) rule]) rules)
   (map-indexed (fn [index edge] [:edge index edge]) edges)
   (map (fn [node]
          [:certificate
           node
           (nth (:distance certificate) node)
           (nth (:witness-edge certificate) node)
           (nth (:hops certificate) node)])
        (range (count nodes)))
   (mapcat
    (fn [index-name]
      (mapcat
       (fn [[bucket indexed-rules]]
         (map-indexed
          (fn [position rule]
            [:index index-name bucket position (:ordinal rule) (:rank rule)])
          indexed-rules))
       (sort-by (comp canonical-key key) (get indexes index-name))))
    [:forward-seeds :forward-consumers :reverse-rules])))

(defn- fingerprint
  [body]
  (secure-format/canonical-records-digest
   fingerprint-domain
   (plan-records body)))

(defn seal-plan
  [rules root]
  (let [body (plan-body rules root)]
    (assoc body :fingerprint (fingerprint body))))

(defn- reseal
  [plan]
  (assoc plan :fingerprint (fingerprint (dissoc plan :fingerprint))))

(defn- exact-index-order?
  [indexes]
  (every?
   (fn [[_ index]]
     (every?
      (fn [[_ rules]]
        (= rules (vec (sort-by (juxt :rank :ordinal) rules))))
      index))
   indexes))

(defn valid-plan?
  [source-rules root plan]
  (try
    (let [normalized (normalize-rules source-rules)
          nodes (plan-nodes root normalized)
          node-index (zipmap nodes (range))
          root-index (get node-index root)
          edges (permission-edges normalized node-index)
          node-count (count nodes)
          certificate (:certificate plan)
          expected-distance
          (bellman-ford-distances node-count root-index edges)
          ranked (rank-rules normalized expected-distance node-index)
          expected-indexes' (expected-indexes ranked)
          body (dissoc plan :fingerprint)]
      (and
       (= 1 (:version plan))
       (= root (:root plan))
       (= root-index (:root-index plan))
       (= nodes (:nodes plan))
       (= edges (:edges plan))
       (= expected-distance (:distance certificate))
       (valid-certificate?
        node-count root-index edges certificate)
       (= ranked (:rules plan))
       (= expected-indexes' (:indexes plan))
       (exact-index-order? (:indexes plan))
       (= (:fingerprint plan) (fingerprint body))))
    (catch Throwable _
      false)))

(defn- shuffled
  [^Random rng values]
  (let [copy (java.util.ArrayList. values)]
    (Collections/shuffle copy rng)
    (vec copy)))

(defn- random-node
  [^Random rng index]
  [(keyword (str "type-" (.nextInt rng 4)))
   (keyword (str "permission-" index))])

(defn- permission-rule
  [^Random rng id head target]
  (if (and (= (first head) (first target))
           (zero? (.nextInt rng 2)))
    {:rule :self-permission
     :node head
     :target-node target}
    {:rule :arrow-permission
     :node head
     :target-node target
     :via-relation-eid id
     :intermediate-type (first target)}))

(defn- seed-rule
  [^Random rng id node]
  (if (zero? (.nextInt rng 2))
    {:rule :relation
     :node node
     :relation-eid id
     :subject-type (keyword (str "subject-" (.nextInt rng 3)))}
    {:rule :arrow-relation
     :node node
     :via-relation-eid id
     :intermediate-type (keyword (str "middle-" (.nextInt rng 3)))
     :target-relation-eid (+ 100000 id)
     :target-subject-type (keyword (str "subject-" (.nextInt rng 3)))}))

(defn- random-case
  [^Random rng]
  (let [node-count (+ 2 (.nextInt rng 47))
        nodes (mapv #(random-node rng %) (range node-count))
        root (first nodes)
        backbone
        (mapv (fn [index]
                (permission-rule
                 rng (+ 1000 index)
                 (nth nodes (.nextInt rng index))
                 (nth nodes index)))
              (range 1 node-count))
        extra-count (.nextInt rng (inc (* 2 node-count)))
        extras
        (mapv (fn [index]
                (permission-rule
                 rng (+ 100000 index)
                 (nth nodes (.nextInt rng node-count))
                 (nth nodes (.nextInt rng node-count))))
              (range extra-count))
        seeds
        (mapv (fn [index]
                (seed-rule rng (+ 200000 index)
                           (nth nodes (.nextInt rng node-count))))
              (range (inc (.nextInt rng (inc (* 2 node-count))))))
        rules (vec (concat backbone extras seeds))
        plan (seal-plan rules root)
        oracle
        (bellman-ford-distances
         (count (:nodes plan)) (:root-index plan) (:edges plan))
        permutation
        (into (shuffled rng rules)
              (take (.nextInt rng (inc (min 5 (count rules)))) rules))
        replay (seal-plan permutation root)]
    (when-not
     (and (= oracle (get-in plan [:certificate :distance]))
          (valid-plan? rules root plan)
          (valid-plan? permutation root replay)
          (= plan replay))
      {:root root
       :rules rules
       :permutation permutation
       :plan plan
       :replay replay
       :oracle oracle})))

(defn- control-rules
  []
  [{:rule :relation
    :node [:document :view]
    :relation-eid 1
    :subject-type :user}
   {:rule :relation
    :node [:document :base]
    :relation-eid 2
    :subject-type :user}
   {:rule :self-permission
    :node [:document :view]
    :target-node [:document :base]}
   {:rule :relation
    :node [:folder :read]
    :relation-eid 3
    :subject-type :user}
   {:rule :arrow-permission
    :node [:document :base]
    :target-node [:folder :read]
    :via-relation-eid 4
    :intermediate-type :folder}
   {:rule :arrow-relation
    :node [:document :view]
    :via-relation-eid 5
    :intermediate-type :team
    :target-relation-eid 6
    :target-subject-type :user}])

(defn- update-first-vector
  [indexes f]
  (let [[index-name buckets]
        (first (filter (comp seq val) indexes))
        [bucket rules] (first buckets)]
    (assoc-in indexes [index-name bucket] (f rules))))

(defn- swap-tied-vector
  [indexes]
  (or
   (some
    (fn [[index-name buckets]]
      (some
       (fn [[bucket rules]]
         (some
          (fn [position]
            (when (= (:rank (nth rules position))
                     (:rank (nth rules (inc position))))
              (assoc-in indexes [index-name bucket]
                        (assoc rules
                               position (nth rules (inc position))
                               (inc position) (nth rules position)))))
          (range (max 0 (dec (count rules))))))
       buckets))
    indexes)
   indexes))

(defn- rerank-indexes
  [rules]
  (expected-indexes rules))

(defn- transitive-rank-counterexample
  [dead-branch-count]
  (let [root [:zz-root :view]
        dead-nodes
        (mapv (fn [index]
                [(keyword (format "aa-dead-%04d" index)) :read])
              (range dead-branch-count))
        rules
        (vec
         (concat
          [{:rule :relation
            :node root
            :relation-eid 1
            :subject-type :user}]
          (map-indexed
           (fn [index node]
             {:rule :relation
              :node node
              :relation-eid (+ 1000 index)
              :subject-type :user})
           dead-nodes)
          (map-indexed
           (fn [index node]
             {:rule :arrow-permission
              :node root
              :target-node node
              :via-relation-eid (+ 100000 index)
              :intermediate-type (first node)})
           dead-nodes)))
        plan (seal-plan rules root)
        ranked-seeds (get-in plan [:indexes :forward-seeds :user])
        local-seeds
        (->> ranked-seeds
             (map #(assoc % :rank (local-read-cost %)))
             (sort-by (juxt :rank :ordinal))
             vec)
        root-position
        (fn [seeds]
          (first
           (keep-indexed
            (fn [position rule]
              (when (= root (:node rule)) position))
            seeds)))]
    {:ranked-root-position (root-position ranked-seeds)
     :local-only-root-position (root-position local-seeds)
     :minimum-avoided-seed-scans dead-branch-count}))

(defn- mutation-controls
  []
  (let [rules (control-rules)
        root [:document :view]
        plan (seal-plan rules root)
        missing-member
        (-> plan
            (update :indexes update-first-vector pop)
            reseal)
        swapped-tie
        (-> plan (update :indexes swap-tied-vector) reseal)
        duplicate-ordinal
        (-> plan
            (assoc-in [:rules 1 :ordinal]
                      (get-in plan [:rules 0 :ordinal]))
            reseal)
        wrong-distance
        (-> plan
            (update-in [:certificate :distance 0] inc)
            reseal)
        out-of-range-witness
        (let [root-index (:root-index plan)
              node (first (remove #{root-index} (range (count (:nodes plan)))))]
          (-> plan
              (assoc-in [:certificate :witness-edge node]
                        (inc (count (:edges plan))))
              reseal))
        local-only-rules
        (mapv #(assoc % :rank (local-read-cost %)) (:rules plan))
        local-cost-only
        (-> plan
            (assoc :rules local-only-rules)
            (assoc :indexes (rerank-indexes local-only-rules))
            reseal)
        host-order-rules
        (vec (reverse (:rules plan)))
        host-order
        (-> plan
            (assoc :rules host-order-rules)
            (assoc :indexes (rerank-indexes host-order-rules))
            reseal)
        counterexample (transitive-rank-counterexample 64)]
    {:valid-control? (valid-plan? rules root plan)
     :missing-member-killed? (not (valid-plan? rules root missing-member))
     :swapped-tie-killed? (not (valid-plan? rules root swapped-tie))
     :duplicate-ordinal-killed? (not (valid-plan? rules root duplicate-ordinal))
     :wrong-distance-killed? (not (valid-plan? rules root wrong-distance))
     :out-of-range-witness-killed?
     (not (valid-plan? rules root out-of-range-witness))
     :local-cost-only-killed? (not (valid-plan? rules root local-cost-only))
     :host-order-killed? (not (valid-plan? rules root host-order))
     :transitive-rank-work-gap?
     (= {:ranked-root-position 0
         :local-only-root-position 64
         :minimum-avoided-seed-scans 64}
        counterexample)}))

(defn run-bridge!
  ([] (run-bridge! 70193 32))
  ([seed case-count]
   (let [rng (Random. (long seed))
         started (System/nanoTime)
         failures (into [] (keep (fn [_] (random-case rng)))
                        (range case-count))
         controls (mutation-controls)
         elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
     (when (or (seq failures) (not (every? true? (vals controls))))
       (throw (ex-info "Sealed-plan refinement bridge failed."
                       {:seed seed
                        :case-count case-count
                        :failures (take 3 failures)
                        :controls controls})))
     {:seed seed
      :case-count case-count
      :permutation-replays case-count
      :distance-oracle-comparisons case-count
      :mutation-controls controls
      :mutation-control-count (count controls)
      :elapsed-ms elapsed-ms})))

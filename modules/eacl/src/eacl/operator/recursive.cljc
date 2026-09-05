(ns eacl.operator.recursive
  "Bounded tabled evaluation for recursive operator membership questions.

  The engine discovers only questions reachable from the demanded typed point
  contexts.  It then evaluates the signed question graph dependency-first.
  Positive components use a deterministic fact worklist; intersection state
  is materialized only after its sealed anchor fact exists.  Negative edges
  may consume absence only after their dependency component is complete."
  (:require [eacl.authorization.evidence :as evidence]
            [eacl.authorization.evidence-index :as evidence-index]
            [eacl.authorization.qualification :as qualification]
            [eacl.relationships.edge :as edge]
            [eacl.backend.direct-membership :as direct]
            [eacl.backend.v8 :as backend]
            [eacl.execution :as execution]
            [eacl.operator.batch-schedule :as batch-schedule]
            [eacl.operator.plan :as operator-plan]
            [eacl.request.counters :as request-counters]
            [eacl.secure-format :as secure-format]
            [eacl.subproblem-cache :as subproblem]))

(def checkpoint-version 1)
(def checkpoint-domain "eacl.operator.recursive-checkpoint.v1")

(def default-limits
  {:maximum-questions 100000
   :maximum-facts 100000
   :maximum-anchor-states 100000
   :maximum-join-slots 1000000
   :maximum-join-words 100000
   :maximum-evidence-cells 1000000
   :maximum-components 100000
   :maximum-strata 1024
   :maximum-commands 1000000
   :maximum-values 4000000
   :maximum-probes 1000000
   :maximum-transitions 4000000
   :maximum-queue 1000000
   :maximum-checkpoint-weight 10000000
   :physical-chunk-size 64})

(def ^:dynamic *recursive-stats*
  "Optional observation-only atom containing exact recursive dimensions."
  nil)

(defn recursive-plan?
  "True when the signed permission graph contains a positive recursive
  component reachable from the sealed root."
  [plan]
  (let [certificate (:dependency-certificate plan)]
    (boolean
     (or (some #(> (count %) 1) (:components certificate))
         (some #(and (= :positive (:sign %))
                     (= (:from %) (:to %)))
               (:edges certificate))))))

(defn- observe! [counters]
  (when *recursive-stats* (reset! *recursive-stats* counters))
  nil)

(defn- invalid! [reason message data]
  (throw
   (ex-info message
            (merge {:type :eacl.operator/invalid-recursive-evaluation
                    :eacl/error :eacl.operator/invalid-recursive-evaluation
                    :reason reason}
                   data))))

(defn- limit! [dimension maximum actual counters]
  (throw
   (ex-info "Recursive operator evaluation exceeded a configured limit."
            {:type :eacl.operator/recursive-limit-exceeded
             :eacl/error :eacl.operator/recursive-limit-exceeded
             :dimension dimension :maximum maximum :actual actual
             :counters counters})))

(def ^:private known-limit-keys (set (keys default-limits)))

(defn- normalize-limits [limits]
  ;; The engine's callers pass no overrides; the closed defaults are already
  ;; a complete normalized map.
  (cond
    (nil? limits)
    default-limits

    (not (map? limits))
    (invalid! :invalid-limits "Recursive limits must be a map."
              {:value-type (some-> limits type str)})

    :else
    (do
      (when-let [unknown (seq (remove known-limit-keys (keys limits)))]
        (invalid! :unknown-limit "Recursive limits contain unknown keys."
                  {:unknown-keys (vec unknown)}))
      (when-not (every? (fn [[_ value]]
                          (and (integer? value) (pos? value)))
                        limits)
        (invalid! :invalid-limit
                  "Recursive limits must be positive integers."
                  {:limits limits}))
      (merge default-limits limits))))

(defn- limit-counter! [limits counters dimension limit-key actual]
  (let [maximum (get limits limit-key)]
    (when (> actual maximum)
      (limit! dimension maximum actual @counters))))

(defn- add-counter!
  ([counters key] (add-counter! counters key 1))
  ([counters key amount]
   (vswap! counters update key (fnil + 0) amount)))

(defn- question
  [permission node-id direction subject-type subject-eid resource-eid]
  [permission node-id direction subject-type subject-eid resource-eid])

(defn- question-permission [q] (nth q 0))
(defn- question-node [q] (nth q 1))
(defn- question-direction [q] (nth q 2))
(defn- question-subject-type [q] (nth q 3))
(defn- question-subject-eid [q] (nth q 4))
(defn- question-resource-eid [q] (nth q 5))

(defn- question-key [q]
  ;; pr-str is portable across the closed key domain and removes host map/set
  ;; iteration from command, component, fact, and checkpoint order.
  (pr-str q))

(defn- sorted-questions [questions]
  ;; Decorate-sort-undecorate: `sort-by question-key` re-runs `pr-str` on
  ;; both operands at every comparison (the same finding
  ;; `sealed-plan/sort-by-canonical` documents with measurements). The
  ;; canonical order is unchanged — both compare the same strings.
  (mapv second
        (sort-by first compare
                 (map (fn [q] [(question-key q) q]) questions))))

(defn- candidate->root-question [roots permission candidate]
  (let [root-id (get roots permission)]
    (when-not (some? root-id)
      (invalid! :missing-root "Recursive plan root expression is missing."
                {:permission permission}))
    (question permission root-id (:direction candidate)
              (:subject-type candidate) (:subject-eid candidate)
              (:resource-eid candidate))))

(defn- validate-candidate! [candidate]
  (when-not (and (map? candidate)
                 (contains? #{:forward :reverse} (:direction candidate))
                 (keyword? (:subject-type candidate))
                 (integer? (:subject-eid candidate))
                 (not (neg? (:subject-eid candidate)))
                 (integer? (:resource-eid candidate))
                 (not (neg? (:resource-eid candidate))))
    (invalid! :invalid-candidate
              "Recursive candidate must contain a complete typed point context."
              {:candidate candidate})))

(defn- direct-probe [q descriptor]
  (when-let [{:keys [relation-id]}
             (operator-plan/relation-partition
              descriptor (question-subject-type q))]
    (if (= :forward (question-direction q))
      {:direction :forward
       :descriptor
       {:subject-type (question-subject-type q)
        :subject-eid (question-subject-eid q)
        :relation-eid relation-id
        :resource-type (first (question-permission q))}
       :candidate [(first (question-permission q))
                   (question-resource-eid q)]}
      {:direction :reverse
       :descriptor
       {:resource-type (first (question-permission q))
        :resource-eid (question-resource-eid q)
        :relation-eid relation-id
        :subject-type (question-subject-type q)}
       :candidate [(question-subject-type q)
                   (question-subject-eid q)]})))

(defn- arrow-target-question
  [roots q intermediate-eid {:keys [target-node]}]
  (let [root-id (get roots target-node)]
    (when-not (some? root-id)
      (invalid! :missing-arrow-target
                "Recursive arrow target permission is outside the plan."
                {:target target-node}))
    (question target-node root-id (question-direction q)
              (question-subject-type q) (question-subject-eid q)
              intermediate-eid)))

(defn- scan-intermediate-chunk!
  [resource->subjects! q partition bound limits counters qualification]
  (let [descriptor
        {:resource-type (first (question-permission q))
         :resource-eid (question-resource-eid q)
         :relation-eid (:via-relation-eid partition)
         :intermediate-type (:intermediate-type partition)}
        chunk-size (:physical-chunk-size limits)
        ;; `:limit` lets natively paging adapters (Datalevin) stop at the
        ;; chunk instead of materializing the whole slice that `take`
        ;; then discards; lazy adapters ignore it.
        options (cond-> {:direction :asc :limit chunk-size}
                  qualification (assoc :include-qualifier? true)
                  (some? bound)
                  (assoc :bound-eid bound :inclusive-bound? false))
        _ (execution/check! execution/*contract*
                            :operator-recursive/arrow-scan-before
                            #(hash-map :commands (:commands @counters)
                                       :values (:values @counters)))
        next-commands (inc (:commands @counters))
        _ (limit-counter! limits counters :commands
                          :maximum-commands next-commands)
        values
        (into [] (take chunk-size)
              (resource->subjects!
               (:resource-type descriptor)
               (:resource-eid descriptor)
               (:relation-eid descriptor)
               (:intermediate-type descriptor)
               options))
        fetched (count values)
        next-values (+ (:values @counters) fetched)]
    (vswap! counters assoc :commands next-commands)
    (request-counters/add-commands!)
    (request-counters/add-fetched-values! fetched)
    (limit-counter! limits counters :values :maximum-values next-values)
    (vswap! counters assoc :values next-values)
    (execution/check! execution/*contract*
                      :operator-recursive/arrow-scan-after
                      #(hash-map :commands next-commands
                                 :fetched-values fetched
                                 :values next-values))
    values))

(defn- node-spec!
  [plan roots q]
  (let [permission (question-permission q)
        node-id (question-node q)
        predicate (get-in plan [:predicate-programs permission node-id])
        instruction (:instruction predicate)
        child-q #(question permission % (question-direction q)
                           (question-subject-type q)
                           (question-subject-eid q)
                           (question-resource-eid q))]
    (case instruction
      :direct-membership
      {:key q :kind :base :dependencies []
       :base-probes (if-let [probe (direct-probe q (:descriptor predicate))]
                      [probe] [])}

      :permission-membership
      (let [target (:target-node predicate)
            target-root (get roots target)]
        (when-not (some? target-root)
          (invalid! :missing-target
                    "Recursive permission target is outside the plan."
                    {:target target}))
        {:key q :kind :union
         :dependencies
         [{:key (question target target-root (question-direction q)
                          (question-subject-type q)
                          (question-subject-eid q)
                          (question-resource-eid q))
           :sign :positive :slot 0}]
         :base-probes []})

      :any-true
      {:key q :kind :union
       :dependencies
       (mapv (fn [slot child]
               {:key (child-q child) :sign :positive :slot slot})
             (range) (:children predicate))
       :base-probes []}

      :all-true
      (let [children (:children predicate)
            anchor (get-in plan [:anchors permission node-id])
            anchor-slot (first (keep-indexed #(when (= anchor %2) %1)
                                             children))]
        (when (nil? anchor-slot)
          (invalid! :missing-anchor
                    "Recursive intersection is missing its sealed anchor."
                    {:permission permission :node-id node-id
                     :anchor anchor :children children}))
        {:key q :kind :intersection :anchor-slot anchor-slot
         :dependencies
         (mapv (fn [slot child]
                 {:key (child-q child) :sign :positive :slot slot})
               (range) children)
         :base-probes []})

      :left-and-not-right
      {:key q :kind :exclusion
       :dependencies
       [{:key (child-q (:left predicate)) :sign :positive :slot 0}
        {:key (child-q (:right predicate)) :sign :negative :slot 1}]
       :base-probes []}

      :arrow-membership
      {:key q :kind :union
       :dependencies []
       :base-probes []
       :arrow-state
       {:partitions (:partitions (:descriptor predicate))
        :partition-index 0
        :bound nil
        :complete? false}}

      (invalid! :unknown-instruction
                "Recursive plan contains an unknown predicate instruction."
                {:permission permission :node-id node-id
                 :instruction instruction}))))

(defn- discover-graph!
  ([plan roots root-questions limits counters]
   (discover-graph! plan roots root-questions limits counters {}))
  ([plan roots root-questions limits counters initial-nodes]
   (loop [queue (vec root-questions)
          index 0
          queued (set root-questions)
          nodes initial-nodes]
     (execution/check! execution/*contract*
                       :operator-recursive/discovery
                       #(hash-map :questions (count nodes)
                                  :queue (- (count queue) index)))
     (if (= index (count queue))
       nodes
       (let [q (nth queue index)]
         (if (contains? nodes q)
           (recur queue (inc index) queued nodes)
           (let [spec (node-spec! plan roots q)
                 dependencies (mapv :key (:dependencies spec))
                 fresh
                 (vec
                  (remove #(or (contains? nodes %)
                               (contains? queued %))
                          dependencies))
                 next-questions (+ (count nodes) 1 (count fresh))
                 next-queue (+ (- (count queue) (inc index))
                               (count fresh))]
             (limit-counter! limits counters :questions
                             :maximum-questions next-questions)
             (limit-counter! limits counters :queue
                             :maximum-queue next-queue)
             (vswap! counters update :maximum-queue max next-queue)
             (recur (into queue fresh) (inc index) (into queued fresh)
                    (assoc nodes q spec)))))))))

(defn- finishing-order [vertices adjacency]
  (loop [remaining (seq (sorted-questions vertices))
         visited #{}
         order []]
    (if-let [start (first remaining)]
      (if (contains? visited start)
        (recur (next remaining) visited order)
        (let [[visited order]
              (loop [stack [[start false]] visited visited order order]
                (if-let [[node expanded?] (peek stack)]
                  (cond
                    expanded?
                    (recur (pop stack) visited (conj order node))

                    (contains? visited node)
                    (recur (pop stack) visited order)

                    :else
                    (let [children
                          (vec (reverse
                                (sorted-questions (get adjacency node []))))]
                      (recur (into (conj (pop stack) [node true])
                                   (map #(vector % false)) children)
                             (conj visited node) order)))
                  [visited order]))]
          (recur (next remaining) visited order)))
      order)))

(defn- collect-component [start reverse-adjacency visited]
  (loop [stack [start] visited visited component []]
    (if-let [node (peek stack)]
      (if (contains? visited node)
        (recur (pop stack) visited component)
        (let [children
              (vec (reverse
                    (sorted-questions (get reverse-adjacency node []))))]
          (recur (into (pop stack) children)
                 (conj visited node) (conj component node))))
      [visited (vec (sorted-questions component))])))

(defn- strongly-connected-components [nodes]
  (let [vertices (keys nodes)
        adjacency
        (into {} (map (fn [[q spec]]
                        [q (mapv :key (:dependencies spec))])) nodes)
        reverse-adjacency
        (reduce-kv
         (fn [result from children]
           (reduce #(update %1 %2 (fnil conj []) from) result children))
         (zipmap vertices (repeat [])) adjacency)
        order (finishing-order vertices adjacency)]
    (loop [remaining (rseq (vec order)) visited #{} components []]
      (if-let [start (first remaining)]
        (if (contains? visited start)
          (recur (next remaining) visited components)
          (let [[visited component]
                (collect-component start reverse-adjacency visited)]
            (recur (next remaining) visited (conj components component))))
        {:components components :adjacency adjacency}))))

(defn- dependency-first-components [nodes components]
  (let [component-of
        (into {} (mapcat (fn [index component]
                           (map #(vector % index) component))
                         (range) components))
        dependencies
        (into {}
              (map-indexed
               (fn [index component]
                 [index
                  (into #{}
                        (comp
                         (mapcat #(get-in nodes [% :dependencies]))
                         (map (comp component-of :key))
                         (remove #{index}))
                        component)]))
              components)
        component-key #(question-key (first (nth components %)))
        starts (mapv second
                     (sort-by first compare
                              (map (fn [index] [(component-key index) index])
                                   (range (count components)))))
        order
        (loop [remaining starts visited #{} order []]
          (if-let [start (first remaining)]
            (if (contains? visited start)
              (recur (next remaining) visited order)
              (let [[visited order]
                    (loop [stack [[start false]]
                           visited visited
                           order order]
                      (if-let [[component expanded?] (peek stack)]
                        (cond
                          expanded?
                          (recur (pop stack) visited (conj order component))

                          (contains? visited component)
                          (recur (pop stack) visited order)

                          :else
                          (let [children
                                (vec
                                 (reverse
                                  (sort-by component-key
                                           (get dependencies component))))]
                            (recur
                             (into (conj (pop stack) [component true])
                                   (map #(vector % false)) children)
                             (conj visited component) order)))
                        [visited order]))]
                (recur (next remaining) visited order)))
            order))]
    (when-not (= (count order) (count components))
      (invalid! :component-cycle
                "Recursive component condensation graph is cyclic."
                {:completed (count order)
                 :components (count components)}))
    {:components components :component-of component-of
     :dependencies dependencies :order order}))

(defn- validate-negative-components! [nodes component-of]
  (doseq [[head spec] nodes
          {:keys [key sign]} (:dependencies spec)
          :when (and (= :negative sign)
                     (= (component-of head) (component-of key)))]
    (invalid! :negative-cycle
              "Recursive question graph contains a negative cycle."
              {:head head :dependency key
               :component (component-of head)})))

(defn- expand-arrow-chunk!
  [resource->subjects! plan roots nodes q limits counters qualification]
  (let [{:keys [arrow-state] :as spec} (get nodes q)
        {:keys [partitions partition-index bound complete?]} arrow-state]
    (when-not arrow-state
      (invalid! :not-an-arrow-question
                "Demand expansion selected a non-arrow question."
                {:question q}))
    (if complete?
      nodes
      (if (>= partition-index (count partitions))
        (assoc-in nodes [q :arrow-state :complete?] true)
        (let [partition (nth partitions partition-index)
              values (scan-intermediate-chunk!
                      resource->subjects! q partition bound limits counters qualification)
              first-slot (count (:dependencies spec))
              permission-target? (= :permission (:target-kind partition))
              first-probe (count (:base-probes spec))
              [dependencies probes probe-vias inactive]
              (reduce
               (fn [[dependencies probes probe-vias inactive] compact-edge]
                 (let [intermediate-eid (edge/endpoint compact-edge)
                       via (if qualification
                             (qualification/qualify qualification (:via-relation-eid partition) compact-edge)
                             true)]
                   (cond
                     (or (evidence/no? via) (evidence/fault? via))
                     [dependencies probes probe-vias (evidence/combine :union inactive via)]

                     permission-target?
                     [(conj dependencies
                            (cond-> {:key (arrow-target-question roots q intermediate-eid partition)
                                     :sign :positive :slot (+ first-slot (count dependencies))}
                              (not (true? via)) (assoc :via via)))
                      probes probe-vias inactive]

                     :else
                     (if-let [probe (direct-probe
                                     (question [(:intermediate-type partition) (:target-name partition)]
                                               -1 (question-direction q) (question-subject-type q)
                                               (question-subject-eid q) intermediate-eid)
                                     (:target-relation partition))]
                       [dependencies (conj probes probe)
                        (if (true? via) probe-vias (assoc probe-vias (+ first-probe (count probes)) via))
                        inactive]
                       [dependencies probes probe-vias inactive]))))
               [[] [] (:base-probe-vias spec) false]
               values)
              short-chunk? (< (count values)
                              (:physical-chunk-size limits))
              next-partition (if short-chunk?
                               (inc partition-index)
                               partition-index)
              next-state
              {:partitions partitions
               :partition-index next-partition
               :bound (when-not short-chunk? (edge/endpoint (peek values)))
               :complete? (and short-chunk?
                               (>= next-partition
                                   (count partitions)))}
              nodes
              (-> nodes
                  (update-in [q :dependencies] into dependencies)
                  (update-in [q :base-probes] into probes)
                  (assoc-in [q :arrow-state] next-state))
              nodes (cond-> nodes
                      (seq probe-vias) (assoc-in [q :base-probe-vias] probe-vias)
                      (or (seq probe-vias) (some :via dependencies) (not (false? inactive)))
                      (assoc-in [q :qualified-evidence?] true)
                      (not (false? inactive))
                      (update-in [q :base-evidence] #(evidence/combine :union (or % false) inactive)))
              dependency-questions (mapv :key dependencies)]
          (add-counter! counters :arrow-chunks)
          (discover-graph! plan roots dependency-questions
                           limits counters nodes))))))

(defn- rule-bounds
  [rule lower-facts upper-facts]
  (let [{:keys [kind dependencies base-true? arrow-state]} rule
        weighted? (:qualified-evidence? rule)
        base (get rule :base-evidence (boolean base-true?))
        base-lower? (if weighted? (evidence/has? base) (boolean base-true?))
        base-upper? (if weighted? (not (evidence/no? base)) (boolean base-true?))
        lower? (if weighted?
                 #(and (evidence/has? (get % :via true)) (contains? lower-facts (:key %)))
                 #(contains? lower-facts (:key %)))
        upper? (if weighted?
                 #(and (not (evidence/no? (get % :via true))) (contains? upper-facts (:key %)))
                 #(contains? upper-facts (:key %)))
        incomplete-arrow?
        (and arrow-state (not (:complete? arrow-state)))]
    (case kind
      :base
      [base-lower? base-upper?]

      :union
      [(boolean (or base-lower? (some lower? dependencies)))
       (boolean (or base-upper? (some upper? dependencies)
                    incomplete-arrow?))]

      :intersection
      [(every? lower? dependencies)
       (every? upper? dependencies)]

      :exclusion
      (let [[left right] dependencies]
        [(and (lower? left) (not (upper? right)))
         (and (upper? left) (not (lower? right)))])

      [false false])))

(defn- condense-graph
  "Strongly connected components of the discovered graph in dependency-first
  order, with the negative-cycle check. Components come out of
  `collect-component` already in canonical question order."
  [nodes]
  (let [{:keys [components]} (strongly-connected-components nodes)
        {:keys [component-of order]}
        (dependency-first-components nodes components)]
    (validate-negative-components! nodes component-of)
    {:components components :component-of component-of :order order}))

(defn- solve-bounds
  "Computes lower and upper least fixed points for the discovered graph.
  Unexhausted arrow continuations are false in the lower bound and possible in
  the upper bound. Strictly lower negative dependencies reverse their bound at
  exclusion, so the result remains sound in the presence of negation. Returns
  the condensation alongside the bounds so the final exact pass can reuse it."
  [nodes]
  (let [{:keys [components order] :as graph} (condense-graph nodes)]
    (loop [remaining order lower-facts #{} upper-facts #{}]
      (if-let [component-index (first remaining)]
        (let [component (nth components component-index)
              [lower-facts upper-facts]
              (loop [lower-facts lower-facts upper-facts upper-facts]
                (let [[next-lower next-upper]
                      (reduce
                       (fn [[lower upper] q]
                         (let [[lower-true? upper-true?]
                               (rule-bounds (get nodes q) lower upper)]
                           [(cond-> lower lower-true? (conj q))
                            (cond-> upper upper-true? (conj q))]))
                       [lower-facts upper-facts]
                       component)]
                  (if (and (= lower-facts next-lower)
                           (= upper-facts next-upper))
                    [lower-facts upper-facts]
                    (recur next-lower next-upper))))]
          (recur (rest remaining) lower-facts upper-facts))
        (assoc graph :lower lower-facts :upper upper-facts)))))

(defn- uncertain-arrow-questions
  [nodes root-questions lower-facts upper-facts known-questions]
  (letfn [(exact? [q]
            (and (contains? known-questions q)
                 (= (contains? lower-facts q)
                    (contains? upper-facts q))))
          (uncertain-dependencies [rule]
            (let [{:keys [kind dependencies]} rule]
              (case kind
                (:union :intersection)
                (mapv :key (filter #(not (exact? (:key %))) dependencies))

                :exclusion
                (let [[left right] dependencies]
                  (cond-> []
                    (not (exact? (:key left))) (conj (:key left))
                    (not (exact? (:key right))) (conj (:key right))))

                [])))
          (first-arrow [root]
            (loop [queue [root] index 0 visited #{}]
              (when (< index (count queue))
                (let [q (nth queue index)]
                  (cond
                    (or (contains? visited q) (exact? q))
                    (recur queue (inc index) (conj visited q))

                    (and (get-in nodes [q :arrow-state])
                         (not (get-in nodes [q :arrow-state :complete?])))
                    q

                    :else
                    (recur (into queue
                                 (uncertain-dependencies (get nodes q)))
                           (inc index) (conj visited q)))))))]
    ;; Expand at most one arrow frontier per demanded root in a round. This
    ;; batches independent candidates without speculatively reading sibling
    ;; union branches or non-anchor intersection branches.
    (->> root-questions
         (keep first-arrow)
         distinct
         vec)))

(defn- expand-arrow-wave!
  [resource->subjects! plan roots nodes initial-arrows lower-facts upper-facts
   known-questions width limits counters qualification]
  (loop [nodes nodes
         frontier (vec initial-arrows)
         remaining width]
    (if (or (zero? remaining) (empty? frontier))
      nodes
      (let [batch (vec (take remaining frontier))
            queued (vec (drop (count batch) frontier))
            nodes
            (reduce
             (fn [current q]
               (expand-arrow-chunk!
                resource->subjects! plan roots current q limits counters qualification))
             nodes batch)
            descendants
            (mapcat
             #(uncertain-arrow-questions
               nodes [%] lower-facts upper-facts known-questions)
             batch)
            frontier (vec (distinct (into queued descendants)))]
        (recur nodes frontier (- remaining (count batch)))))))

(defn- finalize-unused-arrows [nodes]
  (reduce-kv
   (fn [result q rule]
     (if (and (:arrow-state rule)
              (not (get-in rule [:arrow-state :complete?])))
       (-> result (assoc-in [q :arrow-state :complete?] true)
           (assoc-in [q :incomplete-evidence?] true))
       result))
   nodes nodes))

(defn- portable-word-count [width]
  (quot (+ width 31) 32))

(defn- empty-words [width]
  (vec (repeat (portable-word-count width) 0)))

(defn- word-bit-set? [words slot]
  (not (zero? (bit-and (nth words (quot slot 32))
                       (bit-shift-left 1 (mod slot 32))))))

(defn- set-word-bit [words slot]
  (let [word (quot slot 32)
        bit (mod slot 32)]
    (assoc words word
           (bit-or 0 (bit-or (nth words word)
                             (bit-shift-left 1 bit))))))

(defn- join-from-facts [rule facts]
  (let [children (mapv :key (:dependencies rule))
        indexes (keep-indexed #(when (contains? facts %2) %1) children)]
    {:width (count children)
     :words (reduce set-word-bit (empty-words (count children)) indexes)
     :satisfied (count indexes)}))

(defn- reserve-join! [state rule limits counters]
  (let [width (count (:dependencies rule))
        words (portable-word-count width)
        next-states (inc (count (:join-states state)))
        next-slots (+ (:join-slots @counters) width)
        next-words (+ (:join-words @counters) words)]
    (limit-counter! limits counters :anchor-states
                    :maximum-anchor-states next-states)
    (limit-counter! limits counters :join-slots
                    :maximum-join-slots next-slots)
    (limit-counter! limits counters :join-words
                    :maximum-join-words next-words)
    (vswap! counters assoc :anchor-states next-states
            :join-slots next-slots :join-words next-words)
    (let [join (join-from-facts rule (:facts state))
          prior (dec (:satisfied join))]
      (when (pos? prior)
        (add-counter! counters :late-anchor-initialized-slots prior))
      (assoc-in state [:join-states (:key rule)] join))))

(defn update-join
  "Admits one distinct satisfied child slot into an allocated join state.
  Repeated admission of the same slot is idempotent."
  [state rule slot]
  (let [join (get-in state [:join-states (:key rule)])]
    (if (or (nil? join) (word-bit-set? (:words join) slot))
      state
      (assoc-in state [:join-states (:key rule)]
                (-> join
                    (update :words set-word-bit slot)
                    (update :satisfied inc))))))

(defn join-complete?
  "True only when every sealed child slot has been satisfied."
  [state rule]
  (let [join (get-in state [:join-states (:key rule)])]
    (and join (= (:width join) (:satisfied join)))))

(defn join-transition-action
  "Returns the only permitted state transition for an arriving intersection
  fact. Non-anchor facts cannot allocate retained parent state."
  [state rule slot]
  (cond
    (contains? (:join-states state) (:key rule)) :update
    (= slot (:anchor-slot rule)) :reserve
    :else :ignore))

(defn exclusion-decision
  "Classifies strict exclusion from completed-component state and exact facts.
  Absence in an unfinished negative component is never authorization."
  [completed right-component facts left right]
  (cond
    (not (contains? completed right-component)) :incomplete
    (and (contains? facts left) (not (contains? facts right))) :authorize
    :else :deny))

(defn- component-consumers [nodes component]
  ;; Buckets are sorted afterwards by a total key, so iteration order here
  ;; is irrelevant and the component vector is consumed directly.
  (reduce
   (fn [result head]
     (let [spec (get nodes head)]
       (case (:kind spec)
         :union
         (reduce (fn [m {:keys [key slot]}]
                   (update m key (fnil conj [])
                           {:kind :unary :head head :slot slot}))
                 result (:dependencies spec))

         :intersection
         (reduce (fn [m {:keys [key slot]}]
                   (update m key (fnil conj [])
                           {:kind :intersection :head head :slot slot}))
                 result (:dependencies spec))

         :exclusion
         (let [[left right] (:dependencies spec)]
           (update result (:key left) (fnil conj [])
                   {:kind :exclusion :head head :right (:key right)}))

         result)))
   {} component))

(defn- sorted-consumer-bucket
  "Deterministic consumer order, keys computed once per element. The
  fact-dequeue loop previously re-sorted every bucket per dequeued fact
  with `pr-str` inside the comparator."
  [bucket]
  (mapv second
        (sort-by first compare
                 (map (fn [consumer]
                        [[(str (:kind consumer))
                          (question-key (:head consumer))
                          (:slot consumer)]
                         consumer])
                      bucket))))

(defn- enqueue-fact! [state fact limits counters]
  (if (contains? (:facts state) fact)
    (do (add-counter! counters :duplicate-facts) state)
    (let [next-facts (inc (count (:facts state)))
          next-queue (inc (- (count (:agenda state)) (:agenda-index state)))]
      (limit-counter! limits counters :facts :maximum-facts next-facts)
      (limit-counter! limits counters :queue :maximum-queue next-queue)
      (vswap! counters #(-> %
                            (assoc :facts next-facts)
                            (update :maximum-queue max next-queue)))
      (-> state
          (update :facts conj fact)
          (update :agenda conj fact)))))

(defn- initialize-component
  [state nodes component completed component-of limits counters]
  (reduce
   (fn [state head]
     (let [{:keys [kind dependencies base-true? anchor-slot] :as rule}
           (get nodes head)]
       (case kind
         :base (if base-true?
                 (enqueue-fact! state head limits counters)
                 state)

         :union
         (if (or base-true?
                 (some #(contains? (:facts state) (:key %)) dependencies))
           (enqueue-fact! state head limits counters)
           state)

         :intersection
         (let [anchor (:key (nth dependencies anchor-slot))]
           (if (contains? (:facts state) anchor)
             (let [state (reserve-join! state rule limits counters)]
               (if (join-complete? state rule)
                 (enqueue-fact! state head limits counters)
                 state))
             state))

         :exclusion
         (let [[left right] dependencies
               right-component (component-of (:key right))
               decision
               (exclusion-decision completed right-component (:facts state)
                                   (:key left) (:key right))]
           (case decision
             :incomplete
             (invalid! :incomplete-negative-component
                       "Exclusion attempted to consume an incomplete dependency."
                       {:head head :right (:key right)
                        :right-component right-component})

             :authorize (enqueue-fact! state head limits counters)
             :deny state))

         state)))
   state component))

(defn- evidence-step! [limits counters]
  (let [next (inc (:transitions @counters))]
    (limit-counter! limits counters :transitions :maximum-transitions next)
    (vswap! counters assoc :transitions next)
    (execution/check! execution/*contract* :operator-recursive/evidence-transition)))

(defn- dependency-value [state dependency]
  (evidence/combine :arrow (get dependency :via true)
                    (get (:facts state) (:key dependency) false)))

(defn- enqueue-evidence! [state head value limits counters]
  (if (= value (get (:facts state) head false))
    state
    (let [facts (assoc (:facts state) head value)
          pending? (contains? (:queued state) head)
          queue-size (+ (- (count (:agenda state)) (:agenda-index state)) (if pending? 0 1))]
      (limit-counter! limits counters :facts :maximum-facts (count facts))
      (limit-counter! limits counters :queue :maximum-queue queue-size)
      (vswap! counters #(-> % (assoc :facts (count facts)) (update :maximum-queue max queue-size)))
      (cond-> (assoc state :facts facts)
        (not pending?) (update :agenda conj head)
        (not pending?) (update :queued conj head)))))

(defn- unanchored-evidence [state rule slot anchor]
  ;; No join is retained before an anchor has a nonempty completion set.
  ;; A false anchor supplies a conservative certificate by itself. Already
  ;; encountered faults remain authoritative even without an allocated join.
  (if (nil? slot)
    (or (reduce (fn [fault dependency]
                  (let [value (dependency-value state dependency)]
                    (if (evidence/fault? value)
                      (if fault (evidence/combine :intersection fault value) value)
                      fault))) nil (:dependencies rule))
        anchor)
    (let [child (dependency-value state (nth (:dependencies rule) slot))
          prior (get (:facts state) (:key rule) false)]
      (cond (evidence/fault? child) (evidence/combine :intersection prior child)
            (evidence/fault? prior) prior
            :else anchor))))

(defn- charge-evidence-anchor! [rule limits counters]
  (let [states (inc (:anchor-states @counters))
        slots (+ (:join-slots @counters) (count (:dependencies rule)))
        words (+ (:join-words @counters) (portable-word-count (count (:dependencies rule))))]
    (limit-counter! limits counters :anchor-states :maximum-anchor-states states)
    (limit-counter! limits counters :join-slots :maximum-join-slots slots)
    (limit-counter! limits counters :join-words :maximum-join-words words)
    (vswap! counters assoc :anchor-states states :join-slots slots :join-words words)))

(defn- refresh-evidence-head!
  [state rule slot completed component-of limits counters]
  (let [{:keys [key kind dependencies]} rule
        base (get rule :base-evidence (boolean (:base-true? rule)))
        step! #(evidence-step! limits counters)
        [state value]
        (case kind
          :base [state base]
          :exclusion
          (let [[left right] dependencies]
            (when-not (contains? completed (component-of (:key right)))
              (invalid! :incomplete-negative-component
                        "Exclusion attempted to consume incomplete evidence." {:head key}))
            (step!)
            [state (evidence/combine :exclusion (dependency-value state left) (dependency-value state right))])
          (:union :intersection)
          (let [prior (get-in state [:join-states key])
                anchor (when (= kind :intersection)
                         (dependency-value state (nth dependencies (:anchor-slot rule))))
                unanchored? (and (= kind :intersection) (nil? prior)
                                 (or (evidence/no? anchor) (evidence/fault? anchor)))]
            (if unanchored?
              [state (unanchored-evidence state rule slot anchor)]
              (let [_ (when (and (= kind :intersection) (nil? prior))
                        (charge-evidence-anchor! rule limits counters))
                    joined (if prior
                             (evidence-index/replace-slot prior (if (= kind :union) (inc slot) slot)
                                                          (dependency-value state (nth dependencies slot)) step!)
                             (let [children (mapv #(dependency-value state %) dependencies)
                                   values (if (= kind :union)
                                            (cond-> (into [base] children)
                                              (:incomplete-evidence? rule)
                                              (conj (evidence/with-certificate false nil false)))
                                            children)]
                               (evidence-index/build kind values step!)))
                    cells (+ (get @counters :evidence-cells 0)
                             (if prior 0 (evidence-index/storage-weight joined)))]
                (limit-counter! limits counters :evidence-cells :maximum-evidence-cells cells)
                (vswap! counters assoc :evidence-cells cells)
                [(assoc-in state [:join-states key] joined) (evidence-index/value joined)]))))]
    (enqueue-evidence! state key value limits counters)))

(defn- consume-boolean-fact [state nodes {:keys [kind head slot right]} limits counters]
  (case kind
    :unary (enqueue-fact! state head limits counters)
    :exclusion (if (contains? (:facts state) right) state (enqueue-fact! state head limits counters))
    :intersection
    (let [rule (get nodes head)
          state (case (join-transition-action state rule slot)
                  :update (update-join state rule slot)
                  :reserve (reserve-join! state rule limits counters)
                  :ignore state)]
      (if (join-complete? state rule) (enqueue-fact! state head limits counters) state))
    state))

(defn- run-component!
  [state nodes component completed component-of limits counters]
  (let [consumers (update-vals (component-consumers nodes component)
                               sorted-consumer-bucket)
        state (assoc state :agenda [] :agenda-index 0)
        state (if (map? (:facts state))
                (reduce #(refresh-evidence-head! %1 (get nodes %2) nil completed component-of limits counters)
                        (assoc state :queued #{}) component)
                (initialize-component state nodes component completed component-of limits counters))]
    (loop [state state]
      (if (= (:agenda-index state) (count (:agenda state)))
        (assoc state :agenda [] :agenda-index 0)
        (let [fact (nth (:agenda state) (:agenda-index state))
              state (cond-> (update state :agenda-index inc)
                      (map? (:facts state)) (update :queued disj fact))
              next-transition (inc (:transitions @counters))]
          (limit-counter! limits counters :transitions
                          :maximum-transitions next-transition)
          (vswap! counters assoc :transitions next-transition)
          (execution/check! execution/*contract*
                            :operator-recursive/fact-transition
                            #(hash-map :transitions next-transition
                                       :facts (count (:facts state))))
          (let [state
                (reduce
                 (fn [state consumer]
                   (if (map? (:facts state))
                     (refresh-evidence-head! state (get nodes (:head consumer)) (:slot consumer)
                                             completed component-of limits counters)
                     (consume-boolean-fact state nodes consumer limits counters)))
                 state
                 (get consumers fact []))]
            (recur state)))))))

(defn- direct-memo-key [probe]
  [(:direction probe) (:descriptor probe) (:candidate probe)])

(defn- attach-base-decisions!
  "Attaches each new physical probe once, keeping its compact result in the
   owning request memo. Via evidence composes with the target at attachment."
  [adapter nodes limits counters direct-decisions qualification]
  (let [entries (into []
                      (mapcat (fn [q]
                                (let [spec (get nodes q)
                                      attached (or (:attached-probe-count spec) 0)]
                                  (map-indexed (fn [i probe] [q probe (+ attached i)])
                                               (drop attached (:base-probes spec))))))
                      (sorted-questions (keys nodes)))
        probe-count (count entries)
        next-probes (+ (:probes @counters) probe-count)]
    (limit-counter! limits counters :probes :maximum-probes next-probes)
    (vswap! counters assoc :probes next-probes)
    (if (zero? probe-count)
      nodes
      (let [physical (atom {})
            cache-lookup #(get @direct-decisions (direct-memo-key %) direct/cache-miss)
            decisions (binding [direct/*physical-stats* physical]
                        (if qualification
                          (direct/dispatch-cached-edges adapter (mapv second entries) cache-lookup)
                          (direct/dispatch adapter (mapv second entries) cache-lookup)))
            [memo heads]
            (loop [i 0 memo (transient @direct-decisions) heads {}]
              (if (= i probe-count)
                [(persistent! memo) heads]
                (let [[head probe slot] (nth entries i)
                      physical-value (nth decisions i)
                      decision (if qualification
                                 (qualification/qualify qualification (get-in probe [:descriptor :relation-eid])
                                                        physical-value)
                                 physical-value)
                      via (get-in nodes [head :base-probe-vias slot] true)
                      decision (evidence/combine :arrow via decision)
                      prior (get heads head (get-in nodes [head :base-evidence]
                                                    (boolean (get-in nodes [head :base-true?]))))]
                  (recur (inc i) (assoc! memo (direct-memo-key probe) physical-value)
                         (assoc heads head (evidence/combine :union prior decision))))))]
        (vreset! direct-decisions memo)
        (add-counter! counters :direct-adapter-commands (:adapter-commands @physical 0))
        (add-counter! counters :direct-fetched-values (:adapter-fetched-values @physical 0))
        (add-counter! counters :direct-memo-hits (:cache-hits @physical 0))
        (reduce-kv (fn [result q value]
                     (update result q
                             (fn [spec]
                               (cond-> (assoc spec :attached-probe-count (count (:base-probes spec))
                                               :base-true? (evidence/has? value))
                                 (not (boolean? value)) (assoc :base-evidence value :qualified-evidence? true)
                                 (boolean? value) (dissoc :base-evidence)))))
                   nodes heads)))))

(defn- checkpoint-weight
  [facts join-states completed-components completed-strata]
  (+ (* 7 (count facts))
     (reduce + 0
             (map (fn [[_ {:keys [width words]}]]
                    (+ 4 width (count words)))
                  join-states))
     (count completed-components)
     (count completed-strata)
     16))

(defn- command-identity
  [plan root-questions scope-identity]
  (secure-format/canonical-records-digest
   checkpoint-domain
   [[:recursive-command
     {:version checkpoint-version
      :plan-fingerprint (:fingerprint plan)
      :questions (vec root-questions)
      :scope scope-identity}]]))

(defn- make-checkpoint
  "Serializes only completed state. Qualified joins need no retained update
   tree after their fixed point; the canonical evidence facts suffice for replay."
  [identity state completed component-strata counters limits undelivered-boundary checkpoint?]
  (let [completed (vec completed)
        completed-strata (->> completed (map component-strata) distinct sort vec)
        qualified? (map? (:facts state))
        weight (if qualified?
                 (+ (* 7 (count (:facts state)))
                    (reduce + 0 (map evidence-index/storage-weight (vals (:join-states state)))))
                 (checkpoint-weight (:facts state) (:join-states state) completed completed-strata))]
    (limit-counter! limits counters :checkpoint-weight :maximum-checkpoint-weight weight)
    (vswap! counters assoc :checkpoint-weight weight)
    (when checkpoint?
      (let [facts (if qualified?
                    (first
                     (reduce (fn [[out size] q]
                               (let [encoded (evidence/encode (get (:facts state) q))
                                     size (+ size (count (secure-format/utf8-bytes encoded)))]
                                 (limit-counter! limits counters :checkpoint-weight :maximum-checkpoint-weight size)
                                 [(conj out [q encoded]) size]))
                             [[] 0] (sorted-questions (keys (:facts state)))))
                    (vec (sorted-questions (:facts state))))]
        (cond-> {:version checkpoint-version :command-identity identity :completed? true :facts facts
                 :anchor-states (if qualified? []
                                    (mapv second (sort-by first compare
                                                        (map (fn [[key value]] [(question-key key) [key value]])
                                                             (:join-states state)))))
                 :completed-components completed :completed-strata completed-strata
                 :pending-negative-questions [] :pending-commands []
                 :undelivered-boundary undelivered-boundary :counters @counters}
          qualified? (assoc :evidence-format evidence/format-version))))))

(defn- replay-checkpoint [checkpoint identity root-questions]
  (when-not (and (map? checkpoint) (= checkpoint-version (:version checkpoint))
                 (= identity (:command-identity checkpoint)) (true? (:completed? checkpoint))
                 (vector? (:facts checkpoint)) (vector? (:anchor-states checkpoint))
                 (vector? (:completed-components checkpoint)) (vector? (:completed-strata checkpoint))
                 (vector? (:pending-negative-questions checkpoint)) (empty? (:pending-negative-questions checkpoint))
                 (vector? (:pending-commands checkpoint)) (empty? (:pending-commands checkpoint))
                 (or (not (contains? checkpoint :evidence-format))
                     (= evidence/format-version (:evidence-format checkpoint))))
    (invalid! :invalid-checkpoint "Recursive checkpoint is malformed or incompatible."
              {:checkpoint-version (:version checkpoint)}))
  (let [qualified? (contains? checkpoint :evidence-format)
        facts (if qualified?
                (into {} (map (fn [[q encoded]] [q (evidence/decode encoded)])) (:facts checkpoint))
                (set (:facts checkpoint)))]
    {:decisions (if qualified? (mapv #(get facts % false) root-questions)
                    (mapv #(contains? facts %) root-questions))
     :checkpoint checkpoint :counters (:counters checkpoint) :replayed? true}))

(defn- validate-many-options!
  [plan candidates]
  (when-not (operator-plan/operator-plan? plan)
    (invalid! :operator-plan-required
              "Recursive evaluation requires an operator plan."
              {:plan-domain (:domain plan)}))
  (when-not (vector? candidates)
    (invalid! :invalid-candidates
              "Recursive candidates must be a vector."
              {:value-type (some-> candidates type str)}))
  (doseq [candidate candidates] (validate-candidate! candidate)))

(declare evaluate-many-validated)

(defn evaluate-many
  "Evaluates an aligned vector of recursive operator point questions.

  Results are all-or-error.  `:checkpoint`, when supplied, must be a complete
  compatible portable checkpoint and replays without backend work."
  [{:keys [plan candidates] :as options}]
  (validate-many-options! plan candidates)
  (evaluate-many-validated (update options :limits normalize-limits)))

(defn- evaluate-many-validated
  "Trusted core of `evaluate-many`: options already validated and limits
  normalized (each caller validates exactly once at its boundary)."
  [{:keys [adapter plan candidates permission limits checkpoint
           scope-identity undelivered-boundary checkpoint? qualification]
    :or {checkpoint? true}}]
  (let [roots (operator-plan/expression-roots plan)
        permission (or permission (:root plan))
        root-questions (mapv #(candidate->root-question roots permission %)
                             candidates)
        identity (command-identity plan root-questions
                                   (if qualification
                                     [:qualified evidence/format-version scope-identity
                                      (qualification/exact-reuse-identity qualification)]
                                     scope-identity))]
    (if checkpoint
      (replay-checkpoint checkpoint identity root-questions)
      (if (empty? candidates)
        {:decisions [] :checkpoint nil :counters {} :replayed? false}
        (let [counters
              (volatile! {:questions 0 :facts 0 :anchor-states 0
                          :join-slots 0 :join-words 0 :components 0
                          :strata 0 :commands 0 :values 0 :probes 0
                          :transitions 0 :maximum-queue 0
                          :checkpoint-weight 0 :duplicate-facts 0
                          :demand-rounds 0 :arrow-chunks 0
                          :direct-adapter-commands 0
                          :direct-fetched-values 0 :direct-memo-hits 0
                          :late-anchor-initialized-slots 0})
              direct-decisions (volatile! {})
              resource->subjects!
              (backend/scan-invoker adapter :resource->subjects)
              initial-nodes
              (discover-graph! plan roots root-questions limits counters)
              [nodes bounded-decisions graph]
              (loop [nodes
                     (attach-base-decisions!
                      adapter initial-nodes limits counters
                      direct-decisions qualification)
                     wave-width
                     (min batch-schedule/maximum-width
                          (max 1 (count root-questions)))]
                (execution/check! execution/*contract*
                                  :operator-recursive/demand-discovery
                                  #(hash-map :questions (count nodes)
                                             :commands (:commands @counters)))
                (let [{:keys [lower upper] :as graph} (solve-bounds nodes)
                      lower-facts lower
                      upper-facts upper
                      decisions
                      (mapv #(contains? lower-facts %) root-questions)
                      exact?
                      (every?
                       (fn [q]
                         (= (contains? lower-facts q)
                            (contains? upper-facts q)))
                       root-questions)]
                  (if exact?
                    ;; Finalizing arrows flips only `:complete?`; the
                    ;; dependency graph, and therefore the condensation
                    ;; computed by this last round, is unchanged.
                    [(finalize-unused-arrows nodes) decisions graph]
                    (let [known-questions (set (keys nodes))
                          arrows
                          (uncertain-arrow-questions
                           nodes root-questions lower-facts upper-facts
                           known-questions)]
                      (if (empty? arrows)
                        (if qualification
                          [(finalize-unused-arrows nodes) nil graph]
                          (invalid! :stalled-demand-discovery
                                    "Recursive demand discovery could not refine an uncertain result."
                                    {:questions (count nodes) :roots (count root-questions)}))
                        (let [nodes (expand-arrow-wave! resource->subjects! plan roots nodes arrows
                                                        lower-facts upper-facts known-questions
                                                        wave-width limits counters qualification)]
                          (add-counter! counters :demand-rounds)
                          (recur (attach-base-decisions! adapter nodes limits counters direct-decisions qualification)
                                 (min batch-schedule/maximum-width (* 2 wave-width)))))))))
              annotated? (and qualification
                              (some (fn [[_ rule]]
                                      (or (and (contains? rule :base-evidence)
                                               (not (boolean? (:base-evidence rule))))
                                          (some :via (:dependencies rule)))) nodes))
              _ (vswap! counters assoc :questions (count nodes))
              {:keys [components component-of order]} graph
              component-count (count components)
              _ (limit-counter! limits counters :components
                                :maximum-components component-count)
              component-strata
              (loop [remaining order strata {}]
                (if-let [component (first remaining)]
                  (let [dependencies
                        (into #{}
                              (mapcat
                               (fn [q]
                                 (keep (fn [{:keys [key sign]}]
                                         (let [dependency (component-of key)]
                                           (when (not= component dependency)
                                             [dependency sign])))
                                       (get-in nodes [q :dependencies])))
                               (nth components component)))
                        stratum
                        (reduce
                         (fn [maximum [dependency sign]]
                           (max maximum
                                (+ (get strata dependency 0)
                                   (if (= :negative sign) 1 0))))
                         0 dependencies)]
                    (recur (rest remaining) (assoc strata component stratum)))
                  strata))
              stratum-count (count (set (vals component-strata)))
              _ (limit-counter! limits counters :strata :maximum-strata
                                stratum-count)
              _ (vswap! counters assoc :components component-count
                        :strata stratum-count)
              [state completed]
              (loop [remaining order
                     state {:facts (if annotated? {} #{}) :join-states {}
                            :agenda [] :agenda-index 0}
                     completed #{}]
                (if-let [component (first remaining)]
                  (do
                    (execution/check! execution/*contract*
                                      :operator-recursive/component
                                      #(hash-map :component component
                                                 :completed (count completed)))
                    (let [state
                          (run-component!
                           state nodes (nth components component) completed
                           component-of limits counters)]
                      (recur (rest remaining) state
                             (conj completed component))))
                  [state completed]))
              checkpoint
              (make-checkpoint identity state (sort completed)
                               component-strata counters limits
                               undelivered-boundary
                               (and checkpoint? (or (not annotated?)
                                                    (not-any? evidence/fault? (vals (:facts state))))))
              result
              {:decisions (if annotated?
                            (mapv #(get (:facts state) % false) root-questions)
                            (mapv #(contains? (:facts state) %) root-questions))
               :checkpoint checkpoint
               :counters @counters
               :replayed? false}]
          (when-not (if qualification
                      (every? (fn [[q decision]]
                                (let [lower? (contains? (:lower graph) q)
                                      upper? (contains? (:upper graph) q)]
                                  (case (evidence/permissionship decision)
                                    :evaluation-failure true
                                    :has-permission upper?
                                    :no-permission (not lower?)
                                    :conditional-permission (and (not lower?) upper?))))
                              (map vector root-questions (:decisions result)))
                      (= bounded-decisions (:decisions result)))
            (invalid!
             :demand-discovery-mismatch
             "Demand-bounded and exact recursive evaluation disagreed."
             {:bounded bounded-decisions
              :exact (:decisions result)}))
          (observe! @counters)
          result)))))

(def ^:private point-cache-options
  {:valid? boolean?})

(def ^:private qualified-point-cache-options {:valid? string?})

(defn- point-cache-key
  [plan permission scope-identity candidate]
  [:operator-recursive-point checkpoint-version
   (:fingerprint plan) permission scope-identity candidate])

(defn evaluate-cached-many
  "Returns aligned recursive point decisions with proof-compatible completed
  point reuse. Only unresolved distinct points enter the recursive evaluator;
  no point is published until that whole demanded vector succeeds."
  [{:keys [plan candidates permission scope-identity checkpoint qualification] :as options}]
  (validate-many-options! plan candidates)
  (let [options (assoc options :limits (normalize-limits (:limits options)))]
    (if (or checkpoint (nil? subproblem/*store*))
      (evaluate-many-validated options)
      (let [permission (or permission (:root plan))
            store subproblem/*store*
            scope-identity (if qualification
                             [:qualified-point evidence/format-version scope-identity
                              (qualification/exact-reuse-identity qualification)]
                             scope-identity)
            looked-up
            (mapv
             (fn [candidate]
               (let [key (point-cache-key
                          plan permission scope-identity candidate)]
                 (if-let [resolved
                          (subproblem/lookup-denotation! key)]
                   (do
                     (subproblem/record-avoided-backend-operation! store)
                     {:candidate candidate :key key
                      :decision (if qualification (evidence/decode (:value resolved)) (:value resolved))
                      :cached? true})
                   {:candidate candidate :key key :cached? false})))
             candidates)
            misses
            (->> looked-up
                 (remove :cached?)
                 (map :candidate)
                 distinct
                 vec)
            evaluated
            (if (seq misses)
              (evaluate-many-validated (assoc options :candidates misses))
              {:decisions [] :counters {}})
            miss-decisions (zipmap misses (:decisions evaluated))
            decisions
            (mapv (fn [{:keys [candidate decision cached?]}]
                    (if cached? decision (get miss-decisions candidate)))
                  looked-up)]
        (when (and subproblem/*populate?* (not-any? evidence/fault? (:decisions evaluated)))
          ;; Duplicate candidates share one key; publish each key once.
          (reduce (fn [published {:keys [candidate key cached?]}]
                    (if (or cached? (contains? published key))
                      published
                      (do (subproblem/publish-denotation!
                           key (if qualification qualified-point-cache-options point-cache-options)
                           (let [value (get miss-decisions candidate)]
                             (if qualification (evidence/encode value) value)))
                          (conj published key))))
                  #{}
                  looked-up))
        {:decisions decisions
         :counters
         (assoc (:counters evaluated)
                :point-cache-hits (- (count looked-up) (count misses))
                :point-cache-misses (count misses))
         :replayed? false
         :point-cached? (empty? misses)}))))

(defn check-cached-eids
  "Returns one exact recursive membership decision with completed point reuse."
  [{:keys [subject-type subject-eid resource-eid direction] :as options}]
  (first
   (:decisions
    (evaluate-cached-many
     (-> options
         (dissoc :subject-type :subject-eid :resource-eid :direction)
         (assoc :candidates
                [{:direction (or direction :forward)
                  :subject-type subject-type
                  :subject-eid subject-eid
                  :resource-eid resource-eid}]))))))

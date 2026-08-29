(ns eacl.operator.recursive
  "Bounded tabled evaluation for recursive operator membership questions.

  The engine discovers only questions reachable from the demanded typed point
  contexts.  It then evaluates the signed question graph dependency-first.
  Positive components use a deterministic fact worklist; intersection state
  is materialized only after its sealed anchor fact exists.  Negative edges
  may consume absence only after their dependency component is complete."
  (:require [eacl.backend.direct-membership :as direct]
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

(defn- normalize-limits [limits]
  (let [limits (or limits {})]
    (when-not (map? limits)
      (invalid! :invalid-limits "Recursive limits must be a map."
                {:value-type (some-> limits type str)}))
    (let [unknown
          (seq (remove (set (keys default-limits)) (keys limits)))]
      (when unknown
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

(defn- root-index [plan]
  (into {} (map (juxt :permission :root)) (:expressions plan)))

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
  (sort-by question-key questions))

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

(defn- relation-partition [descriptor subject-type]
  (first (filter #(= subject-type (:subject-type %))
                 (:partitions descriptor))))

(defn- direct-probe [q descriptor]
  (when-let [{:keys [relation-id]}
             (relation-partition descriptor (question-subject-type q))]
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
  [resource->subjects! q partition bound limits counters]
  (let [descriptor
        {:resource-type (first (question-permission q))
         :resource-eid (question-resource-eid q)
         :relation-eid (:via-relation-eid partition)
         :intermediate-type (:intermediate-type partition)}
        chunk-size (:physical-chunk-size limits)
        options (cond-> {:direction :asc}
                  (some? bound)
                  (assoc :bound-eid bound :inclusive-bound? false))
        cache-key [:operator-recursive-arrow-chunk 1
                   descriptor options chunk-size]
        resolved
        (subproblem/resolve-layered-bound!
         :projection cache-key
         {:valid? vector?
          :weight-fn #(max 128 (+ 128 (* 16 (count %))))}
         (:relation-eid descriptor)
         (fn []
           (execution/check! execution/*contract*
                             :operator-recursive/arrow-scan-before
                             {:commands (:commands @counters)
                              :values (:values @counters)})
           (let [next-commands (inc (:commands @counters))]
             (limit-counter! limits counters :commands
                             :maximum-commands next-commands)
             (let [chunk
                   (into [] (take chunk-size)
                         (resource->subjects!
                          (:resource-type descriptor)
                          (:resource-eid descriptor)
                          (:relation-eid descriptor)
                          (:intermediate-type descriptor)
                          options))]
               (vswap! counters assoc :commands next-commands)
               (request-counters/add-commands!)
               (request-counters/add-fetched-values! (count chunk))
               (execution/check! execution/*contract*
                                 :operator-recursive/arrow-scan-after
                                 {:commands next-commands
                                  :fetched-values (count chunk)})
               chunk))))
        values (:value resolved)
        next-values (+ (:values @counters) (count values))]
    (limit-counter! limits counters :values :maximum-values next-values)
    (vswap! counters assoc :values next-values)
    (when (:cached? resolved)
      (add-counter! counters :shared-scan-cache-hits)
      (subproblem/record-avoided-backend-operation!))
    (execution/check! execution/*contract*
                      :operator-recursive/arrow-scan-complete
                      {:commands (:commands @counters)
                       :values next-values})
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
                       {:questions (count nodes)
                        :queue (- (count queue) index)})
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
        starts (vec (sort-by component-key (range (count components))))
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
  [resource->subjects! plan roots nodes q limits counters]
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
                      resource->subjects! q partition bound limits counters)
              expanded
              (mapv
               (fn [intermediate-eid]
                 (if (= :permission (:target-kind partition))
                   {:dependency
                    {:key (arrow-target-question
                           roots q intermediate-eid partition)
                     :sign :positive}}
                   {:probe
                    (direct-probe
                     (question [(:intermediate-type partition)
                                (:target-name partition)]
                               -1 (question-direction q)
                               (question-subject-type q)
                               (question-subject-eid q)
                               intermediate-eid)
                     (:target-relation partition))}))
               values)
              dependencies (mapv :dependency
                                  (filter :dependency expanded))
              probes (mapv :probe (filter :probe expanded))
              first-slot (count (:dependencies spec))
              dependencies
              (mapv (fn [slot dependency]
                      (assoc dependency :slot slot))
                    (range first-slot
                           (+ first-slot (count dependencies)))
                    dependencies)
              short-chunk? (< (count values)
                              (:physical-chunk-size limits))
              next-partition (if short-chunk?
                               (inc partition-index)
                               partition-index)
              next-state
              {:partitions partitions
               :partition-index next-partition
               :bound (when-not short-chunk? (peek values))
               :complete? (and short-chunk?
                               (>= next-partition
                                   (count partitions)))}
              nodes
              (-> nodes
                  (update-in [q :dependencies] into dependencies)
                  (update-in [q :base-probes] into probes)
                  (assoc-in [q :arrow-state] next-state))
              dependency-questions (mapv :key dependencies)]
          (add-counter! counters :arrow-chunks)
          (discover-graph! plan roots dependency-questions
                           limits counters nodes))))))

(defn- rule-bounds
  [rule lower-facts upper-facts]
  (let [{:keys [kind dependencies base-true? arrow-state]} rule
        lower? #(contains? lower-facts (:key %))
        upper? #(contains? upper-facts (:key %))
        incomplete-arrow?
        (and arrow-state (not (:complete? arrow-state)))]
    (case kind
      :base
      [(boolean base-true?) (boolean base-true?)]

      :union
      [(boolean (or base-true? (some lower? dependencies)))
       (boolean (or base-true? (some upper? dependencies)
                    incomplete-arrow?))]

      :intersection
      [(every? lower? dependencies)
       (every? upper? dependencies)]

      :exclusion
      (let [[left right] dependencies]
        [(and (lower? left) (not (upper? right)))
         (and (upper? left) (not (lower? right)))])

      [false false])))

(defn- solve-bounds
  "Computes lower and upper least fixed points for the discovered graph.
  Unexhausted arrow continuations are false in the lower bound and possible in
  the upper bound. Strictly lower negative dependencies reverse their bound at
  exclusion, so the result remains sound in the presence of negation."
  [nodes]
  (let [{:keys [components]} (strongly-connected-components nodes)
        {:keys [component-of order]}
        (dependency-first-components nodes components)
        _ (validate-negative-components! nodes component-of)]
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
                       (sorted-questions component))]
                  (if (and (= lower-facts next-lower)
                           (= upper-facts next-upper))
                    [lower-facts upper-facts]
                    (recur next-lower next-upper))))]
          (recur (rest remaining) lower-facts upper-facts))
        {:lower lower-facts :upper upper-facts}))))

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
   known-questions width limits counters]
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
                resource->subjects! plan roots current q limits counters))
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
       (assoc-in result [q :arrow-state :complete?] true)
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
  (let [component-set (set component)]
    (reduce
     (fn [result head]
       (let [spec (get nodes head)]
         (case (:kind spec)
           :union
           (reduce (fn [m {:keys [key]}]
                     (update m key (fnil conj [])
                             {:kind :unary :head head}))
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
     {} component-set)))

(defn- enqueue-fact! [state fact limits counters]
  (if (contains? (:facts state) fact)
    (do (add-counter! counters :duplicate-facts) state)
    (let [next-facts (inc (count (:facts state)))
          next-queue (inc (- (count (:agenda state)) (:agenda-index state)))]
      (limit-counter! limits counters :facts :maximum-facts next-facts)
      (limit-counter! limits counters :queue :maximum-queue next-queue)
      (vswap! counters assoc :facts next-facts)
      (vswap! counters update :maximum-queue max next-queue)
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
   state (sorted-questions component)))

(defn- run-component!
  [state nodes component completed component-of limits counters]
  (let [consumers (component-consumers nodes component)
        state (assoc state :agenda [] :agenda-index 0)
        state (initialize-component state nodes component completed
                                    component-of limits counters)]
    (loop [state state]
      (if (= (:agenda-index state) (count (:agenda state)))
        (assoc state :agenda [] :agenda-index 0)
        (let [fact (nth (:agenda state) (:agenda-index state))
              state (update state :agenda-index inc)
              next-transition (inc (:transitions @counters))]
          (limit-counter! limits counters :transitions
                          :maximum-transitions next-transition)
          (vswap! counters assoc :transitions next-transition)
          (execution/check! execution/*contract*
                            :operator-recursive/fact-transition
                            {:transitions next-transition
                             :facts (count (:facts state))})
          (let [state
                (reduce
                 (fn [state {:keys [kind head slot right]}]
                   (case kind
                     :unary
                     (enqueue-fact! state head limits counters)

                     :exclusion
                     (if (contains? (:facts state) right)
                       state
                       (enqueue-fact! state head limits counters))

                     :intersection
                     (let [rule (get nodes head)
                           state
                           (case (join-transition-action state rule slot)
                             :update (update-join state rule slot)
                             :reserve (reserve-join! state rule limits counters)
                             :ignore state)]
                       (if (join-complete? state rule)
                         (enqueue-fact! state head limits counters)
                         state))

                     state))
                 state
                 (sort-by (juxt (comp str :kind)
                                (comp question-key :head)
                                :slot)
                          (get consumers fact [])))]
            (recur state)))))))

(defn- direct-cache-key [probe]
  [:operator-recursive-direct-membership 1
   (:direction probe) (:descriptor probe) (:candidate probe)])

(def ^:private direct-cache-options
  {:valid? boolean?
   :weight-fn (constantly 128)})

(defn- attach-base-decisions!
  [adapter nodes limits counters pending-publications]
  (let [entries
        (vec
         (mapcat
          (fn [[q spec]]
            (map #(vector q %)
                 (drop (or (:attached-probe-count spec) 0)
                       (:base-probes spec))))
          (sort-by (comp question-key key) nodes)))
        probe-count (count entries)
        next-probes (+ (:probes @counters) probe-count)]
    (limit-counter! limits counters :probes :maximum-probes next-probes)
    (vswap! counters assoc :probes next-probes)
    (if (zero? probe-count)
      nodes
      (let [physical (atom {})
            reused (atom #{})
            cache-lookup
            (fn [probe]
              (let [key (direct-cache-key probe)]
                (if-let [[_ decision] (get @pending-publications key)]
                  (do
                    (swap! reused conj key)
                    decision)
                  (if-let [store subproblem/*store*]
                    (if-let [resolved
                             (subproblem/lookup!
                              store :projection key direct-cache-options)]
                      (do
                        (swap! reused conj key)
                        (subproblem/record-avoided-backend-operation! store)
                        (:value resolved))
                      direct/cache-miss)
                    direct/cache-miss))))
            decisions
            (binding [direct/*physical-stats* physical]
              (direct/dispatch adapter (mapv second entries) cache-lookup))
            _
            (doseq [[[_ probe] decision] (map vector entries decisions)
                    :let [key (direct-cache-key probe)]
                    :when (not (contains? @reused key))]
              ;; Publication is deferred until every demand-discovery round,
              ;; exact fixed point, and checkpoint limit has succeeded.
              (swap! pending-publications assoc key [probe decision]))
            true-heads
            (into #{}
                  (keep (fn [[[head _] decision]]
                          (when decision head)))
                  (map vector entries decisions))]
        (add-counter! counters :direct-adapter-commands
                      (:adapter-commands @physical 0))
        (add-counter! counters :direct-fetched-values
                      (:adapter-fetched-values @physical 0))
        (add-counter! counters :direct-cache-hits
                      (:cache-hits @physical 0))
        (reduce-kv
         (fn [result q spec]
           (assoc result q
                  (assoc spec
                         :attached-probe-count (count (:base-probes spec))
                         :base-true? (or (:base-true? spec)
                                         (contains? true-heads q)))))
         {} nodes)))))

(defn- publish-direct-decisions! [pending-publications]
  (when-let [store subproblem/*store*]
    (when subproblem/*populate?*
      (doseq [[key [_ decision]]
              (sort-by (comp pr-str key) @pending-publications)]
        (subproblem/publish!
         store :projection key direct-cache-options decision)))))

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
  [plan root-questions scope-identity state completed component-strata
   counters limits undelivered-boundary]
  (let [facts (vec (sorted-questions (:facts state)))
        joins
        (mapv (fn [[key value]] [key value])
              (sort-by (comp question-key key) (:join-states state)))
        completed (vec completed)
        completed-strata
        (->> completed (map component-strata) distinct sort vec)
        weight
        (checkpoint-weight facts joins completed completed-strata)]
    (limit-counter! limits counters :checkpoint-weight
                    :maximum-checkpoint-weight weight)
    (vswap! counters assoc :checkpoint-weight weight)
    {:version checkpoint-version
     :command-identity
     (command-identity plan root-questions scope-identity)
     :completed? true
     :facts facts
     :anchor-states joins
     :completed-components completed
     :completed-strata completed-strata
     :pending-negative-questions []
     :pending-commands []
     :undelivered-boundary undelivered-boundary
     :counters @counters}))

(defn- replay-checkpoint
  [checkpoint identity root-questions]
  (when-not (and (map? checkpoint)
                 (= checkpoint-version (:version checkpoint))
                 (= identity (:command-identity checkpoint))
                 (true? (:completed? checkpoint))
                 (vector? (:facts checkpoint))
                 (vector? (:anchor-states checkpoint))
                 (vector? (:completed-components checkpoint))
                 (vector? (:completed-strata checkpoint))
                 (vector? (:pending-negative-questions checkpoint))
                 (empty? (:pending-negative-questions checkpoint))
                 (vector? (:pending-commands checkpoint))
                 (empty? (:pending-commands checkpoint)))
    (invalid! :invalid-checkpoint
              "Recursive checkpoint is malformed or incompatible."
              {:checkpoint-version (:version checkpoint)}))
  (let [facts (set (:facts checkpoint))]
    {:decisions (mapv #(contains? facts %) root-questions)
     :checkpoint checkpoint
     :counters (:counters checkpoint)
     :replayed? true}))

(defn evaluate-many
  "Evaluates an aligned vector of recursive operator point questions.

  Results are all-or-error.  `:checkpoint`, when supplied, must be a complete
  compatible portable checkpoint and replays without backend work."
  [{:keys [adapter plan candidates permission limits checkpoint
           scope-identity undelivered-boundary]}]
  (when-not (operator-plan/operator-plan? plan)
    (invalid! :operator-plan-required
              "Recursive evaluation requires an operator plan."
              {:plan-domain (:domain plan)}))
  (when-not (vector? candidates)
    (invalid! :invalid-candidates
              "Recursive candidates must be a vector."
              {:value-type (some-> candidates type str)}))
  (doseq [candidate candidates] (validate-candidate! candidate))
  (let [limits (normalize-limits limits)
        roots (root-index plan)
        permission (or permission (:root plan))
        root-questions (mapv #(candidate->root-question roots permission %)
                             candidates)
        identity (command-identity plan root-questions scope-identity)]
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
                          :shared-scan-cache-hits 0
                          :direct-adapter-commands 0
                          :direct-fetched-values 0 :direct-cache-hits 0
                          :late-anchor-initialized-slots 0})
              pending-direct-publications (atom {})
              resource->subjects!
              (backend/scan-invoker adapter :resource->subjects)
              initial-nodes
              (discover-graph! plan roots root-questions limits counters)
              [nodes bounded-decisions]
              (loop [nodes
                     (attach-base-decisions!
                      adapter initial-nodes limits counters
                      pending-direct-publications)
                     wave-width
                     (min batch-schedule/maximum-width
                          (max 1 (count root-questions)))]
                (execution/check! execution/*contract*
                                  :operator-recursive/demand-discovery
                                  {:questions (count nodes)
                                   :commands (:commands @counters)})
                (let [{:keys [lower upper]} (solve-bounds nodes)
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
                    [(finalize-unused-arrows nodes) decisions]
                    (let [known-questions (set (keys nodes))
                          arrows
                          (uncertain-arrow-questions
                           nodes root-questions lower-facts upper-facts
                           known-questions)]
                      (when (empty? arrows)
                        (invalid!
                         :stalled-demand-discovery
                         "Recursive demand discovery could not refine an uncertain result."
                         {:questions (count nodes)
                          :roots (count root-questions)}))
                      (let [nodes
                            (expand-arrow-wave!
                             resource->subjects! plan roots nodes arrows
                             lower-facts upper-facts known-questions
                             wave-width limits counters)]
                        (add-counter! counters :demand-rounds)
                        (recur
                         (attach-base-decisions!
                          adapter nodes limits counters
                          pending-direct-publications)
                         (min batch-schedule/maximum-width
                              (* 2 wave-width))))))))
              _ (vswap! counters assoc :questions (count nodes))
              {:keys [components]} (strongly-connected-components nodes)
              component-count (count components)
              _ (limit-counter! limits counters :components
                                :maximum-components component-count)
              {:keys [component-of order]}
              (dependency-first-components nodes components)
              _ (validate-negative-components! nodes component-of)
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
                     state {:facts #{} :join-states {}
                            :agenda [] :agenda-index 0}
                     completed #{}]
                (if-let [component (first remaining)]
                  (do
                    (execution/check! execution/*contract*
                                      :operator-recursive/component
                                      {:component component
                                       :completed (count completed)})
                    (let [state
                          (run-component!
                           state nodes (nth components component) completed
                           component-of limits counters)]
                      (recur (rest remaining) state
                             (conj completed component))))
                  [state completed]))
              checkpoint
              (make-checkpoint plan root-questions scope-identity state
                               (sort completed) component-strata counters limits
                               undelivered-boundary)
              result
              {:decisions (mapv #(contains? (:facts state) %)
                                root-questions)
               :checkpoint checkpoint
               :counters @counters
               :replayed? false}]
          (when-not (= bounded-decisions (:decisions result))
            (invalid!
             :demand-discovery-mismatch
             "Demand-bounded and exact recursive evaluation disagreed."
             {:bounded bounded-decisions
              :exact (:decisions result)}))
          (publish-direct-decisions! pending-direct-publications)
          (observe! @counters)
          result)))))

(defn check-eids
  "Returns one exact recursive membership decision."
  [{:keys [subject-type subject-eid resource-eid direction] :as options}]
  (first
   (:decisions
    (evaluate-many
     (-> options
         (dissoc :subject-type :subject-eid :resource-eid :direction)
         (assoc :candidates
                [{:direction (or direction :forward)
                  :subject-type subject-type
                  :subject-eid subject-eid
                  :resource-eid resource-eid}]))))))

(def ^:private point-cache-options
  {:valid? boolean?
   :weight-fn (constantly 160)})

(defn- point-cache-key
  [plan permission scope-identity candidate]
  [:operator-recursive-point checkpoint-version
   (:fingerprint plan) permission scope-identity candidate])

(defn evaluate-cached-many
  "Returns aligned recursive point decisions with proof-compatible completed
  point reuse. Only unresolved distinct points enter the recursive evaluator;
  no point is published until that whole demanded vector succeeds."
  [{:keys [plan candidates permission scope-identity checkpoint] :as options}]
  (when-not (operator-plan/operator-plan? plan)
    (invalid! :operator-plan-required
              "Recursive evaluation requires an operator plan."
              {:plan-domain (:domain plan)}))
  (when-not (vector? candidates)
    (invalid! :invalid-candidates
              "Recursive candidates must be a vector."
              {:value-type (some-> candidates type str)}))
  (doseq [candidate candidates] (validate-candidate! candidate))
  (let [options (assoc options :limits (normalize-limits (:limits options)))]
    (if (or checkpoint (nil? subproblem/*store*))
      (evaluate-many options)
      (let [permission (or permission (:root plan))
            store subproblem/*store*
            looked-up
            (mapv
             (fn [candidate]
               (let [key (point-cache-key
                          plan permission scope-identity candidate)]
                 (if-let [resolved
                          (subproblem/lookup!
                           store :denotation key point-cache-options)]
                   (do
                     (subproblem/record-avoided-backend-operation! store)
                     {:candidate candidate :key key
                      :decision (:value resolved) :cached? true})
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
              (evaluate-many (assoc options :candidates misses))
              {:decisions [] :counters {}})
            miss-decisions (zipmap misses (:decisions evaluated))
            decisions
            (mapv (fn [{:keys [candidate decision cached?]}]
                    (if cached? decision (get miss-decisions candidate)))
                  looked-up)]
        (when subproblem/*populate?*
          (let [published (atom #{})]
            (doseq [{:keys [candidate key cached?]} looked-up
                    :when (and (not cached?)
                               (not (contains? @published key)))]
              (swap! published conj key)
              (subproblem/publish!
               store :denotation key point-cache-options
               (get miss-decisions candidate)))))
        {:decisions decisions
         :counters
         (assoc (:counters evaluated)
                :point-cache-hits (count (filter :cached? looked-up))
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

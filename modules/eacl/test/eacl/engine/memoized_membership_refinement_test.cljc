(ns eacl.engine.memoized-membership-refinement-test
  "Executable refinement of `eacl.engine.stable-route/check-many-eids`, the
  memoized search through which delegated operator plans decide their
  union-only operands, against formal/dafny/MemoizedMembership.dfy.

  A case is a random union-only schema over three types, random relationship
  tuples on an in-memory adapter, and a random sequence of resource batches
  for one subject over one request context. The model's graph is built from
  the sealed plan's rules and the generated tuples alone, never from the
  search or its scans:

  - a state is `[node eid]`; an arrow frame is `[::arrow ordinal eid]` for the
    arrow rule with that ordinal;
  - a state's successors are its rules' frames in the order the search pushes
    them (reverse sealed order, so the first rule's frame is popped first): a
    self-permission's target state and each arrow rule's frame. An
    arrow-to-permission frame's successors are its intermediates' target
    states in reverse scan order; an arrow-to-relation frame has none;
  - a witness is a state with a relation rule the subject holds there, or an
    arrow-to-relation frame with an intermediate on which the subject holds
    the target relation;
  - a node is possible when it reaches a seed: a plan node with a relation
    rule, or an arrow-to-relation rule's node, whose relation the subject
    holds somewhere.

  A transcription of the model's `Search` decides the same roots in the same
  order as the production calls. After every call the campaign requires equal
  decisions, the production memo equal to the model's retained answers
  restricted to states, the production possible-node set equal to the least
  one, every retained answer equal to the state's reachability, and every
  decision equal to the membership-probe point check. Mutation controls in
  `eacl.formal.executed-mutation-controls` run `run-campaign` against mutated
  searches."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.string :as str]
            [eacl.backend.v8 :as backend]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-route :as route]
            [eacl.test-support.tuple-adapter :as tuple-adapter]))

;; ---------------------------------------------------------------------------
;; Deterministic cases
;; ---------------------------------------------------------------------------

(defn- next-int!
  "Park-Miller minimal standard: exact in JavaScript doubles too."
  [state bound]
  (let [value (mod (* 48271 @state) 2147483647)]
    (reset! state value)
    (mod (quot value 256) bound)))

(defn- chance? [state percent] (< (next-int! state 100) percent))

(defn- pick [state values] (nth values (next-int! state (count values))))

(defn- render-term [[kind a b]]
  (case kind
    (:relation :self) (name a)
    (:arrow :arrow-relation) (str (name a) "->" (name b))))

(defn- render-permissions [permissions]
  (apply str
         (for [[permission terms] permissions]
           (str "  permission " (name permission) " = "
                (str/join " + " (map render-term terms)) "\n"))))

(defn- render-schema [{:keys [team folder]}]
  (str "definition user {}\n\n"
       "definition team {\n"
       "  relation member: user\n"
       "  relation parent: team\n"
       (render-permissions team)
       "}\n\n"
       "definition folder {\n"
       "  relation parent: folder\n"
       "  relation reader: user\n"
       "  relation owner: user\n"
       "  relation team: team\n"
       (render-permissions folder)
       "}\n"))

(defn- random-permissions
  "Union-only bodies over relations, a relation through an arrow, and
  permissions through self and arrow references, recursion included."
  [state]
  (let [teams (mapv #(keyword (str "t" %)) (range (inc (next-int! state 2))))
        folders (mapv #(keyword (str "p" %)) (range (+ 2 (next-int! state 2))))
        body (fn [own options fallback]
               (let [terms (->> (repeatedly (inc (next-int! state 4))
                                            #(pick state options))
                                distinct
                                (remove #{[:self own]})
                                vec)]
                 (if (seq terms) terms [fallback])))
        team-options (vec (concat [[:relation :member]]
                                  (for [t teams] [:arrow :parent t])
                                  (for [t teams] [:self t])))
        folder-options (vec (concat [[:relation :reader] [:relation :owner]
                                     [:arrow-relation :team :member]]
                                    (for [t teams] [:arrow :team t])
                                    (for [p folders] [:arrow :parent p])
                                    (for [p folders] [:self p])))]
    {:team (into (sorted-map)
                 (for [t teams] [t (body t team-options [:relation :member])]))
     :folder (into (sorted-map)
                   (for [p folders]
                     [p (body p folder-options [:relation :reader])]))}))

(def ^:private users [300 301])

(defn- random-tuples
  "Folders 100.., teams 200.., users 300 and 301. Parent edges may form
  cycles and self-loops."
  [state]
  (let [folders (range 100 (+ 100 3 (next-int! state 6)))
        teams (range 200 (+ 200 1 (next-int! state 3)))]
    {:folders (vec folders)
     :teams (vec teams)
     :tuples
     (set
      (concat
       (for [child folders parent folders :when (chance? state 18)]
         [:folder parent :parent :folder child])
       (for [child teams parent teams :when (chance? state 25)]
         [:team parent :parent :team child])
       (for [folder folders user users relation [:reader :owner]
             :when (chance? state 15)]
         [:user user relation :folder folder])
       (for [folder folders team teams :when (chance? state 30)]
         [:team team :team :folder folder])
       (for [team teams user users :when (chance? state 35)]
         [:user user :member :team team])))}))

;; ---------------------------------------------------------------------------
;; The model's graph, from the sealed rules and the tuples alone
;; ---------------------------------------------------------------------------

(defn- arrow? [frame] (= ::arrow (first frame)))

(defn- abstraction
  [adapter plan tuples subject]
  (let [relation-id (fn [resource-type relation]
                      (:relation-id
                       (first (backend/invoke adapter :relation-defs
                                              resource-type relation))))
        id-tuples (set (for [[subject-type subject-eid relation resource-type
                              resource-eid] tuples]
                         [subject-type subject-eid
                          (relation-id resource-type relation)
                          resource-type resource-eid]))
        holds (set (for [[subject-type subject-eid relation-eid resource-type
                          resource-eid] id-tuples
                         :when (and (= :user subject-type) (= subject subject-eid))]
                     [relation-eid resource-type resource-eid]))
        held-slices (set (map (fn [[relation-eid resource-type _]]
                                [relation-eid resource-type])
                              holds))
        intermediates (fn [resource-type eid via-relation-eid intermediate-type]
                        (vec (sort (for [[subject-type subject-eid relation-eid
                                          tuple-type tuple-eid] id-tuples
                                         :when (and (= intermediate-type subject-type)
                                                    (= via-relation-eid relation-eid)
                                                    (= resource-type tuple-type)
                                                    (= eid tuple-eid))]
                                     subject-eid))))
        reverse-rules (get-in plan [:indexes :reverse-rules])
        rules-by-ordinal (into {} (for [rules (vals reverse-rules) rule rules]
                                    [(:ordinal rule) rule]))
        frame-intermediates (fn [rule eid]
                              (intermediates (:resource-type rule) eid
                                             (:via-relation-eid rule)
                                             (:intermediate-type rule)))
        succ (fn [frame]
               (if (arrow? frame)
                 (let [[_ ordinal eid] frame
                       rule (rules-by-ordinal ordinal)]
                   (if (= :arrow-permission (:rule rule))
                     (vec (reverse (map #(vector (:target-node rule) %)
                                        (frame-intermediates rule eid))))
                     []))
                 (let [[node eid] frame]
                   (vec (reverse
                         (keep (fn [rule]
                                 (case (:rule rule)
                                   :relation nil
                                   :self-permission [(:target-node rule) eid]
                                   (:arrow-permission :arrow-relation)
                                   [::arrow (:ordinal rule) eid]))
                               (get reverse-rules node)))))))
        grant? (fn [frame]
                 (if (arrow? frame)
                   (let [[_ ordinal eid] frame
                         rule (rules-by-ordinal ordinal)]
                     (boolean
                      (and (= :arrow-relation (:rule rule))
                           (= :user (:target-subject-type rule))
                           (some #(contains? holds [(:target-relation-eid rule)
                                                    (:intermediate-type rule) %])
                                 (frame-intermediates rule eid)))))
                   (let [[node eid] frame]
                     (boolean
                      (some (fn [rule]
                              (and (= :relation (:rule rule))
                                   (= :user (:subject-type rule))
                                   (contains? holds [(:relation-eid rule)
                                                     (:resource-type rule) eid])))
                            (get reverse-rules node))))))
        node-of (fn [frame] (if (arrow? frame) [::arrow (second frame)] (first frame)))
        seeds (set (concat
                    (for [[node rules] reverse-rules
                          rule rules
                          :when (and (= :relation (:rule rule))
                                     (= :user (:subject-type rule))
                                     (contains? held-slices [(:relation-eid rule)
                                                             (:resource-type rule)]))]
                      node)
                    (for [rules (vals reverse-rules)
                          rule rules
                          :when (and (= :arrow-relation (:rule rule))
                                     (= :user (:target-subject-type rule))
                                     (contains? held-slices
                                                [(:target-relation-eid rule)
                                                 (:intermediate-type rule)]))]
                      [::arrow (:ordinal rule)])))
        targets (merge
                 (into {} (for [[node rules] reverse-rules]
                            [node (set (keep (fn [rule]
                                               (case (:rule rule)
                                                 :relation nil
                                                 :self-permission (:target-node rule)
                                                 [::arrow (:ordinal rule)]))
                                             rules))]))
                 (into {} (for [rule (vals rules-by-ordinal)
                                :when (#{:arrow-permission :arrow-relation} (:rule rule))]
                            [[::arrow (:ordinal rule)]
                             (if (= :arrow-permission (:rule rule))
                               #{(:target-node rule)}
                               #{})])))
        possible (loop [possible seeds]
                   (let [grown (into possible
                                     (for [[source ts] targets
                                           :when (some possible ts)]
                                       source))]
                     (if (= grown possible) possible (recur grown))))]
    {:succ succ :grant? grant? :node-of node-of :possible possible
     :targets targets :seeds seeds}))

(defn- model-precondition-violation
  "Nil when the frames reachable from `roots` meet the leaf's `ConsistentNodes`
  premise: every successor's node is a target of its source's node, and every
  witness's node is a seed. `ClosedPossible` holds by construction of the least
  possible set, which the campaign compares with production's."
  [{:keys [succ grant? node-of targets seeds]} roots]
  (loop [pending (vec roots) seen (set roots)]
    (when-let [frame (peek pending)]
      (let [successors (succ frame)
            stray (first (remove #(contains? (get targets (node-of frame)) (node-of %))
                                 successors))]
        (cond
          stray {:frame frame :successor-outside-targets stray}
          (and (grant? frame) (not (contains? seeds (node-of frame))))
          {:witness-outside-seeds frame}
          :else (let [fresh (remove seen successors)]
                  (recur (into (pop pending) fresh) (into seen fresh))))))))

(defn- reachable-witness?
  "The denotation, independently of the search: some walk from `frame` ends at
  a witness."
  [{:keys [succ grant?]} frame]
  (loop [pending [frame] seen #{frame}]
    (if-let [current (peek pending)]
      (if (grant? current)
        true
        (let [fresh (remove seen (succ current))]
          (recur (into (pop pending) fresh) (into seen fresh))))
      false)))

(defn- model-search
  "`Search` of MemoizedMembership.dfy, transcribed: returns [found positive'
  negative']."
  [{:keys [succ grant? node-of possible]} positive negative root]
  (let [skippable? (fn [t]
                     (or (contains? negative t)
                         (not (contains? possible (node-of t)))))]
    (loop [stack [root] admitted #{}]
      (if (empty? stack)
        [false positive (into negative admitted)]
        (let [s (peek stack)
              stack (pop stack)]
          (cond
            (contains? positive s) [true (conj positive root) negative]
            (or (contains? admitted s) (skippable? s)) (recur stack admitted)
            (grant? s) [true (conj positive root) negative]
            :else (recur (into stack (succ s)) (conj admitted s))))))))

;; ---------------------------------------------------------------------------
;; One case
;; ---------------------------------------------------------------------------

(defn- retained [positive negative]
  (merge (zipmap (remove arrow? negative) (repeat false))
         (zipmap (remove arrow? positive) (repeat true))))

(defn- run-plan
  "Runs one plan's calls for one subject; returns the updated counters, with
  `:failure` describing the first divergence."
  [state counters {:keys [seed adapter plan tuples subject eids
                          holdings-limit chunk-size schema]}]
  (let [graph (abstraction adapter plan tuples subject)
        context (route/membership-context)
        options {:adapter adapter :plan plan :subject-type :user
                 :subject-eid subject :context context
                 :physical-chunk-size chunk-size}
        plan-possible (set (remove arrow? (:possible graph)))
        fail (fn [counters what data]
               (assoc counters :failure
                      (merge {:seed seed :what what :root (:root plan)
                              :subject subject :holdings-limit holdings-limit
                              :chunk-size chunk-size :schema schema
                              :tuples (sort tuples)}
                             data)))]
    (loop [calls (inc (next-int! state 4))
           positive #{}
           negative #{}
           counters (update counters :plans inc)]
      (if (zero? calls)
        counters
        (let [batch (vec (repeatedly (inc (next-int! state 6))
                                     #(when-not (chance? state 5)
                                        (pick state eids))))
              actual (try (route/check-many-eids
                           (assoc options :resource-eids batch))
                          (catch #?(:clj Exception :cljs :default) error
                            {:thrown (or (ex-data error) (str error))}))
              [expected positive negative]
              (reduce (fn [[decisions positive negative] eid]
                        (if (nil? eid)
                          [(conj decisions false) positive negative]
                          (let [[found positive negative]
                                (model-search graph positive negative
                                              [(:root plan) eid])]
                            [(conj decisions found) positive negative])))
                      [[] positive negative]
                      batch)
              entry (get @context [:subject (:fingerprint plan) :user subject])
              memo (some-> entry :memo deref)]
          (cond
            (not= expected actual)
            (fail counters :decisions {:batch batch :expected expected
                                       :actual actual})

            (model-precondition-violation
             graph (for [eid batch :when eid] [(:root plan) eid]))
            (fail counters :model-precondition
                  (model-precondition-violation
                   graph (for [eid batch :when eid] [(:root plan) eid])))

            (not= plan-possible (:possible entry))
            (fail counters :possible-nodes {:expected plan-possible
                                            :actual (:possible entry)})

            (not= (retained positive negative) memo)
            (fail counters :retained-answers {:batch batch
                                              :expected (retained positive negative)
                                              :actual memo})

            (some (fn [[frame value]]
                    (not= value (reachable-witness? graph frame)))
                  memo)
            (fail counters :unsound-retained-answer {:memo memo})

            (some (fn [[eid value]]
                    (and eid
                         (not= value (route/check-eids
                                      (assoc options :resource-eid eid)))))
                  (map vector batch actual))
            (fail counters :point-check {:batch batch :actual actual})

            :else
            (recur (dec calls) positive negative
                   (-> counters
                       (update :calls inc)
                       (update :decisions + (count batch))
                       (update :true-decisions + (count (filter true? actual)))
                       (update :retained-negatives
                               + (count (filter false? (vals memo))))
                       (update :incomplete-holdings
                               + (if (= 1 holdings-limit) 1 0))))))))))

(defn- run-case
  "Returns the case's counters, with `:failure` describing the first
  divergence."
  [seed]
  (let [state (atom seed)
        permissions (random-permissions state)
        schema (render-schema permissions)
        {:keys [folders teams tuples]} (random-tuples state)
        adapter (try (tuple-adapter/from-schema schema tuples)
                     (catch #?(:clj Exception :cljs :default) _ nil))
        holdings-limit (if (chance? state 40) 1 route/holdings-limit)
        chunk-size (pick state [1 2 256])
        roots (concat (for [p (keys (:folder permissions))] [:folder p])
                      (for [t (keys (:team permissions))] [:team t]))
        counters {:plans 0 :calls 0 :decisions 0 :true-decisions 0
                  :retained-negatives 0 :incomplete-holdings 0
                  :rejected-plans 0}]
    (if (nil? adapter)
      (assoc counters :rejected-schemas 1)
      (with-redefs [route/holdings-limit holdings-limit]
        (reduce
         (fn [counters [root subject]]
           (if-let [plan (try (sealed-plan/seal-plan adapter root)
                              (catch #?(:clj Exception :cljs :default) _ nil))]
             (let [counters (run-plan state counters
                                      {:seed seed :adapter adapter :plan plan
                                       :tuples tuples :subject subject
                                       :eids (conj (if (= :folder (first root))
                                                     folders teams)
                                                   999)
                                       :holdings-limit holdings-limit
                                       :chunk-size chunk-size :schema schema})]
               (if (:failure counters) (reduced counters) counters))
             (update counters :rejected-plans inc)))
         counters
         (for [root roots subject users] [root subject]))))))

(defn run-campaign
  "Runs cases seeded `first-seed`..; stops at the first divergence, which is
  returned under `:failure`."
  [first-seed cases]
  (reduce (fn [totals seed]
            (let [result (run-case seed)
                  totals (merge-with + totals (dissoc result :failure))]
              (if-let [failure (:failure result)]
                (reduced (assoc totals :failure failure))
                (update totals :cases inc))))
          {:cases 0}
          (range first-seed (+ first-seed cases))))

(deftest memoized-search-refines-its-model-test
  (let [report (run-campaign 1 #?(:clj 300 :cljs 60))]
    (is (nil? (:failure report)) (pr-str (:failure report)))
    (testing "the campaign reaches every regime of the search"
      (is (< (* 20 (:cases report)) (:decisions report)))
      (is (pos? (:true-decisions report)))
      (is (< (:true-decisions report) (:decisions report)))
      (is (pos? (:retained-negatives report)))
      (is (pos? (:incomplete-holdings report))))))

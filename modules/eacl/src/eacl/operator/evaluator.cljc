(ns eacl.operator.evaluator
  "Stack-safe exact point evaluation for acyclic operator plans.

  The machine evaluates the sealed predicate DAG with request-local completed
  memoization. Union and intersection retain scalar short-circuit demand;
  exclusion evaluates its right operand only after exact left success. Arrow
  scans stay on the selected immutable adapter basis and stop at the first
  exact witness."
  (:require [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.backend.v8 :as backend]
            [eacl.exact-integer :as exact]
            [eacl.execution :as execution]
            [eacl.operator.plan :as operator-plan]
            [eacl.request.counters :as request-counters]
            [eacl.relationships.edge :as edge]))

(def default-limits
  {:maximum-transitions 100000
   :maximum-memo-entries 65536
   :maximum-arrow-commands 16384
   :maximum-arrow-values 1048576
   :physical-chunk-size 64})

(def ^:dynamic *evaluation-stats*
  "Optional observation-only atom receiving dimensional operator work."
  nil)

(def ^:private no-value ::no-value)

(defn- add-stat! [counter amount]
  (when *evaluation-stats*
    (swap! *evaluation-stats* update counter (fnil + 0) amount))
  nil)

(defn- invalid! [reason message data]
  (throw
   (ex-info message
            (merge {:type :eacl.operator/invalid-evaluation
                    :eacl/error :eacl.operator/invalid-evaluation
                    :reason reason}
                   data))))

(defn- limit! [dimension maximum actual]
  (throw
   (ex-info "Operator point evaluation exceeded a configured limit."
            {:type :eacl.operator/limit-exceeded
             :eacl/error :eacl.operator/limit-exceeded
             :dimension dimension
             :maximum maximum
             :actual actual})))

(defn active-recursion-outcome
  "Fail-closed outcome for a point that is already being evaluated.

  A key can only be active twice when the plan's predicate graph is cyclic,
  which the sealing pipeline rejects — so this state is an invariant breach,
  never a decision. The outcome sits in a value position at its call sites
  precisely so that treating active recursion as a boolean decision is an
  executable mutation, detected by the registered
  `:operator-active-recursion-as-false` control."
  [data]
  (throw
   (ex-info "Operator evaluation encountered active recursion."
            (assoc data
                   :type :eacl.operator/active-recursion
                   :eacl/error :eacl.operator/active-recursion))))

(def ^:private known-limit-keys (set (keys default-limits)))

(defn- normalize-limits [overrides]
  ;; The common caller passes no overrides; the closed defaults are already a
  ;; complete normalized map, so no merge or key scan is needed for them.
  (cond
    (nil? overrides)
    default-limits

    (not (map? overrides))
    (invalid! :invalid-limits "Operator limits must be a map."
              {:value overrides})

    :else
    (do
      (when-let [unknown (seq (remove known-limit-keys (keys overrides)))]
        (invalid! :unknown-limit "Operator limits contain unknown keys."
                  {:unknown-keys (vec unknown)
                   :known-keys known-limit-keys}))
      (when-not (every? (fn [[_ value]] (and (integer? value) (pos? value)))
                        overrides)
        (invalid! :invalid-limit "Operator limits must be positive integers."
                  {:limits overrides}))
      (merge default-limits overrides))))

(def ^:private acyclic-plan? operator-plan/certificate-acyclic?)

(def ^:private plan-index operator-plan/expression-roots)

(defn- complete-value [memo active key value maximum]
  (when (and (not (contains? memo key))
             (>= (count memo) maximum))
    (limit! :memo-entries maximum (inc (count memo))))
  [(assoc memo key value) (disj active key) value])

(defn- decisive? [op value]
  (or (evidence/fault? value)
      (if (= :union op) (evidence/has? value) (evidence/no? value))))

(defn- direct-match?
  [direct-match! subject-type subject-eid resource-type resource-eid
   descriptor]
  (if-let [{:keys [relation-id]}
           (operator-plan/relation-partition descriptor subject-type)]
    (do
      ;; The transition check that dispatched this frame ran with no work in
      ;; between; only the post-probe check observes new elapsed time.
      (request-counters/add-probes!)
      (add-stat! :scalar-equivalent-predicates 1)
      (let [decision
            (direct-match! subject-type subject-eid relation-id
                           resource-type resource-eid)]
        (execution/check! execution/*contract*
                          :operator-point/direct-after
                          {:probes 1})
        decision))
    false))

(defn- next-partition [frame]
  (-> frame
      (update :partition-index inc)
      (assoc :values [] :value-index 0 :bound nil :exhausted? false)))

(defn- arrow-values!
  [resource->subjects! {:keys [key partition-index descriptor bound] :as frame}
   limits counters qualification]
  (let [[permission _ _ _ resource-eid] key
        resource-type (first permission)
        partition (nth (:partitions descriptor) partition-index)
        chunk-size (:physical-chunk-size limits)
        next-command (inc (:arrow-commands @counters))]
    ;; The transition check that dispatched this frame ran with no work in
    ;; between; only the post-scan check observes new elapsed time.
    (when (> next-command (:maximum-arrow-commands limits))
      (limit! :arrow-commands (:maximum-arrow-commands limits) next-command))
    (let [options (cond-> {:direction :asc :limit chunk-size}
                    qualification (assoc :include-qualifier? true)
                    bound (assoc :bound-eid bound :inclusive-bound? false))
          values
          (into []
                (take chunk-size)
                (resource->subjects!
                 resource-type resource-eid
                 (:via-relation-eid partition)
                 (:intermediate-type partition)
                 options))
          fetched (count values)
          next-values (+ (:arrow-values @counters) fetched)]
      (when (> next-values (:maximum-arrow-values limits))
        (limit! :arrow-values (:maximum-arrow-values limits) next-values))
      (vswap! counters assoc
              :arrow-commands next-command
              :arrow-values next-values)
      (request-counters/add-commands!)
      (request-counters/add-fetched-values! fetched)
      (add-stat! :adapter-commands 1)
      (add-stat! :adapter-fetched-values fetched)
      (execution/check! execution/*contract*
                        :operator-point/arrow-scan-after
                        {:commands next-command
                         :fetched-values next-values})
      (if (pos? fetched)
        (assoc frame
               :values values
               :value-index 0
               :bound (edge/endpoint (peek values))
               :exhausted? (< fetched chunk-size))
        (next-partition frame)))))

(defn- target-key [roots subject-type subject-eid intermediate-eid partition]
  (let [permission (:target-node partition)
        root (get roots permission)]
    (when-not (some? root)
      (invalid! :missing-target-root
                "Operator plan is missing an arrow target root."
                {:permission permission}))
    [permission root subject-type subject-eid intermediate-eid]))

(defn- validate-arrow-witness!
  "A generator may discharge one exact arrow binding in this selected point.
   Its evidence is only a lower bound for the arrow's union of bindings."
  [plan qualification root-key witness]
  (when (some? witness)
    (execution/check! :arrow-witness)
    (when-not (and (map? witness)
                   (= #{:point :partition :intermediate :evidence :scope} (set (keys witness)))
                   qualification
                   (= root-key (:point witness))
                   (= (qualification/exact-reuse-identity qualification) (:scope witness)))
      (invalid! :arrow-witness-scope "Arrow witness must belong to this exact point and request." {}))
    (let [predicate (get-in (:predicate-programs plan) [(first root-key) (second root-key)])
          partition (:partition witness)]
      (when-not (and (= :arrow-membership (:instruction predicate))
                     (exact/natural? partition)
                     (< partition (count (get-in predicate [:descriptor :partitions])))
                     (exact/natural? (:intermediate witness)))
        (invalid! :arrow-witness-binding "Arrow witness names an invalid binding." {})))
    (when-not (boolean? (:evidence witness)) (evidence/encode (:evidence witness)))
    (when-not (evidence/before? (:time qualification) (evidence/valid-until (:evidence witness)))
      (invalid! :expired-arrow-witness "Arrow witness certificate has already expired." {})))
  witness)

(defn- known-arrow-binding? [witness root-key key partition-index intermediate-eid]
  (and (= root-key key) (= partition-index (:partition witness))
       (= intermediate-eid (:intermediate witness))))

(defn check-eids
  "Returns exact point membership for an acyclic operator plan.

  Required options are :adapter, :plan, :subject-type, :subject-eid, and
  :resource-eid. :limits may tighten the closed defaults. A nil endpoint is
  denied without backend work. Recursive plans fail with a typed transition
  requirement; they are never interpreted as false."
  [{:keys [adapter plan subject-type subject-eid resource-eid limits
           permission node-id qualification arrow-witness]}]
  (when-not (operator-plan/operator-plan? plan)
    (invalid! :operator-plan-required
              "Operator evaluation requires a sealed operator plan."
              {:plan-domain (:domain plan)}))
  (when-not (keyword? subject-type)
    (invalid! :invalid-subject-type
              "Operator evaluation subject type must be a keyword."
              {:subject-type subject-type}))
  (if (or (nil? subject-eid) (nil? resource-eid))
    false
    (do
      (when-not (acyclic-plan? plan)
        (throw
         (ex-info "Recursive operator plan requires stratified evaluation."
                  {:type :eacl.operator/recursive-plan-required
                   :eacl/error :eacl.operator/recursive-plan-required
                   :root (:root plan)})))
      (let [limits (normalize-limits limits)
            maximum-memo-entries (:maximum-memo-entries limits)
            predicate-programs (:predicate-programs plan)
            roots (plan-index plan)
            root-permission (or permission (:root plan))
            root-id (or node-id (get roots root-permission))
            root-key [root-permission root-id subject-type
                      subject-eid resource-eid]
            arrow-witness (validate-arrow-witness! plan qualification root-key arrow-witness)
            counters (volatile! {:transitions 0
                                 :arrow-commands 0
                                 :arrow-values 0})
            direct-match! (if qualification
                            (let [direct-edge! (backend/direct-edge-invoker adapter)]
                              (fn [st s r rt o]
                                (qualification/qualify qualification r (direct-edge! st s r rt o))))
                            (backend/direct-match-invoker adapter))
            resource->subjects!
            (backend/scan-invoker adapter :resource->subjects)]
        (when-not (some? root-id)
          (invalid! :missing-root "Operator plan root expression is missing."
                    {:root root-permission}))
        (loop [stack [{:kind :eval :key root-key}]
               memo (if (and arrow-witness (decisive? :union (:evidence arrow-witness)))
                      {root-key (:evidence arrow-witness)} {})
               active #{}
               returned no-value]
          (if (not= no-value returned)
            (if (empty? stack)
              (do
                (add-stat! :memo-entries (count memo))
                (add-stat! :transitions (:transitions @counters))
                returned)
              (let [{:keys [kind key remaining next-frame] :as continuation}
                    (peek stack)
                    stack (pop stack)]
                (case kind
                  :alias
                  (let [[memo active value]
                        (complete-value memo active key returned
                                        maximum-memo-entries)]
                    (recur stack memo active value))

                  :nary
                  (let [op (:op continuation)
                        accumulated (evidence/combine op (:accumulated continuation) returned)]
                    (if (or (decisive? op accumulated) (empty? remaining))
                      (let [[memo active value]
                            (complete-value memo active key accumulated maximum-memo-entries)]
                        (recur stack memo active value))
                      (recur (conj stack
                                   (assoc continuation :accumulated accumulated
                                          :remaining (subvec remaining 1))
                                   {:kind :eval
                                    :key [(first key) (first remaining)
                                          (nth key 2) (nth key 3) (nth key 4)]})
                             memo active no-value)))

                  :exclusion-left
                  (if (decisive? :exclusion returned)
                    (let [[memo active value]
                          (complete-value memo active key returned maximum-memo-entries)]
                      (recur stack memo active value))
                    (recur (conj stack
                                 {:kind :exclusion-right :key key :left returned}
                                 {:kind :eval
                                  :key [(first key) (:right continuation)
                                        (nth key 2) (nth key 3) (nth key 4)]})
                           memo active no-value))

                  :exclusion-right
                  (let [decision (evidence/combine :exclusion (:left continuation) returned)
                        [memo active value]
                        (complete-value memo active key decision maximum-memo-entries)]
                    (recur stack memo active value))

                  :arrow-child
                  (let [witness (evidence/combine :arrow (:via continuation) returned)
                        accumulated (evidence/combine :union (:accumulated next-frame) witness)]
                    (if (decisive? :union accumulated)
                      (let [[memo active value]
                            (complete-value memo active key accumulated maximum-memo-entries)]
                        (recur stack memo active value))
                      (recur (conj stack (assoc next-frame :accumulated accumulated))
                             memo active no-value)))

                  (invalid! :invalid-continuation
                            "Operator evaluator encountered an invalid continuation."
                            {:continuation continuation}))))
            (let [next-transition (inc (:transitions @counters))]
              (when (> next-transition (:maximum-transitions limits))
                (limit! :transitions (:maximum-transitions limits)
                        next-transition))
              (vswap! counters assoc :transitions next-transition)
              (execution/check! execution/*contract*
                                :operator-point/transition
                                {:transitions next-transition})
              (let [{:keys [kind key] :as frame} (peek stack)
                    stack (pop stack)]
                (case kind
                  :eval
                  (if (contains? memo key)
                    (do
                      (add-stat! :memo-hits 1)
                      (recur stack memo active (get memo key)))
                    (if (contains? active key)
                      (let [[memo active value]
                            (complete-value memo active key
                                            (active-recursion-outcome
                                             {:key key})
                                            maximum-memo-entries)]
                        (recur stack memo active value))
                      (let [[permission node-id current-subject-type
                             current-subject-eid current-resource-eid] key
                            predicate
                            (get-in predicate-programs [permission node-id])
                            instruction (:instruction predicate)
                            active (conj active key)]
                        (add-stat! :node-evaluations 1)
                        (case instruction
                          :direct-membership
                          (let [decision
                                (direct-match?
                                 direct-match! current-subject-type
                                 current-subject-eid (first permission)
                                 current-resource-eid
                                 (:descriptor predicate))
                                [memo active value]
                                (complete-value memo active key decision
                                                maximum-memo-entries)]
                            (recur stack memo active value))

                          :permission-membership
                          (let [target (:target-node predicate)
                                target-root (get roots target)]
                            (when-not (some? target-root)
                              (invalid! :missing-target-root
                                        "Permission predicate target is missing."
                                        {:target target}))
                            (recur (conj stack
                                         {:kind :alias :key key}
                                         {:kind :eval
                                          :key [target target-root
                                                current-subject-type
                                                current-subject-eid
                                                current-resource-eid]})
                                   memo active no-value))

                          :arrow-membership
                          (recur
                           (conj stack
                                 {:kind :arrow-next
                                  :key key
                                  :descriptor (:descriptor predicate)
                                  :partition-index 0
                                  :accumulated (if (and arrow-witness (= root-key key))
                                                 (:evidence arrow-witness) false)
                                  :values [] :value-index 0
                                  :bound nil :exhausted? false})
                           memo active no-value)

                          (:any-true :all-true)
                          (let [children (:children predicate)
                                op (if (= :any-true instruction)
                                     :union :intersection)
                                first-child (first children)]
                            (when-not first-child
                              (invalid! :empty-operator
                                        "Operator predicate has no children."
                                        {:permission permission
                                         :node-id node-id}))
                            (recur
                             (conj stack
                                   {:kind :nary :key key :op op
                                    :accumulated (not= :union op)
                                    :remaining (subvec children 1)}
                                   {:kind :eval
                                    :key [permission first-child
                                          current-subject-type
                                          current-subject-eid
                                          current-resource-eid]})
                             memo active no-value))

                          :left-and-not-right
                          (recur
                           (conj stack
                                 {:kind :exclusion-left :key key
                                  :right (:right predicate)}
                                 {:kind :eval
                                  :key [permission (:left predicate)
                                        current-subject-type
                                        current-subject-eid
                                        current-resource-eid]})
                           memo active no-value)

                          (invalid! :unknown-predicate-instruction
                                    "Operator plan contains an unknown predicate instruction."
                                    {:permission permission
                                     :node-id node-id
                                     :instruction instruction})))))

                  :arrow-next
                  (let [{:keys [descriptor partition-index values value-index
                                exhausted? accumulated]} frame
                        partitions (:partitions descriptor)
                        [stack memo active returned]
                        (cond
                          (>= partition-index (count partitions))
                          (let [[memo active value]
                                (complete-value memo active key accumulated maximum-memo-entries)]
                            [stack memo active value])

                          (< value-index (count values))
                          (let [compact-edge (nth values value-index)
                                intermediate-eid (edge/endpoint compact-edge)
                                partition (nth partitions partition-index)
                                known? (and arrow-witness
                                            (known-arrow-binding? arrow-witness root-key key
                                                                  partition-index intermediate-eid))
                                via (when-not known?
                                      (if qualification
                                        (qualification/qualify qualification (:via-relation-eid partition) compact-edge)
                                        true))
                                next-frame (update frame :value-index inc)]
                            (cond
                              known?
                              ;; The seed already contributed this exact binding.
                              ;; Visit only remaining target obligations.
                              [(conj stack next-frame) memo active no-value]

                              (evidence/fault? via)
                              (let [[memo active value]
                                    (complete-value memo active key via maximum-memo-entries)]
                                [stack memo active value])

                              (evidence/no? via)
                              [(conj stack (assoc next-frame :accumulated
                                                  (evidence/combine :union accumulated via)))
                               memo active no-value]

                              (= :relation (:target-kind partition))
                              (let [child (direct-match? direct-match! (nth key 2) (nth key 3)
                                                         (:intermediate-type partition) intermediate-eid
                                                         (:target-relation partition))
                                    witness (evidence/combine :arrow via child)
                                    result (evidence/combine :union accumulated witness)]
                                (if (decisive? :union result)
                                  (let [[memo active value]
                                        (complete-value memo active key result maximum-memo-entries)]
                                    [stack memo active value])
                                  [(conj stack (assoc next-frame :accumulated result))
                                   memo active no-value]))

                              :else
                              [(conj stack
                                     {:kind :arrow-child :key key :next-frame next-frame :via via}
                                     {:kind :eval
                                      :key (target-key roots (nth key 2) (nth key 3)
                                                       intermediate-eid partition)})
                               memo active no-value]))

                          exhausted?
                          [(conj stack (next-partition frame)) memo active no-value]

                          :else
                          [(conj stack (arrow-values! resource->subjects! frame limits counters qualification))
                           memo active no-value])]
                    (recur stack memo active returned))

                  (invalid! :invalid-frame
                            "Operator evaluator encountered an invalid frame."
                            {:frame frame}))))))))))

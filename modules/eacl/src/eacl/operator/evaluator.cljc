(ns eacl.operator.evaluator
  "Stack-safe exact point evaluation for acyclic operator plans.

  The machine evaluates the sealed predicate DAG with request-local completed
  memoization. Union and intersection retain scalar short-circuit demand;
  exclusion evaluates its right operand only after exact left success. Arrow
  scans stay on the selected immutable adapter basis and stop at the first
  exact witness."
  (:require [eacl.backend.v8 :as backend]
            [eacl.execution :as execution]
            [eacl.operator.plan :as operator-plan]
            [eacl.request.counters :as request-counters]
            [eacl.subproblem-cache :as subproblem]))

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

(defn- normalize-limits [overrides]
  (let [overrides (or overrides {})]
    (when-not (map? overrides)
      (invalid! :invalid-limits "Operator limits must be a map."
                {:value overrides}))
    (when-let [unknown (seq (remove (set (keys default-limits))
                                    (keys overrides)))]
      (invalid! :unknown-limit "Operator limits contain unknown keys."
                {:unknown-keys (vec unknown)
                 :known-keys (set (keys default-limits))}))
    (when-not (every? (fn [[_ value]] (and (integer? value) (pos? value)))
                      overrides)
      (invalid! :invalid-limit "Operator limits must be positive integers."
                {:limits overrides}))
    (merge default-limits overrides)))

(defn- acyclic-plan? [plan]
  (let [certificate (:dependency-certificate plan)]
    (and (every? #(= 1 (count %)) (:components certificate))
         (not-any? #(= (:from %) (:to %)) (:edges certificate)))))

(defn- plan-index [plan]
  (into {}
        (map (fn [{:keys [permission root]}] [permission root]))
        (:expressions plan)))

(defn- complete-value [memo active key value maximum]
  (when (and (not (contains? memo key))
             (>= (count memo) maximum))
    (limit! :memo-entries maximum (inc (count memo))))
  [(assoc memo key (boolean value)) (disj active key) (boolean value)])

(defn- relation-partition [descriptor subject-type]
  (first (filter #(= subject-type (:subject-type %))
                 (:partitions descriptor))))

(defn- direct-match?
  [adapter subject-type subject-eid resource-type resource-eid descriptor]
  (if-let [{:keys [relation-id]}
           (relation-partition descriptor subject-type)]
    (do
      (execution/check! execution/*contract*
                        :operator-point/direct-before
                        {:probes 0})
      (request-counters/add! :probes)
      (add-stat! :scalar-equivalent-predicates 1)
      (let [decision
            (backend/invoke adapter :direct-match?
                            subject-type subject-eid relation-id
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
  [adapter {:keys [key partition-index descriptor bound] :as frame}
   limits counters]
  (let [[permission _ _ _ resource-eid] key
        resource-type (first permission)
        partition (nth (:partitions descriptor) partition-index)]
    (execution/check! execution/*contract*
                      :operator-point/arrow-scan-before
                      {:commands (:arrow-commands @counters)
                       :fetched-values (:arrow-values @counters)})
    (let [options (cond-> {:direction :asc}
                    bound (assoc :bound-eid bound :inclusive-bound? false))
          cache-key
          [:operator-acyclic-arrow-scan 1
           {:resource-type resource-type
            :resource-eid resource-eid
            :relation-eid (:via-relation-eid partition)
            :intermediate-type (:intermediate-type partition)
            :options options
            :physical-chunk-size (:physical-chunk-size limits)}]
          resolved
          (subproblem/resolve-layered-bound!
           :projection cache-key
           {:valid? vector?
            :weight-fn #(max 128 (+ 128 (* 16 (count %))))}
           (:via-relation-eid partition)
           (fn []
             (let [next-command (inc (:arrow-commands @counters))]
               (when (> next-command (:maximum-arrow-commands limits))
                 (limit! :arrow-commands
                         (:maximum-arrow-commands limits) next-command))
               (let [values
                     (into []
                           (take (:physical-chunk-size limits))
                           (backend/invoke
                            adapter :resource->subjects
                            resource-type resource-eid
                            (:via-relation-eid partition)
                            (:intermediate-type partition)
                            options))]
                 (vswap! counters assoc :arrow-commands next-command)
                 (request-counters/add! :commands)
                 (request-counters/add! :fetched-values (count values))
                 (add-stat! :adapter-commands 1)
                 (add-stat! :adapter-fetched-values (count values))
                 values))))
          values (:value resolved)
          next-values (+ (:arrow-values @counters) (count values))]
      (when (> next-values (:maximum-arrow-values limits))
        (limit! :arrow-values (:maximum-arrow-values limits) next-values))
      (vswap! counters assoc :arrow-values next-values)
      (when (:cached? resolved)
        (add-stat! :shared-scan-cache-hits 1)
        (subproblem/record-avoided-backend-operation!))
      (execution/check! execution/*contract*
                        :operator-point/arrow-scan-after
                        {:commands (:arrow-commands @counters)
                         :fetched-values next-values})
      (if (seq values)
        (assoc frame
               :values values
               :value-index 0
               :bound (peek values)
               :exhausted? (< (count values)
                              (:physical-chunk-size limits)))
        (next-partition frame)))))

(defn- target-key [roots subject-type subject-eid intermediate-eid partition]
  (let [permission (:target-node partition)
        root (get roots permission)]
    (when-not (some? root)
      (invalid! :missing-target-root
                "Operator plan is missing an arrow target root."
                {:permission permission}))
    [permission root subject-type subject-eid intermediate-eid]))

(defn check-eids
  "Returns exact point membership for an acyclic operator plan.

  Required options are :adapter, :plan, :subject-type, :subject-eid, and
  :resource-eid. :limits may tighten the closed defaults. A nil endpoint is
  denied without backend work. Recursive plans fail with a typed transition
  requirement; they are never interpreted as false."
  [{:keys [adapter plan subject-type subject-eid resource-eid limits
           permission node-id]}]
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
            roots (plan-index plan)
            root-permission (or permission (:root plan))
            root-id (or node-id (get roots root-permission))
            root-key [root-permission root-id subject-type
                      subject-eid resource-eid]
            counters (volatile! {:transitions 0
                                 :arrow-commands 0
                                 :arrow-values 0})]
        (when-not (some? root-id)
          (invalid! :missing-root "Operator plan root expression is missing."
                    {:root root-permission}))
        (loop [stack [{:kind :eval :key root-key}]
               memo {}
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
                                        (:maximum-memo-entries limits))]
                    (recur stack memo active value))

                  :nary
                  (let [op (:op continuation)
                        decisive? (if (= :union op) returned (not returned))
                        decision (if (= :union op) true false)]
                    (if decisive?
                      (let [[memo active value]
                            (complete-value memo active key decision
                                            (:maximum-memo-entries limits))]
                        (recur stack memo active value))
                      (if-let [child (first remaining)]
                        (recur (conj stack
                                     (assoc continuation
                                            :remaining (subvec remaining 1))
                                     {:kind :eval
                                      :key [(first key) child
                                            (nth key 2) (nth key 3)
                                            (nth key 4)]})
                               memo active no-value)
                        (let [[memo active value]
                              (complete-value memo active key
                                              (if (= :union op) false true)
                                              (:maximum-memo-entries limits))]
                          (recur stack memo active value)))))

                  :exclusion-left
                  (if-not returned
                    (let [[memo active value]
                          (complete-value memo active key false
                                          (:maximum-memo-entries limits))]
                      (recur stack memo active value))
                    (recur (conj stack
                                 {:kind :exclusion-right :key key}
                                 {:kind :eval
                                  :key [(first key) (:right continuation)
                                        (nth key 2) (nth key 3) (nth key 4)]})
                           memo active no-value))

                  :exclusion-right
                  (let [[memo active value]
                        (complete-value memo active key (not returned)
                                        (:maximum-memo-entries limits))]
                    (recur stack memo active value))

                  :arrow-child
                  (if returned
                    (let [[memo active value]
                          (complete-value memo active key true
                                          (:maximum-memo-entries limits))]
                      (recur stack memo active value))
                    (recur (conj stack next-frame)
                           memo active no-value))

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
                                            (:maximum-memo-entries limits))]
                        (recur stack memo active value))
                      (let [[permission node-id current-subject-type
                             current-subject-eid current-resource-eid] key
                            predicate
                            (get-in plan [:predicate-programs permission node-id])
                            instruction (:instruction predicate)
                            active (conj active key)]
                        (add-stat! :node-evaluations 1)
                        (case instruction
                          :direct-membership
                          (let [decision
                                (direct-match?
                                 adapter current-subject-type
                                 current-subject-eid (first permission)
                                 current-resource-eid
                                 (:descriptor predicate))
                                [memo active value]
                                (complete-value memo active key decision
                                                (:maximum-memo-entries limits))]
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
                                exhausted?]} frame
                        partitions (:partitions descriptor)
                        [stack memo active returned]
                        (cond
                          (>= partition-index (count partitions))
                          (let [[memo active value]
                                (complete-value
                                 memo active key false
                                 (:maximum-memo-entries limits))]
                            [stack memo active value])

                          (< value-index (count values))
                          (let [intermediate-eid (nth values value-index)
                                partition (nth partitions partition-index)
                                next-frame (update frame :value-index inc)]
                            (if (= :relation (:target-kind partition))
                              (let [decision
                                    (direct-match?
                                     adapter (nth key 2) (nth key 3)
                                     (:intermediate-type partition)
                                     intermediate-eid
                                     (:target-relation partition))]
                                (if decision
                                  (let [[memo active value]
                                        (complete-value
                                         memo active key true
                                         (:maximum-memo-entries limits))]
                                    [stack memo active value])
                                  [(conj stack next-frame)
                                   memo active no-value]))
                              [(conj stack
                                     {:kind :arrow-child
                                      :key key :next-frame next-frame}
                                     {:kind :eval
                                      :key (target-key roots (nth key 2)
                                                       (nth key 3)
                                                       intermediate-eid
                                                       partition)})
                               memo active no-value]))

                          exhausted?
                          [(conj stack (next-partition frame))
                           memo active no-value]

                          :else
                          [(conj stack
                                 (arrow-values! adapter frame limits counters))
                           memo active no-value])]
                    (recur stack memo active returned))

                  (invalid! :invalid-frame
                            "Operator evaluator encountered an invalid frame."
                            {:frame frame}))))))))))

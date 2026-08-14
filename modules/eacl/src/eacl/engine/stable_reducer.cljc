(ns eacl.engine.stable-reducer
  "The generic stable-discovery reducer (adopt-stable-discovery-enumeration,
  tasks 5.1-5.3): one pure width-one machine executing sealed plans from
  eacl.engine.sealed-plan in either traversal direction.

  Semantics (design Decisions 3-6):
  - a right-edge vector stack; peek/pop is the canonical head;
  - exact per-kind admission via work-id: merge-point work (grants, reverse
    goals, emitted subjects) is keyed by target node + entity identity so
    union arms deduplicate; scan occurrences are keyed by rule ordinal +
    binding, excluding the resume bound;
  - logical release width is exactly ONE value per transition and is not
    configurable; physical fetch width is a pure acceleration knob;
  - the authoritative scan frame is the residual work item's :bound-eid
    (last released value, exclusive); fetched chunks are disposable
    request-local buffers under a per-request cap, evicted oldest-first;
  - the single root emission point is keyed by the emitted entity: forward
    emission is the first admission of [:grant root-node eid], reverse
    emission the first admission of [:reverse-subject type eid] — result
    uniqueness holds by construction;
  - traversal terminates when the discovered count reaches the target or
    the stack empties; direction lives entirely in the plan indexes and
    seeding, not in the machine.

  This is the direct width-one path: the only effectful call is the adapter
  scan at the canonical head (`fetch-values`), which is the preserved seam
  for any future concurrency change."
  (:require [eacl.backend.v8 :as backend]))

(def default-physical-chunk-size 64)
(def default-sidecar-cap 16)

;; ---------------------------------------------------------------------------
;; Admission identity
;; ---------------------------------------------------------------------------

(defn work-id
  "Exact admission key per work kind. Scan occurrences exclude :bound-eid;
  merge points are keyed by node + entity."
  [{:keys [kind rule] :as item}]
  (case kind
    :seed-relation [:seed-relation (:ordinal rule)
                    (:subject-type item) (:subject-eid item)]
    :seed-arrow-relation [:seed-arrow-relation (:ordinal rule)
                          (:subject-type item) (:subject-eid item)]
    :via-scan [:via-scan (:ordinal rule) (:intermediate-eid item)]
    :grant [:grant (:node rule) (:resource-eid item)]
    :consumer [:consumer (:ordinal rule) (:resource-eid item)]
    :reverse-goal [:reverse-goal (:node rule) (:resource-eid item)]
    :reverse-direct [:reverse-direct (:ordinal rule) (:resource-eid item)]
    :reverse-via-permission [:reverse-via-permission (:ordinal rule)
                             (:resource-eid item)]
    :reverse-via-relation [:reverse-via-relation (:ordinal rule)
                           (:resource-eid item)]
    :reverse-base-subjects [:reverse-base-subjects (:ordinal rule)
                            (:intermediate-eid item)]
    :reverse-subject [:reverse-subject (:subject-type item)
                      (:subject-eid item)]))

;; ---------------------------------------------------------------------------
;; Scheduling: right-edge stack, canonical order, exact admission
;; ---------------------------------------------------------------------------

(defn- schedule
  "Admits fresh work exactly once and pushes it after the residual: the
  residual is pushed first, then new work reversed onto the right edge, so
  successors run depth-first in canonical order before the residual resumes.
  Push order is load-bearing for the public order ABI."
  [{:keys [admitted] :as state} residual new-work]
  (let [fresh (into []
                    (comp (remove nil?)
                          (filter #(not (contains? admitted (work-id %)))))
                    new-work)
        stack (cond-> (:stack state)
                residual (conj residual))
        stack (into stack (rseq fresh))]
    (-> state
        (assoc :stack stack)
        (update :admitted into (map work-id) fresh)
        (update :admissions + (count fresh))
        (update :maximum-stack max (count stack)))))

;; ---------------------------------------------------------------------------
;; One-value scan release with bounded disposable buffers
;; ---------------------------------------------------------------------------

(defn- evict-to-cap
  [{:keys [sidecar-cap] :as state}]
  (loop [state state]
    (let [order (:sidecar-order state)]
      (if (<= (count order) sidecar-cap)
        state
        (recur (-> state
                   (update :sidecar dissoc (first order))
                   (assoc :sidecar-order (subvec order 1))))))))

(defn- retain-buffer
  "Retains remaining fetched values as the newest buffer; a refreshed
  identity moves to the newest slot. Cap zero disables retention."
  [state identity values more-physical?]
  (let [state (-> state
                  (update :sidecar dissoc identity)
                  (update :sidecar-order #(vec (remove #{identity} %))))]
    (if (and (pos? (:sidecar-cap state))
             (or (seq values) more-physical?))
      (-> state
          (assoc-in [:sidecar identity]
                    {:values values :more-physical? more-physical?})
          (update :sidecar-order conj identity)
          evict-to-cap
          (as-> state'
                (-> state'
                    (update :maximum-sidecar-buffers
                            max (count (:sidecar-order state')))
                    (update :maximum-sidecar-values
                            max (reduce + 0 (map (comp count :values)
                                                 (vals (:sidecar state'))))))))
      state)))

(defn- fetch-values
  "The single effectful call: fetches one physical chunk strictly after the
  exclusive bound. This is the width-one NeedRead seam."
  [state scan-fn bound-eid]
  (let [values (into [] (take (:physical-chunk-size state)) (scan-fn bound-eid))]
    [(-> state
         (update :commands inc)
         (update :fetched-values + (count values)))
     values
     (= (count values) (:physical-chunk-size state))]))

(defn- release-one
  "Releases exactly one ordered scan value for `item`, preferring the
  retained buffer and refetching from the authoritative bound otherwise.
  Returns [state value residual-item] where value is nil on exhaustion and
  residual-item is nil when the scan is complete."
  [state item scan-fn]
  (let [identity (work-id item)
        entry (get-in state [:sidecar identity])]
    (if (seq (:values entry))
      (let [value (first (:values entry))
            remaining (subvec (:values entry) 1)
            more? (:more-physical? entry)
            state (retain-buffer state identity remaining more?)]
        [state value (when (or (seq remaining) more?)
                       (assoc item :bound-eid value))])
      (let [[state values more?] (fetch-values state scan-fn (:bound-eid item))]
        (if (empty? values)
          [(retain-buffer state identity [] false) nil nil]
          (let [value (first values)
                remaining (subvec values 1)
                state (retain-buffer state identity remaining more?)]
            [state value (when (or (seq remaining) more?)
                           (assoc item :bound-eid value))]))))))

;; ---------------------------------------------------------------------------
;; Transitions
;; ---------------------------------------------------------------------------

(defn- subject->resources-scan
  [adapter subject-type subject-eid relation-eid resource-type]
  (fn [bound-eid]
    (backend/invoke adapter :subject->resources
                    subject-type subject-eid relation-eid resource-type
                    (cond-> {:direction :asc}
                      bound-eid (assoc :bound-eid bound-eid
                                       :inclusive-bound? false)))))

(defn- resource->subjects-scan
  [adapter resource-type resource-eid relation-eid subject-type]
  (fn [bound-eid]
    (backend/invoke adapter :resource->subjects
                    resource-type resource-eid relation-eid subject-type
                    (cond-> {:direction :asc}
                      bound-eid (assoc :bound-eid bound-eid
                                       :inclusive-bound? false)))))

(defn- scan-transition
  "Releases one value and schedules its successors before the residual."
  [state item scan-fn value->successors]
  (let [[state value residual] (release-one state item scan-fn)]
    (if (nil? value)
      (schedule state residual [])
      (schedule state residual (value->successors value)))))

(defn- emit
  [state eid]
  (-> state
      (update :discovered inc)
      (update :results conj eid)))

(defn- grant-successors
  "Consumers of a grant at `node` for entity `eid`: self-permission
  consumers become grants at the head node; arrow-permission consumers
  become consumer scans from the intermediate entity."
  [plan node eid]
  (mapv (fn [consumer]
          (case (:rule consumer)
            :self-permission {:kind :grant :rule consumer :resource-eid eid}
            :arrow-permission {:kind :consumer :rule consumer
                               :resource-eid eid :bound-eid nil}))
        (get-in plan [:indexes :forward-consumers node])))

(defn- reverse-goal-work
  "Expands one reverse goal at `node` for resource `eid` through the sealed
  reverse index, filtered to the requested subject type where the rule
  binds one."
  [plan subject-type node eid]
  (into []
        (keep (fn [rule]
                (case (:rule rule)
                  :relation
                  (when (= subject-type (:subject-type rule))
                    {:kind :reverse-direct :rule rule
                     :resource-eid eid :bound-eid nil})
                  :self-permission
                  {:kind :reverse-goal :rule {:node (:target-node rule)}
                   :resource-eid eid}
                  :arrow-permission
                  {:kind :reverse-via-permission :rule rule
                   :resource-eid eid :bound-eid nil}
                  :arrow-relation
                  (when (= subject-type (:target-subject-type rule))
                    {:kind :reverse-via-relation :rule rule
                     :resource-eid eid :bound-eid nil}))))
        (get-in plan [:indexes :reverse-rules node])))

(defn- step
  "One bounded pure transition of the unified machine. The work kinds are
  disjoint across directions; the plan and seeding determine which kinds
  ever appear."
  [{:keys [plan adapter subject-type root] :as context} state item]
  (let [rule (:rule item)]
    (case (:kind item)
      ;; ---- forward ----
      :seed-relation
      (scan-transition
       state item
       (subject->resources-scan adapter (:subject-type item)
                                (:subject-eid item) (:relation-eid rule)
                                (:resource-type rule))
       (fn [eid] [{:kind :grant :rule rule :resource-eid eid}]))

      :seed-arrow-relation
      (scan-transition
       state item
       (subject->resources-scan adapter (:subject-type item)
                                (:subject-eid item)
                                (:target-relation-eid rule)
                                (:intermediate-type rule))
       (fn [eid] [{:kind :via-scan :rule rule
                   :intermediate-eid eid :bound-eid nil}]))

      :via-scan
      (scan-transition
       state item
       (subject->resources-scan adapter (:intermediate-type rule)
                                (:intermediate-eid item)
                                (:via-relation-eid rule)
                                (:resource-type rule))
       (fn [eid] [{:kind :grant :rule rule :resource-eid eid}]))

      :grant
      (let [node (:node rule)
            eid (:resource-eid item)
            state (cond-> state
                    (= node root) (emit eid))]
        (schedule state nil (grant-successors plan node eid)))

      :consumer
      (scan-transition
       state item
       (subject->resources-scan adapter (:intermediate-type rule)
                                (:resource-eid item)
                                (:via-relation-eid rule)
                                (:resource-type rule))
       (fn [eid] [{:kind :grant :rule rule :resource-eid eid}]))

      ;; ---- reverse ----
      :reverse-goal
      (schedule state nil
                (reverse-goal-work plan subject-type (:node rule)
                                   (:resource-eid item)))

      :reverse-direct
      (scan-transition
       state item
       (resource->subjects-scan adapter (:resource-type rule)
                                (:resource-eid item) (:relation-eid rule)
                                (:subject-type rule))
       (fn [eid] [{:kind :reverse-subject :subject-type subject-type
                   :subject-eid eid}]))

      :reverse-via-permission
      (scan-transition
       state item
       (resource->subjects-scan adapter (:resource-type rule)
                                (:resource-eid item)
                                (:via-relation-eid rule)
                                (:intermediate-type rule))
       (fn [eid] [{:kind :reverse-goal :rule {:node (:target-node rule)}
                   :resource-eid eid}]))

      :reverse-via-relation
      (scan-transition
       state item
       (resource->subjects-scan adapter (:resource-type rule)
                                (:resource-eid item)
                                (:via-relation-eid rule)
                                (:intermediate-type rule))
       (fn [eid] [{:kind :reverse-base-subjects :rule rule
                   :intermediate-eid eid :bound-eid nil}]))

      :reverse-base-subjects
      (scan-transition
       state item
       (resource->subjects-scan adapter (:intermediate-type rule)
                                (:intermediate-eid item)
                                (:target-relation-eid rule)
                                (:target-subject-type rule))
       (fn [eid] [{:kind :reverse-subject :subject-type subject-type
                   :subject-eid eid}]))

      :reverse-subject
      (emit state (:subject-eid item)))))

;; ---------------------------------------------------------------------------
;; Entry points
;; ---------------------------------------------------------------------------

(defn- initial-state
  [{:keys [physical-chunk-size sidecar-cap]
    :or {physical-chunk-size default-physical-chunk-size
         sidecar-cap default-sidecar-cap}}]
  {:pre [(pos? physical-chunk-size) (int? sidecar-cap) (<= 0 sidecar-cap)]}
  {:stack []
   :admitted #{}
   :admissions 0
   :transitions 0
   :commands 0
   :fetched-values 0
   :sidecar {}
   :sidecar-order []
   :sidecar-cap sidecar-cap
   :physical-chunk-size physical-chunk-size
   :maximum-sidecar-buffers 0
   :maximum-sidecar-values 0
   :maximum-stack 0
   :discovered 0
   :results []})

(defn- run-loop
  [context state target cut-point!]
  (loop [state state]
    (cond
      (>= (:discovered state) target)
      state

      (empty? (:stack state))
      state

      :else
      (let [_ (when cut-point! (cut-point! state))
            item (peek (:stack state))
            state (-> state
                      (update :stack pop)
                      (update :transitions inc))]
        (recur (step context state item))))))

(defn- finish [state]
  (assoc state :completed (- (count (:admitted state))
                             (count (:stack state)))))

(defn run-forward
  "Enumerates root resources the subject reaches, in stable first-discovery
  order, until `target` results or exhaustion. Returns the final state;
  :results is the canonical sequence of internal resource ids."
  [{:keys [adapter plan subject-type subject-eid target cut-point!]
    :as options}]
  {:pre [(some? adapter) (some? plan) (keyword? subject-type)
         (some? subject-eid) (pos? target)]}
  (let [context {:plan plan :adapter adapter :root (:root plan)
                 :subject-type subject-type}
        seeds (mapv (fn [rule]
                      (case (:rule rule)
                        :relation {:kind :seed-relation :rule rule
                                   :subject-type subject-type
                                   :subject-eid subject-eid :bound-eid nil}
                        :arrow-relation {:kind :seed-arrow-relation :rule rule
                                         :subject-type subject-type
                                         :subject-eid subject-eid
                                         :bound-eid nil}))
                    (get-in plan [:indexes :forward-seeds subject-type]))
        state (schedule (initial-state options) nil seeds)]
    (finish (run-loop context state target (:cut-point! options)))))

(defn run-reverse
  "Enumerates subjects of `subject-type` that reach the root permission on
  `resource-eid`, in stable first-discovery order, until `target` results
  or exhaustion. Returns the final state; :results is the canonical
  sequence of internal subject ids."
  [{:keys [adapter plan subject-type resource-eid target cut-point!]
    :as options}]
  {:pre [(some? adapter) (some? plan) (keyword? subject-type)
         (some? resource-eid) (pos? target)]}
  (let [context {:plan plan :adapter adapter :root (:root plan)
                 :subject-type subject-type}
        goal {:kind :reverse-goal :rule {:node (:root plan)}
              :resource-eid resource-eid}
        state (schedule (initial-state options) nil [goal])]
    (finish (run-loop context state target (:cut-point! options)))))

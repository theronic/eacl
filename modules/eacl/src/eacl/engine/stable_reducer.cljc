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
  (:require [eacl.backend.v8 :as backend]
            [eacl.request.counters :as request-counters]))

(def default-physical-chunk-size 64)
(def default-sidecar-cap 16)
(def default-max-admissions 1000000)

(def exhaustion-target
  "The result target that never stops a run: an exhaustive run ends only when
  the stack empties (the exact answer) or a typed limit fails. Never a finite
  constant — a finite target caps counts, point checks and last windows
  silently once the public limits authorize more work than the constant."
  #?(:clj Double/POSITIVE_INFINITY :cljs js/Number.POSITIVE_INFINITY))
(def default-max-commands 1000000)
(def default-max-transitions 4000000)
(def default-max-values 4000000)
(def default-max-stack 1000000)

;; ---------------------------------------------------------------------------
;; Admission identity: specialized immutable per-kind keys (task 5.2)
;; ---------------------------------------------------------------------------

#?(:clj
   (deftype AdmissionKey [kind a b
                          ^:unsynchronized-mutable ^int cached-hash]
     Object
     (hashCode [this] (.hasheq this))
     (equals [_ other]
       (and (instance? AdmissionKey other)
            (identical? kind (.-kind ^AdmissionKey other))
            (= a (.-a ^AdmissionKey other))
            (= b (.-b ^AdmissionKey other))))
     clojure.lang.IHashEq
     (hasheq [_]
       (let [h cached-hash]
         (if (zero? h)
           (let [h (-> (hash kind)
                       (hash-combine (hash a))
                       (hash-combine (hash b)))
                 h (if (zero? h) 42 h)]
             (set! cached-hash (int h))
             h)
           h)))))

(defn- admission-key [kind a b]
  #?(:clj (AdmissionKey. kind a b 0)
     :cljs [kind a b]))

(defn work-id
  "Exact admission key per work kind. Scan occurrences exclude :bound-eid;
  merge points are keyed by node + entity. Seed keys fold the subject
  binding into one field because the ordinal already fixes the rule (and
  with it the subject type)."
  [{:keys [kind rule] :as item}]
  (case kind
    :seed-relation (admission-key :seed-relation (:ordinal rule)
                                  (:subject-eid item))
    :seed-arrow-relation (admission-key :seed-arrow-relation (:ordinal rule)
                                        (:subject-eid item))
    :via-scan (admission-key :via-scan (:ordinal rule)
                             (:intermediate-eid item))
    :grant (admission-key :grant (:node rule) (:resource-eid item))
    :consumer (admission-key :consumer (:ordinal rule) (:resource-eid item))
    :reverse-goal (admission-key :reverse-goal (:node rule)
                                 (:resource-eid item))
    :reverse-direct (admission-key :reverse-direct (:ordinal rule)
                                   (:resource-eid item))
    :reverse-via-permission (admission-key :reverse-via-permission
                                           (:ordinal rule)
                                           (:resource-eid item))
    :reverse-via-relation (admission-key :reverse-via-relation
                                         (:ordinal rule)
                                         (:resource-eid item))
    :reverse-base-subjects (admission-key :reverse-base-subjects
                                          (:ordinal rule)
                                          (:intermediate-eid item))
    :reverse-subject (admission-key :reverse-subject (:subject-type item)
                                    (:subject-eid item))))

;; ---------------------------------------------------------------------------
;; Scheduling: right-edge stack, canonical order, exact admission
;; ---------------------------------------------------------------------------

(defn- limit-failure!
  [limit-key state detail]
  (throw (ex-info "Stable-discovery semantic limit exceeded."
                  (merge {:eacl/error :eacl.reducer/limit-exceeded
                          :limit limit-key
                          :admissions (:admissions state)
                          :transitions (:transitions state)
                          :commands (:commands state)
                          :discovered (:discovered state)}
                         detail))))

(defn ^:no-doc schedule
  "Admits fresh work exactly once and pushes it after the residual: the
  residual is pushed first, then new work reversed onto the right edge, so
  successors run depth-first in canonical order before the residual resumes.
  Push order is load-bearing for the public order ABI.

  Admission limits are checked before any state mutates, so a rejected
  transition commits nothing (staged atomic admission)."
  [{:keys [admitted] :as state} residual new-work]
  (letfn [(check-limits! [fresh-count]
            (when (> (+ (:admissions state) fresh-count)
                     (:max-admissions state))
              (limit-failure! :max-admissions state
                              {:max-admissions (:max-admissions state)
                               :staged fresh-count}))
            ;; :max-stack is instantaneous queue depth, never cumulative
            ;; scheduled work.
            (when (> (+ (count (:stack state))
                        (if residual 1 0)
                        fresh-count)
                     (:max-stack state))
              (limit-failure! :max-stack state
                              {:max-stack (:max-stack state)
                               :staged fresh-count})))
          (commit-zero []
            (check-limits! 0)
            (if residual
              (let [stack (conj (:stack state) residual)]
                (-> state
                    (assoc :stack stack)
                    (update :maximum-stack max (count stack))))
              state))
          (commit-one [item id]
            (check-limits! 1)
            (let [stack (cond-> (:stack state)
                          residual (conj residual)
                          true (conj item))]
              (-> state
                  (assoc :stack stack :admitted (conj! admitted id))
                  (update :admissions inc)
                  (update :maximum-stack max (count stack)))))]
    ;; Zero and one successor dominate live traces. They do not allocate the
    ;; batch-local transient set/vector used by the general fan-out oracle.
    (let [items (seq new-work)]
      (cond
        (nil? items)
        (commit-zero)

        (nil? (next items))
        (if-let [item (first items)]
          (let [id (work-id item)]
            (if (contains? admitted id)
              (commit-zero)
              (commit-one item id)))
          (commit-zero))

        :else
        (let [fresh
              (loop [items items
                     seen (transient #{})
                     fresh (transient [])]
                (if items
                  (let [item (first items)]
                    (if (nil? item)
                      (recur (next items) seen fresh)
                      (let [id (work-id item)]
                        (if (or (contains? admitted id)
                                (contains? seen id))
                          (recur (next items) seen fresh)
                          (recur (next items) (conj! seen id)
                                 (conj! fresh [item id]))))))
                  (persistent! fresh)))
              _ (check-limits! (count fresh))
              stack (cond-> (:stack state)
                      residual (conj residual))
              stack (into stack (map first) (rseq fresh))
              admitted (reduce (fn [acc [_ id]] (conj! acc id))
                               admitted fresh)]
          (-> state
              (assoc :stack stack :admitted admitted)
              (update :admissions + (count fresh))
              (update :maximum-stack max (count stack))))))))

;; ---------------------------------------------------------------------------
;; One-value scan release with bounded disposable buffers
;; ---------------------------------------------------------------------------

(defn- remaining-buffer-values
  [entry]
  (if entry
    (- (count (:values entry)) (:index entry 0))
    0))

(defn- remove-buffer
  [state identity]
  (if-let [entry (get-in state [:sidecar identity])]
    (-> state
        (update :sidecar dissoc identity)
        (update :current-sidecar-values -
                (remaining-buffer-values entry)))
    state))

(defn- compact-sidecar-order
  [{:keys [sidecar-cap sidecar-order sidecar-order-index sidecar] :as state}]
  (let [pending (- (count sidecar-order) sidecar-order-index)
        ceiling (max 1 (* 2 sidecar-cap))]
    (if (or (> pending ceiling)
            (> sidecar-order-index sidecar-cap))
      (assoc state
             :sidecar-order
             (->> sidecar
                  (map (fn [[identity entry]]
                         [identity (:generation entry)]))
                  (sort-by second)
                  vec)
             :sidecar-order-index 0)
      state)))

(defn- evict-to-cap
  [{:keys [sidecar-cap] :as state}]
  (loop [state state]
    (if (<= (count (:sidecar state)) sidecar-cap)
      (compact-sidecar-order state)
      (let [index (:sidecar-order-index state)
            [identity generation] (nth (:sidecar-order state) index)
            entry (get-in state [:sidecar identity])
            state (assoc state :sidecar-order-index (inc index))]
        (if (= generation (:generation entry))
          (recur (remove-buffer state identity))
          (recur state))))))

(defn- retain-buffer
  "Retains one fetched vector plus an index, never a suffix view. Recency is
  generation-stamped and compacted at a capacity-derived ceiling; a touch
  appends one pair and performs no whole-order filter."
  [state identity values index more-physical?]
  (let [state (remove-buffer state identity)]
    (if (and (pos? (:sidecar-cap state))
             (or (< index (count values)) more-physical?))
      (let [generation (inc (:sidecar-clock state))
            retained (- (count values) index)
            state (-> state
                      (assoc :sidecar-clock generation)
                      (assoc-in [:sidecar identity]
                                {:values values
                                 :index index
                                 :more-physical? more-physical?
                                 :generation generation})
                      (update :current-sidecar-values + retained)
                      (update :sidecar-order conj [identity generation])
                      compact-sidecar-order
                      evict-to-cap)]
        (-> state
            (update :maximum-sidecar-buffers
                    max (count (:sidecar state)))
            (update :maximum-sidecar-values
                    max (:current-sidecar-values state))))
      (compact-sidecar-order state))))

(defn- advance-buffer
  "Consumes one value from an existing buffer without re-admitting that
  buffer to the recency index. The index entry already represents this live
  scan; rebuilding its map entry and appending a generation record for every
  value made physical chunking more expensive than the traversal itself."
  [state identity entry next-index]
  (if (and (= next-index (count (:values entry)))
           (not (:more-physical? entry)))
    ;; remove-buffer observes the old index, so it subtracts the one value
    ;; consumed by this transition.
    (remove-buffer state identity)
    (-> state
        (assoc-in [:sidecar identity :index] next-index)
        (update :current-sidecar-values dec))))

(defn ^:no-doc bounded-vector
  "Reuses a routed vector when it already satisfies the requested bound.
  Wider or non-vector adapter results are realized into one bounded vector."
  [values limit]
  (if (and (vector? values) (<= (count values) limit))
    values
    (into [] (take limit) values)))

(defn- fetch-values
  "The single effectful call: realizes one physical chunk strictly after the
  exclusive bound for an explicit read-demand descriptor. This is the
  width-one NeedRead seam (task 5.3): the reducer's semantic state is
  untouched until the complete chunk is realized, so a thrown adapter
  failure leaves state unchanged, and a future concurrency change replaces
  only `fetch-fn`."
  [{:keys [fetch-fn] :as state} descriptor bound-eid]
  (when (>= (:commands state) (:max-commands state))
    (limit-failure! :max-commands state
                    {:max-commands (:max-commands state)}))
  (let [values (bounded-vector
                (fetch-fn (assoc descriptor
                                 :bound-eid bound-eid
                                 :limit (:physical-chunk-size state)))
                (:physical-chunk-size state))]
    ;; :max-values bounds consumed projection values (the public
    ;; :max-advanced-datoms contract); the whole chunk is rejected before
    ;; any of it commits.
    (when (> (+ (:fetched-values state) (count values))
             (:max-values state))
      (limit-failure! :max-values state
                      {:max-values (:max-values state)
                       :staged (count values)}))
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
  [state item descriptor]
  (let [identity (work-id item)
        entry (get-in state [:sidecar identity])
        index (:index entry 0)]
    (if (and entry (< index (count (:values entry))))
      (let [value (nth (:values entry) index)
            next-index (inc index)
            more? (:more-physical? entry)
            state (advance-buffer state identity entry next-index)]
        [state value (when (or (< next-index (count (:values entry))) more?)
                       (assoc item :bound-eid value))])
      (let [[state values more?] (fetch-values state descriptor
                                               (:bound-eid item))]
        (if (empty? values)
          [(retain-buffer state identity [] 0 false) nil nil]
          (let [value (first values)
                next-index 1
                state (retain-buffer state identity values next-index more?)]
            [state value (when (or (< next-index (count values)) more?)
                           (assoc item :bound-eid value))]))))))

;; ---------------------------------------------------------------------------
;; Transitions
;; ---------------------------------------------------------------------------

(defn- subject->resources-scan
  "Equality-complete read-demand descriptor for a forward scan."
  [subject-type subject-eid relation-eid resource-type]
  {:operation :subject->resources
   :subject-type subject-type :subject-eid subject-eid
   :relation-eid relation-eid :resource-type resource-type})

(defn- resource->subjects-scan
  "Equality-complete read-demand descriptor for a reverse scan."
  [resource-type resource-eid relation-eid subject-type]
  {:operation :resource->subjects
   :resource-type resource-type :resource-eid resource-eid
   :relation-eid relation-eid :subject-type subject-type})

(defn adapter-fetch-fn
  "The direct width-one path: realizes one read-demand descriptor against
  the adapter with strictly-ascending exclusive-bound scan options."
  [adapter]
  (fn [{:keys [operation bound-eid] :as descriptor}]
    (let [options (cond-> {:direction :asc}
                    (:limit descriptor)
                    (assoc :limit (:limit descriptor))
                    bound-eid (assoc :bound-eid bound-eid
                                     :inclusive-bound? false))]
      (case operation
        :subject->resources
        (backend/invoke adapter :subject->resources
                        (:subject-type descriptor) (:subject-eid descriptor)
                        (:relation-eid descriptor) (:resource-type descriptor)
                        options)
        :resource->subjects
        (backend/invoke adapter :resource->subjects
                        (:resource-type descriptor) (:resource-eid descriptor)
                        (:relation-eid descriptor) (:subject-type descriptor)
                        options)))))

(defn- scan-transition
  "Releases one value and schedules its successors before the residual."
  [state item descriptor value->successors]
  (let [[state value residual] (release-one state item descriptor)]
    (if (nil? value)
      (schedule state residual [])
      (schedule state residual (value->successors value)))))

(defn- emit
  [state eid]
  (let [state (update state :discovered inc)]
    (case (:result-sink state)
      :count state
      :collect (update state :results conj! eid)
      :window
      (let [limit (:result-window-size state)
            index (:result-index state)
            results (conj (:results state) eid)
            index (if (> (- (count results) index) limit)
                    (inc index)
                    index)]
        ;; Compact only after one full window has become unreachable. The
        ;; retained backing vector is therefore always below 2*limit while
        ;; each emission remains amortized constant-time.
        (if (>= index limit)
          (assoc state
                 :results (into [] (drop index results))
                 :result-index 0)
          (assoc state :results results :result-index index))))))

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
  [{:keys [plan subject-type root]} state item]
  (let [rule (:rule item)]
    (case (:kind item)
      ;; ---- forward ----
      :seed-relation
      (scan-transition
       state item
       (subject->resources-scan (:subject-type item)
                                (:subject-eid item) (:relation-eid rule)
                                (:resource-type rule))
       (fn [eid] [{:kind :grant :rule rule :resource-eid eid}]))

      :seed-arrow-relation
      (scan-transition
       state item
       (subject->resources-scan (:subject-type item)
                                (:subject-eid item)
                                (:target-relation-eid rule)
                                (:intermediate-type rule))
       (fn [eid] [{:kind :via-scan :rule rule
                   :intermediate-eid eid :bound-eid nil}]))

      :via-scan
      (scan-transition
       state item
       (subject->resources-scan (:intermediate-type rule)
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
       (subject->resources-scan (:intermediate-type rule)
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
       (resource->subjects-scan (:resource-type rule)
                                (:resource-eid item) (:relation-eid rule)
                                (:subject-type rule))
       (fn [eid] [{:kind :reverse-subject :subject-type subject-type
                   :subject-eid eid}]))

      :reverse-via-permission
      (scan-transition
       state item
       (resource->subjects-scan (:resource-type rule)
                                (:resource-eid item)
                                (:via-relation-eid rule)
                                (:intermediate-type rule))
       (fn [eid] [{:kind :reverse-goal :rule {:node (:target-node rule)}
                   :resource-eid eid}]))

      :reverse-via-relation
      (scan-transition
       state item
       (resource->subjects-scan (:resource-type rule)
                                (:resource-eid item)
                                (:via-relation-eid rule)
                                (:intermediate-type rule))
       (fn [eid] [{:kind :reverse-base-subjects :rule rule
                   :intermediate-eid eid :bound-eid nil}]))

      :reverse-base-subjects
      (scan-transition
       state item
       (resource->subjects-scan (:intermediate-type rule)
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
  "Request-owned state. The admitted set and collecting result vector are
  transients owned linearly by the run loop; scalar count and bounded window
  sinks do not construct a full result history. Limits are checked before
  their transition commits."
  [{:keys [adapter fetch-fn physical-chunk-size sidecar-cap result-sink
           result-window-size
           max-admissions max-commands max-transitions
           max-values max-stack]
    :or {physical-chunk-size default-physical-chunk-size
         sidecar-cap default-sidecar-cap
         max-admissions default-max-admissions
         max-commands default-max-commands
         max-transitions default-max-transitions
         max-values default-max-values
         max-stack default-max-stack
         result-sink :collect}}]
  {:pre [(pos? physical-chunk-size) (int? sidecar-cap) (<= 0 sidecar-cap)
         (contains? #{:collect :count :window} result-sink)
         (or (not= :window result-sink) (pos-int? result-window-size))
         (pos? max-admissions) (pos? max-commands) (pos? max-transitions)
         (pos? max-values) (pos? max-stack)]}
  {:stack []
   :admitted (transient #{})
   :admissions 0
   :transitions 0
   :commands 0
   :fetched-values 0
   :fetch-fn (or fetch-fn (adapter-fetch-fn adapter))
   :sidecar {}
   :sidecar-order []
   :sidecar-order-index 0
   :sidecar-clock 0
   :current-sidecar-values 0
   :sidecar-cap sidecar-cap
   :physical-chunk-size physical-chunk-size
   :max-admissions max-admissions
   :max-commands max-commands
   :max-transitions max-transitions
   :max-values max-values
   :max-stack max-stack
   :maximum-sidecar-buffers 0
   :maximum-sidecar-values 0
   :maximum-stack 0
   :discovered 0
   :result-sink result-sink
   :result-window-size result-window-size
   :result-index 0
   :results (case result-sink
              :collect (transient [])
              :window []
              :count nil)})

(defn- run-loop
  [context state target cut-point!]
  (loop [state state]
    (cond
      (>= (:discovered state) target)
      state

      (empty? (:stack state))
      state

      :else
      (do
        (when (>= (:transitions state) (:max-transitions state))
          (limit-failure! :max-transitions state
                          {:max-transitions (:max-transitions state)}))
        (when cut-point! (cut-point! state))
        (let [item (peek (:stack state))
              state (-> state
                        (update :stack pop)
                        (update :transitions inc))]
          (recur (step context state item)))))))

(defn ^:no-doc finish
  "Freezes request-owned transients. Collecting sinks check result count
  against the constructionally unique root admissions; scalar count sinks
  never construct a delivered-result history."
  [state]
  (let [sink (:result-sink state)
        collect? (= :collect sink)
        results (case sink
                  :collect (persistent! (:results state))
                  :window (into [] (drop (:result-index state)
                                         (:results state)))
                  :count [])
        admitted (persistent! (:admitted state))]
    (when-not (or (not collect?)
                  (= (- (:discovered state) (:base-discovered state 0))
                     (count results)))
      (throw (ex-info "Stable-discovery structural invariant violated."
                      {:eacl/error :eacl.reducer/invariant-violation
                       :discovered (:discovered state)
                       :base-discovered (:base-discovered state 0)
                       :result-count (count results)})))
    (-> state
        (assoc :results results
               :admitted admitted
               :completed (- (count admitted) (count (:stack state))))
        (dissoc :fetch-fn))))

;; ---------------------------------------------------------------------------
;; History-free checkpointing and resumption (section 6 support)
;; ---------------------------------------------------------------------------

(def ^:private semantic-state-keys
  "The complete history-free semantic state: everything a checkpoint needs
  to reproduce the residual canonical suffix. Excludes delivered results,
  physical buffers, and runtime configuration."
  [:stack :admitted :admissions :transitions :commands :fetched-values
   :discovered :maximum-stack])

(defn history-free
  "Extracts checkpointable state from a finished run: exact admitted
  identities, stack, deterministic counters, and the scalar discovered
  count — no delivered results, no buffers, no configuration."
  [finished-state]
  (select-keys finished-state semantic-state-keys))

(def ^:dynamic *observer-stats*
  "When bound to an atom, each completed run bulk-reports its work deltas
  under the public counter names (:derived-grants for logical admissions,
  :advanced-datoms for physical commands, :queued-work for transitions, and
  :fetched-values for values consumed from adapters).
  The default nil costs nothing on the hot path — one check per run."
  nil)

(def ^:dynamic *aggregate-work-stats*
  "Request-owned cumulative work meter used by aggregate resource contracts.
  Unlike *observer-stats*, this state may be consulted by orchestration after
  a semantic quantum completes."
  nil)

(defn- report-run!
  [before final-state]
  (request-counters/add-commands!
   (- (:commands final-state) (:commands before 0)))
  (request-counters/add-fetched-values!
   (- (:fetched-values final-state) (:fetched-values before 0)))
  (doseq [stats (distinct (remove nil? [*observer-stats*
                                        *aggregate-work-stats*]))]
    (swap! stats
           (fn [counters]
             (-> (or counters {})
                 (update :derived-grants (fnil + 0)
                         (- (:admissions final-state)
                            (:admissions before 0)))
                 (update :advanced-datoms (fnil + 0)
                         (- (:commands final-state)
                            (:commands before 0)))
                 (update :queued-work (fnil + 0)
                         (- (:transitions final-state)
                            (:transitions before 0)))
                 (update :fetched-values (fnil + 0)
                         (- (:fetched-values final-state)
                            (:fetched-values before 0)))))))
  final-state)

(defn resume
  "Continues a history-free state to `target` absolute discovered results
  under fresh runtime options. Emissions from before the checkpoint are
  never re-delivered; :results holds only this run's suffix."
  [{:keys [adapter fetch-fn plan subject-type target cut-point!]
    :as options}
   checkpoint-state]
  {:pre [(or (some? adapter) (some? fetch-fn)) (some? plan)
         (keyword? subject-type) (pos? target)]}
  (let [context {:plan plan :root (:root plan) :subject-type subject-type}
        state (-> (initial-state options)
                  (merge (select-keys checkpoint-state semantic-state-keys))
                  (assoc :admitted (transient (:admitted checkpoint-state))
                         :base-discovered (:discovered checkpoint-state)))
        before (select-keys state
                            [:admissions :commands :transitions
                             :fetched-values])]
    (report-run! before (finish (run-loop context state target cut-point!)))))

(defn run-forward
  "Enumerates root resources the subject reaches, in stable first-discovery
  order, until `target` results or exhaustion. Returns the final state;
  :results is the canonical sequence of internal resource ids."
  [{:keys [adapter fetch-fn plan subject-type subject-eid target]
    :as options}]
  {:pre [(or (some? adapter) (some? fetch-fn)) (some? plan)
         (keyword? subject-type) (some? subject-eid) (pos? target)]}
  (let [context {:plan plan :root (:root plan)
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
    (report-run! nil (finish (run-loop context state target
                                       (:cut-point! options))))))

(defn run-reverse
  "Enumerates subjects of `subject-type` that reach the root permission on
  `resource-eid`, in stable first-discovery order, until `target` results
  or exhaustion. Returns the final state; :results is the canonical
  sequence of internal subject ids."
  [{:keys [adapter fetch-fn plan subject-type resource-eid target]
    :as options}]
  {:pre [(or (some? adapter) (some? fetch-fn)) (some? plan)
         (keyword? subject-type) (some? resource-eid) (pos? target)]}
  (let [context {:plan plan :root (:root plan)
                 :subject-type subject-type}
        goal {:kind :reverse-goal :rule {:node (:root plan)}
              :resource-eid resource-eid}
        state (schedule (initial-state options) nil [goal])]
    (report-run! nil (finish (run-loop context state target
                                       (:cut-point! options))))))

(ns eacl.engine.stable-route
  "Operation-appropriate routes on the stable-discovery engine
  (adopt-stable-discovery-enumeration, tasks 8.1-8.2; membership-probe
  point check, membership-probe-point-check).

  - Point checks are anchored to the known resource and answered by a
    membership-probe search over the sealed plan's reverse index: the
    subject itself is always looked up by one exact-bound probe, and each
    two-layer arrow arm is decided bidirectionally — the resource's via-set
    and the subject's holdings are consumed in alternating rounds, so the
    arm costs the SMALLER side, never the via fan-in
    (BidirectionalArrowIntersection.dfy; the resource-side-only probe paid
    the full fan-in — a denied check on a document shared with 10,000
    groups cost 37 ms — and the reverse-enumeration check before it was
    linear in the number of subjects holding the permission: a denied check
    on a resource with 5,000 owners cost 16 ms). Only arrows to recursive
    permissions still enumerate their intermediates and descend. The
    reverse-enumeration form is retained as `enumeration-check-eids`, the
    test oracle.
  - Exact count exhausts the history-free reducer; its scalar discovered
    count equals the denotation cardinality. Exhaustion is unbounded by
    construction (`exhaustion-target` is infinite): a run ends at an empty
    stack or a typed `:max-admissions`/`:max-values` failure, never at a
    silent cap. An order-insensitive specialization remains permitted only
    behind an independent denotation-equivalence proof (none exists yet)."
  (:require [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.backend.v8 :as backend]
            [eacl.engine.stable-reducer :as reducer]
            [eacl.relationships.edge :as edge]
            [eacl.request.counters :as request-counters]))

(def exhaustion-target
  "Alias of `eacl.engine.stable-reducer/exhaustion-target`: exhaustive routes
  run until the stack empties or a typed limit fails, never to a finite cap."
  reducer/exhaustion-target)

;; ---------------------------------------------------------------------------
;; Membership-probe point check
;; ---------------------------------------------------------------------------

(defn- limit-failure!
  [limit-key counters detail]
  (throw (ex-info "Point-check semantic limit exceeded."
                  (merge {:eacl/error :eacl.reducer/limit-exceeded
                          :limit limit-key
                          :admissions (:admissions counters)
                          :transitions (:transitions counters)
                          :commands (:commands counters)
                          :discovered 0}
                         detail))))

(defn- reverse-scan
  "Read-demand descriptor for one reverse scan, shaped exactly like the
  reducer's so the routed fetch-fn layers (classification, retry,
  telemetry) apply unchanged."
  [resource-type resource-eid relation-eid subject-type bound-eid limit]
  {:operation :resource->subjects
   :resource-type resource-type :resource-eid resource-eid
   :relation-eid relation-eid :subject-type subject-type
   :bound-eid bound-eid :limit limit})

(defn- forward-scan
  "Read-demand descriptor for one forward scan (the subject's holdings of
  one relation), shaped exactly like the reducer's so the routed fetch-fn
  layers (classification, retry, telemetry) apply unchanged."
  [subject-type subject-eid relation-eid resource-type bound-eid limit]
  {:operation :subject->resources
   :subject-type subject-type :subject-eid subject-eid
   :relation-eid relation-eid :resource-type resource-type
   :bound-eid bound-eid :limit limit})

(defn- probe-check-eids
  "Iterative depth-first membership search. Without qualification, returns true iff a derivation
  of the plan's root permission on `resource-eid` bottoms out in a tuple
  whose subject is `subject-eid`; equivalently, iff `subject-eid` belongs
  to the exhaustive reverse denotation `run-reverse` would emit.

  Reachability over the rule graph is decided by a visited set on
  [node eid]; a base tuple is decided by one exact-bound probe (the scan
  strictly after `subject-eid - 1`, limit one, equals `subject-eid` iff the
  tuple exists). Two-layer arrow arms — an arrow to a relation, or an arrow
  to a permission every one of whose derivations is a base relation — are
  decided BIDIRECTIONALLY: the resource's via-set and the subject's
  holdings are consumed in alternating rounds, each realized candidate is
  probed on the opposite index, and the arm resolves at the first positive
  probe or as soon as EITHER side exhausts, so its cost is bounded by the
  smaller side plus one chunk — never by the via fan-in alone
  (BidirectionalArrowIntersection.dfy: DecideEqualsArmAnswer,
  RoundsBoundedByShorterSide). Only arrows to recursive permissions still
  enumerate their intermediates and descend. Typed limits mirror the
  reducer's budgets: `:max-admissions` bounds distinct visited states,
  `:max-transitions` visits, `:max-commands` fetches, `:max-values` fetched
  values, `:max-stack` instantaneous stack depth.

  Qualification annotates the same physical paths with bounded residuals
  and temporal evidence. A visited state retains the union of its incoming
  path prefixes, and only a changed full value/certificate revisits it.
  Conditional matches continue the existing search; definite witnesses and
  demanded faults terminate it. Either exhausted side of a bidirectional
  arm covers every matching pair, including its qualified evidence."
  [{:keys [adapter fetch-fn plan subject-type subject-eid resource-eid
           cut-point! physical-chunk-size qualification
           max-admissions max-commands max-transitions max-values max-stack]
    :or {physical-chunk-size reducer/default-physical-chunk-size
         max-admissions reducer/default-max-admissions
         max-commands reducer/default-max-commands
         max-transitions reducer/default-max-transitions
         max-values reducer/default-max-values
         max-stack reducer/default-max-stack}
    :as options}]
  (let [fetch-fn (or fetch-fn (reducer/adapter-fetch-fn adapter))
        reverse-rules (get-in plan [:indexes :reverse-rules])
        qualify (if qualification
                  (fn [relation value] (qualification/qualify qualification relation value))
                  (fn [_ value] (some? value)))
        done? (fn [value] (or (evidence/has? value) (evidence/fault? value)))
        counters (volatile! {:admissions 0 :transitions 0 :commands 0
                             :fetched-values 0})
        fetch! (fn [descriptor]
                 ;; Deadline/cancellation enforcement per adapter command,
                 ;; matching the reducer's per-transition granularity: a
                 ;; single DFS pop may issue many fetches (chunk loops,
                 ;; per-candidate probes), so checking only per pop let up
                 ;; to max-commands reads run past an expired deadline.
                 (when cut-point! (cut-point! @counters))
                 (when (>= (:commands @counters) max-commands)
                   (limit-failure! :max-commands @counters
                                   {:max-commands max-commands}))
                 (let [values (reducer/bounded-vector
                               (fetch-fn (cond-> descriptor qualification
                                                 (assoc :include-qualifier? true)))
                               (:limit descriptor))]
                   (when (> (+ (:fetched-values @counters) (count values))
                            max-values)
                     (limit-failure! :max-values @counters
                                     {:max-values max-values
                                      :staged (count values)}))
                   (vswap! counters #(-> %
                                         (update :commands inc)
                                         (update :fetched-values
                                                 + (count values))))
                   values))
        matching-edge (fn [candidate values]
                        (let [value (first values)]
                          (when (= candidate (edge/endpoint value)) value)))
        probe? (fn [resource-type eid relation-eid]
                 (qualify relation-eid
                          (matching-edge subject-eid
                                         (fetch! (reverse-scan resource-type eid
                                                               relation-eid subject-type
                                                               (dec subject-eid) 1)))))
        intermediates (fn [resource-type eid via-relation-eid intermediate-type]
                        (loop [bound nil acc (transient [])]
                          (let [chunk (fetch! (reverse-scan resource-type eid
                                                            via-relation-eid
                                                            intermediate-type
                                                            bound
                                                            physical-chunk-size))
                                acc (reduce conj! acc chunk)]
                            (if (< (count chunk) physical-chunk-size)
                              (persistent! acc)
                              (recur (edge/endpoint (peek chunk)) acc)))))
        ;; One exact-bound probe per side of a two-layer arrow arm
        ;; (BidirectionalArrowIntersection.dfy): a via candidate is decided
        ;; on the subject's forward index, a holding candidate on the
        ;; resource's reverse index.
        holding-probe? (fn [target-relation-eid intermediate-type candidate]
                         (matching-edge candidate
                                        (fetch! (forward-scan
                                                 subject-type subject-eid
                                                 target-relation-eid
                                                 intermediate-type
                                                 (dec candidate) 1))))
        via-probe? (fn [resource-type eid via-relation-eid intermediate-type
                        candidate]
                     (matching-edge candidate
                                    (fetch! (reverse-scan
                                             resource-type eid
                                             via-relation-eid
                                             intermediate-type
                                             (dec candidate) 1))))
        ;; The interleaved bidirectional decision for one two-layer arm:
        ;; vias(resource) ∩ holdings(subject) ≠ ∅. Round order and both
        ;; exhaustion exits follow the verified model exactly
        ;; (BidirectionalArrowIntersection.dfy `Decide`); enumeration is
        ;; buffered in physical chunks, probing stays per candidate, so the
        ;; cost is bounded by the smaller side plus one chunk per side.
        intersect-arm?
        (fn [resource-type eid via-relation-eid intermediate-type
             target-relation-eid]
          (loop [vias [] via-index 0 via-bound nil vias-done? false
                 holdings [] holding-index 0 holding-bound nil
                 holdings-done? false answer false]
            (let [[vias via-index via-bound vias-done?]
                  (if (and (>= via-index (count vias)) (not vias-done?))
                    (let [chunk (fetch! (reverse-scan resource-type eid
                                                      via-relation-eid
                                                      intermediate-type
                                                      via-bound
                                                      physical-chunk-size))]
                      [chunk 0 (if (seq chunk) (edge/endpoint (peek chunk)) via-bound)
                       (< (count chunk) physical-chunk-size)])
                    [vias via-index via-bound vias-done?])]
              (if (>= via-index (count vias))
                ;; Either exhausted physical side proves that every possible
                ;; matching pair has contributed its evidence.
                answer
                (let [via-edge (nth vias via-index)
                      via (qualify via-relation-eid via-edge)
                      path (if (or (evidence/no? via) (evidence/fault? via))
                             via
                             (evidence/combine
                              :arrow via
                              (qualify target-relation-eid
                                       (holding-probe? target-relation-eid intermediate-type
                                                       (edge/endpoint via-edge)))))
                      answer (evidence/combine :union answer path)]
                  (cond
                    (done? answer) answer
                    :else
                    (let [[holdings holding-index holding-bound holdings-done?]
                          (if (and (>= holding-index (count holdings))
                                   (not holdings-done?))
                            (let [chunk (fetch! (forward-scan
                                                 subject-type subject-eid
                                                 target-relation-eid
                                                 intermediate-type
                                                 holding-bound
                                                 physical-chunk-size))]
                              [chunk 0
                               (if (seq chunk) (edge/endpoint (peek chunk)) holding-bound)
                               (< (count chunk) physical-chunk-size)])
                            [holdings holding-index holding-bound
                             holdings-done?])]
                      (if (>= holding-index (count holdings))
                        answer
                        (let [holding-edge (nth holdings holding-index)
                              via (qualify via-relation-eid
                                           (via-probe? resource-type eid via-relation-eid
                                                       intermediate-type (edge/endpoint holding-edge)))
                              path (if (or (evidence/no? via) (evidence/fault? via))
                                     via
                                     (evidence/combine :arrow via
                                                       (qualify target-relation-eid holding-edge)))
                              answer (evidence/combine :union answer path)]
                          (cond
                            (done? answer) answer
                            :else
                            (recur vias (inc via-index) via-bound vias-done?
                                   holdings (inc holding-index) holding-bound
                                   holdings-done? answer)))))))))))
        ;; A target permission every one of whose derivations is a base
        ;; relation reduces its arrow to a union of two-layer intersections;
        ;; any other shape keeps the enumerate-and-descend route.
        relation-only-rules
        (fn [target-node]
          (let [rules (get reverse-rules target-node)]
            (when (and (seq rules)
                       (every? #(= :relation (:rule %)) rules))
              rules)))
        report! (fn []
                  (request-counters/add-commands! (:commands @counters))
                  (request-counters/add-fetched-values!
                   (:fetched-values @counters))
                  (when (or reducer/*observer-stats*
                            reducer/*reducer-work-stats*)
                    (let [{:keys [admissions commands transitions
                                  fetched-values]} @counters]
                      (reducer/report-work-stats!
                       [reducer/*observer-stats*
                        reducer/*reducer-work-stats*]
                       {:derived-grants admissions
                        :advanced-datoms commands
                        :queued-work transitions
                        :fetched-values fetched-values}))))]
    (loop [stack [[(or (:start-node options) (:root plan)) resource-eid]]
           visited (transient (if qualification {} #{}))
           answer false]
      (if (zero? (count stack))
        (do (report!) answer)
        (let [[node eid incoming :as frame] (peek stack)
              state (if qualification [node eid] frame)
              admitted? (contains? visited state)
              prefix (if qualification
                       (evidence/combine :union (get visited state false)
                                         (if (= 3 (count frame)) incoming true))
                       true)
              unchanged? (if qualification
                           (and admitted? (= prefix (get visited state)))
                           admitted?)
              stack (pop stack)]
          (when (>= (:transitions @counters) max-transitions)
            (limit-failure! :max-transitions @counters
                            {:max-transitions max-transitions}))
          (vswap! counters update :transitions inc)
          (when cut-point! (cut-point! @counters))
          (if unchanged?
            (recur stack visited answer)
            (let [_ (when (and (not admitted?) (>= (:admissions @counters) max-admissions))
                      (limit-failure! :max-admissions @counters
                                      {:max-admissions max-admissions
                                       :staged 1}))
                  _ (when-not admitted? (vswap! counters update :admissions inc))
                  visited (if qualification (assoc! visited state prefix) (conj! visited state))
                  rules (get reverse-rules node)
                  successor (if qualification
                              (fn [target next-eid via] [target next-eid via])
                              (fn [target next-eid _] [target next-eid]))
                  join-path (fn [answer path]
                              (evidence/combine :union answer
                                                (evidence/combine :arrow prefix path)))
                  base-answer
                  (reduce (fn [answer rule]
                            (let [answer (if (and (= :relation (:rule rule))
                                                  (= subject-type (:subject-type rule)))
                                           (join-path answer
                                                      (probe? (:resource-type rule) eid
                                                              (:relation-eid rule)))
                                           answer)]
                              (if (done? answer) (reduced answer) answer)))
                          answer rules)]
              ;; Base tuples first: one exact-bound probe per direct rule.
              (if (done? base-answer)
                (do (report!) base-answer)
                ;; Then the arrows: enumerate intermediates, probe or descend.
                (let [answer (volatile! base-answer)
                      outcome
                      (reduce
                       (fn [successors rule]
                         (let [next-successors
                               (case (:rule rule)
                                 :self-permission
                                 (conj successors (successor (:target-node rule) eid prefix))

                                 :arrow-permission
                                 (if-let [target-rules
                                          (relation-only-rules (:target-node rule))]
                             ;; Every derivation of the target permission is
                             ;; a base relation: the arm is a union of
                             ;; two-layer intersections, each decided
                             ;; bidirectionally without materializing the
                             ;; via fan-in.
                                   (do
                                     (reduce (fn [_ target-rule]
                                               (when (= subject-type (:subject-type target-rule))
                                                 (vswap! answer join-path
                                                         (intersect-arm?
                                                          (:resource-type rule) eid
                                                          (:via-relation-eid rule)
                                                          (:intermediate-type rule)
                                                          (:relation-eid target-rule))))
                                               (when (done? @answer) (reduced nil)))
                                             nil target-rules)
                                     successors)
                                   (reduce (fn [successors compact-edge]
                                             (let [via (evidence/combine
                                                        :arrow prefix
                                                        (qualify (:via-relation-eid rule) compact-edge))]
                                               (if (or (evidence/no? via) (evidence/fault? via))
                                                 (do (vswap! answer #(evidence/combine :union % via))
                                                     (if (done? @answer) (reduced successors) successors))
                                                 (conj successors
                                                       (successor (:target-node rule)
                                                                  (edge/endpoint compact-edge) via)))))
                                           successors
                                           (intermediates (:resource-type rule) eid
                                                          (:via-relation-eid rule)
                                                          (:intermediate-type rule))))

                                 :arrow-relation
                                 (do
                                   (when (= subject-type (:target-subject-type rule))
                                     (vswap! answer join-path
                                             (intersect-arm?
                                              (:resource-type rule) eid
                                              (:via-relation-eid rule)
                                              (:intermediate-type rule)
                                              (:target-relation-eid rule))))
                                   successors)

                                 :relation
                                 successors

                           ;; Fail closed on an unrecognized rule kind, like
                           ;; the reducer's reverse-goal-work: silently
                           ;; skipping one would under-derive and answer
                           ;; false where enumeration paths error.
                                 (throw
                                  (ex-info
                                   "Point check met an unrecognized sealed rule kind."
                                   {:eacl/error :eacl.plan/unknown-rule-kind
                                    :rule-kind (:rule rule)
                                    :node node})))]
                           (if (done? @answer) (reduced next-successors) next-successors)))
                       []
                       rules)]
                  (if (done? @answer)
                    (do (report!) @answer)
                    (let [stack (into stack (rseq outcome))]
                      (when (> (count stack) max-stack)
                        (limit-failure! :max-stack @counters
                                        {:max-stack max-stack
                                         :staged (count outcome)}))
                      (recur stack visited @answer))))))))))))

(defn check-eids
  "Anchored point check over pre-resolved internal ids: does the subject
  hold the plan's root permission on the resource? Decided by the
  membership-probe search (`probe-check-eids`); nil ids never hold.
  With `:qualification`, returns conditional/temporal Evidence or a plain
  timeless Boolean; callers must project through evidence/has?."
  [{:keys [subject-eid resource-eid] :as options}]
  (if (or (nil? subject-eid) (nil? resource-eid))
    false
    (probe-check-eids options)))

(defn derives-from-node?
  "The membership-probe point check anchored at an arbitrary plan node:
  does the subject reach `:start-node`'s permission on the resource?
  Same machinery, budgets, and typed failures as `check-eids`; used by
  the least-path evaluator's witness clauses (acyclic-keyset-pagination),
  where a smaller-witness test asks derivability of one specific rule
  target rather than the sealed root. The evaluator supplies scanned,
  never nil, endpoint ids."
  [options]
  (probe-check-eids options))

(defn- found! []
  (throw (ex-info "found" {::found true})))

(defn enumeration-check-eids
  "The reverse-enumeration point check (the pre-probe route): a reverse
  traversal from the resource with early termination on the subject's first
  admission. Retained as the executable oracle for `check-eids`; its cost is
  linear in the number of subjects that hold the permission."
  [{:keys [subject-eid resource-eid] :as options}]
  (if (or (nil? subject-eid) (nil? resource-eid))
    false
    (let [seen (volatile! 0)
          caller-cut-point! (:cut-point! options)
          watch (fn [state]
                  (when caller-cut-point! (caller-cut-point! state))
                  (let [results (:results state)
                        n (count results)]
                    (when (> n @seen)
                      (vreset! seen n)
                      (when (= subject-eid (nth results (dec n)))
                        (found!)))))]
      (try
        (let [finished (reducer/run-reverse
                        (merge (select-keys options reducer/run-option-keys)
                               {:resource-eid resource-eid
                                :target exhaustion-target
                                :cut-point! watch}))]
          (boolean (some #{subject-eid} (:results finished))))
        (catch #?(:clj clojure.lang.ExceptionInfo
                  :cljs cljs.core/ExceptionInfo) error
          (if (::found (ex-data error))
            true
            (throw error)))))))

(defn check
  "Anchored point check over external ids; see check-eids."
  [{:keys [adapter subject-id resource-id] :as options}]
  (check-eids
   (assoc options
          :subject-eid (backend/invoke adapter :object-id->internal
                                       subject-id)
          :resource-eid (backend/invoke adapter :object-id->internal
                                        resource-id))))

(defn- exhaustive-count
  "Exact count by exhausting the reducer through `run` from `anchor-eid`;
  :count-limit truncates with an explicit marker exactly like the public
  contract."
  [run anchor-key anchor-eid {:keys [count-limit] :as options}]
  (let [target (if count-limit (inc count-limit) exhaustion-target)]
    (if (nil? anchor-eid)
      {:count 0 :limit (or count-limit -1) :truncated? false}
      (let [finished (run (merge (select-keys options reducer/run-option-keys)
                                 {anchor-key anchor-eid
                                  :result-sink :count
                                  :target target}))
            discovered (:discovered finished)
            truncated? (boolean (and count-limit
                                     (> discovered count-limit)))]
        {:count (if truncated? count-limit discovered)
         :limit (or count-limit -1)
         :truncated? truncated?}))))

(defn count-resources
  "Exact count by exhausting the reducer; :count-limit truncates with an
  explicit marker exactly like the current public contract."
  [{:keys [adapter subject-id] :as options}]
  (exhaustive-count reducer/run-forward :subject-eid
                    (backend/invoke adapter :object-id->internal subject-id)
                    options))

(defn count-subjects
  "Exact reverse count by exhaustion, mirroring count-resources."
  [{:keys [adapter resource-id] :as options}]
  (exhaustive-count reducer/run-reverse :resource-eid
                    (backend/invoke adapter :object-id->internal resource-id)
                    options))

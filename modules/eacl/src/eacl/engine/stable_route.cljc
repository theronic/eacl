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
            [eacl.authorization.point-reuse :as point-reuse]
            [eacl.authorization.qualification :as qualification]
            [eacl.execution :as execution]
            [eacl.backend.v8 :as backend]
            [eacl.engine.stable-reducer :as reducer]
            [eacl.relationships.edge :as edge]
            [eacl.request.counters :as request-counters]
            [eacl.subproblem-cache :as subproblem]))

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

(defn- validate-known-witness!
  [{:keys [plan qualification known-witness subject-type subject-eid resource-eid start-node]}]
  (when (some? known-witness)
    (execution/check! :stable-route/witness)
    (let [node (or start-node (:root plan))
          rule (:rule known-witness)
          arrow? (contains? #{:arrow-permission :arrow-relation} (:rule rule))
          required-keys (cond-> #{:point :rule :evidence :scope} arrow? (conj :intermediate))
          valid? (and qualification (map? known-witness)
                      (= required-keys (set (keys known-witness)))
                      (= [node subject-type subject-eid resource-eid] (:point known-witness))
                      (= (qualification/exact-reuse-identity qualification) (:scope known-witness))
                      (some #(= rule %) (get-in plan [:indexes :reverse-rules node]))
                      (or (not arrow?) (and (integer? (:intermediate known-witness))
                                            (edge/valid? (:intermediate known-witness))
                                            (pos? (:intermediate known-witness))))
                      (evidence/before? (:time qualification) (evidence/valid-until (:evidence known-witness))))]
      (when-not valid?
        (throw (ex-info "Known path evidence does not match the selected point."
                        {:type :eacl.route/invalid-witness :eacl/error :eacl.route/invalid-witness})))
      (evidence/encode (:evidence known-witness))
      known-witness)))

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
  (let [known-witness (validate-known-witness! options)
        start-node (or (:start-node options) (:root plan))
        known-rule? (fn [node eid rule]
                      (and known-witness (= start-node node) (= resource-eid eid)
                           (= (:rule known-witness) rule)))
        fetch-fn (or fetch-fn (reducer/adapter-fetch-fn adapter))
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
             target-relation-eid skip-intermediate]
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
                      via (if (= skip-intermediate (edge/endpoint via-edge))
                            false (qualify via-relation-eid via-edge))
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
                              via (if (= skip-intermediate (edge/endpoint holding-edge))
                                    false
                                    (qualify via-relation-eid
                                             (via-probe? resource-type eid via-relation-eid
                                                         intermediate-type (edge/endpoint holding-edge))))
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
    (loop [stack [[start-node resource-eid]]
           visited (transient (if qualification {} #{}))
           answer (if known-witness (:evidence known-witness) false)]
      (if (or (done? answer) (zero? (count stack)))
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
                                                  (= subject-type (:subject-type rule))
                                                  (not (known-rule? node eid rule)))
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
                                 (if (known-rule? node eid rule)
                                   successors
                                   (conj successors (successor (:target-node rule) eid prefix)))

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
                                                          (:relation-eid target-rule)
                                                          (when (known-rule? node eid rule)
                                                            (:intermediate known-witness)))))
                                               (when (done? @answer) (reduced nil)))
                                             nil target-rules)
                                     successors)
                                   (reduce (fn [successors compact-edge]
                                             (let [via (if (and (known-rule? node eid rule)
                                                                (= (:intermediate known-witness) (edge/endpoint compact-edge)))
                                                         false
                                                         (evidence/combine
                                                          :arrow prefix
                                                          (qualify (:via-relation-eid rule) compact-edge)))]
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
                                              (:target-relation-eid rule)
                                              (when (known-rule? node eid rule)
                                                (:intermediate known-witness)))))
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

(declare add-membership-stats!)

;; ---------------------------------------------------------------------------
;; Membership decisions in the client cache
;; ---------------------------------------------------------------------------

(def ^:private membership-point-version 1)

(defn- membership-key
  "The subproblem key of one subject's membership in the root of the plan
  (a sealed union plan or a guarded program) fingerprinted `fingerprint`, at
  one resource. `scope` is the request's certified qualification scope, or
  nil; a qualified value records the interval its evidence certifies."
  [fingerprint scope subject-type subject-eid resource-eid]
  (point-reuse/scoped-key
   [:membership-point membership-point-version fingerprint
    subject-type subject-eid resource-eid]
   scope))

(defn check-eids
  "Anchored point check over pre-resolved internal ids: does the subject
  hold the plan's root permission on the resource? Decided by the
  membership-probe search (`probe-check-eids`); nil ids never hold.
  With `:qualification`, returns conditional/temporal Evidence or a plain
  timeless Boolean; callers must project through evidence/has?. A scoped
  `:known-witness` contributes one rule or arrow binding; the same search
  completes all remaining alternatives without repeating that path.

  With a subproblem store bound, a decision this client cached for the same
  plan, subject and resource is reused while its certificate admits the
  request's time, and a computed decision is published for later requests."
  [{:keys [plan subject-type subject-eid resource-eid qualification] :as options}]
  (if (or (nil? subject-eid) (nil? resource-eid))
    false
    (let [key (when subproblem/*store*
                (membership-key (:fingerprint plan) (point-reuse/scope qualification)
                                subject-type subject-eid resource-eid))
          cached (if key (first (point-reuse/reuse! qualification [key] ::miss)) ::miss)]
      (if (not= ::miss cached)
        (do (add-membership-stats! {:reused 1})
            cached)
        (do (add-membership-stats! {:searched 1})
            (let [value (probe-check-eids options)]
              (when key (point-reuse/publish! qualification [[key value]]))
              value))))))

;; ---------------------------------------------------------------------------
;; Memoized membership of one subject
;; ---------------------------------------------------------------------------

(def ^:dynamic *membership-stats*
  "Optional observation-only atom for `check-eids` and `check-many-eids`:
  resources decided, resources searched, resources reused from the client
  cache, exact point-check fallbacks, extra evidence levels, and
  subject-holding scans."
  nil)

(defn- add-membership-stats! [deltas]
  (when-let [stats *membership-stats*]
    (swap! stats #(merge-with + % deltas)))
  nil)

(def holdings-limit
  "The largest per-relation holding set `check-many-eids` retains for one
  subject. A relation the subject holds more often is decided per candidate
  by the exact-bound probe instead."
  256)

(defn membership-context
  "Request-scoped state for `check-many-eids`. Its entries describe one
  selected immutable basis and one qualification scope, so a caller creates
  one per request and never shares it with another request."
  []
  (volatile! {}))

(defn- holding-key
  "The subject-anchored relation slice a base rule consults, or nil when the
  rule can never match a subject of `subject-type`."
  [subject-type rule]
  (case (:rule rule)
    :relation
    (when (= subject-type (:subject-type rule))
      [(:relation-eid rule) (:resource-type rule)])

    :arrow-relation
    (when (= subject-type (:target-subject-type rule))
      [(:target-relation-eid rule) (:intermediate-type rule)])

    (:self-permission :arrow-permission :oracle :arrow-oracle) nil

    (throw
     (ex-info "Membership check met an unrecognized sealed rule kind."
              {:eacl/error :eacl.plan/unknown-rule-kind
               :rule-kind (:rule rule)
               :node (:node rule)}))))

(defn ^:no-doc possible-nodes
  "Plan nodes the subject can hold on some entity at this basis: the least
  fixed point over the rule graph seeded by the relation slices in which the
  subject holds at least one tuple. A node outside it is false for every
  entity, so the search never enters it."
  [reverse-rules subject-type holdings]
  (loop [possible #{}]
    (let [grown
          (reduce-kv
           (fn [result node rules]
             (if (or (contains? result node)
                     (some (fn [rule]
                             (case (:rule rule)
                               (:self-permission :arrow-permission)
                               (contains? result (:target-node rule))

                               ;; The oracle's permission is decided
                               ;; elsewhere; assume the subject may hold it.
                               (:oracle :arrow-oracle) true

                               (when-let [key (holding-key subject-type rule)]
                                 (:any? (get holdings key)))))
                           rules))
               (conj result node)
               result))
           possible
           reverse-rules)]
      (if (= (count grown) (count possible))
        possible
        (recur grown)))))

(defn ^:no-doc push-successors
  "Pushes a state's non-base successors so the frame of its first rule in
  sealed order is on top, skipping rules that cannot reach the subject. A
  state frame is [node eid]; an arrow frame is [rule eid], expanded when it
  reaches the top of the stack."
  [stack rules eid subject-type holdings possible]
  (loop [index (dec (count rules))
         stack stack]
    (if (neg? index)
      stack
      (let [rule (nth rules index)]
        (recur
         (dec index)
         (case (:rule rule)
           (:relation :oracle) stack

           :self-permission
           (if (contains? possible (:target-node rule))
             ;; A guarded reference is a rule frame: its guards are decided
             ;; when the frame is expanded.
             (conj stack (if (:guards rule) [rule eid] [(:target-node rule) eid]))
             stack)

           :arrow-permission
           (if (contains? possible (:target-node rule))
             (conj stack [rule eid])
             stack)

           :arrow-relation
           (if (:any? (get holdings (holding-key subject-type rule)))
             (conj stack [rule eid])
             stack)

           :arrow-oracle
           (conj stack [rule eid])))))))

(def ^:private plain-level
  "The first evidence level: plain evidence only, which never expires. Every
  later level is a deadline (see `decide-leveled`)."
  ::plain)

(defn- lasts?
  "True when evidence ending at `deadline` (nil when plain) is kept at
  `level`: the graph at a deadline keeps everything lasting at least that
  long."
  [deadline level]
  (or (nil? deadline)
      (and (not= plain-level level) (<= level deadline))))

(defn- later
  "The later of two skipped deadlines, either possibly nil."
  [a b]
  (cond (nil? a) b (nil? b) a :else (max a b)))

(defn- evidence-class
  "One qualified value as the leveled search sees it: `[:decisive deadline]`
  for plain or time-limited `true` (deadline nil when plain), `:absent`,
  `:conditional` (a caveat residual or incomplete evidence), or `:fault`."
  [value]
  (cond
    (true? value) [:decisive nil]
    (or (false? value) (nil? value)) :absent
    (evidence/fault? value) :fault
    (not (evidence/complete? value)) :conditional
    (evidence/has? value) [:decisive (evidence/valid-until value)]
    (evidence/no? value) :absent
    :else :conditional))

(defn- joined-class
  "An arrow's via edge (neither absent nor a fault) and its target tuple
  together: decisive until the earlier deadline only when both are
  decisive."
  [via held]
  (cond
    (= :absent held) :absent
    (= :fault held) :fault
    (or (= :conditional via) (= :conditional held)) :conditional
    :else [:decisive (let [a (second via) b (second held)]
                       (cond (nil? a) b (nil? b) a :else (min a b)))]))

(defn- note!
  "One class's contribution at `level`: ::kept, ::absent, or ::fault. A
  decisive value that ends before `level` notes its deadline as skipped;
  conditional evidence is noted and otherwise treated as absent."
  [{:keys [skipped conditional?]} level class]
  (if (keyword? class)
    (case class
      :absent ::absent
      :fault ::fault
      :conditional (do (vreset! conditional? true) ::absent))
    (if (lasts? (second class) level)
      ::kept
      (do (vswap! skipped later (second class)) ::absent))))

(defn- membership-entry
  "The subject's holdings and possible nodes for one sealed plan, read once
  per request: one bounded forward scan per relation slice the plan's base
  rules name for this subject type. The entry also retains, for the
  request, each resource's decided answer and one memo per evidence level;
  `:memo` is the plain level's."
  [context fetch! plan subject-type subject-eid]
  (let [key [:subject (:fingerprint plan) subject-type subject-eid]]
    (or (get @context key)
        (let [reverse-rules (get-in plan [:indexes :reverse-rules])
              slices (->> (vals reverse-rules)
                          (mapcat identity)
                          (mapcat (fn [rule]
                                    (cons rule (mapcat :alternatives (:guards rule)))))
                          (keep #(holding-key subject-type %))
                          distinct
                          sort)
              holdings
              (into {}
                    (map (fn [[relation-eid resource-type :as slice]]
                           (let [edges (fetch! (forward-scan subject-type subject-eid
                                                             relation-eid resource-type
                                                             nil holdings-limit))]
                             [slice {:any? (boolean (seq edges))
                                     :complete? (< (count edges) holdings-limit)
                                     ;; Scans are strictly ordered by endpoint,
                                     ;; so a truncated scan still decides every
                                     ;; endpoint up to its last.
                                     :bound (some-> (peek edges) edge/endpoint)
                                     :size (count edges)
                                     :edges (into {} (map (juxt edge/endpoint identity))
                                                  edges)}])))
                    slices)
              plain-memo (volatile! {})
              entry {:reverse-rules reverse-rules
                     :holdings holdings
                     :possible (possible-nodes reverse-rules subject-type holdings)
                     :memo plain-memo
                     :memos (volatile! {plain-level plain-memo})
                     :skips (volatile! {})
                     :answers (volatile! {})
                     :guard-classes (volatile! {})
                     :extended (volatile! {})}]
          (add-membership-stats! {:holding-scans (count slices)})
          (vswap! context assoc key entry)
          entry))))

(def ^:private first-extension-probes
  "Probes past a truncated holdings scan before the scan is extended."
  16)

(defn ^:no-doc held-edge
  "The subject's stored edge on `eid` in one relation slice, or nil, from
  its retained holdings. A truncated scan decides every endpoint up to its
  last, since scans are strictly ordered. Past that, `probe!` asks for one
  tuple; once a slice has needed `first-extension-probes` such probes, the
  scan continues from its last endpoint with twice as many edges, and the
  threshold doubles, so reading holdings never costs much more than the
  probes it replaces."
  [{:keys [complete? edges bound size]} extended fetch! scan-from slice eid probe!]
  (if (or complete? (<= eid bound))
    (get edges eid)
    (let [state (or (get @extended slice)
                    {:edges edges :bound bound :size size :complete? false
                     :probes 0 :threshold first-extension-probes})]
      (if (or (:complete? state) (<= eid (:bound state)))
        (get (:edges state) eid)
        (let [probes (inc (:probes state))]
          (if (< probes (:threshold state))
            (do (vswap! extended assoc slice (assoc state :probes probes))
                (probe!))
            (let [limit (* 2 (:size state))
                  chunk (fetch! (scan-from (:bound state) limit))
                  state {:edges (into (:edges state)
                                      (map (juxt edge/endpoint identity)) chunk)
                         :bound (if (seq chunk) (edge/endpoint (peek chunk)) (:bound state))
                         :size limit
                         :complete? (< (count chunk) limit)
                         :probes 0
                         :threshold (* 2 (:threshold state))}]
              (vswap! extended assoc slice state)
              (if (or (:complete? state) (<= eid (:bound state)))
                (get (:edges state) eid)
                (probe!)))))))))

(defn- alternative-classes
  "The evidence classes one rule without successors has at `eid`: one per
  witness it can have there. A relation rule reads the subject's grant; an
  oracle rule asks the oracle for its permission; an arrow rule joins each
  intermediate's via edge with its target's value."
  [{:keys [probe intermediates qualify oracle subject-type]} rule eid]
  (case (:rule rule)
    :relation
    (if (= subject-type (:subject-type rule))
      [(evidence-class (qualify (:relation-eid rule)
                                (probe (:relation-eid rule) (:resource-type rule) eid)))]
      [])

    :oracle
    [(evidence-class (oracle (:target-node rule) eid))]

    (:arrow-relation :arrow-oracle)
    (into []
          (keep
           (fn [compact-edge]
             (let [via (evidence-class (qualify (:via-relation-eid rule) compact-edge))
                   endpoint (edge/endpoint compact-edge)]
               (cond
                 (= :absent via) nil
                 (= :fault via) :fault
                 (= :arrow-oracle (:rule rule))
                 (joined-class via (evidence-class (oracle (:target-node rule) endpoint)))
                 (= subject-type (:target-subject-type rule))
                 (joined-class via (evidence-class
                                    (qualify (:target-relation-eid rule)
                                             (probe (:target-relation-eid rule)
                                                    (:intermediate-type rule) endpoint))))
                 :else nil))))
          (intermediates (:resource-type rule) eid (:via-relation-eid rule)
                         (:intermediate-type rule)))))

(defn- guard-classes
  "The evidence classes of every alternative of `guard` at `eid`, computed
  once per request: they do not depend on the level."
  [{:keys [guard-classes] :as search} guard eid]
  (let [key [(:key guard) eid]]
    (or (get @guard-classes key)
        (let [classes (into [] (mapcat #(alternative-classes search % eid))
                            (:alternatives guard))]
          (vswap! guard-classes assoc key classes)
          classes))))

(defn ^:no-doc guards-outcome
  "Whether a rule's guards hold at `eid` at `level`: ::kept when every guard
  holds, ::absent when one does not hold at this level, or ::fault.

  A guard holds when one of its alternatives lasts at `level`; the others
  are noted as any skipped evidence is. A subtracted guard holds only when
  it is plainly absent. A plainly present one closes the rule at every
  level. Any other value is ::fault: access could appear when it expires,
  so the resource is decided exactly instead."
  [search notes level guards eid]
  (loop [index 0]
    (if (= index (count guards))
      ::kept
      (let [guard (nth guards index)
            classes (guard-classes search guard eid)
            outcome
            (if (= :negative (:sign guard))
              (cond
                (some #(= :fault %) classes) ::fault
                (some #(and (vector? %) (nil? (second %))) classes) ::absent
                (some #(not= :absent %) classes) ::fault
                :else ::kept)
              (loop [i 0]
                (if (= i (count classes))
                  ::absent
                  (let [outcome (note! notes level (nth classes i))]
                    (if (= ::absent outcome) (recur (inc i)) outcome)))))]
        (if (= ::kept outcome) (recur (inc index)) outcome)))))

(defn- expand-arrow
  "Expands a rule frame onto `stack` at `level` once its guards hold there.
  A guarded reference contributes its target state. For an arrow, each
  intermediate whose via edge lasts at `level` contributes its target state
  (arrow to a permission), or its target's decision (arrow to a relation or
  to an oracle's permission). Returns the new stack, ::found, or ::fault."
  [{:keys [probe intermediates qualify oracle] :as search} notes level stack rule eid]
  (let [guarded (if-let [guards (:guards rule)]
                  (guards-outcome search notes level guards eid)
                  ::kept)]
    (cond
      (= ::fault guarded) ::fault
      (= ::absent guarded) stack
      (= :self-permission (:rule rule)) (conj stack [(:target-node rule) eid])
      :else
      (let [edges (intermediates (:resource-type rule) eid (:via-relation-eid rule)
                                 (:intermediate-type rule))
            kind (:rule rule)]
        (loop [index (dec (count edges))
               stack stack]
          (if (neg? index)
            stack
            (let [compact-edge (nth edges index)
                  via (evidence-class (qualify (:via-relation-eid rule) compact-edge))]
              (cond
                (= :absent via) (recur (dec index) stack)
                (= :fault via) ::fault

                (= :arrow-permission kind)
                (let [outcome (note! notes level via)]
                  (cond
                    (= ::kept outcome)
                    (recur (dec index)
                           (conj stack [(:target-node rule) (edge/endpoint compact-edge)]))
                    (= ::fault outcome) ::fault
                    :else (recur (dec index) stack)))

                :else
                (let [endpoint (edge/endpoint compact-edge)
                      held (evidence-class
                            (if (= :arrow-oracle kind)
                              (oracle (:target-node rule) endpoint)
                              (qualify (:target-relation-eid rule)
                                       (probe (:target-relation-eid rule)
                                              (:intermediate-type rule) endpoint))))
                      outcome (note! notes level (joined-class via held))]
                  (cond
                    (= ::kept outcome) ::found
                    (= ::fault outcome) ::fault
                    :else (recur (dec index) stack)))))))))))

(defn- base-outcome
  "Decides a state's rules without successors at `level`: relation rules
  from the subject's holdings and oracle rules from the oracle, each once
  its guards hold. ::found when one lasts there, ::fault, or nil."
  [{:keys [probe qualify subject-type oracle] :as search} notes level rules eid]
  (loop [index 0]
    (when (< index (count rules))
      (let [rule (nth rules index)
            kind (:rule rule)]
        (if (or (and (= :relation kind) (= subject-type (:subject-type rule)))
                (= :oracle kind))
          (let [guarded (if-let [guards (:guards rule)]
                          (guards-outcome search notes level guards eid)
                          ::kept)]
            (cond
              (= ::fault guarded) ::fault
              (= ::absent guarded) (recur (inc index))
              :else
              (let [outcome (note! notes level
                                   (evidence-class
                                    (if (= :oracle kind)
                                      (oracle (:target-node rule) eid)
                                      (qualify (:relation-eid rule)
                                               (probe (:relation-eid rule)
                                                      (:resource-type rule) eid)))))]
                (cond
                  (= ::kept outcome) ::found
                  (= ::fault outcome) ::fault
                  :else (recur (inc index))))))
          (recur (inc index)))))))

(defn ^:no-doc search-level
  "One depth-first search from `root-state` in the graph of evidence that
  lasts at `level`, reusing and extending that level's memo. Returns
  ::found, ::fault, or, when exhausted, `{:skipped deadline :conditional?
  flag}`: the latest deadline and whether any conditional evidence the
  search skipped.

  Within one level the graph is plain, so the memo is sound as in
  MemoizedMembership.dfy: a search that exhausts visited every state
  reachable from each state it admitted, so each is negative at this level;
  a search that finds a witness proves its root. A retained negative keeps
  its closure's skipped deadline and conditional flag, and a later search
  that reuses it inherits them."
  [{:keys [subject-type step! admit!] :as search}
   {:keys [reverse-rules holdings possible memos skips]} level root-state]
  (let [memo (or (get @memos level)
                 (let [memo (volatile! {})] (vswap! memos assoc level memo) memo))
        notes {:skipped (volatile! nil) :conditional? (volatile! false)}]
    (loop [stack [root-state]
           visited (transient #{})]
      (if (zero? (count stack))
        (let [visited (persistent! visited)
              skipped @(:skipped notes)
              conditional? @(:conditional? notes)]
          (vswap! memo #(reduce (fn [m state] (assoc m state false)) % visited))
          (when (or skipped conditional?)
            (vswap! skips update level
                    #(reduce (fn [m state] (assoc m state [skipped conditional?]))
                             (or % {}) visited)))
          {:skipped skipped :conditional? conditional?})
        (let [frame (peek stack)
              stack (pop stack)
              _ (step! (count stack))
              head (nth frame 0)]
          (if (map? head)
            (let [expanded (expand-arrow search notes level stack head (nth frame 1))]
              ;; A vector is the grown stack; `case` would hash it to
              ;; dispatch, so the sentinels are tested explicitly.
              (cond
                (not (keyword? expanded)) (recur expanded visited)
                (= ::found expanded) (do (vswap! memo assoc root-state true) ::found)
                :else ::fault))
            (let [known (get @memo frame)]
              (cond
                (true? known) (do (vswap! memo assoc root-state true) ::found)

                (false? known)
                (do (when-let [[skipped conditional?] (get-in @skips [level frame])]
                      (vswap! (:skipped notes) later skipped)
                      (when conditional? (vreset! (:conditional? notes) true)))
                    (recur stack visited))

                (or (contains? visited frame)
                    (not (contains? possible head)))
                (recur stack visited)

                :else
                (let [eid (nth frame 1)
                      rules (get reverse-rules head)
                      _ (admit!)
                      outcome (base-outcome search notes level rules eid)]
                  (cond
                    (= ::found outcome) (do (vswap! memo assoc root-state true) ::found)
                    (= ::fault outcome) ::fault
                    :else (recur (push-successors stack rules eid subject-type
                                                  holdings possible)
                                 (conj! visited frame))))))))))))

(defn ^:no-doc decide-leveled
  "Decides one resource level by level. The first search keeps only plain
  evidence; each later one keeps the evidence lasting until the latest
  deadline the previous search skipped. Found at the plain level: true.
  Found at deadline `v`: evidence true until `v`. No witness path's
  bottleneck lies strictly between `v` and the previous level, because its
  first skipped edge would have raised the next level. So `v` is the latest
  first-expiry over witness paths, the exact end of the grant. Exhausted with
  nothing skipped: false, which stays false because relationships only
  expire. ::qualified when a fault is met, or when only conditional evidence
  remains; the exact point check then decides the resource."
  [search entry resource-eid]
  (let [root-state [(:root search) resource-eid]]
    (loop [level plain-level]
      (let [outcome (search-level search entry level root-state)]
        (cond
          (= ::found outcome)
          (if (= plain-level level)
            true
            (evidence/with-certificate true level true))

          (= ::fault outcome) ::qualified

          (:skipped outcome)
          (do (add-membership-stats! {:levels 1})
              (recur (:skipped outcome)))

          (:conditional? outcome) ::qualified
          :else false)))))

(defn ^:no-doc subject-holdings
  "One subject's grants of one relation slice, read by one forward scan of at
  most `holdings-limit` edges and retained in the request's `context`:
  `{:complete? flag :edges {endpoint compact-edge}}`. The edges are the
  stored compact edges a direct probe of each resource returns. When the
  subject holds the slice `holdings-limit` times or more, `:complete?` is
  false and a caller probes instead."
  [{:keys [fetch-fn adapter context qualification cut-point!]}
   subject-type subject-eid relation-eid resource-type]
  (let [key [:subject-holdings subject-type subject-eid relation-eid resource-type]]
    (or (get @context key)
        (let [fetch-fn (or fetch-fn (reducer/adapter-fetch-fn adapter))
              _ (when cut-point! (cut-point! nil))
              edges (reducer/bounded-vector
                     (fetch-fn (cond-> (forward-scan subject-type subject-eid relation-eid
                                                     resource-type nil holdings-limit)
                                 qualification (assoc :include-qualifier? true)))
                     holdings-limit)
              holdings {:complete? (< (count edges) holdings-limit)
                        :edges (into {} (map (juxt edge/endpoint identity)) edges)}]
          (request-counters/add-commands! 1)
          (request-counters/add-fetched-values! (count edges))
          (vswap! context assoc key holdings)
          holdings))))

(defn check-many-eids
  "Decides one subject's membership in the plan's root permission for many
  resources: one value per resource, with the permissionship `check-eids`
  returns for that point.

  - A decisive answer is plain true, or true until the latest first-expiry
    over its witness paths (`decide-leveled`). That certificate is sound and
    may end later than the point check's, which is the first witness it
    happened to find.
  - Plain false stays false, because relationships only expire.
  - A resource whose decision meets a fault, or rests only on conditional
    evidence, gets `check-eids`' own value.

  The request-scoped `:context` (from `membership-context`) holds what later
  calls reuse over the same basis:

  - the subject's holdings of each relation slice the plan names, one bounded
    forward scan per slice;
  - the plan nodes those holdings can reach at all;
  - each arrow's intermediates;
  - one memo per evidence level;
  - completed answers.

  A base rule is decided from the retained holdings instead of an adapter
  probe, a node the subject cannot hold is never entered, and an ancestor
  shared by many resources is decided once per level. Typed limits apply per
  call, with the point check's meanings.

  `plan` may also be a guarded program (`operator-plan/guarded-delegation`):
  rules whose guards must hold at the state's entity, and `:oracle` and
  `:arrow-oracle` rules that ask `oracle`, `(fn [permission eid] value)`, for
  a permission decided elsewhere. Such a program has no point check, so it
  supplies `fallback`, `(fn [resource-eid] value)`, the exact value of a
  resource the search defers."
  [{:keys [fetch-fn adapter plan subject-type subject-eid resource-eids
           context cut-point! physical-chunk-size qualification oracle fallback
           max-admissions max-commands max-transitions max-values max-stack]
    :or {physical-chunk-size reducer/default-physical-chunk-size
         max-admissions reducer/default-max-admissions
         max-commands reducer/default-max-commands
         max-transitions reducer/default-max-transitions
         max-values reducer/default-max-values
         max-stack reducer/default-max-stack}
    :as options}]
  (if (nil? subject-eid)
    (vec (repeat (count resource-eids) false))
    (let [fetch-fn (or fetch-fn (reducer/adapter-fetch-fn adapter))
          context (or context (membership-context))
          ;; admissions, transitions, commands, fetched values. The zeros are
          ;; explicit: ClojureScript fills an unsized long-array with nil.
          counts (long-array 4 0)
          counters (fn []
                     {:admissions (aget counts 0) :transitions (aget counts 1)
                      :commands (aget counts 2) :fetched-values (aget counts 3)})
          fetch! (fn [descriptor]
                   (when cut-point! (cut-point! nil))
                   (when (>= (aget counts 2) max-commands)
                     (limit-failure! :max-commands (counters)
                                     {:max-commands max-commands}))
                   (let [values (reducer/bounded-vector
                                 (fetch-fn (cond-> descriptor qualification
                                                   (assoc :include-qualifier? true)))
                                 (:limit descriptor))]
                     (when (> (+ (aget counts 3) (count values)) max-values)
                       (limit-failure! :max-values (counters)
                                       {:max-values max-values
                                        :staged (count values)}))
                     (aset counts 2 (inc (aget counts 2)))
                     (aset counts 3 (+ (aget counts 3) (count values)))
                     values))
          entry (membership-entry context fetch! plan subject-type subject-eid)
          holdings (:holdings entry)
          answers (:answers entry)
          root (:root plan)
          search
          {:root root
           :subject-type subject-type
           :oracle oracle
           :guard-classes (:guard-classes entry)
           :probe
           (fn [relation-eid resource-type eid]
             (let [slice [relation-eid resource-type]
                   held (get holdings slice)]
               (cond
                 (not (:any? held)) nil
                 ;; Almost every probe is decided by the retained scan.
                 (or (:complete? held) (<= eid (:bound held))) (get (:edges held) eid)
                 :else
                 (held-edge
                  held (:extended entry) fetch!
                  (fn [bound limit]
                    (forward-scan subject-type subject-eid relation-eid resource-type
                                  bound limit))
                  slice eid
                  (fn []
                    (let [value (first (fetch! (reverse-scan resource-type eid
                                                             relation-eid subject-type
                                                             (dec subject-eid) 1)))]
                      (when (= subject-eid (some-> value edge/endpoint))
                        value)))))))
           :intermediates
           (fn [resource-type eid via-relation-eid intermediate-type]
             (let [key [:intermediates resource-type eid via-relation-eid
                        intermediate-type]]
               (or (get @context key)
                   (let [values
                         (loop [bound nil acc (transient [])]
                           (let [chunk (fetch! (reverse-scan resource-type eid
                                                             via-relation-eid
                                                             intermediate-type
                                                             bound
                                                             physical-chunk-size))
                                 acc (reduce conj! acc chunk)]
                             (if (< (count chunk) physical-chunk-size)
                               (persistent! acc)
                               (recur (edge/endpoint (peek chunk)) acc))))]
                     (vswap! context assoc key values)
                     values))))
           :qualify (if qualification
                      (fn [relation value]
                        (qualification/qualify qualification relation value))
                      (fn [_ value] (some? value)))
           :step! (fn [depth]
                    (when (>= (aget counts 1) max-transitions)
                      (limit-failure! :max-transitions (counters)
                                      {:max-transitions max-transitions}))
                    (when (> depth max-stack)
                      (limit-failure! :max-stack (counters)
                                      {:max-stack max-stack
                                       :staged depth}))
                    (aset counts 1 (inc (aget counts 1)))
                    (when cut-point! (cut-point! nil)))
           :admit! (fn []
                     (when (>= (aget counts 0) max-admissions)
                       (limit-failure! :max-admissions (counters)
                                       {:max-admissions max-admissions
                                        :staged 1}))
                     (aset counts 0 (inc (aget counts 0))))}
          searched (volatile! 0)
          reused (volatile! 0)
          computed (volatile! #{})
          cache? (some? subproblem/*store*)
          key-of (when cache?
                   (let [fingerprint (:fingerprint plan)
                         scope (point-reuse/scope qualification)]
                     #(membership-key fingerprint scope subject-type subject-eid %)))
          ;; One client-cache lookup for the distinct resources this request
          ;; has not answered yet.
          unknown (when cache?
                    (into [] (comp (remove nil?) (remove #(contains? @answers %)) (distinct))
                          resource-eids))
          stored (if (seq unknown)
                   (zipmap unknown (point-reuse/reuse! qualification (mapv key-of unknown) ::miss))
                   {})
          decided
          (mapv (fn [resource-eid]
                  (if (nil? resource-eid)
                    false
                    (let [known (get @answers resource-eid ::unknown)]
                      (if (not= ::unknown known)
                        known
                        (let [cached (get stored resource-eid ::miss)]
                          (if (not= ::miss cached)
                            (do (vswap! reused inc)
                                (vswap! answers assoc resource-eid cached)
                                cached)
                            (let [value (decide-leveled search entry resource-eid)]
                              (vswap! searched inc)
                              (vswap! computed conj resource-eid)
                              (when-not (= ::qualified value)
                                (vswap! answers assoc resource-eid value))
                              value)))))))
                resource-eids)
          fallbacks (count (filter #{::qualified} decided))]
      (request-counters/add-commands! (aget counts 2))
      (request-counters/add-fetched-values! (aget counts 3))
      (when (or reducer/*observer-stats* reducer/*reducer-work-stats*)
        (reducer/report-work-stats!
         [reducer/*observer-stats* reducer/*reducer-work-stats*]
         {:derived-grants (aget counts 0)
          :advanced-datoms (aget counts 2)
          :queued-work (aget counts 1)
          :fetched-values (aget counts 3)}))
      (add-membership-stats! {:decided (count resource-eids)
                              :searched (+ @searched fallbacks)
                              :reused @reused
                              :fallbacks fallbacks})
      (let [values
            (if (zero? fallbacks)
              decided
              (mapv (fn [resource-eid value]
                      (if (= ::qualified value)
                        (if fallback
                          (fallback resource-eid)
                          (probe-check-eids (-> options
                                                (dissoc :resource-eids :context)
                                                (assoc :fetch-fn fetch-fn
                                                       :resource-eid resource-eid))))
                        value))
                    resource-eids decided))]
        ;; Every decision is complete once the call has succeeded: publish
        ;; each computed one, including exact fallback values, once.
        (when (and cache? (seq @computed))
          (point-reuse/publish!
           qualification
           (vals (reduce (fn [entries [resource-eid value]]
                           (if (and (contains? @computed resource-eid)
                                    (not (contains? entries resource-eid)))
                             (assoc entries resource-eid [(key-of resource-eid) value])
                             entries))
                         {}
                         (map vector resource-eids values)))))
        values))))

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

(defn discovery-options
  "Completes conditional first-discovery candidates with the existing point
   evaluator. Direct tuple witnesses skip only their exact rule; arbitrary
   path prefixes never claim a whole child permission."
  [options direction]
  (if-let [request (:qualification options)]
    (assoc options :candidate-evidence-fn
           (fn [eid item]
             (let [point (assoc options (if (= :reverse direction) :subject-eid :resource-eid) eid)
                   known (:direct-evidence item)
                   root (get-in options [:plan :root])
                   point (cond-> point
                           (and known (= root (get-in known [:rule :node]))
                                (= (:resource-eid point) (:resource-eid known)))
                           (assoc :known-witness {:point [root (:subject-type point)
                                                          (:subject-eid point) (:resource-eid point)]
                                                  :rule (:rule known) :evidence (:evidence known)
                                                  :scope (qualification/exact-reuse-identity request)}))]
               (check-eids point))))
    options))

(defn- exhaustive-count
  "Exact count by exhausting the reducer through `run` from `anchor-eid`;
  :count-limit truncates with an explicit marker exactly like the public
  contract."
  [run anchor-key anchor-eid {:keys [count-limit] :as options}]
  (let [target (if count-limit (inc count-limit) exhaustion-target)
        options (discovery-options (assoc options anchor-key anchor-eid)
                                   (if (= :subject-eid anchor-key) :forward :reverse))]
    (if (nil? anchor-eid)
      (cond-> {:count 0 :limit (or count-limit -1) :truncated? false}
        (:qualification options) (assoc :definite-count 0 :conditional-count 0))
      (let [finished (run (merge (select-keys options reducer/run-option-keys)
                                 {anchor-key anchor-eid
                                  :result-sink :count
                                  :target target}))
            discovered (:discovered finished)
            truncated? (boolean (and count-limit
                                     (> discovered count-limit)))]
        (cond-> {:count (if truncated? count-limit discovered)
                 :limit (or count-limit -1)
                 :truncated? truncated?}
          (:qualification options)
          (assoc :definite-count (- (:definite-count finished)
                                    (if (and truncated? (not (:last-conditional? finished))) 1 0))
                 :conditional-count (- (:conditional-count finished)
                                       (if (and truncated? (:last-conditional? finished)) 1 0))))))))

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

(ns eacl.engine.least-path
  "Least-derivation-path enumeration for acyclic sealed plans
  (acyclic-keyset-pagination; LeastPathOrder.dfy, LeastPathEnumeration.dfy,
  LeastPathResume.dfy).

  A derivation is the per-scan coordinate sequence the nested ordered DFS
  binds: sealed rule ordinals interleaved with ascending scan eids, each
  step's arity fixed by the rule kind. Every derivable entity is emitted
  exactly once, at its lexicographically least derivation path; the
  emission decision is a pure predicate of (plan, snapshot, coordinates)
  — decided by bounded smaller-witness probes, never by traversal
  history — so cursors are the boundary's coordinates and resume is a
  per-level seek with no server-side state and no replay
  (spec: acyclic-keyset-pagination).

  Forward (lookup-resources) enumerates root entities for one subject;
  intermediates behind arrow-to-permission arms are produced by a child
  enumerator in ITS least-path order (recursively filtered, so each
  intermediate arrives once, at its least sub-path). Reverse
  (lookup-subjects) enumerates subjects for one root entity; there every
  intermediate level is a direct index scan, so all witness clauses are
  eid-bounded intersections. Descending traversal mirrors the scans and
  rule order and applies the SAME witness predicate, so ascending and
  descending walks agree on every emission position
  (LeastPathResume.dfy).

  All reads go through the routed fetch-fn seam (classification, retry,
  telemetry); the caller's cut-point runs before every adapter command;
  `:max-commands`/`:max-values` budgets mirror the probe check's and fail
  typed as `:eacl.reducer/limit-exceeded`."
  (:require [eacl.backend.v8 :as backend]
            [eacl.engine.stable-reducer :as reducer]
            [eacl.engine.stable-route :as route]))

(defn adapter-fetch-fn
  "Direction-aware width-one read seam: realizes one read-demand
  descriptor against the adapter, honoring the descriptor's
  `:direction` (the reducer's own seam is ascending-only; descending
  windows need `:desc` scans). Callers supplying their own `:fetch-fn`
  (the routed classified/retrying seam) must preserve `:direction`."
  [adapter]
  (fn [{:keys [operation bound-eid direction] :as descriptor}]
    (let [options (cond-> {:direction (or direction :asc)}
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

;; ---------------------------------------------------------------------------
;; Context: budgeted, cut-pointed reads through the routed fetch seam
;; ---------------------------------------------------------------------------

(defn- limit-failure!
  [limit-key counters detail]
  (throw (ex-info "Least-path semantic limit exceeded."
                  (merge {:eacl/error :eacl.reducer/limit-exceeded
                          :limit limit-key
                          :commands (:commands counters)
                          :fetched-values (:fetched-values counters)
                          :discovered 0}
                         detail))))

(defn make-context
  "One request-scoped read context. `:fetch-fn` is the routed read seam
  (or `:adapter` for the direct path); budgets and the cut-point mirror
  the probe check's semantics."
  [{:keys [adapter fetch-fn cut-point! physical-chunk-size
           max-commands max-values]
    :or {physical-chunk-size reducer/default-physical-chunk-size
         max-commands reducer/default-max-commands
         max-values reducer/default-max-values}}]
  {:pre [(or (some? adapter) (some? fetch-fn))]}
  (let [fetch-fn (or fetch-fn (adapter-fetch-fn adapter))
        counters (volatile! {:commands 0 :fetched-values 0
                             :stream-opens 0 :emissions 0})]
    {:counters counters
     :chunk physical-chunk-size
     :fetch!
     (fn [descriptor]
       (when cut-point! (cut-point! @counters))
       (when (>= (:commands @counters) max-commands)
         (limit-failure! :max-commands @counters
                         {:max-commands max-commands}))
       (let [values (into [] (take (:limit descriptor))
                          (fetch-fn descriptor))]
         (when (> (+ (:fetched-values @counters) (count values))
                  max-values)
           (limit-failure! :max-values @counters
                           {:max-values max-values
                            :staged (count values)}))
         (vswap! counters
                 #(-> %
                      (update :commands inc)
                      (update :fetched-values + (count values))
                      (cond-> (nil? (:bound-eid descriptor))
                        (update :stream-opens inc))))
         values))}))

(defn- fwd-scan
  [subject-type subject-eid relation-eid resource-type bound limit desc?]
  {:operation :subject->resources
   :subject-type subject-type :subject-eid subject-eid
   :relation-eid relation-eid :resource-type resource-type
   :bound-eid bound :limit limit
   :direction (if desc? :desc :asc)})

(defn- rev-scan
  [resource-type resource-eid relation-eid subject-type bound limit desc?]
  {:operation :resource->subjects
   :resource-type resource-type :resource-eid resource-eid
   :relation-eid relation-eid :subject-type subject-type
   :bound-eid bound :limit limit
   :direction (if desc? :desc :asc)})

;; ---------------------------------------------------------------------------
;; Immutable chunked streams
;; ---------------------------------------------------------------------------

(defn- stream
  "An immutable chunked scan stream; `mk-desc` = (fn [bound limit] desc)."
  [mk-desc bound]
  {:mk mk-desc :buf [] :idx 0 :bound bound :done? false})

(defn- stream-next
  "[value stream'] or [nil stream'] on exhaustion."
  [ctx {:keys [mk buf idx bound done?] :as s}]
  (cond
    (< idx (count buf))
    [(nth buf idx) (assoc s :idx (inc idx) :bound (nth buf idx))]

    done? [nil s]

    :else
    (let [chunk ((:fetch! ctx) (mk bound (:chunk ctx)))]
      (if (empty? chunk)
        [nil (assoc s :done? true)]
        [(nth chunk 0)
         (assoc s :buf chunk :idx 1 :bound (nth chunk 0)
                :done? (< (count chunk) (:chunk ctx)))]))))

;; ---------------------------------------------------------------------------
;; Exact-bound probes and bounded intersections (witness primitives)
;; ---------------------------------------------------------------------------

(defn- probe-fwd?
  "Does (subject, relation, candidate) exist? One exact-bound forward scan."
  [ctx subject-type subject-eid relation-eid resource-type candidate]
  (= candidate
     (first ((:fetch! ctx)
             (fwd-scan subject-type subject-eid relation-eid resource-type
                       (dec candidate) 1 false)))))

(defn- probe-rev?
  "Does (candidate, relation, resource) exist? One exact-bound reverse scan."
  [ctx resource-type resource-eid relation-eid subject-type candidate]
  (= candidate
     (first ((:fetch! ctx)
             (rev-scan resource-type resource-eid relation-eid subject-type
                       (dec candidate) 1 false)))))

(defn- isect2?
  "Interleaved min-side intersection of two ascending streams, each side
  decided by the opposite side's exact probe
  (BidirectionalArrowIntersection.dfy `Decide`): stops at the first hit
  or the first exhausted side. `below` (exclusive) bounds both sides."
  [ctx a-stream a-member? b-stream b-member? below]
  (let [beyond? (fn [v] (and below (>= v below)))]
    (loop [a a-stream b b-stream]
      (let [[av a'] (stream-next ctx a)]
        (cond
          (or (nil? av) (beyond? av)) false
          (a-member? av) true
          :else
          (let [[bv b'] (stream-next ctx b)]
            (cond
              (or (nil? bv) (beyond? bv)) false
              (b-member? bv) true
              :else (recur a' b'))))))))

(defn- alternate-witness?
  "Alternates one entity-side candidate (tested by `entity-hit?`) with one
  closure-side emission (accepted by `child-hit?`, optionally cut off by
  `child-stop?`). Returns true on the first hit from either side; false
  when the entity side exhausts (it completely covers the candidates that
  can carry the base tuple) — so cost is bounded by the smaller side
  (spec: witness work never bounded by an entity's total fan-in alone)."
  [ctx entity-stream entity-hit? env child child-next child-hit? child-stop?]
  (loop [es entity-stream child child]
    (let [[cand es'] (stream-next ctx es)]
      (cond
        (and cand (entity-hit? cand)) true
        (nil? cand) false
        :else
        (if (nil? child)
          (recur es' nil)
          (let [[emission child'] (child-next env child)]
            (cond
              (nil? emission) (recur es' nil)
              (and child-stop? (child-stop? emission)) (recur es' nil)
              (child-hit? emission) true
              :else (recur es' child'))))))))

(defn- derives?
  "Does `subject-eid` reach `node`'s permission on `resource-eid`? The
  certified membership-probe check anchored at `node`
  (`eacl.engine.stable-route/derives-from-node?`)."
  [{:keys [plan route-opts subject-type]} node subject-eid resource-eid]
  (route/derives-from-node?
   (assoc route-opts
          :plan plan
          :start-node node
          :subject-type subject-type
          :subject-eid subject-eid
          :resource-eid resource-eid)))

;; ---------------------------------------------------------------------------
;; Coordinates
;; ---------------------------------------------------------------------------

(defn compare-coords
  "Lexicographic comparison of coordinate vectors (LeastPathOrder.Lex);
  complete coordinates of one plan are never prefixes of one another."
  [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (< i n)
        (let [c (compare (nth a i) (nth b i))]
          (if (zero? c) (recur (inc i)) c))
        (compare (count a) (count b))))))

;; ---------------------------------------------------------------------------
;; Forward enumeration (lookup-resources): entities for one subject
;; ---------------------------------------------------------------------------
;;
;; A level enumerates, for plan node N, the entities V with
;; "subject has N on V", in least-path order of the sub-plan rooted at N.
;; Frame state:
;;   {:node N :rules rv :order [rule indexes in traversal order]
;;    :oi k :sub <per-rule state>}
;; Per-rule sub state:
;;   :relation        {:scan stream}
;;   :self-permission {:child level-state}
;;   :arrow-relation  {:outer stream :i I|nil :inner stream|nil}
;;   :arrow-permission{:child level-state :i {:value I :coords c}|nil
;;                     :inner stream|nil}

(declare fwd-level-next)

(defn- node-rules [env node]
  (get-in (:plan env) [:indexes :reverse-rules node]))

(defn- rule-order
  [env rules]
  (if (:desc? env)
    (vec (reverse (range (count rules))))
    (vec (range (count rules)))))

(defn- fwd-mk-level
  [env node]
  (let [rules (vec (node-rules env node))]
    {:node node :rules rules :order (rule-order env rules) :oi 0
     :sub nil}))

(defn- fwd-rule-sub
  "Fresh per-rule traversal state."
  [env rule]
  (let [{:keys [ctx subject-type subject-eid desc?]} env]
    (case (:rule rule)
      :relation
      (when (= subject-type (:subject-type rule))
        {:scan (stream #(fwd-scan subject-type subject-eid
                                  (:relation-eid rule)
                                  (:resource-type rule) %1 %2 desc?)
                       nil)})

      :self-permission
      {:child (fwd-mk-level env (:target-node rule))}

      :arrow-relation
      (when (= subject-type (:target-subject-type rule))
        {:outer (stream #(fwd-scan subject-type subject-eid
                                   (:target-relation-eid rule)
                                   (:intermediate-type rule) %1 %2 desc?)
                        nil)
         :i nil :inner nil})

      :arrow-permission
      {:child (fwd-mk-level env (:target-node rule))
       :i nil :inner nil})))

(defn- fwd-inner-stream
  [env rule intermediate]
  (stream #(fwd-scan (:intermediate-type rule) intermediate
                     (:via-relation-eid rule)
                     (:resource-type rule) %1 %2 (:desc? env))
          nil))

;; --- witness clauses (forward) ---------------------------------------------

(defn- fwd-rule-derives?
  "Does `rule` derive entity V for the subject? The per-clause decision of
  the earlier-alternative witness (LeastPathOrder.LexDecomposition):
  relation arms are one probe; two-layer arms are min-side interleaved
  intersections; permission targets recurse through the certified
  node-anchored probe check."
  [env rule v]
  (let [{:keys [ctx subject-type subject-eid]} env]
    (case (:rule rule)
      :relation
      (and (= subject-type (:subject-type rule))
           (probe-fwd? ctx subject-type subject-eid
                       (:relation-eid rule) (:resource-type rule) v))

      :self-permission
      (derives? env (:target-node rule) subject-eid v)

      :arrow-relation
      (and (= subject-type (:target-subject-type rule))
           (isect2? ctx
                    ;; subject's target-relation holdings
                    (stream #(fwd-scan subject-type subject-eid
                                       (:target-relation-eid rule)
                                       (:intermediate-type rule) %1 %2 false)
                            nil)
                    #(probe-rev? ctx (:resource-type rule) v
                                 (:via-relation-eid rule)
                                 (:intermediate-type rule) %)
                    ;; V's via-set
                    (stream #(rev-scan (:resource-type rule) v
                                       (:via-relation-eid rule)
                                       (:intermediate-type rule) %1 %2 false)
                            nil)
                    #(probe-fwd? ctx subject-type subject-eid
                                 (:target-relation-eid rule)
                                 (:intermediate-type rule) %)
                    nil))

      :arrow-permission
      ;; ∃ I in V's via-set with subject reaching target-node on I:
      ;; alternate V's via candidates (each decided by the certified
      ;; node-anchored check) with the closure's own least-path
      ;; enumeration (each emission probed against V).
      (alternate-witness?
       ctx
       (stream #(rev-scan (:resource-type rule) v
                          (:via-relation-eid rule)
                          (:intermediate-type rule) %1 %2 false)
               nil)
       #(derives? env (:target-node rule) subject-eid %)
       (assoc env :desc? false)
       (fwd-mk-level (assoc env :desc? false) (:target-node rule))
       fwd-level-next
       #(probe-rev? ctx (:resource-type rule) v
                    (:via-relation-eid rule)
                    (:intermediate-type rule) (:value %))
       nil))))

(defn- fwd-least-coords
  "The least derivation coordinates of `v` under `node`, or nil when not
  derivable. Guided descent: first deriving rule in sealed order; within
  an arrow, the least intermediate (by eid for relation targets, by
  recursive least coordinates for permission targets, restricted to the
  entity's via candidates). Bounded by depth × alternatives × probes."
  [env node v]
  (let [{:keys [ctx subject-type subject-eid]} env
        rules (vec (node-rules env node))]
    (loop [oi 0]
      (when (< oi (count rules))
        (let [rule (nth rules oi)]
          (or
           (case (:rule rule)
             :relation
             (when (and (= subject-type (:subject-type rule))
                        (probe-fwd? ctx subject-type subject-eid
                                    (:relation-eid rule)
                                    (:resource-type rule) v))
               [(:ordinal rule) v])

             :self-permission
             (when-let [sub (fwd-least-coords env (:target-node rule) v)]
               (into [(:ordinal rule)] sub))

             :arrow-relation
             (when (= subject-type (:target-subject-type rule))
               ;; least eid in (subject's holdings ∩ v's via-set):
               ;; ascending interleave to the first common element.
               (let [i (loop [s (stream #(fwd-scan subject-type subject-eid
                                                   (:target-relation-eid rule)
                                                   (:intermediate-type rule)
                                                   %1 %2 false)
                                        nil)]
                         (let [[cand s'] (stream-next ctx s)]
                           (cond
                             (nil? cand) nil
                             (probe-rev? ctx (:resource-type rule) v
                                         (:via-relation-eid rule)
                                         (:intermediate-type rule) cand)
                             cand
                             :else (recur s'))))]
                 (when i [(:ordinal rule) i v])))

             :arrow-permission
             ;; candidates limited to v's via-set; least by sub-coords.
             (let [best
                   (loop [s (stream #(rev-scan (:resource-type rule) v
                                               (:via-relation-eid rule)
                                               (:intermediate-type rule)
                                               %1 %2 false)
                                    nil)
                          best nil]
                     (let [[i s'] (stream-next ctx s)]
                       (if (nil? i)
                         best
                         (let [sub (fwd-least-coords
                                    env (:target-node rule) i)]
                           (recur s'
                                  (if (and sub
                                           (or (nil? best)
                                               (neg? (compare-coords
                                                      sub (:sub best)))))
                                    {:sub sub}
                                    best))))))]
               (when best
                 (-> [(:ordinal rule)]
                     (into (:sub best))
                     (conj v)))))
           (recur (inc oi))))))))

(defn- fwd-arrow-perm-smaller-intermediate?
  "Same-rule smaller-witness for an arrow-to-permission arm: does some
  intermediate with strictly smaller sub-coordinates than `i-coords`
  carry (I', via, v)? Alternates the entity side (v's via candidates,
  each tested by derivability and least-coordinate comparison) with the
  closure side (a fresh child enumeration, already least-filtered,
  probed against v and cut at `i-coords`)."
  [env rule i-coords v]
  (let [{:keys [ctx]} env
        asc-env (assoc env :desc? false)]
    (alternate-witness?
     ctx
     (stream #(rev-scan (:resource-type rule) v
                        (:via-relation-eid rule)
                        (:intermediate-type rule) %1 %2 false)
             nil)
     (fn [cand]
       (when-let [least (fwd-least-coords
                         asc-env (:target-node rule) cand)]
         (neg? (compare-coords least i-coords))))
     asc-env
     (fwd-mk-level asc-env (:target-node rule))
     fwd-level-next
     #(probe-rev? ctx (:resource-type rule) v
                  (:via-relation-eid rule)
                  (:intermediate-type rule) (:value %))
     #(>= (compare-coords (:coords %) i-coords) 0))))

(defn- fwd-same-rule-witness?
  "Same-rule smaller-intermediate clause at emission of `v` through
  `rule`; `binding` is the rule's current traversal binding."
  [env rule binding v]
  (case (:rule rule)
    :relation false
    :self-permission false ;; child recursion already least-filtered
    :arrow-relation
    ;; I' < I (eid) with (subject, tr, I') ∧ (I', via, v)
    (let [{:keys [ctx subject-type subject-eid]} env
          i (:i binding)]
      (isect2? ctx
               (stream #(fwd-scan subject-type subject-eid
                                  (:target-relation-eid rule)
                                  (:intermediate-type rule) %1 %2 false)
                       nil)
               #(probe-rev? ctx (:resource-type rule) v
                            (:via-relation-eid rule)
                            (:intermediate-type rule) %)
               (stream #(rev-scan (:resource-type rule) v
                                  (:via-relation-eid rule)
                                  (:intermediate-type rule) %1 %2 false)
                       nil)
               #(probe-fwd? ctx subject-type subject-eid
                            (:target-relation-eid rule)
                            (:intermediate-type rule) %)
               i))
    :arrow-permission
    (fwd-arrow-perm-smaller-intermediate?
     env rule (:coords (:i binding)) v)))

;; --- the forward machine ---------------------------------------------------

(defn- fwd-earlier-in-sealed-order
  "Indexes (into :order space) of rules sealed-before the current one —
  direction-independent witness domain."
  [{:keys [rules order oi]}]
  (let [current (nth order oi)]
    (filterv #(< % current) order)))

(defn- fwd-emit2?
  [env level rule binding v]
  (and (not (boolean
             (some #(fwd-rule-derives? env (nth (:rules level) %) v)
                   (fwd-earlier-in-sealed-order level))))
       (not (fwd-same-rule-witness? env rule binding v))))

(defn fwd-level-next
  "Advances one forward level; returns [{:value v :coords coords} state']
  or [nil state'] on exhaustion. Emissions are least-path filtered at
  this level (witness clauses) and every child level below it."
  [env {:keys [node rules order oi sub] :as level}]
  (if (>= oi (count order))
    [nil level]
    (let [rule (nth rules (nth order oi))
          sub (or sub (fwd-rule-sub env rule))
          advance #(assoc level :oi (inc oi) :sub nil)]
      (if (nil? sub)
        (recur env (advance))
        (case (:rule rule)
          :relation
          (let [[v scan'] (stream-next (:ctx env) (:scan sub))]
            (if (nil? v)
              (recur env (advance))
              (let [level' (assoc level :sub {:scan scan'})]
                (if (fwd-emit2? env level rule nil v)
                  [{:value v :coords [(:ordinal rule) v]} level']
                  (recur env level')))))

          :self-permission
          (let [[emission child'] (fwd-level-next env (:child sub))]
            (if (nil? emission)
              (recur env (advance))
              (let [level' (assoc level :sub {:child child'})
                    v (:value emission)]
                ;; earlier-rule witness only: the child already emits
                ;; least-only within the target node.
                (if (not (boolean
                          (some #(fwd-rule-derives?
                                  env (nth rules %) v)
                                (fwd-earlier-in-sealed-order level))))
                  [{:value v
                    :coords (into [(:ordinal rule)] (:coords emission))}
                   level']
                  (recur env level')))))

          :arrow-relation
          (if (nil? (:i sub))
            (let [[i outer'] (stream-next (:ctx env) (:outer sub))]
              (if (nil? i)
                (recur env (advance))
                (recur env (assoc level :sub
                                  {:outer outer' :i i
                                   :inner (fwd-inner-stream env rule i)}))))
            (let [[v inner'] (stream-next (:ctx env) (:inner sub))]
              (if (nil? v)
                (recur env (assoc level :sub
                                  (assoc sub :i nil :inner nil)))
                (let [level' (assoc level :sub (assoc sub :inner inner'))]
                  (if (fwd-emit2? env level rule {:i (:i sub)} v)
                    [{:value v
                      :coords [(:ordinal rule) (:i sub) v]} level']
                    (recur env level'))))))

          :arrow-permission
          (if (nil? (:i sub))
            (let [[emission child'] (fwd-level-next env (:child sub))]
              (if (nil? emission)
                (recur env (advance))
                (recur env (assoc level :sub
                                  {:child child' :i emission
                                   :inner (fwd-inner-stream
                                           env rule (:value emission))}))))
            (let [[v inner'] (stream-next (:ctx env) (:inner sub))]
              (if (nil? v)
                (recur env (assoc level :sub
                                  (assoc sub :i nil :inner nil)))
                (let [level' (assoc level :sub (assoc sub :inner inner'))]
                  (if (fwd-emit2? env level rule {:i (:i sub)} v)
                    [{:value v
                      :coords (-> [(:ordinal rule)]
                                  (into (:coords (:i sub)))
                                  (conj v))}
                     level']
                    (recur env level')))))))))))

;; ---------------------------------------------------------------------------
;; Reverse enumeration (lookup-subjects): subjects for one entity
;; ---------------------------------------------------------------------------

(declare rev-level-next)

(defn- rev-mk-level
  [env node entity]
  (let [rules (vec (node-rules env node))]
    {:node node :entity entity :rules rules
     :order (rule-order env rules) :oi 0 :sub nil}))

(defn- rev-rule-sub
  [env rule entity]
  (let [{:keys [subject-type desc?]} env]
    (case (:rule rule)
      :relation
      (when (= subject-type (:subject-type rule))
        {:scan (stream #(rev-scan (:resource-type rule) entity
                                  (:relation-eid rule)
                                  subject-type %1 %2 desc?)
                       nil)})

      :self-permission
      {:child (rev-mk-level env (:target-node rule) entity)}

      :arrow-relation
      (when (= subject-type (:target-subject-type rule))
        {:outer (stream #(rev-scan (:resource-type rule) entity
                                   (:via-relation-eid rule)
                                   (:intermediate-type rule) %1 %2 desc?)
                        nil)
         :i nil :inner nil})

      :arrow-permission
      {:outer (stream #(rev-scan (:resource-type rule) entity
                                 (:via-relation-eid rule)
                                 (:intermediate-type rule) %1 %2 desc?)
                      nil)
       :i nil :child nil})))

(defn- rev-rule-derives?
  "Does `rule` (on `entity`) derive subject s? Mirror of the forward
  clause decision; every intermediate side is an eid-ordered scan."
  [env rule entity s]
  (let [{:keys [ctx subject-type]} env]
    (case (:rule rule)
      :relation
      (and (= subject-type (:subject-type rule))
           (probe-rev? ctx (:resource-type rule) entity
                       (:relation-eid rule) subject-type s))

      :self-permission
      (derives? env (:target-node rule) s entity)

      :arrow-relation
      (and (= subject-type (:target-subject-type rule))
           (isect2? ctx
                    (stream #(rev-scan (:resource-type rule) entity
                                       (:via-relation-eid rule)
                                       (:intermediate-type rule) %1 %2 false)
                            nil)
                    #(probe-fwd? ctx subject-type s
                                 (:target-relation-eid rule)
                                 (:intermediate-type rule) %)
                    (stream #(fwd-scan subject-type s
                                       (:target-relation-eid rule)
                                       (:intermediate-type rule) %1 %2 false)
                            nil)
                    #(probe-rev? ctx (:resource-type rule) entity
                                 (:via-relation-eid rule)
                                 (:intermediate-type rule) %)
                    nil))

      :arrow-permission
      (alternate-witness?
       ctx
       (stream #(rev-scan (:resource-type rule) entity
                          (:via-relation-eid rule)
                          (:intermediate-type rule) %1 %2 false)
               nil)
       #(derives? env (:target-node rule) s %)
       (assoc env :desc? false :subject-eid s)
       (fwd-mk-level (assoc env :desc? false :subject-eid s)
                     (:target-node rule))
       fwd-level-next
       #(probe-rev? ctx (:resource-type rule) entity
                    (:via-relation-eid rule)
                    (:intermediate-type rule) (:value %))
       nil))))

(defn- rev-same-rule-witness?
  "I' < I (eid) through the same rule deriving the same subject."
  [env rule entity i s]
  (let [{:keys [ctx subject-type]} env]
    (case (:rule rule)
      :relation false
      :self-permission false
      :arrow-relation
      (isect2? ctx
               (stream #(rev-scan (:resource-type rule) entity
                                  (:via-relation-eid rule)
                                  (:intermediate-type rule) %1 %2 false)
                       nil)
               #(probe-fwd? ctx subject-type s
                            (:target-relation-eid rule)
                            (:intermediate-type rule) %)
               (stream #(fwd-scan subject-type s
                                  (:target-relation-eid rule)
                                  (:intermediate-type rule) %1 %2 false)
                       nil)
               #(probe-rev? ctx (:resource-type rule) entity
                            (:via-relation-eid rule)
                            (:intermediate-type rule) %)
               i)
      :arrow-permission
      (alternate-witness?
       ctx
       (stream #(rev-scan (:resource-type rule) entity
                          (:via-relation-eid rule)
                          (:intermediate-type rule) %1 %2 false)
               nil)
       #(and (< % i) (derives? env (:target-node rule) s %))
       (assoc env :desc? false :subject-eid s)
       (fwd-mk-level (assoc env :desc? false :subject-eid s)
                     (:target-node rule))
       fwd-level-next
       #(and (< (:value %) i)
             (probe-rev? ctx (:resource-type rule) entity
                         (:via-relation-eid rule)
                         (:intermediate-type rule) (:value %)))
       nil))))

(defn- rev-earlier-in-sealed-order
  [{:keys [order oi]}]
  (let [current (nth order oi)]
    (filterv #(< % current) order)))

(defn- rev-emit?
  [env level rule i s]
  (and (not (boolean
             (some #(rev-rule-derives? env (nth (:rules level) %)
                                       (:entity level) s)
                   (rev-earlier-in-sealed-order level))))
       (not (rev-same-rule-witness? env rule (:entity level) i s))))

(defn rev-level-next
  "Advances one reverse level; returns [{:value s :coords coords} state']."
  [env {:keys [entity rules order oi sub] :as level}]
  (if (>= oi (count order))
    [nil level]
    (let [rule (nth rules (nth order oi))
          sub (or sub (rev-rule-sub env rule entity))
          advance #(assoc level :oi (inc oi) :sub nil)]
      (if (nil? sub)
        (recur env (advance))
        (case (:rule rule)
          :relation
          (let [[s scan'] (stream-next (:ctx env) (:scan sub))]
            (if (nil? s)
              (recur env (advance))
              (let [level' (assoc level :sub {:scan scan'})]
                (if (rev-emit? env level rule nil s)
                  [{:value s :coords [(:ordinal rule) s]} level']
                  (recur env level')))))

          :self-permission
          (let [[emission child'] (rev-level-next env (:child sub))]
            (if (nil? emission)
              (recur env (advance))
              (let [level' (assoc level :sub {:child child'})
                    s (:value emission)]
                (if (not (boolean
                          (some #(rev-rule-derives?
                                  env (nth rules %) entity s)
                                (rev-earlier-in-sealed-order level))))
                  [{:value s
                    :coords (into [(:ordinal rule)] (:coords emission))}
                   level']
                  (recur env level')))))

          :arrow-relation
          (if (nil? (:i sub))
            (let [[i outer'] (stream-next (:ctx env) (:outer sub))]
              (if (nil? i)
                (recur env (advance))
                (recur env
                       (assoc level :sub
                              {:outer outer' :i i
                               :inner (stream
                                       #(rev-scan (:intermediate-type rule) i
                                                  (:target-relation-eid rule)
                                                  (:subject-type env)
                                                  %1 %2 (:desc? env))
                                       nil)}))))
            (let [[s inner'] (stream-next (:ctx env) (:inner sub))]
              (if (nil? s)
                (recur env (assoc level :sub (assoc sub :i nil :inner nil)))
                (let [level' (assoc level :sub (assoc sub :inner inner'))]
                  (if (rev-emit? env level rule (:i sub) s)
                    [{:value s :coords [(:ordinal rule) (:i sub) s]}
                     level']
                    (recur env level'))))))

          :arrow-permission
          (if (nil? (:i sub))
            (let [[i outer'] (stream-next (:ctx env) (:outer sub))]
              (if (nil? i)
                (recur env (advance))
                (recur env
                       (assoc level :sub
                              {:outer outer' :i i
                               :child (rev-mk-level
                                       env (:target-node rule) i)}))))
            (let [[emission child'] (rev-level-next env (:child sub))]
              (if (nil? emission)
                (recur env (assoc level :sub (assoc sub :i nil :child nil)))
                (let [level' (assoc level :sub (assoc sub :child child'))
                      s (:value emission)]
                  (if (rev-emit? env level rule (:i sub) s)
                    [{:value s
                      :coords (-> [(:ordinal rule) (:i sub)]
                                  (into (:coords emission)))}
                     level']
                    (recur env level')))))))))))

;; ---------------------------------------------------------------------------
;; Resume: rebuild a level positioned strictly past boundary coordinates
;; ---------------------------------------------------------------------------

(defn- rule-index-by-ordinal
  [rules ordinal]
  (some (fn [k] (when (= ordinal (:ordinal (nth rules k))) k))
        (range (count rules))))

(defn- order-position
  [order rule-index]
  (some (fn [k] (when (= rule-index (nth order k)) k))
        (range (count order))))

(defn- invalid-coords!
  [reason data]
  (throw (ex-info "Least-path cursor coordinates are not reproducible."
                  (assoc data :eacl/error :eacl.page/invalid-cursor
                         :reason reason))))

(defn fwd-resume-level
  "A forward level positioned strictly after `coords` (the boundary's
  coordinate suffix for this level). O(depth) stream seeks: each
  boundary scan opens bounded at its coordinate."
  [env node coords]
  (let [rules (vec (node-rules env node))
        order (rule-order env rules)
        _ (when (empty? coords) (invalid-coords! :empty {:node node}))
        ri (or (rule-index-by-ordinal rules (nth coords 0))
               (invalid-coords! :unknown-ordinal {:node node
                                                  :ordinal (nth coords 0)}))
        oi (order-position order ri)
        rule (nth rules ri)
        {:keys [subject-type subject-eid desc?]} env
        sub
        (case (:rule rule)
          :relation
          {:scan (stream #(fwd-scan subject-type subject-eid
                                    (:relation-eid rule)
                                    (:resource-type rule) %1 %2 desc?)
                         (nth coords 1))}

          :self-permission
          {:child (fwd-resume-level env (:target-node rule)
                                    (subvec (vec coords) 1))}

          :arrow-relation
          (let [i (nth coords 1) v (nth coords 2)]
            {:outer (stream #(fwd-scan subject-type subject-eid
                                       (:target-relation-eid rule)
                                       (:intermediate-type rule) %1 %2 desc?)
                            i)
             :i i
             :inner (stream #(fwd-scan (:intermediate-type rule) i
                                       (:via-relation-eid rule)
                                       (:resource-type rule) %1 %2 desc?)
                            v)})

          :arrow-permission
          (let [subcoords (subvec (vec coords) 1 (dec (count coords)))
                v (peek (vec coords))
                child (fwd-resume-level env (:target-node rule) subcoords)
                i (last (butlast coords))]
            ;; The intermediate value is the sub-derivation's own leaf:
            ;; the deepest coordinate of the sub-path.
            {:child child
             :i {:value i :coords (vec subcoords)}
             :inner (stream #(fwd-scan (:intermediate-type rule) i
                                       (:via-relation-eid rule)
                                       (:resource-type rule) %1 %2 desc?)
                            v)}))]
    {:node node :rules rules :order order :oi oi :sub sub}))

(defn rev-resume-level
  [env node entity coords]
  (let [rules (vec (node-rules env node))
        order (rule-order env rules)
        _ (when (empty? coords) (invalid-coords! :empty {:node node}))
        ri (or (rule-index-by-ordinal rules (nth coords 0))
               (invalid-coords! :unknown-ordinal {:node node
                                                  :ordinal (nth coords 0)}))
        oi (order-position order ri)
        rule (nth rules ri)
        {:keys [subject-type desc?]} env
        sub
        (case (:rule rule)
          :relation
          {:scan (stream #(rev-scan (:resource-type rule) entity
                                    (:relation-eid rule)
                                    subject-type %1 %2 desc?)
                         (nth coords 1))}

          :self-permission
          {:child (rev-resume-level env (:target-node rule) entity
                                    (subvec (vec coords) 1))}

          :arrow-relation
          (let [i (nth coords 1) s (nth coords 2)]
            {:outer (stream #(rev-scan (:resource-type rule) entity
                                       (:via-relation-eid rule)
                                       (:intermediate-type rule) %1 %2 desc?)
                            i)
             :i i
             :inner (stream #(rev-scan (:intermediate-type rule) i
                                       (:target-relation-eid rule)
                                       subject-type %1 %2 desc?)
                            s)})

          :arrow-permission
          (let [i (nth coords 1)
                subcoords (subvec (vec coords) 2)]
            {:outer (stream #(rev-scan (:resource-type rule) entity
                                       (:via-relation-eid rule)
                                       (:intermediate-type rule) %1 %2 desc?)
                            i)
             :i i
             :child (rev-resume-level env (:target-node rule) i subcoords)}))]
    {:node node :entity entity :rules rules :order order :oi oi :sub sub}))

;; ---------------------------------------------------------------------------
;; Public pagination API
;; ---------------------------------------------------------------------------

(defn- make-env
  [{:keys [plan subject-type subject-eid desc?] :as options} ctx]
  {:plan plan
   :ctx ctx
   :subject-type subject-type
   :subject-eid subject-eid
   :desc? (boolean desc?)
   :route-opts (select-keys options
                            [:adapter :fetch-fn :physical-chunk-size
                             :max-commands :max-values :max-transitions
                             :max-admissions :max-stack :cut-point!])})

(defn- run-page
  [env level next-fn page-size]
  (loop [level level
         emissions []]
    (if (= (count emissions) (inc page-size))
      {:emissions (subvec emissions 0 page-size)
       :has-more? true}
      (let [[emission level'] (next-fn env level)]
        (if (nil? emission)
          {:emissions emissions :has-more? false}
          (do (vswap! (:counters (:ctx env)) update :emissions inc)
              (recur level' (conj emissions emission))))))))

(defn forward-page
  "One least-path page of root entities for the subject.

  {:plan .. :adapter/:fetch-fn .. :subject-type .. :subject-eid ..
   :page-size n :after-coords c|nil :before-coords c|nil :last? bool}
  → {:emissions [{:value .. :coords ..} ...] :has-more? bool}

  `:after-coords` resumes ascending strictly past the boundary;
  `:before-coords` resumes DESCENDING strictly past the boundary (toward
  smaller coordinates); `:last?` starts a descending walk from the end.
  Descending pages return emissions in descending coordinate order; the
  caller reverses for public presentation."
  [{:keys [plan subject-eid page-size after-coords before-coords last?]
    :as options}]
  {:pre [(some? plan) (pos-int? page-size) (some? subject-eid)
         (not (and after-coords before-coords))]}
  (let [ctx (make-context options)
        desc? (boolean (or before-coords last?))
        env (make-env (assoc options :desc? desc?) ctx)
        root (:root plan)
        level (cond
                after-coords (fwd-resume-level env root (vec after-coords))
                before-coords (fwd-resume-level env root (vec before-coords))
                :else (fwd-mk-level env root))]
    (assoc (run-page env level fwd-level-next page-size)
           :counters @(:counters ctx))))

(defn reverse-page
  "One least-path page of subjects for the entity; see `forward-page`."
  [{:keys [plan resource-eid page-size after-coords before-coords last?]
    :as options}]
  {:pre [(some? plan) (pos-int? page-size) (some? resource-eid)
         (not (and after-coords before-coords))]}
  (let [ctx (make-context options)
        desc? (boolean (or before-coords last?))
        env (make-env (assoc options :desc? desc?) ctx)
        root (:root plan)
        level (cond
                after-coords (rev-resume-level env root resource-eid
                                               (vec after-coords))
                before-coords (rev-resume-level env root resource-eid
                                                (vec before-coords))
                :else (rev-mk-level env root resource-eid))]
    (assoc (run-page env level rev-level-next page-size)
           :counters @(:counters ctx))))

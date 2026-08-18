(ns eacl.engine.stable-route
  "Operation-appropriate routes on the stable-discovery engine
  (adopt-stable-discovery-enumeration, tasks 8.1-8.2; membership-probe
  point check, membership-probe-point-check).

  - Point checks are anchored to the known resource and answered by a
    membership-probe search over the sealed plan's reverse index: the few
    intermediates a resource reaches are enumerated, the subject itself is
    always looked up by one exact-bound probe. Cost is bounded by the number
    of reachable intermediates, never by the number of subjects that hold
    the permission (the reverse-enumeration check it replaces was linear in
    that number: a denied check on a resource with 5,000 owners cost 16 ms).
    The reverse-enumeration form is retained as `enumeration-check-eids`,
    the test oracle.
  - Exact count exhausts the history-free reducer; its scalar discovered
    count equals the denotation cardinality. Exhaustion is unbounded by
    construction (`exhaustion-target` is infinite): a run ends at an empty
    stack or a typed `:max-admissions`/`:max-values` failure, never at a
    silent cap. An order-insensitive specialization remains permitted only
    behind an independent denotation-equivalence proof (none exists yet)."
  (:require [eacl.backend.v8 :as backend]
            [eacl.engine.stable-reducer :as reducer]))

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

(defn- probe-check-eids
  "Iterative depth-first membership search. Returns true iff a derivation
  of the plan's root permission on `resource-eid` bottoms out in a tuple
  whose subject is `subject-eid`; equivalently, iff `subject-eid` belongs
  to the exhaustive reverse denotation `run-reverse` would emit.

  Reachability over the rule graph is decided by a visited set on
  [node eid]; a base tuple is decided by one exact-bound probe (the scan
  strictly after `subject-eid - 1`, limit one, equals `subject-eid` iff the
  tuple exists). Only intermediates are enumerated. Typed limits mirror the
  reducer's budgets: `:max-admissions` bounds distinct visited states,
  `:max-transitions` visits, `:max-commands` fetches, `:max-values` fetched
  values, `:max-stack` instantaneous stack depth."
  [{:keys [adapter fetch-fn plan subject-type subject-eid resource-eid
           cut-point! physical-chunk-size
           max-admissions max-commands max-transitions max-values max-stack]
    :or {physical-chunk-size reducer/default-physical-chunk-size
         max-admissions reducer/default-max-admissions
         max-commands reducer/default-max-commands
         max-transitions reducer/default-max-transitions
         max-values reducer/default-max-values
         max-stack reducer/default-max-stack}}]
  {:pre [(or (some? adapter) (some? fetch-fn)) (some? plan)
         (keyword? subject-type) (some? subject-eid) (some? resource-eid)]}
  (let [fetch-fn (or fetch-fn (reducer/adapter-fetch-fn adapter))
        reverse-rules (get-in plan [:indexes :reverse-rules])
        counters (volatile! {:admissions 0 :transitions 0 :commands 0
                             :fetched-values 0})
        fetch! (fn [descriptor]
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
                   (vswap! counters #(-> %
                                         (update :commands inc)
                                         (update :fetched-values
                                                 + (count values))))
                   values))
        probe? (fn [resource-type eid relation-eid]
                 (= subject-eid
                    (first (fetch! (reverse-scan resource-type eid
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
                              (recur (peek chunk) acc)))))
        report! (fn []
                  (when-let [stats reducer/*observer-stats*]
                    (let [{:keys [admissions commands transitions]} @counters]
                      (swap! stats
                             (fn [c]
                               (-> (or c {})
                                   (update :derived-grants (fnil + 0) admissions)
                                   (update :advanced-datoms (fnil + 0) commands)
                                   (update :queued-work (fnil + 0) transitions)))))))]
    (loop [stack [[(:root plan) resource-eid]]
           visited (transient #{})]
      (if (empty? stack)
        (do (report!) false)
        (let [[node eid :as state] (peek stack)
              stack (pop stack)]
          (when (>= (:transitions @counters) max-transitions)
            (limit-failure! :max-transitions @counters
                            {:max-transitions max-transitions}))
          (vswap! counters update :transitions inc)
          (when cut-point! (cut-point! @counters))
          (if (contains? visited state)
            (recur stack visited)
            (let [_ (when (>= (:admissions @counters) max-admissions)
                      (limit-failure! :max-admissions @counters
                                      {:max-admissions max-admissions
                                       :staged 1}))
                  _ (vswap! counters update :admissions inc)
                  visited (conj! visited state)
                  rules (get reverse-rules node)]
              ;; Base tuples first: one exact-bound probe per direct rule.
              (if (some (fn [rule]
                          (and (= :relation (:rule rule))
                               (= subject-type (:subject-type rule))
                               (probe? (:resource-type rule) eid
                                       (:relation-eid rule))))
                        rules)
                (do (report!) true)
                ;; Then the arrows: enumerate intermediates, probe or descend.
                (let [outcome
                      (reduce
                       (fn [successors rule]
                         (case (:rule rule)
                           :self-permission
                           (conj successors [(:target-node rule) eid])

                           :arrow-permission
                           (into successors
                                 (map (fn [i] [(:target-node rule) i]))
                                 (intermediates (:resource-type rule) eid
                                                (:via-relation-eid rule)
                                                (:intermediate-type rule)))

                           :arrow-relation
                           (if (and (= subject-type (:target-subject-type rule))
                                    (some (fn [i]
                                            (probe? (:intermediate-type rule) i
                                                    (:target-relation-eid rule)))
                                          (intermediates (:resource-type rule) eid
                                                         (:via-relation-eid rule)
                                                         (:intermediate-type rule))))
                             (reduced ::found)
                             successors)

                           successors))
                       []
                       rules)]
                  ;; Value comparison, not `identical?`: ClojureScript keyword
                  ;; literals are not interned objects.
                  (if (= ::found outcome)
                    (do (report!) true)
                    (let [stack (into stack (rseq outcome))]
                      (when (> (count stack) max-stack)
                        (limit-failure! :max-stack @counters
                                        {:max-stack max-stack
                                         :staged (count outcome)}))
                      (recur stack visited))))))))))))

(defn check-eids
  "Anchored point check over pre-resolved internal ids: does the subject
  hold the plan's root permission on the resource? Decided by the
  membership-probe search (`probe-check-eids`); nil ids never hold."
  [{:keys [subject-eid resource-eid] :as options}]
  (if (or (nil? subject-eid) (nil? resource-eid))
    false
    (probe-check-eids options)))

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
                        (merge (select-keys options
                                            [:adapter :fetch-fn :plan
                                             :subject-type
                                             :physical-chunk-size
                                             :sidecar-cap :max-admissions
                                             :max-commands
                                             :max-transitions :max-values :max-stack])
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

(defn count-resources
  "Exact count by exhausting the reducer; :count-limit truncates with an
  explicit marker exactly like the current public contract."
  [{:keys [adapter subject-id count-limit] :as options}]
  (let [subject-eid (backend/invoke adapter :object-id->internal subject-id)
        target (if count-limit (inc count-limit) exhaustion-target)]
    (if (nil? subject-eid)
      {:count 0 :limit (or count-limit -1) :truncated? false}
      (let [finished (reducer/run-forward
                      (merge (select-keys options
                                          [:adapter :fetch-fn :plan
                                           :subject-type :cut-point!
                                           :physical-chunk-size :sidecar-cap
                                           :max-admissions :max-commands
                                           :max-transitions :max-values :max-stack])
                             {:subject-eid subject-eid
                              :target target}))
            discovered (:discovered finished)
            truncated? (boolean (and count-limit
                                     (> discovered count-limit)))]
        {:count (if truncated? count-limit discovered)
         :limit (or count-limit -1)
         :truncated? truncated?}))))

(defn count-subjects
  "Exact reverse count by exhaustion, mirroring count-resources."
  [{:keys [adapter resource-id count-limit] :as options}]
  (let [resource-eid (backend/invoke adapter :object-id->internal resource-id)
        target (if count-limit (inc count-limit) exhaustion-target)]
    (if (nil? resource-eid)
      {:count 0 :limit (or count-limit -1) :truncated? false}
      (let [finished (reducer/run-reverse
                      (merge (select-keys options
                                          [:adapter :fetch-fn :plan
                                           :subject-type :cut-point!
                                           :physical-chunk-size :sidecar-cap
                                           :max-admissions :max-commands
                                           :max-transitions :max-values :max-stack])
                             {:resource-eid resource-eid
                              :target target}))
            discovered (:discovered finished)
            truncated? (boolean (and count-limit
                                     (> discovered count-limit)))]
        {:count (if truncated? count-limit discovered)
         :limit (or count-limit -1)
         :truncated? truncated?}))))

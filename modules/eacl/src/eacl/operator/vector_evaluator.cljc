(ns eacl.operator.vector-evaluator
  "Aligned mask-driven predicates for bounded acyclic candidate vectors."
  (:require [eacl.authorization.evidence :as evidence]
            [eacl.authorization.point-reuse :as point-reuse]
            [eacl.authorization.qualification :as qualification]
            [eacl.backend.direct-membership :as direct]
            [eacl.backend.v8 :as backend]
            [eacl.caveats.values :as caveat-values]
            [eacl.execution :as execution]
            [eacl.backend.entity-id :as entity-id]
            [eacl.operator.bitmask :as bitmask]
            [eacl.operator.evaluator :as scalar]
            [eacl.operator.plan :as operator-plan]
            [eacl.subproblem-cache :as subproblem]))

(def ^:private required-candidate-keys
  #{:direction :subject-type :subject-eid :resource-type :resource-eid})
(def ^:private optional-candidate-keys #{:true-nodes :evidence-witnesses})
(def maximum-evidence-witnesses 4096)
(def maximum-evidence-witness-bytes 4194304)
(def ^:private unresolved ::unresolved)

(def ^:dynamic *vector-stats*
  "Optional observation-only atom receiving vector predicate dimensions."
  nil)

(defn- add-stat! [counter amount]
  (when *vector-stats*
    (swap! *vector-stats* update counter (fnil + 0) amount))
  nil)

(defn- invalid! [reason message data]
  (throw
   (ex-info message
            (merge {:type :eacl.operator/invalid-vector
                    :eacl/error :eacl.operator/invalid-vector
                    :reason reason}
                   data))))

(defn- semantic-candidate-key [candidate]
  (select-keys candidate required-candidate-keys))

(defn- normalize-candidate [index candidate]
  (when-not (map? candidate)
    (invalid! :invalid-candidate "Vector candidate must be a map."
              {:index index :candidate candidate}))
  ;; Closed key set without allocating one: every required key present and
  ;; the map holds nothing beyond the required and optional keys.
  (when-not (and (every? #(contains? candidate %) required-candidate-keys)
                 (case (count candidate)
                   5 true
                   6 (or (contains? candidate :true-nodes) (contains? candidate :evidence-witnesses))
                   7 (and (contains? candidate :true-nodes) (contains? candidate :evidence-witnesses))
                   false))
    (invalid! :invalid-candidate-fields
              "Vector candidate has unknown or missing fields."
              {:index index
               :required-keys required-candidate-keys
               :optional-keys optional-candidate-keys
               :actual-keys (set (keys candidate))}))
  (when-not (contains? #{:forward :reverse} (:direction candidate))
    (invalid! :invalid-direction "Vector candidate direction is invalid."
              {:index index :direction (:direction candidate)}))
  (doseq [field [:subject-type :resource-type]]
    (when-not (keyword? (get candidate field))
      (invalid! :invalid-typed-identity
                "Vector candidate entity types must be keywords."
                {:index index :field field :value (get candidate field)})))
  (doseq [field [:subject-eid :resource-eid]]
    (when-not (entity-id/valid? (get candidate field))
      (invalid! :invalid-typed-identity
                "Vector candidate identifiers must be native nonnegative integers."
                {:index index :field field :value (get candidate field)})))
  (when-let [true-nodes (:true-nodes candidate)]
    (when-not (and (set? true-nodes)
                   (every? #(and (vector? %) (= 2 (count %))
                                 (vector? (first %))
                                 (integer? (second %)))
                           true-nodes))
      (invalid! :invalid-witness
                "Vector candidate witness nodes must be a set of plan-node keys."
                {:index index :true-nodes true-nodes})))
  (when (and (contains? candidate :evidence-witnesses)
             (not (map? (:evidence-witnesses candidate))))
    (invalid! :invalid-witness "Evidence witnesses must map exact plan nodes to evidence." {:index index}))
  (if (:true-nodes candidate)
    candidate
    (assoc candidate :true-nodes #{})))

(defn- normalize-candidates [candidates]
  (when-not (vector? candidates)
    (invalid! :invalid-candidates "Vector candidates must be a vector."
              {:value-type (some-> candidates type str)}))
  (when (> (count candidates) backend/maximum-direct-membership-batch-width)
    (invalid! :candidate-width
              "Vector candidate width exceeds the physical maximum."
              {:width (count candidates)
               :maximum-width backend/maximum-direct-membership-batch-width}))
  (let [normalized (mapv normalize-candidate (range) candidates)]
    ;; A single candidate (the point-check shape) cannot collide with itself.
    (when (> (count normalized) 1)
      (let [identities (mapv semantic-candidate-key normalized)]
        (when-not (= (count identities) (count (distinct identities)))
          (invalid! :duplicate-candidate
                    "Vector candidates must have distinct typed identities."
                    {:width (count identities)}))))
    normalized))

(defn- witness-size! [bytes]
  (when (> bytes maximum-evidence-witness-bytes)
    (invalid! :witness-size "Evidence witnesses exceed the vector byte bound." {:bytes bytes}))
  bytes)

(defn- validate-witness-map!
  "Checks every witness of one candidate against the plan, the request time
  and the byte bound, and returns the running byte total."
  [plan qualification bytes proofs]
  (reduce-kv
   (fn [bytes node value]
     (execution/check! :evidence-witness)
     (when-not (and (vector? node) (= 2 (count node))
                    (get-in (:predicate-programs plan) node))
       (invalid! :invalid-witness-node "Evidence witness is outside the sealed plan." {:node node}))
     (let [bytes (witness-size!
                  (+ bytes (if (boolean? value) 1
                               (caveat-values/utf8-size (evidence/encode value)))))]
       (when-not (evidence/before? (:time qualification) (evidence/valid-until value))
         (invalid! :expired-witness "Evidence witness certificate has already expired." {}))
       bytes))
   bytes proofs))

(defn- validate-evidence-witnesses!
  "Exact node evidence produced in this same selected request can discharge
   predicate work. A derivation's conditional lower bound is not an exact
   witness; its unresolved alternatives must still be evaluated by the owner.
   Validate before point-cache lookup as well as before fresh evaluation.
   Candidates that share one witness map have it checked once; the count and
   byte bounds apply to every candidate's witnesses."
  [{:keys [plan qualification witness-scope]} candidates]
  (when (and qualification (some #(seq (:true-nodes %)) candidates))
    (invalid! :unqualified-witness "Qualified evaluation requires temporal witness evidence." {}))
  (let [n (reduce #(+ %1 (count (:evidence-witnesses %2))) 0 candidates)]
    (when (pos? n)
      (when (> n maximum-evidence-witnesses)
        (invalid! :witness-limit "Evidence witness count exceeds the vector bound." {:count n}))
      (when-not qualification
        (invalid! :qualified-witness-required "Evidence witnesses require a qualified request." {}))
      (when-not (= witness-scope (qualification/exact-reuse-identity qualification))
        (invalid! :witness-scope "Evidence witnesses belong to another request scope." {}))
      (loop [index 0 bytes 0 checked nil checked-size 0]
        (when (< index (count candidates))
          (let [proofs (:evidence-witnesses (nth candidates index))]
            (cond
              (zero? (count proofs))
              (recur (inc index) bytes checked checked-size)

              (identical? proofs checked)
              (recur (inc index) (witness-size! (+ bytes checked-size)) checked checked-size)

              :else
              (let [total (validate-witness-map! plan qualification bytes proofs)]
                (recur (inc index) total proofs (- total bytes)))))))))
  nil)

(defn- direct-probe [candidate relation-id subject-eid]
  (if (= :forward (:direction candidate))
    {:direction :forward
     :descriptor {:subject-type (:subject-type candidate)
                  :subject-eid subject-eid
                  :relation-eid relation-id
                  :resource-type (:resource-type candidate)}
     :candidate [(:resource-type candidate) (:resource-eid candidate)]}
    {:direction :reverse
     :descriptor {:resource-type (:resource-type candidate)
                  :resource-eid (:resource-eid candidate)
                  :relation-eid relation-id
                  :subject-type (:subject-type candidate)}
     :candidate [(:subject-type candidate) subject-eid]}))

(defn- direct-probes
  "The physical probes deciding one candidate's direct membership: the
  subject's own tuple, and the wildcard subject's tuple when the relation
  declares `T:*`. The dispatcher deduplicates the shared wildcard probes."
  [candidate descriptor]
  (if-let [{:keys [relation-id wildcard-eid]}
           (operator-plan/relation-partition
            descriptor (:subject-type candidate))]
    (cond-> [(direct-probe candidate relation-id (:subject-eid candidate))]
      (and (some? wildcard-eid) (not= wildcard-eid (:subject-eid candidate)))
      (conj (direct-probe candidate relation-id wildcard-eid)))
    []))

(defn- held-decisions
  "Decisions for direct probes from the subject's retained holdings
  (`holdings`, see `stable-route/subject-holdings`), or nil to probe. Only
  forward probes of one subject and relation slice qualify; their number is
  the slice's demand. A complete slice decides every probe. A truncated one
  decides the resources up to its last endpoint, because scans are strictly
  ordered, and leaves `unresolved` for a probe past it. Each decision is the
  one the probe gives: the stored compact edge, qualified on the request."
  [holdings qualification probes]
  (when holdings
    (let [{:keys [direction descriptor]} (first probes)]
      (when (and (= :forward direction)
                 (every? #(and (= :forward (:direction %))
                               (= descriptor (:descriptor %)))
                         probes))
        (let [{:keys [subject-type subject-eid relation-eid resource-type]} descriptor
              {:keys [complete? edges bound]}
              (holdings subject-type subject-eid relation-eid resource-type (count probes))]
          (when (or complete? (some? bound))
            (mapv (fn [{[_ resource-eid] :candidate}]
                    (if (or complete? (<= resource-eid bound))
                      (let [compact-edge (get edges resource-eid)]
                        (if qualification
                          (qualification/qualify qualification relation-eid compact-edge)
                          (some? compact-edge)))
                      unresolved))
                  probes)))))))

(defn- root-masks
  "Observation-only aligned masks for one resolved row, derived from the memo
  when a `*vector-stats*` observer asks for them. The production path keeps
  no mask state: the memo row is the single source of every decision, so the
  masks are a projection of it rather than a second bookkeeping structure
  mutated on every resolution."
  [width row]
  (let [indexes-where (fn [pred]
                        (bitmask/from-indexes
                         width (filter #(pred (nth row %)) (range width))))]
    {:known-true (bitmask/portable (indexes-where evidence/has?))
     :known-false (bitmask/portable (indexes-where evidence/no?))
     :unresolved (bitmask/portable (indexes-where #(= unresolved %)))
     :failed (bitmask/portable (indexes-where evidence/fault?))}))

(declare check-many-normalized)

(defn ^:no-doc decisive?
  "A definite absorber: `true` for a union, `false` for an intersection or
  an exclusion's left operand. A fault is never decisive: an evaluator that
  stops at the first decisive operand of its static order must read past a
  faulting one."
  [op result]
  (if (= :union op) (evidence/has? result) (evidence/no? result)))

(defn check-many-eids
  "Evaluates a distinct vector of complete typed candidate contexts and
  returns one aligned decision per candidate, or throws without returning a
  partial vector. Direct leaves are regrouped through the bounded backend
  dispatcher; arrow leaves retain exact scalar semantics. Qualified callers
  may supply :evidence-witnesses per candidate and the complete :witness-scope
  from qualification/exact-reuse-identity. Only exact node results qualify."
  [{:keys [plan candidates] :as options}]
  (when-not (operator-plan/operator-plan? plan)
    (invalid! :operator-plan-required
              "Vector evaluation requires a sealed operator plan."
              {:plan-domain (:domain plan)}))
  (let [candidates (normalize-candidates candidates)]
    (validate-evidence-witnesses! options candidates)
    (check-many-normalized (assoc options :candidates candidates))))

(declare check-many-normalized)

(defn ^:no-doc check-many-trusted
  "`check-many-eids` for an engine caller that has already validated each
  candidate's typed point context and deduplicated the vector, and supplies
  every candidate with its `:true-nodes` set. Skips only that re-validation;
  witness validation and evaluation are unchanged."
  [{:keys [plan candidates] :as options}]
  (when-not (operator-plan/operator-plan? plan)
    (invalid! :operator-plan-required
              "Vector evaluation requires a sealed operator plan."
              {:plan-domain (:domain plan)}))
  (when (> (count candidates) backend/maximum-direct-membership-batch-width)
    (invalid! :candidate-width
              "Vector candidate width exceeds the physical maximum."
              {:width (count candidates)
               :maximum-width backend/maximum-direct-membership-batch-width}))
  (validate-evidence-witnesses! options candidates)
  (check-many-normalized options))

(defn- assoc-each
  "`row` with `(value index)` at every index of `indexes`. `value` reads only
  rows that are already persistent."
  [row indexes value]
  (if (zero? (count indexes))
    row
    (persistent!
     (reduce (fn [row index] (assoc! row index (value index)))
             (transient row) indexes))))

(defn- witnessed-nodes
  "The plan nodes some candidate carries a witness for. Candidates commonly
  share one witness map, which is read once."
  [candidates]
  (loop [index 0 nodes #{} seen nil]
    (if (= index (count candidates))
      nodes
      (let [candidate (nth candidates index)
            proofs (:evidence-witnesses candidate)
            true-nodes (:true-nodes candidate)
            nodes (if (or (nil? proofs) (identical? proofs seen))
                    nodes
                    (into nodes (keys proofs)))]
        (recur (inc index)
               (if (seq true-nodes) (into nodes true-nodes) nodes)
               (or proofs seen))))))

(defn- held-leaf-decisions
  "Decisions of one relation leaf for the `pending` candidates, read from the
  subject's retained holdings without building a probe, or nil when probes
  must decide them all. It applies when every pending candidate is a forward
  point of one subject and resource type and the relation declares no
  wildcard for another subject. A truncated slice decides the candidates up
  to its bound and leaves `unresolved` for one past it. Each decision is the
  one `held-decisions` gives the candidate's probe."
  [holdings qualification candidates pending descriptor]
  (when holdings
    (let [{:keys [direction subject-type subject-eid resource-type]}
          (nth candidates (nth pending 0))]
      (when (and (= :forward direction)
                 (every? #(let [candidate (nth candidates %)]
                            (and (= :forward (:direction candidate))
                                 (= subject-eid (:subject-eid candidate))
                                 (= subject-type (:subject-type candidate))
                                 (= resource-type (:resource-type candidate))))
                         pending))
        (when-let [{:keys [relation-id wildcard-eid]}
                   (operator-plan/relation-partition descriptor subject-type)]
          (when (or (nil? wildcard-eid) (= wildcard-eid subject-eid))
            (let [{:keys [complete? edges bound]}
                  (holdings subject-type subject-eid relation-id resource-type
                            (count pending))]
              (when (or complete? (some? bound))
                (mapv (fn [index]
                        (let [resource-eid (:resource-eid (nth candidates index))]
                          (if (or complete? (<= resource-eid bound))
                            (let [compact-edge (get edges resource-eid)]
                              (if qualification
                                (qualification/qualify qualification relation-id compact-edge)
                                (some? compact-edge)))
                            unresolved)))
                      pending)))))))))

(defn- check-many-normalized
  "Trusted core of `check-many-eids`: the candidate vector is already
  normalized (each caller normalizes exactly once at its boundary)."
  [{:keys [adapter plan candidates cache-lookup cache-publish-many!
           limits permission node-id qualification delegate holdings]}]
  (let [width (count candidates)]
    (if (zero? width)
      []
      (let [root-permission (or permission (:root plan))
            root-id (or node-id
                        (get (operator-plan/expression-roots plan)
                             root-permission))
            memo (volatile! {})
            active (volatile! #{})
            unresolved-row (vec (repeat width unresolved))
            node-roots (operator-plan/expression-roots plan)
            predicate-programs (:predicate-programs plan)
            cache-lookup (or cache-lookup (constantly direct/cache-miss))
            completed-leaves (volatile! [])
            witnessed-nodes (witnessed-nodes candidates)]
        (when-not (or (nil? cache-publish-many!)
                      (fn? cache-publish-many!))
          (invalid! :invalid-cache-publication
                    "Vector cache publication hook must be callable."
                    {:value-type (some-> cache-publish-many! type str)}))
        (when-not (some? root-id)
          (invalid! :missing-root
                    "Vector predicate root is outside the sealed plan."
                    {:permission root-permission :node-id node-id}))
        (letfn [(commit! [node-key values indexes]
                  ;; Indexes are distinct, so a full set overwrites every
                  ;; entry with `values`' own.
                  (let [resolved (if (= width (count indexes))
                                   values
                                   (assoc-each (get @memo node-key unresolved-row)
                                               indexes #(nth values %)))]
                    (vswap! memo assoc node-key resolved)
                    resolved))
                ;; `row` with `values`, aligned with the ascending distinct
                ;; `indexes`, at those indexes. Indexes that cover the row
                ;; make it `values` itself.
                (aligned [row indexes values]
                  (if (= (count row) (count indexes))
                    values
                    (persistent!
                     (reduce-kv (fn [row position index]
                                  (assoc! row index (nth values position)))
                                (transient row) indexes))))
                ;; `row` with a child's decisions at `indexes`: the child's
                ;; own row when the indexes cover it.
                (adopt [row indexes child]
                  (if (= width (count indexes))
                    child
                    (assoc-each row indexes #(nth child %))))
                ;; Defer both child calls and completed continuations. An
                ;; admitted chain may cross every permission in the schema;
                ;; its depth must not consume the JVM or JavaScript stack.
                (evaluate! [[permission node-id :as node-key] indexes continue]
                  (let [initial (get @memo node-key unresolved-row)
                        ;; An exact node witness, a faulting one included, is
                        ;; that node's value; composition decides whether a
                        ;; definite sibling absorbs its fault.
                        witnessed
                        (if (contains? witnessed-nodes node-key)
                          (persistent!
                           (reduce
                            (fn [values index]
                              (let [candidate (nth candidates index)
                                    proofs (:evidence-witnesses candidate)]
                                (cond
                                  (contains? proofs node-key) (assoc! values index (get proofs node-key))
                                  (contains? (:true-nodes candidate) node-key) (assoc! values index true)
                                  :else values)))
                            (transient initial) indexes))
                          initial)
                        _ (when-not (identical? witnessed initial)
                            (vswap! memo assoc node-key witnessed))
                        pending (if (identical? witnessed unresolved-row)
                                  indexes
                                  (filterv #(= unresolved (nth witnessed %))
                                           indexes))]
                    (if (empty? pending)
                      (fn [] (continue witnessed))
                      (do
                        ;; The active nodes are exactly the unfinished
                        ;; ancestors of this evaluation: children run one at a
                        ;; time, each on a subset of its parent's pending
                        ;; candidates. Re-entering an active node with pending
                        ;; candidates is therefore a cycle for each of them,
                        ;; so one membership test per node replaces one per
                        ;; node and candidate.
                        (when (contains? @active node-key)
                          (scalar/active-recursion-outcome
                           {:node node-key :candidate-index (first pending)}))
                        (vswap! active conj node-key)
                        (add-stat! :node-candidate-evaluations (count pending))
                        (let [predicate
                              (get-in predicate-programs
                                      [permission node-id])
                              instruction (:instruction predicate)
                              finish!
                              (fn [values]
                                (vswap! active disj node-key)
                                (let [resolved (commit! node-key values pending)]
                                  (fn [] (continue resolved))))]
                          (case instruction
                            :direct-membership
                            (let [held (when (nil? cache-publish-many!)
                                         (held-leaf-decisions
                                          holdings qualification candidates pending
                                          (:descriptor predicate)))
                                  witnessed (if held
                                              (aligned witnessed pending held)
                                              witnessed)
                                  ;; Probes decide what the retained holdings
                                  ;; did not.
                                  pending (if held
                                            (filterv #(= unresolved (nth witnessed %)) pending)
                                            pending)]
                              (if (empty? pending)
                                (finish! witnessed)
                                (let [indexed-probes
                                      (into []
                                            (keep (fn [index]
                                                    (let [probes (direct-probes
                                                                  (nth candidates index)
                                                                  (:descriptor predicate))]
                                                      (when (seq probes) [index probes]))))
                                            pending)
                                      dispatch!
                                      (fn [probes]
                                        ;; Own probes share one subject's slice,
                                        ;; as do wildcard probes, so each group
                                        ;; can be decided from retained holdings.
                                        (let [probe!
                                              (fn [probes]
                                                (if qualification
                                                  (mapv (fn [probe compact-edge]
                                                          (qualification/qualify qualification
                                                                                 (get-in probe [:descriptor :relation-eid])
                                                                                 compact-edge))
                                                        probes (direct/dispatch-edges adapter probes))
                                                  (direct/dispatch adapter probes cache-lookup)))
                                              held (when (seq probes)
                                                     (held-decisions holdings qualification probes))
                                              past (when held
                                                     (into [] (keep-indexed
                                                               (fn [index decision]
                                                                 (when (= unresolved decision) index)))
                                                           held))
                                              decisions
                                              (cond
                                                (empty? probes) []
                                                (nil? held) (probe! probes)
                                                (empty? past) held
                                                ;; Probes decide the resources
                                                ;; past a truncated slice.
                                                :else
                                                (aligned held past (probe! (mapv #(nth probes %) past))))]
                                          ;; Publish only after every demanded
                                          ;; subgroup in the vector succeeds.
                                          (when cache-publish-many!
                                            (vswap! completed-leaves into (mapv vector probes decisions)))
                                          decisions))
                                      own-decisions (dispatch! (mapv (comp first second) indexed-probes))
                                      wildcard-probes
                                      (into []
                                            (keep (fn [[[index probes] own]]
                                                    (when (and (second probes)
                                                               (not (evidence/has? own)))
                                                      [index (second probes)])))
                                            (map vector indexed-probes own-decisions))
                                      ;; Match scalar union demand: a definite own
                                      ;; grant decides alone. A faulting own tuple
                                      ;; still demands the wildcard, whose grant
                                      ;; absorbs the fault (strong Kleene).
                                      decisions (into own-decisions
                                                      (dispatch! (mapv second wildcard-probes)))
                                      probe-indexes (into (mapv first indexed-probes)
                                                          (map first wildcard-probes))]
                                  (finish!
                                   (reduce (fn [result index]
                                             (assoc result index false))
                                           ;; A wildcard probe unions with the
                                           ;; candidate's own probe.
                                           (reduce (fn [result [index decision]]
                                                     (let [prior (nth result index)]
                                                       (assoc result index
                                                              (if (= unresolved prior)
                                                                decision
                                                                (evidence/combine :union prior decision)))))
                                                   witnessed
                                                   (map vector probe-indexes
                                                        decisions))
                                           (remove (set probe-indexes) pending))))))

                            :permission-membership
                            (let [target (:target-node predicate)
                                  target-root (get node-roots target)]
                              (when-not (some? target-root)
                                (invalid! :missing-target-root
                                          "Permission vector target is missing."
                                          {:target target}))
                              (fn []
                                (evaluate! [target target-root] pending
                                           (fn [child]
                                             (finish! (adopt witnessed pending child))))))

                            :arrow-membership
                            (finish! (assoc-each
                                      witnessed pending
                                      (fn [index]
                                        (let [candidate (nth candidates index)]
                                          (scalar/check-eids
                                           {:adapter adapter :plan plan
                                            :permission permission
                                            :node-id node-id
                                            :subject-type
                                            (:subject-type candidate)
                                            :subject-eid (:subject-eid candidate)
                                            :resource-eid
                                            (:resource-eid candidate)
                                            :limits limits :qualification qualification
                                            :delegate delegate})))))

                            :delegated-membership
                            ;; A union-only operand of a delegated view: the
                            ;; oracle decides every pending candidate at once
                            ;; through that permission's own sealed union plan.
                            (let [decisions
                                  (when delegate
                                    (vec (delegate (:permission predicate)
                                                   (mapv #(nth candidates %) pending))))]
                              (when-not (and decisions
                                             (= (count pending) (count decisions)))
                                (invalid! :invalid-delegated-decisions
                                          "A delegated operand returned no aligned decisions."
                                          {:node node-key
                                           :expected (count pending)
                                           :actual (count decisions)}))
                              (finish! (aligned witnessed pending decisions)))

                            (:any-true :all-true)
                            (let [op (if (= :any-true instruction) :union :intersection)]
                              (letfn [(children! [children remaining result]
                                        (if (or (empty? children) (empty? remaining))
                                          (finish! result)
                                          (fn []
                                            (evaluate!
                                             [permission (first children)] remaining
                                             (fn [child]
                                               (let [result (assoc-each
                                                             result remaining
                                                             #(evidence/combine op
                                                                                (nth result %)
                                                                                (nth child %)))
                                                     remaining (filterv #(not (decisive? op (nth result %))) remaining)]
                                                 (children! (subvec children 1) remaining result)))))))]
                                (children! (operator-plan/operand-order plan permission node-id predicate)
                                           pending
                                           (assoc-each witnessed pending
                                                       (constantly (not= op :union))))))

                            :left-and-not-right
                            (fn []
                              (evaluate!
                               [permission (:left predicate)] pending
                               (fn [left]
                                 (let [admitted (filterv #(not (decisive? :exclusion (nth left %))) pending)
                                       result (adopt witnessed pending left)]
                                   (if (empty? admitted)
                                     (finish! result)
                                     (fn []
                                       (evaluate!
                                        [permission (:right predicate)] admitted
                                        (fn [right]
                                          (finish! (assoc-each
                                                    result admitted
                                                    #(evidence/combine :exclusion
                                                                       (nth left %)
                                                                       (nth right %))))))))))))

                            (invalid! :unknown-predicate-instruction
                                      "Vector plan contains an unknown predicate instruction."
                                      {:node node-key
                                       :instruction instruction})))))))]
          (try
            (let [decisions (trampoline evaluate! [root-permission root-id]
                                        (vec (range width)) identity)]
              (when *vector-stats*
                (add-stat! :candidate-count width)
                (add-stat! :mask-word-count (* 4 (bitmask/word-count width)))
                (swap! *vector-stats* assoc
                       :root-masks
                       (root-masks width
                                   (get @memo [root-permission root-id]))))
              (when (and cache-publish-many! (not-any? evidence/fault? decisions))
                (cache-publish-many! @completed-leaves))
              decisions)
            (catch #?(:clj Exception :cljs :default) error
              (add-stat! :failed-vectors 1)
              (throw error))))))))

(defn- point-cache-key
  [plan permission node-id scope-identity candidate]
  [:operator-acyclic-point 1
   (:fingerprint plan) permission node-id scope-identity
   (-> (semantic-candidate-key candidate)
       (update :subject-eid point-reuse/canonical-id)
       (update :resource-eid point-reuse/canonical-id))])

(defn check-cached-many-eids
  "Evaluates an aligned acyclic vector with proof-compatible completed point
  reuse. Cache hits only fill already demanded decisions. Point misses remain
  private until the entire demanded vector succeeds; cache-disabled execution
  performs no cache work."
  [{:keys [plan candidates permission node-id scope-identity qualification] :as options}]
  (when-not (operator-plan/operator-plan? plan)
    (invalid! :operator-plan-required
              "Vector evaluation requires a sealed operator plan."
              {:plan-domain (:domain plan)}))
  (let [candidates (normalize-candidates candidates)
        _ (validate-evidence-witnesses! options candidates)
        permission (or permission (:root plan))
        node-id (or node-id
                    (get (operator-plan/expression-roots plan) permission))
        options (assoc options :candidates candidates
                       :permission permission :node-id node-id)
        store subproblem/*store*
        ;; A qualified decision is keyed without its evaluation time and
        ;; stored with its certified interval, so a later request reuses it
        ;; while that interval admits the later time.
        scope (when store (point-reuse/scope qualification))]
    (if (or (nil? store) (empty? candidates))
      (check-many-normalized options)
      (let [indexes (range (count candidates))
            keys (mapv #(point-reuse/scoped-key
                         (point-cache-key plan permission node-id scope-identity %) scope)
                       candidates)
            reused (point-reuse/reuse! qualification keys ::miss)
            miss-indexes (filterv #(= ::miss (nth reused %)) indexes)
            misses (mapv candidates miss-indexes)
            miss-decisions
            (if (seq misses)
              (check-many-normalized (assoc options :candidates misses))
              [])
            decisions
            (persistent!
             (reduce (fn [decisions [index decision]] (assoc! decisions index decision))
                     (transient reused)
                     (map vector miss-indexes miss-decisions)))]
        ;; The full miss vector and its leaf subgroups have succeeded before
        ;; any completed point becomes externally reusable.
        (when (not-any? evidence/fault? miss-decisions)
          (point-reuse/publish! qualification (map (fn [index decision] [(nth keys index) decision])
                                                   miss-indexes miss-decisions)))
        (add-stat! :point-cache-hits (- (count candidates) (count misses)))
        (add-stat! :point-cache-misses (count misses))
        decisions))))

(ns eacl.operator.vector-evaluator
  "Aligned mask-driven predicates for bounded acyclic candidate vectors."
  (:require [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.backend.direct-membership :as direct]
            [eacl.backend.v8 :as backend]
            [eacl.exact-integer :as exact-integer]
            [eacl.operator.bitmask :as bitmask]
            [eacl.operator.evaluator :as scalar]
            [eacl.operator.plan :as operator-plan]
            [eacl.subproblem-cache :as subproblem]))

(def ^:private required-candidate-keys
  #{:direction :subject-type :subject-eid :resource-type :resource-eid})
(def ^:private optional-candidate-keys #{:true-nodes})
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
  ;; the map holds nothing beyond the required keys plus the one optional key.
  (when-not (and (every? #(contains? candidate %) required-candidate-keys)
                 (case (count candidate)
                   5 true
                   6 (contains? candidate :true-nodes)
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
    (when-not (exact-integer/natural? (get candidate field))
      (invalid! :invalid-typed-identity
                "Vector candidate identifiers must be portable natural integers."
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

(defn- direct-probe [candidate descriptor]
  (when-let [{:keys [relation-id]}
             (operator-plan/relation-partition
              descriptor (:subject-type candidate))]
    (if (= :forward (:direction candidate))
      {:direction :forward
       :descriptor {:subject-type (:subject-type candidate)
                    :subject-eid (:subject-eid candidate)
                    :relation-eid relation-id
                    :resource-type (:resource-type candidate)}
       :candidate [(:resource-type candidate) (:resource-eid candidate)]}
      {:direction :reverse
       :descriptor {:resource-type (:resource-type candidate)
                    :resource-eid (:resource-eid candidate)
                    :relation-eid relation-id
                    :subject-type (:subject-type candidate)}
       :candidate [(:subject-type candidate) (:subject-eid candidate)]})))

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

(defn- decisive? [op result]
  (or (evidence/fault? result)
      (if (= :union op) (evidence/has? result) (evidence/no? result))))

(defn check-many-eids
  "Evaluates a distinct vector of complete typed candidate contexts and
  returns one aligned Boolean per candidate, or throws without returning a
  partial vector. Direct leaves are regrouped through the bounded backend
  dispatcher; arrow leaves retain exact scalar semantics."
  [{:keys [plan candidates] :as options}]
  (when-not (operator-plan/operator-plan? plan)
    (invalid! :operator-plan-required
              "Vector evaluation requires a sealed operator plan."
              {:plan-domain (:domain plan)}))
  (check-many-normalized
   (assoc options :candidates (normalize-candidates candidates))))

(defn- check-many-normalized
  "Trusted core of `check-many-eids`: the candidate vector is already
  normalized (each caller normalizes exactly once at its boundary)."
  [{:keys [adapter plan candidates cache-lookup cache-publish-many!
           limits permission node-id qualification]}]
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
            completed-leaves (volatile! [])]
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
                  (let [current (get @memo node-key unresolved-row)
                        resolved (reduce (fn [result index]
                                           (assoc result index
                                                  (nth values index)))
                                         current indexes)]
                    (vswap! memo assoc node-key resolved)
                    resolved))
                (evaluate! [[permission node-id :as node-key] indexes]
                  (let [initial (get @memo node-key unresolved-row)
                        witnessed
                        (reduce
                         (fn [values index]
                           (if (contains? (:true-nodes (nth candidates index)) node-key)
                             (if qualification
                               (invalid! :unqualified-witness "Qualified evaluation requires temporal witness evidence." {})
                             (assoc values index true))
                             values))
                         initial indexes)
                        _ (vswap! memo assoc node-key witnessed)
                        pending (filterv #(= unresolved (nth witnessed %))
                                         indexes)]
                    (if (empty? pending)
                      witnessed
                      (do
                        (let [active-now @active]
                          (doseq [index pending]
                            (when (contains? active-now [node-key index])
                              (scalar/active-recursion-outcome
                               {:node node-key :candidate-index index}))))
                        (vswap! active into (map #(vector node-key %) pending))
                        (add-stat! :node-candidate-evaluations (count pending))
                        (let [predicate
                              (get-in predicate-programs
                                      [permission node-id])
                              instruction (:instruction predicate)
                              values
                              (case instruction
                                :direct-membership
                                (let [indexed-probes
                                      (keep (fn [index]
                                              (when-let [probe
                                                         (direct-probe
                                                          (nth candidates index)
                                                          (:descriptor predicate))]
                                                [index probe]))
                                            pending)
                                      probe-indexes (mapv first indexed-probes)
                                      probes (mapv second indexed-probes)
                                      decisions
                                      (if (seq probes)
                                        (if qualification
                                          (mapv (fn [probe compact-edge]
                                                  (qualification/qualify qualification
                                                                         (get-in probe [:descriptor :relation-eid])
                                                                         compact-edge))
                                                probes (direct/dispatch-edges adapter probes))
                                          (direct/dispatch adapter probes cache-lookup))
                                        [])]
                                  ;; Retain exact leaf decisions privately until
                                  ;; every demanded subgroup in the vector has
                                  ;; completed. A later failure therefore cannot
                                  ;; publish a successful prefix.
                                  (vswap! completed-leaves into
                                         (mapv vector probes decisions))
                                  (reduce (fn [result index]
                                            (assoc result index false))
                                          (reduce (fn [result [index decision]]
                                                    (assoc result index decision))
                                                  witnessed
                                                  (map vector probe-indexes
                                                       decisions))
                                          (remove (set probe-indexes) pending)))

                                :permission-membership
                                (let [target (:target-node predicate)
                                      target-root (get node-roots target)]
                                  (when-not (some? target-root)
                                    (invalid! :missing-target-root
                                              "Permission vector target is missing."
                                              {:target target}))
                                  (let [child (evaluate! [target target-root]
                                                         pending)]
                                    (reduce #(assoc %1 %2 (nth child %2))
                                            witnessed pending)))

                                :arrow-membership
                                (reduce
                                 (fn [result index]
                                   (let [candidate (nth candidates index)
                                         decision
                                         (scalar/check-eids
                                          {:adapter adapter :plan plan
                                           :permission permission
                                           :node-id node-id
                                           :subject-type
                                           (:subject-type candidate)
                                           :subject-eid (:subject-eid candidate)
                                           :resource-eid
                                           (:resource-eid candidate)
                                           :limits limits :qualification qualification})]
                                     (assoc result index decision)))
                                 witnessed pending)

                                (:any-true :all-true)
                                (let [op (if (= :any-true instruction) :union :intersection)]
                                  (loop [children (:children predicate)
                                         remaining pending
                                         result (reduce #(assoc %1 %2 (not= op :union)) witnessed pending)]
                                    (if (or (empty? children) (empty? remaining))
                                      result
                                      (let [child (evaluate! [permission (first children)] remaining)
                                            result (reduce (fn [row index]
                                                             (assoc row index (evidence/combine op
                                                                                               (nth row index)
                                                                                               (nth child index))))
                                                           result remaining)
                                            remaining (filterv #(not (decisive? op (nth result %))) remaining)]
                                        (recur (subvec children 1) remaining result)))))

                                :left-and-not-right
                                (let [left (evaluate! [permission (:left predicate)] pending)
                                      admitted (filterv #(not (decisive? :exclusion (nth left %))) pending)
                                      result (reduce #(assoc %1 %2 (nth left %2)) witnessed pending)]
                                  (if (empty? admitted)
                                    result
                                    (let [right (evaluate! [permission (:right predicate)] admitted)]
                                      (reduce (fn [row index]
                                                (assoc row index (evidence/combine :exclusion
                                                                                  (nth left index)
                                                                                  (nth right index))))
                                              result admitted))))

                                (invalid! :unknown-predicate-instruction
                                          "Vector plan contains an unknown predicate instruction."
                                          {:node node-key
                                           :instruction instruction}))]
                          (vswap! active #(reduce disj %
                                                 (map (fn [index]
                                                        [node-key index])
                                                      pending)))
                          (commit! node-key values pending))))))]
          (try
            (let [decisions (evaluate! [root-permission root-id]
                                       (vec (range width)))]
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

(def ^:private point-cache-options
  {:valid? boolean?})

(def ^:private qualified-point-cache-options {:valid? string?})

(defn- point-cache-key
  [plan permission node-id scope-identity candidate]
  [:operator-acyclic-point 1
   (:fingerprint plan) permission node-id scope-identity
   (semantic-candidate-key candidate)])

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
        permission (or permission (:root plan))
        node-id (or node-id
                    (get (operator-plan/expression-roots plan) permission))
        options (assoc options :candidates candidates
                       :permission permission :node-id node-id)
        store subproblem/*store*
        scope-identity (if (and store qualification)
                         [:qualified-point evidence/format-version scope-identity
                          (qualification/exact-reuse-identity qualification)]
                         scope-identity)]
    (if (or (nil? store) (empty? candidates))
      (check-many-normalized options)
      (let [looked-up
            (mapv
             (fn [candidate]
               (let [key (point-cache-key
                          plan permission node-id scope-identity candidate)]
                 (if-let [resolved
                          (subproblem/lookup-denotation! key)]
                   (do
                     (subproblem/record-avoided-backend-operation! store)
                     {:candidate candidate :key key
                      :decision (if qualification (evidence/decode (:value resolved)) (:value resolved))
                      :cached? true})
                   {:candidate candidate :key key :cached? false})))
             candidates)
            miss-records (filterv (complement :cached?) looked-up)
            misses (mapv :candidate miss-records)
            ;; Miss decisions align positionally with `miss-records`, so the
            ;; scatter back into candidate order walks one miss index rather
            ;; than hashing each candidate's semantic identity twice.
            miss-decisions
            (if (seq misses)
              (check-many-normalized (assoc options :candidates misses))
              [])
            decisions
            (loop [index 0 miss-index 0 decisions (transient [])]
              (if (= index (count looked-up))
                (persistent! decisions)
                (let [{:keys [decision cached?]} (nth looked-up index)]
                  (if cached?
                    (recur (inc index) miss-index (conj! decisions decision))
                    (recur (inc index) (inc miss-index)
                           (conj! decisions
                                  (nth miss-decisions miss-index)))))))]
        ;; The full miss vector and its leaf subgroups have succeeded before
        ;; any completed point becomes externally reusable.
        (when (and subproblem/*populate?* (not-any? evidence/fault? miss-decisions))
          (dotimes [miss-index (count miss-records)]
            (let [decision (nth miss-decisions miss-index)]
              (when-not (evidence/fault? decision)
                (subproblem/publish-denotation!
                 (:key (nth miss-records miss-index))
                 (if qualification qualified-point-cache-options point-cache-options)
                 (if qualification (evidence/encode decision) decision))))))
        (add-stat! :point-cache-hits (- (count looked-up) (count misses)))
        (add-stat! :point-cache-misses (count misses))
        decisions))))

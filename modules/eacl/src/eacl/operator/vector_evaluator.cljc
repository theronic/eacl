(ns eacl.operator.vector-evaluator
  "Aligned mask-driven predicates for bounded acyclic candidate vectors."
  (:require [eacl.backend.direct-membership :as direct]
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

(defn- normalize-candidate [index candidate]
  (when-not (map? candidate)
    (invalid! :invalid-candidate "Vector candidate must be a map."
              {:index index :candidate candidate}))
  (let [keys (set (keys candidate))]
    (when-not (and (every? keys required-candidate-keys)
                   (every? #(or (contains? required-candidate-keys %)
                                (contains? optional-candidate-keys %))
                           keys))
      (invalid! :invalid-candidate-fields
                "Vector candidate has unknown or missing fields."
                {:index index
                 :required-keys required-candidate-keys
                 :optional-keys optional-candidate-keys
                 :actual-keys keys})))
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
  (update candidate :true-nodes #(or % #{})))

(defn- normalize-candidates [candidates]
  (when-not (vector? candidates)
    (invalid! :invalid-candidates "Vector candidates must be a vector."
              {:value-type (some-> candidates type str)}))
  (when (> (count candidates) backend/maximum-direct-membership-batch-width)
    (invalid! :candidate-width
              "Vector candidate width exceeds the physical maximum."
              {:width (count candidates)
               :maximum-width backend/maximum-direct-membership-batch-width}))
  (let [normalized (mapv normalize-candidate (range) candidates)
        identities (mapv #(select-keys % required-candidate-keys) normalized)]
    (when-not (= (count identities) (count (distinct identities)))
      (invalid! :duplicate-candidate
                "Vector candidates must have distinct typed identities."
                {:width (count identities)}))
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

(defn- empty-node-masks [width]
  {:known-true (bitmask/native width)
   :known-false (bitmask/native width)
   :unresolved (bitmask/from-indexes width (range width))
   :failed (bitmask/native width)})

(defn- resolve-mask! [mask-state width node-key index value]
  (let [masks (or (get @mask-state node-key)
                  (empty-node-masks width))]
    (bitmask/clear-bit! (:unresolved masks) index)
    (bitmask/set-bit! (if value (:known-true masks) (:known-false masks))
                      index)
    (swap! mask-state assoc node-key masks)))

(defn- portable-masks [masks]
  (into {}
        (map (fn [[key mask]] [key (bitmask/portable mask)]))
        masks))

(defn check-many-eids
  "Evaluates a distinct vector of complete typed candidate contexts and
  returns one aligned Boolean per candidate, or throws without returning a
  partial vector. Direct leaves are regrouped through the bounded backend
  dispatcher; arrow leaves retain exact scalar semantics."
  [{:keys [adapter plan candidates cache-lookup cache-publish-many!
           limits permission node-id]}]
  (when-not (operator-plan/operator-plan? plan)
    (invalid! :operator-plan-required
              "Vector evaluation requires a sealed operator plan."
              {:plan-domain (:domain plan)}))
  (let [candidates (normalize-candidates candidates)
        width (count candidates)]
    (if (zero? width)
      []
      (let [root-permission (or permission (:root plan))
            root-id (or node-id
                        (get (operator-plan/expression-roots plan)
                             root-permission))
            memo (atom {})
            active (atom #{})
            mask-state (atom {})
            node-roots (operator-plan/expression-roots plan)
            cache-lookup (or cache-lookup (constantly direct/cache-miss))
            completed-leaves (atom [])]
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
                  (let [current (get @memo node-key
                                     (vec (repeat width unresolved)))
                        next (reduce (fn [result index]
                                       (let [value (boolean (nth values index))]
                                         (resolve-mask! mask-state width node-key
                                                        index value)
                                         (assoc result index value)))
                                     current indexes)]
                    (swap! memo assoc node-key next)
                    next))
                (evaluate! [[permission node-id :as node-key] indexes]
                  (let [initial (get @memo node-key
                                    (vec (repeat width unresolved)))
                        witnessed
                        (reduce
                         (fn [values index]
                           (if (contains? (:true-nodes (nth candidates index))
                                          node-key)
                             (do
                               (resolve-mask! mask-state width node-key index true)
                               (assoc values index true))
                             values))
                         initial indexes)
                        _ (swap! memo assoc node-key witnessed)
                        pending (filterv #(= unresolved (nth witnessed %))
                                         indexes)]
                    (if (empty? pending)
                      witnessed
                      (do
                        (doseq [index pending]
                          (when (contains? @active [node-key index])
                            (scalar/active-recursion-outcome
                             {:node node-key :candidate-index index})))
                        (swap! active into (map #(vector node-key %) pending))
                        (add-stat! :node-candidate-evaluations (count pending))
                        (let [predicate
                              (get-in plan
                                      [:predicate-programs permission node-id])
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
                                        (direct/dispatch adapter probes
                                                         cache-lookup)
                                        [])]
                                  ;; Retain exact leaf decisions privately until
                                  ;; every demanded subgroup in the vector has
                                  ;; completed. A later failure therefore cannot
                                  ;; publish a successful prefix.
                                  (swap! completed-leaves into
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
                                           :limits limits})]
                                     (assoc result index decision)))
                                 witnessed pending)

                                :any-true
                                (loop [children (:children predicate)
                                       remaining pending
                                       result witnessed]
                                  (if (or (empty? children)
                                          (empty? remaining))
                                    (reduce #(assoc %1 %2 false)
                                            result remaining)
                                    (let [child (evaluate!
                                                 [permission (first children)]
                                                 remaining)
                                          granted (filterv #(true? (nth child %))
                                                           remaining)
                                          remaining (filterv #(false? (nth child %))
                                                             remaining)
                                          result (reduce #(assoc %1 %2 true)
                                                         result granted)]
                                      (recur (subvec children 1)
                                             remaining result))))

                                :all-true
                                (loop [children (:children predicate)
                                       remaining pending
                                       result witnessed]
                                  (if (or (empty? children)
                                          (empty? remaining))
                                    (reduce #(assoc %1 %2 true)
                                            result remaining)
                                    (let [child (evaluate!
                                                 [permission (first children)]
                                                 remaining)
                                          rejected (filterv #(false? (nth child %))
                                                            remaining)
                                          remaining (filterv #(true? (nth child %))
                                                             remaining)
                                          result (reduce #(assoc %1 %2 false)
                                                         result rejected)]
                                      (recur (subvec children 1)
                                             remaining result))))

                                :left-and-not-right
                                (let [left (evaluate!
                                            [permission (:left predicate)]
                                            pending)
                                      admitted (filterv #(true? (nth left %))
                                                        pending)
                                      rejected (filterv #(false? (nth left %))
                                                        pending)
                                      result (reduce #(assoc %1 %2 false)
                                                     witnessed rejected)]
                                  (if (empty? admitted)
                                    result
                                    (let [right (evaluate!
                                                 [permission (:right predicate)]
                                                 admitted)]
                                      (reduce #(assoc %1 %2
                                                      (not (nth right %2)))
                                              result admitted))))

                                (invalid! :unknown-predicate-instruction
                                          "Vector plan contains an unknown predicate instruction."
                                          {:node node-key
                                           :instruction instruction}))]
                          (swap! active #(reduce disj %
                                                 (map (fn [index]
                                                        [node-key index])
                                                      pending)))
                          (commit! node-key values pending))))))]
          (try
            (let [decisions (evaluate! [root-permission root-id]
                                       (vec (range width)))
                  masks (get @mask-state [root-permission root-id])]
              (add-stat! :candidate-count width)
              (add-stat! :mask-word-count (* 4 (bitmask/word-count width)))
              (when *vector-stats*
                (swap! *vector-stats* assoc
                       :root-masks (portable-masks masks)))
              (when cache-publish-many!
                (cache-publish-many! @completed-leaves))
              (mapv boolean decisions))
            (catch #?(:clj Exception :cljs :default) error
              (add-stat! :failed-vectors 1)
              (throw error))))))))

(def ^:private point-cache-options
  {:valid? boolean?})

(defn- semantic-candidate-key [candidate]
  (select-keys candidate required-candidate-keys))

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
  [{:keys [plan candidates permission node-id scope-identity] :as options}]
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
        store subproblem/*store*]
    (if (or (nil? store) (empty? candidates))
      (check-many-eids options)
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
                      :decision (:value resolved) :cached? true})
                   {:candidate candidate :key key :cached? false})))
             candidates)
            misses (mapv :candidate (remove :cached? looked-up))
            miss-decisions
            (if (seq misses)
              (check-many-eids (assoc options :candidates misses))
              [])
            decisions-by-key
            (into {}
                  (map (fn [candidate decision]
                         [(semantic-candidate-key candidate) decision])
                       misses miss-decisions))
            decisions
            (mapv (fn [{:keys [candidate decision cached?]}]
                    (if cached?
                      decision
                      (get decisions-by-key
                           (semantic-candidate-key candidate))))
                  looked-up)]
        ;; The full miss vector and its leaf subgroups have succeeded before
        ;; any completed point becomes externally reusable.
        (when subproblem/*populate?*
          (doseq [{:keys [candidate key cached?]} looked-up
                  :when (not cached?)]
            (subproblem/publish-denotation!
             key point-cache-options
             (get decisions-by-key (semantic-candidate-key candidate)))))
        (add-stat! :point-cache-hits (count (filter :cached? looked-up)))
        (add-stat! :point-cache-misses (count misses))
        decisions))))

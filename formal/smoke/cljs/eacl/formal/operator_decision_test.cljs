(ns eacl.formal.operator-decision-test
  (:require [cljs.reader :as reader]
            [cljs.test :refer-macros [deftest is testing]]))

(def generated
  (js/require
   (.resolve
    (js/require "path")
    (.cwd js/process)
    "formal/smoke/js/generated_loader.cjs")))

(defn- vectors
  []
  (-> (js/require "fs")
      (.readFileSync "formal/cross-runtime/operator-vectors.edn" "utf8")
      reader/read-string))

(defn- big-number
  [value]
  (new (.-BigNumber generated) value))

(defn- dafny-sequence
  [values]
  (.apply
   (.-of (.-Seq (.-_dafny generated)))
   (.-Seq (.-_dafny generated))
   (into-array values)))

(defn- strategy
  [value]
  (cond
    (.-is_OperatorEmpty value) :empty
    (.-is_OperatorDensePrefix value) :dense-prefix
    (.-is_OperatorSparseExact value) :sparse-exact
    :else (throw (js/Error. "Unknown generated operator strategy."))))

(defn- generated-decision
  [{:keys [candidate-count first-eid last-eid maximum-span
           density-multiplier demand physical-cap candidate-window
           previous-width physical-decisions]}]
  (let [decision
        (js-invoke
         (.-__default (.-EaclKernel generated))
         "DecideOperatorBatch"
         (big-number candidate-count)
         (big-number first-eid)
         (big-number last-eid)
         (big-number maximum-span)
         (big-number density-multiplier)
         (big-number demand)
         (big-number physical-cap)
         (big-number candidate-window)
         (big-number previous-width)
         (dafny-sequence physical-decisions))]
    {:strategy (strategy (.-dtor_strategy decision))
     :span-valid (.-dtor_spanValid decision)
     :inclusive-span (.toNumber (.-dtor_inclusiveSpan decision))
     :initial-width (.toNumber (.-dtor_initialWidth decision))
     :grown-width (.toNumber (.-dtor_grownWidth decision))
     :logical-candidates (.toNumber (.-dtor_logicalCandidates decision))
     :physical-candidates (.toNumber (.-dtor_physicalCandidates decision))
     :physical-overread (.toNumber (.-dtor_physicalOverread decision))}))

(defn- operator-sign
  [sign]
  (case sign
    :positive
    (js-invoke (.-OperatorEdgeSign (.-EaclKernel generated))
               "create_OperatorPositive")
    :negative
    (js-invoke (.-OperatorEdgeSign (.-EaclKernel generated))
               "create_OperatorNegative")
    (throw (js/Error. (str "Unknown operator edge sign: " sign)))))

(defn- operator-edge
  [{:keys [source target sign]}]
  (js-invoke
   (.-OperatorDependencyEdge (.-EaclKernel generated))
   "create_OperatorDependencyEdge"
   (big-number source)
   (big-number target)
   (operator-sign sign)))

(defn- generated-signed-graph-decision
  [{:keys [vertices edges components]}]
  (let [decision
        (js-invoke
         (.-__default (.-EaclKernel generated))
         "DecideOperatorSignedGraph"
         (dafny-sequence (mapv big-number vertices))
         (dafny-sequence (mapv operator-edge edges))
         (dafny-sequence
          (mapv #(dafny-sequence (mapv big-number %)) components)))]
    (cond
      (.-is_OperatorSignedGraphAccepted decision)
      {:status :accepted}

      (.-is_OperatorInvalidComponentCertificate decision)
      {:status :invalid-component-certificate}

      (.-is_OperatorNonCanonicalEdgeSequence decision)
      {:status :noncanonical-edge-sequence}

      (.-is_OperatorNegativeCycle decision)
      {:status :negative-cycle
       :edge-index (.toNumber (.-dtor_edgeIndex decision))
       :source (.toNumber (.-dtor_source decision))
       :target (.toNumber (.-dtor_target decision))}

      :else
      (throw (js/Error. "Unknown generated signed-graph decision.")))))

(def recursive-generated
  (.-OperatorRecursiveGeneratedPolicy generated))

(defn- recursive-fact [expression entity-type entity-eid]
  (js-invoke
   (.-TypedExpressionFact recursive-generated)
   "create_TypedExpressionFact"
   (big-number expression) (big-number entity-type) (big-number entity-eid)))

(defn- recursive-rule [parent width intersection? anchor-slot]
  (js-invoke
   (.-PositiveRule recursive-generated)
   "create_PositiveRule"
   (big-number parent) (big-number width) intersection?
   (big-number anchor-slot)))

(defn- recursive-edge [child parent slot]
  (js-invoke
   (.-PositiveConsumerEdge recursive-generated)
   "create_PositiveConsumerEdge"
   (big-number child) (big-number parent) (big-number slot)))

(defn- recursive-state [facts completed pending]
  (js-invoke
   (.-RecursiveState recursive-generated)
   "create_RecursiveState"
   (dafny-sequence facts) (dafny-sequence [])
   (dafny-sequence (mapv big-number completed)) (dafny-sequence pending)))

(defn- admit-command [fact]
  (js-invoke (.-RecursiveCommand recursive-generated)
             "create_AdmitTypedFact" fact))

(defn- recursive-step [state rules edges command]
  (js-invoke
   (.-__default (.-EaclKernel generated))
   "DecideOperatorRecursiveCommand"
   state (dafny-sequence rules) (dafny-sequence edges)
   (dafny-sequence []) (dafny-sequence []) command))

(defn- prefix-for-demand
  [decisions demand]
  (loop [remaining decisions demand demand consumed 0]
    (if (or (zero? demand) (empty? remaining))
      consumed
      (recur (next remaining)
             (if (first remaining) (dec demand) demand)
             (inc consumed)))))

(defn- oracle
  [{:keys [candidate-count first-eid last-eid maximum-span
           density-multiplier demand physical-cap candidate-window
           previous-width physical-decisions]}]
  (let [span-valid? (and (<= first-eid last-eid)
                         (< (- last-eid first-eid) maximum-span))
        span (if span-valid? (inc (- last-eid first-eid)) 0)
        strategy (cond
                   (zero? candidate-count) :empty
                   (and span-valid?
                        (<= span (* density-multiplier candidate-count)))
                   :dense-prefix
                   :else :sparse-exact)
        logical (prefix-for-demand physical-decisions demand)
        physical (count physical-decisions)]
    {:strategy strategy
     :span-valid span-valid?
     :inclusive-span span
     :initial-width (min demand physical-cap candidate-window)
     :grown-width (min (* 2 previous-width)
                       physical-cap
                       candidate-window)
     :logical-candidates logical
     :physical-candidates physical
     :physical-overread (- physical logical)}))

(defn- next-random!
  [state]
  (let [next-state (mod (* 48271 @state) 2147483647)]
    (reset! state next-state)
    next-state))

(defn- next-int!
  [state bound]
  (mod (next-random! state) bound))

(defn- random-input
  [state]
  (let [candidate-count (next-int! state 300)
        first-eid (next-int! state 10000)
        reversed? (zero? (next-int! state 7))
        delta (next-int! state 20000)
        last-eid (if reversed?
                   (max 0 (- first-eid (inc (next-int! state 100))))
                   (+ first-eid delta))
        maximum-span (inc (next-int! state 20000))
        decision-count (next-int! state 257)]
    {:candidate-count candidate-count
     :first-eid first-eid
     :last-eid last-eid
     :maximum-span maximum-span
     :density-multiplier (inc (next-int! state 8))
     :demand (next-int! state 65)
     :physical-cap (inc (next-int! state 256))
     :candidate-window (inc (next-int! state 512))
     :previous-width (next-int! state 257)
     :physical-decisions
     (mapv (fn [_] (odd? (next-random! state)))
           (range decision-count))}))

(defn- edge-key
  [{:keys [source target sign]}]
  [source target (case sign :positive 0 :negative 1)])

(defn- canonical-edges?
  [edges]
  (and (= (count edges) (count (distinct edges)))
       (= edges (vec (sort-by edge-key edges)))))

(defn- reachable-closure
  [vertices edges source]
  (loop [fuel (count vertices)
         reachable #{source}]
    (if (zero? fuel)
      reachable
      (recur
       (dec fuel)
       (reduce
        (fn [next-reachable {:keys [source target]}]
          (if (contains? reachable source)
            (conj next-reachable target)
            next-reachable))
        reachable
        edges)))))

(defn- executable-reachable?
  [vertices edges source target]
  (and (some #{source} vertices)
       (some #{target} vertices)
       (contains? (reachable-closure vertices edges source) target)))

(defn- certificate-valid?
  [vertices edges components]
  (and
   (= (count vertices) (count (distinct vertices)))
   (every? (fn [{:keys [source target]}]
             (and (some #{source} vertices)
                  (some #{target} vertices)))
           edges)
   (every? (fn [vertex]
             (= 1 (count (filter #(some #{vertex} %) components))))
           vertices)
   (every?
    (fn [component]
      (and (seq component)
           (= (count component) (count (distinct component)))
           (every? #(some #{%} vertices) component)
           (let [anchor (first component)]
             (every?
              (fn [vertex]
                (= (boolean (some #{vertex} component))
                   (boolean
                    (and (executable-reachable?
                          vertices edges anchor vertex)
                         (executable-reachable?
                          vertices edges vertex anchor)))))
              vertices))))
    components)))

(defn- same-component?
  [components left right]
  (boolean
   (some (fn [component]
           (and (some #{left} component)
                (some #{right} component)))
         components)))

(defn- signed-graph-oracle
  [{:keys [vertices edges components]}]
  (cond
    (not (canonical-edges? edges))
    {:status :noncanonical-edge-sequence}

    (not (certificate-valid? vertices edges components))
    {:status :invalid-component-certificate}

    :else
    (if-let [[edge-index edge]
             (first
              (keep-indexed
               (fn [index {:keys [source target sign] :as edge}]
                 (when (and (= :negative sign)
                            (same-component? components source target))
                   [index edge]))
               edges))]
      {:status :negative-cycle
       :edge-index edge-index
       :source (:source edge)
       :target (:target edge)}
      {:status :accepted})))

(defn- exact-components
  [vertices edges]
  (loop [remaining vertices
         components []]
    (if-let [anchor (first remaining)]
      (let [component
            (filterv
             (fn [vertex]
               (and (executable-reachable? vertices edges anchor vertex)
                    (executable-reachable? vertices edges vertex anchor)))
             remaining)
            members (set component)]
        (recur (filterv #(not (contains? members %)) remaining)
               (conj components component)))
      components)))

(defn- random-signed-graph-input
  [state]
  (let [vertex-count (next-int! state 7)
        vertices (vec (range vertex-count))
        edges (->> (for [source vertices
                         target vertices
                         sign [:positive :negative]
                         :when (zero? (next-int! state 5))]
                     {:source source :target target :sign sign})
                   (sort-by edge-key)
                   vec)
        components (exact-components vertices edges)]
    (case (next-int! state 5)
      0 {:vertices vertices :edges edges :components components}
      1 {:vertices vertices
         :edges edges
         :components (if (seq vertices)
                       (conj components [(first vertices)])
                       [[]])}
      2 {:vertices vertices
         :edges (cond
                  (> (count edges) 1) (vec (reverse edges))
                  (= (count edges) 1) [(first edges) (first edges)]
                  :else [{:source 0 :target 0 :sign :positive}
                         {:source 0 :target 0 :sign :positive}])
         :components components}
      3 {:vertices (if (seq vertices)
                     (conj vertices (first vertices))
                     [0 0])
         :edges edges
         :components components}
      4 {:vertices vertices
         :edges (->> (conj edges {:source vertex-count
                                  :target vertex-count
                                  :sign :positive})
                     (sort-by edge-key)
                     vec)
         :components components})))

(deftest generated-javascript-operator-fixed-vectors
  (doseq [{:keys [input expected]} (:cases (vectors))]
    (is (= expected (generated-decision input)) (pr-str input))))

(deftest generated-javascript-operator-fixed-seed-differential
  (let [{:keys [seed random-cases]} (vectors)
        state (atom seed)]
    (testing "generated JavaScript decisions equal the independent oracle"
      (dotimes [case-index random-cases]
        (let [input (random-input state)]
          (is (= (oracle input) (generated-decision input))
              (pr-str {:case case-index :input input})))))))

(deftest generated-javascript-signed-graph-fixed-vectors
  (doseq [{:keys [input expected]} (:signed-graph-cases (vectors))]
    (is (= expected (generated-signed-graph-decision input))
        (pr-str input))))

(deftest generated-javascript-signed-graph-fixed-seed-differential
  (let [{:keys [signed-graph-seed signed-graph-random-cases]} (vectors)
        state (atom signed-graph-seed)]
    (testing "generated JavaScript signed-graph decisions equal the independent oracle"
      (dotimes [case-index signed-graph-random-cases]
        (let [input (random-signed-graph-input state)]
          (is (= (signed-graph-oracle input)
                 (generated-signed-graph-decision input))
              (pr-str {:case case-index :input input})))))))

(deftest generated-javascript-recursive-anchor-state
  (let [rules [(recursive-rule 30 2 true 0)]
        edges [(recursive-edge 10 30 0) (recursive-edge 20 30 1)]
        first-transition
        (recursive-step
         (recursive-state [] [] []) rules edges
         (admit-command (recursive-fact 20 7 42)))
        second-transition
        (recursive-step
         (.-dtor_state first-transition) rules edges
         (admit-command (recursive-fact 10 7 42)))
        anchor (first (.-dtor_anchorStates (.-dtor_state second-transition)))
        action (first (.-dtor_actions second-transition))]
    (is (.-is_RecursiveTransitionAccepted first-transition))
    (is (empty? (.-dtor_anchorStates (.-dtor_state first-transition))))
    (is (= [true true] (vec (.-dtor_satisfiedSlots anchor))))
    (is (= 2 (.toNumber (.-dtor_satisfiedCount anchor))))
    (is (.-is_ScheduleTypedFact action))
    (is (= 30 (.toNumber (.-dtor_expression (.-dtor_fact action)))))))

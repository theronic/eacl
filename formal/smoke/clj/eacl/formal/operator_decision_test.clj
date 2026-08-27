(ns eacl.formal.operator-decision-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [eacl.formal.java-operator-decision :as generated]
            [eacl.operator.batch-schedule :as batch-schedule])
  (:import (java.util Random)))

(defn- vectors
  []
  (edn/read-string
   (slurp "formal/cross-runtime/operator-vectors.edn")))

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

(defn- random-input
  [^Random random]
  (let [candidate-count (.nextInt random 300)
        first-eid (.nextInt random 10000)
        reversed? (zero? (.nextInt random 7))
        delta (.nextInt random 20000)
        last-eid (if reversed?
                   (max 0 (- first-eid (inc (.nextInt random 100))))
                   (+ first-eid delta))
        maximum-span (inc (.nextInt random 20000))
        decision-count (.nextInt random 257)]
    {:candidate-count candidate-count
     :first-eid first-eid
     :last-eid last-eid
     :maximum-span maximum-span
     :density-multiplier (inc (.nextInt random 8))
     :demand (.nextInt random 65)
     :physical-cap (inc (.nextInt random 256))
     :candidate-window (inc (.nextInt random 512))
     :previous-width (.nextInt random 257)
     :physical-decisions
     (mapv (fn [_] (.nextBoolean random)) (range decision-count))}))

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
  [^Random random]
  (let [vertex-count (.nextInt random 7)
        vertices (vec (range vertex-count))
        edges (->> (for [source vertices
                         target vertices
                         sign [:positive :negative]
                         :when (zero? (.nextInt random 5))]
                     {:source source :target target :sign sign})
                   (sort-by edge-key)
                   vec)
        components (exact-components vertices edges)]
    (case (.nextInt random 5)
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

(deftest generated-operator-fixed-vectors
  (doseq [{:keys [input expected]} (:cases (vectors))]
    (is (= expected (generated/decide input)) (pr-str input))))

(deftest generated-operator-fixed-seed-differential
  (let [{:keys [seed random-cases]} (vectors)
        random (Random. (long seed))]
    (testing "generated Java decisions equal the independent oracle"
      (dotimes [case-index random-cases]
        (let [input (random-input random)]
          (is (= (oracle input) (generated/decide input))
              (pr-str {:case case-index :input input})))))))

(deftest production-batch-schedule-is-bound-to-the-generated-decision
  ;; The differential runs the production schedule itself against the
  ;; generated batch-growth decision, step for step, rather than comparing
  ;; the kernel with a local re-copy of its formula. Every walk issues the
  ;; production-selected width and randomizes acceptance, so growth is
  ;; exercised both rejection-gated and demand-clamped.
  (let [random (Random. 640353)]
    (dotimes [walk 200]
      (let [result-demand (.nextInt random 400)
            candidate-window (.nextInt random 1200)]
        (loop [state (batch-schedule/initial result-demand candidate-window)
               step 0]
          (when (and (not (batch-schedule/done? state)) (< step 64))
            (let [issued (:next-width state)
                  accepted (.nextInt random (inc issued))
                  advanced (batch-schedule/advance state issued accepted)
                  generated-width
                  (generated/advance
                   {:remaining-demand (:remaining-demand advanced)
                    :remaining-window (:remaining-window advanced)
                    :physical-cap batch-schedule/maximum-width
                    :issued-width issued
                    :accepted-count accepted})]
              (is (= (:next-width advanced) generated-width)
                  (pr-str {:walk walk
                           :step step
                           :state state
                           :issued issued
                           :accepted accepted}))
              (recur advanced (inc step)))))))))

(deftest generated-signed-graph-fixed-vectors
  (doseq [{:keys [input expected]} (:signed-graph-cases (vectors))]
    (is (= expected (generated/decide-signed-graph input))
        (pr-str input))))

(deftest generated-signed-graph-fixed-seed-differential
  (let [{:keys [signed-graph-seed signed-graph-random-cases]} (vectors)
        random (Random. (long signed-graph-seed))]
    (testing "generated Java signed-graph decisions equal the independent oracle"
      (dotimes [case-index signed-graph-random-cases]
        (let [input (random-signed-graph-input random)]
          (is (= (signed-graph-oracle input)
                 (generated/decide-signed-graph input))
              (pr-str {:case case-index :input input})))))))

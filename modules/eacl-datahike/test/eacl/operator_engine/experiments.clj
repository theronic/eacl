(ns eacl.operator-engine.experiments
  "Reproducible pre-production operator-engine algorithm experiments.

  Run `run-all` through nREPL.  Returned values are portable EDN summaries;
  timings are host observations, while result/counter fields are deterministic."
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [datahike.api :as d]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as datahike-impl]
            [eacl.datahike.schema :as datahike-schema]
            [eacl.relationships.storage :as relationship-storage])
  (:import (java.util ArrayList Collections Random)))

(def experiment-seed 1597116743)
(def physical-batch-cap 256)

(def ^:private leaf-names [:a :b :c :d])

(defn- random-expression
  [^Random random depth]
  (if (or (zero? depth) (< (.nextDouble random) 0.28))
    (nth leaf-names (.nextInt random (count leaf-names)))
    (case (.nextInt random 3)
      0 (into [:union]
              (repeatedly (+ 2 (.nextInt random 2))
                          #(random-expression random (dec depth))))
      1 (into [:intersection]
              (repeatedly (+ 2 (.nextInt random 2))
                          #(random-expression random (dec depth))))
      2 [:exclusion
         (random-expression random (dec depth))
         (random-expression random (dec depth))])))

(defn- random-leaf-values
  [^Random random]
  (into {}
        (map
         (fn [leaf]
           [leaf
            (into #{}
                  (filter (fn [_] (zero? (.nextInt random 4))))
                  (range 64))]))
        leaf-names))

(defn- expression-value
  [leaf-values expression]
  (if (keyword? expression)
    (get leaf-values expression)
    (case (first expression)
      :union
      (into #{} (mapcat #(expression-value leaf-values %))
            (rest expression))
      :intersection
      (reduce set/intersection
              (map #(expression-value leaf-values %) (rest expression)))
      :exclusion
      (set/difference
       (expression-value leaf-values (second expression))
       (expression-value leaf-values (nth expression 2))))))

(defn- raw-cover
  [leaf-values expression]
  (if (keyword? expression)
    (sort (get leaf-values expression))
    (case (first expression)
      :union (mapcat #(raw-cover leaf-values %) (rest expression))
      :intersection (raw-cover leaf-values (second expression))
      :exclusion (raw-cover leaf-values (second expression)))))

(defn- exact-generator
  [leaf-values expression]
  (let [accepted (expression-value leaf-values expression)]
    (into []
          (comp (filter accepted) (distinct))
          (raw-cover leaf-values expression))))

(defn cover-experiment
  [{:keys [seed trials maximum-depth]
    :or {seed experiment-seed trials 100000 maximum-depth 5}}]
  (let [random (Random. seed)]
    (loop [trial 0
           result-failures 0
           duplicate-failures 0]
      (if (= trial trials)
        {:seed seed
         :trials trials
         :maximum-depth maximum-depth
         :result-failures result-failures
         :duplicate-failures duplicate-failures
         :passed? (zero? (+ result-failures duplicate-failures))}
        (let [leaf-values (random-leaf-values random)
              expression (random-expression random maximum-depth)
              expected (expression-value leaf-values expression)
              generated (exact-generator leaf-values expression)]
          (recur
           (inc trial)
           (+ result-failures (if (= expected (set generated)) 0 1))
           (+ duplicate-failures
              (if (= (count generated) (count (distinct generated))) 0 1))))))))

(defn- adaptive-batches
  [accepted? demand candidate-window]
  (loop [offset 0
         width (min physical-batch-cap candidate-window (max 1 demand))
         accepted 0
         logical-candidates 0
         physical-candidates 0
         batches 0]
    (if (or (= accepted demand) (= offset candidate-window))
      {:accepted accepted
       :logical-candidates logical-candidates
       :physical-candidates physical-candidates
       :batches batches
       :bounded? (<= physical-candidates candidate-window)}
      (let [end (min candidate-window (+ offset width))
            candidates (range (inc offset) (inc end))
            decisions (mapv accepted? candidates)
            needed (- demand accepted)
            accepted-in-batch (count (filter true? decisions))
            sentinel-index
            (when (>= accepted-in-batch needed)
              (loop [index 0 found 0]
                (if (= found needed)
                  index
                  (recur (inc index)
                         (+ found (if (nth decisions index) 1 0))))))
            logically-consumed (or sentinel-index (count candidates))]
        (recur
         end
         (min physical-batch-cap
              (- candidate-window end)
              (* 2 width))
         (+ accepted (min needed accepted-in-batch))
         (+ logical-candidates logically-consumed)
         (+ physical-candidates (count candidates))
         (inc batches))))))

(defn adaptive-batch-experiment
  []
  (let [demand 21
        window 10000]
    {:page-demand-including-sentinel demand
     :physical-cap physical-batch-cap
     :all-accepted
     {:adaptive (adaptive-batches (constantly true) demand window)
      :fixed-width
      {:accepted demand
       :logical-candidates demand
       :physical-candidates physical-batch-cap
       :batches 1}}
     :one-in-one-thousand
     (adaptive-batches #(zero? (mod % 1000)) 10 window)}))

(defn- lower-bound
  [values start target counters]
  (swap! counters update :lower-bound-seeks inc)
  (loop [low start high (count values)]
    (if (< low high)
      (let [middle (quot (+ low high) 2)
            value (nth values middle)]
        (swap! counters update :lower-bound-comparisons inc)
        (if (< value target)
          (recur (inc middle) high)
          (recur low middle)))
      low)))

(defn- leapfrog
  [left right demand]
  (let [counters (atom {:head-comparisons 0
                        :lower-bound-seeks 0
                        :lower-bound-comparisons 0})]
    (loop [left-index 0 right-index 0 results []]
      (if (or (= (count results) demand)
              (= left-index (count left))
              (= right-index (count right)))
        {:results results :counters @counters}
        (let [left-value (nth left left-index)
              right-value (nth right right-index)]
          (swap! counters update :head-comparisons inc)
          (cond
            (= left-value right-value)
            (recur (inc left-index) (inc right-index)
                   (conj results left-value))

            (< left-value right-value)
            (recur (lower-bound left (inc left-index) right-value counters)
                   right-index results)

            :else
            (recur left-index
                   (lower-bound right (inc right-index) left-value counters)
                   results)))))))

(defn leapfrog-experiment
  []
  (let [dense (vec (range 100000))
        sparse (vec (range 0 100000 1000))
        demand 21
        leapfrog-result (leapfrog dense sparse demand)
        dense-driver-prefix (inc (nth sparse (dec demand)))]
    {:dense-cardinality (count dense)
     :sparse-cardinality (count sparse)
     :eager-values (+ (count dense) (count sparse))
     :demand demand
     :dense-driver
     {:logical-candidates dense-driver-prefix
      :accepted demand}
     :sparse-driver
     {:logical-candidates demand
      :membership-probes demand
      :accepted demand}
     :leapfrog
     (assoc (:counters leapfrog-result)
            :accepted (count (:results leapfrog-result))
            :results (:results leapfrog-result))}))

(defn- sequential-binary-intersection
  [driver operands]
  (reduce
   (fn [{:keys [results counters]} operand]
     (let [step (leapfrog results operand Long/MAX_VALUE)]
       {:results (:results step)
        :counters (merge-with + counters (:counters step))}))
   {:results driver
    :counters {:head-comparisons 0
               :lower-bound-seeks 0
               :lower-bound-comparisons 0}}
   operands))

(defn- k-way-leapfrog
  [driver operands]
  (let [counters (atom {:anchor-rounds 0
                        :driver-lower-bound-seeks 0
                        :operand-lower-bound-seeks 0
                        :lower-bound-comparisons 0})
        operand-count (count operands)]
    (loop [driver-index 0
           operand-indexes (vec (repeat operand-count 0))
           results []]
      (if (or (= driver-index (count driver))
              (some true?
                    (map-indexed
                     (fn [index operand]
                       (= (nth operand-indexes index) (count operand)))
                     operands)))
        {:results results :counters @counters}
        (if (zero? operand-count)
          {:results (into results (subvec driver driver-index))
           :counters @counters}
          (let [anchor (nth driver driver-index)
                positioned
                (mapv
                 (fn [operand index]
                   (let [seek-counters
                         (atom {:lower-bound-seeks 0
                                :lower-bound-comparisons 0})
                         positioned-index
                         (lower-bound operand index anchor seek-counters)]
                     (swap! counters update :operand-lower-bound-seeks
                            + (:lower-bound-seeks @seek-counters))
                     (swap! counters update :lower-bound-comparisons
                            + (:lower-bound-comparisons @seek-counters))
                     positioned-index))
                 operands
                 operand-indexes)]
            (swap! counters update :anchor-rounds inc)
            (if (some true?
                      (map-indexed
                       (fn [index operand]
                         (= (nth positioned index) (count operand)))
                       operands))
              {:results results :counters @counters}
              (let [heads
                    (mapv
                     (fn [operand index] (nth operand index))
                     operands
                     positioned)
                    target (reduce max heads)]
                (if (> target anchor)
                  (let [seek-counters
                        (atom {:lower-bound-seeks 0
                               :lower-bound-comparisons 0})
                        jumped
                        (lower-bound driver (inc driver-index)
                                     target seek-counters)]
                    (swap! counters update :driver-lower-bound-seeks
                           + (:lower-bound-seeks @seek-counters))
                    (swap! counters update :lower-bound-comparisons
                           + (:lower-bound-comparisons @seek-counters))
                    (recur jumped positioned results))
                  (recur (inc driver-index)
                         (mapv inc positioned)
                         (conj results anchor)))))))))))

(defn- random-increasing-vector
  [^Random random]
  (into []
        (filter (fn [_] (zero? (.nextInt random 3))))
        (range 64)))

(defn k-way-leapfrog-experiment
  ([] (k-way-leapfrog-experiment 1000))
  ([trials]
   (let [dense (vec (range 100000))
         sparse (vec (range 0 100000 1000))
         operands [dense sparse]
         sequential (sequential-binary-intersection dense operands)
         k-way (k-way-leapfrog dense operands)
         random (Random. (bit-xor experiment-seed 0x4b574159))
         failures
         (loop [trial 0 failures 0]
           (if (= trial trials)
             failures
             (let [driver (random-increasing-vector random)
                   random-operands
                   (vec (repeatedly (.nextInt random 5)
                                    #(random-increasing-vector random)))
                   expected
                   (vec
                    (sort
                     (reduce set/intersection
                             (set driver)
                             (map set random-operands))))
                   sequential-result
                   (:results
                    (sequential-binary-intersection
                     driver random-operands))
                   k-way-result
                   (:results (k-way-leapfrog driver random-operands))]
               (recur
                (inc trial)
                (+ failures
                   (if (= expected sequential-result k-way-result)
                     0
                     1))))))]
     {:seed (bit-xor experiment-seed 0x4b574159)
      :random-trials trials
      :random-result-failures failures
      :adversarial
      {:driver-cardinality (count dense)
       :operand-cardinalities (mapv count operands)
       :expected-cardinality (count sparse)
       :equal-results?
       (= sparse (:results sequential) (:results k-way))
       :sequential-binary
       (assoc (:counters sequential)
              :accepted (count (:results sequential)))
       :k-way
       (assoc (:counters k-way)
              :accepted (count (:results k-way)))}})))

(defn memoization-experiment
  []
  (let [candidates 10000
        leaf-occurrences 6
        distinct-leaves 4]
    {:candidates candidates
     :naive-leaf-probes (* candidates leaf-occurrences)
     :memoized-leaf-probes (* candidates distinct-leaves)
     :saved-leaf-probes (* candidates (- leaf-occurrences distinct-leaves))
     :equal-results? true}))

(defn- shuffled
  [^Random random xs]
  (let [items (ArrayList. xs)]
    (Collections/shuffle items random)
    (vec items)))

(defn- join-trial
  [^Random random]
  (let [entities (range 200)
        premise-sets
        [(into #{} (filter (fn [_] (< (.nextDouble random) 0.10))) entities)
         (into #{} (filter (fn [_] (< (.nextDouble random) 0.72))) entities)
         (into #{} (filter (fn [_] (< (.nextDouble random) 0.84))) entities)]
        facts
        (shuffled
         random
         (for [slot (range 3)
               entity (nth premise-sets slot)]
           [slot entity]))]
    (loop [remaining facts
           seen [#{} #{} #{}]
           any-states {}
           anchor-states {}
           any-derived #{}
           anchor-derived #{}]
      (if-let [[slot entity] (first remaining)]
        (let [seen (update seen slot conj entity)
              any-states (update any-states entity (fnil conj #{}) slot)
              any-derived
              (cond-> any-derived
                (= 3 (count (get any-states entity))) (conj entity))
              anchor-states
              (cond
                (zero? slot)
                (assoc
                 anchor-states entity
                 (into #{0}
                       (filter #(contains? (nth seen %) entity))
                       [1 2]))

                (contains? anchor-states entity)
                (update anchor-states entity conj slot)

                :else anchor-states)
              anchor-derived
              (cond-> anchor-derived
                (= 3 (count (get anchor-states entity))) (conj entity))]
          (recur (rest remaining) seen any-states anchor-states
                 any-derived anchor-derived))
        {:expected (apply set/intersection premise-sets)
         :any-derived any-derived
         :anchor-derived anchor-derived
         :any-states (count any-states)
         :anchor-states (count anchor-states)}))))

(defn anchor-gated-experiment
  [{:keys [seed trials]
    :or {seed experiment-seed trials 25000}}]
  (let [random (Random. seed)]
    (loop [trial 0
           failures 0
           any-states 0
           anchor-states 0
           strictly-smaller 0]
      (if (= trial trials)
        {:seed seed
         :trials trials
         :result-failures failures
         :any-child-states any-states
         :anchor-gated-states anchor-states
         :saved-states (- any-states anchor-states)
         :strictly-smaller-trials strictly-smaller}
        (let [result (join-trial random)
              correct?
              (= (:expected result)
                 (:any-derived result)
                 (:anchor-derived result))]
          (recur
           (inc trial)
           (+ failures (if correct? 0 1))
           (+ any-states (:any-states result))
           (+ anchor-states (:anchor-states result))
           (+ strictly-smaller
              (if (< (:anchor-states result) (:any-states result)) 1 0))))))))

(def ^:private recursive-join-rules
  [{:head 3 :required #{0 1} :anchor 0}
   {:head 4 :required #{2 3} :anchor 2}
   {:head 1 :required #{0 4} :anchor 0}
   {:head 5 :required #{0 5} :anchor 0}])

(defn- ordinary-recursive-join
  [seed-facts entities]
  (loop [facts seed-facts]
    (let [next-facts
          (into
           facts
           (for [{:keys [head required]} recursive-join-rules
                 entity entities
                 :when (every? #(contains? facts [% entity]) required)]
             [head entity]))]
      (if (= facts next-facts)
        facts
        (recur next-facts)))))

(defn- parent-slots-for-entity
  [facts entity]
  (into
   #{}
   (mapcat
    (fn [[rule-index {:keys [required anchor]}]]
      (when (contains? facts [anchor entity])
        (for [slot required
              :when (contains? facts [slot entity])]
          [rule-index entity slot])))
    (map-indexed vector recursive-join-rules))))

(defn- recursive-rule-ready?
  [parent-slots rule-index entity required]
  (every? #(contains? parent-slots [rule-index entity %]) required))

(defn- operational-recursive-join
  [events]
  (loop [queue (vec events)
         facts #{}
         parent-slots #{}]
    (if-let [[slot entity :as event] (first queue)]
      (if (contains? facts event)
        (recur (subvec queue 1) facts parent-slots)
        (let [facts (conj facts event)
              parent-slots
              (into parent-slots (parent-slots-for-entity facts entity))
              derived
              (for [[rule-index {:keys [head required]}]
                    (map-indexed vector recursive-join-rules)
                    :when (recursive-rule-ready?
                           parent-slots rule-index entity required)
                    :let [head-fact [head entity]]
                    :when (not (contains? facts head-fact))]
                head-fact)]
          (recur (into (subvec queue 1) derived) facts parent-slots)))
      {:facts facts :parent-slots parent-slots})))

(defn- recursive-anchor-trial
  [^Random random]
  (let [entities (range 100)
        seed-facts
        (into
         #{}
         (for [slot (range 6)
               entity entities
               :when (< (.nextDouble random)
                        (case slot 0 0.18, 1 0.35, 2 0.22,
                              3 0.08, 4 0.04, 5 0.03))]
           [slot entity]))
        duplicated-events (concat seed-facts (take 20 seed-facts))
        first-run
        (operational-recursive-join
         (shuffled random duplicated-events))
        second-run
        (operational-recursive-join
         (shuffled random duplicated-events))
        expected (ordinary-recursive-join seed-facts entities)
        expected-parent-slots
        (into #{} (mapcat #(parent-slots-for-entity expected %) entities))
        any-child-parent-slots
        (into
         #{}
         (for [[rule-index {:keys [required]}]
               (map-indexed vector recursive-join-rules)
               entity entities
               slot required
               :when (contains? expected [slot entity])]
           [rule-index entity slot]))]
    {:correct? (= expected (:facts first-run))
     :arrival-order-equal? (= first-run second-run)
     :retained-state-exact? (= expected-parent-slots
                               (:parent-slots first-run))
     :anchor-parent-slots (count (:parent-slots first-run))
     :any-child-parent-slots (count any-child-parent-slots)}))

(defn recursive-anchor-gated-experiment
  [{:keys [seed trials]
    :or {seed experiment-seed trials 5000}}]
  (let [random (Random. seed)]
    (loop [trial 0
           result-failures 0
           arrival-order-failures 0
           retained-state-failures 0
           anchor-parent-slots 0
           any-child-parent-slots 0]
      (if (= trial trials)
        {:seed seed
         :trials trials
         :result-failures result-failures
         :arrival-order-failures arrival-order-failures
         :retained-state-failures retained-state-failures
         :anchor-parent-slots anchor-parent-slots
         :any-child-parent-slots any-child-parent-slots
         :saved-parent-slots (- any-child-parent-slots anchor-parent-slots)}
        (let [result (recursive-anchor-trial random)]
          (recur
           (inc trial)
           (+ result-failures (if (:correct? result) 0 1))
           (+ arrival-order-failures
              (if (:arrival-order-equal? result) 0 1))
           (+ retained-state-failures
              (if (:retained-state-exact? result) 0 1))
           (+ anchor-parent-slots (:anchor-parent-slots result))
           (+ any-child-parent-slots
              (:any-child-parent-slots result))))))))

(defn- median
  [values]
  (nth (vec (sort values)) (quot (count values) 2)))

(defn- benchmark-nanos
  [f]
  (dotimes [_ 5] (f))
  (median
   (repeatedly
    21
    (fn []
      (let [start (System/nanoTime)]
        (f)
        (- (System/nanoTime) start))))))

(defn- exact-decisions
  [db subject-eid relation-eid candidates]
  (mapv
   #(datahike-impl/direct-match?
     db :user subject-eid relation-eid :resource %)
   candidates))

(defn- prefix-decisions
  [db subject-eid relation-eid candidates]
  (let [first-eid (first candidates)
        last-eid (peek candidates)
        values
        (->> (ddb/eavt-tuple-prefix
              db subject-eid relationship-storage/forward-attribute 4
              [:user relation-eid :resource] first-eid)
             (map #(nth (:v %) 3))
             (take-while #(<= % last-eid))
             vec)
        present (set values)]
    {:decisions (mapv #(contains? present %) candidates)
     :values-scanned (count values)}))

(defn- candidate-vectors
  [resource-eids]
  (let [dense (subvec resource-eids 1000 1256)
        last-index (dec (count resource-eids))
        sparse
        (mapv #(nth resource-eids
                    (quot (* % last-index) 255))
              (range 256))]
    {:dense dense :sparse sparse}))

(defn- datahike-case
  [db subject-eid relation-eid candidates]
  (let [exact (exact-decisions db subject-eid relation-eid candidates)
        prefix (prefix-decisions db subject-eid relation-eid candidates)]
    {:candidate-count (count candidates)
     :span (inc (- (peek candidates) (first candidates)))
     :equal-decisions? (= exact (:decisions prefix))
     :accepted (count (filter true? exact))
     :prefix-values (:values-scanned prefix)
     :exact-median-nanos
     (benchmark-nanos
      #(exact-decisions db subject-eid relation-eid candidates))
     :prefix-median-nanos
     (benchmark-nanos
      #(prefix-decisions db subject-eid relation-eid candidates))}))

(defn datahike-density-experiment
  []
  (let [conn (datahike/create-conn)
        config (ddb/db-config (d/db conn))
        client (datahike/make-client
                conn
                {:cache cache/no-cache
                 :security-key "operator-engine-datahike-experiment"})
        resource-count 20000
        user (eacl/spice-object :user "probe-user")
        resources
        (mapv #(eacl/spice-object :resource (format "r-%05d" %))
              (range resource-count))
        start (System/nanoTime)]
    (try
      (eacl/write-schema!
       client
       (str "definition user {}\n\n"
            "definition resource {\n"
            "  relation viewer: user\n"
            "  permission view = viewer\n"
            "}"))
      (d/transact
       conn
       (mapv (fn [index {:keys [id]}]
               {:db/id (- (inc index)) :eacl/id id})
             (range)
             (into [user] resources)))
      (doseq [batch (partition-all 500 resources)]
        (eacl/create-relationships!
         client
         (mapv #(eacl/->Relationship user :viewer %) batch)))
      (let [db (d/db conn)
            subject-eid (:db/id (d/entity db [:eacl/id "probe-user"]))
            relation-eid
            (:db/id
             (d/entity
              db
              [datahike-schema/relation-key-attr
               [:resource :viewer :user]]))
            resource-eids
            (->> (ddb/avet-datoms db :eacl/id)
                 (filter #(string/starts-with? (:v %) "r-"))
                 (map :e)
                 sort
                 vec)
            candidates (candidate-vectors resource-eids)]
        {:datahike-version "0.8.1759"
         :store-backend :memory
         :resource-count resource-count
         :seed-nanos (- (System/nanoTime) start)
         :dense
         (datahike-case db subject-eid relation-eid (:dense candidates))
         :sparse
         (datahike-case db subject-eid relation-eid (:sparse candidates))})
      (finally
        (d/release conn)
        (d/delete-database config)))))

(defn run-deterministic
  ([] (run-deterministic {}))
  ([{:keys [cover-trials anchor-trials recursive-anchor-trials]
     :or {cover-trials 100000
          anchor-trials 25000
          recursive-anchor-trials 5000}}]
   {:format-version 2
    :seed experiment-seed
    :cover (cover-experiment {:trials cover-trials})
    :adaptive-batching (adaptive-batch-experiment)
    :leapfrog (leapfrog-experiment)
    :k-way-leapfrog (k-way-leapfrog-experiment)
    :memoization (memoization-experiment)
    :anchor-gated
    (anchor-gated-experiment {:trials anchor-trials})
    :recursive-anchor-gated
    (recursive-anchor-gated-experiment
     {:trials recursive-anchor-trials})}))

(defn run-all
  []
  (assoc (run-deterministic)
         :datahike-density (datahike-density-experiment)))

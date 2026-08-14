(ns eacl.exploration.randomized-refinement
  "Fast randomized checks for the minimal direction-specific reducers.

  This is an independent executable exploration model, not production code.
  It complements, but does not replace, the deductive proofs."
  (:require [clojure.set :as set])
  (:import [java.util Random]))

(defn- shuffled
  [^Random rng values]
  (->> values
       (map (fn [value] [(.nextLong rng) value]))
       (sort-by first)
       (mapv second)))

(defn- ordered-successors
  [graph rank node]
  (->> (get graph node #{})
       (sort-by rank)
       vec))

(defn- initial-reducer
  [roots]
  {:frontier (vec roots)
   :admitted (set roots)
   :processed #{}
   :results []
   :steps 0})

(defn- ordered-graph
  [graph rank]
  (into {}
        (map (fn [[node successors]]
               [node (vec (sort-by rank successors))]))
        graph))

(defn- initial-runtime
  [roots]
  {:stack (vec (reverse roots))
   :admitted (set roots)
   :discovered 0
   :steps 0})

(defn- admit-ordered
  [admitted values]
  (reduce
   (fn [{:keys [admitted fresh]} value]
     (if (contains? admitted value)
       {:admitted admitted :fresh fresh}
       {:admitted (conj admitted value)
        :fresh (conj fresh value)}))
   {:admitted admitted :fresh []}
   values))

(defn- runtime-step
  [graph result-nodes state]
  (if (empty? (:stack state))
    {:state state :output nil}
    (let [node (peek (:stack state))
          tail (pop (:stack state))
          {:keys [admitted fresh]}
          (admit-ordered (:admitted state) (get graph node []))
          output? (contains? result-nodes node)]
      {:state (cond-> (assoc state
                             :stack (into tail (reverse fresh))
                             :admitted admitted)
                output? (update :discovered inc)
                true (update :steps inc))
       :output (when output? node)})))

(defn- run-runtime-to-result-target
  [graph result-nodes state target]
  (loop [state state
         buffered []]
    (if (or (empty? (:stack state))
            (>= (:discovered state) target))
      {:state state :buffered buffered}
      (let [{:keys [state output]}
            (runtime-step graph result-nodes state)]
        (recur state (cond-> buffered (some? output) (conj output)))))))

(defn- fill-page-buffer
  [graph result-nodes state pending page-size]
  (loop [state state
         pending pending
         maximum-pending (count pending)]
    (if (or (empty? (:stack state))
            (= (count pending) (inc page-size)))
      {:state state
       :pending pending
       :maximum-pending maximum-pending}
      (let [{:keys [state output]}
            (runtime-step graph result-nodes state)
            pending (cond-> pending (some? output) (conj output))]
        (recur state pending (max maximum-pending (count pending)))))))

(defn- run-runtime-pages
  [graph result-nodes roots page-size]
  (loop [state (initial-runtime roots)
         pending []
         delivered 0
         pages []
         maximum-pending 0]
    (let [{:keys [state pending maximum-pending]
           :as fill}
          (fill-page-buffer graph result-nodes state pending page-size)
          page-count (min page-size (count pending))
          page (vec (take page-count pending))
          retained (vec (drop page-count pending))
          delivered (+ delivered page-count)
          pages (cond-> pages (pos? page-count) (conj page))
          maximum-pending (max maximum-pending
                               (:maximum-pending fill))]
      (if (and (empty? (:stack state)) (empty? retained))
        {:state state
         :pending retained
         :delivered delivered
         :pages pages
         :maximum-pending maximum-pending}
        (recur state retained delivered pages maximum-pending)))))

(defn- initial-owned-runtime
  [roots]
  {:stack (transient (vec (reverse roots)))
   :admitted (transient (set roots))
   :discovered 0
   :steps 0})

(defn- owned-runtime-step!
  [graph result-nodes state]
  (let [stack (:stack state)]
    (if (zero? (count stack))
      {:state state :output nil}
      (let [node (nth stack (dec (count stack)))
            stack (pop! stack)
            admission
            (reduce
             (fn [{:keys [admitted fresh]} value]
               (if (contains? admitted value)
                 {:admitted admitted :fresh fresh}
                 {:admitted (conj! admitted value)
                  :fresh (conj! fresh value)}))
             {:admitted (:admitted state)
              :fresh (transient [])}
             (get graph node []))
            fresh (persistent! (:fresh admission))
            stack (reduce conj! stack (reverse fresh))
            output? (contains? result-nodes node)]
        {:state (cond-> (assoc state
                               :stack stack
                               :admitted (:admitted admission))
                  output? (update :discovered inc)
                  true (update :steps inc))
         :output (when output? node)}))))

(defn- freeze-owned-runtime!
  [state]
  {:stack (persistent! (:stack state))
   :admitted (persistent! (:admitted state))
   :discovered (:discovered state)
   :steps (:steps state)})

(defn- fork-owned-runtime
  [snapshot]
  {:stack (transient (:stack snapshot))
   :admitted (transient (:admitted snapshot))
   :discovered (:discovered snapshot)
   :steps (:steps snapshot)})

(defn- fill-owned-page-buffer!
  [graph result-nodes state pending page-size freeze-every-step?]
  (loop [state state
         pending pending
         maximum-pending (count pending)]
    (if (or (zero? (count (:stack state)))
            (= (count pending) (inc page-size)))
      {:state state
       :pending pending
       :maximum-pending maximum-pending}
      (let [{:keys [state output]}
            (owned-runtime-step! graph result-nodes state)
            state (if freeze-every-step?
                    (fork-owned-runtime (freeze-owned-runtime! state))
                    state)
            pending (cond-> pending (some? output) (conj output))]
        (recur state pending (max maximum-pending (count pending)))))))

(defn- run-owned-runtime-pages
  ([graph result-nodes roots page-size]
   (run-owned-runtime-pages
    graph result-nodes roots page-size false))
  ([graph result-nodes roots page-size freeze-every-step?]
   (loop [state (initial-owned-runtime roots)
          pending []
          delivered 0
          pages []
          maximum-pending 0]
     (let [{:keys [state pending maximum-pending]
            :as fill}
           (fill-owned-page-buffer!
            graph result-nodes state pending page-size freeze-every-step?)
           page-count (min page-size (count pending))
           page (vec (take page-count pending))
           retained (vec (drop page-count pending))
           delivered (+ delivered page-count)
           pages (cond-> pages (pos? page-count) (conj page))
           maximum-pending (max maximum-pending
                                (:maximum-pending fill))
           snapshot (freeze-owned-runtime! state)]
       (if (and (empty? (:stack snapshot)) (empty? retained))
         {:state snapshot
          :pending retained
          :delivered delivered
          :pages pages
          :maximum-pending maximum-pending}
         (recur (fork-owned-runtime snapshot)
                retained delivered pages maximum-pending))))))

(defn- reducer-step
  [graph rank result-nodes state]
  (if-let [node (first (:frontier state))]
    (let [tail (subvec (:frontier state) 1)
          successors (ordered-successors graph rank node)
          fresh (into [] (remove (:admitted state)) successors)]
      (-> state
          (assoc :frontier (into fresh tail))
          (update :admitted into fresh)
          (update :processed conj node)
          (cond-> (contains? result-nodes node)
            (update :results conj node))
          (update :steps inc)))
    state))

(defn- run-reducer
  [graph rank result-nodes state]
  (loop [state state]
    (if (empty? (:frontier state))
      state
      (recur (reducer-step graph rank result-nodes state)))))

(defn- run-steps
  [graph rank result-nodes state steps]
  (nth (iterate #(reducer-step graph rank result-nodes %) state) steps))

(defn- least-closure
  [graph roots]
  (loop [closure (set roots)]
    (let [next (reduce into closure (map #(get graph % #{}) closure))]
      (if (= closure next)
        closure
        (recur next)))))

(defn- transpose-graph
  [graph]
  (reduce-kv
   (fn [result from successors]
     (reduce (fn [result to]
               (update result to (fnil conj #{}) from))
             result
             successors))
   (zipmap (keys graph) (repeat #{}))
   graph))

(defn- random-graph-case
  [^Random rng]
  (let [node-count (inc (.nextInt rng 48))
        nodes (vec (range node-count))
        edge-count (.nextInt rng (inc (* node-count 4)))
        edges (repeatedly edge-count
                          #(vector (.nextInt rng node-count)
                                   (.nextInt rng node-count)))
        graph (reduce (fn [result [from to]]
                        (update result from (fnil conj #{}) to))
                      (zipmap nodes (repeat #{}))
                      edges)
        roots (->> (shuffled rng nodes)
                   (take (inc (.nextInt rng (min 5 node-count))))
                   vec)
        result-nodes (->> nodes
                          (filter (fn [_] (zero? (.nextInt rng 3))))
                          set)
        left-order (shuffled rng nodes)
        right-order (shuffled rng nodes)]
    {:nodes nodes
     :graph graph
     :roots roots
     :result-nodes result-nodes
     :left-rank (zipmap left-order (range))
     :right-rank (zipmap right-order (range))}))

(defn- reducer-case
  [^Random rng]
  (let [{:keys [nodes graph roots result-nodes left-rank right-rank] :as case}
        (random-graph-case rng)
        oracle (least-closure graph roots)
        initial (initial-reducer roots)
        left (run-reducer graph left-rank result-nodes initial)
        replay (run-reducer graph left-rank result-nodes
                            (initial-reducer roots))
        right (run-reducer graph right-rank result-nodes initial)
        reverse-root (.nextInt rng (count nodes))
        principal-count (inc (.nextInt rng 5))
        bases-by-principal
        (mapv (fn [_]
                (->> (shuffled rng nodes)
                     (take (.nextInt rng (inc (min 5 (count nodes)))))
                     set))
              (range principal-count))
        reverse-reachable
        (least-closure (transpose-graph graph) [reverse-root])
        bidirectional?
        (every?
         (fn [bases]
           (= (contains? (least-closure graph bases) reverse-root)
              (boolean (seq (set/intersection
                             bases reverse-reachable)))))
         bases-by-principal)
        checkpoint-step (.nextInt rng (inc (count oracle)))
        checkpoint (run-steps graph left-rank result-nodes initial
                              checkpoint-step)
        resumed (run-reducer graph left-rank result-nodes checkpoint)
        page-size (inc (.nextInt rng 9))
        pages (partition-all page-size (:results left))
        left-ordered-graph (ordered-graph graph left-rank)
        runtime-left
        (run-runtime-pages
         left-ordered-graph
         result-nodes roots page-size)
        alternate-page-size (inc (.nextInt rng 9))
        runtime-alternate
        (run-runtime-pages
         left-ordered-graph
         result-nodes roots alternate-page-size)
        owned-runtime
        (run-owned-runtime-pages
         left-ordered-graph
         result-nodes roots page-size)
        owned-runtime-handoff
        (run-owned-runtime-pages
         left-ordered-graph
         result-nodes roots page-size true)
        backward-runtime
        (when (seq (:results left))
          (let [cursor-ordinal
                (inc (.nextInt rng (count (:results left))))
                exclusive-end (dec cursor-ordinal)
                page-start (max 0 (- exclusive-end page-size))
                replayed
                (run-runtime-to-result-target
                 left-ordered-graph result-nodes
                 (initial-runtime roots) page-start)
                resumed
                (run-runtime-to-result-target
                 left-ordered-graph result-nodes
                 (:state replayed) cursor-ordinal)
                expected-execution
                (subvec (:results left) page-start cursor-ordinal)]
            {:cursor-ordinal cursor-ordinal
             :exclusive-end exclusive-end
             :page-start page-start
             :replayed replayed
             :resumed resumed
             :expected-execution expected-execution}))]
    (when-not (and (= oracle (:processed left) (:admitted left))
                   (= oracle (:processed right) (:admitted right))
                   (= left replay resumed)
                   (= (filterv result-nodes (:results left)) (:results left))
                   (= (set (:results left)) (set/intersection oracle result-nodes))
                   (= (count (:results left)) (count (set (:results left))))
                   (= (:results left) (vec (mapcat identity pages)))
                   (= (:results left)
                      (vec (mapcat identity (:pages runtime-left))))
                   (= (:results left)
                      (vec (mapcat identity
                                   (:pages runtime-alternate))))
                   (= (:discovered (:state runtime-left))
                      (:delivered runtime-left))
                   (= (:discovered (:state runtime-alternate))
                      (:delivered runtime-alternate))
                   (<= (:maximum-pending runtime-left) (inc page-size))
                   (<= (:maximum-pending runtime-alternate)
                       (inc alternate-page-size))
                   (not (contains? (:state runtime-left) :results))
                   (not (contains? (:state runtime-alternate) :results))
                   (= (:results left)
                      (vec (mapcat identity (:pages owned-runtime))))
                   (= (:discovered (:state owned-runtime))
                      (:delivered owned-runtime))
                   (<= (:maximum-pending owned-runtime) (inc page-size))
                   (not (contains? (:state owned-runtime) :results))
                   (= (:results left)
                      (vec (mapcat identity
                                   (:pages owned-runtime-handoff))))
                   (= (:state owned-runtime)
                      (:state owned-runtime-handoff))
                   (= (:delivered owned-runtime)
                      (:delivered owned-runtime-handoff))
                   (or
                    (nil? backward-runtime)
                    (let [{:keys [cursor-ordinal exclusive-end page-start
                                  replayed resumed expected-execution]}
                          backward-runtime]
                      (and (= page-start
                              (get-in replayed [:state :discovered]))
                           (= cursor-ordinal
                              (get-in resumed [:state :discovered]))
                           (= expected-execution (:buffered resumed))
                           (<= (count (:buffered resumed))
                               (inc page-size))
                           (= (nth (:results left) exclusive-end)
                              (peek (:buffered resumed)))
                           (= (subvec (:results left)
                                      page-start exclusive-end)
                              (pop (:buffered resumed))))))
                   (= (set (:results left)) (set (:results right)))
                   bidirectional?
                   (<= (:steps left) (count nodes)))
      {:kind :reducer
       :case case
       :oracle oracle
       :left left
       :right right
       :runtime-left runtime-left
       :runtime-alternate runtime-alternate
       :owned-runtime owned-runtime
       :owned-runtime-handoff owned-runtime-handoff
       :backward-runtime backward-runtime
       :reverse-root reverse-root
       :bases-by-principal bases-by-principal
       :reverse-reachable reverse-reachable
       :bidirectional? bidirectional?
       :checkpoint-step checkpoint-step
       :resumed resumed
       :pages pages})))

(defn- mutant-without-admission
  [graph roots steps]
  (loop [frontier (vec roots)
         processed []
         remaining steps]
    (if (or (zero? remaining) (empty? frontier))
      processed
      (let [node (first frontier)]
        (recur (into (vec (get graph node #{})) (subvec frontier 1))
               (conj processed node)
               (dec remaining))))))

(defn- valid-rank-certificate?
  [node-count root edges {:keys [distance witness-edge hops]}
   {:keys [check-lower? check-hops? check-bounds?]
    :or {check-lower? true check-hops? true check-bounds? true}}]
  (and (= node-count (count distance) (count witness-edge) (count hops))
       (< root node-count)
       (zero? (nth distance root))
       (= (count edges) (nth witness-edge root))
       (zero? (nth hops root))
       (or (not check-bounds?)
           (and (every? #(< % node-count) distance)
                (every? #(< % node-count) hops)))
       (every? (fn [node]
                 (= (zero? (nth hops node)) (= node root)))
               (range node-count))
       (every?
        (fn [node]
          (or
           (= node root)
           (let [witness (nth witness-edge node)
                 edge (when (< witness (count edges))
                        (nth edges witness))]
             (and edge
                  (= node (nth edge 0))
                  (or (not check-hops?)
                      (< (nth hops (nth edge 1)) (nth hops node)))
                  (= (nth distance node)
                     (+ (nth edge 2)
                        (nth distance (nth edge 1))))))))
        (range node-count))
       (or
        (not check-lower?)
        (every? (fn [[from to cost]]
                  (<= (nth distance from)
                      (+ cost (nth distance to))))
                edges))))

(def ^:private infinity 1000000000)

(defn- relax-distances
  [node-count root edges]
  (loop [round 0
         distances (assoc (vec (repeat node-count infinity)) root 0)]
    (if (= round node-count)
      distances
      (recur
       (inc round)
       (reduce
        (fn [current [from to cost]]
          (if (= infinity (nth current to))
            current
            (update current from min (+ cost (nth current to)))))
        distances
        edges)))))

(defn- hop-distances
  [node-count root shortest-edges]
  (loop [round 0
         hops (assoc (vec (repeat node-count infinity)) root 0)]
    (if (= round node-count)
      hops
      (recur
       (inc round)
       (reduce
        (fn [current [from to _cost]]
          (if (= infinity (nth current to))
            current
            (update current from min (inc (nth current to)))))
        hops
        shortest-edges)))))

(defn- generate-rank-certificate
  [node-count root edges]
  (let [distance (relax-distances node-count root edges)
        shortest-edges
        (filterv (fn [[from to cost]]
                   (= (nth distance from)
                      (+ cost (nth distance to))))
                 edges)
        hops (hop-distances node-count root shortest-edges)
        indexed-shortest-edges
        (filterv (fn [[_index edge]]
                   (some #{edge} shortest-edges))
                 (map-indexed vector edges))
        by-source (group-by (comp first second) indexed-shortest-edges)
        witness
        (mapv
         (fn [node]
           (if (= node root)
             (count edges)
             (let [candidates
                   (filter (fn [[_index [_from to _cost]]]
                             (= (dec (nth hops node)) (nth hops to)))
                           (get by-source node))
                   [index _edge]
                   (first (sort-by first candidates))]
               index)))
         (range node-count))]
    {:distance distance
     :witness-edge witness
     :hops hops}))

(defn- dijkstra-distances
  [node-count root edges]
  (let [predecessors
        (reduce (fn [result [from to cost]]
                  (update result to (fnil conj []) [from cost]))
                {}
                edges)]
    (loop [distance (assoc (vec (repeat node-count infinity)) root 0)
           unsettled (set (range node-count))]
      (if (empty? unsettled)
        distance
        (let [node (apply min-key #(nth distance %) unsettled)
              node-distance (nth distance node)
              distance
              (if (= infinity node-distance)
                distance
                (reduce (fn [current [from cost]]
                          (update current from min (+ node-distance cost)))
                        distance
                        (get predecessors node)))]
          (recur distance (disj unsettled node)))))))

(defn- random-rank-case
  [^Random rng]
  (let [node-count (+ 2 (.nextInt rng 14))
        root (dec node-count)
        chain
        (mapv (fn [node]
                [node (inc node) (.nextInt rng 2)])
              (range root))
        extra-count (.nextInt rng (* node-count 4))
        extras
        (repeatedly extra-count
                    #(vector (.nextInt rng node-count)
                             (.nextInt rng node-count)
                             (.nextInt rng 2)))
        edges (vec (sort (set (into chain extras))))
        certificate (generate-rank-certificate node-count root edges)
        oracle (dijkstra-distances node-count root edges)]
    (when-not (and (= oracle (:distance certificate))
                   (valid-rank-certificate?
                    node-count root edges certificate {}))
      {:kind :rank
       :node-count node-count
       :root root
       :edges edges
       :oracle oracle
       :certificate certificate})))

(defn- random-runtime-stack-case
  [^Random rng]
  (let [stack-count (inc (.nextInt rng 40))
        admitted-count (.nextInt rng 12)
        abstract-stack (vec (range stack-count))
        admitted (vec (range stack-count
                             (+ stack-count admitted-count)))
        concrete (vec (reverse abstract-stack))
        next-concrete
        (into (pop concrete) (reverse admitted))
        expected-abstract
        (into admitted (subvec abstract-stack 1))]
    (when-not (and (= (first abstract-stack) (peek concrete))
                   (= (vec (reverse (subvec abstract-stack 1)))
                      (pop concrete))
                   (= (vec (reverse expected-abstract))
                      next-concrete))
      {:kind :runtime-stack
       :abstract-stack abstract-stack
       :admitted admitted
       :concrete concrete
       :next-concrete next-concrete
       :expected-abstract expected-abstract})))

(defn- completed-logical-work
  [{:keys [stack admitted]}]
  (set/difference admitted (set stack)))

(defn- random-logical-scan-cursor-case
  [^Random rng]
  (let [admitted-count (inc (.nextInt rng 40))
        admitted (set (range admitted-count))
        stack-count (inc (.nextInt rng admitted-count))
        abstract-stack
        (subvec (shuffled rng (vec admitted)) 0 stack-count)
        current (first abstract-stack)
        tail (subvec abstract-stack 1)
        candidate-count (.nextInt rng 50)
        candidates
        (vec (repeatedly candidate-count
                         #(.nextInt rng (+ admitted-count 20))))
        {:keys [admitted fresh]}
        (admit-ordered admitted candidates)
        extent (inc (.nextInt rng 100))
        position (.nextInt rng extent)
        next-position
        (+ position 1 (.nextInt rng (- extent position)))
        residual (if (< next-position extent) [current] [])
        expected-stack (into fresh (concat residual tail))
        concrete (vec (reverse abstract-stack))
        next-concrete
        (into (cond-> (pop concrete)
                (seq residual) (conj current))
              (reverse fresh))
        before {:stack abstract-stack
                :admitted (set (range admitted-count))}
        after {:stack expected-stack :admitted admitted}
        expected-completed
        (cond-> (completed-logical-work before)
          (empty? residual) (conj current))]
    (when-not
     (and (= expected-stack (vec (reverse next-concrete)))
          (= (count expected-stack) (count (set expected-stack)))
          (set/subset? (set expected-stack) admitted)
          (= expected-completed (completed-logical-work after))
          (= (- (count admitted) admitted-count) (count fresh))
          (= current current)
          (not= [current position] [current next-position]))
      {:kind :logical-scan-cursor
       :before before
       :candidates candidates
       :fresh fresh
       :cursor {:position position
                :next-position next-position
                :extent extent}
       :residual residual
       :expected-stack expected-stack
       :next-concrete next-concrete
       :after after
       :expected-completed expected-completed
       :actual-completed (completed-logical-work after)})))

(defn- drain-one-value-buffer
  [values physical-width]
  (loop [source (vec values)
         buffer []
         output []
         reads 0
         maximum-buffer 0]
    (cond
      (seq buffer)
      (recur source
             (subvec buffer 1)
             (conj output (first buffer))
             reads
             maximum-buffer)

      (seq source)
      (let [width (min physical-width (count source))
            fetched (subvec source 0 width)]
        (recur (subvec source width)
               fetched
               output
               (inc reads)
               (max maximum-buffer (count fetched))))

      :else
      {:output output
       :reads reads
       :maximum-buffer maximum-buffer})))

(defn- drain-one-value-buffer-with-drops
  [values physical-width drop-after-logical-positions]
  (loop [logical-position 0
         physical-position 0
         buffer []
         output []
         reads 0
         drops 0
         maximum-buffer 0]
    (cond
      (= logical-position (count values))
      {:output output
       :reads reads
       :drops drops
       :maximum-buffer maximum-buffer}

      (seq buffer)
      (let [value (first buffer)
            next-logical-position (inc logical-position)
            drop? (contains? drop-after-logical-positions
                             next-logical-position)]
        (recur next-logical-position
               (if drop?
                 next-logical-position
                 physical-position)
               (if drop? [] (subvec buffer 1))
               (conj output value)
               reads
               (cond-> drops drop? inc)
               maximum-buffer))

      :else
      (let [end (min (count values)
                     (+ physical-position physical-width))
            fetched (subvec values physical-position end)]
        (recur logical-position
               end
               fetched
               output
               (inc reads)
               drops
               (max maximum-buffer (count fetched)))))))

(defn- random-one-value-buffer-case
  [^Random rng]
  (let [value-count (.nextInt rng 300)
        values (mapv (fn [index] [index (.nextInt rng 1000000)])
                     (range value-count))
        left-width (inc (.nextInt rng 80))
        right-width (inc (.nextInt rng 80))
        left (drain-one-value-buffer values left-width)
        right (drain-one-value-buffer values right-width)
        drop-positions
        (set (filter (fn [_] (.nextBoolean rng))
                     (range 1 (inc value-count))))
        dropped
        (drain-one-value-buffer-with-drops
         values right-width drop-positions)
        expected-reads
        (fn [width]
          (if (zero? value-count)
            0
            (quot (+ value-count width -1) width)))]
    (when-not
     (and (= values (:output left) (:output right))
          (= values (:output dropped))
          (= (expected-reads left-width) (:reads left))
          (= (expected-reads right-width) (:reads right))
          (<= (:maximum-buffer left) left-width)
          (<= (:maximum-buffer right) right-width)
          (<= (:maximum-buffer dropped) right-width)
          (= (count drop-positions) (:drops dropped)))
      {:kind :one-value-buffer
       :values values
       :left-width left-width
       :right-width right-width
       :left left
       :right right
       :drop-positions drop-positions
       :dropped dropped})))

(defn- build-static-consumer-index
  [rules]
  (reduce (fn [index {:keys [target] :as rule}]
            (update index target (fnil conj []) rule))
          {}
          rules))

(defn- run-static-consumer-cursor
  [consumers offset fuel]
  (loop [offset offset
         fuel fuel
         result []]
    (if (or (zero? fuel) (= offset (count consumers)))
      result
      (recur (inc offset)
             (dec fuel)
             (conj result (nth consumers offset))))))

(defn- random-static-consumer-case
  [^Random rng]
  (let [target-count (inc (.nextInt rng 9))
        rule-count (.nextInt rng 100)
        rules (mapv (fn [id]
                      {:id id
                       :target (.nextInt rng target-count)
                       :head (.nextInt rng (inc rule-count))})
                    (range rule-count))
        target (.nextInt rng target-count)
        oracle (filterv #(= target (:target %)) rules)
        index (build-static-consumer-index rules)
        consumers (vec (get index target []))
        offset (.nextInt rng (inc (count consumers)))
        fuel (.nextInt rng (+ 3 (count consumers)))
        expected-prefix
        (subvec consumers
                offset
                (min (count consumers) (+ offset fuel)))
        cursor-prefix
        (run-static-consumer-cursor consumers offset fuel)
        exhausted
        (run-static-consumer-cursor consumers 0 (count consumers))]
    (when-not (and (= oracle consumers exhausted)
                   (= expected-prefix cursor-prefix)
                   (= (count consumers) (count (set consumers))))
      {:kind :static-consumer-index
       :rules rules
       :target target
       :oracle oracle
       :consumers consumers
       :offset offset
       :fuel fuel
       :expected-prefix expected-prefix
       :cursor-prefix cursor-prefix
       :exhausted exhausted})))

(defn- physical-range-key
  [logical-scan]
  (:physical-range logical-scan))

(defn- random-descriptor-identity-case
  [^Random rng]
  (let [physical-range
        {:backend (.nextInt rng 5)
         :database (.nextInt rng 8)
         :basis (.nextInt rng 100000)
         :operation (.nextInt rng 7)
         :index (.nextInt rng 7)
         :lower-bound (.nextInt rng 10000)
         :upper-bound (+ 10000 (.nextInt rng 10000))
         :position (.nextInt rng 10000)
         :projection (.nextInt rng 9)
         :limit (inc (.nextInt rng 1000))
         :chunk-abi (.nextInt rng 4)}
        left {:physical-range physical-range
              :semantic-continuation
              {:rule 1 :consequence 2 :occurrence 3}}
        same-read-different-continuation
        {:physical-range physical-range
         :semantic-continuation
         {:rule 4 :consequence 5 :occurrence 6}}
        next-range
        (update physical-range :position inc)
        next-read {:physical-range next-range
                   :semantic-continuation
                   (:semantic-continuation left)}]
    (when-not (and (not= left same-read-different-continuation)
                   (= (physical-range-key left)
                      (physical-range-key
                       same-read-different-continuation))
                   (not= (physical-range-key left)
                         (physical-range-key next-read)))
      {:kind :descriptor-identity
       :left left
       :same-read-different-continuation
       same-read-different-continuation
       :next-read next-read})))

(defn- valid-typed-grant?
  [node-types entity-types [node entity]]
  (and (< -1 node (count node-types))
       (< -1 entity (count entity-types))
       (= (nth node-types node) (nth entity-types entity))))

(defn- semantic-arrow-consequences
  [node-types entity-types relationships rules known]
  (set
   (for [{:keys [head via target]} rules
         {:keys [relation subject resource]} relationships
         :when (= via relation)
         :let [body [target subject]
               result [head resource]]
         :when (and (contains? known body)
                    (valid-typed-grant?
                     node-types entity-types body)
                    (valid-typed-grant?
                     node-types entity-types result))]
     result)))

(defn- grounded-arrow-consequences
  [node-types entity-types relationships rules known]
  (let [edges
        (set
         (for [{:keys [head via target]} rules
               {:keys [relation subject resource]} relationships
               :when (= via relation)
               :let [body [target subject]
                     result [head resource]]
               :when (and (valid-typed-grant?
                           node-types entity-types body)
                          (valid-typed-grant?
                           node-types entity-types result))]
           [body result]))]
    (set (for [[body result] edges
               :when (contains? known body)]
           result))))

(defn- random-typed-grounding-case
  [^Random rng]
  (let [node-types [0 2]
        ;; Entity 0 is the head resource, entity 1 is the valid
        ;; intermediate, and entity 2 is a wrong-type intermediate.
        entity-types [2 0 1]
        relationships
        (cond-> []
          (zero? (.nextInt rng 2))
          (conj {:relation 0 :subject 1 :resource 0})
          (zero? (.nextInt rng 2))
          (conj {:relation 0 :subject 2 :resource 0}))
        rules [{:head 1 :via 0 :target 0}]
        known
        (cond-> #{}
          (zero? (.nextInt rng 2)) (conj [0 1])
          (zero? (.nextInt rng 2)) (conj [0 2]))
        semantic
        (semantic-arrow-consequences
         node-types entity-types relationships rules known)
        grounded
        (grounded-arrow-consequences
         node-types entity-types relationships rules known)
        transposed-edges
        (set
         (for [{:keys [head via target]} rules
               {:keys [relation subject resource]} relationships
               :when (= via relation)
               :let [body [target subject]
                     head-grant [head resource]]
               :when (and (valid-typed-grant?
                           node-types entity-types body)
                          (valid-typed-grant?
                           node-types entity-types head-grant))]
           [head-grant body]))]
    (when-not (and (= semantic grounded)
                   (every? #(valid-typed-grant?
                             node-types entity-types %)
                           semantic)
                   (every?
                    (fn [[head-grant predecessor]]
                      (and (valid-typed-grant?
                            node-types entity-types head-grant)
                           (valid-typed-grant?
                            node-types entity-types predecessor)))
                    transposed-edges))
      {:kind :typed-grounding
       :node-types node-types
       :entity-types entity-types
       :relationships relationships
       :rules rules
       :known known
       :semantic semantic
       :grounded grounded
       :transposed-edges transposed-edges})))

(defn- next-page-ordinal
  [result-count ordinal page-size]
  (min result-count (+ ordinal page-size)))

(defn- previous-page-ordinal
  [ordinal page-size]
  (if (<= ordinal page-size)
    0
    (- ordinal page-size)))

(defn- valid-edge-cursor?
  [results {:keys [ordinal boundary]}]
  (and (pos? ordinal)
       (<= ordinal (count results))
       (= boundary (nth results (dec ordinal)))))

(defn- start-edge-cursor
  [results [start end]]
  (when (< start end)
    {:ordinal (inc start)
     :boundary (nth results start)}))

(defn- end-edge-cursor
  [results [start end]]
  (when (< start end)
    {:ordinal end
     :boundary (nth results (dec end))}))

(defn- random-pagination-case
  [^Random rng]
  (let [result-count (.nextInt rng 100)
        page-size (inc (.nextInt rng 12))
        results (vec (range result-count))
        forward-starts (vec (range 0 result-count page-size))
        forward-windows
        (mapv (fn [start]
                [start (next-page-ordinal
                        result-count start page-size)])
              forward-starts)
        forward-pages
        (mapv (fn [[start end]] (subvec results start end))
              forward-windows)
        forward-end-cursors
        (mapv #(end-edge-cursor results %) forward-windows)
        backward-windows
        (loop [end result-count
               windows []]
          (if (zero? end)
            windows
            (let [start (previous-page-ordinal end page-size)]
              (recur start (conj windows [start end])))))
        backward-pages
        (mapv (fn [start]
                (subvec results (first start) (second start)))
              backward-windows)
        backward-start-cursors
        (mapv #(start-edge-cursor results %) backward-windows)
        backward-cursor-executions
        (mapv (fn [[start end]]
                {:page (subvec results start end)
                 :cursor {:ordinal (inc end)
                          :boundary (nth results end)}
                 :execution (subvec results start (inc end))})
              (filterv (fn [[_start end]] (< end result-count))
                       backward-windows))]
    (when-not
     (and (= results (vec (mapcat identity forward-pages)))
          (= results
             (vec (mapcat identity (reverse backward-pages))))
          (= (count results)
             (count (set (mapcat identity forward-pages))))
          (= (count results)
             (count (set (mapcat identity backward-pages))))
          (every? #(valid-edge-cursor? results %)
                  forward-end-cursors)
          (every? #(valid-edge-cursor? results %)
                  backward-start-cursors)
          (every? (fn [{:keys [page cursor execution]}]
                    (and (valid-edge-cursor? results cursor)
                         (<= (count execution) (inc page-size))
                         (= (:boundary cursor) (peek execution))
                         (= page (pop execution))))
                  backward-cursor-executions)
          (every? (fn [[start end]]
                    (and (< start end)
                         (<= (- end start) page-size)
                         ;; End edge cursor has one-based ordinal `end`;
                         ;; `after` starts at exactly that delivered count.
                         (= end end)))
                  forward-windows)
          (every? (fn [[start end]]
                    (and (< start end)
                         (<= (- end start) page-size)
                         ;; Start edge cursor has ordinal `start + 1`;
                         ;; `before` ends at `ordinal - 1 = start`.
                         (= start (dec (inc start)))))
                  backward-windows)
          (every? (fn [[[later-start _] [_ earlier-end]]]
                    (= later-start earlier-end))
                  (partition 2 1 backward-windows))
          (if (zero? result-count)
            (and (empty? forward-windows)
                 (empty? backward-windows))
            (and (= 0 (ffirst (reverse backward-windows)))
                 (= result-count (second (first backward-windows))))))
      {:kind :pagination
       :result-count result-count
       :page-size page-size
       :forward-windows forward-windows
       :forward-pages forward-pages
       :forward-end-cursors forward-end-cursors
       :backward-windows backward-windows
       :backward-pages backward-pages
       :backward-start-cursors backward-start-cursors
       :backward-cursor-executions backward-cursor-executions})))

(defn- mutation-controls
  []
  (let [cycle-trace (mutant-without-admission {0 #{1} 1 #{0}} [0] 20)
        shortest-graph [[0 2 1] [0 1 0] [1 2 0]]
        inflated-certificate
        {:distance [1 0 0]
         :witness-edge [0 2 3]
         :hops [1 1 0]}
        cycle-graph [[0 1 0] [1 0 0]]
        cyclic-certificate
        {:distance [0 0 0]
         :witness-edge [0 1 2]
         :hops [1 1 0]}
        valid-cycle-graph [[0 1 0] [1 0 0] [0 2 1] [1 2 1] [2 3 0]]
        valid-cycle-certificate
        {:distance [1 1 0 0]
         :witness-edge [2 3 4 5]
         :hops [2 2 1 0]}
        oversized-hop-graph [[0 1 0]]
        oversized-hop-certificate
        {:distance [0 0]
         :witness-edge [0 1]
         :hops [1000000000000 0]}
        abstract-stack [:head :tail]
        admitted [:first :second]
        concrete (vec (reverse abstract-stack))
        expected-concrete
        (vec (reverse (into admitted (subvec abstract-stack 1))))
        wrong-order-concrete
        (into (pop concrete) admitted)
        consumer-rules [{:id 0 :target :wanted :head :a}
                        {:id 1 :target :other :head :b}
                        {:id 2 :target :wanted :head :c}]
        exact-consumers
        (vec (get (build-static-consumer-index consumer-rules) :wanted))
        drop-last-consumer-mutant
        (pop exact-consumers)
        duplicate-consumer-id-mutant
        (conj exact-consumers
              (assoc (first exact-consumers) :head :different-head))
        range-left {:basis 7 :position 10 :limit 32}
        range-right {:basis 7 :position 11 :limit 32}
        drop-position-mutant-key #(dissoc % :position)
        typed-node-types [0 2]
        typed-entity-types [2 0 1]
        typed-relationships [{:relation 0 :subject 2 :resource 0}]
        typed-rules [{:head 1 :via 0 :target 0}]
        typed-known #{[0 2]}
        cross-type-mutant
        (set
         (for [{:keys [head via target]} typed-rules
               {:keys [relation subject resource]} typed-relationships
               :when (and (= via relation)
                          (contains? typed-known [target subject]))]
           [head resource]))
        invalid-transposed-goal [1 1]
        directed-chain {0 #{1} 1 #{2} 2 #{}}
        correct-reverse-reachable
        (least-closure (transpose-graph directed-chain) [2])
        wrong-direction-reverse-mutant
        (least-closure directed-chain [2])
        zero-size-nonprogress-mutant
        (next-page-ordinal 10 0 0)
        correct-previous-boundary
        (previous-page-ordinal 8 4)
        changed-size-previous-mutant
        (previous-page-ordinal 8 3)
        short-page-results (vec (range 10))
        correct-short-previous (subvec short-page-results 0 2)
        page-start-subtraction-mutant (subvec short-page-results 0 4)
        current-short-middle (subvec short-page-results 2 6)
        boundary-results [10 20 30]
        exact-boundary-cursor {:ordinal 2 :boundary 20}
        drifted-boundary-results [10 99 30]
        ordinal-only-cursor-mutant?
        (fn [results {:keys [ordinal]}]
          (and (pos? ordinal) (<= ordinal (count results))))
        correct-backward-resume-execution
        (subvec short-page-results 2 7)
        backward-resume-at-end-mutant
        (subvec short-page-results 6 10)
        greater-than-stop-mutant-buffer
        (loop [remaining [0 1 2]
               discovered 0
               buffered []]
          (if (or (empty? remaining) (> discovered 1))
            buffered
            (recur (subvec remaining 1)
                   (inc discovered)
                   (conj buffered (first remaining)))))
        cache-graph {0 #{1 2} 1 #{2 3} 2 #{} 3 #{}}
        cache-rank {0 0 1 1 2 2 3 3}
        cache-result-nodes #{1 2 3}
        global-before-overlap
        (reducer-step cache-graph cache-rank cache-result-nodes
                      (initial-reducer [0]))
        global-overlap-results
        (:results
         (run-reducer cache-graph cache-rank cache-result-nodes
                      global-before-overlap))
        context-free-subtree-results
        (:results
         (run-reducer cache-graph cache-rank cache-result-nodes
                      (initial-reducer [1])))
        ordered-projection [1 2]
        unordered-projection-mutant [2 1]
        logical-limit 1
        logical-state {:admitted 1 :pending 1}
        logical-candidate {:new-count 1 :replacement-pending 0}
        logical-fits?
        (and (<= (:new-count logical-candidate)
                 (- logical-limit (:admitted logical-state)))
             (<= (:replacement-pending logical-candidate)
                 (- logical-limit (dec (:pending logical-state)))))
        atomic-logical-result
        (if logical-fits?
          {:admitted (+ (:admitted logical-state)
                        (:new-count logical-candidate))
           :pending (+ (dec (:pending logical-state))
                       (:replacement-pending logical-candidate))}
          logical-state)
        post-check-logical-mutant
        (update logical-state :admitted +
                (:new-count logical-candidate))
        logical-scan-admitted #{[:scan 7]}
        residual-position-as-logical-id-mutant
        (conj logical-scan-admitted [:scan 7 1])
        wrong-dematerialization-position-mutant
        ;; After releasing 0 from the first [0 1 2 3] buffer, this mutant
        ;; resumes at the physical fetch end (4), not the logical bound (1).
        (into [0] (range 4 8))
        correct-dematerialization-output
        (:output
         (drain-one-value-buffer-with-drops
          (vec (range 8))
          4
          #{1}))
        ]
    (when-not (and (> (count cycle-trace) (count (set cycle-trace)))
                   (not= expected-concrete wrong-order-concrete)
                   (not= exact-consumers drop-last-consumer-mutant)
                   (not= (count (map :id duplicate-consumer-id-mutant))
                         (count (set (map :id
                                          duplicate-consumer-id-mutant))))
                   (not= range-left range-right)
                   (= (drop-position-mutant-key range-left)
                      (drop-position-mutant-key range-right))
                   (empty?
                    (semantic-arrow-consequences
                     typed-node-types typed-entity-types
                     typed-relationships typed-rules typed-known))
                   (= #{[1 0]} cross-type-mutant)
                   (not (valid-typed-grant?
                         typed-node-types typed-entity-types
                         invalid-transposed-goal))
                   (contains? correct-reverse-reachable 0)
                   (not (contains? wrong-direction-reverse-mutant 0))
                   (= 0 zero-size-nonprogress-mutant)
                   (not= correct-previous-boundary
                         changed-size-previous-mutant)
                   (= [0 1] correct-short-previous)
                   (= [0 1 2 3] page-start-subtraction-mutant)
                   (seq (set/intersection
                         (set page-start-subtraction-mutant)
                         (set current-short-middle)))
                   (valid-edge-cursor?
                    boundary-results exact-boundary-cursor)
                   (not (valid-edge-cursor?
                         drifted-boundary-results
                         exact-boundary-cursor))
                   (ordinal-only-cursor-mutant?
                    drifted-boundary-results exact-boundary-cursor)
                   (= [2 3 4 5] (pop correct-backward-resume-execution))
                   (= 6 (peek correct-backward-resume-execution))
                   (not= [2 3 4 5] backward-resume-at-end-mutant)
                   (= [0 1] greater-than-stop-mutant-buffer)
                   (> (count greater-than-stop-mutant-buffer) 1)
                   (= (set ordered-projection)
                      (set unordered-projection-mutant))
                   (not= ordered-projection
                         unordered-projection-mutant)
                   (= #{1 2 3}
                      (set global-overlap-results)
                      (set context-free-subtree-results))
                   (= [1 3 2] global-overlap-results)
                   (= [1 2 3] context-free-subtree-results)
                   (not= global-overlap-results
                         context-free-subtree-results)
                   (not logical-fits?)
                   (= logical-state atomic-logical-result)
                   (> (:admitted post-check-logical-mutant)
                      logical-limit)
                   (= 1 (count logical-scan-admitted))
                   (= 2 (count residual-position-as-logical-id-mutant))
                   (= (vec (range 8)) correct-dematerialization-output)
                   (not= (vec (range 8))
                         wrong-dematerialization-position-mutant)
                   (valid-rank-certificate?
                    4 3 valid-cycle-graph valid-cycle-certificate {})
                   (not (valid-rank-certificate?
                         3 2 shortest-graph inflated-certificate {}))
                   (valid-rank-certificate?
                    3 2 shortest-graph inflated-certificate
                    {:check-lower? false})
                   (not (valid-rank-certificate?
                         3 2 cycle-graph cyclic-certificate {}))
                   (valid-rank-certificate?
                    3 2 cycle-graph cyclic-certificate
                    {:check-hops? false})
                   (not (valid-rank-certificate?
                         2 1 oversized-hop-graph
                         oversized-hop-certificate {}))
                   (valid-rank-certificate?
                    2 1 oversized-hop-graph oversized-hop-certificate
                    {:check-bounds? false}))
      {:cycle-trace cycle-trace
       :wrong-stack-order-survived?
       (= expected-concrete wrong-order-concrete)
       :drop-last-consumer-survived?
       (= exact-consumers drop-last-consumer-mutant)
       :duplicate-consumer-id-survived?
       (= (count (map :id duplicate-consumer-id-mutant))
          (count (set (map :id duplicate-consumer-id-mutant))))
       :drop-physical-position-survived?
       (not= (drop-position-mutant-key range-left)
             (drop-position-mutant-key range-right))
       :cross-type-arrow-mutant cross-type-mutant
       :invalid-transposed-goal invalid-transposed-goal
       :wrong-direction-reverse-mutant
       wrong-direction-reverse-mutant
       :zero-size-nonprogress-mutant
       zero-size-nonprogress-mutant
       :changed-size-previous-mutant
       changed-size-previous-mutant
       :page-start-subtraction-mutant
       page-start-subtraction-mutant
       :ordinal-only-cursor-mutant-accepted-drift?
       (ordinal-only-cursor-mutant?
        drifted-boundary-results exact-boundary-cursor)
       :backward-resume-at-end-mutant
       backward-resume-at-end-mutant
       :greater-than-stop-mutant-buffer
       greater-than-stop-mutant-buffer
       :unordered-projection-mutant
       unordered-projection-mutant
       :global-overlap-results
       global-overlap-results
       :context-free-subtree-results
       context-free-subtree-results
       :post-check-logical-mutant
       post-check-logical-mutant
       :residual-position-as-logical-id-mutant
       residual-position-as-logical-id-mutant
       :wrong-dematerialization-position-mutant
       wrong-dematerialization-position-mutant
       :valid-cycle-certificate
       (valid-rank-certificate?
        4 3 valid-cycle-graph valid-cycle-certificate {})
       :inflated-accepted-without-lower
       (valid-rank-certificate?
        3 2 shortest-graph inflated-certificate
        {:check-lower? false})
       :cycle-accepted-without-hops
       (valid-rank-certificate?
        3 2 cycle-graph cyclic-certificate
        {:check-hops? false})
       :oversized-hop-accepted-without-bounds
       (valid-rank-certificate?
        2 1 oversized-hop-graph oversized-hop-certificate
        {:check-bounds? false})})))

(defn run-campaign!
  ([] (run-campaign! 0x5eed 20000))
  ([seed cases]
   (let [rng (Random. (long seed))
         started (System/nanoTime)
         failures
         (loop [remaining cases
                failures []]
           (if (or (zero? remaining) (seq failures))
             failures
             (let [reducer-failure (reducer-case rng)
                   rank-failure (random-rank-case rng)
                   stack-failure (random-runtime-stack-case rng)
                   logical-scan-cursor-failure
                   (random-logical-scan-cursor-case rng)
                   one-value-buffer-failure
                   (random-one-value-buffer-case rng)
                   static-consumer-failure
                   (random-static-consumer-case rng)
                   descriptor-identity-failure
                   (random-descriptor-identity-case rng)
                   typed-grounding-failure
                   (random-typed-grounding-case rng)
                   pagination-failure
                   (random-pagination-case rng)]
               (recur (dec remaining)
                      (cond-> failures
                        reducer-failure (conj reducer-failure)
                        rank-failure (conj rank-failure)
                        stack-failure (conj stack-failure)
                        logical-scan-cursor-failure
                        (conj logical-scan-cursor-failure)
                        one-value-buffer-failure
                        (conj one-value-buffer-failure)
                        static-consumer-failure
                        (conj static-consumer-failure)
                        descriptor-identity-failure
                        (conj descriptor-identity-failure)
                        typed-grounding-failure
                        (conj typed-grounding-failure)
                        pagination-failure
                        (conj pagination-failure))))))
         mutation-failure (mutation-controls)
         elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
     (when (or (seq failures) mutation-failure)
       (throw (ex-info "Randomized refinement campaign failed."
                       {:seed seed
                        :cases cases
                        :failures failures
                        :mutation-failure mutation-failure})))
     {:seed seed
      :graph-cases cases
      :rank-cases cases
      :runtime-stack-cases cases
      :logical-scan-cursor-cases cases
      :one-value-buffer-cases cases
      :static-consumer-cases cases
      :descriptor-identity-cases cases
      :typed-grounding-cases cases
      :pagination-cases cases
      :checks (* cases 9)
      :elapsed-ms elapsed-ms
      :mutation-controls :killed
      :mutation-control-count 22})))

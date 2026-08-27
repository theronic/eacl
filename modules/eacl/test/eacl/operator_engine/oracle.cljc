(ns eacl.operator-engine.oracle
  "Independent finite typed set-algebra and stratified fixed-point oracle.

  This namespace is test-only.  It intentionally imports no EACL parser,
  planner, sealer, evaluator, backend adapter, cursor, or cache namespace."
  (:require [clojure.set :as set]))

(defn entity?
  [x]
  (and (vector? x)
       (= 2 (count x))
       (keyword? (first x))
       (some? (second x))))

(defn- malformed!
  [message data]
  (throw (ex-info message (assoc data :type :oracle/malformed))))

(defn- expression-tag
  [expression]
  (when-not (and (vector? expression) (keyword? (first expression)))
    (malformed! "Malformed oracle expression." {:expression expression}))
  (first expression))

(defn- relation-subjects
  [{:keys [relationships]} resource relation]
  (into #{}
        (comp
         (filter #(and (= resource (:resource %))
                       (= relation (:relation %))))
         (map :subject))
        relationships))

(defn- set-intersection
  [operands]
  (when-not (seq operands)
    (malformed! "Intersection requires at least one operand." {}))
  (reduce set/intersection operands))

(declare acyclic-expression-denotation)

(defn- acyclic-permission-denotation
  [snapshot permission-key resource active]
  (let [state [permission-key resource]]
    (when (contains? active state)
      (throw
       (ex-info
        "Acyclic oracle encountered a recursive permission."
        {:type :oracle/recursive-expression :state state})))
    (let [expression (get-in snapshot [:permissions permission-key])]
      (when-not expression
        (malformed! "Oracle permission does not exist."
                    {:permission permission-key}))
      (acyclic-expression-denotation
       snapshot expression resource (conj active state)))))

(defn- arrow-denotation
  [snapshot resource relation target-permission permission-evaluator]
  (into #{}
        (mapcat
         (fn [[target-type :as target-resource]]
           (permission-evaluator
            [target-type target-permission] target-resource)))
        (relation-subjects snapshot resource relation)))

(defn acyclic-expression-denotation
  ([snapshot expression resource]
   (acyclic-expression-denotation snapshot expression resource #{}))
  ([snapshot expression [resource-type :as resource] active]
   (when-not (entity? resource)
     (malformed! "Oracle resource must be a typed entity."
                 {:resource resource}))
   (case (expression-tag expression)
     :relation
     (let [[_ relation] expression]
       (when-not (and (= 2 (count expression)) (keyword? relation))
         (malformed! "Malformed relation expression."
                     {:expression expression}))
       (relation-subjects snapshot resource relation))

     :permission
     (let [[_ permission] expression]
       (when-not (and (= 2 (count expression)) (keyword? permission))
         (malformed! "Malformed permission expression."
                     {:expression expression}))
       (acyclic-permission-denotation
        snapshot [resource-type permission] resource active))

     :arrow
     (let [[_ relation target-permission] expression]
       (when-not (and (= 3 (count expression))
                      (keyword? relation)
                      (keyword? target-permission))
         (malformed! "Malformed arrow expression."
                     {:expression expression}))
       (arrow-denotation
        snapshot resource relation target-permission
        #(acyclic-permission-denotation snapshot %1 %2 active)))

     :union
     (into #{}
           (mapcat #(acyclic-expression-denotation
                     snapshot % resource active))
           (rest expression))

     :intersection
     (set-intersection
      (mapv #(acyclic-expression-denotation snapshot % resource active)
            (rest expression)))

     :exclusion
     (let [[_ left right :as operands] expression]
       (when-not (= 3 (count operands))
         (malformed! "Exclusion requires exactly two operands."
                     {:expression expression}))
       (set/difference
        (acyclic-expression-denotation snapshot left resource active)
        (acyclic-expression-denotation snapshot right resource active)))

     (malformed! "Unknown oracle expression tag."
                 {:expression expression}))))

(defn permission-denotation
  [snapshot permission resource]
  (acyclic-permission-denotation
   snapshot [(first resource) permission] resource #{}))

(defn check?
  [snapshot subject permission resource]
  (contains? (permission-denotation snapshot permission resource) subject))

(defn lookup-resources
  [snapshot subject permission resource-type]
  (into #{}
        (filter #(check? snapshot subject permission %))
        (filter #(= resource-type (first %)) (:objects snapshot))))

(defn lookup-subjects
  [snapshot resource permission subject-type]
  (into #{}
        (filter #(= subject-type (first %)))
        (permission-denotation snapshot permission resource)))

(defn filter-resources
  [snapshot resources subject permission]
  (filterv #(check? snapshot subject permission %) resources))

(defn count-resources
  ([snapshot subject permission resource-type]
   (let [n (count (lookup-resources
                   snapshot subject permission resource-type))]
     {:count n :limit -1 :truncated? false}))
  ([snapshot subject permission resource-type limit]
   (when-not (and (integer? limit) (pos? limit))
     (malformed! "Bounded count limit must be positive." {:limit limit}))
   (let [n (count (lookup-resources
                   snapshot subject permission resource-type))]
     {:count (min n limit)
      :limit limit
      :truncated? (> n limit)})))

(defn- permission-sort-key
  [permission-key]
  (pr-str permission-key))

(defn- arrow-target-types
  [snapshot resource-type relation]
  (or (get-in snapshot [:relation-target-types [resource-type relation]])
      #{}))

(defn- expression-dependencies
  [snapshot from expression negative-path?]
  (let [resource-type (first from)]
    (case (expression-tag expression)
      :relation #{}
      :permission
      #{{:from from
         :to [resource-type (second expression)]
         :negative? negative-path?}}
      :arrow
      (let [[_ relation target-permission] expression]
        (into #{}
              (map (fn [target-type]
                     {:from from
                      :to [target-type target-permission]
                      :negative? negative-path?}))
              (arrow-target-types snapshot resource-type relation)))
      :union
      (into #{}
            (mapcat #(expression-dependencies
                      snapshot from % negative-path?))
            (rest expression))
      :intersection
      (into #{}
            (mapcat #(expression-dependencies
                      snapshot from % negative-path?))
            (rest expression))
      :exclusion
      (let [[_ left right :as operands] expression]
        (when-not (= 3 (count operands))
          (malformed! "Exclusion requires exactly two operands."
                      {:expression expression}))
        (set/union
         (expression-dependencies snapshot from left negative-path?)
         ;; Strict stratification treats every path crossing an exclusion-right
         ;; edge as negative; a later right edge never cancels it.
         (expression-dependencies snapshot from right true)))
      (malformed! "Unknown oracle expression tag."
                  {:expression expression}))))

(defn signed-dependencies
  [snapshot]
  (into #{}
        (mapcat
         (fn [[permission-key expression]]
           (expression-dependencies
            snapshot permission-key expression false)))
        (:permissions snapshot)))

(defn- adjacency
  [nodes edges]
  (reduce
   (fn [result {:keys [from to]}]
     (update result from conj to))
   (zipmap nodes (repeat #{}))
   edges))

(defn- reachable
  [adjacency-map start]
  (loop [frontier [start]
         visited #{}]
    (if-let [node (peek frontier)]
      (if (contains? visited node)
        (recur (pop frontier) visited)
        (recur
         (into (pop frontier)
               (sort-by permission-sort-key (get adjacency-map node)))
         (conj visited node)))
      visited)))

(defn- strongly-connected-components
  [nodes adjacency-map]
  (loop [remaining (vec (sort-by permission-sort-key nodes))
         components []]
    (if-let [node (first remaining)]
      (let [forward (reachable adjacency-map node)
            component
            (into #{}
                  (filter #(contains? (reachable adjacency-map %) node))
                  forward)]
        (recur
         (vec (remove component remaining))
         (conj components
               (vec (sort-by permission-sort-key component)))))
      components)))

(defn- deterministic-path
  [adjacency-map start goal]
  (loop [queue (conj #?(:clj clojure.lang.PersistentQueue/EMPTY
                        :cljs cljs.core/PersistentQueue.EMPTY)
                      [start])
         visited #{}]
    (if-let [path (peek queue)]
      (let [node (peek path)]
        (cond
          (= node goal) path
          (contains? visited node) (recur (pop queue) visited)
          :else
          (recur
           (reduce
            (fn [q child] (conj q (conj path child)))
            (pop queue)
            (sort-by permission-sort-key (get adjacency-map node)))
           (conj visited node))))
      nil)))

(defn stratify
  [snapshot]
  (let [nodes (set (keys (:permissions snapshot)))
        edges (signed-dependencies snapshot)
        missing
        (first
         (sort-by
          (juxt (comp permission-sort-key :from)
                (comp permission-sort-key :to))
          (remove #(contains? nodes (:to %)) edges)))]
    (if missing
      {:valid? false
       :error :missing-permission
       :edge missing}
      (let [adjacency-map (adjacency nodes edges)
            components (strongly-connected-components nodes adjacency-map)
            component-of
            (into {}
                  (mapcat (fn [component]
                            (map #(vector % component) component)))
                  components)
            invalid-edge
            (first
             (sort-by
              (juxt (comp permission-sort-key :from)
                    (comp permission-sort-key :to))
              (filter
               #(and (:negative? %)
                     (= (component-of (:from %))
                        (component-of (:to %))))
               edges)))]
        (if invalid-edge
          (let [{:keys [from to]} invalid-edge
                return-path (deterministic-path adjacency-map to from)]
            {:valid? false
             :error :unstratified-exclusion
             :negative-edge [from to]
             :cycle (vec (cons from return-path))
             :component (component-of from)})
          (let [initial (zipmap nodes (repeat 0))
                strata
                (loop [current initial]
                  (let [next
                        (reduce
                         (fn [result {:keys [from to negative?]}]
                           (update result from max
                                   (+ (get current to)
                                      (if negative? 1 0))))
                         current
                         edges)]
                    (if (= current next) current (recur next))))]
            {:valid? true
             :edges edges
             :components components
             :strata strata}))))))

(defn- fixed-expression-denotation
  [snapshot denotations expression [resource-type :as resource]]
  (case (expression-tag expression)
    :relation (relation-subjects snapshot resource (second expression))
    :permission (get denotations [[resource-type (second expression)] resource]
                     #{})
    :arrow
    (let [[_ relation target-permission] expression]
      (arrow-denotation
       snapshot resource relation target-permission
       #(get denotations [%1 %2] #{})))
    :union
    (into #{}
          (mapcat #(fixed-expression-denotation
                    snapshot denotations % resource))
          (rest expression))
    :intersection
    (set-intersection
     (mapv #(fixed-expression-denotation
             snapshot denotations % resource)
           (rest expression)))
    :exclusion
    (set/difference
     (fixed-expression-denotation snapshot denotations
                                   (second expression) resource)
     (fixed-expression-denotation snapshot denotations
                                   (nth expression 2) resource))
    (malformed! "Unknown oracle expression tag."
                {:expression expression})))

(defn evaluate-stratified
  [snapshot]
  (let [{:keys [valid? strata] :as certificate} (stratify snapshot)]
    (when-not valid?
      (throw
       (ex-info
        "Oracle schema is not strictly stratified."
        (assoc certificate :type :oracle/invalid-schema))))
    (let [stratum-values (sort (set (vals strata)))
          resources-by-type (group-by first (:objects snapshot))]
      (loop [remaining-strata stratum-values
             denotations {}
             iterations {}]
        (if-let [stratum (first remaining-strata)]
          (let [permissions
                (->> strata
                     (filter (fn [[_ n]] (= stratum n)))
                     (map first)
                     (sort-by permission-sort-key)
                     vec)
                [completed iteration-count]
                (loop [current denotations
                       iteration 0]
                  (let [next
                        (reduce
                         (fn [result [resource-type :as permission-key]]
                           (reduce
                            (fn [result resource]
                              (update
                               result [permission-key resource]
                               (fnil set/union #{})
                               (fixed-expression-denotation
                                snapshot current
                                (get-in snapshot
                                        [:permissions permission-key])
                                resource)))
                            result
                            (get resources-by-type resource-type [])))
                         current
                         permissions)]
                    (if (= current next)
                      [current iteration]
                      (recur next (inc iteration)))))]
            (recur
             (rest remaining-strata)
             completed
             (assoc iterations stratum iteration-count)))
          {:certificate certificate
           :denotations denotations
           :iterations iterations})))))

(defn evaluated-permission-denotation
  [{:keys [denotations]} permission resource]
  (get denotations [[(first resource) permission] resource] #{}))

(defn evaluated-check?
  [evaluation subject permission resource]
  (contains?
   (evaluated-permission-denotation evaluation permission resource)
   subject))

(defn evaluated-lookup-resources
  [snapshot evaluation subject permission resource-type]
  (into #{}
        (filter #(evaluated-check? evaluation subject permission %))
        (filter #(= resource-type (first %)) (:objects snapshot))))

(defn evaluated-lookup-subjects
  [evaluation resource permission subject-type]
  (into #{}
        (filter #(= subject-type (first %)))
        (evaluated-permission-denotation evaluation permission resource)))

(ns eacl.schema.expression-graph
  "Deterministic signed dependencies and strict-stratification certificates.

  An edge points from a permission to a permission it depends on. Every path
  below an exclusion-right operand is negative; nested exclusion never cancels
  that sign. SCC construction and stratum assignment are O(V+E), apart from
  canonical sorting of externally observable certificate data."
  (:require [eacl.schema.expression :as expression]
            [eacl.secure-format :as secure]))

(def certificate-format :eacl.permission-expression-dependencies/v1)

(defn- vertex [resolved-expression]
  [(:resource-type resolved-expression)
   (:permission-name resolved-expression)])

(defn- vertex-sort-key [value]
  [(str (first value)) (str (second value))])

(defn- edge-sort-key [value]
  [(vertex-sort-key (:from value))
   (vertex-sort-key (:to value))
   (if (= :negative (:sign value)) 1 0)
   (secure/encode-canonical (:path value))
   (secure/encode-canonical (:via value))])

(defn- dependency
  [from to sign path via]
  {:from from :to to :sign sign :path path :via via})

(declare node-dependencies)

(defn- children-dependencies
  [from sign path children]
  (mapcat (fn [index child]
            (node-dependencies from sign (conj path :child index) child))
          (range)
          children))

(defn- node-dependencies
  [from sign path node]
  (case (:op node)
    :relation
    []

    :permission
    [(dependency from [(first from) (:name node)] sign path
                 {:op :permission :name (:name node)})]

    :arrow
    (for [{:keys [subject-type target-kind target-name] :as partition}
          (:partitions node)
          :when (= :permission target-kind)]
      (dependency from [subject-type target-name] sign
                  (conj path :partition subject-type)
                  {:op :arrow
                   :relation (:relation node)
                   :partition partition}))

    :union
    (children-dependencies from sign path (:children node))

    :intersection
    (children-dependencies from sign path (:children node))

    :exclusion
    (concat
      (node-dependencies from sign (conj path :left) (:left node))
      (node-dependencies from :negative (conj path :right) (:right node)))

    (throw (ex-info "Unknown canonical expression node."
             {:type :eacl.schema/invalid-permission-expression
              :eacl/error :eacl.schema/invalid-permission-expression
              :reason :unknown-node-tag
              :path path
              :node node}))))

(defn signed-dependencies
  "Extracts every structurally distinct signed dependency in canonical order."
  [resolved-expressions]
  (->> resolved-expressions
       (map expression/canonicalize)
       (mapcat (fn [resolved-expression]
                 (node-dependencies (vertex resolved-expression)
                                    :positive
                                    [:root]
                                    (:root resolved-expression))))
       distinct
       (sort-by edge-sort-key)
       vec))

(defn- adjacency
  [vertices edges direction]
  (let [initial (zipmap vertices (repeat #{}))]
    (reduce (fn [result {:keys [from to]}]
              (let [[source target] (if (= :forward direction)
                                      [from to]
                                      [to from])]
                (update result source conj target)))
            initial
            edges)))

(defn- finish-one
  [adjacency-map start visited finish]
  (loop [stack [[start false]]
         visited visited
         finish finish]
    (if-let [[node expanded?] (peek stack)]
      (cond
        expanded?
        (recur (pop stack) visited (conj finish node))

        (contains? visited node)
        (recur (pop stack) visited finish)

        :else
        (let [children (->> (get adjacency-map node)
                            (remove visited)
                            (sort-by vertex-sort-key)
                            reverse)]
          (recur (into (conj (pop stack) [node true])
                       (map #(vector % false) children))
                 (conj visited node)
                 finish)))
      [visited finish])))

(defn- finish-order
  [vertices adjacency-map]
  (loop [remaining vertices
         visited #{}
         finish []]
    (if-let [start (first remaining)]
      (if (contains? visited start)
        (recur (rest remaining) visited finish)
        (let [[visited finish]
              (finish-one adjacency-map start visited finish)]
          (recur (rest remaining) visited finish)))
      finish)))

(defn- collect-component
  [reverse-adjacency start assigned]
  (loop [stack [start]
         assigned assigned
         component []]
    (if-let [node (peek stack)]
      (if (contains? assigned node)
        (recur (pop stack) assigned component)
        (let [children (->> (get reverse-adjacency node)
                            (remove assigned)
                            (sort-by vertex-sort-key)
                            reverse)]
          (recur (into (pop stack) children)
                 (conj assigned node)
                 (conj component node))))
      [assigned (vec (sort-by vertex-sort-key component))])))

(defn- strongly-connected-components
  "Kosaraju SCCs with iterative DFS; components and members are canonical."
  [vertices edges]
  (let [forward (adjacency vertices edges :forward)
        reverse-adjacency (adjacency vertices edges :reverse)
        order (reverse (finish-order vertices forward))]
    (loop [remaining order
           assigned #{}
           components []]
      (if-let [start (first remaining)]
        (if (contains? assigned start)
          (recur (rest remaining) assigned components)
          (let [[assigned component]
                (collect-component reverse-adjacency start assigned)]
            (recur (rest remaining) assigned (conj components component))))
        (vec (sort-by (comp vertex-sort-key first) components))))))

(defn- deterministic-path
  [adjacency-map start goal allowed]
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
            (reduce (fn [result child]
                      (conj result (conj path child)))
                    (pop queue)
                    (->> (get adjacency-map node)
                         (filter allowed)
                         (remove visited)
                         (sort-by vertex-sort-key)))
            (conj visited node))))
      nil)))

(defn- cycle-edge
  [edges from to]
  (first (filter #(and (= from (:from %)) (= to (:to %))) edges)))

(defn- negative-cycle!
  [edges components component-of adjacency-map]
  (when-let [invalid-edge
             (first
               (filter #(and (= :negative (:sign %))
                             (= (component-of (:from %))
                                (component-of (:to %))))
                       edges))]
    (let [{:keys [from to]} invalid-edge
          component-id (component-of from)
          component (nth components component-id)
          allowed (set component)
          return-path (deterministic-path adjacency-map to from allowed)
          cycle (vec (cons from return-path))
          cycle-edges
          (mapv (fn [[source target]]
                  (if (and (= source from) (= target to))
                    invalid-edge
                    (cycle-edge edges source target)))
                (partition 2 1 cycle))]
      (throw (ex-info "Permission schema contains an unstratified exclusion cycle."
               {:type :eacl.schema/unstratified-exclusion
                :eacl/error :eacl.schema/unstratified-exclusion
                :negative-edge invalid-edge
                :cycle cycle
                :cycle-edges cycle-edges
                :component component})))))

(defn- component-dependencies
  [edges component-of]
  (reduce
    (fn [result {:keys [from to sign]}]
      (let [source (component-of from)
            target (component-of to)
            weight (if (= :negative sign) 1 0)]
        (if (= source target)
          result
          (update-in result [source target] (fnil max 0) weight))))
    {}
    edges))

(defn- component-strata
  "Longest weighted dependency path over the SCC condensation DAG in O(V+E)."
  [component-count dependencies]
  (let [dependency-count
        (into {} (for [component (range component-count)]
                   [component (count (get dependencies component))]))
        consumers
        (reduce-kv
          (fn [result source targets]
            (reduce-kv (fn [result target weight]
                         (update result target (fnil conj []) [source weight]))
                       result
                       targets))
          {}
          dependencies)]
    (loop [ready (into (sorted-set)
                       (for [[component n] dependency-count :when (zero? n)]
                         component))
           remaining dependency-count
           strata (zipmap (range component-count) (repeat 0))
           completed 0]
      (if-let [target (first ready)]
        (let [[ready remaining strata]
              (reduce
                (fn [[ready remaining strata] [source weight]]
                  (let [next-count (dec (get remaining source))
                        remaining (assoc remaining source next-count)
                        strata (update strata source max
                                       (+ (get strata target) weight))]
                    [(cond-> ready (zero? next-count) (conj source))
                     remaining
                     strata]))
                [(disj ready target) remaining strata]
                (sort-by first (get consumers target)))]
          (recur ready remaining strata (inc completed)))
        (if (= completed component-count)
          strata
          (throw (ex-info "SCC condensation graph is cyclic."
                   {:type :eacl.schema/invalid-stratification-certificate
                    :eacl/error :eacl.schema/invalid-stratification-certificate
                    :completed completed
                    :component-count component-count})))))))

(defn build-certificate
  "Builds an exact signed-dependency/SCC/stratum certificate or fails closed.

  The input must contain exactly one canonical expression per permission."
  [resolved-expressions]
  (let [resolved-expressions (mapv expression/canonicalize resolved-expressions)
        vertices (mapv vertex resolved-expressions)
        duplicate (first (sort-by vertex-sort-key
                           (for [[value n] (frequencies vertices)
                                 :when (> n 1)]
                             value)))]
    (when duplicate
      (throw (ex-info "Duplicate permission expression."
               {:type :eacl.schema/duplicate-permission-expression
                :eacl/error :eacl.schema/duplicate-permission-expression
                :permission duplicate})))
    (let [vertices (vec (sort-by vertex-sort-key vertices))
          vertex-set (set vertices)
          edges (signed-dependencies resolved-expressions)
          missing (first (remove #(contains? vertex-set (:to %)) edges))]
      (when missing
        (throw (ex-info "Permission dependency target is missing."
                 {:type :eacl.schema/missing-expression-dependency
                  :eacl/error :eacl.schema/missing-expression-dependency
                  :edge missing})))
      (let [components (strongly-connected-components vertices edges)
            component-of
            (into {}
                  (mapcat (fn [component-id component]
                            (map #(vector % component-id) component))
                          (range)
                          components))
            adjacency-map (adjacency vertices edges :forward)
            _ (negative-cycle! edges components component-of adjacency-map)
            dependencies (component-dependencies edges component-of)
            component-strata (component-strata (count components) dependencies)
            strata (into {}
                         (for [permission vertices]
                           [permission
                            (get component-strata (component-of permission))]))]
        {:format certificate-format
         :vertices vertices
         :edges edges
         :components components
         :component-of component-of
         :strata strata
         :maximum-stratum (reduce max 0 (vals strata))}))))

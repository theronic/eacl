(ns eacl.engine.sealed-plan
  "Sealed direction-specific plans for stable-discovery enumeration
  (adopt-stable-discovery-enumeration, tasks 4.1-4.4).

  Compiles the finite positive rule program reachable from one root
  permission node directly from the adapter's schema-definition operations
  (fail-closed on missing definitions), assigns dense canonical ordinals,
  certifies a static 0/1 shortest-remaining-storage-read rank with a linear
  certificate checker, orders every alternative vector by (rank, ordinal),
  and folds the complete plan, order contract, and admission-key granularity
  into one composite fingerprint.

  The plan is immutable and contains every semantic ordering input. Mutable
  cache state, latency, completion order, host map iteration, and physical
  chunk width are not inputs. Plan compilation consults no relationship data.

  Rule forms (the accepted four-kind vocabulary; node = [resource-type
  permission-name]):

    {:rule :relation        :node n :relation-eid e :subject-type st ...}
    {:rule :self-permission :node n :target-node [rt q] ...}
    {:rule :arrow-permission :node n :via-relation-eid e
     :intermediate-type it :target-node [it q] ...}
    {:rule :arrow-relation  :node n :via-relation-eid e :intermediate-type it
     :target-relation-eid te :target-subject-type st ...}"
  (:require [eacl.backend.v8 :as backend]
            [eacl.secure-format :as secure-format]))

(def plan-version 1)

(def fingerprint-domain "eacl.sealed-plan.v1")

(def order-contract
  "The complete order-ABI contract sealed into every fingerprint. Any change
  to rule ordering, rank costs, admission-key granularity, scan order, or
  logical release width is a new contract."
  {:abi-version 1
   :rule-order :canonical-encoding-ordinal
   :alternative-order :rank-then-ordinal
   :rank-costs {:relation 1 :self-permission 0
                :arrow-relation 2 :arrow-permission 1}
   :admission-keys {:merge-points :target-node-and-entity
                    :scans :rule-ordinal-and-binding-excluding-bound}
   :logical-release-width 1
   :scan-order :strict-ascending-eid})

(defn- compile-error!
  [message data]
  (throw (ex-info message (assoc data :eacl/error :eacl.plan/compile-error))))

(defn- relation-defs
  "All relation-definition rows for (resource-type, relation-name): one row
  per declared subject type. Fail-closed when absent."
  [adapter resource-type relation-name]
  (let [rows (vec (backend/invoke adapter :relation-defs
                                  resource-type relation-name))]
    (when (empty? rows)
      (compile-error!
       "Relation is not defined for this resource type."
       {:resource-type resource-type :relation relation-name}))
    rows))

(defn- permission-defs
  "All permission-definition rows (one per union branch) for
  (resource-type, permission-name). Fail-closed when absent."
  [adapter resource-type permission-name]
  (let [rows (vec (backend/invoke adapter :permission-defs
                                  resource-type permission-name))]
    (when (empty? rows)
      (compile-error!
       "Permission is not defined for this resource type."
       {:resource-type resource-type :permission permission-name}))
    rows))

(defn- node-rules
  "Compiles the four-kind rules for one permission node from its
  definition rows."
  [adapter [resource-type permission-name :as node]]
  (vec
   (mapcat
    (fn [{:keys [source-relation-name target-type target-name]}]
      (cond
        ;; permission p = r (direct relation grant)
        (and (= :self source-relation-name) (= :relation target-type))
        (for [{:keys [relation-id subject-type]}
              (relation-defs adapter resource-type target-name)]
          {:rule :relation
           :node node
           :resource-type resource-type
           :permission permission-name
           :relation-eid relation-id
           :subject-type subject-type})

        ;; permission p = q (self permission)
        (and (= :self source-relation-name) (= :permission target-type))
        [{:rule :self-permission
          :node node
          :resource-type resource-type
          :permission permission-name
          :target-node [resource-type target-name]}]

        ;; permission p = via->q (arrow to permission)
        (= :permission target-type)
        (for [{:keys [relation-id subject-type]}
              (relation-defs adapter resource-type source-relation-name)]
          {:rule :arrow-permission
           :node node
           :resource-type resource-type
           :permission permission-name
           :via-relation-eid relation-id
           :intermediate-type subject-type
           :target-node [subject-type target-name]})

        ;; permission p = via->r (arrow to relation)
        (= :relation target-type)
        (for [{via-eid :relation-id intermediate :subject-type}
              (relation-defs adapter resource-type source-relation-name)
              {target-eid :relation-id target-subject :subject-type}
              (relation-defs adapter intermediate target-name)]
          {:rule :arrow-relation
           :node node
           :resource-type resource-type
           :permission permission-name
           :via-relation-eid via-eid
           :intermediate-type intermediate
           :target-relation-eid target-eid
           :target-subject-type target-subject})

        :else
        (compile-error! "Unrecognized permission definition form."
                        {:node node
                         :source-relation source-relation-name
                         :target-type target-type
                         :target-name target-name})))
    (permission-defs adapter resource-type permission-name))))

(defn- reachable-rules
  "Breadth-first closure over permission nodes reachable from the root via
  self-permission and arrow-permission targets. Returns the complete rule
  vector for the program."
  [adapter root-node]
  (loop [frontier [root-node]
         visited #{}
         rules []]
    (if-let [node (first frontier)]
      (if (visited node)
        (recur (subvec frontier 1) visited rules)
        (let [compiled (node-rules adapter node)
              targets (keep :target-node compiled)]
          (recur (into (subvec frontier 1) targets)
                 (conj visited node)
                 (into rules compiled))))
      rules)))

;; ---------------------------------------------------------------------------
;; Canonical ordinals
;; ---------------------------------------------------------------------------

(defn- assign-ordinals
  "Sorts semantic rules by their canonical encoding and assigns dense
  ordinals, so byte-identical rule sets seal identically regardless of
  discovery order."
  [rules]
  (let [sorted (vec (sort-by secure-format/encode-canonical rules))]
    (when (not= (count sorted) (count (distinct (map secure-format/encode-canonical sorted))))
      (compile-error! "Duplicate sealed rules." {}))
    (vec (map-indexed (fn [ordinal rule] (assoc rule :ordinal ordinal))
                      sorted))))

;; ---------------------------------------------------------------------------
;; Rank certificate: static 0/1 shortest remaining storage-read distance
;; ---------------------------------------------------------------------------

(defn- plan-nodes [root-node rules]
  (vec (sort-by secure-format/encode-canonical
                (into #{root-node} (map :node rules)))))

(defn- permission-edges
  "Permission edges run target-node -> node with cost 0 (self-permission)
  or 1 (arrow-permission): the static number of storage-read boundaries
  between deriving the target and deriving the head."
  [node->index rules]
  (vec
   (map-indexed
    (fn [edge-index edge] (assoc edge :edge-index edge-index))
    (keep
     (fn [rule]
       (when (contains? #{:self-permission :arrow-permission} (:rule rule))
         {:from (node->index (:target-node rule))
          :to (node->index (:node rule))
          :cost (if (= :self-permission (:rule rule)) 0 1)
          :rule-ordinal (:ordinal rule)}))
     rules))))

(defn- zero-one-distances
  "0/1 BFS from the root over REVERSED edges: distance from each node's
  derivation to producing a root fact."
  [node-count root-index edges]
  (let [incoming (group-by :to edges)
        ;; distance from node to root over edges node ->(cost) head
        outgoing (group-by :from edges)]
    (loop [distance (assoc (vec (repeat node-count nil)) root-index 0)
           ;; simple iterate-to-fixpoint (small graphs; certificate checker
           ;; is the trusted component, this generator is not)
           iterations 0]
      (if (> iterations node-count)
        distance
        (let [next
              (reduce
               (fn [distance {:keys [from to cost]}]
                 (let [head (distance to)
                       candidate (when head (+ head cost))
                       current (distance from)]
                   (if (and candidate
                            (or (nil? current) (< candidate current)))
                     (assoc distance from candidate)
                     distance)))
               distance
               edges)]
          (if (= next distance)
            distance
            (recur next (inc iterations))))))))

(defn- witness-arrays
  "For each non-root node with finite distance, a witness edge achieving its
  distance whose hop count strictly decreases toward the root."
  [node-count root-index edges distance]
  (let [edges-by-from (group-by :from edges)
        hops (loop [hops (assoc (vec (repeat node-count nil)) root-index 0)
                    iterations 0]
               (if (> iterations node-count)
                 hops
                 (let [next
                       (reduce
                        (fn [hops {:keys [from to cost]}]
                          (let [head-hops (hops to)
                                head-distance (distance to)
                                from-distance (distance from)]
                            (if (and head-hops head-distance from-distance
                                     (= from-distance (+ head-distance cost))
                                     (or (nil? (hops from))
                                         (< (inc head-hops) (hops from))))
                              (assoc hops from (inc head-hops))
                              hops)))
                        hops
                        edges)]
                   (if (= next hops) hops (recur next (inc iterations))))))
        witness
        (vec
         (for [node-index (range node-count)]
           (if (= node-index root-index)
             (count edges)
             (or (some
                  (fn [{:keys [to cost edge-index]}]
                    (when (and (distance node-index) (distance to)
                               (hops node-index) (hops to)
                               (= (distance node-index)
                                  (+ (distance to) cost))
                               (= (hops node-index) (inc (hops to))))
                      edge-index))
                  (edges-by-from node-index))
                 (count edges)))))]
    {:hops hops :witness witness}))

(defn valid-certificate?
  "Linear trusted checker: bounded fields, root at zero, every edge
  inequality respected, every non-root reachable node has a witness edge
  achieving its distance with strictly decreasing hops."
  [{:keys [distance witness-edge hops]} node-count root-index edges]
  (let [edge-count (count edges)]
    (and (= node-count (count distance) (count witness-edge) (count hops))
         (= 0 (distance root-index) (hops root-index))
         ;; bounded fields
         (every? (fn [value]
                   (or (nil? value)
                       (and (int? value) (<= 0 value) (< value node-count))))
                 (concat distance hops))
         (every? (fn [value] (and (int? value) (<= 0 value edge-count)))
                 witness-edge)
         ;; edge inequalities: distance(from) <= distance(to) + cost
         (every? (fn [{:keys [from to cost]}]
                   (let [df (distance from) dt (distance to)]
                     (or (nil? dt) (nil? df) (<= df (+ dt cost)))))
                 edges)
         ;; witnesses
         (every? (fn [node-index]
                   (or (= node-index root-index)
                       (nil? (distance node-index))
                       (let [edge-index (witness-edge node-index)]
                         (and (< edge-index edge-count)
                              (let [{:keys [from to cost]}
                                    (nth edges edge-index)]
                                (and (= from node-index)
                                     (some? (distance to))
                                     (= (distance node-index)
                                        (+ (distance to) cost))
                                     (some? (hops to))
                                     (= (hops node-index)
                                        (inc (hops to)))))))))
                 (range node-count)))))

(def local-read-cost
  "Static storage-read boundaries the rule itself must cross before its
  head derivation continues."
  {:relation 1 :self-permission 0 :arrow-relation 2 :arrow-permission 1})

(defn- rank-rules
  [rules node->index distance]
  (mapv (fn [rule]
          (let [node-distance (distance (node->index (:node rule)))]
            (when (nil? node-distance)
              (compile-error!
               "Rule node cannot reach the root; the program is malformed."
               {:node (:node rule)}))
            (assoc rule :rank (+ (local-read-cost (:rule rule))
                                 node-distance))))
        rules))

;; ---------------------------------------------------------------------------
;; Direction indexes and fingerprint
;; ---------------------------------------------------------------------------

(defn- by-rank-then-ordinal [rules]
  (vec (sort-by (juxt :rank :ordinal) rules)))

(defn- indexes
  [rules]
  {:forward-seeds
   (into (sorted-map)
         (map (fn [[subject-type bucket]]
                [subject-type (by-rank-then-ordinal bucket)]))
         (group-by (fn [rule]
                     (case (:rule rule)
                       :relation (:subject-type rule)
                       :arrow-relation (:target-subject-type rule)
                       nil))
                   (filter #(contains? #{:relation :arrow-relation}
                                       (:rule %))
                           rules)))
   :forward-consumers
   (into {}
         (map (fn [[target-node bucket]]
                [target-node (by-rank-then-ordinal bucket)]))
         (group-by :target-node
                   (filter #(contains? #{:self-permission :arrow-permission}
                                       (:rule %))
                           rules)))
   :reverse-rules
   (into {}
         (map (fn [[node bucket]] [node (by-rank-then-ordinal bucket)]))
         (group-by :node rules))})

(defn- plan-records
  "Complete canonical record sequence for the composite fingerprint. Record
  order is contractual."
  [{:keys [version root root-index nodes rules edges certificate]}]
  (concat
   [[:header version root root-index (count nodes) (count rules)]
    [:order-contract order-contract]]
   (map-indexed (fn [i node] [:node i node]) nodes)
   (map (fn [rule] [:rule (:ordinal rule) rule]) rules)
   (map-indexed (fn [i edge] [:edge i edge]) edges)
   (map (fn [i]
          [:certificate i
           (nth (:distance certificate) i)
           (nth (:witness-edge certificate) i)
           (nth (:hops certificate) i)])
        (range (count nodes)))))

(defn- cyclic-nodes?
  "True when the permission-dependency graph contains a cycle, i.e. the
  schema is recursive and traversal depth is data-dependent. Kahn's
  peel: a graph is acyclic exactly when every node topologically sorts."
  [node-count edges]
  (let [outgoing (group-by :from edges)
        initial-degrees (reduce (fn [degrees {:keys [to]}]
                                  (update degrees to (fnil inc 0)))
                                {} edges)]
    (loop [degrees initial-degrees
           stack (vec (remove #(pos? (get initial-degrees % 0))
                              (range node-count)))
           sorted 0]
      (if-let [node (peek stack)]
        (let [[degrees stack]
              (reduce (fn [[degrees stack] {:keys [to]}]
                        (let [degree (dec (get degrees to))]
                          [(assoc degrees to degree)
                           (if (zero? degree) (conj stack to) stack)]))
                      [degrees (pop stack)]
                      (outgoing node))]
          (recur degrees stack (inc sorted)))
        (< sorted node-count)))))

(defn seal-plan
  "Compiles and seals the direction-specific plan for one root permission
  node against one adapter's schema definitions. Pure with respect to
  relationship data; fail-closed on malformed schemas."
  [adapter root-node]
  (let [rules (assign-ordinals (reachable-rules adapter root-node))
        nodes (plan-nodes root-node rules)
        node->index (into {} (map-indexed (fn [i node] [node i]) nodes))
        root-index (node->index root-node)
        edges (permission-edges node->index rules)
        distance (zero-one-distances (count nodes) root-index edges)
        {:keys [hops witness]} (witness-arrays (count nodes) root-index
                                               edges distance)
        certificate {:distance distance :witness-edge witness :hops hops}
        _ (when-not (valid-certificate? certificate (count nodes)
                                        root-index edges)
            (compile-error! "Rank certificate failed validation."
                            {:root root-node}))
        ranked (rank-rules rules node->index distance)
        plan {:version plan-version
              :root root-node
              :root-index root-index
              :nodes nodes
              :rules ranked
              :edges edges
              :certificate certificate
              :order-contract order-contract
              :indexes (indexes ranked)}]
    (assoc plan
           :fingerprint
           (secure-format/canonical-records-digest
            fingerprint-domain (vec (plan-records plan)))
           ;; Derived from already-fingerprinted structure; excluded from
           ;; the digest so the composite fingerprint stays unchanged.
           :recursive? (cyclic-nodes? (count nodes) edges))))

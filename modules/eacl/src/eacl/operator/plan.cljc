(ns eacl.operator.plan
  "Canonical, witness-carrying plans for intersection and exclusion.

  Union-only programs delegate to eacl.engine.sealed-plan and retain that
  value byte-for-byte. Operator compilation reads only the permission
  expression closure rooted at the requested permission and never reads
  relationship data."
  (:require [clojure.set :as set]
            [eacl.backend.v8 :as backend]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.exact-integer :as exact-integer]
            [eacl.schema.expression :as expression]
            [eacl.schema.expression-graph :as expression-graph]
            [eacl.schema.expression-limits :as expression-limits]
            [eacl.schema.expression-persistence :as expression-persistence]
            [eacl.schema.expression-policy :as expression-policy]
            [eacl.secure-format :as secure]))

(def plan-version 1)
(def fingerprint-domain "eacl/operator-plan/v2")
(def cover-version :recursive-exact-cover-v1)
(def witness-version :typed-node-bitset-v1)
(def predicate-version :short-circuit-dag-v1)
(def physical-policy-version :demand-sized-doubling-256-v1)

(def order-contract
  {:abi-version 1
   :mode :filtered-least-derivation-path
   :union-composition :sealed-least-path
   :intersection-composition :sealed-anchor-filter
   :exclusion-composition :sealed-left-filter
   :candidate-order :generator-relative
   :logical-progress :last-consumed-cover-candidate
   :physical-overread-advances-progress? false})

(def ^:private operator-plan-keys
  #{:format :version :domain :root :expressions :dependency-certificate
    :positive-components :strata :relation-closures :child-consumers
    :leaf-descriptors :costs :covers :generators :anchors
    :witness-programs :predicate-programs :specializations
    :capability-identity :compatibility-formats :versions :order-contract
    :fingerprint :expression-roots :certificate-acyclic?
    :delegated-permissions :guarded-delegation})

(defn- compile-error! [reason message data]
  (throw
   (ex-info message
            (merge {:type :eacl.plan/operator-compile-error
                    :eacl/error :eacl.plan/operator-compile-error
                    :reason reason}
                   data))))

(defn operator-plan? [value]
  (and (map? value)
       (= expression-policy/operator-plan-format (:format value))
       (= plan-version (:version value))
       (= :operator (:domain value))))

(defn ^:no-doc expression-roots
  "Permission → root-node index. Sealed into the plan at compile time;
  the fallback recompute keeps hand-built test plans working."
  [plan]
  (or (:expression-roots plan)
      (into {} (map (juxt :permission :root)) (:expressions plan))))

(defn ^:no-doc certificate-acyclic?
  "True when the dependency certificate has no cycle: every strongly
  connected component is a singleton and no edge is a self-loop. Sealed
  into the plan at compile time; the fallback keeps hand-built plans
  working."
  [plan]
  (if (contains? plan :certificate-acyclic?)
    (:certificate-acyclic? plan)
    (let [certificate (:dependency-certificate plan)]
      (and (every? #(= 1 (count %)) (:components certificate))
           (not-any? #(= (:from %) (:to %)) (:edges certificate))))))

(defn- operator-free-dag? [dag]
  (not-any? #(contains? #{:intersection :exclusion} (first %)) (:nodes dag)))

(defn- closure-analysis
  "The operator-reaching permissions of a plan's closure, its recursive
  components that contain them, and the permissions that reach no operator.
  Everything that reaches an operator permission carries its operators."
  [plan]
  (let [certificate (:dependency-certificate plan)
        edges (:edges certificate)
        operator-permissions
        (into #{}
              (keep (fn [{:keys [permission dag]}]
                      (when-not (operator-free-dag? dag) permission)))
              (:expressions plan))
        consumers (reduce (fn [index {:keys [from to]}]
                            (update index to (fnil conj []) from))
                          {} edges)
        operator-reaching
        (loop [pending (vec operator-permissions)
               reached operator-permissions]
          (if-let [permission (peek pending)]
            (let [fresh (remove reached (get consumers permission))]
              (recur (into (pop pending) fresh) (into reached fresh)))
            reached))
        self-loops (into #{}
                         (keep (fn [{:keys [from to]}] (when (= from to) from)))
                         edges)]
    {:operator-reaching operator-reaching
     :recursive-operator-components
     (filterv (fn [component]
                (and (some operator-reaching component)
                     (or (> (count component) 1)
                         (contains? self-loops (first component)))))
              (:components certificate))
     :union-only (into (sorted-set)
                       (remove operator-reaching)
                       (:vertices certificate))}))

(defn ^:no-doc union-only-permissions
  "The permissions of an operator plan's closure that reach no intersection
  or exclusion permission. Each one's denotation is exactly the union
  engine's."
  [plan]
  (:union-only (closure-analysis plan)))

(defn ^:no-doc delegated-permissions
  "The union-only permissions of an operator plan's closure, or nil when some
  intersection or exclusion permission lies on a positive dependency cycle.

  A permission is union-only when neither its own expression nor any
  permission it reaches uses intersection or exclusion. Its denotation is
  exactly the union engine's, so an evaluator may decide it through that
  permission's sealed union plan. When every positive recursive component
  consists of union-only permissions, the permissions that remain — the ones
  carrying the operators — form an acyclic graph over those opaque operands,
  and the acyclic operator evaluators can decide the root once each
  union-only root is delegated. A pure function of the sealed expressions and
  dependency certificate; relationship data is never an input."
  [plan]
  (if (contains? plan :delegated-permissions)
    (:delegated-permissions plan)
    (let [{:keys [recursive-operator-components union-only]}
          (closure-analysis plan)]
      (when (empty? recursive-operator-components)
        union-only))))

(def ^:private leaf-instructions
  #{:direct-membership :permission-membership :arrow-membership})

(defn- leaf-rules
  "The search rules of one leaf of `member`'s expression at resource type
  `resource-type`: relation grants, and permission targets that are
  component members (`internal?`) or decided by the oracle (`decidable?`).
  Nil when a target is neither."
  [predicate member resource-type internal? decidable?]
  (let [common {:node member :resource-type resource-type}
        target (fn [rule kind target-node]
                 (cond
                   (internal? target-node) (assoc rule :rule kind :target-node target-node)
                   (decidable? target-node)
                   (assoc rule
                          :rule (if (= :self-permission kind) :oracle :arrow-oracle)
                          :target-node target-node)))
        rules
        (case (:instruction predicate)
          :direct-membership
          (mapv (fn [{:keys [subject-type relation-id]}]
                  (assoc common :rule :relation :relation-eid relation-id
                         :subject-type subject-type))
                (get-in predicate [:descriptor :partitions]))

          :permission-membership
          [(target common :self-permission (:target-node predicate))]

          :arrow-membership
          (vec
           (mapcat
            (fn [{:keys [intermediate-type via-relation-eid target-kind
                         target-node target-relation]}]
              (let [arrow (assoc common :via-relation-eid via-relation-eid
                                 :intermediate-type intermediate-type)]
                (if (= :permission target-kind)
                  [(target arrow :arrow-permission target-node)]
                  (mapv (fn [{:keys [subject-type relation-id]}]
                          (assoc arrow :rule :arrow-relation
                                 :target-relation-eid relation-id
                                 :target-subject-type subject-type))
                        (:partitions target-relation)))))
            (get-in predicate [:descriptor :partitions]))))]
    (when (every? some? rules) rules)))

(defn ^:no-doc recursive-operand
  "The one operand of an intersection that depends on its component, or nil
  when none or several do. Linearity is decided here alone."
  [depends? children]
  (let [[recursive & more] (filter depends? children)]
    (when (and recursive (empty? more))
      recursive)))

(defn- member-rules
  "The rules of `member`'s expression for the guarded membership search, or
  nil when the expression is not linearly guarded.

  A union contributes every child. An intersection must have exactly one
  child that depends on the component; every other child must be a leaf
  outside it, and becomes a guard on the rules of the recursive child. An
  exclusion's left operand must depend on the component and its right
  operand must be a leaf outside it, which becomes a subtracted guard. Each
  guard is the vector of rules of its leaf, one of which must hold."
  [plan component decidable? member]
  (let [programs (get-in plan [:predicate-programs member])
        resource-type (first member)
        internal? (set component)
        depends? (memoize
                  (fn depends? [node-id]
                    (let [{:keys [instruction] :as predicate} (get programs node-id)]
                      (case instruction
                        :direct-membership false
                        :permission-membership (internal? (:target-node predicate))
                        :arrow-membership (boolean
                                           (some #(internal? (:target-node %))
                                                 (get-in predicate [:descriptor :partitions])))
                        (:any-true :all-true) (boolean (some depends? (:children predicate)))
                        :left-and-not-right (or (depends? (:left predicate))
                                                (depends? (:right predicate)))))))
        guard (fn [sign node-id]
                (let [predicate (get programs node-id)]
                  (when (contains? leaf-instructions (:instruction predicate))
                    (when-let [alternatives (leaf-rules predicate member resource-type
                                                        (constantly false) decidable?)]
                      {:sign sign :key [member node-id] :alternatives alternatives}))))
        walk (fn walk [node-id guards]
               (let [{:keys [instruction] :as predicate} (get programs node-id)]
                 (case instruction
                   :any-true
                   (reduce (fn [rules child]
                             (if-let [child-rules (walk child guards)]
                               (into rules child-rules)
                               (reduced nil)))
                           [] (:children predicate))

                   :all-true
                   (let [recursive (recursive-operand depends? (:children predicate))
                         added (mapv #(guard :positive %)
                                     (remove #{recursive} (:children predicate)))]
                     (when (and recursive (every? some? added))
                       (walk recursive (into guards added))))

                   :left-and-not-right
                   (let [subtracted (when-not (depends? (:right predicate))
                                      (guard :negative (:right predicate)))]
                     (when (and (depends? (:left predicate)) subtracted)
                       (walk (:left predicate) (conj guards subtracted))))

                   (when-let [rules (leaf-rules predicate member resource-type
                                                internal? decidable?)]
                     (mapv #(cond-> % (seq guards) (assoc :guards guards)) rules)))))]
    (walk (get (expression-roots plan) member) [])))

(defn ^:no-doc guarded-delegation
  "The guarded delegation of a plan that recurses through an operator, or
  nil. Derived outside the plan fingerprint from sealed fields alone.

  It exists when every recursive component that carries an operator is
  linearly guarded (`member-rules`) and every permission such a component
  references outside itself is union-only or a member of another such
  component. Then each member is decided by a guarded membership search over
  its rules, and each union-only permission by the union engine. Returns
  `{:members members :union-only permissions :rules {member rules}}`."
  [plan]
  (if (contains? plan :guarded-delegation)
    (:guarded-delegation plan)
    (let [{:keys [recursive-operator-components union-only]} (closure-analysis plan)
          members (into (sorted-set) (mapcat identity) recursive-operator-components)
          decidable? #(or (contains? union-only %) (contains? members %))]
      (when (seq recursive-operator-components)
        (let [rules (reduce
                     (fn [result component]
                       (reduce (fn [result member]
                                 (if-let [rules (member-rules plan component decidable? member)]
                                   (assoc result member rules)
                                   (reduced nil)))
                               result component))
                     {} recursive-operator-components)]
          (when (and rules (= (count rules) (count members)))
            {:members members
             :union-only union-only
             :rules (into (sorted-map) rules)}))))))

(defn ^:no-doc delegation
  "The permissions an evaluator of `plan` hands to oracles, or nil: every
  union-only permission when no operator permission lies on a cycle
  (`delegated-permissions`); otherwise, when the plan is guarded
  (`guarded-delegation`), its union-only permissions and guarded members."
  [plan]
  (or (delegated-permissions plan)
      (when-let [{:keys [members union-only]} (guarded-delegation plan)]
        (into union-only members))))

(defn ^:no-doc guarded-program
  "The guarded membership search's program for `member`: the rules of the
  members of its component, rooted at `member`. Other permissions its rules
  name are decided by the oracle."
  [plan member]
  (let [{:keys [rules]} (guarded-delegation plan)
        certificate (:dependency-certificate plan)
        component (get-in certificate [:components (get-in certificate [:component-of member])])]
    {:root member
     :fingerprint [::guarded-program (:fingerprint plan) member]
     :indexes {:reverse-rules (select-keys rules component)}}))

(defn ^:no-doc delegated-generator
  "The delegated union-only permission whose own sealed union plan generates
  `permission`'s candidates, or nil. It exists when the root's generator
  chain — each intersection's sealed anchor, each exclusion's left operand —
  reaches a permission in `delegated` through permission references alone,
  without union fan-in. Every result of the root then lies in that
  permission's closure, which its union plan enumerates exactly, so the plan
  is a complete candidate cover. Pure over sealed fields."
  [plan permission delegated]
  (let [roots (expression-roots plan)]
    (loop [permission permission
           node-id (get roots permission)
           seen #{}]
      (when-not (contains? seen [permission node-id])
        (let [seen (conj seen [permission node-id])
              {:keys [kind source-node]} (get-in plan [:covers permission node-id])
              predicate (get-in plan [:predicate-programs permission node-id])]
          (case kind
            :child (recur permission source-node seen)
            :self
            (when (= :permission-membership (:instruction predicate))
              (let [target (:target-node predicate)]
                (if (contains? delegated target)
                  target
                  (recur target (get roots target) seen))))
            nil))))))

(defn ^:no-doc delegated-view
  "An evaluation view of `plan` in which the root predicate of every
  permission in `permissions` becomes `:delegated-membership`: the acyclic
  evaluators then ask a caller-supplied union-engine oracle for that operand
  instead of evaluating its (possibly recursive) expression themselves. The
  sealed fields, fingerprint, and point-cache identity are unchanged; the view
  never leaves the evaluator that requested it."
  [plan permissions]
  (let [roots (expression-roots plan)]
    (reduce
     (fn [view permission]
       (assoc-in view [:predicate-programs permission (get roots permission)]
                 {:instruction :delegated-membership
                  :permission permission
                  :modes #{:scalar :aligned-vector}
                  :entity-identity :typed-pair}))
     (assoc plan :operator-delegation {:permissions permissions})
     permissions)))

(defn- expression-entity [adapter [resource-type permission-name :as node]]
  (let [entity (backend/invoke adapter :permission-expression
                               resource-type permission-name)]
    (when-not entity
      (compile-error! :missing-permission-expression
                      "Permission expression is missing from the selected snapshot."
                      {:permission node}))
    (let [resolved (expression-persistence/decode-entity entity)
          actual [(:resource-type resolved) (:permission-name resolved)]]
      (when-not (= node actual)
        (compile-error! :expression-identity-mismatch
                        "Permission expression identity does not match its lookup key."
                        {:permission node :actual actual}))
      {:entity entity :expression resolved})))

(defn- collect-expression-closure [adapter root]
  (loop [frontier [root]
         collected {}]
    (if-let [node (first frontier)]
      (if (contains? collected node)
        (recur (subvec frontier 1) collected)
        (let [{:keys [expression] :as value} (expression-entity adapter node)
              targets (->> (expression-graph/signed-dependencies [expression])
                           (map :to)
                           distinct
                           (sort-by (juxt (comp str first) (comp str second)))
                           vec)]
          (recur (into (subvec frontier 1) targets)
                 (assoc collected node value))))
      collected)))

(defn- operator-node? [node]
  (case (:op node)
    (:intersection :exclusion) true
    (:union) (boolean (some operator-node? (:children node)))
    false))

(defn- expression-closure-has-operator? [collected]
  (boolean
   (some (comp operator-node? :root :expression val) collected)))

(defn- relation-descriptor [adapter cache resource-type relation-name]
  (let [key [resource-type relation-name]]
    (if-some [cached (get @cache key)]
      cached
      (let [rows (vec (backend/invoke adapter :relation-defs
                                      resource-type relation-name))]
        (when (empty? rows)
          (compile-error! :missing-relation
                          "Relation referenced by an operator plan is missing."
                          {:resource-type resource-type
                           :relation relation-name}))
        (doseq [row rows]
          (when-not (and (= #{:relation-id :resource-type
                              :relation-name :subject-type}
                            (set (keys row)))
                         (= resource-type (:resource-type row))
                         (= relation-name (:relation-name row))
                         (keyword? (:subject-type row))
                         (exact-integer/natural? (:relation-id row)))
            (compile-error! :malformed-relation-definition
                            "Backend returned a malformed relation definition."
                            {:resource-type resource-type
                             :relation relation-name
                             :definition row})))
        (let [partitions
              (->> rows
                   (map #(select-keys % [:subject-type :relation-id]))
                   (sort-by (juxt (comp str :subject-type) :relation-id))
                   vec)
              duplicate (first (for [[subject-type n]
                                     (frequencies (map :subject-type partitions))
                                     :when (> n 1)]
                                 subject-type))]
          (when duplicate
            (compile-error! :duplicate-relation-partition
                            "Relation has duplicate subject-type partitions."
                            {:resource-type resource-type
                             :relation relation-name
                             :subject-type duplicate}))
          (let [descriptor
                {:kind :relation
                 :resource-type resource-type
                 :relation relation-name
                 :partitions partitions
                 :order {:forward :strict-ascending-resource-eid
                         :reverse :strict-ascending-subject-eid
                         :unique? true
                         :inclusive-reseek? true}}]
            (swap! cache assoc key descriptor)
            descriptor))))))

(defn ^:no-doc relation-partition [descriptor subject-type]
  (some #(when (= subject-type (:subject-type %)) %)
        (:partitions descriptor)))

(defn- validate-relation-subjects! [node descriptor declared]
  (let [actual (mapv :subject-type (:partitions descriptor))]
    (when-not (= declared actual)
      (compile-error! :relation-partition-mismatch
                      "Expression and relation storage disagree on subject types."
                      {:node node :declared declared :actual actual}))))

(defn- arrow-descriptor
  [adapter relation-cache resource-type relation-name partitions]
  (let [via (relation-descriptor adapter relation-cache
                                 resource-type relation-name)
        by-type (into {} (map (juxt :subject-type identity)) partitions)
        via-types (mapv :subject-type (:partitions via))]
    (when-not (= (set via-types) (set (keys by-type)))
      (compile-error! :arrow-partition-mismatch
                      "Arrow partitions do not cover the source relation exactly."
                      {:resource-type resource-type
                       :relation relation-name
                       :source-subject-types via-types
                       :partition-subject-types
                       (vec (sort-by str (keys by-type)))}))
    {:kind :arrow
     :resource-type resource-type
     :relation relation-name
     :partitions
     (mapv
      (fn [{:keys [subject-type relation-id]}]
        (let [{:keys [target-kind target-name]} (get by-type subject-type)]
          (merge
           {:intermediate-type subject-type
            :via-relation-eid relation-id
            :target-kind target-kind
            :target-name target-name}
           (if (= :permission target-kind)
             {:target-node [subject-type target-name]}
             {:target-relation
              (relation-descriptor adapter relation-cache
                                   subject-type target-name)}))))
      (:partitions via))
     :order {:forward :least-path
             :reverse :least-path
             :direct-sequence-compatible? false}}))

(defn- enrich-expression
  [adapter relation-cache [resource-type _ :as permission]
   resolved]
  (let [{:keys [dag metrics]}
        (expression-limits/check-normalized!
         resolved (expression-persistence/effective-expression-limits))
        nodes
        (mapv
         (fn [id record]
           (let [op (first record)
                 base {:id id :op op :record record}]
             (case op
               :relation
               (let [descriptor
                     (relation-descriptor adapter relation-cache
                                          resource-type (second record))]
                 (validate-relation-subjects! permission descriptor
                                              (nth record 2))
                 (assoc base :descriptor descriptor))

               :permission
               (assoc base :target-node [resource-type (second record)])

               :arrow
               (assoc base :descriptor
                      (arrow-descriptor adapter relation-cache resource-type
                                        (second record) (nth record 2)))

               base)))
         (range)
         (:nodes dag))]
    {:permission permission
     :expression-format expression/format-version
     ;; Plan identity follows the canonical semantic DAG, not source grouping
     ;; or commutative spelling. This is a runtime plan/cursor fingerprint, not
     ;; a durable permission attribute or source of schema truth.
     :expression-digest
     (secure/canonical-tree-digest "eacl/operator-expression/v2" dag)
     :dag dag
     :metrics metrics
     :root (:root dag)
     :nodes nodes}))

(defn- child-consumers [nodes]
  (reduce
   (fn [result {:keys [id record]}]
     (reduce #(update %1 %2 (fnil conj []) id)
             result
             (expression-limits/record-children record)))
   (sorted-map)
   nodes))

(defn- permission-consumers [dependency-certificate]
  (reduce
   (fn [result {:keys [from to sign path via]}]
     (update result to (fnil conj [])
             {:consumer from :sign sign :path path :via via}))
   (sorted-map)
   (:edges dependency-certificate)))

(defn- node-costs [nodes]
  (reduce
   (fn [costs {:keys [id op record descriptor]}]
     (let [children (expression-limits/record-children record)
           depth (if (seq children)
                   (inc (reduce max (map #(get-in costs [% :depth]) children)))
                   (if (= :arrow op) 1 0))
           work
           (case op
             :relation (count (:partitions descriptor))
             :arrow (+ (count (:partitions descriptor))
                       (reduce + 0
                               (map #(count (get-in % [:target-relation
                                                       :partitions]))
                                    (filter :target-relation
                                            (:partitions descriptor)))))
             :permission 1
             (inc (reduce + 0 (map #(get-in costs [% :work]) children))))
           direct? (= :relation op)
           sequence-compatible? direct?
           tuple [(if direct? 0 1)
                  (if sequence-compatible? 0 1)
                  depth work id]]
       (assoc costs id {:depth depth
                        :work work
                        :direct? direct?
                        :sequence-compatible? sequence-compatible?
                        :tuple tuple})))
   (sorted-map)
   nodes))

(defn select-intersection-anchor
  "Selects the deterministic generator anchor from sealed structural costs.

  This decision is deliberately pure: request state, cache contents, observed
  selectivity, and backend timing are not inputs and therefore cannot change
  plan identity or result order."
  [children costs]
  (first (sort-by #(get-in costs [% :tuple]) children)))

(defn- compile-node-programs [nodes costs]
  (reduce
   (fn [result {:keys [id op record descriptor target-node]}]
     (let [children (expression-limits/record-children record)
           anchor (when (= :intersection op)
                    (select-intersection-anchor children costs))
           left (when (= :exclusion op) (second record))
           right (when (= :exclusion op) (nth record 2))
           cover
           (case op
             :union {:kind :union :source-nodes children}
             :intersection {:kind :child :source-node anchor}
             :exclusion {:kind :child :source-node left}
             {:kind :self :source-node id})
           generator
           (case op
             :relation {:kind :exact-direct-leaf :source-node id}
             :permission {:kind :exact-permission :target-node target-node}
             :arrow {:kind :exact-arrow :source-node id}
             :union {:kind :least-path-union :source-nodes children}
             :intersection
             {:kind :anchor-filter
              :source-node anchor
              :predicate-nodes (vec (remove #{anchor} children))}
             :exclusion {:kind :left-anti-filter
                         :source-node left
                         :negative-node right})
           witness
           (case op
             :intersection {:generator-proves [anchor]
                            :on-success-add [id]}
             :exclusion {:generator-proves [left]
                         :on-success-add [id]}
             :union {:generator-proves :emitting-child
                     :on-success-add [id]}
             {:generator-proves [id]
              :on-success-add [id]})
           predicate
           (assoc
            (case op
              :relation {:instruction :direct-membership
                         :descriptor descriptor}
              :permission {:instruction :permission-membership
                           :target-node target-node}
              :arrow {:instruction :arrow-membership
                      :descriptor descriptor}
              :union {:instruction :any-true
                      :children children
                      :short-circuit :first-true}
              :intersection {:instruction :all-true
                             :children children
                             :short-circuit :first-false}
              :exclusion {:instruction :left-and-not-right
                          :left left :right right
                          :right-requires :completed-exact})
            :modes #{:scalar :aligned-vector}
            :entity-identity :typed-pair)]
       (-> result
           (assoc-in [:covers id] cover)
           (assoc-in [:generators id] generator)
           (assoc-in [:witnesses id] witness)
           (assoc-in [:predicates id] predicate)
           (cond-> anchor (assoc-in [:anchors id] anchor)))))
   {:covers (sorted-map)
    :generators (sorted-map)
    :anchors (sorted-map)
    :witnesses (sorted-map)
    :predicates (sorted-map)}
   nodes))

(defn- relation-specialization-partitions [nodes child-ids]
  (let [descriptors (mapv #(get-in nodes [% :descriptor]) child-ids)
        common-types (if (seq descriptors)
                       (apply set/intersection
                              (map #(set (map :subject-type (:partitions %)))
                                   descriptors))
                       #{})]
    (into (sorted-map)
          (for [subject-type (sort-by str common-types)]
            [subject-type
             (mapv (fn [child-id descriptor]
                     {:node child-id
                      :relation-eid
                      (:relation-id
                       (relation-partition descriptor subject-type))})
                   child-ids descriptors)]))))

(defn- direct-specializations [nodes programs]
  (let [nodes-by-id (into {} (map (juxt :id identity)) nodes)]
    (into
     (sorted-map)
     (keep
      (fn [{:keys [id op record]}]
        (let [children (expression-limits/record-children record)]
          (when (and (contains? #{:intersection :exclusion} op)
                     (every? #(= :relation (get-in nodes-by-id [% :op]))
                             children))
            (let [ordered-children
                  (if (= :intersection op)
                    (let [anchor (get-in programs [:anchors id])]
                      (into [anchor] (remove #{anchor}) children))
                    children)
                  partitions
                  (relation-specialization-partitions nodes-by-id
                                                      ordered-children)]
              (when (seq partitions)
                [id {:kind (if (= :intersection op)
                             :direct-k-way-intersection
                             :direct-monotone-exclusion)
                     :sequence :strict-ascending-candidate-eid
                     :directions #{:forward :reverse}
                     :bounds :identical-inclusive-exclusive-request-bounds
                     :typed-partitions partitions
                     :driver (first ordered-children)
                     :operands (vec (rest ordered-children))}])))))
      nodes))))

(defn- own-signed-relations
  [adapter relation-cache permission resolved]
  (let [result (atom {:positive #{} :negative #{}})]
    (letfn [(add! [sign ids]
              (swap! result update sign into ids))
            (walk [node sign]
              (case (:op node)
                :relation
                (add! sign
                      (map :relation-id
                           (:partitions
                            (relation-descriptor adapter relation-cache
                                                 (first permission)
                                                 (:name node)))))

                :permission nil

                :arrow
                (let [descriptor
                      (arrow-descriptor adapter relation-cache
                                        (first permission)
                                        (:relation node)
                                        (:partitions node))]
                  (add! sign (map :via-relation-eid
                                  (:partitions descriptor)))
                  (doseq [{:keys [target-relation]} (:partitions descriptor)
                          :when target-relation]
                    (add! sign (map :relation-id
                                    (:partitions target-relation)))))

                (:union :intersection)
                (doseq [child (:children node)] (walk child sign))

                :exclusion
                (do (walk (:left node) sign)
                    (walk (:right node) :negative))))]
      (walk (:root resolved) :positive)
      @result)))

(defn- relation-closures
  [adapter relation-cache collected]
  (let [own (into {}
                  (for [[permission {:keys [expression]}] collected]
                    [permission
                     (own-signed-relations adapter relation-cache
                                           permission expression)]))
        edges (expression-graph/signed-dependencies
               (mapv (comp :expression val) collected))
        dependencies (group-by :from edges)]
    (into
     (sorted-map)
     (for [root (sort-by (juxt (comp str first) (comp str second))
                        (keys collected))]
       [root
        (let [result
              (loop [frontier [[root :positive]]
                     seen #{}
                     result {:positive #{} :negative #{}}]
                (if-let [[permission sign] (first frontier)]
                  (if (contains? seen [permission sign])
                    (recur (subvec frontier 1) seen result)
                    (let [own-relations (get own permission)
                          result
                          (if (= :negative sign)
                            (update result :negative into
                                    (into (:positive own-relations)
                                          (:negative own-relations)))
                            (-> result
                                (update :positive into
                                        (:positive own-relations))
                                (update :negative into
                                        (:negative own-relations))))
                          next
                          (for [edge (get dependencies permission)
                                :let [next-sign
                                      (if (or (= :negative sign)
                                              (= :negative (:sign edge)))
                                        :negative :positive)]]
                            [(:to edge) next-sign])]
                      (recur (into (subvec frontier 1) next)
                             (conj seen [permission sign])
                             result)))
                  result))]
          {:positive (vec (sort (:positive result)))
           :negative (vec (sort (:negative result)))
           :all (vec (sort (into (:positive result)
                                 (:negative result))))})]))))

(defn- fingerprint-input [plan]
  ;; These projections are recomputed from authenticated fields; fresh-compile
  ;; validation also checks them. The complete remaining plan is authenticated.
  (dissoc plan :fingerprint :expression-roots :certificate-acyclic?
          :delegated-permissions :guarded-delegation))

(defn- compile-operator-plan [adapter root collected]
  (when-not (expression-closure-has-operator? collected)
    (compile-error! :operator-transition-without-operator
                    "Union sealing requested operator compilation without an operator."
                    {:root root}))
  (let [expressions (mapv (comp :expression val)
                          (sort-by key collected))
        dependency-certificate (expression-graph/build-certificate expressions)
        relation-cache (atom {})
        enriched
        (into (sorted-map)
              (for [[permission {:keys [expression]}]
                    (sort-by key collected)]
                [permission
                 (enrich-expression adapter relation-cache
                                    permission expression)]))
        child-consumer-index
        (into (sorted-map)
              (for [[permission data] enriched]
                [permission (child-consumers (:nodes data))]))
        costs
        (into (sorted-map)
              (for [[permission data] enriched]
                [permission (node-costs (:nodes data))]))
        programs
        (into (sorted-map)
              (for [[permission data] enriched]
                [permission
                 (compile-node-programs (:nodes data)
                                        (get costs permission))]))
        specializations
        (into (sorted-map)
              (for [[permission data] enriched]
                [permission
                 (direct-specializations (:nodes data)
                                         (get programs permission))]))
        plan
        {:format expression-policy/operator-plan-format
         :version plan-version
         :domain :operator
         :root root
         :expressions
         (mapv (fn [[permission data]]
                 {:permission permission
                  :expression-format (:expression-format data)
                  :expression-digest (:expression-digest data)
                  :dag (:dag data)
                  :metrics (:metrics data)
                  :root (:root data)})
               enriched)
         :dependency-certificate dependency-certificate
         :positive-components (:components dependency-certificate)
         :strata (:strata dependency-certificate)
         :relation-closures
         (relation-closures adapter relation-cache collected)
         :child-consumers
         {:local child-consumer-index
          :permissions (permission-consumers dependency-certificate)}
         :leaf-descriptors
         (into (sorted-map)
               (for [[permission data] enriched]
                 [permission
                  (into (sorted-map)
                        (keep (fn [{:keys [id descriptor]}]
                                (when descriptor [id descriptor])))
                        (:nodes data))]))
         :costs costs
         :covers (into (sorted-map)
                       (map (fn [[permission value]]
                              [permission (:covers value)]))
                       programs)
         :generators (into (sorted-map)
                           (map (fn [[permission value]]
                                  [permission (:generators value)]))
                           programs)
         :anchors (into (sorted-map)
                        (map (fn [[permission value]]
                               [permission (:anchors value)]))
                        programs)
         :witness-programs
         (into (sorted-map)
               (map (fn [[permission value]]
                      [permission (:witnesses value)]))
               programs)
         :predicate-programs
         (into (sorted-map)
               (map (fn [[permission value]]
                      [permission (:predicates value)]))
               programs)
         :specializations specializations
         :capability-identity (backend/operator-capability-identity adapter)
         :compatibility-formats expression-policy/compatibility-value
         :versions {:cover cover-version
                    :witness witness-version
                    :predicate predicate-version
                    :physical-policy physical-policy-version}
         :order-contract order-contract}
        fingerprint
        (secure/canonical-tree-digest fingerprint-domain
                                      (fingerprint-input plan))
        ;; Derived fields ride outside the fingerprint and outside the
        ;; cursor-scope digest key list: plan identity is unchanged.
        plan (assoc plan :fingerprint fingerprint)]
    (assoc plan
           :expression-roots (expression-roots plan)
           :certificate-acyclic? (certificate-acyclic? plan)
           :delegated-permissions (delegated-permissions plan)
           :guarded-delegation (guarded-delegation plan))))

(defn seal-plan
  "Returns the existing union-only sealed plan unchanged, or compiles an
  operator plan after the legacy-compatible projection reports that one is
  required. No operator expression state is constructed on the union path."
  [adapter root]
  (try
    (sealed-plan/seal-plan adapter root)
    (catch #?(:clj Exception :cljs :default) error
      (if (= :eacl.schema/operator-plan-required (:type (ex-data error)))
        (compile-operator-plan adapter root
                               (collect-expression-closure adapter root))
        (throw error)))))

(defn validate-plan
  "Fails closed unless an operator plan is closed, internally fingerprinted,
  and exactly equal to a fresh compile against the selected immutable basis."
  [adapter plan]
  (when-not (operator-plan? plan)
    (compile-error! :not-an-operator-plan
                    "Value is not a supported operator plan."
                    {:value plan}))
  (when-not (= operator-plan-keys (set (keys plan)))
    (compile-error! :unknown-or-missing-plan-fields
                    "Operator plan has unknown or missing fields."
                    {:expected-keys operator-plan-keys
                     :actual-keys (set (keys plan))}))
  (let [actual-fingerprint
        (secure/canonical-tree-digest fingerprint-domain
                                      (fingerprint-input plan))]
    (when-not (= actual-fingerprint (:fingerprint plan))
      (compile-error! :fingerprint-mismatch
                      "Operator plan fingerprint does not authenticate its fields."
                      {:expected actual-fingerprint
                       :actual (:fingerprint plan)})))
  (let [fresh (seal-plan adapter (:root plan))]
    (when-not (operator-plan? fresh)
      (compile-error! :stale-operator-plan
                      "Selected schema no longer compiles to an operator plan."
                      {:root (:root plan)}))
    (when-not (= fresh plan)
      (compile-error! :stale-operator-plan
                      "Operator plan does not match the selected schema basis."
                      {:root (:root plan)
                       :expected-fingerprint (:fingerprint fresh)
                       :actual-fingerprint (:fingerprint plan)}))
    plan))

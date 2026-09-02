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
(def fingerprint-domain "eacl/operator-plan/v1")
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
    :fingerprint})

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

(defn ^:no-doc expression-roots [plan]
  (into {} (map (juxt :permission :root)) (:expressions plan)))

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
  (first (filter #(= subject-type (:subject-type %))
                 (:partitions descriptor))))

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
     (secure/canonical-digest "eacl/operator-expression/v1" dag)
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

(defn- fingerprint-records [plan]
  (let [without-fingerprint (dissoc plan :fingerprint)]
    (into [[:header (:format plan) (:version plan) (:root plan)]]
          (for [key (sort-by str (keys without-fingerprint))]
            [:field key (get without-fingerprint key)]))))

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
        (secure/canonical-records-digest fingerprint-domain
                                         (fingerprint-records plan))]
    (assoc plan :fingerprint fingerprint)))

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
        (secure/canonical-records-digest fingerprint-domain
                                         (fingerprint-records plan))]
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

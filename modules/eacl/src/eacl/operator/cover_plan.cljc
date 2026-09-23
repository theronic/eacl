(ns eacl.operator.cover-plan
  "Compiles every operator expression node into the unchanged acyclic
  least-path rule domain. Operator composition becomes synthetic
  self-permission edges, so an execution-time local predicate can make each
  child generator exact before the parent consumes its witness."
  (:require [eacl.authorization.data :as qualification-data]
            [eacl.backend.v8 :as backend]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.operator.plan :as operator-plan]))

(def ^:private synthetic-tag :eacl.operator.cover/node)

(defn- permission-id [permission node-id]
  (str "operator-cover:" (pr-str permission) ":" node-id))

(defn- synthetic-name [permission node-id]
  [synthetic-tag permission node-id])

(defn- synthetic-node [[resource-type :as permission] node-id]
  [resource-type (synthetic-name permission node-id)])

(defn- node-maps [plan]
  (let [forward
        (into {}
              (mapcat
               (fn [{:keys [permission dag]}]
                 (map (fn [node-id]
                        [[permission node-id]
                         (synthetic-node permission node-id)])
                      (range (count (:nodes dag)))))
               (:expressions plan)))]
    {:semantic->synthetic forward
     :synthetic->semantic (into {} (map (fn [[k v]] [v k])) forward)}))

(defn- base-definition
  [[resource-type permission-name :as permission] node-id]
  {:permission-id (permission-id permission node-id)
   :resource-type resource-type
   :permission-name permission-name})

(defn- target-root-node [plan semantic->synthetic permission]
  (let [root (get (operator-plan/expression-roots plan) permission)]
    (or (get semantic->synthetic [permission root])
        (throw
         (ex-info "Operator cover target permission is outside the plan."
                  {:type :eacl.operator/invalid-cover
                   :eacl/error :eacl.operator/invalid-cover
                   :permission permission})))))

(defn- leaf-definitions
  [plan semantic->synthetic permission node-id synthetic]
  (let [predicate (get-in plan [:predicate-programs permission node-id])
        common (base-definition synthetic node-id)]
    (case (:instruction predicate)
      :direct-membership
      [(assoc common
              :source-relation-name :self
              :target-type :relation
              :target-name (get-in predicate [:descriptor :relation]))]

      :permission-membership
      (let [target (target-root-node
                    plan semantic->synthetic (:target-node predicate))]
        [(assoc common
                :source-relation-name :self
                :target-type :permission
                :target-name (second target))])

      :arrow-membership
      (mapv
       (fn [{:keys [intermediate-type target-kind target-name target-node]}]
         (assoc common
                :source-relation-name (get-in predicate
                                              [:descriptor :relation])
                :source-subject-type intermediate-type
                :target-type target-kind
                :target-name
                (if (= :permission target-kind)
                  (second (target-root-node
                           plan semantic->synthetic target-node))
                  target-name)))
       (get-in predicate [:descriptor :partitions]))

      (throw
       (ex-info "Operator cover leaf has a non-leaf predicate."
                {:type :eacl.operator/invalid-cover
                 :eacl/error :eacl.operator/invalid-cover
                 :permission permission :node-id node-id
                 :instruction (:instruction predicate)})))))

(defn- child-definition [synthetic node-id target]
  [(assoc (base-definition synthetic node-id)
          :source-relation-name :self
          :target-type :permission
          :target-name (second target))])

(defn- node-definitions
  [plan semantic->synthetic [permission node-id :as _semantic] synthetic]
  (let [{:keys [source-node source-nodes]}
        (get-in plan [:generators permission node-id])]
    (cond
      (seq source-nodes)
      (vec
       (mapcat
        (fn [child-id]
          (child-definition synthetic node-id
                            (get semantic->synthetic
                                 [permission child-id])))
        source-nodes))

      (and (some? source-node) (not= source-node node-id))
      (child-definition synthetic node-id
                        (get semantic->synthetic
                             [permission source-node]))

      :else
      (leaf-definitions plan semantic->synthetic permission node-id
                        synthetic))))

(defn- forwarding-operation [adapter operation]
  (case operation
    (:subject->resources :resource->subjects)
    (backend/scan-invoker adapter operation)

    :direct-match?
    (backend/direct-match-invoker adapter)

    (fn [& arguments]
      (apply backend/invoke adapter operation arguments))))

(defn- cover-definitions
  "The per-node cover's `:permission-defs`: each synthetic node's rows."
  [operator-plan {:keys [semantic->synthetic synthetic->semantic]}]
  (fn [resource-type permission-name]
    (let [synthetic [resource-type permission-name]
          semantic (get synthetic->semantic synthetic)]
      (when-not semantic
        (throw
         (ex-info
          "Least-path requested an unknown operator cover node."
          {:type :eacl.operator/invalid-cover
           :eacl/error :eacl.operator/invalid-cover
           :node synthetic})))
      (node-definitions operator-plan semantic->synthetic
                        semantic synthetic))))

(defn- wrapper-adapter
  "The base adapter, with `permission-defs` serving synthetic definitions.
  `fingerprint` names what the definitions were derived from."
  [adapter fingerprint permission-defs]
  (let [operations
        (into {}
              (map (fn [operation]
                     [operation (forwarding-operation adapter operation)]))
              backend/required-snapshot-operations)
        operations
        (assoc operations
               :schema-generation
               (forwarding-operation adapter :schema-generation)
               :permission-defs permission-defs)
        operations
        (cond-> operations
          (backend/supports? adapter :qualification qualification-data/capability)
          (assoc :qualification-data (forwarding-operation adapter :qualification-data))

          (backend/supports? adapter :cache-proofs :ordered-generations)
          (assoc :proof-frame (forwarding-operation adapter :proof-frame))

          (backend/supports?
           adapter :direct-membership-batch
           backend/direct-membership-batch-capability)
          (assoc :direct-match-many?
                 (forwarding-operation adapter :direct-match-many?)))]
    (backend/make-adapter
     (cond->
      {:id (backend/backend-id adapter)
       :capabilities (backend/capabilities adapter)
       :traversal-execution (backend/traversal-execution adapter)
       :fingerprint (merge {:base (backend/fingerprint adapter)} fingerprint)
       :deterministic? (backend/deterministic? adapter)
       :identity-contract (backend/identity-contract adapter)
       ;; The forwarding operations already run the base adapter's runtime
       ;; guards (scan-invoker/direct-match-invoker capture them), and the
       ;; synthetic node-definition operations serve trusted plan data.
       ;; Re-enabling guards here re-validated every scanned value a second
       ;; time on the operator cover path.
       :runtime-guards? false
       :state (backend/state adapter)
       :operations operations}
       (backend/supports?
        adapter :direct-membership-batch
        backend/direct-membership-batch-capability)
       (assoc :operator-physical-policy
              (get-in (backend/operator-capability-identity adapter)
                      [:direct-membership :physical-policy]))))))

(defn seal-plan
  ([adapter plan]
   (seal-plan adapter plan (:root plan)))
  ([adapter plan permission]
   (when-not (operator-plan/operator-plan? plan)
     (throw
      (ex-info "Raw cover sealing requires an operator plan."
               {:type :eacl.operator/invalid-cover
                :eacl/error :eacl.operator/invalid-cover})))
   (let [{:keys [semantic->synthetic synthetic->semantic] :as maps}
         (node-maps plan)
         root-id (get (operator-plan/expression-roots plan) permission)
         root (get semantic->synthetic [permission root-id])]
     (when-not root
       (throw
        (ex-info "Operator cover root permission is outside the plan."
                 {:type :eacl.operator/invalid-cover
                  :eacl/error :eacl.operator/invalid-cover
                  :permission permission})))
     (let [cover-plan
           (sealed-plan/seal-plan
            (wrapper-adapter adapter {:operator-cover (:fingerprint plan)}
                             (cover-definitions plan maps))
            root)
           allowed (set (get-in plan [:relation-closures permission :all]))
           outside (vec (remove allowed
                                (sealed-plan/relation-ids cover-plan)))]
       (when (seq outside)
         (throw
          (ex-info "Raw cover reads outside the operator dependency closure."
                   {:type :eacl.operator/invalid-cover
                    :eacl/error :eacl.operator/invalid-cover
                    :outside-relation-ids outside})))
       (assoc cover-plan
              :operator-semantic->synthetic semantic->synthetic
              :operator-synthetic->semantic synthetic->semantic
              :operator-root-semantic [permission root-id])))))

;; ---------------------------------------------------------------------------
;; Flattened generators
;; ---------------------------------------------------------------------------

(def ^:private generator-tag :eacl.operator.generator/node)

(defn- generator-name [permission]
  [generator-tag permission])

(defn- generated-permission
  "The permission a flattened generator node's name stands for, or nil."
  [permission-name]
  (when (and (vector? permission-name)
             (= 2 (count permission-name))
             (= generator-tag (first permission-name)))
    (second permission-name)))

(defn ^:no-doc generator-terms
  "The cover leaves of `permission`'s expression, in expression order, each
  once: a union contributes every child, an intersection its sealed anchor,
  and an exclusion its left operand. Every result of `permission` holds at
  least one of them. Pure over sealed fields."
  [plan permission]
  (let [covers (get-in plan [:covers permission])]
    (letfn [(leaves [node-id]
              (let [{:keys [kind source-node source-nodes]} (get covers node-id)]
                (case kind
                  :union (mapcat leaves source-nodes)
                  :child (leaves source-node)
                  [node-id])))]
      (vec (distinct (leaves (get (operator-plan/expression-roots plan)
                                  permission)))))))

(defn ^:no-doc generator-definitions
  "The definition rows of `permission`'s flattened generator node: one per
  cover leaf, or one per partition of an arrow leaf, deduplicated in order.
  A reference to a delegated permission names that permission, so the
  union engine seals its own rules. A reference to any other permission
  names that permission's generator node."
  [plan delegated [resource-type :as permission]]
  (let [reference (fn [target]
                    (if (contains? delegated target)
                      (second target)
                      (generator-name target)))
        common {:permission-id (str "operator-generator:" (pr-str permission))
                :resource-type resource-type
                :permission-name (generator-name permission)}]
    (into []
          (comp
           (mapcat
            (fn [node-id]
              (let [predicate (get-in plan [:predicate-programs permission node-id])]
                (case (:instruction predicate)
                  :direct-membership
                  [(assoc common
                          :source-relation-name :self
                          :target-type :relation
                          :target-name (get-in predicate [:descriptor :relation]))]

                  :permission-membership
                  [(assoc common
                          :source-relation-name :self
                          :target-type :permission
                          :target-name (reference (:target-node predicate)))]

                  :arrow-membership
                  (mapv
                   (fn [{:keys [intermediate-type target-kind target-name target-node]}]
                     (assoc common
                            :source-relation-name (get-in predicate [:descriptor :relation])
                            :source-subject-type intermediate-type
                            :target-type target-kind
                            :target-name (if (= :permission target-kind)
                                           (reference target-node)
                                           target-name)))
                   (get-in predicate [:descriptor :partitions]))

                  (throw
                   (ex-info "Operator generator leaf has a non-leaf predicate."
                            {:type :eacl.operator/invalid-cover
                             :eacl/error :eacl.operator/invalid-cover
                             :permission permission :node-id node-id
                             :instruction (:instruction predicate)}))))))
           (distinct))
          (generator-terms plan permission))))

(defn- generator-adapter
  [adapter plan delegated]
  (let [base-definitions (forwarding-operation adapter :permission-defs)
        generated (into #{}
                        (comp (map :permission)
                              (remove #(contains? delegated %)))
                        (:expressions plan))]
    (wrapper-adapter
     adapter
     {:operator-generator (:fingerprint plan)}
     (fn [resource-type permission-name]
       (let [permission (generated-permission permission-name)]
         (cond
           (and permission
                (= resource-type (first permission))
                (contains? generated permission))
           (generator-definitions plan delegated permission)

           (contains? delegated [resource-type permission-name])
           (base-definitions resource-type permission-name)

           :else
           (throw
            (ex-info "The sealer requested an unknown operator generator node."
                     {:type :eacl.operator/invalid-cover
                      :eacl/error :eacl.operator/invalid-cover
                      :node [resource-type permission-name]}))))))))

(defn seal-generator
  "Seals the flattened generator of a recursive operator plan whose
  union-only permissions are `delegated`
  (`operator-plan/delegated-permissions`).

  The generator has one synthetic union node per other permission the root's
  cover reaches, and it reaches the delegated permissions' own union rules.
  It generates the same set as the per-node cover of `seal-plan`, through one
  node per permission instead of one per expression node. Reads outside the
  root's relation closure are rejected."
  [adapter plan delegated]
  (when-not (operator-plan/operator-plan? plan)
    (throw
     (ex-info "Generator sealing requires an operator plan."
              {:type :eacl.operator/invalid-cover
               :eacl/error :eacl.operator/invalid-cover})))
  (let [[resource-type :as permission] (:root plan)
        generator-plan (sealed-plan/seal-plan
                        (generator-adapter adapter plan delegated)
                        [resource-type (generator-name permission)])
        allowed (set (get-in plan [:relation-closures permission :all]))
        outside (vec (remove allowed
                             (sealed-plan/relation-ids generator-plan)))]
    (when (seq outside)
      (throw
       (ex-info "Operator generator reads outside the operator dependency closure."
                {:type :eacl.operator/invalid-cover
                 :eacl/error :eacl.operator/invalid-cover
                 :outside-relation-ids outside})))
    (assoc generator-plan
           :operator-root-semantic
           [permission (get (operator-plan/expression-roots plan) permission)])))

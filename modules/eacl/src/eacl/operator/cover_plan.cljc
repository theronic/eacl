(ns eacl.operator.cover-plan
  "Compiles every operator expression node into the unchanged acyclic
  least-path rule domain. Operator composition becomes synthetic
  self-permission edges, so an execution-time local predicate can make each
  child generator exact before the parent consumes its witness."
  (:require [eacl.backend.v8 :as backend]
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

(defn- wrapper-adapter
  [adapter operator-plan {:keys [semantic->synthetic synthetic->semantic]}]
  (let [operations
        (into {}
              (map (fn [operation]
                     [operation (forwarding-operation adapter operation)]))
              backend/required-snapshot-operations)
        operations
        (assoc operations
               :schema-generation
               (forwarding-operation adapter :schema-generation)
               :permission-defs
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
        operations
        (cond-> operations
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
       :fingerprint {:base (backend/fingerprint adapter)
                     :operator-cover (:fingerprint operator-plan)}
       :deterministic? (backend/deterministic? adapter)
       :identity-contract (backend/identity-contract adapter)
       :runtime-guards? (backend/runtime-guards? adapter)
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
           (sealed-plan/seal-plan (wrapper-adapter adapter plan maps) root)
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

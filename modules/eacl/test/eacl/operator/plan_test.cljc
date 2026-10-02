(ns eacl.operator.plan-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.operator.cover-plan :as cover-plan]
            [eacl.operator.plan :as plan]
            [eacl.schema.expression :as expression]
            [eacl.schema.expression-persistence :as persistence]
            [eacl.schema.expression-resolver :as resolver]))

(def union-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     permission view = reader + writer
   }")

(def direct-operator-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission view = (reader & writer) - banned
   }")

(def swapped-intersection-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission view = (writer & reader) - banned
   }")

(def swapped-exclusion-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission view = banned - (reader & writer)
   }")

(def nested-schema
  "definition user {}
   definition group {
     relation member: user
     relation disabled: user
     permission blocked = disabled
     permission active = member - blocked
   }
   definition document {
     relation reader: user
     relation parent: group
     relation banned: user
     permission denied = banned
     permission inherited = parent->active
     permission view = (reader & inherited) - denied
   }")

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(defn- relation-key [relation]
  [(:eacl.relation/resource-type relation)
   (:eacl.relation/relation-name relation)
   (:eacl.relation/subject-type relation)])

(defn- adapter
  ([schema-source backend-id]
   (adapter schema-source backend-id false))
  ([schema-source backend-id native-batch?]
   (let [validated (resolver/validate-schema schema-source)
         candidate (persistence/candidate-schema validated)
         relation-rows
         (->> (:relations candidate)
              (sort-by relation-key)
              (map-indexed
               (fn [index relation]
                 {:relation-id (+ 100 index)
                  :resource-type (:eacl.relation/resource-type relation)
                  :relation-name (:eacl.relation/relation-name relation)
                  :subject-type (:eacl.relation/subject-type relation)}))
              vec)
         relations (group-by (juxt :resource-type :relation-name)
                             relation-rows)
         expressions
         (into {}
               (map (fn [entity]
                      [[(:eacl.permission/resource-type entity)
                        (:eacl.permission/permission-name entity)]
                       entity]))
               (:permissions candidate))
         required-stubs
         (into {}
               (map #(vector % (fn [& _] nil)))
               backend/required-snapshot-operations)
         operations
         (merge
          required-stubs
          {:snapshot-id (constantly {:snapshot backend-id})
           :basis-kind (constantly :ordinary)
           :native-revision (constantly {:revision 1})
           :order-hint (constantly 1)
           :exact-locator (constantly nil)
           :object-id->internal identity
           :internal-id->object identity
           :relation-defs
           (fn [resource-type relation-name]
             (mapv #(select-keys % [:relation-id :resource-type
                                     :relation-name :subject-type])
                   (get relations [resource-type relation-name] [])))
           :permission-expression
           (fn [resource-type permission-name]
             (get expressions [resource-type permission-name]))
           :permission-defs
           (fn [resource-type permission-name]
             (when-let [entity (get expressions
                                    [resource-type permission-name])]
               (persistence/union-compatible-definitions
                (:eacl/id entity)
                (persistence/decode-entity entity))))
           :subject->resources (fn [& _] [])
           :resource->subjects (fn [& _] [])
           :direct-match? (fn [& _] false)
           :all-permission-nodes (constantly (set (keys expressions)))})
         operations (cond-> operations
                      native-batch?
                      (assoc :direct-match-many?
                             (fn [{:keys [candidates]}]
                               (vec (repeat (count candidates) false)))))]
     (backend/make-adapter
      {:id backend-id
       :capabilities
       (cond-> backend/empty-capabilities
         native-batch?
         (assoc :direct-membership-batch
                #{backend/direct-membership-batch-capability}))
       :operator-physical-policy
       (when native-batch?
         {:id :fake-native-policy-v1
          :parameters {:maximum-width 256}})
       :operations operations}))))

(deftest union-only-plan-remains-byte-identical-test
  (let [adapter (adapter union-schema :fake-union)]
    (is (= (sealed-plan/seal-plan adapter [:document :view])
           (plan/seal-plan adapter [:document :view])))
    (is (not (plan/operator-plan?
              (plan/seal-plan adapter [:document :view]))))))

(deftest commutative-dag-and-plan-identity-test
  (let [left (plan/seal-plan
              (adapter direct-operator-schema :backend-a)
              [:document :view])
        right (plan/seal-plan
               (adapter swapped-intersection-schema :backend-b)
               [:document :view])]
    (is (= left right))
    (is (= (:fingerprint left) (:fingerprint right)))
    (is (= (:anchors left) (:anchors right)))
    (is (= (:witness-programs left) (:witness-programs right)))
    (is (= plan/order-contract (:order-contract left)))))

(deftest ordered-exclusion-and-complete-evidence-test
  (let [operator-plan
        (plan/seal-plan (adapter direct-operator-schema :operator)
                        [:document :view])
        reversed
        (plan/seal-plan (adapter swapped-exclusion-schema :operator)
                        [:document :view])
        root-expression (first (:expressions operator-plan))
        root-id (:root root-expression)
        intersection-id
        (get-in operator-plan [:generators [:document :view]
                root-id :source-node])]
    (is (plan/operator-plan? operator-plan))
    (is (not= (:fingerprint operator-plan) (:fingerprint reversed)))
    (is (= :left-anti-filter
           (get-in operator-plan [:generators [:document :view]
                                  root-id :kind])))
    (is (= :direct-k-way-intersection
           (get-in operator-plan [:specializations [:document :view]
                                  intersection-id :kind])))
    (is (= #{:user}
           (set (keys
                 (get-in operator-plan
                         [:specializations [:document :view]
                          intersection-id :typed-partitions])))))
    (is (= 2
           (count (get-in operator-plan
                          [:specializations [:document :view]
                           intersection-id :typed-partitions :user]))))
    (is (= 2 (count (get-in operator-plan
                            [:relation-closures [:document :view]
                             :positive]))))
    (is (= 1 (count (get-in operator-plan
                            [:relation-closures [:document :view]
                             :negative]))))
    (is (= #{:cover :witness :predicate :physical-policy}
           (set (keys (:versions operator-plan)))))))

(deftest nested-arrow-strata-and-signed-closure-test
  (let [operator-plan
        (plan/seal-plan (adapter nested-schema :nested)
                        [:document :view])
        view-closure (get-in operator-plan
                             [:relation-closures [:document :view]])]
    (is (= 1 (get-in operator-plan [:strata [:group :active]])))
    (is (= 1 (get-in operator-plan [:strata [:document :inherited]])))
    (is (= 1 (get-in operator-plan [:strata [:document :view]])))
    (is (= 5 (count (:all view-closure))))
    (is (= 2 (count (:negative view-closure))))
    (is (some (fn [[_ descriptor]] (= :arrow (:kind descriptor)))
              (get-in operator-plan
                      [:leaf-descriptors [:document :inherited]])))
    (is (= #{[:document :view] [:document :inherited]
             [:document :denied] [:group :active] [:group :blocked]}
           (set (map :permission (:expressions operator-plan)))))))

(deftest capability-identity-is-sealed-test
  (let [scalar (plan/seal-plan
                (adapter direct-operator-schema :same-backend false)
                [:document :view])
        native (plan/seal-plan
                (adapter direct-operator-schema :same-backend true)
                [:document :view])]
    (is (= :certified-scalar-fallback-v1
           (get-in scalar [:capability-identity
                           :direct-membership :mode])))
    (is (= backend/direct-membership-batch-capability
           (get-in native [:capability-identity
                           :direct-membership :mode])))
    (is (not= (:fingerprint scalar) (:fingerprint native)))))

(deftest native-policy-identity-survives-cover-adapter-test
  (let [basis (adapter direct-operator-schema :native-cover true)
        operator-plan (plan/seal-plan basis [:document :view])
        cover (cover-plan/seal-plan basis operator-plan)]
    (is (string? (:fingerprint cover)))
    (is (= [[:document :view] (:root (first (:expressions operator-plan)))]
           (:operator-root-semantic cover)))))

(deftest validation-rejects-missing-malformed-and-stale-evidence-test
  (let [basis-adapter (adapter direct-operator-schema :validation)
        operator-plan (plan/seal-plan basis-adapter [:document :view])]
    (is (= operator-plan (plan/validate-plan basis-adapter operator-plan)))
    (is (= :unknown-or-missing-plan-fields
           (:reason (error-data
                     #(plan/validate-plan basis-adapter
                                          (dissoc operator-plan
                                                  :generators))))))
    (is (= :fingerprint-mismatch
           (:reason (error-data
                     #(plan/validate-plan
                       basis-adapter
                       (assoc-in operator-plan
                                 [:dependency-certificate :maximum-stratum]
                                 99))))))
    (is (= :stale-operator-plan
           (:reason
            (error-data
             #(plan/validate-plan
               (adapter swapped-exclusion-schema :validation)
               operator-plan)))))))

(deftest repeated-commutative-operands-are-flattened-deduplicated-and-interned-test
  (let [schema
        "definition user {}
         definition document {
           relation reader: user
           relation writer: user
           permission view = (reader & writer) & reader
         }"
        operator-plan (plan/seal-plan (adapter schema :interning)
                                      [:document :view])
        expression (first (:expressions operator-plan))]
    (is (= 3 (get-in expression [:metrics :node-count])))
    (is (= 2 (count (get-in expression
                            [:dag :nodes (:root expression) 1]))))))

(def ^:private delegation-schema
  "definition user {}
   definition folder {
     relation parent: folder
     relation reader: user
     relation deleter: user
     relation banned: user
     relation eligible: user
     permission readable = reader + parent->readable
     permission granted = deleter + parent->granted
     permission removable = granted & readable
     permission kept = granted - readable
     permission cleared = granted - banned
     permission cleared_readable = cleared & readable
     permission either = (granted + readable) & (granted + reader)
     permission inherited = reader + (parent->inherited & eligible)
   }")

(deftest recursion-inside-union-only-operands-is-delegable-test
  (let [adapter (adapter delegation-schema :delegation)
        seal #(plan/seal-plan adapter [:folder %])
        delegated #(plan/delegated-permissions (seal %))]
    (testing "union-only recursive operands are delegated; operator nodes are not"
      (is (= #{[:folder :granted] [:folder :readable]} (delegated :removable)))
      (is (= #{[:folder :granted] [:folder :readable]} (delegated :kept)))
      (is (= #{[:folder :granted] [:folder :readable]}
             (delegated :cleared_readable))))
    (testing "recursion through an intersection is not delegable"
      (is (nil? (delegated :inherited))))
    (testing "the sealed derived field matches a recomputation and stays
              outside the fingerprint"
      (let [sealed (seal :removable)
            recomputed (dissoc sealed :delegated-permissions)]
        (is (= (:delegated-permissions sealed)
               (plan/delegated-permissions recomputed)))
        (is (= sealed (plan/validate-plan adapter sealed)))
        (is (= (:fingerprint sealed) (:fingerprint (seal :removable))))))))

(def ^:private folded-operator-schema
  "`viewer & viewer` normalizes to `viewer` in the semantic DAG, so `manage`
  looks union-only there; the stored expression still has the intersection,
  which the union engine refuses (EACL-FORMAL-098)."
  "definition user {}
   definition project {
     relation viewer: user
     permission access = manage
     permission manage = (viewer & viewer) + access
   }")

(deftest a-folded-operator-keeps-its-permission-an-operator-permission-test
  (let [sealed (plan/seal-plan (adapter folded-operator-schema :folded) [:project :manage])
        flags (into {} (map (juxt :permission :folded-operator?)) (:expressions sealed))]
    (is (plan/operator-plan? sealed))
    (testing "the stored operator is recorded where the DAG folded it"
      (is (= {[:project :access] nil [:project :manage] true} flags))
      (is (= [[:permission :access] [:relation :viewer [:user]] [:union [0 1]]]
             (get-in (first (filter #(= [:project :manage] (:permission %)) (:expressions sealed)))
                     [:dag :nodes]))))
    (testing "so neither permission is handed to its own union plan"
      (is (nil? (plan/delegated-permissions sealed)))
      (is (= #{} (plan/union-only-permissions sealed)))
      (is (= #{[:project :access] [:project :manage]}
             (:members (plan/guarded-delegation sealed)))))
    (testing "the flag rides inside the fingerprint, and only where it is needed"
      (is (= sealed (plan/validate-plan (adapter folded-operator-schema :folded) sealed)))
      (is (not-any? #(contains? % :folded-operator?)
                    (:expressions (plan/seal-plan (adapter delegation-schema :delegation)
                                                  [:folder :removable])))))))

(def ^:private operand-order-schema
  "definition user {}
   definition team {
     relation member: user
     permission members = member
   }
   definition folder {
     relation parent: folder
     relation team: team
     relation viewer: user
     relation owner: user
     relation banned: user
     relation eligible: user
     permission tree = viewer + parent->tree
     permission gated = viewer & eligible
     permission view = (parent->tree + team->members + team->member + tree + owner + gated + viewer) - banned
   }")

(deftest unions-decide-their-operands-in-static-cost-order-test
  (let [sealed (plan/seal-plan (adapter operand-order-schema :operand-order) [:folder :view])
        program (get-in sealed [:predicate-programs [:folder :view]])
        union-id (some (fn [[id predicate]]
                         (when (and (= :any-true (:instruction predicate)) (< 2 (count (:children predicate))))
                           id))
                       program)
        describe (fn [id]
                   (let [{:keys [instruction descriptor target-node]} (get program id)]
                     (case instruction
                       :direct-membership [:relation (:relation descriptor)]
                       :arrow-membership [:arrow (:relation descriptor)
                                          (mapv #(or (:target-node %) (:target-kind %)) (:partitions descriptor))]
                       :permission-membership [:permission target-node]
                       [instruction])))
        order (plan/operand-order sealed [:folder :view] union-id (get program union-id))]
    (testing "relation leaves, arrows to relations, plain references, recursive ones, operators"
      (is (= [[:relation :owner] [:relation :viewer]
              [:arrow :team [:relation]]
              [:arrow :team [[:team :members]]]
              [:arrow :parent [[:folder :tree]]] [:permission [:folder :tree]]
              [:permission [:folder :gated]]]
             (mapv describe order))))
    (testing "canonical order breaks ties, and the order is a permutation of the children"
      (is (= (sort (:children (get program union-id))) (sort order)))
      (is (every? (fn [[a b]] (or (not= (take 1 (describe a)) (take 1 (describe b))) (< a b)))
                  (partition 2 1 (take 2 order)))))
    (testing "the order is derived outside the fingerprint, and a plan without it keeps the canonical order"
      (is (= sealed (plan/validate-plan (adapter operand-order-schema :operand-order) sealed)))
      (is (= (:children (get program union-id))
             (plan/operand-order (dissoc sealed :operand-orders) [:folder :view] union-id
                                 (get program union-id)))))))

(def ^:private self-operand-order-schema
  "use self
   definition user {}
   definition folder {
     relation parent: folder
     relation viewer: user
     relation banned: user
     permission tree = viewer + parent->tree
     permission view = (parent->tree + tree + self + viewer) - banned
     permission strict = parent->tree & self
   }")

(deftest self-ranks-with-the-relation-leaves-in-the-static-cost-order-test
  ;; `self` reads the definition's identity relation, so a union or an
  ;; intersection decides it with the relation leaves, before every arrow and
  ;; permission reference.
  (let [sealed-for #(plan/seal-plan (adapter self-operand-order-schema :self-operand-order) [:folder %])
        order-of (fn [permission instruction]
                   (let [sealed (sealed-for permission)
                         program (get-in sealed [:predicate-programs [:folder permission]])
                         [node-id predicate]
                         (some (fn [[id predicate]]
                                 (when (= instruction (:instruction predicate)) [id predicate]))
                               program)
                         describe (fn [id]
                                    (let [{:keys [instruction descriptor target-node]} (get program id)]
                                      (case instruction
                                        :direct-membership [:relation (:relation descriptor)]
                                        :arrow-membership [:arrow (:relation descriptor)]
                                        :permission-membership [:permission target-node]
                                        [instruction])))]
                     (mapv describe (plan/operand-order sealed [:folder permission] node-id predicate))))]
    (testing "a union"
      (let [order (order-of :view :any-true)]
        (is (= #{[:relation expression/self-relation] [:relation :viewer]} (set (take 2 order))))
        (is (= #{[:arrow :parent] [:permission [:folder :tree]]} (set (drop 2 order))))))
    (testing "an intersection"
      (is (= [[:relation expression/self-relation] [:arrow :parent]]
             (order-of :strict :all-true))))))

(deftest delegated-generator-follows-the-anchor-and-left-chain-test
  (let [adapter (adapter delegation-schema :delegation-generator)
        generator (fn [permission]
                    (let [sealed (plan/seal-plan adapter [:folder permission])]
                      (plan/delegated-generator
                       sealed (:root sealed) (plan/delegated-permissions sealed))))]
    (is (= [:folder :granted] (generator :removable)) "intersection anchor")
    (is (= [:folder :granted] (generator :kept)) "exclusion left operand")
    (is (= [:folder :granted] (generator :cleared_readable))
        "through an operator permission to its own left operand")
    (is (nil? (generator :either)) "a union anchor fans in")))

(def ^:private guarded-schema
  "definition user {}
   definition folder {
     relation parent: folder
     relation reader: user
     relation deleter: user
     relation eligible: user
     relation blocked: user
     permission readable = reader + parent->readable
     permission granted = deleter + parent->granted
     permission removable = granted & readable
     permission inherited = reader + (parent->inherited & eligible)
     permission pruned = reader + (parent->pruned - blocked)
     permission arrowguard = reader + (parent->arrowguard & parent->readable)
     permission outer = deleter + (parent->outer & inherited)
     permission top = inherited & readable
     permission opguard = reader + (parent->opguard & removable)
     permission shared = reader + (parent->shared & gate)
     permission gate = eligible + parent->shared
   }")

(defn- rule-shape
  "A guarded rule without relation ids: its kind, its target permission, and
  each guard's sign with its alternatives' kinds and targets."
  [rule]
  [(:rule rule) (:target-node rule)
   (mapv (fn [{:keys [sign alternatives]}]
           [sign (mapv (juxt :rule :target-node) alternatives)])
         (:guards rule))])

(deftest linearly-guarded-recursion-is-classified-test
  (let [adapter (adapter guarded-schema :guarded)
        seal #(plan/seal-plan adapter [:folder %])
        guarded #(plan/guarded-delegation (seal %))
        shapes #(update-vals (:rules (guarded %)) (partial mapv rule-shape))]
    (testing "an intersection guard becomes a condition on the recursive edge"
      (is (= #{[:folder :inherited]} (:members (guarded :inherited))))
      (is (= {[:folder :inherited]
              [[:relation nil []]
               [:arrow-permission [:folder :inherited] [[:positive [[:relation nil]]]]]]}
             (shapes :inherited))))
    (testing "an exclusion's right operand becomes a subtracted guard"
      (is (= {[:folder :pruned]
              [[:relation nil []]
               [:arrow-permission [:folder :pruned] [[:negative [[:relation nil]]]]]]}
             (shapes :pruned))))
    (testing "a guard may reach a union-only permission through an arrow"
      (is (= [:positive [[:arrow-oracle [:folder :readable]]]]
             (get-in (shapes :arrowguard) [[:folder :arrowguard] 1 2 0]))))
    (testing "a lower guarded component is a guard of a higher one"
      (is (= #{[:folder :inherited] [:folder :outer]} (:members (guarded :outer))))
      (is (= [:positive [[:oracle [:folder :inherited]]]]
             (get-in (shapes :outer) [[:folder :outer] 1 2 0]))))
    (testing "an operator above a guarded component delegates to it"
      (is (= #{[:folder :inherited]} (:members (guarded :top))))
      (is (= #{[:folder :inherited] [:folder :readable]}
             (plan/delegation (seal :top)))))
    (testing "other shapes are not guarded"
      (is (nil? (guarded :opguard)) "a guard that is an operator permission")
      (is (nil? (guarded :shared)) "an intersection with two recursive children")
      (is (nil? (guarded :removable)) "no operator on a cycle")
      (is (some? (plan/delegated-permissions (seal :removable)))))
    (testing "the sealed derived field matches a recomputation and stays
              outside the fingerprint"
      (let [sealed (seal :outer)]
        (is (= (:guarded-delegation sealed)
               (plan/guarded-delegation (dissoc sealed :guarded-delegation))))
        (is (= sealed (plan/validate-plan adapter sealed)))))))

(ns eacl.operator.plan-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.operator.cover-plan :as cover-plan]
            [eacl.operator.plan :as plan]
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
    (is (= plan/order-contract (:order-contract left)))
    (is (= "qbgt_ARJfd9I6hMnpX1PzI5of7_Nvh7UmYgThMfPBDc"
           (:fingerprint left))
        "the canonical plan fingerprint is identical in CLJ and CLJS")))

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

(ns eacl.operator.evaluator-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.cache.key :as cache-key]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.impl :as datascript-impl]
            [eacl.datascript.schema :as datascript-schema]
            [eacl.execution :as execution]
            [eacl.operator.evaluator :as evaluator]
            [eacl.operator.plan :as plan]
            [eacl.subproblem-cache :as subproblem]))

(defn- test-exact-key
  [semantic]
  (let [identity {:tier :denotation
                  :source-lifecycle {:source :test :lifecycle :operator}
                  :abi :test-authorization-v2
                  :semantic semantic
                  :reuse [:basis 1]}]
    (cache-key/exact-denotation-key identity)))

(def direct-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission view = (reader & writer) - banned
   }")

(def arrow-schema
  "definition user {}
   definition group {
     relation member: user
     relation disabled: user
     permission active = member - disabled
   }
   definition document {
     relation reader: user
     relation parent: group
     permission view = reader & parent->active
     permission direct_inherited = reader & parent->member
   }")

(def shared-dag-schema
  "definition user {}
   definition document {
     relation a: user
     relation b: user
     relation c: user
     permission view = (a & b) + (a & c)
   }")

(def recursive-schema
  "definition user {}
   definition folder {
     relation member: user
     relation parent: folder
     permission view = member + (parent->view & member)
   }")

(defn- object [type id]
  (eacl/spice-object type [:eacl/id id]))

(defn- fixture [schema-source objects relationships]
  (let [conn (datascript/create-conn)]
    (datascript-schema/write-schema! conn schema-source)
    (ds/transact! conn
                  (map-indexed
                   (fn [index value]
                     {:db/id (- (inc index))
                      :eacl/id (second (:id value))})
                   objects))
    (doseq [relationship relationships]
      (ds/transact!
       conn
       (datascript-impl/tx-update-relationship
        (ds/db conn)
        {:operation :touch :relationship relationship})))
    (let [db (ds/db conn)]
      {:conn conn
       :db db
       :adapter (datascript-backend/basis-adapter db {})
       :eid #(ds/entid db (:id %))})))

(defn- check
  [{:keys [adapter eid]} operator-plan subject resource & [options]]
  (evaluator/check-eids
   (merge {:adapter adapter
           :plan operator-plan
           :subject-type (:type subject)
           :subject-eid (eid subject)
           :resource-eid (eid resource)}
          options)))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(deftest direct-intersection-and-exclusion-point-matrix-test
  (let [u1 (object :user "u1")
        u2 (object :user "u2")
        documents (mapv #(object :document (str "d" %)) (range 1 5))
        [d1 d2 d3 d4] documents
        relationships
        [(eacl/->Relationship u1 :reader d1)
         (eacl/->Relationship u1 :writer d1)
         (eacl/->Relationship u1 :reader d2)
         (eacl/->Relationship u1 :reader d3)
         (eacl/->Relationship u1 :writer d3)
         (eacl/->Relationship u1 :banned d3)
         (eacl/->Relationship u2 :reader d4)
         (eacl/->Relationship u2 :writer d4)]
        env (fixture direct-schema (into [u1 u2] documents) relationships)
        operator-plan (plan/seal-plan (:adapter env) [:document :view])]
    (is (= [[true false false false]
            [false false false true]]
           (mapv (fn [user]
                   (mapv #(check env operator-plan user %) documents))
                 [u1 u2])))
    (is (false? (evaluator/check-eids
                 {:adapter (:adapter env) :plan operator-plan
                  :subject-type :user :subject-eid nil
                  :resource-eid ((:eid env) d1)})))))

(deftest arrow-to-permission-and-relation-remain-exact-test
  (let [u1 (object :user "u1")
        u2 (object :user "u2")
        g1 (object :group "g1")
        g2 (object :group "g2")
        d1 (object :document "d1")
        d2 (object :document "d2")
        relationships
        [(eacl/->Relationship u1 :reader d1)
         (eacl/->Relationship u1 :reader d2)
         (eacl/->Relationship u1 :member g1)
         (eacl/->Relationship u1 :disabled g1)
         (eacl/->Relationship u1 :member g2)
         (eacl/->Relationship u2 :member g1)
         (eacl/->Relationship g1 :parent d1)
         (eacl/->Relationship g2 :parent d2)]
        env (fixture arrow-schema [u1 u2 g1 g2 d1 d2] relationships)
        permission-plan (plan/seal-plan (:adapter env) [:document :view])
        relation-plan
        (plan/seal-plan (:adapter env) [:document :direct_inherited])]
    (is (false? (check env permission-plan u1 d1)))
    (is (true? (check env permission-plan u1 d2)))
    (is (false? (check env permission-plan u2 d1))
        "the direct reader premise remains required")
    (is (true? (check env relation-plan u1 d1)))
    (is (true? (check env relation-plan u1 d2)))
    (is (false? (check env relation-plan u2 d1)))))

(deftest acyclic-arrow-scans-remain-physical-request-work-test
  (let [u1 (object :user "u1")
        g1 (object :group "g1")
        d1 (object :document "d1")
        relationships
        [(eacl/->Relationship u1 :reader d1)
         (eacl/->Relationship u1 :member g1)
         (eacl/->Relationship g1 :parent d1)]
        env (fixture arrow-schema [u1 g1 d1] relationships)
        operator-plan (plan/seal-plan (:adapter env) [:document :view])
        store (subproblem/store)
        first-stats (atom {})
        first-result
        (binding [subproblem/*store* store
                  subproblem/*exact-denotation-key-fn* test-exact-key
                  evaluator/*evaluation-stats* first-stats]
          (check env operator-plan u1 d1))
        second-stats (atom {})
        second-result
        (binding [subproblem/*store* store
                  subproblem/*exact-denotation-key-fn* test-exact-key
                  evaluator/*evaluation-stats* second-stats]
          (check env operator-plan u1 d1))]
    (is (= true first-result second-result))
    (is (pos? (:adapter-commands @first-stats)))
    (is (= (:adapter-commands @first-stats)
           (:adapter-commands @second-stats)))
    (is (not (contains? (subproblem/stats store) :projection-hits)))
    (is (not (contains? (:tiers (subproblem/stats store)) :projection)))))

(deftest shared-dag-is-memoized-and-short-circuited-test
  (let [user (object :user "u")
        document (object :document "d")
        env
        (fixture shared-dag-schema [user document]
                 [(eacl/->Relationship user :a document)
                  (eacl/->Relationship user :c document)])
        operator-plan (plan/seal-plan (:adapter env) [:document :view])
        stats (atom {})]
    (is (true?
         (binding [evaluator/*evaluation-stats* stats]
           (check env operator-plan user document))))
    (is (= 1 (:memo-hits @stats))
        "the shared :a leaf is physically decided once")
    (is (= 3 (:scalar-equivalent-predicates @stats))
        "short-circuiting avoids no demanded leaf and repeats no shared leaf")))

(deftest selected-branch-failure-and-unselected-branch-elision-test
  (let [user (object :user "u")
        d1 (object :document "d1")
        d2 (object :document "d2")
        env (fixture direct-schema [user d1 d2]
                     [(eacl/->Relationship user :reader d1)
                      (eacl/->Relationship user :reader d2)
                      (eacl/->Relationship user :writer d2)])
        operator-plan (plan/seal-plan (:adapter env) [:document :view])
        original-invoker backend/direct-match-invoker
        banned-id
        (get-in operator-plan
                [:leaf-descriptors [:document :view] 0
                 :partitions 0 :relation-id])
        fail-on-banned
        (fn [adapter]
          (let [direct-match! (original-invoker adapter)]
            (fn [& args]
              (if (= banned-id (nth args 2))
                (throw (ex-info "selected banned failure"
                                {:type :test/failure}))
                (apply direct-match! args)))))]
    (is (false?
         (with-redefs [backend/direct-match-invoker fail-on-banned]
           (check env operator-plan user d1)))
        "false intersection-left evidence never demands exclusion-right")
    (is (= :test/failure
           (:type
            (error-data
             #(with-redefs [backend/direct-match-invoker fail-on-banned]
                (check env operator-plan user d2)))))
        "a demanded exclusion-right error is propagated, never absence")))

(deftest cancellation-limits-and-recursion-fail-typed-test
  (let [user (object :user "u")
        document (object :document "d")
        env (fixture direct-schema [user document]
                     [(eacl/->Relationship user :reader document)
                      (eacl/->Relationship user :writer document)])
        operator-plan (plan/seal-plan (:adapter env) [:document :view])]
    (is (= :transitions
           (:dimension
            (error-data
             #(check env operator-plan user document
                     {:limits {:maximum-transitions 1}})))))
    (let [token (execution/cancellation-token)
          contract (execution/normalize
                    {} :check-permission {:cancellation-token token})]
      (execution/cancel! token)
      (is (= :eacl.execution/cancelled
             (:type
              (error-data
               #(binding [execution/*contract* contract]
                  (check env operator-plan user document))))))))
  (let [user (object :user "u")
        folder (object :folder "f")
        env (fixture recursive-schema [user folder]
                     [(eacl/->Relationship user :member folder)])
        recursive-plan (plan/seal-plan (:adapter env) [:folder :view])]
    (is (= :eacl.operator/recursive-plan-required
           (:type (error-data #(check env recursive-plan user folder)))))))

(ns eacl.formal.qualified.operator-bridge
  "Refines the existing scalar machine independently of the separately
   certified storage/qualification seam. Injected leaf evidence stands for
   all bounded completion sets, faults, and temporal certificates."
  (:require [clojure.test :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.core :as eacl]
            [eacl.formal.qualified.evidence-bridge :as bridge]
            [eacl.formal.qualified.model :as model]
            [eacl.formal.qualified.model-test :as contract]
            [eacl.operator.evaluator :as scalar]
            [eacl.operator.evaluator-test :as fixtures]
            [eacl.operator.plan :as plan]
            [eacl.operator.vector-evaluator :as vector-evaluator]))

(defn demand [op a b]
  (if (or (= :failure (model/kind contract/universe (:value a)))
          (= (if (= op :union) :has :no) (model/kind contract/universe (:value a))))
    a
    (model/combine contract/universe op a b)))

(defn cases []
  (mapv (fn [i value]
          (let [[end complete] (nth [[nil true] [100 true] [100 false]] (mod i 3))]
            (evidence/with-certificate value end complete)))
        (range) (take-nth 3 (bridge/inputs))))

(defn fixture [schema objects relationships]
  ((ns-resolve 'eacl.operator.evaluator-test 'fixture) schema objects relationships))

(defn object [type name] (eacl/spice-object type [:eacl/id name]))
(defn relation [env resource name subject]
  (ds/entid (:db env) [:eacl.relation/resource-type+relation-name+subject-type [resource name subject]]))

(defn assert-refinement! [expected actual]
  (let [observed (bridge/model-evidence actual)]
    (is (= (:value expected) (:value observed)))
    (is (= (:end expected) (:end observed)))
    (is (= (:complete? expected) (:complete? observed)))
    (is (= actual (evidence/decode (evidence/encode actual))))))

(defn vector-result [options]
  (first
   (vector-evaluator/check-many-eids
    (assoc options :candidates [(assoc (select-keys options [:subject-type :subject-eid :resource-eid])
                                       :direction :forward :resource-type (first (:root (:plan options))))]))))

(deftest scalar-intersection-and-exclusion-refinement
  (let [user (object :user "model/user") doc (object :document "model/doc")
        env (fixture fixtures/direct-schema [user doc]
                     (mapv #(eacl/->Relationship user % doc) [:reader :writer :banned]))
        sealed (plan/seal-plan (:adapter env) [:document :view])
        ids (mapv #(relation env :document % :user) [:reader :writer :banned])
        options {:adapter (:adapter env) :plan sealed :subject-type :user
                 :subject-eid ((:eid env) user) :resource-eid ((:eid env) doc)
                 :qualification ::modeled-leaves}]
    (doseq [a (cases) b (cases) c (cases)]
      (let [leaf (zipmap ids [a b c])
            expected (demand :exclusion
                             (demand :intersection (bridge/model-evidence a) (bridge/model-evidence b))
                             (bridge/model-evidence c))]
        (with-redefs [qualification/qualify (fn [_ r e] (if e (get leaf r) false))]
          (assert-refinement! expected (scalar/check-eids options))
          (assert-refinement! expected (vector-result options)))))))

(deftest scalar-arrow-refinement
  (let [user (object :user "model/user") group (object :group "model/group") doc (object :document "model/doc")
        env (fixture fixtures/arrow-schema [user group doc]
                     [(eacl/->Relationship user :reader doc)
                      (eacl/->Relationship group :parent doc)
                      (eacl/->Relationship user :member group)
                      (eacl/->Relationship user :disabled group)])
        sealed (plan/seal-plan (:adapter env) [:document :view])
        ids [(relation env :document :parent :group) (relation env :group :member :user)
             (relation env :group :disabled :user) (relation env :document :reader :user)]
        options {:adapter (:adapter env) :plan sealed :subject-type :user
                 :subject-eid ((:eid env) user) :resource-eid ((:eid env) doc)
                 :qualification ::modeled-leaves}]
    (doseq [via (cases) member (cases) disabled (cases)]
      (let [leaf (zipmap ids [via member disabled true])
            target (demand :exclusion (bridge/model-evidence member) (bridge/model-evidence disabled))
            expected (demand :intersection (bridge/model-evidence true)
                             (demand :arrow (bridge/model-evidence via) target))]
        (with-redefs [qualification/qualify (fn [_ r e] (if e (get leaf r) false))]
          (assert-refinement! expected (scalar/check-eids options))
          (assert-refinement! expected (vector-result options)))))))

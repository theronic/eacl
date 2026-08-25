(ns eacl.operator-engine.oracle-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.operator-engine.oracle :as oracle]))

(def u1 [:user "u1"])
(def u2 [:user "u2"])
(def u3 [:user "same"])
(def service3 [:service "same"])
(def g1 [:group "g1"])
(def d1 [:document "d1"])
(def d2 [:document "d2"])
(def d3 [:document "d3"])

(def acyclic-snapshot
  {:objects #{u1 u2 u3 service3 g1 d1 d2 d3}
   :relation-target-types
   {[:document :parent] #{:group}}
   :relationships
   #{{:resource g1 :relation :member :subject u1}
     {:resource g1 :relation :member :subject u2}
     {:resource g1 :relation :member :subject u3}
     {:resource g1 :relation :suspended :subject u2}
     {:resource d1 :relation :reader :subject u1}
     {:resource d1 :relation :reader :subject u2}
     {:resource d1 :relation :writer :subject service3}
     {:resource d1 :relation :banned :subject u2}
     {:resource d1 :relation :parent :subject g1}
     {:resource d2 :relation :reader :subject u2}
     {:resource d2 :relation :writer :subject u3}
     {:resource d2 :relation :parent :subject g1}
     {:resource d3 :relation :reader :subject u3}}
   :permissions
   {[:group :member]
    [:exclusion [:relation :member] [:relation :suspended]]
    [:document :base]
    [:union [:relation :reader] [:relation :writer]]
    [:document :via-group]
    [:arrow :parent :member]
    [:document :view]
    [:exclusion
     [:intersection
      [:permission :base]
      [:union [:relation :reader] [:permission :via-group]]]
     [:relation :banned]]}})

(deftest acyclic-set-algebra-operation-matrix-test
  (testing "relation, named permission, arrow, union, intersection, exclusion"
    (is (= #{u1 u2 service3}
           (oracle/permission-denotation acyclic-snapshot :base d1)))
    (is (= #{u1 u3}
           (oracle/permission-denotation acyclic-snapshot :via-group d1)))
    (is (= #{u1}
           (oracle/permission-denotation acyclic-snapshot :view d1)))
    (is (= #{u2 u3}
           (oracle/permission-denotation acyclic-snapshot :view d2)))
    (is (= #{u3}
           (oracle/permission-denotation acyclic-snapshot :view d3))))
  (testing "point, forward, reverse, filtering, exact and bounded counts"
    (is (oracle/check? acyclic-snapshot u1 :view d1))
    (is (not (oracle/check? acyclic-snapshot u2 :view d1)))
    (is (= #{d1}
           (oracle/lookup-resources acyclic-snapshot u1 :view :document)))
    (is (= #{d2 d3}
           (oracle/lookup-resources acyclic-snapshot u3 :view :document)))
    (is (= #{u1}
           (oracle/lookup-subjects acyclic-snapshot d1 :view :user)))
    (is (= [d1]
           (oracle/filter-resources acyclic-snapshot [d3 d1 d2] u1 :view)))
    (is (= {:count 2 :limit -1 :truncated? false}
           (oracle/count-resources
            acyclic-snapshot u3 :view :document)))
    (is (= {:count 1 :limit 1 :truncated? true}
           (oracle/count-resources
            acyclic-snapshot u3 :view :document 1)))))

(deftest typed-identities-never-collapse-test
  (is (= #{u1 u2 service3}
         (oracle/permission-denotation acyclic-snapshot :base d1)))
  (is (= #{u1 u2}
         (oracle/lookup-subjects acyclic-snapshot d1 :base :user)))
  (is (= #{service3}
         (oracle/lookup-subjects acyclic-snapshot d1 :base :service))))

(def alice [:user "alice"])
(def bob [:user "bob"])
(def f0 [:folder "f0"])
(def f1 [:folder "f1"])
(def f2 [:folder "f2"])

(def recursive-snapshot
  {:objects #{alice bob f0 f1 f2}
   :relation-target-types {[:folder :parent] #{:folder}}
   :relationships
   #{{:resource f0 :relation :direct :subject alice}
     {:resource f0 :relation :direct :subject bob}
     {:resource f1 :relation :parent :subject f0}
     {:resource f1 :relation :eligible :subject alice}
     {:resource f2 :relation :parent :subject f1}
     {:resource f2 :relation :eligible :subject alice}
     {:resource f2 :relation :banned :subject alice}}
   :permissions
   {[:folder :view]
    [:union
     [:relation :direct]
     [:intersection [:arrow :parent :view] [:relation :eligible]]]
    [:folder :blocked] [:relation :banned]
    [:folder :allowed]
    [:exclusion [:permission :view] [:permission :blocked]]}})

(deftest naive-stratified-fixed-point-test
  (let [{:keys [certificate iterations] :as evaluation}
        (oracle/evaluate-stratified recursive-snapshot)]
    (is (:valid? certificate))
    (is (= 0 (get-in certificate [:strata [:folder :view]])))
    (is (= 0 (get-in certificate [:strata [:folder :blocked]])))
    (is (= 1 (get-in certificate [:strata [:folder :allowed]])))
    (is (pos? (get iterations 0)))
    (is (= #{alice bob}
           (oracle/evaluated-permission-denotation evaluation :view f0)))
    (is (= #{alice}
           (oracle/evaluated-permission-denotation evaluation :view f1)))
    (is (= #{alice}
           (oracle/evaluated-permission-denotation evaluation :view f2)))
    (is (= #{}
           (oracle/evaluated-permission-denotation evaluation :allowed f2)))
    (is (= #{f0 f1 f2}
           (oracle/evaluated-lookup-resources
            recursive-snapshot evaluation alice :view :folder)))
    (is (= #{alice}
           (oracle/evaluated-lookup-subjects evaluation f1 :view :user)))))

(deftest signed-cycle-diagnostics-are-deterministic-test
  (let [snapshot
        {:objects #{f0 alice}
         :relationships #{}
         :permissions
         {[:folder :p]
          [:exclusion [:relation :direct] [:permission :q]]
          [:folder :q] [:permission :p]}}
        first-result (oracle/stratify snapshot)
        second-result (oracle/stratify snapshot)]
    (is (= first-result second-result))
    (is (= :unstratified-exclusion (:error first-result)))
    (is (= [[:folder :p] [:folder :q]]
           (:negative-edge first-result)))
    (is (= [[:folder :p] [:folder :q] [:folder :p]]
           (:cycle first-result)))))

(deftest double-negative-cycle-remains-invalid-test
  (let [snapshot
        {:objects #{f0 alice}
         :relationships #{}
         :permissions
         {[:folder :p]
          [:exclusion [:relation :direct] [:permission :q]]
          [:folder :q]
          [:exclusion [:relation :direct] [:permission :p]]}}
        result (oracle/stratify snapshot)]
    (is (false? (:valid? result)))
    (is (= :unstratified-exclusion (:error result)))))

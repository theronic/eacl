(ns eacl.schema.relation-allowance-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.caveats.definition-test :as errors]
            [eacl.schema.model :as model]
            [eacl.schema.relation-allowance :as allowance]))

(def plain (model/Relation :doc :viewer :user))
(def optional (assoc plain :eacl.relation/caveats [[:eacl.caveat/name "enabled"]]
                     :eacl.relation/allows-unqualified? true))
(def required (assoc optional :eacl.relation/allows-unqualified? false))
(defn delta [before after] {:retractions #{before} :additions #{after}})

(deftest native-relation-alternatives-have-one-canonical-schema-shape
  (is (= plain (allowance/canonicalize plain)))
  (is (= optional (allowance/canonicalize (assoc optional :eacl.relation/caveats [{:eacl.caveat/name "enabled"}]))))
  (is (= #{nil "enabled"} (allowance/names optional)))
  (is (= #{"enabled"} (allowance/names required)))
  (doseq [bad [(dissoc optional :eacl.relation/caveats)
               (dissoc optional :eacl.relation/allows-unqualified?)
               (assoc optional :eacl.relation/caveats [])
               (assoc optional :eacl.relation/caveats [[:eacl.caveat/name "enabled"] [:eacl.caveat/name "enabled"]])]]
    (is (= :eacl.schema/invalid-relation-allowance (errors/error-type #(allowance/names bad))))))

(deftest allowance-updates-retain-logical-relation-identity
  (is (empty? (allowance/entity-deletions (delta plain optional))))
  (is (= [plain] (vec (allowance/entity-deletions {:retractions #{plain} :additions #{}}))))
  (is (= [] (allowance/attribute-retractions (delta optional required))))
  (is (= #{[:db/retract [:eacl/id (:eacl/id plain)] :eacl.relation/caveats [:eacl.caveat/name "enabled"]]
           [:db/retract [:eacl/id (:eacl/id plain)] :eacl.relation/allows-unqualified? true]}
         (set (allowance/attribute-retractions (delta optional plain))))))

(deftest tightening-relation-alternatives-validates-retained-relationships
  (is (true? (allowance/validate-existing! (delta plain optional) (constantly [nil]))))
  (is (true? (allowance/validate-existing! (delta optional required) (constantly ["enabled"]))))
  (doseq [[before after references] [[plain required [nil]] [optional plain ["enabled"]] [optional required [nil "enabled"]]]]
    (is (= :eacl.schema/relationship-qualifier-in-use
           (errors/error-type #(allowance/validate-existing! (delta before after) (constantly references)))))))

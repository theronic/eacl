(ns eacl.engine.wildcard-membership-test
  "The wildcard rule/partition seam shared with the pending wildcard PR.
   Construct its sealed representations directly so the optimization remains
   testable before the schema/parser support is merged."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.walk :as walk]
            [eacl.engine.sealed-plan :as sealed]
            [eacl.engine.stable-route :as route]
            [eacl.operator.plan :as operator]
            [eacl.test-support.tuple-adapter :as tuples]))

(def ^:private schema
  "definition user {}
   definition folder {
     relation parent: folder
     relation reader: user
     relation blocked: user
     permission readable = reader + parent->readable
     permission arrow = parent->reader + parent->arrow
     permission pruned = reader + (parent->pruned - blocked)
   }")

(defn- with-wildcards [plan]
  (update-in plan [:indexes :reverse-rules]
             (fn [index]
               (update-vals index
                            #(into []
                                   (mapcat (fn [rule]
                                             (cond-> [rule]
                                               (contains? #{:relation :arrow-relation} (:rule rule))
                                               (conj (assoc rule :wildcard-eid 999)))))
                                   %)))))

(deftest membership-keeps-concrete-and-wildcard-holdings-separate
  (let [adapter (tuples/from-schema schema
                                    #{[:user 999 :reader :folder 100]
                                      [:user 999 :reader :folder 102]
                                      [:user 300 :reader :folder 104]
                                      [:folder 100 :parent :folder 101]
                                      [:folder 102 :parent :folder 103]})]
    (doseq [limit [256 1]
            [permission expected] [[:readable [true true true true true false]]
                                   [:arrow [false true false true false false]]]]
      (testing (str permission " with holdings limit " limit)
        (with-redefs [route/holdings-limit limit]
          (let [plan (with-wildcards (sealed/seal-plan adapter [:folder permission]))
                options {:adapter adapter :plan plan :subject-type :user :subject-eid 300
                         :resource-eids [100 101 102 103 104 105]}]
            (is (= expected (route/check-many-eids options)))
            (is (= (vec (repeat 6 false))
                   (route/check-many-eids (assoc options :subject-type :robot))))))))))

(deftest guarded-compilation-retains-wildcard-bans
  (let [adapter (tuples/from-schema schema
                                    #{[:user 300 :reader :folder 100]
                                      [:user 999 :blocked :folder 101]
                                      [:folder 100 :parent :folder 101]
                                      [:folder 100 :parent :folder 102]})
        plan (-> (operator/seal-plan adapter [:folder :pruned])
                 (dissoc :guarded-delegation)
                 (update :predicate-programs
                         #(walk/postwalk
                           (fn [value]
                             (if (and (map? value) (some #{:relation-id} (keys value))
                                      (= :user (:subject-type value)))
                               (assoc value :wildcard-eid 999)
                               value))
                           %)))
        program (operator/guarded-program plan [:folder :pruned])]
    (doseq [limit [256 1]]
      (with-redefs [route/holdings-limit limit]
        (is (= [true false true]
               (route/check-many-eids {:adapter adapter :plan program :subject-type :user
                                       :subject-eid 300 :resource-eids [100 101 102]})))))))

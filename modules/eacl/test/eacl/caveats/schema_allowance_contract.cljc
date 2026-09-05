(ns eacl.caveats.schema-allowance-contract
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is]]
            [eacl.caveats.definition-test :as errors]
            [eacl.caveats.schema-admission-test :as schemas]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]))

(defn check! [{:keys [client writer read-schema entid interleave!]}]
  (let [write-schema! (fn [source]
                        (binding [orchestration/*qualified-authorization-enabled?* true]
                          (eacl/write-schema! client {:schema source})))]
    (write-schema! (schemas/source "user"))
    (let [native (:native (writer))
          with-db (:with-snapshot native)
          tx! (:transact! native)
          relation-id #(with-db (fn [db] (entid db [:eacl.relation/resource-type+relation-name+subject-type [:doc :viewer :user]])))
          generation #(with-db (:generation native))
          relation #(first (:relations (with-db read-schema)))
          original-id (relation-id)
          subject (eacl/spice-object :user "allowance/u")
          resource (eacl/spice-object :doc "allowance/doc")
          relationship (eacl/->Relationship subject :viewer resource)
          qualified (assoc relationship :caveat "enabled" :caveat-context {"flag" true})]
      (tx! [{:eacl/id "allowance/u"} {:eacl/id "allowance/doc"}])
      (eacl/create-relationship! client relationship)
      (write-schema! (schemas/source "user | user with enabled"))
      (is (= original-id (relation-id)))
      (is (true? (:eacl.relation/allows-unqualified? (relation))))
      (is (= [[:eacl.caveat/name "enabled"]] (:eacl.relation/caveats (relation))))
      (let [before (generation)]
        (is (= :eacl.schema/relationship-qualifier-in-use
               (errors/error-type #(write-schema! (schemas/source "user with enabled")))))
        (is (= before (generation))))
      (binding [orchestration/*qualified-authorization-enabled?* true]
        (eacl/write-relationships! client [{:operation :touch :relationship qualified}])
        (when interleave!
          (is (contains? #{:eacl.schema/relationship-qualifier-in-use :eacl.schema/concurrent-write}
                         (errors/error-type
                          #(interleave!
                            (fn [] (eacl/write-relationships! client [{:operation :touch :relationship relationship}]))
                            (fn [] (write-schema! (schemas/source "user with enabled")))))))
          (is (true? (:eacl.relation/allows-unqualified? (relation)))))
        (eacl/write-relationships! client [{:operation :touch :relationship qualified}])
        (write-schema! (schemas/source "user with enabled"))
        (is (= original-id (relation-id)))
        (is (false? (:eacl.relation/allows-unqualified? (relation))))
        (is (= :eacl.qualifier/staged-write
               (errors/error-type #(eacl/write-relationships! client [{:operation :touch :relationship relationship}]))))
        (write-schema! (schemas/source "user | user with enabled"))
        (let [before (generation)]
          (is (= :eacl.schema/relationship-qualifier-in-use
                 (errors/error-type #(write-schema! (schemas/source "user")))))
          (is (= before (generation))))
        (eacl/write-relationships! client [{:operation :touch :relationship relationship}])
        (write-schema! (schemas/source "user"))
        (is (= original-id (relation-id)))
        (is (not (contains? (relation) :eacl.relation/caveats)))
        (is (not (contains? (relation) :eacl.relation/allows-unqualified?)))
        (is (true? (eacl/can? client {:subject subject :permission :view :resource resource})))))))

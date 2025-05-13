(ns eacl.core2-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.fixtures :as fixtures]
            [eacl.core2 :as eacl :refer [with-fresh-conn Relation Relationship Permission can?]]))

(deftest eacl2-tests
  (testing "Relation Helper"
    (is (= #:eacl.relation{:resource-type :product
                           :relation-name :owner
                           :subject-type  :user}
           (Relation :product :owner :user)))
    (testing "We can infer resource type from namespaced relation keyword"
      (is (= #:eacl.relation{:resource-type :product
                             :relation-name :owner
                             :subject-type  :user}
             (Relation :product/owner :user)))))

  (testing "Permission helper"
    (is (= #:eacl.permission{:resource-type :product
                             :permission-name :admin
                             :relation-name :owner}
           (Permission :product :owner :admin)))
    (testing "permission admin Permission can infer resource type from namespaced relation keyword"
      (is (= #:eacl.permission{:resource-type   :product
                               :permission-name :admin
                               :relation-name   :owner}
             (Permission :product/owner :admin)))))

  (testing "fixtures"
    (with-fresh-conn conn eacl/v2-eacl-schema
      (is @(d/transact conn fixtures/base-fixtures))

      (let [db (d/db conn)]
        ":test/user can view their product"
        (is (can? db :test/user :view :test/product))
        "...but :test/user2 can't."
        (is (not (can? db :test/user2 :view :test/product)))

        ":test/user is admin of :test/vpc because they own account"
        (is (can? db :test/user :admin :test/vpc))

        "Sanity check that relations don't affect wrong resources"
        (is (not (can? db :test/user :view :test/company)))

        "User 2 can view Product 2"
        (is (can? db :test/user2 :view :test/product2))

        "User 2 can delete Product 2 because they have product.owner relation"
        (is (can? db :test/user2 :delete :test/product2))
        "...but not :test/user"
        (is (not (can? db :test/user :delete :test/product2)))

        (testing "We can enumerate subjects that can access a resource."
          ; Bug: currently returns the subject itself which needs a fix.
          (is (= #{:test/user :test/team :test/product}
                 (set (mapv :db/ident (eacl/lookup-subjects db :test/product :view)))))

          (testing ":test/user2 is only subject who can delete :test/product2"
            (is (= [:test/user2] (mapv :db/ident (eacl/lookup-subjects db :test/product2 :delete))))))

        (testing "We can enumerate resources with lookup-resources"
          (is (= [:test/product] (mapv :db/ident (eacl/lookup-resources db :test/user :view))))
          (is (= [:test/product2] (mapv :db/ident (eacl/lookup-resources db :test/user2 :view)))))

        (testing "Make user-1 a :product/owner of product-2"
          (is @(d/transact conn [(Relationship :test/user :owner :test/product2)])))

        (let [db (d/db conn)]
          "Now :test/user can also :product/delete product 2"
          (is (can? db :test/user :delete :test/product2))

          ; todo: lookup-resources needs resource type filter.
          (is (= [:test/product :test/product2] (mapv :db/ident (eacl/lookup-resources db :test/user :view))))
          (is (= [:test/user :test/user2] (mapv :db/ident (eacl/lookup-subjects db :test/product2 :delete)))))

        (testing "Now let's delete all :product/owner Relationships for :test/user2"
          (let [db   (d/db conn)
                rels (d/q '[:find [(pull ?rel [* {:eacl/subject [*]}]) ...]
                            :where
                            [?rel :eacl.relationship/subject :test/user2]
                            [?rel :eacl.relationship/relation-name :owner]]
                          db)]
            (is @(d/transact conn (for [rel rels] [:db.fn/retractEntity (:db/id rel)]))))

          (testing "Now only :test/user can access both products."
            (let [db' (d/db conn)]
              (is (= #{:test/user :test/team2 :test/product2}
                     (set (mapv :db/ident (eacl/lookup-subjects db' :test/product2 :view)))))
              (testing ":test/user2 cannot access any products" ; is this correct?
                (is (= [] (mapv :db/ident (eacl/lookup-resources db' :test/user2 :view)))))

              (is (not (can? db' :test/user2 :product/delete :test/product2)))

              (testing ":test/user permissions remain unchanged"
                (is (= [:test/product :test/product2]
                       (mapv :db/ident (eacl/lookup-resources db' :test/user :view))))))))))))
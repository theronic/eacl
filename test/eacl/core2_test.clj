(ns eacl.core2-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.fixtures :as fixtures]
            [eacl.core2 :as eacl :refer [with-fresh-conn Relation Relationship can?]]))

(deftest eacl2-tests
  (testing "fixtures"
    (with-fresh-conn conn eacl/v2-eacl-schema
      @(d/transact conn fixtures/base-fixtures)

      (let [db (d/db conn)]
        ":test/user can view their product"
        (is (can? db :test/user :product/view :test/product))
        "...but :test/user2 can't."
        (is (not (can? db :test/user2 :product/view :test/product)))

        "Sanity check that relations don't affect wrong resources"
        (is (not (can? db :test/user :product/view :test/company)))

        "User 2 can view Product 2"
        (is (can? db :test/user2 :product/view :test/product2))

        "User 2 can delete Product 2 because they have product.owner relation"
        (is (can? db :test/user2 :product/delete :test/product2))
        "...but not :test/user"
        (is (not (can? db :test/user :product/delete :test/product2)))

        (testing "We can enumerate subjects that can access a resource."
          ; Bug: currently returns the subject itself which needs a fix.
          (is (= [:test/user :test/team :test/product]
                 (mapv :db/ident (eacl/lookup-subjects db :test/product :product/view))))

          (testing ":test/user2 is only subject who can delete :test/product2"
            (is (= [:test/user2] (mapv :db/ident (eacl/lookup-subjects db :test/product2 :product/delete))))))

        (testing "We can enumerate resources with lookup-resources"
          (is (= [:test/product] (mapv :db/ident (eacl/lookup-resources db :test/user :product/view))))
          (is (= [:test/product2] (mapv :db/ident (eacl/lookup-resources db :test/user2 :product/view)))))

        (testing "Make user-1 a :product/owner of product-2"
          (is @(d/transact conn [(Relationship :test/user :product/owner :test/product2)])))

        (let [db (d/db conn)]
          "Now :test/user can also :product/delete product 2"
          (is (can? db :test/user :product/delete :test/product2))

          (is (= [:test/product :test/product2] (mapv :db/ident (eacl/lookup-resources db :test/user :product/view))))
          (is (= [:test/user :test/user2] (mapv :db/ident (eacl/lookup-subjects db :test/product2 :product/delete)))))

        (testing "Now let's delete all :product/owner Relationships for :test/user2"
          (let [db   (d/db conn)
                rels (d/q '[:find [(pull ?rel [* {:eacl/subject [*]}]) ...]
                            :where
                            [?rel :eacl/subject :test/user2]
                            [?rel :eacl/relation :product/owner]]
                          db)]
            (is @(d/transact conn (for [rel rels] [:db.fn/retractEntity (:db/id rel)]))))

          (testing "Now only :test/user can access both products."
            (let [db' (d/db conn)]
              (is (= [:test/user :test/team2 :test/product2]
                     (mapv :db/ident (eacl/lookup-subjects db' :test/product2 :product/view))))
              (testing ":test/user2 cannot access any products" ; is this correct?
                (is (= [] (mapv :db/ident (eacl/lookup-resources db' :test/user2 :product/view)))))

              (is (not (can? db' :test/user2 :product/delete :test/product2)))

              (testing ":test/user permissions remain unchanged"
                (is (= [:test/product :test/product2]
                       (mapv :db/ident (eacl/lookup-resources db' :test/user :product/view))))))))))))
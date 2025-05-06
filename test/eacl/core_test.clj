(ns eacl.core-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [datomic.api :as d]
            [hyperfiddle.rcf :as rcf :refer [tests]]
            [eacl.core :as eacl :refer [with-fresh-conn Relation Relationship can? lookup-subjects lookup-resources]]))

;(deftest eacl-tests)
;
(comment
  (rcf/enable!))

(tests
  (with-fresh-conn conn eacl/v2-eacl-schema
                   @(d/transact conn
                                [; Company
                                 {:db/id    "company-1"
                                  :db/ident :test/company}

                                 {:db/id    "company-2"
                                  :db/ident :test/company2}

                                 ; Team
                                 {:db/id    "team-1"
                                  :db/ident :test/team}

                                 {:db/id    "team-2"
                                  :db/ident :test/team2}

                                 ;{:db/id "products"
                                 ; :db/ident :type/products}

                                 ;; Products
                                 {:db/id    "product-1"
                                  :db/ident :test/product}

                                 {:db/id    "product-2"
                                  :db/ident :test/product2}

                                 ; the annoying part of current design is that you need to specify all resources for a relation.
                                 ; I'm looking into a resource type or collective resource e.g. 'products'.
                                 (Relation :product/company ["product-1" "product-2"] [:product/view :product/edit])
                                 (Relation :product/owner ["product-1" "product-2"] [:product/view :product/edit :product/delete])

                                 ;; Users:
                                 {:db/id    "user-1"
                                  :db/ident :test/user}

                                 {:db/id    "user-2"
                                  :db/ident :test/user2}

                                 ;; Relationships:
                                 (Relationship "product-1" :product/company "company-1")
                                 (Relationship "product-2" :product/company "company-2")

                                 ;; Team Membership:
                                 (Relationship "user-1" :team/member "team-1") ; User 1 is on Team 1
                                 ;(Relationship "user-2" :team/member "team-2")

                                 ; User 2 is the direct :product/owner of Product 2
                                 (Relationship "user-2" :product/owner "product-2")

                                 ; Team 1 has control of Company 1
                                 (Relationship "team-1" :team/company "company-1")
                                 (Relationship "team-2" :team/company "company-2")])

                   (let [db (d/db conn)]
                     ":test/user can view their product"
                     (can? db :test/user :product/view :test/product) := true
                     "...but :test/user2 can't."
                     (can? db :test/user2 :product/view :test/product) := false

                     "Sanity check that relations don't affect wrong resources"
                     (can? db :test/user :product/view :test/company) := false

                     "User 2 can view Product 2"
                     (can? db :test/user2 :product/view :test/product2) := true

                     "User 2 can delete Product 2 because they have product.owner relation"
                     (can? db :test/user2 :product/delete :test/product2) := true
                     "...but not :test/user"
                     (can? db :test/user :product/delete :test/product2) := false

                     "We can enumerate subjects that can access a resource."
                     ; Bug: currently returns the subject itself which needs a fix.
                     (map :db/ident (lookup-subjects db :test/product :product/view)) := [:test/user :test/team :test/product]
                     ":test/user2 is only subject who can delete :test/product2"
                     (map :db/ident (lookup-subjects db :test/product2 :product/delete)) := [:test/user2]

                     "We can enumerate resources with lookup-resources"
                     (map :db/ident (lookup-resources db :test/user :product/view)) := [:test/product]
                     (map :db/ident (lookup-resources db :test/user2 :product/view)) := [:test/product2])

                   "Make user-1 a :product/owner of product-2"
                   @(d/transact conn [(Relationship :test/user :product/owner :test/product2)])

                   (let [db (d/db conn)]
                     "Now :test/user can also :product/delete product 2"
                     (can? db :test/user :product/delete :test/product2) := true

                     (map :db/ident (lookup-resources db :test/user :product/view)) := [:test/product :test/product2]
                     (map :db/ident (lookup-subjects db :test/product2 :product/delete)) := [:test/user :test/user2])

                   "Now let's delete all :product/owner Relationships for :test/user2"
                   (let [rels (d/q '[:find [(pull ?rel [* {:eacl/subject [*]}]) ...]
                                     :where
                                     [?rel :eacl/subject :test/user2]
                                     [?rel :eacl/relation :product/owner]]
                                   (d/db conn))]

                     @(d/transact conn (for [rel rels] [:db.fn/retractEntity (:db/id rel)])))

                   "Now only :test/user can access both products."
                   (let [db (d/db conn)]
                     (map :db/ident (lookup-subjects db :test/product2 :product/view)) := [:test/user :test/team2 :test/product2]
                     (map :db/ident (lookup-resources db :test/user2 :product/view)) := []

                     (can? db :test/user2 :product/delete :test/product2) := false

                     ":test/user permissions unchanged"
                     (map :db/ident (lookup-resources db :test/user :product/view)) := [:test/product :test/product2])))
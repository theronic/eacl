(ns eacl.fixtures
  (:require [datomic.api :as d]
            [eacl.core2 :as eacl :refer (Relation Relationship)]))

(def base-fixtures
  [
   ; VPC
   {:db/id         "vpc-1"
    :db/ident      :test/vpc
    :resource/type :vpc}

   {:db/id         "account-1"
    :db/ident      :test/account
    :resource/type :account}

   {:db/id         "account-2"
    :db/ident      :test/account2
    :resource/type :account}

   ; Company
   {:db/id         "company-1"
    :db/ident      :test/company
    :resource/type :company}

   {:db/id         "company-2"
    :db/ident      :test/company2
    :resource/type :company}

   ; Team
   {:db/id         "team-1"
    :db/ident      :test/team
    :resource/type :team}

   {:db/id         "team-2"
    :db/ident      :test/team2
    :resource/type :team}

   ;{:db/id "products"
   ; :db/ident :type/products}

   ;; Products
   {:db/id         "product-1"
    :db/ident      :test/product
    :resource/type :product}

   {:db/id         "product-2"
    :db/ident      :test/product2
    :resource/type :product}

   ; Schema:
   ; the annoying part of current design is that you need to specify all resources for a relation.
   ; I'm looking into a resource type or collective resource e.g. 'products'.
   ; consider: can we handle these inputs as namespaced, i.e. resource type :product + :owner (relation) are unique?
   ; Todo: constrain relation subject types.
   ; OK, we need to decouple permissions from relations.
   (Relation :vpc :vpc/owner [:vpc/admin])
   (Relation :account :account/vpc [:account/admin])
   (Relation :product :product/company [:product/view :product/edit])
   (Relation :product :product/owner [:product/view :product/edit :product/delete])

   ;; Users:
   {:db/id         "user-1"
    :db/ident      :test/user
    :resource/type :user}

   {:db/id         "user-2"
    :db/ident      :test/user2
    :resource/type :user}

   ;; Relationships:
   (Relationship "product-1" :product/company "company-1")
   (Relationship "product-2" :product/company "company-2")

   ;; Team Membership:
   (Relationship "user-1" :team/member "team-1")            ; User 1 is on Team 1
   ;(Relationship "user-2" :team/member "team-2")

   ; User 2 is the direct :product/owner of Product 2
   (Relationship "user-2" :product/owner "product-2")

   ; Team 1 has control of Company 1
   (Relationship "team-1" :team/company "company-1")
   (Relationship "team-2" :team/company "company-2")])
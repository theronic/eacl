(ns eacl.fixtures
  (:require [datomic.api :as d]
            [eacl.core :as eacl :refer (Relation Relationship Permission)]))

(def base-fixtures
  [; Schema
   (Relation :platform/super_admin :user)                   ; means resource-type/relation subject-type, e.g. definition platform { relation super_admin: user }.
   (Permission :platform :super_admin :platform_admin)      ; hack to support platform->admin

   (Relation :vpc/account :account)                         ; vpc, relation account: account.
   ;permission admin = account->admin + shared_admin
   (Permission :vpc :shared_admin :admin)
   (Permission :vpc :account :admin :admin)                 ; vpc/admin = account->admin (arrow syntax)

   ; VPCs:
   (Relation :vpc/owner :user)
   (Permission :vpc/owner :admin)                           ; just admin?

   ; Accounts:
   (Relation :account :owner :user)                         ; Account has an owner (a user)
   (Permission :account :owner :admin)                      ; Owner of account gets admin on account
   (Permission :account :owner :view)
   (Permission :account :platform :platform_admin :admin)   ; hack for platform->super_admin
   (Permission :account :platform :platform_admin :view)    ; spurious.

   ; Teams:
   (Relation :team/account :account)

   ;; Servers:
   (Relation :server/account :account)
   (Permission :server/account :view)
   (Permission :server :account :admin :view)
   (Permission :server :account :admin :delete)
   (Permission :server :account :admin :view)
   (Permission :server/account :edit)
   ; Server Shared Admin:
   (Permission :server/shared_admin :view)
   (Permission :server/shared_admin :admin)
   (Permission :server/shared_admin :delete)

   ;(Relation :server/company :company)
   ; can we combine these into one with multi-cardinality?

   ; these can go away
   (Relation :server/owner :user)
   (Permission :server/owner :view)
   (Permission :server/owner :edit)
   (Permission :server/owner :delete)

   ; Global Platform for Super Admins:
   {:db/id         "platform"
    :entity/id     "platform"
    :db/ident      :test/platform
    :resource/type :platform}

   ; Users:
   {:db/id         "user-1"
    :entity/id     "user-1"
    :db/ident      :test/user1
    :resource/type :user}

   ; Super User can do all the things:
   {:db/id         "super-user"
    :entity/id     "super-user"
    :db/ident      :user/super-user
    :resource/type :user}

   (Relationship "super-user" :super_admin "platform")

   (Relationship "user-1" :member "team-1")                 ; User 1 is on Team 1
   (Relationship "user-1" :owner "account-1")

   {:db/id         "user-2"
    :entity/id     "user-2"
    :db/ident      :test/user2
    :resource/type :user}

   (Relationship "user-2" :owner "account-2")

   ; Accounts
   {:db/id         "account-1"
    :entity/id     "account-1"
    :db/ident      :test/account1
    :resource/type :account}

   (Relationship "platform" :platform "account-1")

   {:db/id         "account-2"
    :entity/id     "account-2"
    :db/ident      :test/account2
    :resource/type :account}

   (Relationship "platform" :platform "account-2")

   ; VPC
   {:db/id         "vpc-1"
    :entity/id     "vpc-1"
    :db/ident      :test/vpc
    :resource/type :vpc}

   (Relationship "account-1" :account "vpc-1")

   {:db/id         "vpc-2"
    :entity/id     "vpc-2"
    :db/ident      :test/vpc2
    :resource/type :vpc}

   (Relationship "account-2" :account "vpc-2")

   ; Team
   {:db/id         "team-1"
    :entity/id     "team-1"
    :db/ident      :test/team
    :resource/type :team}

   ; Teams belongs to accounts
   (Relationship "account-1" :account "team-1")

   {:db/id         "team-2"
    :entity/id     "team-2"
    :db/ident      :test/team2
    :resource/type :team}

   (Relationship "account-2" :account "team-2")

   ;; Servers:
   {:db/id         "server-1"
    :entity/id     "server-1"
    :db/ident      :test/server1
    :resource/type :server}

   (Relationship "account-1" :account "server-1") ; hmm let's check schema plz.

   {:db/id         "server-2"
    :entity/id     "server-2"
    :db/ident      :test/server2
    :resource/type :server}

   (Relationship "account-2" :account "server-2")])

;; Team Membership:

;(Relationship "user-2" :team/member "team-2")




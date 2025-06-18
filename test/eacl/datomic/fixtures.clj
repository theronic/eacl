(ns eacl.datomic.fixtures
  (:require [datomic.api :as d]
            [eacl.core :refer [spice-object]]
            [eacl.datomic.impl :as eacl :refer (Relation Relationship Permission)]))

; These are helpers specific to CA (todo move out):
(def ->user (partial spice-object :user))
(def ->team (partial spice-object :team))
(def ->server (partial spice-object :server))
(def ->platform (partial spice-object :platform))
(def ->account (partial spice-object :account))
(def ->vpc (partial spice-object :vpc))
(def ->backup (partial spice-object :backup))
(def ->host (partial spice-object :host))

(def base-fixtures
  [; Schema
   (Relation :platform :super_admin :user)                   ; means resource-type/relation subject-type, e.g. definition platform { relation super_admin: user }.
   ; definition platform {
   ;   relation super_admin: user;
   ;   permission platform_admin = super_admin   # EACL requires this hack for arrow relations because we traverse permissions->relations. Could be fixed.
   ; }

   (Relation :vpc/account :account)                         ; vpc, relation account: account.
   ;permission admin = account->admin + shared_admin
   (Permission :vpc :shared_admin :admin)
   (Permission :vpc :account :admin :admin)                 ; vpc/admin = account->admin (arrow syntax)

   ; VPCs:
   (Relation :vpc/owner :user)
   (Permission :vpc/owner :admin)                           ; just admin?

   ; Accounts:
   (Relation :account :owner :user)                         ; Account has an owner (a user)
   (Relation :account :platform :platform)

   (Permission :account :owner :admin)                      ; Owner of account gets admin on account
   (Permission :account :owner :view)
   (Permission :account :platform :platform_admin :admin)   ; hack for platform->super_admin.
   (Permission :account :platform :platform_admin :view)    ; spurious.
   (Permission :platform :super_admin :platform_admin)      ; hack to support platform->admin

   ; Teams:
   (Relation :team/account :account)

   ;; Servers:
   (Relation :server/account :account)

   (Permission :server/account :view)

   (Permission :server :account :admin :view)
   (Permission :server :account :admin :delete)
   (Permission :server :account :admin :reboot)
   ;(Permission :server :account :admin :reboot)

   (Permission :server/account :edit)
   ; Server Shared Admin:
   (Permission :server/shared_admin :view)
   (Permission :server/shared_admin :reboot)
   (Permission :server/shared_admin :admin)
   (Permission :server/shared_admin :delete)

   ;(Relation :server/company :company)
   ; can we combine these into one with multi-cardinality?

   ; these can go away
   (Relation :server/owner :user)

   (Permission :server/owner :view)
   ;(Permission :server/owner :reboot)
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

   ; we need to specify types for indices, but id can be tempid here.
   (Relationship (->user "user-1") :member (->team "team-1")) ; User 1 is on Team 1
   (Relationship (->user "user-1") :owner (->account "account-1"))

   ; Super User can do all the things:
   {:db/id         "super-user"
    :entity/id     "super-user"
    :db/ident      :user/super-user
    :resource/type :user}

   (Relationship (->user "super-user") :super_admin (->platform "platform"))

   {:db/id         "user-2"
    :entity/id     "user-2"
    :db/ident      :test/user2
    :resource/type :user}

   (Relationship (->user "user-2") :owner (->account "account-2"))

   ; Accounts
   {:db/id         "account-1"
    :entity/id     "account-1"
    :db/ident      :test/account1
    :resource/type :account}

   (Relationship (->platform "platform") :platform (->account "account-1"))

   {:db/id         "account-2"
    :entity/id     "account-2"
    :db/ident      :test/account2
    :resource/type :account}

   (Relationship (->platform "platform") :platform (->account "account-2"))

   ; VPC
   {:db/id         "vpc-1"
    :entity/id     "vpc-1"
    :db/ident      :test/vpc
    :resource/type :vpc}

   (Relationship (->account "account-1") :account (->vpc "vpc-1"))

   {:db/id         "vpc-2"
    :entity/id     "vpc-2"
    :db/ident      :test/vpc2
    :resource/type :vpc}

   (Relationship (->account "account-2") :account (->vpc "vpc-2"))

   ; Team
   {:db/id         "team-1"
    :entity/id     "team-1"
    :resource/type :team
    :db/ident      :test/team}

   ; Teams belongs to accounts
   (Relationship (->account "account-1") :account (->team "team-1"))

   {:db/id         "team-2"
    :entity/id     "team-2"
    :db/ident      :test/team2
    :resource/type :team}

   (Relationship (->account "account-2") :account (->team "team-2"))

   ;; Servers:
   {:db/id         "account1-server1"
    :entity/id     "account1-server1"
    :db/ident      :test/server1
    :resource/type :server}

   (Relationship (->account "account-1") :account (->server "account1-server1")) ; hmm let's check schema plz.

   {:db/id         "account1-server2"
    :entity/id     "account1-server2"
    :resource/type :server}

   (Relationship (->account "account-1") :account (->server "account1-server2"))

   {:db/id         "account2-server1"
    :entity/id     "account2-server1"
    :db/ident      :test/server2
    :resource/type :server}

   (Relationship (->account "account-2") :account (->server "account2-server1"))])

;; Team Membership:

;(Relationship "user-2" :team/member "team-2")




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
(def ->network (partial spice-object :network))
(def ->lease (partial spice-object :lease))
(def ->nic (partial spice-object :network_interface))
(def ->backup (partial spice-object :backup))
(def ->host (partial spice-object :host))

(def relations+permissions
  [;; Schema
   ; Platform for super_admin rights
   (Relation :platform :super_admin :user) ; definition platform { relation super_admin: user }

   ; VPC Relations:
   (Relation :vpc :shared_admin :user) ; definition vpc { relation shared_admin: user }
   (Relation :vpc :account :account) ; definition vpc { relation account: account }

   ;(Permission :vpc :admin {:relation :owner}) ; direct

   ; model: server -> nic -> lease -> network -> vpc.
   (Relation :server :nic :network_interface) ; a server can have many NICs. definition server { relation nic: network_interface }
   (Relation :network_interface :lease :lease) ; a NIC can have a lease (to an IP). definition network { relation lease: lease }
   (Relation :lease :network :network) ; a lease has a network. definition lease { relation network: network }
   (Relation :network :vpc :vpc) ; a network has a vpc. definition network { relation vpc: vpc }

   ; VPC Permissions:
   ; definition vpc { permission admin = account->admin + shared_admin }
   (Permission :vpc :admin {:relation :shared_admin})
   (Permission :vpc :admin {:arrow :account :permission :admin})

   (Permission :network_interface :view {:relation :lease}) ; direct
   (Permission :network_interface :view {:arrow :lease :permission :view}) ; arrow

   (Permission :lease :view {:relation :network}) ; direct
   (Permission :lease :view {:arrow :network :permission :view}) ; arrow

   (Permission :network :view {:relation :vpc}) ; direct
   (Permission :network :view {:arrow :vpc :permission :view}) ; arrow

   ; definition server { permission view =

   ; Accounts:
   (Relation :account :owner :user) ; Account has an owner (a user)
   (Relation :account :platform :platform)

   ; definition account { permission admin = owner + platform->super_admin }
   (Permission :account :admin {:relation :owner}) ; Owner of account gets admin on account
   (Permission :account :admin {:arrow :platform :relation :super_admin})

   ; definition account { permission view = owner + platform->super_admin }
   (Permission :account :view {:relation :owner})
   (Permission :account :view {:arrow :platform :relation :super_admin})

   ; Special permission for test case:
   ; definition account { permission view_via_arrow_relation = platform->super_admin }
   (Permission :account :view_via_arrow_relation {:arrow :platform :relation :super_admin})

   ; Teams:
   (Relation :team :account :account)

   ;; Servers:
   (Relation :server :account :account)
   (Relation :server :shared_admin :user)
   (Relation :server :owner :user)

   ; definition server { permission view = owner + account + account->admin + nic->view + shared_admin }
   (Permission :server :view {:relation :owner})
   (Permission :server :view {:relation :account})
   (Permission :server :view {:arrow :account :permission :admin})
   (Permission :server :view {:arrow :nic :permission :view})
   (Permission :server :view {:relation :shared_admin})

   ; definition server { permission edit = owner } ; admin?
   (Permission :server :edit {:relation :owner})

   ; definition server { permission delete = account->admin }
   (Permission :server :delete {:arrow :account :permission :admin})

   ; definition server { permission reboot = account->admin + shared_admin }
   (Permission :server :reboot {:arrow :account :permission :admin})
   (Permission :server :reboot {:relation :shared_admin})

   ; definition server { permission admin = account->admin + vpc->admin + shared_admin }
   (Permission :server :admin {:arrow :account :permission :admin})
   (Permission :server :admin {:arrow :vpc :permission :admin})
   (Permission :server :admin {:relation :shared_admin})

   (Permission :server :view_server_via_arrow_relation {:arrow :account :permission :view_via_arrow_relation}) ; special test case.

   ; definition server { permission delete = owner + shared_admin }
   (Permission :server :delete {:relation :owner})
   (Permission :server :delete {:relation :shared_admin})])

(def entity-fixtures
  [; Global Platform for Super Admins:
   {:db/id "platform"
    :db/ident :test/platform
    :eacl/id "platform"}

   ; Users:
   {:db/id "user-1"
    :db/ident :test/user1
    :eacl/id "user-1"}

   {:db/id "user-2"
    :db/ident :test/user2
    :eacl/id "user-2"}

   ; Super User can do all the things:
   {:db/id "super-user"
    :db/ident :user/super-user
    :eacl/id "super-user"}

   ; Accounts
   {:db/id "account-1"
    :db/ident :test/account1
    :eacl/id "account-1"}

   {:db/id "account-2"
    :db/ident :test/account2
    :eacl/id "account-2"}

   ;; Servers
   ;; ...Account 1 Servers:
   {:db/id "account1-server1"
    :db/ident :test/server1
    :eacl/id "account1-server1"}

   {:db/id "account1-server2"
    :eacl/id "account1-server2"}

   ; ...Account 2 Servers:
   {:db/id "account2-server1"
    :db/ident :test/server2
    :eacl/id "account2-server1"}

   ; VPC
   {:db/id "vpc-1"
    :db/ident :test/vpc1
    :eacl/id "vpc-1"}

   {:db/id "vpc-2"
    :db/ident :test/vpc2
    :eacl/id "vpc-2"}

   ;; Networks, NICs & Leases

   {:db/id "network-1"
    :db/ident :test/network1
    :eacl/id "network-1"}

   {:db/id "nic-1"
    :db/ident :test/nic1
    :eacl/id "nic-1"}

   {:db/id "lease-1"
    :db/ident :test/lease1
    :eacl/id "lease-1"}

   ; Team
   {:db/id "team-1"
    :db/ident :test/team
    :eacl/id "team-1"}

   {:db/id "team-2"
    :db/ident :test/team2
    :eacl/id "team-2"}])

(def relationship-fixtures
  [; we need to specify types for indices, but id can be tempid here.
   (Relationship (->user "user-1") :member (->team "team-1")) ; User 1 is on Team 1
   (Relationship (->user "user-1") :owner (->account "account-1"))

   (Relationship (->user "super-user") :super_admin (->platform "platform"))

   (Relationship (->user "user-2") :owner (->account "account-2"))

   (Relationship (->platform "platform") :platform (->account "account-1"))
   (Relationship (->platform "platform") :platform (->account "account-2"))

   (Relationship (->account "account-1") :account (->vpc "vpc-1"))
   (Relationship (->account "account-2") :account (->vpc "vpc-2"))

   ; server -> nic -> lease -> network -> vpc
   (Relationship (->vpc "vpc-1") :vpc (->network "network-1"))
   (Relationship (->network "network-1") :network (->lease "lease-1"))
   (Relationship (->lease "lease-1") :lease (->nic "nic-1"))
   (Relationship (->nic "nic-1") :nic (->server "account1-server1"))

   ; Teams belongs to accounts
   (Relationship (->account "account-1") :account (->team "team-1"))
   (Relationship (->account "account-2") :account (->team "team-2"))

   (Relationship (->account "account-1") :account (->server "account1-server1")) ; hmm let's check schema plz.
   (Relationship (->account "account-1") :account (->server "account1-server2"))

   (Relationship (->account "account-2") :account (->server "account2-server1"))])

(def base-fixtures
  (concat
   relations+permissions
   entity-fixtures
   relationship-fixtures))

;; (For Later) Team Membership:
;(Relationship "user-2" :team/member "team-2")



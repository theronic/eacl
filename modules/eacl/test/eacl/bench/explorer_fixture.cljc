(ns eacl.bench.explorer-fixture
  "Deterministic EACL Explorer-shaped authorization data.

  Five accounts with 2,000 servers each produce the diagnostic 10k fixture;
  twenty-five accounts produce the 50k super-user acceptance fixture. User-1
  owns the first four accounts (8k servers), while owner-0001 owns one account
  (2k servers). Every server is reachable through account, team, and VPC paths
  so enumeration must merge overlapping indexed grant streams exactly once."
  (:require [eacl.core :as eacl]))

(def schema
  "definition user {}

   definition platform {
     relation super_admin: user
   }

   definition account {
     relation owner: user
     relation platform: platform
     permission admin = owner + platform->super_admin
     permission view = admin
   }

   definition team {
     relation account: account
     relation leader: user
     permission admin = account->admin + leader
     permission view = admin
   }

   definition vpc {
     relation account: account
     relation shared_admin: user
     permission admin = account->admin + shared_admin
     permission view = admin
   }

   definition server {
     relation account: account
     relation team: team
     relation vpc: vpc
     relation shared_admin: user
     permission admin = account->admin + shared_admin
     permission view = admin + account->view + team->view + vpc->view + shared_admin
   }")

(def recursive-schema
  "definition user {}

   definition platform {
     relation super_admin: user
   }

   definition account {
     relation owner: user
     relation platform: platform
     relation parent: account
     permission admin = owner + parent->admin + platform->super_admin
     permission view = admin + parent->admin
   }

   definition team {
     relation account: account
     relation leader: user
     permission admin = account->admin + leader
     permission view = admin
   }

   definition vpc {
     relation account: account
     relation shared_admin: user
     permission admin = account->admin + shared_admin
     permission view = admin
   }

   definition server {
     relation account: account
     relation team: team
     relation vpc: vpc
     relation shared_admin: user
     relation parent: server
     permission admin = account->admin + shared_admin
     permission view = admin + parent->view + account->view + team->view + vpc->view + shared_admin
   }")

(defn object
  [type id]
  (eacl/spice-object type id))

(def super-user (object :user "super-user"))
(def user-1 (object :user "user-1"))
(def owner-0001 (object :user "owner-0001"))
(def platform (object :platform "platform"))

(defn- zero-pad
  [width value]
  (let [value (str value)]
    (str (apply str (repeat (max 0 (- width (count value))) "0"))
         value)))

(defn account-id [account-index]
  (str "account-" (zero-pad 4 (inc account-index))))

(defn team-id [account-index team-index]
  (str "team-"
       (zero-pad 4 (inc account-index))
       "-"
       (zero-pad 2 (inc team-index))))

(defn vpc-id [account-index vpc-index]
  (str "vpc-"
       (zero-pad 4 (inc account-index))
       "-"
       (zero-pad 2 (inc vpc-index))))

(defn server-id [account-index server-index]
  (str "server-"
       (zero-pad 4 (inc account-index))
       "-"
       (zero-pad 5 (inc server-index))))

(defn owner-id [account-index]
  (str "owner-" (zero-pad 4 (inc account-index))))

(defn leader-id [account-index team-index]
  (str "leader-"
       (zero-pad 4 (inc account-index))
       "-"
       (zero-pad 2 (inc team-index))))

(defn vpc-admin-id [account-index vpc-index]
  (str "vpc-admin-"
       (zero-pad 4 (inc account-index))
       "-"
       (zero-pad 2 (inc vpc-index))))

(def default-shape
  {:accounts 5
   :teams-per-account 4
   :vpcs-per-account 2
   :servers-per-account 2000
   :user-1-account-count 4})

(def acceptance-shape
  (assoc default-shape :accounts 25))

(defn expected-counts
  [{:keys [accounts servers-per-account user-1-account-count]}]
  {:super-user (* accounts servers-per-account)
   :user-1 (* user-1-account-count servers-per-account)
   :owner-0001 servers-per-account})

(defn objects
  [{:keys [accounts teams-per-account vpcs-per-account servers-per-account]}]
  (concat
   [super-user user-1 platform]
   (mapcat
    (fn [account-index]
      (concat
       [(object :user (owner-id account-index))
        (object :account (account-id account-index))]
       (mapcat
        (fn [team-index]
          [(object :user (leader-id account-index team-index))
           (object :team (team-id account-index team-index))])
        (range teams-per-account))
       (mapcat
        (fn [vpc-index]
          [(object :user (vpc-admin-id account-index vpc-index))
           (object :vpc (vpc-id account-index vpc-index))])
        (range vpcs-per-account))
       (mapcat
        (fn [server-index]
          [(object :server (server-id account-index server-index))])
        (range servers-per-account))))
    (range accounts))))

(defn relationships
  [{:keys [accounts teams-per-account vpcs-per-account servers-per-account
           user-1-account-count]}]
  (concat
   [(eacl/->Relationship super-user :super_admin platform)]
   (mapcat
    (fn [account-index]
      (let [account (object :account (account-id account-index))]
        (concat
         [(eacl/->Relationship
           (object :user (owner-id account-index)) :owner account)
          (eacl/->Relationship platform :platform account)]
         (when (< account-index user-1-account-count)
           [(eacl/->Relationship user-1 :owner account)])
         (mapcat
          (fn [team-index]
            (let [team (object :team (team-id account-index team-index))]
              [(eacl/->Relationship account :account team)
               (eacl/->Relationship
                (object :user (leader-id account-index team-index))
                :leader
                team)]))
          (range teams-per-account))
         (mapcat
          (fn [vpc-index]
            (let [vpc (object :vpc (vpc-id account-index vpc-index))]
              [(eacl/->Relationship account :account vpc)
               (eacl/->Relationship
                (object :user (vpc-admin-id account-index vpc-index))
                :shared_admin
                vpc)]))
          (range vpcs-per-account))
         (mapcat
          (fn [server-index]
            (let [server (object :server
                                 (server-id account-index server-index))]
              [(eacl/->Relationship account :account server)
               (eacl/->Relationship
                (object :team
                        (team-id account-index
                                 (mod server-index teams-per-account)))
                :team
                server)
               (eacl/->Relationship
                (object :vpc
                        (vpc-id account-index
                                (mod server-index vpcs-per-account)))
                :vpc
                server)]))
          (range servers-per-account)))))
    (range accounts))))

(defn object-transactions
  [shape]
  (map-indexed
   (fn [index {:keys [id]}]
     {:db/id (- (inc index))
      :eacl/id id})
   (objects shape)))

(defn relationship-batches
  ([shape]
   (relationship-batches shape 500))
  ([shape batch-size]
   (partition-all batch-size (relationships shape))))

(defn resource-query
  ([subject permission]
   (resource-query subject permission 50))
  ([subject permission page-size]
   {:subject subject
    :permission permission
    :resource/type :server
    :first page-size}))

(defn count-query
  [subject permission]
  (dissoc (resource-query subject permission) :first))

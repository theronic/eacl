(ns eacl.contract-support
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [eacl.core :as eacl]))

(def ->user (partial eacl/spice-object :user))
(def ->platform (partial eacl/spice-object :platform))
(def ->account (partial eacl/spice-object :account))
(def ->server (partial eacl/spice-object :server))

(def smoke-schema
  "definition user {}

   definition platform {
     relation super_admin: user
   }

   definition account {
     relation platform: platform
     relation owner: user

     permission admin = owner + platform->super_admin
     permission view = admin
   }

   definition server {
     relation account: account

     permission view = account->view
     permission reboot = account->admin
   }")

(def smoke-objects
  [(->user "user-1")
   (->user "user-2")
   (->user "super-user")
   (->platform "platform-1")
   (->account "account-1")
   (->server "server-1")
   (->server "server-2")])

(def smoke-relationships
  [(eacl/->Relationship (->user "user-1") :owner (->account "account-1"))
   (eacl/->Relationship (->user "super-user") :super_admin (->platform "platform-1"))
   (eacl/->Relationship (->platform "platform-1") :platform (->account "account-1"))
   (eacl/->Relationship (->account "account-1") :account (->server "server-1"))
   (eacl/->Relationship (->account "account-1") :account (->server "server-2"))])

(defn assert-seeded-contracts!
  [client]
  (testing "schema round-trips through the logical representation"
    (let [{:keys [relations permissions]} (eacl/read-schema client)]
      (is (= 4 (count relations)))
      (is (= 5 (count permissions)))))

  (testing "permission checks traverse direct and arrow relations"
    (is (true? (eacl/can? client (->user "user-1") :reboot (->server "server-1"))))
    (is (true? (eacl/can? client (->user "super-user") :reboot (->server "server-2"))))
    (is (false? (eacl/can? client (->user "user-2") :reboot (->server "server-1"))))
    (is (false? (eacl/can? client (->user "missing-user") :reboot (->server "server-1")))))

  (testing "lookup-resources and count-resources share cursor semantics"
    (let [{page1-data :data page1-cursor :cursor}
          (eacl/lookup-resources client {:subject       (->user "user-1")
                                         :permission    :view
                                         :resource/type :server
                                         :limit         1})
          {page2-data :data}
          (eacl/lookup-resources client {:subject       (->user "user-1")
                                         :permission    :view
                                         :resource/type :server
                                         :limit         1
                                         :cursor        page1-cursor})
          {count :count count-cursor :cursor}
          (eacl/count-resources client {:subject       (->user "user-1")
                                        :permission    :view
                                        :resource/type :server
                                        :limit         1})]
      (is (= [(->server "server-1")] page1-data))
      (is (= [(->server "server-2")] page2-data))
      (is (= 1 count))
      (is (string? page1-cursor))
      (is (string? count-cursor))))

  (testing "lookup-subjects and count-subjects enumerate reverse access"
    (let [subjects (->> (eacl/lookup-subjects client {:resource     (->server "server-1")
                                                      :permission   :reboot
                                                      :subject/type :user})
                        :data
                        set)
          {count :count cursor :cursor}
          (eacl/count-subjects client {:resource     (->server "server-1")
                                       :permission   :reboot
                                       :subject/type :user
                                       :limit        1})]
      (is (= #{(->user "user-1") (->user "super-user")} subjects))
      (is (= 1 count))
      (is (string? cursor))))

  (testing "relationship writes and reads remain part of the contract"
    (is (= [(eacl/->Relationship (->user "user-1") :owner (->account "account-1"))]
           (eacl/read-relationships client {:resource/type     :account
                                            :resource/id       "account-1"
                                            :resource/relation :owner
                                            :subject/type      :user
                                            :subject/id        "user-1"})))
    (eacl/create-relationship! client (->user "user-2") :owner (->account "account-1"))
    (is (true? (eacl/can? client (->user "user-2") :reboot (->server "server-1"))))
    (eacl/delete-relationship! client (->user "user-2") :owner (->account "account-1"))
    (is (false? (eacl/can? client (->user "user-2") :reboot (->server "server-1"))))))

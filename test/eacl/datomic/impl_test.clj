(ns eacl.datomic.impl-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.fixtures :as fixtures]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.impl :as spiceomic :refer [Relation Relationship Permission can?]]))

(deftest eacl3-tests

  (testing "Permission helper"
    (is (= #:eacl.permission{:resource-type   :server
                             :permission-name :admin
                             :relation-name   :owner}
           (Permission :server :owner :admin)))
    (testing "permission admin Permission can infer resource type from namespaced relation keyword"
      (is (= #:eacl.permission{:resource-type   :server
                               :permission-name :admin
                               :relation-name   :owner}
             (Permission :server/owner :admin)))))

  (testing "fixtures"
    (with-mem-conn [conn schema/v4-schema]
      (is @(d/transact conn fixtures/base-fixtures))

      (let [db (d/db conn)]
        ":test/user can :view and :reboot their server"

        (is (can? db :test/user1 :view :test/server1))
        (is (can? db :test/user1 :reboot :test/server1))

        "...but :test/user2 can't."
        (is (not (can? db :test/user2 :view :test/server1)))
        (is (not (can? db :test/user2 :reboot :test/server1)))

        ":test/user1 is admin of :test/vpc because they own account"
        (is (can? db :test/user1 :admin :test/vpc))

        "and so is super-user because he is super_admin of platform"
        (is (can? db :user/super-user :admin :test/vpc))

        "but :test/user2 is not"
        (is (not (can? db :test/user2 :admin :test/vpc)))

        "Sanity check that relations don't affect wrong resources"
        (is (not (can? db :test/user2 :view :test/account1)))

        "User 2 can view server 2"
        (is (can? db :test/user2 :view :test/server2))

        "Super User can view all servers"
        (is (can? db :user/super-user :view :test/server1))
        (is (can? db :user/super-user :view :test/server2))

        "User 2 can delete server2 because they have server.owner relation"
        (is (can? db :test/user2 :delete :test/server2))
        "...but not :test/user1"
        (is (not (can? db :test/user1 :delete :test/server2)))

        (testing "We can enumerate subjects that can access a resource."
          ; Bug: currently returns the subject itself which needs a fix.
          (is (= #{(spice-object :user "user-1")
                   (spice-object :account "account-1")
                   (spice-object :user "super-user")}
                 (set (spiceomic/lookup-subjects db {:resource/id "server-1"
                                                     :permission  :view}))))

          (testing ":test/user2 is only subject who can delete :test/server2"
            (is (= #{(spice-object :user "user-2")
                     (spice-object :user "super-user")}
                   (set (spiceomic/lookup-subjects db {:resource/id "server-2"
                                                       :permission  :delete}))))))

        (testing "We can enumerate resources with lookup-resources"
          (is (= #{(spice-object :account "account-1")
                   (spice-object :server "server-1")}
                 (set (spiceomic/lookup-resources db {:subject/id "user-1"
                                                      :permission :view}))))
          (is (= #{(spice-object :account "account-2")
                   (spice-object :server "server-2")}
                 (set (spiceomic/lookup-resources db {:subject/id "user-2"
                                                      :permission :view})))))

        (testing "Make user-1 a shared_admin of server-2"
          (is @(d/transact conn [(Relationship :test/user1 :shared_admin :test/server2)]))) ; this shouldn't be working. no schema for it.

        (let [db (d/db conn)]
          "Now :test/user1 can also :server/delete server 2"
          (is (can? db :test/user1 :delete :test/server2))

          ; todo: lookup-resources needs resource type filter.
          (is (= #{(spice-object :account "account-1")
                   (spice-object :server "server-1")
                   (spice-object :server "server-2")}
                 (set (spiceomic/lookup-resources db {:subject/id "user-1"
                                                      :permission :view}))))
          (is (= #{(spice-object :user "super-user")
                   (spice-object :user "user-1")
                   (spice-object :user "user-2")}
                 (set (spiceomic/lookup-subjects db {:resource/id "server-2"
                                                     :permission  :delete})))))

        (testing "Now let's delete all :server/owner Relationships for :test/user2"
          (let [db-for-delete (d/db conn)
                rels          (d/q '[:find [(pull ?rel [* {:eacl/subject [*]}]) ...]
                                     :where
                                     [?rel :eacl.relationship/subject :test/user2]
                                     [?rel :eacl.relationship/relation-name :owner]]
                                   db-for-delete)]
            (is @(d/transact conn (for [rel rels] [:db.fn/retractEntity (:db/id rel)])))))

        (testing "Now only :test/user1 can access both servers."
          (let [db' (d/db conn)]
            (is (= #{(spice-object :account "account-2")
                     (spice-object :user "user-1")
                     (spice-object :user "super-user")}
                   (set (spiceomic/lookup-subjects db' {:resource/id "server-2"
                                                        :permission  :view}))))
            (testing ":test/user2 cannot access any servers" ; is this correct?
              (is (= #{}                                    ; Expect empty set of spice objects
                     (set (spiceomic/lookup-resources db' {:subject/id "user-2"
                                                           :permission :view})))))

            (is (not (can? db' :test/user2 :server/delete :test/server2)))

            (testing ":test/user1 permissions remain unchanged"
              (is (= #{(spice-object :account "account-1")
                       (spice-object :server "server-1")
                       (spice-object :server "server-2")}
                     (set (spiceomic/lookup-resources db' {:subject/id "user-1"
                                                           :permission :view})))))))))))

(comment
  (require '[eacl.fixtures :as fixtures])
  (spiceomic/with-mem-conn [conn schema/v4-schema]
    @(d/transact conn fixtures/base-fixtures)
    (let [client (make-client conn)]
      (lookup-resources client {}))))
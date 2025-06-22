(ns eacl.datomic.impl-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.fixtures :as fixtures :refer [->user ->server ->account]]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.impl :as spiceomic :refer [Relation Relationship Permission can? lookup-subjects lookup-resources read-relationships]]))

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

        ;(prn 'datoms (d/datoms db :avet
        ;                       :eacl.relationship/subject+relation-name+resource-type+resource
        ;                       [(:db/id subject-ent)
        ;                        relation
        ;                        (:type resource)
        ;                        (:db/id resource-ent)]))

        (testing "we can find a relationship using internals"
          (is (= {:eacl.relationship/subject       {:eacl/type :user, :eacl/id "user-1"}
                  :eacl.relationship/relation-name :owner
                  :eacl.relationship/resource      {:eacl/type :account, :eacl/id "account-1"}}
                 (let [rel-eid (impl/find-one-relationship-id db
                                                              {:subject  (->user "user-1")
                                                               :relation :owner
                                                               :resource (->account "account-1")})]
                   (d/pull db '[{:eacl.relationship/subject [:eacl/type :eacl/id]}
                                :eacl.relationship/relation-name
                                {:eacl.relationship/resource [:eacl/type :eacl/id]}] rel-eid)))))

        (testing "find-one-relationship-by-id returns nil for missing relationship"
          (is (nil? (impl/find-one-relationship-id db
                                                   {:subject  (->user "missing-user")
                                                    :relation :owner
                                                    :resource (->account "account-1")}))))

        (testing "super-user can view all servers"
          (is (= #{(spice-object :server "account1-server1")
                   (spice-object :server "account1-server2")
                   (spice-object :server "account2-server1")}
                 (set (:data (lookup-resources db {:subject       (->user "super-user")
                                                   :permission    :view
                                                   :resource/type :server
                                                   :limit         1000
                                                   :cursor        nil}))))))

        (testing ":test/user can :view and :reboot their server"
          (is (can? db :test/user1 :view :test/server1))
          (is (can? db :test/user1 :reboot :test/server1)))

        (testing "can? supports idents"
          (is (can? db [:eacl/id "user-1"] :view [:eacl/id "account1-server1"])))

        (testing "can? supports passing :db/id directly"
          (let [subject-eid  (d/entid db :test/user1)
                resource-eid (d/entid db :test/server1)]
            (is (can? db subject-eid :view resource-eid))))

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

        (testing "read-relationships filters"
          ;(is (= [] (read-relationships db {})))
          ; need better tests here.
          (is (= #{:server} (set (map (comp :type :resource) (read-relationships db {:resource/type :server})))))
          (is (= #{:owner} (set (map :relation (read-relationships db {:resource/relation :owner})))))
          (is (= #{:account} (set (map :relation (read-relationships db {:resource/type :server
                                                                         :resource/relation :account}))))))

        (testing "We can enumerate subjects that can access a resource."
          ; Bug: currently returns the subject itself which needs a fix.
          (is (= #{(spice-object :user "user-1")
                   ;(spice-object :account "account-1")
                   (spice-object :user "super-user")}
                 (set (:data (lookup-subjects db {:resource     (->server "account1-server1")
                                                  :permission   :view
                                                  :subject/type :user})))))

          (testing ":test/user2 is only subject who can delete :test/server2"
            (is (= #{(spice-object :user "user-2")
                     (spice-object :user "super-user")}
                   ; todo pagination + cursor. this is outdated.
                   (set (:data (lookup-subjects db {:resource     (->server "account2-server1")
                                                    :permission   :delete
                                                    :subject/type :user})))))))

        (testing "We can enumerate resources with lookup-resources"
          (is (= #{(spice-object :server "account1-server1")
                   (spice-object :server "account1-server2")}
                 ; todo cursor
                 (set (:data (lookup-resources db {:subject       (->user "user-1")
                                                   :permission    :view
                                                   :resource/type :server})))))

          (testing "same for :reboot permission"
            (is (= #{(spice-object :server "account1-server1")
                     (spice-object :server "account1-server2")}
                   ; todo cursor
                   (set (:data (lookup-resources db {:subject       (->user "user-1")
                                                     :permission    :reboot
                                                     :resource/type :server}))))))

          (is (= #{(spice-object :account "account-1")}
                 ; todo cursor
                 (set (:data (lookup-resources db {:subject       (->user "user-1")
                                                   :permission    :view
                                                   :resource/type :account})))))

          ; todo cursor
          (is (= #{(spice-object :server "account2-server1")}
                 (set (:data (lookup-resources db {:subject       (->user "user-2")
                                                   :permission    :view
                                                   :resource/type :server}))))))

        (testing "Make user-1 a shared_admin of server-2"
          (is @(d/transact conn [(Relationship (->user :test/user1) :shared_admin (->server :test/server2))]))) ; this shouldn't be working. no schema for it.

        (let [db (d/db conn)]
          "Now :test/user1 can also :server/delete server 2"
          (is (can? db :test/user1 :delete :test/server2))

          (is (= #{(spice-object :server "account1-server1")
                   (spice-object :server "account1-server2")
                   (spice-object :server "account2-server1")}
                 (set (:data (lookup-resources db {:subject       (->user "user-1")
                                                   :permission    :view
                                                   :resource/type :server
                                                   :cursor        nil})))))

          (is (= #{(spice-object :user "super-user")
                   (spice-object :user "user-1")
                   (spice-object :user "user-2")}
                 (set (:data (lookup-subjects db {:resource     (->server "account2-server1")
                                                  :permission   :delete
                                                  :subject/type :user}))))))

        (testing "Now let's delete all :server/owner Relationships for :test/user2"
          (let [db-for-delete (d/db conn)
                rels          (d/q '[:find [(pull ?rel [* {:eacl/subject [*]}]) ...]
                                     :where
                                     [?rel :eacl.relationship/subject :test/user2]
                                     [?rel :eacl.relationship/relation-name :owner]]
                                   db-for-delete)]
            (is @(d/transact conn (for [rel rels] [:db.fn/retractEntity (:db/id rel)])))))

        (testing "Now only user-1 can :view all 3 servers."
          (let [db' (d/db conn)]
            (is (= #{
                     ;(spice-object :account "account-2")
                     (spice-object :user "user-1")
                     (spice-object :user "super-user")}
                   (set (:data (lookup-subjects db' {:resource     (->server "account2-server1")
                                                     :permission   :view
                                                     :subject/type :user})))))
            (testing ":test/user2 cannot access any servers" ; is this correct?
              (is (= #{}                                    ; Expect empty set of spice objects
                     (set (:data (lookup-resources db' {:resource/type :server
                                                        :permission    :view
                                                        :subject       (->user "user-2")}))))))

            (is (not (can? db' :test/user2 :server/delete :test/server2)))

            (testing ":test/user1 permissions remain unchanged"
              (is (= #{(spice-object :server "account1-server1")
                       (spice-object :server "account1-server2")
                       (spice-object :server "account2-server1")}
                     (set (:data (lookup-resources db' {:resource/type :server
                                                        :permission    :reboot
                                                        :subject       (->user "user-1")})))))

              (is (= #{(spice-object :server "account1-server1")
                       (spice-object :server "account1-server2")
                       (spice-object :server "account2-server1")}
                     (set (:data (lookup-resources db' {:resource/type :server
                                                        :permission    :reboot
                                                        :subject       (->user "super-user")})))))

              (is (= #{(spice-object :server "account1-server1")
                       (spice-object :server "account1-server2")
                       (spice-object :server "account2-server1")}
                     (set (:data (lookup-resources db' {:resource/type :server
                                                        :permission    :view
                                                        :subject       (->user "user-1")})))))

              (is (= #{(spice-object :account "account-1")}
                     (set (:data (lookup-resources db' {:resource/type :account
                                                        :permission    :view
                                                        :subject       (->user "user-1")}))))))))

        (testing "pagination: limit & offset are handled correctly for arrow permissions"
          (testing "add a 3rd server. make super-user a direct shared_admin of server1 and server 3 to try and trip up pagination"
            @(d/transact conn [{:db/id         "server3"
                                :db/ident      :test/server3
                                :eacl/type :server      ; note, no account.
                                :eacl/id     "server-3"}
                               (Relationship (->user :user/super-user) :shared_admin (->server :test/server1))
                               (Relationship (->user :user/super-user) :shared_admin (->server "server3"))]))

          (let [db' (d/db conn)]
            (testing "limit: 10, offset 0 should include all 3 servers"
              (is (= #{(spice-object :server "account1-server1")
                       (spice-object :server "account1-server2")
                       (spice-object :server "account2-server1")
                       (spice-object :server "server-3")}
                     (set (:data (lookup-resources db' {:limit         10
                                                        :cursor        nil ; no cursor should return all 3 servers
                                                        :resource/type :server
                                                        :permission    :view
                                                        :subject       (->user "super-user")}))))))

            (testing "limit: 10, offset: 1 should exclude server-1"
              (is (= [ ; excluded: (spice-object :server "account1-server1")
                      (spice-object :server "server-3")
                      (spice-object :server "account1-server2")
                      (spice-object :server "account2-server1")]
                     ; cursor seems weird here. shouldn't it be exclusive?
                     (:data (lookup-resources db' {:limit         10
                                                   :cursor        "account1-server1"
                                                   :resource/type :server
                                                   :permission    :view
                                                   :subject       (->user "super-user")})))))

            ; Note that return order of Spice resources is not defined, because we do not sort during lookup.
            ; We assume order will be: [server-1, server-3, server-2].
            (testing "limit 1, offset 0 should return first result only, server-1"
              (is (= #{(spice-object :server "account1-server1")}
                     (set (:data (lookup-resources db' {:cursor        nil
                                                        :limit         1
                                                        :resource/type :server
                                                        :permission    :view
                                                        :subject       (->user "super-user")}))))))

            (testing "limit 1, offset 1 should return 2nd result, server-3"
              (is (= [(spice-object :server "server-3")]
                     (:data (lookup-resources db' {:cursor        "account1-server1"
                                                   :limit         1
                                                   :resource/type :server
                                                   :permission    :view
                                                   :subject       (->user "super-user")})))))

            (testing "offset: 2, limit: 10, should return last result only, server-3"
              (is (= [(spice-object :server "account1-server2")
                      (spice-object :server "account2-server1")]
                     (:data (lookup-resources db' {:cursor        "server-3"
                                                   ;:offset        2
                                                   :limit         10
                                                   :resource/type :server
                                                   :permission    :view
                                                   :subject       (->user "super-user")})))))

            (testing "offset: last cursor, limit: 10 should be empty"
              (is (= [] (:data (lookup-resources db' {:limit         10
                                                      :cursor        "account2-server1"
                                                      :resource/type :server
                                                      :permission    :view
                                                      :subject       (->user "super-user")})))))

            (testing "offset: 2, limit: 10 should return last result, server-3"
              (is (= [(spice-object :server "account1-server2")
                      (spice-object :server "account2-server1")]
                     (:data (lookup-resources db' {:cursor        "server-3"
                                                   ;:offset        2
                                                   :limit         10
                                                   :resource/type :server
                                                   :permission    :view
                                                   :subject       (->user "super-user")})))))

            (testing "offset: 2, limit 1, should return last result only, server-3"
              (is (= [(spice-object :server "account1-server2")]
                     (:data (lookup-resources db' {:limit         1
                                                   :cursor        "server-3"
                                                   ;:offset        2
                                                   :resource/type :server
                                                   :permission    :view
                                                   :subject       (->user "super-user")})))))))))))
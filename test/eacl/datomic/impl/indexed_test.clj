(ns eacl.datomic.impl.indexed-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.fixtures :as fixtures :refer [->user ->group ->server ->account ->vpc ->nic ->network ->lease ->backup ->backup-schedule]]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.schema :as schema]
            [eacl.lazy-merge-sort :refer [lazy-merge-dedupe-sort]]
            [eacl.datomic.impl :as impl
             :refer [Relation Relationship Permission
                     can?
                     read-relationships]]
    ;[eacl.datomic.impl.datalog :as impl.datalog :refer [lookup-subjects]]
            [eacl.datomic.impl.indexed :as impl.indexed :refer [count-resources lookup-resources lookup-subjects]]
            [clojure.set]))

; Test grouping & cleanup is in progress.

(def ^:dynamic *conn* nil)

(deftest lazy-merge-sort-tests
  (is (= [1 2 3 4 5 6 7 8 9 10 11]
         (let [seq1 [1 3 5 7 9]
               seq2 [2 3 6 -1 8 9 10] ; note unsorted -1 will be skipped because not sorted (undefined behaviour)
               seq3 [1 4 5 6 11]]
           (take 20 (lazy-merge-dedupe-sort [seq1 seq2 seq3]))))))

;; Load schema from SpiceDB DSL file
(def fixtures-schema-string
  (slurp "test/eacl/fixtures.schema"))

(defn eacl-schema-fixture [f]
  (with-mem-conn [conn schema/v6-schema]
    ;; Use write-schema! with SpiceDB DSL instead of direct Relation/Permission fixtures
    (schema/write-schema! conn fixtures-schema-string)
    ;; Transact entity fixtures and relationship fixtures together (tempids reference each other)
    (is @(d/transact conn (concat fixtures/entity-fixtures fixtures/relationship-fixtures)))
    (is @(d/transact conn fixtures/txes-additional-account3+server))
    (binding [*conn* conn]
      (doall (f)))))

(t/use-fixtures :each eacl-schema-fixture)

;; Test Helpers

(comment

  (d/delete-database in-mem-uri)

  (do
    (def in-mem-uri "datomic:mem://stateful-test")

    (let [created? (d/create-database in-mem-uri)
          conn (d/connect in-mem-uri)]
      (prn 'created? created?)

      (when created?
        (prn 'transacting 'schema)
        @(d/transact conn schema/v6-schema)
        @(d/transact conn fixtures/base-fixtures))

      (->> (lookup-resources (d/db conn)
                             {:subject (->vpc :test/vpc1)
                              :permission :view
                              :resource/type :server
                              :limit 1000
                              :cursor nil})
           (:data)))))

(defn paginated-data->spice
  "To make tests pass after we moved to eids in internals."
  [db data]
  (->> data
       (map (fn [{:as obj :keys [type id]}]
              (let [ent (d/entity db id)]
                (spice-object type (:eacl/id ent)))))))

(defn paginated->spice
  "To make tests pass after we moved to eids in internals."
  [db {:as page :keys [data cursor]}]
  (paginated-data->spice db data))

(defn paginated->spice-set
  "To make tests pass after we moved to eids in internals."
  [db {:as page :keys [data cursor]}]
  (set (paginated->spice db page)))

(deftest permission-helper-tests
  (testing "Permission helper with new unified API"
    (is (= #:eacl.permission{:eacl/id "eacl:permission::server::admin::self::relation::owner"
                             :resource-type :server
                             :permission-name :admin
                             :source-relation-name :self
                             :target-type :relation
                             :target-name :owner}
           (Permission :server :admin {:relation :owner})))
    (testing "arrow permission to permission"
      (is (= #:eacl.permission{:eacl/id "eacl:permission::server::admin::account::permission::admin"
                               :resource-type :server
                               :permission-name :admin
                               :source-relation-name :account
                               :target-type :permission
                               :target-name :admin}
             (Permission :server :admin {:arrow :account :permission :admin}))))
    (testing "arrow permission to relation"
      (is (= #:eacl.permission{:eacl/id "eacl:permission::server::view::account::relation::owner"
                               :resource-type :server
                               :permission-name :view
                               :source-relation-name :account
                               :target-type :relation
                               :target-name :owner}
             (Permission :server :view {:arrow :account :relation :owner}))))))

(deftest check-permission-tests
  (let [db (d/db *conn*)
        super-user-eid (d/entid db :user/super-user)]

    (testing "we can count resources (materializes full index)"
      (is (= 3 (:count (count-resources db {:subject (->user super-user-eid)
                                            :permission :view
                                            :resource/type :server
                                            :limit 1000
                                            :cursor nil})))))

    (testing ":test/user1 can :view, :reboot or :share server1"
      (is (can? db (->user :test/user1) :view (->server :test/server1)))
      (is (can? db (->user :test/user1) :share (->server :test/server1)))
      (is (can? db (->user :test/user1) :reboot (->server :test/server1))))

    (testing "but :test/user2 can't :view, :reboot or :share server1"
      (is (not (can? db (->user :test/user2) :view (->server :test/server1))))
      (is (not (can? db (->user :test/user2) :share (->server :test/server1))))
      (is (not (can? db (->user :test/user2) :reboot (->server :test/server1)))))

    (testing "can? supports :db/id and idents"
      (let [user1-eid (d/entid db :test/user1)]
        (is (can? db (->user user1-eid) :view (->server :test/server1))))

      (is (can? db (->user [:eacl/id "user-1"]) :view (->server [:eacl/id "account1-server1"])))
      (is (can? db (->user :test/user1) :view (->server [:eacl/id "account1-server1"]))))

    (testing "can? supports passing :db/id directly"
      (let [subject-eid (d/entid db :test/user1)
            resource-eid (d/entid db :test/server1)]
        (is (can? db (->user subject-eid) :view (->server resource-eid)))))

    (testing "...but :test/user2 can't."
      (is (not (can? db (->user :test/user2) :view (->server :test/server1))))
      (is (not (can? db (->user :test/user2) :reboot (->server :test/server1)))))

    (testing "user1 can share a server via a direct :permission on admin"
      (is (can? db (->user :test/user1) :share (->server :test/server1)))
      (is (not (can? db (->user :test/user2) :share (->server :test/server1)))))

    (testing ":test/user1 is admin of :test/vpc1 because they own account"
      (is (can? db (->user :test/user1) :admin (->vpc :test/vpc1))))

    (testing "and so is super-user because he is super_admin of platform"
      (is (can? db (->user :user/super-user) :admin (->vpc :test/vpc1))))

    (testing "but :test/user2 is not"
      (is (not (can? db (->user :test/user2) :admin (->vpc :test/vpc1)))))

    (testing "Sanity check that relations don't affect wrong resources"
      (is (not (can? db (->user :test/user2) :view (->account :test/account1)))))

    (testing "User 2 can view server 2"
      (is (can? db (->user :test/user2) :view (->server :test/server2))))

    (testing "Super User can view all servers"
      (is (can? db (->user :user/super-user) :view (->server :test/server1)))
      (is (can? db (->user :user/super-user) :view (->server :test/server2))))

    (testing "User 2 can delete server2 because they have server.owner relation"
      (is (can? db (->user :test/user2) :delete (->server :test/server2)))
      (testing "...but not :test/user1"
        (is (not (can? db (->user :test/user1) :delete (->server :test/server2))))))))

(deftest complex-relation-tests
  (testing "does EACL support reverse lookups?"
    (let [db (d/db *conn*)]
      (is (can? db (->lease :test/lease1) :view (->server :test/server1)))
      (is (can? db (->network :test/network1) :view (->server :test/server1)))
      (is (can? db (->vpc :test/vpc1) :view (->server :test/server1))) ; why does htis work, but below does not?

      (is (can? db (->user :test/user1) :via_self_admin (->server :test/server1)))
      (is (not (can? db (->user :test/user2) :via_self_admin (->server :test/server1))))

      (is (can? db (->user :test/user1) :view (->backup :test/backup1)))
      (is (not (can? db (->user :test/user2) :view (->backup :test/backup1))))

      (testing ":test/user1 can :view backup-schedule-1, but not :test/user2"
        (is (can? db (->user :test/user1) :view (->backup-schedule :test/backup-schedule1)))
        (is (not (can? db (->user :test/user2) :view (->backup-schedule :test/backup-schedule1)))))

      (testing "Adding :test/user2 as a :viewer of a backup schedule, lets them view related backups:"
        (let [rx (d/with db [(Relationship (->user :test/user2) :viewer (->backup-schedule :test/backup-schedule1))])]
          (is (can? (:db-after rx) (->user :test/user2) :view (->backup-schedule :test/backup-schedule1)))
          (is (can? (:db-after rx) (->user :test/user2) :view (->backup :test/backup1)))))

      (is (not (can? db (->vpc :test/vpc2) :view (->server :test/server1))))

      (let [vpc-eid (d/entid db :test/vpc1)]
        (is (= [(->server "account1-server1")]
               (->> (lookup-resources db
                                      {:subject (->vpc vpc-eid)
                                       :permission :view
                                       :resource/type :server
                                       :limit 1000
                                       :cursor nil})
                    (paginated->spice db))))))))

(deftest read-relationships-tests
  (let [db (d/db *conn*)]
    (testing "we can find a relationship using internals"
      ; todo update for opts & internals
      (is (= {:eacl.relationship/subject {:db/ident :test/user1}
              :eacl.relationship/subject-type :user
              :eacl.relationship/relation-name :owner
              :eacl.relationship/resource {:db/ident :test/account1}
              :eacl.relationship/resource-type :account}
             (let [rel-eid (impl/find-one-relationship-id db {:subject (->user :test/user1)
                                                              :relation :owner
                                                              :resource (->account :test/account1)})]
               (d/pull db '[:eacl.relationship/subject-type
                            :eacl.relationship/resource-type
                            {:eacl.relationship/subject [:db/ident]}
                            :eacl.relationship/relation-name
                            {:eacl.relationship/resource [:db/ident]}] rel-eid)))))

    (testing "find-one-relationship-by-id throws if you pass missing subject or resource"
      (is (thrown? Throwable (impl/find-one-relationship-id db {:subject (->user "missing-user")
                                                                :relation :owner
                                                                :resource (->account "account-1")}))))

    (testing "read-relationships filters"
      ;(is (= [] (read-relationships db {})))
      ; need better tests here.
      (is (= #{:server} (set (map (comp :type :resource) (read-relationships db {:resource/type :server})))))
      (is (= #{:owner} (set (map :relation (read-relationships db {:resource/relation :owner})))))
      (is (= #{:account} (set (map :relation (read-relationships db {:resource/type :server
                                                                     :resource/relation :account}))))))))

(deftest lookup-subjects-tests
  (let [db (d/db *conn*)]
    (testing "We can enumerate subjects that can access a resource."
      ; Bug: currently returns the subject itself which needs a fix.
      (is (= #{(->user "user-1")
               (->user "super-user")}
             (->> (lookup-subjects db {:resource (->server (d/entid db :test/server1))
                                       :permission :view
                                       :subject/type :user})
                  (paginated->spice-set db))))

      (is (= #{(->group "group-1")}
             (->> (lookup-subjects db {:resource (->server (d/entid db :test/server1))
                                       :permission :view
                                       :subject/type :group})
                  (paginated->spice-set db))))

      (testing ":test/user2 is only subject who can delete :test/server2"
        (is (= #{(->user "user-2")
                 (->user "super-user")}
              ; todo pagination + cursor. this is outdated.
               (->> (lookup-subjects db {:resource (->server (d/entid db [:eacl/id "account2-server1"]))
                                         :permission :delete
                                         :subject/type :user})
                    (paginated->spice-set db))))))))

(deftest eacl3-tests

  (let [db (d/db *conn*)
        super-user-eid (d/entid db :user/super-user)
        user1-eid (d/entid db :test/user1)
        user2-eid (d/entid db :test/user2)]

    (testing "lookup-resources: super-user can view all servers"
      ; note: the return order of `lookup-resources` depends on the tuple index, or any sort operation.
      ; currently we sort, which is super slow. this needs to revert to the tuple-index order.
      (let [{:as page1
             page1-data :data
             page1-cursor :cursor} (lookup-resources db {:subject (->user super-user-eid)
                                                         :permission :view
                                                         :resource/type :server
                                                         :limit 1000
                                                         :cursor nil})
            resources (paginated->spice db page1)]
        (is (= [(spice-object :server "account1-server1")
                (spice-object :server "account1-server2")
                (spice-object :server "account2-server1")]
               resources))
        (testing "cursor should be the last resource"
          (is page1-cursor)
          (is (:e page1-cursor))
          (let [last-resource-ent (d/entity db (:e page1-cursor))]
            (is (= "account2-server1" (:eacl/id last-resource-ent)))))))

    (testing "view_via_arrow_relation works"
      (is (= #{(spice-object :account "account-1")
               (spice-object :account "account-2")}
             (->> (lookup-resources db {:subject (->user super-user-eid)
                                        :permission :view_via_arrow_relation
                                        :resource/type :account
                                        :limit 1000
                                        :cursor nil})
                  (paginated->spice db)
                  (set))))

      (testing "...and server { permission view_via_arrow_relation = account->view_via_arrow_relation } works")
      (is (= #{(spice-object :server "account1-server1")
               (spice-object :server "account1-server2")
               (spice-object :server "account2-server1")}
             (->> (lookup-resources db {:subject (->user super-user-eid)
                                        :permission :view_server_via_arrow_relation
                                        :resource/type :server
                                        :limit 1000
                                        :cursor nil})
                  (paginated->spice db)
                  (set)))))

    (testing "We can enumerate resources with lookup-resources"
      (is (= #{(spice-object :server "account1-server1")
               (spice-object :server "account1-server2")
               (spice-object :server "account3-server3.1")}
             (->> (lookup-resources db {:subject (->user user1-eid)
                                        :permission :view
                                        :resource/type :server})
                  (:data)
                  (map (fn [{:as obj :keys [type id]}] (spice-object type (:eacl/id (d/entity db id)))))
                  (set))))

      (testing "count-resources returns 3 as above"
        (is (= 3 (:count (count-resources db {:subject (->user user1-eid)
                                              :permission :view
                                              :resource/type :server})))))

      (testing "same for :reboot permission"
        (is (= [(->server "account1-server1")
                (->server "account1-server2")
                (->server "account3-server3.1")]
              ; todo cursor
               (->> (lookup-resources db {:subject (->user user1-eid)
                                          :permission :reboot
                                          :resource/type :server})
                    (paginated->spice db)))))

      (testing "we can enumerate :account objects user1 can :view"
        (is (= #{(->account "account-1")
                 (->account "account-3")}
               (->> (lookup-resources db {:subject (->user user1-eid)
                                          :permission :view
                                          :resource/type :account})
                    (paginated->spice db)
                    (set))))

        (testing "count matches lookup"
          (is (= 2 (:count (count-resources db {:subject (->user user1-eid)
                                                :permission :view
                                                :resource/type :account}))))))

      (is (= #{(->server "account2-server1")}
             (->> (lookup-resources db
                                    {:subject (->user user2-eid)
                                     :permission :view
                                     :resource/type :server})
                  (paginated->spice db)
                  (set)))))

    (testing "Make user-1 a shared_admin of server-2"
      (is @(d/transact *conn* [(Relationship (->user :test/user1) :shared_admin (->server :test/server2))]))) ; this shouldn't be working. no schema for it.

    (let [db (d/db *conn*)]
      (testing "As a :shared_admin, :test/user1 can also :server/delete server 2"
        (is (can? db (->user :test/user1) :delete (->server :test/server2)))

        (is (= #{(spice-object :server "account1-server1")
                 (spice-object :server "account1-server2")
                 (spice-object :server "account2-server1")
                 (spice-object :server "account3-server3.1")}
               (->> (lookup-resources db {:subject (->user user1-eid)
                                          :permission :view
                                          :resource/type :server
                                          :cursor nil})
                    (paginated->spice-set db))))

        (is (= #{(->user "super-user")
                 (->user "user-1")
                 (->user "user-2")}
               (->> (lookup-subjects db {:resource (->server (d/entid db [:eacl/id "account2-server1"])) ; todo fix
                                         :permission :delete
                                         :subject/type :user})
                    (paginated->spice-set db))))))

    (testing "Now let's delete all :server/owner Relationships for :test/user2"
      (let [db-for-delete (d/db *conn*)
            rels (d/q '[:find [(pull ?rel [* {:eacl/subject [*]}]) ...]
                        :where
                        [?rel :eacl.relationship/subject :test/user2]
                        [?rel :eacl.relationship/relation-name :owner]]
                      db-for-delete)]
        (is @(d/transact *conn* (for [rel rels] [:db.fn/retractEntity (:db/id rel)])))))

    (testing "Now only user-1 can :view all 3 servers, including those in account2."
      (let [db' (d/db *conn*)]
        (is (= [(spice-object :user "user-1")
                (spice-object :user "super-user")]
               (->> (lookup-subjects db' {:resource (->server (d/entid db' [:eacl/id "account2-server1"]))
                                          :permission :view
                                          :subject/type :user})
                    (paginated->spice db'))))

        (is (can? db' (->user (d/entid db' [:eacl/id "user-1"])) :view (->server [:eacl/id "account2-server1"])))
        (is (can? db' (->user (d/entid db' [:eacl/id "super-user"])) :view (->server [:eacl/id "account2-server1"])))
        (is (not (can? db' (->user (d/entid db' [:eacl/id "user2"])) :view (->server [:eacl/id "account2-server1"]))))

        (testing ":test/user2 cannot access any servers" ; is this correct?
          (is (= #{} (->> (lookup-resources db' {:resource/type :server
                                                 :permission :view
                                                 :subject (->user [:eacl/id "user-2"])})
                          (:data)
                          (set))))
          (testing "count-resources also zero"
            (is (zero? (:count (count-resources db' {:resource/type :server
                                                     :permission :view
                                                     :subject (->user [:eacl/id "user-2"])}))))))

        (is (false? (can? db' (->user :test/user2) :delete (->server :test/server2))))

        (testing ":test/user1 permissions remain unchanged"
          (is (= #{(spice-object :server "account1-server1")
                   (spice-object :server "account1-server2")
                   (spice-object :server "account2-server1")
                   (spice-object :server "account3-server3.1")}
                 (->> (lookup-resources db' {:resource/type :server
                                             :permission :reboot
                                             :subject (->user user1-eid)})
                      (paginated->spice-set db))))

          (testing "super-user can still view all servers"
            (is (= #{(spice-object :server "account1-server1")
                     (spice-object :server "account1-server2")
                     (spice-object :server "account2-server1")}
                   (->> (lookup-resources db' {:resource/type :server
                                               :permission :reboot
                                               :subject (->user super-user-eid)})
                        (paginated->spice-set db)))))

          (is (= #{(spice-object :account "account-1")
                   (spice-object :account "account-3")}
                 (->> (lookup-resources db' {:resource/type :account
                                             :permission :view
                                             :subject (->user user1-eid)})
                      (paginated->spice-set db)))))))))

(deftest lookup-resources-tests
  (let [db (d/db *conn*)
        super-user-eid (d/entid db :user/super-user)]

    (testing "ensure account eids are larger than server to test cursor-pagination"
      (let [account1 (d/entity db :test/account1)
            server1 (d/entity db :test/server1)]
        (is (> (:db/id account1) (:db/id server1)))))

    ;(testing "what happens when find-relation-def is called with source-relation-name `:self`?"
    ;  ; what do we expect here?
    ;  (is (nil? (impl.indexed/find-relation-def db :server :self))))

    (testing "lookup of :view against :vpc works"
      (is (= #{(->vpc "vpc-1")
               (->vpc "account1-vpc2")
               (->vpc "account1-vpc3")} (->> (lookup-resources db {:subject (->user :test/user1)
                                                                   :permission :view
                                                                   :resource/type :vpc})
                                             (paginated->spice-set db)))))

    (testing "`permission via_self_admin = admin` should work, but currently throws"
      (is (->> (lookup-resources db {:subject (->user :test/user1)
                                     :permission :via_self_admin
                                     :resource/type :server}))))

    (testing "pagination: limit & offset are handled correctly for arrow permissions"
      (testing "add a 3rd server. make super-user a direct shared_admin of server1 and server 3 to try and trip up pagination"
        (is @(d/transact *conn* [{:db/id "server3"
                                  :db/ident :test/server3
                                  :eacl/id "server-3"}
                                 (Relationship (->user :user/super-user) :shared_admin (->server :test/server1))
                                 ; We can use tempids in Relationship because it produces 's in same tx-data.
                                 (Relationship (->user :user/super-user) :shared_admin (->server "server3"))])))

      (let [db' (d/db *conn*)]
        (testing "ensure user1 can only see servers from account1, so excludes server-3"
          (is (= [(spice-object :server "account1-server1")
                  (spice-object :server "account1-server2")
                  (spice-object :server "account3-server3.1")]
                 (->> (lookup-resources db' {:subject (->user :test/user1)
                                             :permission :view
                                             :resource/type :server
                                             :cursor nil})
                      (paginated->spice db')))))

        (testing "enumerate super-user servers with limit: 10 & nil cursor should return all 4 servers"
          (is (= [(spice-object :server "account1-server1")
                  (spice-object :server "account1-server2")
                  (spice-object :server "account2-server1")
                  (spice-object :server "server-3")]
                 (->> (lookup-resources db' {:limit 10
                                             :cursor nil ; no cursor should return all 4 servers
                                             :resource/type :server
                                             :permission :view
                                             :subject (->user super-user-eid)})
                      (paginated->spice db'))))

          (testing "count-resources matches above"
            (is (= 4 (:count (count-resources db' {:resource/type :server
                                                   :permission :view
                                                   :subject (->user super-user-eid)}))))))
        (testing "limit: 10 with cursor set to 1st result should exclude the first result"
          ; test failing because return order is oriented towards how it's stored in index
          (is (= [(spice-object :server "account1-server2") ; (spice-object :server "account1-server1") is excludced.
                  (spice-object :server "account2-server1")
                  (spice-object :server "server-3")]
                ; cursor seems weird here. shouldn't it be exclusive?
                 (->> (lookup-resources db' {:limit 10
                                             :cursor {:resource {:type :account
                                                                 :id (d/entid db' [:eacl/id "account1-server1"])}}
                                             :resource/type :server
                                             :permission :view
                                             :subject (->user super-user-eid)})
                      (paginated->spice db')))))

        ; Note that return order of Spice resources is not defined, because we do not sort during lookup.
        ; We assume order will be: [server-1, server-3, server-2].
        (testing "limit 1 with cursor set to 2nd result should skip first two and return only 3rd result"
          (is (= [(spice-object :server "account2-server1")]
                ;(spice-object :server "server-3")] excluded because of limit.
                 (->> (lookup-resources db' {:cursor {:resource {:type :server
                                                                 :id (d/entid db' [:eacl/id "account1-server2"])}}
                                             :limit 1
                                             :resource/type :server
                                             :permission :view
                                             :subject (->user super-user-eid)})
                      (paginated->spice db)))))

        (testing "limit 1, 3rd cursor offset should return 2nd result, server-3"
          (is (= [(spice-object :server "server-3")]
                 (->> (lookup-resources db' {:cursor {:resource {:type :server
                                                                 :id (d/entid db' [:eacl/id "account2-server1"])}}
                                             :limit 1
                                             :resource/type :server
                                             :permission :view
                                             :subject (->user super-user-eid)})
                      (paginated->spice db')))))

        (testing "limit: 10, cursor at 3rd result only the 4th result"
          (is (= [(spice-object :server "server-3")]
                 (->> (lookup-resources db' {:cursor {:resource {:type :server
                                                                 :id (d/entid db' [:eacl/id "account2-server1"])}}
                                             :limit 10
                                             :resource/type :server
                                             :permission :view
                                             :subject (->user super-user-eid)})
                      (paginated->spice db')))))

        (testing "offset: last cursor, limit: 10 should be empty"
          (is (empty? (->> (lookup-resources db' {:limit 10
                                                  :cursor {:resource {:type :server
                                                                      :id (d/entid db' [:eacl/id "server-3"])}}
                                                  :resource/type :server
                                                  :permission :view
                                                  :subject (->user super-user-eid)})
                           (paginated->spice db'))))))

      (let [db' (d/db *conn*)]
        (testing "ask for 5 results (there are only 4), and ensure page1 & page2 are subsequences with matching cursor"
          (let [both-pages (lookup-resources db' {:limit 5
                                                  :cursor nil ; :cursor nil = first page.
                                                  :resource/type :server
                                                  :permission :view
                                                  :subject (->user super-user-eid)})

                both-pages-resolved (paginated-data->spice db' (:data both-pages))
                [page1-expected
                 page2-expected
                 page3-expected] (partition-all 2 both-pages-resolved)

                {:as page1
                 page1-data :data
                 page1-cursor :cursor} (lookup-resources db' {:limit 2
                                                              :cursor nil ; no cursor should the first page.
                                                              :resource/type :server
                                                              :permission :view
                                                              :subject (->user super-user-eid)})
                ;_                   (prn 'page1 'cursor (:cursor page1))
                {:as page2
                 page2-data :data
                 page2-cursor :cursor} (lookup-resources db' {:limit 2
                                                              :cursor (:cursor page1)
                                                              :resource/type :server
                                                              :permission :view
                                                              :subject (->user super-user-eid)})
                {:as page3-empty
                 page3-data :data
                 page3-cursor :cursor} (->> (lookup-resources db' {:limit 2
                                                                   :cursor (:cursor page2)
                                                                   :resource/type :server
                                                                   :permission :view
                                                                   :subject (->user super-user-eid)}))]

            ;_                   (prn 'page2 'cursor (:cursor page2))]

            (testing "page1 cursor points to next page"
              (is page1-cursor)
              (is (:e page1-cursor)))

            (testing "page2 cursor points to next page"
              (is page2-cursor)
              (is (:e page2-cursor)))

            (testing "page3 cursor matches page 2 because we exhausted results on page 2"
              (is (= page2-cursor page3-cursor)))

            (testing "limit 5 should include all 4 results, in sorted order" ; (depends on tuple index or costly sort)
              (is (= [(spice-object :server "account1-server1")
                      (spice-object :server "account1-server2")
                      (spice-object :server "account2-server1")
                      (spice-object :server "server-3")]
                     (paginated-data->spice db' (:data both-pages)))))

            (testing "page1 with 1-2 of 4 should match the first 2 results"
              (is (= page1-expected (paginated-data->spice db' page1-data))))

            (testing "page2 with 3-4 of 4 should match the second two results"
              (is (= page2-expected (paginated-data->spice db' page2-data))))

            (testing "page3 with 5-6 of 4 should be empty (results exhausted)"
              (is (nil? page3-expected))
              (is (empty? (paginated-data->spice db' page3-data))))

            (testing "repeating page2 query with limit: 1 should return only the first result on that page"
              (is (= (take 1 page2-expected)
                     (->> (lookup-resources db' {:limit 1
                                                 :cursor page1-cursor
                                                 :resource/type :server
                                                 :permission :view
                                                 :subject (->user super-user-eid)})
                          (paginated->spice db')))))

            (testing "repeat page2 query with limit: 10 should return remainder"
              (is (= (drop 2 both-pages-resolved)
                     (->> (lookup-resources db' {:limit 10
                                                 :cursor page1-cursor
                                                 :resource/type :server
                                                 :permission :view
                                                 :subject (->user super-user-eid)})
                          (paginated->spice db')))))

            (testing "lookup-resources returns cursor input when looking beyond any values"
              (let [page3-empty (lookup-resources db' {:limit 100
                                                       :cursor page2-cursor
                                                       :resource/type :server
                                                       :permission :view
                                                       :subject (->user super-user-eid)})]
                (is (empty? (:data page3-empty)))
                (is (= page2-cursor (:cursor page3-empty)))))

            (testing "count-resources should return count of all result"
              (is (= 4 (:count (count-resources db' {:resource/type :server
                                                     :permission :view
                                                     :subject (->user super-user-eid)})))))

            (testing "count-resources is also cursor-sensitive"
              (is (= 2 (:count (count-resources db' {:resource/type :server
                                                     :permission :view
                                                     :cursor page1-cursor
                                                     :subject (->user super-user-eid)}))))

              (is (zero? (:count (count-resources db' {:resource/type :server
                                                       :permission :view
                                                       :subject (->user super-user-eid)
                                                       :cursor page2-cursor}))))))

          (testing "now test pagination for :view permission on :account for  account->super_admin with {:arrow :account :relation :super_admin}"
            (let [both-pages (lookup-resources db' {:limit 3
                                                    :cursor nil ; :cursor nil = first page.
                                                    :resource/type :account
                                                    :permission :view
                                                    :subject (->user super-user-eid)})

                  both-pages-resolved (paginated-data->spice db' (:data both-pages))
                  [page1-expected page2-expected] (partition-all 1 both-pages-resolved)
                  page3-expected [] ; empty.

                  {:as page1
                   page1-data :data
                   page1-cursor :cursor} (lookup-resources db' {:limit 1
                                                                :cursor nil ; no cursor should the first page.
                                                                :resource/type :account
                                                                :permission :view
                                                                :subject (->user super-user-eid)})
                  ;_                   (prn 'page1 'cursor (:cursor page1))
                  {:as page2
                   page2-data :data
                   page2-cursor :cursor} (lookup-resources db' {:limit 1
                                                                :cursor (:cursor page1)
                                                                :resource/type :account
                                                                :permission :view
                                                                :subject (->user super-user-eid)})
                  {:as page3-empty
                   page3-data :data
                   page3-cursor :cursor} (->> (lookup-resources db' {:limit 2
                                                                     :cursor (:cursor page2)
                                                                     :resource/type :account
                                                                     :permission :view
                                                                     :subject (->user super-user-eid)}))]

              ;_                   (prn 'page2 'cursor (:cursor page2))]

              (testing "page1 cursor points to next page"
                (is page1-cursor)
                (is (:e page1-cursor)))

              (testing "page2 cursor points to next page"
                (is page2-cursor)
                (is (:e page2-cursor)))

              (testing "page3 cursor matches page 2 because we exhausted results on page 2"
                (is (= page2-cursor page3-cursor)))

              ;(prn 'both-pages both-pages)

              (testing "both-pages :limit 3 should include both results, in sorted order" ; (depends on tuple index or costly sort)
                (is (= [(spice-object :account "account-1")
                        (spice-object :account "account-2")]
                       (paginated-data->spice db' (:data both-pages)))))

              (testing "page1 contains first result only (no. 1 of 2"
                (is (= page1-expected (paginated-data->spice db' page1-data))))

              (testing "page2 contains second result only (no. 2 of 2)"
                (is (= page2-expected (paginated-data->spice db' page2-data))))

              (testing "page3 should be empty (results exhausted)"
                (is (empty? page3-expected))
                (is (empty? (paginated-data->spice db' page3-data))))

              (testing "lookup-resources returns cursor input when looking beyond any values"
                (let [page3-empty (lookup-resources db' {:limit 100
                                                         :cursor page2-cursor
                                                         :resource/type :account
                                                         :permission :view
                                                         :subject (->user super-user-eid)})]
                  (is (empty? (:data page3-empty)))
                  (is (= page2-cursor (:cursor page3-empty)))))

              ;; server.view tests follow
              (testing "count-resources & cursors"
                (testing "count-resources should return count of all servers"
                  (is (= 4 (:count (count-resources db' {:resource/type :server
                                                         :permission :view
                                                         :subject (->user super-user-eid)}))))

                  (let [{all-servers :data} (lookup-resources db' {:subject (->user super-user-eid) ; all results.
                                                                   :permission :view
                                                                   :resource/type :server
                                                                   :limit -1
                                                                   :cursor nil})]
                    (is (= [(spice-object :server "account1-server1")
                            (spice-object :server "account1-server2")
                            (spice-object :server "account2-server1")
                            (spice-object :server "server-3")]
                           (paginated-data->spice db' all-servers))))

                  (let [{page1-servers :data
                         page1-cursor :cursor} (lookup-resources db' {:subject (->user super-user-eid) ; all results.
                                                                      :permission :view
                                                                      :resource/type :server
                                                                      :limit 2
                                                                      :cursor nil})]
                    (is (:e page1-cursor))
                    (is (= [(spice-object :server "account1-server1")
                            (spice-object :server "account1-server2")]
                           (paginated-data->spice db' page1-servers)))

                    (let [{page2-servers :data
                           page2-cursor :cursor} (lookup-resources db' {:subject (->user super-user-eid) ; all results.
                                                                        :permission :view
                                                                        :resource/type :server
                                                                        :limit 2
                                                                        :cursor page1-cursor})]
                      (is (:e page2-cursor))
                      (is (= [(spice-object :server "account2-server1")
                              (spice-object :server "server-3")]
                             (paginated-data->spice db' page2-servers)))

                      (testing "count-resources should respect limit & cursors"
                        (is (:e page1-cursor))
                        (is (:e page2-cursor))
                        (is (not= page1-cursor page2-cursor))

                        (is (= 2 (:count (count-resources db'
                                                          {:resource/type :server
                                                           :permission :view
                                                           :limit 2
                                                           :cursor nil
                                                           :subject (->user super-user-eid)}))))

                        (is (= 2 (:count (count-resources db'
                                                          {:resource/type :server
                                                           :permission :view
                                                           :limit 2
                                                           :cursor page1-cursor
                                                           :subject (->user super-user-eid)}))))

                        (is (= 0 (:count (count-resources db'
                                                          {:resource/type :server
                                                           :permission :view
                                                           :limit 10
                                                           :subject (->user super-user-eid)
                                                           :cursor page2-cursor}))))))))))))))))

(deftest permission-schema-helper-tests
  (let [db (d/db *conn*)]

    ; skipped for now.
    #_(testing "find-relation-def returns nil if called with any nil inputs"
        (is (nil? (impl.indexed/find-relation-def db nil nil)))
        (is (nil? (impl.indexed/find-relation-def db nil :something)))
        (is (nil? (impl.indexed/find-relation-def db :something nil))))

    ;(testing "find-relation-def"
    ;  (is (= {:eacl.relation/subject-type  :account
    ;          :eacl.relation/resource-type :server
    ;          :eacl.relation/relation-name :account}
    ;        (impl.indexed/find-relation-def db :server :account))))

    (testing "find-permission-defs"
      (is (pos? (count (impl.indexed/find-permission-defs db :server :view))))
      (is (empty? (impl.indexed/find-permission-defs db :server :nonexistent))))))

(deftest get-permission-paths-tests
  (let [db (d/db *conn*)]
    (testing "Direct permission paths"
      (let [paths (impl.indexed/get-permission-paths db :account :view)]
        ;; :account :view has direct (owner) and arrow (platform->super_admin) paths
        ;; Direct owner now returns two paths due to polymorphic :user and :group
        (is (= 3 (count paths)))
        (is (some #(and (= (:type %) :relation)
                        (= (:name %) :owner)
                        (= (:subject-type %) :user))
                  paths))
        (is (some #(and (= (:type %) :relation)
                        (= (:name %) :owner)
                        (= (:subject-type %) :group))
                  paths))
        (is (some #(and (= (:type %) :arrow)
                        (= (:via %) :platform)
                        (= (:target-type %) :platform))
                  paths))))

    (testing "Arrow permission to permission"
      (let [paths (impl.indexed/get-permission-paths db :vpc :admin)]
        ;; Should have arrow to account->admin
        (is (some #(and (= (:type %) :arrow)
                        (= (:via %) :account)
                        (= (:target-type %) :account)
                        (> (count (:sub-paths %)) 0))
                  paths))
        ;; The sub-paths should eventually lead to relations
        (let [arrow-path (first (filter #(= (:via %) :account) paths))]
          (is (vector? (:sub-paths arrow-path)))
          (is (pos? (count (:sub-paths arrow-path)))))))

    (testing "Complex nested paths"
      (let [paths (impl.indexed/get-permission-paths db :server :view)]
        ;; Find the NIC arrow path
        (let [nic-path (first (filter #(= (:via %) :nic) paths))]
          (is nic-path)
          (is (= :network_interface (:target-type nic-path)))
          ;; The sub-paths should show NIC->view paths
          (is (pos? (count (:sub-paths nic-path)))))))

    (testing "Empty paths for non-existent permission"
      (is (empty? (impl.indexed/get-permission-paths db :server :nonexistent))))))

(deftest traverse-paths-tests
  (let [db (d/db *conn*)]
    (testing "Direct path traversal"
      (let [user1-eid (d/entid db :test/user1)
            resources (impl.indexed/traverse-permission-path db :user user1-eid :view :account nil)]
        ;; User1 should be able to view account1
        (is (seq resources))
        (is (some #(= (d/entid db :test/account1) (first %)) resources))))

    (testing "Arrow path traversal"
      (let [user1-eid (d/entid db :test/user1)
            resources (impl.indexed/traverse-permission-path db :user user1-eid :view :server nil)]
        ;; User1 should be able to view servers in account1
        (is (seq resources))
        (is (= [(->server "account1-server1")
                (->server "account1-server2")])
            resources)))

    (testing "Complex nested path traversal"
      (let [vpc1-eid (d/entid db :test/vpc1)
            resources (impl.indexed/traverse-permission-path db :vpc vpc1-eid :view :server nil)]
        ;; VPC1 should be able to view servers through the network chain
        (is (= [(d/entid db :test/server1)]
               (map first resources))))) ; hard to understand destructuring here.

    (testing "Cursor pagination"
      (let [user1-eid (d/entid db :test/user1)
            ;; Get first result
            first-batch (impl.indexed/traverse-permission-path db :user user1-eid :view :server nil)
            first-eid (ffirst first-batch)
            ;; Get next result after cursor
            second-batch (impl.indexed/traverse-permission-path db :user user1-eid :view :server first-eid)]
        (is (= ["account1-server1"
                "account1-server2"
                "account3-server3.1"] (map (comp :eacl/id #(d/entity db %) first) first-batch)))
        (is (= ["account1-server2"
                "account3-server3.1"] (map (comp :eacl/id #(d/entity db %) first) second-batch)))
        (is (not= (ffirst first-batch) (ffirst second-batch)))))))

(deftest lookup-resources-optimized-tests
  (let [db (d/db *conn*)]
    (testing "Basic lookup-resources"
      (let [user1-eid (d/entid db :test/user1)
            result (lookup-resources db {:subject (->user user1-eid)
                                         :permission :view
                                         :resource/type :server
                                         :limit 10})]
        ; this replicates bug where find-relation-def is called with resource-type = nil.
        (is (= [(->server "account1-server1")
                (->server "account1-server2")
                (->server "account3-server3.1")]
               (paginated->spice db result)))))

    (testing "Pagination with cursor"
      (let [user1-eid (d/entid db :test/user1)
            ;; Get first page
            page1 (lookup-resources db {:subject (->user user1-eid)
                                        :permission :view
                                        :resource/type :server
                                        :limit 1})
            ;; Get second page using cursor
            page2 (lookup-resources db {:subject (->user user1-eid)
                                        :permission :view
                                        :resource/type :server
                                        :limit 1
                                        :cursor (:cursor page1)})
            ;; Convert for comparison
            page1-converted (paginated->spice db page1)
            page2-converted (paginated->spice db page2)]
        (is (= 1 (count (:data page1))))
        (is (= 1 (count (:data page2))))
        (is (not= (first page1-converted) (first page2-converted)))))

    (testing "Super user can view all servers"
      (let [super-user-eid (d/entid db :user/super-user)
            result (lookup-resources db {:subject (->user super-user-eid)
                                         :permission :view
                                         :resource/type :server
                                         :limit 100})]
        ;; Should see servers from both accounts
        (is (>= (count (:data result)) 3))))))

(deftest lookup-resources-with-merge-tests
  (let [db (d/db *conn*)]
    (testing "Merge handles duplicate resources from multiple paths"
      ;; Add a direct relation that overlaps with arrow permission
      @(d/transact *conn* [(Relationship (->user :test/user1) :shared_admin (->server :test/server1))])
      (let [db' (d/db *conn*)
            user1-eid (d/entid db' :test/user1)
            result (impl.indexed/lookup-resources db' {:subject (->user user1-eid)
                                                       :permission :view
                                                       :resource/type :server
                                                       :limit 10})]
        ;; Should still only see each server once
        (is (= [(->server "account1-server1")
                (->server "account1-server2")
                (->server "account3-server3.1")]
               (paginated->spice db' result)))))

    (testing "Lazy merge preserves sort order"
      (let [super-user-eid (d/entid db :user/super-user)
            result (impl.indexed/lookup-resources db {:subject (->user super-user-eid)
                                                      :permission :view
                                                      :resource/type :server
                                                      :limit 100})
            eids (map :id (:data result))]
        ;; Entity IDs should be in ascending order
        (is (= eids (sort eids)))))

    (testing "Cursor works with merged results"
      (let [super-user-eid (d/entid db :user/super-user)
            both-pages (impl.indexed/lookup-resources db {:subject (->user super-user-eid)
                                                          :permission :view
                                                          :resource/type :server
                                                          :limit 4})
            page1 (impl.indexed/lookup-resources db {:subject (->user super-user-eid)
                                                     :permission :view
                                                     :resource/type :server
                                                     :limit 2})
            page2 (impl.indexed/lookup-resources db {:subject (->user super-user-eid)
                                                     :permission :view
                                                     :resource/type :server
                                                     :limit 2
                                                     :cursor (:cursor page1)})
            page1+page2-data (apply concat (map :data [page1 page2]))
            all-eids (map :id page1+page2-data)]
        ;; No duplicates across pages
        (is (= (:data both-pages) page1+page2-data))
        (is (= (count all-eids) (count (distinct all-eids))))
        ;; Results are in order
        (is (= all-eids (sort all-eids)))))))

(deftest can-optimized-tests
  (let [db (d/db *conn*)]
    (testing "Direct permission check"
      (is (impl.indexed/can? db (->user :test/user1) :view (->account :test/account1)))
      (is (not (impl.indexed/can? db (->user :test/user2) :view (->account :test/account1)))))

    (testing "Arrow permission check"
      ;; User1 can view server1 through account admin
      (is (impl.indexed/can? db (->user :test/user1) :view (->server :test/server1)))
      (is (impl.indexed/can? db (->user :test/user1) :start (->server :test/server1)))
      ;; User2 cannot view server1
      (is (not (impl.indexed/can? db (->user :test/user2) :view (->server :test/server1))))
      (is (not (impl.indexed/can? db (->user :test/user2) :start (->server :test/server1)))))

    (testing "Super user permissions"
      (is (impl.indexed/can? db (->user :user/super-user) :view (->server :test/server1)))
      (is (impl.indexed/can? db (->user :user/super-user) :view (->server :test/server2))))

    (testing "Complex nested permissions"
      ;; VPC can view server through network chain
      (is (impl.indexed/can? db (->vpc :test/vpc1) :view (->server :test/server1)))
      (is (not (impl.indexed/can? db (->vpc :test/vpc2) :view (->server :test/server1)))))))

; uncommented because server :owner relation went away.
;(testing "Performance - early termination"
;  ;; Add multiple paths that grant the same permission
;  @(d/transact *conn* [(Relationship (->user :test/user1) :owner (->server :test/server1))])
;  (let [db' (d/db *conn*)]
;    ;; Should still be fast even with multiple valid paths
;    (is (impl.indexed/can? db' (->user :test/user1) :view (->server :test/server1)))))))

(deftest reproduce-infinite-recursion-test
  (testing "lookup-resources should handle cyclic permissions without throwing"
    ;; Introduce a dependency cycle:
    ;; server/view -> server/admin -> account/admin -> server/view
    @(d/transact *conn*
                 [;; 1. Relation for account to have a primary server
                  (Relation :account :server-cycle :server)
        ;; 2. Permission making account/admin depend on server/view, completing the cycle.
                  (Permission :account :admin {:arrow :server-cycle :permission :view})])

    ;; 3. Create a relationship connecting a specific account and server.
    @(d/transact *conn*
                 [(Relationship (->server :test/server1) :primary-server (->account :test/account1))])

    (let [db' (d/db *conn*)]
      ;; This call will trigger the infinite recursion in get-permission-paths
      ;; when checking for :view on a :server. The expected behavior is an exception,
      ;; which this test confirms. A proper fix would involve adding cycle detection
      ;; to get-permission-paths.

      ; this causes infinite loop because we do not guard against loops yet.
      ; this should be prevented at schema write time, not runtime.
      (testing "cycles should not throw (we return empty), as they should be prevented at schema write time"
        (is (lookup-resources db'
                              {:subject (->user :test/user1)
                               :permission :view
                               :resource/type :server}))))))

;;; ============================================================
;;; Cursor Tree Tests (Issue #43)
;;; These tests are written BEFORE implementation (TDD).
;;; They should FAIL until the cursor tree is implemented.
;;; ============================================================

(deftest cursor-tree-structure-test
  (testing "lookup-resources returns v2 cursor with :v, :e, and :p keys"
    (let [db (d/db *conn*)
          super-user-eid (d/entid db :user/super-user)
          result (lookup-resources db {:subject      (->user super-user-eid)
                                       :permission   :admin
                                       :resource/type :server
                                       :limit        2})]
      (is (= 2 (get-in result [:cursor :v])) "cursor should have :v 2")
      (is (some? (get-in result [:cursor :e])) "cursor should have :e (last result eid)")
      (is (map? (get-in result [:cursor :p])) "cursor should have :p map")

      (testing ":p keys are integers (path indices)"
        (let [p (:p (:cursor result))]
          (when (seq p)
            (is (every? integer? (keys p)) ":p keys should be path indices (integers)")
            (is (every? integer? (vals p)) ":p values should be intermediate-cursor-eids (integers)")))))))

(deftest cursor-tree-intermediate-advancement-test
  (testing "intermediate-cursor-eid advances as intermediates are consumed"
    (let [db (d/db *conn*)
          super-user-eid (d/entid db :user/super-user)
          ;; super-user -> platform -> super_admin -> accounts -> servers
          ;; With 3 accounts (account-1, account-2, account-3), each with servers.
          ;; Paginating with small limit should advance intermediate-cursor-eid.
          page1 (lookup-resources db {:subject       (->user super-user-eid)
                                      :permission    :admin
                                      :resource/type :server
                                      :limit         2
                                      :cursor        nil})
          page1-cursor (:cursor page1)

          page2 (lookup-resources db {:subject       (->user super-user-eid)
                                      :permission    :admin
                                      :resource/type :server
                                      :limit         2
                                      :cursor        page1-cursor})]

      (testing "page1 cursor is v2"
        (is (= 2 (:v page1-cursor))))

      (testing "page1 :p has at least one arrow path entry"
        (is (seq (:p page1-cursor)) ":p should have entries for arrow paths"))

      (testing "page2 results have no overlap with page1"
        (let [page1-eids (set (map :id (:data page1)))
              page2-eids (set (map :id (:data page2)))]
          (is (empty? (clojure.set/intersection page1-eids page2-eids))
              "no duplicate results between pages")))

      (testing "page2 results are strictly greater than page1 cursor :e"
        (when-let [cursor-eid (:e page1-cursor)]
          (doseq [item (:data page2)]
            (is (> (:id item) cursor-eid) "page2 results should be > cursor eid")))))))

(deftest cursor-tree-backward-compat-test
  (testing "v1 cursor (no :v key) still works correctly"
    (let [db (d/db *conn*)
          super-user-eid (d/entid db :user/super-user)
          ;; Get first page to find a valid eid for cursor
          page1 (lookup-resources db {:subject       (->user super-user-eid)
                                      :permission    :admin
                                      :resource/type :server
                                      :limit         1
                                      :cursor        nil})
          first-eid (:id (first (:data page1)))
          ;; Use old-style v1 cursor
          v1-cursor {:resource {:type :server :id first-eid}}
          page2-v1 (lookup-resources db {:subject       (->user super-user-eid)
                                         :permission    :admin
                                         :resource/type :server
                                         :limit         10
                                         :cursor        v1-cursor})]

      (testing "v1 cursor returns results after the cursor eid"
        (is (seq (:data page2-v1)) "should have results after first server")
        (doseq [item (:data page2-v1)]
          (is (> (:id item) first-eid) "all results should be > v1 cursor eid")))

      (testing "v1 cursor result also returns v2 cursor"
        (is (= 2 (get-in page2-v1 [:cursor :v])) "output cursor should be v2 even with v1 input")))))

(deftest cursor-tree-pagination-correctness-test
  (testing "paginating with limit 1 yields same results as unpaginated"
    (let [db (d/db *conn*)
          super-user-eid (d/entid db :user/super-user)
          ;; Get all results in one shot
          all-results (:data (lookup-resources db {:subject       (->user super-user-eid)
                                                   :permission    :admin
                                                   :resource/type :server
                                                   :limit         -1}))
          ;; Paginate with limit 1
          paginated (loop [cursor nil
                           pages  []]
                      (let [page (lookup-resources db {:subject       (->user super-user-eid)
                                                       :permission    :admin
                                                       :resource/type :server
                                                       :limit         1
                                                       :cursor        cursor})]
                        (if (empty? (:data page))
                          pages
                          (recur (:cursor page)
                                 (into pages (:data page))))))]

      (testing "paginated results match unpaginated"
        (is (= (map :id all-results)
               (map :id paginated))
            "limit-1 pagination should yield identical results"))

      (testing "no duplicates"
        (is (= (count paginated)
               (count (distinct (map :id paginated))))
            "no duplicate eids across pages")))))

(deftest cursor-tree-performance-test
  (testing "later pages make fewer index-range calls than page 1"
    (let [;; Create many accounts with servers to stress intermediate scanning.
          ;; We already have 3 accounts from fixtures. Add 10 more, each with 2 servers.
          ;; Entities and relationships must be in the SAME transaction for tempid resolution.
          extra-tx (vec (apply concat
                    (for [i (range 10)]
                      [{:db/id    (str "perf-account-" i)
                        :eacl/id  (str "perf-account-" i)}
                       {:db/id    (str "perf-server-" i "-a")
                        :eacl/id  (str "perf-server-" i "-a")}
                       {:db/id    (str "perf-server-" i "-b")
                        :eacl/id  (str "perf-server-" i "-b")}
                       (Relationship (->account (str "perf-account-" i)) :account (->server (str "perf-server-" i "-a")))
                       (Relationship (->account (str "perf-account-" i)) :account (->server (str "perf-server-" i "-b")))
                       (Relationship (->user :user/super-user) :owner (->account (str "perf-account-" i)))
                       (Relationship (fixtures/->platform :test/platform) :platform (->account (str "perf-account-" i)))])))]
      @(d/transact *conn* extra-tx)

      (let [db (d/db *conn*)
            super-user-eid (d/entid db :user/super-user)
            call-count (atom 0)
            orig-index-range d/index-range]

        ;; Page 1 (no cursor) — count index-range calls
        (reset! call-count 0)
        (let [page1 (with-redefs [d/index-range (fn [& args]
                                                   (swap! call-count inc)
                                                   (apply orig-index-range args))]
                      (lookup-resources db {:subject       (->user super-user-eid)
                                            :permission    :admin
                                            :resource/type :server
                                            :limit         4
                                            :cursor        nil}))
              page1-calls @call-count

              ;; Page 3 (with cursor) — should skip exhausted intermediates
              _ (reset! call-count 0)
              page2 (lookup-resources db {:subject       (->user super-user-eid)
                                          :permission    :admin
                                          :resource/type :server
                                          :limit         4
                                          :cursor        (:cursor page1)})
              _ (reset! call-count 0)
              page3 (with-redefs [d/index-range (fn [& args]
                                                   (swap! call-count inc)
                                                   (apply orig-index-range args))]
                      (lookup-resources db {:subject       (->user super-user-eid)
                                            :permission    :admin
                                            :resource/type :server
                                            :limit         4
                                            :cursor        (:cursor page2)}))
              page3-calls @call-count]

          (testing "page3 makes fewer index-range calls than page1"
            (is (< page3-calls page1-calls)
                (str "page3 (" page3-calls " calls) should be < page1 (" page1-calls " calls)"))))))))

(deftest cursor-tree-lookup-subjects-test
  (testing "lookup-subjects returns v2 cursor with intermediate advancement"
    (let [db (d/db *conn*)
          ;; server account1-server1 -> account-1 -> owner -> user-1
          ;; server account1-server1 -> account-1 -> platform -> platform -> super_admin -> super-user
          page1 (lookup-subjects db {:resource      (->server (d/entid db [:eacl/id "account1-server1"]))
                                     :permission    :admin
                                     :subject/type  :user
                                     :limit         1
                                     :cursor        nil})
          page1-cursor (:cursor page1)]

      (testing "cursor is v2"
        (is (= 2 (:v page1-cursor)) "subjects cursor should be v2"))

      (testing "cursor has :e and :p"
        (is (some? (:e page1-cursor)) "cursor should have :e")
        (is (map? (:p page1-cursor)) "cursor should have :p"))

      (testing "page 2 with cursor returns remaining subjects without duplicates"
        (let [page2 (lookup-subjects db {:resource      (->server (d/entid db [:eacl/id "account1-server1"]))
                                         :permission    :admin
                                         :subject/type  :user
                                         :limit         10
                                         :cursor        page1-cursor})
              page1-eids (set (map :id (:data page1)))
              page2-eids (set (map :id (:data page2)))]
          (is (empty? (clojure.set/intersection page1-eids page2-eids))
              "no duplicate subjects between pages"))))))

(deftest cursor-tree-exhausted-path-preservation-test
  (testing "cursor :p entries are preserved even when a path is exhausted"
    (let [db (d/db *conn*)
          super-user-eid (d/entid db :user/super-user)
          ;; Get all pages with limit 1
          pages (loop [cursor nil
                       ps     []]
                  (let [page (lookup-resources db {:subject       (->user super-user-eid)
                                                   :permission    :admin
                                                   :resource/type :server
                                                   :limit         1
                                                   :cursor        cursor})]
                    (if (empty? (:data page))
                      (conj ps page) ; include the final empty page
                      (recur (:cursor page)
                             (conj ps page)))))]

      (testing "all cursors are v2"
        (doseq [page pages]
          (is (= 2 (get-in page [:cursor :v])))))

      (testing "cursor :p entries never disappear once set"
        ;; Once a path index appears in :p, it should remain in subsequent pages
        (let [cursors (map :cursor pages)
              all-p-keys (reduce (fn [seen cursor]
                                   (let [current-keys (set (keys (:p cursor)))]
                                     ;; Every key we've seen before should still be present
                                     (when (seq seen)
                                       (is (clojure.set/subset? seen current-keys)
                                           (str "cursor :p lost keys: had " seen " but now " current-keys)))
                                     (clojure.set/union seen current-keys)))
                           #{}
                           cursors)]
          (is (some? all-p-keys)))))))

(deftest cursor-tree-subjects-pagination-correctness-test
  (testing "paginating lookup-subjects with limit 1 yields same results as unpaginated"
    (let [db (d/db *conn*)
          server1-eid (d/entid db [:eacl/id "account1-server1"])
          ;; Get all results in one shot
          all-results (:data (lookup-subjects db {:resource      (->server server1-eid)
                                                   :permission    :admin
                                                   :subject/type  :user
                                                   :limit         -1}))
          ;; Paginate with limit 1
          paginated (loop [cursor nil
                           pages  []]
                      (let [page (lookup-subjects db {:resource      (->server server1-eid)
                                                      :permission    :admin
                                                      :subject/type  :user
                                                      :limit         1
                                                      :cursor        cursor})]
                        (if (empty? (:data page))
                          pages
                          (recur (:cursor page)
                                 (into pages (:data page))))))]

      (testing "paginated results match unpaginated"
        (is (= (map :id all-results)
               (map :id paginated))
            "limit-1 pagination should yield identical subjects"))

      (testing "no duplicates"
        (is (= (count paginated)
               (count (distinct (map :id paginated))))
            "no duplicate subject eids across pages")))))

(deftest cursor-tree-multi-path-test
  (testing "cursor tree handles multiple arrow paths contributing to same result set"
    ;; Set up: user-1 gets server.admin through BOTH account->admin AND vpc->admin
    ;; plus shared_admin direct relation on another server
    @(d/transact *conn* [;; vpc-1 already has account-1 via account relation
                          ;; Add vpc relation to server1 so vpc->admin path contributes
                          (Relationship (->vpc :test/vpc1) :vpc (->server :test/server1))
                          ;; Make user-1 shared_admin of server2 (direct relation path)
                          (Relationship (->user :test/user1) :shared_admin (->server :test/server2))])

    (let [db (d/db *conn*)
          user1-eid (d/entid db :test/user1)
          ;; Get all results unpaginated
          all-results (:data (lookup-resources db {:subject       (->user user1-eid)
                                                    :permission    :admin
                                                    :resource/type :server
                                                    :limit         -1}))
          ;; Paginate with limit 1
          paginated (loop [cursor nil
                           pages  []]
                      (let [page (lookup-resources db {:subject       (->user user1-eid)
                                                       :permission    :admin
                                                       :resource/type :server
                                                       :limit         1
                                                       :cursor        cursor})]
                        (if (empty? (:data page))
                          pages
                          (recur (:cursor page)
                                 (into pages (:data page))))))]

      (testing "paginated results match unpaginated (completeness)"
        (is (= (map :id all-results)
               (map :id paginated))
            "limit-1 pagination should yield identical results across multi-path"))

      (testing "no duplicates"
        (is (= (count paginated)
               (count (distinct (map :id paginated))))
            "no duplicate eids across pages despite multiple contributing paths"))

      (testing "all cursors are v2 with :p map"
        ;; Re-paginate to check cursors
        (let [page1 (lookup-resources db {:subject       (->user user1-eid)
                                           :permission    :admin
                                           :resource/type :server
                                           :limit         1
                                           :cursor        nil})]
          (is (= 2 (get-in page1 [:cursor :v])))
          (is (map? (get-in page1 [:cursor :p]))))))))

(deftest cursor-tree-self-permission-test
  (testing "cursor tree works through self-permission indirection (server.view = admin + ...)"
    (let [db (d/db *conn*)
          super-user-eid (d/entid db :user/super-user)
          ;; server.view uses self-permission admin, plus nic->view, shared_member, backup_creator
          ;; Get all results in one shot
          all-results (:data (lookup-resources db {:subject       (->user super-user-eid)
                                                    :permission    :view
                                                    :resource/type :server
                                                    :limit         -1}))
          ;; Paginate with limit 1
          paginated (loop [cursor nil
                           pages  []]
                      (let [page (lookup-resources db {:subject       (->user super-user-eid)
                                                       :permission    :view
                                                       :resource/type :server
                                                       :limit         1
                                                       :cursor        cursor})]
                        (if (empty? (:data page))
                          pages
                          (recur (:cursor page)
                                 (into pages (:data page))))))]

      (testing "paginated results match unpaginated through self-permission"
        (is (= (map :id all-results)
               (map :id paginated))
            "self-permission pagination should yield identical results"))

      (testing "no duplicates"
        (is (= (count paginated)
               (count (distinct (map :id paginated))))
            "no duplicate eids across pages with self-permission")))))

(deftest cursor-tree-empty-idempotency-test
  (testing "calling with exhausted cursor is idempotent"
    (let [db (d/db *conn*)
          super-user-eid (d/entid db :user/super-user)
          ;; Get all results paginated to find the final cursor
          final-cursor (loop [cursor nil]
                         (let [page (lookup-resources db {:subject       (->user super-user-eid)
                                                          :permission    :admin
                                                          :resource/type :server
                                                          :limit         1
                                                          :cursor        cursor})]
                           (if (empty? (:data page))
                             (:cursor page)
                             (recur (:cursor page)))))
          ;; Call twice more with the exhausted cursor
          call1 (lookup-resources db {:subject       (->user super-user-eid)
                                       :permission    :admin
                                       :resource/type :server
                                       :limit         10
                                       :cursor        final-cursor})
          call2 (lookup-resources db {:subject       (->user super-user-eid)
                                       :permission    :admin
                                       :resource/type :server
                                       :limit         10
                                       :cursor        final-cursor})]

      (testing "both calls return empty data"
        (is (empty? (:data call1)))
        (is (empty? (:data call2))))

      (testing "both calls return identical cursors"
        (is (= (:cursor call1) (:cursor call2))))

      (testing "cursor matches the exhausted cursor"
        (is (= final-cursor (:cursor call1)))))))

(deftest cursor-tree-count-with-v2-cursor-test
  (testing "count-resources works correctly with v2 cursor from lookup-resources"
    (let [db (d/db *conn*)
          super-user-eid (d/entid db :user/super-user)
          ;; Get all results
          total-count (:count (count-resources db {:subject       (->user super-user-eid)
                                                    :permission    :admin
                                                    :resource/type :server
                                                    :limit         -1}))
          ;; Get first page
          page1 (lookup-resources db {:subject       (->user super-user-eid)
                                       :permission    :admin
                                       :resource/type :server
                                       :limit         2
                                       :cursor        nil})
          page1-count (count (:data page1))
          ;; Count remaining with v2 cursor from page1
          remaining (count-resources db {:subject       (->user super-user-eid)
                                          :permission    :admin
                                          :resource/type :server
                                          :limit         -1
                                          :cursor        (:cursor page1)})]

      (testing "count of remaining equals total minus page1"
        (is (= (- total-count page1-count)
               (:count remaining))
            "remaining count should equal total - page1"))

      (testing "remaining cursor is v2"
        (is (= 2 (get-in remaining [:cursor :v])))))))

(deftest permission-paths-caching-test
  (let [db (d/db *conn*)]
    (testing "Caching of permission paths"
      (impl.indexed/evict-permission-paths-cache!)

      (let [calc-calls (atom 0)
            orig-calc impl.indexed/calc-permission-paths]
        (with-redefs [impl.indexed/calc-permission-paths (fn [& args]
                                                           (swap! calc-calls inc)
                                                           (apply orig-calc args))]
          ;; First call - should compute
          (let [paths1 (impl.indexed/get-permission-paths db :account :view)]
            (is (pos? (count paths1)))
            (is (pos? @calc-calls) "Should have called calc-permission-paths")

            (reset! calc-calls 0)

            ;; Second call - should be cached
            (let [paths2 (impl.indexed/get-permission-paths db :account :view)]
              (is (pos? (count paths2)))
              (is (= paths1 paths2))
              (is (zero? @calc-calls) "Should use cache, not call calc-permission-paths")

              ;; Evict cache
              (impl.indexed/evict-permission-paths-cache!)

              ;; Third call - should recompute
              (let [paths3 (impl.indexed/get-permission-paths db :account :view)]
                (is (pos? (count paths3)))
                (is (pos? @calc-calls) "Should call calc-permission-paths after eviction")))))))))

(deftest tracking-min-test
  (testing "tracking-min records minimum value that passes through"
    (let [!min (volatile! nil)
          result (doall (#'impl.indexed/tracking-min !min 42 [1 2 3]))]
      (is (= [1 2 3] result) "elements pass through unchanged")
      (is (= 42 @!min) "volatile records the value")))

  (testing "tracking-min picks minimum across multiple calls"
    (let [!min (volatile! nil)]
      (doall (#'impl.indexed/tracking-min !min 50 [1]))
      (doall (#'impl.indexed/tracking-min !min 30 [2]))
      (doall (#'impl.indexed/tracking-min !min 70 [3]))
      (is (= 30 @!min) "should be minimum of 50, 30, 70")))

  (testing "tracking-min with empty coll leaves volatile nil"
    (let [!min (volatile! nil)
          result (doall (#'impl.indexed/tracking-min !min 99 []))]
      (is (= [] result))
      (is (nil? @!min) "no elements means volatile stays nil"))))

(deftest count-resources-propagates-volatile-state-test
  (testing "count-resources cursor :p advances (not just pass-through)"
    (let [db (d/db *conn*)
          super-user-eid (d/entid db :user/super-user)
          ;; Get page1 from lookup-resources
          page1 (lookup-resources db {:subject       (->user super-user-eid)
                                       :permission    :admin
                                       :resource/type :server
                                       :limit         2
                                       :cursor        nil})
          ;; Now count remaining with page1's cursor
          remaining (count-resources db {:subject       (->user super-user-eid)
                                          :permission    :admin
                                          :resource/type :server
                                          :limit         -1
                                          :cursor        (:cursor page1)})
          count-cursor (:cursor remaining)
          lookup-cursor (:cursor page1)]
      ;; If count-resources propagates volatile state, its cursor :p
      ;; should be at least as populated as the input cursor's :p
      (when (seq (:p lookup-cursor))
        (is (seq (:p count-cursor))
            "count-resources cursor :p should not lose intermediate positions")))))

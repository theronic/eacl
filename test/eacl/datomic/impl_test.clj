(ns eacl.datomic.impl-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.fixtures :as fixtures :refer [->user ->server ->account ->vpc ->nic ->network ->lease]]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.impl :as impl
             :refer [Relation Relationship Permission
                     can? lookup-subjects
                     read-relationships]]
    ;[eacl.datomic.impl-fixed :refer [lookup-resources count-resources]])) ; use this once impl-fixed is ready.
            [eacl.datomic.impl-optimized :refer [count-resources lookup-resources]]))

; Test grouping & cleanup is in progress.

(def ^:dynamic *conn* nil)

(defn eacl-schema-fixture [f]
  (with-mem-conn [conn schema/v5-schema]
    (is @(d/transact conn fixtures/base-fixtures))
    (binding [*conn* conn]
      (doall (f)))))

(t/use-fixtures :each eacl-schema-fixture)

;; Test Helpers

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

(comment

  (d/delete-database in-mem-uri)

  (do
    (def in-mem-uri "datomic:mem://stateful-test")

    (let [created? (d/create-database in-mem-uri)
          conn     (d/connect in-mem-uri)]
      (prn 'created? created?)

      (when created?
        (prn 'transacting 'schema)
        @(d/transact conn schema/v5-schema)
        @(d/transact conn fixtures/base-fixtures))

      (->> (lookup-resources (d/db conn)
                             {:subject       (->vpc :test/vpc1)
                              :permission    :view
                              :resource/type :server
                              :limit         1000
                              :cursor        nil})
           (:data)))))

(defn paginated->spice
  "To make tests pass after we moved to eids in internals."
  [db {:as page :keys [data cursor]}]
  ;(prn 'paginated-spice page)
  (->> data
       (map (fn [{:as obj :keys [type id]}]
              (let [ent (d/entity db id)]
                (spice-object type (:eacl/id ent)))))))

(defn paginated->spice-set
  "To make tests pass after we moved to eids in internals."
  [db {:as page :keys [data cursor]}]
  (set (paginated->spice db page)))

;(defn exists? [db ident-lookup]
;  {:pre [(vector? ident-lookup)]}
;  (cond
;    (number? ident-lookup) (seq (d/datoms db :eavt ident-lookup))
;    (vector? ident-lookup) (some? (d/entid db ident-lookup))
;    (keyword? ident-lookup) (d/entid db ident-lookup)))

(deftest permission-helper-tests
  (testing "Permission helper with new unified API"
    (is (= #:eacl.permission{:resource-type   :server
                             :permission-name :admin
                             :target-type     :relation
                             :target-name     :owner}
           (Permission :server :admin {:relation :owner})))
    (testing "arrow permission to permission"
      (is (= #:eacl.permission{:resource-type        :server
                               :permission-name      :admin
                               :source-relation-name :account
                               :target-type          :permission
                               :target-name          :admin}
             (Permission :server :admin {:arrow :account :permission :admin}))))
    (testing "arrow permission to relation"
      (is (= #:eacl.permission{:resource-type        :server
                               :permission-name      :view
                               :source-relation-name :account
                               :target-type          :relation
                               :target-name          :owner}
             (Permission :server :view {:arrow :account :relation :owner}))))))

(deftest check-permission-tests
  (let [db             (d/db *conn*)
        super-user-eid (d/entid db :user/super-user)]

    (testing "we can count resources (materializes full index)"
      (is (= 3 (count-resources db {:subject       (->user super-user-eid)
                                    :permission    :view
                                    :resource/type :server
                                    :limit         1000
                                    :cursor        nil}))))

    (testing ":test/user can :view and :reboot their server"
      (is (can? db (->user :test/user1) :view (->server :test/server1)))
      (is (can? db (->user :test/user1) :reboot (->server :test/server1))))

    (testing "can? supports :db/id and idents"
      (let [user1-eid (d/entid db :test/user1)]
        (is (can? db (->user user1-eid) :view (->server :test/server1))))

      (is (can? db (->user [:eacl/id "user-1"]) :view (->server [:eacl/id "account1-server1"])))
      (is (can? db (->user :test/user1) :view (->server [:eacl/id "account1-server1"]))))

    (testing "can? supports passing :db/id directly"
      (let [subject-eid  (d/entid db :test/user1)
            resource-eid (d/entid db :test/server1)]
        (is (can? db (->user subject-eid) :view (->server resource-eid)))))

    (testing "...but :test/user2 can't."
      (is (not (can? db (->user :test/user2) :view (->server :test/server1))))
      (is (not (can? db (->user :test/user2) :reboot (->server :test/server1)))))

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
      ; server -> vpc via NIC & lease
      ; so what's the permission there?
      ; basically, we want to find all the servers in a given VPC...
      ; so the permission is on server, but for the VPC via the thing
      ; model: server -> nic -> lease <- network <- vpc.
      ;(is (can? db (->nic :test/nic1) :view (->server :test/server1)))
      (is (can? db (->lease :test/lease1) :view (->server :test/server1)))
      (is (can? db (->network :test/network1) :view (->server :test/server1)))
      (is (can? db (->vpc :test/vpc1) :view (->server :test/server1))) ; why does htis work, but below does not?

      (is (not (can? db (->vpc :test/vpc2) :view (->server :test/server1))))

      (is (= [(->server "account1-server1")]
             (->> (lookup-resources db
                                    {:subject       (->vpc :test/vpc1)
                                     :permission    :view
                                     :resource/type :server
                                     :limit         1000
                                     :cursor        nil})
                  (paginated->spice db)))))))

(deftest read-relationships-tests
  (let [db (d/db *conn*)]
    (testing "we can find a relationship using internals"
      ; todo update for opts & internals
      (is (= {:eacl.relationship/subject       {:db/ident :test/user1}
              :eacl.relationship/subject-type  :user
              :eacl.relationship/relation-name :owner
              :eacl.relationship/resource      {:db/ident :test/account1}
              :eacl.relationship/resource-type :account}
             (let [rel-eid (impl/find-one-relationship-id db {:subject  (->user :test/user1)
                                                              :relation :owner
                                                              :resource (->account :test/account1)})]
               (d/pull db '[:eacl.relationship/subject-type
                            :eacl.relationship/resource-type
                            {:eacl.relationship/subject [:db/ident]}
                            :eacl.relationship/relation-name
                            {:eacl.relationship/resource [:db/ident]}] rel-eid)))))

    (testing "find-one-relationship-by-id throws if you pass missing subject or resource"
      (is (thrown? Throwable (impl/find-one-relationship-id db {:subject  (->user "missing-user")
                                                                :relation :owner
                                                                :resource (->account "account-1")}))))

    (testing "read-relationships filters"
      ;(is (= [] (read-relationships db {})))
      ; need better tests here.
      (is (= #{:server} (set (map (comp :type :resource) (read-relationships db {:resource/type :server})))))
      (is (= #{:owner} (set (map :relation (read-relationships db {:resource/relation :owner})))))
      (is (= #{:account} (set (map :relation (read-relationships db {:resource/type     :server
                                                                     :resource/relation :account}))))))))

(deftest lookup-subjects-tests
  (let [db (d/db *conn*)]
    (testing "We can enumerate subjects that can access a resource."
      ; Bug: currently returns the subject itself which needs a fix.
      (is (= #{(->user "user-1")
               ;(spice-object :account "account-1")
               (->user "super-user")}
             (->> (lookup-subjects db {:resource     (->server (d/entid db :test/server1))
                                       :permission   :view
                                       :subject/type :user})
                  (paginated->spice-set db))))

      (testing ":test/user2 is only subject who can delete :test/server2"
        (is (= #{(->user "user-2")
                 (->user "super-user")}
               ; todo pagination + cursor. this is outdated.
               (->> (lookup-subjects db {:resource     (->server (d/entid db [:eacl/id "account2-server1"]))
                                         :permission   :delete
                                         :subject/type :user})
                    (paginated->spice-set db))))))))

(deftest eacl3-tests

  (let [db             (d/db *conn*)
        super-user-eid (d/entid db :user/super-user)
        user1-eid      (d/entid db :test/user1)
        user2-eid      (d/entid db :test/user2)]

    (testing "lookup-resources: super-user can view all servers"
      ; note: the return order of `lookup-resources` depends on the tuple index, or any sort operation.
      ; currently we sort, which is super slow. this needs to revert to the tuple-index order.
      (is (= #{(spice-object :server "account1-server1")
               (spice-object :server "account1-server2")
               (spice-object :server "account2-server1")}
             (->> (lookup-resources db {:subject       (->user super-user-eid)
                                        :permission    :view
                                        :resource/type :server
                                        :limit         1000
                                        :cursor        nil})
                  (paginated->spice db)
                  (set)))))

    (testing "We can enumerate resources with lookup-resources"
      (is (= #{(spice-object :server "account1-server1")
               (spice-object :server "account1-server2")}
             (->> (lookup-resources db {:subject       (->user user1-eid)
                                        :permission    :view
                                        :resource/type :server})
                  (:data)
                  (map (fn [{:as obj :keys [type id]}] (spice-object type (:eacl/id (d/entity db id)))))
                  (set))))

      (testing "count-resources returns 2 as above"
        (is (= 2 (count-resources db {:subject       (->user user1-eid)
                                      :permission    :view
                                      :resource/type :server}))))

      (testing "same for :reboot permission"
        (is (= [(->server "account1-server1")
                (->server "account1-server2")]
               ; todo cursor
               (->> (lookup-resources db {:subject       (->user user1-eid)
                                          :permission    :reboot
                                          :resource/type :server})
                    (paginated->spice db)))))

      (testing "we can enumerate :account objects user1 can :view"
        (is (= #{(->account "account-1")}
               (->> (lookup-resources db {:subject       (->user user1-eid)
                                          :permission    :view
                                          :resource/type :account})
                    (paginated->spice db)
                    (set))))

        (testing "count matches lookup"
          (is (= 1 (count-resources db {:subject       (->user user1-eid)
                                        :permission    :view
                                        :resource/type :account})))))

      (is (= #{(->server "account2-server1")}
             (->> (lookup-resources db
                                    {:subject       (->user user2-eid)
                                     :permission    :view
                                     :resource/type :server})
                  (paginated->spice db)
                  (set)))))

    (testing "Make user-1 a shared_admin of server-2"
      (is @(d/transact *conn* [(Relationship (->user :test/user1) :shared_admin (->server :test/server2))]))) ; this shouldn't be working. no schema for it.

    (let [db (d/db *conn*)]
      (testing "Now :test/user1 can also :server/delete server 2"
        (is (can? db (->user :test/user1) :delete (->server :test/server2)))

        (is (= #{(spice-object :server "account1-server1")
                 (spice-object :server "account1-server2")
                 (spice-object :server "account2-server1")}
               (->> (lookup-resources db {:subject       (->user user1-eid)
                                          :permission    :view
                                          :resource/type :server
                                          :cursor        nil})
                    (paginated->spice-set db))))

        (is (= #{(->user "super-user")
                 (->user "user-1")
                 (->user "user-2")}
               (->> (lookup-subjects db {:resource     (->server (d/entid db [:eacl/id "account2-server1"])) ; todo fix
                                         :permission   :delete
                                         :subject/type :user})
                    (paginated->spice-set db))))))

    (testing "Now let's delete all :server/owner Relationships for :test/user2"
      (let [db-for-delete (d/db *conn*)
            rels          (d/q '[:find [(pull ?rel [* {:eacl/subject [*]}]) ...]
                                 :where
                                 [?rel :eacl.relationship/subject :test/user2]
                                 [?rel :eacl.relationship/relation-name :owner]]
                               db-for-delete)]
        (is @(d/transact *conn* (for [rel rels] [:db.fn/retractEntity (:db/id rel)])))))

    (testing "Now only user-1 can :view all 3 servers, including those in account2."
      (let [db' (d/db *conn*)]
        (is (= #{(spice-object :user "user-1")
                 (spice-object :user "super-user")}
               (->> (lookup-subjects db' {:resource     (->server (d/entid db [:eacl/id "account2-server1"]))
                                          :permission   :view
                                          :subject/type :user})
                    (paginated->spice-set db))))

        (testing ":test/user2 cannot access any servers"    ; is this correct?
          (is (= #{} (->> (lookup-resources db' {:resource/type :server
                                                 :permission    :view
                                                 :subject       (->user [:eacl/id "user-2"])})
                          (:data)
                          (set))))
          (testing "count-resources also zero"
            (is (zero? (count-resources db' {:resource/type :server
                                             :permission    :view
                                             :subject       (->user [:eacl/id "user-2"])})))))

        (is (not (can? db' (->user :test/user2) :delete (->server :test/server2))))

        (testing ":test/user1 permissions remain unchanged"
          (is (= #{(spice-object :server "account1-server1")
                   (spice-object :server "account1-server2")
                   (spice-object :server "account2-server1")}
                 (->> (lookup-resources db' {:resource/type :server
                                             :permission    :reboot
                                             :subject       (->user user1-eid)})
                      (paginated->spice-set db))))

          (testing "super-user can still view all servers"
            (is (= #{(spice-object :server "account1-server1")
                     (spice-object :server "account1-server2")
                     (spice-object :server "account2-server1")}
                   (->> (lookup-resources db' {:resource/type :server
                                               :permission    :reboot
                                               :subject       (->user super-user-eid)})
                        (paginated->spice-set db)))))

          (is (= #{(spice-object :account "account-1")}
                 (->> (lookup-resources db' {:resource/type :account
                                             :permission    :view
                                             :subject       (->user user1-eid)})
                      (paginated->spice-set db)))))))))

(deftest lookup-resources-tests
  (let [db             (d/db *conn*)
        super-user-eid (d/entid db :user/super-user)]

    (testing "pagination: limit & offset are handled correctly for arrow permissions"
      (testing "add a 3rd server. make super-user a direct shared_admin of server1 and server 3 to try and trip up pagination"
        (is @(d/transact *conn* [{:db/id    "server3"
                                  :db/ident :test/server3
                                  :eacl/id  "server-3"}
                                 (Relationship (->user :user/super-user) :shared_admin (->server :test/server1))
                                 ; We can use tempids in Relationship because it produces 's in same tx-data.
                                 (Relationship (->user :user/super-user) :shared_admin (->server "server3"))])))

      (let [db' (d/db *conn*)]
        (testing "ensure user1 can only see servers from account1, so excludes server-3"
          (is (= [(spice-object :server "account1-server1")
                  (spice-object :server "account1-server2")]
                 (->> (lookup-resources db' {:subject       (->user :test/user1)
                                             :permission    :view
                                             :resource/type :server
                                             :cursor        nil})
                      (paginated->spice db')))))

        (testing "enumerate super-user servers with limit: 10 & nil cursor should return all 4 servers"
          (is (= [(spice-object :server "account1-server1")
                  (spice-object :server "account1-server2")
                  (spice-object :server "account2-server1")
                  (spice-object :server "server-3")]
                 (->> (lookup-resources db' {:limit         10
                                             :cursor        nil ; no cursor should return all 4 servers
                                             :resource/type :server
                                             :permission    :view
                                             :subject       (->user super-user-eid)})
                      (paginated->spice db'))))

          (testing "count-resources matches above"
            (is (= 4 (count-resources db' {:resource/type :server
                                           :permission    :view
                                           :subject       (->user super-user-eid)})))))

        (testing "limit: 10 with cursor set to 1st result should exclude the first result"
          ; test failing because return order is oriented towards how it's stored in index
          (is (= [(spice-object :server "account1-server2") ; (spice-object :server "account1-server1") is excludced.
                  (spice-object :server "account2-server1")
                  (spice-object :server "server-3")]
                 ; cursor seems weird here. shouldn't it be exclusive?
                 (->> (lookup-resources db' {:limit         10
                                             :cursor        {:resource {:type :account
                                                                        :id   (d/entid db' [:eacl/id "account1-server1"])}}
                                             :resource/type :server
                                             :permission    :view
                                             :subject       (->user super-user-eid)})
                      (paginated->spice db')))))

        ; Note that return order of Spice resources is not defined, because we do not sort during lookup.
        ; We assume order will be: [server-1, server-3, server-2].
        (testing "limit 1 with cursor set to 2nd result should skip first two and return only 3rd result"
          (is (= [(spice-object :server "account2-server1")]
                 ;(spice-object :server "server-3")] excluded because of limit.
                 (->> (lookup-resources db' {:cursor        {:resource {:type :server
                                                                        :id   (d/entid db' [:eacl/id "account1-server2"])}}
                                             :limit         1
                                             :resource/type :server
                                             :permission    :view
                                             :subject       (->user super-user-eid)})
                      (paginated->spice db)))))

        (testing "limit 1, 3rd cursor offset should return 2nd result, server-3"
          (is (= [(spice-object :server "server-3")]
                 (->> (lookup-resources db' {:cursor        {:resource {:type :server
                                                                        :id   (d/entid db' [:eacl/id "account2-server1"])}}
                                             :limit         1
                                             :resource/type :server
                                             :permission    :view
                                             :subject       (->user super-user-eid)})
                      (paginated->spice db')))))

        (testing "limit: 10, cursor at 3rd result only the 4th result"
          (is (= [(spice-object :server "server-3")]
                 (->> (lookup-resources db' {:cursor        {:resource {:type :server
                                                                        :id   (d/entid db' [:eacl/id "account2-server1"])}}
                                             :limit         10
                                             :resource/type :server
                                             :permission    :view
                                             :subject       (->user super-user-eid)})
                      (paginated->spice db')))))

        (testing "offset: last cursor, limit: 10 should be empty"
          (is (empty? (->> (lookup-resources db' {:limit         10
                                                  :cursor        {:resource {:type :server
                                                                             :id   (d/entid db' [:eacl/id "server-3"])}}
                                                  :resource/type :server
                                                  :permission    :view
                                                  :subject       (->user super-user-eid)})
                           (paginated->spice db'))))))

      (let [db' (d/db *conn*)]
        (testing "ask for 5 results (there are only 4), and ensure page1 & page2 are subsequences with matching cursor"
          (let [both-pages          (lookup-resources db' {:limit         5
                                                           :cursor        nil ; :cursor nil = first page.
                                                           :resource/type :server
                                                           :permission    :view
                                                           :subject       (->user super-user-eid)})

                both-pages-resolved (paginated-data->spice db' (:data both-pages))
                [page1-expected
                 page2-expected
                 page3-expected] (partition-all 2 both-pages-resolved)

                {:as          page1
                 page1-data   :data
                 page1-cursor :cursor} (lookup-resources db' {:limit         2
                                                              :cursor        nil ; no cursor should the first page.
                                                              :resource/type :server
                                                              :permission    :view
                                                              :subject       (->user super-user-eid)})
                _                   (prn 'page1 'cursor (:cursor page1))
                {:as          page2
                 page2-data   :data
                 page2-cursor :cursor} (lookup-resources db' {:limit         2
                                                              :cursor        (:cursor page1)
                                                              :resource/type :server
                                                              :permission    :view
                                                              :subject       (->user super-user-eid)})
                {:as          page3-empty
                 page3-data   :data
                 page3-cursor :cursor} (->> (lookup-resources db' {:limit         2
                                                                   :cursor        (:cursor page2)
                                                                   :resource/type :server
                                                                   :permission    :view
                                                                   :subject       (->user super-user-eid)}))

                _                   (prn 'page2 'cursor (:cursor page2))]

            (testing "page1 cursor points to next page"
              (is page1-cursor)
              (is (:resource page1-cursor)))

            (testing "page2 cursor points to next page"
              (is page2-cursor)
              (is (:resource page2-cursor)))

            (testing "page3 cursor is nil because exhausted. Should this return the previous cursor?"
              ; TODO: figure out what this should return. The previous cursor?
              ; we probably need a flag for empty, but empty :data implies that.
              (is (not (:resource page3-cursor))))

            (testing "limit 5 should include all 4 results, in sorted order" ; (depends on tuple index or costly sort)
              (is (= [(spice-object :server "account1-server1")
                      (spice-object :server "account1-server2")
                      (spice-object :server "account2-server1")
                      (spice-object :server "server-3")]
                     (paginated-data->spice db' (:data both-pages)))))

            (testing "page1 with 0-1 of 4 should match the first 2 results"
              (is (= page1-expected (paginated-data->spice db' page1-data))))

            (testing "page2 with 2-4 of 4 should match the second two results"
              (is (= page2-expected (paginated-data->spice db' page2-data))))

            (testing "page3 with 5-6 of 4 should be empty (results exhausted)"
              (is (nil? page3-expected))
              (is (empty? (paginated-data->spice db' page3-data))))

            (testing "repeating page2 query with limit: 1 should return only the first result on that page"
              (is (= (take 1 page2-expected)
                     (->> (lookup-resources db' {:limit         1
                                                 :cursor        page1-cursor
                                                 :resource/type :server
                                                 :permission    :view
                                                 :subject       (->user super-user-eid)})
                          (paginated->spice db')))))

            (testing "repeat page2 query with limit: 10 should return remainder"
              (is (= (drop 2 both-pages-resolved)
                     (->> (lookup-resources db' {:limit         10
                                                 :cursor        page1-cursor
                                                 :resource/type :server
                                                 :permission    :view
                                                 :subject       (->user super-user-eid)})
                          (paginated->spice db')))))

            (testing "count-resources should return count of all result"
              (is (= 4 (count-resources db' {:resource/type :server
                                             :permission    :view
                                             :subject       (->user super-user-eid)}))))

            (testing "count-resources is also cursor-sensitive"
              (is (= 2 (count-resources db' {:resource/type :server
                                             :permission    :view
                                             :cursor        page1-cursor
                                             :subject       (->user super-user-eid)})))

              (is (zero? (count-resources db' {:resource/type :server
                                               :permission    :view
                                               :subject       (->user super-user-eid)
                                               :cursor        page2-cursor}))))))))))
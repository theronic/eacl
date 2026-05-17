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
            [eacl.datomic.impl.indexed :as impl.indexed :refer [count-resources lookup-resources lookup-subjects]]))

; Test grouping & cleanup is in progress.

(def ^:dynamic *conn* nil)

(deftest lazy-merge-sort-tests
  (is (= [1 2 3 4 5 6 7 8 9 10 11]
         (let [seq1 [1 3 5 7 9]
               seq2 [2 3 6 8 9 10]
               seq3 [1 4 5 6 11]]
           (take 20 (lazy-merge-dedupe-sort [seq1 seq2 seq3]))))))

;; Load schema from SpiceDB DSL file
(def fixtures-schema-string
  (slurp "test/eacl/fixtures.schema"))

(defn eacl-schema-fixture [f]
  (with-mem-conn [conn schema/v7-schema]
    ;; Use write-schema! with SpiceDB DSL instead of direct Relation/Permission fixtures
    (schema/write-schema! conn fixtures-schema-string)
    ;; Transact entity fixtures and relationship fixtures together (tempids reference each other)
    (is @(d/transact conn (concat fixtures/entity-fixtures
                                  (fixtures/relationship-fixtures (d/db conn)))))
    (is @(d/transact conn (fixtures/txes-additional-account3+server (d/db conn))))
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
        @(d/transact conn schema/v7-schema)
        @(d/transact conn (fixtures/base-fixtures (d/db conn))))

      (->> (lookup-resources (d/db conn)
                             {:subject (->vpc :test/vpc1)
                              :permission :view
                              :resource/type :server
                              :first 1000})
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
  [db {:as page :keys [data]}]
  (paginated-data->spice db data))

(defn paginated->spice-set
  "To make tests pass after we moved to eids in internals."
  [db {:as page}]
  (set (paginated->spice db page)))

(defn page-start-cursor
  [page]
  (get-in page [:page-info :start-cursor]))

(defn page-end-cursor
  [page]
  (get-in page [:page-info :end-cursor]))

(defn lookup-cursor
  [db eacl-id]
  {:kind :lookup-eid
   :result-eid (d/entid db [:eacl/id eacl-id])})

(def ^:private forward-relationship-attr
  :eacl.v7.relationship/subject-type+relation+resource-type+resource)

(defn- relationship-eid-pair
  [{:keys [subject resource]}]
  [(:id subject) (:id resource)])

(defn- relationship-sort-key
  [{:keys [subject relation resource]}]
  [(:type subject) (:id subject) (:type resource) relation (:id resource)])

(def recursive-parent-schema-string
  "definition user {}

   definition account {
     relation parent: account
     relation reader: user

     permission read = reader + parent->read
   }")

(def recursive-document-schema-string
  "definition user {}

   definition folder {
     relation parent: folder
     relation reader: user

     permission read = reader + parent->read
   }

   definition document {
     relation folder: folder
     relation viewer: user

     permission view = viewer + folder->read
   }")

(defn- load-recursive-parent-db!
  [conn]
  (schema/write-schema! conn recursive-parent-schema-string)
  @(d/transact conn [{:db/id "user-1"
                      :eacl/id "user-1"}
                     {:db/id "root"
                      :eacl/id "root"}
                     {:db/id "child"
                      :eacl/id "child"}
                     {:db/id "grandchild"
                      :eacl/id "grandchild"}])
  @(d/transact conn
               (into []
                     (mapcat #(impl/tx-relationship (d/db conn) %))
                     [(Relationship (spice-object :account "root") :parent (spice-object :account "child"))
                      (Relationship (spice-object :account "child") :parent (spice-object :account "grandchild"))
                      (Relationship (spice-object :user "user-1") :reader (spice-object :account "root"))]))
  (d/db conn))

(defn- recursive-user-ref
  [eacl-id]
  (spice-object :user [:eacl/id eacl-id]))

(defn- recursive-account-ref
  [eacl-id]
  (spice-object :account [:eacl/id eacl-id]))

(defn- load-recursive-document-db!
  [conn]
  (schema/write-schema! conn recursive-document-schema-string)
  @(d/transact conn [{:db/id "user-1"
                      :eacl/id "user-1"}
                     {:db/id "root-folder"
                      :eacl/id "root-folder"}
                     {:db/id "child-folder"
                      :eacl/id "child-folder"}
                     {:db/id "doc-1"
                      :eacl/id "doc-1"}])
  @(d/transact conn
               (into []
                     (mapcat #(impl/tx-relationship (d/db conn) %))
                     [(Relationship (spice-object :folder "root-folder") :parent (spice-object :folder "child-folder"))
                      (Relationship (spice-object :user "user-1") :reader (spice-object :folder "root-folder"))
                      (Relationship (spice-object :folder "child-folder") :folder (spice-object :document "doc-1"))]))
  (d/db conn))

(defn- load-recursive-out-of-eid-order-db!
  [conn]
  (schema/write-schema! conn recursive-parent-schema-string)
  @(d/transact conn [{:db/id "user-1"
                      :eacl/id "user-1"}
                     {:db/id "child"
                      :eacl/id "child"}
                     {:db/id "grandchild"
                      :eacl/id "grandchild"}
                     {:db/id "root"
                      :eacl/id "root"}])
  @(d/transact conn
               (into []
                     (mapcat #(impl/tx-relationship (d/db conn) %))
                     [(Relationship (spice-object :account "root") :parent (spice-object :account "child"))
                      (Relationship (spice-object :account "child") :parent (spice-object :account "grandchild"))
                      (Relationship (spice-object :user "user-1") :reader (spice-object :account "root"))]))
  (d/db conn))

(defn- thrown-ex-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

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
                                            :resource/type :server})))))

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
        (let [rx (d/with db
                         (impl/tx-relationship db
                                               (Relationship (->user :test/user2) :viewer (->backup-schedule :test/backup-schedule1))))]
          (is (can? (:db-after rx) (->user :test/user2) :view (->backup-schedule :test/backup-schedule1)))
          (is (can? (:db-after rx) (->user :test/user2) :view (->backup :test/backup1)))))

      (is (not (can? db (->vpc :test/vpc2) :view (->server :test/server1))))

      (let [vpc-eid (d/entid db :test/vpc1)]
        (is (= [(->server "account1-server1")]
               (->> (lookup-resources db
                                      {:subject (->vpc vpc-eid)
                                       :permission :view
                                       :resource/type :server
                                       :first 1000})
                    (paginated->spice db))))))))

(deftest read-relationships-tests
  (let [db (d/db *conn*)]
    (testing "we can find a relationship using internals"
      (is (= {:subject-type :user
              :subject-eid (d/entid db :test/user1)
              :relation :owner
              :resource-type :account
              :resource-eid (d/entid db :test/account1)}
             (let [rel (impl/find-one-relationship-id db {:subject (->user :test/user1)
                                                          :relation :owner
                                                          :resource (->account :test/account1)})]
               {:subject-type (:subject-type rel)
                :subject-eid (:subject-eid rel)
                :relation (:relation rel)
                :resource-type (:resource-type rel)
                :resource-eid (:resource-eid rel)}))))

    (testing "find-one-relationship-by-id returns nil for missing subject or resource"
      (is (nil? (impl/find-one-relationship-id db {:subject (->user "missing-user")
                                                   :relation :owner
                                                   :resource (->account "account-1")}))))

    (testing "read-relationships filters"
      ;(is (= [] (read-relationships db {})))
      ; need better tests here.
      (is (= #{:server} (set (map (comp :type :resource)
                                  (:data (read-relationships db {:resource/type :server}))))))
      (is (= #{:owner} (set (map :relation
                                 (:data (read-relationships db {:resource/relation :owner}))))))
      (is (= #{:account} (set (map :relation
                                   (:data (read-relationships db {:resource/type :server
                                                                  :resource/relation :account})))))))

    (testing "read-relationships returns storage order instead of sorting decoded relationships"
      (is @(d/transact *conn*
                       (concat
                        (impl/tx-relationship db
                                              (Relationship (->user :test/user1)
                                                            :owner
                                                            (->account :test/account2)))
                        (impl/tx-relationship db
                                              (Relationship (->user :test/user2)
                                                            :owner
                                                            (->account :test/account1))))))
      (let [db' (d/db *conn*)
            attr-eid (d/entid db' forward-relationship-attr)
            relation-eids (set (d/q '[:find [?relation ...]
                                       :in $ ?relation-name ?subject-type
                                       :where
                                       [?relation :eacl.relation/relation-name ?relation-name]
                                       [?relation :eacl.relation/subject-type ?subject-type]]
                                     db' :owner :user))
            page (read-relationships db' {:subject/type :user
                                          :resource/relation :owner
                                          :first 20})
            expected-storage-order (->> (d/seek-datoms db' :avet attr-eid [:user])
                                        (take-while #(= :user (first (:v %))))
                                        (filter #(contains? relation-eids (nth (:v %) 1)))
                                        (map (fn [datom]
                                               [(:e datom) (nth (:v datom) 3)]))
                                        vec)
            actual-order (mapv relationship-eid-pair (:data page))
            synthetic-sorted-order (->> (:data page)
                                        (sort-by relationship-sort-key)
                                        (mapv relationship-eid-pair))]
        (is (= expected-storage-order actual-order))
        (is (not= synthetic-sorted-order actual-order))
        (is (= :global-forward (get-in page [:page-info :start-cursor :scan])))))))

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
      (let [page1 (lookup-resources db {:subject (->user super-user-eid)
                                        :permission :view
                                        :resource/type :server
                                        :first 1000})
            page1-end-cursor (page-end-cursor page1)
            resources (paginated->spice db page1)]
        (is (= [(spice-object :server "account1-server1")
                (spice-object :server "account1-server2")
                (spice-object :server "account2-server1")]
               resources))
        (testing "end cursor should be the last resource"
          (is page1-end-cursor)
          (let [last-resource-ent (d/entity db (:result-eid page1-end-cursor))
                last-resource-internal (spice-object :server (:eacl/id last-resource-ent))]
            (is (= (spice-object :server "account2-server1") last-resource-internal))))))

    (testing "view_via_arrow_relation works"
      (is (= #{(spice-object :account "account-1")
               (spice-object :account "account-2")}
             (->> (lookup-resources db {:subject (->user super-user-eid)
                                        :permission :view_via_arrow_relation
                                        :resource/type :account
                                        :first 1000})
                  (paginated->spice db)
                  (set))))

      (testing "...and server { permission view_via_arrow_relation = account->view_via_arrow_relation } works")
      (is (= #{(spice-object :server "account1-server1")
               (spice-object :server "account1-server2")
               (spice-object :server "account2-server1")}
             (->> (lookup-resources db {:subject (->user super-user-eid)
                                        :permission :view_server_via_arrow_relation
                                        :resource/type :server
                                        :first 1000})
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
                  (map (fn [{:keys [type id]}] (spice-object type (:eacl/id (d/entity db id)))))
                  (set))))

      (testing "count-resources returns 3 as above"
        (is (= 3 (:count (count-resources db {:subject (->user user1-eid)
                                              :permission :view
                                              :resource/type :server})))))

      (testing "same for :reboot permission"
        (is (= [(->server "account1-server1")
                (->server "account1-server2")
                (->server "account3-server3.1")]
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
      (is @(d/transact *conn*
                       (impl/tx-relationship (d/db *conn*)
                                             (Relationship (->user :test/user1) :shared_admin (->server :test/server2)))))) ; this shouldn't be working. no schema for it.

    (let [db (d/db *conn*)]
      (testing "As a :shared_admin, :test/user1 can also :server/delete server 2"
        (is (can? db (->user :test/user1) :delete (->server :test/server2)))

        (is (= #{(spice-object :server "account1-server1")
                 (spice-object :server "account1-server2")
                 (spice-object :server "account2-server1")
                 (spice-object :server "account3-server3.1")}
               (->> (lookup-resources db {:subject (->user user1-eid)
                                          :permission :view
                                          :resource/type :server})
                    (paginated->spice-set db))))

        (is (= #{(->user "super-user")
                 (->user "user-1")
                 (->user "user-2")}
               (->> (lookup-subjects db {:resource (->server (d/entid db [:eacl/id "account2-server1"])) ; todo fix
                                         :permission :delete
                                         :subject/type :user})
                    (paginated->spice-set db)))))

      (testing "Now let's delete all :server/owner Relationships for :test/user2"
        (let [db-for-delete (d/db *conn*)
              rels (:data (impl/read-relationships db-for-delete {:subject/id :test/user2
                                                                  :resource/relation :owner}))
              txes (mapcat #(impl/tx-update-relationship db-for-delete
                                                         {:operation :delete
                                                          :relationship %})
                           rels)]
          (is @(d/transact *conn* txes))))

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
                        (paginated->spice-set db))))))))))

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

    (testing "pagination: :first/:after and :last/:before are handled correctly for arrow permissions"
      (testing "add a 3rd server. make super-user a direct shared_admin of server1 and server 3 to try and trip up pagination"
        (is @(d/transact *conn* [{:db/id "server3"
                                  :db/ident :test/server3
                                  :eacl/id "server-3"}
                                 (first (impl/tx-relationship (d/db *conn*)
                                                              (Relationship (->user :user/super-user) :shared_admin (->server :test/server1))))
                                 (second (impl/tx-relationship (d/db *conn*)
                                                               (Relationship (->user :user/super-user) :shared_admin (->server :test/server1))))
                                 ; We can use tempids in Relationship because tuple tx-data keeps the tempid.
                                 (first (impl/tx-relationship (d/db *conn*)
                                                              (Relationship (->user :user/super-user) :shared_admin (->server "server3"))))
                                 (second (impl/tx-relationship (d/db *conn*)
                                                               (Relationship (->user :user/super-user) :shared_admin (->server "server3"))))])))

      (let [db' (d/db *conn*)]
        (testing "ensure user1 can only see servers from account1, so excludes server-3"
          (is (= [(spice-object :server "account1-server1")
                  (spice-object :server "account1-server2")
                  (spice-object :server "account3-server3.1")]
                 (->> (lookup-resources db' {:subject (->user :test/user1)
                                             :permission :view
                                             :resource/type :server})
                      (paginated->spice db')))))

        (testing "enumerate super-user servers with :first 10 should return all 4 servers"
          (is (= [(spice-object :server "account1-server1")
                  (spice-object :server "account1-server2")
                  (spice-object :server "account2-server1")
                  (spice-object :server "server-3")]
                 (->> (lookup-resources db' {:first 10
                                             :resource/type :server
                                             :permission :view
                                             :subject (->user super-user-eid)})
                      (paginated->spice db'))))

          (testing "count-resources matches above"
            (is (= 4 (:count (count-resources db' {:resource/type :server
                                                   :permission :view
                                                   :subject (->user super-user-eid)}))))))

        (testing ":after the first result should exclude the first result"
          (is (= [(spice-object :server "account1-server2") ; (spice-object :server "account1-server1") is excludced.
                  (spice-object :server "account2-server1")
                  (spice-object :server "server-3")]
                 (->> (lookup-resources db' {:first 10
                                             :after (lookup-cursor db' "account1-server1")
                                             :resource/type :server
                                             :permission :view
                                             :subject (->user super-user-eid)})
                      (paginated->spice db')))))

        (testing ":first 1 after the second result should skip the first two and return only the third result"
          (is (= [(spice-object :server "account2-server1")]
                 (->> (lookup-resources db' {:after (lookup-cursor db' "account1-server2")
                                             :first 1
                                             :resource/type :server
                                             :permission :view
                                             :subject (->user super-user-eid)})
                      (paginated->spice db)))))

        (testing ":first 1 after the third result should return the fourth result"
          (is (= [(spice-object :server "server-3")]
                 (->> (lookup-resources db' {:after (lookup-cursor db' "account2-server1")
                                             :first 1
                                             :resource/type :server
                                             :permission :view
                                             :subject (->user super-user-eid)})
                      (paginated->spice db')))))

        (testing ":first 10 after the third result returns only the fourth result"
          (is (= [(spice-object :server "server-3")]
                 (->> (lookup-resources db' {:after (lookup-cursor db' "account2-server1")
                                             :first 10
                                             :resource/type :server
                                             :permission :view
                                             :subject (->user super-user-eid)})
                      (paginated->spice db')))))

        (testing ":after the last cursor should be empty"
          (let [empty-page (lookup-resources db' {:first 10
                                                  :after (lookup-cursor db' "server-3")
                                                  :resource/type :server
                                                  :permission :view
                                                  :subject (->user super-user-eid)})]
            (is (empty? (paginated->spice db' empty-page)))
            (is (nil? (page-start-cursor empty-page)))
            (is (nil? (page-end-cursor empty-page)))
            (is (false? (get-in empty-page [:page-info :has-next-page?])))
            (is (true? (get-in empty-page [:page-info :has-previous-page?])))))

        (testing "forward pages compose with :after end-cursor"
          (let [both-pages (lookup-resources db' {:first 5
                                                  :resource/type :server
                                                  :permission :view
                                                  :subject (->user super-user-eid)})
                both-pages-resolved (paginated-data->spice db' (:data both-pages))
                [page1-expected page2-expected page3-expected] (partition-all 2 both-pages-resolved)
                page1 (lookup-resources db' {:first 2
                                             :resource/type :server
                                             :permission :view
                                             :subject (->user super-user-eid)})
                page2 (lookup-resources db' {:first 2
                                             :after (page-end-cursor page1)
                                             :resource/type :server
                                             :permission :view
                                             :subject (->user super-user-eid)})
                page3-empty (lookup-resources db' {:first 2
                                                   :after (page-end-cursor page2)
                                                   :resource/type :server
                                                   :permission :view
                                                   :subject (->user super-user-eid)})]
            (is (page-start-cursor page1))
            (is (page-end-cursor page1))
            (is (page-start-cursor page2))
            (is (page-end-cursor page2))
            (is (= [(spice-object :server "account1-server1")
                    (spice-object :server "account1-server2")
                    (spice-object :server "account2-server1")
                    (spice-object :server "server-3")]
                   both-pages-resolved))
            (is (= page1-expected (paginated-data->spice db' (:data page1))))
            (is (= page2-expected (paginated-data->spice db' (:data page2))))
            (is (nil? page3-expected))
            (is (empty? (paginated-data->spice db' (:data page3-empty))))
            (is (true? (get-in page1 [:page-info :has-next-page?])))
            (is (false? (get-in page1 [:page-info :has-previous-page?])))
            (is (false? (get-in page2 [:page-info :has-next-page?])))
            (is (true? (get-in page2 [:page-info :has-previous-page?])))))

        (testing "backward pages compose with :before start-cursor"
          (let [last-page (lookup-resources db' {:last 2
                                                 :resource/type :server
                                                 :permission :view
                                                 :subject (->user super-user-eid)})
                previous-page (lookup-resources db' {:last 2
                                                     :before (page-start-cursor last-page)
                                                     :resource/type :server
                                                     :permission :view
                                                     :subject (->user super-user-eid)})]
            (is (= [(spice-object :server "account2-server1")
                    (spice-object :server "server-3")]
                   (paginated->spice db' last-page)))
            (is (= [(spice-object :server "account1-server1")
                    (spice-object :server "account1-server2")]
                   (paginated->spice db' previous-page)))
            (is (false? (get-in last-page [:page-info :has-next-page?])))
            (is (true? (get-in last-page [:page-info :has-previous-page?])))
            (is (true? (get-in previous-page [:page-info :has-next-page?])))
            (is (false? (get-in previous-page [:page-info :has-previous-page?])))))

        (testing "count-resources should return the full count independent of list page boundaries"
          (is (= 4 (:count (count-resources db' {:resource/type :server
                                                 :permission :view
                                                 :subject (->user super-user-eid)})))))

        (testing "account pagination supports forward and backward traversal"
          (let [all-accounts (lookup-resources db' {:first 3
                                                    :resource/type :account
                                                    :permission :view
                                                    :subject (->user super-user-eid)})
                page1 (lookup-resources db' {:first 1
                                             :resource/type :account
                                             :permission :view
                                             :subject (->user super-user-eid)})
                page2 (lookup-resources db' {:first 1
                                             :after (page-end-cursor page1)
                                             :resource/type :account
                                             :permission :view
                                             :subject (->user super-user-eid)})
                previous-page (lookup-resources db' {:last 1
                                                     :before (page-start-cursor page2)
                                                     :resource/type :account
                                                     :permission :view
                                                     :subject (->user super-user-eid)})]
            (is (= [(spice-object :account "account-1")
                    (spice-object :account "account-2")]
                   (paginated->spice db' all-accounts)))
            (is (= [(spice-object :account "account-1")]
                   (paginated->spice db' page1)))
            (is (= [(spice-object :account "account-2")]
                   (paginated->spice db' page2)))
            (is (= [(spice-object :account "account-1")]
                   (paginated->spice db' previous-page)))))))))

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
                        (= (:target-permission %) :admin))
                  paths))
        ;; Permission edges stay symbolic and are evaluated at runtime.
        (let [arrow-path (first (filter #(= (:via %) :account) paths))]
          (is (= :admin (:target-permission arrow-path))))))

    (testing "Complex nested paths"
      (let [paths (impl.indexed/get-permission-paths db :server :view)]
        ;; Find the NIC arrow path
        (let [nic-path (first (filter #(= (:via %) :nic) paths))]
          (is nic-path)
          (is (= :network_interface (:target-type nic-path)))
          ;; Nested permission edges stay symbolic and resolve against concrete resources at runtime.
          (is (= :view (:target-permission nic-path))))))

    (testing "Empty paths for non-existent permission"
      (is (empty? (impl.indexed/get-permission-paths db :server :nonexistent))))))

(deftest traverse-paths-tests
  (let [db (d/db *conn*)]
    (testing "Direct path traversal"
      (let [user1-eid (d/entid db :test/user1)
            resources (impl.indexed/traverse-permission-path db :user user1-eid :view :account nil)]
        ;; User1 should be able to view account1
        (is (seq resources))
        (is (some #(= (d/entid db :test/account1) %) resources))))

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
               resources))))

    (testing "Cursor pagination"
      (let [user1-eid (d/entid db :test/user1)
            ;; Get first result
            first-batch (impl.indexed/traverse-permission-path db :user user1-eid :view :server nil)
            first-eid (first first-batch)
            ;; Get next result after cursor
            second-batch (impl.indexed/traverse-permission-path db :user user1-eid :view :server first-eid)]
        (is (= ["account1-server1"
                "account1-server2"
                "account3-server3.1"] (map (comp :eacl/id #(d/entity db %)) first-batch)))
        (is (= ["account1-server2"
                "account3-server3.1"] (map (comp :eacl/id #(d/entity db %)) second-batch)))
        (is (not= (first first-batch) (first second-batch)))))))

(deftest lookup-resources-optimized-tests
  (let [db (d/db *conn*)]
    (testing "Basic lookup-resources"
      (let [user1-eid (d/entid db :test/user1)
            result (lookup-resources db {:subject (->user user1-eid)
                                         :permission :view
                                         :resource/type :server
                                         :first 10})]
        ; this replicates bug where find-relation-def is called with resource-type = nil.
        (is (= [(->server "account1-server1")
                (->server "account1-server2")
                (->server "account3-server3.1")]
               (paginated->spice db result)))))

    (testing "Pagination with :after"
      (let [user1-eid (d/entid db :test/user1)
            ;; Get first page
            page1 (lookup-resources db {:subject (->user user1-eid)
                                        :permission :view
                                        :resource/type :server
                                        :first 1})
            ;; Get second page using the first page end cursor.
            page2 (lookup-resources db {:subject (->user user1-eid)
                                        :permission :view
                                        :resource/type :server
                                        :first 1
                                        :after (page-end-cursor page1)})
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
                                         :first 100})]
        ;; Should see servers from both accounts
        (is (>= (count (:data result)) 3))))))

(deftest lookup-resources-with-merge-tests
  (let [db (d/db *conn*)]
    (testing "Merge handles duplicate resources from multiple paths"
      ;; Add a direct relation that overlaps with arrow permission
      @(d/transact *conn*
                   (impl/tx-relationship (d/db *conn*)
                                         (Relationship (->user :test/user1) :shared_admin (->server :test/server1))))
      (let [db' (d/db *conn*)
            user1-eid (d/entid db' :test/user1)
            result (impl.indexed/lookup-resources db' {:subject (->user user1-eid)
                                                       :permission :view
                                                       :resource/type :server
                                                       :first 10})]
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
                                                      :first 100})
            eids (map :id (:data result))]
        ;; Entity IDs should be in ascending order
        (is (= eids (sort eids)))))

    (testing "Page cursors work with merged results"
      (let [super-user-eid (d/entid db :user/super-user)
            both-pages (impl.indexed/lookup-resources db {:subject (->user super-user-eid)
                                                          :permission :view
                                                          :resource/type :server
                                                          :first 4})
            page1 (impl.indexed/lookup-resources db {:subject (->user super-user-eid)
                                                     :permission :view
                                                     :resource/type :server
                                                     :first 2})
            page2 (impl.indexed/lookup-resources db {:subject (->user super-user-eid)
                                                     :permission :view
                                                     :resource/type :server
                                                     :first 2
                                                     :after (page-end-cursor page1)})
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

(deftest recursive-arrow-permission-path-tests
  (with-mem-conn [conn schema/v7-schema]
    (let [db    (load-recursive-parent-db! conn)
          paths (impl.indexed/get-permission-paths db :account :read)]
      (testing "recursive parent->permission should retain the arrow path on an acyclic tree"
        (is (= 2 (count paths)))
        (is (some #(= :relation (:type %)) paths))
        (is (some #(and (= :arrow (:type %))
                        (= :parent (:via %))
                        (= :account (:target-type %))
                        (= :read (:target-permission %)))
                  paths))))))

(deftest recursive-arrow-permission-can-and-lookup-subjects-tests
  (with-mem-conn [conn schema/v7-schema]
    (let [db   (load-recursive-parent-db! conn)
          user (recursive-user-ref "user-1")]
      (testing "can? should climb an acyclic parent tree"
        (is (can? db user :read (recursive-account-ref "root")))
        (is (can? db user :read (recursive-account-ref "child")))
        (is (can? db user :read (recursive-account-ref "grandchild"))))

      (testing "lookup-subjects should include inherited readers on descendants"
        (is (= #{(spice-object :user "user-1")}
               (paginated->spice-set db
                                     (lookup-subjects db {:resource     (recursive-account-ref "child")
                                                          :permission   :read
                                                          :subject/type :user
                                                          :first        100}))))
        (is (= #{(spice-object :user "user-1")}
               (paginated->spice-set db
                                     (lookup-subjects db {:resource     (recursive-account-ref "grandchild")
                                                          :permission   :read
                                                          :subject/type :user
                                                          :first        100})))))

      (testing "recursive lookup-resources should page in deterministic traversal order"
        (is (impl.indexed/traversal-permission? db :account :read))
        (let [page1 (lookup-resources db {:subject       user
                                          :permission    :read
                                          :resource/type :account
                                          :first         2})
              page2 (lookup-resources db {:subject       user
                                          :permission    :read
                                          :resource/type :account
                                          :first         2
                                          :after         (page-end-cursor page1)})
              previous (lookup-resources db {:subject       user
                                             :permission    :read
                                             :resource/type :account
                                             :last          2
                                             :before        (page-start-cursor page2)})]
          (is (= [(spice-object :account "root")
                  (spice-object :account "child")]
                 (paginated->spice db page1)))
          (is (= [(spice-object :account "grandchild")]
                 (paginated->spice db page2)))
          (is (= [(spice-object :account "root")
                  (spice-object :account "child")]
                 (paginated->spice db previous))))))))

(deftest recursive-arrow-permission-lookup-resources-visited-state-test
  (with-mem-conn [conn schema/v7-schema]
    (let [db                  (load-recursive-parent-db! conn)
          user                (recursive-user-ref "user-1")
          reader-relation-eid (:db/id (impl.indexed/find-relation-def db :account :reader))
          parent-relation-eid (:db/id (impl.indexed/find-relation-def db :account :parent))
          reader-path         {:type :relation
                               :name :reader
                               :subject-type :user
                               :relation-eid reader-relation-eid}
          recursive-paths     [reader-path
                               {:type :arrow
                                :via :parent
                                :target-type :account
                                :via-relation-eid parent-relation-eid
                                :target-permission :read
                                :sub-paths [reader-path]}]]
      (testing "lookup-resources should not stop recursion only because the permission name repeats on a different resource"
        (with-redefs [impl.indexed/get-permission-paths
                      (fn [_db resource-type permission-name]
                        (if (and (= :account resource-type)
                                 (= :read permission-name))
                          recursive-paths
                          []))]
          (is (= #{(spice-object :account "root")
                   (spice-object :account "child")
                   (spice-object :account "grandchild")}
                 (paginated->spice-set db
                                       (lookup-resources db {:subject       user
                                                             :permission    :read
                                                             :resource/type :account
                                                             :first         100}))))
          (is (= 3 (:count (count-resources db {:subject       user
                                                :permission    :read
                                                :resource/type :account})))))))))

(deftest recursive-dependency-closure-traversal-tests
  (with-mem-conn [conn schema/v7-schema]
    (let [db   (load-recursive-document-db! conn)
          user (spice-object :user [:eacl/id "user-1"])]
      (testing "acyclic roots that depend on recursive permissions should use traversal"
        (is (impl.indexed/traversal-permission? db :folder :read))
        (is (impl.indexed/traversal-permission? db :document :view))
        (is (can? db user :view (spice-object :document [:eacl/id "doc-1"])))
        (is (= [(spice-object :document "doc-1")]
               (paginated->spice db
                                 (lookup-resources db {:subject       user
                                                       :permission    :view
                                                       :resource/type :document
                                                       :first         10}))))
        (is (= 1 (:count (count-resources db {:subject       user
                                              :permission    :view
                                              :resource/type :document}))))))))

(deftest recursive-traversal-pagination-contract-tests
  (with-mem-conn [conn schema/v7-schema]
    (let [db   (load-recursive-out-of-eid-order-db! conn)
          user (recursive-user-ref "user-1")]
      (testing "recursive lookup-resources uses traversal order, not global eid order"
        (let [page (lookup-resources db {:subject       user
                                         :permission    :read
                                         :resource/type :account
                                         :first         10})
              eids (map :id (:data page))]
          (is (= [(spice-object :account "root")
                  (spice-object :account "child")
                  (spice-object :account "grandchild")]
                 (paginated->spice db page)))
          (is (not= (sort eids) eids))
          (is (= :recursive-traversal (get-in page [:page-info :start-cursor :kind])))
          (is (= 0 (get-in page [:page-info :start-cursor :ordinal])))))

      (testing "recursive lookup does not call public can? while paging"
        (with-redefs [impl.indexed/can? (fn [& _]
                                          (throw (ex-info "can? should not be called by pagination" {})))]
          (is (= 2 (count (:data (lookup-resources db {:subject       user
                                                       :permission    :read
                                                       :resource/type :account
                                                       :first         2})))))))

      (testing "bare recursive :last is rejected because it requires full traversal"
        (is (= :eacl.pagination/unsupported-recursive-last
               (:eacl/error
                (thrown-ex-data
                 #(lookup-resources db {:subject       user
                                        :permission    :read
                                        :resource/type :account
                                        :last          2}))))))

      (testing "wrong cursor kind is rejected by recursive lookup"
        (is (= :eacl.pagination/wrong-cursor-kind
               (:eacl/error
                (thrown-ex-data
                 #(lookup-resources db {:subject       user
                                        :permission    :read
                                        :resource/type :account
                                        :first         2
                                        :after         {:kind :lookup-eid
                                                        :result-eid (d/entid db [:eacl/id "root"])}}))))))

      (testing "recursive traversal guardrails throw typed errors"
        (binding [impl.indexed/*recursive-traversal-limits* {:max-derived-grants 1
                                                            :max-advanced-datoms 100
                                                            :max-queued-work 100}]
          (is (= :eacl.recursive-traversal/limit-exceeded
                 (:eacl/error
                  (thrown-ex-data
                   #(lookup-resources db {:subject       user
                                          :permission    :read
                                          :resource/type :account
                                          :first         10})))))))

      (testing "recursive lookup-subjects rejects unsupported subject relation filters"
        (is (= :eacl.pagination/unsupported-filter
               (:eacl/error
                (thrown-ex-data
                 #(lookup-subjects db {:resource         (recursive-account-ref "child")
                                       :permission       :read
                                       :subject/type     :user
                                       :subject/relation :member
                                       :first            10})))))))))

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
                 (impl/tx-relationship (d/db *conn*)
                                       (Relationship (->server :test/server1) :server-cycle (->account :test/account1))))
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

(deftest permission-paths-cache-is-scoped-per-database-test
  (with-mem-conn [conn1 schema/v7-schema]
    (with-mem-conn [conn2 schema/v7-schema]
      @(d/transact conn1 fixtures/relations+permissions)
      @(d/transact conn1 fixtures/entity-fixtures)
      @(d/transact conn2 fixtures/relations+permissions)
      @(d/transact conn2 fixtures/entity-fixtures)

      (impl.indexed/evict-permission-paths-cache!)

      (impl.indexed/get-permission-paths (d/db conn1) :server :view)
      (impl.indexed/get-permission-paths (d/db conn2) :server :view)

      (is (= 2 (count @impl.indexed/permission-paths-cache))
          "Permission path caching must not leak DB-specific relation eids across databases"))))

(deftest permission-paths-cache-is-scoped-per-schema-test
  (with-mem-conn [conn schema/v7-schema]
    @(d/transact conn fixtures/relations+permissions)
    @(d/transact conn fixtures/entity-fixtures)

    (impl.indexed/evict-permission-paths-cache!)

    (let [db-before (d/db conn)
          before-paths (impl.indexed/get-permission-paths db-before :account :view)]
      @(d/transact conn [(Relation :account :auditor :user)
                         (Permission :account :view {:relation :auditor})])
      (let [db-after (d/db conn)
            after-paths (impl.indexed/get-permission-paths db-after :account :view)
            historical-paths (impl.indexed/get-permission-paths db-before :account :view)]
        (is (< (count before-paths) (count after-paths)))
        (is (= before-paths historical-paths))
        (is (= 2 (count @impl.indexed/permission-paths-cache))
            "Permission path caching must not reuse live schema paths for an older as-of db")))))

(ns eacl.datomic.schema-test
  (:require [clojure.java.io :as io]
            [clojure.test :as t :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.core :as eacl]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.fixtures :as fixtures]
            [eacl.datomic.impl :as impl]
            [eacl.spicedb.parser]))

(def example-schema-string
  "definition user {}

   definition platform {
     relation super_admin: user
   }

   definition account {
     relation platform: platform
     relation owner: user

     permission admin = owner + platform->super_admin
     permission view = owner + admin
     permission update = admin
   }")

(deftest eacl-schema-stable-ident-tests
  (with-mem-conn [conn schema/v7-schema]
    (testing "we can transact Realtions & Permissions twice without datom conflicts after introduction of :eacl/id for Relation & Permission."
      (is @(d/transact conn fixtures/relations+permissions))
      (is @(d/transact conn fixtures/relations+permissions))
      (is (schema/read-schema (d/db conn))))))

(deftest schema-does-not-include-persisted-grants-test
  (testing "recursive traversal does not require persisted effective grant attrs"
    (let [idents (set (map :db/ident schema/v7-schema))]
      (is (not (contains? idents :eacl.v7.grant/subject-type+permission+resource-type+resource)))
      (is (not (contains? idents :eacl.v7.grant/resource-type+permission+subject-type+subject)))
      (is (not (contains? idents :eacl.grant/indexed-node))))))

(deftest eacl-schema-comparison-tests
  (testing "we can calculate additions & retractions"
    ; note we do not care about shape of set elements here.
    (is (= {:additions   #{:added}
            :unchanged   #{:retained}
            :retractions #{:deleted}}
          (schema/calc-set-deltas
           #{:deleted :retained}
           #{:retained :added})))

    (is (= {:relations   {:additions   #{:added}
                          :unchanged   #{:retained}
                          :retractions #{:deleted}}
            :permissions {:additions   #{:added}
                          :unchanged   #{:retained}
                          :retractions #{:deleted :also-deleted}}}
          (schema/compare-schema
           {:relations   [:deleted :retained]
            :permissions [:deleted :retained :also-deleted]}
           {:relations   [:retained :added]
            :permissions [:retained :added]})))))

(deftest write-schema-test
  (with-mem-conn [conn schema/v7-schema]
    (testing "Initial schema write"
      (let [deltas (schema/write-schema! conn example-schema-string)
            db     (d/db conn)
            schema (schema/read-schema db)]
        (is (= 3 (count (:relations schema))))
        (is (= 5 (count (:permissions schema))))
        (is (= 3 (count (:additions (:relations deltas)))))
        (is (= 5 (count (:additions (:permissions deltas)))))

        ;; Verify schema string is stored
        (is (= example-schema-string (:eacl/schema-string (d/entity db [:eacl/id "schema-string"]))))))

    (testing "Update schema (no changes)"
      (let [deltas (schema/write-schema! conn example-schema-string)]
        (is (empty? (:additions (:relations deltas))))
        (is (empty? (:retractions (:relations deltas))))
        (is (empty? (:additions (:permissions deltas))))
        (is (empty? (:retractions (:permissions deltas))))))

    (testing "Update schema (add relation)"
      (let [new-schema (str example-schema-string "\ndefinition new_res {\nrelation new_rel: user\n}")
            deltas     (schema/write-schema! conn new-schema)
            db         (d/db conn)
            schema     (schema/read-schema db)]
        (is (= 4 (count (:relations schema))))
        (is (= 1 (count (:additions (:relations deltas)))))))

    (testing "Update schema (remove relation - safe)"
      (let [deltas (schema/write-schema! conn example-schema-string) ; revert to original
            db     (d/db conn)
            schema (schema/read-schema db)]
        (is (= 3 (count (:relations schema))))
        (is (= 1 (count (:retractions (:relations deltas)))))))

    (testing "Update schema (remove relation - unsafe)"
      ;; Create a relationship using a relation
      (let [user-id "user1"
            acc-id  "acc1"]
        @(d/transact conn [{:db/id user-id :eacl/id "user1"}
                           {:db/id acc-id :eacl/id "acc1"}])
        @(d/transact conn (impl/tx-relationship (d/db conn)
                           (impl/Relationship {:type :user :id user-id} :owner {:type :account :id acc-id})))

        ;; Verify relationship exists
        (let [db  (d/db conn)
              rel (impl/find-one-relationship-id db
                    {:subject {:type :user :id user-id}
                     :relation :owner
                     :resource {:type :account :id acc-id}})]
          (is rel "Relationship should exist")))

      ;; Try to remove 'relation owner: user' from account
      (let [unsafe-schema "definition user {}
                           definition platform {
                             relation super_admin: user
                           }
                           definition account {
                             relation platform: platform
                           }"] ; removed owner
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot delete relation :owner because it is used by 1 relationships"
              (schema/write-schema! conn unsafe-schema)))))))

(deftest schema-validation-tests
  "Tests for ADR 012 requirement: 'Invalid schema should be rejected and no changes made.'"

  (testing "permission referencing non-existent relation is rejected"
    (with-mem-conn [conn schema/v7-schema]
      (let [bad-schema "definition user {}
                        definition account {
                          permission admin = nonexistent_relation
                        }"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid schema"
              (schema/write-schema! conn bad-schema))))))

  (testing "arrow permission with invalid target is rejected"
    (with-mem-conn [conn schema/v7-schema]
      (let [bad-schema "definition user {}
                        definition account {
                          relation owner: user
                        }
                        definition server {
                          relation account: account
                          permission view = account->nonexistent
                        }"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid schema"
              (schema/write-schema! conn bad-schema))))))

  (testing "self-permission referencing non-existent permission is rejected"
    (with-mem-conn [conn schema/v7-schema]
      (let [bad-schema "definition user {}
                        definition server {
                          permission view = fake_permission
                        }"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid schema"
              (schema/write-schema! conn bad-schema))))))

  (testing "arrow permission with missing source relation is rejected"
    (with-mem-conn [conn schema/v7-schema]
      (let [bad-schema "definition user {}
                        definition account {
                          relation owner: user
                        }
                        definition server {
                          permission view = missing_relation->admin
                        }"]
        ;; Error comes from parser's resolve-component (validates during parse)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown relation"
              (schema/write-schema! conn bad-schema))))))

  (testing "valid schema is accepted"
    (with-mem-conn [conn schema/v7-schema]
      (let [good-schema "definition user {}
                         definition platform {
                           relation super_admin: user
                         }
                         definition account {
                           relation platform: platform
                           relation owner: user
                           permission admin = owner + platform->super_admin
                           permission view = admin
                         }"]
        (is (schema/write-schema! conn good-schema))
        (let [db     (d/db conn)
              schema (schema/read-schema db)]
          (is (= 3 (count (:relations schema))))
          (is (= 3 (count (:permissions schema)))))))))

(deftest invalid-schema-does-not-install-cache-stamp-attributes-test
  ;; ADR 012 says an invalid write makes no changes. The v8.0 compatibility
  ;; installer used to transact these attributes before parsing or validating
  ;; the proposed schema.
  (let [without-stamps
        (remove (fn [{:keys [db/ident]}]
                  (contains? #{:eacl/schema-version
                               :eacl/relation-version
                               :eacl.fn/assert-relation-unused}
                             ident))
                schema/v7-schema)]
    (with-mem-conn [conn without-stamps]
      (is (nil? (d/entid (d/db conn) :eacl/schema-version)))
      (is (nil? (d/entid (d/db conn) :eacl/relation-version)))
      (is (nil? (d/entid (d/db conn)
                         :eacl.fn/assert-relation-unused)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (schema/write-schema!
                    conn
                    "definition user {}
                     definition account {
                       permission admin = missing
                     }")))
      (is (nil? (d/entid (d/db conn) :eacl/schema-version)))
      (is (nil? (d/entid (d/db conn) :eacl/relation-version)))
      (is (nil? (d/entid (d/db conn)
                         :eacl.fn/assert-relation-unused))))))

(deftest concurrent-schema-replacements-do-not-merge-test
  ;; Two writers diffing from the same generation previously submitted plain
  ;; adds/retracts. Both transactions could commit, producing the UNION of two
  ;; replacement schemas while :eacl/schema-string described only the winner.
  (with-mem-conn [conn schema/v7-schema]
    (schema/write-schema! conn "definition user {}")
    (let [schema-left
          "definition user {}
           definition account {
             relation left: user
           }"
          schema-right
          "definition user {}
           definition account {
             relation right: user
           }"
          ready (java.util.concurrent.CountDownLatch. 2)
          transact d/transact
          schema-transaction?
          (fn [tx-data]
            (some #(and (vector? %)
                        (= :db.fn/cas (first %)))
                  tx-data))]
      (with-redefs [d/transact
                    (fn [connection tx-data]
                      (when (schema-transaction? tx-data)
                        (.countDown ready)
                        (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
                      (transact connection tx-data))]
        (let [write (fn [text]
                      (future
                        (try
                          (schema/write-schema! conn text)
                          :ok
                          (catch clojure.lang.ExceptionInfo e
                            (:type (ex-data e)))
                          (catch Throwable t
                            (or (some-> t .getCause ex-data :db/error)
                                (class t))))))
              results (mapv deref [(write schema-left)
                                   (write schema-right)])
              relation-names
              (into #{}
                    (map :eacl.relation/relation-name)
                    (:relations (schema/read-schema (d/db conn))))]
          (is (= #{:ok :eacl.schema/concurrent-write}
                 (set results)))
          (is (contains? #{#{:left} #{:right}} relation-names)
              "the committed schema is one complete replacement, never a union"))))))

(deftest relation-removal-is-checked-again-at-commit-test
  ;; Force a relationship into the window between the preflight count and
  ;; schema transaction. The transactor-side guard must reject the replacement
  ;; rather than orphaning a tuple whose relation definition was retracted.
  (with-mem-conn [conn schema/v7-schema]
    (let [with-owner
          "definition user {}
           definition account {
             relation owner: user
           }"
          without-owner
          "definition user {}
           definition account {}"]
      (schema/write-schema! conn with-owner)
      @(d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
      (let [stale-db (d/db conn)
            relationship
            (impl/Relationship (eacl/spice-object :user "u")
                               :owner
                               (eacl/spice-object :account "a"))
            relationship-tx (impl/tx-relationship stale-db relationship)
            transact d/transact
            inject? (atom true)
            schema-transaction?
            (fn [tx-data]
              (some #(and (vector? %)
                          (= :eacl.fn/assert-relation-unused (first %)))
                    tx-data))]
        (with-redefs [d/transact
                      (fn [connection tx-data]
                        (when (and @inject?
                                   (schema-transaction? tx-data))
                          (reset! inject? false)
                          @(transact connection relationship-tx))
                        (transact connection tx-data))]
          (let [data (try
                       (schema/write-schema! conn without-owner)
                       nil
                       (catch clojure.lang.ExceptionInfo e
                         (ex-data e)))]
            (is (= :eacl.schema/relation-in-use (:type data)))))
        (is (= #{:owner}
               (into #{}
                     (map :eacl.relation/relation-name)
                     (schema/read-relations (d/db conn))))
            "the failed replacement leaves the relation definition intact")))))

(deftest relationships-using-relation-count-uses-canonical-identity-test
  (with-mem-conn [conn schema/v7-schema]
    (schema/write-schema!
     conn
     "definition user {}
      definition account {
        relation owner: user
      }")
    @(d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
    @(d/transact
      conn
      (impl/tx-relationship
       (d/db conn)
       (impl/Relationship (eacl/spice-object :user "u")
                          :owner
                          (eacl/spice-object :account "a"))))
    (let [db       (d/db conn)
          relation (first (schema/read-relations db))]
      (is (= "eacl.relation::account::owner::user"
             (:eacl/id relation))
          "the canonical identity includes keyword colons")
      (is (= 1 (schema/count-relationships-using-relation db relation))))))

(deftest stale-relationship-tx-cannot-resurrect-a-removed-relation-test
  (with-mem-conn [conn schema/v7-schema]
    (let [with-owner
          "definition user {}
           definition account {
             relation owner: user
           }"
          without-owner
          "definition user {}
           definition account {}"]
      (schema/write-schema! conn with-owner)
      @(d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
      (let [stale-tx
            (impl/tx-relationship
             (d/db conn)
             (impl/Relationship (eacl/spice-object :user "u")
                                :owner
                                (eacl/spice-object :account "a")))]
        (schema/write-schema! conn without-owner)
        (let [data (try
                     @(d/transact conn stale-tx)
                     nil
                     (catch Throwable throwable
                       (some-> throwable .getCause ex-data)))]
          (is (= :db.error/cas-failed (:db/error data))))
        (is (empty? (schema/read-relations (d/db conn))))))))

(deftest relation-removal-rejects-reverse-only-orphans-test
  (with-mem-conn [conn schema/v7-schema]
    (let [with-owner
          "definition user {}
           definition account {
             relation owner: user
           }"
          without-owner
          "definition user {}
           definition account {}"]
      (schema/write-schema! conn with-owner)
      @(d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
      (let [db            (d/db conn)
            relationship  (impl/Relationship (eacl/spice-object :user "u")
                                             :owner
                                             (eacl/spice-object :account "a"))
            tx-data       (impl/tx-relationship db relationship)
            forward-op    (first
                           (filter
                            #(and (vector? %)
                                  (= :db/add (first %))
                                  (= :eacl.v7.relationship/subject-type+relation+resource-type+resource
                                     (nth % 2 nil)))
                            tx-data))]
        @(d/transact conn tx-data)
        @(d/transact conn [(assoc forward-op 0 :db/retract)])
        (let [data (try
                     (schema/write-schema! conn without-owner)
                     nil
                     (catch clojure.lang.ExceptionInfo e
                       (ex-data e)))]
          (is (= :eacl.schema/relation-in-use (:type data))))
        (is (= #{:owner}
               (into #{}
                     (map :eacl.relation/relation-name)
                     (schema/read-relations (d/db conn))))
            "a reverse-only orphan still keeps its relation definition alive")))))

(deftest write-schema-parse-failure-test
  (testing "a malformed schema string throws a typed error and leaves the stored schema untouched"
    (with-mem-conn [conn schema/v7-schema]
      (schema/write-schema! conn example-schema-string)
      (let [before (schema/read-schema (d/db conn))]
        (try
          ;; missing closing brace — pre-fix this silently retracted the ENTIRE schema
          (schema/write-schema! conn "definition user {}
             definition account {
               relation owner: user
               permission admin = owner")
          (is false "should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :eacl.schema/parse-error (:type (ex-data e))))))
        (is (= before (schema/read-schema (d/db conn)))
            "schema must be unchanged after a failed write"))))

  (testing "a schema containing comments (e.g. pasted from the SpiceDB playground) writes cleanly"
    (with-mem-conn [conn schema/v7-schema]
      (is (schema/write-schema! conn "// users of the system
         definition user {}
         /* accounts own things */
         definition account {
           relation owner: user // the owner
           permission admin = owner
         }"))
      (is (= 1 (count (:relations (schema/read-schema (d/db conn)))))))))

(deftest write-schema-empty-guard-test
  (testing "zero-definition output cannot wipe a non-empty schema (parser-gap belt-and-braces)"
    (with-mem-conn [conn schema/v7-schema]
      (schema/write-schema! conn example-schema-string)
      (with-redefs [eacl.spicedb.parser/->eacl-schema (fn [_] {:definitions [] :relations [] :permissions []})]
        (try
          (schema/write-schema! conn "anything")
          (is false "should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :eacl.schema/empty-schema-guard (:type (ex-data e))))))
        (testing "explicit opt-in allows the wipe when nothing would be orphaned"
          (is (schema/write-schema! conn "anything" {:allow-empty-schema? true}))
          (is (= {:relations [] :permissions []} (schema/read-schema (d/db conn)))))))))

(deftest arrow-validation-order-independence-test
  (let [schema-with-owner-types (fn [types]
                                  (str "definition user {
                                          relation boss: user
                                          permission mgmt = boss
                                        }
                                        definition group {}
                                        definition account {
                                          relation owner: " types "
                                          permission admin = owner->mgmt
                                        }"))]
    (testing "arrow targets are validated against ALL subject types, regardless of declaration order"
      ;; mgmt exists on user but not group: both orders must be rejected identically.
      (doseq [types ["user | group" "group | user"]]
        (with-mem-conn [conn schema/v7-schema]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid schema"
                (schema/write-schema! conn (schema-with-owner-types types)))
              (str "owner: " types " should be rejected — mgmt missing on group")))))

    (testing "accepted when the target exists on every subject type"
      (with-mem-conn [conn schema/v7-schema]
        (is (schema/write-schema! conn
              "definition user {
                 relation boss: user
                 permission mgmt = boss
               }
               definition group {
                 relation lead: user
                 permission mgmt = lead
               }
               definition account {
                 relation owner: user | group
                 permission admin = owner->mgmt
               }"))))))

(deftest fixtures-schema-round-trip-test
  "Tests that fixtures.schema can be written and read back correctly.
   ADR 012 requirement: 'Rewrite the fixtures... to a new test/eacl/fixtures.schema file'"
  (with-mem-conn [conn schema/v7-schema]
    (let [schema-string (slurp (io/resource "eacl/fixtures.schema"))
          _             (schema/write-schema! conn schema-string)
          db            (d/db conn)
          schema        (schema/read-schema db)
          relations     (:relations schema)
          permissions   (:permissions schema)]

      (testing "multi-type relations are expanded correctly"
        ;; account/owner should have both user and group subject types
        (let [account-owner-rels (filter #(and (= :account (:eacl.relation/resource-type %))
                                            (= :owner (:eacl.relation/relation-name %)))
                                   relations)]
          (is (= #{:user :group} (set (map :eacl.relation/subject-type account-owner-rels))))))

      (testing "platform/super_admin relation exists"
        (is (some #(= (impl/Relation :platform :super_admin :user) %) relations)))

      (testing "server/account relation exists"
        (is (some #(= (impl/Relation :server :account :account) %) relations)))

      (testing "vpc/shared_admin relation exists"
        (is (some #(= (impl/Relation :vpc :shared_admin :user) %) relations)))

      (testing "account/admin permission has correct definitions"
        ;; permission admin = owner + platform->super_admin
        (let [account-admin-perms (filter #(and (= :account (:eacl.permission/resource-type %))
                                             (= :admin (:eacl.permission/permission-name %)))
                                    permissions)]
          (is (= #{(impl/Permission :account :admin {:relation :owner})
                   (impl/Permission :account :admin {:arrow :platform :relation :super_admin})}
                (set account-admin-perms)))))

      (testing "server/view permission has correct definitions"
        ;; permission view = admin + nic->view + shared_member + backup_creator
        (let [server-view-perms (filter #(and (= :server (:eacl.permission/resource-type %))
                                           (= :view (:eacl.permission/permission-name %)))
                                  permissions)]
          (is (= #{(impl/Permission :server :view {:permission :admin})
                   (impl/Permission :server :view {:arrow :nic :permission :view})
                   (impl/Permission :server :view {:relation :shared_member})
                   (impl/Permission :server :view {:relation :backup_creator})}
                (set server-view-perms)))))

      (testing "vpc/admin permission has correct definitions"
        ;; permission admin = account->admin + shared_admin
        (let [vpc-admin-perms (filter #(and (= :vpc (:eacl.permission/resource-type %))
                                         (= :admin (:eacl.permission/permission-name %)))
                                permissions)]
          (is (= #{(impl/Permission :vpc :admin {:arrow :account :permission :admin})
                   (impl/Permission :vpc :admin {:relation :shared_admin})}
                (set vpc-admin-perms)))))

      (testing "schema string is stored"
        (is (= schema-string (:eacl/schema-string (d/entity db [:eacl/id "schema-string"]))))))))

(deftest validation-error-map-tests
  (testing "reference-validation errors carry complete messages and keyword-only keys"
    ;; A misplaced paren truncated :message and injected the message tail as a
    ;; stray string key in 3 of the 5 error constructors.
    (doseq [[permissions expected-type expected-message]
            [[[#:eacl.permission{:resource-type :doc :permission-name :view
                                 :source-relation-name :self :target-type :relation :target-name :owner}]
              :invalid-self-relation
              "Permission doc/view references non-existent relation: owner"]
             [[#:eacl.permission{:resource-type :doc :permission-name :view
                                 :source-relation-name :self :target-type :permission :target-name :admin}]
              :invalid-self-permission
              "Permission doc/view references non-existent permission: admin"]
             [[#:eacl.permission{:resource-type :doc :permission-name :view
                                 :source-relation-name :owner :target-type :relation :target-name :member}]
              :missing-source-relation
              "Permission doc/view references non-existent relation: owner"]]]
      (let [error (try
                    (schema/validate-schema-references {:relations [] :permissions permissions})
                    nil
                    (catch clojure.lang.ExceptionInfo e
                      (first (:errors (ex-data e)))))]
        (is (= expected-type (:type error)))
        (is (= expected-message (:message error)))
        (is (every? keyword? (keys error)))))))

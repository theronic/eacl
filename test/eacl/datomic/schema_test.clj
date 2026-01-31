(ns eacl.datomic.schema-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.fixtures :as fixtures]
            [eacl.datomic.impl :as impl]))

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
  (with-mem-conn [conn schema/v6-schema]
    (testing "we can transact Realtions & Permissions twice without datom conflicts after introduction of :eacl/id for Relation & Permission."
      (is @(d/transact conn fixtures/relations+permissions))
      (is @(d/transact conn fixtures/relations+permissions))
      (is (schema/read-schema (d/db conn))))))

(deftest eacl-schema-comparison-tests
  (testing "we can calculate additions & retractions"
    ; note we do not care about shape of set elements here.
    (is (= {:additions #{:added}
            :unchanged #{:retained}
            :retractions #{:deleted}}
           (schema/calc-set-deltas
            #{:deleted :retained}
            #{:retained :added})))

    (is (= {:relations {:additions #{:added}
                        :unchanged #{:retained}
                        :retractions #{:deleted}}
            :permissions {:additions #{:added}
                          :unchanged #{:retained}
                          :retractions #{:deleted :also-deleted}}}
           (schema/compare-schema
            {:relations [:deleted :retained]
             :permissions [:deleted :retained :also-deleted]}
            {:relations [:retained :added]
             :permissions [:retained :added]})))))

(deftest write-schema-test
  (with-mem-conn [conn schema/v6-schema]
    (testing "Initial schema write"
      (let [deltas (schema/write-schema! conn example-schema-string)
            db (d/db conn)
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
      (let [new-schema (str example-schema-string "\ndefinition new_res { relation new_rel: user }")
            deltas (schema/write-schema! conn new-schema)
            db (d/db conn)
            schema (schema/read-schema db)]
        (is (= 4 (count (:relations schema))))
        (is (= 1 (count (:additions (:relations deltas)))))))

    (testing "Update schema (remove relation - safe)"
      (let [deltas (schema/write-schema! conn example-schema-string) ; revert to original
            db (d/db conn)
            schema (schema/read-schema db)]
        (is (= 3 (count (:relations schema))))
        (is (= 1 (count (:retractions (:relations deltas)))))))

    (testing "Update schema (remove relation - unsafe)"
      ;; Create a relationship using a relation
      (let [user-id "user1"
            acc-id "acc1"]
        @(d/transact conn [{:db/id user-id :eacl/id "user1"}
                           {:db/id acc-id :eacl/id "acc1"}
                           (impl/Relationship {:type :user :id user-id} :owner {:type :account :id acc-id})])

        ;; Verify relationship exists
        (let [db (d/db conn)
              rel-eid (d/q '[:find ?r .
                             :where [?r :eacl.relationship/relation-name :owner]] db)]
          (is rel-eid "Relationship should exist")))

      ;; Try to remove 'relation owner: user' from account
      (let [unsafe-schema "definition user {}
                           definition platform { relation super_admin: user }
                           definition account { relation platform: platform }"] ; removed owner
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot delete relation :owner because it is used by 1 relationships"
                              (schema/write-schema! conn unsafe-schema)))))))

(deftest schema-validation-tests
  "Tests for ADR 012 requirement: 'Invalid schema should be rejected and no changes made.'"

  (testing "permission referencing non-existent relation is rejected"
    (with-mem-conn [conn schema/v6-schema]
      (let [bad-schema "definition user {}
                        definition account {
                          permission admin = nonexistent_relation
                        }"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid schema"
                              (schema/write-schema! conn bad-schema))))))

  (testing "arrow permission with invalid target is rejected"
    (with-mem-conn [conn schema/v6-schema]
      (let [bad-schema "definition user {}
                        definition account { relation owner: user }
                        definition server {
                          relation account: account
                          permission view = account->nonexistent
                        }"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid schema"
                              (schema/write-schema! conn bad-schema))))))

  (testing "self-permission referencing non-existent permission is rejected"
    (with-mem-conn [conn schema/v6-schema]
      (let [bad-schema "definition user {}
                        definition server {
                          permission view = fake_permission
                        }"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid schema"
                              (schema/write-schema! conn bad-schema))))))

  (testing "arrow permission with missing source relation is rejected"
    (with-mem-conn [conn schema/v6-schema]
      (let [bad-schema "definition user {}
                        definition account { relation owner: user }
                        definition server {
                          permission view = missing_relation->admin
                        }"]
        ;; Error comes from parser's resolve-component (validates during parse)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown relation"
                              (schema/write-schema! conn bad-schema))))))

  (testing "valid schema is accepted"
    (with-mem-conn [conn schema/v6-schema]
      (let [good-schema "definition user {}
                         definition platform { relation super_admin: user }
                         definition account {
                           relation platform: platform
                           relation owner: user
                           permission admin = owner + platform->super_admin
                           permission view = admin
                         }"]
        (is (schema/write-schema! conn good-schema))
        (let [db (d/db conn)
              schema (schema/read-schema db)]
          (is (= 3 (count (:relations schema))))
          (is (= 3 (count (:permissions schema)))))))))

(deftest fixtures-schema-round-trip-test
  "Tests that fixtures.schema can be written and read back correctly.
   ADR 012 requirement: 'Rewrite the fixtures... to a new test/eacl/fixtures.schema file'"
  (with-mem-conn [conn schema/v6-schema]
    (let [schema-string (slurp "test/eacl/fixtures.schema")
          _ (schema/write-schema! conn schema-string)
          db (d/db conn)
          schema (schema/read-schema db)
          relations (:relations schema)
          permissions (:permissions schema)]

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
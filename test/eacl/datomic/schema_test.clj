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
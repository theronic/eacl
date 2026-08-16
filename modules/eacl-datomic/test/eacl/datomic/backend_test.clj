(ns eacl.datomic.backend-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]
            [eacl.datomic.backend :as datomic-backend]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]))

(def ^:private test-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(deftest immutable-snapshot-adapter-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {})]
      (eacl/write-schema! client test-schema)
      @(d/transact conn [{:eacl/id "alice"}
                         {:eacl/id "account-1"}])
      (eacl/create-relationship!
       client
       (eacl/->Relationship
        (eacl/spice-object :user "alice")
        :owner
        (eacl/spice-object :account "account-1")))
      (let [db (d/db conn)
            adapter
            (datomic-backend/snapshot-adapter
             db
             {:entid->object-id
              (fn [snapshot eid]
                (:eacl/id (d/entity snapshot eid)))
              :conn conn
              })
            alice (backend/invoke
                   adapter :object-id->internal "alice")
            account (backend/invoke
                     adapter :object-id->internal "account-1")
            relation (first
                      (backend/invoke
                       adapter :relation-defs :account :owner))
            relation-id (:relation-id relation)]
        (is (= :datomic (backend/backend-id adapter)))
        (is (= (d/basis-t db)
               (:basis-t
                (backend/invoke adapter :snapshot-id))))
        (is (= "alice"
               (backend/invoke adapter :internal-id->object alice)))
        (is (= :user (:subject-type relation)))
        (is (= :owner (:relation-name relation)))
        (is (= [:account :admin]
               ((juxt :resource-type :permission-name)
                (first
                 (backend/invoke
                  adapter :permission-defs :account :admin)))))
        (is (= [account]
               (vec
                (backend/invoke
                 adapter
                 :subject->resources
                 :user alice relation-id :account
                 {:direction :asc}))))
        (is (= [alice]
               (vec
                (backend/invoke
                 adapter
                 :resource->subjects
                 :account account relation-id :user
                 {:direction :asc}))))
        (is (true?
             (backend/invoke
              adapter
              :direct-match?
              :user alice relation-id :account account)))
        (let [proof (backend/invoke adapter :proof-frame [relation-id])]
          (is (= #{:schema-stamp :relation-stamps}
                 (set (keys proof))))
          (is (integer? (:schema-stamp proof)))
          (is (= relation-id (ffirst (:relation-stamps proof))))
          (is (integer? (second (first (:relation-stamps proof))))))
        (testing "the adapter advertises Datomic's existing guarantees"
          (doseq [mode [:fully-consistent
                        :minimize-latency
                        :at-least-as-fresh
                        :at-exact-snapshot]]
            (is (backend/supports? adapter :consistency mode))))))))

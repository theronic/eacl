(ns eacl.datomic.backend-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.backend.source :as source]
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

(deftest immutable-basis-adapter-test
  (with-mem-conn [conn schema/v8-schema]
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
            (datomic-backend/basis-adapter
             db
             {:entid->object-id
              (fn [snapshot eid]
                (:eacl/id (d/entity snapshot eid)))})
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
          (is (= relation-id (ffirst proof)))
          (is (integer? (second (first proof)))))
        (testing "the source advertises Datomic selection guarantees"
          (doseq [mode [:fully-consistent
                        :minimize-latency
                        :at-least-as-fresh
                        :at-exact-snapshot]]
            (is (source/supports? (:source client)
                                  :consistency mode))))))))

(deftest relation-generation-history-is-readable-through-as-of-test
  (with-mem-conn [conn schema/v8-schema]
    (let [client (core/make-client conn {})]
      (eacl/write-schema! client test-schema)
      @(d/transact conn [{:eacl/id "alice"}
                         {:eacl/id "account-1"}])
      (let [before-db (d/db conn)
            before-t (d/basis-t before-db)
            relation-id
            (:e (first (d/datoms before-db
                                 :aevt
                                 :eacl.relation/relation-name)))
            before-proof
            (backend/invoke
             (datomic-backend/basis-adapter before-db {})
             :proof-frame
             [relation-id])]
        @(d/transact conn [{:eacl/id "unrelated"}])
        (eacl/create-relationship!
         client
         (eacl/->Relationship
          (eacl/spice-object :user "alice")
          :owner
          (eacl/spice-object :account "account-1")))
        (let [current-t (d/basis-t (d/db conn))]
          (d/request-index conn)
          (is (not= ::timeout
                    (deref (d/sync-index conn current-t)
                           10000
                           ::timeout)))
          (let [as-of-db (d/as-of (d/db conn) before-t)
                as-of-proof
                (backend/invoke
                 (datomic-backend/basis-adapter as-of-db {})
                 :proof-frame
                 [relation-id])
                current-proof
                (backend/invoke
                 (datomic-backend/basis-adapter (d/db conn) {})
                 :proof-frame
                 [relation-id])]
            (is (= before-proof as-of-proof)
                "the older immutable basis retains its relation generation")
            (is (< (second (first as-of-proof))
                   (second (first current-proof))))))))))

(deftest existing-no-history-relation-generation-schema-is-upgraded-test
  (let [legacy-schema
        (mapv (fn [attribute]
                (if (= :eacl/relation-version (:db/ident attribute))
                  (assoc attribute :db/noHistory true)
                  attribute))
              schema/v8-schema)]
    (with-mem-conn [conn legacy-schema]
      (is (true? (:db/noHistory
                  (d/entity (d/db conn) :eacl/relation-version))))
      (schema/write-schema! conn test-schema)
      (is (false? (:db/noHistory
                   (d/entity (d/db conn) :eacl/relation-version)))))))

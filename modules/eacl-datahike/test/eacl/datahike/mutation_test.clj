(ns eacl.datahike.mutation-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.causal-token :as causal-token]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.mutation :as journal]
            [eacl.datahike.schema :as schema]
            [eacl.mutation :as mutation]
            [eacl.schema.model :as model]))

(def test-schema
  "definition user {}
   definition folder {
     relation owner: user
     relation viewer: user
     permission view = owner + viewer
   }")

(def security-key
  "01234567890123456789012345678901")

(defn- relation-eid
  [db relation-name]
  (ddb/entid
   db
   [:eacl/id
    (model/->relation-id :folder relation-name :user)]))

(deftest managed-writes-publish-one-committed-mutation-test
  (let [conn (schema/create-conn)
        client (datahike/make-client
                conn
                {:coherence-authority :managed
                 :security-key security-key})
        schema-result (eacl/write-schema! client test-schema)
        first-payload
        (causal-token/token-data
         (get-in client [:opts :format-options])
         (:zed/token schema-result))
        first-head (:head-id (journal/graph-state (d/db conn)))
        no-op-result (eacl/write-schema! client test-schema)
        no-op-payload
        (causal-token/token-data
         (get-in client [:opts :format-options])
         (:zed/token no-op-result))]
    (testing "schema tokens name the committed graph head and branch"
      (is (= first-head (:graph-anchor first-payload)))
      (is (= (:max-tx (d/db conn)) (:order-hint first-payload)))
      (is (= :db (:branch first-payload))))
    (testing "a schema no-op keeps the graph head"
      (is (= first-head (:head-id (journal/graph-state (d/db conn)))))
      (is (= first-head (:graph-anchor no-op-payload))))

    (d/transact conn [{:eacl/id "user-1"}
                      {:eacl/id "folder-1"}
                      {:eacl/id "folder-2"}])
    (let [write-result
          (eacl/create-relationships!
           client
           [(eacl/->Relationship
             (eacl/spice-object :user "user-1")
             :owner
             (eacl/spice-object :folder "folder-1"))
            (eacl/->Relationship
             (eacl/spice-object :user "user-1")
             :viewer
             (eacl/spice-object :folder "folder-2"))])
          db (d/db conn)
          owner-eid (relation-eid db :owner)
          viewer-eid (relation-eid db :viewer)
          owner-stamp
          (get (d/entity db owner-eid)
               mutation/relation-mutation-id-attr)
          viewer-stamp
          (get (d/entity db viewer-eid)
               mutation/relation-mutation-id-attr)
          owner-stamp-tx
          (:tx
           (first
            (ddb/eavt-datoms
             db
             owner-eid
             mutation/relation-mutation-id-attr)))
          viewer-stamp-tx
          (:tx
           (first
            (ddb/eavt-datoms
             db
             viewer-eid
             mutation/relation-mutation-id-attr)))
          payload
          (causal-token/token-data
           (get-in client [:opts :format-options])
           (:zed/token write-result))]
      (testing "one batch stamps both affected relation identities"
        (is (= owner-stamp viewer-stamp))
        (is (= owner-stamp (:graph-anchor payload)))
        (is (= owner-stamp-tx viewer-stamp-tx (:max-tx db))
            "current datom transaction is the numeric cache stamp"))
      (let [delete-result
            (eacl/delete-object!
             client
             (eacl/spice-object :user "user-1"))
            db-after (d/db conn)
            owner-after-eid (relation-eid db-after :owner)
            viewer-after-eid (relation-eid db-after :viewer)
            owner-delete-stamp
            (get (d/entity db-after owner-after-eid)
                 mutation/relation-mutation-id-attr)
            viewer-delete-stamp
            (get (d/entity db-after viewer-after-eid)
                 mutation/relation-mutation-id-attr)
            owner-delete-tx
            (:tx
             (first
              (ddb/eavt-datoms
               db-after
               owner-after-eid
               mutation/relation-mutation-id-attr)))
            viewer-delete-tx
            (:tx
             (first
              (ddb/eavt-datoms
               db-after
               viewer-after-eid
               mutation/relation-mutation-id-attr)))
            delete-payload
            (causal-token/token-data
             (get-in client [:opts :format-options])
             (:zed/token delete-result))]
        (testing "cascade deletion stamps every affected relation"
          (is (= owner-delete-stamp viewer-delete-stamp))
          (is (not= owner-stamp owner-delete-stamp))
          (is (= owner-delete-tx viewer-delete-tx (:max-tx db-after))
              "cascade deletion advances every affected numeric stamp")
          (is (= owner-delete-stamp
                 (:graph-anchor delete-payload))))))))

(deftest cross-connection-writer-and-migration-race-test
  (let [conn-1 (schema/create-conn)
        _ (d/transact
           conn-1
           [{:eacl/id "legacy-relation"
             :eacl.relation/relation-name :member}])
        config (:config (d/db conn-1))
        conn-2 (d/connect config)
        attempts
        (doall
         (for [conn [conn-1 conn-2]]
           (future (journal/ensure-migrated! conn))))
        states (mapv deref attempts)
        state (journal/graph-state (d/db conn-1))]
    (is (every? #(= (:family-id state) (:family-id %)) states))
    (is (every? #(= (:head-id state) (:head-id %)) states))
    (is (= (:head-id state)
           (get (d/entity (d/db conn-1)
                          [:eacl/id "legacy-relation"])
                mutation/relation-mutation-id-attr)))
    (let [first-id (mutation/new-id)
          second-id (mutation/new-id)]
      (journal/transact!
       conn-1
       {:mutation-id first-id
        :kind :custom
        :canonical-data {:writer 1}
        :tx-data []})
      (journal/transact!
       conn-2
       {:mutation-id second-id
        :kind :custom
        :canonical-data {:writer 2}
        :tx-data []})
      (is (journal/contains-anchor? (d/db conn-1) first-id))
      (is (journal/contains-anchor? (d/db conn-1) second-id))
      (is (= second-id
             (:head-id (journal/graph-state (d/db conn-1))))))))

(deftest idempotency-custom-dependency-and-expiry-test
  (let [conn (schema/create-conn)
        _ (journal/ensure-migrated! conn)
        _ (d/transact conn [{:eacl/id "identity-1"}])
        mutation-id (mutation/new-id)
        options
        {:mutation-id mutation-id
         :kind :caveat-input
         :canonical-data {:operation :caveat-input
                          :id "identity-1"
                          :value true}
         :dependency-ids [[:eacl/id "identity-1"]]
         :token-ttl-seconds 1
         :retention-grace-seconds 0
         :tx-data [[:db/add
                    [:eacl/id "identity-1"]
                    :eacl/schema-string
                    "true"]]}
        report (journal/transact! conn options)
        recovered (journal/transact! conn options)]
    (is (not (:idempotent-recovery? report)))
    (is (true? (:idempotent-recovery? recovered)))
    (is (= mutation-id
           (get (d/entity (d/db conn) [:eacl/id "identity-1"])
                mutation/dependency-mutation-id-attr)))
    (is (= :eacl.mutation/id-reused
           (try
             (journal/transact!
              conn
              (assoc options :canonical-data {:different true}))
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))
    (let [old-head (:head-id (journal/graph-state (d/db conn)))
          next-id (mutation/new-id)]
      (journal/transact!
       conn
       {:mutation-id next-id
        :kind :custom
        :canonical-data {:operation :next}
        :token-ttl-seconds 1
        :retention-grace-seconds 0
        :tx-data []})
      (is (pos? (journal/prune-expired!
                 conn
                 (+ 2 (mutation/now-seconds)))))
      (is (false? (journal/contains-anchor? (d/db conn) old-head)))
      (is (true? (journal/contains-anchor? (d/db conn) next-id))))))

(deftest stale-calculation-head-cannot-be-adopted-at-submission-test
  (let [conn (schema/create-conn)
        _ (journal/ensure-migrated! conn)
        calculation-db (d/db conn)
        expected-head (:head-id (journal/graph-state calculation-db))
        winner-id (mutation/new-id)
        loser-id (mutation/new-id)
        _ (journal/transact!
           conn
           {:mutation-id winner-id
            :calculation-db calculation-db
            :kind :custom
            :canonical-data {:writer :winner}
            :tx-data []})
        error
        (try
          (journal/transact!
           conn
           {:mutation-id loser-id
            :calculation-db calculation-db
            :kind :custom
            :canonical-data {:writer :stale}
            :tx-data []})
          nil
          (catch clojure.lang.ExceptionInfo error
            (ex-data error)))
        final-db (d/db conn)]
    (is (= :eacl.mutation/concurrent-write (:type error)) (pr-str error))
    (is (= expected-head (:expected-head error)))
    (is (= winner-id (:observed-head error)))
    (is (true? (:retryable? error)))
    (is (= winner-id (:head-id (journal/graph-state final-db))))
    (is (not (journal/contains-anchor? final-db loser-id)))))

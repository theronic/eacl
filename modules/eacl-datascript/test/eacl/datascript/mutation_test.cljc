(ns eacl.datascript.mutation-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.causal-token :as causal-token]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.mutation :as journal]
            [eacl.datascript.schema :as schema]
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
  (ds/entid
   db
   [:eacl/id
    (model/->relation-id :folder relation-name :user)]))

(deftest managed-writes-publish-one-committed-mutation-test
  (let [conn (schema/create-conn)
        client (datascript/make-client
                conn
                {:coherence-authority :managed
                 :security-key security-key})
        schema-result (eacl/write-schema! client test-schema)
        first-token (:zed/token schema-result)
        first-payload
        (causal-token/token-data
         (get-in client [:opts :format-options])
         first-token)
        first-head (:head-id (journal/graph-state (ds/db conn)))
        no-op-result (eacl/write-schema! client test-schema)
        no-op-payload
        (causal-token/token-data
         (get-in client [:opts :format-options])
         (:zed/token no-op-result))]
    (testing "schema tokens name the committed graph head"
      (is (= first-head (:graph-anchor first-payload)))
      (is (= (:max-tx (ds/db conn)) (:order-hint first-payload))))
    (testing "a structural and textual no-op does not advance the graph"
      (is (= first-head (:head-id (journal/graph-state (ds/db conn)))))
      (is (= first-head (:graph-anchor no-op-payload))))

    (ds/transact! conn [{:eacl/id "user-1"}
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
          db (ds/db conn)
          owner-eid (relation-eid db :owner)
          viewer-eid (relation-eid db :viewer)
          owner-stamp
          (get (ds/entity db owner-eid)
               mutation/relation-mutation-id-attr)
          viewer-stamp
          (get (ds/entity db viewer-eid)
               mutation/relation-mutation-id-attr)
          owner-stamp-tx
          (:tx
           (first
            (ds/datoms
             db :eavt owner-eid
             mutation/relation-mutation-id-attr)))
          viewer-stamp-tx
          (:tx
           (first
            (ds/datoms
             db :eavt viewer-eid
             mutation/relation-mutation-id-attr)))
          payload
          (causal-token/token-data
           (get-in client [:opts :format-options])
           (:zed/token write-result))]
      (testing "one batched write stamps every affected relation once"
        (is (= owner-stamp viewer-stamp))
        (is (= owner-stamp (:graph-anchor payload)))
        (is (= owner-stamp
               (:head-id (journal/graph-state db))))
        (is (= owner-stamp-tx viewer-stamp-tx (:max-tx db))
            "current datom transaction is the numeric cache stamp"))

      (let [delete-result
            (eacl/delete-object!
             client
             (eacl/spice-object :user "user-1"))
            db-after (ds/db conn)
            owner-after-eid (relation-eid db-after :owner)
            viewer-after-eid (relation-eid db-after :viewer)
            owner-delete-stamp
            (get (ds/entity db-after owner-after-eid)
                 mutation/relation-mutation-id-attr)
            viewer-delete-stamp
            (get (ds/entity db-after viewer-after-eid)
                 mutation/relation-mutation-id-attr)
            owner-delete-tx
            (:tx
             (first
              (ds/datoms
               db-after :eavt owner-after-eid
               mutation/relation-mutation-id-attr)))
            viewer-delete-tx
            (:tx
             (first
              (ds/datoms
               db-after :eavt viewer-after-eid
               mutation/relation-mutation-id-attr)))
            delete-payload
            (causal-token/token-data
             (get-in client [:opts :format-options])
             (:zed/token delete-result))]
        (testing "cascade deletion stamps all affected relations atomically"
          (is (= owner-delete-stamp viewer-delete-stamp))
          (is (not= owner-stamp owner-delete-stamp))
          (is (= owner-delete-tx viewer-delete-tx (:max-tx db-after))
              "cascade deletion advances every affected numeric stamp")
          (is (= owner-delete-stamp (:graph-anchor delete-payload))))))))

(deftest mutation-idempotency-dependency-and-retention-test
  (let [conn (schema/create-conn)
        _ (journal/ensure-migrated! conn)
        _ (ds/transact! conn [{:eacl/id "identity-1"}])
        mutation-id (mutation/new-id)
        options
        {:mutation-id mutation-id
         :kind :object-identity
         :canonical-data {:operation :rename
                          :identity "identity-1"
                          :value "public-2"}
         :dependency-ids [[:eacl/id "identity-1"]]
         :token-ttl-seconds 1
         :retention-grace-seconds 0
         :tx-data [[:db/add
                    [:eacl/id "identity-1"]
                    :eacl/schema-string
                    "public-2"]]}
        report (journal/transact! conn options)
        recovered (journal/transact! conn options)
        db (ds/db conn)]
    (is (not (:idempotent-recovery? report)))
    (is (true? (:idempotent-recovery? recovered)))
    (is (= mutation-id
           (get (ds/entity db [:eacl/id "identity-1"])
                mutation/dependency-mutation-id-attr)))
    (is (= :eacl.mutation/id-reused
           (try
             (journal/transact!
              conn
              (assoc options :canonical-data {:different true}))
             nil
             (catch #?(:clj clojure.lang.ExceptionInfo
                       :cljs cljs.core.ExceptionInfo) error
               (:type (ex-data error))))))
    (let [old-head (:head-id (journal/graph-state db))
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
      (is (false? (journal/contains-anchor? (ds/db conn) old-head)))
      (is (true? (journal/contains-anchor? (ds/db conn) next-id))))))

#?(:clj
   (deftest migration-race-test
     (let [conn (schema/create-conn)
           _ (ds/transact!
              conn
              [{:eacl/id "legacy-relation"
                :eacl.relation/relation-name :member}])
           attempts (doall
                     (repeatedly
                      8
                      #(future (journal/ensure-migrated! conn))))
           states (mapv deref attempts)
           final-state (journal/graph-state (ds/db conn))]
       (is (every? #(= (:family-id final-state) (:family-id %))
                   states))
       (is (every? #(= (:head-id final-state) (:head-id %))
                   states))
       (is (= (:head-id final-state)
              (get (ds/entity (ds/db conn)
                              [:eacl/id "legacy-relation"])
                   mutation/relation-mutation-id-attr))))))

(ns eacl.datomic.mutation-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.causal-token :as causal-token]
            [eacl.core :as eacl]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers
             :refer [with-mem-conn with-mem-conns]]
            [eacl.datomic.mutation :as journal]
            [eacl.datomic.schema :as schema]
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
  (d/entid
   db
   [:eacl/id
    (model/->relation-id :folder relation-name :user)]))

(deftest managed-writes-publish-committed-head-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (datomic/make-client
                  conn
                  {:coherence-authority :managed
                   :zed-token-key security-key})
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
      (testing "schema response derives its anchor and order from db-after"
        (is (= first-head (:graph-anchor first-payload)))
        (is (= (d/basis-t (d/db conn)) (:order-hint no-op-payload)))
        (is (= first-head (:graph-anchor no-op-payload))))

      @(d/transact conn [{:eacl/id "user-1"}
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
            owner-stamp
            (get (d/entity db (relation-eid db :owner))
                 mutation/relation-mutation-id-attr)
            viewer-stamp
            (get (d/entity db (relation-eid db :viewer))
                 mutation/relation-mutation-id-attr)
            payload
            (causal-token/token-data
             (get-in client [:opts :format-options])
             (:zed/token write-result))]
        (testing "one batched relationship write stamps every relation"
          (is (= owner-stamp viewer-stamp))
          (is (= owner-stamp (:graph-anchor payload)))
          (is (= (d/basis-t db) (:order-hint payload))))
        (let [delete-result
              (eacl/delete-object!
               client
               (eacl/spice-object :user "user-1"))
              db-after (d/db conn)
              owner-delete-stamp
              (get (d/entity db-after (relation-eid db-after :owner))
                   mutation/relation-mutation-id-attr)
              viewer-delete-stamp
              (get (d/entity db-after (relation-eid db-after :viewer))
                   mutation/relation-mutation-id-attr)
              delete-payload
              (causal-token/token-data
               (get-in client [:opts :format-options])
               (:zed/token delete-result))]
          (testing "cascade deletion publishes all dependency stamps"
            (is (= owner-delete-stamp viewer-delete-stamp))
            (is (not= owner-stamp owner-delete-stamp))
            (is (= owner-delete-stamp
                   (:graph-anchor delete-payload)))))))))

(deftest migration-race-and-cross-connection-writers-test
  (with-mem-conns [conn-1 conn-2 schema/v7-schema]
    @(d/transact
      conn-1
      [{:eacl/id "legacy-relation"
        :eacl.relation/relation-name :member}])
    (let [attempts [(future (journal/ensure-migrated! conn-1))
                    (future (journal/ensure-migrated! conn-2))]
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
               (:head-id (journal/graph-state (d/db conn-1)))))))))

(deftest idempotency-custom-dependency-and-expiry-test
  (with-mem-conn [conn schema/v7-schema]
    (journal/ensure-migrated! conn)
    @(d/transact conn [{:eacl/id "identity-1"}])
    (let [mutation-id (mutation/new-id)
          options
          {:mutation-id mutation-id
           :kind :object-identity
           :canonical-data {:operation :rename
                            :id "identity-1"
                            :value "public-2"}
           :dependency-ids [[:eacl/id "identity-1"]]
           :token-ttl-seconds 1
           :retention-grace-seconds 0
           :tx-data [[:db/add
                      [:eacl/id "identity-1"]
                      :eacl/schema-string
                      "public-2"]]}
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
        (is (true? (journal/contains-anchor? (d/db conn) next-id)))))))

(deftest incomplete-authority-cannot-issue-read-token-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (datomic/make-client
                  conn
                  {:zed-token-key security-key})]
      (is (= :eacl/causal-authority-incomplete
             (try
               (datomic/current-zed-token client)
               nil
               (catch clojure.lang.ExceptionInfo error
                 (:type (ex-data error)))))))))

(deftest concurrent-mutation-id-claim-test
  (with-mem-conns [conn-1 conn-2 schema/v7-schema]
    (journal/ensure-migrated! conn-1)
    (let [mutation-id (mutation/new-id)
          options {:mutation-id mutation-id
                   :kind :custom
                   :canonical-data {:operation :same}
                   :tx-data []}
          results
          (mapv deref
                [(future (journal/transact! conn-1 options))
                 (future (journal/transact! conn-2 options))])]
      (is (= 1 (count (filter :idempotent-recovery? results))))
      (is (= mutation-id
             (:head-id (journal/graph-state (d/db conn-1))))))
    (let [mutation-id (mutation/new-id)
          result
          (mapv
           deref
           [(future
              (try
                (journal/transact!
                 conn-1
                 {:mutation-id mutation-id
                  :kind :custom
                  :canonical-data {:value 1}
                  :tx-data []})
                :committed
                (catch clojure.lang.ExceptionInfo error
                  (:type (ex-data error)))))
            (future
              (try
                (journal/transact!
                 conn-2
                 {:mutation-id mutation-id
                  :kind :custom
                  :canonical-data {:value 2}
                  :tx-data []})
                :committed
                (catch clojure.lang.ExceptionInfo error
                  (:type (ex-data error)))))])]
      (is (= #{:committed :eacl.mutation/id-reused}
             (set result))))))

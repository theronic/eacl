(ns eacl.datomic.mutation-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.causal-token :as causal-token]
            [eacl.core :as eacl]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
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

(def security-key "01234567890123456789012345678901")

(defn- relation-eid [db relation-name]
  (d/entid db [:eacl/id (model/->relation-id :folder relation-name :user)]))

(defn- stamp [db relation-name]
  (first (d/datoms db :eavt (relation-eid db relation-name)
                   :eacl/relation-version)))

(deftest ordinary-writes-publish-native-generations-without-journal-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (datomic/make-client
                  conn {:coherence-authority :managed
                        :zed-token-key security-key})
          schema-result (eacl/write-schema! client test-schema)
          payload (causal-token/token-data
                   (get-in client [:opts :format-options])
                   (:zed/token schema-result))]
      (testing "schema write initializes physical relation generations"
        (is (= (d/basis-t (d/db conn)) (:revision payload)))
        (is (every? some? [(stamp (d/db conn) :owner)
                           (stamp (d/db conn) :viewer)]))
        (is (nil? (journal/graph-state (d/db conn))))
        (is (nil? (d/entid (d/db conn) :eacl.mutation/id))))
      @(d/transact conn [{:eacl/id "user-1"}
                         {:eacl/id "folder-1"}
                         {:eacl/id "folder-2"}])
      (eacl/create-relationships!
       client
       [(eacl/->Relationship (eacl/spice-object :user "user-1") :owner
                             (eacl/spice-object :folder "folder-1"))
        (eacl/->Relationship (eacl/spice-object :user "user-1") :viewer
                             (eacl/spice-object :folder "folder-2"))])
      (let [db (d/db conn)
            owner (stamp db :owner)
            viewer (stamp db :viewer)]
        (testing "one batch advances every distinct affected relation"
          (is (= (d/tx->t (:tx owner))
                 (d/tx->t (:tx viewer))
                 (d/basis-t db)))
          (is (= (:v owner) (:v viewer))))
        (eacl/delete-object! client (eacl/spice-object :user "user-1"))
        (let [after (d/db conn)]
          (is (= (d/basis-t after)
                 (d/tx->t (:tx (stamp after :owner)))
                 (d/tx->t (:tx (stamp after :viewer)))))
          (is (not= (:tx owner) (:tx (stamp after :owner))))
          (is (nil? (journal/graph-state after))))))))

(deftest legacy-retry-journal-is-explicit-and-cache-independent-test
  (with-mem-conn [conn schema/v7-schema]
    (journal/ensure-migrated! conn)
    (let [mutation-id (mutation/new-id)
          options {:mutation-id mutation-id
                   :kind :custom
                   :canonical-data {:operation :optional-audit}
                   :tx-data []}
          report (journal/transact! conn options)
          recovered (journal/transact! conn options)]
      (is (not (:idempotent-recovery? report)))
      (is (true? (:idempotent-recovery? recovered)))
      (is (journal/contains-anchor? (d/db conn) mutation-id)))))

(deftest unknown-cache-authority-still-issues-native-token-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (datomic/make-client conn {:zed-token-key security-key})
          token (datomic/current-zed-token client)
          payload (causal-token/token-data
                   (get-in client [:opts :format-options]) token)]
      (is (= :datomic (:backend payload)))
      (is (= (d/basis-t (d/db conn)) (:revision payload))))))

(deftest preparation-initializes-only-missing-generations-idempotently-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (datomic/make-client conn {:zed-token-key security-key})
          _ (eacl/write-schema! client test-schema)
          db (d/db conn)
          owner-stamp (stamp db :owner)]
      @(d/transact conn [[:db/retract (relation-eid db :owner)
                          :eacl/relation-version (:v owner-stamp)]])
      (let [prepared (datomic/prepare-cache-coherence! conn)
            repeated (datomic/prepare-cache-coherence! conn)]
        (is (true? (:prepared? prepared)))
        (is (true? (:changed? prepared)))
        (is (= 1 (:relation-generations-initialized prepared)))
        (is (empty? (:missing-after prepared)))
        (is (false? (:changed? repeated)))
        (is (zero? (:relation-generations-initialized repeated)))))))

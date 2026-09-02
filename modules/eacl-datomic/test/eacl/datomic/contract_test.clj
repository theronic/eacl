(ns eacl.datomic.contract-test
  (:require [clojure.test :refer [deftest is]]
            [datomic.api :as d]
            [eacl.cache :as shared-cache]
            [eacl.backend.v8 :as backend]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers
             :refer [with-mem-conn with-mem-conns]]
            [eacl.datomic.schema :as schema]
            [eacl.spicedb.consistency :as consistency]))

(defn- seed-objects!
  [conn]
  @(d/transact conn
               (mapv (fn [{:keys [id]}]
                       {:db/id id
                        :eacl/id id})
                     contract/smoke-objects)))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest recreated-memory-database-rejects-prior-source-token-test
  (let [key "01234567890123456789012345678901"]
    (with-mem-conn [first-conn schema/v7-schema]
      (let [first-client (datomic/make-client first-conn {:security-key key})]
        (eacl/write-schema! first-client contract/smoke-schema)
        (seed-objects! first-conn)
        (let [old-token
              (:zed/token
               (eacl/create-relationship!
                first-client
                (first contract/smoke-relationships)))
              _ (eacl/create-relationships!
                 first-client (rest contract/smoke-relationships))
              query {:subject (contract/->user "user-1")
                     :permission :view
                     :resource/type :server
                     :first 1}
              first-page (eacl/lookup-resources first-client query)
              oracle-stream
              (:data
               (eacl/lookup-resources
                first-client
                (assoc query
                       :first 10
                       :cache? false
                       :populate-cache? false)))]
          (with-mem-conn [second-conn schema/v7-schema]
            (let [second-client
                  (datomic/make-client second-conn {:security-key key})]
              (eacl/write-schema! second-client contract/smoke-schema)
              (seed-objects! second-conn)
              (eacl/create-relationships!
               second-client contract/smoke-relationships)
              (is (= :eacl.consistency/incomparable-scope
                     (:type
                      (error-data
                       #(eacl/can?
                         second-client
                         (contract/->user "user-1")
                         :admin
                         (contract/->account "account-1")
                         (consistency/at-least-as-fresh old-token))))))
              (contract/assert-cursor-source-transition!
               {:client second-client
                :query query
                :first-page first-page
                :oracle-stream oracle-stream
                :durability :non-durable}))))))))

(deftest default-source-lifecycle-is-cross-client-constant-test
  (with-mem-conn [conn schema/v7-schema]
    (let [key "01234567890123456789012345678901"
          client-a (datomic/make-client conn {:security-key key})
          client-b (datomic/make-client conn {:security-key key})
          snapshot-a (eacl/snapshot client-a)
          snapshot-b (eacl/snapshot client-b)]
      (try
        (is (= "eacl/initial"
               (get-in client-a [:runtime :source-lifecycle])
               (get-in client-b [:runtime :source-lifecycle])
               (:source-lifecycle (eacl/basis snapshot-a))
               (:source-lifecycle (eacl/basis snapshot-b))))
        (finally
          (eacl/release! snapshot-a)
          (eacl/release! snapshot-b)))
      (eacl/write-schema! client-a contract/smoke-schema)
      (seed-objects! conn)
      (let [token
            (:zed/token
             (eacl/create-relationship!
              client-a (first contract/smoke-relationships)))]
        (is (true?
             (eacl/can?
              client-b
              (contract/->user "user-1") :admin
              (contract/->account "account-1")
              (consistency/at-least-as-fresh token))))))))

(deftest same-database-connection-handoff-preserves-cursor-lineage-test
  (with-mem-conns [first-conn second-conn schema/v7-schema]
    (let [key "01234567890123456789012345678901"
          first-client (datomic/make-client first-conn {:security-key key})
          second-client (datomic/make-client second-conn {:security-key key})
          query {:subject (contract/->user "user-1")
                 :permission :view
                 :resource/type :server
                 :first 1}]
      (eacl/write-schema! first-client contract/smoke-schema)
      (seed-objects! first-conn)
      (eacl/create-relationships!
       first-client contract/smoke-relationships)
      (let [first-page (eacl/lookup-resources first-client query)
            oracle-stream
            (:data
             (eacl/lookup-resources
              first-client
              (assoc query
                     :first 10
                     :cache? false
                     :populate-cache? false)))]
        (contract/assert-cursor-source-transition!
         {:client second-client
          :query query
          :first-page first-page
          :oracle-stream oracle-stream
          :durability :durable})))))

(deftest removed-cache-coherence-options-are-unknown-test
  (with-mem-conn [conn schema/v7-schema]
    (doseq [[option values]
            [[:coherence-authority [:unknown :managed]]
             [:proof-mode [:auto :mutation :content :none]]]
            value values]
      (let [error
            (try
              (datomic/make-client conn {option value})
              nil
              (catch clojure.lang.ExceptionInfo cause
                (ex-data cause)))]
        (is (= :eacl/invalid-config (:type error)))
        (is (= [option] (:unknown-keys error)))))))

(deftest datomic-contract-test
  (with-mem-conn [conn schema/v7-schema]
    (let [security-key "datomic-contract-test00000000000"
          client (datomic/make-client conn {:security-key security-key})]
      (eacl/write-schema! client contract/smoke-schema)
      (seed-objects! conn)
      (eacl/create-relationships! client contract/smoke-relationships)
      (contract/assert-v8-seeded-contracts! client)
      (contract/assert-v8-cache-differential! client)
      (contract/assert-v8-permission-tree-contract! client)
      (contract/assert-authorization-target-matrix!
       {:writable client
        :read-only
        (datomic/make-client
         conn {:security-key security-key :read-only? true})})
      (contract/assert-unified-filter-validation! client)
      (contract/assert-v8-request-cache-controls! client {})
      (contract/assert-v8-cache-disabled!
       (datomic/make-client
        conn {:security-key security-key
              :cache shared-cache/no-cache})))))

(deftest datomic-certified-generation-plan-reuse-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (datomic/make-client
                  conn {:security-key "datomic-plan-reuse00000000000000"})]
      (eacl/write-schema! client contract/smoke-schema)
      (seed-objects! conn)
      (eacl/create-relationships! client contract/smoke-relationships)
      (contract/assert-certified-generation-plan-reuse! client))))

(deftest datomic-pinned-spicedb-permission-tree-golden-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (datomic/make-client conn {})]
      (eacl/write-schema! client contract/permission-tree-golden-schema)
      @(d/transact
        conn
        (mapv (fn [{:keys [id]}]
                {:db/id id :eacl/id id})
              contract/permission-tree-golden-objects))
      (eacl/create-relationships!
       client contract/permission-tree-golden-relationships)
      (contract/assert-pinned-permission-tree-golden! client)
      (let [request {:resource (eacl/spice-object :document "testdoc")
                     :permission :view}
            first-response (eacl/expand-permission-tree client request)
            exact-request
            (assoc request :consistency
                   (consistency/at-exact-snapshot
                    (:expanded-at first-response)))
            before (datomic/cache-stats client)
            exact-response (eacl/expand-permission-tree client exact-request)
            repeated-exact (eacl/expand-permission-tree client exact-request)
            after (datomic/cache-stats client)]
        (is (= (:tree-root first-response)
               (:tree-root exact-response)
               (:tree-root repeated-exact)))
        (is (= (inc (:exact-hits before))
               (:exact-hits after))
            "a lifted as-of answer is promoted; its repeat hits exactly")))))

(deftest datomic-permission-tree-schema-mutation-snapshot-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (datomic/make-client conn {})
          resource (eacl/spice-object :document "d1")
          old-schema
          "definition user {}
           definition document {
             relation viewer: user
             permission view = viewer
           }"
          new-schema
          "definition user {}
           definition document {
             relation viewer: user
             relation editor: user
             permission view = viewer + editor
           }"
          _ (eacl/write-schema! client old-schema)
          _ @(d/transact conn [{:db/id "d1" :eacl/id "d1"}])
          mutated? (atom false)
          captured
          (binding [backend/*invoke-observer*
                    (fn [{:keys [phase operation]}]
                      (when (and (= :before phase)
                                 (= :relation-defs operation)
                                 (compare-and-set! mutated? false true))
                        (eacl/write-schema! client new-schema)))]
            (eacl/expand-permission-tree
             client {:resource resource :permission :view}))]
      (is (= 1 (count (get-in captured
                              [:tree-root :intermediate :children]))))
      (is (= 2 (count (get-in
                       (eacl/expand-permission-tree
                        client {:resource resource :permission :view})
                       [:tree-root :intermediate :children])))))))

(deftest datomic-recursive-contract-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client
          (datomic/make-client
           conn
           {:security-key "datomic-recursive-contract-test0"
            :cache {}})]
      (eacl/write-schema! client contract/recursive-schema)
      @(d/transact conn
                   (mapv (fn [{:keys [id]}]
                           {:db/id id
                            :eacl/id id})
                         contract/recursive-objects))
      (eacl/create-relationships! client contract/recursive-relationships)
      (contract/assert-v8-recursive-contracts! client)
      (doseq [limit-key [:max-derived-grants
                         :max-advanced-datoms
                         :max-queued-work]]
        (contract/assert-v8-recursive-safety-limit!
         (datomic/make-client
          conn
          {:security-key
           (str "datomic-recursive-safety-" (name limit-key))
           :cache {}
           :recursive-traversal-limits {limit-key 1}}))))))

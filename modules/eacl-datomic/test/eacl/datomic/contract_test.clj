(ns eacl.datomic.contract-test
  (:require [clojure.test :refer [deftest is]]
            [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]
            [eacl.spicedb.consistency :as consistency]))

(defn- seed-objects!
  [conn]
  @(d/transact conn
     (mapv (fn [{:keys [id]}]
             {:db/id id
              :eacl/id id})
       contract/smoke-objects)))

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
    (let [client (datomic/make-client conn {:page-token-key "datomic-contract-test"})]
      (eacl/write-schema! client contract/smoke-schema)
      (seed-objects! conn)
      (eacl/create-relationships! client contract/smoke-relationships)
      (contract/assert-v8-seeded-contracts! client)
      (contract/assert-v8-permission-tree-contract! client)
      (contract/assert-unified-filter-validation! client))))

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
            first-response (eacl/expand-permission-tree client request)]
        (is (= (:tree-root first-response)
               (:tree-root
                (eacl/expand-permission-tree
                 client
                 (assoc request :consistency
                        (consistency/at-exact-snapshot
                         (:expanded-at first-response)))))))))))

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
           {:page-token-key "datomic-recursive-contract-test"
            :cache {:remember-answers true}})]
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
          {:page-token-key
           (str "datomic-recursive-safety-" (name limit-key))
           :cache cache/no-cache
           :recursive-traversal-limits {limit-key 1}}))))))

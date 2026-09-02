(ns eacl.datahike.contract-test
  "The shared backend contract, on Datahike. A backend is finished when this
   passes — it is the same suite `eacl.datascript.contract-test` runs, so the
   two backends are held to one definition rather than to separate tests that
   drifted.

   The suite runs TWICE, once per attribute representation. Datahike reports a
   datom's `:a` as the attribute keyword by default and as a numeric ref under
   `:attribute-refs? true` (Datomic's representation). Composite relation and
   permission tuples need replikativ/datahike#921 to derive in the latter mode,
   and code comparing `:a` directly against a keyword stops matching. Both
   failures deny permissions, so both representations remain mandatory."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]
            [eacl.spicedb.consistency :as consistency]
            [eacl.verified-kernel :as verified]))

(defn- seed-objects!
  "The contract's objects, addressed by `:eacl/id`. Negative `:db/id`s are
   tempids, as in the DataScript contract test."
  [conn]
  (d/transact conn
              (vec (map-indexed (fn [idx {:keys [id]}]
                                  {:db/id (- (inc idx))
                                   :eacl/id id})
                                contract/smoke-objects))))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- current-source-scope
  [client]
  (eacl/with-snapshot [snapshot (eacl/snapshot client)]
    (select-keys (eacl/basis snapshot) [:backend :source-id :branch])))

(deftest portable-cache-api-round-trip-test
  (let [conn (datahike/create-conn)
        client (datahike/make-client conn {})
        bounds {:max-entries 64}
        before (datahike/cache-content-revision client)
        snapshot (datahike/export-cache-snapshot client bounds)
        restored (datahike/restore-cache-snapshot! client snapshot bounds)]
    (is (= :eacl.cache/basis-snapshot-v2 (:format snapshot)))
    (is (zero? (:entry-count snapshot)))
    (is (true? (:restored? restored)))
    (is (> (datahike/cache-content-revision client) before))))

(deftest native-speculative-contract-test
  (is (nil? (ns-resolve 'eacl.datahike.core 'snapshot)))
  (let [conn (datahike/create-conn nil {})
        config (:config (d/db conn))
        client (datahike/make-client conn {})]
    (try
      (contract/assert-speculative-contract!
       client
       #(d/transact
         conn {:tx-data [{:eacl/id "speculative-user"}
                         {:eacl/id "speculative-account"}]}))
      (finally
        (d/release conn)
        (d/delete-database config)))))

(deftest fixed-memory-store-id-does-not-become-lineage-test
  (let [key "01234567890123456789012345678901"
        fixed-id (random-uuid)
        config {:store {:backend :memory :id fixed-id}}
        first-conn (datahike/create-conn nil config)
        first-config (:config (d/db first-conn))
        first-result
        (try
          (let [first-client
                (datahike/make-client first-conn {:security-key key})]
            (eacl/write-schema! first-client contract/smoke-schema)
            (seed-objects! first-conn)
            (let [token
                  (:zed/token
                   (eacl/create-relationship!
                    first-client
                    (first contract/smoke-relationships)))
                  _ (eacl/create-relationships!
                     first-client (rest contract/smoke-relationships))
                  query {:subject (contract/->user "user-1")
                         :permission :view
                         :resource/type :server
                         :first 1}]
              {:token token
               :query query
               :first-page (eacl/lookup-resources first-client query)
               :oracle-stream
               (:data
                (eacl/lookup-resources
                 first-client
                 (assoc query
                        :first 10
                        :cache? false
                        :populate-cache? false)))
               :scope (current-source-scope first-client)}))
          (finally
            (d/release first-conn)
            (d/delete-database first-config)))
        second-conn (datahike/create-conn nil config)]
    (try
      (let [second-client
            (datahike/make-client second-conn {:security-key key})
            second-scope
            (current-source-scope second-client)]
        (is (not= (:scope first-result) second-scope))
        (is (not= (str fixed-id)
                  (get-in first-result [:scope :source-id :store-id])))
        (is (not= (str fixed-id)
                  (get-in second-scope [:source-id :store-id])))
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
                   (consistency/at-least-as-fresh
                    (:token first-result)))))))
        (contract/assert-cursor-source-transition!
         {:client second-client
          :query (:query first-result)
          :first-page (:first-page first-result)
          :oracle-stream (:oracle-stream first-result)
          :durability :non-durable}))
      (finally
        (let [second-config (:config (d/db second-conn))]
          (d/release second-conn)
          (d/delete-database second-config))))))

(deftest default-source-lifecycle-is-cross-client-constant-test
  (let [conn (datahike/create-conn)
        key "01234567890123456789012345678901"
        client-a (datahike/make-client conn {:security-key key})
        client-b (datahike/make-client conn {:security-key key})
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
    (try
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
              (consistency/at-least-as-fresh token)))))
      (finally
        (d/release conn)))))

(deftest generated-authority-is-the-only-production-engine-test
  (let [conn (datahike/create-conn)
        default-selection
        (get-in (datahike/make-client conn {}) [:runtime :decision-kernel])
        error
        (try
          (datahike/make-client conn {:engine-selection :anything})
          nil
          (catch clojure.lang.ExceptionInfo exception
            (ex-data exception)))]
    (is (satisfies? verified/DecisionKernel (:kernel default-selection)))
    (is (= :eacl/invalid-config (:type error)))
    (is (= [:engine-selection] (:unknown-keys error)))))

(deftest removed-cache-coherence-options-are-unknown-test
  (let [conn (datahike/create-conn)]
    (doseq [[option values]
            [[:coherence-authority [:unknown :managed]]
             [:proof-mode [:auto :mutation :content :none]]]
            value values]
      (let [error
            (try
              (datahike/make-client conn {option value})
              nil
              (catch clojure.lang.ExceptionInfo cause
                (ex-data cause)))]
        (is (= :eacl/invalid-config (:type error)))
        (is (= [option] (:unknown-keys error)))))))

(defn- run-contract!
  [config]
  (let [conn   (datahike/create-conn nil config)
        store  (contract/portable-store)
        client (datahike/make-client conn {:cache store})]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (eacl/create-relationships! client contract/smoke-relationships)
    (contract/assert-v8-seeded-contracts! client)
    (contract/assert-v8-cache-differential! client)
    (contract/assert-v8-permission-tree-contract! client)
    (contract/assert-authorization-target-matrix!
     {:writable client
      :read-only (datahike/make-client conn {:read-only? true})})
    (contract/assert-unified-filter-validation! client)
    (contract/assert-v8-request-cache-controls! client store)
    (contract/assert-v8-cache-disabled!
     (datahike/make-client conn {:cache cache/no-cache}))))

(defn- run-recursive-contract!
  [config]
  (let [conn (datahike/create-conn nil config)
        client
        (datahike/make-client
         conn
         {})]
    (eacl/write-schema! client contract/recursive-schema)
    (d/transact
     conn
     (vec
      (map-indexed
       (fn [index {:keys [id]}]
         {:db/id (- (inc index))
          :eacl/id id})
       contract/recursive-objects)))
    (eacl/create-relationships! client contract/recursive-relationships)
    (contract/assert-v8-recursive-contracts! client)
    (doseq [limit-key [:max-derived-grants
                       :max-advanced-datoms
                       :max-queued-work]]
      (contract/assert-v8-recursive-safety-limit!
       (datahike/make-client
        conn
        {:cache {}
         :recursive-traversal-limits {limit-key 1}})))))

(deftest datahike-contract-test
  (testing "attributes as keywords (datahike's default)"
    (run-contract! nil))

  (testing "attributes as numeric refs (:attribute-refs?, Datomic's representation)"
    (run-contract! {:attribute-refs? true})))

(deftest datahike-certified-generation-plan-reuse-test
  (doseq [[label config]
          [["attributes as keywords" nil]
           ["attributes as numeric refs" {:attribute-refs? true}]]]
    (testing label
      (let [conn (datahike/create-conn nil config)
            client (datahike/make-client conn {})]
        (eacl/write-schema! client contract/smoke-schema)
        (seed-objects! conn)
        (eacl/create-relationships! client contract/smoke-relationships)
        (contract/assert-certified-generation-plan-reuse! client)))))

(deftest datahike-pinned-spicedb-permission-tree-golden-test
  (doseq [config [nil {:attribute-refs? true}]]
    (let [conn (datahike/create-conn nil config)
          client (datahike/make-client conn {})]
      (eacl/write-schema! client contract/permission-tree-golden-schema)
      (d/transact
       conn
       (mapv (fn [{:keys [id]}] {:eacl/id id})
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
            before (datahike/cache-stats client)
            exact-response (eacl/expand-permission-tree client exact-request)
            repeated-exact (eacl/expand-permission-tree client exact-request)
            after (datahike/cache-stats client)]
        (is (= (:tree-root first-response)
               (:tree-root exact-response)
               (:tree-root repeated-exact)))
        (is (= (+ 2 (:exact-hits before))
               (:exact-hits after))
            "tree roots are reusable, while expanded-at is rebuilt per exact request")))))

(deftest datahike-permission-tree-schema-mutation-snapshot-test
  (let [conn (datahike/create-conn)
        client (datahike/make-client conn {})
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
        _ (d/transact conn [{:eacl/id "d1"}])
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
                     [:tree-root :intermediate :children]))))))

(deftest datahike-recursive-v8-contract-test
  (testing "attributes as keywords"
    (run-recursive-contract! nil))
  (testing "attributes as numeric refs"
    (run-recursive-contract! {:attribute-refs? true})))

(deftest datahike-multi-connection-cache-proof-test
  (doseq [attribute-refs? [false true]]
    (testing (str "database-visible proofs with attribute refs "
                  attribute-refs?)
      (let [conn-1 (datahike/create-conn nil
                                         {:attribute-refs? attribute-refs?})
            conn-2 (d/connect (:config (d/db conn-1)))
            store (contract/portable-store)
            client-1 (datahike/make-client conn-1 {:cache store})
            client-2 (datahike/make-client conn-2 {:cache store})
            query {:subject (contract/->user "user-2")
                   :permission :view
                   :resource/type :server
                   :first 10}]
        (eacl/write-schema! client-1 contract/smoke-schema)
        (seed-objects! conn-1)
        (eacl/create-relationships!
         client-1 contract/smoke-relationships)

        (let [miss (eacl/lookup-resources client-1 query)
              hit (eacl/lookup-resources client-1 query)]
          (clojure.test/is (= [] (:data miss)))
          (clojure.test/is (false? (:cached? miss)))
          (clojure.test/is (true? (:cached? hit))))

        (eacl/create-relationship!
         client-2
         (contract/->user "user-2")
         :owner
         (contract/->account "account-1"))

        (let [after-write (eacl/lookup-resources client-1 query)]
          (clojure.test/is (false? (:cached? after-write)))
          (clojure.test/is
           (= #{(contract/->server "server-1")
                (contract/->server "server-2")}
              (set (:data after-write)))))))))

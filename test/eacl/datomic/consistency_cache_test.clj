(ns eacl.datomic.consistency-cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.consistency :as revision]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.schema :as schema]
            [eacl.spicedb.consistency :as consistency]))

(def ^:private direct-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(defn- cached-client
  [conn]
  (let [context (cache/local-context)]
    (core/make-client
     conn
     {:cache (assoc context
                    :live-results? true
                    :checkpoints true)})))

(defn- seed!
  [conn client]
  (eacl/write-schema! client direct-schema)
  @(d/transact conn [{:eacl/id "alice"}
                     {:eacl/id "bob"}
                     {:eacl/id "acct"}])
  (eacl/create-relationship!
   client
   (->Relationship (spice-object :user "alice")
                   :owner
                   (spice-object :account "acct"))))

(deftest can-results-obey-all-cache-consistency-modes-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          alice (spice-object :user "alice")
          account (spice-object :account "acct")
          relationship (->Relationship alice :owner account)
          {created-token :zed/token} (seed! conn client)
          calls (atom 0)
          original impl/can?]
      (with-redefs [impl/can?
                    (fn [db subject permission resource]
                      (swap! calls inc)
                      (original db subject permission resource))
                    d/sync
                    (fn [& _]
                      (throw (ex-info "unexpected sync" {})))]
        (testing "fully-consistent reuses only the current dependency proof"
          (is (true? (eacl/can? client alice :admin account)))
          (is (true? (eacl/can? client alice :admin account)))
          (is (= 1 @calls)))

        (let [{deleted-token :zed/token}
              (eacl/delete-relationship! client relationship)]
          (testing "minimize-latency may use the last coherent cached answer"
            (is (true? (eacl/can? client alice :admin account
                                  consistency/minimize-latency)))
            (is (= 1 @calls)))

          (testing "exact mode is cache resident and never time travels"
            (is (true? (eacl/can? client alice :admin account
                                  (consistency/at-exact-snapshot
                                   created-token))))
            (is (= 1 @calls)))

          (testing "fully-consistent observes the relationship deletion"
            (is (false? (eacl/can? client alice :admin account)))
            (is (= 2 @calls)))

          (testing "at-least-as-fresh accepts the current cached revision"
            (is (false? (eacl/can? client alice :admin account
                                   (consistency/at-least-as-fresh
                                    deleted-token))))
            (is (= 2 @calls))))))))

(deftest missing-external-ids-never-enter-the-result-cache-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          _ (seed! conn client)
          calls (atom 0)
          original impl/can?]
      (with-redefs [impl/can?
                    (fn [db subject permission resource]
                      (swap! calls inc)
                      (original db subject permission resource))]
        (is (false? (eacl/can? client
                               (spice-object :user "missing")
                               :admin
                               (spice-object :account "acct"))))
        (is (false? (eacl/can? client
                               (spice-object :user "missing")
                               :admin
                               (spice-object :account "acct"))))
        (is (zero? @calls)
            "boundary resolution returns before graph traversal or result caching")))))

(deftest exact-lookup-is-a-cache-snapshot-and-misses-fail-closed-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          alice (spice-object :user "alice")
          account (spice-object :account "acct")
          relationship (->Relationship alice :owner account)
          {created-token :zed/token} (seed! conn client)
          query {:subject alice
                 :permission :admin
                 :resource/type :account
                 :first 10}]
      (is (= ["acct"]
             (mapv :id (:data (eacl/lookup-resources client query)))))
      (eacl/delete-relationship! client relationship)
      (with-redefs [d/as-of
                    (fn [& _]
                      (throw (ex-info "d/as-of must not be called" {})))]
        (let [exact-page
              (eacl/lookup-resources
               client
               (assoc query
                      :consistency
                      (consistency/at-exact-snapshot created-token)))
              cursor (get-in exact-page [:page-info :end-cursor])
              decoded (core/token->page-bound
                       (get client :opts)
                       cursor)
              database-id (get-in client [:opts :database-id])]
          (is (= ["acct"] (mapv :id (:data exact-page))))
          (is (= (revision/token-revision database-id created-token)
                 (:basis-t decoded))
              "a cursor names the cached snapshot that produced its page"))
        (try
          (eacl/lookup-resources
           client
           (assoc query
                  :first 11
                  :consistency
                  (consistency/at-exact-snapshot created-token)))
          (is false "an uncomputed exact query must be unavailable")
          (catch clojure.lang.ExceptionInfo e
            (is (= :eacl.consistency/snapshot-unavailable
                   (:type (ex-data e))))))
        (is (empty?
             (:data (eacl/lookup-resources client query))))))))

(deftest exact-result-retention-is-explicit-without-live-coordination-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client
          (core/make-client
           conn
           {:cache {:exact-results? true}})
          alice (spice-object :user "alice")
          account (spice-object :account "acct")
          relationship (->Relationship alice :owner account)
          {created-token :zed/token} (seed! conn client)]
      (is (true? (eacl/can? client alice :admin account)))
      (eacl/delete-relationship! client relationship)
      (is (true?
           (eacl/can? client alice :admin account
                      (consistency/at-exact-snapshot created-token))))
      (is (false? (eacl/can? client alice :admin account)))))

  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {})
          alice (spice-object :user "alice")
          account (spice-object :account "acct")
          {created-token :zed/token} (seed! conn client)]
      (is (true? (eacl/can? client alice :admin account)))
      (is (zero?
           (:entries
            (cache/stats (get-in client [:opts :lookup-cache-store]))))
          "the default fast path does not publish completed can? results")
      (try
        (eacl/can? client alice :admin account
                   (consistency/at-exact-snapshot created-token))
        (is false "default clients do not tax can? with exact-result retention")
        (catch clojure.lang.ExceptionInfo e
          (is (= :exact-result-caching-disabled
                 (:reason (ex-data e)))))))))

(deftest exact-query-with-an-unresolved-current-boundary-fails-closed-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          alice (spice-object :user "alice")
          account (spice-object :account "acct")
          relationship (->Relationship alice :owner account)
          {created-token :zed/token} (seed! conn client)]
      (is (true? (eacl/can? client alice :admin account)))
      (eacl/delete-relationship! client relationship)
      @(d/transact conn [[:db.fn/retractEntity [:eacl/id "acct"]]])
      (try
        (eacl/can? client alice :admin account
                   (consistency/at-exact-snapshot created-token))
        (is false "EACL cannot reconstruct a deleted external-ID boundary")
        (catch clojure.lang.ExceptionInfo e
          (is (= :eacl.consistency/snapshot-unavailable
                 (:type (ex-data e))))
          (is (= :unresolved-boundary-object
                 (:reason (ex-data e)))))))))

(deftest zed-token-helpers-never-force-sync-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          _ (seed! conn client)
          current-token (core/current-zed-token client)
          age-token (core/zed-token-at-least-seconds-ago client 30)
          database-id (get-in client [:opts :database-id])]
      (is (integer? (revision/token-revision database-id current-token)))
      (is (integer? (revision/token-revision database-id age-token)))
      (with-redefs [d/sync
                    (fn [& _]
                      (throw (ex-info "unexpected sync" {})))]
        (is (true?
             (eacl/can? client
                        (spice-object :user "alice")
                        :admin
                        (spice-object :account "acct")
                        (consistency/at-least-as-fresh age-token))))))))

(deftest cross-database-zed-token-is-rejected-test
  (with-mem-conn [conn-a schema/v7-schema]
    (with-mem-conn [conn-b schema/v7-schema]
      (let [client-a (cached-client conn-a)
            client-b (cached-client conn-b)
            _ (seed! conn-a client-a)
            _ (seed! conn-b client-b)
            token (core/current-zed-token client-a)]
        (try
          (eacl/can? client-b
                     (spice-object :user "alice")
                     :admin
                     (spice-object :account "acct")
                     (consistency/at-least-as-fresh token))
          (is false "a token cannot cross databases")
          (catch clojure.lang.ExceptionInfo e
            (is (= :database-mismatch (:reason (ex-data e))))))))))

(deftest page-cursor-reuses-current-db-only-while-relationship-proof-matches-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          alice (spice-object :user "alice")
          _ (eacl/write-schema! client direct-schema)
          _ @(d/transact conn [{:eacl/id "alice"}
                               {:eacl/id "acct-1"}
                               {:eacl/id "acct-2"}
                               {:eacl/id "acct-3"}])
          _ (eacl/create-relationships!
             client
             [(->Relationship alice :owner
                              (spice-object :account "acct-1"))
              (->Relationship alice :owner
                              (spice-object :account "acct-2"))])
          query {:subject alice
                 :permission :admin
                 :resource/type :account
                 :first 1}
          page1 (eacl/lookup-resources client query)
          cursor (get-in page1 [:page-info :end-cursor])]
      @(d/transact conn [{:eacl/id "unrelated-app-data"}])
      (with-redefs [d/as-of
                    (fn [& _]
                      (throw (ex-info "d/as-of must not be called" {})))]
        (is (= ["acct-2"]
               (mapv :id
                     (:data
                      (eacl/lookup-resources
                       client
                       (assoc query :after cursor)))))))
      (let [new-page1 (eacl/lookup-resources client query)
            new-cursor (get-in new-page1 [:page-info :end-cursor])]
        (eacl/create-relationship!
         client
         (->Relationship alice :owner
                         (spice-object :account "acct-3")))
        (try
          (eacl/lookup-resources client
                                 (assoc query :after new-cursor))
          (is false "a changed relationship proof cannot fall forward")
          (catch clojure.lang.ExceptionInfo e
            (is (= :eacl.consistency/snapshot-unavailable
                   (:type (ex-data e))))))))))

(deftest at-least-as-fresh-targets-only-the-requested-revision-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          _ (seed! conn client)
          database-id (get-in client [:opts :database-id])
          future-t (inc (d/basis-t (d/db conn)))
          future-token (revision/zed-token database-id future-t)
          sync-calls (atom [])]
      (with-redefs [d/sync
                    (fn [_conn t]
                      (swap! sync-calls conj t)
                      (delay (d/db conn)))]
        (try
          (eacl/can? client
                     (spice-object :user "alice")
                     :admin
                     (spice-object :account "acct")
                     (consistency/at-least-as-fresh future-token))
          (is false "the Peer did not actually reach the future revision")
          (catch clojure.lang.ExceptionInfo e
            (is (= :eacl.consistency/freshness-unavailable
                   (:type (ex-data e)))))))
      (is (= [future-t] @sync-calls)
          "EACL waits for the caller's exact lower bound, not transactor head"))))

(deftest at-least-as-fresh-propagates-a-targeted-sync-timeout-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          _ (seed! conn client)
          database-id (get-in client [:opts :database-id])
          future-t (inc (d/basis-t (d/db conn)))
          token (revision/zed-token database-id future-t)]
      (with-redefs [d/sync
                    (fn [_conn t]
                      (throw
                       (ex-info "targeted synchronization timed out"
                                {:type :eacl.consistency/sync-timeout
                                 :requested-t t})))]
        (try
          (eacl/can? client
                     (spice-object :user "alice")
                     :admin
                     (spice-object :account "acct")
                     (consistency/at-least-as-fresh token))
          (is false "a timeout cannot fall back to an older answer")
          (catch clojure.lang.ExceptionInfo e
            (is (= :eacl.consistency/sync-timeout
                   (:type (ex-data e))))
            (is (= future-t (:requested-t (ex-data e))))))))))

(deftest exact-provider-failure-is-snapshot-unavailable-test
  (with-mem-conn [conn schema/v7-schema]
    (let [delegate (cache/local-store)
          fail? (atom false)
          store
          (reify
            cache/CacheStore
            (lookup [_ k]
              (if @fail?
                (throw (ex-info "provider unavailable" {}))
                (cache/lookup delegate k)))
            (store! [_ k value weight ttl]
              (cache/store! delegate k value weight ttl))
            (evict! [_ k] (cache/evict! delegate k))
            (clear! [_] (cache/clear! delegate))
            (stats [_] (cache/stats delegate))

            cache/CacheProvider
            (capabilities [_] (cache/capabilities delegate))
            (clear-namespace! [_ namespace]
              (cache/clear-namespace! delegate namespace))
            (record-provider-error! [_ operation kind]
              (cache/record-provider-error! delegate operation kind)))
          context {:store store
                   :coordinator (cache/local-coordinator)
                   :live-results? true}
          client (core/make-client conn {:cache context})
          {token :zed/token} (seed! conn client)
          alice (spice-object :user "alice")
          account (spice-object :account "acct")]
      (is (true? (eacl/can? client alice :admin account)))
      (reset! fail? true)
      (try
        (eacl/can? client alice :admin account
                   (consistency/at-exact-snapshot token))
        (is false "exact mode cannot recompute after provider failure")
        (catch clojure.lang.ExceptionInfo e
          (is (= :eacl.consistency/snapshot-unavailable
                 (:type (ex-data e))))))
      (is (pos? (:provider-errors (cache/stats delegate)))))))

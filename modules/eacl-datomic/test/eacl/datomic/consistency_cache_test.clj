(ns eacl.datomic.consistency-cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk]
            [datomic.api :as d]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as shared-cache]
            [eacl.causal-token :as causal-token]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.backend :as datomic-backend]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn with-mem-conns]]
            [eacl.datomic.schema :as schema]
            [eacl.engine.v8 :as engine]
            [eacl.spicedb.consistency :as consistency]))

(def ^:private direct-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(def ^:private source-lifecycle "datomic-consistency-cache-v4-test")

(defn- cached-client
  [conn]
  (core/make-client
   conn
   {:security-key "consistency-cache-test-key000000"
    :source-lifecycle source-lifecycle
    :cache {}}))

(defn- token-payload
  [client token]
  (causal-token/token-data
   (get-in client [:runtime :format-options])
   token))

(defn- token-with-order-hint
  [client order-hint]
  (let [format-options (get-in client [:runtime :format-options])
        payload
        (token-payload client (core/current-zed-token client))]
    (causal-token/issue
     format-options
     (assoc payload
            :revision order-hint
            :exact-locator order-hint))))

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

(defn- ex-data-of
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(deftest readable-as-of-basis-lifts-from-a-newer-equal-proof-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          old-token (:zed/token (seed! conn client))
          alice (spice-object :user "alice")
          account (spice-object :account "acct")]
      @(d/transact conn [{:eacl/id "newer-unrelated"}])
      (let [newer (eacl/check-permission client alice :admin account)
            older
            (eacl/check-permission
             client
             {:subject alice
              :permission :admin
              :resource account
              :consistency (consistency/at-exact-snapshot old-token)})]
        (is (true? (:allowed? newer)))
        (is (false? (:cached? newer)))
        (is (true? (:allowed? older)))
        (is (true? (:cached? older))
            "a readable equal as-of proof lifts in the older direction")))))

(deftest readable-as-of-basis-misses-when-its-proof-differs-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          old-token (:zed/token (seed! conn client))
          alice (spice-object :user "alice")
          account (spice-object :account "acct")
          relationship
          (->Relationship alice :owner account)]
      (eacl/delete-relationship! client relationship)
      (let [newer (eacl/check-permission client alice :admin account)
            older
            (eacl/check-permission
             client
             {:subject alice
              :permission :admin
              :resource account
              :consistency (consistency/at-exact-snapshot old-token)})]
        (is (false? (:allowed? newer)))
        (is (false? (:cached? newer)))
        (is (true? (:allowed? older)))
        (is (false? (:cached? older))
            "different complete proofs cannot lift across revisions")))))

(deftest reader-peer-cache-survives-token-change-across-unrelated-write-test
  (with-mem-conns [writer-conn reader-conn schema/v7-schema]
    (let [writer (cached-client writer-conn)
          reader (cached-client reader-conn)
          _ (seed! writer-conn writer)
          alice (spice-object :user "alice")
          account (spice-object :account "acct")
          seeded (eacl/check-permission reader alice :admin account)]
      (is (true? (:allowed? seeded)))
      (is (false? (:cached? seeded)))
      @(d/transact writer-conn [{:eacl/id "peer-unrelated"}])
      (let [token (core/current-zed-token writer)
            refreshed
            (eacl/check-permission
             reader
             {:subject alice
              :permission :admin
              :resource account
              :consistency (consistency/at-least-as-fresh token)})]
        (is (true? (:allowed? refreshed)))
        (is (true? (:cached? refreshed))
            "the reader Peer preserves its managed generation across tokens")))))

(deftest explicit-cache-expiry-installs-a-fresh-lifecycle-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          _ (seed! conn client)
          alice (spice-object :user "alice")
          account (spice-object :account "acct")]
      (is (true? (eacl/can? client alice :admin account)))
      (is (true? (eacl/can? client alice :admin account)))
      (let [before (core/cache-stats client)]
        (is (pos? (:exact-hits before)))
        (core/expire-cache! client)
        (is (true? (eacl/can? client alice :admin account)))
        (let [after (core/cache-stats client)]
          (is (= (inc (:expirations before)) (:expirations after)))
          (is (= (inc (:misses before)) (:misses after)))
          (is (= (:exact-hits before) (:exact-hits after))))))))

(deftest schema-no-op-keeps-completed-cache-hot-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          _ (seed! conn client)
          alice (spice-object :user "alice")
          account (spice-object :account "acct")]
      (is (true? (eacl/can? client alice :admin account)))
      (is (true? (eacl/can? client alice :admin account)))
      (let [before (core/cache-stats client)]
        (eacl/write-schema! client direct-schema)
        (is (true? (eacl/can? client alice :admin account)))
        (let [after (core/cache-stats client)]
          (is (= (:expirations before) (:expirations after)))
          (is (= (:misses before) (:misses after)))
          (is (= (inc (:exact-hits before)) (:exact-hits after))))))))

(deftest native-on-repeat-admits-on-the-second-sighting-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client
          (core/make-client
           conn
           {:cache {:admit-on-repeat? true}})
          _ (seed! conn client)
          alice (spice-object :user "alice")
          account (spice-object :account "acct")
          calls (atom 0)
          original engine/can?]
      (with-redefs [engine/can?
                    (fn [db subject permission resource]
                      (swap! calls inc)
                      (original db subject permission resource))]
        (is (true? (eacl/can? client alice :admin account)))
        (is (true? (eacl/can? client alice :admin account)))
        (is (true? (eacl/can? client alice :admin account)))
        (is (= 2 @calls))))))

(deftest can-results-obey-all-cache-consistency-modes-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          alice (spice-object :user "alice")
          account (spice-object :account "acct")
          relationship (->Relationship alice :owner account)
          {created-token :zed/token} (seed! conn client)
          calls (atom 0)
          original engine/can?]
      (with-redefs [engine/can?
                    (fn [db subject permission resource]
                      (swap! calls inc)
                      (original db subject permission resource))]
        (testing "fully-consistent reuses only the current dependency proof"
          (is (true? (eacl/can? client alice :admin account)))
          (is (true? (eacl/can? client alice :admin account)))
          (is (= 1 @calls)))

        (let [{deleted-token :zed/token}
              (eacl/delete-relationship! client relationship)]
          (testing "minimize-latency validates against its selected snapshot"
            (is (false? (eacl/can? client alice :admin account
                                   consistency/minimize-latency)))
            (is (= 2 @calls)))

          (testing "exact mode returns the authenticated historical snapshot"
            (is (true? (eacl/can? client alice :admin account
                                  (consistency/at-exact-snapshot
                                   created-token))))
            (is (= 2 @calls)
                "the readable historical proof lifts the matching answer"))

          (testing "fully-consistent observes the relationship deletion"
            (is (false? (eacl/can? client alice :admin account)))
            (is (= 2 @calls)
                "the exact-basis deletion result is reused"))

          (testing "at-least-as-fresh accepts the current cached revision"
            (is (false? (eacl/can? client alice :admin account
                                   (consistency/at-least-as-fresh
                                    deleted-token))))
            (is (= 2 @calls)))

          (testing "and repeated exact selection remains snapshot-correct"
            (is (true? (eacl/can? client alice :admin account
                                  (consistency/at-exact-snapshot
                                   created-token))))
            (is (= 2 @calls)
                "the promoted exact entry remains snapshot-correct")))))))

(deftest missing-external-ids-never-enter-the-result-cache-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          _ (seed! conn client)
          calls (atom 0)
          original engine/can?]
      (with-redefs [engine/can?
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

(deftest detailed-permission-check-cache-provenance-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          _ (seed! conn client)
          demand {:subject (spice-object :user "alice")
                  :permission :admin
                  :resource (spice-object :account "acct")}
          calls (atom 0)
          original engine/can?]
      (with-redefs [engine/can?
                    (fn [db subject permission resource]
                      (swap! calls inc)
                      (original db subject permission resource))]
        (let [miss (eacl/check-permission client demand)
              hit (eacl/check-permission client demand)
              bypass
              (eacl/check-permission
               client (assoc demand :cache? false))
              retained-hit (eacl/check-permission client demand)]
          (is (= true
                 (:allowed? miss)
                 (:allowed? hit)
                 (:allowed? bypass)
                 (:allowed? retained-hit)))
          (is (false? (:cached? miss)))
          (is (true? (:cached? hit)))
          (is (false? (:cached? bypass)))
          (is (true? (:cached? retained-hit)))
          (is (= 2 @calls)
              "the bypass computes once without replacing the retained entry")
          (is (boolean? (eacl/can? client demand))))))))

(deftest exact-lookup-uses-cache-or-historical-replay-test
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
      (let [exact-page
            (eacl/lookup-resources
             client
             (assoc query
                    :consistency
                    (consistency/at-exact-snapshot created-token)))
            cursor (get-in exact-page [:page-info :end-cursor])]
        (is (= ["acct"] (mapv :id (:data exact-page))))
        (is (or (nil? cursor) (string? cursor))
            "the public boundary exposes only an opaque cursor"))
      (is (= ["acct"]
             (mapv
              :id
              (:data
               (eacl/lookup-resources
                client
                (assoc query
                       :first 11
                       :consistency
                       (consistency/at-exact-snapshot
                        created-token))))))
          "an exact cache miss replays from Datomic history")
      (is (empty?
           (:data (eacl/lookup-resources client query)))))))

(deftest exact-result-retention-is-explicit-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client
          (core/make-client
           conn
           {:cache {}})
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

  ;; Native current answers are client-private and admitted immediately. The
  ;; same completed semantic answer is also addressable by its exact snapshot.
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client
                  conn
                  {})
          alice (spice-object :user "alice")
          account (spice-object :account "acct")
          {created-token :zed/token} (seed! conn client)]
      (is (true? (eacl/can? client alice :admin account)))
      (is (pos?
           (:exact-entries
            (shared-cache/basis-cache-stats
             (get-in client [:runtime :basis-cache-store]))))
          "the first current answer is retained in the private generation")
      (is (true? (eacl/can? client alice :admin account)))
      (is (pos?
           (:exact-hits
            (shared-cache/basis-cache-stats
             (get-in client [:runtime :basis-cache-store]))))
          "the second sighting is an exact-basis hit")
      (is (true?
           (eacl/can? client alice :admin account
                      (consistency/at-exact-snapshot created-token)))
          "exact correctness does not require result retention"))))

(deftest exact-query-resolves-the-historical-boundary-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          alice (spice-object :user "alice")
          account (spice-object :account "acct")
          relationship (->Relationship alice :owner account)
          {created-token :zed/token} (seed! conn client)]
      (is (true? (eacl/can? client alice :admin account)))
      (eacl/delete-relationship! client relationship)
      @(d/transact conn [[:db.fn/retractEntity [:eacl/id "acct"]]])
      (is (true?
           (eacl/can? client alice :admin account
                      (consistency/at-exact-snapshot created-token)))
          "historical ID resolution does not depend on the live entity"))))

(deftest stale-cached-lookup-pages-use-their-selected-basis-test
  (testing "lookup-resources with minimize-latency"
    (with-mem-conn [conn schema/v7-schema]
      (let [client (cached-client conn)
            alice (spice-object :user "alice")
            account (spice-object :account "acct")
            relationship (->Relationship alice :owner account)
            _ (seed! conn client)
            query {:subject alice
                   :permission :admin
                   :resource/type :account
                   :first 10}]
        (is (= ["acct"]
               (mapv :id (:data (eacl/lookup-resources client query)))))
        (eacl/delete-relationship! client relationship)
        @(d/transact conn [[:db.fn/retractEntity [:eacl/id "acct"]]])
        (is (= []
               (mapv
                :id
                (:data
                 (eacl/lookup-resources
                  client
                  (assoc query
                         :consistency
                         consistency/minimize-latency)))))
            "minimize-latency never bypasses selected-snapshot proof validation"))))

  (testing "lookup-subjects with at-least-as-fresh"
    (with-mem-conn [conn schema/v7-schema]
      (let [client (cached-client conn)
            alice (spice-object :user "alice")
            account (spice-object :account "acct")
            relationship (->Relationship alice :owner account)
            {created-token :zed/token} (seed! conn client)
            query {:resource account
                   :permission :admin
                   :subject/type :user
                   :first 10}]
        (is (= ["alice"]
               (mapv :id (:data (eacl/lookup-subjects client query)))))
        (eacl/delete-relationship! client relationship)
        @(d/transact conn [[:db.fn/retractEntity [:eacl/id "alice"]]])
        (is (= []
               (mapv
                :id
                (:data
                 (eacl/lookup-subjects
                  client
                  (assoc query
                         :consistency
                         (consistency/at-least-as-fresh
                          created-token))))))
            "at-least selects a current dominating snapshot, not an old cached value")))))

(deftest exact-query-preserves-historical-unknown-object-semantics-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          _ (eacl/write-schema! client direct-schema)
          token (core/current-zed-token client)
          alice (spice-object :user "alice")
          account (spice-object :account "acct")
          exact (consistency/at-exact-snapshot token)]
      @(d/transact conn [{:eacl/id "alice"}
                         {:eacl/id "acct"}])
      (eacl/create-relationship!
       client
       (->Relationship alice :owner account))
      (is (false? (eacl/can? client alice :admin account exact)))
      (is (empty?
           (:data
            (eacl/lookup-resources
             client
             {:subject alice
              :permission :admin
              :resource/type :account
              :consistency exact}))))
      (is (= 0
             (:count
              (eacl/count-resources
               client
               {:subject alice
                :permission :admin
                :resource/type :account
                :consistency exact}))))
      (is (true? (eacl/can? client alice :admin account))
          "the same objects and relationship exist only in the live DB"))))

(deftest current-zed-token-never-forces-sync-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          _ (seed! conn client)
          current-token
          (with-redefs [d/sync
                        (fn [& _]
                          (throw (ex-info "token helpers must not sync" {})))]
            (core/current-zed-token client))
          current-payload (token-payload client current-token)]
      (is (integer? (:revision current-payload)))
      (is (true?
           (eacl/can? client
                      (spice-object :user "alice")
                      :admin
                      (spice-object :account "acct")
                      (consistency/at-least-as-fresh current-token)))))))

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
            (is (= :eacl.consistency/incomparable-scope
                   (:type (ex-data e))))
            (is (= :incomparable-scope
                   (:reason (ex-data e))))))))))

(deftest cross-database-page-cursor-is-rejected-before-history-test
  (with-mem-conn [conn-a schema/v7-schema]
    (with-mem-conn [conn-b schema/v7-schema]
      (let [token-key "shared-page-token-key00000000000"
            client-a (core/make-client conn-a {:security-key token-key})
            client-b (core/make-client conn-b {:security-key token-key})
            _ (seed! conn-a client-a)
            _ (seed! conn-b client-b)
            query {:subject (spice-object :user "alice")
                   :permission :admin
                   :resource/type :account
                   :first 1}
            cursor (get-in (eacl/lookup-resources client-a query)
                           [:page-info :end-cursor])
            history-operations (atom [])
            original-as-of d/as-of
            error
            (with-redefs [d/as-of
                          (fn [db t]
                            (swap! history-operations conj [db t])
                            (original-as-of db t))]
              (ex-data-of
               #(eacl/lookup-resources
                 client-b
                 (assoc query :after cursor))))]
        (is (string? cursor))
        (is (= :eacl.pagination/invalid-cursor (:type error)))
        (is (= :source-scope (:reason error)))
        (is (empty? @history-operations)
            "database identity is rejected before selecting the cursor basis")))))

(deftest forged-zed-token-is-rejected-before-revision-work-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          _ (seed! conn client)
          token (core/current-zed-token client)
          forged
          (str (subs token 0 (dec (count token)))
               (if (= \A (last token)) \B \A))
          operations (atom [])]
      (with-redefs [d/sync
                    (fn [& _]
                      (swap! operations conj :sync)
                      (throw (ex-info "must not synchronize" {})))
                    d/as-of
                    (fn [& _]
                      (swap! operations conj :as-of)
                      (throw (ex-info "must not time travel" {})))
                    cache/safe-entry-value
                    (fn [& _]
                      (swap! operations conj :cache)
                      (throw (ex-info "must not access cache" {})))]
        (try
          (eacl/can? client
                     (spice-object :user "alice")
                     :admin
                     (spice-object :account "acct")
                     (consistency/at-least-as-fresh forged))
          (is false "a modified frontend token must be rejected")
          (catch clojure.lang.ExceptionInfo e
            (is (= :eacl/invalid-zed-token
                   (:type (ex-data e))))))
        (is (empty? @operations))))))

(deftest zed-token-keyring-is-shared-and-rotatable-across-clients-test
  (with-mem-conn [conn schema/v7-schema]
    (let [old-key "old-stable-zed-token-key00000000"
          new-key "new-stable-zed-token-key00000000"
          old-client
          (core/make-client conn
                            {:source-lifecycle source-lifecycle
                             :security-keyring {:old old-key}
                             :security-kid :old})
          _ (seed! conn old-client)
          old-token (core/current-zed-token old-client)
          overlap-client
          (core/make-client conn
                            {:source-lifecycle source-lifecycle
                             :security-keyring {:old old-key
                                                 :new new-key}
                             :security-kid :new})
          new-token (core/current-zed-token overlap-client)
          new-only-client
          (core/make-client conn
                            {:source-lifecycle source-lifecycle
                             :security-keyring {:new new-key}
                             :security-kid :new})
          demand [(spice-object :user "alice")
                  :admin
                  (spice-object :account "acct")]]
      (is (true?
           (apply eacl/can? overlap-client
                  (concat demand
                          [(consistency/at-least-as-fresh
                            old-token)])))
          "a retained old key verifies during rotation")
      (is (true?
           (apply eacl/can? new-only-client
                  (concat demand
                          [(consistency/at-least-as-fresh
                            new-token)])))
          "a token signed by one client verifies on another shared-key client")
      (is (thrown?
           clojure.lang.ExceptionInfo
           (apply eacl/can? new-only-client
                  (concat demand
                          [(consistency/at-least-as-fresh
                            old-token)])))
          "retiring the old key invalidates old frontend tokens"))))

(deftest page-cursor-remains-on-its-historical-snapshot-test
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
      (is (= ["acct-2"]
             (mapv :id
                   (:data
                    (eacl/lookup-resources
                     client
                     (assoc query :after cursor))))))
      (let [new-page1 (eacl/lookup-resources client query)
            new-cursor (get-in new-page1 [:page-info :end-cursor])]
        (eacl/create-relationship!
         client
         (->Relationship alice :owner
                         (spice-object :account "acct-3")))
        (is (= ["acct-2"]
               (mapv :id
                     (:data
                      (eacl/lookup-resources
                       client
                       (assoc query :after new-cursor)))))
            "a relevant write does not alter the cursor's original page")))))

(deftest cache-disabled-cursor-survives-unrelated-and-relationship-writes-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client
                  conn
                  {:cache shared-cache/no-cache
                   :security-key "cache-disabled-cursor00000000000"})
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
      @(d/transact conn [{:eacl/id "unrelated"}])
      (eacl/create-relationship!
       client
       (->Relationship alice :owner
                       (spice-object :account "acct-3")))
      (is (= ["acct-2"]
             (mapv :id
                   (:data
                    (eacl/lookup-resources
                     client
                     (assoc query :after cursor)))))
          "cache disablement cannot turn exact cursor replay into an error"))))

(deftest cursor-identity-is-validated-before-historical-db-selection-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          alice (spice-object :user "alice")
          _ (seed! conn client)
          query {:subject alice
                 :permission :admin
                 :resource/type :account
                 :first 1}
          cursor (get-in (eacl/lookup-resources client query)
                         [:page-info :end-cursor])
          as-of-calls (atom 0)]
      (with-redefs [d/as-of
                    (fn [& _]
                      (swap! as-of-calls inc)
                      (throw (ex-info "historical selection must not run" {})))]
        (let [data
              (try
                (eacl/lookup-resources
                 client
                 (assoc query
                        :permission :other
                        :after cursor))
                nil
                (catch clojure.lang.ExceptionInfo error
                  (ex-data error)))]
          (is (= :eacl.pagination/invalid-cursor (:type data)))
          (is (= :query-mismatch (:reason data))))
        (is (zero? @as-of-calls))))))

(deftest read-relationships-cursor-survives-basis-advancement-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client
                  conn
                  {:cache shared-cache/no-cache
                   :security-key "read-relationships-history000000"})
          alice (spice-object :user "alice")
          _ (eacl/write-schema! client direct-schema)
          _ @(d/transact conn [{:eacl/id "alice"}
                               {:eacl/id "acct-1"}
                               {:eacl/id "acct-2"}])
          _ (eacl/create-relationships!
             client
             [(->Relationship alice :owner
                              (spice-object :account "acct-1"))
              (->Relationship alice :owner
                              (spice-object :account "acct-2"))])
          query {:resource/type :account}
          all-data (:data (eacl/read-relationships
                           client (assoc query :first 10)))
          page1 (eacl/read-relationships client (assoc query :first 1))
          cursor (get-in page1 [:page-info :end-cursor])]
      @(d/transact conn [{:eacl/id "unrelated-read-relationships"}])
      (is (= [(second all-data)]
             (:data
              (eacl/read-relationships
               client
               (assoc query :first 1 :after cursor))))))))

(deftest at-least-as-fresh-targets-only-the-requested-revision-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          _ (seed! conn client)
          future-t (inc (d/basis-t (d/db conn)))
          future-token (token-with-order-hint client future-t)
          sync-calls (atom [])]
      (with-redefs [d/sync
                    (fn [_conn t]
                      (swap! sync-calls conj t)
                      (future (d/db conn)))]
        (try
          (eacl/can? client
                     (spice-object :user "alice")
                     :admin
                     (spice-object :account "acct")
                     (consistency/at-least-as-fresh future-token))
          (is false "the Peer did not actually reach the future revision")
          (catch clojure.lang.ExceptionInfo e
            (is (= :eacl.consistency/freshness-unavailable
                   (:type (ex-data e))))
            (is (= :head-behind (:reason (ex-data e))))
            (is (= future-t
                   (:requested-order-hint (ex-data e)))))))
      (is (= [future-t] @sync-calls)
          "EACL waits for the caller's exact lower bound, not transactor head"))))

(deftest at-least-as-fresh-bounds-a-targeted-sync-wait-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (assoc-in (cached-client conn)
                           [:runtime :consistency-sync-timeout-ms]
                           1)
          _ (seed! conn client)
          future-t (inc (d/basis-t (d/db conn)))
          token (token-with-order-hint client future-t)]
      (with-redefs [d/sync
                    (fn [_conn _t]
                      (future
                        (Thread/sleep 1000)
                        (d/db conn)))]
        (try
          (eacl/can? client
                     (spice-object :user "alice")
                     :admin
                     (spice-object :account "acct")
                     (consistency/at-least-as-fresh token))
          (is false "a timeout cannot fall back to an older answer")
          (catch clojure.lang.ExceptionInfo e
            (is (= :eacl.consistency/freshness-unavailable
                   (:type (ex-data e))))
            (is (= :freshness-timeout (:reason (ex-data e))))
            (is (= future-t
                   (:requested-order-hint (ex-data e))))
            (is (= 1 (:timeout-ms (ex-data e))))))))))

(deftest at-least-as-fresh-normalizes-targeted-sync-failures-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          _ (seed! conn client)
          future-t (inc (d/basis-t (d/db conn)))
          token (token-with-order-hint client future-t)]
      (with-redefs [d/sync
                    (fn [_conn _t]
                      (throw (ex-info "peer unavailable" {})))]
        (try
          (eacl/can? client
                     (spice-object :user "alice")
                     :admin
                     (spice-object :account "acct")
                     (consistency/at-least-as-fresh token))
          (is false "a sync failure cannot escape or use an older DB")
          (catch clojure.lang.ExceptionInfo e
            (is (= :eacl.consistency/freshness-unavailable
                   (:type (ex-data e))))
            (is (= :sync-failed (:reason (ex-data e))))
            (is (= future-t
                   (:requested-order-hint (ex-data e))))))))))

(deftest exact-request-reuses-only-the-matching-snapshot-cache-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client
                  conn
                  {:cache {}})
          {token :zed/token} (seed! conn client)
          alice (spice-object :user "alice")
          account (spice-object :account "acct")
          demand {:subject alice
                  :permission :admin
                  :resource account}]
      (is (true? (eacl/can? client alice :admin account)))
      (let [exact-demand
            (assoc demand
                   :consistency
                   (consistency/at-exact-snapshot token))
            first-result (eacl/check-permission client exact-demand)
            second-result (eacl/check-permission client exact-demand)]
        (is (true? (:allowed? first-result)))
        (is (true? (:allowed? second-result)))
        (is (true? (:cached? second-result))
            "exact selection reuses the answer computed at the identical basis")))))

;; --- 2026-08-16 exact-cache review ------------------------------------------

(def ^:private relation-root-schema
  "definition user {}
   definition document {
     relation viewer: user
     permission view = viewer
   }")

(defn- leaf-subject-ids
  [tree]
  (let [ids (atom #{})]
    (clojure.walk/postwalk
     (fn [node]
       (when (and (map? node) (contains? node :subjects))
         (swap! ids into (map :id (:subjects node))))
       node)
     tree)
    @ids))

(deftest relation-root-tree-expansion-observes-relationship-writes-test
  ;; A relation root reads that relation's relationships, but no permission
  ;; path names it. When the dependency closure missed it, the managed
  ;; cross-snapshot tier proved a stale tree equal at every later snapshot.
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          alice (spice-object :user "alice")
          bob (spice-object :user "bob")
          document (spice-object :document "doc1")]
      (eacl/write-schema! client relation-root-schema)
      @(d/transact conn [{:eacl/id "alice"}
                         {:eacl/id "bob"}
                         {:eacl/id "doc1"}])
      (eacl/create-relationship! client (->Relationship alice :viewer document))
      (doseq [root [:viewer :view]]
        (testing (str "root " root)
          (let [before (eacl/expand-permission-tree
                        client {:resource document :permission root})]
            (is (= #{"alice"} (leaf-subject-ids (:tree-root before))))
            (eacl/create-relationship!
             client (->Relationship bob :viewer document))
            (let [after (eacl/expand-permission-tree
                         client {:resource document :permission root})]
              (is (= #{"alice" "bob"} (leaf-subject-ids (:tree-root after)))
                  "a cached tree must not survive a write it reports")
              (eacl/delete-relationship!
               client (->Relationship bob :viewer document)))))))))

(deftest non-deterministic-clients-use-exact-basis-but-not-managed-lifting-test
  ;; A runtime-unique adapter fingerprint makes exact reuse safe inside one
  ;; client. It cannot certify semantic equivalence across different bases,
  ;; so proof-backed lifting remains disabled.
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client
                  conn
                  {:security-key "consistency-cache-test-key000000"
                   :source-lifecycle source-lifecycle
                   :object-id->lookup-ref (fn [id] [:eacl/id id])
                   :cache {}})
          alice (spice-object :user "alice")
          account (spice-object :account "account")
          demand {:subject alice :permission :admin :resource account}]
      (eacl/write-schema! client direct-schema)
      @(d/transact conn [{:eacl/id "alice"} {:eacl/id "account"}])
      (eacl/create-relationship! client (->Relationship alice :owner account))
      (let [selected (source/acquire! (:source client) :current)]
        (try
          (is (false? (backend/deterministic? (source/adapter selected)))
              "a custom id codec without a fingerprint is not deterministic")
          (finally
            (source/release! selected))))
      (let [before (core/cache-stats client)
            first-result (eacl/check-permission client demand)
            second-result (eacl/check-permission client demand)
            same-basis (core/cache-stats client)]
        (is (false? (:cached? first-result)))
        (is (true? (:cached? second-result))
            "the runtime-unique fingerprint safely admits same-basis reuse")
        (is (= (inc (:exact-hits before)) (:exact-hits same-basis)))
        @(d/transact conn [{:eacl/id "unrelated"}])
        (let [next-basis-result (eacl/check-permission client demand)
              next-basis (core/cache-stats client)]
          (is (false? (:cached? next-basis-result))
              "an uncertified codec cannot lift an answer across bases")
          (is (= (:managed-hits same-basis) (:managed-hits next-basis))))))))

(deftest filtered-datomic-views-cannot-claim-exact-consistency-test
  ;; d/filter, d/since and d/history report their origin's database id and
  ;; basis, so an exact identity minted from one is indistinguishable from the
  ;; plain value at the same basis while answering a different question.
  (with-mem-conn [conn schema/v7-schema]
    (let [db (d/db conn)
          adapter-for (fn [value]
                        (datomic-backend/basis-adapter
                         value {:adapter-fingerprint {:test :fingerprint}
                                :adapter-deterministic? true}))
          kind #(backend/invoke (adapter-for %) :basis-kind)]
      (is (= :ordinary (kind db)))
      (is (= :as-of (kind (d/as-of db (d/basis-t db)))))
      (is (= :since (kind (d/since db 0))))
      (is (= :history (kind (d/history db))))
      (is (= :filtered (kind (d/filter db (fn [_ _] true)))))
      (is (backend/admissible-basis-kind? (kind db)))
      (is (not (backend/admissible-basis-kind?
                (kind (d/since db 0))))))))

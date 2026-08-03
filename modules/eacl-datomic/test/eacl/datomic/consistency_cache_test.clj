(ns eacl.datomic.consistency-cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.cache :as shared-cache]
            [eacl.causal-token :as causal-token]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.cache :as cache]
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
  (core/make-client
   conn
   {:coherence-authority :managed
    :zed-token-key "consistency-cache-test-key"
    :cache {:checkpoints true
            :remember-answers true}}))

(defn- token-payload
  [client token]
  (causal-token/token-data
   (get-in client [:opts :format-options])
   token))

(defn- token-with-order-hint
  [client order-hint]
  (let [format-options (get-in client [:opts :format-options])
        payload
        (token-payload client (core/current-zed-token client))]
    (causal-token/issue
     format-options
     (assoc payload
            :order-hint order-hint
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
            (is (= 3 @calls)))

          (testing "fully-consistent observes the relationship deletion"
            (is (false? (eacl/can? client alice :admin account)))
            (is (= 3 @calls)
                "the exact-current deletion result is reused"))

          (testing "at-least-as-fresh accepts the current cached revision"
            (is (false? (eacl/can? client alice :admin account
                                   (consistency/at-least-as-fresh
                                    deleted-token))))
            (is (= 3 @calls)))

          (testing "and repeated exact selection remains snapshot-correct"
            (is (true? (eacl/can? client alice :admin account
                                  (consistency/at-exact-snapshot
                                   created-token))))
            (is (= 4 @calls)
                "exact requests bypass completed-answer caching")))))))

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
            cursor (get-in exact-page [:page-info :end-cursor])
            decoded (core/token->page-bound
                     (get client :opts)
                     cursor)
            opts (get client :opts)]
        (is (= ["acct"] (mapv :id (:data exact-page))))
        (is (= (:exact-locator
                (token-payload client created-token))
               (:basis-t decoded))
            "a cursor names the exact snapshot that produced its page"))
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
           {:coherence-authority :managed
            :cache {:remember-answers true}})
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

  ;; Native current answers are client-private and admitted immediately.
  ;; Exact replay remains cache-free.
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client
                  conn
                  {:coherence-authority :managed})
          alice (spice-object :user "alice")
          account (spice-object :account "acct")
          {created-token :zed/token} (seed! conn client)]
      (is (true? (eacl/can? client alice :admin account)))
      (is (pos?
           (:exact-entries
            (shared-cache/current-cache-stats
             (get-in client [:opts :current-cache-store]))))
          "the first current answer is retained in the private generation")
      (is (true? (eacl/can? client alice :admin account)))
      (is (pos?
           (:exact-hits
            (shared-cache/current-cache-stats
             (get-in client [:opts :current-cache-store]))))
          "the second sighting is an exact-current hit")
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

(deftest zed-token-helpers-never-force-sync-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (cached-client conn)
          _ (seed! conn client)
          [current-token age-token]
          (with-redefs [d/sync
                        (fn [& _]
                          (throw (ex-info "token helpers must not sync" {})))]
            [(core/current-zed-token client)
             (core/zed-token-at-least-seconds-ago client 30)])
          current-payload (token-payload client current-token)
          age-payload (token-payload client age-token)]
      (is (integer? (:order-hint current-payload)))
      (is (integer? (:order-hint age-payload)))
      (is (= (:source-id current-payload)
             (:source-id age-payload)))
      (is (true?
           (eacl/can? client
                      (spice-object :user "alice")
                      :admin
                      (spice-object :account "acct")
                      (consistency/at-least-as-fresh age-token)))))))

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
      (let [token-key "shared-page-token-key"
            client-a (core/make-client conn-a {:page-token-key token-key})
            client-b (core/make-client conn-b {:page-token-key token-key})
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
        (is (= :database-mismatch (:reason error)))
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
    (let [old-key "old-stable-zed-token-key"
          new-key "new-stable-zed-token-key"
          old-client
          (core/make-client conn
                            {:coherence-authority :managed
                             :zed-token-keyring {:old old-key}
                             :zed-token-kid :old})
          _ (seed! conn old-client)
          old-token (core/current-zed-token old-client)
          overlap-client
          (core/make-client conn
                            {:coherence-authority :managed
                             :zed-token-keyring {:old old-key
                                                 :new new-key}
                             :zed-token-kid :new})
          new-token (core/current-zed-token overlap-client)
          new-only-client
          (core/make-client conn
                            {:coherence-authority :managed
                             :zed-token-keyring {:new new-key}
                             :zed-token-kid :new})
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
                  {:cache cache/no-cache
                   :page-token-key "cache-disabled-cursor"})
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
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Page token does not match"
             (eacl/lookup-resources
              client
              (assoc query
                     :permission :other
                     :after cursor))))
        (is (zero? @as-of-calls))))))

(deftest read-relationships-cursor-survives-basis-advancement-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client
                  conn
                  {:cache cache/no-cache
                   :page-token-key "read-relationships-history"})
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
                           [:opts :consistency-sync-timeout-ms]
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

(deftest exact-request-bypasses-completed-provider-test
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
          client (core/make-client
                  conn
                  {:coherence-authority :managed
                   :cache {:store store
                           :remember-answers true}})
          {token :zed/token} (seed! conn client)
          alice (spice-object :user "alice")
          account (spice-object :account "acct")]
      (is (true? (eacl/can? client alice :admin account)))
      (reset! fail? true)
      (is (true?
           (eacl/can? client alice :admin account
                      (consistency/at-exact-snapshot token))))
      (is (zero? (:provider-errors (cache/stats delegate)))
          "exact selection does not consult the completed-answer provider"))))

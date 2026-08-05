(ns eacl.datomic.lookup-cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.cache :as shared-cache]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn
                                                  with-mem-conns]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.schema :as schema]
            [eacl.engine.v8 :as engine]))

(def ^:private direct-schema
  "definition user {}
   definition account {
     relation owner: user
     relation auditor: user
     permission admin = owner
   }")

(def ^:private arrow-schema
  "definition user {}
   definition account {
     relation owner: user
     relation auditor: user
     permission admin = owner
   }
   definition server {
     relation account: account
     permission view = account->admin
   }")

(def ^:private recursive-schema
  "definition user {}
   definition folder {
     relation parent: folder
     relation reader: user
     permission read = reader + parent->read
   }")

(defn- seed-direct!
  [conn client]
  (eacl/write-schema! client direct-schema)
  @(d/transact conn [{:eacl/id "alice"}
                     {:eacl/id "bob"}
                     {:eacl/id "a-1"}
                     {:eacl/id "a-2"}])
  (eacl/create-relationship!
   client
   (->Relationship (spice-object :user "alice")
                   :owner
                   (spice-object :account "a-1"))))

(defn- live-cache-context
  "A client that memoizes completed results. This used to need an explicit
  coordinator plus :live-results? true; per-relation stamps made the
  coordinator unnecessary, so it is now just :remember-answers."
  []
  {:remember-answers true})

(deftest live-non-recursive-pages-survive-unrelated-transactions-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:coherence-authority :managed
                                         :cache (live-cache-context)})
          forward-query {:subject (spice-object :user "alice")
                         :permission :admin
                         :resource/type :account}
          reverse-query {:resource (spice-object :account "a-1")
                         :permission :admin
                         :subject/type :user}
          forward-calls (atom 0)
          reverse-calls (atom 0)
          original-forward impl/lookup-resources
          original-reverse impl/lookup-subjects]
      (seed-direct! conn client)
      (with-redefs [impl/lookup-resources
                    (fn [db query continuation-context]
                      (swap! forward-calls inc)
                      (original-forward db query continuation-context))
                    impl/lookup-subjects
                    (fn [db query continuation-context]
                      (swap! reverse-calls inc)
                      (original-reverse db query continuation-context))]
        (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources client forward-query)))))
        (is (= ["alice"] (mapv :id (:data (eacl/lookup-subjects client reverse-query)))))

        (dotimes [n 10]
          @(d/transact conn [{:eacl/id (str "unrelated-" n)}]))

        (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources client forward-query)))))
        (is (= ["alice"] (mapv :id (:data (eacl/lookup-subjects client reverse-query)))))
        (is (= 1 @forward-calls) "basis changes do not invalidate EACL results")
        (is (= 1 @reverse-calls) "reverse lookup uses the same logical generation")))))

(deftest relationship-write-invalidates-live-pages-but-no-op-does-not-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:coherence-authority :managed
                                         :cache (live-cache-context)})
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account}
          calls (atom 0)
          original impl/lookup-resources
          second-rel (->Relationship (spice-object :user "alice")
                                     :owner
                                     (spice-object :account "a-2"))]
      (seed-direct! conn client)
      (with-redefs [impl/lookup-resources
                    (fn [db internal-query continuation-context]
                      (swap! calls inc)
                      (original db internal-query continuation-context))]
        (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources client query)))))
        (is (= 1 @calls))

        (eacl/create-relationship!
         client
         (->Relationship (spice-object :user "bob")
                         :auditor
                         (spice-object :account "a-2")))
        (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources client query)))))
        (is (= 1 @calls)
            "a relationship outside the permission dependency set keeps the page hot")

        (eacl/create-relationship! client second-rel)
        (is (= #{"a-1" "a-2"}
               (set (map :id (:data (eacl/lookup-resources client query))))))
        (is (= 2 @calls) "an actual relationship write selects a new generation")

        (eacl/write-relationship!
         client
         {:operation :touch
          :subject (:subject second-rel)
          :relation (:relation second-rel)
          :resource (:resource second-rel)})
        (is (= #{"a-1" "a-2"}
               (set (map :id (:data (eacl/lookup-resources client query))))))
        (is (= 2 @calls) "a relationship no-op keeps the hot generation")))))

(deftest direct-relationship-writes-are-observed-test
  ;; This test used to assert the OPPOSITE: a raw d/transact of
  ;; tx-relationship output was deliberately not observed, because the
  ;; relationship coordinator only saw writes made through a client sharing it.
  ;; tx-relationship's returned tx-data now carries the :eacl/relation-version
  ;; stamp, so such a caller publishes the change without knowing the cache
  ;; exists, and no explicit eviction is needed.
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:coherence-authority :managed
                                         :cache (live-cache-context)})
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account}
          relationship
          (->Relationship (spice-object :user "alice")
                          :owner
                          (spice-object :account "a-2"))]
      (seed-direct! conn client)
      (is (= ["a-1"]
             (mapv :id (:data (eacl/lookup-resources client query)))))
      @(d/transact conn
                   (impl/tx-relationship (d/db conn) relationship))
      (is (= #{"a-1" "a-2"}
             (set (map :id (:data (eacl/lookup-resources client query)))))
          "a relationship written outside the EACL client takes effect"))))

(deftest live-page-dependencies-include-arrow-relations-and-target-permissions-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:coherence-authority :managed
                                         :cache (live-cache-context)})
          alice (spice-object :user "alice")
          bob (spice-object :user "bob")
          account (spice-object :account "account")
          server (spice-object :server "server")
          owner-rel (->Relationship alice :owner account)
          account-rel (->Relationship account :account server)
          query {:subject alice
                 :permission :view
                 :resource/type :server}
          calls (atom 0)
          original impl/lookup-resources]
      (eacl/write-schema! client arrow-schema)
      @(d/transact conn [{:eacl/id "alice"}
                         {:eacl/id "bob"}
                         {:eacl/id "account"}
                         {:eacl/id "server"}])
      (eacl/create-relationships! client [owner-rel account-rel])
      (with-redefs [impl/lookup-resources
                    (fn [db internal-query continuation-context]
                      (swap! calls inc)
                      (original db internal-query continuation-context))]
        (is (= ["server"]
               (mapv :id (:data (eacl/lookup-resources client query)))))
        (eacl/create-relationship!
         client
         (->Relationship bob :auditor account))
        (is (= ["server"]
               (mapv :id (:data (eacl/lookup-resources client query)))))
        (is (= 1 @calls)
            "an unrelated relation leaves the arrow permission page hot")

        (eacl/delete-relationship! client owner-rel)
        (is (empty? (:data (eacl/lookup-resources client query))))
        (is (= 2 @calls)
            "a target-permission relation invalidates the arrow lookup")

        (eacl/create-relationship! client owner-rel)
        (is (= ["server"]
               (mapv :id (:data (eacl/lookup-resources client query)))))
        (eacl/delete-relationship! client account-rel)
        (is (empty? (:data (eacl/lookup-resources client query))))
        (is (= 4 @calls)
            "the arrow's source relation is also a dependency")))))

(deftest cached-pages-store-eids-and-reapply-current-id-coercion-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:coherence-authority :managed
                                         :cache (live-cache-context)})
          original impl/lookup-resources
          calls (atom 0)]
      (seed-direct! conn client)
      (with-redefs [impl/lookup-resources
                    (fn [db internal-query continuation-context]
                      (swap! calls inc)
                      (original db internal-query continuation-context))]
        (is (= ["a-1"]
               (mapv :id
                     (:data
                      (eacl/lookup-resources
                       client
                       {:subject (spice-object :user "alice")
                        :permission :admin
                        :resource/type :account})))))
        @(d/transact conn [{:db/id [:eacl/id "a-1"]
                            :eacl/id "renamed-account"}
                           {:db/id [:eacl/id "alice"]
                            :eacl/id "renamed-alice"}])
        (is (= ["renamed-account"]
               (mapv :id
                     (:data
                      (eacl/lookup-resources
                       client
                       {:subject (spice-object :user "renamed-alice")
                        :permission :admin
                        :resource/type :account})))))
        (is (= 2 @calls)
            "a changed public identity binding cannot reuse the old query key")))))

(deftest recreated-external-id-does-not-reuse-the-retracted-entity-cache-key-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:coherence-authority :managed
                                         :cache (live-cache-context)})
          alice (spice-object :user "alice")
          account (spice-object :account "a-1")
          relationship (->Relationship alice :owner account)
          query {:subject alice
                 :permission :admin
                 :resource/type :account}]
      (seed-direct! conn client)
      (is (true? (eacl/can? client alice :admin account)))
      (is (= ["a-1"]
             (mapv :id (:data (eacl/lookup-resources client query)))))
      (let [old-eid (d/entid (d/db conn) [:eacl/id "a-1"])]
        (eacl/delete-relationship! client relationship)
        @(d/transact conn [[:db.fn/retractEntity old-eid]])
        @(d/transact conn [{:eacl/id "a-1"}])
        (is (not= old-eid
                  (d/entid (d/db conn) [:eacl/id "a-1"])))
        (is (false? (eacl/can? client alice :admin account)))
        (is (empty? (:data (eacl/lookup-resources client query)))
            "the recreated external ID resolves to a new internal cache key")))))

(deftest disabled-and-failing-caches-use-the-indexed-path-test
  (with-mem-conn [conn schema/v7-schema]
    (let [disabled (core/make-client conn {:cache cache/no-cache})
          broken-store
          (reify cache/CacheStore
            (lookup [_ _] (throw (ex-info "unavailable" {})))
            (store! [_ _ _ _ _] (throw (ex-info "unavailable" {})))
            (evict! [_ _] nil)
            (clear! [_] nil)
            (stats [_] {}))
          failing (core/make-client
                   conn
                   {:cache {:store broken-store}})
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account}]
      (seed-direct! conn disabled)
      (testing "both clients return the same correct data"
        (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources disabled query)))))
        (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources failing query)))))))))

(deftest malformed-value-under-correct-cache-key-is-a-miss-test
  (with-mem-conn [conn schema/v7-schema]
    (let [values (atom {})
          corrupting-store
          (reify cache/CacheStore
            (lookup [_ key] (get @values key))
            (store! [_ key wrapped _weight _ttl]
              (swap! values
                     assoc
                     key
                     (assoc wrapped
                            :eacl.cache/value
                            {:data []
                             :page-info {:start-cursor nil
                                         :end-cursor nil
                                         :has-next-page? true
                                         :has-previous-page? false}}))
              true)
            (evict! [_ key] (some? (get (swap! values dissoc key) key)))
            (clear! [_] (reset! values {}))
            (stats [_] {:entries (count @values)}))
          client
          (core/make-client
           conn
           {:cache {:store corrupting-store}})
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account}
          calls (atom 0)
          original impl/lookup-resources]
      (seed-direct! conn client)
      (with-redefs [impl/lookup-resources
                    (fn [db internal-query continuation-context]
                      (swap! calls inc)
                      (original db internal-query continuation-context))]
        (is (= ["a-1"]
               (mapv :id (:data (eacl/lookup-resources client query)))))
        (is (= ["a-1"]
               (mapv :id (:data (eacl/lookup-resources client query)))))
        (is (= 1 @calls)
            "an untrusted provider cannot influence the private answer cache")))))

(deftest live-counts-share-dependency-aware-result-cache-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:coherence-authority :managed
                                         :cache (live-cache-context)})
          forward-query {:subject (spice-object :user "alice")
                         :permission :admin
                         :resource/type :account}
          reverse-query {:resource (spice-object :account "a-1")
                         :permission :admin
                         :subject/type :user}
          forward-calls (atom 0)
          reverse-calls (atom 0)
          original-forward impl/count-resources
          original-reverse impl/count-subjects]
      (seed-direct! conn client)
      (with-redefs [impl/count-resources
                    (fn [db query]
                      (swap! forward-calls inc)
                      (original-forward db query))
                    impl/count-subjects
                    (fn [db query]
                      (swap! reverse-calls inc)
                      (original-reverse db query))]
        (is (= 1 (:count (eacl/count-resources client forward-query))))
        (is (= 1 (:count (eacl/count-subjects client reverse-query))))

        @(d/transact conn [{:eacl/id "unrelated-count-data"}])
        (eacl/create-relationship!
         client
         (->Relationship (spice-object :user "bob")
                         :auditor
                         (spice-object :account "a-2")))
        (is (= 1 (:count (eacl/count-resources client forward-query))))
        (is (= 1 (:count (eacl/count-subjects client reverse-query))))
        (is (= 1 @forward-calls))
        (is (= 1 @reverse-calls))

        (eacl/create-relationship!
         client
         (->Relationship (spice-object :user "alice")
                         :owner
                         (spice-object :account "a-2")))
        (is (= 2 (:count (eacl/count-resources client forward-query))))
        (is (= 1 (:count (eacl/count-subjects client reverse-query))))
        (is (= 2 @forward-calls))
        (is (= 2 @reverse-calls)
            "a relevant relation epoch invalidates both count directions")))))

(deftest completed-recursive-page-hit-skips-engine-classification-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:coherence-authority :managed
                                         :cache (live-cache-context)})
          alice (spice-object :user "alice")
          root (spice-object :folder "root")
          child (spice-object :folder "child")
          query {:subject alice
                 :permission :read
                 :resource/type :folder
                 :first 10}
          classifications (atom 0)
          classify engine/traversal-permission?]
      (eacl/write-schema! client recursive-schema)
      @(d/transact conn [{:eacl/id "alice"}
                         {:eacl/id "root"}
                         {:eacl/id "child"}])
      (eacl/create-relationships!
       client
       [(->Relationship alice :reader root)
        (->Relationship root :parent child)])
      (with-redefs [engine/traversal-permission?
                    (fn [snapshot resource-type permission]
                      (swap! classifications inc)
                      (classify snapshot resource-type permission))]
        (is (= #{"root" "child"}
               (set (map :id
                         (:data
                          (eacl/lookup-resources client query))))))
        (let [cold-classifications @classifications]
          (is (= #{"root" "child"}
                 (set (map :id
                           (:data
                            (eacl/lookup-resources client query))))))
          (is (= cold-classifications @classifications)
              "the uniform completed-page hit precedes any additional traversal selection"))))))

(deftest recursive-cursors-replay-across-independent-client-proofs-test
  (with-mem-conn [conn schema/v7-schema]
    (let [store (cache/local-store)
          token-key "shared-store-opaque-continuation"
          first-client (core/make-client conn {:cache {:store store
                                                       :remember-answers false}
                                               :page-token-key token-key})
          alice (spice-object :user "alice")
          root (spice-object :folder "root")
          child (spice-object :folder "child")
          query {:subject alice
                 :permission :read
                 :resource/type :folder
                 :first 1}]
      (eacl/write-schema! first-client recursive-schema)
      @(d/transact conn [{:eacl/id "alice"}
                         {:eacl/id "root"}
                         {:eacl/id "child"}])
      (eacl/create-relationships!
       first-client
       [(->Relationship alice :reader root)
        (->Relationship root :parent child)])
      ;; :remember-answers false forces deterministic replay. The old recursive
      ;; page side cache was process-local and unauthenticated; v3 refuses to
      ;; read it until continuation state uses the proof envelope contract.
      (let [second-client (core/make-client conn {:cache {:store store
                                                          :remember-answers false}
                                                  :page-token-key token-key})
            first-page (eacl/lookup-resources first-client query)
            cursor (get-in first-page [:page-info :end-cursor])
            expected-page (eacl/lookup-resources first-client
                                                 (assoc query :after cursor))
            stats (atom {})]
        (is (= (:data expected-page)
               (:data
                (binding [idx/*recursive-traversal-stats* stats]
                  (eacl/lookup-resources second-client
                                         (assoc query :after cursor)))))
            "another client replays the cursor against its authenticated snapshot")
        (is (nil? (:recursive-page-hits @stats))
            "no unauthenticated recursive page is reused across clients")))))

(deftest recursive-cursors-resume-private-continuations-within-client-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client
                  conn
                  {:cache {:remember-answers false}
                   :page-token-key "private-continuation"})
          alice (spice-object :user "alice")
          root (spice-object :folder "root")
          child (spice-object :folder "child")
          grandchild (spice-object :folder "grandchild")
          query {:subject alice
                 :permission :read
                 :resource/type :folder
                 :first 1}]
      (eacl/write-schema! client recursive-schema)
      @(d/transact conn [{:eacl/id "alice"}
                         {:eacl/id "root"}
                         {:eacl/id "child"}
                         {:eacl/id "grandchild"}])
      (eacl/create-relationships!
       client
       [(->Relationship alice :reader root)
        (->Relationship root :parent child)
        (->Relationship child :parent grandchild)])
      (let [first-page (eacl/lookup-resources client query)
            stats (atom {})
            second-page
            (binding [idx/*recursive-traversal-stats* stats]
              (eacl/lookup-resources
               client
               (assoc query
                      :after
                      (get-in first-page [:page-info :end-cursor]))))]
        (is (= ["root"] (mapv :id (:data first-page))))
        (is (= ["child"] (mapv :id (:data second-page))))
        (is (= 1 (:continuation-hits @stats)))
        (is (nil? (:continuation-misses @stats))))
      (testing "rejected private-cache admission deterministically replays"
        (let [bounded-client
              (core/make-client
               conn
               {:cache {:remember-answers false
                        :max-weight 1024
                        :max-entry-weight 512}
                :page-token-key "rejected-private-continuation"})
              first-page (eacl/lookup-resources bounded-client query)
              stats (atom {})
              second-page
              (binding [idx/*recursive-traversal-stats* stats]
                (eacl/lookup-resources
                 bounded-client
                 (assoc query
                        :after
                        (get-in first-page [:page-info :end-cursor]))))]
          (is (= ["child"] (mapv :id (:data second-page))))
          (is (nil? (:continuation-hits @stats)))
          (is (= 1 (:continuation-misses @stats))))))))

(deftest long-count-does-not-hold-relationship-writer-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:coherence-authority :managed
                                         :cache (live-cache-context)})
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account}
          entered-count (promise)
          release-count (promise)
          original-count impl/count-resources]
      (seed-direct! conn client)
      (with-redefs [impl/count-resources
                    (fn [db internal-query]
                      (deliver entered-count true)
                      @release-count
                      (original-count db internal-query))]
        (let [count-future (future (eacl/count-resources client query))]
          (is (= true (deref entered-count 1000 ::timeout)))
          (let [write-future
                (future
                  (eacl/create-relationship!
                   client
                   (->Relationship (spice-object :user "alice")
                                   :owner
                                   (spice-object :account "a-2"))))]
            (try
              (is (not= ::blocked (deref write-future 1000 ::blocked))
                  "the read barrier ends before expensive count computation")
              (finally
                (deliver release-count true)))
            (is (= 1 (:count @count-future))
                "the in-flight count remains correct for its captured snapshot")
            (is (= 2 (:count (eacl/count-resources client query)))
                "the post-write dependency epoch selects the new snapshot")))))))

(deftest cache-config-is-validated-test
  (with-mem-conn [conn schema/v7-schema]
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/make-client conn {:cache :yes})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/make-client conn {:cache {:unknown true}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/make-client conn {:cache {:ttl-ms 0}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/make-client conn {:cache {:remember-answers :yes}})))
    (testing ":on-repeat is accepted"
      (is (some? (core/make-client
                  conn {:cache {:remember-answers :on-repeat}}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/make-client conn {:cache {:namespace ""}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/make-client conn {:cache {:checkpoints :yes}})))
    (testing "a cache adapter is accepted directly"
      (is (some? (core/make-client
                  conn {:cache (cache/local-store {:max-entries 8})}))))
    (testing "whatever :cache holds must BE a cache"
      (let [store-of #(:lookup-cache-store (:opts (core/make-client conn %)))]
        (testing "nil and absent both mean the default adapter"
          (is (some? (store-of {})))
          (is (some? (store-of {:cache nil}))))
        (testing "no-cache is the only way to turn it off"
          (is (nil? (store-of {:cache cache/no-cache})))
          (is (nil? (store-of {:cache {:store cache/no-cache}}))))
        (testing "an explicit adapter is used as given"
          (let [adapter (cache/local-store)]
            (is (identical? adapter (store-of {:cache adapter})))))
        (testing "booleans are rejected rather than interpreted"
          ;; They read as a flag in a slot that holds a cache, and they left
          ;; nil ambiguous between "the default" and "none".
          (is (thrown? clojure.lang.ExceptionInfo (store-of {:cache false})))
          (is (thrown? clojure.lang.ExceptionInfo (store-of {:cache true})))
          (is (thrown? clojure.lang.ExceptionInfo
                       (store-of {:cache {:store false}}))))))
    (testing ":live-results? and :coordinator are gone, not ignored"
      (is (thrown? clojure.lang.ExceptionInfo
                   (core/make-client conn {:cache {:live-results? true}})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (core/make-client
                    conn
                    {:cache {:coordinator :anything}}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/make-client
                  conn
                  {:consistency-sync-timeout-ms 0})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/make-client
                  conn
                  {:zed-token-key "one"
                   :zed-token-keyring {:current "two"}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/make-client
                  conn
                  {:zed-token-keyring {:old "old"}
                   :zed-token-kid :missing})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/make-client
                  conn
                  {:zed-token-keyring {:current 42}})))
    (doseq [config [{:consistency-sync-timeout-ms nil}
                    {:zed-token-key nil}
                    {:zed-token-key false}
                    {:zed-token-key ""}
                    {:zed-token-key (byte-array 0)}
                    {:zed-token-keyring nil}
                    {:zed-token-keyring []}
                    {:zed-token-keyring {}}
                    {:zed-token-keyring {"" "key"}
                     :zed-token-kid ""}
                    {:zed-token-keyring {:current "key"}
                     :zed-token-kid nil}
                    {:zed-token-keyring {:current "key"}
                     :zed-token-kid []}]]
      (try
        (core/make-client conn config)
        (is false (str "expected invalid config: " (pr-str config)))
        (catch clojure.lang.ExceptionInfo e
          (is (= :eacl/invalid-config (:type (ex-data e)))))))))

;; --- per-request cache override ----------------------------------------------

(deftest disabled-cache-skips-native-cache-strategy-test
  (with-mem-conn [conn schema/v7-schema]
    (let [enabled (core/make-client conn {:cache {:remember-answers true}})
          _ (seed-direct! conn enabled)
          disabled (core/make-client conn {:cache cache/no-cache})
          demand {:subject (spice-object :user "alice")
                  :permission :admin
                  :resource (spice-object :account "a-1")}]
      (with-redefs [shared-cache/resolve-current!
                    (fn [& _]
                      (throw
                       (ex-info "cache resolution must be unreachable" {})))]
        (is (true? (eacl/can? disabled demand))
            "globally disabled clients evaluate directly")
        (is (true? (eacl/can? enabled (assoc demand :cache? false)))
            "per-request bypass evaluates directly")))))

(deftest per-request-cache-flag-bypasses-the-cache-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:cache {:remember-answers true}})
          _ (seed-direct! conn client)
          alice (spice-object :user "alice")
          account (spice-object :account "a-1")
          query {:subject alice :permission :admin :resource/type :account}
          calls (atom 0)
          original impl/can?
          lookups (atom 0)
          original-lookup impl/lookup-resources]
      (with-redefs [impl/can? (fn [db s p r]
                                (swap! calls inc)
                                (original db s p r))
                    impl/lookup-resources
                    (fn [db q cc]
                      (swap! lookups inc)
                      (original-lookup db q cc))]
        (testing "can? map arity"
          (is (true? (eacl/can? client {:subject alice
                                        :permission :admin
                                        :resource account})))
          (is (= 1 @calls))
          (is (true? (eacl/can? client {:subject alice
                                        :permission :admin
                                        :resource account})))
          (is (= 1 @calls) "second identical call is a hit")

          (is (true? (eacl/can? client {:subject alice
                                        :permission :admin
                                        :resource account
                                        :cache? false})))
          (is (= 2 @calls) ":cache? false does not read the cached answer"))

        (testing "the bypassed call also wrote nothing"
          (is (true? (eacl/can? client {:subject alice
                                        :permission :admin
                                        :resource account})))
          (is (= 2 @calls)
              "the earlier cached entry is still the one being served"))

        (testing "lookups take it from the query map"
          (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources client query)))))
          (let [after-first @lookups]
            (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources client query)))))
            (is (= after-first @lookups) "second identical lookup is a hit")
            (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources
                                             client (assoc query :cache? false))))))
            (is (= (inc after-first) @lookups)
                ":cache? false recomputes")))))))

(deftest per-request-cache-flag-does-not-break-cursors-test
  ;; :cache is excluded from the cursor's query identity. Leaving it in would
  ;; make a page-2 request that omits it fail against a page-1 token minted
  ;; with it — the same failure :consistency once caused.
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:cache {:remember-answers true}})]
      (eacl/write-schema! client direct-schema)
      @(d/transact conn (into [{:eacl/id "alice"}]
                              (for [n (range 6)] {:eacl/id (str "a-" n)})))
      (doseq [n (range 6)]
        (eacl/create-relationship!
         client (->Relationship (spice-object :user "alice") :owner
                                (spice-object :account (str "a-" n)))))
      (let [query {:subject (spice-object :user "alice")
                   :permission :admin
                   :resource/type :account
                   :first 2}
            page-1 (eacl/lookup-resources client (assoc query :cache? false))
            cursor (get-in page-1 [:page-info :end-cursor])]
        (is (= 2 (count (:data page-1))))
        (testing "a cursor minted with the override is usable without it"
          (is (= 2 (count (:data (eacl/lookup-resources
                                  client (assoc query :after cursor)))))))
        (testing "and vice versa"
          (let [c2 (get-in (eacl/lookup-resources client query)
                           [:page-info :end-cursor])]
            (is (= 2 (count (:data (eacl/lookup-resources
                                    client (assoc query :after c2
                                                  :cache? false)))))))))
      (testing "a non-boolean override is rejected rather than ignored"
        (is (thrown? clojure.lang.ExceptionInfo
                     (eacl/lookup-resources
                      client {:subject (spice-object :user "alice")
                              :permission :admin
                              :resource/type :account
                              :cache? :sometimes})))))))

(deftest per-request-cache-flag-covers-every-operation-test
  ;; read-relationships validates its filter keys strictly — an unknown key is
  ;; a hard error, not something to ignore — so :cache? had to be added to the
  ;; accepted set. This test is why that was caught: an operation-by-operation
  ;; sweep rather than a spot check on can? and lookup-resources.
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:page-token-key "bypass-all"})
          _ (seed-direct! conn client)
          alice (spice-object :user "alice")
          account (spice-object :account "a-1")
          store (get-in client [:opts :lookup-cache-store])
          puts #(:puts (cache/stats store))
          calls [[:can? #(eacl/can? client (assoc % :subject alice
                                                 :permission :admin
                                                 :resource account))]
                 [:lookup-resources #(eacl/lookup-resources
                                      client (assoc % :subject alice
                                                    :permission :admin
                                                    :resource/type :account))]
                 [:lookup-subjects #(eacl/lookup-subjects
                                     client (assoc % :resource account
                                                   :permission :admin
                                                   :subject/type :user))]
                 [:count-resources #(eacl/count-resources
                                     client (assoc % :subject alice
                                                   :permission :admin
                                                   :resource/type :account))]
                 [:count-subjects #(eacl/count-subjects
                                    client (assoc % :resource account
                                                  :permission :admin
                                                  :subject/type :user))]
                 [:read-relationships #(eacl/read-relationships
                                        client (assoc % :resource/type :account))]]]
      ;; warm every operation so there is something to bypass
      (dotimes [_ 2] (doseq [[_ f] calls] (f {})))
      (doseq [[label f] calls]
        (testing (str label " accepts :cache? false")
          (let [before (puts)
                result (f {:cache? false})]
            (is (some? result) (str label " still answers"))
            (is (= before (puts))
                (str label " with :cache? false wrote nothing to the store")))))
      (testing "and a non-boolean is rejected on each"
        (doseq [[label f] calls]
          (is (thrown? clojure.lang.ExceptionInfo (f {:cache? :maybe}))
              (str label " rejects a non-boolean :cache?")))))))

;; --- cache provenance on responses -------------------------------------------

(deftest responses-report-whether-they-came-from-cache-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:page-token-key "provenance"
                                         :cache {:remember-answers true}})
          _ (seed-direct! conn client)
          alice (spice-object :user "alice")
          account (spice-object :account "a-1")
          query {:subject alice :permission :admin :resource/type :account}]
      (testing "a computed answer reports its own basis and no hit"
        (let [r (eacl/lookup-resources client query)]
          (is (false? (:cached? r)))
          (is (integer? (:cache-basis r)))))

      (testing "a repeat is a hit at the basis it was computed at"
        (let [r (eacl/lookup-resources client query)]
          (is (true? (:cached? r)))
          (is (integer? (:cache-basis r)))))

      (testing "counts too"
        (is (false? (:cached? (eacl/count-resources client query))))
        (is (true? (:cached? (eacl/count-resources client query)))))

      (testing "a write to a dependency moves the basis and clears the hit"
        (let [before (:cache-basis (eacl/lookup-resources client query))]
          (eacl/create-relationship!
           client (->Relationship alice :owner (spice-object :account "a-2")))
          (let [r (eacl/lookup-resources client query)]
            (is (false? (:cached? r)))
            (is (> (:cache-basis r) before)
                "recomputed against a newer basis"))))

      (testing "the basis resolves to a wall-clock instant"
        (let [r (eacl/lookup-resources client query)]
          (is (inst? (core/basis-instant client (:cache-basis r))))
          (is (nil? (core/basis-instant client nil)))))

      (testing "a bypassed call never reports a hit"
        (is (false? (:cached? (eacl/lookup-resources
                               client (assoc query :cache? false))))))

      (testing "and neither does a client with no cache"
        (let [plain (core/make-client conn {:cache cache/no-cache
                                            :page-token-key "provenance"})]
          (is (false? (:cached? (eacl/lookup-resources plain query))))
          (is (false? (:cached? (eacl/count-resources plain query)))))))))

(deftest cache-entries-do-not-expire-on-a-timer-test
  ;; Relation stamps are the staleness bound, so age is not a reason to drop an
  ;; entry. A ttl used to be applied by default and silently capped to the
  ;; page-token lifetime, which meant a hot entry was discarded on a clock.
  (let [now (atom 0)
        store (cache/local-store {:clock (fn [] @now)})]
    (is (true? (cache/store! store [:k] {:eacl.cache/kind :can? :v 1} 10 nil)))
    (swap! now + (* 1000 60 60 24 365))
    (is (some? (cache/lookup store [:k]))
        "a year later the entry is still there")
    (testing "an explicit :ttl-ms still expires"
      (let [ttl-store (cache/local-store {:clock (fn [] @now)})]
        (is (true? (cache/store! ttl-store [:k] {:eacl.cache/kind :can? :v 1} 10 1000)))
        (is (some? (cache/lookup ttl-store [:k])))
        (swap! now + 1001)
        (is (nil? (cache/lookup ttl-store [:k])))))))

(deftest default-client-cache-has-no-ttl-test
  (with-mem-conn [conn schema/v7-schema]
    (is (nil? (:lookup-cache-ttl-ms
               (:opts (core/make-client conn {}))))
        "no expiry unless the caller asks for one")
    (is (= 5000 (:lookup-cache-ttl-ms
                 (:opts (core/make-client conn {:cache {:ttl-ms 5000}})))))))

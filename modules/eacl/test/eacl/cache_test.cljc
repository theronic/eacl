(ns eacl.cache-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
            :refer [deftest is testing]]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.subproblem-cache :as subproblem]))

(defn- snapshot-object
  []
  #?(:clj (Object.)
     :cljs (js-obj)))

(deftest current-generation-cache-is-client-private-test
  (let [native-cache (cache/current-cache)]
    (is (= :client-private-cache-reuse
           (try
             (cache/current-cache-for-option native-cache)
             nil
             (catch #?(:clj clojure.lang.ExceptionInfo
                       :cljs cljs.core.ExceptionInfo)
                    error
               (:reason (ex-data error))))))
    (is (not (identical? (cache/current-cache-for-option nil)
                         (cache/current-cache-for-option nil)))
        "each client option normalization creates a distinct native cache")))

(deftest current-generation-cache-test
  (let [store (cache/current-cache {:max-entries 32})
        snapshot-1 (snapshot-object)
        snapshot-2 (snapshot-object)
        snapshot-3 (snapshot-object)
        snapshot-4 (snapshot-object)
        computations (atom 0)
        stamp-reads (atom 0)
        answer (atom true)
        context
        (fn [snapshot order schema-stamp dependency-stamp]
          {:snapshot snapshot
           :snapshot-order order
           :same-snapshot? identical?
           :cache-basis order
           :managed-key-fn
           (fn []
             (swap! stamp-reads inc)
             {:schema-stamp schema-stamp
              :dependency-stamp dependency-stamp})})
        resolve
        (fn [current-context]
          (cache/resolve-current!
           store current-context
           [:can? :query] :decision boolean?
           (fn []
             (swap! computations inc)
             @answer)))]
    (testing "the exact hot path does not read dependency stamps"
      (is (= {:value true :cached? false :cache-tier nil}
             (select-keys
              (resolve
               (context
                 snapshot-1 1
                 10
                 20))
              [:value :cached? :cache-tier])))
      (is (= {:value true :cached? true :cache-tier :exact-current}
             (select-keys
              (resolve
               (context
                 snapshot-1 1
                 10
                 20))
              [:value :cached? :cache-tier])))
      (is (= 1 @computations))
      (is (= 1 @stamp-reads)))

    (testing "an unrelated transaction lifts through the managed tier"
      (is (= {:value true :cached? true :cache-tier :managed-current}
             (select-keys
              (resolve
               (context
                 snapshot-2 2
                 10
                 20))
              [:value :cached? :cache-tier])))
      (is (= 1 @computations))
      (is (= 2 @stamp-reads))
      (is (= :exact-current
             (:cache-tier
              (resolve
               (context
                 snapshot-2 2
                 10
                 20)))))
      (is (= 2 @stamp-reads)
          "promotion makes the next hit independent of stamp extraction"))

    (testing "same-tx relation and schema mutation identities cannot collide"
      (reset! answer false)
      (is (false? (:cached?
                   (resolve
                    (context
                      snapshot-3 3
                      10
                      21)))))
      (reset! answer true)
      (is (false? (:cached?
                   (resolve
                    (context
                      snapshot-4 4
                      22
                      22)))))
      (is (= 3 @computations)))

    (testing "explicit bypass neither reads nor publishes"
      (let [before-stamps @stamp-reads
            bypass-context
            {:snapshot snapshot-4
             :snapshot-order 4
             :same-snapshot? identical?
             :cache-basis 4
             :cacheable? false
             :managed-key-fn
             #(throw (ex-info "must not read stamps" {}))}]
        (is (false? (:cached? (resolve bypass-context))))
        (is (= before-stamps @stamp-reads))
        (is (= 4 @computations))))))

(deftest current-generation-late-publication-test
  (let [store (cache/current-cache)
        old-snapshot (snapshot-object)
        new-snapshot (snapshot-object)
        key [:can? :late-publication]
        context
        (fn [snapshot order]
          {:snapshot snapshot
           :snapshot-order order
           :same-snapshot? identical?
           :cache-basis order})
        nested? (atom false)
        old-answer
        (cache/resolve-current!
         store (context old-snapshot 1)
         key :decision boolean?
         (fn []
           (when (compare-and-set! nested? false true)
             (cache/resolve-current!
              store (context new-snapshot 2)
              key :decision boolean?
              (constantly false)))
           true))]
    (is (true? (:value old-answer)))
    (is (false?
         (:value
          (cache/resolve-current!
           store (context new-snapshot 2)
           key :decision boolean?
           (constantly true))))
        "old publication cannot repopulate the installed newer generation")
    (is (= :exact-current
           (:cache-tier
            (cache/resolve-current!
             store (context new-snapshot 2)
             key :decision boolean?
             (constantly true)))))))

(deftest snapshot-exact-completed-answer-cache-test
  (let [store (cache/current-cache)
        current-snapshot (snapshot-object)
        semantic-key {:operation :can? :query [:alice :read :document]}
        snapshot-key
        (fn [lifecycle revision]
          {:key-version 1
           :backend :test
           :source-scope {:source-id :source
                          :branch nil
                          :source-lifecycle lifecycle
                          :backend :test}
           :native-revision {:revision revision :exact-locator revision}
           :exact-locator revision
           :view-kind :ordinary-exact
           :snapshot-id {:basis-t revision}
           :adapter-fingerprint :adapter-v1
           :identity-contract :identity-v1})
        key-1 (snapshot-key :lifecycle-a 1)
        key-2 (snapshot-key :lifecycle-a 2)
        key-1-replaced (snapshot-key :lifecycle-b 1)
        computations (atom 0)
        exact
        (fn [snapshot-key value]
          (cache/resolve-exact!
           store
           {:snapshot-exact-key snapshot-key
            :cache-basis (:snapshot-id snapshot-key)}
           semantic-key :decision boolean?
           (fn []
             (swap! computations inc)
             value)))]
    (testing "a current answer seeds the matching authenticated exact tier"
      (cache/resolve-current!
       store
       {:snapshot current-snapshot
        :snapshot-order 1
        :same-snapshot? identical?
        :snapshot-exact-key key-1
        :cache-basis {:basis-t 1}}
       semantic-key :decision boolean? (constantly true))
      (let [answer (exact key-1 false)]
        (is (true? (:value answer)))
        (is (true? (:cached? answer)))
        (is (= :snapshot-exact (:cache-tier answer)))
        (is (zero? @computations))))

    (testing "different revisions and repeated numbers after lifecycle rotation cannot collide"
      (is (false? (:value (exact key-2 false))))
      (is (false? (:cached? (exact key-1-replaced false))))
      (is (= 2 @computations))
      (is (true? (:value (exact key-1 false)))
          "the older retained snapshot remains independently addressable")
      (is (false? (:value (exact key-2 true)))
          "the newer retained snapshot keeps its own completed answer"))

    (testing "explicit cache lifecycle expiry detaches every historical answer"
      (cache/expire-current! store)
      (is (false? (:cached? (exact key-1 false))))
      (is (false? (:value (exact key-1 false))))
      (is (= 3 @computations)))))

(deftest current-generation-expiry-test
  (let [store (cache/current-cache)
        snapshot (snapshot-object)
        context {:snapshot snapshot
                 :snapshot-order 1
                 :same-snapshot? identical?
                 :cache-basis 1}
        calls (atom 0)
        resolve
        #(cache/resolve-current!
          store context :key :decision boolean?
          (fn [] (swap! calls inc) true))]
    (is (false? (:cached? (resolve))))
    (is (true? (:cached? (resolve))))
    (cache/expire-current! store)
    (is (false? (:cached? (resolve))))
    (is (= 2 @calls))))

(deftest exact-generation-subproblem-lifecycle-test
  (let [store (cache/current-cache)
        snapshot (snapshot-object)
        context {:snapshot snapshot
                 :snapshot-order 1
                 :same-snapshot? identical?
                 :cache-basis 1}
        projection-calls (atom 0)
        resolve
        (fn [top-level-key]
          (cache/resolve-current!
           store context top-level-key :decision integer?
           (fn []
             (:value
              (subproblem/resolve-bound!
               :projection :shared-projection {}
               (fn []
                 (swap! projection-calls inc)
                 42))))))]
    (testing "distinct completed-answer keys share one exact projection"
      (is (= 42 (:value (resolve :top-level-a))))
      (is (= 42 (:value (resolve :top-level-b))))
      (is (= 1 @projection-calls))
      (is (zero? (:exact-hits (cache/current-cache-stats store)))
          "different top-level keys must not be counted as final-answer hits")
      (is (= 1 (get-in (cache/current-cache-stats store)
                       [:subproblems :projection-hits]))))
    (testing "expiry replaces the complete subproblem generation"
      (cache/expire-current! store)
      (is (= 42 (:value (resolve :top-level-c))))
      (is (= 2 @projection-calls))
      (is (zero? (get-in (cache/current-cache-stats store)
                         [:subproblems :projection-hits]))))))

(deftest completed-answer-bypass-still-shares-current-subproblems-test
  (let [store (cache/current-cache)
        snapshot (snapshot-object)
        context {:snapshot snapshot
                 :snapshot-order 1
                 :same-snapshot? identical?
                 :cache-basis 1
                 :remember-answer? false}
        top-level-calls (atom 0)
        projection-calls (atom 0)
        resolve
        #(cache/resolve-current!
          store context :same-top-level-key :decision integer?
          (fn []
            (swap! top-level-calls inc)
            (:value
             (subproblem/resolve-bound!
              :projection :shared-projection {}
              (fn []
                (swap! projection-calls inc)
                42)))))]
    (is (= 42 (:value (resolve))))
    (is (= 42 (:value (resolve))))
    (is (= 2 @top-level-calls)
        "remember-answer? false never reuses the completed answer")
    (is (= 1 @projection-calls)
        "the current-generation subproblem remains reusable")
    (let [stats (cache/current-cache-stats store)]
      (is (zero? (:exact-entries stats)))
      (is (= 2 (:bypasses stats)))
      (is (= 1 (get-in stats [:subproblems :projection-hits]))))))

#?(:clj
   (deftest delayed-subproblem-publication-cannot-resurrect-expired-generation-test
     (let [store (cache/current-cache)
           snapshot-1 (snapshot-object)
           snapshot-2 (snapshot-object)
           context
           (fn [snapshot order]
             {:snapshot snapshot
              :snapshot-order order
              :same-snapshot? identical?
              :cache-basis order})
           started (promise)
           release (promise)
           old
           (future
             (cache/resolve-current!
              store (context snapshot-1 1) :old :decision integer?
              (fn []
                (:value
                 (subproblem/resolve-bound!
                  :projection :shared {}
                  (fn []
                    (deliver started true)
                    @release
                    41))))))
           _ @started]
       (cache/expire-current! store)
       (is (= 42
              (:value
               (cache/resolve-current!
                store (context snapshot-2 2) :new-a :decision integer?
                (fn []
                  (:value
                   (subproblem/resolve-bound!
                    :projection :shared {}
                    (constantly 42))))))))
       (deliver release true)
       (is (= 41 (:value @old))
           "the already selected request may finish on its old snapshot")
       (is (= 42
              (:value
               (cache/resolve-current!
                store (context snapshot-2 2) :new-b :decision integer?
                (fn []
                  (:value
                   (subproblem/resolve-bound!
                    :projection :shared {}
                    (constantly 99)))))))
           "late work remains reachable only from the expired lifecycle")
       (is (= 1 (get-in (cache/current-cache-stats store)
                        [:subproblems :projection-hits]))))))

(deftest request-boundary-capture-prevents-pre-lookup-expiry-reattachment-test
  (let [store (cache/current-cache)
        captured (cache/capture-current-lifecycle store)
        context {:snapshot 7
                 :snapshot-order 7
                 :same-snapshot? =
                 :cache-basis 7}
        resolve-projection
        (fn [request-context top-level-key value]
          (:value
           (cache/resolve-current!
            store request-context top-level-key :decision integer?
            (fn []
              (:value
               (subproblem/resolve-bound!
                :projection :shared-projection {}
                (constantly value)))))))]
    ;; Model expiry after a request selected its source snapshot but before it
    ;; reached resolve-current!.  Reusing the numeric revision after a restore
    ;; must not let that old request populate the replacement lifecycle.
    (cache/expire-current! store)
    (is (= 41
           (resolve-projection
            (assoc context :cache-lifecycle captured)
            :old-request
            41)))
    (is (= 42
           (resolve-projection context :new-request-a 42)))
    (is (= 42
           (resolve-projection context :new-request-b 99))
        "new requests share only the replacement lifecycle's projection")))

#?(:clj
   (deftest generation-expiry-detaches-old-publication-without-blocking-test
     (let [store (cache/current-cache)
           context
           (fn [snapshot order]
             {:snapshot snapshot
              :snapshot-order order
              :same-snapshot? identical?
              :cache-basis order})
           old-started (promise)
           release-old (promise)
           new-started (promise)
           new-snapshot (snapshot-object)
           old-work
           (future
             (cache/resolve-current!
              store (context (snapshot-object) 1) :old :decision keyword?
              (fn []
                (:value
                 (subproblem/resolve-bound!
                  :projection :old-projection {}
                  (fn []
                    (deliver old-started true)
                    @release-old
                    :old))))))
           _ @old-started
           _ (cache/expire-current! store)
           new-work
           (future
             (cache/resolve-current!
              store (context new-snapshot 2) :new :decision keyword?
              (fn []
                (:value
                 (subproblem/resolve-bound!
                  :projection :new-projection {}
                  (fn []
                    (deliver new-started true)
                    :new))))))]
       (is (= true (deref new-started 1000 ::timed-out)))
       (is (= :new (:value @new-work)))
       (deliver release-old true)
       (is (= :old (:value @old-work)))
       (is (= :new
              (:value
               (cache/resolve-current!
                store (context new-snapshot 2)
                :new :decision keyword? (constantly :wrong))))))))

(deftest cache-disabled-request-bypasses-subproblem-store-test
  (let [store (cache/current-cache)
        snapshot (snapshot-object)
        cached-context {:snapshot snapshot
                        :snapshot-order 1
                        :same-snapshot? identical?
                        :cache-basis 1}
        projection-calls (atom 0)
        projection
        #(subproblem/resolve-bound!
          :projection :projection {}
          (fn []
            (swap! projection-calls inc)
            7))
        top-level
        (fn [key context]
          (cache/resolve-current!
           store context key :decision integer?
           #(-> (projection) :value)))]
    (is (= 7 (:value (top-level :cached cached-context))))
    (let [before (:subproblems (cache/current-cache-stats store))]
      (is (= 7 (:value
                (top-level :disabled
                           (assoc cached-context :cacheable? false)))))
      (is (= 2 @projection-calls))
      (is (= before
             (:subproblems (cache/current-cache-stats store)))))))

(deftest completed-answer-only-cache-option-test
  (let [store
        (cache/current-cache
         {:subproblem-cache {:enabled? false}})
        snapshot (snapshot-object)
        context {:snapshot snapshot
                 :snapshot-order 1
                 :same-snapshot? identical?
                 :cache-basis 1}
        calls (atom 0)
        resolve
        (fn [key]
          (cache/resolve-current!
           store context key :decision integer?
           (fn []
             (:value
              (subproblem/resolve-bound!
               :projection :shared {}
               (fn []
                 (swap! calls inc)
                 9))))))]
    (is (= 9 (:value (resolve :a))))
    (is (= 9 (:value (resolve :b))))
    (is (= 2 @calls))
    (is (zero? (get-in (cache/current-cache-stats store)
                       [:subproblems :projection-hits])))))

(deftest managed-descriptor-is-lazy-after-exact-lookup-test
  (let [store (cache/current-cache)
        snapshot-1 (snapshot-object)
        snapshot-2 (snapshot-object)
        descriptor-reads (atom 0)
        context
        (fn [snapshot order]
          {:snapshot snapshot
           :snapshot-order order
           :same-snapshot? identical?
           :cache-basis order
           :managed-key-fn
           (fn []
             (swap! descriptor-reads inc)
             {:schema-stamp 10
              :dependency-stamp 20})})
        resolve
        (fn [semantic-key current-context]
          (cache/resolve-current!
           store current-context semantic-key :decision integer?
           (constantly 7)))]
    (resolve :query-a (context snapshot-1 1))
    (is (= 1 @descriptor-reads)
        "an exact miss acquires one descriptor")
    (resolve :query-a (context snapshot-1 1))
    (is (= 1 @descriptor-reads)
        "an exact hit acquires no descriptor")
    (resolve :query-b (context snapshot-1 1))
    (is (= 2 @descriptor-reads)
        "another semantic miss owns another request frame")
    (resolve :query-b (context snapshot-2 2))
    (is (= 3 @descriptor-reads)
        "a new immutable snapshot must revalidate its proof")))

(deftest unavailable-proof-cannot-reuse-or-publish-managed-answer-test
  (let [store (cache/current-cache)
        snapshot-1 (snapshot-object)
        snapshot-2 (snapshot-object)
        compute-calls (atom 0)
        resolve
        (fn [snapshot order managed-key value]
          (cache/resolve-current!
           store
           {:snapshot snapshot
            :snapshot-order order
            :same-snapshot? identical?
            :cache-basis order
            :managed-key-fn (constantly managed-key)}
           :proof-fallback
           :decision
           boolean?
           (fn []
             (swap! compute-calls inc)
             value)))]
    (is (true? (:value (resolve snapshot-1 1
                               {:schema-stamp 10
                                :dependency-stamp 20}
                               true))))
    (let [fallback (resolve snapshot-2 2 nil false)]
      (is (false? (:value fallback))
          "an unavailable proof must not return the older managed ALLOW")
      (is (false? (:cached? fallback)))
      (is (nil? (:cache-tier fallback))))
    (is (= 2 @compute-calls))
    (is (false? (:value (resolve snapshot-2 2 nil true)))
        "the exact result computed during fallback remains reusable")))

(deftest proof-unavailable-diagnostics-are-typed-telemetry-test
  (let [store (cache/current-cache)]
    (cache/record-proof-unavailable!
     store {:status :unavailable :reason :missing-generation})
    (cache/record-proof-unavailable!
     store {:status :unavailable :reason :provider-failure})
    (cache/record-proof-unavailable!
     store {:status :unavailable :reason :missing-generation})
    (let [stats (cache/current-cache-stats store)]
      (is (= 3 (:proof-unavailable stats)))
      (is (= {:missing-generation 2 :provider-failure 1}
             (:proof-unavailable-reasons stats))))))

(deftest current-generation-two-hit-admission-test
  (let [store (cache/current-cache {:admit-on-repeat? true})
        snapshot (snapshot-object)
        context {:snapshot snapshot
                 :snapshot-order 1
                 :same-snapshot? identical?
                 :cache-basis 1}
        calls (atom 0)
        resolve
        #(cache/resolve-current!
          store context :key :decision boolean?
          (fn [] (swap! calls inc) true))]
    (is (false? (:cached? (resolve)))
        "the first sighting is not retained")
    (is (false? (:cached? (resolve)))
        "the second sighting demonstrates reuse and is retained")
    (is (true? (:cached? (resolve))))
    (is (= 2 @calls))
    (is (= 1 (:exact-entries (cache/current-cache-stats store))))
    (cache/expire-current! store)
    (is (false? (:cached? (resolve)))
        "explicit expiry also resets admission history")
    (is (= 0 (:exact-entries (cache/current-cache-stats store))))))

(deftest authenticated-page-query-identity-ignores-signed-transport-test
  (let [base-public
        {:subject {:type :user :id "user"}
         :permission :view
         :resource/type :document
         :first 20}
        base-internal
        {:subject {:type :user :id 1}
         :permission :view
         :resource/type :document
         :first 20}
        boundary {:kind :lookup-eid :resource 42}
        original
        (cache/lookup-page-query-identity
         (assoc base-public
                :after "signed-snapshot-a"
                :cancellation-token (eacl/cancellation-token))
         (assoc base-internal
                :after boundary
                :cancellation-token (eacl/cancellation-token)))
        recovered
        (cache/lookup-page-query-identity
         (assoc base-public
                :after "signed-snapshot-b"
                :cache? true
                :cancellation-token (eacl/cancellation-token))
         (assoc base-internal
                :after boundary
                :cancellation-token (eacl/cancellation-token)))]
    (is (= original recovered)
        "signed transport bytes are not page semantics")
    (is (not=
         original
         (cache/lookup-page-query-identity
          (assoc base-public :after "signed-snapshot-c")
          (assoc base-internal
                 :after (assoc boundary :resource 43))))
        "the authenticated internal boundary still distinguishes pages")
    (is (not=
         original
         (cache/lookup-page-query-identity
          (assoc base-public :after "signed-snapshot-d" :first 50)
          (assoc base-internal :after boundary :first 50)))
        "page size remains semantic")))

(deftest completed-answer-hot-key-survives-churn-test
  ;; R6 regression scenario "hot key survives churn": the deleted
  ;; completed-answer map evicted in hash-iteration order, so a repeatedly
  ;; accessed key was as likely to die as any cold key. The weighted
  ;; :answer tier evicts least-recently-used.
  (let [store (cache/current-cache
               {:subproblem-cache {:answer-max-weight 4096}})
        snapshot (snapshot-object)
        context {:snapshot snapshot
                 :snapshot-order 1
                 :same-snapshot? identical?
                 :cache-basis 1
                 ;; Fixed per-answer weight: the tier retains 8 answers.
                 :answer-weight-fn (constantly 512)}
        resolve
        (fn [key]
          (cache/resolve-current!
           store context key :decision boolean? (constantly true)))]
    (resolve :hot)
    (dotimes [i 64]
      (resolve [:cold i])
      (is (true? (:cached? (resolve :hot)))
          "the repeatedly accessed answer stays resident through churn"))
    (let [tier (get-in (cache/current-cache-stats store)
                       [:subproblems :tiers :answer])]
      (is (<= (:weight tier) 4096)
          "retained answer weight never exceeds the configured budget")
      (is (false? (:cached? (resolve [:cold 0])))
          "cold keys evicted; only the hot answer and the newest survive"))))

(deftest completed-answer-byte-budget-and-oversized-rejection-test
  ;; R6 regression scenario "page-heavy workload stays within budget": the
  ;; deleted answer map was entry-bounded only (weight fn accepted and
  ;; ignored), measured at 95.5 MB retained. The :answer tier is
  ;; weight-bounded with a budget/4 per-entry ceiling.
  (let [store (cache/current-cache
               {:subproblem-cache {:answer-max-weight 8192}})
        snapshot (snapshot-object)
        page (fn [n] {:data (vec (range n))})
        context {:snapshot snapshot
                 :snapshot-order 1
                 :same-snapshot? identical?
                 :cache-basis 1}
        resolve
        (fn [key value]
          (cache/resolve-current!
           store context key :lookup-resources map?
           (fn [] value)))]
    ;; Saturate with page answers under the default row-count weight
    ;; (512 + 128/row: 1792 each, so at most four fit).
    (dotimes [i 32]
      (resolve [:page i] (page 10)))
    (let [tier (get-in (cache/current-cache-stats store)
                       [:subproblems :tiers :answer])]
      (is (pos? (:entries tier)))
      (is (<= (:weight tier) 8192)
          "retained answer weight stays within the configured budget"))
    ;; One answer heavier than budget/4 is rejected at publication and
    ;; recomputed on the next request instead of retained unbounded.
    (resolve :oversized (page 100))
    (is (pos? (get-in (cache/current-cache-stats store)
                      [:subproblems :oversized-rejections])))
    (is (false? (:cached? (resolve :oversized (page 100))))
        "an oversized answer is never served from cache")))

(deftest repeat-admission-survives-large-keyspace-churn-test
  ;; R6 regression scenario "repeat admission at scale": the deleted
  ;; admissions map evicted its hash-trie-order minimum, freezing into a
  ;; fixed hash-lucky sighting set (measured 2.3% admit at 50x keyspace).
  ;; The first-in-first-out sighting window forgets oldest first sightings,
  ;; so admission tracks access recency at any keyspace size.
  (let [window 64
        store (cache/current-cache
               {:max-entries window
                :admit-on-repeat? true})
        snapshot (snapshot-object)
        context {:snapshot snapshot
                 :snapshot-order 1
                 :same-snapshot? identical?
                 :cache-basis 1}
        resolve
        (fn [key]
          (cache/resolve-current!
           store context key :decision boolean? (constantly true)))]
    ;; Churn 50x the window in distinct single-sighting keys.
    (dotimes [i (* 50 window)]
      (resolve [:cold i]))
    ;; A key then seen twice in close succession is admitted.
    (resolve :fresh)
    (resolve :fresh)
    (is (true? (:cached? (resolve :fresh)))
        "a second sighting within the window admits the answer")
    ;; A second sighting within window-many distinct first sightings still
    ;; admits; one spaced past the window is forgotten and starts over.
    (resolve :spaced)
    (dotimes [i (dec window)]
      (resolve [:filler i]))
    (resolve :spaced)
    (is (true? (:cached? (resolve :spaced)))
        "the sighting window spans max-entries distinct first sightings")
    (resolve :forgotten)
    (dotimes [i (inc window)]
      (resolve [:beyond i]))
    ;; Had the first sighting survived the window, this call would admit
    ;; and store, making the next call a cache hit. It restarts instead.
    (resolve :forgotten)
    (is (false? (:cached? (resolve :forgotten)))
        "a sighting spaced past the window restarts admission")
    (is (<= (:admission-entries (cache/current-cache-stats store)) window)
        "sighting state stays bounded by the window at 50x keyspace")))

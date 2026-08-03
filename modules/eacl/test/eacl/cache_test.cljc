(ns eacl.cache-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.verified-kernel :as verified]))

(defn- adapter
  [proofs]
  (backend/make-adapter
   {:id :cache-test
    :capabilities
    {:consistency #{:fully-consistent}
     :snapshots #{:current}
     :cursor #{:forward}
     :transactions #{}
     :cache-proofs #{:schema :relations :snapshot-bound}
     :runtime #{:clj}}
    :operations
    (merge
     (into {}
           (map (fn [operation]
                  [operation (fn [& _] nil)]))
           backend/required-snapshot-operations)
     {:snapshot-id #(select-keys @proofs [:basis])
      :source-scope
      (fn [] {:source-id (:source @proofs) :branch nil})
      :graph-head
      (fn [] {:graph-anchor (:head @proofs)
              :order-hint (:basis @proofs)
              :exact-locator (:basis @proofs)})
      :contains-anchor?
      (fn [anchor] (contains? (:anchors @proofs) anchor))
      :order-hint (fn [] (:basis @proofs))
      :exact-locator (fn [] (:basis @proofs))
      :schema-proof
      (fn
        ([] (if (:proof-provider-failure? @proofs)
              (throw (ex-info "schema proof unavailable" {}))
              (when-not (:proof-unavailable? @proofs)
                (:schema @proofs))))
        ([{:keys [permission-nodes]}]
         (if (:proof-provider-failure? @proofs)
           (throw (ex-info "schema proof unavailable" {}))
           (when-not (:proof-unavailable? @proofs)
             (select-keys (:schema @proofs) permission-nodes)))))
      :relation-proof
      (fn [relation-ids]
        (if (:relation-proof-provider-failure? @proofs)
          (throw (ex-info "relationship proof unavailable" {}))
          (when-not (:proof-unavailable? @proofs)
            (select-keys (:relations @proofs) relation-ids))))})}))

(defrecord ThrowingStore []
  cache/CacheStore
  (lookup [_ _] (throw (ex-info "unavailable" {})))
  (store! [_ _ _] (throw (ex-info "unavailable" {})))
  (evict! [_ _] (throw (ex-info "unavailable" {})))
  (clear! [_] (throw (ex-info "unavailable" {})))
  (stats [_] (throw (ex-info "unavailable" {}))))

(defrecord ForgingStore [value]
  cache/CacheStore
  (lookup [_ _] value)
  (store! [_ _ _] true)
  (evict! [_ _] false)
  (clear! [_] nil)
  (stats [_] {}))

(defrecord RacingStore [entries replaced?]
  cache/CacheStore
  (lookup [_ key] (get @entries key))
  (store! [_ key value]
    (swap! entries assoc key value)
    true)
  (evict! [_ key]
    (swap! entries dissoc key)
    true)
  (clear! [_]
    (reset! entries {})
    nil)
  (stats [_] {:entries (count @entries)})
  cache/CacheTelemetry
  (record-validation! [_ _] nil)
  cache/CacheValidationUpdate
  (store-validation! [_ key _expected _replacement]
    ;; Simulate another validator replacing the provider value after this
    ;; request's lookup but before its authenticated telemetry CAS.
    (reset! replaced? true)
    (swap! entries assoc key "concurrent-replacement")
    false))

(defrecord RejectingKernel []
  verified/DecisionKernel
  (-decide [_ operation _input]
    (case operation
      :cache-validation
      {:status :miss :reason :proof-mismatch}

      :cursor-continuation
      :scope-mismatch

      {:status :invalid :reason :rejected})))

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
              (resolve (context snapshot-1 1 10 20))
              [:value :cached? :cache-tier])))
      (is (= {:value true :cached? true :cache-tier :exact-current}
             (select-keys
              (resolve (context snapshot-1 1 10 20))
              [:value :cached? :cache-tier])))
      (is (= 1 @computations))
      (is (= 1 @stamp-reads)))

    (testing "an unrelated transaction lifts through the managed tier"
      (is (= {:value true :cached? true :cache-tier :managed-current}
             (select-keys
              (resolve (context snapshot-2 2 10 20))
              [:value :cached? :cache-tier])))
      (is (= 1 @computations))
      (is (= 2 @stamp-reads))
      (is (= :exact-current
             (:cache-tier
              (resolve (context snapshot-2 2 10 20)))))
      (is (= 2 @stamp-reads)
          "promotion makes the next hit independent of stamp extraction"))

    (testing "relevant relation and schema changes miss"
      (reset! answer false)
      (is (false? (:cached?
                   (resolve (context snapshot-3 3 10 21)))))
      (reset! answer true)
      (is (false? (:cached?
                   (resolve (context snapshot-4 4 11 21)))))
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

(deftest exact-proof-validation-test
  (let [proofs (atom {:basis 1
                      :source "source"
                      :head "head-1"
                      :anchors #{"head-1"}
                      :schema {:document :schema-1
                               :unrelated :schema-a}
                      :relations {10 :relation-1
                                  20 :unrelated-1}})
        snapshot (adapter proofs)
        store (cache/local-store)
        calls (atom 0)
        compute #(do (swap! calls inc) {:answer true})
        resolve #(cache/resolve!
                  snapshot store :key :can?
                  {:permission-nodes #{:document}}
                  [10]
                  (fn [value] (= #{:answer} (set (keys value))))
                  compute)]
    (testing "matching proofs reuse the value"
      (is (false? (:cached? (resolve))))
      (is (true? (:cached? (resolve))))
      (is (= 1 @calls)))

    (testing "unrelated relation changes retain the entry"
      (swap! proofs assoc-in [:relations 20] :unrelated-2)
      (swap! proofs assoc
             :basis 2
             :head "head-2"
             :anchors #{"head-1" "head-2"})
      (is (true? (:cached? (resolve))))
      (is (= 1 @calls)))

    (testing "unrelated schema changes retain the entry"
      (swap! proofs assoc-in [:schema :unrelated] :schema-b)
      (is (true? (:cached? (resolve))))
      (is (= 1 @calls)))

    (testing "relevant relation and schema changes invalidate"
      (swap! proofs assoc-in [:relations 10] :relation-2)
      (is (false? (:cached? (resolve))))
      (swap! proofs assoc-in [:schema :document] :schema-2)
      (is (false? (:cached? (resolve))))
      (is (= 3 @calls)))))

(deftest authoritative-kernel-rejection-cannot-return-cache-entry-test
  (let [proofs (atom {:basis 1
                      :source "source"
                      :head "head-1"
                      :anchors #{"head-1"}
                      :schema {:document :schema-1}
                      :relations {10 :relation-1}})
        snapshot (adapter proofs)
        store (cache/local-store)
        calls (atom 0)
        resolve
        #(cache/resolve!
          snapshot store :key :can?
          {:permission-nodes #{:document}}
          [10] boolean?
          (fn [] (swap! calls inc) true)
          {:engine-selection
           {:mode :verified-authoritative
            :kernel (->RejectingKernel)}})]
    (is (false? (:cached? (resolve))))
    (is (false? (:cached? (resolve))))
    (is (= 2 @calls))))

(deftest forward-only-proof-lifting-test
  (let [proofs
        (atom {:basis 1
               :source "source"
               :head "computed"
               :anchors #{"computed"}
               :schema {:document :schema}
               :relations {10 :relation}})
        snapshot (adapter proofs)
        store (cache/local-store)
        calls (atom 0)
        resolve
        #(cache/resolve!
          snapshot store :key :can?
          {:permission-nodes #{:document}}
          [10] boolean?
          (fn [] (swap! calls inc) true))]
    (is (false? (:cached? (resolve))))
    (swap! proofs assoc
           :basis 2
           :head "descendant"
           :anchors #{"computed" "descendant"})
    (is (true? (:cached? (resolve))))
    (swap! proofs assoc
           :basis 2
           :head "sibling"
           :anchors #{"sibling"})
    (is (false? (:cached? (resolve))))
    (is (= 2 @calls))
    (is (= 1 (:causal-proof-lift (cache/stats store))))
    (is (= 1 (:future-history-rejection
              (cache/stats store))))))

(deftest corrupt-and-unavailable-store-fail-closed-test
  (let [proofs (atom {:basis 1
                      :source "source"
                      :head "head"
                      :anchors #{"head"}
                      :schema {:document :schema}
                      :relations {10 :relation}})
        snapshot (adapter proofs)
        corrupt-store (cache/local-store)
        _ (cache/store! corrupt-store :key {:answer :unvalidated})
        computed (atom 0)
        compute #(do (swap! computed inc) false)]
    (testing "a malformed value is a miss"
      (let [answer
            (cache/resolve!
             snapshot (->ForgingStore "eacl_ce3_forged")
             :key :can?
             {:permission-nodes #{:document}}
             [10] boolean? compute)]
        (is (false? (:value answer)))
        (is (false? (:cached? answer)))))

    (testing "provider read and write failures fall back to computation"
      (let [answer
            (cache/resolve!
             snapshot (->ThrowingStore)
             :key :can?
             {:permission-nodes #{:document}}
             [10] boolean? compute)]
        (is (false? (:value answer)))
        (is (false? (:cached? answer)))))

    (is (= 2 @computed))))

(deftest proof-provider-failure-fails-closed-test
  (doseq [failure-key
          [:proof-provider-failure?
           :relation-proof-provider-failure?]]
    (testing (name failure-key)
      (let [proofs (atom {:basis 1
                          :source "source"
                          :head "head"
                          :anchors #{"head"}
                          :schema {:document :schema}
                          :relations {10 :relation}
                          failure-key true})
            store (cache/local-store)
            computed (atom 0)
            answer
            (cache/resolve!
             (adapter proofs)
             store
             :key :can?
             {:permission-nodes #{:document}}
             [10]
             boolean?
             #(do (swap! computed inc) false))]
        (is (= {:value false :cached? false}
               (select-keys answer [:value :cached?])))
        (is (= 1 @computed))
        (is (= 1 (:provider-failure (cache/stats store))))))))

(deftest cache-scope-and-proof-availability-test
  (let [store (cache/local-store)
        first-proof
        (atom {:basis 1
               :source "first"
               :head "first-head"
               :anchors #{"first-head"}
               :schema {:document :schema}
               :relations {10 :relation}})
        second-proof
        (atom {:basis 1
               :source "second"
               :head "second-head"
               :anchors #{"second-head"}
               :schema {:document :schema}
               :relations {10 :relation}})
        calls (atom 0)
        compute #(do (swap! calls inc) true)
        resolve
        (fn [snapshot]
          (cache/resolve!
           snapshot store :same-query :can?
           {:permission-nodes #{:document}}
           [10] boolean? compute))]
    (is (false? (:cached? (resolve (adapter first-proof)))))
    (is (false? (:cached? (resolve (adapter second-proof)))))
    (is (= 2 @calls)
        "equal content in another source cannot reuse the entry")
    (swap! first-proof assoc :proof-unavailable? true)
    (is (false? (:cached? (resolve (adapter first-proof)))))
    (is (= 1 (:no-proof-bypass (cache/stats store))))
    (testing "an empty relationship dependency scope always bypasses"
      (swap! first-proof dissoc :proof-unavailable?)
      (is (false?
           (:cached?
            (cache/resolve!
             (adapter first-proof)
             store :same-query :can?
             {:permission-nodes #{:document}}
             [] boolean? compute))))
      (is (= 2 (:no-proof-bypass (cache/stats store)))))))

(deftest structural-proof-revocation-schema-and-restore-test
  (let [proofs (atom {:basis 1
                      :source "source"
                      :head "original"
                      :anchors #{"original"}
                      :schema {:document {:view :schema-v1}
                               :unrelated {:view :other-v1}}
                      :relations {10 #{[:user "u1" :document "d1"]}
                                  20 #{[:user "u2" :other "o1"]}}})
        authorization (atom true)
        store (cache/local-store)
        calls (atom 0)
        resolve
        #(cache/resolve!
          (adapter proofs) store :structural :can?
          {:permission-nodes #{:document}}
          [10] boolean?
          (fn []
            (swap! calls inc)
            @authorization))]
    (is (= {:value true :cached? false}
           (select-keys (resolve) [:value :cached?])))
    (testing "unrelated writes on a descendant preserve structural proofs"
      (swap! proofs assoc
             :basis 2
             :head "descendant"
             :anchors #{"original" "descendant"})
      (swap! proofs assoc-in
             [:relations 20] #{[:user "u3" :other "o1"]})
      (swap! proofs assoc-in
             [:schema :unrelated] {:view :other-v2})
      (is (true? (:cached? (resolve))))
      (is (= 1 @calls)))
    (testing "a relevant revocation changes proof and forces recomputation"
      (reset! authorization false)
      (swap! proofs assoc-in [:relations 10] #{})
      (is (= {:value false :cached? false}
             (select-keys (resolve) [:value :cached?])))
      (is (= 2 @calls)))
    (testing "a relevant schema change also forces recomputation"
      (reset! authorization true)
      (swap! proofs assoc-in [:schema :document] {:view :schema-v2})
      (is (= {:value true :cached? false}
             (select-keys (resolve) [:value :cached?])))
      (is (= 3 @calls)))
    (testing "restore changes are validated structurally before later reuse"
      (swap! proofs assoc
             :basis 1
             :head "original"
             :anchors #{"original"})
      (swap! proofs assoc-in
             [:relations 10] #{[:user "u1" :document "d1"]})
      (swap! proofs assoc-in
             [:schema :document] {:view :schema-v1})
      (is (false? (:cached? (resolve))))
      (is (= 4 @calls))
      (is (true? (:cached? (resolve))))
      (is (= 4 @calls)))
    (testing "equal content on a sibling branch cannot reverse-lift"
      (swap! proofs assoc
             :basis 3
             :head "sibling"
             :anchors #{"sibling"})
      (is (false? (:cached? (resolve))))
      (is (= 5 @calls)))))

(deftest dishonest-proof-collision-exposes-named-assumption-test
  (let [proofs (atom {:basis 1
                      :source "source"
                      :head "head-1"
                      :anchors #{"head-1"}
                      :schema {:document :dishonest-constant}
                      :relations {10 :dishonest-constant}})
        actual (atom true)
        store (cache/local-store)
        resolve
        #(cache/resolve!
          (adapter proofs) store :collision-double :can?
          {:permission-nodes #{:document}}
          [10] boolean? (fn [] @actual))]
    (is (true? (:value (resolve))))
    (reset! actual false)
    (swap! proofs assoc
           :basis 2
           :head "head-2"
           :anchors #{"head-1" "head-2"})
    (let [answer (resolve)]
      (is (true? (:cached? answer)))
      (is (true? (:value answer))
          "a dishonest/colliding complete-proof provider is an explicit axiom violation"))))

(deftest concurrent-validation-replacement-is-telemetry-only-test
  (let [proofs (atom {:basis 1
                      :source "source"
                      :head "head"
                      :anchors #{"head"}
                      :schema {:document :schema}
                      :relations {10 :relation}})
        entries (atom {})
        replaced? (atom false)
        store (->RacingStore entries replaced?)
        calls (atom 0)
        resolve
        #(cache/resolve!
          (adapter proofs) store :race :can?
          {:permission-nodes #{:document}}
          [10] boolean?
          (fn [] (swap! calls inc) true))]
    (is (false? (:cached? (resolve))))
    (is (true? (:cached? (resolve))))
    (is (true? @replaced?))
    (is (= 1 @calls)
        "the already authenticated provider value remains valid for this request")
    (is (false? (:cached? (resolve)))
        "the concurrent unvalidated replacement is never returned")
    (is (= 2 @calls))))

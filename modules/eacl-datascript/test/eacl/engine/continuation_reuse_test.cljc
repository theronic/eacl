(ns eacl.engine.continuation-reuse-test
  "Cross-request cache reuse gates (operator-reported regression).

  Repeated pagination through the public client must reuse latest-only
  checkpoints from the client's continuation store instead of replaying the
  canonical prefix on every page, and sealed plans must be reused across
  snapshot re-wraps of the same source at the same basis. The original
  defect: the stable engine rejected the client's continuation context
  (fn-map) outright, and keyed plans by JVM object identity, so both caches
  missed on every request in a real service."
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            #?(:clj [eacl.baseline.capture :as capture])
            [eacl.backend.v8 :as backend]
            [eacl.continuation :as continuation]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.engine.checkpoint-fixtures :as portable]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-page :as page]
            [eacl.engine.v8 :as v8]
            [eacl.proof-frame :as proof-frame]
            [datascript.core :as ds]))

(defn- fixture-for
  [fixture-key]
  #?(:clj ((get capture/fixtures fixture-key))
     :cljs (portable/fixture fixture-key)))

(defn- seed-fixture-client!
  [fixture]
  #?(:clj (capture/seed-client! fixture)
     :cljs (portable/seed-client! fixture)))

(defn- seeded-caching-client
  "Public DataScript client with default caching (the production shape:
  answer cache on, continuation store present)."
  ([fixture-key]
   (seeded-caching-client fixture-key {}))
  ([fixture-key client-options]
   (let [{:keys [schema objects relationships] :as fixture}
         (fixture-for fixture-key)
         conn (datascript/create-conn)
         client (datascript/make-client conn client-options)]
     (eacl/write-schema! client schema)
     (ds/transact! conn
                   (vec (map-indexed
                         (fn [index {:keys [id]}]
                           {:db/id (- (inc index)) :eacl/id id})
                         objects)))
     (doseq [batch (partition-all 500 relationships)]
       (eacl/create-relationships! client (vec batch)))
     {:fixture fixture :conn conn :client client})))

(deftest mutable-identity-contract-keeps-cursors-exact-basis-bound-test
  (let [{:keys [fixture conn client]}
        (seeded-caching-client
         :folder-chain
         {:security-key "mutable-identity-cursor-test-key"
          :identity-immutable? false})
        query {:subject (get-in fixture [:principals :alice])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)
               :first 3}
        all-ids (mapv :id (:data (eacl/lookup-resources
                                  client (assoc query :first 1000))))
        page-1 (eacl/lookup-resources client query)
        delivered-id (first all-ids)
        future-id (peek all-ids)
        first-eid (ds/entid (ds/db conn) [:eacl/id delivered-id])
        future-eid (ds/entid (ds/db conn) [:eacl/id future-id])
        _ (ds/transact!
           conn
           [[:db/retract first-eid :eacl/id delivered-id]
            [:db/retract future-eid :eacl/id future-id]
            [:db/add first-eid :eacl/id future-id]
            [:db/add future-eid :eacl/id delivered-id]])
        outcome
        (try
          {:value
           (eacl/lookup-resources
            client
            (assoc query :after (get-in page-1 [:page-info :end-cursor])))}
          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
            {:error (ex-data error)}))]
    (is (> (count all-ids) 3)
        "the chosen future identity is outside the delivered first page")
    (is (= delivered-id (get-in page-1 [:data 0 :id])))
    (is (false? (get-in client [:runtime :proof-equivalent-cursors?])))
    (is (= :selected-internal/current-external-injective-v2
           (get-in client [:runtime :identity-contract])))
    (is (nil? (:value outcome)))
    (is (= :eacl.pagination/stale-cursor
           (get-in outcome [:error :type])))
    (is (= :frame-changed (get-in outcome [:error :reason]))
        "identity churn cannot produce a hybrid cross-basis public stream")))

(deftest repeated-pagination-reuses-checkpoints-test
  ;; Checkpoints belong to RECURSIVE plans (order ABI v2:
  ;; acyclic-keyset-pagination gives acyclic roots self-contained keyset
  ;; cursors that never touch the continuation store — see
  ;; acyclic-pagination-needs-no-checkpoints-test below).
  (let [{:keys [fixture client]} (seeded-caching-client :folder-chain)
        store (get-in client [:runtime :continuation-cache-store])
        query {:subject (get-in fixture [:principals :alice])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)
               :first 5}
        one-shot (mapv :id (:data (eacl/lookup-resources
                                   client (assoc query :first 1000))))]
    (is (continuation/store? store)
        "the default client construction must provide a continuation store")
    (let [page-1 (eacl/lookup-resources client query)
          puts-after-1 (:puts (continuation/stats store))
          hits-after-1 (:hits (continuation/stats store))
          cursor-1 (get-in page-1 [:page-info :end-cursor])
          page-2 (eacl/lookup-resources client (assoc query :after cursor-1))
          hits-after-2 (:hits (continuation/stats store))]
      (is (pos? puts-after-1)
          "page 1 must publish its checkpoint to the client store")
      (is (> hits-after-2 hits-after-1)
          "page 2 must continue from the stored checkpoint, not replay")
      (is (= (take 5 one-shot) (mapv :id (:data page-1))))
      (is (= (take 5 (drop 5 one-shot)) (mapv :id (:data page-2))))
      (testing "full pagination composes exactly under checkpoint reuse"
        (loop [q query acc []]
          (let [{:keys [data page-info]} (eacl/lookup-resources client q)
                acc (into acc (map :id) data)]
            (if (and (:has-next-page? page-info) (:end-cursor page-info)
                     (seq data))
              (recur (assoc q :after (:end-cursor page-info)) acc)
              (is (= one-shot acc)))))))))

(deftest repeated-page-request-is-served-from-cache-test
  ;; Operator-reported: repeating the identical page request must not
  ;; recompute. The relay page-navigation cache serves the exact page back
  ;; and marks it :cached? under the same immutable snapshot.
  (let [{:keys [fixture client]} (seeded-caching-client :explorer-acyclic)
        query {:subject (get-in fixture [:principals :super-user])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)
               :first 5}
        page-1 (eacl/lookup-resources client query)
        page-1-again (eacl/lookup-resources client query)
        cursor-1 (get-in page-1 [:page-info :end-cursor])
        page-2 (eacl/lookup-resources client (assoc query :after cursor-1))
        page-2-again (eacl/lookup-resources client (assoc query :after cursor-1))]
    (is (true? (:cached? page-1-again))
        "the identical first-page request must be served from cache")
    (is (= (:data page-1) (:data page-1-again)))
    (is (true? (:cached? page-2-again))
        "the identical cursor-page request must be served from cache")
    (is (= (:data page-2) (:data page-2-again)))))

(deftest navigation-alias-never-serves-a-wrong-size-page-test
  ;; Review finding F7: the page-navigation cache learns an
  ;; opposite-direction alias for an adjacent page, but the boundary index
  ;; carries no page size, so a {:last N :before start} request could be
  ;; served a stored page of a different size. The alias may only answer
  ;; when the stored adjacent page holds exactly N items. Self-seeded so
  ;; both runtimes have the depth for three distinct pages.
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})
        alice (eacl/spice-object :user "alias-alice")
        docs (mapv #(eacl/spice-object :document (str "alias-doc-" %))
                   (range 9))]
    (eacl/write-schema!
     client
     "definition user {}
      definition document {
        relation reader: user
        permission view = reader
      }")
    (ds/transact! conn (mapv #(hash-map :eacl/id (:id %))
                             (into [alice] docs)))
    (eacl/create-relationships!
     client (mapv #(eacl/->Relationship alice :reader %) docs))
    (let [base {:subject alice
                :permission :view
                :resource/type :document}
          one-shot (mapv :id (:data (eacl/lookup-resources
                                     client (assoc base :first 1000))))
          page-0 (eacl/lookup-resources client (assoc base :first 2))
          page-a (eacl/lookup-resources
                  client (assoc base :first 2
                                :after (get-in page-0
                                               [:page-info :end-cursor])))
          ;; This request records the backward alias for page-a under
          ;; {:last 3 :before start-of-b} - page-a holds 2 items, not 3.
          page-b (eacl/lookup-resources
                  client (assoc base :first 3
                                :after (get-in page-a
                                               [:page-info :end-cursor])))
          backwards (eacl/lookup-resources
                     client (assoc base :last 3
                                   :before (get-in page-b
                                                   [:page-info
                                                    :start-cursor])))]
      (is (= 9 (count one-shot)))
      (is (= (take 2 (drop 2 one-shot)) (mapv :id (:data page-a))))
      (is (= (take 3 (drop 4 one-shot)) (mapv :id (:data page-b))))
      (is (= 3 (count (:data backwards)))
          "a :last 3 request must return exactly the three preceding items")
      (is (= (take 3 (drop 1 one-shot)) (mapv :id (:data backwards)))
          "the answer is the true window, not the smaller adjacent page"))))

(deftest repeated-count-is-served-from-cache-test
  (let [{:keys [fixture client]} (seeded-caching-client :explorer-acyclic)
        query {:subject (get-in fixture [:principals :super-user])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)}
        count-1 (eacl/count-resources client query)
        count-2 (eacl/count-resources client query)]
    (is (pos? (:count count-1)))
    (is (true? (:cached? count-2))
        "the identical count request must be served from cache")
    (is (= (:count count-1) (:count count-2)))))

(defn- limited-client
  [fixture-key limits]
  (let [{:keys [schema objects relationships] :as fixture}
        (fixture-for fixture-key)
        conn (datascript/create-conn)
        client (datascript/make-client
                conn {:recursive-traversal-limits limits})]
    (eacl/write-schema! client schema)
    (ds/transact! conn
                  (vec (map-indexed
                        (fn [index {:keys [id]}]
                          {:db/id (- (inc index)) :eacl/id id})
                        objects)))
    (doseq [batch (partition-all 500 relationships)]
      (eacl/create-relationships! client (vec batch)))
    {:fixture fixture :client client}))

(deftest queued-work-bounds-depth-not-cumulative-transitions-test
  ;; Operator-reported: a ~24k-result exhaustive count failed the public
  ;; :max-queued-work limit because it was mapped onto CUMULATIVE reducer
  ;; transitions. The public contract bounds instantaneous queue depth: a
  ;; deep chain takes many transitions while its stack stays shallow, so
  ;; a tight :max-queued-work must not fail it.
  (let [{:keys [fixture client]}
        (limited-client :folder-chain {:max-queued-work 25
                                       :max-derived-grants 100000
                                       :max-advanced-datoms 100000})
        query {:subject (get-in fixture [:principals :alice])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)}
        looked (count (:data (eacl/lookup-resources
                              client (assoc query :first 1000))))
        counted (:count (eacl/count-resources client query))]
    (is (pos? counted))
    (is (= looked counted)
        "the exhaustive count agrees with the lookup denotation")))

(deftest advanced-datoms-limit-binds-consumed-values-test
  (let [{:keys [fixture client]}
        (limited-client :explorer-acyclic {:max-advanced-datoms 3
                                           :max-derived-grants 100000
                                           :max-queued-work 100000})
        query {:subject (get-in fixture [:principals :super-user])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)
               :first 20}
        data (try (eacl/lookup-resources client query)
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo
                            :cljs cljs.core.ExceptionInfo) error
                    (ex-data error)))]
    (is (= :eacl.recursive-traversal/limit-exceeded (:eacl/error data)))
    (is (= :advanced-datoms (:limit-kind data))
        "consumed projection values surface under the public limit kind")))

(defn- adapter-opts
  [_conn extra]
  (select-keys
   (merge {:object-id->entid
           (fn [snapshot object-id]
             (ds/entid snapshot [:eacl/id object-id]))
           :entid->object-id
           (fn [snapshot internal-id]
             (:eacl/id (ds/entity snapshot internal-id)))}
          extra)
   datascript-backend/adapter-config-keys))

(def ^:private test-lineage
  {:source-scope
   {:backend :datascript :source-id "continuation-test" :branch nil}
   :source-lifecycle "continuation-test"})

(defn- identity-for-test-adapter
  [adapter]
  (merge
   {:backend :datascript
    :source-id "continuation-test"
    :branch nil
    :source-lifecycle "continuation-test"
    :basis-kind (backend/invoke adapter :basis-kind)
    :backend-snapshot-id (backend/invoke adapter :snapshot-id)}
   (backend/invoke adapter :native-revision)))

(deftest checkpoint-key-is-frame-scoped-not-revision-scoped-test
  (let [{:keys [fixture conn client]}
        (seeded-caching-client :folder-chain)
        opts (adapter-opts conn {:source-lifecycle "continuation-test"})
        root [(:resource-type fixture) (:permission fixture)]
        adapter-1 (datascript-backend/basis-adapter (ds/db conn) opts)
        identity-1 (identity-for-test-adapter adapter-1)
        plan-1 (sealed-plan/seal-plan adapter-1 root)
        key-at
        (fn [adapter identity plan]
          (let [request-proof-frame
                (proof-frame/request-frame
                 adapter {:basis-identity identity})
                frame
                (proof-frame/descriptor
                 (proof-frame/resolve!
                  request-proof-frame
                  (sealed-plan/relation-ids plan)))]
            (binding [v8/*request-lineage* test-lineage
                      v8/*request-frame* frame]
              (v8/checkpoint-key plan :forward :user 1 3))))
        key-1 (key-at adapter-1 identity-1 plan-1)
        _ (ds/transact! conn [{:eacl/id "unrelated-checkpoint-basis"}])
        adapter-2 (datascript-backend/basis-adapter (ds/db conn) opts)
        identity-2 (identity-for-test-adapter adapter-2)
        plan-2 (sealed-plan/seal-plan adapter-2 root)
        key-2 (key-at adapter-2 identity-2 plan-2)
        _ (eacl/create-relationship!
           client
           (get-in fixture [:principals :stranger])
           (:relation (first (:relationships fixture)))
           (get-in fixture [:reverse-resources :leaf]))
        adapter-3 (datascript-backend/basis-adapter (ds/db conn) opts)
        identity-3 (identity-for-test-adapter adapter-3)
        plan-3 (sealed-plan/seal-plan adapter-3 root)
        key-3 (key-at adapter-3 identity-3 plan-3)]
    (is (= 7 (count key-1)))
    (is (= test-lineage (first key-1)))
    (is (= (:fingerprint plan-1) (nth key-1 2)))
    (is (nil?
         (binding [v8/*request-lineage* nil
                   v8/*request-frame* {:schema-generation 1
                                       :dependency-stamp 1}]
           (v8/checkpoint-key plan-1 :forward :user 1 3)))
        "a frame without an authenticated lineage cannot address state")
    (is (nil?
         (binding [v8/*request-lineage* test-lineage
                   v8/*request-frame* (delay nil)]
           (v8/checkpoint-key plan-1 :forward :user 1 3)))
        "an incomplete proof disables checkpoint acceleration")
    (is (= key-1 key-2)
        "native revision changes do not separate equal-frame state")
    (is (not= key-2 key-3)
        "a closure-relation write changes the checkpoint frame")))

(deftest generation-plan-registry-survives-snapshot-rewraps-test
  ;; Two adapter wraps of distinct bases in one certified schema generation
  ;; are distinct JVM objects; the runtime registry must return one plan.
  (let [{:keys [conn]} (seed-fixture-client!
                        (fixture-for :explorer-acyclic))
        opts (adapter-opts conn {:source-lifecycle "plan-rewrap-test"})
        stable-plan v8/stable-plan
        registry (atom {})
        adapter-1 (datascript-backend/basis-adapter (ds/db conn) opts)
        identity-for
        (fn [adapter]
          (merge
           {:backend :datascript
            :source-id :plan-rewrap-test
            :branch nil
            :source-lifecycle "plan-rewrap-test"
            :basis-kind (backend/invoke adapter :basis-kind)
            :backend-snapshot-id (backend/invoke adapter :snapshot-id)}
           (backend/invoke adapter :native-revision)))
        generation-1 (backend/invoke adapter-1 :schema-generation)
        cache-1 (v8/schema-cache-for!
                 registry adapter-1 (identity-for adapter-1) generation-1)
        plan-1 (binding [v8/*schema-cache* cache-1]
                 (stable-plan adapter-1 [:server :view]))
        _ (ds/transact! conn [{:eacl/id "unrelated-new-basis"}])
        adapter-2 (datascript-backend/basis-adapter (ds/db conn) opts)
        generation-2 (backend/invoke adapter-2 :schema-generation)
        cache-2 (v8/schema-cache-for!
                 registry adapter-2 (identity-for adapter-2) generation-2)
        plan-2 (binding [v8/*schema-cache* cache-2]
                 (stable-plan adapter-2 [:server :view]))]
    (is (= (:schema-version cache-1) (:schema-version cache-2)))
    (is (identical? cache-1 cache-2)
        "two bases of one generation select the same derived cache")
    (is (identical? plan-1 plan-2)
        "re-wrapping the source at another basis must hit the plan registry")))

(deftest default-lifecycle-is-the-portable-cross-process-constant-test
  (let [{client-a :client} (seed-fixture-client!
                            (fixture-for :explorer-acyclic))
        {client-r :client} (seed-fixture-client!
                            (fixture-for :explorer-recursive))]
    (is (= "eacl/initial"
           (get-in client-a [:runtime :source-lifecycle])
           (get-in client-r [:runtime :source-lifecycle])))))

(deftest checkpoint-store-adopts-client-context-test
  (let [{:keys [conn]} (seed-fixture-client!
                        (fixture-for :explorer-acyclic))
        adapter (datascript-backend/basis-adapter
                 (ds/db conn) (adapter-opts conn {}))
        store (continuation/make-store {})
        context (continuation/private-context
                 store adapter :lookup-resources {:query {:q 1}}
                 {:request-lineage test-lineage})
        checkpoint-hit page/checkpoint-hit
        checkpoint {:ordinal 2 :boundary 42 :pending [7]
                    :state {:transitions 10 :admitted #{1 2} :stack []}}]
    (is (identical? context (v8/stable-checkpoints context))
        "the engine must accept the client's continuation context")
    (is (nil? (v8/stable-checkpoints (atom {})))
        "an unrecognized cache shape must degrade to replay")
    (is (nil?
         (v8/stable-checkpoints
          {:opaque-values? true
           :get (constantly nil)
           :put! (constantly false)}))
        "the obsolete partial callback contract must degrade to replay")
    (page/checkpoint-put! context [:k] checkpoint)
    (is (= checkpoint (checkpoint-hit context [:k] 2 42)))
    (is (nil? (checkpoint-hit context [:k] 3 42))
        "an ordinal mismatch must miss")
    (is (nil? (checkpoint-hit context [:k] 2 41))
        "a boundary mismatch must miss")
    (testing "an older checkpoint never replaces newer progress"
      (page/checkpoint-put!
       context [:k] (assoc-in checkpoint [:state :transitions] 5))
      (is (= 10 (get-in (checkpoint-hit context [:k] 2 42)
                        [:state :transitions]))))))

(deftest read-without-publication-can-read-but-not-write-checkpoints-test
  (let [{:keys [conn]} (seed-fixture-client!
                        (fixture-for :explorer-acyclic))
        adapter (datascript-backend/basis-adapter
                 (ds/db conn) (adapter-opts conn {}))
        store (continuation/make-store {})
        writable
        (continuation/private-context
         store adapter :lookup-resources {:query {:q 1}}
         {:request-lineage test-lineage})
        read-only
        (continuation/private-context
         store adapter :lookup-resources {:query {:q 1}}
         {:request-lineage test-lineage
          :populate-cache? false})]
    (is (= #{:required? :opaque-values? :peek :get :hit! :miss! :put!}
           (set (keys writable)))
        "the private context exposes only checkpoint operations")
    (is (true? ((:put! writable) :edge :stored 1)))
    (let [state-before @(:state store)]
      (is (false? ((:put! read-only) :edge :suppressed 1)))
      (is (= state-before @(:state store))
          "suppressed publication must not delete or replace retained state"))
    (is (= :stored ((:get read-only) :edge)))
    (is (= 1 (:puts (continuation/stats store)))
        "only the writable context publishes")))

(deftest population-disabled-leaves-no-tombstone-and-next-page-replays-test
  (let [{:keys [fixture client]} (seeded-caching-client :folder-chain)
        store (get-in client [:runtime :continuation-cache-store])
        query {:subject (get-in fixture [:principals :alice])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)
               :first 3}
        oracle (mapv :id (:data (eacl/lookup-resources
                                 client (assoc query :first 1000))))
        before (continuation/stats store)
        page-1 (eacl/lookup-resources
                client (assoc query :populate-cache? false))
        after-first (continuation/stats store)
        page-2 (eacl/lookup-resources
                client (assoc query :after
                              (get-in page-1 [:page-info :end-cursor])))
        after-second (continuation/stats store)]
    (is (= (:publications before) (:publications after-first))
        "publication control suppresses the checkpoint")
    (is (= (take 3 oracle) (mapv :id (:data page-1))))
    (is (= (take 3 (drop 3 oracle)) (mapv :id (:data page-2)))
        "the next page replays from the authenticated boundary")
    (is (= (get-in before [:miss-reasons :population-disabled] 0)
           (get-in after-second
                   [:miss-reasons :population-disabled] 0))
        "suppressed publication is not a destructive cache event")
    (is (> (get-in after-second [:miss-reasons :absent] 0)
           (get-in before [:miss-reasons :absent] 0))
        "the uncached next page observes an ordinary absence")))

(deftest checkpoint-miss-reason-telemetry-test
  (let [{:keys [conn]} (seed-fixture-client!
                        (fixture-for :explorer-acyclic))
        adapter (datascript-backend/basis-adapter
                 (ds/db conn) (adapter-opts conn {}))
        make-context
        (fn [store & [options]]
          (continuation/private-context
           store adapter :lookup-resources {:query {:q 1}}
           (merge {:request-lineage test-lineage} options)))
        checkpoint
        {:ordinal 2 :boundary 42 :pending [7]
         :state {:transitions 10 :admitted #{1 2} :stack []}}
        key-a [test-lineage {:schema-generation 1 :dependency-stamp 2}
               "plan-a" :forward :user 1 2]
        key-b (assoc key-a 2 "plan-b")]
    (testing "absent and boundary mismatch"
      (let [store (continuation/make-store {})
            context (make-context store)]
        (is (nil? (page/checkpoint-hit context key-a 2 42)))
        (page/checkpoint-put! context key-a checkpoint)
        (is (nil? (page/checkpoint-hit context key-a 3 42)))
        (let [stats (continuation/stats store)]
          (is (= 1 (get-in stats [:miss-reasons :absent])))
          (is (= 1 (get-in stats [:miss-reasons :boundary-mismatch]))))))
    (testing "plan mismatch"
      (let [store (continuation/make-store {})
            context (make-context store)]
        (page/checkpoint-put! context key-a checkpoint)
        (is (nil? (page/checkpoint-hit context key-b 2 42)))
        (is (= 1 (get-in (continuation/stats store)
                         [:miss-reasons :plan-mismatch])))))
    (testing "evicted"
      (let [store (continuation/make-store {:max-entries 1})
            context (make-context store)
            other-key (assoc key-a 5 99)]
        (page/checkpoint-put! context key-a checkpoint)
        (page/checkpoint-put! context other-key checkpoint)
        (is (nil? (page/checkpoint-hit context key-a 2 42)))
        (is (= 1 (get-in (continuation/stats store)
                         [:miss-reasons :evicted])))
        (is (nil? (page/checkpoint-hit context key-b 2 42)))
        (is (= 1 (get-in (continuation/stats store)
                         [:miss-reasons :absent]))
            "eviction removes the constant-time plan-family index")))
    (testing "overweight"
      (let [store (continuation/make-store
                   {:max-weight 8192 :max-entry-weight 3000})
            context (make-context store)
            heavy (-> checkpoint
                      (assoc-in [:state :transitions] 11)
                      (assoc-in [:state :admitted]
                                (set (range 20))))]
        (page/checkpoint-put! context key-a checkpoint)
        (let [state-before @(:state store)]
          (page/checkpoint-put! context key-a heavy)
          (is (= state-before @(:state store))
              "an overweight replacement preserves older valid progress"))
        (is (= checkpoint (page/checkpoint-hit context key-a 2 42)))
        (page/checkpoint-put! context key-b heavy)
        (is (nil? (page/checkpoint-hit context key-b 2 42)))
        (let [stats (continuation/stats store)]
          (is (= 1 (get-in stats [:miss-reasons :overweight])))
          (is (= 2 (:rejections stats)))
          (is (= 1 (:publications stats)))
          (is (zero? (:replacements stats)))
          (is (= 1 (:entries stats))))))
    (testing "population disabled"
      (let [store (continuation/make-store {})
            writable (make-context store)
            disabled (make-context store {:populate-cache? false})]
        (page/checkpoint-put! writable key-a checkpoint)
        (let [state-before @(:state store)]
          (page/checkpoint-put!
           disabled key-a (assoc-in checkpoint [:state :transitions] 11))
          (is (= state-before @(:state store))
              "a suppressed replacement performs no cache mutation"))
        (is (= checkpoint (page/checkpoint-hit disabled key-a 2 42))
            "a read-only request can still reuse retained progress")
        (let [stats (continuation/stats store)]
          (is (zero? (get-in stats [:miss-reasons :population-disabled] 0)))
          (is (= 1 (:publications stats)))
          (is (zero? (:replacements stats)))
          (is (= 1 (:entries stats))))))
    (testing "publication and replacement occupancy"
      (let [store (continuation/make-store {})
            context (make-context store)]
        (page/checkpoint-put! context key-a checkpoint)
        (let [order (:order @(:state store))]
          (is (= checkpoint
                 (page/checkpoint-hit context key-a 2 42)))
          (is (= order (:order @(:state store)))
              "a hot checkpoint hit performs no linear order maintenance"))
        (page/checkpoint-put!
         context key-a (assoc-in checkpoint [:state :transitions] 11))
        (let [stats (continuation/stats store)]
          (is (= 2 (:publications stats)))
          (is (= 1 (:replacements stats)))
          (is (= 1 (:entries stats)))
          (is (pos? (:weight stats))))))))

(deftest cursor-rejection-precedes-checkpoint-access-and-absence-replays-test
  (let [security-key "checkpoint-pipeline-test-key-0123456789"
        {:keys [fixture client]}
        (seeded-caching-client :folder-chain {:security-key security-key})
        store (get-in client [:runtime :continuation-cache-store])
        query {:subject (get-in fixture [:principals :alice])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)
               :first 3}
        oracle (mapv :id (:data (eacl/lookup-resources
                                 client (assoc query :first 1000))))
        page-1 (eacl/lookup-resources client query)
        cursor (get-in page-1 [:page-info :end-cursor])
        traffic (fn [stats] (select-keys stats [:hits :misses]))
        error-data
        (fn [f]
          (try (f) nil
               (catch #?(:clj clojure.lang.ExceptionInfo
                         :cljs cljs.core.ExceptionInfo) error
                 (ex-data error))))]
    (testing "an invalid cursor never consults private state"
      (let [before (traffic (continuation/stats store))
            error (error-data
                   #(eacl/lookup-resources
                     client (assoc query :after (str cursor "x"))))]
        (is (= :eacl.pagination/invalid-cursor (:type error)))
        (is (= before (traffic (continuation/stats store))))))
    (testing "a wrong-lineage cursor never consults the other client store"
      (let [{other :client}
            (seeded-caching-client
             :folder-chain {:security-key security-key})
            other-store (get-in other [:runtime :continuation-cache-store])
            before (traffic (continuation/stats other-store))
            error (error-data
                   #(eacl/lookup-resources
                     other (assoc query :after cursor)))]
        (is (= :eacl.pagination/invalid-cursor (:type error)))
        (is (= :source-scope (:reason error)))
        (is (= before (traffic (continuation/stats other-store))))))
    (testing "a missing accepted checkpoint replays without restarting"
      (continuation/clear! store)
      (let [before (continuation/stats store)
            page-2 (eacl/lookup-resources client (assoc query :after cursor))
            after (continuation/stats store)]
        (is (= (take 3 (drop 3 oracle)) (mapv :id (:data page-2))))
        (is (> (get-in after [:miss-reasons :absent] 0)
               (get-in before [:miss-reasons :absent] 0)))))
    (testing "a changed frame fails before checkpoint lookup"
      (let [fresh-page-1 (eacl/lookup-resources client query)
            fresh-cursor (get-in fresh-page-1 [:page-info :end-cursor])
            _ (eacl/create-relationship!
               client
               (get-in fixture [:principals :stranger])
               (:relation (first (:relationships fixture)))
               (get-in fixture [:reverse-resources :leaf]))
            before (traffic (continuation/stats store))
            error (error-data
                   #(eacl/lookup-resources
                     client (assoc query :after fresh-cursor)))]
        (is (= :eacl.pagination/stale-cursor (:type error)))
        (is (= :frame-changed (:reason error)))
        (is (= before (traffic (continuation/stats store))))))))

(deftest acyclic-pagination-needs-no-checkpoints-test
  ;; The keyset regime: an acyclic root paginates statelessly — exact
  ;; pages, zero continuation-store traffic (acyclic-keyset-pagination).
  (let [{:keys [fixture client]} (seeded-caching-client :explorer-acyclic)
        store (get-in client [:runtime :continuation-cache-store])
        query {:subject (get-in fixture [:principals :super-user])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)
               :first 5}
        one-shot (mapv :id (:data (eacl/lookup-resources
                                   client (assoc query :first 1000))))
        puts-before (:puts (continuation/stats store))
        walked
        (loop [q query acc []]
          (let [{:keys [data page-info]} (eacl/lookup-resources client q)
                acc (into acc (map :id) data)]
            (if (and (:has-next-page? page-info) (:end-cursor page-info)
                     (seq data))
              (recur (assoc q :after (:end-cursor page-info)) acc)
              acc)))]
    (is (= one-shot walked)
        "keyset pages compose exactly with no server-side state")
    (is (= puts-before (:puts (continuation/stats store)))
        "acyclic pagination publishes nothing to the continuation store")))

(deftest continuation-order-metadata-is-capacity-bounded-test
  (let [capacity 4
        store (continuation/make-store
               {:max-entries capacity
                :max-weight capacity
                :max-entry-weight 1})]
    (doseq [index (range 200)]
      (continuation/put! store :checkpoint [:scope :checkpoint index]
                         {:index index} 1))
    (let [{:keys [entries order order-index tombstones tombstone-order
                  tombstone-order-index]} @(:state store)]
      (is (= capacity (count entries)))
      (is (<= (- (count order) order-index) capacity))
      (is (<= (count order) (* 2 capacity)))
      (is (<= (count tombstones) capacity))
      (is (<= (- (count tombstone-order) tombstone-order-index)
              (* 2 capacity)))
      (is (<= (count tombstone-order) (* 2 capacity))))
    ;; Repeatedly refreshing the same rejected key used to filter the whole
    ;; tombstone vector on every publication attempt.
    (doseq [_ (range 200)]
      (continuation/put! store :checkpoint [:scope :checkpoint :overweight]
                         :rejected 2))
    (let [{:keys [tombstones tombstone-order tombstone-order-index]}
          @(:state store)]
      (is (<= (count tombstones) capacity))
      (is (<= (- (count tombstone-order) tombstone-order-index)
              (* 2 capacity)))
      (is (<= (count tombstone-order) (* 2 capacity))))))

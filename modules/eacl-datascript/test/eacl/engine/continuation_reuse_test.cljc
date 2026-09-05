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
            [eacl.cache :as cache]
            [eacl.cache.derived-schema :as derived-schema]
            [eacl.cache.standard-lru :as lru]
            [eacl.continuation :as continuation]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.engine.checkpoint-fixtures :as portable]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-page :as page]
            [eacl.engine.v8 :as v8]
            [eacl.proof-frame :as proof-frame]
            [eacl.request.counters :as request-counters]
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
  (let [{:keys [fixture client]} ;; Range reuse would serve page 2 from the retained segment; this
        ;; test exercises the checkpoint mechanism itself.
        (seeded-caching-client :folder-chain {:range-reuse false})
        store (get-in client [:runtime :continuation-cache-store])
        query {:subject (get-in fixture [:principals :alice])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)
               :first 5}
        ;; Checkpoints are keyed by walk and boundary, not page size, and
        ;; the store keeps the furthest boundary per walk; the oracle must
        ;; not occupy that slot.
        one-shot (mapv :id (:data (eacl/lookup-resources
                                   client (assoc query :first 1000
                                                 :populate-cache? false))))]
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

(deftest derived-window-on-a-recursive-plan-continues-from-the-checkpoint-test
  ;; A shorter window served from a recursive-plan page hands out a cursor
  ;; inside the retained segment; continuing from it is served from the
  ;; segment, and the first window that leaves the segment resumes the
  ;; checkpoint at the segment's end whatever page size produced it.
  (let [{:keys [fixture client]} (seeded-caching-client :folder-chain)
        store (get-in client [:runtime :continuation-cache-store])
        query {:subject (get-in fixture [:principals :alice])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)}
        ;; The oracle bypasses the cache so it seeds no segment.
        one-shot (mapv :id (:data (eacl/lookup-resources
                                   client (assoc query :first 1000 :cache? false))))
        page-5 (eacl/lookup-resources client (assoc query :first 5))
        derived (eacl/lookup-resources client (assoc query :first 3))
        inside (eacl/lookup-resources
                client (assoc query :first 1
                              :after (get-in derived [:page-info :end-cursor])))
        hits-before (:hits (continuation/stats store))
        ;; Results five to seven: the segment holds result five, the
        ;; remainder continues from the checkpoint at result five.
        leaving (eacl/lookup-resources
                 client (assoc query :first 3
                               :after (get-in inside [:page-info :end-cursor])))
        hits-after (:hits (continuation/stats store))]
    (is (> (count one-shot) 8) "the fixture must reach past the eighth result")
    (is (false? (:cached? page-5)))
    (is (true? (:cached? derived)))
    (is (= (take 3 one-shot) (mapv :id (:data derived))))
    (is (true? (:cached? inside)) "a window inside the segment is served from it")
    (is (= (take 1 (drop 3 one-shot)) (mapv :id (:data inside))))
    (is (pos? (:partial-hits (:range-reuse (datascript/cache-stats client))))
        "the window past the segment composes its tail with one continuation")
    (is (= (take 3 (drop 4 one-shot)) (mapv :id (:data leaving))))
    (is (> hits-after hits-before)
        "the continuation resumed the checkpoint at the segment's end, not a replay")))

(deftest page-size-change-continues-from-the-checkpoint-test
  (let [{:keys [fixture client]} (seeded-caching-client :folder-chain)
        store (get-in client [:runtime :continuation-cache-store])
        query {:subject (get-in fixture [:principals :alice])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)}
        one-shot (mapv :id (:data (eacl/lookup-resources
                                   client (assoc query :first 1000 :cache? false))))
        page-5 (eacl/lookup-resources client (assoc query :first 5))
        hits-before (:hits (continuation/stats store))
        page-7 (eacl/lookup-resources
                client (assoc query :first 7
                              :after (get-in page-5 [:page-info :end-cursor])))
        hits-after (:hits (continuation/stats store))]
    (is (= (take 7 (drop 5 one-shot)) (mapv :id (:data page-7))))
    (is (> hits-after hits-before)
        "a different page size resumes the same frontier")))

(deftest repeated-page-request-is-served-from-cache-test
  ;; Repeating the identical page request resolves the completed internal
  ;; answer and externalizes it against the same immutable snapshot.
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

(defn- measured-request
  [f]
  (let [ledger (request-counters/make-ledger)
        value (request-counters/call-with-ledger ledger f)]
    {:value value
     :work (request-counters/snapshot ledger)}))

(deftest next-previous-oscillation-uses-completed-answers-not-page-aliases-test
  (let [security-key "oscillation-cache-free-parity-0000"
        conn (datascript/create-conn)
        client (datascript/make-client conn {:security-key security-key})
        alice (eacl/spice-object :user "oscillation-alice")
        docs (mapv #(eacl/spice-object :document (str "oscillation-doc-" %))
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
    (let [oracle (datascript/make-client
                  conn {:security-key security-key
                        :cache cache/no-cache})
          query {:subject alice
                 :permission :view
                 :resource/type :document
                 :first 2}
          one-shot (mapv :id (:data (eacl/lookup-resources
                                     client (assoc query :first 1000))))
          page-1 (eacl/lookup-resources client query)
          next-query (assoc query :after
                            (get-in page-1 [:page-info :end-cursor]))
          page-2 (eacl/lookup-resources client next-query)
          previous-query (-> query
                             (dissoc :first)
                             (assoc :last 2
                                    :before
                                    (get-in page-2
                                            [:page-info :start-cursor])))
          previous-first (measured-request
                          #(eacl/lookup-resources client previous-query))
          previous-page (:value previous-first)
          oracle-previous (eacl/lookup-resources oracle previous-query)
          previous-hit (measured-request
                        #(eacl/lookup-resources client previous-query))
          next-again-query
          (assoc query :after
                 (get-in previous-page [:page-info :end-cursor]))
          next-again (measured-request
                      #(eacl/lookup-resources client next-again-query))
          previous-page-hit (:value previous-hit)
          page-2-hit (:value next-again)]
      (is (= 9 (count one-shot)))
      (is (= (take 2 one-shot) (mapv :id (:data page-1))
             (mapv :id (:data previous-page))
             (mapv :id (:data previous-page-hit))
             (mapv :id (:data oracle-previous))))
      (is (= (take 2 (drop 2 one-shot))
             (mapv :id (:data page-2))
             (mapv :id (:data page-2-hit))))
      (is (false? (:cached? previous-page))
          "the first reverse request is permitted one independent miss")
      (is (true? (:cached? previous-page-hit)))
      (is (true? (:cached? page-2-hit)))
      (is (= (:cache-basis page-1)
             (:cache-basis page-2)
             (:cache-basis previous-page)
             (:cache-basis previous-page-hit)
             (:cache-basis page-2-hit))
          "every oscillation stays on the exact selected snapshot")
      (is (= (select-keys (:page-info page-1)
                          [:has-next-page? :has-previous-page?])
             (select-keys (:page-info previous-page)
                          [:has-next-page? :has-previous-page?])
             (select-keys (:page-info previous-page-hit)
                          [:has-next-page? :has-previous-page?])
             (select-keys (:page-info oracle-previous)
                          [:has-next-page? :has-previous-page?])))
      (is (= (select-keys (:page-info page-2)
                          [:has-next-page? :has-previous-page?])
             (select-keys (:page-info page-2-hit)
                          [:has-next-page? :has-previous-page?])))
      (is (every? some?
                  (for [page [page-1 page-2 previous-page
                              previous-page-hit page-2-hit]
                        field [:start-cursor :end-cursor]]
                    (get-in page [:page-info field])))
          "every returned boundary remains a usable opaque cursor")
      (is (pos? (get-in previous-first [:work :commands]))
          "the permitted first reverse miss performs bounded page work")
      (is (zero? (get-in previous-hit [:work :commands]))
          "a repeated reverse request does not re-enter page evaluation")
      (is (zero? (get-in next-again [:work :commands]))
          "returning Next reuses its earlier completed internal answer"))))

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

(deftest flat-plan-lru-survives-snapshot-rewraps-test
  ;; Two adapter wraps of distinct bases in one certified schema generation
  ;; are distinct JVM objects; the flat derived-artifact LRU must return one plan.
  (let [{:keys [conn]} (seed-fixture-client!
                        (fixture-for :explorer-acyclic))
        opts (adapter-opts conn {:source-lifecycle "plan-rewrap-test"})
        stable-plan v8/stable-plan
        registry (derived-schema/store)
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
    (is (not (identical? cache-1 cache-2))
        "requests retain only stateless handles, not generation containers")
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
                        [:state :transitions]))))
    (testing "a later boundary wins even when reducer transitions are equal"
      (let [later (assoc checkpoint :ordinal 3 :boundary 43)]
        (page/checkpoint-put! context [:k] later)
        (is (= later (checkpoint-hit context [:k] 3 43)))))))

(deftest client-checkpoint-context-enforces-admission-count-cap-test
  (let [{:keys [conn]} (seed-fixture-client!
                        (fixture-for :explorer-acyclic))
        adapter (datascript-backend/basis-adapter
                 (ds/db conn) (adapter-opts conn {}))
        store (continuation/make-store {})
        context
        (assoc
         (continuation/private-context
          store adapter :lookup-resources {:query {:q 1}}
          {:request-lineage test-lineage})
         :max-entry-admissions 1)
        retained {:ordinal 2 :boundary 42 :pending []
                  :state {:transitions 10 :admitted #{1} :stack []}}
        oversized {:ordinal 3 :boundary 43 :pending []
                   :state {:transitions 11 :admitted #{1 2} :stack []}}]
    (page/checkpoint-put! context :retained retained)
    (is (= retained (page/checkpoint-hit context :retained 2 42))
        "the configured boundary remains retainable")
    (let [stats-before (continuation/stats store)]
      (is (nil? (page/checkpoint-put! context :oversized oversized)))
      (page/checkpoint-put! context :retained oversized)
      (is (= stats-before (continuation/stats store))
          "rejection performs no continuation LRU or telemetry mutation")
      (is (nil? (page/checkpoint-hit context :oversized 3 43)))
      (is (= retained (page/checkpoint-hit context :retained 2 42))
          "an oversized replacement cannot displace the resident checkpoint"))))

(deftest continuation-storage-keeps-the-full-semantic-scope-test
  (let [{:keys [conn]} (seed-fixture-client!
                        (fixture-for :explorer-acyclic))
        adapter (datascript-backend/basis-adapter
                 (ds/db conn) (adapter-opts conn {}))
        store (continuation/make-store {})
        query-a {:query {:q 1}}
        query-b {:query {:q 2}}
        context-a (continuation/private-context
                   store adapter :lookup-resources query-a
                   {:request-lineage test-lineage})
        context-b (continuation/private-context
                   store adapter :lookup-resources query-b
                   {:request-lineage test-lineage})
        checkpoint-a {:ordinal 2 :boundary 42 :pending [7]
                      :state {:transitions 5 :admitted #{1} :stack []}}
        checkpoint-b {:ordinal 3 :boundary 43 :pending [8]
                      :state {:transitions 6 :admitted #{2} :stack []}}]
    (page/checkpoint-put! context-a [:same-edge] checkpoint-a)
    (page/checkpoint-put! context-b [:same-edge] checkpoint-b)
    (is (= checkpoint-a (page/checkpoint-hit context-a [:same-edge] 2 42)))
    (is (= checkpoint-b (page/checkpoint-hit context-b [:same-edge] 3 43)))
    (is (= #{query-a query-b}
           (into #{}
                 (map (fn [[storage-key _]]
                        (get-in storage-key [2 1 0 6])))
                 (lru/entries (:storage store))))
        "ordinary keys retain the collision-checked scope, not only a digest")))

#?(:clj
   (deftest concurrent-checkpoint-publication-retains-newest-progress-test
     (let [{:keys [conn]} (seed-fixture-client!
                           (fixture-for :explorer-acyclic))
           adapter (datascript-backend/basis-adapter
                    (ds/db conn) (adapter-opts conn {}))
           store (continuation/make-store {})
           context (continuation/private-context
                    store adapter :lookup-resources {:query {:q 1}}
                    {:request-lineage test-lineage})
           base {:ordinal 2 :boundary 42 :pending [7]
                 :state {:transitions 5 :admitted #{1 2} :stack []}}
           older (assoc-in base [:state :transitions] 6)
           newer (assoc-in base [:state :transitions] 7)
           original-replace lru/replace-if!
           older-entered (promise)
           release-older (promise)
           older-publication (atom nil)]
       (page/checkpoint-put! context [:k] base)
       (with-redefs
        [lru/replace-if!
         (fn [storage key expected replacement]
           (when (= 6 (get-in replacement [:state :transitions]))
             (deliver older-entered true)
             @release-older)
           (original-replace storage key expected replacement))]
         (try
           (reset! older-publication
                   (future (page/checkpoint-put! context [:k] older)))
           (is (= true (deref older-entered 5000 ::timeout)))
           ;; Both candidates observed transition 5. The newer expected-value
           ;; replacement wins before the older CAS is released.
           (page/checkpoint-put! context [:k] newer)
           (deliver release-older true)
           (is (nil? (deref @older-publication 5000 ::timeout)))
           (is (= 7
                  (get-in (page/checkpoint-hit context [:k] 2 42)
                          [:state :transitions])))
           (let [stats (continuation/stats store)]
             (is (= 2 (:publications stats)))
             (is (= 1 (:replacements stats))))
           (finally
             (deliver release-older true)
             (some-> @older-publication future-cancel)))))))

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
          :populate-cache? false})
        checkpoint {:ordinal 2 :boundary 42 :pending [7]
                    :state {:transitions 10}}]
    (is (= #{:required? :opaque-values? :get :hit! :miss! :put!}
           (set (keys writable)))
        "the private context exposes only checkpoint operations")
    (is (true? ((:put! writable) :edge checkpoint)))
    (let [stats-before (continuation/stats store)]
      (is (false? ((:put! read-only)
                   :edge
                   (assoc-in checkpoint [:state :transitions] 11))))
      (is (= stats-before (continuation/stats store))
          "suppressed publication must not delete or replace retained state"))
    (is (= checkpoint ((:get read-only) :edge)))
    (is (= 1 (:puts (continuation/stats store)))
        "only the writable context publishes")))

(deftest continuation-store-failure-degrades-to-replay-test
  (let [{:keys [conn]} (seed-fixture-client!
                        (fixture-for :explorer-acyclic))
        adapter (datascript-backend/basis-adapter
                 (ds/db conn) (adapter-opts conn {}))
        store (continuation/make-store {})
        context (continuation/private-context
                 store adapter :lookup-resources {:query {:q 1}}
                 {:request-lineage test-lineage})
        checkpoint {:ordinal 2 :boundary 42 :pending []
                    :state {:transitions 10}}]
    (with-redefs [lru/lookup!
                  (fn [_ _]
                    (throw (ex-info "lookup failed" {})))
                  lru/peek-entry
                  (fn [_ _]
                    (throw (ex-info "publication inspection failed" {})))
                  lru/put-if-absent!
                  (fn [_ _ _]
                    (throw (ex-info "publication failed" {})))]
      (is (nil? ((:get context) :edge)))
      (is (false? ((:put! context) :edge checkpoint))))
    (is (= 2 (:errors (continuation/stats store))))))

(deftest continuation-touch-failure-keeps-held-checkpoint-test
  (let [{:keys [conn]} (seed-fixture-client!
                        (fixture-for :explorer-acyclic))
        adapter (datascript-backend/basis-adapter
                 (ds/db conn) (adapter-opts conn {}))
        store (continuation/make-store {})
        context (continuation/private-context
                 store adapter :lookup-resources {:query {:q 1}}
                 {:request-lineage test-lineage})
        checkpoint {:ordinal 2 :boundary 42 :pending []
                    :state {:transitions 10}}]
    (page/checkpoint-put! context :edge checkpoint)
    (with-redefs [lru/hit-if-value!
                  (fn [_ _ _]
                    (throw (ex-info "touch failed" {})))]
      (is (= checkpoint (page/checkpoint-hit context :edge 2 42))
          "the authenticated held value remains usable when recency fails"))
    (is (= 1 (:errors (continuation/stats store))))))

(deftest population-disabled-leaves-no-tombstone-and-next-page-replays-test
  (let [{:keys [fixture client]} (seeded-caching-client :folder-chain {:range-reuse false})
        store (get-in client [:runtime :continuation-cache-store])
        query {:subject (get-in fixture [:principals :alice])
               :permission (:permission fixture)
               :resource/type (:resource-type fixture)
               :first 3}
        oracle (mapv :id (:data (eacl/lookup-resources
                                 client (assoc query :first 1000
                                               :populate-cache? false))))
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

(deftest checkpoint-miss-and-lru-telemetry-test
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
    (testing "a different plan is an ordinary absent key"
      (let [store (continuation/make-store {})
            context (make-context store)]
        (page/checkpoint-put! context key-a checkpoint)
        (is (nil? (page/checkpoint-hit context key-b 2 42)))
        (is (= 1 (get-in (continuation/stats store)
                         [:miss-reasons :absent])))))
    (testing "evicted"
      (let [store (continuation/make-store {:max-entries 1})
            context (make-context store)
            other-key (assoc key-a 5 99)]
        (page/checkpoint-put! context key-a checkpoint)
        (page/checkpoint-put! context other-key checkpoint)
        (let [settled (continuation/stats store)
              resident-results
              [(page/checkpoint-hit context key-a 2 42)
               (page/checkpoint-hit context other-key 2 42)]]
          (is (= 1 (:entries settled)))
          (is (= 1 (:evictions settled)))
          (is (= 1 (count (filter some? resident-results)))
              "bounded storage retains one candidate without promising which cold victim")
          (is (= 1 (:hits (continuation/stats store)))))
        (is (nil? (page/checkpoint-hit context key-b 2 42)))
        (is (= 2 (get-in (continuation/stats store)
                         [:miss-reasons :absent]))
            "retention policy does not keep per-key tombstones")))
    (testing "an accepted hot checkpoint survives cold churn"
      (let [store (continuation/make-store {:max-entries 16})
            context (make-context store)]
        (doseq [seed (range 15)]
          (page/checkpoint-put!
           context (assoc key-a 2 (str "plan-seed-" seed)) checkpoint))
        (page/checkpoint-put! context key-a checkpoint)
        ;; Caffeine's Window TinyLFU policy combines frequency and recency and
        ;; need not select the strict-LRU cold victim. The CLJS implementation
        ;; is true LRU, but both policies must retain a repeatedly accepted hot
        ;; checkpoint while one-hit candidates churn through bounded storage.
        (dotimes [candidate 100]
          (dotimes [_ 10]
            (page/checkpoint-hit context key-a 2 42))
          (page/checkpoint-put!
           context (assoc key-a 2 (str "plan-c-" candidate)) checkpoint))
        (is (= checkpoint (page/checkpoint-hit context key-a 2 42)))
        (let [stats (continuation/stats store)]
          (is (= 16 (:entries stats)))
          (is (= 1001 (:hits stats)))
          (is (= 116 (:puts stats)))
          (is (= 116 (:publications stats)))
          (is (= 100 (:evictions stats))))))
    (testing "a rejected checkpoint boundary does not record a cache hit"
      (let [store (continuation/make-store {:max-entries 2})
            context (make-context store)
            key-c (assoc key-a 2 "plan-c")]
        (page/checkpoint-put! context key-a checkpoint)
        (page/checkpoint-put! context key-b checkpoint)
        (is (nil? (page/checkpoint-hit context key-a 3 42)))
        (is (= 1 (get-in (continuation/stats store)
                         [:miss-reasons :boundary-mismatch])))
        (page/checkpoint-put! context key-c checkpoint)
        ;; Window TinyLFU chooses by frequency and recency rather than exposing
        ;; a strict-LRU victim. The miss metric proves no accepted cache hit.
        (is (= 2 (:entries (continuation/stats store))))))
    (testing "a stale publication offer is not retention-policy usage"
      (let [store (continuation/make-store {:max-entries 2})
            context (make-context store)
            key-c (assoc key-a 2 "plan-c")]
        (page/checkpoint-put! context key-a checkpoint)
        (page/checkpoint-put! context key-b checkpoint)
        (let [before (continuation/stats store)]
          (page/checkpoint-put!
           context key-a (assoc-in checkpoint [:state :transitions] 9))
          (let [after (continuation/stats store)]
            (is (= (:publications before) (:publications after)))
            (is (= (:replacements before) (:replacements after)))
            (is (= (inc (:puts before)) (:puts after)))))
        (page/checkpoint-put! context key-c checkpoint)
        (is (= 2 (:entries (continuation/stats store))))
        (is (= 1 (:evictions (continuation/stats store))))))
    (testing "population disabled"
      (let [store (continuation/make-store {})
            writable (make-context store)
            disabled (make-context store {:populate-cache? false})]
        (page/checkpoint-put! writable key-a checkpoint)
        (let [stats-before (continuation/stats store)]
          (page/checkpoint-put!
           disabled key-a (assoc-in checkpoint [:state :transitions] 11))
          (is (= stats-before (continuation/stats store))
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
        (is (= checkpoint
               (page/checkpoint-hit context key-a 2 42)))
        (page/checkpoint-put!
         context key-a (assoc-in checkpoint [:state :transitions] 11))
        (let [stats (continuation/stats store)]
          (is (= 2 (:publications stats)))
          (is (= 1 (:replacements stats)))
          (is (= 1 (:entries stats))))))))

(deftest continuation-telemetry-disablement-retains-storage-only-test
  (let [{:keys [conn]} (seed-fixture-client!
                        (fixture-for :explorer-acyclic))
        adapter (datascript-backend/basis-adapter
                 (ds/db conn) (adapter-opts conn {}))
        store (continuation/make-store {:max-entries 2 :telemetry? false})
        context (continuation/private-context
                 store adapter :lookup-resources {:query {:q 1}}
                 {:request-lineage test-lineage})
        checkpoint {:ordinal 2 :boundary 42 :pending []
                    :state {:transitions 10 :admitted #{}}}
        counters [:hits :misses :puts :publications
                  :replacements :evictions :errors]]
    (page/checkpoint-put! context :edge checkpoint)
    (is (= checkpoint (page/checkpoint-hit context :edge 2 42)))
    (is (nil? (page/checkpoint-hit context :edge 3 42)))
    (is (nil? (page/checkpoint-hit context :absent 2 42)))
    (let [stats (continuation/stats store)]
      (is (false? (:telemetry-enabled? stats)))
      (is (= 1 (:entries stats)) "LRU retention remains enabled")
      (is (every? zero? (map stats counters))
          "disabled telemetry performs no counter mutations")
      (is (empty? (:miss-reasons stats)))
      (is (empty? (:by-kind stats))))))

(deftest outer-cache-telemetry-switch-reaches-continuation-store-test
  (let [{:keys [client]}
        (seeded-caching-client
         :explorer-acyclic
         {:cache {:telemetry? false}})
        store (get-in client [:runtime :continuation-cache-store])]
    (is (continuation/store? store))
    (is (false? (:telemetry-enabled? (continuation/stats store))))))

(deftest retired-continuation-weight-options-are-rejected-test
  (is (= 1024 (:max-entries (continuation/stats (continuation/make-store))))
      "the standalone continuation constructor shares the public cache default")
  (doseq [options [{:max-weight 4096}
                   {:max-entry-weight 1024}]]
    (let [error
          (try
            (continuation/make-store options)
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo) exception
              (ex-data exception)))]
      (is (= :eacl/invalid-config (:type error)))
      (is (= (set (keys options)) (:unknown-options error))))))

(deftest cursor-rejection-precedes-checkpoint-access-and-absence-replays-test
  (let [security-key "checkpoint-pipeline-test-key-0123456789"
        {:keys [fixture client]}
        (seeded-caching-client :folder-chain {:security-key security-key :range-reuse false})
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
             :folder-chain {:security-key security-key :range-reuse false})
            other-store (get-in other [:runtime :continuation-cache-store])
            before (traffic (continuation/stats other-store))
            error (error-data
                   #(eacl/lookup-resources
                     other (assoc query :after cursor)))]
        (is (= :eacl.pagination/invalid-cursor (:type error)))
        (is (= :source-scope (:reason error)))
        (is (= before (traffic (continuation/stats other-store))))))
    (testing "a missing accepted checkpoint replays without restarting"
      (lru/clear! (:storage store))
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

(deftest checkpoint-state-is-partitioned-and-validated-by-series-key
  (let [{:keys [conn]} (seed-fixture-client! (fixture-for :explorer-acyclic))
        adapter (datascript-backend/basis-adapter (ds/db conn) (adapter-opts conn {}))
        store (continuation/make-store {})
        context (fn [read-kid mint-kid]
                  (continuation/private-context
                   store adapter :lookup-resources {:query {:q 1}}
                   {:request-lineage test-lineage :security-kid read-kid :minting-security-kid mint-kid}))
        old (context :old :old) new (context :new :new) transition (context :old :new)
        checkpoint {:ordinal 2 :boundary 42 :pending [7] :state {:transitions 10 :admitted #{1 2} :stack []}}]
    ((:put! old) [:k] checkpoint)
    (is (= checkpoint (dissoc ((:get old) [:k]) :security-kid)))
    (is (nil? ((:get new) [:k])))
    (is (= checkpoint (dissoc ((:get transition) [:k]) :security-kid)))
    ((:put! transition) [:k] checkpoint)
    (is (= checkpoint (dissoc ((:get new) [:k]) :security-kid)))
    (is (= #{:old :new} (set (map (comp :security-kid second) (lru/entries (:storage store))))))
    (let [[key value] (first (filter #(= :old (:security-kid (second %))) (lru/entries (:storage store))))]
      (lru/replace-if! (:storage store) key value (assoc value :security-kid :new))
      (is (nil? ((:get old) [:k]))))
    (is (= 1 (get-in (continuation/stats store) [:miss-reasons :security-key-mismatch])))))

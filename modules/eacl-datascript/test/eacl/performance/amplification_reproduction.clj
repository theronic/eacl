(ns eacl.performance.amplification-reproduction
  "DataScript-backed deterministic reproduction probes for the v8 performance audit.

  These probes report logical counters and structural allocation proxies. They
  are not release benchmarks and do not turn elapsed time into a correctness
  claim."
  (:require [clojure.set :as set]
            [datascript.core :as ds]
            [eacl.authorization.filters :as authorization-filters]
            [eacl.backend.direct-membership :as direct]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.cache.derived-schema :as derived-schema]
            [eacl.cache.key :as cache-key]
            [eacl.cache.standard-lru :as standard-lru]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.least-path :as least-path]
            [eacl.engine.v8 :as engine]
            [eacl.execution :as execution]
            [eacl.engine.stable-reducer :as stable]
            [eacl.proof-frame :as proof-frame]
            [eacl.relationships.filters :as relationship-filters]
            [eacl.request.context :as request-context]
            [eacl.request.counters :as request-counters]
            [eacl.schema.errors :as schema-errors]
            [eacl.schema.expression-persistence :as expression-persistence]
            [eacl.subproblem-cache :as subproblem]
            [eacl.test-support.repo :as repo]))

(def source-base "e137dc55512d4eeebcc31cfbe5087d61ab04465b")
(def classpath-sha256
  "0fe14955538600e6940ffdd2c9cb63d993471117ea45115ca8ac0e53a14f48b5")

(defn- private-function
  [namespace symbol]
  (or (some-> (ns-resolve namespace symbol) deref)
      (throw (ex-info "Missing private reproduction target."
                      {:namespace namespace :symbol symbol}))))

(def ^:private relation-rule
  {:rule :relation
   :node [:document :view]
   :resource-type :document
   :permission :view
   :relation-eid 101
   :subject-type :user
   :ordinal 0
   :rank 1})

(def ^:private direct-plan
  {:root [:document :view]
   :indexes {:forward-seeds {:user [relation-rule]}
             :forward-consumers {}
             :reverse-rules {[:document :view] [relation-rule]}}})

(defn- bounded-fetch
  [value-count]
  (fn [{:keys [bound-eid limit]}]
    (let [start (inc (or bound-eid -1))
          stop (min value-count (+ start limit))]
      (vec (range start stop)))))

(defn- stable-run
  [value-count options]
  (stable/run-forward
   (merge {:fetch-fn (bounded-fetch value-count)
           :plan direct-plan
           :subject-type :user
           :subject-eid 1
           :target value-count}
          options)))

(defn- least-path-limit-probe
  [backend-width]
  (let [adapter-options (atom nil)
        backend-realized (atom 0)
        direct-fetch (least-path/adapter-fetch-fn ::eager-adapter)]
    (with-redefs
     [backend/invoke
      (fn [_ operation & args]
        (let [options (last args)
              ;; Model an eager backend that honors the option it receives.
              ;; On the baseline path :limit is absent and the complete
              ;; available width is materialized; on the candidate path the
              ;; forwarded descriptor limit bounds work before realization.
              eager-values (vec (range (min backend-width
                                            (or (:limit options)
                                                backend-width))))]
          (reset! adapter-options options)
          (reset! backend-realized (count eager-values))
          (when-not (= :subject->resources operation)
            (throw (ex-info "Unexpected reproduction operation."
                            {:operation operation})))
          eager-values))]
      (let [context
            (least-path/make-context
             {:fetch-fn direct-fetch
              :physical-chunk-size 1
              :max-commands 4
              :max-values 4})
            descriptor
            {:operation :subject->resources
             :subject-type :user
             :subject-eid 1
             :relation-eid 101
             :resource-type :document
             :bound-eid nil
             :limit 1
             :direction :asc}
            values ((:fetch! context) descriptor)]
        {:route :least-path
         :descriptor-limit 1
         :adapter-options @adapter-options
         :adapter-received-limit (:limit @adapter-options)
         :backend-eager-values-realized @backend-realized
         :logical-values-returned (count values)
         :logical-counters @(:counters context)
         :stable-reducer-counters :not-applicable}))))

(defn- stable-structural-probe
  [value-count]
  (let [original-distinct @#'clojure.core/distinct
        original-subvec @#'clojure.core/subvec
        distinct-widths (atom [])
        suffix-input-widths (atom [])
        result
        (with-redefs-fn
          {#'clojure.core/distinct
           (fn [values]
             (swap! distinct-widths conj (count values))
             (original-distinct values))
           #'clojure.core/subvec
           (fn [values & bounds]
             (swap! suffix-input-widths conj (count values))
             (apply original-subvec values bounds))}
          #(stable-run value-count
                       {:physical-chunk-size 64
                        :sidecar-cap 1
                        :result-sink :count
                        :target stable/exhaustion-target}))]
    {:route :stable-exact-count
     :discovered (:discovered result)
     :retained-results (count (:results result))
     :completion-distinct-input-width (apply max 0 @distinct-widths)
     :completion-distinct-call-widths @distinct-widths
     :suffix-view-calls (count @suffix-input-widths)
     :suffix-view-source-elements
     (reduce + 0 @suffix-input-widths)
     :raw-counters
     (select-keys result
                  [:admissions :transitions :commands :fetched-values
                   :maximum-stack :maximum-sidecar-buffers
                   :maximum-sidecar-values :discovered])}))

(defn- routed-vector-recopy-probe
  []
  (let [source-vector [1 2 3]
        fetch-values
        (private-function 'eacl.engine.stable-reducer 'fetch-values)
        [_ stable-values _]
        (fetch-values
         {:fetch-fn (constantly source-vector)
          :commands 0 :max-commands 4
          :fetched-values 0 :max-values 4
          :physical-chunk-size 3}
         {:operation :subject->resources}
         nil)
        least-context
        (least-path/make-context
         {:fetch-fn (constantly source-vector)
          :physical-chunk-size 3 :max-commands 4 :max-values 4})
        least-values
        ((:fetch! least-context)
         {:operation :subject->resources :limit 3})]
    {:physical-boundary-value-type (str (type source-vector))
     :stable-reused-identically? (identical? source-vector stable-values)
     :least-path-reused-identically? (identical? source-vector least-values)
     :equivalent-vector-width (count source-vector)}))

(defn- stable-sidecar-churn-probe
  [touches capacity]
  (let [retain-buffer
        (private-function 'eacl.engine.stable-reducer 'retain-buffer)
        original-vals @#'clojure.core/vals
        original-remove @#'clojure.core/remove
        maximum-scan-widths (atom [])
        recency-scan-widths (atom [])
        values (vec (range 8))
        initial
        {:sidecar-cap capacity
         :sidecar (into {} (map (fn [key]
                                  [key {:values values
                                        :index 0
                                        :generation (inc key)
                                        :more-physical? true}]))
                        (range capacity))
         :sidecar-order (mapv (fn [key] [key (inc key)])
                              (range capacity))
         :sidecar-order-index 0
         :sidecar-clock capacity
         :current-sidecar-values (* capacity (count values))
         :maximum-sidecar-buffers capacity
         :maximum-sidecar-values (* capacity (count values))}
        final
        (with-redefs-fn
          {#'clojure.core/vals
           (fn [value]
             (swap! maximum-scan-widths conj (count value))
             (original-vals value))
           #'clojure.core/remove
           (fn [predicate value]
             (swap! recency-scan-widths conj (count value))
             (original-remove predicate value))}
          #(loop [state initial
                  remaining touches]
             (if (zero? remaining)
               state
               (recur (retain-buffer state 0 values 0 true)
                      (dec remaining)))))]
    {:touches touches
     :capacity capacity
     :maximum-rescan-calls (count @maximum-scan-widths)
     :maximum-rescan-entry-visits (reduce + 0 @maximum-scan-widths)
     :recency-filter-calls (count @recency-scan-widths)
     :recency-filter-entry-visits (reduce + 0 @recency-scan-widths)
     :final-retained-buffers (count (:sidecar final))
     :final-maximum-retained-values (:maximum-sidecar-values final)}))

(defn- continuation-churn-probe
  [publications capacity]
  ;; Continuation publication is deliberately reachable only through its
  ;; validated private context. This storage-only probe therefore exercises
  ;; the shared standard-LRU boundary directly.
  (let [store (standard-lru/store capacity)]
    (doseq [index (range publications)]
      (standard-lru/put-if-absent!
       store
       [:scope :checkpoint [index 1 2 3 4 5 6]]
       {:index index}))
    {:publications publications
     :capacity capacity
     :retention-policy :standard-lru
     :resident-entries (standard-lru/entry-count store)
     :final-stats {:entries (standard-lru/entry-count store)
                   :max-entries capacity}}))

(defn- scheduler-transient-probe
  [transitions successors]
  (let [states
        (vec
         (repeatedly
          transitions
          #(hash-map :stack []
                     :admitted (transient #{})
                     :admissions 0
                     :max-admissions 100
                     :max-stack 100
                     :maximum-stack 0)))
        item {:kind :grant
              :rule {:node [:document :view]}
              :resource-eid 7}
        new-work (if (zero? successors) [] [item])
        original-transient @#'clojure.core/transient
        transient-calls (atom 0)]
    (with-redefs-fn
      {#'clojure.core/transient
       (fn [value]
         (swap! transient-calls inc)
         (original-transient value))}
      #(doseq [state states]
         (stable/schedule state nil new-work)))
    {:transitions transitions
     :successors successors
     :batch-local-transient-allocations @transient-calls}))

(defn- allocation-bean
  []
  (let [bean (java.lang.management.ManagementFactory/getThreadMXBean)]
    (when (instance? com.sun.management.ThreadMXBean bean)
      (let [bean ^com.sun.management.ThreadMXBean bean]
        (when (.isThreadAllocatedMemorySupported bean)
          (when-not (.isThreadAllocatedMemoryEnabled bean)
            (.setThreadAllocatedMemoryEnabled bean true))
          bean)))))

(defn- measured-loop
  "Reports same-thread wall-clock and allocated-byte observations.

  These values are raw runtime observations, never semantic counters. Every
  caller also records a same-shape control so later analysis can reject an
  unsupported or uncalibrated allocation lane instead of treating it as zero."
  [iterations f]
  (let [bean (allocation-bean)
        thread-id (.getId (Thread/currentThread))
        allocated-before (when bean (.getThreadAllocatedBytes bean thread-id))
        started (System/nanoTime)]
    (dotimes [index iterations]
      (f index))
    (let [elapsed (- (System/nanoTime) started)
          allocated-after (when bean
                            (.getThreadAllocatedBytes bean thread-id))]
      {:iterations iterations
       :elapsed-nanos elapsed
       :allocated-bytes
       (when (and allocated-before allocated-after)
         (- allocated-after allocated-before))
       :allocation-capability
       (if bean :thread-allocated-bytes :unsupported)})))

(defn- counter-increment-probe
  [iterations]
  (let [require-counter-var
        (ns-resolve 'eacl.request.counters 'require-counter!)
        original-require @require-counter-var
        counter-index-var
        (ns-resolve 'eacl.request.counters 'counter-index)
        counter-index @counter-index-var
        structural-counts (long-array 2)
        counting-index
        (reify clojure.lang.ILookup
          (valAt [_ key]
            (aset-long structural-counts 1
                       (inc (aget structural-counts 1)))
            (get counter-index key))
          (valAt [_ key not-found]
            (aset-long structural-counts 1
                       (inc (aget structural-counts 1)))
            (get counter-index key not-found)))
        structural-ledger (request-counters/make-ledger)]
    (binding [request-counters/*ledger* structural-ledger]
      (with-redefs-fn
        {require-counter-var
         (fn [counter]
           (aset-long structural-counts 0
                      (inc (aget structural-counts 0)))
           (original-require counter))
         counter-index-var counting-index}
        #(dotimes [_ iterations]
           (request-counters/add-commands!))))
    ;; Warm the exact uninstrumented path before recording the raw lane.
    (let [warm-ledger (request-counters/make-ledger)]
      (binding [request-counters/*ledger* warm-ledger]
        (dotimes [_ 10000]
          (request-counters/add-commands!))))
    (let [ledger (request-counters/make-ledger)
          observed
          (binding [request-counters/*ledger* ledger]
            (measured-loop iterations
                           (fn [_]
                             (request-counters/add-commands!))))
          control (measured-loop iterations (fn [_] nil))]
      {:classification :mandatory-semantic-meter
       :namespace-documentation :mandatory-semantic-meter-correctly-documented
       :counter :commands
       :dynamic-key-validations (aget structural-counts 0)
       :dynamic-index-lookups (aget structural-counts 1)
       :preindexed-slot-increments iterations
       :exact-final-value (:commands (request-counters/snapshot ledger))
       :raw-runtime-observation observed
       :same-shape-control control})))

(defn- required-adapter-operations
  [overrides]
  (merge
   (into {}
         (map
          (fn [operation]
            [operation
             (case operation
               :snapshot-id (constantly {:database-id ::database :basis-t 7})
               :basis-kind (constantly :ordinary)
               :native-revision
               (constantly {:revision 7 :exact-locator 7})
               :order-hint (constantly 7)
               :exact-locator (constantly 7)
               :relation-defs (constantly [])
               :permission-defs (constantly [])
               :permission-expression (constantly nil)
               :subject->resources (constantly [])
               :resource->subjects (constantly [])
               :direct-match? (constantly false)
               :all-permission-nodes (constantly #{})
               (constantly nil))])
          backend/required-snapshot-operations))
   overrides))

(defn- native-membership-adapter
  [native-widths]
  (backend/make-adapter
   {:id :amplification-native-membership
    :capabilities
    {:direct-membership-batch
     #{backend/direct-membership-batch-capability}}
    :operator-physical-policy
    {:id :amplification-native-policy-v1
     :parameters {:maximum-width
                  backend/maximum-direct-membership-batch-width}}
    :runtime-guards? true
    :operations
    (required-adapter-operations
     {:direct-match-many?
      (fn [{:keys [candidates]}]
        (swap! native-widths conj (count candidates))
        (mapv (comp even? second) candidates))})}))

(defn- native-membership-normalization-probe
  [candidate-count]
  (let [descriptor {:subject-type :user
                    :subject-eid 1
                    :relation-eid 2
                    :resource-type :document}
        probes
        (mapv (fn [eid]
                {:direction :forward
                 :descriptor descriptor
                 :candidate [:document eid]})
              (range candidate-count))
        native-widths (atom [])
        adapter (native-membership-adapter native-widths)
        normalize-var #'direct/normalize-request
        original-normalize @normalize-var
        normalized-widths (atom [])
        results
        (with-redefs-fn
          {normalize-var
           (fn [request]
             (swap! normalized-widths conj (count (:candidates request)))
             (original-normalize request))}
          #(direct/dispatch adapter probes))]
    {:candidate-count candidate-count
     :normalization-calls (count @normalized-widths)
     :candidate-validations (reduce + 0 @normalized-widths)
     :singleton-normalizations
     (count (filter #{1} @normalized-widths))
     :group-normalization-widths
     (vec (remove #{1} @normalized-widths))
     :native-adapter-widths @native-widths
     :native-adapter-calls (count @native-widths)
     :aligned-result-count (count results)
     :all-results-boolean? (every? boolean? results)}))

(def ^:private reproduction-basis-identity
  {:backend :amplification-request-context
   :source-id ::source
   :branch nil
   :source-lifecycle ::lifecycle
   :basis-kind :ordinary
   :revision 7
   :exact-locator 7
   :backend-snapshot-id {:database-id ::database :basis-t 7}})

(defn- request-context-adapter
  []
  (backend/make-adapter
   {:id :amplification-request-context
    :capabilities
    (assoc backend/empty-capabilities
           :cache-proofs #{:ordered-generations})
    :traversal-execution backend/strict-sequential-traversal-execution
    :operations
    (required-adapter-operations
     {:schema-generation (constantly 3)
      :proof-frame
      (fn [relation-ids]
        (mapv (fn [relation-id] [relation-id 2]) relation-ids))})}))

(defn- make-reproduction-context
  [adapter]
  (request-context/make-context
   {:runtime {:derived-schema-caches (derived-schema/store)}
    :adapter adapter
    :basis-identity reproduction-basis-identity
    :contract (execution/normalize {} :check-permission {})}))

(defn- request-context-construction-probe
  [constructions]
  (let [adapter (request-context-adapter)
        state-of (private-function 'eacl.request.context 'state-of)
        backend-calls (atom {})
        totals (long-array 2)
        observation
        (binding [backend/*backend-op-stats* backend-calls]
          (measured-loop
           constructions
           (fn [_]
             (let [context (make-reproduction-context adapter)
                   state (state-of context)]
               (when (realized? (:memos-delay state))
                 (aset-long totals 0 (inc (aget totals 0))))
               (when (realized? (:proof-frame-delay state))
                 (aset-long totals 1 (inc (aget totals 1))))
               (request-context/close! context)))))]
    {:constructions constructions
     :proof-frames-constructed (aget totals 1)
     :memo-atoms-constructed (aget totals 0)
     :backend-calls-before-first-use @backend-calls
     :raw-runtime-observation observation}))

(defn- completed-hit-mutation-probe
  [hits]
  (let [store (subproblem/store
               {:denotation-max-entries 64
                :answer-max-entries 64
                :telemetry? false})
        storage-key
        (cache-key/exact-answer-key
         {:tier :answer
          :source-lifecycle :amplification-probe
          :abi :amplification-probe-v2
          :semantic :hot
          :reuse :exact-probe})
        options {:valid? boolean?}
        _ (subproblem/publish! store :answer storage-key options true)
        state-atom (get-in store [:tiers :answer :state])
        original-compare-and-set! @#'clojure.core/compare-and-set!
        state-cas-attempts (long-array 1)
        observation
        (with-redefs-fn
          {#'clojure.core/compare-and-set!
           (fn [target old-value new-value]
             (when (identical? target state-atom)
               (aset-long
                state-cas-attempts 0 (inc (aget state-cas-attempts 0))))
             (original-compare-and-set! target old-value new-value))}
          #(measured-loop
            hits
            (fn [_]
              (when-not (:cached?
                         (subproblem/lookup!
                          store :answer storage-key))
                (throw (ex-info "Expected resident hit." {}))))))
        stats (subproblem/stats store)]
    {:hits hits
     :lru-touch-cas-attempts (aget state-cas-attempts 0)
     :optional-telemetry-mutations 0
     :telemetry-disable-switch :enabled
     :publication-races (:publication-races stats)
     :final-hit-count (:hits stats)
     :final-entry-count (get-in stats [:tiers :answer :entries])
     :raw-runtime-observation observation}))

(defn- independent-miss-probe
  [request-count]
  (let [store (subproblem/store
               {:denotation-max-entries 64
                :answer-max-entries 64})
        storage-key
        (cache-key/exact-answer-key
         {:tier :answer
          :source-lifecycle :amplification-probe
          :abi :amplification-probe-v2
          :semantic :cold
          :reuse :exact-probe})
        options {:valid? integer?}
        entered (java.util.concurrent.CountDownLatch. request-count)
        release (java.util.concurrent.CountDownLatch. 1)
        computations (java.util.concurrent.atomic.AtomicLong.)
        workers
        (mapv
         (fn [request-index]
           (future
             (if-let [hit (subproblem/lookup! store :answer storage-key)]
               hit
               (let [value (do
                             (.incrementAndGet computations)
                             (.countDown entered)
                             (.await release)
                             request-index)
                     publication
                     (subproblem/publish!
                      store :answer storage-key options value)]
                 {:value value
                  :cached? false
                  :publication publication}))))
         (range request-count))]
    (when-not (.await entered 20 java.util.concurrent.TimeUnit/SECONDS)
      (throw (ex-info "Concurrent miss workers did not all enter computation."
                      {:request-count request-count})))
    (let [all-computations-entered-before-publication?
          (= request-count (.get computations))]
      (.countDown release)
      (let [results (mapv #(deref % 20000 ::timeout) workers)
            _ (when (some #{::timeout} results)
                (throw (ex-info "Concurrent miss worker timed out."
                                {:request-count request-count})))
            final-hit
            (subproblem/lookup! store :answer storage-key)
            stats (subproblem/stats store)]
        {:request-count request-count
         :all-computations-entered-before-publication?
         all-computations-entered-before-publication?
         :independent-computations (.get computations)
         :initial-read-hits (count (filter :cached? results))
         :own-values-returned
         (= (set (range request-count)) (set (map :value results)))
         :successful-publications
         (count (filter #(true? (get-in % [:publication :published?]))
                        results))
         :publication-races (:publication-races stats)
         :later-read-hit? (:cached? final-hit)
         :lookup-misses (:lookup-misses stats)}))))

(defn- derived-hit-allocation-probe
  [hits]
  (let [slot (atom nil)
        root [:document :view]
        schema-cache
        {:expression-metrics (atom {})
         :sealed-plans (atom {})}
        _ (engine/memoized-derived! slot (constantly ::resident))
        seal-var (ns-resolve 'eacl.engine.v8 'seal-and-certify-plan)
        _ (with-redefs-fn
            {seal-var (constantly {})}
            #(binding [engine/*schema-cache* schema-cache]
               (engine/stable-plan nil root)))
        memo-observed
        (measured-loop
         hits
         (fn [_]
           (engine/memoized-derived!
            slot #(throw (ex-info "Resident memo rebuilt." {})))))
        memo-control
        (measured-loop hits (fn [_] (force @slot)))
        plan-observed
        (binding [engine/*schema-cache* schema-cache]
          (measured-loop hits (fn [_] (engine/stable-plan nil root))))
        plan-control
        (measured-loop hits (fn [_] (force (get @(:sealed-plans schema-cache)
                                                root))))]
    {:hits hits
     :memo-build-calls 0
     :memo-hit-runtime memo-observed
     :memo-direct-force-control memo-control
     :sealed-plan-build-calls 0
     :sealed-plan-hit-runtime plan-observed
     :sealed-plan-direct-force-control plan-control
     :source-structural-fact
     :completed-value-is-read-before-builder-or-delay-allocation}))

(defn- caught
  [f]
  (try
    (f)
    nil
    (catch Throwable error
      error)))

(defn- waiting-state?
  [^Thread thread]
  (contains? #{Thread$State/WAITING
               Thread$State/TIMED_WAITING
               Thread$State/BLOCKED}
             (.getState thread)))

(defn- await-shared-waiters!
  [threads workers]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (cond
        (every? waiting-state? @threads) true
        (some realized? workers) false
        (> (System/nanoTime) deadline) false
        :else (do (Thread/yield) (recur))))))

(defn- await-builder-count!
  [^java.util.concurrent.atomic.AtomicLong builds expected workers]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (cond
        (= expected (.get builds)) true
        (some realized? workers) false
        (> (System/nanoTime) deadline) false
        :else (do (Thread/yield) (recur))))))

(defn- coordinated-shared-failure
  [request-count call]
  (let [ready (java.util.concurrent.CountDownLatch. request-count)
        start (java.util.concurrent.CountDownLatch. 1)
        threads (atom [])
        workers
        (mapv
         (fn [_]
           (let [result (promise)
                 thread
                 (Thread.
                  (fn []
                    (.countDown ready)
                    (.await start)
                    (deliver result (caught call))))]
             (swap! threads conj thread)
             (.start thread)
             result))
         (range request-count))]
    (when-not (.await ready 20 java.util.concurrent.TimeUnit/SECONDS)
      (throw (ex-info "Shared-delay workers did not become ready."
                      {:request-count request-count})))
    (.countDown start)
    {:workers workers
     :threads threads}))

(defn- shared-failure-probe
  [request-count call-with-builder retry-call]
  (let [builds (java.util.concurrent.atomic.AtomicLong.)
        build-started (java.util.concurrent.CountDownLatch. 1)
        release-build (java.util.concurrent.CountDownLatch. 1)
        initial-owner-error (atom nil)
        builder
        (fn [& _]
          (let [build-id (.incrementAndGet builds)
                error
                (ex-info "Injected shared derived-build failure."
                         {:type :reproduction/derived-build-failure
                          :build-id build-id})]
            (compare-and-set! initial-owner-error nil error)
            (.countDown build-started)
            (.await release-build)
            (throw error)))
        {:keys [workers threads]}
        (coordinated-shared-failure
         request-count #(call-with-builder builder))]
    (when-not (.await build-started 20 java.util.concurrent.TimeUnit/SECONDS)
      (.countDown release-build)
      (throw (ex-info "No shared derived builder started."
                      {:request-count request-count})))
    (let [all-builders-entered-before-release?
          (await-builder-count! builds request-count workers)
          all-waiting-before-owner-release?
          (await-shared-waiters! threads workers)
          builds-before-release (.get builds)
          no-call-returned-before-owner-release?
          (not-any? realized? workers)]
      (.countDown release-build)
      (let [errors (mapv #(deref % 20000 ::timeout) workers)
            _ (when (some #{::timeout} errors)
                (throw (ex-info "Shared-delay worker timed out."
                                {:request-count request-count})))
            builds-after-fanout (.get builds)
            retry-error (caught #(retry-call builder))]
        {:request-count request-count
         :all-workers-blocked-or-waiting-before-owner-release?
         all-waiting-before-owner-release?
         :no-call-returned-before-owner-release?
         no-call-returned-before-owner-release?
         :builders-before-release builds-before-release
         :builders-after-fanout builds-after-fanout
         :builders-after-explicit-retry (.get builds)
         :all-misses-built-independently-before-release?
         (and all-builders-entered-before-release?
              (= request-count builds-before-release))
         :all-callers-failed? (every? #(instance? Throwable %) errors)
         :initial-owner-error-inheritance-count
         (count (filter #(identical? @initial-owner-error %) errors))
         :distinct-failure-objects (count (set errors))
         :retry-inherited-initial-owner-error-object?
         (identical? @initial-owner-error retry-error)}))))

(defn- parsed-schema-shared-delay-probe
  [request-count]
  (let [request-schema
        (private-function 'eacl.client.orchestration 'request-schema)
        schema-cache {:schema-version 1
                      :parsed-schema (atom nil)
                      :validation-catalog (atom nil)}
        api {:schema {:read-schema nil}}
        call
        (fn [builder]
          (binding [engine/*schema-cache* schema-cache]
            (request-schema (assoc-in api [:schema :read-schema] builder)
                            ::db)))]
    (with-redefs-fn
      {#'schema-errors/catalog (constantly {})
       #'schema-errors/with-catalog (fn [schema _] schema)}
      #(assoc (shared-failure-probe request-count call call)
              :slot :parsed-schema
              :failure-publication :not-installed))))

(defn- validation-catalog-shared-delay-probe
  [request-count]
  (let [request-schema
        (private-function 'eacl.client.orchestration 'request-schema)
        schema-cache {:schema-version 1
                      :parsed-schema (atom ::schema)
                      :validation-catalog (atom nil)}
        api {:schema {:read-schema (constantly ::unexpected-schema-read)}}
        builder-ref (atom nil)
        call
        (fn [builder]
          (reset! builder-ref builder)
          (binding [engine/*schema-cache* schema-cache]
            (request-schema api ::db)))]
    (with-redefs-fn
      {#'schema-errors/catalog
       (fn [& args] (apply @builder-ref args))
       #'schema-errors/with-catalog (fn [schema _] schema)}
      #(assoc (shared-failure-probe request-count call call)
              :slot :validation-catalog
              :failure-publication :not-installed))))

(defn- structural-decode-shared-delay-probe
  [request-count]
  (let [cache (atom {})
        decode-var
        (ns-resolve 'eacl.schema.expression-persistence
                    'decode-entity-with-metadata-uncached)
        builder-ref (atom nil)
        call
        (fn [builder]
          (reset! builder-ref builder)
          (binding [expression-persistence/*structural-cache* cache]
            (expression-persistence/decode-entity-with-metadata
             {:eacl.permission/expression-payload "reproduction"})))]
    (with-redefs-fn
      {decode-var (fn [& args] (apply @builder-ref args))}
      #(assoc (shared-failure-probe request-count call call)
              :slot :structural-expression-decode
              :failure-publication :not-installed))))

(defn- sealed-plan-shared-delay-probe
  [request-count]
  (let [root [:document :view]
        schema-cache {:expression-metrics (atom {})
                      :sealed-plans (atom {})}
        seal-var (ns-resolve 'eacl.engine.v8 'seal-and-certify-plan)
        builder-ref (atom nil)
        call
        (fn [builder]
          (reset! builder-ref builder)
          (binding [engine/*schema-cache* schema-cache]
            (engine/stable-plan ::db root)))]
    (with-redefs-fn
      {seal-var (fn [& args] (apply @builder-ref args))}
      #(assoc (shared-failure-probe request-count call call)
              :slot :sealed-plan
              :failure-publication :not-installed))))

(def ^:private point-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(defn- point-datascript-fixture
  []
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})
        user (eacl/spice-object :user "user")
        account (eacl/spice-object :account "account")]
    (eacl/write-schema! client point-schema)
    (ds/transact! conn [{:eacl/id "user"} {:eacl/id "account"}])
    (eacl/create-relationship!
     client (eacl/->Relationship user :owner account))
    {:conn conn :client client :user user :account account}))

(defn- eacl-stack-frame
  [^StackTraceElement frame]
  (let [class-name (.getClassName frame)]
    (when (and (.startsWith class-name "eacl.")
               (not (.startsWith class-name "eacl.backend.v8"))
               (not (.startsWith
                     class-name
                     "eacl.performance.amplification_reproduction")))
      class-name)))

(defn- first-production-caller
  []
  (some eacl-stack-frame (.getStackTrace (Thread/currentThread))))

(defn- duplicate-snapshot-id-probe
  []
  (let [{:keys [client user account]} (point-datascript-fixture)
        backend-calls (atom {})
        snapshot-callers (atom [])
        result
        (binding [backend/*backend-op-stats* backend-calls
                  backend/*invoke-observer*
                  (fn [{:keys [phase operation]}]
                    (when (and (= :before phase)
                               (= :snapshot-id operation))
                      (swap! snapshot-callers
                             conj
                             (some eacl-stack-frame
                                   (.getStackTrace
                                    (Thread/currentThread))))))]
          (eacl/check-permission client user :admin account))]
    {:allowed? (:allowed? result)
     :cached? (:cached? result)
     :snapshot-id-invocations (get @backend-calls :snapshot-id 0)
     :snapshot-id-callers @snapshot-callers
     :all-backend-operation-counts @backend-calls
     :captured-basis-call-present?
     (boolean (some #(and % (.contains ^String % "backend.source"))
                    @snapshot-callers))
     :post-capture-cache-metadata-call-present?
     (boolean (some #(and % (.contains ^String % "client.orchestration"))
                    @snapshot-callers))}))

(defn- duplicate-snapshot-id-series-probe
  [requests]
  (let [{:keys [client user account]} (point-datascript-fixture)
        backend-calls (atom {})
        cold-result (volatile! nil)
        final-result (volatile! nil)
        observation
        (binding [backend/*backend-op-stats* backend-calls]
          (measured-loop
           requests
           (fn [index]
             (let [result
                   (eacl/check-permission client user :admin account)]
               (when (zero? index)
                 (vreset! cold-result result))
               (vreset! final-result result)))))]
    {:requests requests
     :snapshot-id-invocations (get @backend-calls :snapshot-id 0)
     :cold-request-cached? (:cached? @cold-result)
     :final-request-cached? (:cached? @final-result)
     :allowed-throughout?
     (and (:allowed? @cold-result) (:allowed? @final-result))
     :raw-runtime-observation observation}))

(defn completed-cache-identity-probe
  []
  (let [{:keys [client user account]} (point-datascript-fixture)
        observed-semantic-key (atom nil)
        original-resolve cache/resolve-basis!
        result
        (with-redefs
         [cache/resolve-basis!
          (fn [store context semantic-key compute]
            (reset! observed-semantic-key semantic-key)
            (original-resolve store context semantic-key compute))]
         (eacl/check-permission client user :admin account))
        required-keys
        #{:compiler-plan-compatibility :cache-value-abi
          :expression-limits :aggregate-limits}
        present-keys (set (keys @observed-semantic-key))]
    {:allowed? (:allowed? result)
     :semantic-key @observed-semantic-key
     :required-keys required-keys
     :missing-keys (set/difference required-keys present-keys)
     :required-identity-present?
     (set/subset? required-keys present-keys)}))

(defn completed-cache-compatible-hit-probe
  []
  (let [{:keys [client user account]} (point-datascript-fixture)
        cold (eacl/check-permission client user :admin account)
        schema-calls (atom 0)
        plan-calls (atom 0)
        proof-calls (atom 0)
        backend-calls (atom {})
        request-schema-var
        (ns-resolve 'eacl.client.orchestration 'request-schema)
        original-request-schema @request-schema-var
        original-stable-plan engine/stable-plan
        original-proof-resolve proof-frame/resolve!
        hit
        (binding [backend/*backend-op-stats* backend-calls]
          (with-redefs-fn
            {request-schema-var
             (fn [& args]
               (swap! schema-calls inc)
               (apply original-request-schema args))
             #'engine/stable-plan
             (fn [& args]
               (swap! plan-calls inc)
               (apply original-stable-plan args))
             #'proof-frame/resolve!
             (fn [& args]
               (swap! proof-calls inc)
               (apply original-proof-resolve args))}
            #(eacl/check-permission client user :admin account)))]
    {:cold-cached? (:cached? cold)
     :hit-cached? (:cached? hit)
     :same-answer? (= (:allowed? cold) (:allowed? hit))
     :schema-work @schema-calls
     :plan-work @plan-calls
     :proof-work @proof-calls
     :backend-operations @backend-calls}))

(defn completed-cache-rollout-probe
  []
  (let [{:keys [client user account]} (point-datascript-fixture)
        old-identity (assoc engine/compiler-plan-compatibility
                            :pre-rollout-test true)
        [old-cold old-hit]
        (with-redefs [engine/compiler-plan-compatibility old-identity
                      orchestration/completed-cache-value-abi 1]
          [(eacl/check-permission client user :admin account)
           (eacl/check-permission client user :admin account)])
        current-cold (eacl/check-permission client user :admin account)
        current-hit (eacl/check-permission client user :admin account)]
    {:old-cold-cached? (:cached? old-cold)
     :old-hit-cached? (:cached? old-hit)
     :current-first-cached? (:cached? current-cold)
     :current-second-cached? (:cached? current-hit)
     :same-answer?
     (apply = (map :allowed? [old-cold old-hit current-cold current-hit]))
     :incompatible-entry-missed?
     (and (:cached? old-hit)
          (not (:cached? current-cold))
          (:cached? current-hit))}))

(defn cache-flight-contract-probe
  []
  (let [specs
        ["openspec/specs/single-flight-coordination/spec.md"
         "openspec/specs/answer-cache-bounding/spec.md"
         "openspec/specs/verified-subproblem-cache/spec.md"]
        positive-pattern
        #"(?i)joins that flight|must wait or reject|inheriting[^\n]*single-flight coordination|registered flights|single-flight waits"
        observations
        (mapv
         (fn [path]
           (let [file (repo/file path)
                 text (when (.isFile file) (slurp file))
                 matches (when text (re-seq positive-pattern text))]
             {:path path
              :exists? (.isFile file)
              :positive-flight-requirements (count matches)}))
         specs)]
    {:observations observations
     :positive-flight-requirements
     (reduce + 0 (map :positive-flight-requirements observations))
     :reconciled?
     (zero? (reduce + 0 (map :positive-flight-requirements observations)))}))

(def ^:private rejection-scan-schema
  "definition user {}
   definition document {
     relation candidate: user
     relation viewer: user
     permission view = viewer
   }")

(defn- rejection-scan-fixture
  [candidate-count]
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn
         {:cache cache/no-cache
          :aggregate-limits {:candidate-window candidate-count}})
        marker (eacl/spice-object :user "marker")
        denied (eacl/spice-object :user "denied")
        documents
        (mapv #(eacl/spice-object :document (str "document-" %))
              (range candidate-count))]
    (eacl/write-schema! client rejection-scan-schema)
    (doseq [entity-batch
            (partition-all
             4096
             (concat [{:eacl/id "marker"} {:eacl/id "denied"}]
                     (map (fn [document] {:eacl/id (:id document)})
                          documents)))]
      (ds/transact! conn (vec entity-batch)))
    (doseq [relationship-batch
            (partition-all
             4096
             (map #(eacl/->Relationship marker :candidate %) documents))]
      (eacl/create-relationships! client (vec relationship-batch)))
    {:client client :marker marker :denied denied}))

(defn- boundary-revalidation-probe
  [candidate-count]
  (let [{:keys [client marker denied]}
        (rejection-scan-fixture candidate-count)
        query {:subject/type :user
               :subject/id (:id marker)
               :resource/type :document
               :resource/relation :candidate
               :authorization {:subject denied
                               :permission :view
                               :on :resource}
               :first 1}
        relationship-var #'relationship-filters/validate!
        authorization-var
        #'authorization-filters/validate-scan-authorization!
        schema-var #'schema-errors/validate-authorized-relationship-read!
        original-relationship @relationship-var
        original-authorization @authorization-var
        original-schema @schema-var
        calls (atom {:public-relationship-shape 0
                     :public-authorization-shape 0
                     :selected-schema 0})
        callers (atom {:public-relationship-shape []
                       :public-authorization-shape []
                       :selected-schema []})
        ledger (request-counters/make-ledger)
        observation
        (with-redefs-fn
          {relationship-var
           (fn [filters]
             (swap! calls update :public-relationship-shape inc)
             (swap! callers update :public-relationship-shape
                    conj (first-production-caller))
             (original-relationship filters))
           authorization-var
           (fn [filters]
             (swap! calls update :public-authorization-shape inc)
             (swap! callers update :public-authorization-shape
                    conj (first-production-caller))
             (original-authorization filters))
           schema-var
           (fn [& args]
             (swap! calls update :selected-schema inc)
             (swap! callers update :selected-schema
                    conj (first-production-caller))
             (apply original-schema args))}
          #(binding [request-counters/*ledger* ledger]
             (let [result (volatile! nil)
                   runtime
                   (measured-loop
                    1
                    (fn [_]
                      (vreset! result
                               (eacl/read-relationships client query))))]
               {:runtime runtime :result @result})))
        counters (request-counters/snapshot ledger)]
    {:candidate-count candidate-count
     :accepted-results (count (get-in observation [:result :data]))
     :validation-calls @calls
     :validation-callers @callers
     :candidates-examined (:candidates-examined counters)
     :direct-probes (:probes counters)
     :raw-runtime-observation (:runtime observation)}))

(defn- captured-invoker-opportunity-probe
  [invocations]
  (let [adapter (backend/make-adapter
                 {:id :amplification-invoker
                  :capabilities backend/empty-capabilities
                  :runtime-guards? true
                  :operations
                  (required-adapter-operations
                   {:direct-match? (constantly true)})})
        operation-var #'backend/operation
        original-operation @operation-var
        operation-lookups (long-array 1)
        ordinary
        (with-redefs-fn
          {operation-var
           (fn [candidate operation]
             (aset-long operation-lookups 0
                        (inc (aget operation-lookups 0)))
             (original-operation candidate operation))}
          #(measured-loop
            invocations
            (fn [_]
              (backend/invoke
               adapter :direct-match?
               :user 1 2 :document 3))))
        captured-operation (backend/operation adapter :direct-match?)
        captured
        (measured-loop
         invocations
         (fn [_]
           (captured-operation :user 1 2 :document 3)))]
    {:invocations invocations
     :ordinary-operation-lookups (aget operation-lookups 0)
     :ordinary-full-boundary-runtime ordinary
     :raw-captured-function-runtime captured
     :captured-comparison-excludes
     [:request-meter :observer :runtime-output-guard :typed-wrapper]
     :semantic-fast-path-implemented? false}))

(def ^:private alias-frontier-schema
  "definition user {}
   definition organization {
     relation member: user
     permission base = member
     permission alias = base
   }
   definition document {
     relation organization: organization
     permission view = organization->base + organization->alias
   }")

(defn alias-frontier-probe
  []
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {:cache cache/no-cache})
        user (eacl/spice-object :user "user")
        organization (eacl/spice-object :organization "organization")
        document (eacl/spice-object :document "document")]
    (eacl/write-schema! client alias-frontier-schema)
    (ds/transact! conn [{:eacl/id "user"}
                        {:eacl/id "organization"}
                        {:eacl/id "document"}])
    (eacl/create-relationships!
     client
     [(eacl/->Relationship user :member organization)
      (eacl/->Relationship organization :organization document)])
    (let [original-invoke @#'backend/invoke
          scans (atom [])
          result
          (with-redefs
           [backend/invoke
            (fn [adapter operation & args]
              (when (contains? #{:subject->resources :resource->subjects}
                               operation)
                (swap! scans conj
                       {:operation operation
                        :args args
                        :caller (first-production-caller)}))
              (apply original-invoke adapter operation args))]
           (eacl/lookup-resources
            client
            {:subject user
             :permission :view
             :resource/type :document
             :first 10
             :cache? false}))]
      (let [alias-frontier-scans
            (filterv
             (fn [{:keys [operation args]}]
               (and (= :subject->resources operation)
                    (= :organization (first args))
                    (= :document (nth args 3))))
             @scans)]
        {:result-ids (mapv :id (:data result))
         :scan-count (count @scans)
         :scans @scans
         :alias-equivalent-frontier-scan-count
         (count alias-frontier-scans)
         :duplicate-alias-frontier-scans?
         (> (count alias-frontier-scans) 1)}))))

(defn request-report
  []
  {:evidence/version 1
   :source-base source-base
   :classpath-sha256 classpath-sha256
   :operation-boundary :public-request-cache-and-derived-state-seams
   :estimator :deterministic-counters-structural-proxies-and-raw-runtime-lanes
   :metric-classification
   {:mandatory-semantic-meters
    [:commands :fetched-values :candidates-examined :probes]
    :optional-observation
    [:backend-operation-counts :cache-hit-metrics :allocation-bytes
     :elapsed-nanos]
    :absence-policy :unsupported-is-never-zero}
   :observations
   {:boundary-revalidation
    :three-relationship-shape-two-authorization-shape-and-one-selected-schema-validation-per-public-scan
    :fixed-counter-path
    :zero-dynamic-validation-or-lookup-per-bound-increment
    :snapshot-id
    :one-captured-snapshot-identity-invocation-per-request
    :request-context
    :proof-frame-and-one-combined-memo-stay-lazy-before-use
    :native-membership
    :every-external-candidate-is-validated-once-before-checked-native-chunking
    :completed-hit
    :per-entry-recency-is-nonserializing-and-optional-telemetry-can-be-zero
    :concurrent-miss
    :all-callers-compute-return-own-values-and-only-publication-losers-count-as-races
    :derived-independent-failure
    :all-misses-build-independently-and-failures-are-never-published}
   :boundary-revalidation
   (mapv boundary-revalidation-probe [256 4096 65536])
   :fixed-counter-key-validation
   (mapv counter-increment-probe [10000 100000 1000000])
   :duplicate-snapshot-id
   {:call-paths (duplicate-snapshot-id-probe)
    :scale
    (mapv duplicate-snapshot-id-series-probe [1 100 10000])}
   :eager-request-context
   (mapv request-context-construction-probe [1 1000 100000])
   :native-membership-normalization
   (mapv native-membership-normalization-probe [256 1024 4096])
   :captured-invoker-opportunity
   (mapv captured-invoker-opportunity-probe [256 4096 65536])
   :completed-hit-mutations
   (mapv completed-hit-mutation-probe [1000 10000 100000])
   :derived-hit-allocation
   (mapv derived-hit-allocation-probe [1000 10000 100000])
   :independent-concurrent-misses
   (mapv independent-miss-probe [2 8 32])
   :shared-derived-failures
   {:parsed-schema
    (mapv parsed-schema-shared-delay-probe [2 8 32])
    :validation-catalog
    (mapv validation-catalog-shared-delay-probe [2 8 32])
    :structural-expression-decode
    (mapv structural-decode-shared-delay-probe [2 8 32])
    :sealed-plan
    (mapv sealed-plan-shared-delay-probe [2 8 32])}
   :attribution
   {:completed-read-hit
    :standard-lru-touch-is-the-only-required-shared-hit-mutation
    :publication-race
    :each-initial-lookup-missed-and-loser-returned-own-computed-value
    :mandatory-counter
    :consumed-by-aggregate-resource-limits
    :optional-telemetry
    :does-not-control-authorization-and-can-be-disabled-with-zero-mutation}})

(defn engine-report
  []
  {:evidence/version 1
   :source-base source-base
   :classpath-sha256 classpath-sha256
   :operation-boundary :internal-engine-and-adapter-seams
   :estimator :deterministic-counters-and-structural-proxies
   :acyclic-alias-frontier (alias-frontier-probe)
   :least-path-limit
   (mapv least-path-limit-probe [64 1024 16384])
   :stable-retention
   (mapv stable-structural-probe [64 1024 16384])
   :routed-vector-recopy (routed-vector-recopy-probe)
   :stable-sidecar-churn
   (mapv #(stable-sidecar-churn-probe % 16) [1000 10000 100000])
   :continuation-churn
   (mapv #(continuation-churn-probe % 16) [1000 10000 100000])
   :scheduler
   {:zero-successor (scheduler-transient-probe 10000 0)
    :one-successor (scheduler-transient-probe 10000 1)}
   :route-attribution
   {:least-path :eacl.engine.least-path
    :stable-first-discovery :eacl.engine.stable-reducer
    :point-probe :separate-route-not-measured-as-stable
    :cross-route-counter-inference :forbidden}})

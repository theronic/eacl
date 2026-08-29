(ns eacl.cache
  "Backend-neutral private basis caching.

  The portable CacheStore protocol family remains the provider/continuation
  adapter surface; the unreachable authenticated-envelope completed-cache
  path that once lived here was deleted by trusted-surface-hygiene 11.1
  (native completed answers are client-private and never flow through
  portable providers)."
  (:require [eacl.backend.v8 :as backend]
            [eacl.formal.current-cache-refinement :as cache-refinement]
            [eacl.subproblem-cache :as subproblem]
            [eacl.verified-kernel :as verified]))

(defprotocol CacheStore
  (lookup [store key])
  (store! [store key value])
  (evict! [store key])
  (clear! [store])
  (stats [store]))

(defprotocol CacheTelemetry
  (record-validation! [store metric]))

(defprotocol CacheValidationUpdate
  (store-validation!
    [store key expected-entry replacement-entry]
    "Conditionally replaces validation telemetry for an unchanged entry."))

(defrecord NoCache []
  CacheStore
  (lookup [_ _] nil)
  (store! [_ _ _] false)
  (evict! [_ _] false)
  (clear! [_] nil)
  (stats [_] {:entries 0 :hits 0 :misses 0 :puts 0 :errors 0})
  CacheTelemetry
  (record-validation! [_ _] nil)
  CacheValidationUpdate
  (store-validation! [_ _ _ _] false))

(def no-cache (->NoCache))

(defn no-cache?
  [store]
  (instance? NoCache store))

(defn validate-request-cache-option!
  "Validates the per-request `:cache?` execution control."
  [cache-option]
  (when-not (or (nil? cache-option) (boolean? cache-option))
    (throw (ex-info "EACL Error: per-request :cache? must be true or false."
                    {:type :eacl/invalid-request :eacl/error :eacl/invalid-request
                     :key :cache?
                     :value cache-option})))
  cache-option)

(defn validate-request-populate-option!
  "Validates the per-request `:populate-cache?` publication control."
  [populate-option]
  (when-not (or (nil? populate-option) (boolean? populate-option))
    (throw
     (ex-info
      "EACL Error: per-request :populate-cache? must be true or false."
      {:type :eacl/invalid-request :eacl/error :eacl/invalid-request
       :key :populate-cache?
       :value populate-option})))
  populate-option)

(defn lookup-page-query-identity
  "Builds the semantic cache identity for an authenticated lookup page.

  Public cursors are signed transport envelopes. The authenticated internal
  ordinal/result boundary is the semantic position, while transport bytes are
  deliberately excluded.

  Call only after the public cursor has been authenticated and internalized."
  [public-query internal-query]
  {:public
   (dissoc public-query
           :consistency :cache? :populate-cache?
           :after :before :cancellation-token)
   :internal
   (dissoc internal-query
           :consistency :cache? :populate-cache? :cancellation-token)})

(defrecord ExactGeneration
           [snapshot order subproblems last-used access-state promoted-access])
(defrecord ManagedGeneration
           [generation-key schema-generation subproblems last-used
            access-state promoted-access])
(defrecord CacheLifecycle [bases managed])
(defrecord BasisCache [lifecycle metrics max-entries admissions
                                   admit-on-repeat? subproblem-options
                                   retained-bases managed-lifting-disabled?
                                   reported-contract-violations
                                   content-revision
                                   proof-contract-reporter
                                   telemetry-enabled?])

(def basis-snapshot-format
  "Version identifier for process-neutral basis-cache snapshot values."
  :eacl.cache/basis-snapshot-v1)

(defn- new-lifecycle
  []
  (->CacheLifecycle
   (atom {:tick 0 :generations {}})
   (atom {:tick 0 :generations {}})))

(defn exact-basis-key
  "Returns the complete cache identity of one admissible immutable basis.

  Unlike the removed snapshot-specific tier, this identity exists for ordinary
  current-only values such as DataScript as well as exact-reconstructable
  backends. Basis kind and complete lineage are mandatory dimensions. Exact
  reuse is client-private and basis-pinned, so an unfingerprinted custom codec
  receives a runtime-unique adapter fingerprint and remains exact-cache safe;
  adapter determinism gates managed cross-basis lifting, not this tier."
  [adapter basis-identity]
  (when (and (map? basis-identity)
             (backend/admissible-basis-kind?
              (:basis-kind basis-identity)))
    {:key-version 2
     :backend (backend/backend-id adapter)
     :basis-identity
     (select-keys basis-identity
                  [:backend :source-id :branch :source-lifecycle
                   :basis-kind :revision :exact-locator
                   :backend-snapshot-id])
     :adapter-fingerprint (backend/fingerprint adapter)
     :identity-contract (backend/identity-contract adapter)}))

(defn- valid-exact-basis-key?
  [basis-key]
  (let [identity (:basis-identity basis-key)]
    (and (map? basis-key)
         (= 2 (:key-version basis-key))
         (keyword? (:backend basis-key))
         (map? identity)
         (= (:backend basis-key) (:backend identity))
         (backend/admissible-basis-kind? (:basis-kind identity))
         (some? (:source-id identity))
         (some? (:source-lifecycle identity))
         (integer? (:revision identity))
         (some? (:backend-snapshot-id identity))
         (some? (:adapter-fingerprint basis-key))
         (keyword? (:identity-contract basis-key)))))

(def ^:private empty-answer-sightings
  {:tick 0
   :queue #?(:clj clojure.lang.PersistentQueue/EMPTY
             :cljs (.-EMPTY cljs.core/PersistentQueue))
   :seen {}})

(defn- sighting-transition
  "One `:on-repeat` admission step over a first-in-first-out sighting window.

  Returns `[admit? next-state]`. A key already in the window demonstrates
  reuse and is admitted (its sighting is consumed). A new key records a
  first sighting; when the window exceeds `capacity`, the OLDEST first
  sightings are forgotten — recency-honest by construction, so the retained
  sighting set can never converge to a fixed hash-lucky subset. A key seen
  twice within `capacity` distinct first sightings is therefore always
  admitted."
  [{:keys [tick queue seen] :as state} entry-key capacity]
  (if (contains? seen entry-key)
    [true (assoc state :seen (dissoc seen entry-key))]
    (let [tick (inc tick)
          queue (conj queue [tick entry-key])
          seen (assoc seen entry-key tick)
          [queue seen]
          (loop [queue queue
                 seen seen]
            (if (<= (count seen) capacity)
              [queue seen]
              (let [[record-tick record-key] (peek queue)
                    queue (pop queue)]
                (if (= record-tick (get seen record-key))
                  (recur queue (dissoc seen record-key))
                  (recur queue seen)))))
          queue
          ;; Consumed and superseded records are skipped rather than removed
          ;; above; compact when they dominate so the queue stays
          ;; proportional to the live window.
          (if (> (count queue) (max 64 (* 2 (count seen))))
            (into (:queue empty-answer-sightings)
                  (filter (fn [[record-tick record-key]]
                            (= record-tick (get seen record-key))))
                  queue)
            queue)]
      [false {:tick tick :queue queue :seen seen}])))

(defn- admit-answer?
  [store entry-key]
  (if-not (:admit-on-repeat? store)
    true
    (let [admitted? (volatile! false)]
      (swap! (:admissions store)
             (fn [state]
               (let [[admit? next-state]
                     (sighting-transition
                      state entry-key (:max-entries store))]
                 (vreset! admitted? admit?)
                 next-state)))
      @admitted?)))

(def ^:private answer-weight-floor 512)
(def ^:private answer-weight-per-unit 128)

(defn- structural-answer-units
  "Counts the collection entries an answer retains, walking with an explicit
  stack so a deep tree cannot exhaust the runtime stack.

  Permission trees carry their payload in nested `:intermediate`/`:leaf` nodes
  rather than a flat `:data` vector, so a shape-specific rule would weigh a
  100k-subject tree the same as a boolean. The traversal is bounded by the
  expansion limits that produced the value and runs only when an answer is
  published."
  [value]
  (loop [stack (list value)
         units 0]
    ;; Test the stack, not the element: a nil or false value inside the answer
    ;; must not end the walk and silently drop everything still queued.
    (if (seq stack)
      (let [current (first stack)
            remaining (rest stack)]
        (cond
          (map? current)
          (recur (into remaining (vals current))
                 (+ units (count current)))

          (coll? current)
          (recur (into remaining current)
                 (+ units (count current)))

          :else
          (recur remaining units)))
      units)))

(defn- default-answer-weight
  "Conservative retained-size estimate for one completed answer.

  Mirrors the page-weight family the Datomic backend supplies explicitly:
  paged results weigh in by row count, nested results by retained collection
  entries, and scalar decisions and counts pay a flat floor. Callers with
  better knowledge pass `:answer-weight-fn`."
  [value]
  (let [data (when (map? value) (:data value))]
    (cond
      ;; `some?` before `counted?`: ClojureScript answers true for
      ;; `(counted? nil)` while Clojure answers false, so testing countedness
      ;; alone would silently route every non-page answer down the page branch
      ;; on one runtime only.
      (and (some? data) (counted? data))
      (+ answer-weight-floor (* answer-weight-per-unit (count data)))

      (coll? value)
      (+ answer-weight-floor
         (* answer-weight-per-unit (structural-answer-units value)))

      :else
      answer-weight-floor)))

(defn- answer-entry-options
  "Store options validating and weighing `{:value v :cache-basis b}` wrappers."
  [valid-value? answer-weight-fn]
  (let [weight-fn (or answer-weight-fn default-answer-weight)]
    {:valid? (fn [entry]
               (and (map? entry)
                    (contains? entry :value)
                    (boolean (valid-value? (:value entry)))))
     :weight-fn (fn [entry]
                  (weight-fn (:value entry)))}))

(defn basis-cache
  "Creates the private, client-owned completed-answer cache.

  Exact entries belong to one complete immutable basis identity. A bounded LRU
  retains several basis generations, including authenticated historical
  selections. Managed entries may lift between ordinary bases in either
  revision direction under an explicit proof contract; historical bases are
  exact-only. Neither tier is a portable provider.

  Completed answers are stored in the `:answer` tier of the lifecycle's
  weighted subproblem stores: byte-weight bounded (`:subproblem-cache
  {:answer-max-weight n}`, default 16 MiB), least-recently-used eviction,
  and a per-entry ceiling of one quarter of the budget with oversized
  rejection. `:max-entries` no longer bounds stored answers — the weight
  budget does — but remains accepted: it sizes the `:admit-on-repeat?`
  second-sighting window (default 1024), its historical admission role."
  ([]
   (basis-cache {}))
  ([{:keys [max-entries admit-on-repeat? subproblem-cache retained-bases
            proof-contract-reporter telemetry?]
     :or {max-entries 1024
          admit-on-repeat? false
          retained-bases 4
          subproblem-cache {}
          telemetry? true}}]
   (when-not (and (integer? max-entries) (pos? max-entries))
     (throw (ex-info "Basis cache :max-entries must be positive."
                     {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                      :max-entries max-entries})))
   (when-not (boolean? admit-on-repeat?)
     (throw (ex-info "Basis cache :admit-on-repeat? must be boolean."
                     {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                      :admit-on-repeat? admit-on-repeat?})))
   (when-not (and (integer? retained-bases) (pos? retained-bases))
     (throw
      (ex-info "Basis cache :retained-bases must be positive."
               {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                :retained-bases retained-bases})))
   (when-not (map? subproblem-cache)
     (throw (ex-info "Basis cache :subproblem-cache must be a map."
                     {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                      :subproblem-cache subproblem-cache})))
   (when-not (or (nil? proof-contract-reporter)
                 (fn? proof-contract-reporter))
     (throw
      (ex-info "Basis cache :proof-contract-reporter must be a function."
               {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                :proof-contract-reporter proof-contract-reporter})))
   (when-not (boolean? telemetry?)
     (throw
      (ex-info "Basis cache :telemetry? must be boolean."
               {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                :telemetry? telemetry?})))
   (when-not (boolean? (get subproblem-cache :enabled? true))
     (throw (ex-info "Basis cache subproblem :enabled? must be boolean."
                     {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                      :subproblem-cache subproblem-cache})))
   ;; Validate budgets before a request attempts to install a generation.
   (let [subproblem-cache (if telemetry?
                            subproblem-cache
                            (assoc subproblem-cache :telemetry? false))]
     (subproblem/store (dissoc subproblem-cache :enabled?))
     (let [content-revision (atom 0)]
     (->BasisCache
      (atom (new-lifecycle))
      (atom {:exact-hits 0
             :managed-hits 0
             :misses 0
             :bypasses 0
             :stamp-failures 0
             :proof-unavailable 0
             :proof-unavailable-reasons {}
             :proof-contract-violations 0
             :proof-contract-violation-reasons {}
             :puts 0
             :expirations 0
             :restores 0})
      max-entries
      (atom empty-answer-sightings)
      admit-on-repeat?
      subproblem-cache
      retained-bases
      (atom false)
      (atom #{})
      content-revision
      proof-contract-reporter
      telemetry?)))))

(defn basis-cache?
  [value]
  (instance? BasisCache value))

(defn- record-metrics!
  [store f & args]
  (when (:telemetry-enabled? store)
    (apply swap! (:metrics store) f args))
  nil)

(def cache-option-keys
  "Closed configuration surface for the client-private authorization cache."
  #{:max-entries :admit-on-repeat? :subproblem-cache :retained-bases
    :telemetry?})

(defn- invalid-cache-option!
  [message value data]
  (throw
   (ex-info
    message
    (merge
     {:type :eacl/invalid-config
      :eacl/error :eacl/invalid-config
      :key :cache
      :value value}
     data))))

(defn basis-cache-for-option
  "Builds the private basis cache corresponding to a public `:cache` option.

  `no-cache` disables it. Cache stores are client-private and cannot be
  supplied by applications. A closed config map contributes only the five
  documented capacity/admission/telemetry settings."
  ([value]
   (basis-cache-for-option value {}))
  ([value {:keys [proof-contract-reporter]}]
   (cond
    (no-cache? value) nil

    (nil? value)
    (basis-cache {:proof-contract-reporter proof-contract-reporter})

    (basis-cache? value)
    (invalid-cache-option!
     "A basis cache is owned by exactly one EACL client."
     value {:reason :client-private-cache-reuse})

    (satisfies? CacheStore value)
    (invalid-cache-option!
     "Application cache stores cannot control client-private authorization state."
     value {:reason :unsupported-provider-store})

    (record? value)
    (invalid-cache-option!
     "Record-backed cache adapters cannot control client-private authorization state."
     value {:reason :unsupported-provider-store})

    (map? value)
    (let [_
          (when (contains? value :store)
            (invalid-cache-option!
             "Nested cache adapters cannot control client-private authorization state."
             value {:reason :unsupported-provider-store}))
          unknown (seq (remove cache-option-keys (keys value)))]
      (when unknown
        (invalid-cache-option!
         "EACL cache configuration contains unknown options."
         value
         {:reason :unknown-cache-options
          :unknown-keys (vec unknown)
          :known-keys cache-option-keys}))
      (basis-cache (assoc value
                          :proof-contract-reporter
                          proof-contract-reporter)))

    :else
    (invalid-cache-option!
     "EACL :cache must be a configuration map or eacl.cache/no-cache."
     value {:reason :unsupported-provider-store}))))

(defn- merge-stat-values
  [left right]
  (cond
    (and (map? left) (map? right))
    (merge-with merge-stat-values left right)

    (and (number? left) (number? right))
    (+ left right)

    (nil? left) right
    :else right))

(defn- aggregate-subproblem-stats
  [stats]
  (when (seq stats)
    (reduce #(merge-with merge-stat-values %1 %2) {} stats)))

(defn basis-cache-stats
  [store]
  (when-not (basis-cache? store)
    (throw (ex-info "Expected an EACL basis cache."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :cache store})))
  (let [lifecycle @(:lifecycle store)
        basis-state @(:bases lifecycle)
        generations (vals (:generations basis-state))
        managed-state @(:managed lifecycle)
        managed-generations (vals (:generations managed-state))
        exact-stats (mapv #(subproblem/stats (:subproblems %)) generations)
        managed-stats
        (mapv #(subproblem/stats (:subproblems %)) managed-generations)
        exact-aggregate (aggregate-subproblem-stats exact-stats)
        managed-aggregate (aggregate-subproblem-stats managed-stats)
        empty-exact-stats
        (delay
          (subproblem/stats
           (subproblem/store
            (dissoc (:subproblem-options store) :enabled?)
            (:content-revision store))))]
    (assoc @(:metrics store)
           :telemetry-enabled? (:telemetry-enabled? store)
           :managed-lifting-disabled?
           @(:managed-lifting-disabled? store)
           :exact-entries
           (reduce + 0 (map #(get-in % [:tiers :answer :entries] 0)
                            exact-stats))
           :retained-bases (count generations)
           :managed-entries
           (reduce + 0 (map #(get-in % [:tiers :answer :entries] 0)
                            managed-stats))
           :managed-generations (count managed-generations)
           :admission-entries
           (count (:seen @(:admissions store)))
           :subproblems
           (or exact-aggregate @empty-exact-stats)
           :exact-subproblems
           (or exact-aggregate @empty-exact-stats)
           :managed-subproblems
           managed-aggregate)))

(defn record-current-bypass!
  "Records that a configured native cache was deliberately skipped without
  entering cache key/stamp resolution."
  [store]
  (when store
    (when-not (basis-cache? store)
      (throw (ex-info "Expected an EACL basis cache."
                      {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                       :cache store})))
    (record-metrics! store update :bypasses inc))
  nil)

(defn record-proof-unavailable!
  "Records a typed, optimization-only proof diagnostic.

  Proof availability never changes authorization availability: callers still
  evaluate and publish only against the selected exact snapshot."
  [store {:keys [reason]}]
  (when store
    (when-not (basis-cache? store)
      (throw (ex-info "Expected an EACL basis cache."
                      {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                       :cache store})))
    (record-metrics!
     store
     (fn [metrics]
       (-> metrics
           (update :proof-unavailable inc)
           (update-in [:proof-unavailable-reasons reason]
                      (fnil inc 0))))))
  nil)

(defn record-proof-diagnostic!
  "Records a typed proof outcome and disables managed lifting on violations.

  A reporter is invoked at most once for each reason in one cache lifecycle;
  reporter failures never change authorization or cache decisions."
  [store {:keys [status reason] :as diagnostic}]
  (when store
    (when-not (basis-cache? store)
      (throw (ex-info "Expected an EACL basis cache."
                      {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                       :cache store})))
    (case status
      :unavailable
      (record-proof-unavailable! store diagnostic)

      :contract-violation
      (do
        (reset! (:managed-lifting-disabled? store) true)
        (record-metrics!
         store
         (fn [metrics]
           (-> metrics
               (update :proof-contract-violations inc)
               (update-in [:proof-contract-violation-reasons reason]
                          (fnil inc 0)))))
        (when (and (:proof-contract-reporter store)
                   (not (contains? @(:reported-contract-violations store)
                                   reason)))
          (let [report? (volatile! false)]
            (swap! (:reported-contract-violations store)
                   (fn [reported]
                     (if (contains? reported reason)
                       reported
                       (do (vreset! report? true)
                           (conj reported reason)))))
            (when @report?
              (try
                ((:proof-contract-reporter store) diagnostic)
                (catch #?(:clj Throwable :cljs :default) _)))))
        nil)

      nil))
  nil)

(defn capture-cache-lifecycle
  "Captures the cache lifecycle object at a public request boundary.

  Passing this object back to `resolve-basis!` prevents a request that was
  already in flight during `expire-basis-cache!` from attaching its work to the
  replacement lifecycle, even when expiry happens before cache lookup."
  [store]
  (when store
    (when-not (basis-cache? store)
      (throw (ex-info "Expected an EACL basis cache."
                      {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                       :cache store})))
    @(:lifecycle store)))

(defn cache-content-revision
  "Returns the monotonic process-local reusable-content revision."
  [store]
  (when-not (basis-cache? store)
    (throw (ex-info "Expected an EACL basis cache."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :cache store})))
  @(:content-revision store))

(defn expire-basis-cache!
  "Atomically makes every exact and managed entry unreachable.

  In-flight work retains only the old lifecycle object and can therefore
  publish only into unreachable generations."
  [store]
  (when-not (basis-cache? store)
    (throw (ex-info "Expected an EACL basis cache."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :cache store})))
  (reset! (:lifecycle store) (new-lifecycle))
  (reset! (:admissions store) empty-answer-sightings)
  (reset! (:managed-lifting-disabled? store) false)
  (reset! (:reported-contract-violations store) #{})
  (record-metrics! store update :expirations inc)
  (swap! (:content-revision store) inc)
  nil)

(defn- touch-generation!
  [generation]
  (when-let [access-state (:access-state generation)]
    (swap! access-state inc))
  generation)

(defn- materialize-generation-accesses
  "Coalesces per-generation hit counters into the immutable eviction state.

  This runs only while installing a generation. Hits never contend on the
  lifecycle atom; every generation touched since the previous installation is
  promoted ahead of untouched generations before a victim is selected."
  [{:keys [generations] :as state}]
  (reduce
   (fn [{:keys [tick] :as current} [key generation]]
     (let [access-generation (some-> (:access-state generation) deref)]
       (if (and (integer? access-generation)
                (< (:promoted-access generation 0) access-generation))
         (let [tick (inc tick)]
           (-> current
               (assoc :tick tick)
               (assoc-in [:generations key :last-used] tick)
               (assoc-in [:generations key :promoted-access]
                         access-generation)))
         current)))
   state
   ;; Preserve the previous LRU relation among entries promoted in the same
   ;; coalescing pass. The new generation is installed after all promotions.
   (sort-by (fn [[key generation]]
              [(:last-used generation) (pr-str key)])
            generations)))

(defn- install-exact-generation!
  "Installs one exact-basis generation or records a non-serializing touch."
  [store lifecycle basis-key snapshot order]
  (if-not (valid-exact-basis-key? basis-key)
    {:generation nil :active? false}
    (if-let [existing
             (get-in @(:bases lifecycle) [:generations basis-key])]
      {:generation (touch-generation! existing) :active? true}
      (let [selected (volatile! nil)
            installed? (volatile! false)
            candidate
            (->ExactGeneration
             snapshot order
             (subproblem/store
              (dissoc (:subproblem-options store) :enabled?)
              (:content-revision store))
             0 (atom 0) 0)]
        (swap!
         (:bases lifecycle)
         (fn [{:keys [generations] :as original}]
           (if-let [winner (get generations basis-key)]
             (do (vreset! selected winner) original)
             (let [{:keys [tick generations] :as materialized}
                   (materialize-generation-accesses original)
                   tick (inc tick)
                   generation (assoc candidate :last-used tick)
                   generations (assoc generations basis-key generation)
                   overflow (- (count generations) (:retained-bases store))
                   victims
                   (when (pos? overflow)
                     (->> generations
                          (remove (fn [[key _]] (= key basis-key)))
                          (sort-by (fn [[key value]]
                                     [(:last-used value) (pr-str key)]))
                          (take overflow)
                          (map first)))
                   generations (if (seq victims)
                                 (apply dissoc generations victims)
                                 generations)]
               (vreset! selected generation)
               (vreset! installed? true)
               (assoc materialized :tick tick :generations generations)))))
        (if @installed?
          (swap! (:content-revision store) inc)
          (touch-generation! @selected))
        {:generation @selected :active? true}))))

(defn- select-exact-generation!
  "Selects an existing exact generation for read-only requests; installs one
  only when publication is enabled."
  [store lifecycle basis-key snapshot order populate?]
  (if populate?
    (install-exact-generation! store lifecycle basis-key snapshot order)
    (let [valid? (valid-exact-basis-key? basis-key)
          generation
          (when valid?
            (get-in @(:bases lifecycle) [:generations basis-key]))]
      {:generation (some-> generation touch-generation!)
       :active? valid?})))

(defn- managed-generation-key
  [lineage schema-generation]
  (when (and (map? lineage)
             (map? (:source-scope lineage))
             (keyword? (get-in lineage [:source-scope :backend]))
             (some? (get-in lineage [:source-scope :source-id]))
             (some? (:source-lifecycle lineage))
             (subproblem/proof-stamp? schema-generation))
    {:lineage
     {:source-scope
      (select-keys (:source-scope lineage)
                   [:backend :source-id :branch])
      :source-lifecycle (:source-lifecycle lineage)}
     :schema-generation schema-generation}))

(defn- install-managed-generation!
  "Installs one lineage-and-schema managed generation or records a touch.

  Revision order is recorded only for diagnostics. It is never an admission
  predicate: equal complete schema and dependency frontiers prove an equal
  deterministic answer in either revision direction."
  [store lifecycle lineage schema-generation]
  (when-let [generation-key
             (managed-generation-key lineage schema-generation)]
    (if-let [existing
             (get-in @(:managed lifecycle) [:generations generation-key])]
      (touch-generation! existing)
      (let [selected (volatile! nil)
            installed? (volatile! false)
            candidate
            (->ManagedGeneration
             generation-key schema-generation
             (subproblem/store
              (dissoc (:subproblem-options store) :enabled?)
              (:content-revision store))
             0 (atom 0) 0)]
        (swap!
         (:managed lifecycle)
         (fn [{:keys [generations] :as original}]
           (if-let [winner (get generations generation-key)]
             (do (vreset! selected winner) original)
             (let [{:keys [tick generations] :as materialized}
                   (materialize-generation-accesses original)
                   tick (inc tick)
                   generation (assoc candidate :last-used tick)
                   generations (assoc generations generation-key generation)
                   overflow (- (count generations) (:retained-bases store))
                   victims
                   (when (pos? overflow)
                     (->> generations
                          (remove (fn [[key _]] (= key generation-key)))
                          (sort-by (fn [[key value]]
                                     [(:last-used value) (pr-str key)]))
                          (take overflow)
                          (map first)))
                   generations (if (seq victims)
                                 (apply dissoc generations victims)
                                 generations)]
               (vreset! selected generation)
               (vreset! installed? true)
               (assoc materialized :tick tick :generations generations)))))
        (if @installed?
          (swap! (:content-revision store) inc)
          (touch-generation! @selected))
        @selected))))

(defn- select-managed-generation!
  "Selects an existing lineage generation without creating it for read-only
  requests."
  [store lifecycle lineage schema-generation populate?]
  (if populate?
    (install-managed-generation!
     store lifecycle lineage schema-generation)
    (when-let [generation-key
               (managed-generation-key lineage schema-generation)]
      (some-> (get-in @(:managed lifecycle) [:generations generation-key])
              touch-generation!))))

(defn- positive-snapshot-bound!
  [option value]
  (when-not (and (integer? value) (pos? value))
    (throw
     (ex-info "Cache snapshot bounds must be positive integers."
              {:type :eacl/invalid-bound :eacl/error :eacl/invalid-bound
               :option option :value value})))
  value)

(defn- generation-order
  [state]
  (->> (:generations state)
       (sort-by (fn [[key generation]]
                  [(- (:last-used generation)) (pr-str key)]))
       vec))

(defn- select-snapshot-entries
  [exact managed max-weight max-entries]
  (reduce
   (fn [selection tier]
     (reduce
      (fn [selection [kind generation-key generation]]
        (reduce
         (fn [selection entry]
           (if (and (< (:entry-count selection) max-entries)
                    (<= (+ (:retained-weight selection) (:weight entry))
                        max-weight))
             (-> selection
                 (update-in [:entries [kind generation-key] tier]
                            (fnil conj []) entry)
                 (update :entry-count inc)
                 (update :retained-weight + (:weight entry)))
             selection))
         selection
         (subproblem/snapshot-tier-entries (:subproblems generation) tier)))
      selection
      (concat
       (map (fn [[key generation]] [:exact key generation]) exact)
       (map (fn [[key generation]] [:managed key generation]) managed))))
   {:entries {} :entry-count 0 :retained-weight 0}
   subproblem/snapshot-tier-priority))

(defn export-basis-snapshot
  "Exports reusable exact and managed entries as one bounded immutable value.

  The result contains no backend snapshots, atoms, metrics, continuations,
  cursors, or process-local identity tokens. Represented weight is a cache
  capacity unit, not a measured JVM byte count."
  [store {:keys [max-weight max-entries]}]
  (when-not (basis-cache? store)
    (throw (ex-info "Expected an EACL basis cache."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :cache store})))
  (positive-snapshot-bound! :max-weight max-weight)
  (positive-snapshot-bound! :max-entries max-entries)
  (let [lifecycle @(:lifecycle store)
        exact (generation-order @(:bases lifecycle))
        managed (generation-order @(:managed lifecycle))
        {:keys [entries entry-count retained-weight]}
        (select-snapshot-entries exact managed max-weight max-entries)
        exact-snapshots
        (into []
              (keep
               (fn [[basis-key generation]]
                 (when-let [tier->entries (get entries [:exact basis-key])]
                   {:basis-key basis-key
                    :order (:order generation)
                    :subproblems
                    (subproblem/snapshot-value
                     (:subproblems generation) tier->entries)})))
              exact)
        managed-snapshots
        (into []
              (keep
               (fn [[generation-key generation]]
                 (when-let [tier->entries (get entries
                                               [:managed generation-key])]
                   {:generation-key generation-key
                    :schema-generation (:schema-generation generation)
                    :subproblems
                    (subproblem/snapshot-value
                     (:subproblems generation) tier->entries)})))
              managed)]
    {:format basis-snapshot-format
     :contract {:retained-bases (:retained-bases store)}
     :exact exact-snapshots
     :managed managed-snapshots
     :generation-counts {:exact (count exact-snapshots)
                         :managed (count managed-snapshots)}
     :entry-count entry-count
     :retained-weight retained-weight}))

(defn- incompatible-snapshot!
  [message data]
  (throw
   (ex-info message
            (merge {:type :eacl/cache-snapshot-incompatible
                    :eacl/error :eacl/cache-snapshot-incompatible}
                   data))))

(defn- closed-map?
  [value expected-keys]
  (and (map? value) (= expected-keys (set (keys value)))))

(defn- validate-basis-snapshot-shape!
  [store snapshot max-weight max-entries]
  (when-not
   (closed-map? snapshot
                #{:format :contract :exact :managed :generation-counts
                  :entry-count :retained-weight})
    (incompatible-snapshot! "Malformed basis cache snapshot."
                            {:snapshot-keys (some-> snapshot keys set)}))
  (when-not (= basis-snapshot-format (:format snapshot))
    (incompatible-snapshot! "Unsupported basis cache snapshot format."
                            {:format (:format snapshot)}))
  (when-not (closed-map? (:contract snapshot) #{:retained-bases})
    (incompatible-snapshot! "Malformed basis cache snapshot contract."
                            {:contract (:contract snapshot)}))
  (when-not (and (integer? (get-in snapshot [:contract :retained-bases]))
                 (pos? (get-in snapshot [:contract :retained-bases])))
    (incompatible-snapshot! "Invalid retained-bases snapshot contract."
                            {:contract (:contract snapshot)}))
  (when-not (and (vector? (:exact snapshot))
                 (vector? (:managed snapshot)))
    (incompatible-snapshot! "Basis cache generations must be vectors." {}))
  (when (or (> (count (:exact snapshot)) (:retained-bases store))
            (> (count (:managed snapshot)) (:retained-bases store)))
    (incompatible-snapshot! "Snapshot exceeds retained generation capacity."
                            {:retained-bases (:retained-bases store)}))
  (when-not
   (= {:exact (count (:exact snapshot))
       :managed (count (:managed snapshot))}
      (:generation-counts snapshot))
    (incompatible-snapshot! "Snapshot generation totals do not match."
                            {:generation-counts (:generation-counts snapshot)}))
  (when-not (and (integer? (:entry-count snapshot))
                 (not (neg? (:entry-count snapshot)))
                 (<= (:entry-count snapshot) max-entries)
                 (integer? (:retained-weight snapshot))
                 (not (neg? (:retained-weight snapshot)))
                 (<= (:retained-weight snapshot) max-weight))
    (incompatible-snapshot! "Snapshot exceeds restore bounds."
                            {:entry-count (:entry-count snapshot)
                             :retained-weight (:retained-weight snapshot)
                             :max-entries max-entries
                             :max-weight max-weight})))

(defn- restore-exact-descriptor
  [store descriptor]
  (when-not (closed-map? descriptor #{:basis-key :order :subproblems})
    (incompatible-snapshot! "Malformed exact cache generation."
                            {:descriptor descriptor}))
  (when-not (and (valid-exact-basis-key? (:basis-key descriptor))
                 (integer? (:order descriptor)))
    (incompatible-snapshot! "Invalid exact cache generation identity."
                            {:basis-key (:basis-key descriptor)
                             :order (:order descriptor)}))
  (assoc descriptor
         :store
         (subproblem/restore-store
          (:subproblems descriptor)
          (dissoc (:subproblem-options store) :enabled?)
          (:content-revision store))))

(defn- restore-managed-descriptor
  [store descriptor]
  (when-not
   (closed-map? descriptor
                #{:generation-key :schema-generation :subproblems})
    (incompatible-snapshot! "Malformed managed cache generation."
                            {:descriptor descriptor}))
  (let [generation-key (:generation-key descriptor)
        schema-generation (:schema-generation descriptor)]
    (when-not (and (subproblem/proof-stamp? schema-generation)
                   (= generation-key
                      (managed-generation-key
                       (:lineage generation-key) schema-generation)))
      (incompatible-snapshot! "Invalid managed cache generation identity."
                              {:generation-key generation-key
                               :schema-generation schema-generation})))
  (assoc descriptor
         :store
         (subproblem/restore-store
          (:subproblems descriptor)
          (dissoc (:subproblem-options store) :enabled?)
          (:content-revision store))))

(defn- restored-generation-state
  [descriptors key-fn generation-fn]
  (let [generation-count (count descriptors)]
    {:tick generation-count
     :generations
     (into {}
           (map-indexed
            (fn [index descriptor]
              (let [last-used (- generation-count index)]
                [(key-fn descriptor)
                 (generation-fn descriptor last-used)])))
           descriptors)}))

(defn restore-basis-snapshot!
  "Atomically replaces one basis-cache lifecycle from a trusted snapshot value.

  A host reading external bytes MUST authenticate and encoded-size-bound the
  envelope before deserialization. Any structural or compatibility failure
  leaves the currently visible lifecycle unchanged."
  [store snapshot {:keys [max-weight max-entries]}]
  (when-not (basis-cache? store)
    (throw (ex-info "Expected an EACL basis cache."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :cache store})))
  (positive-snapshot-bound! :max-weight max-weight)
  (positive-snapshot-bound! :max-entries max-entries)
  (validate-basis-snapshot-shape! store snapshot max-weight max-entries)
  (let [exact (mapv #(restore-exact-descriptor store %) (:exact snapshot))
        managed
        (mapv #(restore-managed-descriptor store %) (:managed snapshot))
        exact-keys (mapv :basis-key exact)
        managed-keys (mapv :generation-key managed)]
    (when-not (= (count exact-keys) (count (set exact-keys)))
      (incompatible-snapshot! "Duplicate exact cache generation identity." {}))
    (when-not (= (count managed-keys) (count (set managed-keys)))
      (incompatible-snapshot! "Duplicate managed cache generation identity." {}))
    (let [actual-entry-count
          (reduce + 0
                  (map #(get-in % [:subproblems :entry-count])
                       (concat exact managed)))
          actual-retained-weight
          (reduce + 0
                  (map #(get-in % [:subproblems :retained-weight])
                       (concat exact managed)))]
      (when-not (and (= actual-entry-count (:entry-count snapshot))
                     (= actual-retained-weight (:retained-weight snapshot)))
        (incompatible-snapshot! "Basis snapshot totals do not match entries."
                                {:declared-entry-count (:entry-count snapshot)
                                 :actual-entry-count actual-entry-count
                                 :declared-retained-weight
                                 (:retained-weight snapshot)
                                 :actual-retained-weight
                                 actual-retained-weight})))
    (let [lifecycle
          (->CacheLifecycle
           (atom
            (restored-generation-state
             exact :basis-key
             (fn [{:keys [order store]} last-used]
               (->ExactGeneration nil order store last-used (atom 0) 0))))
           (atom
            (restored-generation-state
             managed :generation-key
             (fn [{:keys [generation-key schema-generation store]} last-used]
               (->ManagedGeneration
                generation-key schema-generation store last-used
                (atom 0) 0)))))]
      (reset! (:lifecycle store) lifecycle)
      (reset! (:admissions store) empty-answer-sightings)
      (reset! (:managed-lifting-disabled? store) false)
      (reset! (:reported-contract-violations store) #{})
      (record-metrics! store update :restores inc)
      (swap! (:content-revision store) inc)
      {:restored? true
       :generation-counts (:generation-counts snapshot)
       :entry-count (:entry-count snapshot)
       :retained-weight (:retained-weight snapshot)})))

(defn- valid-managed-descriptor?
  [descriptor]
  (let [{:keys [schema-generation dependency-stamp]} descriptor]
    (and (map? descriptor)
         (subproblem/proof-stamp? schema-generation)
         (subproblem/proof-stamp? dependency-stamp))))

(defn- managed-descriptor
  [store managed-key-fn]
  (when (and managed-key-fn
             (not @(:managed-lifting-disabled? store)))
    (try
      (let [descriptor (managed-key-fn)]
        (when (valid-managed-descriptor? descriptor)
          descriptor))
      (catch #?(:clj Exception :cljs :default) _
        (record-metrics! store update :stamp-failures inc)
        nil))))

(def ^:dynamic ^:no-doc *current-cache-specialization-enabled?* true)

(defn ^:no-doc specialized-current-cache-action
  [stage available?]
  (cache-refinement/action stage available?))

(defn ^:no-doc current-cache-specialization-authorized?
  [kernel]
  (and (identical? kernel subproblem/default-decision-kernel)
       (cache-refinement/authorized-selection?
        subproblem/default-current-cache-refinement)))

(defn- current-cache-action
  [decision-kernel stage available?]
  (let [kernel (or decision-kernel subproblem/*decision-kernel*)]
    (if (and *current-cache-specialization-enabled?*
             (current-cache-specialization-authorized? kernel))
      (specialized-current-cache-action stage available?)
      (verified/decide
       kernel
       :current-cache-decision
       {:stage stage :available? available?}))))

(defn resolve-exact!
  "Resolves an answer in one historical-class exact-basis generation.

  Historical bases never probe or publish the managed tier. Their completed
  answers, projections, and traversal subproblems are isolated inside the
  generation selected by the complete basis identity."
  [store
   {:keys [snapshot snapshot-order exact-basis-key cache-basis
           cache-lifecycle decision-kernel remember-answer? answer-weight-fn
           populate-cache?]
    :or {remember-answer? true
         populate-cache? true}}
   semantic-key kind valid-value? compute]
  (when-not (fn? compute)
    (throw (ex-info "Exact-basis cache computation must be a function."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config})))
  (when-not (basis-cache? store)
    (throw (ex-info "Expected an EACL basis cache."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :cache store})))
  (let [uncached-compute
        (fn []
          (binding [subproblem/*store* nil
                    subproblem/*managed-store* nil
                    subproblem/*managed-key-fn* nil
                    subproblem/*managed-scope* nil
                    subproblem/*decision-kernel*
                    (or decision-kernel subproblem/*decision-kernel*)]
            (subproblem/with-decision-memo compute)))]
    (if-not (valid-exact-basis-key? exact-basis-key)
      (do
        (record-metrics! store update :bypasses inc)
        {:value (uncached-compute)
         :cached? false
         :cache-tier nil
         :cache-basis nil})
      (let [lifecycle (or cache-lifecycle @(:lifecycle store))
            order (or snapshot-order
                      (get-in exact-basis-key
                              [:basis-identity :revision]))
            {:keys [generation]}
            (select-exact-generation!
             store lifecycle exact-basis-key snapshot order populate-cache?)
            answer-store (:subproblems generation)
            entry-key [semantic-key kind]
            answer-options
            (assoc (answer-entry-options valid-value? answer-weight-fn)
                   :populate? populate-cache?)
            exact-entry
            (when (and remember-answer? answer-store)
              (:value
               (subproblem/lookup!
                answer-store :answer entry-key answer-options)))
            exact-action
            (current-cache-action
             decision-kernel :exact-only-entry (some? exact-entry))
            evaluate-entry
            (fn []
              {:value
               (binding [subproblem/*populate?* populate-cache?
                         subproblem/*store*
                         (when (get (:subproblem-options store)
                                    :enabled? true)
                           answer-store)
                         subproblem/*managed-store* nil
                         subproblem/*managed-key-fn* nil
                         subproblem/*managed-scope* nil
                         subproblem/*decision-kernel*
                         (or decision-kernel subproblem/*decision-kernel*)]
                 (subproblem/with-decision-memo compute))
               :cache-basis cache-basis})
            hit
            (fn [entry]
              (record-metrics! store update :exact-hits inc)
              {:value (:value entry)
               :cached? true
               :cache-tier :exact-basis
               :cache-basis cache-basis
               :subproblem-store answer-store})]
        (if (= :use-exact-entry exact-action)
          (hit exact-entry)
          (if (and remember-answer?
                   answer-store
                   populate-cache?
                   (admit-answer?
                    store [:exact-basis exact-basis-key entry-key]))
            (let [resolved
                  (subproblem/resolve-independent!
                   answer-store :answer entry-key answer-options
                   evaluate-entry)
                  entry (:value resolved)]
              (if (:cached? resolved)
                (hit entry)
                (do
                  (record-metrics!
                   store
                   #(-> %
                        (update :misses inc)
                        (update :puts inc)))
                  {:value (:value entry)
                   :cached? false
                   :cache-tier nil
                   :cache-basis cache-basis
                   :subproblem-store answer-store})))
            (let [entry (evaluate-entry)]
              (record-metrics!
               store update (if remember-answer? :misses :bypasses) inc)
              {:value (:value entry)
               :cached? false
               :cache-tier nil
               :cache-basis cache-basis
               :subproblem-store answer-store})))))))

(defn resolve-basis!
  "Resolves an ordinary-class answer through exact-basis then managed tiers.

  `:exact-basis-key` identifies the selected immutable value completely.
  `:managed-key-fn` may supply certified schema and dependency frontiers from
  that same value. Managed reuse is lineage- and lifecycle-scoped and does not
  compare revisions."
  [store
   {:keys [snapshot snapshot-order exact-basis-key cache-basis cacheable?
           cache-lifecycle
           managed-key-fn
           managed-subproblem-key-fn managed-subproblem-scope
           decision-kernel remember-answer? answer-weight-fn
           populate-cache?]
    :or {cacheable? true
         remember-answer? true
         populate-cache? true}}
   semantic-key kind valid-value? compute]
  (when-not (fn? compute)
    (throw (ex-info "Basis cache computation must be a function."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config})))
  (if (= :bypass-current-cache
         (current-cache-action
          decision-kernel
          :eligibility
          (and (some? store) cacheable?)))
    (do
      (when (basis-cache? store)
        (record-metrics! store update :bypasses inc))
      {:value (binding [subproblem/*store* nil
                        subproblem/*managed-store* nil
                        subproblem/*managed-key-fn* nil
                        subproblem/*managed-scope* nil
                        subproblem/*decision-kernel*
                        (or decision-kernel
                            subproblem/*decision-kernel*)]
                (compute))
       :cached? false
       :cache-tier nil
       :cache-basis nil})
    (do
      (when-not (basis-cache? store)
        (throw (ex-info "Expected an EACL basis cache."
                        {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                         :cache store})))
      (when-not (and (integer? snapshot-order)
                     (not (neg? snapshot-order)))
        (throw (ex-info "Invalid basis-cache snapshot context."
                        {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                         :snapshot-order snapshot-order})))
      (let [lifecycle (or cache-lifecycle @(:lifecycle store))
            {:keys [generation active?]}
            (select-exact-generation!
             store lifecycle exact-basis-key snapshot snapshot-order
             populate-cache?)
            entry-key [semantic-key kind]
            admission-key [:exact-basis exact-basis-key entry-key]]
        (if (= :bypass-current-cache
               (current-cache-action
                decision-kernel :generation active?))
          (do
            (record-metrics! store update :bypasses inc)
            {:value (binding [subproblem/*store* nil
                              subproblem/*managed-store* nil
                              subproblem/*managed-key-fn* nil
                              subproblem/*managed-scope* nil
                              subproblem/*decision-kernel*
                              (or decision-kernel
                                  subproblem/*decision-kernel*)]
                      (compute))
             :cached? false
             :cache-tier nil
             :cache-basis nil})
          (let [answer-store (:subproblems generation)
                answer-options
                (assoc (answer-entry-options valid-value? answer-weight-fn)
                       :populate? populate-cache?)
                exact-entry
                (when (and remember-answer? answer-store)
                  (:value
                   (subproblem/lookup!
                    answer-store :answer entry-key answer-options)))
                exact-action
                (current-cache-action
                 decision-kernel :exact-entry (some? exact-entry))]
            (if (= :use-exact-entry exact-action)
              (do
                (record-metrics! store update :exact-hits inc)
                {:value (:value exact-entry)
                 :cached? true
                 :cache-tier :exact-basis
                 :cache-basis (:cache-basis exact-entry)
                 :subproblem-store answer-store})
              (let [{:keys [schema-generation dependency-stamp]}
                    (managed-descriptor
                     store managed-key-fn)
                    managed-generation
                    (when (some? schema-generation)
                      (select-managed-generation!
                       store lifecycle managed-subproblem-scope
                       schema-generation populate-cache?))
                    managed-store (:subproblems managed-generation)
                    managed-entry-key
                    (when managed-generation
                      [semantic-key kind dependency-stamp])
                    managed-entry
                    (when (and remember-answer? managed-entry-key)
                      (:value
                       (subproblem/lookup!
                        managed-store :answer managed-entry-key
                        answer-options)))
                    managed-action
                    (current-cache-action
                     decision-kernel :managed-entry (some? managed-entry))]
                (if (= :use-managed-entry managed-action)
                  (do
                  ;; Promote the still-valid managed answer into the selected
                  ;; exact generation so the next identical request hits
                  ;; without dependency-stamp extraction.
                    (when (and populate-cache? answer-store)
                      (subproblem/resolve-independent!
                       answer-store :answer entry-key answer-options
                       (fn [] managed-entry))
                      (record-metrics! store update :puts inc))
                    (record-metrics! store update :managed-hits inc)
                    {:value (:value managed-entry)
                     :cached? true
                     :cache-tier :managed-current
                     :cache-basis (:cache-basis managed-entry)
                     :subproblem-store answer-store})
                  (let [subproblems-enabled?
                        (get (:subproblem-options store) :enabled? true)
                        compute-entry
                        (fn []
                          {:value
                           (binding [subproblem/*populate?* populate-cache?
                                     subproblem/*store*
                                     (when subproblems-enabled?
                                       answer-store)
                                     subproblem/*managed-store*
                                     (when subproblems-enabled?
                                       managed-store)
                                     subproblem/*managed-key-fn*
                                     managed-subproblem-key-fn
                                     subproblem/*managed-scope*
                                     managed-subproblem-scope
                                     subproblem/*decision-kernel*
                                     (or decision-kernel
                                         subproblem/*decision-kernel*)]
                             (subproblem/with-decision-memo compute))
                           :cache-basis cache-basis})
                        layered-compute
                        (fn []
                          (if managed-entry-key
                            (:value
                             (subproblem/resolve-independent!
                              managed-store :answer managed-entry-key
                              answer-options compute-entry))
                            (compute-entry)))]
                    (if (and remember-answer?
                             answer-store
                             populate-cache?
                             (admit-answer? store admission-key))
                      (let [resolved
                            (subproblem/resolve-independent!
                             answer-store :answer entry-key
                             answer-options layered-compute)
                            entry (:value resolved)]
                        (if (:cached? resolved)
                        ;; A compatible completed value was already visible
                        ;; at lookup time; serve it as the exact hit it is.
                          (do
                            (record-metrics! store update :exact-hits inc)
                            {:value (:value entry)
                             :cached? true
                             :cache-tier :exact-basis
                             :cache-basis (:cache-basis entry)
                             :subproblem-store answer-store})
                          (do
                            (record-metrics! store update :misses inc)
                            (record-metrics! store update :puts inc)
                            {:value (:value entry)
                             :cached? false
                             :cache-tier nil
                             :cache-basis (:cache-basis entry)
                             :subproblem-store answer-store})))
                      (let [entry (compute-entry)]
                        (record-metrics!
                         store update
                         (if remember-answer? :misses :bypasses) inc)
                        {:value (:value entry)
                         :cached? false
                         :cache-tier nil
                         :cache-basis cache-basis
                         :subproblem-store answer-store}))))))))))))

(defn resolve-managed-read-only!
  "Resolves a speculative operation only through the committed managed tier.

  The caller supplies a proof descriptor certified against the speculative
  snapshot's committed root after checking cumulative-effect disjointness.
  Exact generations are neither selected nor inspected. A miss evaluates with
  publication disabled while still allowing proof-keyed managed subproblem
  reads from an already existing generation."
  [store
   {:keys [cache-lifecycle managed-key-fn managed-subproblem-key-fn
           managed-subproblem-scope decision-kernel]}
   semantic-key kind valid-value? compute]
  (when-not (fn? compute)
    (throw
     (ex-info
      "Managed read-only cache computation must be a function."
      {:type :eacl/invalid-config :eacl/error :eacl/invalid-config})))
  (if-not (basis-cache? store)
    {:value (binding [subproblem/*populate?* false
                      subproblem/*store* nil
                      subproblem/*managed-store* nil
                      subproblem/*managed-key-fn* nil
                      subproblem/*managed-scope* nil
                      subproblem/*decision-kernel*
                      (or decision-kernel subproblem/*decision-kernel*)]
              (subproblem/with-decision-memo compute))
     :cached? false
     :cache-tier nil
     :cache-basis nil}
    (let [lifecycle (or cache-lifecycle @(:lifecycle store))
          {:keys [schema-generation dependency-stamp]}
          (managed-descriptor store managed-key-fn)
          generation
          (when (some? schema-generation)
            (select-managed-generation!
             store lifecycle managed-subproblem-scope
             schema-generation false))
          managed-store (:subproblems generation)
          entry-key
          (when generation [semantic-key kind dependency-stamp])
          answer-options
          (assoc (answer-entry-options valid-value? nil) :populate? false)
          entry
          (when entry-key
            (:value
             (subproblem/lookup!
              managed-store :answer entry-key answer-options)))]
      (if entry
        (do
          (record-metrics! store update :managed-hits inc)
          {:value (:value entry)
           :cached? true
           :cache-tier :managed-current
           :cache-basis (:cache-basis entry)
           :subproblem-store nil})
        (do
          (record-metrics! store update :bypasses inc)
          {:value
           (binding [subproblem/*populate?* false
                     subproblem/*store* nil
                     subproblem/*managed-store* managed-store
                     subproblem/*managed-key-fn* managed-subproblem-key-fn
                     subproblem/*managed-scope* managed-subproblem-scope
                     subproblem/*decision-kernel*
                     (or decision-kernel subproblem/*decision-kernel*)]
             (subproblem/with-decision-memo compute))
           :cached? false
           :cache-tier nil
           :cache-basis nil
           :subproblem-store nil})))))

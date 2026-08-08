(ns eacl.cache
  "Backend-neutral private current-generation caching.

  The portable CacheStore protocol family remains the provider/continuation
  adapter surface; the unreachable authenticated-envelope completed-cache
  path that once lived here was deleted by trusted-surface-hygiene 11.1
  (native completed answers are client-private and never flow through
  portable providers)."
  (:require [eacl.subproblem-cache :as subproblem]
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
                    {:type :eacl/invalid-request
                     :key :cache?
                     :value cache-option})))
  cache-option)

(defn lookup-page-query-identity
  "Builds the semantic cache identity for an authenticated lookup page.

  Public cursors are signed transport envelopes whose snapshot metadata can
  change when the same logical boundary is recovered on a newer current
  snapshot. The authenticated internal boundary is the semantic position.
  `:rebase?` is likewise an execution instruction, not part of that position.

  Call only after the public cursor has been authenticated and internalized."
  [public-query internal-query]
  (let [semantic-bound
        (fn [bound]
          (if (map? bound)
            (dissoc bound :rebase?)
            bound))]
    {:public
     (dissoc public-query
             :consistency :cache? :after :before)
     :internal
     (cond-> (dissoc internal-query :consistency :cache?)
       (contains? internal-query :after)
       (update :after semantic-bound)

       (contains? internal-query :before)
       (update :before semantic-bound))}))

(defrecord ExactGeneration [snapshot order subproblems])
(defrecord ManagedGeneration
  [schema-stamp installed-order subproblems])
(defrecord CacheLifecycle [exact managed])
(defrecord CurrentGenerationCache [lifecycle metrics max-entries admissions
                                   admit-on-repeat? subproblem-options
                                   subproblem-coordinator])

(defn- new-lifecycle
  []
  (->CacheLifecycle (atom nil) (atom nil)))

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

(defn- default-answer-weight
  "Conservative retained-size estimate for one completed answer.

  Mirrors the page-weight family the Datomic backend supplies explicitly:
  paged results weigh in by row count; scalar decisions and counts pay a
  flat floor. Callers with better knowledge pass `:answer-weight-fn`."
  [value]
  (let [data (when (map? value) (:data value))]
    (if (counted? data)
      (+ 512 (* 128 (count data)))
      512)))

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

(defn current-cache
  "Creates the private, client-owned completed-answer cache.

  Exact entries belong to one immutable selected DB value. Managed entries
  survive unrelated forward transactions under an explicit backend stamp
  contract. Neither tier is a portable provider or a historical cache.

  Completed answers are stored in the `:answer` tier of the lifecycle's
  weighted subproblem stores: byte-weight bounded (`:subproblem-cache
  {:answer-max-weight n}`, default 16 MiB), least-recently-used eviction,
  and a per-entry ceiling of one quarter of the budget with oversized
  rejection. `:max-entries` no longer bounds stored answers — the weight
  budget does — but remains accepted: it sizes the `:admit-on-repeat?`
  second-sighting window (default 1024), its historical admission role."
  ([]
   (current-cache {}))
  ([{:keys [max-entries admit-on-repeat? subproblem-cache]
     :or {max-entries 1024
          admit-on-repeat? false
          subproblem-cache {}}}]
   (when-not (and (integer? max-entries) (pos? max-entries))
     (throw (ex-info "Current cache :max-entries must be positive."
                     {:type :eacl/invalid-config
                      :max-entries max-entries})))
   (when-not (boolean? admit-on-repeat?)
     (throw (ex-info "Current cache :admit-on-repeat? must be boolean."
                     {:type :eacl/invalid-config
                      :admit-on-repeat? admit-on-repeat?})))
   (when-not (map? subproblem-cache)
     (throw (ex-info "Current cache :subproblem-cache must be a map."
                     {:type :eacl/invalid-config
                      :subproblem-cache subproblem-cache})))
   (when-not (boolean? (get subproblem-cache :enabled? true))
     (throw (ex-info "Current cache subproblem :enabled? must be boolean."
                     {:type :eacl/invalid-config
                      :subproblem-cache subproblem-cache})))
   ;; Validate budgets before a request attempts to install a generation, then
   ;; keep the execution coordinator above exact/schema generation replacement.
   ;; Old detached work and new-generation work therefore consume one client
   ;; lifecycle-wide concurrency budget.
   (let [validated
         (subproblem/store (dissoc subproblem-cache :enabled?))
         coordinator
         (subproblem/computation-coordinator (:max-inflight validated))]
     (->CurrentGenerationCache
      (atom (new-lifecycle))
      (atom {:exact-hits 0
             :managed-hits 0
             :misses 0
             :bypasses 0
             :stamp-failures 0
             :puts 0
             :expirations 0})
      max-entries
      (atom empty-answer-sightings)
      admit-on-repeat?
      subproblem-cache
      coordinator))))

(defn current-cache?
  [value]
  (instance? CurrentGenerationCache value))

(defn current-cache-for-option
  "Builds the private current cache corresponding to a public `:cache` option.

  `no-cache` disables it. Portable/custom CacheStore values serve
  provider and telemetry roles, but completed native answers are isolated
  in this client-owned cache. A config map contributes only native capacity
  settings."
  [value]
  (cond
    (no-cache? value) nil
    (current-cache? value)
    (throw
     (ex-info
      "A current-generation cache is owned by exactly one EACL client and cannot be supplied as a client cache option."
      {:type :eacl/invalid-config
       :reason :client-private-cache-reuse}))
    (and (map? value)
         (not (satisfies? CacheStore value)))
    (current-cache
     (select-keys value
                  [:max-entries :admit-on-repeat? :subproblem-cache]))
    :else
    (current-cache)))

(defn current-cache-stats
  [store]
  (when-not (current-cache? store)
    (throw (ex-info "Expected an EACL current-generation cache."
                    {:type :eacl/invalid-config
                     :cache store})))
  (let [lifecycle @(:lifecycle store)
        exact @(:exact lifecycle)
        managed @(:managed lifecycle)
        exact-stats
        (when-let [subproblems (:subproblems exact)]
          (subproblem/stats subproblems))
        managed-stats
        (when-let [subproblems (:subproblems managed)]
          (subproblem/stats subproblems))]
    (assoc @(:metrics store)
           :exact-entries
           (get-in exact-stats [:tiers :answer :entries] 0)
           :managed-entries
           (get-in managed-stats [:tiers :answer :entries] 0)
           :active-subproblem-computations
           @(:active (:subproblem-coordinator store))
           :max-subproblem-computations
           (:maximum (:subproblem-coordinator store))
           :admission-entries
           (count (:seen @(:admissions store)))
           :subproblems
           (or exact-stats
               (subproblem/stats
                (subproblem/store
                 (dissoc (:subproblem-options store) :enabled?))))
           :managed-subproblems
           managed-stats)))

(defn record-current-bypass!
  "Records that a configured native cache was deliberately skipped without
  entering cache key/stamp resolution."
  [store]
  (when store
    (when-not (current-cache? store)
      (throw (ex-info "Expected an EACL current-generation cache."
                      {:type :eacl/invalid-config
                       :cache store})))
    (swap! (:metrics store) update :bypasses inc))
  nil)

(defn expire-current!
  "Atomically makes every exact and managed entry unreachable.

  In-flight work retains only the old lifecycle object and can therefore
  publish only into unreachable generations."
  [store]
  (when-not (current-cache? store)
    (throw (ex-info "Expected an EACL current-generation cache."
                    {:type :eacl/invalid-config
                     :cache store})))
  (reset! (:lifecycle store) (new-lifecycle))
  (reset! (:admissions store) empty-answer-sightings)
  (swap! (:metrics store) update :expirations inc)
  nil)

(defn- install-exact-generation!
  "Installs (or reuses) the exact generation for one selected snapshot.

  The generation's weighted subproblem store is created unconditionally: it
  now also carries the completed-answer `:answer` tier, so `:subproblem-cache
  {:enabled? false}` disables traversal-subproblem bindings without disabling
  completed-answer storage."
  [exact snapshot order same-snapshot? subproblem-options coordinator]
  (loop []
    (let [current @exact]
      (cond
        (and current
             (same-snapshot? snapshot (:snapshot current)))
        {:generation current :active? true}

        (or (nil? current)
            (< (:order current) order))
        (let [created
              (->ExactGeneration
               snapshot order
               (subproblem/store
                (assoc (dissoc subproblem-options :enabled?)
                       :computation-coordinator coordinator)))]
          (if (compare-and-set! exact current created)
            {:generation created :active? true}
            (recur)))

        ;; A delayed older request, or an unsupported reset that reused the
        ;; numeric order, must not replace the installed current generation.
        :else
        {:generation nil :active? false}))))

(defn- install-managed-generation!
  [managed schema-stamp order subproblem-options coordinator]
  (loop []
    (let [current @managed]
      (cond
        (and current (= schema-stamp (:schema-stamp current)))
        current

        (or (nil? current)
            (< (:installed-order current) order))
        (let [created
              (->ManagedGeneration
               schema-stamp
               order
               (subproblem/store
                (assoc (dissoc subproblem-options :enabled?)
                       :computation-coordinator coordinator)))]
          (if (compare-and-set! managed current created)
            created
            (recur)))

        ;; A delayed request on an older schema cannot roll the cache back.
        :else
        nil))))

(defn- valid-managed-descriptor?
  [descriptor]
  (let [{:keys [schema-stamp dependency-stamp]} descriptor]
    (and (map? descriptor)
         (subproblem/proof-stamp? schema-stamp)
         (subproblem/proof-stamp? dependency-stamp))))

(defn- managed-descriptor
  [store subproblem-store descriptor-key-fn managed-key-fn]
  (when managed-key-fn
    (try
      (let [descriptor-key
            (when descriptor-key-fn (descriptor-key-fn))
            descriptor
            (if (and subproblem-store descriptor-key)
              (:value
               (subproblem/resolve!
                subproblem-store
                :denotation
                [:managed-descriptor 1 descriptor-key]
                {:valid? valid-managed-descriptor?
                 :weight-fn (constantly 160)}
                managed-key-fn))
              (managed-key-fn))]
        (when (valid-managed-descriptor? descriptor)
          descriptor))
      (catch #?(:clj Exception :cljs :default) _
        (swap! (:metrics store) update :stamp-failures inc)
        nil))))

(defn- current-cache-action
  [decision-kernel stage available?]
  (verified/decide
   (or decision-kernel subproblem/*decision-kernel*)
   :current-cache-decision
   {:stage stage :available? available?}))

(defn resolve-current!
  "Resolves one completed semantic answer against a captured current snapshot.

  `context` requires `:snapshot`, monotone integer `:snapshot-order`, and a
  `:same-snapshot?` predicate. `:cacheable? false` is the explicit boundary
  for exact, historical, and arbitrary-db evaluation. An optional
  `:managed-key-fn` is invoked only after an exact miss and must return numeric
  `:schema-stamp` and `:dependency-stamp` values extracted from that same
  immutable snapshot. An optional `:answer-weight-fn` estimates the retained
  size of one completed answer value for the weighted `:answer` tier;
  omitted, a conservative page-size default applies.

  Completed answers live in the `:answer` tier of the lifecycle's exact and
  managed subproblem stores: weight-bounded, least-recently-used, oversized
  rejecting, and single-flighted. Exact keying is the semantic key plus kind
  on the selected generation's store; managed keying adds the dependency
  stamp on the schema-generation store.

  Returns `{:value v :cached? b :cache-tier tier :cache-basis basis}`."
  [store
   {:keys [snapshot snapshot-order same-snapshot? cache-basis cacheable?
           managed-descriptor-key-fn managed-key-fn
           managed-subproblem-key-fn managed-subproblem-scope
           decision-kernel remember-answer? answer-weight-fn]
    :or {same-snapshot? =
         cacheable? true
         remember-answer? true}}
   semantic-key kind valid-value? compute]
  (when-not (fn? compute)
    (throw (ex-info "Current cache computation must be a function."
                    {:type :eacl/invalid-config})))
  (if (= :bypass-current-cache
         (current-cache-action
          decision-kernel
          :eligibility
          (and (some? store) cacheable?)))
    (do
      (when (current-cache? store)
        (swap! (:metrics store) update :bypasses inc))
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
      (when-not (current-cache? store)
        (throw (ex-info "Expected an EACL current-generation cache."
                        {:type :eacl/invalid-config
                         :cache store})))
      (when-not (and (integer? snapshot-order)
                     (not (neg? snapshot-order))
                     (fn? same-snapshot?))
        (throw (ex-info "Invalid current-cache snapshot context."
                        {:type :eacl/invalid-config
                         :snapshot-order snapshot-order})))
      (let [lifecycle @(:lifecycle store)
            {:keys [generation active?]}
            (install-exact-generation!
             (:exact lifecycle)
             snapshot snapshot-order same-snapshot?
             (:subproblem-options store)
             (:subproblem-coordinator store))
            entry-key [semantic-key kind]]
        (if (= :bypass-current-cache
               (current-cache-action
                decision-kernel :generation active?))
          (do
            (swap! (:metrics store) update :bypasses inc)
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
                (answer-entry-options valid-value? answer-weight-fn)
                exact-entry
                (when remember-answer?
                  (:value
                   (subproblem/lookup!
                    answer-store :answer entry-key answer-options)))
                exact-action
                (current-cache-action
                 decision-kernel :exact-entry (some? exact-entry))]
            (if (= :use-exact-entry exact-action)
            (do
              (swap! (:metrics store) update :exact-hits inc)
              {:value (:value exact-entry)
               :cached? true
               :cache-tier :exact-current
               :cache-basis (:cache-basis exact-entry)
               :subproblem-store answer-store})
            (let [{:keys [schema-stamp dependency-stamp]}
                  (managed-descriptor
                   store answer-store
                   managed-descriptor-key-fn managed-key-fn)
                  managed-generation
                  (when (some? schema-stamp)
                    (install-managed-generation!
                     (:managed lifecycle)
                     schema-stamp snapshot-order
                     (:subproblem-options store)
                     (:subproblem-coordinator store)))
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
                  (subproblem/resolve!
                   answer-store :answer entry-key answer-options
                   (fn [] managed-entry))
                  (swap! (:metrics store) update :puts inc)
                  (swap! (:metrics store) update :managed-hits inc)
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
                         (binding [subproblem/*store*
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
                           (subproblem/resolve!
                            managed-store :answer managed-entry-key
                            answer-options compute-entry))
                          (compute-entry)))]
                  (if (and remember-answer?
                           (admit-answer? store entry-key))
                    (let [resolved
                          (subproblem/resolve!
                           answer-store :answer entry-key
                           answer-options layered-compute)
                          entry (:value resolved)]
                      (if (:cached? resolved)
                        ;; A concurrent identical request published or
                        ;; single-flighted this answer first; serve it as the
                        ;; exact hit it is.
                        (do
                          (swap! (:metrics store) update :exact-hits inc)
                          {:value (:value entry)
                           :cached? true
                           :cache-tier :exact-current
                           :cache-basis (:cache-basis entry)
                           :subproblem-store answer-store})
                        (do
                          (swap! (:metrics store) update :misses inc)
                          (swap! (:metrics store) update :puts inc)
                          {:value (:value entry)
                           :cached? false
                           :cache-tier nil
                           :cache-basis (:cache-basis entry)
                           :subproblem-store answer-store})))
                    (let [entry (compute-entry)]
                      (swap! (:metrics store)
                             update
                             (if remember-answer? :misses :bypasses)
                             inc)
                      {:value (:value entry)
                       :cached? false
                       :cache-tier nil
                       :cache-basis cache-basis
                       :subproblem-store answer-store}))))))))))))


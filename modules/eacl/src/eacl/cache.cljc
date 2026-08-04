(ns eacl.cache
  "Backend-neutral private current-generation caching plus legacy portable
  authenticated-entry validation."
  (:require [eacl.backend.v8 :as backend]
            [eacl.secure-format :as secure]
            [eacl.subproblem-cache :as subproblem]
            [eacl.verified-kernel :as verified]))

(def cache-entry-version 3)
(def portable-value-version 1)
(def cache-entry-prefix "eacl_ce3_")
(def cache-entry-domain "eacl/cache-entry/envelope/v3")
(def cache-entry-keys
  #{:version :portable-version :key :kind :computed-at :validated-at
    :dependency-scope :proof :value})

(def validation-metric-keys
  [:exact-hit
   :causal-proof-lift
   :content-proof
   :mutation-proof
   :proof-mismatch
   :future-history-rejection
   :unauthenticated-entry
   :no-proof-bypass
   :provider-failure])

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

(defrecord ExactGeneration [snapshot order entries subproblems])
(defrecord ManagedGeneration
  [schema-stamp installed-order entries subproblems])
(defrecord CacheLifecycle [exact managed])
(defrecord CurrentGenerationCache [lifecycle metrics max-entries admissions
                                   admit-on-repeat? subproblem-options
                                   subproblem-coordinator])

(defn- new-lifecycle
  []
  (->CacheLifecycle (atom nil) (atom nil)))

(defn current-cache
  "Creates the private, client-owned completed-answer cache.

  Exact entries belong to one immutable selected DB value. Managed entries
  survive unrelated forward transactions under an explicit backend stamp
  contract. Neither tier is a portable provider or a historical cache."
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
      (atom {})
      admit-on-repeat?
      subproblem-cache
      coordinator))))

(defn current-cache?
  [value]
  (instance? CurrentGenerationCache value))

(defn current-cache-for-option
  "Builds the private current cache corresponding to a public `:cache` option.

  `no-cache` disables it. Portable/custom CacheStore values continue to serve
  provider/telemetry compatibility, but completed native answers are isolated
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
        managed @(:managed lifecycle)]
    (assoc @(:metrics store)
           :exact-entries
           (if exact (count @(:entries exact)) 0)
           :managed-entries
           (if managed (count @(:entries managed)) 0)
           :active-subproblem-computations
           @(:active (:subproblem-coordinator store))
           :max-subproblem-computations
           (:maximum (:subproblem-coordinator store))
           :admission-entries
           (count @(:admissions store))
           :subproblems
           (if (and exact (:subproblems exact))
             (subproblem/stats (:subproblems exact))
             (subproblem/stats
              (subproblem/store
               (dissoc (:subproblem-options store) :enabled?))))
           :managed-subproblems
           (when (and managed (:subproblems managed))
             (subproblem/stats (:subproblems managed))))))

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
  (reset! (:admissions store) {})
  (swap! (:metrics store) update :expirations inc)
  nil)

(defn- bounded-assoc
  [entries key value max-entries]
  (let [updated (assoc entries key value)]
    (if (<= (count updated) max-entries)
      updated
      (let [victim
            (first
             (remove #(= key %) (keys updated)))]
        (if victim
          (dissoc updated victim)
          updated)))))

(defn- admit-entry?
  [store entry-key]
  (if-not (:admit-on-repeat? store)
    true
    (let [repeated? (volatile! false)]
      (swap! (:admissions store)
             (fn [admissions]
               (if (contains? admissions entry-key)
                 (do
                   (vreset! repeated? true)
                   admissions)
                 (bounded-assoc
                  admissions entry-key true (:max-entries store)))))
      @repeated?)))

(defn- put-entry!
  [store entries key value]
  (swap! entries bounded-assoc key value (:max-entries store))
  (swap! (:metrics store) update :puts inc)
  nil)

(defn- valid-current-entry
  [entries key valid-value?]
  (when-let [[_ entry] (find @entries key)]
    (try
      (if (valid-value? (:value entry))
        entry
        (do
          (swap! entries dissoc key)
          nil))
      (catch #?(:clj Exception :cljs :default) _
        (swap! entries dissoc key)
        nil))))

(defn- install-exact-generation!
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
               snapshot order (atom {})
               (when (get subproblem-options :enabled? true)
                 (subproblem/store
                  (assoc (dissoc subproblem-options :enabled?)
                         :computation-coordinator coordinator))))]
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
               (atom {})
               (when (get subproblem-options :enabled? true)
                 (subproblem/store
                  (assoc (dissoc subproblem-options :enabled?)
                         :computation-coordinator coordinator))))]
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
  [engine-selection stage available?]
  (let [legacy
        #(case stage
           (:eligibility :generation)
           (if available?
             :probe-exact-entry
             :bypass-current-cache)

           :exact-entry
           (if available?
             :use-exact-entry
             :probe-managed-entry)

           :managed-entry
           (if available?
             :use-managed-entry
             :compute-current-value))]
    ;; Preserve the default cache hot path. Verified modes cross the generated
    ;; boundary at every authorization-affecting cache-selection stage.
    (if (or (nil? engine-selection)
            (= :legacy-authoritative engine-selection)
            (and (map? engine-selection)
                 (= :legacy-authoritative (:mode engine-selection))))
      (legacy)
      (verified/decide
       engine-selection
       :current-cache-decision
       {:stage stage :available? available?}
       legacy))))

(defn resolve-current!
  "Resolves one completed semantic answer against a captured current snapshot.

  `context` requires `:snapshot`, monotone integer `:snapshot-order`, and a
  `:same-snapshot?` predicate. `:cacheable? false` is the explicit boundary
  for exact, historical, and arbitrary-db evaluation. An optional
  `:managed-key-fn` is invoked only after an exact miss and must return numeric
  `:schema-stamp` and `:dependency-stamp` values extracted from that same
  immutable snapshot.

  Returns `{:value v :cached? b :cache-tier tier :cache-basis basis}`."
  [store
   {:keys [snapshot snapshot-order same-snapshot? cache-basis cacheable?
           managed-descriptor-key-fn managed-key-fn
           managed-subproblem-key-fn managed-subproblem-scope
           engine-selection]
    :or {same-snapshot? =
         cacheable? true}}
   semantic-key kind valid-value? compute]
  (when-not (fn? compute)
    (throw (ex-info "Current cache computation must be a function."
                    {:type :eacl/invalid-config})))
  (if (= :bypass-current-cache
         (current-cache-action
          engine-selection
          :eligibility
          (and (some? store) cacheable?)))
    (do
      (when (current-cache? store)
        (swap! (:metrics store) update :bypasses inc))
      {:value (binding [subproblem/*store* nil
                        subproblem/*managed-store* nil
                        subproblem/*managed-key-fn* nil
                        subproblem/*managed-scope* nil
                        subproblem/*engine-selection* engine-selection]
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
                engine-selection :generation active?))
          (do
            (swap! (:metrics store) update :bypasses inc)
            {:value (binding [subproblem/*store* nil
                              subproblem/*managed-store* nil
                              subproblem/*managed-key-fn* nil
                              subproblem/*managed-scope* nil
                              subproblem/*engine-selection*
                              engine-selection]
                      (compute))
             :cached? false
             :cache-tier nil
             :cache-basis nil})
          (let [entry
                (valid-current-entry
                 (:entries generation) entry-key valid-value?)
                exact-action
                (current-cache-action
                 engine-selection :exact-entry (some? entry))]
            (if (= :use-exact-entry exact-action)
            (do
              (swap! (:metrics store) update :exact-hits inc)
              {:value (:value entry)
               :cached? true
               :cache-tier :exact-current
               :cache-basis (:cache-basis entry)
               :subproblem-store (:subproblems generation)})
            (let [{:keys [schema-stamp dependency-stamp]}
                  (managed-descriptor
                   store (:subproblems generation)
                   managed-descriptor-key-fn managed-key-fn)
                  managed-generation
                  (when (some? schema-stamp)
                    (install-managed-generation!
                     (:managed lifecycle)
                     schema-stamp snapshot-order
                     (:subproblem-options store)
                     (:subproblem-coordinator store)))
                  managed-entry-key
                  (when managed-generation
                    [semantic-key kind dependency-stamp])
                  managed-entry
                  (when managed-entry-key
                    (valid-current-entry
                     (:entries managed-generation)
                     managed-entry-key
                     valid-value?))
                  managed-action
                  (current-cache-action
                   engine-selection :managed-entry (some? managed-entry))]
              (if (= :use-managed-entry managed-action)
                (do
                  (put-entry!
                   store (:entries generation) entry-key managed-entry)
                  (swap! (:metrics store) update :managed-hits inc)
                  {:value (:value managed-entry)
                   :cached? true
                   :cache-tier :managed-current
                   :cache-basis (:cache-basis managed-entry)
                   :subproblem-store (:subproblems generation)})
                (let [value
                      (binding [subproblem/*store*
                                (:subproblems generation)
                                subproblem/*managed-store*
                                (:subproblems managed-generation)
                                subproblem/*managed-key-fn*
                                managed-subproblem-key-fn
                                subproblem/*managed-scope*
                                managed-subproblem-scope
                                subproblem/*engine-selection*
                                engine-selection]
                        (compute))
                      entry {:value value
                             :cache-basis cache-basis}
                      admit? (admit-entry? store entry-key)]
                  (when admit?
                    (put-entry!
                     store (:entries generation) entry-key entry)
                    (when managed-entry-key
                      (put-entry!
                       store
                       (:entries managed-generation)
                       managed-entry-key
                       entry)))
                  (swap! (:metrics store) update :misses inc)
                  {:value value
                   :cached? false
                   :cache-tier nil
                   :cache-basis cache-basis
                   :subproblem-store
                   (:subproblems generation)}))))))))))

(defrecord LocalStore [entries metrics max-entries]
  CacheStore
  (lookup [_ key]
    (let [value (get @entries key)]
      (swap! metrics update (if (some? value) :hits :misses) inc)
      value))
  (store! [_ key value]
    (if (nil? value)
      false
      (do
        (swap! entries
               (fn [current]
                 (let [updated (assoc current key value)]
                   (if (<= (count updated) max-entries)
                     updated
                     ;; Portable reference implementation: deterministic
                     ;; bounded admission, not a claim of LRU ordering.
                     (dissoc updated (first (keys updated)))))))
        (swap! metrics update :puts inc)
        true)))
  (evict! [_ key]
    (let [present? (contains? @entries key)]
      (swap! entries dissoc key)
      present?))
  (clear! [_]
    (reset! entries {})
    nil)
  (stats [_]
    (assoc @metrics :entries (count @entries)))
  CacheTelemetry
  (record-validation! [_ metric]
    (swap! metrics update metric (fnil inc 0))
    nil)
  CacheValidationUpdate
  (store-validation! [_ key expected-entry replacement-entry]
    (let [updated? (atom false)]
      (swap! entries
             (fn [current]
               (if (= expected-entry (get current key))
                 (do
                   (reset! updated? true)
                   (assoc current key replacement-entry))
                 current)))
      @updated?)))

(defn local-store
  ([]
   (local-store {}))
  ([{:keys [max-entries]
     :or {max-entries 1024}}]
   (when-not (and (integer? max-entries) (pos? max-entries))
     (throw (ex-info "Portable cache :max-entries must be positive."
                     {:type :eacl/invalid-config
                      :max-entries max-entries})))
   (->LocalStore (atom {})
                 (atom
                  (merge {:hits 0 :misses 0 :puts 0 :errors 0}
                         (zipmap validation-metric-keys
                                 (repeat 0))))
                 max-entries)))

(defn cache-store
  "Normalizes a client cache option. nil selects the bounded local reference
  store, a map configures it, and a CacheStore is used as supplied."
  [value]
  (cond
    (nil? value) (local-store)
    (satisfies? CacheStore value) value
    (map? value) (local-store value)
    :else
    (throw (ex-info "Expected a portable EACL CacheStore or cache config map."
                    {:type :eacl/invalid-config
                     :cache value}))))

(defn- selected-point
  [adapter]
  {:source-scope
   {:backend (backend/backend-id adapter)
    :scope (backend/invoke adapter :source-scope)}
   :graph-head (backend/invoke adapter :graph-head)
   :snapshot-id (backend/invoke adapter :snapshot-id)})

(defn- complete-key
  [adapter key kind]
  {:cache-version cache-entry-version
   :adapter-version backend/adapter-version
   :source-scope
   {:backend (backend/backend-id adapter)
    :scope (backend/invoke adapter :source-scope)}
   :kind kind
   :adapter-fingerprint (backend/fingerprint adapter)
   :identity-contract (backend/identity-contract adapter)
   :semantic-key key})

(defn- encode-entry
  [format-options payload]
  (secure/encode-authenticated
   (merge (dissoc format-options :engine-selection)
          {:domain cache-entry-domain
           :prefix cache-entry-prefix})
   payload))

(defn- cache-entry
  [format-options key kind computation-point schema-scope relation-ids
   schema-proof relation-proof value]
  (encode-entry
   format-options
   {:version cache-entry-version
    :portable-version portable-value-version
    :key key
    :kind kind
    :computed-at computation-point
    :validated-at computation-point
    :dependency-scope {:schema schema-scope
                       :relations (vec (sort relation-ids))}
    :proof {:schema schema-proof
            :relations relation-proof}
    :value value}))

(defn- boundary-digest
  [domain value]
  (secure/canonical-digest domain value))

(defn- decoded-entry
  [format-options entry]
  (try
    {:status :decoded
     :entry
     (secure/decode-authenticated
      (merge (dissoc format-options :engine-selection)
             {:domain cache-entry-domain
              :prefix cache-entry-prefix
              :payload-keys cache-entry-keys})
      entry)}
    (catch #?(:clj Exception :cljs :default) _
      {:status :unauthenticated-entry})))

(defn- legacy-cache-decision
  [authenticated? key-matches? source-matches? contains? exact? proof-matches?]
  (cond
    (not authenticated?) {:status :miss :reason :unauthenticated}
    (not key-matches?) {:status :miss :reason :scope-mismatch}
    (not source-matches?) {:status :miss :reason :scope-mismatch}
    (not contains?) {:status :miss :reason :future-or-sibling}
    (not proof-matches?) {:status :miss :reason :proof-mismatch}
    :else
    {:status :hit
     :provenance (if exact? :exact-hit :causal-proof-lift)}))

(defn- cache-result
  [decision decoded]
  (if (= :hit (:status decision))
    {:status (:provenance decision)
     :entry decoded}
    {:status
     (case (:reason decision)
       :future-or-sibling :future-history-rejection
       :proof-mismatch :proof-mismatch
       :no-proof-bypass :no-proof-bypass
       :provider-failure :provider-failure
       :missing nil
       :unauthenticated-entry)}))

(defn- valid-entry?
  [adapter format-options entry key kind schema-scope relation-ids
   schema-proof relation-proof valid-value? selected-point]
  (let [{decode-status :status decoded :entry}
        (decoded-entry format-options entry)]
    (if-not (= :decoded decode-status)
      {:status :unauthenticated-entry}
      (let [expected-dependency-scope
            {:schema schema-scope
             :relations (vec (sort relation-ids))}
            authenticated?
            (and (= cache-entry-version (:version decoded))
                 (= portable-value-version (:portable-version decoded))
                 (= kind (:kind decoded))
                 (valid-value? (:value decoded)))
            key-matches?
            (and authenticated?
                 (= (secure/canonicalize
                     [key expected-dependency-scope])
                    (secure/canonicalize
                     [(:key decoded)
                      (:dependency-scope decoded)])))
            source-matches?
            (and authenticated?
                 (= (secure/canonicalize
                     (:source-scope selected-point))
                    (secure/canonicalize
                     (get-in decoded
                             [:computed-at :source-scope]))))
            selected-anchor
            (get-in selected-point [:graph-head :graph-anchor])
            candidate-anchor
            (get-in decoded
                    [:computed-at :graph-head :graph-anchor])
            exact? (= selected-anchor candidate-anchor)
            contains?
            (and authenticated?
                 key-matches?
                 source-matches?
                 (backend/invoke
                  adapter :contains-anchor? candidate-anchor))
            proof-matches?
            (= (secure/canonicalize
                {:schema schema-proof
                 :relations relation-proof})
               (secure/canonicalize (:proof decoded)))
            expected-key
            (boundary-digest
             "eacl/cache/kernel-key/v1"
             [key expected-dependency-scope])
            candidate-key
            (boundary-digest
             "eacl/cache/kernel-key/v1"
             [(:key decoded) (:dependency-scope decoded)])
            expected-source
            (boundary-digest
             "eacl/cache/kernel-source/v1"
             (:source-scope selected-point))
            candidate-source
            (boundary-digest
             "eacl/cache/kernel-source/v1"
             (get-in decoded [:computed-at :source-scope]))
            selected-proof
            (boundary-digest
             "eacl/cache/kernel-proof/v1"
             {:schema schema-proof
              :relations relation-proof})
            candidate-proof
            (boundary-digest
             "eacl/cache/kernel-proof/v1"
             (:proof decoded))
            candidate-graph
            (cond exact? 0 contains? 1 :else 2)
            input
            {:deterministic? (backend/deterministic? adapter)
             :dependency-scope-nonempty?
             (boolean (seq relation-ids))
             :expected-key expected-key
             :expected-source expected-source
             :selected-graph 0
             :ancestors (if (and contains? (not exact?)) #{1} #{})
             :selected-proof selected-proof
             :entry
             {:status :candidate
              :authenticated? (boolean authenticated?)
              :key candidate-key
              :source candidate-source
              :graph candidate-graph
              :proof candidate-proof}}
            decision
            (verified/decide
             (:engine-selection format-options)
             :cache-validation
             input
             #(legacy-cache-decision
               authenticated?
               key-matches?
               source-matches?
               contains?
               exact?
               proof-matches?))]
        (cache-result decision decoded)))))

(defn- safe-store-call
  [fallback f]
  (try
    (f)
    (catch #?(:clj Exception :cljs :default) _
      fallback)))

(defn- note!
  [store metric]
  (when (satisfies? CacheTelemetry store)
    (record-validation! store metric)))

(defn- proof-metric
  [schema-proof relation-proof]
  (if (and (string? schema-proof)
           (or (empty? relation-proof)
               (every? (fn [[_ value]]
                         (string? value))
                       relation-proof)))
    :mutation-proof
    :content-proof))

(defn- dependency-proofs
  [adapter schema-scope relation-ids]
  (let [provider-error #?(:clj (Object.) :cljs (js-obj))
        schema-proof
        (safe-store-call
         provider-error
         #(backend/invoke adapter :schema-proof schema-scope))
        relation-proof
        (if (identical? provider-error schema-proof)
          provider-error
          (safe-store-call
           provider-error
           #(backend/invoke adapter :relation-proof relation-ids)))]
    (if (or (identical? provider-error schema-proof)
            (identical? provider-error relation-proof))
      {:status :provider-failure}
      {:status :available
       :schema-proof schema-proof
       :relation-proof relation-proof})))

(defn resolve!
  "Returns {:value value :cached? boolean :cache-basis snapshot-id}.

  Proofs are read from the same immutable adapter used for authorization.
  Store failure, malformed entries, and proof mismatch are misses; no cached
  value is returned before its opaque schema and relation proofs compare
  exactly."
  ([adapter store key kind schema-scope relation-ids valid-value? compute]
   (resolve! adapter store key kind schema-scope relation-ids
             valid-value? compute {}))
  ([adapter store key kind schema-scope relation-ids valid-value? compute
    format-options]
   (if (no-cache? store)
     {:value (compute)
      :cached? false
      :cache-basis nil}
     (let [{:keys [status schema-proof relation-proof]}
           (dependency-proofs adapter schema-scope relation-ids)]
       (if (= :provider-failure status)
         (do
           (note! store :provider-failure)
           {:value (compute)
            :cached? false
            :cache-basis nil})
         (let [point (selected-point adapter)
               full-key (complete-key adapter key kind)]
           (if (or (not (backend/deterministic? adapter))
                   (nil? schema-proof)
                   (nil? relation-proof)
                   (empty? relation-ids))
             (do
               (note! store :no-proof-bypass)
               {:value (compute)
                :cached? false
                :cache-basis (:snapshot-id point)})
             (let [provider-error #?(:clj (Object.) :cljs (js-obj))
                   stored-entry
                   (safe-store-call provider-error #(lookup store full-key))
                   _ (when (identical? provider-error stored-entry)
                       (note! store :provider-failure))
                   {:keys [status entry]}
                   (if (or (identical? provider-error stored-entry)
                           (nil? stored-entry))
                     {:status nil}
                     (valid-entry?
                      adapter format-options stored-entry full-key kind
                      schema-scope relation-ids
                      schema-proof relation-proof valid-value? point))]
               (when status
                 (note! store status))
               (if entry
                 (do
                   (note! store (proof-metric schema-proof relation-proof))
                   ;; `validated-at` is authenticated telemetry only. Every hit
                   ;; above still re-read the selected snapshot's proof and
                   ;; causal anchor; this update never acts as a lease.
                   (let [replacement
                         (encode-entry
                          format-options
                          (assoc entry :validated-at point))]
                     (safe-store-call
                      false
                      #(if (satisfies? CacheValidationUpdate store)
                         (store-validation!
                          store full-key
                          ;; The authenticated provider value read above is
                          ;; the CAS expectation. A later validator cannot be
                          ;; overwritten by this request after it wins the
                          ;; race.
                          stored-entry
                          replacement)
                         (store! store full-key replacement))))
                   {:value (:value entry)
                    :cached? true
                    :cache-basis
                    (get-in entry [:computed-at :snapshot-id])
                    :cache-computed-at (:computed-at entry)
                    :cache-validated-at point})
                 (let [value (compute)
                       stored?
                       (safe-store-call
                        provider-error
                        #(store! store full-key
                                 (cache-entry
                                  format-options full-key kind point
                                  schema-scope relation-ids
                                  schema-proof relation-proof value)))]
                   (when (identical? provider-error stored?)
                     (note! store :provider-failure))
                   {:value value
                    :cached? false
                    :cache-basis (:snapshot-id point)
                    :cache-computed-at point
                    :cache-validated-at point}))))))))))

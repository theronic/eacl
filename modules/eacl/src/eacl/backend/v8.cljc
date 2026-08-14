(ns eacl.backend.v8
  "Validated capability and operation contract for v8 backend snapshots.

  This is the sole production backend boundary for recursive traversal, Relay
  pagination, deletion, consistency selection, and ordered-generation proofs."
  (:require [eacl.spicedb.consistency :as consistency]))

(def adapter-version 7)
(def maximum-exact-integer 9007199254740991)
(def minimum-exact-integer (- maximum-exact-integer))

(def ^:dynamic *backend-op-stats*
  "Optional atom counting backend adapter invocations by operation keyword.

  Includes the `:proof-frame` operation and index scans. Observation-only:
  counters never influence dispatch or guard behavior."
  nil)

(def ^:dynamic *invoke-observer*
  "Optional CLJ/CLJS test observer called immediately before and after one
  synchronous adapter invocation. It is observation-only and provides the
  deterministic bounded-blocking seam used by deadline certification tests."
  nil)

(defn- observe-invocation!
  [phase adapter operation-key]
  (when *invoke-observer*
    (*invoke-observer*
     {:phase phase
      :backend (:id adapter)
      :operation operation-key}))
  nil)

(def required-snapshot-operations
  #{:snapshot-id
    :source-scope
    :source-lifecycle
    :native-revision
    :order-hint
    :select-current
    :select-authoritative
    :select-at-least
    :exact-locator
    :select-exact
    :object-id->internal
    :internal-id->object
    :relation-defs
    :permission-defs
    :subject->resources
    :resource->subjects
    :direct-match?
    :relation-populated?
    :all-permission-nodes})

(def adapter-obligations
  "Runtime-facing statement of the assumptions made by
  `formal/dafny/SnapshotOracle.dfy`.

  Dafny proves the engine correct when these obligations hold. It does not
  verify Datomic, DataScript, Datahike, their storage engines, or these adapter
  implementations. Certification tests and optional runtime guards provide
  evidence for each named obligation."
  {:snapshot-id
   #{:stable-for-immutable-snapshot :source-and-revision-identity}
   :source-scope
   #{:stable-source-identity :branch-identity}
   :source-lifecycle
   #{:bounded-canonical-identity :rotated-on-source-replacement}
   :native-revision
   #{:selected-native-revision :monotone-order-hint :exact-locator-identity}
   :order-hint
   #{:selected-snapshot-order :exact-integer}
   :select-current
   #{:returns-immutable-snapshot :same-source}
   :select-authoritative
   #{:returns-immutable-snapshot :same-source :authoritative-or-fails-closed}
   :select-at-least
   #{:returns-immutable-snapshot :same-source :native-revision-at-least-requested}
   :exact-locator
   #{:stable-for-immutable-snapshot}
   :select-exact
   #{:same-source :exact-locator-match :unavailable-or-immutable}
   :object-id->internal
   #{:visible-object-total :injective :nonnegative :snapshot-bound}
   :internal-id->object
   #{:visible-object-round-trip :snapshot-bound}
   :relation-defs
   #{:finite :complete :type-correct :snapshot-bound}
   :permission-defs
   #{:finite :complete :type-correct :snapshot-bound}
   :subject->resources
   #{:finite :strict-order :unique :complete
     :inclusive-exclusive-bounds :nonnegative :snapshot-bound}
   :resource->subjects
   #{:finite :strict-order :unique :complete
     :inclusive-exclusive-bounds :nonnegative :snapshot-bound}
   :direct-match?
   #{:iff-forward-scan-membership :iff-reverse-scan-membership
     :snapshot-bound}
   :relation-populated?
   #{:iff-forward-prefix-nonempty :snapshot-bound}
   :all-permission-nodes
   #{:finite :exact-schema-coverage :snapshot-bound}
   :proof-frame
   #{:snapshot-bound :initialized-schema-generation
     :complete-canonical-relation-generations
     :globally-ordered-committed-generations
     :atomic-with-supported-mutations}})

(def known-consistency-modes
  #{:minimize-latency
    :fully-consistent
    :at-least-as-fresh
    :at-exact-snapshot})

(def empty-capabilities
  {:consistency #{}
   :snapshots #{}
   :source #{}
   :cursor #{}
   :transactions #{}
   :cache-proofs #{}
   :runtime #{}})

(def ^:private scan-contract-keys
  #{:strict-order? :unique? :replayable? :strict-progress? :atomic-chunk?})

(def ^:private concurrent-read-keys
  #{:max-width :physically-cancellable?
    :physical-termination-on-return?
    :maximum-physical-lifetime-ms :maximum-nested-attempts})

(def default-traversal-execution
  "Conservative traversal execution profile. Absence of a certified
  topology-specific concurrency declaration means effective width one."
  {:immutable-basis-reads? false
   :scan-contract
   {:strict-order? false
    :unique? false
    :replayable? false
    :strict-progress? false
    :atomic-chunk? false}
   :concurrent-snapshot-reads nil})

(def strict-sequential-traversal-execution
  "Certified semantic scan contract with concurrency deliberately disabled."
  {:immutable-basis-reads? true
   :scan-contract
   {:strict-order? true
    :unique? true
    :replayable? true
    :strict-progress? true
    :atomic-chunk? true}
   :concurrent-snapshot-reads nil})

(defn- invalid-adapter!
  [message data]
  (throw (ex-info message
                  (assoc data
                         :type :eacl/invalid-backend-adapter
                         :eacl/error :eacl/invalid-backend-adapter))))

(defn- contract-violation!
  [backend-id operation obligation value]
  (throw
   (ex-info
    (str "Backend " (pr-str backend-id)
         " violated adapter contract " (pr-str obligation)
         " for " (pr-str operation) ".")
    {:type :eacl/backend-contract-violation
     :eacl/error :eacl/backend-contract-violation
     :backend backend-id
     :operation operation
     :obligation obligation
     :value value})))

(defn certification-obligations
  "Returns the declared proof assumptions for one operation, or the complete
  operation-to-obligations map when called without an argument."
  ([]
   adapter-obligations)
  ([operation-key]
   (or (get adapter-obligations operation-key)
       (invalid-adapter!
        "Unknown backend operation in certification request."
        {:operation operation-key
         :known-operations (set (keys adapter-obligations))}))))

(defn- unsupported!
  [backend-id capability requested supported]
  (throw
   (ex-info
    (str "Backend " (pr-str backend-id)
         " does not support " (pr-str capability)
         (when (some? requested)
           (str " " (pr-str requested)))
         ".")
    {:type :eacl/unsupported-capability
     :eacl/error :eacl/unsupported-capability
     :backend backend-id
     :capability capability
     :requested requested
     :supported supported})))

(defn normalize-capabilities
  [backend-id capabilities]
  (when-not (map? capabilities)
    (invalid-adapter! "Backend :capabilities must be a map."
                      {:backend backend-id
                       :capabilities capabilities}))
  (let [normalized (merge empty-capabilities capabilities)
        unknown-keys (seq (remove (set (keys empty-capabilities))
                                  (keys normalized)))]
    (when unknown-keys
      (invalid-adapter! "Backend declares unknown capability groups."
                        {:backend backend-id
                         :unknown-capabilities (vec unknown-keys)
                         :known-capabilities (set (keys empty-capabilities))}))
    (doseq [[capability values] normalized]
      (when-not (set? values)
        (invalid-adapter! "Backend capability groups must contain sets."
                          {:backend backend-id
                           :capability capability
                           :value values})))
    (when-let [unknown-modes
               (seq (remove known-consistency-modes
                            (:consistency normalized)))]
      (invalid-adapter! "Backend declares unknown consistency modes."
                        {:backend backend-id
                         :unknown-consistency-modes (vec unknown-modes)
                         :known-consistency-modes known-consistency-modes}))
    normalized))

(defn normalize-traversal-execution
  [backend-id profile]
  (let [profile (or profile default-traversal-execution)
        expected #{:immutable-basis-reads? :scan-contract
                   :concurrent-snapshot-reads}]
    (when-not (and (map? profile) (= expected (set (keys profile))))
      (invalid-adapter!
       "Backend traversal execution profile has unknown or missing fields."
       {:backend backend-id
        :expected-keys expected
        :actual-keys (when (map? profile) (set (keys profile)))}))
    (when-not (boolean? (:immutable-basis-reads? profile))
      (invalid-adapter!
       "Backend immutable-basis execution capability must be boolean."
       {:backend backend-id
        :value (:immutable-basis-reads? profile)}))
    (let [scan-contract (:scan-contract profile)]
      (when-not (and (map? scan-contract)
                     (= scan-contract-keys (set (keys scan-contract)))
                     (every? boolean? (vals scan-contract)))
        (invalid-adapter!
         "Backend scan execution contract must be a closed boolean map."
         {:backend backend-id
          :expected-keys scan-contract-keys
          :value scan-contract})))
    (when-some [concurrent (:concurrent-snapshot-reads profile)]
      (when-not (and (map? concurrent)
                     (= concurrent-read-keys (set (keys concurrent))))
        (invalid-adapter!
         "Concurrent snapshot-read capability has unknown or missing fields."
         {:backend backend-id
          :expected-keys concurrent-read-keys
          :value concurrent}))
      (doseq [field [:max-width :maximum-physical-lifetime-ms
                     :maximum-nested-attempts]]
        (when-not (pos-int? (get concurrent field))
          (invalid-adapter!
           "Concurrent snapshot-read numeric limits must be positive integers."
           {:backend backend-id :field field :value (get concurrent field)})))
      (when-not (boolean? (:physically-cancellable? concurrent))
        (invalid-adapter!
         "Concurrent physical-cancellation capability must be boolean."
         {:backend backend-id
          :value (:physically-cancellable? concurrent)}))
      (when-not (boolean? (:physical-termination-on-return? concurrent))
        (invalid-adapter!
         "Concurrent physical termination-on-return capability must be boolean."
         {:backend backend-id
          :value (:physical-termination-on-return? concurrent)}))
      (when-not (and (:immutable-basis-reads? profile)
                     (every? true? (vals (:scan-contract profile))))
        (invalid-adapter!
         "Concurrent reads require every immutable and scan-contract prerequisite."
         {:backend backend-id
          :profile profile})))
    profile))

(defn make-adapter
  [{:keys [id capabilities operations state fingerprint deterministic?
           identity-contract runtime-guards? traversal-execution]
    :or {deterministic? true
         identity-contract :selected-internal/current-external-injective-v2}}]
  (when-not (keyword? id)
    (invalid-adapter! "Backend :id must be a keyword."
                      {:backend id}))
  (when-not (keyword? identity-contract)
    (invalid-adapter!
     "Backend :identity-contract must name a versioned injective contract."
     {:backend id :identity-contract identity-contract}))
  (when-not (map? operations)
    (invalid-adapter! "Backend :operations must be a map."
                      {:backend id
                       :operations operations}))
  (let [missing (seq (remove #(fn? (get operations %))
                             required-snapshot-operations))]
    (when missing
      (invalid-adapter! "Backend is missing required snapshot operations."
                        {:backend id
                         :missing-operations (vec missing)
                         :required-operations required-snapshot-operations})))
  (let [normalized (normalize-capabilities id capabilities)
        traversal-execution
        (normalize-traversal-execution id traversal-execution)]
    (when (and (contains? (:cache-proofs normalized) :ordered-generations)
               (not (fn? (:proof-frame operations))))
      (invalid-adapter!
       "Backend advertises ordered generations without a proof-frame operation."
       {:backend id :capability :ordered-generations}))
  {::adapter true
   ::version adapter-version
   ::id id
   ::capabilities normalized
   ::traversal-execution traversal-execution
   ::operations operations
   ::fingerprint
   (or fingerprint
       {:backend id :adapter-version adapter-version})
   ::deterministic? (boolean deterministic?)
   ::identity-contract identity-contract
   ::runtime-guards? (boolean runtime-guards?)
   ::state state}))

(defn adapter?
  [candidate]
  (and (map? candidate)
       (true? (::adapter candidate))
       (= adapter-version (::version candidate))
       (keyword? (::id candidate))
       (map? (::capabilities candidate))
       (map? (::traversal-execution candidate))
       (map? (::operations candidate))))

(defn backend-id
  [adapter]
  (if (adapter? adapter)
    (::id adapter)
    (invalid-adapter! "Value is not a v8 backend adapter."
                      {:value adapter})))

(defn capabilities
  [adapter]
  (if (adapter? adapter)
    (::capabilities adapter)
    (invalid-adapter! "Value is not a v8 backend adapter."
                      {:value adapter})))

(defn traversal-execution
  "Returns the closed immutable execution profile certified for this exact
  adapter topology. A nil concurrency declaration means width one."
  [adapter]
  (if (adapter? adapter)
    (::traversal-execution adapter)
    (invalid-adapter! "Value is not a v8 backend adapter."
                      {:value adapter})))

(defn maximum-concurrent-snapshot-read-width
  [adapter]
  (or (get-in (traversal-execution adapter)
              [:concurrent-snapshot-reads :max-width])
      1))

(defn state
  "Returns the immutable backend state captured by this adapter.

  Selection orchestration uses this only to hand the selected native snapshot
  to backend-specific ID/cursor codecs; authorization reads remain behind the
  validated operation boundary."
  [adapter]
  (if (adapter? adapter)
    (::state adapter)
    (invalid-adapter! "Value is not a v8 backend adapter."
                      {:value adapter})))

(defn fingerprint
  [adapter]
  (if (adapter? adapter)
    (::fingerprint adapter)
    (invalid-adapter! "Value is not a v8 backend adapter."
                      {:value adapter})))

(defn deterministic?
  [adapter]
  (if (adapter? adapter)
    (::deterministic? adapter)
    (invalid-adapter! "Value is not a v8 backend adapter."
                      {:value adapter})))

(defn identity-contract
  [adapter]
  (if (adapter? adapter)
    (::identity-contract adapter)
    (invalid-adapter! "Value is not a v8 backend adapter."
                      {:value adapter})))

(defn runtime-guards?
  [adapter]
  (if (adapter? adapter)
    (true? (::runtime-guards? adapter))
    (invalid-adapter! "Value is not a v8 backend adapter."
                      {:value adapter})))

(defn with-runtime-guards
  "Returns the same immutable adapter with optional fail-closed output guards
  enabled. Guards check representable runtime properties; they do not replace
  adapter certification or establish backend correctness."
  ([adapter]
   (with-runtime-guards adapter true))
  ([adapter enabled?]
   (when-not (adapter? adapter)
     (invalid-adapter! "Value is not a v8 backend adapter."
                       {:value adapter}))
   (assoc adapter ::runtime-guards? (boolean enabled?))))

(defn supports?
  ([adapter capability]
   (boolean (seq (get (capabilities adapter) capability))))
  ([adapter capability requested]
   (contains? (get (capabilities adapter) capability #{}) requested)))

(defn require-supported!
  [backend-id capabilities capability requested]
  (let [normalized (normalize-capabilities backend-id capabilities)
        supported (get normalized capability #{})]
    (if (contains? supported requested)
      requested
      (unsupported! backend-id capability requested supported))))

(defn require-capability!
  [adapter capability requested]
  (require-supported! (backend-id adapter)
                      (capabilities adapter)
                      capability
                      requested))

(defn require-consistency!
  "Normalizes a public consistency descriptor and verifies that the backend
  promises the selected mode. Returns the normalized descriptor."
  [adapter value]
  (let [{:keys [mode] :as descriptor} (consistency/descriptor value)]
    (require-capability! adapter :consistency mode)
    descriptor))

(defn operation
  [adapter operation-key]
  (when-not (adapter? adapter)
    (invalid-adapter! "Value is not a v8 backend adapter."
                      {:value adapter}))
  (if-let [implementation (get (::operations adapter) operation-key)]
    implementation
    (unsupported! (::id adapter)
                  :operation
                  operation-key
                  (set (keys (::operations adapter))))))

(defn- exact-integer?
  [value]
  (and
   #?(:clj (integer? value)
      :cljs (and (number? value)
                 (js/Number.isSafeInteger value)))
   (<= minimum-exact-integer value maximum-exact-integer)))

(defn- exact-natural?
  [value]
  (and (exact-integer? value)
       (not (neg? value))))

(defn- strictly-ordered?
  [direction values]
  (or (< (count values) 2)
      (every?
       (fn [[left right]]
         ((if (= :desc direction) > <) left right))
       (partition 2 1 values))))

(defn- within-bound?
  [direction bound inclusive? value]
  (if (= :desc direction)
    ((if inclusive? >= >) bound value)
    ((if inclusive? <= <) bound value)))

(defn- guard-scan!
  [adapter operation-key args value]
  (let [backend-id (::id adapter)
        options (or (last args) {})
        direction (or (:direction options) :asc)
        bound (:bound-eid options)
        inclusive? (true? (:inclusive-bound? options))]
    (when-not (sequential? value)
      (contract-violation!
       backend-id operation-key :finite-sequential-result value))
    (let [values (vec value)]
      (when-not (every? exact-integer? values)
        (contract-violation!
         backend-id operation-key :exact-integer values))
      (when-not (every? exact-natural? values)
        (contract-violation!
         backend-id operation-key :nonnegative values))
      (when-not (= (count values) (count (distinct values)))
        (contract-violation!
         backend-id operation-key :unique values))
      (when-not (contains? #{:asc :desc} direction)
        (contract-violation!
         backend-id operation-key :known-direction direction))
      (when-not (strictly-ordered? direction values)
        (contract-violation!
         backend-id operation-key :strict-order values))
      (when (and (some? bound)
                 (not
                  (every?
                   #(within-bound?
                     direction bound inclusive? %)
                   values)))
        (contract-violation!
         backend-id operation-key
         :inclusive-exclusive-bound
         {:options options :values values}))
      value)))

(defn- guard-output!
  [adapter operation-key args value]
  (let [backend-id (::id adapter)]
    (case operation-key
      (:subject->resources :resource->subjects)
      (guard-scan! adapter operation-key args value)

      :object-id->internal
      (do
        (when (and (some? value)
                   (not (exact-integer? value)))
          (contract-violation!
           backend-id operation-key :exact-integer value))
        (when (and (some? value)
                   (not (exact-natural? value)))
          (contract-violation!
           backend-id operation-key :nonnegative value))
        value)

      :order-hint
      (do
        (when-not (exact-integer? value)
          (contract-violation!
           backend-id operation-key :exact-integer value))
        (when-not (exact-natural? value)
          (contract-violation!
           backend-id operation-key :nonnegative value))
        value)

      (:snapshot-id :source-scope :native-revision)
      (do
        (when-not (map? value)
          (contract-violation!
           backend-id operation-key :map-shape value))
        value)

      (:relation-defs :permission-defs)
      (do
        (when-not (and (sequential? value)
                       (every? map? value))
          (contract-violation!
           backend-id operation-key
           :finite-definition-sequence
           value))
        value)

      :all-permission-nodes
      (do
        (when-not (set? value)
          (contract-violation!
           backend-id operation-key :finite-node-set value))
        value)

      (:direct-match? :relation-populated?)
      (do
        (when-not (boolean? value)
          (contract-violation!
           backend-id operation-key :boolean-result value))
        value)

      (:select-current :select-authoritative
       :select-at-least :select-exact)
      (do
        (when-not (or (nil? value) (adapter? value))
          (contract-violation!
           backend-id operation-key :adapter-or-unavailable value))
        value)

      ;; These values are intentionally opaque at this boundary. Their
      ;; semantic obligations (round trip, locator identity, and complete
      ;; proofs) require paired/global certification rather than a local shape
      ;; predicate. They still pass through this dispatch so an added callback
      ;; cannot silently bypass the runtime-guard review.
      (:internal-id->object :exact-locator :source-lifecycle :proof-frame)
      value

      (contract-violation!
       backend-id operation-key :registered-runtime-guard value))))

(defn invoke
  [adapter operation-key & args]
  (when *backend-op-stats*
    (swap! *backend-op-stats* update operation-key (fnil inc 0)))
  (observe-invocation! :before adapter operation-key)
  (try
    (let [value (apply (operation adapter operation-key) args)]
      (observe-invocation! :after adapter operation-key)
      (if (runtime-guards? adapter)
        (guard-output! adapter operation-key args value)
        value))
    (catch #?(:clj Throwable :cljs :default) error
      (observe-invocation! :failed adapter operation-key)
      (throw error))))

(defn reduce-definitions
  "Incrementally consumes one schema-definition sequence.

  This boundary exists so callers can meter and deadline-check lazy adapter
  results without first realizing the complete sequence. Existing definition
  operation arities and the ordinary `invoke` behavior remain unchanged."
  [adapter operation-key args init
   {:keys [before-realize! after-realize! step]
    :or {before-realize! (constantly nil)
         after-realize! (constantly nil)}}]
  (when-not (contains? #{:relation-defs :permission-defs} operation-key)
    (invalid-adapter! "Incremental definition reduction requires a schema operation."
                      {:operation operation-key}))
  (when-not (fn? step)
    (invalid-adapter! "Incremental definition reduction requires a step function."
                      {:operation operation-key}))
  (when *backend-op-stats*
    (swap! *backend-op-stats* update operation-key (fnil inc 0)))
  (observe-invocation! :before adapter operation-key)
  (try
    (let [value (apply (operation adapter operation-key) args)]
      (observe-invocation! :after adapter operation-key)
      (when-not (sequential? value)
        (contract-violation!
         (::id adapter) operation-key :finite-definition-sequence :redacted))
      (loop [remaining value
             accumulator init]
        (before-realize!)
        (let [items (seq remaining)]
          (after-realize!)
          (if-not items
            accumulator
            (let [item (first items)]
              (when (and (runtime-guards? adapter) (not (map? item)))
                (contract-violation!
                 (::id adapter) operation-key
                 :finite-definition-sequence :redacted))
              (recur (rest items) (step accumulator item)))))))
    (catch #?(:clj Throwable :cljs :default) error
      (observe-invocation! :failed adapter operation-key)
      (throw error))))

(defn reduce-scan
  "Incrementally consumes one ordered backend scan without materializing it.

  `args` is the adapter operation argument vector. `before-realize!` and
  `after-realize!` bracket each potentially blocking sequence realization;
  `step` receives the accumulator and one validated internal id. Existing
  adapter operation arities remain unchanged."
  [adapter operation-key args init
   {:keys [before-realize! after-realize! step]
    :or {before-realize! (constantly nil)
         after-realize! (constantly nil)}}]
  (when-not (contains? #{:subject->resources :resource->subjects}
                       operation-key)
    (invalid-adapter! "Incremental reduction requires an ordered scan operation."
                      {:operation operation-key}))
  (when-not (fn? step)
    (invalid-adapter! "Incremental reduction requires a step function."
                      {:operation operation-key}))
  (when *backend-op-stats*
    (swap! *backend-op-stats* update operation-key (fnil inc 0)))
  (observe-invocation! :before adapter operation-key)
  (try
    (let [value (apply (operation adapter operation-key) args)
          options (or (last args) {})
          direction (or (:direction options) :asc)
          bound (:bound-eid options)
          inclusive? (true? (:inclusive-bound? options))]
      (observe-invocation! :after adapter operation-key)
      (when-not (sequential? value)
        (contract-violation!
         (::id adapter) operation-key :finite-sequential-result :redacted))
      (when (and (runtime-guards? adapter)
                 (not (contains? #{:asc :desc} direction)))
        (contract-violation!
         (::id adapter) operation-key :known-direction :redacted))
      (loop [remaining value
             previous nil
             accumulator init]
        (before-realize!)
        (let [items (seq remaining)]
          (after-realize!)
          (if-not items
            accumulator
            (let [item (first items)]
              (when (runtime-guards? adapter)
                (when-not (exact-natural? item)
                  (contract-violation!
                   (::id adapter) operation-key :exact-natural :redacted))
                (when (and (some? previous)
                           (not ((if (= :desc direction) > <)
                                 previous item)))
                  (contract-violation!
                   (::id adapter) operation-key :strict-order :redacted))
                (when (and (some? bound)
                           (not (within-bound?
                                 direction bound inclusive? item)))
                  (contract-violation!
                   (::id adapter) operation-key
                   :inclusive-exclusive-bound :redacted)))
              (recur (rest items) item (step accumulator item)))))))
    (catch #?(:clj Throwable :cljs :default) error
      (observe-invocation! :failed adapter operation-key)
      (throw error))))

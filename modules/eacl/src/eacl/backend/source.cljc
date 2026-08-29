(ns eacl.backend.source
  "Closed lifecycle contract between a long-lived backend source and one
  selected immutable request basis.

  Sources own freshness acquisition. Selected bases own, or explicitly
  borrow, exactly one adapter and carry an idempotent release boundary. Native
  handles and release tokens remain private to this namespace."
  (:require [eacl.backend.v8 :as backend]
            [eacl.request.counters :as request-counters]))

(def source-version 1)
(def selected-basis-version 2)

(def ownership-policies
  #{:borrowed :owned :mixed})

(def basis-ownerships
  #{:borrowed :owned})

(def required-source-operations
  #{:source-scope
    :source-lifecycle
    :acquire-current!
    :acquire-authoritative!
    :acquire-at-least!
    :acquire-exact!
    :release!})

(def optional-source-operations
  "Operations a source MAY provide beyond committed basis selection."
  #{})

(def source-obligations
  "Runtime-facing assumptions for basis selection and native lifecycle. These
  are distinct from the immutable basis-adapter assumptions modeled by
  `formal/dafny/SnapshotOracle.dfy`."
  {:source-scope
   #{:stable-for-source-lifecycle :authenticated-token-comparability}
   :source-lifecycle
   #{:stable-until-explicit-rotation :persisted-when-backend-requires-it}
   :acquire-current!
   #{:one-selected-basis :truthful-current-selection}
   :acquire-authoritative!
   #{:one-selected-basis :declared-authoritative-barrier}
   :acquire-at-least!
   #{:one-selected-basis :requested-floor-satisfied-or-typed-failure}
   :acquire-exact!
   #{:one-selected-basis :exact-locator-selection
     :at-most-one-local-observation
     :conditional-targeted-synchronization}
   :release!
   #{:owned-native-release :idempotent-shared-boundary
     :declared-thread-constraint}})

(def acquisition-operations
  {:current :acquire-current!
   :authoritative :acquire-authoritative!
   :at-least :acquire-at-least!
   :exact :acquire-exact!})

(def default-execution-constraints
  "Portable sources have no thread affinity. A native source can reject
  virtual threads and bind use/release to the acquiring thread."
  {:virtual-threads :supported
   :snapshot-thread :any
   :release-thread :any})

(def ^:private execution-constraint-values
  {:virtual-threads #{:supported :rejected}
   :snapshot-thread #{:any :acquiring-thread}
   :release-thread #{:any :acquiring-thread}})

(def ^:private source-input-keys
  #{:id
    :capabilities
    :traversal-execution
    :topology
    :execution-constraints
    :basis-ownership
    :fingerprint
    :deterministic?
    :operations})

(def ^:private raw-acquisition-keys
  #{:adapter :ownership :release-token})

(def semantic-identity-keys
  "Closed equality identity for all EACL-visible state of one selected
  snapshot. Schema generation keys derived artifacts and is not a basis
  identity dimension."
  #{:backend
    :source-id
    :branch
    :source-lifecycle
    :basis-kind
    :revision
    :exact-locator
    :backend-snapshot-id})

(def ^:dynamic *source-op-stats*
  "Optional atom counting basis-source operation invocations by keyword."
  nil)

(defn- invalid-source!
  [message data]
  (throw
   (ex-info
    message
    (assoc data
           :type :eacl/invalid-source
           :eacl/error :eacl/invalid-source))))

(defn- invalid-selected-basis!
  [message data]
  (throw
   (ex-info
    message
    (assoc data
           :type :eacl/invalid-selected-basis
           :eacl/error :eacl/invalid-selected-basis))))

(defn- normalize-execution-constraints
  [backend-id constraints]
  (let [constraints (or constraints default-execution-constraints)
        expected (set (keys execution-constraint-values))]
    (when-not (and (map? constraints)
                   (= expected (set (keys constraints))))
      (invalid-source!
       "Basis source execution constraints have unknown or missing fields."
       {:backend backend-id
        :expected-keys expected
        :actual-keys (when (map? constraints) (set (keys constraints)))}))
    (doseq [[field supported] execution-constraint-values]
      (when-not (contains? supported (get constraints field))
        (invalid-source!
         "Basis source declares an unknown execution constraint."
         {:backend backend-id
          :field field
          :value (get constraints field)
          :supported supported})))
    constraints))

(defn make-source
  "Constructs a validated long-lived basis source.

  Acquisition operations return exactly
  `{:adapter v8-adapter :ownership :borrowed-or-owned :release-token x}`.
  `:release!` accepts the opaque release token and must tolerate one call for
  every successful acquisition. This namespace makes repeated core release
  calls idempotent."
  [{:keys [id capabilities traversal-execution topology execution-constraints
           basis-ownership fingerprint deterministic? operations]
    :or {traversal-execution backend/default-traversal-execution
         topology {}
         execution-constraints default-execution-constraints
         deterministic? true}
    :as source-input}]
  (let [unknown-keys (seq (remove source-input-keys (keys source-input)))]
    (when unknown-keys
      (invalid-source!
       "Basis source has unknown fields."
       {:backend id
        :unknown-keys (vec unknown-keys)
        :known-keys source-input-keys})))
  (when-not (keyword? id)
    (invalid-source! "Basis source :id must be a keyword."
                       {:backend id}))
  (when-not (map? topology)
    (invalid-source! "Basis source :topology must be a map."
                       {:backend id :topology topology}))
  (when-not (contains? ownership-policies basis-ownership)
    (invalid-source!
     "Basis source must declare borrowed, owned, or mixed ownership."
     {:backend id
      :basis-ownership basis-ownership
      :supported ownership-policies}))
  (when-not (map? operations)
    (invalid-source! "Basis source :operations must be a map."
                       {:backend id :operations operations}))
  (let [missing
        (seq
         (remove #(ifn? (get operations %))
                 required-source-operations))]
    (when missing
      (throw
       (ex-info
        "Basis source is missing required operations."
        {:type :eacl/invalid-backend-role
         :eacl/error :eacl/invalid-backend-role
         :role :source
         :backend id
         :operation (first missing)
         :missing-operations (vec missing)
         :required-operations required-source-operations}))))
  {::source true
   ::version source-version
   ::id id
   ::capabilities (backend/normalize-capabilities id capabilities)
   ::traversal-execution
   (backend/normalize-traversal-execution id traversal-execution)
   ::topology topology
   ::execution-constraints
   (normalize-execution-constraints id execution-constraints)
   ::basis-ownership basis-ownership
   ::fingerprint
   (or fingerprint
       {:backend id
        :source-version source-version
        :adapter-version backend/adapter-version})
   ::deterministic? (boolean deterministic?)
   ::operations operations})

(defn source?
  [candidate]
  (and (map? candidate)
       (true? (::source candidate))
       (= source-version (::version candidate))
       (keyword? (::id candidate))
       (map? (::capabilities candidate))
       (map? (::traversal-execution candidate))
       (map? (::topology candidate))
       (map? (::execution-constraints candidate))
       (contains? ownership-policies (::basis-ownership candidate))
       (map? (::operations candidate))))

(defn- require-source
  [source]
  (if (source? source)
    source
    (invalid-source! "Value is not a basis source."
                       {:value source})))

(defn backend-id
  [source]
  (::id (require-source source)))

(defn capabilities
  [source]
  (::capabilities (require-source source)))

(defn traversal-execution
  [source]
  (::traversal-execution (require-source source)))

(defn topology
  [source]
  (::topology (require-source source)))

(defn execution-constraints
  [source]
  (::execution-constraints (require-source source)))

(defn basis-ownership
  [source]
  (::basis-ownership (require-source source)))

(defn fingerprint
  [source]
  (::fingerprint (require-source source)))

(defn deterministic?
  [source]
  (::deterministic? (require-source source)))

(defn static-profile
  "Returns the snapshot-free source metadata used at client construction."
  [source]
  {:backend-id (backend-id source)
   :capabilities (capabilities source)
   :traversal-execution (traversal-execution source)
   :topology (topology source)
   :execution-constraints (execution-constraints source)
   :basis-ownership (basis-ownership source)
   :fingerprint (fingerprint source)
   :deterministic? (deterministic? source)})

(defn supports?
  ([source capability]
   (boolean (seq (get (capabilities source) capability))))
  ([source capability requested]
   (contains? (get (capabilities source) capability #{}) requested)))

(defn operation
  [source operation-key]
  (let [source (require-source source)]
    (if-let [implementation (get (::operations source) operation-key)]
      implementation
      (invalid-source!
       "Basis source operation is unavailable."
       {:backend (::id source)
        :operation operation-key
        :supported (set (keys (::operations source)))}))))

(defn invoke
  [source operation-key & args]
  (when *source-op-stats*
    (swap! *source-op-stats* update operation-key (fnil inc 0)))
  (apply (operation source operation-key) args))

(defn- virtual-thread?
  []
  #?(:clj
     (try
       (boolean
        (clojure.lang.Reflector/invokeInstanceMethod
         (Thread/currentThread) "isVirtual" (object-array 0)))
       ;; Java runtimes before virtual threads have no `Thread.isVirtual`.
       (catch Throwable _
         false))
     :cljs false))

(defn- require-acquisition-runtime!
  [source]
  (let [constraints (execution-constraints source)]
    (when (and (= :rejected (:virtual-threads constraints))
               (virtual-thread?))
      (throw
       (ex-info
        "Snapshot acquisition is unsupported on a virtual thread."
        {:type :eacl/unsupported-runtime
         :eacl/error :eacl/unsupported-runtime
         :backend (backend-id source)
         :runtime :virtual-thread
         :phase :snapshot-acquisition}))))
  nil)

(defn- require-owner-thread!
  [selected constraint phase]
  #?(:clj
     (when (and (= :acquiring-thread constraint)
                (not (identical? (::owner-thread selected)
                                 (Thread/currentThread))))
       (throw
        (ex-info
         "Selected snapshot operation escaped its acquiring thread."
         {:type :eacl/snapshot-thread-violation
          :eacl/error :eacl/snapshot-thread-violation
          :backend (::backend-id selected)
          :phase phase
          :constraint constraint})))
     :cljs nil)
  nil)

(defn source-scope
  "Returns source-static source and branch identity without acquiring a DB."
  [source]
  (let [scope (invoke source :source-scope)]
    (when-not (and (map? scope)
                   (contains? scope :source-id)
                   (contains? scope :branch))
      (invalid-source!
       "Basis source returned an invalid source scope."
       {:backend (backend-id source)
        :source-scope scope}))
    (select-keys scope [:source-id :branch])))

(defn source-lifecycle
  "Returns current source continuity identity without acquiring a DB."
  [source]
  (invoke source :source-lifecycle))

(defn selected-basis?
  [candidate]
  (and (map? candidate)
       (true? (::selected-basis candidate))
       (= selected-basis-version (::version candidate))
       (keyword? (::backend-id candidate))
       (map? (::execution-constraints candidate))
       (fn? (::release-fn candidate))
       (backend/adapter? (::adapter candidate))
       (map? (::semantic-identity candidate))
       (= semantic-identity-keys
          (set (keys (::semantic-identity candidate))))
       (contains? basis-ownerships (::ownership candidate))
       #?(:clj (instance? clojure.lang.Atom (::release-state candidate))
          :cljs (satisfies? IDeref (::release-state candidate)))
       (contains? #{:open :releasing :released}
                  @(::release-state candidate))))

(defn- ownership-compatible?
  [policy ownership]
  (or (= :mixed policy)
      (= policy ownership)))

(defn- exact-natural?
  [value]
  (and (integer? value)
       (not (neg? value))
       (<= value backend/maximum-exact-integer)))

(defn- adapter-semantic-identity
  [source adapter]
  (let [backend-id (backend/backend-id adapter)
        scope (source-scope source)
        lifecycle (source-lifecycle source)
        native-revision (backend/invoke adapter :native-revision)
        snapshot-id (backend/invoke adapter :snapshot-id)
        basis-kind (backend/basis-kind adapter)
        revision (:revision native-revision)
        order-hint (backend/invoke adapter :order-hint)
        exact-locator (backend/invoke adapter :exact-locator)]
    (when-not (and (map? scope)
                   (contains? scope :source-id)
                   (contains? scope :branch))
      (invalid-selected-basis!
       "Selected adapter returned an invalid source scope."
       {:backend backend-id :source-scope scope}))
    (when-not (and (map? native-revision)
                   (contains? native-revision :exact-locator)
                   (exact-natural? revision)
                   (= revision order-hint)
                   (= (:exact-locator native-revision) exact-locator))
      (invalid-selected-basis!
       "Selected adapter returned an invalid native revision."
       {:backend backend-id
        :native-revision native-revision
        :order-hint order-hint
        :exact-locator exact-locator}))
    (when-not (map? snapshot-id)
      (invalid-selected-basis!
       "Selected adapter returned an invalid snapshot identity."
       {:backend backend-id :snapshot-id snapshot-id}))
    (when (contains? snapshot-id :schema-identity)
      (invalid-selected-basis!
       "Selected adapter advertised the removed physical schema identity."
       {:backend backend-id
        :field :schema-identity}))
    {:backend backend-id
     :source-id (:source-id scope)
     :branch (:branch scope)
     :source-lifecycle lifecycle
     :basis-kind basis-kind
     :revision revision
     :exact-locator (:exact-locator native-revision)
     :backend-snapshot-id snapshot-id}))

(defn- selected-basis
  [source {:keys [adapter ownership release-token] :as acquisition}]
  (when-not (and (map? acquisition)
                 (= raw-acquisition-keys (set (keys acquisition))))
    (invalid-selected-basis!
     "Source acquisition must return the closed selected-basis map."
     {:backend (backend-id source)
      :expected-keys raw-acquisition-keys
      :actual-keys (when (map? acquisition) (set (keys acquisition)))}))
  (when-not (backend/adapter? adapter)
    (invalid-selected-basis!
     "Source acquisition did not return a v8 adapter."
     {:backend (backend-id source)
      :adapter adapter}))
  (when-not (= (backend-id source) (backend/backend-id adapter))
    (invalid-selected-basis!
     "Source acquisition crossed backend identity."
     {:source-backend (backend-id source)
      :adapter-backend (backend/backend-id adapter)}))
  (when-not (contains? basis-ownerships ownership)
    (invalid-selected-basis!
     "Source acquisition declared invalid ownership."
     {:backend (backend-id source)
      :ownership ownership
      :supported basis-ownerships}))
  (when-not (ownership-compatible?
             (basis-ownership source)
             ownership)
    (invalid-selected-basis!
     "Source acquisition violated its static ownership policy."
     {:backend (backend-id source)
      :source-ownership (basis-ownership source)
      :acquisition-ownership ownership}))
  (when-not (= (traversal-execution source)
               (backend/traversal-execution adapter))
    (invalid-selected-basis!
     "Selected adapter traversal profile differs from source-static metadata."
     {:backend (backend-id source)}))
  (let [basis-adapter adapter]
    {::selected-basis true
     ::version selected-basis-version
     ::backend-id (backend-id source)
     ::execution-constraints (execution-constraints source)
     ::release-fn (operation source :release!)
     ::adapter basis-adapter
     ::semantic-identity (adapter-semantic-identity source basis-adapter)
     ::ownership ownership
     ::release-token release-token
     ::owner-thread #?(:clj (Thread/currentThread) :cljs nil)
     ::release-state (atom :open)}))

(defn acquire!
  "Acquires and validates one candidate selected basis.

  `kind` is one of `:current`, `:authoritative`, `:at-least`, or `:exact`.
  Remaining arguments are forwarded to that acquisition operation."
  [source kind & args]
  (let [source (require-source source)
        operation-key (get acquisition-operations kind)]
    (require-acquisition-runtime! source)
    (when-not operation-key
      (invalid-source!
       "Unknown snapshot acquisition kind."
       {:backend (backend-id source)
        :kind kind
        :supported (set (keys acquisition-operations))}))
    (let [acquisition (apply invoke source operation-key args)]
      (try
        (let [selected (selected-basis source acquisition)]
          (request-counters/add! :acquisitions)
          selected)
        (catch #?(:clj Throwable :cljs :default) selection-error
          ;; Any map returned from an acquisition operation represents a
          ;; completed acquisition. Core therefore invokes cleanup even when
          ;; the source omitted the mandatory token; passing nil is the only
          ;; fail-closed cleanup opportunity available for that malformed
          ;; result. A conforming source must accept exactly the token it
          ;; returned, including nil when nil is its opaque token.
          (when (map? acquisition)
            (try
              (invoke source :release! (:release-token acquisition))
              (catch #?(:clj Throwable :cljs :default) release-error
                (throw
                 (ex-info
                  "Invalid snapshot acquisition also failed cleanup."
                  {:type :eacl/snapshot-release-failed
                   :eacl/error :eacl/snapshot-release-failed
                   :backend (backend-id source)
                   :acquisition-error (ex-data selection-error)}
                  release-error)))))
          (throw selection-error))))))

(defn released?
  [selected]
  (if (selected-basis? selected)
    (= :released @(::release-state selected))
    (invalid-selected-basis!
     "Value is not a selected basis."
     {:value selected})))

(defn ownership
  [selected]
  (if (selected-basis? selected)
    (::ownership selected)
    (invalid-selected-basis!
     "Value is not a selected basis."
     {:value selected})))

(defn semantic-identity
  "Returns the immutable semantic equality identity captured at acquisition."
  [selected]
  (if (selected-basis? selected)
    (::semantic-identity selected)
    (invalid-selected-basis!
     "Value is not a selected basis."
     {:value selected})))

(defn assert-open!
  [selected]
  (when-not (selected-basis? selected)
    (invalid-selected-basis!
     "Value is not a selected basis."
     {:value selected}))
  (case @(::release-state selected)
    :open nil
    :releasing
    (throw
     (ex-info
      "Selected basis release is already in progress."
      {:type :eacl/snapshot-release-in-progress
       :eacl/error :eacl/snapshot-release-in-progress
       :backend (::backend-id selected)}))
    :released
    (throw
     (ex-info
      "Selected basis has already been released."
      {:type :eacl/snapshot-released
       :eacl/error :eacl/snapshot-released
       :backend (::backend-id selected)})))
  (require-owner-thread!
   selected
   (:snapshot-thread
    (::execution-constraints selected))
   :snapshot-access)
  selected)

(defn adapter
  "Returns the selected adapter while the selected basis is open."
  [selected]
  (::adapter (assert-open! selected)))

(defn owner-thread
  "Returns the acquiring thread on CLJ and nil on CLJS."
  [selected]
  (if (selected-basis? selected)
    (::owner-thread selected)
    (invalid-selected-basis!
     "Value is not a selected basis."
     {:value selected})))

(defn release!
  "Releases a selected basis at most once.

  Returns true for the call that performs source release and false after the
  snapshot has already been released."
  [selected]
  (when-not (selected-basis? selected)
    (invalid-selected-basis!
     "Value is not a selected basis."
     {:value selected}))
  (require-owner-thread!
   selected
   (:release-thread
    (::execution-constraints selected))
   :snapshot-release)
  (let [release-state (::release-state selected)]
    (loop []
      (case @release-state
        :released false

        :releasing
        (throw
         (ex-info
          "Selected basis release is already in progress."
          {:type :eacl/snapshot-release-in-progress
           :eacl/error :eacl/snapshot-release-in-progress
           :backend (::backend-id selected)}))

        :open
        (if (compare-and-set! release-state :open :releasing)
          (try
            (when *source-op-stats*
              (swap! *source-op-stats* update :release! (fnil inc 0)))
            ((::release-fn selected) (::release-token selected))
            (reset! release-state :released)
            true
            (catch #?(:clj Throwable :cljs :default) error
              ;; A failed native release is not equivalent to a closed
              ;; resource. Restore retryability and classify the boundary.
              (reset! release-state :open)
              (throw
               (ex-info
                "Basis source failed to release an owned candidate."
                {:type :eacl/snapshot-release-failed
                 :eacl/error :eacl/snapshot-release-failed
                 :backend (::backend-id selected)}
                error))))
          (recur))))))

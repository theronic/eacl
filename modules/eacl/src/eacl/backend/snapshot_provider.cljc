(ns eacl.backend.snapshot-provider
  "Closed lifecycle contract between a long-lived backend source and one
  selected immutable request snapshot.

  Providers own freshness acquisition. Selected snapshots own, or explicitly
  borrow, exactly one adapter and carry an idempotent release boundary. Native
  handles and release tokens remain private to this namespace."
  (:require [eacl.backend.v8 :as backend]))

(def provider-version 1)
(def selected-snapshot-version 1)

(def ownership-policies
  #{:borrowed :owned :mixed})

(def snapshot-ownerships
  #{:borrowed :owned})

(def required-provider-operations
  #{:source-scope
    :source-lifecycle
    :acquire-current!
    :acquire-authoritative!
    :acquire-at-least!
    :acquire-exact!
    :release!})

(def acquisition-operations
  {:current :acquire-current!
   :authoritative :acquire-authoritative!
   :at-least :acquire-at-least!
   :exact :acquire-exact!})

(def default-execution-constraints
  "Portable providers have no thread affinity. A native provider can reject
  virtual threads and bind use/release to the acquiring thread."
  {:virtual-threads :supported
   :snapshot-thread :any
   :release-thread :any})

(def ^:private execution-constraint-values
  {:virtual-threads #{:supported :rejected}
   :snapshot-thread #{:any :acquiring-thread}
   :release-thread #{:any :acquiring-thread}})

(def ^:private provider-input-keys
  #{:id
    :capabilities
    :traversal-execution
    :topology
    :execution-constraints
    :snapshot-ownership
    :fingerprint
    :deterministic?
    :operations})

(def ^:private raw-acquisition-keys
  #{:adapter :ownership :release-token})

(def semantic-identity-keys
  "Closed equality identity for all EACL-visible state of one selected
  snapshot. `:schema-identity` is nil unless the backend certifies a schema
  dimension independent of its revision."
  #{:backend
    :source-id
    :branch
    :source-lifecycle
    :revision
    :exact-locator
    :schema-identity
    :backend-snapshot-id})

(def ^:dynamic *provider-op-stats*
  "Optional atom counting provider operation invocations by keyword."
  nil)

(defn- invalid-provider!
  [message data]
  (throw
   (ex-info
    message
    (assoc data
           :type :eacl/invalid-snapshot-provider
           :eacl/error :eacl/invalid-snapshot-provider))))

(defn- invalid-selected-snapshot!
  [message data]
  (throw
   (ex-info
    message
    (assoc data
           :type :eacl/invalid-selected-snapshot
           :eacl/error :eacl/invalid-selected-snapshot))))

(defn- normalize-execution-constraints
  [backend-id constraints]
  (let [constraints (or constraints default-execution-constraints)
        expected (set (keys execution-constraint-values))]
    (when-not (and (map? constraints)
                   (= expected (set (keys constraints))))
      (invalid-provider!
       "Snapshot provider execution constraints have unknown or missing fields."
       {:backend backend-id
        :expected-keys expected
        :actual-keys (when (map? constraints) (set (keys constraints)))}))
    (doseq [[field supported] execution-constraint-values]
      (when-not (contains? supported (get constraints field))
        (invalid-provider!
         "Snapshot provider declares an unknown execution constraint."
         {:backend backend-id
          :field field
          :value (get constraints field)
          :supported supported})))
    constraints))

(defn make-provider
  "Constructs a validated long-lived snapshot provider.

  Acquisition operations return exactly
  `{:adapter v8-adapter :ownership :borrowed-or-owned :release-token x}`.
  `:release!` accepts the opaque release token and must tolerate one call for
  every successful acquisition. This namespace makes repeated core release
  calls idempotent."
  [{:keys [id capabilities traversal-execution topology execution-constraints
           snapshot-ownership fingerprint deterministic? operations]
    :or {traversal-execution backend/default-traversal-execution
         topology {}
         execution-constraints default-execution-constraints
         deterministic? true}
    :as provider-input}]
  (let [unknown-keys (seq (remove provider-input-keys (keys provider-input)))]
    (when unknown-keys
      (invalid-provider!
       "Snapshot provider has unknown fields."
       {:backend id
        :unknown-keys (vec unknown-keys)
        :known-keys provider-input-keys})))
  (when-not (keyword? id)
    (invalid-provider! "Snapshot provider :id must be a keyword."
                       {:backend id}))
  (when-not (map? topology)
    (invalid-provider! "Snapshot provider :topology must be a map."
                       {:backend id :topology topology}))
  (when-not (contains? ownership-policies snapshot-ownership)
    (invalid-provider!
     "Snapshot provider must declare borrowed, owned, or mixed ownership."
     {:backend id
      :snapshot-ownership snapshot-ownership
      :supported ownership-policies}))
  (when-not (map? operations)
    (invalid-provider! "Snapshot provider :operations must be a map."
                       {:backend id :operations operations}))
  (let [missing
        (seq
         (remove #(fn? (get operations %))
                 required-provider-operations))]
    (when missing
      (invalid-provider!
       "Snapshot provider is missing required operations."
       {:backend id
        :missing-operations (vec missing)
        :required-operations required-provider-operations})))
  {::provider true
   ::version provider-version
   ::id id
   ::capabilities (backend/normalize-capabilities id capabilities)
   ::traversal-execution
   (backend/normalize-traversal-execution id traversal-execution)
   ::topology topology
   ::execution-constraints
   (normalize-execution-constraints id execution-constraints)
   ::snapshot-ownership snapshot-ownership
   ::fingerprint
   (or fingerprint
       {:backend id
        :provider-version provider-version
        :adapter-version backend/adapter-version})
   ::deterministic? (boolean deterministic?)
   ::operations operations})

(defn provider?
  [candidate]
  (and (map? candidate)
       (true? (::provider candidate))
       (= provider-version (::version candidate))
       (keyword? (::id candidate))
       (map? (::capabilities candidate))
       (map? (::traversal-execution candidate))
       (map? (::topology candidate))
       (map? (::execution-constraints candidate))
       (contains? ownership-policies (::snapshot-ownership candidate))
       (map? (::operations candidate))))

(defn- require-provider
  [provider]
  (if (provider? provider)
    provider
    (invalid-provider! "Value is not a snapshot provider."
                       {:value provider})))

(defn backend-id
  [provider]
  (::id (require-provider provider)))

(defn capabilities
  [provider]
  (::capabilities (require-provider provider)))

(defn traversal-execution
  [provider]
  (::traversal-execution (require-provider provider)))

(defn topology
  [provider]
  (::topology (require-provider provider)))

(defn execution-constraints
  [provider]
  (::execution-constraints (require-provider provider)))

(defn snapshot-ownership
  [provider]
  (::snapshot-ownership (require-provider provider)))

(defn fingerprint
  [provider]
  (::fingerprint (require-provider provider)))

(defn deterministic?
  [provider]
  (::deterministic? (require-provider provider)))

(defn static-profile
  "Returns the snapshot-free provider metadata used at client construction."
  [provider]
  {:backend-id (backend-id provider)
   :capabilities (capabilities provider)
   :traversal-execution (traversal-execution provider)
   :topology (topology provider)
   :execution-constraints (execution-constraints provider)
   :snapshot-ownership (snapshot-ownership provider)
   :fingerprint (fingerprint provider)
   :deterministic? (deterministic? provider)})

(defn supports?
  ([provider capability]
   (boolean (seq (get (capabilities provider) capability))))
  ([provider capability requested]
   (contains? (get (capabilities provider) capability #{}) requested)))

(defn operation
  [provider operation-key]
  (let [provider (require-provider provider)]
    (if-let [implementation (get (::operations provider) operation-key)]
      implementation
      (invalid-provider!
       "Snapshot provider operation is unavailable."
       {:backend (::id provider)
        :operation operation-key
        :supported (set (keys (::operations provider)))}))))

(defn invoke
  [provider operation-key & args]
  (when *provider-op-stats*
    (swap! *provider-op-stats* update operation-key (fnil inc 0)))
  (apply (operation provider operation-key) args))

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
  [provider]
  (let [constraints (execution-constraints provider)]
    (when (and (= :rejected (:virtual-threads constraints))
               (virtual-thread?))
      (throw
       (ex-info
        "Snapshot acquisition is unsupported on a virtual thread."
        {:type :eacl/unsupported-runtime
         :eacl/error :eacl/unsupported-runtime
         :backend (backend-id provider)
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
          :backend (backend-id (::provider selected))
          :phase phase
          :constraint constraint})))
     :cljs nil)
  nil)

(defn source-scope
  "Returns provider-static source and branch identity without acquiring a DB."
  [provider]
  (let [scope (invoke provider :source-scope)]
    (when-not (and (map? scope)
                   (contains? scope :source-id)
                   (contains? scope :branch))
      (invalid-provider!
       "Snapshot provider returned an invalid source scope."
       {:backend (backend-id provider)
        :source-scope scope}))
    (select-keys scope [:source-id :branch])))

(defn source-lifecycle
  "Returns current provider continuity identity without acquiring a DB."
  [provider]
  (invoke provider :source-lifecycle))

(defn selected-snapshot?
  [candidate]
  (and (map? candidate)
       (true? (::selected-snapshot candidate))
       (= selected-snapshot-version (::version candidate))
       (provider? (::provider candidate))
       (backend/adapter? (::adapter candidate))
       (map? (::semantic-identity candidate))
       (= semantic-identity-keys
          (set (keys (::semantic-identity candidate))))
       (contains? snapshot-ownerships (::ownership candidate))
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
  [adapter]
  (let [backend-id (backend/backend-id adapter)
        scope (backend/invoke adapter :source-scope)
        lifecycle (backend/invoke adapter :source-lifecycle)
        native-revision (backend/invoke adapter :native-revision)
        snapshot-id (backend/invoke adapter :snapshot-id)
        revision (:revision native-revision)
        order-hint (backend/invoke adapter :order-hint)
        exact-locator (backend/invoke adapter :exact-locator)]
    (when-not (and (map? scope)
                   (contains? scope :source-id)
                   (contains? scope :branch))
      (invalid-selected-snapshot!
       "Selected adapter returned an invalid source scope."
       {:backend backend-id :source-scope scope}))
    (when-not (and (map? native-revision)
                   (contains? native-revision :exact-locator)
                   (exact-natural? revision)
                   (= revision order-hint)
                   (= (:exact-locator native-revision) exact-locator))
      (invalid-selected-snapshot!
       "Selected adapter returned an invalid native revision."
       {:backend backend-id
        :native-revision native-revision
        :order-hint order-hint
        :exact-locator exact-locator}))
    (when-not (map? snapshot-id)
      (invalid-selected-snapshot!
       "Selected adapter returned an invalid snapshot identity."
       {:backend backend-id :snapshot-id snapshot-id}))
    {:backend backend-id
     :source-id (:source-id scope)
     :branch (:branch scope)
     :source-lifecycle lifecycle
     :revision revision
     :exact-locator (:exact-locator native-revision)
     :schema-identity (:schema-identity snapshot-id)
     :backend-snapshot-id snapshot-id}))

(defn- selected-snapshot
  [provider {:keys [adapter ownership release-token] :as acquisition}]
  (when-not (and (map? acquisition)
                 (= raw-acquisition-keys (set (keys acquisition))))
    (invalid-selected-snapshot!
     "Provider acquisition must return the closed selected-snapshot map."
     {:backend (backend-id provider)
      :expected-keys raw-acquisition-keys
      :actual-keys (when (map? acquisition) (set (keys acquisition)))}))
  (when-not (backend/adapter? adapter)
    (invalid-selected-snapshot!
     "Provider acquisition did not return a v8 adapter."
     {:backend (backend-id provider)
      :adapter adapter}))
  (when-not (= (backend-id provider) (backend/backend-id adapter))
    (invalid-selected-snapshot!
     "Provider acquisition crossed backend identity."
     {:provider-backend (backend-id provider)
      :adapter-backend (backend/backend-id adapter)}))
  (when-not (contains? snapshot-ownerships ownership)
    (invalid-selected-snapshot!
     "Provider acquisition declared invalid ownership."
     {:backend (backend-id provider)
      :ownership ownership
      :supported snapshot-ownerships}))
  (when-not (ownership-compatible?
             (snapshot-ownership provider)
             ownership)
    (invalid-selected-snapshot!
     "Provider acquisition violated its static ownership policy."
     {:backend (backend-id provider)
      :provider-ownership (snapshot-ownership provider)
      :acquisition-ownership ownership}))
  (when-not (= (traversal-execution provider)
               (backend/traversal-execution adapter))
    (invalid-selected-snapshot!
     "Selected adapter traversal profile differs from provider-static metadata."
     {:backend (backend-id provider)}))
  {::selected-snapshot true
   ::version selected-snapshot-version
   ::provider provider
   ::adapter adapter
   ::semantic-identity (adapter-semantic-identity adapter)
   ::ownership ownership
   ::release-token release-token
   ::owner-thread #?(:clj (Thread/currentThread) :cljs nil)
   ::release-state (atom :open)})

(defn acquire!
  "Acquires and validates one candidate selected snapshot.

  `kind` is one of `:current`, `:authoritative`, `:at-least`, or `:exact`.
  Remaining arguments are forwarded to that acquisition operation."
  [provider kind & args]
  (let [provider (require-provider provider)
        operation-key (get acquisition-operations kind)]
    (require-acquisition-runtime! provider)
    (when-not operation-key
      (invalid-provider!
       "Unknown snapshot acquisition kind."
       {:backend (backend-id provider)
        :kind kind
        :supported (set (keys acquisition-operations))}))
    (let [acquisition (apply invoke provider operation-key args)]
      (try
        (selected-snapshot provider acquisition)
        (catch #?(:clj Throwable :cljs :default) selection-error
          ;; Any map returned from an acquisition operation represents a
          ;; completed acquisition. Core therefore invokes cleanup even when
          ;; the provider omitted the mandatory token; passing nil is the only
          ;; fail-closed cleanup opportunity available for that malformed
          ;; result. A conforming provider must accept exactly the token it
          ;; returned, including nil when nil is its opaque token.
          (when (map? acquisition)
            (try
              (invoke provider :release! (:release-token acquisition))
              (catch #?(:clj Throwable :cljs :default) release-error
                (throw
                 (ex-info
                  "Invalid snapshot acquisition also failed cleanup."
                  {:type :eacl/snapshot-release-failed
                   :eacl/error :eacl/snapshot-release-failed
                   :backend (backend-id provider)
                   :acquisition-error (ex-data selection-error)}
                  release-error)))))
          (throw selection-error))))))

(defn released?
  [selected]
  (if (selected-snapshot? selected)
    (= :released @(::release-state selected))
    (invalid-selected-snapshot!
     "Value is not a selected snapshot."
     {:value selected})))

(defn ownership
  [selected]
  (if (selected-snapshot? selected)
    (::ownership selected)
    (invalid-selected-snapshot!
     "Value is not a selected snapshot."
     {:value selected})))

(defn semantic-identity
  "Returns the immutable semantic equality identity captured at acquisition."
  [selected]
  (if (selected-snapshot? selected)
    (::semantic-identity selected)
    (invalid-selected-snapshot!
     "Value is not a selected snapshot."
     {:value selected})))

(defn assert-open!
  [selected]
  (when-not (selected-snapshot? selected)
    (invalid-selected-snapshot!
     "Value is not a selected snapshot."
     {:value selected}))
  (case @(::release-state selected)
    :open nil
    :releasing
    (throw
     (ex-info
      "Selected snapshot release is already in progress."
      {:type :eacl/snapshot-release-in-progress
       :eacl/error :eacl/snapshot-release-in-progress
       :backend (backend-id (::provider selected))}))
    :released
    (throw
     (ex-info
      "Selected snapshot has already been released."
      {:type :eacl/snapshot-released
       :eacl/error :eacl/snapshot-released
       :backend (backend-id (::provider selected))})))
  (require-owner-thread!
   selected
   (:snapshot-thread
    (execution-constraints (::provider selected)))
   :snapshot-access)
  selected)

(defn adapter
  "Returns the selected adapter while the selected snapshot is open."
  [selected]
  (::adapter (assert-open! selected)))

(defn owner-thread
  "Returns the acquiring thread on CLJ and nil on CLJS."
  [selected]
  (if (selected-snapshot? selected)
    (::owner-thread selected)
    (invalid-selected-snapshot!
     "Value is not a selected snapshot."
     {:value selected})))

(defn release!
  "Releases a selected snapshot at most once.

  Returns true for the call that performs provider release and false after the
  snapshot has already been released."
  [selected]
  (when-not (selected-snapshot? selected)
    (invalid-selected-snapshot!
     "Value is not a selected snapshot."
     {:value selected}))
  (require-owner-thread!
   selected
   (:release-thread
    (execution-constraints (::provider selected)))
   :snapshot-release)
  (let [release-state (::release-state selected)]
    (loop []
      (case @release-state
        :released false

        :releasing
        (throw
         (ex-info
          "Selected snapshot release is already in progress."
          {:type :eacl/snapshot-release-in-progress
           :eacl/error :eacl/snapshot-release-in-progress
           :backend (backend-id (::provider selected))}))

        :open
        (if (compare-and-set! release-state :open :releasing)
          (try
            (invoke (::provider selected)
                    :release!
                    (::release-token selected))
            (reset! release-state :released)
            true
            (catch #?(:clj Throwable :cljs :default) error
              ;; A failed native release is not equivalent to a closed
              ;; resource. Restore retryability and classify the boundary.
              (reset! release-state :open)
              (throw
               (ex-info
                "Snapshot provider failed to release an owned candidate."
                {:type :eacl/snapshot-release-failed
                 :eacl/error :eacl/snapshot-release-failed
                 :backend (backend-id (::provider selected))}
                error))))
          (recur))))))

(defn borrowed-adapter-provider
  "Compatibility provider for backends whose selected DB values are immutable
  and require no close.

  `static-adapter` is inspected during construction only and is not retained.
  Each acquisition callback must return a newly selected adapter (or throw).
  This compatibility path must never be used for a mutable or closeable native
  value."
  [{:keys [static-adapter topology execution-constraints source-scope-fn
           source-lifecycle-fn acquire-current! acquire-authoritative!
           acquire-at-least! acquire-exact!]}]
  (when-not (backend/adapter? static-adapter)
    (invalid-provider!
     "Borrowed compatibility provider requires a static v8 adapter."
     {:adapter static-adapter}))
  (doseq [[operation-key f]
          [[:source-scope source-scope-fn]
           [:source-lifecycle source-lifecycle-fn]
           [:acquire-current! acquire-current!]
           [:acquire-authoritative! acquire-authoritative!]
           [:acquire-at-least! acquire-at-least!]
           [:acquire-exact! acquire-exact!]]]
    (when-not (fn? f)
      (invalid-provider!
       "Borrowed compatibility provider requires every callback."
       {:backend (backend/backend-id static-adapter)
        :operation operation-key})))
  (let [backend-id (backend/backend-id static-adapter)
        capabilities (backend/capabilities static-adapter)
        traversal-execution (backend/traversal-execution static-adapter)
        fingerprint (backend/fingerprint static-adapter)
        deterministic? (backend/deterministic? static-adapter)
        acquired
        (fn [candidate]
          (when-not (backend/adapter? candidate)
            (invalid-selected-snapshot!
             "Borrowed provider acquisition returned no immutable adapter."
             {:backend backend-id :adapter candidate}))
          {:adapter candidate
           :ownership :borrowed
           :release-token nil})]
    (make-provider
     {:id backend-id
      :capabilities capabilities
      :traversal-execution traversal-execution
      :topology (or topology {:snapshot-values :immutable})
      :execution-constraints
      (or execution-constraints default-execution-constraints)
      :snapshot-ownership :borrowed
      :fingerprint fingerprint
      :deterministic? deterministic?
      :operations
      {:source-scope source-scope-fn
       :source-lifecycle source-lifecycle-fn
       :acquire-current! #(acquired (acquire-current!))
       :acquire-authoritative!
       #(acquired (acquire-authoritative! %))
       :acquire-at-least!
       #(acquired (acquire-at-least! %1 %2))
       :acquire-exact!
       #(acquired (acquire-exact! %1 %2))
       :release! (constantly nil)}})))

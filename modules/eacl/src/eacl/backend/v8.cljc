(ns eacl.backend.v8
  "Validated capability and operation contract for v8 backend snapshots.

  This is the sole production backend boundary for recursive traversal, Relay
  pagination, deletion, consistency selection, and ordered-generation proofs."
  (:require [eacl.authorization.data :as qualification-data]
            [eacl.exact-integer :as exact-integer]
            [eacl.relationships.edge :as edge]
            [eacl.request.counters :as request-counters]
            [eacl.spicedb.consistency :as consistency]))

(def adapter-version 9)
(def maximum-exact-integer exact-integer/maximum)
(def minimum-exact-integer exact-integer/minimum)

(def permission-expression-capability
  "Required canonical permission-expression contract carried by every v8
  adapter. Its version is fixed by the required operation rather than an
  optional capability declaration."
  :canonical-expression-v1)

(def direct-membership-batch-capability :bounded-aligned-v1)
(def maximum-direct-membership-batch-width 256)

(def ^:dynamic *backend-op-stats*
  "Optional atom counting backend adapter invocations by operation keyword.

  Includes `:schema-generation`, `:proof-frame`, and index scans.
  Observation-only: counters never influence dispatch or guard behavior."
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
    :basis-kind
    :native-revision
    :order-hint
    :exact-locator
    :object-id->internal
    :internal-id->object
    :relation-defs
    :permission-defs
    :permission-expression
    :subject->resources
    :resource->subjects
    :direct-match?
    :all-permission-nodes})

(def optional-snapshot-operations
  "Snapshot operations with fail-closed defaults. They remain visible to the
  certification boundary without making an uncertified snapshot invalid."
  #{:schema-generation :direct-match-many? :direct-edge :qualification-data})

(def ^:private source-authority-operation-keys
  #{:select-current :select-authoritative :select-at-least :select-exact
    :source-scope :source-lifecycle})

(def basis-adapter-obligations
  "Runtime-facing statement of the assumptions made by
  `formal/dafny/SnapshotOracle.dfy`.

  Dafny proves the engine correct when these obligations hold. It does not
  verify Datomic, DataScript, Datahike, their storage engines, or these adapter
  implementations. Certification tests and optional runtime guards provide
  evidence for each named obligation."
  {:snapshot-id
   #{:stable-for-immutable-snapshot :source-and-revision-identity}
   :basis-kind
   #{:stable-for-immutable-snapshot :complete-identity}
   :native-revision
   #{:selected-native-revision :monotone-order-hint :exact-locator-identity}
   :order-hint
   #{:selected-snapshot-order :exact-integer}
   :exact-locator
   #{:stable-for-immutable-snapshot}
   :object-id->internal
   #{:visible-object-total :injective :nonnegative :snapshot-bound}
   :internal-id->object
   #{:visible-object-round-trip :snapshot-bound}
   :relation-defs
   #{:finite :complete :type-correct :snapshot-bound}
   :permission-defs
   #{:finite :complete :type-correct :snapshot-bound}
   :permission-expression
   #{:canonical-expression-v1 :complete-metadata-and-digest
     :at-most-one-logical-permission :snapshot-bound}
   :subject->resources
   #{:finite :strict-order :unique :complete
     :inclusive-exclusive-bounds :nonnegative :snapshot-bound}
   :resource->subjects
   #{:finite :strict-order :unique :complete
     :inclusive-exclusive-bounds :nonnegative :snapshot-bound}
   :direct-match?
   #{:iff-forward-scan-membership :iff-reverse-scan-membership
     :snapshot-bound}
   :direct-edge
   #{:optional-capability-paired :stored-eid-or-qualified-pair-or-nil
     :iff-forward-scan-membership :iff-reverse-scan-membership
     :no-authorization-before-qualification :snapshot-bound}
   :direct-match-many?
   #{:optional-capability-paired :immutable-basis
     :normalized-direct-relation-descriptor
     :distinct-typed-input :maximum-width-256
     :aligned-boolean-result :scalar-equivalent
     :cooperative-cancellation :atomic-failure :snapshot-bound}
   :qualification-data
   #{:optional-capability-paired :bounded-entity-facts :unknown-fields-preserved
     :snapshot-bound :same-basis-assertion-version-or-nil :physical-facts-metered}
   :all-permission-nodes
   #{:finite :exact-schema-coverage :snapshot-bound}
   :schema-generation
   #{:snapshot-bound :transactionally-persisted-eacl-generation
     :at-most-one-index-probe :memoized-per-selected-adapter
     :independent-of-ordered-generations
     :advances-with-managed-schema-writes
     :unchanged-by-relationship-only-writes
     :nil-when-uncertified}
   :proof-frame
   #{:snapshot-bound :relation-generations-only
     :same-domain-as-native-revision :generation-at-or-below-revision
     :complete-canonical-relation-generations
     :globally-ordered-committed-generations
     :atomic-with-supported-mutations}})

(def known-consistency-modes
  #{:minimize-latency
    :fully-consistent
    :at-least-as-fresh
    :at-exact-snapshot})

(def basis-kinds
  "Closed classification of database views at the adapter boundary. Only
  ordinary and exact as-of values are admissible public authorization bases."
  #{:ordinary :as-of :filtered :since :history :speculative})

(def admissible-basis-kinds #{:ordinary :as-of})

(defn admissible-basis-kind?
  [kind]
  (contains? admissible-basis-kinds kind))

(def empty-capabilities
  {:consistency #{}
   :snapshots #{}
   :source #{}
   :cursor #{}
   :transactions #{}
   :cache-proofs #{}
   :runtime #{}})

(def ^:private known-capability-groups
  (conj (set (keys empty-capabilities)) :direct-membership-batch :qualification))

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

(defn validate-adapter-config!
  "Certifies that a basis adapter receives only immutable-value conversion
  configuration declared by its backend. Connection, source, writer, and
  request/runtime option leakage is rejected before adapter construction."
  [backend-id allowed-keys config]
  (when-not (map? config)
    (throw
     (ex-info
      "Basis adapter configuration must be a map."
      {:type :eacl/invalid-backend-role
       :eacl/error :eacl/invalid-backend-role
       :role :adapter
       :backend backend-id
       :operation :configure
       :value config})))
  (when-let [unknown (seq (remove allowed-keys (keys config)))]
    (throw
     (ex-info
      "Basis adapter received state outside its closed configuration."
      {:type :eacl/invalid-backend-role
       :eacl/error :eacl/invalid-backend-role
       :role :adapter
       :backend backend-id
       :operation (first unknown)
       :unknown-keys (vec unknown)
       :allowed-keys allowed-keys})))
  config)

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
   basis-adapter-obligations)
  ([operation-key]
   (or (get basis-adapter-obligations operation-key)
       (invalid-adapter!
        "Unknown backend operation in certification request."
        {:operation operation-key
         :known-operations (set (keys basis-adapter-obligations))}))))

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
        unknown-keys (seq (remove known-capability-groups
                                  (keys normalized)))]
    (when unknown-keys
      (invalid-adapter! "Backend declares unknown capability groups."
                        {:backend backend-id
                         :unknown-capabilities (vec unknown-keys)
                         :known-capabilities known-capability-groups}))
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
    (when-let [unknown-batch-contracts
               (seq (remove #{direct-membership-batch-capability}
                            (:direct-membership-batch normalized)))]
      (invalid-adapter!
       "Backend declares an unknown direct-membership batch contract."
       {:backend backend-id
        :unknown-direct-membership-batch-contracts
        (vec unknown-batch-contracts)
        :known-direct-membership-batch-contracts
        #{direct-membership-batch-capability}}))
    (when (seq (remove #{qualification-data/capability} (:qualification normalized)))
      (invalid-adapter! "Backend declares an unknown qualification data contract." {:backend backend-id}))
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
           identity-contract runtime-guards? traversal-execution
           operator-physical-policy]
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
  (when-let [forbidden
             (seq (filter source-authority-operation-keys
                          (keys operations)))]
    (throw
     (ex-info
      "Basis adapter contains source-selection authority."
      {:type :eacl/invalid-backend-role
       :eacl/error :eacl/invalid-backend-role
       :role :adapter
       :backend id
       :operation (first forbidden)
       :forbidden-operations (vec forbidden)})))
  (when (and (contains? operations :schema-generation)
             (not (fn? (:schema-generation operations))))
    (invalid-adapter!
     "Optional backend operations must be functions when supplied."
     {:backend id
      :operation :schema-generation
      :value (:schema-generation operations)}))
  (when (and (contains? operations :direct-match-many?)
             (not (fn? (:direct-match-many? operations))))
    (invalid-adapter!
     "Optional backend operations must be functions when supplied."
     {:backend id
      :operation :direct-match-many?
      :value (:direct-match-many? operations)}))
  (let [missing
        (reduce
         (fn [result operation]
           (if (ifn? (get operations operation))
             result
             (conj result operation)))
         nil
         required-snapshot-operations)]
    (when missing
      (throw
       (ex-info
        "Basis adapter is missing required operations."
        {:type :eacl/invalid-backend-role
         :eacl/error :eacl/invalid-backend-role
         :role :adapter
         :backend id
         :operation (first missing)
         :missing-operations (vec missing)
         :required-operations required-snapshot-operations}))))
  (let [normalized (normalize-capabilities id capabilities)
        traversal-execution
        (normalize-traversal-execution id traversal-execution)
        schema-generation
        (when-let [read-generation (:schema-generation operations)]
          (delay (read-generation)))
        operations
        (assoc operations
               :schema-generation
               (if schema-generation
                 (fn [] @schema-generation)
                 (constantly nil)))]
    (when (and (contains? (:cache-proofs normalized) :ordered-generations)
               (not (fn? (:proof-frame operations))))
      (invalid-adapter!
       "Backend advertises ordered generations without a proof-frame operation."
       {:backend id :capability :ordered-generations}))
    (when-not (= (contains? (:qualification normalized) qualification-data/capability)
                 (fn? (:qualification-data operations)))
      (invalid-adapter! "Qualification data capability and operation must be declared together."
                        {:backend id :operation :qualification-data}))
    (let [batch-capability?
          (contains? (:direct-membership-batch normalized)
                     direct-membership-batch-capability)
          batch-operation? (fn? (:direct-match-many? operations))]
      (when-not (= batch-capability? batch-operation?)
        (invalid-adapter!
         "Batched direct membership capability and operation must be declared together."
         {:backend id
          :capability direct-membership-batch-capability
          :capability-present? batch-capability?
          :operation :direct-match-many?
          :operation-present? batch-operation?}))
      (when-not (= batch-capability? (some? operator-physical-policy))
        (invalid-adapter!
         "Native batched membership requires one sealed physical policy identity."
         {:backend id
          :capability direct-membership-batch-capability
          :capability-present? batch-capability?
          :physical-policy operator-physical-policy}))
      (when (and operator-physical-policy
                 (not (and (map? operator-physical-policy)
                           (= #{:id :parameters}
                              (set (keys operator-physical-policy)))
                           (keyword? (:id operator-physical-policy))
                           (map? (:parameters operator-physical-policy)))))
        (invalid-adapter!
         "Operator physical policy identity must be a closed versioned value."
         {:backend id :physical-policy operator-physical-policy})))
  (cond->
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
    ::state state}
    operator-physical-policy
    (assoc ::operator-physical-policy operator-physical-policy))))

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

(defn operator-capability-identity
  "Returns the sealed physical capability identity used by operator plans.
  Absence of native batching is an explicit scalar-fallback identity, never an
  implicit or provider-selected behavior."
  [adapter]
  (if (adapter? adapter)
    {:permission-expression permission-expression-capability
     :direct-membership
     {:mode (if (contains?
                 (get (capabilities adapter) :direct-membership-batch #{})
                 direct-membership-batch-capability)
              direct-membership-batch-capability
              :certified-scalar-fallback-v1)
      :maximum-width maximum-direct-membership-batch-width
      :physical-policy
      (or (::operator-physical-policy adapter)
          {:id :certified-scalar-fallback-v1 :parameters {}})}}
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

(declare invoke)

(defn basis-kind
  "Returns the certified database-view classification for one adapter."
  [adapter]
  (let [kind (invoke adapter :basis-kind)]
    (when-not (contains? basis-kinds kind)
      (contract-violation!
       (backend-id adapter) :basis-kind :known-basis-kind kind))
    kind))

(defn schema-generation?
  "True for a portable certified schema-generation value."
  [value]
  (exact-integer/natural? value))

(defn- within-bound?
  [direction bound inclusive? value]
  (if (= :desc direction)
    ((if inclusive? >= >) bound value)
    ((if inclusive? <= <) bound value)))

(defn- guard-scan!
  [adapter operation-key options value]
  (let [backend-id (::id adapter)
        direction (or (:direction options) :asc)
        bound (:bound-eid options)
        inclusive? (true? (:inclusive-bound? options))
        compact? (true? (:include-qualifier? options))]
    (when-not (sequential? value)
      (contract-violation!
       backend-id operation-key :finite-sequential-result value))
    (when-not (contains? #{:asc :desc} direction)
      (contract-violation!
       backend-id operation-key :known-direction direction))
    (loop [remaining (seq value)
           previous nil
           first? true]
      (if-not remaining
        value
        (let [raw-item (first remaining)
              valid-item? (if compact? (edge/valid? raw-item) (exact-integer/natural? raw-item))
              item (if (and compact? valid-item?) (edge/endpoint raw-item) raw-item)]
          ;; One combined predicate on the hot path; the failed obligation
          ;; is classified only on the cold violation branch.
          (when-not valid-item?
            (contract-violation!
             backend-id operation-key
             (if (exact-integer/exact? item) :nonnegative :exact-integer)
             value))
          (when-not first?
            (if (= previous item)
              (contract-violation!
               backend-id operation-key :unique value)
              (when-not ((if (= :desc direction) > <) previous item)
                (contract-violation!
                 backend-id operation-key :strict-order value))))
          (when (and (some? bound)
                     (not (within-bound?
                           direction bound inclusive? item)))
            (contract-violation!
             backend-id operation-key
             :inclusive-exclusive-bound
             {:options options :values value}))
          (recur (next remaining) item false))))))

(defn- guard-output!
  [adapter operation-key options value]
  (let [backend-id (::id adapter)]
    (case operation-key
      (:subject->resources :resource->subjects)
      (guard-scan! adapter operation-key (or options {}) value)

      :object-id->internal
      (do
        (when (and (some? value)
                   (not (exact-integer/natural? value)))
          (contract-violation!
           backend-id operation-key
           (if (exact-integer/exact? value) :nonnegative :exact-integer)
           value))
        value)

      :order-hint
      (do
        (when-not (exact-integer/natural? value)
          (contract-violation!
           backend-id operation-key
           (if (exact-integer/exact? value) :nonnegative :exact-integer)
           value))
        value)

      :basis-kind
      (do
        (when-not (contains? basis-kinds value)
          (contract-violation!
           backend-id operation-key :known-basis-kind value))
        value)

      :schema-generation
      (do
        (when-not (or (nil? value) (schema-generation? value))
          (contract-violation!
           backend-id operation-key :exact-natural-or-nil value))
        value)

      (:snapshot-id :native-revision)
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

      :permission-expression
      (do
        (when-not (or (nil? value) (map? value))
          (contract-violation!
           backend-id operation-key :canonical-expression-or-nil value))
        value)

      :all-permission-nodes
      (do
        (when-not (set? value)
          (contract-violation!
           backend-id operation-key :finite-node-set value))
        value)

      :qualification-data
      (do
        (when-not (and (map? value) (= #{:entity :version :fact-count} (set (keys value)))
                       (or (nil? (:entity value)) (map? (:entity value)))
                       (or (nil? (:version value)) (exact-integer/natural? (:version value)))
                       (exact-integer/natural? (:fact-count value))
                       (<= (:fact-count value) qualification-data/maximum-entity-facts))
          (contract-violation! backend-id operation-key :bounded-qualification-data :redacted))
        value)

      :direct-match?
      (do
        (when-not (boolean? value)
          (contract-violation!
           backend-id operation-key :boolean-result value))
        value)

      :direct-edge
      (do
        (when-not (or (nil? value) (edge/valid? value))
          (contract-violation! backend-id operation-key :compact-edge-or-nil value))
        value)

      :direct-match-many?
      (do
        (when-not (and (sequential? value) (every? boolean? value))
          (contract-violation!
           backend-id operation-key :boolean-vector value))
        value)

      ;; These values are intentionally opaque at this boundary. Their
      ;; semantic obligations (round trip, locator identity, and complete
      ;; proofs) require paired/global certification rather than a local shape
      ;; predicate. They still pass through this dispatch so an added callback
      ;; cannot silently bypass the runtime-guard review.
      (:internal-id->object :exact-locator :proof-frame)
      value

      (contract-violation!
       backend-id operation-key :registered-runtime-guard value))))

(defn ^:no-doc scan-invoker
  "Captures one immutable ordered-scan implementation while preserving the
  complete adapter invocation boundary on every call.

  This is a fixed-arity specialization of `invoke`, not a raw adapter escape:
  mandatory request metering, optional backend stats, invocation observers,
  runtime guards, and failure observation remain identical."
  [adapter operation-key]
  (when-not (contains? #{:subject->resources :resource->subjects}
                       operation-key)
    (invalid-adapter! "A scan invoker requires an ordered scan operation."
                      {:operation operation-key}))
  (if-not (adapter? adapter)
    ;; Preserve the old call-time validation/redefinition seam for test and
    ;; diagnostic fetch functions that deliberately stand in for an adapter.
    ;; Production adapters always take the captured fixed-arity path below.
    (fn [endpoint-type endpoint-eid relation-eid result-type options]
      (invoke adapter operation-key endpoint-type endpoint-eid
              relation-eid result-type options))
    (let [implementation (operation adapter operation-key)
          guarded? (runtime-guards? adapter)]
      (fn [endpoint-type endpoint-eid relation-eid result-type options]
        (request-counters/add-adapter-reads!)
        (when *backend-op-stats*
          (swap! *backend-op-stats* update operation-key (fnil inc 0)))
        (observe-invocation! :before adapter operation-key)
        (try
          (let [value (implementation endpoint-type endpoint-eid
                                      relation-eid result-type options)]
            (observe-invocation! :after adapter operation-key)
            (if guarded?
              (guard-scan! adapter operation-key options value)
              value))
          (catch #?(:clj Throwable :cljs :default) error
            (observe-invocation! :failed adapter operation-key)
            (throw error)))))))

(defn- direct-invoker
  [adapter operation-key]
  (when-not (contains? #{:direct-match? :direct-edge} operation-key)
    (invalid-adapter! "A direct invoker requires a direct membership operation."
                      {:operation operation-key}))
    (if-not (adapter? adapter)
      (fn [subject-type subject-eid relation-eid resource-type resource-eid]
        (invoke adapter operation-key subject-type subject-eid relation-eid
                resource-type resource-eid))
      (let [implementation (operation adapter operation-key)
            guarded? (runtime-guards? adapter)]
        (fn [subject-type subject-eid relation-eid resource-type resource-eid]
          (request-counters/add-adapter-reads!)
          (when *backend-op-stats*
            (swap! *backend-op-stats* update operation-key (fnil inc 0)))
          (observe-invocation! :before adapter operation-key)
          (try
            (let [value (implementation subject-type subject-eid relation-eid
                                        resource-type resource-eid)]
              (observe-invocation! :after adapter operation-key)
              (if guarded? (guard-output! adapter operation-key nil value) value))
            (catch #?(:clj Throwable :cljs :default) error
              (observe-invocation! :failed adapter operation-key)
              (throw error)))))))

(defn ^:no-doc direct-match-invoker
  "Captures immutable direct membership with complete metering and guards."
  [adapter] (direct-invoker adapter :direct-match?))

(defn ^:no-doc direct-edge-invoker
  "Captures compact direct edges with complete metering and guards."
  [adapter] (direct-invoker adapter :direct-edge))

(defn- invoke-completed
  [adapter operation-key guarded? options value]
  (observe-invocation! :after adapter operation-key)
  (if guarded?
    (guard-output! adapter operation-key options value)
    value))

(defn invoke
  "Invokes one adapter operation through the complete invocation boundary.
  Fixed arities avoid per-call rest-arg allocation and `apply`; `operation`
  validates the adapter once, so the guard flag is read directly. The
  trailing options argument of a 5-argument ordered scan reaches the output
  guard without traversing an argument seq."
  ([adapter operation-key]
   (let [implementation (operation adapter operation-key)
         guarded? (true? (::runtime-guards? adapter))]
     (request-counters/add-adapter-reads!)
     (when *backend-op-stats*
       (swap! *backend-op-stats* update operation-key (fnil inc 0)))
     (observe-invocation! :before adapter operation-key)
     (try
       (invoke-completed adapter operation-key guarded? nil
                         (implementation))
       (catch #?(:clj Throwable :cljs :default) error
         (observe-invocation! :failed adapter operation-key)
         (throw error)))))
  ([adapter operation-key a]
   (let [implementation (operation adapter operation-key)
         guarded? (true? (::runtime-guards? adapter))]
     (request-counters/add-adapter-reads!)
     (when *backend-op-stats*
       (swap! *backend-op-stats* update operation-key (fnil inc 0)))
     (observe-invocation! :before adapter operation-key)
     (try
       (invoke-completed adapter operation-key guarded? nil
                         (implementation a))
       (catch #?(:clj Throwable :cljs :default) error
         (observe-invocation! :failed adapter operation-key)
         (throw error)))))
  ([adapter operation-key a b]
   (let [implementation (operation adapter operation-key)
         guarded? (true? (::runtime-guards? adapter))]
     (request-counters/add-adapter-reads!)
     (when *backend-op-stats*
       (swap! *backend-op-stats* update operation-key (fnil inc 0)))
     (observe-invocation! :before adapter operation-key)
     (try
       (invoke-completed adapter operation-key guarded? nil
                         (implementation a b))
       (catch #?(:clj Throwable :cljs :default) error
         (observe-invocation! :failed adapter operation-key)
         (throw error)))))
  ([adapter operation-key a b c]
   (let [implementation (operation adapter operation-key)
         guarded? (true? (::runtime-guards? adapter))]
     (request-counters/add-adapter-reads!)
     (when *backend-op-stats*
       (swap! *backend-op-stats* update operation-key (fnil inc 0)))
     (observe-invocation! :before adapter operation-key)
     (try
       (invoke-completed adapter operation-key guarded? nil
                         (implementation a b c))
       (catch #?(:clj Throwable :cljs :default) error
         (observe-invocation! :failed adapter operation-key)
         (throw error)))))
  ([adapter operation-key a b c d]
   (let [implementation (operation adapter operation-key)
         guarded? (true? (::runtime-guards? adapter))]
     (request-counters/add-adapter-reads!)
     (when *backend-op-stats*
       (swap! *backend-op-stats* update operation-key (fnil inc 0)))
     (observe-invocation! :before adapter operation-key)
     (try
       (invoke-completed adapter operation-key guarded? nil
                         (implementation a b c d))
       (catch #?(:clj Throwable :cljs :default) error
         (observe-invocation! :failed adapter operation-key)
         (throw error)))))
  ([adapter operation-key a b c d e]
   (let [implementation (operation adapter operation-key)
         guarded? (true? (::runtime-guards? adapter))]
     (request-counters/add-adapter-reads!)
     (when *backend-op-stats*
       (swap! *backend-op-stats* update operation-key (fnil inc 0)))
     (observe-invocation! :before adapter operation-key)
     (try
       (invoke-completed adapter operation-key guarded? e
                         (implementation a b c d e))
       (catch #?(:clj Throwable :cljs :default) error
         (observe-invocation! :failed adapter operation-key)
         (throw error))))))

(defn reduce-definitions
  "Incrementally consumes one schema-definition sequence.

  This boundary exists so callers can meter and deadline-check lazy adapter
  results without first realizing the complete sequence. Existing definition
  operation arities and the ordinary `invoke` behavior remain unchanged."
  [adapter operation-key args init
   {:keys [before-realize! after-realize! step]
    :or {before-realize! (constantly nil)
         after-realize! (constantly nil)}}]
  (request-counters/add-adapter-reads!)
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
  (request-counters/add-adapter-reads!)
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
          inclusive? (true? (:inclusive-bound? options))
          compact? (true? (:include-qualifier? options))
          guarded? (runtime-guards? adapter)]
      (observe-invocation! :after adapter operation-key)
      (when-not (sequential? value)
        (contract-violation!
         (::id adapter) operation-key :finite-sequential-result :redacted))
      (when (and guarded?
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
            (let [item (first items)
                  valid-item? (or (not guarded?)
                                  (if compact? (edge/valid? item) (exact-integer/natural? item)))
                  eid (if (and compact? valid-item?) (edge/endpoint item) item)]
              (when guarded?
                (when-not valid-item?
                  (contract-violation!
                   (::id adapter) operation-key :exact-natural :redacted))
                (when (and (some? previous)
                           (not ((if (= :desc direction) > <)
                                 previous eid)))
                  (contract-violation!
                   (::id adapter) operation-key :strict-order :redacted))
                (when (and (some? bound)
                           (not (within-bound?
                                 direction bound inclusive? eid)))
                  (contract-violation!
                   (::id adapter) operation-key
                   :inclusive-exclusive-bound :redacted)))
              (recur (rest items) eid (step accumulator item)))))))
    (catch #?(:clj Throwable :cljs :default) error
      (observe-invocation! :failed adapter operation-key)
      (throw error))))

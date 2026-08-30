(ns eacl.formal.production-kernel
  "Released generated-Java implementation of EACL's strict decision SPI."
  (:require [eacl.cache.standard-lru :as lru]
            [eacl.formal.generated-runtime]
            [eacl.verified-kernel :as verified])
  (:import
   (ConsistencyDecision
   ConsistencyError
   SelectionKind
   SelectionWork
   SnapshotConsistencyMode
    SuccessfulSelectionPath)
   (dafny DafnySequence Tuple2 TypeDescriptor)
   (IndexedCertification PlanCertificationError)
   (IndexedBatching
    ForwardBatchState
    ForwardBatchStep
    ReverseBatchState
    ReverseBatchStep)
   (IndexedRefinement RelationBinding)
   (IndexedTraversal
    ForwardInit
    ForwardPageContinuation
    ForwardResume
    ForwardState
    IndexedLimits
    IndexedLimitKind
    IndexedRule
    OptionalEid
    PageContinuationError
    Projection
    PublicRenderResult
    RenderMode
    RenderError
    ResourceCounters
    ReverseInit
    ReversePageContinuation
    ReverseResume
    ReverseState
    ScanError
    ScanCommand
    ScanResponse)
   (OrderedMerge MergeChunk MergeDirection OptionalHead)
   (PageWindow
    Direction
    ExactSelection
    NormalizedPageRequest
    Page
    PageError
    Presence
    RawPageRequest)
   (RecursiveEngine
    BooleanOutcome
    CountOutcome
    LimitKind
    SequenceOutcome
    TraversalLimits
    WorkCounters)
   (RoutingCertificate
    EnumerationRoute
    EnumerationRouteDecision
    EnumerationRouteError
    IndexedDependencyEdge
    IndexedRoutingPath
    RoutingDerivationCounters
    RoutingDerivationDecision
    RoutingCertificateError
    RoutingProof)
   (AcyclicEngine
    AcyclicContinuationAction
    AcyclicCountDecision
    AcyclicDirection
    AcyclicPageDecision
    AcyclicPageWork
    AcyclicWorkDecision)
   (Semantics
    ObjectRef
    PermissionNode
    RelationNode
    Relationship
    RuleDefinition)
   (java.math BigInteger)))

(defn- dafny-string
  [value]
  (DafnySequence/asUnicodeString value))

(defn- dafny-nat
  [value]
  (biginteger value))

(defn- dafny-long
  [^BigInteger value]
  (.longValueExact value))

(defn- dafny-unicode
  [^DafnySequence value]
  (.verbatimString value))

(defn- dafny-sequence
  [values]
  (DafnySequence/fromList
   TypeDescriptor/BIG_INTEGER
   (mapv dafny-nat values)))

(defn- typed-sequence
  [descriptor values]
  (DafnySequence/fromList descriptor values))

(def ^:private fuel-memo (volatile! nil))

(defn- dafny-fuel
  [fuel]
  (let [cached @fuel-memo]
    (if (and cached (= (first cached) fuel))
      (second cached)
      (let [built (dafny-nat fuel)]
        (vreset! fuel-memo [fuel built])
        built))))

(def ^:private unicode-memo
  "Standard LRU for type-name decodes keyed by the raw
  DafnySequence (the patched runtime implements equals/hashCode).
  Scan commands carry a handful of distinct type names but previously
  decoded UTF-32 strings per command."
  (lru/store 64))

(defn- dafny-unicode-interned
  [^DafnySequence value]
  (try
    (let [resident (lru/lookup! unicode-memo value)]
      (if (:found? resident)
        (:value resident)
        (let [decoded (.verbatimString value)]
          ;; Decoding belongs to the requesting thread, outside the LRU atom
          ;; transition. A publication race or private-store failure is only
          ;; an optimization miss and cannot affect the decoded result.
          (try
            (lru/put-if-absent! unicode-memo value decoded)
            (catch Throwable _ nil))
          decoded)))
    (catch Throwable _
      (.verbatimString value))))

(def ^:private empty-values-sequence
  "Interned empty scan-response payload: ~98% of populated-recursion
  scan responses realize zero datoms (audited emptiness probes). Sound
  under the collection shims' contract that generated code mutates only
  freshly constructed wrappers — pinned by a regression asserting the
  interned instance stays empty after traversals."
  (delay (dafny-sequence [])))

(def ^:private limits-memo
  "Single-slot identity memo: traversal limits are one map value per
  request (usually the engine's default), yet the sequential protocol
  re-marshalled three BigIntegers on EVERY drive and EVERY resume —
  2x crossings avoidable allocations per traversal (audited). Volatile
  race is harmless: last write wins, both objects are equivalent pure
  values."
  (volatile! nil))

(defn- object->dafny
  [{:keys [type id]}]
  (ObjectRef/create
   (dafny-string type)
   (dafny-string id)))

(defn- dafny-object->object
  [^ObjectRef object]
  {:type (dafny-unicode (.dtor_typeName object))
   :id (dafny-unicode (.dtor_objectId object))})

(defn- permission-node
  [{:keys [resource-type permission]}]
  (PermissionNode/create
   (dafny-string resource-type)
   (dafny-string permission)))

(defn- indexed-routing-edge
  [{:keys [head target]}]
  (IndexedDependencyEdge/create
   (dafny-nat head)
   (dafny-nat target)))

(defn- indexed-routing-path
  [{:keys [kind head target]}]
  (case kind
    :relation
    (IndexedRoutingPath/create_IndexedDirectRelation
     (dafny-nat head))

    :self-permission
    (IndexedRoutingPath/create_IndexedSelfPermission
     (dafny-nat head)
     (dafny-nat target))

    :arrow-relation
    (IndexedRoutingPath/create_IndexedArrowRelation
     (dafny-nat head))

    :arrow-permission
    (IndexedRoutingPath/create_IndexedArrowPermission
     (dafny-nat head)
     (dafny-nat target))))

(defn- routing-proof
  [{:keys [component-root
           forward-parent-edge
           reverse-parent-edge
           forward-depth
           reverse-depth
           component-rank
           multiple-member-witness
           self-loop-witness-edge
           traversal
           traversal-witness-edge]}]
  (RoutingProof/create
   (dafny-sequence component-root)
   (dafny-sequence forward-parent-edge)
   (dafny-sequence reverse-parent-edge)
   (dafny-sequence forward-depth)
   (dafny-sequence reverse-depth)
   (dafny-sequence component-rank)
   (dafny-sequence multiple-member-witness)
   (dafny-sequence self-loop-witness-edge)
   (typed-sequence TypeDescriptor/BOOLEAN traversal)
   (dafny-sequence traversal-witness-edge)))

(defn- routing-certificate-error
  [^RoutingCertificateError error]
  (cond
    (.is_ShapeMismatch error) :shape-mismatch
    (.is_InvalidComponent error) :invalid-component
    (.is_InvalidDependencyEdge error) :invalid-dependency-edge
    (.is_InvalidComponentWitness error) :invalid-component-witness
    (.is_InvalidRoutingPath error) :invalid-routing-path
    (.is_RoutingPathEdgeMismatch error) :routing-path-edge-mismatch
    :else
    (throw
     (ex-info
      "Unknown generated routing certificate error."
      {:error error}))))

(defn- routing-certificate-decision
  [{:keys [node-count path-descriptors edges certificate]}]
  (let [^RoutingDerivationDecision decision
        (RoutingCertificate.__default/CheckRoutingCertificateFromPaths
         (dafny-nat node-count)
         (typed-sequence
          (IndexedRoutingPath/_typeDescriptor)
          (mapv indexed-routing-path path-descriptors))
         (typed-sequence
          (IndexedDependencyEdge/_typeDescriptor)
          (mapv indexed-routing-edge edges))
         (routing-proof certificate))
        ^RoutingDerivationCounters counters
        (.dtor_counters decision)
        work
        {:path-checks (dafny-long (.dtor_pathChecks counters))
         :node-checks (dafny-long (.dtor_nodeChecks counters))
         :edge-checks (dafny-long (.dtor_edgeChecks counters))}]
    (if (.is_RoutingDerivationAccepted decision)
      (merge
       {:status :accepted
        :traversal (mapv boolean (.dtor_traversal decision))}
       work)
      (merge
       {:status :rejected
        :reason
        (routing-certificate-error (.dtor_error decision))}
       work))))

(defn- enumeration-route-decision
  [{:keys [schema-identity certificate-schema-identity
           root-defined? recursive? recursive-data-active?]}]
  (let [^EnumerationRouteDecision decision
        (RoutingCertificate.__default/SelectEnumerationRoute
         (dafny-string schema-identity)
         (dafny-string certificate-schema-identity)
         root-defined?
         recursive?
         recursive-data-active?)]
    (if (.is_EnumerationRouteAccepted decision)
      (let [^EnumerationRoute route (.dtor_route decision)]
        {:status :accepted
         :route
         (cond
           (.is_UndefinedEnumerationRoute route) :undefined
           (.is_CertifiedAcyclicEnumerationRoute route) :acyclic
           :else :recursive)})
      (let [^EnumerationRouteError error (.dtor_error decision)]
        {:status :rejected
         :reason
         (if (.is_MissingSchemaIdentity error)
           :missing-schema-identity
           :schema-identity-mismatch)}))))

(defn- acyclic-direction
  [direction]
  (case direction
    :asc
    (AcyclicDirection/create_AcyclicAscending)

    :desc
    (AcyclicDirection/create_AcyclicDescending)))

(defn- acyclic-page-decision
  [{:keys [direction realized-eids size bound?]}]
  (let [^AcyclicPageDecision decision
        (AcyclicEngine.__default/DecideAcyclicPage
         (acyclic-direction direction)
         (dafny-sequence realized-eids)
         (dafny-nat size)
         bound?)
        ^AcyclicPageWork work (.dtor_work decision)]
    {:take-count (dafny-long (.dtor_takeCount decision))
     :reverse? (.dtor_reverseOutput decision)
     :has-next? (.dtor_hasNext decision)
     :has-previous? (.dtor_hasPrevious decision)
     :merge-advances (dafny-long (.dtor_mergeAdvances work))
     :emitted-results (dafny-long (.dtor_emittedResults work))
     :recursive-work (dafny-long (.dtor_recursiveWork work))}))

(defn- acyclic-continuation-decision
  [{:keys [authenticated? schema-matches? query-matches?
           snapshot-matches? entry-present? entry-valid?]}]
  (let [^AcyclicContinuationAction action
        (AcyclicEngine.__default/DecideAcyclicContinuation
         authenticated?
         schema-matches?
         query-matches?
         snapshot-matches?
         entry-present?
         entry-valid?)]
    (cond
      (.is_ResumePrivateContinuation action) :resume
      (.is_ReplayAuthenticatedBoundary action) :replay
      :else :reject)))

(defn- acyclic-count-decision
  [{:keys [unique-count more? limit]}]
  (let [^AcyclicCountDecision decision
        (AcyclicEngine.__default/DecideAcyclicCount
         (dafny-nat unique-count)
         more?
         (some? limit)
         (dafny-nat (or limit 0)))]
    {:count (dafny-long (.dtor_count decision))
     :truncated? (.dtor_truncated decision)
     :recursive-work
     (dafny-long (.dtor_recursiveWork decision))}))

(defn- acyclic-work-decision
  [{:keys [requested-window merge-advances
           emitted-results recursive-work]}]
  (let [^AcyclicWorkDecision decision
        (AcyclicEngine.__default/CertifyAcyclicWork
         (dafny-nat requested-window)
         (dafny-nat merge-advances)
         (dafny-nat emitted-results)
         (dafny-nat recursive-work))]
    (if (.is_AcyclicWorkAccepted decision)
      :accepted
      :rejected)))

(defn- relation-node
  [{:keys [resource-type relation subject-type]}]
  (RelationNode/create
   (dafny-string resource-type)
   (dafny-string relation)
   (dafny-string subject-type)))

(defn- rule-definition
  [{:keys [kind resource-type permission relation subject-type
           target-permission via-relation target-relation]}]
  (let [head
        (permission-node
         {:resource-type resource-type
          :permission permission})]
    (case kind
      :direct-relation
      (RuleDefinition/create_DirectRelation
       head
       (dafny-string relation)
       (dafny-string subject-type))

      :self-permission
      (RuleDefinition/create_SelfPermission
       head
       (dafny-string target-permission))

      :arrow-relation
      (RuleDefinition/create_ArrowRelation
       head
       (dafny-string via-relation)
       (dafny-string target-relation)
       (dafny-string subject-type))

      :arrow-permission
      (RuleDefinition/create_ArrowPermission
       head
       (dafny-string via-relation)
       (dafny-string target-permission)))))

(defn- relationship->dafny
  [{:keys [resource relation subject]}]
  (Relationship/create
   (object->dafny resource)
   (dafny-string relation)
   (object->dafny subject)))

(defn- authorization-inputs
  [{:keys [objects schema relationships]}]
  {:objects
   (typed-sequence
    (ObjectRef/_typeDescriptor)
    (mapv object->dafny objects))
   :relations
   (typed-sequence
    (RelationNode/_typeDescriptor)
    (mapv relation-node (:relations schema)))
   :permissions
   (typed-sequence
    (PermissionNode/_typeDescriptor)
    (mapv permission-node (:permissions schema)))
   :definitions
   (typed-sequence
    (RuleDefinition/_typeDescriptor)
    (mapv rule-definition (:definitions schema)))
   :relationships
   (typed-sequence
    (Relationship/_typeDescriptor)
    (mapv relationship->dafny relationships))})

(defn- traversal-limits
  [{:keys [max-derived-grants
           max-advanced-datoms
           max-queued-work]}]
  (TraversalLimits/create
   (dafny-nat max-derived-grants)
   (dafny-nat max-advanced-datoms)
   (dafny-nat max-queued-work)))

(defn- limit-kind
  [^LimitKind kind]
  (cond
    (.is_DerivedGrants kind) :derived-grants
    (.is_AdvancedDatoms kind) :advanced-datoms
    :else :queued-work))

(defn- work-counters
  [^WorkCounters counters]
  {:derived-grants
   (dafny-long (.dtor_derivedGrants counters))
   :advanced-datoms
   (dafny-long (.dtor_advancedDatoms counters))
   :queued-work
   (dafny-long (.dtor_queuedWork counters))})

(defn- sequence-outcome
  [operation ^SequenceOutcome outcome]
  (if (.is_SequenceComplete outcome)
    {:status :complete
     :operation operation
     :items (mapv dafny-object->object (.dtor_items outcome))
     :counters (work-counters (.dtor_counters outcome))}
    {:status :limit-exceeded
     :operation operation
     :limit-kind (limit-kind (.dtor_kind outcome))
     :counters (work-counters (.dtor_counters outcome))}))

(defn- boolean-outcome
  [operation ^BooleanOutcome outcome]
  (if (.is_BooleanComplete outcome)
    {:status :complete
     :operation operation
     :allowed? (.dtor_allowed outcome)
     :counters (work-counters (.dtor_counters outcome))}
    {:status :limit-exceeded
     :operation operation
     :limit-kind (limit-kind (.dtor_kind outcome))
     :counters (work-counters (.dtor_counters outcome))}))

(defn- count-outcome
  [operation ^CountOutcome outcome]
  (if (.is_CountComplete outcome)
    {:status :complete
     :operation operation
     :count (dafny-long (.dtor_count outcome))
     :truncated? (.dtor_truncated outcome)
     :counters (work-counters (.dtor_counters outcome))}
    {:status :limit-exceeded
     :operation operation
     :limit-kind (limit-kind (.dtor_kind outcome))
     :counters (work-counters (.dtor_counters outcome))}))

(defn- authorization-outcome
  [{:keys [objects permissions definitions relationships]}
   request limits]
  (let [node
        (permission-node
         {:resource-type
          (or (:resource-type request)
              (:type (:resource request)))
          :permission (:permission request)})
        traversal-limits (traversal-limits limits)]
    (case (:operation request)
      :can?
      (boolean-outcome
       :can?
       (RecursiveEngine.__default/RecursiveCan
        objects
        permissions
        definitions
        relationships
        (object->dafny (:subject request))
        node
        (object->dafny (:resource request))
        traversal-limits))

      :lookup-resources
      (sequence-outcome
       :lookup-resources
       (RecursiveEngine.__default/RecursiveForward
        objects
        permissions
        definitions
        relationships
        (object->dafny (:subject request))
        node
        traversal-limits))

      :lookup-subjects
      (sequence-outcome
       :lookup-subjects
       (RecursiveEngine.__default/RecursiveReverseTyped
        objects
        permissions
        definitions
        relationships
        (object->dafny (:resource request))
        node
        (dafny-string (:subject-type request))
        traversal-limits))

      :count-resources
      (count-outcome
       :count-resources
       (RecursiveEngine.__default/RecursiveCountForward
        objects
        permissions
        definitions
        relationships
        (object->dafny (:subject request))
        node
        (dafny-nat (:count-limit request))
        traversal-limits))

      :count-subjects
      (count-outcome
       :count-subjects
       (RecursiveEngine.__default/RecursiveCountReverseTyped
        objects
        permissions
        definitions
        relationships
        (object->dafny (:resource request))
        node
        (dafny-string (:subject-type request))
        (dafny-nat (:count-limit request))
        traversal-limits)))))

(defn- authorization-decision
  [{:keys [request] :as input}]
  (let [{:keys [relations permissions definitions]
         dafny-objects :objects
         dafny-relationships :relationships
         :as converted}
        (authorization-inputs input)
        well-formed?
        (Semantics.__default/WellFormedSchema
         dafny-objects
         relations
         permissions
         definitions
         dafny-relationships)]
    (if-not well-formed?
      {:status :invalid-schema
       :errors [:not-well-formed]}
      (authorization-outcome converted request (:limits input)))))

(defn- page-presence
  [value]
  (case value
    :absent
    (Presence/create_Absent TypeDescriptor/BIG_INTEGER)

    :nil
    (Presence/create_PresentNil TypeDescriptor/BIG_INTEGER)

    (Presence/create_PresentValue
     TypeDescriptor/BIG_INTEGER
     (dafny-nat value))))

(defn- page-error
  [^PageError error]
  (cond
    (.is_BothDirections error) :both-directions
    (.is_BothBounds error) :both-bounds
    (.is_AfterWithoutFirst error) :after-without-first
    (.is_BeforeWithoutLast error) :before-without-last
    (.is_NilAfter error) :nil-after
    (.is_NilBefore error) :nil-before
    (.is_NonPositiveSize error) :non-positive-size
    :else :oversized-page))

(defn- page-decision
  [{:keys [length request default-size maximum-size]}]
  (let [raw
        (RawPageRequest/create
         (page-presence (:first request))
         (page-presence (:last request))
         (page-presence (:after request))
         (page-presence (:before request)))
        ^Tuple2 result
        (PageWindow.__default/PaginateRelationshipItems
         TypeDescriptor/BIG_INTEGER
         (dafny-sequence (range length))
         raw
         (dafny-nat default-size)
         (dafny-nat maximum-size))
        ^NormalizedPageRequest normalized (.dtor__0 result)
        ^Page page (.dtor__1 result)]
    (if (.is_InvalidPageRequest normalized)
      {:status :invalid
       :reason (page-error (.dtor_error normalized))}
      {:status :valid
       :direction
       (if (.is_Ascending ^Direction (.dtor_direction normalized))
         :asc
         :desc)
       :size (dafny-long (.dtor_size normalized))
       :start (dafny-long (.dtor_start page))
       :end (dafny-long (.dtor_end page))
       :has-next? (.dtor_hasNext page)
       :has-previous? (.dtor_hasPrevious page)})))

(defn- keyset-page-decision
  [{:keys [direction size bound? realized-count]}]
  (let [decision
        (PageWindow.__default/DecideKeysetPage
         (if (= :asc direction)
           (Direction/create_Ascending)
           (Direction/create_Descending))
         (dafny-nat size)
         bound?
         (dafny-nat realized-count))]
    {:take-count (.longValue (.dtor_takeCount decision))
     :reverse? (.dtor_reverseItems decision)
     :has-next? (.dtor_hasNext decision)
     :has-previous? (.dtor_hasPrevious decision)}))

(defn- exact-selection
  [exact]
  (if exact
    (ExactSelection/create_ExactSnapshot
     (dafny-nat (:graph exact))
     (dafny-string (:source exact))
     (dafny-string (:proof exact)))
    (ExactSelection/create_ExactUnavailable)))

(defn- continuation-decision
  [{:keys [authenticated?
           scope-matches?
           expired?
           source
           cursor-source
           current-proof
           cursor-proof
           cursor-graph
           exact]}]
  (let [decision
        (PageWindow.__default/DecideContinuation
         authenticated?
         scope-matches?
         expired?
         (dafny-string source)
         (dafny-string cursor-source)
         (dafny-string current-proof)
         (dafny-string cursor-proof)
         (dafny-nat cursor-graph)
         (exact-selection exact))]
    (cond
      (.is_UseCurrent decision) :current
      (.is_UseExact decision) :exact
      (.is_InvalidAuthentication (.dtor_reason decision))
      :invalid-authentication
      (.is_ScopeMismatch (.dtor_reason decision)) :scope-mismatch
      (.is_CursorExpired (.dtor_reason decision)) :expired
      (.is_SnapshotUnavailable (.dtor_reason decision))
      :snapshot-unavailable
      :else :history-divergence)))

(defn- snapshot-consistency-mode
  [mode]
  (case mode
    :minimize-latency
    (SnapshotConsistencyMode/create_MinimizeLatency)

    :fully-consistent
    (SnapshotConsistencyMode/create_FullyConsistent)

    :at-least-as-fresh
    (SnapshotConsistencyMode/create_AtLeastAsFresh)

    :at-exact-snapshot
    (SnapshotConsistencyMode/create_AtExactSnapshot)))

(defn- consistency-error
  [^ConsistencyError error]
  (cond
    (.is_UnsupportedCapability error) :unsupported-capability
    (.is_UnsupportedHeadBarrier error) :unsupported-head-barrier
    (.is_ExactSnapshotUnavailable error) :exact-snapshot-unavailable
    (.is_InvalidSelectedAdapter error) :invalid-selected-adapter
    (.is_IncomparableScope error) :incomparable-scope
    :else :history-divergence))

(defn- consistency-plan-decision
  [{:keys [mode capability-supported?]}]
  (let [outcome
        (ConsistencyDecision.__default/DecideSelectionPlan
         (snapshot-consistency-mode mode)
         capability-supported?)]
    (if (.is_Planned outcome)
      (let [action (.dtor_action outcome)]
        (cond
          (.is_SelectCurrent action) :select-current
          (.is_SelectAuthoritative action) :select-authoritative
          (.is_AuthenticateAndSelectAtLeast action)
          :authenticate-and-select-at-least
          :else :authenticate-and-select-exact))
      (consistency-error (.dtor_error outcome)))))

(defn- consistency-selection-kind
  [kind]
  (case kind
    :current (SelectionKind/create_CurrentSelection)
    :authoritative (SelectionKind/create_AuthoritativeSelection)
    :at-least (SelectionKind/create_AtLeastSelection)
    :exact (SelectionKind/create_ExactSelection)))

(defn- consistency-selection-decision
  [{:keys [kind selection-present? selected-adapter?
           same-source-scope? revision-satisfied?]}]
  (let [outcome
        (ConsistencyDecision.__default/ValidateSelectedSnapshot
         (consistency-selection-kind kind)
         selection-present?
         selected-adapter?
         same-source-scope?
         revision-satisfied?)]
    (if (.is_SelectionAccepted outcome)
      :accept
      (consistency-error (.dtor_error outcome)))))

(defn- consistency-work-map
  [^SelectionWork work]
  {:capability-observations
   (.longValueExact (.dtor_capabilityObservations work))
   :plan-decisions
   (.longValueExact (.dtor_planDecisions work))
   :authentication-attempts
   (.longValueExact (.dtor_authenticationAttempts work))
   :backend-selection-calls
   (.longValueExact (.dtor_backendSelectionCalls work))
   :validation-decisions
   (.longValueExact (.dtor_validationDecisions work))
   :source-scope-reads
   (.longValueExact (.dtor_sourceScopeReads work))
   :revision-validation-calls
   (.longValueExact (.dtor_revisionValidationCalls work))
   :native-revision-reads
   (.longValueExact (.dtor_nativeRevisionReads work))
   :order-hint-reads
   (.longValueExact (.dtor_orderHintReads work))
   :exact-locator-reads
   (.longValueExact (.dtor_exactLocatorReads work))
   :source-lifecycle-reads
   (.longValueExact (.dtor_sourceLifecycleReads work))
   :snapshot-id-reads
   (.longValueExact (.dtor_snapshotIdReads work))
   :basis-kind-reads
   (.longValueExact (.dtor_basisKindReads work))})

(defn consistency-plan-work
  "Returns the generated Dafny logical-work vector for planning only."
  []
  (consistency-work-map
   (ConsistencyDecision.__default/SelectionPlanWork)))

(defn consistency-selection-work
  "Returns the generated Dafny logical-work vector for one successful path."
  [path issue-response-token?]
  (let [formal-path
        (case path
          :selected-current
          (SuccessfulSelectionPath/create_SelectedCurrentPath)
          :authoritative
          (SuccessfulSelectionPath/create_AuthoritativePath)
          :at-least
          (SuccessfulSelectionPath/create_AtLeastPath)
          :exact
          (SuccessfulSelectionPath/create_ExactPath))
        work
        (ConsistencyDecision.__default/SuccessfulSelectionWork
         formal-path
         issue-response-token?)]
    (consistency-work-map work)))

(defn- ordered-merge-head
  [value]
  (if (some? value)
    (OptionalHead/create_Head (dafny-nat value))
    (OptionalHead/create_NoHead)))

(defn- ordered-merge-decision
  [{:keys [direction left-head right-head]}]
  (let [direction'
        (case direction
          :asc (MergeDirection/create_Ascending)
          :desc (MergeDirection/create_Descending))
        step
        (OrderedMerge.__default/DecideMergeStep
         direction'
         (ordered-merge-head left-head)
         (ordered-merge-head right-head))]
    (cond
      (.is_LeftExhausted step) :left-exhausted
      (.is_RightExhausted step) :right-exhausted
      (.is_TakeLeft step) :take-left
      (.is_TakeRight step) :take-right
      :else :take-both)))

(defn- ordered-merge-chunk
  [{:keys [direction left right]}]
  (let [direction'
        (case direction
          :asc (MergeDirection/create_Ascending)
          :desc (MergeDirection/create_Descending))
        ^MergeChunk chunk
        (OrderedMerge.__default/DecideMergeChunk
         direction'
         (dafny-sequence left)
         (dafny-sequence right))]
    {:values (mapv dafny-long (.dtor_values chunk))
     :left-consumed (dafny-long (.dtor_leftConsumed chunk))
     :right-consumed (dafny-long (.dtor_rightConsumed chunk))}))

(defn production-ordered-merge
  "Executes the generated model of the production `has-last?` two-stream
  recursion. This is source-refinement test support, not a production SPI
  operation."
  [{:keys [direction left right]}]
  (let [^MergeDirection direction'
        (case direction
          :asc (MergeDirection/create_Ascending)
          :desc (MergeDirection/create_Descending))
        ^DafnySequence left' (dafny-sequence left)
        ^DafnySequence right' (dafny-sequence right)
        ^DafnySequence merged
        (OrderedMerge.__default/ExecuteProductionMerge
         direction'
         left'
         right')]
    (mapv dafny-long merged)))

(defn- optional-eid
  [value]
  (if (some? value)
    (OptionalEid/create_Bound (dafny-nat value))
    (OptionalEid/create_NoBound)))

(defn- indexed-projection
  [{:keys [kind subject-type subject-eid relation-eid
           resource-type resource-eid bound-eid]}]
  (case kind
    :subject->resources
    (Projection/create_SubjectToResources
     (dafny-string subject-type)
     (dafny-nat subject-eid)
     (dafny-nat relation-eid)
     (dafny-string resource-type)
     (optional-eid bound-eid))

    :resource->subjects
    (Projection/create_ResourceToSubjects
     (dafny-string resource-type)
     (dafny-nat resource-eid)
     (dafny-nat relation-eid)
     (dafny-string subject-type)
     (optional-eid bound-eid))))

(defn- indexed-scan-rejection-reason
  [^ScanError error]
  (cond
    (.is_InvalidCommand error) :invalid-command
    (.is_MismatchedRequestScope error) :mismatched-request-scope
    (.is_MismatchedRequest error) :mismatched-request
    (.is_OversizedChunk error) :oversized-chunk
    (.is_InvalidFetchedCount error) :invalid-fetched-count
    (.is_NonProgressingResponse error) :non-progressing-response
    (.is_InvalidEid error) :invalid-eid
    (.is_OutOfOrder error) :out-of-order
    :else :bound-violation))

(defn- indexed-page-continuation-reason
  [^PageContinuationError error]
  (cond
    (.is_InvalidContinuationSize error) :invalid-size
    (.is_ContinuationNotForwardPage error) :not-forward-page
    (.is_ContinuationNotComplete error) :not-complete
    (.is_ContinuationHasNoLookahead error) :no-lookahead
    (.is_ContinuationHasPendingScan error) :pending-scan
    :else :boundary-mismatch))

(defn- indexed-scan-decision
  [{:keys [command response]}]
  (let [command'
        (ScanCommand/create
         (dafny-nat (:request-scope command))
         (dafny-nat (:request-id command))
         (indexed-projection (:projection command))
         (dafny-nat (:chunk-size command)))
        response'
        (ScanResponse/create
         (dafny-nat (:request-scope response))
         (dafny-nat (:request-id response))
         (let [values (:values response)]
           (if (seq values)
             (dafny-sequence values)
             @empty-values-sequence))
         (:terminal? response)
         (dafny-nat (:fetched-values response)))
        decision
        (IndexedTraversal.__default/ValidateScanResponse
         command' response')]
    (if (.is_ScanAccepted decision)
      {:status :accepted
       :values (mapv dafny-long (.dtor_values decision))
       :terminal? (.dtor_terminal decision)
       :fetched-values (dafny-long (.dtor_fetchedValues decision))}
      {:status :rejected
       :reason
       (indexed-scan-rejection-reason (.dtor_error decision))})))

(defn- indexed-rule
  [{:keys [kind head relation-eid subject-type target-node
           via-relation-eid intermediate-type target-relation-eid
           target-subject-type]}]
  (let [head' (permission-node head)]
    (case kind
      :relation
      (IndexedRule/create_RelationRule
       head'
       (dafny-nat relation-eid)
       (dafny-string subject-type))

      :self-permission
      (IndexedRule/create_SelfPermissionRule
       head'
       (permission-node target-node))

      :arrow-relation
      (IndexedRule/create_ArrowRelationRule
       head'
       (dafny-nat via-relation-eid)
       (dafny-string intermediate-type)
       (dafny-nat target-relation-eid)
       (dafny-string target-subject-type))

      :arrow-permission
      (IndexedRule/create_ArrowPermissionRule
       head'
       (dafny-nat via-relation-eid)
       (dafny-string intermediate-type)
       (permission-node target-node)))))

(defn- relation-binding
  [{:keys [eid relation]}]
  (RelationBinding/create
   (dafny-nat eid)
   (relation-node relation)))

(defn- indexed-plan-rejection-reason
  [^PlanCertificationError error]
  (cond
    (.is_InvalidRelationCatalog error) :invalid-relation-catalog
    (.is_InvalidIndexedRule error) :invalid-indexed-rule
    (.is_DuplicateIndexedRule error) :duplicate-indexed-rule
    (.is_PermissionOpenRule error) :permission-open-rule
    (.is_CompiledRuleMismatch error) :compiled-rule-mismatch
    (.is_InvalidSeedRule error) :invalid-seed-rule
    (.is_DuplicateSeedRule error) :duplicate-seed-rule
    :else :seed-bucket-mismatch))

(defn- indexed-plan-decision
  [{:keys [relations permissions definitions relation-bindings
           indexed-rules]}]
  (let [decision
        (IndexedCertification.__default/CertifyIndexedRules
         (typed-sequence
          (RelationNode/_typeDescriptor)
          (mapv relation-node relations))
         (typed-sequence
          (PermissionNode/_typeDescriptor)
          (mapv permission-node permissions))
         (typed-sequence
          (RuleDefinition/_typeDescriptor)
          (mapv rule-definition definitions))
         (typed-sequence
          (RelationBinding/_typeDescriptor)
          (mapv relation-binding relation-bindings))
         (typed-sequence
          (IndexedRule/_typeDescriptor)
          (mapv indexed-rule indexed-rules)))]
    (if (.is_PlanCertified decision)
      {:status :certified}
      {:status :rejected
       :reason
       (indexed-plan-rejection-reason (.dtor_error decision))})))

(defn- indexed-seed-decision
  [{:keys [indexed-rules seed-rules subject-type]}]
  (let [decision
        (IndexedCertification.__default/CertifySeedBucket
         (typed-sequence
          (IndexedRule/_typeDescriptor)
          (mapv indexed-rule indexed-rules))
         (typed-sequence
          (IndexedRule/_typeDescriptor)
          (mapv indexed-rule seed-rules))
         (dafny-string subject-type))]
    (if (.is_PlanCertified decision)
      {:status :certified}
      {:status :rejected
       :reason
       (indexed-plan-rejection-reason (.dtor_error decision))})))

(defn- indexed-limits
  [{:keys [max-derived-grants max-advanced-datoms max-queued-work]
    :as limits}]
  (let [cached @limits-memo]
    (if (and cached (identical? (first cached) limits))
      (second cached)
      (let [built (IndexedLimits/create
                   (dafny-nat max-derived-grants)
                   (dafny-nat max-advanced-datoms)
                   (dafny-nat max-queued-work))]
        (vreset! limits-memo [limits built])
        built))))



(defn- indexed-render-mode
  [{:keys [kind size limit target-eid]}]
  (case kind
    :page
    (RenderMode/create_RenderPage (dafny-nat size))

    :count
    (RenderMode/create_RenderCount (dafny-nat limit))

    :all-count
    (RenderMode/create_RenderAllCount)

    :boolean
    (RenderMode/create_RenderBoolean (dafny-nat target-eid))))

(defn- indexed-limit-kind
  [^IndexedLimitKind kind]
  (cond
    (.is_IndexedDerivedGrants kind) :derived-grants
    (.is_IndexedAdvancedDatoms kind) :advanced-datoms
    :else :queued-work))

(defn- indexed-render-error
  [^RenderError error]
  (if (.is_CursorSkipped error)
    {:reason :cursor-skipped
     :expected-ordinal (dafny-long (.dtor_expectedOrdinal error))
     :actual-ordinal (dafny-long (.dtor_actualOrdinal error))}
    {:reason :cursor-result-mismatch
     :ordinal (dafny-long (.dtor_ordinal error))
     :expected-eid (dafny-long (.dtor_expectedEid error))
     :actual-eid (dafny-long (.dtor_actualEid error))}))

(defn- indexed-bound-value
  [^OptionalEid bound]
  (when (.is_Bound bound)
    (dafny-long (.dtor_value bound))))

(defn- indexed-projection-value
  [^Projection projection]
  (if (.is_SubjectToResources projection)
    {:kind :subject->resources
     :subject-type (dafny-unicode-interned (.dtor_subjectType projection))
     :subject-eid (dafny-long (.dtor_subjectEid projection))
     :relation-eid (dafny-long (.dtor_relationEid projection))
     :resource-type (dafny-unicode-interned (.dtor_resourceType projection))
     :bound-eid (indexed-bound-value (.dtor_bound projection))}
    {:kind :resource->subjects
     :resource-type (dafny-unicode-interned (.dtor_resourceType projection))
     :resource-eid (dafny-long (.dtor_resourceEid projection))
     :relation-eid (dafny-long (.dtor_relationEid projection))
     :subject-type (dafny-unicode-interned (.dtor_subjectType projection))
     :bound-eid (indexed-bound-value (.dtor_bound projection))}))

(defn- indexed-command-value
  [^ScanCommand command]
  {:request-scope (dafny-long (.dtor_requestScope command))
   :request-id (dafny-long (.dtor_requestId command))
   :projection (indexed-projection-value (.dtor_projection command))
   :chunk-size (dafny-long (.dtor_chunkSize command))})

(defn- indexed-counters-value
  [^ResourceCounters counters]
  {:backend-commands
   (dafny-long (.dtor_backendCommands counters))
   :adapter-fetched-values
   (dafny-long (.dtor_adapterFetchedValues counters))
   :engine-consumed-values
   (dafny-long (.dtor_engineConsumedValues counters))
   :cumulative-enqueues
   (dafny-long (.dtor_cumulativeEnqueues counters))
   :current-queue-depth
   (dafny-long (.dtor_currentQueueDepth counters))
   :maximum-queue-depth
   (dafny-long (.dtor_maximumQueueDepth counters))
   :unique-grants
   (dafny-long (.dtor_uniqueGrants counters))
   :emitted-results
   (dafny-long (.dtor_emittedResults counters))
   :rule-applications
   (dafny-long (.dtor_ruleApplications counters))
   :consumer-grant-joins
   (dafny-long (.dtor_consumerGrantJoins counters))
   :render-advances
   (dafny-long (.dtor_renderAdvances counters))})

(defrecord GeneratedJavaIndexedPlan [plan seed-rules])

(defn- compile-indexed-plan
  [{:keys [indexed-rules seed-rules-by-subject-type]}]
  (let [rules
        (typed-sequence
         (IndexedRule/_typeDescriptor)
         (mapv indexed-rule indexed-rules))]
    (->GeneratedJavaIndexedPlan
     (IndexedTraversal.__default/CompileTraversalPlan rules)
     (into
      {}
      (map
       (fn [[subject-type seed-rules]]
         [subject-type
          (typed-sequence
           (IndexedRule/_typeDescriptor)
           (mapv indexed-rule seed-rules))]))
      seed-rules-by-subject-type))))

(defn- indexed-init
  [direction
   {:keys [compiled-plan request-scope subject-type subject-eid
           root-node root-resource-eid result-type render chunk-size
           limits]}]
  (let [plan (:plan compiled-plan)
        limits' (indexed-limits limits)
        outcome
        (case direction
          :forward
          (IndexedTraversal.__default/InitializeForwardCompiled
           plan
           (get
            (:seed-rules compiled-plan)
            subject-type
           (typed-sequence
             (IndexedRule/_typeDescriptor) []))
           (dafny-nat request-scope)
           (dafny-string subject-type)
           (dafny-nat subject-eid)
           (permission-node root-node)
           (dafny-string result-type)
           (indexed-render-mode render)
           (dafny-nat chunk-size)
           limits')

          :reverse
          (IndexedTraversal.__default/InitializeReverseCompiled
           plan
           (dafny-nat request-scope)
           (dafny-string subject-type)
           (permission-node root-node)
           (dafny-nat root-resource-eid)
           (dafny-string result-type)
           (indexed-render-mode render)
           (dafny-nat chunk-size)
           limits'))]
    (if (case direction
          :forward (.is_ForwardInitialized ^ForwardInit outcome)
          :reverse (.is_ReverseInitialized ^ReverseInit outcome))
      {:status :initialized
       :state
       (case direction
         :forward (.dtor_state ^ForwardInit outcome)
         :reverse (.dtor_state ^ReverseInit outcome))}
      {:status :limit-exceeded
       :limit-kind
       (indexed-limit-kind
        (case direction
          :forward (.dtor_kind ^ForwardInit outcome)
          :reverse (.dtor_kind ^ReverseInit outcome)))})))

(defn- indexed-drive
  [direction state limits fuel]
  (let [outcome
        (case direction
          :forward
          (IndexedBatching.__default/DriveForwardScans
           ^ForwardState state
           (indexed-limits limits)
           (dafny-fuel fuel))

          :reverse
          (IndexedBatching.__default/DriveReverseScans
           ^ReverseState state
           (indexed-limits limits)
           (dafny-fuel fuel)))
        prefix
        (case direction
          :forward "Forward"
          :reverse "Reverse")]
    (cond
      (case direction
        :forward (.is_ForwardNeedScans ^ForwardBatchStep outcome)
        :reverse (.is_ReverseNeedScans ^ReverseBatchStep outcome))
      (let [commands
            (mapv
             indexed-command-value
             (case direction
               :forward (.dtor_commands ^ForwardBatchStep outcome)
               :reverse (.dtor_commands ^ReverseBatchStep outcome)))
            state
            (case direction
              :forward (.dtor_batch ^ForwardBatchStep outcome)
              :reverse (.dtor_batch ^ReverseBatchStep outcome))]
        (if (= 1 (count commands))
          {:status :need-scan
           :state state
           :command (first commands)}
          {:status :need-scans
           :state state
           :commands commands}))

      (case direction
        :forward (.is_ForwardBatchComplete ^ForwardBatchStep outcome)
        :reverse (.is_ReverseBatchComplete ^ReverseBatchStep outcome))
      {:status :complete
       :state
       (case direction
         :forward (.dtor_state ^ForwardBatchStep outcome)
         :reverse (.dtor_state ^ReverseBatchStep outcome))}

      (case direction
        :forward (.is_ForwardBatchYielded ^ForwardBatchStep outcome)
        :reverse (.is_ReverseBatchYielded ^ReverseBatchStep outcome))
      {:status :yielded
       :state
       (case direction
         :forward (.dtor_state ^ForwardBatchStep outcome)
         :reverse (.dtor_state ^ReverseBatchStep outcome))}

      (case direction
        :forward (.is_ForwardBatchRenderRejected ^ForwardBatchStep outcome)
        :reverse (.is_ReverseBatchRenderRejected ^ReverseBatchStep outcome))
      {:status :render-rejected
       :state
       (case direction
         :forward (.dtor_state ^ForwardBatchStep outcome)
         :reverse (.dtor_state ^ReverseBatchStep outcome))
       :error
       (indexed-render-error
        (case direction
          :forward (.dtor_error ^ForwardBatchStep outcome)
          :reverse (.dtor_error ^ReverseBatchStep outcome)))}

      (case direction
        :forward (.is_ForwardBatchLimitExceeded ^ForwardBatchStep outcome)
        :reverse (.is_ReverseBatchLimitExceeded ^ReverseBatchStep outcome))
      {:status :limit-exceeded
       :state
       (case direction
         :forward (.dtor_state ^ForwardBatchStep outcome)
         :reverse (.dtor_state ^ReverseBatchStep outcome))
       :limit-kind
       (indexed-limit-kind
        (case direction
          :forward (.dtor_kind ^ForwardBatchStep outcome)
          :reverse (.dtor_kind ^ReverseBatchStep outcome)))}

      :else
      (throw
       (ex-info
        "Generated indexed drive returned an internal-only step variant."
        {:direction direction
         :variant prefix})))))

(defn- indexed-response
  [response]
  (ScanResponse/create
   (dafny-nat (:request-scope response))
   (dafny-nat (:request-id response))
   (let [values (:values response)]
     (if (seq values)
       (dafny-sequence values)
       @empty-values-sequence))
   (:terminal? response)
   (dafny-nat (:fetched-values response))))

(defn- indexed-resume
  [direction state response limits]
  (let [batch-state?
        (case direction
          :forward (instance? ForwardBatchState state)
          :reverse (instance? ReverseBatchState state))
        batch? (or batch-state? (vector? response))
        responses (if (vector? response) response [response])
        response'
        (if batch?
          (typed-sequence
           (ScanResponse/_typeDescriptor)
           (mapv indexed-response responses))
          (indexed-response response))
        outcome
        (case direction
          :forward
          (if batch?
            (IndexedBatching.__default/ResumeForwardScans
             ^ForwardBatchState state
             ^DafnySequence response'
             (indexed-limits limits))
            (IndexedTraversal.__default/ResumeForwardScan
             ^ForwardState state
             ^ScanResponse response'
             (indexed-limits limits)))

          :reverse
          (if batch?
            (IndexedBatching.__default/ResumeReverseScans
             ^ReverseBatchState state
             ^DafnySequence response'
             (indexed-limits limits))
            (IndexedTraversal.__default/ResumeReverseScan
             ^ReverseState state
             ^ScanResponse response'
             (indexed-limits limits))))]
    (cond
      (case direction
        :forward (.is_ForwardScanResumed ^ForwardResume outcome)
        :reverse (.is_ReverseScanResumed ^ReverseResume outcome))
      {:status :resumed
       :state
       (case direction
         :forward (.dtor_state ^ForwardResume outcome)
         :reverse (.dtor_state ^ReverseResume outcome))}

      (case direction
        :forward (.is_ForwardScanRejected ^ForwardResume outcome)
        :reverse (.is_ReverseScanRejected ^ReverseResume outcome))
      {:status :scan-rejected
       :reason
       (indexed-scan-rejection-reason
        (case direction
          :forward (.dtor_error ^ForwardResume outcome)
          :reverse (.dtor_error ^ReverseResume outcome)))}

      :else
      {:status :limit-exceeded
       :state
       (case direction
         :forward (.dtor_state ^ForwardResume outcome)
         :reverse (.dtor_state ^ReverseResume outcome))
       :limit-kind
       (indexed-limit-kind
       (case direction
         :forward (.dtor_kind ^ForwardResume outcome)
         :reverse (.dtor_kind ^ReverseResume outcome)))})))

(defn- indexed-continue-page
  [direction state {:keys [size bound]}]
  (let [outcome
        (case direction
          :forward
          (IndexedTraversal.__default/ContinueForwardPage
           ^ForwardState state
           (dafny-nat size)
           (dafny-nat (:ordinal bound))
           (dafny-nat (:eid bound)))

          :reverse
          (IndexedTraversal.__default/ContinueReversePage
           ^ReverseState state
           (dafny-nat size)
           (dafny-nat (:ordinal bound))
           (dafny-nat (:eid bound))))]
    (if (case direction
          :forward
          (.is_ForwardPageContinued ^ForwardPageContinuation outcome)
          :reverse
          (.is_ReversePageContinued ^ReversePageContinuation outcome))
      {:status :continued
       :state
       (case direction
         :forward
         (.dtor_state ^ForwardPageContinuation outcome)
         :reverse
         (.dtor_state ^ReversePageContinuation outcome))}
      {:status :rejected
       :reason
       (indexed-page-continuation-reason
        (case direction
          :forward
          (.dtor_error ^ForwardPageContinuation outcome)
          :reverse
          (.dtor_error ^ReversePageContinuation outcome)))})))

(defn- indexed-public-result
  [direction state]
  (let [render
        (case direction
          :forward (.dtor_render ^ForwardState state)
          :reverse (.dtor_render ^ReverseState state))
        ^PublicRenderResult result
        (IndexedTraversal.__default/ReadRenderResult render)
        common
        {:counters
         (indexed-counters-value
          (case direction
            :forward (.dtor_counters ^ForwardState state)
            :reverse (.dtor_counters ^ReverseState state)))
         :retained-logical-units
         (dafny-long
          (case direction
            :forward
            (IndexedTraversal.__default/ForwardRetainedLogicalUnits
             ^ForwardState state)
            :reverse
            (IndexedTraversal.__default/ReverseRetainedLogicalUnits
             ^ReverseState state)))}]
    (cond
      (.is_PageReady result)
      (merge
       common
       {:status :page
        :items (mapv dafny-long (.dtor_items result))
        :start-ordinal
        (dafny-long (.dtor_startOrdinal result))
        :has-next? (.dtor_hasNext result)
        :has-previous? (.dtor_hasPrevious result)})

      (.is_CountReady result)
      (merge
       common
       {:status :count
        :count (dafny-long (.dtor_count result))
        :truncated? (.dtor_truncated result)})

      :else
      (merge
       common
       {:status :boolean
        :allowed? (.dtor_allowed result)}))))

(defrecord GeneratedJavaKernel []
  verified/DecisionKernel
  (-decide [_ operation input]
    (case operation
      :relationship-page (page-decision input)
      :relationship-keyset-page (keyset-page-decision input)
      :cursor-continuation (continuation-decision input)
      :consistency-plan (consistency-plan-decision input)
      :consistency-validation
      (consistency-selection-decision input)
      :ordered-merge-step (ordered-merge-decision input)
      :ordered-merge-chunk (ordered-merge-chunk input)
      :recursive-routing-certificate
      (routing-certificate-decision input)
      :enumeration-route (enumeration-route-decision input)
      :acyclic-page (acyclic-page-decision input)
      :acyclic-continuation
      (acyclic-continuation-decision input)
      :acyclic-count (acyclic-count-decision input)
      :acyclic-work (acyclic-work-decision input)
      :indexed-scan-response (indexed-scan-decision input)
      :indexed-plan-certification (indexed-plan-decision input)
      :indexed-seed-certification (indexed-seed-decision input)
      :authorization-evaluation (authorization-decision input)))

  verified/IndexedTraversalKernel
  (-compile-indexed-plan [_ input]
    (compile-indexed-plan input))
  (-initialize-indexed [_ direction input]
    (indexed-init direction input))
  (-drive-indexed [_ direction state limits fuel]
    (indexed-drive direction state limits fuel))
  (-resume-indexed [_ direction state response limits]
    (indexed-resume direction state response limits))
  (-continue-indexed-page [_ direction state input]
    (indexed-continue-page direction state input))
  (-read-indexed-result [_ direction state]
    (indexed-public-result direction state)))

(def generated-java-kernel
  (->GeneratedJavaKernel))

(def default-selection
  {:kernel generated-java-kernel})

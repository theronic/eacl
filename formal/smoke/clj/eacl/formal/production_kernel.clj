(ns eacl.formal.production-kernel
  "Generated-Java implementation of EACL's strict production decision SPI."
  (:require [eacl.verified-kernel :as verified])
  (:import
   (CacheKernel CacheCandidate ProofState Telemetry)
   (ConsistencyDecision
    ConsistencyError
    SelectionKind
    SnapshotConsistencyMode
    SuccessfulSelectionPath)
   (CurrentCache CurrentCacheStage)
   (dafny DafnySequence DafnySet Tuple2 Tuple4 TypeDescriptor)
   (IndexedCertification PlanCertificationError)
   (IndexedRefinement RelationBinding)
   (IndexedTraversal
    CursorBound
    ForwardInit
    ForwardPageContinuation
    ForwardResume
    ForwardState
    ForwardStep
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
    ReverseStep
    ScanError
    ScanCommand
    ScanResponse)
   (OrderedMerge MergeChunk MergeDirection OptionalHead)
   (PageWindow
    ConsistencyMode
    ExactSelection
    NormalizedPageRequest
    Page
    PageError
    Presence
    RawPageRequest)
   (Pagination Direction)
   (RecursiveEngine
    BooleanOutcome
    CountOutcome
    LimitKind
    PermissionDependencyEdge
    SequenceOutcome
    TraversalLimits
    WorkCounters)
   (RoutingCertificate
    IndexedDependencyEdge
    RoutingCertificateCounters
    RoutingCertificateDecision
    RoutingCertificateError
    RoutingProof)
   (SubproblemCache CandidateState)
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

(defn- permission-dependency-edge
  [{:keys [head target]}]
  (PermissionDependencyEdge/create
   (permission-node head)
   (permission-node target)))

(defn typed-traversal-permission?
  "Runs the generated exact SCC-routing oracle over fully typed dependency
  edges. This is a semantic oracle; its closure scans are not a resource model
  of production's iterative O(V+E) Kosaraju implementation."
  [{:keys [root edges permissions]}]
  (RecursiveEngine.__default/DecideTypedTraversalPermission
   (permission-node root)
   (typed-sequence
    (PermissionDependencyEdge/_typeDescriptor)
    (mapv permission-dependency-edge edges))
   (typed-sequence
    (PermissionNode/_typeDescriptor)
    (mapv permission-node permissions))))

(defn- indexed-routing-edge
  [{:keys [head target]}]
  (IndexedDependencyEdge/create
   (dafny-nat head)
   (dafny-nat target)))

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
    :else :invalid-component-witness))

(defn- routing-certificate-decision
  [{:keys [node-count edges certificate]}]
  (let [^RoutingCertificateDecision decision
        (RoutingCertificate.__default/CheckRoutingCertificate
         (dafny-nat node-count)
         (typed-sequence
          (IndexedDependencyEdge/_typeDescriptor)
          (mapv indexed-routing-edge edges))
         (routing-proof certificate))
        ^RoutingCertificateCounters counters
        (.dtor_counters decision)
        work
        {:node-checks (dafny-long (.dtor_nodeChecks counters))
         :edge-checks (dafny-long (.dtor_edgeChecks counters))}]
    (if (.is_RoutingCertificateAccepted decision)
      (merge
       {:status :accepted
        :traversal (mapv boolean (.dtor_traversal decision))}
       work)
      (merge
       {:status :rejected
        :reason
        (routing-certificate-error (.dtor_error decision))}
       work))))

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
    (.is_LegacyPagination error) :legacy-pagination
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
         (page-presence (:before request))
         (:has-legacy-limit? request)
         (:has-legacy-cursor? request))
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
           mode
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
         (if (= :exact-snapshot mode)
           (ConsistencyMode/create_ExactSnapshotMode)
           (ConsistencyMode/create_RecoverCurrent))
         (dafny-nat cursor-graph)
         (exact-selection exact))]
    (cond
      (.is_UseCurrent decision) :current
      (.is_RebaseCurrent decision) :rebase-current
      (.is_UseExact decision) :exact
      (.is_InvalidAuthentication (.dtor_reason decision))
      :invalid-authentication
      (.is_ScopeMismatch (.dtor_reason decision)) :scope-mismatch
      (.is_CursorExpired (.dtor_reason decision)) :expired
      (.is_CursorConflict (.dtor_reason decision)) :conflict
      (.is_SnapshotUnavailable (.dtor_reason decision))
      :snapshot-unavailable
      :else :history-divergence)))

(defn- snapshot-consistency-mode
  [mode]
  (case mode
    :local-snapshot
    (SnapshotConsistencyMode/create_LocalSnapshot)

    :minimize-latency
    (SnapshotConsistencyMode/create_MinimizeLatency)

    :fully-consistent
    (SnapshotConsistencyMode/create_FullyConsistent)

    :synchronized-head
    (SnapshotConsistencyMode/create_SynchronizedHead)

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
  [{:keys [mode capability-supported? managed-authority?]}]
  (let [outcome
        (ConsistencyDecision.__default/DecideSelectionPlan
         (snapshot-consistency-mode mode)
         capability-supported?
         managed-authority?)]
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
           same-source-scope? anchor-satisfied?]}]
  (let [outcome
        (ConsistencyDecision.__default/ValidateSelectedSnapshot
         (consistency-selection-kind kind)
         selection-present?
         selected-adapter?
         same-source-scope?
         anchor-satisfied?)]
    (if (.is_SelectionAccepted outcome)
      :accept
      (consistency-error (.dtor_error outcome)))))

(defn consistency-selection-work
  "Returns the generated Dafny logical-work vector for one successful path."
  [path issue-response-token?]
  (let [formal-path
        (case path
          :captured-current
          (SuccessfulSelectionPath/create_CapturedCurrentPath)
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
     :contains-anchor-calls
     (.longValueExact (.dtor_containsAnchorCalls work))
     :graph-head-reads
     (.longValueExact (.dtor_graphHeadReads work))
     :order-hint-reads
     (.longValueExact (.dtor_orderHintReads work))
     :exact-locator-reads
     (.longValueExact (.dtor_exactLocatorReads work))}))

(defn- proof-state
  [proof]
  (if (some? proof)
    (ProofState/create_CompleteProof (dafny-string proof))
    (ProofState/create_ProofUnavailable)))

(defn- cache-candidate
  [{:keys [status authenticated? key source graph proof]}]
  (case status
    :missing
    (CacheCandidate/create_NoCandidate TypeDescriptor/BIG_INTEGER)

    :provider-failure
    (CacheCandidate/create_ProviderFailed TypeDescriptor/BIG_INTEGER)

    (CacheCandidate/create_Candidate
     TypeDescriptor/BIG_INTEGER
     authenticated?
     (dafny-string key)
     (dafny-string source)
     (dafny-nat graph)
     (proof-state proof)
     (dafny-nat 0)
     (Telemetry/create (dafny-nat graph) (dafny-nat 0)))))

(defn- cache-decision
  [{:keys [deterministic?
           dependency-scope-nonempty?
           expected-key
           expected-source
           selected-graph
           ancestors
           selected-proof
           entry]}]
  (let [decision
        (CacheKernel.__default/ValidateCache
         TypeDescriptor/BIG_INTEGER
         deterministic?
         dependency-scope-nonempty?
         (dafny-string expected-key)
         (dafny-string expected-source)
         (dafny-nat selected-graph)
         (DafnySet.
          ^java.util.Collection
          (mapv dafny-nat ancestors))
         (proof-state selected-proof)
         (cache-candidate entry))]
    (if (.is_CacheHit decision)
      {:status :hit
       :provenance
       (if (.is_ExactHit (.dtor_provenance decision))
         :exact-hit
         :causal-proof-lift)}
      (let [reason (.dtor_reason decision)]
        {:status :miss
         :reason
         (cond
           (.is_Missing reason) :missing
           (.is_ProviderFailure reason) :provider-failure
           (.is_NoProofBypass reason) :no-proof-bypass
           (.is_Unauthenticated reason) :unauthenticated
           (.is_ScopeMismatch reason) :scope-mismatch
           (.is_FutureOrSibling reason) :future-or-sibling
           :else :proof-mismatch)}))))

(defn- candidate-state
  [candidate]
  (case candidate
    :missing (CandidateState/create_CandidateMissing)
    :computing (CandidateState/create_CandidateComputing)
    :complete (CandidateState/create_CandidateComplete)
    :failed (CandidateState/create_CandidateFailed)))

(defn- subproblem-cache-decision
  [{:keys [decision] :as input}]
  (case decision
    :lookup
    (let [action
          (SubproblemCache.__default/DecideLookup
           (:recursive-self? input)
           (candidate-state (:candidate input)))]
      (cond
        (.is_BypassRecursiveSelf action) :bypass-recursive-self
        (.is_StartComputation action) :start-computation
        (.is_JoinComputation action) :join-computation
        :else :use-completed-value))

    :admission
    (let [action
          (SubproblemCache.__default/DecideAdmission
           (:candidate-present? input)
           (dafny-nat (:represented-candidates input))
           (dafny-nat (:maximum-candidates input)))]
      (cond
        (.is_JoinExisting action) :join-existing
        (.is_AdmitComputation action) :admit-computation
        :else :compute-without-admission))

    :publication
    (let [action
          (SubproblemCache.__default/DecidePublication
           (:ticket-current? input)
           (:complete? input)
           (:valid? input)
           (dafny-nat (:weight input))
           (dafny-nat (:budget input)))]
      (if (.is_RetainPublication action)
        :retain-publication
        :drop-publication))))

(defn- current-cache-stage
  [stage]
  (case stage
    :eligibility
    (CurrentCacheStage/create_EligibilityStage)

    :generation
    (CurrentCacheStage/create_GenerationStage)

    :exact-entry
    (CurrentCacheStage/create_ExactEntryStage)

    :managed-entry
    (CurrentCacheStage/create_ManagedEntryStage)))

(defn- current-cache-decision
  [{:keys [stage available?]}]
  (let [action
        (CurrentCache.__default/DecideCurrentCache
         (current-cache-stage stage)
         available?)]
    (cond
      (.is_BypassCurrentCache action) :bypass-current-cache
      (.is_ProbeExactEntry action) :probe-exact-entry
      (.is_UseExactEntry action) :use-exact-entry
      (.is_ProbeManagedEntry action) :probe-managed-entry
      (.is_UseManagedEntry action) :use-managed-entry
      :else :compute-current-value)))

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

(defn acyclic-leapfrog-intersection
  "Executes the generated bounded leapfrog oracle for source-specialization
  tests. This is test-support code, not part of EACL's production kernel SPI."
  [{:keys [left right]}]
  (let [^Tuple4 result
        (AcyclicEngine.__default/LeapfrogSortedEidsIntersectWithWork
         (dafny-sequence left)
         (dafny-sequence right))]
    {:intersects? (.dtor__0 result)
     :iterations (dafny-long (.dtor__1 result))
     :reseek-calls (dafny-long (.dtor__2 result))
     :examined-heads (dafny-long (.dtor__3 result))}))

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
         (dafny-sequence (:values response))
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
  [{:keys [max-derived-grants max-advanced-datoms max-queued-work]}]
  (IndexedLimits/create
   (dafny-nat max-derived-grants)
   (dafny-nat max-advanced-datoms)
   (dafny-nat max-queued-work)))

(defn- indexed-cursor-bound
  [bound]
  (if bound
    (CursorBound/create_AfterCursor
     (dafny-nat (:ordinal bound))
     (dafny-nat (:eid bound)))
    (CursorBound/create_NoCursorBound)))

(defn- indexed-render-mode
  [{:keys [kind size bound limit target-eid]}]
  (case kind
    :page
    (RenderMode/create_RenderPage
     (dafny-nat size)
     (indexed-cursor-bound bound))

    :backward-page
    (RenderMode/create_RenderBackwardPage
     (dafny-nat size)
     (indexed-cursor-bound bound))

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
     :subject-type (dafny-unicode (.dtor_subjectType projection))
     :subject-eid (dafny-long (.dtor_subjectEid projection))
     :relation-eid (dafny-long (.dtor_relationEid projection))
     :resource-type (dafny-unicode (.dtor_resourceType projection))
     :bound-eid (indexed-bound-value (.dtor_bound projection))}
    {:kind :resource->subjects
     :resource-type (dafny-unicode (.dtor_resourceType projection))
     :resource-eid (dafny-long (.dtor_resourceEid projection))
     :relation-eid (dafny-long (.dtor_relationEid projection))
     :subject-type (dafny-unicode (.dtor_subjectType projection))
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
          (IndexedTraversal.__default/DriveForwardIterative
           state (indexed-limits limits) (dafny-nat fuel))

          :reverse
          (IndexedTraversal.__default/DriveReverseIterative
           state (indexed-limits limits) (dafny-nat fuel)))
        prefix
        (case direction
          :forward "Forward"
          :reverse "Reverse")]
    (cond
      (case direction
        :forward (.is_ForwardNeedScan ^ForwardStep outcome)
        :reverse (.is_ReverseNeedScan ^ReverseStep outcome))
      {:status :need-scan
       :state
       (case direction
         :forward (.dtor_state ^ForwardStep outcome)
         :reverse (.dtor_state ^ReverseStep outcome))
       :command
       (indexed-command-value
        (case direction
          :forward (.dtor_command ^ForwardStep outcome)
          :reverse (.dtor_command ^ReverseStep outcome)))}

      (case direction
        :forward (.is_ForwardComplete ^ForwardStep outcome)
        :reverse (.is_ReverseComplete ^ReverseStep outcome))
      {:status :complete
       :state
       (case direction
         :forward (.dtor_state ^ForwardStep outcome)
         :reverse (.dtor_state ^ReverseStep outcome))}

      (case direction
        :forward (.is_ForwardYielded ^ForwardStep outcome)
        :reverse (.is_ReverseYielded ^ReverseStep outcome))
      {:status :yielded
       :state
       (case direction
         :forward (.dtor_state ^ForwardStep outcome)
         :reverse (.dtor_state ^ReverseStep outcome))}

      (case direction
        :forward (.is_ForwardRenderRejected ^ForwardStep outcome)
        :reverse (.is_ReverseRenderRejected ^ReverseStep outcome))
      {:status :render-rejected
       :state
       (case direction
         :forward (.dtor_state ^ForwardStep outcome)
         :reverse (.dtor_state ^ReverseStep outcome))
       :error
       (indexed-render-error
        (case direction
          :forward (.dtor_error ^ForwardStep outcome)
          :reverse (.dtor_error ^ReverseStep outcome)))}

      (case direction
        :forward (.is_ForwardStepLimitExceeded ^ForwardStep outcome)
        :reverse (.is_ReverseStepLimitExceeded ^ReverseStep outcome))
      {:status :limit-exceeded
       :state
       (case direction
         :forward (.dtor_state ^ForwardStep outcome)
         :reverse (.dtor_state ^ReverseStep outcome))
       :limit-kind
       (indexed-limit-kind
        (case direction
          :forward (.dtor_kind ^ForwardStep outcome)
          :reverse (.dtor_kind ^ReverseStep outcome)))}

      :else
      (throw
       (ex-info
        "Generated indexed drive returned an internal-only step variant."
        {:direction direction
         :variant prefix})))))

(defn- indexed-resume
  [direction state response limits]
  (let [response'
        (ScanResponse/create
         (dafny-nat (:request-scope response))
         (dafny-nat (:request-id response))
         (dafny-sequence (:values response))
         (:terminal? response)
         (dafny-nat (:fetched-values response)))
        outcome
        (case direction
          :forward
          (IndexedTraversal.__default/ResumeForwardScan
           state response' (indexed-limits limits))

          :reverse
          (IndexedTraversal.__default/ResumeReverseScan
           state response' (indexed-limits limits)))]
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
      :cache-validation (cache-decision input)
      :current-cache-decision (current-cache-decision input)
      :subproblem-cache-decision
      (subproblem-cache-decision input)
      :ordered-merge-step (ordered-merge-decision input)
      :ordered-merge-chunk (ordered-merge-chunk input)
      :recursive-routing-certificate
      (routing-certificate-decision input)
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

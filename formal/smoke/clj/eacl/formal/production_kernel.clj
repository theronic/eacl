(ns eacl.formal.production-kernel
  "Generated-Java implementation of EACL's strict production decision SPI."
  (:require [eacl.verified-kernel :as verified])
  (:import
   (CacheKernel CacheCandidate ProofState Telemetry)
   (CurrentCache CurrentCacheStage)
   (dafny DafnySequence DafnySet TypeDescriptor)
   (IndexedRefinement RelationBinding)
   (IndexedTraversal
    CursorBound
    IndexedLimits
    IndexedRule
    OptionalEid
    Projection
    RenderMode
    ScanCommand
    ScanResponse)
   (OrderedMerge MergeDirection OptionalHead)
   (PageWindow
    ConsistencyMode
    ExactSelection
    Presence
    RawPageRequest)
   (Pagination Direction)
   (RecursiveEngine PermissionDependencyEdge TraversalLimits)
   (SubproblemCache CandidateState)
   (Semantics
    ObjectRef
    PermissionNode
    RelationNode
    Relationship
    RuleDefinition)))

(defn- dafny-string
  [value]
  (DafnySequence/asUnicodeString value))

(defn- dafny-nat
  [value]
  (biginteger value))

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
  [object]
  {:type (.verbatimString (.dtor_typeName object))
   :id (.verbatimString (.dtor_objectId object))})

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
  [kind]
  (cond
    (.is_DerivedGrants kind) :derived-grants
    (.is_AdvancedDatoms kind) :advanced-datoms
    :else :queued-work))

(defn- work-counters
  [counters]
  {:derived-grants
   (.longValue (.dtor_derivedGrants counters))
   :advanced-datoms
   (.longValue (.dtor_advancedDatoms counters))
   :queued-work
   (.longValue (.dtor_queuedWork counters))})

(defn- sequence-outcome
  [operation outcome]
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
  [operation outcome]
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
  [operation outcome]
  (if (.is_CountComplete outcome)
    {:status :complete
     :operation operation
     :count (.longValue (.dtor_count outcome))
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
  [error]
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
        result
        (PageWindow.__default/PaginateRelationshipItems
         TypeDescriptor/BIG_INTEGER
         (dafny-sequence (range length))
         raw
         (dafny-nat default-size)
         (dafny-nat maximum-size))
        normalized (.dtor__0 result)
        page (.dtor__1 result)]
    (if (.is_InvalidPageRequest normalized)
      {:status :invalid
       :reason (page-error (.dtor_error normalized))}
      {:status :valid
       :direction
       (if (.is_Ascending (.dtor_direction normalized))
         :asc
         :desc)
       :size (.longValue (.dtor_size normalized))
       :start (.longValue (.dtor_start page))
       :end (.longValue (.dtor_end page))
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
         (DafnySet. (mapv dafny-nat ancestors))
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
        chunk
        (OrderedMerge.__default/DecideMergeChunk
         direction'
         (dafny-sequence left)
         (dafny-sequence right))]
    {:values (mapv #(.longValueExact %) (.dtor_values chunk))
     :left-consumed (.longValueExact (.dtor_leftConsumed chunk))
     :right-consumed (.longValueExact (.dtor_rightConsumed chunk))}))

(defn acyclic-leapfrog-intersection
  "Executes the generated bounded leapfrog oracle for source-specialization
  tests. This is test-support code, not part of EACL's production kernel SPI."
  [{:keys [left right]}]
  (let [result
        (AcyclicEngine.__default/LeapfrogSortedEidsIntersectWithWork
         (dafny-sequence left)
         (dafny-sequence right))]
    {:intersects? (.dtor__0 result)
     :iterations (.longValueExact (.dtor__1 result))
     :reseek-calls (.longValueExact (.dtor__2 result))
     :examined-heads (.longValueExact (.dtor__3 result))}))

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
  [error]
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
       :values (mapv #(.longValue %) (.dtor_values decision))
       :terminal? (.dtor_terminal decision)
       :fetched-values (.longValue (.dtor_fetchedValues decision))}
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
  [error]
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
  [kind]
  (cond
    (.is_IndexedDerivedGrants kind) :derived-grants
    (.is_IndexedAdvancedDatoms kind) :advanced-datoms
    :else :queued-work))

(defn- indexed-render-error
  [error]
  (if (.is_CursorSkipped error)
    {:reason :cursor-skipped
     :expected-ordinal (.longValueExact (.dtor_expectedOrdinal error))
     :actual-ordinal (.longValueExact (.dtor_actualOrdinal error))}
    {:reason :cursor-result-mismatch
     :ordinal (.longValueExact (.dtor_ordinal error))
     :expected-eid (.longValueExact (.dtor_expectedEid error))
     :actual-eid (.longValueExact (.dtor_actualEid error))}))

(defn- indexed-bound-value
  [bound]
  (when (.is_Bound bound)
    (.longValueExact (.dtor_value bound))))

(defn- indexed-projection-value
  [projection]
  (if (.is_SubjectToResources projection)
    {:kind :subject->resources
     :subject-type (.verbatimString (.dtor_subjectType projection))
     :subject-eid (.longValueExact (.dtor_subjectEid projection))
     :relation-eid (.longValueExact (.dtor_relationEid projection))
     :resource-type (.verbatimString (.dtor_resourceType projection))
     :bound-eid (indexed-bound-value (.dtor_bound projection))}
    {:kind :resource->subjects
     :resource-type (.verbatimString (.dtor_resourceType projection))
     :resource-eid (.longValueExact (.dtor_resourceEid projection))
     :relation-eid (.longValueExact (.dtor_relationEid projection))
     :subject-type (.verbatimString (.dtor_subjectType projection))
     :bound-eid (indexed-bound-value (.dtor_bound projection))}))

(defn- indexed-command-value
  [command]
  {:request-scope (.longValueExact (.dtor_requestScope command))
   :request-id (.longValueExact (.dtor_requestId command))
   :projection (indexed-projection-value (.dtor_projection command))
   :chunk-size (.longValueExact (.dtor_chunkSize command))})

(defn- indexed-counters-value
  [counters]
  {:backend-commands
   (.longValueExact (.dtor_backendCommands counters))
   :adapter-fetched-values
   (.longValueExact (.dtor_adapterFetchedValues counters))
   :engine-consumed-values
   (.longValueExact (.dtor_engineConsumedValues counters))
   :cumulative-enqueues
   (.longValueExact (.dtor_cumulativeEnqueues counters))
   :current-queue-depth
   (.longValueExact (.dtor_currentQueueDepth counters))
   :maximum-queue-depth
   (.longValueExact (.dtor_maximumQueueDepth counters))
   :unique-grants
   (.longValueExact (.dtor_uniqueGrants counters))
   :emitted-results
   (.longValueExact (.dtor_emittedResults counters))
   :rule-applications
   (.longValueExact (.dtor_ruleApplications counters))
   :consumer-grant-joins
   (.longValueExact (.dtor_consumerGrantJoins counters))
   :render-advances
   (.longValueExact (.dtor_renderAdvances counters))})

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
          :forward (.is_ForwardInitialized outcome)
          :reverse (.is_ReverseInitialized outcome))
      {:status :initialized
       :state (.dtor_state outcome)}
      {:status :limit-exceeded
       :limit-kind (indexed-limit-kind (.dtor_kind outcome))})))

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
        :forward (.is_ForwardNeedScan outcome)
        :reverse (.is_ReverseNeedScan outcome))
      {:status :need-scan
       :state (.dtor_state outcome)
       :command (indexed-command-value (.dtor_command outcome))}

      (case direction
        :forward (.is_ForwardComplete outcome)
        :reverse (.is_ReverseComplete outcome))
      {:status :complete
       :state (.dtor_state outcome)}

      (case direction
        :forward (.is_ForwardYielded outcome)
        :reverse (.is_ReverseYielded outcome))
      {:status :yielded
       :state (.dtor_state outcome)}

      (case direction
        :forward (.is_ForwardRenderRejected outcome)
        :reverse (.is_ReverseRenderRejected outcome))
      {:status :render-rejected
       :state (.dtor_state outcome)
       :error (indexed-render-error (.dtor_error outcome))}

      (case direction
        :forward (.is_ForwardStepLimitExceeded outcome)
        :reverse (.is_ReverseStepLimitExceeded outcome))
      {:status :limit-exceeded
       :state (.dtor_state outcome)
       :limit-kind (indexed-limit-kind (.dtor_kind outcome))}

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
        :forward (.is_ForwardScanResumed outcome)
        :reverse (.is_ReverseScanResumed outcome))
      {:status :resumed
       :state (.dtor_state outcome)}

      (case direction
        :forward (.is_ForwardScanRejected outcome)
        :reverse (.is_ReverseScanRejected outcome))
      {:status :scan-rejected
       :reason
       (indexed-scan-rejection-reason (.dtor_error outcome))}

      :else
      {:status :limit-exceeded
       :state (.dtor_state outcome)
       :limit-kind (indexed-limit-kind (.dtor_kind outcome))})))

(defn- indexed-public-result
  [direction state]
  (let [render (.dtor_render state)
        result (IndexedTraversal.__default/ReadRenderResult render)
        common
        {:counters (indexed-counters-value (.dtor_counters state))
         :retained-logical-units
         (.longValueExact
          (case direction
            :forward
            (IndexedTraversal.__default/ForwardRetainedLogicalUnits state)
            :reverse
            (IndexedTraversal.__default/ReverseRetainedLogicalUnits state)))}]
    (cond
      (.is_PageReady result)
      (merge
       common
       {:status :page
        :items (mapv #(.longValueExact %) (.dtor_items result))
        :start-ordinal
        (.longValueExact (.dtor_startOrdinal result))
        :has-next? (.dtor_hasNext result)
        :has-previous? (.dtor_hasPrevious result)})

      (.is_CountReady result)
      (merge
       common
       {:status :count
        :count (.longValueExact (.dtor_count result))
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
      :cache-validation (cache-decision input)
      :current-cache-decision (current-cache-decision input)
      :subproblem-cache-decision
      (subproblem-cache-decision input)
      :ordered-merge-step (ordered-merge-decision input)
      :ordered-merge-chunk (ordered-merge-chunk input)
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
  (-read-indexed-result [_ direction state]
    (indexed-public-result direction state)))

(def generated-java-kernel
  (->GeneratedJavaKernel))

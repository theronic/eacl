(ns eacl.formal.production-kernel
  "Generated-Java implementation of EACL's strict production decision SPI."
  (:require [eacl.verified-kernel :as verified])
  (:import
   (CacheKernel CacheCandidate ProofState Telemetry)
   (dafny DafnySequence DafnySet TypeDescriptor)
   (PageWindow
    ConsistencyMode
    ExactSelection
    Presence
    RawPageRequest)
   (RecursiveEngine TraversalLimits)
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

(defrecord GeneratedJavaKernel []
  verified/DecisionKernel
  (-decide [_ operation input]
    (case operation
      :relationship-page (page-decision input)
      :cursor-continuation (continuation-decision input)
      :cache-validation (cache-decision input)
      :authorization-evaluation (authorization-decision input))))

(def generated-java-kernel
  (->GeneratedJavaKernel))

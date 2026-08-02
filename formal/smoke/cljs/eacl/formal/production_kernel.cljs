(ns eacl.formal.production-kernel
  "Generated-JavaScript implementation of EACL's production decision SPI."
  (:require
   [eacl.verified-kernel :as verified]))

(def generated
  (js/require
   (.resolve
    (js/require "path")
    (.cwd js/process)
    "formal/smoke/js/generated_loader.cjs")))

(defn- dafny-string
  [value]
  (.UnicodeFromString
   (.-Seq (.-_dafny generated))
   value))

(defn- big-number
  [value]
  (new (.-BigNumber generated) value))

(defn- dafny-sequence
  [values]
  (.apply
   (.-of (.-Seq (.-_dafny generated)))
   (.-Seq (.-_dafny generated))
   (into-array values)))

(defn- object->dafny
  [{:keys [type id]}]
  (js-invoke
   (.-ObjectRef (.-Semantics generated))
   "create_ObjectRef"
   (dafny-string type)
   (dafny-string id)))

(defn- dafny-object->object
  [object]
  {:type (.toVerbatimString (.-dtor_typeName object) false)
   :id (.toVerbatimString (.-dtor_objectId object) false)})

(defn- permission-node
  [{:keys [resource-type permission]}]
  (js-invoke
   (.-PermissionNode (.-Semantics generated))
   "create_PermissionNode"
   (dafny-string resource-type)
   (dafny-string permission)))

(defn- relation-node
  [{:keys [resource-type relation subject-type]}]
  (js-invoke
   (.-RelationNode (.-Semantics generated))
   "create_RelationNode"
   (dafny-string resource-type)
   (dafny-string relation)
   (dafny-string subject-type)))

(defn- rule-definition
  [{:keys [kind resource-type permission relation subject-type
           target-permission via-relation target-relation]}]
  (let [rules (.-RuleDefinition (.-Semantics generated))
        head
        (permission-node
         {:resource-type resource-type
          :permission permission})]
    (case kind
      :direct-relation
      (js-invoke
       rules "create_DirectRelation"
       head (dafny-string relation) (dafny-string subject-type))

      :self-permission
      (js-invoke
       rules "create_SelfPermission"
       head (dafny-string target-permission))

      :arrow-relation
      (js-invoke
       rules "create_ArrowRelation"
       head
       (dafny-string via-relation)
       (dafny-string target-relation)
       (dafny-string subject-type))

      :arrow-permission
      (js-invoke
       rules "create_ArrowPermission"
       head
       (dafny-string via-relation)
       (dafny-string target-permission)))))

(defn- relationship->dafny
  [{:keys [resource relation subject]}]
  (js-invoke
   (.-Relationship (.-Semantics generated))
   "create_Relationship"
   (object->dafny resource)
   (dafny-string relation)
   (object->dafny subject)))

(defn- authorization-inputs
  [{:keys [objects schema relationships]}]
  {:objects (dafny-sequence (map object->dafny objects))
   :relations
   (dafny-sequence (map relation-node (:relations schema)))
   :permissions
   (dafny-sequence (map permission-node (:permissions schema)))
   :definitions
   (dafny-sequence (map rule-definition (:definitions schema)))
   :relationships
   (dafny-sequence (map relationship->dafny relationships))})

(defn- traversal-limits
  [{:keys [max-derived-grants
           max-advanced-datoms
           max-queued-work]}]
  (js-invoke
   (.-TraversalLimits (.-RecursiveEngine generated))
   "create_TraversalLimits"
   (big-number max-derived-grants)
   (big-number max-advanced-datoms)
   (big-number max-queued-work)))

(defn- limit-kind
  [kind]
  (cond
    (.-is_DerivedGrants kind) :derived-grants
    (.-is_AdvancedDatoms kind) :advanced-datoms
    :else :queued-work))

(defn- work-counters
  [counters]
  {:derived-grants
   (.toNumber (.-dtor_derivedGrants counters))
   :advanced-datoms
   (.toNumber (.-dtor_advancedDatoms counters))
   :queued-work
   (.toNumber (.-dtor_queuedWork counters))})

(defn- sequence-outcome
  [operation outcome]
  (if (.-is_SequenceComplete outcome)
    {:status :complete
     :operation operation
     :items (mapv dafny-object->object (.-dtor_items outcome))
     :counters (work-counters (.-dtor_counters outcome))}
    {:status :limit-exceeded
     :operation operation
     :limit-kind (limit-kind (.-dtor_kind outcome))
     :counters (work-counters (.-dtor_counters outcome))}))

(defn- boolean-outcome
  [operation outcome]
  (if (.-is_BooleanComplete outcome)
    {:status :complete
     :operation operation
     :allowed? (.-dtor_allowed outcome)
     :counters (work-counters (.-dtor_counters outcome))}
    {:status :limit-exceeded
     :operation operation
     :limit-kind (limit-kind (.-dtor_kind outcome))
     :counters (work-counters (.-dtor_counters outcome))}))

(defn- count-outcome
  [operation outcome]
  (if (.-is_CountComplete outcome)
    {:status :complete
     :operation operation
     :count (.toNumber (.-dtor_count outcome))
     :truncated? (.-dtor_truncated outcome)
     :counters (work-counters (.-dtor_counters outcome))}
    {:status :limit-exceeded
     :operation operation
     :limit-kind (limit-kind (.-dtor_kind outcome))
     :counters (work-counters (.-dtor_counters outcome))}))

(defn- authorization-outcome
  [{:keys [objects permissions definitions relationships]}
   request traversal-limit-values]
  (let [node
        (permission-node
         {:resource-type
          (or (:resource-type request)
              (:type (:resource request)))
          :permission (:permission request)})
        recursive (.-RecursiveEngine generated)
        kernel (.-__default recursive)
        limits (traversal-limits traversal-limit-values)]
    (case (:operation request)
      :can?
      (boolean-outcome
       :can?
       (js-invoke
        kernel
        "RecursiveCan"
        objects permissions definitions relationships
        (object->dafny (:subject request))
        node
        (object->dafny (:resource request))
        limits))

      :lookup-resources
      (sequence-outcome
       :lookup-resources
       (js-invoke
        kernel
        "RecursiveForward"
        objects permissions definitions relationships
        (object->dafny (:subject request))
        node
        limits))

      :lookup-subjects
      (sequence-outcome
       :lookup-subjects
       (js-invoke
        kernel
        "RecursiveReverseTyped"
        objects permissions definitions relationships
        (object->dafny (:resource request))
        node
        (dafny-string (:subject-type request))
        limits))

      :count-resources
      (count-outcome
       :count-resources
       (js-invoke
        kernel
        "RecursiveCountForward"
        objects permissions definitions relationships
        (object->dafny (:subject request))
        node
        (big-number (:count-limit request))
        limits))

      :count-subjects
      (count-outcome
       :count-subjects
       (js-invoke
        kernel
        "RecursiveCountReverseTyped"
        objects permissions definitions relationships
        (object->dafny (:resource request))
        node
        (dafny-string (:subject-type request))
        (big-number (:count-limit request))
        limits)))))

(defn- authorization-decision
  [{:keys [request] :as input}]
  (let [{:keys [objects relations permissions definitions relationships]
         :as converted}
        (authorization-inputs input)
        well-formed?
        (js-invoke
         (.-__default (.-Semantics generated))
         "WellFormedSchema"
         objects relations permissions definitions relationships)]
    (if-not well-formed?
      {:status :invalid-schema
       :errors [:not-well-formed]}
      (authorization-outcome converted request (:limits input)))))

(defn- page-presence
  [value]
  (let [presence (.-Presence (.-PageWindow generated))]
    (case value
      :absent
      (js-invoke presence "create_Absent")

      :nil
      (js-invoke presence "create_PresentNil")

      (js-invoke
       presence "create_PresentValue" (big-number value)))))

(defn- page-error
  [error]
  (cond
    (.-is_LegacyPagination error) :legacy-pagination
    (.-is_BothDirections error) :both-directions
    (.-is_BothBounds error) :both-bounds
    (.-is_AfterWithoutFirst error) :after-without-first
    (.-is_BeforeWithoutLast error) :before-without-last
    (.-is_NilAfter error) :nil-after
    (.-is_NilBefore error) :nil-before
    (.-is_NonPositiveSize error) :non-positive-size
    :else :oversized-page))

(defn- page-decision
  [{:keys [length request default-size maximum-size]}]
  (let [page-window (.-PageWindow generated)
        raw
        (js-invoke
         (.-RawPageRequest page-window)
         "create_RawPageRequest"
         (page-presence (:first request))
         (page-presence (:last request))
         (page-presence (:after request))
         (page-presence (:before request))
         (:has-legacy-limit? request)
         (:has-legacy-cursor? request))
        result
        (js-invoke
         (.-__default page-window)
         "PaginateRelationshipItems"
         (dafny-sequence
          (map #(big-number %) (range length)))
         raw
         (big-number default-size)
         (big-number maximum-size))
        normalized (aget result 0)
        page (aget result 1)]
    (if (.-is_InvalidPageRequest normalized)
      {:status :invalid
       :reason (page-error (.-dtor_error normalized))}
      {:status :valid
       :direction
       (if (.-is_Ascending (.-dtor_direction normalized))
         :asc
         :desc)
       :size (.toNumber (.-dtor_size normalized))
       :start (.toNumber (.-dtor_start page))
       :end (.toNumber (.-dtor_end page))
       :has-next? (.-dtor_hasNext page)
       :has-previous? (.-dtor_hasPrevious page)})))

(defn- exact-selection
  [exact]
  (let [page-window (.-PageWindow generated)]
    (if exact
      (js-invoke
       (.-ExactSelection page-window)
       "create_ExactSnapshot"
       (big-number (:graph exact))
       (dafny-string (:source exact))
       (dafny-string (:proof exact)))
      (js-invoke
       (.-ExactSelection page-window)
       "create_ExactUnavailable"))))

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
  (let [page-window (.-PageWindow generated)
        consistency-mode
        (if (= :at-least-as-fresh mode)
          (js-invoke
           (.-ConsistencyMode page-window)
           "create_AtLeastAsFresh")
          (js-invoke
           (.-ConsistencyMode page-window)
           "create_MinimizeLatency"))
        decision
        (js-invoke
         (.-__default page-window)
         "DecideContinuation"
         authenticated?
         scope-matches?
         expired?
         (dafny-string source)
         (dafny-string cursor-source)
         (dafny-string current-proof)
         (dafny-string cursor-proof)
         consistency-mode
         (big-number cursor-graph)
         (exact-selection exact))]
    (cond
      (.-is_UseCurrent decision) :current
      (.-is_UseExact decision) :exact
      (.-is_InvalidAuthentication (.-dtor_reason decision))
      :invalid-authentication
      (.-is_ScopeMismatch (.-dtor_reason decision)) :scope-mismatch
      (.-is_CursorExpired (.-dtor_reason decision)) :expired
      (.-is_CursorConflict (.-dtor_reason decision)) :conflict
      (.-is_SnapshotUnavailable (.-dtor_reason decision))
      :snapshot-unavailable
      :else :history-divergence)))

(defn- proof-state
  [cache proof]
  (if (some? proof)
    (js-invoke
     (.-ProofState cache)
     "create_CompleteProof"
     (dafny-string proof))
    (js-invoke
     (.-ProofState cache)
     "create_ProofUnavailable")))

(defn- cache-candidate
  [cache {:keys [status authenticated? key source graph proof]}]
  (case status
    :missing
    (js-invoke (.-CacheCandidate cache) "create_NoCandidate")

    :provider-failure
    (js-invoke (.-CacheCandidate cache) "create_ProviderFailed")

    (js-invoke
     (.-CacheCandidate cache)
     "create_Candidate"
     authenticated?
     (dafny-string key)
     (dafny-string source)
     (big-number graph)
     (proof-state cache proof)
     (big-number 0)
     (js-invoke
      (.-Telemetry cache)
      "create_Telemetry"
      (big-number graph)
      (big-number 0)))))

(defn- dafny-set
  [values]
  (.apply
   (.-fromElements (.-Set (.-_dafny generated)))
   (.-Set (.-_dafny generated))
   (into-array (map big-number values))))

(defn- cache-decision
  [{:keys [deterministic?
           dependency-scope-nonempty?
           expected-key
           expected-source
           selected-graph
           ancestors
           selected-proof
           entry]}]
  (let [cache (.-CacheKernel generated)
        decision
        (js-invoke
         (.-__default cache)
         "ValidateCache"
         deterministic?
         dependency-scope-nonempty?
         (dafny-string expected-key)
         (dafny-string expected-source)
         (big-number selected-graph)
         (dafny-set ancestors)
         (proof-state cache selected-proof)
         (cache-candidate cache entry))]
    (if (.-is_CacheHit decision)
      {:status :hit
       :provenance
       (if (.-is_ExactHit (.-dtor_provenance decision))
         :exact-hit
         :causal-proof-lift)}
      (let [reason (.-dtor_reason decision)]
        {:status :miss
         :reason
         (cond
           (.-is_Missing reason) :missing
           (.-is_ProviderFailure reason) :provider-failure
           (.-is_NoProofBypass reason) :no-proof-bypass
           (.-is_Unauthenticated reason) :unauthenticated
           (.-is_ScopeMismatch reason) :scope-mismatch
           (.-is_FutureOrSibling reason) :future-or-sibling
           :else :proof-mismatch)}))))

(defrecord GeneratedJavaScriptKernel []
  verified/DecisionKernel
  (-decide [_ operation input]
    (case operation
      :relationship-page (page-decision input)
      :cursor-continuation (continuation-decision input)
      :cache-validation (cache-decision input)
      :authorization-evaluation (authorization-decision input))))

(def generated-javascript-kernel
  (->GeneratedJavaScriptKernel))

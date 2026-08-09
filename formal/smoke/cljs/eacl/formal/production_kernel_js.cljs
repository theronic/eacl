(ns eacl.formal.production-kernel-js
  "Formal-only generated-JavaScript oracle for EACL's decision SPI."
  (:require
   [eacl.formal.generated-runtime]
   [eacl.verified-kernel :as verified]))

(def generated
  (.-EaclFormal js/globalThis))

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

(def ^:private limits-memo
  ;; Single-slot identity memo mirroring the JVM adapter: limits are one
  ;; map value per request; the sequential protocol re-marshalled three
  ;; BigNumbers on every drive and resume.
  (volatile! nil))

(defn- indexed-limits
  [{:keys [max-derived-grants max-advanced-datoms max-queued-work]
    :as limits}]
  (let [cached @limits-memo]
    (if (and cached (identical? (first cached) limits))
      (second cached)
      (let [built (js-invoke
                   (.-IndexedLimits (.-IndexedTraversal generated))
                   "create_IndexedLimits"
                   (big-number max-derived-grants)
                   (big-number max-advanced-datoms)
                   (big-number max-queued-work))]
        (vreset! limits-memo [limits built])
        built))))

(def ^:private fuel-memo (volatile! nil))

(defn- dafny-fuel
  [fuel]
  (let [cached @fuel-memo]
    (if (and cached (= (first cached) fuel))
      (second cached)
      (let [built (big-number fuel)]
        (vreset! fuel-memo [fuel built])
        built))))

(def ^:private empty-values-sequence
  ;; Interned empty scan-response payload (JVM mirror): ~98% of
  ;; populated-recursion scan responses are emptiness probes.
  (delay (dafny-sequence [])))

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

(defn- permission-dependency-edge
  [{:keys [head target]}]
  (js-invoke
   (.-PermissionDependencyEdge (.-RecursiveEngine generated))
   "create_PermissionDependencyEdge"
   (permission-node head)
   (permission-node target)))

(defn typed-traversal-permission?
  "Runs the generated exact SCC-routing oracle over fully typed dependency
  edges. This is a semantic oracle; its closure scans are not a resource model
  of production's iterative O(V+E) Kosaraju implementation."
  [{:keys [root edges permissions]}]
  (js-invoke
   (.-__default (.-RecursiveEngine generated))
   "DecideTypedTraversalPermission"
   (permission-node root)
   (dafny-sequence (map permission-dependency-edge edges))
   (dafny-sequence (map permission-node permissions))))

(defn- indexed-routing-edge
  [{:keys [head target]}]
  (js-invoke
   (.-IndexedDependencyEdge (.-RoutingCertificate generated))
   "create_IndexedDependencyEdge"
   (big-number head)
   (big-number target)))

(defn- indexed-routing-path
  [{:keys [kind head target]}]
  (let [routing-path (.-IndexedRoutingPath (.-RoutingCertificate generated))]
    (case kind
      :relation
      (js-invoke
       routing-path
       "create_IndexedDirectRelation"
       (big-number head))

      :self-permission
      (js-invoke
       routing-path
       "create_IndexedSelfPermission"
       (big-number head)
       (big-number target))

      :arrow-relation
      (js-invoke
       routing-path
       "create_IndexedArrowRelation"
       (big-number head))

      :arrow-permission
      (js-invoke
       routing-path
       "create_IndexedArrowPermission"
       (big-number head)
       (big-number target)))))

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
  (js-invoke
   (.-RoutingProof (.-RoutingCertificate generated))
   "create_RoutingProof"
   (dafny-sequence (map big-number component-root))
   (dafny-sequence (map big-number forward-parent-edge))
   (dafny-sequence (map big-number reverse-parent-edge))
   (dafny-sequence (map big-number forward-depth))
   (dafny-sequence (map big-number reverse-depth))
   (dafny-sequence (map big-number component-rank))
   (dafny-sequence (map big-number multiple-member-witness))
   (dafny-sequence (map big-number self-loop-witness-edge))
   (dafny-sequence traversal)
   (dafny-sequence (map big-number traversal-witness-edge))))

(defn- routing-certificate-error
  [error]
  (cond
    (.-is_ShapeMismatch error) :shape-mismatch
    (.-is_InvalidComponent error) :invalid-component
    (.-is_InvalidDependencyEdge error) :invalid-dependency-edge
    (.-is_InvalidComponentWitness error) :invalid-component-witness
    (.-is_InvalidRoutingPath error) :invalid-routing-path
    (.-is_RoutingPathEdgeMismatch error) :routing-path-edge-mismatch
    :else
    (throw
     (ex-info
      "Unknown generated routing certificate error."
      {:error error}))))

(defn- routing-certificate-decision
  [{:keys [node-count path-descriptors edges certificate]}]
  (let [routing (.-RoutingCertificate generated)
        decision
        (js-invoke
         (.-__default routing)
         "CheckRoutingCertificateFromPaths"
         (big-number node-count)
         (dafny-sequence (map indexed-routing-path path-descriptors))
         (dafny-sequence (map indexed-routing-edge edges))
         (routing-proof certificate))
        counters (.-dtor_counters decision)
        work
        {:path-checks (.toNumber (.-dtor_pathChecks counters))
         :node-checks (.toNumber (.-dtor_nodeChecks counters))
         :edge-checks (.toNumber (.-dtor_edgeChecks counters))}]
    (if (.-is_RoutingDerivationAccepted decision)
      (merge
       {:status :accepted
        :traversal (mapv boolean (.-dtor_traversal decision))}
       work)
      (merge
       {:status :rejected
        :reason
        (routing-certificate-error (.-dtor_error decision))}
       work))))

(defn- enumeration-route-decision
  [{:keys [schema-identity certificate-schema-identity
           root-defined? recursive? recursive-data-active?]}]
  (let [routing (.-RoutingCertificate generated)
        decision
        (js-invoke
         (.-__default routing)
         "SelectEnumerationRoute"
         (dafny-string schema-identity)
         (dafny-string certificate-schema-identity)
         root-defined?
         recursive?
         recursive-data-active?)]
    (if (.-is_EnumerationRouteAccepted decision)
      (let [route (.-dtor_route decision)]
        {:status :accepted
         :route
         (cond
           (.-is_UndefinedEnumerationRoute route) :undefined
           (.-is_CertifiedAcyclicEnumerationRoute route) :acyclic
           :else :recursive)})
      (let [error (.-dtor_error decision)]
        {:status :rejected
         :reason
         (if (.-is_MissingSchemaIdentity error)
           :missing-schema-identity
           :schema-identity-mismatch)}))))

(defn- acyclic-direction
  [direction]
  (let [acyclic (.-AcyclicEngine generated)]
    (case direction
      :asc
      (js-invoke
       (.-AcyclicDirection acyclic)
       "create_AcyclicAscending")

      :desc
      (js-invoke
       (.-AcyclicDirection acyclic)
       "create_AcyclicDescending"))))

(defn- acyclic-page-decision
  [{:keys [direction realized-eids size bound?]}]
  (let [acyclic (.-AcyclicEngine generated)
        decision
        (js-invoke
         (.-__default acyclic)
         "DecideAcyclicPage"
         (acyclic-direction direction)
         (dafny-sequence (map big-number realized-eids))
         (big-number size)
         bound?)
        work (.-dtor_work decision)]
    {:take-count (.toNumber (.-dtor_takeCount decision))
     :reverse? (.-dtor_reverseOutput decision)
     :has-next? (.-dtor_hasNext decision)
     :has-previous? (.-dtor_hasPrevious decision)
     :merge-advances (.toNumber (.-dtor_mergeAdvances work))
     :emitted-results (.toNumber (.-dtor_emittedResults work))
     :recursive-work (.toNumber (.-dtor_recursiveWork work))}))

(defn- acyclic-continuation-decision
  [{:keys [authenticated? schema-matches? query-matches?
           snapshot-matches? entry-present? entry-valid?]}]
  (let [acyclic (.-AcyclicEngine generated)
        action
        (js-invoke
         (.-__default acyclic)
         "DecideAcyclicContinuation"
         authenticated?
         schema-matches?
         query-matches?
         snapshot-matches?
         entry-present?
         entry-valid?)]
    (cond
      (.-is_ResumePrivateContinuation action) :resume
      (.-is_ReplayAuthenticatedBoundary action) :replay
      :else :reject)))

(defn- acyclic-count-decision
  [{:keys [unique-count more? limit]}]
  (let [acyclic (.-AcyclicEngine generated)
        decision
        (js-invoke
         (.-__default acyclic)
         "DecideAcyclicCount"
         (big-number unique-count)
         more?
         (some? limit)
         (big-number (or limit 0)))]
    {:count (.toNumber (.-dtor_count decision))
     :truncated? (.-dtor_truncated decision)
     :recursive-work (.toNumber (.-dtor_recursiveWork decision))}))

(defn- acyclic-work-decision
  [{:keys [requested-window merge-advances
           emitted-results recursive-work]}]
  (let [acyclic (.-AcyclicEngine generated)
        decision
        (js-invoke
         (.-__default acyclic)
         "CertifyAcyclicWork"
         (big-number requested-window)
         (big-number merge-advances)
         (big-number emitted-results)
         (big-number recursive-work))]
    (if (.-is_AcyclicWorkAccepted decision)
      :accepted
      :rejected)))

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
         (page-presence (:before request)))
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

(defn- keyset-page-decision
  [{:keys [direction size bound? realized-count]}]
  (let [page-window (.-PageWindow generated)
        direction-value
        (if (= :asc direction)
          (js-invoke
           (.-Direction page-window) "create_Ascending")
          (js-invoke
           (.-Direction page-window) "create_Descending"))
        decision
        (js-invoke
         (.-__default page-window)
         "DecideKeysetPage"
         direction-value
         (big-number size)
         bound?
         (big-number realized-count))]
    {:take-count (.toNumber (.-dtor_takeCount decision))
     :reverse? (.-dtor_reverseItems decision)
     :has-next? (.-dtor_hasNext decision)
     :has-previous? (.-dtor_hasPrevious decision)}))

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
           cursor-graph
           exact]}]
  (let [page-window (.-PageWindow generated)
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

(defn- snapshot-consistency-mode
  [consistency mode]
  (case mode
    :minimize-latency
    (js-invoke
     (.-SnapshotConsistencyMode consistency)
     "create_MinimizeLatency")

    :fully-consistent
    (js-invoke
     (.-SnapshotConsistencyMode consistency)
     "create_FullyConsistent")

    :at-least-as-fresh
    (js-invoke
     (.-SnapshotConsistencyMode consistency)
     "create_AtLeastAsFresh")

    :at-exact-snapshot
    (js-invoke
     (.-SnapshotConsistencyMode consistency)
     "create_AtExactSnapshot")))

(defn- consistency-error
  [error]
  (cond
    (.-is_UnsupportedCapability error) :unsupported-capability
    (.-is_UnsupportedHeadBarrier error) :unsupported-head-barrier
    (.-is_ExactSnapshotUnavailable error) :exact-snapshot-unavailable
    (.-is_InvalidSelectedAdapter error) :invalid-selected-adapter
    (.-is_IncomparableScope error) :incomparable-scope
    :else :history-divergence))

(defn- consistency-plan-decision
  [{:keys [mode capability-supported? managed-authority?]}]
  (let [consistency (.-ConsistencyDecision generated)
        outcome
        (js-invoke
         (.-__default consistency)
         "DecideSelectionPlan"
         (snapshot-consistency-mode consistency mode)
         capability-supported?
         managed-authority?)]
    (if (.-is_Planned outcome)
      (let [action (.-dtor_action outcome)]
        (cond
          (.-is_SelectCurrent action) :select-current
          (.-is_SelectAuthoritative action) :select-authoritative
          (.-is_AuthenticateAndSelectAtLeast action)
          :authenticate-and-select-at-least
          :else :authenticate-and-select-exact))
      (consistency-error (.-dtor_error outcome)))))

(defn- consistency-selection-kind
  [consistency kind]
  (case kind
    :current
    (js-invoke
     (.-SelectionKind consistency)
     "create_CurrentSelection")

    :authoritative
    (js-invoke
     (.-SelectionKind consistency)
     "create_AuthoritativeSelection")

    :at-least
    (js-invoke
     (.-SelectionKind consistency)
     "create_AtLeastSelection")

    :exact
    (js-invoke
     (.-SelectionKind consistency)
     "create_ExactSelection")))

(defn- consistency-selection-decision
  [{:keys [kind selection-present? selected-adapter?
           same-source-scope? anchor-satisfied?]}]
  (let [consistency (.-ConsistencyDecision generated)
        outcome
        (js-invoke
         (.-__default consistency)
         "ValidateSelectedSnapshot"
         (consistency-selection-kind consistency kind)
         selection-present?
         selected-adapter?
         same-source-scope?
         anchor-satisfied?)]
    (if (.-is_SelectionAccepted outcome)
      :accept
      (consistency-error (.-dtor_error outcome)))))

(defn consistency-selection-work
  "Returns the generated Dafny logical-work vector for one successful path."
  [path issue-response-token?]
  (let [consistency (.-ConsistencyDecision generated)
        paths (.-SuccessfulSelectionPath consistency)
        formal-path
        (case path
          :captured-current
          (js-invoke paths "create_CapturedCurrentPath")
          :selected-current
          (js-invoke paths "create_SelectedCurrentPath")
          :authoritative
          (js-invoke paths "create_AuthoritativePath")
          :at-least
          (js-invoke paths "create_AtLeastPath")
          :exact
          (js-invoke paths "create_ExactPath"))
        work
        (js-invoke
         (.-__default consistency)
         "SuccessfulSelectionWork"
         formal-path
         issue-response-token?)]
    {:capability-observations
     (.toNumber (.-dtor_capabilityObservations work))
     :plan-decisions
     (.toNumber (.-dtor_planDecisions work))
     :authentication-attempts
     (.toNumber (.-dtor_authenticationAttempts work))
     :backend-selection-calls
     (.toNumber (.-dtor_backendSelectionCalls work))
     :validation-decisions
     (.toNumber (.-dtor_validationDecisions work))
     :source-scope-reads
     (.toNumber (.-dtor_sourceScopeReads work))
     :contains-anchor-calls
     (.toNumber (.-dtor_containsAnchorCalls work))
     :graph-head-reads
     (.toNumber (.-dtor_graphHeadReads work))
     :order-hint-reads
     (.toNumber (.-dtor_orderHintReads work))
     :exact-locator-reads
     (.toNumber (.-dtor_exactLocatorReads work))}))

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

(defn- candidate-state
  [subproblem candidate]
  (case candidate
    :missing
    (js-invoke (.-CandidateState subproblem) "create_CandidateMissing")

    :computing
    (js-invoke (.-CandidateState subproblem) "create_CandidateComputing")

    :complete
    (js-invoke (.-CandidateState subproblem) "create_CandidateComplete")

    :failed
    (js-invoke (.-CandidateState subproblem) "create_CandidateFailed")))

(defn- subproblem-cache-decision
  [{:keys [decision] :as input}]
  (let [subproblem (.-SubproblemCache generated)]
    (case decision
      :lookup
      (let [action
            (js-invoke
             (.-__default subproblem)
             "DecideLookup"
             (candidate-state subproblem (:candidate input)))]
        (if (.-is_StartIndependentComputation action)
          :start-independent-computation
          :use-completed-value))

      :admission
      (let [action
            (js-invoke
             (.-__default subproblem)
             "DecideAdmission"
             (:candidate-present? input)
             (big-number (:attempted-publications input))
             (big-number (:maximum-attempts input)))]
        (if (.-is_AttemptPublication action)
          :attempt-publication
          :skip-publication))

      :publication
      (let [action
            (js-invoke
             (.-__default subproblem)
             "DecidePublication"
             (:ticket-current? input)
             (:complete? input)
             (:valid? input)
             (big-number (:weight input))
             (big-number (:budget input)))]
        (if (.-is_RetainPublication action)
          :retain-publication
          :drop-publication)))))

(defn- current-cache-stage
  [current stage]
  (let [stages (.-CurrentCacheStage current)]
    (case stage
      :eligibility
      (js-invoke stages "create_EligibilityStage")

      :generation
      (js-invoke stages "create_GenerationStage")

      :exact-entry
      (js-invoke stages "create_ExactEntryStage")

      :managed-entry
      (js-invoke stages "create_ManagedEntryStage"))))

(defn- current-cache-decision
  [{:keys [stage available?]}]
  (let [current (.-CurrentCache generated)
        action
        (js-invoke
         (.-__default current)
         "DecideCurrentCache"
         (current-cache-stage current stage)
         available?)]
    (cond
      (.-is_BypassCurrentCache action) :bypass-current-cache
      (.-is_ProbeExactEntry action) :probe-exact-entry
      (.-is_UseExactEntry action) :use-exact-entry
      (.-is_ProbeManagedEntry action) :probe-managed-entry
      (.-is_UseManagedEntry action) :use-managed-entry
      :else :compute-current-value)))

(defn- ordered-merge-head
  [value]
  (let [ordered-merge (.-OrderedMerge generated)]
    (if (some? value)
      (js-invoke
       (.-OptionalHead ordered-merge)
       "create_Head"
       (big-number value))
      (js-invoke
       (.-OptionalHead ordered-merge)
       "create_NoHead"))))

(defn- ordered-merge-decision
  [{:keys [direction left-head right-head]}]
  (let [ordered-merge (.-OrderedMerge generated)
        direction'
        (case direction
          :asc
          (js-invoke
           (.-MergeDirection ordered-merge)
           "create_Ascending")
          :desc
          (js-invoke
           (.-MergeDirection ordered-merge)
           "create_Descending"))
        step
        (js-invoke
         (.-__default ordered-merge)
         "DecideMergeStep"
         direction'
         (ordered-merge-head left-head)
         (ordered-merge-head right-head))]
    (cond
      (.-is_LeftExhausted step) :left-exhausted
      (.-is_RightExhausted step) :right-exhausted
      (.-is_TakeLeft step) :take-left
      (.-is_TakeRight step) :take-right
      :else :take-both)))

(defn- ordered-merge-chunk
  [{:keys [direction left right]}]
  (let [ordered-merge (.-OrderedMerge generated)
        direction'
        (case direction
          :asc
          (js-invoke
           (.-MergeDirection ordered-merge)
           "create_Ascending")
          :desc
          (js-invoke
           (.-MergeDirection ordered-merge)
           "create_Descending"))
        chunk
        (js-invoke
         (.-__default ordered-merge)
         "DecideMergeChunk"
         direction'
         (dafny-sequence (mapv big-number left))
         (dafny-sequence (mapv big-number right)))]
    {:values (mapv #(.toNumber %) (.-dtor_values chunk))
     :left-consumed (.toNumber (.-dtor_leftConsumed chunk))
     :right-consumed (.toNumber (.-dtor_rightConsumed chunk))}))

(defn production-ordered-merge
  "Executes the generated model of the production `has-last?` two-stream
  recursion. This is source-refinement test support, not a production SPI
  operation."
  [{:keys [direction left right]}]
  (let [ordered-merge (.-OrderedMerge generated)
        direction'
        (case direction
          :asc
          (js-invoke
           (.-MergeDirection ordered-merge)
           "create_Ascending")
          :desc
          (js-invoke
           (.-MergeDirection ordered-merge)
           "create_Descending"))
        merged
        (js-invoke
         (.-__default ordered-merge)
         "ExecuteProductionMerge"
         direction'
         (dafny-sequence (mapv big-number left))
         (dafny-sequence (mapv big-number right)))]
    (mapv #(.toNumber %) merged)))

(defn production-ordered-fold
  "Executes the generated model of production's non-empty filtering and
  pairwise balanced-fold schedule. This is source-refinement test support."
  [{:keys [direction streams]}]
  (let [ordered-merge (.-OrderedMerge generated)
        direction'
        (case direction
          :asc
          (js-invoke
           (.-MergeDirection ordered-merge)
           "create_Ascending")
          :desc
          (js-invoke
           (.-MergeDirection ordered-merge)
           "create_Descending"))
        streams'
        (dafny-sequence
         (mapv
          #(dafny-sequence (mapv big-number %))
          streams))
        merged
        (js-invoke
         (.-__default ordered-merge)
         "ExecuteProductionFold"
         direction'
         streams')]
    (mapv #(.toNumber %) merged)))

(defn acyclic-leapfrog-intersection
  "Executes the generated bounded leapfrog oracle for source-specialization
  tests. This is test-support code, not part of EACL's production kernel SPI."
  [{:keys [left right]}]
  (let [acyclic (.-AcyclicEngine generated)
        result
        (js-invoke
         (.-__default acyclic)
         "LeapfrogSortedEidsIntersectWithWork"
         (dafny-sequence (mapv big-number left))
         (dafny-sequence (mapv big-number right)))]
    {:intersects? (aget result 0)
     :iterations (.toNumber (aget result 1))
     :reseek-calls (.toNumber (aget result 2))
     :examined-heads (.toNumber (aget result 3))
     :reseek-trace
     (mapv
      (fn [event]
        (mapv #(.toNumber %) event))
      (aget result 4))}))

(defn acyclic-arrow-path-decision
  "Executes the generated source-shaped acyclic arrow fast-path decision.
  This is test-support code, not part of EACL's production kernel SPI."
  [{:keys [full-candidate-matches direct-intersects? exhaustive?]}]
  (let [acyclic (.-AcyclicEngine generated)
        result
        (js-invoke
         (.-__default acyclic)
         "AcyclicArrowPathDecisionWithWork"
         (dafny-sequence full-candidate-matches)
         direct-intersects?
         exhaustive?)]
    {:allowed? (aget result 0)
     :direct-intersection-phases (.toNumber (aget result 1))
     :full-candidate-checks (.toNumber (aget result 2))}))

(defn acyclic-path-fold
  "Executes the generated source-shaped outer acyclic path fold. This is
  formal test support, not a public EACL API."
  [{:keys [visited? kinds direct-subject-type-matches path-results]}]
  (let [acyclic (.-AcyclicEngine generated)
        result
        (js-invoke
         (.-__default acyclic)
         "AcyclicPathFoldWithTrace"
         visited?
         (dafny-sequence (map big-number kinds))
         (dafny-sequence direct-subject-type-matches)
         (dafny-sequence path-results))]
    {:allowed? (aget result 0)
     :path-checks (.toNumber (aget result 1))
     :direct-probe-checks (.toNumber (aget result 2))
     :self-permission-checks (.toNumber (aget result 3))
     :arrow-checks (.toNumber (aget result 4))
     :callback-trace
     (mapv
      (fn [event]
        (mapv #(.toNumber %) event))
      (aget result 5))}))

(defn- raw-relation-definition
  [acyclic
   {:keys [resource-type relation-name subject-type relation-eid]}]
  (js-invoke
   (.-RawRelationDefinition acyclic)
   "create_RawRelationDefinition"
   (dafny-string resource-type)
   (dafny-string relation-name)
   (dafny-string subject-type)
   (big-number relation-eid)))

(defn- raw-permission-definition
  [acyclic
   {:keys [resource-type permission-name source-is-self?
           source-relation-name target-is-relation? target-name]}]
  (js-invoke
   (.-RawPermissionDefinition acyclic)
   "create_RawPermissionDefinition"
   (dafny-string resource-type)
   (dafny-string permission-name)
   source-is-self?
   (dafny-string source-relation-name)
   target-is-relation?
   (dafny-string target-name)))

(defn- materialized-relation-path->map
  [path]
  {:type :relation
   :name (.toVerbatimString (.-dtor_relationName path) false)
   :subject-type (.toVerbatimString (.-dtor_subjectType path) false)
   :relation-eid (.toNumber (.-dtor_relationEid path))})

(defn- source-materialized-path->map
  [path]
  (cond
    (.-is_SourceRelationPath path)
    {:type :relation
     :name (.toVerbatimString (.-dtor_relationName path) false)
     :subject-type (.toVerbatimString (.-dtor_subjectType path) false)
     :relation-eid (.toNumber (.-dtor_relationEid path))}

    (.-is_SourceSelfPermissionPath path)
    {:type :self-permission
     :target-permission
     (.toVerbatimString (.-dtor_targetPermission path) false)
     :resource-type (.toVerbatimString (.-dtor_resourceType path) false)}

    (.-is_SourceArrowRelationPath path)
    {:type :arrow
     :via (.toVerbatimString (.-dtor_viaRelation path) false)
     :target-type (.toVerbatimString (.-dtor_targetType path) false)
     :via-relation-eid (.toNumber (.-dtor_viaRelationEid path))
     :target-relation
     (.toVerbatimString (.-dtor_targetRelation path) false)
     :sub-paths
     (mapv materialized-relation-path->map (.-dtor_subPaths path))}

    :else
    {:type :arrow
     :via (.toVerbatimString (.-dtor_viaRelation path) false)
     :target-type (.toVerbatimString (.-dtor_targetType path) false)
     :via-relation-eid (.toNumber (.-dtor_viaRelationEid path))
     :target-permission
     (.toVerbatimString (.-dtor_targetPermission path) false)}))

(defn materialize-permission-paths
  "Executes the generated source-shaped permission-path materializer and
  direct-grant summary. This is formal test support, not a public EACL API."
  [{:keys [relations definitions subject-type]}]
  (let [acyclic (.-AcyclicEngine generated)
        result
        (js-invoke
         (.-__default acyclic)
         "MaterializePermissionPaths"
         (dafny-sequence
          (map #(raw-relation-definition acyclic %) relations))
         (dafny-sequence
          (map #(raw-permission-definition acyclic %) definitions))
         (dafny-string subject-type))]
    {:paths (mapv source-materialized-path->map (aget result 0))
     :direct-relation-eids
     (mapv #(.toNumber %) (aget result 1))
     :exhaustive? (aget result 2)}))

(defn- optional-eid
  [indexed value]
  (if (some? value)
    (js-invoke
     (.-OptionalEid indexed)
     "create_Bound"
     (big-number value))
    (js-invoke
     (.-OptionalEid indexed)
     "create_NoBound")))

(defn- indexed-projection
  [indexed
   {:keys [kind subject-type subject-eid relation-eid
           resource-type resource-eid bound-eid]}]
  (case kind
    :subject->resources
    (js-invoke
     (.-Projection indexed)
     "create_SubjectToResources"
     (dafny-string subject-type)
     (big-number subject-eid)
     (big-number relation-eid)
     (dafny-string resource-type)
     (optional-eid indexed bound-eid))

    :resource->subjects
    (js-invoke
     (.-Projection indexed)
     "create_ResourceToSubjects"
     (dafny-string resource-type)
     (big-number resource-eid)
     (big-number relation-eid)
     (dafny-string subject-type)
     (optional-eid indexed bound-eid))))

(defn- indexed-scan-rejection-reason
  [error]
  (cond
    (.-is_InvalidCommand error) :invalid-command
    (.-is_MismatchedRequestScope error) :mismatched-request-scope
    (.-is_MismatchedRequest error) :mismatched-request
    (.-is_OversizedChunk error) :oversized-chunk
    (.-is_InvalidFetchedCount error) :invalid-fetched-count
    (.-is_NonProgressingResponse error) :non-progressing-response
    (.-is_InvalidEid error) :invalid-eid
    (.-is_OutOfOrder error) :out-of-order
    :else :bound-violation))

(defn- indexed-page-continuation-reason
  [error]
  (cond
    (.-is_InvalidContinuationSize error) :invalid-size
    (.-is_ContinuationNotForwardPage error) :not-forward-page
    (.-is_ContinuationNotComplete error) :not-complete
    (.-is_ContinuationHasNoLookahead error) :no-lookahead
    (.-is_ContinuationHasPendingScan error) :pending-scan
    :else :boundary-mismatch))

(defn- indexed-scan-decision
  [{:keys [command response]}]
  (let [indexed (.-IndexedTraversal generated)
        command'
        (js-invoke
         (.-ScanCommand indexed)
         "create_ScanCommand"
         (big-number (:request-scope command))
         (big-number (:request-id command))
         (indexed-projection indexed (:projection command))
         (big-number (:chunk-size command)))
        response'
        (js-invoke
         (.-ScanResponse indexed)
         "create_ScanResponse"
         (big-number (:request-scope response))
         (big-number (:request-id response))
         (let [values (:values response)]
           (if (seq values)
             (dafny-sequence (map big-number values))
             @empty-values-sequence))
         (:terminal? response)
         (big-number (:fetched-values response)))
        decision
        (js-invoke
         (.-__default indexed)
         "ValidateScanResponse"
         command'
         response')]
    (if (.-is_ScanAccepted decision)
      {:status :accepted
       :values (mapv #(.toNumber %) (.-dtor_values decision))
       :terminal? (.-dtor_terminal decision)
       :fetched-values (.toNumber (.-dtor_fetchedValues decision))}
      {:status :rejected
       :reason
       (indexed-scan-rejection-reason (.-dtor_error decision))})))

(defn- indexed-rule
  [indexed
   {:keys [kind head relation-eid subject-type target-node
           via-relation-eid intermediate-type target-relation-eid
           target-subject-type]}]
  (let [rule (.-IndexedRule indexed)
        head' (permission-node head)]
    (case kind
      :relation
      (js-invoke
       rule
       "create_RelationRule"
       head'
       (big-number relation-eid)
       (dafny-string subject-type))

      :self-permission
      (js-invoke
       rule
       "create_SelfPermissionRule"
       head'
       (permission-node target-node))

      :arrow-relation
      (js-invoke
       rule
       "create_ArrowRelationRule"
       head'
       (big-number via-relation-eid)
       (dafny-string intermediate-type)
       (big-number target-relation-eid)
       (dafny-string target-subject-type))

      :arrow-permission
      (js-invoke
       rule
       "create_ArrowPermissionRule"
       head'
       (big-number via-relation-eid)
       (dafny-string intermediate-type)
       (permission-node target-node)))))

(defn- relation-binding
  [refinement {:keys [eid relation]}]
  (js-invoke
   (.-RelationBinding refinement)
   "create_RelationBinding"
   (big-number eid)
   (relation-node relation)))

(defn- indexed-plan-rejection-reason
  [error]
  (cond
    (.-is_InvalidRelationCatalog error) :invalid-relation-catalog
    (.-is_InvalidIndexedRule error) :invalid-indexed-rule
    (.-is_DuplicateIndexedRule error) :duplicate-indexed-rule
    (.-is_PermissionOpenRule error) :permission-open-rule
    (.-is_CompiledRuleMismatch error) :compiled-rule-mismatch
    (.-is_InvalidSeedRule error) :invalid-seed-rule
    (.-is_DuplicateSeedRule error) :duplicate-seed-rule
    :else :seed-bucket-mismatch))

(defn- indexed-plan-decision
  [{:keys [relations permissions definitions relation-bindings
           indexed-rules]}]
  (let [indexed (.-IndexedTraversal generated)
        refinement (.-IndexedRefinement generated)
        decision
        (js-invoke
         (.-__default (.-IndexedCertification generated))
         "CertifyIndexedRules"
         (dafny-sequence (map relation-node relations))
         (dafny-sequence (map permission-node permissions))
         (dafny-sequence (map rule-definition definitions))
         (dafny-sequence
          (map #(relation-binding refinement %) relation-bindings))
         (dafny-sequence
          (map #(indexed-rule indexed %) indexed-rules)))]
    (if (.-is_PlanCertified decision)
      {:status :certified}
      {:status :rejected
       :reason
       (indexed-plan-rejection-reason (.-dtor_error decision))})))

(defn- indexed-seed-decision
  [{:keys [indexed-rules seed-rules subject-type]}]
  (let [indexed (.-IndexedTraversal generated)
        decision
        (js-invoke
         (.-__default (.-IndexedCertification generated))
         "CertifySeedBucket"
         (dafny-sequence
          (map #(indexed-rule indexed %) indexed-rules))
         (dafny-sequence
          (map #(indexed-rule indexed %) seed-rules))
         (dafny-string subject-type))]
    (if (.-is_PlanCertified decision)
      {:status :certified}
      {:status :rejected
       :reason
       (indexed-plan-rejection-reason (.-dtor_error decision))})))


(defn- indexed-render-mode
  [{:keys [kind size limit target-eid]}]
  (let [render-mode
        (.-RenderMode (.-IndexedTraversal generated))]
    (case kind
      :page
      (js-invoke
       render-mode
       "create_RenderPage"
       (big-number size))

      :count
      (js-invoke
       render-mode "create_RenderCount" (big-number limit))

      :all-count
      (js-invoke render-mode "create_RenderAllCount")

      :boolean
      (js-invoke
       render-mode "create_RenderBoolean" (big-number target-eid)))))

(defn- indexed-limit-kind
  [kind]
  (cond
    (.-is_IndexedDerivedGrants kind) :derived-grants
    (.-is_IndexedAdvancedDatoms kind) :advanced-datoms
    :else :queued-work))

(defn- indexed-render-error
  [error]
  (if (.-is_CursorSkipped error)
    {:reason :cursor-skipped
     :expected-ordinal (.toNumber (.-dtor_expectedOrdinal error))
     :actual-ordinal (.toNumber (.-dtor_actualOrdinal error))}
    {:reason :cursor-result-mismatch
     :ordinal (.toNumber (.-dtor_ordinal error))
     :expected-eid (.toNumber (.-dtor_expectedEid error))
     :actual-eid (.toNumber (.-dtor_actualEid error))}))

(defn- indexed-bound-value
  [bound]
  (when (.-is_Bound bound)
    (.toNumber (.-dtor_value bound))))

(defn- dafny-string-value
  [value]
  (.toVerbatimString value false))
(defn- indexed-projection-value
  [projection]
  (if (.-is_SubjectToResources projection)
    {:kind :subject->resources
     :subject-type
     (dafny-string-value (.-dtor_subjectType projection))
     :subject-eid (.toNumber (.-dtor_subjectEid projection))
     :relation-eid (.toNumber (.-dtor_relationEid projection))
     :resource-type
     (dafny-string-value (.-dtor_resourceType projection))
     :bound-eid (indexed-bound-value (.-dtor_bound projection))}
    {:kind :resource->subjects
     :resource-type
     (dafny-string-value (.-dtor_resourceType projection))
     :resource-eid (.toNumber (.-dtor_resourceEid projection))
     :relation-eid (.toNumber (.-dtor_relationEid projection))
     :subject-type
     (dafny-string-value (.-dtor_subjectType projection))
     :bound-eid (indexed-bound-value (.-dtor_bound projection))}))

(defn- indexed-command-value
  [command]
  {:request-scope (.toNumber (.-dtor_requestScope command))
   :request-id (.toNumber (.-dtor_requestId command))
   :projection
   (indexed-projection-value (.-dtor_projection command))
   :chunk-size (.toNumber (.-dtor_chunkSize command))})

(defn- indexed-counters-value
  [counters]
  {:backend-commands
   (.toNumber (.-dtor_backendCommands counters))
   :adapter-fetched-values
   (.toNumber (.-dtor_adapterFetchedValues counters))
   :engine-consumed-values
   (.toNumber (.-dtor_engineConsumedValues counters))
   :cumulative-enqueues
   (.toNumber (.-dtor_cumulativeEnqueues counters))
   :current-queue-depth
   (.toNumber (.-dtor_currentQueueDepth counters))
   :maximum-queue-depth
   (.toNumber (.-dtor_maximumQueueDepth counters))
   :unique-grants
   (.toNumber (.-dtor_uniqueGrants counters))
   :emitted-results
   (.toNumber (.-dtor_emittedResults counters))
   :rule-applications
   (.toNumber (.-dtor_ruleApplications counters))
   :consumer-grant-joins
   (.toNumber (.-dtor_consumerGrantJoins counters))
   :render-advances
   (.toNumber (.-dtor_renderAdvances counters))})

(defrecord GeneratedJavaScriptIndexedPlan [plan seed-rules])

(defn- compile-indexed-plan
  [{:keys [indexed-rules seed-rules-by-subject-type]}]
  (let [indexed (.-IndexedTraversal generated)
        rules
        (dafny-sequence
         (map #(indexed-rule indexed %) indexed-rules))]
    (->GeneratedJavaScriptIndexedPlan
     (js-invoke
      (.-__default indexed) "CompileTraversalPlan" rules)
     (into
      {}
      (map
       (fn [[subject-type seed-rules]]
         [subject-type
          (dafny-sequence
           (map #(indexed-rule indexed %) seed-rules))]))
      seed-rules-by-subject-type))))

(defn- indexed-init
  [direction
   {:keys [compiled-plan request-scope subject-type subject-eid
           root-node root-resource-eid result-type render chunk-size
           limits]}]
  (let [indexed (.-IndexedTraversal generated)
        plan (:plan compiled-plan)
        limits' (indexed-limits limits)
        outcome
        (case direction
          :forward
          (js-invoke
           (.-__default indexed)
           "InitializeForwardCompiled"
           plan
           (get
            (:seed-rules compiled-plan)
            subject-type
            (dafny-sequence []))
           (big-number request-scope)
           (dafny-string subject-type)
           (big-number subject-eid)
           (permission-node root-node)
           (dafny-string result-type)
           (indexed-render-mode render)
           (big-number chunk-size)
           limits')

          :reverse
          (js-invoke
           (.-__default indexed)
           "InitializeReverseCompiled"
           plan
           (big-number request-scope)
           (dafny-string subject-type)
           (permission-node root-node)
           (big-number root-resource-eid)
           (dafny-string result-type)
           (indexed-render-mode render)
           (big-number chunk-size)
           limits'))]
    (if (case direction
          :forward (.-is_ForwardInitialized outcome)
          :reverse (.-is_ReverseInitialized outcome))
      {:status :initialized
       :state (.-dtor_state outcome)}
      {:status :limit-exceeded
       :limit-kind
       (indexed-limit-kind (.-dtor_kind outcome))})))

(defn- indexed-drive
  [direction state limits fuel]
  (let [indexed (.-IndexedTraversal generated)
        batching (.-IndexedBatching generated)
        outcome
        (case direction
          :forward
          (js-invoke
           (.-__default batching)
           "DriveForwardScans"
           state (indexed-limits limits) (dafny-fuel fuel) (big-number 64))

          :reverse
          (js-invoke
           (.-__default batching)
           "DriveReverseScans"
           state (indexed-limits limits) (dafny-fuel fuel) (big-number 64)))]
    (cond
      (case direction
        :forward (.-is_ForwardNeedScans outcome)
        :reverse (.-is_ReverseNeedScans outcome))
      (let [commands (mapv indexed-command-value (.-dtor_commands outcome))
            state (.-dtor_batch outcome)]
        (if (= 1 (count commands))
          {:status :need-scan
           :state state
           :command (first commands)}
          {:status :need-scans
           :state state
           :commands commands}))

      (case direction
        :forward (.-is_ForwardBatchComplete outcome)
        :reverse (.-is_ReverseBatchComplete outcome))
      {:status :complete
       :state (.-dtor_state outcome)}

      (case direction
        :forward (.-is_ForwardBatchYielded outcome)
        :reverse (.-is_ReverseBatchYielded outcome))
      {:status :yielded
       :state (.-dtor_state outcome)}

      (case direction
        :forward (.-is_ForwardBatchRenderRejected outcome)
        :reverse (.-is_ReverseBatchRenderRejected outcome))
      {:status :render-rejected
       :state (.-dtor_state outcome)
       :error (indexed-render-error (.-dtor_error outcome))}

      (case direction
        :forward (.-is_ForwardBatchLimitExceeded outcome)
        :reverse (.-is_ReverseBatchLimitExceeded outcome))
      {:status :limit-exceeded
       :state (.-dtor_state outcome)
       :limit-kind
       (indexed-limit-kind (.-dtor_kind outcome))}

      :else
      (throw
       (ex-info
        "Generated indexed drive returned an internal-only step variant."
        {:direction direction})))))

(defn- indexed-response
  [indexed response]
  (js-invoke
   (.-ScanResponse indexed)
   "create_ScanResponse"
   (big-number (:request-scope response))
   (big-number (:request-id response))
   (let [values (:values response)]
     (if (seq values)
       (dafny-sequence (map big-number values))
       @empty-values-sequence))
   (:terminal? response)
   (big-number (:fetched-values response))))

(defn- indexed-resume
  [direction state response limits]
  (let [indexed (.-IndexedTraversal generated)
        batching (.-IndexedBatching generated)
        batch-state?
        (case direction
          :forward (instance? (.-ForwardBatchState batching) state)
          :reverse (instance? (.-ReverseBatchState batching) state))
        batch? (or batch-state? (vector? response))
        responses (if (vector? response) response [response])
        response'
        (if batch?
          (dafny-sequence (map #(indexed-response indexed %) responses))
          (indexed-response indexed response))
        outcome
        (case direction
          :forward
          (if batch?
            (js-invoke
             (.-__default batching)
             "ResumeForwardScans"
             state response' (indexed-limits limits))
            (js-invoke
             (.-__default indexed)
             "ResumeForwardScan"
             state response' (indexed-limits limits)))

          :reverse
          (if batch?
            (js-invoke
             (.-__default batching)
             "ResumeReverseScans"
             state response' (indexed-limits limits))
            (js-invoke
             (.-__default indexed)
             "ResumeReverseScan"
             state response' (indexed-limits limits))))]
    (cond
      (case direction
        :forward (.-is_ForwardScanResumed outcome)
        :reverse (.-is_ReverseScanResumed outcome))
      {:status :resumed
       :state (.-dtor_state outcome)}

      (case direction
        :forward (.-is_ForwardScanRejected outcome)
        :reverse (.-is_ReverseScanRejected outcome))
      {:status :scan-rejected
       :reason
       (indexed-scan-rejection-reason (.-dtor_error outcome))}

      :else
      {:status :limit-exceeded
       :state (.-dtor_state outcome)
       :limit-kind
       (indexed-limit-kind (.-dtor_kind outcome))})))

(defn- indexed-continue-page
  [direction state {:keys [size bound]}]
  (let [indexed (.-IndexedTraversal generated)
        outcome
        (js-invoke
         (.-__default indexed)
         (case direction
           :forward "ContinueForwardPage"
           :reverse "ContinueReversePage")
         state
         (big-number size)
         (big-number (:ordinal bound))
         (big-number (:eid bound)))]
    (if (case direction
          :forward (.-is_ForwardPageContinued outcome)
          :reverse (.-is_ReversePageContinued outcome))
      {:status :continued
       :state (.-dtor_state outcome)}
      {:status :rejected
       :reason
       (indexed-page-continuation-reason
        (.-dtor_error outcome))})))

(defn- indexed-public-result
  [direction state]
  (let [indexed (.-IndexedTraversal generated)
        render (.-dtor_render state)
        result
        (js-invoke (.-__default indexed) "ReadRenderResult" render)
        retained
        (case direction
          :forward
          (js-invoke
           (.-__default indexed)
           "ForwardRetainedLogicalUnits"
           state)
          :reverse
          (js-invoke
           (.-__default indexed)
           "ReverseRetainedLogicalUnits"
           state))
        common
        {:counters
         (indexed-counters-value (.-dtor_counters state))
         :retained-logical-units (.toNumber retained)}]
    (cond
      (.-is_PageReady result)
      (merge
       common
       {:status :page
        :items
        (mapv #(.toNumber %) (.-dtor_items result))
        :start-ordinal
        (.toNumber (.-dtor_startOrdinal result))
        :has-next? (.-dtor_hasNext result)
        :has-previous? (.-dtor_hasPrevious result)})

      (.-is_CountReady result)
      (merge
       common
       {:status :count
        :count (.toNumber (.-dtor_count result))
        :truncated? (.-dtor_truncated result)})

      :else
      (merge
       common
       {:status :boolean
        :allowed? (.-dtor_allowed result)}))))

(defrecord GeneratedJavaScriptKernel []
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

(def generated-javascript-kernel
  (->GeneratedJavaScriptKernel))

(def default-selection
  {:kernel generated-javascript-kernel})

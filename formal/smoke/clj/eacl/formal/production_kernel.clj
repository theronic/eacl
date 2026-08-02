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
    RawPageRequest)))

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
         (if (= :at-least-as-fresh mode)
           (ConsistencyMode/create_AtLeastAsFresh)
           (ConsistencyMode/create_MinimizeLatency))
         (dafny-nat cursor-graph)
         (exact-selection exact))]
    (cond
      (.is_UseCurrent decision) :current
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
      :cache-validation (cache-decision input))))

(def generated-java-kernel
  (->GeneratedJavaKernel))

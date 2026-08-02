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
      :cache-validation (cache-decision input))))

(def generated-javascript-kernel
  (->GeneratedJavaScriptKernel))

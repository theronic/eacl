(ns eacl.formal.cache-kernel-bridge
  (:import
   (CacheKernel CacheCandidate ProofState Telemetry)
   (dafny DafnySequence DafnySet TypeDescriptor)))

(defn- dafny-string
  [value]
  (DafnySequence/asUnicodeString value))

(defn- proof-state
  [proof]
  (if (some? proof)
    (ProofState/create_CompleteProof (dafny-string proof))
    (ProofState/create_ProofUnavailable)))

(defn- candidate
  [{:keys [status
           authenticated?
           key
           source
           graph
           proof
           value]
    :or {status :candidate
         authenticated? true
         key "key"
         source "source"
         graph 7
         proof "proof"
         value 42}}]
  (case status
    :missing
    (CacheCandidate/create_NoCandidate
     TypeDescriptor/BIG_INTEGER)

    :provider-failure
    (CacheCandidate/create_ProviderFailed
     TypeDescriptor/BIG_INTEGER)

    (CacheCandidate/create_Candidate
     TypeDescriptor/BIG_INTEGER
     authenticated?
     (dafny-string key)
     (dafny-string source)
     (biginteger graph)
     (proof-state proof)
     (biginteger value)
     (Telemetry/create
      (biginteger graph)
      (biginteger 0)))))

(defn validate
  [{:keys [deterministic?
           dependency-scope-nonempty?
           expected-key
           expected-source
           selected-graph
           ancestors
           selected-proof
           entry]
    :or {deterministic? true
         dependency-scope-nonempty? true
         expected-key "key"
         expected-source "source"
         selected-graph 7
         ancestors #{6}
         selected-proof "proof"
         entry {}}}]
  (let [decision
        (CacheKernel.__default/ValidateCache
         TypeDescriptor/BIG_INTEGER
         deterministic?
         dependency-scope-nonempty?
         (dafny-string expected-key)
         (dafny-string expected-source)
         (biginteger selected-graph)
         (DafnySet.
          (mapv biginteger ancestors))
         (proof-state selected-proof)
         (candidate entry))]
    (if (.is_CacheHit decision)
      {:status :hit
       :value (.longValue (.dtor_value decision))
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

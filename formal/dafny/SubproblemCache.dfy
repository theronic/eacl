module SubproblemCache {
  datatype CacheTier = DenotationTier | AnswerTier

  datatype ReuseMode = ExactReuse | ManagedReuse

  // Storage treats keys as opaque.  This datatype records only the complete
  // authority dimensions that must participate in equality; operation-
  // specific semantics and proof descriptors remain host inputs.
  datatype CompositeKey = CompositeKey(
    formatVersion: nat,
    domain: string,
    tier: CacheTier,
    mode: ReuseMode,
    sourceLifecycle: nat,
    sourceIdentity: string,
    engineAbi: string,
    valueAbi: string,
    semanticIdentity: string,
    reuseIdentity: string
  )

  // Every authority dimension is mandatory in the datatype.  The exact
  // current format version rejects both unversioned placeholders and prior or
  // unknown snapshot/key ABIs; individual opaque strings may legitimately
  // encode an empty host value and therefore have no extra storage semantics.
  predicate LegalTierMode(key: CompositeKey) {
    key.tier.AnswerTier? || key.mode.ExactReuse?
  }

  predicate CompleteCompositeKey(key: CompositeKey) {
    key.formatVersion == 2 && LegalTierMode(key)
  }

  lemma DenotationKeysAreExactOnly(key: CompositeKey)
    requires CompleteCompositeKey(key)
    requires key.tier.DenotationTier?
    ensures key.mode.ExactReuse?
  {
  }

  lemma ManagedKeysAreAnswerTierOnly(key: CompositeKey)
    requires CompleteCompositeKey(key)
    requires key.mode.ManagedReuse?
    ensures key.tier.AnswerTier?
  {
  }

  lemma EqualCompositeKeysSeparateEveryAuthorityInput(
    left: CompositeKey,
    right: CompositeKey
  )
    requires left == right
    ensures left.formatVersion == right.formatVersion
    ensures left.domain == right.domain
    ensures left.tier == right.tier
    ensures left.mode == right.mode
    ensures left.sourceLifecycle == right.sourceLifecycle
    ensures left.sourceIdentity == right.sourceIdentity
    ensures left.engineAbi == right.engineAbi
    ensures left.valueAbi == right.valueAbi
    ensures left.semanticIdentity == right.semanticIdentity
    ensures left.reuseIdentity == right.reuseIdentity
  {
  }

  datatype CompletionState = Partial | Completed

  datatype CompletedValue<T> = CompletedValue(
    value: T,
    state: CompletionState,
    structurallyValid: bool,
    pageValue: bool,
    resultCount: nat
  )

  predicate RetentionEligible<T>(completed: CompletedValue<T>) {
    completed.state.Completed? &&
    completed.structurallyValid &&
    (!completed.pageValue || completed.resultCount <= 1000)
  }

  lemma OneThousandPageItemsAreRetentionEligible<T>(value: T)
    ensures RetentionEligible(
              CompletedValue(value, Completed, true, true, 1000)
            )
  {
  }

  lemma MoreThanOneThousandPageItemsAreNotRetentionEligible<T>(
    value: T,
    resultCount: nat
  )
    requires 1000 < resultCount
    ensures !RetentionEligible(
              CompletedValue(value, Completed, true, true, resultCount)
            )
  {
  }

  lemma PartialValuesAreNotRetentionEligible<T>(
    value: T,
    pageValue: bool,
    resultCount: nat
  )
    ensures !RetentionEligible(
              CompletedValue(value, Partial, true, pageValue, resultCount)
            )
  {
  }

  predicate CacheStageAvailable(
    deadlineExpired: bool,
    cancelled: bool
  ) {
    !deadlineExpired && !cancelled
  }

  lemma ExpiredOrCancelledRequestCannotPublish(
    deadlineExpired: bool,
    cancelled: bool
  )
    requires deadlineExpired || cancelled
    ensures !CacheStageAvailable(deadlineExpired, cancelled)
  {
  }

  // One CacheStore instance represents one independent production LRU tier.
  // `storeInstance` is the identity of the private tier atoms installed in
  // the outer runtime slot; it is deliberately distinct from the semantic
  // source lifecycle carried by every composite key. LRU order is absent:
  // arbitrary eviction is its complete semantic effect on authorization.
  datatype CacheStore<T> = CacheStore(
    entries: map<CompositeKey, CompletedValue<T>>,
    capacity: nat,
    storeInstance: nat,
    tier: CacheTier
  )

  // The fresh-evaluation map is ghost authority, not runtime state. ValidStore
  // separates the host's ingress-validity contract for the complete resident
  // envelope from equality of its semantic payload to independent evaluation.
  // This admits a causally validated managed-to-exact promotion that preserves
  // immutable origin metadata while still requiring the promoted payload to be
  // exactly the selected exact key's independently computed result.
  ghost predicate ValidStore<T, D>(
    store: CacheStore<T>,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool
  ) {
    0 < store.capacity &&
    |store.entries| <= store.capacity &&
    (forall key <- store.entries ::
       CompleteCompositeKey(key) &&
       key.tier == store.tier &&
       RetentionEligible(store.entries[key]) &&
       residentValid(key, store.entries[key].value) &&
       key in recomputed &&
       semanticValue(store.entries[key].value) == recomputed[key])
  }

  datatype CacheLookup<T> = CacheMiss | CacheHit(value: T)

  // A live read is ordinary map membership.  It intentionally has no
  // operation validator: ValidStore is established at the only two ingress
  // points (validated publication and validated off-side restore).
  function Lookup<T>(
    store: CacheStore<T>,
    key: CompositeKey
  ): CacheLookup<T> {
    if key in store.entries
    then CacheHit(store.entries[key].value)
    else CacheMiss
  }

  datatype LookupTrace<T> = LookupTrace(
    result: CacheLookup<T>,
    lookupProbes: nat,
    recencyTouches: nat
  )

  // The cache-stage guard precedes the standard-LRU operation.  An
  // unavailable request therefore neither probes the resident map nor changes
  // recency metadata.  An available rejected/missing candidate is a probe but
  // not a touch; only an accepted held value is LRU usage.
  function LookupAtAvailableCacheStage<T>(
    cacheStageAvailable: bool,
    store: CacheStore<T>,
    key: CompositeKey
  ): LookupTrace<T> {
    if !cacheStageAvailable then
      LookupTrace(CacheMiss, 0, 0)
    else
      var result := Lookup(store, key);
      LookupTrace(result, 1, if result.CacheHit? then 1 else 0)
  }

  lemma UnavailableCacheStageSkipsLookupAndTouch<T>(
    store: CacheStore<T>,
    key: CompositeKey
  )
    ensures var trace := LookupAtAvailableCacheStage(false, store, key);
            trace.result.CacheMiss? &&
            trace.lookupProbes == 0 &&
            trace.recencyTouches == 0
  {
  }

  lemma ResidentLookupNeedsNoRepeatedValidation<T, D>(
    store: CacheStore<T>,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool,
    key: CompositeKey
  )
    requires ValidStore(store, recomputed, semanticValue, residentValid)
    requires key in store.entries
    ensures Lookup(store, key).CacheHit?
    ensures residentValid(key, Lookup(store, key).value)
    ensures semanticValue(Lookup(store, key).value) == recomputed[key]
  {
  }

  lemma EveryAcceptedHitEqualsFreshEvaluation<T, D>(
    store: CacheStore<T>,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool,
    key: CompositeKey
  )
    requires ValidStore(store, recomputed, semanticValue, residentValid)
    ensures Lookup(store, key).CacheHit? ==>
              key in recomputed &&
              CompleteCompositeKey(key) &&
              key.tier == store.tier &&
              residentValid(key, Lookup(store, key).value) &&
              semanticValue(Lookup(store, key).value) == recomputed[key]
  {
  }

  // Exact and managed mappings inhabit the same production LRU tier. This
  // uniform semantic envelope lets one CacheStore model that shared capacity;
  // the computed revision is immutable provenance for exact residents and the
  // request-relative causal field of managed residents.
  datatype ResidentEnvelope<T> = ResidentEnvelope(
    value: T,
    computedRevision: nat
  )

  function ResidentSemanticValue<T>(envelope: ResidentEnvelope<T>): T {
    envelope.value
  }

  function ExactLookup<T>(
    store: CacheStore<ResidentEnvelope<T>>,
    key: CompositeKey
  ): CacheLookup<T>
    requires CompleteCompositeKey(key)
    requires key.mode.ExactReuse?
  {
    match Lookup(store, key)
    case CacheMiss => CacheMiss
    case CacheHit(envelope) => CacheHit(envelope.value)
  }

  lemma ExactResidentLookupEqualsFreshEvaluation<T>(
    store: CacheStore<ResidentEnvelope<T>>,
    recomputed: map<CompositeKey, T>,
    residentValid: (CompositeKey, ResidentEnvelope<T>) -> bool,
    key: CompositeKey
  )
    requires ValidStore(
               store,
               recomputed,
               ResidentSemanticValue,
               residentValid
             )
    requires CompleteCompositeKey(key)
    requires key.mode.ExactReuse?
    requires key in store.entries
    ensures ExactLookup(store, key).CacheHit?
    ensures residentValid(key, store.entries[key].value)
    ensures ExactLookup(store, key).value == recomputed[key]
  {
  }

  predicate ManagedCausallyEligible<T>(
    envelope: ResidentEnvelope<T>,
    selectedRevision: nat
  ) {
    envelope.computedRevision <= selectedRevision
  }

  function ManagedLookup<T>(
    store: CacheStore<ResidentEnvelope<T>>,
    key: CompositeKey,
    selectedRevision: nat
  ): CacheLookup<T>
    requires CompleteCompositeKey(key)
    requires key.mode.ManagedReuse?
  {
    match Lookup(store, key)
    case CacheMiss => CacheMiss
    case CacheHit(envelope) =>
      if ManagedCausallyEligible(envelope, selectedRevision)
      then CacheHit(envelope.value)
      else CacheMiss
  }

  lemma ManagedHitRetainsPerRequestCausalCheck<T>(
    store: CacheStore<ResidentEnvelope<T>>,
    recomputed: map<CompositeKey, T>,
    residentValid: (CompositeKey, ResidentEnvelope<T>) -> bool,
    key: CompositeKey,
    selectedRevision: nat
  )
    requires ValidStore(
               store,
               recomputed,
               ResidentSemanticValue,
               residentValid
             )
    requires CompleteCompositeKey(key)
    requires key.mode.ManagedReuse?
    ensures ManagedLookup(store, key, selectedRevision).CacheHit? ==>
              key in store.entries &&
              residentValid(key, store.entries[key].value) &&
              store.entries[key].value.computedRevision <= selectedRevision &&
              ManagedLookup(store, key, selectedRevision).value ==
              recomputed[key]
  {
  }

  lemma FutureManagedResidentCannotAnswerOlderRequest<T>(
    store: CacheStore<ResidentEnvelope<T>>,
    key: CompositeKey,
    selectedRevision: nat
  )
    requires key in store.entries
    requires CompleteCompositeKey(key)
    requires key.mode.ManagedReuse?
    requires selectedRevision <
             store.entries[key].value.computedRevision
    ensures ManagedLookup(store, key, selectedRevision).CacheMiss?
  {
  }

  datatype TieredResolution<T> =
    | TieredMiss
    | ExactHit(value: T)
    | ManagedHit(value: T)

  function ResolveExactThenManaged<T>(
    exact: CacheLookup<T>,
    managed: CacheLookup<T>
  ): TieredResolution<T> {
    match exact
    case CacheHit(value) => ExactHit(value)
    case CacheMiss =>
      match managed
      case CacheHit(value) => ManagedHit(value)
      case CacheMiss => TieredMiss
  }

  predicate ExactManagedAnswerAlternatives(
    exactKey: CompositeKey,
    managedKey: CompositeKey
  ) {
    CompleteCompositeKey(exactKey) &&
    CompleteCompositeKey(managedKey) &&
    exactKey.tier.AnswerTier? &&
    managedKey.tier.AnswerTier? &&
    exactKey.mode.ExactReuse? &&
    managedKey.mode.ManagedReuse? &&
    exactKey.domain == managedKey.domain &&
    exactKey.sourceLifecycle == managedKey.sourceLifecycle &&
    exactKey.sourceIdentity == managedKey.sourceIdentity &&
    exactKey.engineAbi == managedKey.engineAbi &&
    exactKey.valueAbi == managedKey.valueAbi &&
    exactKey.semanticIdentity == managedKey.semanticIdentity
  }

  function ResolveKeysExactThenManaged<T>(
    store: CacheStore<ResidentEnvelope<T>>,
    exactKey: CompositeKey,
    managedKey: CompositeKey,
    selectedRevision: nat,
    managedReuseEligible: bool
  ): TieredResolution<T>
    requires ExactManagedAnswerAlternatives(exactKey, managedKey)
  {
    ResolveExactThenManaged(
      ExactLookup(store, exactKey),
      if managedReuseEligible
      then ManagedLookup(store, managedKey, selectedRevision)
      else CacheMiss
    )
  }

  datatype AnswerLookupTrace<T> = AnswerLookupTrace(
    result: TieredResolution<T>,
    lookupProbes: nat,
    recencyTouches: nat,
    managedProofReads: nat
  )

  // The high-level availability check runs before the exact probe and before
  // constructing a managed proof descriptor. Exact-first lookup also avoids
  // managed proof I/O on an exact hit. A causally rejected managed resident is
  // a non-touching miss.
  function ResolveAnswerAtAvailableCacheStage<T>(
    cacheStageAvailable: bool,
    store: CacheStore<ResidentEnvelope<T>>,
    exactKey: CompositeKey,
    managedKey: CompositeKey,
    selectedRevision: nat,
    managedReuseEligible: bool
  ): AnswerLookupTrace<T>
    requires ExactManagedAnswerAlternatives(exactKey, managedKey)
  {
    if !cacheStageAvailable then
      AnswerLookupTrace(TieredMiss, 0, 0, 0)
    else
      var exact := ExactLookup(store, exactKey);
      match exact
      case CacheHit(value) =>
        AnswerLookupTrace(ExactHit(value), 1, 1, 0)
      case CacheMiss =>
        if !managedReuseEligible then
          AnswerLookupTrace(TieredMiss, 1, 0, 0)
        else
          var managed := ManagedLookup(store, managedKey, selectedRevision);
          match managed
          case CacheHit(value) =>
            AnswerLookupTrace(ManagedHit(value), 2, 1, 1)
          case CacheMiss =>
            AnswerLookupTrace(TieredMiss, 2, 0, 1)
  }

  lemma UnavailableCacheStageSkipsAnswerLookupProofAndTouch<T>(
    store: CacheStore<ResidentEnvelope<T>>,
    exactKey: CompositeKey,
    managedKey: CompositeKey,
    selectedRevision: nat,
    managedReuseEligible: bool
  )
    requires ExactManagedAnswerAlternatives(exactKey, managedKey)
    ensures var trace := ResolveAnswerAtAvailableCacheStage(
                           false,
                           store,
                           exactKey,
                           managedKey,
                           selectedRevision,
                           managedReuseEligible
                         );
            trace.result.TieredMiss? &&
            trace.lookupProbes == 0 &&
            trace.recencyTouches == 0 &&
            trace.managedProofReads == 0
  {
  }

  lemma ExactAnswerHitSkipsManagedProofRead<T>(
    store: CacheStore<ResidentEnvelope<T>>,
    exactKey: CompositeKey,
    managedKey: CompositeKey,
    selectedRevision: nat,
    managedReuseEligible: bool
  )
    requires ExactManagedAnswerAlternatives(exactKey, managedKey)
    requires ExactLookup(store, exactKey).CacheHit?
    ensures var trace := ResolveAnswerAtAvailableCacheStage(
                           true,
                           store,
                           exactKey,
                           managedKey,
                           selectedRevision,
                           managedReuseEligible
                         );
            trace.result.ExactHit? &&
            trace.lookupProbes == 1 &&
            trace.recencyTouches == 1 &&
            trace.managedProofReads == 0
  {
  }

  lemma ManagedKeyResolutionRequiresEligibility<T>(
    store: CacheStore<ResidentEnvelope<T>>,
    exactKey: CompositeKey,
    managedKey: CompositeKey,
    selectedRevision: nat,
    managedReuseEligible: bool
  )
    requires ExactManagedAnswerAlternatives(exactKey, managedKey)
    ensures ResolveKeysExactThenManaged(
              store,
              exactKey,
              managedKey,
              selectedRevision,
              managedReuseEligible
            ).ManagedHit? ==>
              managedReuseEligible &&
              ExactLookup(store, exactKey).CacheMiss?
  {
  }

  lemma ExactHitAlwaysPrecedesManagedHit<T>(
    exactValue: T,
    managed: CacheLookup<T>
  )
    ensures ResolveExactThenManaged(
              CacheHit(exactValue),
              managed
            ) == ExactHit(exactValue)
  {
  }

  lemma ManagedHitRequiresExactMiss<T>(
    exact: CacheLookup<T>,
    managed: CacheLookup<T>
  )
    ensures ResolveExactThenManaged(exact, managed).ManagedHit? ==>
              exact.CacheMiss? && managed.CacheHit?
  {
  }

  datatype MissComputation = MissComputation(
    owner: nat,
    key: CompositeKey,
    computationToken: nat
  )

  function BeginIndependentComputation(
    request: nat,
    key: CompositeKey,
    computationToken: nat
  ): MissComputation {
    MissComputation(request, key, computationToken)
  }

  lemma EveryMissBeginsARequestOwnedComputation<T>(
    lookup: CacheLookup<T>,
    request: nat,
    key: CompositeKey,
    computationToken: nat
  )
    requires lookup.CacheMiss?
    ensures BeginIndependentComputation(
              request,
              key,
              computationToken
            ).owner == request
    ensures BeginIndependentComputation(
              request,
              key,
              computationToken
            ).key == key
    ensures BeginIndependentComputation(
              request,
              key,
              computationToken
            ).computationToken == computationToken
  {
  }

  // Enabled resolution projects a held resident to its user-visible semantic
  // result, or returns the requesting operation's independently computed
  // result. Disabled caching skips storage entirely. D may itself be a typed
  // success-or-error outcome.
  function ResolveSemanticRequest<T, D>(
    cacheEnabled: bool,
    store: CacheStore<T>,
    key: CompositeKey,
    semanticValue: T -> D,
    independentlyComputed: D
  ): D {
    if !cacheEnabled then
      independentlyComputed
    else
      match Lookup(store, key)
      case CacheHit(value) => semanticValue(value)
      case CacheMiss => independentlyComputed
  }

  // Cache disablement is request-wide, including nested denotation work.  The
  // host refinement must clear both dynamic stores before invoking the
  // independent computation; retaining the stores here makes a leaked lookup
  // or publication visible instead of hiding it behind result equality.
  datatype StorageIsolatedResolution<A, N, D> = StorageIsolatedResolution(
    result: D,
    answerStore: CacheStore<A>,
    denotationStore: CacheStore<N>,
    lookupProbes: nat,
    recencyTouches: nat,
    managedProofReads: nat,
    publicationAttempts: nat
  )

  function ResolveStorageIsolatedRequest<A, N, D>(
    answerStore: CacheStore<A>,
    denotationStore: CacheStore<N>,
    independentlyComputed: D
  ): StorageIsolatedResolution<A, N, D> {
    StorageIsolatedResolution(
      independentlyComputed,
      answerStore,
      denotationStore,
      0,
      0,
      0,
      0
    )
  }

  // The host chooses this branch before either tier is dereferenced.  Keeping
  // both guards in the signature prevents the cache-disabled and
  // deadline/cancellation obligations from being justified by an unrelated
  // result-equality argument.
  function ResolveAtUnavailableStorageBoundary<A, N, D>(
    cacheEnabled: bool,
    cacheStageAvailable: bool,
    answerStore: CacheStore<A>,
    denotationStore: CacheStore<N>,
    independentlyComputed: D
  ): StorageIsolatedResolution<A, N, D>
    requires !cacheEnabled || !cacheStageAvailable
  {
    ResolveStorageIsolatedRequest(
      answerStore,
      denotationStore,
      independentlyComputed
    )
  }

  lemma CacheDisabledRequestIsStorageIsolated<A, N, D>(
    answerStore: CacheStore<A>,
    denotationStore: CacheStore<N>,
    independentlyComputed: D
  )
    ensures var resolution := ResolveAtUnavailableStorageBoundary(
                                false,
                                true,
                                answerStore,
                                denotationStore,
                                independentlyComputed
                              );
            resolution.result == independentlyComputed &&
            resolution.answerStore == answerStore &&
            resolution.denotationStore == denotationStore &&
            resolution.lookupProbes == 0 &&
            resolution.recencyTouches == 0 &&
            resolution.managedProofReads == 0 &&
            resolution.publicationAttempts == 0
  {
  }

  lemma UnavailableCacheStageIsStorageIsolated<A, N, D>(
    answerStore: CacheStore<A>,
    denotationStore: CacheStore<N>,
    independentlyComputed: D
  )
    ensures var resolution := ResolveAtUnavailableStorageBoundary(
                                true,
                                false,
                                answerStore,
                                denotationStore,
                                independentlyComputed
                              );
            resolution.result == independentlyComputed &&
            resolution.answerStore == answerStore &&
            resolution.denotationStore == denotationStore &&
            resolution.lookupProbes == 0 &&
            resolution.recencyTouches == 0 &&
            resolution.managedProofReads == 0 &&
            resolution.publicationAttempts == 0
  {
  }

  lemma CacheBypassReturnsIndependentEvaluation<T, D>(
    store: CacheStore<T>,
    key: CompositeKey,
    semanticValue: T -> D,
    independentlyComputed: D
  )
    ensures ResolveSemanticRequest(
              false,
              store,
              key,
              semanticValue,
              independentlyComputed
            ) == independentlyComputed
  {
  }

  lemma ValidStoreResolutionMatchesIndependentEvaluation<T, D>(
    store: CacheStore<T>,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool,
    key: CompositeKey
  )
    requires ValidStore(store, recomputed, semanticValue, residentValid)
    requires key in recomputed
    ensures ResolveSemanticRequest(
              true,
              store,
              key,
              semanticValue,
              recomputed[key]
            ) == recomputed[key]
  {
  }

  predicate PublicationPermitted<T>(
    capturedStoreInstance: nat,
    target: CacheStore<T>,
    key: CompositeKey,
    completed: CompletedValue<T>,
    cacheStageAvailable: bool
  ) {
    capturedStoreInstance == target.storeInstance &&
    cacheStageAvailable &&
    CompleteCompositeKey(key) &&
    key.tier == target.tier &&
    RetentionEligible(completed)
  }

  // A retained publication may evict old residents to satisfy capacity, but
  // cannot synthesize or rewrite unrelated values. Optional drop is modeled
  // separately below as the identity transition.
  ghost predicate PublicationStep<T>(
    before: CacheStore<T>,
    after: CacheStore<T>,
    capturedStoreInstance: nat,
    key: CompositeKey,
    completed: CompletedValue<T>,
    cacheStageAvailable: bool
  ) {
    PublicationPermitted(
      capturedStoreInstance,
      before,
      key,
      completed,
      cacheStageAvailable
    ) &&
    after.capacity == before.capacity &&
    after.storeInstance == before.storeInstance &&
    after.tier == before.tier &&
    key in after.entries &&
    (key in before.entries ==>
       after.entries[key] == before.entries[key]) &&
    (key !in before.entries ==> after.entries[key] == completed) &&
    |after.entries| <= after.capacity &&
    (forall resident <- after.entries ::
       resident == key ||
       (resident in before.entries &&
        after.entries[resident] == before.entries[resident]))
  }

  lemma PublicationStepRequiresCapturedInstalledStoreInstance<T>(
    before: CacheStore<T>,
    after: CacheStore<T>,
    capturedStoreInstance: nat,
    key: CompositeKey,
    completed: CompletedValue<T>,
    cacheStageAvailable: bool
  )
    requires PublicationStep(
               before,
               after,
               capturedStoreInstance,
               key,
               completed,
               cacheStageAvailable
             )
    ensures capturedStoreInstance == before.storeInstance
  {
  }

  ghost predicate ValidatedPublicationStep<T, D>(
    before: CacheStore<T>,
    after: CacheStore<T>,
    capturedStoreInstance: nat,
    key: CompositeKey,
    completed: CompletedValue<T>,
    cacheStageAvailable: bool,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool
  ) {
    PublicationStep(
      before,
      after,
      capturedStoreInstance,
      key,
      completed,
      cacheStageAvailable
    ) &&
    key in recomputed &&
    residentValid(key, completed.value) &&
    semanticValue(completed.value) == recomputed[key]
  }

  lemma ValidatedPublicationPreservesValidStore<T, D>(
    before: CacheStore<T>,
    after: CacheStore<T>,
    capturedStoreInstance: nat,
    key: CompositeKey,
    completed: CompletedValue<T>,
    cacheStageAvailable: bool,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool
  )
    requires ValidStore(before, recomputed, semanticValue, residentValid)
    requires ValidatedPublicationStep(
               before,
               after,
               capturedStoreInstance,
               key,
               completed,
               cacheStageAvailable,
               recomputed,
               semanticValue,
               residentValid
             )
    ensures ValidStore(after, recomputed, semanticValue, residentValid)
  {
    forall resident | resident in after.entries
      ensures resident.tier == after.tier &&
              CompleteCompositeKey(resident) &&
              RetentionEligible(after.entries[resident]) &&
              residentValid(resident, after.entries[resident].value) &&
              resident in recomputed &&
              semanticValue(after.entries[resident].value) ==
              recomputed[resident]
    {
      if resident in before.entries {
        if resident == key {
          assert after.entries[resident] == before.entries[resident];
        }
      } else {
        assert resident == key;
        assert after.entries[resident] == completed;
      }
      if resident != key {
        assert resident in before.entries;
      }
    }
  }

  lemma PartialInvalidOrOversizedValuesCannotPublish<T>(
    storeInstance: nat,
    target: CacheStore<T>,
    key: CompositeKey,
    completed: CompletedValue<T>,
    cacheStageAvailable: bool
  )
    requires target.storeInstance == storeInstance
    requires !RetentionEligible(completed)
    ensures !PublicationPermitted(
              storeInstance,
              target,
              key,
              completed,
              cacheStageAvailable
            )
  {
  }

  lemma UnavailableCacheStageCannotPublish<T>(
    storeInstance: nat,
    target: CacheStore<T>,
    key: CompositeKey,
    completed: CompletedValue<T>
  )
    requires target.storeInstance == storeInstance
    ensures !PublicationPermitted(
              storeInstance,
              target,
              key,
              completed,
              false
            )
  {
  }

  lemma ExpiredOrCancelledCandidateCannotPublish<T>(
    storeInstance: nat,
    target: CacheStore<T>,
    key: CompositeKey,
    completed: CompletedValue<T>,
    deadlineExpired: bool,
    cancelled: bool
  )
    requires target.storeInstance == storeInstance
    requires deadlineExpired || cancelled
    ensures !PublicationPermitted(
              storeInstance,
              target,
              key,
              completed,
              CacheStageAvailable(deadlineExpired, cancelled)
            )
  {
    ExpiredOrCancelledRequestCannotPublish(deadlineExpired, cancelled);
    UnavailableCacheStageCannotPublish(
      storeInstance,
      target,
      key,
      completed
    );
  }

  ghost predicate ArbitraryEviction<T>(
    before: CacheStore<T>,
    after: CacheStore<T>
  ) {
    after.capacity == before.capacity &&
    after.storeInstance == before.storeInstance &&
    after.tier == before.tier &&
    |after.entries| <= after.capacity &&
    (forall key <- after.entries ::
       key in before.entries && after.entries[key] == before.entries[key])
  }

  lemma ArbitraryEvictionPreservesValidStore<T, D>(
    before: CacheStore<T>,
    after: CacheStore<T>,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool
  )
    requires ValidStore(before, recomputed, semanticValue, residentValid)
    requires ArbitraryEviction(before, after)
    ensures ValidStore(after, recomputed, semanticValue, residentValid)
  {
  }

  lemma ArbitraryEvictionCanOnlyTurnAHitIntoAMiss<T>(
    before: CacheStore<T>,
    after: CacheStore<T>,
    key: CompositeKey
  )
    requires ArbitraryEviction(before, after)
    ensures Lookup(after, key).CacheHit? ==>
              Lookup(before, key) == Lookup(after, key)
  {
  }

  lemma ArbitraryEvictionPreservesResolvedResult<T, D>(
    before: CacheStore<T>,
    after: CacheStore<T>,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool,
    key: CompositeKey
  )
    requires ValidStore(before, recomputed, semanticValue, residentValid)
    requires ArbitraryEviction(before, after)
    requires key in recomputed
    ensures ResolveSemanticRequest(
              true,
              before,
              key,
              semanticValue,
              recomputed[key]
            ) ==
            ResolveSemanticRequest(
              true,
              after,
              key,
              semanticValue,
              recomputed[key]
            )
  {
    ArbitraryEvictionPreservesValidStore(
      before,
      after,
      recomputed,
      semanticValue,
      residentValid
    );
    ValidStoreResolutionMatchesIndependentEvaluation(
      before,
      recomputed,
      semanticValue,
      residentValid,
      key
    );
    ValidStoreResolutionMatchesIndependentEvaluation(
      after,
      recomputed,
      semanticValue,
      residentValid,
      key
    );
  }

  function EmptyReplacement<T>(
    installed: CacheStore<T>,
    nextStoreInstance: nat
  ): CacheStore<T>
    requires installed.storeInstance < nextStoreInstance
  {
    CacheStore(map[], installed.capacity, nextStoreInstance, installed.tier)
  }

  function EmptyStore<T>(
    capacity: nat,
    storeInstance: nat,
    tier: CacheTier
  ): CacheStore<T>
    requires 0 < capacity
  {
    CacheStore(map[], capacity, storeInstance, tier)
  }

  lemma EmptyConstructionEstablishesValidStore<T, D>(
    capacity: nat,
    storeInstance: nat,
    tier: CacheTier,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool
  )
    requires 0 < capacity
    ensures ValidStore(
              EmptyStore(capacity, storeInstance, tier),
              recomputed,
              semanticValue,
              residentValid
            )
  {
  }

  lemma ReplacementStoreInstanceStartsEmptyAndValid<T, D>(
    installed: CacheStore<T>,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool,
    nextStoreInstance: nat
  )
    requires ValidStore(installed, recomputed, semanticValue, residentValid)
    requires installed.storeInstance < nextStoreInstance
    ensures ValidStore(
              EmptyReplacement(installed, nextStoreInstance),
              recomputed,
              semanticValue,
              residentValid
            )
    ensures |EmptyReplacement(installed, nextStoreInstance).entries| == 0
  {
  }

  // A computation publishes through its captured private store reference.
  // Once the outer slot installs a different store instance, the candidate
  // cannot target the installed store. The detached and installed stores may
  // legitimately contain equal semantic composite keys.
  lemma DetachedCandidateCannotPublishIntoInstalledStore<T>(
    installed: CacheStore<T>,
    capturedStoreInstance: nat,
    key: CompositeKey,
    completed: CompletedValue<T>,
    cacheStageAvailable: bool
  )
    requires capturedStoreInstance < installed.storeInstance
    ensures !PublicationPermitted(
              capturedStoreInstance,
              installed,
              key,
              completed,
              cacheStageAvailable
            )
  {
  }

  lemma MonotoneStoreReplacementPreventsABA<T>(
    installed: CacheStore<T>,
    capturedStoreInstance: nat,
    intermediateStoreInstance: nat,
    key: CompositeKey,
    completed: CompletedValue<T>,
    cacheStageAvailable: bool
  )
    requires capturedStoreInstance < intermediateStoreInstance
    requires intermediateStoreInstance < installed.storeInstance
    ensures capturedStoreInstance != installed.storeInstance
    ensures !PublicationPermitted(
              capturedStoreInstance,
              installed,
              key,
              completed,
              cacheStageAvailable
            )
  {
  }

  // Restore validation happens off-side, before a fresh private store becomes
  // visible.  The predicate includes key completeness, structure, page bound,
  // capacity, and the host validity contract for every resident envelope.
  // Equality to `recomputed` is the semantic fact inherited from the
  // authenticated exporting ValidStore; restore does not recompute answers.
  ghost predicate ValidatedRestore<T, D>(
    restored: CacheStore<T>,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool
  ) {
    0 < restored.capacity &&
    |restored.entries| <= restored.capacity &&
    (forall key <- restored.entries ::
       CompleteCompositeKey(key) &&
       key.tier == restored.tier &&
       RetentionEligible(restored.entries[key]) &&
       residentValid(key, restored.entries[key].value) &&
       key in recomputed &&
       semanticValue(restored.entries[key].value) == recomputed[key])
  }

  lemma ValidatedOffSideRestoreEstablishesValidStore<T, D>(
    restored: CacheStore<T>,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool
  )
    requires ValidatedRestore(
               restored,
               recomputed,
               semanticValue,
               residentValid
             )
    ensures ValidStore(restored, recomputed, semanticValue, residentValid)
  {
  }

  lemma FreshValidatedRestoreDetachesPriorStoreInstance<T, D>(
    before: CacheStore<T>,
    restored: CacheStore<T>,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool
  )
    requires ValidStore(before, recomputed, semanticValue, residentValid)
    requires ValidatedRestore(
               restored,
               recomputed,
               semanticValue,
               residentValid
             )
    requires restored.capacity == before.capacity
    requires restored.tier == before.tier
    requires before.storeInstance < restored.storeInstance
    ensures ValidStore(restored, recomputed, semanticValue, residentValid)
    ensures restored.capacity == before.capacity
    ensures restored.tier == before.tier
    ensures before.storeInstance < restored.storeInstance
  {
    ValidatedOffSideRestoreEstablishesValidStore(
      restored,
      recomputed,
      semanticValue,
      residentValid
    );
  }

  // These are the complete authorized ways a private resident map can change.
  // LRU touches are abstractly map-preserving; an implementation may mutate
  // recency metadata that is absent from this semantic state.
  datatype AuthorizedStoreAction<T> =
    | LookupTouch
    | Evict
    | Publish(
        capturedStoreInstance: nat,
        key: CompositeKey,
        completed: CompletedValue<T>,
        cacheStageAvailable: bool
      )
    | DropPublication
    | Restore
    | ReplaceStoreInstance

  ghost predicate AuthorizedStoreTransition<T, D>(
    action: AuthorizedStoreAction<T>,
    before: CacheStore<T>,
    after: CacheStore<T>,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool
  ) {
    match action
    case LookupTouch => after == before
    case Evict => ArbitraryEviction(before, after)
    case Publish(
      capturedStoreInstance,
      key,
      completed,
      cacheStageAvailable
      ) =>
      ValidatedPublicationStep(
        before,
        after,
        capturedStoreInstance,
        key,
        completed,
        cacheStageAvailable,
        recomputed,
        semanticValue,
        residentValid
      )
    case DropPublication => after == before
    case Restore =>
      ValidatedRestore(after, recomputed, semanticValue, residentValid) &&
      after.capacity == before.capacity &&
      after.tier == before.tier &&
      before.storeInstance < after.storeInstance
    case ReplaceStoreInstance =>
      before.storeInstance < after.storeInstance &&
      after == EmptyReplacement(before, after.storeInstance)
  }

  // Induction step for ValidStore: the base cases are empty construction and
  // validated restore; every authorized live transition preserves it.
  lemma AuthorizedStoreTransitionPreservesValidStore<T, D>(
    action: AuthorizedStoreAction<T>,
    before: CacheStore<T>,
    after: CacheStore<T>,
    recomputed: map<CompositeKey, D>,
    semanticValue: T -> D,
    residentValid: (CompositeKey, T) -> bool
  )
    requires ValidStore(before, recomputed, semanticValue, residentValid)
    requires AuthorizedStoreTransition(
               action,
               before,
               after,
               recomputed,
               semanticValue,
               residentValid
             )
    ensures ValidStore(after, recomputed, semanticValue, residentValid)
  {
    match action
    case LookupTouch =>
    case Evict =>
      ArbitraryEvictionPreservesValidStore(
        before,
        after,
        recomputed,
        semanticValue,
        residentValid
      );
    case Publish(
      capturedStoreInstance,
        key,
        completed,
        cacheStageAvailable
        ) =>
      ValidatedPublicationPreservesValidStore(
        before,
        after,
        capturedStoreInstance,
        key,
        completed,
        cacheStageAvailable,
        recomputed,
        semanticValue,
        residentValid
      );
    case DropPublication =>
    case Restore =>
      FreshValidatedRestoreDetachesPriorStoreInstance(
        before,
        after,
        recomputed,
        semanticValue,
        residentValid
      );
    case ReplaceStoreInstance =>
      ReplacementStoreInstanceStartsEmptyAndValid(
        before,
        recomputed,
        semanticValue,
        residentValid,
        after.storeInstance
      );
  }

  lemma BegunMissIsOwnedByItsInitiatingRequest(
    request: nat,
    key: CompositeKey,
    computationToken: nat
  )
    ensures BeginIndependentComputation(
              request,
              key,
              computationToken
            ).owner == request
    ensures BeginIndependentComputation(
              request,
              key,
              computationToken
            ).key == key
  {
  }

  lemma ConcurrentRequestsHaveIndependentComputationOwners(
    leftRequest: nat,
    rightRequest: nat,
    key: CompositeKey,
    leftComputationToken: nat,
    rightComputationToken: nat
  )
    requires leftRequest != rightRequest
    ensures BeginIndependentComputation(
              leftRequest,
              key,
              leftComputationToken
            ).owner !=
            BeginIndependentComputation(
              rightRequest,
              key,
              rightComputationToken
            ).owner
  {
  }

  lemma OneRequestCanOwnDistinctSimultaneousIdenticalMisses(
    request: nat,
    key: CompositeKey,
    leftComputationToken: nat,
    rightComputationToken: nat
  )
    requires leftComputationToken != rightComputationToken
    ensures BeginIndependentComputation(
              request,
              key,
              leftComputationToken
            ) !=
            BeginIndependentComputation(
              request,
              key,
              rightComputationToken
            )
  {
  }
}

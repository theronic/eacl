// Cache refinement for complete signed dependencies and exact scan responses.
module OperatorCacheRefinement {
  datatype DependencySign = Positive | Negative

  datatype SignedDependency = SignedDependency(
    relation: nat,
    sign: DependencySign
  )

  datatype DecisionValue =
    | PointDecision(allowed: bool)
    | VectorDecision(decisions: seq<bool>)

  datatype ComputationOutcome =
    | ComputationComplete(value: DecisionValue)
    | ComputationFailed(code: nat)

  datatype CacheEntry = CacheEntry(
    value: DecisionValue,
    dependencies: set<SignedDependency>,
    proofFrame: map<nat, nat>
  )

  predicate CompleteDependencyEntry(
    entry: CacheEntry,
    completeDependencies: set<SignedDependency>
  ) {
    entry.dependencies == completeDependencies &&
    forall dependency <- completeDependencies ::
      dependency.relation in entry.proofFrame
  }

  predicate EntryCurrent(
    entry: CacheEntry,
    currentGenerations: map<nat, nat>
  ) {
    forall dependency <- entry.dependencies ::
      dependency.relation in currentGenerations &&
      dependency.relation in entry.proofFrame &&
      currentGenerations[dependency.relation] ==
      entry.proofFrame[dependency.relation]
  }

  lemma EveryPositiveAndNegativeDependencyRelationInvalidates(
    entry: CacheEntry,
    completeDependencies: set<SignedDependency>,
    currentGenerations: map<nat, nat>,
    changedRelation: nat
  )
    requires CompleteDependencyEntry(entry, completeDependencies)
    requires exists dependency <- completeDependencies ::
               dependency.relation == changedRelation
    requires changedRelation in currentGenerations
    requires currentGenerations[changedRelation] !=
             entry.proofFrame[changedRelation]
    ensures !EntryCurrent(entry, currentGenerations)
  {
    var dependency :|
      dependency in completeDependencies &&
      dependency.relation == changedRelation;
    assert dependency in entry.dependencies;
  }

  datatype SemanticSnapshot = SemanticSnapshot(
    relationSlices: map<nat, set<nat>>,
    generations: map<nat, nat>
  )

  function DependencyRelations(
    dependencies: set<SignedDependency>
  ): set<nat> {
    set dependency <- dependencies :: dependency.relation
  }

  predicate SnapshotCoversDependencies(
    snapshot: SemanticSnapshot,
    dependencies: set<SignedDependency>
  ) {
    forall relation <- DependencyRelations(dependencies) ::
      relation in snapshot.relationSlices &&
      relation in snapshot.generations
  }

  predicate EntryCreatedFromSnapshot(
    entry: CacheEntry,
    dependencies: set<SignedDependency>,
    snapshot: SemanticSnapshot
  ) {
    CompleteDependencyEntry(entry, dependencies) &&
    SnapshotCoversDependencies(snapshot, dependencies) &&
    forall relation <- DependencyRelations(dependencies) ::
      entry.proofFrame[relation] == snapshot.generations[relation]
  }

  // This is the supported-writer premise: equal generation stamps for a
  // dependency relation imply an equal complete relation slice.  It is an
  // explicit backend obligation, not inferred from numeric revisions.
  predicate GenerationFrameCoherent(
    prior: SemanticSnapshot,
    current: SemanticSnapshot,
    dependencies: set<SignedDependency>
  ) {
    SnapshotCoversDependencies(prior, dependencies) &&
    SnapshotCoversDependencies(current, dependencies) &&
    forall relation <- DependencyRelations(dependencies) ::
      prior.generations[relation] == current.generations[relation] ==>
        prior.relationSlices[relation] ==
        current.relationSlices[relation]
  }

  predicate DependencyProjectionEqual(
    left: SemanticSnapshot,
    right: SemanticSnapshot,
    dependencies: set<SignedDependency>
  ) {
    SnapshotCoversDependencies(left, dependencies) &&
    SnapshotCoversDependencies(right, dependencies) &&
    forall relation <- DependencyRelations(dependencies) ::
      left.relationSlices[relation] == right.relationSlices[relation]
  }

  lemma CurrentCompleteFramePreservesDependencyProjection(
    entry: CacheEntry,
    dependencies: set<SignedDependency>,
    prior: SemanticSnapshot,
    current: SemanticSnapshot
  )
    requires EntryCreatedFromSnapshot(entry, dependencies, prior)
    requires EntryCurrent(entry, current.generations)
    requires GenerationFrameCoherent(prior, current, dependencies)
    ensures DependencyProjectionEqual(prior, current, dependencies)
  {
    forall relation <- DependencyRelations(dependencies)
      ensures prior.relationSlices[relation] ==
              current.relationSlices[relation]
    {
      var dependency :|
        dependency in dependencies &&
        dependency.relation == relation;
      assert dependency in entry.dependencies;
      assert entry.proofFrame[relation] == prior.generations[relation];
      assert current.generations[relation] == entry.proofFrame[relation];
    }
  }

  // A concrete evaluator refinement supplies this relation.  Functionality
  // is weaker and less circular than assuming the cached value equals a
  // separately computed value.
  ghost predicate DecisionSemantics(
    request: nat,
    snapshot: SemanticSnapshot,
    dependencies: set<SignedDependency>,
    value: DecisionValue
  )

  ghost predicate SemanticsDependsOnlyOnProjection(
    request: nat,
    dependencies: set<SignedDependency>,
    left: SemanticSnapshot,
    right: SemanticSnapshot
  ) {
    DependencyProjectionEqual(left, right, dependencies) &&
    forall value: DecisionValue ::
      DecisionSemantics(request, left, dependencies, value) <==>
      DecisionSemantics(request, right, dependencies, value)
  }

  ghost predicate SemanticsIsFunctional(
    request: nat,
    snapshot: SemanticSnapshot,
    dependencies: set<SignedDependency>
  ) {
    forall left, right: DecisionValue ::
      DecisionSemantics(request, snapshot, dependencies, left) &&
      DecisionSemantics(request, snapshot, dependencies, right) ==>
        left == right
  }

  lemma CompleteCurrentCacheEntryEqualsFreshSemanticDecision(
    request: nat,
    entry: CacheEntry,
    freshValue: DecisionValue,
    dependencies: set<SignedDependency>,
    prior: SemanticSnapshot,
    current: SemanticSnapshot
  )
    requires EntryCreatedFromSnapshot(entry, dependencies, prior)
    requires EntryCurrent(entry, current.generations)
    requires GenerationFrameCoherent(prior, current, dependencies)
    requires SemanticsDependsOnlyOnProjection(
               request,
               dependencies,
               prior,
               current
             )
    requires SemanticsIsFunctional(request, current, dependencies)
    requires DecisionSemantics(
               request,
               prior,
               dependencies,
               entry.value
             )
    requires DecisionSemantics(
               request,
               current,
               dependencies,
               freshValue
             )
    ensures entry.value == freshValue
  {
    CurrentCompleteFramePreservesDependencyProjection(
      entry,
      dependencies,
      prior,
      current
    );
    assert DecisionSemantics(
        request,
        current,
        dependencies,
        entry.value
      );
  }

  datatype NegativeSubproblem =
    | NegativeCompleted(exactDenotation: set<nat>)
    | NegativePending
    | NegativeTimedOut
    | NegativeCancelled
    | NegativeLimitExceeded
    | NegativeBackendFailed

  predicate NegativeResultCacheEligible(result: NegativeSubproblem) {
    result.NegativeCompleted?
  }

  lemma OnlyCompletedExactNegativeResultsAreEligible(
    result: NegativeSubproblem
  )
    ensures NegativeResultCacheEligible(result) <==>
            result.NegativeCompleted?
    ensures !result.NegativeCompleted? ==>
              !NegativeResultCacheEligible(result)
  {
  }

  datatype CachedDecision = MissingDecision | PresentDecision(entry: CacheEntry)

  datatype Execution = Execution(
    outcome: ComputationOutcome,
    cacheLookups: nat,
    backendComputations: nat,
    cachePublications: nat
  )

  function Execute(
    cacheEnabled: bool,
    cached: CachedDecision,
    validHit: bool,
    computed: ComputationOutcome
  ): Execution {
    if !cacheEnabled then
      Execution(computed, 0, 1, 0)
    else if cached.PresentDecision? && validHit then
      Execution(ComputationComplete(cached.entry.value), 1, 0, 0)
    else
      Execution(
        computed,
        1,
        1,
        if computed.ComputationComplete? then 1 else 0
      )
  }

  lemma ValidCacheHitElisionRefinesCacheDisabledExecution(
    cached: CacheEntry,
    computedValue: DecisionValue
  )
    requires cached.value == computedValue
    ensures Execute(
              true,
              PresentDecision(cached),
              true,
              ComputationComplete(computedValue)
            ).outcome ==
            Execute(
              false,
              MissingDecision,
              false,
              ComputationComplete(computedValue)
            ).outcome
    ensures Execute(
              true,
              PresentDecision(cached),
              true,
              ComputationComplete(computedValue)
            ).backendComputations == 0
  {
  }

  lemma CurrentCompleteCacheHitRefinesFreshExecution(
    request: nat,
    entry: CacheEntry,
    freshValue: DecisionValue,
    dependencies: set<SignedDependency>,
    prior: SemanticSnapshot,
    current: SemanticSnapshot
  )
    requires EntryCreatedFromSnapshot(entry, dependencies, prior)
    requires EntryCurrent(entry, current.generations)
    requires GenerationFrameCoherent(prior, current, dependencies)
    requires SemanticsDependsOnlyOnProjection(
               request,
               dependencies,
               prior,
               current
             )
    requires SemanticsIsFunctional(request, current, dependencies)
    requires DecisionSemantics(
               request,
               prior,
               dependencies,
               entry.value
             )
    requires DecisionSemantics(
               request,
               current,
               dependencies,
               freshValue
             )
    ensures Execute(
              true,
              PresentDecision(entry),
              true,
              ComputationComplete(freshValue)
            ).outcome ==
            Execute(
              false,
              MissingDecision,
              false,
              ComputationComplete(freshValue)
            ).outcome
    ensures Execute(
              true,
              PresentDecision(entry),
              true,
              ComputationComplete(freshValue)
            ).backendComputations == 0
  {
    CompleteCurrentCacheEntryEqualsFreshSemanticDecision(
      request,
      entry,
      freshValue,
      dependencies,
      prior,
      current
    );
    ValidCacheHitElisionRefinesCacheDisabledExecution(entry, freshValue);
  }

  lemma CacheDisabledPerformsNoCacheWork(
    cached: CachedDecision,
    validHit: bool,
    computed: ComputationOutcome
  )
    ensures Execute(false, cached, validHit, computed).outcome == computed
    ensures Execute(false, cached, validHit, computed).cacheLookups == 0
    ensures Execute(false, cached, validHit, computed).cachePublications == 0
  {
  }

  datatype ScanKey = ScanKey(
    descriptor: nat,
    basis: nat,
    ascending: bool,
    lowerBound: nat,
    upperBound: nat,
    limit: nat
  )

  datatype ScanEntry = ScanEntry(
    key: ScanKey,
    values: seq<nat>,
    exhausted: bool
  )

  function Prefix(values: seq<nat>, limit: nat): seq<nat> {
    if limit < |values| then values[..limit] else values
  }

  predicate ExactScanEntry(
    entry: ScanEntry,
    completeScan: seq<nat>
  ) {
    entry.values == Prefix(completeScan, entry.key.limit) &&
    entry.exhausted == (|completeScan| <= entry.key.limit)
  }

  predicate ReusableScanPrefix(
    request: ScanKey,
    entry: ScanEntry
  ) {
    request.descriptor == entry.key.descriptor &&
    request.basis == entry.key.basis &&
    request.ascending == entry.key.ascending &&
    request.lowerBound == entry.key.lowerBound &&
    request.upperBound == entry.key.upperBound &&
    request.limit <= entry.key.limit &&
    (entry.exhausted || request.limit <= |entry.values|)
  }

  function ReuseScanPrefix(
    request: ScanKey,
    entry: ScanEntry
  ): seq<nat>
    requires ReusableScanPrefix(request, entry)
  {
    Prefix(entry.values, request.limit)
  }

  lemma PrefixOfPrefixIsSmallestPrefix(
    values: seq<nat>,
    innerLimit: nat,
    outerLimit: nat
  )
    requires outerLimit <= innerLimit
    ensures Prefix(Prefix(values, innerLimit), outerLimit) ==
            Prefix(values, outerLimit)
  {
    if innerLimit < |values| {
      if outerLimit < innerLimit {
        assert values[..innerLimit][..outerLimit] == values[..outerLimit];
      }
    }
  }

  lemma ExactScanResponseReuseDoesNotWidenOrChangeResult(
    request: ScanKey,
    entry: ScanEntry,
    completeScan: seq<nat>
  )
    requires ExactScanEntry(entry, completeScan)
    requires ReusableScanPrefix(request, entry)
    ensures request.descriptor == entry.key.descriptor
    ensures request.basis == entry.key.basis
    ensures request.ascending == entry.key.ascending
    ensures request.lowerBound == entry.key.lowerBound
    ensures request.upperBound == entry.key.upperBound
    ensures request.limit <= entry.key.limit
    ensures ReuseScanPrefix(request, entry) ==
            Prefix(completeScan, request.limit)
  {
    PrefixOfPrefixIsSmallestPrefix(
      completeScan,
      entry.key.limit,
      request.limit
    );
  }
}

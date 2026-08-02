module TemporalSafety {
  datatype Graph = Graph(graphId: nat)

  datatype CacheMissReason =
    | NoCandidate
    | ProviderFailure
    | Unauthenticated
    | ScopeMismatch
    | FutureOrSibling
    | ProofMismatch

  datatype CacheProvenance =
    | ExactGraph
    | CausalProofLift

  datatype CacheEntry<T> =
    | Missing
    | Failed
    | Candidate(
        authenticated: bool,
        semanticKey: string,
        sourceIdentity: string,
        computationGraph: Graph,
        dependencyProof: string,
        cachedValue: T
      )

  datatype CacheDecision<T> =
    | Miss(reason: CacheMissReason)
    | Hit(value: T, provenance: CacheProvenance)

  function ValidateCache<T>(
    expectedKey: string,
    expectedSource: string,
    selectedGraph: Graph,
    selectedAncestors: set<Graph>,
    selectedProof: string,
    entry: CacheEntry<T>
  ): CacheDecision<T> {
    match entry
    case Missing => Miss(NoCandidate)
    case Failed => Miss(ProviderFailure)
    case Candidate(
      authenticated,
      semanticKey,
      sourceIdentity,
      computationGraph,
      dependencyProof,
      cachedValue
      ) =>
      if !authenticated then
        Miss(Unauthenticated)
      else if semanticKey != expectedKey ||
              sourceIdentity != expectedSource
      then
        Miss(ScopeMismatch)
      else if computationGraph != selectedGraph &&
              computationGraph !in selectedAncestors
        then
          Miss(FutureOrSibling)
        else if dependencyProof != selectedProof then
            Miss(ProofMismatch)
          else if computationGraph == selectedGraph then
            Hit(cachedValue, ExactGraph)
          else
            Hit(cachedValue, CausalProofLift)
  }

  lemma AcceptedCacheIsAuthenticatedAndForwardOnly<T>(
    expectedKey: string,
    expectedSource: string,
    selectedGraph: Graph,
    selectedAncestors: set<Graph>,
    selectedProof: string,
    entry: CacheEntry<T>
  )
    ensures ValidateCache(
              expectedKey,
              expectedSource,
              selectedGraph,
              selectedAncestors,
              selectedProof,
              entry
            ).Hit? ==>
              entry.Candidate? &&
              entry.authenticated &&
              entry.semanticKey == expectedKey &&
              entry.sourceIdentity == expectedSource &&
              (entry.computationGraph == selectedGraph ||
               entry.computationGraph in selectedAncestors) &&
              entry.dependencyProof == selectedProof
  {
  }

  lemma FutureOrSiblingCacheIsRejected<T>(
    expectedKey: string,
    expectedSource: string,
    selectedGraph: Graph,
    selectedAncestors: set<Graph>,
    selectedProof: string,
    entry: CacheEntry<T>
  )
    requires entry.Candidate?
    requires entry.computationGraph != selectedGraph
    requires entry.computationGraph !in selectedAncestors
    ensures ValidateCache(
              expectedKey,
              expectedSource,
              selectedGraph,
              selectedAncestors,
              selectedProof,
              entry
            ).Miss?
  {
  }

  ghost predicate CompleteProofContract<T>(
    selectedGraph: Graph,
    selectedAncestors: set<Graph>,
    selectedProof: string,
    entry: CacheEntry<T>,
    freshValue: T
  ) {
    entry.Candidate? &&
    entry.authenticated &&
    (entry.computationGraph == selectedGraph ||
     entry.computationGraph in selectedAncestors) &&
    entry.dependencyProof == selectedProof ==>
      entry.cachedValue == freshValue
  }

  lemma AcceptedCacheEqualsRecomputation<T>(
    expectedKey: string,
    expectedSource: string,
    selectedGraph: Graph,
    selectedAncestors: set<Graph>,
    selectedProof: string,
    entry: CacheEntry<T>,
    freshValue: T
  )
    requires CompleteProofContract(
               selectedGraph,
               selectedAncestors,
               selectedProof,
               entry,
               freshValue
             )
    ensures ValidateCache(
              expectedKey,
              expectedSource,
              selectedGraph,
              selectedAncestors,
              selectedProof,
              entry
            ).Hit? ==>
              ValidateCache(
                expectedKey,
                expectedSource,
                selectedGraph,
                selectedAncestors,
                selectedProof,
                entry
              ).value == freshValue
  {
    AcceptedCacheIsAuthenticatedAndForwardOnly(
      expectedKey,
      expectedSource,
      selectedGraph,
      selectedAncestors,
      selectedProof,
      entry
    );
  }

  datatype Direction = Ascending | Descending
  datatype ResultKind = ResourceResult | SubjectResult | RelationshipResult

  datatype CursorScope = CursorScope(
    operation: string,
    normalizedQuery: string,
    direction: Direction,
    resultKind: ResultKind,
    engineVersion: nat,
    semanticsVersion: nat,
    executionIdentity: string,
    dependencyScope: string
  )

  datatype CursorProofIdentity =
    | CompleteProofIdentity(proof: string)
    | ExactSnapshotIdentity(graph: Graph)

  function BuildCursorProofIdentity(
    completeProofAvailable: bool,
    deterministicAdapter: bool,
    graph: Graph,
    proof: string
  ): CursorProofIdentity {
    if completeProofAvailable && deterministicAdapter then
      CompleteProofIdentity(proof)
    else
      ExactSnapshotIdentity(graph)
  }

  datatype CursorToken =
    | AbsentCursor
    | DecodedCursor(
        cursorAuthenticated: bool,
        cursorScope: CursorScope,
        cursorGraph: Graph,
        cursorProof: CursorProofIdentity,
        expiresAt: nat
      )

  datatype CursorRejectReason =
    | MissingCursor
    | InvalidAuthentication
    | WrongScope
    | Expired
    | ExactGraphUnavailable

  datatype CursorDecision =
    | RejectCursor(rejectReason: CursorRejectReason)
    | ContinueCurrent(selectedGraph: Graph)
    | ContinueExact(exactGraph: Graph)

  function ValidateCursor(
    expectedScope: CursorScope,
    selectedGraph: Graph,
    selectedProof: CursorProofIdentity,
    retainedGraphs: set<Graph>,
    now: nat,
    token: CursorToken
  ): CursorDecision {
    match token
    case AbsentCursor => RejectCursor(MissingCursor)
    case DecodedCursor(
      authenticated,
      scope,
      graph,
      dependencyProof,
      expiresAt
      ) =>
      if !authenticated then
        RejectCursor(InvalidAuthentication)
      else if scope != expectedScope then
        RejectCursor(WrongScope)
      else if expiresAt <= now then
        RejectCursor(Expired)
      else if dependencyProof == selectedProof then
        ContinueCurrent(selectedGraph)
      else if graph in retainedGraphs then
        ContinueExact(graph)
      else
        RejectCursor(ExactGraphUnavailable)
  }

  lemma WrongScopeRejectedBeforeGraphInfluence(
    expectedScope: CursorScope,
    selectedGraph: Graph,
    selectedProof: CursorProofIdentity,
    retainedGraphs: set<Graph>,
    now: nat,
    token: CursorToken
  )
    requires token.DecodedCursor?
    requires token.cursorAuthenticated
    requires token.cursorScope != expectedScope
    ensures ValidateCursor(
              expectedScope,
              selectedGraph,
              selectedProof,
              retainedGraphs,
              now,
              token
            ) == RejectCursor(WrongScope)
  {
  }

  lemma CurrentContinuationUsesSelectedGraph(
    expectedScope: CursorScope,
    selectedGraph: Graph,
    selectedProof: CursorProofIdentity,
    retainedGraphs: set<Graph>,
    now: nat,
    token: CursorToken
  )
    ensures ValidateCursor(
              expectedScope,
              selectedGraph,
              selectedProof,
              retainedGraphs,
              now,
              token
            ).ContinueCurrent? ==>
              ValidateCursor(
                expectedScope,
                selectedGraph,
                selectedProof,
                retainedGraphs,
                now,
                token
              ).selectedGraph == selectedGraph
  {
  }

  lemma ExactContinuationUsesRetainedAuthenticatedGraph(
    expectedScope: CursorScope,
    selectedGraph: Graph,
    selectedProof: CursorProofIdentity,
    retainedGraphs: set<Graph>,
    now: nat,
    token: CursorToken
  )
    ensures ValidateCursor(
              expectedScope,
              selectedGraph,
              selectedProof,
              retainedGraphs,
              now,
              token
            ).ContinueExact? ==>
              token.DecodedCursor? &&
              token.cursorAuthenticated &&
              token.cursorScope == expectedScope &&
              token.expiresAt > now &&
              token.cursorGraph in retainedGraphs &&
              ValidateCursor(
                expectedScope,
                selectedGraph,
                selectedProof,
                retainedGraphs,
                now,
                token
              ).exactGraph == token.cursorGraph
  {
  }

  lemma MissingProofCannotLiftAcrossGraphs(
    cursorGraph: Graph,
    selectedGraph: Graph,
    cursorProof: string,
    selectedProof: string
  )
    requires cursorGraph != selectedGraph
    ensures BuildCursorProofIdentity(
              false,
              true,
              cursorGraph,
              cursorProof
            ) != BuildCursorProofIdentity(
                   false,
                   true,
                   selectedGraph,
                   selectedProof
                 )
  {
  }

  lemma NondeterministicAdapterCannotLiftAcrossGraphs(
    cursorGraph: Graph,
    selectedGraph: Graph,
    cursorProof: string,
    selectedProof: string
  )
    requires cursorGraph != selectedGraph
    ensures BuildCursorProofIdentity(
              true,
              false,
              cursorGraph,
              cursorProof
            ) != BuildCursorProofIdentity(
                   true,
                   false,
                   selectedGraph,
                   selectedProof
                 )
  {
  }

  lemma CompleteEqualProofCanNameOneLiftIdentity(
    cursorGraph: Graph,
    selectedGraph: Graph,
    proof: string
  )
    ensures BuildCursorProofIdentity(
              true,
              true,
              cursorGraph,
              proof
            ) == BuildCursorProofIdentity(
                   true,
                   true,
                   selectedGraph,
                   proof
                 )
  {
  }

  datatype ContinuationOutcome<T> =
    | ReturnedPage(page: T)
    | TraversalFailed(error: string)

  function ResolveContinuation<T>(
    continuationAvailable: bool,
    pageAvailable: bool,
    retainedOutcome: ContinuationOutcome<T>,
    replayOutcome: ContinuationOutcome<T>
  ): ContinuationOutcome<T> {
    if continuationAvailable || pageAvailable then
      retainedOutcome
    else
      replayOutcome
  }

  ghost predicate ContinuationReplayContract<T>(
    retainedOutcome: ContinuationOutcome<T>,
    replayOutcome: ContinuationOutcome<T>
  ) {
    retainedOutcome == replayOutcome
  }

  lemma CursorCacheRaceIsDecisionTransparent<T>(
    retainedOutcome: ContinuationOutcome<T>,
    replayOutcome: ContinuationOutcome<T>,
    continuationBefore: bool,
    continuationAfter: bool,
    pageBefore: bool,
    pageAfter: bool
  )
    requires ContinuationReplayContract(retainedOutcome, replayOutcome)
    ensures ResolveContinuation(
              continuationBefore,
              pageBefore,
              retainedOutcome,
              replayOutcome
            ) == ResolveContinuation(
                   continuationAfter,
                   pageAfter,
                   retainedOutcome,
                   replayOutcome
                 )
  {
  }

  // The following machine states re-express the stabilized temporal
  // invariants as unbounded transition-preservation obligations.  They are
  // intentionally independent of any finite Apalache history bound.

  datatype CacheMachineState<T> = CacheMachineState(
    expectedKey: string,
    expectedSource: string,
    selectedGraph: Graph,
    selectedAncestors: set<Graph>,
    selectedProof: string,
    entry: CacheEntry<T>,
    decision: CacheDecision<T>
  )

  ghost predicate CacheStateSafe<T>(state: CacheMachineState<T>) {
    state.decision ==
    ValidateCache(
      state.expectedKey,
      state.expectedSource,
      state.selectedGraph,
      state.selectedAncestors,
      state.selectedProof,
      state.entry
    ) &&
    (state.decision.Hit? ==>
       state.entry.Candidate? &&
       state.entry.authenticated &&
       state.entry.semanticKey == state.expectedKey &&
       state.entry.sourceIdentity == state.expectedSource &&
       (state.entry.computationGraph == state.selectedGraph ||
        state.entry.computationGraph in state.selectedAncestors) &&
       state.entry.dependencyProof == state.selectedProof)
  }

  function ApplyCacheLookup<T>(
    expectedKey: string,
    expectedSource: string,
    selectedGraph: Graph,
    selectedAncestors: set<Graph>,
    selectedProof: string,
    entry: CacheEntry<T>
  ): CacheMachineState<T> {
    CacheMachineState(
      expectedKey,
      expectedSource,
      selectedGraph,
      selectedAncestors,
      selectedProof,
      entry,
      ValidateCache(
        expectedKey,
        expectedSource,
        selectedGraph,
        selectedAncestors,
        selectedProof,
        entry
      )
    )
  }

  lemma CacheLookupPreservesSafety<T>(
    expectedKey: string,
    expectedSource: string,
    selectedGraph: Graph,
    selectedAncestors: set<Graph>,
    selectedProof: string,
    entry: CacheEntry<T>
  )
    ensures CacheStateSafe(
              ApplyCacheLookup(
                expectedKey,
                expectedSource,
                selectedGraph,
                selectedAncestors,
                selectedProof,
                entry
              )
            )
  {
    AcceptedCacheIsAuthenticatedAndForwardOnly(
      expectedKey,
      expectedSource,
      selectedGraph,
      selectedAncestors,
      selectedProof,
      entry
    );
  }

  datatype CursorMachineState = CursorMachineState(
    expectedScope: CursorScope,
    selectedGraph: Graph,
    selectedProof: CursorProofIdentity,
    retainedGraphs: set<Graph>,
    now: nat,
    token: CursorToken,
    decision: CursorDecision
  )

  ghost predicate CursorStateSafe(state: CursorMachineState) {
    state.decision ==
    ValidateCursor(
      state.expectedScope,
      state.selectedGraph,
      state.selectedProof,
      state.retainedGraphs,
      state.now,
      state.token
    ) &&
    (state.decision.ContinueCurrent? ==>
       state.decision.selectedGraph == state.selectedGraph) &&
    (state.decision.ContinueExact? ==>
       state.token.DecodedCursor? &&
       state.token.cursorAuthenticated &&
       state.token.cursorScope == state.expectedScope &&
       state.token.expiresAt > state.now &&
       state.token.cursorGraph in state.retainedGraphs &&
       state.decision.exactGraph == state.token.cursorGraph)
  }

  function ApplyCursorValidation(
    expectedScope: CursorScope,
    selectedGraph: Graph,
    selectedProof: CursorProofIdentity,
    retainedGraphs: set<Graph>,
    now: nat,
    token: CursorToken
  ): CursorMachineState {
    CursorMachineState(
      expectedScope,
      selectedGraph,
      selectedProof,
      retainedGraphs,
      now,
      token,
      ValidateCursor(
        expectedScope,
        selectedGraph,
        selectedProof,
        retainedGraphs,
        now,
        token
      )
    )
  }

  lemma CursorValidationPreservesSafety(
    expectedScope: CursorScope,
    selectedGraph: Graph,
    selectedProof: CursorProofIdentity,
    retainedGraphs: set<Graph>,
    now: nat,
    token: CursorToken
  )
    ensures CursorStateSafe(
              ApplyCursorValidation(
                expectedScope,
                selectedGraph,
                selectedProof,
                retainedGraphs,
                now,
                token
              )
            )
  {
    CurrentContinuationUsesSelectedGraph(
      expectedScope,
      selectedGraph,
      selectedProof,
      retainedGraphs,
      now,
      token
    );
    ExactContinuationUsesRetainedAuthenticatedGraph(
      expectedScope,
      selectedGraph,
      selectedProof,
      retainedGraphs,
      now,
      token
    );
  }

  datatype ContinuationMachineState<T> = ContinuationMachineState(
    continuationAvailable: bool,
    pageAvailable: bool,
    retainedOutcome: ContinuationOutcome<T>,
    replayOutcome: ContinuationOutcome<T>,
    outcome: ContinuationOutcome<T>
  )

  ghost predicate ContinuationStateSafe<T>(
    state: ContinuationMachineState<T>
  ) {
    ContinuationReplayContract(
      state.retainedOutcome,
      state.replayOutcome
    ) &&
    state.outcome ==
    ResolveContinuation(
      state.continuationAvailable,
      state.pageAvailable,
      state.retainedOutcome,
      state.replayOutcome
    ) &&
    state.outcome == state.retainedOutcome
  }

  function ApplyContinuationLookup<T>(
    continuationAvailable: bool,
    pageAvailable: bool,
    retainedOutcome: ContinuationOutcome<T>,
    replayOutcome: ContinuationOutcome<T>
  ): ContinuationMachineState<T> {
    ContinuationMachineState(
      continuationAvailable,
      pageAvailable,
      retainedOutcome,
      replayOutcome,
      ResolveContinuation(
        continuationAvailable,
        pageAvailable,
        retainedOutcome,
        replayOutcome
      )
    )
  }

  lemma ContinuationLookupPreservesSafety<T>(
    continuationAvailable: bool,
    pageAvailable: bool,
    retainedOutcome: ContinuationOutcome<T>,
    replayOutcome: ContinuationOutcome<T>
  )
    requires ContinuationReplayContract(retainedOutcome, replayOutcome)
    ensures ContinuationStateSafe(
              ApplyContinuationLookup(
                continuationAvailable,
                pageAvailable,
                retainedOutcome,
                replayOutcome
              )
            )
  {
  }

  datatype TelemetryMachineState = TelemetryMachineState(
    generation: nat,
    validationChecks: nat,
    validationFailures: nat
  )

  ghost predicate TelemetryStateSafe(state: TelemetryMachineState) {
    state.validationFailures <= state.validationChecks
  }

  function ApplyTelemetryCompareAndSet(
    state: TelemetryMachineState,
    expectedGeneration: nat,
    validationFailed: bool
  ): TelemetryMachineState {
    if expectedGeneration != state.generation then
      state
    else
      TelemetryMachineState(
        state.generation + 1,
        state.validationChecks + 1,
        if validationFailed
        then state.validationFailures + 1
        else state.validationFailures
      )
  }

  lemma TelemetryCompareAndSetPreservesSafety(
    state: TelemetryMachineState,
    expectedGeneration: nat,
    validationFailed: bool
  )
    requires TelemetryStateSafe(state)
    ensures TelemetryStateSafe(
              ApplyTelemetryCompareAndSet(
                state,
                expectedGeneration,
                validationFailed
              )
            )
  {
  }
}

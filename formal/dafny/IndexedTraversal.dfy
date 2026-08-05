include "Semantics.dfy"

module IndexedTraversal {
  import Semantics

  datatype OptionalEid =
    | NoBound
    | Bound(value: int)

  datatype Projection =
    | SubjectToResources(
        subjectType: string,
        subjectEid: int,
        relationEid: int,
        resourceType: string,
        bound: OptionalEid
      )
    | ResourceToSubjects(
        resourceType: string,
        resourceEid: int,
        relationEid: int,
        subjectType: string,
        bound: OptionalEid
      )

  datatype ScanCommand = ScanCommand(
    requestScope: nat,
    requestId: nat,
    projection: Projection,
    chunkSize: nat
  )

  datatype ScanResponse = ScanResponse(
    requestScope: nat,
    requestId: nat,
    values: seq<int>,
    terminal: bool,
    fetchedValues: nat
  )

  datatype ScanError =
    | InvalidCommand
    | MismatchedRequestScope(expected: nat, actual: nat)
    | MismatchedRequest(expected: nat, actual: nat)
    | OversizedChunk(actual: nat, maximum: nat)
    | InvalidFetchedCount(actual: nat, expected: nat)
    | NonProgressingResponse
    | InvalidEid(index: nat)
    | OutOfOrder(index: nat)
    | BoundViolation(index: nat)

  datatype ValidatedScan =
    | ScanAccepted(values: seq<int>, terminal: bool, fetchedValues: nat)
    | ScanRejected(error: ScanError)

  datatype ForwardContinuation =
    | ForwardGrant(node: Semantics.PermissionNode)
    | ForwardArrowRelation(
        node: Semantics.PermissionNode,
        intermediateType: string,
        viaRelationEid: int,
        resourceType: string
      )

  datatype ReverseContinuation =
    | ReverseGrant(
        node: Semantics.PermissionNode,
        resourceEid: int,
        subjectType: string
      )
    | ReverseArrowRelation(
        node: Semantics.PermissionNode,
        resourceEid: int,
        subjectType: string,
        intermediateType: string,
        targetRelationEid: int
      )
    | ReverseArrowPermission(
        node: Semantics.PermissionNode,
        resourceEid: int,
        targetNode: Semantics.PermissionNode
      )

  datatype IndexedRule =
    | RelationRule(
        head: Semantics.PermissionNode,
        relationEid: int,
        subjectType: string
      )
    | SelfPermissionRule(
        head: Semantics.PermissionNode,
        targetNode: Semantics.PermissionNode
      )
    | ArrowRelationRule(
        head: Semantics.PermissionNode,
        viaRelationEid: int,
        intermediateType: string,
        targetRelationEid: int,
        targetSubjectType: string
      )
    | ArrowPermissionRule(
        head: Semantics.PermissionNode,
        viaRelationEid: int,
        intermediateType: string,
        targetNode: Semantics.PermissionNode
      )

  datatype CompiledTraversalPlan = CompiledTraversalPlan(
    rules: seq<IndexedRule>,
    forwardConsumers:
    map<Semantics.PermissionNode, seq<IndexedRule>>,
    rulesByNode:
    map<Semantics.PermissionNode, seq<IndexedRule>>
  )

  datatype ForwardStream = ForwardStream(
    projection: Projection,
    buffered: seq<int>,
    more: bool,
    continuation: ForwardContinuation
  )

  datatype ReverseStream = ReverseStream(
    projection: Projection,
    buffered: seq<int>,
    more: bool,
    continuation: ReverseContinuation
  )

  datatype ForwardGrantKey = ForwardGrantKey(
    node: Semantics.PermissionNode,
    resourceEid: int
  )

  datatype ReverseGoalKey = ReverseGoalKey(
    node: Semantics.PermissionNode,
    resourceEid: int
  )

  datatype ReverseGrantKey = ReverseGrantKey(
    node: Semantics.PermissionNode,
    resourceEid: int,
    subjectType: string,
    subjectEid: int
  )

  datatype ReverseConsumer = ReverseConsumer(
    node: Semantics.PermissionNode,
    resourceEid: int
  )

  datatype ReverseConsumerRegistration = ReverseConsumerRegistration(
    key: ReverseGoalKey,
    consumer: ReverseConsumer
  )

  datatype ForwardWork =
    | ForwardStreamWork(stream: ForwardStream)
    | ForwardGrantWork(grant: ForwardGrantKey)

  datatype ReverseWork =
    | ReverseStreamWork(stream: ReverseStream)
    | ReverseGoalWork(goal: ReverseGoalKey)
    | ReverseRegisterConsumerWork(
        key: ReverseGoalKey,
        consumer: ReverseConsumer
      )
    | ReverseGrantWork(grant: ReverseGrantKey)

  datatype ForwardPending =
    | NoForwardPending
    | AwaitingForwardScan(
        command: ScanCommand,
        continuation: ForwardContinuation
      )

  datatype ReversePending =
    | NoReversePending
    | AwaitingReverseScan(
        command: ScanCommand,
        continuation: ReverseContinuation
      )

  datatype CursorBound =
    | NoCursorBound
    | AfterCursor(ordinal: nat, eid: int)

  datatype RenderMode =
    | RenderPage(size: nat, after: CursorBound)
    | RenderBackwardPage(size: nat, before: CursorBound)
    | RenderCount(limit: nat)
    | RenderAllCount
    | RenderBoolean(targetEid: int)

  datatype RenderState = RenderState(
    mode: RenderMode,
    ordinal: nat,
    emitted: seq<int>,
    delivered: seq<int>,
    boundMatched: bool,
    complete: bool,
    truncated: bool
  )

  datatype RenderError =
    | CursorSkipped(expectedOrdinal: nat, actualOrdinal: nat)
    | CursorResultMismatch(
        ordinal: nat,
        expectedEid: int,
        actualEid: int
      )

  datatype RenderAdvance =
    | RenderProgress(state: RenderState, delivered: bool)
    | RenderRejected(error: RenderError)

  datatype PublicRenderResult =
    | PageReady(
        items: seq<int>,
        startOrdinal: nat,
        hasNext: bool,
        hasPrevious: bool
      )
    | CountReady(count: nat, truncated: bool)
    | BooleanReady(allowed: bool)

  // These are logical, dimensionally distinct measures. In particular,
  // currentQueueDepth is not cumulativeEnqueues. Retained logical state is
  // measured structurally by ForwardRetainedLogicalUnits and
  // ReverseRetainedLogicalUnits below; neither measure claims JVM/JavaScript
  // heap bytes or assigns constant target cost to an abstract set, map, or
  // sequence operation. Target collection implementations require a separate
  // refinement argument and host-runtime regression gates.
  datatype ResourceCounters = ResourceCounters(
    backendCommands: nat,
    adapterFetchedValues: nat,
    engineConsumedValues: nat,
    cumulativeEnqueues: nat,
    currentQueueDepth: nat,
    maximumQueueDepth: nat,
    uniqueGrants: nat,
    emittedResults: nat,
    ruleApplications: nat,
    consumerGrantJoins: nat,
    renderAdvances: nat
  )

  datatype RequestSequence = RequestSequence(
    scope: nat,
    next: nat
  )

  datatype ForwardState = ForwardState(
    queue: seq<ForwardWork>,
    consumers: map<Semantics.PermissionNode, seq<IndexedRule>>,
    seen: set<ForwardGrantKey>,
    emitted: set<int>,
    render: RenderState,
    rootNode: Semantics.PermissionNode,
    resultType: string,
    chunkSize: nat,
    pending: ForwardPending,
    nextRequestId: RequestSequence,
    counters: ResourceCounters
  )

  datatype ReverseState = ReverseState(
    queue: seq<ReverseWork>,
    rulesByNode: map<Semantics.PermissionNode, seq<IndexedRule>>,
    seenGoals: set<ReverseGoalKey>,
    seenGrants: set<ReverseGrantKey>,
    grantsByGoal: map<ReverseGoalKey, seq<ReverseGrantKey>>,
    consumers: map<ReverseGoalKey, seq<ReverseConsumer>>,
    seenConsumers: set<ReverseConsumerRegistration>,
    emitted: set<int>,
    render: RenderState,
    rootNode: Semantics.PermissionNode,
    rootResourceEid: int,
    resultType: string,
    subjectType: string,
    chunkSize: nat,
    pending: ReversePending,
    nextRequestId: RequestSequence,
    counters: ResourceCounters
  )

  datatype IndexedLimitKind =
    | IndexedDerivedGrants
    | IndexedAdvancedDatoms
    | IndexedQueuedWork

  datatype IndexedLimits = IndexedLimits(
    maxDerivedGrants: nat,
    maxAdvancedDatoms: nat,
    maxQueuedWork: nat
  )

  datatype ForwardResume =
    | ForwardScanResumed(state: ForwardState)
    | ForwardScanRejected(error: ScanError)
    | ForwardScanLimitExceeded(
        kind: IndexedLimitKind,
        state: ForwardState
      )

  datatype ReverseResume =
    | ReverseScanResumed(state: ReverseState)
    | ReverseScanRejected(error: ScanError)
    | ReverseScanLimitExceeded(
        kind: IndexedLimitKind,
        state: ReverseState
      )

  datatype ForwardStep =
    | ForwardAdvanced(state: ForwardState)
    | ForwardYielded(state: ForwardState)
    | ForwardNeedScan(state: ForwardState, command: ScanCommand)
    | ForwardEmitted(
        state: ForwardState,
        eid: int,
        ordinal: nat
      )
    | ForwardComplete(state: ForwardState)
    | ForwardRenderRejected(error: RenderError, state: ForwardState)
    | ForwardStepLimitExceeded(
        kind: IndexedLimitKind,
        state: ForwardState
      )

  datatype ReverseStep =
    | ReverseAdvanced(state: ReverseState)
    | ReverseYielded(state: ReverseState)
    | ReverseNeedScan(state: ReverseState, command: ScanCommand)
    | ReverseEmitted(
        state: ReverseState,
        eid: int,
        ordinal: nat
      )
    | ReverseComplete(state: ReverseState)
    | ReverseRenderRejected(error: RenderError, state: ReverseState)
    | ReverseStepLimitExceeded(
        kind: IndexedLimitKind,
        state: ReverseState
      )

  datatype ForwardInit =
    | ForwardInitialized(state: ForwardState)
    | ForwardInitLimitExceeded(kind: IndexedLimitKind)

  datatype ReverseInit =
    | ReverseInitialized(state: ReverseState)
    | ReverseInitLimitExceeded(kind: IndexedLimitKind)

  datatype PageContinuationError =
    | InvalidContinuationSize
    | ContinuationNotForwardPage
    | ContinuationNotComplete
    | ContinuationHasNoLookahead
    | ContinuationHasPendingScan
    | ContinuationBoundaryMismatch

  datatype ForwardPageContinuation =
    | ForwardPageContinued(state: ForwardState)
    | ForwardPageContinuationRejected(error: PageContinuationError)

  datatype ReversePageContinuation =
    | ReversePageContinued(state: ReverseState)
    | ReversePageContinuationRejected(error: PageContinuationError)

  predicate ValidRenderMode(mode: RenderMode) {
    match mode
    case RenderPage(size, after) =>
      0 < size &&
      (after.AfterCursor? ==> 0 <= after.eid)
    case RenderBackwardPage(size, before) =>
      0 < size &&
      before.AfterCursor? &&
      0 <= before.eid
    case RenderCount(_) => true
    case RenderAllCount => true
    case RenderBoolean(targetEid) => 0 <= targetEid
  }

  predicate UniqueEids(values: seq<int>) {
    forall left, right | 0 <= left < right < |values| ::
      values[left] != values[right]
  }

  predicate ValidRenderState(render: RenderState) {
    ValidRenderMode(render.mode) &&
    (render.mode.RenderAllCount? ||
     render.ordinal == |render.emitted|) &&
    (forall eid <- render.emitted :: 0 <= eid) &&
    UniqueEids(render.emitted) &&
    (set eid <- render.delivered) <=
    (set eid <- render.emitted) &&
    (forall eid <- render.delivered ::
       eid in render.emitted) &&
    UniqueEids(render.delivered) &&
    match render.mode
    case RenderPage(size, after) =>
      (after.NoCursorBound? ==> render.boundMatched) &&
      |render.delivered| <= size + 1 &&
      (!render.complete ==> |render.delivered| <= size) &&
      !render.truncated
    case RenderBackwardPage(size, before) =>
      before.AfterCursor? &&
      |render.delivered| <= size + 1 &&
      |render.delivered| <= render.ordinal &&
      |render.delivered| <= before.ordinal &&
      (render.boundMatched ==>
         render.ordinal == before.ordinal + 1) &&
      (!render.boundMatched ==> render.ordinal <= before.ordinal) &&
      (render.boundMatched ==> render.complete) &&
      !render.truncated
    case RenderCount(limit) =>
      |render.delivered| <= limit &&
      (render.truncated ==> render.complete)
    case RenderAllCount =>
      render.emitted == [] &&
      render.delivered == [] &&
      !render.truncated
    case RenderBoolean(_) =>
      |render.delivered| <= 1 &&
      (|render.delivered| == 1 ==> render.complete) &&
      !render.truncated
  }

  lemma CompletedBackwardRenderConsumesAuthenticatedPrefix(
    render: RenderState
  )
    requires ValidRenderState(render)
    requires render.mode.RenderBackwardPage?
    requires render.complete
    requires render.boundMatched
    ensures render.ordinal == render.mode.before.ordinal + 1
    ensures |render.emitted| == render.mode.before.ordinal + 1
  {
  }

  function BackwardReplayBoundCounterexampleOrdinal(
    pageSize: nat,
    multiplier: nat
  ): nat
    requires 0 < pageSize
  {
    multiplier * pageSize
  }

  lemma NoUniformBackwardReplayPageBound(
    pageSize: nat,
    multiplier: nat
  )
    requires 0 < pageSize
    ensures
      BackwardReplayBoundCounterexampleOrdinal(
        pageSize,
        multiplier
      ) + 1 > multiplier * pageSize
  {
  }

  function ProjectionBound(projection: Projection): OptionalEid {
    match projection
    case SubjectToResources(_, _, _, _, bound) => bound
    case ResourceToSubjects(_, _, _, _, bound) => bound
  }

  predicate ValidProjection(projection: Projection) {
    match projection
    case SubjectToResources(
      subjectType,
      subjectEid,
      relationEid,
      resourceType,
      bound
      ) =>
      0 < |subjectType| &&
      0 <= subjectEid &&
      0 <= relationEid &&
      0 < |resourceType| &&
      (bound.Bound? ==> 0 <= bound.value)
    case ResourceToSubjects(
      resourceType,
      resourceEid,
      relationEid,
      subjectType,
      bound
      ) =>
      0 < |resourceType| &&
      0 <= resourceEid &&
      0 <= relationEid &&
      0 < |subjectType| &&
      (bound.Bound? ==> 0 <= bound.value)
  }

  predicate StrictlyIncreasing(values: seq<int>) {
    forall left, right | 0 <= left < right < |values| ::
      values[left] < values[right]
  }

  predicate AdjacentStrictlyIncreasing(values: seq<int>) {
    forall index | 0 < index < |values| ::
      values[index - 1] < values[index]
  }

  lemma AdjacentIncreasingBetween(
    values: seq<int>,
    left: int,
    right: int
  )
    requires AdjacentStrictlyIncreasing(values)
    requires 0 <= left < right < |values|
    ensures values[left] < values[right]
    decreases right - left
  {
    if left + 1 < right {
      AdjacentIncreasingBetween(values, left, right - 1);
      assert values[left] < values[right - 1];
      assert values[right - 1] < values[right];
    }
  }

  lemma StrictlyIncreasingIffAdjacent(values: seq<int>)
    ensures StrictlyIncreasing(values) <==>
            AdjacentStrictlyIncreasing(values)
  {
    if StrictlyIncreasing(values) {
      forall index | 0 < index < |values|
        ensures values[index - 1] < values[index]
      {
      }
    }
    if AdjacentStrictlyIncreasing(values) {
      forall left, right | 0 <= left < right < |values|
        ensures values[left] < values[right]
      {
        AdjacentIncreasingBetween(values, left, right);
      }
    }
  }

  predicate ValuesAboveBound(
    values: seq<int>,
    bound: OptionalEid
  ) {
    bound.Bound? ==>
      forall index | 0 <= index < |values| ::
        bound.value < values[index]
  }

  predicate ValidScanResponse(
    command: ScanCommand,
    response: ScanResponse
  ) {
    ValidProjection(command.projection) &&
    0 < command.chunkSize &&
    response.requestScope == command.requestScope &&
    response.requestId == command.requestId &&
    |response.values| <= command.chunkSize &&
    (response.terminal
     ==> response.fetchedValues == |response.values|) &&
    (!response.terminal
     ==> |response.values| == command.chunkSize &&
         response.fetchedValues == |response.values| + 1) &&
    (response.terminal || 0 < |response.values|) &&
    (forall index | 0 <= index < |response.values| ::
       0 <= response.values[index]) &&
    StrictlyIncreasing(response.values) &&
    ValuesAboveBound(
      response.values,
      ProjectionBound(command.projection)
    )
  }

  function FirstInvalidEid(values: seq<int>): nat
    requires exists index | 0 <= index < |values| ::
               values[index] < 0
    ensures FirstInvalidEid(values) < |values|
    ensures values[FirstInvalidEid(values)] < 0
    decreases |values|
  {
    if values[0] < 0 then
      0
    else
      1 + FirstInvalidEid(values[1..])
  }

  function FirstOutOfOrder(values: seq<int>): nat
    requires !StrictlyIncreasing(values)
    ensures 0 < FirstOutOfOrder(values) < |values|
    ensures values[FirstOutOfOrder(values) - 1] >=
            values[FirstOutOfOrder(values)]
    decreases |values|
  {
    if values[0] >= values[1] then
      1
    else
      1 + FirstOutOfOrder(values[1..])
  }

  function FirstBoundViolation(
    values: seq<int>,
    bound: OptionalEid
  ): nat
    requires !ValuesAboveBound(values, bound)
    ensures FirstBoundViolation(values, bound) < |values|
    ensures bound.Bound?
    ensures values[FirstBoundViolation(values, bound)] <= bound.value
    decreases |values|
  {
    if values[0] <= bound.value then
      0
    else
      1 + FirstBoundViolation(values[1..], bound)
  }

  function ValidateScanResponseSpec(
    command: ScanCommand,
    response: ScanResponse
  ): ValidatedScan
  {
    if !ValidProjection(command.projection) ||
       command.chunkSize == 0
    then ScanRejected(InvalidCommand)
    else if response.requestScope != command.requestScope
      then
        ScanRejected(
          MismatchedRequestScope(
            command.requestScope,
            response.requestScope
          )
        )
      else if response.requestId != command.requestId
        then
          ScanRejected(
            MismatchedRequest(command.requestId, response.requestId)
          )
        else if |response.values| > command.chunkSize
          then
            ScanRejected(
              OversizedChunk(|response.values|, command.chunkSize)
            )
          else if response.terminal &&
                  response.fetchedValues != |response.values|
            then
              ScanRejected(
                InvalidFetchedCount(
                  response.fetchedValues,
                  |response.values|
                )
              )
            else if !response.terminal && |response.values| == 0
              then ScanRejected(NonProgressingResponse)
              else if !response.terminal &&
                      (|response.values| != command.chunkSize ||
                       response.fetchedValues != |response.values| + 1)
                then
                  ScanRejected(
                    InvalidFetchedCount(
                      response.fetchedValues,
                      |response.values| + 1
                    )
                  )
                else if exists index | 0 <= index < |response.values| ::
                          response.values[index] < 0
                  then
                    ScanRejected(
                      InvalidEid(FirstInvalidEid(response.values))
                    )
                  else if !StrictlyIncreasing(response.values)
                    then
                      ScanRejected(
                        OutOfOrder(FirstOutOfOrder(response.values))
                      )
                    else if !ValuesAboveBound(
                              response.values,
                              ProjectionBound(command.projection)
                            )
                      then
                        ScanRejected(
                          BoundViolation(
                            FirstBoundViolation(
                              response.values,
                              ProjectionBound(command.projection)
                            )
                          )
                        )
                      else
                        ScanAccepted(
                          response.values,
                          response.terminal,
                          response.fetchedValues
                        )
  }

  method ValidateScanResponse(
    command: ScanCommand,
    response: ScanResponse
  ) returns (result: ValidatedScan)
    ensures result == ValidateScanResponseSpec(command, response)
    ensures result.ScanAccepted? <==>
            ValidScanResponse(command, response)
    ensures result.ScanAccepted? ==>
              result.values == response.values &&
              result.terminal == response.terminal &&
              result.fetchedValues == response.fetchedValues
  {
    if !ValidProjection(command.projection) ||
       command.chunkSize == 0 {
      return ScanRejected(InvalidCommand);
    }
    if response.requestScope != command.requestScope {
      return ScanRejected(
          MismatchedRequestScope(
            command.requestScope,
            response.requestScope
          )
        );
    }
    if response.requestId != command.requestId {
      return ScanRejected(
          MismatchedRequest(command.requestId, response.requestId)
        );
    }
    if |response.values| > command.chunkSize {
      return ScanRejected(
          OversizedChunk(|response.values|, command.chunkSize)
        );
    }
    if response.terminal &&
       response.fetchedValues != |response.values| {
      return ScanRejected(
          InvalidFetchedCount(
            response.fetchedValues,
            |response.values|
          )
        );
    }
    if !response.terminal && |response.values| == 0 {
      return ScanRejected(NonProgressingResponse);
    }
    if !response.terminal &&
       (|response.values| != command.chunkSize ||
        response.fetchedValues != |response.values| + 1) {
      return ScanRejected(
          InvalidFetchedCount(
            response.fetchedValues,
            |response.values| + 1
          )
        );
    }
    if exists index | 0 <= index < |response.values| ::
        response.values[index] < 0 {
      return ScanRejected(
          InvalidEid(FirstInvalidEid(response.values))
        );
    }
    StrictlyIncreasingIffAdjacent(response.values);
    if !AdjacentStrictlyIncreasing(response.values) {
      assert !StrictlyIncreasing(response.values);
      return ScanRejected(
          OutOfOrder(FirstOutOfOrder(response.values))
        );
    }
    if !ValuesAboveBound(
        response.values,
        ProjectionBound(command.projection)
      ) {
      return ScanRejected(
          BoundViolation(
            FirstBoundViolation(
              response.values,
              ProjectionBound(command.projection)
            )
          )
        );
    }
    return ScanAccepted(
        response.values,
        response.terminal,
        response.fetchedValues
      );
  }

  method RecordBackendResponse(
    counters: ResourceCounters,
    response: ValidatedScan
  ) returns (updated: ResourceCounters)
    requires response.ScanAccepted?
    ensures updated.backendCommands == counters.backendCommands + 1
    ensures updated.adapterFetchedValues ==
            counters.adapterFetchedValues + response.fetchedValues
    ensures updated.engineConsumedValues ==
            counters.engineConsumedValues
    ensures updated.cumulativeEnqueues == counters.cumulativeEnqueues
    ensures updated.currentQueueDepth == counters.currentQueueDepth
    ensures updated.maximumQueueDepth == counters.maximumQueueDepth
    ensures updated.uniqueGrants == counters.uniqueGrants
    ensures updated.emittedResults == counters.emittedResults
  {
    return ResourceCounters(
        counters.backendCommands + 1,
        counters.adapterFetchedValues + response.fetchedValues,
        counters.engineConsumedValues,
        counters.cumulativeEnqueues,
        counters.currentQueueDepth,
        counters.maximumQueueDepth,
        counters.uniqueGrants,
        counters.emittedResults,
        counters.ruleApplications,
        counters.consumerGrantJoins,
        counters.renderAdvances
      );
  }

  method RecordEnqueue(
    counters: ResourceCounters,
    amount: nat
  ) returns (updated: ResourceCounters)
    ensures updated.cumulativeEnqueues ==
            counters.cumulativeEnqueues + amount
    ensures updated.currentQueueDepth ==
            counters.currentQueueDepth + amount
    ensures updated.maximumQueueDepth ==
            if counters.maximumQueueDepth <
               counters.currentQueueDepth + amount
            then counters.currentQueueDepth + amount
            else counters.maximumQueueDepth
    ensures updated.maximumQueueDepth >= updated.currentQueueDepth
  {
    var depth := counters.currentQueueDepth + amount;
    return ResourceCounters(
        counters.backendCommands,
        counters.adapterFetchedValues,
        counters.engineConsumedValues,
        counters.cumulativeEnqueues + amount,
        depth,
        if counters.maximumQueueDepth < depth
        then depth
        else counters.maximumQueueDepth,
        counters.uniqueGrants,
        counters.emittedResults,
        counters.ruleApplications,
        counters.consumerGrantJoins,
        counters.renderAdvances
      );
  }

  method RecordDequeue(
    counters: ResourceCounters
  ) returns (updated: ResourceCounters)
    requires 0 < counters.currentQueueDepth
    requires counters.maximumQueueDepth >= counters.currentQueueDepth
    ensures updated.currentQueueDepth ==
            counters.currentQueueDepth - 1
    ensures updated.maximumQueueDepth == counters.maximumQueueDepth
    ensures updated.cumulativeEnqueues == counters.cumulativeEnqueues
    ensures updated.maximumQueueDepth >= updated.currentQueueDepth
  {
    return ResourceCounters(
        counters.backendCommands,
        counters.adapterFetchedValues,
        counters.engineConsumedValues,
        counters.cumulativeEnqueues,
        counters.currentQueueDepth - 1,
        counters.maximumQueueDepth,
        counters.uniqueGrants,
        counters.emittedResults,
        counters.ruleApplications,
        counters.consumerGrantJoins,
        counters.renderAdvances
      );
  }

  function ProjectionAfterChunk(
    projection: Projection,
    values: seq<int>
  ): Projection
    requires 0 < |values|
  {
    var bound := Bound(values[|values| - 1]);
    match projection
    case SubjectToResources(
      subjectType,
      subjectEid,
      relationEid,
      resourceType,
      _
      ) =>
      SubjectToResources(
        subjectType,
        subjectEid,
        relationEid,
        resourceType,
        bound
      )
    case ResourceToSubjects(
      resourceType,
      resourceEid,
      relationEid,
      subjectType,
      _
      ) =>
      ResourceToSubjects(
        resourceType,
        resourceEid,
        relationEid,
        subjectType,
        bound
      )
  }

  function ContinueForward(
    continuation: ForwardContinuation,
    eid: int
  ): seq<ForwardWork>
    requires 0 <= eid
    ensures ValidForwardQueuedEids(ContinueForward(continuation, eid))
  {
    match continuation
    case ForwardGrant(node) =>
      [ForwardGrantWork(ForwardGrantKey(node, eid))]
    case ForwardArrowRelation(
      node,
      intermediateType,
      viaRelationEid,
      resourceType
      ) =>
      [ForwardStreamWork(
         ForwardStream(
           SubjectToResources(
             intermediateType,
             eid,
             viaRelationEid,
             resourceType,
             NoBound
           ),
           [],
           true,
           ForwardGrant(node)
         )
       )]
  }

  function ContinueReverse(
    continuation: ReverseContinuation,
    eid: int
  ): seq<ReverseWork>
    requires 0 <= eid
    requires ValidReverseContinuationEids(continuation)
    ensures ValidReverseQueuedEids(ContinueReverse(continuation, eid))
  {
    match continuation
    case ReverseGrant(node, resourceEid, subjectType) =>
      [ReverseGrantWork(
         ReverseGrantKey(node, resourceEid, subjectType, eid)
       )]
    case ReverseArrowRelation(
      node,
      resourceEid,
      subjectType,
      intermediateType,
      targetRelationEid
      ) =>
      [ReverseStreamWork(
         ReverseStream(
           ResourceToSubjects(
             intermediateType,
             eid,
             targetRelationEid,
             subjectType,
             NoBound
           ),
           [],
           true,
           ReverseGrant(node, resourceEid, subjectType)
         )
       )]
    case ReverseArrowPermission(
      node,
      resourceEid,
      targetNode
      ) =>
      [ReverseRegisterConsumerWork(
         ReverseGoalKey(targetNode, eid),
         ReverseConsumer(node, resourceEid)
       ),
       ReverseGoalWork(ReverseGoalKey(targetNode, eid))]
  }

  function ForwardWorkAfterResponse(
    command: ScanCommand,
    continuation: ForwardContinuation,
    response: ValidatedScan
  ): seq<ForwardWork>
    requires response.ScanAccepted?
    requires 0 < |response.values|
    requires forall index | 0 <= index < |response.values| ::
               0 <= response.values[index]
    ensures ValidForwardQueuedEids(
              ForwardWorkAfterResponse(command, continuation, response)
            )
  {
    var continuationWork :=
      ContinueForward(continuation, response.values[0]);
    var streamWork :=
      if 1 < |response.values| || !response.terminal
      then
        [ForwardStreamWork(
           ForwardStream(
             ProjectionAfterChunk(
               command.projection,
               response.values
             ),
             response.values[1..],
             !response.terminal,
             continuation
           )
         )]
      else [];
    continuationWork + streamWork
  }

  function ReverseWorkAfterResponse(
    command: ScanCommand,
    continuation: ReverseContinuation,
    response: ValidatedScan
  ): seq<ReverseWork>
    requires response.ScanAccepted?
    requires 0 < |response.values|
    requires forall index | 0 <= index < |response.values| ::
               0 <= response.values[index]
    requires ValidReverseContinuationEids(continuation)
    ensures ValidReverseQueuedEids(
              ReverseWorkAfterResponse(command, continuation, response)
            )
  {
    var continuationWork :=
      ContinueReverse(continuation, response.values[0]);
    var streamWork :=
      if 1 < |response.values| || !response.terminal
      then
        [ReverseStreamWork(
           ReverseStream(
             ProjectionAfterChunk(
               command.projection,
               response.values
             ),
             response.values[1..],
             !response.terminal,
             continuation
           )
         )]
      else [];
    continuationWork + streamWork
  }

  function ForwardBufferedValues(
    queue: seq<ForwardWork>
  ): nat
    decreases |queue|
  {
    if |queue| == 0 then
      0
    else
      (if queue[0].ForwardStreamWork?
       then |queue[0].stream.buffered|
       else 0) +
      ForwardBufferedValues(queue[1..])
  }

  function ReverseBufferedValues(
    queue: seq<ReverseWork>
  ): nat
    decreases |queue|
  {
    if |queue| == 0 then
      0
    else
      (if queue[0].ReverseStreamWork?
       then |queue[0].stream.buffered|
       else 0) +
      ReverseBufferedValues(queue[1..])
  }

  predicate ForwardStateInvariant(state: ForwardState) {
    0 < state.chunkSize &&
    state.counters.currentQueueDepth == |state.queue| &&
    state.counters.maximumQueueDepth >=
    state.counters.currentQueueDepth &&
    state.counters.currentQueueDepth <=
    state.counters.cumulativeEnqueues &&
    state.counters.uniqueGrants == |state.seen| &&
    state.counters.uniqueGrants +
    state.counters.currentQueueDepth <=
    state.counters.cumulativeEnqueues &&
    state.counters.emittedResults == |state.emitted| &&
    state.counters.emittedResults <= state.counters.uniqueGrants &&
    (forall grant <- state.seen :: 0 <= grant.resourceEid) &&
    (forall node <- state.consumers.Keys ::
       forall rule <- state.consumers[node] ::
         ValidIndexedRule(rule)) &&
    ValidRenderState(state.render) &&
    state.render.ordinal == |state.emitted| &&
    (state.render.mode.RenderAllCount? ||
     (state.emitted == (set eid <- state.render.emitted) &&
      |state.emitted| == |state.render.emitted|)) &&
    (state.pending.AwaitingForwardScan? ==>
       state.pending.command.chunkSize == state.chunkSize &&
       state.pending.command.requestScope ==
       state.nextRequestId.scope &&
       state.pending.command.requestId < state.nextRequestId.next)
  }

  predicate ReverseStateInvariant(state: ReverseState) {
    0 < state.chunkSize &&
    state.counters.currentQueueDepth == |state.queue| &&
    state.counters.maximumQueueDepth >=
    state.counters.currentQueueDepth &&
    state.counters.currentQueueDepth <=
    state.counters.cumulativeEnqueues &&
    state.counters.uniqueGrants == |state.seenGrants| &&
    state.counters.uniqueGrants +
    state.counters.currentQueueDepth <=
    state.counters.cumulativeEnqueues &&
    state.counters.emittedResults == |state.emitted| &&
    state.counters.emittedResults <= state.counters.uniqueGrants &&
    (forall goal <- state.seenGoals :: 0 <= goal.resourceEid) &&
    (forall grant <- state.seenGrants ::
       0 <= grant.resourceEid && 0 <= grant.subjectEid) &&
    (forall registration <- state.seenConsumers ::
       0 <= registration.key.resourceEid &&
       0 <= registration.consumer.resourceEid) &&
    (forall node <- state.rulesByNode.Keys ::
       forall rule <- state.rulesByNode[node] ::
         ValidIndexedRule(rule)) &&
    (forall key <- state.grantsByGoal.Keys ::
       0 <= key.resourceEid &&
       forall grant <- state.grantsByGoal[key] ::
         0 <= grant.resourceEid &&
         0 <= grant.subjectEid) &&
    (forall key <- state.consumers.Keys ::
       0 <= key.resourceEid &&
       forall consumer <- state.consumers[key] ::
         0 <= consumer.resourceEid) &&
    ValidRenderState(state.render) &&
    state.render.ordinal == |state.emitted| &&
    (state.render.mode.RenderAllCount? ||
     (state.emitted == (set eid <- state.render.emitted) &&
      |state.emitted| == |state.render.emitted|)) &&
    (state.pending.AwaitingReverseScan? ==>
       state.pending.command.chunkSize == state.chunkSize &&
       state.pending.command.requestScope ==
       state.nextRequestId.scope &&
       state.pending.command.requestId < state.nextRequestId.next &&
       ValidReverseContinuationEids(state.pending.continuation))
  }

  predicate CountersWithinLimits(
    counters: ResourceCounters,
    limits: IndexedLimits
  ) {
    counters.uniqueGrants <= limits.maxDerivedGrants &&
    counters.engineConsumedValues <= limits.maxAdvancedDatoms &&
    counters.currentQueueDepth <= limits.maxQueuedWork &&
    counters.maximumQueueDepth <= limits.maxQueuedWork
  }

  function CountersAfterEmptyResponse(
    counters: ResourceCounters,
    response: ValidatedScan
  ): ResourceCounters
    requires response.ScanAccepted?
    requires |response.values| == 0
  {
    ResourceCounters(
      counters.backendCommands + 1,
      counters.adapterFetchedValues + response.fetchedValues,
      counters.engineConsumedValues,
      counters.cumulativeEnqueues,
      counters.currentQueueDepth,
      counters.maximumQueueDepth,
      counters.uniqueGrants,
      counters.emittedResults,
      counters.ruleApplications,
      counters.consumerGrantJoins,
      counters.renderAdvances
    )
  }

  function CountersAfterConsumedResponse(
    counters: ResourceCounters,
    response: ValidatedScan,
    enqueued: nat
  ): ResourceCounters
    requires response.ScanAccepted?
    requires 0 < |response.values|
  {
    var depth := counters.currentQueueDepth + enqueued;
    ResourceCounters(
      counters.backendCommands + 1,
      counters.adapterFetchedValues + response.fetchedValues,
      counters.engineConsumedValues + 1,
      counters.cumulativeEnqueues + enqueued,
      depth,
      if counters.maximumQueueDepth < depth
      then depth
      else counters.maximumQueueDepth,
      counters.uniqueGrants,
      counters.emittedResults,
      counters.ruleApplications,
      counters.consumerGrantJoins,
      counters.renderAdvances
    )
  }

  predicate ForwardResumeRelation(
    before: ForwardState,
    response: ScanResponse,
    outcome: ForwardResume
  )
    requires ForwardStateInvariant(before)
    requires before.pending.AwaitingForwardScan?
  {
    match outcome
    case ForwardScanResumed(after) =>
      ForwardSemanticFrame(before, after) &&
      after.seen == before.seen &&
      after.emitted == before.emitted &&
      after.pending.NoForwardPending? &&
      ValidScanResponse(before.pending.command, response) &&
      (
        (|response.values| == 0 &&
         after.queue == before.queue) ||
        (0 < |response.values| &&
         after.queue ==
         before.queue +
         ForwardWorkAfterResponse(
           before.pending.command,
           before.pending.continuation,
           ScanAccepted(
             response.values,
             response.terminal,
             response.fetchedValues
           )
         ))
      )
    case ForwardScanRejected(_) =>
      !ValidScanResponse(before.pending.command, response)
    case ForwardScanLimitExceeded(_, after) =>
      after == before
  }

  function ForwardResumeSpec(
    state: ForwardState,
    response: ScanResponse,
    limits: IndexedLimits
  ): ForwardResume
    requires ForwardStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.AwaitingForwardScan?
    ensures ForwardResumeRelation(
              state,
              response,
              ForwardResumeSpec(state, response, limits)
            )
    ensures ForwardResumeSpec(
              state,
              response,
              limits
            ).ForwardScanResumed? ==>
              ForwardStateInvariant(
                ForwardResumeSpec(
                  state,
                  response,
                  limits
                ).state
              ) &&
              CountersWithinLimits(
                ForwardResumeSpec(
                  state,
                  response,
                  limits
                ).state.counters,
                limits
              )
  {
    var pending := state.pending;
    var validated :=
      ValidateScanResponseSpec(pending.command, response);
    if validated.ScanRejected?
    then ForwardScanRejected(validated.error)
    else if |validated.values| == 0
      then
        var counters :=
          CountersAfterEmptyResponse(state.counters, validated);
        ForwardScanResumed(
          ForwardState(
            state.queue,
            state.consumers,
            state.seen,
            state.emitted,
            state.render,
            state.rootNode,
            state.resultType,
            state.chunkSize,
            NoForwardPending,
            state.nextRequestId,
            counters
          )
        )
      else if state.counters.engineConsumedValues + 1 >
              limits.maxAdvancedDatoms
        then
          ForwardScanLimitExceeded(
            IndexedAdvancedDatoms,
            state
          )
        else
          var work :=
            ForwardWorkAfterResponse(
              pending.command,
              pending.continuation,
              validated
            );
          if state.counters.currentQueueDepth + |work| >
             limits.maxQueuedWork
          then
            ForwardScanLimitExceeded(
              IndexedQueuedWork,
              state
            )
          else
            var counters :=
              CountersAfterConsumedResponse(
                state.counters,
                validated,
                |work|
              );
            ForwardScanResumed(
              ForwardState(
                state.queue + work,
                state.consumers,
                state.seen,
                state.emitted,
                state.render,
                state.rootNode,
                state.resultType,
                state.chunkSize,
                NoForwardPending,
                state.nextRequestId,
                counters
              )
            )
  }

  method ResumeForwardScan(
    state: ForwardState,
    response: ScanResponse,
    limits: IndexedLimits
  ) returns (outcome: ForwardResume)
    requires ForwardStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.AwaitingForwardScan?
    ensures outcome == ForwardResumeSpec(state, response, limits)
    ensures ForwardResumeRelation(state, response, outcome)
    ensures outcome.ForwardScanResumed? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits)
    ensures outcome.ForwardScanLimitExceeded? ==>
              CountersWithinLimits(outcome.state.counters, limits)
  {
    var pending := state.pending;
    var validated :=
      ValidateScanResponse(pending.command, response);
    if validated.ScanRejected? {
      return ForwardScanRejected(validated.error);
    }

    if |validated.values| == 0 {
      var counters :=
        CountersAfterEmptyResponse(state.counters, validated);
      return ForwardScanResumed(
          ForwardState(
            state.queue,
            state.consumers,
            state.seen,
            state.emitted,
            state.render,
            state.rootNode,
            state.resultType,
            state.chunkSize,
            NoForwardPending,
            state.nextRequestId,
            counters
          )
        );
    }

    if state.counters.engineConsumedValues + 1 >
       limits.maxAdvancedDatoms {
      return ForwardScanLimitExceeded(
          IndexedAdvancedDatoms,
          state
        );
    }

    var work :=
      ForwardWorkAfterResponse(
        pending.command,
        pending.continuation,
        validated
      );
    if state.counters.currentQueueDepth + |work| >
       limits.maxQueuedWork {
      return ForwardScanLimitExceeded(
          IndexedQueuedWork,
          state
        );
    }

    var counters :=
      CountersAfterConsumedResponse(
        state.counters,
        validated,
        |work|
      );
    return ForwardScanResumed(
        ForwardState(
          state.queue + work,
          state.consumers,
          state.seen,
          state.emitted,
          state.render,
          state.rootNode,
          state.resultType,
          state.chunkSize,
          NoForwardPending,
          state.nextRequestId,
          counters
        )
      );
  }

  predicate ReverseResumeRelation(
    before: ReverseState,
    response: ScanResponse,
    outcome: ReverseResume
  )
    requires ReverseStateInvariant(before)
    requires before.pending.AwaitingReverseScan?
  {
    match outcome
    case ReverseScanResumed(after) =>
      ReverseSemanticFrame(before, after) &&
      after.seenGoals == before.seenGoals &&
      after.seenGrants == before.seenGrants &&
      after.grantsByGoal == before.grantsByGoal &&
      after.consumers == before.consumers &&
      after.seenConsumers == before.seenConsumers &&
      after.emitted == before.emitted &&
      after.pending.NoReversePending? &&
      ValidScanResponse(before.pending.command, response) &&
      (
        (|response.values| == 0 &&
         after.queue == before.queue) ||
        (0 < |response.values| &&
         after.queue ==
         before.queue +
         ReverseWorkAfterResponse(
           before.pending.command,
           before.pending.continuation,
           ScanAccepted(
             response.values,
             response.terminal,
             response.fetchedValues
           )
         ))
      )
    case ReverseScanRejected(_) =>
      !ValidScanResponse(before.pending.command, response)
    case ReverseScanLimitExceeded(_, after) =>
      after == before
  }

  function ReverseResumeSpec(
    state: ReverseState,
    response: ScanResponse,
    limits: IndexedLimits
  ): ReverseResume
    requires ReverseStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.AwaitingReverseScan?
    ensures ReverseResumeRelation(
              state,
              response,
              ReverseResumeSpec(state, response, limits)
            )
    ensures ReverseResumeSpec(
              state,
              response,
              limits
            ).ReverseScanResumed? ==>
              ReverseStateInvariant(
                ReverseResumeSpec(
                  state,
                  response,
                  limits
                ).state
              ) &&
              CountersWithinLimits(
                ReverseResumeSpec(
                  state,
                  response,
                  limits
                ).state.counters,
                limits
              )
  {
    var pending := state.pending;
    var validated :=
      ValidateScanResponseSpec(pending.command, response);
    if validated.ScanRejected?
    then ReverseScanRejected(validated.error)
    else if |validated.values| == 0
      then
        var counters :=
          CountersAfterEmptyResponse(state.counters, validated);
        ReverseScanResumed(
          ReverseState(
            state.queue,
            state.rulesByNode,
            state.seenGoals,
            state.seenGrants,
            state.grantsByGoal,
            state.consumers,
            state.seenConsumers,
            state.emitted,
            state.render,
            state.rootNode,
            state.rootResourceEid,
            state.resultType,
            state.subjectType,
            state.chunkSize,
            NoReversePending,
            state.nextRequestId,
            counters
          )
        )
      else if state.counters.engineConsumedValues + 1 >
              limits.maxAdvancedDatoms
        then
          ReverseScanLimitExceeded(
            IndexedAdvancedDatoms,
            state
          )
        else
          var work :=
            ReverseWorkAfterResponse(
              pending.command,
              pending.continuation,
              validated
            );
          if state.counters.currentQueueDepth + |work| >
             limits.maxQueuedWork
          then
            ReverseScanLimitExceeded(
              IndexedQueuedWork,
              state
            )
          else
            var counters :=
              CountersAfterConsumedResponse(
                state.counters,
                validated,
                |work|
              );
            ReverseScanResumed(
              ReverseState(
                state.queue + work,
                state.rulesByNode,
                state.seenGoals,
                state.seenGrants,
                state.grantsByGoal,
                state.consumers,
                state.seenConsumers,
                state.emitted,
                state.render,
                state.rootNode,
                state.rootResourceEid,
                state.resultType,
                state.subjectType,
                state.chunkSize,
                NoReversePending,
                state.nextRequestId,
                counters
              )
            )
  }

  method ResumeReverseScan(
    state: ReverseState,
    response: ScanResponse,
    limits: IndexedLimits
  ) returns (outcome: ReverseResume)
    requires ReverseStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.AwaitingReverseScan?
    ensures outcome == ReverseResumeSpec(state, response, limits)
    ensures ReverseResumeRelation(state, response, outcome)
    ensures outcome.ReverseScanResumed? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits)
    ensures outcome.ReverseScanLimitExceeded? ==>
              CountersWithinLimits(outcome.state.counters, limits)
  {
    var pending := state.pending;
    var validated :=
      ValidateScanResponse(pending.command, response);
    if validated.ScanRejected? {
      return ReverseScanRejected(validated.error);
    }

    if |validated.values| == 0 {
      var counters :=
        CountersAfterEmptyResponse(state.counters, validated);
      return ReverseScanResumed(
          ReverseState(
            state.queue,
            state.rulesByNode,
            state.seenGoals,
            state.seenGrants,
            state.grantsByGoal,
            state.consumers,
            state.seenConsumers,
            state.emitted,
            state.render,
            state.rootNode,
            state.rootResourceEid,
            state.resultType,
            state.subjectType,
            state.chunkSize,
            NoReversePending,
            state.nextRequestId,
            counters
          )
        );
    }

    if state.counters.engineConsumedValues + 1 >
       limits.maxAdvancedDatoms {
      return ReverseScanLimitExceeded(
          IndexedAdvancedDatoms,
          state
        );
    }

    var work :=
      ReverseWorkAfterResponse(
        pending.command,
        pending.continuation,
        validated
      );
    if state.counters.currentQueueDepth + |work| >
       limits.maxQueuedWork {
      return ReverseScanLimitExceeded(
          IndexedQueuedWork,
          state
        );
    }

    var counters :=
      CountersAfterConsumedResponse(
        state.counters,
        validated,
        |work|
      );
    return ReverseScanResumed(
        ReverseState(
          state.queue + work,
          state.rulesByNode,
          state.seenGoals,
          state.seenGrants,
          state.grantsByGoal,
          state.consumers,
          state.seenConsumers,
          state.emitted,
          state.render,
          state.rootNode,
          state.rootResourceEid,
          state.resultType,
          state.subjectType,
          state.chunkSize,
          NoReversePending,
          state.nextRequestId,
          counters
        )
      );
  }

  function ForwardConsumerWork(
    rule: IndexedRule,
    grant: ForwardGrantKey
  ): seq<ForwardWork>
    requires ValidIndexedRule(rule)
    requires 0 <= grant.resourceEid
    ensures ValidForwardQueuedEids(ForwardConsumerWork(rule, grant))
  {
    match rule
    case SelfPermissionRule(head, _) =>
      [ForwardGrantWork(ForwardGrantKey(head, grant.resourceEid))]
    case ArrowPermissionRule(
      head,
      viaRelationEid,
      intermediateType,
      _
      ) =>
      [ForwardStreamWork(
         ForwardStream(
           SubjectToResources(
             intermediateType,
             grant.resourceEid,
             viaRelationEid,
             head.resourceType,
             NoBound
           ),
           [],
           true,
           ForwardGrant(head)
         )
       )]
    case RelationRule(_, _, _) => []
    case ArrowRelationRule(_, _, _, _, _) => []
  }

  function ForwardConsumerWorks(
    rules: seq<IndexedRule>,
    grant: ForwardGrantKey
  ): seq<ForwardWork>
    requires ValidIndexedRules(rules)
    requires 0 <= grant.resourceEid
    ensures ValidForwardQueuedEids(ForwardConsumerWorks(rules, grant))
    decreases |rules|
  {
    if |rules| == 0 then
      []
    else
      ForwardConsumerWork(rules[0], grant) +
      ForwardConsumerWorks(rules[1..], grant)
  }

  function ForwardBufferedWork(
    stream: ForwardStream
  ): seq<ForwardWork>
    requires 0 < |stream.buffered|
    requires forall index | 0 <= index < |stream.buffered| ::
               0 <= stream.buffered[index]
    ensures ValidForwardQueuedEids(ForwardBufferedWork(stream))
  {
    var continuationWork :=
      ContinueForward(stream.continuation, stream.buffered[0]);
    var streamWork :=
      if 1 < |stream.buffered| || stream.more
      then
        [ForwardStreamWork(
           ForwardStream(
             stream.projection,
             stream.buffered[1..],
             stream.more,
             stream.continuation
           )
         )]
      else [];
    continuationWork + streamWork
  }

  function CountersAfterQueueStep(
    counters: ResourceCounters,
    consumedValues: nat,
    enqueued: nat,
    uniqueGrants: nat,
    emittedResults: nat,
    ruleApplications: nat,
    consumerGrantJoins: nat,
    renderAdvances: nat
  ): ResourceCounters
    requires 0 < counters.currentQueueDepth
  {
    var depth := counters.currentQueueDepth - 1 + enqueued;
    ResourceCounters(
      counters.backendCommands,
      counters.adapterFetchedValues,
      counters.engineConsumedValues + consumedValues,
      counters.cumulativeEnqueues + enqueued,
      depth,
      if counters.maximumQueueDepth < depth
      then depth
      else counters.maximumQueueDepth,
      counters.uniqueGrants + uniqueGrants,
      counters.emittedResults + emittedResults,
      counters.ruleApplications + ruleApplications,
      counters.consumerGrantJoins + consumerGrantJoins,
      counters.renderAdvances + renderAdvances
    )
  }

  function UpdateForwardState(
    state: ForwardState,
    queue: seq<ForwardWork>,
    seen: set<ForwardGrantKey>,
    emitted: set<int>,
    render: RenderState,
    pending: ForwardPending,
    nextRequestId: RequestSequence,
    counters: ResourceCounters
  ): ForwardState
  {
    ForwardState(
      queue,
      state.consumers,
      seen,
      emitted,
      render,
      state.rootNode,
      state.resultType,
      state.chunkSize,
      pending,
      nextRequestId,
      counters
    )
  }

  predicate ValidForwardQueuedEids(queue: seq<ForwardWork>) {
    (forall work <- queue ::
       work.ForwardStreamWork? ==>
         (forall index | 0 <= index < |work.stream.buffered| ::
            0 <= work.stream.buffered[index])) &&
    (forall work <- queue ::
       work.ForwardGrantWork? ==> 0 <= work.grant.resourceEid)
  }

  predicate ValidReverseContinuationEids(
    continuation: ReverseContinuation
  ) {
    match continuation
    case ReverseGrant(_, resourceEid, _) =>
      0 <= resourceEid
    case ReverseArrowRelation(
      _,
      resourceEid,
      _,
      _,
      targetRelationEid
      ) =>
      0 <= resourceEid && 0 <= targetRelationEid
    case ReverseArrowPermission(_, resourceEid, _) =>
      0 <= resourceEid
  }

  predicate ValidReverseQueuedEids(queue: seq<ReverseWork>) {
    (forall work <- queue ::
       work.ReverseStreamWork? ==>
         (forall index | 0 <= index < |work.stream.buffered| ::
            0 <= work.stream.buffered[index]) &&
         ValidReverseContinuationEids(work.stream.continuation)) &&
    (forall work <- queue ::
       work.ReverseGoalWork? ==>
         0 <= work.goal.resourceEid) &&
    (forall work <- queue ::
       work.ReverseRegisterConsumerWork? ==>
         0 <= work.key.resourceEid &&
         0 <= work.consumer.resourceEid) &&
    (forall work <- queue ::
       work.ReverseGrantWork? ==>
         0 <= work.grant.resourceEid &&
         0 <= work.grant.subjectEid)
  }

  lemma UniqueSequenceTail(values: seq<int>)
    requires 0 < |values|
    requires UniqueEids(values)
    ensures UniqueEids(values[1..])
  {
  }

  lemma AppendFreshEid(values: seq<int>, eid: int)
    requires UniqueEids(values)
    requires eid !in values
    ensures UniqueEids(values + [eid])
  {
  }

  function AppendFreshEidValue(
    values: seq<int>,
    eid: int
  ): seq<int>
    requires UniqueEids(values)
    requires eid !in values
    ensures AppendFreshEidValue(values, eid) == values + [eid]
    ensures UniqueEids(AppendFreshEidValue(values, eid))
    ensures (set value <- AppendFreshEidValue(values, eid)) ==
            (set value <- values) + {eid}
  {
    values + [eid]
  }

  function BoundedSequenceTail(
    values: seq<int>,
    maximum: nat
  ): seq<int>
    requires UniqueEids(values)
    ensures UniqueEids(BoundedSequenceTail(values, maximum))
    ensures (set value <- BoundedSequenceTail(values, maximum)) <=
            (set value <- values)
  {
    if |values| <= maximum
    then values
    else values[1..]
  }

  function AdvanceRenderSpec(
    render: RenderState,
    eid: int
  ): RenderAdvance
    requires ValidRenderState(render)
    requires 0 <= eid
    requires eid !in render.emitted
    requires eid !in render.delivered
    requires !render.complete
    ensures AdvanceRenderSpec(render, eid).RenderProgress? ==>
              ValidRenderState(AdvanceRenderSpec(render, eid).state) &&
              AdvanceRenderSpec(render, eid).state.ordinal ==
              render.ordinal + 1 &&
              AdvanceRenderSpec(render, eid).state.emitted ==
              (if render.mode.RenderAllCount?
               then render.emitted
               else render.emitted + [eid])
  {
    var traversed :=
      if render.mode.RenderAllCount?
      then render.emitted
      else AppendFreshEidValue(render.emitted, eid);
    var deliveredWithEid :=
      AppendFreshEidValue(render.delivered, eid);
    match render.mode
    case RenderPage(size, after) =>
      if !render.boundMatched
      then
        if after.NoCursorBound?
        then RenderRejected(CursorSkipped(0, render.ordinal))
        else if render.ordinal < after.ordinal
          then
            RenderProgress(
              RenderState(
                render.mode,
                render.ordinal + 1,
                traversed,
                render.delivered,
                false,
                false,
                false
              ),
              false
            )
          else if render.ordinal > after.ordinal
            then
              RenderRejected(
                CursorSkipped(after.ordinal, render.ordinal)
              )
            else if eid != after.eid
              then
                RenderRejected(
                  CursorResultMismatch(render.ordinal, after.eid, eid)
                )
              else
                RenderProgress(
                  RenderState(
                    render.mode,
                    render.ordinal + 1,
                    traversed,
                    render.delivered,
                    true,
                    false,
                    false
                  ),
                  false
                )
      else
        RenderProgress(
          RenderState(
            render.mode,
            render.ordinal + 1,
            traversed,
            deliveredWithEid,
            true,
            |deliveredWithEid| == size + 1,
            false
          ),
          true
        )
    case RenderBackwardPage(size, before) =>
      if before.NoCursorBound?
      then RenderRejected(CursorSkipped(0, render.ordinal))
      else if render.ordinal < before.ordinal
        then
          var appended := deliveredWithEid;
          var ring := BoundedSequenceTail(appended, size + 1);
          RenderProgress(
            RenderState(
              render.mode,
              render.ordinal + 1,
              traversed,
              ring,
              false,
              false,
              false
            ),
            false
          )
        else if render.ordinal > before.ordinal
          then
            RenderRejected(
              CursorSkipped(before.ordinal, render.ordinal)
            )
          else if eid != before.eid
            then
              RenderRejected(
                CursorResultMismatch(render.ordinal, before.eid, eid)
              )
            else
              RenderProgress(
                RenderState(
                  render.mode,
                  render.ordinal + 1,
                  traversed,
                  render.delivered,
                  true,
                  true,
                  false
                ),
                false
              )
    case RenderCount(limit) =>
      if |render.delivered| < limit
      then
        RenderProgress(
          RenderState(
            render.mode,
            render.ordinal + 1,
            traversed,
            deliveredWithEid,
            true,
            false,
            false
          ),
          true
        )
      else
        RenderProgress(
          RenderState(
            render.mode,
            render.ordinal + 1,
            traversed,
            render.delivered,
            true,
            true,
            true
          ),
          false
        )
    case RenderAllCount =>
      RenderProgress(
        RenderState(
          render.mode,
          render.ordinal + 1,
          traversed,
          render.delivered,
          true,
          false,
          false
        ),
        false
      )
    case RenderBoolean(targetEid) =>
      var matched := eid == targetEid;
      RenderProgress(
        RenderState(
          render.mode,
          render.ordinal + 1,
          traversed,
          if matched
          then deliveredWithEid
          else render.delivered,
          true,
          matched,
          false
        ),
        matched
      )
  }

  method AdvanceRender(
    render: RenderState,
    eid: int
  ) returns (outcome: RenderAdvance)
    requires ValidRenderState(render)
    requires 0 <= eid
    requires eid !in render.emitted
    requires eid !in render.delivered
    requires !render.complete
    ensures outcome == AdvanceRenderSpec(render, eid)
    ensures outcome.RenderProgress? ==>
              ValidRenderState(outcome.state) &&
              outcome.state.ordinal == render.ordinal + 1 &&
              outcome.state.emitted ==
              (if render.mode.RenderAllCount?
               then render.emitted
               else render.emitted + [eid])
  {
    var traversed :=
      if render.mode.RenderAllCount?
      then render.emitted
      else render.emitted + [eid];
    var deliveredWithEid := render.delivered + [eid];
    AppendFreshEid(render.emitted, eid);
    AppendFreshEid(render.delivered, eid);
    if render.mode.RenderAllCount? {
      assert traversed == render.emitted;
    } else {
      assert (set value <- traversed) ==
             (set value <- render.emitted) + {eid};
    }
    assert (set value <- deliveredWithEid) ==
           (set value <- render.delivered) + {eid};
    match render.mode
    case RenderPage(size, after) => {
      if !render.boundMatched {
        if after.NoCursorBound? {
          assert false;
        }
        if render.ordinal < after.ordinal {
          return RenderProgress(
              RenderState(
                render.mode,
                render.ordinal + 1,
                traversed,
                render.delivered,
                false,
                false,
                false
              ),
              false
            );
        }
        if render.ordinal > after.ordinal {
          return RenderRejected(
              CursorSkipped(after.ordinal, render.ordinal)
            );
        }
        if eid != after.eid {
          return RenderRejected(
              CursorResultMismatch(render.ordinal, after.eid, eid)
            );
        }
        return RenderProgress(
            RenderState(
              render.mode,
              render.ordinal + 1,
              traversed,
              render.delivered,
              true,
              false,
              false
            ),
            false
          );
      }

      var delivered := deliveredWithEid;
      return RenderProgress(
          RenderState(
            render.mode,
            render.ordinal + 1,
            traversed,
            delivered,
            true,
            |delivered| == size + 1,
            false
          ),
          true
        );
    }
    case RenderBackwardPage(size, before) => {
      if before.NoCursorBound? {
        assert false;
      }
      if render.ordinal < before.ordinal {
        var appended := deliveredWithEid;
        var ring :=
          if |appended| <= size + 1
          then appended
          else appended[1..];
        if |appended| > size + 1 {
          UniqueSequenceTail(appended);
        }
        assert UniqueEids(ring);
        return RenderProgress(
            RenderState(
              render.mode,
              render.ordinal + 1,
              traversed,
              ring,
              false,
              false,
              false
            ),
            false
          );
      }
      if render.ordinal > before.ordinal {
        return RenderRejected(
            CursorSkipped(before.ordinal, render.ordinal)
          );
      }
      if eid != before.eid {
        return RenderRejected(
            CursorResultMismatch(render.ordinal, before.eid, eid)
          );
      }
      return RenderProgress(
          RenderState(
            render.mode,
            render.ordinal + 1,
            traversed,
            render.delivered,
            true,
            true,
            false
          ),
          false
        );
    }
    case RenderCount(limit) => {
      if |render.delivered| < limit {
        return RenderProgress(
            RenderState(
              render.mode,
              render.ordinal + 1,
              traversed,
              deliveredWithEid,
              true,
              false,
              false
            ),
            true
          );
      }
      return RenderProgress(
          RenderState(
            render.mode,
            render.ordinal + 1,
            traversed,
            render.delivered,
            true,
            true,
            true
          ),
          false
        );
    }
    case RenderAllCount => {
      return RenderProgress(
          RenderState(
            render.mode,
            render.ordinal + 1,
            traversed,
            render.delivered,
            true,
            false,
            false
          ),
          false
        );
    }
    case RenderBoolean(targetEid) => {
      var matched := eid == targetEid;
      return RenderProgress(
          RenderState(
            render.mode,
            render.ordinal + 1,
            traversed,
            if matched
            then deliveredWithEid
            else render.delivered,
            true,
            matched,
            false
          ),
          matched
        );
    }
  }

  function FinishRenderSpec(
    render: RenderState
  ): RenderAdvance
    requires ValidRenderState(render)
  {
    if render.mode.RenderPage? && !render.boundMatched
    then
      RenderRejected(
        CursorSkipped(render.mode.after.ordinal, render.ordinal)
      )
    else if render.mode.RenderBackwardPage? && !render.boundMatched
      then
        RenderRejected(
          CursorSkipped(render.mode.before.ordinal, render.ordinal)
        )
      else
        RenderProgress(
          RenderState(
            render.mode,
            render.ordinal,
            render.emitted,
            render.delivered,
            render.boundMatched,
            true,
            render.truncated
          ),
          false
        )
  }

  method FinishRender(
    render: RenderState
  ) returns (outcome: RenderAdvance)
    requires ValidRenderState(render)
    ensures outcome == FinishRenderSpec(render)
    ensures outcome.RenderProgress? ==>
              ValidRenderState(outcome.state) &&
              outcome.state.complete &&
              outcome.state.ordinal == render.ordinal &&
              outcome.state.emitted == render.emitted &&
              outcome.state.delivered == render.delivered
  {
    if render.mode.RenderPage? && !render.boundMatched {
      if render.mode.after.AfterCursor? {
        return RenderRejected(
            CursorSkipped(render.mode.after.ordinal, render.ordinal)
          );
      }
      assert false;
    }
    if render.mode.RenderBackwardPage? && !render.boundMatched {
      if render.mode.before.AfterCursor? {
        return RenderRejected(
            CursorSkipped(render.mode.before.ordinal, render.ordinal)
          );
      }
      assert false;
    }
    return RenderProgress(
        RenderState(
          render.mode,
          render.ordinal,
          render.emitted,
          render.delivered,
          render.boundMatched,
          true,
          render.truncated
        ),
        false
      );
  }

  function ReadRenderResultSpec(
    render: RenderState
  ): PublicRenderResult
    requires ValidRenderState(render)
    requires render.complete
  {
    match render.mode
    case RenderPage(size, after) =>
      var items :=
        if |render.delivered| <= size
        then render.delivered
        else render.delivered[..size];
      var start :=
        if after.AfterCursor?
        then after.ordinal + 1
        else 0;
      PageReady(
        items,
        start,
        |render.delivered| > size,
        after.AfterCursor?
      )
    case RenderBackwardPage(size, before) =>
      var items :=
        if |render.delivered| <= size
        then render.delivered
        else render.delivered[1..];
      PageReady(
        items,
        before.ordinal - |items|,
        true,
        |render.delivered| > size
      )
    case RenderCount(_) =>
      CountReady(|render.delivered|, render.truncated)
    case RenderAllCount =>
      CountReady(render.ordinal, false)
    case RenderBoolean(_) =>
      BooleanReady(|render.delivered| == 1)
  }

  method ReadRenderResult(
    render: RenderState
  ) returns (result: PublicRenderResult)
    requires ValidRenderState(render)
    requires render.complete
    ensures result == ReadRenderResultSpec(render)
    ensures result.PageReady? ==>
              (render.mode.RenderPage? ||
               render.mode.RenderBackwardPage?) &&
              |result.items| <= render.mode.size
    ensures result.CountReady? ==>
              (render.mode.RenderCount? || render.mode.RenderAllCount?) &&
              (render.mode.RenderCount? ==>
                 result.count == |render.delivered|) &&
              (render.mode.RenderAllCount? ==>
                 result.count == render.ordinal)
    ensures result.BooleanReady? ==>
              render.mode.RenderBoolean? &&
              (result.allowed <==> |render.delivered| == 1)
  {
    match render.mode
    case RenderPage(size, after) => {
      var items :=
        if |render.delivered| <= size
        then render.delivered
        else render.delivered[..size];
      var start :=
        if after.AfterCursor?
        then after.ordinal + 1
        else 0;
      return PageReady(
          items,
          start,
          |render.delivered| > size,
          after.AfterCursor?
        );
    }
    case RenderBackwardPage(size, before) => {
      if before.NoCursorBound? {
        assert false;
      }
      var items :=
        if |render.delivered| <= size
        then render.delivered
        else render.delivered[1..];
      assert |items| <= before.ordinal;
      return PageReady(
          items,
          before.ordinal - |items|,
          true,
          |render.delivered| > size
        );
    }
    case RenderCount(_) => {
      return CountReady(|render.delivered|, render.truncated);
    }
    case RenderAllCount => {
      return CountReady(render.ordinal, false);
    }
    case RenderBoolean(_) => {
      return BooleanReady(|render.delivered| == 1);
    }
  }

  function ContinuedPageRender(
    render: RenderState,
    size: nat,
    afterOrdinal: nat,
    afterEid: int
  ): RenderState
    requires ValidRenderState(render)
    requires render.mode.RenderPage?
    requires render.complete
    requires 0 < size
    requires 0 < render.mode.size
    requires |render.delivered| == render.mode.size + 1
    requires 0 <= afterEid
    requires
      afterOrdinal ==
      (if render.mode.after.AfterCursor?
       then render.mode.after.ordinal + 1
       else 0) +
      render.mode.size - 1
    requires render.delivered[render.mode.size - 1] == afterEid
    ensures ValidRenderState(
              ContinuedPageRender(
                render,
                size,
                afterOrdinal,
                afterEid
              )
            )
    ensures
      ContinuedPageRender(
        render,
        size,
        afterOrdinal,
        afterEid
      ).ordinal == render.ordinal
    ensures
      ContinuedPageRender(
        render,
        size,
        afterOrdinal,
        afterEid
      ).emitted == render.emitted
    ensures
      ContinuedPageRender(
        render,
        size,
        afterOrdinal,
        afterEid
      ).delivered == [render.delivered[render.mode.size]]
  {
    RenderState(
      RenderPage(size, AfterCursor(afterOrdinal, afterEid)),
      render.ordinal,
      render.emitted,
      [render.delivered[render.mode.size]],
      true,
      false,
      false
    )
  }

  method ContinueForwardPage(
    state: ForwardState,
    size: nat,
    afterOrdinal: nat,
    afterEid: int
  ) returns (outcome: ForwardPageContinuation)
    requires ForwardStateInvariant(state)
    ensures outcome.ForwardPageContinued? ==>
              ForwardStateInvariant(outcome.state)
    ensures outcome.ForwardPageContinued? ==>
              ForwardSemanticFrame(state, outcome.state)
    ensures outcome.ForwardPageContinued? ==>
              outcome.state.queue == state.queue &&
              outcome.state.seen == state.seen &&
              outcome.state.emitted == state.emitted &&
              outcome.state.counters == state.counters &&
              outcome.state.render.ordinal == state.render.ordinal &&
              outcome.state.render.emitted == state.render.emitted
  {
    if size == 0 {
      return
        ForwardPageContinuationRejected(InvalidContinuationSize);
    }
    if !state.render.mode.RenderPage? {
      return
        ForwardPageContinuationRejected(ContinuationNotForwardPage);
    }
    if !state.render.complete {
      return
        ForwardPageContinuationRejected(ContinuationNotComplete);
    }
    if |state.render.delivered| != state.render.mode.size + 1 {
      return
        ForwardPageContinuationRejected(ContinuationHasNoLookahead);
    }
    if !state.pending.NoForwardPending? {
      return
        ForwardPageContinuationRejected(ContinuationHasPendingScan);
    }
    var start :=
      if state.render.mode.after.AfterCursor?
      then state.render.mode.after.ordinal + 1
      else 0;
    if afterEid < 0 ||
       afterOrdinal != start + state.render.mode.size - 1 ||
       state.render.delivered[state.render.mode.size - 1] != afterEid
    {
      return
        ForwardPageContinuationRejected(ContinuationBoundaryMismatch);
    }
    var render :=
      ContinuedPageRender(
        state.render,
        size,
        afterOrdinal,
        afterEid
      );
    var continued :=
      ForwardState(
        state.queue,
        state.consumers,
        state.seen,
        state.emitted,
        render,
        state.rootNode,
        state.resultType,
        state.chunkSize,
        state.pending,
        state.nextRequestId,
        state.counters
      );
    assert ForwardStateInvariant(continued);
    return ForwardPageContinued(continued);
  }

  method ContinueReversePage(
    state: ReverseState,
    size: nat,
    afterOrdinal: nat,
    afterEid: int
  ) returns (outcome: ReversePageContinuation)
    requires ReverseStateInvariant(state)
    ensures outcome.ReversePageContinued? ==>
              ReverseStateInvariant(outcome.state)
    ensures outcome.ReversePageContinued? ==>
              ReverseSemanticFrame(state, outcome.state)
    ensures outcome.ReversePageContinued? ==>
              outcome.state.queue == state.queue &&
              outcome.state.seenGoals == state.seenGoals &&
              outcome.state.seenGrants == state.seenGrants &&
              outcome.state.emitted == state.emitted &&
              outcome.state.counters == state.counters &&
              outcome.state.render.ordinal == state.render.ordinal &&
              outcome.state.render.emitted == state.render.emitted
  {
    if size == 0 {
      return
        ReversePageContinuationRejected(InvalidContinuationSize);
    }
    if !state.render.mode.RenderPage? {
      return
        ReversePageContinuationRejected(ContinuationNotForwardPage);
    }
    if !state.render.complete {
      return
        ReversePageContinuationRejected(ContinuationNotComplete);
    }
    if |state.render.delivered| != state.render.mode.size + 1 {
      return
        ReversePageContinuationRejected(ContinuationHasNoLookahead);
    }
    if !state.pending.NoReversePending? {
      return
        ReversePageContinuationRejected(ContinuationHasPendingScan);
    }
    var start :=
      if state.render.mode.after.AfterCursor?
      then state.render.mode.after.ordinal + 1
      else 0;
    if afterEid < 0 ||
       afterOrdinal != start + state.render.mode.size - 1 ||
       state.render.delivered[state.render.mode.size - 1] != afterEid
    {
      return
        ReversePageContinuationRejected(ContinuationBoundaryMismatch);
    }
    var render :=
      ContinuedPageRender(
        state.render,
        size,
        afterOrdinal,
        afterEid
      );
    var continued :=
      ReverseState(
        state.queue,
        state.rulesByNode,
        state.seenGoals,
        state.seenGrants,
        state.grantsByGoal,
        state.consumers,
        state.seenConsumers,
        state.emitted,
        render,
        state.rootNode,
        state.rootResourceEid,
        state.resultType,
        state.subjectType,
        state.chunkSize,
        state.pending,
        state.nextRequestId,
        state.counters
      );
    assert ReverseStateInvariant(continued);
    return ReversePageContinued(continued);
  }

  predicate ForwardSemanticFrame(
    before: ForwardState,
    after: ForwardState
  ) {
    after.consumers == before.consumers &&
    after.rootNode == before.rootNode &&
    after.resultType == before.resultType &&
    after.chunkSize == before.chunkSize
  }

  predicate ForwardSuccessfulGrantTransition(
    before: ForwardState,
    after: ForwardState,
    grant: ForwardGrantKey
  )
    requires ForwardStateInvariant(before)
    requires ValidForwardQueuedEids(before.queue)
    requires 0 < |before.queue|
    requires before.queue[0].ForwardGrantWork?
    requires grant == before.queue[0].grant
  {
    var rest := before.queue[1..];
    var consumerRules :=
      if grant.node in before.consumers
      then before.consumers[grant.node]
      else [];
    ForwardSemanticFrame(before, after) &&
    grant !in before.seen &&
    after.queue ==
    rest + ForwardConsumerWorks(consumerRules, grant) &&
    after.seen == before.seen + {grant} &&
    after.emitted ==
    (if grant.node == before.rootNode &&
        grant.resourceEid !in before.emitted
     then before.emitted + {grant.resourceEid}
     else before.emitted) &&
    after.pending.NoForwardPending?
  }

  predicate ForwardStepRelation(
    before: ForwardState,
    outcome: ForwardStep
  )
    requires ForwardStateInvariant(before)
    requires ValidForwardQueuedEids(before.queue)
  {
    match outcome
    case ForwardAdvanced(after) =>
      ForwardSemanticFrame(before, after) &&
      0 < |before.queue| &&
      (
        (before.queue[0].ForwardStreamWork? &&
         |before.queue[0].stream.buffered| == 0 &&
         !before.queue[0].stream.more &&
         after.queue == before.queue[1..] &&
         after.seen == before.seen &&
         after.emitted == before.emitted &&
         after.pending.NoForwardPending?) ||
        (before.queue[0].ForwardStreamWork? &&
         0 < |before.queue[0].stream.buffered| &&
         after.queue ==
         before.queue[1..] +
         ForwardBufferedWork(before.queue[0].stream) &&
         after.seen == before.seen &&
         after.emitted == before.emitted &&
         after.pending.NoForwardPending?) ||
        (before.queue[0].ForwardGrantWork? &&
         before.queue[0].grant in before.seen &&
         after.queue == before.queue[1..] &&
         after.seen == before.seen &&
         after.emitted == before.emitted &&
         after.pending.NoForwardPending?) ||
        (before.queue[0].ForwardGrantWork? &&
         ForwardSuccessfulGrantTransition(
           before,
           after,
           before.queue[0].grant
         ))
      )
    case ForwardYielded(after) =>
      after == before
    case ForwardNeedScan(after, command) =>
      ForwardSemanticFrame(before, after) &&
      0 < |before.queue| &&
      before.queue[0].ForwardStreamWork? &&
      |before.queue[0].stream.buffered| == 0 &&
      before.queue[0].stream.more &&
      after.queue == before.queue[1..] &&
      after.seen == before.seen &&
      after.emitted == before.emitted &&
      after.pending.AwaitingForwardScan? &&
      after.pending.command == command &&
      command.projection == before.queue[0].stream.projection &&
      after.pending.continuation ==
      before.queue[0].stream.continuation
    case ForwardEmitted(after, eid, ordinal) =>
      0 < |before.queue| &&
      before.queue[0].ForwardGrantWork? &&
      eid == before.queue[0].grant.resourceEid &&
      ForwardSuccessfulGrantTransition(
        before,
        after,
        before.queue[0].grant
      )
    case ForwardComplete(after) =>
      ForwardSemanticFrame(before, after) &&
      after.queue == before.queue &&
      after.seen == before.seen &&
      after.emitted == before.emitted &&
      after.pending == before.pending
    case ForwardRenderRejected(_, after) =>
      after == before
    case ForwardStepLimitExceeded(_, after) =>
      after == before
  }

  function ForwardStepRuleApplicationCost(
    state: ForwardState
  ): nat {
    if state.render.complete || |state.queue| == 0
    then 0
    else if state.queue[0].ForwardGrantWork? &&
            state.queue[0].grant !in state.seen
      then
        if state.queue[0].grant.node in state.consumers
        then |state.consumers[state.queue[0].grant.node]|
        else 0
      else 0
  }

  function ForwardStepConsumerJoinCost(
    state: ForwardState
  ): nat {
    ForwardStepRuleApplicationCost(state)
  }

  function ForwardStepRenderAdvanceCost(
    state: ForwardState
  ): nat {
    if state.render.complete || |state.queue| == 0
    then 0
    else if state.queue[0].ForwardGrantWork? &&
            state.queue[0].grant !in state.seen &&
            state.queue[0].grant.node == state.rootNode &&
            state.queue[0].grant.resourceEid !in state.emitted
      then 1
      else 0
  }

  function ForwardStepSpec(
    state: ForwardState,
    limits: IndexedLimits
  ): ForwardStep
    requires ForwardStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.NoForwardPending?
    requires ValidForwardQueuedEids(state.queue)
    ensures ForwardStateInvariant(
              ForwardStepSpec(state, limits).state
            )
    ensures CountersWithinLimits(
              ForwardStepSpec(state, limits).state.counters,
              limits
            )
    ensures ValidForwardQueuedEids(
              ForwardStepSpec(state, limits).state.queue
            )
    ensures ForwardStepRelation(
              state,
              ForwardStepSpec(state, limits)
            )
    ensures ForwardStepSpec(state, limits).ForwardAdvanced? ==>
              ForwardStateInvariant(
                ForwardStepSpec(state, limits).state
              ) &&
              CountersWithinLimits(
                ForwardStepSpec(state, limits).state.counters,
                limits
              ) &&
              ForwardStepSpec(
                state,
                limits
              ).state.pending.NoForwardPending? &&
              ValidForwardQueuedEids(
                ForwardStepSpec(state, limits).state.queue
              )
    ensures ForwardStepSpec(state, limits).ForwardEmitted? ==>
              ForwardStateInvariant(
                ForwardStepSpec(state, limits).state
              ) &&
              CountersWithinLimits(
                ForwardStepSpec(state, limits).state.counters,
                limits
              ) &&
              ForwardStepSpec(
                state,
                limits
              ).state.pending.NoForwardPending? &&
              ValidForwardQueuedEids(
                ForwardStepSpec(state, limits).state.queue
              )
  {
    if state.render.complete
    then ForwardComplete(state)
    else if |state.queue| == 0
      then
        var finished := FinishRenderSpec(state.render);
        if finished.RenderRejected?
        then ForwardRenderRejected(finished.error, state)
        else
          ForwardComplete(
            UpdateForwardState(
              state,
              state.queue,
              state.seen,
              state.emitted,
              finished.state,
              state.pending,
              state.nextRequestId,
              state.counters
            )
          )
      else
        var work := state.queue[0];
        var rest := state.queue[1..];
        if work.ForwardStreamWork?
        then
          var stream := work.stream;
          if |stream.buffered| == 0
          then
            var counters :=
              CountersAfterQueueStep(
                state.counters, 0, 0, 0, 0, 0, 0, 0
              );
            if !stream.more
            then
              ForwardAdvanced(
                UpdateForwardState(
                  state,
                  rest,
                  state.seen,
                  state.emitted,
                  state.render,
                  NoForwardPending,
                  state.nextRequestId,
                  counters
                )
              )
            else
              var command :=
                ScanCommand(
                  state.nextRequestId.scope,
                  state.nextRequestId.next,
                  stream.projection,
                  state.chunkSize
                );
              ForwardNeedScan(
                UpdateForwardState(
                  state,
                  rest,
                  state.seen,
                  state.emitted,
                  state.render,
                  AwaitingForwardScan(command, stream.continuation),
                  RequestSequence(
                    state.nextRequestId.scope,
                    state.nextRequestId.next + 1
                  ),
                  counters
                ),
                command
              )
          else if state.counters.engineConsumedValues + 1 >
                  limits.maxAdvancedDatoms
            then
              ForwardStepLimitExceeded(
                IndexedAdvancedDatoms,
                state
              )
            else
              var generated := ForwardBufferedWork(stream);
              if |rest| + |generated| > limits.maxQueuedWork
              then ForwardStepLimitExceeded(IndexedQueuedWork, state)
              else
                var counters :=
                  CountersAfterQueueStep(
                    state.counters,
                    1,
                    |generated|,
                    0,
                    0,
                    0,
                    0,
                    0
                  );
                ForwardAdvanced(
                  UpdateForwardState(
                    state,
                    rest + generated,
                    state.seen,
                    state.emitted,
                    state.render,
                    NoForwardPending,
                    state.nextRequestId,
                    counters
                  )
                )
        else
          var grant := work.grant;
          if grant in state.seen
          then
            var counters :=
              CountersAfterQueueStep(
                state.counters, 0, 0, 0, 0, 0, 0, 0
              );
            ForwardAdvanced(
              UpdateForwardState(
                state,
                rest,
                state.seen,
                state.emitted,
                state.render,
                NoForwardPending,
                state.nextRequestId,
                counters
              )
            )
          else if state.counters.uniqueGrants + 1 >
                  limits.maxDerivedGrants
            then
              ForwardStepLimitExceeded(
                IndexedDerivedGrants,
                state
              )
            else
              var consumerRules :=
                if grant.node in state.consumers
                then state.consumers[grant.node]
                else [];
              var generated :=
                ForwardConsumerWorks(consumerRules, grant);
              if |rest| + |generated| > limits.maxQueuedWork
              then ForwardStepLimitExceeded(IndexedQueuedWork, state)
              else
                var seen := state.seen + {grant};
                var rootResult :=
                  grant.node == state.rootNode &&
                  grant.resourceEid !in state.emitted;
                var emitted :=
                  if rootResult
                  then state.emitted + {grant.resourceEid}
                  else state.emitted;
                if rootResult
                then
                  var renderOutcome :=
                    AdvanceRenderSpec(
                      state.render,
                      grant.resourceEid
                    );
                  if renderOutcome.RenderRejected?
                  then ForwardRenderRejected(renderOutcome.error, state)
                  else
                    var counters :=
                      CountersAfterQueueStep(
                        state.counters,
                        0,
                        |generated|,
                        1,
                        1,
                        |consumerRules|,
                        |consumerRules|,
                        1
                      );
                    var updated :=
                      UpdateForwardState(
                        state,
                        rest + generated,
                        seen,
                        emitted,
                        renderOutcome.state,
                        NoForwardPending,
                        state.nextRequestId,
                        counters
                      );
                    if renderOutcome.delivered
                    then
                      ForwardEmitted(
                        updated,
                        grant.resourceEid,
                        state.render.ordinal
                      )
                    else ForwardAdvanced(updated)
                else
                  var counters :=
                    CountersAfterQueueStep(
                      state.counters,
                      0,
                      |generated|,
                      1,
                      0,
                      |consumerRules|,
                      |consumerRules|,
                      0
                    );
                  ForwardAdvanced(
                    UpdateForwardState(
                      state,
                      rest + generated,
                      seen,
                      emitted,
                      state.render,
                      NoForwardPending,
                      state.nextRequestId,
                      counters
                    )
                  )
  }

  method StepForward(
    state: ForwardState,
    limits: IndexedLimits
  ) returns (outcome: ForwardStep)
    requires ForwardStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.NoForwardPending?
    requires ValidForwardQueuedEids(state.queue)
    ensures outcome == ForwardStepSpec(state, limits)
    ensures ForwardStepRelation(state, outcome)
    ensures !outcome.ForwardRenderRejected? &&
            !outcome.ForwardStepLimitExceeded? ==>
              outcome.state.counters.ruleApplications ==
              state.counters.ruleApplications +
              ForwardStepRuleApplicationCost(state) &&
              outcome.state.counters.consumerGrantJoins ==
              state.counters.consumerGrantJoins +
              ForwardStepConsumerJoinCost(state) &&
              outcome.state.counters.renderAdvances ==
              state.counters.renderAdvances +
              ForwardStepRenderAdvanceCost(state)
    ensures outcome.ForwardAdvanced? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              outcome.state.pending.NoForwardPending? &&
              ValidForwardQueuedEids(outcome.state.queue)
    ensures outcome.ForwardYielded? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidForwardQueuedEids(outcome.state.queue)
    ensures outcome.ForwardNeedScan? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidForwardQueuedEids(outcome.state.queue) &&
              outcome.state.pending.AwaitingForwardScan? &&
              outcome.command == outcome.state.pending.command
    ensures outcome.ForwardEmitted? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              outcome.state.pending.NoForwardPending? &&
              ValidForwardQueuedEids(outcome.state.queue) &&
              outcome.eid in outcome.state.emitted &&
              outcome.ordinal + 1 == outcome.state.render.ordinal
    ensures outcome.ForwardComplete? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidForwardQueuedEids(outcome.state.queue)
    ensures outcome.ForwardRenderRejected? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidForwardQueuedEids(outcome.state.queue)
    ensures outcome.ForwardStepLimitExceeded? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidForwardQueuedEids(outcome.state.queue)
  {
    if state.render.complete {
      return ForwardComplete(state);
    }
    if |state.queue| == 0 {
      var finished := FinishRender(state.render);
      if finished.RenderRejected? {
        return ForwardRenderRejected(finished.error, state);
      }
      return ForwardComplete(
          UpdateForwardState(
            state,
            state.queue,
            state.seen,
            state.emitted,
            finished.state,
            state.pending,
            state.nextRequestId,
            state.counters
          )
        );
    }

    var work := state.queue[0];
    var rest := state.queue[1..];
    if work.ForwardStreamWork? {
      var stream := work.stream;
      if |stream.buffered| == 0 {
        var counters :=
          CountersAfterQueueStep(
            state.counters, 0, 0, 0, 0, 0, 0, 0
          );
        if !stream.more {
          return ForwardAdvanced(
              UpdateForwardState(
                state,
                rest,
                state.seen,
                state.emitted,
                state.render,
                NoForwardPending,
                state.nextRequestId,
                counters
              )
            );
        }

        var command :=
          ScanCommand(
            state.nextRequestId.scope,
            state.nextRequestId.next,
            stream.projection,
            state.chunkSize
          );
        return ForwardNeedScan(
            UpdateForwardState(
              state,
              rest,
              state.seen,
              state.emitted,
              state.render,
              AwaitingForwardScan(command, stream.continuation),
              RequestSequence(
                state.nextRequestId.scope,
                state.nextRequestId.next + 1
              ),
              counters
            ),
            command
          );
      }

      if state.counters.engineConsumedValues + 1 >
         limits.maxAdvancedDatoms {
        return ForwardStepLimitExceeded(
            IndexedAdvancedDatoms,
            state
          );
      }
      var generated := ForwardBufferedWork(stream);
      if |rest| + |generated| > limits.maxQueuedWork {
        return ForwardStepLimitExceeded(IndexedQueuedWork, state);
      }
      var counters :=
        CountersAfterQueueStep(
          state.counters,
          1,
          |generated|,
          0,
          0,
          0,
          0,
          0
        );
      return ForwardAdvanced(
          UpdateForwardState(
            state,
            rest + generated,
            state.seen,
            state.emitted,
            state.render,
            NoForwardPending,
            state.nextRequestId,
            counters
          )
        );
    }

    var grant := work.grant;
    if grant in state.seen {
      var counters :=
        CountersAfterQueueStep(
          state.counters, 0, 0, 0, 0, 0, 0, 0
        );
      return ForwardAdvanced(
          UpdateForwardState(
            state,
            rest,
            state.seen,
            state.emitted,
            state.render,
            NoForwardPending,
            state.nextRequestId,
            counters
          )
        );
    }

    if state.counters.uniqueGrants + 1 >
       limits.maxDerivedGrants {
      return ForwardStepLimitExceeded(
          IndexedDerivedGrants,
          state
        );
    }
    var consumerRules :=
      if grant.node in state.consumers
      then state.consumers[grant.node]
      else [];
    var generated :=
      ForwardConsumerWorks(consumerRules, grant);
    if |rest| + |generated| > limits.maxQueuedWork {
      return ForwardStepLimitExceeded(IndexedQueuedWork, state);
    }

    var seen := state.seen + {grant};
    var rootResult :=
      grant.node == state.rootNode &&
      grant.resourceEid !in state.emitted;
    var emitted :=
      if rootResult
      then state.emitted + {grant.resourceEid}
      else state.emitted;
    var render := state.render;
    var delivered := false;
    if rootResult {
      var renderOutcome :=
        AdvanceRender(state.render, grant.resourceEid);
      if renderOutcome.RenderRejected? {
        return ForwardRenderRejected(renderOutcome.error, state);
      }
      render := renderOutcome.state;
      delivered := renderOutcome.delivered;
    }
    var counters :=
      CountersAfterQueueStep(
        state.counters,
        0,
        |generated|,
        1,
        if rootResult then 1 else 0,
        |consumerRules|,
        |consumerRules|,
        if rootResult then 1 else 0
      );
    var updated :=
      UpdateForwardState(
        state,
        rest + generated,
        seen,
        emitted,
        render,
        NoForwardPending,
        state.nextRequestId,
        counters
      );
    if delivered {
      return ForwardEmitted(
          updated,
          grant.resourceEid,
          state.render.ordinal
        );
    }
    return ForwardAdvanced(updated);
  }

  opaque function DriveForwardSpec(
    state: ForwardState,
    limits: IndexedLimits,
    fuel: nat
  ): ForwardStep
    requires ForwardStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.NoForwardPending?
    requires ValidForwardQueuedEids(state.queue)
    decreases fuel
  {
    if fuel == 0
    then ForwardYielded(state)
    else
      var step := ForwardStepSpec(state, limits);
      match step
      case ForwardAdvanced(next) =>
        DriveForwardSpec(next, limits, fuel - 1)
      case ForwardEmitted(next, _, _) =>
        DriveForwardSpec(next, limits, fuel - 1)
      case ForwardYielded(next) =>
        ForwardYielded(next)
      case ForwardNeedScan(next, command) =>
        ForwardNeedScan(next, command)
      case ForwardComplete(next) =>
        ForwardComplete(next)
      case ForwardRenderRejected(error, next) =>
        ForwardRenderRejected(error, next)
      case ForwardStepLimitExceeded(kind, next) =>
        ForwardStepLimitExceeded(kind, next)
  }

  lemma UnfoldDriveForwardSpec(
    state: ForwardState,
    limits: IndexedLimits,
    fuel: nat
  )
    requires ForwardStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.NoForwardPending?
    requires ValidForwardQueuedEids(state.queue)
    ensures
      DriveForwardSpec(state, limits, fuel) ==
      (if fuel == 0
       then ForwardYielded(state)
       else
         var step := ForwardStepSpec(state, limits);
         match step
         case ForwardAdvanced(next) =>
           DriveForwardSpec(next, limits, fuel - 1)
         case ForwardEmitted(next, _, _) =>
           DriveForwardSpec(next, limits, fuel - 1)
         case ForwardYielded(next) =>
           ForwardYielded(next)
         case ForwardNeedScan(next, command) =>
           ForwardNeedScan(next, command)
         case ForwardComplete(next) =>
           ForwardComplete(next)
         case ForwardRenderRejected(error, next) =>
           ForwardRenderRejected(error, next)
         case ForwardStepLimitExceeded(kind, next) =>
           ForwardStepLimitExceeded(kind, next))
  {
    reveal DriveForwardSpec();
  }

  method DriveForward(
    state: ForwardState,
    limits: IndexedLimits,
    fuel: nat
  ) returns (outcome: ForwardStep)
    requires ForwardStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.NoForwardPending?
    requires ValidForwardQueuedEids(state.queue)
    decreases fuel
    ensures outcome == DriveForwardSpec(state, limits, fuel)
    ensures outcome.ForwardAdvanced? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidForwardQueuedEids(outcome.state.queue)
    ensures outcome.ForwardYielded? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidForwardQueuedEids(outcome.state.queue)
    ensures outcome.ForwardNeedScan? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidForwardQueuedEids(outcome.state.queue) &&
              outcome.state.pending.AwaitingForwardScan? &&
              outcome.command == outcome.state.pending.command
    ensures outcome.ForwardEmitted? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidForwardQueuedEids(outcome.state.queue) &&
              outcome.eid in outcome.state.emitted &&
              outcome.ordinal + 1 == outcome.state.render.ordinal
    ensures outcome.ForwardComplete? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidForwardQueuedEids(outcome.state.queue)
    ensures outcome.ForwardRenderRejected? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidForwardQueuedEids(outcome.state.queue)
    ensures outcome.ForwardStepLimitExceeded? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidForwardQueuedEids(outcome.state.queue)
  {
    UnfoldDriveForwardSpec(state, limits, fuel);
    if fuel == 0 {
      return ForwardYielded(state);
    }
    var step := StepForward(state, limits);
    match step
    case ForwardAdvanced(next) => {
      var driven := DriveForward(next, limits, fuel - 1);
      return driven;
    }
    case ForwardEmitted(next, _, _) => {
      var driven := DriveForward(next, limits, fuel - 1);
      return driven;
    }
    case ForwardYielded(next) =>
      return ForwardYielded(next);
    case ForwardNeedScan(next, command) =>
      return ForwardNeedScan(next, command);
    case ForwardComplete(next) =>
      return ForwardComplete(next);
    case ForwardRenderRejected(error, next) =>
      return ForwardRenderRejected(error, next);
    case ForwardStepLimitExceeded(kind, next) =>
      return ForwardStepLimitExceeded(kind, next);
  }

  method DriveForwardIterative(
    state: ForwardState,
    limits: IndexedLimits,
    fuel: nat
  ) returns (outcome: ForwardStep)
    requires ForwardStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.NoForwardPending?
    requires ValidForwardQueuedEids(state.queue)
    ensures outcome == DriveForwardSpec(state, limits, fuel)
  {
    var current := state;
    var remaining := fuel;
    while 0 < remaining
      invariant ForwardStateInvariant(current)
      invariant CountersWithinLimits(current.counters, limits)
      invariant current.pending.NoForwardPending?
      invariant ValidForwardQueuedEids(current.queue)
      invariant
        DriveForwardSpec(current, limits, remaining) ==
        DriveForwardSpec(state, limits, fuel)
      decreases remaining
    {
      UnfoldDriveForwardSpec(current, limits, remaining);
      var step := StepForward(current, limits);
      match step
      case ForwardAdvanced(next) => {
        current := next;
        remaining := remaining - 1;
      }
      case ForwardEmitted(next, _, _) => {
        current := next;
        remaining := remaining - 1;
      }
      case ForwardYielded(next) =>
        return ForwardYielded(next);
      case ForwardNeedScan(next, command) =>
        return ForwardNeedScan(next, command);
      case ForwardComplete(next) =>
        return ForwardComplete(next);
      case ForwardRenderRejected(error, next) =>
        return ForwardRenderRejected(error, next);
      case ForwardStepLimitExceeded(kind, next) =>
        return ForwardStepLimitExceeded(kind, next);
    }
    UnfoldDriveForwardSpec(current, limits, remaining);
    return ForwardYielded(current);
  }

  function ReverseConsumerWork(
    consumer: ReverseConsumer,
    grant: ReverseGrantKey
  ): seq<ReverseWork>
    requires 0 <= consumer.resourceEid
    requires 0 <= grant.resourceEid
    requires 0 <= grant.subjectEid
    ensures ValidReverseQueuedEids(ReverseConsumerWork(consumer, grant))
  {
    [ReverseGrantWork(
       ReverseGrantKey(
         consumer.node,
         consumer.resourceEid,
         grant.subjectType,
         grant.subjectEid
       )
     )]
  }

  function ReverseConsumerWorks(
    consumers: seq<ReverseConsumer>,
    grant: ReverseGrantKey
  ): seq<ReverseWork>
    requires forall consumer <- consumers ::
               0 <= consumer.resourceEid
    requires 0 <= grant.resourceEid
    requires 0 <= grant.subjectEid
    ensures ValidReverseQueuedEids(
              ReverseConsumerWorks(consumers, grant)
            )
    decreases |consumers|
  {
    if |consumers| == 0 then
      []
    else
      ReverseConsumerWork(consumers[0], grant) +
      ReverseConsumerWorks(consumers[1..], grant)
  }

  function ReverseConsumersForGrants(
    consumer: ReverseConsumer,
    grants: seq<ReverseGrantKey>
  ): seq<ReverseWork>
    requires 0 <= consumer.resourceEid
    requires forall grant <- grants ::
               0 <= grant.resourceEid &&
               0 <= grant.subjectEid
    ensures ValidReverseQueuedEids(
              ReverseConsumersForGrants(consumer, grants)
            )
    decreases |grants|
  {
    if |grants| == 0 then
      []
    else
      ReverseConsumerWork(consumer, grants[0]) +
      ReverseConsumersForGrants(consumer, grants[1..])
  }

  function ReverseGoalRuleWork(
    rule: IndexedRule,
    goal: ReverseGoalKey,
    querySubjectType: string
  ): seq<ReverseWork>
    requires ValidIndexedRule(rule)
    requires 0 <= goal.resourceEid
    ensures ValidReverseQueuedEids(
              ReverseGoalRuleWork(rule, goal, querySubjectType)
            )
  {
    match rule
    case RelationRule(head, relationEid, subjectType) =>
      if querySubjectType == subjectType
      then
        [ReverseStreamWork(
           ReverseStream(
             ResourceToSubjects(
               head.resourceType,
               goal.resourceEid,
               relationEid,
               querySubjectType,
               NoBound
             ),
             [],
             true,
             ReverseGrant(
               head,
               goal.resourceEid,
               querySubjectType
             )
           )
         )]
      else []
    case SelfPermissionRule(head, targetNode) =>
      [ReverseRegisterConsumerWork(
         ReverseGoalKey(targetNode, goal.resourceEid),
         ReverseConsumer(head, goal.resourceEid)
       ),
       ReverseGoalWork(
         ReverseGoalKey(targetNode, goal.resourceEid)
       )]
    case ArrowRelationRule(
      head,
      viaRelationEid,
      intermediateType,
      targetRelationEid,
      targetSubjectType
      ) =>
      if querySubjectType == targetSubjectType
      then
        [ReverseStreamWork(
           ReverseStream(
             ResourceToSubjects(
               head.resourceType,
               goal.resourceEid,
               viaRelationEid,
               intermediateType,
               NoBound
             ),
             [],
             true,
             ReverseArrowRelation(
               head,
               goal.resourceEid,
               querySubjectType,
               intermediateType,
               targetRelationEid
             )
           )
         )]
      else []
    case ArrowPermissionRule(
      head,
      viaRelationEid,
      intermediateType,
      targetNode
      ) =>
      [ReverseStreamWork(
         ReverseStream(
           ResourceToSubjects(
             head.resourceType,
             goal.resourceEid,
             viaRelationEid,
             intermediateType,
             NoBound
           ),
           [],
           true,
           ReverseArrowPermission(
             head,
             goal.resourceEid,
             targetNode
           )
         )
       )]
  }

  function ReverseGoalRuleWorks(
    rules: seq<IndexedRule>,
    goal: ReverseGoalKey,
    querySubjectType: string
  ): seq<ReverseWork>
    requires ValidIndexedRules(rules)
    requires 0 <= goal.resourceEid
    ensures ValidReverseQueuedEids(
              ReverseGoalRuleWorks(rules, goal, querySubjectType)
            )
    decreases |rules|
  {
    if |rules| == 0 then
      []
    else
      ReverseGoalRuleWork(rules[0], goal, querySubjectType) +
      ReverseGoalRuleWorks(
        rules[1..],
        goal,
        querySubjectType
      )
  }

  function ReverseBufferedWork(
    stream: ReverseStream
  ): seq<ReverseWork>
    requires 0 < |stream.buffered|
    requires forall index | 0 <= index < |stream.buffered| ::
               0 <= stream.buffered[index]
    requires ValidReverseContinuationEids(stream.continuation)
    ensures ValidReverseQueuedEids(ReverseBufferedWork(stream))
  {
    var continuationWork :=
      ContinueReverse(stream.continuation, stream.buffered[0]);
    var streamWork :=
      if 1 < |stream.buffered| || stream.more
      then
        [ReverseStreamWork(
           ReverseStream(
             stream.projection,
             stream.buffered[1..],
             stream.more,
             stream.continuation
           )
         )]
      else [];
    continuationWork + streamWork
  }

  function UpdateReverseState(
    state: ReverseState,
    queue: seq<ReverseWork>,
    seenGoals: set<ReverseGoalKey>,
    seenGrants: set<ReverseGrantKey>,
    grantsByGoal: map<ReverseGoalKey, seq<ReverseGrantKey>>,
    consumers: map<ReverseGoalKey, seq<ReverseConsumer>>,
    emitted: set<int>,
    render: RenderState,
    pending: ReversePending,
    nextRequestId: RequestSequence,
    counters: ResourceCounters
  ): ReverseState
  {
    ReverseState(
      queue,
      state.rulesByNode,
      seenGoals,
      seenGrants,
      grantsByGoal,
      consumers,
      state.seenConsumers,
      emitted,
      render,
      state.rootNode,
      state.rootResourceEid,
      state.resultType,
      state.subjectType,
      state.chunkSize,
      pending,
      nextRequestId,
      counters
    )
  }

  function WithSeenReverseConsumers(
    state: ReverseState,
    seenConsumers: set<ReverseConsumerRegistration>
  ): ReverseState
  {
    ReverseState(
      state.queue,
      state.rulesByNode,
      state.seenGoals,
      state.seenGrants,
      state.grantsByGoal,
      state.consumers,
      seenConsumers,
      state.emitted,
      state.render,
      state.rootNode,
      state.rootResourceEid,
      state.resultType,
      state.subjectType,
      state.chunkSize,
      state.pending,
      state.nextRequestId,
      state.counters
    )
  }

  predicate ReverseSemanticFrame(
    before: ReverseState,
    after: ReverseState
  ) {
    after.rulesByNode == before.rulesByNode &&
    after.rootNode == before.rootNode &&
    after.rootResourceEid == before.rootResourceEid &&
    after.resultType == before.resultType &&
    after.subjectType == before.subjectType &&
    after.chunkSize == before.chunkSize
  }

  predicate ReverseSuccessfulGoalTransition(
    before: ReverseState,
    after: ReverseState,
    goal: ReverseGoalKey
  )
    requires ReverseStateInvariant(before)
    requires ValidReverseQueuedEids(before.queue)
    requires 0 < |before.queue|
    requires before.queue[0].ReverseGoalWork?
    requires goal == before.queue[0].goal
  {
    var rules :=
      if goal.node in before.rulesByNode
      then before.rulesByNode[goal.node]
      else [];
    ReverseSemanticFrame(before, after) &&
    goal !in before.seenGoals &&
    after.queue ==
    before.queue[1..] +
    ReverseGoalRuleWorks(rules, goal, before.subjectType) &&
    after.seenGoals == before.seenGoals + {goal} &&
    after.seenGrants == before.seenGrants &&
    after.grantsByGoal == before.grantsByGoal &&
    after.consumers == before.consumers &&
    after.seenConsumers == before.seenConsumers &&
    after.emitted == before.emitted &&
    after.pending.NoReversePending?
  }

  predicate ReverseSuccessfulConsumerTransition(
    before: ReverseState,
    after: ReverseState,
    key: ReverseGoalKey,
    consumer: ReverseConsumer
  )
    requires ReverseStateInvariant(before)
    requires ValidReverseQueuedEids(before.queue)
    requires 0 < |before.queue|
    requires before.queue[0].ReverseRegisterConsumerWork?
    requires key == before.queue[0].key
    requires consumer == before.queue[0].consumer
  {
    var registration := ReverseConsumerRegistration(key, consumer);
    var previousConsumers :=
      if key in before.consumers
      then before.consumers[key]
      else [];
    var existingGrants :=
      if key in before.grantsByGoal
      then before.grantsByGoal[key]
      else [];
    ReverseSemanticFrame(before, after) &&
    registration !in before.seenConsumers &&
    after.queue ==
    before.queue[1..] +
    ReverseConsumersForGrants(consumer, existingGrants) &&
    after.seenGoals == before.seenGoals &&
    after.seenGrants == before.seenGrants &&
    after.grantsByGoal == before.grantsByGoal &&
    after.consumers ==
    before.consumers[
    key := previousConsumers + [consumer]
    ] &&
    after.seenConsumers ==
    before.seenConsumers + {registration} &&
    after.emitted == before.emitted &&
    after.pending.NoReversePending?
  }

  predicate ReverseSuccessfulGrantTransition(
    before: ReverseState,
    after: ReverseState,
    grant: ReverseGrantKey
  )
    requires ReverseStateInvariant(before)
    requires ValidReverseQueuedEids(before.queue)
    requires 0 < |before.queue|
    requires before.queue[0].ReverseGrantWork?
    requires grant == before.queue[0].grant
  {
    var key := ReverseGoalKey(grant.node, grant.resourceEid);
    var consumers :=
      if key in before.consumers
      then before.consumers[key]
      else [];
    var previousGrants :=
      if key in before.grantsByGoal
      then before.grantsByGoal[key]
      else [];
    ReverseSemanticFrame(before, after) &&
    grant !in before.seenGrants &&
    after.queue ==
    before.queue[1..] +
    ReverseConsumerWorks(consumers, grant) &&
    after.seenGoals == before.seenGoals &&
    after.seenGrants == before.seenGrants + {grant} &&
    after.grantsByGoal ==
    before.grantsByGoal[
    key := previousGrants + [grant]
    ] &&
    after.consumers == before.consumers &&
    after.seenConsumers == before.seenConsumers &&
    after.emitted ==
    (if grant.node == before.rootNode &&
        grant.resourceEid == before.rootResourceEid &&
        grant.subjectType == before.resultType &&
        grant.subjectEid !in before.emitted
     then before.emitted + {grant.subjectEid}
     else before.emitted) &&
    after.pending.NoReversePending?
  }

  predicate ReverseStepRelation(
    before: ReverseState,
    outcome: ReverseStep
  )
    requires ReverseStateInvariant(before)
    requires ValidReverseQueuedEids(before.queue)
  {
    match outcome
    case ReverseAdvanced(after) =>
      ReverseSemanticFrame(before, after) &&
      0 < |before.queue| &&
      (
        (before.queue[0].ReverseStreamWork? &&
         |before.queue[0].stream.buffered| == 0 &&
         !before.queue[0].stream.more &&
         after.queue == before.queue[1..] &&
         after.seenGoals == before.seenGoals &&
         after.seenGrants == before.seenGrants &&
         after.grantsByGoal == before.grantsByGoal &&
         after.consumers == before.consumers &&
         after.seenConsumers == before.seenConsumers &&
         after.emitted == before.emitted &&
         after.pending.NoReversePending?) ||
        (before.queue[0].ReverseStreamWork? &&
         0 < |before.queue[0].stream.buffered| &&
         after.queue ==
         before.queue[1..] +
         ReverseBufferedWork(before.queue[0].stream) &&
         after.seenGoals == before.seenGoals &&
         after.seenGrants == before.seenGrants &&
         after.grantsByGoal == before.grantsByGoal &&
         after.consumers == before.consumers &&
         after.seenConsumers == before.seenConsumers &&
         after.emitted == before.emitted &&
         after.pending.NoReversePending?) ||
        (before.queue[0].ReverseGoalWork? &&
         before.queue[0].goal in before.seenGoals &&
         after.queue == before.queue[1..] &&
         after.seenGoals == before.seenGoals &&
         after.seenGrants == before.seenGrants &&
         after.grantsByGoal == before.grantsByGoal &&
         after.consumers == before.consumers &&
         after.seenConsumers == before.seenConsumers &&
         after.emitted == before.emitted &&
         after.pending.NoReversePending?) ||
        (before.queue[0].ReverseGoalWork? &&
         ReverseSuccessfulGoalTransition(
           before,
           after,
           before.queue[0].goal
         )) ||
        (before.queue[0].ReverseRegisterConsumerWork? &&
         ReverseConsumerRegistration(
           before.queue[0].key,
           before.queue[0].consumer
         ) in before.seenConsumers &&
         after.queue == before.queue[1..] &&
         after.seenGoals == before.seenGoals &&
         after.seenGrants == before.seenGrants &&
         after.grantsByGoal == before.grantsByGoal &&
         after.consumers == before.consumers &&
         after.seenConsumers == before.seenConsumers &&
         after.emitted == before.emitted &&
         after.pending.NoReversePending?) ||
        (before.queue[0].ReverseRegisterConsumerWork? &&
         ReverseSuccessfulConsumerTransition(
           before,
           after,
           before.queue[0].key,
           before.queue[0].consumer
         )) ||
        (before.queue[0].ReverseGrantWork? &&
         before.queue[0].grant in before.seenGrants &&
         after.queue == before.queue[1..] &&
         after.seenGoals == before.seenGoals &&
         after.seenGrants == before.seenGrants &&
         after.grantsByGoal == before.grantsByGoal &&
         after.consumers == before.consumers &&
         after.seenConsumers == before.seenConsumers &&
         after.emitted == before.emitted &&
         after.pending.NoReversePending?) ||
        (before.queue[0].ReverseGrantWork? &&
         ReverseSuccessfulGrantTransition(
           before,
           after,
           before.queue[0].grant
         ))
      )
    case ReverseYielded(after) =>
      after == before
    case ReverseNeedScan(after, command) =>
      ReverseSemanticFrame(before, after) &&
      0 < |before.queue| &&
      before.queue[0].ReverseStreamWork? &&
      |before.queue[0].stream.buffered| == 0 &&
      before.queue[0].stream.more &&
      after.queue == before.queue[1..] &&
      after.seenGoals == before.seenGoals &&
      after.seenGrants == before.seenGrants &&
      after.grantsByGoal == before.grantsByGoal &&
      after.consumers == before.consumers &&
      after.seenConsumers == before.seenConsumers &&
      after.emitted == before.emitted &&
      after.pending.AwaitingReverseScan? &&
      after.pending.command == command &&
      command.projection == before.queue[0].stream.projection &&
      after.pending.continuation ==
      before.queue[0].stream.continuation
    case ReverseEmitted(after, eid, _) =>
      0 < |before.queue| &&
      before.queue[0].ReverseGrantWork? &&
      eid == before.queue[0].grant.subjectEid &&
      ReverseSuccessfulGrantTransition(
        before,
        after,
        before.queue[0].grant
      )
    case ReverseComplete(after) =>
      ReverseSemanticFrame(before, after) &&
      after.queue == before.queue &&
      after.seenGoals == before.seenGoals &&
      after.seenGrants == before.seenGrants &&
      after.grantsByGoal == before.grantsByGoal &&
      after.consumers == before.consumers &&
      after.seenConsumers == before.seenConsumers &&
      after.emitted == before.emitted &&
      after.pending == before.pending
    case ReverseRenderRejected(_, after) =>
      after == before
    case ReverseStepLimitExceeded(_, after) =>
      after == before
  }

  function ReverseStepRuleApplicationCost(
    state: ReverseState
  ): nat {
    if state.render.complete || |state.queue| == 0
    then 0
    else if state.queue[0].ReverseGoalWork? &&
            state.queue[0].goal !in state.seenGoals
      then
        if state.queue[0].goal.node in state.rulesByNode
        then |state.rulesByNode[state.queue[0].goal.node]|
        else 0
      else 0
  }

  function ReverseStepConsumerJoinCost(
    state: ReverseState
  ): nat {
    if state.render.complete || |state.queue| == 0
    then 0
    else if state.queue[0].ReverseRegisterConsumerWork? &&
            ReverseConsumerRegistration(
              state.queue[0].key,
              state.queue[0].consumer
            ) !in state.seenConsumers
      then
        if state.queue[0].key in state.grantsByGoal
        then |state.grantsByGoal[state.queue[0].key]|
        else 0
      else if state.queue[0].ReverseGrantWork? &&
              state.queue[0].grant !in state.seenGrants
        then
          var key :=
            ReverseGoalKey(
              state.queue[0].grant.node,
              state.queue[0].grant.resourceEid
            );
          if key in state.consumers
          then |state.consumers[key]|
          else 0
        else 0
  }

  function ReverseStepRenderAdvanceCost(
    state: ReverseState
  ): nat {
    if state.render.complete || |state.queue| == 0
    then 0
    else if state.queue[0].ReverseGrantWork? &&
            state.queue[0].grant !in state.seenGrants &&
            state.queue[0].grant.node == state.rootNode &&
            state.queue[0].grant.resourceEid ==
            state.rootResourceEid &&
            state.queue[0].grant.subjectType ==
            state.resultType &&
            state.queue[0].grant.subjectEid !in state.emitted
      then 1
      else 0
  }

  function ReverseStepSpec(
    state: ReverseState,
    limits: IndexedLimits
  ): ReverseStep
    requires ReverseStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.NoReversePending?
    requires ValidReverseQueuedEids(state.queue)
    ensures ReverseStateInvariant(
              ReverseStepSpec(state, limits).state
            )
    ensures CountersWithinLimits(
              ReverseStepSpec(state, limits).state.counters,
              limits
            )
    ensures ValidReverseQueuedEids(
              ReverseStepSpec(state, limits).state.queue
            )
    ensures ReverseStepRelation(
              state,
              ReverseStepSpec(state, limits)
            )
    ensures ReverseStepSpec(state, limits).ReverseAdvanced? ==>
              ReverseStateInvariant(
                ReverseStepSpec(state, limits).state
              ) &&
              CountersWithinLimits(
                ReverseStepSpec(state, limits).state.counters,
                limits
              ) &&
              ReverseStepSpec(
                state,
                limits
              ).state.pending.NoReversePending? &&
              ValidReverseQueuedEids(
                ReverseStepSpec(state, limits).state.queue
              )
    ensures ReverseStepSpec(state, limits).ReverseEmitted? ==>
              ReverseStateInvariant(
                ReverseStepSpec(state, limits).state
              ) &&
              CountersWithinLimits(
                ReverseStepSpec(state, limits).state.counters,
                limits
              ) &&
              ReverseStepSpec(
                state,
                limits
              ).state.pending.NoReversePending? &&
              ValidReverseQueuedEids(
                ReverseStepSpec(state, limits).state.queue
              )
  {
    if state.render.complete
    then ReverseComplete(state)
    else if |state.queue| == 0
      then
        var finished := FinishRenderSpec(state.render);
        if finished.RenderRejected?
        then ReverseRenderRejected(finished.error, state)
        else
          ReverseComplete(
            UpdateReverseState(
              state,
              state.queue,
              state.seenGoals,
              state.seenGrants,
              state.grantsByGoal,
              state.consumers,
              state.emitted,
              finished.state,
              state.pending,
              state.nextRequestId,
              state.counters
            )
          )
      else
        var work := state.queue[0];
        var rest := state.queue[1..];
        if work.ReverseStreamWork?
        then
          var stream := work.stream;
          if |stream.buffered| == 0
          then
            var counters :=
              CountersAfterQueueStep(
                state.counters, 0, 0, 0, 0, 0, 0, 0
              );
            if !stream.more
            then
              ReverseAdvanced(
                UpdateReverseState(
                  state,
                  rest,
                  state.seenGoals,
                  state.seenGrants,
                  state.grantsByGoal,
                  state.consumers,
                  state.emitted,
                  state.render,
                  NoReversePending,
                  state.nextRequestId,
                  counters
                )
              )
            else
              var command :=
                ScanCommand(
                  state.nextRequestId.scope,
                  state.nextRequestId.next,
                  stream.projection,
                  state.chunkSize
                );
              ReverseNeedScan(
                UpdateReverseState(
                  state,
                  rest,
                  state.seenGoals,
                  state.seenGrants,
                  state.grantsByGoal,
                  state.consumers,
                  state.emitted,
                  state.render,
                  AwaitingReverseScan(
                    command,
                    stream.continuation
                  ),
                  RequestSequence(
                    state.nextRequestId.scope,
                    state.nextRequestId.next + 1
                  ),
                  counters
                ),
                command
              )
          else if state.counters.engineConsumedValues + 1 >
                  limits.maxAdvancedDatoms
            then
              ReverseStepLimitExceeded(
                IndexedAdvancedDatoms,
                state
              )
            else
              var generated := ReverseBufferedWork(stream);
              if |rest| + |generated| > limits.maxQueuedWork
              then ReverseStepLimitExceeded(IndexedQueuedWork, state)
              else
                var counters :=
                  CountersAfterQueueStep(
                    state.counters,
                    1,
                    |generated|,
                    0,
                    0,
                    0,
                    0,
                    0
                  );
                ReverseAdvanced(
                  UpdateReverseState(
                    state,
                    rest + generated,
                    state.seenGoals,
                    state.seenGrants,
                    state.grantsByGoal,
                    state.consumers,
                    state.emitted,
                    state.render,
                    NoReversePending,
                    state.nextRequestId,
                    counters
                  )
                )
        else if work.ReverseGoalWork?
          then
            var goal := work.goal;
            if goal in state.seenGoals
            then
              var counters :=
                CountersAfterQueueStep(
                  state.counters, 0, 0, 0, 0, 0, 0, 0
                );
              ReverseAdvanced(
                UpdateReverseState(
                  state,
                  rest,
                  state.seenGoals,
                  state.seenGrants,
                  state.grantsByGoal,
                  state.consumers,
                  state.emitted,
                  state.render,
                  NoReversePending,
                  state.nextRequestId,
                  counters
                )
              )
            else
              var rules :=
                if goal.node in state.rulesByNode
                then state.rulesByNode[goal.node]
                else [];
              var generated :=
                ReverseGoalRuleWorks(rules, goal, state.subjectType);
              if |rest| + |generated| > limits.maxQueuedWork
              then ReverseStepLimitExceeded(IndexedQueuedWork, state)
              else
                var counters :=
                  CountersAfterQueueStep(
                    state.counters,
                    0,
                    |generated|,
                    0,
                    0,
                    |rules|,
                    0,
                    0
                  );
                ReverseAdvanced(
                  UpdateReverseState(
                    state,
                    rest + generated,
                    state.seenGoals + {goal},
                    state.seenGrants,
                    state.grantsByGoal,
                    state.consumers,
                    state.emitted,
                    state.render,
                    NoReversePending,
                    state.nextRequestId,
                    counters
                  )
                )
          else if work.ReverseRegisterConsumerWork?
            then
              var registration :=
                ReverseConsumerRegistration(work.key, work.consumer);
              if registration in state.seenConsumers
              then
                var counters :=
                  CountersAfterQueueStep(
                    state.counters, 0, 0, 0, 0, 0, 0, 0
                  );
                ReverseAdvanced(
                  UpdateReverseState(
                    state,
                    rest,
                    state.seenGoals,
                    state.seenGrants,
                    state.grantsByGoal,
                    state.consumers,
                    state.emitted,
                    state.render,
                    NoReversePending,
                    state.nextRequestId,
                    counters
                  )
                )
              else
                var existingConsumers :=
                  if work.key in state.consumers
                  then state.consumers[work.key]
                  else [];
                var existingGrants :=
                  if work.key in state.grantsByGoal
                  then state.grantsByGoal[work.key]
                  else [];
                var generated :=
                  ReverseConsumersForGrants(
                    work.consumer,
                    existingGrants
                  );
                if |rest| + |generated| > limits.maxQueuedWork
                then ReverseStepLimitExceeded(IndexedQueuedWork, state)
                else
                  var counters :=
                    CountersAfterQueueStep(
                      state.counters,
                      0,
                      |generated|,
                      0,
                      0,
                      0,
                      |existingGrants|,
                      0
                    );
                  ReverseAdvanced(
                    WithSeenReverseConsumers(
                      UpdateReverseState(
                        state,
                        rest + generated,
                        state.seenGoals,
                        state.seenGrants,
                        state.grantsByGoal,
                        state.consumers[
                        work.key :=
                        existingConsumers + [work.consumer]
                        ],
                        state.emitted,
                        state.render,
                        NoReversePending,
                        state.nextRequestId,
                        counters
                      ),
                      state.seenConsumers + {registration}
                    )
                  )
            else
              var grant := work.grant;
              if grant in state.seenGrants
              then
                var counters :=
                  CountersAfterQueueStep(
                    state.counters, 0, 0, 0, 0, 0, 0, 0
                  );
                ReverseAdvanced(
                  UpdateReverseState(
                    state,
                    rest,
                    state.seenGoals,
                    state.seenGrants,
                    state.grantsByGoal,
                    state.consumers,
                    state.emitted,
                    state.render,
                    NoReversePending,
                    state.nextRequestId,
                    counters
                  )
                )
              else if state.counters.uniqueGrants + 1 >
                      limits.maxDerivedGrants
                then
                  ReverseStepLimitExceeded(
                    IndexedDerivedGrants,
                    state
                  )
                else
                  var key :=
                    ReverseGoalKey(grant.node, grant.resourceEid);
                  var consumers :=
                    if key in state.consumers
                    then state.consumers[key]
                    else [];
                  var generated :=
                    ReverseConsumerWorks(consumers, grant);
                  if |rest| + |generated| > limits.maxQueuedWork
                  then ReverseStepLimitExceeded(IndexedQueuedWork, state)
                  else
                    var previousGrants :=
                      if key in state.grantsByGoal
                      then state.grantsByGoal[key]
                      else [];
                    var grantsByGoal :=
                      state.grantsByGoal[
                      key := previousGrants + [grant]
                      ];
                    var seenGrants := state.seenGrants + {grant};
                    var rootResult :=
                      grant.node == state.rootNode &&
                      grant.resourceEid == state.rootResourceEid &&
                      grant.subjectType == state.resultType &&
                      grant.subjectEid !in state.emitted;
                    var emitted :=
                      if rootResult
                      then state.emitted + {grant.subjectEid}
                      else state.emitted;
                    if rootResult
                    then
                      var renderOutcome :=
                        AdvanceRenderSpec(
                          state.render,
                          grant.subjectEid
                        );
                      if renderOutcome.RenderRejected?
                      then ReverseRenderRejected(renderOutcome.error, state)
                      else
                        var counters :=
                          CountersAfterQueueStep(
                            state.counters,
                            0,
                            |generated|,
                            1,
                            1,
                            0,
                            |consumers|,
                            1
                          );
                        var updated :=
                          UpdateReverseState(
                            state,
                            rest + generated,
                            state.seenGoals,
                            seenGrants,
                            grantsByGoal,
                            state.consumers,
                            emitted,
                            renderOutcome.state,
                            NoReversePending,
                            state.nextRequestId,
                            counters
                          );
                        if renderOutcome.delivered
                        then
                          ReverseEmitted(
                            updated,
                            grant.subjectEid,
                            state.render.ordinal
                          )
                        else ReverseAdvanced(updated)
                    else
                      var counters :=
                        CountersAfterQueueStep(
                          state.counters,
                          0,
                          |generated|,
                          1,
                          0,
                          0,
                          |consumers|,
                          0
                        );
                      ReverseAdvanced(
                        UpdateReverseState(
                          state,
                          rest + generated,
                          state.seenGoals,
                          seenGrants,
                          grantsByGoal,
                          state.consumers,
                          emitted,
                          state.render,
                          NoReversePending,
                          state.nextRequestId,
                          counters
                        )
                      )
  }

  method StepReverse(
    state: ReverseState,
    limits: IndexedLimits
  ) returns (outcome: ReverseStep)
    requires ReverseStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.NoReversePending?
    requires ValidReverseQueuedEids(state.queue)
    ensures outcome == ReverseStepSpec(state, limits)
    ensures ReverseStepRelation(state, outcome)
    ensures !outcome.ReverseRenderRejected? &&
            !outcome.ReverseStepLimitExceeded? ==>
              outcome.state.counters.ruleApplications ==
              state.counters.ruleApplications +
              ReverseStepRuleApplicationCost(state) &&
              outcome.state.counters.consumerGrantJoins ==
              state.counters.consumerGrantJoins +
              ReverseStepConsumerJoinCost(state) &&
              outcome.state.counters.renderAdvances ==
              state.counters.renderAdvances +
              ReverseStepRenderAdvanceCost(state)
    ensures outcome.ReverseAdvanced? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              outcome.state.pending.NoReversePending? &&
              ValidReverseQueuedEids(outcome.state.queue)
    ensures outcome.ReverseYielded? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidReverseQueuedEids(outcome.state.queue)
    ensures outcome.ReverseNeedScan? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidReverseQueuedEids(outcome.state.queue) &&
              outcome.state.pending.AwaitingReverseScan? &&
              outcome.command == outcome.state.pending.command
    ensures outcome.ReverseEmitted? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              outcome.state.pending.NoReversePending? &&
              ValidReverseQueuedEids(outcome.state.queue) &&
              outcome.eid in outcome.state.emitted &&
              outcome.ordinal + 1 == outcome.state.render.ordinal
    ensures outcome.ReverseComplete? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidReverseQueuedEids(outcome.state.queue)
    ensures outcome.ReverseRenderRejected? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidReverseQueuedEids(outcome.state.queue)
    ensures outcome.ReverseStepLimitExceeded? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidReverseQueuedEids(outcome.state.queue)
  {
    if state.render.complete {
      return ReverseComplete(state);
    }
    if |state.queue| == 0 {
      var finished := FinishRender(state.render);
      if finished.RenderRejected? {
        return ReverseRenderRejected(finished.error, state);
      }
      return ReverseComplete(
          UpdateReverseState(
            state,
            state.queue,
            state.seenGoals,
            state.seenGrants,
            state.grantsByGoal,
            state.consumers,
            state.emitted,
            finished.state,
            state.pending,
            state.nextRequestId,
            state.counters
          )
        );
    }

    var work := state.queue[0];
    var rest := state.queue[1..];
    if work.ReverseStreamWork? {
      var stream := work.stream;
      if |stream.buffered| == 0 {
        var counters :=
          CountersAfterQueueStep(
            state.counters, 0, 0, 0, 0, 0, 0, 0
          );
        if !stream.more {
          return ReverseAdvanced(
              UpdateReverseState(
                state,
                rest,
                state.seenGoals,
                state.seenGrants,
                state.grantsByGoal,
                state.consumers,
                state.emitted,
                state.render,
                NoReversePending,
                state.nextRequestId,
                counters
              )
            );
        }

        var command :=
          ScanCommand(
            state.nextRequestId.scope,
            state.nextRequestId.next,
            stream.projection,
            state.chunkSize
          );
        return ReverseNeedScan(
            UpdateReverseState(
              state,
              rest,
              state.seenGoals,
              state.seenGrants,
              state.grantsByGoal,
              state.consumers,
              state.emitted,
              state.render,
              AwaitingReverseScan(command, stream.continuation),
              RequestSequence(
                state.nextRequestId.scope,
                state.nextRequestId.next + 1
              ),
              counters
            ),
            command
          );
      }

      if state.counters.engineConsumedValues + 1 >
         limits.maxAdvancedDatoms {
        return ReverseStepLimitExceeded(
            IndexedAdvancedDatoms,
            state
          );
      }
      var generated := ReverseBufferedWork(stream);
      if |rest| + |generated| > limits.maxQueuedWork {
        return ReverseStepLimitExceeded(IndexedQueuedWork, state);
      }
      var counters :=
        CountersAfterQueueStep(
          state.counters,
          1,
          |generated|,
          0,
          0,
          0,
          0,
          0
        );
      return ReverseAdvanced(
          UpdateReverseState(
            state,
            rest + generated,
            state.seenGoals,
            state.seenGrants,
            state.grantsByGoal,
            state.consumers,
            state.emitted,
            state.render,
            NoReversePending,
            state.nextRequestId,
            counters
          )
        );
    }

    if work.ReverseGoalWork? {
      var goal := work.goal;
      if goal in state.seenGoals {
        var counters :=
          CountersAfterQueueStep(
            state.counters, 0, 0, 0, 0, 0, 0, 0
          );
        return ReverseAdvanced(
            UpdateReverseState(
              state,
              rest,
              state.seenGoals,
              state.seenGrants,
              state.grantsByGoal,
              state.consumers,
              state.emitted,
              state.render,
              NoReversePending,
              state.nextRequestId,
              counters
            )
          );
      }
      var rules :=
        if goal.node in state.rulesByNode
        then state.rulesByNode[goal.node]
        else [];
      var generated :=
        ReverseGoalRuleWorks(rules, goal, state.subjectType);
      if |rest| + |generated| > limits.maxQueuedWork {
        return ReverseStepLimitExceeded(IndexedQueuedWork, state);
      }
      var counters :=
        CountersAfterQueueStep(
          state.counters,
          0,
          |generated|,
          0,
          0,
          |rules|,
          0,
          0
        );
      return ReverseAdvanced(
          UpdateReverseState(
            state,
            rest + generated,
            state.seenGoals + {goal},
            state.seenGrants,
            state.grantsByGoal,
            state.consumers,
            state.emitted,
            state.render,
            NoReversePending,
            state.nextRequestId,
            counters
          )
        );
    }

    if work.ReverseRegisterConsumerWork? {
      var registration :=
        ReverseConsumerRegistration(work.key, work.consumer);
      if registration in state.seenConsumers {
        var counters :=
          CountersAfterQueueStep(
            state.counters, 0, 0, 0, 0, 0, 0, 0
          );
        return ReverseAdvanced(
            UpdateReverseState(
              state,
              rest,
              state.seenGoals,
              state.seenGrants,
              state.grantsByGoal,
              state.consumers,
              state.emitted,
              state.render,
              NoReversePending,
              state.nextRequestId,
              counters
            )
          );
      }
      var existingConsumers :=
        if work.key in state.consumers
        then state.consumers[work.key]
        else [];
      var existingGrants :=
        if work.key in state.grantsByGoal
        then state.grantsByGoal[work.key]
        else [];
      var generated :=
        ReverseConsumersForGrants(
          work.consumer,
          existingGrants
        );
      if |rest| + |generated| > limits.maxQueuedWork {
        return ReverseStepLimitExceeded(IndexedQueuedWork, state);
      }
      var counters :=
        CountersAfterQueueStep(
          state.counters,
          0,
          |generated|,
          0,
          0,
          0,
          |existingGrants|,
          0
        );
      return ReverseAdvanced(
          WithSeenReverseConsumers(
            UpdateReverseState(
              state,
              rest + generated,
              state.seenGoals,
              state.seenGrants,
              state.grantsByGoal,
              state.consumers[
              work.key := existingConsumers + [work.consumer]
              ],
              state.emitted,
              state.render,
              NoReversePending,
              state.nextRequestId,
              counters
            ),
            state.seenConsumers + {registration}
          )
        );
    }

    var grant := work.grant;
    if grant in state.seenGrants {
      var counters :=
        CountersAfterQueueStep(
          state.counters, 0, 0, 0, 0, 0, 0, 0
        );
      return ReverseAdvanced(
          UpdateReverseState(
            state,
            rest,
            state.seenGoals,
            state.seenGrants,
            state.grantsByGoal,
            state.consumers,
            state.emitted,
            state.render,
            NoReversePending,
            state.nextRequestId,
            counters
          )
        );
    }

    if state.counters.uniqueGrants + 1 >
       limits.maxDerivedGrants {
      return ReverseStepLimitExceeded(
          IndexedDerivedGrants,
          state
        );
    }
    var key := ReverseGoalKey(grant.node, grant.resourceEid);
    var consumers :=
      if key in state.consumers
      then state.consumers[key]
      else [];
    var generated := ReverseConsumerWorks(consumers, grant);
    if |rest| + |generated| > limits.maxQueuedWork {
      return ReverseStepLimitExceeded(IndexedQueuedWork, state);
    }
    var previousGrants :=
      if key in state.grantsByGoal
      then state.grantsByGoal[key]
      else [];
    var grantsByGoal :=
      state.grantsByGoal[key := previousGrants + [grant]];
    var seenGrants := state.seenGrants + {grant};
    var rootResult :=
      grant.node == state.rootNode &&
      grant.resourceEid == state.rootResourceEid &&
      grant.subjectType == state.resultType &&
      grant.subjectEid !in state.emitted;
    var emitted :=
      if rootResult
      then state.emitted + {grant.subjectEid}
      else state.emitted;
    var render := state.render;
    var delivered := false;
    if rootResult {
      var renderOutcome :=
        AdvanceRender(state.render, grant.subjectEid);
      if renderOutcome.RenderRejected? {
        return ReverseRenderRejected(renderOutcome.error, state);
      }
      render := renderOutcome.state;
      delivered := renderOutcome.delivered;
    }
    var counters :=
      CountersAfterQueueStep(
        state.counters,
        0,
        |generated|,
        1,
        if rootResult then 1 else 0,
        0,
        |consumers|,
        if rootResult then 1 else 0
      );
    var updated :=
      UpdateReverseState(
        state,
        rest + generated,
        state.seenGoals,
        seenGrants,
        grantsByGoal,
        state.consumers,
        emitted,
        render,
        NoReversePending,
        state.nextRequestId,
        counters
      );
    if delivered {
      return ReverseEmitted(
          updated,
          grant.subjectEid,
          state.render.ordinal
        );
    }
    return ReverseAdvanced(updated);
  }

  opaque function DriveReverseSpec(
    state: ReverseState,
    limits: IndexedLimits,
    fuel: nat
  ): ReverseStep
    requires ReverseStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.NoReversePending?
    requires ValidReverseQueuedEids(state.queue)
    decreases fuel
  {
    if fuel == 0
    then ReverseYielded(state)
    else
      var step := ReverseStepSpec(state, limits);
      match step
      case ReverseAdvanced(next) =>
        DriveReverseSpec(next, limits, fuel - 1)
      case ReverseEmitted(next, _, _) =>
        DriveReverseSpec(next, limits, fuel - 1)
      case ReverseYielded(next) =>
        ReverseYielded(next)
      case ReverseNeedScan(next, command) =>
        ReverseNeedScan(next, command)
      case ReverseComplete(next) =>
        ReverseComplete(next)
      case ReverseRenderRejected(error, next) =>
        ReverseRenderRejected(error, next)
      case ReverseStepLimitExceeded(kind, next) =>
        ReverseStepLimitExceeded(kind, next)
  }

  lemma UnfoldDriveReverseSpec(
    state: ReverseState,
    limits: IndexedLimits,
    fuel: nat
  )
    requires ReverseStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.NoReversePending?
    requires ValidReverseQueuedEids(state.queue)
    ensures
      DriveReverseSpec(state, limits, fuel) ==
      (if fuel == 0
       then ReverseYielded(state)
       else
         var step := ReverseStepSpec(state, limits);
         match step
         case ReverseAdvanced(next) =>
           DriveReverseSpec(next, limits, fuel - 1)
         case ReverseEmitted(next, _, _) =>
           DriveReverseSpec(next, limits, fuel - 1)
         case ReverseYielded(next) =>
           ReverseYielded(next)
         case ReverseNeedScan(next, command) =>
           ReverseNeedScan(next, command)
         case ReverseComplete(next) =>
           ReverseComplete(next)
         case ReverseRenderRejected(error, next) =>
           ReverseRenderRejected(error, next)
         case ReverseStepLimitExceeded(kind, next) =>
           ReverseStepLimitExceeded(kind, next))
  {
    reveal DriveReverseSpec();
  }

  method DriveReverse(
    state: ReverseState,
    limits: IndexedLimits,
    fuel: nat
  ) returns (outcome: ReverseStep)
    requires ReverseStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.NoReversePending?
    requires ValidReverseQueuedEids(state.queue)
    decreases fuel
    ensures outcome == DriveReverseSpec(state, limits, fuel)
    ensures outcome.ReverseAdvanced? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidReverseQueuedEids(outcome.state.queue)
    ensures outcome.ReverseYielded? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidReverseQueuedEids(outcome.state.queue)
    ensures outcome.ReverseNeedScan? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidReverseQueuedEids(outcome.state.queue) &&
              outcome.state.pending.AwaitingReverseScan? &&
              outcome.command == outcome.state.pending.command
    ensures outcome.ReverseEmitted? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidReverseQueuedEids(outcome.state.queue) &&
              outcome.eid in outcome.state.emitted &&
              outcome.ordinal + 1 == outcome.state.render.ordinal
    ensures outcome.ReverseComplete? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidReverseQueuedEids(outcome.state.queue)
    ensures outcome.ReverseRenderRejected? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidReverseQueuedEids(outcome.state.queue)
    ensures outcome.ReverseStepLimitExceeded? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits) &&
              ValidReverseQueuedEids(outcome.state.queue)
  {
    UnfoldDriveReverseSpec(state, limits, fuel);
    if fuel == 0 {
      return ReverseYielded(state);
    }
    var step := StepReverse(state, limits);
    match step
    case ReverseAdvanced(next) => {
      var driven := DriveReverse(next, limits, fuel - 1);
      return driven;
    }
    case ReverseEmitted(next, _, _) => {
      var driven := DriveReverse(next, limits, fuel - 1);
      return driven;
    }
    case ReverseYielded(next) =>
      return ReverseYielded(next);
    case ReverseNeedScan(next, command) =>
      return ReverseNeedScan(next, command);
    case ReverseComplete(next) =>
      return ReverseComplete(next);
    case ReverseRenderRejected(error, next) =>
      return ReverseRenderRejected(error, next);
    case ReverseStepLimitExceeded(kind, next) =>
      return ReverseStepLimitExceeded(kind, next);
  }

  method {:isolate_assertions} DriveReverseIterative(
    state: ReverseState,
    limits: IndexedLimits,
    fuel: nat
  ) returns (outcome: ReverseStep)
    requires ReverseStateInvariant(state)
    requires CountersWithinLimits(state.counters, limits)
    requires state.pending.NoReversePending?
    requires ValidReverseQueuedEids(state.queue)
    ensures outcome == DriveReverseSpec(state, limits, fuel)
  {
    var current := state;
    var remaining := fuel;
    while 0 < remaining
      invariant ReverseStateInvariant(current)
      invariant CountersWithinLimits(current.counters, limits)
      invariant current.pending.NoReversePending?
      invariant ValidReverseQueuedEids(current.queue)
      invariant
        DriveReverseSpec(current, limits, remaining) ==
        DriveReverseSpec(state, limits, fuel)
      decreases remaining
    {
      UnfoldDriveReverseSpec(current, limits, remaining);
      var step := StepReverse(current, limits);
      match step
      case ReverseAdvanced(next) => {
        current := next;
        remaining := remaining - 1;
      }
      case ReverseEmitted(next, _, _) => {
        current := next;
        remaining := remaining - 1;
      }
      case ReverseYielded(next) =>
        return ReverseYielded(next);
      case ReverseNeedScan(next, command) =>
        return ReverseNeedScan(next, command);
      case ReverseComplete(next) =>
        return ReverseComplete(next);
      case ReverseRenderRejected(error, next) =>
        return ReverseRenderRejected(error, next);
      case ReverseStepLimitExceeded(kind, next) =>
        return ReverseStepLimitExceeded(kind, next);
    }
    UnfoldDriveReverseSpec(current, limits, remaining);
    return ReverseYielded(current);
  }

  function RuleHead(rule: IndexedRule): Semantics.PermissionNode {
    match rule
    case RelationRule(head, _, _) => head
    case SelfPermissionRule(head, _) => head
    case ArrowRelationRule(head, _, _, _, _) => head
    case ArrowPermissionRule(head, _, _, _) => head
  }

  predicate ValidPermissionNode(node: Semantics.PermissionNode) {
    0 < |node.resourceType| && 0 < |node.permissionName|
  }

  predicate ValidIndexedRule(rule: IndexedRule) {
    match rule
    case RelationRule(head, relationEid, subjectType) =>
      ValidPermissionNode(head) &&
      0 <= relationEid &&
      0 < |subjectType|
    case SelfPermissionRule(head, targetNode) =>
      ValidPermissionNode(head) &&
      ValidPermissionNode(targetNode)
    case ArrowRelationRule(
      head,
      viaRelationEid,
      intermediateType,
      targetRelationEid,
      targetSubjectType
      ) =>
      ValidPermissionNode(head) &&
      0 <= viaRelationEid &&
      0 < |intermediateType| &&
      0 <= targetRelationEid &&
      0 < |targetSubjectType|
    case ArrowPermissionRule(
      head,
      viaRelationEid,
      intermediateType,
      targetNode
      ) =>
      ValidPermissionNode(head) &&
      0 <= viaRelationEid &&
      0 < |intermediateType| &&
      ValidPermissionNode(targetNode)
  }

  predicate ValidIndexedRules(rules: seq<IndexedRule>) {
    forall rule <- rules :: ValidIndexedRule(rule)
  }

  function AddForwardConsumer(
    consumers: map<Semantics.PermissionNode, seq<IndexedRule>>,
    rule: IndexedRule
  ): map<Semantics.PermissionNode, seq<IndexedRule>>
  {
    var target :=
      match rule
      case SelfPermissionRule(_, targetNode) => Some(targetNode)
      case ArrowPermissionRule(_, _, _, targetNode) => Some(targetNode)
      case RelationRule(_, _, _) => None
      case ArrowRelationRule(_, _, _, _, _) => None;
    if target.Some?
    then
      consumers[
      target.value :=
      [rule] +
      (if target.value in consumers
       then consumers[target.value]
       else [])
      ]
    else consumers
  }

  datatype OptionalPermissionNode =
    | None
    | Some(value: Semantics.PermissionNode)

  function ForwardConsumers(
    rules: seq<IndexedRule>
  ): map<Semantics.PermissionNode, seq<IndexedRule>>
    requires ValidIndexedRules(rules)
    ensures forall node <- ForwardConsumers(rules).Keys ::
              ValidIndexedRules(ForwardConsumers(rules)[node])
    decreases |rules|
  {
    if |rules| == 0 then
      map[]
    else
      AddForwardConsumer(
        ForwardConsumers(rules[1..]),
        rules[0]
      )
  }

  function AddRuleByNode(
    rulesByNode: map<Semantics.PermissionNode, seq<IndexedRule>>,
    rule: IndexedRule
  ): map<Semantics.PermissionNode, seq<IndexedRule>>
  {
    var head := RuleHead(rule);
    rulesByNode[
    head :=
    [rule] +
    (if head in rulesByNode
     then rulesByNode[head]
     else [])
    ]
  }

  function RulesByNode(
    rules: seq<IndexedRule>
  ): map<Semantics.PermissionNode, seq<IndexedRule>>
    requires ValidIndexedRules(rules)
    ensures forall node <- RulesByNode(rules).Keys ::
              ValidIndexedRules(RulesByNode(rules)[node])
    decreases |rules|
  {
    if |rules| == 0 then
      map[]
    else
      AddRuleByNode(RulesByNode(rules[1..]), rules[0])
  }

  predicate ValidCompiledTraversalPlan(
    plan: CompiledTraversalPlan
  ) {
    ValidIndexedRules(plan.rules) &&
    plan.forwardConsumers == ForwardConsumers(plan.rules) &&
    plan.rulesByNode == RulesByNode(plan.rules)
  }

  method CompileTraversalPlan(
    rules: seq<IndexedRule>
  ) returns (plan: CompiledTraversalPlan)
    requires ValidIndexedRules(rules)
    ensures ValidCompiledTraversalPlan(plan)
    ensures plan.rules == rules
  {
    return CompiledTraversalPlan(
        rules,
        ForwardConsumers(rules),
        RulesByNode(rules)
      );
  }

  function ForwardSeedWork(
    rule: IndexedRule,
    subjectType: string,
    subjectEid: int
  ): seq<ForwardWork>
    requires 0 <= subjectEid
  {
    match rule
    case RelationRule(head, relationEid, ruleSubjectType) =>
      if subjectType == ruleSubjectType
      then
        [ForwardStreamWork(
           ForwardStream(
             SubjectToResources(
               subjectType,
               subjectEid,
               relationEid,
               head.resourceType,
               NoBound
             ),
             [],
             true,
             ForwardGrant(head)
           )
         )]
      else []
    case ArrowRelationRule(
      head,
      viaRelationEid,
      intermediateType,
      targetRelationEid,
      targetSubjectType
      ) =>
      if subjectType == targetSubjectType
      then
        [ForwardStreamWork(
           ForwardStream(
             SubjectToResources(
               subjectType,
               subjectEid,
               targetRelationEid,
               intermediateType,
               NoBound
             ),
             [],
             true,
             ForwardArrowRelation(
               head,
               intermediateType,
               viaRelationEid,
               head.resourceType
             )
           )
         )]
      else []
    case SelfPermissionRule(_, _) => []
    case ArrowPermissionRule(_, _, _, _) => []
  }

  function ForwardSeedWorks(
    rules: seq<IndexedRule>,
    subjectType: string,
    subjectEid: int
  ): seq<ForwardWork>
    requires 0 <= subjectEid
    requires ValidIndexedRules(rules)
    ensures ValidForwardQueuedEids(
              ForwardSeedWorks(rules, subjectType, subjectEid)
            )
    decreases |rules|
  {
    if |rules| == 0 then
      []
    else
      ForwardSeedWork(rules[0], subjectType, subjectEid) +
      ForwardSeedWorks(rules[1..], subjectType, subjectEid)
  }

  function InitialCounters(
    queueDepth: nat,
    ruleApplications: nat
  ): ResourceCounters {
    ResourceCounters(
      0,
      0,
      0,
      queueDepth,
      queueDepth,
      queueDepth,
      0,
      0,
      ruleApplications,
      0,
      0
    )
  }

  function InitialRender(mode: RenderMode): RenderState
    requires ValidRenderMode(mode)
    ensures ValidRenderState(InitialRender(mode))
  {
    RenderState(
      mode,
      0,
      [],
      [],
      match mode
      case RenderPage(_, after) => after.NoCursorBound?
      case RenderBackwardPage(_, _) => false
      case RenderCount(_) => true
      case RenderAllCount => true
      case RenderBoolean(_) => true,
      false,
      false
    )
  }

  function ForwardRetainedLogicalUnits(state: ForwardState): nat {
    |state.queue| +
    ForwardBufferedValues(state.queue) +
    |state.consumers| +
    |state.seen| +
    |state.emitted| +
    |state.render.emitted| +
    |state.render.delivered| +
    (if state.pending.AwaitingForwardScan? then 1 else 0)
  }

  function ReverseRetainedLogicalUnits(state: ReverseState): nat {
    |state.queue| +
    ReverseBufferedValues(state.queue) +
    |state.rulesByNode| +
    |state.seenGoals| +
    |state.seenGrants| +
    |state.grantsByGoal| +
    |state.consumers| +
    |state.seenConsumers| +
    |state.emitted| +
    |state.render.emitted| +
    |state.render.delivered| +
    (if state.pending.AwaitingReverseScan? then 1 else 0)
  }

  lemma ForwardAllCountRetainsOneResultCollection(
    state: ForwardState
  )
    requires ForwardStateInvariant(state)
    requires state.render.mode.RenderAllCount?
    ensures
      ForwardRetainedLogicalUnits(state) >=
      |state.emitted|
  {
  }

  lemma ReverseAllCountRetainsOneResultCollection(
    state: ReverseState
  )
    requires ReverseStateInvariant(state)
    requires state.render.mode.RenderAllCount?
    ensures
      ReverseRetainedLogicalUnits(state) >=
      |state.emitted|
  {
  }

  lemma AllCountRenderStorageIsConstant(
    render: RenderState
  )
    requires ValidRenderState(render)
    requires render.mode.RenderAllCount?
    ensures |render.emitted| + |render.delivered| == 0
  {
  }

  function InitializeForwardSpec(
    rules: seq<IndexedRule>,
    seedRules: seq<IndexedRule>,
    requestScope: nat,
    subjectType: string,
    subjectEid: int,
    rootNode: Semantics.PermissionNode,
    resultType: string,
    mode: RenderMode,
    chunkSize: nat,
    limits: IndexedLimits
  ): ForwardInit
    requires ValidIndexedRules(rules)
    requires ValidIndexedRules(seedRules)
    requires forall rule <- seedRules :: rule in rules
    requires 0 < |subjectType|
    requires 0 <= subjectEid
    requires ValidPermissionNode(rootNode)
    requires 0 < |resultType|
    requires ValidRenderMode(mode)
    requires 0 < chunkSize
  {
    var queue :=
      ForwardSeedWorks(seedRules, subjectType, subjectEid);
    if |queue| > limits.maxQueuedWork
    then ForwardInitLimitExceeded(IndexedQueuedWork)
    else
      ForwardInitialized(
        ForwardState(
          queue,
          ForwardConsumers(rules),
          {},
          {},
          InitialRender(mode),
          rootNode,
          resultType,
          chunkSize,
          NoForwardPending,
          RequestSequence(requestScope, 0),
          InitialCounters(|queue|, |seedRules|)
        )
      )
  }

  method InitializeForward(
    rules: seq<IndexedRule>,
    seedRules: seq<IndexedRule>,
    requestScope: nat,
    subjectType: string,
    subjectEid: int,
    rootNode: Semantics.PermissionNode,
    resultType: string,
    mode: RenderMode,
    chunkSize: nat,
    limits: IndexedLimits
  ) returns (outcome: ForwardInit)
    requires ValidIndexedRules(rules)
    requires ValidIndexedRules(seedRules)
    requires forall rule <- seedRules :: rule in rules
    requires 0 < |subjectType|
    requires 0 <= subjectEid
    requires ValidPermissionNode(rootNode)
    requires 0 < |resultType|
    requires ValidRenderMode(mode)
    requires 0 < chunkSize
    ensures outcome ==
            InitializeForwardSpec(
              rules,
              seedRules,
              requestScope,
              subjectType,
              subjectEid,
              rootNode,
              resultType,
              mode,
              chunkSize,
              limits
            )
    ensures outcome.ForwardInitialized? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits)
    ensures outcome.ForwardInitialized? ==>
              outcome.state.queue ==
              ForwardSeedWorks(
                seedRules,
                subjectType,
                subjectEid
              ) &&
              outcome.state.consumers == ForwardConsumers(rules) &&
              outcome.state.seen == {} &&
              outcome.state.emitted == {} &&
              outcome.state.pending.NoForwardPending?
  {
    var queue :=
      ForwardSeedWorks(seedRules, subjectType, subjectEid);
    if |queue| > limits.maxQueuedWork {
      return ForwardInitLimitExceeded(IndexedQueuedWork);
    }
    return ForwardInitialized(
        ForwardState(
          queue,
          ForwardConsumers(rules),
          {},
          {},
          InitialRender(mode),
          rootNode,
          resultType,
          chunkSize,
          NoForwardPending,
          RequestSequence(requestScope, 0),
          InitialCounters(|queue|, |seedRules|)
        )
      );
  }

  method InitializeForwardCompiled(
    plan: CompiledTraversalPlan,
    seedRules: seq<IndexedRule>,
    requestScope: nat,
    subjectType: string,
    subjectEid: int,
    rootNode: Semantics.PermissionNode,
    resultType: string,
    mode: RenderMode,
    chunkSize: nat,
    limits: IndexedLimits
  ) returns (outcome: ForwardInit)
    requires ValidCompiledTraversalPlan(plan)
    requires ValidIndexedRules(seedRules)
    requires forall rule <- seedRules :: rule in plan.rules
    requires 0 < |subjectType|
    requires 0 <= subjectEid
    requires ValidPermissionNode(rootNode)
    requires 0 < |resultType|
    requires ValidRenderMode(mode)
    requires 0 < chunkSize
    ensures outcome ==
            InitializeForwardSpec(
              plan.rules,
              seedRules,
              requestScope,
              subjectType,
              subjectEid,
              rootNode,
              resultType,
              mode,
              chunkSize,
              limits
            )
    ensures outcome.ForwardInitialized? ==>
              ForwardStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits)
  {
    var queue :=
      ForwardSeedWorks(seedRules, subjectType, subjectEid);
    if |queue| > limits.maxQueuedWork {
      return ForwardInitLimitExceeded(IndexedQueuedWork);
    }
    return ForwardInitialized(
        ForwardState(
          queue,
          plan.forwardConsumers,
          {},
          {},
          InitialRender(mode),
          rootNode,
          resultType,
          chunkSize,
          NoForwardPending,
          RequestSequence(requestScope, 0),
          InitialCounters(|queue|, |seedRules|)
        )
      );
  }

  function InitializeReverseSpec(
    rules: seq<IndexedRule>,
    requestScope: nat,
    subjectType: string,
    rootNode: Semantics.PermissionNode,
    rootResourceEid: int,
    resultType: string,
    mode: RenderMode,
    chunkSize: nat,
    limits: IndexedLimits
  ): ReverseInit
    requires ValidIndexedRules(rules)
    requires 0 < |subjectType|
    requires ValidPermissionNode(rootNode)
    requires 0 <= rootResourceEid
    requires 0 < |resultType|
    requires ValidRenderMode(mode)
    requires 0 < chunkSize
  {
    if limits.maxQueuedWork == 0
    then ReverseInitLimitExceeded(IndexedQueuedWork)
    else
      var rootGoal :=
        ReverseGoalKey(rootNode, rootResourceEid);
      var queue := [ReverseGoalWork(rootGoal)];
      ReverseInitialized(
        ReverseState(
          queue,
          RulesByNode(rules),
          {},
          {},
          map[],
          map[],
          {},
          {},
          InitialRender(mode),
          rootNode,
          rootResourceEid,
          resultType,
          subjectType,
          chunkSize,
          NoReversePending,
          RequestSequence(requestScope, 0),
          InitialCounters(1, 0)
        )
      )
  }

  method InitializeReverse(
    rules: seq<IndexedRule>,
    requestScope: nat,
    subjectType: string,
    rootNode: Semantics.PermissionNode,
    rootResourceEid: int,
    resultType: string,
    mode: RenderMode,
    chunkSize: nat,
    limits: IndexedLimits
  ) returns (outcome: ReverseInit)
    requires ValidIndexedRules(rules)
    requires 0 < |subjectType|
    requires ValidPermissionNode(rootNode)
    requires 0 <= rootResourceEid
    requires 0 < |resultType|
    requires ValidRenderMode(mode)
    requires 0 < chunkSize
    ensures outcome ==
            InitializeReverseSpec(
              rules,
              requestScope,
              subjectType,
              rootNode,
              rootResourceEid,
              resultType,
              mode,
              chunkSize,
              limits
            )
    ensures outcome.ReverseInitialized? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits)
    ensures outcome.ReverseInitialized? ==>
              outcome.state.queue ==
              [ReverseGoalWork(
                 ReverseGoalKey(rootNode, rootResourceEid)
               )] &&
              outcome.state.rulesByNode == RulesByNode(rules) &&
              outcome.state.seenGoals == {} &&
              outcome.state.seenGrants == {} &&
              outcome.state.grantsByGoal == map[] &&
              outcome.state.consumers == map[] &&
              outcome.state.seenConsumers == {} &&
              outcome.state.emitted == {} &&
              outcome.state.pending.NoReversePending?
  {
    if limits.maxQueuedWork == 0 {
      return ReverseInitLimitExceeded(IndexedQueuedWork);
    }
    var rootGoal := ReverseGoalKey(rootNode, rootResourceEid);
    var queue := [ReverseGoalWork(rootGoal)];
    return ReverseInitialized(
        ReverseState(
          queue,
          RulesByNode(rules),
          {},
          {},
          map[],
          map[],
          {},
          {},
          InitialRender(mode),
          rootNode,
          rootResourceEid,
          resultType,
          subjectType,
          chunkSize,
          NoReversePending,
          RequestSequence(requestScope, 0),
          InitialCounters(1, 0)
        )
      );
  }

  method InitializeReverseCompiled(
    plan: CompiledTraversalPlan,
    requestScope: nat,
    subjectType: string,
    rootNode: Semantics.PermissionNode,
    rootResourceEid: int,
    resultType: string,
    mode: RenderMode,
    chunkSize: nat,
    limits: IndexedLimits
  ) returns (outcome: ReverseInit)
    requires ValidCompiledTraversalPlan(plan)
    requires 0 < |subjectType|
    requires ValidPermissionNode(rootNode)
    requires 0 <= rootResourceEid
    requires 0 < |resultType|
    requires ValidRenderMode(mode)
    requires 0 < chunkSize
    ensures outcome ==
            InitializeReverseSpec(
              plan.rules,
              requestScope,
              subjectType,
              rootNode,
              rootResourceEid,
              resultType,
              mode,
              chunkSize,
              limits
            )
    ensures outcome.ReverseInitialized? ==>
              ReverseStateInvariant(outcome.state) &&
              CountersWithinLimits(outcome.state.counters, limits)
  {
    if limits.maxQueuedWork == 0 {
      return ReverseInitLimitExceeded(IndexedQueuedWork);
    }
    var rootGoal := ReverseGoalKey(rootNode, rootResourceEid);
    var queue := [ReverseGoalWork(rootGoal)];
    return ReverseInitialized(
        ReverseState(
          queue,
          plan.rulesByNode,
          {},
          {},
          map[],
          map[],
          {},
          {},
          InitialRender(mode),
          rootNode,
          rootResourceEid,
          resultType,
          subjectType,
          chunkSize,
          NoReversePending,
          RequestSequence(requestScope, 0),
          InitialCounters(1, 0)
        )
      );
  }

}

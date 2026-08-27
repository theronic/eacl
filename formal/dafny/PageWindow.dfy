include "TemporalSafety.dfy"

module PageWindow {
  import TemporalSafety

  datatype Direction = Ascending | Descending

  datatype Presence<T> =
    | Absent
    | PresentNil
    | PresentValue(value: T)

  datatype RawPageRequest = RawPageRequest(
    first: Presence<int>,
    last: Presence<int>,
    after: Presence<nat>,
    before: Presence<nat>
  )

  datatype PageError =
    | BothDirections
    | BothBounds
    | AfterWithoutFirst
    | BeforeWithoutLast
    | NilAfter
    | NilBefore
    | NonPositiveSize
    | OversizedPage

  datatype NormalizedPageRequest =
    | ValidPageRequest(
        direction: Direction,
        size: nat,
        bound: Presence<nat>
      )
    | InvalidPageRequest(error: PageError)

  function NormalizePageRequest(
    raw: RawPageRequest,
    defaultSize: nat,
    maximumSize: nat
  ): NormalizedPageRequest
    ensures NormalizePageRequest(
              raw,
              defaultSize,
              maximumSize
            ).ValidPageRequest? ==>
              0 <
              NormalizePageRequest(
                raw,
                defaultSize,
                maximumSize
              ).size <= maximumSize
  {
    if !raw.first.Absent? && !raw.last.Absent? then
      InvalidPageRequest(BothDirections)
    else if !raw.after.Absent? && !raw.before.Absent? then
      InvalidPageRequest(BothBounds)
    else if !raw.after.Absent? && raw.first.Absent? then
      InvalidPageRequest(AfterWithoutFirst)
    else if !raw.before.Absent? && raw.last.Absent? then
      InvalidPageRequest(BeforeWithoutLast)
    else if raw.after.PresentNil? then
      InvalidPageRequest(NilAfter)
    else if raw.before.PresentNil? then
      InvalidPageRequest(NilBefore)
    else
      var requested :=
        if raw.first.PresentValue? then raw.first.value
        else if raw.last.PresentValue? then raw.last.value
        else defaultSize;
      if requested <= 0 then
        InvalidPageRequest(NonPositiveSize)
      else if maximumSize == 0 || requested > maximumSize then
        InvalidPageRequest(OversizedPage)
      else
        ValidPageRequest(
          if raw.last.Absent?
          then Ascending
          else Descending,
          requested,
          if raw.last.Absent? then raw.after else raw.before
        )
  }

  lemma ValidNormalizationHasSafeSize(
    raw: RawPageRequest,
    defaultSize: nat,
    maximumSize: nat
  )
    ensures NormalizePageRequest(
              raw,
              defaultSize,
              maximumSize
            ).ValidPageRequest? ==>
              0 <
              NormalizePageRequest(
                raw,
                defaultSize,
                maximumSize
              ).size <= maximumSize
  {
  }

  lemma InvalidCombinationsAreRejected(
    raw: RawPageRequest,
    defaultSize: nat,
    maximumSize: nat
  )
    requires (!raw.first.Absent? && !raw.last.Absent?) ||
             (!raw.after.Absent? && !raw.before.Absent?) ||
             (!raw.after.Absent? && raw.first.Absent?) ||
             (!raw.before.Absent? && raw.last.Absent?) ||
             raw.after.PresentNil? ||
             raw.before.PresentNil?
    ensures NormalizePageRequest(
              raw,
              defaultSize,
              maximumSize
            ).InvalidPageRequest?
  {
  }

  function Minimum(left: nat, right: nat): nat {
    if left < right then left else right
  }

  function WindowStart(
    length: nat,
    direction: Direction,
    size: nat,
    bound: Presence<nat>
  ): nat {
    if direction.Ascending? then
      if bound.PresentValue?
      then Minimum(length, bound.value + 1)
      else 0
    else
      var end :=
        if bound.PresentValue?
        then Minimum(length, bound.value)
        else length;
      if size < end then end - size else 0
  }

  function WindowEnd(
    length: nat,
    direction: Direction,
    size: nat,
    bound: Presence<nat>
  ): nat {
    if direction.Ascending? then
      Minimum(
        length,
        WindowStart(length, direction, size, bound) + size
      )
    else if bound.PresentValue? then
      Minimum(length, bound.value)
    else
      length
  }

  datatype Page<T> = Page(
    items: seq<T>,
    start: nat,
    end: nat,
    hasNext: bool,
    hasPrevious: bool
  )

  datatype KeysetPageDecision = KeysetPageDecision(
    takeCount: nat,
    reverseItems: bool,
    hasNext: bool,
    hasPrevious: bool
  )

  method DecideKeysetPage(
    direction: Direction,
    size: nat,
    boundPresent: bool,
    realizedCount: nat
  ) returns (decision: KeysetPageDecision)
    requires 0 < size
    requires realizedCount <= size + 1
    ensures decision.takeCount == Minimum(size, realizedCount)
    ensures decision.takeCount <= size
    ensures decision.reverseItems <==> direction.Descending?
    ensures decision.hasNext <==>
            0 < decision.takeCount &&
            (if direction.Ascending?
             then realizedCount > size
             else boundPresent)
    ensures decision.hasPrevious <==>
            0 < decision.takeCount &&
            (if direction.Ascending?
             then boundPresent
             else realizedCount > size)
  {
    var takeCount := Minimum(size, realizedCount);
    decision := KeysetPageDecision(
      takeCount,
      direction.Descending?,
      0 < takeCount &&
      (if direction.Ascending?
       then realizedCount > size
       else boundPresent),
      0 < takeCount &&
      (if direction.Ascending?
       then boundPresent
       else realizedCount > size)
    );
  }

  function PageValues<T>(
    values: seq<T>,
    direction: Direction,
    size: nat,
    bound: Presence<nat>
  ): Page<T>
    requires 0 < size
    ensures PageValues(
              values,
              direction,
              size,
              bound
            ).start <=
            PageValues(
              values,
              direction,
              size,
              bound
            ).end <= |values|
    ensures PageValues(
              values,
              direction,
              size,
              bound
            ).items ==
            values[
            PageValues(
              values,
              direction,
              size,
              bound
            ).start..
            PageValues(
              values,
              direction,
              size,
              bound
            ).end
            ]
    ensures |PageValues(
              values,
              direction,
              size,
              bound
            ).items| <= size
  {
    var start := WindowStart(|values|, direction, size, bound);
    var end := WindowEnd(|values|, direction, size, bound);
    Page(
      values[start..end],
      start,
      end,
      start < end && end < |values|,
      start < end && 0 < start
    )
  }

  datatype LogicalBoundary<T(==)> =
    | NoLogicalBoundary
    | LogicalBoundary(ordinal: nat, value: T)

  datatype CompletedLogicalPageDecision<T> =
    | CompletedLogicalPage(page: Page<T>)
    | StaleLogicalBoundary

  predicate LogicalBoundaryMatches<T(==)>(
    values: seq<T>,
    bound: LogicalBoundary<T>
  ) {
    bound.NoLogicalBoundary? ||
    (bound.ordinal < |values| && values[bound.ordinal] == bound.value)
  }

  function LogicalBoundaryPresence<T(==)>(
    bound: LogicalBoundary<T>
  ): Presence<nat> {
    if bound.NoLogicalBoundary?
    then Absent
    else PresentValue(bound.ordinal)
  }

  function DecideCompletedLogicalPage<T(==)>(
    values: seq<T>,
    direction: Direction,
    size: nat,
    bound: LogicalBoundary<T>
  ): CompletedLogicalPageDecision<T>
    requires 0 < size
  {
    if !LogicalBoundaryMatches(values, bound) then
      StaleLogicalBoundary
    else
      CompletedLogicalPage(
        PageValues(
          values,
          direction,
          size,
          LogicalBoundaryPresence(bound)
        )
      )
  }

  lemma AcceptedCompletedLogicalPageIsExactLogicalSlice<T>(
    values: seq<T>,
    direction: Direction,
    size: nat,
    bound: LogicalBoundary<T>
  )
    requires 0 < size
    requires LogicalBoundaryMatches(values, bound)
    ensures DecideCompletedLogicalPage(
              values,
              direction,
              size,
              bound
            ).CompletedLogicalPage?
    ensures DecideCompletedLogicalPage(
              values,
              direction,
              size,
              bound
            ).page ==
            PageValues(
              values,
              direction,
              size,
              LogicalBoundaryPresence(bound)
            )
  {
  }

  lemma MismatchedLogicalBoundaryIsStale<T>(
    values: seq<T>,
    direction: Direction,
    size: nat,
    bound: LogicalBoundary<T>
  )
    requires 0 < size
    requires !LogicalBoundaryMatches(values, bound)
    ensures DecideCompletedLogicalPage(
              values,
              direction,
              size,
              bound
            ).StaleLogicalBoundary?
  {
  }

  ghost predicate Unique<T>(values: seq<T>) {
    forall left, right | 0 <= left < right < |values| ::
      values[left] != values[right]
  }

  lemma SlicePreservesUniqueness<T>(
    values: seq<T>,
    start: nat,
    end: nat
  )
    requires Unique(values)
    requires start <= end <= |values|
    ensures Unique(values[start..end])
  {
  }

  lemma PagePreservesDeterministicSequence<T>(
    values: seq<T>,
    direction: Direction,
    size: nat,
    bound: Presence<nat>
  )
    requires 0 < size
    requires Unique(values)
    ensures Unique(PageValues(values, direction, size, bound).items)
    ensures forall item |
              item in
                PageValues(values, direction, size, bound).items ::
              item in values
  {
    var page := PageValues(values, direction, size, bound);
    SlicePreservesUniqueness(values, page.start, page.end);
  }

  function ForwardWalk<T>(
    values: seq<T>,
    size: nat,
    start: nat
  ): seq<T>
    requires 0 < size
    requires start <= |values|
    ensures ForwardWalk(values, size, start) == values[start..]
    decreases |values| - start
  {
    if start == |values| then
      []
    else
      var end := Minimum(|values|, start + size);
      values[start..end] + ForwardWalk(values, size, end)
  }

  lemma CompleteForwardWalkHasNoOmissionOrDuplicate<T>(
    values: seq<T>,
    size: nat
  )
    requires 0 < size
    requires Unique(values)
    ensures ForwardWalk(values, size, 0) == values
    ensures Unique(ForwardWalk(values, size, 0))
  {
  }

  lemma BackwardPageIsExactPrecedingWindow<T>(
    values: seq<T>,
    size: nat,
    before: nat
  )
    requires 0 < size
    ensures var page :=
              PageValues(
                values,
                Descending,
                size,
                PresentValue(before)
              );
            var end := Minimum(|values|, before);
            page.end == end &&
            page.start == (if size < end then end - size else 0) &&
            page.items == values[page.start..end]
  {
  }

  datatype ExactSelection =
    | ExactUnavailable
    | ExactSnapshot(
        graph: TemporalSafety.Graph,
        sourceIdentity: string,
        itemsProof: string
      )

  // Production attempts one ordered-generation proof for an eligible current
  // cursor. An unavailable proof binds the cursor to the exact selected
  // immutable snapshot; there is no selectable proof algorithm.
  datatype OrderedGenerationProofAvailability =
    | CompleteOrderedGenerationProof
    | OrderedGenerationProofUnavailable

  datatype CursorProofStrategy =
    | ManagedDependencyProof
    | ExactSnapshotProof

  function SelectCursorProofStrategy(
    availability: OrderedGenerationProofAvailability
  ): CursorProofStrategy {
    if availability.CompleteOrderedGenerationProof?
    then ManagedDependencyProof
    else ExactSnapshotProof
  }

  function CursorProofFrameCommands(
    availability: OrderedGenerationProofAvailability
  ): nat {
    if SelectCursorProofStrategy(availability).ManagedDependencyProof?
    then 1
    else 0
  }

  lemma UnavailableProofUsesExactSnapshotWithoutGenerationReads()
    ensures SelectCursorProofStrategy(
              OrderedGenerationProofUnavailable
            ).ExactSnapshotProof?
    ensures CursorProofFrameCommands(
              OrderedGenerationProofUnavailable
            ) == 0
  {
  }

  lemma CompleteProofUsesManagedDependencyIdentity()
    ensures SelectCursorProofStrategy(
              CompleteOrderedGenerationProof
            ).ManagedDependencyProof?
    ensures CursorProofFrameCommands(CompleteOrderedGenerationProof) == 1
  {
  }

  lemma OnlyCompleteOrderedGenerationProofUsesManagedIdentity(
    availability: OrderedGenerationProofAvailability
  )
    ensures CursorProofFrameCommands(availability) > 0 <==>
            availability.CompleteOrderedGenerationProof?
  {
  }

  datatype ContinuationRejectReason =
    | InvalidAuthentication
    | ScopeMismatch
    | CursorExpired
    | SnapshotUnavailable
    | HistoryDivergence

  datatype ContinuationDecision =
    | UseCurrent
    | UseExact(graph: TemporalSafety.Graph)
    | Reject(reason: ContinuationRejectReason)

  datatype RetainedAuthorizationPageDecision<T> =
    | AuthorizationPageCacheMiss
    | AuthorizationPageCacheHit(page: Page<T>)

  function DecideRetainedAuthorizationPage<T>(
    cacheEnabled: bool,
    sameClient: bool,
    sameGeneration: bool,
    sameOperation: bool,
    sameQuery: bool,
    samePageRequest: bool,
    sameOrderingAbi: bool,
    retained: Page<T>
  ): RetainedAuthorizationPageDecision<T> {
    if cacheEnabled &&
       sameClient &&
       sameGeneration &&
       sameOperation &&
       sameQuery &&
       samePageRequest &&
       sameOrderingAbi
    then AuthorizationPageCacheHit(retained)
    else AuthorizationPageCacheMiss
  }

  lemma MatchingAuthorizationPageScopeReusesExactPage<T>(
    retained: Page<T>
  )
    ensures DecideRetainedAuthorizationPage(
              true,
              true,
              true,
              true,
              true,
              true,
              true,
              retained
            ) == AuthorizationPageCacheHit(retained)
  {
  }

  lemma AuthorizationPageScopeMismatchCannotHit<T>(
    cacheEnabled: bool,
    sameClient: bool,
    sameGeneration: bool,
    sameOperation: bool,
    sameQuery: bool,
    samePageRequest: bool,
    sameOrderingAbi: bool,
    retained: Page<T>
  )
    requires !cacheEnabled ||
             !sameClient ||
             !sameGeneration ||
             !sameOperation ||
             !sameQuery ||
             !samePageRequest ||
             !sameOrderingAbi
    ensures DecideRetainedAuthorizationPage(
              cacheEnabled,
              sameClient,
              sameGeneration,
              sameOperation,
              sameQuery,
              samePageRequest,
              sameOrderingAbi,
              retained
            ).AuthorizationPageCacheMiss?
  {
  }

  function DecideContinuation(
    authenticated: bool,
    scopeMatches: bool,
    expired: bool,
    sourceIdentity: string,
    cursorSourceIdentity: string,
    currentProof: string,
    cursorProof: string,
    cursorGraph: TemporalSafety.Graph,
    exact: ExactSelection
  ): ContinuationDecision {
    if !authenticated then
      Reject(InvalidAuthentication)
    else if !scopeMatches ||
            sourceIdentity != cursorSourceIdentity
    then
      Reject(ScopeMismatch)
    else if expired then
        Reject(CursorExpired)
      else if currentProof == cursorProof then
        UseCurrent
      else
        match exact
        case ExactUnavailable =>
          Reject(SnapshotUnavailable)
        case ExactSnapshot(graph, exactSource, exactProof) =>
          if graph != cursorGraph ||
             exactSource != cursorSourceIdentity ||
             exactProof != cursorProof
          then
            Reject(HistoryDivergence)
          else
            UseExact(graph)
  }

  lemma CurrentContinuationRequiresEqualProof(
    authenticated: bool,
    scopeMatches: bool,
    expired: bool,
    sourceIdentity: string,
    cursorSourceIdentity: string,
    currentProof: string,
    cursorProof: string,
    cursorGraph: TemporalSafety.Graph,
    exact: ExactSelection
  )
    ensures DecideContinuation(
              authenticated,
              scopeMatches,
              expired,
              sourceIdentity,
              cursorSourceIdentity,
              currentProof,
              cursorProof,
              cursorGraph,
              exact
            ).UseCurrent? ==>
              authenticated &&
              scopeMatches &&
              !expired &&
              sourceIdentity == cursorSourceIdentity &&
              currentProof == cursorProof
  {
  }

  lemma ExactContinuationRequiresAuthenticatedExactProof(
    authenticated: bool,
    scopeMatches: bool,
    expired: bool,
    sourceIdentity: string,
    cursorSourceIdentity: string,
    currentProof: string,
    cursorProof: string,
    cursorGraph: TemporalSafety.Graph,
    exact: ExactSelection
  )
    ensures DecideContinuation(
              authenticated,
              scopeMatches,
              expired,
              sourceIdentity,
              cursorSourceIdentity,
              currentProof,
              cursorProof,
              cursorGraph,
              exact
            ).UseExact? ==>
              authenticated &&
              scopeMatches &&
              !expired &&
              sourceIdentity == cursorSourceIdentity &&
              currentProof != cursorProof &&
              exact.ExactSnapshot? &&
              exact.graph == cursorGraph &&
              exact.sourceIdentity == cursorSourceIdentity &&
              exact.itemsProof == cursorProof
  {
  }

  lemma ChangedProofNeverContinuesOnCurrent(
    cursorGraph: TemporalSafety.Graph,
    exact: ExactSelection,
    currentProof: string,
    cursorProof: string
  )
    requires currentProof != cursorProof
    ensures DecideContinuation(
              true,
              true,
              false,
              "source",
              "source",
              currentProof,
              cursorProof,
              cursorGraph,
              exact
            ) != UseCurrent
  {
  }

  lemma ChangedProofWithoutExactSnapshotIsRejected(
    currentProof: string,
    cursorProof: string,
    cursorGraph: TemporalSafety.Graph
  )
    requires currentProof != cursorProof
    ensures DecideContinuation(
              true,
              true,
              false,
              "source",
              "source",
              currentProof,
              cursorProof,
              cursorGraph,
              ExactUnavailable
            ) == Reject(SnapshotUnavailable)
  {
  }

  function DataScriptCurrentBasisContinuation(
    currentBasisProof: string,
    cursorBasisProof: string,
    cursorGraph: TemporalSafety.Graph
  ): ContinuationDecision {
    DecideContinuation(
      true,
      true,
      false,
      "datascript-current",
      "datascript-current",
      currentBasisProof,
      cursorBasisProof,
      cursorGraph,
      ExactUnavailable
    )
  }

  lemma DataScriptExactProofContinuesOnlyAtCurrentBasis(
    currentBasisProof: string,
    cursorBasisProof: string,
    cursorGraph: TemporalSafety.Graph
  )
    ensures DataScriptCurrentBasisContinuation(
              currentBasisProof,
              cursorBasisProof,
              cursorGraph
            ).UseCurrent? <==>
            currentBasisProof == cursorBasisProof
  {
  }

  lemma DataScriptChangedBasisCannotYieldCursorPage(
    currentBasisProof: string,
    cursorBasisProof: string,
    cursorGraph: TemporalSafety.Graph
  )
    requires currentBasisProof != cursorBasisProof
    ensures DataScriptCurrentBasisContinuation(
              currentBasisProof,
              cursorBasisProof,
              cursorGraph
            ) == Reject(SnapshotUnavailable)
  {
  }

  function PageFromNormalized<T>(
    values: seq<T>,
    request: NormalizedPageRequest
  ): Page<T> {
    match request
    case InvalidPageRequest(_) =>
      Page([], 0, 0, false, false)
    case ValidPageRequest(direction, size, bound) =>
      if size == 0 then
        Page([], 0, 0, false, false)
      else
        PageValues(values, direction, size, bound)
  }

  method PaginateRelationshipItems<T>(
    values: seq<T>,
    raw: RawPageRequest,
    defaultSize: nat,
    maximumSize: nat
  ) returns (result: NormalizedPageRequest, page: Page<T>)
    ensures result ==
            NormalizePageRequest(raw, defaultSize, maximumSize)
    ensures result.InvalidPageRequest? ==>
              page.items == []
    ensures page == PageFromNormalized(values, result)
  {
    result := NormalizePageRequest(raw, defaultSize, maximumSize);
    if result.InvalidPageRequest? {
      page := Page([], 0, 0, false, false);
      return;
    }
    ValidNormalizationHasSafeSize(
      raw,
      defaultSize,
      maximumSize
    );
    page := PageValues(
      values,
      result.direction,
      result.size,
      result.bound
    );
  }

}

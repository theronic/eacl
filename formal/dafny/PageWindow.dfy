include "Pagination.dfy"
include "TemporalSafety.dfy"

module PageWindow {
  import Pagination
  import TemporalSafety

  const MaximumCursorRebaseChunkLimit: nat := 16384

  newtype {:nativeType "number", "long"} CursorEid =
    value: int | 0 <= value <= 9007199254740991

  newtype {:nativeType "number", "long"} CursorChunkIndex =
    value: int | 0 <= value <= MaximumCursorRebaseChunkLimit

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
        direction: Pagination.Direction,
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
          then Pagination.Ascending
          else Pagination.Descending,
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
    direction: Pagination.Direction,
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
    direction: Pagination.Direction,
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
    direction: Pagination.Direction,
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
    direction: Pagination.Direction,
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

  ghost predicate Unique<T>(values: seq<T>) {
    forall left, right | 0 <= left < right < |values| ::
      values[left] != values[right]
  }

  datatype CursorBoundRebase =
    | CursorBoundRebased(ordinal: nat, inspectedCount: nat)
    | CursorBoundRestarted(inspectedCount: nat)

  lemma CursorRebaseAdapterChunkIsBounded(
    offset: nat,
    length: nat,
    chunkLimit: nat
  )
    requires offset < length
    requires 0 < chunkLimit <= MaximumCursorRebaseChunkLimit
    ensures var end :=
              if offset + chunkLimit < length
              then offset + chunkLimit
              else length;
            offset < end <= length &&
            end - offset <= chunkLimit &&
            end - offset <= MaximumCursorRebaseChunkLimit
  {
  }

  method RebaseCursorBound(
    values: array<CursorEid>,
    boundEid: CursorEid
  ) returns (decision: CursorBoundRebase)
    requires values.Length <= MaximumCursorRebaseChunkLimit
    ensures decision.CursorBoundRebased? ==>
              decision.ordinal < values.Length &&
              values[decision.ordinal] == boundEid &&
              decision.inspectedCount == decision.ordinal + 1 &&
              forall prior | 0 <= prior < decision.ordinal ::
                values[prior] != boundEid
    ensures decision.CursorBoundRestarted? ==>
              boundEid !in values[..] &&
              decision.inspectedCount == values.Length
    ensures boundEid in values[..] <==>
            decision.CursorBoundRebased?
    ensures decision.inspectedCount <= values.Length
  {
    var length := values.Length as CursorChunkIndex;
    var ordinal: CursorChunkIndex := 0;
    while ordinal < length
      invariant ordinal as int <= values.Length
      invariant length as int == values.Length
      invariant forall prior | 0 <= prior < ordinal as int ::
                  values[prior] != boundEid
      decreases length as int - ordinal as int
    {
      if values[ordinal] == boundEid {
        decision :=
          CursorBoundRebased(
            ordinal as nat,
            ordinal as nat + 1
          );
        return;
      }
      ordinal := ordinal + 1;
    }
    decision := CursorBoundRestarted(length as nat);
  }

  method RebaseCursorBoundChunked(
    values: array<CursorEid>,
    boundEid: CursorEid,
    chunkLimit: nat
  ) returns (decision: CursorBoundRebase)
    requires 0 < chunkLimit <= MaximumCursorRebaseChunkLimit
    ensures decision.CursorBoundRebased? ==>
              decision.ordinal < values.Length &&
              values[decision.ordinal] == boundEid &&
              decision.inspectedCount == decision.ordinal + 1 &&
              forall prior | 0 <= prior < decision.ordinal ::
                values[prior] != boundEid
    ensures decision.CursorBoundRestarted? ==>
              boundEid !in values[..] &&
              decision.inspectedCount == values.Length
    ensures boundEid in values[..] <==>
            decision.CursorBoundRebased?
    ensures decision.inspectedCount <= values.Length
  {
    var offset: nat := 0;
    while offset < values.Length
      invariant offset <= values.Length
      invariant forall prior | 0 <= prior < offset ::
                  values[prior] != boundEid
      decreases values.Length - offset
    {
      var end :=
        if offset + chunkLimit < values.Length
        then offset + chunkLimit
        else values.Length;
      CursorRebaseAdapterChunkIsBounded(
        offset,
        values.Length,
        chunkLimit
      );
      var ordinal := offset;
      while ordinal < end
        invariant offset <= ordinal <= end <= values.Length
        invariant forall prior | 0 <= prior < ordinal ::
                    values[prior] != boundEid
        decreases end - ordinal
      {
        if values[ordinal] == boundEid {
          decision :=
            CursorBoundRebased(ordinal, ordinal + 1);
          return;
        }
        ordinal := ordinal + 1;
      }
      offset := end;
    }
    decision := CursorBoundRestarted(values.Length);
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
    direction: Pagination.Direction,
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
                Pagination.Descending,
                size,
                PresentValue(before)
              );
            var end := Minimum(|values|, before);
            page.end == end &&
            page.start == (if size < end then end - size else 0) &&
            page.items == values[page.start..end]
  {
  }

  datatype ConsistencyMode =
    | RecoverCurrent
    | ExactSnapshotMode

  datatype ExactSelection =
    | ExactUnavailable
    | ExactSnapshot(
        graph: TemporalSafety.Graph,
        sourceIdentity: string,
        itemsProof: string
      )

  datatype ContinuationRejectReason =
    | InvalidAuthentication
    | ScopeMismatch
    | CursorExpired
    | CursorConflict
    | SnapshotUnavailable
    | HistoryDivergence

  datatype ContinuationDecision =
    | UseCurrent
    | RebaseCurrent
    | UseExact(graph: TemporalSafety.Graph)
    | Reject(reason: ContinuationRejectReason)

  datatype RelationshipPageOutcome<T> =
    | ReturnedCurrentPage(page: Page<T>)
    | ReturnedRecoveredPage(
        page: Page<T>,
        rebase: CursorBoundRebase
      )
    | ReturnedExactPage(
        graph: TemporalSafety.Graph,
        page: Page<T>
      )
    | RelationshipPageRejected(reason: ContinuationRejectReason)

  function ApplyCursorBoundRebase(
    raw: RawPageRequest,
    rebase: CursorBoundRebase
  ): RawPageRequest {
    var rebound :=
      if rebase.CursorBoundRebased?
      then PresentValue(rebase.ordinal)
      else Absent;
    RawPageRequest(
      raw.first,
      raw.last,
      if raw.after.Absent? then raw.after else rebound,
      if raw.before.Absent? then raw.before else rebound
    )
  }

  function DecideContinuation(
    authenticated: bool,
    scopeMatches: bool,
    expired: bool,
    sourceIdentity: string,
    cursorSourceIdentity: string,
    currentProof: string,
    cursorProof: string,
    mode: ConsistencyMode,
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
      else if mode.RecoverCurrent? then
        RebaseCurrent
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
    mode: ConsistencyMode,
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
              mode,
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
    mode: ConsistencyMode,
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
              mode,
              cursorGraph,
              exact
            ).UseExact? ==>
              authenticated &&
              scopeMatches &&
              !expired &&
              sourceIdentity == cursorSourceIdentity &&
              currentProof != cursorProof &&
              mode.ExactSnapshotMode? &&
              exact.ExactSnapshot? &&
              exact.graph == cursorGraph &&
              exact.sourceIdentity == cursorSourceIdentity &&
              exact.itemsProof == cursorProof
  {
  }

  lemma RecoverCurrentNeverRequiresRetainedHistory(
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
              RecoverCurrent,
              cursorGraph,
              exact
            ) == RebaseCurrent
  {
  }

  lemma RebasedContinuationRequiresAuthenticatedSameScope(
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
              RecoverCurrent,
              cursorGraph,
              exact
            ).RebaseCurrent? ==>
              authenticated &&
              scopeMatches &&
              !expired &&
              sourceIdentity == cursorSourceIdentity &&
              currentProof != cursorProof
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

  method PaginateRelationshipContinuation(
    currentValues: array<CursorEid>,
    exactValues: seq<CursorEid>,
    cursorBoundEid: CursorEid,
    raw: RawPageRequest,
    defaultSize: nat,
    maximumSize: nat,
    authenticated: bool,
    scopeMatches: bool,
    expired: bool,
    sourceIdentity: string,
    cursorSourceIdentity: string,
    currentProof: string,
    cursorProof: string,
    mode: ConsistencyMode,
    cursorGraph: TemporalSafety.Graph,
    exact: ExactSelection
  ) returns (outcome: RelationshipPageOutcome<CursorEid>)
    ensures outcome.ReturnedCurrentPage? ==>
              authenticated &&
              scopeMatches &&
              !expired &&
              sourceIdentity == cursorSourceIdentity &&
              currentProof == cursorProof &&
              outcome.page ==
              PageFromNormalized(
                currentValues[..],
                NormalizePageRequest(
                  raw,
                  defaultSize,
                  maximumSize
                )
              )
    ensures outcome.ReturnedExactPage? ==>
              authenticated &&
              scopeMatches &&
              !expired &&
              sourceIdentity == cursorSourceIdentity &&
              currentProof != cursorProof &&
              mode.ExactSnapshotMode? &&
              exact.ExactSnapshot? &&
              exact.graph == cursorGraph &&
              exact.sourceIdentity == cursorSourceIdentity &&
              exact.itemsProof == cursorProof &&
              outcome.graph == cursorGraph &&
              outcome.page ==
              PageFromNormalized(
                exactValues,
                NormalizePageRequest(
                  raw,
                  defaultSize,
                  maximumSize
                )
              )
    ensures outcome.ReturnedRecoveredPage? ==>
              authenticated &&
              scopeMatches &&
              !expired &&
              sourceIdentity == cursorSourceIdentity &&
              currentProof != cursorProof &&
              mode.RecoverCurrent? &&
              outcome.page ==
              PageFromNormalized(
                currentValues[..],
                NormalizePageRequest(
                  ApplyCursorBoundRebase(raw, outcome.rebase),
                  defaultSize,
                  maximumSize
                )
              ) &&
              (outcome.rebase.CursorBoundRebased? ==>
                 outcome.rebase.ordinal < currentValues.Length &&
                 currentValues[outcome.rebase.ordinal] ==
                 cursorBoundEid &&
                 outcome.rebase.inspectedCount ==
                 outcome.rebase.ordinal + 1) &&
              (outcome.rebase.CursorBoundRestarted? ==>
                 cursorBoundEid !in currentValues[..] &&
                 outcome.rebase.inspectedCount ==
                 currentValues.Length)
  {
    var decision := DecideContinuation(
      authenticated,
      scopeMatches,
      expired,
      sourceIdentity,
      cursorSourceIdentity,
      currentProof,
      cursorProof,
      mode,
      cursorGraph,
      exact
    );
    if decision.Reject? {
      return RelationshipPageRejected(decision.reason);
    }

    var normalized: NormalizedPageRequest;
    var page: Page<CursorEid>;
    if decision.UseCurrent? {
      normalized, page := PaginateRelationshipItems(
        currentValues[..],
        raw,
        defaultSize,
        maximumSize
      );
      CurrentContinuationRequiresEqualProof(
        authenticated,
        scopeMatches,
        expired,
        sourceIdentity,
        cursorSourceIdentity,
        currentProof,
        cursorProof,
        mode,
        cursorGraph,
        exact
      );
      return ReturnedCurrentPage(page);
    }

    if decision.RebaseCurrent? {
      var rebase := RebaseCursorBoundChunked(
        currentValues,
        cursorBoundEid,
        MaximumCursorRebaseChunkLimit
      );
      normalized, page := PaginateRelationshipItems(
        currentValues[..],
        ApplyCursorBoundRebase(raw, rebase),
        defaultSize,
        maximumSize
      );
      RebasedContinuationRequiresAuthenticatedSameScope(
        authenticated,
        scopeMatches,
        expired,
        sourceIdentity,
        cursorSourceIdentity,
        currentProof,
        cursorProof,
        cursorGraph,
        exact
      );
      return ReturnedRecoveredPage(page, rebase);
    }

    normalized, page := PaginateRelationshipItems(
      exactValues,
      raw,
      defaultSize,
      maximumSize
    );
    ExactContinuationRequiresAuthenticatedExactProof(
      authenticated,
      scopeMatches,
      expired,
      sourceIdentity,
      cursorSourceIdentity,
      currentProof,
      cursorProof,
      mode,
      cursorGraph,
      exact
    );
    return ReturnedExactPage(decision.graph, page);
  }
}

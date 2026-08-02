include "Pagination.dfy"
include "TemporalSafety.dfy"

module PageWindow {
  import Pagination
  import TemporalSafety

  datatype Presence<T> =
    | Absent
    | PresentNil
    | PresentValue(value: T)

  datatype RawPageRequest = RawPageRequest(
    first: Presence<int>,
    last: Presence<int>,
    after: Presence<nat>,
    before: Presence<nat>,
    hasLegacyLimit: bool,
    hasLegacyCursor: bool
  )

  datatype PageError =
    | LegacyPagination
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
    if raw.hasLegacyLimit || raw.hasLegacyCursor then
      InvalidPageRequest(LegacyPagination)
    else if !raw.first.Absent? && !raw.last.Absent? then
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
    requires raw.hasLegacyLimit ||
             raw.hasLegacyCursor ||
             (!raw.first.Absent? && !raw.last.Absent?) ||
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
    | MinimizeLatency
    | AtLeastAsFresh

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
    | UseExact(graph: TemporalSafety.Graph)
    | Reject(reason: ContinuationRejectReason)

  datatype RelationshipPageOutcome<T> =
    | ReturnedCurrentPage(page: Page<T>)
    | ReturnedExactPage(
        graph: TemporalSafety.Graph,
        page: Page<T>
      )
    | RelationshipPageRejected(reason: ContinuationRejectReason)

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
      else if mode.AtLeastAsFresh? then
        Reject(CursorConflict)
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
              mode.MinimizeLatency? &&
              exact.ExactSnapshot? &&
              exact.graph == cursorGraph &&
              exact.sourceIdentity == cursorSourceIdentity &&
              exact.itemsProof == cursorProof
  {
  }

  lemma AtLeastConflictNeverFallsBack(
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
              AtLeastAsFresh,
              cursorGraph,
              exact
            ) == Reject(CursorConflict)
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

  method PaginateRelationshipContinuation<T>(
    currentValues: seq<T>,
    exactValues: seq<T>,
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
  ) returns (outcome: RelationshipPageOutcome<T>)
    ensures outcome.ReturnedCurrentPage? ==>
              authenticated &&
              scopeMatches &&
              !expired &&
              sourceIdentity == cursorSourceIdentity &&
              currentProof == cursorProof &&
              outcome.page ==
              PageFromNormalized(
                currentValues,
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
              mode.MinimizeLatency? &&
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
    var page: Page<T>;
    if decision.UseCurrent? {
      normalized, page := PaginateRelationshipItems(
        currentValues,
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

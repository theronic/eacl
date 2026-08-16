// Exploratory lookahead/resume proof; intentionally excluded from release artifacts.
module LookaheadPagination {
  datatype BoundaryState<T> = BoundaryState(
    delivered: nat,
    discovered: nat,
    pending: seq<T>
  )

  function Min(left: nat, right: nat): nat {
    if left <= right then left else right
  }

  predicate ExactBoundary<T(==)>(
    complete: seq<T>,
    state: BoundaryState<T>
  ) {
    state.delivered <= state.discovered <= |complete| &&
    state.pending ==
      complete[state.delivered..state.discovered]
  }

  function Residual<T>(
    complete: seq<T>,
    state: BoundaryState<T>
  ): seq<T>
    requires state.discovered <= |complete|
  {
    state.pending + complete[state.discovered..]
  }

  lemma ExactBoundaryResidual<T>(
    complete: seq<T>,
    state: BoundaryState<T>
  )
    requires ExactBoundary(complete, state)
    ensures Residual(complete, state) ==
            complete[state.delivered..]
  {
    assert complete[state.delivered..] ==
           complete[state.delivered..state.discovered] +
           complete[state.discovered..];
  }

  function ReplayAt<T>(
    complete: seq<T>,
    delivered: nat
  ): BoundaryState<T>
    requires delivered <= |complete|
  {
    BoundaryState(
      delivered,
      delivered,
      []
    )
  }

  function LookaheadAt<T>(
    complete: seq<T>,
    delivered: nat
  ): BoundaryState<T>
    requires delivered <= |complete|
  {
    var discovered := Min(delivered + 1, |complete|);
    BoundaryState(
      delivered,
      discovered,
      complete[delivered..discovered]
    )
  }

  lemma ReplayBoundaryIsExact<T>(
    complete: seq<T>,
    delivered: nat
  )
    requires delivered <= |complete|
    ensures ExactBoundary(complete, ReplayAt(complete, delivered))
  {
  }

  lemma LookaheadBoundaryIsExact<T>(
    complete: seq<T>,
    delivered: nat
  )
    requires delivered <= |complete|
    ensures ExactBoundary(
              complete,
              LookaheadAt(complete, delivered)
            )
  {
  }

  lemma LookaheadDoesNotAdvanceDelivery<T>(
    complete: seq<T>,
    delivered: nat
  )
    requires delivered <= |complete|
    ensures LookaheadAt(complete, delivered).delivered ==
            delivered
  {
  }

  lemma LookaheadHasNextExactly<T>(
    complete: seq<T>,
    delivered: nat
  )
    requires delivered <= |complete|
    ensures |LookaheadAt(complete, delivered).pending| > 0 <==>
            delivered < |complete|
  {
  }

  lemma LookaheadRetainsNextResult<T>(
    complete: seq<T>,
    delivered: nat
  )
    requires delivered < |complete|
    ensures |LookaheadAt(complete, delivered).pending| == 1
    ensures LookaheadAt(complete, delivered).pending[0] ==
            complete[delivered]
  {
  }

  function NextPage<T>(
    complete: seq<T>,
    state: BoundaryState<T>,
    size: nat
  ): seq<T>
    requires ExactBoundary(complete, state)
  {
    var residual := Residual(complete, state);
    residual[..Min(size, |residual|)]
  }

  lemma ExactBoundariesHaveSameNextPage<T>(
    complete: seq<T>,
    left: BoundaryState<T>,
    right: BoundaryState<T>,
    size: nat
  )
    requires ExactBoundary(complete, left)
    requires ExactBoundary(complete, right)
    requires left.delivered == right.delivered
    ensures NextPage(complete, left, size) ==
            NextPage(complete, right, size)
  {
    ExactBoundaryResidual(complete, left);
    ExactBoundaryResidual(complete, right);
  }

  lemma LookaheadCheckpointEqualsReplay<T>(
    complete: seq<T>,
    delivered: nat,
    size: nat
  )
    requires delivered <= |complete|
    ensures NextPage(
              complete,
              LookaheadAt(complete, delivered),
              size
            ) ==
            NextPage(
              complete,
              ReplayAt(complete, delivered),
              size
            )
  {
    LookaheadBoundaryIsExact(complete, delivered);
    ReplayBoundaryIsExact(complete, delivered);
    ExactBoundariesHaveSameNextPage(
      complete,
      LookaheadAt(complete, delivered),
      ReplayAt(complete, delivered),
      size
    );
  }

  lemma PositiveNextPageStartsWithLookahead<T>(
    complete: seq<T>,
    delivered: nat,
    size: nat
  )
    requires delivered < |complete|
    requires size > 0
    ensures |NextPage(
               complete,
               LookaheadAt(complete, delivered),
               size
             )| > 0
    ensures NextPage(
              complete,
              LookaheadAt(complete, delivered),
              size
            )[0] == complete[delivered]
  {
    LookaheadBoundaryIsExact(complete, delivered);
    ExactBoundaryResidual(
      complete,
      LookaheadAt(complete, delivered)
    );
  }
}

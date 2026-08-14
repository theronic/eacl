// Refinement from an authenticated public edge cursor to the exact edge in
// one canonical result sequence. The public token carries a one-based edge
// ordinal and that edge's canonical external identity. Validation precedes
// conversion to an internal delivered-count boundary.
// Exploratory proof model; intentionally excluded from release artifacts.
include "RelayEdgePagination.dfy"

module EdgeBoundaryAuthentication {
  import P = StablePagination
  import R = RelayEdgePagination

  datatype AuthenticatedEdgeCursor = AuthenticatedEdgeCursor(
    edge: R.EdgeCursor,
    boundary: nat
  )

  datatype AuthenticatedBound =
    Unbounded |
    At(cursor: AuthenticatedEdgeCursor)

  predicate ValidCursor(
    context: P.Context,
    results: seq<nat>,
    cursor: AuthenticatedEdgeCursor
  ) {
    R.ValidEdgeCursor(context, |results|, cursor.edge) &&
    cursor.boundary == results[cursor.edge.ordinal - 1]
  }

  predicate ValidBound(
    context: P.Context,
    results: seq<nat>,
    bound: AuthenticatedBound
  ) {
    P.ValidContext(context) &&
    match bound
    case Unbounded => true
    case At(cursor) => ValidCursor(context, results, cursor)
  }

  function PlainBound(bound: AuthenticatedBound): R.Bound {
    match bound
    case Unbounded => R.Unbounded
    case At(cursor) => R.At(cursor.edge)
  }

  function ForwardWindow(
    context: P.Context,
    results: seq<nat>,
    after: AuthenticatedBound
  ): R.Window
    requires ValidBound(context, results, after)
  {
    R.ForwardWindow(context, |results|, PlainBound(after))
  }

  function BackwardWindow(
    context: P.Context,
    results: seq<nat>,
    before: AuthenticatedBound
  ): R.Window
    requires ValidBound(context, results, before)
  {
    R.BackwardWindow(context, |results|, PlainBound(before))
  }

  function MintStart(
    context: P.Context,
    results: seq<nat>,
    window: R.Window
  ): AuthenticatedEdgeCursor
    requires P.ValidContext(context)
    requires R.ValidWindow(|results|, window)
    requires window.start < window.end
  {
    AuthenticatedEdgeCursor(
      R.StartCursor(context, window),
      results[window.start]
    )
  }

  function MintEnd(
    context: P.Context,
    results: seq<nat>,
    window: R.Window
  ): AuthenticatedEdgeCursor
    requires P.ValidContext(context)
    requires R.ValidWindow(|results|, window)
    requires window.start < window.end
  {
    AuthenticatedEdgeCursor(
      R.EndCursor(context, window),
      results[window.end - 1]
    )
  }

  lemma PlainBoundIsValid(
    context: P.Context,
    results: seq<nat>,
    bound: AuthenticatedBound
  )
    requires ValidBound(context, results, bound)
    ensures R.ValidBound(context, |results|, PlainBound(bound))
  {
  }

  lemma MintedStartIsValid(
    context: P.Context,
    results: seq<nat>,
    window: R.Window
  )
    requires P.ValidContext(context)
    requires R.ValidWindow(|results|, window)
    requires window.start < window.end
    ensures ValidCursor(context, results, MintStart(context, results, window))
  {
    R.NonemptyWindowCursorsAreValid(context, |results|, window);
  }

  lemma MintedEndIsValid(
    context: P.Context,
    results: seq<nat>,
    window: R.Window
  )
    requires P.ValidContext(context)
    requires R.ValidWindow(|results|, window)
    requires window.start < window.end
    ensures ValidCursor(context, results, MintEnd(context, results, window))
  {
    R.NonemptyWindowCursorsAreValid(context, |results|, window);
  }

  lemma MintedBoundariesAreExact(
    context: P.Context,
    results: seq<nat>,
    window: R.Window
  )
    requires P.ValidContext(context)
    requires R.ValidWindow(|results|, window)
    requires window.start < window.end
    ensures MintStart(context, results, window).edge.ordinal ==
              window.start + 1
    ensures MintStart(context, results, window).boundary ==
              results[window.start]
    ensures MintEnd(context, results, window).edge.ordinal == window.end
    ensures MintEnd(context, results, window).boundary ==
              results[window.end - 1]
  {
  }

  lemma AuthenticatedAfterEndStartsAtCurrentEnd(
    context: P.Context,
    results: seq<nat>,
    window: R.Window
  )
    requires P.ValidContext(context)
    requires R.ValidWindow(|results|, window)
    requires window.start < window.end
    ensures ForwardWindow(
              context,
              results,
              At(MintEnd(context, results, window))
            ).start == window.end
  {
    MintedEndIsValid(context, results, window);
    R.AfterEndStartsAtCurrentEnd(context, |results|, window);
  }

  lemma AuthenticatedBeforeStartEndsAtCurrentStart(
    context: P.Context,
    results: seq<nat>,
    window: R.Window
  )
    requires P.ValidContext(context)
    requires R.ValidWindow(|results|, window)
    requires window.start < window.end
    ensures BackwardWindow(
              context,
              results,
              At(MintStart(context, results, window))
            ).end == window.start
  {
    MintedStartIsValid(context, results, window);
    R.BeforeStartEndsAtCurrentStart(context, |results|, window);
  }

  lemma OneAuthenticatedEdgeSupportsBothNavigationModes(
    context: P.Context,
    results: seq<nat>,
    cursor: AuthenticatedEdgeCursor
  )
    requires ValidCursor(context, results, cursor)
    ensures ForwardWindow(context, results, At(cursor)).start ==
              cursor.edge.ordinal
    ensures BackwardWindow(context, results, At(cursor)).end ==
              cursor.edge.ordinal - 1
  {
  }

  lemma WrongBoundaryIsRejected(
    context: P.Context,
    results: seq<nat>,
    edge: R.EdgeCursor,
    wrongBoundary: nat
  )
    requires R.ValidEdgeCursor(context, |results|, edge)
    requires wrongBoundary != results[edge.ordinal - 1]
    ensures !ValidCursor(
              context,
              results,
              AuthenticatedEdgeCursor(edge, wrongBoundary)
            )
  {
  }

  lemma ReplayBoundaryDriftIsRejected(
    context: P.Context,
    original: seq<nat>,
    replayed: seq<nat>,
    cursor: AuthenticatedEdgeCursor
  )
    requires ValidCursor(context, original, cursor)
    requires |replayed| >= cursor.edge.ordinal
    requires replayed[cursor.edge.ordinal - 1] != cursor.boundary
    ensures !ValidCursor(context, replayed, cursor)
  {
  }

  lemma ExactReplayPreservesCursor(
    context: P.Context,
    original: seq<nat>,
    replayed: seq<nat>,
    cursor: AuthenticatedEdgeCursor
  )
    requires ValidCursor(context, original, cursor)
    requires original == replayed
    ensures ValidCursor(context, replayed, cursor)
  {
  }

  lemma DifferentOrdinalWithSameBoundaryIsRejectedWhenUnique(
    context: P.Context,
    results: seq<nat>,
    original: AuthenticatedEdgeCursor,
    changedEdge: R.EdgeCursor
  )
    requires P.Unique(results)
    requires ValidCursor(context, results, original)
    requires R.ValidEdgeCursor(context, |results|, changedEdge)
    requires changedEdge.ordinal != original.edge.ordinal
    ensures !ValidCursor(
              context,
              results,
              AuthenticatedEdgeCursor(changedEdge, original.boundary)
            )
  {
    var left := original.edge.ordinal - 1;
    var right := changedEdge.ordinal - 1;
    if left < right {
      assert results[left] != results[right];
    } else {
      assert right < left;
      assert results[right] != results[left];
    }
  }
}

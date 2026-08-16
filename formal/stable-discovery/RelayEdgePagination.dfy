// Public Relay-style edge cursors over one canonical discovery sequence.
// Forward requests use `after` the page end edge; backward requests use
// `before` the page start edge and still display items in forward order.
// Exploratory proof model; intentionally excluded from release artifacts.
include "StablePagination.dfy"

module RelayEdgePagination {
  import P = StablePagination

  datatype EdgeCursor = EdgeCursor(
    context: P.Context,
    ordinal: nat // one-based ordinal of the represented edge
  )

  datatype Bound = Unbounded | At(cursor: EdgeCursor)
  datatype Window = Window(start: nat, end: nat)

  predicate ValidEdgeCursor(
    context: P.Context,
    resultCount: nat,
    cursor: EdgeCursor
  ) {
    P.ValidContext(context) &&
    cursor.context == context &&
    0 < cursor.ordinal <= resultCount
  }

  predicate ValidBound(
    context: P.Context,
    resultCount: nat,
    bound: Bound
  ) {
    P.ValidContext(context) &&
    match bound
    case Unbounded => true
    case At(cursor) => ValidEdgeCursor(context, resultCount, cursor)
  }

  predicate ValidWindow(resultCount: nat, window: Window) {
    window.start <= window.end <= resultCount
  }

  function ForwardWindow(
    context: P.Context,
    resultCount: nat,
    after: Bound
  ): Window
    requires ValidBound(context, resultCount, after)
  {
    var start :=
      match after
      case Unbounded => 0
      case At(cursor) => cursor.ordinal;
    Window(start, P.Min(start + context.pageSize, resultCount))
  }

  function BackwardWindow(
    context: P.Context,
    resultCount: nat,
    before: Bound
  ): Window
    requires ValidBound(context, resultCount, before)
  {
    var end :=
      match before
      case Unbounded => resultCount
      case At(cursor) => cursor.ordinal - 1;
    var start := if end <= context.pageSize
                 then 0
                 else end - context.pageSize;
    Window(start, end)
  }

  function Page<T>(results: seq<T>, window: Window): seq<T>
    requires ValidWindow(|results|, window)
  {
    results[window.start..window.end]
  }

  function StartCursor(
    context: P.Context,
    window: Window
  ): EdgeCursor
    requires window.start < window.end
  {
    EdgeCursor(context, window.start + 1)
  }

  function EndCursor(
    context: P.Context,
    window: Window
  ): EdgeCursor
    requires window.start < window.end
  {
    EdgeCursor(context, window.end)
  }

  predicate HasPrevious(window: Window) {
    window.start > 0
  }

  predicate HasNext(resultCount: nat, window: Window) {
    window.end < resultCount
  }

  lemma ForwardWindowIsValid(
    context: P.Context,
    resultCount: nat,
    after: Bound
  )
    requires ValidBound(context, resultCount, after)
    ensures ValidWindow(
              resultCount,
              ForwardWindow(context, resultCount, after)
            )
  {
  }

  lemma BackwardWindowIsValid(
    context: P.Context,
    resultCount: nat,
    before: Bound
  )
    requires ValidBound(context, resultCount, before)
    ensures ValidWindow(
              resultCount,
              BackwardWindow(context, resultCount, before)
            )
  {
  }

  lemma ForwardPageIsBounded<T>(
    context: P.Context,
    results: seq<T>,
    after: Bound
  )
    requires ValidBound(context, |results|, after)
    ensures var window := ForwardWindow(context, |results|, after);
            |Page(results, window)| <= context.pageSize
  {
    ForwardWindowIsValid(context, |results|, after);
  }

  lemma BackwardPageIsBounded<T>(
    context: P.Context,
    results: seq<T>,
    before: Bound
  )
    requires ValidBound(context, |results|, before)
    ensures var window := BackwardWindow(context, |results|, before);
            |Page(results, window)| <= context.pageSize
  {
    BackwardWindowIsValid(context, |results|, before);
  }

  lemma NonemptyWindowCursorsAreValid(
    context: P.Context,
    resultCount: nat,
    window: Window
  )
    requires P.ValidContext(context)
    requires ValidWindow(resultCount, window)
    requires window.start < window.end
    ensures ValidEdgeCursor(
              context, resultCount, StartCursor(context, window)
            )
    ensures ValidEdgeCursor(
              context, resultCount, EndCursor(context, window)
            )
  {
  }

  lemma AfterEndStartsAtCurrentEnd(
    context: P.Context,
    resultCount: nat,
    window: Window
  )
    requires P.ValidContext(context)
    requires ValidWindow(resultCount, window)
    requires window.start < window.end
    ensures ForwardWindow(
              context,
              resultCount,
              At(EndCursor(context, window))
            ).start == window.end
  {
    NonemptyWindowCursorsAreValid(context, resultCount, window);
  }

  lemma BeforeStartEndsAtCurrentStart(
    context: P.Context,
    resultCount: nat,
    window: Window
  )
    requires P.ValidContext(context)
    requires ValidWindow(resultCount, window)
    requires window.start < window.end
    ensures BackwardWindow(
              context,
              resultCount,
              At(StartCursor(context, window))
            ).end == window.start
  {
    NonemptyWindowCursorsAreValid(context, resultCount, window);
  }

  lemma ForwardNextPageIsContiguous<T>(
    context: P.Context,
    results: seq<T>,
    after: Bound
  )
    requires ValidBound(context, |results|, after)
    requires var current := ForwardWindow(context, |results|, after);
             current.start < current.end
    ensures var current := ForwardWindow(context, |results|, after);
            var next := ForwardWindow(
                          context,
                          |results|,
                          At(EndCursor(context, current))
                        );
            current.end == next.start &&
            Page(results, current) + Page(results, next) ==
              results[current.start..next.end]
  {
    var current := ForwardWindow(context, |results|, after);
    ForwardWindowIsValid(context, |results|, after);
    NonemptyWindowCursorsAreValid(context, |results|, current);
    var next := ForwardWindow(
                  context,
                  |results|,
                  At(EndCursor(context, current))
                );
    ForwardWindowIsValid(
      context, |results|, At(EndCursor(context, current))
    );
    assert current.end == next.start;
    assert results[current.start..next.end] ==
           results[current.start..current.end] +
           results[current.end..next.end];
  }

  lemma BackwardPreviousPageIsContiguous<T>(
    context: P.Context,
    results: seq<T>,
    current: Window
  )
    requires P.ValidContext(context)
    requires ValidWindow(|results|, current)
    requires current.start < current.end
    ensures var previous := BackwardWindow(
                             context,
                             |results|,
                             At(StartCursor(context, current))
                           );
            previous.end == current.start &&
            Page(results, previous) + Page(results, current) ==
              results[previous.start..current.end]
  {
    NonemptyWindowCursorsAreValid(context, |results|, current);
    var previous := BackwardWindow(
                      context,
                      |results|,
                      At(StartCursor(context, current))
                    );
    BackwardWindowIsValid(
      context, |results|, At(StartCursor(context, current))
    );
    assert previous.end == current.start;
    assert results[previous.start..current.end] ==
           results[previous.start..previous.end] +
           results[previous.end..current.end];
  }

  lemma BareLastIsCanonicalSuffix<T>(
    context: P.Context,
    results: seq<T>
  )
    requires P.ValidContext(context)
    ensures var window :=
              BackwardWindow(context, |results|, Unbounded);
            window.end == |results| &&
            Page(results, window) == results[window.start..]
  {
    BackwardWindowIsValid(context, |results|, Unbounded);
  }

  lemma EmptySequenceHasNoEdgeCursor(context: P.Context, ordinal: nat)
    requires P.ValidContext(context)
    ensures !ValidEdgeCursor(context, 0, EdgeCursor(context, ordinal))
  {
  }

  // The old page-start/subtract model returns overlapping [0,4) and [2,6)
  // for ten results at size four. Edge-cursor `before` instead yields the
  // exact non-overlapping chain [6,10), [2,6), [0,2).
  lemma ShortEndPageBackwardChainIsExact()
    ensures var context := P.Context(1, 2, 3, 4, 5, 4);
            var results := [0, 1, 2, 3, 4, 5, 6, 7, 8, 9];
            var last := BackwardWindow(context, |results|, Unbounded);
            var middle := BackwardWindow(
                            context,
                            |results|,
                            At(StartCursor(context, last))
                          );
            var first := BackwardWindow(
                           context,
                           |results|,
                           At(StartCursor(context, middle))
                         );
            last == Window(6, 10) &&
            middle == Window(2, 6) &&
            first == Window(0, 2) &&
            Page(results, first) +
              Page(results, middle) +
              Page(results, last) == results
  {
  }

  lemma DifferentPageSizeRejectsEdgeCursor(
    original: P.Context,
    changed: P.Context,
    resultCount: nat,
    cursor: EdgeCursor
  )
    requires ValidEdgeCursor(original, resultCount, cursor)
    requires original != changed
    requires cursor.context == original
    ensures !ValidEdgeCursor(changed, resultCount, cursor)
  {
  }
}

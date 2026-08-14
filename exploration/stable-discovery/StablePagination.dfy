// Internal exact delivered-count boundary used by reducer/checkpoint
// composition. Public Relay edge cursors are modeled separately in
// RelayEdgePagination.dfy.
module StablePagination {
  datatype Context = Context(
    basis: nat,
    query: nat,
    principal: nat,
    plan: nat,
    orderAbi: nat,
    pageSize: nat
  )

  datatype Cursor = Cursor(context: Context, ordinal: nat)

  predicate Unique<T(==)>(values: seq<T>) {
    forall left, right | 0 <= left < right < |values| ::
      values[left] != values[right]
  }

  predicate ValidContext(context: Context) {
    context.pageSize > 0
  }

  function Min(left: nat, right: nat): nat {
    if left <= right then left else right
  }

  predicate ValidCursor<T>(
    context: Context,
    results: seq<T>,
    cursor: Cursor
  ) {
    ValidContext(context) &&
    cursor.context == context &&
    cursor.ordinal <= |results|
  }

  function Page<T>(results: seq<T>, cursor: Cursor): seq<T>
    requires cursor.ordinal <= |results|
  {
    results[
      cursor.ordinal..
      Min(cursor.ordinal + cursor.context.pageSize, |results|)
    ]
  }

  function NextCursor<T>(results: seq<T>, cursor: Cursor): Cursor
    requires ValidCursor(cursor.context, results, cursor)
  {
    Cursor(
      cursor.context,
      Min(cursor.ordinal + cursor.context.pageSize, |results|)
    )
  }

  lemma NextCursorIsValid<T>(results: seq<T>, cursor: Cursor)
    requires ValidCursor(cursor.context, results, cursor)
    ensures ValidCursor(
              cursor.context,
              results,
              NextCursor(results, cursor)
            )
  {
  }

  lemma NextCursorProgressesWhenPageIsNonempty<T>(
    results: seq<T>,
    cursor: Cursor
  )
    requires ValidCursor(cursor.context, results, cursor)
    requires cursor.ordinal < |results|
    ensures NextCursor(results, cursor).ordinal > cursor.ordinal
  {
  }

  lemma NextOrdinalEqualsDeliveredPage<T>(
    results: seq<T>,
    cursor: Cursor
  )
    requires ValidCursor(cursor.context, results, cursor)
    ensures NextCursor(results, cursor).ordinal ==
            cursor.ordinal + |Page(results, cursor)|
  {
  }

  lemma AdjacentForwardPageItemsDiffer<T>(
    results: seq<T>,
    cursor: Cursor,
    left: nat,
    right: nat
  )
    requires Unique(results)
    requires ValidCursor(cursor.context, results, cursor)
    requires left < |Page(results, cursor)|
    requires right < |Page(results, NextCursor(results, cursor))|
    ensures Page(results, cursor)[left] !=
            Page(results, NextCursor(results, cursor))[right]
  {
    var next := NextCursor(results, cursor);
    assert Page(results, cursor)[left] ==
           results[cursor.ordinal + left];
    assert Page(results, next)[right] ==
           results[next.ordinal + right];
    assert cursor.ordinal + left < next.ordinal + right;
  }

  lemma DeterministicReplayHasSamePage<T>(
    context: Context,
    original: seq<T>,
    replayed: seq<T>,
    cursor: Cursor
  )
    requires ValidCursor(context, original, cursor)
    requires original == replayed
    ensures ValidCursor(context, replayed, cursor)
    ensures Page(original, cursor) == Page(replayed, cursor)
  {
  }

  lemma ContextMismatchIsRejected<T>(
    originalContext: Context,
    currentContext: Context,
    results: seq<T>,
    cursor: Cursor
  )
    requires cursor.context == originalContext
    requires originalContext != currentContext
    ensures !ValidCursor(currentContext, results, cursor)
  {
  }

  lemma SamePrefixDoesNotProveSameNextPage()
    ensures [1, 2, 3][..2] == [1, 2, 4][..2]
    ensures [1, 2, 3][2] != [1, 2, 4][2]
  {
  }
}

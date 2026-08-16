// Refinement bridge between the authenticated public cursor and an exact
// private checkpoint/lookahead boundary.
include "StablePagination.dfy"
include "LookaheadPagination.dfy"

module PaginationComposition {
  import P = StablePagination
  import L = LookaheadPagination

  predicate SameBoundary<T(==)>(
    complete: seq<T>,
    cursor: P.Cursor,
    state: L.BoundaryState<T>
  ) {
    P.ValidCursor(cursor.context, complete, cursor) &&
    L.ExactBoundary(complete, state) &&
    state.delivered == cursor.ordinal
  }

  lemma PublicPageEqualsCheckpointPage<T>(
    complete: seq<T>,
    cursor: P.Cursor,
    state: L.BoundaryState<T>
  )
    requires SameBoundary(complete, cursor, state)
    ensures P.Page(complete, cursor) ==
            L.NextPage(
              complete,
              state,
              cursor.context.pageSize
            )
  {
    L.ExactBoundaryResidual(complete, state);
    assert L.Residual(complete, state) ==
           complete[cursor.ordinal..];
    assert |complete[cursor.ordinal..]| ==
           |complete| - cursor.ordinal;
  }

  lemma NextOrdinalEqualsDeliveredPage<T>(
    complete: seq<T>,
    cursor: P.Cursor
  )
    requires P.ValidCursor(cursor.context, complete, cursor)
    ensures P.NextCursor(complete, cursor).ordinal ==
            cursor.ordinal + |P.Page(complete, cursor)|
  {
  }

  lemma ReplayAndCheckpointUseSamePublicPage<T>(
    complete: seq<T>,
    cursor: P.Cursor,
    checkpoint: L.BoundaryState<T>
  )
    requires SameBoundary(complete, cursor, checkpoint)
    ensures L.NextPage(
              complete,
              checkpoint,
              cursor.context.pageSize
            ) ==
            L.NextPage(
              complete,
              L.ReplayAt(complete, cursor.ordinal),
              cursor.context.pageSize
            )
  {
    L.ReplayBoundaryIsExact(complete, cursor.ordinal);
    L.ExactBoundariesHaveSameNextPage(
      complete,
      checkpoint,
      L.ReplayAt(complete, cursor.ordinal),
      cursor.context.pageSize
    );
  }
}

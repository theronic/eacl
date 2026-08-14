// Bounded host buffer: one page plus one lookahead, never complete delivered
// result history.
include "PaginationComposition.dfy"

module BoundedPageBuffer {
  import P = StablePagination
  import L = LookaheadPagination
  import C = PaginationComposition

  function Filled<T>(complete: seq<T>, cursor: P.Cursor): L.BoundaryState<T>
    requires P.ValidCursor(cursor.context, complete, cursor)
  {
    var discovered := P.Min(
                        cursor.ordinal + cursor.context.pageSize + 1,
                        |complete|
                      );
    L.BoundaryState(
      cursor.ordinal,
      discovered,
      complete[cursor.ordinal..discovered]
    )
  }

  function DeliverPage<T>(
    state: L.BoundaryState<T>,
    pageSize: nat
  ): L.BoundaryState<T>
    requires pageSize > 0
  {
    var deliveredNow := P.Min(pageSize, |state.pending|);
    L.BoundaryState(
      state.delivered + deliveredNow,
      state.discovered,
      state.pending[deliveredNow..]
    )
  }

  lemma FilledIsExact<T>(complete: seq<T>, cursor: P.Cursor)
    requires P.ValidCursor(cursor.context, complete, cursor)
    ensures L.ExactBoundary(complete, Filled(complete, cursor))
  {
  }

  lemma FilledIsPagePlusLookaheadBounded<T>(
    complete: seq<T>,
    cursor: P.Cursor
  )
    requires P.ValidCursor(cursor.context, complete, cursor)
    ensures |Filled(complete, cursor).pending| <=
            cursor.context.pageSize + 1
  {
  }

  lemma FilledPublicPageIsExact<T>(
    complete: seq<T>,
    cursor: P.Cursor
  )
    requires P.ValidCursor(cursor.context, complete, cursor)
    ensures P.Page(complete, cursor) ==
            L.NextPage(
              complete,
              Filled(complete, cursor),
              cursor.context.pageSize
            )
  {
    FilledIsExact(complete, cursor);
    C.PublicPageEqualsCheckpointPage(
      complete, cursor, Filled(complete, cursor)
    );
  }

  lemma DeliverFilledPagePreservesExactBoundary<T>(
    complete: seq<T>,
    cursor: P.Cursor
  )
    requires P.ValidCursor(cursor.context, complete, cursor)
    ensures L.ExactBoundary(
              complete,
              DeliverPage(
                Filled(complete, cursor),
                cursor.context.pageSize
              )
            )
  {
    FilledIsExact(complete, cursor);
  }

  lemma DeliverFilledPageUsesNextCursorOrdinal<T>(
    complete: seq<T>,
    cursor: P.Cursor
  )
    requires P.ValidCursor(cursor.context, complete, cursor)
    ensures DeliverPage(
              Filled(complete, cursor),
              cursor.context.pageSize
            ).delivered ==
            P.NextCursor(complete, cursor).ordinal
  {
  }

  lemma DeliverFilledPageRetainsAtMostLookahead<T>(
    complete: seq<T>,
    cursor: P.Cursor
  )
    requires P.ValidCursor(cursor.context, complete, cursor)
    ensures |DeliverPage(
               Filled(complete, cursor),
               cursor.context.pageSize
             ).pending| <= 1
  {
    FilledIsPagePlusLookaheadBounded(complete, cursor);
  }
}

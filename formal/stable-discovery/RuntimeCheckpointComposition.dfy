// Exact relation among the observation-rich reducer, history-free runtime
// core, public cursor boundary, and bounded pending page segment.
include "HistoryFreeReducer.dfy"
include "PaginationComposition.dfy"

module RuntimeCheckpointComposition {
  import R = StableReducer
  import H = HistoryFreeReducer
  import P = StablePagination
  import L = LookaheadPagination
  import C = PaginationComposition

  predicate ExactCheckpoint(
    specification: R.State,
    runtime: H.RuntimeState,
    complete: seq<nat>,
    cursor: P.Cursor,
    boundary: L.BoundaryState<nat>
  ) {
    H.Refines(specification, runtime) &&
    C.SameBoundary(complete, cursor, boundary) &&
    runtime.discovered == boundary.discovered &&
    specification.results == complete[..boundary.discovered]
  }

  lemma RuntimeScalarEqualsObservedPrefix(
    specification: R.State,
    runtime: H.RuntimeState,
    complete: seq<nat>,
    cursor: P.Cursor,
    boundary: L.BoundaryState<nat>
  )
    requires ExactCheckpoint(
               specification, runtime, complete, cursor, boundary
             )
    ensures runtime.discovered == |specification.results|
    ensures runtime.discovered == boundary.discovered
    ensures |specification.results| == boundary.discovered
  {
    H.RuntimeDiscoveredCountMatchesObservation(specification, runtime);
  }

  lemma PendingIsExactlyUndeliveredObservedSuffix(
    specification: R.State,
    runtime: H.RuntimeState,
    complete: seq<nat>,
    cursor: P.Cursor,
    boundary: L.BoundaryState<nat>
  )
    requires ExactCheckpoint(
               specification, runtime, complete, cursor, boundary
             )
    ensures boundary.pending ==
            specification.results[cursor.ordinal..]
  {
  }

  lemma CheckpointPublicPageMatchesReplay(
    specification: R.State,
    runtime: H.RuntimeState,
    complete: seq<nat>,
    cursor: P.Cursor,
    boundary: L.BoundaryState<nat>
  )
    requires ExactCheckpoint(
               specification, runtime, complete, cursor, boundary
             )
    ensures P.Page(complete, cursor) ==
            L.NextPage(
              complete,
              boundary,
              cursor.context.pageSize
            )
  {
    C.PublicPageEqualsCheckpointPage(complete, cursor, boundary);
  }
}

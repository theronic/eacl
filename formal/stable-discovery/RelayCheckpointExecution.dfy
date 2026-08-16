// Operational refinement from public authenticated Relay edge cursors to
// history-free forward reducer checkpoints. Forward `after` resumes at the
// edge ordinal. Backward `before` resumes at the previous page start, runs
// forward through the cursor edge as one validation lookahead, and returns
// the preceding prefix in canonical order.
// Exploratory proof model; intentionally excluded from release artifacts.
include "EdgeBoundaryAuthentication.dfy"
include "TargetedResultDriver.dfy"

module RelayCheckpointExecution {
  import P = StablePagination
  import R = RelayEdgePagination
  import A = EdgeBoundaryAuthentication
  import S = StableReducer
  import H = HistoryFreeReducer
  import D = TargetedResultDriver

  datatype LastBoundary = NoBoundary | Boundary(value: nat)

  datatype Checkpoint = Checkpoint(
    delivered: nat,
    lastBoundary: LastBoundary
  )

  predicate ExactCheckpoint(results: seq<nat>, checkpoint: Checkpoint) {
    checkpoint.delivered <= |results| &&
    checkpoint.lastBoundary ==
      (if checkpoint.delivered == 0
       then NoBoundary
       else Boundary(results[checkpoint.delivered - 1]))
  }

  function ReplayAt(results: seq<nat>, delivered: nat): Checkpoint
    requires delivered <= |results|
  {
    Checkpoint(
      delivered,
      if delivered == 0
      then NoBoundary
      else Boundary(results[delivered - 1])
    )
  }

  predicate CursorMatchesCheckpoint(
    cursor: A.AuthenticatedEdgeCursor,
    checkpoint: Checkpoint
  ) {
    checkpoint.delivered == cursor.edge.ordinal &&
    checkpoint.lastBoundary == Boundary(cursor.boundary)
  }

  function ForwardResume(
    context: P.Context,
    results: seq<nat>,
    cursor: A.AuthenticatedEdgeCursor
  ): Checkpoint
    requires A.ValidCursor(context, results, cursor)
  {
    ReplayAt(results, cursor.edge.ordinal)
  }

  function BackwardResume(
    context: P.Context,
    results: seq<nat>,
    cursor: A.AuthenticatedEdgeCursor
  ): Checkpoint
    requires A.ValidCursor(context, results, cursor)
  {
    var window := A.BackwardWindow(context, results, A.At(cursor));
    ReplayAt(results, window.start)
  }

  function BackwardExecution(
    context: P.Context,
    results: seq<nat>,
    cursor: A.AuthenticatedEdgeCursor
  ): seq<nat>
    requires A.ValidCursor(context, results, cursor)
  {
    var window := A.BackwardWindow(context, results, A.At(cursor));
    // `window.end == ordinal - 1`; include the represented cursor edge at
    // index `ordinal - 1` as the one validation lookahead.
    results[window.start..cursor.edge.ordinal]
  }

  function BackwardPageFromExecution(execution: seq<nat>): seq<nat>
    requires 0 < |execution|
  {
    execution[..|execution| - 1]
  }

  lemma ReplayAtIsExact(results: seq<nat>, delivered: nat)
    requires delivered <= |results|
    ensures ExactCheckpoint(results, ReplayAt(results, delivered))
  {
  }

  lemma ForwardResumeIsExactAndMatchesCursor(
    context: P.Context,
    results: seq<nat>,
    cursor: A.AuthenticatedEdgeCursor
  )
    requires A.ValidCursor(context, results, cursor)
    ensures ExactCheckpoint(
              results,
              ForwardResume(context, results, cursor)
            )
    ensures CursorMatchesCheckpoint(
              cursor,
              ForwardResume(context, results, cursor)
            )
  {
    ReplayAtIsExact(results, cursor.edge.ordinal);
  }

  lemma WrongBoundaryDoesNotMatchExactForwardCheckpoint(
    context: P.Context,
    results: seq<nat>,
    cursor: A.AuthenticatedEdgeCursor,
    wrongBoundary: nat
  )
    requires A.ValidCursor(context, results, cursor)
    requires wrongBoundary != cursor.boundary
    ensures !CursorMatchesCheckpoint(
              A.AuthenticatedEdgeCursor(cursor.edge, wrongBoundary),
              ForwardResume(context, results, cursor)
            )
  {
  }

  lemma ForwardResumeStartsAtAfterBoundary(
    context: P.Context,
    results: seq<nat>,
    cursor: A.AuthenticatedEdgeCursor
  )
    requires A.ValidCursor(context, results, cursor)
    ensures ForwardResume(context, results, cursor).delivered ==
              A.ForwardWindow(context, results, A.At(cursor)).start
  {
    A.OneAuthenticatedEdgeSupportsBothNavigationModes(
      context, results, cursor
    );
  }

  lemma BackwardResumeIsExactAtPageStart(
    context: P.Context,
    results: seq<nat>,
    cursor: A.AuthenticatedEdgeCursor
  )
    requires A.ValidCursor(context, results, cursor)
    ensures ExactCheckpoint(
              results,
              BackwardResume(context, results, cursor)
            )
    ensures BackwardResume(context, results, cursor).delivered ==
              A.BackwardWindow(context, results, A.At(cursor)).start
  {
    var window := A.BackwardWindow(context, results, A.At(cursor));
    A.PlainBoundIsValid(context, results, A.At(cursor));
    R.BackwardWindowIsValid(
      context, |results|, R.At(cursor.edge)
    );
    ReplayAtIsExact(results, window.start);
  }

  lemma BackwardExecutionIsPagePlusBoundaryLookahead(
    context: P.Context,
    results: seq<nat>,
    cursor: A.AuthenticatedEdgeCursor
  )
    requires A.ValidCursor(context, results, cursor)
    ensures var window := A.BackwardWindow(
                            context, results, A.At(cursor)
                          );
            |BackwardExecution(context, results, cursor)| ==
              window.end - window.start + 1
    ensures |BackwardExecution(context, results, cursor)| <=
              context.pageSize + 1
  {
    var window := A.BackwardWindow(context, results, A.At(cursor));
    A.PlainBoundIsValid(context, results, A.At(cursor));
    R.BackwardWindowIsValid(
      context, |results|, R.At(cursor.edge)
    );
    A.OneAuthenticatedEdgeSupportsBothNavigationModes(
      context, results, cursor
    );
    assert window.end == cursor.edge.ordinal - 1;
    assert |results[window.start..cursor.edge.ordinal]| ==
           cursor.edge.ordinal - window.start;
    assert cursor.edge.ordinal - window.start ==
           window.end - window.start + 1;
    assert window.end - window.start <= context.pageSize;
  }

  lemma BackwardExecutionValidatesBoundaryAndReturnsExactPage(
    context: P.Context,
    results: seq<nat>,
    cursor: A.AuthenticatedEdgeCursor
  )
    requires A.ValidCursor(context, results, cursor)
    ensures var execution := BackwardExecution(context, results, cursor);
            0 < |execution| &&
            execution[|execution| - 1] == cursor.boundary
    ensures var window := A.BackwardWindow(
                            context, results, A.At(cursor)
                          );
            BackwardPageFromExecution(
              BackwardExecution(context, results, cursor)
            ) == R.Page(results, window)
  {
    var window := A.BackwardWindow(context, results, A.At(cursor));
    var execution := BackwardExecution(context, results, cursor);
    A.PlainBoundIsValid(context, results, A.At(cursor));
    R.BackwardWindowIsValid(
      context, |results|, R.At(cursor.edge)
    );
    A.OneAuthenticatedEdgeSupportsBothNavigationModes(
      context, results, cursor
    );
    assert window.end == cursor.edge.ordinal - 1;
    assert window.start < cursor.edge.ordinal;
    assert |execution| == cursor.edge.ordinal - window.start;
    assert execution[|execution| - 1] ==
           results[cursor.edge.ordinal - 1];
    assert results[cursor.edge.ordinal - 1] == cursor.boundary;
    assert BackwardPageFromExecution(execution) ==
           results[window.start..window.end];
  }

  lemma BackwardBeforeFirstEdgeReturnsEmptyPageWithOneValidationItem(
    context: P.Context,
    results: seq<nat>
  )
    requires P.ValidContext(context)
    requires 0 < |results|
    ensures var edge := R.EdgeCursor(context, 1);
            var cursor := A.AuthenticatedEdgeCursor(edge, results[0]);
            var execution := BackwardExecution(context, results, cursor);
            A.ValidCursor(context, results, cursor) &&
            |execution| == 1 &&
            BackwardPageFromExecution(execution) == []
  {
  }

  lemma HistoryFreeBackwardDriverIsBoundedAndExact(
    context: P.Context,
    complete: seq<nat>,
    cursor: A.AuthenticatedEdgeCursor,
    program: S.Program,
    specification: S.State,
    runtime: H.RuntimeState,
    fuel: nat
  )
    requires A.ValidCursor(context, complete, cursor)
    requires H.Refines(specification, runtime)
    requires var window := A.BackwardWindow(
                             context, complete, A.At(cursor)
                           );
             specification.results == complete[..window.start]
    requires var completedSpecification :=
               D.SpecificationRunToTarget(
                 program,
                 specification,
                 cursor.edge.ordinal,
                 fuel
               );
             completedSpecification.results ==
               complete[..cursor.edge.ordinal]
    ensures var completed := D.RunToTarget(
                               program,
                               D.Initial(runtime),
                               cursor.edge.ordinal,
                               fuel
                             );
            completed.buffered ==
              BackwardExecution(context, complete, cursor)
    ensures var completed := D.RunToTarget(
                               program,
                               D.Initial(runtime),
                               cursor.edge.ordinal,
                               fuel
                             );
            |completed.buffered| <= context.pageSize + 1
  {
    var window := A.BackwardWindow(context, complete, A.At(cursor));
    var completedSpecification := D.SpecificationRunToTarget(
                                   program,
                                   specification,
                                   cursor.edge.ordinal,
                                   fuel
                                 );
    var completed := D.RunToTarget(
                       program,
                       D.Initial(runtime),
                       cursor.edge.ordinal,
                       fuel
                     );
    H.RuntimeDiscoveredCountMatchesObservation(specification, runtime);
    assert runtime.discovered == window.start;
    D.InitialRefines(specification, runtime);
    D.RunToTargetRefines(
      program,
      specification,
      runtime.discovered,
      D.Initial(runtime),
      cursor.edge.ordinal,
      fuel
    );
    H.RuntimeDiscoveredCountMatchesObservation(
      completedSpecification, completed.runtime
    );
    assert completed.runtime.discovered == cursor.edge.ordinal;
    D.FreshRunBufferEqualsDiscoveredDelta(
      program,
      specification,
      runtime,
      cursor.edge.ordinal,
      fuel
    );
    D.ReachedTargetBufferIsExactSuffix(
      program,
      specification,
      runtime,
      cursor.edge.ordinal,
      fuel
    );
    assert completed.buffered ==
           completedSpecification.results[window.start..];
    assert completedSpecification.results[window.start..] ==
           complete[window.start..cursor.edge.ordinal];
    assert completed.buffered ==
           BackwardExecution(context, complete, cursor);
    BackwardExecutionIsPagePlusBoundaryLookahead(
      context, complete, cursor
    );
  }
}

// One logical scan occurrence may issue many physical range reads. Advancing
// its physical cursor replaces the current frontier occurrence; it does not
// admit a new logical identity for every chunk.
// Exploratory proof model; intentionally excluded from release artifacts.
include "StableReducer.dfy"

module LogicalScanCursor {
  import R = StableReducer

  datatype Cursor = Cursor(position: nat, extent: nat)

  datatype State = State(
    stack: seq<nat>,
    admitted: set<nat>
  )

  predicate Exact(state: State) {
    R.Unique(state.stack) &&
    R.SeqSet(state.stack) <= state.admitted
  }

  function Completed(state: State): set<nat> {
    state.admitted - R.SeqSet(state.stack)
  }

  predicate ValidCursor(cursor: Cursor) {
    cursor.position < cursor.extent
  }

  function NextPosition(cursor: Cursor, limit: nat): nat
    requires ValidCursor(cursor)
    requires 0 < limit
  {
    if cursor.position + limit < cursor.extent then
      cursor.position + limit
    else
      cursor.extent
  }

  function Residual(
    logicalId: nat,
    cursor: Cursor,
    nextPosition: nat
  ): seq<nat>
    requires ValidCursor(cursor)
    requires cursor.position < nextPosition <= cursor.extent
  {
    if nextPosition < cursor.extent then [logicalId] else []
  }

  function Integrate(
    state: State,
    candidates: seq<nat>,
    cursor: Cursor,
    nextPosition: nat
  ): State
    requires Exact(state)
    requires |state.stack| > 0
    requires ValidCursor(cursor)
    requires cursor.position < nextPosition <= cursor.extent
  {
    var current := state.stack[0];
    var admission := R.Admit(candidates, state.admitted);
    State(
      admission.newValues +
        (Residual(current, cursor, nextPosition) + state.stack[1..]),
      admission.seen
    )
  }

  function ExpandPure(
    state: State,
    candidates: seq<nat>
  ): State
  {
    if |state.stack| == 0 then state
    else
      var admission := R.Admit(candidates, state.admitted);
      State(admission.newValues + state.stack[1..], admission.seen)
  }

  lemma NextPositionStrictlyProgresses(cursor: Cursor, limit: nat)
    requires ValidCursor(cursor)
    requires 0 < limit
    ensures cursor.position < NextPosition(cursor, limit) <= cursor.extent
    ensures cursor.extent - NextPosition(cursor, limit) <
            cursor.extent - cursor.position
  {
  }

  lemma ResidualKeepsLogicalIdentity(
    logicalId: nat,
    cursor: Cursor,
    nextPosition: nat
  )
    requires ValidCursor(cursor)
    requires cursor.position < nextPosition <= cursor.extent
    ensures R.Unique(Residual(logicalId, cursor, nextPosition))
    ensures R.SeqSet(Residual(logicalId, cursor, nextPosition)) <=
            {logicalId}
    ensures |Residual(logicalId, cursor, nextPosition)| > 0 ==>
            Residual(logicalId, cursor, nextPosition)[0] == logicalId
  {
  }

  lemma IntegratePreservesExactState(
    state: State,
    candidates: seq<nat>,
    cursor: Cursor,
    nextPosition: nat
  )
    requires Exact(state)
    requires |state.stack| > 0
    requires ValidCursor(cursor)
    requires cursor.position < nextPosition <= cursor.extent
    ensures Exact(Integrate(state, candidates, cursor, nextPosition))
    ensures Integrate(state, candidates, cursor, nextPosition).admitted ==
            state.admitted + R.SeqSet(candidates)
  {
    var current := state.stack[0];
    var tail := state.stack[1..];
    var admission := R.Admit(candidates, state.admitted);
    var residual := Residual(current, cursor, nextPosition);

    R.SeqSetHeadTail(state.stack);
    assert current in state.admitted;
    assert R.Unique(tail);
    assert current !in R.SeqSet(tail);
    assert R.SeqSet(tail) <= state.admitted;

    R.AdmitProperties(candidates, state.admitted);
    ResidualKeepsLogicalIdentity(current, cursor, nextPosition);
    assert R.SeqSet(residual) <= state.admitted;
    assert R.SeqSet(residual) * R.SeqSet(tail) == {};
    R.ConcatenationIsUnique(residual, tail);
    R.SeqSetConcat(residual, tail);
    assert R.SeqSet(residual + tail) <= state.admitted;

    assert R.SeqSet(admission.newValues) *
           R.SeqSet(residual + tail) == {};
    R.ConcatenationIsUnique(admission.newValues, residual + tail);
    R.SeqSetConcat(admission.newValues, residual + tail);
    assert R.SeqSet(admission.newValues + (residual + tail)) <=
           admission.seen;
  }

  lemma AdvancingResidualDoesNotGrowAdmission(
    state: State,
    cursor: Cursor,
    limit: nat
  )
    requires Exact(state)
    requires |state.stack| > 0
    requires ValidCursor(cursor)
    requires 0 < limit
    ensures Exact(
              Integrate(
                state, [], cursor, NextPosition(cursor, limit)
              )
            )
    ensures Integrate(
              state, [], cursor, NextPosition(cursor, limit)
            ).admitted == state.admitted
    ensures |Integrate(
               state, [], cursor, NextPosition(cursor, limit)
             ).stack| == |state.stack|
            || |Integrate(
                  state, [], cursor, NextPosition(cursor, limit)
                ).stack| + 1 == |state.stack|
  {
    NextPositionStrictlyProgresses(cursor, limit);
    IntegratePreservesExactState(
      state, [], cursor, NextPosition(cursor, limit)
    );
  }

  lemma NonterminalChunkDoesNotCompleteLogicalWork(
    state: State,
    candidates: seq<nat>,
    cursor: Cursor,
    nextPosition: nat
  )
    requires Exact(state)
    requires |state.stack| > 0
    requires ValidCursor(cursor)
    requires cursor.position < nextPosition < cursor.extent
    ensures Completed(
              Integrate(state, candidates, cursor, nextPosition)
            ) == Completed(state)
  {
    var current := state.stack[0];
    var tail := state.stack[1..];
    var admission := R.Admit(candidates, state.admitted);

    R.SeqSetHeadTail(state.stack);
    R.AdmitProperties(candidates, state.admitted);
    R.SeqSetConcat([current], tail);
    R.SeqSetConcat(admission.newValues, [current] + tail);
    assert Residual(current, cursor, nextPosition) == [current];
    assert R.SeqSet(state.stack) == {current} + R.SeqSet(tail);
    assert R.SeqSet(
             admission.newValues + ([current] + tail)
           ) ==
           R.SeqSet(admission.newValues) +
             ({current} + R.SeqSet(tail));
  }

  lemma TerminalChunkCompletesCurrentLogicalWorkExactlyOnce(
    state: State,
    candidates: seq<nat>,
    cursor: Cursor
  )
    requires Exact(state)
    requires |state.stack| > 0
    requires ValidCursor(cursor)
    ensures Completed(
              Integrate(state, candidates, cursor, cursor.extent)
            ) == Completed(state) + {state.stack[0]}
  {
    var current := state.stack[0];
    var tail := state.stack[1..];
    var admission := R.Admit(candidates, state.admitted);

    R.SeqSetHeadTail(state.stack);
    R.AdmitProperties(candidates, state.admitted);
    R.SeqSetConcat(admission.newValues, tail);
    assert Residual(current, cursor, cursor.extent) == [];
    assert current in state.admitted;
    assert current !in R.SeqSet(tail);
    assert R.SeqSet(state.stack) == {current} + R.SeqSet(tail);
    assert R.SeqSet(admission.newValues + tail) ==
           R.SeqSet(admission.newValues) + R.SeqSet(tail);
    assert R.SeqSet(admission.newValues) * state.admitted == {};
    assert Integrate(state, candidates, cursor, cursor.extent) ==
           State(admission.newValues + tail, admission.seen);

    var next := Integrate(state, candidates, cursor, cursor.extent);
    forall value: nat | value in Completed(next)
      ensures value in Completed(state) + {current}
    {
      assert value in admission.seen;
      assert value !in R.SeqSet(admission.newValues);
      assert value !in R.SeqSet(tail);
      if value !in state.admitted {
        assert value in R.SeqSet(candidates);
        assert value in R.SeqSet(candidates) - state.admitted;
        assert value in R.SeqSet(admission.newValues);
        assert false;
      }
      if value != current {
        assert value !in R.SeqSet(state.stack);
        assert value in Completed(state);
      }
    }
    assert Completed(next) <= Completed(state) + {current};

    forall value: nat | value in Completed(state) + {current}
      ensures value in Completed(next)
    {
      if value == current {
        assert value in admission.seen;
        assert value !in R.SeqSet(admission.newValues);
        assert value !in R.SeqSet(tail);
      } else {
        assert value in Completed(state);
        assert value in state.admitted;
        assert value !in R.SeqSet(state.stack);
        assert value !in R.SeqSet(admission.newValues);
        assert value !in R.SeqSet(tail);
        assert value in admission.seen;
      }
    }
    assert Completed(state) + {current} <= Completed(next);
  }

  datatype LogicalKey = LogicalKey(logicalId: nat)
  datatype PhysicalKey = PhysicalKey(logicalId: nat, position: nat)

  lemma CursorChangesPhysicalIdentityButNotLogicalIdentity(
    logicalId: nat,
    cursor: Cursor,
    nextPosition: nat
  )
    requires ValidCursor(cursor)
    requires cursor.position < nextPosition <= cursor.extent
    ensures LogicalKey(logicalId) == LogicalKey(logicalId)
    ensures PhysicalKey(logicalId, cursor.position) !=
            PhysicalKey(logicalId, nextPosition)
  {
  }

  // Scan 0 yields [1, 2, 3]. Work 1 discovers 4, and work 4 discovers
  // [3, 2]. A wide first chunk admits 2 before recursive work can reach it;
  // a one-value chunk lets the recursive path admit 3 then 2 first. Both runs
  // are set-correct, but their stable discovery order differs.
  lemma ChunkWidthCanChangeDiscoveryOrderUnderOverlap()
    ensures var initial := State([0], {0});
            var cursor := Cursor(0, 3);
            var wide0 := Integrate(initial, [1, 2, 3], cursor, 3);
            var wide1 := ExpandPure(wide0, [4]);
            var wide2 := ExpandPure(wide1, [3, 2]);
            var narrow0 := Integrate(initial, [1], cursor, 1);
            var narrow1 := ExpandPure(narrow0, [4]);
            var narrow2 := ExpandPure(narrow1, [3, 2]);
            wide2.admitted == narrow2.admitted == {0, 1, 2, 3, 4} &&
            wide2.stack == [2, 3] &&
            narrow2.stack == [3, 2, 0] &&
            wide2.stack[0] != narrow2.stack[0]
  {
    assert Exact(State([0], {0}));
  }
}

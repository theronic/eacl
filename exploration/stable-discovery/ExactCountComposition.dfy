// Exhausted exact-count composition for the accepted history-free reducer.
// This proves the initial release count route can use the scalar discovered
// count after exact exhaustion. It does not authorize an independent optimized
// backend count route; such a route still needs its own denotation refinement.
// Exploratory proof model; intentionally excluded from release artifacts.
include "ReducerCompleteness.dfy"
include "HistoryFreeReducer.dfy"

module ExactCountComposition {
  import R = StableReducer
  import C = ReducerCompleteness
  import H = HistoryFreeReducer

  ghost predicate ExactCountState(
    program: R.Program,
    roots: seq<nat>,
    state: R.State
  ) {
    C.ExecutionInvariant(program, roots, state) &&
    R.SeqSet(state.results) ==
      C.Processed(state) * program.resultNodes
  }

  lemma InitialExactCountState(
    program: R.Program,
    roots: seq<nat>
  )
    requires R.ValidProgram(program)
    requires R.SeqSet(roots) <= R.Nodes(program)
    ensures ExactCountState(
              program,
              roots,
              R.Initial(program, roots)
            )
  {
    C.InitialExecutionInvariant(program, roots);
    var initial := R.Initial(program, roots);
    var admission := R.Admit(roots, {});
    R.AdmitProperties(roots, {});
    assert initial.admitted == R.SeqSet(roots);
    assert R.SeqSet(initial.stack) == R.SeqSet(roots);
    assert initial.results == [];
    assert C.Processed(initial) == {};
  }

  lemma StepPreservesExactCountState(
    program: R.Program,
    roots: seq<nat>,
    state: R.State
  )
    requires ExactCountState(program, roots, state)
    ensures ExactCountState(
              program,
              roots,
              R.Step(program, state)
            )
  {
    C.StepPreservesExecutionInvariant(program, roots, state);
    if |state.stack| > 0 {
      var node := state.stack[0];
      var after := R.Step(program, state);
      C.ProcessedAfterStep(program, state);
      if node in program.resultNodes {
        assert after.results == state.results + [node];
        R.SeqSetConcat(state.results, [node]);
        assert R.SeqSet(after.results) ==
               R.SeqSet(state.results) + {node};
      } else {
        assert after.results == state.results;
      }
      assert (C.Processed(state) + {node}) *
             program.resultNodes ==
             (C.Processed(state) * program.resultNodes) +
             (if node in program.resultNodes then {node} else {});
    }
  }

  lemma UniqueSequenceCardinality<T>(values: seq<T>)
    requires R.Unique(values)
    ensures |R.SeqSet(values)| == |values|
    decreases |values|
  {
    if |values| > 0 {
      R.SeqSetHeadTail(values);
      UniqueSequenceCardinality(values[1..]);
      assert values[0] !in R.SeqSet(values[1..]);
      assert {values[0]} * R.SeqSet(values[1..]) == {};
      assert |{values[0]} + R.SeqSet(values[1..])| ==
             1 + |R.SeqSet(values[1..])|;
    }
  }

  lemma ExhaustedResultsAreExact(
    program: R.Program,
    roots: seq<nat>,
    state: R.State
  )
    requires ExactCountState(program, roots, state)
    requires |state.stack| == 0
    ensures R.SeqSet(state.results) ==
              C.ReachableNodes(program, roots) * program.resultNodes
    ensures |state.results| ==
              |C.ReachableNodes(program, roots) * program.resultNodes|
  {
    C.ExhaustedReducerIsComplete(program, roots, state);
    assert C.Processed(state) == state.admitted;
    assert state.admitted == C.ReachableNodes(program, roots);
    assert R.Unique(state.results);
    UniqueSequenceCardinality(state.results);
  }

  lemma ExhaustedRuntimeCountIsExact(
    program: R.Program,
    roots: seq<nat>,
    specification: R.State,
    runtime: H.RuntimeState
  )
    requires ExactCountState(program, roots, specification)
    requires |specification.stack| == 0
    requires H.Refines(specification, runtime)
    ensures runtime.discovered ==
              |C.ReachableNodes(program, roots) * program.resultNodes|
  {
    H.RuntimeDiscoveredCountMatchesObservation(specification, runtime);
    ExhaustedResultsAreExact(program, roots, specification);
  }

  function NonResultFixture(): R.Program {
    R.Program([[1], []], {1})
  }

  function NonResultFixtureEnd(): R.State {
    R.Run(
      NonResultFixture(),
      R.Initial(NonResultFixture(), [0]),
      2
    )
  }

  // Mutation control: admitted work is not the answer count. The fixture
  // admits one non-result node and one result node, so counting the admitted
  // set overstates the exact answer cardinality.
  lemma CountingAllAdmittedWorkIsWrong()
    ensures |NonResultFixtureEnd().stack| == 0
    ensures |NonResultFixtureEnd().admitted| == 2
    ensures |NonResultFixtureEnd().results| == 1
    ensures |NonResultFixtureEnd().admitted| !=
              |NonResultFixtureEnd().results|
  {
  }
}

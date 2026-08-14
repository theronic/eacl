// Exploratory logical-cost proof; physical/JVM costs require refinement.
include "ReducerCompleteness.dfy"

module ReducerCost {
  import R = StableReducer
  import C = ReducerCompleteness

  lemma SubsetCardinality<T>(small: set<T>, large: set<T>)
    requires small <= large
    ensures |small| <= |large|
    decreases |small|
  {
    if small != {} {
      var value :| value in small;
      assert small - {value} <= large - {value};
      SubsetCardinality(small - {value}, large - {value});
    }
  }

  lemma UniqueSeqSetCardinality<T>(values: seq<T>)
    requires R.Unique(values)
    ensures |R.SeqSet(values)| == |values|
    decreases |values|
  {
    if |values| > 0 {
      R.SeqSetHeadTail(values);
      UniqueSeqSetCardinality(values[1..]);
      assert values[0] !in R.SeqSet(values[1..]);
      assert |{values[0]} + R.SeqSet(values[1..])| ==
             1 + |R.SeqSet(values[1..])|;
    }
  }

  lemma AdmittedPartitionsIntoProcessedAndPending(
    program: R.Program,
    roots: seq<nat>,
    state: R.State
  )
    requires C.ExecutionInvariant(program, roots, state)
    ensures state.admitted ==
            C.Processed(state) + R.SeqSet(state.stack)
    ensures C.Processed(state) * R.SeqSet(state.stack) == {}
  {
    assert R.SeqSet(state.stack) <= state.admitted;
    assert C.Processed(state) ==
           state.admitted - R.SeqSet(state.stack);
  }

  lemma RetainedLogicalWorkIsBounded(
    program: R.Program,
    roots: seq<nat>,
    state: R.State
  )
    requires C.ExecutionInvariant(program, roots, state)
    ensures |state.admitted| <= |R.Nodes(program)|
    ensures |C.Processed(state)| <= |R.Nodes(program)|
    ensures |state.stack| <= |R.Nodes(program)|
  {
    assert state.admitted <= C.ReachableNodes(program, roots);
    assert C.ReachableNodes(program, roots) <= R.Nodes(program);
    assert C.Processed(state) <= state.admitted;
    assert R.SeqSet(state.stack) <= state.admitted;
    SubsetCardinality(state.admitted, R.Nodes(program));
    SubsetCardinality(C.Processed(state), R.Nodes(program));
    SubsetCardinality(R.SeqSet(state.stack), R.Nodes(program));
    UniqueSeqSetCardinality(state.stack);
    assert |R.SeqSet(state.stack)| == |state.stack|;
  }

  lemma OneStepProcessesOneFreshNode(
    program: R.Program,
    roots: seq<nat>,
    state: R.State
  )
    requires C.ExecutionInvariant(program, roots, state)
    requires |state.stack| > 0
    ensures state.stack[0] !in C.Processed(state)
    ensures C.Processed(R.Step(program, state)) ==
            C.Processed(state) + {state.stack[0]}
    ensures |C.Processed(R.Step(program, state))| ==
            |C.Processed(state)| + 1
  {
    var node := state.stack[0];
    R.SeqSetHeadTail(state.stack);
    assert node in R.SeqSet(state.stack);
    assert node in state.admitted;
    assert node !in C.Processed(state);
    C.ProcessedAfterStep(program, state);
  }

  lemma OneStepPreservesLogicalBound(
    program: R.Program,
    roots: seq<nat>,
    state: R.State
  )
    requires C.ExecutionInvariant(program, roots, state)
    ensures C.ExecutionInvariant(
              program,
              roots,
              R.Step(program, state)
            )
    ensures |C.Processed(R.Step(program, state))| <=
            |R.Nodes(program)|
  {
    C.StepPreservesExecutionInvariant(program, roots, state);
    RetainedLogicalWorkIsBounded(
      program, roots, R.Step(program, state)
    );
  }
}

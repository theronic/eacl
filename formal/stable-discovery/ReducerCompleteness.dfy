// Exploratory proof model; intentionally excluded from release artifacts.
include "StableReducer.dfy"

module ReducerCompleteness {
  import R = StableReducer

  lemma LastBelongsToSeqSet<T>(values: seq<T>)
    requires |values| > 0
    ensures values[|values| - 1] in R.SeqSet(values)
    decreases |values|
  {
    if |values| > 1 {
      LastBelongsToSeqSet(values[1..]);
    }
  }

  ghost predicate PathEdges(
    program: R.Program,
    path: seq<nat>
  )
    decreases |path|
  {
    |path| <= 1 ||
    (path[1] in R.SeqSet(R.Successors(program, path[0])) &&
     PathEdges(program, path[1..]))
  }

  ghost predicate ValidPath(
    program: R.Program,
    roots: seq<nat>,
    path: seq<nat>
  ) {
    |path| > 0 &&
    path[0] in R.SeqSet(roots) &&
    R.SeqSet(path) <= R.Nodes(program) &&
    PathEdges(program, path)
  }

  ghost predicate Reachable(
    program: R.Program,
    roots: seq<nat>,
    node: nat
  ) {
    exists path: seq<nat> ::
      ValidPath(program, roots, path) &&
      path[|path| - 1] == node
  }

  ghost function ReachableNodes(
    program: R.Program,
    roots: seq<nat>
  ): set<nat> {
    set node: nat | node in R.Nodes(program) &&
                    Reachable(program, roots, node)
  }

  function Processed(state: R.State): set<nat> {
    state.admitted - R.SeqSet(state.stack)
  }

  ghost predicate ExecutionInvariant(
    program: R.Program,
    roots: seq<nat>,
    state: R.State
  ) {
    R.ExactState(program, state) &&
    R.SeqSet(roots) <= state.admitted &&
    state.admitted <= ReachableNodes(program, roots) &&
    forall node | node in Processed(state) ::
      R.SeqSet(R.Successors(program, node)) <= state.admitted
  }

  lemma SingletonPathIsReachable(
    program: R.Program,
    roots: seq<nat>,
    node: nat
  )
    requires R.SeqSet(roots) <= R.Nodes(program)
    requires node in R.SeqSet(roots)
    ensures Reachable(program, roots, node)
  {
    assert ValidPath(program, roots, [node]);
  }

  lemma AppendPreservesPathEdges(
    program: R.Program,
    path: seq<nat>,
    successor: nat
  )
    requires |path| > 0
    requires PathEdges(program, path)
    requires successor in
             R.SeqSet(R.Successors(program, path[|path| - 1]))
    ensures PathEdges(program, path + [successor])
    decreases |path|
  {
    if |path| > 1 {
      AppendPreservesPathEdges(
        program, path[1..], successor
      );
      assert (path + [successor])[1..] ==
             path[1..] + [successor];
    }
  }

  lemma ReachableSuccessor(
    program: R.Program,
    roots: seq<nat>,
    node: nat,
    successor: nat
  )
    requires R.ValidProgram(program)
    requires Reachable(program, roots, node)
    requires successor in R.SeqSet(R.Successors(program, node))
    ensures Reachable(program, roots, successor)
  {
    var path: seq<nat> :|
      ValidPath(program, roots, path) &&
      path[|path| - 1] == node;
    var extended := path + [successor];
    AppendPreservesPathEdges(program, path, successor);
    R.SeqSetConcat(path, [successor]);
    LastBelongsToSeqSet(path);
    assert node in R.Nodes(program);
    R.RangeMembership(|program.successors|, node);
    assert R.SeqSet(R.Successors(program, node)) <=
           R.Nodes(program);
    assert successor in R.Nodes(program);
    assert ValidPath(program, roots, extended);
  }

  lemma InitialExecutionInvariant(
    program: R.Program,
    roots: seq<nat>
  )
    requires R.ValidProgram(program)
    requires R.SeqSet(roots) <= R.Nodes(program)
    ensures ExecutionInvariant(program, roots, R.Initial(program, roots))
  {
    var initial := R.Initial(program, roots);
    var admission := R.Admit(roots, {});
    R.InitialIsExact(program, roots);
    R.AdmitProperties(roots, {});
    assert initial.admitted == R.SeqSet(roots);
    assert R.SeqSet(initial.stack) == R.SeqSet(roots);
    assert Processed(initial) == {};
    forall node | node in initial.admitted
      ensures node in ReachableNodes(program, roots)
    {
      assert node in R.SeqSet(roots);
      SingletonPathIsReachable(program, roots, node);
    }
  }

  lemma ProcessedAfterStep(
    program: R.Program,
    state: R.State
  )
    requires R.ExactState(program, state)
    requires |state.stack| > 0
    ensures Processed(R.Step(program, state)) ==
            Processed(state) + {state.stack[0]}
  {
    var node := state.stack[0];
    var tail := state.stack[1..];
    var successors := R.Successors(program, node);
    var admission := R.Admit(successors, state.admitted);
    R.SeqSetHeadTail(state.stack);
    R.AdmitProperties(successors, state.admitted);
    R.SeqSetConcat(admission.newValues, tail);
    assert node in state.admitted;
    assert node !in R.SeqSet(tail);
    assert R.SeqSet(tail) <= state.admitted;
    assert admission.seen ==
           state.admitted + R.SeqSet(successors);
    assert R.SeqSet(admission.newValues) ==
           R.SeqSet(successors) - state.admitted;
    assert Processed(R.Step(program, state)) ==
           Processed(state) + {node} by {
      assert forall value: nat ::
        value in Processed(R.Step(program, state)) <==>
        value in Processed(state) + {node};
    }
  }

  lemma StepPreservesExecutionInvariant(
    program: R.Program,
    roots: seq<nat>,
    state: R.State
  )
    requires ExecutionInvariant(program, roots, state)
    ensures ExecutionInvariant(
              program,
              roots,
              R.Step(program, state)
            )
  {
    R.StepPreservesExactState(program, state);
    if |state.stack| > 0 {
      var node := state.stack[0];
      var successors := R.Successors(program, node);
      var admission := R.Admit(successors, state.admitted);
      var after := R.Step(program, state);
      R.SeqSetHeadTail(state.stack);
      R.AdmitProperties(successors, state.admitted);
      ProcessedAfterStep(program, state);

      assert node in state.admitted;
      assert node in ReachableNodes(program, roots);
      assert Reachable(program, roots, node);
      assert after.admitted == admission.seen;
      assert after.admitted == state.admitted + R.SeqSet(successors);
      assert state.admitted <= after.admitted;

      forall successor | successor in after.admitted
        ensures successor in ReachableNodes(program, roots)
      {
        if successor !in state.admitted {
          assert successor in R.SeqSet(successors);
          ReachableSuccessor(
            program, roots, node, successor
          );
        }
      }

      forall processed | processed in Processed(after)
        ensures R.SeqSet(R.Successors(program, processed)) <=
                after.admitted
      {
        if processed == node {
          assert R.SeqSet(successors) <= after.admitted;
        } else {
          assert processed in Processed(state);
          assert R.SeqSet(R.Successors(program, processed)) <=
                 state.admitted;
        }
      }
    }
  }

  ghost predicate ClosedOverSuccessors(
    program: R.Program,
    roots: seq<nat>,
    nodes: set<nat>
  ) {
    R.SeqSet(roots) <= nodes &&
    nodes <= R.Nodes(program) &&
    forall node | node in nodes ::
      R.SeqSet(R.Successors(program, node)) <= nodes
  }

  lemma PathEdgesStayInClosedSet(
    program: R.Program,
    path: seq<nat>,
    nodes: set<nat>
  )
    requires |path| > 0
    requires PathEdges(program, path)
    requires path[0] in nodes
    requires forall node | node in nodes ::
      R.SeqSet(R.Successors(program, node)) <= nodes
    ensures path[|path| - 1] in nodes
    decreases |path|
  {
    if |path| > 1 {
      assert path[1] in
             R.SeqSet(R.Successors(program, path[0]));
      assert path[1] in nodes;
      PathEdgesStayInClosedSet(program, path[1..], nodes);
    }
  }

  lemma ReachableNodesAreLeastClosedSet(
    program: R.Program,
    roots: seq<nat>,
    nodes: set<nat>
  )
    requires R.ValidProgram(program)
    requires ClosedOverSuccessors(program, roots, nodes)
    ensures ReachableNodes(program, roots) <= nodes
  {
    forall node | node in ReachableNodes(program, roots)
      ensures node in nodes
    {
      var path: seq<nat> :|
        ValidPath(program, roots, path) &&
        path[|path| - 1] == node;
      assert path[0] in nodes;
      PathEdgesStayInClosedSet(program, path, nodes);
    }
  }

  lemma ExhaustedReducerIsComplete(
    program: R.Program,
    roots: seq<nat>,
    state: R.State
  )
    requires ExecutionInvariant(program, roots, state)
    requires |state.stack| == 0
    ensures state.admitted == ReachableNodes(program, roots)
  {
    assert Processed(state) == state.admitted;
    assert ClosedOverSuccessors(
             program, roots, state.admitted
           );
    ReachableNodesAreLeastClosedSet(
      program, roots, state.admitted
    );
  }
}

// A reverse EACL lookup is ordinary traversal over a static transposed grant
// graph.  It needs one exact admitted set and one work stack; dynamic
// grant/consumer goal cells are not part of the semantic minimum.
include "StableReducer.dfy"

module StaticReverseFrontier {
  import S = StableReducer

  datatype Graph = Graph(predecessors: seq<seq<nat>>)

  datatype State = State(
    stack: seq<nat>,
    admitted: set<nat>,
    processed: set<nat>
  )

  function Nodes(graph: Graph): set<nat> {
    S.Range(|graph.predecessors|)
  }

  predicate ValidGraph(graph: Graph) {
    forall node: nat | node < |graph.predecessors| ::
      S.SeqSet(graph.predecessors[node]) <= Nodes(graph)
  }

  predicate Path(graph: Graph, values: seq<nat>)
    decreases |values|
  {
    |values| <= 1 ||
    (values[0] < |graph.predecessors| &&
     values[1] in S.SeqSet(graph.predecessors[values[0]]) &&
     Path(graph, values[1..]))
  }

  ghost predicate Reachable(
    graph: Graph,
    root: nat,
    node: nat
  ) {
    exists path: seq<nat> ::
      |path| > 0 &&
      path[0] == root &&
      path[|path| - 1] == node &&
      Path(graph, path)
  }

  ghost predicate Exact(
    graph: Graph,
    root: nat,
    state: State
  ) {
    ValidGraph(graph) &&
    root in state.admitted &&
    state.admitted <= Nodes(graph) &&
    S.Unique(state.stack) &&
    S.SeqSet(state.stack) <= state.admitted &&
    state.processed <= state.admitted &&
    state.processed * S.SeqSet(state.stack) == {} &&
    state.admitted == state.processed + S.SeqSet(state.stack) &&
    (forall node: nat | node in state.admitted ::
      Reachable(graph, root, node)) &&
    (forall node: nat | node in state.processed ::
      node < |graph.predecessors| &&
      S.SeqSet(graph.predecessors[node]) <= state.admitted)
  }

  function Initial(graph: Graph, root: nat): State {
    State([root], {root}, {})
  }

  lemma InitialIsExact(graph: Graph, root: nat)
    requires ValidGraph(graph)
    requires root in Nodes(graph)
    ensures Exact(graph, root, Initial(graph, root))
  {
    S.RangeMembership(|graph.predecessors|, root);
    assert Path(graph, [root]);
    assert Reachable(graph, root, root);
  }

  lemma ExtendReachable(
    graph: Graph,
    root: nat,
    node: nat,
    predecessor: nat
  )
    requires Reachable(graph, root, node)
    requires node < |graph.predecessors|
    requires predecessor in S.SeqSet(graph.predecessors[node])
    ensures Reachable(graph, root, predecessor)
  {
    var path: seq<nat> :|
      |path| > 0 &&
      path[0] == root &&
      path[|path| - 1] == node &&
      Path(graph, path);
    var extended := path + [predecessor];
    assert |extended| > 0;
    assert extended[0] == root;
    assert extended[|extended| - 1] == predecessor;
    ExtendPath(graph, path, predecessor);
  }

  lemma ExtendPath(
    graph: Graph,
    path: seq<nat>,
    successor: nat
  )
    requires |path| > 0
    requires Path(graph, path)
    requires path[|path| - 1] < |graph.predecessors|
    requires successor in
             S.SeqSet(graph.predecessors[path[|path| - 1]])
    ensures Path(graph, path + [successor])
    decreases |path|
  {
    if |path| > 1 {
      ExtendPath(graph, path[1..], successor);
      assert (path + [successor])[1..] == path[1..] + [successor];
    }
  }

  function Step(graph: Graph, state: State): State {
    if |state.stack| == 0 ||
       state.stack[0] >= |graph.predecessors| then state
    else
      var node := state.stack[0];
      var admission :=
        S.Admit(graph.predecessors[node], state.admitted);
      State(
        admission.newValues + state.stack[1..],
        admission.seen,
        state.processed + {node}
      )
  }

  lemma StepPreservesExact(
    graph: Graph,
    root: nat,
    state: State
  )
    requires Exact(graph, root, state)
    ensures Exact(graph, root, Step(graph, state))
  {
    if |state.stack| > 0 {
      var node := state.stack[0];
      var tail := state.stack[1..];
      S.SeqSetHeadTail(state.stack);
      assert node in S.SeqSet(state.stack);
      assert node in state.admitted;
      assert node !in state.processed;
      S.RangeMembership(|graph.predecessors|, node);
      assert node < |graph.predecessors|;
      var successors := graph.predecessors[node];
      var admission := S.Admit(successors, state.admitted);
      S.AdmitProperties(successors, state.admitted);
      S.SeqSetConcat(admission.newValues, tail);
      S.ConcatenationIsUnique(admission.newValues, tail);

      forall predecessor: nat |
        predecessor in admission.seen
        ensures Reachable(graph, root, predecessor)
      {
        if predecessor !in state.admitted {
          assert predecessor in S.SeqSet(successors);
          ExtendReachable(graph, root, node, predecessor);
        }
      }

      assert admission.seen ==
             state.admitted + S.SeqSet(successors);
      assert state.admitted ==
             state.processed + {node} + S.SeqSet(tail);
      assert S.SeqSet(admission.newValues) ==
             S.SeqSet(successors) - state.admitted;
      assert admission.seen ==
             (state.processed + {node}) +
             S.SeqSet(admission.newValues + tail);

      forall processed: nat |
        processed in state.processed + {node}
        ensures processed < |graph.predecessors| &&
                S.SeqSet(graph.predecessors[processed]) <=
                admission.seen
      {
        if processed == node {
          assert S.SeqSet(successors) <= admission.seen;
        } else {
          assert processed in state.processed;
          assert S.SeqSet(graph.predecessors[processed]) <=
                 state.admitted;
          assert state.admitted <= admission.seen;
        }
      }
    }
  }

  lemma ClosedContainsPath(
    graph: Graph,
    closed: set<nat>,
    path: seq<nat>
  )
    requires |path| > 0
    requires path[0] in closed
    requires Path(graph, path)
    requires forall node: nat | node in closed ::
      node < |graph.predecessors| &&
      S.SeqSet(graph.predecessors[node]) <= closed
    ensures path[|path| - 1] in closed
    decreases |path|
  {
    if |path| > 1 {
      assert path[1] in
             S.SeqSet(graph.predecessors[path[0]]);
      assert path[1] in closed;
      ClosedContainsPath(graph, closed, path[1..]);
    }
  }

  lemma ExhaustionIsComplete(
    graph: Graph,
    root: nat,
    state: State,
    node: nat
  )
    requires Exact(graph, root, state)
    requires |state.stack| == 0
    requires Reachable(graph, root, node)
    ensures node in state.processed
  {
    assert state.admitted == state.processed;
    var path: seq<nat> :|
      |path| > 0 &&
      path[0] == root &&
      path[|path| - 1] == node &&
      Path(graph, path);
    ClosedContainsPath(graph, state.processed, path);
  }

  lemma EveryStepProcessesOneFreshGoal(
    graph: Graph,
    root: nat,
    state: State
  )
    requires Exact(graph, root, state)
    requires |state.stack| > 0
    ensures |Step(graph, state).processed| ==
            |state.processed| + 1
  {
    S.SeqSetHeadTail(state.stack);
    assert state.stack[0] in S.SeqSet(state.stack);
    assert state.stack[0] in state.admitted;
    assert state.stack[0] in Nodes(graph);
    S.RangeMembership(|graph.predecessors|, state.stack[0]);
    assert state.stack[0] < |graph.predecessors|;
    assert state.stack[0] !in state.processed;
  }
}

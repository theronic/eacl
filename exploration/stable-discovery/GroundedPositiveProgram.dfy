// A fixed finite EACL snapshot grounds its union-only permission language into
// base grant atoms and unary positive implications.
include "ReducerCompleteness.dfy"

module GroundedPositiveProgram {
  import R = StableReducer
  import C = ReducerCompleteness

  datatype Edge = Edge(body: nat, head: nat)

  datatype GroundProgram = GroundProgram(
    nodeCount: nat,
    bases: seq<nat>,
    edges: seq<Edge>
  )

  function EdgeSet(edges: seq<Edge>): set<Edge>
    decreases |edges|
  {
    if |edges| == 0 then {}
    else {edges[0]} + EdgeSet(edges[1..])
  }

  function SuccessorsFor(
    edges: seq<Edge>,
    body: nat
  ): seq<nat>
    decreases |edges|
  {
    if |edges| == 0 then []
    else if edges[0].body == body then
      [edges[0].head] + SuccessorsFor(edges[1..], body)
    else
      SuccessorsFor(edges[1..], body)
  }

  function SuccessorTable(
    edges: seq<Edge>,
    nodeCount: nat
  ): seq<seq<nat>>
    decreases nodeCount
  {
    if nodeCount == 0 then []
    else SuccessorTable(edges, nodeCount - 1) +
         [SuccessorsFor(edges, nodeCount - 1)]
  }

  function Compile(program: GroundProgram): R.Program {
    R.Program(
      SuccessorTable(program.edges, program.nodeCount),
      {}
    )
  }

  predicate ValidGroundProgram(program: GroundProgram) {
    R.SeqSet(program.bases) <= R.Range(program.nodeCount) &&
    forall edge | edge in EdgeSet(program.edges) ::
      edge.body < program.nodeCount &&
      edge.head < program.nodeCount
  }

  predicate GroundClosed(
    program: GroundProgram,
    candidate: set<nat>
  ) {
    R.SeqSet(program.bases) <= candidate &&
    candidate <= R.Range(program.nodeCount) &&
    forall edge | edge in EdgeSet(program.edges) &&
                  edge.body in candidate ::
      edge.head in candidate
  }

  lemma EdgeSetHeadTail(edges: seq<Edge>)
    requires |edges| > 0
    ensures EdgeSet(edges) == {edges[0]} + EdgeSet(edges[1..])
  {
  }

  lemma SuccessorsForExact(
    edges: seq<Edge>,
    body: nat
  )
    ensures forall head: nat ::
      head in R.SeqSet(SuccessorsFor(edges, body)) <==>
      exists edge :: edge in EdgeSet(edges) &&
                     edge.body == body && edge.head == head
    decreases |edges|
  {
    if |edges| > 0 {
      EdgeSetHeadTail(edges);
      SuccessorsForExact(edges[1..], body);
      if edges[0].body == body {
        R.SeqSetHeadTail(SuccessorsFor(edges, body));
      }
    }
  }

  lemma SuccessorTableLength(
    edges: seq<Edge>,
    nodeCount: nat
  )
    ensures |SuccessorTable(edges, nodeCount)| == nodeCount
    decreases nodeCount
  {
    if nodeCount > 0 {
      SuccessorTableLength(edges, nodeCount - 1);
    }
  }

  lemma SuccessorTableAt(
    edges: seq<Edge>,
    nodeCount: nat,
    node: nat
  )
    requires node < nodeCount
    requires |SuccessorTable(edges, nodeCount)| == nodeCount
    ensures SuccessorTable(edges, nodeCount)[node] ==
            SuccessorsFor(edges, node)
    decreases nodeCount
  {
    if node + 1 < nodeCount {
      SuccessorTableLength(edges, nodeCount - 1);
      SuccessorTableAt(edges, nodeCount - 1, node);
    } else {
      assert node + 1 == nodeCount;
      assert SuccessorTable(edges, nodeCount) ==
             SuccessorTable(edges, nodeCount - 1) +
             [SuccessorsFor(edges, nodeCount - 1)];
    }
  }

  lemma CompiledProgramIsValid(program: GroundProgram)
    requires ValidGroundProgram(program)
    ensures R.ValidProgram(Compile(program))
  {
    SuccessorTableLength(program.edges, program.nodeCount);
    forall node: nat | node < program.nodeCount
      ensures R.SeqSet(R.Successors(Compile(program), node)) <=
              R.Nodes(Compile(program))
    {
      SuccessorTableAt(program.edges, program.nodeCount, node);
      SuccessorsForExact(program.edges, node);
      forall head | head in R.SeqSet(
                             SuccessorsFor(program.edges, node))
        ensures head in R.Range(program.nodeCount)
      {
        var edge :| edge in EdgeSet(program.edges) &&
                    edge.body == node && edge.head == head;
        assert head < program.nodeCount;
        R.RangeMembership(program.nodeCount, head);
      }
    }
  }

  lemma GroundClosedImpliesCompiledClosed(
    program: GroundProgram,
    candidate: set<nat>
  )
    requires ValidGroundProgram(program)
    requires GroundClosed(program, candidate)
    ensures C.ClosedOverSuccessors(
              Compile(program),
              program.bases,
              candidate
            )
  {
    CompiledProgramIsValid(program);
    SuccessorTableLength(program.edges, program.nodeCount);
    assert R.Nodes(Compile(program)) ==
           R.Range(program.nodeCount);
    forall node | node in candidate
      ensures R.SeqSet(
                R.Successors(Compile(program), node)
              ) <= candidate
    {
      assert node in R.Range(program.nodeCount);
      R.RangeMembership(program.nodeCount, node);
      assert node < program.nodeCount;
      SuccessorTableAt(program.edges, program.nodeCount, node);
      SuccessorsForExact(program.edges, node);
      forall head | head in R.SeqSet(
                             SuccessorsFor(program.edges, node))
        ensures head in candidate
      {
        var edge :| edge in EdgeSet(program.edges) &&
                    edge.body == node && edge.head == head;
      }
    }
  }

  lemma CompiledClosedImpliesGroundClosed(
    program: GroundProgram,
    candidate: set<nat>
  )
    requires ValidGroundProgram(program)
    requires C.ClosedOverSuccessors(
              Compile(program),
              program.bases,
              candidate
            )
    ensures GroundClosed(program, candidate)
  {
    CompiledProgramIsValid(program);
    SuccessorTableLength(program.edges, program.nodeCount);
    assert R.Nodes(Compile(program)) ==
           R.Range(program.nodeCount);
    forall edge | edge in EdgeSet(program.edges) &&
                  edge.body in candidate
      ensures edge.head in candidate
    {
      assert edge.body < program.nodeCount;
      SuccessorTableAt(
        program.edges, program.nodeCount, edge.body
      );
      SuccessorsForExact(program.edges, edge.body);
      assert edge.head in R.SeqSet(
                            SuccessorsFor(
                              program.edges, edge.body
                            )
                          );
      assert R.SeqSet(
               R.Successors(Compile(program), edge.body)
             ) <= candidate;
    }
  }

  lemma GroundClosedEqualsCompiledClosed(
    program: GroundProgram,
    candidate: set<nat>
  )
    requires ValidGroundProgram(program)
    ensures GroundClosed(program, candidate) <==>
            C.ClosedOverSuccessors(
              Compile(program),
              program.bases,
              candidate
            )
  {
    if GroundClosed(program, candidate) {
      GroundClosedImpliesCompiledClosed(program, candidate);
    } else if C.ClosedOverSuccessors(
                    Compile(program),
                    program.bases,
                    candidate
                  ) {
      CompiledClosedImpliesGroundClosed(program, candidate);
    }
  }

  lemma ExhaustedCompiledReducerIsGroundComplete(
    program: GroundProgram,
    state: R.State
  )
    requires ValidGroundProgram(program)
    requires C.ExecutionInvariant(
              Compile(program),
              program.bases,
              state
            )
    requires |state.stack| == 0
    ensures state.admitted ==
            C.ReachableNodes(Compile(program), program.bases)
    ensures GroundClosed(program, state.admitted)
    ensures forall candidate: set<nat> |
              GroundClosed(program, candidate) ::
              state.admitted <= candidate
  {
    CompiledProgramIsValid(program);
    C.ExhaustedReducerIsComplete(
      Compile(program), program.bases, state
    );
    assert C.ClosedOverSuccessors(
             Compile(program),
             program.bases,
             state.admitted
           );
    GroundClosedEqualsCompiledClosed(program, state.admitted);
    forall candidate: set<nat> |
      GroundClosed(program, candidate)
      ensures state.admitted <= candidate
    {
      GroundClosedEqualsCompiledClosed(program, candidate);
      C.ReachableNodesAreLeastClosedSet(
        Compile(program), program.bases, candidate
      );
    }
  }
}

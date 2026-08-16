// Successor order determines discovery order, but not the positive closure.
include "ReducerCompleteness.dfy"

module OrderIrrelevance {
  import R = StableReducer
  import C = ReducerCompleteness

  predicate SameDenotationEdges(
    left: R.Program,
    right: R.Program
  ) {
    R.ValidProgram(left) &&
    R.ValidProgram(right) &&
    R.Nodes(left) == R.Nodes(right) &&
    forall node | node in R.Nodes(left) ::
      R.SeqSet(R.Successors(left, node)) ==
      R.SeqSet(R.Successors(right, node))
  }

  lemma ReachableNodesAreClosed(
    program: R.Program,
    roots: seq<nat>
  )
    requires R.ValidProgram(program)
    requires R.SeqSet(roots) <= R.Nodes(program)
    ensures C.ClosedOverSuccessors(
              program,
              roots,
              C.ReachableNodes(program, roots)
            )
  {
    forall root | root in R.SeqSet(roots)
      ensures root in C.ReachableNodes(program, roots)
    {
      C.SingletonPathIsReachable(program, roots, root);
    }
    forall node | node in C.ReachableNodes(program, roots)
      ensures R.SeqSet(R.Successors(program, node)) <=
              C.ReachableNodes(program, roots)
    {
      forall successor |
        successor in R.SeqSet(R.Successors(program, node))
        ensures successor in C.ReachableNodes(program, roots)
      {
        C.ReachableSuccessor(
          program, roots, node, successor
        );
      }
    }
  }

  lemma ClosedTransfersAcrossOrder(
    left: R.Program,
    right: R.Program,
    roots: seq<nat>,
    candidate: set<nat>
  )
    requires SameDenotationEdges(left, right)
    requires C.ClosedOverSuccessors(left, roots, candidate)
    ensures C.ClosedOverSuccessors(right, roots, candidate)
  {
    forall node | node in candidate
      ensures R.SeqSet(R.Successors(right, node)) <= candidate
    {
      assert node in R.Nodes(left);
      assert R.SeqSet(R.Successors(left, node)) <= candidate;
    }
  }

  lemma StableRuleReorderingPreservesDenotation(
    left: R.Program,
    right: R.Program,
    roots: seq<nat>
  )
    requires SameDenotationEdges(left, right)
    requires R.SeqSet(roots) <= R.Nodes(left)
    ensures C.ReachableNodes(left, roots) ==
            C.ReachableNodes(right, roots)
  {
    ReachableNodesAreClosed(left, roots);
    ClosedTransfersAcrossOrder(
      left,
      right,
      roots,
      C.ReachableNodes(left, roots)
    );
    C.ReachableNodesAreLeastClosedSet(
      right, roots, C.ReachableNodes(left, roots)
    );

    ReachableNodesAreClosed(right, roots);
    ClosedTransfersAcrossOrder(
      right,
      left,
      roots,
      C.ReachableNodes(right, roots)
    );
    C.ReachableNodesAreLeastClosedSet(
      left, roots, C.ReachableNodes(right, roots)
    );
  }
}

// Membership-probe point check (membership-probe-point-check): a point
// authorization is decided by exploring only the intermediates a resource
// reaches and probing the subject as a direct base successor, never by
// enumerating the subjects that hold the permission. This leaf proves that
// answer equal to membership in the exhaustive reverse-discovery denotation
// the enumeration check inspected.
//
// Model. A reverse program is a StableReducer program whose result nodes
// (emitted subjects) are leaves: LeafResults. Pruning removes result nodes
// from every successor list; the exhaustive reducer over the pruned program
// is exactly the probe search's depth-first exploration of intermediates,
// and ReducerCompleteness proves it admits precisely the pruned reachable
// set. The probe answer holds iff some admitted intermediate has the target
// as a direct successor (one exact-bound scan per direct rule).
//
// Exploratory proof model; intentionally excluded from release artifacts.
include "ExactCountComposition.dfy"

module MembershipProbeCheck {
  import R = StableReducer
  import C = ReducerCompleteness
  import X = ExactCountComposition

  // Reverse programs emit subjects as leaves: an emitted subject pushes no
  // further work.
  ghost predicate LeafResults(program: R.Program) {
    forall node: nat | node in program.resultNodes ::
      R.Successors(program, node) == []
  }

  // ---------------------------------------------------------------------
  // Pruning: drop result nodes from successor lists
  // ---------------------------------------------------------------------

  function Prune(values: seq<nat>, results: set<nat>): seq<nat>
    decreases |values|
  {
    if |values| == 0 then
      []
    else if values[0] in results then
      Prune(values[1..], results)
    else
      [values[0]] + Prune(values[1..], results)
  }

  lemma PruneMembership(values: seq<nat>, results: set<nat>, value: nat)
    ensures value in R.SeqSet(Prune(values, results)) <==>
            (value in R.SeqSet(values) && value !in results)
    decreases |values|
  {
    if |values| > 0 {
      PruneMembership(values[1..], results, value);
      if values[0] !in results {
        assert Prune(values, results) ==
               [values[0]] + Prune(values[1..], results);
        R.SeqSetConcat([values[0]], Prune(values[1..], results));
      }
    }
  }

  function PruneProgram(program: R.Program): R.Program {
    R.Program(
      seq(|program.successors|,
      i requires 0 <= i < |program.successors| =>
        Prune(program.successors[i], program.resultNodes)),
      {}
    )
  }

  lemma PrunedNodes(program: R.Program)
    ensures R.Nodes(PruneProgram(program)) == R.Nodes(program)
  {
  }

  lemma PrunedSuccessors(program: R.Program, node: nat)
    ensures R.Successors(PruneProgram(program), node) ==
            Prune(R.Successors(program, node), program.resultNodes)
  {
  }

  lemma PrunedSuccessorMembership(
    program: R.Program,
    node: nat,
    successor: nat
  )
    ensures successor in R.SeqSet(R.Successors(PruneProgram(program), node))
            <==>
            (successor in R.SeqSet(R.Successors(program, node)) &&
             successor !in program.resultNodes)
  {
    PrunedSuccessors(program, node);
    PruneMembership(R.Successors(program, node), program.resultNodes,
                    successor);
  }

  lemma PrunedProgramIsValid(program: R.Program)
    requires R.ValidProgram(program)
    ensures R.ValidProgram(PruneProgram(program))
  {
    var pruned := PruneProgram(program);
    PrunedNodes(program);
    forall node: nat | node < |pruned.successors|
      ensures R.SeqSet(R.Successors(pruned, node)) <= R.Nodes(pruned)
    {
      forall successor | successor in R.SeqSet(R.Successors(pruned, node))
        ensures successor in R.Nodes(pruned)
      {
        PrunedSuccessorMembership(program, node, successor);
      }
    }
  }

  // ---------------------------------------------------------------------
  // The probe answer
  // ---------------------------------------------------------------------

  // Some intermediate reachable through non-result states holds the target
  // as a direct successor: the probe succeeds at that intermediate.
  ghost predicate ProbeAnswer(program: R.Program, root: nat, target: nat) {
    exists node: nat ::
      node in C.ReachableNodes(PruneProgram(program), [root]) &&
      target in R.SeqSet(R.Successors(program, node))
  }

  // ---------------------------------------------------------------------
  // Paths in the pruned program are paths in the program
  // ---------------------------------------------------------------------

  lemma PrunedPathEdgesArePathEdges(program: R.Program, path: seq<nat>)
    requires C.PathEdges(PruneProgram(program), path)
    ensures C.PathEdges(program, path)
    decreases |path|
  {
    if |path| > 1 {
      PrunedSuccessorMembership(program, path[0], path[1]);
      PrunedPathEdgesArePathEdges(program, path[1..]);
    }
  }

  lemma PrunedReachableIsReachable(
    program: R.Program,
    roots: seq<nat>,
    node: nat
  )
    requires C.Reachable(PruneProgram(program), roots, node)
    ensures C.Reachable(program, roots, node)
  {
    var path: seq<nat> :|
      C.ValidPath(PruneProgram(program), roots, path) &&
      path[|path| - 1] == node;
    PrunedNodes(program);
    PrunedPathEdgesArePathEdges(program, path);
    assert C.ValidPath(program, roots, path);
  }

  // ---------------------------------------------------------------------
  // A path ending in a result node runs through non-result nodes only, and
  // that prefix survives pruning
  // ---------------------------------------------------------------------

  lemma InteriorNodesAreNotResults(program: R.Program, path: seq<nat>)
    requires LeafResults(program)
    requires C.PathEdges(program, path)
    ensures forall i | 0 <= i < |path| - 1 :: path[i] !in program.resultNodes
    decreases |path|
  {
    if |path| > 1 {
      // path[0] has the successor path[1]; a result node has none.
      assert path[1] in R.SeqSet(R.Successors(program, path[0]));
      if path[0] in program.resultNodes {
        assert R.Successors(program, path[0]) == [];
        assert R.SeqSet(R.Successors(program, path[0])) == {};
        assert false;
      }
      InteriorNodesAreNotResults(program, path[1..]);
    }
  }

  lemma PrefixPathEdgesSurvivePruning(program: R.Program, path: seq<nat>)
    requires C.PathEdges(program, path)
    requires forall i | 0 <= i < |path| :: path[i] !in program.resultNodes
    ensures C.PathEdges(PruneProgram(program), path)
    decreases |path|
  {
    if |path| > 1 {
      PrunedSuccessorMembership(program, path[0], path[1]);
      PrefixPathEdgesSurvivePruning(program, path[1..]);
    }
  }

  lemma PathEdgesPrefix(program: R.Program, path: seq<nat>, count: nat)
    requires C.PathEdges(program, path)
    requires count <= |path|
    ensures C.PathEdges(program, path[..count])
    decreases |path|
  {
    if |path| > 1 && count > 1 {
      assert path[..count][1..] == path[1..][..count - 1];
      PathEdgesPrefix(program, path[1..], count - 1);
    }
  }

  lemma PathEdgeAt(program: R.Program, path: seq<nat>, index: nat)
    requires C.PathEdges(program, path)
    requires index < |path| - 1
    ensures path[index + 1] in R.SeqSet(R.Successors(program, path[index]))
    decreases |path|
  {
    if index > 0 {
      PathEdgeAt(program, path[1..], index - 1);
    }
  }

  lemma SeqSetPrefixSubset(path: seq<nat>, count: nat)
    requires count <= |path|
    ensures R.SeqSet(path[..count]) <= R.SeqSet(path)
    decreases |path|
  {
    if |path| > 0 && count > 0 {
      assert path[..count][1..] == path[1..][..count - 1];
      SeqSetPrefixSubset(path[1..], count - 1);
    }
  }

  // ---------------------------------------------------------------------
  // Main theorem: the probe answer is exactly reverse reachability
  // ---------------------------------------------------------------------

  lemma ProbeAnswerEqualsReachability(
    program: R.Program,
    root: nat,
    target: nat
  )
    requires R.ValidProgram(program)
    requires LeafResults(program)
    requires root in R.Nodes(program)
    requires root !in program.resultNodes
    requires target in program.resultNodes
    ensures ProbeAnswer(program, root, target) <==>
            target in C.ReachableNodes(program, [root])
  {
    var pruned := PruneProgram(program);
    PrunedNodes(program);
    if ProbeAnswer(program, root, target) {
      var node: nat :|
        node in C.ReachableNodes(pruned, [root]) &&
        target in R.SeqSet(R.Successors(program, node));
      PrunedReachableIsReachable(program, [root], node);
      C.ReachableSuccessor(program, [root], node, target);
      assert target in R.Nodes(program);
    }
    if target in C.ReachableNodes(program, [root]) {
      var path: seq<nat> :|
        C.ValidPath(program, [root], path) &&
        path[|path| - 1] == target;
      // The path has at least two nodes: root is not a result node.
      assert R.SeqSet([root]) == {root} + R.SeqSet([]);
      assert path[0] == root;
      assert |path| >= 2;
      var prefix := path[..|path| - 1];
      var last := |path| - 2;
      InteriorNodesAreNotResults(program, path);
      PathEdgesPrefix(program, path, |path| - 1);
      PrefixPathEdgesSurvivePruning(program, prefix);
      SeqSetPrefixSubset(path, |path| - 1);
      assert C.ValidPath(pruned, [root], prefix);
      assert prefix[|prefix| - 1] == path[last];
      C.LastBelongsToSeqSet(prefix);
      assert path[last] in R.SeqSet(prefix);
      assert path[last] in R.SeqSet(path);
      assert path[last] in R.Nodes(program);
      assert C.Reachable(pruned, [root], path[last]);
      assert path[last] in R.Nodes(pruned);
      assert path[last] in C.ReachableNodes(pruned, [root]);
      // The last edge of the path is the probe.
      PathEdgeAt(program, path, last);
      assert path[last + 1] in
               R.SeqSet(R.Successors(program, path[last]));
      assert path[last + 1] == target;
      assert ProbeAnswer(program, root, target);
    }
  }

  // ---------------------------------------------------------------------
  // Executable connections
  // ---------------------------------------------------------------------

  // The exhaustive reducer over the pruned program (the probe search's
  // exploration of intermediates) admits exactly the pruned reachable set,
  // so the answer is one probe per admitted intermediate.
  lemma ExhaustedExplorationYieldsProbeAnswer(
    program: R.Program,
    root: nat,
    target: nat,
    explored: R.State
  )
    requires R.ValidProgram(program)
    requires C.ExecutionInvariant(PruneProgram(program), [root], explored)
    requires |explored.stack| == 0
    ensures ProbeAnswer(program, root, target) <==>
            exists node: nat ::
              node in explored.admitted &&
              target in R.SeqSet(R.Successors(program, node))
  {
    C.ExhaustedReducerIsComplete(PruneProgram(program), [root], explored);
  }

  // The reverse-enumeration check answered "target in results" after
  // exhausting the unpruned reverse program; the probe answer agrees.
  lemma ProbeCheckEqualsEnumerationCheck(
    program: R.Program,
    root: nat,
    target: nat,
    enumerated: R.State
  )
    requires R.ValidProgram(program)
    requires LeafResults(program)
    requires root in R.Nodes(program)
    requires root !in program.resultNodes
    requires target in program.resultNodes
    requires X.ExactCountState(program, [root], enumerated)
    requires |enumerated.stack| == 0
    ensures ProbeAnswer(program, root, target) <==>
            target in R.SeqSet(enumerated.results)
  {
    X.ExhaustedResultsAreExact(program, [root], enumerated);
    ProbeAnswerEqualsReachability(program, root, target);
  }
}

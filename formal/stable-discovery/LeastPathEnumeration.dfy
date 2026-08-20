// Least-path enumeration (acyclic-keyset-pagination, task 1.2): the
// ordered depth-first enumeration of LeastPathOrder, filtered by the
// first-occurrence (no-smaller-witness) rule, emits exactly the reachable
// result leaves of an acyclic program — each exactly once, at its unique
// least derivation path. This leaf proves:
//
//  - the emission filter's two equivalent readings: "no earlier
//    enumerated path derives this leaf" and "no strictly smaller complete
//    path derives this leaf" (`EmittedImpliesNoSmallerWitness` and
//    `NoSmallerWitnessImpliesEmitted`) — the second reading is what the
//    runtime decides with bounded probes;
//  - emitted leaves are exactly the reachable leaves, once each
//    (`EmittedExactlyReachable`, `EmittedOncePerLeaf`), and reachability
//    here coincides with the engine's existing denotation
//    (`ReachableLeafBridge` onto ReducerCompleteness.Reachable);
//  - pruning the subtree of a repeated interior state — the same node
//    reached again through a lex-greater prefix — drops no emission
//    (`NoEmissionUnderRepeatedState`): every result under the repeat has
//    a lex-smaller path under the first occurrence, so the emission
//    filter already rejects the pruned positions;
//  - merging two strictly ascending streams (the leaf-level closure
//    optimization) is strictly ascending and carries exactly the union
//    (`MergeAscending`, `MergeUnion`).
//
// Exploratory proof model; intentionally excluded from release artifacts.
include "LeastPathOrder.dfy"
include "BidirectionalArrowIntersection.dfy"

module LeastPathEnumeration {
  import R = StableReducer
  import C = ReducerCompleteness
  import O = LeastPathOrder
  import B = BidirectionalArrowIntersection

  // ---------------------------------------------------------------------
  // Total leaf projection and the emission filter
  // ---------------------------------------------------------------------

  ghost function TotalLeaf(p: R.Program, node: nat, path: O.Path): nat {
    if O.CompletePath(p, node, path) then O.PathLeaf(p, node, path) else 0
  }

  ghost predicate EmittedIndex(p: R.Program, rank: seq<nat>, bound: nat,
                               root: nat, k: nat)
    requires O.Wf(p, rank, bound) && root < |p.successors|
  {
    var e := O.AllPaths(p, rank, bound, root);
    k < |e| &&
    forall j: nat | j < k ::
      TotalLeaf(p, root, e[j]) != TotalLeaf(p, root, e[k])
  }

  ghost predicate ReachableLeaf(p: R.Program, root: nat, leaf: nat) {
    exists path: O.Path ::
      O.CompletePath(p, root, path) && O.PathLeaf(p, root, path) == leaf
  }

  // ---------------------------------------------------------------------
  // The witness form of the emission filter
  // ---------------------------------------------------------------------

  lemma EmittedImpliesNoSmallerWitness(p: R.Program, rank: seq<nat>,
                                       bound: nat, root: nat, k: nat)
    requires O.Wf(p, rank, bound) && root < |p.successors|
    requires k < |O.AllPaths(p, rank, bound, root)|
    requires EmittedIndex(p, rank, bound, root, k)
    ensures !(exists q: O.Path ::
        O.CompletePath(p, root, q) &&
        O.PathLeaf(p, root, q) ==
          TotalLeaf(p, root, O.AllPaths(p, rank, bound, root)[k]) &&
        O.Lex(q, O.AllPaths(p, rank, bound, root)[k]))
  {
    var e := O.AllPaths(p, rank, bound, root);
    O.AllPathsSound(p, rank, bound, root);
    O.AllPathsSorted(p, rank, bound, root);
    if exists q: O.Path ::
        O.CompletePath(p, root, q) &&
        O.PathLeaf(p, root, q) ==
          TotalLeaf(p, root, O.AllPaths(p, rank, bound, root)[k]) &&
        O.Lex(q, O.AllPaths(p, rank, bound, root)[k]) {
      var q: O.Path :|
        O.CompletePath(p, root, q) &&
        O.PathLeaf(p, root, q) == TotalLeaf(p, root, e[k]) &&
        O.Lex(q, e[k]);
      O.AllPathsComplete(p, rank, bound, root, q);
      var j: nat :| j < |e| && e[j] == q;
      if j > k {
        assert O.Lex(e[k], e[j]);
        O.LexTransitive(e[k], e[j], e[k]);
        O.LexIrreflexive(e[k]);
      } else if j == k {
        O.LexIrreflexive(e[k]);
      } else {
        assert TotalLeaf(p, root, e[j]) == TotalLeaf(p, root, e[k]);
      }
      assert false;
    }
  }

  lemma NoSmallerWitnessImpliesEmitted(p: R.Program, rank: seq<nat>,
                                       bound: nat, root: nat, k: nat)
    requires O.Wf(p, rank, bound) && root < |p.successors|
    requires k < |O.AllPaths(p, rank, bound, root)|
    requires !(exists q: O.Path ::
        O.CompletePath(p, root, q) &&
        O.PathLeaf(p, root, q) ==
          TotalLeaf(p, root, O.AllPaths(p, rank, bound, root)[k]) &&
        O.Lex(q, O.AllPaths(p, rank, bound, root)[k]))
    ensures EmittedIndex(p, rank, bound, root, k)
  {
    var e := O.AllPaths(p, rank, bound, root);
    O.AllPathsSound(p, rank, bound, root);
    O.AllPathsSorted(p, rank, bound, root);
    if !EmittedIndex(p, rank, bound, root, k) {
      var j: nat :| j < k &&
        TotalLeaf(p, root, e[j]) == TotalLeaf(p, root, e[k]);
      assert O.CompletePath(p, root, e[j]);
      assert O.Lex(e[j], e[k]);
      assert O.PathLeaf(p, root, e[j]) ==
        TotalLeaf(p, root, O.AllPaths(p, rank, bound, root)[k]);
      assert false;
    }
  }

  // ---------------------------------------------------------------------
  // Emitted exactly the reachable leaves, once each, at the least path
  // ---------------------------------------------------------------------

  lemma EmittedExactlyReachable(p: R.Program, rank: seq<nat>, bound: nat,
                                root: nat, leaf: nat)
    requires O.Wf(p, rank, bound) && root < |p.successors|
    ensures ReachableLeaf(p, root, leaf) <==>
      exists k: nat ::
        EmittedIndex(p, rank, bound, root, k) &&
        TotalLeaf(p, root, O.AllPaths(p, rank, bound, root)[k]) == leaf
  {
    var e := O.AllPaths(p, rank, bound, root);
    O.AllPathsSound(p, rank, bound, root);
    if ReachableLeaf(p, root, leaf) {
      var path: O.Path :| O.CompletePath(p, root, path) &&
                          O.PathLeaf(p, root, path) == leaf;
      O.AllPathsComplete(p, rank, bound, root, path);
      // The first index deriving `leaf` is emitted.
      var k: nat :| k < |e| && TotalLeaf(p, root, e[k]) == leaf;
      k := FirstDeriving(p, rank, bound, root, leaf, k);
      assert EmittedIndex(p, rank, bound, root, k);
    }
    if exists k: nat ::
        EmittedIndex(p, rank, bound, root, k) &&
        TotalLeaf(p, root, e[k]) == leaf {
      var k: nat :| EmittedIndex(p, rank, bound, root, k) &&
                    TotalLeaf(p, root, e[k]) == leaf;
      assert O.CompletePath(p, root, e[k]);
    }
  }

  lemma FirstDeriving(p: R.Program, rank: seq<nat>, bound: nat,
                      root: nat, leaf: nat, k: nat)
    returns (first: nat)
    requires O.Wf(p, rank, bound) && root < |p.successors|
    requires k < |O.AllPaths(p, rank, bound, root)|
    requires TotalLeaf(p, root, O.AllPaths(p, rank, bound, root)[k]) == leaf
    ensures first <= k
    ensures TotalLeaf(p, root,
                      O.AllPaths(p, rank, bound, root)[first]) == leaf
    ensures EmittedIndex(p, rank, bound, root, first)
    decreases k
  {
    var e := O.AllPaths(p, rank, bound, root);
    if forall j: nat | j < k :: TotalLeaf(p, root, e[j]) != leaf {
      first := k;
    } else {
      var j: nat :| j < k && TotalLeaf(p, root, e[j]) == leaf;
      first := FirstDeriving(p, rank, bound, root, leaf, j);
    }
  }

  lemma EmittedOncePerLeaf(p: R.Program, rank: seq<nat>, bound: nat,
                           root: nat, k1: nat, k2: nat)
    requires O.Wf(p, rank, bound) && root < |p.successors|
    requires EmittedIndex(p, rank, bound, root, k1)
    requires EmittedIndex(p, rank, bound, root, k2)
    requires TotalLeaf(p, root, O.AllPaths(p, rank, bound, root)[k1])
          == TotalLeaf(p, root, O.AllPaths(p, rank, bound, root)[k2])
    ensures k1 == k2
  {
    if k1 < k2 {
      assert TotalLeaf(p, root, O.AllPaths(p, rank, bound, root)[k1])
          != TotalLeaf(p, root, O.AllPaths(p, rank, bound, root)[k2]);
    } else if k2 < k1 {
      assert TotalLeaf(p, root, O.AllPaths(p, rank, bound, root)[k2])
          != TotalLeaf(p, root, O.AllPaths(p, rank, bound, root)[k1]);
    }
  }

  // ---------------------------------------------------------------------
  // Interior steps, concatenation, and prune soundness
  // ---------------------------------------------------------------------

  ghost predicate Steps(p: R.Program, node: nat, path: O.Path)
    decreases |path|
  {
    |path| == 0 ||
    (node !in p.resultNodes &&
     node < |p.successors| &&
     path[0] < |R.Successors(p, node)| &&
     Steps(p, R.Successors(p, node)[path[0]], path[1..]))
  }

  ghost function After(p: R.Program, node: nat, path: O.Path): nat
    requires Steps(p, node, path)
    decreases |path|
  {
    if |path| == 0 then node
    else After(p, R.Successors(p, node)[path[0]], path[1..])
  }

  lemma StepsRaiseRank(p: R.Program, rank: seq<nat>, bound: nat,
                       node: nat, path: O.Path)
    requires O.Wf(p, rank, bound) && node < |p.successors|
    requires Steps(p, node, path) && |path| > 0
    ensures After(p, node, path) < |p.successors|
    ensures rank[After(p, node, path)] > rank[node]
    decreases |path|
  {
    var child := R.Successors(p, node)[path[0]];
    if |path| > 1 {
      StepsRaiseRank(p, rank, bound, child, path[1..]);
    }
  }

  lemma StepsEndInBounds(p: R.Program, rank: seq<nat>, bound: nat,
                         node: nat, path: O.Path)
    requires O.Wf(p, rank, bound) && node < |p.successors|
    requires Steps(p, node, path)
    ensures After(p, node, path) < |p.successors|
    decreases |path|
  {
    if |path| > 0 {
      StepsEndInBounds(p, rank, bound,
                       R.Successors(p, node)[path[0]], path[1..]);
    }
  }

  lemma ConcatComplete(p: R.Program, node: nat, prefix: O.Path,
                       completion: O.Path)
    requires Steps(p, node, prefix)
    requires O.CompletePath(p, After(p, node, prefix), completion)
    ensures O.CompletePath(p, node, prefix + completion)
    ensures O.PathLeaf(p, node, prefix + completion)
         == O.PathLeaf(p, After(p, node, prefix), completion)
    decreases |prefix|
  {
    if |prefix| == 0 {
      assert prefix + completion == completion;
    } else {
      var child := R.Successors(p, node)[prefix[0]];
      ConcatComplete(p, child, prefix[1..], completion);
      assert (prefix + completion)[1..] == prefix[1..] + completion;
    }
  }

  lemma SplitComplete(p: R.Program, node: nat, prefix: O.Path,
                      whole: O.Path)
    requires Steps(p, node, prefix)
    requires O.CompletePath(p, node, whole)
    requires |prefix| <= |whole| && whole[..|prefix|] == prefix
    ensures O.CompletePath(p, After(p, node, prefix), whole[|prefix|..])
    ensures O.PathLeaf(p, node, whole)
         == O.PathLeaf(p, After(p, node, prefix), whole[|prefix|..])
    decreases |prefix|
  {
    if |prefix| > 0 {
      var child := R.Successors(p, node)[prefix[0]];
      assert whole[1..][..|prefix[1..]|] == whole[..|prefix|][1..];
      SplitComplete(p, child, prefix[1..], whole[1..]);
      assert whole[1..][|prefix[1..]|..] == whole[|prefix|..];
    }
  }

  lemma LexConcatDiverge(a: O.Path, b: O.Path, x: O.Path, y: O.Path,
                         j: nat)
    requires j < |a| && j < |b|
    requires a[..j] == b[..j] && a[j] < b[j]
    ensures O.Lex(a + x, b + y)
    decreases j
  {
    if j == 0 {
      assert (a + x)[0] == a[0] && (b + y)[0] == b[0];
    } else {
      assert a[1..][..j - 1] == a[..j][1..] == b[..j][1..]
          == b[1..][..j - 1];
      LexConcatDiverge(a[1..], b[1..], x, y, j - 1);
      assert (a + x)[1..] == a[1..] + x;
      assert (b + y)[1..] == b[1..] + y;
      assert (a + x)[0] == (b + y)[0];
    }
  }

  // Two step-prefixes reaching the same interior node never extend one
  // another (rank strictly rises along nonempty step segments), so a
  // lex-smaller one diverges at an index below both lengths.
  lemma SameNodePrefixesDiverge(p: R.Program, rank: seq<nat>, bound: nat,
                                root: nat, q: O.Path, r: O.Path)
    returns (j: nat)
    requires O.Wf(p, rank, bound) && root < |p.successors|
    requires Steps(p, root, q) && Steps(p, root, r)
    requires After(p, root, q) == After(p, root, r)
    requires q != r
    ensures j < |q| && j < |r| && q[..j] == r[..j] && q[j] != r[j]
    decreases |q|
  {
    if |q| == 0 || |r| == 0 {
      // One is empty: the other is a nonempty loop on the same node,
      // impossible because rank strictly rises.
      if |q| == 0 {
        StepsRaiseRank(p, rank, bound, root, r);
      } else {
        StepsRaiseRank(p, rank, bound, root, q);
      }
      assert false;
    } else if q[0] == r[0] {
      var child := R.Successors(p, root)[q[0]];
      var j0 := SameNodePrefixesDiverge(p, rank, bound, child,
                                        q[1..], r[1..]);
      j := j0 + 1;
      assert q[..j] == [q[0]] + q[1..][..j0];
      assert r[..j] == [r[0]] + r[1..][..j0];
    } else {
      j := 0;
    }
  }

  // Pruning a repeated interior state is sound: no emission's path passes
  // through the lex-greater occurrence of a node already reached by a
  // lex-smaller step-prefix.
  lemma NoEmissionUnderRepeatedState(p: R.Program, rank: seq<nat>,
                                     bound: nat, root: nat,
                                     q: O.Path, r: O.Path, k: nat)
    requires O.Wf(p, rank, bound) && root < |p.successors|
    requires Steps(p, root, q) && Steps(p, root, r)
    requires After(p, root, q) == After(p, root, r)
    requires q != r && O.Lex(q, r)
    requires EmittedIndex(p, rank, bound, root, k)
    ensures var e := O.AllPaths(p, rank, bound, root);
      !(|r| <= |e[k]| && e[k][..|r|] == r)
  {
    var e := O.AllPaths(p, rank, bound, root);
    O.AllPathsSound(p, rank, bound, root);
    if |r| <= |e[k]| && e[k][..|r|] == r {
      SplitComplete(p, root, r, e[k]);
      var completion := e[k][|r|..];
      ConcatComplete(p, root, q, completion);
      var smaller := q + completion;
      assert O.PathLeaf(p, root, smaller)
          == O.PathLeaf(p, root, e[k]);
      var j := SameNodePrefixesDiverge(p, rank, bound, root, q, r);
      if q[j] < r[j] {
        LexConcatDiverge(q, r, completion, completion, j);
      } else {
        // Lex(q, r) forces divergence in q's favor at the first
        // difference; a divergence with q[j] > r[j] would contradict it.
        LexConcatDiverge(r, q, [], [], j);
        assert O.Lex(r + [], q + []);
        assert r + [] == r && q + [] == q;
        O.LexTransitive(q, r, q);
        O.LexIrreflexive(q);
        assert false;
      }
      assert r + completion == e[k];
      assert O.Lex(smaller, e[k]);
      EmittedImpliesNoSmallerWitness(p, rank, bound, root, k);
      assert false;
    }
  }

  // ---------------------------------------------------------------------
  // Bridge: reachable leaves here are the engine's reachable results
  // ---------------------------------------------------------------------

  lemma IndexPathToNodePath(p: R.Program, node: nat, path: O.Path)
    returns (nodes: seq<nat>)
    requires R.ValidProgram(p) && node < |p.successors|
    requires O.CompletePath(p, node, path)
    ensures |nodes| == |path| + 1
    ensures nodes[0] == node
    ensures nodes[|nodes| - 1] == O.PathLeaf(p, node, path)
    ensures C.PathEdges(p, nodes)
    ensures forall n: nat | n in R.SeqSet(nodes) :: n < |p.successors|
    decreases |path|
  {
    if |path| == 0 {
      nodes := [node];
      assert R.SeqSet(nodes) == {node} + R.SeqSet([]);
    } else {
      var succ := R.Successors(p, node);
      SeqSetHasIndex(succ, path[0]);
      var child := succ[path[0]];
      assert child in R.SeqSet(succ);
      assert child in R.Nodes(p);
      R.RangeMembership(|p.successors|, child);
      var rest := IndexPathToNodePath(p, child, path[1..]);
      nodes := [node] + rest;
      assert nodes[1..] == rest;
      R.SeqSetConcat([node], rest);
      assert R.SeqSet([node]) == {node} + R.SeqSet([]);
    }
  }

  lemma SeqSetHasIndex(values: seq<nat>, i: nat)
    requires i < |values|
    ensures values[i] in R.SeqSet(values)
    decreases i
  {
    if i > 0 {
      SeqSetHasIndex(values[1..], i - 1);
    }
  }

  lemma NodePathToIndexPath(p: R.Program, nodes: seq<nat>)
    returns (path: O.Path)
    requires R.ValidProgram(p) && O.LeafResults(p)
    requires |nodes| > 0 && nodes[0] < |p.successors|
    requires C.PathEdges(p, nodes)
    requires nodes[|nodes| - 1] in p.resultNodes
    requires forall i: nat | i < |nodes| - 1 ::
      nodes[i] !in p.resultNodes
    ensures O.CompletePath(p, nodes[0], path)
    ensures O.PathLeaf(p, nodes[0], path) == nodes[|nodes| - 1]
    decreases |nodes|
  {
    if |nodes| == 1 {
      path := [];
    } else {
      assert nodes[1] in R.SeqSet(R.Successors(p, nodes[0]));
      B.SeqSetMembership(R.Successors(p, nodes[0]), nodes[1]);
      var i: nat :| i < |R.Successors(p, nodes[0])| &&
                    R.Successors(p, nodes[0])[i] == nodes[1];
      assert nodes[1] in R.Nodes(p);
      R.RangeMembership(|p.successors|, nodes[1]);
      var rest := NodePathToIndexPath(p, nodes[1..]);
      path := [i] + rest;
      assert path[1..] == rest;
    }
  }

  // A result leaf is ReachableLeaf here iff the engine's reachability
  // (over interior nodes) also reaches it: the denotations coincide.
  lemma ReachableLeafBridge(p: R.Program, rank: seq<nat>, bound: nat,
                            root: nat, leaf: nat)
    requires O.Wf(p, rank, bound) && root < |p.successors|
    requires root !in p.resultNodes
    ensures ReachableLeaf(p, root, leaf) ==>
      (C.Reachable(p, [root], leaf) && leaf in p.resultNodes)
  {
    if ReachableLeaf(p, root, leaf) {
      var path: O.Path :| O.CompletePath(p, root, path) &&
                          O.PathLeaf(p, root, path) == leaf;
      var nodes := IndexPathToNodePath(p, root, path);
      assert R.SeqSet([root]) == {root} + R.SeqSet([]);
      forall n: nat | n in R.SeqSet(nodes)
        ensures n in R.Nodes(p)
      {
        R.RangeMembership(|p.successors|, n);
      }
      assert C.ValidPath(p, [root], nodes);
      assert C.Reachable(p, [root], leaf);
      LeafIsResult(p, root, path);
    }
  }

  lemma LeafIsResult(p: R.Program, node: nat, path: O.Path)
    requires O.CompletePath(p, node, path)
    ensures O.PathLeaf(p, node, path) in p.resultNodes
    decreases |path|
  {
    if |path| > 0 {
      LeafIsResult(p, R.Successors(p, node)[path[0]], path[1..]);
    }
  }

  // ---------------------------------------------------------------------
  // The leaf-level merge optimization
  // ---------------------------------------------------------------------

  ghost predicate Ascending(values: seq<nat>)
    decreases |values|
  {
    |values| <= 1 ||
    (values[0] < values[1] && Ascending(values[1..]))
  }

  ghost function Merge(a: seq<nat>, b: seq<nat>): seq<nat>
    decreases |a| + |b|
  {
    if |a| == 0 then b
    else if |b| == 0 then a
    else if a[0] < b[0] then [a[0]] + Merge(a[1..], b)
    else if b[0] < a[0] then [b[0]] + Merge(a, b[1..])
    else [a[0]] + Merge(a[1..], b[1..])
  }

  lemma MergeUnion(a: seq<nat>, b: seq<nat>)
    ensures R.SeqSet(Merge(a, b)) == R.SeqSet(a) + R.SeqSet(b)
    decreases |a| + |b|
  {
    if |a| > 0 && |b| > 0 {
      if a[0] < b[0] {
        MergeUnion(a[1..], b);
        assert Merge(a, b)[1..] == Merge(a[1..], b);
      } else if b[0] < a[0] {
        MergeUnion(a, b[1..]);
        assert Merge(a, b)[1..] == Merge(a, b[1..]);
      } else {
        MergeUnion(a[1..], b[1..]);
        assert Merge(a, b)[1..] == Merge(a[1..], b[1..]);
      }
    }
  }

  lemma AscendingLowerBound(values: seq<nat>, floor: nat)
    requires Ascending(values)
    requires |values| > 0 ==> floor < values[0]
    ensures forall v: nat | v in R.SeqSet(values) :: floor < v
    decreases |values|
  {
    if |values| > 1 {
      AscendingLowerBound(values[1..], floor);
    }
  }

  lemma MergeAscending(a: seq<nat>, b: seq<nat>)
    requires Ascending(a) && Ascending(b)
    ensures Ascending(Merge(a, b))
    decreases |a| + |b|
  {
    if |a| > 0 && |b| > 0 {
      if a[0] < b[0] {
        MergeAscending(a[1..], b);
        MergeHeadFloor(a[1..], b, a[0]);
        assert Merge(a, b)[1..] == Merge(a[1..], b);
      } else if b[0] < a[0] {
        MergeAscending(a, b[1..]);
        MergeHeadFloor(a, b[1..], b[0]);
        assert Merge(a, b)[1..] == Merge(a, b[1..]);
      } else {
        MergeAscending(a[1..], b[1..]);
        MergeHeadFloor(a[1..], b[1..], a[0]);
        assert Merge(a, b)[1..] == Merge(a[1..], b[1..]);
      }
    }
  }

  lemma MergeHeadFloor(a: seq<nat>, b: seq<nat>, floor: nat)
    requires Ascending(a) && Ascending(b)
    requires |a| > 0 ==> floor < a[0]
    requires |b| > 0 ==> floor < b[0]
    ensures |Merge(a, b)| > 0 ==> floor < Merge(a, b)[0]
  {
  }
}

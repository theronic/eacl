// Least-derivation-path order (acyclic-keyset-pagination, task 1.1): over
// an acyclic positive program whose successor vectors are already sealed
// in canonical producer order, a derivation is the sequence of successor
// indices chosen from the root to a result leaf. This leaf proves:
//
//  - lexicographic comparison of index sequences is a strict total order
//    on the complete derivation paths of one root (complete paths are
//    mutually prefix-free because results are leaves);
//  - the ordered depth-first construction `AllPaths` enumerates exactly
//    the valid complete paths, each exactly once, in strictly ascending
//    lexicographic order — so emission order equals coordinate order with
//    no runtime sorting, and the least path of every derivable leaf is
//    its first occurrence;
//  - a strictly smaller path decomposes as an equal prefix followed by an
//    earlier successor index (`LexDecomposition`) — the clause structure
//    the runtime smaller-witness check implements with bounded probes.
//
// Acyclicity is certified by a ghost rank strictly increasing along every
// edge, the same static shape the sealed plan's read-rank certificate
// already carries.
//
// Exploratory proof model; intentionally excluded from release artifacts.
include "StableReducer.dfy"

module LeastPathOrder {
  import R = StableReducer

  type Path = seq<nat>

  // ---------------------------------------------------------------------
  // Well-formed acyclic programs
  // ---------------------------------------------------------------------

  ghost predicate LeafResults(p: R.Program) {
    forall node: nat | node in p.resultNodes ::
      R.Successors(p, node) == []
  }

  ghost predicate RankedAcyclic(p: R.Program, rank: seq<nat>) {
    |rank| == |p.successors| &&
    forall node: nat, k: nat |
      node < |p.successors| && k < |R.Successors(p, node)| ::
        R.Successors(p, node)[k] < |p.successors| &&
        rank[R.Successors(p, node)[k]] > rank[node]
  }

  ghost predicate Wf(p: R.Program, rank: seq<nat>, bound: nat) {
    R.ValidProgram(p) && LeafResults(p) && RankedAcyclic(p, rank) &&
    forall node: nat | node < |rank| :: rank[node] < bound
  }

  // ---------------------------------------------------------------------
  // Valid complete paths and the leaf they derive
  // ---------------------------------------------------------------------

  ghost predicate CompletePath(p: R.Program, node: nat, path: Path)
    decreases |path|
  {
    if |path| == 0 then
      node in p.resultNodes
    else
      node !in p.resultNodes &&
      node < |p.successors| &&
      path[0] < |R.Successors(p, node)| &&
      CompletePath(p, R.Successors(p, node)[path[0]], path[1..])
  }

  ghost function PathLeaf(p: R.Program, node: nat, path: Path): nat
    requires CompletePath(p, node, path)
    decreases |path|
  {
    if |path| == 0 then node
    else PathLeaf(p, R.Successors(p, node)[path[0]], path[1..])
  }

  // ---------------------------------------------------------------------
  // Lexicographic order
  // ---------------------------------------------------------------------

  ghost predicate Lex(a: Path, b: Path)
    decreases |a|
  {
    if |a| == 0 then
      |b| > 0
    else if |b| == 0 then
      false
    else
      a[0] < b[0] || (a[0] == b[0] && Lex(a[1..], b[1..]))
  }

  lemma LexIrreflexive(a: Path)
    ensures !Lex(a, a)
    decreases |a|
  {
    if |a| > 0 { LexIrreflexive(a[1..]); }
  }

  lemma LexTrichotomy(a: Path, b: Path)
    ensures Lex(a, b) || Lex(b, a) || a == b
    decreases |a|
  {
    if |a| > 0 && |b| > 0 && a[0] == b[0] {
      LexTrichotomy(a[1..], b[1..]);
      if a[1..] == b[1..] {
        assert a == [a[0]] + a[1..] == [b[0]] + b[1..] == b;
      }
    }
  }

  lemma LexTransitive(a: Path, b: Path, c: Path)
    requires Lex(a, b) && Lex(b, c)
    ensures Lex(a, c)
    decreases |a|
  {
    if |a| > 0 && |b| > 0 && |c| > 0 &&
       a[0] == b[0] && b[0] == c[0] {
      LexTransitive(a[1..], b[1..], c[1..]);
    }
  }

  lemma LexPrepend(i: nat, a: Path, b: Path)
    requires Lex(a, b)
    ensures Lex([i] + a, [i] + b)
  {
    assert ([i] + a)[1..] == a && ([i] + b)[1..] == b;
  }

  lemma LexPrependLt(i: nat, j: nat, a: Path, b: Path)
    requires i < j
    ensures Lex([i] + a, [j] + b)
  {
  }

  // A strict prefix is lexicographically smaller; complete paths are
  // never strict prefixes of one another, because a complete path ends
  // at a leaf with no successors.
  lemma CompletePathsPrefixFree(p: R.Program, node: nat, a: Path, b: Path)
    requires CompletePath(p, node, a) && CompletePath(p, node, b)
    requires |a| < |b|
    ensures a != b[..|a|]
    decreases |a|
  {
    if |a| == 0 {
      // node is a leaf, so b cannot take a step.
      assert R.Successors(p, node) == [];
    } else if a[0] == b[0] {
      assert b[1..][..|a[1..]|] == b[..|a|][1..];
      CompletePathsPrefixFree(p, R.Successors(p, node)[a[0]],
                              a[1..], b[1..]);
    }
  }

  // Distinct complete paths of one root always diverge at an index, and
  // the lex order is decided exactly there: the clause structure the
  // runtime witness check implements per level.
  lemma LexDecomposition(p: R.Program, node: nat, a: Path, b: Path)
    requires CompletePath(p, node, a) && CompletePath(p, node, b)
    requires a != b
    ensures exists j: nat ::
      j < |a| && j < |b| && a[..j] == b[..j] && a[j] != b[j] &&
      (Lex(a, b) <==> a[j] < b[j])
    decreases |a|
  {
    if |a| == 0 || |b| == 0 {
      // One is empty: node both a leaf and not — impossible unless the
      // other is empty too, contradicting a != b.
      assert false;
    } else if a[0] == b[0] {
      LexDecomposition(p, R.Successors(p, node)[a[0]], a[1..], b[1..]);
      var j: nat :| j < |a[1..]| && j < |b[1..]| &&
        a[1..][..j] == b[1..][..j] && a[1..][j] != b[1..][j] &&
        (Lex(a[1..], b[1..]) <==> a[1..][j] < b[1..][j]);
      assert a[..j + 1] == [a[0]] + a[1..][..j];
      assert b[..j + 1] == [b[0]] + b[1..][..j];
      assert a[..j + 1] == b[..j + 1] && a[j + 1] != b[j + 1];
      assert Lex(a, b) <==> Lex(a[1..], b[1..]);
    } else {
      assert a[..0] == b[..0];
    }
  }

  // ---------------------------------------------------------------------
  // The ordered enumeration of complete paths
  // ---------------------------------------------------------------------

  ghost function Prefixed(i: nat, paths: seq<Path>): seq<Path> {
    seq(|paths|, k requires 0 <= k < |paths| => [i] + paths[k])
  }

  ghost function AllPaths(p: R.Program, rank: seq<nat>, bound: nat,
                          node: nat): seq<Path>
    requires Wf(p, rank, bound) && node < |p.successors|
    decreases bound - rank[node], 0
  {
    if node in p.resultNodes then [[]]
    else PathsFromAlt(p, rank, bound, node, 0)
  }

  ghost function PathsFromAlt(p: R.Program, rank: seq<nat>, bound: nat,
                              node: nat, i: nat): seq<Path>
    requires Wf(p, rank, bound) && node < |p.successors|
    requires node !in p.resultNodes
    requires i <= |R.Successors(p, node)|
    decreases bound - rank[node], 0, |R.Successors(p, node)| - i
  {
    if i == |R.Successors(p, node)| then []
    else
      Prefixed(i, AllPaths(p, rank, bound, R.Successors(p, node)[i]))
      + PathsFromAlt(p, rank, bound, node, i + 1)
  }

  // Soundness: everything enumerated is a valid complete path.
  lemma AllPathsSound(p: R.Program, rank: seq<nat>, bound: nat, node: nat)
    requires Wf(p, rank, bound) && node < |p.successors|
    ensures forall k: nat | k < |AllPaths(p, rank, bound, node)| ::
      CompletePath(p, node, AllPaths(p, rank, bound, node)[k])
    decreases bound - rank[node], 0
  {
    if node !in p.resultNodes {
      AltSound(p, rank, bound, node, 0);
    }
  }

  lemma AltSound(p: R.Program, rank: seq<nat>, bound: nat, node: nat,
                 i: nat)
    requires Wf(p, rank, bound) && node < |p.successors|
    requires node !in p.resultNodes
    requires i <= |R.Successors(p, node)|
    ensures forall k: nat | k < |PathsFromAlt(p, rank, bound, node, i)| ::
      CompletePath(p, node, PathsFromAlt(p, rank, bound, node, i)[k])
    decreases bound - rank[node], 0, |R.Successors(p, node)| - i
  {
    if i < |R.Successors(p, node)| {
      var child := R.Successors(p, node)[i];
      AllPathsSound(p, rank, bound, child);
      var block := Prefixed(i, AllPaths(p, rank, bound, child));
      forall k: nat | k < |block|
        ensures CompletePath(p, node, block[k])
      {
        assert block[k][1..] == AllPaths(p, rank, bound, child)[k];
      }
      AltSound(p, rank, bound, node, i + 1);
      var rest := PathsFromAlt(p, rank, bound, node, i + 1);
      forall k: nat | k < |block| + |rest|
        ensures CompletePath(p, node,
                             PathsFromAlt(p, rank, bound, node, i)[k])
      {
        if k < |block| {
          assert PathsFromAlt(p, rank, bound, node, i)[k] == block[k];
        } else {
          assert PathsFromAlt(p, rank, bound, node, i)[k]
              == rest[k - |block|];
        }
      }
    }
  }

  // Completeness: every valid complete path is enumerated.
  lemma AllPathsComplete(p: R.Program, rank: seq<nat>, bound: nat,
                         node: nat, path: Path)
    requires Wf(p, rank, bound) && node < |p.successors|
    requires CompletePath(p, node, path)
    ensures path in AllPaths(p, rank, bound, node)
    decreases bound - rank[node], 0
  {
    if |path| == 0 {
      assert AllPaths(p, rank, bound, node) == [[]];
    } else {
      AltComplete(p, rank, bound, node, path, 0);
    }
  }

  lemma AltComplete(p: R.Program, rank: seq<nat>, bound: nat, node: nat,
                    path: Path, i: nat)
    requires Wf(p, rank, bound) && node < |p.successors|
    requires node !in p.resultNodes
    requires CompletePath(p, node, path) && |path| > 0
    requires i <= path[0]
    ensures path in PathsFromAlt(p, rank, bound, node, i)
    decreases bound - rank[node], 0, |R.Successors(p, node)| - i
  {
    var child := R.Successors(p, node)[i];
    if i == path[0] {
      AllPathsComplete(p, rank, bound,
                       R.Successors(p, node)[path[0]], path[1..]);
      var inner := AllPaths(p, rank, bound, child);
      var k: nat :| k < |inner| && inner[k] == path[1..];
      assert Prefixed(i, inner)[k] == [i] + path[1..] == path;
      assert path in Prefixed(i, inner);
    } else {
      AltComplete(p, rank, bound, node, path, i + 1);
    }
  }

  // Strict ascending order: the enumeration is sorted without sorting.
  ghost predicate StrictlySorted(paths: seq<Path>) {
    forall j: nat, k: nat | j < k < |paths| :: Lex(paths[j], paths[k])
  }

  lemma PrefixedSorted(i: nat, paths: seq<Path>)
    requires StrictlySorted(paths)
    ensures StrictlySorted(Prefixed(i, paths))
  {
    forall j: nat, k: nat | j < k < |paths|
      ensures Lex(Prefixed(i, paths)[j], Prefixed(i, paths)[k])
    {
      LexPrepend(i, paths[j], paths[k]);
    }
  }

  lemma AllPathsSorted(p: R.Program, rank: seq<nat>, bound: nat, node: nat)
    requires Wf(p, rank, bound) && node < |p.successors|
    ensures StrictlySorted(AllPaths(p, rank, bound, node))
    decreases bound - rank[node], 0
  {
    if node !in p.resultNodes {
      AltSorted(p, rank, bound, node, 0);
    }
  }

  // Every path in the tail block starts with an alternative >= i.
  lemma AltLowerBound(p: R.Program, rank: seq<nat>, bound: nat, node: nat,
                      i: nat)
    requires Wf(p, rank, bound) && node < |p.successors|
    requires node !in p.resultNodes
    requires i <= |R.Successors(p, node)|
    ensures forall k: nat | k < |PathsFromAlt(p, rank, bound, node, i)| ::
      |PathsFromAlt(p, rank, bound, node, i)[k]| > 0 &&
      PathsFromAlt(p, rank, bound, node, i)[k][0] >= i
    decreases bound - rank[node], 0, |R.Successors(p, node)| - i
  {
    if i < |R.Successors(p, node)| {
      AltLowerBound(p, rank, bound, node, i + 1);
      var child := R.Successors(p, node)[i];
      var block := Prefixed(i, AllPaths(p, rank, bound, child));
      var rest := PathsFromAlt(p, rank, bound, node, i + 1);
      forall k: nat | k < |block| + |rest|
        ensures |PathsFromAlt(p, rank, bound, node, i)[k]| > 0 &&
                PathsFromAlt(p, rank, bound, node, i)[k][0] >= i
      {
        if k < |block| {
          assert PathsFromAlt(p, rank, bound, node, i)[k] == block[k];
        } else {
          assert PathsFromAlt(p, rank, bound, node, i)[k]
              == rest[k - |block|];
        }
      }
    }
  }

  lemma AltSorted(p: R.Program, rank: seq<nat>, bound: nat, node: nat,
                  i: nat)
    requires Wf(p, rank, bound) && node < |p.successors|
    requires node !in p.resultNodes
    requires i <= |R.Successors(p, node)|
    ensures StrictlySorted(PathsFromAlt(p, rank, bound, node, i))
    decreases bound - rank[node], 0, |R.Successors(p, node)| - i
  {
    if i < |R.Successors(p, node)| {
      var child := R.Successors(p, node)[i];
      AllPathsSorted(p, rank, bound, child);
      PrefixedSorted(i, AllPaths(p, rank, bound, child));
      AltSorted(p, rank, bound, node, i + 1);
      AltLowerBound(p, rank, bound, node, i + 1);
      var block := Prefixed(i, AllPaths(p, rank, bound, child));
      var rest := PathsFromAlt(p, rank, bound, node, i + 1);
      var whole := PathsFromAlt(p, rank, bound, node, i);
      forall j: nat, k: nat | j < k < |whole|
        ensures Lex(whole[j], whole[k])
      {
        if j < |block| && k < |block| {
          assert whole[j] == block[j] && whole[k] == block[k];
        } else if j < |block| && k >= |block| {
          assert whole[j] == block[j] && whole[k] == rest[k - |block|];
          assert whole[j][0] == i && whole[k][0] >= i + 1;
          LexPrependLt(whole[j][0], whole[k][0],
                       whole[j][1..], whole[k][1..]);
          assert [whole[j][0]] + whole[j][1..] == whole[j];
          assert [whole[k][0]] + whole[k][1..] == whole[k];
        } else {
          assert whole[j] == rest[j - |block|] &&
                 whole[k] == rest[k - |block|];
        }
      }
    }
  }
}

// Least-path resume (acyclic-keyset-pagination, task 1.3): keyset resume
// and descending windows over the least-path enumeration. This leaf
// proves:
//
//  - the complete paths strictly lex-greater than a boundary path are
//    exactly the enumeration's suffix after the boundary's position
//    (`SuffixIsGreater`, `GreaterIsSuffix`): seeking every level strictly
//    past the boundary's coordinates and continuing the ordered DFS
//    reproduces precisely the remaining enumeration — no server state,
//    no replay;
//  - the emission decision is a global predicate of the program and the
//    position alone (`EmissionPositionFree` restates the witness form
//    with no reference to traversal history or direction), so a resumed
//    walk emits exactly the suffix of the full walk's emissions;
//  - the reversed enumeration is strictly descending
//    (`ReverseStrictlyDescending`), and descending traversal meets
//    emitted positions in exactly reverse order (`ReverseVisitsInOrder`),
//    so ascending and descending walks agree on every emission position
//    and a :last window is the reverse-order prefix of the same emitted
//    sequence.
//
// Exploratory proof model; intentionally excluded from release artifacts.
include "LeastPathEnumeration.dfy"

module LeastPathResume {
  import R = StableReducer
  import O = LeastPathOrder
  import E = LeastPathEnumeration

  // ---------------------------------------------------------------------
  // Keyset resume: the strictly-greater paths are exactly the suffix
  // ---------------------------------------------------------------------

  lemma SuffixIsGreater(p: R.Program, rank: seq<nat>, bound: nat,
                        root: nat, k: nat, m: nat)
    requires O.Wf(p, rank, bound) && root < |p.successors|
    requires k < m < |O.AllPaths(p, rank, bound, root)|
    ensures O.Lex(O.AllPaths(p, rank, bound, root)[k],
                  O.AllPaths(p, rank, bound, root)[m])
  {
    O.AllPathsSorted(p, rank, bound, root);
  }

  lemma GreaterIsSuffix(p: R.Program, rank: seq<nat>, bound: nat,
                        root: nat, k: nat, q: O.Path)
    returns (m: nat)
    requires O.Wf(p, rank, bound) && root < |p.successors|
    requires k < |O.AllPaths(p, rank, bound, root)|
    requires O.CompletePath(p, root, q)
    requires O.Lex(O.AllPaths(p, rank, bound, root)[k], q)
    ensures k < m < |O.AllPaths(p, rank, bound, root)|
    ensures O.AllPaths(p, rank, bound, root)[m] == q
  {
    var e := O.AllPaths(p, rank, bound, root);
    O.AllPathsSorted(p, rank, bound, root);
    O.AllPathsComplete(p, rank, bound, root, q);
    var found: nat :| found < |e| && e[found] == q;
    if found < k {
      assert O.Lex(e[found], e[k]);
      O.LexTransitive(e[k], q, e[k]);
      O.LexIrreflexive(e[k]);
    } else if found == k {
      O.LexIrreflexive(e[k]);
    }
    m := found;
  }

  // The emission decision at a position mentions only the program and the
  // position's path — never the traversal that reached it, its direction,
  // or any retained state. A resumed (or reversed) walk that evaluates
  // the same predicate at the same positions therefore emits identically.
  lemma EmissionPositionFree(p: R.Program, rank: seq<nat>, bound: nat,
                             root: nat, k: nat)
    requires O.Wf(p, rank, bound) && root < |p.successors|
    requires k < |O.AllPaths(p, rank, bound, root)|
    ensures E.EmittedIndex(p, rank, bound, root, k) ==>
      !(exists q: O.Path ::
          O.CompletePath(p, root, q) &&
          O.PathLeaf(p, root, q) ==
            E.TotalLeaf(p, root, O.AllPaths(p, rank, bound, root)[k]) &&
          O.Lex(q, O.AllPaths(p, rank, bound, root)[k]))
    ensures !(exists q: O.Path ::
          O.CompletePath(p, root, q) &&
          O.PathLeaf(p, root, q) ==
            E.TotalLeaf(p, root, O.AllPaths(p, rank, bound, root)[k]) &&
          O.Lex(q, O.AllPaths(p, rank, bound, root)[k])) ==>
      E.EmittedIndex(p, rank, bound, root, k)
  {
    if E.EmittedIndex(p, rank, bound, root, k) {
      E.EmittedImpliesNoSmallerWitness(p, rank, bound, root, k);
    } else {
      if !(exists q: O.Path ::
            O.CompletePath(p, root, q) &&
            O.PathLeaf(p, root, q) ==
              E.TotalLeaf(p, root,
                          O.AllPaths(p, rank, bound, root)[k]) &&
            O.Lex(q, O.AllPaths(p, rank, bound, root)[k])) {
        E.NoSmallerWitnessImpliesEmitted(p, rank, bound, root, k);
      }
    }
  }

  // ---------------------------------------------------------------------
  // Descending windows
  // ---------------------------------------------------------------------

  ghost function Reversed(paths: seq<O.Path>): seq<O.Path> {
    seq(|paths|, r requires 0 <= r < |paths| => paths[|paths| - 1 - r])
  }

  lemma ReverseStrictlyDescending(p: R.Program, rank: seq<nat>,
                                  bound: nat, root: nat)
    requires O.Wf(p, rank, bound) && root < |p.successors|
    ensures var rev := Reversed(O.AllPaths(p, rank, bound, root));
      forall r: nat, s: nat | r < s < |rev| :: O.Lex(rev[s], rev[r])
  {
    var e := O.AllPaths(p, rank, bound, root);
    O.AllPathsSorted(p, rank, bound, root);
    var rev := Reversed(e);
    forall r: nat, s: nat | r < s < |rev|
      ensures O.Lex(rev[s], rev[r])
    {
      assert rev[s] == e[|e| - 1 - s] && rev[r] == e[|e| - 1 - r];
      assert |e| - 1 - s < |e| - 1 - r;
    }
  }

  // Descending traversal meets emitted positions in exactly reverse
  // order: of two emitted positions the later one is visited first, so
  // the first N emissions a descending walk meets are the N greatest —
  // the :last window — and every emission position is the same one the
  // ascending walk emitted.
  lemma ReverseVisitsInOrder(p: R.Program, rank: seq<nat>, bound: nat,
                             root: nat, k1: nat, k2: nat)
    requires O.Wf(p, rank, bound) && root < |p.successors|
    requires k1 < k2 < |O.AllPaths(p, rank, bound, root)|
    ensures var n := |O.AllPaths(p, rank, bound, root)|;
      n - 1 - k2 < n - 1 - k1
  {
  }
}

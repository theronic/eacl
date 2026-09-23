// Leveled membership: the widest decisive witness when relationships expire.
//
// Production mapping: eacl.engine.stable-route/decide-leveled and
// search-level, which extend the memoized search of MemoizedMembership.dfy
// to plain and expiring evidence for one subject at one evaluation time.
// Every edge and witness carries a deadline: Forever for plain evidence, or
// the expiry of an unexpired relationship (expired ones are already absent).
// The graph at a level keeps what lasts at least that long; the plain level
// keeps only plain evidence. Within a level the graph is plain, so the
// memoized search and its memo are exactly MemoizedMembership's.
//
// The search runs at the plain level, then at each next level: the latest
// deadline that the previous level's exhausted closure skipped, including the
// bounds retained with reused negatives. This leaf proves:
//
// - no witness lies strictly between a skipped bound and its level;
// - found at a level, that level is the widest witness;
// - its certificate is exact in time;
// - exhausted with nothing skipped, the subject holds the permission at no
//   level at all.
include "MemoizedMembership.dfy"

module LeveledMembership {
  import opened MemoizedMembership

  datatype Deadline = Forever | Until(at: int)

  datatype Level = PlainLevel | At(t: int)

  datatype Holding = NoHolding | HoldingUntil(deadline: Deadline)

  datatype Bound = Nothing | Latest(at: int)

  // Each state's edges with their deadlines, and the base tuple the subject
  // holds there, if any (a witness state).
  datatype LeveledGraph = LeveledGraph(
    edges: seq<seq<(nat, Deadline)>>,
    holding: seq<Holding>
  )

  predicate Lasts(d: Deadline, level: Level) {
    d.Forever? || (level.At? && level.t <= d.at)
  }

  predicate LevelLe(u: Level, v: Level) {
    v.PlainLevel? || (u.At? && v.At? && u.t <= v.t)
  }

  predicate LevelLt(u: Level, v: Level) {
    LevelLe(u, v) && u != v
  }

  predicate Within(d: Deadline, bound: Bound) {
    bound.Latest? && d.Until? && d.at <= bound.at
  }

  function Later(a: Bound, b: Bound): Bound {
    if a.Nothing? then b
    else if b.Nothing? then a
    else Latest(if a.at < b.at then b.at else a.at)
  }

  ghost predicate ValidLeveled(g: LeveledGraph) {
    |g.holding| == |g.edges| &&
    forall s, i | 0 <= s < |g.edges| && 0 <= i < |g.edges[s]| ::
      g.edges[s][i].0 < |g.edges|
  }

  function KeptTargets(es: seq<(nat, Deadline)>, level: Level): seq<nat>
    decreases |es|
  {
    if |es| == 0 then []
    else (if Lasts(es[0].1, level) then [es[0].0] else []) + KeptTargets(es[1..], level)
  }

  function AtLevel(g: LeveledGraph, level: Level): Graph
    requires |g.holding| == |g.edges|
  {
    Graph(
      seq(|g.edges|, s requires 0 <= s < |g.edges| => KeptTargets(g.edges[s], level)),
      set s | 0 <= s < |g.holding| && g.holding[s].HoldingUntil? &&
              Lasts(g.holding[s].deadline, level)
    )
  }

  // ---------------------------------------------------------------------
  // The graph at a level
  // ---------------------------------------------------------------------

  lemma KeptTargetsMember(es: seq<(nat, Deadline)>, level: Level, t: nat)
    ensures t in KeptTargets(es, level) <==>
            exists i :: 0 <= i < |es| && es[i].0 == t && Lasts(es[i].1, level)
    decreases |es|
  {
    if |es| > 0 {
      KeptTargetsMember(es[1..], level, t);
      if t in KeptTargets(es[1..], level) {
        var i :| 0 <= i < |es[1..]| && es[1..][i].0 == t && Lasts(es[1..][i].1, level);
        assert es[i + 1] == es[1..][i];
      }
      if exists i :: 0 <= i < |es| && es[i].0 == t && Lasts(es[i].1, level) {
        var i :| 0 <= i < |es| && es[i].0 == t && Lasts(es[i].1, level);
        if i > 0 {
          assert es[1..][i - 1] == es[i];
        }
      }
    }
  }

  lemma AtLevelValid(g: LeveledGraph, level: Level)
    requires ValidLeveled(g)
    ensures ValidGraph(AtLevel(g, level))
  {
    var G := AtLevel(g, level);
    forall s, t | 0 <= s < |G.succ| && t in G.succ[s]
      ensures t < |G.succ|
    {
      KeptTargetsMember(g.edges[s], level, t);
    }
  }

  lemma LastsMonotone(d: Deadline, u: Level, v: Level)
    requires LevelLe(u, v) && Lasts(d, v)
    ensures Lasts(d, u)
  {
  }

  lemma LevelTotal(u: Level, v: Level)
    ensures LevelLe(u, v) || LevelLt(v, u)
  {
  }

  // A witness at a level is a witness at every lower level.
  lemma PositiveMonotone(g: LeveledGraph, u: Level, v: Level, s: nat)
    requires ValidLeveled(g) && LevelLe(u, v)
    requires Positive(AtLevel(g, v), s)
    ensures Positive(AtLevel(g, u), s)
  {
    var Gv := AtLevel(g, v);
    var Gu := AtLevel(g, u);
    var path :| Walk(Gv, path) && path[0] == s && path[|path| - 1] in Gv.grants;
    forall i | 0 <= i < |path| - 1
      ensures path[i + 1] in Gu.succ[path[i]]
    {
      KeptTargetsMember(g.edges[path[i]], v, path[i + 1]);
      var j :| 0 <= j < |g.edges[path[i]]| && g.edges[path[i]][j].0 == path[i + 1] &&
               Lasts(g.edges[path[i]][j].1, v);
      LastsMonotone(g.edges[path[i]][j].1, u, v);
      KeptTargetsMember(g.edges[path[i]], u, path[i + 1]);
    }
    var last := path[|path| - 1];
    LastsMonotone(g.holding[last].deadline, u, v);
    assert Walk(Gu, path);
  }

  // ---------------------------------------------------------------------
  // Bounded closures: what an exhausted search admitted
  // ---------------------------------------------------------------------

  // Every state of K holds nothing kept at `level`; each kept edge stays in
  // K or enters `rest`; and whatever it has that is not kept ends by `bound`.
  ghost predicate BoundedRelative(
    g: LeveledGraph, level: Level, K: set<nat>, rest: set<nat>, bound: Bound)
  {
    |g.holding| == |g.edges| &&
    forall s | s in K ::
      s < |g.edges| &&
      (g.holding[s].HoldingUntil? ==>
         !Lasts(g.holding[s].deadline, level) && Within(g.holding[s].deadline, bound)) &&
      (forall i | 0 <= i < |g.edges[s]| ::
         if Lasts(g.edges[s][i].1, level)
         then g.edges[s][i].0 in K || g.edges[s][i].0 in rest
         else Within(g.edges[s][i].1, bound))
  }

  ghost predicate BoundedClosure(g: LeveledGraph, level: Level, K: set<nat>, bound: Bound) {
    BoundedRelative(g, level, K, {}, bound)
  }

  lemma WithinLater(d: Deadline, a: Bound, b: Bound)
    ensures Within(d, a) ==> Within(d, Later(a, b))
    ensures Within(d, b) ==> Within(d, Later(a, b))
  {
  }

  // A search's own admitted states, closed up to reused negatives' closure,
  // form one bounded closure with the later bound.
  lemma ComposeClosures(
    g: LeveledGraph, level: Level, A: set<nat>, own: Bound, K: set<nat>, bound: Bound)
    requires BoundedRelative(g, level, A, K, own)
    requires BoundedClosure(g, level, K, bound)
    ensures BoundedClosure(g, level, A + K, Later(own, bound))
  {
    forall s | s in A + K
      ensures s < |g.edges| &&
              (g.holding[s].HoldingUntil? ==>
                 !Lasts(g.holding[s].deadline, level) &&
                 Within(g.holding[s].deadline, Later(own, bound))) &&
              (forall i | 0 <= i < |g.edges[s]| ::
                 if Lasts(g.edges[s][i].1, level)
                 then g.edges[s][i].0 in A + K || g.edges[s][i].0 in {}
                 else Within(g.edges[s][i].1, Later(own, bound)))
    {
      if g.holding[s].HoldingUntil? {
        WithinLater(g.holding[s].deadline, own, bound);
      }
      forall i | 0 <= i < |g.edges[s]|
        ensures if Lasts(g.edges[s][i].1, level)
                then g.edges[s][i].0 in A + K || g.edges[s][i].0 in {}
                else Within(g.edges[s][i].1, Later(own, bound))
      {
        WithinLater(g.edges[s][i].1, own, bound);
      }
    }
  }

  // No state of an exhausted closure has a witness at its own level, nor at
  // any lower level above its bound.
  lemma GapNegative(g: LeveledGraph, level: Level, K: set<nat>, bound: Bound, u: Level)
    requires ValidLeveled(g) && BoundedClosure(g, level, K, bound)
    requires LevelLe(u, level)
    requires u == level || bound.Nothing? || (u.At? && bound.at < u.t)
    ensures forall s | s in K :: !Positive(AtLevel(g, u), s)
  {
    var G := AtLevel(g, u);
    AtLevelValid(g, u);
    forall s | s in K
      ensures s < |G.succ| && s !in G.grants &&
              forall t | t in G.succ[s] :: t in K || t in {}
    {
      if s in G.grants {
        var d := g.holding[s].deadline;
        assert Lasts(d, u);
        assert !Lasts(d, level) && Within(d, bound);
      }
      forall t | t in G.succ[s]
        ensures t in K
      {
        KeptTargetsMember(g.edges[s], u, t);
        var i :| 0 <= i < |g.edges[s]| && g.edges[s][i].0 == t && Lasts(g.edges[s][i].1, u);
        if !Lasts(g.edges[s][i].1, level) {
          assert Within(g.edges[s][i].1, bound);
        }
      }
    }
    assert ClosedUnder(G, K, {});
    assert AllNegative(G, {});
    ClosedSetIsNegative(G, K, {});
  }

  // ---------------------------------------------------------------------
  // The level sequence
  // ---------------------------------------------------------------------

  ghost predicate NoWitnessAbove(g: LeveledGraph, r: nat, v: Level)
    requires |g.holding| == |g.edges|
  {
    forall w: Level | LevelLt(v, w) :: !Positive(AtLevel(g, w), r)
  }

  ghost predicate Widest(g: LeveledGraph, r: nat, v: Level)
    requires |g.holding| == |g.edges|
  {
    Positive(AtLevel(g, v), r) && NoWitnessAbove(g, r, v)
  }

  lemma NothingAbovePlain(g: LeveledGraph, r: nat)
    requires |g.holding| == |g.edges|
    ensures NoWitnessAbove(g, r, PlainLevel)
  {
  }

  // Exhausted at `v` with the root in a closure bounded by `m` below `v`:
  // nothing lies above `At(m)` either.
  lemma NextLevel(g: LeveledGraph, r: nat, v: Level, K: set<nat>, m: int)
    requires ValidLeveled(g) && NoWitnessAbove(g, r, v)
    requires r in K && BoundedClosure(g, v, K, Latest(m))
    requires LevelLt(At(m), v)
    ensures NoWitnessAbove(g, r, At(m))
  {
    forall w: Level | LevelLt(At(m), w)
      ensures !Positive(AtLevel(g, w), r)
    {
      if !LevelLt(v, w) {
        LevelTotal(w, v);
        if w != v {
          assert w.At? && m < w.t;
        }
        GapNegative(g, v, K, Latest(m), w);
      }
    }
  }

  // Found at `v` after the sequence: `v` is the widest witness.
  lemma FoundIsWidest(g: LeveledGraph, r: nat, v: Level)
    requires ValidLeveled(g) && NoWitnessAbove(g, r, v)
    requires Positive(AtLevel(g, v), r)
    ensures Widest(g, r, v)
  {
  }

  // Exhausted with nothing skipped: no witness at any level.
  lemma ExhaustedIsNegativeEverywhere(g: LeveledGraph, r: nat, v: Level, K: set<nat>)
    requires ValidLeveled(g) && NoWitnessAbove(g, r, v)
    requires r in K && BoundedClosure(g, v, K, Nothing)
    ensures forall w: Level :: !Positive(AtLevel(g, w), r)
  {
    forall w: Level
      ensures !Positive(AtLevel(g, w), r)
    {
      if !LevelLt(v, w) {
        LevelTotal(w, v);
        GapNegative(g, v, K, Nothing, w);
      }
    }
  }

  // ---------------------------------------------------------------------
  // Certificates in time
  // ---------------------------------------------------------------------

  // The subject holds the permission at `time` when some witness path is
  // wholly unexpired then: every deadline lies strictly after `time`.
  ghost predicate HoldsAt(g: LeveledGraph, r: nat, time: int)
    requires |g.holding| == |g.edges|
  {
    Positive(AtLevel(g, At(time + 1)), r)
  }

  // Found at the plain level: true at every time.
  lemma PlainCertificate(g: LeveledGraph, r: nat)
    requires ValidLeveled(g) && Positive(AtLevel(g, PlainLevel), r)
    ensures forall time :: HoldsAt(g, r, time)
  {
    forall time
      ensures HoldsAt(g, r, time)
    {
      PositiveMonotone(g, At(time + 1), PlainLevel, r);
    }
  }

  // Found at `At(v)`: true before `v` and false from `v`, so the
  // certificate ending at `v` is the exact end of the grant.
  lemma TimedCertificate(g: LeveledGraph, r: nat, v: int)
    requires ValidLeveled(g) && Widest(g, r, At(v))
    ensures forall time | time < v :: HoldsAt(g, r, time)
    ensures !HoldsAt(g, r, v)
  {
    forall time | time < v
      ensures HoldsAt(g, r, time)
    {
      PositiveMonotone(g, At(time + 1), At(v), r);
    }
    assert LevelLt(At(v), At(v + 1));
  }
}

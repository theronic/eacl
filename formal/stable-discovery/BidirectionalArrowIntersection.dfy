// Bidirectional arrow intersection (bidirectional-arrow-point-check): a
// two-layer arrow arm — resource --via--> intermediate --relation-->
// subject — holds exactly when the resource's finite via-set meets the
// subject's finite holdings-set. Either side may be enumerated while the
// other is decided by exact-bound probes: the answer is proved
// strategy-independent, the interleaved decision is proved equal to
// nonempty intersection, and its consumption is proved bounded by the
// SMALLER side — never the larger. MembershipProbeCheck.dfy bounds the
// point check by the resource's reachable intermediates; this leaf
// licenses bounding each two-layer arm by min(resource fan-in, subject
// holdings) instead, which is the complexity property the resource-only
// probe left open (a resource shared with many intermediates paid the full
// fan-in even when the subject held one tuple).
//
// Runtime shape. The resource-side enumeration is the reverse-index scan
// of the via relation; the subject-side enumeration is the forward-index
// scan of the subject's target-relation holdings; each probe is one
// exact-bound scan (strictly after candidate - 1, limit one). The adapter
// obligations (:subject->resources/:resource->subjects :finite
// :strict-order :unique :complete, :direct-match?
// :iff-forward-scan-membership :iff-reverse-scan-membership) supply the
// CompleteEnumeration and probe premises at runtime.
//
// Exploratory proof model; intentionally excluded from release artifacts.
include "MembershipProbeCheck.dfy"

module BidirectionalArrowIntersection {
  import R = StableReducer

  // ---------------------------------------------------------------------
  // Finite-side enumerations and the arm's denotational answer
  // ---------------------------------------------------------------------

  // A complete, duplicate-free listing of one finite base-tuple side.
  ghost predicate CompleteEnumeration(values: seq<nat>, universe: set<nat>) {
    R.Unique(values) && R.SeqSet(values) == universe
  }

  // The arm holds iff some intermediate carries both base tuples.
  ghost predicate ArmAnswer(vias: set<nat>, holdings: set<nat>) {
    vias * holdings != {}
  }

  lemma SeqSetMembership(values: seq<nat>, value: nat)
    ensures value in R.SeqSet(values) <==>
            exists index: nat :: index < |values| && values[index] == value
    decreases |values|
  {
    if |values| > 0 {
      SeqSetMembership(values[1..], value);
      if value in R.SeqSet(values) && value != values[0] {
        var index: nat :|
          index < |values[1..]| && values[1..][index] == value;
        assert values[index + 1] == value;
      }
      if exists index: nat :: index < |values| && values[index] == value {
        var index: nat :| index < |values| && values[index] == value;
        if index > 0 {
          assert values[1..][index - 1] == value;
        }
      }
    }
  }

  // ---------------------------------------------------------------------
  // Single-sided strategies agree: the answer is strategy-independent
  // ---------------------------------------------------------------------

  // Enumerate the resource's via-set; probe each candidate in holdings.
  ghost predicate ResourceSideAnswer(s: seq<nat>, holdings: set<nat>) {
    exists index: nat :: index < |s| && s[index] in holdings
  }

  // Enumerate the subject's holdings; probe each candidate in vias.
  ghost predicate SubjectSideAnswer(t: seq<nat>, vias: set<nat>) {
    exists index: nat :: index < |t| && t[index] in vias
  }

  lemma StrategiesAgree(
    s: seq<nat>,
    t: seq<nat>,
    vias: set<nat>,
    holdings: set<nat>
  )
    requires CompleteEnumeration(s, vias)
    requires CompleteEnumeration(t, holdings)
    ensures ResourceSideAnswer(s, holdings) <==> ArmAnswer(vias, holdings)
    ensures SubjectSideAnswer(t, vias) <==> ArmAnswer(vias, holdings)
  {
    if ResourceSideAnswer(s, holdings) {
      var index: nat :| index < |s| && s[index] in holdings;
      SeqSetMembership(s, s[index]);
      assert s[index] in vias * holdings;
    }
    if SubjectSideAnswer(t, vias) {
      var index: nat :| index < |t| && t[index] in vias;
      SeqSetMembership(t, t[index]);
      assert t[index] in vias * holdings;
    }
    if ArmAnswer(vias, holdings) {
      var shared: nat :| shared in vias * holdings;
      SeqSetMembership(s, shared);
      SeqSetMembership(t, shared);
    }
  }

  // ---------------------------------------------------------------------
  // The interleaved decision
  // ---------------------------------------------------------------------

  // One round consumes the head of each remaining side: the via candidate
  // is probed in holdings, the holding candidate is probed in vias. The
  // decision stops true at the first positive probe and false as soon as
  // EITHER side exhausts with every consumed candidate probed negative —
  // a fully consumed side has been completely intersected with the other
  // universe, so no shared can remain.
  function Decide(
    s: seq<nat>,
    t: seq<nat>,
    holdings: set<nat>,
    vias: set<nat>
  ): bool
    decreases |s| + |t|
  {
    if |s| == 0 || |t| == 0 then
      false
    else if s[0] in holdings then
      true
    else if t[0] in vias then
      true
    else
      Decide(s[1..], t[1..], holdings, vias)
  }

  lemma DecideSound(
    s: seq<nat>,
    t: seq<nat>,
    holdings: set<nat>,
    vias: set<nat>
  )
    requires R.SeqSet(s) <= vias
    requires R.SeqSet(t) <= holdings
    ensures Decide(s, t, holdings, vias) ==> ArmAnswer(vias, holdings)
    decreases |s| + |t|
  {
    if |s| > 0 && |t| > 0 {
      if s[0] in holdings {
        assert s[0] in R.SeqSet(s);
        assert s[0] in vias * holdings;
      } else if t[0] in vias {
        assert t[0] in R.SeqSet(t);
        assert t[0] in vias * holdings;
      } else {
        DecideSound(s[1..], t[1..], holdings, vias);
      }
    }
  }

  lemma DecideComplete(
    s: seq<nat>,
    t: seq<nat>,
    holdings: set<nat>,
    vias: set<nat>
  )
    requires R.SeqSet(s) <= vias
    requires R.SeqSet(t) <= holdings
    // Every intersection shared is still ahead on BOTH suffixes: the
    // consumed prefixes were probed negative, so no witness was skipped.
    requires forall shared: nat | shared in vias * holdings ::
      shared in R.SeqSet(s) && shared in R.SeqSet(t)
    requires ArmAnswer(vias, holdings)
    ensures Decide(s, t, holdings, vias)
    decreases |s| + |t|
  {
    var shared: nat :| shared in vias * holdings;
    assert shared in R.SeqSet(s);
    assert shared in R.SeqSet(t);
    assert |s| > 0 && |t| > 0;
    if s[0] !in holdings && t[0] !in vias {
      forall remaining: nat | remaining in vias * holdings
        ensures remaining in R.SeqSet(s[1..]) && remaining in R.SeqSet(t[1..])
      {
        // A shared element lies in holdings and in vias; the two consumed heads
        // do not, so neither head is a shared and every shared survives
        // into both tails.
        assert remaining != s[0] && remaining != t[0];
      }
      DecideComplete(s[1..], t[1..], holdings, vias);
    }
  }

  lemma DecideEqualsArmAnswer(
    s: seq<nat>,
    t: seq<nat>,
    vias: set<nat>,
    holdings: set<nat>
  )
    requires CompleteEnumeration(s, vias)
    requires CompleteEnumeration(t, holdings)
    ensures Decide(s, t, holdings, vias) <==> ArmAnswer(vias, holdings)
  {
    DecideSound(s, t, holdings, vias);
    if ArmAnswer(vias, holdings) {
      DecideComplete(s, t, holdings, vias);
    }
  }

  // ---------------------------------------------------------------------
  // The work bound: consumption is bounded by the SMALLER side
  // ---------------------------------------------------------------------

  function Min(left: nat, right: nat): nat {
    if left <= right then left else right
  }

  // Rounds the decision consumes; each round realizes at most one value
  // from each side and performs at most two exact-bound probes.
  function Rounds(
    s: seq<nat>,
    t: seq<nat>,
    holdings: set<nat>,
    vias: set<nat>
  ): nat
    decreases |s| + |t|
  {
    if |s| == 0 || |t| == 0 then
      0
    else if s[0] in holdings then
      1
    else if t[0] in vias then
      1
    else
      1 + Rounds(s[1..], t[1..], holdings, vias)
  }

  lemma RoundsBoundedByShorterSide(
    s: seq<nat>,
    t: seq<nat>,
    holdings: set<nat>,
    vias: set<nat>
  )
    // At most min(|s|, |t|) rounds, hence at most 2 * min(|s|, |t|)
    // probes and min(|s|, |t|) realized values per side: a huge fan-in on
    // one side costs only what the small side costs.
    ensures Rounds(s, t, holdings, vias) <= Min(|s|, |t|)
    decreases |s| + |t|
  {
    if |s| > 0 && |t| > 0 && s[0] !in holdings && t[0] !in vias {
      RoundsBoundedByShorterSide(s[1..], t[1..], holdings, vias);
    }
  }

  // ---------------------------------------------------------------------
  // The two-layer bridge into the membership-probe abstraction
  // ---------------------------------------------------------------------

  // Nodes whose successor lists carry the target: in the reverse program
  // abstraction these are exactly the intermediates the subject-side
  // (forward-index) enumeration lists.
  ghost function Predecessors(program: R.Program, target: nat): set<nat> {
    set node: nat |
      node in R.Nodes(program) &&
      target in R.SeqSet(R.Successors(program, node))
  }

  // A two-layer arm at `node` — some successor carries `target` directly —
  // is exactly a nonempty intersection of the node's successor set (the
  // resource side) with the target's predecessor set (the subject side),
  // so either enumeration decides it.
  lemma TwoLayerArmIsIntersection(
    program: R.Program,
    node: nat,
    target: nat
  )
    requires R.ValidProgram(program)
    ensures (exists intermediate: nat ::
               intermediate in R.SeqSet(R.Successors(program, node)) &&
               target in R.SeqSet(R.Successors(program, intermediate)))
            <==>
            R.SeqSet(R.Successors(program, node)) *
              Predecessors(program, target) != {}
  {
    if exists intermediate: nat ::
      intermediate in R.SeqSet(R.Successors(program, node)) &&
      target in R.SeqSet(R.Successors(program, intermediate))
    {
      var intermediate: nat :|
        intermediate in R.SeqSet(R.Successors(program, node)) &&
        target in R.SeqSet(R.Successors(program, intermediate));
      if node < |program.successors| {
        assert intermediate in R.Nodes(program);
      } else {
        assert R.Successors(program, node) == [];
        assert R.SeqSet(R.Successors(program, node)) == {};
        assert false;
      }
      assert intermediate in Predecessors(program, target);
      assert intermediate in
        R.SeqSet(R.Successors(program, node)) *
          Predecessors(program, target);
    }
  }
}

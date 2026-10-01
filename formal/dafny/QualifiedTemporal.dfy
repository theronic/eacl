include "QualifiedEvidence.dfy"

// Leaf qualification and decisive witness certificates over Kleene evidence.
// A complete total absorber decides its composition even when the other
// operand faults; the certificate then ends with that witness, and only after
// the witness expires can the masked fault surface.
module QualifiedTemporal {
  import opened E = QualifiedEvidence
  datatype Deadline = Forever | Until(at: int)
  datatype Qualifier = Qualifier(valid: bool, expiry: Deadline, caveat: Outcome)
  datatype Snapshot = Snapshot(forward: map<nat, nat>, reverse: map<nat, nat>, qualifiers: map<nat, Qualifier>, stamp: nat)
  datatype Evidence = Evidence(value: Outcome, end: Deadline, complete: bool)

  predicate Before(t: int, d: Deadline) { d.Forever? || t < d.at }

  function Meet(a: Deadline, b: Deadline): Deadline {
    if a.Forever? then b else if b.Forever? then a
    else Until(if a.at <= b.at then a.at else b.at)
  }

  // The later of two deadlines: the end of the time either still holds.
  function Later(a: Deadline, b: Deadline): Deadline {
    if a.Forever? || b.Forever? then Forever
    else Until(if a.at <= b.at then b.at else a.at)
  }

  function Qualify(universe: set<nat>, qid: nat, qs: map<nat, Qualifier>, t: int): Evidence {
    if qid == 0 then Evidence(Value(universe), Forever, true)
    else if qid !in qs || !qs[qid].valid then Evidence(Fault(universe, {0}), Forever, true)
    else if !Before(t, qs[qid].expiry) then Evidence(Value({}), Forever, true)
    else Evidence(qs[qid].caveat, qs[qid].expiry, true)
  }

  function Edge(universe: set<nat>, s: Snapshot, identity: nat, t: int): Evidence {
    if identity !in s.forward && identity !in s.reverse then Evidence(Value({}), Forever, true)
    else if identity !in s.forward || identity !in s.reverse || s.forward[identity] != s.reverse[identity]
    then Evidence(Fault(universe, {1}), Forever, true)
    else Qualify(universe, s.forward[identity], s.qualifiers, t)
  }

  // An operand decides only as a total absorber of its operator. U and
  // partially defined values never decide.
  predicate LeftDecides(universe: set<nat>, op: Operator, x: Outcome) {
    if op.Union? then AllTrue(universe, x) else AllFalse(universe, x)
  }

  predicate RightDecides(universe: set<nat>, op: Operator, x: Outcome) {
    if op.Union? || op.Exclusion? then AllTrue(universe, x) else AllFalse(universe, x)
  }

  // A complete decisive operand decides whatever the other operand is, a
  // faulting one included.
  function Need(universe: set<nat>, op: Operator, a: Evidence, b: Evidence): nat {
    if LeftDecides(universe, op, a.value) && a.complete then 1
    else if RightDecides(universe, op, b.value) && b.complete then 2
    else 3
  }

  // Both operands are complete and decisive.
  predicate BothDecide(universe: set<nat>, op: Operator, a: Evidence, b: Evidence) {
    LeftDecides(universe, op, a.value) && a.complete && RightDecides(universe, op, b.value) && b.complete
  }

  // The certificate: the later deadline when both operands decide (either
  // witness keeps the composition decided), the deciding operand's own when
  // one decides, and the earlier deadline when neither does.
  function Certificate(universe: set<nat>, op: Operator, a: Evidence, b: Evidence): Deadline {
    var needed := Need(universe, op, a, b);
    if BothDecide(universe, op, a, b) then Later(a.end, b.end)
    else if needed == 1 then a.end
    else if needed == 2 then b.end
    else Meet(a.end, b.end)
  }

  function Combine(universe: set<nat>, op: Operator, a: Evidence, b: Evidence): Evidence {
    var needed := Need(universe, op, a, b);
    Evidence(Compose(op, a.value, b.value),
             Certificate(universe, op, a, b),
             if needed == 1 then a.complete else if needed == 2 then b.complete else a.complete && b.complete)
  }

  lemma OnlyTotalAbsorbersDecide(universe: set<nat>, op: Operator, x: Outcome)
    requires universe != {}
    requires Classify(universe, x) == Failure || Classify(universe, x) == Conditional
    ensures !LeftDecides(universe, op, x) && !RightDecides(universe, op, x)
  {
    ClassificationIsExact(universe, x);
  }

  lemma DecisionIgnoresTheOtherOperand(universe: set<nat>, op: Operator, a: Evidence, b: Evidence, b2: Evidence)
    requires a.complete && LeftDecides(universe, op, a.value)
    ensures Need(universe, op, a, b) == 1 && Need(universe, op, a, b2) == 1
    ensures Combine(universe, op, a, b).complete
    ensures !(b.complete && RightDecides(universe, op, b.value)) ==> Combine(universe, op, a, b).end == a.end
  {}

  // When both operands are already decisive, the certificate is the later
  // deadline, so it does not depend on which operand an evaluator read
  // first.
  lemma BothDecisiveTakeTheLaterDeadline(universe: set<nat>, op: Operator, a: Evidence, b: Evidence)
    requires BothDecide(universe, op, a, b)
    ensures Combine(universe, op, a, b).end == Later(a.end, b.end)
    ensures Combine(universe, op, a, b).complete
  {}

  lemma LaterIsEither(t: int, a: Deadline, b: Deadline)
    ensures Before(t, Later(a, b)) <==> Before(t, a) || Before(t, b)
  {}

  // For a union and an intersection the operands' roles are symmetric, so
  // when both decide the composition's certificate is the same either way
  // round.
  lemma SymmetricCertificateWhenBothDecide(universe: set<nat>, op: Operator, a: Evidence, b: Evidence)
    requires op.Union? || op.Intersection?
    requires BothDecide(universe, op, a, b)
    ensures BothDecide(universe, op, b, a)
    ensures Combine(universe, op, a, b).end == Combine(universe, op, b, a).end
  {}

  // A positive worklist accumulates derivations. Replacing an established
  // witness with the latest cyclic derivation can oscillate between deadlines
  // even after every node's Boolean membership has stabilized.
  lemma AccumulationRetainsGroundedGrant(universe: set<nat>, prior: Evidence, derived: Evidence)
    requires prior.value == Value(universe) && prior.complete
    requires Within(universe, derived.value)
    ensures Combine(universe, Union, prior, derived).value == prior.value
    ensures Combine(universe, Union, prior, derived).complete
    ensures !(derived.complete && RightDecides(universe, Union, derived.value)) ==>
              Combine(universe, Union, prior, derived) == prior
    ensures derived.complete && RightDecides(universe, Union, derived.value) ==>
              Combine(universe, Union, prior, derived).end == Later(prior.end, derived.end)
  {
    assert LeftDecides(universe, Union, prior.value);
    TotalGrantAbsorbs(universe, derived.value);
  }

  // Union accumulation never loses a T world and never lowers a world's level.
  lemma AccumulationPreservesPositiveFacts(universe: set<nat>, prior: Evidence, derived: Evidence)
    ensures Worlds(prior.value) <= Worlds(Combine(universe, Union, prior, derived).value)
    ensures Worlds(derived.value) <= Worlds(Combine(universe, Union, prior, derived).value)
    ensures Below(prior.value, Combine(universe, Union, prior, derived).value)
    ensures Below(derived.value, Combine(universe, Union, prior, derived).value)
  {
    var c := Combine(universe, Union, prior, derived).value;
    forall w
      ensures Level(At(prior.value, w)) <= Level(At(c, w)) && Level(At(derived.value, w)) <= Level(At(c, w))
      ensures At(prior.value, w).T? || At(derived.value, w).T? ==> At(c, w).T?
    {
      Pointwise(Union, prior.value, derived.value, w);
      KleeneIsMaxMinOnLevels(At(prior.value, w), At(derived.value, w));
    }
    TrueWorlds(prior.value);
    TrueWorlds(derived.value);
    TrueWorlds(c);
  }

  // A union accumulator keeps a world's U, with its reasons, unless that world
  // becomes T.
  lemma AccumulationRetainsFaults(universe: set<nat>, prior: Evidence, derived: Evidence, w: nat)
    requires At(prior.value, w).U? || At(derived.value, w).U?
    ensures At(Combine(universe, Union, prior, derived).value, w) ==
            if At(prior.value, w).T? || At(derived.value, w).T? then T
            else U(Reasons(At(prior.value, w)) + Reasons(At(derived.value, w)))
  {
    Pointwise(Union, prior.value, derived.value, w);
  }

  lemma MeetIsIntersection(t: int, a: Deadline, b: Deadline)
    ensures Before(t, Meet(a, b)) <==> Before(t, a) && Before(t, b)
  {}

  lemma ExclusiveBoundary(universe: set<nat>, qid: nat, qs: map<nat, Qualifier>)
    requires qid != 0 && qid in qs && qs[qid].valid && qs[qid].expiry.Until?
    ensures Qualify(universe, qid, qs, qs[qid].expiry.at).value == Value({})
    ensures Qualify(universe, qid, qs, qs[qid].expiry.at - 1).value == qs[qid].caveat
  {}

  lemma NilHasNoQualifierDependency(universe: set<nat>, a: map<nat, Qualifier>, b: map<nat, Qualifier>, t: int, later: int)
    ensures Qualify(universe, 0, a, t) == Qualify(universe, 0, b, later)
    ensures Qualify(universe, 0, a, t).value == Value(universe)
  {}

  lemma NonNilInvalidIsAuthoritative(universe: set<nat>, qid: nat, qs: map<nat, Qualifier>, t: int)
    requires qid != 0 && (qid !in qs || !qs[qid].valid)
    ensures Qualify(universe, qid, qs, t) == Evidence(Fault(universe, {0}), Forever, true)
    ensures universe != {} ==> Classify(universe, Qualify(universe, qid, qs, t).value) == Failure
  {
    if universe != {} {
      var w :| w in universe;
      assert At(Fault(universe, {0}), w).U?;
    }
  }

  lemma ExpirySuppressesCaveat(universe: set<nat>, qid: nat, qs: map<nat, Qualifier>, t: int, other: Outcome)
    requires qid != 0 && qid in qs && qs[qid].valid && !Before(t, qs[qid].expiry)
    ensures Qualify(universe, qid, qs, t) == Qualify(universe, qid, qs[qid := Qualifier(true, qs[qid].expiry, other)], t)
  {}

  lemma QualifierCertificateIsSound(universe: set<nat>, qid: nat, qs: map<nat, Qualifier>, start: int, later: int)
    requires start <= later && Before(later, Qualify(universe, qid, qs, start).end)
    ensures Qualify(universe, qid, qs, later).value == Qualify(universe, qid, qs, start).value
  {}

  // At a leaf, time can remove a fault by expiring its edge but never adds one.
  lemma ExpiryCannotCreateFaults(universe: set<nat>, qid: nat, qs: map<nat, Qualifier>, start: int, later: int)
    requires start <= later
    ensures NoNewFaults(Qualify(universe, qid, qs, start).value, Qualify(universe, qid, qs, later).value)
  {}

  lemma EdgeExpiryCannotCreateFaults(universe: set<nat>, s: Snapshot, identity: nat, start: int, later: int)
    requires start <= later
    ensures NoNewFaults(Edge(universe, s, identity, start).value, Edge(universe, s, identity, later).value)
  {
    if identity in s.forward && identity in s.reverse && s.forward[identity] == s.reverse[identity] {
      ExpiryCannotCreateFaults(universe, s.forward[identity], s.qualifiers, start, later);
    }
  }

  lemma ExpiryCanRemoveAnEvaluatorFault(universe: set<nat>, d: int)
    requires universe != {}
    ensures var qs := map[1 := Qualifier(true, Until(d), Fault(universe, {5}))];
            Classify(universe, Qualify(universe, 1, qs, d - 1).value) == Failure &&
            Qualify(universe, 1, qs, d).value == Value({})
  {
    var w :| w in universe;
    assert At(Fault(universe, {5}), w).U?;
  }

  lemma PreparationHasNoDenotation(universe: set<nat>, s: Snapshot, qid: nat, q: Qualifier, identity: nat, t: int)
    requires qid != 0 && qid !in s.qualifiers
    requires qid !in s.forward.Values && qid !in s.reverse.Values
    ensures Edge(universe, s, identity, t) == Edge(universe,
                                                   Snapshot(s.forward, s.reverse, s.qualifiers[qid := q], s.stamp + 1), identity, t)
  {}

  function Publish(s: Snapshot, identity: nat, qid: nat): Snapshot {
    Snapshot(s.forward[identity := qid], s.reverse[identity := qid], s.qualifiers, s.stamp + 1)
  }

  lemma PublicationIsOneTemporalCommit(universe: set<nat>, s: Snapshot, identity: nat, qid: nat, t: int)
    ensures Publish(s, identity, qid).stamp > s.stamp
    ensures Publish(s, identity, qid).forward[identity] == Publish(s, identity, qid).reverse[identity] == qid
    ensures Edge(universe, Publish(s, identity, qid), identity, t) == Qualify(universe, qid, s.qualifiers, t)
  {}

  // A complete decisive witness that is still valid at `later` fixes the
  // composition, whatever the other operand becomes (including a fault).
  lemma WitnessCertificateIsSound(universe: set<nat>, op: Operator, a: Evidence, b: Evidence, laterA: Outcome, laterB: Outcome, later: int)
    requires Within(universe, a.value) && Within(universe, b.value)
    requires Within(universe, laterA) && Within(universe, laterB)
    requires a.complete && Before(later, a.end) ==> laterA == a.value
    requires b.complete && Before(later, b.end) ==> laterB == b.value
    requires Combine(universe, op, a, b).complete && Before(later, Combine(universe, op, a, b).end)
    ensures Compose(op, laterA, laterB) == Combine(universe, op, a, b).value
  {
    var needed := Need(universe, op, a, b);
    // When both decide, at least one witness is still valid at `later`.
    var aHolds := needed == 1 && (!BothDecide(universe, op, a, b) || Before(later, a.end));
    if BothDecide(universe, op, a, b) {
      LaterIsEither(later, a.end, b.end);
    }
    if aHolds {
      forall w | w in universe
        ensures Connect(op, At(laterA, w), At(laterB, w)) == Connect(op, At(a.value, w), At(b.value, w))
      {
        DefiniteAbsorbers(At(laterB, w));
        DefiniteAbsorbers(At(b.value, w));
      }
      ComposeAgreesOnTheUniverse(universe, op, laterA, laterB, a.value, b.value);
    } else if needed == 1 || needed == 2 {
      forall w | w in universe
        ensures Connect(op, At(laterA, w), At(laterB, w)) == Connect(op, At(a.value, w), At(b.value, w))
      {
        DefiniteAbsorbers(At(laterA, w));
        DefiniteAbsorbers(At(a.value, w));
      }
      ComposeAgreesOnTheUniverse(universe, op, laterA, laterB, a.value, b.value);
    } else {
      MeetIsIntersection(later, a.end, b.end);
    }
  }

  // A composed value can gain a fault later, but only at a time its certificate
  // no longer admits.
  lemma FaultSurfacesOnlyAfterTheCertificate(universe: set<nat>, op: Operator, a: Evidence, b: Evidence, laterA: Outcome, laterB: Outcome, later: int)
    requires Within(universe, a.value) && Within(universe, b.value)
    requires Within(universe, laterA) && Within(universe, laterB)
    requires a.complete && Before(later, a.end) ==> laterA == a.value
    requires b.complete && Before(later, b.end) ==> laterB == b.value
    requires !Faulted(universe, Combine(universe, op, a, b).value) && Faulted(universe, Compose(op, laterA, laterB))
    ensures !(Combine(universe, op, a, b).complete && Before(later, Combine(universe, op, a, b).end))
  {
    if Combine(universe, op, a, b).complete && Before(later, Combine(universe, op, a, b).end) {
      WitnessCertificateIsSound(universe, op, a, b, laterA, laterB, later);
    }
  }

  // Publication excludes any outcome with a U world.
  predicate Publishable(e: Evidence, start: int) { e.complete && Before(start, e.end) && Definite(e.value) }

  lemma IncompleteEvidenceCannotPublish(e: Evidence, start: int)
    requires !e.complete
    ensures !Publishable(e, start)
  {}

  lemma FaultedEvidenceCannotPublish(universe: set<nat>, e: Evidence, start: int)
    requires Faulted(universe, e.value)
    ensures !Publishable(e, start)
  {
    if Definite(e.value) {
      DefiniteOutcomesAreDecided(universe, e.value);
    }
  }

  lemma PublishedEvidenceIsDecided(universe: set<nat>, e: Evidence, start: int)
    requires Publishable(e, start)
    ensures Classify(universe, e.value) != Failure && Errors(universe, e.value) == {}
  {
    DefiniteOutcomesAreDecided(universe, e.value);
  }

  lemma ExpiredBanCanGrant(universe: set<nat>, deadline: int)
    requires universe != {}
    ensures Classify(universe, Compose(Exclusion, Value(universe),
                                       Qualify(universe, 1, map[1 := Qualifier(true, Until(deadline), Value(universe))], deadline - 1).value)) == No
    ensures Classify(universe, Compose(Exclusion, Value(universe),
                                       Qualify(universe, 1, map[1 := Qualifier(true, Until(deadline), Value(universe))], deadline).value)) == Has
  {
    PointwiseBooleanAlgebra(Exclusion, universe, universe);
    PointwiseBooleanAlgebra(Exclusion, universe, {});
    assert universe - universe == {};
    assert universe - {} == universe;
    TotalValuesClassify(universe);
  }

  function AcceptClock(prior: int, sample: int): int { if prior <= sample then sample else prior }

  lemma ClockCannotRevive(prior: int, sample: int, expiry: int)
    requires expiry <= prior
    ensures prior <= AcceptClock(prior, sample)
    ensures !(AcceptClock(prior, sample) < expiry)
  {}

  datatype Tree = Tip(id: nat) | Join(op: Operator, left: Tree, right: Tree)

  function Lookup(universe: set<nat>, leaves: map<nat, Evidence>, id: nat): Evidence {
    if id in leaves then leaves[id] else Evidence(Fault(universe, {0}), Forever, true)
  }

  function TreeEvidence(universe: set<nat>, tree: Tree, leaves: map<nat, Evidence>): Evidence {
    match tree
    case Tip(id) => Lookup(universe, leaves, id)
    case Join(op, a, b) => Combine(universe, op, TreeEvidence(universe, a, leaves), TreeEvidence(universe, b, leaves))
  }

  // Only leaf certificates are needed, with no hypothesis on how faults change:
  // each combined certificate rests either on a decisive operand, which absorbs
  // whatever the other operand becomes, or on both operands' certificates.
  lemma ArbitraryEvidenceTreeCertificate(universe: set<nat>, tree: Tree, leaves: map<nat, Evidence>, laterLeaves: map<nat, Evidence>, later: int)
    requires leaves.Keys == laterLeaves.Keys
    requires forall id | id in leaves :: Within(universe, leaves[id].value) && Within(universe, laterLeaves[id].value)
    requires forall id | id in leaves :: leaves[id].complete && Before(later, leaves[id].end) ==> leaves[id].value == laterLeaves[id].value
    ensures Within(universe, TreeEvidence(universe, tree, leaves).value)
    ensures Within(universe, TreeEvidence(universe, tree, laterLeaves).value)
    ensures TreeEvidence(universe, tree, leaves).complete && Before(later, TreeEvidence(universe, tree, leaves).end) ==>
              TreeEvidence(universe, tree, leaves).value == TreeEvidence(universe, tree, laterLeaves).value
  {
    match tree
    case Tip(id) =>
    case Join(op, a, b) =>
      ArbitraryEvidenceTreeCertificate(universe, a, leaves, laterLeaves, later);
      ArbitraryEvidenceTreeCertificate(universe, b, leaves, laterLeaves, later);
      var left := TreeEvidence(universe, a, leaves);
      var right := TreeEvidence(universe, b, leaves);
      var laterLeft := TreeEvidence(universe, a, laterLeaves).value;
      var laterRight := TreeEvidence(universe, b, laterLeaves).value;
      ComposeIsWithin(universe, op, left.value, right.value);
      ComposeIsWithin(universe, op, laterLeft, laterRight);
      if Combine(universe, op, left, right).complete && Before(later, Combine(universe, op, left, right).end) {
        WitnessCertificateIsSound(universe, op, left, right, laterLeft, laterRight, later);
      }
  }

  // N-ary unions and intersections in any evaluation order. An evaluator
  // combines the operands left to right from the operator's identity and
  // stops once the accumulator is a complete total absorber; the operands it
  // skips are never read. These proofs recurse explicitly: automatic lemma
  // induction is off, so no step rests on an induction hypothesis the solver
  // chose.
  function Identity(universe: set<nat>, op: Operator): Evidence {
    if op.Union? then Evidence(Value({}), Forever, true) else Evidence(Value(universe), Forever, true)
  }

  function Fold(universe: set<nat>, op: Operator, s: seq<Outcome>): Outcome {
    if op.Union? then FoldUnion(s) else FoldIntersection(universe, s)
  }

  function Values(s: seq<Evidence>): seq<Outcome> {
    seq(|s|, i requires 0 <= i < |s| => s[i].value)
  }

  function Accumulate(universe: set<nat>, op: Operator, acc: Evidence, s: seq<Evidence>): Evidence
    decreases |s|
  {
    if |s| == 0 || (acc.complete && LeftDecides(universe, op, acc.value)) then acc
    else Accumulate(universe, op, Combine(universe, op, acc, s[0]), s[1..])
  }

  lemma {:induction false} FoldIsWithin(universe: set<nat>, op: Operator, s: seq<Outcome>)
    requires op.Union? || op.Intersection?
    requires forall x | x in s :: Within(universe, x)
    ensures Within(universe, Fold(universe, op, s))
    decreases |s|
  {
    if |s| > 0 {
      var p := s[..|s| - 1];
      assert forall x | x in p :: x in s;
      FoldIsWithin(universe, op, p);
      ComposeIsWithin(universe, op, Fold(universe, op, p), s[|s| - 1]);
    } else if op.Intersection? {
      assert Within(universe, Value(universe));
    }
  }

  lemma {:induction false} FoldMayStop(universe: set<nat>, op: Operator, s: seq<Outcome>, k: nat)
    requires op.Union? || op.Intersection?
    requires k <= |s|
    requires forall x | x in s :: Within(universe, x)
    requires LeftDecides(universe, op, Fold(universe, op, s[..k]))
    ensures Fold(universe, op, s) == Fold(universe, op, s[..k])
  {
    if op.Union? {
      UnionMayStopAtATotalGrant(universe, s, k);
    } else {
      IntersectionMayStopAtATotalDenial(universe, s, k);
    }
  }

  // The invariant of one evaluation: after `k` operands the accumulator is
  // their composition, and its certificate, when complete, fixes the
  // composition of their later values.
  lemma {:induction false} AccumulationFromPrefix(universe: set<nat>, op: Operator, s: seq<Evidence>, later: seq<Outcome>, t: int, k: nat, acc: Evidence)
    requires op.Union? || op.Intersection?
    requires k <= |s| && |later| == |s|
    requires forall i | 0 <= i < |s| :: Within(universe, s[i].value) && Within(universe, later[i])
    requires forall i | 0 <= i < |s| :: s[i].complete && Before(t, s[i].end) ==> later[i] == s[i].value
    requires acc.value == Fold(universe, op, Values(s)[..k])
    requires acc.complete && Before(t, acc.end) ==> Fold(universe, op, later[..k]) == acc.value
    ensures Accumulate(universe, op, acc, s[k..]).value == Fold(universe, op, Values(s))
    ensures var r := Accumulate(universe, op, acc, s[k..]);
            r.complete && Before(t, r.end) ==> Fold(universe, op, later) == r.value
    decreases |s| - k
  {
    var values := Values(s);
    assert forall x | x in values :: Within(universe, x);
    assert forall x | x in later :: Within(universe, x);
    if k == |s| {
      assert s[k..] == [];
      assert values[..k] == values && later[..k] == later;
    } else if acc.complete && LeftDecides(universe, op, acc.value) {
      FoldMayStop(universe, op, values, k);
      if Before(t, acc.end) {
        FoldMayStop(universe, op, later, k);
      }
    } else {
      var next := Combine(universe, op, acc, s[k]);
      assert s[k..][0] == s[k] && s[k..][1..] == s[k + 1..];
      assert values[..k + 1][..k] == values[..k] && values[..k + 1][k] == s[k].value;
      assert later[..k + 1][..k] == later[..k];
      assert forall x | x in values[..k] :: x in values;
      assert forall x | x in later[..k] :: x in later;
      FoldIsWithin(universe, op, values[..k]);
      FoldIsWithin(universe, op, later[..k]);
      if next.complete && Before(t, next.end) {
        WitnessCertificateIsSound(universe, op, acc, s[k], Fold(universe, op, later[..k]), later[k], t);
      }
      AccumulationFromPrefix(universe, op, s, later, t, k + 1, next);
    }
  }

  // Whatever the order, the evaluation yields the composition of every
  // operand (QualifiedEvidence.FoldsIgnoreOrder: the composition itself
  // ignores the order), and its certificate is sound: the first decisive
  // operand's deadline when one decides, else the earliest deadline read.
  lemma {:induction false} OrderedShortCircuitIsSound(universe: set<nat>, op: Operator, s: seq<Evidence>, later: seq<Outcome>, t: int)
    requires op.Union? || op.Intersection?
    requires |later| == |s|
    requires forall i | 0 <= i < |s| :: Within(universe, s[i].value) && Within(universe, later[i])
    requires forall i | 0 <= i < |s| :: s[i].complete && Before(t, s[i].end) ==> later[i] == s[i].value
    ensures Accumulate(universe, op, Identity(universe, op), s).value == Fold(universe, op, Values(s))
    ensures var r := Accumulate(universe, op, Identity(universe, op), s);
            r.complete && Before(t, r.end) ==> Fold(universe, op, later) == r.value
  {
    assert Values(s)[..0] == [] && later[..0] == [] && s[0..] == s;
    AccumulationFromPrefix(universe, op, s, later, t, 0, Identity(universe, op));
  }

  // The value ignores the order; the certificate does not. Two total grants
  // with different deadlines certify a union until whichever an evaluator
  // reads first: both deadlines are sound.
  lemma {:induction false} CertificateFollowsTheOrder(universe: set<nat>, d: int, e: int)
    requires universe != {} && d < e
    ensures var a, b := Evidence(Value(universe), Until(d), true), Evidence(Value(universe), Until(e), true);
            Accumulate(universe, Union, Identity(universe, Union), [a, b]).end == Until(d) &&
            Accumulate(universe, Union, Identity(universe, Union), [b, a]).end == Until(e) &&
            Accumulate(universe, Union, Identity(universe, Union), [a, b]).value ==
            Accumulate(universe, Union, Identity(universe, Union), [b, a]).value
  {
    var a, b := Evidence(Value(universe), Until(d), true), Evidence(Value(universe), Until(e), true);
    var w :| w in universe;
    assert !AllTrue(universe, Value({})) by {
      assert At(Value({}), w).F?;
    }
    TotalGrantAbsorbs(universe, Value({}));
    var first := Combine(universe, Union, Identity(universe, Union), a);
    assert first.value == Value(universe) && first.end == Until(d) && first.complete;
    assert [a, b][1..] == [b];
    assert Accumulate(universe, Union, first, [b]) == first;
    var other := Combine(universe, Union, Identity(universe, Union), b);
    assert other.value == Value(universe) && other.end == Until(e) && other.complete;
    assert [b, a][1..] == [a];
    assert Accumulate(universe, Union, other, [a]) == other;
  }

  // A complete T leaf valid until `d` masks a faulting operand of a union: the
  // combined answer is Has and certified exactly until `d`. Once the witness
  // has expired to F, the same composition is a Failure.
  lemma MaskedFaultSurfacesOnlyAfterItsWitnessExpires(universe: set<nat>, d: int, t: int)
    requires universe != {}
    ensures var qs := map[1 := Qualifier(true, Until(d), Value(universe))];
            var c := Combine(universe, Union, Qualify(universe, 1, qs, t), Qualify(universe, 2, qs, t));
            (t < d ==> c.complete && c.end == Until(d) && Classify(universe, c.value) == Has) &&
            (d <= t ==> c.complete && c.end == Forever && Classify(universe, c.value) == Failure && Errors(universe, c.value) == {0})
  {
    var qs := map[1 := Qualifier(true, Until(d), Value(universe))];
    var grant, fault := Qualify(universe, 1, qs, t), Qualify(universe, 2, qs, t);
    var c := Combine(universe, Union, grant, fault);
    var w :| w in universe;
    assert fault == Evidence(Fault(universe, {0}), Forever, true);
    assert !AllTrue(universe, fault.value) by {
      assert At(fault.value, w).U?;
    }
    if t < d {
      assert grant == Evidence(Value(universe), Until(d), true);
      TotalGrantAbsorbs(universe, fault.value);
      TotalValuesClassify(universe);
    } else {
      assert grant == Evidence(Value({}), Forever, true);
      assert !AllTrue(universe, grant.value) by {
        assert At(grant.value, w).F?;
      }
      forall v | v in universe
        ensures At(c.value, v) == U({0})
      {
        Pointwise(Union, grant.value, fault.value, v);
        assert {} + {0} == {0};
      }
      assert At(c.value, w).U?;
      forall r | r in Errors(universe, c.value)
        ensures r == 0
      {
      }
      assert 0 in Errors(universe, c.value) by {
        assert w in universe && At(c.value, w).U? && 0 in At(c.value, w).reasons;
      }
    }
  }
}

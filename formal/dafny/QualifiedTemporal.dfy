include "QualifiedEvidence.dfy"

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

  function Qualify(universe: set<nat>, qid: nat, qs: map<nat, Qualifier>, t: int): Evidence {
    if qid == 0 then Evidence(Value(universe), Forever, true)
    else if qid !in qs || !qs[qid].valid then Evidence(Fault({0}), Forever, true)
    else if !Before(t, qs[qid].expiry) then Evidence(Value({}), Forever, true)
    else Evidence(qs[qid].caveat, qs[qid].expiry, true)
  }

  function Edge(universe: set<nat>, s: Snapshot, identity: nat, t: int): Evidence {
    if identity !in s.forward && identity !in s.reverse then Evidence(Value({}), Forever, true)
    else if identity !in s.forward || identity !in s.reverse || s.forward[identity] != s.reverse[identity]
    then Evidence(Fault({1}), Forever, true)
    else Qualify(universe, s.forward[identity], s.qualifiers, t)
  }

  predicate LeftDecides(universe: set<nat>, op: Operator, x: Outcome) {
    x.Value? && (if op.Union? then x.worlds == universe else x.worlds == {})
  }

  predicate RightDecides(universe: set<nat>, op: Operator, x: Outcome) {
    x.Value? && (if op.Union? || op.Exclusion? then x.worlds == universe else x.worlds == {})
  }

  function Need(universe: set<nat>, op: Operator, a: Evidence, b: Evidence): nat {
    if a.value.Fault? || b.value.Fault? then 3
    else if LeftDecides(universe, op, a.value) && a.complete then 1
    else if RightDecides(universe, op, b.value) && b.complete then 2
    else 3
  }

  function Combine(universe: set<nat>, op: Operator, a: Evidence, b: Evidence): Evidence {
    var needed := Need(universe, op, a, b);
    Evidence(Compose(op, a.value, b.value),
             if needed == 1 then a.end else if needed == 2 then b.end else Meet(a.end, b.end),
             if needed == 1 then a.complete else if needed == 2 then b.complete else a.complete && b.complete)
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
    ensures Qualify(universe, qid, qs, t).value.Fault?
  {}

  lemma ExpirySuppressesCaveat(universe: set<nat>, qid: nat, qs: map<nat, Qualifier>, t: int, other: Outcome)
    requires qid != 0 && qid in qs && qs[qid].valid && !Before(t, qs[qid].expiry)
    ensures Qualify(universe, qid, qs, t) == Qualify(universe, qid, qs[qid := Qualifier(true, qs[qid].expiry, other)], t)
  {}

  lemma QualifierCertificateIsSound(universe: set<nat>, qid: nat, qs: map<nat, Qualifier>, start: int, later: int)
    requires start <= later && Before(later, Qualify(universe, qid, qs, start).end)
    ensures Qualify(universe, qid, qs, later).value == Qualify(universe, qid, qs, start).value
  {}

  lemma ExpiryCannotCreateFaults(universe: set<nat>, qid: nat, qs: map<nat, Qualifier>, start: int, later: int)
    requires start <= later
    ensures NoNewFaults(Qualify(universe, qid, qs, start).value, Qualify(universe, qid, qs, later).value)
  {}

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

  lemma WitnessCertificateIsSound(universe: set<nat>, op: Operator, a: Evidence, b: Evidence, laterA: Outcome, laterB: Outcome, later: int)
    requires a.value.Value? ==> a.value.worlds <= universe
    requires b.value.Value? ==> b.value.worlds <= universe
    requires laterA.Value? ==> laterA.worlds <= universe
    requires laterB.Value? ==> laterB.worlds <= universe
    requires NoNewFaults(a.value, laterA) && NoNewFaults(b.value, laterB)
    requires a.complete && Before(later, a.end) ==> laterA == a.value
    requires b.complete && Before(later, b.end) ==> laterB == b.value
    requires Combine(universe, op, a, b).complete && Before(later, Combine(universe, op, a, b).end)
    ensures Compose(op, laterA, laterB) == Combine(universe, op, a, b).value
  {
    MeetIsIntersection(later, a.end, b.end);
  }

  predicate Publishable(e: Evidence, start: int) { e.complete && Before(start, e.end) && !e.value.Fault? }

  lemma IncompleteEvidenceCannotPublish(e: Evidence, start: int)
    requires !e.complete
    ensures !Publishable(e, start)
  {}

  lemma ExpiredBanCanGrant(universe: set<nat>, deadline: int)
    requires universe != {}
    ensures Classify(universe, Compose(Exclusion, Value(universe),
                                       Qualify(universe, 1, map[1 := Qualifier(true, Until(deadline), Value(universe))], deadline - 1).value)) == No
    ensures Classify(universe, Compose(Exclusion, Value(universe),
                                       Qualify(universe, 1, map[1 := Qualifier(true, Until(deadline), Value(universe))], deadline).value)) == Has
  {}

  function AcceptClock(prior: int, sample: int): int { if prior <= sample then sample else prior }

  lemma ClockCannotRevive(prior: int, sample: int, expiry: int)
    requires expiry <= prior
    ensures prior <= AcceptClock(prior, sample)
    ensures !(AcceptClock(prior, sample) < expiry)
  {}

  datatype Tree = Tip(id: nat) | Join(op: Operator, left: Tree, right: Tree)

  function Lookup(leaves: map<nat, Evidence>, id: nat): Evidence {
    if id in leaves then leaves[id] else Evidence(Fault({0}), Forever, true)
  }

  function TreeEvidence(universe: set<nat>, tree: Tree, leaves: map<nat, Evidence>): Evidence {
    match tree
    case Tip(id) => Lookup(leaves, id)
    case Join(op, a, b) => Combine(universe, op, TreeEvidence(universe, a, leaves), TreeEvidence(universe, b, leaves))
  }

  predicate Within(universe: set<nat>, x: Outcome) { x.Value? ==> x.worlds <= universe }

  lemma ArbitraryEvidenceTreeCertificate(universe: set<nat>, tree: Tree, leaves: map<nat, Evidence>, laterLeaves: map<nat, Evidence>, later: int)
    requires leaves.Keys == laterLeaves.Keys
    requires forall id | id in leaves :: Within(universe, leaves[id].value) && Within(universe, laterLeaves[id].value)
    requires forall id | id in leaves :: NoNewFaults(leaves[id].value, laterLeaves[id].value)
    requires forall id | id in leaves :: leaves[id].complete && Before(later, leaves[id].end) ==> leaves[id].value == laterLeaves[id].value
    ensures Within(universe, TreeEvidence(universe, tree, leaves).value)
    ensures Within(universe, TreeEvidence(universe, tree, laterLeaves).value)
    ensures NoNewFaults(TreeEvidence(universe, tree, leaves).value, TreeEvidence(universe, tree, laterLeaves).value)
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
      FaultsCannotAppearByComposition(op, left.value, right.value, laterLeft, laterRight);
      if Combine(universe, op, left, right).complete && Before(later, Combine(universe, op, left, right).end) {
        WitnessCertificateIsSound(universe, op, left, right, laterLeft, laterRight, later);
      }
  }
}

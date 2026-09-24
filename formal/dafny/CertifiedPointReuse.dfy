include "QualifiedTemporal.dfy"

// Cross-request reuse of certified point decisions in a client's subproblem
// store. A decision computed at `start` on one exact basis is stored with its
// evidence's certificate and keyed without its evaluation time. A later request
// on the same basis and qualification scope reuses it exactly when the
// certificate admits the request's time (`temporal/reusable?`).
//
// A point decision is modelled as the evidence of a finite derivation tree
// over qualified edges. LeveledMembership.dfy and GuardedMembership.dfy prove
// that the recursive searches' certificates are those of such a derivation.
module CertifiedPointReuse {
  import opened E = QualifiedEvidence
  import opened T = QualifiedTemporal

  // The collision-checked qualification scope of a certified key: the exact
  // reuse identity without its evaluation time, which the certificate
  // replaces, and without its basis, which the storage key carries as its
  // exact reuse identity.
  datatype Scope = Scope(format: nat, context: seq<nat>, evaluator: seq<nat>)
  datatype Request = Request(basis: nat, time: int, scope: Scope)
  datatype Key = Key(basis: nat, point: nat, scope: Scope)
  datatype Entry = Entry(start: int, evidence: Evidence, kind: Kind)

  function KeyOf(request: Request, point: nat): Key {
    Key(request.basis, point, request.scope)
  }

  // temporal/point-answer
  function Stored(universe: set<nat>, start: int, e: Evidence): Entry {
    Entry(start, e, Classify(universe, e.value))
  }

  // temporal/reusable? on the entry's own exact basis
  predicate Reusable(entry: Entry, time: int) {
    entry.start <= time && Before(time, entry.evidence.end) &&
    (entry.evidence.complete || time == entry.start)
  }

  // temporal/supersedes?
  predicate Supersedes(prior: Entry, next: Entry) {
    prior.start < next.start && !Reusable(prior, next.start)
  }

  // temporal/point-answer-valid? after decoding a restored entry's evidence
  predicate Admitted(universe: set<nat>, entry: Entry) {
    !entry.evidence.value.Fault? && Before(entry.start, entry.evidence.end) &&
    entry.kind == Classify(universe, entry.evidence.value)
  }

  function Leaves(universe: set<nat>, qids: map<nat, nat>, qs: map<nat, Qualifier>, t: int): map<nat, Evidence> {
    map id | id in qids :: Qualify(universe, qids[id], qs, t)
  }

  lemma KeysIgnoreOnlyTheTime(a: Request, b: Request, point: nat)
    ensures KeyOf(a, point) == KeyOf(b, point) <==> a.basis == b.basis && a.scope == b.scope
  {}

  lemma IncompleteCertificateIsReusedOnlyAtItsTime(entry: Entry, time: int)
    requires !entry.evidence.complete && Reusable(entry, time)
    ensures time == entry.start
  {}

  lemma NoReuseAtOrAfterTheEnd(entry: Entry, time: int)
    requires entry.evidence.end.Until? && entry.evidence.end.at <= time
    ensures !Reusable(entry, time)
  {}

  lemma NoReuseBeforeTheComputation(entry: Entry, time: int)
    requires time < entry.start
    ensures !Reusable(entry, time)
  {}

  lemma QualifiedLeafIsWithin(universe: set<nat>, qid: nat, qs: map<nat, Qualifier>, t: int)
    requires forall q | q in qs :: Within(universe, qs[q].caveat)
    ensures Within(universe, Qualify(universe, qid, qs, t).value)
  {}

  // A reused decision is the decision a fresh evaluation makes at the later
  // time, on the same basis and scope.
  lemma ReusedDecisionIsTheFreshDecision(
    universe: set<nat>, tree: Tree, qids: map<nat, nat>, qs: map<nat, Qualifier>, start: int, later: int)
    requires forall q | q in qs :: Within(universe, qs[q].caveat)
    requires Reusable(Stored(universe, start, TreeEvidence(universe, tree, Leaves(universe, qids, qs, start))), later)
    ensures TreeEvidence(universe, tree, Leaves(universe, qids, qs, start)).value ==
            TreeEvidence(universe, tree, Leaves(universe, qids, qs, later)).value
    ensures Stored(universe, start, TreeEvidence(universe, tree, Leaves(universe, qids, qs, start))).kind ==
            Classify(universe, TreeEvidence(universe, tree, Leaves(universe, qids, qs, later)).value)
  {
    var leaves := Leaves(universe, qids, qs, start);
    var laterLeaves := Leaves(universe, qids, qs, later);
    if later != start {
      forall id | id in leaves
        ensures Within(universe, leaves[id].value) && Within(universe, laterLeaves[id].value)
        ensures NoNewFaults(leaves[id].value, laterLeaves[id].value)
        ensures leaves[id].complete && Before(later, leaves[id].end) ==> leaves[id].value == laterLeaves[id].value
      {
        QualifiedLeafIsWithin(universe, qids[id], qs, start);
        QualifiedLeafIsWithin(universe, qids[id], qs, later);
        ExpiryCannotCreateFaults(universe, qids[id], qs, start, later);
        if Before(later, leaves[id].end) {
          QualifierCertificateIsSound(universe, qids[id], qs, start, later);
        }
      }
      ArbitraryEvidenceTreeCertificate(universe, tree, leaves, laterLeaves, later);
    }
  }

  // Supersession moves forward in time and replaces only an entry that no
  // request at or after the replacement's time could reuse.
  lemma ReplacedEntryHasNoLaterReuse(prior: Entry, next: Entry, time: int)
    requires Supersedes(prior, next) && next.start <= time
    ensures !Reusable(prior, time)
  {}

  lemma SupersessionNeverRegresses(prior: Entry, next: Entry)
    requires Supersedes(prior, next)
    ensures !Supersedes(next, prior)
  {}

  // Reusing a decision observes its certificate on the request, so the
  // request's answer is certified no longer than the reused decision.
  lemma ObservedCertificateBoundsTheAnswer(request: Deadline, reused: Deadline, time: int)
    ensures Before(time, Meet(request, reused)) ==> Before(time, reused)
  {
    MeetIsIntersection(time, request, reused);
  }

  // A restored entry whose kind agrees with its evidence decides that kind,
  // and never a failure.
  lemma AdmittedEntryIsDecided(universe: set<nat>, entry: Entry)
    requires Admitted(universe, entry)
    ensures entry.kind != Failure
    ensures entry.kind == Classify(universe, entry.evidence.value)
  {}
}

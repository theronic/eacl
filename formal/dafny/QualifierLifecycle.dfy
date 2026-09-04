// Finite-map storage model. The qid is deliberately absent from Identity.
module QualifierLifecycle {
  datatype Identity = Identity(subject: nat, subjectType: nat, relation: nat, resourceType: nat, resource: nat)
  datatype Qualifier = Qualifier(caveat: nat, contextPresent: bool, expires: bool, created: nat)
  datatype State = State(forward: map<Identity, nat>, reverse: map<Identity, nat>, entities: map<nat, Qualifier>, everUsed: set<nat>, stamp: nat, facts: set<nat>)

  predicate Valid(q: Qualifier) {
    (q.caveat != 0 || q.expires) && (q.contextPresent ==> q.caveat != 0)
  }

  predicate Unowned(s: State, qid: nat) {
    forall i | i in s.forward :: s.forward[i] != qid
  }

  predicate Healthy(s: State) {
    s.forward == s.reverse &&
    0 !in s.everUsed && s.entities.Keys <= s.everUsed &&
    (forall q | q in s.entities :: Valid(s.entities[q]) && s.entities[q].created <= s.stamp) &&
    (forall i | i in s.forward && s.forward[i] != 0 :: s.forward[i] in s.entities) &&
    (forall a, b | a in s.forward && b in s.forward && s.forward[a] != 0 && s.forward[a] == s.forward[b] :: a == b)
  }

  function Prepare(s: State, qid: nat, q: Qualifier): State
    requires Healthy(s) && qid != 0 && qid !in s.everUsed
    requires Valid(q) && q.created == s.stamp + 1
  {
    State(s.forward, s.reverse, s.entities[qid := q], s.everUsed + {qid}, s.stamp + 1, s.facts)
  }

  function Publish(s: State, i: Identity, qid: nat, app: set<nat>): State
    requires Healthy(s) && i !in s.forward
    requires qid == 0 || (qid in s.entities && Unowned(s, qid))
  {
    State(s.forward[i := qid], s.reverse[i := qid], s.entities, s.everUsed, s.stamp + 1, s.facts + app)
  }

  function Remove(s: State, i: Identity): State
    requires Healthy(s) && i in s.forward
  {
    State(s.forward - {i}, s.reverse - {i}, s.entities - {s.forward[i]}, s.everUsed, s.stamp + 1, s.facts)
  }

  function Replace(s: State, i: Identity, replacement: nat, app: set<nat>): State
    requires Healthy(s) && i in s.forward
    requires replacement == 0 || (replacement in s.entities && Unowned(s, replacement))
  {
    State(s.forward[i := replacement], s.reverse[i := replacement], s.entities - {s.forward[i]}, s.everUsed, s.stamp + 1, s.facts + app)
  }

  function Cleanup(s: State, qid: nat): State
    requires Healthy(s) && Unowned(s, qid)
  {
    State(s.forward, s.reverse, s.entities - {qid}, s.everUsed, s.stamp + 1, s.facts)
  }

  lemma PreparationIsInert(s: State, qid: nat, q: Qualifier)
    requires Healthy(s) && qid != 0 && qid !in s.everUsed
    requires Valid(q) && q.created == s.stamp + 1
    ensures Healthy(Prepare(s, qid, q))
    ensures Prepare(s, qid, q).forward == s.forward && Prepare(s, qid, q).reverse == s.reverse
    ensures Prepare(s, qid, q).facts == s.facts && Unowned(Prepare(s, qid, q), qid)
    ensures forall prior | prior in s.entities :: Prepare(s, qid, q).entities[prior] == s.entities[prior]
  {
  }

  lemma PublicationIsAtomic(s: State, i: Identity, qid: nat, app: set<nat>)
    requires Healthy(s) && i !in s.forward
    requires qid == 0 || (qid in s.entities && Unowned(s, qid))
    ensures Healthy(Publish(s, i, qid, app))
    ensures Publish(s, i, qid, app).forward[i] == qid && Publish(s, i, qid, app).reverse[i] == qid
    ensures app <= Publish(s, i, qid, app).facts && Publish(s, i, qid, app).stamp > s.stamp
    ensures Publish(s, i, qid, app).entities == s.entities
  {
  }

  lemma DeleteFollowsIdentity(s: State, i: Identity)
    requires Healthy(s) && i in s.forward
    ensures Healthy(Remove(s, i))
    ensures i !in Remove(s, i).forward && i !in Remove(s, i).reverse
    ensures s.forward[i] !in Remove(s, i).entities
    ensures Remove(s, i).facts == s.facts
  {
  }

  lemma ReplacementIsFreshAndAtomic(s: State, i: Identity, replacement: nat, app: set<nat>)
    requires Healthy(s) && i in s.forward
    requires replacement == 0 || (replacement in s.entities && Unowned(s, replacement))
    ensures Healthy(Replace(s, i, replacement, app))
    ensures Replace(s, i, replacement, app).forward[i] == replacement && Replace(s, i, replacement, app).reverse[i] == replacement
    ensures s.forward[i] !in Replace(s, i, replacement, app).entities
    ensures replacement != 0 ==> replacement != s.forward[i]
    ensures forall q | q in Replace(s, i, replacement, app).entities :: Replace(s, i, replacement, app).entities[q] == s.entities[q]
  {
  }

  lemma OrphanCleanupIsInert(s: State, qid: nat)
    requires Healthy(s) && Unowned(s, qid)
    ensures Healthy(Cleanup(s, qid))
    ensures Cleanup(s, qid).forward == s.forward && Cleanup(s, qid).reverse == s.reverse
    ensures Cleanup(s, qid).facts == s.facts
  {
  }

  lemma NonNilMissingIsFault(s: State, i: Identity)
    requires i in s.forward && s.forward[i] != 0 && s.forward[i] !in s.entities
    ensures !Healthy(s)
  {
  }

  lemma SharedIsFault(s: State, a: Identity, b: Identity)
    requires a != b && a in s.forward && b in s.forward
    requires s.forward[a] != 0 && s.forward[a] == s.forward[b]
    ensures !Healthy(s)
  {
  }

  lemma AsymmetricIsFault(s: State)
    requires s.forward != s.reverse
    ensures !Healthy(s)
  {
  }
}

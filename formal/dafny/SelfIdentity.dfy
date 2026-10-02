include "PermissionSetAlgebra.dfy"

// SpiceDB's `self` (`use self`) as EACL evaluates it. A `self` leaf on
// resource type t compiles to a relation leaf over t's identity relation
// (`expression/self-relation`, subject type t). Nothing is stored for that
// relation: the adapter boundary (`eacl.backend.v8`, `with-self-identity`)
// answers its ordered scans and membership probes. This model proves that the
// answer is the typed identity relation the leaf denotes, that each answered
// scan is the bounded, limited slice an ordinary scan of that relation
// returns, and that the leaf is never unknown under Kleene evaluation.
module SelfIdentity {
  import opened PermissionSetAlgebra

  // A subject holds `self` on a resource of type t iff it is that resource:
  // the same type and the same object.
  predicate SelfContains(t: nat, query: ExpressionQuery) {
    query.resource.typeId == t && query.subject == query.resource
  }

  function IdentityKey(t: nat, name: nat): RelationKey {
    RelationKey(t, name, t)
  }

  // The identity relation of type t over a finite object catalog.
  function IdentityTuples(objects: set<ObjectRef>, t: nat, name: nat): set<RelationTuple> {
    set o | o in objects && o.typeId == t :: RelationTuple(o, IdentityKey(t, name), o)
  }

  lemma SelfIsTheIdentityRelation(
    objects: set<ObjectRef>, t: nat, name: nat, query: ExpressionQuery)
    requires query.resource in objects && query.subject in objects
    ensures SelfContains(t, query) <==>
            RelationContains(IdentityTuples(objects, t, name), query.resource,
                             IdentityKey(t, name), query.subject)
  {
    var key := IdentityKey(t, name);
    var tuples := IdentityTuples(objects, t, name);
    if SelfContains(t, query) {
      assert RelationTuple(query.resource, key, query.resource) in tuples;
    }
    if RelationContains(tuples, query.resource, key, query.subject) {
      var tuple := RelationTuple(query.resource, key, query.subject);
      assert tuple in tuples;
      var o :| o in objects && o.typeId == t && tuple == RelationTuple(o, key, o);
      assert query.resource == o && query.subject == o;
    }
  }

  // Situated EACL objects share one entity across types (`user:1` and
  // `doc:1`); identity is typed, so the shared id never grants.
  lemma SameIdOtherTypeIsDenied(t: nat, subject: ObjectRef, resource: ObjectRef)
    requires subject.objectId == resource.objectId && subject.typeId != resource.typeId
    ensures !SelfContains(t, ExpressionQuery(subject, resource))
  {}

  // The resources a forward scan from `subject` reaches, and the subjects a
  // reverse scan from `resource` reaches: the anchor itself, when its type is
  // the relation's.
  lemma ForwardIdentityEndpoints(
    objects: set<ObjectRef>, t: nat, name: nat, subject: ObjectRef)
    requires subject in objects
    ensures (set r | r in objects &&
                     RelationContains(IdentityTuples(objects, t, name), r,
                                      IdentityKey(t, name), subject))
         == (if subject.typeId == t then {subject} else {})
  {
    var tuples := IdentityTuples(objects, t, name);
    var key := IdentityKey(t, name);
    var reached := set r | r in objects && RelationContains(tuples, r, key, subject);
    forall r | r in reached
      ensures r == subject && subject.typeId == t
    {
      var tuple := RelationTuple(r, key, subject);
      assert tuple in tuples;
      var o :| o in objects && o.typeId == t && tuple == RelationTuple(o, key, o);
    }
    if subject.typeId == t {
      assert RelationTuple(subject, key, subject) in tuples;
      assert subject in reached;
    }
  }

  lemma ReverseIdentityEndpoints(
    objects: set<ObjectRef>, t: nat, name: nat, resource: ObjectRef)
    requires resource in objects
    ensures (set s | s in objects &&
                     RelationContains(IdentityTuples(objects, t, name), resource,
                                      IdentityKey(t, name), s))
         == (if resource.typeId == t then {resource} else {})
  {
    var tuples := IdentityTuples(objects, t, name);
    var key := IdentityKey(t, name);
    var reached := set s | s in objects && RelationContains(tuples, resource, key, s);
    forall s | s in reached
      ensures s == resource && resource.typeId == t
    {
      var tuple := RelationTuple(resource, key, s);
      assert tuple in tuples;
      var o :| o in objects && o.typeId == t && tuple == RelationTuple(o, key, o);
    }
    if resource.typeId == t {
      assert RelationTuple(resource, key, resource) in tuples;
      assert resource in reached;
    }
  }

  // The ordered-scan contract the adapter boundary guards: values inside an
  // exclusive (or inclusive) bound in the scan direction, at most `limit` of
  // them.
  datatype Direction = Ascending | Descending
  datatype Bound = Unbounded | BoundAt(eid: nat, inclusive: bool)
  datatype Limit = Unlimited | AtMost(n: nat)

  predicate WithinBound(direction: Direction, bound: Bound, value: nat) {
    match bound
    case Unbounded => true
    case BoundAt(eid, inclusive) =>
      match direction
      case Ascending => if inclusive then eid <= value else eid < value
      case Descending => if inclusive then value <= eid else value < eid
  }

  function Inside(direction: Direction, bound: Bound, values: seq<nat>): seq<nat>
    decreases |values|
  {
    if |values| == 0 then []
    else if WithinBound(direction, bound, values[0])
    then [values[0]] + Inside(direction, bound, values[1..])
    else Inside(direction, bound, values[1..])
  }

  function Take(limit: Limit, values: seq<nat>): seq<nat> {
    match limit
    case Unlimited => values
    case AtMost(n) => if n < |values| then values[..n] else values
  }

  // An ordinary scan of a relation whose endpoint sequence from the anchor is
  // `endpoints`.
  function BoundedScan(endpoints: seq<nat>, direction: Direction, bound: Bound,
                       limit: Limit): seq<nat> {
    Take(limit, Inside(direction, bound, endpoints))
  }

  // `identity-scan` in `eacl.backend.v8`.
  function IdentityScan(anchor: nat, direction: Direction, bound: Bound,
                        limit: Limit): seq<nat> {
    if (limit.AtMost? && limit.n == 0) || !WithinBound(direction, bound, anchor)
    then []
    else [anchor]
  }

  lemma IdentityScanIsTheBoundedScan(
    anchor: nat, direction: Direction, bound: Bound, limit: Limit)
    ensures IdentityScan(anchor, direction, bound, limit)
         == BoundedScan([anchor], direction, bound, limit)
  {
    var endpoints := [anchor];
    assert endpoints[0] == anchor;
    assert endpoints[1..] == [];
    var inside := Inside(direction, bound, endpoints);
    if WithinBound(direction, bound, anchor) {
      assert inside == [anchor] + Inside(direction, bound, []);
      assert inside == [anchor];
    } else {
      assert inside == Inside(direction, bound, []);
      assert inside == [];
    }
    if limit.AtMost? && limit.n == 0 {
      assert Take(limit, inside) == [];
    }
  }

  // Kleene evaluation: the identity relation's edges carry no qualifier, so a
  // `self` leaf is never unknown.
  datatype Truth = Definite(value: bool) | Unknown

  function SelfTruth(t: nat, query: ExpressionQuery): Truth {
    Definite(SelfContains(t, query))
  }

  lemma SelfIsDefinite(t: nat, query: ExpressionQuery)
    ensures SelfTruth(t, query).Definite?
  {}
}

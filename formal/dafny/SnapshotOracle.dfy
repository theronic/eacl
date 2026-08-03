include "Semantics.dfy"

module SnapshotOracle {
  import Semantics

  datatype SnapshotId = SnapshotId(source: string, revision: string)
  datatype GraphAnchor = GraphAnchor(value: string)
  datatype InternalObject = InternalObject(typeName: string, internalId: int)
  datatype ScanDirection = Ascending | Descending
  datatype Bound = Unbounded | Exclusive(value: int) | Inclusive(value: int)
  datatype SelectionResult = Unavailable | Selected(snapshot: SnapshotView)
  datatype ProofResult = ProofUnavailable | CompleteProof(
                           scope: set<string>,
                           value: string
                         )

  datatype SnapshotView = SnapshotView(
    identity: SnapshotId,
    sourceIdentity: string,
    graphHead: GraphAnchor,
    ancestors: set<GraphAnchor>,
    exactLocator: string,
    visibleObjects: seq<Semantics.ObjectRef>,
    objects: map<Semantics.ObjectRef, InternalObject>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    permissionNodes: set<Semantics.PermissionNode>,
    schemaContent: string,
    relationContent: map<set<string>, string>
  )

  function InternalIds(values: seq<InternalObject>): seq<int> {
    seq(|values|, i requires 0 <= i < |values| => values[i].internalId)
  }

  predicate StrictlyAscending(values: seq<int>) {
    forall i, j | 0 <= i < j < |values| :: values[i] < values[j]
  }

  predicate StrictlyDescending(values: seq<int>) {
    forall i, j | 0 <= i < j < |values| :: values[i] > values[j]
  }

  predicate BoundAllows(bound: Bound, value: int) {
    match bound
    case Unbounded => true
    case Exclusive(edge) => edge < value
    case Inclusive(edge) => edge <= value
  }

  predicate DirectedBoundAllows(
    direction: ScanDirection,
    bound: Bound,
    value: int
  ) {
    match direction
    case Ascending => BoundAllows(bound, value)
    case Descending =>
      match bound
      case Unbounded => true
      case Exclusive(edge) => value < edge
      case Inclusive(edge) => value <= edge
  }

  predicate OrderedFor(
    direction: ScanDirection,
    values: seq<InternalObject>
  ) {
    match direction
    case Ascending => StrictlyAscending(InternalIds(values))
    case Descending => StrictlyDescending(InternalIds(values))
  }

  predicate UniqueInternal(values: seq<InternalObject>) {
    forall i, j | 0 <= i < j < |values| :: values[i] != values[j]
  }

  predicate SnapshotWellFormed(snapshot: SnapshotView) {
    0 < |snapshot.sourceIdentity| &&
    0 < |snapshot.exactLocator| &&
    snapshot.objects.Keys ==
    (set external | external in snapshot.visibleObjects :: external) &&
    (forall external <- snapshot.visibleObjects ::
       Semantics.ValidObject(external) &&
       snapshot.objects[external].typeName == external.typeName) &&
    (forall left, right |
       left in snapshot.objects &&
       right in snapshot.objects &&
       snapshot.objects[left] == snapshot.objects[right] ::
       left == right) &&
    Semantics.WellFormedSchema(
      snapshot.visibleObjects,
      snapshot.relations,
      snapshot.permissions,
      snapshot.definitions,
      snapshot.relationships
    ) &&
    snapshot.permissionNodes ==
    (set node | node in snapshot.permissions :: node)
  }

  predicate ForwardScanContract(
    snapshot: SnapshotView,
    subject: Semantics.ObjectRef,
    relationName: string,
    resourceType: string,
    direction: ScanDirection,
    bound: Bound,
    result: seq<InternalObject>
  ) {
    OrderedFor(direction, result) &&
    UniqueInternal(result) &&
    (forall item <- result ::
       item.typeName == resourceType &&
       DirectedBoundAllows(direction, bound, item.internalId)) &&
    (set item | item in result :: item) ==
    (set relationship |
       relationship in snapshot.relationships &&
       relationship.resource in snapshot.objects &&
       relationship.subject == subject &&
       relationship.relationName == relationName &&
       relationship.resource.typeName == resourceType &&
       DirectedBoundAllows(
         direction,
         bound,
         snapshot.objects[relationship.resource].internalId
       )
       :: snapshot.objects[relationship.resource])
  }

  predicate ReverseScanContract(
    snapshot: SnapshotView,
    resource: Semantics.ObjectRef,
    relationName: string,
    subjectType: string,
    direction: ScanDirection,
    bound: Bound,
    result: seq<InternalObject>
  ) {
    OrderedFor(direction, result) &&
    UniqueInternal(result) &&
    (forall item <- result ::
       item.typeName == subjectType &&
       DirectedBoundAllows(direction, bound, item.internalId)) &&
    (set item | item in result :: item) ==
    (set relationship |
       relationship in snapshot.relationships &&
       relationship.subject in snapshot.objects &&
       relationship.resource == resource &&
       relationship.relationName == relationName &&
       relationship.subject.typeName == subjectType &&
       DirectedBoundAllows(
         direction,
         bound,
         snapshot.objects[relationship.subject].internalId
       )
       :: snapshot.objects[relationship.subject])
  }

  trait AbstractSnapshotOracle {
    method SelectCurrent() returns (result: SnapshotView)
      ensures SnapshotWellFormed(result)

    method SelectAuthoritative() returns (result: SelectionResult)
      ensures result.Selected? ==> SnapshotWellFormed(result.snapshot)

    method SelectAtLeast(anchor: GraphAnchor)
      returns (result: SelectionResult)
      ensures result.Selected? ==>
                SnapshotWellFormed(result.snapshot) &&
                anchor in result.snapshot.ancestors + {result.snapshot.graphHead}

    method SelectExact(source: string, locator: string)
      returns (result: SelectionResult)
      ensures result.Selected? ==>
                SnapshotWellFormed(result.snapshot) &&
                result.snapshot.sourceIdentity == source &&
                result.snapshot.exactLocator == locator

    method SnapshotIdentity(snapshot: SnapshotView)
      returns (identity: SnapshotId)
      requires SnapshotWellFormed(snapshot)
      ensures identity == snapshot.identity

    method SourceIdentity(snapshot: SnapshotView)
      returns (identity: string)
      requires SnapshotWellFormed(snapshot)
      ensures identity == snapshot.sourceIdentity

    method GraphHead(snapshot: SnapshotView)
      returns (anchor: GraphAnchor)
      requires SnapshotWellFormed(snapshot)
      ensures anchor == snapshot.graphHead

    method ContainsAnchor(snapshot: SnapshotView, anchor: GraphAnchor)
      returns (contains: bool)
      requires SnapshotWellFormed(snapshot)
      ensures contains <==>
              anchor in snapshot.ancestors + {snapshot.graphHead}

    method ObjectToInternal(
      snapshot: SnapshotView,
      external: Semantics.ObjectRef
    ) returns (result: InternalObject)
      requires SnapshotWellFormed(snapshot)
      requires external in snapshot.objects
      ensures result == snapshot.objects[external]

    method InternalToObject(
      snapshot: SnapshotView,
      internal: InternalObject
    ) returns (result: Semantics.ObjectRef)
      requires SnapshotWellFormed(snapshot)
      requires internal in snapshot.objects.Values
      ensures result in snapshot.objects
      ensures snapshot.objects[result] == internal

    method RelationDefinitions(snapshot: SnapshotView)
      returns (result: seq<Semantics.RelationNode>)
      requires SnapshotWellFormed(snapshot)
      ensures result == snapshot.relations

    method PermissionDefinitions(snapshot: SnapshotView)
      returns (result: seq<Semantics.RuleDefinition>)
      requires SnapshotWellFormed(snapshot)
      ensures result == snapshot.definitions

    method AllPermissionNodes(snapshot: SnapshotView)
      returns (result: set<Semantics.PermissionNode>)
      requires SnapshotWellFormed(snapshot)
      ensures result == snapshot.permissionNodes

    method ForwardScan(
      snapshot: SnapshotView,
      subject: Semantics.ObjectRef,
      relationName: string,
      resourceType: string,
      direction: ScanDirection,
      bound: Bound
    ) returns (result: seq<InternalObject>)
      requires SnapshotWellFormed(snapshot)
      requires subject in snapshot.objects
      ensures ForwardScanContract(
                snapshot,
                subject,
                relationName,
                resourceType,
                direction,
                bound,
                result
              )

    method ReverseScan(
      snapshot: SnapshotView,
      resource: Semantics.ObjectRef,
      relationName: string,
      subjectType: string,
      direction: ScanDirection,
      bound: Bound
    ) returns (result: seq<InternalObject>)
      requires SnapshotWellFormed(snapshot)
      requires resource in snapshot.objects
      ensures ReverseScanContract(
                snapshot,
                resource,
                relationName,
                subjectType,
                direction,
                bound,
                result
              )

    method DirectMatch(
      snapshot: SnapshotView,
      relationship: Semantics.Relationship
    ) returns (matches: bool)
      requires SnapshotWellFormed(snapshot)
      ensures matches <==> relationship in snapshot.relationships

    method SchemaProof(
      snapshot: SnapshotView,
      scope: set<string>
    ) returns (result: ProofResult)
      requires SnapshotWellFormed(snapshot)
      ensures result.CompleteProof? ==>
                result.scope == scope &&
                result.value == snapshot.schemaContent

    method RelationProof(
      snapshot: SnapshotView,
      scope: set<string>
    ) returns (result: ProofResult)
      requires SnapshotWellFormed(snapshot)
      ensures result.CompleteProof? ==>
                result.scope == scope &&
                scope in snapshot.relationContent &&
                result.value ==
                snapshot.relationContent[scope]
  }

  lemma ObjectConversionIsInjective(
    snapshot: SnapshotView,
    left: Semantics.ObjectRef,
    right: Semantics.ObjectRef
  )
    requires SnapshotWellFormed(snapshot)
    requires left in snapshot.objects
    requires right in snapshot.objects
    requires snapshot.objects[left] == snapshot.objects[right]
    ensures left == right
  {
  }
}

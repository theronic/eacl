// Mathematical model for EACL's bounded shallow permission-tree expansion.
//
// The verified boundary starts with an immutable, well-formed Snapshot whose
// typed objects and normalized union components were supplied by an adapter.
// It does not prove the handwritten Clojure implementation, adapter queries,
// codecs, clocks, causal-token authentication, or host integer semantics.
// Those refinement obligations are exercised by correspondence and boundary
// tests and remain explicit in the assurance manifest.
module PermissionTree {
  datatype ObjectRef = ObjectRef(typeId: nat, objectId: nat)

  datatype DefinitionKey = DefinitionKey(typeId: nat, name: nat)

  datatype ExpansionKey = ExpansionKey(resource: ObjectRef, name: nat)

  datatype Relationship = Relationship(
    resource: ObjectRef,
    relation: nat,
    subject: ObjectRef
  )

  datatype Component =
    | SameRelation(relation: nat)
    | SamePermission(permission: nat)
    | ArrowRelation(sourceRelation: nat, targetRelation: nat)
    | ArrowPermission(sourceRelation: nat, targetPermission: nat)

  datatype RelationDefinition = RelationDefinition(
    key: DefinitionKey,
    subjectType: nat
  )

  datatype PermissionDefinition = PermissionDefinition(
    key: DefinitionKey,
    components: seq<Component>
  )

  datatype Schema = Schema(
    relations: seq<RelationDefinition>,
    permissions: seq<PermissionDefinition>
  )

  datatype Snapshot = Snapshot(
    schema: Schema,
    relationships: seq<Relationship>
  )

  // The datatype makes leaf/intermediate a true oneof. Every constructor also
  // carries the expanded object and relation/permission annotation.
  datatype Tree =
    | Leaf(
        expandedObject: ObjectRef,
        expandedRelation: nat,
        subjects: seq<ObjectRef>
      )
    | Union(
        expandedObject: ObjectRef,
        expandedRelation: nat,
        children: seq<Tree>
      )

  datatype Budget = Budget(
    schemaComponents: nat,
    relationshipValues: nat,
    treeNodes: nat,
    leafSubjects: nat
  )

  datatype Limits = Limits(
    maxDepth: nat,
    maxSchemaComponents: nat,
    maxRelationshipValues: nat,
    maxTreeNodes: nat,
    maxLeafSubjects: nat
  )

  datatype Failure =
    | UnknownRoot
    | InvalidSchema
    | CycleDetected
    | DepthLimitExceeded
    | SchemaComponentLimitExceeded
    | RelationshipValueLimitExceeded
    | TreeNodeLimitExceeded
    | LeafSubjectLimitExceeded

  datatype RawOutcome =
    | RawSuccess(tree: Tree, budget: Budget)
    | RawFailure(failure: Failure)

  // A failure contains no tree, so neither a cycle nor a resource failure can
  // publish a partial result.
  datatype Outcome =
    | Success(tree: Tree, budget: Budget)
    | Failed(failure: Failure)

  datatype ChildrenOutcome =
    | ChildrenSuccess(children: seq<Tree>, budget: Budget)
    | ChildrenFailure(failure: Failure)

  function ZeroBudget(): Budget {
    Budget(0, 0, 0, 0)
  }

  function AddBudget(left: Budget, right: Budget): Budget {
    Budget(
      left.schemaComponents + right.schemaComponents,
      left.relationshipValues + right.relationshipValues,
      left.treeNodes + right.treeNodes,
      left.leafSubjects + right.leafSubjects
    )
  }

  predicate BudgetLe(left: Budget, right: Budget) {
    left.schemaComponents <= right.schemaComponents &&
    left.relationshipValues <= right.relationshipValues &&
    left.treeNodes <= right.treeNodes &&
    left.leafSubjects <= right.leafSubjects
  }

  lemma BudgetAdditionIsMonotone(left: Budget, right: Budget)
    ensures BudgetLe(left, AddBudget(left, right))
    ensures BudgetLe(right, AddBudget(left, right))
  {
  }

  function RelationDefinitions(
    definitions: seq<RelationDefinition>,
    key: DefinitionKey
  ): seq<RelationDefinition>
    decreases |definitions|
  {
    if |definitions| == 0 then
      []
    else
      (if definitions[0].key == key then [definitions[0]] else []) +
      RelationDefinitions(definitions[1..], key)
  }

  predicate DistinctSubjectTypes(
    definitions: seq<RelationDefinition>
  )
    decreases |definitions|
  {
    |definitions| == 0 ||
    (!DeclaresSubjectType(
       definitions[1..],
       definitions[0].subjectType
     ) &&
     DistinctSubjectTypes(definitions[1..]))
  }

  predicate DeclaresSubjectType(
    definitions: seq<RelationDefinition>,
    subjectType: nat
  ) {
    exists definition <- definitions ::
      definition.subjectType == subjectType
  }

  function PermissionDefinitions(
    definitions: seq<PermissionDefinition>,
    key: DefinitionKey
  ): seq<PermissionDefinition>
    decreases |definitions|
  {
    if |definitions| == 0 then
      []
    else
      (if definitions[0].key == key then [definitions[0]] else []) +
      PermissionDefinitions(definitions[1..], key)
  }

  function MatchingSubjects(
    definitions: seq<RelationDefinition>,
    relationships: seq<Relationship>,
    resource: ObjectRef,
    relation: nat
  ): seq<ObjectRef>
    decreases |relationships|
  {
    if |relationships| == 0 then
      []
    else
      (if relationships[0].resource == resource &&
          relationships[0].relation == relation
          && DeclaresSubjectType(
            definitions,
            relationships[0].subject.typeId
          )
       then [relationships[0].subject]
       else []) +
      MatchingSubjects(
        definitions,
        relationships[1..],
        resource,
        relation
      )
  }

  lemma MatchingSubjectsAreExact(
    definitions: seq<RelationDefinition>,
    relationships: seq<Relationship>,
    resource: ObjectRef,
    relation: nat,
    subject: ObjectRef
  )
    ensures subject in MatchingSubjects(
                         definitions,
                         relationships,
                         resource,
                         relation
                       ) <==>
            exists item <- relationships ::
              item.resource == resource &&
              item.relation == relation &&
              item.subject == subject &&
              DeclaresSubjectType(definitions, item.subject.typeId)
    decreases |relationships|
  {
    if |relationships| != 0 {
      MatchingSubjectsAreExact(
        definitions,
        relationships[1..],
        resource,
        relation,
        subject
      );
    }
  }

  lemma AbsentResourceHasNoSubjects(
    definitions: seq<RelationDefinition>,
    relationships: seq<Relationship>,
    resource: ObjectRef,
    relation: nat
  )
    requires forall item <- relationships ::
               item.resource != resource || item.relation != relation
    ensures MatchingSubjects(
              definitions,
              relationships,
              resource,
              relation
            ) == []
    decreases |relationships|
  {
    if |relationships| != 0 {
      AbsentResourceHasNoSubjects(
        definitions,
        relationships[1..],
        resource,
        relation
      );
    }
  }

  function RelationScanBudget(
    definitions: seq<RelationDefinition>,
    subjects: seq<ObjectRef>
  ): Budget {
    Budget(|definitions|, |subjects|, 0, 0)
  }

  function DirectLeafBudget(
    definitions: seq<RelationDefinition>,
    subjects: seq<ObjectRef>
  ): Budget {
    Budget(|definitions|, |subjects|, 1, |subjects|)
  }

  function RawRelationTree(
    snapshot: Snapshot,
    resource: ObjectRef,
    relation: nat,
    definitions: seq<RelationDefinition>
  ): RawOutcome {
    var subjects := MatchingSubjects(
                      definitions,
                      snapshot.relationships,
                      resource,
                      relation
                    );
    RawSuccess(
      Leaf(resource, relation, subjects),
      DirectLeafBudget(definitions, subjects)
    )
  }

  function Collapse(outcomes: seq<RawOutcome>): ChildrenOutcome
    decreases |outcomes|
  {
    if |outcomes| == 0 then
      ChildrenSuccess([], ZeroBudget())
    else if outcomes[0].RawFailure? then
      ChildrenFailure(outcomes[0].failure)
    else
      var tail := Collapse(outcomes[1..]);
      if tail.ChildrenFailure? then
        tail
      else
        ChildrenSuccess(
          [outcomes[0].tree] + tail.children,
          AddBudget(outcomes[0].budget, tail.budget)
        )
  }

  function RawExpand(
    snapshot: Snapshot,
    resource: ObjectRef,
    name: nat,
    fuel: nat,
    active: set<ExpansionKey>
  ): RawOutcome
    decreases fuel, 1
  {
    var expansionKey := ExpansionKey(resource, name);
    var definitionKey := DefinitionKey(resource.typeId, name);
    if fuel == 0 then
      RawFailure(DepthLimitExceeded)
    else if expansionKey in active then
      RawFailure(CycleDetected)
    else
      var relations := RelationDefinitions(
                         snapshot.schema.relations,
                         definitionKey
                       );
      var permissions := PermissionDefinitions(
                           snapshot.schema.permissions,
                           definitionKey
                         );
      if |relations| != 0 && |permissions| != 0 then
        RawFailure(InvalidSchema)
      else if |relations| != 0 then
        if !DistinctSubjectTypes(relations) then
          RawFailure(InvalidSchema)
        else
          RawRelationTree(snapshot, resource, name, relations)
      else if |permissions| > 1 ||
              (|permissions| == 1 &&
               |permissions[0].components| == 0) then
        RawFailure(InvalidSchema)
      else if |permissions| == 1 then
        var permission := permissions[0];
        var nextActive := active + {expansionKey};
        var componentOutcomes := seq(
                                 |permission.components|,
                                 index requires 0 <= index < |permission.components| =>
                                   RawExpandComponent(
                                     snapshot,
                                     resource,
                                     name,
                                     permission.components[index],
                                     fuel - 1,
                                     nextActive
                                   )
                                   );
        var collapsed := Collapse(componentOutcomes);
        if collapsed.ChildrenFailure? then
          RawFailure(collapsed.failure)
        else
          RawSuccess(
            Union(resource, name, collapsed.children),
            AddBudget(
              Budget(|permission.components|, 0, 1, 0),
              collapsed.budget
            )
          )
      else
        RawFailure(UnknownRoot)
  }

  function RawExpandComponent(
    snapshot: Snapshot,
    resource: ObjectRef,
    expandedName: nat,
    component: Component,
    fuel: nat,
    active: set<ExpansionKey>
  ): RawOutcome
    decreases fuel, 2
  {
    if fuel == 0 then
      RawFailure(DepthLimitExceeded)
    else
      match component
      case SameRelation(relation) =>
        var key := DefinitionKey(resource.typeId, relation);
        var relations := RelationDefinitions(snapshot.schema.relations, key);
        if |relations| != 0 && DistinctSubjectTypes(relations) &&
           |PermissionDefinitions(snapshot.schema.permissions, key)| == 0
        then RawRelationTree(snapshot, resource, relation, relations)
        else RawFailure(InvalidSchema)
      case SamePermission(permission) =>
        var key := DefinitionKey(resource.typeId, permission);
        var permissions := PermissionDefinitions(
                             snapshot.schema.permissions,
                             key
                           );
        if |permissions| == 1 &&
           |permissions[0].components| != 0 &&
           |RelationDefinitions(snapshot.schema.relations, key)| == 0
        then RawExpand(snapshot, resource, permission, fuel, active)
        else RawFailure(InvalidSchema)
      case ArrowRelation(sourceRelation, targetRelation) =>
        RawExpandArrow(
          snapshot,
          resource,
          sourceRelation,
          targetRelation,
          expandedName,
          false,
          fuel,
          active
        )
      case ArrowPermission(sourceRelation, targetPermission) =>
        RawExpandArrow(
          snapshot,
          resource,
          sourceRelation,
          targetPermission,
          expandedName,
          true,
          fuel,
          active
        )
  }

  function RawExpandArrow(
    snapshot: Snapshot,
    resource: ObjectRef,
    sourceRelation: nat,
    targetName: nat,
    expandedName: nat,
    targetIsPermission: bool,
    fuel: nat,
    active: set<ExpansionKey>
  ): RawOutcome
    decreases fuel, 0
  {
    var sourceKey := DefinitionKey(resource.typeId, sourceRelation);
    var sourceDefinitions := RelationDefinitions(
                               snapshot.schema.relations,
                               sourceKey
                             );
    if fuel == 0 then
      RawFailure(DepthLimitExceeded)
    else if |sourceDefinitions| == 0 ||
            |PermissionDefinitions(
              snapshot.schema.permissions,
              sourceKey
            )| != 0 ||
            !DistinctSubjectTypes(sourceDefinitions) then
      RawFailure(InvalidSchema)
    else
      var intermediates := MatchingSubjects(
                             sourceDefinitions,
                             snapshot.relationships,
                             resource,
                             sourceRelation
                           );
      var childOutcomes := seq(
                           |intermediates|,
                           index requires 0 <= index < |intermediates| =>
                             if targetIsPermission
                             then RawExpand(
                                    snapshot,
                                    intermediates[index],
                                    targetName,
                                    fuel - 1,
                                    active
                                  )
                             else
                               var targetKey := DefinitionKey(
                                                  intermediates[index].typeId,
                                                  targetName
                                                );
                               var targetDefinitions := RelationDefinitions(
                                                          snapshot.schema.relations,
                                                          targetKey
                                                        );
                               if fuel == 1 then
                                 RawFailure(DepthLimitExceeded)
                               else if |targetDefinitions| != 0 &&
                                       DistinctSubjectTypes(targetDefinitions) &&
                                       |PermissionDefinitions(
                                         snapshot.schema.permissions,
                                         targetKey
                                       )| == 0
                               then RawRelationTree(
                                              snapshot,
                                              intermediates[index],
                                              targetName,
                                              targetDefinitions
                                            )
                               else RawFailure(InvalidSchema)
                                 );
      var collapsed := Collapse(childOutcomes);
      if collapsed.ChildrenFailure? then
        RawFailure(collapsed.failure)
      else
        RawSuccess(
          Union(resource, expandedName, collapsed.children),
          AddBudget(
            Budget(
              |sourceDefinitions|,
              |intermediates|,
              1,
              0
            ),
            collapsed.budget
          )
        )
  }

  predicate TreeDepthWithin(tree: Tree, maxDepth: nat)
    decreases tree
  {
    0 < maxDepth &&
    match tree
    case Leaf(_, _, _) => true
    case Union(_, _, children) =>
      forall child <- children ::
        TreeDepthWithin(child, maxDepth - 1)
  }

  predicate WithinLimits(tree: Tree, budget: Budget, limits: Limits) {
    TreeDepthWithin(tree, limits.maxDepth) &&
    budget.schemaComponents <= limits.maxSchemaComponents &&
    budget.relationshipValues <= limits.maxRelationshipValues &&
    budget.treeNodes <= limits.maxTreeNodes &&
    budget.leafSubjects <= limits.maxLeafSubjects
  }

  function LimitFailure(
    tree: Tree,
    budget: Budget,
    limits: Limits
  ): Failure
    requires !WithinLimits(tree, budget, limits)
  {
    if !TreeDepthWithin(tree, limits.maxDepth) then
      DepthLimitExceeded
    else if budget.schemaComponents > limits.maxSchemaComponents then
      SchemaComponentLimitExceeded
    else if budget.relationshipValues > limits.maxRelationshipValues then
      RelationshipValueLimitExceeded
    else if budget.treeNodes > limits.maxTreeNodes then
      TreeNodeLimitExceeded
    else
      LeafSubjectLimitExceeded
  }

  function EnforceLimits(raw: RawOutcome, limits: Limits): Outcome {
    if raw.RawFailure? then
      Failed(raw.failure)
    else if WithinLimits(raw.tree, raw.budget, limits) then
      Success(raw.tree, raw.budget)
    else
      Failed(LimitFailure(raw.tree, raw.budget, limits))
  }

  function Expand(
    snapshot: Snapshot,
    resource: ObjectRef,
    name: nat,
    limits: Limits
  ): Outcome {
    EnforceLimits(
      RawExpand(snapshot, resource, name, limits.maxDepth, {}),
      limits
    )
  }

  predicate TreeContains(tree: Tree, subject: ObjectRef)
    decreases tree
  {
    match tree
    case Leaf(_, _, subjects) => subject in subjects
    case Union(_, _, children) =>
      exists child <- children :: TreeContains(child, subject)
  }

  predicate TreeWellFormed(tree: Tree)
    decreases tree
  {
    match tree
    case Leaf(_, _, _) => true
    case Union(_, _, children) =>
      forall child <- children :: TreeWellFormed(child)
  }

  lemma EveryTreeHasExactlyOneVariant(tree: Tree)
    ensures tree.Leaf? != tree.Union?
  {
  }

  lemma EveryTreeIsWellFormed(tree: Tree)
    ensures TreeWellFormed(tree)
    decreases tree
  {
    match tree
    case Leaf(_, _, _) =>
    case Union(_, _, children) =>
      forall index | 0 <= index < |children|
        ensures TreeWellFormed(children[index])
      {
        EveryTreeIsWellFormed(children[index]);
      }
  }

  lemma DirectLeafIsExact(
    snapshot: Snapshot,
    resource: ObjectRef,
    relation: nat,
    definitions: seq<RelationDefinition>
  )
    ensures RawRelationTree(
              snapshot,
              resource,
              relation,
              definitions
            ).RawSuccess?
    ensures RawRelationTree(
              snapshot,
              resource,
              relation,
              definitions
            ).tree ==
            Leaf(
              resource,
              relation,
              MatchingSubjects(
                definitions,
                snapshot.relationships,
                resource,
                relation
              )
            )
  {
  }

  lemma UnionDenotesItsChildren(
    resource: ObjectRef,
    name: nat,
    children: seq<Tree>,
    subject: ObjectRef
  )
    ensures TreeContains(Union(resource, name, children), subject) <==>
            exists child <- children :: TreeContains(child, subject)
  {
  }

  lemma UnionChildPermutationIsSemantic(
    resource: ObjectRef,
    name: nat,
    left: Tree,
    right: Tree,
    rest: seq<Tree>
  )
    ensures forall subject: ObjectRef ::
              TreeContains(
                Union(resource, name, [left, right] + rest),
                subject
              ) <==>
              TreeContains(
                Union(resource, name, [right, left] + rest),
                subject
              )
  {
  }

  lemma ActivePathIsRejected(
    snapshot: Snapshot,
    resource: ObjectRef,
    name: nat,
    fuel: nat,
    active: set<ExpansionKey>
  )
    requires 0 < fuel
    requires ExpansionKey(resource, name) in active
    ensures RawExpand(
              snapshot,
              resource,
              name,
              fuel,
              active
            ) == RawFailure(CycleDetected)
  {
  }

  lemma SuccessfulExpansionPreservesEveryLimit(
    snapshot: Snapshot,
    resource: ObjectRef,
    name: nat,
    limits: Limits
  )
    requires Expand(snapshot, resource, name, limits).Success?
    ensures WithinLimits(
              Expand(snapshot, resource, name, limits).tree,
              Expand(snapshot, resource, name, limits).budget,
              limits
            )
  {
  }

  lemma LimitFailureNeverContainsPartialTree(
    raw: RawOutcome,
    limits: Limits
  )
    requires raw.RawSuccess?
    requires !WithinLimits(raw.tree, raw.budget, limits)
    ensures EnforceLimits(raw, limits).Failed?
  {
  }

  // Executable witnesses and mutation controls. These are deliberately small
  // concrete models: changing typed identity to objectId-only, changing the
  // active path to a global visited set, flattening nested unions, or returning
  // raw success after a failed limit makes at least one assertion false.
  lemma EmptyRelationWitness()
    ensures MatchingSubjects(
              [],
              [],
              ObjectRef(1, 1),
              2
            ) == []
    ensures RawRelationTree(
              Snapshot(Schema([], []), []),
              ObjectRef(1, 1),
              2,
              [RelationDefinition(DefinitionKey(1, 2), 3)]
            ).tree.subjects == []
  {
  }

  lemma TypedIdentityMutationControl()
    ensures ObjectRef(1, 7) != ObjectRef(2, 7)
    ensures ObjectRef(1, 7).objectId == ObjectRef(2, 7).objectId
  {
  }

  lemma NestedUnionMutationControl()
    ensures Union(
              ObjectRef(1, 1),
              9,
              [Union(ObjectRef(1, 1), 8, [])]
            ) != Union(ObjectRef(1, 1), 9, [])
  {
  }

  lemma DuplicateTopologyMutationControl(tree: Tree)
    ensures Union(ObjectRef(1, 1), 9, [tree, tree]) !=
            Union(ObjectRef(1, 1), 9, [tree])
  {
  }

  lemma DiamondIsNotAnActivePathCycleMutationControl()
    ensures ExpansionKey(ObjectRef(2, 7), 3) !in
            {ExpansionKey(ObjectRef(1, 1), 9)}
  {
  }

  lemma CycleWitness()
    ensures RawExpand(
              Snapshot(Schema([], []), []),
              ObjectRef(1, 1),
              9,
              1,
              {ExpansionKey(ObjectRef(1, 1), 9)}
            ) == RawFailure(CycleDetected)
  {
  }

  lemma OverLimitMutationControl(tree: Tree)
    ensures EnforceLimits(
              RawSuccess(tree, Budget(1, 2, 1, 2)),
              Limits(1, 1, 1, 1, 1)
            ).Failed?
  {
  }

  lemma DirectWitness()
    ensures RawRelationTree(
              Snapshot(
                Schema([], []),
                [Relationship(
                   ObjectRef(1, 1),
                   2,
                   ObjectRef(3, 4)
                 )]
              ),
              ObjectRef(1, 1),
              2,
              [RelationDefinition(DefinitionKey(1, 2), 3)]
            ).tree ==
            Leaf(ObjectRef(1, 1), 2, [ObjectRef(3, 4)])
  {
  }

  lemma SumTypedRelationWitness()
    ensures MatchingSubjects(
              [
                RelationDefinition(DefinitionKey(1, 2), 3),
                RelationDefinition(DefinitionKey(1, 2), 4)
              ],
              [
                Relationship(ObjectRef(1, 1), 2, ObjectRef(3, 7)),
                Relationship(ObjectRef(1, 1), 2, ObjectRef(4, 7)),
                Relationship(ObjectRef(1, 1), 2, ObjectRef(5, 7))
              ],
              ObjectRef(1, 1),
              2
            ) == [ObjectRef(3, 7), ObjectRef(4, 7)]
    ensures DirectLeafBudget(
              [
                RelationDefinition(DefinitionKey(1, 2), 3),
                RelationDefinition(DefinitionKey(1, 2), 4)
              ],
              [ObjectRef(3, 7), ObjectRef(4, 7)]
            ).schemaComponents == 2
  {
  }

  lemma DuplicateRelationSubjectTypesAreInvalidWitness()
    ensures !DistinctSubjectTypes(
              [
                RelationDefinition(DefinitionKey(1, 2), 3),
                RelationDefinition(DefinitionKey(1, 2), 3)
              ]
            )
  {
  }

  function SameRelationDepthFixture(maxDepth: nat): Outcome {
    Expand(
      Snapshot(
        Schema(
          [RelationDefinition(DefinitionKey(1, 2), 3)],
          [
            PermissionDefinition(
              DefinitionKey(1, 9),
              [SameRelation(2)]
            )
          ]
        ),
        []
      ),
      ObjectRef(1, 1),
      9,
      Limits(maxDepth, 10, 10, 10, 10)
    )
  }

  function SameRelationComponentDepthFixture(fuel: nat): RawOutcome {
    RawExpandComponent(
      Snapshot(
        Schema(
          [RelationDefinition(DefinitionKey(1, 2), 3)],
          []
        ),
        []
      ),
      ObjectRef(1, 1),
      9,
      SameRelation(2),
      fuel,
      {ExpansionKey(ObjectRef(1, 1), 9)}
    )
  }

  lemma EveryEmittedChildConsumesDepthWitness()
    ensures SameRelationDepthFixture(1).Failed?
    ensures SameRelationDepthFixture(1).failure == DepthLimitExceeded
    ensures SameRelationComponentDepthFixture(0) ==
            RawFailure(DepthLimitExceeded)
    ensures SameRelationComponentDepthFixture(1).RawSuccess?
  {
  }

  function UnionFixture(): Tree {
    Union(
      ObjectRef(1, 1),
      9,
      [
        Leaf(ObjectRef(1, 1), 2, []),
        Leaf(ObjectRef(1, 1), 2, [])
      ]
    )
  }

  lemma UnionWitness()
    ensures UnionFixture().Union?
    ensures UnionFixture().children ==
            [
              Leaf(ObjectRef(1, 1), 2, []),
              Leaf(ObjectRef(1, 1), 2, [])
            ]
  {
  }

  function ArrowFixture(): Tree {
    Union(
      ObjectRef(1, 1),
      9,
      [Leaf(ObjectRef(3, 7), 4, [ObjectRef(5, 8)])]
    )
  }

  lemma ArrowWitness()
    ensures ArrowFixture().Union?
    ensures ArrowFixture().expandedObject == ObjectRef(1, 1)
    ensures ArrowFixture().children ==
            [Leaf(ObjectRef(3, 7), 4, [ObjectRef(5, 8)])]
  {
  }

  lemma DiamondWitness()
    ensures UnionFixture().children[0] == UnionFixture().children[1]
    ensures |UnionFixture().children| == 2
  {
  }
}

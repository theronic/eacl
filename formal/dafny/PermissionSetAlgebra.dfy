// Finite executable semantics for permission expressions and stratified,
// recursively positive authorization rules.  Every identity is typed: object
// identity is the pair (typeId, objectId), never objectId alone.
module PermissionSetAlgebra {
  datatype ObjectRef = ObjectRef(typeId: nat, objectId: nat)

  datatype PermissionKey = PermissionKey(resourceType: nat, name: nat)

  datatype RelationKey = RelationKey(
    resourceType: nat,
    name: nat,
    subjectType: nat
  )

  datatype RelationTuple = RelationTuple(
    resource: ObjectRef,
    relation: RelationKey,
    subject: ObjectRef
  )

  datatype PermissionFact = PermissionFact(
    subject: ObjectRef,
    permission: PermissionKey,
    resource: ObjectRef
  )

  datatype ArrowTarget =
    | TargetRelation(relation: RelationKey)
    | TargetPermission(permission: PermissionKey)

  // Child references are indexes into a finite canonical node table.  The
  // table representation admits DAG sharing without recursive host objects.
  datatype ExpressionNode =
    | RelationExpression(relation: RelationKey)
    | PermissionExpression(permission: PermissionKey)
    | ArrowExpression(viaRelation: RelationKey, target: ArrowTarget)
    | UnionExpression(children: seq<nat>)
    | IntersectionExpression(children: seq<nat>)
    | ExclusionExpression(includeChild: nat, excludeChild: nat)

  datatype ExpressionQuery = ExpressionQuery(
    subject: ObjectRef,
    resource: ObjectRef
  )

  predicate WellTypedRelationTuple(tuple: RelationTuple) {
    tuple.resource.typeId == tuple.relation.resourceType &&
    tuple.subject.typeId == tuple.relation.subjectType
  }

  predicate WellTypedPermissionFact(fact: PermissionFact) {
    fact.resource.typeId == fact.permission.resourceType
  }

  predicate UniqueObjects(objects: seq<ObjectRef>) {
    forall left, right | 0 <= left < right < |objects| ::
      objects[left] != objects[right]
  }

  predicate WellTypedRelationStore(tuples: set<RelationTuple>) {
    forall tuple <- tuples :: WellTypedRelationTuple(tuple)
  }

  predicate WellTypedPermissionStore(facts: set<PermissionFact>) {
    forall fact <- facts :: WellTypedPermissionFact(fact)
  }

  predicate CompleteObjectCatalog(
    objects: seq<ObjectRef>,
    tuples: set<RelationTuple>,
    facts: set<PermissionFact>
  ) {
    UniqueObjects(objects) &&
    WellTypedRelationStore(tuples) &&
    WellTypedPermissionStore(facts) &&
    (forall tuple <- tuples ::
       tuple.resource in objects && tuple.subject in objects) &&
    (forall fact <- facts ::
       fact.resource in objects && fact.subject in objects)
  }

  predicate ChildIndexesBelow(children: seq<nat>, parent: nat) {
    forall child <- children :: child < parent
  }

  predicate WellFormedNode(nodes: seq<ExpressionNode>, index: nat) {
    index < |nodes| &&
    match nodes[index]
    case RelationExpression(_) => true
    case PermissionExpression(_) => true
    case ArrowExpression(_, _) => true
    case UnionExpression(children) =>
      0 < |children| && ChildIndexesBelow(children, index)
    case IntersectionExpression(children) =>
      0 < |children| && ChildIndexesBelow(children, index)
    case ExclusionExpression(includeChild, excludeChild) =>
      includeChild < index && excludeChild < index
  }

  predicate WellFormedExpressionTable(nodes: seq<ExpressionNode>) {
    forall index | 0 <= index < |nodes| :: WellFormedNode(nodes, index)
  }

  function ArrowTargetResourceType(target: ArrowTarget): nat {
    match target
    case TargetRelation(relation) => relation.resourceType
    case TargetPermission(permission) => permission.resourceType
  }

  predicate WellTypedExpressionNode(
    nodes: seq<ExpressionNode>,
    nodeResourceTypes: map<nat, nat>,
    index: nat
  ) {
    index < |nodes| &&
    index in nodeResourceTypes &&
    match nodes[index]
    case RelationExpression(relation) =>
      relation.resourceType == nodeResourceTypes[index]
    case PermissionExpression(permission) =>
      permission.resourceType == nodeResourceTypes[index]
    case ArrowExpression(viaRelation, target) =>
      viaRelation.resourceType == nodeResourceTypes[index] &&
      ArrowTargetResourceType(target) == viaRelation.subjectType
    case UnionExpression(children) =>
      forall child <- children ::
        child in nodeResourceTypes &&
        nodeResourceTypes[child] == nodeResourceTypes[index]
    case IntersectionExpression(children) =>
      forall child <- children ::
        child in nodeResourceTypes &&
        nodeResourceTypes[child] == nodeResourceTypes[index]
    case ExclusionExpression(includeChild, excludeChild) =>
      includeChild in nodeResourceTypes &&
      excludeChild in nodeResourceTypes &&
      nodeResourceTypes[includeChild] == nodeResourceTypes[index] &&
      nodeResourceTypes[excludeChild] == nodeResourceTypes[index]
  }

  predicate WellFormedTypedExpressionTable(
    nodes: seq<ExpressionNode>,
    nodeResourceTypes: map<nat, nat>
  ) {
    WellFormedExpressionTable(nodes) &&
    (forall index | 0 <= index < |nodes| ::
       WellTypedExpressionNode(nodes, nodeResourceTypes, index))
  }

  function RelationContains(
    tuples: set<RelationTuple>,
    resource: ObjectRef,
    relation: RelationKey,
    subject: ObjectRef
  ): bool {
    resource.typeId == relation.resourceType &&
    subject.typeId == relation.subjectType &&
    RelationTuple(resource, relation, subject) in tuples
  }

  function ArrowTargetContains(
    tuples: set<RelationTuple>,
    facts: set<PermissionFact>,
    subject: ObjectRef,
    intermediate: ObjectRef,
    target: ArrowTarget
  ): bool {
    match target
    case TargetRelation(relation) =>
      intermediate.typeId == relation.resourceType &&
      subject.typeId == relation.subjectType &&
      RelationContains(tuples, intermediate, relation, subject)
    case TargetPermission(permission) =>
      intermediate.typeId == permission.resourceType &&
      PermissionFact(subject, permission, intermediate) in facts
  }

  function ArrowContains(
    objects: seq<ObjectRef>,
    tuples: set<RelationTuple>,
    facts: set<PermissionFact>,
    query: ExpressionQuery,
    viaRelation: RelationKey,
    target: ArrowTarget
  ): bool
    decreases |objects|
  {
    if |objects| == 0 then
      false
    else
      (RelationContains(
         tuples,
         query.resource,
         viaRelation,
         objects[0]
       ) &&
       ArrowTargetContains(
         tuples,
         facts,
         query.subject,
         objects[0],
         target
       )) ||
      ArrowContains(
        objects[1..],
        tuples,
        facts,
        query,
        viaRelation,
        target
      )
  }

  function EvaluateExpression(
    nodes: seq<ExpressionNode>,
    nodeIndex: nat,
    objects: seq<ObjectRef>,
    tuples: set<RelationTuple>,
    currentFacts: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>,
    query: ExpressionQuery,
    fuel: nat
  ): bool
    decreases fuel, 0
  {
    if fuel == 0 || nodeIndex >= |nodes| then
      false
    else
      match nodes[nodeIndex]
      case RelationExpression(relation) =>
        RelationContains(tuples, query.resource, relation, query.subject)
      case PermissionExpression(permission) =>
        query.resource.typeId == permission.resourceType &&
        PermissionFact(query.subject, permission, query.resource) in
          currentFacts
      case ArrowExpression(viaRelation, target) =>
        ArrowContains(
          objects,
          tuples,
          currentFacts,
          query,
          viaRelation,
          target
        )
      case UnionExpression(children) =>
        AnyChildHolds(
          nodes,
          children,
          objects,
          tuples,
          currentFacts,
          completedLowerFacts,
          query,
          fuel - 1
        )
      case IntersectionExpression(children) =>
        AllChildrenHold(
          nodes,
          children,
          objects,
          tuples,
          currentFacts,
          completedLowerFacts,
          query,
          fuel - 1
        )
      case ExclusionExpression(includeChild, excludeChild) =>
        EvaluateExpression(
          nodes,
          includeChild,
          objects,
          tuples,
          currentFacts,
          completedLowerFacts,
          query,
          fuel - 1
        ) &&
        !EvaluateExpression(
          nodes,
          excludeChild,
          objects,
          tuples,
          completedLowerFacts,
          completedLowerFacts,
          query,
          fuel - 1
        )
  }

  ghost predicate ArrowWitnessExists(
    tuples: set<RelationTuple>,
    facts: set<PermissionFact>,
    query: ExpressionQuery,
    viaRelation: RelationKey,
    target: ArrowTarget
  ) {
    exists intermediate: ObjectRef ::
      RelationContains(
        tuples,
        query.resource,
        viaRelation,
        intermediate
      ) &&
      ArrowTargetContains(
        tuples,
        facts,
        query.subject,
        intermediate,
        target
      )
  }

  lemma ArrowContainsIsSound(
    objects: seq<ObjectRef>,
    tuples: set<RelationTuple>,
    facts: set<PermissionFact>,
    query: ExpressionQuery,
    viaRelation: RelationKey,
    target: ArrowTarget
  )
    requires ArrowContains(
               objects,
               tuples,
               facts,
               query,
               viaRelation,
               target
             )
    ensures ArrowWitnessExists(
              tuples,
              facts,
              query,
              viaRelation,
              target
            )
    decreases |objects|
  {
    assert |objects| != 0;
    if !(RelationContains(
           tuples,
           query.resource,
           viaRelation,
           objects[0]
         ) &&
         ArrowTargetContains(
           tuples,
           facts,
           query.subject,
           objects[0],
           target
         )) {
      ArrowContainsIsSound(
        objects[1..],
        tuples,
        facts,
        query,
        viaRelation,
        target
      );
    }
  }

  lemma CatalogMemberWitnessMakesArrowTrue(
    objects: seq<ObjectRef>,
    tuples: set<RelationTuple>,
    facts: set<PermissionFact>,
    query: ExpressionQuery,
    viaRelation: RelationKey,
    target: ArrowTarget,
    intermediate: ObjectRef
  )
    requires intermediate in objects
    requires RelationContains(
               tuples,
               query.resource,
               viaRelation,
               intermediate
             )
    requires ArrowTargetContains(
               tuples,
               facts,
               query.subject,
               intermediate,
               target
             )
    ensures ArrowContains(
              objects,
              tuples,
              facts,
              query,
              viaRelation,
              target
            )
    decreases |objects|
  {
    assert |objects| != 0;
    if objects[0] != intermediate {
      assert intermediate in objects[1..];
      CatalogMemberWitnessMakesArrowTrue(
        objects[1..],
        tuples,
        facts,
        query,
        viaRelation,
        target,
        intermediate
      );
    }
  }

  lemma ArrowCatalogIsExact(
    objects: seq<ObjectRef>,
    tuples: set<RelationTuple>,
    facts: set<PermissionFact>,
    query: ExpressionQuery,
    viaRelation: RelationKey,
    target: ArrowTarget
  )
    requires CompleteObjectCatalog(objects, tuples, facts)
    ensures ArrowContains(
              objects,
              tuples,
              facts,
              query,
              viaRelation,
              target
            ) <==>
            ArrowWitnessExists(
              tuples,
              facts,
              query,
              viaRelation,
              target
            )
  {
    if ArrowContains(
        objects,
        tuples,
        facts,
        query,
        viaRelation,
        target
      ) {
      ArrowContainsIsSound(
        objects,
        tuples,
        facts,
        query,
        viaRelation,
        target
      );
    }
    if ArrowWitnessExists(tuples, facts, query, viaRelation, target) {
      var chosen: ObjectRef :|
        RelationContains(
          tuples,
          query.resource,
          viaRelation,
          chosen
        ) &&
        ArrowTargetContains(
          tuples,
          facts,
          query.subject,
          chosen,
          target
        );
      assert RelationTuple(
          query.resource,
          viaRelation,
          chosen
        ) in tuples;
      assert chosen in objects;
      CatalogMemberWitnessMakesArrowTrue(
        objects,
        tuples,
        facts,
        query,
        viaRelation,
        target,
        chosen
      );
    }
  }

  function AnyChildHolds(
    nodes: seq<ExpressionNode>,
    children: seq<nat>,
    objects: seq<ObjectRef>,
    tuples: set<RelationTuple>,
    currentFacts: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>,
    query: ExpressionQuery,
    fuel: nat
  ): bool
    decreases fuel, 1, |children|
  {
    |children| != 0 &&
    (EvaluateExpression(
       nodes,
       children[0],
       objects,
       tuples,
       currentFacts,
       completedLowerFacts,
       query,
       fuel
     ) ||
     AnyChildHolds(
       nodes,
       children[1..],
       objects,
       tuples,
       currentFacts,
       completedLowerFacts,
       query,
       fuel
     ))
  }

  function AllChildrenHold(
    nodes: seq<ExpressionNode>,
    children: seq<nat>,
    objects: seq<ObjectRef>,
    tuples: set<RelationTuple>,
    currentFacts: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>,
    query: ExpressionQuery,
    fuel: nat
  ): bool
    decreases fuel, 1, |children|
  {
    |children| == 0 ||
    (EvaluateExpression(
       nodes,
       children[0],
       objects,
       tuples,
       currentFacts,
       completedLowerFacts,
       query,
       fuel
     ) &&
     AllChildrenHold(
       nodes,
       children[1..],
       objects,
       tuples,
       currentFacts,
       completedLowerFacts,
       query,
       fuel
     ))
  }

  lemma EvaluateExpressionFuelStable(
    nodes: seq<ExpressionNode>,
    nodeIndex: nat,
    objects: seq<ObjectRef>,
    tuples: set<RelationTuple>,
    currentFacts: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>,
    query: ExpressionQuery,
    lowerFuel: nat,
    higherFuel: nat
  )
    requires WellFormedExpressionTable(nodes)
    requires nodeIndex < |nodes|
    requires nodeIndex < lowerFuel <= higherFuel
    ensures EvaluateExpression(
              nodes,
              nodeIndex,
              objects,
              tuples,
              currentFacts,
              completedLowerFacts,
              query,
              lowerFuel
            ) ==
            EvaluateExpression(
              nodes,
              nodeIndex,
              objects,
              tuples,
              currentFacts,
              completedLowerFacts,
              query,
              higherFuel
            )
    decreases lowerFuel, 0
  {
    assert WellFormedNode(nodes, nodeIndex);
    match nodes[nodeIndex]
    case RelationExpression(_) =>
    case PermissionExpression(_) =>
    case ArrowExpression(_, _) =>
    case UnionExpression(children) =>
      ChildIndexesBelowMeansAllForExpression(children, nodeIndex);
      assert forall child <- children ::
          child < |nodes| && child < lowerFuel - 1 <= higherFuel - 1;
      AnyChildrenFuelStable(
        nodes,
        children,
        objects,
        tuples,
        currentFacts,
        completedLowerFacts,
        query,
        lowerFuel - 1,
        higherFuel - 1
      );
    case IntersectionExpression(children) =>
      ChildIndexesBelowMeansAllForExpression(children, nodeIndex);
      assert forall child <- children ::
          child < |nodes| && child < lowerFuel - 1 <= higherFuel - 1;
      AllChildrenFuelStable(
        nodes,
        children,
        objects,
        tuples,
        currentFacts,
        completedLowerFacts,
        query,
        lowerFuel - 1,
        higherFuel - 1
      );
    case ExclusionExpression(includeChild, excludeChild) =>
      EvaluateExpressionFuelStable(
        nodes,
        includeChild,
        objects,
        tuples,
        currentFacts,
        completedLowerFacts,
        query,
        lowerFuel - 1,
        higherFuel - 1
      );
      EvaluateExpressionFuelStable(
        nodes,
        excludeChild,
        objects,
        tuples,
        completedLowerFacts,
        completedLowerFacts,
        query,
        lowerFuel - 1,
        higherFuel - 1
      );
  }

  lemma AnyChildrenFuelStable(
    nodes: seq<ExpressionNode>,
    children: seq<nat>,
    objects: seq<ObjectRef>,
    tuples: set<RelationTuple>,
    currentFacts: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>,
    query: ExpressionQuery,
    lowerFuel: nat,
    higherFuel: nat
  )
    requires WellFormedExpressionTable(nodes)
    requires lowerFuel <= higherFuel
    requires forall child <- children ::
               child < |nodes| && child < lowerFuel
    ensures AnyChildHolds(
              nodes,
              children,
              objects,
              tuples,
              currentFacts,
              completedLowerFacts,
              query,
              lowerFuel
            ) ==
            AnyChildHolds(
              nodes,
              children,
              objects,
              tuples,
              currentFacts,
              completedLowerFacts,
              query,
              higherFuel
            )
    decreases lowerFuel, 1, |children|
  {
    if |children| != 0 {
      assert children[0] in children;
      EvaluateExpressionFuelStable(
        nodes,
        children[0],
        objects,
        tuples,
        currentFacts,
        completedLowerFacts,
        query,
        lowerFuel,
        higherFuel
      );
      forall child | child in children[1..]
        ensures child < |nodes| && child < lowerFuel
      {
        assert child in children;
      }
      AnyChildrenFuelStable(
        nodes,
        children[1..],
        objects,
        tuples,
        currentFacts,
        completedLowerFacts,
        query,
        lowerFuel,
        higherFuel
      );
    }
  }

  lemma AllChildrenFuelStable(
    nodes: seq<ExpressionNode>,
    children: seq<nat>,
    objects: seq<ObjectRef>,
    tuples: set<RelationTuple>,
    currentFacts: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>,
    query: ExpressionQuery,
    lowerFuel: nat,
    higherFuel: nat
  )
    requires WellFormedExpressionTable(nodes)
    requires lowerFuel <= higherFuel
    requires forall child <- children ::
               child < |nodes| && child < lowerFuel
    ensures AllChildrenHold(
              nodes,
              children,
              objects,
              tuples,
              currentFacts,
              completedLowerFacts,
              query,
              lowerFuel
            ) ==
            AllChildrenHold(
              nodes,
              children,
              objects,
              tuples,
              currentFacts,
              completedLowerFacts,
              query,
              higherFuel
            )
    decreases lowerFuel, 1, |children|
  {
    if |children| != 0 {
      assert children[0] in children;
      EvaluateExpressionFuelStable(
        nodes,
        children[0],
        objects,
        tuples,
        currentFacts,
        completedLowerFacts,
        query,
        lowerFuel,
        higherFuel
      );
      forall child | child in children[1..]
        ensures child < |nodes| && child < lowerFuel
      {
        assert child in children;
      }
      AllChildrenFuelStable(
        nodes,
        children[1..],
        objects,
        tuples,
        currentFacts,
        completedLowerFacts,
        query,
        lowerFuel,
        higherFuel
      );
    }
  }

  lemma ChildIndexesBelowMeansAllForExpression(
    children: seq<nat>,
    parent: nat
  )
    requires ChildIndexesBelow(children, parent)
    ensures forall child <- children :: child < parent
  {
  }

  // Ground rules are the finite semantic form of expression definitions.
  // A conjunction is one rule with an n-ary positive body.  A union is a set
  // of rules with a common head.  Exclusion is a positive body plus a strict
  // lower-stratum negative body.
  datatype GroundRule = GroundRule(
    head: PermissionFact,
    positiveBody: seq<PermissionFact>,
    negativeBody: seq<PermissionFact>,
    stratum: nat
  )

  function AllPresent(
    body: seq<PermissionFact>,
    facts: set<PermissionFact>
  ): bool
    decreases |body|
  {
    |body| == 0 ||
    (body[0] in facts && AllPresent(body[1..], facts))
  }

  function AllAbsent(
    body: seq<PermissionFact>,
    facts: set<PermissionFact>
  ): bool
    decreases |body|
  {
    |body| == 0 ||
    (body[0] !in facts && AllAbsent(body[1..], facts))
  }

  predicate RuleReady(
    rule: GroundRule,
    currentFacts: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>
  ) {
    AllPresent(rule.positiveBody, currentFacts) &&
    AllAbsent(rule.negativeBody, completedLowerFacts)
  }

  function ImmediateConsequences(
    rules: set<GroundRule>,
    stratum: nat,
    currentFacts: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>
  ): set<PermissionFact> {
    currentFacts +
    set rule <- rules |
        rule.stratum == stratum &&
        RuleReady(rule, currentFacts, completedLowerFacts) :: rule.head
  }

  lemma AllPresentIsMonotone(
    body: seq<PermissionFact>,
    smaller: set<PermissionFact>,
    larger: set<PermissionFact>
  )
    requires smaller <= larger
    requires AllPresent(body, smaller)
    ensures AllPresent(body, larger)
    decreases |body|
  {
    if |body| != 0 {
      AllPresentIsMonotone(body[1..], smaller, larger);
    }
  }

  lemma ImmediateConsequencesAreInflationary(
    rules: set<GroundRule>,
    stratum: nat,
    currentFacts: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>
  )
    ensures currentFacts <= ImmediateConsequences(
                              rules,
                              stratum,
                              currentFacts,
                              completedLowerFacts
                            )
  {
  }

  lemma ImmediateConsequencesAreMonotone(
    rules: set<GroundRule>,
    stratum: nat,
    smaller: set<PermissionFact>,
    larger: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>
  )
    requires smaller <= larger
    ensures ImmediateConsequences(
              rules,
              stratum,
              smaller,
              completedLowerFacts
            ) <=
            ImmediateConsequences(
              rules,
              stratum,
              larger,
              completedLowerFacts
            )
  {
    forall fact |
      fact in ImmediateConsequences(
                rules,
                stratum,
                smaller,
                completedLowerFacts
              )
      ensures fact in ImmediateConsequences(
                        rules,
                        stratum,
                        larger,
                        completedLowerFacts
                      )
    {
      if fact !in smaller {
        var rule :|
          rule in rules &&
          rule.stratum == stratum &&
          RuleReady(rule, smaller, completedLowerFacts) &&
          rule.head == fact;
        AllPresentIsMonotone(rule.positiveBody, smaller, larger);
      }
    }
  }

  predicate RuleUsesOnlyUniverse(
    rule: GroundRule,
    universe: set<PermissionFact>
  ) {
    rule.head in universe &&
    (forall fact <- rule.positiveBody :: fact in universe) &&
    (forall fact <- rule.negativeBody :: fact in universe)
  }

  predicate RulesUseOnlyUniverse(
    rules: set<GroundRule>,
    universe: set<PermissionFact>
  ) {
    forall rule <- rules :: RuleUsesOnlyUniverse(rule, universe)
  }

  predicate WellTypedPermissionUniverse(
    universe: set<PermissionFact>
  ) {
    forall fact <- universe :: WellTypedPermissionFact(fact)
  }

  lemma ImmediateConsequencesStayInUniverse(
    rules: set<GroundRule>,
    stratum: nat,
    currentFacts: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>,
    universe: set<PermissionFact>
  )
    requires RulesUseOnlyUniverse(rules, universe)
    requires currentFacts <= universe
    ensures ImmediateConsequences(
              rules,
              stratum,
              currentFacts,
              completedLowerFacts
            ) <= universe
  {
  }

  method IterateStratum(
    rules: set<GroundRule>,
    stratum: nat,
    strata: map<PermissionKey, nat>,
    universe: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>,
    currentFacts: set<PermissionFact>
  ) returns (facts: set<PermissionFact>)
    requires RulesUseOnlyUniverse(rules, universe)
    requires StrictlyStratifiedProgram(rules, strata)
    requires CompletedBelow(completedLowerFacts, strata, stratum)
    requires completedLowerFacts <= universe
    requires completedLowerFacts <= currentFacts <= universe
    requires FactsAtOrBelow(currentFacts, strata, stratum)
    ensures currentFacts <= facts <= universe
    ensures FactsAtOrBelow(facts, strata, stratum)
    ensures ImmediateConsequences(
              rules,
              stratum,
              facts,
              completedLowerFacts
            ) == facts
    ensures forall closed: set<PermissionFact> |
              currentFacts <= closed <= universe &&
              ImmediateConsequences(
                rules,
                stratum,
                closed,
                completedLowerFacts
              ) == closed ::
              facts <= closed
    decreases universe - currentFacts
  {
    var next := ImmediateConsequences(
      rules,
      stratum,
      currentFacts,
      completedLowerFacts
    );
    ImmediateConsequencesAreInflationary(
      rules,
      stratum,
      currentFacts,
      completedLowerFacts
    );
    ImmediateConsequencesStayInUniverse(
      rules,
      stratum,
      currentFacts,
      completedLowerFacts,
      universe
    );
    ImmediateConsequencesPreserveStratumBound(
      rules,
      stratum,
      strata,
      currentFacts,
      completedLowerFacts
    );
    assert currentFacts <= next <= universe;

    if next == currentFacts {
      return currentFacts;
    }

    assert currentFacts < next;
    assert universe - next < universe - currentFacts;
    facts := IterateStratum(
      rules,
      stratum,
      strata,
      universe,
      completedLowerFacts,
      next
    );

    forall closed: set<PermissionFact> |
      currentFacts <= closed <= universe &&
      ImmediateConsequences(
        rules,
        stratum,
        closed,
        completedLowerFacts
      ) == closed
      ensures facts <= closed
    {
      ImmediateConsequencesAreMonotone(
        rules,
        stratum,
        currentFacts,
        closed,
        completedLowerFacts
      );
      assert next <= closed;
    }
  }

  method EvaluateStratum(
    rules: set<GroundRule>,
    stratum: nat,
    strata: map<PermissionKey, nat>,
    universe: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>
  ) returns (facts: set<PermissionFact>)
    requires RulesUseOnlyUniverse(rules, universe)
    requires StrictlyStratifiedProgram(rules, strata)
    requires CompletedBelow(completedLowerFacts, strata, stratum)
    requires completedLowerFacts <= universe
    ensures completedLowerFacts <= facts <= universe
    ensures FactsAtOrBelow(facts, strata, stratum)
    ensures CompletedBelow(facts, strata, stratum + 1)
    ensures ImmediateConsequences(
              rules,
              stratum,
              facts,
              completedLowerFacts
            ) == facts
    ensures forall closed: set<PermissionFact> |
              completedLowerFacts <= closed <= universe &&
              ImmediateConsequences(
                rules,
                stratum,
                closed,
                completedLowerFacts
              ) == closed ::
              facts <= closed
  {
    CompletedBelowImpliesAtOrBelow(
      completedLowerFacts,
      strata,
      stratum
    );
    facts := IterateStratum(
      rules,
      stratum,
      strata,
      universe,
      completedLowerFacts,
      completedLowerFacts
    );
    FactsAtOrBelowIffCompletedBelowSuccessor(facts, strata, stratum);
  }

  predicate StrictlyStratifiedRule(
    rule: GroundRule,
    strata: map<PermissionKey, nat>
  ) {
    rule.head.permission in strata &&
    strata[rule.head.permission] == rule.stratum &&
    (forall fact <- rule.positiveBody ::
       fact.permission in strata &&
       strata[fact.permission] <= rule.stratum) &&
    (forall fact <- rule.negativeBody ::
       fact.permission in strata &&
       strata[fact.permission] < rule.stratum)
  }

  predicate StrictlyStratifiedProgram(
    rules: set<GroundRule>,
    strata: map<PermissionKey, nat>
  ) {
    forall rule <- rules :: StrictlyStratifiedRule(rule, strata)
  }

  predicate CompletedBelow(
    facts: set<PermissionFact>,
    strata: map<PermissionKey, nat>,
    stratum: nat
  ) {
    forall fact <- facts ::
      fact.permission in strata && strata[fact.permission] < stratum
  }

  predicate FactsAtOrBelow(
    facts: set<PermissionFact>,
    strata: map<PermissionKey, nat>,
    stratum: nat
  ) {
    forall fact <- facts ::
      fact.permission in strata &&
      strata[fact.permission] <= stratum
  }

  lemma CompletedBelowImpliesAtOrBelow(
    facts: set<PermissionFact>,
    strata: map<PermissionKey, nat>,
    stratum: nat
  )
    requires CompletedBelow(facts, strata, stratum)
    ensures FactsAtOrBelow(facts, strata, stratum)
  {
  }

  lemma FactsAtOrBelowIffCompletedBelowSuccessor(
    facts: set<PermissionFact>,
    strata: map<PermissionKey, nat>,
    stratum: nat
  )
    ensures FactsAtOrBelow(facts, strata, stratum) <==>
            CompletedBelow(facts, strata, stratum + 1)
  {
  }

  lemma ImmediateConsequencesPreserveStratumBound(
    rules: set<GroundRule>,
    stratum: nat,
    strata: map<PermissionKey, nat>,
    currentFacts: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>
  )
    requires StrictlyStratifiedProgram(rules, strata)
    requires FactsAtOrBelow(currentFacts, strata, stratum)
    ensures FactsAtOrBelow(
              ImmediateConsequences(
                rules,
                stratum,
                currentFacts,
                completedLowerFacts
              ),
              strata,
              stratum
            )
  {
    forall fact |
      fact in ImmediateConsequences(
                rules,
                stratum,
                currentFacts,
                completedLowerFacts
              )
      ensures fact.permission in strata &&
              strata[fact.permission] <= stratum
    {
      if fact !in currentFacts {
        var rule :|
          rule in rules &&
          rule.stratum == stratum &&
          RuleReady(rule, currentFacts, completedLowerFacts) &&
          rule.head == fact;
        assert StrictlyStratifiedRule(rule, strata);
        assert strata[fact.permission] == stratum;
      }
    }
  }

  lemma NegativePremisesUseCompletedLowerStrata(
    rule: GroundRule,
    strata: map<PermissionKey, nat>,
    completedLowerFacts: set<PermissionFact>
  )
    requires StrictlyStratifiedRule(rule, strata)
    requires CompletedBelow(completedLowerFacts, strata, rule.stratum)
    ensures forall fact <- rule.negativeBody ::
              fact.permission in strata &&
              strata[fact.permission] < rule.stratum
  {
  }

  predicate StratumFixed(
    rules: set<GroundRule>,
    stratum: nat,
    facts: set<PermissionFact>,
    completedLowerFacts: set<PermissionFact>
  ) {
    completedLowerFacts <= facts &&
    ImmediateConsequences(
      rules,
      stratum,
      facts,
      completedLowerFacts
    ) == facts
  }

  // Each trace entry is the completed fixed point of one stratum.  Crucially,
  // its negative premises are evaluated against the preceding trace entry,
  // never against a partial state from the stratum being computed.
  predicate StratifiedTrace(
    rules: set<GroundRule>,
    nextStratum: nat,
    completedLowerFacts: set<PermissionFact>,
    trace: seq<set<PermissionFact>>
  )
    decreases |trace|
  {
    |trace| == 0 ||
    (StratumFixed(
       rules,
       nextStratum,
       trace[0],
       completedLowerFacts
     ) &&
     StratifiedTrace(
       rules,
       nextStratum + 1,
       trace[0],
       trace[1..]
     ))
  }

  predicate CompletedStratifiedTrace(
    rules: set<GroundRule>,
    strata: map<PermissionKey, nat>,
    nextStratum: nat,
    completedLowerFacts: set<PermissionFact>,
    trace: seq<set<PermissionFact>>
  )
    decreases |trace|
  {
    if |trace| == 0 then
      CompletedBelow(completedLowerFacts, strata, nextStratum)
    else
      CompletedBelow(completedLowerFacts, strata, nextStratum) &&
      StratumFixed(
        rules,
        nextStratum,
        trace[0],
        completedLowerFacts
      ) &&
      CompletedBelow(trace[0], strata, nextStratum + 1) &&
      CompletedStratifiedTrace(
        rules,
        strata,
        nextStratum + 1,
        trace[0],
        trace[1..]
      )
  }

  method EvaluateStrata(
    rules: set<GroundRule>,
    universe: set<PermissionFact>,
    strata: map<PermissionKey, nat>,
    nextStratum: nat,
    stratumCount: nat,
    completedLowerFacts: set<PermissionFact>
  ) returns (
      completed: set<PermissionFact>,
      trace: seq<set<PermissionFact>>
    )
    requires RulesUseOnlyUniverse(rules, universe)
    requires StrictlyStratifiedProgram(rules, strata)
    requires CompletedBelow(completedLowerFacts, strata, nextStratum)
    requires completedLowerFacts <= universe
    ensures completedLowerFacts <= completed <= universe
    ensures CompletedBelow(
              completed,
              strata,
              nextStratum + stratumCount
            )
    ensures |trace| == stratumCount
    ensures StratifiedTrace(
              rules,
              nextStratum,
              completedLowerFacts,
              trace
            )
    ensures CompletedStratifiedTrace(
              rules,
              strata,
              nextStratum,
              completedLowerFacts,
              trace
            )
    ensures |trace| == 0 ==> completed == completedLowerFacts
    ensures |trace| != 0 ==> completed == trace[|trace| - 1]
    decreases stratumCount
  {
    if stratumCount == 0 {
      return completedLowerFacts, [];
    }

    var current := EvaluateStratum(
      rules,
      nextStratum,
      strata,
      universe,
      completedLowerFacts
    );
    var remaining, remainingTrace := EvaluateStrata(
      rules,
      universe,
      strata,
      nextStratum + 1,
      stratumCount - 1,
      current
    );
    completed := remaining;
    trace := [current] + remainingTrace;
  }

  method EvaluateProgram(
    rules: set<GroundRule>,
    universe: set<PermissionFact>,
    strata: map<PermissionKey, nat>,
    maximumStratum: nat
  ) returns (
      completed: set<PermissionFact>,
      trace: seq<set<PermissionFact>>
    )
    requires RulesUseOnlyUniverse(rules, universe)
    requires WellTypedPermissionUniverse(universe)
    requires StrictlyStratifiedProgram(rules, strata)
    requires forall rule <- rules :: rule.stratum <= maximumStratum
    ensures completed <= universe
    ensures WellTypedPermissionStore(completed)
    ensures CompletedBelow(completed, strata, maximumStratum + 1)
    ensures |trace| == maximumStratum + 1
    ensures StratifiedTrace(rules, 0, {}, trace)
    ensures CompletedStratifiedTrace(rules, strata, 0, {}, trace)
    ensures completed == trace[|trace| - 1]
  {
    completed, trace := EvaluateStrata(
      rules,
      universe,
      strata,
      0,
      maximumStratum + 1,
      {}
    );
  }
}

include "PermissionSetAlgebra.dfy"
include "CandidateCover.dfy"
include "WitnessPredicate.dfy"
include "StratifiedExclusion.dfy"

// Structural refinement from the typed source-expression table to the
// candidate-cover and scalar-predicate plan domain.  Leaf denotations are the
// explicit boundary supplied by exact relation, named-permission, and one-hop
// arrow evaluators; operator denotations are derived, never assumed.
module ExpressionPlanRefinement {
  import opened PSA = PermissionSetAlgebra
  import opened Cover = CandidateCover
  import opened Predicate = WitnessPredicate
  import Exclusion = StratifiedExclusion

  function CompiledNode(
    source: PSA.ExpressionNode,
    sourceIndex: nat,
    anchors: map<nat, nat>
  ): Cover.PlanNode {
    match source
    case RelationExpression(_) => Cover.LeafNode(sourceIndex)
    case PermissionExpression(_) => Cover.LeafNode(sourceIndex)
    case ArrowExpression(_, _) => Cover.LeafNode(sourceIndex)
    case UnionExpression(children) => Cover.UnionNode(children)
    case IntersectionExpression(children) =>
      Cover.IntersectionNode(
        children,
        if sourceIndex in anchors then anchors[sourceIndex] else 0
      )
    case ExclusionExpression(includeChild, excludeChild) =>
      Cover.ExclusionNode(includeChild, excludeChild)
  }

  function CompileTable(
    sources: seq<PSA.ExpressionNode>,
    anchors: map<nat, nat>
  ): seq<Cover.PlanNode> {
    seq(
    |sources|,
    index requires 0 <= index < |sources| =>
      CompiledNode(sources[index], index, anchors)
      )
  }

  predicate ValidIntersectionAnchors(
    sources: seq<PSA.ExpressionNode>,
    anchors: map<nat, nat>
  ) {
    forall index | 0 <= index < |sources| ::
      match sources[index]
      case IntersectionExpression(children) =>
        index in anchors && anchors[index] in children
      case _ => true
  }

  predicate SourceExpressionSemanticTable(
    sources: seq<PSA.ExpressionNode>,
    nodeResourceTypes: map<nat, nat>,
    denotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>
  ) {
    PSA.WellFormedTypedExpressionTable(sources, nodeResourceTypes) &&
    (forall index | 0 <= index < |sources| ::
       index in denotations && denotations[index] <= universe) &&
    (forall index | 0 <= index < |sources| ::
       match sources[index]
       case RelationExpression(_) => true
       case PermissionExpression(_) => true
       case ArrowExpression(_, _) => true
       case UnionExpression(children) =>
         denotations[index] ==
         Cover.UnionDenotations(children, denotations)
       case IntersectionExpression(children) =>
         denotations[index] ==
         Cover.IntersectionDenotations(
           children,
           denotations,
           universe
         )
       case ExclusionExpression(includeChild, excludeChild) =>
         denotations[index] ==
         Cover.DenotationAt(denotations, includeChild) -
         Cover.DenotationAt(denotations, excludeChild))
  }

  predicate CompleteBoundedLeafTable(
    sources: seq<PSA.ExpressionNode>,
    leafDenotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>
  ) {
    forall index | 0 <= index < |sources| ::
      sources[index].RelationExpression? ||
      sources[index].PermissionExpression? ||
      sources[index].ArrowExpression? ==>
        index in leafDenotations &&
        leafDenotations[index] <= universe
  }

  predicate LeafDenotationsPreserved(
    sources: seq<PSA.ExpressionNode>,
    denotations: map<nat, set<Cover.Entity>>,
    leafDenotations: map<nat, set<Cover.Entity>>
  ) {
    forall index | 0 <= index < |sources| ::
      sources[index].RelationExpression? ||
      sources[index].PermissionExpression? ||
      sources[index].ArrowExpression? ==>
        index in leafDenotations &&
        index in denotations &&
        denotations[index] == leafDenotations[index]
  }

  function DenotationForSourceNode(
    source: PSA.ExpressionNode,
    sourceIndex: nat,
    completed: map<nat, set<Cover.Entity>>,
    leafDenotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>
  ): set<Cover.Entity> {
    match source
    case RelationExpression(_) =>
      Cover.DenotationAt(leafDenotations, sourceIndex)
    case PermissionExpression(_) =>
      Cover.DenotationAt(leafDenotations, sourceIndex)
    case ArrowExpression(_, _) =>
      Cover.DenotationAt(leafDenotations, sourceIndex)
    case UnionExpression(children) =>
      Cover.UnionDenotations(children, completed)
    case IntersectionExpression(children) =>
      Cover.IntersectionDenotations(children, completed, universe)
    case ExclusionExpression(includeChild, excludeChild) =>
      Cover.DenotationAt(completed, includeChild) -
      Cover.DenotationAt(completed, excludeChild)
  }

  function EvaluateFromLeafDenotations(
    sources: seq<PSA.ExpressionNode>,
    leafDenotations: map<nat, set<Cover.Entity>>,
    node: nat,
    entity: Cover.Entity,
    fuel: nat
  ): bool
    decreases fuel, 0, 0
  {
    if fuel == 0 || node >= |sources| then
      false
    else
      match sources[node]
      case RelationExpression(_) =>
        entity in Cover.DenotationAt(leafDenotations, node)
      case PermissionExpression(_) =>
        entity in Cover.DenotationAt(leafDenotations, node)
      case ArrowExpression(_, _) =>
        entity in Cover.DenotationAt(leafDenotations, node)
      case UnionExpression(children) =>
        AnyLeafChildHolds(
          sources,
          leafDenotations,
          children,
          entity,
          fuel - 1
        )
      case IntersectionExpression(children) =>
        AllLeafChildrenHold(
          sources,
          leafDenotations,
          children,
          entity,
          fuel - 1
        )
      case ExclusionExpression(includeChild, excludeChild) =>
        EvaluateFromLeafDenotations(
          sources,
          leafDenotations,
          includeChild,
          entity,
          fuel - 1
        ) &&
        !EvaluateFromLeafDenotations(
          sources,
          leafDenotations,
          excludeChild,
          entity,
          fuel - 1
        )
  }

  function AnyLeafChildHolds(
    sources: seq<PSA.ExpressionNode>,
    leafDenotations: map<nat, set<Cover.Entity>>,
    children: seq<nat>,
    entity: Cover.Entity,
    fuel: nat
  ): bool
    decreases fuel, 1, |children|
  {
    |children| != 0 &&
    (EvaluateFromLeafDenotations(
       sources,
       leafDenotations,
       children[0],
       entity,
       fuel
     ) ||
     AnyLeafChildHolds(
       sources,
       leafDenotations,
       children[1..],
       entity,
       fuel
     ))
  }

  function AllLeafChildrenHold(
    sources: seq<PSA.ExpressionNode>,
    leafDenotations: map<nat, set<Cover.Entity>>,
    children: seq<nat>,
    entity: Cover.Entity,
    fuel: nat
  ): bool
    decreases fuel, 1, |children|
  {
    |children| == 0 ||
    (EvaluateFromLeafDenotations(
       sources,
       leafDenotations,
       children[0],
       entity,
       fuel
     ) &&
     AllLeafChildrenHold(
       sources,
       leafDenotations,
       children[1..],
       entity,
       fuel
     ))
  }

  lemma SourceDenotationMatchesExecutableLeafTraversal(
    sources: seq<PSA.ExpressionNode>,
    nodeResourceTypes: map<nat, nat>,
    denotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    node: nat,
    entity: Cover.Entity,
    fuel: nat
  )
    requires SourceExpressionSemanticTable(
               sources,
               nodeResourceTypes,
               denotations,
               universe
             )
    requires node < |sources|
    requires node < fuel
    requires entity in universe
    ensures entity in denotations[node] <==>
            EvaluateFromLeafDenotations(
              sources,
              denotations,
              node,
              entity,
              fuel
            )
    decreases fuel, 0, 0
  {
    assert PSA.WellFormedNode(sources, node);
    match sources[node]
    case RelationExpression(_) =>
    case PermissionExpression(_) =>
    case ArrowExpression(_, _) =>
    case UnionExpression(children) =>
      assert forall child <- children :: child < node < fuel;
      AnyLeafChildrenMatchUnionDenotation(
        sources,
        nodeResourceTypes,
        denotations,
        universe,
        children,
        entity,
        fuel - 1
      );
    case IntersectionExpression(children) =>
      assert forall child <- children :: child < node < fuel;
      AllLeafChildrenMatchIntersectionDenotation(
        sources,
        nodeResourceTypes,
        denotations,
        universe,
        children,
        entity,
        fuel - 1
      );
    case ExclusionExpression(includeChild, excludeChild) =>
      SourceDenotationMatchesExecutableLeafTraversal(
        sources,
        nodeResourceTypes,
        denotations,
        universe,
        includeChild,
        entity,
        fuel - 1
      );
      SourceDenotationMatchesExecutableLeafTraversal(
        sources,
        nodeResourceTypes,
        denotations,
        universe,
        excludeChild,
        entity,
        fuel - 1
      );
  }

  lemma AnyLeafChildrenMatchUnionDenotation(
    sources: seq<PSA.ExpressionNode>,
    nodeResourceTypes: map<nat, nat>,
    denotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    children: seq<nat>,
    entity: Cover.Entity,
    fuel: nat
  )
    requires SourceExpressionSemanticTable(
               sources,
               nodeResourceTypes,
               denotations,
               universe
             )
    requires entity in universe
    requires forall child <- children :: child < |sources| && child < fuel
    ensures entity in Cover.UnionDenotations(children, denotations) <==>
            AnyLeafChildHolds(
              sources,
              denotations,
              children,
              entity,
              fuel
            )
    decreases fuel, 1, |children|
  {
    if |children| != 0 {
      assert children[0] in children;
      SourceDenotationMatchesExecutableLeafTraversal(
        sources,
        nodeResourceTypes,
        denotations,
        universe,
        children[0],
        entity,
        fuel
      );
      forall child | child in children[1..]
        ensures child < |sources| && child < fuel
      {
        assert child in children;
      }
      AnyLeafChildrenMatchUnionDenotation(
        sources,
        nodeResourceTypes,
        denotations,
        universe,
        children[1..],
        entity,
        fuel
      );
    }
  }

  lemma AllLeafChildrenMatchIntersectionDenotation(
    sources: seq<PSA.ExpressionNode>,
    nodeResourceTypes: map<nat, nat>,
    denotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    children: seq<nat>,
    entity: Cover.Entity,
    fuel: nat
  )
    requires SourceExpressionSemanticTable(
               sources,
               nodeResourceTypes,
               denotations,
               universe
             )
    requires entity in universe
    requires forall child <- children :: child < |sources| && child < fuel
    ensures entity in Cover.IntersectionDenotations(
                        children,
                        denotations,
                        universe
                      ) <==>
            AllLeafChildrenHold(
              sources,
              denotations,
              children,
              entity,
              fuel
            )
    decreases fuel, 1, |children|
  {
    if |children| != 0 {
      assert children[0] in children;
      SourceDenotationMatchesExecutableLeafTraversal(
        sources,
        nodeResourceTypes,
        denotations,
        universe,
        children[0],
        entity,
        fuel
      );
      forall child | child in children[1..]
        ensures child < |sources| && child < fuel
      {
        assert child in children;
      }
      AllLeafChildrenMatchIntersectionDenotation(
        sources,
        nodeResourceTypes,
        denotations,
        universe,
        children[1..],
        entity,
        fuel
      );
    }
  }

  lemma IntersectionDenotationsStayInUniverse(
    children: seq<nat>,
    denotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>
  )
    requires forall child <- children ::
               child in denotations && denotations[child] <= universe
    ensures Cover.IntersectionDenotations(
              children,
              denotations,
              universe
            ) <= universe
    decreases |children|
  {
    if |children| != 0 {
      forall child | child in children[1..]
        ensures child in denotations && denotations[child] <= universe
      {
        assert child in children;
      }
      IntersectionDenotationsStayInUniverse(
        children[1..],
        denotations,
        universe
      );
    }
  }

  lemma UnionDenotationsStayInUniverse(
    children: seq<nat>,
    denotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>
  )
    requires forall child <- children ::
               child in denotations && denotations[child] <= universe
    ensures Cover.UnionDenotations(children, denotations) <= universe
    decreases |children|
  {
    if |children| != 0 {
      forall child | child in children[1..]
        ensures child in denotations && denotations[child] <= universe
      {
        assert child in children;
      }
      UnionDenotationsStayInUniverse(
        children[1..],
        denotations,
        universe
      );
    }
  }

  lemma LaterMapUpdateDoesNotChangeSourceNode(
    sources: seq<PSA.ExpressionNode>,
    sourceIndex: nat,
    completed: map<nat, set<Cover.Entity>>,
    leafDenotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    laterIndex: nat,
    laterValue: set<Cover.Entity>
  )
    requires PSA.WellFormedNode(sources, sourceIndex)
    requires sourceIndex <= laterIndex
    ensures DenotationForSourceNode(
              sources[sourceIndex],
              sourceIndex,
              completed[laterIndex := laterValue],
              leafDenotations,
              universe
            ) ==
            DenotationForSourceNode(
              sources[sourceIndex],
              sourceIndex,
              completed,
              leafDenotations,
              universe
            )
  {
    match sources[sourceIndex]
    case RelationExpression(_) =>
    case PermissionExpression(_) =>
    case ArrowExpression(_, _) =>
    case UnionExpression(children) =>
      assert forall child <- children :: child < sourceIndex <= laterIndex;
      UnionDenotationsUnaffectedByLaterUpdate(
        children,
        completed,
        laterIndex,
        laterValue
      );
    case IntersectionExpression(children) =>
      assert forall child <- children :: child < sourceIndex <= laterIndex;
      IntersectionDenotationsUnaffectedByLaterUpdate(
        children,
        completed,
        universe,
        laterIndex,
        laterValue
      );
    case ExclusionExpression(includeChild, excludeChild) =>
      assert includeChild < sourceIndex <= laterIndex;
      assert excludeChild < sourceIndex <= laterIndex;
  }

  lemma UnionDenotationsUnaffectedByLaterUpdate(
    children: seq<nat>,
    denotations: map<nat, set<Cover.Entity>>,
    laterIndex: nat,
    laterValue: set<Cover.Entity>
  )
    requires forall child <- children :: child < laterIndex
    ensures Cover.UnionDenotations(
              children,
              denotations[laterIndex := laterValue]
            ) ==
            Cover.UnionDenotations(children, denotations)
    decreases |children|
  {
    if |children| != 0 {
      assert children[0] in children;
      assert children[0] < laterIndex;
      assert Cover.DenotationAt(
          denotations[laterIndex := laterValue],
          children[0]
        ) == Cover.DenotationAt(denotations, children[0]);
      forall child | child in children[1..]
        ensures child < laterIndex
      {
        assert child in children;
      }
      UnionDenotationsUnaffectedByLaterUpdate(
        children[1..],
        denotations,
        laterIndex,
        laterValue
      );
      assert Cover.UnionDenotations(
          children,
          denotations[laterIndex := laterValue]
        ) ==
             Cover.DenotationAt(
               denotations[laterIndex := laterValue],
               children[0]
             ) +
             Cover.UnionDenotations(
               children[1..],
               denotations[laterIndex := laterValue]
             );
    }
  }

  lemma IntersectionDenotationsUnaffectedByLaterUpdate(
    children: seq<nat>,
    denotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    laterIndex: nat,
    laterValue: set<Cover.Entity>
  )
    requires forall child <- children :: child < laterIndex
    ensures Cover.IntersectionDenotations(
              children,
              denotations[laterIndex := laterValue],
              universe
            ) ==
            Cover.IntersectionDenotations(
              children,
              denotations,
              universe
            )
    decreases |children|
  {
    if |children| != 0 {
      assert children[0] in children;
      assert children[0] < laterIndex;
      assert Cover.DenotationAt(
          denotations[laterIndex := laterValue],
          children[0]
        ) == Cover.DenotationAt(denotations, children[0]);
      forall child | child in children[1..]
        ensures child < laterIndex
      {
        assert child in children;
      }
      IntersectionDenotationsUnaffectedByLaterUpdate(
        children[1..],
        denotations,
        universe,
        laterIndex,
        laterValue
      );
      assert Cover.IntersectionDenotations(
          children,
          denotations[laterIndex := laterValue],
          universe
        ) ==
             Cover.DenotationAt(
               denotations[laterIndex := laterValue],
               children[0]
             ) *
             Cover.IntersectionDenotations(
               children[1..],
               denotations[laterIndex := laterValue],
               universe
             );
    }
  }

  predicate SourceExpressionSemanticPrefix(
    sources: seq<PSA.ExpressionNode>,
    leafDenotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    index: nat,
    denotations: map<nat, set<Cover.Entity>>
  ) {
    index <= |sources| &&
    forall completedIndex | 0 <= completedIndex < index ::
      completedIndex in denotations &&
      denotations[completedIndex] <= universe &&
      denotations[completedIndex] ==
      DenotationForSourceNode(
        sources[completedIndex],
        completedIndex,
        denotations,
        leafDenotations,
        universe
      )
  }

  lemma CompletedPrefixIsSourceExpressionSemanticTable(
    sources: seq<PSA.ExpressionNode>,
    nodeResourceTypes: map<nat, nat>,
    leafDenotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    denotations: map<nat, set<Cover.Entity>>
  )
    requires PSA.WellFormedTypedExpressionTable(
               sources,
               nodeResourceTypes
             )
    requires SourceExpressionSemanticPrefix(
               sources,
               leafDenotations,
               universe,
               |sources|,
               denotations
             )
    ensures SourceExpressionSemanticTable(
              sources,
              nodeResourceTypes,
              denotations,
              universe
            )
  {
    forall index | 0 <= index < |sources|
      ensures match sources[index]
              case RelationExpression(_) => true
              case PermissionExpression(_) => true
              case ArrowExpression(_, _) => true
              case UnionExpression(children) =>
                denotations[index] ==
                Cover.UnionDenotations(children, denotations)
              case IntersectionExpression(children) =>
                denotations[index] ==
                Cover.IntersectionDenotations(
                  children,
                  denotations,
                  universe
                )
              case ExclusionExpression(includeChild, excludeChild) =>
                denotations[index] ==
                Cover.DenotationAt(denotations, includeChild) -
                Cover.DenotationAt(denotations, excludeChild)
    {
      assert denotations[index] ==
             DenotationForSourceNode(
               sources[index],
               index,
               denotations,
               leafDenotations,
               universe
             );
    }
  }

  lemma CompletedPrefixPreservesEveryLeafDenotation(
    sources: seq<PSA.ExpressionNode>,
    leafDenotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    denotations: map<nat, set<Cover.Entity>>
  )
    requires CompleteBoundedLeafTable(
               sources,
               leafDenotations,
               universe
             )
    requires SourceExpressionSemanticPrefix(
               sources,
               leafDenotations,
               universe,
               |sources|,
               denotations
             )
    ensures LeafDenotationsPreserved(
              sources,
              denotations,
              leafDenotations
            )
  {
    forall index | 0 <= index < |sources|
      ensures sources[index].RelationExpression? ||
              sources[index].PermissionExpression? ||
              sources[index].ArrowExpression? ==>
                index in leafDenotations &&
                index in denotations &&
                denotations[index] == leafDenotations[index]
    {
      if sources[index].RelationExpression? ||
         sources[index].PermissionExpression? ||
         sources[index].ArrowExpression? {
        assert denotations[index] ==
               DenotationForSourceNode(
                 sources[index],
                 index,
                 denotations,
                 leafDenotations,
                 universe
               );
      }
    }
  }

  method BuildSourceExpressionSemanticTableFrom(
    sources: seq<PSA.ExpressionNode>,
    nodeResourceTypes: map<nat, nat>,
    leafDenotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    index: nat,
    current: map<nat, set<Cover.Entity>>
  ) returns (denotations: map<nat, set<Cover.Entity>>)
    requires PSA.WellFormedTypedExpressionTable(
               sources,
               nodeResourceTypes
             )
    requires CompleteBoundedLeafTable(
               sources,
               leafDenotations,
               universe
             )
    requires SourceExpressionSemanticPrefix(
               sources,
               leafDenotations,
               universe,
               index,
               current
             )
    ensures SourceExpressionSemanticTable(
              sources,
              nodeResourceTypes,
              denotations,
              universe
            )
    ensures LeafDenotationsPreserved(
              sources,
              denotations,
              leafDenotations
            )
    decreases |sources| - index
  {
    if index == |sources| {
      CompletedPrefixIsSourceExpressionSemanticTable(
        sources,
        nodeResourceTypes,
        leafDenotations,
        universe,
        current
      );
      CompletedPrefixPreservesEveryLeafDenotation(
        sources,
        leafDenotations,
        universe,
        current
      );
      return current;
    }
    assert index < |sources|;
    var value := DenotationForSourceNode(
      sources[index],
      index,
      current,
      leafDenotations,
      universe
    );
    assert PSA.WellFormedNode(sources, index);
    match sources[index] {
      case RelationExpression(_) =>
        assert index in leafDenotations;
        assert value <= universe;
      case PermissionExpression(_) =>
        assert index in leafDenotations;
        assert value <= universe;
      case ArrowExpression(_, _) =>
        assert index in leafDenotations;
        assert value <= universe;
      case UnionExpression(children) =>
        assert forall child <- children :: child < index;
        assert forall child <- children ::
            child in current && current[child] <= universe;
        UnionDenotationsStayInUniverse(
          children,
          current,
          universe
        );
        assert value <= universe;
      case IntersectionExpression(children) =>
        assert forall child <- children :: child < index;
        assert forall child <- children ::
            child in current && current[child] <= universe;
        IntersectionDenotationsStayInUniverse(
          children,
          current,
          universe
        );
        assert value <= universe;
      case ExclusionExpression(includeChild, _) =>
        assert includeChild < index;
        assert includeChild in current;
        assert current[includeChild] <= universe;
        assert value <= universe;
    }
    var next := current[index := value];
    forall completedIndex | 0 <= completedIndex < index
      ensures DenotationForSourceNode(
                sources[completedIndex],
                completedIndex,
                next,
                leafDenotations,
                universe
              ) ==
              DenotationForSourceNode(
                sources[completedIndex],
                completedIndex,
                current,
                leafDenotations,
                universe
              )
    {
      assert PSA.WellFormedNode(sources, completedIndex);
      LaterMapUpdateDoesNotChangeSourceNode(
        sources,
        completedIndex,
        current,
        leafDenotations,
        universe,
        index,
        value
      );
    }
    LaterMapUpdateDoesNotChangeSourceNode(
      sources,
      index,
      current,
      leafDenotations,
      universe,
      index,
      value
    );
    assert SourceExpressionSemanticPrefix(
        sources,
        leafDenotations,
        universe,
        index + 1,
        next
      );
    denotations := BuildSourceExpressionSemanticTableFrom(
      sources,
      nodeResourceTypes,
      leafDenotations,
      universe,
      index + 1,
      next
    );
  }

  method BuildSourceExpressionSemanticTable(
    sources: seq<PSA.ExpressionNode>,
    nodeResourceTypes: map<nat, nat>,
    leafDenotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>
  ) returns (denotations: map<nat, set<Cover.Entity>>)
    requires PSA.WellFormedTypedExpressionTable(
               sources,
               nodeResourceTypes
             )
    requires CompleteBoundedLeafTable(
               sources,
               leafDenotations,
               universe
             )
    ensures SourceExpressionSemanticTable(
              sources,
              nodeResourceTypes,
              denotations,
              universe
            )
    ensures LeafDenotationsPreserved(
              sources,
              denotations,
              leafDenotations
            )
  {
    assert SourceExpressionSemanticPrefix(
        sources,
        leafDenotations,
        universe,
        0,
        map[]
      );
    denotations := BuildSourceExpressionSemanticTableFrom(
      sources,
      nodeResourceTypes,
      leafDenotations,
      universe,
      0,
      map[]
    );
  }

  datatype Projection =
    | ResourcesForSubject(subject: PSA.ObjectRef)
    | SubjectsForResource(resource: PSA.ObjectRef)

  function ToObject(entity: Cover.Entity): PSA.ObjectRef {
    PSA.ObjectRef(entity.typeId, entity.entityId)
  }

  function QueryFor(
    projection: Projection,
    entity: Cover.Entity
  ): PSA.ExpressionQuery {
    match projection
    case ResourcesForSubject(subject) =>
      PSA.ExpressionQuery(subject, ToObject(entity))
    case SubjectsForResource(resource) =>
      PSA.ExpressionQuery(ToObject(entity), resource)
  }

  // Only leaf values are supplied at this boundary.  The source semantic
  // table above derives every operator value from those exact leaves.
  predicate ExactExecutableLeafDenotations(
    sources: seq<PSA.ExpressionNode>,
    denotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    objects: seq<PSA.ObjectRef>,
    tuples: set<PSA.RelationTuple>,
    currentFacts: set<PSA.PermissionFact>,
    completedLowerFacts: set<PSA.PermissionFact>,
    projection: Projection
  ) {
    PSA.CompleteObjectCatalog(objects, tuples, currentFacts) &&
    completedLowerFacts <= currentFacts &&
    (forall index | 0 <= index < |sources| ::
       sources[index].RelationExpression? ||
       sources[index].PermissionExpression? ||
       sources[index].ArrowExpression? ==>
         index in denotations &&
         forall entity <- universe ::
           entity in denotations[index] <==>
                     PSA.EvaluateExpression(
                       sources,
                       index,
                       objects,
                       tuples,
                       currentFacts,
                       completedLowerFacts,
                       QueryFor(projection, entity),
                       |sources| + 1
                     ))
  }

  lemma ExpressionChildrenCompileBelow(
    children: seq<nat>,
    parent: nat
  )
    requires PSA.ChildIndexesBelow(children, parent)
    ensures Cover.ChildIndexesBelow(children, parent)
    decreases |children|
  {
    if |children| != 0 {
      assert children[0] in children;
      assert children[0] < parent;
      forall child | child in children[1..]
        ensures child < parent
      {
        assert child in children;
      }
      assert PSA.ChildIndexesBelow(children[1..], parent);
      ExpressionChildrenCompileBelow(children[1..], parent);
    }
  }

  lemma CompiledTableIsWellFormed(
    sources: seq<PSA.ExpressionNode>,
    nodeResourceTypes: map<nat, nat>,
    anchors: map<nat, nat>
  )
    requires PSA.WellFormedTypedExpressionTable(
               sources,
               nodeResourceTypes
             )
    requires ValidIntersectionAnchors(sources, anchors)
    ensures Cover.WellFormedTable(CompileTable(sources, anchors))
  {
    forall index | 0 <= index < |sources|
      ensures Cover.WellFormedNode(
                CompileTable(sources, anchors),
                index
              )
    {
      assert |CompileTable(sources, anchors)| == |sources|;
      assert CompileTable(sources, anchors)[index] ==
             CompiledNode(sources[index], index, anchors);
      assert PSA.WellFormedNode(sources, index);
      match sources[index]
      case RelationExpression(_) =>
      case PermissionExpression(_) =>
      case ArrowExpression(_, _) =>
      case UnionExpression(children) =>
        ExpressionChildrenCompileBelow(children, index);
      case IntersectionExpression(children) =>
        ExpressionChildrenCompileBelow(children, index);
      case ExclusionExpression(_, _) =>
    }
  }

  lemma CompiledLeafDenotationMatchesExecutableLeaf(
    sources: seq<PSA.ExpressionNode>,
    anchors: map<nat, nat>,
    denotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    objects: seq<PSA.ObjectRef>,
    tuples: set<PSA.RelationTuple>,
    currentFacts: set<PSA.PermissionFact>,
    completedLowerFacts: set<PSA.PermissionFact>,
    projection: Projection,
    leaf: nat,
    entity: Cover.Entity
  )
    requires leaf < |sources|
    requires entity in universe
    requires sources[leaf].RelationExpression? ||
             sources[leaf].PermissionExpression? ||
             sources[leaf].ArrowExpression?
    requires ExactExecutableLeafDenotations(
               sources,
               denotations,
               universe,
               objects,
               tuples,
               currentFacts,
               completedLowerFacts,
               projection
             )
    ensures CompileTable(sources, anchors)[leaf].LeafNode?
    ensures entity in denotations[leaf] <==>
            PSA.EvaluateExpression(
              sources,
              leaf,
              objects,
              tuples,
              currentFacts,
              completedLowerFacts,
              QueryFor(projection, entity),
              |sources| + 1
            )
  {
  }

  lemma SourceExpressionSemanticsRefinesCompiledPlan(
    sources: seq<PSA.ExpressionNode>,
    nodeResourceTypes: map<nat, nat>,
    anchors: map<nat, nat>,
    denotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>
  )
    requires SourceExpressionSemanticTable(
               sources,
               nodeResourceTypes,
               denotations,
               universe
             )
    requires ValidIntersectionAnchors(sources, anchors)
    ensures Cover.SemanticTable(
              CompileTable(sources, anchors),
              denotations,
              universe
            )
  {
    CompiledTableIsWellFormed(sources, nodeResourceTypes, anchors);
    forall index | 0 <= index < |sources|
      ensures match CompileTable(sources, anchors)[index]
              case LeafNode(_) => true
              case UnionNode(children) =>
                denotations[index] ==
                Cover.UnionDenotations(children, denotations)
              case IntersectionNode(children, _) =>
                denotations[index] ==
                Cover.IntersectionDenotations(
                  children,
                  denotations,
                  universe
                )
              case ExclusionNode(includeChild, excludeChild) =>
                denotations[index] ==
                Cover.DenotationAt(denotations, includeChild) -
                Cover.DenotationAt(denotations, excludeChild)
    {
      assert CompileTable(sources, anchors)[index] ==
             CompiledNode(sources[index], index, anchors);
    }
  }

  lemma CompiledCandidateCoverContainsSourceExpressionDenotation(
    sources: seq<PSA.ExpressionNode>,
    nodeResourceTypes: map<nat, nat>,
    anchors: map<nat, nat>,
    denotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    root: nat
  )
    requires SourceExpressionSemanticTable(
               sources,
               nodeResourceTypes,
               denotations,
               universe
             )
    requires ValidIntersectionAnchors(sources, anchors)
    requires root < |sources|
    ensures denotations[root] <=
            Cover.CandidateCover(
              CompileTable(sources, anchors),
              denotations,
              root
            )
  {
    SourceExpressionSemanticsRefinesCompiledPlan(
      sources,
      nodeResourceTypes,
      anchors,
      denotations,
      universe
    );
    Cover.CandidateCoverContainsDenotation(
      CompileTable(sources, anchors),
      denotations,
      universe,
      root
    );
  }

  lemma CompiledScalarPredicateIsExactForSourceExpression(
    sources: seq<PSA.ExpressionNode>,
    nodeResourceTypes: map<nat, nat>,
    anchors: map<nat, nat>,
    denotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    root: nat,
    entity: Cover.Entity,
    evidence: Predicate.Witness,
    derivationId: nat
  )
    requires SourceExpressionSemanticTable(
               sources,
               nodeResourceTypes,
               denotations,
               universe
             )
    requires ValidIntersectionAnchors(sources, anchors)
    requires root < |sources|
    requires entity in universe
    requires evidence.entity == entity
    requires Predicate.SoundWitness(evidence, denotations)
    ensures Predicate.EvaluateScalar(
              CompileTable(sources, anchors),
              denotations,
              root,
              entity,
              evidence,
              derivationId,
              root + 1
            ).allowed <==>
            entity in denotations[root]
  {
    SourceExpressionSemanticsRefinesCompiledPlan(
      sources,
      nodeResourceTypes,
      anchors,
      denotations,
      universe
    );
    Predicate.ScalarDecisionIsExact(
      CompileTable(sources, anchors),
      denotations,
      universe,
      root,
      entity,
      evidence,
      derivationId,
      root + 1
    );
  }

  lemma CompiledScalarPredicateRefinesExecutableLeafTraversal(
    sources: seq<PSA.ExpressionNode>,
    nodeResourceTypes: map<nat, nat>,
    anchors: map<nat, nat>,
    denotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    root: nat,
    entity: Cover.Entity,
    evidence: Predicate.Witness,
    derivationId: nat
  )
    requires SourceExpressionSemanticTable(
               sources,
               nodeResourceTypes,
               denotations,
               universe
             )
    requires ValidIntersectionAnchors(sources, anchors)
    requires root < |sources|
    requires entity in universe
    requires evidence.entity == entity
    requires Predicate.SoundWitness(evidence, denotations)
    ensures Predicate.EvaluateScalar(
              CompileTable(sources, anchors),
              denotations,
              root,
              entity,
              evidence,
              derivationId,
              root + 1
            ).allowed ==
            EvaluateFromLeafDenotations(
              sources,
              denotations,
              root,
              entity,
              root + 1
            )
  {
    CompiledScalarPredicateIsExactForSourceExpression(
      sources,
      nodeResourceTypes,
      anchors,
      denotations,
      universe,
      root,
      entity,
      evidence,
      derivationId
    );
    SourceDenotationMatchesExecutableLeafTraversal(
      sources,
      nodeResourceTypes,
      denotations,
      universe,
      root,
      entity,
      root + 1
    );
  }

  lemma CompiledExclusionMatchesCompletedLowerStratum(
    sources: seq<PSA.ExpressionNode>,
    nodeResourceTypes: map<nat, nat>,
    denotations: map<nat, set<Cover.Entity>>,
    universe: set<Cover.Entity>,
    root: nat,
    parentStratum: nat,
    negativeStratum: nat
  )
    requires SourceExpressionSemanticTable(
               sources,
               nodeResourceTypes,
               denotations,
               universe
             )
    requires root < |sources|
    requires sources[root].ExclusionExpression?
    requires sources[root].includeChild in denotations
    requires sources[root].excludeChild in denotations
    requires negativeStratum < parentStratum
    ensures var includeChild := sources[root].includeChild;
            var excludeChild := sources[root].excludeChild;
            Exclusion.EvaluateExclusion(
              parentStratum,
              negativeStratum,
              Exclusion.ExactComplete(denotations[includeChild]),
              Exclusion.ExactComplete(denotations[excludeChild])
            ) == Exclusion.ExclusionComplete(denotations[root])
  {
    var includeChild := sources[root].includeChild;
    var excludeChild := sources[root].excludeChild;
    assert PSA.WellFormedNode(sources, root);
    assert includeChild < root;
    assert excludeChild < root;
    assert includeChild in denotations;
    assert excludeChild in denotations;
    assert denotations[root] ==
           denotations[includeChild] - denotations[excludeChild];
    Exclusion.CompletedLowerStratumExclusionIsExact(
      parentStratum,
      negativeStratum,
      denotations[includeChild],
      denotations[excludeChild]
    );
  }
}

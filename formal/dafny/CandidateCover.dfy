// Recursive candidate covers and exact witness-carrying generators.
module CandidateCover {
  datatype Entity = Entity(typeId: nat, entityId: nat)

  datatype PlanNode =
    | LeafNode(leafId: nat)
    | UnionNode(children: seq<nat>)
    | IntersectionNode(children: seq<nat>, anchor: nat)
    | ExclusionNode(includeChild: nat, excludeChild: nat)

  predicate ChildIndexesBelow(children: seq<nat>, parent: nat)
    decreases |children|
  {
    |children| == 0 ||
    (children[0] < parent &&
     ChildIndexesBelow(children[1..], parent))
  }

  lemma ChildIndexesBelowMeansAll(
    children: seq<nat>,
    parent: nat
  )
    requires ChildIndexesBelow(children, parent)
    ensures forall child <- children :: child < parent
    decreases |children|
  {
    if |children| != 0 {
      ChildIndexesBelowMeansAll(children[1..], parent);
      forall child | child in children
        ensures child < parent
      {
        if child != children[0] {
          assert child in children[1..];
        }
      }
    }
  }

  predicate WellFormedNode(nodes: seq<PlanNode>, index: nat) {
    index < |nodes| &&
    match nodes[index]
    case LeafNode(_) => true
    case UnionNode(children) =>
      0 < |children| && ChildIndexesBelow(children, index)
    case IntersectionNode(children, anchor) =>
      0 < |children| &&
      ChildIndexesBelow(children, index) &&
      anchor in children
    case ExclusionNode(includeChild, excludeChild) =>
      includeChild < index && excludeChild < index
  }

  predicate WellFormedTable(nodes: seq<PlanNode>) {
    forall index | 0 <= index < |nodes| :: WellFormedNode(nodes, index)
  }

  function DenotationAt(
    denotations: map<nat, set<Entity>>,
    index: nat
  ): set<Entity> {
    if index in denotations then denotations[index] else {}
  }

  function UnionDenotations(
    children: seq<nat>,
    denotations: map<nat, set<Entity>>
  ): set<Entity>
    decreases |children|
  {
    if |children| == 0 then
      {}
    else
      DenotationAt(denotations, children[0]) +
      UnionDenotations(children[1..], denotations)
  }

  function IntersectionDenotations(
    children: seq<nat>,
    denotations: map<nat, set<Entity>>,
    universe: set<Entity>
  ): set<Entity>
    decreases |children|
  {
    if |children| == 0 then
      universe
    else
      DenotationAt(denotations, children[0]) *
      IntersectionDenotations(
        children[1..],
        denotations,
        universe
      )
  }

  predicate SemanticTable(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    universe: set<Entity>
  ) {
    WellFormedTable(nodes) &&
    (forall index | 0 <= index < |nodes| ::
       index in denotations && denotations[index] <= universe) &&
    (forall index | 0 <= index < |nodes| ::
       match nodes[index]
       case LeafNode(_) => true
       case UnionNode(children) =>
         denotations[index] ==
         UnionDenotations(children, denotations)
       case IntersectionNode(children, _) =>
         denotations[index] ==
         IntersectionDenotations(children, denotations, universe)
       case ExclusionNode(includeChild, excludeChild) =>
         denotations[index] ==
         DenotationAt(denotations, includeChild) -
         DenotationAt(denotations, excludeChild))
  }

  // Fuel makes this total even for hostile input.  A well-formed canonical
  // DAG needs at most nodeIndex + 1 frames because every child index is lower.
  function RawCandidateCover(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    nodeIndex: nat,
    fuel: nat
  ): set<Entity>
    decreases fuel, 0
  {
    if fuel == 0 || nodeIndex >= |nodes| then
      {}
    else
      match nodes[nodeIndex]
      case LeafNode(_) => DenotationAt(denotations, nodeIndex)
      case UnionNode(children) =>
        UnionChildCovers(nodes, denotations, children, fuel - 1)
      case IntersectionNode(_, anchor) =>
        RawCandidateCover(nodes, denotations, anchor, fuel - 1)
      case ExclusionNode(includeChild, _) =>
        RawCandidateCover(
          nodes,
          denotations,
          includeChild,
          fuel - 1
        )
  }

  function UnionChildCovers(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    children: seq<nat>,
    fuel: nat
  ): set<Entity>
    decreases fuel, 1, |children|
  {
    if |children| == 0 then
      {}
    else
      RawCandidateCover(nodes, denotations, children[0], fuel) +
      UnionChildCovers(
        nodes,
        denotations,
        children[1..],
        fuel
      )
  }

  function CandidateCover(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    nodeIndex: nat
  ): set<Entity> {
    RawCandidateCover(nodes, denotations, nodeIndex, nodeIndex + 1)
  }

  lemma IntersectionIsSubsetOfMember(
    children: seq<nat>,
    denotations: map<nat, set<Entity>>,
    universe: set<Entity>,
    member: nat
  )
    requires member in children
    ensures IntersectionDenotations(
              children,
              denotations,
              universe
            ) <= DenotationAt(denotations, member)
    decreases |children|
  {
    if children[0] != member {
      IntersectionIsSubsetOfMember(
        children[1..],
        denotations,
        universe,
        member
      );
    }
  }

  lemma UnionChildCoverContainsDenotation(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    universe: set<Entity>,
    children: seq<nat>,
    fuel: nat
  )
    requires SemanticTable(nodes, denotations, universe)
    requires forall child <- children :: child < |nodes| && child < fuel
    ensures UnionDenotations(children, denotations) <=
            UnionChildCovers(nodes, denotations, children, fuel)
    decreases fuel, 1, |children|
  {
    if |children| != 0 {
      assert children[0] in children;
      assert children[0] < |nodes| && children[0] < fuel;
      CandidateCoverContainsWithFuel(
        nodes,
        denotations,
        universe,
        children[0],
        fuel
      );
      forall child | child in children[1..]
        ensures child < |nodes| && child < fuel
      {
        assert child in children;
      }
      UnionChildCoverContainsDenotation(
        nodes,
        denotations,
        universe,
        children[1..],
        fuel
      );
    }
  }

  lemma CandidateCoverContainsWithFuel(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    universe: set<Entity>,
    nodeIndex: nat,
    fuel: nat
  )
    requires SemanticTable(nodes, denotations, universe)
    requires nodeIndex < |nodes|
    requires nodeIndex < fuel
    ensures denotations[nodeIndex] <=
            RawCandidateCover(nodes, denotations, nodeIndex, fuel)
    decreases fuel, 0
  {
    assert WellFormedNode(nodes, nodeIndex);
    match nodes[nodeIndex]
    case LeafNode(_) =>
    case UnionNode(children) =>
      assert ChildIndexesBelow(children, nodeIndex);
      ChildIndexesBelowMeansAll(children, nodeIndex);
      assert forall child <- children ::
          child < |nodes| && child < fuel - 1;
      UnionChildCoverContainsDenotation(
        nodes,
        denotations,
        universe,
        children,
        fuel - 1
      );
    case IntersectionNode(children, anchor) =>
      assert ChildIndexesBelow(children, nodeIndex);
      ChildIndexesBelowMeansAll(children, nodeIndex);
      assert anchor in children;
      assert anchor < |nodes| && anchor < fuel - 1;
      CandidateCoverContainsWithFuel(
        nodes,
        denotations,
        universe,
        anchor,
        fuel - 1
      );
      IntersectionIsSubsetOfMember(
        children,
        denotations,
        universe,
        anchor
      );
      assert denotations[nodeIndex] <= denotations[anchor];
    case ExclusionNode(includeChild, _) =>
      assert includeChild < |nodes| && includeChild < fuel - 1;
      CandidateCoverContainsWithFuel(
        nodes,
        denotations,
        universe,
        includeChild,
        fuel - 1
      );
      assert denotations[nodeIndex] <= denotations[includeChild];
  }

  lemma CandidateCoverContainsDenotation(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    universe: set<Entity>,
    nodeIndex: nat
  )
    requires SemanticTable(nodes, denotations, universe)
    requires nodeIndex < |nodes|
    ensures denotations[nodeIndex] <=
            CandidateCover(nodes, denotations, nodeIndex)
  {
    CandidateCoverContainsWithFuel(
      nodes,
      denotations,
      universe,
      nodeIndex,
      nodeIndex + 1
    );
  }

  predicate UnionOnlyTable(nodes: seq<PlanNode>) {
    forall node <- nodes :: node.LeafNode? || node.UnionNode?
  }

  function LegacyUnionCover(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    nodeIndex: nat,
    fuel: nat
  ): set<Entity>
    decreases fuel, 0
  {
    if fuel == 0 || nodeIndex >= |nodes| then
      {}
    else
      match nodes[nodeIndex]
      case LeafNode(_) => DenotationAt(denotations, nodeIndex)
      case UnionNode(children) =>
        LegacyUnionChildCovers(
          nodes,
          denotations,
          children,
          fuel - 1
        )
      case IntersectionNode(_, _) => {}
      case ExclusionNode(_, _) => {}
  }

  function LegacyUnionChildCovers(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    children: seq<nat>,
    fuel: nat
  ): set<Entity>
    decreases fuel, 1, |children|
  {
    if |children| == 0 then
      {}
    else
      LegacyUnionCover(nodes, denotations, children[0], fuel) +
      LegacyUnionChildCovers(
        nodes,
        denotations,
        children[1..],
        fuel
      )
  }

  lemma UnionOnlyChildrenRetainLegacyIdentity(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    children: seq<nat>,
    fuel: nat
  )
    requires UnionOnlyTable(nodes)
    ensures UnionChildCovers(nodes, denotations, children, fuel) ==
            LegacyUnionChildCovers(
              nodes,
              denotations,
              children,
              fuel
            )
    decreases fuel, 1, |children|
  {
    if |children| != 0 {
      UnionOnlyCoverRetainsLegacyIdentity(
        nodes,
        denotations,
        children[0],
        fuel
      );
      UnionOnlyChildrenRetainLegacyIdentity(
        nodes,
        denotations,
        children[1..],
        fuel
      );
    }
  }

  lemma UnionOnlyCoverRetainsLegacyIdentity(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    nodeIndex: nat,
    fuel: nat
  )
    requires UnionOnlyTable(nodes)
    ensures RawCandidateCover(nodes, denotations, nodeIndex, fuel) ==
            LegacyUnionCover(nodes, denotations, nodeIndex, fuel)
    decreases fuel, 0
  {
    if fuel != 0 && nodeIndex < |nodes| {
      assert nodes[nodeIndex] in nodes;
      assert nodes[nodeIndex].LeafNode? || nodes[nodeIndex].UnionNode?;
      match nodes[nodeIndex]
      case LeafNode(_) =>
      case UnionNode(children) =>
        UnionOnlyChildrenRetainLegacyIdentity(
          nodes,
          denotations,
          children,
          fuel - 1
        );
      case IntersectionNode(_, _) =>
        assert false;
      case ExclusionNode(_, _) =>
        assert false;
    }
  }

  datatype Emission = Emission(
    entity: Entity,
    trueNodes: set<nat>
  )

  function EmittedSet(emissions: seq<Emission>): set<Entity>
    decreases |emissions|
  {
    if |emissions| == 0 then
      {}
    else
      {emissions[0].entity} + EmittedSet(emissions[1..])
  }

  predicate UniqueEmissions(emissions: seq<Emission>)
    decreases |emissions|
  {
    |emissions| == 0 ||
    (emissions[0].entity !in EmittedSet(emissions[1..]) &&
     UniqueEmissions(emissions[1..]))
  }

  predicate WitnessesSound(
    emissions: seq<Emission>,
    denotations: map<nat, set<Entity>>
  ) {
    forall emission <- emissions, node <- emission.trueNodes ::
      node in denotations && emission.entity in denotations[node]
  }

  predicate ExactGenerator(
    emissions: seq<Emission>,
    node: nat,
    denotations: map<nat, set<Entity>>
  ) {
    node in denotations &&
    UniqueEmissions(emissions) &&
    EmittedSet(emissions) == denotations[node] &&
    WitnessesSound(emissions, denotations) &&
    (forall emission <- emissions :: node in emission.trueNodes)
  }

  // The branch test is the local exact predicate.  A parent witness bit is
  // constructed only in the true branch, never from raw cover membership.
  function FilterChildAndIssueParentWitness(
    childEmissions: seq<Emission>,
    parent: nat,
    parentDenotation: set<Entity>
  ): seq<Emission>
    decreases |childEmissions|
  {
    if |childEmissions| == 0 then
      []
    else
      (if childEmissions[0].entity in parentDenotation then
         [Emission(
            childEmissions[0].entity,
            childEmissions[0].trueNodes + {parent}
          )]
       else
         []) +
      FilterChildAndIssueParentWitness(
        childEmissions[1..],
        parent,
        parentDenotation
      )
  }

  lemma FilteredParentEmissionMembership(
    childEmissions: seq<Emission>,
    parent: nat,
    parentDenotation: set<Entity>,
    entity: Entity
  )
    ensures entity in EmittedSet(
                        FilterChildAndIssueParentWitness(
                          childEmissions,
                          parent,
                          parentDenotation
                        )
                      ) <==>
            entity in EmittedSet(childEmissions) &&
            entity in parentDenotation
    decreases |childEmissions|
  {
    if |childEmissions| != 0 {
      FilteredParentEmissionMembership(
        childEmissions[1..],
        parent,
        parentDenotation,
        entity
      );
      if childEmissions[0].entity in parentDenotation {
        if entity == childEmissions[0].entity {
          assert entity in EmittedSet(childEmissions);
        }
      } else {
        assert FilterChildAndIssueParentWitness(
            childEmissions,
            parent,
            parentDenotation
          ) ==
               FilterChildAndIssueParentWitness(
                 childEmissions[1..],
                 parent,
                 parentDenotation
               );
        if entity == childEmissions[0].entity {
          assert entity !in parentDenotation;
          assert entity !in EmittedSet(
              FilterChildAndIssueParentWitness(
                childEmissions[1..],
                parent,
                parentDenotation
              )
            );
          assert entity in EmittedSet(childEmissions);
        } else {
          assert entity in EmittedSet(childEmissions) <==>
                 entity in EmittedSet(childEmissions[1..]);
        }
      }
    }
  }

  lemma FilteredParentEmissionSet(
    childEmissions: seq<Emission>,
    parent: nat,
    parentDenotation: set<Entity>
  )
    ensures EmittedSet(
              FilterChildAndIssueParentWitness(
                childEmissions,
                parent,
                parentDenotation
              )
            ) ==
            EmittedSet(childEmissions) * parentDenotation
    decreases |childEmissions|
  {
    forall entity: Entity
      ensures entity in EmittedSet(
                          FilterChildAndIssueParentWitness(
                            childEmissions,
                            parent,
                            parentDenotation
                          )
                        ) <==>
              entity in EmittedSet(childEmissions) * parentDenotation
    {
      FilteredParentEmissionMembership(
        childEmissions,
        parent,
        parentDenotation,
        entity
      );
    }
  }

  lemma FilteredParentWitnessIsIssuedOnlyAfterExactMembership(
    childEmissions: seq<Emission>,
    parent: nat,
    parentDenotation: set<Entity>,
    emission: Emission
  )
    requires emission in FilterChildAndIssueParentWitness(
                           childEmissions,
                           parent,
                           parentDenotation
                         )
    ensures emission.entity in parentDenotation
    ensures parent in emission.trueNodes
    ensures exists childEmission <- childEmissions ::
              childEmission.entity == emission.entity &&
              emission.trueNodes == childEmission.trueNodes + {parent}
    decreases |childEmissions|
  {
    if |childEmissions| != 0 &&
       !(childEmissions[0].entity in parentDenotation &&
         emission.entity == childEmissions[0].entity &&
         emission.trueNodes ==
         childEmissions[0].trueNodes + {parent}) {
      FilteredParentWitnessIsIssuedOnlyAfterExactMembership(
        childEmissions[1..],
        parent,
        parentDenotation,
        emission
      );
    }
  }

  lemma FilteringPreservesUniqueEmissions(
    childEmissions: seq<Emission>,
    parent: nat,
    parentDenotation: set<Entity>
  )
    requires UniqueEmissions(childEmissions)
    ensures UniqueEmissions(
              FilterChildAndIssueParentWitness(
                childEmissions,
                parent,
                parentDenotation
              )
            )
    decreases |childEmissions|
  {
    if |childEmissions| != 0 {
      FilteringPreservesUniqueEmissions(
        childEmissions[1..],
        parent,
        parentDenotation
      );
      if childEmissions[0].entity in parentDenotation {
        assert childEmissions[0].entity !in
          EmittedSet(childEmissions[1..]);
        FilteredParentEmissionMembership(
          childEmissions[1..],
          parent,
          parentDenotation,
          childEmissions[0].entity
        );
        assert childEmissions[0].entity !in EmittedSet(
            FilterChildAndIssueParentWitness(
              childEmissions[1..],
              parent,
              parentDenotation
            )
          );
      } else {
        assert FilterChildAndIssueParentWitness(
            childEmissions,
            parent,
            parentDenotation
          ) ==
               FilterChildAndIssueParentWitness(
                 childEmissions[1..],
                 parent,
                 parentDenotation
               );
      }
    }
  }

  lemma FilteringPreservesAndExtendsSoundWitnesses(
    childEmissions: seq<Emission>,
    parent: nat,
    denotations: map<nat, set<Entity>>
  )
    requires WitnessesSound(childEmissions, denotations)
    requires parent in denotations
    ensures WitnessesSound(
              FilterChildAndIssueParentWitness(
                childEmissions,
                parent,
                denotations[parent]
              ),
              denotations
            )
    decreases |childEmissions|
  {
    if |childEmissions| != 0 {
      FilteringPreservesAndExtendsSoundWitnesses(
        childEmissions[1..],
        parent,
        denotations
      );
      if childEmissions[0].entity in denotations[parent] {
        forall node <- childEmissions[0].trueNodes + {parent}
          ensures node in denotations &&
                  childEmissions[0].entity in denotations[node]
        {
          if node != parent {
            assert node in childEmissions[0].trueNodes;
            assert childEmissions[0] in childEmissions;
          }
        }
      }
    }
  }

  lemma RecursivelyFilteredChildGeneratorIsExact(
    childEmissions: seq<Emission>,
    child: nat,
    parent: nat,
    denotations: map<nat, set<Entity>>
  )
    requires ExactGenerator(childEmissions, child, denotations)
    requires parent in denotations
    requires denotations[parent] <= denotations[child]
    ensures ExactGenerator(
              FilterChildAndIssueParentWitness(
                childEmissions,
                parent,
                denotations[parent]
              ),
              parent,
              denotations
            )
    ensures EmittedSet(
              FilterChildAndIssueParentWitness(
                childEmissions,
                parent,
                denotations[parent]
              )
            ) == denotations[parent]
    ensures forall emission <-
                     FilterChildAndIssueParentWitness(
                       childEmissions,
                       parent,
                       denotations[parent]
                     ) ::
              parent in emission.trueNodes &&
              emission.entity in denotations[parent]
  {
    FilteringPreservesUniqueEmissions(
      childEmissions,
      parent,
      denotations[parent]
    );
    FilteringPreservesAndExtendsSoundWitnesses(
      childEmissions,
      parent,
      denotations
    );
    FilteredParentEmissionSet(
      childEmissions,
      parent,
      denotations[parent]
    );
    forall emission <-
             FilterChildAndIssueParentWitness(
               childEmissions,
               parent,
               denotations[parent]
             )
      ensures parent in emission.trueNodes &&
              emission.entity in denotations[parent]
    {
      FilteredParentWitnessIsIssuedOnlyAfterExactMembership(
        childEmissions,
        parent,
        denotations[parent],
        emission
      );
    }
  }
}

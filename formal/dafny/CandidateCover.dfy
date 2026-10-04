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

  // ---------------------------------------------------------------------
  // Plain witnesses
  // ---------------------------------------------------------------------
  //
  // Production mapping. The stable reducer marks a work item once its
  // first-discovery path crosses an edge with a qualifier slot
  // (eacl.engine.stable-reducer: qualified-edge?, path-qualified?, the
  // :qualified-path? mark), and an emitted candidate without the mark is
  // plain. For a plain candidate eacl.engine.v8 hands the vector evaluator
  // `true` at the generator node (eacl.operator.cover-plan/
  // plain-witness-node): the node the root's cover follows down to, when it
  // is a leaf or a union of leaves and every generator row derives one of
  // those leaves exactly (exact-generator-leaf?). The evaluator then decides
  // only the operands the chain passed.
  //
  // A world is one evaluation time and one caveat context. It decides every
  // qualified edge as true, false or unknown (a residual or a fault). An
  // ordinary edge has no qualifier and holds in every world, so what a plain
  // path proves has no deadline. Values compose with the strong Kleene
  // connectives.

  datatype Truth = No | Unknown | Yes

  function Or3(a: Truth, b: Truth): Truth {
    if a.Yes? || b.Yes? then Yes
    else if a.Unknown? || b.Unknown? then Unknown
    else No
  }

  function And3(a: Truth, b: Truth): Truth {
    if a.No? || b.No? then No
    else if a.Unknown? || b.Unknown? then Unknown
    else Yes
  }

  function Not3(a: Truth): Truth {
    if a.Yes? then No else if a.No? then Yes else Unknown
  }

  // The qualifier slot of a compact edge: empty for an ordinary edge, or the
  // qualifier a world decides.
  datatype Slot = Plain | Qualifier(id: nat)

  type World = nat -> Truth

  function SlotTruth(world: World, slot: Slot): Truth {
    match slot
    case Plain => Yes
    case Qualifier(id) => world(id)
  }

  // A path holds as the conjunction of its edges, in discovery order.
  function PathTruth(world: World, path: seq<Slot>): Truth
    decreases |path|
  {
    if |path| == 0 then
      Yes
    else
      And3(
        PathTruth(world, path[..|path| - 1]),
        SlotTruth(world, path[|path| - 1])
      )
  }

  // The mark the reducer carries along a path: the first edge with a
  // qualifier slot sets it and every successor keeps it.
  function PathMark(path: seq<Slot>): bool
    decreases |path|
  {
    if |path| == 0 then
      false
    else
      PathMark(path[..|path| - 1]) || path[|path| - 1].Qualifier?
  }

  lemma {:induction false} UnmarkedPathHoldsInEveryWorld(
    world: World,
    path: seq<Slot>
  )
    requires !PathMark(path)
    ensures PathTruth(world, path) == Yes
    decreases |path|
  {
    if |path| != 0 {
      UnmarkedPathHoldsInEveryWorld(world, path[..|path| - 1]);
    }
  }

  // Both parts of the mark are needed. A mark that ignores the qualifier
  // slot, or one that a later ordinary edge clears, leaves a path unmarked
  // that fails in some world.
  function SlotBlindMark(path: seq<Slot>): bool {
    false
  }

  function LastEdgeMark(path: seq<Slot>): bool {
    |path| != 0 && path[|path| - 1].Qualifier?
  }

  lemma MarkMustReadTheQualifierSlot()
    ensures exists world: World, path: seq<Slot> ::
              !SlotBlindMark(path) && PathTruth(world, path) == No
  {
    var world: World := (id: nat) => No;
    var path := [Qualifier(0)];
    assert path[..0] == [];
    assert PathTruth(world, path) ==
           And3(PathTruth(world, []), SlotTruth(world, Qualifier(0)));
    assert !SlotBlindMark(path) && PathTruth(world, path) == No;
  }

  lemma MarkMustSurviveALaterPlainEdge()
    ensures exists world: World, path: seq<Slot> ::
              !LastEdgeMark(path) && PathTruth(world, path) == No
  {
    var world: World := (id: nat) => No;
    var path := [Qualifier(0), Plain];
    assert path[..1] == [Qualifier(0)];
    assert path[..1][..0] == [];
    assert PathTruth(world, [Qualifier(0)]) ==
           And3(PathTruth(world, []), SlotTruth(world, Qualifier(0)));
    assert PathTruth(world, path) ==
           And3(PathTruth(world, [Qualifier(0)]), SlotTruth(world, Plain));
    assert !LastEdgeMark(path) && PathTruth(world, path) == No;
  }

  // The value of a plan node for one candidate in one world, from the
  // values of its leaves.
  function NodeTruth(
    nodes: seq<PlanNode>,
    leaf: map<nat, Truth>,
    index: nat,
    fuel: nat
  ): Truth
    decreases fuel, 0
  {
    if fuel == 0 || index >= |nodes| then
      No
    else
      match nodes[index]
      case LeafNode(_) => if index in leaf then leaf[index] else No
      case UnionNode(children) =>
        AnyTruth(nodes, leaf, children, fuel - 1)
      case IntersectionNode(children, _) =>
        AllTruth(nodes, leaf, children, fuel - 1)
      case ExclusionNode(includeChild, excludeChild) =>
        And3(
          NodeTruth(nodes, leaf, includeChild, fuel - 1),
          Not3(NodeTruth(nodes, leaf, excludeChild, fuel - 1))
        )
  }

  function AnyTruth(
    nodes: seq<PlanNode>,
    leaf: map<nat, Truth>,
    children: seq<nat>,
    fuel: nat
  ): Truth
    decreases fuel, 1, |children|
  {
    if |children| == 0 then
      No
    else
      Or3(
        NodeTruth(nodes, leaf, children[0], fuel),
        AnyTruth(nodes, leaf, children[1..], fuel)
      )
  }

  function AllTruth(
    nodes: seq<PlanNode>,
    leaf: map<nat, Truth>,
    children: seq<nat>,
    fuel: nat
  ): Truth
    decreases fuel, 1, |children|
  {
    if |children| == 0 then
      Yes
    else
      And3(
        NodeTruth(nodes, leaf, children[0], fuel),
        AllTruth(nodes, leaf, children[1..], fuel)
      )
  }

  // The node the root's cover follows down to: each exclusion's included
  // operand and each intersection's anchor, to a leaf or a union. The second
  // component is the fuel left there.
  function GeneratorNode(
    nodes: seq<PlanNode>,
    index: nat,
    fuel: nat
  ): (nat, nat)
    decreases fuel
  {
    if fuel == 0 || index >= |nodes| then
      (index, fuel)
    else
      match nodes[index]
      case IntersectionNode(_, anchor) =>
        GeneratorNode(nodes, anchor, fuel - 1)
      case ExclusionNode(includeChild, _) =>
        GeneratorNode(nodes, includeChild, fuel - 1)
      case LeafNode(_) => (index, fuel)
      case UnionNode(_) => (index, fuel)
  }

  lemma {:induction false} GeneratorNodeHasTheRootsCover(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    index: nat,
    fuel: nat
  )
    ensures RawCandidateCover(nodes, denotations, index, fuel) ==
            RawCandidateCover(
              nodes,
              denotations,
              GeneratorNode(nodes, index, fuel).0,
              GeneratorNode(nodes, index, fuel).1
            )
    decreases fuel
  {
    if fuel != 0 && index < |nodes| {
      match nodes[index]
      case IntersectionNode(_, anchor) =>
        GeneratorNodeHasTheRootsCover(nodes, denotations, anchor, fuel - 1);
      case ExclusionNode(includeChild, _) =>
        GeneratorNodeHasTheRootsCover(
          nodes,
          denotations,
          includeChild,
          fuel - 1
        );
      case LeafNode(_) =>
      case UnionNode(_) =>
    }
  }

  // A leaf, or a union of such nodes. Its cover leaves are its own leaves,
  // and it holds wherever one of them does.
  predicate LeafUnion(nodes: seq<PlanNode>, index: nat, fuel: nat)
    decreases fuel, 0
  {
    fuel != 0 && index < |nodes| &&
    match nodes[index]
    case LeafNode(_) => true
    case UnionNode(children) => LeafUnions(nodes, children, fuel - 1)
    case IntersectionNode(_, _) => false
    case ExclusionNode(_, _) => false
  }

  predicate LeafUnions(nodes: seq<PlanNode>, children: seq<nat>, fuel: nat)
    decreases fuel, 1, |children|
  {
    |children| == 0 ||
    (LeafUnion(nodes, children[0], fuel) &&
     LeafUnions(nodes, children[1..], fuel))
  }

  predicate LeafOf(
    nodes: seq<PlanNode>,
    index: nat,
    leafIndex: nat,
    fuel: nat
  )
    decreases fuel, 0
  {
    fuel != 0 && index < |nodes| &&
    match nodes[index]
    case LeafNode(_) => index == leafIndex
    case UnionNode(children) =>
      LeafOfSome(nodes, children, leafIndex, fuel - 1)
    case IntersectionNode(_, _) => false
    case ExclusionNode(_, _) => false
  }

  predicate LeafOfSome(
    nodes: seq<PlanNode>,
    children: seq<nat>,
    leafIndex: nat,
    fuel: nat
  )
    decreases fuel, 1, |children|
  {
    |children| != 0 &&
    (LeafOf(nodes, children[0], leafIndex, fuel) ||
     LeafOfSome(nodes, children[1..], leafIndex, fuel))
  }

  lemma {:induction false} LeafUnionHoldsWhereALeafDoes(
    nodes: seq<PlanNode>,
    leaf: map<nat, Truth>,
    index: nat,
    leafIndex: nat,
    fuel: nat
  )
    requires LeafOf(nodes, index, leafIndex, fuel)
    requires leafIndex in leaf && leaf[leafIndex] == Yes
    ensures NodeTruth(nodes, leaf, index, fuel) == Yes
    decreases fuel, 0
  {
    match nodes[index]
    case LeafNode(_) =>
    case UnionNode(children) =>
      SomeChildHoldsWhereALeafDoes(
        nodes,
        leaf,
        children,
        leafIndex,
        fuel - 1
      );
    case IntersectionNode(_, _) =>
    case ExclusionNode(_, _) =>
  }

  lemma {:induction false} SomeChildHoldsWhereALeafDoes(
    nodes: seq<PlanNode>,
    leaf: map<nat, Truth>,
    children: seq<nat>,
    leafIndex: nat,
    fuel: nat
  )
    requires LeafOfSome(nodes, children, leafIndex, fuel)
    requires leafIndex in leaf && leaf[leafIndex] == Yes
    ensures AnyTruth(nodes, leaf, children, fuel) == Yes
    decreases fuel, 1, |children|
  {
    if LeafOf(nodes, children[0], leafIndex, fuel) {
      LeafUnionHoldsWhereALeafDoes(
        nodes,
        leaf,
        children[0],
        leafIndex,
        fuel
      );
    } else {
      SomeChildHoldsWhereALeafDoes(
        nodes,
        leaf,
        children[1..],
        leafIndex,
        fuel
      );
    }
  }

  // The plain-witness lemma. `path` is the first-discovery path of a
  // candidate through a row of leaf `leafIndex` of the generator node. The
  // leaf is exact: a path its rows trace is one of its derivations, so the
  // leaf holds in a world where the path does. An unmarked path holds in
  // every world, so the generator node does too, whatever the other leaves
  // decide in that world.
  lemma PlainCoverWitnessProvesGeneratorNode(
    nodes: seq<PlanNode>,
    generator: nat,
    fuel: nat,
    leafIndex: nat,
    path: seq<Slot>,
    world: World,
    leaf: map<nat, Truth>
  )
    requires LeafUnion(nodes, generator, fuel)
    requires LeafOf(nodes, generator, leafIndex, fuel)
    requires PathTruth(world, path) == Yes ==>
               leafIndex in leaf && leaf[leafIndex] == Yes
    requires !PathMark(path)
    ensures NodeTruth(nodes, leaf, generator, fuel) == Yes
  {
    UnmarkedPathHoldsInEveryWorld(world, path);
    LeafUnionHoldsWhereALeafDoes(nodes, leaf, generator, leafIndex, fuel);
  }

  // With the generator node proved, an exclusion is decided by its excluded
  // operand alone and an anchored intersection by its other operands.
  lemma WitnessedExclusionIsDecidedByItsExcludedOperand(
    nodes: seq<PlanNode>,
    leaf: map<nat, Truth>,
    index: nat,
    fuel: nat
  )
    requires fuel != 0 && index < |nodes| && nodes[index].ExclusionNode?
    requires NodeTruth(
               nodes,
               leaf,
               nodes[index].includeChild,
               fuel - 1
             ) == Yes
    ensures NodeTruth(nodes, leaf, index, fuel) ==
            Not3(NodeTruth(nodes, leaf, nodes[index].excludeChild, fuel - 1))
  {
  }

  lemma {:induction false} WitnessedOperandDropsOutOfAnIntersection(
    nodes: seq<PlanNode>,
    leaf: map<nat, Truth>,
    children: seq<nat>,
    position: nat,
    fuel: nat
  )
    requires position < |children|
    requires NodeTruth(nodes, leaf, children[position], fuel) == Yes
    ensures AllTruth(nodes, leaf, children, fuel) ==
            AllTruth(
              nodes,
              leaf,
              children[..position] + children[position + 1..],
              fuel
            )
    decreases position
  {
    if position == 0 {
      assert children[..0] + children[1..] == children[1..];
    } else {
      var rest := children[..position] + children[position + 1..];
      assert rest[0] == children[0];
      assert rest[1..] ==
             children[1..][..position - 1] + children[1..][position..];
      WitnessedOperandDropsOutOfAnIntersection(
        nodes,
        leaf,
        children[1..],
        position - 1,
        fuel
      );
    }
  }

  // The generator node must be a union of leaves. Under a union with an
  // operator child, a leaf the cover reaches (the child's included operand)
  // can hold plainly while the union does not.
  lemma CoverLeafDoesNotProveAnOperatorUnion()
    ensures exists nodes: seq<PlanNode>, leaf: map<nat, Truth> ::
              WellFormedTable(nodes) &&
              !LeafUnion(nodes, 3, 4) &&
              0 in leaf && leaf[0] == Yes &&
              NodeTruth(nodes, leaf, 3, 4) == No
  {
    var nodes := [
      LeafNode(0),
      LeafNode(1),
      ExclusionNode(0, 1),
      UnionNode([2])
    ];
    var leaf := map[0 := Yes, 1 := Yes];
    assert ChildIndexesBelow([2], 3) by {
      assert [2][1..] == [];
    }
    assert WellFormedTable(nodes);
    assert NodeTruth(nodes, leaf, 2, 3) == No;
    assert [2][1..] == [];
    assert AnyTruth(nodes, leaf, [2], 3) ==
           Or3(NodeTruth(nodes, leaf, 2, 3), AnyTruth(nodes, leaf, [], 3));
    assert NodeTruth(nodes, leaf, 3, 4) == No;
    assert !LeafUnion(nodes, 3, 4);
  }
}

include "CandidateCover.dfy"

// Scalar predicate semantics with derivation-scoped reusable witnesses.
module WitnessPredicate {
  import opened CandidateCover

  datatype Witness = Witness(
    entity: Entity,
    derivationId: nat,
    trueNodes: set<nat>
  )

  datatype ScalarDecision = ScalarDecision(
    allowed: bool,
    membershipProbes: nat
  )

  predicate SoundWitness(
    evidence: Witness,
    denotations: map<nat, set<Entity>>
  ) {
    forall node <- evidence.trueNodes ::
      node in denotations && evidence.entity in denotations[node]
  }

  function WitnessFromEmission(
    emission: Emission,
    derivationId: nat
  ): Witness {
    Witness(emission.entity, derivationId, emission.trueNodes)
  }

  lemma SoundGeneratorEmissionProducesSoundDerivationWitness(
    emissions: seq<Emission>,
    emission: Emission,
    derivationId: nat,
    denotations: map<nat, set<Entity>>
  )
    requires emission in emissions
    requires WitnessesSound(emissions, denotations)
    ensures SoundWitness(
              WitnessFromEmission(emission, derivationId),
              denotations
            )
    ensures WitnessFromEmission(emission, derivationId).entity ==
            emission.entity
    ensures WitnessFromEmission(emission, derivationId).derivationId ==
            derivationId
    ensures WitnessFromEmission(emission, derivationId).trueNodes ==
            emission.trueNodes
  {
  }

  function ReusableWitness(
    evidence: Witness,
    entity: Entity,
    node: nat,
    derivationId: nat
  ): bool {
    evidence.entity == entity &&
    evidence.derivationId == derivationId &&
    node in evidence.trueNodes
  }

  function EvaluateScalar(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    nodeIndex: nat,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  ): ScalarDecision
    decreases fuel, 0
  {
    if fuel == 0 || nodeIndex >= |nodes| then
      ScalarDecision(false, 0)
    else if ReusableWitness(
              evidence,
              entity,
              nodeIndex,
              derivationId
            ) then
      ScalarDecision(true, 0)
    else
      match nodes[nodeIndex]
      case LeafNode(_) =>
        ScalarDecision(
          entity in DenotationAt(denotations, nodeIndex),
          1
        )
      case UnionNode(children) =>
        EvaluateAnyChild(
          nodes,
          denotations,
          children,
          entity,
          evidence,
          derivationId,
          fuel - 1
        )
      case IntersectionNode(children, _) =>
        EvaluateAllChildren(
          nodes,
          denotations,
          children,
          entity,
          evidence,
          derivationId,
          fuel - 1
        )
      case ExclusionNode(includeChild, excludeChild) =>
        var includeDecision := EvaluateScalar(
                                 nodes,
                                 denotations,
                                 includeChild,
                                 entity,
                                 evidence,
                                 derivationId,
                                 fuel - 1
                               );
        if !includeDecision.allowed then
          includeDecision
        else
          var excludeDecision := EvaluateScalar(
                                   nodes,
                                   denotations,
                                   excludeChild,
                                   entity,
                                   evidence,
                                   derivationId,
                                   fuel - 1
                                 );
          ScalarDecision(
            !excludeDecision.allowed,
            includeDecision.membershipProbes +
            excludeDecision.membershipProbes
          )
  }

  function EvaluateAnyChild(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    children: seq<nat>,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  ): ScalarDecision
    decreases fuel, 1, |children|
  {
    if |children| == 0 then
      ScalarDecision(false, 0)
    else
      var first := EvaluateScalar(
                     nodes,
                     denotations,
                     children[0],
                     entity,
                     evidence,
                     derivationId,
                     fuel
                   );
      if first.allowed then
        first
      else
        var remaining := EvaluateAnyChild(
                           nodes,
                           denotations,
                           children[1..],
                           entity,
                           evidence,
                           derivationId,
                           fuel
                         );
        ScalarDecision(
          remaining.allowed,
          first.membershipProbes + remaining.membershipProbes
        )
  }

  function EvaluateAllChildren(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    children: seq<nat>,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  ): ScalarDecision
    decreases fuel, 1, |children|
  {
    if |children| == 0 then
      ScalarDecision(true, 0)
    else
      var first := EvaluateScalar(
                     nodes,
                     denotations,
                     children[0],
                     entity,
                     evidence,
                     derivationId,
                     fuel
                   );
      if !first.allowed then
        first
      else
        var remaining := EvaluateAllChildren(
                           nodes,
                           denotations,
                           children[1..],
                           entity,
                           evidence,
                           derivationId,
                           fuel
                         );
        ScalarDecision(
          remaining.allowed,
          first.membershipProbes + remaining.membershipProbes
        )
  }

  lemma AnyChildDecisionIsExact(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    universe: set<Entity>,
    children: seq<nat>,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  )
    requires SemanticTable(nodes, denotations, universe)
    requires entity in universe
    requires evidence.entity == entity
    requires SoundWitness(evidence, denotations)
    requires forall child <- children :: child < |nodes| && child < fuel
    ensures EvaluateAnyChild(
              nodes,
              denotations,
              children,
              entity,
              evidence,
              derivationId,
              fuel
            ).allowed <==>
            entity in UnionDenotations(children, denotations)
    decreases fuel, 1, |children|
  {
    if |children| != 0 {
      assert children[0] in children;
      ScalarDecisionIsExact(
        nodes,
        denotations,
        universe,
        children[0],
        entity,
        evidence,
        derivationId,
        fuel
      );
      forall child | child in children[1..]
        ensures child < |nodes| && child < fuel
      {
        assert child in children;
      }
      AnyChildDecisionIsExact(
        nodes,
        denotations,
        universe,
        children[1..],
        entity,
        evidence,
        derivationId,
        fuel
      );
    }
  }

  lemma AllChildrenDecisionIsExact(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    universe: set<Entity>,
    children: seq<nat>,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  )
    requires SemanticTable(nodes, denotations, universe)
    requires entity in universe
    requires evidence.entity == entity
    requires SoundWitness(evidence, denotations)
    requires forall child <- children :: child < |nodes| && child < fuel
    ensures EvaluateAllChildren(
              nodes,
              denotations,
              children,
              entity,
              evidence,
              derivationId,
              fuel
            ).allowed <==>
            entity in IntersectionDenotations(
                        children,
                        denotations,
                        universe
                      )
    decreases fuel, 1, |children|
  {
    if |children| != 0 {
      assert children[0] in children;
      ScalarDecisionIsExact(
        nodes,
        denotations,
        universe,
        children[0],
        entity,
        evidence,
        derivationId,
        fuel
      );
      forall child | child in children[1..]
        ensures child < |nodes| && child < fuel
      {
        assert child in children;
      }
      AllChildrenDecisionIsExact(
        nodes,
        denotations,
        universe,
        children[1..],
        entity,
        evidence,
        derivationId,
        fuel
      );
    }
  }

  lemma ScalarDecisionIsExact(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    universe: set<Entity>,
    nodeIndex: nat,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  )
    requires SemanticTable(nodes, denotations, universe)
    requires nodeIndex < |nodes|
    requires nodeIndex < fuel
    requires entity in universe
    requires evidence.entity == entity
    requires SoundWitness(evidence, denotations)
    ensures EvaluateScalar(
              nodes,
              denotations,
              nodeIndex,
              entity,
              evidence,
              derivationId,
              fuel
            ).allowed <==>
            entity in denotations[nodeIndex]
    decreases fuel, 0
  {
    if ReusableWitness(evidence, entity, nodeIndex, derivationId) {
      assert nodeIndex in evidence.trueNodes;
    } else {
      assert WellFormedNode(nodes, nodeIndex);
      match nodes[nodeIndex]
      case LeafNode(_) =>
      case UnionNode(children) =>
        assert ChildIndexesBelow(children, nodeIndex);
        ChildIndexesBelowMeansAll(children, nodeIndex);
        assert forall child <- children ::
            child < |nodes| && child < fuel - 1;
        AnyChildDecisionIsExact(
          nodes,
          denotations,
          universe,
          children,
          entity,
          evidence,
          derivationId,
          fuel - 1
        );
      case IntersectionNode(children, _) =>
        assert ChildIndexesBelow(children, nodeIndex);
        ChildIndexesBelowMeansAll(children, nodeIndex);
        assert forall child <- children ::
            child < |nodes| && child < fuel - 1;
        AllChildrenDecisionIsExact(
          nodes,
          denotations,
          universe,
          children,
          entity,
          evidence,
          derivationId,
          fuel - 1
        );
      case ExclusionNode(includeChild, excludeChild) =>
        assert includeChild < |nodes| && includeChild < fuel - 1;
        assert excludeChild < |nodes| && excludeChild < fuel - 1;
        ScalarDecisionIsExact(
          nodes,
          denotations,
          universe,
          includeChild,
          entity,
          evidence,
          derivationId,
          fuel - 1
        );
        ScalarDecisionIsExact(
          nodes,
          denotations,
          universe,
          excludeChild,
          entity,
          evidence,
          derivationId,
          fuel - 1
        );
    }
  }

  lemma ReusableEvidenceNeedsNoMembershipProbe(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    node: nat,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  )
    requires node < |nodes|
    requires 0 < fuel
    requires ReusableWitness(evidence, entity, node, derivationId)
    ensures EvaluateScalar(
              nodes,
              denotations,
              node,
              entity,
              evidence,
              derivationId,
              fuel
            ) == ScalarDecision(true, 0)
  {
  }

  lemma SoundAnchorOrLeftEvidenceIsReusableWithinDerivation(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    witnessedChild: nat,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  )
    requires witnessedChild < |nodes|
    requires 0 < fuel
    requires evidence.entity == entity
    requires evidence.derivationId == derivationId
    requires witnessedChild in evidence.trueNodes
    requires SoundWitness(evidence, denotations)
    ensures entity in denotations[witnessedChild]
    ensures EvaluateScalar(
              nodes,
              denotations,
              witnessedChild,
              entity,
              evidence,
              derivationId,
              fuel
            ) == ScalarDecision(true, 0)
  {
    ReusableEvidenceNeedsNoMembershipProbe(
      nodes,
      denotations,
      witnessedChild,
      entity,
      evidence,
      derivationId,
      fuel
    );
  }

  lemma FilteredParentEmissionIsReusableOnlyInItsDerivation(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    childEmissions: seq<Emission>,
    parent: nat,
    emission: Emission,
    derivationId: nat,
    otherDerivationId: nat,
    fuel: nat
  )
    requires parent < |nodes|
    requires 0 < fuel
    requires parent in denotations
    requires WitnessesSound(childEmissions, denotations)
    requires emission in FilterChildAndIssueParentWitness(
                           childEmissions,
                           parent,
                           denotations[parent]
                         )
    requires otherDerivationId != derivationId
    ensures SoundWitness(
              WitnessFromEmission(emission, derivationId),
              denotations
            )
    ensures EvaluateScalar(
              nodes,
              denotations,
              parent,
              emission.entity,
              WitnessFromEmission(emission, derivationId),
              derivationId,
              fuel
            ) == ScalarDecision(true, 0)
    ensures !ReusableWitness(
              WitnessFromEmission(emission, derivationId),
              emission.entity,
              parent,
              otherDerivationId
            )
  {
    FilteringPreservesAndExtendsSoundWitnesses(
      childEmissions,
      parent,
      denotations
    );
    var filtered := FilterChildAndIssueParentWitness(
      childEmissions,
      parent,
      denotations[parent]
    );
    SoundGeneratorEmissionProducesSoundDerivationWitness(
      filtered,
      emission,
      derivationId,
      denotations
    );
    FilteredParentWitnessIsIssuedOnlyAfterExactMembership(
      childEmissions,
      parent,
      denotations[parent],
      emission
    );
    ReusableEvidenceNeedsNoMembershipProbe(
      nodes,
      denotations,
      parent,
      emission.entity,
      WitnessFromEmission(emission, derivationId),
      derivationId,
      fuel
    );
  }
}

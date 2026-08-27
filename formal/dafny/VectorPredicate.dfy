include "WitnessPredicate.dfy"

// Aligned vector refinement of scalar witness predicates.
module VectorPredicate {
  import opened CandidateCover
  import opened WitnessPredicate

  datatype Candidate = Candidate(entity: Entity, evidence: Witness)

  datatype MaskState = KnownTrue | KnownFalse | Unresolved | Failed

  datatype BatchError =
    | FailedMask
    | ProviderFailed(code: nat)
    | MalformedPermutation
    | MalformedResponseWidth(expected: nat, actual: nat)

  datatype BatchOutcome =
    | BatchComplete(decisions: seq<bool>)
    | BatchFailed(error: BatchError)

  predicate DistinctTypedCandidates(candidates: seq<Candidate>) {
    forall left, right | 0 <= left < right < |candidates| ::
      candidates[left].entity != candidates[right].entity
  }

  predicate CandidateEvidenceIsSound(
    candidates: seq<Candidate>,
    denotations: map<nat, set<Entity>>
  ) {
    forall candidate <- candidates ::
      candidate.evidence.entity == candidate.entity &&
      SoundWitness(candidate.evidence, denotations)
  }

  function ScalarDecisions(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    root: nat,
    candidates: seq<Candidate>,
    derivationId: nat,
    fuel: nat
  ): seq<bool>
    decreases |candidates|
  {
    if |candidates| == 0 then
      []
    else
      [EvaluateScalar(
         nodes,
         denotations,
         root,
         candidates[0].entity,
         candidates[0].evidence,
         derivationId,
         fuel
       ).allowed] +
      ScalarDecisions(
        nodes,
        denotations,
        root,
        candidates[1..],
        derivationId,
        fuel
      )
  }

  lemma ScalarDecisionsAreAligned(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    universe: set<Entity>,
    root: nat,
    candidates: seq<Candidate>,
    derivationId: nat,
    fuel: nat
  )
    requires SemanticTable(nodes, denotations, universe)
    requires root < |nodes| && root < fuel
    requires forall candidate <- candidates :: candidate.entity in universe
    requires CandidateEvidenceIsSound(candidates, denotations)
    ensures |ScalarDecisions(
              nodes,
              denotations,
              root,
              candidates,
              derivationId,
              fuel
            )| == |candidates|
    ensures forall index | 0 <= index < |candidates| ::
              ScalarDecisions(
                nodes,
                denotations,
                root,
                candidates,
                derivationId,
                fuel
              )[index] <==>
              candidates[index].entity in denotations[root]
    decreases |candidates|
  {
    if |candidates| != 0 {
      assert candidates[0] in candidates;
      ScalarDecisionIsExact(
        nodes,
        denotations,
        universe,
        root,
        candidates[0].entity,
        candidates[0].evidence,
        derivationId,
        fuel
      );
      forall candidate | candidate in candidates[1..]
        ensures candidate.entity in universe &&
                candidate.evidence.entity == candidate.entity &&
                SoundWitness(candidate.evidence, denotations)
      {
        assert candidate in candidates;
      }
      ScalarDecisionsAreAligned(
        nodes,
        denotations,
        universe,
        root,
        candidates[1..],
        derivationId,
        fuel
      );
    }
  }

  predicate MaskCertificate(
    masks: seq<MaskState>,
    candidates: seq<Candidate>,
    rootDenotation: set<Entity>
  ) {
    |masks| == |candidates| &&
    forall index | 0 <= index < |masks| ::
      (masks[index].KnownTrue? ==>
         candidates[index].entity in rootDenotation) &&
      (masks[index].KnownFalse? ==>
         candidates[index].entity !in rootDenotation)
  }

  predicate NoFailedMask(masks: seq<MaskState>) {
    forall mask <- masks :: !mask.Failed?
  }

  function ResolveMaskValues(
    masks: seq<MaskState>,
    scalar: seq<bool>
  ): seq<bool>
    decreases |masks|
  {
    if |masks| == 0 then
      []
    else
      [(if masks[0].KnownTrue? then
          true
        else if masks[0].KnownFalse? then
          false
        else if |scalar| != 0 then
          scalar[0]
        else
          false)] +
      ResolveMaskValues(
        masks[1..],
        if |scalar| == 0 then [] else scalar[1..]
      )
  }

  function ApplyCertifiedMasks(
    masks: seq<MaskState>,
    scalar: seq<bool>
  ): BatchOutcome {
    if |masks| != |scalar| then
      BatchFailed(MalformedResponseWidth(|masks|, |scalar|))
    else if exists mask <- masks :: mask.Failed? then
      BatchFailed(FailedMask)
    else
      BatchComplete(ResolveMaskValues(masks, scalar))
  }

  lemma CertifiedMasksPreserveScalarValues(
    masks: seq<MaskState>,
    candidates: seq<Candidate>,
    rootDenotation: set<Entity>,
    scalar: seq<bool>
  )
    requires MaskCertificate(masks, candidates, rootDenotation)
    requires |scalar| == |candidates|
    requires forall index | 0 <= index < |scalar| ::
               scalar[index] <==>
               candidates[index].entity in rootDenotation
    requires NoFailedMask(masks)
    ensures ResolveMaskValues(masks, scalar) == scalar
    ensures ApplyCertifiedMasks(masks, scalar).BatchComplete?
    ensures ApplyCertifiedMasks(masks, scalar).decisions == scalar
    decreases |masks|
  {
    if |masks| != 0 {
      CertifiedMasksPreserveScalarValues(
        masks[1..],
        candidates[1..],
        rootDenotation,
        scalar[1..]
      );
    }
  }

  datatype ProbeKey = ProbeKey(node: nat, entity: Entity)

  function ScalarNodeRequests(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    nodeIndex: nat,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  ): seq<ProbeKey>
    decreases fuel, 0, 0
  {
    if fuel == 0 || nodeIndex >= |nodes| ||
       ReusableWitness(evidence, entity, nodeIndex, derivationId) then
      []
    else
      [ProbeKey(nodeIndex, entity)] +
      match nodes[nodeIndex]
      case LeafNode(_) => []
      case UnionNode(children) =>
        AnyChildNodeRequests(
          nodes,
          denotations,
          children,
          entity,
          evidence,
          derivationId,
          fuel - 1
        )
      case IntersectionNode(children, _) =>
        AllChildNodeRequests(
          nodes,
          denotations,
          children,
          entity,
          evidence,
          derivationId,
          fuel - 1
        )
      case ExclusionNode(includeChild, excludeChild) =>
        var includeRequests := ScalarNodeRequests(
                                 nodes,
                                 denotations,
                                 includeChild,
                                 entity,
                                 evidence,
                                 derivationId,
                                 fuel - 1
                               );
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
          includeRequests
        else
          includeRequests + ScalarNodeRequests(
            nodes,
            denotations,
            excludeChild,
            entity,
            evidence,
            derivationId,
            fuel - 1
          )
  }

  function AnyChildNodeRequests(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    children: seq<nat>,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  ): seq<ProbeKey>
    decreases fuel, 1, |children|
  {
    if |children| == 0 then
      []
    else
      var firstRequests := ScalarNodeRequests(
                             nodes,
                             denotations,
                             children[0],
                             entity,
                             evidence,
                             derivationId,
                             fuel
                           );
      var firstDecision := EvaluateScalar(
                             nodes,
                             denotations,
                             children[0],
                             entity,
                             evidence,
                             derivationId,
                             fuel
                           );
      if firstDecision.allowed then
        firstRequests
      else
        firstRequests + AnyChildNodeRequests(
          nodes,
          denotations,
          children[1..],
          entity,
          evidence,
          derivationId,
          fuel
        )
  }

  function AllChildNodeRequests(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    children: seq<nat>,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  ): seq<ProbeKey>
    decreases fuel, 1, |children|
  {
    if |children| == 0 then
      []
    else
      var firstRequests := ScalarNodeRequests(
                             nodes,
                             denotations,
                             children[0],
                             entity,
                             evidence,
                             derivationId,
                             fuel
                           );
      var firstDecision := EvaluateScalar(
                             nodes,
                             denotations,
                             children[0],
                             entity,
                             evidence,
                             derivationId,
                             fuel
                           );
      if !firstDecision.allowed then
        firstRequests
      else
        firstRequests + AllChildNodeRequests(
          nodes,
          denotations,
          children[1..],
          entity,
          evidence,
          derivationId,
          fuel
        )
  }

  lemma UnionRequestsStopAfterFirstAcceptedChild(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    children: seq<nat>,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  )
    requires |children| != 0
    requires EvaluateScalar(
               nodes,
               denotations,
               children[0],
               entity,
               evidence,
               derivationId,
               fuel
             ).allowed
    ensures AnyChildNodeRequests(
              nodes,
              denotations,
              children,
              entity,
              evidence,
              derivationId,
              fuel
            ) == ScalarNodeRequests(
                   nodes,
                   denotations,
                   children[0],
                   entity,
                   evidence,
                   derivationId,
                   fuel
                 )
  {
  }

  lemma IntersectionRequestsStopAfterFirstRejectedChild(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    children: seq<nat>,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  )
    requires |children| != 0
    requires !EvaluateScalar(
               nodes,
               denotations,
               children[0],
               entity,
               evidence,
               derivationId,
               fuel
             ).allowed
    ensures AllChildNodeRequests(
              nodes,
              denotations,
              children,
              entity,
              evidence,
              derivationId,
              fuel
            ) == ScalarNodeRequests(
                   nodes,
                   denotations,
                   children[0],
                   entity,
                   evidence,
                   derivationId,
                   fuel
                 )
  {
  }

  lemma ReusableWitnessSchedulesNoNodeEvaluation(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    node: nat,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  )
    requires ReusableWitness(evidence, entity, node, derivationId)
    ensures ScalarNodeRequests(
              nodes,
              denotations,
              node,
              entity,
              evidence,
              derivationId,
              fuel
            ) == []
  {
  }

  lemma RejectedExclusionLeftSchedulesNoRightEvaluation(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    parent: nat,
    includeChild: nat,
    excludeChild: nat,
    entity: Entity,
    evidence: Witness,
    derivationId: nat,
    fuel: nat
  )
    requires 0 < fuel
    requires parent < |nodes|
    requires nodes[parent] == ExclusionNode(includeChild, excludeChild)
    requires !ReusableWitness(evidence, entity, parent, derivationId)
    requires !EvaluateScalar(
               nodes,
               denotations,
               includeChild,
               entity,
               evidence,
               derivationId,
               fuel - 1
             ).allowed
    ensures ScalarNodeRequests(
              nodes,
              denotations,
              parent,
              entity,
              evidence,
              derivationId,
              fuel
            ) == [ProbeKey(parent, entity)] +
                 ScalarNodeRequests(
                   nodes,
                   denotations,
                   includeChild,
                   entity,
                   evidence,
                   derivationId,
                   fuel - 1
                 )
  {
  }

  function MaskedCandidateRequests(
    mask: MaskState,
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    root: nat,
    candidate: Candidate,
    derivationId: nat,
    fuel: nat
  ): seq<ProbeKey> {
    if mask.Unresolved? then
      ScalarNodeRequests(
        nodes,
        denotations,
        root,
        candidate.entity,
        candidate.evidence,
        derivationId,
        fuel
      )
    else
      []
  }

  function MaskedVectorRequests(
    masks: seq<MaskState>,
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    root: nat,
    candidates: seq<Candidate>,
    derivationId: nat,
    fuel: nat
  ): seq<ProbeKey>
    decreases |candidates|
  {
    if |candidates| == 0 then
      []
    else
      MaskedCandidateRequests(
        if |masks| == 0 then Failed else masks[0],
        nodes,
        denotations,
        root,
        candidates[0],
        derivationId,
        fuel
      ) +
      MaskedVectorRequests(
        if |masks| == 0 then [] else masks[1..],
        nodes,
        denotations,
        root,
        candidates[1..],
        derivationId,
        fuel
      )
  }

  lemma CandidateMaskControlsEveryScheduledRequest(
    mask: MaskState,
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    root: nat,
    candidate: Candidate,
    derivationId: nat,
    fuel: nat
  )
    ensures mask.Unresolved? ==>
              MaskedCandidateRequests(
                mask,
                nodes,
                denotations,
                root,
                candidate,
                derivationId,
                fuel
              ) == ScalarNodeRequests(
                nodes,
                denotations,
                root,
                candidate.entity,
                candidate.evidence,
                derivationId,
                fuel
              )
    ensures !mask.Unresolved? ==>
              MaskedCandidateRequests(
                mask,
                nodes,
                denotations,
                root,
                candidate,
                derivationId,
                fuel
              ) == []
  {
  }

  datatype PredicateCertificate = PredicateCertificate(
    entity: Entity,
    derivationId: nat,
    trueNodes: set<nat>,
    falseNodes: set<nat>
  )

  predicate SoundPredicateCertificate(
    certificate: PredicateCertificate,
    denotations: map<nat, set<Entity>>
  ) {
    (forall node <- certificate.trueNodes ::
       node in denotations &&
       certificate.entity in denotations[node]) &&
    (forall node <- certificate.falseNodes ::
       node in denotations &&
       certificate.entity !in denotations[node]) &&
    certificate.trueNodes * certificate.falseNodes == {}
  }

  function MaskFromCertificate(
    certificate: PredicateCertificate,
    entity: Entity,
    node: nat,
    derivationId: nat
  ): MaskState {
    if certificate.entity != entity ||
       certificate.derivationId != derivationId then
      Unresolved
    else if node in certificate.trueNodes then
      KnownTrue
    else if node in certificate.falseNodes then
      KnownFalse
    else
      Unresolved
  }

  function MasksFromCertificates(
    certificates: seq<PredicateCertificate>,
    candidates: seq<Candidate>,
    node: nat,
    derivationId: nat
  ): seq<MaskState>
    decreases |candidates|
  {
    if |candidates| == 0 then
      []
    else
      [if |certificates| == 0 then
         Unresolved
       else
         MaskFromCertificate(
           certificates[0],
           candidates[0].entity,
           node,
           derivationId
         )] +
      MasksFromCertificates(
        if |certificates| == 0 then [] else certificates[1..],
        candidates[1..],
        node,
        derivationId
      )
  }

  lemma ExactPredicateCertificatesProduceSoundMasks(
    certificates: seq<PredicateCertificate>,
    candidates: seq<Candidate>,
    node: nat,
    derivationId: nat,
    denotations: map<nat, set<Entity>>
  )
    requires |certificates| == |candidates|
    requires node in denotations
    requires forall certificate <- certificates ::
               SoundPredicateCertificate(certificate, denotations)
    ensures MaskCertificate(
              MasksFromCertificates(
                certificates,
                candidates,
                node,
                derivationId
              ),
              candidates,
              denotations[node]
            )
    ensures NoFailedMask(
              MasksFromCertificates(
                certificates,
                candidates,
                node,
                derivationId
              )
            )
    decreases |candidates|
  {
    if |candidates| != 0 {
      assert |certificates| != 0;
      assert certificates[0] in certificates;
      forall certificate | certificate in certificates[1..]
        ensures SoundPredicateCertificate(certificate, denotations)
      {
        assert certificate in certificates;
      }
      ExactPredicateCertificatesProduceSoundMasks(
        certificates[1..],
        candidates[1..],
        node,
        derivationId,
        denotations
      );
    }
  }

  lemma CertifiedVectorPredicateRefinesScalarAndDenotation(
    nodes: seq<PlanNode>,
    denotations: map<nat, set<Entity>>,
    universe: set<Entity>,
    root: nat,
    candidates: seq<Candidate>,
    certificates: seq<PredicateCertificate>,
    derivationId: nat,
    fuel: nat
  )
    requires SemanticTable(nodes, denotations, universe)
    requires root < |nodes| && root < fuel
    requires DistinctTypedCandidates(candidates)
    requires forall candidate <- candidates :: candidate.entity in universe
    requires CandidateEvidenceIsSound(candidates, denotations)
    requires |certificates| == |candidates|
    requires forall certificate <- certificates ::
               SoundPredicateCertificate(certificate, denotations)
    ensures var masks := MasksFromCertificates(
                           certificates,
                           candidates,
                           root,
                           derivationId
                         );
            var scalar := ScalarDecisions(
                            nodes,
                            denotations,
                            root,
                            candidates,
                            derivationId,
                            fuel
                          );
            ApplyCertifiedMasks(masks, scalar).BatchComplete? &&
            ApplyCertifiedMasks(masks, scalar).decisions == scalar &&
            |scalar| == |candidates| &&
            forall index | 0 <= index < |candidates| ::
              scalar[index] <==>
              candidates[index].entity in denotations[root]
  {
    ScalarDecisionsAreAligned(
      nodes,
      denotations,
      universe,
      root,
      candidates,
      derivationId,
      fuel
    );
    ExactPredicateCertificatesProduceSoundMasks(
      certificates,
      candidates,
      root,
      derivationId,
      denotations
    );
    var masks := MasksFromCertificates(
      certificates,
      candidates,
      root,
      derivationId
    );
    var scalar := ScalarDecisions(
      nodes,
      denotations,
      root,
      candidates,
      derivationId,
      fuel
    );
    CertifiedMasksPreserveScalarValues(
      masks,
      candidates,
      denotations[root],
      scalar
    );
  }

  predicate UniqueProbeKeys(keys: seq<ProbeKey>)
    decreases |keys|
  {
    |keys| == 0 ||
    (keys[0] !in keys[1..] && UniqueProbeKeys(keys[1..]))
  }

  function DeduplicateProbeRequests(
    requested: seq<ProbeKey>,
    seen: set<ProbeKey>
  ): seq<ProbeKey>
    decreases |requested|
  {
    if |requested| == 0 then
      []
    else if requested[0] in seen then
      DeduplicateProbeRequests(requested[1..], seen)
    else
      [requested[0]] +
      DeduplicateProbeRequests(
        requested[1..],
        seen + {requested[0]}
      )
  }

  lemma DeduplicatedRequestsContainExactlyUnseenRequests(
    requested: seq<ProbeKey>,
    seen: set<ProbeKey>,
    key: ProbeKey
  )
    ensures key in DeduplicateProbeRequests(requested, seen) <==>
            key in requested && key !in seen
    decreases |requested|
  {
    if |requested| != 0 {
      DeduplicatedRequestsContainExactlyUnseenRequests(
        requested[1..],
        if requested[0] in seen then
          seen
        else
          seen + {requested[0]},
        key
      );
    }
  }

  lemma DeduplicatedRequestsAreUnique(
    requested: seq<ProbeKey>,
    seen: set<ProbeKey>
  )
    ensures UniqueProbeKeys(DeduplicateProbeRequests(requested, seen))
    ensures forall key <- DeduplicateProbeRequests(requested, seen) ::
              key !in seen
    decreases |requested|
  {
    if |requested| != 0 {
      var nextSeen := if requested[0] in seen then
        seen
      else
        seen + {requested[0]};
      DeduplicatedRequestsAreUnique(requested[1..], nextSeen);
      if requested[0] !in seen {
        DeduplicatedRequestsContainExactlyUnseenRequests(
          requested[1..],
          nextSeen,
          requested[0]
        );
      }
    }
  }

  function SharedProbeSchedule(
    requested: seq<ProbeKey>
  ): seq<ProbeKey> {
    DeduplicateProbeRequests(requested, {})
  }

  function PhysicalEvaluationCount(
    schedule: seq<ProbeKey>,
    key: ProbeKey
  ): nat
    decreases |schedule|
  {
    if |schedule| == 0 then
      0
    else
      (if schedule[0] == key then 1 else 0) +
      PhysicalEvaluationCount(schedule[1..], key)
  }

  function EvaluateSharedSchedule(
    requested: seq<ProbeKey>,
    denotations: map<nat, set<Entity>>
  ): map<ProbeKey, bool> {
    map key <- set key <- SharedProbeSchedule(requested) :: key ::
      key.node in denotations && key.entity in denotations[key.node]
  }

  lemma UniqueScheduleCountsEveryKeyAtMostOnce(
    schedule: seq<ProbeKey>,
    key: ProbeKey
  )
    requires UniqueProbeKeys(schedule)
    ensures PhysicalEvaluationCount(schedule, key) <= 1
    decreases |schedule|
  {
    if |schedule| != 0 {
      if schedule[0] == key {
        MissingScheduleKeyHasZeroCount(schedule[1..], key);
      } else {
        UniqueScheduleCountsEveryKeyAtMostOnce(schedule[1..], key);
      }
    }
  }

  lemma MissingScheduleKeyHasZeroCount(
    schedule: seq<ProbeKey>,
    key: ProbeKey
  )
    requires key !in schedule
    ensures PhysicalEvaluationCount(schedule, key) == 0
    decreases |schedule|
  {
    if |schedule| != 0 {
      MissingScheduleKeyHasZeroCount(schedule[1..], key);
    }
  }

  lemma SharedScheduleCoversEveryRequestExactly(
    requested: seq<ProbeKey>,
    denotations: map<nat, set<Entity>>,
    key: ProbeKey
  )
    requires key in requested
    ensures key in EvaluateSharedSchedule(requested, denotations)
    ensures EvaluateSharedSchedule(requested, denotations)[key] <==>
            key.node in denotations &&
            key.entity in denotations[key.node]
  {
    DeduplicatedRequestsContainExactlyUnseenRequests(
      requested,
      {},
      key
    );
  }

  lemma SharedScheduleContainsNoUnrequestedEvaluation(
    requested: seq<ProbeKey>,
    denotations: map<nat, set<Entity>>,
    key: ProbeKey
  )
    ensures key in EvaluateSharedSchedule(requested, denotations) <==>
            key in requested
  {
    DeduplicatedRequestsContainExactlyUnseenRequests(
      requested,
      {},
      key
    );
  }

  lemma DAGSharingEvaluatesEachTypedKeyAtMostOnce(
    requested: seq<ProbeKey>,
    key: ProbeKey
  )
    ensures PhysicalEvaluationCount(
              SharedProbeSchedule(requested),
              key
            ) <= 1
    ensures key in SharedProbeSchedule(requested) <==> key in requested
  {
    DeduplicatedRequestsAreUnique(requested, {});
    UniqueScheduleCountsEveryKeyAtMostOnce(
      SharedProbeSchedule(requested),
      key
    );
    DeduplicatedRequestsContainExactlyUnseenRequests(
      requested,
      {},
      key
    );
  }

  predicate ValidPermutationCertificate(
    order: seq<nat>,
    positions: map<nat, nat>,
    width: nat
  ) {
    |order| == width &&
    (forall physical | 0 <= physical < width ::
       order[physical] < width &&
       order[physical] in positions &&
       positions[order[physical]] == physical) &&
    (forall original | 0 <= original < width ::
       original in positions &&
       positions[original] < width &&
       order[positions[original]] == original)
  }

  function BoolAt(values: seq<bool>, index: nat): bool {
    index < |values| && values[index]
  }

  function Gather(
    values: seq<bool>,
    order: seq<nat>
  ): seq<bool>
    ensures |Gather(values, order)| == |order|
    decreases |order|
  {
    if |order| == 0 then
      []
    else
      [BoolAt(values, order[0])] + Gather(values, order[1..])
  }

  function ScatterFrom(
    physicalValues: seq<bool>,
    positions: map<nat, nat>,
    original: nat,
    width: nat
  ): seq<bool>
    requires original <= width
    ensures |ScatterFrom(
              physicalValues,
              positions,
              original,
              width
            )| == width - original
    decreases width - original
  {
    if original >= width then
      []
    else
      [if original in positions then
         BoolAt(physicalValues, positions[original])
       else
         false] +
      ScatterFrom(
        physicalValues,
        positions,
        original + 1,
        width
      )
  }

  function Scatter(
    physicalValues: seq<bool>,
    positions: map<nat, nat>,
    width: nat
  ): seq<bool>
    ensures |Scatter(physicalValues, positions, width)| == width
  {
    ScatterFrom(physicalValues, positions, 0, width)
  }

  lemma GatherHasOrderWidth(
    values: seq<bool>,
    order: seq<nat>
  )
    ensures |Gather(values, order)| == |order|
    decreases |order|
  {
    if |order| != 0 {
      GatherHasOrderWidth(values, order[1..]);
    }
  }

  lemma GatherAtPhysicalIndex(
    values: seq<bool>,
    order: seq<nat>,
    physical: nat
  )
    requires physical < |order|
    ensures Gather(values, order)[physical] ==
            BoolAt(values, order[physical])
    decreases |order|
  {
    if physical != 0 {
      GatherAtPhysicalIndex(values, order[1..], physical - 1);
    }
  }

  lemma ScatterFromHasRemainingWidth(
    physicalValues: seq<bool>,
    positions: map<nat, nat>,
    original: nat,
    width: nat
  )
    requires original <= width
    ensures |ScatterFrom(
              physicalValues,
              positions,
              original,
              width
            )| == width - original
    decreases width - original
  {
    if original < width {
      ScatterFromHasRemainingWidth(
        physicalValues,
        positions,
        original + 1,
        width
      );
    }
  }

  lemma ScatterAtOriginalIndex(
    physicalValues: seq<bool>,
    positions: map<nat, nat>,
    original: nat,
    width: nat
  )
    requires original < width
    ensures Scatter(physicalValues, positions, width)[original] ==
            (if original in positions then
               BoolAt(physicalValues, positions[original])
             else
               false)
  {
    ScatterFromAtOriginalIndex(
      physicalValues,
      positions,
      0,
      original,
      width
    );
  }

  lemma ScatterFromAtOriginalIndex(
    physicalValues: seq<bool>,
    positions: map<nat, nat>,
    start: nat,
    original: nat,
    width: nat
  )
    requires start <= original < width
    ensures ScatterFrom(
              physicalValues,
              positions,
              start,
              width
            )[original - start] ==
            (if original in positions then
               BoolAt(physicalValues, positions[original])
             else
               false)
    decreases original - start
  {
    if start < original {
      ScatterFromAtOriginalIndex(
        physicalValues,
        positions,
        start + 1,
        original,
        width
      );
    }
  }

  lemma RegroupAndScatterPreservesAlignment(
    values: seq<bool>,
    order: seq<nat>,
    positions: map<nat, nat>
  )
    requires ValidPermutationCertificate(
               order,
               positions,
               |values|
             )
    ensures Scatter(
              Gather(values, order),
              positions,
              |values|
            ) == values
  {
    GatherHasOrderWidth(values, order);
    ScatterFromHasRemainingWidth(
      Gather(values, order),
      positions,
      0,
      |values|
    );
    forall original | 0 <= original < |values|
      ensures Scatter(
                Gather(values, order),
                positions,
                |values|
              )[original] == values[original]
    {
      ScatterAtOriginalIndex(
        Gather(values, order),
        positions,
        original,
        |values|
      );
      GatherAtPhysicalIndex(
        values,
        order,
        positions[original]
      );
    }
  }

  datatype ProviderResponse =
    | ProviderSuccess(values: seq<bool>)
    | ProviderError(code: nat)

  function ExecuteRegroupedBatch(
    expected: seq<bool>,
    order: seq<nat>,
    positions: map<nat, nat>,
    response: ProviderResponse
  ): BatchOutcome {
    if !ValidPermutationCertificate(order, positions, |expected|) then
      BatchFailed(MalformedPermutation)
    else
      match response
      case ProviderError(code) => BatchFailed(ProviderFailed(code))
      case ProviderSuccess(values) =>
        if |values| != |expected| then
          BatchFailed(MalformedResponseWidth(|expected|, |values|))
        else
          BatchComplete(Scatter(values, positions, |expected|))
  }

  lemma CorrectRegroupedProviderRefinesScalarVector(
    expected: seq<bool>,
    order: seq<nat>,
    positions: map<nat, nat>
  )
    requires ValidPermutationCertificate(
               order,
               positions,
               |expected|
             )
    ensures ExecuteRegroupedBatch(
              expected,
              order,
              positions,
              ProviderSuccess(Gather(expected, order))
            ).BatchComplete?
    ensures ExecuteRegroupedBatch(
              expected,
              order,
              positions,
              ProviderSuccess(Gather(expected, order))
            ).decisions == expected
  {
    GatherHasOrderWidth(expected, order);
    RegroupAndScatterPreservesAlignment(expected, order, positions);
  }

  lemma MalformedOrFailedProviderPublishesNoDecisionVector(
    expected: seq<bool>,
    order: seq<nat>,
    positions: map<nat, nat>,
    response: ProviderResponse
  )
    requires !ValidPermutationCertificate(
               order,
               positions,
               |expected|
             ) ||
             response.ProviderError? ||
             (response.ProviderSuccess? &&
              |response.values| != |expected|)
    ensures ExecuteRegroupedBatch(
              expected,
              order,
              positions,
              response
            ).BatchFailed?
  {
  }
}

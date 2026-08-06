include "Semantics.dfy"
include "IndexedTraversal.dfy"

module IndexedRefinement {
  import Semantics
  import Indexed = IndexedTraversal

  datatype ObjectBinding = ObjectBinding(
    eid: int,
    objectRef: Semantics.ObjectRef
  )

  datatype RelationBinding = RelationBinding(
    eid: int,
    relation: Semantics.RelationNode
  )

  predicate UniqueObjectBindingEids(
    bindings: seq<ObjectBinding>
  ) {
    forall left, right |
      0 <= left < right < |bindings| ::
      bindings[left].eid != bindings[right].eid
  }

  predicate UniqueBoundObjects(
    bindings: seq<ObjectBinding>
  ) {
    forall left, right |
      0 <= left < right < |bindings| ::
      bindings[left].objectRef != bindings[right].objectRef
  }

  predicate UniqueRelationBindingEids(
    bindings: seq<RelationBinding>
  ) {
    forall left, right |
      0 <= left < right < |bindings| ::
      bindings[left].eid != bindings[right].eid
  }

  predicate UniqueBoundRelations(
    bindings: seq<RelationBinding>
  ) {
    forall left, right |
      0 <= left < right < |bindings| ::
      bindings[left].relation != bindings[right].relation
  }

  predicate ExactObjectCatalog(
    objects: seq<Semantics.ObjectRef>,
    bindings: seq<ObjectBinding>
  ) {
    UniqueObjectBindingEids(bindings) &&
    UniqueBoundObjects(bindings) &&
    (forall binding <- bindings ::
       0 <= binding.eid &&
       binding.objectRef in objects) &&
    (forall candidate <- objects ::
       exists binding <- bindings ::
         binding.objectRef == candidate)
  }

  predicate ExactRelationCatalog(
    relations: seq<Semantics.RelationNode>,
    bindings: seq<RelationBinding>
  ) {
    UniqueRelationBindingEids(bindings) &&
    UniqueBoundRelations(bindings) &&
    (forall binding <- bindings ::
       0 <= binding.eid &&
       binding.relation in relations) &&
    (forall relation <- relations ::
       exists binding <- bindings ::
         binding.relation == relation)
  }

  predicate RelationshipProjectsTo(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    projection: Indexed.Projection,
    candidateEid: int
  ) {
    match projection
    case SubjectToResources(
      subjectType,
      subjectEid,
      relationEid,
      resourceType,
      _
      ) =>
      exists subjectBinding <- objectBindings,
        resourceBinding <- objectBindings,
        relationBinding <- relationBindings,
        relationship <- relationships ::
        subjectBinding.eid == subjectEid &&
        subjectBinding.objectRef.typeName == subjectType &&
        resourceBinding.eid == candidateEid &&
        resourceBinding.objectRef.typeName == resourceType &&
        relationBinding.eid == relationEid &&
        relationBinding.relation ==
        Semantics.RelationNode(
          resourceType,
          relationship.relationName,
          subjectType
        ) &&
        relationship ==
        Semantics.Relationship(
          resourceBinding.objectRef,
          relationship.relationName,
          subjectBinding.objectRef
        )
    case ResourceToSubjects(
      resourceType,
      resourceEid,
      relationEid,
      subjectType,
      _
      ) =>
      exists resourceBinding <- objectBindings,
        subjectBinding <- objectBindings,
        relationBinding <- relationBindings,
        relationship <- relationships ::
        resourceBinding.eid == resourceEid &&
        resourceBinding.objectRef.typeName == resourceType &&
        subjectBinding.eid == candidateEid &&
        subjectBinding.objectRef.typeName == subjectType &&
        relationBinding.eid == relationEid &&
        relationBinding.relation ==
        Semantics.RelationNode(
          resourceType,
          relationship.relationName,
          subjectType
        ) &&
        relationship ==
        Semantics.Relationship(
          resourceBinding.objectRef,
          relationship.relationName,
          subjectBinding.objectRef
        )
  }

  ghost predicate ExactProjectionValues(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    projection: Indexed.Projection,
    values: seq<int>
  ) {
    Indexed.ValidProjection(projection) &&
    Indexed.StrictlyIncreasing(values) &&
    (forall value <- values :: 0 <= value) &&
    (forall candidateEid: int ::
       candidateEid in values <==>
                       RelationshipProjectsTo(
                         objectBindings,
                         relationBindings,
                         relationships,
                         projection,
                         candidateEid
                       ))
  }

  function ValuesAfterBound(
    values: seq<int>,
    bound: Indexed.OptionalEid
  ): seq<int>
    decreases |values|
  {
    if |values| == 0 then
      []
    else if bound.Bound? && values[0] <= bound.value then
      ValuesAfterBound(values[1..], bound)
    else
      values
  }

  function ChunkPrefix(
    values: seq<int>,
    chunkSize: nat
  ): seq<int>
    requires 0 < chunkSize
  {
    if |values| <= chunkSize
    then values
    else values[..chunkSize]
  }

  predicate ExactScanResponse(
    command: Indexed.ScanCommand,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>
  ) {
    var remaining :=
      ValuesAfterBound(
        fullProjectionValues,
        Indexed.ProjectionBound(command.projection)
      );
    Indexed.ValidScanResponse(command, response) &&
    response.values == ChunkPrefix(remaining, command.chunkSize) &&
    (response.terminal <==> |remaining| <= command.chunkSize)
  }

  ghost predicate CertifiedOrderedChunkAdapter(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    adapter: Indexed.ScanCommand -> Indexed.ScanResponse
  ) {
    forall command: Indexed.ScanCommand |
      Indexed.ValidProjection(command.projection) &&
      0 < command.chunkSize ::
      exists fullProjectionValues ::
        ExactProjectionValues(
          objectBindings,
          relationBindings,
          relationships,
          command.projection,
          fullProjectionValues
        ) &&
        ExactScanResponse(
          command,
          adapter(command),
          fullProjectionValues
        )
  }

  lemma CertifiedResponseIsStructurallyValid(
    command: Indexed.ScanCommand,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>
  )
    requires ExactScanResponse(
               command,
               response,
               fullProjectionValues
             )
    ensures Indexed.ValidScanResponse(command, response)
  {
  }

  lemma TerminalCertifiedResponseHasNoOmittedSuffix(
    command: Indexed.ScanCommand,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>
  )
    requires ExactScanResponse(
               command,
               response,
               fullProjectionValues
             )
    requires response.terminal
    ensures response.values ==
            ValuesAfterBound(
              fullProjectionValues,
              Indexed.ProjectionBound(command.projection)
            )
  {
  }

  lemma ValuesAfterBoundIsSubset(
    values: seq<int>,
    bound: Indexed.OptionalEid,
    candidate: int
  )
    requires candidate in ValuesAfterBound(values, bound)
    ensures candidate in values
    decreases |values|
  {
    if |values| != 0 &&
       bound.Bound? &&
       values[0] <= bound.value {
      ValuesAfterBoundIsSubset(values[1..], bound, candidate);
    }
  }

  lemma ChunkPrefixIsSubset(
    values: seq<int>,
    chunkSize: nat,
    candidate: int
  )
    requires 0 < chunkSize
    requires candidate in ChunkPrefix(values, chunkSize)
    ensures candidate in values
  {
  }

  lemma CertifiedResponseValueBelongsToProjection(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    command: Indexed.ScanCommand,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>,
    candidate: int
  )
    requires ExactProjectionValues(
               objectBindings,
               relationBindings,
               relationships,
               command.projection,
               fullProjectionValues
             )
    requires ExactScanResponse(
               command,
               response,
               fullProjectionValues
             )
    requires candidate in response.values
    ensures RelationshipProjectsTo(
              objectBindings,
              relationBindings,
              relationships,
              command.projection,
              candidate
            )
  {
    var remaining :=
      ValuesAfterBound(
        fullProjectionValues,
        Indexed.ProjectionBound(command.projection)
      );
    ChunkPrefixIsSubset(remaining, command.chunkSize, candidate);
    ValuesAfterBoundIsSubset(
      fullProjectionValues,
      Indexed.ProjectionBound(command.projection),
      candidate
    );
  }

  predicate RelationBindingMatches(
    binding: RelationBinding,
    resourceType: string,
    relationName: string,
    subjectType: string
  ) {
    binding.relation ==
    Semantics.RelationNode(
      resourceType,
      relationName,
      subjectType
    )
  }

  predicate IndexedRuleRefines(
    indexedRule: Indexed.IndexedRule,
    normalizedRule: Semantics.NormalizedRule,
    relationBindings: seq<RelationBinding>
  ) {
    match normalizedRule
    case DirectRelationRule(
      head,
      relationName,
      subjectType
      ) =>
      indexedRule.RelationRule? &&
      indexedRule.head == head &&
      indexedRule.subjectType == subjectType &&
      exists binding <- relationBindings ::
        binding.eid == indexedRule.relationEid &&
        RelationBindingMatches(
          binding,
          head.resourceType,
          relationName,
          subjectType
        )
    case SelfPermissionRule(
      head,
      sourcePermission
      ) =>
      indexedRule.SelfPermissionRule? &&
      indexedRule.head == head &&
      indexedRule.targetNode ==
      Semantics.PermissionNode(
        head.resourceType,
        sourcePermission
      )
    case ArrowRelationRule(
      head,
      viaRelation,
      targetRelation,
      subjectType
      ) =>
      indexedRule.ArrowRelationRule? &&
      indexedRule.head == head &&
      indexedRule.targetSubjectType == subjectType &&
      exists viaBinding <- relationBindings,
        targetBinding <- relationBindings ::
        viaBinding.eid == indexedRule.viaRelationEid &&
        RelationBindingMatches(
          viaBinding,
          head.resourceType,
          viaRelation,
          indexedRule.intermediateType
        ) &&
        targetBinding.eid == indexedRule.targetRelationEid &&
        RelationBindingMatches(
          targetBinding,
          indexedRule.intermediateType,
          targetRelation,
          subjectType
        )
    case ArrowPermissionRule(
      head,
      viaRelation,
      targetPermission
      ) =>
      indexedRule.ArrowPermissionRule? &&
      indexedRule.head == head &&
      indexedRule.targetNode ==
      Semantics.PermissionNode(
        indexedRule.intermediateType,
        targetPermission
      ) &&
      exists viaBinding <- relationBindings ::
        viaBinding.eid == indexedRule.viaRelationEid &&
        RelationBindingMatches(
          viaBinding,
          head.resourceType,
          viaRelation,
          indexedRule.intermediateType
        )
  }

  predicate ExactCompiledRules(
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationBindings: seq<RelationBinding>
  ) {
    (forall indexedRule <- indexedRules ::
       exists normalizedRule <- normalizedRules ::
         IndexedRuleRefines(
           indexedRule,
           normalizedRule,
           relationBindings
         )) &&
    (forall normalizedRule <- normalizedRules,
       binding <- relationBindings ::
       normalizedRule.DirectRelationRule? &&
       RelationBindingMatches(
         binding,
         normalizedRule.head.resourceType,
         normalizedRule.relationName,
         normalizedRule.subjectType
       ) ==>
         Indexed.RelationRule(
           normalizedRule.head,
           binding.eid,
           normalizedRule.subjectType
         ) in indexedRules) &&
    (forall normalizedRule <- normalizedRules ::
       normalizedRule.SelfPermissionRule? ==>
         Indexed.SelfPermissionRule(
           normalizedRule.head,
           Semantics.PermissionNode(
             normalizedRule.head.resourceType,
             normalizedRule.sourcePermission
           )
         ) in indexedRules) &&
    (forall normalizedRule <- normalizedRules,
       viaBinding <- relationBindings,
       targetBinding <- relationBindings ::
       normalizedRule.ArrowRelationRule? &&
       RelationBindingMatches(
         viaBinding,
         normalizedRule.head.resourceType,
         normalizedRule.viaRelation,
         viaBinding.relation.subjectType
       ) &&
       RelationBindingMatches(
         targetBinding,
         viaBinding.relation.subjectType,
         normalizedRule.targetRelation,
         normalizedRule.subjectType
       ) ==>
         Indexed.ArrowRelationRule(
           normalizedRule.head,
           viaBinding.eid,
           viaBinding.relation.subjectType,
           targetBinding.eid,
           normalizedRule.subjectType
         ) in indexedRules) &&
    (forall normalizedRule <- normalizedRules,
       viaBinding <- relationBindings ::
       normalizedRule.ArrowPermissionRule? &&
       RelationBindingMatches(
         viaBinding,
         normalizedRule.head.resourceType,
         normalizedRule.viaRelation,
         viaBinding.relation.subjectType
       ) ==>
         Indexed.ArrowPermissionRule(
           normalizedRule.head,
           viaBinding.eid,
           viaBinding.relation.subjectType,
           Semantics.PermissionNode(
             viaBinding.relation.subjectType,
             normalizedRule.targetPermission
           )
         ) in indexedRules)
  }

  predicate IndexedRulePermissionClosed(
    rule: Indexed.IndexedRule,
    permissions: seq<Semantics.PermissionNode>
  ) {
    rule.head in permissions &&
    (rule.SelfPermissionRule? ==> rule.targetNode in permissions) &&
    (rule.ArrowPermissionRule? ==> rule.targetNode in permissions)
  }

  predicate UniqueIndexedRules(
    indexedRules: seq<Indexed.IndexedRule>
  ) {
    forall left, right |
      0 <= left < right < |indexedRules| ::
      indexedRules[left] != indexedRules[right]
  }

  predicate EligibleForwardSeed(
    rule: Indexed.IndexedRule,
    subjectType: string
  ) {
    match rule
    case RelationRule(_, _, ruleSubjectType) =>
      ruleSubjectType == subjectType
    case ArrowRelationRule(_, _, _, _, targetSubjectType) =>
      targetSubjectType == subjectType
    case SelfPermissionRule(_, _) => false
    case ArrowPermissionRule(_, _, _, _) => false
  }

  ghost predicate ExactForwardSeedBucket(
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string
  ) {
    UniqueIndexedRules(indexedRules) &&
    UniqueIndexedRules(seedRules) &&
    forall rule: Indexed.IndexedRule ::
      rule in seedRules <==>
              rule in indexedRules &&
              EligibleForwardSeed(rule, subjectType)
  }

  lemma EveryIndexedRuleHasSemanticWitness(
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationBindings: seq<RelationBinding>,
    indexedRule: Indexed.IndexedRule
  )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires indexedRule in indexedRules
    ensures exists normalizedRule <- normalizedRules ::
              IndexedRuleRefines(
                indexedRule,
                normalizedRule,
                relationBindings
              )
  {
  }

  lemma UniqueObjectEidIdentifiesBinding(
    bindings: seq<ObjectBinding>,
    left: ObjectBinding,
    right: ObjectBinding
  )
    requires UniqueObjectBindingEids(bindings)
    requires left in bindings
    requires right in bindings
    requires left.eid == right.eid
    ensures left == right
  {
    var leftIndex :| 0 <= leftIndex < |bindings| &&
                     bindings[leftIndex] == left;
    var rightIndex :| 0 <= rightIndex < |bindings| &&
                      bindings[rightIndex] == right;
    if leftIndex < rightIndex {
      assert bindings[leftIndex].eid != bindings[rightIndex].eid;
    }
    if rightIndex < leftIndex {
      assert bindings[rightIndex].eid != bindings[leftIndex].eid;
    }
  }

  lemma UniqueRelationEidIdentifiesBinding(
    bindings: seq<RelationBinding>,
    left: RelationBinding,
    right: RelationBinding
  )
    requires UniqueRelationBindingEids(bindings)
    requires left in bindings
    requires right in bindings
    requires left.eid == right.eid
    ensures left == right
  {
    var leftIndex :| 0 <= leftIndex < |bindings| &&
                     bindings[leftIndex] == left;
    var rightIndex :| 0 <= rightIndex < |bindings| &&
                      bindings[rightIndex] == right;
    if leftIndex < rightIndex {
      assert bindings[leftIndex].eid != bindings[rightIndex].eid;
    }
    if rightIndex < leftIndex {
      assert bindings[rightIndex].eid != bindings[leftIndex].eid;
    }
  }

  ghost predicate NormalizedFixedPoint(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>
  ) {
    grants <= Semantics.GrantUniverse(objects, permissions) &&
    Semantics.ImmediateConsequences(
      objects,
      permissions,
      normalizedRules,
      relationships,
      grants
    ) == grants
  }

  lemma RuleDerivationBelongsToFixedPoint(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>,
    normalizedRule: Semantics.NormalizedRule,
    grant: Semantics.Grant
  )
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires normalizedRule in normalizedRules
    requires grant in Semantics.GrantUniverse(objects, permissions)
    requires Semantics.RuleDerives(
               normalizedRule,
               relationships,
               grants,
               grant
             )
    ensures grant in grants
  {
    var ruleIndex :| 0 <= ruleIndex < |normalizedRules| &&
                     normalizedRules[ruleIndex] == normalizedRule;
    assert Semantics.AnyRuleDerives(
        normalizedRules,
        relationships,
        grants,
        grant
      );
    assert grant in Semantics.ImmediateConsequences(
                      objects,
                      permissions,
                      normalizedRules,
                      relationships,
                      grants
                    );
  }

  lemma GrantBelongsToUniverse(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    subject: Semantics.ObjectRef,
    node: Semantics.PermissionNode,
    resource: Semantics.ObjectRef
  )
    requires subject in objects
    requires resource in objects
    requires node in permissions
    requires resource.typeName == node.resourceType
    ensures Semantics.Grant(subject, node, resource) in
              Semantics.GrantUniverse(objects, permissions)
    decreases |permissions|
  {
    if permissions[0] != node {
      GrantBelongsToUniverse(
        objects,
        permissions[1..],
        subject,
        node,
        resource
      );
    }
  }

  predicate ForwardGrantRefines(
    objectBindings: seq<ObjectBinding>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    indexedGrant: Indexed.ForwardGrantKey
  ) {
    exists resourceBinding <- objectBindings ::
      resourceBinding.eid == indexedGrant.resourceEid &&
      resourceBinding.objectRef.typeName ==
      indexedGrant.node.resourceType &&
      Semantics.Grant(
        semanticSubject,
        indexedGrant.node,
        resourceBinding.objectRef
      ) in grants
  }

  predicate ReverseGrantRefines(
    objectBindings: seq<ObjectBinding>,
    grants: set<Semantics.Grant>,
    indexedGrant: Indexed.ReverseGrantKey
  ) {
    exists resourceBinding <- objectBindings,
      subjectBinding <- objectBindings ::
      resourceBinding.eid == indexedGrant.resourceEid &&
      resourceBinding.objectRef.typeName ==
      indexedGrant.node.resourceType &&
      subjectBinding.eid == indexedGrant.subjectEid &&
      subjectBinding.objectRef.typeName == indexedGrant.subjectType &&
      Semantics.Grant(
        subjectBinding.objectRef,
        indexedGrant.node,
        resourceBinding.objectRef
      ) in grants
  }

  predicate ReverseGoalRefines(
    objectBindings: seq<ObjectBinding>,
    permissions: seq<Semantics.PermissionNode>,
    goal: Indexed.ReverseGoalKey
  ) {
    goal.node in permissions &&
    exists resourceBinding <- objectBindings ::
      resourceBinding.eid == goal.resourceEid &&
      resourceBinding.objectRef.typeName == goal.node.resourceType
  }

  ghost predicate ReverseConsumerRefines(
    objectBindings: seq<ObjectBinding>,
    grants: set<Semantics.Grant>,
    key: Indexed.ReverseGoalKey,
    consumer: Indexed.ReverseConsumer
  ) {
    forall indexedGrant: Indexed.ReverseGrantKey |
      indexedGrant.node == key.node &&
      indexedGrant.resourceEid == key.resourceEid &&
      ReverseGrantRefines(
        objectBindings,
        grants,
        indexedGrant
      ) ::
      ReverseGrantRefines(
        objectBindings,
        grants,
        Indexed.ReverseGrantKey(
          consumer.node,
          consumer.resourceEid,
          indexedGrant.subjectType,
          indexedGrant.subjectEid
        )
      )
  }

  ghost predicate ReverseStreamSound(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    stream: Indexed.ReverseStream
  ) {
    (forall buffered <- stream.buffered ::
       RelationshipProjectsTo(
         objectBindings,
         relationBindings,
         relationships,
         stream.projection,
         buffered
       )) &&
    match stream.continuation
    case ReverseGrant(node, resourceEid, subjectType) =>
      forall subjectEid: int |
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          stream.projection,
          subjectEid
        ) ::
        ReverseGrantRefines(
          objectBindings,
          grants,
          Indexed.ReverseGrantKey(
            node,
            resourceEid,
            subjectType,
            subjectEid
          )
        )
    case ReverseArrowRelation(
      node,
      resourceEid,
      subjectType,
      intermediateType,
      targetRelationEid
      ) =>
      forall intermediateEid: int, subjectEid: int |
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          stream.projection,
          intermediateEid
        ) &&
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ResourceToSubjects(
            intermediateType,
            intermediateEid,
            targetRelationEid,
            subjectType,
            Indexed.NoBound
          ),
          subjectEid
        ) ::
        ReverseGrantRefines(
          objectBindings,
          grants,
          Indexed.ReverseGrantKey(
            node,
            resourceEid,
            subjectType,
            subjectEid
          )
        )
    case ReverseArrowPermission(
      node,
      resourceEid,
      targetNode
      ) =>
      forall intermediateEid: int |
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          stream.projection,
          intermediateEid
        ) ::
        ReverseGoalRefines(
          objectBindings,
          permissions,
          Indexed.ReverseGoalKey(targetNode, intermediateEid)
        ) &&
        ReverseConsumerRefines(
          objectBindings,
          grants,
          Indexed.ReverseGoalKey(targetNode, intermediateEid),
          Indexed.ReverseConsumer(node, resourceEid)
        )
  }

  ghost predicate ReverseWorkSound(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    work: Indexed.ReverseWork
  ) {
    match work
    case ReverseStreamWork(stream) =>
      ReverseStreamSound(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        stream
      )
    case ReverseGoalWork(goal) =>
      ReverseGoalRefines(objectBindings, permissions, goal)
    case ReverseRegisterConsumerWork(key, consumer) =>
      ReverseGoalRefines(objectBindings, permissions, key) &&
      ReverseConsumerRefines(objectBindings, grants, key, consumer)
    case ReverseGrantWork(grant) =>
      ReverseGrantRefines(objectBindings, grants, grant)
  }

  ghost predicate ReverseQueueSound(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    queue: seq<Indexed.ReverseWork>
  ) {
    forall work <- queue ::
      ReverseWorkSound(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        work
      )
  }

  lemma ContinueReversePreservesSoundness(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    stream: Indexed.ReverseStream,
    candidateEid: int
  )
    requires ReverseStreamSound(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               stream
             )
    requires RelationshipProjectsTo(
               objectBindings,
               relationBindings,
               relationships,
               stream.projection,
               candidateEid
             )
    requires 0 <= candidateEid
    requires Indexed.ValidReverseContinuationEids(
               stream.continuation
             )
    ensures ReverseQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              Indexed.ContinueReverse(
                stream.continuation,
                candidateEid
              )
            )
  {
  }

  lemma ReverseQueueSoundConcatenation(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    left: seq<Indexed.ReverseWork>,
    right: seq<Indexed.ReverseWork>
  )
    requires ReverseQueueSound(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               left
             )
    requires ReverseQueueSound(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               right
             )
    ensures ReverseQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              left + right
            )
  {
  }

  lemma ReverseStreamSoundAfterChunk(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    stream: Indexed.ReverseStream,
    values: seq<int>
  )
    requires 0 < |values|
    requires ReverseStreamSound(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               stream
             )
    requires forall candidate <- values ::
               RelationshipProjectsTo(
                 objectBindings,
                 relationBindings,
                 relationships,
                 stream.projection,
                 candidate
               )
    ensures ReverseStreamSound(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              Indexed.ReverseStream(
                Indexed.ProjectionAfterChunk(
                  stream.projection,
                  values
                ),
                values[1..],
                stream.more,
                stream.continuation
              )
            )
  {
    forall candidate <- values
      ensures RelationshipProjectsTo(
                objectBindings,
                relationBindings,
                relationships,
                Indexed.ProjectionAfterChunk(
                  stream.projection,
                  values
                ),
                candidate
              )
    {
      ProjectionAfterChunkPreservesMembership(
        objectBindings,
        relationBindings,
        relationships,
        stream.projection,
        values,
        candidate
      );
    }
    match stream.continuation
    case ReverseGrant(_, _, _) => {
      forall subjectEid: int |
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ProjectionAfterChunk(
            stream.projection,
            values
          ),
          subjectEid
        )
        ensures ReverseGrantRefines(
                  objectBindings,
                  grants,
                  Indexed.ReverseGrantKey(
                    stream.continuation.node,
                    stream.continuation.resourceEid,
                    stream.continuation.subjectType,
                    subjectEid
                  )
                )
      {
        ProjectionAfterChunkPreservesMembership(
          objectBindings,
          relationBindings,
          relationships,
          stream.projection,
          values,
          subjectEid
        );
      }
    }
    case ReverseArrowRelation(_, _, _, _, _) => {
      forall intermediateEid: int, subjectEid: int |
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ProjectionAfterChunk(
            stream.projection,
            values
          ),
          intermediateEid
        ) &&
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ResourceToSubjects(
            stream.continuation.intermediateType,
            intermediateEid,
            stream.continuation.targetRelationEid,
            stream.continuation.subjectType,
            Indexed.NoBound
          ),
          subjectEid
        )
        ensures ReverseGrantRefines(
                  objectBindings,
                  grants,
                  Indexed.ReverseGrantKey(
                    stream.continuation.node,
                    stream.continuation.resourceEid,
                    stream.continuation.subjectType,
                    subjectEid
                  )
                )
      {
        ProjectionAfterChunkPreservesMembership(
          objectBindings,
          relationBindings,
          relationships,
          stream.projection,
          values,
          intermediateEid
        );
      }
    }
    case ReverseArrowPermission(_, _, _) => {
      forall intermediateEid: int |
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ProjectionAfterChunk(
            stream.projection,
            values
          ),
          intermediateEid
        )
        ensures ReverseGoalRefines(
                  objectBindings,
                  permissions,
                  Indexed.ReverseGoalKey(
                    stream.continuation.targetNode,
                    intermediateEid
                  )
                ) &&
                ReverseConsumerRefines(
                  objectBindings,
                  grants,
                  Indexed.ReverseGoalKey(
                    stream.continuation.targetNode,
                    intermediateEid
                  ),
                  Indexed.ReverseConsumer(
                    stream.continuation.node,
                    stream.continuation.resourceEid
                  )
                )
      {
        ProjectionAfterChunkPreservesMembership(
          objectBindings,
          relationBindings,
          relationships,
          stream.projection,
          values,
          intermediateEid
        );
      }
    }
  }

  lemma ReverseBufferedWorkPreservesSoundness(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    stream: Indexed.ReverseStream
  )
    requires 0 < |stream.buffered|
    requires forall index | 0 <= index < |stream.buffered| ::
               0 <= stream.buffered[index]
    requires Indexed.ValidReverseContinuationEids(
               stream.continuation
             )
    requires ReverseStreamSound(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               stream
             )
    ensures ReverseQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              Indexed.ReverseBufferedWork(stream)
            )
  {
    ContinueReversePreservesSoundness(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      stream,
      stream.buffered[0]
    );
    var continued :=
      Indexed.ContinueReverse(
        stream.continuation,
        stream.buffered[0]
      );
    var tail :=
      if 1 < |stream.buffered| || stream.more
      then
        [Indexed.ReverseStreamWork(
           Indexed.ReverseStream(
             stream.projection,
             stream.buffered[1..],
             stream.more,
             stream.continuation
           )
         )]
      else [];
    assert ReverseQueueSound(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        tail
      );
    ReverseQueueSoundConcatenation(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      continued,
      tail
    );
  }

  lemma ReverseResponseWorkPreservesSoundness(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    command: Indexed.ScanCommand,
    response: Indexed.ScanResponse,
    validated: Indexed.ValidatedScan,
    continuation: Indexed.ReverseContinuation,
    fullProjectionValues: seq<int>
  )
    requires ExactProjectionValues(
               objectBindings,
               relationBindings,
               relationships,
               command.projection,
               fullProjectionValues
             )
    requires ExactScanResponse(
               command,
               response,
               fullProjectionValues
             )
    requires validated.ScanAccepted?
    requires validated.values == response.values
    requires validated.terminal == response.terminal
    requires validated.fetchedValues == response.fetchedValues
    requires 0 < |validated.values|
    requires Indexed.ValidReverseContinuationEids(continuation)
    requires ReverseStreamSound(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               Indexed.ReverseStream(
                 command.projection,
                 [],
                 true,
                 continuation
               )
             )
    ensures ReverseQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              Indexed.ReverseWorkAfterResponse(
                command,
                continuation,
                validated
              )
            )
  {
    forall candidate <- validated.values
      ensures RelationshipProjectsTo(
                objectBindings,
                relationBindings,
                relationships,
                command.projection,
                candidate
              )
    {
      CertifiedResponseValueBelongsToProjection(
        objectBindings,
        relationBindings,
        relationships,
        command,
        response,
        fullProjectionValues,
        candidate
      );
    }
    var stream :=
      Indexed.ReverseStream(
        command.projection,
        validated.values,
        !validated.terminal,
        continuation
      );
    assert ReverseStreamSound(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        stream
      );
    ReverseStreamSoundAfterChunk(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      stream,
      validated.values
    );
    var continued :=
      Indexed.ContinueReverse(
        continuation,
        validated.values[0]
      );
    ContinueReversePreservesSoundness(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      stream,
      validated.values[0]
    );
    var tail :=
      if 1 < |validated.values| || !validated.terminal
      then
        [Indexed.ReverseStreamWork(
           Indexed.ReverseStream(
             Indexed.ProjectionAfterChunk(
               command.projection,
               validated.values
             ),
             validated.values[1..],
             !validated.terminal,
             continuation
           )
         )]
      else [];
    assert ReverseQueueSound(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        tail
      );
    ReverseQueueSoundConcatenation(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      continued,
      tail
    );
  }

  ghost predicate ForwardStreamSound(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    stream: Indexed.ForwardStream
  ) {
    (forall buffered <- stream.buffered ::
       RelationshipProjectsTo(
         objectBindings,
         relationBindings,
         relationships,
         stream.projection,
         buffered
       )) &&
    match stream.continuation
    case ForwardGrant(node) =>
      forall candidateEid: int |
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          stream.projection,
          candidateEid
        ) ::
        ForwardGrantRefines(
          objectBindings,
          semanticSubject,
          grants,
          Indexed.ForwardGrantKey(node, candidateEid)
        )
    case ForwardArrowRelation(
      node,
      intermediateType,
      viaRelationEid,
      resourceType
      ) =>
      forall intermediateEid: int, resourceEid: int |
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          stream.projection,
          intermediateEid
        ) &&
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.SubjectToResources(
            intermediateType,
            intermediateEid,
            viaRelationEid,
            resourceType,
            Indexed.NoBound
          ),
          resourceEid
        ) ::
        ForwardGrantRefines(
          objectBindings,
          semanticSubject,
          grants,
          Indexed.ForwardGrantKey(node, resourceEid)
        )
  }

  ghost predicate ForwardWorkSound(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    work: Indexed.ForwardWork
  ) {
    if work.ForwardGrantWork?
    then ForwardGrantRefines(
           objectBindings,
           semanticSubject,
           grants,
           work.grant
         )
    else ForwardStreamSound(
        objectBindings,
        relationBindings,
        relationships,
        semanticSubject,
        grants,
        work.stream
      )
  }

  ghost predicate ForwardQueueSound(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    queue: seq<Indexed.ForwardWork>
  ) {
    forall work <- queue ::
      ForwardWorkSound(
        objectBindings,
        relationBindings,
        relationships,
        semanticSubject,
        grants,
        work
      )
  }

  lemma ContinueForwardPreservesSoundness(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    stream: Indexed.ForwardStream,
    candidateEid: int
  )
    requires ForwardStreamSound(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               stream
             )
    requires RelationshipProjectsTo(
               objectBindings,
               relationBindings,
               relationships,
               stream.projection,
               candidateEid
             )
    requires 0 <= candidateEid
    ensures ForwardQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              Indexed.ContinueForward(
                stream.continuation,
                candidateEid
              )
            )
  {
  }

  lemma ForwardQueueSoundConcatenation(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    left: seq<Indexed.ForwardWork>,
    right: seq<Indexed.ForwardWork>
  )
    requires ForwardQueueSound(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               left
             )
    requires ForwardQueueSound(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               right
             )
    ensures ForwardQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              left + right
            )
  {
  }

  lemma ProjectionAfterChunkPreservesMembership(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    projection: Indexed.Projection,
    values: seq<int>,
    candidateEid: int
  )
    requires 0 < |values|
    ensures RelationshipProjectsTo(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ProjectionAfterChunk(projection, values),
              candidateEid
            ) <==>
            RelationshipProjectsTo(
              objectBindings,
              relationBindings,
              relationships,
              projection,
              candidateEid
            )
  {
  }

  lemma ForwardStreamSoundAfterChunk(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    stream: Indexed.ForwardStream,
    values: seq<int>
  )
    requires 0 < |values|
    requires ForwardStreamSound(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               stream
             )
    requires forall candidate <- values ::
               RelationshipProjectsTo(
                 objectBindings,
                 relationBindings,
                 relationships,
                 stream.projection,
                 candidate
               )
    ensures ForwardStreamSound(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              Indexed.ForwardStream(
                Indexed.ProjectionAfterChunk(
                  stream.projection,
                  values
                ),
                values[1..],
                stream.more,
                stream.continuation
              )
            )
  {
    forall candidate <- values
      ensures RelationshipProjectsTo(
                objectBindings,
                relationBindings,
                relationships,
                Indexed.ProjectionAfterChunk(
                  stream.projection,
                  values
                ),
                candidate
              )
    {
      ProjectionAfterChunkPreservesMembership(
        objectBindings,
        relationBindings,
        relationships,
        stream.projection,
        values,
        candidate
      );
    }
    match stream.continuation
    case ForwardGrant(_) => {
      forall candidateEid: int |
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ProjectionAfterChunk(
            stream.projection,
            values
          ),
          candidateEid
        )
        ensures ForwardGrantRefines(
                  objectBindings,
                  semanticSubject,
                  grants,
                  Indexed.ForwardGrantKey(
                    stream.continuation.node,
                    candidateEid
                  )
                )
      {
        ProjectionAfterChunkPreservesMembership(
          objectBindings,
          relationBindings,
          relationships,
          stream.projection,
          values,
          candidateEid
        );
      }
    }
    case ForwardArrowRelation(_, _, _, _) => {
      forall intermediateEid: int, resourceEid: int |
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ProjectionAfterChunk(
            stream.projection,
            values
          ),
          intermediateEid
        ) &&
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.SubjectToResources(
            stream.continuation.intermediateType,
            intermediateEid,
            stream.continuation.viaRelationEid,
            stream.continuation.resourceType,
            Indexed.NoBound
          ),
          resourceEid
        )
        ensures ForwardGrantRefines(
                  objectBindings,
                  semanticSubject,
                  grants,
                  Indexed.ForwardGrantKey(
                    stream.continuation.node,
                    resourceEid
                  )
                )
      {
        ProjectionAfterChunkPreservesMembership(
          objectBindings,
          relationBindings,
          relationships,
          stream.projection,
          values,
          intermediateEid
        );
      }
    }
  }

  lemma ForwardBufferedWorkPreservesSoundness(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    stream: Indexed.ForwardStream
  )
    requires 0 < |stream.buffered|
    requires forall index | 0 <= index < |stream.buffered| ::
               0 <= stream.buffered[index]
    requires ForwardStreamSound(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               stream
             )
    ensures ForwardQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              Indexed.ForwardBufferedWork(stream)
            )
  {
    assert 0 <= stream.buffered[0];
    ContinueForwardPreservesSoundness(
      objectBindings,
      relationBindings,
      relationships,
      semanticSubject,
      grants,
      stream,
      stream.buffered[0]
    );
    var continued :=
      Indexed.ContinueForward(
        stream.continuation,
        stream.buffered[0]
      );
    var tail :=
      if 1 < |stream.buffered| || stream.more
      then
        [Indexed.ForwardStreamWork(
           Indexed.ForwardStream(
             stream.projection,
             stream.buffered[1..],
             stream.more,
             stream.continuation
           )
         )]
      else [];
    assert ForwardQueueSound(
        objectBindings,
        relationBindings,
        relationships,
        semanticSubject,
        grants,
        tail
      );
    ForwardQueueSoundConcatenation(
      objectBindings,
      relationBindings,
      relationships,
      semanticSubject,
      grants,
      continued,
      tail
    );
  }

  lemma ForwardResponseWorkPreservesSoundness(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    command: Indexed.ScanCommand,
    response: Indexed.ScanResponse,
    validated: Indexed.ValidatedScan,
    continuation: Indexed.ForwardContinuation,
    fullProjectionValues: seq<int>
  )
    requires ExactProjectionValues(
               objectBindings,
               relationBindings,
               relationships,
               command.projection,
               fullProjectionValues
             )
    requires ExactScanResponse(
               command,
               response,
               fullProjectionValues
             )
    requires validated.ScanAccepted?
    requires validated.values == response.values
    requires validated.terminal == response.terminal
    requires validated.fetchedValues == response.fetchedValues
    requires 0 < |validated.values|
    requires ForwardStreamSound(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               Indexed.ForwardStream(
                 command.projection,
                 [],
                 true,
                 continuation
               )
             )
    ensures ForwardQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              Indexed.ForwardWorkAfterResponse(
                command,
                continuation,
                validated
              )
            )
  {
    forall candidate <- validated.values
      ensures RelationshipProjectsTo(
                objectBindings,
                relationBindings,
                relationships,
                command.projection,
                candidate
              )
    {
      CertifiedResponseValueBelongsToProjection(
        objectBindings,
        relationBindings,
        relationships,
        command,
        response,
        fullProjectionValues,
        candidate
      );
    }
    var stream :=
      Indexed.ForwardStream(
        command.projection,
        validated.values,
        !validated.terminal,
        continuation
      );
    assert ForwardStreamSound(
        objectBindings,
        relationBindings,
        relationships,
        semanticSubject,
        grants,
        stream
      );
    ForwardStreamSoundAfterChunk(
      objectBindings,
      relationBindings,
      relationships,
      semanticSubject,
      grants,
      stream,
      validated.values
    );
    var continued :=
      Indexed.ContinueForward(
        continuation,
        validated.values[0]
      );
    ContinueForwardPreservesSoundness(
      objectBindings,
      relationBindings,
      relationships,
      semanticSubject,
      grants,
      stream,
      validated.values[0]
    );
    var tail :=
      if 1 < |validated.values| || !validated.terminal
      then
        [Indexed.ForwardStreamWork(
           Indexed.ForwardStream(
             Indexed.ProjectionAfterChunk(
               command.projection,
               validated.values
             ),
             validated.values[1..],
             !validated.terminal,
             continuation
           )
         )]
      else [];
    assert ForwardQueueSound(
        objectBindings,
        relationBindings,
        relationships,
        semanticSubject,
        grants,
        tail
      );
    ForwardQueueSoundConcatenation(
      objectBindings,
      relationBindings,
      relationships,
      semanticSubject,
      grants,
      continued,
      tail
    );
  }

  lemma DirectIndexedDerivationIsSound(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    normalizedRule: Semantics.NormalizedRule,
    indexedRule: Indexed.IndexedRule,
    subjectBinding: ObjectBinding,
    resourceBinding: ObjectBinding,
    relationship: Semantics.Relationship
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires UniqueRelationBindingEids(relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires normalizedRule in normalizedRules
    requires indexedRule.RelationRule?
    requires IndexedRuleRefines(
               indexedRule,
               normalizedRule,
               relationBindings
             )
    requires subjectBinding in objectBindings
    requires resourceBinding in objectBindings
    requires subjectBinding.objectRef.typeName ==
             indexedRule.subjectType
    requires resourceBinding.objectRef.typeName ==
             indexedRule.head.resourceType
    requires IndexedRulePermissionClosed(indexedRule, permissions)
    requires relationship in relationships
    requires relationship.resource == resourceBinding.objectRef
    requires relationship.subject == subjectBinding.objectRef
    requires exists relationBinding <- relationBindings ::
               relationBinding.eid == indexedRule.relationEid &&
               relationBinding.relation ==
               Semantics.RelationNode(
                 indexedRule.head.resourceType,
                 relationship.relationName,
                 indexedRule.subjectType
               )
    ensures Semantics.Grant(
              subjectBinding.objectRef,
              indexedRule.head,
              resourceBinding.objectRef
            ) in grants
  {
    var semanticBinding :| semanticBinding in relationBindings &&
                           semanticBinding.eid ==
                           indexedRule.relationEid &&
                           RelationBindingMatches(
                             semanticBinding,
                             indexedRule.head.resourceType,
                             normalizedRule.relationName,
                             indexedRule.subjectType
                           );
    var relationshipBinding :|
      relationshipBinding in relationBindings &&
      relationshipBinding.eid == indexedRule.relationEid &&
      relationshipBinding.relation ==
      Semantics.RelationNode(
        indexedRule.head.resourceType,
        relationship.relationName,
        indexedRule.subjectType
      );
    UniqueRelationEidIdentifiesBinding(
      relationBindings,
      semanticBinding,
      relationshipBinding
    );
    assert normalizedRule.DirectRelationRule?;
    assert normalizedRule.relationName == relationship.relationName;
    var grant :=
      Semantics.Grant(
        subjectBinding.objectRef,
        indexedRule.head,
        resourceBinding.objectRef
      );
    assert Semantics.HasRelationship(
        relationships,
        resourceBinding.objectRef,
        normalizedRule.relationName,
        subjectBinding.objectRef
      );
    GrantBelongsToUniverse(
      objects,
      permissions,
      subjectBinding.objectRef,
      indexedRule.head,
      resourceBinding.objectRef
    );
    assert Semantics.RuleDerives(
        normalizedRule,
        relationships,
        grants,
        grant
      );
    RuleDerivationBelongsToFixedPoint(
      objects,
      permissions,
      normalizedRules,
      relationships,
      grants,
      normalizedRule,
      grant
    );
  }

  lemma SelfPermissionIndexedDerivationIsSound(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    grants: set<Semantics.Grant>,
    normalizedRule: Semantics.NormalizedRule,
    indexedRule: Indexed.IndexedRule,
    subjectBinding: ObjectBinding,
    resourceBinding: ObjectBinding
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires normalizedRule in normalizedRules
    requires indexedRule.SelfPermissionRule?
    requires IndexedRuleRefines(indexedRule, normalizedRule, [])
    requires subjectBinding in objectBindings
    requires resourceBinding in objectBindings
    requires resourceBinding.objectRef.typeName ==
             indexedRule.head.resourceType
    requires indexedRule.head in permissions
    requires Semantics.Grant(
               subjectBinding.objectRef,
               indexedRule.targetNode,
               resourceBinding.objectRef
             ) in grants
    ensures Semantics.Grant(
              subjectBinding.objectRef,
              indexedRule.head,
              resourceBinding.objectRef
            ) in grants
  {
    var grant :=
      Semantics.Grant(
        subjectBinding.objectRef,
        indexedRule.head,
        resourceBinding.objectRef
      );
    GrantBelongsToUniverse(
      objects,
      permissions,
      subjectBinding.objectRef,
      indexedRule.head,
      resourceBinding.objectRef
    );
    assert Semantics.RuleDerives(
        normalizedRule,
        relationships,
        grants,
        grant
      );
    RuleDerivationBelongsToFixedPoint(
      objects,
      permissions,
      normalizedRules,
      relationships,
      grants,
      normalizedRule,
      grant
    );
  }

  lemma ArrowRelationIndexedDerivationIsSound(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    normalizedRule: Semantics.NormalizedRule,
    indexedRule: Indexed.IndexedRule,
    subjectBinding: ObjectBinding,
    intermediateBinding: ObjectBinding,
    resourceBinding: ObjectBinding,
    viaRelationship: Semantics.Relationship,
    targetRelationship: Semantics.Relationship
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires UniqueRelationBindingEids(relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires normalizedRule in normalizedRules
    requires indexedRule.ArrowRelationRule?
    requires IndexedRuleRefines(
               indexedRule,
               normalizedRule,
               relationBindings
             )
    requires subjectBinding in objectBindings
    requires intermediateBinding in objectBindings
    requires resourceBinding in objectBindings
    requires subjectBinding.objectRef.typeName ==
             indexedRule.targetSubjectType
    requires intermediateBinding.objectRef.typeName ==
             indexedRule.intermediateType
    requires resourceBinding.objectRef.typeName ==
             indexedRule.head.resourceType
    requires indexedRule.head in permissions
    requires viaRelationship in relationships
    requires viaRelationship.resource == resourceBinding.objectRef
    requires viaRelationship.subject == intermediateBinding.objectRef
    requires targetRelationship in relationships
    requires targetRelationship.resource == intermediateBinding.objectRef
    requires targetRelationship.subject == subjectBinding.objectRef
    requires exists viaBinding <- relationBindings ::
               viaBinding.eid == indexedRule.viaRelationEid &&
               viaBinding.relation ==
               Semantics.RelationNode(
                 indexedRule.head.resourceType,
                 viaRelationship.relationName,
                 indexedRule.intermediateType
               )
    requires exists targetBinding <- relationBindings ::
               targetBinding.eid == indexedRule.targetRelationEid &&
               targetBinding.relation ==
               Semantics.RelationNode(
                 indexedRule.intermediateType,
                 targetRelationship.relationName,
                 indexedRule.targetSubjectType
               )
    ensures Semantics.Grant(
              subjectBinding.objectRef,
              indexedRule.head,
              resourceBinding.objectRef
            ) in grants
  {
    var semanticVia :| semanticVia in relationBindings &&
                       semanticVia.eid == indexedRule.viaRelationEid &&
                       RelationBindingMatches(
                         semanticVia,
                         indexedRule.head.resourceType,
                         normalizedRule.viaRelation,
                         indexedRule.intermediateType
                       );
    var actualVia :| actualVia in relationBindings &&
                     actualVia.eid == indexedRule.viaRelationEid &&
                     actualVia.relation ==
                     Semantics.RelationNode(
                       indexedRule.head.resourceType,
                       viaRelationship.relationName,
                       indexedRule.intermediateType
                     );
    UniqueRelationEidIdentifiesBinding(
      relationBindings,
      semanticVia,
      actualVia
    );
    var semanticTarget :|
      semanticTarget in relationBindings &&
      semanticTarget.eid == indexedRule.targetRelationEid &&
      RelationBindingMatches(
        semanticTarget,
        indexedRule.intermediateType,
        normalizedRule.targetRelation,
        indexedRule.targetSubjectType
      );
    var actualTarget :|
      actualTarget in relationBindings &&
      actualTarget.eid == indexedRule.targetRelationEid &&
      actualTarget.relation ==
      Semantics.RelationNode(
        indexedRule.intermediateType,
        targetRelationship.relationName,
        indexedRule.targetSubjectType
      );
    UniqueRelationEidIdentifiesBinding(
      relationBindings,
      semanticTarget,
      actualTarget
    );
    assert normalizedRule.viaRelation ==
           viaRelationship.relationName;
    assert normalizedRule.targetRelation ==
           targetRelationship.relationName;
    var grant :=
      Semantics.Grant(
        subjectBinding.objectRef,
        indexedRule.head,
        resourceBinding.objectRef
      );
    GrantBelongsToUniverse(
      objects,
      permissions,
      subjectBinding.objectRef,
      indexedRule.head,
      resourceBinding.objectRef
    );
    assert Semantics.HasRelationship(
        relationships,
        intermediateBinding.objectRef,
        normalizedRule.targetRelation,
        subjectBinding.objectRef
      );
    assert Semantics.RuleDerives(
        normalizedRule,
        relationships,
        grants,
        grant
      );
    RuleDerivationBelongsToFixedPoint(
      objects,
      permissions,
      normalizedRules,
      relationships,
      grants,
      normalizedRule,
      grant
    );
  }

  lemma ArrowPermissionIndexedDerivationIsSound(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    normalizedRule: Semantics.NormalizedRule,
    indexedRule: Indexed.IndexedRule,
    subjectBinding: ObjectBinding,
    intermediateBinding: ObjectBinding,
    resourceBinding: ObjectBinding,
    viaRelationship: Semantics.Relationship
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires UniqueRelationBindingEids(relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires normalizedRule in normalizedRules
    requires indexedRule.ArrowPermissionRule?
    requires IndexedRuleRefines(
               indexedRule,
               normalizedRule,
               relationBindings
             )
    requires subjectBinding in objectBindings
    requires intermediateBinding in objectBindings
    requires resourceBinding in objectBindings
    requires intermediateBinding.objectRef.typeName ==
             indexedRule.intermediateType
    requires resourceBinding.objectRef.typeName ==
             indexedRule.head.resourceType
    requires indexedRule.head in permissions
    requires viaRelationship in relationships
    requires viaRelationship.resource == resourceBinding.objectRef
    requires viaRelationship.subject == intermediateBinding.objectRef
    requires exists viaBinding <- relationBindings ::
               viaBinding.eid == indexedRule.viaRelationEid &&
               viaBinding.relation ==
               Semantics.RelationNode(
                 indexedRule.head.resourceType,
                 viaRelationship.relationName,
                 indexedRule.intermediateType
               )
    requires Semantics.Grant(
               subjectBinding.objectRef,
               indexedRule.targetNode,
               intermediateBinding.objectRef
             ) in grants
    ensures Semantics.Grant(
              subjectBinding.objectRef,
              indexedRule.head,
              resourceBinding.objectRef
            ) in grants
  {
    var semanticVia :| semanticVia in relationBindings &&
                       semanticVia.eid == indexedRule.viaRelationEid &&
                       RelationBindingMatches(
                         semanticVia,
                         indexedRule.head.resourceType,
                         normalizedRule.viaRelation,
                         indexedRule.intermediateType
                       );
    var actualVia :| actualVia in relationBindings &&
                     actualVia.eid == indexedRule.viaRelationEid &&
                     actualVia.relation ==
                     Semantics.RelationNode(
                       indexedRule.head.resourceType,
                       viaRelationship.relationName,
                       indexedRule.intermediateType
                     );
    UniqueRelationEidIdentifiesBinding(
      relationBindings,
      semanticVia,
      actualVia
    );
    assert normalizedRule.viaRelation ==
           viaRelationship.relationName;
    var grant :=
      Semantics.Grant(
        subjectBinding.objectRef,
        indexedRule.head,
        resourceBinding.objectRef
      );
    GrantBelongsToUniverse(
      objects,
      permissions,
      subjectBinding.objectRef,
      indexedRule.head,
      resourceBinding.objectRef
    );
    assert Semantics.RuleDerives(
        normalizedRule,
        relationships,
        grants,
        grant
      );
    RuleDerivationBelongsToFixedPoint(
      objects,
      permissions,
      normalizedRules,
      relationships,
      grants,
      normalizedRule,
      grant
    );
  }

  lemma ForwardConsumerWorkPreservesSoundness(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    semanticSubject: Semantics.ObjectRef,
    indexedRule: Indexed.IndexedRule,
    indexedGrant: Indexed.ForwardGrantKey
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires indexedRule in indexedRules
    requires Indexed.ValidIndexedRule(indexedRule)
    requires semanticSubject in objects
    requires ForwardGrantRefines(
               objectBindings,
               semanticSubject,
               grants,
               indexedGrant
             )
    requires indexedRule.SelfPermissionRule? ||
             indexedRule.ArrowPermissionRule?
    requires indexedRule.SelfPermissionRule? ==>
               indexedRule.targetNode == indexedGrant.node
    requires indexedRule.ArrowPermissionRule? ==>
               indexedRule.targetNode == indexedGrant.node
    requires indexedRule.head in permissions
    ensures ForwardQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              Indexed.ForwardConsumerWork(
                indexedRule,
                indexedGrant
              )
            )
  {
    EveryIndexedRuleHasSemanticWitness(
      normalizedRules,
      indexedRules,
      relationBindings,
      indexedRule
    );
    var normalizedRule :| normalizedRule in normalizedRules &&
                          IndexedRuleRefines(
                            indexedRule,
                            normalizedRule,
                            relationBindings
                          );
    var subjectBinding :| subjectBinding in objectBindings &&
                          subjectBinding.objectRef == semanticSubject;
    var intermediateBinding :|
      intermediateBinding in objectBindings &&
      intermediateBinding.eid == indexedGrant.resourceEid &&
      Semantics.Grant(
        semanticSubject,
        indexedGrant.node,
        intermediateBinding.objectRef
      ) in grants;
    match indexedRule
    case SelfPermissionRule(_, _) => {
      SelfPermissionIndexedDerivationIsSound(
        objects,
        permissions,
        normalizedRules,
        relationships,
        objectBindings,
        grants,
        normalizedRule,
        indexedRule,
        subjectBinding,
        intermediateBinding
      );
    }
    case ArrowPermissionRule(
      head,
        viaRelationEid,
        intermediateType,
        _
        ) => {
      var generatedStream :=
        Indexed.ForwardStream(
          Indexed.SubjectToResources(
            intermediateType,
            indexedGrant.resourceEid,
            viaRelationEid,
            head.resourceType,
            Indexed.NoBound
          ),
          [],
          true,
          Indexed.ForwardGrant(head)
        );
      forall candidateEid: int |
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          generatedStream.projection,
          candidateEid
        )
        ensures ForwardGrantRefines(
                  objectBindings,
                  semanticSubject,
                  grants,
                  Indexed.ForwardGrantKey(head, candidateEid)
                )
      {
        var projectedIntermediate :|
          projectedIntermediate in objectBindings &&
          projectedIntermediate.eid == indexedGrant.resourceEid &&
          projectedIntermediate.objectRef.typeName ==
          intermediateType;
        var resourceBinding :|
          resourceBinding in objectBindings &&
          resourceBinding.eid == candidateEid &&
          resourceBinding.objectRef.typeName == head.resourceType;
        var relationBinding :|
          relationBinding in relationBindings &&
          relationBinding.eid == viaRelationEid;
        var relationship :|
          relationship in relationships &&
          relationship ==
          Semantics.Relationship(
            resourceBinding.objectRef,
            relationship.relationName,
            projectedIntermediate.objectRef
          ) &&
          relationBinding.relation ==
          Semantics.RelationNode(
            head.resourceType,
            relationship.relationName,
            intermediateType
          );
        UniqueObjectEidIdentifiesBinding(
          objectBindings,
          intermediateBinding,
          projectedIntermediate
        );
        ArrowPermissionIndexedDerivationIsSound(
          objects,
          permissions,
          normalizedRules,
          relationships,
          objectBindings,
          relationBindings,
          grants,
          normalizedRule,
          indexedRule,
          subjectBinding,
          intermediateBinding,
          resourceBinding,
          relationship
        );
      }
      assert ForwardStreamSound(
          objectBindings,
          relationBindings,
          relationships,
          semanticSubject,
          grants,
          generatedStream
        );
    }
    case RelationRule(_, _, _) => {
      assert false;
    }
    case ArrowRelationRule(_, _, _, _, _) => {
      assert false;
    }
  }

  lemma ForwardConsumerWorksPreserveSoundness(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    semanticSubject: Semantics.ObjectRef,
    consumerRules: seq<Indexed.IndexedRule>,
    indexedGrant: Indexed.ForwardGrantKey
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires forall rule <- consumerRules ::
               rule in indexedRules &&
               (rule.SelfPermissionRule? ||
                rule.ArrowPermissionRule?) &&
               rule.targetNode == indexedGrant.node &&
               rule.head in permissions
    requires Indexed.ValidIndexedRules(consumerRules)
    requires semanticSubject in objects
    requires ForwardGrantRefines(
               objectBindings,
               semanticSubject,
               grants,
               indexedGrant
             )
    ensures ForwardQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              Indexed.ForwardConsumerWorks(
                consumerRules,
                indexedGrant
              )
            )
    decreases |consumerRules|
  {
    if |consumerRules| != 0 {
      ForwardConsumerWorkPreservesSoundness(
        objects,
        relations,
        permissions,
        normalizedRules,
        indexedRules,
        relationships,
        objectBindings,
        relationBindings,
        grants,
        semanticSubject,
        consumerRules[0],
        indexedGrant
      );
      ForwardConsumerWorksPreserveSoundness(
        objects,
        relations,
        permissions,
        normalizedRules,
        indexedRules,
        relationships,
        objectBindings,
        relationBindings,
        grants,
        semanticSubject,
        consumerRules[1..],
        indexedGrant
      );
      ForwardQueueSoundConcatenation(
        objectBindings,
        relationBindings,
        relationships,
        semanticSubject,
        grants,
        Indexed.ForwardConsumerWork(
          consumerRules[0],
          indexedGrant
        ),
        Indexed.ForwardConsumerWorks(
          consumerRules[1..],
          indexedGrant
        )
      );
    }
  }

  lemma SubjectToResourcesWitness(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    subjectType: string,
    subjectEid: int,
    relationEid: int,
    resourceType: string,
    candidateEid: int
  ) returns (
      subjectBinding: ObjectBinding,
      resourceBinding: ObjectBinding,
      relationBinding: RelationBinding,
      relationship: Semantics.Relationship
    )
    requires RelationshipProjectsTo(
               objectBindings,
               relationBindings,
               relationships,
               Indexed.SubjectToResources(
                 subjectType,
                 subjectEid,
                 relationEid,
                 resourceType,
                 Indexed.NoBound
               ),
               candidateEid
             )
    ensures subjectBinding in objectBindings
    ensures subjectBinding.eid == subjectEid
    ensures subjectBinding.objectRef.typeName == subjectType
    ensures resourceBinding in objectBindings
    ensures resourceBinding.eid == candidateEid
    ensures resourceBinding.objectRef.typeName == resourceType
    ensures relationBinding in relationBindings
    ensures relationBinding.eid == relationEid
    ensures relationship in relationships
    ensures relationship ==
            Semantics.Relationship(
              resourceBinding.objectRef,
              relationship.relationName,
              subjectBinding.objectRef
            )
    ensures relationBinding.relation ==
            Semantics.RelationNode(
              resourceType,
              relationship.relationName,
              subjectType
            )
  {
    var selectedSubject,
        selectedResource,
        selectedRelation,
        selectedRelationship :|
      selectedSubject in objectBindings &&
      selectedSubject.eid == subjectEid &&
      selectedSubject.objectRef.typeName == subjectType &&
      selectedResource in objectBindings &&
      selectedResource.eid == candidateEid &&
      selectedResource.objectRef.typeName == resourceType &&
      selectedRelation in relationBindings &&
      selectedRelation.eid == relationEid &&
      selectedRelationship in relationships &&
      selectedRelationship ==
      Semantics.Relationship(
        selectedResource.objectRef,
        selectedRelationship.relationName,
        selectedSubject.objectRef
      ) &&
      selectedRelation.relation ==
      Semantics.RelationNode(
        resourceType,
        selectedRelationship.relationName,
        subjectType
      );
    return selectedSubject,
        selectedResource,
        selectedRelation,
        selectedRelationship;
  }

  lemma ResourceToSubjectsWitness(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    resourceType: string,
    resourceEid: int,
    relationEid: int,
    subjectType: string,
    candidateEid: int
  ) returns (
      resourceBinding: ObjectBinding,
      subjectBinding: ObjectBinding,
      relationBinding: RelationBinding,
      relationship: Semantics.Relationship
    )
    requires RelationshipProjectsTo(
               objectBindings,
               relationBindings,
               relationships,
               Indexed.ResourceToSubjects(
                 resourceType,
                 resourceEid,
                 relationEid,
                 subjectType,
                 Indexed.NoBound
               ),
               candidateEid
             )
    ensures resourceBinding in objectBindings
    ensures resourceBinding.eid == resourceEid
    ensures resourceBinding.objectRef.typeName == resourceType
    ensures subjectBinding in objectBindings
    ensures subjectBinding.eid == candidateEid
    ensures subjectBinding.objectRef.typeName == subjectType
    ensures relationBinding in relationBindings
    ensures relationBinding.eid == relationEid
    ensures relationship in relationships
    ensures relationship ==
            Semantics.Relationship(
              resourceBinding.objectRef,
              relationship.relationName,
              subjectBinding.objectRef
            )
    ensures relationBinding.relation ==
            Semantics.RelationNode(
              resourceType,
              relationship.relationName,
              subjectType
            )
  {
    var selectedResource,
        selectedSubject,
        selectedRelation,
        selectedRelationship :|
      selectedResource in objectBindings &&
      selectedResource.eid == resourceEid &&
      selectedResource.objectRef.typeName == resourceType &&
      selectedSubject in objectBindings &&
      selectedSubject.eid == candidateEid &&
      selectedSubject.objectRef.typeName == subjectType &&
      selectedRelation in relationBindings &&
      selectedRelation.eid == relationEid &&
      selectedRelationship in relationships &&
      selectedRelationship ==
      Semantics.Relationship(
        selectedResource.objectRef,
        selectedRelationship.relationName,
        selectedSubject.objectRef
      ) &&
      selectedRelation.relation ==
      Semantics.RelationNode(
        resourceType,
        selectedRelationship.relationName,
        subjectType
      );
    return selectedResource,
        selectedSubject,
        selectedRelation,
        selectedRelationship;
  }

  lemma {:isolate_assertions} ForwardSeedWorkPreservesSoundness(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    subjectType: string,
    subjectEid: int,
    semanticSubject: Semantics.ObjectRef,
    indexedRule: Indexed.IndexedRule
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires indexedRule in indexedRules
    requires Indexed.ValidIndexedRule(indexedRule)
    requires semanticSubject in objects
    requires 0 <= subjectEid
    requires exists subjectBinding <- objectBindings ::
               subjectBinding.eid == subjectEid &&
               subjectBinding.objectRef == semanticSubject &&
               subjectBinding.objectRef.typeName == subjectType
    requires indexedRule.head in permissions
    ensures ForwardQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              Indexed.ForwardSeedWork(
                indexedRule,
                subjectType,
                subjectEid
              )
            )
  {
    EveryIndexedRuleHasSemanticWitness(
      normalizedRules,
      indexedRules,
      relationBindings,
      indexedRule
    );
    var normalizedRule :| normalizedRule in normalizedRules &&
                          IndexedRuleRefines(
                            indexedRule,
                            normalizedRule,
                            relationBindings
                          );
    var subjectBinding :| subjectBinding in objectBindings &&
                          subjectBinding.eid == subjectEid &&
                          subjectBinding.objectRef == semanticSubject &&
                          subjectBinding.objectRef.typeName == subjectType;
    match indexedRule
    case RelationRule(head, relationEid, ruleSubjectType) => {
      if subjectType == ruleSubjectType {
        var generatedStream :=
          Indexed.ForwardStream(
            Indexed.SubjectToResources(
              subjectType,
              subjectEid,
              relationEid,
              head.resourceType,
              Indexed.NoBound
            ),
            [],
            true,
            Indexed.ForwardGrant(head)
          );
        forall candidateEid: int |
          RelationshipProjectsTo(
            objectBindings,
            relationBindings,
            relationships,
            generatedStream.projection,
            candidateEid
          )
          ensures ForwardGrantRefines(
                    objectBindings,
                    semanticSubject,
                    grants,
                    Indexed.ForwardGrantKey(head, candidateEid)
                  )
        {
          var projectedSubject,
              resourceBinding,
              relationBinding,
              relationship :=
            SubjectToResourcesWitness(
              objectBindings,
              relationBindings,
              relationships,
              subjectType,
              subjectEid,
              relationEid,
              head.resourceType,
              candidateEid
            );
          UniqueObjectEidIdentifiesBinding(
            objectBindings,
            subjectBinding,
            projectedSubject
          );
          DirectIndexedDerivationIsSound(
            objects,
            permissions,
            normalizedRules,
            relationships,
            objectBindings,
            relationBindings,
            grants,
            normalizedRule,
            indexedRule,
            subjectBinding,
            resourceBinding,
            relationship
          );
        }
        assert ForwardStreamSound(
            objectBindings,
            relationBindings,
            relationships,
            semanticSubject,
            grants,
            generatedStream
          );
      }
    }
    case ArrowRelationRule(
      head,
        viaRelationEid,
        intermediateType,
        targetRelationEid,
        targetSubjectType
        ) => {
      if subjectType == targetSubjectType {
        var generatedStream :=
          Indexed.ForwardStream(
            Indexed.SubjectToResources(
              subjectType,
              subjectEid,
              targetRelationEid,
              intermediateType,
              Indexed.NoBound
            ),
            [],
            true,
            Indexed.ForwardArrowRelation(
              head,
              intermediateType,
              viaRelationEid,
              head.resourceType
            )
          );
        forall intermediateEid: int, resourceEid: int |
          RelationshipProjectsTo(
            objectBindings,
            relationBindings,
            relationships,
            generatedStream.projection,
            intermediateEid
          ) &&
          RelationshipProjectsTo(
            objectBindings,
            relationBindings,
            relationships,
            Indexed.SubjectToResources(
              intermediateType,
              intermediateEid,
              viaRelationEid,
              head.resourceType,
              Indexed.NoBound
            ),
            resourceEid
          )
          ensures ForwardGrantRefines(
                    objectBindings,
                    semanticSubject,
                    grants,
                    Indexed.ForwardGrantKey(head, resourceEid)
                  )
        {
          var projectedSubject,
              intermediateBinding,
              targetBinding,
              targetRelationship :=
            SubjectToResourcesWitness(
              objectBindings,
              relationBindings,
              relationships,
              subjectType,
              subjectEid,
              targetRelationEid,
              intermediateType,
              intermediateEid
            );
          var projectedIntermediate,
              resourceBinding,
              viaBinding,
              viaRelationship :=
            SubjectToResourcesWitness(
              objectBindings,
              relationBindings,
              relationships,
              intermediateType,
              intermediateEid,
              viaRelationEid,
              head.resourceType,
              resourceEid
            );
          UniqueObjectEidIdentifiesBinding(
            objectBindings,
            subjectBinding,
            projectedSubject
          );
          UniqueObjectEidIdentifiesBinding(
            objectBindings,
            intermediateBinding,
            projectedIntermediate
          );
          ArrowRelationIndexedDerivationIsSound(
            objects,
            permissions,
            normalizedRules,
            relationships,
            objectBindings,
            relationBindings,
            grants,
            normalizedRule,
            indexedRule,
            subjectBinding,
            intermediateBinding,
            resourceBinding,
            viaRelationship,
            targetRelationship
          );
        }
        assert ForwardStreamSound(
            objectBindings,
            relationBindings,
            relationships,
            semanticSubject,
            grants,
            generatedStream
          );
      }
    }
    case SelfPermissionRule(_, _) => {
    }
    case ArrowPermissionRule(_, _, _, _) => {
    }
  }

  lemma ForwardSeedWorksPreserveSoundness(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    allIndexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    subjectType: string,
    subjectEid: int,
    semanticSubject: Semantics.ObjectRef
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               allIndexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(seedRules)
    requires forall rule <- seedRules :: rule in allIndexedRules
    requires semanticSubject in objects
    requires 0 <= subjectEid
    requires exists subjectBinding <- objectBindings ::
               subjectBinding.eid == subjectEid &&
               subjectBinding.objectRef == semanticSubject &&
               subjectBinding.objectRef.typeName == subjectType
    requires forall rule <- seedRules :: rule.head in permissions
    ensures ForwardQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              Indexed.ForwardSeedWorks(
                seedRules,
                subjectType,
                subjectEid
              )
            )
    decreases |seedRules|
  {
    if |seedRules| != 0 {
      ForwardSeedWorkPreservesSoundness(
        objects,
        relations,
        permissions,
        normalizedRules,
        allIndexedRules,
        relationships,
        objectBindings,
        relationBindings,
        grants,
        subjectType,
        subjectEid,
        semanticSubject,
        seedRules[0]
      );
      ForwardSeedWorksPreserveSoundness(
        objects,
        relations,
        permissions,
        normalizedRules,
        allIndexedRules,
        seedRules[1..],
        relationships,
        objectBindings,
        relationBindings,
        grants,
        subjectType,
        subjectEid,
        semanticSubject
      );
      ForwardQueueSoundConcatenation(
        objectBindings,
        relationBindings,
        relationships,
        semanticSubject,
        grants,
        Indexed.ForwardSeedWork(
          seedRules[0],
          subjectType,
          subjectEid
        ),
        Indexed.ForwardSeedWorks(
          seedRules[1..],
          subjectType,
          subjectEid
        )
      );
    }
  }

  predicate ForwardConsumersRefine(
    consumers:
    map<Semantics.PermissionNode, seq<Indexed.IndexedRule>>,
    indexedRules: seq<Indexed.IndexedRule>
  ) {
    forall node <- consumers.Keys, rule <- consumers[node] ::
      rule in indexedRules &&
      (rule.SelfPermissionRule? ||
       rule.ArrowPermissionRule?) &&
      rule.targetNode == node
  }

  lemma AddForwardConsumerPreservesRefinement(
    consumers:
    map<Semantics.PermissionNode, seq<Indexed.IndexedRule>>,
    previousRules: seq<Indexed.IndexedRule>,
    rule: Indexed.IndexedRule
  )
    requires ForwardConsumersRefine(consumers, previousRules)
    ensures ForwardConsumersRefine(
              Indexed.AddForwardConsumer(consumers, rule),
              [rule] + previousRules
            )
  {
  }

  lemma ForwardConsumersRefineRules(
    indexedRules: seq<Indexed.IndexedRule>
  )
    requires Indexed.ValidIndexedRules(indexedRules)
    ensures ForwardConsumersRefine(
              Indexed.ForwardConsumers(indexedRules),
              indexedRules
            )
    decreases |indexedRules|
  {
    if |indexedRules| != 0 {
      ForwardConsumersRefineRules(indexedRules[1..]);
      AddForwardConsumerPreservesRefinement(
        Indexed.ForwardConsumers(indexedRules[1..]),
        indexedRules[1..],
        indexedRules[0]
      );
      assert indexedRules == [indexedRules[0]] + indexedRules[1..];
    }
  }

  ghost predicate PendingForwardScanSound(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    pending: Indexed.ForwardPending
  ) {
    pending.NoForwardPending? ||
    ForwardStreamSound(
      objectBindings,
      relationBindings,
      relationships,
      semanticSubject,
      grants,
      Indexed.ForwardStream(
        pending.command.projection,
        [],
        true,
        pending.continuation
      )
    )
  }

  ghost predicate ForwardStateRefines(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    indexedRules: seq<Indexed.IndexedRule>,
    state: Indexed.ForwardState
  ) {
    ForwardQueueSound(
      objectBindings,
      relationBindings,
      relationships,
      semanticSubject,
      grants,
      state.queue
    ) &&
    (forall indexedGrant <- state.seen ::
       ForwardGrantRefines(
         objectBindings,
         semanticSubject,
         grants,
         indexedGrant
       )) &&
    (forall emittedEid <- state.emitted ::
       ForwardGrantRefines(
         objectBindings,
         semanticSubject,
         grants,
         Indexed.ForwardGrantKey(state.rootNode, emittedEid)
       )) &&
    ForwardConsumersRefine(state.consumers, indexedRules) &&
    PendingForwardScanSound(
      objectBindings,
      relationBindings,
      relationships,
      semanticSubject,
      grants,
      state.pending
    )
  }

  lemma ForwardQueueTailIsSound(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    queue: seq<Indexed.ForwardWork>
  )
    requires 0 < |queue|
    requires ForwardQueueSound(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               queue
             )
    ensures ForwardQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              queue[1..]
            )
  {
  }

  lemma {:isolate_assertions}
    ForwardSuccessfulGrantPreservesRefinement(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    semanticSubject: Semantics.ObjectRef,
    before: Indexed.ForwardState,
    after: Indexed.ForwardState,
    indexedGrant: Indexed.ForwardGrantKey
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires semanticSubject in objects
    requires forall rule <- indexedRules :: rule.head in permissions
    requires Indexed.ForwardStateInvariant(before)
    requires Indexed.ValidForwardQueuedEids(before.queue)
    requires ForwardStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               indexedRules,
               before
             )
    requires 0 < |before.queue|
    requires before.queue[0].ForwardGrantWork?
    requires indexedGrant == before.queue[0].grant
    requires Indexed.ForwardSuccessfulGrantTransition(
               before,
               after,
               indexedGrant
             )
    ensures ForwardStateRefines(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              indexedRules,
              after
            )
  {
    var consumerRules :=
      if indexedGrant.node in before.consumers
      then before.consumers[indexedGrant.node]
      else [];
    assert ForwardWorkSound(
        objectBindings,
        relationBindings,
        relationships,
        semanticSubject,
        grants,
        before.queue[0]
      );
    assert ForwardGrantRefines(
        objectBindings,
        semanticSubject,
        grants,
        indexedGrant
      );
    assert forall rule <- consumerRules ::
        rule in indexedRules &&
        (rule.SelfPermissionRule? ||
         rule.ArrowPermissionRule?) &&
        rule.targetNode == indexedGrant.node;
    assert Indexed.ValidIndexedRules(consumerRules);
    assert forall rule <- consumerRules :: rule.head in permissions;
    ForwardConsumerWorksPreserveSoundness(
      objects,
      relations,
      permissions,
      normalizedRules,
      indexedRules,
      relationships,
      objectBindings,
      relationBindings,
      grants,
      semanticSubject,
      consumerRules,
      indexedGrant
    );
    ForwardQueueTailIsSound(
      objectBindings,
      relationBindings,
      relationships,
      semanticSubject,
      grants,
      before.queue
    );
    ForwardQueueSoundConcatenation(
      objectBindings,
      relationBindings,
      relationships,
      semanticSubject,
      grants,
      before.queue[1..],
      Indexed.ForwardConsumerWorks(
        consumerRules,
        indexedGrant
      )
    );
  }

  lemma {:isolate_assertions}
    ForwardStepRelationPreservesRefinement(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    semanticSubject: Semantics.ObjectRef,
    before: Indexed.ForwardState,
    outcome: Indexed.ForwardStep
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires semanticSubject in objects
    requires forall rule <- indexedRules :: rule.head in permissions
    requires Indexed.ForwardStateInvariant(before)
    requires Indexed.ValidForwardQueuedEids(before.queue)
    requires ForwardStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               indexedRules,
               before
             )
    requires Indexed.ForwardStepRelation(before, outcome)
    ensures ForwardStateRefines(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              indexedRules,
              outcome.state
            )
  {
    match outcome
    case ForwardAdvanced(after) => {
      ForwardQueueTailIsSound(
        objectBindings,
        relationBindings,
        relationships,
        semanticSubject,
        grants,
        before.queue
      );
      var work := before.queue[0];
      if work.ForwardStreamWork? {
        if 0 < |work.stream.buffered| {
          ForwardBufferedWorkPreservesSoundness(
            objectBindings,
            relationBindings,
            relationships,
            semanticSubject,
            grants,
            work.stream
          );
          ForwardQueueSoundConcatenation(
            objectBindings,
            relationBindings,
            relationships,
            semanticSubject,
            grants,
            before.queue[1..],
            Indexed.ForwardBufferedWork(work.stream)
          );
        }
      } else if work.grant !in before.seen {
        ForwardSuccessfulGrantPreservesRefinement(
          objects,
          relations,
          permissions,
          normalizedRules,
          indexedRules,
          relationships,
          objectBindings,
          relationBindings,
          grants,
          semanticSubject,
          before,
          after,
          work.grant
        );
      }
    }
    case ForwardYielded(_) => {
    }
    case ForwardNeedScan(after, _) => {
      ForwardQueueTailIsSound(
        objectBindings,
        relationBindings,
        relationships,
        semanticSubject,
        grants,
        before.queue
      );
      assert ForwardWorkSound(
          objectBindings,
          relationBindings,
          relationships,
          semanticSubject,
          grants,
          before.queue[0]
        );
      assert ForwardStreamSound(
          objectBindings,
          relationBindings,
          relationships,
          semanticSubject,
          grants,
          Indexed.ForwardStream(
            after.pending.command.projection,
            [],
            true,
            after.pending.continuation
          )
        );
    }
    case ForwardEmitted(after, _, _) => {
      ForwardSuccessfulGrantPreservesRefinement(
        objects,
        relations,
        permissions,
        normalizedRules,
        indexedRules,
        relationships,
        objectBindings,
        relationBindings,
        grants,
        semanticSubject,
        before,
        after,
        before.queue[0].grant
      );
    }
    case ForwardComplete(_) => {
    }
    case ForwardRenderRejected(_, _) => {
    }
    case ForwardStepLimitExceeded(_, _) => {
    }
  }

  lemma ForwardInitializationRefinesFixedPoint(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    subjectType: string,
    subjectEid: int,
    semanticSubject: Semantics.ObjectRef,
    rootNode: Semantics.PermissionNode,
    resultType: string,
    mode: Indexed.RenderMode,
    chunkSize: nat,
    limits: Indexed.IndexedLimits,
    outcome: Indexed.ForwardInit
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ValidIndexedRules(seedRules)
    requires forall rule <- seedRules :: rule in indexedRules
    requires ExactForwardSeedBucket(
               indexedRules,
               seedRules,
               subjectType
             )
    requires semanticSubject in objects
    requires 0 <= subjectEid
    requires exists subjectBinding <- objectBindings ::
               subjectBinding.eid == subjectEid &&
               subjectBinding.objectRef == semanticSubject &&
               subjectBinding.objectRef.typeName == subjectType
    requires forall rule <- indexedRules :: rule.head in permissions
    requires outcome.ForwardInitialized?
    requires outcome.state.queue ==
             Indexed.ForwardSeedWorks(
               seedRules,
               subjectType,
               subjectEid
             )
    requires outcome.state.consumers ==
             Indexed.ForwardConsumers(indexedRules)
    requires outcome.state.seen == {}
    requires outcome.state.emitted == {}
    requires outcome.state.pending.NoForwardPending?
    ensures ForwardStateRefines(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              indexedRules,
              outcome.state
            )
  {
    ForwardSeedWorksPreserveSoundness(
      objects,
      relations,
      permissions,
      normalizedRules,
      indexedRules,
      seedRules,
      relationships,
      objectBindings,
      relationBindings,
      grants,
      subjectType,
      subjectEid,
      semanticSubject
    );
    ForwardConsumersRefineRules(indexedRules);
  }

  method InitializeForwardRefined(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    subjectType: string,
    subjectEid: int,
    semanticSubject: Semantics.ObjectRef,
    rootNode: Semantics.PermissionNode,
    resultType: string,
    mode: Indexed.RenderMode,
    chunkSize: nat,
    limits: Indexed.IndexedLimits
  ) returns (outcome: Indexed.ForwardInit)
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ValidIndexedRules(seedRules)
    requires forall rule <- seedRules :: rule in indexedRules
    requires ExactForwardSeedBucket(
               indexedRules,
               seedRules,
               subjectType
             )
    requires 0 < |subjectType|
    requires 0 <= subjectEid
    requires semanticSubject in objects
    requires exists subjectBinding <- objectBindings ::
               subjectBinding.eid == subjectEid &&
               subjectBinding.objectRef == semanticSubject &&
               subjectBinding.objectRef.typeName == subjectType
    requires Indexed.ValidPermissionNode(rootNode)
    requires rootNode in permissions
    requires 0 < |resultType|
    requires Indexed.ValidRenderMode(mode)
    requires 0 < chunkSize
    requires forall rule <- indexedRules :: rule.head in permissions
    ensures outcome ==
            Indexed.InitializeForwardSpec(
              indexedRules,
              seedRules,
              0,
              subjectType,
              subjectEid,
              rootNode,
              resultType,
              mode,
              chunkSize,
              limits
            )
    ensures outcome.ForwardInitialized? ==>
              Indexed.ForwardStateInvariant(outcome.state) &&
              Indexed.CountersWithinLimits(
                outcome.state.counters,
                limits
              ) &&
              ForwardStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                semanticSubject,
                grants,
                indexedRules,
                outcome.state
              )
  {
    outcome :=
      Indexed.InitializeForward(
        indexedRules,
        seedRules,
        0,
        subjectType,
        subjectEid,
        rootNode,
        resultType,
        mode,
        chunkSize,
        limits
      );
    if outcome.ForwardInitialized? {
      ForwardInitializationRefinesFixedPoint(
        objects,
        relations,
        permissions,
        normalizedRules,
        indexedRules,
        seedRules,
        relationships,
        objectBindings,
        relationBindings,
        grants,
        subjectType,
        subjectEid,
        semanticSubject,
        rootNode,
        resultType,
        mode,
        chunkSize,
        limits,
        outcome
      );
    }
  }

  method StepForwardRefined(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    semanticSubject: Semantics.ObjectRef,
    state: Indexed.ForwardState,
    limits: Indexed.IndexedLimits
  ) returns (outcome: Indexed.ForwardStep)
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires semanticSubject in objects
    requires forall rule <- indexedRules :: rule.head in permissions
    requires Indexed.ForwardStateInvariant(state)
    requires Indexed.CountersWithinLimits(state.counters, limits)
    requires state.pending.NoForwardPending?
    requires Indexed.ValidForwardQueuedEids(state.queue)
    requires ForwardStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               indexedRules,
               state
             )
    ensures outcome == Indexed.ForwardStepSpec(state, limits)
    ensures ForwardStateRefines(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              indexedRules,
              outcome.state
            )
    ensures Indexed.ForwardStepRelation(state, outcome)
  {
    outcome := Indexed.StepForward(state, limits);
    ForwardStepRelationPreservesRefinement(
      objects,
      relations,
      permissions,
      normalizedRules,
      indexedRules,
      relationships,
      objectBindings,
      relationBindings,
      grants,
      semanticSubject,
      state,
      outcome
    );
  }

  lemma {:isolate_assertions}
    ForwardResumeRelationPreservesRefinement(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    indexedRules: seq<Indexed.IndexedRule>,
    before: Indexed.ForwardState,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>,
    outcome: Indexed.ForwardResume
  )
    requires Indexed.ForwardStateInvariant(before)
    requires before.pending.AwaitingForwardScan?
    requires ForwardStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               indexedRules,
               before
             )
    requires ExactProjectionValues(
               objectBindings,
               relationBindings,
               relationships,
               before.pending.command.projection,
               fullProjectionValues
             )
    requires ExactScanResponse(
               before.pending.command,
               response,
               fullProjectionValues
             )
    requires Indexed.ForwardResumeRelation(
               before,
               response,
               outcome
             )
    ensures outcome.ForwardScanResumed? ==>
              ForwardStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                semanticSubject,
                grants,
                indexedRules,
                outcome.state
              )
    ensures outcome.ForwardScanLimitExceeded? ==>
              ForwardStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                semanticSubject,
                grants,
                indexedRules,
                outcome.state
              )
    ensures !outcome.ForwardScanRejected?
  {
    match outcome
    case ForwardScanResumed(after) => {
      if 0 < |response.values| {
        var validated :=
          Indexed.ScanAccepted(
            response.values,
            response.terminal,
            response.fetchedValues
          );
        assert ForwardStreamSound(
            objectBindings,
            relationBindings,
            relationships,
            semanticSubject,
            grants,
            Indexed.ForwardStream(
              before.pending.command.projection,
              [],
              true,
              before.pending.continuation
            )
          );
        ForwardResponseWorkPreservesSoundness(
          objectBindings,
          relationBindings,
          relationships,
          semanticSubject,
          grants,
          before.pending.command,
          response,
          validated,
          before.pending.continuation,
          fullProjectionValues
        );
        ForwardQueueSoundConcatenation(
          objectBindings,
          relationBindings,
          relationships,
          semanticSubject,
          grants,
          before.queue,
          Indexed.ForwardWorkAfterResponse(
            before.pending.command,
            before.pending.continuation,
            validated
          )
        );
      }
    }
    case ForwardScanRejected(_) => {
      assert false;
    }
    case ForwardScanLimitExceeded(_, _) => {
    }
  }

  method ResumeForwardRefined(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    indexedRules: seq<Indexed.IndexedRule>,
    state: Indexed.ForwardState,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>,
    limits: Indexed.IndexedLimits
  ) returns (outcome: Indexed.ForwardResume)
    requires Indexed.ForwardStateInvariant(state)
    requires Indexed.CountersWithinLimits(state.counters, limits)
    requires state.pending.AwaitingForwardScan?
    requires ForwardStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               indexedRules,
               state
             )
    requires ExactProjectionValues(
               objectBindings,
               relationBindings,
               relationships,
               state.pending.command.projection,
               fullProjectionValues
             )
    requires ExactScanResponse(
               state.pending.command,
               response,
               fullProjectionValues
             )
    ensures outcome ==
            Indexed.ForwardResumeSpec(
              state,
              response,
              limits
            )
    ensures outcome.ForwardScanResumed? ==>
              ForwardStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                semanticSubject,
                grants,
                indexedRules,
                outcome.state
              )
    ensures outcome.ForwardScanLimitExceeded? ==>
              ForwardStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                semanticSubject,
                grants,
                indexedRules,
                outcome.state
              )
    ensures !outcome.ForwardScanRejected?
  {
    outcome := Indexed.ResumeForwardScan(state, response, limits);
    ForwardResumeRelationPreservesRefinement(
      objectBindings,
      relationBindings,
      relationships,
      semanticSubject,
      grants,
      indexedRules,
      state,
      response,
      fullProjectionValues,
      outcome
    );
  }

  lemma {:isolate_assertions}
    ReverseGoalRuleWorkPreservesSoundness(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    indexedRule: Indexed.IndexedRule,
    goal: Indexed.ReverseGoalKey,
    querySubjectType: string
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires indexedRule in indexedRules
    requires Indexed.ValidIndexedRule(indexedRule)
    requires indexedRule.head == goal.node
    requires IndexedRulePermissionClosed(indexedRule, permissions)
    requires ReverseGoalRefines(objectBindings, permissions, goal)
    ensures ReverseQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              Indexed.ReverseGoalRuleWork(
                indexedRule,
                goal,
                querySubjectType
              )
            )
  {
    EveryIndexedRuleHasSemanticWitness(
      normalizedRules,
      indexedRules,
      relationBindings,
      indexedRule
    );
    var normalizedRule :| normalizedRule in normalizedRules &&
                          IndexedRuleRefines(
                            indexedRule,
                            normalizedRule,
                            relationBindings
                          );
    var goalResource :| goalResource in objectBindings &&
                        goalResource.eid == goal.resourceEid &&
                        goalResource.objectRef.typeName ==
                        goal.node.resourceType;
    match indexedRule
    case RelationRule(head, relationEid, subjectType) => {
      if querySubjectType == subjectType {
        var generatedStream :=
          Indexed.ReverseStream(
            Indexed.ResourceToSubjects(
              head.resourceType,
              goal.resourceEid,
              relationEid,
              querySubjectType,
              Indexed.NoBound
            ),
            [],
            true,
            Indexed.ReverseGrant(
              head,
              goal.resourceEid,
              querySubjectType
            )
          );
        forall subjectEid: int |
          RelationshipProjectsTo(
            objectBindings,
            relationBindings,
            relationships,
            generatedStream.projection,
            subjectEid
          )
          ensures ReverseGrantRefines(
                    objectBindings,
                    grants,
                    Indexed.ReverseGrantKey(
                      head,
                      goal.resourceEid,
                      querySubjectType,
                      subjectEid
                    )
                  )
        {
          var projectedResource,
              subjectBinding,
              relationBinding,
              relationship :=
            ResourceToSubjectsWitness(
              objectBindings,
              relationBindings,
              relationships,
              head.resourceType,
              goal.resourceEid,
              relationEid,
              querySubjectType,
              subjectEid
            );
          UniqueObjectEidIdentifiesBinding(
            objectBindings,
            goalResource,
            projectedResource
          );
          DirectIndexedDerivationIsSound(
            objects,
            permissions,
            normalizedRules,
            relationships,
            objectBindings,
            relationBindings,
            grants,
            normalizedRule,
            indexedRule,
            subjectBinding,
            goalResource,
            relationship
          );
        }
        assert ReverseStreamSound(
            objectBindings,
            relationBindings,
            relationships,
            permissions,
            grants,
            generatedStream
          );
      }
    }
    case SelfPermissionRule(head, targetNode) => {
      var key :=
        Indexed.ReverseGoalKey(targetNode, goal.resourceEid);
      var consumer :=
        Indexed.ReverseConsumer(head, goal.resourceEid);
      assert ReverseGoalRefines(objectBindings, permissions, key);
      forall targetGrant: Indexed.ReverseGrantKey |
        targetGrant.node == key.node &&
        targetGrant.resourceEid == key.resourceEid &&
        ReverseGrantRefines(
          objectBindings,
          grants,
          targetGrant
        )
        ensures ReverseGrantRefines(
                  objectBindings,
                  grants,
                  Indexed.ReverseGrantKey(
                    consumer.node,
                    consumer.resourceEid,
                    targetGrant.subjectType,
                    targetGrant.subjectEid
                  )
                )
      {
        var targetResource :| targetResource in objectBindings &&
                              targetResource.eid ==
                              targetGrant.resourceEid &&
                              targetResource.objectRef.typeName ==
                              targetGrant.node.resourceType;
        var subjectBinding :| subjectBinding in objectBindings &&
                              subjectBinding.eid ==
                              targetGrant.subjectEid &&
                              subjectBinding.objectRef.typeName ==
                              targetGrant.subjectType &&
                              Semantics.Grant(
                                subjectBinding.objectRef,
                                targetGrant.node,
                                targetResource.objectRef
                              ) in grants;
        UniqueObjectEidIdentifiesBinding(
          objectBindings,
          goalResource,
          targetResource
        );
        SelfPermissionIndexedDerivationIsSound(
          objects,
          permissions,
          normalizedRules,
          relationships,
          objectBindings,
          grants,
          normalizedRule,
          indexedRule,
          subjectBinding,
          goalResource
        );
      }
      assert ReverseConsumerRefines(
          objectBindings,
          grants,
          key,
          consumer
        );
    }
    case ArrowRelationRule(
      head,
        viaRelationEid,
        intermediateType,
        targetRelationEid,
        targetSubjectType
        ) => {
      if querySubjectType == targetSubjectType {
        var generatedStream :=
          Indexed.ReverseStream(
            Indexed.ResourceToSubjects(
              head.resourceType,
              goal.resourceEid,
              viaRelationEid,
              intermediateType,
              Indexed.NoBound
            ),
            [],
            true,
            Indexed.ReverseArrowRelation(
              head,
              goal.resourceEid,
              querySubjectType,
              intermediateType,
              targetRelationEid
            )
          );
        forall intermediateEid: int, subjectEid: int |
          RelationshipProjectsTo(
            objectBindings,
            relationBindings,
            relationships,
            generatedStream.projection,
            intermediateEid
          ) &&
          RelationshipProjectsTo(
            objectBindings,
            relationBindings,
            relationships,
            Indexed.ResourceToSubjects(
              intermediateType,
              intermediateEid,
              targetRelationEid,
              querySubjectType,
              Indexed.NoBound
            ),
            subjectEid
          )
          ensures ReverseGrantRefines(
                    objectBindings,
                    grants,
                    Indexed.ReverseGrantKey(
                      head,
                      goal.resourceEid,
                      querySubjectType,
                      subjectEid
                    )
                  )
        {
          var projectedResource,
              intermediateBinding,
              viaBinding,
              viaRelationship :=
            ResourceToSubjectsWitness(
              objectBindings,
              relationBindings,
              relationships,
              head.resourceType,
              goal.resourceEid,
              viaRelationEid,
              intermediateType,
              intermediateEid
            );
          var projectedIntermediate,
              subjectBinding,
              targetBinding,
              targetRelationship :=
            ResourceToSubjectsWitness(
              objectBindings,
              relationBindings,
              relationships,
              intermediateType,
              intermediateEid,
              targetRelationEid,
              querySubjectType,
              subjectEid
            );
          UniqueObjectEidIdentifiesBinding(
            objectBindings,
            goalResource,
            projectedResource
          );
          UniqueObjectEidIdentifiesBinding(
            objectBindings,
            intermediateBinding,
            projectedIntermediate
          );
          ArrowRelationIndexedDerivationIsSound(
            objects,
            permissions,
            normalizedRules,
            relationships,
            objectBindings,
            relationBindings,
            grants,
            normalizedRule,
            indexedRule,
            subjectBinding,
            intermediateBinding,
            goalResource,
            viaRelationship,
            targetRelationship
          );
        }
        assert ReverseStreamSound(
            objectBindings,
            relationBindings,
            relationships,
            permissions,
            grants,
            generatedStream
          );
      }
    }
    case ArrowPermissionRule(
      head,
        viaRelationEid,
        intermediateType,
        targetNode
        ) => {
      var generatedStream :=
        Indexed.ReverseStream(
          Indexed.ResourceToSubjects(
            head.resourceType,
            goal.resourceEid,
            viaRelationEid,
            intermediateType,
            Indexed.NoBound
          ),
          [],
          true,
          Indexed.ReverseArrowPermission(
            head,
            goal.resourceEid,
            targetNode
          )
        );
      forall intermediateEid: int |
        RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          generatedStream.projection,
          intermediateEid
        )
        ensures ReverseGoalRefines(
                  objectBindings,
                  permissions,
                  Indexed.ReverseGoalKey(
                    targetNode,
                    intermediateEid
                  )
                ) &&
                ReverseConsumerRefines(
                  objectBindings,
                  grants,
                  Indexed.ReverseGoalKey(
                    targetNode,
                    intermediateEid
                  ),
                  Indexed.ReverseConsumer(
                    head,
                    goal.resourceEid
                  )
                )
      {
        var projectedResource,
            intermediateBinding,
            viaBinding,
            viaRelationship :=
          ResourceToSubjectsWitness(
            objectBindings,
            relationBindings,
            relationships,
            head.resourceType,
            goal.resourceEid,
            viaRelationEid,
            intermediateType,
            intermediateEid
          );
        UniqueObjectEidIdentifiesBinding(
          objectBindings,
          goalResource,
          projectedResource
        );
        var targetGoal :=
          Indexed.ReverseGoalKey(targetNode, intermediateEid);
        assert ReverseGoalRefines(
            objectBindings,
            permissions,
            targetGoal
          );
        forall targetGrant: Indexed.ReverseGrantKey |
          targetGrant.node == targetGoal.node &&
          targetGrant.resourceEid == targetGoal.resourceEid &&
          ReverseGrantRefines(
            objectBindings,
            grants,
            targetGrant
          )
          ensures ReverseGrantRefines(
                    objectBindings,
                    grants,
                    Indexed.ReverseGrantKey(
                      head,
                      goal.resourceEid,
                      targetGrant.subjectType,
                      targetGrant.subjectEid
                    )
                  )
        {
          var targetResource :| targetResource in objectBindings &&
                                targetResource.eid ==
                                targetGrant.resourceEid &&
                                targetResource.objectRef.typeName ==
                                targetGrant.node.resourceType;
          var subjectBinding :| subjectBinding in objectBindings &&
                                subjectBinding.eid ==
                                targetGrant.subjectEid &&
                                subjectBinding.objectRef.typeName ==
                                targetGrant.subjectType &&
                                Semantics.Grant(
                                  subjectBinding.objectRef,
                                  targetGrant.node,
                                  targetResource.objectRef
                                ) in grants;
          UniqueObjectEidIdentifiesBinding(
            objectBindings,
            intermediateBinding,
            targetResource
          );
          ArrowPermissionIndexedDerivationIsSound(
            objects,
            permissions,
            normalizedRules,
            relationships,
            objectBindings,
            relationBindings,
            grants,
            normalizedRule,
            indexedRule,
            subjectBinding,
            intermediateBinding,
            goalResource,
            viaRelationship
          );
        }
      }
      assert ReverseStreamSound(
          objectBindings,
          relationBindings,
          relationships,
          permissions,
          grants,
          generatedStream
        );
    }
  }

  lemma ReverseGoalRuleWorksPreserveSoundness(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    goalRules: seq<Indexed.IndexedRule>,
    goal: Indexed.ReverseGoalKey,
    querySubjectType: string
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ValidIndexedRules(goalRules)
    requires forall rule <- goalRules ::
               rule in indexedRules &&
               rule.head == goal.node &&
               IndexedRulePermissionClosed(rule, permissions)
    requires ReverseGoalRefines(objectBindings, permissions, goal)
    ensures ReverseQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              Indexed.ReverseGoalRuleWorks(
                goalRules,
                goal,
                querySubjectType
              )
            )
    decreases |goalRules|
  {
    if |goalRules| != 0 {
      ReverseGoalRuleWorkPreservesSoundness(
        objects,
        relations,
        permissions,
        normalizedRules,
        indexedRules,
        relationships,
        objectBindings,
        relationBindings,
        grants,
        goalRules[0],
        goal,
        querySubjectType
      );
      ReverseGoalRuleWorksPreserveSoundness(
        objects,
        relations,
        permissions,
        normalizedRules,
        indexedRules,
        relationships,
        objectBindings,
        relationBindings,
        grants,
        goalRules[1..],
        goal,
        querySubjectType
      );
      ReverseQueueSoundConcatenation(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        Indexed.ReverseGoalRuleWork(
          goalRules[0],
          goal,
          querySubjectType
        ),
        Indexed.ReverseGoalRuleWorks(
          goalRules[1..],
          goal,
          querySubjectType
        )
      );
    }
  }

  predicate RulesByNodeRefine(
    rulesByNode:
    map<Semantics.PermissionNode, seq<Indexed.IndexedRule>>,
    indexedRules: seq<Indexed.IndexedRule>
  ) {
    forall node <- rulesByNode.Keys, rule <- rulesByNode[node] ::
      rule in indexedRules &&
      rule.head == node
  }

  lemma AddRuleByNodePreservesRefinement(
    rulesByNode:
    map<Semantics.PermissionNode, seq<Indexed.IndexedRule>>,
    previousRules: seq<Indexed.IndexedRule>,
    rule: Indexed.IndexedRule
  )
    requires RulesByNodeRefine(rulesByNode, previousRules)
    ensures RulesByNodeRefine(
              Indexed.AddRuleByNode(rulesByNode, rule),
              [rule] + previousRules
            )
  {
  }

  lemma RulesByNodeRefineRules(
    indexedRules: seq<Indexed.IndexedRule>
  )
    requires Indexed.ValidIndexedRules(indexedRules)
    ensures RulesByNodeRefine(
              Indexed.RulesByNode(indexedRules),
              indexedRules
            )
    decreases |indexedRules|
  {
    if |indexedRules| != 0 {
      RulesByNodeRefineRules(indexedRules[1..]);
      AddRuleByNodePreservesRefinement(
        Indexed.RulesByNode(indexedRules[1..]),
        indexedRules[1..],
        indexedRules[0]
      );
      assert indexedRules == [indexedRules[0]] + indexedRules[1..];
    }
  }

  ghost predicate PendingReverseScanSound(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    pending: Indexed.ReversePending
  ) {
    pending.NoReversePending? ||
    ReverseStreamSound(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      Indexed.ReverseStream(
        pending.command.projection,
        [],
        true,
        pending.continuation
      )
    )
  }

  ghost predicate ReverseStateRefines(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    indexedRules: seq<Indexed.IndexedRule>,
    state: Indexed.ReverseState
  ) {
    ReverseQueueSound(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      state.queue
    ) &&
    (forall goal <- state.seenGoals ::
       ReverseGoalRefines(objectBindings, permissions, goal)) &&
    (forall indexedGrant <- state.seenGrants ::
       ReverseGrantRefines(
         objectBindings,
         grants,
         indexedGrant
       )) &&
    (forall key <- state.grantsByGoal.Keys,
       indexedGrant <- state.grantsByGoal[key] ::
       indexedGrant.node == key.node &&
       indexedGrant.resourceEid == key.resourceEid &&
       ReverseGrantRefines(
         objectBindings,
         grants,
         indexedGrant
       )) &&
    (forall key <- state.consumers.Keys,
       consumer <- state.consumers[key] ::
       ReverseGoalRefines(objectBindings, permissions, key) &&
       ReverseConsumerRefines(
         objectBindings,
         grants,
         key,
         consumer
       )) &&
    (forall emittedEid <- state.emitted ::
       ReverseGrantRefines(
         objectBindings,
         grants,
         Indexed.ReverseGrantKey(
           state.rootNode,
           state.rootResourceEid,
           state.resultType,
           emittedEid
         )
       )) &&
    RulesByNodeRefine(state.rulesByNode, indexedRules) &&
    PendingReverseScanSound(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      state.pending
    )
  }

  lemma ReverseInitializationRefinesFixedPoint(
    permissions: seq<Semantics.PermissionNode>,
    indexedRules: seq<Indexed.IndexedRule>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>,
    rootNode: Semantics.PermissionNode,
    rootResourceEid: int,
    outcome: Indexed.ReverseInit
  )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires rootNode in permissions
    requires exists rootResource <- objectBindings ::
               rootResource.eid == rootResourceEid &&
               rootResource.objectRef.typeName == rootNode.resourceType
    requires outcome.ReverseInitialized?
    requires outcome.state.queue ==
             [Indexed.ReverseGoalWork(
                Indexed.ReverseGoalKey(
                  rootNode,
                  rootResourceEid
                )
              )]
    requires outcome.state.rulesByNode ==
             Indexed.RulesByNode(indexedRules)
    requires outcome.state.seenGoals == {}
    requires outcome.state.seenGrants == {}
    requires outcome.state.grantsByGoal == map[]
    requires outcome.state.consumers == map[]
    requires outcome.state.seenConsumers == {}
    requires outcome.state.emitted == {}
    requires outcome.state.pending.NoReversePending?
    ensures ReverseStateRefines(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              indexedRules,
              outcome.state
            )
  {
    RulesByNodeRefineRules(indexedRules);
  }

  method InitializeReverseRefined(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    subjectType: string,
    rootNode: Semantics.PermissionNode,
    rootResourceEid: int,
    resultType: string,
    mode: Indexed.RenderMode,
    chunkSize: nat,
    limits: Indexed.IndexedLimits
  ) returns (outcome: Indexed.ReverseInit)
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires 0 < |subjectType|
    requires Indexed.ValidPermissionNode(rootNode)
    requires rootNode in permissions
    requires 0 <= rootResourceEid
    requires exists rootResource <- objectBindings ::
               rootResource.eid == rootResourceEid &&
               rootResource.objectRef.typeName == rootNode.resourceType
    requires 0 < |resultType|
    requires Indexed.ValidRenderMode(mode)
    requires 0 < chunkSize
    requires forall rule <- indexedRules ::
               IndexedRulePermissionClosed(rule, permissions)
    ensures outcome ==
            Indexed.InitializeReverseSpec(
              indexedRules,
              0,
              subjectType,
              rootNode,
              rootResourceEid,
              resultType,
              mode,
              chunkSize,
              limits
            )
    ensures outcome.ReverseInitialized? ==>
              Indexed.ReverseStateInvariant(outcome.state) &&
              Indexed.CountersWithinLimits(
                outcome.state.counters,
                limits
              ) &&
              ReverseStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                permissions,
                grants,
                indexedRules,
                outcome.state
              )
  {
    outcome :=
      Indexed.InitializeReverse(
        indexedRules,
        0,
        subjectType,
        rootNode,
        rootResourceEid,
        resultType,
        mode,
        chunkSize,
        limits
      );
    if outcome.ReverseInitialized? {
      ReverseInitializationRefinesFixedPoint(
        permissions,
        indexedRules,
        objectBindings,
        relationBindings,
        relationships,
        grants,
        rootNode,
        rootResourceEid,
        outcome
      );
    }
  }

  lemma ReverseQueueTailIsSound(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    queue: seq<Indexed.ReverseWork>
  )
    requires 0 < |queue|
    requires ReverseQueueSound(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               queue
             )
    ensures ReverseQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              queue[1..]
            )
  {
  }

  lemma ReverseConsumerWorkPreservesSoundness(
    objectBindings: seq<ObjectBinding>,
    grants: set<Semantics.Grant>,
    key: Indexed.ReverseGoalKey,
    consumer: Indexed.ReverseConsumer,
    indexedGrant: Indexed.ReverseGrantKey
  )
    requires 0 <= consumer.resourceEid
    requires 0 <= indexedGrant.resourceEid
    requires 0 <= indexedGrant.subjectEid
    requires indexedGrant.node == key.node
    requires indexedGrant.resourceEid == key.resourceEid
    requires ReverseGrantRefines(
               objectBindings,
               grants,
               indexedGrant
             )
    requires ReverseConsumerRefines(
               objectBindings,
               grants,
               key,
               consumer
             )
    ensures forall propagated <-
                     Indexed.ReverseConsumerWork(consumer, indexedGrant) ::
              ReverseGrantRefines(
                objectBindings,
                grants,
                propagated.grant
              )
  {
  }

  lemma ReverseConsumerWorksPreserveSoundness(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    key: Indexed.ReverseGoalKey,
    consumers: seq<Indexed.ReverseConsumer>,
    indexedGrant: Indexed.ReverseGrantKey
  )
    requires 0 <= indexedGrant.resourceEid
    requires 0 <= indexedGrant.subjectEid
    requires indexedGrant.node == key.node
    requires indexedGrant.resourceEid == key.resourceEid
    requires ReverseGrantRefines(
               objectBindings,
               grants,
               indexedGrant
             )
    requires forall consumer <- consumers ::
               0 <= consumer.resourceEid &&
               ReverseConsumerRefines(
                 objectBindings,
                 grants,
                 key,
                 consumer
               )
    ensures ReverseQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              Indexed.ReverseConsumerWorks(
                consumers,
                indexedGrant
              )
            )
    decreases |consumers|
  {
    if |consumers| != 0 {
      ReverseConsumerWorkPreservesSoundness(
        objectBindings,
        grants,
        key,
        consumers[0],
        indexedGrant
      );
      ReverseConsumerWorksPreserveSoundness(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        key,
        consumers[1..],
        indexedGrant
      );
      ReverseQueueSoundConcatenation(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        Indexed.ReverseConsumerWork(consumers[0], indexedGrant),
        Indexed.ReverseConsumerWorks(consumers[1..], indexedGrant)
      );
    }
  }

  lemma ReverseConsumerForGrantsPreservesSoundness(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    key: Indexed.ReverseGoalKey,
    consumer: Indexed.ReverseConsumer,
    existingGrants: seq<Indexed.ReverseGrantKey>
  )
    requires 0 <= consumer.resourceEid
    requires ReverseConsumerRefines(
               objectBindings,
               grants,
               key,
               consumer
             )
    requires forall indexedGrant <- existingGrants ::
               indexedGrant.node == key.node &&
               indexedGrant.resourceEid == key.resourceEid &&
               0 <= indexedGrant.resourceEid &&
               0 <= indexedGrant.subjectEid &&
               ReverseGrantRefines(
                 objectBindings,
                 grants,
                 indexedGrant
               )
    ensures ReverseQueueSound(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              Indexed.ReverseConsumersForGrants(
                consumer,
                existingGrants
              )
            )
    decreases |existingGrants|
  {
    if |existingGrants| != 0 {
      ReverseConsumerWorkPreservesSoundness(
        objectBindings,
        grants,
        key,
        consumer,
        existingGrants[0]
      );
      ReverseConsumerForGrantsPreservesSoundness(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        key,
        consumer,
        existingGrants[1..]
      );
      ReverseQueueSoundConcatenation(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        Indexed.ReverseConsumerWork(
          consumer,
          existingGrants[0]
        ),
        Indexed.ReverseConsumersForGrants(
          consumer,
          existingGrants[1..]
        )
      );
    }
  }

  lemma {:isolate_assertions}
    ReverseSuccessfulGoalPreservesRefinement(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    before: Indexed.ReverseState,
    after: Indexed.ReverseState,
    goal: Indexed.ReverseGoalKey
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires forall rule <- indexedRules ::
               IndexedRulePermissionClosed(rule, permissions)
    requires Indexed.ReverseStateInvariant(before)
    requires Indexed.ValidReverseQueuedEids(before.queue)
    requires ReverseStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               indexedRules,
               before
             )
    requires 0 < |before.queue|
    requires before.queue[0].ReverseGoalWork?
    requires goal == before.queue[0].goal
    requires Indexed.ReverseSuccessfulGoalTransition(
               before,
               after,
               goal
             )
    ensures ReverseStateRefines(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              indexedRules,
              after
            )
  {
    var goalRules :=
      if goal.node in before.rulesByNode
      then before.rulesByNode[goal.node]
      else [];
    assert ReverseWorkSound(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        before.queue[0]
      );
    assert ReverseGoalRefines(objectBindings, permissions, goal);
    assert Indexed.ValidIndexedRules(goalRules);
    assert forall rule <- goalRules ::
        rule in indexedRules &&
        rule.head == goal.node &&
        IndexedRulePermissionClosed(rule, permissions);
    ReverseGoalRuleWorksPreserveSoundness(
      objects,
      relations,
      permissions,
      normalizedRules,
      indexedRules,
      relationships,
      objectBindings,
      relationBindings,
      grants,
      goalRules,
      goal,
      before.subjectType
    );
    ReverseQueueTailIsSound(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      before.queue
    );
    ReverseQueueSoundConcatenation(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      before.queue[1..],
      Indexed.ReverseGoalRuleWorks(
        goalRules,
        goal,
        before.subjectType
      )
    );
  }

  lemma {:isolate_assertions}
    ReverseSuccessfulConsumerPreservesRefinement(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    indexedRules: seq<Indexed.IndexedRule>,
    before: Indexed.ReverseState,
    after: Indexed.ReverseState,
    key: Indexed.ReverseGoalKey,
    consumer: Indexed.ReverseConsumer
  )
    requires Indexed.ReverseStateInvariant(before)
    requires Indexed.ValidReverseQueuedEids(before.queue)
    requires ReverseStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               indexedRules,
               before
             )
    requires 0 < |before.queue|
    requires before.queue[0].ReverseRegisterConsumerWork?
    requires key == before.queue[0].key
    requires consumer == before.queue[0].consumer
    requires Indexed.ReverseSuccessfulConsumerTransition(
               before,
               after,
               key,
               consumer
             )
    ensures ReverseStateRefines(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              indexedRules,
              after
            )
  {
    var existingGrants :=
      if key in before.grantsByGoal
      then before.grantsByGoal[key]
      else [];
    assert ReverseWorkSound(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        before.queue[0]
      );
    assert ReverseGoalRefines(objectBindings, permissions, key);
    assert ReverseConsumerRefines(
        objectBindings,
        grants,
        key,
        consumer
      );
    assert forall indexedGrant <- existingGrants ::
        indexedGrant.node == key.node &&
        indexedGrant.resourceEid == key.resourceEid &&
        0 <= indexedGrant.resourceEid &&
        0 <= indexedGrant.subjectEid &&
        ReverseGrantRefines(
          objectBindings,
          grants,
          indexedGrant
        );
    ReverseConsumerForGrantsPreservesSoundness(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      key,
      consumer,
      existingGrants
    );
    ReverseQueueTailIsSound(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      before.queue
    );
    ReverseQueueSoundConcatenation(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      before.queue[1..],
      Indexed.ReverseConsumersForGrants(
        consumer,
        existingGrants
      )
    );
  }

  lemma {:isolate_assertions}
    ReverseSuccessfulGrantPreservesRefinement(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    indexedRules: seq<Indexed.IndexedRule>,
    before: Indexed.ReverseState,
    after: Indexed.ReverseState,
    indexedGrant: Indexed.ReverseGrantKey
  )
    requires Indexed.ReverseStateInvariant(before)
    requires Indexed.ValidReverseQueuedEids(before.queue)
    requires ReverseStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               indexedRules,
               before
             )
    requires 0 < |before.queue|
    requires before.queue[0].ReverseGrantWork?
    requires indexedGrant == before.queue[0].grant
    requires Indexed.ReverseSuccessfulGrantTransition(
               before,
               after,
               indexedGrant
             )
    ensures ReverseStateRefines(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              indexedRules,
              after
            )
  {
    var key :=
      Indexed.ReverseGoalKey(
        indexedGrant.node,
        indexedGrant.resourceEid
      );
    var consumers :=
      if key in before.consumers
      then before.consumers[key]
      else [];
    assert ReverseWorkSound(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        before.queue[0]
      );
    assert ReverseGrantRefines(
        objectBindings,
        grants,
        indexedGrant
      );
    assert forall consumer <- consumers ::
        0 <= consumer.resourceEid &&
        ReverseConsumerRefines(
          objectBindings,
          grants,
          key,
          consumer
        );
    ReverseConsumerWorksPreserveSoundness(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      key,
      consumers,
      indexedGrant
    );
    ReverseQueueTailIsSound(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      before.queue
    );
    ReverseQueueSoundConcatenation(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      before.queue[1..],
      Indexed.ReverseConsumerWorks(
        consumers,
        indexedGrant
      )
    );
  }

  lemma {:isolate_assertions}
    ReverseStepRelationPreservesRefinement(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    before: Indexed.ReverseState,
    outcome: Indexed.ReverseStep
  )
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires forall rule <- indexedRules ::
               IndexedRulePermissionClosed(rule, permissions)
    requires Indexed.ReverseStateInvariant(before)
    requires Indexed.ValidReverseQueuedEids(before.queue)
    requires ReverseStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               indexedRules,
               before
             )
    requires Indexed.ReverseStepRelation(before, outcome)
    ensures ReverseStateRefines(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              indexedRules,
              outcome.state
            )
  {
    match outcome
    case ReverseAdvanced(after) => {
      ReverseQueueTailIsSound(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        before.queue
      );
      var work := before.queue[0];
      match work
      case ReverseStreamWork(stream) => {
        if 0 < |stream.buffered| {
          ReverseBufferedWorkPreservesSoundness(
            objectBindings,
            relationBindings,
            relationships,
            permissions,
            grants,
            stream
          );
          ReverseQueueSoundConcatenation(
            objectBindings,
            relationBindings,
            relationships,
            permissions,
            grants,
            before.queue[1..],
            Indexed.ReverseBufferedWork(stream)
          );
        }
      }
      case ReverseGoalWork(goal) => {
        if goal !in before.seenGoals {
          ReverseSuccessfulGoalPreservesRefinement(
            objects,
            relations,
            permissions,
            normalizedRules,
            indexedRules,
            relationships,
            objectBindings,
            relationBindings,
            grants,
            before,
            after,
            goal
          );
        }
      }
      case ReverseRegisterConsumerWork(key, consumer) => {
        var registration :=
          Indexed.ReverseConsumerRegistration(key, consumer);
        if registration !in before.seenConsumers {
          ReverseSuccessfulConsumerPreservesRefinement(
            objectBindings,
            relationBindings,
            relationships,
            permissions,
            grants,
            indexedRules,
            before,
            after,
            key,
            consumer
          );
        }
      }
      case ReverseGrantWork(indexedGrant) => {
        if indexedGrant !in before.seenGrants {
          ReverseSuccessfulGrantPreservesRefinement(
            objectBindings,
            relationBindings,
            relationships,
            permissions,
            grants,
            indexedRules,
            before,
            after,
            indexedGrant
          );
        }
      }
    }
    case ReverseYielded(_) => {
    }
    case ReverseNeedScan(after, _) => {
      ReverseQueueTailIsSound(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        before.queue
      );
      assert ReverseWorkSound(
          objectBindings,
          relationBindings,
          relationships,
          permissions,
          grants,
          before.queue[0]
        );
      assert ReverseStreamSound(
          objectBindings,
          relationBindings,
          relationships,
          permissions,
          grants,
          Indexed.ReverseStream(
            after.pending.command.projection,
            [],
            true,
            after.pending.continuation
          )
        );
    }
    case ReverseEmitted(after, _, _) => {
      ReverseSuccessfulGrantPreservesRefinement(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        indexedRules,
        before,
        after,
        before.queue[0].grant
      );
    }
    case ReverseComplete(_) => {
    }
    case ReverseRenderRejected(_, _) => {
    }
    case ReverseStepLimitExceeded(_, _) => {
    }
  }

  method StepReverseRefined(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    grants: set<Semantics.Grant>,
    state: Indexed.ReverseState,
    limits: Indexed.IndexedLimits
  ) returns (outcome: Indexed.ReverseStep)
    requires ExactObjectCatalog(objects, objectBindings)
    requires ExactRelationCatalog(relations, relationBindings)
    requires NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires forall rule <- indexedRules ::
               IndexedRulePermissionClosed(rule, permissions)
    requires Indexed.ReverseStateInvariant(state)
    requires Indexed.CountersWithinLimits(state.counters, limits)
    requires state.pending.NoReversePending?
    requires Indexed.ValidReverseQueuedEids(state.queue)
    requires ReverseStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               indexedRules,
               state
             )
    ensures outcome == Indexed.ReverseStepSpec(state, limits)
    ensures ReverseStateRefines(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              indexedRules,
              outcome.state
            )
    ensures Indexed.ReverseStepRelation(state, outcome)
  {
    outcome := Indexed.StepReverse(state, limits);
    ReverseStepRelationPreservesRefinement(
      objects,
      relations,
      permissions,
      normalizedRules,
      indexedRules,
      relationships,
      objectBindings,
      relationBindings,
      grants,
      state,
      outcome
    );
  }

  lemma {:isolate_assertions}
    ReverseResumeRelationPreservesRefinement(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    indexedRules: seq<Indexed.IndexedRule>,
    before: Indexed.ReverseState,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>,
    outcome: Indexed.ReverseResume
  )
    requires Indexed.ReverseStateInvariant(before)
    requires before.pending.AwaitingReverseScan?
    requires ReverseStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               indexedRules,
               before
             )
    requires ExactProjectionValues(
               objectBindings,
               relationBindings,
               relationships,
               before.pending.command.projection,
               fullProjectionValues
             )
    requires ExactScanResponse(
               before.pending.command,
               response,
               fullProjectionValues
             )
    requires Indexed.ReverseResumeRelation(
               before,
               response,
               outcome
             )
    ensures outcome.ReverseScanResumed? ==>
              ReverseStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                permissions,
                grants,
                indexedRules,
                outcome.state
              )
    ensures outcome.ReverseScanLimitExceeded? ==>
              ReverseStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                permissions,
                grants,
                indexedRules,
                outcome.state
              )
    ensures !outcome.ReverseScanRejected?
  {
    match outcome
    case ReverseScanResumed(after) => {
      if 0 < |response.values| {
        var validated :=
          Indexed.ScanAccepted(
            response.values,
            response.terminal,
            response.fetchedValues
          );
        assert ReverseStreamSound(
            objectBindings,
            relationBindings,
            relationships,
            permissions,
            grants,
            Indexed.ReverseStream(
              before.pending.command.projection,
              [],
              true,
              before.pending.continuation
            )
          );
        ReverseResponseWorkPreservesSoundness(
          objectBindings,
          relationBindings,
          relationships,
          permissions,
          grants,
          before.pending.command,
          response,
          validated,
          before.pending.continuation,
          fullProjectionValues
        );
        ReverseQueueSoundConcatenation(
          objectBindings,
          relationBindings,
          relationships,
          permissions,
          grants,
          before.queue,
          Indexed.ReverseWorkAfterResponse(
            before.pending.command,
            before.pending.continuation,
            validated
          )
        );
      }
    }
    case ReverseScanRejected(_) => {
      assert false;
    }
    case ReverseScanLimitExceeded(_, _) => {
    }
  }

  method ResumeReverseRefined(
    objectBindings: seq<ObjectBinding>,
    relationBindings: seq<RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    indexedRules: seq<Indexed.IndexedRule>,
    state: Indexed.ReverseState,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>,
    limits: Indexed.IndexedLimits
  ) returns (outcome: Indexed.ReverseResume)
    requires Indexed.ReverseStateInvariant(state)
    requires Indexed.CountersWithinLimits(state.counters, limits)
    requires state.pending.AwaitingReverseScan?
    requires ReverseStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               indexedRules,
               state
             )
    requires ExactProjectionValues(
               objectBindings,
               relationBindings,
               relationships,
               state.pending.command.projection,
               fullProjectionValues
             )
    requires ExactScanResponse(
               state.pending.command,
               response,
               fullProjectionValues
             )
    ensures outcome ==
            Indexed.ReverseResumeSpec(
              state,
              response,
              limits
            )
    ensures outcome.ReverseScanResumed? ==>
              ReverseStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                permissions,
                grants,
                indexedRules,
                outcome.state
              )
    ensures outcome.ReverseScanLimitExceeded? ==>
              ReverseStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                permissions,
                grants,
                indexedRules,
                outcome.state
              )
    ensures !outcome.ReverseScanRejected?
  {
    outcome := Indexed.ResumeReverseScan(state, response, limits);
    ReverseResumeRelationPreservesRefinement(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      indexedRules,
      state,
      response,
      fullProjectionValues,
      outcome
    );
  }
}

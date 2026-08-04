include "Semantics.dfy"
include "IndexedTraversal.dfy"
include "IndexedRefinement.dfy"

module IndexedForwardCompleteness {
  import Semantics
  import Indexed = IndexedTraversal
  import Refinement = IndexedRefinement

  predicate EidAboveBound(
    eid: int,
    bound: Indexed.OptionalEid
  ) {
    bound.NoBound? || bound.value < eid
  }

  ghost predicate ForwardContinuationCovers(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    continuation: Indexed.ForwardContinuation,
    sourceEid: int,
    candidate: Indexed.ForwardGrantKey
  ) {
    match continuation
    case ForwardGrant(node) =>
      candidate == Indexed.ForwardGrantKey(node, sourceEid)
    case ForwardArrowRelation(
      node,
      intermediateType,
      viaRelationEid,
      resourceType
      ) =>
      candidate.node == node &&
      0 <= candidate.resourceEid &&
      Refinement.RelationshipProjectsTo(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.SubjectToResources(
          intermediateType,
          sourceEid,
          viaRelationEid,
          resourceType,
          Indexed.NoBound
        ),
        candidate.resourceEid
      )
  }

  ghost predicate ForwardStreamCovers(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    stream: Indexed.ForwardStream,
    candidate: Indexed.ForwardGrantKey
  ) {
    (exists sourceEid <- stream.buffered ::
       ForwardContinuationCovers(
         objectBindings,
         relationBindings,
         relationships,
         stream.continuation,
         sourceEid,
         candidate
       )) ||
    (stream.more &&
     exists sourceEid: int ::
       0 <= sourceEid &&
       EidAboveBound(
         sourceEid,
         Indexed.ProjectionBound(stream.projection)
       ) &&
       Refinement.RelationshipProjectsTo(
         objectBindings,
         relationBindings,
         relationships,
         stream.projection,
         sourceEid
       ) &&
       ForwardContinuationCovers(
         objectBindings,
         relationBindings,
         relationships,
         stream.continuation,
         sourceEid,
         candidate
       ))
  }

  ghost predicate ForwardWorkCovers(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    work: Indexed.ForwardWork,
    candidate: Indexed.ForwardGrantKey
  ) {
    match work
    case ForwardStreamWork(stream) =>
      ForwardStreamCovers(
        objectBindings,
        relationBindings,
        relationships,
        stream,
        candidate
      )
    case ForwardGrantWork(grant) =>
      candidate == grant
  }

  ghost predicate ForwardQueueCovers(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    queue: seq<Indexed.ForwardWork>,
    candidate: Indexed.ForwardGrantKey
  ) {
    exists work <- queue ::
      ForwardWorkCovers(
        objectBindings,
        relationBindings,
        relationships,
        work,
        candidate
      )
  }

  ghost predicate PendingForwardCovers(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    pending: Indexed.ForwardPending,
    candidate: Indexed.ForwardGrantKey
  ) {
    pending.AwaitingForwardScan? &&
    ForwardStreamCovers(
      objectBindings,
      relationBindings,
      relationships,
      Indexed.ForwardStream(
        pending.command.projection,
        [],
        true,
        pending.continuation
      ),
      candidate
    )
  }

  ghost predicate ForwardSeedGrant(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string,
    subjectEid: int,
    candidate: Indexed.ForwardGrantKey
  )
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
  {
    ForwardQueueCovers(
      objectBindings,
      relationBindings,
      relationships,
      Indexed.ForwardSeedWorks(
        seedRules,
        subjectType,
        subjectEid
      ),
      candidate
    )
  }

  ghost predicate ForwardSuccessorGrant(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    consumers: map<
      Semantics.PermissionNode,
      seq<Indexed.IndexedRule>
    >,
    source: Indexed.ForwardGrantKey,
    candidate: Indexed.ForwardGrantKey
  )
    requires 0 <= source.resourceEid
    requires forall node <- consumers.Keys ::
               Indexed.ValidIndexedRules(consumers[node])
  {
    var consumerRules :=
      if source.node in consumers
      then consumers[source.node]
      else [];
    ForwardQueueCovers(
      objectBindings,
      relationBindings,
      relationships,
      Indexed.ForwardConsumerWorks(consumerRules, source),
      candidate
    )
  }

  ghost predicate ForwardCoverageInvariant(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string,
    subjectEid: int,
    state: Indexed.ForwardState
  )
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires Indexed.ForwardStateInvariant(state)
  {
    forall candidate: Indexed.ForwardGrantKey ::
      (
        ForwardSeedGrant(
          objectBindings,
          relationBindings,
          relationships,
          seedRules,
          subjectType,
          subjectEid,
          candidate
        ) ||
        exists source <- state.seen ::
          ForwardSuccessorGrant(
            objectBindings,
            relationBindings,
            relationships,
            state.consumers,
            source,
            candidate
          )
      )
      ==>
        candidate in state.seen ||
        ForwardQueueCovers(
          objectBindings,
          relationBindings,
          relationships,
          state.queue,
          candidate
        ) ||
        PendingForwardCovers(
          objectBindings,
          relationBindings,
          relationships,
          state.pending,
          candidate
        )
  }

  ghost function SeenSubjectGrants(
    objectBindings: seq<Refinement.ObjectBinding>,
    semanticSubject: Semantics.ObjectRef,
    seen: set<Indexed.ForwardGrantKey>
  ): set<Semantics.Grant>
  {
    set indexedGrant <- seen,
      resourceBinding <- objectBindings |
        resourceBinding.eid == indexedGrant.resourceEid &&
        resourceBinding.objectRef.typeName ==
        indexedGrant.node.resourceType ::
      Semantics.Grant(
        semanticSubject,
        indexedGrant.node,
        resourceBinding.objectRef
      )
  }

  ghost function ReplaceSubjectGrants(
    grants: set<Semantics.Grant>,
    semanticSubject: Semantics.ObjectRef,
    replacement: set<Semantics.Grant>
  ): set<Semantics.Grant>
  {
    (set grant <- grants |
         grant.subject != semanticSubject) +
    replacement
  }

  ghost predicate NormalizedLeastFixedPoint(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>
  ) {
    Refinement.NormalizedFixedPoint(
      objects,
      permissions,
      normalizedRules,
      relationships,
      grants
    ) &&
    forall fixed: set<Semantics.Grant> |
      fixed <= Semantics.GrantUniverse(objects, permissions) &&
      Semantics.ImmediateConsequences(
        objects,
        permissions,
        normalizedRules,
        relationships,
        fixed
      ) == fixed ::
      grants <= fixed
  }

  ghost predicate AllRuleRelationsBound(
    normalizedRules: seq<Semantics.NormalizedRule>,
    relationBindings: seq<Refinement.RelationBinding>
  ) {
    forall normalizedRule <- normalizedRules ::
      match normalizedRule
      case DirectRelationRule(head, relationName, subjectType) =>
        exists binding <- relationBindings ::
          Refinement.RelationBindingMatches(
            binding,
            head.resourceType,
            relationName,
            subjectType
          )
      case SelfPermissionRule(_, _) =>
        true
      case ArrowRelationRule(
        head,
        viaRelation,
        targetRelation,
        subjectType
        ) =>
        exists viaBinding <- relationBindings,
          targetBinding <- relationBindings ::
          Refinement.RelationBindingMatches(
            viaBinding,
            head.resourceType,
            viaRelation,
            viaBinding.relation.subjectType
          ) &&
          Refinement.RelationBindingMatches(
            targetBinding,
            viaBinding.relation.subjectType,
            targetRelation,
            subjectType
          )
      case ArrowPermissionRule(
        head,
        viaRelation,
        _
        ) =>
        exists viaBinding <- relationBindings ::
          Refinement.RelationBindingMatches(
            viaBinding,
            head.resourceType,
            viaRelation,
            viaBinding.relation.subjectType
          )
  }

  ghost predicate RelationshipsWellTyped(
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>
  ) {
    forall relationship <- relationships ::
      exists binding <- relationBindings ::
        binding.relation ==
        Semantics.RelationNode(
          relationship.resource.typeName,
          relationship.relationName,
          relationship.subject.typeName
        )
  }

  ghost predicate RelationshipEndpointsCataloged(
    objects: seq<Semantics.ObjectRef>,
    relationships: seq<Semantics.Relationship>
  ) {
    forall relationship <- relationships ::
      relationship.resource in objects &&
      relationship.subject in objects
  }

  lemma ObjectCatalogBinding(
    objects: seq<Semantics.ObjectRef>,
    objectBindings: seq<Refinement.ObjectBinding>,
    objectRef: Semantics.ObjectRef
  ) returns (binding: Refinement.ObjectBinding)
    requires Refinement.ExactObjectCatalog(objects, objectBindings)
    requires objectRef in objects
    ensures binding in objectBindings
    ensures binding.objectRef == objectRef
  {
    var selected :| selected in objectBindings &&
                    selected.objectRef == objectRef;
    return selected;
  }

  lemma UniqueObjectRefIdentifiesBinding(
    bindings: seq<Refinement.ObjectBinding>,
    left: Refinement.ObjectBinding,
    right: Refinement.ObjectBinding
  )
    requires Refinement.UniqueBoundObjects(bindings)
    requires left in bindings
    requires right in bindings
    requires left.objectRef == right.objectRef
    ensures left == right
  {
    var leftIndex :| 0 <= leftIndex < |bindings| &&
                     bindings[leftIndex] == left;
    var rightIndex :| 0 <= rightIndex < |bindings| &&
                      bindings[rightIndex] == right;
    if leftIndex < rightIndex {
      assert bindings[leftIndex].objectRef !=
             bindings[rightIndex].objectRef;
    }
    if rightIndex < leftIndex {
      assert bindings[rightIndex].objectRef !=
             bindings[leftIndex].objectRef;
    }
  }

  lemma TypedRelationshipBinding(
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    relationship: Semantics.Relationship
  ) returns (binding: Refinement.RelationBinding)
    requires RelationshipsWellTyped(
               relationBindings,
               relationships
             )
    requires relationship in relationships
    ensures binding in relationBindings
    ensures binding.relation ==
            Semantics.RelationNode(
              relationship.resource.typeName,
              relationship.relationName,
              relationship.subject.typeName
            )
  {
    var selected :| selected in relationBindings &&
                    selected.relation ==
                    Semantics.RelationNode(
                      relationship.resource.typeName,
                      relationship.relationName,
                      relationship.subject.typeName
                    );
    return selected;
  }

  ghost predicate ForwardSeenClosed(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string,
    subjectEid: int,
    state: Indexed.ForwardState
  )
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires Indexed.ForwardStateInvariant(state)
  {
    forall candidate: Indexed.ForwardGrantKey ::
      (
        ForwardSeedGrant(
          objectBindings,
          relationBindings,
          relationships,
          seedRules,
          subjectType,
          subjectEid,
          candidate
        ) ||
        exists source <- state.seen ::
          ForwardSuccessorGrant(
            objectBindings,
            relationBindings,
            relationships,
            state.consumers,
            source,
            candidate
          )
      )
      ==> candidate in state.seen
  }

  lemma GrantUniverseMembershipHasCatalogShape(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    grant: Semantics.Grant
  )
    requires grant in
               Semantics.GrantUniverse(objects, permissions)
    ensures grant.subject in objects
    ensures grant.resource in objects
    ensures grant.node in permissions
    ensures grant.resource.typeName == grant.node.resourceType
    decreases |permissions|
  {
    if grant !in Semantics.NodeGrantUniverse(
        objects,
        permissions[0]
      )
    {
      GrantUniverseMembershipHasCatalogShape(
        objects,
        permissions[1..],
        grant
      );
    }
  }

  lemma SeenSubjectGrantsAreSound(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    indexedRules: seq<Indexed.IndexedRule>,
    state: Indexed.ForwardState
  )
    requires Refinement.UniqueObjectBindingEids(objectBindings)
    requires Refinement.ForwardStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               indexedRules,
               state
             )
    ensures SeenSubjectGrants(
              objectBindings,
              semanticSubject,
              state.seen
            ) <= grants
  {
    forall semanticGrant |
      semanticGrant in SeenSubjectGrants(
                         objectBindings,
                         semanticSubject,
                         state.seen
                       )
      ensures semanticGrant in grants
    {
      var indexedGrant,
          resourceBinding :|
        indexedGrant in state.seen &&
        resourceBinding in objectBindings &&
        resourceBinding.eid == indexedGrant.resourceEid &&
        resourceBinding.objectRef.typeName ==
        indexedGrant.node.resourceType &&
        semanticGrant ==
        Semantics.Grant(
          semanticSubject,
          indexedGrant.node,
          resourceBinding.objectRef
        );
      assert Refinement.ForwardGrantRefines(
          objectBindings,
          semanticSubject,
          grants,
          indexedGrant
        );
      var soundBinding :| soundBinding in objectBindings &&
                          soundBinding.eid ==
                          indexedGrant.resourceEid &&
                          soundBinding.objectRef.typeName ==
                          indexedGrant.node.resourceType &&
                          Semantics.Grant(
                            semanticSubject,
                            indexedGrant.node,
                            soundBinding.objectRef
                          ) in grants;
      Refinement.UniqueObjectEidIdentifiesBinding(
        objectBindings,
        resourceBinding,
        soundBinding
      );
    }
  }

  lemma SeedRuleCoverIsIncluded(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string,
    subjectEid: int,
    rule: Indexed.IndexedRule,
    candidate: Indexed.ForwardGrantKey
  )
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires rule in seedRules
    requires ForwardQueueCovers(
               objectBindings,
               relationBindings,
               relationships,
               Indexed.ForwardSeedWork(
                 rule,
                 subjectType,
                 subjectEid
               ),
               candidate
             )
    ensures ForwardSeedGrant(
              objectBindings,
              relationBindings,
              relationships,
              seedRules,
              subjectType,
              subjectEid,
              candidate
            )
    decreases |seedRules|
  {
    if seedRules[0] == rule {
      LeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ForwardSeedWork(
          seedRules[0],
          subjectType,
          subjectEid
        ),
        Indexed.ForwardSeedWorks(
          seedRules[1..],
          subjectType,
          subjectEid
        ),
        candidate
      );
    } else {
      SeedRuleCoverIsIncluded(
        objectBindings,
        relationBindings,
        relationships,
        seedRules[1..],
        subjectType,
        subjectEid,
        rule,
        candidate
      );
      LeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ForwardSeedWork(
          seedRules[0],
          subjectType,
          subjectEid
        ),
        Indexed.ForwardSeedWorks(
          seedRules[1..],
          subjectType,
          subjectEid
        ),
        candidate
      );
    }
  }

  lemma ArrowRelationSeedWorkCovers(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    indexedRule: Indexed.IndexedRule,
    subjectType: string,
    subjectEid: int,
    intermediateEid: int,
    resourceEid: int
  )
    requires indexedRule.ArrowRelationRule?
    requires subjectType == indexedRule.targetSubjectType
    requires 0 <= subjectEid
    requires 0 <= intermediateEid
    requires 0 <= resourceEid
    requires Refinement.RelationshipProjectsTo(
               objectBindings,
               relationBindings,
               relationships,
               Indexed.SubjectToResources(
                 subjectType,
                 subjectEid,
                 indexedRule.targetRelationEid,
                 indexedRule.intermediateType,
                 Indexed.NoBound
               ),
               intermediateEid
             )
    requires Refinement.RelationshipProjectsTo(
               objectBindings,
               relationBindings,
               relationships,
               Indexed.SubjectToResources(
                 indexedRule.intermediateType,
                 intermediateEid,
                 indexedRule.viaRelationEid,
                 indexedRule.head.resourceType,
                 Indexed.NoBound
               ),
               resourceEid
             )
    ensures ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ForwardSeedWork(
                indexedRule,
                subjectType,
                subjectEid
              ),
              Indexed.ForwardGrantKey(
                indexedRule.head,
                resourceEid
              )
            )
  {
    var candidate :=
      Indexed.ForwardGrantKey(indexedRule.head, resourceEid);
    var continuation :=
      Indexed.ForwardArrowRelation(
        indexedRule.head,
        indexedRule.intermediateType,
        indexedRule.viaRelationEid,
        indexedRule.head.resourceType
      );
    assert ForwardContinuationCovers(
        objectBindings,
        relationBindings,
        relationships,
        continuation,
        intermediateEid,
        candidate
      );
    var stream :=
      Indexed.ForwardStream(
        Indexed.SubjectToResources(
          subjectType,
          subjectEid,
          indexedRule.targetRelationEid,
          indexedRule.intermediateType,
          Indexed.NoBound
        ),
        [],
        true,
        continuation
      );
    assert ForwardStreamCovers(
        objectBindings,
        relationBindings,
        relationships,
        stream,
        candidate
      );
    assert Indexed.ForwardSeedWork(
        indexedRule,
        subjectType,
        subjectEid
      ) == [Indexed.ForwardStreamWork(stream)];
    assert ForwardWorkCovers(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ForwardStreamWork(stream),
        candidate
      );
  }

  lemma ArrowPermissionConsumerWorkCovers(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    indexedRule: Indexed.IndexedRule,
    source: Indexed.ForwardGrantKey,
    resourceEid: int
  )
    requires indexedRule.ArrowPermissionRule?
    requires Indexed.ValidIndexedRule(indexedRule)
    requires source.node == indexedRule.targetNode
    requires 0 <= source.resourceEid
    requires 0 <= resourceEid
    requires Refinement.RelationshipProjectsTo(
               objectBindings,
               relationBindings,
               relationships,
               Indexed.SubjectToResources(
                 indexedRule.intermediateType,
                 source.resourceEid,
                 indexedRule.viaRelationEid,
                 indexedRule.head.resourceType,
                 Indexed.NoBound
               ),
               resourceEid
             )
    ensures ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ForwardConsumerWork(indexedRule, source),
              Indexed.ForwardGrantKey(
                indexedRule.head,
                resourceEid
              )
            )
  {
    var candidate :=
      Indexed.ForwardGrantKey(indexedRule.head, resourceEid);
    var stream :=
      Indexed.ForwardStream(
        Indexed.SubjectToResources(
          indexedRule.intermediateType,
          source.resourceEid,
          indexedRule.viaRelationEid,
          indexedRule.head.resourceType,
          Indexed.NoBound
        ),
        [],
        true,
        Indexed.ForwardGrant(indexedRule.head)
      );
    assert ForwardContinuationCovers(
        objectBindings,
        relationBindings,
        relationships,
        stream.continuation,
        resourceEid,
        candidate
      );
    assert ForwardStreamCovers(
        objectBindings,
        relationBindings,
        relationships,
        stream,
        candidate
      );
    assert Indexed.ForwardConsumerWork(
        indexedRule,
        source
      ) == [Indexed.ForwardStreamWork(stream)];
    assert ForwardWorkCovers(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ForwardStreamWork(stream),
        candidate
      );
  }

  lemma ClosedSuccessorIsSeen(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string,
    subjectEid: int,
    state: Indexed.ForwardState,
    source: Indexed.ForwardGrantKey,
    candidate: Indexed.ForwardGrantKey
  )
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires Indexed.ForwardStateInvariant(state)
    requires ForwardSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               seedRules,
               subjectType,
               subjectEid,
               state
             )
    requires source in state.seen
    requires ForwardSuccessorGrant(
               objectBindings,
               relationBindings,
               relationships,
               state.consumers,
               source,
               candidate
             )
    ensures candidate in state.seen
  {
  }

  lemma ConsumerRuleCoverIsIncluded(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    consumerRules: seq<Indexed.IndexedRule>,
    source: Indexed.ForwardGrantKey,
    rule: Indexed.IndexedRule,
    candidate: Indexed.ForwardGrantKey
  )
    requires Indexed.ValidIndexedRules(consumerRules)
    requires 0 <= source.resourceEid
    requires rule in consumerRules
    requires ForwardQueueCovers(
               objectBindings,
               relationBindings,
               relationships,
               Indexed.ForwardConsumerWork(rule, source),
               candidate
             )
    ensures ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ForwardConsumerWorks(
                consumerRules,
                source
              ),
              candidate
            )
    decreases |consumerRules|
  {
    if consumerRules[0] == rule {
      LeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ForwardConsumerWork(
          consumerRules[0],
          source
        ),
        Indexed.ForwardConsumerWorks(
          consumerRules[1..],
          source
        ),
        candidate
      );
    } else {
      ConsumerRuleCoverIsIncluded(
        objectBindings,
        relationBindings,
        relationships,
        consumerRules[1..],
        source,
        rule,
        candidate
      );
      LeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ForwardConsumerWork(
          consumerRules[0],
          source
        ),
        Indexed.ForwardConsumerWorks(
          consumerRules[1..],
          source
        ),
        candidate
      );
    }
  }

  function ForwardConsumerTarget(
    rule: Indexed.IndexedRule
  ): Semantics.PermissionNode
    requires rule.SelfPermissionRule? ||
             rule.ArrowPermissionRule?
  {
    if rule.SelfPermissionRule?
    then rule.targetNode
    else rule.targetNode
  }

  lemma PermissionRuleAppearsInForwardConsumers(
    indexedRules: seq<Indexed.IndexedRule>,
    rule: Indexed.IndexedRule
  )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires rule in indexedRules
    requires rule.SelfPermissionRule? ||
             rule.ArrowPermissionRule?
    ensures ForwardConsumerTarget(rule) in
              Indexed.ForwardConsumers(indexedRules)
    ensures rule in
              Indexed.ForwardConsumers(indexedRules)[
              ForwardConsumerTarget(rule)
              ]
    decreases |indexedRules|
  {
    if indexedRules[0] != rule {
      PermissionRuleAppearsInForwardConsumers(
        indexedRules[1..],
        rule
      );
    }
  }

  lemma QueueCoverComesFromLeftOrRight(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    left: seq<Indexed.ForwardWork>,
    right: seq<Indexed.ForwardWork>,
    candidate: Indexed.ForwardGrantKey
  )
    requires ForwardQueueCovers(
               objectBindings,
               relationBindings,
               relationships,
               left + right,
               candidate
             )
    ensures ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              left,
              candidate
            ) ||
            ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              right,
              candidate
            )
  {
  }

  lemma LeftOrRightCoverImpliesConcatenatedCover(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    left: seq<Indexed.ForwardWork>,
    right: seq<Indexed.ForwardWork>,
    candidate: Indexed.ForwardGrantKey
  )
    requires ForwardQueueCovers(
               objectBindings,
               relationBindings,
               relationships,
               left,
               candidate
             ) ||
             ForwardQueueCovers(
               objectBindings,
               relationBindings,
               relationships,
               right,
               candidate
             )
    ensures ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              left + right,
              candidate
            )
  {
  }

  lemma EmptyQueueCoversNothing(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    candidate: Indexed.ForwardGrantKey
  )
    ensures !ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              [],
              candidate
            )
  {
  }

  lemma NoPendingScanCoversNothing(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    candidate: Indexed.ForwardGrantKey
  )
    ensures !PendingForwardCovers(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.NoForwardPending,
              candidate
            )
  {
  }

  lemma ForwardInitializationEstablishesCoverage(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string,
    subjectEid: int,
    state: Indexed.ForwardState
  )
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires Indexed.ForwardStateInvariant(state)
    requires state.queue ==
             Indexed.ForwardSeedWorks(
               seedRules,
               subjectType,
               subjectEid
             )
    requires state.seen == {}
    requires state.pending.NoForwardPending?
    ensures ForwardCoverageInvariant(
              objectBindings,
              relationBindings,
              relationships,
              seedRules,
              subjectType,
              subjectEid,
              state
            )
  {
  }

  lemma ContinueForwardCoversCandidate(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    continuation: Indexed.ForwardContinuation,
    sourceEid: int,
    candidate: Indexed.ForwardGrantKey
  )
    requires 0 <= sourceEid
    requires ForwardContinuationCovers(
               objectBindings,
               relationBindings,
               relationships,
               continuation,
               sourceEid,
               candidate
             )
    ensures ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ContinueForward(continuation, sourceEid),
              candidate
            )
  {
    match continuation
    case ForwardGrant(node) => {
      assert candidate ==
             Indexed.ForwardGrantKey(node, sourceEid);
      assert ForwardWorkCovers(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ForwardGrantWork(candidate),
          candidate
        );
    }
    case ForwardArrowRelation(
      node,
        intermediateType,
        viaRelationEid,
        resourceType
        ) => {
      assert 0 <= candidate.resourceEid;
      assert EidAboveBound(candidate.resourceEid, Indexed.NoBound);
      assert ForwardContinuationCovers(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ForwardGrant(node),
          candidate.resourceEid,
          candidate
        );
      assert ForwardStreamCovers(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ForwardStream(
            Indexed.SubjectToResources(
              intermediateType,
              sourceEid,
              viaRelationEid,
              resourceType,
              Indexed.NoBound
            ),
            [],
            true,
            Indexed.ForwardGrant(node)
          ),
          candidate
        );
      assert ForwardWorkCovers(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ContinueForward(continuation, sourceEid)[0],
          candidate
        );
    }
  }

  lemma BufferedForwardStreamCoverIsPreserved(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    stream: Indexed.ForwardStream,
    candidate: Indexed.ForwardGrantKey
  )
    requires 0 < |stream.buffered|
    requires forall index | 0 <= index < |stream.buffered| ::
               0 <= stream.buffered[index]
    requires ForwardStreamCovers(
               objectBindings,
               relationBindings,
               relationships,
               stream,
               candidate
             )
    ensures ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ForwardBufferedWork(stream),
              candidate
            )
  {
    var continuationWork :=
      Indexed.ContinueForward(
        stream.continuation,
        stream.buffered[0]
      );
    var streamWork :=
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
    if exists sourceEid <- stream.buffered ::
        ForwardContinuationCovers(
          objectBindings,
          relationBindings,
          relationships,
          stream.continuation,
          sourceEid,
          candidate
        )
    {
      var sourceEid :| sourceEid in stream.buffered &&
                       ForwardContinuationCovers(
                         objectBindings,
                         relationBindings,
                         relationships,
                         stream.continuation,
                         sourceEid,
                         candidate
                       );
      if sourceEid == stream.buffered[0] {
        ContinueForwardCoversCandidate(
          objectBindings,
          relationBindings,
          relationships,
          stream.continuation,
          sourceEid,
          candidate
        );
        LeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          continuationWork,
          streamWork,
          candidate
        );
      } else {
        assert sourceEid in stream.buffered[1..];
        assert 1 < |stream.buffered|;
        assert ForwardWorkCovers(
            objectBindings,
            relationBindings,
            relationships,
            streamWork[0],
            candidate
          );
        assert ForwardQueueCovers(
            objectBindings,
            relationBindings,
            relationships,
            streamWork,
            candidate
          );
        LeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          continuationWork,
          streamWork,
          candidate
        );
      }
    } else {
      assert stream.more;
      assert 0 < |streamWork|;
      assert ForwardWorkCovers(
          objectBindings,
          relationBindings,
          relationships,
          streamWork[0],
          candidate
        );
      assert ForwardQueueCovers(
          objectBindings,
          relationBindings,
          relationships,
          streamWork,
          candidate
        );
      LeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        continuationWork,
        streamWork,
        candidate
      );
    }
  }

  lemma EmptyFinishedForwardStreamCoversNothing(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    stream: Indexed.ForwardStream,
    candidate: Indexed.ForwardGrantKey
  )
    requires stream.buffered == []
    requires !stream.more
    ensures !ForwardStreamCovers(
              objectBindings,
              relationBindings,
              relationships,
              stream,
              candidate
            )
  {
  }

  lemma IssuedPendingScanCoversEmptyOpenStream(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    stream: Indexed.ForwardStream,
    command: Indexed.ScanCommand,
    candidate: Indexed.ForwardGrantKey
  )
    requires stream.buffered == []
    requires stream.more
    requires command.projection == stream.projection
    ensures ForwardStreamCovers(
              objectBindings,
              relationBindings,
              relationships,
              stream,
              candidate
            ) <==>
            PendingForwardCovers(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.AwaitingForwardScan(
                command,
                stream.continuation
              ),
              candidate
            )
  {
  }

  lemma QueueHeadOrTailCovers(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    queue: seq<Indexed.ForwardWork>,
    candidate: Indexed.ForwardGrantKey
  )
    requires 0 < |queue|
    requires ForwardQueueCovers(
               objectBindings,
               relationBindings,
               relationships,
               queue,
               candidate
             )
    ensures ForwardWorkCovers(
              objectBindings,
              relationBindings,
              relationships,
              queue[0],
              candidate
            ) ||
            ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              queue[1..],
              candidate
            )
  {
    assert queue == [queue[0]] + queue[1..];
    QueueCoverComesFromLeftOrRight(
      objectBindings,
      relationBindings,
      relationships,
      [queue[0]],
      queue[1..],
      candidate
    );
  }

  lemma QueueTailCoverSurvivesAppendedWork(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    tail: seq<Indexed.ForwardWork>,
    appended: seq<Indexed.ForwardWork>,
    candidate: Indexed.ForwardGrantKey
  )
    requires ForwardQueueCovers(
               objectBindings,
               relationBindings,
               relationships,
               tail,
               candidate
             )
    ensures ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              tail + appended,
              candidate
            )
  {
    LeftOrRightCoverImpliesConcatenatedCover(
      objectBindings,
      relationBindings,
      relationships,
      tail,
      appended,
      candidate
    );
  }

  lemma ForwardCoverageTransport(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string,
    subjectEid: int,
    before: Indexed.ForwardState,
    after: Indexed.ForwardState
  )
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires Indexed.ForwardStateInvariant(before)
    requires Indexed.ForwardStateInvariant(after)
    requires ForwardCoverageInvariant(
               objectBindings,
               relationBindings,
               relationships,
               seedRules,
               subjectType,
               subjectEid,
               before
             )
    requires after.consumers == before.consumers
    requires before.seen <= after.seen
    requires forall candidate: Indexed.ForwardGrantKey |
               ForwardQueueCovers(
                 objectBindings,
                 relationBindings,
                 relationships,
                 before.queue,
                 candidate
               ) ::
               candidate in after.seen ||
               ForwardQueueCovers(
                 objectBindings,
                 relationBindings,
                 relationships,
                 after.queue,
                 candidate
               ) ||
               PendingForwardCovers(
                 objectBindings,
                 relationBindings,
                 relationships,
                 after.pending,
                 candidate
               )
    requires forall candidate: Indexed.ForwardGrantKey |
               PendingForwardCovers(
                 objectBindings,
                 relationBindings,
                 relationships,
                 before.pending,
                 candidate
               ) ::
               candidate in after.seen ||
               ForwardQueueCovers(
                 objectBindings,
                 relationBindings,
                 relationships,
                 after.queue,
                 candidate
               ) ||
               PendingForwardCovers(
                 objectBindings,
                 relationBindings,
                 relationships,
                 after.pending,
                 candidate
               )
    requires forall source: Indexed.ForwardGrantKey,
               candidate: Indexed.ForwardGrantKey |
               source in after.seen &&
               source !in before.seen &&
               ForwardSuccessorGrant(
                 objectBindings,
                 relationBindings,
                 relationships,
                 after.consumers,
                 source,
                 candidate
               ) ::
               candidate in after.seen ||
               ForwardQueueCovers(
                 objectBindings,
                 relationBindings,
                 relationships,
                 after.queue,
                 candidate
               ) ||
               PendingForwardCovers(
                 objectBindings,
                 relationBindings,
                 relationships,
                 after.pending,
                 candidate
               )
    ensures ForwardCoverageInvariant(
              objectBindings,
              relationBindings,
              relationships,
              seedRules,
              subjectType,
              subjectEid,
              after
            )
  {
    forall candidate: Indexed.ForwardGrantKey |
      ForwardSeedGrant(
        objectBindings,
        relationBindings,
        relationships,
        seedRules,
        subjectType,
        subjectEid,
        candidate
      ) ||
      exists source <- after.seen ::
        ForwardSuccessorGrant(
          objectBindings,
          relationBindings,
          relationships,
          after.consumers,
          source,
          candidate
        )
      ensures candidate in after.seen ||
              ForwardQueueCovers(
                objectBindings,
                relationBindings,
                relationships,
                after.queue,
                candidate
              ) ||
              PendingForwardCovers(
                objectBindings,
                relationBindings,
                relationships,
                after.pending,
                candidate
              )
    {
      if !ForwardSeedGrant(
          objectBindings,
          relationBindings,
          relationships,
          seedRules,
          subjectType,
          subjectEid,
          candidate
        )
      {
        var source :| source in after.seen &&
                      ForwardSuccessorGrant(
                        objectBindings,
                        relationBindings,
                        relationships,
                        after.consumers,
                        source,
                        candidate
                      );
        if source !in before.seen {
          assert candidate in after.seen ||
                 ForwardQueueCovers(
                   objectBindings,
                   relationBindings,
                   relationships,
                   after.queue,
                   candidate
                 ) ||
                 PendingForwardCovers(
                   objectBindings,
                   relationBindings,
                   relationships,
                   after.pending,
                   candidate
                 );
        } else {
          assert ForwardSuccessorGrant(
              objectBindings,
              relationBindings,
              relationships,
              before.consumers,
              source,
              candidate
            );
        }
      }
      if candidate !in before.seen {
        if ForwardQueueCovers(
            objectBindings,
            relationBindings,
            relationships,
            before.queue,
            candidate
          )
        {
          assert candidate in after.seen ||
                 ForwardQueueCovers(
                   objectBindings,
                   relationBindings,
                   relationships,
                   after.queue,
                   candidate
                 ) ||
                 PendingForwardCovers(
                   objectBindings,
                   relationBindings,
                   relationships,
                   after.pending,
                   candidate
                 );
        } else if PendingForwardCovers(
            objectBindings,
            relationBindings,
            relationships,
            before.pending,
            candidate
          )
        {
          assert candidate in after.seen ||
                 ForwardQueueCovers(
                   objectBindings,
                   relationBindings,
                   relationships,
                   after.queue,
                   candidate
                 ) ||
                 PendingForwardCovers(
                   objectBindings,
                   relationBindings,
                   relationships,
                   after.pending,
                   candidate
                 );
        }
      }
    }
  }

  lemma ForwardStepCoversOldQueueObligation(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    before: Indexed.ForwardState,
    outcome: Indexed.ForwardStep,
    candidate: Indexed.ForwardGrantKey
  )
    requires Indexed.ForwardStateInvariant(before)
    requires Indexed.ValidForwardQueuedEids(before.queue)
    requires Indexed.ForwardStepRelation(before, outcome)
    requires ForwardQueueCovers(
               objectBindings,
               relationBindings,
               relationships,
               before.queue,
               candidate
             )
    ensures candidate in outcome.state.seen ||
            ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              outcome.state.queue,
              candidate
            ) ||
            PendingForwardCovers(
              objectBindings,
              relationBindings,
              relationships,
              outcome.state.pending,
              candidate
            )
  {
    match outcome
    case ForwardAdvanced(after) => {
      QueueHeadOrTailCovers(
        objectBindings,
        relationBindings,
        relationships,
        before.queue,
        candidate
      );
      var work := before.queue[0];
      if ForwardQueueCovers(
          objectBindings,
          relationBindings,
          relationships,
          before.queue[1..],
          candidate
        )
      {
        match work
        case ForwardStreamWork(stream) => {
          if |stream.buffered| == 0 {
            assert after.queue == before.queue[1..];
          } else {
            QueueTailCoverSurvivesAppendedWork(
              objectBindings,
              relationBindings,
              relationships,
              before.queue[1..],
              Indexed.ForwardBufferedWork(stream),
              candidate
            );
          }
        }
        case ForwardGrantWork(grant) => {
          if grant in before.seen {
            assert after.queue == before.queue[1..];
          } else {
            var consumerRules :=
              if grant.node in before.consumers
              then before.consumers[grant.node]
              else [];
            QueueTailCoverSurvivesAppendedWork(
              objectBindings,
              relationBindings,
              relationships,
              before.queue[1..],
              Indexed.ForwardConsumerWorks(
                consumerRules,
                grant
              ),
              candidate
            );
          }
        }
      } else {
        assert ForwardWorkCovers(
            objectBindings,
            relationBindings,
            relationships,
            work,
            candidate
          );
        match work
        case ForwardStreamWork(stream) => {
          if |stream.buffered| == 0 {
            EmptyFinishedForwardStreamCoversNothing(
              objectBindings,
              relationBindings,
              relationships,
              stream,
              candidate
            );
          } else {
            BufferedForwardStreamCoverIsPreserved(
              objectBindings,
              relationBindings,
              relationships,
              stream,
              candidate
            );
            LeftOrRightCoverImpliesConcatenatedCover(
              objectBindings,
              relationBindings,
              relationships,
              before.queue[1..],
              Indexed.ForwardBufferedWork(stream),
              candidate
            );
          }
        }
        case ForwardGrantWork(grant) => {
          assert candidate == grant;
          if grant in before.seen {
            assert grant in after.seen;
          } else {
            assert grant in after.seen;
          }
        }
      }
    }
    case ForwardNeedScan(after, command) => {
      QueueHeadOrTailCovers(
        objectBindings,
        relationBindings,
        relationships,
        before.queue,
        candidate
      );
      if ForwardQueueCovers(
          objectBindings,
          relationBindings,
          relationships,
          before.queue[1..],
          candidate
        )
      {
        assert after.queue == before.queue[1..];
      } else {
        var stream := before.queue[0].stream;
        assert ForwardStreamCovers(
            objectBindings,
            relationBindings,
            relationships,
            stream,
            candidate
          );
        IssuedPendingScanCoversEmptyOpenStream(
          objectBindings,
          relationBindings,
          relationships,
          stream,
          command,
          candidate
        );
      }
    }
    case ForwardEmitted(after, _, _) => {
      QueueHeadOrTailCovers(
        objectBindings,
        relationBindings,
        relationships,
        before.queue,
        candidate
      );
      var grant := before.queue[0].grant;
      if ForwardQueueCovers(
          objectBindings,
          relationBindings,
          relationships,
          before.queue[1..],
          candidate
        )
      {
        var consumerRules :=
          if grant.node in before.consumers
          then before.consumers[grant.node]
          else [];
        QueueTailCoverSurvivesAppendedWork(
          objectBindings,
          relationBindings,
          relationships,
          before.queue[1..],
          Indexed.ForwardConsumerWorks(
            consumerRules,
            grant
          ),
          candidate
        );
      } else {
        assert candidate == grant;
        assert candidate in after.seen;
      }
    }
    case ForwardYielded(after) => {
      assert after == before;
    }
    case ForwardComplete(after) => {
      assert after.queue == before.queue;
      assert after.seen == before.seen;
      assert after.pending == before.pending;
    }
    case ForwardRenderRejected(_, after) => {
      assert after == before;
    }
    case ForwardStepLimitExceeded(_, after) => {
      assert after == before;
    }
  }

  lemma ForwardStepCoversNewSuccessor(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    before: Indexed.ForwardState,
    outcome: Indexed.ForwardStep,
    source: Indexed.ForwardGrantKey,
    candidate: Indexed.ForwardGrantKey
  )
    requires Indexed.ForwardStateInvariant(before)
    requires Indexed.ValidForwardQueuedEids(before.queue)
    requires Indexed.ForwardStepRelation(before, outcome)
    requires source in outcome.state.seen
    requires source !in before.seen
    requires ForwardSuccessorGrant(
               objectBindings,
               relationBindings,
               relationships,
               outcome.state.consumers,
               source,
               candidate
             )
    ensures candidate in outcome.state.seen ||
            ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              outcome.state.queue,
              candidate
            ) ||
            PendingForwardCovers(
              objectBindings,
              relationBindings,
              relationships,
              outcome.state.pending,
              candidate
            )
  {
    match outcome
    case ForwardAdvanced(after) => {
      var grant := before.queue[0].grant;
      assert source == grant;
      var consumerRules :=
        if grant.node in before.consumers
        then before.consumers[grant.node]
        else [];
      assert after.consumers == before.consumers;
      assert ForwardQueueCovers(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ForwardConsumerWorks(
            consumerRules,
            grant
          ),
          candidate
        );
      LeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        before.queue[1..],
        Indexed.ForwardConsumerWorks(
          consumerRules,
          grant
        ),
        candidate
      );
    }
    case ForwardEmitted(after, _, _) => {
      var grant := before.queue[0].grant;
      assert source == grant;
      var consumerRules :=
        if grant.node in before.consumers
        then before.consumers[grant.node]
        else [];
      assert after.consumers == before.consumers;
      assert ForwardQueueCovers(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ForwardConsumerWorks(
            consumerRules,
            grant
          ),
          candidate
        );
      LeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        before.queue[1..],
        Indexed.ForwardConsumerWorks(
          consumerRules,
          grant
        ),
        candidate
      );
    }
    case ForwardYielded(after) => {
      assert after == before;
      assert false;
    }
    case ForwardNeedScan(after, _) => {
      assert after.seen == before.seen;
      assert false;
    }
    case ForwardComplete(after) => {
      assert after.seen == before.seen;
      assert false;
    }
    case ForwardRenderRejected(_, after) => {
      assert after == before;
      assert false;
    }
    case ForwardStepLimitExceeded(_, after) => {
      assert after == before;
      assert false;
    }
  }

  lemma ForwardStepPreservesCoverage(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string,
    subjectEid: int,
    before: Indexed.ForwardState,
    limits: Indexed.IndexedLimits,
    outcome: Indexed.ForwardStep
  )
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires Indexed.ForwardStateInvariant(before)
    requires Indexed.CountersWithinLimits(before.counters, limits)
    requires before.pending.NoForwardPending?
    requires Indexed.ValidForwardQueuedEids(before.queue)
    requires ForwardCoverageInvariant(
               objectBindings,
               relationBindings,
               relationships,
               seedRules,
               subjectType,
               subjectEid,
               before
             )
    requires outcome == Indexed.ForwardStepSpec(before, limits)
    ensures ForwardCoverageInvariant(
              objectBindings,
              relationBindings,
              relationships,
              seedRules,
              subjectType,
              subjectEid,
              outcome.state
            )
  {
    assert Indexed.ForwardStateInvariant(outcome.state);
    assert Indexed.ForwardStepRelation(before, outcome);
    assert outcome.state.consumers == before.consumers;
    assert before.seen <= outcome.state.seen;
    forall candidate: Indexed.ForwardGrantKey |
      ForwardQueueCovers(
        objectBindings,
        relationBindings,
        relationships,
        before.queue,
        candidate
      )
      ensures candidate in outcome.state.seen ||
              ForwardQueueCovers(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state.queue,
                candidate
              ) ||
              PendingForwardCovers(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state.pending,
                candidate
              )
    {
      ForwardStepCoversOldQueueObligation(
        objectBindings,
        relationBindings,
        relationships,
        before,
        outcome,
        candidate
      );
    }
    forall candidate: Indexed.ForwardGrantKey |
      PendingForwardCovers(
        objectBindings,
        relationBindings,
        relationships,
        before.pending,
        candidate
      )
      ensures candidate in outcome.state.seen ||
              ForwardQueueCovers(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state.queue,
                candidate
              ) ||
              PendingForwardCovers(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state.pending,
                candidate
              )
    {
      NoPendingScanCoversNothing(
        objectBindings,
        relationBindings,
        relationships,
        candidate
      );
      assert false;
    }
    forall source: Indexed.ForwardGrantKey,
      candidate: Indexed.ForwardGrantKey |
      source in outcome.state.seen &&
      source !in before.seen &&
      ForwardSuccessorGrant(
        objectBindings,
        relationBindings,
        relationships,
        outcome.state.consumers,
        source,
        candidate
      )
      ensures candidate in outcome.state.seen ||
              ForwardQueueCovers(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state.queue,
                candidate
              ) ||
              PendingForwardCovers(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state.pending,
                candidate
              )
    {
      ForwardStepCoversNewSuccessor(
        objectBindings,
        relationBindings,
        relationships,
        before,
        outcome,
        source,
        candidate
      );
    }
    ForwardCoverageTransport(
      objectBindings,
      relationBindings,
      relationships,
      seedRules,
      subjectType,
      subjectEid,
      before,
      outcome.state
    );
  }

  lemma ValuesAfterBoundKeepsAboveMember(
    values: seq<int>,
    bound: Indexed.OptionalEid,
    eid: int
  )
    requires eid in values
    requires EidAboveBound(eid, bound)
    ensures eid in Refinement.ValuesAfterBound(values, bound)
    decreases |values|
  {
    if |values| != 0 &&
       bound.Bound? &&
       values[0] <= bound.value
    {
      assert eid != values[0];
      assert eid in values[1..];
      ValuesAfterBoundKeepsAboveMember(
        values[1..],
        bound,
        eid
      );
    }
  }

  lemma ValuesAfterBoundPreservesIncreasing(
    values: seq<int>,
    bound: Indexed.OptionalEid
  )
    requires Indexed.StrictlyIncreasing(values)
    ensures Indexed.StrictlyIncreasing(
              Refinement.ValuesAfterBound(values, bound)
            )
    decreases |values|
  {
    if |values| != 0 &&
       bound.Bound? &&
       values[0] <= bound.value
    {
      ValuesAfterBoundPreservesIncreasing(values[1..], bound);
    }
  }

  lemma ChunkPrefixContainsMemberOrEndsBeforeIt(
    values: seq<int>,
    chunkSize: nat,
    eid: int
  )
    requires 0 < chunkSize
    requires Indexed.StrictlyIncreasing(values)
    requires eid in values
    ensures eid in Refinement.ChunkPrefix(values, chunkSize) ||
            (
              0 < |Refinement.ChunkPrefix(values, chunkSize)| &&
              Refinement.ChunkPrefix(values, chunkSize)[
              |Refinement.ChunkPrefix(values, chunkSize)| - 1
              ] < eid
            )
  {
    if |values| > chunkSize &&
       eid !in Refinement.ChunkPrefix(values, chunkSize)
    {
      var eidIndex :| 0 <= eidIndex < |values| &&
                      values[eidIndex] == eid;
      assert chunkSize <= eidIndex;
      assert 0 <= chunkSize - 1 < eidIndex < |values|;
      assert values[chunkSize - 1] < values[eidIndex];
    }
  }

  lemma ProjectionAfterChunkPreservesProjectedMembership(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    projection: Indexed.Projection,
    values: seq<int>,
    candidateEid: int
  )
    requires 0 < |values|
    ensures Refinement.RelationshipProjectsTo(
              objectBindings,
              relationBindings,
              relationships,
              projection,
              candidateEid
            ) <==>
            Refinement.RelationshipProjectsTo(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ProjectionAfterChunk(projection, values),
              candidateEid
            )
  {
  }

  lemma ForwardResponseWorkCoversSource(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    command: Indexed.ScanCommand,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>,
    continuation: Indexed.ForwardContinuation,
    sourceEid: int,
    candidate: Indexed.ForwardGrantKey
  )
    requires Refinement.ExactProjectionValues(
               objectBindings,
               relationBindings,
               relationships,
               command.projection,
               fullProjectionValues
             )
    requires Refinement.ExactScanResponse(
               command,
               response,
               fullProjectionValues
             )
    requires 0 < |response.values|
    requires sourceEid in Refinement.ValuesAfterBound(
                            fullProjectionValues,
                            Indexed.ProjectionBound(
                              command.projection
                            )
                          )
    requires Refinement.RelationshipProjectsTo(
               objectBindings,
               relationBindings,
               relationships,
               command.projection,
               sourceEid
             )
    requires ForwardContinuationCovers(
               objectBindings,
               relationBindings,
               relationships,
               continuation,
               sourceEid,
               candidate
             )
    ensures ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ForwardWorkAfterResponse(
                command,
                continuation,
                Indexed.ScanAccepted(
                  response.values,
                  response.terminal,
                  response.fetchedValues
                )
              ),
              candidate
            )
  {
    var remaining :=
      Refinement.ValuesAfterBound(
        fullProjectionValues,
        Indexed.ProjectionBound(command.projection)
      );
    var accepted :=
      Indexed.ScanAccepted(
        response.values,
        response.terminal,
        response.fetchedValues
      );
    var continuationWork :=
      Indexed.ContinueForward(continuation, response.values[0]);
    var streamWork :=
      if 1 < |response.values| || !response.terminal
      then
        [Indexed.ForwardStreamWork(
           Indexed.ForwardStream(
             Indexed.ProjectionAfterChunk(
               command.projection,
               response.values
             ),
             response.values[1..],
             !response.terminal,
             continuation
           )
         )]
      else [];
    assert response.values ==
           Refinement.ChunkPrefix(remaining, command.chunkSize);
    ValuesAfterBoundPreservesIncreasing(
      fullProjectionValues,
      Indexed.ProjectionBound(command.projection)
    );
    ChunkPrefixContainsMemberOrEndsBeforeIt(
      remaining,
      command.chunkSize,
      sourceEid
    );
    if sourceEid in response.values {
      if sourceEid == response.values[0] {
        ContinueForwardCoversCandidate(
          objectBindings,
          relationBindings,
          relationships,
          continuation,
          sourceEid,
          candidate
        );
        LeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          continuationWork,
          streamWork,
          candidate
        );
      } else {
        assert sourceEid in response.values[1..];
        assert 1 < |response.values|;
        assert 0 < |streamWork|;
        assert ForwardStreamCovers(
            objectBindings,
            relationBindings,
            relationships,
            streamWork[0].stream,
            candidate
          );
        assert ForwardWorkCovers(
            objectBindings,
            relationBindings,
            relationships,
            streamWork[0],
            candidate
          );
        assert ForwardQueueCovers(
            objectBindings,
            relationBindings,
            relationships,
            streamWork,
            candidate
          );
        LeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          continuationWork,
          streamWork,
          candidate
        );
      }
    } else {
      assert !response.terminal;
      assert 0 < |streamWork|;
      var projection :=
        Indexed.ProjectionAfterChunk(
          command.projection,
          response.values
        );
      ProjectionAfterChunkPreservesProjectedMembership(
        objectBindings,
        relationBindings,
        relationships,
        command.projection,
        response.values,
        sourceEid
      );
      assert Indexed.ProjectionBound(projection) ==
             Indexed.Bound(
               response.values[|response.values| - 1]
             );
      assert EidAboveBound(
          sourceEid,
          Indexed.ProjectionBound(projection)
        );
      assert ForwardStreamCovers(
          objectBindings,
          relationBindings,
          relationships,
          streamWork[0].stream,
          candidate
        );
      assert ForwardWorkCovers(
          objectBindings,
          relationBindings,
          relationships,
          streamWork[0],
          candidate
        );
      assert ForwardQueueCovers(
          objectBindings,
          relationBindings,
          relationships,
          streamWork,
          candidate
        );
      LeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        continuationWork,
        streamWork,
        candidate
      );
    }
    assert Indexed.ForwardWorkAfterResponse(
        command,
        continuation,
        accepted
      ) == continuationWork + streamWork;
  }

  lemma CertifiedResponseCoversPendingForwardObligation(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    pending: Indexed.ForwardPending,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>,
    candidate: Indexed.ForwardGrantKey
  )
    requires pending.AwaitingForwardScan?
    requires Refinement.ExactProjectionValues(
               objectBindings,
               relationBindings,
               relationships,
               pending.command.projection,
               fullProjectionValues
             )
    requires Refinement.ExactScanResponse(
               pending.command,
               response,
               fullProjectionValues
             )
    requires PendingForwardCovers(
               objectBindings,
               relationBindings,
               relationships,
               pending,
               candidate
             )
    ensures 0 < |response.values| &&
            ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ForwardWorkAfterResponse(
                pending.command,
                pending.continuation,
                Indexed.ScanAccepted(
                  response.values,
                  response.terminal,
                  response.fetchedValues
                )
              ),
              candidate
            )
  {
    var sourceEid :| 0 <= sourceEid &&
                     EidAboveBound(
                       sourceEid,
                       Indexed.ProjectionBound(
                         pending.command.projection
                       )
                     ) &&
                     Refinement.RelationshipProjectsTo(
                       objectBindings,
                       relationBindings,
                       relationships,
                       pending.command.projection,
                       sourceEid
                     ) &&
                     ForwardContinuationCovers(
                       objectBindings,
                       relationBindings,
                       relationships,
                       pending.continuation,
                       sourceEid,
                       candidate
                     );
    assert sourceEid in fullProjectionValues;
    ValuesAfterBoundKeepsAboveMember(
      fullProjectionValues,
      Indexed.ProjectionBound(pending.command.projection),
      sourceEid
    );
    var remaining :=
      Refinement.ValuesAfterBound(
        fullProjectionValues,
        Indexed.ProjectionBound(pending.command.projection)
      );
    assert response.values ==
           Refinement.ChunkPrefix(
             remaining,
             pending.command.chunkSize
           );
    assert 0 < |response.values|;
    ForwardResponseWorkCoversSource(
      objectBindings,
      relationBindings,
      relationships,
      pending.command,
      response,
      fullProjectionValues,
      pending.continuation,
      sourceEid,
      candidate
    );
  }

  lemma ForwardCertifiedResumePreservesCoverage(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string,
    subjectEid: int,
    before: Indexed.ForwardState,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>,
    limits: Indexed.IndexedLimits,
    outcome: Indexed.ForwardResume
  )
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires Indexed.ForwardStateInvariant(before)
    requires Indexed.CountersWithinLimits(before.counters, limits)
    requires before.pending.AwaitingForwardScan?
    requires ForwardCoverageInvariant(
               objectBindings,
               relationBindings,
               relationships,
               seedRules,
               subjectType,
               subjectEid,
               before
             )
    requires Refinement.ExactProjectionValues(
               objectBindings,
               relationBindings,
               relationships,
               before.pending.command.projection,
               fullProjectionValues
             )
    requires Refinement.ExactScanResponse(
               before.pending.command,
               response,
               fullProjectionValues
             )
    requires outcome ==
             Indexed.ForwardResumeSpec(
               before,
               response,
               limits
             )
    ensures outcome.ForwardScanResumed? ==>
              ForwardCoverageInvariant(
                objectBindings,
                relationBindings,
                relationships,
                seedRules,
                subjectType,
                subjectEid,
                outcome.state
              )
    ensures outcome.ForwardScanLimitExceeded? ==>
              ForwardCoverageInvariant(
                objectBindings,
                relationBindings,
                relationships,
                seedRules,
                subjectType,
                subjectEid,
                outcome.state
              )
  {
    assert Indexed.ForwardResumeRelation(
        before,
        response,
        outcome
      );
    match outcome
    case ForwardScanRejected(_) => {
      assert Indexed.ValidScanResponse(
          before.pending.command,
          response
        );
      assert false;
    }
    case ForwardScanLimitExceeded(_, after) => {
      assert after == before;
    }
    case ForwardScanResumed(after) => {
      assert Indexed.ForwardStateInvariant(after);
      assert after.consumers == before.consumers;
      assert after.seen == before.seen;
      forall candidate: Indexed.ForwardGrantKey |
        ForwardQueueCovers(
          objectBindings,
          relationBindings,
          relationships,
          before.queue,
          candidate
        )
        ensures candidate in after.seen ||
                ForwardQueueCovers(
                  objectBindings,
                  relationBindings,
                  relationships,
                  after.queue,
                  candidate
                ) ||
                PendingForwardCovers(
                  objectBindings,
                  relationBindings,
                  relationships,
                  after.pending,
                  candidate
                )
      {
        if |response.values| == 0 {
          assert after.queue == before.queue;
        } else {
          var work :=
            Indexed.ForwardWorkAfterResponse(
              before.pending.command,
              before.pending.continuation,
              Indexed.ScanAccepted(
                response.values,
                response.terminal,
                response.fetchedValues
              )
            );
          LeftOrRightCoverImpliesConcatenatedCover(
            objectBindings,
            relationBindings,
            relationships,
            before.queue,
            work,
            candidate
          );
        }
      }
      forall candidate: Indexed.ForwardGrantKey |
        PendingForwardCovers(
          objectBindings,
          relationBindings,
          relationships,
          before.pending,
          candidate
        )
        ensures candidate in after.seen ||
                ForwardQueueCovers(
                  objectBindings,
                  relationBindings,
                  relationships,
                  after.queue,
                  candidate
                ) ||
                PendingForwardCovers(
                  objectBindings,
                  relationBindings,
                  relationships,
                  after.pending,
                  candidate
                )
      {
        CertifiedResponseCoversPendingForwardObligation(
          objectBindings,
          relationBindings,
          relationships,
          before.pending,
          response,
          fullProjectionValues,
          candidate
        );
        var work :=
          Indexed.ForwardWorkAfterResponse(
            before.pending.command,
            before.pending.continuation,
            Indexed.ScanAccepted(
              response.values,
              response.terminal,
              response.fetchedValues
            )
          );
        assert after.queue == before.queue + work;
        LeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          before.queue,
          work,
          candidate
        );
      }
      forall source: Indexed.ForwardGrantKey,
        candidate: Indexed.ForwardGrantKey |
        source in after.seen &&
        source !in before.seen &&
        ForwardSuccessorGrant(
          objectBindings,
          relationBindings,
          relationships,
          after.consumers,
          source,
          candidate
        )
        ensures candidate in after.seen ||
                ForwardQueueCovers(
                  objectBindings,
                  relationBindings,
                  relationships,
                  after.queue,
                  candidate
                ) ||
                PendingForwardCovers(
                  objectBindings,
                  relationBindings,
                  relationships,
                  after.pending,
                  candidate
                )
      {
        assert false;
      }
      ForwardCoverageTransport(
        objectBindings,
        relationBindings,
        relationships,
        seedRules,
        subjectType,
        subjectEid,
        before,
        after
      );
    }
  }

  lemma {:isolate_assertions}
    DirectSubjectDerivationIsSeen(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    semanticSubject: Semantics.ObjectRef,
    subjectEid: int,
    state: Indexed.ForwardState,
    premiseGrants: set<Semantics.Grant>,
    normalizedRule: Semantics.NormalizedRule,
    semanticGrant: Semantics.Grant
  )
    requires Refinement.ExactObjectCatalog(
               objects,
               objectBindings
             )
    requires Refinement.ExactRelationCatalog(
               relations,
               relationBindings
             )
    requires Refinement.ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires AllRuleRelationsBound(
               normalizedRules,
               relationBindings
             )
    requires Refinement.ExactForwardSeedBucket(
               indexedRules,
               seedRules,
               semanticSubject.typeName
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires exists binding <- objectBindings ::
               binding.eid == subjectEid &&
               binding.objectRef == semanticSubject
    requires Indexed.ForwardStateInvariant(state)
    requires state.consumers ==
             Indexed.ForwardConsumers(indexedRules)
    requires ForwardSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               seedRules,
               semanticSubject.typeName,
               subjectEid,
               state
             )
    requires normalizedRule in normalizedRules
    requires normalizedRule.DirectRelationRule?
    requires semanticGrant in
               Semantics.GrantUniverse(objects, permissions)
    requires semanticGrant.subject == semanticSubject
    requires Semantics.RuleDerives(
               normalizedRule,
               relationships,
               premiseGrants,
               semanticGrant
             )
    ensures semanticGrant in
              SeenSubjectGrants(
                objectBindings,
                semanticSubject,
                state.seen
              )
  {
    GrantUniverseMembershipHasCatalogShape(
      objects,
      permissions,
      semanticGrant
    );
    var subjectBinding :| subjectBinding in objectBindings &&
                          subjectBinding.objectRef ==
                          semanticSubject;
    var resourceBinding :| resourceBinding in objectBindings &&
                           resourceBinding.objectRef ==
                           semanticGrant.resource;
    var querySubjectBinding :|
      querySubjectBinding in objectBindings &&
      querySubjectBinding.eid == subjectEid &&
      querySubjectBinding.objectRef == semanticSubject;
    Refinement.UniqueObjectEidIdentifiesBinding(
      objectBindings,
      subjectBinding,
      querySubjectBinding
    );
    var relationshipIndex :|
      0 <= relationshipIndex < |relationships| &&
      relationships[relationshipIndex] ==
      Semantics.Relationship(
        semanticGrant.resource,
        normalizedRule.relationName,
        semanticSubject
      );
    var relationship := relationships[relationshipIndex];
    var relationBinding :|
      relationBinding in relationBindings &&
      Refinement.RelationBindingMatches(
        relationBinding,
        normalizedRule.head.resourceType,
        normalizedRule.relationName,
        normalizedRule.subjectType
      );
    var indexedRule :=
      Indexed.RelationRule(
        normalizedRule.head,
        relationBinding.eid,
        normalizedRule.subjectType
      );
    assert indexedRule in indexedRules;
    assert Refinement.EligibleForwardSeed(
        indexedRule,
        semanticSubject.typeName
      );
    assert indexedRule in seedRules;
    var indexedGrant :=
      Indexed.ForwardGrantKey(
        normalizedRule.head,
        resourceBinding.eid
      );
    var projection :=
      Indexed.SubjectToResources(
        semanticSubject.typeName,
        subjectEid,
        relationBinding.eid,
        normalizedRule.head.resourceType,
        Indexed.NoBound
      );
    assert Refinement.RelationshipProjectsTo(
        objectBindings,
        relationBindings,
        relationships,
        projection,
        resourceBinding.eid
      );
    assert ForwardContinuationCovers(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ForwardGrant(normalizedRule.head),
        resourceBinding.eid,
        indexedGrant
      );
    var stream :=
      Indexed.ForwardStream(
        projection,
        [],
        true,
        Indexed.ForwardGrant(normalizedRule.head)
      );
    assert ForwardStreamCovers(
        objectBindings,
        relationBindings,
        relationships,
        stream,
        indexedGrant
      );
    assert Indexed.ForwardSeedWork(
        indexedRule,
        semanticSubject.typeName,
        subjectEid
      ) == [Indexed.ForwardStreamWork(stream)];
    assert ForwardWorkCovers(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ForwardStreamWork(stream),
        indexedGrant
      );
    assert ForwardQueueCovers(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ForwardSeedWork(
          indexedRule,
          semanticSubject.typeName,
          subjectEid
        ),
        indexedGrant
      );
    SeedRuleCoverIsIncluded(
      objectBindings,
      relationBindings,
      relationships,
      seedRules,
      semanticSubject.typeName,
      subjectEid,
      indexedRule,
      indexedGrant
    );
    assert ForwardSeedGrant(
        objectBindings,
        relationBindings,
        relationships,
        seedRules,
        semanticSubject.typeName,
        subjectEid,
        indexedGrant
      );
    assert indexedGrant in state.seen;
  }

  lemma {:isolate_assertions}
    SelfPermissionSubjectDerivationIsSeen(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    semanticSubject: Semantics.ObjectRef,
    subjectEid: int,
    state: Indexed.ForwardState,
    normalizedRule: Semantics.NormalizedRule,
    semanticGrant: Semantics.Grant
  )
    requires Refinement.ExactObjectCatalog(
               objects,
               objectBindings
             )
    requires Refinement.ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires Indexed.ForwardStateInvariant(state)
    requires state.consumers ==
             Indexed.ForwardConsumers(indexedRules)
    requires ForwardSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               seedRules,
               semanticSubject.typeName,
               subjectEid,
               state
             )
    requires normalizedRule in normalizedRules
    requires normalizedRule.SelfPermissionRule?
    requires semanticGrant in
               Semantics.GrantUniverse(objects, permissions)
    requires semanticGrant.subject == semanticSubject
    requires Semantics.RuleDerives(
               normalizedRule,
               relationships,
               SeenSubjectGrants(
                 objectBindings,
                 semanticSubject,
                 state.seen
               ),
               semanticGrant
             )
    ensures semanticGrant in
              SeenSubjectGrants(
                objectBindings,
                semanticSubject,
                state.seen
              )
  {
    GrantUniverseMembershipHasCatalogShape(
      objects,
      permissions,
      semanticGrant
    );
    var targetNode :=
      Semantics.PermissionNode(
        normalizedRule.head.resourceType,
        normalizedRule.sourcePermission
      );
    var premise :=
      Semantics.Grant(
        semanticSubject,
        targetNode,
        semanticGrant.resource
      );
    assert premise in SeenSubjectGrants(
                        objectBindings,
                        semanticSubject,
                        state.seen
                      );
    var source,
        resourceBinding :|
      source in state.seen &&
      resourceBinding in objectBindings &&
      resourceBinding.eid == source.resourceEid &&
      resourceBinding.objectRef.typeName ==
      source.node.resourceType &&
      premise ==
      Semantics.Grant(
        semanticSubject,
        source.node,
        resourceBinding.objectRef
      );
    assert source.node == targetNode;
    var indexedRule :=
      Indexed.SelfPermissionRule(
        normalizedRule.head,
        targetNode
      );
    assert indexedRule in indexedRules;
    PermissionRuleAppearsInForwardConsumers(
      indexedRules,
      indexedRule
    );
    var consumerRules :=
      state.consumers[targetNode];
    assert indexedRule in consumerRules;
    var candidate :=
      Indexed.ForwardGrantKey(
        normalizedRule.head,
        source.resourceEid
      );
    assert Indexed.ForwardConsumerWork(
        indexedRule,
        source
      ) == [Indexed.ForwardGrantWork(candidate)];
    assert ForwardWorkCovers(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ForwardGrantWork(candidate),
        candidate
      );
    assert ForwardQueueCovers(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ForwardConsumerWork(indexedRule, source),
        candidate
      );
    ConsumerRuleCoverIsIncluded(
      objectBindings,
      relationBindings,
      relationships,
      consumerRules,
      source,
      indexedRule,
      candidate
    );
    assert ForwardSuccessorGrant(
        objectBindings,
        relationBindings,
        relationships,
        state.consumers,
        source,
        candidate
      );
    ClosedSuccessorIsSeen(
      objectBindings,
      relationBindings,
      relationships,
      seedRules,
      semanticSubject.typeName,
      subjectEid,
      state,
      source,
      candidate
    );
  }

  lemma {:isolate_assertions}
    ArrowRelationSubjectDerivationIsSeen(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    semanticSubject: Semantics.ObjectRef,
    subjectEid: int,
    state: Indexed.ForwardState,
    premiseGrants: set<Semantics.Grant>,
    normalizedRule: Semantics.NormalizedRule,
    semanticGrant: Semantics.Grant
  )
    requires Refinement.ExactObjectCatalog(
               objects,
               objectBindings
             )
    requires Refinement.ExactRelationCatalog(
               relations,
               relationBindings
             )
    requires Refinement.ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires AllRuleRelationsBound(
               normalizedRules,
               relationBindings
             )
    requires RelationshipsWellTyped(
               relationBindings,
               relationships
             )
    requires RelationshipEndpointsCataloged(
               objects,
               relationships
             )
    requires Refinement.ExactForwardSeedBucket(
               indexedRules,
               seedRules,
               semanticSubject.typeName
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires exists binding <- objectBindings ::
               binding.eid == subjectEid &&
               binding.objectRef == semanticSubject
    requires Indexed.ForwardStateInvariant(state)
    requires state.consumers ==
             Indexed.ForwardConsumers(indexedRules)
    requires ForwardSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               seedRules,
               semanticSubject.typeName,
               subjectEid,
               state
             )
    requires normalizedRule in normalizedRules
    requires normalizedRule.ArrowRelationRule?
    requires semanticGrant in
               Semantics.GrantUniverse(objects, permissions)
    requires semanticGrant.subject == semanticSubject
    requires Semantics.RuleDerives(
               normalizedRule,
               relationships,
               premiseGrants,
               semanticGrant
             )
    ensures semanticGrant in
              SeenSubjectGrants(
                objectBindings,
                semanticSubject,
                state.seen
              )
  {
    GrantUniverseMembershipHasCatalogShape(
      objects,
      permissions,
      semanticGrant
    );
    var subjectBinding :|
      subjectBinding in objectBindings &&
      subjectBinding.eid == subjectEid &&
      subjectBinding.objectRef == semanticSubject;
    var resourceBinding :=
      ObjectCatalogBinding(
        objects,
        objectBindings,
        semanticGrant.resource
      );
    var viaRelationship :|
      viaRelationship in relationships &&
      viaRelationship.resource == semanticGrant.resource &&
      viaRelationship.relationName ==
      normalizedRule.viaRelation &&
      Semantics.HasRelationship(
        relationships,
        viaRelationship.subject,
        normalizedRule.targetRelation,
        semanticSubject
      );
    assert viaRelationship.subject in objects;
    var intermediateBinding :=
      ObjectCatalogBinding(
        objects,
        objectBindings,
        viaRelationship.subject
      );
    var targetRelationshipIndex :|
      0 <= targetRelationshipIndex < |relationships| &&
      relationships[targetRelationshipIndex] ==
      Semantics.Relationship(
        viaRelationship.subject,
        normalizedRule.targetRelation,
        semanticSubject
      );
    var targetRelationship :=
      relationships[targetRelationshipIndex];
    var viaBinding :=
      TypedRelationshipBinding(
        relationBindings,
        relationships,
        viaRelationship
      );
    var targetBinding :=
      TypedRelationshipBinding(
        relationBindings,
        relationships,
        targetRelationship
      );
    var indexedRule :=
      Indexed.ArrowRelationRule(
        normalizedRule.head,
        viaBinding.eid,
        viaRelationship.subject.typeName,
        targetBinding.eid,
        normalizedRule.subjectType
      );
    assert semanticSubject.typeName ==
           normalizedRule.subjectType;
    assert indexedRule in indexedRules;
    assert Refinement.EligibleForwardSeed(
        indexedRule,
        semanticSubject.typeName
      );
    assert indexedRule in seedRules;
    var candidate :=
      Indexed.ForwardGrantKey(
        normalizedRule.head,
        resourceBinding.eid
      );
    var targetProjection :=
      Indexed.SubjectToResources(
        semanticSubject.typeName,
        subjectEid,
        targetBinding.eid,
        viaRelationship.subject.typeName,
        Indexed.NoBound
      );
    assert Refinement.RelationshipProjectsTo(
        objectBindings,
        relationBindings,
        relationships,
        targetProjection,
        intermediateBinding.eid
      );
    assert Refinement.RelationshipProjectsTo(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.SubjectToResources(
          viaRelationship.subject.typeName,
          intermediateBinding.eid,
          viaBinding.eid,
          normalizedRule.head.resourceType,
          Indexed.NoBound
        ),
        resourceBinding.eid
      );
    ArrowRelationSeedWorkCovers(
      objectBindings,
      relationBindings,
      relationships,
      indexedRule,
      semanticSubject.typeName,
      subjectEid,
      intermediateBinding.eid,
      resourceBinding.eid
    );
    SeedRuleCoverIsIncluded(
      objectBindings,
      relationBindings,
      relationships,
      seedRules,
      semanticSubject.typeName,
      subjectEid,
      indexedRule,
      candidate
    );
    assert ForwardSeedGrant(
        objectBindings,
        relationBindings,
        relationships,
        seedRules,
        semanticSubject.typeName,
        subjectEid,
        candidate
      );
    assert candidate in state.seen;
  }

  lemma {:isolate_assertions}
    ArrowPermissionSubjectDerivationIsSeen(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    semanticSubject: Semantics.ObjectRef,
    subjectEid: int,
    state: Indexed.ForwardState,
    normalizedRule: Semantics.NormalizedRule,
    semanticGrant: Semantics.Grant
  )
    requires Refinement.ExactObjectCatalog(
               objects,
               objectBindings
             )
    requires Refinement.ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires AllRuleRelationsBound(
               normalizedRules,
               relationBindings
             )
    requires RelationshipsWellTyped(
               relationBindings,
               relationships
             )
    requires RelationshipEndpointsCataloged(
               objects,
               relationships
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires Indexed.ForwardStateInvariant(state)
    requires state.consumers ==
             Indexed.ForwardConsumers(indexedRules)
    requires ForwardSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               seedRules,
               semanticSubject.typeName,
               subjectEid,
               state
             )
    requires normalizedRule in normalizedRules
    requires normalizedRule.ArrowPermissionRule?
    requires semanticGrant in
               Semantics.GrantUniverse(objects, permissions)
    requires semanticGrant.subject == semanticSubject
    requires Semantics.RuleDerives(
               normalizedRule,
               relationships,
               SeenSubjectGrants(
                 objectBindings,
                 semanticSubject,
                 state.seen
               ),
               semanticGrant
             )
    ensures semanticGrant in
              SeenSubjectGrants(
                objectBindings,
                semanticSubject,
                state.seen
              )
  {
    GrantUniverseMembershipHasCatalogShape(
      objects,
      permissions,
      semanticGrant
    );
    var resourceBinding :=
      ObjectCatalogBinding(
        objects,
        objectBindings,
        semanticGrant.resource
      );
    var viaRelationship :|
      viaRelationship in relationships &&
      viaRelationship.resource == semanticGrant.resource &&
      viaRelationship.relationName ==
      normalizedRule.viaRelation &&
      Semantics.Grant(
        semanticSubject,
        Semantics.PermissionNode(
          viaRelationship.subject.typeName,
          normalizedRule.targetPermission
        ),
        viaRelationship.subject
      ) in SeenSubjectGrants(
             objectBindings,
             semanticSubject,
             state.seen
           );
    var intermediateBinding :=
      ObjectCatalogBinding(
        objects,
        objectBindings,
        viaRelationship.subject
      );
    var targetNode :=
      Semantics.PermissionNode(
        viaRelationship.subject.typeName,
        normalizedRule.targetPermission
      );
    var premise :=
      Semantics.Grant(
        semanticSubject,
        targetNode,
        viaRelationship.subject
      );
    var source,
        seenIntermediateBinding :|
      source in state.seen &&
      seenIntermediateBinding in objectBindings &&
      seenIntermediateBinding.eid == source.resourceEid &&
      seenIntermediateBinding.objectRef.typeName ==
      source.node.resourceType &&
      premise ==
      Semantics.Grant(
        semanticSubject,
        source.node,
        seenIntermediateBinding.objectRef
      );
    UniqueObjectRefIdentifiesBinding(
      objectBindings,
      intermediateBinding,
      seenIntermediateBinding
    );
    assert source.node == targetNode;
    assert source.resourceEid == intermediateBinding.eid;
    var viaBinding :=
      TypedRelationshipBinding(
        relationBindings,
        relationships,
        viaRelationship
      );
    var indexedRule :=
      Indexed.ArrowPermissionRule(
        normalizedRule.head,
        viaBinding.eid,
        viaRelationship.subject.typeName,
        targetNode
      );
    assert indexedRule in indexedRules;
    PermissionRuleAppearsInForwardConsumers(
      indexedRules,
      indexedRule
    );
    var consumerRules := state.consumers[targetNode];
    assert indexedRule in consumerRules;
    assert Refinement.RelationshipProjectsTo(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.SubjectToResources(
          viaRelationship.subject.typeName,
          source.resourceEid,
          viaBinding.eid,
          normalizedRule.head.resourceType,
          Indexed.NoBound
        ),
        resourceBinding.eid
      );
    ArrowPermissionConsumerWorkCovers(
      objectBindings,
      relationBindings,
      relationships,
      indexedRule,
      source,
      resourceBinding.eid
    );
    var candidate :=
      Indexed.ForwardGrantKey(
        normalizedRule.head,
        resourceBinding.eid
      );
    ConsumerRuleCoverIsIncluded(
      objectBindings,
      relationBindings,
      relationships,
      consumerRules,
      source,
      indexedRule,
      candidate
    );
    assert ForwardSuccessorGrant(
        objectBindings,
        relationBindings,
        relationships,
        state.consumers,
        source,
        candidate
      );
    ClosedSuccessorIsSeen(
      objectBindings,
      relationBindings,
      relationships,
      seedRules,
      semanticSubject.typeName,
      subjectEid,
      state,
      source,
      candidate
    );
  }

  lemma SubjectRuleDerivationFromReplacementUsesSeen(
    semanticSubject: Semantics.ObjectRef,
    seenSubjectGrants: set<Semantics.Grant>,
    grants: set<Semantics.Grant>,
    normalizedRule: Semantics.NormalizedRule,
    relationships: seq<Semantics.Relationship>,
    semanticGrant: Semantics.Grant
  )
    requires semanticGrant.subject == semanticSubject
    requires Semantics.RuleDerives(
               normalizedRule,
               relationships,
               ReplaceSubjectGrants(
                 grants,
                 semanticSubject,
                 seenSubjectGrants
               ),
               semanticGrant
             )
    ensures Semantics.RuleDerives(
              normalizedRule,
              relationships,
              seenSubjectGrants,
              semanticGrant
            )
  {
    match normalizedRule
    case DirectRelationRule(_, _, _) => {
    }
    case ArrowRelationRule(_, _, _, _) => {
    }
    case SelfPermissionRule(head, sourcePermission) => {
      var premise :=
        Semantics.Grant(
          semanticSubject,
          Semantics.PermissionNode(
            head.resourceType,
            sourcePermission
          ),
          semanticGrant.resource
        );
      assert premise in ReplaceSubjectGrants(
                          grants,
                          semanticSubject,
                          seenSubjectGrants
                        );
      assert premise in seenSubjectGrants;
    }
    case ArrowPermissionRule(_, _, targetPermission) => {
      var via :| via in relationships &&
                 via.resource == semanticGrant.resource &&
                 via.relationName ==
                 normalizedRule.viaRelation &&
                 Semantics.Grant(
                   semanticSubject,
                   Semantics.PermissionNode(
                     via.subject.typeName,
                     targetPermission
                   ),
                   via.subject
                 ) in ReplaceSubjectGrants(
                        grants,
                        semanticSubject,
                        seenSubjectGrants
                      );
      var premise :=
        Semantics.Grant(
          semanticSubject,
          Semantics.PermissionNode(
            via.subject.typeName,
            targetPermission
          ),
          via.subject
        );
      assert premise in seenSubjectGrants;
    }
  }

  lemma SubjectImmediateDerivationIsSeen(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    semanticSubject: Semantics.ObjectRef,
    subjectEid: int,
    state: Indexed.ForwardState,
    premiseGrants: set<Semantics.Grant>,
    semanticGrant: Semantics.Grant
  )
    requires Refinement.ExactObjectCatalog(
               objects,
               objectBindings
             )
    requires Refinement.ExactRelationCatalog(
               relations,
               relationBindings
             )
    requires Refinement.ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires AllRuleRelationsBound(
               normalizedRules,
               relationBindings
             )
    requires RelationshipsWellTyped(
               relationBindings,
               relationships
             )
    requires RelationshipEndpointsCataloged(
               objects,
               relationships
             )
    requires Refinement.ExactForwardSeedBucket(
               indexedRules,
               seedRules,
               semanticSubject.typeName
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires exists binding <- objectBindings ::
               binding.eid == subjectEid &&
               binding.objectRef == semanticSubject
    requires Indexed.ForwardStateInvariant(state)
    requires state.consumers ==
             Indexed.ForwardConsumers(indexedRules)
    requires ForwardSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               seedRules,
               semanticSubject.typeName,
               subjectEid,
               state
             )
    requires semanticGrant in
               Semantics.GrantUniverse(objects, permissions)
    requires semanticGrant.subject == semanticSubject
    requires premiseGrants ==
             SeenSubjectGrants(
               objectBindings,
               semanticSubject,
               state.seen
             )
    requires Semantics.AnyRuleDerives(
               normalizedRules,
               relationships,
               premiseGrants,
               semanticGrant
             )
    ensures semanticGrant in
              SeenSubjectGrants(
                objectBindings,
                semanticSubject,
                state.seen
              )
  {
    var ruleIndex :|
      0 <= ruleIndex < |normalizedRules| &&
      Semantics.RuleDerives(
        normalizedRules[ruleIndex],
        relationships,
        premiseGrants,
        semanticGrant
      );
    var normalizedRule := normalizedRules[ruleIndex];
    match normalizedRule
    case DirectRelationRule(_, _, _) => {
      DirectSubjectDerivationIsSeen(
        objects,
        relations,
        permissions,
        normalizedRules,
        indexedRules,
        seedRules,
        relationships,
        objectBindings,
        relationBindings,
        semanticSubject,
        subjectEid,
        state,
        premiseGrants,
        normalizedRule,
        semanticGrant
      );
    }
    case SelfPermissionRule(_, _) => {
      SelfPermissionSubjectDerivationIsSeen(
        objects,
        permissions,
        normalizedRules,
        indexedRules,
        seedRules,
        relationships,
        objectBindings,
        relationBindings,
        semanticSubject,
        subjectEid,
        state,
        normalizedRule,
        semanticGrant
      );
    }
    case ArrowRelationRule(_, _, _, _) => {
      ArrowRelationSubjectDerivationIsSeen(
        objects,
        relations,
        permissions,
        normalizedRules,
        indexedRules,
        seedRules,
        relationships,
        objectBindings,
        relationBindings,
        semanticSubject,
        subjectEid,
        state,
        premiseGrants,
        normalizedRule,
        semanticGrant
      );
    }
    case ArrowPermissionRule(_, _, _) => {
      ArrowPermissionSubjectDerivationIsSeen(
        objects,
        permissions,
        normalizedRules,
        indexedRules,
        seedRules,
        relationships,
        objectBindings,
        relationBindings,
        semanticSubject,
        subjectEid,
        state,
        normalizedRule,
        semanticGrant
      );
    }
  }

  lemma ReplacementIsClosedUnderImmediateConsequences(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    semanticSubject: Semantics.ObjectRef,
    subjectEid: int,
    grants: set<Semantics.Grant>,
    state: Indexed.ForwardState
  )
    requires Refinement.ExactObjectCatalog(
               objects,
               objectBindings
             )
    requires Refinement.ExactRelationCatalog(
               relations,
               relationBindings
             )
    requires Refinement.ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires AllRuleRelationsBound(
               normalizedRules,
               relationBindings
             )
    requires RelationshipsWellTyped(
               relationBindings,
               relationships
             )
    requires RelationshipEndpointsCataloged(
               objects,
               relationships
             )
    requires Refinement.ExactForwardSeedBucket(
               indexedRules,
               seedRules,
               semanticSubject.typeName
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires exists binding <- objectBindings ::
               binding.eid == subjectEid &&
               binding.objectRef == semanticSubject
    requires Indexed.ForwardStateInvariant(state)
    requires state.consumers ==
             Indexed.ForwardConsumers(indexedRules)
    requires ForwardSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               seedRules,
               semanticSubject.typeName,
               subjectEid,
               state
             )
    requires NormalizedLeastFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires Refinement.ForwardStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               indexedRules,
               state
             )
    ensures Semantics.ImmediateConsequences(
              objects,
              permissions,
              normalizedRules,
              relationships,
              ReplaceSubjectGrants(
                grants,
                semanticSubject,
                SeenSubjectGrants(
                  objectBindings,
                  semanticSubject,
                  state.seen
                )
              )
            ) <=
            ReplaceSubjectGrants(
              grants,
              semanticSubject,
              SeenSubjectGrants(
                objectBindings,
                semanticSubject,
                state.seen
              )
            )
  {
    var seenSubjectGrants :=
      SeenSubjectGrants(
        objectBindings,
        semanticSubject,
        state.seen
      );
    var replacement :=
      ReplaceSubjectGrants(
        grants,
        semanticSubject,
        seenSubjectGrants
      );
    SeenSubjectGrantsAreSound(
      objectBindings,
      relationBindings,
      relationships,
      semanticSubject,
      grants,
      indexedRules,
      state
    );
    assert seenSubjectGrants <= grants;
    assert replacement <= grants;
    forall semanticGrant |
      semanticGrant in Semantics.ImmediateConsequences(
                         objects,
                         permissions,
                         normalizedRules,
                         relationships,
                         replacement
                       )
      ensures semanticGrant in replacement
    {
      if semanticGrant !in replacement {
        assert semanticGrant in
                 Semantics.GrantUniverse(objects, permissions);
        var ruleIndex :|
          0 <= ruleIndex < |normalizedRules| &&
          Semantics.RuleDerives(
            normalizedRules[ruleIndex],
            relationships,
            replacement,
            semanticGrant
          );
        var normalizedRule := normalizedRules[ruleIndex];
        if semanticGrant.subject != semanticSubject {
          Semantics.RuleDerivationIsMonotone(
            normalizedRule,
            relationships,
            replacement,
            grants,
            semanticGrant
          );
          assert Semantics.AnyRuleDerives(
              normalizedRules,
              relationships,
              grants,
              semanticGrant
            );
          assert semanticGrant in Semantics.ImmediateConsequences(
                                    objects,
                                    permissions,
                                    normalizedRules,
                                    relationships,
                                    grants
                                  );
          assert semanticGrant in grants;
          assert semanticGrant in replacement;
        } else {
          SubjectRuleDerivationFromReplacementUsesSeen(
            semanticSubject,
            seenSubjectGrants,
            grants,
            normalizedRule,
            relationships,
            semanticGrant
          );
          assert Semantics.AnyRuleDerives(
              normalizedRules,
              relationships,
              seenSubjectGrants,
              semanticGrant
            );
          SubjectImmediateDerivationIsSeen(
            objects,
            relations,
            permissions,
            normalizedRules,
            indexedRules,
            seedRules,
            relationships,
            objectBindings,
            relationBindings,
            semanticSubject,
            subjectEid,
            state,
            seenSubjectGrants,
            semanticGrant
          );
          assert semanticGrant in seenSubjectGrants;
          assert semanticGrant in replacement;
        }
      }
    }
  }

  lemma ExhaustedForwardTraversalIsComplete(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    semanticSubject: Semantics.ObjectRef,
    subjectEid: int,
    grants: set<Semantics.Grant>,
    state: Indexed.ForwardState
  )
    requires Refinement.ExactObjectCatalog(
               objects,
               objectBindings
             )
    requires Refinement.ExactRelationCatalog(
               relations,
               relationBindings
             )
    requires Refinement.ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires AllRuleRelationsBound(
               normalizedRules,
               relationBindings
             )
    requires RelationshipsWellTyped(
               relationBindings,
               relationships
             )
    requires RelationshipEndpointsCataloged(
               objects,
               relationships
             )
    requires Refinement.ExactForwardSeedBucket(
               indexedRules,
               seedRules,
               semanticSubject.typeName
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires exists binding <- objectBindings ::
               binding.eid == subjectEid &&
               binding.objectRef == semanticSubject
    requires Indexed.ForwardStateInvariant(state)
    requires state.consumers ==
             Indexed.ForwardConsumers(indexedRules)
    requires ForwardSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               seedRules,
               semanticSubject.typeName,
               subjectEid,
               state
             )
    requires NormalizedLeastFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires Refinement.ForwardStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               indexedRules,
               state
             )
    ensures forall semanticGrant <- grants ::
              semanticGrant.subject == semanticSubject ==>
                semanticGrant in SeenSubjectGrants(
                                   objectBindings,
                                   semanticSubject,
                                   state.seen
                                 )
  {
    var seenSubjectGrants :=
      SeenSubjectGrants(
        objectBindings,
        semanticSubject,
        state.seen
      );
    var replacement :=
      ReplaceSubjectGrants(
        grants,
        semanticSubject,
        seenSubjectGrants
      );
    SeenSubjectGrantsAreSound(
      objectBindings,
      relationBindings,
      relationships,
      semanticSubject,
      grants,
      indexedRules,
      state
    );
    assert seenSubjectGrants <= grants;
    assert replacement <= grants;
    assert replacement <=
           Semantics.GrantUniverse(objects, permissions);
    ReplacementIsClosedUnderImmediateConsequences(
      objects,
      relations,
      permissions,
      normalizedRules,
      indexedRules,
      seedRules,
      relationships,
      objectBindings,
      relationBindings,
      semanticSubject,
      subjectEid,
      grants,
      state
    );
    assert replacement <= Semantics.ImmediateConsequences(
                            objects,
                            permissions,
                            normalizedRules,
                            relationships,
                            replacement
                          );
    assert Semantics.ImmediateConsequences(
        objects,
        permissions,
        normalizedRules,
        relationships,
        replacement
      ) == replacement;
    assert grants <= replacement;
    forall semanticGrant <- grants |
           semanticGrant.subject == semanticSubject
      ensures semanticGrant in seenSubjectGrants
    {
      assert semanticGrant in replacement;
    }
  }

  lemma ExhaustedCoverageIsClosed(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string,
    subjectEid: int,
    state: Indexed.ForwardState
  )
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires Indexed.ForwardStateInvariant(state)
    requires ForwardCoverageInvariant(
               objectBindings,
               relationBindings,
               relationships,
               seedRules,
               subjectType,
               subjectEid,
               state
             )
    requires state.queue == []
    requires state.pending.NoForwardPending?
    ensures ForwardSeenClosed(
              objectBindings,
              relationBindings,
              relationships,
              seedRules,
              subjectType,
              subjectEid,
              state
            )
    ensures forall candidate: Indexed.ForwardGrantKey ::
              (
                ForwardSeedGrant(
                  objectBindings,
                  relationBindings,
                  relationships,
                  seedRules,
                  subjectType,
                  subjectEid,
                  candidate
                ) ||
                exists source <- state.seen ::
                  ForwardSuccessorGrant(
                    objectBindings,
                    relationBindings,
                    relationships,
                    state.consumers,
                    source,
                    candidate
                  )
              )
              ==> candidate in state.seen
  {
    forall candidate: Indexed.ForwardGrantKey |
      ForwardSeedGrant(
        objectBindings,
        relationBindings,
        relationships,
        seedRules,
        subjectType,
        subjectEid,
        candidate
      ) ||
      exists source <- state.seen ::
        ForwardSuccessorGrant(
          objectBindings,
          relationBindings,
          relationships,
          state.consumers,
          source,
          candidate
        )
      ensures candidate in state.seen
    {
      EmptyQueueCoversNothing(
        objectBindings,
        relationBindings,
        relationships,
        candidate
      );
      NoPendingScanCoversNothing(
        objectBindings,
        relationBindings,
        relationships,
        candidate
      );
    }
  }

  method InitializeForwardCompleteRefined(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
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
    requires Refinement.ExactObjectCatalog(
               objects,
               objectBindings
             )
    requires Refinement.ExactRelationCatalog(
               relations,
               relationBindings
             )
    requires NormalizedLeastFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires Refinement.ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ValidIndexedRules(seedRules)
    requires forall rule <- seedRules :: rule in indexedRules
    requires Refinement.ExactForwardSeedBucket(
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
    ensures outcome.ForwardInitialized? ==>
              Indexed.ForwardStateInvariant(outcome.state) &&
              Indexed.CountersWithinLimits(
                outcome.state.counters,
                limits
              ) &&
              outcome.state.consumers ==
              Indexed.ForwardConsumers(indexedRules) &&
              Refinement.ForwardStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                semanticSubject,
                grants,
                indexedRules,
                outcome.state
              ) &&
              ForwardCoverageInvariant(
                objectBindings,
                relationBindings,
                relationships,
                seedRules,
                subjectType,
                subjectEid,
                outcome.state
              )
  {
    outcome :=
      Refinement.InitializeForwardRefined(
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
        limits
      );
    if outcome.ForwardInitialized? {
      ForwardInitializationEstablishesCoverage(
        objectBindings,
        relationBindings,
        relationships,
        seedRules,
        subjectType,
        subjectEid,
        outcome.state
      );
    }
  }

  method {:isolate_assertions} StepForwardCompleteRefined(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    grants: set<Semantics.Grant>,
    subjectType: string,
    subjectEid: int,
    semanticSubject: Semantics.ObjectRef,
    state: Indexed.ForwardState,
    limits: Indexed.IndexedLimits
  ) returns (outcome: Indexed.ForwardStep)
    requires Refinement.ExactObjectCatalog(
               objects,
               objectBindings
             )
    requires Refinement.ExactRelationCatalog(
               relations,
               relationBindings
             )
    requires Refinement.NormalizedFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires Refinement.ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires forall rule <- indexedRules ::
               rule.head in permissions
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires semanticSubject in objects
    requires Indexed.ForwardStateInvariant(state)
    requires Indexed.CountersWithinLimits(state.counters, limits)
    requires state.pending.NoForwardPending?
    requires Indexed.ValidForwardQueuedEids(state.queue)
    requires Refinement.ForwardStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               indexedRules,
               state
             )
    requires ForwardCoverageInvariant(
               objectBindings,
               relationBindings,
               relationships,
               seedRules,
               subjectType,
               subjectEid,
               state
             )
    ensures outcome == Indexed.ForwardStepSpec(state, limits)
    ensures Refinement.ForwardStateRefines(
              objectBindings,
              relationBindings,
              relationships,
              semanticSubject,
              grants,
              indexedRules,
              outcome.state
            )
    ensures ForwardCoverageInvariant(
              objectBindings,
              relationBindings,
              relationships,
              seedRules,
              subjectType,
              subjectEid,
              outcome.state
            )
  {
    outcome :=
      Refinement.StepForwardRefined(
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
        limits
      );
    assert outcome == Indexed.ForwardStepSpec(state, limits);
    ForwardStepPreservesCoverage(
      objectBindings,
      relationBindings,
      relationships,
      seedRules,
      subjectType,
      subjectEid,
      state,
      limits,
      outcome
    );
  }

  method {:isolate_assertions} ResumeForwardCompleteRefined(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    semanticSubject: Semantics.ObjectRef,
    grants: set<Semantics.Grant>,
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string,
    subjectEid: int,
    state: Indexed.ForwardState,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>,
    limits: Indexed.IndexedLimits
  ) returns (outcome: Indexed.ForwardResume)
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires Indexed.ForwardStateInvariant(state)
    requires Indexed.CountersWithinLimits(state.counters, limits)
    requires state.pending.AwaitingForwardScan?
    requires Refinement.ForwardStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               semanticSubject,
               grants,
               indexedRules,
               state
             )
    requires ForwardCoverageInvariant(
               objectBindings,
               relationBindings,
               relationships,
               seedRules,
               subjectType,
               subjectEid,
               state
             )
    requires Refinement.ExactProjectionValues(
               objectBindings,
               relationBindings,
               relationships,
               state.pending.command.projection,
               fullProjectionValues
             )
    requires Refinement.ExactScanResponse(
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
              Refinement.ForwardStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                semanticSubject,
                grants,
                indexedRules,
                outcome.state
              ) &&
              ForwardCoverageInvariant(
                objectBindings,
                relationBindings,
                relationships,
                seedRules,
                subjectType,
                subjectEid,
                outcome.state
              )
    ensures outcome.ForwardScanLimitExceeded? ==>
              Refinement.ForwardStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                semanticSubject,
                grants,
                indexedRules,
                outcome.state
              ) &&
              ForwardCoverageInvariant(
                objectBindings,
                relationBindings,
                relationships,
                seedRules,
                subjectType,
                subjectEid,
                outcome.state
              )
    ensures !outcome.ForwardScanRejected?
  {
    outcome :=
      Refinement.ResumeForwardRefined(
        objectBindings,
        relationBindings,
        relationships,
        semanticSubject,
        grants,
        indexedRules,
        state,
        response,
        fullProjectionValues,
        limits
      );
    ForwardCertifiedResumePreservesCoverage(
      objectBindings,
      relationBindings,
      relationships,
      seedRules,
      subjectType,
      subjectEid,
      state,
      response,
      fullProjectionValues,
      limits,
      outcome
    );
  }
}

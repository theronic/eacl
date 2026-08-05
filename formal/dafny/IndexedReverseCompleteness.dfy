include "Semantics.dfy"
include "IndexedTraversal.dfy"
include "IndexedRefinement.dfy"
include "IndexedForwardCompleteness.dfy"

module IndexedReverseCompleteness {
  import Semantics
  import Indexed = IndexedTraversal
  import Refinement = IndexedRefinement
  import Forward = IndexedForwardCompleteness

  datatype ReverseFact =
    | ReverseGoalFact(goal: Indexed.ReverseGoalKey)
    | ReverseGrantFact(grant: Indexed.ReverseGrantKey)
    | ReverseConsumerFact(
        key: Indexed.ReverseGoalKey,
        consumer: Indexed.ReverseConsumer
      )

  ghost predicate ReverseFactSatisfied(
    state: Indexed.ReverseState,
    fact: ReverseFact
  ) {
    match fact
    case ReverseGoalFact(goal) =>
      goal in state.seenGoals
    case ReverseGrantFact(grant) =>
      grant in state.seenGrants
    case ReverseConsumerFact(key, consumer) =>
      key in state.consumers &&
      consumer in state.consumers[key]
  }

  ghost predicate ReverseIndexCoherence(
    state: Indexed.ReverseState
  ) {
    (forall grant <- state.seenGrants ::
       var key :=
         Indexed.ReverseGoalKey(
           grant.node,
           grant.resourceEid
         );
       key in state.grantsByGoal &&
       grant in state.grantsByGoal[key]) &&
    (forall key <- state.grantsByGoal.Keys,
       grant <- state.grantsByGoal[key] ::
       grant in state.seenGrants &&
       grant.node == key.node &&
       grant.resourceEid == key.resourceEid) &&
    (forall key <- state.consumers.Keys,
       consumer <- state.consumers[key] ::
       Indexed.ReverseConsumerRegistration(
         key,
         consumer
       ) in state.seenConsumers) &&
    (forall registration <- state.seenConsumers ::
       registration.key in state.consumers &&
       registration.consumer in
         state.consumers[registration.key])
  }

  ghost predicate ReverseConsumersIncluded(
    before: Indexed.ReverseState,
    after: Indexed.ReverseState
  ) {
    forall key <- before.consumers.Keys,
      consumer <- before.consumers[key] ::
      key in after.consumers &&
      consumer in after.consumers[key]
  }

  ghost predicate ReverseContinuationCoversFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    continuation: Indexed.ReverseContinuation,
    sourceEid: int,
    fact: ReverseFact
  ) {
    match continuation
    case ReverseGrant(node, resourceEid, subjectType) =>
      fact ==
      ReverseGrantFact(
        Indexed.ReverseGrantKey(
          node,
          resourceEid,
          subjectType,
          sourceEid
        )
      )
    case ReverseArrowRelation(
      node,
      resourceEid,
      subjectType,
      intermediateType,
      targetRelationEid
      ) =>
      exists subjectEid: int ::
        0 <= subjectEid &&
        Refinement.RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ResourceToSubjects(
            intermediateType,
            sourceEid,
            targetRelationEid,
            subjectType,
            Indexed.NoBound
          ),
          subjectEid
        ) &&
        fact ==
        ReverseGrantFact(
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
      var key := Indexed.ReverseGoalKey(targetNode, sourceEid);
      fact == ReverseGoalFact(key) ||
      fact ==
      ReverseConsumerFact(
        key,
        Indexed.ReverseConsumer(node, resourceEid)
      )
  }

  ghost predicate ReverseStreamCoversFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    stream: Indexed.ReverseStream,
    fact: ReverseFact
  ) {
    (exists sourceEid <- stream.buffered ::
       ReverseContinuationCoversFact(
         objectBindings,
         relationBindings,
         relationships,
         stream.continuation,
         sourceEid,
         fact
       )) ||
    (stream.more &&
     exists sourceEid: int ::
       0 <= sourceEid &&
       Forward.EidAboveBound(
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
       ReverseContinuationCoversFact(
         objectBindings,
         relationBindings,
         relationships,
         stream.continuation,
         sourceEid,
         fact
       ))
  }

  ghost predicate ReverseWorkCoversFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    work: Indexed.ReverseWork,
    fact: ReverseFact
  ) {
    match work
    case ReverseStreamWork(stream) =>
      ReverseStreamCoversFact(
        objectBindings,
        relationBindings,
        relationships,
        stream,
        fact
      )
    case ReverseGoalWork(goal) =>
      fact == ReverseGoalFact(goal)
    case ReverseRegisterConsumerWork(key, consumer) =>
      fact == ReverseConsumerFact(key, consumer)
    case ReverseGrantWork(grant) =>
      fact == ReverseGrantFact(grant)
  }

  ghost predicate ReverseQueueCoversFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    queue: seq<Indexed.ReverseWork>,
    fact: ReverseFact
  ) {
    exists work <- queue ::
      ReverseWorkCoversFact(
        objectBindings,
        relationBindings,
        relationships,
        work,
        fact
      )
  }

  ghost predicate PendingReverseCoversFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    pending: Indexed.ReversePending,
    fact: ReverseFact
  ) {
    pending.AwaitingReverseScan? &&
    ReverseStreamCoversFact(
      objectBindings,
      relationBindings,
      relationships,
      Indexed.ReverseStream(
        pending.command.projection,
        [],
        true,
        pending.continuation
      ),
      fact
    )
  }

  ghost predicate ReverseCanonicalObligation(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    fact: ReverseFact
  )
    requires Indexed.ReverseStateInvariant(state)
  {
    fact ==
    ReverseGoalFact(
      Indexed.ReverseGoalKey(
        state.rootNode,
        state.rootResourceEid
      )
    ) ||
    (exists goal <- state.seenGoals ::
       var rules :=
         if goal.node in state.rulesByNode
         then state.rulesByNode[goal.node]
         else [];
       ReverseQueueCoversFact(
         objectBindings,
         relationBindings,
         relationships,
         Indexed.ReverseGoalRuleWorks(
           rules,
           goal,
           state.subjectType
         ),
         fact
       )) ||
    (exists key <- state.consumers.Keys,
       consumer <- state.consumers[key],
       grant <- state.seenGrants ::
       grant.node == key.node &&
       grant.resourceEid == key.resourceEid &&
       ReverseQueueCoversFact(
         objectBindings,
         relationBindings,
         relationships,
         Indexed.ReverseConsumerWork(
           consumer,
           grant
         ),
         fact
       ))
  }

  ghost predicate ReverseCoverageInvariant(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState
  )
    requires Indexed.ReverseStateInvariant(state)
  {
    ReverseIndexCoherence(state) &&
    forall fact: ReverseFact |
      ReverseCanonicalObligation(
        objectBindings,
        relationBindings,
        relationships,
        state,
        fact
      ) ::
      ReverseFactSatisfied(state, fact) ||
      ReverseQueueCoversFact(
        objectBindings,
        relationBindings,
        relationships,
        state.queue,
        fact
      ) ||
      PendingReverseCoversFact(
        objectBindings,
        relationBindings,
        relationships,
        state.pending,
        fact
      )
  }

  lemma ReverseQueueCoverComesFromLeftOrRight(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    left: seq<Indexed.ReverseWork>,
    right: seq<Indexed.ReverseWork>,
    fact: ReverseFact
  )
    requires ReverseQueueCoversFact(
               objectBindings,
               relationBindings,
               relationships,
               left + right,
               fact
             )
    ensures ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              left,
              fact
            ) ||
            ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              right,
              fact
            )
  {
  }

  lemma ReverseLeftOrRightCoverImpliesConcatenatedCover(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    left: seq<Indexed.ReverseWork>,
    right: seq<Indexed.ReverseWork>,
    fact: ReverseFact
  )
    requires ReverseQueueCoversFact(
               objectBindings,
               relationBindings,
               relationships,
               left,
               fact
             ) ||
             ReverseQueueCoversFact(
               objectBindings,
               relationBindings,
               relationships,
               right,
               fact
             )
    ensures ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              left + right,
              fact
            )
  {
  }

  lemma EmptyReverseQueueCoversNoFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    fact: ReverseFact
  )
    ensures !ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              [],
              fact
            )
  {
  }

  lemma NoPendingReverseScanCoversNoFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    fact: ReverseFact
  )
    ensures !PendingReverseCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.NoReversePending,
              fact
            )
  {
  }

  lemma ReverseQueueHeadOrTailCoversFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    queue: seq<Indexed.ReverseWork>,
    fact: ReverseFact
  )
    requires 0 < |queue|
    requires ReverseQueueCoversFact(
               objectBindings,
               relationBindings,
               relationships,
               queue,
               fact
             )
    ensures ReverseWorkCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              queue[0],
              fact
            ) ||
            ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              queue[1..],
              fact
            )
  {
    assert queue == [queue[0]] + queue[1..];
    ReverseQueueCoverComesFromLeftOrRight(
      objectBindings,
      relationBindings,
      relationships,
      [queue[0]],
      queue[1..],
      fact
    );
  }

  lemma ReverseQueueTailCoverSurvivesAppendedWork(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    tail: seq<Indexed.ReverseWork>,
    appended: seq<Indexed.ReverseWork>,
    fact: ReverseFact
  )
    requires ReverseQueueCoversFact(
               objectBindings,
               relationBindings,
               relationships,
               tail,
               fact
             )
    ensures ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              tail + appended,
              fact
            )
  {
    ReverseLeftOrRightCoverImpliesConcatenatedCover(
      objectBindings,
      relationBindings,
      relationships,
      tail,
      appended,
      fact
    );
  }

  lemma ContinueReverseCoversFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    continuation: Indexed.ReverseContinuation,
    sourceEid: int,
    fact: ReverseFact
  )
    requires 0 <= sourceEid
    requires Indexed.ValidReverseContinuationEids(continuation)
    requires ReverseContinuationCoversFact(
               objectBindings,
               relationBindings,
               relationships,
               continuation,
               sourceEid,
               fact
             )
    ensures ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ContinueReverse(continuation, sourceEid),
              fact
            )
  {
    match continuation
    case ReverseGrant(node, resourceEid, subjectType) => {
      var grant :=
        Indexed.ReverseGrantKey(
          node,
          resourceEid,
          subjectType,
          sourceEid
        );
      assert fact == ReverseGrantFact(grant);
      assert ReverseWorkCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ReverseGrantWork(grant),
          fact
        );
      assert ReverseQueueCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          [Indexed.ReverseGrantWork(grant)],
          fact
        );
    }
    case ReverseArrowRelation(
      node,
        resourceEid,
        subjectType,
        intermediateType,
        targetRelationEid
        ) => {
      var subjectEid :|
        0 <= subjectEid &&
        Refinement.RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ResourceToSubjects(
            intermediateType,
            sourceEid,
            targetRelationEid,
            subjectType,
            Indexed.NoBound
          ),
          subjectEid
        ) &&
        fact ==
        ReverseGrantFact(
          Indexed.ReverseGrantKey(
            node,
            resourceEid,
            subjectType,
            subjectEid
          )
        );
      var stream :=
        Indexed.ReverseStream(
          Indexed.ResourceToSubjects(
            intermediateType,
            sourceEid,
            targetRelationEid,
            subjectType,
            Indexed.NoBound
          ),
          [],
          true,
          Indexed.ReverseGrant(
            node,
            resourceEid,
            subjectType
          )
        );
      assert Forward.EidAboveBound(subjectEid, Indexed.NoBound);
      assert ReverseContinuationCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          stream.continuation,
          subjectEid,
          fact
        );
      assert ReverseStreamCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          stream,
          fact
        );
      assert Indexed.ContinueReverse(
          continuation,
          sourceEid
        ) == [Indexed.ReverseStreamWork(stream)];
      assert ReverseWorkCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ReverseStreamWork(stream),
          fact
        );
      assert ReverseQueueCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          [Indexed.ReverseStreamWork(stream)],
          fact
        );
    }
    case ReverseArrowPermission(
      node,
        resourceEid,
        targetNode
        ) => {
      var key := Indexed.ReverseGoalKey(targetNode, sourceEid);
      var consumer := Indexed.ReverseConsumer(node, resourceEid);
      if fact == ReverseGoalFact(key) {
        assert ReverseWorkCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            Indexed.ReverseGoalWork(key),
            fact
          );
        assert ReverseQueueCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            Indexed.ContinueReverse(
              continuation,
              sourceEid
            ),
            fact
          );
      } else {
        assert fact == ReverseConsumerFact(key, consumer);
        assert ReverseWorkCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            Indexed.ReverseRegisterConsumerWork(
              key,
              consumer
            ),
            fact
          );
        assert ReverseQueueCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            Indexed.ContinueReverse(
              continuation,
              sourceEid
            ),
            fact
          );
      }
    }
  }

  lemma BufferedReverseStreamCoverIsPreserved(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    stream: Indexed.ReverseStream,
    fact: ReverseFact
  )
    requires 0 < |stream.buffered|
    requires forall index | 0 <= index < |stream.buffered| ::
               0 <= stream.buffered[index]
    requires Indexed.ValidReverseContinuationEids(
               stream.continuation
             )
    requires ReverseStreamCoversFact(
               objectBindings,
               relationBindings,
               relationships,
               stream,
               fact
             )
    ensures ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ReverseBufferedWork(stream),
              fact
            )
  {
    var continuationWork :=
      Indexed.ContinueReverse(
        stream.continuation,
        stream.buffered[0]
      );
    var streamWork :=
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
    if exists sourceEid <- stream.buffered ::
        ReverseContinuationCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          stream.continuation,
          sourceEid,
          fact
        )
    {
      var sourceEid :| sourceEid in stream.buffered &&
                       ReverseContinuationCoversFact(
                         objectBindings,
                         relationBindings,
                         relationships,
                         stream.continuation,
                         sourceEid,
                         fact
                       );
      if sourceEid == stream.buffered[0] {
        ContinueReverseCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          stream.continuation,
          sourceEid,
          fact
        );
        ReverseLeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          continuationWork,
          streamWork,
          fact
        );
      } else {
        assert sourceEid in stream.buffered[1..];
        assert 1 < |stream.buffered|;
        assert ReverseWorkCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            streamWork[0],
            fact
          );
        assert ReverseQueueCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            streamWork,
            fact
          );
        ReverseLeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          continuationWork,
          streamWork,
          fact
        );
      }
    } else {
      assert stream.more;
      assert 0 < |streamWork|;
      assert ReverseWorkCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          streamWork[0],
          fact
        );
      assert ReverseQueueCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          streamWork,
          fact
        );
      ReverseLeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        continuationWork,
        streamWork,
        fact
      );
    }
  }

  lemma EmptyFinishedReverseStreamCoversNoFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    stream: Indexed.ReverseStream,
    fact: ReverseFact
  )
    requires stream.buffered == []
    requires !stream.more
    ensures !ReverseStreamCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              stream,
              fact
            )
  {
  }

  lemma IssuedPendingReverseScanCoversEmptyOpenStream(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    stream: Indexed.ReverseStream,
    command: Indexed.ScanCommand,
    fact: ReverseFact
  )
    requires stream.buffered == []
    requires stream.more
    requires command.projection == stream.projection
    ensures ReverseStreamCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              stream,
              fact
            ) <==>
            PendingReverseCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.AwaitingReverseScan(
                command,
                stream.continuation
              ),
              fact
            )
  {
  }

  lemma ReverseInitializationEstablishesCoverage(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState
  )
    requires Indexed.ReverseStateInvariant(state)
    requires state.queue ==
             [Indexed.ReverseGoalWork(
                Indexed.ReverseGoalKey(
                  state.rootNode,
                  state.rootResourceEid
                )
              )]
    requires state.seenGoals == {}
    requires state.seenGrants == {}
    requires state.grantsByGoal == map[]
    requires state.consumers == map[]
    requires state.seenConsumers == {}
    requires state.pending.NoReversePending?
    ensures ReverseCoverageInvariant(
              objectBindings,
              relationBindings,
              relationships,
              state
            )
  {
    assert ReverseIndexCoherence(state);
    forall fact: ReverseFact |
      ReverseCanonicalObligation(
        objectBindings,
        relationBindings,
        relationships,
        state,
        fact
      )
      ensures ReverseFactSatisfied(state, fact) ||
              ReverseQueueCoversFact(
                objectBindings,
                relationBindings,
                relationships,
                state.queue,
                fact
              ) ||
              PendingReverseCoversFact(
                objectBindings,
                relationBindings,
                relationships,
                state.pending,
                fact
              )
    {
      var root :=
        Indexed.ReverseGoalKey(
          state.rootNode,
          state.rootResourceEid
        );
      assert fact == ReverseGoalFact(root);
      assert ReverseWorkCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ReverseGoalWork(root),
          fact
        );
      assert ReverseQueueCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          state.queue,
          fact
        );
    }
  }

  lemma {:isolate_assertions}
    ReverseStepPreservesSemanticGrowth(
    before: Indexed.ReverseState,
    limits: Indexed.IndexedLimits,
    outcome: Indexed.ReverseStep
  )
    requires Indexed.ReverseStateInvariant(before)
    requires Indexed.CountersWithinLimits(
               before.counters,
               limits
             )
    requires before.pending.NoReversePending?
    requires Indexed.ValidReverseQueuedEids(before.queue)
    requires outcome ==
             Indexed.ReverseStepSpec(before, limits)
    ensures Indexed.ReverseSemanticFrame(
              before,
              outcome.state
            )
    ensures before.seenGoals <= outcome.state.seenGoals
    ensures before.seenGrants <= outcome.state.seenGrants
    ensures ReverseConsumersIncluded(before, outcome.state)
  {
    assert Indexed.ReverseStepRelation(before, outcome);
    match outcome
    case ReverseAdvanced(after) => {
    }
    case ReverseYielded(after) => {
    }
    case ReverseNeedScan(after, _) => {
    }
    case ReverseEmitted(after, _, _) => {
    }
    case ReverseComplete(after) => {
    }
    case ReverseRenderRejected(_, after) => {
    }
    case ReverseStepLimitExceeded(_, after) => {
    }
  }

  lemma {:isolate_assertions}
    ReverseStepPreservesIndexCoherence(
    before: Indexed.ReverseState,
    limits: Indexed.IndexedLimits,
    outcome: Indexed.ReverseStep
  )
    requires Indexed.ReverseStateInvariant(before)
    requires Indexed.CountersWithinLimits(
               before.counters,
               limits
             )
    requires before.pending.NoReversePending?
    requires Indexed.ValidReverseQueuedEids(before.queue)
    requires ReverseIndexCoherence(before)
    requires outcome ==
             Indexed.ReverseStepSpec(before, limits)
    ensures ReverseIndexCoherence(outcome.state)
  {
    assert Indexed.ReverseStepRelation(before, outcome);
    match outcome
    case ReverseAdvanced(after) => {
      if after.seenGrants != before.seenGrants {
        var grant := before.queue[0].grant;
        var key :=
          Indexed.ReverseGoalKey(
            grant.node,
            grant.resourceEid
          );
        assert Indexed.ReverseSuccessfulGrantTransition(
            before,
            after,
            grant
          );
        forall seenGrant <- after.seenGrants
          ensures
            var seenKey :=
              Indexed.ReverseGoalKey(
                seenGrant.node,
                seenGrant.resourceEid
              );
            seenKey in after.grantsByGoal &&
            seenGrant in after.grantsByGoal[seenKey]
        {
        }
        forall indexedKey <- after.grantsByGoal.Keys,
          seenGrant <- after.grantsByGoal[indexedKey]
          ensures seenGrant in after.seenGrants &&
                  seenGrant.node == indexedKey.node &&
                  seenGrant.resourceEid ==
                  indexedKey.resourceEid
        {
        }
      } else if after.consumers != before.consumers {
        var key := before.queue[0].key;
        var consumer := before.queue[0].consumer;
        assert Indexed.ReverseSuccessfulConsumerTransition(
            before,
            after,
            key,
            consumer
          );
        forall indexedKey <- after.consumers.Keys,
          indexedConsumer <- after.consumers[indexedKey]
          ensures Indexed.ReverseConsumerRegistration(
                    indexedKey,
                    indexedConsumer
                  ) in after.seenConsumers
        {
        }
        forall registration <- after.seenConsumers
          ensures registration.key in after.consumers &&
                  registration.consumer in
                    after.consumers[registration.key]
        {
        }
      }
    }
    case ReverseYielded(after) => {
    }
    case ReverseNeedScan(after, _) => {
    }
    case ReverseEmitted(after, _, _) => {
      var grant := before.queue[0].grant;
      var key :=
        Indexed.ReverseGoalKey(
          grant.node,
          grant.resourceEid
        );
      assert Indexed.ReverseSuccessfulGrantTransition(
          before,
          after,
          grant
        );
      forall seenGrant <- after.seenGrants
        ensures
          var seenKey :=
            Indexed.ReverseGoalKey(
              seenGrant.node,
              seenGrant.resourceEid
            );
          seenKey in after.grantsByGoal &&
          seenGrant in after.grantsByGoal[seenKey]
      {
      }
      forall indexedKey <- after.grantsByGoal.Keys,
        seenGrant <- after.grantsByGoal[indexedKey]
        ensures seenGrant in after.seenGrants &&
                seenGrant.node == indexedKey.node &&
                seenGrant.resourceEid == indexedKey.resourceEid
      {
      }
    }
    case ReverseComplete(after) => {
    }
    case ReverseRenderRejected(_, after) => {
    }
    case ReverseStepLimitExceeded(_, after) => {
    }
  }

  lemma ReverseConsumerWorkFactIsIncluded(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    consumers: seq<Indexed.ReverseConsumer>,
    consumer: Indexed.ReverseConsumer,
    grant: Indexed.ReverseGrantKey,
    fact: ReverseFact
  )
    requires forall indexedConsumer <- consumers ::
               0 <= indexedConsumer.resourceEid
    requires 0 <= grant.resourceEid
    requires 0 <= grant.subjectEid
    requires consumer in consumers
    requires ReverseQueueCoversFact(
               objectBindings,
               relationBindings,
               relationships,
               Indexed.ReverseConsumerWork(
                 consumer,
                 grant
               ),
               fact
             )
    ensures ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ReverseConsumerWorks(
                consumers,
                grant
              ),
              fact
            )
    decreases |consumers|
  {
    if consumers[0] == consumer {
      ReverseLeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ReverseConsumerWork(
          consumers[0],
          grant
        ),
        Indexed.ReverseConsumerWorks(
          consumers[1..],
          grant
        ),
        fact
      );
    } else {
      ReverseConsumerWorkFactIsIncluded(
        objectBindings,
        relationBindings,
        relationships,
        consumers[1..],
        consumer,
        grant,
        fact
      );
      ReverseLeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ReverseConsumerWork(
          consumers[0],
          grant
        ),
        Indexed.ReverseConsumerWorks(
          consumers[1..],
          grant
        ),
        fact
      );
    }
  }

  lemma ReverseGrantConsumerFactIsIncluded(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    consumer: Indexed.ReverseConsumer,
    grants: seq<Indexed.ReverseGrantKey>,
    grant: Indexed.ReverseGrantKey,
    fact: ReverseFact
  )
    requires 0 <= consumer.resourceEid
    requires forall indexedGrant <- grants ::
               0 <= indexedGrant.resourceEid &&
               0 <= indexedGrant.subjectEid
    requires grant in grants
    requires ReverseQueueCoversFact(
               objectBindings,
               relationBindings,
               relationships,
               Indexed.ReverseConsumerWork(
                 consumer,
                 grant
               ),
               fact
             )
    ensures ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ReverseConsumersForGrants(
                consumer,
                grants
              ),
              fact
            )
    decreases |grants|
  {
    if grants[0] == grant {
      ReverseLeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ReverseConsumerWork(
          consumer,
          grants[0]
        ),
        Indexed.ReverseConsumersForGrants(
          consumer,
          grants[1..]
        ),
        fact
      );
    } else {
      ReverseGrantConsumerFactIsIncluded(
        objectBindings,
        relationBindings,
        relationships,
        consumer,
        grants[1..],
        grant,
        fact
      );
      ReverseLeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ReverseConsumerWork(
          consumer,
          grants[0]
        ),
        Indexed.ReverseConsumersForGrants(
          consumer,
          grants[1..]
        ),
        fact
      );
    }
  }

  lemma {:isolate_assertions}
    ReverseStepCoversOldQueueFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    before: Indexed.ReverseState,
    limits: Indexed.IndexedLimits,
    outcome: Indexed.ReverseStep,
    fact: ReverseFact
  )
    requires Indexed.ReverseStateInvariant(before)
    requires Indexed.CountersWithinLimits(
               before.counters,
               limits
             )
    requires before.pending.NoReversePending?
    requires Indexed.ValidReverseQueuedEids(before.queue)
    requires ReverseIndexCoherence(before)
    requires ReverseQueueCoversFact(
               objectBindings,
               relationBindings,
               relationships,
               before.queue,
               fact
             )
    requires outcome ==
             Indexed.ReverseStepSpec(before, limits)
    ensures ReverseFactSatisfied(outcome.state, fact) ||
            ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              outcome.state.queue,
              fact
            ) ||
            PendingReverseCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              outcome.state.pending,
              fact
            )
  {
    assert Indexed.ReverseStepRelation(before, outcome);
    ReverseStepPreservesSemanticGrowth(before, limits, outcome);
    match outcome
    case ReverseYielded(after) => {
    }
    case ReverseComplete(after) => {
    }
    case ReverseRenderRejected(_, after) => {
    }
    case ReverseStepLimitExceeded(_, after) => {
    }
    case ReverseNeedScan(after, command) => {
      ReverseQueueHeadOrTailCoversFact(
        objectBindings,
        relationBindings,
        relationships,
        before.queue,
        fact
      );
      if ReverseWorkCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          before.queue[0],
          fact
        )
      {
        IssuedPendingReverseScanCoversEmptyOpenStream(
          objectBindings,
          relationBindings,
          relationships,
          before.queue[0].stream,
          command,
          fact
        );
      }
    }
    case ReverseAdvanced(after) => {
      ReverseQueueHeadOrTailCoversFact(
        objectBindings,
        relationBindings,
        relationships,
        before.queue,
        fact
      );
      var work := before.queue[0];
      var tail := before.queue[1..];
      if work.ReverseStreamWork? {
        if |work.stream.buffered| == 0 {
          assert !work.stream.more;
          EmptyFinishedReverseStreamCoversNoFact(
            objectBindings,
            relationBindings,
            relationships,
            work.stream,
            fact
          );
          assert ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              tail,
              fact
            );
        } else {
          var generated :=
            Indexed.ReverseBufferedWork(work.stream);
          if ReverseWorkCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              work,
              fact
            )
          {
            BufferedReverseStreamCoverIsPreserved(
              objectBindings,
              relationBindings,
              relationships,
              work.stream,
              fact
            );
            ReverseLeftOrRightCoverImpliesConcatenatedCover(
              objectBindings,
              relationBindings,
              relationships,
              tail,
              generated,
              fact
            );
          } else {
            ReverseQueueTailCoverSurvivesAppendedWork(
              objectBindings,
              relationBindings,
              relationships,
              tail,
              generated,
              fact
            );
          }
        }
      } else if work.ReverseGoalWork? {
        if work.goal in before.seenGoals {
          assert ReverseFactSatisfied(before, fact) ||
                 ReverseQueueCoversFact(
                   objectBindings,
                   relationBindings,
                   relationships,
                   tail,
                   fact
                 );
        } else {
          assert Indexed.ReverseSuccessfulGoalTransition(
              before,
              after,
              work.goal
            );
          if fact == ReverseGoalFact(work.goal) {
            assert ReverseFactSatisfied(after, fact);
          } else {
            var rules :=
              if work.goal.node in before.rulesByNode
              then before.rulesByNode[work.goal.node]
              else [];
            ReverseQueueTailCoverSurvivesAppendedWork(
              objectBindings,
              relationBindings,
              relationships,
              tail,
              Indexed.ReverseGoalRuleWorks(
                rules,
                work.goal,
                before.subjectType
              ),
              fact
            );
          }
        }
      } else if work.ReverseRegisterConsumerWork? {
        var registration :=
          Indexed.ReverseConsumerRegistration(
            work.key,
            work.consumer
          );
        if registration in before.seenConsumers {
          assert work.key in before.consumers;
          assert work.consumer in before.consumers[work.key];
          assert ReverseFactSatisfied(before, fact) ||
                 ReverseQueueCoversFact(
                   objectBindings,
                   relationBindings,
                   relationships,
                   tail,
                   fact
                 );
        } else {
          assert Indexed.ReverseSuccessfulConsumerTransition(
              before,
              after,
              work.key,
              work.consumer
            );
          if fact == ReverseConsumerFact(
                       work.key,
                       work.consumer
                     )
          {
            assert ReverseFactSatisfied(after, fact);
          } else {
            var existingGrants :=
              if work.key in before.grantsByGoal
              then before.grantsByGoal[work.key]
              else [];
            ReverseQueueTailCoverSurvivesAppendedWork(
              objectBindings,
              relationBindings,
              relationships,
              tail,
              Indexed.ReverseConsumersForGrants(
                work.consumer,
                existingGrants
              ),
              fact
            );
          }
        }
      } else {
        if work.grant in before.seenGrants {
          assert ReverseFactSatisfied(before, fact) ||
                 ReverseQueueCoversFact(
                   objectBindings,
                   relationBindings,
                   relationships,
                   tail,
                   fact
                 );
        } else {
          assert Indexed.ReverseSuccessfulGrantTransition(
              before,
              after,
              work.grant
            );
          if fact == ReverseGrantFact(work.grant) {
            assert ReverseFactSatisfied(after, fact);
          } else {
            var key :=
              Indexed.ReverseGoalKey(
                work.grant.node,
                work.grant.resourceEid
              );
            var consumers :=
              if key in before.consumers
              then before.consumers[key]
              else [];
            ReverseQueueTailCoverSurvivesAppendedWork(
              objectBindings,
              relationBindings,
              relationships,
              tail,
              Indexed.ReverseConsumerWorks(
                consumers,
                work.grant
              ),
              fact
            );
          }
        }
      }
    }
    case ReverseEmitted(after, _, _) => {
      ReverseQueueHeadOrTailCoversFact(
        objectBindings,
        relationBindings,
        relationships,
        before.queue,
        fact
      );
      var work := before.queue[0];
      assert work.ReverseGrantWork?;
      assert Indexed.ReverseSuccessfulGrantTransition(
          before,
          after,
          work.grant
        );
      if fact == ReverseGrantFact(work.grant) {
        assert ReverseFactSatisfied(after, fact);
      } else {
        var key :=
          Indexed.ReverseGoalKey(
            work.grant.node,
            work.grant.resourceEid
          );
        var consumers :=
          if key in before.consumers
          then before.consumers[key]
          else [];
        ReverseQueueTailCoverSurvivesAppendedWork(
          objectBindings,
          relationBindings,
          relationships,
          before.queue[1..],
          Indexed.ReverseConsumerWorks(
            consumers,
            work.grant
          ),
          fact
        );
      }
    }
  }

  lemma ReverseCoverageTransport(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    before: Indexed.ReverseState,
    after: Indexed.ReverseState
  )
    requires Indexed.ReverseStateInvariant(before)
    requires Indexed.ReverseStateInvariant(after)
    requires ReverseCoverageInvariant(
               objectBindings,
               relationBindings,
               relationships,
               before
             )
    requires ReverseIndexCoherence(after)
    requires Indexed.ReverseSemanticFrame(before, after)
    requires before.seenGoals <= after.seenGoals
    requires before.seenGrants <= after.seenGrants
    requires ReverseConsumersIncluded(before, after)
    requires forall fact: ReverseFact |
               ReverseQueueCoversFact(
                 objectBindings,
                 relationBindings,
                 relationships,
                 before.queue,
                 fact
               ) ::
               ReverseFactSatisfied(after, fact) ||
               ReverseQueueCoversFact(
                 objectBindings,
                 relationBindings,
                 relationships,
                 after.queue,
                 fact
               ) ||
               PendingReverseCoversFact(
                 objectBindings,
                 relationBindings,
                 relationships,
                 after.pending,
                 fact
               )
    requires forall fact: ReverseFact |
               PendingReverseCoversFact(
                 objectBindings,
                 relationBindings,
                 relationships,
                 before.pending,
                 fact
               ) ::
               ReverseFactSatisfied(after, fact) ||
               ReverseQueueCoversFact(
                 objectBindings,
                 relationBindings,
                 relationships,
                 after.queue,
                 fact
               ) ||
               PendingReverseCoversFact(
                 objectBindings,
                 relationBindings,
                 relationships,
                 after.pending,
                 fact
               )
    requires forall fact: ReverseFact |
               ReverseCanonicalObligation(
                 objectBindings,
                 relationBindings,
                 relationships,
                 after,
                 fact
               ) &&
               !ReverseCanonicalObligation(
                 objectBindings,
                 relationBindings,
                 relationships,
                 before,
                 fact
               ) ::
               ReverseFactSatisfied(after, fact) ||
               ReverseQueueCoversFact(
                 objectBindings,
                 relationBindings,
                 relationships,
                 after.queue,
                 fact
               ) ||
               PendingReverseCoversFact(
                 objectBindings,
                 relationBindings,
                 relationships,
                 after.pending,
                 fact
               )
    ensures ReverseCoverageInvariant(
              objectBindings,
              relationBindings,
              relationships,
              after
            )
  {
    forall fact: ReverseFact |
      ReverseCanonicalObligation(
        objectBindings,
        relationBindings,
        relationships,
        after,
        fact
      )
      ensures ReverseFactSatisfied(after, fact) ||
              ReverseQueueCoversFact(
                objectBindings,
                relationBindings,
                relationships,
                after.queue,
                fact
              ) ||
              PendingReverseCoversFact(
                objectBindings,
                relationBindings,
                relationships,
                after.pending,
                fact
              )
    {
      if ReverseCanonicalObligation(
          objectBindings,
          relationBindings,
          relationships,
          before,
          fact
        )
      {
        if ReverseFactSatisfied(before, fact) {
          match fact
          case ReverseGoalFact(goal) => {
          }
          case ReverseGrantFact(grant) => {
          }
          case ReverseConsumerFact(key, consumer) => {
          }
        } else if ReverseQueueCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            before.queue,
            fact
          )
        {
        } else {
          assert PendingReverseCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              before.pending,
              fact
            );
        }
      }
    }
  }

  lemma {:isolate_assertions}
    ReverseStepCoversNewCanonicalFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    before: Indexed.ReverseState,
    limits: Indexed.IndexedLimits,
    outcome: Indexed.ReverseStep,
    fact: ReverseFact
  )
    requires Indexed.ReverseStateInvariant(before)
    requires Indexed.CountersWithinLimits(
               before.counters,
               limits
             )
    requires before.pending.NoReversePending?
    requires Indexed.ValidReverseQueuedEids(before.queue)
    requires ReverseIndexCoherence(before)
    requires outcome ==
             Indexed.ReverseStepSpec(before, limits)
    requires ReverseCanonicalObligation(
               objectBindings,
               relationBindings,
               relationships,
               outcome.state,
               fact
             )
    requires !ReverseCanonicalObligation(
               objectBindings,
               relationBindings,
               relationships,
               before,
               fact
             )
    ensures ReverseFactSatisfied(outcome.state, fact) ||
            ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              outcome.state.queue,
              fact
            ) ||
            PendingReverseCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              outcome.state.pending,
              fact
            )
  {
    assert Indexed.ReverseStepRelation(before, outcome);
    ReverseStepPreservesSemanticGrowth(before, limits, outcome);
    match outcome
    case ReverseYielded(_) => {
      assert false;
    }
    case ReverseComplete(_) => {
      assert false;
    }
    case ReverseRenderRejected(_, _) => {
      assert false;
    }
    case ReverseStepLimitExceeded(_, _) => {
      assert false;
    }
    case ReverseNeedScan(_, _) => {
      assert false;
    }
    case ReverseAdvanced(after) => {
      var work := before.queue[0];
      var tail := before.queue[1..];
      if after.seenGoals != before.seenGoals {
        assert work.ReverseGoalWork?;
        assert Indexed.ReverseSuccessfulGoalTransition(
            before,
            after,
            work.goal
          );
        var rules :=
          if work.goal.node in before.rulesByNode
          then before.rulesByNode[work.goal.node]
          else [];
        assert ReverseQueueCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            Indexed.ReverseGoalRuleWorks(
              rules,
              work.goal,
              before.subjectType
            ),
            fact
          );
        ReverseLeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          tail,
          Indexed.ReverseGoalRuleWorks(
            rules,
            work.goal,
            before.subjectType
          ),
          fact
        );
      } else if after.consumers != before.consumers {
        assert work.ReverseRegisterConsumerWork?;
        assert Indexed.ReverseSuccessfulConsumerTransition(
            before,
            after,
            work.key,
            work.consumer
          );
        var key,
            consumer,
            grant :|
          key in after.consumers.Keys &&
          consumer in after.consumers[key] &&
          grant in after.seenGrants &&
          grant.node == key.node &&
          grant.resourceEid == key.resourceEid &&
          ReverseQueueCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            Indexed.ReverseConsumerWork(
              consumer,
              grant
            ),
            fact
          );
        assert key == work.key;
        assert consumer == work.consumer;
        assert grant in before.seenGrants;
        assert work.key in before.grantsByGoal;
        assert grant in before.grantsByGoal[work.key];
        var existingGrants := before.grantsByGoal[work.key];
        ReverseGrantConsumerFactIsIncluded(
          objectBindings,
          relationBindings,
          relationships,
          work.consumer,
          existingGrants,
          grant,
          fact
        );
        ReverseLeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          tail,
          Indexed.ReverseConsumersForGrants(
            work.consumer,
            existingGrants
          ),
          fact
        );
      } else if after.seenGrants != before.seenGrants {
        assert work.ReverseGrantWork?;
        assert Indexed.ReverseSuccessfulGrantTransition(
            before,
            after,
            work.grant
          );
        var key,
            consumer,
            grant :|
          key in after.consumers.Keys &&
          consumer in after.consumers[key] &&
          grant in after.seenGrants &&
          grant.node == key.node &&
          grant.resourceEid == key.resourceEid &&
          ReverseQueueCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            Indexed.ReverseConsumerWork(
              consumer,
              grant
            ),
            fact
          );
        assert grant == work.grant;
        assert key in before.consumers;
        assert consumer in before.consumers[key];
        var consumers := before.consumers[key];
        ReverseConsumerWorkFactIsIncluded(
          objectBindings,
          relationBindings,
          relationships,
          consumers,
          consumer,
          work.grant,
          fact
        );
        ReverseLeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          tail,
          Indexed.ReverseConsumerWorks(
            consumers,
            work.grant
          ),
          fact
        );
      } else {
        assert false;
      }
    }
    case ReverseEmitted(after, _, _) => {
      var work := before.queue[0];
      assert work.ReverseGrantWork?;
      assert Indexed.ReverseSuccessfulGrantTransition(
          before,
          after,
          work.grant
        );
      var key,
          consumer,
          grant :|
        key in after.consumers.Keys &&
        consumer in after.consumers[key] &&
        grant in after.seenGrants &&
        grant.node == key.node &&
        grant.resourceEid == key.resourceEid &&
        ReverseQueueCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ReverseConsumerWork(
            consumer,
            grant
          ),
          fact
        );
      assert grant == work.grant;
      assert key in before.consumers;
      assert consumer in before.consumers[key];
      var consumers := before.consumers[key];
      ReverseConsumerWorkFactIsIncluded(
        objectBindings,
        relationBindings,
        relationships,
        consumers,
        consumer,
        work.grant,
        fact
      );
      ReverseLeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        before.queue[1..],
        Indexed.ReverseConsumerWorks(
          consumers,
          work.grant
        ),
        fact
      );
    }
  }

  lemma {:isolate_assertions}
    ReverseStepPreservesCoverage(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    before: Indexed.ReverseState,
    limits: Indexed.IndexedLimits,
    outcome: Indexed.ReverseStep
  )
    requires Indexed.ReverseStateInvariant(before)
    requires Indexed.CountersWithinLimits(
               before.counters,
               limits
             )
    requires before.pending.NoReversePending?
    requires Indexed.ValidReverseQueuedEids(before.queue)
    requires ReverseCoverageInvariant(
               objectBindings,
               relationBindings,
               relationships,
               before
             )
    requires outcome ==
             Indexed.ReverseStepSpec(before, limits)
    ensures ReverseCoverageInvariant(
              objectBindings,
              relationBindings,
              relationships,
              outcome.state
            )
  {
    ReverseStepPreservesSemanticGrowth(before, limits, outcome);
    ReverseStepPreservesIndexCoherence(before, limits, outcome);
    forall fact: ReverseFact |
      ReverseQueueCoversFact(
        objectBindings,
        relationBindings,
        relationships,
        before.queue,
        fact
      )
      ensures ReverseFactSatisfied(outcome.state, fact) ||
              ReverseQueueCoversFact(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state.queue,
                fact
              ) ||
              PendingReverseCoversFact(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state.pending,
                fact
              )
    {
      ReverseStepCoversOldQueueFact(
        objectBindings,
        relationBindings,
        relationships,
        before,
        limits,
        outcome,
        fact
      );
    }
    forall fact: ReverseFact |
      PendingReverseCoversFact(
        objectBindings,
        relationBindings,
        relationships,
        before.pending,
        fact
      )
      ensures ReverseFactSatisfied(outcome.state, fact) ||
              ReverseQueueCoversFact(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state.queue,
                fact
              ) ||
              PendingReverseCoversFact(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state.pending,
                fact
              )
    {
      NoPendingReverseScanCoversNoFact(
        objectBindings,
        relationBindings,
        relationships,
        fact
      );
      assert false;
    }
    forall fact: ReverseFact |
      ReverseCanonicalObligation(
        objectBindings,
        relationBindings,
        relationships,
        outcome.state,
        fact
      ) &&
      !ReverseCanonicalObligation(
        objectBindings,
        relationBindings,
        relationships,
        before,
        fact
      )
      ensures ReverseFactSatisfied(outcome.state, fact) ||
              ReverseQueueCoversFact(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state.queue,
                fact
              ) ||
              PendingReverseCoversFact(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state.pending,
                fact
              )
    {
      ReverseStepCoversNewCanonicalFact(
        objectBindings,
        relationBindings,
        relationships,
        before,
        limits,
        outcome,
        fact
      );
    }
    ReverseCoverageTransport(
      objectBindings,
      relationBindings,
      relationships,
      before,
      outcome.state
    );
  }

  lemma ReverseResponseWorkCoversSourceFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    command: Indexed.ScanCommand,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>,
    continuation: Indexed.ReverseContinuation,
    sourceEid: int,
    fact: ReverseFact
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
    requires Indexed.ValidReverseContinuationEids(
               continuation
             )
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
    requires ReverseContinuationCoversFact(
               objectBindings,
               relationBindings,
               relationships,
               continuation,
               sourceEid,
               fact
             )
    ensures ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ReverseWorkAfterResponse(
                command,
                continuation,
                Indexed.ScanAccepted(
                  response.values,
                  response.terminal,
                  response.fetchedValues
                )
              ),
              fact
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
      Indexed.ContinueReverse(
        continuation,
        response.values[0]
      );
    var streamWork :=
      if 1 < |response.values| || !response.terminal
      then
        [Indexed.ReverseStreamWork(
           Indexed.ReverseStream(
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
    Forward.ValuesAfterBoundPreservesIncreasing(
      fullProjectionValues,
      Indexed.ProjectionBound(command.projection)
    );
    Forward.ChunkPrefixContainsMemberOrEndsBeforeIt(
      remaining,
      command.chunkSize,
      sourceEid
    );
    if sourceEid in response.values {
      if sourceEid == response.values[0] {
        ContinueReverseCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          continuation,
          sourceEid,
          fact
        );
        ReverseLeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          continuationWork,
          streamWork,
          fact
        );
      } else {
        assert sourceEid in response.values[1..];
        assert 1 < |response.values|;
        assert 0 < |streamWork|;
        assert ReverseStreamCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            streamWork[0].stream,
            fact
          );
        assert ReverseWorkCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            streamWork[0],
            fact
          );
        assert ReverseQueueCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            streamWork,
            fact
          );
        ReverseLeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          continuationWork,
          streamWork,
          fact
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
      Forward.ProjectionAfterChunkPreservesProjectedMembership(
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
      assert Forward.EidAboveBound(
          sourceEid,
          Indexed.ProjectionBound(projection)
        );
      assert ReverseStreamCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          streamWork[0].stream,
          fact
        );
      assert ReverseWorkCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          streamWork[0],
          fact
        );
      assert ReverseQueueCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          streamWork,
          fact
        );
      ReverseLeftOrRightCoverImpliesConcatenatedCover(
        objectBindings,
        relationBindings,
        relationships,
        continuationWork,
        streamWork,
        fact
      );
    }
    assert Indexed.ReverseWorkAfterResponse(
        command,
        continuation,
        accepted
      ) == continuationWork + streamWork;
  }

  lemma CertifiedResponseCoversPendingReverseFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    pending: Indexed.ReversePending,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>,
    fact: ReverseFact
  )
    requires pending.AwaitingReverseScan?
    requires Indexed.ValidReverseContinuationEids(
               pending.continuation
             )
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
    requires PendingReverseCoversFact(
               objectBindings,
               relationBindings,
               relationships,
               pending,
               fact
             )
    ensures 0 < |response.values| &&
            ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ReverseWorkAfterResponse(
                pending.command,
                pending.continuation,
                Indexed.ScanAccepted(
                  response.values,
                  response.terminal,
                  response.fetchedValues
                )
              ),
              fact
            )
  {
    var sourceEid :| 0 <= sourceEid &&
                     Forward.EidAboveBound(
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
                     ReverseContinuationCoversFact(
                       objectBindings,
                       relationBindings,
                       relationships,
                       pending.continuation,
                       sourceEid,
                       fact
                     );
    assert sourceEid in fullProjectionValues;
    Forward.ValuesAfterBoundKeepsAboveMember(
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
    ReverseResponseWorkCoversSourceFact(
      objectBindings,
      relationBindings,
      relationships,
      pending.command,
      response,
      fullProjectionValues,
      pending.continuation,
      sourceEid,
      fact
    );
  }

  lemma {:isolate_assertions}
    ReverseCertifiedResumePreservesCoverage(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    before: Indexed.ReverseState,
    response: Indexed.ScanResponse,
    fullProjectionValues: seq<int>,
    limits: Indexed.IndexedLimits,
    outcome: Indexed.ReverseResume
  )
    requires Indexed.ReverseStateInvariant(before)
    requires Indexed.CountersWithinLimits(
               before.counters,
               limits
             )
    requires before.pending.AwaitingReverseScan?
    requires ReverseCoverageInvariant(
               objectBindings,
               relationBindings,
               relationships,
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
             Indexed.ReverseResumeSpec(
               before,
               response,
               limits
             )
    ensures outcome.ReverseScanResumed? ==>
              ReverseCoverageInvariant(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state
              )
    ensures outcome.ReverseScanLimitExceeded? ==>
              ReverseCoverageInvariant(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state
              )
  {
    assert Indexed.ReverseResumeRelation(
        before,
        response,
        outcome
      );
    match outcome
    case ReverseScanRejected(_) => {
      assert Indexed.ValidScanResponse(
          before.pending.command,
          response
        );
      assert false;
    }
    case ReverseScanLimitExceeded(_, after) => {
      assert after == before;
    }
    case ReverseScanResumed(after) => {
      assert Indexed.ReverseStateInvariant(after);
      assert ReverseIndexCoherence(after);
      assert Indexed.ReverseSemanticFrame(before, after);
      assert before.seenGoals == after.seenGoals;
      assert before.seenGrants == after.seenGrants;
      assert before.consumers == after.consumers;
      assert ReverseConsumersIncluded(before, after);
      forall fact: ReverseFact |
        ReverseQueueCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          before.queue,
          fact
        )
        ensures ReverseFactSatisfied(after, fact) ||
                ReverseQueueCoversFact(
                  objectBindings,
                  relationBindings,
                  relationships,
                  after.queue,
                  fact
                ) ||
                PendingReverseCoversFact(
                  objectBindings,
                  relationBindings,
                  relationships,
                  after.pending,
                  fact
                )
      {
        if |response.values| == 0 {
          assert after.queue == before.queue;
        } else {
          var work :=
            Indexed.ReverseWorkAfterResponse(
              before.pending.command,
              before.pending.continuation,
              Indexed.ScanAccepted(
                response.values,
                response.terminal,
                response.fetchedValues
              )
            );
          ReverseLeftOrRightCoverImpliesConcatenatedCover(
            objectBindings,
            relationBindings,
            relationships,
            before.queue,
            work,
            fact
          );
        }
      }
      forall fact: ReverseFact |
        PendingReverseCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          before.pending,
          fact
        )
        ensures ReverseFactSatisfied(after, fact) ||
                ReverseQueueCoversFact(
                  objectBindings,
                  relationBindings,
                  relationships,
                  after.queue,
                  fact
                ) ||
                PendingReverseCoversFact(
                  objectBindings,
                  relationBindings,
                  relationships,
                  after.pending,
                  fact
                )
      {
        CertifiedResponseCoversPendingReverseFact(
          objectBindings,
          relationBindings,
          relationships,
          before.pending,
          response,
          fullProjectionValues,
          fact
        );
        var work :=
          Indexed.ReverseWorkAfterResponse(
            before.pending.command,
            before.pending.continuation,
            Indexed.ScanAccepted(
              response.values,
              response.terminal,
              response.fetchedValues
            )
          );
        assert after.queue == before.queue + work;
        ReverseLeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          before.queue,
          work,
          fact
        );
      }
      forall fact: ReverseFact |
        ReverseCanonicalObligation(
          objectBindings,
          relationBindings,
          relationships,
          after,
          fact
        ) &&
        !ReverseCanonicalObligation(
          objectBindings,
          relationBindings,
          relationships,
          before,
          fact
        )
        ensures ReverseFactSatisfied(after, fact) ||
                ReverseQueueCoversFact(
                  objectBindings,
                  relationBindings,
                  relationships,
                  after.queue,
                  fact
                ) ||
                PendingReverseCoversFact(
                  objectBindings,
                  relationBindings,
                  relationships,
                  after.pending,
                  fact
                )
      {
        assert false;
      }
      ReverseCoverageTransport(
        objectBindings,
        relationBindings,
        relationships,
        before,
        after
      );
    }
  }

  ghost predicate ReverseContinuationSatisfied(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    continuation: Indexed.ReverseContinuation,
    sourceEid: int
  ) {
    match continuation
    case ReverseGrant(node, resourceEid, subjectType) =>
      Indexed.ReverseGrantKey(
        node,
        resourceEid,
        subjectType,
        sourceEid
      ) in state.seenGrants
    case ReverseArrowRelation(
      node,
      resourceEid,
      subjectType,
      intermediateType,
      targetRelationEid
      ) =>
      forall subjectEid: int |
        0 <= subjectEid &&
        Refinement.RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ResourceToSubjects(
            intermediateType,
            sourceEid,
            targetRelationEid,
            subjectType,
            Indexed.NoBound
          ),
          subjectEid
        ) ::
        Indexed.ReverseGrantKey(
          node,
          resourceEid,
          subjectType,
          subjectEid
        ) in state.seenGrants
    case ReverseArrowPermission(
      node,
      resourceEid,
      targetNode
      ) =>
      var key := Indexed.ReverseGoalKey(targetNode, sourceEid);
      key in state.seenGoals &&
      key in state.consumers &&
      Indexed.ReverseConsumer(node, resourceEid) in
        state.consumers[key]
  }

  ghost predicate ReverseStreamSatisfied(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    stream: Indexed.ReverseStream
  ) {
    (forall sourceEid <- stream.buffered ::
       ReverseContinuationSatisfied(
         objectBindings,
         relationBindings,
         relationships,
         state,
         stream.continuation,
         sourceEid
       )) &&
    (
      stream.more ==>
        forall sourceEid: int |
          0 <= sourceEid &&
          Forward.EidAboveBound(
            sourceEid,
            Indexed.ProjectionBound(stream.projection)
          ) &&
          Refinement.RelationshipProjectsTo(
            objectBindings,
            relationBindings,
            relationships,
            stream.projection,
            sourceEid
          ) ::
          ReverseContinuationSatisfied(
            objectBindings,
            relationBindings,
            relationships,
            state,
            stream.continuation,
            sourceEid
          )
    )
  }

  lemma ReverseArrowRelationSatisfactionYieldsGrant(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    node: Semantics.PermissionNode,
    resourceEid: int,
    subjectType: string,
    intermediateType: string,
    targetRelationEid: int,
    intermediateEid: int,
    subjectEid: int
  )
    requires ReverseContinuationSatisfied(
               objectBindings,
               relationBindings,
               relationships,
               state,
               Indexed.ReverseArrowRelation(
                 node,
                 resourceEid,
                 subjectType,
                 intermediateType,
                 targetRelationEid
               ),
               intermediateEid
             )
    requires 0 <= subjectEid
    requires Refinement.RelationshipProjectsTo(
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
             )
    ensures Indexed.ReverseGrantKey(
              node,
              resourceEid,
              subjectType,
              subjectEid
            ) in state.seenGrants
  {
  }

  lemma RelationshipWitnessProjectsToReverse(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    resourceBinding: Refinement.ObjectBinding,
    subjectBinding: Refinement.ObjectBinding,
    relationBinding: Refinement.RelationBinding,
    relationship: Semantics.Relationship
  )
    requires resourceBinding in objectBindings
    requires subjectBinding in objectBindings
    requires relationBinding in relationBindings
    requires relationship in relationships
    requires relationship ==
             Semantics.Relationship(
               resourceBinding.objectRef,
               relationship.relationName,
               subjectBinding.objectRef
             )
    requires relationBinding.relation ==
             Semantics.RelationNode(
               resourceBinding.objectRef.typeName,
               relationship.relationName,
               subjectBinding.objectRef.typeName
             )
    ensures Refinement.RelationshipProjectsTo(
              objectBindings,
              relationBindings,
              relationships,
              Indexed.ResourceToSubjects(
                resourceBinding.objectRef.typeName,
                resourceBinding.eid,
                relationBinding.eid,
                subjectBinding.objectRef.typeName,
                Indexed.NoBound
              ),
              subjectBinding.eid
            )
  {
  }

  lemma MoreReverseStreamProjectsToContinuation(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    stream: Indexed.ReverseStream,
    sourceEid: int
  )
    requires ReverseStreamSatisfied(
               objectBindings,
               relationBindings,
               relationships,
               state,
               stream
             )
    requires stream.more
    requires 0 <= sourceEid
    requires Forward.EidAboveBound(
               sourceEid,
               Indexed.ProjectionBound(stream.projection)
             )
    requires Refinement.RelationshipProjectsTo(
               objectBindings,
               relationBindings,
               relationships,
               stream.projection,
               sourceEid
             )
    ensures ReverseContinuationSatisfied(
              objectBindings,
              relationBindings,
              relationships,
              state,
              stream.continuation,
              sourceEid
            )
  {
  }

  lemma ReverseArrowPermissionSatisfactionYieldsRegistration(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    node: Semantics.PermissionNode,
    resourceEid: int,
    targetNode: Semantics.PermissionNode,
    sourceEid: int
  )
    requires ReverseContinuationSatisfied(
               objectBindings,
               relationBindings,
               relationships,
               state,
               Indexed.ReverseArrowPermission(
                 node,
                 resourceEid,
                 targetNode
               ),
               sourceEid
             )
    ensures Indexed.ReverseGoalKey(
              targetNode,
              sourceEid
            ) in state.seenGoals
    ensures Indexed.ReverseGoalKey(
              targetNode,
              sourceEid
            ) in state.consumers
    ensures Indexed.ReverseConsumer(
              node,
              resourceEid
            ) in
              state.consumers[
              Indexed.ReverseGoalKey(
                targetNode,
                sourceEid
              )
              ]
  {
  }

  ghost predicate ReverseWorkSatisfied(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    work: Indexed.ReverseWork
  ) {
    match work
    case ReverseStreamWork(stream) =>
      ReverseStreamSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        stream
      )
    case ReverseGoalWork(goal) =>
      goal in state.seenGoals
    case ReverseRegisterConsumerWork(key, consumer) =>
      key in state.consumers &&
      consumer in state.consumers[key]
    case ReverseGrantWork(grant) =>
      grant in state.seenGrants
  }

  ghost predicate ReverseQueueSatisfied(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    queue: seq<Indexed.ReverseWork>
  ) {
    forall work <- queue ::
      ReverseWorkSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        work
      )
  }

  lemma ReverseContinuationFactsSatisfiedImpliesSatisfied(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    continuation: Indexed.ReverseContinuation,
    sourceEid: int
  )
    requires forall fact: ReverseFact |
               ReverseContinuationCoversFact(
                 objectBindings,
                 relationBindings,
                 relationships,
                 continuation,
                 sourceEid,
                 fact
               ) ::
               ReverseFactSatisfied(state, fact)
    ensures ReverseContinuationSatisfied(
              objectBindings,
              relationBindings,
              relationships,
              state,
              continuation,
              sourceEid
            )
  {
    match continuation
    case ReverseGrant(node, resourceEid, subjectType) => {
      var fact :=
        ReverseGrantFact(
          Indexed.ReverseGrantKey(
            node,
            resourceEid,
            subjectType,
            sourceEid
          )
        );
      assert ReverseContinuationCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          continuation,
          sourceEid,
          fact
        );
    }
    case ReverseArrowRelation(
      node,
        resourceEid,
        subjectType,
        intermediateType,
        targetRelationEid
        ) => {
      forall subjectEid: int |
        0 <= subjectEid &&
        Refinement.RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ResourceToSubjects(
            intermediateType,
            sourceEid,
            targetRelationEid,
            subjectType,
            Indexed.NoBound
          ),
          subjectEid
        )
        ensures Indexed.ReverseGrantKey(
                  node,
                  resourceEid,
                  subjectType,
                  subjectEid
                ) in state.seenGrants
      {
        var fact :=
          ReverseGrantFact(
            Indexed.ReverseGrantKey(
              node,
              resourceEid,
              subjectType,
              subjectEid
            )
          );
        assert ReverseContinuationCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            continuation,
            sourceEid,
            fact
          );
      }
    }
    case ReverseArrowPermission(
      node,
        resourceEid,
        targetNode
        ) => {
      var key := Indexed.ReverseGoalKey(targetNode, sourceEid);
      var consumer := Indexed.ReverseConsumer(node, resourceEid);
      var goalFact := ReverseGoalFact(key);
      var consumerFact := ReverseConsumerFact(key, consumer);
      assert ReverseContinuationCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          continuation,
          sourceEid,
          goalFact
        );
      assert ReverseContinuationCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          continuation,
          sourceEid,
          consumerFact
        );
    }
  }

  lemma ReverseStreamFactsSatisfiedImpliesSatisfied(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    stream: Indexed.ReverseStream
  )
    requires forall fact: ReverseFact |
               ReverseStreamCoversFact(
                 objectBindings,
                 relationBindings,
                 relationships,
                 stream,
                 fact
               ) ::
               ReverseFactSatisfied(state, fact)
    ensures ReverseStreamSatisfied(
              objectBindings,
              relationBindings,
              relationships,
              state,
              stream
            )
  {
    forall sourceEid <- stream.buffered
      ensures ReverseContinuationSatisfied(
                objectBindings,
                relationBindings,
                relationships,
                state,
                stream.continuation,
                sourceEid
              )
    {
      forall fact: ReverseFact |
        ReverseContinuationCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          stream.continuation,
          sourceEid,
          fact
        )
        ensures ReverseFactSatisfied(state, fact)
      {
        assert ReverseStreamCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            stream,
            fact
          );
      }
      ReverseContinuationFactsSatisfiedImpliesSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        stream.continuation,
        sourceEid
      );
    }
    if stream.more {
      forall sourceEid: int |
        0 <= sourceEid &&
        Forward.EidAboveBound(
          sourceEid,
          Indexed.ProjectionBound(stream.projection)
        ) &&
        Refinement.RelationshipProjectsTo(
          objectBindings,
          relationBindings,
          relationships,
          stream.projection,
          sourceEid
        )
        ensures ReverseContinuationSatisfied(
                  objectBindings,
                  relationBindings,
                  relationships,
                  state,
                  stream.continuation,
                  sourceEid
                )
      {
        forall fact: ReverseFact |
          ReverseContinuationCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            stream.continuation,
            sourceEid,
            fact
          )
          ensures ReverseFactSatisfied(state, fact)
        {
          assert ReverseStreamCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              stream,
              fact
            );
        }
        ReverseContinuationFactsSatisfiedImpliesSatisfied(
          objectBindings,
          relationBindings,
          relationships,
          state,
          stream.continuation,
          sourceEid
        );
      }
    }
  }

  lemma ReverseWorkFactsSatisfiedImpliesSatisfied(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    work: Indexed.ReverseWork
  )
    requires forall fact: ReverseFact |
               ReverseWorkCoversFact(
                 objectBindings,
                 relationBindings,
                 relationships,
                 work,
                 fact
               ) ::
               ReverseFactSatisfied(state, fact)
    ensures ReverseWorkSatisfied(
              objectBindings,
              relationBindings,
              relationships,
              state,
              work
            )
  {
    match work
    case ReverseStreamWork(stream) => {
      ReverseStreamFactsSatisfiedImpliesSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        stream
      );
    }
    case ReverseGoalWork(goal) => {
      var fact := ReverseGoalFact(goal);
      assert ReverseWorkCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          work,
          fact
        );
    }
    case ReverseRegisterConsumerWork(key, consumer) => {
      var fact := ReverseConsumerFact(key, consumer);
      assert ReverseWorkCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          work,
          fact
        );
    }
    case ReverseGrantWork(grant) => {
      var fact := ReverseGrantFact(grant);
      assert ReverseWorkCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          work,
          fact
        );
    }
  }

  lemma ReverseQueueFactsSatisfiedImpliesSatisfied(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    queue: seq<Indexed.ReverseWork>
  )
    requires forall fact: ReverseFact |
               ReverseQueueCoversFact(
                 objectBindings,
                 relationBindings,
                 relationships,
                 queue,
                 fact
               ) ::
               ReverseFactSatisfied(state, fact)
    ensures ReverseQueueSatisfied(
              objectBindings,
              relationBindings,
              relationships,
              state,
              queue
            )
  {
    forall work <- queue
      ensures ReverseWorkSatisfied(
                objectBindings,
                relationBindings,
                relationships,
                state,
                work
              )
    {
      forall fact: ReverseFact |
        ReverseWorkCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          work,
          fact
        )
        ensures ReverseFactSatisfied(state, fact)
      {
        assert ReverseQueueCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            queue,
            fact
          );
      }
      ReverseWorkFactsSatisfiedImpliesSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        work
      );
    }
  }

  lemma SingletonReverseQueueSatisfactionYieldsWork(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    work: Indexed.ReverseWork
  )
    requires ReverseQueueSatisfied(
               objectBindings,
               relationBindings,
               relationships,
               state,
               [work]
             )
    ensures ReverseWorkSatisfied(
              objectBindings,
              relationBindings,
              relationships,
              state,
              work
            )
  {
  }

  lemma SeenGoalRulesAreSatisfied(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    indexedRules: seq<Indexed.IndexedRule>,
    state: Indexed.ReverseState,
    goal: Indexed.ReverseGoalKey
  )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ReverseStateInvariant(state)
    requires ReverseSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               indexedRules,
               state
             )
    requires goal in state.seenGoals
    ensures state.rulesByNode ==
            Indexed.RulesByNode(indexedRules)
    ensures ReverseQueueSatisfied(
              objectBindings,
              relationBindings,
              relationships,
              state,
              Indexed.ReverseGoalRuleWorks(
                if goal.node in state.rulesByNode
                then state.rulesByNode[goal.node]
                else [],
                goal,
                state.subjectType
              )
            )
  {
  }

  ghost predicate ReverseConsumerJoinsClosed(
    state: Indexed.ReverseState
  ) {
    forall key <- state.consumers.Keys,
      consumer <- state.consumers[key],
      grant <- state.seenGrants |
           grant.node == key.node &&
           grant.resourceEid == key.resourceEid ::
      Indexed.ReverseGrantKey(
        consumer.node,
        consumer.resourceEid,
        grant.subjectType,
        grant.subjectEid
      ) in state.seenGrants
  }

  ghost predicate ReverseSeenClosed(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    indexedRules: seq<Indexed.IndexedRule>,
    state: Indexed.ReverseState
  )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ReverseStateInvariant(state)
  {
    Indexed.ReverseGoalKey(
      state.rootNode,
      state.rootResourceEid
    ) in state.seenGoals &&
    (forall goal <- state.seenGoals ::
       var goalRules :=
         if goal.node in state.rulesByNode
         then state.rulesByNode[goal.node]
         else [];
       ReverseQueueSatisfied(
         objectBindings,
         relationBindings,
         relationships,
         state,
         Indexed.ReverseGoalRuleWorks(
           goalRules,
           goal,
           state.subjectType
         )
       )) &&
    state.rulesByNode == Indexed.RulesByNode(indexedRules) &&
    ReverseConsumerJoinsClosed(state)
  }

  lemma ExhaustedCanonicalReverseFactIsSatisfied(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    fact: ReverseFact
  )
    requires Indexed.ReverseStateInvariant(state)
    requires ReverseCoverageInvariant(
               objectBindings,
               relationBindings,
               relationships,
               state
             )
    requires state.queue == []
    requires state.pending.NoReversePending?
    requires ReverseCanonicalObligation(
               objectBindings,
               relationBindings,
               relationships,
               state,
               fact
             )
    ensures ReverseFactSatisfied(state, fact)
  {
    EmptyReverseQueueCoversNoFact(
      objectBindings,
      relationBindings,
      relationships,
      fact
    );
    NoPendingReverseScanCoversNoFact(
      objectBindings,
      relationBindings,
      relationships,
      fact
    );
  }

  lemma ExhaustedReverseCoverageIsClosed(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    indexedRules: seq<Indexed.IndexedRule>,
    state: Indexed.ReverseState
  )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ReverseStateInvariant(state)
    requires ReverseCoverageInvariant(
               objectBindings,
               relationBindings,
               relationships,
               state
             )
    requires state.rulesByNode ==
             Indexed.RulesByNode(indexedRules)
    requires state.queue == []
    requires state.pending.NoReversePending?
    ensures ReverseSeenClosed(
              objectBindings,
              relationBindings,
              relationships,
              indexedRules,
              state
            )
  {
    var rootGoal :=
      Indexed.ReverseGoalKey(
        state.rootNode,
        state.rootResourceEid
      );
    var rootFact := ReverseGoalFact(rootGoal);
    assert ReverseCanonicalObligation(
        objectBindings,
        relationBindings,
        relationships,
        state,
        rootFact
      );
    ExhaustedCanonicalReverseFactIsSatisfied(
      objectBindings,
      relationBindings,
      relationships,
      state,
      rootFact
    );
    assert rootGoal in state.seenGoals;
    forall goal <- state.seenGoals
      ensures
        var goalRules :=
          if goal.node in state.rulesByNode
          then state.rulesByNode[goal.node]
          else [];
        ReverseQueueSatisfied(
          objectBindings,
          relationBindings,
          relationships,
          state,
          Indexed.ReverseGoalRuleWorks(
            goalRules,
            goal,
            state.subjectType
          )
        )
    {
      var goalRules :=
        if goal.node in state.rulesByNode
        then state.rulesByNode[goal.node]
        else [];
      var work :=
        Indexed.ReverseGoalRuleWorks(
          goalRules,
          goal,
          state.subjectType
        );
      forall fact: ReverseFact |
        ReverseQueueCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          work,
          fact
        )
        ensures ReverseFactSatisfied(state, fact)
      {
        assert ReverseCanonicalObligation(
            objectBindings,
            relationBindings,
            relationships,
            state,
            fact
          );
        ExhaustedCanonicalReverseFactIsSatisfied(
          objectBindings,
          relationBindings,
          relationships,
          state,
          fact
        );
      }
      ReverseQueueFactsSatisfiedImpliesSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        work
      );
    }
    forall key <- state.consumers.Keys,
      consumer <- state.consumers[key],
      grant <- state.seenGrants |
           grant.node == key.node &&
           grant.resourceEid == key.resourceEid
      ensures Indexed.ReverseGrantKey(
                consumer.node,
                consumer.resourceEid,
                grant.subjectType,
                grant.subjectEid
              ) in state.seenGrants
    {
      var propagated :=
        Indexed.ReverseGrantKey(
          consumer.node,
          consumer.resourceEid,
          grant.subjectType,
          grant.subjectEid
        );
      var fact := ReverseGrantFact(propagated);
      assert Indexed.ReverseConsumerWork(
          consumer,
          grant
        ) == [Indexed.ReverseGrantWork(propagated)];
      assert ReverseWorkCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ReverseGrantWork(propagated),
          fact
        );
      assert ReverseQueueCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          Indexed.ReverseConsumerWork(
            consumer,
            grant
          ),
          fact
        );
      assert ReverseCanonicalObligation(
          objectBindings,
          relationBindings,
          relationships,
          state,
          fact
        );
      ExhaustedCanonicalReverseFactIsSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        fact
      );
    }
  }

  ghost function SeenReverseGrants(
    objectBindings: seq<Refinement.ObjectBinding>,
    seen: set<Indexed.ReverseGrantKey>
  ): set<Semantics.Grant>
  {
    set indexedGrant <- seen,
      subjectBinding <- objectBindings,
      resourceBinding <- objectBindings |
        subjectBinding.eid == indexedGrant.subjectEid &&
        subjectBinding.objectRef.typeName ==
        indexedGrant.subjectType &&
        resourceBinding.eid == indexedGrant.resourceEid &&
        resourceBinding.objectRef.typeName ==
        indexedGrant.node.resourceType ::
      Semantics.Grant(
        subjectBinding.objectRef,
        indexedGrant.node,
        resourceBinding.objectRef
      )
  }

  ghost predicate GrantGoalIsSeen(
    objectBindings: seq<Refinement.ObjectBinding>,
    seenGoals: set<Indexed.ReverseGoalKey>,
    grant: Semantics.Grant
  ) {
    exists resourceBinding <- objectBindings ::
      resourceBinding.objectRef == grant.resource &&
      Indexed.ReverseGoalKey(
        grant.node,
        resourceBinding.eid
      ) in seenGoals
  }

  ghost function ReplaceReachedReverseGrants(
    objectBindings: seq<Refinement.ObjectBinding>,
    grants: set<Semantics.Grant>,
    subjectType: string,
    seenGoals: set<Indexed.ReverseGoalKey>,
    seenGrants: set<Indexed.ReverseGrantKey>
  ): set<Semantics.Grant>
  {
    (set grant <- grants |
         grant.subject.typeName != subjectType ||
         !GrantGoalIsSeen(objectBindings, seenGoals, grant)) +
    SeenReverseGrants(objectBindings, seenGrants)
  }

  lemma SeenReverseGrantsAreSound(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    permissions: seq<Semantics.PermissionNode>,
    grants: set<Semantics.Grant>,
    indexedRules: seq<Indexed.IndexedRule>,
    state: Indexed.ReverseState
  )
    requires Refinement.UniqueObjectBindingEids(objectBindings)
    requires Refinement.ReverseStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               indexedRules,
               state
             )
    ensures SeenReverseGrants(
              objectBindings,
              state.seenGrants
            ) <= grants
  {
    forall semanticGrant |
      semanticGrant in SeenReverseGrants(
                         objectBindings,
                         state.seenGrants
                       )
      ensures semanticGrant in grants
    {
      var indexedGrant,
          subjectBinding,
          resourceBinding :|
        indexedGrant in state.seenGrants &&
        subjectBinding in objectBindings &&
        resourceBinding in objectBindings &&
        subjectBinding.eid == indexedGrant.subjectEid &&
        subjectBinding.objectRef.typeName ==
        indexedGrant.subjectType &&
        resourceBinding.eid == indexedGrant.resourceEid &&
        resourceBinding.objectRef.typeName ==
        indexedGrant.node.resourceType &&
        semanticGrant ==
        Semantics.Grant(
          subjectBinding.objectRef,
          indexedGrant.node,
          resourceBinding.objectRef
        );
      assert Refinement.ReverseGrantRefines(
          objectBindings,
          grants,
          indexedGrant
        );
      var soundResource,
          soundSubject :|
        soundResource in objectBindings &&
        soundSubject in objectBindings &&
        soundResource.eid == indexedGrant.resourceEid &&
        soundResource.objectRef.typeName ==
        indexedGrant.node.resourceType &&
        soundSubject.eid == indexedGrant.subjectEid &&
        soundSubject.objectRef.typeName ==
        indexedGrant.subjectType &&
        Semantics.Grant(
          soundSubject.objectRef,
          indexedGrant.node,
          soundResource.objectRef
        ) in grants;
      Refinement.UniqueObjectEidIdentifiesBinding(
        objectBindings,
        subjectBinding,
        soundSubject
      );
      Refinement.UniqueObjectEidIdentifiesBinding(
        objectBindings,
        resourceBinding,
        soundResource
      );
    }
  }

  lemma RuleAppearsInRulesByNode(
    indexedRules: seq<Indexed.IndexedRule>,
    rule: Indexed.IndexedRule
  )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires rule in indexedRules
    ensures rule.head in Indexed.RulesByNode(indexedRules)
    ensures rule in Indexed.RulesByNode(indexedRules)[rule.head]
    decreases |indexedRules|
  {
    if indexedRules[0] != rule {
      RuleAppearsInRulesByNode(indexedRules[1..], rule);
    }
  }

  lemma GoalRuleWorkIsSatisfied(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    state: Indexed.ReverseState,
    rules: seq<Indexed.IndexedRule>,
    goal: Indexed.ReverseGoalKey,
    subjectType: string,
    rule: Indexed.IndexedRule
  )
    requires Indexed.ValidIndexedRules(rules)
    requires 0 <= goal.resourceEid
    requires rule in rules
    requires ReverseQueueSatisfied(
               objectBindings,
               relationBindings,
               relationships,
               state,
               Indexed.ReverseGoalRuleWorks(
                 rules,
                 goal,
                 subjectType
               )
             )
    ensures ReverseQueueSatisfied(
              objectBindings,
              relationBindings,
              relationships,
              state,
              Indexed.ReverseGoalRuleWork(
                rule,
                goal,
                subjectType
              )
            )
    decreases |rules|
  {
    if rules[0] != rule {
      GoalRuleWorkIsSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        rules[1..],
        goal,
        subjectType,
        rule
      );
    }
  }

  lemma ReplacedReachedGrantIsSeen(
    objectBindings: seq<Refinement.ObjectBinding>,
    grants: set<Semantics.Grant>,
    subjectType: string,
    state: Indexed.ReverseState,
    semanticGrant: Semantics.Grant
  )
    requires semanticGrant in
               ReplaceReachedReverseGrants(
                 objectBindings,
                 grants,
                 subjectType,
                 state.seenGoals,
                 state.seenGrants
               )
    requires semanticGrant.subject.typeName == subjectType
    requires GrantGoalIsSeen(
               objectBindings,
               state.seenGoals,
               semanticGrant
             )
    ensures semanticGrant in
              SeenReverseGrants(
                objectBindings,
                state.seenGrants
              )
  {
  }

  lemma {:isolate_assertions}
    DirectReachedDerivationIsSeen(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    premiseGrants: set<Semantics.Grant>,
    normalizedRule: Semantics.NormalizedRule,
    semanticGrant: Semantics.Grant,
    state: Indexed.ReverseState
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
    requires Forward.AllRuleRelationsBound(
               normalizedRules,
               relationBindings
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ReverseStateInvariant(state)
    requires ReverseSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               indexedRules,
               state
             )
    requires normalizedRule in normalizedRules
    requires normalizedRule.DirectRelationRule?
    requires semanticGrant in
               Semantics.GrantUniverse(objects, permissions)
    requires semanticGrant.subject.typeName == state.subjectType
    requires GrantGoalIsSeen(
               objectBindings,
               state.seenGoals,
               semanticGrant
             )
    requires Semantics.RuleDerives(
               normalizedRule,
               relationships,
               premiseGrants,
               semanticGrant
             )
    ensures semanticGrant in
              SeenReverseGrants(
                objectBindings,
                state.seenGrants
              )
  {
    Forward.GrantUniverseMembershipHasCatalogShape(
      objects,
      permissions,
      semanticGrant
    );
    var resourceBinding :|
      resourceBinding in objectBindings &&
      resourceBinding.objectRef == semanticGrant.resource &&
      Indexed.ReverseGoalKey(
        semanticGrant.node,
        resourceBinding.eid
      ) in state.seenGoals;
    var subjectBinding :=
      Forward.ObjectCatalogBinding(
        objects,
        objectBindings,
        semanticGrant.subject
      );
    var relationshipIndex :|
      0 <= relationshipIndex < |relationships| &&
      relationships[relationshipIndex] ==
      Semantics.Relationship(
        semanticGrant.resource,
        normalizedRule.relationName,
        semanticGrant.subject
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
    RuleAppearsInRulesByNode(indexedRules, indexedRule);
    var goal :=
      Indexed.ReverseGoalKey(
        normalizedRule.head,
        resourceBinding.eid
      );
    assert goal in state.seenGoals;
    var goalRules := state.rulesByNode[goal.node];
    assert indexedRule in goalRules;
    assert ReverseQueueSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        Indexed.ReverseGoalRuleWorks(
          goalRules,
          goal,
          state.subjectType
        )
      );
    GoalRuleWorkIsSatisfied(
      objectBindings,
      relationBindings,
      relationships,
      state,
      goalRules,
      goal,
      state.subjectType,
      indexedRule
    );
    var projection :=
      Indexed.ResourceToSubjects(
        normalizedRule.head.resourceType,
        resourceBinding.eid,
        relationBinding.eid,
        normalizedRule.subjectType,
        Indexed.NoBound
      );
    assert Refinement.RelationshipProjectsTo(
        objectBindings,
        relationBindings,
        relationships,
        projection,
        subjectBinding.eid
      );
    var continuation :=
      Indexed.ReverseGrant(
        normalizedRule.head,
        resourceBinding.eid,
        normalizedRule.subjectType
      );
    var stream :=
      Indexed.ReverseStream(
        projection,
        [],
        true,
        continuation
      );
    assert Indexed.ReverseGoalRuleWork(
        indexedRule,
        goal,
        state.subjectType
      ) == [Indexed.ReverseStreamWork(stream)];
    assert ReverseQueueSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        [Indexed.ReverseStreamWork(stream)]
      );
    SingletonReverseQueueSatisfactionYieldsWork(
      objectBindings,
      relationBindings,
      relationships,
      state,
      Indexed.ReverseStreamWork(stream)
    );
    assert ReverseStreamSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        stream
      );
    assert ReverseContinuationSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        continuation,
        subjectBinding.eid
      );
    var indexedGrant :=
      Indexed.ReverseGrantKey(
        normalizedRule.head,
        resourceBinding.eid,
        normalizedRule.subjectType,
        subjectBinding.eid
      );
    assert indexedGrant in state.seenGrants;
  }

  lemma {:isolate_assertions}
    SelfPermissionReachedDerivationIsSeen(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    grants: set<Semantics.Grant>,
    premiseGrants: set<Semantics.Grant>,
    normalizedRule: Semantics.NormalizedRule,
    semanticGrant: Semantics.Grant,
    state: Indexed.ReverseState
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
    requires Indexed.ReverseStateInvariant(state)
    requires ReverseSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               indexedRules,
               state
             )
    requires normalizedRule in normalizedRules
    requires normalizedRule.SelfPermissionRule?
    requires semanticGrant in
               Semantics.GrantUniverse(objects, permissions)
    requires semanticGrant.subject.typeName == state.subjectType
    requires GrantGoalIsSeen(
               objectBindings,
               state.seenGoals,
               semanticGrant
             )
    requires premiseGrants ==
             ReplaceReachedReverseGrants(
               objectBindings,
               grants,
               state.subjectType,
               state.seenGoals,
               state.seenGrants
             )
    requires Semantics.RuleDerives(
               normalizedRule,
               relationships,
               premiseGrants,
               semanticGrant
             )
    ensures semanticGrant in
              SeenReverseGrants(
                objectBindings,
                state.seenGrants
              )
  {
    Forward.GrantUniverseMembershipHasCatalogShape(
      objects,
      permissions,
      semanticGrant
    );
    var resourceBinding :|
      resourceBinding in objectBindings &&
      resourceBinding.objectRef == semanticGrant.resource &&
      Indexed.ReverseGoalKey(
        semanticGrant.node,
        resourceBinding.eid
      ) in state.seenGoals;
    var targetNode :=
      Semantics.PermissionNode(
        normalizedRule.head.resourceType,
        normalizedRule.sourcePermission
      );
    var indexedRule :=
      Indexed.SelfPermissionRule(
        normalizedRule.head,
        targetNode
      );
    assert indexedRule in indexedRules;
    RuleAppearsInRulesByNode(indexedRules, indexedRule);
    var goal :=
      Indexed.ReverseGoalKey(
        normalizedRule.head,
        resourceBinding.eid
      );
    var targetGoal :=
      Indexed.ReverseGoalKey(
        targetNode,
        resourceBinding.eid
      );
    var consumer :=
      Indexed.ReverseConsumer(
        normalizedRule.head,
        resourceBinding.eid
      );
    var goalRules := state.rulesByNode[goal.node];
    assert indexedRule in goalRules;
    assert ReverseQueueSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        Indexed.ReverseGoalRuleWorks(
          goalRules,
          goal,
          state.subjectType
        )
      );
    GoalRuleWorkIsSatisfied(
      objectBindings,
      relationBindings,
      relationships,
      state,
      goalRules,
      goal,
      state.subjectType,
      indexedRule
    );
    assert Indexed.ReverseGoalRuleWork(
        indexedRule,
        goal,
        state.subjectType
      ) ==
           [Indexed.ReverseRegisterConsumerWork(
              targetGoal,
              consumer
            ),
            Indexed.ReverseGoalWork(targetGoal)];
    assert ReverseWorkSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        Indexed.ReverseRegisterConsumerWork(
          targetGoal,
          consumer
        )
      );
    assert ReverseWorkSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        Indexed.ReverseGoalWork(targetGoal)
      );
    assert targetGoal in state.seenGoals;
    assert targetGoal in state.consumers;
    assert consumer in state.consumers[targetGoal];
    var premise :=
      Semantics.Grant(
        semanticGrant.subject,
        targetNode,
        semanticGrant.resource
      );
    assert premise in premiseGrants;
    assert GrantGoalIsSeen(
        objectBindings,
        state.seenGoals,
        premise
      );
    ReplacedReachedGrantIsSeen(
      objectBindings,
      grants,
      state.subjectType,
      state,
      premise
    );
    var sourceGrant,
        sourceSubjectBinding,
        sourceResourceBinding :|
      sourceGrant in state.seenGrants &&
      sourceSubjectBinding in objectBindings &&
      sourceResourceBinding in objectBindings &&
      sourceSubjectBinding.eid == sourceGrant.subjectEid &&
      sourceSubjectBinding.objectRef.typeName ==
      sourceGrant.subjectType &&
      sourceResourceBinding.eid == sourceGrant.resourceEid &&
      sourceResourceBinding.objectRef.typeName ==
      sourceGrant.node.resourceType &&
      premise ==
      Semantics.Grant(
        sourceSubjectBinding.objectRef,
        sourceGrant.node,
        sourceResourceBinding.objectRef
      );
    Forward.UniqueObjectRefIdentifiesBinding(
      objectBindings,
      resourceBinding,
      sourceResourceBinding
    );
    assert sourceGrant.node == targetNode;
    assert sourceGrant.resourceEid == resourceBinding.eid;
    assert ReverseConsumerJoinsClosed(state);
    var propagated :=
      Indexed.ReverseGrantKey(
        normalizedRule.head,
        resourceBinding.eid,
        sourceGrant.subjectType,
        sourceGrant.subjectEid
      );
    assert propagated in state.seenGrants;
  }

  lemma {:isolate_assertions}
    ArrowRelationReachedDerivationIsSeen(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    premiseGrants: set<Semantics.Grant>,
    normalizedRule: Semantics.NormalizedRule,
    semanticGrant: Semantics.Grant,
    state: Indexed.ReverseState
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
    requires Forward.AllRuleRelationsBound(
               normalizedRules,
               relationBindings
             )
    requires Forward.RelationshipsWellTyped(
               relationBindings,
               relationships
             )
    requires Forward.RelationshipEndpointsCataloged(
               objects,
               relationships
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ReverseStateInvariant(state)
    requires ReverseSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               indexedRules,
               state
             )
    requires normalizedRule in normalizedRules
    requires normalizedRule.ArrowRelationRule?
    requires semanticGrant in
               Semantics.GrantUniverse(objects, permissions)
    requires semanticGrant.subject.typeName == state.subjectType
    requires GrantGoalIsSeen(
               objectBindings,
               state.seenGoals,
               semanticGrant
             )
    requires Semantics.RuleDerives(
               normalizedRule,
               relationships,
               premiseGrants,
               semanticGrant
             )
    ensures semanticGrant in
              SeenReverseGrants(
                objectBindings,
                state.seenGrants
              )
  {
    Forward.GrantUniverseMembershipHasCatalogShape(
      objects,
      permissions,
      semanticGrant
    );
    var resourceBinding :|
      resourceBinding in objectBindings &&
      resourceBinding.objectRef == semanticGrant.resource &&
      Indexed.ReverseGoalKey(
        semanticGrant.node,
        resourceBinding.eid
      ) in state.seenGoals;
    var subjectBinding :=
      Forward.ObjectCatalogBinding(
        objects,
        objectBindings,
        semanticGrant.subject
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
        semanticGrant.subject
      );
    var intermediateBinding :=
      Forward.ObjectCatalogBinding(
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
        semanticGrant.subject
      );
    var targetRelationship :=
      relationships[targetRelationshipIndex];
    var viaBinding :=
      Forward.TypedRelationshipBinding(
        relationBindings,
        relationships,
        viaRelationship
      );
    var targetBinding :=
      Forward.TypedRelationshipBinding(
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
    assert indexedRule in indexedRules;
    RuleAppearsInRulesByNode(indexedRules, indexedRule);
    var goal :=
      Indexed.ReverseGoalKey(
        normalizedRule.head,
        resourceBinding.eid
      );
    assert goal in state.seenGoals;
    SeenGoalRulesAreSatisfied(
      objectBindings,
      relationBindings,
      relationships,
      indexedRules,
      state,
      goal
    );
    var goalRules := state.rulesByNode[goal.node];
    assert indexedRule in goalRules;
    GoalRuleWorkIsSatisfied(
      objectBindings,
      relationBindings,
      relationships,
      state,
      goalRules,
      goal,
      state.subjectType,
      indexedRule
    );
    var projection :=
      Indexed.ResourceToSubjects(
        normalizedRule.head.resourceType,
        resourceBinding.eid,
        viaBinding.eid,
        viaRelationship.subject.typeName,
        Indexed.NoBound
      );
    var continuation :=
      Indexed.ReverseArrowRelation(
        normalizedRule.head,
        resourceBinding.eid,
        normalizedRule.subjectType,
        viaRelationship.subject.typeName,
        targetBinding.eid
      );
    var stream :=
      Indexed.ReverseStream(
        projection,
        [],
        true,
        continuation
      );
    assert Indexed.ReverseGoalRuleWork(
        indexedRule,
        goal,
        state.subjectType
      ) == [Indexed.ReverseStreamWork(stream)];
    assert ReverseQueueSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        [Indexed.ReverseStreamWork(stream)]
      );
    SingletonReverseQueueSatisfactionYieldsWork(
      objectBindings,
      relationBindings,
      relationships,
      state,
      Indexed.ReverseStreamWork(stream)
    );
    assert ReverseStreamSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        stream
      );
    assert Refinement.RelationshipProjectsTo(
        objectBindings,
        relationBindings,
        relationships,
        projection,
        intermediateBinding.eid
      );
    MoreReverseStreamProjectsToContinuation(
      objectBindings,
      relationBindings,
      relationships,
      state,
      stream,
      intermediateBinding.eid
    );
    assert ReverseContinuationSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        continuation,
        intermediateBinding.eid
      );
    assert Refinement.RelationshipProjectsTo(
        objectBindings,
        relationBindings,
        relationships,
        Indexed.ResourceToSubjects(
          viaRelationship.subject.typeName,
          intermediateBinding.eid,
          targetBinding.eid,
          normalizedRule.subjectType,
          Indexed.NoBound
        ),
        subjectBinding.eid
      );
    ReverseArrowRelationSatisfactionYieldsGrant(
      objectBindings,
      relationBindings,
      relationships,
      state,
      normalizedRule.head,
      resourceBinding.eid,
      normalizedRule.subjectType,
      viaRelationship.subject.typeName,
      targetBinding.eid,
      intermediateBinding.eid,
      subjectBinding.eid
    );
    var indexedGrant :=
      Indexed.ReverseGrantKey(
        normalizedRule.head,
        resourceBinding.eid,
        normalizedRule.subjectType,
        subjectBinding.eid
      );
    assert indexedGrant in state.seenGrants;
  }

  lemma {:isolate_assertions}
    ArrowPermissionReachedDerivationIsSeen(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    grants: set<Semantics.Grant>,
    premiseGrants: set<Semantics.Grant>,
    normalizedRule: Semantics.NormalizedRule,
    semanticGrant: Semantics.Grant,
    state: Indexed.ReverseState
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
    requires Forward.AllRuleRelationsBound(
               normalizedRules,
               relationBindings
             )
    requires Forward.RelationshipsWellTyped(
               relationBindings,
               relationships
             )
    requires Forward.RelationshipEndpointsCataloged(
               objects,
               relationships
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ReverseStateInvariant(state)
    requires ReverseSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               indexedRules,
               state
             )
    requires normalizedRule in normalizedRules
    requires normalizedRule.ArrowPermissionRule?
    requires semanticGrant in
               Semantics.GrantUniverse(objects, permissions)
    requires semanticGrant.subject.typeName == state.subjectType
    requires GrantGoalIsSeen(
               objectBindings,
               state.seenGoals,
               semanticGrant
             )
    requires premiseGrants ==
             ReplaceReachedReverseGrants(
               objectBindings,
               grants,
               state.subjectType,
               state.seenGoals,
               state.seenGrants
             )
    requires Semantics.RuleDerives(
               normalizedRule,
               relationships,
               premiseGrants,
               semanticGrant
             )
    ensures semanticGrant in
              SeenReverseGrants(
                objectBindings,
                state.seenGrants
              )
  {
    Forward.GrantUniverseMembershipHasCatalogShape(
      objects,
      permissions,
      semanticGrant
    );
    var resourceBinding :|
      resourceBinding in objectBindings &&
      resourceBinding.objectRef == semanticGrant.resource &&
      Indexed.ReverseGoalKey(
        semanticGrant.node,
        resourceBinding.eid
      ) in state.seenGoals;
    var viaRelationship :|
      viaRelationship in relationships &&
      viaRelationship.resource == semanticGrant.resource &&
      viaRelationship.relationName ==
      normalizedRule.viaRelation &&
      Semantics.Grant(
        semanticGrant.subject,
        Semantics.PermissionNode(
          viaRelationship.subject.typeName,
          normalizedRule.targetPermission
        ),
        viaRelationship.subject
      ) in premiseGrants;
    var intermediateBinding :=
      Forward.ObjectCatalogBinding(
        objects,
        objectBindings,
        viaRelationship.subject
      );
    var viaBinding :=
      Forward.TypedRelationshipBinding(
        relationBindings,
        relationships,
        viaRelationship
      );
    var targetNode :=
      Semantics.PermissionNode(
        viaRelationship.subject.typeName,
        normalizedRule.targetPermission
      );
    var indexedRule :=
      Indexed.ArrowPermissionRule(
        normalizedRule.head,
        viaBinding.eid,
        viaRelationship.subject.typeName,
        targetNode
      );
    assert indexedRule in indexedRules;
    RuleAppearsInRulesByNode(indexedRules, indexedRule);
    assert semanticGrant.node == normalizedRule.head;
    assert Indexed.ReverseGoalKey(
        semanticGrant.node,
        resourceBinding.eid
      ) in state.seenGoals;
    var goal :=
      Indexed.ReverseGoalKey(
        normalizedRule.head,
        resourceBinding.eid
      );
    assert goal in state.seenGoals;
    SeenGoalRulesAreSatisfied(
      objectBindings,
      relationBindings,
      relationships,
      indexedRules,
      state,
      goal
    );
    var goalRules := state.rulesByNode[goal.node];
    assert indexedRule in goalRules;
    GoalRuleWorkIsSatisfied(
      objectBindings,
      relationBindings,
      relationships,
      state,
      goalRules,
      goal,
      state.subjectType,
      indexedRule
    );
    var projection :=
      Indexed.ResourceToSubjects(
        normalizedRule.head.resourceType,
        resourceBinding.eid,
        viaBinding.eid,
        viaRelationship.subject.typeName,
        Indexed.NoBound
      );
    var continuation :=
      Indexed.ReverseArrowPermission(
        normalizedRule.head,
        resourceBinding.eid,
        targetNode
      );
    var stream :=
      Indexed.ReverseStream(
        projection,
        [],
        true,
        continuation
      );
    assert Indexed.ReverseGoalRuleWork(
        indexedRule,
        goal,
        state.subjectType
      ) == [Indexed.ReverseStreamWork(stream)];
    assert ReverseQueueSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        [Indexed.ReverseStreamWork(stream)]
      );
    SingletonReverseQueueSatisfactionYieldsWork(
      objectBindings,
      relationBindings,
      relationships,
      state,
      Indexed.ReverseStreamWork(stream)
    );
    assert ReverseStreamSatisfied(
        objectBindings,
        relationBindings,
        relationships,
        state,
        stream
      );
    RelationshipWitnessProjectsToReverse(
      objectBindings,
      relationBindings,
      relationships,
      resourceBinding,
      intermediateBinding,
      viaBinding,
      viaRelationship
    );
    assert semanticGrant.node == normalizedRule.head;
    assert semanticGrant.resource.typeName ==
           normalizedRule.head.resourceType;
    assert resourceBinding.objectRef.typeName ==
           normalizedRule.head.resourceType;
    assert intermediateBinding.objectRef.typeName ==
           viaRelationship.subject.typeName;
    assert projection ==
           Indexed.ResourceToSubjects(
             resourceBinding.objectRef.typeName,
             resourceBinding.eid,
             viaBinding.eid,
             intermediateBinding.objectRef.typeName,
             Indexed.NoBound
           );
    assert 0 <= intermediateBinding.eid;
    assert stream.more;
    assert !Indexed.ProjectionBound(stream.projection).Bound?;
    assert Forward.EidAboveBound(
        intermediateBinding.eid,
        Indexed.ProjectionBound(stream.projection)
      );
    assert stream.projection == projection;
    assert Refinement.RelationshipProjectsTo(
        objectBindings,
        relationBindings,
        relationships,
        stream.projection,
        intermediateBinding.eid
      );
    MoreReverseStreamProjectsToContinuation(
      objectBindings,
      relationBindings,
      relationships,
      state,
      stream,
      intermediateBinding.eid
    );
    var targetGoal :=
      Indexed.ReverseGoalKey(
        targetNode,
        intermediateBinding.eid
      );
    var consumer :=
      Indexed.ReverseConsumer(
        normalizedRule.head,
        resourceBinding.eid
      );
    ReverseArrowPermissionSatisfactionYieldsRegistration(
      objectBindings,
      relationBindings,
      relationships,
      state,
      normalizedRule.head,
      resourceBinding.eid,
      targetNode,
      intermediateBinding.eid
    );
    var premise :=
      Semantics.Grant(
        semanticGrant.subject,
        targetNode,
        viaRelationship.subject
      );
    assert premise in premiseGrants;
    assert GrantGoalIsSeen(
        objectBindings,
        state.seenGoals,
        premise
      );
    ReplacedReachedGrantIsSeen(
      objectBindings,
      grants,
      state.subjectType,
      state,
      premise
    );
    var sourceGrant,
        sourceSubjectBinding,
        sourceResourceBinding :|
      sourceGrant in state.seenGrants &&
      sourceSubjectBinding in objectBindings &&
      sourceResourceBinding in objectBindings &&
      sourceSubjectBinding.eid == sourceGrant.subjectEid &&
      sourceSubjectBinding.objectRef.typeName ==
      sourceGrant.subjectType &&
      sourceResourceBinding.eid == sourceGrant.resourceEid &&
      sourceResourceBinding.objectRef.typeName ==
      sourceGrant.node.resourceType &&
      premise ==
      Semantics.Grant(
        sourceSubjectBinding.objectRef,
        sourceGrant.node,
        sourceResourceBinding.objectRef
      );
    Forward.UniqueObjectRefIdentifiesBinding(
      objectBindings,
      intermediateBinding,
      sourceResourceBinding
    );
    assert sourceGrant.node == targetNode;
    assert sourceGrant.resourceEid == intermediateBinding.eid;
    var propagated :=
      Indexed.ReverseGrantKey(
        normalizedRule.head,
        resourceBinding.eid,
        sourceGrant.subjectType,
        sourceGrant.subjectEid
      );
    assert ReverseConsumerJoinsClosed(state);
    assert propagated in state.seenGrants;
  }

  lemma ReachedImmediateDerivationIsSeen(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    grants: set<Semantics.Grant>,
    premiseGrants: set<Semantics.Grant>,
    semanticGrant: Semantics.Grant,
    state: Indexed.ReverseState
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
    requires Forward.AllRuleRelationsBound(
               normalizedRules,
               relationBindings
             )
    requires Forward.RelationshipsWellTyped(
               relationBindings,
               relationships
             )
    requires Forward.RelationshipEndpointsCataloged(
               objects,
               relationships
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ReverseStateInvariant(state)
    requires ReverseSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               indexedRules,
               state
             )
    requires semanticGrant in
               Semantics.GrantUniverse(objects, permissions)
    requires semanticGrant.subject.typeName == state.subjectType
    requires GrantGoalIsSeen(
               objectBindings,
               state.seenGoals,
               semanticGrant
             )
    requires premiseGrants ==
             ReplaceReachedReverseGrants(
               objectBindings,
               grants,
               state.subjectType,
               state.seenGoals,
               state.seenGrants
             )
    requires Semantics.AnyRuleDerives(
               normalizedRules,
               relationships,
               premiseGrants,
               semanticGrant
             )
    ensures semanticGrant in
              SeenReverseGrants(
                objectBindings,
                state.seenGrants
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
      DirectReachedDerivationIsSeen(
        objects,
        relations,
        permissions,
        normalizedRules,
        indexedRules,
        relationships,
        objectBindings,
        relationBindings,
        premiseGrants,
        normalizedRule,
        semanticGrant,
        state
      );
    }
    case SelfPermissionRule(_, _) => {
      SelfPermissionReachedDerivationIsSeen(
        objects,
        permissions,
        normalizedRules,
        indexedRules,
        relationships,
        objectBindings,
        relationBindings,
        grants,
        premiseGrants,
        normalizedRule,
        semanticGrant,
        state
      );
    }
    case ArrowRelationRule(_, _, _, _) => {
      ArrowRelationReachedDerivationIsSeen(
        objects,
        relations,
        permissions,
        normalizedRules,
        indexedRules,
        relationships,
        objectBindings,
        relationBindings,
        premiseGrants,
        normalizedRule,
        semanticGrant,
        state
      );
    }
    case ArrowPermissionRule(_, _, _) => {
      ArrowPermissionReachedDerivationIsSeen(
        objects,
        permissions,
        normalizedRules,
        indexedRules,
        relationships,
        objectBindings,
        relationBindings,
        grants,
        premiseGrants,
        normalizedRule,
        semanticGrant,
        state
      );
    }
  }

  lemma ReverseReplacementIsClosedUnderImmediateConsequences(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    grants: set<Semantics.Grant>,
    state: Indexed.ReverseState
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
    requires Forward.AllRuleRelationsBound(
               normalizedRules,
               relationBindings
             )
    requires Forward.RelationshipsWellTyped(
               relationBindings,
               relationships
             )
    requires Forward.RelationshipEndpointsCataloged(
               objects,
               relationships
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ReverseStateInvariant(state)
    requires ReverseSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               indexedRules,
               state
             )
    requires Forward.NormalizedLeastFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires Refinement.ReverseStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               indexedRules,
               state
             )
    ensures Semantics.ImmediateConsequences(
              objects,
              permissions,
              normalizedRules,
              relationships,
              ReplaceReachedReverseGrants(
                objectBindings,
                grants,
                state.subjectType,
                state.seenGoals,
                state.seenGrants
              )
            ) <=
            ReplaceReachedReverseGrants(
              objectBindings,
              grants,
              state.subjectType,
              state.seenGoals,
              state.seenGrants
            )
  {
    var seenReverseGrants :=
      SeenReverseGrants(
        objectBindings,
        state.seenGrants
      );
    var replacement :=
      ReplaceReachedReverseGrants(
        objectBindings,
        grants,
        state.subjectType,
        state.seenGoals,
        state.seenGrants
      );
    SeenReverseGrantsAreSound(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      indexedRules,
      state
    );
    assert seenReverseGrants <= grants;
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
        if semanticGrant.subject.typeName != state.subjectType ||
           !GrantGoalIsSeen(
             objectBindings,
             state.seenGoals,
             semanticGrant
           )
        {
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
          assert Semantics.AnyRuleDerives(
              normalizedRules,
              relationships,
              replacement,
              semanticGrant
            );
          ReachedImmediateDerivationIsSeen(
            objects,
            relations,
            permissions,
            normalizedRules,
            indexedRules,
            relationships,
            objectBindings,
            relationBindings,
            grants,
            replacement,
            semanticGrant,
            state
          );
          assert semanticGrant in seenReverseGrants;
          assert semanticGrant in replacement;
        }
      }
    }
  }

  lemma ExhaustedReverseTraversalIsComplete(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    grants: set<Semantics.Grant>,
    state: Indexed.ReverseState
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
    requires Forward.AllRuleRelationsBound(
               normalizedRules,
               relationBindings
             )
    requires Forward.RelationshipsWellTyped(
               relationBindings,
               relationships
             )
    requires Forward.RelationshipEndpointsCataloged(
               objects,
               relationships
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ReverseStateInvariant(state)
    requires ReverseSeenClosed(
               objectBindings,
               relationBindings,
               relationships,
               indexedRules,
               state
             )
    requires Forward.NormalizedLeastFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires Refinement.ReverseStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               indexedRules,
               state
             )
    ensures forall semanticGrant <- grants |
                   semanticGrant.subject.typeName == state.subjectType &&
                   GrantGoalIsSeen(
                     objectBindings,
                     state.seenGoals,
                     semanticGrant
                   ) ::
              semanticGrant in SeenReverseGrants(
                                 objectBindings,
                                 state.seenGrants
                               )
  {
    var seenReverseGrants :=
      SeenReverseGrants(
        objectBindings,
        state.seenGrants
      );
    var replacement :=
      ReplaceReachedReverseGrants(
        objectBindings,
        grants,
        state.subjectType,
        state.seenGoals,
        state.seenGrants
      );
    SeenReverseGrantsAreSound(
      objectBindings,
      relationBindings,
      relationships,
      permissions,
      grants,
      indexedRules,
      state
    );
    assert seenReverseGrants <= grants;
    assert replacement <= grants;
    assert replacement <=
           Semantics.GrantUniverse(objects, permissions);
    ReverseReplacementIsClosedUnderImmediateConsequences(
      objects,
      relations,
      permissions,
      normalizedRules,
      indexedRules,
      relationships,
      objectBindings,
      relationBindings,
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
           semanticGrant.subject.typeName == state.subjectType &&
           GrantGoalIsSeen(
             objectBindings,
             state.seenGoals,
             semanticGrant
           )
      ensures semanticGrant in seenReverseGrants
    {
      assert semanticGrant in replacement;
    }
  }

  method InitializeReverseCompleteRefined(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    grants: set<Semantics.Grant>,
    subjectType: string,
    rootNode: Semantics.PermissionNode,
    rootResourceEid: int,
    resultType: string,
    mode: Indexed.RenderMode,
    chunkSize: nat,
    limits: Indexed.IndexedLimits
  ) returns (outcome: Indexed.ReverseInit)
    requires Refinement.ExactObjectCatalog(
               objects,
               objectBindings
             )
    requires Refinement.ExactRelationCatalog(
               relations,
               relationBindings
             )
    requires Forward.NormalizedLeastFixedPoint(
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
    requires 0 < |subjectType|
    requires Indexed.ValidPermissionNode(rootNode)
    requires rootNode in permissions
    requires 0 <= rootResourceEid
    requires exists rootResource <- objectBindings ::
               rootResource.eid == rootResourceEid &&
               rootResource.objectRef.typeName ==
               rootNode.resourceType
    requires 0 < |resultType|
    requires Indexed.ValidRenderMode(mode)
    requires 0 < chunkSize
    requires forall rule <- indexedRules ::
               Refinement.IndexedRulePermissionClosed(
                 rule,
                 permissions
               )
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
              Refinement.ReverseStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                permissions,
                grants,
                indexedRules,
                outcome.state
              ) &&
              ReverseCoverageInvariant(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state
              )
  {
    outcome :=
      Refinement.InitializeReverseRefined(
        objects,
        relations,
        permissions,
        normalizedRules,
        indexedRules,
        relationships,
        objectBindings,
        relationBindings,
        grants,
        subjectType,
        rootNode,
        rootResourceEid,
        resultType,
        mode,
        chunkSize,
        limits
      );
    if outcome.ReverseInitialized? {
      ReverseInitializationEstablishesCoverage(
        objectBindings,
        relationBindings,
        relationships,
        outcome.state
      );
    }
  }

  method {:isolate_assertions} StepReverseCompleteRefined(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    grants: set<Semantics.Grant>,
    state: Indexed.ReverseState,
    limits: Indexed.IndexedLimits
  ) returns (outcome: Indexed.ReverseStep)
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
               Refinement.IndexedRulePermissionClosed(
                 rule,
                 permissions
               )
    requires Indexed.ReverseStateInvariant(state)
    requires Indexed.CountersWithinLimits(state.counters, limits)
    requires state.pending.NoReversePending?
    requires Indexed.ValidReverseQueuedEids(state.queue)
    requires Refinement.ReverseStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               indexedRules,
               state
             )
    requires ReverseCoverageInvariant(
               objectBindings,
               relationBindings,
               relationships,
               state
             )
    ensures outcome == Indexed.ReverseStepSpec(state, limits)
    ensures Refinement.ReverseStateRefines(
              objectBindings,
              relationBindings,
              relationships,
              permissions,
              grants,
              indexedRules,
              outcome.state
            )
    ensures ReverseCoverageInvariant(
              objectBindings,
              relationBindings,
              relationships,
              outcome.state
            )
  {
    outcome :=
      Refinement.StepReverseRefined(
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
        limits
      );
    ReverseStepPreservesCoverage(
      objectBindings,
      relationBindings,
      relationships,
      state,
      limits,
      outcome
    );
  }

  method {:isolate_assertions} ResumeReverseCompleteRefined(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
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
    requires Refinement.ReverseStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               indexedRules,
               state
             )
    requires ReverseCoverageInvariant(
               objectBindings,
               relationBindings,
               relationships,
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
            Indexed.ReverseResumeSpec(
              state,
              response,
              limits
            )
    ensures outcome.ReverseScanResumed? ==>
              Refinement.ReverseStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                permissions,
                grants,
                indexedRules,
                outcome.state
              ) &&
              ReverseCoverageInvariant(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state
              )
    ensures outcome.ReverseScanLimitExceeded? ==>
              Refinement.ReverseStateRefines(
                objectBindings,
                relationBindings,
                relationships,
                permissions,
                grants,
                indexedRules,
                outcome.state
              ) &&
              ReverseCoverageInvariant(
                objectBindings,
                relationBindings,
                relationships,
                outcome.state
              )
    ensures !outcome.ReverseScanRejected?
  {
    outcome :=
      Refinement.ResumeReverseRefined(
        objectBindings,
        relationBindings,
        relationships,
        permissions,
        grants,
        indexedRules,
        state,
        response,
        fullProjectionValues,
        limits
      );
    ReverseCertifiedResumePreservesCoverage(
      objectBindings,
      relationBindings,
      relationships,
      state,
      response,
      fullProjectionValues,
      limits,
      outcome
    );
  }

  lemma ExhaustedReverseTraversalRefinesLeastFixedPoint(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationships: seq<Semantics.Relationship>,
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    grants: set<Semantics.Grant>,
    state: Indexed.ReverseState
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
    requires Forward.AllRuleRelationsBound(
               normalizedRules,
               relationBindings
             )
    requires Forward.RelationshipsWellTyped(
               relationBindings,
               relationships
             )
    requires Forward.RelationshipEndpointsCataloged(
               objects,
               relationships
             )
    requires Indexed.ValidIndexedRules(indexedRules)
    requires Indexed.ReverseStateInvariant(state)
    requires state.rulesByNode ==
             Indexed.RulesByNode(indexedRules)
    requires Forward.NormalizedLeastFixedPoint(
               objects,
               permissions,
               normalizedRules,
               relationships,
               grants
             )
    requires Refinement.ReverseStateRefines(
               objectBindings,
               relationBindings,
               relationships,
               permissions,
               grants,
               indexedRules,
               state
             )
    requires ReverseCoverageInvariant(
               objectBindings,
               relationBindings,
               relationships,
               state
             )
    requires state.queue == []
    requires state.pending.NoReversePending?
    ensures forall semanticGrant <- grants |
                   semanticGrant.subject.typeName == state.subjectType &&
                   GrantGoalIsSeen(
                     objectBindings,
                     state.seenGoals,
                     semanticGrant
                   ) ::
              semanticGrant in SeenReverseGrants(
                                 objectBindings,
                                 state.seenGrants
                               )
  {
    ExhaustedReverseCoverageIsClosed(
      objectBindings,
      relationBindings,
      relationships,
      indexedRules,
      state
    );
    ExhaustedReverseTraversalIsComplete(
      objects,
      relations,
      permissions,
      normalizedRules,
      indexedRules,
      relationships,
      objectBindings,
      relationBindings,
      grants,
      state
    );
  }
}

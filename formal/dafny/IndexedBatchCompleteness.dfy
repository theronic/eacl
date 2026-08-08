include "Semantics.dfy"
include "IndexedTraversal.dfy"
include "IndexedBatching.dfy"
include "IndexedRefinement.dfy"
include "IndexedForwardCompleteness.dfy"
include "IndexedReverseCompleteness.dfy"

module IndexedBatchCompleteness {
  import Semantics
  import Indexed = IndexedTraversal
  import Batching = IndexedBatching
  import Refinement = IndexedRefinement
  import Forward = IndexedForwardCompleteness
  import Reverse = IndexedReverseCompleteness

  ghost function ForwardPendingWork(
    pending: Indexed.ForwardPending
  ): Indexed.ForwardWork
    requires pending.AwaitingForwardScan?
  {
    Indexed.ForwardStreamWork(
      Indexed.ForwardStream(
        pending.command.projection,
        [],
        true,
        pending.continuation
      )
    )
  }

  ghost function ReversePendingWork(
    pending: Indexed.ReversePending
  ): Indexed.ReverseWork
    requires pending.AwaitingReverseScan?
  {
    Indexed.ReverseStreamWork(
      Indexed.ReverseStream(
        pending.command.projection,
        [],
        true,
        pending.continuation
      )
    )
  }

  // The ghost view turns every outstanding scan back into the open stream
  // work item it represents. Completeness can therefore keep using the
  // established queue-cover predicates even though executable state carries
  // a bounded sequence of pending scans outside the FIFO queue.
  ghost function ForwardPendingGhostView(
    pending: seq<Indexed.ForwardPending>
  ): seq<Indexed.ForwardWork>
    requires forall item <- pending :: item.AwaitingForwardScan?
    ensures |ForwardPendingGhostView(pending)| == |pending|
    decreases |pending|
  {
    if |pending| == 0 then
      []
    else
      [ForwardPendingWork(pending[0])] +
      ForwardPendingGhostView(pending[1..])
  }

  ghost function ReversePendingGhostView(
    pending: seq<Indexed.ReversePending>
  ): seq<Indexed.ReverseWork>
    requires forall item <- pending :: item.AwaitingReverseScan?
    ensures |ReversePendingGhostView(pending)| == |pending|
    decreases |pending|
  {
    if |pending| == 0 then
      []
    else
      [ReversePendingWork(pending[0])] +
      ReversePendingGhostView(pending[1..])
  }

  ghost predicate ForwardBatchCovers(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    pending: seq<Indexed.ForwardPending>,
    candidate: Indexed.ForwardGrantKey
  )
    requires forall item <- pending :: item.AwaitingForwardScan?
  {
    Forward.ForwardQueueCovers(
      objectBindings,
      relationBindings,
      relationships,
      ForwardPendingGhostView(pending),
      candidate
    )
  }

  ghost predicate ReverseBatchCoversFact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    pending: seq<Indexed.ReversePending>,
    fact: Reverse.ReverseFact
  )
    requires forall item <- pending :: item.AwaitingReverseScan?
  {
    Reverse.ReverseQueueCoversFact(
      objectBindings,
      relationBindings,
      relationships,
      ReversePendingGhostView(pending),
      fact
    )
  }

  lemma ForwardPendingGhostViewIsExact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    pending: seq<Indexed.ForwardPending>,
    candidate: Indexed.ForwardGrantKey
  )
    requires forall item <- pending :: item.AwaitingForwardScan?
    ensures ForwardBatchCovers(
              objectBindings,
              relationBindings,
              relationships,
              pending,
              candidate
            ) <==>
            (exists item <- pending ::
               Forward.PendingForwardCovers(
                 objectBindings,
                 relationBindings,
                 relationships,
                 item,
                 candidate
               ))
    decreases |pending|
  {
    if 0 < |pending| {
      var head := [ForwardPendingWork(pending[0])];
      var tail := ForwardPendingGhostView(pending[1..]);
      ForwardPendingGhostViewIsExact(
        objectBindings,
        relationBindings,
        relationships,
        pending[1..],
        candidate
      );
      assert ForwardPendingGhostView(pending) == head + tail;
      assert Forward.ForwardWorkCovers(
          objectBindings,
          relationBindings,
          relationships,
          head[0],
          candidate
        ) <==>
             Forward.PendingForwardCovers(
               objectBindings,
               relationBindings,
               relationships,
               pending[0],
               candidate
             );
      if ForwardBatchCovers(
          objectBindings,
          relationBindings,
          relationships,
          pending,
          candidate
        ) {
        Forward.QueueCoverComesFromLeftOrRight(
          objectBindings,
          relationBindings,
          relationships,
          head,
          tail,
          candidate
        );
      } else if exists item <- pending ::
          Forward.PendingForwardCovers(
            objectBindings,
            relationBindings,
            relationships,
            item,
            candidate
          ) {
        var item :| item in pending &&
                    Forward.PendingForwardCovers(
                      objectBindings,
                      relationBindings,
                      relationships,
                      item,
                      candidate
                    );
        if item == pending[0] {
          assert Forward.ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              head,
              candidate
            );
        } else {
          assert item in pending[1..];
          assert Forward.ForwardQueueCovers(
              objectBindings,
              relationBindings,
              relationships,
              tail,
              candidate
            );
        }
        Forward.LeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          head,
          tail,
          candidate
        );
      }
    }
  }

  lemma ReversePendingGhostViewIsExact(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    pending: seq<Indexed.ReversePending>,
    fact: Reverse.ReverseFact
  )
    requires forall item <- pending :: item.AwaitingReverseScan?
    ensures ReverseBatchCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              pending,
              fact
            ) <==>
            (exists item <- pending ::
               Reverse.PendingReverseCoversFact(
                 objectBindings,
                 relationBindings,
                 relationships,
                 item,
                 fact
               ))
    decreases |pending|
  {
    if 0 < |pending| {
      var head := [ReversePendingWork(pending[0])];
      var tail := ReversePendingGhostView(pending[1..]);
      ReversePendingGhostViewIsExact(
        objectBindings,
        relationBindings,
        relationships,
        pending[1..],
        fact
      );
      assert ReversePendingGhostView(pending) == head + tail;
      assert Reverse.ReverseWorkCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          head[0],
          fact
        ) <==>
             Reverse.PendingReverseCoversFact(
               objectBindings,
               relationBindings,
               relationships,
               pending[0],
               fact
             );
      if ReverseBatchCoversFact(
          objectBindings,
          relationBindings,
          relationships,
          pending,
          fact
        ) {
        Reverse.ReverseQueueCoverComesFromLeftOrRight(
          objectBindings,
          relationBindings,
          relationships,
          head,
          tail,
          fact
        );
      } else if exists item <- pending ::
          Reverse.PendingReverseCoversFact(
            objectBindings,
            relationBindings,
            relationships,
            item,
            fact
          ) {
        var item :| item in pending &&
                    Reverse.PendingReverseCoversFact(
                      objectBindings,
                      relationBindings,
                      relationships,
                      item,
                      fact
                    );
        if item == pending[0] {
          assert Reverse.ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              head,
              fact
            );
        } else {
          assert item in pending[1..];
          assert Reverse.ReverseQueueCoversFact(
              objectBindings,
              relationBindings,
              relationships,
              tail,
              fact
            );
        }
        Reverse.ReverseLeftOrRightCoverImpliesConcatenatedCover(
          objectBindings,
          relationBindings,
          relationships,
          head,
          tail,
          fact
        );
      }
    }
  }

  ghost predicate ForwardBatchCoverageInvariant(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string,
    subjectEid: int,
    batch: Batching.ForwardBatchState
  )
    requires Indexed.ValidIndexedRules(seedRules)
    requires 0 <= subjectEid
    requires Batching.ForwardBatchInvariant(batch)
  {
    forall candidate: Indexed.ForwardGrantKey ::
      (
        Forward.ForwardSeedGrant(
          objectBindings,
          relationBindings,
          relationships,
          seedRules,
          subjectType,
          subjectEid,
          candidate
        ) ||
        exists source <- batch.state.seen ::
          Forward.ForwardSuccessorGrant(
            objectBindings,
            relationBindings,
            relationships,
            batch.state.consumers,
            source,
            candidate
          )
      )
      ==>
        candidate in batch.state.seen ||
        Forward.ForwardQueueCovers(
          objectBindings,
          relationBindings,
          relationships,
          batch.state.queue,
          candidate
        ) ||
        ForwardBatchCovers(
          objectBindings,
          relationBindings,
          relationships,
          batch.pending,
          candidate
        )
  }

  ghost predicate ReverseBatchCoverageInvariant(
    objectBindings: seq<Refinement.ObjectBinding>,
    relationBindings: seq<Refinement.RelationBinding>,
    relationships: seq<Semantics.Relationship>,
    batch: Batching.ReverseBatchState
  )
    requires Batching.ReverseBatchInvariant(batch)
  {
    Reverse.ReverseIndexCoherence(batch.state) &&
    forall fact: Reverse.ReverseFact |
      Reverse.ReverseCanonicalObligation(
        objectBindings,
        relationBindings,
        relationships,
        batch.state,
        fact
      ) ::
      Reverse.ReverseFactSatisfied(batch.state, fact) ||
      Reverse.ReverseQueueCoversFact(
        objectBindings,
        relationBindings,
        relationships,
        batch.state.queue,
        fact
      ) ||
      ReverseBatchCoversFact(
        objectBindings,
        relationBindings,
        relationships,
        batch.pending,
        fact
      )
  }
}

include "IndexedTraversal.dfy"

module IndexedBatching {
  import Indexed = IndexedTraversal

  const DefaultScanBatchSize: nat := 64

  datatype ForwardBatchState = ForwardBatchState(
    state: Indexed.ForwardState,
    pending: seq<Indexed.ForwardPending>
  )

  datatype ReverseBatchState = ReverseBatchState(
    state: Indexed.ReverseState,
    pending: seq<Indexed.ReversePending>
  )

  datatype ForwardBatchStep =
    | ForwardBatchYielded(state: Indexed.ForwardState)
    | ForwardNeedScans(
        batch: ForwardBatchState,
        commands: seq<Indexed.ScanCommand>
      )
    | ForwardBatchComplete(state: Indexed.ForwardState)
    | ForwardBatchRenderRejected(
        error: Indexed.RenderError,
        state: Indexed.ForwardState
      )
    | ForwardBatchLimitExceeded(
        kind: Indexed.IndexedLimitKind,
        state: Indexed.ForwardState
      )

  datatype ReverseBatchStep =
    | ReverseBatchYielded(state: Indexed.ReverseState)
    | ReverseNeedScans(
        batch: ReverseBatchState,
        commands: seq<Indexed.ScanCommand>
      )
    | ReverseBatchComplete(state: Indexed.ReverseState)
    | ReverseBatchRenderRejected(
        error: Indexed.RenderError,
        state: Indexed.ReverseState
      )
    | ReverseBatchLimitExceeded(
        kind: Indexed.IndexedLimitKind,
        state: Indexed.ReverseState
      )

  function ClearForwardPending(
    state: Indexed.ForwardState
  ): Indexed.ForwardState {
    Indexed.ForwardState(
      state.queue,
      state.consumers,
      state.seen,
      state.emitted,
      state.render,
      state.rootNode,
      state.resultType,
      state.chunkSize,
      Indexed.NoForwardPending,
      state.nextRequestId,
      state.counters
    )
  }

  function ClearReversePending(
    state: Indexed.ReverseState
  ): Indexed.ReverseState {
    Indexed.ReverseState(
      state.queue,
      state.rulesByNode,
      state.seenGoals,
      state.seenGrants,
      state.grantsByGoal,
      state.consumers,
      state.seenConsumers,
      state.emitted,
      state.render,
      state.rootNode,
      state.rootResourceEid,
      state.resultType,
      state.subjectType,
      state.chunkSize,
      Indexed.NoReversePending,
      state.nextRequestId,
      state.counters
    )
  }

  function WithForwardPending(
    state: Indexed.ForwardState,
    pending: Indexed.ForwardPending
  ): Indexed.ForwardState {
    Indexed.ForwardState(
      state.queue,
      state.consumers,
      state.seen,
      state.emitted,
      state.render,
      state.rootNode,
      state.resultType,
      state.chunkSize,
      pending,
      state.nextRequestId,
      state.counters
    )
  }

  function WithReversePending(
    state: Indexed.ReverseState,
    pending: Indexed.ReversePending
  ): Indexed.ReverseState {
    Indexed.ReverseState(
      state.queue,
      state.rulesByNode,
      state.seenGoals,
      state.seenGrants,
      state.grantsByGoal,
      state.consumers,
      state.seenConsumers,
      state.emitted,
      state.render,
      state.rootNode,
      state.rootResourceEid,
      state.resultType,
      state.subjectType,
      state.chunkSize,
      pending,
      state.nextRequestId,
      state.counters
    )
  }

  predicate ForwardPendingFits(
    state: Indexed.ForwardState,
    pending: Indexed.ForwardPending
  ) {
    pending.AwaitingForwardScan? &&
    pending.command.chunkSize == state.chunkSize &&
    pending.command.requestScope == state.nextRequestId.scope &&
    pending.command.requestId < state.nextRequestId.next
  }

  predicate ReversePendingFits(
    state: Indexed.ReverseState,
    pending: Indexed.ReversePending
  ) {
    pending.AwaitingReverseScan? &&
    pending.command.chunkSize == state.chunkSize &&
    pending.command.requestScope == state.nextRequestId.scope &&
    pending.command.requestId < state.nextRequestId.next &&
    Indexed.ValidReverseContinuationEids(pending.continuation)
  }

  predicate ForwardBatchInvariant(batch: ForwardBatchState) {
    Indexed.ForwardStateInvariant(batch.state) &&
    batch.state.pending.NoForwardPending? &&
    0 < |batch.pending| &&
    (forall pending <- batch.pending ::
       ForwardPendingFits(batch.state, pending))
  }

  predicate ReverseBatchInvariant(batch: ReverseBatchState) {
    Indexed.ReverseStateInvariant(batch.state) &&
    batch.state.pending.NoReversePending? &&
    0 < |batch.pending| &&
    (forall pending <- batch.pending ::
       ReversePendingFits(batch.state, pending))
  }

  ghost function ForwardPendingGhostView(
    batch: ForwardBatchState
  ): seq<Indexed.ForwardPending> {
    batch.pending
  }

  ghost function ReversePendingGhostView(
    batch: ReverseBatchState
  ): seq<Indexed.ReversePending> {
    batch.pending
  }

  function ForwardCommands(
    pending: seq<Indexed.ForwardPending>
  ): seq<Indexed.ScanCommand>
    requires forall item <- pending :: item.AwaitingForwardScan?
    ensures |ForwardCommands(pending)| == |pending|
    decreases |pending|
  {
    if |pending| == 0 then
      []
    else
      [pending[0].command] + ForwardCommands(pending[1..])
  }

  function ReverseCommands(
    pending: seq<Indexed.ReversePending>
  ): seq<Indexed.ScanCommand>
    requires forall item <- pending :: item.AwaitingReverseScan?
    ensures |ReverseCommands(pending)| == |pending|
    decreases |pending|
  {
    if |pending| == 0 then
      []
    else
      [pending[0].command] + ReverseCommands(pending[1..])
  }

  lemma WithForwardPendingPreservesInvariant(
    state: Indexed.ForwardState,
    pending: Indexed.ForwardPending
  )
    requires Indexed.ForwardStateInvariant(state)
    requires state.pending.NoForwardPending?
    requires ForwardPendingFits(state, pending)
    ensures Indexed.ForwardStateInvariant(
              WithForwardPending(state, pending)
            )
  {
  }

  lemma WithReversePendingPreservesInvariant(
    state: Indexed.ReverseState,
    pending: Indexed.ReversePending
  )
    requires Indexed.ReverseStateInvariant(state)
    requires state.pending.NoReversePending?
    requires ReversePendingFits(state, pending)
    ensures Indexed.ReverseStateInvariant(
              WithReversePending(state, pending)
            )
  {
  }

  lemma ForwardPendingFitsFrame(
    before: Indexed.ForwardState,
    after: Indexed.ForwardState,
    pending: Indexed.ForwardPending
  )
    requires ForwardPendingFits(before, pending)
    requires after.chunkSize == before.chunkSize
    requires after.nextRequestId == before.nextRequestId
    ensures ForwardPendingFits(after, pending)
  {
  }

  lemma ForwardPendingFitsMonotonic(
    before: Indexed.ForwardState,
    after: Indexed.ForwardState,
    pending: Indexed.ForwardPending
  )
    requires ForwardPendingFits(before, pending)
    requires after.chunkSize == before.chunkSize
    requires after.nextRequestId.scope == before.nextRequestId.scope
    requires before.nextRequestId.next <= after.nextRequestId.next
    ensures ForwardPendingFits(after, pending)
  {
  }

  lemma ReversePendingFitsFrame(
    before: Indexed.ReverseState,
    after: Indexed.ReverseState,
    pending: Indexed.ReversePending
  )
    requires ReversePendingFits(before, pending)
    requires after.chunkSize == before.chunkSize
    requires after.nextRequestId == before.nextRequestId
    ensures ReversePendingFits(after, pending)
  {
  }

  lemma ReversePendingFitsMonotonic(
    before: Indexed.ReverseState,
    after: Indexed.ReverseState,
    pending: Indexed.ReversePending
  )
    requires ReversePendingFits(before, pending)
    requires after.chunkSize == before.chunkSize
    requires after.nextRequestId.scope == before.nextRequestId.scope
    requires before.nextRequestId.next <= after.nextRequestId.next
    ensures ReversePendingFits(after, pending)
  {
  }

  method DriveForwardScans(
    state: Indexed.ForwardState,
    limits: Indexed.IndexedLimits,
    fuel: nat,
    batchSize: nat
  ) returns (outcome: ForwardBatchStep)
    requires 0 < batchSize
    requires Indexed.ForwardStateInvariant(state)
    requires Indexed.CountersWithinLimits(state.counters, limits)
    requires state.pending.NoForwardPending?
    requires Indexed.ValidForwardQueuedEids(state.queue)
    ensures outcome.ForwardBatchYielded? ==>
              Indexed.ForwardStateInvariant(outcome.state)
    ensures outcome.ForwardNeedScans? ==>
              ForwardBatchInvariant(outcome.batch) &&
              outcome.commands == ForwardCommands(outcome.batch.pending) &&
              0 < |outcome.commands| <= batchSize
    ensures outcome.ForwardBatchComplete? ==>
              Indexed.ForwardStateInvariant(outcome.state)
  {
    var current := state;
    var remaining := fuel;
    var pending: seq<Indexed.ForwardPending> := [];
    while 0 < remaining
      invariant Indexed.ForwardStateInvariant(current)
      invariant Indexed.CountersWithinLimits(current.counters, limits)
      invariant current.pending.NoForwardPending?
      invariant Indexed.ValidForwardQueuedEids(current.queue)
      invariant |pending| < batchSize
      invariant forall item <- pending :: ForwardPendingFits(current, item)
      decreases remaining
    {
      if 0 < |pending| &&
         (current.render.complete || |current.queue| == 0) {
        var batch := ForwardBatchState(current, pending);
        return ForwardNeedScans(batch, ForwardCommands(pending));
      }

      var step := Indexed.StepForward(current, limits);
      match step
      case ForwardAdvanced(next) => {
        forall item | item in pending
          ensures ForwardPendingFits(next, item)
        {
          ForwardPendingFitsFrame(current, next, item);
        }
        current := next;
        remaining := remaining - 1;
      }
      case ForwardEmitted(next, _, _) => {
        forall item | item in pending
          ensures ForwardPendingFits(next, item)
        {
          ForwardPendingFitsFrame(current, next, item);
        }
        current := next;
        remaining := remaining - 1;
      }
      case ForwardNeedScan(next, _) => {
        var nextPending := next.pending;
        var cleared := ClearForwardPending(next);
        forall item | item in pending
          ensures ForwardPendingFits(cleared, item)
        {
          ForwardPendingFitsMonotonic(current, cleared, item);
        }
        pending := pending + [nextPending];
        current := cleared;
        remaining := remaining - 1;
        if |pending| == batchSize {
          var batch := ForwardBatchState(current, pending);
          return ForwardNeedScans(batch, ForwardCommands(pending));
        }
      }
      case ForwardComplete(next) => {
        if 0 < |pending| {
          var batch := ForwardBatchState(current, pending);
          return ForwardNeedScans(batch, ForwardCommands(pending));
        }
        return ForwardBatchComplete(next);
      }
      case ForwardYielded(next) => {
        if 0 < |pending| {
          var batch := ForwardBatchState(current, pending);
          return ForwardNeedScans(batch, ForwardCommands(pending));
        }
        return ForwardBatchYielded(next);
      }
      case ForwardRenderRejected(error, next) => {
        if 0 < |pending| {
          var batch := ForwardBatchState(current, pending);
          return ForwardNeedScans(batch, ForwardCommands(pending));
        }
        return ForwardBatchRenderRejected(error, next);
      }
      case ForwardStepLimitExceeded(kind, next) => {
        if 0 < |pending| {
          var batch := ForwardBatchState(current, pending);
          return ForwardNeedScans(batch, ForwardCommands(pending));
        }
        return ForwardBatchLimitExceeded(kind, next);
      }
    }
    if 0 < |pending| {
      // Every issued request must either remain in the returned state or be
      // published as a bounded wave.  Rolling back here would repeat the same
      // prefix forever whenever fuel expires with fewer than batchSize scans.
      var batch := ForwardBatchState(current, pending);
      return ForwardNeedScans(batch, ForwardCommands(pending));
    }
    return ForwardBatchYielded(current);
  }

  method DriveReverseScans(
    state: Indexed.ReverseState,
    limits: Indexed.IndexedLimits,
    fuel: nat,
    batchSize: nat
  ) returns (outcome: ReverseBatchStep)
    requires 0 < batchSize
    requires Indexed.ReverseStateInvariant(state)
    requires Indexed.CountersWithinLimits(state.counters, limits)
    requires state.pending.NoReversePending?
    requires Indexed.ValidReverseQueuedEids(state.queue)
    ensures outcome.ReverseBatchYielded? ==>
              Indexed.ReverseStateInvariant(outcome.state)
    ensures outcome.ReverseNeedScans? ==>
              ReverseBatchInvariant(outcome.batch) &&
              outcome.commands == ReverseCommands(outcome.batch.pending) &&
              0 < |outcome.commands| <= batchSize
    ensures outcome.ReverseBatchComplete? ==>
              Indexed.ReverseStateInvariant(outcome.state)
  {
    var current := state;
    var remaining := fuel;
    var pending: seq<Indexed.ReversePending> := [];
    while 0 < remaining
      invariant Indexed.ReverseStateInvariant(current)
      invariant Indexed.CountersWithinLimits(current.counters, limits)
      invariant current.pending.NoReversePending?
      invariant Indexed.ValidReverseQueuedEids(current.queue)
      invariant |pending| < batchSize
      invariant forall item <- pending :: ReversePendingFits(current, item)
      decreases remaining
    {
      if 0 < |pending| &&
         (current.render.complete || |current.queue| == 0) {
        var batch := ReverseBatchState(current, pending);
        return ReverseNeedScans(batch, ReverseCommands(pending));
      }

      var step := Indexed.StepReverse(current, limits);
      match step
      case ReverseAdvanced(next) => {
        forall item | item in pending
          ensures ReversePendingFits(next, item)
        {
          ReversePendingFitsFrame(current, next, item);
        }
        current := next;
        remaining := remaining - 1;
      }
      case ReverseEmitted(next, _, _) => {
        forall item | item in pending
          ensures ReversePendingFits(next, item)
        {
          ReversePendingFitsFrame(current, next, item);
        }
        current := next;
        remaining := remaining - 1;
      }
      case ReverseNeedScan(next, _) => {
        var nextPending := next.pending;
        var cleared := ClearReversePending(next);
        forall item | item in pending
          ensures ReversePendingFits(cleared, item)
        {
          ReversePendingFitsMonotonic(current, cleared, item);
        }
        pending := pending + [nextPending];
        current := cleared;
        remaining := remaining - 1;
        if |pending| == batchSize {
          var batch := ReverseBatchState(current, pending);
          return ReverseNeedScans(batch, ReverseCommands(pending));
        }
      }
      case ReverseComplete(next) => {
        if 0 < |pending| {
          var batch := ReverseBatchState(current, pending);
          return ReverseNeedScans(batch, ReverseCommands(pending));
        }
        return ReverseBatchComplete(next);
      }
      case ReverseYielded(next) => {
        if 0 < |pending| {
          var batch := ReverseBatchState(current, pending);
          return ReverseNeedScans(batch, ReverseCommands(pending));
        }
        return ReverseBatchYielded(next);
      }
      case ReverseRenderRejected(error, next) => {
        if 0 < |pending| {
          var batch := ReverseBatchState(current, pending);
          return ReverseNeedScans(batch, ReverseCommands(pending));
        }
        return ReverseBatchRenderRejected(error, next);
      }
      case ReverseStepLimitExceeded(kind, next) => {
        if 0 < |pending| {
          var batch := ReverseBatchState(current, pending);
          return ReverseNeedScans(batch, ReverseCommands(pending));
        }
        return ReverseBatchLimitExceeded(kind, next);
      }
    }
    if 0 < |pending| {
      var batch := ReverseBatchState(current, pending);
      return ReverseNeedScans(batch, ReverseCommands(pending));
    }
    return ReverseBatchYielded(current);
  }

  method ResumeForwardScans(
    batch: ForwardBatchState,
    responses: seq<Indexed.ScanResponse>,
    limits: Indexed.IndexedLimits
  ) returns (outcome: Indexed.ForwardResume)
    requires ForwardBatchInvariant(batch)
    requires |responses| == |batch.pending|
    requires Indexed.CountersWithinLimits(batch.state.counters, limits)
  {
    var current := batch.state;
    var index := 0;
    while index < |responses|
      invariant 0 <= index <= |responses| == |batch.pending|
      invariant Indexed.ForwardStateInvariant(current)
      invariant current.pending.NoForwardPending?
      invariant Indexed.CountersWithinLimits(current.counters, limits)
      invariant forall pending <- batch.pending[index..] ::
                  ForwardPendingFits(current, pending)
      decreases |responses| - index
    {
      assert ForwardPendingFits(current, batch.pending[index]);
      var pendingState := WithForwardPending(current, batch.pending[index]);
      WithForwardPendingPreservesInvariant(
        current,
        batch.pending[index]
      );
      var resumed := Indexed.ResumeForwardScan(
        pendingState,
        responses[index],
        limits
      );
      if resumed.ForwardScanRejected? {
        return resumed;
      }
      if resumed.ForwardScanLimitExceeded? {
        return resumed;
      }
      assert resumed.state.nextRequestId == current.nextRequestId;
      assert resumed.state.chunkSize == current.chunkSize;
      forall item | item in batch.pending[index + 1..]
        ensures ForwardPendingFits(resumed.state, item)
      {
        assert item in batch.pending[index..];
        ForwardPendingFitsFrame(current, resumed.state, item);
      }
      current := resumed.state;
      index := index + 1;
    }
    return Indexed.ForwardScanResumed(current);
  }

  method ResumeReverseScans(
    batch: ReverseBatchState,
    responses: seq<Indexed.ScanResponse>,
    limits: Indexed.IndexedLimits
  ) returns (outcome: Indexed.ReverseResume)
    requires ReverseBatchInvariant(batch)
    requires |responses| == |batch.pending|
    requires Indexed.CountersWithinLimits(batch.state.counters, limits)
  {
    var current := batch.state;
    var index := 0;
    while index < |responses|
      invariant 0 <= index <= |responses| == |batch.pending|
      invariant Indexed.ReverseStateInvariant(current)
      invariant current.pending.NoReversePending?
      invariant Indexed.CountersWithinLimits(current.counters, limits)
      invariant forall pending <- batch.pending[index..] ::
                  ReversePendingFits(current, pending)
      decreases |responses| - index
    {
      assert ReversePendingFits(current, batch.pending[index]);
      var pendingState := WithReversePending(current, batch.pending[index]);
      WithReversePendingPreservesInvariant(
        current,
        batch.pending[index]
      );
      var resumed := Indexed.ResumeReverseScan(
        pendingState,
        responses[index],
        limits
      );
      if resumed.ReverseScanRejected? {
        return resumed;
      }
      if resumed.ReverseScanLimitExceeded? {
        return resumed;
      }
      assert resumed.state.nextRequestId == current.nextRequestId;
      assert resumed.state.chunkSize == current.chunkSize;
      forall item | item in batch.pending[index + 1..]
        ensures ReversePendingFits(resumed.state, item)
      {
        assert item in batch.pending[index..];
        ReversePendingFitsFrame(current, resumed.state, item);
      }
      current := resumed.state;
      index := index + 1;
    }
    return Indexed.ReverseScanResumed(current);
  }

  function CeilingDiv(value: nat, divisor: nat): nat
    requires 0 < divisor
    decreases value
  {
    if value == 0 then
      0
    else
      1 + CeilingDiv(
        if divisor < value then value - divisor else 0,
        divisor
      )
  }

  lemma BatchedCrossingLaw(scans: nat, batchSize: nat)
    requires 0 < batchSize
    ensures CeilingDiv(scans, batchSize) <= scans
    ensures 2 * CeilingDiv(scans, batchSize) + 1 <= 2 * scans + 1
    decreases scans
  {
    if 0 < scans {
      BatchedCrossingLaw(
        if batchSize < scans then scans - batchSize else 0,
        batchSize
      );
    }
  }
}

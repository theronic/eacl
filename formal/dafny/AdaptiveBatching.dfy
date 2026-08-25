// Demand-sized exponential batches with separate logical and physical progress.
module AdaptiveBatching {
  function Min(left: nat, right: nat): nat {
    if left <= right then left else right
  }

  function Min3(first: nat, second: nat, third: nat): nat {
    Min(Min(first, second), third)
  }

  function InitialWidth(
    demand: nat,
    physicalCap: nat,
    candidateWindow: nat
  ): nat {
    Min3(demand, physicalCap, candidateWindow)
  }

  function GrownWidth(
    previousWidth: nat,
    physicalCap: nat,
    remainingWindow: nat
  ): nat {
    Min3(2 * previousWidth, physicalCap, remainingWindow)
  }

  lemma InitialWidthRespectsEveryBound(
    demand: nat,
    physicalCap: nat,
    candidateWindow: nat
  )
    ensures InitialWidth(demand, physicalCap, candidateWindow) <= demand
    ensures InitialWidth(demand, physicalCap, candidateWindow) <= physicalCap
    ensures InitialWidth(demand, physicalCap, candidateWindow) <= candidateWindow
    ensures 0 < demand && 0 < physicalCap && 0 < candidateWindow ==>
              0 < InitialWidth(demand, physicalCap, candidateWindow)
  {
  }

  lemma GrownWidthIsBoundedDoubling(
    previousWidth: nat,
    physicalCap: nat,
    remainingWindow: nat
  )
    ensures GrownWidth(
              previousWidth,
              physicalCap,
              remainingWindow
            ) <= 2 * previousWidth
    ensures GrownWidth(
              previousWidth,
              physicalCap,
              remainingWindow
            ) <= physicalCap
    ensures GrownWidth(
              previousWidth,
              physicalCap,
              remainingWindow
            ) <= remainingWindow
    ensures 0 < previousWidth &&
            0 < physicalCap &&
            0 < remainingWindow ==>
              0 < GrownWidth(
                previousWidth,
                physicalCap,
                remainingWindow
              )
    ensures 2 * previousWidth <= physicalCap &&
            2 * previousWidth <= remainingWindow ==>
              GrownWidth(
                previousWidth,
                physicalCap,
                remainingWindow
              ) == 2 * previousWidth
  {
  }

  function CountTrue(values: seq<bool>): nat
    decreases |values|
  {
    if |values| == 0 then
      0
    else
      (if values[0] then 1 else 0) + CountTrue(values[1..])
  }

  // Smallest consumed prefix that reaches demand, or the complete sequence
  // when the batch contains too few accepted values.
  function PrefixForDemand(values: seq<bool>, demand: nat): nat
    ensures PrefixForDemand(values, demand) <= |values|
    decreases |values|
  {
    if demand == 0 || |values| == 0 then
      0
    else
      1 + PrefixForDemand(
        values[1..],
        if values[0] then demand - 1 else demand
      )
  }

  lemma PrefixForDemandIsBounded(
    values: seq<bool>,
    demand: nat
  )
    ensures PrefixForDemand(values, demand) <= |values|
    decreases |values|
  {
    if demand != 0 && |values| != 0 {
      PrefixForDemandIsBounded(
        values[1..],
        if values[0] then demand - 1 else demand
      );
    }
  }

  lemma CountTrueNeverExceedsWidth(values: seq<bool>)
    ensures CountTrue(values) <= |values|
    decreases |values|
  {
    if |values| != 0 {
      CountTrueNeverExceedsWidth(values[1..]);
    }
  }

  lemma PrefixReachesExactSentinel(
    values: seq<bool>,
    demand: nat
  )
    requires demand <= CountTrue(values)
    ensures CountTrue(values[..PrefixForDemand(values, demand)]) == demand
    ensures demand == 0 || PrefixForDemand(values, demand) != 0
    decreases |values|
  {
    if demand != 0 {
      assert |values| != 0;
      if values[0] {
        PrefixReachesExactSentinel(values[1..], demand - 1);
        assert values[..PrefixForDemand(values, demand)] ==
               [values[0]] +
               values[1..][..PrefixForDemand(
                 values[1..],
                 demand - 1
               )];
      } else {
        PrefixReachesExactSentinel(values[1..], demand);
        assert values[..PrefixForDemand(values, demand)] ==
               [values[0]] +
               values[1..][..PrefixForDemand(values[1..], demand)];
      }
    }
  }

  lemma PrefixNeverExceedsDemand(
    values: seq<bool>,
    demand: nat
  )
    ensures CountTrue(values[..PrefixForDemand(values, demand)]) <= demand
    decreases |values|
  {
    if demand != 0 && |values| != 0 {
      PrefixNeverExceedsDemand(
        values[1..],
        if values[0] then demand - 1 else demand
      );
      assert values[..PrefixForDemand(values, demand)] ==
             [values[0]] +
             values[1..][..PrefixForDemand(
               values[1..],
               if values[0] then demand - 1 else demand
             )];
    }
  }

  lemma InsufficientBatchConsumesWholeWidth(
    values: seq<bool>,
    demand: nat
  )
    requires CountTrue(values) < demand
    ensures PrefixForDemand(values, demand) == |values|
    decreases |values|
  {
    if |values| != 0 {
      InsufficientBatchConsumesWholeWidth(
        values[1..],
        if values[0] then demand - 1 else demand
      );
    }
  }

  function AcceptedIndexes(
    values: seq<bool>,
    offset: nat,
    consumed: nat
  ): seq<nat>
    requires consumed <= |values|
    decreases consumed
  {
    if consumed == 0 then
      []
    else
      (if values[0] then [offset] else []) +
      AcceptedIndexes(values[1..], offset + 1, consumed - 1)
  }

  lemma AcceptedIndexCount(
    values: seq<bool>,
    offset: nat,
    consumed: nat
  )
    requires consumed <= |values|
    ensures |AcceptedIndexes(values, offset, consumed)| ==
            CountTrue(values[..consumed])
    decreases consumed
  {
    if consumed != 0 {
      AcceptedIndexCount(
        values[1..],
        offset + 1,
        consumed - 1
      );
      assert values[..consumed] ==
             [values[0]] + values[1..][..consumed - 1];
    }
  }

  lemma AcceptedIndexesConcatenate(
    left: seq<bool>,
    right: seq<bool>,
    offset: nat,
    consumedRight: nat
  )
    requires consumedRight <= |right|
    ensures AcceptedIndexes(
              left + right,
              offset,
              |left| + consumedRight
            ) ==
            AcceptedIndexes(left, offset, |left|) +
            AcceptedIndexes(
              right,
              offset + |left|,
              consumedRight
            )
    decreases |left|
  {
    if |left| != 0 {
      AcceptedIndexesConcatenate(
        left[1..],
        right,
        offset + 1,
        consumedRight
      );
      assert left + right == [left[0]] + (left[1..] + right);
      assert (left + right)[1..] == left[1..] + right;
      assert |left[1..]| == |left| - 1;
      assert offset + 1 + |left[1..]| == offset + |left|;
      var headIndexes := if left[0] then [offset] else [];
      calc {
         AcceptedIndexes(
           left + right,
           offset,
           |left| + consumedRight
         );
      == headIndexes + AcceptedIndexes(
           left[1..] + right,
           offset + 1,
           |left| - 1 + consumedRight
         );
      == headIndexes +
         (AcceptedIndexes(
            left[1..],
            offset + 1,
            |left| - 1
          ) +
          AcceptedIndexes(
            right,
            offset + |left|,
            consumedRight
          ));
      == (headIndexes +
          AcceptedIndexes(
            left[1..],
            offset + 1,
            |left| - 1
          )) +
         AcceptedIndexes(
           right,
           offset + |left|,
           consumedRight
         );
      == AcceptedIndexes(left, offset, |left|) +
         AcceptedIndexes(
           right,
           offset + |left|,
           consumedRight
         );
      }
      assert AcceptedIndexes(
          left + right,
          offset,
          |left| + consumedRight
        ) ==
             AcceptedIndexes(left, offset, |left|) +
             AcceptedIndexes(
               right,
               offset + |left|,
               consumedRight
             );
    } else {
      assert left == [];
      assert left + right == right;
      assert AcceptedIndexes(left, offset, |left|) == [];
    }
  }

  datatype WorkCounters = WorkCounters(
    physicalCandidates: nat,
    batches: nat,
    physicalOverread: nat
  )

  datatype BatchState = BatchState(
    demand: nat,
    candidateWindow: nat,
    physicalCap: nat,
    nextCandidate: nat,
    batchWidth: nat,
    accepted: seq<nat>,
    processedDecisions: seq<bool>,
    counters: WorkCounters
  )

  predicate ValidState(state: BatchState) {
    0 < state.demand &&
    0 < state.physicalCap &&
    |state.accepted| < state.demand &&
    |state.processedDecisions| == state.nextCandidate &&
    state.accepted == AcceptedIndexes(
      state.processedDecisions,
      0,
      |state.processedDecisions|
    ) &&
    state.counters.physicalCandidates == state.nextCandidate &&
    state.counters.physicalOverread == 0 &&
    state.nextCandidate < state.candidateWindow &&
    0 < state.batchWidth <= state.physicalCap &&
    state.nextCandidate + state.batchWidth <= state.candidateWindow
  }

  datatype StartOutcome =
    | NoBatchNeeded
    | InvalidPhysicalCap
    | BatchStarted(state: BatchState)

  function StartBatch(
    demand: nat,
    candidateWindow: nat,
    physicalCap: nat
  ): StartOutcome {
    if demand == 0 || candidateWindow == 0 then
      NoBatchNeeded
    else if physicalCap == 0 then
      InvalidPhysicalCap
    else
      BatchStarted(
        BatchState(
          demand,
          candidateWindow,
          physicalCap,
          0,
          InitialWidth(demand, physicalCap, candidateWindow),
          [],
          [],
          WorkCounters(0, 0, 0)
        )
      )
  }

  lemma StartedBatchStateIsValid(
    demand: nat,
    candidateWindow: nat,
    physicalCap: nat
  )
    ensures var outcome := StartBatch(
                             demand,
                             candidateWindow,
                             physicalCap
                           );
            outcome.BatchStarted? ==> ValidState(outcome.state)
    ensures StartBatch(demand, candidateWindow, physicalCap)
            .NoBatchNeeded? <==>
            demand == 0 || candidateWindow == 0
    ensures StartBatch(demand, candidateWindow, physicalCap)
            .InvalidPhysicalCap? <==>
            demand != 0 && candidateWindow != 0 && physicalCap == 0
  {
    InitialWidthRespectsEveryBound(
      demand,
      physicalCap,
      candidateWindow
    );
  }

  datatype PhysicalResponse =
    | PhysicalSuccess(decisions: seq<bool>)
    | PhysicalCancelled
    | PhysicalError(code: nat)

  datatype BatchFailure =
    | Cancelled
    | BackendError(code: nat)
    | MalformedWidth(expected: nat, actual: nat)

  datatype AdvanceOutcome =
    | Continue(next: BatchState)
    | DemandComplete(
        accepted: seq<nat>,
        logicalBoundary: nat,
        physicalBoundary: nat,
        counters: WorkCounters
      )
    | CandidateWindowExhausted(
        accepted: seq<nat>,
        logicalBoundary: nat,
        physicalBoundary: nat,
        counters: WorkCounters
      )
    | AdvanceFailed(failure: BatchFailure)

  function AdvanceBatch(
    state: BatchState,
    response: PhysicalResponse
  ): AdvanceOutcome
    requires ValidState(state)
  {
    match response
    case PhysicalCancelled => AdvanceFailed(Cancelled)
    case PhysicalError(code) => AdvanceFailed(BackendError(code))
    case PhysicalSuccess(decisions) =>
      if |decisions| != state.batchWidth then
        AdvanceFailed(
          MalformedWidth(state.batchWidth, |decisions|)
        )
      else
        var remainingDemand := state.demand - |state.accepted|;
        var consumed := PrefixForDemand(decisions, remainingDemand);
        var acceptedNow := AcceptedIndexes(
                             decisions,
                             state.nextCandidate,
                             consumed
                           );
        var accepted := state.accepted + acceptedNow;
        var logicalBoundary := state.nextCandidate + consumed;
        var physicalBoundary :=
          state.nextCandidate + state.batchWidth;
        var counters := WorkCounters(
                          state.counters.physicalCandidates +
                          state.batchWidth,
                          state.counters.batches + 1,
                          state.counters.physicalOverread +
                          state.batchWidth - consumed
                        );
        if |accepted| == state.demand then
          DemandComplete(
            accepted,
            logicalBoundary,
            physicalBoundary,
            counters
          )
        else if physicalBoundary == state.candidateWindow then
          CandidateWindowExhausted(
            accepted,
            logicalBoundary,
            physicalBoundary,
            counters
          )
        else
          Continue(
            BatchState(
              state.demand,
              state.candidateWindow,
              state.physicalCap,
              physicalBoundary,
              GrownWidth(
                state.batchWidth,
                state.physicalCap,
                state.candidateWindow - physicalBoundary
              ),
              accepted,
              state.processedDecisions + decisions,
              counters
            )
          )
  }

  lemma SuccessfulBatchRespectsSentinelAndBoundaries(
    state: BatchState,
    decisions: seq<bool>
  )
    requires ValidState(state)
    requires |decisions| == state.batchWidth
    ensures var outcome := AdvanceBatch(
                             state,
                             PhysicalSuccess(decisions)
                           );
            outcome.DemandComplete? ==>
              |outcome.accepted| == state.demand &&
              outcome.accepted == AcceptedIndexes(
                state.processedDecisions + decisions,
                0,
                outcome.logicalBoundary
              ) &&
              state.nextCandidate < outcome.logicalBoundary <=
              outcome.physicalBoundary <= state.candidateWindow &&
              outcome.counters.physicalCandidates ==
              state.counters.physicalCandidates + state.batchWidth &&
              outcome.counters.physicalOverread ==
              state.counters.physicalOverread +
              outcome.physicalBoundary - outcome.logicalBoundary
    ensures var outcome := AdvanceBatch(
                             state,
                             PhysicalSuccess(decisions)
                           );
            outcome.Continue? ==>
              ValidState(outcome.next) &&
              outcome.next.nextCandidate ==
              state.nextCandidate + state.batchWidth &&
              outcome.next.processedDecisions ==
              state.processedDecisions + decisions &&
              outcome.next.batchWidth <= 2 * state.batchWidth &&
              outcome.next.batchWidth <= state.physicalCap
    ensures var outcome := AdvanceBatch(
                             state,
                             PhysicalSuccess(decisions)
                           );
            outcome.CandidateWindowExhausted? ==>
              outcome.accepted == AcceptedIndexes(
                state.processedDecisions + decisions,
                0,
                outcome.logicalBoundary
              ) &&
              outcome.logicalBoundary == outcome.physicalBoundary &&
              outcome.physicalBoundary == state.candidateWindow
  {
    var remainingDemand := state.demand - |state.accepted|;
    var consumed := PrefixForDemand(decisions, remainingDemand);
    PrefixForDemandIsBounded(decisions, remainingDemand);
    PrefixNeverExceedsDemand(decisions, remainingDemand);
    AcceptedIndexCount(
      decisions,
      state.nextCandidate,
      consumed
    );
    AcceptedIndexesConcatenate(
      state.processedDecisions,
      decisions,
      0,
      consumed
    );
    if remainingDemand <= CountTrue(decisions) {
      PrefixReachesExactSentinel(decisions, remainingDemand);
    } else {
      InsufficientBatchConsumesWholeWidth(decisions, remainingDemand);
    }
    var outcome := AdvanceBatch(state, PhysicalSuccess(decisions));
    if outcome.DemandComplete? {
      assert outcome.logicalBoundary == state.nextCandidate + consumed;
      assert outcome.accepted ==
             state.accepted + AcceptedIndexes(
               decisions,
               state.nextCandidate,
               consumed
             );
      assert outcome.logicalBoundary - state.nextCandidate == consumed;
      assert |state.processedDecisions + decisions[..consumed]| ==
             outcome.logicalBoundary;
    }
    if outcome.CandidateWindowExhausted? {
      assert consumed == |decisions|;
      assert decisions[..consumed] == decisions;
    }
    if outcome.Continue? {
      GrownWidthIsBoundedDoubling(
        state.batchWidth,
        state.physicalCap,
        state.candidateWindow -
        (state.nextCandidate + state.batchWidth)
      );
      assert |state.accepted| +
        CountTrue(decisions[..consumed]) <= state.demand;
      assert |state.accepted| +
        CountTrue(decisions[..consumed]) != state.demand;
    }
  }

  lemma CancellationFailureAndMalformedWidthPublishNothing(
    state: BatchState,
    response: PhysicalResponse
  )
    requires ValidState(state)
    requires response.PhysicalCancelled? ||
             response.PhysicalError? ||
             (response.PhysicalSuccess? &&
              |response.decisions| != state.batchWidth)
    ensures AdvanceBatch(state, response).AdvanceFailed?
  {
  }
}

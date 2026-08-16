// Exact progress checkpoints are reducer states, not partial denotations.
include "StableReducer.dfy"

module ReducerCheckpoint {
  import R = StableReducer

  datatype Checkpoint = Checkpoint(
    state: R.State,
    ordinal: nat
  )

  predicate ExactAtOrdinal(
    program: R.Program,
    initial: R.State,
    checkpoint: Checkpoint
  ) {
    checkpoint.state ==
      R.Run(program, initial, checkpoint.ordinal)
  }

  lemma RunComposition(
    program: R.Program,
    state: R.State,
    first: nat,
    second: nat
  )
    ensures R.Run(
              program,
              R.Run(program, state, first),
              second
            ) ==
            R.Run(program, state, first + second)
    decreases first
  {
    if first > 0 && |state.stack| > 0 {
      RunComposition(
        program, R.Step(program, state), first - 1, second
      );
    } else if |state.stack| == 0 {
      assert R.Run(program, state, first) == state;
      assert R.Run(program, state, first + second) == state;
    }
  }

  lemma ExactCheckpointResumesUninterruptedExecution(
    program: R.Program,
    state: R.State,
    checkpointSteps: nat,
    resumedSteps: nat
  )
    requires R.ExactState(program, state)
    ensures var checkpoint := R.Run(
                                program, state, checkpointSteps
                              );
            R.ExactState(program, checkpoint) &&
            R.Run(program, checkpoint, resumedSteps) ==
            R.Run(
              program,
              state,
              checkpointSteps + resumedSteps
            )
  {
    R.RunIsExact(program, state, checkpointSteps);
    RunComposition(
      program, state, checkpointSteps, resumedSteps
    );
  }

  lemma LaterOrdinalExtendsEarlierCheckpoint(
    program: R.Program,
    initial: R.State,
    earlier: nat,
    later: nat
  )
    requires R.ExactState(program, initial)
    requires earlier <= later
    ensures R.Run(
              program,
              R.Run(program, initial, earlier),
              later - earlier
            ) ==
            R.Run(program, initial, later)
  {
    assert earlier + (later - earlier) == later;
    RunComposition(program, initial, earlier, later - earlier);
  }

  lemma ConcurrentExactCandidatesAreOneTrajectory(
    program: R.Program,
    initial: R.State,
    left: Checkpoint,
    right: Checkpoint
  )
    requires R.ExactState(program, initial)
    requires ExactAtOrdinal(program, initial, left)
    requires ExactAtOrdinal(program, initial, right)
    requires left.ordinal <= right.ordinal
    ensures R.Run(
              program,
              left.state,
              right.ordinal - left.ordinal
            ) == right.state
  {
    LaterOrdinalExtendsEarlierCheckpoint(
      program, initial, left.ordinal, right.ordinal
    );
  }

  function LatestExact(
    left: Checkpoint,
    right: Checkpoint
  ): Checkpoint {
    if left.ordinal <= right.ordinal then right else left
  }

  lemma LatestExactIsExactAndNonregressing(
    program: R.Program,
    initial: R.State,
    left: Checkpoint,
    right: Checkpoint
  )
    requires ExactAtOrdinal(program, initial, left)
    requires ExactAtOrdinal(program, initial, right)
    ensures ExactAtOrdinal(
              program, initial, LatestExact(left, right)
            )
    ensures LatestExact(left, right).ordinal >= left.ordinal
    ensures LatestExact(left, right).ordinal >= right.ordinal
  {
  }

  lemma CheckpointRetainsExactDeliveredPrefix(
    program: R.Program,
    state: R.State,
    checkpointSteps: nat,
    resumedSteps: nat
  )
    requires R.ExactState(program, state)
    ensures var checkpoint := R.Run(
                                program, state, checkpointSteps
                              );
            var resumed := R.Run(
                             program, checkpoint, resumedSteps
                           );
            R.IsPrefix(state.results, checkpoint.results) &&
            R.IsPrefix(checkpoint.results, resumed.results)
  {
    R.RunIsExact(program, state, checkpointSteps);
    R.RunOnlyExtendsResults(program, state, checkpointSteps);
    R.RunOnlyExtendsResults(
      program,
      R.Run(program, state, checkpointSteps),
      resumedSteps
    );
  }
}

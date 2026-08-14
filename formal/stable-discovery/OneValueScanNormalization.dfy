// A physical scan may prefetch any positive bounded chunk, but the canonical
// reducer observes exactly one ordered scan value per logical transition.
// Exploratory proof model; intentionally excluded from release artifacts.
include "ChunkedScan.dfy"

module OneValueScanNormalization {
  import C = ChunkedScan

  datatype PhysicalState<T(==)> = PhysicalState(
    position: nat,
    buffer: seq<T>
  )

  datatype Release<T(==)> = Release(
    value: T,
    state: PhysicalState<T>
  )

  // The buffer is precisely the materialized, not-yet-released interval.
  // Everything from physical position onward remains in the backend.
  predicate Exact<T(==)>(
    values: seq<T>,
    logicalPosition: nat,
    state: PhysicalState<T>,
    physicalLimit: nat
  ) {
    logicalPosition <= state.position <= |values| &&
    state.buffer == values[logicalPosition..state.position] &&
    |state.buffer| <= physicalLimit
  }

  function Fill<T(==)>(
    values: seq<T>,
    logicalPosition: nat,
    state: PhysicalState<T>,
    physicalLimit: nat
  ): PhysicalState<T>
    requires Exact(values, logicalPosition, state, physicalLimit)
    requires logicalPosition < |values|
    requires 0 < physicalLimit
  {
    if |state.buffer| > 0 then
      state
    else
      var end := C.ChunkEnd(values, state.position, physicalLimit);
      PhysicalState(end, values[state.position..end])
  }

  lemma FillPreservesExactAndProducesAValue<T>(
    values: seq<T>,
    logicalPosition: nat,
    state: PhysicalState<T>,
    physicalLimit: nat
  )
    requires Exact(values, logicalPosition, state, physicalLimit)
    requires logicalPosition < |values|
    requires 0 < physicalLimit
    ensures Exact(
              values,
              logicalPosition,
              Fill(values, logicalPosition, state, physicalLimit),
              physicalLimit
            )
    ensures |Fill(values, logicalPosition, state, physicalLimit).buffer| > 0
    ensures Fill(values, logicalPosition, state, physicalLimit).position >=
            state.position
  {
    if |state.buffer| == 0 {
      assert logicalPosition == state.position;
      C.ChunkEndBounds(values, state.position, physicalLimit);
      var end := C.ChunkEnd(values, state.position, physicalLimit);
      assert state.position < end;
      assert |values[state.position..end]| == end - state.position;
      assert end - state.position <= physicalLimit;
    }
  }

  function ReleaseOne<T(==)>(
    values: seq<T>,
    logicalPosition: nat,
    state: PhysicalState<T>,
    physicalLimit: nat
  ): Release<T>
    requires Exact(values, logicalPosition, state, physicalLimit)
    requires logicalPosition < |values|
    requires 0 < physicalLimit
  {
    var filled := Fill(values, logicalPosition, state, physicalLimit);
    Release(
      filled.buffer[0],
      PhysicalState(filled.position, filled.buffer[1..])
    )
  }

  lemma ReleaseOneRefinesExactlyOneLogicalValue<T>(
    values: seq<T>,
    logicalPosition: nat,
    state: PhysicalState<T>,
    physicalLimit: nat
  )
    requires Exact(values, logicalPosition, state, physicalLimit)
    requires logicalPosition < |values|
    requires 0 < physicalLimit
    ensures ReleaseOne(
              values, logicalPosition, state, physicalLimit
            ).value == values[logicalPosition]
    ensures Exact(
              values,
              logicalPosition + 1,
              ReleaseOne(
                values, logicalPosition, state, physicalLimit
              ).state,
              physicalLimit
            )
    ensures ReleaseOne(
              values, logicalPosition, state, physicalLimit
            ).state.position >= state.position
  {
    FillPreservesExactAndProducesAValue(
      values, logicalPosition, state, physicalLimit
    );
    var filled := Fill(values, logicalPosition, state, physicalLimit);
    assert filled.buffer == values[logicalPosition..filled.position];
    assert filled.buffer[0] == values[logicalPosition];
    assert filled.buffer[1..] ==
           values[logicalPosition + 1..filled.position];
  }

  function ReleaseAll<T(==)>(
    values: seq<T>,
    logicalPosition: nat,
    state: PhysicalState<T>,
    physicalLimit: nat
  ): seq<T>
    requires Exact(values, logicalPosition, state, physicalLimit)
    requires 0 < physicalLimit
    decreases |values| - logicalPosition
  {
    if logicalPosition == |values| then
      []
    else
      var released := ReleaseOne(
        values, logicalPosition, state, physicalLimit
      );
      [released.value] + ReleaseAll(
        values,
        logicalPosition + 1,
        released.state,
        physicalLimit
      )
  }

  lemma ReleaseAllIsExactSuffix<T>(
    values: seq<T>,
    logicalPosition: nat,
    state: PhysicalState<T>,
    physicalLimit: nat
  )
    requires Exact(values, logicalPosition, state, physicalLimit)
    requires 0 < physicalLimit
    ensures ReleaseAll(
              values, logicalPosition, state, physicalLimit
            ) == values[logicalPosition..]
    decreases |values| - logicalPosition
  {
    if logicalPosition < |values| {
      ReleaseOneRefinesExactlyOneLogicalValue(
        values, logicalPosition, state, physicalLimit
      );
      var released := ReleaseOne(
        values, logicalPosition, state, physicalLimit
      );
      ReleaseAllIsExactSuffix(
        values,
        logicalPosition + 1,
        released.state,
        physicalLimit
      );
      assert values[logicalPosition..] ==
             [values[logicalPosition]] + values[logicalPosition + 1..];
    }
  }

  lemma PhysicalWidthCannotChangeLogicalReleaseOrder<T>(
    values: seq<T>,
    logicalPosition: nat,
    left: PhysicalState<T>,
    right: PhysicalState<T>,
    leftLimit: nat,
    rightLimit: nat
  )
    requires Exact(values, logicalPosition, left, leftLimit)
    requires Exact(values, logicalPosition, right, rightLimit)
    requires 0 < leftLimit
    requires 0 < rightLimit
    ensures ReleaseAll(values, logicalPosition, left, leftLimit) ==
            ReleaseAll(values, logicalPosition, right, rightLimit)
  {
    ReleaseAllIsExactSuffix(values, logicalPosition, left, leftLimit);
    ReleaseAllIsExactSuffix(values, logicalPosition, right, rightLimit);
  }

  function Dematerialize<T(==)>(logicalPosition: nat): PhysicalState<T> {
    PhysicalState(logicalPosition, [])
  }

  lemma DematerializePreservesExactLogicalPosition<T>(
    values: seq<T>,
    logicalPosition: nat,
    physicalLimit: nat
  )
    requires logicalPosition <= |values|
    requires 0 < physicalLimit
    ensures Exact(
              values,
              logicalPosition,
              Dematerialize<T>(logicalPosition),
              physicalLimit
            )
  {
  }

  lemma DroppingPhysicalBufferCannotChangeLogicalReleaseOrder<T>(
    values: seq<T>,
    logicalPosition: nat,
    materialized: PhysicalState<T>,
    materializedLimit: nat,
    refillLimit: nat
  )
    requires Exact(
               values,
               logicalPosition,
               materialized,
               materializedLimit
             )
    requires 0 < materializedLimit
    requires 0 < refillLimit
    ensures ReleaseAll(
              values,
              logicalPosition,
              materialized,
              materializedLimit
            ) ==
            ReleaseAll(
              values,
              logicalPosition,
              Dematerialize<T>(logicalPosition),
              refillLimit
            )
  {
    DematerializePreservesExactLogicalPosition(
      values, logicalPosition, refillLimit
    );
    ReleaseAllIsExactSuffix(
      values, logicalPosition, materialized, materializedLimit
    );
    ReleaseAllIsExactSuffix(
      values,
      logicalPosition,
      Dematerialize<T>(logicalPosition),
      refillLimit
    );
  }

  lemma InitialStateIsExact<T>(values: seq<T>, physicalLimit: nat)
    requires 0 < physicalLimit
    ensures Exact(values, 0, PhysicalState(0, []), physicalLimit)
  {
  }
}

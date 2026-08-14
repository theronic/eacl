// Linear ownership protocol for transient builders of the complete concrete
// reducer state and immutable cursor/checkpoint snapshots.
// Exploratory proof model; intentionally excluded from release artifacts.
include "ConcreteHistoryFreeRuntime.dfy"

module OwnedTransientSnapshot {
  import R = StableReducer
  import H = HistoryFreeReducer
  import C = ConcreteHistoryFreeRuntime

  datatype Snapshot = Snapshot(
    state: C.ConcreteState,
    generation: nat
  )

  datatype Branch = Branch(
    state: C.ConcreteState,
    generation: nat,
    owner: nat,
    live: bool
  )

  datatype FreezeResult = FreezeResult(
    snapshot: Snapshot,
    retired: Branch
  )

  predicate MutableBy(branch: Branch, owner: nat) {
    branch.live && branch.owner == owner
  }

  function Fork(snapshot: Snapshot, owner: nat): Branch {
    Branch(snapshot.state, snapshot.generation + 1, owner, true)
  }

  function Step(
    program: R.Program,
    branch: Branch,
    owner: nat
  ): Branch
    requires MutableBy(branch, owner)
  {
    Branch(
      C.Step(program, branch.state).state,
      branch.generation,
      branch.owner,
      true
    )
  }

  function Freeze(branch: Branch, owner: nat): FreezeResult
    requires MutableBy(branch, owner)
  {
    FreezeResult(
      Snapshot(branch.state, branch.generation),
      Branch(
        branch.state,
        branch.generation,
        branch.owner,
        false
      )
    )
  }

  lemma ForkPreservesCompleteSnapshot(
    snapshot: Snapshot,
    owner: nat
  )
    ensures MutableBy(Fork(snapshot, owner), owner)
    ensures Fork(snapshot, owner).state == snapshot.state
    ensures Fork(snapshot, owner).generation > snapshot.generation
  {
  }

  lemma OwnedStepIsExact(
    program: R.Program,
    branch: Branch,
    owner: nat
  )
    requires MutableBy(branch, owner)
    ensures MutableBy(Step(program, branch, owner), owner)
    ensures Step(program, branch, owner).state ==
              C.Step(program, branch.state).state
    ensures Step(program, branch, owner).generation == branch.generation
  {
  }

  lemma FreezePreservesCompleteStateAndRevokesOwner(
    branch: Branch,
    owner: nat
  )
    requires MutableBy(branch, owner)
    ensures Freeze(branch, owner).snapshot.state == branch.state
    ensures Freeze(branch, owner).snapshot.generation == branch.generation
    ensures Freeze(branch, owner).retired.state == branch.state
    ensures !Freeze(branch, owner).retired.live
    ensures !MutableBy(Freeze(branch, owner).retired, owner)
  {
  }

  lemma FreezeThenForkPreservesCompleteState(
    branch: Branch,
    owner: nat,
    nextOwner: nat
  )
    requires MutableBy(branch, owner)
    ensures Fork(
              Freeze(branch, owner).snapshot,
              nextOwner
            ).state == branch.state
    ensures MutableBy(
              Fork(
                Freeze(branch, owner).snapshot,
                nextOwner
              ),
              nextOwner
            )
  {
  }

  lemma ForkedBranchesAreIndependent(
    program: R.Program,
    snapshot: Snapshot,
    leftOwner: nat,
    rightOwner: nat
  )
    requires leftOwner != rightOwner
    ensures var left := Step(
                         program,
                         Fork(snapshot, leftOwner),
                         leftOwner
                       );
            var right := Fork(snapshot, rightOwner);
            left.state == C.Step(program, snapshot.state).state &&
            right.state == snapshot.state &&
            MutableBy(left, leftOwner) &&
            !MutableBy(left, rightOwner) &&
            MutableBy(right, rightOwner) &&
            !MutableBy(right, leftOwner)
  {
  }

  lemma FrozenSnapshotRefinesSameRuntime(
    runtime: H.RuntimeState,
    branch: Branch,
    owner: nat
  )
    requires MutableBy(branch, owner)
    requires C.Represents(runtime, branch.state)
    ensures C.Represents(
              runtime,
              Freeze(branch, owner).snapshot.state
            )
  {
  }
}

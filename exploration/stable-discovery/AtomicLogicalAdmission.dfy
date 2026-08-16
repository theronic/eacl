// A logical successor batch is admitted completely or not at all.
// Exploratory proof model; intentionally excluded from release artifacts.
module AtomicLogicalAdmission {
  datatype Limits = Limits(
    admitted: nat,
    pending: nat
  )

  datatype State = State(
    admitted: nat,
    pending: nat
  )

  datatype Candidate = Candidate(
    newCount: nat,
    replacementPending: nat
  )

  datatype Outcome = Outcome(state: State, accepted: bool)

  predicate Within(limits: Limits, state: State) {
    state.admitted <= limits.admitted &&
    state.pending <= limits.pending
  }

  // Subtraction form is the source contract: host implementations validate
  // naturals first and never evaluate an overflowing current + increment.
  predicate Fits(current: nat, increment: nat, limit: nat) {
    current <= limit && increment <= limit - current
  }

  predicate CanApply(
    limits: Limits,
    state: State,
    candidate: Candidate
  ) {
    state.pending > 0 &&
    Within(limits, state) &&
    Fits(state.admitted, candidate.newCount, limits.admitted) &&
    Fits(state.pending - 1, candidate.replacementPending,
         limits.pending)
  }

  function Apply(
    limits: Limits,
    state: State,
    candidate: Candidate
  ): Outcome
  {
    if CanApply(limits, state, candidate) then
      Outcome(
        State(
          state.admitted + candidate.newCount,
          state.pending - 1 + candidate.replacementPending
        ),
        true
      )
    else
      Outcome(state, false)
  }

  lemma AcceptedBatchPreservesAllCaps(
    limits: Limits,
    state: State,
    candidate: Candidate
  )
    requires CanApply(limits, state, candidate)
    ensures Apply(limits, state, candidate).accepted
    ensures Within(limits, Apply(limits, state, candidate).state)
    ensures Apply(limits, state, candidate).state.admitted ==
            state.admitted + candidate.newCount
    ensures Apply(limits, state, candidate).state.pending ==
            state.pending - 1 + candidate.replacementPending
  {
  }

  lemma RejectedBatchIsAtomic(
    limits: Limits,
    state: State,
    candidate: Candidate
  )
    requires !CanApply(limits, state, candidate)
    ensures !Apply(limits, state, candidate).accepted
    ensures Apply(limits, state, candidate).state == state
  {
  }

  // This deliberately models a post-check implementation that has already
  // inserted fresh identities when it notices the cap violation.
  function PostCheckMutant(
    state: State,
    candidate: Candidate
  ): State
  {
    State(
      state.admitted + candidate.newCount,
      state.pending
    )
  }

  lemma PostCheckMutationCanExceedTheCap()
    ensures var limits := Limits(1, 1);
            var state := State(1, 1);
            var candidate := Candidate(1, 0);
            Within(limits, state) &&
            !CanApply(limits, state, candidate) &&
            Apply(limits, state, candidate).state == state &&
            !Within(limits, PostCheckMutant(state, candidate))
  {
  }
}

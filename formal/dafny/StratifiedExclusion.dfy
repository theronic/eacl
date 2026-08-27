include "CandidateCover.dfy"

// Strict lower-stratum exclusion with exact absence and failure propagation.
module StratifiedExclusion {
  import opened CandidateCover

  datatype ExactOutcome =
    | ExactComplete(denotation: set<Entity>)
    | TimedOut
    | Cancelled
    | LimitExceeded(kind: nat)
    | ActiveRecursion
    | BackendFailed(code: nat)

  datatype ExclusionFailure =
    | InvalidNegativeStratum(parent: nat, negative: nat)
    | LeftTimedOut
    | LeftCancelled
    | LeftLimitExceeded(kind: nat)
    | LeftActiveRecursion
    | LeftBackendFailed(code: nat)
    | NegativeTimedOut
    | NegativeCancelled
    | NegativeLimitExceeded(kind: nat)
    | NegativeActiveRecursion
    | NegativeBackendFailed(code: nat)

  datatype ExclusionOutcome =
    | ExclusionComplete(denotation: set<Entity>)
    | ExclusionFailed(failure: ExclusionFailure)

  function LeftFailure(outcome: ExactOutcome): ExclusionFailure
    requires !outcome.ExactComplete?
  {
    match outcome
    case TimedOut => LeftTimedOut
    case Cancelled => LeftCancelled
    case LimitExceeded(kind) => LeftLimitExceeded(kind)
    case ActiveRecursion => LeftActiveRecursion
    case BackendFailed(code) => LeftBackendFailed(code)
    case ExactComplete(_) => LeftActiveRecursion
  }

  function NegativeFailure(outcome: ExactOutcome): ExclusionFailure
    requires !outcome.ExactComplete?
  {
    match outcome
    case TimedOut => NegativeTimedOut
    case Cancelled => NegativeCancelled
    case LimitExceeded(kind) => NegativeLimitExceeded(kind)
    case ActiveRecursion => NegativeActiveRecursion
    case BackendFailed(code) => NegativeBackendFailed(code)
    case ExactComplete(_) => NegativeActiveRecursion
  }

  function EvaluateExclusion(
    parentStratum: nat,
    negativeStratum: nat,
    left: ExactOutcome,
    negative: ExactOutcome
  ): ExclusionOutcome {
    if negativeStratum >= parentStratum then
      ExclusionFailed(
        InvalidNegativeStratum(parentStratum, negativeStratum)
      )
    else if !left.ExactComplete? then
      ExclusionFailed(LeftFailure(left))
    else if !negative.ExactComplete? then
      ExclusionFailed(NegativeFailure(negative))
    else
      ExclusionComplete(left.denotation - negative.denotation)
  }

  datatype PointOutcome =
    | PointComplete(allowed: bool)
    | PointFailed(failure: ExclusionFailure)

  function EvaluateExclusionPoint(
    parentStratum: nat,
    negativeStratum: nat,
    left: ExactOutcome,
    negative: ExactOutcome,
    entity: Entity
  ): PointOutcome {
    var result := EvaluateExclusion(
                    parentStratum,
                    negativeStratum,
                    left,
                    negative
                  );
    if result.ExclusionComplete? then
      PointComplete(entity in result.denotation)
    else
      PointFailed(result.failure)
  }

  lemma CompletedLowerStratumExclusionIsExact(
    parentStratum: nat,
    negativeStratum: nat,
    left: set<Entity>,
    negative: set<Entity>
  )
    requires negativeStratum < parentStratum
    ensures EvaluateExclusion(
              parentStratum,
              negativeStratum,
              ExactComplete(left),
              ExactComplete(negative)
            ) == ExclusionComplete(left - negative)
    ensures forall entity: Entity ::
              EvaluateExclusionPoint(
                parentStratum,
                negativeStratum,
                ExactComplete(left),
                ExactComplete(negative),
                entity
              ) == PointComplete(
                entity in left && entity !in negative
              )
  {
  }

  lemma IncompleteNegativePremiseNeverAuthorizes(
    parentStratum: nat,
    negativeStratum: nat,
    left: ExactOutcome,
    negative: ExactOutcome,
    entity: Entity
  )
    requires negativeStratum < parentStratum
    requires !negative.ExactComplete?
    ensures EvaluateExclusion(
              parentStratum,
              negativeStratum,
              left,
              negative
            ).ExclusionFailed?
    ensures EvaluateExclusionPoint(
              parentStratum,
              negativeStratum,
              left,
              negative,
              entity
            ).PointFailed?
  {
  }

  lemma EveryNegativeFailurePropagatesExactly(
    parentStratum: nat,
    negativeStratum: nat,
    left: set<Entity>,
    negative: ExactOutcome
  )
    requires negativeStratum < parentStratum
    requires !negative.ExactComplete?
    ensures EvaluateExclusion(
              parentStratum,
              negativeStratum,
              ExactComplete(left),
              negative
            ) == ExclusionFailed(NegativeFailure(negative))
  {
  }

  lemma EveryLeftFailurePropagatesExactly(
    parentStratum: nat,
    negativeStratum: nat,
    left: ExactOutcome,
    negative: ExactOutcome
  )
    requires negativeStratum < parentStratum
    requires !left.ExactComplete?
    ensures EvaluateExclusion(
              parentStratum,
              negativeStratum,
              left,
              negative
            ) == ExclusionFailed(LeftFailure(left))
  {
  }

  lemma NonLowerNegativeStratumIsRejectedBeforeEvaluation(
    parentStratum: nat,
    negativeStratum: nat,
    left: ExactOutcome,
    negative: ExactOutcome
  )
    requires parentStratum <= negativeStratum
    ensures EvaluateExclusion(
              parentStratum,
              negativeStratum,
              left,
              negative
            ) ==
            ExclusionFailed(
              InvalidNegativeStratum(parentStratum, negativeStratum)
            )
  {
  }
}

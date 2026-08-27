include "OperatorGeneratedPolicy.dfy"
include "AdaptiveBatching.dfy"
include "DensityBoundedBatch.dfy"

// Proof-only bridge.  EaclKernel compiles OperatorGeneratedPolicy; this
// module proves that the smaller generated definitions are exactly the
// decisions proved by the richer abstract models.
module OperatorGeneratedPolicyRefinement {
  import Generated = OperatorGeneratedPolicy
  import Batch = AdaptiveBatching
  import Density = DensityBoundedBatch

  function ToDensitySpan(
    span: Generated.SpanOutcome
  ): Density.SpanOutcome {
    match span
    case CheckedSpan(value) => Density.CheckedSpan(value)
    case InvalidOrder => Density.InvalidOrder
    case SpanOverflow => Density.SpanOverflow
  }

  function ToDensityStrategy(
    strategy: Generated.ProbeStrategy
  ): Density.ProbeStrategy {
    match strategy
    case EmptyBatch => Density.EmptyBatch
    case DensePrefix => Density.DensePrefix
    case SparseExact => Density.SparseExact
  }

  lemma GeneratedWidthsEqualAbstractWidths(
    demand: nat,
    physicalCap: nat,
    candidateWindow: nat,
    previousWidth: nat
  )
    ensures Generated.InitialWidth(
              demand,
              physicalCap,
              candidateWindow
            ) ==
            Batch.InitialWidth(demand, physicalCap, candidateWindow)
    ensures Generated.GrownWidth(
              previousWidth,
              physicalCap,
              candidateWindow
            ) ==
            Batch.GrownWidth(
              previousWidth,
              physicalCap,
              candidateWindow
            )
  {
  }

  lemma GeneratedPrefixEqualsAbstractPrefix(
    values: seq<bool>,
    demand: nat
  )
    ensures Generated.PrefixForDemand(values, demand) ==
            Batch.PrefixForDemand(values, demand)
    decreases |values|
  {
    if demand != 0 && |values| != 0 {
      GeneratedPrefixEqualsAbstractPrefix(
        values[1..],
        if values[0] then demand - 1 else demand
      );
    }
  }

  lemma GeneratedSpanEqualsAbstractSpan(
    firstEid: nat,
    lastEid: nat,
    maximumRepresentableSpan: nat
  )
    ensures ToDensitySpan(
              Generated.CheckedInclusiveSpan(
                firstEid,
                lastEid,
                maximumRepresentableSpan
              )
            ) ==
            Density.CheckedInclusiveSpan(
              firstEid,
              lastEid,
              maximumRepresentableSpan
            )
  {
  }

  lemma GeneratedStrategyEqualsAbstractStrategy(
    candidateCount: nat,
    span: Generated.SpanOutcome,
    densityMultiplier: nat
  )
    ensures ToDensityStrategy(
              Generated.SelectStrategy(
                candidateCount,
                span,
                densityMultiplier
              )
            ) ==
            Density.SelectStrategy(
              candidateCount,
              ToDensitySpan(span),
              densityMultiplier
            )
  {
  }

  lemma GeneratedScheduledNextWidthEqualsAbstract(
    remainingDemand: nat,
    remainingWindow: nat,
    physicalCap: nat,
    issuedWidth: nat,
    acceptedCount: nat
  )
    requires acceptedCount <= issuedWidth
    ensures Generated.ScheduledNextWidth(
              remainingDemand,
              remainingWindow,
              physicalCap,
              issuedWidth,
              acceptedCount
            ) ==
            Batch.ScheduledNextWidth(
              remainingDemand,
              remainingWindow,
              physicalCap,
              issuedWidth,
              acceptedCount
            )
  {
  }
}

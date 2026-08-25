// Deliberately small executable policy surface for the generated Java and
// JavaScript smoke boundary.  The proof-only refinement module proves these
// definitions equal the richer batching and density models without pulling
// those models into generated artifacts.
module OperatorGeneratedPolicy {
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

  datatype SpanOutcome =
    | CheckedSpan(span: nat)
    | InvalidOrder
    | SpanOverflow

  function CheckedInclusiveSpan(
    firstEid: nat,
    lastEid: nat,
    maximumRepresentableSpan: nat
  ): SpanOutcome {
    if lastEid < firstEid then
      InvalidOrder
    else if lastEid - firstEid >= maximumRepresentableSpan then
      SpanOverflow
    else
      CheckedSpan(lastEid - firstEid + 1)
  }

  datatype ProbeStrategy = EmptyBatch | DensePrefix | SparseExact

  function SelectStrategy(
    candidateCount: nat,
    span: SpanOutcome,
    densityMultiplier: nat
  ): ProbeStrategy {
    if candidateCount == 0 then
      EmptyBatch
    else if span.CheckedSpan? &&
            span.span <= densityMultiplier * candidateCount then
      DensePrefix
    else
      SparseExact
  }

  lemma InitialWidthRespectsEveryBound(
    demand: nat,
    physicalCap: nat,
    candidateWindow: nat
  )
    ensures InitialWidth(demand, physicalCap, candidateWindow) <= demand
    ensures InitialWidth(demand, physicalCap, candidateWindow) <= physicalCap
    ensures InitialWidth(demand, physicalCap, candidateWindow) <=
            candidateWindow
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
  {
  }
}

include "SeekableSetKernels.dfy"
include "VectorPredicate.dfy"

// Checked density selection for prefix merge versus sparse exact probes.
module DensityBoundedBatch {
  import SeekableSetKernels
  import VectorPredicate

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

  function ValuesInRange(
    values: seq<nat>,
    firstEid: nat,
    lastEid: nat
  ): seq<nat>
    decreases |values|
  {
    if |values| == 0 then
      []
    else
      (if firstEid <= values[0] <= lastEid then [values[0]] else []) +
      ValuesInRange(values[1..], firstEid, lastEid)
  }

  lemma RangeMembershipIsExact(
    values: seq<nat>,
    firstEid: nat,
    lastEid: nat,
    value: nat
  )
    ensures value in ValuesInRange(values, firstEid, lastEid) <==>
            value in values && firstEid <= value <= lastEid
    decreases |values|
  {
    if |values| != 0 {
      RangeMembershipIsExact(
        values[1..],
        firstEid,
        lastEid,
        value
      );
    }
  }

  lemma RangeFilterPreservesStrictOrder(
    values: seq<nat>,
    firstEid: nat,
    lastEid: nat
  )
    requires SeekableSetKernels.StrictlyIncreasing(values)
    ensures SeekableSetKernels.StrictlyIncreasing(
              ValuesInRange(values, firstEid, lastEid)
            )
    decreases |values|
  {
    if |values| != 0 {
      assert SeekableSetKernels.StrictlyIncreasing(values[1..]);
      RangeFilterPreservesStrictOrder(
        values[1..],
        firstEid,
        lastEid
      );
      if firstEid <= values[0] <= lastEid {
        forall value | value in ValuesInRange(
                                  values[1..],
                                  firstEid,
                                  lastEid
                                )
          ensures values[0] < value
        {
          RangeMembershipIsExact(
            values[1..],
            firstEid,
            lastEid,
            value
          );
          assert forall tailValue <- values[1..] :: values[0] < tailValue;
        }
        assert ValuesInRange(values, firstEid, lastEid) ==
               [values[0]] +
               ValuesInRange(values[1..], firstEid, lastEid);
      } else {
        assert ValuesInRange(values, firstEid, lastEid) ==
               ValuesInRange(values[1..], firstEid, lastEid);
      }
    }
  }

  lemma EveryRangeValueIsWithinBounds(
    values: seq<nat>,
    firstEid: nat,
    lastEid: nat,
    value: nat
  )
    requires value in ValuesInRange(values, firstEid, lastEid)
    ensures firstEid <= value <= lastEid
    decreases |values|
  {
    if |values| != 0 &&
       !(firstEid <= values[0] <= lastEid && value == values[0]) {
      EveryRangeValueIsWithinBounds(
        values[1..],
        firstEid,
        lastEid,
        value
      );
    }
  }

  lemma LastIsAtLeastFirstPlusWidthMinusOne(values: seq<nat>)
    requires 0 < |values|
    requires SeekableSetKernels.StrictlyIncreasing(values)
    ensures values[|values| - 1] >= values[0] + |values| - 1
    decreases |values|
  {
    if |values| > 1 {
      assert SeekableSetKernels.StrictlyIncreasing(values[1..]);
      LastIsAtLeastFirstPlusWidthMinusOne(values[1..]);
      assert values[1] in values[1..];
      assert forall value <- values[1..] :: values[0] < value;
      assert values[0] < values[1];
    }
  }

  lemma StrictRangeCardinalityIsBoundedBySpan(
    values: seq<nat>,
    firstEid: nat,
    lastEid: nat
  )
    requires firstEid <= lastEid
    requires SeekableSetKernels.StrictlyIncreasing(values)
    requires forall value <- values :: firstEid <= value <= lastEid
    ensures |values| <= lastEid - firstEid + 1
  {
    if |values| != 0 {
      LastIsAtLeastFirstPlusWidthMinusOne(values);
      assert values[0] in values;
      assert values[|values| - 1] in values;
      assert firstEid <= values[0];
      assert values[|values| - 1] <= lastEid;
    }
  }

  lemma DensePrefixRealizationIsDensityBounded(
    relationValues: seq<nat>,
    firstEid: nat,
    lastEid: nat,
    maximumRepresentableSpan: nat,
    candidateCount: nat
  )
    requires SeekableSetKernels.StrictlyIncreasing(relationValues)
    requires SelectStrategy(
               candidateCount,
               CheckedInclusiveSpan(
                 firstEid,
                 lastEid,
                 maximumRepresentableSpan
               ),
               4
             ).DensePrefix?
    ensures |ValuesInRange(relationValues, firstEid, lastEid)| <=
            4 * candidateCount
  {
    var checked := CheckedInclusiveSpan(
      firstEid,
      lastEid,
      maximumRepresentableSpan
    );
    assert checked.CheckedSpan?;
    assert firstEid <= lastEid;
    RangeFilterPreservesStrictOrder(
      relationValues,
      firstEid,
      lastEid
    );
    forall value | value in ValuesInRange(
                              relationValues,
                              firstEid,
                              lastEid
                            )
      ensures firstEid <= value <= lastEid
    {
      EveryRangeValueIsWithinBounds(
        relationValues,
        firstEid,
        lastEid,
        value
      );
    }
    StrictRangeCardinalityIsBoundedBySpan(
      ValuesInRange(relationValues, firstEid, lastEid),
      firstEid,
      lastEid
    );
  }

  function SparseExactDecisions(
    candidates: seq<nat>,
    relationValues: seq<nat>
  ): seq<bool>
    ensures |SparseExactDecisions(candidates, relationValues)| ==
            |candidates|
    decreases |candidates|
  {
    if |candidates| == 0 then
      []
    else
      [var positioned := SeekableSetKernels.InclusiveGallop(
                           relationValues,
                           candidates[0]
                         );
       |positioned| != 0 && positioned[0] == candidates[0]] +
      SparseExactDecisions(candidates[1..], relationValues)
  }

  lemma SparseFallbackIsExact(
    candidates: seq<nat>,
    relationValues: seq<nat>
  )
    requires SeekableSetKernels.StrictlyIncreasing(relationValues)
    ensures SparseExactDecisions(candidates, relationValues) ==
            SeekableSetKernels.MembershipDecisions(
              candidates,
              relationValues,
              true
            )
    decreases |candidates|
  {
    if |candidates| != 0 {
      SeekableSetKernels.GallopHeadDecidesExactMembership(
        relationValues,
        candidates[0]
      );
      SparseFallbackIsExact(candidates[1..], relationValues);
    }
  }

  // The dense kernel decides each candidate against the realized range
  // prefix, exactly as the production loop tests membership in the values
  // it realized between the batch's first and last candidate.
  function DensePrefixDecisions(
    candidates: seq<nat>,
    relationValues: seq<nat>,
    firstEid: nat,
    lastEid: nat
  ): seq<bool>
    ensures |DensePrefixDecisions(
              candidates, relationValues, firstEid, lastEid
            )| == |candidates|
    decreases |candidates|
  {
    if |candidates| == 0 then
      []
    else
      [candidates[0] in ValuesInRange(relationValues, firstEid, lastEid)] +
      DensePrefixDecisions(candidates[1..], relationValues, firstEid, lastEid)
  }

  // Dense-path exactness: for candidates within the realized span - which
  // the batch guarantees, since the span is bounded by its own first and
  // last candidate - the dense decisions equal exact membership, so both
  // physical modes decide identically.
  lemma DensePrefixDecisionsAreExact(
    candidates: seq<nat>,
    relationValues: seq<nat>,
    firstEid: nat,
    lastEid: nat
  )
    requires SeekableSetKernels.StrictlyIncreasing(relationValues)
    requires forall candidate <- candidates ::
               firstEid <= candidate <= lastEid
    ensures DensePrefixDecisions(
              candidates, relationValues, firstEid, lastEid
            ) ==
            SeekableSetKernels.MembershipDecisions(
              candidates,
              relationValues,
              true
            )
    decreases |candidates|
  {
    if |candidates| != 0 {
      RangeMembershipIsExact(
        relationValues,
        firstEid,
        lastEid,
        candidates[0]
      );
      assert candidates[0] in candidates;
      assert (candidates[0] in
                ValuesInRange(relationValues, firstEid, lastEid)) <==>
             (candidates[0] in relationValues);
      assert forall candidate <- candidates[1..] ::
          candidate in candidates;
      DensePrefixDecisionsAreExact(
        candidates[1..],
        relationValues,
        firstEid,
        lastEid
      );
      assert SeekableSetKernels.MembershipDecisions(
          candidates, relationValues, true
        ) ==
             [candidates[0] in relationValues] +
             SeekableSetKernels.MembershipDecisions(
               candidates[1..], relationValues, true
             );
    }
  }

  predicate SortedPhysicalOrder(
    candidates: seq<nat>,
    order: seq<nat>
  ) {
    forall left, right | 0 <= left < right < |order| ::
      order[left] < |candidates| &&
      order[right] < |candidates| &&
      candidates[order[left]] < candidates[order[right]]
  }

  lemma SortedProbeDecisionsScatterBackToInputOrder(
    candidates: seq<nat>,
    relationValues: seq<nat>,
    order: seq<nat>,
    positions: map<nat, nat>
  )
    requires SeekableSetKernels.StrictlyIncreasing(relationValues)
    requires VectorPredicate.ValidPermutationCertificate(
               order,
               positions,
               |candidates|
             )
    requires SortedPhysicalOrder(candidates, order)
    ensures var inputDecisions := SparseExactDecisions(
                                    candidates,
                                    relationValues
                                  );
            VectorPredicate.Scatter(
              VectorPredicate.Gather(inputDecisions, order),
              positions,
              |candidates|
            ) == inputDecisions
  {
    var inputDecisions := SparseExactDecisions(
      candidates,
      relationValues
    );
    SparseFallbackIsExact(candidates, relationValues);
    VectorPredicate.RegroupAndScatterPreservesAlignment(
      inputDecisions,
      order,
      positions
    );
  }
}

include "AdaptiveBatching.dfy"

// Inclusive galloping/reseek kernels for compatible ordered direct operands.
module SeekableSetKernels {
  import AdaptiveBatching

  predicate StrictlyIncreasing(values: seq<nat>)
    decreases |values|
  {
    |values| == 0 ||
    ((forall value <- values[1..] :: values[0] < value) &&
     StrictlyIncreasing(values[1..]))
  }

  function InclusiveGallop(
    values: seq<nat>,
    target: nat
  ): seq<nat>
    ensures |InclusiveGallop(values, target)| <= |values|
    ensures |InclusiveGallop(values, target)| != 0 ==>
              InclusiveGallop(values, target)[0] >= target
    ensures |values| != 0 && values[0] < target ==>
              |InclusiveGallop(values, target)| < |values|
    decreases |values|
  {
    if |values| == 0 || values[0] >= target then
      values
    else
      InclusiveGallop(values[1..], target)
  }

  lemma GallopRetainsExactlyValuesAtOrAboveTarget(
    values: seq<nat>,
    target: nat,
    value: nat
  )
    requires target <= value
    ensures value in InclusiveGallop(values, target) <==> value in values
    decreases |values|
  {
    if |values| != 0 && values[0] < target {
      GallopRetainsExactlyValuesAtOrAboveTarget(
        values[1..],
        target,
        value
      );
    }
  }

  lemma GallopHeadDecidesExactMembership(
    values: seq<nat>,
    target: nat
  )
    requires StrictlyIncreasing(values)
    ensures target in values <==>
            |InclusiveGallop(values, target)| != 0 &&
            InclusiveGallop(values, target)[0] == target
  {
    GallopRetainsExactlyValuesAtOrAboveTarget(values, target, target);
    GallopPreservesStrictOrder(values, target);
    var positioned := InclusiveGallop(values, target);
    if |positioned| != 0 {
      assert positioned[0] >= target;
      if positioned[0] != target {
        assert forall value <- positioned[1..] :: positioned[0] < value;
        forall value | value in positioned
          ensures value != target
        {
          if value == positioned[0] {
            assert value > target;
          } else {
            assert value in positioned[1..];
            assert value > positioned[0] > target;
          }
        }
      }
    }
  }

  lemma GallopPreservesStrictOrder(
    values: seq<nat>,
    target: nat
  )
    requires StrictlyIncreasing(values)
    ensures StrictlyIncreasing(InclusiveGallop(values, target))
    decreases |values|
  {
    if |values| != 0 && values[0] < target {
      GallopPreservesStrictOrder(values[1..], target);
    }
  }

  function GenericIntersection(
    driver: seq<nat>,
    other: seq<nat>
  ): seq<nat>
    decreases |driver|
  {
    if |driver| == 0 then
      []
    else
      (if driver[0] in other then [driver[0]] else []) +
      GenericIntersection(driver[1..], other)
  }

  function GenericExclusion(
    driver: seq<nat>,
    excluded: seq<nat>
  ): seq<nat>
    decreases |driver|
  {
    if |driver| == 0 then
      []
    else
      (if driver[0] !in excluded then [driver[0]] else []) +
      GenericExclusion(driver[1..], excluded)
  }

  // The positioned operand is passed to the next candidate, so it only moves
  // forward.  An implementation may realize InclusiveGallop with exponential
  // search followed by a bounded binary seek.
  function LeapfrogIntersection(
    driver: seq<nat>,
    other: seq<nat>
  ): seq<nat>
    decreases |driver|
  {
    if |driver| == 0 then
      []
    else
      var positioned := InclusiveGallop(other, driver[0]);
      if |positioned| != 0 && positioned[0] == driver[0] then
        [driver[0]] +
        LeapfrogIntersection(driver[1..], positioned[1..])
      else
        LeapfrogIntersection(driver[1..], positioned)
  }

  function MonotoneExclusionAntiJoin(
    driver: seq<nat>,
    excluded: seq<nat>
  ): seq<nat>
    decreases |driver|
  {
    if |driver| == 0 then
      []
    else
      var positioned := InclusiveGallop(excluded, driver[0]);
      if |positioned| != 0 && positioned[0] == driver[0] then
        MonotoneExclusionAntiJoin(driver[1..], positioned[1..])
      else
        [driver[0]] +
        MonotoneExclusionAntiJoin(driver[1..], positioned)
  }

  lemma FilterMembershipEquivalent(
    driver: seq<nat>,
    left: seq<nat>,
    right: seq<nat>
  )
    requires forall value <- driver :: value in left <==> value in right
    ensures GenericIntersection(driver, left) ==
            GenericIntersection(driver, right)
    ensures GenericExclusion(driver, left) ==
            GenericExclusion(driver, right)
    decreases |driver|
  {
    if |driver| != 0 {
      forall value | value in driver[1..]
        ensures value in left <==> value in right
      {
        assert value in driver;
      }
      FilterMembershipEquivalent(driver[1..], left, right);
    }
  }

  lemma PositionedOperandRetainsFutureDriverMembership(
    driver: seq<nat>,
    other: seq<nat>
  )
    requires |driver| != 0
    requires StrictlyIncreasing(driver)
    requires StrictlyIncreasing(other)
    ensures var positioned := InclusiveGallop(other, driver[0]);
            forall value <- driver[1..] ::
              value in positioned <==> value in other
    ensures var positioned := InclusiveGallop(other, driver[0]);
            |positioned| != 0 && positioned[0] == driver[0] ==>
              forall value <- driver[1..] ::
                value in positioned[1..] <==> value in other
  {
    var positioned := InclusiveGallop(other, driver[0]);
    forall value | value in driver[1..]
      ensures value in positioned <==> value in other
    {
      assert driver[0] < value;
      GallopRetainsExactlyValuesAtOrAboveTarget(
        other,
        driver[0],
        value
      );
    }
    if |positioned| != 0 && positioned[0] == driver[0] {
      forall value | value in driver[1..]
        ensures value in positioned[1..] <==> value in other
      {
        assert value != positioned[0];
        assert value in positioned <==> value in positioned[1..];
      }
    }
  }

  lemma LeapfrogIntersectionRefinesGenericFilter(
    driver: seq<nat>,
    other: seq<nat>
  )
    requires StrictlyIncreasing(driver)
    requires StrictlyIncreasing(other)
    ensures LeapfrogIntersection(driver, other) ==
            GenericIntersection(driver, other)
    decreases |driver|
  {
    if |driver| != 0 {
      var positioned := InclusiveGallop(other, driver[0]);
      GallopHeadDecidesExactMembership(other, driver[0]);
      GallopPreservesStrictOrder(other, driver[0]);
      PositionedOperandRetainsFutureDriverMembership(driver, other);
      if |positioned| != 0 && positioned[0] == driver[0] {
        assert StrictlyIncreasing(positioned[1..]);
        LeapfrogIntersectionRefinesGenericFilter(
          driver[1..],
          positioned[1..]
        );
        FilterMembershipEquivalent(
          driver[1..],
          positioned[1..],
          other
        );
      } else {
        LeapfrogIntersectionRefinesGenericFilter(
          driver[1..],
          positioned
        );
        FilterMembershipEquivalent(driver[1..], positioned, other);
      }
    }
  }

  lemma MonotoneExclusionRefinesGenericFilter(
    driver: seq<nat>,
    excluded: seq<nat>
  )
    requires StrictlyIncreasing(driver)
    requires StrictlyIncreasing(excluded)
    ensures MonotoneExclusionAntiJoin(driver, excluded) ==
            GenericExclusion(driver, excluded)
    decreases |driver|
  {
    if |driver| != 0 {
      var positioned := InclusiveGallop(excluded, driver[0]);
      GallopHeadDecidesExactMembership(excluded, driver[0]);
      GallopPreservesStrictOrder(excluded, driver[0]);
      PositionedOperandRetainsFutureDriverMembership(driver, excluded);
      if |positioned| != 0 && positioned[0] == driver[0] {
        assert StrictlyIncreasing(positioned[1..]);
        MonotoneExclusionRefinesGenericFilter(
          driver[1..],
          positioned[1..]
        );
        FilterMembershipEquivalent(
          driver[1..],
          positioned[1..],
          excluded
        );
      } else {
        MonotoneExclusionRefinesGenericFilter(
          driver[1..],
          positioned
        );
        FilterMembershipEquivalent(driver[1..], positioned, excluded);
      }
    }
  }

  predicate EveryOperandStrictlyIncreasing(
    operands: seq<seq<nat>>
  )
    decreases |operands|
  {
    |operands| == 0 ||
    (StrictlyIncreasing(operands[0]) &&
     EveryOperandStrictlyIncreasing(operands[1..]))
  }

  predicate InEveryOperand(
    operands: seq<seq<nat>>,
    value: nat
  )
    decreases |operands|
  {
    |operands| == 0 ||
    (value in operands[0] && InEveryOperand(operands[1..], value))
  }

  function PositionOperandsAtLeast(
    operands: seq<seq<nat>>,
    target: nat
  ): seq<seq<nat>>
    decreases |operands|
  {
    if |operands| == 0 then
      []
    else
      [InclusiveGallop(operands[0], target)] +
      PositionOperandsAtLeast(operands[1..], target)
  }

  predicate AnyOperandEmpty(operands: seq<seq<nat>>)
    decreases |operands|
  {
    |operands| != 0 &&
    (|operands[0]| == 0 || AnyOperandEmpty(operands[1..]))
  }

  datatype OperandPositioning = OperandPositioning(
    positioned: seq<seq<nat>>,
    seeks: nat,
    exhausted: bool
  )

  // Position operands in sealed order, but do not open any operand after the
  // first exhausted cursor has already decided the intersection is empty.
  function PositionOperandsUntilEmpty(
    operands: seq<seq<nat>>,
    target: nat
  ): OperandPositioning
    ensures PositionOperandsUntilEmpty(operands, target).seeks <=
            |operands|
    ensures !PositionOperandsUntilEmpty(operands, target).exhausted ==>
              PositionOperandsUntilEmpty(operands, target).seeks ==
              |operands|
    ensures !PositionOperandsUntilEmpty(operands, target).exhausted ==>
              |PositionOperandsUntilEmpty(operands, target).positioned| ==
              |operands|
    ensures !PositionOperandsUntilEmpty(operands, target).exhausted ==>
              !AnyOperandEmpty(
                PositionOperandsUntilEmpty(operands, target).positioned
              )
    decreases |operands|
  {
    if |operands| == 0 then
      OperandPositioning([], 0, false)
    else
      var positionedHead := InclusiveGallop(operands[0], target);
      if |positionedHead| == 0 then
        OperandPositioning([positionedHead], 1, true)
      else
        var remaining :=
          PositionOperandsUntilEmpty(operands[1..], target);
        OperandPositioning(
          [positionedHead] + remaining.positioned,
          1 + remaining.seeks,
          remaining.exhausted
        )
  }

  lemma PositionOperandsUntilEmptyProperties(
    operands: seq<seq<nat>>,
    target: nat
  )
    ensures var result :=
              PositionOperandsUntilEmpty(operands, target);
            result.seeks <= |operands|
    ensures var result :=
              PositionOperandsUntilEmpty(operands, target);
            !result.exhausted ==>
              result.positioned ==
              PositionOperandsAtLeast(operands, target)
    ensures var result :=
              PositionOperandsUntilEmpty(operands, target);
            !result.exhausted ==>
              |result.positioned| == |operands|
    ensures var result :=
              PositionOperandsUntilEmpty(operands, target);
            !result.exhausted ==>
              !AnyOperandEmpty(result.positioned)
    ensures var result :=
              PositionOperandsUntilEmpty(operands, target);
            !result.exhausted ==> result.seeks == |operands|
    ensures var result :=
              PositionOperandsUntilEmpty(operands, target);
            result.exhausted ==>
              AnyOperandEmpty(
                PositionOperandsAtLeast(operands, target)
              )
    decreases |operands|
  {
    if |operands| != 0 {
      var positionedHead := InclusiveGallop(operands[0], target);
      if |positionedHead| != 0 {
        PositionOperandsUntilEmptyProperties(
          operands[1..],
          target
        );
      }
    }
  }

  predicate HeadsAtLeast(
    operands: seq<seq<nat>>,
    lowerBound: nat
  ) {
    forall operand <- operands ::
      |operand| != 0 ==> lowerBound <= operand[0]
  }

  function MaximumHead(operands: seq<seq<nat>>): nat
    requires |operands| != 0
    requires !AnyOperandEmpty(operands)
    ensures forall operand <- operands ::
              |operand| != 0 && operand[0] <= MaximumHead(operands)
    ensures exists operand <- operands ::
              |operand| != 0 && operand[0] == MaximumHead(operands)
    decreases |operands|
  {
    if |operands| == 1 then
      operands[0][0]
    else
      var remainingMaximum := MaximumHead(operands[1..]);
      if operands[0][0] >= remainingMaximum then
        operands[0][0]
      else
        remainingMaximum
  }

  function AdvanceMatchingHeads(
    operands: seq<seq<nat>>,
    matched: nat
  ): seq<seq<nat>>
    ensures |AdvanceMatchingHeads(operands, matched)| == |operands|
    decreases |operands|
  {
    if |operands| == 0 then
      []
    else
      [(if |operands[0]| != 0 && operands[0][0] == matched then
          operands[0][1..]
        else
          operands[0])] +
      AdvanceMatchingHeads(operands[1..], matched)
  }

  datatype KWayWork = KWayWork(
    operandSeekTrace: seq<nat>,
    driverSeeks: nat
  )

  function SumNats(values: seq<nat>): nat
    decreases |values|
  {
    if |values| == 0 then 0 else values[0] + SumNats(values[1..])
  }

  predicate EveryAtMost(values: seq<nat>, bound: nat)
    decreases |values|
  {
    |values| == 0 ||
    (values[0] <= bound && EveryAtMost(values[1..], bound))
  }

  function AnchorRounds(work: KWayWork): nat
  {
    |work.operandSeekTrace|
  }

  function OperandSeeks(work: KWayWork): nat
  {
    SumNats(work.operandSeekTrace)
  }

  datatype KWayDemandResult = KWayDemandResult(
    output: seq<nat>,
    work: KWayWork
  )

  function TakeAtMost(values: seq<nat>, demand: nat): seq<nat>
    ensures |TakeAtMost(values, demand)| <= demand
    ensures |TakeAtMost(values, demand)| <= |values|
    decreases demand
  {
    if demand == 0 || |values| == 0 then
      []
    else
      [values[0]] + TakeAtMost(values[1..], demand - 1)
  }

  // Anchor-preserving k-way leapfrog. Every operand is positioned at the
  // current anchor. A larger child head jumps the anchor directly to the
  // maximum head; equality advances every matching cursor exactly once.
  function KWayLeapfrogIntersection(
    driver: seq<nat>,
    operands: seq<seq<nat>>
  ): seq<nat>
    decreases |driver|
  {
    if |operands| == 0 then
      driver
    else if |driver| == 0 then
      []
    else
      var positioned := PositionOperandsAtLeast(operands, driver[0]);
      if AnyOperandEmpty(positioned) then
        []
      else
        var target := MaximumHead(positioned);
        if target > driver[0] then
          var jumped := InclusiveGallop(driver, target);
          if |jumped| == 0 then
            []
          else
            KWayLeapfrogIntersection(jumped, positioned)
        else
          [driver[0]] +
          KWayLeapfrogIntersection(
            driver[1..],
            AdvanceMatchingHeads(positioned, driver[0])
          )
  }

  // Demand-stopping execution and its dimensional work are defined together.
  // One anchor round positions operands in sealed order until the first
  // exhausted child. A mismatched maximum head performs one inclusive driver
  // seek; a match emits one result and reduces demand. No work is issued once
  // demand is zero.
  function KWayLeapfrogForDemand(
    driver: seq<nat>,
    operands: seq<seq<nat>>,
    demand: nat
  ): KWayDemandResult
    decreases |driver|
  {
    if demand == 0 then
      KWayDemandResult([], KWayWork([], 0))
    else if |operands| == 0 then
      KWayDemandResult(
        TakeAtMost(driver, demand),
        KWayWork([], 0)
      )
    else if |driver| == 0 then
      KWayDemandResult([], KWayWork([], 0))
    else
      var positioning :=
        PositionOperandsUntilEmpty(operands, driver[0]);
      if positioning.exhausted then
        KWayDemandResult(
          [],
          KWayWork([positioning.seeks], 0)
        )
      else
        var positioned := positioning.positioned;
        var target := MaximumHead(positioned);
        if target > driver[0] then
          var jumped := InclusiveGallop(driver, target);
          if |jumped| == 0 then
            KWayDemandResult(
              [],
              KWayWork([positioning.seeks], 1)
            )
          else
            var remaining :=
              KWayLeapfrogForDemand(jumped, positioned, demand);
            KWayDemandResult(
              remaining.output,
              KWayWork(
                [positioning.seeks] +
                remaining.work.operandSeekTrace,
                1 + remaining.work.driverSeeks
              )
            )
        else
          var remaining := KWayLeapfrogForDemand(
                             driver[1..],
                             AdvanceMatchingHeads(positioned, driver[0]),
                             demand - 1
                           );
          KWayDemandResult(
            [driver[0]] + remaining.output,
            KWayWork(
              [positioning.seeks] +
              remaining.work.operandSeekTrace,
              remaining.work.driverSeeks
            )
          )
  }

  lemma PositionOperandsPreservesCount(
    operands: seq<seq<nat>>,
    target: nat
  )
    ensures |PositionOperandsAtLeast(operands, target)| == |operands|
    decreases |operands|
  {
    if |operands| != 0 {
      PositionOperandsPreservesCount(operands[1..], target);
    }
  }

  lemma AdvanceMatchingHeadsPreservesCount(
    operands: seq<seq<nat>>,
    matched: nat
  )
    ensures |AdvanceMatchingHeads(operands, matched)| == |operands|
    decreases |operands|
  {
    if |operands| != 0 {
      AdvanceMatchingHeadsPreservesCount(operands[1..], matched);
    }
  }

  lemma KWayDemandOutputMatchesFullPrefix(
    driver: seq<nat>,
    operands: seq<seq<nat>>,
    demand: nat
  )
    ensures KWayLeapfrogForDemand(driver, operands, demand).output ==
            TakeAtMost(
              KWayLeapfrogIntersection(driver, operands),
              demand
            )
    decreases |driver|
  {
    if demand != 0 && |operands| != 0 && |driver| != 0 {
      var positioning :=
        PositionOperandsUntilEmpty(operands, driver[0]);
      PositionOperandsUntilEmptyProperties(operands, driver[0]);
      if !positioning.exhausted {
        var positioned := positioning.positioned;
        var target := MaximumHead(positioned);
        if target > driver[0] {
          var jumped := InclusiveGallop(driver, target);
          if |jumped| != 0 {
            KWayDemandOutputMatchesFullPrefix(
              jumped,
              positioned,
              demand
            );
          }
        } else {
          KWayDemandOutputMatchesFullPrefix(
            driver[1..],
            AdvanceMatchingHeads(positioned, driver[0]),
            demand - 1
          );
        }
      }
    }
  }

  lemma KWayDemandAnchorRoundsAreBounded(
    driver: seq<nat>,
    operands: seq<seq<nat>>,
    demand: nat
  )
    ensures var result :=
              KWayLeapfrogForDemand(driver, operands, demand);
            AnchorRounds(result.work) <= |driver|
    decreases |driver|
  {
    if demand != 0 && |operands| != 0 && |driver| != 0 {
      var positioning :=
        PositionOperandsUntilEmpty(operands, driver[0]);
      PositionOperandsUntilEmptyProperties(operands, driver[0]);
      if !positioning.exhausted {
        var positioned := positioning.positioned;
        var target := MaximumHead(positioned);
        if target > driver[0] {
          var jumped := InclusiveGallop(driver, target);
          if |jumped| != 0 {
            KWayDemandAnchorRoundsAreBounded(
              jumped,
              positioned,
              demand
            );
            assert |jumped| < |driver|;
          }
        } else {
          var advanced := AdvanceMatchingHeads(
            positioned,
            driver[0]
          );
          KWayDemandAnchorRoundsAreBounded(
            driver[1..],
            advanced,
            demand - 1
          );
        }
      }
    }
  }

  lemma KWayDemandOperandSeekTraceIsBounded(
    driver: seq<nat>,
    operands: seq<seq<nat>>,
    demand: nat
  )
    ensures var result :=
              KWayLeapfrogForDemand(driver, operands, demand);
            EveryAtMost(result.work.operandSeekTrace, |operands|)
    decreases |driver|
  {
    if demand != 0 && |operands| != 0 && |driver| != 0 {
      var positioning :=
        PositionOperandsUntilEmpty(operands, driver[0]);
      PositionOperandsUntilEmptyProperties(operands, driver[0]);
      if !positioning.exhausted {
        var positioned := positioning.positioned;
        PositionOperandsPreservesCount(operands, driver[0]);
        var target := MaximumHead(positioned);
        if target > driver[0] {
          var jumped := InclusiveGallop(driver, target);
          if |jumped| != 0 {
            KWayDemandOperandSeekTraceIsBounded(
              jumped,
              positioned,
              demand
            );
            assert positioning.seeks == |operands|;
            assert |positioned| == |operands|;
          }
        } else {
          var advanced := AdvanceMatchingHeads(
            positioned,
            driver[0]
          );
          AdvanceMatchingHeadsPreservesCount(
            positioned,
            driver[0]
          );
          KWayDemandOperandSeekTraceIsBounded(
            driver[1..],
            advanced,
            demand - 1
          );
          assert positioning.seeks == |operands|;
          assert |advanced| == |operands|;
        }
      }
    }
  }

  lemma {:isolate_assertions} SumAtMostProduct(
    values: seq<nat>,
    bound: nat
  )
    requires EveryAtMost(values, bound)
    ensures SumNats(values) <= bound * |values|
    decreases |values|
  {
    if |values| != 0 {
      SumAtMostProduct(values[1..], bound);
      calc {
         SumNats(values);
      == values[0] + SumNats(values[1..]);
      <= bound + SumNats(values[1..]);
      <= bound + bound * |values[1..]|;
      == bound * (1 + |values[1..]|);
      == bound * |values|;
      }
    }
  }

  lemma KWayDemandOperandSeeksAreBounded(
    driver: seq<nat>,
    operands: seq<seq<nat>>,
    demand: nat
  )
    ensures var result :=
              KWayLeapfrogForDemand(driver, operands, demand);
            OperandSeeks(result.work) <=
            |operands| * AnchorRounds(result.work)
  {
    KWayDemandOperandSeekTraceIsBounded(driver, operands, demand);
    var result := KWayLeapfrogForDemand(driver, operands, demand);
    SumAtMostProduct(result.work.operandSeekTrace, |operands|);
  }

  lemma KWayDemandDriverSeeksAreBounded(
    driver: seq<nat>,
    operands: seq<seq<nat>>,
    demand: nat
  )
    ensures var result :=
              KWayLeapfrogForDemand(driver, operands, demand);
            result.work.driverSeeks <= AnchorRounds(result.work)
    decreases |driver|
  {
    if demand != 0 && |operands| != 0 && |driver| != 0 {
      var positioning :=
        PositionOperandsUntilEmpty(operands, driver[0]);
      PositionOperandsUntilEmptyProperties(operands, driver[0]);
      if !positioning.exhausted {
        var positioned := positioning.positioned;
        var target := MaximumHead(positioned);
        if target > driver[0] {
          var jumped := InclusiveGallop(driver, target);
          if |jumped| != 0 {
            KWayDemandDriverSeeksAreBounded(
              jumped,
              positioned,
              demand
            );
          }
        } else {
          KWayDemandDriverSeeksAreBounded(
            driver[1..],
            AdvanceMatchingHeads(positioned, driver[0]),
            demand - 1
          );
        }
      }
    }
  }

  lemma KWayDemandOutputIsBounded(
    driver: seq<nat>,
    operands: seq<seq<nat>>,
    demand: nat
  )
    ensures var result :=
              KWayLeapfrogForDemand(driver, operands, demand);
            |result.output| <= demand
  {
    KWayDemandOutputMatchesFullPrefix(driver, operands, demand);
  }

  lemma ProductMonotonic(
    coefficient: nat,
    smaller: nat,
    larger: nat
  )
    requires smaller <= larger
    ensures coefficient * smaller <= coefficient * larger
  {
  }

  lemma KWayDemandWorkIsBounded(
    driver: seq<nat>,
    operands: seq<seq<nat>>,
    demand: nat
  )
    ensures var result :=
              KWayLeapfrogForDemand(driver, operands, demand);
            AnchorRounds(result.work) <= |driver|
    ensures var result :=
              KWayLeapfrogForDemand(driver, operands, demand);
            OperandSeeks(result.work) <=
            |operands| * AnchorRounds(result.work)
    ensures var result :=
              KWayLeapfrogForDemand(driver, operands, demand);
            result.work.driverSeeks <= AnchorRounds(result.work)
    ensures var result :=
              KWayLeapfrogForDemand(driver, operands, demand);
            OperandSeeks(result.work) + result.work.driverSeeks <=
            (|operands| + 1) * |driver|
    ensures var result :=
              KWayLeapfrogForDemand(driver, operands, demand);
            |result.output| <= demand
  {
    KWayDemandAnchorRoundsAreBounded(driver, operands, demand);
    KWayDemandOperandSeeksAreBounded(driver, operands, demand);
    KWayDemandDriverSeeksAreBounded(driver, operands, demand);
    KWayDemandOutputIsBounded(driver, operands, demand);
    var result := KWayLeapfrogForDemand(driver, operands, demand);
    ProductMonotonic(
      |operands| + 1,
      AnchorRounds(result.work),
      |driver|
    );
    assert OperandSeeks(result.work) + result.work.driverSeeks <=
           |operands| * AnchorRounds(result.work) +
           AnchorRounds(result.work);
    assert |operands| * AnchorRounds(result.work) +
      AnchorRounds(result.work) ==
           (|operands| + 1) * AnchorRounds(result.work);
  }

  lemma PositionOperandsPreserveStrictOrder(
    operands: seq<seq<nat>>,
    target: nat
  )
    requires EveryOperandStrictlyIncreasing(operands)
    ensures EveryOperandStrictlyIncreasing(
              PositionOperandsAtLeast(operands, target)
            )
    decreases |operands|
  {
    if |operands| != 0 {
      GallopPreservesStrictOrder(operands[0], target);
      PositionOperandsPreserveStrictOrder(operands[1..], target);
    }
  }

  lemma PositionOperandsRetainAtOrAbove(
    operands: seq<seq<nat>>,
    target: nat,
    value: nat
  )
    requires target <= value
    ensures InEveryOperand(
              PositionOperandsAtLeast(operands, target),
              value
            ) == InEveryOperand(operands, value)
    decreases |operands|
  {
    if |operands| != 0 {
      GallopRetainsExactlyValuesAtOrAboveTarget(
        operands[0],
        target,
        value
      );
      PositionOperandsRetainAtOrAbove(
        operands[1..],
        target,
        value
      );
    }
  }

  lemma PositionedHeadsRespectLowerBound(
    operands: seq<seq<nat>>,
    target: nat
  )
    ensures HeadsAtLeast(PositionOperandsAtLeast(operands, target), target)
    decreases |operands|
  {
    if |operands| != 0 {
      PositionedHeadsRespectLowerBound(operands[1..], target);
    }
  }

  lemma EmptyOperandMakesEveryFalse(
    operands: seq<seq<nat>>,
    value: nat
  )
    requires AnyOperandEmpty(operands)
    ensures !InEveryOperand(operands, value)
    decreases |operands|
  {
    if |operands[0]| != 0 {
      EmptyOperandMakesEveryFalse(operands[1..], value);
    }
  }

  lemma StrictHeadIsAtMostMember(
    values: seq<nat>,
    value: nat
  )
    requires StrictlyIncreasing(values)
    requires value in values
    ensures |values| != 0 && values[0] <= value
  {
    if value != values[0] {
      assert value in values[1..];
      assert values[0] < value;
    }
  }

  lemma InEveryOperandMeansInEach(
    operands: seq<seq<nat>>,
    value: nat
  )
    requires InEveryOperand(operands, value)
    ensures forall operand <- operands :: value in operand
    decreases |operands|
  {
    if |operands| != 0 {
      InEveryOperandMeansInEach(operands[1..], value);
    }
  }

  lemma EveryOperandStrictMeansEachStrict(
    operands: seq<seq<nat>>
  )
    requires EveryOperandStrictlyIncreasing(operands)
    ensures forall operand <- operands :: StrictlyIncreasing(operand)
    decreases |operands|
  {
    if |operands| != 0 {
      EveryOperandStrictMeansEachStrict(operands[1..]);
    }
  }

  lemma EveryMemberBoundsMaximumHead(
    operands: seq<seq<nat>>,
    value: nat
  )
    requires |operands| != 0
    requires !AnyOperandEmpty(operands)
    requires EveryOperandStrictlyIncreasing(operands)
    requires InEveryOperand(operands, value)
    ensures MaximumHead(operands) <= value
    decreases |operands|
  {
    StrictHeadIsAtMostMember(operands[0], value);
    if |operands| != 1 {
      EveryMemberBoundsMaximumHead(operands[1..], value);
    }
  }

  lemma MaximumHeadBlocksEverySmallerValue(
    operands: seq<seq<nat>>,
    value: nat
  )
    requires |operands| != 0
    requires !AnyOperandEmpty(operands)
    requires EveryOperandStrictlyIncreasing(operands)
    requires value < MaximumHead(operands)
    ensures !InEveryOperand(operands, value)
  {
    var blocking :| blocking in operands &&
                    |blocking| != 0 &&
                    blocking[0] == MaximumHead(operands);
    if InEveryOperand(operands, value) {
      InEveryOperandMeansInEach(operands, value);
      EveryOperandStrictMeansEachStrict(operands);
      assert value in blocking;
      StrictHeadIsAtMostMember(blocking, value);
      assert false;
    }
  }

  lemma MaximumAtLowerBoundMeansAllHeadsMatch(
    operands: seq<seq<nat>>,
    lowerBound: nat
  )
    requires |operands| != 0
    requires !AnyOperandEmpty(operands)
    requires HeadsAtLeast(operands, lowerBound)
    requires MaximumHead(operands) <= lowerBound
    ensures forall operand <- operands ::
              |operand| != 0 && operand[0] == lowerBound
  {
  }

  lemma MatchingHeadsAreInEveryOperand(
    operands: seq<seq<nat>>,
    matched: nat
  )
    requires forall operand <- operands ::
               |operand| != 0 && operand[0] == matched
    ensures InEveryOperand(operands, matched)
    decreases |operands|
  {
    if |operands| != 0 {
      MatchingHeadsAreInEveryOperand(operands[1..], matched);
    }
  }

  lemma AdvanceMatchingHeadsPreservesStrictOrder(
    operands: seq<seq<nat>>,
    matched: nat
  )
    requires EveryOperandStrictlyIncreasing(operands)
    ensures EveryOperandStrictlyIncreasing(
              AdvanceMatchingHeads(operands, matched)
            )
    decreases |operands|
  {
    if |operands| != 0 {
      AdvanceMatchingHeadsPreservesStrictOrder(
        operands[1..],
        matched
      );
    }
  }

  lemma AdvanceMatchingHeadsRetainsLargerMembership(
    operands: seq<seq<nat>>,
    matched: nat,
    value: nat
  )
    requires EveryOperandStrictlyIncreasing(operands)
    requires forall operand <- operands ::
               |operand| != 0 && operand[0] == matched
    requires matched < value
    ensures InEveryOperand(
              AdvanceMatchingHeads(operands, matched),
              value
            ) == InEveryOperand(operands, value)
    decreases |operands|
  {
    if |operands| != 0 {
      assert value != operands[0][0];
      assert value in operands[0] <==> value in operands[0][1..];
      AdvanceMatchingHeadsRetainsLargerMembership(
        operands[1..],
        matched,
        value
      );
    }
  }

  lemma KWayLeapfrogMembership(
    driver: seq<nat>,
    operands: seq<seq<nat>>,
    value: nat
  )
    requires StrictlyIncreasing(driver)
    requires EveryOperandStrictlyIncreasing(operands)
    ensures (value in KWayLeapfrogIntersection(driver, operands) <==>
             value in driver && InEveryOperand(operands, value))
    decreases |driver|
  {
    if |operands| == 0 || |driver| == 0 {
      return;
    }

    var head := driver[0];
    var positioned := PositionOperandsAtLeast(operands, head);
    PositionOperandsPreserveStrictOrder(operands, head);
    PositionedHeadsRespectLowerBound(operands, head);

    if AnyOperandEmpty(positioned) {
      EmptyOperandMakesEveryFalse(positioned, value);
      if value in driver {
        StrictHeadIsAtMostMember(driver, value);
        PositionOperandsRetainAtOrAbove(operands, head, value);
      }
      return;
    }

    var target := MaximumHead(positioned);
    if target > head {
      var jumped := InclusiveGallop(driver, target);
      GallopPreservesStrictOrder(driver, target);
      if |jumped| == 0 {
        if value in driver && InEveryOperand(operands, value) {
          assert head <= value;
          PositionOperandsRetainAtOrAbove(operands, head, value);
          EveryMemberBoundsMaximumHead(positioned, value);
          GallopRetainsExactlyValuesAtOrAboveTarget(
            driver,
            target,
            value
          );
          assert false;
        }
      } else {
        KWayLeapfrogMembership(jumped, positioned, value);
        if value in driver && InEveryOperand(operands, value) {
          assert head <= value;
          PositionOperandsRetainAtOrAbove(operands, head, value);
          EveryMemberBoundsMaximumHead(positioned, value);
          GallopRetainsExactlyValuesAtOrAboveTarget(
            driver,
            target,
            value
          );
        }
        if value in jumped && InEveryOperand(positioned, value) {
          assert target <= value;
          GallopRetainsExactlyValuesAtOrAboveTarget(
            driver,
            target,
            value
          );
          PositionOperandsRetainAtOrAbove(operands, head, value);
        }
      }
    } else {
      MaximumAtLowerBoundMeansAllHeadsMatch(positioned, head);
      assert forall operand <- positioned ::
          |operand| != 0 && operand[0] == head;
      MatchingHeadsAreInEveryOperand(positioned, head);
      PositionOperandsRetainAtOrAbove(operands, head, head);
      AdvanceMatchingHeadsPreservesStrictOrder(positioned, head);
      KWayLeapfrogMembership(
        driver[1..],
        AdvanceMatchingHeads(positioned, head),
        value
      );
      if value in driver[1..] {
        assert head < value;
        AdvanceMatchingHeadsRetainsLargerMembership(
          positioned,
          head,
          value
        );
        PositionOperandsRetainAtOrAbove(operands, head, value);
      }
    }
  }

  lemma KWayLeapfrogPreservesStrictOrder(
    driver: seq<nat>,
    operands: seq<seq<nat>>
  )
    requires StrictlyIncreasing(driver)
    requires EveryOperandStrictlyIncreasing(operands)
    ensures StrictlyIncreasing(KWayLeapfrogIntersection(driver, operands))
    decreases |driver|
  {
    if |operands| == 0 || |driver| == 0 {
      return;
    }
    var head := driver[0];
    var positioned := PositionOperandsAtLeast(operands, head);
    PositionOperandsPreserveStrictOrder(operands, head);
    PositionedHeadsRespectLowerBound(operands, head);
    if !AnyOperandEmpty(positioned) {
      var target := MaximumHead(positioned);
      if target > head {
        var jumped := InclusiveGallop(driver, target);
        GallopPreservesStrictOrder(driver, target);
        if |jumped| != 0 {
          KWayLeapfrogPreservesStrictOrder(jumped, positioned);
        }
      } else {
        MaximumAtLowerBoundMeansAllHeadsMatch(positioned, head);
        AdvanceMatchingHeadsPreservesStrictOrder(positioned, head);
        KWayLeapfrogPreservesStrictOrder(
          driver[1..],
          AdvanceMatchingHeads(positioned, head)
        );
        forall outputValue |
          outputValue in KWayLeapfrogIntersection(
                           driver[1..],
                           AdvanceMatchingHeads(positioned, head)
                         )
          ensures head < outputValue
        {
          KWayLeapfrogMembership(
            driver[1..],
            AdvanceMatchingHeads(positioned, head),
            outputValue
          );
          assert outputValue in driver[1..];
        }
      }
    }
  }

  lemma StrictlyIncreasingSequencesWithSameMembersAreEqual(
    left: seq<nat>,
    right: seq<nat>
  )
    requires StrictlyIncreasing(left)
    requires StrictlyIncreasing(right)
    requires forall value: nat :: value in left <==> value in right
    ensures left == right
    decreases |left| + |right|
  {
    if |left| == 0 {
      if |right| != 0 {
        assert right[0] in right;
        assert right[0] in left;
        assert false;
      }
    } else if |right| == 0 {
      assert left[0] in left;
      assert left[0] in right;
      assert false;
    } else {
      if left[0] != right[0] {
        assert left[0] in left;
        assert left[0] in right;
        assert right[0] in right;
        assert right[0] in left;
        if left[0] in right[1..] {
          assert right[0] < left[0];
        }
        if right[0] in left[1..] {
          assert left[0] < right[0];
        }
        assert false;
      }
      forall value: nat
        ensures value in left[1..] <==> value in right[1..]
      {
        if value == left[0] {
          assert value !in left[1..];
          assert value !in right[1..];
        } else {
          assert value in left <==> value in left[1..];
          assert value in right <==> value in right[1..];
        }
      }
      StrictlyIncreasingSequencesWithSameMembersAreEqual(
        left[1..],
        right[1..]
      );
    }
  }

  // Generic semantics preserve the sealed anchor and test exact membership
  // in every remaining operand.
  function GenericNaryIntersection(
    driver: seq<nat>,
    operands: seq<seq<nat>>
  ): seq<nat>
    decreases |operands|
  {
    if |operands| == 0 then
      driver
    else
      GenericNaryIntersection(
        GenericIntersection(driver, operands[0]),
        operands[1..]
      )
  }

  function SeekableNaryIntersection(
    driver: seq<nat>,
    operands: seq<seq<nat>>
  ): seq<nat>
  {
    KWayLeapfrogIntersection(driver, operands)
  }

  lemma GenericIntersectionPreservesStrictOrder(
    driver: seq<nat>,
    other: seq<nat>
  )
    requires StrictlyIncreasing(driver)
    ensures StrictlyIncreasing(GenericIntersection(driver, other))
    decreases |driver|
  {
    if |driver| != 0 {
      GenericIntersectionPreservesStrictOrder(driver[1..], other);
      if driver[0] in other {
        forall value | value in GenericIntersection(driver[1..], other)
          ensures driver[0] < value
        {
          GenericIntersectionMembership(
            driver[1..],
            other,
            value
          );
          assert value in driver[1..];
        }
        assert GenericIntersection(driver, other) ==
               [driver[0]] +
               GenericIntersection(driver[1..], other);
      } else {
        assert GenericIntersection(driver, other) ==
               GenericIntersection(driver[1..], other);
      }
    }
  }

  lemma GenericNaryIntersectionMembership(
    driver: seq<nat>,
    operands: seq<seq<nat>>,
    value: nat
  )
    ensures value in GenericNaryIntersection(driver, operands) <==>
            value in driver && InEveryOperand(operands, value)
    decreases |operands|
  {
    if |operands| != 0 {
      GenericIntersectionMembership(driver, operands[0], value);
      GenericNaryIntersectionMembership(
        GenericIntersection(driver, operands[0]),
        operands[1..],
        value
      );
    }
  }

  lemma GenericNaryIntersectionPreservesStrictOrder(
    driver: seq<nat>,
    operands: seq<seq<nat>>
  )
    requires StrictlyIncreasing(driver)
    ensures StrictlyIncreasing(GenericNaryIntersection(driver, operands))
    decreases |operands|
  {
    if |operands| != 0 {
      GenericIntersectionPreservesStrictOrder(driver, operands[0]);
      GenericNaryIntersectionPreservesStrictOrder(
        GenericIntersection(driver, operands[0]),
        operands[1..]
      );
    }
  }

  lemma SeekableNaryIntersectionRefinesGenericAnchorFilter(
    driver: seq<nat>,
    operands: seq<seq<nat>>
  )
    requires StrictlyIncreasing(driver)
    requires EveryOperandStrictlyIncreasing(operands)
    ensures SeekableNaryIntersection(driver, operands) ==
            GenericNaryIntersection(driver, operands)
    ensures StrictlyIncreasing(
              SeekableNaryIntersection(driver, operands)
            )
  {
    KWayLeapfrogPreservesStrictOrder(driver, operands);
    GenericNaryIntersectionPreservesStrictOrder(driver, operands);
    forall value: nat
      ensures value in SeekableNaryIntersection(driver, operands) <==>
              value in GenericNaryIntersection(driver, operands)
    {
      KWayLeapfrogMembership(driver, operands, value);
      GenericNaryIntersectionMembership(driver, operands, value);
    }
    StrictlyIncreasingSequencesWithSameMembersAreEqual(
      SeekableNaryIntersection(driver, operands),
      GenericNaryIntersection(driver, operands)
    );
  }

  function GenericNaryLogicalBoundary(
    driver: seq<nat>,
    operands: seq<seq<nat>>,
    demand: nat
  ): nat {
    AdaptiveBatching.PrefixForDemand(
      OutputDecisions(
        driver,
        GenericNaryIntersection(driver, operands)
      ),
      demand
    )
  }

  function SeekableNaryLogicalBoundary(
    driver: seq<nat>,
    operands: seq<seq<nat>>,
    demand: nat
  ): nat {
    AdaptiveBatching.PrefixForDemand(
      OutputDecisions(
        driver,
        SeekableNaryIntersection(driver, operands)
      ),
      demand
    )
  }

  lemma SeekableNarySequenceAndBoundaryMatchGeneric(
    driver: seq<nat>,
    operands: seq<seq<nat>>,
    demand: nat
  )
    requires StrictlyIncreasing(driver)
    requires EveryOperandStrictlyIncreasing(operands)
    ensures SeekableNaryIntersection(driver, operands) ==
            GenericNaryIntersection(driver, operands)
    ensures SeekableNaryLogicalBoundary(driver, operands, demand) ==
            GenericNaryLogicalBoundary(driver, operands, demand)
  {
    SeekableNaryIntersectionRefinesGenericAnchorFilter(
      driver,
      operands
    );
  }

  function MembershipDecisions(
    driver: seq<nat>,
    other: seq<nat>,
    intersection: bool
  ): seq<bool>
    decreases |driver|
  {
    if |driver| == 0 then
      []
    else
      [(if intersection then driver[0] in other else driver[0] !in other)] +
      MembershipDecisions(driver[1..], other, intersection)
  }

  function OutputDecisions(
    driver: seq<nat>,
    output: seq<nat>
  ): seq<bool>
    decreases |driver|
  {
    if |driver| == 0 then
      []
    else
      [driver[0] in output] + OutputDecisions(driver[1..], output)
  }

  lemma GenericIntersectionMembership(
    driver: seq<nat>,
    other: seq<nat>,
    value: nat
  )
    ensures value in GenericIntersection(driver, other) <==>
            value in driver && value in other
    decreases |driver|
  {
    if |driver| != 0 {
      GenericIntersectionMembership(driver[1..], other, value);
    }
  }

  lemma GenericExclusionMembership(
    driver: seq<nat>,
    excluded: seq<nat>,
    value: nat
  )
    ensures value in GenericExclusion(driver, excluded) <==>
            value in driver && value !in excluded
    decreases |driver|
  {
    if |driver| != 0 {
      GenericExclusionMembership(driver[1..], excluded, value);
    }
  }

  lemma GenericOutputDecisionsFromFullDriverMatchMembership(
    driver: seq<nat>,
    fullDriver: seq<nat>,
    other: seq<nat>,
    intersection: bool
  )
    requires forall value <- driver :: value in fullDriver
    ensures OutputDecisions(
              driver,
              if intersection then
                GenericIntersection(fullDriver, other)
              else
                GenericExclusion(fullDriver, other)
            ) == MembershipDecisions(driver, other, intersection)
    decreases |driver|
  {
    if |driver| != 0 {
      assert driver[0] in fullDriver;
      GenericIntersectionMembership(fullDriver, other, driver[0]);
      GenericExclusionMembership(fullDriver, other, driver[0]);
      forall value | value in driver[1..]
        ensures value in fullDriver
      {
        assert value in driver;
      }
      GenericOutputDecisionsFromFullDriverMatchMembership(
        driver[1..],
        fullDriver,
        other,
        intersection
      );
    }
  }

  lemma GenericOutputDecisionsMatchMembership(
    driver: seq<nat>,
    other: seq<nat>,
    intersection: bool
  )
    ensures OutputDecisions(
              driver,
              if intersection then
                GenericIntersection(driver, other)
              else
                GenericExclusion(driver, other)
            ) == MembershipDecisions(driver, other, intersection)
  {
    GenericOutputDecisionsFromFullDriverMatchMembership(
      driver,
      driver,
      other,
      intersection
    );
  }

  function GenericLogicalBoundary(
    driver: seq<nat>,
    other: seq<nat>,
    intersection: bool,
    demand: nat
  ): nat {
    AdaptiveBatching.PrefixForDemand(
      MembershipDecisions(driver, other, intersection),
      demand
    )
  }

  function SpecializedLogicalBoundary(
    driver: seq<nat>,
    other: seq<nat>,
    intersection: bool,
    demand: nat
  ): nat {
    AdaptiveBatching.PrefixForDemand(
      OutputDecisions(
        driver,
        if intersection then
          LeapfrogIntersection(driver, other)
        else
          MonotoneExclusionAntiJoin(driver, other)
      ),
      demand
    )
  }

  lemma SpecializedSequencesAndLogicalBoundariesMatchGeneric(
    driver: seq<nat>,
    other: seq<nat>,
    demand: nat
  )
    requires StrictlyIncreasing(driver)
    requires StrictlyIncreasing(other)
    ensures LeapfrogIntersection(driver, other) ==
            GenericIntersection(driver, other)
    ensures MonotoneExclusionAntiJoin(driver, other) ==
            GenericExclusion(driver, other)
    ensures SpecializedLogicalBoundary(driver, other, true, demand) ==
            GenericLogicalBoundary(driver, other, true, demand)
    ensures SpecializedLogicalBoundary(driver, other, false, demand) ==
            GenericLogicalBoundary(driver, other, false, demand)
  {
    LeapfrogIntersectionRefinesGenericFilter(driver, other);
    MonotoneExclusionRefinesGenericFilter(driver, other);
    GenericOutputDecisionsMatchMembership(driver, other, true);
    GenericOutputDecisionsMatchMembership(driver, other, false);
  }
}

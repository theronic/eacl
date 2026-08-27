include "CandidateCover.dfy"
include "AdaptiveBatching.dfy"

// Filtered least-path order, pagination, and logical continuation boundaries.
module OperatorLeastPath {
  import opened CandidateCover
  import AdaptiveBatching

  datatype Direction = Ascending | Descending

  datatype CoverItem = CoverItem(
    entity: Entity,
    leastPathRank: nat
  )

  predicate StrictlyIncreasing(items: seq<CoverItem>)
    decreases |items|
  {
    |items| == 0 ||
    ((forall item <- items[1..] ::
        items[0].leastPathRank < item.leastPathRank) &&
     StrictlyIncreasing(items[1..]))
  }

  predicate StrictlyDecreasing(items: seq<CoverItem>)
    decreases |items|
  {
    |items| == 0 ||
    ((forall item <- items[1..] ::
        items[0].leastPathRank > item.leastPathRank) &&
     StrictlyDecreasing(items[1..]))
  }

  predicate ExactUniqueEntities(items: seq<CoverItem>) {
    forall left, right | 0 <= left < right < |items| ::
      items[left].entity != items[right].entity
  }

  function ItemEntities(items: seq<CoverItem>): set<Entity> {
    set item <- items :: item.entity
  }

  predicate ExactAscendingCover(
    items: seq<CoverItem>,
    cover: set<Entity>
  ) {
    StrictlyIncreasing(items) &&
    ExactUniqueEntities(items) &&
    ItemEntities(items) == cover
  }

  function FilterAllowed(
    items: seq<CoverItem>,
    allowed: set<Entity>
  ): seq<CoverItem>
    decreases |items|
  {
    if |items| == 0 then
      []
    else
      (if items[0].entity in allowed then [items[0]] else []) +
      FilterAllowed(items[1..], allowed)
  }

  lemma FilterMembershipIsExact(
    items: seq<CoverItem>,
    allowed: set<Entity>,
    item: CoverItem
  )
    ensures item in FilterAllowed(items, allowed) <==>
            item in items && item.entity in allowed
    decreases |items|
  {
    if |items| != 0 {
      FilterMembershipIsExact(items[1..], allowed, item);
    }
  }

  lemma FilterPreservesIncreasingLeastPathOrder(
    items: seq<CoverItem>,
    allowed: set<Entity>
  )
    requires StrictlyIncreasing(items)
    ensures StrictlyIncreasing(FilterAllowed(items, allowed))
    decreases |items|
  {
    if |items| != 0 {
      FilterPreservesIncreasingLeastPathOrder(items[1..], allowed);
      if items[0].entity in allowed {
        forall item | item in FilterAllowed(items[1..], allowed)
          ensures items[0].leastPathRank < item.leastPathRank
        {
          FilterMembershipIsExact(items[1..], allowed, item);
        }
        assert FilterAllowed(items, allowed) ==
               [items[0]] + FilterAllowed(items[1..], allowed);
      } else {
        assert FilterAllowed(items, allowed) ==
               FilterAllowed(items[1..], allowed);
      }
    }
  }

  lemma FilterPreservesDecreasingLeastPathOrder(
    items: seq<CoverItem>,
    allowed: set<Entity>
  )
    requires StrictlyDecreasing(items)
    ensures StrictlyDecreasing(FilterAllowed(items, allowed))
    decreases |items|
  {
    if |items| != 0 {
      FilterPreservesDecreasingLeastPathOrder(items[1..], allowed);
      if items[0].entity in allowed {
        forall item | item in FilterAllowed(items[1..], allowed)
          ensures items[0].leastPathRank > item.leastPathRank
        {
          FilterMembershipIsExact(items[1..], allowed, item);
        }
        assert FilterAllowed(items, allowed) ==
               [items[0]] + FilterAllowed(items[1..], allowed);
      } else {
        assert FilterAllowed(items, allowed) ==
               FilterAllowed(items[1..], allowed);
      }
    }
  }

  lemma FilterPreservesExactUniqueness(
    items: seq<CoverItem>,
    allowed: set<Entity>
  )
    requires ExactUniqueEntities(items)
    ensures ExactUniqueEntities(FilterAllowed(items, allowed))
    decreases |items|
  {
    if |items| != 0 {
      assert ExactUniqueEntities(items[1..]);
      FilterPreservesExactUniqueness(items[1..], allowed);
      if items[0].entity in allowed {
        forall item | item in FilterAllowed(items[1..], allowed)
          ensures items[0].entity != item.entity
        {
          FilterMembershipIsExact(items[1..], allowed, item);
        }
      }
    }
  }

  lemma FilteredEntitySetIsExactIntersection(
    items: seq<CoverItem>,
    allowed: set<Entity>
  )
    ensures ItemEntities(FilterAllowed(items, allowed)) ==
            ItemEntities(items) * allowed
  {
    forall entity: Entity
      ensures entity in ItemEntities(FilterAllowed(items, allowed)) <==>
              entity in ItemEntities(items) * allowed
    {
      if entity in ItemEntities(FilterAllowed(items, allowed)) {
        var item :| item in FilterAllowed(items, allowed) &&
                    item.entity == entity;
        FilterMembershipIsExact(items, allowed, item);
      }
      if entity in ItemEntities(items) * allowed {
        var item :| item in items && item.entity == entity;
        FilterMembershipIsExact(items, allowed, item);
      }
    }
  }

  lemma ExactCoverFilteringProducesExactOrderedResult(
    items: seq<CoverItem>,
    cover: set<Entity>,
    allowed: set<Entity>
  )
    requires ExactAscendingCover(items, cover)
    ensures StrictlyIncreasing(FilterAllowed(items, allowed))
    ensures ExactUniqueEntities(FilterAllowed(items, allowed))
    ensures ItemEntities(FilterAllowed(items, allowed)) == cover * allowed
  {
    FilterPreservesIncreasingLeastPathOrder(items, allowed);
    FilterPreservesExactUniqueness(items, allowed);
    FilteredEntitySetIsExactIntersection(items, allowed);
  }

  function Reverse(items: seq<CoverItem>): seq<CoverItem>
    decreases |items|
  {
    if |items| == 0 then [] else Reverse(items[1..]) + [items[0]]
  }

  lemma ReverseMembership(items: seq<CoverItem>, item: CoverItem)
    ensures item in Reverse(items) <==> item in items
    decreases |items|
  {
    if |items| != 0 {
      ReverseMembership(items[1..], item);
    }
  }

  lemma ReversePreservesExactUniqueness(items: seq<CoverItem>)
    requires ExactUniqueEntities(items)
    ensures ExactUniqueEntities(Reverse(items))
    decreases |items|
  {
    if |items| != 0 {
      assert ExactUniqueEntities(items[1..]);
      ReversePreservesExactUniqueness(items[1..]);
      forall prefixItem | prefixItem in Reverse(items[1..])
        ensures prefixItem.entity != items[0].entity
      {
        ReverseMembership(items[1..], prefixItem);
        assert prefixItem in items[1..];
      }
    }
  }

  lemma AppendSmallerPreservesDecreasing(
    items: seq<CoverItem>,
    smaller: CoverItem
  )
    requires StrictlyDecreasing(items)
    requires forall item <- items ::
               item.leastPathRank > smaller.leastPathRank
    ensures StrictlyDecreasing(items + [smaller])
    decreases |items|
  {
    if |items| != 0 {
      assert StrictlyDecreasing(items[1..]);
      forall item | item in items[1..]
        ensures item.leastPathRank > smaller.leastPathRank
      {
        assert item in items;
      }
      AppendSmallerPreservesDecreasing(items[1..], smaller);
      forall item | item in items[1..] + [smaller]
        ensures items[0].leastPathRank > item.leastPathRank
      {
        if item == smaller {
          assert items[0] in items;
        } else {
          assert item in items[1..];
        }
      }
      assert items + [smaller] ==
             [items[0]] + (items[1..] + [smaller]);
    }
  }

  lemma ReverseIncreasingIsDecreasing(items: seq<CoverItem>)
    requires StrictlyIncreasing(items)
    ensures StrictlyDecreasing(Reverse(items))
    decreases |items|
  {
    if |items| != 0 {
      assert StrictlyIncreasing(items[1..]);
      ReverseIncreasingIsDecreasing(items[1..]);
      forall reversedItem | reversedItem in Reverse(items[1..])
        ensures reversedItem.leastPathRank > items[0].leastPathRank
      {
        ReverseMembership(items[1..], reversedItem);
        assert reversedItem in items[1..];
      }
      AppendSmallerPreservesDecreasing(
        Reverse(items[1..]),
        items[0]
      );
    }
  }

  lemma DirectedCoverHasCertifiedOrderAndUniqueness(
    ascendingCover: seq<CoverItem>,
    direction: Direction
  )
    requires StrictlyIncreasing(ascendingCover)
    requires ExactUniqueEntities(ascendingCover)
    ensures direction.Ascending? ==>
              StrictlyIncreasing(Directed(ascendingCover, direction))
    ensures direction.Descending? ==>
              StrictlyDecreasing(Directed(ascendingCover, direction))
    ensures ExactUniqueEntities(Directed(ascendingCover, direction))
  {
    if direction.Descending? {
      ReverseIncreasingIsDecreasing(ascendingCover);
      ReversePreservesExactUniqueness(ascendingCover);
    }
  }

  lemma DirectedFilteredCoverIsExactAndOrdered(
    ascendingCover: seq<CoverItem>,
    cover: set<Entity>,
    allowed: set<Entity>,
    direction: Direction
  )
    requires ExactAscendingCover(ascendingCover, cover)
    ensures direction.Ascending? ==>
              StrictlyIncreasing(
                FilterAllowed(
                  Directed(ascendingCover, direction),
                  allowed
                )
              )
    ensures direction.Descending? ==>
              StrictlyDecreasing(
                FilterAllowed(
                  Directed(ascendingCover, direction),
                  allowed
                )
              )
    ensures ExactUniqueEntities(
              FilterAllowed(
                Directed(ascendingCover, direction),
                allowed
              )
            )
    ensures ItemEntities(
              FilterAllowed(
                Directed(ascendingCover, direction),
                allowed
              )
            ) == cover * allowed
  {
    DirectedCoverHasCertifiedOrderAndUniqueness(
      ascendingCover,
      direction
    );
    if direction.Ascending? {
      FilterPreservesIncreasingLeastPathOrder(
        Directed(ascendingCover, direction),
        allowed
      );
    } else {
      FilterPreservesDecreasingLeastPathOrder(
        Directed(ascendingCover, direction),
        allowed
      );
    }
    FilterPreservesExactUniqueness(
      Directed(ascendingCover, direction),
      allowed
    );
    forall item: CoverItem
      ensures item in Reverse(ascendingCover) <==> item in ascendingCover
    {
      ReverseMembership(ascendingCover, item);
    }
    assert ItemEntities(Directed(ascendingCover, direction)) == cover;
    FilteredEntitySetIsExactIntersection(
      Directed(ascendingCover, direction),
      allowed
    );
  }

  function Directed(
    ascendingCover: seq<CoverItem>,
    direction: Direction
  ): seq<CoverItem> {
    if direction.Ascending? then ascendingCover else Reverse(ascendingCover)
  }

  function Page(
    items: seq<CoverItem>,
    start: nat,
    width: nat
  ): seq<CoverItem> {
    if start >= |items| then
      []
    else
      items[start..AdaptiveBatching.Min(|items|, start + width)]
  }

  lemma AdjacentPagesCompose(
    items: seq<CoverItem>,
    start: nat,
    firstWidth: nat,
    secondWidth: nat
  )
    requires start + firstWidth + secondWidth <= |items|
    ensures Page(items, start, firstWidth) +
            Page(items, start + firstWidth, secondWidth) ==
            Page(items, start, firstWidth + secondWidth)
  {
  }

  lemma AscendingAndDescendingPagesCompose(
    ascendingCover: seq<CoverItem>,
    direction: Direction,
    start: nat,
    firstWidth: nat,
    secondWidth: nat
  )
    requires start + firstWidth + secondWidth <=
             |Directed(ascendingCover, direction)|
    ensures Page(
              Directed(ascendingCover, direction),
              start,
              firstWidth
            ) +
            Page(
              Directed(ascendingCover, direction),
              start + firstWidth,
              secondWidth
            ) ==
            Page(
              Directed(ascendingCover, direction),
              start,
              firstWidth + secondWidth
            )
  {
    AdjacentPagesCompose(
      Directed(ascendingCover, direction),
      start,
      firstWidth,
      secondWidth
    );
  }

  function DecisionVector(
    items: seq<CoverItem>,
    allowed: set<Entity>
  ): seq<bool>
    ensures |DecisionVector(items, allowed)| == |items|
    decreases |items|
  {
    if |items| == 0 then
      []
    else
      [items[0].entity in allowed] +
      DecisionVector(items[1..], allowed)
  }

  lemma DecisionVectorWidth(
    items: seq<CoverItem>,
    allowed: set<Entity>
  )
    ensures |DecisionVector(items, allowed)| == |items|
    decreases |items|
  {
    if |items| != 0 {
      DecisionVectorWidth(items[1..], allowed);
    }
  }

  lemma FilteredWidthEqualsAcceptedDecisionCount(
    items: seq<CoverItem>,
    allowed: set<Entity>
  )
    ensures |FilterAllowed(items, allowed)| ==
            AdaptiveBatching.CountTrue(DecisionVector(items, allowed))
    decreases |items|
  {
    if |items| != 0 {
      FilteredWidthEqualsAcceptedDecisionCount(items[1..], allowed);
    }
  }

  lemma DecisionVectorOfPrefixIsPrefixOfDecisionVector(
    items: seq<CoverItem>,
    allowed: set<Entity>,
    consumed: nat
  )
    requires consumed <= |items|
    ensures DecisionVector(items[..consumed], allowed) ==
            DecisionVector(items, allowed)[..consumed]
    decreases consumed
  {
    if consumed != 0 {
      DecisionVectorOfPrefixIsPrefixOfDecisionVector(
        items[1..],
        allowed,
        consumed - 1
      );
      assert items[..consumed] ==
             [items[0]] + items[1..][..consumed - 1];
    }
  }

  function LogicalConsumed(
    directedCover: seq<CoverItem>,
    allowed: set<Entity>,
    start: nat,
    demand: nat,
    candidateWindow: nat
  ): nat {
    if start >= |directedCover| then
      0
    else
      var physicalWindow := Page(
                              directedCover,
                              start,
                              candidateWindow
                            );
      AdaptiveBatching.PrefixForDemand(
        DecisionVector(physicalWindow, allowed),
        demand
      )
  }

  function LogicalBoundary(
    directedCover: seq<CoverItem>,
    allowed: set<Entity>,
    start: nat,
    demand: nat,
    candidateWindow: nat
  ): nat
    requires start <= |directedCover|
    ensures start <= LogicalBoundary(
              directedCover,
              allowed,
              start,
              demand,
              candidateWindow
            ) <= |directedCover|
  {
    AdaptiveBatching.Min(
      |directedCover|,
      start + LogicalConsumed(
        directedCover,
        allowed,
        start,
        demand,
        candidateWindow
      )
    )
  }

  lemma FilterConcatenates(
    left: seq<CoverItem>,
    right: seq<CoverItem>,
    allowed: set<Entity>
  )
    ensures FilterAllowed(left + right, allowed) ==
            FilterAllowed(left, allowed) + FilterAllowed(right, allowed)
    decreases |left|
  {
    if |left| != 0 {
      FilterConcatenates(left[1..], right, allowed);
      assert (left + right)[0] == left[0];
      assert (left + right)[1..] == left[1..] + right;
      if left[0].entity in allowed {
        calc {
           FilterAllowed(left + right, allowed);
        == [left[0]] + FilterAllowed(left[1..] + right, allowed);
        == [left[0]] +
           (FilterAllowed(left[1..], allowed) +
            FilterAllowed(right, allowed));
        == ([left[0]] + FilterAllowed(left[1..], allowed)) +
           FilterAllowed(right, allowed);
        == FilterAllowed(left, allowed) +
           FilterAllowed(right, allowed);
        }
      } else {
        calc {
           FilterAllowed(left + right, allowed);
        == FilterAllowed(left[1..] + right, allowed);
        == FilterAllowed(left[1..], allowed) +
           FilterAllowed(right, allowed);
        == FilterAllowed(left, allowed) +
           FilterAllowed(right, allowed);
        }
      }
    } else {
      assert left == [];
      assert left + right == right;
    }
  }

  lemma LogicalBoundaryIsWithinCover(
    directedCover: seq<CoverItem>,
    allowed: set<Entity>,
    start: nat,
    demand: nat,
    candidateWindow: nat
  )
    requires start <= |directedCover|
    ensures start <= LogicalBoundary(
              directedCover,
              allowed,
              start,
              demand,
              candidateWindow
            ) <= |directedCover|
  {
  }

  lemma ResumeAtLogicalBoundaryEqualsUninterruptedSuffix(
    directedCover: seq<CoverItem>,
    allowed: set<Entity>,
    start: nat,
    demand: nat,
    candidateWindow: nat
  )
    requires start <= |directedCover|
    ensures var boundary := LogicalBoundary(
                              directedCover,
                              allowed,
                              start,
                              demand,
                              candidateWindow
                            );
            FilterAllowed(directedCover[start..], allowed) ==
            FilterAllowed(directedCover[start..boundary], allowed) +
            FilterAllowed(directedCover[boundary..], allowed)
  {
    LogicalBoundaryIsWithinCover(
      directedCover,
      allowed,
      start,
      demand,
      candidateWindow
    );
    var boundary := LogicalBoundary(
      directedCover,
      allowed,
      start,
      demand,
      candidateWindow
    );
    assert directedCover[start..] ==
           directedCover[start..boundary] + directedCover[boundary..];
    FilterConcatenates(
      directedCover[start..boundary],
      directedCover[boundary..],
      allowed
    );
  }

  lemma FilteredAdjacentPagesComposeWithoutReordering(
    directedCover: seq<CoverItem>,
    allowed: set<Entity>,
    start: nat,
    firstWidth: nat,
    secondWidth: nat
  )
    requires start + firstWidth + secondWidth <= |directedCover|
    ensures FilterAllowed(
              Page(directedCover, start, firstWidth),
              allowed
            ) +
            FilterAllowed(
              Page(
                directedCover,
                start + firstWidth,
                secondWidth
              ),
              allowed
            ) ==
            FilterAllowed(
              Page(
                directedCover,
                start,
                firstWidth + secondWidth
              ),
              allowed
            )
  {
    AdjacentPagesCompose(
      directedCover,
      start,
      firstWidth,
      secondWidth
    );
    FilterConcatenates(
      Page(directedCover, start, firstWidth),
      Page(
        directedCover,
        start + firstWidth,
        secondWidth
      ),
      allowed
    );
  }

  lemma SatisfiedDemandConsumesExactlyTheAcceptedSentinel(
    directedCover: seq<CoverItem>,
    allowed: set<Entity>,
    start: nat,
    demand: nat,
    candidateWindow: nat
  )
    requires start <= |directedCover|
    requires var window := Page(
                             directedCover,
                             start,
                             candidateWindow
                           );
             demand <= AdaptiveBatching.CountTrue(
               DecisionVector(window, allowed)
             )
    ensures var boundary := LogicalBoundary(
                              directedCover,
                              allowed,
                              start,
                              demand,
                              candidateWindow
                            );
            |FilterAllowed(
              directedCover[start..boundary],
              allowed
            )| == demand
  {
    var window := Page(
      directedCover,
      start,
      candidateWindow
    );
    var decisions := DecisionVector(window, allowed);
    var consumed := AdaptiveBatching.PrefixForDemand(decisions, demand);
    DecisionVectorWidth(window, allowed);
    AdaptiveBatching.PrefixReachesExactSentinel(decisions, demand);
    assert consumed <= |window|;
    assert start + consumed <= |directedCover|;
    assert LogicalBoundary(
        directedCover,
        allowed,
        start,
        demand,
        candidateWindow
      ) == start + consumed;
    assert directedCover[start..start + consumed] == window[..consumed];
    DecisionVectorOfPrefixIsPrefixOfDecisionVector(
      window,
      allowed,
      consumed
    );
    FilteredWidthEqualsAcceptedDecisionCount(
      window[..consumed],
      allowed
    );
  }

  lemma InsufficientCandidateWindowContinuesAtWindowBoundary(
    directedCover: seq<CoverItem>,
    allowed: set<Entity>,
    start: nat,
    demand: nat,
    candidateWindow: nat
  )
    requires start < |directedCover|
    requires var window := Page(
                             directedCover,
                             start,
                             candidateWindow
                           );
             AdaptiveBatching.CountTrue(
               DecisionVector(window, allowed)
             ) < demand
    ensures LogicalBoundary(
              directedCover,
              allowed,
              start,
              demand,
              candidateWindow
            ) ==
            AdaptiveBatching.Min(
              |directedCover|,
              start + candidateWindow
            )
  {
    var window := Page(
      directedCover,
      start,
      candidateWindow
    );
    DecisionVectorWidth(window, allowed);
    AdaptiveBatching.InsufficientBatchConsumesWholeWidth(
      DecisionVector(window, allowed),
      demand
    );
  }
}

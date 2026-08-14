// Request-side physical accelerators use a fixed newest-first capacity.  Their
// keys and eviction order are nonsemantic; logical frames retain exact resume
// bounds independently.
// Exploratory proof model; intentionally excluded from release artifacts.
include "StableReducer.dfy"

module BoundedSidecar {
  import R = StableReducer

  function KeepNewest<T>(values: seq<T>, capacity: nat): seq<T> {
    if |values| <= capacity then
      values
    else
      values[|values| - capacity..]
  }

  lemma SeqSetSuffixSubset<T>(values: seq<T>, offset: nat)
    requires offset <= |values|
    ensures R.SeqSet(values[offset..]) <= R.SeqSet(values)
    decreases offset
  {
    if offset > 0 {
      SeqSetSuffixSubset(values[1..], offset - 1);
      R.SeqSetHeadTail(values);
      assert values[offset..] == values[1..][offset - 1..];
    }
  }

  lemma KeepNewestIsBounded<T>(values: seq<T>, capacity: nat)
    ensures |KeepNewest(values, capacity)| <= capacity
    ensures R.SeqSet(KeepNewest(values, capacity)) <= R.SeqSet(values)
  {
    if |values| > capacity {
      SeqSetSuffixSubset(values, |values| - capacity);
    }
  }

  lemma SuffixOfUniqueIsUnique<T>(values: seq<T>, offset: nat)
    requires R.Unique(values)
    requires offset <= |values|
    ensures R.Unique(values[offset..])
    decreases offset
  {
    if offset > 0 {
      SuffixOfUniqueIsUnique(values[1..], offset - 1);
      assert values[offset..] == values[1..][offset - 1..];
    }
  }

  lemma KeepNewestPreservesUnique<T>(values: seq<T>, capacity: nat)
    requires R.Unique(values)
    ensures R.Unique(KeepNewest(values, capacity))
  {
    if |values| > capacity {
      SuffixOfUniqueIsUnique(values, |values| - capacity);
    }
  }

  function Retain<T(==)>(
    existingOldestFirst: seq<T>,
    current: T,
    capacity: nat
  ): seq<T>
    requires current !in R.SeqSet(existingOldestFirst)
  {
    if capacity == 0 then
      []
    else
      KeepNewest(existingOldestFirst, capacity - 1) + [current]
  }

  lemma RetainIsBoundedAndKeepsCurrentNewest<T>(
    existingOldestFirst: seq<T>,
    current: T,
    capacity: nat
  )
    requires R.Unique(existingOldestFirst)
    requires current !in R.SeqSet(existingOldestFirst)
    ensures R.Unique(Retain(existingOldestFirst, current, capacity))
    ensures |Retain(existingOldestFirst, current, capacity)| <= capacity
    ensures capacity == 0 ==>
            |Retain(existingOldestFirst, current, capacity)| == 0
    ensures capacity > 0 ==>
            Retain(existingOldestFirst, current, capacity)[
              |Retain(existingOldestFirst, current, capacity)| - 1
            ] == current
    ensures R.SeqSet(Retain(
              existingOldestFirst, current, capacity
            )) <= R.SeqSet(existingOldestFirst) + {current}
  {
    if capacity > 0 {
      var kept := KeepNewest(existingOldestFirst, capacity - 1);
      KeepNewestIsBounded(existingOldestFirst, capacity - 1);
      KeepNewestPreservesUnique(existingOldestFirst, capacity - 1);
      assert current !in R.SeqSet(kept);
      R.AppendFreshIsUnique(kept, current);
      R.SeqSetConcat(kept, [current]);
    }
  }
}

// One exact checkpoint key owns at most one latest immutable state. Other
// cache keys are summarized by their already-charged aggregate weight; an
// eviction policy may reduce that aggregate but cannot bypass the capacity
// check. Exploratory proof model; excluded from release artifacts.
module WeightedCheckpointSlot {
  datatype Slot = Slot(
    present: bool,
    ordinal: nat,
    weight: nat
  )

  datatype Cache = Cache(
    capacity: nat,
    otherWeight: nat,
    slot: Slot
  )

  function SlotWeight(slot: Slot): nat {
    if slot.present then slot.weight else 0
  }

  function Used(cache: Cache): nat {
    cache.otherWeight + SlotWeight(cache.slot)
  }

  predicate Valid(cache: Cache) {
    (!cache.slot.present || cache.slot.weight > 0) &&
    Used(cache) <= cache.capacity
  }

  function Publish(
    cache: Cache,
    candidateOrdinal: nat,
    candidateWeight: nat
  ): Cache
    requires candidateWeight > 0
  {
    if cache.slot.present &&
       candidateOrdinal <= cache.slot.ordinal then
      cache
    else if cache.otherWeight + candidateWeight <= cache.capacity then
      Cache(
        cache.capacity,
        cache.otherWeight,
        Slot(true, candidateOrdinal, candidateWeight)
      )
    else
      cache
  }

  lemma PublicationPreservesCapacity(
    cache: Cache,
    candidateOrdinal: nat,
    candidateWeight: nat
  )
    requires Valid(cache)
    requires candidateWeight > 0
    ensures Valid(Publish(cache, candidateOrdinal, candidateWeight))
    ensures Used(Publish(cache, candidateOrdinal, candidateWeight)) <=
            cache.capacity
  {
  }

  lemma PublicationNeverRegressesOrdinal(
    cache: Cache,
    candidateOrdinal: nat,
    candidateWeight: nat
  )
    requires Valid(cache)
    requires cache.slot.present
    requires candidateWeight > 0
    ensures Publish(cache, candidateOrdinal, candidateWeight).slot.present
    ensures Publish(cache, candidateOrdinal, candidateWeight).slot.ordinal >=
            cache.slot.ordinal
  {
  }

  lemma OversizedCandidateIsRejectedWithoutMutation(
    cache: Cache,
    candidateOrdinal: nat,
    candidateWeight: nat
  )
    requires Valid(cache)
    requires candidateWeight > 0
    requires cache.otherWeight + candidateWeight > cache.capacity
    requires !cache.slot.present ||
             candidateOrdinal > cache.slot.ordinal
    ensures Publish(cache, candidateOrdinal, candidateWeight) == cache
  {
  }

  function ReleaseOtherWeight(
    cache: Cache,
    released: nat
  ): Cache
    requires released <= cache.otherWeight
  {
    Cache(
      cache.capacity,
      cache.otherWeight - released,
      cache.slot
    )
  }

  lemma EvictingOtherKeysOnlyReleasesWeight(
    cache: Cache,
    released: nat
  )
    requires Valid(cache)
    requires released <= cache.otherWeight
    ensures Valid(ReleaseOtherWeight(cache, released))
    ensures Used(ReleaseOtherWeight(cache, released)) <= Used(cache)
  {
  }
}

// Exploratory weighted refinement of the count-based response lifecycle.
module WeightedResponseLease {
  datatype Lease = Lease(
    inFlight: bool,
    pins: nat,
    cached: bool,
    weight: nat,
    reserved: nat
  )

  predicate Held(lease: Lease) {
    lease.inFlight || lease.pins > 0 || lease.cached
  }

  predicate Exact(lease: Lease) {
    lease.weight > 0 &&
    lease.reserved == (if Held(lease) then lease.weight else 0)
  }

  function Start(weight: nat): Lease {
    Lease(true, 0, false, weight, weight)
  }

  lemma StartReservesBeforeIo(weight: nat)
    requires weight > 0
    ensures Exact(Start(weight))
    ensures Start(weight).reserved == weight
  {
  }

  function Complete(
    lease: Lease,
    livePins: nat,
    publish: bool
  ): Lease
    requires lease.inFlight
  {
    var pins := lease.pins + livePins;
    var cached := lease.cached || publish;
    Lease(
      false,
      pins,
      cached,
      lease.weight,
      if pins > 0 || cached then lease.weight else 0
    )
  }

  lemma CompletionPreservesExactOwnership(
    lease: Lease,
    livePins: nat,
    publish: bool
  )
    requires Exact(lease)
    requires lease.inFlight
    ensures Exact(Complete(lease, livePins, publish))
    ensures Complete(lease, livePins, publish).reserved <=
            lease.reserved
  {
  }

  function Pin(lease: Lease): Lease
    requires lease.cached
  {
    Lease(
      lease.inFlight,
      lease.pins + 1,
      lease.cached,
      lease.weight,
      lease.reserved
    )
  }

  lemma CacheHitPinDoesNotReserveTwice(lease: Lease)
    requires Exact(lease)
    requires lease.cached
    ensures Exact(Pin(lease))
    ensures Pin(lease).reserved == lease.reserved
  {
  }

  function Unpin(lease: Lease): Lease
    requires lease.pins > 0
  {
    var pins := lease.pins - 1;
    Lease(
      lease.inFlight,
      pins,
      lease.cached,
      lease.weight,
      if lease.inFlight || pins > 0 || lease.cached
      then lease.weight else 0
    )
  }

  lemma UnpinReleasesOnlyTheLastOwner(lease: Lease)
    requires Exact(lease)
    requires lease.pins > 0
    ensures Exact(Unpin(lease))
    ensures Unpin(lease).reserved <= lease.reserved
    ensures Unpin(lease).reserved == 0 <==>
            !Unpin(lease).inFlight &&
            Unpin(lease).pins == 0 &&
            !Unpin(lease).cached
  {
  }

  function Evict(lease: Lease): Lease
    requires lease.cached
  {
    Lease(
      lease.inFlight,
      lease.pins,
      false,
      lease.weight,
      if lease.inFlight || lease.pins > 0 then lease.weight else 0
    )
  }

  lemma EvictionCannotFreePinnedWeight(lease: Lease)
    requires Exact(lease)
    requires lease.cached
    ensures Exact(Evict(lease))
    ensures Evict(lease).reserved <= lease.reserved
    ensures lease.pins > 0 ==> Evict(lease).reserved == lease.weight
  {
  }

  datatype Governor = Governor(capacity: nat, used: nat)

  predicate ValidGovernor(governor: Governor) {
    governor.used <= governor.capacity
  }

  function Reserve(
    governor: Governor,
    weight: nat
  ): Governor
    requires ValidGovernor(governor)
    requires governor.used + weight <= governor.capacity
  {
    Governor(governor.capacity, governor.used + weight)
  }

  lemma ReservationPreservesCapacity(
    governor: Governor,
    weight: nat
  )
    requires ValidGovernor(governor)
    requires governor.used + weight <= governor.capacity
    ensures ValidGovernor(Reserve(governor, weight))
  {
  }

  function Adjust(
    governor: Governor,
    before: Lease,
    after: Lease
  ): Governor
    requires ValidGovernor(governor)
    requires Exact(before)
    requires Exact(after)
    requires after.reserved <= before.reserved <= governor.used
  {
    Governor(
      governor.capacity,
      governor.used - before.reserved + after.reserved
    )
  }

  lemma OwnerReleasePreservesAggregateCapacity(
    governor: Governor,
    before: Lease,
    after: Lease
  )
    requires ValidGovernor(governor)
    requires Exact(before)
    requires Exact(after)
    requires after.reserved <= before.reserved <= governor.used
    ensures ValidGovernor(Adjust(governor, before, after))
  {
  }
}

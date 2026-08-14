--------------------------- MODULE ServiceLifecycle ---------------------------
EXTENDS Naturals, FiniteSets, TLC

CONSTANTS
  Attempts,
  Capacity,
  ResponseCapacity,
  FreeChargesAtRotation,
  FreePinnedAtRotation,
  PublishLateResponse,
  IgnoreRetainedCapacity

ASSUME
  /\ Capacity > 0
  /\ ResponseCapacity > 0
  /\ Cardinality(Attempts) > Capacity
  /\ FreeChargesAtRotation \in BOOLEAN
  /\ FreePinnedAtRotation \in BOOLEAN
  /\ PublishLateResponse \in BOOLEAN
  /\ IgnoreRetainedCapacity \in BOOLEAN

VARIABLES
  currentEpoch,
  used,
  physical,
  charged,
  retained,
  pinned,
  ownerEpoch,
  published

vars ==
  <<currentEpoch, used, physical, charged, retained, pinned, ownerEpoch,
    published>>

TypeOK ==
  /\ currentEpoch \in {0, 1}
  /\ used \in SUBSET Attempts
  /\ physical \in SUBSET Attempts
  /\ charged \in SUBSET Attempts
  /\ retained \in SUBSET Attempts
  /\ pinned \in SUBSET Attempts
  /\ ownerEpoch \in [Attempts -> {0, 1}]
  /\ published \in SUBSET Attempts

CapacitySafety ==
  /\ charged = physical
  /\ Cardinality(charged) <= Capacity
  /\ retained = physical \union pinned \union published
  /\ Cardinality(retained) <= ResponseCapacity

LifecycleSafety ==
  /\ physical \subseteq used
  /\ retained \subseteq used
  /\ pinned \subseteq used
  /\ published \subseteq used
  /\ \A attempt \in published:
       ownerEpoch[attempt] = currentEpoch

Safety ==
  /\ TypeOK
  /\ CapacitySafety
  /\ LifecycleSafety

Init ==
  /\ currentEpoch = 0
  /\ used = {}
  /\ physical = {}
  /\ charged = {}
  /\ retained = {}
  /\ pinned = {}
  /\ ownerEpoch = [attempt \in Attempts |-> 0]
  /\ published = {}

Start ==
  \E attempt \in Attempts \ used:
    /\ Cardinality(charged) < Capacity
    /\ \/ IgnoreRetainedCapacity
       \/ Cardinality(retained) < ResponseCapacity
    /\ used' = used \union {attempt}
    /\ physical' = physical \union {attempt}
    /\ charged' = charged \union {attempt}
    /\ retained' = retained \union {attempt}
    /\ ownerEpoch' = [ownerEpoch EXCEPT ![attempt] = currentEpoch]
    /\ UNCHANGED <<currentEpoch, pinned, published>>

CompleteSuccess ==
  \E attempt \in physical:
    /\ physical' = physical \ {attempt}
    /\ charged' = charged \ {attempt}
    /\ pinned' = pinned \union {attempt}
    /\ IF ownerEpoch[attempt] = currentEpoch \/ PublishLateResponse
       THEN /\ published' = published \union {attempt}
            /\ UNCHANGED retained
       ELSE /\ UNCHANGED published
            /\ UNCHANGED retained
    /\ UNCHANGED <<currentEpoch, used, ownerEpoch>>

CompleteFailure ==
  \E attempt \in physical:
    /\ physical' = physical \ {attempt}
    /\ charged' = charged \ {attempt}
    /\ retained' = retained \ {attempt}
    /\ UNCHANGED <<currentEpoch, used, pinned, ownerEpoch, published>>

Consume ==
  \E attempt \in pinned:
    /\ pinned' = pinned \ {attempt}
    /\ IF attempt \in published
       THEN UNCHANGED retained
       ELSE retained' = retained \ {attempt}
    /\ UNCHANGED
         <<currentEpoch, used, physical, charged, ownerEpoch, published>>

Evict ==
  \E attempt \in published:
    /\ published' = published \ {attempt}
    /\ IF attempt \in pinned
       THEN UNCHANGED retained
       ELSE retained' = retained \ {attempt}
    /\ UNCHANGED
         <<currentEpoch, used, physical, charged, pinned, ownerEpoch>>

Rotate ==
  /\ currentEpoch = 0
  /\ currentEpoch' = 1
  /\ published' = {}
  /\ IF FreePinnedAtRotation
     THEN retained' = physical
     ELSE retained' = physical \union pinned
  /\ IF FreeChargesAtRotation
     THEN charged' = {}
     ELSE UNCHANGED charged
  /\ UNCHANGED <<used, physical, pinned, ownerEpoch>>

Next ==
  \/ Start
  \/ CompleteSuccess
  \/ CompleteFailure
  \/ Consume
  \/ Evict
  \/ Rotate

=============================================================================

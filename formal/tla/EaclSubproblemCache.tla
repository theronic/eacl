--------------------- MODULE EaclSubproblemCache ---------------------
EXTENDS Integers, FiniteSets

CONSTANTS
  \* @type: Set(Int);
  Generations,
  \* @type: Set(Int);
  Keys,
  \* @type: Int;
  Budget,
  \* @type: Int;
  InflightBound

ASSUME
  /\ Generations # {}
  /\ Keys # {}
  /\ 0 \in Generations
  /\ 0 \in Keys
  /\ 0 < Budget
  /\ 0 < InflightBound

Absent == 0
Computing == 1
Partial == 2
Complete == 3
Failed == 4
EntryStates == {Absent, Computing, Partial, Complete, Failed}

NoOutcome == 0
CacheHit == 1
CacheMiss == 2
SingleFlightJoin == 3
Outcomes == {NoOutcome, CacheHit, CacheMiss, SingleFlightJoin}

VARIABLES
  \* @type: Int;
  activeGeneration,
  \* @type: Int;
  lifecycle,
  \* @type: Int -> Int;
  entryState,
  \* @type: Int -> Int;
  capturedGeneration,
  \* @type: Int -> Int;
  capturedLifecycle,
  \* @type: Int -> Int;
  entryWeight,
  \* @type: Int;
  admittedWeight,
  \* @type: Int;
  orphanPublications,
  \* @type: Int;
  chosenKey,
  \* @type: Int;
  outcome

vars ==
  <<activeGeneration, lifecycle, entryState, capturedGeneration,
    capturedLifecycle, entryWeight, admittedWeight, orphanPublications,
    chosenKey, outcome>>

TypeOK ==
  /\ activeGeneration \in Generations
  /\ lifecycle \in Nat
  /\ entryState \in [Keys -> EntryStates]
  /\ capturedGeneration \in [Keys -> Generations]
  /\ capturedLifecycle \in [Keys -> Nat]
  /\ entryWeight \in [Keys -> Nat]
  /\ admittedWeight \in Nat
  /\ orphanPublications \in Nat
  /\ chosenKey \in Keys
  /\ outcome \in Outcomes

WeightBound ==
  admittedWeight <= Budget

CompleteKeys ==
  {key \in Keys: entryState[key] = Complete}

InflightKeys ==
  {key \in Keys:
    entryState[key] \in {Computing, Partial}}

ReservedKeys ==
  CompleteKeys \union InflightKeys

EntryWeightsAreAccounted ==
  /\ admittedWeight = Cardinality(ReservedKeys)
  /\ \A key \in Keys:
    entryWeight[key] =
      IF key \in ReservedKeys THEN 1 ELSE 0

HitIsCompleteAndCurrent ==
  outcome # CacheHit \/
    /\ entryState[chosenKey] = Complete
    /\ capturedGeneration[chosenKey] = activeGeneration
    /\ capturedLifecycle[chosenKey] = lifecycle

SingleFlightJoinIsCurrentAndComputing ==
  outcome # SingleFlightJoin \/
    /\ entryState[chosenKey] = Computing
    /\ capturedGeneration[chosenKey] = activeGeneration
    /\ capturedLifecycle[chosenKey] = lifecycle

PartialNeverHits ==
  outcome = CacheHit => entryState[chosenKey] # Partial

OneFlightPerKey ==
  \A key \in Keys:
    entryState[key] \in EntryStates

InflightIsBounded ==
  Cardinality(InflightKeys) <= InflightBound

Safety ==
  /\ WeightBound
  /\ EntryWeightsAreAccounted
  /\ HitIsCompleteAndCurrent
  /\ SingleFlightJoinIsCurrentAndComputing
  /\ PartialNeverHits
  /\ OneFlightPerKey
  /\ InflightIsBounded

InductiveInvariant ==
  /\ TypeOK
  /\ Safety

Init ==
  /\ activeGeneration = 0
  /\ lifecycle = 0
  /\ entryState = [key \in Keys |-> Absent]
  /\ capturedGeneration = [key \in Keys |-> 0]
  /\ capturedLifecycle = [key \in Keys |-> 0]
  /\ entryWeight = [key \in Keys |-> 0]
  /\ admittedWeight = 0
  /\ orphanPublications = 0
  /\ chosenKey = 0
  /\ outcome = NoOutcome

BeginMiss ==
  \E key \in Keys:
    /\ entryState[key] = Absent
    /\ Cardinality(InflightKeys) < InflightBound
    /\ admittedWeight + 1 <= Budget
    /\ entryState' = [entryState EXCEPT ![key] = Computing]
    /\ capturedGeneration' =
      [capturedGeneration EXCEPT ![key] = activeGeneration]
    /\ capturedLifecycle' =
      [capturedLifecycle EXCEPT ![key] = lifecycle]
    /\ entryWeight' = [entryWeight EXCEPT ![key] = 1]
    /\ chosenKey' = key
    /\ outcome' = CacheMiss
    /\ admittedWeight' = admittedWeight + 1
    /\ UNCHANGED
      <<activeGeneration, lifecycle, orphanPublications>>

JoinOrRead ==
  \E key \in Keys:
    /\ chosenKey' = key
    /\ outcome' =
      IF /\ entryState[key] = Complete
         /\ capturedGeneration[key] = activeGeneration
         /\ capturedLifecycle[key] = lifecycle
      THEN CacheHit
      ELSE IF /\ entryState[key] = Computing
              /\ capturedGeneration[key] = activeGeneration
              /\ capturedLifecycle[key] = lifecycle
           THEN SingleFlightJoin
      ELSE CacheMiss
    /\ UNCHANGED
      <<activeGeneration, lifecycle, entryState, capturedGeneration,
        capturedLifecycle, entryWeight, admittedWeight,
        orphanPublications>>

PublishComplete ==
  \E key \in Keys:
    /\ entryState[key] = Computing
    /\ capturedGeneration[key] = activeGeneration
    /\ capturedLifecycle[key] = lifecycle
    /\ entryState' = [entryState EXCEPT ![key] = Complete]
    /\ entryWeight' = [entryWeight EXCEPT ![key] = 1]
    /\ chosenKey' = key
    /\ outcome' = NoOutcome
    /\ UNCHANGED
      <<activeGeneration, lifecycle, capturedGeneration,
        capturedLifecycle, admittedWeight, orphanPublications>>

PublishPartial ==
  \E key \in Keys:
    /\ entryState[key] = Computing
    /\ entryState' = [entryState EXCEPT ![key] = Partial]
    /\ chosenKey' = key
    /\ outcome' = NoOutcome
    /\ UNCHANGED
      <<activeGeneration, lifecycle, capturedGeneration,
        capturedLifecycle, entryWeight, admittedWeight,
        orphanPublications>>

FailOrReject ==
  \E key \in Keys:
    /\ entryState[key] \in {Computing, Partial}
    /\ entryState' = [entryState EXCEPT ![key] = Absent]
    /\ entryWeight' = [entryWeight EXCEPT ![key] = 0]
    /\ admittedWeight' = admittedWeight - 1
    /\ chosenKey' = key
    /\ outcome' = NoOutcome
    /\ UNCHANGED
      <<activeGeneration, lifecycle, capturedGeneration,
        capturedLifecycle, orphanPublications>>

EvictReserved ==
  \E key \in Keys:
    /\ key \in ReservedKeys
    /\ entryState' = [entryState EXCEPT ![key] = Absent]
    /\ admittedWeight' = admittedWeight - 1
    /\ entryWeight' = [entryWeight EXCEPT ![key] = 0]
    /\ chosenKey' = key
    /\ outcome' = NoOutcome
    /\ UNCHANGED
      <<activeGeneration, lifecycle, capturedGeneration,
        capturedLifecycle, orphanPublications>>

ExpireGeneration ==
  \E generation \in Generations:
    /\ activeGeneration' = generation
    /\ lifecycle' = lifecycle + 1
    /\ entryState' = [key \in Keys |-> Absent]
    /\ capturedGeneration' =
      [key \in Keys |-> activeGeneration']
    /\ capturedLifecycle' = [key \in Keys |-> lifecycle']
    /\ entryWeight' = [key \in Keys |-> 0]
    /\ admittedWeight' = 0
    /\ outcome' = NoOutcome
    /\ UNCHANGED <<orphanPublications, chosenKey>>

LateOrphanCompletion ==
  /\ orphanPublications' = orphanPublications + 1
  /\ outcome' = NoOutcome
  /\ UNCHANGED
    <<activeGeneration, lifecycle, entryState, capturedGeneration,
      capturedLifecycle, entryWeight, admittedWeight, chosenKey>>

Next ==
  \/ BeginMiss
  \/ JoinOrRead
  \/ PublishComplete
  \/ PublishPartial
  \/ FailOrReject
  \/ EvictReserved
  \/ ExpireGeneration
  \/ LateOrphanCompletion

Spec ==
  Init /\ [][Next]_vars

=====================================================================

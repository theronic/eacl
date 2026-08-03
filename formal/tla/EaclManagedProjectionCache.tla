---------------- MODULE EaclManagedProjectionCache ----------------
EXTENDS Integers, FiniteSets

CONSTANTS
  \* @type: Set(Int);
  Generations,
  \* @type: Set(Int);
  SchemaStamps,
  \* @type: Set(Int);
  RelationStamps,
  \* @type: Set(Int);
  Sources,
  \* @type: Set(Int);
  Keys,
  \* @type: Int;
  InflightBound

ASSUME
  /\ Generations # {}
  /\ SchemaStamps # {}
  /\ RelationStamps # {}
  /\ Sources # {}
  /\ Keys # {}
  /\ 0 \in Generations
  /\ 0 \in SchemaStamps
  /\ 0 \in RelationStamps
  /\ 0 \in Sources
  /\ 0 \in Keys
  /\ 0 < InflightBound

Absent == 0
Computing == 1
Complete == 2
Failed == 3
EntryStates == {Absent, Computing, Complete, Failed}

NoOutcome == 0
CacheHit == 1
CacheMiss == 2
SingleFlightJoin == 3
Outcomes == {NoOutcome, CacheHit, CacheMiss, SingleFlightJoin}

VARIABLES
  \* @type: Int;
  activeGeneration,
  \* @type: Int;
  activeSchemaStamp,
  \* @type: Int;
  activeRelationStamp,
  \* @type: Int;
  activeSource,
  \* @type: Int -> Int;
  entryState,
  \* @type: Int -> Int;
  capturedGeneration,
  \* @type: Int -> Int;
  capturedSchemaStamp,
  \* @type: Int -> Int;
  capturedRelationStamp,
  \* @type: Int -> Int;
  capturedSource,
  \* @type: Int;
  chosenKey,
  \* @type: Int;
  outcome

vars ==
  <<activeGeneration, activeSchemaStamp, activeRelationStamp, activeSource,
    entryState, capturedGeneration, capturedSchemaStamp,
    capturedRelationStamp, capturedSource, chosenKey, outcome>>

TypeOK ==
  /\ activeGeneration \in Generations
  /\ activeSchemaStamp \in SchemaStamps
  /\ activeRelationStamp \in RelationStamps
  /\ activeSource \in Sources
  /\ entryState \in [Keys -> EntryStates]
  /\ capturedGeneration \in [Keys -> Generations]
  /\ capturedSchemaStamp \in [Keys -> SchemaStamps]
  /\ capturedRelationStamp \in [Keys -> RelationStamps]
  /\ capturedSource \in [Keys -> Sources]
  /\ chosenKey \in Keys
  /\ outcome \in Outcomes

InflightKeys ==
  {key \in Keys: entryState[key] = Computing}

InflightIsBounded ==
  Cardinality(InflightKeys) <= InflightBound

HitIsCompleteAtCurrentProof ==
  outcome # CacheHit \/
    /\ entryState[chosenKey] = Complete
    /\ capturedSchemaStamp[chosenKey] = activeSchemaStamp
    /\ capturedRelationStamp[chosenKey] = activeRelationStamp
    /\ capturedSource[chosenKey] = activeSource

SingleFlightJoinIsComputingAtCurrentProof ==
  outcome # SingleFlightJoin \/
    /\ entryState[chosenKey] = Computing
    /\ capturedSchemaStamp[chosenKey] = activeSchemaStamp
    /\ capturedRelationStamp[chosenKey] = activeRelationStamp
    /\ capturedSource[chosenKey] = activeSource

Safety ==
  /\ InflightIsBounded
  /\ HitIsCompleteAtCurrentProof
  /\ SingleFlightJoinIsComputingAtCurrentProof

InductiveInvariant ==
  /\ TypeOK
  /\ Safety

Init ==
  /\ activeGeneration = 0
  /\ activeSchemaStamp = 0
  /\ activeRelationStamp = 0
  /\ activeSource = 0
  /\ entryState = [key \in Keys |-> Absent]
  /\ capturedGeneration = [key \in Keys |-> 0]
  /\ capturedSchemaStamp = [key \in Keys |-> 0]
  /\ capturedRelationStamp = [key \in Keys |-> 0]
  /\ capturedSource = [key \in Keys |-> 0]
  /\ chosenKey = 0
  /\ outcome = NoOutcome

BeginMiss ==
  \E key \in Keys:
    /\ entryState[key] \in {Absent, Failed}
    /\ Cardinality(InflightKeys) < InflightBound
    /\ entryState' = [entryState EXCEPT ![key] = Computing]
    /\ capturedGeneration' =
      [capturedGeneration EXCEPT ![key] = activeGeneration]
    /\ capturedSchemaStamp' =
      [capturedSchemaStamp EXCEPT ![key] = activeSchemaStamp]
    /\ capturedRelationStamp' =
      [capturedRelationStamp EXCEPT ![key] = activeRelationStamp]
    /\ capturedSource' =
      [capturedSource EXCEPT ![key] = activeSource]
    /\ chosenKey' = key
    /\ outcome' = CacheMiss
    /\ UNCHANGED
      <<activeGeneration, activeSchemaStamp, activeRelationStamp,
        activeSource>>

JoinOrRead ==
  \E key \in Keys:
    /\ chosenKey' = key
    /\ outcome' =
      IF /\ entryState[key] = Complete
         /\ capturedSchemaStamp[key] = activeSchemaStamp
         /\ capturedRelationStamp[key] = activeRelationStamp
         /\ capturedSource[key] = activeSource
      THEN CacheHit
      ELSE IF /\ entryState[key] = Computing
              /\ capturedSchemaStamp[key] = activeSchemaStamp
              /\ capturedRelationStamp[key] = activeRelationStamp
              /\ capturedSource[key] = activeSource
           THEN SingleFlightJoin
      ELSE CacheMiss
    /\ UNCHANGED
      <<activeGeneration, activeSchemaStamp, activeRelationStamp, activeSource,
        entryState, capturedGeneration, capturedSchemaStamp,
        capturedRelationStamp, capturedSource>>

PublishComplete ==
  \E key \in Keys:
    /\ entryState[key] = Computing
    /\ entryState' = [entryState EXCEPT ![key] = Complete]
    /\ chosenKey' = key
    /\ outcome' = NoOutcome
    /\ UNCHANGED
      <<activeGeneration, activeSchemaStamp, activeRelationStamp, activeSource,
        capturedGeneration, capturedSchemaStamp,
        capturedRelationStamp, capturedSource>>

Fail ==
  \E key \in Keys:
    /\ entryState[key] = Computing
    /\ entryState' = [entryState EXCEPT ![key] = Failed]
    /\ chosenKey' = key
    /\ outcome' = NoOutcome
    /\ UNCHANGED
      <<activeGeneration, activeSchemaStamp, activeRelationStamp, activeSource,
        capturedGeneration, capturedSchemaStamp,
        capturedRelationStamp, capturedSource>>

AdvanceUnrelatedGeneration ==
  \E generation \in Generations:
    /\ generation # activeGeneration
    /\ activeGeneration' = generation
    /\ outcome' = NoOutcome
    /\ UNCHANGED
      <<activeSchemaStamp, activeRelationStamp, activeSource, entryState,
        capturedGeneration, capturedSchemaStamp,
        capturedRelationStamp, capturedSource, chosenKey>>

MutateRelation ==
  \E generation \in Generations:
    \E stamp \in RelationStamps:
      /\ generation # activeGeneration
      /\ stamp # activeRelationStamp
      /\ activeGeneration' = generation
      /\ activeRelationStamp' = stamp
      /\ outcome' = NoOutcome
      /\ UNCHANGED
        <<activeSchemaStamp, activeSource, entryState, capturedGeneration,
          capturedSchemaStamp, capturedRelationStamp, capturedSource,
          chosenKey>>

SwitchSource ==
  \E generation \in Generations:
    \E source \in Sources:
      /\ generation # activeGeneration
      /\ source # activeSource
      /\ activeGeneration' = generation
      /\ activeSource' = source
      /\ outcome' = NoOutcome
      /\ UNCHANGED
        <<activeSchemaStamp, activeRelationStamp, entryState,
          capturedGeneration, capturedSchemaStamp,
          capturedRelationStamp, capturedSource, chosenKey>>

MutateSchema ==
  \E generation \in Generations:
    \E stamp \in SchemaStamps:
      /\ generation # activeGeneration
      /\ stamp # activeSchemaStamp
      /\ activeGeneration' = generation
      /\ activeSchemaStamp' = stamp
      /\ entryState' = [key \in Keys |-> Absent]
      /\ capturedGeneration' =
        [key \in Keys |-> activeGeneration']
      /\ capturedSchemaStamp' =
        [key \in Keys |-> activeSchemaStamp']
      /\ capturedRelationStamp' =
        [key \in Keys |-> activeRelationStamp]
      /\ capturedSource' =
        [key \in Keys |-> activeSource]
      /\ outcome' = NoOutcome
      /\ UNCHANGED <<activeRelationStamp, activeSource, chosenKey>>

Next ==
  \/ BeginMiss
  \/ JoinOrRead
  \/ PublishComplete
  \/ Fail
  \/ AdvanceUnrelatedGeneration
  \/ MutateRelation
  \/ SwitchSource
  \/ MutateSchema

Spec ==
  Init /\ [][Next]_vars

====================================================================

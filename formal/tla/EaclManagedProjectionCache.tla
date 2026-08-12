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
  \* Mutation control: ignoring relation proof must violate Safety.
  \* @type: Bool;
  IgnoreRelationProof

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

NoOutcome == 0
CacheHit == 1
CacheMiss == 2
PublicationRetained == 3
PublicationDropped == 4
Outcomes ==
  {NoOutcome, CacheHit, CacheMiss, PublicationRetained, PublicationDropped}

VARIABLES
  \* @type: Int;
  activeGeneration,
  \* @type: Int;
  activeSchemaStamp,
  \* @type: Int;
  activeRelationStamp,
  \* @type: Int;
  activeSource,
  \* Completed immutable values only; misses compute outside cache state.
  \* @type: Set(Int);
  entries,
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
    entries, capturedGeneration, capturedSchemaStamp,
    capturedRelationStamp, capturedSource, chosenKey, outcome>>

TypeOK ==
  /\ activeGeneration \in Generations
  /\ activeSchemaStamp \in SchemaStamps
  /\ activeRelationStamp \in RelationStamps
  /\ activeSource \in Sources
  /\ entries \in SUBSET Keys
  /\ capturedGeneration \in [Keys -> Generations]
  /\ capturedSchemaStamp \in [Keys -> SchemaStamps]
  /\ capturedRelationStamp \in [Keys -> RelationStamps]
  /\ capturedSource \in [Keys -> Sources]
  /\ chosenKey \in Keys
  /\ outcome \in Outcomes

HitIsCompleteAtCurrentProof ==
  outcome # CacheHit \/
    /\ chosenKey \in entries
    /\ capturedSchemaStamp[chosenKey] = activeSchemaStamp
    /\ capturedRelationStamp[chosenKey] = activeRelationStamp
    /\ capturedSource[chosenKey] = activeSource

OnlyCompletedArtifactsAreStored == entries \in SUBSET Keys

Safety ==
  /\ HitIsCompleteAtCurrentProof
  /\ OnlyCompletedArtifactsAreStored

InductiveInvariant == /\ TypeOK /\ Safety

Init ==
  /\ activeGeneration = 0
  /\ activeSchemaStamp = 0
  /\ activeRelationStamp = 0
  /\ activeSource = 0
  /\ entries = {}
  /\ capturedGeneration = [key \in Keys |-> 0]
  /\ capturedSchemaStamp = [key \in Keys |-> 0]
  /\ capturedRelationStamp = [key \in Keys |-> 0]
  /\ capturedSource = [key \in Keys |-> 0]
  /\ chosenKey = 0
  /\ outcome = NoOutcome

Lookup ==
  \E key \in Keys:
    /\ chosenKey' = key
    /\ outcome' =
         IF /\ key \in entries
            /\ capturedSchemaStamp[key] = activeSchemaStamp
            /\ \/ IgnoreRelationProof
               \/ capturedRelationStamp[key] = activeRelationStamp
            /\ capturedSource[key] = activeSource
         THEN CacheHit
         ELSE CacheMiss
    /\ UNCHANGED
         <<activeGeneration, activeSchemaStamp, activeRelationStamp,
           activeSource, entries, capturedGeneration, capturedSchemaStamp,
           capturedRelationStamp, capturedSource>>

PublishCompleted ==
  \E key \in Keys:
    /\ entries' = entries \union {key}
    /\ capturedGeneration' =
         [capturedGeneration EXCEPT ![key] = activeGeneration]
    /\ capturedSchemaStamp' =
         [capturedSchemaStamp EXCEPT ![key] = activeSchemaStamp]
    /\ capturedRelationStamp' =
         [capturedRelationStamp EXCEPT ![key] = activeRelationStamp]
    /\ capturedSource' = [capturedSource EXCEPT ![key] = activeSource]
    /\ chosenKey' = key
    /\ outcome' = PublicationRetained
    /\ UNCHANGED
         <<activeGeneration, activeSchemaStamp, activeRelationStamp,
           activeSource>>

AdvanceUnrelatedGeneration ==
  \E generation \in Generations:
    /\ generation # activeGeneration
    /\ activeGeneration' = generation
    /\ outcome' = NoOutcome
    /\ UNCHANGED
         <<activeSchemaStamp, activeRelationStamp, activeSource, entries,
           capturedGeneration, capturedSchemaStamp, capturedRelationStamp,
           capturedSource, chosenKey>>

MutateRelation ==
  \E generation \in Generations:
    \E stamp \in RelationStamps:
      /\ generation # activeGeneration
      /\ stamp # activeRelationStamp
      /\ activeGeneration' = generation
      /\ activeRelationStamp' = stamp
      /\ outcome' = NoOutcome
      /\ UNCHANGED
           <<activeSchemaStamp, activeSource, entries, capturedGeneration,
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
           <<activeSchemaStamp, activeRelationStamp, entries,
             capturedGeneration, capturedSchemaStamp,
             capturedRelationStamp, capturedSource, chosenKey>>

MutateSchema ==
  \E generation \in Generations:
    \E stamp \in SchemaStamps:
      /\ generation # activeGeneration
      /\ stamp # activeSchemaStamp
      /\ activeGeneration' = generation
      /\ activeSchemaStamp' = stamp
      /\ entries' = {}
      /\ outcome' = NoOutcome
      /\ UNCHANGED
           <<activeRelationStamp, activeSource, capturedGeneration,
             capturedSchemaStamp, capturedRelationStamp, capturedSource,
             chosenKey>>

Next ==
  \/ Lookup
  \/ PublishCompleted
  \/ AdvanceUnrelatedGeneration
  \/ MutateRelation
  \/ SwitchSource
  \/ MutateSchema

Spec == Init /\ [][Next]_vars

====================================================================

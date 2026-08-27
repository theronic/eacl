---------------- MODULE EaclSpeculativeCache ----------------
EXTENDS Integers, FiniteSets

CONSTANTS
  \* @type: Set(Int);
  Generations,
  \* @type: Set(Int);
  Stamps,
  \* @type: Set(Int);
  Sources,
  \* @type: Set(Int);
  Components,
  \* @type: Set(Int);
  Keys,
  \* Mutation controls. Either value TRUE must violate Safety.
  \* @type: Bool;
  AllowSpeculativePublication,
  \* @type: Bool;
  AllowSpeculativeExactHit

ASSUME
  /\ Generations # {}
  /\ Stamps # {}
  /\ Sources # {}
  /\ Components # {}
  /\ Keys # {}
  /\ 0 \in Generations
  /\ 0 \in Stamps
  /\ 0 \in Sources
  /\ 0 \in Components
  /\ 0 \in Keys

Ordinary == 0
Speculative == 1
Kinds == {Ordinary, Speculative}

NoOutcome == 0
ManagedHit == 1
ExactHit == 2
CacheMiss == 3
PublicationRetained == 4
PublicationDropped == 5
Outcomes ==
  {NoOutcome, ManagedHit, ExactHit, CacheMiss,
   PublicationRetained, PublicationDropped}

VARIABLES
  \* @type: Int;
  kind,
  \* The committed root never changes during one speculative chain.
  \* @type: Int;
  rootGeneration,
  \* @type: Int;
  rootSchemaStamp,
  \* @type: Int;
  rootRelationStamp,
  \* @type: Int;
  rootSource,
  \* Native speculative metadata may collide with a future commit.
  \* @type: Int;
  activeGeneration,
  \* @type: Int;
  activeSchemaStamp,
  \* @type: Int;
  activeRelationStamp,
  \* Cumulative, semantic effect certificates.
  \* @type: Set(Int);
  relationshipEffects,
  \* @type: Set(Int);
  schemaEffects,
  \* @type: Set(Int);
  otherEffects,
  \* @type: Bool;
  effectsComplete,
  \* Only committed managed entries are represented here.
  \* @type: Set(Int);
  entries,
  \* @type: Set(Int);
  exactEntries,
  \* @type: Int -> Int;
  capturedGeneration,
  \* @type: Int -> Int;
  capturedSchemaStamp,
  \* @type: Int -> Int;
  capturedRelationStamp,
  \* @type: Int -> Int;
  capturedSource,
  \* @type: Int -> Bool;
  capturedComplete,
  \* @type: Int -> Set(Int);
  relationshipDependencies,
  \* @type: Int -> Set(Int);
  schemaDependencies,
  \* @type: Int -> Set(Int);
  otherDependencies,
  \* @type: Int;
  chosenKey,
  \* @type: Int;
  outcome

vars ==
  <<kind, rootGeneration, rootSchemaStamp, rootRelationStamp, rootSource,
    activeGeneration, activeSchemaStamp, activeRelationStamp,
    relationshipEffects, schemaEffects, otherEffects, effectsComplete,
    entries, exactEntries, capturedGeneration, capturedSchemaStamp,
    capturedRelationStamp, capturedSource, capturedComplete,
    relationshipDependencies, schemaDependencies, otherDependencies,
    chosenKey, outcome>>

TypeOK ==
  /\ kind \in Kinds
  /\ rootGeneration \in Generations
  /\ rootSchemaStamp \in Stamps
  /\ rootRelationStamp \in Stamps
  /\ rootSource \in Sources
  /\ activeGeneration \in Generations
  /\ activeSchemaStamp \in Stamps
  /\ activeRelationStamp \in Stamps
  /\ relationshipEffects \in SUBSET Components
  /\ schemaEffects \in SUBSET Components
  /\ otherEffects \in SUBSET Components
  /\ effectsComplete \in BOOLEAN
  /\ entries \in SUBSET Keys
  /\ exactEntries \in SUBSET Keys
  /\ capturedGeneration \in [Keys -> Generations]
  /\ capturedSchemaStamp \in [Keys -> Stamps]
  /\ capturedRelationStamp \in [Keys -> Stamps]
  /\ capturedSource \in [Keys -> Sources]
  /\ capturedComplete \in [Keys -> BOOLEAN]
  /\ relationshipDependencies \in [Keys -> SUBSET Components]
  /\ schemaDependencies \in [Keys -> SUBSET Components]
  /\ otherDependencies \in [Keys -> SUBSET Components]
  /\ chosenKey \in Keys
  /\ outcome \in Outcomes

CommittedRootValid(key) ==
  /\ key \in entries
  /\ capturedComplete[key]
  /\ capturedGeneration[key] = rootGeneration
  /\ capturedSchemaStamp[key] = rootSchemaStamp
  /\ capturedRelationStamp[key] = rootRelationStamp
  /\ capturedSource[key] = rootSource

DisjointFromSpeculation(key) ==
  /\ effectsComplete
  /\ relationshipDependencies[key] \intersect relationshipEffects = {}
  /\ schemaDependencies[key] \intersect schemaEffects = {}
  /\ otherDependencies[key] \intersect otherEffects = {}

ManagedHitIsProved ==
  outcome # ManagedHit \/
    /\ CommittedRootValid(chosenKey)
    /\ IF kind = Speculative
          THEN DisjointFromSpeculation(chosenKey)
          ELSE TRUE

SpeculativeNeverUsesExactTier ==
  outcome # ExactHit \/ kind = Ordinary

SpeculativeNeverPublishes ==
  outcome # PublicationRetained \/ kind = Ordinary

OrdinaryStateHasNoSpeculativeEffects ==
  kind # Ordinary \/
    /\ relationshipEffects = {}
    /\ schemaEffects = {}
    /\ otherEffects = {}
    /\ effectsComplete

Safety ==
  /\ ManagedHitIsProved
  /\ SpeculativeNeverUsesExactTier
  /\ SpeculativeNeverPublishes
  /\ OrdinaryStateHasNoSpeculativeEffects

InductiveInvariant == /\ TypeOK /\ Safety

Init ==
  /\ kind = Ordinary
  /\ rootGeneration = 0
  /\ rootSchemaStamp = 0
  /\ rootRelationStamp = 0
  /\ rootSource = 0
  /\ activeGeneration = 0
  /\ activeSchemaStamp = 0
  /\ activeRelationStamp = 0
  /\ relationshipEffects = {}
  /\ schemaEffects = {}
  /\ otherEffects = {}
  /\ effectsComplete = TRUE
  /\ entries = {}
  /\ exactEntries = {}
  /\ capturedGeneration = [key \in Keys |-> 0]
  /\ capturedSchemaStamp = [key \in Keys |-> 0]
  /\ capturedRelationStamp = [key \in Keys |-> 0]
  /\ capturedSource = [key \in Keys |-> 0]
  /\ capturedComplete = [key \in Keys |-> FALSE]
  /\ relationshipDependencies = [key \in Keys |-> {}]
  /\ schemaDependencies = [key \in Keys |-> {}]
  /\ otherDependencies = [key \in Keys |-> {}]
  /\ chosenKey = 0
  /\ outcome = NoOutcome

PublishCompleted ==
  \E key \in Keys:
    \E relationDeps \in SUBSET Components:
      \E schemaDeps \in SUBSET Components:
        \E remainingDeps \in SUBSET Components:
          /\ chosenKey' = key
          /\ IF kind = Ordinary \/ AllowSpeculativePublication
                THEN
                  /\ entries' = entries \union {key}
                  /\ exactEntries' = exactEntries \union {key}
                  /\ capturedGeneration' =
                       [capturedGeneration EXCEPT ![key] = rootGeneration]
                  /\ capturedSchemaStamp' =
                       [capturedSchemaStamp EXCEPT ![key] = rootSchemaStamp]
                  /\ capturedRelationStamp' =
                       [capturedRelationStamp EXCEPT ![key] = rootRelationStamp]
                  /\ capturedSource' =
                       [capturedSource EXCEPT ![key] = rootSource]
                  /\ capturedComplete' =
                       [capturedComplete EXCEPT ![key] = TRUE]
                  /\ relationshipDependencies' =
                       [relationshipDependencies EXCEPT ![key] = relationDeps]
                  /\ schemaDependencies' =
                       [schemaDependencies EXCEPT ![key] = schemaDeps]
                  /\ otherDependencies' =
                       [otherDependencies EXCEPT ![key] = remainingDeps]
                  /\ outcome' = PublicationRetained
                ELSE
                  /\ UNCHANGED
                       <<entries, exactEntries, capturedGeneration,
                         capturedSchemaStamp, capturedRelationStamp,
                         capturedSource, capturedComplete,
                         relationshipDependencies, schemaDependencies,
                         otherDependencies>>
                  /\ outcome' = PublicationDropped
          /\ UNCHANGED
               <<kind, rootGeneration, rootSchemaStamp, rootRelationStamp,
                 rootSource, activeGeneration, activeSchemaStamp,
                 activeRelationStamp, relationshipEffects, schemaEffects,
                 otherEffects, effectsComplete>>

LookupManaged ==
  \E key \in Keys:
    /\ chosenKey' = key
    /\ outcome' =
         IF /\ CommittedRootValid(key)
            /\ IF kind = Speculative
                  THEN DisjointFromSpeculation(key)
                  ELSE TRUE
         THEN ManagedHit
         ELSE CacheMiss
    /\ UNCHANGED
         <<kind, rootGeneration, rootSchemaStamp, rootRelationStamp,
           rootSource, activeGeneration, activeSchemaStamp,
           activeRelationStamp, relationshipEffects, schemaEffects,
           otherEffects, effectsComplete, entries, exactEntries,
           capturedGeneration, capturedSchemaStamp, capturedRelationStamp,
           capturedSource, capturedComplete, relationshipDependencies,
           schemaDependencies, otherDependencies>>

LookupExact ==
  \E key \in Keys:
    /\ chosenKey' = key
    /\ outcome' =
         IF /\ key \in exactEntries
            /\ capturedGeneration[key] = activeGeneration
            /\ capturedSource[key] = rootSource
            /\ (kind = Ordinary \/ AllowSpeculativeExactHit)
         THEN ExactHit
         ELSE CacheMiss
    /\ UNCHANGED
         <<kind, rootGeneration, rootSchemaStamp, rootRelationStamp,
           rootSource, activeGeneration, activeSchemaStamp,
           activeRelationStamp, relationshipEffects, schemaEffects,
           otherEffects, effectsComplete, entries, exactEntries,
           capturedGeneration, capturedSchemaStamp, capturedRelationStamp,
           capturedSource, capturedComplete, relationshipDependencies,
           schemaDependencies, otherDependencies>>

EnterSpeculative ==
  \E generation \in Generations:
    \E schemaStamp \in Stamps:
      \E relationStamp \in Stamps:
        \E relationEffects \in SUBSET Components:
          \E changedSchema \in SUBSET Components:
            \E remainingEffects \in SUBSET Components:
              \E complete \in BOOLEAN:
                /\ kind = Ordinary
                /\ kind' = Speculative
                /\ activeGeneration' = generation
                /\ activeSchemaStamp' = schemaStamp
                /\ activeRelationStamp' = relationStamp
                /\ relationshipEffects' = relationEffects
                /\ schemaEffects' = changedSchema
                /\ otherEffects' = remainingEffects
                /\ effectsComplete' = complete
                /\ outcome' = NoOutcome
                /\ UNCHANGED
                     <<rootGeneration, rootSchemaStamp, rootRelationStamp,
                       rootSource, entries, exactEntries, capturedGeneration,
                       capturedSchemaStamp, capturedRelationStamp,
                       capturedSource, capturedComplete,
                       relationshipDependencies, schemaDependencies,
                       otherDependencies, chosenKey>>

ChainSpeculative ==
  \E generation \in Generations:
    \E schemaStamp \in Stamps:
      \E relationStamp \in Stamps:
        \E relationEffects \in SUBSET Components:
          \E changedSchema \in SUBSET Components:
            \E remainingEffects \in SUBSET Components:
              \E complete \in BOOLEAN:
                /\ kind = Speculative
                /\ activeGeneration' = generation
                /\ activeSchemaStamp' = schemaStamp
                /\ activeRelationStamp' = relationStamp
                /\ relationshipEffects' =
                     relationshipEffects \union relationEffects
                /\ schemaEffects' = schemaEffects \union changedSchema
                /\ otherEffects' = otherEffects \union remainingEffects
                /\ effectsComplete' = effectsComplete /\ complete
                /\ outcome' = NoOutcome
                /\ UNCHANGED
                     <<kind, rootGeneration, rootSchemaStamp,
                       rootRelationStamp, rootSource, entries, exactEntries,
                       capturedGeneration, capturedSchemaStamp,
                       capturedRelationStamp, capturedSource,
                       capturedComplete, relationshipDependencies,
                       schemaDependencies, otherDependencies, chosenKey>>

ReturnToCommittedRoot ==
  /\ kind = Speculative
  /\ kind' = Ordinary
  /\ activeGeneration' = rootGeneration
  /\ activeSchemaStamp' = rootSchemaStamp
  /\ activeRelationStamp' = rootRelationStamp
  /\ relationshipEffects' = {}
  /\ schemaEffects' = {}
  /\ otherEffects' = {}
  /\ effectsComplete' = TRUE
  /\ outcome' = NoOutcome
  /\ UNCHANGED
       <<rootGeneration, rootSchemaStamp, rootRelationStamp, rootSource,
         entries, exactEntries, capturedGeneration, capturedSchemaStamp,
         capturedRelationStamp, capturedSource, capturedComplete,
         relationshipDependencies, schemaDependencies, otherDependencies,
         chosenKey>>

AdvanceCommitted ==
  \E generation \in Generations:
    \E schemaStamp \in Stamps:
      \E relationStamp \in Stamps:
        \E source \in Sources:
          /\ kind = Ordinary
          /\ rootGeneration' = generation
          /\ rootSchemaStamp' = schemaStamp
          /\ rootRelationStamp' = relationStamp
          /\ rootSource' = source
          /\ activeGeneration' = generation
          /\ activeSchemaStamp' = schemaStamp
          /\ activeRelationStamp' = relationStamp
          /\ outcome' = NoOutcome
          /\ UNCHANGED
               <<kind, relationshipEffects, schemaEffects, otherEffects,
                 effectsComplete, entries, exactEntries, capturedGeneration,
                 capturedSchemaStamp, capturedRelationStamp, capturedSource,
                 capturedComplete, relationshipDependencies,
                 schemaDependencies, otherDependencies, chosenKey>>

Next ==
  \/ PublishCompleted
  \/ LookupManaged
  \/ LookupExact
  \/ EnterSpeculative
  \/ ChainSpeculative
  \/ ReturnToCommittedRoot
  \/ AdvanceCommitted

Spec == Init /\ [][Next]_vars

====================================================================

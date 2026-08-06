---------------- MODULE EaclTieredSubproblemCache ----------------
EXTENDS Integers, FiniteSets

CONSTANTS
  \* @type: Set(Int);
  Generations,
  \* @type: Set(Int);
  Keys,
  \* @type: Set(Int);
  Tiers,
  \* @type: Int;
  ProjectionTier,
  \* @type: Int;
  DenotationTier,
  \* @type: Int;
  ProjectionBudget,
  \* @type: Int;
  DenotationBudget,
  \* @type: Int;
  CandidateBound,
  \* @type: Int;
  ExecutionBound,
  \* @type: Int;
  MaximumLifecycle,
  \* @type: Bool;
  AllowPartialHit,
  \* @type: Bool;
  AllowStaleHit,
  \* @type: Bool;
  AllowDuplicateStart,
  \* @type: Bool;
  PublishLateOrphan,
  \* @type: Bool;
  CoupleTierBudgets,
  \* @type: Bool;
  EvictIncompleteAndDropFlight,
  \* @type: Bool;
  AllowObsoleteFlightJoin

ASSUME
  /\ Generations # {}
  /\ Keys # {}
  /\ 0 \in Generations
  /\ 0 \in Keys
  /\ ProjectionTier # DenotationTier
  /\ Tiers = {ProjectionTier, DenotationTier}
  /\ 0 < ProjectionBudget
  /\ 0 < DenotationBudget
  /\ 0 < CandidateBound
  /\ 0 < ExecutionBound
  /\ 0 < MaximumLifecycle

Absent == 0
Waiting == 1
Computing == 2
Partial == 3
Complete == 4
Failed == 5
EntryStates == {Absent, Waiting, Computing, Partial, Complete, Failed}

NoOutcome == 0
CacheHit == 1
CacheMiss == 2
SingleFlightJoin == 3
OrphanDropped == 4
Outcomes ==
  {NoOutcome, CacheHit, CacheMiss, SingleFlightJoin, OrphanDropped}

TierBudget(tier) ==
  IF tier = ProjectionTier
  THEN ProjectionBudget
  ELSE DenotationBudget

AdmissionTierBudget(tier) ==
  IF CoupleTierBudgets
  THEN ProjectionBudget + DenotationBudget
  ELSE TierBudget(tier)

VARIABLES
  \* @type: Int;
  activeGeneration,
  \* @type: Int;
  lifecycle,
  \* @type: Int -> (Int -> Int);
  entryState,
  \* @type: Int -> (Int -> Int);
  capturedGeneration,
  \* @type: Int -> (Int -> Int);
  capturedLifecycle,
  \* @type: Int -> (Int -> Int);
  entryWeight,
  \* @type: Int -> Int;
  admittedWeight,
  \* @type: Int;
  chosenTier,
  \* @type: Int;
  chosenKey,
  \* @type: Int;
  outcome,
  \* @type: Int;
  orphanPublicationCount,
  \* @type: Int;
  actualActiveComputations,
  \* Flight ownership is coordinator state, indexed by the lifecycle ticket
  \* as well as tier and key. Expiry may therefore detach an old flight while
  \* a new-lifecycle flight for the same tier/key waits or executes.
  \* @type: <<Int, Int, Int>> -> Bool;
  flightOwner,
  \* @type: <<Int, Int, Int>> -> Int;
  actualCount

vars ==
  <<activeGeneration, lifecycle, entryState, capturedGeneration,
    capturedLifecycle, entryWeight, admittedWeight, chosenTier,
    chosenKey, outcome, orphanPublicationCount,
    actualActiveComputations, flightOwner, actualCount>>

\* @type: Set(<<Int, Int>>);
Addresses == Tiers \X Keys

\* Apalache 0.58.3 cannot check inductiveness over a three-level nested
\* finite-function type. A tuple-keyed function expresses the same product
\* state without adding a semantic abstraction.
\* @type: Set(<<Int, Int, Int>>);
FlightAddresses == (0..MaximumLifecycle) \X Tiers \X Keys

\* @type: Set(<<Int, Int>>);
CompleteAddresses ==
  UNION
    {IF entryState[tier][key] = Complete
     THEN {<<tier, key>>}
     ELSE {}:
       tier \in Tiers, key \in Keys}

\* @type: Set(<<Int, Int>>);
InflightAddresses ==
  UNION
    {IF entryState[tier][key] \in {Waiting, Computing, Partial}
     THEN {<<tier, key>>}
     ELSE {}:
       tier \in Tiers, key \in Keys}

\* @type: Set(<<Int, Int>>);
ActiveRepresentedAddresses ==
  UNION
    {IF entryState[tier][key] \in {Computing, Partial}
     THEN {<<tier, key>>}
     ELSE {}:
       tier \in Tiers, key \in Keys}

\* @type: Set(<<Int, Int, Int>>);
ActiveExecutionAddresses ==
  UNION
    {IF 0 < actualCount[<<flightLifecycle, tier, key>>]
     THEN {<<flightLifecycle, tier, key>>}
     ELSE {}:
       flightLifecycle \in 0..MaximumLifecycle,
       tier \in Tiers,
       key \in Keys}

\* @type: Set(<<Int, Int, Int>>);
RegisteredFlightAddresses ==
  UNION
    {IF flightOwner[<<flightLifecycle, tier, key>>]
     THEN {<<flightLifecycle, tier, key>>}
     ELSE {}:
       flightLifecycle \in 0..MaximumLifecycle,
       tier \in Tiers,
       key \in Keys}

\* @type: Set(<<Int, Int>>);
ReservedAddresses ==
  CompleteAddresses \union InflightAddresses

ReservedKeysInTier(tier) ==
  {key \in Keys:
    <<tier, key>> \in ReservedAddresses}

TypeOK ==
  /\ activeGeneration \in Generations
  /\ lifecycle \in 0..MaximumLifecycle
  /\ entryState \in [Tiers -> [Keys -> EntryStates]]
  /\ capturedGeneration \in [Tiers -> [Keys -> Generations]]
  /\ capturedLifecycle \in
     [Tiers -> [Keys -> 0..MaximumLifecycle]]
  /\ entryWeight \in [Tiers -> [Keys -> 0..1]]
  /\ admittedWeight \in
     [Tiers -> 0..(ProjectionBudget + DenotationBudget)]
  /\ chosenTier \in Tiers
  /\ chosenKey \in Keys
  /\ outcome \in Outcomes
  /\ orphanPublicationCount \in 0..1
  /\ actualActiveComputations \in 0..ExecutionBound
  /\ flightOwner \in [FlightAddresses -> BOOLEAN]
  /\ actualCount \in [FlightAddresses -> 0..ExecutionBound]

TierWeightBounds ==
  \A tier \in Tiers:
    admittedWeight[tier] <= TierBudget(tier)

EntryWeightsAreAccounted ==
  \A tier \in Tiers:
    /\ admittedWeight[tier] =
       Cardinality(ReservedKeysInTier(tier))
    /\ \A key \in Keys:
      entryWeight[tier][key] =
        IF key \in ReservedKeysInTier(tier) THEN 1 ELSE 0

RepresentedCandidatesAreCurrent ==
  \A address \in ReservedAddresses:
    /\ capturedGeneration[address[1]][address[2]] =
       activeGeneration
    /\ capturedLifecycle[address[1]][address[2]] = lifecycle

HitIsCompleteAndCurrent ==
  outcome # CacheHit \/
    /\ entryState[chosenTier][chosenKey] = Complete
    /\ capturedGeneration[chosenTier][chosenKey] =
       activeGeneration
    /\ capturedLifecycle[chosenTier][chosenKey] = lifecycle

SingleFlightJoinIsCurrentAndIncomplete ==
  outcome # SingleFlightJoin \/
    /\ flightOwner[<<lifecycle, chosenTier, chosenKey>>]
    /\ entryState[chosenTier][chosenKey] \in
         {Absent, Failed} \/
       /\ entryState[chosenTier][chosenKey] \in
          {Waiting, Computing, Partial}
       /\ capturedGeneration[chosenTier][chosenKey] =
          activeGeneration
       /\ capturedLifecycle[chosenTier][chosenKey] = lifecycle

PartialRecursiveDenotationNeverHits ==
  outcome # CacheHit \/
    chosenTier # DenotationTier \/
    entryState[chosenTier][chosenKey] = Complete

InflightIsGloballyBounded ==
  Cardinality(InflightAddresses) <= CandidateBound

ActiveRepresentedFlightsAreAccounted ==
  \A address \in ActiveRepresentedAddresses:
    actualCount[
      <<capturedLifecycle[address[1]][address[2]],
        address[1],
        address[2]>>] = 1

IncompleteCandidatesKeepFlightOwnership ==
  \A address \in InflightAddresses:
    flightOwner[
      <<capturedLifecycle[address[1]][address[2]],
        address[1],
        address[2]>>]

ActiveExecutionsKeepFlightOwnership ==
  \A address \in ActiveExecutionAddresses:
    flightOwner[address]

ExactAddressHasAtMostOneActiveComputation ==
  \A flightLifecycle \in 0..MaximumLifecycle:
    \A tier \in Tiers:
      \A key \in Keys:
        actualCount[<<flightLifecycle, tier, key>>] <= 1

ActualComputationAccounting ==
  actualActiveComputations =
    Cardinality(ActiveExecutionAddresses)

ActualComputationsAreGloballyBounded ==
  actualActiveComputations <= ExecutionBound

LateCompletionCannotPublish ==
  orphanPublicationCount = 0

Safety ==
  /\ TierWeightBounds
  /\ EntryWeightsAreAccounted
  /\ RepresentedCandidatesAreCurrent
  /\ HitIsCompleteAndCurrent
  /\ SingleFlightJoinIsCurrentAndIncomplete
  /\ PartialRecursiveDenotationNeverHits
  /\ InflightIsGloballyBounded
  /\ ActiveRepresentedFlightsAreAccounted
  /\ IncompleteCandidatesKeepFlightOwnership
  /\ ActiveExecutionsKeepFlightOwnership
  /\ ExactAddressHasAtMostOneActiveComputation
  /\ ActualComputationAccounting
  /\ ActualComputationsAreGloballyBounded
  /\ LateCompletionCannotPublish

InductiveInvariant ==
  /\ TypeOK
  /\ Safety

Init ==
  /\ activeGeneration = 0
  /\ lifecycle = 0
  /\ entryState =
     [tier \in Tiers |-> [key \in Keys |-> Absent]]
  /\ capturedGeneration =
     [tier \in Tiers |-> [key \in Keys |-> 0]]
  /\ capturedLifecycle =
     [tier \in Tiers |-> [key \in Keys |-> 0]]
  /\ entryWeight =
     [tier \in Tiers |-> [key \in Keys |-> 0]]
  /\ admittedWeight = [tier \in Tiers |-> 0]
  /\ chosenTier = ProjectionTier
  /\ chosenKey = 0
  /\ outcome = NoOutcome
  /\ orphanPublicationCount = 0
  /\ actualActiveComputations = 0
  /\ flightOwner =
     [address \in FlightAddresses |-> FALSE]
  /\ actualCount =
     [address \in FlightAddresses |-> 0]

BeginMiss ==
  \E tier \in Tiers:
    \E key \in Keys:
      /\ entryState[tier][key] \in {Absent, Failed}
      /\ ~flightOwner[<<lifecycle, tier, key>>]
      /\ Cardinality(InflightAddresses) < CandidateBound
      /\ admittedWeight[tier] + 1 <= AdmissionTierBudget(tier)
      /\ entryState' =
         [entryState EXCEPT ![tier][key] = Waiting]
      /\ capturedGeneration' =
         [capturedGeneration EXCEPT
           ![tier][key] = activeGeneration]
      /\ capturedLifecycle' =
         [capturedLifecycle EXCEPT ![tier][key] = lifecycle]
      /\ entryWeight' =
         [entryWeight EXCEPT ![tier][key] = 1]
      /\ admittedWeight' =
         [admittedWeight EXCEPT ![tier] = @ + 1]
      /\ flightOwner' =
         [flightOwner EXCEPT
           ![<<lifecycle, tier, key>>] = TRUE]
      /\ chosenTier' = tier
      /\ chosenKey' = key
      /\ outcome' = CacheMiss
      /\ UNCHANGED
        <<activeGeneration, lifecycle, orphanPublicationCount,
          actualActiveComputations, actualCount>>

StartRepresentedCompute ==
  \E tier \in Tiers:
    \E key \in Keys:
      /\ entryState[tier][key] = Waiting
      /\ flightOwner[
           <<capturedLifecycle[tier][key], tier, key>>]
      /\ actualCount[
           <<capturedLifecycle[tier][key], tier, key>>] = 0
      /\ actualActiveComputations < ExecutionBound
      /\ entryState' =
         [entryState EXCEPT ![tier][key] = Computing]
      /\ actualActiveComputations' =
         actualActiveComputations + 1
      /\ actualCount' =
         [actualCount EXCEPT
           ![<<capturedLifecycle[tier][key], tier, key>>] = 1]
      /\ chosenTier' = tier
      /\ chosenKey' = key
      /\ outcome' = NoOutcome
      /\ UNCHANGED
        <<activeGeneration, lifecycle, capturedGeneration,
          capturedLifecycle, entryWeight, admittedWeight,
          orphanPublicationCount, flightOwner>>

JoinOrRead ==
  \E tier \in Tiers:
    \E key \in Keys:
      /\ chosenTier' = tier
      /\ chosenKey' = key
      /\ outcome' =
        IF /\ \/ entryState[tier][key] = Complete
               \/ /\ AllowPartialHit
                  /\ entryState[tier][key] = Partial
           /\ \/ AllowStaleHit
              \/ /\ capturedGeneration[tier][key] = activeGeneration
                 /\ capturedLifecycle[tier][key] = lifecycle
        THEN CacheHit
             ELSE IF
                     /\ IF AllowObsoleteFlightJoin
                        THEN \E flightLifecycle
                               \in 0..MaximumLifecycle:
                               flightOwner[
                                 <<flightLifecycle, tier, key>>]
                        ELSE flightOwner[
                               <<lifecycle, tier, key>>]
                     /\ \/ entryState[tier][key] \in {Absent, Failed}
                        \/ /\ entryState[tier][key] \in
                              {Waiting, Computing, Partial}
                           /\ capturedGeneration[tier][key] =
                              activeGeneration
                           /\ capturedLifecycle[tier][key] = lifecycle
             THEN SingleFlightJoin
             ELSE CacheMiss
      /\ UNCHANGED
        <<activeGeneration, lifecycle, entryState,
          capturedGeneration, capturedLifecycle, entryWeight,
          admittedWeight, orphanPublicationCount,
          actualActiveComputations, flightOwner, actualCount>>

PublishComplete ==
  \E tier \in Tiers:
    \E key \in Keys:
      /\ entryState[tier][key] \in {Computing, Partial}
      /\ flightOwner[
           <<capturedLifecycle[tier][key], tier, key>>]
      /\ actualCount[
           <<capturedLifecycle[tier][key], tier, key>>] = 1
      /\ capturedGeneration[tier][key] = activeGeneration
      /\ capturedLifecycle[tier][key] = lifecycle
      /\ 0 < actualActiveComputations
      /\ entryState' =
         [entryState EXCEPT ![tier][key] = Complete]
      /\ actualActiveComputations' =
         actualActiveComputations - 1
      /\ flightOwner' =
         [flightOwner EXCEPT
           ![<<capturedLifecycle[tier][key], tier, key>>] = FALSE]
      /\ actualCount' =
         [actualCount EXCEPT
           ![<<capturedLifecycle[tier][key], tier, key>>] = 0]
      /\ chosenTier' = tier
      /\ chosenKey' = key
      /\ outcome' = NoOutcome
      /\ UNCHANGED
        <<activeGeneration, lifecycle, capturedGeneration,
          capturedLifecycle, entryWeight, admittedWeight,
          orphanPublicationCount>>

PublishPartial ==
  \E tier \in Tiers:
    \E key \in Keys:
      /\ entryState[tier][key] = Computing
      /\ entryState' =
         [entryState EXCEPT ![tier][key] = Partial]
      /\ chosenTier' = tier
      /\ chosenKey' = key
      /\ outcome' = NoOutcome
      /\ UNCHANGED
        <<activeGeneration, lifecycle, capturedGeneration,
          capturedLifecycle, entryWeight, admittedWeight,
          orphanPublicationCount, actualActiveComputations,
          flightOwner, actualCount>>

FailOrReject ==
  \E tier \in Tiers:
    \E key \in Keys:
      /\ entryState[tier][key] \in {Computing, Partial}
      /\ flightOwner[
           <<capturedLifecycle[tier][key], tier, key>>]
      /\ actualCount[
           <<capturedLifecycle[tier][key], tier, key>>] = 1
      /\ 0 < actualActiveComputations
      /\ entryState' =
         [entryState EXCEPT ![tier][key] = Absent]
      /\ entryWeight' =
         [entryWeight EXCEPT ![tier][key] = 0]
      /\ admittedWeight' =
         [admittedWeight EXCEPT ![tier] = @ - 1]
      /\ actualActiveComputations' =
         actualActiveComputations - 1
      /\ flightOwner' =
         [flightOwner EXCEPT
           ![<<capturedLifecycle[tier][key], tier, key>>] = FALSE]
      /\ actualCount' =
         [actualCount EXCEPT
           ![<<capturedLifecycle[tier][key], tier, key>>] = 0]
      /\ chosenTier' = tier
      /\ chosenKey' = key
      /\ outcome' = NoOutcome
      /\ UNCHANGED
        <<activeGeneration, lifecycle, capturedGeneration,
          capturedLifecycle, orphanPublicationCount>>

EvictReserved ==
  \E tier \in Tiers:
    \E key \in Keys:
      /\ \/ entryState[tier][key] = Complete
         \/ /\ EvictIncompleteAndDropFlight
            /\ entryState[tier][key] \in
               {Waiting, Computing, Partial}
      /\ entryState' =
         [entryState EXCEPT ![tier][key] = Absent]
      /\ entryWeight' =
         [entryWeight EXCEPT ![tier][key] = 0]
      /\ admittedWeight' =
         [admittedWeight EXCEPT ![tier] = @ - 1]
      /\ flightOwner' =
         [flightOwner EXCEPT
           ![<<capturedLifecycle[tier][key], tier, key>>] =
             IF entryState[tier][key] = Complete
             THEN @
             ELSE FALSE]
      /\ chosenTier' = tier
      /\ chosenKey' = key
      /\ outcome' = NoOutcome
      /\ UNCHANGED
        <<activeGeneration, lifecycle, capturedGeneration,
          capturedLifecycle, orphanPublicationCount,
          actualActiveComputations, actualCount>>

ExpireGeneration ==
  \E generation \in Generations:
    /\ lifecycle < MaximumLifecycle
    /\ activeGeneration' = generation
    /\ lifecycle' = lifecycle + 1
    /\ entryState' =
       [tier \in Tiers |-> [key \in Keys |-> Absent]]
    /\ capturedGeneration' =
       [tier \in Tiers |-> [key \in Keys |-> generation]]
    /\ capturedLifecycle' =
       [tier \in Tiers |-> [key \in Keys |-> lifecycle + 1]]
    /\ entryWeight' =
       [tier \in Tiers |-> [key \in Keys |-> 0]]
    /\ admittedWeight' = [tier \in Tiers |-> 0]
    /\ outcome' = NoOutcome
    /\ UNCHANGED
      <<chosenTier, chosenKey, orphanPublicationCount,
        actualActiveComputations, flightOwner, actualCount>>

UnsafeAdvanceGeneration ==
  /\ AllowStaleHit
  /\ \E generation \in Generations:
    /\ generation # activeGeneration
    /\ activeGeneration' = generation
    /\ outcome' = NoOutcome
    /\ UNCHANGED
      <<lifecycle, entryState, capturedGeneration,
        capturedLifecycle, entryWeight, admittedWeight,
        chosenTier, chosenKey, orphanPublicationCount,
        actualActiveComputations, flightOwner, actualCount>>

BeginUnadmittedFlight ==
  \E tier \in Tiers:
    \E key \in Keys:
      /\ entryState[tier][key] \in {Absent, Failed}
      /\ ~flightOwner[<<lifecycle, tier, key>>]
      /\ flightOwner' =
         [flightOwner EXCEPT
           ![<<lifecycle, tier, key>>] = TRUE]
      /\ chosenTier' = tier
      /\ chosenKey' = key
      /\ outcome' = CacheMiss
      /\ UNCHANGED
        <<activeGeneration, lifecycle, entryState,
          capturedGeneration, capturedLifecycle, entryWeight,
          admittedWeight, orphanPublicationCount,
          actualActiveComputations, actualCount>>

StartUnrepresentedCompute ==
  \E tier \in Tiers:
    \E key \in Keys:
      /\ entryState[tier][key] \in {Absent, Failed}
      /\ flightOwner[<<lifecycle, tier, key>>]
      /\ \/ actualCount[<<lifecycle, tier, key>>] = 0
         \/ AllowDuplicateStart
      /\ actualActiveComputations < ExecutionBound
      /\ actualActiveComputations' =
         actualActiveComputations + 1
      /\ actualCount' =
         [actualCount EXCEPT
           ![<<lifecycle, tier, key>>] = @ + 1]
      /\ chosenTier' = tier
      /\ chosenKey' = key
      /\ outcome' = NoOutcome
      /\ UNCHANGED
        <<activeGeneration, lifecycle, entryState,
          capturedGeneration, capturedLifecycle, entryWeight,
          admittedWeight, orphanPublicationCount, flightOwner>>

StartDetachedCompute ==
  \E flightLifecycle \in 0..MaximumLifecycle:
    \E tier \in Tiers:
      \E key \in Keys:
        /\ flightLifecycle # lifecycle
        /\ flightOwner[<<flightLifecycle, tier, key>>]
        /\ actualCount[<<flightLifecycle, tier, key>>] = 0
        /\ actualActiveComputations < ExecutionBound
        /\ actualActiveComputations' =
           actualActiveComputations + 1
        /\ actualCount' =
           [actualCount EXCEPT
             ![<<flightLifecycle, tier, key>>] = 1]
        /\ chosenTier' = tier
        /\ chosenKey' = key
        /\ outcome' = NoOutcome
        /\ UNCHANGED
          <<activeGeneration, lifecycle, entryState,
            capturedGeneration, capturedLifecycle, entryWeight,
            admittedWeight, orphanPublicationCount, flightOwner>>

FinishUnrepresentedCompute ==
  \E tier \in Tiers:
    \E key \in Keys:
      /\ entryState[tier][key] \in {Absent, Failed}
      /\ flightOwner[<<lifecycle, tier, key>>]
      /\ actualCount[<<lifecycle, tier, key>>] = 1
      /\ actualActiveComputations' =
         actualActiveComputations - 1
      /\ flightOwner' =
         [flightOwner EXCEPT
           ![<<lifecycle, tier, key>>] = FALSE]
      /\ actualCount' =
         [actualCount EXCEPT
           ![<<lifecycle, tier, key>>] = 0]
      /\ chosenTier' = tier
      /\ chosenKey' = key
      /\ outcome' = NoOutcome
      /\ UNCHANGED
        <<activeGeneration, lifecycle, entryState,
          capturedGeneration, capturedLifecycle, entryWeight,
          admittedWeight, orphanPublicationCount>>

LateOrphanCompletion ==
  \E flightLifecycle \in 0..MaximumLifecycle:
    \E tier \in Tiers:
      \E key \in Keys:
        /\ flightLifecycle # lifecycle
        /\ flightOwner[<<flightLifecycle, tier, key>>]
        /\ actualCount[<<flightLifecycle, tier, key>>] = 1
        /\ actualActiveComputations' =
           actualActiveComputations - 1
        /\ flightOwner' =
           [flightOwner EXCEPT
             ![<<flightLifecycle, tier, key>>] = FALSE]
        /\ actualCount' =
           [actualCount EXCEPT
             ![<<flightLifecycle, tier, key>>] = 0]
        /\ orphanPublicationCount' =
           IF PublishLateOrphan
           THEN 1
           ELSE orphanPublicationCount
        /\ chosenTier' = tier
        /\ chosenKey' = key
        /\ outcome' = OrphanDropped
        /\ UNCHANGED
          <<activeGeneration, lifecycle, entryState,
            capturedGeneration, capturedLifecycle, entryWeight,
            admittedWeight>>

Next ==
  \/ BeginMiss
  \/ StartRepresentedCompute
  \/ JoinOrRead
  \/ PublishComplete
  \/ PublishPartial
  \/ FailOrReject
  \/ EvictReserved
  \/ ExpireGeneration
  \/ UnsafeAdvanceGeneration
  \/ BeginUnadmittedFlight
  \/ StartUnrepresentedCompute
  \/ StartDetachedCompute
  \/ FinishUnrepresentedCompute
  \/ LateOrphanCompletion

Spec ==
  Init /\ [][Next]_vars

==================================================================

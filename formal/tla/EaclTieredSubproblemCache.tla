---------------- MODULE EaclTieredSubproblemCache ----------------
EXTENDS Integers, FiniteSets

CONSTANTS
  \* @type: Set(Int);
  Generations,
  \* @type: Set(Int);
  Keys,
  \* @type: Set(Int);
  Requests,
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
  MaximumLifecycle,
  \* Mutation controls.
  \* @type: Bool;
  AllowPartialHit,
  \* @type: Bool;
  PublishLateOrphan,
  \* @type: Bool;
  CoupleTierBudgets

ASSUME
  /\ Generations # {}
  /\ Keys # {}
  /\ Requests # {}
  /\ 0 \in Generations
  /\ 0 \in Keys
  /\ 0 \in Requests
  /\ ProjectionTier # DenotationTier
  /\ Tiers = {ProjectionTier, DenotationTier}
  /\ 0 < ProjectionBudget
  /\ 0 < DenotationBudget
  /\ 0 < MaximumLifecycle

Idle == 0
Computing == 1
RequestStates == {Idle, Computing}

NoOutcome == 0
CacheHit == 1
CacheMiss == 2
PublicationRetained == 3
PublicationDropped == 4
Outcomes ==
  {NoOutcome, CacheHit, CacheMiss, PublicationRetained, PublicationDropped}

TierBudget(tier) ==
  IF tier = ProjectionTier THEN ProjectionBudget ELSE DenotationBudget

AdmissionBudget(tier) ==
  IF CoupleTierBudgets
  THEN ProjectionBudget + DenotationBudget
  ELSE TierBudget(tier)

VARIABLES
  \* @type: Int;
  activeGeneration,
  \* @type: Int;
  lifecycle,
  \* @type: Int -> Set(Int);
  completeEntries,
  \* Partial values are never answer artifacts in the unmutated model.
  \* @type: Int -> Set(Int);
  partialEntries,
  \* @type: Int -> (Int -> Int);
  capturedGeneration,
  \* @type: Int -> (Int -> Int);
  capturedLifecycle,
  \* @type: Int -> Int;
  admittedWeight,
  \* Request-owned computation is separate from cache representation.
  \* @type: Int -> Int;
  requestState,
  \* @type: Int -> Int;
  requestTier,
  \* @type: Int -> Int;
  requestKey,
  \* @type: Int -> Int;
  requestGeneration,
  \* @type: Int -> Int;
  requestLifecycle,
  \* @type: Int;
  chosenTier,
  \* @type: Int;
  chosenKey,
  \* @type: Int;
  chosenRequest,
  \* @type: Int;
  outcome,
  \* @type: Int;
  orphanPublicationCount

vars ==
  <<activeGeneration, lifecycle, completeEntries, partialEntries,
    capturedGeneration, capturedLifecycle, admittedWeight,
    requestState, requestTier, requestKey, requestGeneration,
    requestLifecycle, chosenTier, chosenKey, chosenRequest,
    outcome, orphanPublicationCount>>

TypeOK ==
  /\ activeGeneration \in Generations
  /\ lifecycle \in 0..MaximumLifecycle
  /\ completeEntries \in [Tiers -> SUBSET Keys]
  /\ partialEntries \in [Tiers -> SUBSET Keys]
  /\ capturedGeneration \in [Tiers -> [Keys -> Generations]]
  /\ capturedLifecycle \in [Tiers -> [Keys -> 0..MaximumLifecycle]]
  /\ admittedWeight \in [Tiers -> Nat]
  /\ requestState \in [Requests -> RequestStates]
  /\ requestTier \in [Requests -> Tiers]
  /\ requestKey \in [Requests -> Keys]
  /\ requestGeneration \in [Requests -> Generations]
  /\ requestLifecycle \in [Requests -> 0..MaximumLifecycle]
  /\ chosenTier \in Tiers
  /\ chosenKey \in Keys
  /\ chosenRequest \in Requests
  /\ outcome \in Outcomes
  /\ orphanPublicationCount \in Nat

SeparateTierWeightBounds ==
  \A tier \in Tiers: admittedWeight[tier] <= TierBudget(tier)

WeightsMatchCompletedEntries ==
  \A tier \in Tiers:
    admittedWeight[tier] = Cardinality(completeEntries[tier])

PartialArtifactsNeverHit ==
  outcome # CacheHit \/ chosenKey \notin partialEntries[chosenTier]

PartialArtifactsRequireMutation ==
  AllowPartialHit \/
    \A tier \in Tiers: partialEntries[tier] = {}

HitIsCompleteAndCurrent ==
  outcome # CacheHit \/
    /\ chosenKey \in completeEntries[chosenTier]
    /\ capturedGeneration[chosenTier][chosenKey] = activeGeneration
    /\ capturedLifecycle[chosenTier][chosenKey] = lifecycle

NoRequestWaitsForAnother ==
  \A request \in Requests:
    requestState[request] \in {Idle, Computing}

LatePublicationCannotReachActiveGeneration ==
  orphanPublicationCount = 0

Safety ==
  /\ SeparateTierWeightBounds
  /\ WeightsMatchCompletedEntries
  /\ PartialArtifactsRequireMutation
  /\ PartialArtifactsNeverHit
  /\ HitIsCompleteAndCurrent
  /\ NoRequestWaitsForAnother
  /\ LatePublicationCannotReachActiveGeneration

InductiveInvariant == /\ TypeOK /\ Safety

Init ==
  /\ activeGeneration = 0
  /\ lifecycle = 0
  /\ completeEntries = [tier \in Tiers |-> {}]
  /\ partialEntries = [tier \in Tiers |-> {}]
  /\ capturedGeneration =
       [tier \in Tiers |-> [key \in Keys |-> 0]]
  /\ capturedLifecycle =
       [tier \in Tiers |-> [key \in Keys |-> 0]]
  /\ admittedWeight = [tier \in Tiers |-> 0]
  /\ requestState = [request \in Requests |-> Idle]
  /\ requestTier = [request \in Requests |-> ProjectionTier]
  /\ requestKey = [request \in Requests |-> 0]
  /\ requestGeneration = [request \in Requests |-> 0]
  /\ requestLifecycle = [request \in Requests |-> 0]
  /\ chosenTier = ProjectionTier
  /\ chosenKey = 0
  /\ chosenRequest = 0
  /\ outcome = NoOutcome
  /\ orphanPublicationCount = 0

LookupOrBeginIndependentMiss ==
  \E request \in Requests:
    \E tier \in Tiers:
      \E key \in Keys:
        /\ requestState[request] = Idle
        /\ chosenRequest' = request
        /\ chosenTier' = tier
        /\ chosenKey' = key
        /\ IF /\ \/ key \in completeEntries[tier]
                      \/ /\ AllowPartialHit
                         /\ key \in partialEntries[tier]
              /\ capturedGeneration[tier][key] = activeGeneration
              /\ capturedLifecycle[tier][key] = lifecycle
           THEN /\ outcome' = CacheHit
                /\ UNCHANGED
                     <<requestState, requestTier, requestKey,
                       requestGeneration, requestLifecycle>>
           ELSE /\ outcome' = CacheMiss
                /\ requestState' =
                     [requestState EXCEPT ![request] = Computing]
                /\ requestTier' = [requestTier EXCEPT ![request] = tier]
                /\ requestKey' = [requestKey EXCEPT ![request] = key]
                /\ requestGeneration' =
                     [requestGeneration EXCEPT
                        ![request] = activeGeneration]
                /\ requestLifecycle' =
                     [requestLifecycle EXCEPT ![request] = lifecycle]
        /\ UNCHANGED
             <<activeGeneration, lifecycle, completeEntries, partialEntries,
               capturedGeneration, capturedLifecycle, admittedWeight,
               orphanPublicationCount>>

PublishOrDiscard ==
  \E request \in Requests:
    /\ requestState[request] = Computing
    /\ chosenRequest' = request
    /\ chosenTier' = requestTier[request]
    /\ chosenKey' = requestKey[request]
    /\ requestState' = [requestState EXCEPT ![request] = Idle]
    /\ IF /\ \/ PublishLateOrphan
                \/ /\ requestGeneration[request] = activeGeneration
                   /\ requestLifecycle[request] = lifecycle
          /\ requestKey[request] \notin completeEntries[requestTier[request]]
          /\ admittedWeight[requestTier[request]] <
             AdmissionBudget(requestTier[request])
       THEN /\ completeEntries' =
                  [completeEntries EXCEPT
                    ![requestTier[request]] =
                      @ \union {requestKey[request]}]
            /\ capturedGeneration' =
                 [capturedGeneration EXCEPT
                   ![requestTier[request]][requestKey[request]] =
                     requestGeneration[request]]
            /\ capturedLifecycle' =
                 [capturedLifecycle EXCEPT
                   ![requestTier[request]][requestKey[request]] =
                     requestLifecycle[request]]
            /\ admittedWeight' =
                 [admittedWeight EXCEPT ![requestTier[request]] = @ + 1]
            /\ outcome' = PublicationRetained
            /\ orphanPublicationCount' =
                 orphanPublicationCount +
                 (IF requestLifecycle[request] # lifecycle THEN 1 ELSE 0)
       ELSE /\ UNCHANGED
                  <<completeEntries, capturedGeneration,
                    capturedLifecycle, admittedWeight,
                    orphanPublicationCount>>
            /\ outcome' = PublicationDropped
    /\ UNCHANGED
         <<activeGeneration, lifecycle, partialEntries, requestTier,
           requestKey, requestGeneration, requestLifecycle>>

PublishPartialMutation ==
  /\ AllowPartialHit
  /\ \E tier \in Tiers:
       \E key \in Keys:
         /\ partialEntries' =
              [partialEntries EXCEPT ![tier] = @ \union {key}]
         /\ capturedGeneration' =
              [capturedGeneration EXCEPT ![tier][key] = activeGeneration]
         /\ capturedLifecycle' =
              [capturedLifecycle EXCEPT ![tier][key] = lifecycle]
         /\ chosenTier' = tier
         /\ chosenKey' = key
         /\ outcome' = NoOutcome
  /\ UNCHANGED
       <<activeGeneration, lifecycle, completeEntries, admittedWeight,
         requestState, requestTier, requestKey, requestGeneration,
         requestLifecycle, chosenRequest, orphanPublicationCount>>

ExpireGeneration ==
  /\ lifecycle < MaximumLifecycle
  /\ \E generation \in Generations:
       /\ activeGeneration' = generation
       /\ lifecycle' = lifecycle + 1
  /\ completeEntries' = [tier \in Tiers |-> {}]
  /\ partialEntries' = [tier \in Tiers |-> {}]
  /\ admittedWeight' = [tier \in Tiers |-> 0]
  /\ outcome' = NoOutcome
  /\ UNCHANGED
       <<capturedGeneration, capturedLifecycle, requestState,
         requestTier, requestKey, requestGeneration, requestLifecycle,
         chosenTier, chosenKey, chosenRequest, orphanPublicationCount>>

Next ==
  \/ LookupOrBeginIndependentMiss
  \/ PublishOrDiscard
  \/ PublishPartialMutation
  \/ ExpireGeneration

Spec == Init /\ [][Next]_vars

====================================================================

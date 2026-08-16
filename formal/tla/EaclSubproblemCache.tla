--------------------- MODULE EaclSubproblemCache ---------------------
EXTENDS Integers, FiniteSets

CONSTANTS
  \* @type: Set(Int);
  Generations,
  \* @type: Set(Int);
  Keys,
  \* @type: Set(Int);
  Requests,
  \* @type: Int;
  Budget

ASSUME
  /\ Generations # {}
  /\ Keys # {}
  /\ Requests # {}
  /\ 0 \in Generations
  /\ 0 \in Keys
  /\ 0 \in Requests
  /\ 0 < Budget

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

VARIABLES
  \* @type: Int;
  activeGeneration,
  \* @type: Int;
  lifecycle,
  \* Completed immutable values only. There is no computing cache entry.
  \* @type: Set(Int);
  entries,
  \* @type: Int -> Int;
  entryGeneration,
  \* @type: Int -> Int;
  entryLifecycle,
  \* Each miss is owned independently by its initiating request.
  \* @type: Int -> Int;
  requestState,
  \* @type: Int -> Int;
  requestKey,
  \* @type: Int -> Int;
  requestGeneration,
  \* @type: Int -> Int;
  requestLifecycle,
  \* @type: Int;
  chosenKey,
  \* @type: Int;
  chosenRequest,
  \* @type: Int;
  outcome,
  \* @type: Int;
  detachedPublications

vars ==
  <<activeGeneration, lifecycle, entries, entryGeneration, entryLifecycle,
    requestState, requestKey, requestGeneration, requestLifecycle,
    chosenKey, chosenRequest, outcome, detachedPublications>>

TypeOK ==
  /\ activeGeneration \in Generations
  /\ lifecycle \in Nat
  /\ entries \in SUBSET Keys
  /\ entryGeneration \in [Keys -> Generations]
  /\ entryLifecycle \in [Keys -> Nat]
  /\ requestState \in [Requests -> RequestStates]
  /\ requestKey \in [Requests -> Keys]
  /\ requestGeneration \in [Requests -> Generations]
  /\ requestLifecycle \in [Requests -> Nat]
  /\ chosenKey \in Keys
  /\ chosenRequest \in Requests
  /\ outcome \in Outcomes
  /\ detachedPublications \in Nat

CompletedStoreIsBounded == Cardinality(entries) <= Budget

HitIsCompletedAndCurrent ==
  outcome # CacheHit \/
    /\ chosenKey \in entries
    /\ entryGeneration[chosenKey] = activeGeneration
    /\ entryLifecycle[chosenKey] = lifecycle

OnlyCompletedValuesAreRepresented ==
  \A key \in entries:
    /\ entryGeneration[key] = activeGeneration
    /\ entryLifecycle[key] = lifecycle

NoRequestWaitsForAnother ==
  \A request \in Requests:
    requestState[request] \in {Idle, Computing}

LateGenerationCannotPublish ==
  \A request \in Requests:
    requestState[request] = Computing /\
    requestLifecycle[request] # lifecycle
      => requestKey[request] \notin entries \/
         entryLifecycle[requestKey[request]] = lifecycle

Safety ==
  /\ CompletedStoreIsBounded
  /\ HitIsCompletedAndCurrent
  /\ OnlyCompletedValuesAreRepresented
  /\ NoRequestWaitsForAnother
  /\ LateGenerationCannotPublish

InductiveInvariant ==
  /\ TypeOK
  /\ Safety

Init ==
  /\ activeGeneration = 0
  /\ lifecycle = 0
  /\ entries = {}
  /\ entryGeneration = [key \in Keys |-> 0]
  /\ entryLifecycle = [key \in Keys |-> 0]
  /\ requestState = [request \in Requests |-> Idle]
  /\ requestKey = [request \in Requests |-> 0]
  /\ requestGeneration = [request \in Requests |-> 0]
  /\ requestLifecycle = [request \in Requests |-> 0]
  /\ chosenKey = 0
  /\ chosenRequest = 0
  /\ outcome = NoOutcome
  /\ detachedPublications = 0

LookupOrBeginIndependentMiss ==
  \E request \in Requests:
    \E key \in Keys:
      /\ requestState[request] = Idle
      /\ chosenRequest' = request
      /\ chosenKey' = key
      /\ IF /\ key \in entries
            /\ entryGeneration[key] = activeGeneration
            /\ entryLifecycle[key] = lifecycle
         THEN /\ outcome' = CacheHit
              /\ UNCHANGED
                   <<requestState, requestKey, requestGeneration,
                     requestLifecycle>>
         ELSE /\ outcome' = CacheMiss
              /\ requestState' =
                   [requestState EXCEPT ![request] = Computing]
              /\ requestKey' = [requestKey EXCEPT ![request] = key]
              /\ requestGeneration' =
                   [requestGeneration EXCEPT ![request] = activeGeneration]
              /\ requestLifecycle' =
                   [requestLifecycle EXCEPT ![request] = lifecycle]
      /\ UNCHANGED
           <<activeGeneration, lifecycle, entries, entryGeneration,
             entryLifecycle, detachedPublications>>

PublishOrDiscard ==
  \E request \in Requests:
    /\ requestState[request] = Computing
    /\ chosenRequest' = request
    /\ chosenKey' = requestKey[request]
    /\ requestState' = [requestState EXCEPT ![request] = Idle]
    /\ IF /\ requestGeneration[request] = activeGeneration
          /\ requestLifecycle[request] = lifecycle
          /\ requestKey[request] \notin entries
          /\ Cardinality(entries) < Budget
       THEN /\ entries' = entries \union {requestKey[request]}
            /\ entryGeneration' =
                 [entryGeneration EXCEPT
                    ![requestKey[request]] = activeGeneration]
            /\ entryLifecycle' =
                 [entryLifecycle EXCEPT ![requestKey[request]] = lifecycle]
            /\ outcome' = PublicationRetained
            /\ UNCHANGED detachedPublications
       ELSE /\ UNCHANGED <<entries, entryGeneration, entryLifecycle>>
            /\ outcome' = PublicationDropped
            /\ detachedPublications' =
                 detachedPublications +
                 (IF requestLifecycle[request] # lifecycle THEN 1 ELSE 0)
    /\ UNCHANGED
         <<activeGeneration, lifecycle, requestKey, requestGeneration,
           requestLifecycle>>

EvictCompleted ==
  \E key \in entries:
    /\ entries' = entries \ {key}
    /\ chosenKey' = key
    /\ outcome' = NoOutcome
    /\ UNCHANGED
         <<activeGeneration, lifecycle, entryGeneration, entryLifecycle,
           requestState, requestKey, requestGeneration, requestLifecycle,
           chosenRequest, detachedPublications>>

ExpireGeneration ==
  \E generation \in Generations:
    /\ activeGeneration' = generation
    /\ lifecycle' = lifecycle + 1
    /\ entries' = {}
    /\ entryGeneration' = [key \in Keys |-> generation]
    /\ entryLifecycle' = [key \in Keys |-> lifecycle']
    /\ outcome' = NoOutcome
    /\ UNCHANGED
         <<requestState, requestKey, requestGeneration, requestLifecycle,
           chosenKey, chosenRequest, detachedPublications>>

Next ==
  \/ LookupOrBeginIndependentMiss
  \/ PublishOrDiscard
  \/ EvictCompleted
  \/ ExpireGeneration

Spec == Init /\ [][Next]_vars

=====================================================================

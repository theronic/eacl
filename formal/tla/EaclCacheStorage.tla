------------------------ MODULE EaclCacheStorage ------------------------
EXTENDS Integers, FiniteSets, Sequences

CONSTANTS
  \* @type: Set(Int);
  SourceLifecycles,
  \* @type: Set(Int);
  StoreInstances,
  \* @type: Set(Int);
  Proofs,
  \* @type: Set(Int);
  Sources,
  \* @type: Set(Int);
  Semantics,
  \* @type: Set(Int);
  Requests,
  \* @type: Set(Int);
  Computations,
  \* @type: Int;
  DenotationTier,
  \* @type: Int;
  AnswerTier,
  \* @type: Int;
  ExactMode,
  \* @type: Int;
  ManagedMode,
  \* @type: Int;
  Capacity,
  \* Retained negative controls.
  \* @type: Bool;
  InstallPartialAsCompleted,
  \* @type: Bool;
  ReturnPartialAsCompletedHit,
  \* @type: Bool;
  PublishDetachedStoreInstance,
  \* @type: Bool;
  ReuseRetiredStoreInstance,
  \* @type: Bool;
  IgnoreManagedProof,
  \* @type: Bool;
  PublishWithUncapturedManagedProof

ASSUME
  /\ SourceLifecycles # {}
  /\ StoreInstances # {}
  /\ Proofs # {}
  /\ Sources # {}
  /\ Semantics # {}
  /\ Requests # {}
  /\ Computations # {}
  /\ 0 \in SourceLifecycles
  /\ 0 \in StoreInstances
  /\ 0 \in Proofs
  /\ 0 \in Sources
  /\ 0 \in Semantics
  /\ 0 \in Requests
  /\ 0 \in Computations
  /\ DenotationTier # AnswerTier
  /\ ExactMode # ManagedMode
  /\ 0 < Capacity

Modes == {ExactMode, ManagedMode}
Tiers == {DenotationTier, AnswerTier}

\* Denotation residents are exact completed point decisions. Only completed
\* answers have a managed alternative.
\* @type: Int => Set(Int);
LegalModesForTier(tier) ==
  IF tier = DenotationTier THEN {ExactMode} ELSE Modes

\* @type: (Int, Int) => Bool;
LegalTierMode(tier, mode) ==
  mode \in LegalModesForTier(tier)

\* Format version, cache domain, and engine/value ABI are fixed for this
\* transition system and therefore quotient to constants. CurrentCache and
\* SubproblemCache separately retain those fields and prove full-key equality;
\* `identity` and `semantic` are the remaining opaque exact/proof and request
\* descriptors varied by this finite model.
\* @type: (Int, Int, Int, Int, Int, Int) => Seq(Int);
CompositeKey(tier, mode, sourceLifecycle, source, identity, semantic) ==
  <<tier, mode, sourceLifecycle, source, identity, semantic>>

KeySpace ==
  UNION
    {{CompositeKey(tier, mode, sourceLifecycle, source, identity, semantic):
       mode \in LegalModesForTier(tier),
       sourceLifecycle \in SourceLifecycles,
       source \in Sources,
       identity \in Proofs,
       semantic \in Semantics}:
      tier \in Tiers}

\* @type: Seq(Int) => Int;
KeyTier(key) == key[1]
\* @type: Seq(Int) => Int;
KeyMode(key) == key[2]
\* @type: Seq(Int) => Int;
KeySourceLifecycle(key) == key[3]
\* @type: Seq(Int) => Int;
KeySource(key) == key[4]
\* @type: Seq(Int) => Int;
KeyIdentity(key) == key[5]
\* @type: Seq(Int) => Int;
KeySemantic(key) == key[6]

Idle == 0
Computing == 1
ComputationStates == {Idle, Computing}

NoOutcome == 0
ExactHit == 1
ManagedHit == 2
CacheMiss == 3
PublicationRetained == 4
PublicationDropped == 5
Outcomes ==
  {NoOutcome, ExactHit, ManagedHit, CacheMiss,
   PublicationRetained, PublicationDropped}

VARIABLES
  \* Semantic source identity is independent from the installed private store.
  \* @type: Int;
  activeSourceLifecycle,
  \* @type: Int;
  activeStoreInstance,
  \* Store-instance identities are never reused. This ghost history makes an
  \* ABA relaxation an executable negative control rather than an untested
  \* transition assumption.
  \* @type: Set(Int);
  retiredStoreInstances,
  \* @type: Int;
  activeExactIdentity,
  \* @type: Int;
  activeManagedProof,
  \* @type: Int;
  activeSource,
  \* The domain of one finite partial map whose resident values are abstracted
  \* to already validated, immutable completed values.
  \* @type: Set(Seq(Int));
  completeEntries,
  \* Resident provenance is storage identity, not part of the semantic key.
  \* @type: Seq(Int) -> Int;
  entryStoreInstance,
  \* Invalid/partial artifacts exist only in the explicit negative control.
  \* @type: Set(Seq(Int));
  partialEntries,
  \* Miss computations have independent tokens and request owners. One request
  \* may own several simultaneous subproblems.
  \* @type: Int -> Int;
  computationState,
  \* @type: Int -> Int;
  computationOwner,
  \* @type: Int -> Seq(Int);
  computationKey,
  \* Managed publication must keep the proof descriptor captured by this
  \* computation rather than reading whichever proof is active later.
  \* @type: Int -> Int;
  computationManagedProof,
  \* @type: Int -> Int;
  computationStoreInstance,
  \* @type: Int;
  chosenComputation,
  \* @type: Int;
  chosenRequest,
  \* @type: Seq(Int);
  chosenKey,
  \* @type: Int;
  outcome

vars ==
  <<activeSourceLifecycle, activeStoreInstance, retiredStoreInstances,
    activeExactIdentity,
    activeManagedProof, activeSource, completeEntries, entryStoreInstance,
    partialEntries, computationState, computationOwner, computationKey,
    computationManagedProof, computationStoreInstance, chosenComputation,
    chosenRequest, chosenKey,
    outcome>>

\* @type: (Int, Int) => Seq(Int);
ExactKey(tier, semantic) ==
  CompositeKey(
    tier,
    ExactMode,
    activeSourceLifecycle,
    activeSource,
    activeExactIdentity,
    semantic
  )

\* @type: (Int, Int) => Seq(Int);
ManagedKey(tier, semantic) ==
  CompositeKey(
    tier,
    ManagedMode,
    activeSourceLifecycle,
    activeSource,
    activeManagedProof,
    semantic
  )

\* The temporal abstraction retains at most one optional mapping per completed
\* computation step.  For an answer computation it may project that validated
\* result to the managed alternative; denotation computations remain exact.
\* Both alternatives preserve the computation's captured source/key identity.
\* @type: (Int, Bool) => Seq(Int);
CandidatePublicationKey(computation, publishManaged) ==
  LET exact == computationKey[computation]
  IN IF publishManaged /\ KeyTier(exact) = AnswerTier
     THEN CompositeKey(
            AnswerTier,
            ManagedMode,
            KeySourceLifecycle(exact),
            KeySource(exact),
            IF PublishWithUncapturedManagedProof
            THEN activeManagedProof
            ELSE computationManagedProof[computation],
            KeySemantic(exact)
          )
     ELSE exact

\* @type: (Int, Int) => Seq(Int);
ManagedCandidate(tier, semantic) ==
  CHOOSE key \in completeEntries:
    /\ tier = AnswerTier
    /\ KeyTier(key) = tier
    /\ KeyMode(key) = ManagedMode
    /\ KeySourceLifecycle(key) = activeSourceLifecycle
    /\ KeySource(key) = activeSource
    /\ KeySemantic(key) = semantic

\* @type: (Int, Int) => Bool;
HasManagedCandidate(tier, semantic) ==
  \E key \in completeEntries:
    /\ tier = AnswerTier
    /\ KeyTier(key) = tier
    /\ KeyMode(key) = ManagedMode
    /\ KeySourceLifecycle(key) = activeSourceLifecycle
    /\ KeySource(key) = activeSource
    /\ KeySemantic(key) = semantic

TypeOK ==
  /\ activeSourceLifecycle \in SourceLifecycles
  /\ activeStoreInstance \in StoreInstances
  /\ retiredStoreInstances \in SUBSET StoreInstances
  /\ activeExactIdentity \in Proofs
  /\ activeManagedProof \in Proofs
  /\ activeSource \in Sources
  /\ completeEntries \in SUBSET KeySpace
  /\ entryStoreInstance \in [KeySpace -> StoreInstances]
  /\ partialEntries \in SUBSET KeySpace
  /\ computationState \in [Computations -> ComputationStates]
  /\ computationOwner \in [Computations -> Requests]
  /\ computationKey \in [Computations -> KeySpace]
  /\ computationManagedProof \in [Computations -> Proofs]
  /\ computationStoreInstance \in [Computations -> StoreInstances]
  /\ chosenComputation \in Computations
  /\ chosenRequest \in Requests
  /\ chosenKey \in KeySpace
  /\ outcome \in Outcomes

StoreIsCountBounded ==
  \A tier \in Tiers:
    Cardinality({key \in completeEntries: KeyTier(key) = tier}) <= Capacity

OnlyCompletedValuesAreStored ==
  completeEntries \intersect partialEntries = {}

PartialCandidatesExistOnlyUnderNegativeControl ==
  (~InstallPartialAsCompleted /\ ~ReturnPartialAsCompletedHit) =>
    partialEntries = {}

InstalledEntriesBelongToInstalledStoreInstance ==
  \A key \in completeEntries:
    entryStoreInstance[key] = activeStoreInstance

CapturedStoreInstancesDoNotComeFromTheFuture ==
  \A computation \in Computations:
    computationState[computation] = Computing =>
      computationStoreInstance[computation] <= activeStoreInstance

\* Every miss captures an exact computation key. Managed publication is a
\* derived alternative that substitutes the proof captured by that miss.
\* Keeping this as an explicit invariant makes the proof discipline inductive
\* instead of relying on reachability from Init.
ActiveComputationKeysAreExact ==
  \A computation \in Computations:
    computationState[computation] = Computing =>
      KeyMode(computationKey[computation]) = ExactMode

InstalledStoreInstanceHasNeverBeenRetired ==
  activeStoreInstance \notin retiredStoreInstances

ManagedPublicationUsesCapturedProof ==
  \/ outcome # PublicationRetained
  \/ KeyMode(chosenKey) # ManagedMode
  \/ KeyIdentity(chosenKey) = computationManagedProof[chosenComputation]

HitIsAResidentCurrentCompositeKey ==
  /\ (outcome = ExactHit =>
        /\ chosenKey \in completeEntries
        /\ chosenKey = ExactKey(KeyTier(chosenKey), KeySemantic(chosenKey)))
  /\ (outcome = ManagedHit =>
        /\ chosenKey \in completeEntries
        /\ KeyTier(chosenKey) = AnswerTier
        /\ chosenKey = ManagedKey(KeyTier(chosenKey), KeySemantic(chosenKey)))

ExactFirst ==
  outcome # ManagedHit \/
    ExactKey(KeyTier(chosenKey), KeySemantic(chosenKey))
      \notin completeEntries

Safety ==
  /\ StoreIsCountBounded
  /\ OnlyCompletedValuesAreStored
  /\ PartialCandidatesExistOnlyUnderNegativeControl
  /\ InstalledEntriesBelongToInstalledStoreInstance
  /\ CapturedStoreInstancesDoNotComeFromTheFuture
  /\ ActiveComputationKeysAreExact
  /\ InstalledStoreInstanceHasNeverBeenRetired
  /\ ManagedPublicationUsesCapturedProof
  /\ HitIsAResidentCurrentCompositeKey
  /\ ExactFirst

InductiveInvariant == /\ TypeOK /\ Safety

Init ==
  /\ activeSourceLifecycle = 0
  /\ activeStoreInstance = 0
  /\ retiredStoreInstances = {}
  /\ activeExactIdentity = 0
  /\ activeManagedProof = 0
  /\ activeSource = 0
  /\ completeEntries = {}
  /\ entryStoreInstance = [key \in KeySpace |-> 0]
  /\ partialEntries = {}
  /\ computationState = [computation \in Computations |-> Idle]
  /\ computationOwner = [computation \in Computations |-> 0]
  /\ computationKey =
       [computation \in Computations |->
         CompositeKey(CHOOSE tier \in Tiers: TRUE,
                      ExactMode, 0, 0, 0, 0)]
  /\ computationManagedProof = [computation \in Computations |-> 0]
  /\ computationStoreInstance = [computation \in Computations |-> 0]
  /\ chosenComputation = 0
  /\ chosenRequest = 0
  /\ chosenKey =
       CompositeKey(CHOOSE tier \in Tiers: TRUE,
                    ExactMode, 0, 0, 0, 0)
  /\ outcome = NoOutcome

\* A miss reserves an independent computation token with its initiating
\* request and captured private store instance. There is no cache-owned
\* promise, loader, or single-flight transition to wait on or adopt.
LookupOrBeginIndependentMiss ==
  \E request \in Requests:
    \E tier \in Tiers:
      \E semantic \in Semantics:
        LET exact == ExactKey(tier, semantic)
            managed == ManagedKey(tier, semantic)
            partialFailOpen ==
              /\ ReturnPartialAsCompletedHit
              /\ exact \in partialEntries
            proofBypassed ==
              /\ IgnoreManagedProof
              /\ HasManagedCandidate(tier, semantic)
            selectedManaged ==
              IF proofBypassed
              THEN ManagedCandidate(tier, semantic)
              ELSE managed
        IN
        /\ chosenRequest' = request
        /\ IF exact \in completeEntries
           THEN /\ chosenKey' = exact
                /\ outcome' = ExactHit
                /\ UNCHANGED
                     <<computationState, computationOwner, computationKey,
                       computationManagedProof,
                       computationStoreInstance>>
           ELSE IF partialFailOpen
           THEN /\ chosenKey' = exact
                /\ outcome' = ExactHit
                /\ UNCHANGED
                     <<computationState, computationOwner, computationKey,
                       computationManagedProof,
                       computationStoreInstance>>
           ELSE IF /\ tier = AnswerTier
                   /\ (managed \in completeEntries \/ proofBypassed)
           THEN /\ chosenKey' = selectedManaged
                /\ outcome' = ManagedHit
                /\ UNCHANGED
                     <<computationState, computationOwner, computationKey,
                       computationManagedProof,
                       computationStoreInstance>>
           ELSE /\ \E computation \in Computations:
                     /\ computationState[computation] = Idle
                     /\ chosenKey' = exact
                     /\ outcome' = CacheMiss
                     /\ computationState' =
                          [computationState EXCEPT
                            ![computation] = Computing]
                     /\ computationOwner' =
                          [computationOwner EXCEPT ![computation] = request]
                     /\ computationKey' =
                          [computationKey EXCEPT ![computation] = exact]
                     /\ computationManagedProof' =
                          [computationManagedProof EXCEPT
                            ![computation] = activeManagedProof]
                     /\ computationStoreInstance' =
                          [computationStoreInstance EXCEPT
                            ![computation] = activeStoreInstance]
        /\ UNCHANGED
             <<activeSourceLifecycle, activeStoreInstance,
               retiredStoreInstances,
               activeExactIdentity, activeManagedProof, activeSource,
               completeEntries, entryStoreInstance, partialEntries,
               chosenComputation>>

PublishOrDrop ==
  \E computation \in Computations:
    \E publishManaged \in BOOLEAN:
      \E publicationEligible \in BOOLEAN:
        LET key == CandidatePublicationKey(computation, publishManaged)
        IN
        /\ computationState[computation] = Computing
        /\ chosenComputation' = computation
        /\ chosenRequest' = computationOwner[computation]
        /\ chosenKey' = key
        /\ computationState' =
             [computationState EXCEPT ![computation] = Idle]
        \* This boundary value combines completed/page retention eligibility
        \* with deadline/cancellation availability immediately before the
        \* standard-LRU publication call.
        /\ IF /\ publicationEligible
              /\ (computationStoreInstance[computation] = activeStoreInstance \/
                  PublishDetachedStoreInstance)
              /\ Cardinality(
                   {resident \in completeEntries:
                     KeyTier(resident) = KeyTier(key)}) <
                  Capacity
           THEN /\ completeEntries' = completeEntries \union {key}
                /\ entryStoreInstance' =
                     [entryStoreInstance EXCEPT
                       ![key] = computationStoreInstance[computation]]
                /\ outcome' = PublicationRetained
           ELSE /\ UNCHANGED <<completeEntries, entryStoreInstance>>
                /\ outcome' = PublicationDropped
        /\ UNCHANGED
             <<activeSourceLifecycle, activeStoreInstance,
               retiredStoreInstances, activeExactIdentity,
               activeManagedProof, activeSource, partialEntries,
               computationOwner, computationKey, computationManagedProof,
               computationStoreInstance>>

PublishPartialMutation ==
  /\ InstallPartialAsCompleted
  /\ \E semantic \in Semantics:
       LET key == ExactKey(DenotationTier, semantic)
       IN
       /\ Cardinality(
            {resident \in completeEntries:
              KeyTier(resident) = DenotationTier}) < Capacity
       /\ completeEntries' = completeEntries \union {key}
       /\ entryStoreInstance' =
            [entryStoreInstance EXCEPT ![key] = activeStoreInstance]
       /\ partialEntries' = partialEntries \union {key}
       /\ chosenKey' = key
  /\ outcome' = PublicationRetained
  /\ UNCHANGED
       <<activeSourceLifecycle, activeStoreInstance, retiredStoreInstances,
         activeExactIdentity,
         activeManagedProof, activeSource, computationState, computationOwner,
         computationKey, computationManagedProof,
         computationStoreInstance, chosenComputation, chosenRequest>>

StagePartialCandidateMutation ==
  /\ ReturnPartialAsCompletedHit
  /\ \E tier \in Tiers:
       \E semantic \in Semantics:
         LET key == ExactKey(tier, semantic)
         IN
         /\ partialEntries' = partialEntries \union {key}
         /\ chosenKey' = key
  /\ outcome' = NoOutcome
  /\ UNCHANGED
       <<activeSourceLifecycle, activeStoreInstance, retiredStoreInstances,
         activeExactIdentity,
         activeManagedProof, activeSource, completeEntries,
         entryStoreInstance, computationState, computationOwner,
         computationKey, computationManagedProof,
         computationStoreInstance, chosenComputation, chosenRequest>>

EvictArbitrary ==
  \E key \in completeEntries:
    /\ completeEntries' = completeEntries \ {key}
    /\ chosenKey' = key
    /\ outcome' = NoOutcome
    /\ UNCHANGED
         <<activeSourceLifecycle, activeStoreInstance, retiredStoreInstances,
           activeExactIdentity,
           activeManagedProof, activeSource, entryStoreInstance,
           partialEntries, computationState, computationOwner,
           computationKey, computationManagedProof,
           computationStoreInstance, chosenComputation, chosenRequest>>

AdvanceExactIdentity ==
  \E identity \in Proofs:
    /\ identity # activeExactIdentity
    /\ activeExactIdentity' = identity
    /\ outcome' = NoOutcome
    /\ UNCHANGED
         <<activeSourceLifecycle, activeStoreInstance, retiredStoreInstances,
           activeManagedProof,
           activeSource, completeEntries, entryStoreInstance, partialEntries,
           computationState, computationOwner, computationKey,
           computationManagedProof,
           computationStoreInstance,
           chosenComputation, chosenRequest, chosenKey>>

AdvanceManagedProof ==
  \E identity \in Proofs:
    /\ identity # activeManagedProof
    /\ activeManagedProof' = identity
    /\ outcome' = NoOutcome
    /\ UNCHANGED
         <<activeSourceLifecycle, activeStoreInstance, retiredStoreInstances,
           activeExactIdentity,
           activeSource, completeEntries, entryStoreInstance, partialEntries,
           computationState, computationOwner, computationKey,
           computationManagedProof,
           computationStoreInstance,
           chosenComputation, chosenRequest, chosenKey>>

AdvanceSourceLifecycle ==
  \E sourceLifecycle \in SourceLifecycles:
    /\ sourceLifecycle # activeSourceLifecycle
    /\ activeSourceLifecycle' = sourceLifecycle
    /\ outcome' = NoOutcome
    /\ UNCHANGED
         <<activeStoreInstance, retiredStoreInstances, activeExactIdentity,
           activeManagedProof,
           activeSource, completeEntries, entryStoreInstance, partialEntries,
           computationState, computationOwner, computationKey,
           computationManagedProof,
           computationStoreInstance,
           chosenComputation, chosenRequest, chosenKey>>

\* A different backend/source identity can reuse the same numeric lifecycle,
\* exact identity, proof, and semantic input.  Keeping old entries resident
\* makes source-key separation non-vacuous: they may consume capacity but
\* cannot answer a request from the newly selected source.
AdvanceSourceIdentity ==
  \E source \in Sources:
    /\ source # activeSource
    /\ activeSource' = source
    /\ outcome' = NoOutcome
    /\ UNCHANGED
         <<activeSourceLifecycle, activeStoreInstance, retiredStoreInstances,
           activeExactIdentity, activeManagedProof, completeEntries,
           entryStoreInstance, partialEntries, computationState,
           computationOwner, computationKey, computationManagedProof,
           computationStoreInstance, chosenComputation, chosenRequest,
           chosenKey>>

RotateStoreInstance ==
  \E storeInstance \in StoreInstances:
    /\ storeInstance # activeStoreInstance
    /\ IF storeInstance \notin retiredStoreInstances
       THEN activeStoreInstance < storeInstance
       ELSE /\ ReuseRetiredStoreInstance
            /\ \E computation \in Computations:
                 /\ computationState[computation] = Computing
                 /\ computationStoreInstance[computation] = storeInstance
    /\ activeStoreInstance' = storeInstance
    /\ retiredStoreInstances' =
         retiredStoreInstances \union {activeStoreInstance}
    /\ completeEntries' = {}
    /\ entryStoreInstance' = [key \in KeySpace |-> storeInstance]
    /\ partialEntries' = {}
    /\ outcome' = NoOutcome
    /\ UNCHANGED
         <<activeSourceLifecycle, activeExactIdentity, activeManagedProof,
           activeSource, computationState, computationOwner, computationKey,
           computationManagedProof,
           computationStoreInstance,
           chosenComputation, chosenRequest, chosenKey>>

Next ==
  \/ LookupOrBeginIndependentMiss
  \/ PublishOrDrop
  \/ PublishPartialMutation
  \/ StagePartialCandidateMutation
  \/ EvictArbitrary
  \/ AdvanceExactIdentity
  \/ AdvanceManagedProof
  \/ AdvanceSourceLifecycle
  \/ AdvanceSourceIdentity
  \/ RotateStoreInstance

Spec == Init /\ [][Next]_vars

=============================================================================

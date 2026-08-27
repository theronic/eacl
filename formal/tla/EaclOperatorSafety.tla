---------------------- MODULE EaclOperatorSafety ----------------------
EXTENDS Integers

CONSTANTS
  \* 0 is the safe machine; 1..6 select one explicit mutant each.
  \* @type: Int;
  Mutation,
  \* @type: Int;
  MaxCursor,
  \* @type: Int;
  SubgroupCount

ASSUME
  /\ 0 <= Mutation
  /\ Mutation <= 6
  /\ 2 <= MaxCursor
  /\ 1 < SubgroupCount

VectorIdle == 0
VectorRunning == 1
VectorComplete == 2
VectorFailed == 3
VectorStates == {VectorIdle, VectorRunning, VectorComplete, VectorFailed}

NegativePending == 0
NegativeComplete == 1
NegativeFailed == 2
NegativeStates == {NegativePending, NegativeComplete, NegativeFailed}

VARIABLES
  \* @type: Int;
  vectorState,
  \* @type: Int;
  completedSubgroups,
  \* @type: Bool;
  vectorPublished,
  \* @type: Int;
  logicalCursor,
  \* @type: Int;
  physicalCursor,
  \* @type: Int;
  checkpointCursor,
  \* @type: Int;
  resumedCursor,
  \* @type: Bool;
  resumeValid,
  \* @type: Int;
  lifecycle,
  \* @type: Bool;
  cachePresent,
  \* @type: Int;
  cacheLifecycle,
  \* @type: Bool;
  cacheHit,
  \* @type: Int;
  negativeState,
  \* @type: Bool;
  negativeAbsent,
  \* @type: Bool;
  authorizationPublished

vars ==
  <<vectorState, completedSubgroups, vectorPublished,
    logicalCursor, physicalCursor, checkpointCursor, resumedCursor,
    resumeValid, lifecycle, cachePresent, cacheLifecycle, cacheHit,
    negativeState, negativeAbsent, authorizationPublished>>

TypeOK ==
  /\ vectorState \in VectorStates
  /\ completedSubgroups \in 0..SubgroupCount
  /\ vectorPublished \in BOOLEAN
  /\ logicalCursor \in 0..MaxCursor
  /\ physicalCursor \in 0..MaxCursor
  /\ checkpointCursor \in 0..MaxCursor
  /\ resumedCursor \in 0..MaxCursor
  /\ resumeValid \in BOOLEAN
  /\ lifecycle \in Nat
  /\ cachePresent \in BOOLEAN
  /\ cacheLifecycle \in Nat
  /\ cacheHit \in BOOLEAN
  /\ negativeState \in NegativeStates
  /\ negativeAbsent \in BOOLEAN
  /\ authorizationPublished \in BOOLEAN

VectorFailureIsAtomic ==
  vectorState # VectorFailed \/ ~vectorPublished

VectorPublicationIsComplete ==
  ~vectorPublished \/
    /\ vectorState = VectorComplete
    /\ completedSubgroups = SubgroupCount

LogicalProgressDoesNotConsumePhysicalOverread ==
  /\ logicalCursor <= physicalCursor
  /\ checkpointCursor <= logicalCursor

CheckpointResumeIsExact ==
  ~resumeValid \/ resumedCursor = checkpointCursor

CacheHitUsesCurrentLifecycle ==
  ~cacheHit \/
    /\ cachePresent
    /\ cacheLifecycle = lifecycle

CacheEntryUsesCurrentLifecycle ==
  ~cachePresent \/ cacheLifecycle = lifecycle

NegativeAuthorizationUsesCompletedExactAbsence ==
  ~authorizationPublished \/
    /\ negativeState = NegativeComplete
    /\ negativeAbsent

Safety ==
  /\ VectorFailureIsAtomic
  /\ VectorPublicationIsComplete
  /\ LogicalProgressDoesNotConsumePhysicalOverread
  /\ CheckpointResumeIsExact
  /\ CacheEntryUsesCurrentLifecycle
  /\ CacheHitUsesCurrentLifecycle
  /\ NegativeAuthorizationUsesCompletedExactAbsence

InductiveInvariant == TypeOK /\ Safety

Init ==
  /\ vectorState = VectorIdle
  /\ completedSubgroups = 0
  /\ vectorPublished = FALSE
  /\ logicalCursor = 0
  /\ physicalCursor = 0
  /\ checkpointCursor = 0
  /\ resumedCursor = 0
  /\ resumeValid = FALSE
  /\ lifecycle = 0
  /\ cachePresent = FALSE
  /\ cacheLifecycle = 0
  /\ cacheHit = FALSE
  /\ negativeState = NegativePending
  /\ negativeAbsent = FALSE
  /\ authorizationPublished = FALSE

StartVector ==
  /\ vectorState = VectorIdle
  /\ vectorState' = VectorRunning
  /\ completedSubgroups' = 0
  /\ vectorPublished' = FALSE
  /\ UNCHANGED
       <<logicalCursor, physicalCursor, checkpointCursor, resumedCursor,
         resumeValid, lifecycle, cachePresent, cacheLifecycle, cacheHit,
         negativeState, negativeAbsent, authorizationPublished>>

CompleteVectorSubgroup ==
  /\ vectorState = VectorRunning
  /\ completedSubgroups < SubgroupCount
  /\ completedSubgroups' = completedSubgroups + 1
  /\ UNCHANGED
       <<vectorState, vectorPublished, logicalCursor, physicalCursor,
         checkpointCursor, resumedCursor, resumeValid, lifecycle,
         cachePresent, cacheLifecycle, cacheHit, negativeState,
         negativeAbsent, authorizationPublished>>

PublishVector ==
  /\ vectorState = VectorRunning
  /\ completedSubgroups = SubgroupCount \/ Mutation = 2
  /\ vectorState' = VectorComplete
  /\ vectorPublished' = TRUE
  /\ UNCHANGED
       <<completedSubgroups, logicalCursor, physicalCursor,
         checkpointCursor, resumedCursor, resumeValid, lifecycle,
         cachePresent, cacheLifecycle, cacheHit, negativeState,
         negativeAbsent, authorizationPublished>>

CancelVector ==
  /\ vectorState = VectorRunning
  /\ vectorState' = VectorFailed
  /\ vectorPublished' = IF Mutation = 1 THEN TRUE ELSE FALSE
  /\ UNCHANGED
       <<completedSubgroups, logicalCursor, physicalCursor,
         checkpointCursor, resumedCursor, resumeValid, lifecycle,
         cachePresent, cacheLifecycle, cacheHit, negativeState,
         negativeAbsent, authorizationPublished>>

ProbeWithOverread ==
  /\ physicalCursor + 2 <= MaxCursor
  /\ physicalCursor' = physicalCursor + 2
  /\ logicalCursor' = physicalCursor + 1
  /\ checkpointCursor' =
       IF Mutation = 3 THEN physicalCursor + 2 ELSE physicalCursor + 1
  /\ resumeValid' = FALSE
  /\ UNCHANGED
       <<vectorState, completedSubgroups, vectorPublished, resumedCursor,
         lifecycle, cachePresent, cacheLifecycle, cacheHit, negativeState,
         negativeAbsent, authorizationPublished>>

ResumeCheckpoint ==
  /\ checkpointCursor > 0
  /\ resumedCursor' =
       IF Mutation = 4 THEN checkpointCursor - 1 ELSE checkpointCursor
  /\ resumeValid' = TRUE
  /\ UNCHANGED
       <<vectorState, completedSubgroups, vectorPublished, logicalCursor,
         physicalCursor, checkpointCursor, lifecycle, cachePresent,
         cacheLifecycle, cacheHit, negativeState, negativeAbsent,
         authorizationPublished>>

FillCache ==
  /\ cachePresent' = TRUE
  /\ cacheLifecycle' = lifecycle
  /\ cacheHit' = FALSE
  /\ UNCHANGED
       <<vectorState, completedSubgroups, vectorPublished, logicalCursor,
         physicalCursor, checkpointCursor, resumedCursor, resumeValid,
         lifecycle, negativeState, negativeAbsent,
         authorizationPublished>>

ExpireLifecycle ==
  /\ lifecycle' = lifecycle + 1
  /\ cachePresent' = IF Mutation = 5 THEN cachePresent ELSE FALSE
  /\ cacheLifecycle' = IF Mutation = 5 THEN cacheLifecycle ELSE lifecycle'
  /\ cacheHit' = FALSE
  /\ UNCHANGED
       <<vectorState, completedSubgroups, vectorPublished, logicalCursor,
         physicalCursor, checkpointCursor, resumedCursor, resumeValid,
         negativeState, negativeAbsent, authorizationPublished>>

LookupCache ==
  /\ cachePresent
  /\ cacheHit' = TRUE
  /\ UNCHANGED
       <<vectorState, completedSubgroups, vectorPublished, logicalCursor,
         physicalCursor, checkpointCursor, resumedCursor, resumeValid,
         lifecycle, cachePresent, cacheLifecycle, negativeState,
         negativeAbsent, authorizationPublished>>

CompleteNegativeAbsence ==
  /\ negativeState = NegativePending
  /\ negativeState' = NegativeComplete
  /\ negativeAbsent' = TRUE
  /\ authorizationPublished' = FALSE
  /\ UNCHANGED
       <<vectorState, completedSubgroups, vectorPublished, logicalCursor,
         physicalCursor, checkpointCursor, resumedCursor, resumeValid,
         lifecycle, cachePresent, cacheLifecycle, cacheHit>>

FailNegative ==
  /\ negativeState = NegativePending
  /\ negativeState' = NegativeFailed
  /\ negativeAbsent' = FALSE
  /\ authorizationPublished' = FALSE
  /\ UNCHANGED
       <<vectorState, completedSubgroups, vectorPublished, logicalCursor,
         physicalCursor, checkpointCursor, resumedCursor, resumeValid,
         lifecycle, cachePresent, cacheLifecycle, cacheHit>>

PublishNegativeAuthorization ==
  /\ (negativeState = NegativeComplete /\ negativeAbsent) \/ Mutation = 6
  /\ authorizationPublished' = TRUE
  /\ UNCHANGED
       <<vectorState, completedSubgroups, vectorPublished, logicalCursor,
         physicalCursor, checkpointCursor, resumedCursor, resumeValid,
         lifecycle, cachePresent, cacheLifecycle, cacheHit, negativeState,
         negativeAbsent>>

Next ==
  \/ StartVector
  \/ CompleteVectorSubgroup
  \/ PublishVector
  \/ CancelVector
  \/ ProbeWithOverread
  \/ ResumeCheckpoint
  \/ FillCache
  \/ ExpireLifecycle
  \/ LookupCache
  \/ CompleteNegativeAbsence
  \/ FailNegative
  \/ PublishNegativeAuthorization

Spec == Init /\ [][Next]_vars

=====================================================================

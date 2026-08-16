------------------------- MODULE ProgressCheckpoint -------------------------
EXTENDS Naturals

CONSTANTS
  MaximumProgress,
  PublishPartial,
  ComputeAfterControl,
  PublishOldEpoch,
  ReplaceWithOlder,
  RestoreWrongContext,
  ProgressAsAnswer

ASSUME
  /\ MaximumProgress > 1
  /\ PublishPartial \in BOOLEAN
  /\ ComputeAfterControl \in BOOLEAN
  /\ PublishOldEpoch \in BOOLEAN
  /\ ReplaceWithOlder \in BOOLEAN
  /\ RestoreWrongContext \in BOOLEAN
  /\ ProgressAsAnswer \in BOOLEAN

Contexts == {0, 1}
Active == 0
Canceled == 1
Deadline == 2
Controls == {Active, Canceled, Deadline}

VARIABLES
  requestContext,
  requestEpoch,
  semanticProgress,
  control,
  progressAtControl,
  epoch,
  cachePresent,
  cacheComplete,
  cacheContext,
  cacheProgress,
  cacheEpoch,
  greatestPublished,
  restored,
  restoredContext,
  restoredProgress,
  answerPublished

vars ==
  <<requestContext, requestEpoch, semanticProgress, control,
    progressAtControl, epoch, cachePresent, cacheComplete, cacheContext,
    cacheProgress, cacheEpoch, greatestPublished, restored,
    restoredContext, restoredProgress, answerPublished>>

TypeOK ==
  /\ requestContext \in Contexts
  /\ requestEpoch \in {0, 1}
  /\ semanticProgress \in 0..MaximumProgress
  /\ control \in Controls
  /\ progressAtControl \in 0..MaximumProgress
  /\ epoch \in {0, 1}
  /\ cachePresent \in BOOLEAN
  /\ cacheComplete \in BOOLEAN
  /\ cacheContext \in Contexts
  /\ cacheProgress \in 0..MaximumProgress
  /\ cacheEpoch \in {0, 1}
  /\ greatestPublished \in 0..MaximumProgress
  /\ restored \in BOOLEAN
  /\ restoredContext \in Contexts
  /\ restoredProgress \in 0..MaximumProgress
  /\ answerPublished \in BOOLEAN

ControlFreezesSemantics ==
  control = Active \/ semanticProgress = progressAtControl

VisibleCheckpointIsExact ==
  ~cachePresent \/
    /\ cacheComplete
    /\ cacheEpoch = epoch
    /\ cacheProgress = greatestPublished

RestoreIsExactContextFork ==
  ~restored \/ restoredContext = requestContext

ProgressIsNotAnswer ==
  ~answerPublished \/ restoredProgress = MaximumProgress

Safety ==
  /\ TypeOK
  /\ ControlFreezesSemantics
  /\ VisibleCheckpointIsExact
  /\ RestoreIsExactContextFork
  /\ ProgressIsNotAnswer

Init ==
  /\ requestContext = 0
  /\ requestEpoch = 0
  /\ semanticProgress = 0
  /\ control = Active
  /\ progressAtControl = 0
  /\ epoch = 0
  /\ cachePresent = FALSE
  /\ cacheComplete = FALSE
  /\ cacheContext = 0
  /\ cacheProgress = 0
  /\ cacheEpoch = 0
  /\ greatestPublished = 0
  /\ restored = FALSE
  /\ restoredContext = 0
  /\ restoredProgress = 0
  /\ answerPublished = FALSE

Compute ==
  /\ semanticProgress < MaximumProgress
  /\ control = Active \/ ComputeAfterControl
  /\ semanticProgress' = semanticProgress + 1
  /\ UNCHANGED
       <<requestContext, requestEpoch, control, progressAtControl, epoch,
         cachePresent, cacheComplete, cacheContext, cacheProgress,
         cacheEpoch, greatestPublished, restored, restoredContext,
         restoredProgress, answerPublished>>

SignalCancellation ==
  /\ control = Active
  /\ control' = Canceled
  /\ progressAtControl' = semanticProgress
  /\ UNCHANGED
       <<requestContext, requestEpoch, semanticProgress, epoch,
         cachePresent, cacheComplete, cacheContext, cacheProgress,
         cacheEpoch, greatestPublished, restored, restoredContext,
         restoredProgress, answerPublished>>

SignalDeadline ==
  /\ control = Active
  /\ control' = Deadline
  /\ progressAtControl' = semanticProgress
  /\ UNCHANGED
       <<requestContext, requestEpoch, semanticProgress, epoch,
         cachePresent, cacheComplete, cacheContext, cacheProgress,
         cacheEpoch, greatestPublished, restored, restoredContext,
         restoredProgress, answerPublished>>

Rotate ==
  /\ epoch = 0
  /\ epoch' = 1
  /\ cachePresent' = FALSE
  /\ cacheComplete' = FALSE
  /\ cacheProgress' = 0
  /\ cacheEpoch' = 1
  /\ greatestPublished' = 0
  /\ UNCHANGED
       <<requestContext, requestEpoch, semanticProgress, control,
         progressAtControl, cacheContext, restored, restoredContext,
         restoredProgress, answerPublished>>

Publish ==
  /\ semanticProgress > 0
  /\ requestEpoch = epoch \/ PublishOldEpoch
  /\ ~cachePresent \/ cacheContext = requestContext
  /\ \E captured \in 1..semanticProgress:
       /\ ~cachePresent \/ captured >= cacheProgress \/ ReplaceWithOlder
       /\ cachePresent' = TRUE
       /\ cacheComplete' = ~PublishPartial
       /\ cacheContext' = requestContext
       /\ cacheProgress' = captured
       /\ cacheEpoch' = requestEpoch
       /\ greatestPublished' =
            IF captured > greatestPublished
            THEN captured
            ELSE greatestPublished
  /\ UNCHANGED
       <<requestContext, requestEpoch, semanticProgress, control,
         progressAtControl, epoch, restored, restoredContext,
         restoredProgress, answerPublished>>

SwitchRequestContext ==
  /\ requestContext = 0
  /\ ~restored
  /\ requestContext' = 1
  /\ UNCHANGED
       <<requestEpoch, semanticProgress, control, progressAtControl, epoch,
         cachePresent, cacheComplete, cacheContext, cacheProgress,
         cacheEpoch, greatestPublished, restored, restoredContext,
         restoredProgress, answerPublished>>

Restore ==
  /\ ~restored
  /\ cachePresent
  /\ cacheComplete
  /\ cacheEpoch = epoch
  /\ cacheContext = requestContext \/ RestoreWrongContext
  /\ restored' = TRUE
  /\ restoredContext' = cacheContext
  /\ restoredProgress' = cacheProgress
  /\ UNCHANGED
       <<requestContext, requestEpoch, semanticProgress, control,
         progressAtControl, epoch, cachePresent, cacheComplete,
         cacheContext, cacheProgress, cacheEpoch, greatestPublished,
         answerPublished>>

PublishAnswer ==
  /\ restored
  /\ ~answerPublished
  /\ restoredProgress = MaximumProgress \/ ProgressAsAnswer
  /\ answerPublished' = TRUE
  /\ UNCHANGED
       <<requestContext, requestEpoch, semanticProgress, control,
         progressAtControl, epoch, cachePresent, cacheComplete,
         cacheContext, cacheProgress, cacheEpoch, greatestPublished,
         restored, restoredContext, restoredProgress>>

Next ==
  \/ Compute
  \/ SignalCancellation
  \/ SignalDeadline
  \/ Rotate
  \/ Publish
  \/ SwitchRequestContext
  \/ Restore
  \/ PublishAnswer

=============================================================================

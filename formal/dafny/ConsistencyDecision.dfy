module ConsistencyDecision {
  datatype SnapshotConsistencyMode =
    | MinimizeLatency
    | FullyConsistent
    | AtLeastAsFresh
    | AtExactSnapshot

  datatype PublicConsistencyInput =
    | ConsistencyOmitted
    | ConsistencyNil
    | ConsistencyMode(mode: SnapshotConsistencyMode)
    | ConsistencyFalse
    | ConsistencyMalformed

  datatype PublicConsistencyOutcome =
    | ConsistencyAccepted(mode: SnapshotConsistencyMode)
    | ConsistencyRejected

  function NormalizePublicConsistency(
    input: PublicConsistencyInput
  ): PublicConsistencyOutcome {
    match input
    case ConsistencyOmitted => ConsistencyAccepted(MinimizeLatency)
    case ConsistencyNil => ConsistencyAccepted(MinimizeLatency)
    case ConsistencyMode(mode) => ConsistencyAccepted(mode)
    case ConsistencyFalse => ConsistencyRejected
    case ConsistencyMalformed => ConsistencyRejected
  }

  lemma OnlyOmittedOrNilConsistencyDefaultsToMinimizeLatency(
    input: PublicConsistencyInput
  )
    ensures NormalizePublicConsistency(input) ==
            ConsistencyAccepted(MinimizeLatency) ==>
              input.ConsistencyOmitted? ||
              input.ConsistencyNil? ||
              (input.ConsistencyMode? && input.mode.MinimizeLatency?)
  {
  }

  lemma FalseConsistencyCannotSilentlyDefault()
    ensures NormalizePublicConsistency(ConsistencyFalse) ==
            ConsistencyRejected
  {
  }

  lemma MalformedConsistencyCannotBeAccepted()
    ensures NormalizePublicConsistency(ConsistencyMalformed) ==
            ConsistencyRejected
  {
  }

  lemma ExplicitConsistencyModeIsPreserved(
    mode: SnapshotConsistencyMode
  )
    ensures NormalizePublicConsistency(ConsistencyMode(mode)) ==
            ConsistencyAccepted(mode)
  {
  }

  datatype SelectionAction =
    | SelectCurrent
    | SelectAuthoritative
    | AuthenticateAndSelectAtLeast
    | AuthenticateAndSelectExact

  datatype ConsistencyError =
    | UnsupportedCapability
    | UnsupportedHeadBarrier
    | ExactSnapshotUnavailable
    | InvalidSelectedAdapter
    | IncomparableScope
    | HistoryDivergence

  datatype PlanOutcome =
    | Planned(action: SelectionAction)
    | PlanRejected(error: ConsistencyError)

  datatype SelectionKind =
    | CurrentSelection
    | AuthoritativeSelection
    | AtLeastSelection
    | ExactSelection

  datatype SelectionOutcome =
    | SelectionAccepted
    | SelectionRejected(error: ConsistencyError)

  datatype SuccessfulSelectionPath =
    | CapturedCurrentPath
    | SelectedCurrentPath
    | AuthoritativePath
    | AtLeastPath
    | ExactPath

  datatype SelectionWork = SelectionWork(
    capabilityObservations: nat,
    planDecisions: nat,
    authenticationAttempts: nat,
    backendSelectionCalls: nat,
    validationDecisions: nat,
    sourceScopeReads: nat,
    revisionValidationCalls: nat,
    nativeRevisionReads: nat,
    orderHintReads: nat,
    exactLocatorReads: nat
  )

  function SuccessfulSelectionWork(
    path: SuccessfulSelectionPath,
    issueResponseToken: bool
  ): SelectionWork {
    match path
    case CapturedCurrentPath =>
      SelectionWork(1, 1, 0, 0, 0, 0, 0, 0, 0, 0)
    case SelectedCurrentPath =>
      SelectionWork(1, 1, 0, 1, 1, 2 + if issueResponseToken then 1 else 0, 0, 1, 1, 1)
    case AuthoritativePath =>
      SelectionWork(1, 1, 0, 1, 1, 2 + if issueResponseToken then 1 else 0, 0, 1, 1, 1)
    case AtLeastPath =>
      SelectionWork(1, 1, 1, 1, 1, 3 + if issueResponseToken then 1 else 0, 1, 2, 2, 2)
    case ExactPath =>
      SelectionWork(1, 1, 1, 1, 1, 3 + if issueResponseToken then 1 else 0, 1, 2, 2, 2)
  }

  lemma SuccessfulSelectionLogicalWorkIsConstantBounded(
    path: SuccessfulSelectionPath,
    issueResponseToken: bool
  )
    ensures SuccessfulSelectionWork(path, issueResponseToken).capabilityObservations == 1
    ensures SuccessfulSelectionWork(path, issueResponseToken).planDecisions == 1
    ensures SuccessfulSelectionWork(path, issueResponseToken).authenticationAttempts <= 1
    ensures SuccessfulSelectionWork(path, issueResponseToken).backendSelectionCalls <= 1
    ensures SuccessfulSelectionWork(path, issueResponseToken).validationDecisions <= 1
    ensures SuccessfulSelectionWork(path, issueResponseToken).sourceScopeReads <= 4
    ensures SuccessfulSelectionWork(path, issueResponseToken).revisionValidationCalls <= 1
    ensures SuccessfulSelectionWork(path, issueResponseToken).nativeRevisionReads <= 2
    ensures SuccessfulSelectionWork(path, issueResponseToken).orderHintReads <= 2
    ensures SuccessfulSelectionWork(path, issueResponseToken).exactLocatorReads <= 2
  {
  }

  lemma CapturedCurrentNeedsNoPostSelectionDecision(
    issueResponseToken: bool
  )
    ensures SuccessfulSelectionWork(
              CapturedCurrentPath,
              issueResponseToken
            ).validationDecisions == 0
    ensures SuccessfulSelectionWork(
              CapturedCurrentPath,
              issueResponseToken
            ).backendSelectionCalls == 0
    ensures SuccessfulSelectionWork(
              CapturedCurrentPath,
              issueResponseToken
            ).sourceScopeReads == 0
  {
  }

  lemma SelectedCurrentHasOneSelectionAndValidation(
    issueResponseToken: bool
  )
    ensures SuccessfulSelectionWork(
              SelectedCurrentPath,
              issueResponseToken
            ).backendSelectionCalls == 1
    ensures SuccessfulSelectionWork(
              SelectedCurrentPath,
              issueResponseToken
            ).validationDecisions == 1
    ensures SuccessfulSelectionWork(
              SelectedCurrentPath,
              issueResponseToken
            ).sourceScopeReads <= 3
    ensures SuccessfulSelectionWork(
              SelectedCurrentPath,
              issueResponseToken
            ).nativeRevisionReads == 1
    ensures SuccessfulSelectionWork(
              SelectedCurrentPath,
              issueResponseToken
            ).orderHintReads == 1
    ensures SuccessfulSelectionWork(
              SelectedCurrentPath,
              issueResponseToken
            ).exactLocatorReads == 1
  {
  }

  function PlannedAction(mode: SnapshotConsistencyMode): SelectionAction {
    match mode
    case MinimizeLatency => SelectCurrent
    case FullyConsistent => SelectAuthoritative
    case AtLeastAsFresh => AuthenticateAndSelectAtLeast
    case AtExactSnapshot => AuthenticateAndSelectExact
  }

  function UnsupportedModeError(
    mode: SnapshotConsistencyMode
  ): ConsistencyError {
    match mode
    case MinimizeLatency => UnsupportedCapability
    case FullyConsistent => UnsupportedHeadBarrier
    case AtLeastAsFresh => UnsupportedHeadBarrier
    case AtExactSnapshot => ExactSnapshotUnavailable
  }

  function SelectionPlanDecision(
    mode: SnapshotConsistencyMode,
    capabilitySupported: bool
  ): PlanOutcome {
    if !capabilitySupported then
      PlanRejected(UnsupportedModeError(mode))
    else
      Planned(PlannedAction(mode))
  }

  method DecideSelectionPlan(
    mode: SnapshotConsistencyMode,
    capabilitySupported: bool
  ) returns (outcome: PlanOutcome)
    ensures outcome.Planned? <==> capabilitySupported
    ensures outcome.Planned? ==>
              outcome.action == PlannedAction(mode)
    ensures outcome.PlanRejected? && !capabilitySupported ==>
              outcome.error == UnsupportedModeError(mode)
  {
    return SelectionPlanDecision(
        mode,
        capabilitySupported
      );
  }

  lemma UnsupportedExactPlanIsExactSnapshotUnavailable()
    ensures SelectionPlanDecision(
              AtExactSnapshot,
              false
            ) == PlanRejected(ExactSnapshotUnavailable)
  {
  }

  predicate RequiresRevisionValidation(kind: SelectionKind) {
    kind.AtLeastSelection? || kind.ExactSelection?
  }

  function MissingSelectionError(
    kind: SelectionKind
  ): ConsistencyError {
    if kind.ExactSelection? then
      ExactSnapshotUnavailable
    else
      InvalidSelectedAdapter
  }

  function SelectedSnapshotDecision(
    kind: SelectionKind,
    selectionPresent: bool,
    selectedAdapter: bool,
    sameSourceScope: bool,
    revisionSatisfied: bool
  ): SelectionOutcome {
    if !selectionPresent then
      SelectionRejected(MissingSelectionError(kind))
    else if !selectedAdapter then
      SelectionRejected(InvalidSelectedAdapter)
    else if !sameSourceScope then
      SelectionRejected(IncomparableScope)
    else if RequiresRevisionValidation(kind) && !revisionSatisfied then
      SelectionRejected(HistoryDivergence)
    else
      SelectionAccepted
  }

  method ValidateSelectedSnapshot(
    kind: SelectionKind,
    selectionPresent: bool,
    selectedAdapter: bool,
    sameSourceScope: bool,
    revisionSatisfied: bool
  ) returns (outcome: SelectionOutcome)
    requires selectedAdapter ==> selectionPresent
    ensures outcome.SelectionAccepted? <==>
            selectionPresent &&
            selectedAdapter &&
            sameSourceScope &&
            (!RequiresRevisionValidation(kind) || revisionSatisfied)
    ensures outcome.SelectionRejected? && !selectionPresent ==>
              outcome.error == MissingSelectionError(kind)
    ensures outcome.SelectionRejected? &&
            selectionPresent &&
            !selectedAdapter ==>
              outcome.error == InvalidSelectedAdapter
    ensures outcome.SelectionRejected? &&
            selectedAdapter &&
            !sameSourceScope ==>
              outcome.error == IncomparableScope
    ensures outcome.SelectionRejected? &&
            selectedAdapter &&
            sameSourceScope &&
            RequiresRevisionValidation(kind) &&
            !revisionSatisfied ==>
              outcome.error == HistoryDivergence
  {
    return SelectedSnapshotDecision(
        kind,
        selectionPresent,
        selectedAdapter,
        sameSourceScope,
        revisionSatisfied
      );
  }

  lemma AtLeastAcceptanceRequiresRevisionFloor(
    selectedAdapter: bool,
    sameSourceScope: bool,
    revisionSatisfied: bool
  )
    ensures SelectedSnapshotDecision(
              AtLeastSelection,
              selectedAdapter,
              selectedAdapter,
              sameSourceScope,
              revisionSatisfied
            ).SelectionAccepted? ==>
              selectedAdapter &&
              sameSourceScope &&
              revisionSatisfied
  {
  }

  lemma ExactAcceptanceRequiresExactRevision(
    selectedAdapter: bool,
    sameSourceScope: bool,
    exactRevisionMatches: bool
  )
    ensures SelectedSnapshotDecision(
              ExactSelection,
              selectedAdapter,
              selectedAdapter,
              sameSourceScope,
              exactRevisionMatches
            ).SelectionAccepted? ==>
              selectedAdapter &&
              sameSourceScope &&
              exactRevisionMatches
  {
  }

  lemma PresentMalformedSelectionIsNotSnapshotAbsence(
    kind: SelectionKind,
    sameSourceScope: bool,
    revisionSatisfied: bool
  )
    ensures SelectedSnapshotDecision(
              kind,
              true,
              false,
              sameSourceScope,
              revisionSatisfied
            ) == SelectionRejected(InvalidSelectedAdapter)
  {
  }

  lemma CurrentSelectionDoesNotRequireRevisionValidation(
    selectedAdapter: bool,
    sameSourceScope: bool,
    revisionSatisfied: bool
  )
    requires selectedAdapter
    requires sameSourceScope
    ensures SelectedSnapshotDecision(
              CurrentSelection,
              selectedAdapter,
              selectedAdapter,
              sameSourceScope,
              revisionSatisfied
            ).SelectionAccepted?
  {
  }
}

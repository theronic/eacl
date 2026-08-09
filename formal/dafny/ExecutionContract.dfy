module ExecutionContract {
  datatype OperationKind =
    | PointAuthorization
    | LookupAuthorization
    | CountAuthorization

  datatype EvaluationMode =
    | Demand
    | CompleteDenotation

  datatype CertifiedEvaluatorRoute =
    | UndefinedEvaluator
    | AcyclicShortcutEvaluator
    | FixedPointEvaluator

  datatype PublicResultOrder =
    | NoPublicOrder
    | CertifiedAcyclicEidOrder
    | FixedPointLogicalOrder

  datatype CompletedArtifactContract = CompletedArtifactContract(
    version: nat,
    publicOrder: PublicResultOrder
  )

  function SelectedEvaluatorRoute(
    evaluation: EvaluationMode,
    certifiedRoute: CertifiedEvaluatorRoute
  ): CertifiedEvaluatorRoute
  {
    if evaluation.CompleteDenotation? &&
       certifiedRoute.AcyclicShortcutEvaluator?
    then FixedPointEvaluator
    else certifiedRoute
  }

  function SelectedPublicResultOrder(
    evaluation: EvaluationMode,
    certifiedRoute: CertifiedEvaluatorRoute
  ): PublicResultOrder
  {
    if certifiedRoute.UndefinedEvaluator?
    then NoPublicOrder
    else if certifiedRoute.AcyclicShortcutEvaluator?
      then CertifiedAcyclicEidOrder
      else FixedPointLogicalOrder
  }

  function CompletedArtifactContractFor(
    certifiedRoute: CertifiedEvaluatorRoute
  ): CompletedArtifactContract
    requires !certifiedRoute.UndefinedEvaluator?
  {
    CompletedArtifactContract(
      5,
      SelectedPublicResultOrder(CompleteDenotation, certifiedRoute)
    )
  }

  predicate StrictlyIncreasingPositiveEids(values: seq<nat>)
  {
    (forall i :: 0 <= i < |values| ==> values[i] > 0) &&
    (forall i, j :: 0 <= i < j < |values| ==> values[i] < values[j])
  }

  predicate UniquePositiveLogicalEids(values: seq<nat>)
  {
    (forall i :: 0 <= i < |values| ==> values[i] > 0) &&
    (forall i, j :: 0 <= i < j < |values| ==> values[i] != values[j])
  }

  predicate ValidCompletedArtifact(
    contract: CompletedArtifactContract,
    values: seq<nat>
  )
  {
    contract.version == 5 &&
    if contract.publicOrder.CertifiedAcyclicEidOrder?
    then StrictlyIncreasingPositiveEids(values)
    else contract.publicOrder.FixedPointLogicalOrder? &&
         UniquePositiveLogicalEids(values)
  }

  lemma DemandPreservesCertifiedEvaluatorRoute(
    certifiedRoute: CertifiedEvaluatorRoute
  )
    ensures SelectedEvaluatorRoute(Demand, certifiedRoute) == certifiedRoute
  {
  }

  lemma CompleteEvaluationUsesFixedPointForEveryDefinedRoot(
    certifiedRoute: CertifiedEvaluatorRoute
  )
    requires !certifiedRoute.UndefinedEvaluator?
    ensures SelectedEvaluatorRoute(
              CompleteDenotation,
              certifiedRoute
            ).FixedPointEvaluator?
  {
  }

  lemma UndefinedRootNeverSelectsAnEvaluator(
    evaluation: EvaluationMode
  )
    ensures SelectedEvaluatorRoute(
              evaluation,
              UndefinedEvaluator
            ).UndefinedEvaluator?
  {
  }

  lemma AcyclicCompletionPreservesCertifiedPublicOrder()
    ensures SelectedEvaluatorRoute(
              CompleteDenotation,
              AcyclicShortcutEvaluator
            ).FixedPointEvaluator?
    ensures SelectedPublicResultOrder(
              CompleteDenotation,
              AcyclicShortcutEvaluator
            ).CertifiedAcyclicEidOrder?
    ensures SelectedPublicResultOrder(
              Demand,
              AcyclicShortcutEvaluator
            ) == SelectedPublicResultOrder(
                   CompleteDenotation,
                   AcyclicShortcutEvaluator
                 )
    ensures CompletedArtifactContractFor(
              AcyclicShortcutEvaluator
            ).version == 5
    ensures CompletedArtifactContractFor(
              AcyclicShortcutEvaluator
            ).publicOrder.CertifiedAcyclicEidOrder?
  {
  }

  lemma ValidAcyclicCompletedArtifactIsStrictlyOrdered(values: seq<nat>)
    requires ValidCompletedArtifact(
               CompletedArtifactContractFor(AcyclicShortcutEvaluator),
               values
             )
    ensures StrictlyIncreasingPositiveEids(values)
  {
  }

  datatype DemandShape =
    | BooleanDemand(targetEid: int)
    | PageDemand(size: nat, boundaryOrdinal: nat)
    | BoundedCountDemand(limit: nat)
    | ExactCountDemand
    | CompleteDenotationDemand

  datatype TraversalLimits = TraversalLimits(
    maxDerivedGrants: nat,
    maxAdvancedDatoms: nat,
    maxQueuedWork: nat
  )

  predicate ValidTraversalLimits(limits: TraversalLimits) {
    0 < limits.maxDerivedGrants &&
    0 < limits.maxAdvancedDatoms &&
    0 < limits.maxQueuedWork
  }

  datatype CacheAttemptEnvelope = CacheAttemptEnvelope(
    evaluationReserveTicks: nat,
    maximumAtomicAttempts: nat
  )

  predicate ValidCacheAttemptEnvelope(envelope: CacheAttemptEnvelope) {
    0 < envelope.evaluationReserveTicks &&
    0 < envelope.maximumAtomicAttempts
  }

  datatype NormalizedExecutionRequest = NormalizedExecutionRequest(
    operation: OperationKind,
    evaluation: EvaluationMode,
    demand: DemandShape,
    configuredTimeoutTicks: nat,
    startedTick: nat,
    deadlineTick: nat,
    limits: TraversalLimits,
    cacheAttempt: CacheAttemptEnvelope
  )

  predicate ValidRequest(request: NormalizedExecutionRequest) {
    0 < request.configuredTimeoutTicks &&
    request.deadlineTick ==
    request.startedTick + request.configuredTimeoutTicks &&
    ValidTraversalLimits(request.limits) &&
    ValidCacheAttemptEnvelope(request.cacheAttempt) &&
    (request.evaluation.CompleteDenotation? <==>
     request.demand.CompleteDenotationDemand?) &&
    (request.evaluation.Demand? ==>
       match request.operation
       case PointAuthorization => request.demand.BooleanDemand?
       case LookupAuthorization =>
         request.demand.PageDemand? && 0 < request.demand.size
       case CountAuthorization =>
         request.demand.BoundedCountDemand? ||
         request.demand.ExactCountDemand?)
  }

  datatype ExecutionBoundary =
    | QuantumBoundary
    | AdapterCommandBoundary
    | AdapterResponseBoundary
    | PrivateContinuationLookupBoundary
    | PrivateContinuationPublicationBoundary
    | PrivatePageLookupBoundary
    | PrivatePagePublicationBoundary

  datatype StoppingReason =
    | TargetDerived
    | DemandSentinel
    | GraphExhausted
    | DeadlineExceeded

  datatype ExecutionDecision =
    | ContinueExecution
    | StopExecution(reason: StoppingReason)

  function ForwardedTraversalLimits(
    request: NormalizedExecutionRequest,
    boundary: ExecutionBoundary
  ): TraversalLimits
    requires ValidRequest(request)
  {
    request.limits
  }

  lemma EveryExecutionBoundaryForwardsIdenticalTraversalLimits(
    request: NormalizedExecutionRequest,
    boundary: ExecutionBoundary
  )
    requires ValidRequest(request)
    ensures ForwardedTraversalLimits(request, boundary) == request.limits
  {
  }

  function DecideExecutionBoundary(
    request: NormalizedExecutionRequest,
    boundary: ExecutionBoundary,
    nowTick: nat,
    distinctResults: nat,
    targetDerived: bool,
    graphExhausted: bool
  ): ExecutionDecision
    requires ValidRequest(request)
  {
    if request.deadlineTick <= nowTick then
      StopExecution(DeadlineExceeded)
    else if request.evaluation.CompleteDenotation? then
      if graphExhausted
      then StopExecution(GraphExhausted)
      else ContinueExecution
    else
      match request.demand
      case BooleanDemand(_) =>
        if targetDerived then StopExecution(TargetDerived)
        else if graphExhausted then StopExecution(GraphExhausted)
        else ContinueExecution
      case PageDemand(size, _) =>
        if size < distinctResults then StopExecution(DemandSentinel)
        else if graphExhausted then StopExecution(GraphExhausted)
        else ContinueExecution
      case BoundedCountDemand(limit) =>
        if limit < distinctResults then StopExecution(DemandSentinel)
        else if graphExhausted then StopExecution(GraphExhausted)
        else ContinueExecution
      case ExactCountDemand =>
        if graphExhausted
        then StopExecution(GraphExhausted)
        else ContinueExecution
      case CompleteDenotationDemand =>
        if graphExhausted
        then StopExecution(GraphExhausted)
        else ContinueExecution
  }

  lemma DeadlineDominatesEveryExecutionBoundary(
    request: NormalizedExecutionRequest,
    boundary: ExecutionBoundary,
    nowTick: nat,
    distinctResults: nat,
    targetDerived: bool,
    graphExhausted: bool
  )
    requires ValidRequest(request)
    requires request.deadlineTick <= nowTick
    ensures DecideExecutionBoundary(
              request,
              boundary,
              nowTick,
              distinctResults,
              targetDerived,
              graphExhausted
            ) == StopExecution(DeadlineExceeded)
  {
  }

  lemma DemandPageStopsAtExactlyOneLookahead(
    request: NormalizedExecutionRequest,
    boundary: ExecutionBoundary,
    nowTick: nat,
    distinctResults: nat,
    targetDerived: bool,
    graphExhausted: bool
  )
    requires ValidRequest(request)
    requires nowTick < request.deadlineTick
    requires request.evaluation.Demand?
    requires request.demand.PageDemand?
    requires request.demand.size < distinctResults
    ensures DecideExecutionBoundary(
              request,
              boundary,
              nowTick,
              distinctResults,
              targetDerived,
              graphExhausted
            ) == StopExecution(DemandSentinel)
  {
  }

  lemma BoundedCountStopsAtLimitPlusOne(
    request: NormalizedExecutionRequest,
    boundary: ExecutionBoundary,
    nowTick: nat,
    distinctResults: nat,
    targetDerived: bool,
    graphExhausted: bool
  )
    requires ValidRequest(request)
    requires nowTick < request.deadlineTick
    requires request.evaluation.Demand?
    requires request.demand.BoundedCountDemand?
    requires request.demand.limit < distinctResults
    ensures DecideExecutionBoundary(
              request,
              boundary,
              nowTick,
              distinctResults,
              targetDerived,
              graphExhausted
            ) == StopExecution(DemandSentinel)
  {
  }

  lemma CompleteEvaluationIgnoresDemandSentinels(
    request: NormalizedExecutionRequest,
    boundary: ExecutionBoundary,
    nowTick: nat,
    distinctResults: nat,
    targetDerived: bool
  )
    requires ValidRequest(request)
    requires nowTick < request.deadlineTick
    requires request.evaluation.CompleteDenotation?
    ensures DecideExecutionBoundary(
              request,
              boundary,
              nowTick,
              distinctResults,
              targetDerived,
              false
            ) == ContinueExecution
  {
  }

  lemma PositivePointDemandStopsWhenTargetIsDerived(
    request: NormalizedExecutionRequest,
    boundary: ExecutionBoundary,
    nowTick: nat,
    distinctResults: nat
  )
    requires ValidRequest(request)
    requires nowTick < request.deadlineTick
    requires request.evaluation.Demand?
    requires request.demand.BooleanDemand?
    ensures DecideExecutionBoundary(
              request,
              boundary,
              nowTick,
              distinctResults,
              true,
              false
            ) == StopExecution(TargetDerived)
  {
  }
}

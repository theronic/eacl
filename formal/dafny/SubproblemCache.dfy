include "RecursiveEngine.dfy"

module SubproblemCache {
  import Semantics
  import AcyclicEngine
  import RecursiveEngine

  datatype Direction = Ascending | Descending

  datatype ProjectionKey = ProjectionKey(
    version: nat,
    operation: string,
    direction: Direction,
    subjectType: string,
    subjectId: int,
    relationId: int,
    resourceType: string,
    resourceId: int,
    hasBound: bool,
    bound: int,
    inclusive: bool,
    chunkWidth: nat
  )

  lemma EqualProjectionKeysSeparateEveryField(
    left: ProjectionKey,
    right: ProjectionKey
  )
    requires left == right
    ensures left.version == right.version
    ensures left.operation == right.operation
    ensures left.direction == right.direction
    ensures left.subjectType == right.subjectType
    ensures left.subjectId == right.subjectId
    ensures left.relationId == right.relationId
    ensures left.resourceType == right.resourceType
    ensures left.resourceId == right.resourceId
    ensures left.hasBound == right.hasBound
    ensures left.bound == right.bound
    ensures left.inclusive == right.inclusive
    ensures left.chunkWidth == right.chunkWidth
  {
  }

  datatype DirectProbeKey = DirectProbeKey(
    version: nat,
    subjectType: string,
    subjectId: int,
    relationId: int,
    resourceType: string,
    resourceId: int
  )

  lemma EqualDirectProbeKeysSeparateEveryField(
    left: DirectProbeKey,
    right: DirectProbeKey
  )
    requires left == right
    ensures left.version == right.version
    ensures left.subjectType == right.subjectType
    ensures left.subjectId == right.subjectId
    ensures left.relationId == right.relationId
    ensures left.resourceType == right.resourceType
    ensures left.resourceId == right.resourceId
  {
  }

  datatype AcyclicBooleanKey = AcyclicBooleanKey(
    version: nat,
    subjectType: string,
    subjectId: int,
    permissionName: string,
    resourceType: string,
    resourceId: int
  )

  lemma EqualAcyclicBooleanKeysSeparateEveryField(
    left: AcyclicBooleanKey,
    right: AcyclicBooleanKey
  )
    requires left == right
    ensures left.version == right.version
    ensures left.subjectType == right.subjectType
    ensures left.subjectId == right.subjectId
    ensures left.permissionName == right.permissionName
    ensures left.resourceType == right.resourceType
    ensures left.resourceId == right.resourceId
  {
  }

  datatype AcyclicProjectionKey = AcyclicProjectionKey(
    version: nat,
    operation: string,
    direction: Direction,
    anchorType: string,
    anchorId: int,
    permissionResourceType: string,
    permissionName: string,
    resultType: string,
    hasBound: bool,
    bound: int,
    inclusive: bool,
    chunkWidth: nat
  )

  lemma EqualAcyclicProjectionKeysSeparateEveryField(
    left: AcyclicProjectionKey,
    right: AcyclicProjectionKey
  )
    requires left == right
    ensures left.version == right.version
    ensures left.operation == right.operation
    ensures left.direction == right.direction
    ensures left.anchorType == right.anchorType
    ensures left.anchorId == right.anchorId
    ensures left.permissionResourceType == right.permissionResourceType
    ensures left.permissionName == right.permissionName
    ensures left.resultType == right.resultType
    ensures left.hasBound == right.hasBound
    ensures left.bound == right.bound
    ensures left.inclusive == right.inclusive
    ensures left.chunkWidth == right.chunkWidth
  {
  }

  datatype RecursiveDenotationKey = RecursiveDenotationKey(
    version: nat,
    recursiveVersion: nat,
    direction: Direction,
    rootComponent: seq<Semantics.PermissionNode>,
    root: Semantics.PermissionNode,
    anchorType: string,
    anchorId: int,
    resultType: string,
    maxDerivedGrants: nat,
    maxAdvancedDatoms: nat,
    maxQueuedWork: nat
  )

  lemma EqualRecursiveDenotationKeysSeparateEveryField(
    left: RecursiveDenotationKey,
    right: RecursiveDenotationKey
  )
    requires left == right
    ensures left.version == right.version
    ensures left.recursiveVersion == right.recursiveVersion
    ensures left.direction == right.direction
    ensures left.rootComponent == right.rootComponent
    ensures left.root == right.root
    ensures left.anchorType == right.anchorType
    ensures left.anchorId == right.anchorId
    ensures left.resultType == right.resultType
    ensures left.maxDerivedGrants == right.maxDerivedGrants
    ensures left.maxAdvancedDatoms == right.maxAdvancedDatoms
    ensures left.maxQueuedWork == right.maxQueuedWork
  {
  }

  datatype RecursiveCursorKey = RecursiveCursorKey(
    engineVersion: nat,
    traversal: string,
    resultKind: string,
    ordinal: nat,
    resultType: string,
    resultId: int
  )

  lemma EqualRecursiveCursorKeysSeparateEveryField(
    left: RecursiveCursorKey,
    right: RecursiveCursorKey
  )
    requires left == right
    ensures left.engineVersion == right.engineVersion
    ensures left.traversal == right.traversal
    ensures left.resultKind == right.resultKind
    ensures left.ordinal == right.ordinal
    ensures left.resultType == right.resultType
    ensures left.resultId == right.resultId
  {
  }

  datatype RecursivePageKey = RecursivePageKey(
    version: nat,
    traversal: string,
    resultKind: string,
    pageDirection: Direction,
    hasBound: bool,
    bound: RecursiveCursorKey,
    size: nat
  )

  lemma EqualRecursivePageKeysSeparateEveryField(
    left: RecursivePageKey,
    right: RecursivePageKey
  )
    requires left == right
    ensures left.version == right.version
    ensures left.traversal == right.traversal
    ensures left.resultKind == right.resultKind
    ensures left.pageDirection == right.pageDirection
    ensures left.hasBound == right.hasBound
    ensures left.bound == right.bound
    ensures left.size == right.size
  {
  }

  datatype ExactSemanticKey =
    | ProjectionSemanticKey(projection: ProjectionKey)
    | DirectProbeSemanticKey(probe: DirectProbeKey)
    | AcyclicBooleanSemanticKey(acyclicBoolean: AcyclicBooleanKey)
    | AcyclicProjectionSemanticKey(acyclicProjection: AcyclicProjectionKey)
    | RecursiveDenotationSemanticKey(recursive: RecursiveDenotationKey)
    | RecursiveContinuationSemanticKey(cursor: RecursiveCursorKey)
    | RecursivePageSemanticKey(page: RecursivePageKey)

  datatype ExactCacheAddress = ExactCacheAddress(
    generation: nat,
    tier: string,
    semanticKey: ExactSemanticKey
  )

  lemma EqualExactCacheAddressesSeparateGenerationTierAndSemantics(
    left: ExactCacheAddress,
    right: ExactCacheAddress
  )
    requires left == right
    ensures left.generation == right.generation
    ensures left.tier == right.tier
    ensures left.semanticKey == right.semanticKey
  {
  }

  function RecursiveSelfLookup(
    resolving: set<ExactCacheAddress>,
    lifecycle: nat,
    tier: string,
    semanticKey: ExactSemanticKey
  ): bool {
    ExactCacheAddress(lifecycle, tier, semanticKey) in resolving
  }

  lemma LifecycleQualifiedRecursiveSelfIsDetected(
    resolving: set<ExactCacheAddress>,
    lifecycle: nat,
    tier: string,
    semanticKey: ExactSemanticKey
  )
    requires ExactCacheAddress(
               lifecycle,
               tier,
               semanticKey
             ) in resolving
    ensures RecursiveSelfLookup(
              resolving,
              lifecycle,
              tier,
              semanticKey
            )
  {
  }

  lemma DifferentLifecycleIsNotRecursiveSelf(
    activeLifecycle: nat,
    otherLifecycle: nat,
    tier: string,
    semanticKey: ExactSemanticKey
  )
    requires activeLifecycle != otherLifecycle
    ensures !RecursiveSelfLookup(
              {ExactCacheAddress(
                 activeLifecycle,
                 tier,
                 semanticKey
               )},
              otherLifecycle,
              tier,
              semanticKey
            )
  {
  }

  datatype ExactAddressSelection = ExactAddressSelection(
    capturedLifecycle: nat,
    recursiveAddress: ExactCacheAddress,
    flightAddress: ExactCacheAddress
  )

  function LinearizedExactAddressSelection(
    lifecycle: nat,
    tier: string,
    semanticKey: ExactSemanticKey
  ): ExactAddressSelection {
    var address := ExactCacheAddress(lifecycle, tier, semanticKey);
    ExactAddressSelection(lifecycle, address, address)
  }

  predicate ExactAddressSelectionConsistent(
    selection: ExactAddressSelection
  ) {
    selection.recursiveAddress == selection.flightAddress &&
    selection.recursiveAddress.generation ==
    selection.capturedLifecycle
  }

  lemma LinearizedResolutionSelectionUsesOneLifecycle(
    lifecycle: nat,
    tier: string,
    semanticKey: ExactSemanticKey
  )
    ensures ExactAddressSelectionConsistent(
              LinearizedExactAddressSelection(
                lifecycle,
                tier,
                semanticKey
              )
            )
  {
  }

  lemma SplitLifecycleSelectionCannotBeConsistent(
    recursiveLifecycle: nat,
    flightLifecycle: nat,
    tier: string,
    semanticKey: ExactSemanticKey
  )
    requires recursiveLifecycle != flightLifecycle
    ensures ExactCacheAddress(
              recursiveLifecycle,
              tier,
              semanticKey
            ) !=
            ExactCacheAddress(
              flightLifecycle,
              tier,
              semanticKey
            )
  {
  }

  function MatchesRelationDependency(
    relationship: Semantics.Relationship,
    dependency: Semantics.RelationNode
  ): bool {
    relationship.resource.typeName == dependency.resourceType &&
    relationship.relationName == dependency.relationName &&
    relationship.subject.typeName == dependency.subjectType
  }

  function RelationSlice(
    relationships: seq<Semantics.Relationship>,
    dependency: Semantics.RelationNode
  ): seq<Semantics.Relationship>
    decreases |relationships|
  {
    if |relationships| == 0 then
      []
    else
      (if MatchesRelationDependency(relationships[0], dependency)
       then [relationships[0]]
       else []) +
      RelationSlice(relationships[1..], dependency)
  }

  datatype MutationDatomIdentity = MutationDatomIdentity(
    transaction: nat,
    mutationValue: string
  )

  lemma SameTransactionDifferentMutationValuesSeparateProofs(
    transaction: nat,
    leftMutationValue: string,
    rightMutationValue: string
  )
    requires leftMutationValue != rightMutationValue
    ensures MutationDatomIdentity(transaction, leftMutationValue) !=
            MutationDatomIdentity(transaction, rightMutationValue)
  {
  }

  ghost predicate ManagedRelationStampContract(
    previous: seq<Semantics.Relationship>,
    selected: seq<Semantics.Relationship>,
    dependency: Semantics.RelationNode,
    previousStamp: MutationDatomIdentity,
    selectedStamp: MutationDatomIdentity
  ) {
    previousStamp == selectedStamp ==>
      RelationSlice(previous, dependency) ==
      RelationSlice(selected, dependency)
  }

  lemma UnchangedManagedRelationStampPreservesProjection(
    previous: seq<Semantics.Relationship>,
    selected: seq<Semantics.Relationship>,
    dependency: Semantics.RelationNode,
    previousStamp: MutationDatomIdentity,
    selectedStamp: MutationDatomIdentity,
    cachedProjection: seq<Semantics.Relationship>
  )
    requires ManagedRelationStampContract(
               previous,
               selected,
               dependency,
               previousStamp,
               selectedStamp
             )
    requires previousStamp == selectedStamp
    requires cachedProjection == RelationSlice(previous, dependency)
    ensures cachedProjection == RelationSlice(selected, dependency)
  {
  }

  lemma ChangedManagedRelationSliceRequiresChangedStamp(
    previous: seq<Semantics.Relationship>,
    selected: seq<Semantics.Relationship>,
    dependency: Semantics.RelationNode,
    previousStamp: MutationDatomIdentity,
    selectedStamp: MutationDatomIdentity
  )
    requires ManagedRelationStampContract(
               previous,
               selected,
               dependency,
               previousStamp,
               selectedStamp
             )
    requires RelationSlice(previous, dependency) !=
             RelationSlice(selected, dependency)
    ensures previousStamp != selectedStamp
  {
  }

  datatype ManagedProjectionKey = ManagedProjectionKey(
    version: nat,
    source: string,
    schemaStamp: MutationDatomIdentity,
    dependency: Semantics.RelationNode,
    dependencyStamp: MutationDatomIdentity,
    projection: ProjectionKey
  )

  lemma EqualManagedProjectionKeysSeparateProofAndProjection(
    left: ManagedProjectionKey,
    right: ManagedProjectionKey
  )
    requires left == right
    ensures left.version == right.version
    ensures left.source == right.source
    ensures left.schemaStamp == right.schemaStamp
    ensures left.dependency == right.dependency
    ensures left.dependencyStamp == right.dependencyStamp
    ensures left.projection == right.projection
  {
  }

  datatype RelationProofAtom = RelationProofAtom(
    dependency: Semantics.RelationNode,
    stamp: MutationDatomIdentity
  )

  datatype ComposedProof =
    | ProofUnavailable
    | CompleteProof(atoms: seq<RelationProofAtom>)

  predicate UniqueProofDependencies(atoms: seq<RelationProofAtom>) {
    forall i, j | 0 <= i < j < |atoms| ::
      atoms[i].dependency != atoms[j].dependency
  }

  function ComposeProof(
    atoms: seq<RelationProofAtom>,
    maximumAtoms: nat
  ): ComposedProof {
    if |atoms| <= maximumAtoms && UniqueProofDependencies(atoms)
    then CompleteProof(atoms)
    else ProofUnavailable
  }

  lemma CompleteComposedProofRespectsConfiguredBound(
    atoms: seq<RelationProofAtom>,
    maximumAtoms: nat
  )
    ensures ComposeProof(atoms, maximumAtoms).CompleteProof? ==>
              |atoms| <= maximumAtoms
    ensures ComposeProof(atoms, maximumAtoms).CompleteProof? ==>
              UniqueProofDependencies(atoms)
  {
  }

  function ProofValidationOperations(
    atoms: seq<RelationProofAtom>,
    maximumAtoms: nat
  ): nat {
    if maximumAtoms < |atoms| then 1 else |atoms|
  }

  lemma ProofValidationCostIsConfiguredBounded(
    atoms: seq<RelationProofAtom>,
    maximumAtoms: nat
  )
    ensures ProofValidationOperations(atoms, maximumAtoms) <=
            maximumAtoms + 1
  {
  }

  predicate EqualProofIdentities(
    previous: seq<RelationProofAtom>,
    selected: seq<RelationProofAtom>
  ) {
    |previous| == |selected| &&
    forall i | 0 <= i < |previous| ::
      previous[i] == selected[i]
  }

  predicate CompleteDependencyFrame(
    previousRelationships: seq<Semantics.Relationship>,
    selectedRelationships: seq<Semantics.Relationship>,
    atoms: seq<RelationProofAtom>
  ) {
    forall atom <- atoms ::
      RelationSlice(previousRelationships, atom.dependency) ==
      RelationSlice(selectedRelationships, atom.dependency)
  }

  ghost predicate ManagedProofAtomsContract(
    previousRelationships: seq<Semantics.Relationship>,
    selectedRelationships: seq<Semantics.Relationship>,
    previous: seq<RelationProofAtom>,
    selected: seq<RelationProofAtom>
  ) {
    |previous| == |selected| &&
    forall i | 0 <= i < |previous| ::
      previous[i].dependency == selected[i].dependency &&
      ManagedRelationStampContract(
        previousRelationships,
        selectedRelationships,
        previous[i].dependency,
        previous[i].stamp,
        selected[i].stamp
      )
  }

  lemma EqualBoundedProofsEstablishCompleteDependencyFrame(
    previousRelationships: seq<Semantics.Relationship>,
    selectedRelationships: seq<Semantics.Relationship>,
    previous: seq<RelationProofAtom>,
    selected: seq<RelationProofAtom>,
    maximumAtoms: nat
  )
    requires ComposeProof(previous, maximumAtoms).CompleteProof?
    requires ComposeProof(selected, maximumAtoms).CompleteProof?
    requires EqualProofIdentities(previous, selected)
    requires ManagedProofAtomsContract(
               previousRelationships,
               selectedRelationships,
               previous,
               selected
             )
    ensures CompleteDependencyFrame(
              previousRelationships,
              selectedRelationships,
              previous
            )
  {
    forall atom <- previous
      ensures RelationSlice(previousRelationships, atom.dependency) ==
              RelationSlice(selectedRelationships, atom.dependency)
    {
      var i :| 0 <= i < |previous| && previous[i] == atom;
      assert previous[i] == selected[i];
    }
  }

  ghost predicate DerivedDenotationFrameContract<T>(
    previousRelationships: seq<Semantics.Relationship>,
    selectedRelationships: seq<Semantics.Relationship>,
    dependencies: seq<RelationProofAtom>,
    previousValue: T,
    selectedValue: T
  ) {
    (CompleteDependencyFrame(
       previousRelationships,
       selectedRelationships,
       dependencies
     )) ==> previousValue == selectedValue
  }

  lemma EqualBoundedProofsPreserveDerivedDenotation<T>(
    previousRelationships: seq<Semantics.Relationship>,
    selectedRelationships: seq<Semantics.Relationship>,
    previous: seq<RelationProofAtom>,
    selected: seq<RelationProofAtom>,
    maximumAtoms: nat,
    previousValue: T,
    selectedValue: T
  )
    requires ComposeProof(previous, maximumAtoms).CompleteProof?
    requires ComposeProof(selected, maximumAtoms).CompleteProof?
    requires EqualProofIdentities(previous, selected)
    requires ManagedProofAtomsContract(
               previousRelationships,
               selectedRelationships,
               previous,
               selected
             )
    requires DerivedDenotationFrameContract(
               previousRelationships,
               selectedRelationships,
               previous,
               previousValue,
               selectedValue
             )
    ensures previousValue == selectedValue
  {
    EqualBoundedProofsEstablishCompleteDependencyFrame(
      previousRelationships,
      selectedRelationships,
      previous,
      selected,
      maximumAtoms
    );
  }

  datatype ManagedDenotationKey = ManagedDenotationKey(
    version: nat,
    source: string,
    schemaStamp: MutationDatomIdentity,
    proof: seq<RelationProofAtom>,
    tier: string,
    semanticKey: ExactSemanticKey
  )

  lemma EqualManagedDenotationKeysSeparateEveryInput(
    left: ManagedDenotationKey,
    right: ManagedDenotationKey
  )
    requires left == right
    ensures left.version == right.version
    ensures left.source == right.source
    ensures left.schemaStamp == right.schemaStamp
    ensures left.proof == right.proof
    ensures left.tier == right.tier
    ensures left.semanticKey == right.semanticKey
  {
  }

  function Minimum(left: nat, right: nat): nat {
    if left < right then left else right
  }

  function ProjectionChunk(
    projection: seq<int>,
    start: nat,
    width: nat
  ): seq<int>
    requires start <= |projection|
  {
    projection[start..Minimum(start + width, |projection|)]
  }

  lemma ProjectionChunkIsExactSlice(
    projection: seq<int>,
    start: nat,
    width: nat
  )
    requires start <= |projection|
    ensures ProjectionChunk(projection, start, width) ==
            projection[
            start..
            Minimum(start + width, |projection|)
            ]
    ensures |ProjectionChunk(projection, start, width)| <= width
  {
  }

  lemma AdjacentProjectionChunksConcatenateExactly(
    projection: seq<int>,
    start: nat,
    width: nat
  )
    requires 0 < width
    requires start <= |projection|
    requires start + width <= |projection|
    ensures ProjectionChunk(projection, start, width) +
            projection[start + width..] ==
            projection[start..]
  {
  }

  datatype ExactCandidate<T> =
    | ExactMissing
    | ExactComputing
    | ExactComplete(value: T)
    | ExactFailed

  datatype ExactResolution<T> =
    | ExactMiss
    | ExactHit(value: T)

  datatype CandidateState =
    | CandidateMissing
    | CandidateComputing
    | CandidateComplete
    | CandidateFailed

  datatype LookupAction =
    | BypassRecursiveSelf
    | StartComputation
    | JoinComputation
    | UseCompletedValue

  function DecideLookup(
    recursiveSelf: bool,
    candidate: CandidateState
  ): LookupAction {
    if recursiveSelf then
      BypassRecursiveSelf
    else
      match candidate
      case CandidateMissing => StartComputation
      case CandidateComputing => JoinComputation
      case CandidateComplete => UseCompletedValue
      case CandidateFailed => StartComputation
  }

  function LifecycleStableCandidate(
    represented: CandidateState,
    registeredFlight: bool
  ): CandidateState {
    if represented.CandidateComplete? then
      CandidateComplete
    else if registeredFlight || represented.CandidateComputing? then
      CandidateComputing
    else
      CandidateMissing
  }

  lemma RegisteredUnrepresentedFlightIsJoined(
    recursiveSelf: bool
  )
    requires !recursiveSelf
    ensures DecideLookup(
              recursiveSelf,
              LifecycleStableCandidate(
                CandidateMissing,
                true
              )
            ).JoinComputation?
  {
  }

  lemma MissingEntryAndFlightStartsComputation(
    recursiveSelf: bool
  )
    requires !recursiveSelf
    ensures DecideLookup(
              recursiveSelf,
              LifecycleStableCandidate(
                CandidateMissing,
                false
              )
            ).StartComputation?
  {
  }

  function FlightInstallationsForLookup(action: LookupAction): nat {
    if action.StartComputation? then 1 else 0
  }

  lemma RecursiveBypassDecisionPrecedesAndSuppressesFlightInstallation(
    candidate: CandidateState
  )
    ensures FlightInstallationsForLookup(
              DecideLookup(true, candidate)
            ) == 0
  {
  }

  datatype AdmissionAction =
    | JoinExisting
    | AdmitComputation
    | ComputeWithoutAdmission

  function DecideAdmission(
    candidatePresent: bool,
    representedCandidates: nat,
    maximumCandidates: nat
  ): AdmissionAction {
    if candidatePresent then
      JoinExisting
    else if representedCandidates < maximumCandidates then
      AdmitComputation
    else
      ComputeWithoutAdmission
  }

  datatype PublicationAction =
    | RetainPublication
    | DropPublication

  function DecidePublication(
    ticketCurrent: bool,
    complete: bool,
    valid: bool,
    weight: nat,
    budget: nat
  ): PublicationAction {
    if ticketCurrent &&
       complete &&
       valid &&
       0 < weight <= budget
    then
      RetainPublication
    else
      DropPublication
  }

  lemma CompletedCandidatesAreTheOnlyLookupHits(
    recursiveSelf: bool,
    candidate: CandidateState
  )
    ensures DecideLookup(
              recursiveSelf,
              candidate
            ).UseCompletedValue? ==>
              !recursiveSelf && candidate.CandidateComplete?
  {
  }

  lemma AdmissionRespectsInflightLimit(
    candidatePresent: bool,
    representedCandidates: nat,
    maximumCandidates: nat
  )
    ensures DecideAdmission(
              candidatePresent,
              representedCandidates,
              maximumCandidates
            ).AdmitComputation? ==>
              !candidatePresent &&
              representedCandidates < maximumCandidates
  {
  }

  lemma RetainedPublicationIsCurrentCompleteValidAndBounded(
    ticketCurrent: bool,
    complete: bool,
    valid: bool,
    weight: nat,
    budget: nat
  )
    ensures DecidePublication(
              ticketCurrent,
              complete,
              valid,
              weight,
              budget
            ).RetainPublication? ==>
              ticketCurrent &&
              complete &&
              valid &&
              0 < weight <= budget
  {
  }

  function ResolveExact<T>(
    sameGeneration: bool,
    candidate: ExactCandidate<T>
  ): ExactResolution<T> {
    if !sameGeneration
    then ExactMiss
    else
      match candidate
      case ExactComplete(value) => ExactHit(value)
      case _ => ExactMiss
  }

  lemma IncompleteExactCandidatesCannotHit<T>(
    sameGeneration: bool,
    candidate: ExactCandidate<T>
  )
    requires !candidate.ExactComplete?
    ensures ResolveExact(sameGeneration, candidate).ExactMiss?
  {
  }

  lemma DifferentGenerationCannotHit<T>(
    candidate: ExactCandidate<T>
  )
    ensures ResolveExact(false, candidate).ExactMiss?
  {
  }

  ghost predicate ExactCandidateRefines<T>(
    candidate: ExactCandidate<T>,
    recomputed: T
  ) {
    candidate.ExactComplete? ==> candidate.value == recomputed
  }

  lemma ExactHitRefinesRecomputation<T>(
    sameGeneration: bool,
    candidate: ExactCandidate<T>,
    recomputed: T
  )
    requires ExactCandidateRefines(candidate, recomputed)
    ensures ResolveExact(
              sameGeneration,
              candidate
            ).ExactHit? ==>
              ResolveExact(
                sameGeneration,
                candidate
              ).value == recomputed
  {
  }

  function StructuralValidationCalls<T>(
    candidate: ExactCandidate<T>
  ): nat {
    if candidate.ExactComplete? then 0 else 1
  }

  function WeightRecomputationCalls<T>(
    candidate: ExactCandidate<T>
  ): nat {
    if candidate.ExactComplete? then 0 else 1
  }

  lemma CompleteExactHitSkipsValueDependentCallbacks<T>(
    value: T
  )
    ensures StructuralValidationCalls(ExactComplete(value)) == 0
    ensures WeightRecomputationCalls(ExactComplete(value)) == 0
  {
  }

  lemma CandidateCallbackCostIsConstant<T>(
    candidate: ExactCandidate<T>
  )
    ensures StructuralValidationCalls(candidate) <= 1
    ensures WeightRecomputationCalls(candidate) <= 1
  {
  }

  function AtomicManagedProofReads(): nat {
    1
  }

  lemma AtomicManagedProofReadCostIsGraphSizeIndependent(
    graphRelationships: nat,
    graphSchemaNodes: nat
  )
    ensures AtomicManagedProofReads() == 1
  {
  }

  ghost predicate AcyclicRoot(
    root: Semantics.PermissionNode,
    definitions: seq<Semantics.RuleDefinition>,
    permissions: seq<Semantics.PermissionNode>
  ) {
    RecursiveEngine.ReachableRecursiveComponents(
      root,
      AcyclicEngine.CompilePaths(definitions),
      permissions
    ) == {}
  }

  ghost predicate BooleanDenotation(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    query: Semantics.Query
  ) {
    AcyclicEngine.SemanticallyAuthorized(
      objects,
      permissions,
      definitions,
      relationships,
      Semantics.Grant(query.subject, query.node, query.resource)
    )
  }

  function TraversalStackNodes(
    traversalStack: set<Semantics.Grant>
  ): set<Semantics.PermissionNode> {
    set grant <- traversalStack :: grant.node
  }

  ghost predicate AdmissibleAcyclicTraversalContext(
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    query: Semantics.Query,
    traversalStack: set<Semantics.Grant>
  ) {
    AcyclicRoot(query.node, definitions, permissions) &&
    TraversalStackNodes(traversalStack) !!
    RecursiveEngine.BoundedReachability(
      query.node,
      AcyclicEngine.CompilePaths(definitions),
      permissions
    )
  }

  ghost predicate CacheFreeAcyclicEvaluationContract(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    query: Semantics.Query,
    traversalStack: set<Semantics.Grant>,
    allowed: bool
  ) {
    AdmissibleAcyclicTraversalContext(
      permissions,
      definitions,
      query,
      traversalStack
    ) ==>
      (allowed <==>
       BooleanDenotation(
         objects,
         permissions,
         definitions,
         relationships,
         query
       ))
  }

  lemma AdmissibleAcyclicEvaluationsIgnoreTraversalStack(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    query: Semantics.Query,
    leftStack: set<Semantics.Grant>,
    rightStack: set<Semantics.Grant>,
    leftAllowed: bool,
    rightAllowed: bool
  )
    requires AdmissibleAcyclicTraversalContext(
               permissions,
               definitions,
               query,
               leftStack
             )
    requires AdmissibleAcyclicTraversalContext(
               permissions,
               definitions,
               query,
               rightStack
             )
    requires CacheFreeAcyclicEvaluationContract(
               objects,
               permissions,
               definitions,
               relationships,
               query,
               leftStack,
               leftAllowed
             )
    requires CacheFreeAcyclicEvaluationContract(
               objects,
               permissions,
               definitions,
               relationships,
               query,
               rightStack,
               rightAllowed
             )
    ensures leftAllowed == rightAllowed
  {
  }

  lemma RequestLocalAcyclicMemoRefinesCacheFreeEvaluation(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    query: Semantics.Query,
    traversalStack: set<Semantics.Grant>,
    recomputed: bool,
    candidate: ExactCandidate<bool>
  )
    requires AdmissibleAcyclicTraversalContext(
               permissions,
               definitions,
               query,
               traversalStack
             )
    requires CacheFreeAcyclicEvaluationContract(
               objects,
               permissions,
               definitions,
               relationships,
               query,
               traversalStack,
               recomputed
             )
    requires ExactCandidateRefines(candidate, recomputed)
    ensures ResolveExact(true, candidate).ExactHit? ==>
              (ResolveExact(true, candidate).value <==>
               BooleanDenotation(
                 objects,
                 permissions,
                 definitions,
                 relationships,
                 query
               ))
  {
    ExactHitRefinesRecomputation(true, candidate, recomputed);
  }

  lemma SharedAcyclicMemoRefinesCacheFreeEvaluation(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    query: Semantics.Query,
    publisherStack: set<Semantics.Grant>,
    consumerStack: set<Semantics.Grant>,
    published: bool,
    consumerRecomputed: bool
  )
    requires AdmissibleAcyclicTraversalContext(
               permissions,
               definitions,
               query,
               publisherStack
             )
    requires AdmissibleAcyclicTraversalContext(
               permissions,
               definitions,
               query,
               consumerStack
             )
    requires CacheFreeAcyclicEvaluationContract(
               objects,
               permissions,
               definitions,
               relationships,
               query,
               publisherStack,
               published
             )
    requires CacheFreeAcyclicEvaluationContract(
               objects,
               permissions,
               definitions,
               relationships,
               query,
               consumerStack,
               consumerRecomputed
             )
    ensures published == consumerRecomputed
    ensures published <==>
            BooleanDenotation(
              objects,
              permissions,
              definitions,
              relationships,
              query
            )
  {
    AdmissibleAcyclicEvaluationsIgnoreTraversalStack(
      objects,
      permissions,
      definitions,
      relationships,
      query,
      publisherStack,
      consumerStack,
      published,
      consumerRecomputed
    );
  }

  predicate PublicationAllowed(
    capturedLifecycle: nat,
    activeLifecycle: nat,
    complete: bool,
    valid: bool,
    weight: nat,
    budget: nat
  ) {
    capturedLifecycle == activeLifecycle &&
    complete &&
    valid &&
    0 < weight <= budget
  }

  lemma ExpiredLifecycleCannotPublish(
    capturedLifecycle: nat,
    activeLifecycle: nat,
    complete: bool,
    valid: bool,
    weight: nat,
    budget: nat
  )
    requires capturedLifecycle != activeLifecycle
    ensures !PublicationAllowed(
              capturedLifecycle,
              activeLifecycle,
              complete,
              valid,
              weight,
              budget
            )
  {
  }

  lemma PartialInvalidOrOversizedValuesCannotPublish(
    lifecycle: nat,
    complete: bool,
    valid: bool,
    weight: nat,
    budget: nat
  )
    requires !complete || !valid || weight == 0 || budget < weight
    ensures !PublicationAllowed(
              lifecycle,
              lifecycle,
              complete,
              valid,
              weight,
              budget
            )
  {
  }

  function AdmittedPrefixWeight(
    weights: seq<nat>,
    budget: nat,
    used: nat
  ): nat
    requires used <= budget
    decreases |weights|
  {
    if |weights| == 0 || budget < used + weights[0]
    then used
    else AdmittedPrefixWeight(weights[1..], budget, used + weights[0])
  }

  lemma AdmittedPrefixWeightIsBounded(
    weights: seq<nat>,
    budget: nat,
    used: nat
  )
    requires used <= budget
    ensures used <= AdmittedPrefixWeight(weights, budget, used) <= budget
    decreases |weights|
  {
    if |weights| != 0 && used + weights[0] <= budget {
      AdmittedPrefixWeightIsBounded(
        weights[1..],
        budget,
        used + weights[0]
      );
    }
  }

  predicate CandidateAdmissionAllowed(
    representedCandidates: nat,
    maximumCandidates: nat
  ) {
    0 < maximumCandidates &&
    representedCandidates < maximumCandidates
  }

  lemma CandidateAdmissionPreservesBound(
    representedCandidates: nat,
    maximumCandidates: nat
  )
    requires representedCandidates <= maximumCandidates
    requires CandidateAdmissionAllowed(
               representedCandidates,
               maximumCandidates
             )
    ensures representedCandidates + 1 <= maximumCandidates
  {
  }

  lemma FullCandidateStoreRejectsDistinctAdmission(
    maximumCandidates: nat
  )
    requires 0 < maximumCandidates
    ensures !CandidateAdmissionAllowed(
              maximumCandidates,
              maximumCandidates
            )
  {
  }

  function ChunksForDemand(
    demandedValues: nat,
    chunkWidth: nat
  ): nat
    requires 0 < chunkWidth
    decreases demandedValues
  {
    if demandedValues == 0 then
      0
    else if demandedValues <= chunkWidth then
      1
    else
      1 + ChunksForDemand(
        demandedValues - chunkWidth,
        chunkWidth
      )
  }

  lemma ChunksFetchedAreBoundedByDemand(
    demandedValues: nat,
    chunkWidth: nat
  )
    requires 0 < chunkWidth
    ensures ChunksForDemand(demandedValues, chunkWidth) <= demandedValues
    decreases demandedValues
  {
    if chunkWidth < demandedValues {
      ChunksFetchedAreBoundedByDemand(
        demandedValues - chunkWidth,
        chunkWidth
      );
    }
  }

  function MaximumValuesFetchedForDemand(
    demandedValues: nat,
    chunkWidth: nat
  ): nat
    requires 0 < chunkWidth
  {
    ChunksForDemand(demandedValues, chunkWidth) * chunkWidth
  }

  lemma FetchedProjectionPrefixHasAtMostOneChunkOfSlack(
    demandedValues: nat,
    chunkWidth: nat
  )
    requires 0 < chunkWidth
    ensures MaximumValuesFetchedForDemand(
              demandedValues,
              chunkWidth
            ) <= demandedValues + chunkWidth
    decreases demandedValues
  {
    if chunkWidth < demandedValues {
      FetchedProjectionPrefixHasAtMostOneChunkOfSlack(
        demandedValues - chunkWidth,
        chunkWidth
      );
      calc {
        MaximumValuesFetchedForDemand(demandedValues, chunkWidth);
      ==
        chunkWidth +
        MaximumValuesFetchedForDemand(
          demandedValues - chunkWidth,
          chunkWidth
        );
      <=
        chunkWidth + (demandedValues - chunkWidth) + chunkWidth;
      ==
        demandedValues + chunkWidth;
      }
    }
  }

  datatype FlightState =
    | NoFlight
    | ComputingFlight
    | CompletedFlight
    | FailedFlight

  function ComputationsForExactKey(state: FlightState): nat {
    if state.ComputingFlight? then 1 else 0
  }

  lemma ExactKeyHasAtMostOneComputation(state: FlightState)
    ensures ComputationsForExactKey(state) <= 1
  {
  }

  predicate EvictionEligible(state: FlightState) {
    state.CompletedFlight?
  }

  lemma RunningFlightsAreNotEvictionEligible(state: FlightState)
    requires state.ComputingFlight?
    ensures !EvictionEligible(state)
  {
  }

  datatype ComputationAccounting = ComputationAccounting(
    representedActive: nat,
    detachedActive: nat,
    unadmittedActive: nat,
    maximum: nat
  )

  function ActualComputations(
    accounting: ComputationAccounting
  ): nat {
    accounting.representedActive +
    accounting.detachedActive +
    accounting.unadmittedActive
  }

  predicate CoordinatorInvariant(
    accounting: ComputationAccounting
  ) {
    0 < accounting.maximum &&
    ActualComputations(accounting) <= accounting.maximum
  }

  predicate ComputationSlotAvailable(
    accounting: ComputationAccounting
  ) {
    ActualComputations(accounting) < accounting.maximum
  }

  datatype RecursiveBypassSlotAction =
    | ReuseOwnedSlot
    | AcquireDistinctContextSlot

  function DecideRecursiveBypassSlot(
    recordedOwnerContext: nat,
    currentContext: nat
  ): RecursiveBypassSlotAction {
    if recordedOwnerContext == currentContext then
      ReuseOwnedSlot
    else
      AcquireDistinctContextSlot
  }

  function StartRepresentedComputation(
    accounting: ComputationAccounting
  ): ComputationAccounting
    requires CoordinatorInvariant(accounting)
    requires ComputationSlotAvailable(accounting)
  {
    ComputationAccounting(
      accounting.representedActive + 1,
      accounting.detachedActive,
      accounting.unadmittedActive,
      accounting.maximum
    )
  }

  function StartUnadmittedComputation(
    accounting: ComputationAccounting
  ): ComputationAccounting
    requires CoordinatorInvariant(accounting)
    requires ComputationSlotAvailable(accounting)
  {
    ComputationAccounting(
      accounting.representedActive,
      accounting.detachedActive,
      accounting.unadmittedActive + 1,
      accounting.maximum
    )
  }

  function StartRecursiveBypassComputation(
    accounting: ComputationAccounting,
    recordedOwnerContext: nat,
    currentContext: nat
  ): ComputationAccounting
    requires CoordinatorInvariant(accounting)
    requires recordedOwnerContext == currentContext ||
             ComputationSlotAvailable(accounting)
  {
    if DecideRecursiveBypassSlot(
         recordedOwnerContext,
         currentContext
       ).ReuseOwnedSlot?
    then
      accounting
    else
      StartUnadmittedComputation(accounting)
  }

  function DetachRepresentedComputation(
    accounting: ComputationAccounting
  ): ComputationAccounting
    requires 0 < accounting.representedActive
  {
    ComputationAccounting(
      accounting.representedActive - 1,
      accounting.detachedActive + 1,
      accounting.unadmittedActive,
      accounting.maximum
    )
  }

  function FinishDetachedComputation(
    accounting: ComputationAccounting
  ): ComputationAccounting
    requires 0 < accounting.detachedActive
  {
    ComputationAccounting(
      accounting.representedActive,
      accounting.detachedActive - 1,
      accounting.unadmittedActive,
      accounting.maximum
    )
  }

  function FinishUnadmittedComputation(
    accounting: ComputationAccounting
  ): ComputationAccounting
    requires 0 < accounting.unadmittedActive
  {
    ComputationAccounting(
      accounting.representedActive,
      accounting.detachedActive,
      accounting.unadmittedActive - 1,
      accounting.maximum
    )
  }

  lemma RepresentedStartPreservesActualComputationBound(
    accounting: ComputationAccounting
  )
    requires CoordinatorInvariant(accounting)
    requires ComputationSlotAvailable(accounting)
    ensures CoordinatorInvariant(
              StartRepresentedComputation(accounting)
            )
  {
  }

  lemma UnadmittedStartPreservesActualComputationBound(
    accounting: ComputationAccounting
  )
    requires CoordinatorInvariant(accounting)
    requires ComputationSlotAvailable(accounting)
    ensures CoordinatorInvariant(
              StartUnadmittedComputation(accounting)
            )
  {
  }

  lemma RecursiveBypassReusesOnlyItsOwnerContext(
    accounting: ComputationAccounting,
    recordedOwnerContext: nat,
    currentContext: nat
  )
    requires CoordinatorInvariant(accounting)
    requires recordedOwnerContext == currentContext ||
             ComputationSlotAvailable(accounting)
    ensures
      ActualComputations(
        StartRecursiveBypassComputation(
          accounting,
          recordedOwnerContext,
          currentContext
        )
      ) ==
      ActualComputations(accounting) +
      (if recordedOwnerContext == currentContext then 0 else 1)
  {
  }

  lemma RecursiveBypassPreservesActualComputationBound(
    accounting: ComputationAccounting,
    recordedOwnerContext: nat,
    currentContext: nat
  )
    requires CoordinatorInvariant(accounting)
    requires recordedOwnerContext == currentContext ||
             ComputationSlotAvailable(accounting)
    ensures CoordinatorInvariant(
              StartRecursiveBypassComputation(
                accounting,
                recordedOwnerContext,
                currentContext
              )
            )
  {
  }

  lemma LifecycleDetachmentPreservesActualComputationCount(
    accounting: ComputationAccounting
  )
    requires 0 < accounting.representedActive
    ensures ActualComputations(
              DetachRepresentedComputation(accounting)
            ) ==
            ActualComputations(accounting)
  {
  }

  lemma DetachedCompletionPreservesActualComputationBound(
    accounting: ComputationAccounting
  )
    requires CoordinatorInvariant(accounting)
    requires 0 < accounting.detachedActive
    ensures CoordinatorInvariant(
              FinishDetachedComputation(accounting)
            )
  {
  }

  lemma UnadmittedCompletionPreservesActualComputationBound(
    accounting: ComputationAccounting
  )
    requires CoordinatorInvariant(accounting)
    requires 0 < accounting.unadmittedActive
    ensures CoordinatorInvariant(
              FinishUnadmittedComputation(accounting)
            )
  {
  }

  datatype ComponentPublication =
    | ComponentAbsent
    | ComponentRunning(grants: set<Semantics.Grant>)
    | ComponentComplete(grants: set<Semantics.Grant>)
    | ComponentLimited
    | ComponentFailed

  datatype DenotationPublication =
    | DenotationAbsent
    | DenotationRunning(items: seq<Semantics.ObjectRef>)
    | DenotationComplete(items: seq<Semantics.ObjectRef>)
    | DenotationLimited
    | DenotationFailed

  ghost predicate CompleteLeastFixedPoint(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    publication: ComponentPublication
  ) {
    publication.ComponentComplete? &&
    AcyclicEngine.LeastFixedPoint(
      objects,
      permissions,
      definitions,
      relationships,
      publication.grants
    )
  }

  ghost predicate RecursivePublicationAllowed(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    publication: ComponentPublication
  ) {
    CompleteLeastFixedPoint(
      objects,
      permissions,
      definitions,
      relationships,
      publication
    )
  }

  lemma PartialRecursiveStateCannotPublish(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    publication: ComponentPublication
  )
    requires !publication.ComponentComplete?
    ensures !RecursivePublicationAllowed(
              objects,
              permissions,
              definitions,
              relationships,
              publication
            )
  {
  }

  ghost predicate CompleteForwardDenotation(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    subject: Semantics.ObjectRef,
    node: Semantics.PermissionNode,
    publication: DenotationPublication
  ) {
    publication.DenotationComplete? &&
    AcyclicEngine.UniqueObjects(publication.items) &&
    (forall resource ::
       resource in publication.items <==>
                   resource in objects &&
                   AcyclicEngine.SemanticallyAuthorized(
                     objects,
                     permissions,
                     definitions,
                     relationships,
                     Semantics.Grant(subject, node, resource)
                   ))
  }

  ghost predicate CompleteReverseDenotation(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    resource: Semantics.ObjectRef,
    node: Semantics.PermissionNode,
    subjectType: string,
    publication: DenotationPublication
  ) {
    publication.DenotationComplete? &&
    AcyclicEngine.UniqueObjects(publication.items) &&
    (forall subject ::
       subject in publication.items <==>
                  subject in objects &&
                  subject.typeName == subjectType &&
                  AcyclicEngine.SemanticallyAuthorized(
                    objects,
                    permissions,
                    definitions,
                    relationships,
                    Semantics.Grant(subject, node, resource)
                  ))
  }

  lemma PartialRecursiveDenotationCannotPublishForward(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    subject: Semantics.ObjectRef,
    node: Semantics.PermissionNode,
    publication: DenotationPublication
  )
    requires !publication.DenotationComplete?
    ensures !CompleteForwardDenotation(
              objects,
              permissions,
              definitions,
              relationships,
              subject,
              node,
              publication
            )
  {
  }

  lemma PartialRecursiveDenotationCannotPublishReverse(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    resource: Semantics.ObjectRef,
    node: Semantics.PermissionNode,
    subjectType: string,
    publication: DenotationPublication
  )
    requires !publication.DenotationComplete?
    ensures !CompleteReverseDenotation(
              objects,
              permissions,
              definitions,
              relationships,
              resource,
              node,
              subjectType,
              publication
            )
  {
  }

  method CompletedWorklistMayPublish(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    limits: RecursiveEngine.TraversalLimits
  ) returns (publication: ComponentPublication)
    ensures publication.ComponentComplete? ==>
              RecursivePublicationAllowed(
                objects,
                permissions,
                definitions,
                relationships,
                publication
              )
  {
    var outcome :=
      RecursiveEngine.EvaluateClosureWithLimits(
        RecursiveEngine.Forward,
        objects,
        permissions,
        definitions,
        relationships,
        limits
      );
    if outcome.ClosureComplete? {
      publication := ComponentComplete(outcome.grants);
    } else {
      publication := ComponentLimited;
    }
  }

  method CompletedForwardDenotationMayPublish(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    subject: Semantics.ObjectRef,
    node: Semantics.PermissionNode,
    limits: RecursiveEngine.TraversalLimits
  ) returns (publication: DenotationPublication)
    requires AcyclicEngine.UniqueObjects(objects)
    ensures publication.DenotationComplete? ==>
              CompleteForwardDenotation(
                objects,
                permissions,
                definitions,
                relationships,
                subject,
                node,
                publication
              )
  {
    var outcome :=
      RecursiveEngine.RecursiveForward(
        objects,
        permissions,
        definitions,
        relationships,
        subject,
        node,
        limits
      );
    if outcome.SequenceComplete? {
      publication := DenotationComplete(outcome.items);
    } else {
      publication := DenotationLimited;
    }
  }

  method CompletedReverseDenotationMayPublish(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    resource: Semantics.ObjectRef,
    node: Semantics.PermissionNode,
    subjectType: string,
    limits: RecursiveEngine.TraversalLimits
  ) returns (publication: DenotationPublication)
    requires AcyclicEngine.UniqueObjects(objects)
    ensures publication.DenotationComplete? ==>
              CompleteReverseDenotation(
                objects,
                permissions,
                definitions,
                relationships,
                resource,
                node,
                subjectType,
                publication
              )
  {
    var outcome :=
      RecursiveEngine.RecursiveReverseTyped(
        objects,
        permissions,
        definitions,
        relationships,
        resource,
        node,
        subjectType,
        limits
      );
    if outcome.SequenceComplete? {
      publication := DenotationComplete(outcome.items);
    } else {
      publication := DenotationLimited;
    }
  }
}

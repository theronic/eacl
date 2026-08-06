include "RecursiveEngine.dfy"
include "TemporalSafety.dfy"

module CacheKernel {
  import Semantics
  import AcyclicEngine
  import RecursiveEngine
  import TemporalSafety

  datatype RelationDependency = RelationDependency(
    resourceType: string,
    relationName: string
  )

  datatype DependencyScope = DependencyScope(
    permissionNodes: set<Semantics.PermissionNode>,
    relations: set<RelationDependency>
  )

  function ObjectTypes(
    objects: seq<Semantics.ObjectRef>
  ): set<string> {
    set item <- objects :: item.typeName
  }

  function DefinitionRelationDependencies(
    definition: Semantics.RuleDefinition,
    objectTypes: set<string>
  ): set<RelationDependency> {
    match definition
    case DirectRelation(head, relationName, _) =>
      {RelationDependency(head.resourceType, relationName)}
    case SelfPermission(_, _) =>
      {}
    case ArrowRelation(
      head,
      viaRelation,
      targetRelation,
      _
      ) =>
      {RelationDependency(head.resourceType, viaRelation)} +
      set typeName <- objectTypes ::
        RelationDependency(typeName, targetRelation)
    case ArrowPermission(head, viaRelation, _) =>
      {RelationDependency(head.resourceType, viaRelation)}
  }

  function DefinitionsRelationDependencies(
    definitions: seq<Semantics.RuleDefinition>,
    reachable: set<Semantics.PermissionNode>,
    objectTypes: set<string>
  ): set<RelationDependency>
    decreases |definitions|
  {
    if |definitions| == 0 then
      {}
    else
      (if definitions[0].head in reachable
       then DefinitionRelationDependencies(
              definitions[0],
              objectTypes
            )
       else {}) +
      DefinitionsRelationDependencies(
        definitions[1..],
        reachable,
        objectTypes
      )
  }

  function CalculatedDependencyScope(
    root: Semantics.PermissionNode,
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>
  ): DependencyScope {
    var paths := AcyclicEngine.CompilePaths(definitions);
    var reachable :=
      RecursiveEngine.BoundedReachability(
        root,
        paths,
        permissions
      );
    var relations := DefinitionsRelationDependencies(
                       definitions,
                       reachable,
                       ObjectTypes(objects)
                     );
    DependencyScope(reachable, relations)
  }

  lemma ReachableDefinitionDependenciesAreIncluded(
    definitions: seq<Semantics.RuleDefinition>,
    reachable: set<Semantics.PermissionNode>,
    objectTypes: set<string>,
    index: nat
  )
    requires index < |definitions|
    requires definitions[index].head in reachable
    ensures DefinitionRelationDependencies(
              definitions[index],
              objectTypes
            ) <=
            DefinitionsRelationDependencies(
              definitions,
              reachable,
              objectTypes
            )
    decreases |definitions|
  {
    if index != 0 {
      ReachableDefinitionDependenciesAreIncluded(
        definitions[1..],
        reachable,
        objectTypes,
        index - 1
      );
    }
  }

  ghost predicate ScopeCompleteForSemantics(
    root: Semantics.PermissionNode,
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    scope: DependencyScope
  ) {
    root in AcyclicEngine.PermissionUniverse(permissions) ==>
      root in scope.permissionNodes &&
      (forall definition <- definitions |
              definition.head in scope.permissionNodes ::
         DefinitionRelationDependencies(
           definition,
           ObjectTypes(objects)
         ) <= scope.relations) &&
      AcyclicEngine.DependencyStep(
        AcyclicEngine.CompilePaths(definitions),
        permissions,
        scope.permissionNodes
      ) == scope.permissionNodes
  }

  lemma CalculatedScopeIsSemanticallyComplete(
    root: Semantics.PermissionNode,
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>
  )
    ensures ScopeCompleteForSemantics(
              root,
              objects,
              permissions,
              definitions,
              CalculatedDependencyScope(
                root,
                objects,
                permissions,
                definitions
              )
            )
  {
    RecursiveEngine.BoundedReachabilityIsExactLeastClosure(
      root,
      AcyclicEngine.CompilePaths(definitions),
      permissions
    );
    forall definition <- definitions |
           definition.head in
             CalculatedDependencyScope(
               root,
               objects,
               permissions,
               definitions
             ).permissionNodes
      ensures DefinitionRelationDependencies(
                definition,
                ObjectTypes(objects)
              ) <=
              CalculatedDependencyScope(
                root,
                objects,
                permissions,
                definitions
              ).relations
    {
      var index :| 0 <= index < |definitions| &&
                   definitions[index] == definition;
      ReachableDefinitionDependenciesAreIncluded(
        definitions,
        CalculatedDependencyScope(
          root,
          objects,
          permissions,
          definitions
        ).permissionNodes,
        ObjectTypes(objects),
        index
      );
    }
  }

  lemma EveryReachableDefinitionReadIsInScope(
    root: Semantics.PermissionNode,
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    definition: Semantics.RuleDefinition
  )
    requires definition in definitions
    requires definition.head in
               CalculatedDependencyScope(
                 root,
                 objects,
                 permissions,
                 definitions
               ).permissionNodes
    ensures DefinitionRelationDependencies(
              definition,
              ObjectTypes(objects)
            ) <=
            CalculatedDependencyScope(
              root,
              objects,
              permissions,
              definitions
            ).relations
  {
    var index :| 0 <= index < |definitions| &&
                 definitions[index] == definition;
    ReachableDefinitionDependenciesAreIncluded(
      definitions,
      CalculatedDependencyScope(
        root,
        objects,
        permissions,
        definitions
      ).permissionNodes,
      ObjectTypes(objects),
      index
    );
  }

  datatype ProofState =
    | ProofUnavailable
    | CompleteProof(value: string)

  datatype Telemetry = Telemetry(
    validatedGraph: TemporalSafety.Graph,
    validationCount: nat
  )

  datatype CacheCandidate<T> =
    | NoCandidate
    | ProviderFailed
    | Candidate(
        authenticated: bool,
        semanticKey: string,
        sourceIdentity: string,
        computationGraph: TemporalSafety.Graph,
        proof: ProofState,
        value: T,
        telemetry: Telemetry
      )

  datatype CacheMissReason =
    | Missing
    | ProviderFailure
    | NoProofBypass
    | Unauthenticated
    | ScopeMismatch
    | FutureOrSibling
    | ProofMismatch

  datatype CacheProvenance =
    | ExactHit
    | CausalProofLift

  datatype CacheDecision<T> =
    | CacheMiss(reason: CacheMissReason)
    | CacheHit(value: T, provenance: CacheProvenance)

  function ValidateCache<T>(
    deterministicAdapter: bool,
    dependencyScopeNonempty: bool,
    expectedKey: string,
    expectedSource: string,
    selectedGraph: TemporalSafety.Graph,
    selectedAncestors: set<TemporalSafety.Graph>,
    selectedProof: ProofState,
    candidate: CacheCandidate<T>
  ): CacheDecision<T> {
    if !deterministicAdapter ||
       !dependencyScopeNonempty ||
       selectedProof.ProofUnavailable?
    then
      CacheMiss(NoProofBypass)
    else
      match candidate
      case NoCandidate =>
        CacheMiss(Missing)
      case ProviderFailed =>
        CacheMiss(ProviderFailure)
      case Candidate(
        authenticated,
        semanticKey,
        sourceIdentity,
        computationGraph,
        proof,
        value,
        _
        ) =>
        if !authenticated then
          CacheMiss(Unauthenticated)
        else if semanticKey != expectedKey ||
                sourceIdentity != expectedSource
        then
          CacheMiss(ScopeMismatch)
        else if computationGraph != selectedGraph &&
                computationGraph !in selectedAncestors
          then
            CacheMiss(FutureOrSibling)
          else if proof.ProofUnavailable? ||
                  proof != selectedProof
            then
              CacheMiss(ProofMismatch)
            else if computationGraph == selectedGraph then
                CacheHit(value, ExactHit)
              else
                CacheHit(value, CausalProofLift)
  }

  lemma AcceptedCacheHasCompleteProofAndForwardHistory<T>(
    deterministicAdapter: bool,
    dependencyScopeNonempty: bool,
    expectedKey: string,
    expectedSource: string,
    selectedGraph: TemporalSafety.Graph,
    selectedAncestors: set<TemporalSafety.Graph>,
    selectedProof: ProofState,
    candidate: CacheCandidate<T>
  )
    ensures ValidateCache(
              deterministicAdapter,
              dependencyScopeNonempty,
              expectedKey,
              expectedSource,
              selectedGraph,
              selectedAncestors,
              selectedProof,
              candidate
            ).CacheHit? ==>
              deterministicAdapter &&
              dependencyScopeNonempty &&
              selectedProof.CompleteProof? &&
              candidate.Candidate? &&
              candidate.authenticated &&
              candidate.semanticKey == expectedKey &&
              candidate.sourceIdentity == expectedSource &&
              candidate.proof == selectedProof &&
              (candidate.computationGraph == selectedGraph ||
               candidate.computationGraph in selectedAncestors)
  {
  }

  lemma NoProofAlwaysBypasses<T>(
    expectedKey: string,
    expectedSource: string,
    selectedGraph: TemporalSafety.Graph,
    selectedAncestors: set<TemporalSafety.Graph>,
    candidate: CacheCandidate<T>
  )
    ensures ValidateCache(
              true,
              true,
              expectedKey,
              expectedSource,
              selectedGraph,
              selectedAncestors,
              ProofUnavailable,
              candidate
            ) == CacheMiss(NoProofBypass)
  {
  }

  lemma FutureAndSiblingCandidatesAreRejected<T>(
    expectedKey: string,
    expectedSource: string,
    selectedGraph: TemporalSafety.Graph,
    selectedAncestors: set<TemporalSafety.Graph>,
    proof: string,
    candidate: CacheCandidate<T>
  )
    requires candidate.Candidate?
    requires candidate.computationGraph != selectedGraph
    requires candidate.computationGraph !in selectedAncestors
    ensures !ValidateCache(
              true,
              true,
              expectedKey,
              expectedSource,
              selectedGraph,
              selectedAncestors,
              CompleteProof(proof),
              candidate
            ).CacheHit?
  {
  }

  ghost predicate CompleteProofContract<T>(
    selectedGraph: TemporalSafety.Graph,
    selectedAncestors: set<TemporalSafety.Graph>,
    selectedProof: ProofState,
    candidate: CacheCandidate<T>,
    recomputed: T
  ) {
    candidate.Candidate? &&
    candidate.authenticated &&
    selectedProof.CompleteProof? &&
    candidate.proof == selectedProof &&
    (candidate.computationGraph == selectedGraph ||
     candidate.computationGraph in selectedAncestors) ==>
      candidate.value == recomputed
  }

  lemma AcceptedCacheEqualsRecomputation<T>(
    deterministicAdapter: bool,
    dependencyScopeNonempty: bool,
    expectedKey: string,
    expectedSource: string,
    selectedGraph: TemporalSafety.Graph,
    selectedAncestors: set<TemporalSafety.Graph>,
    selectedProof: ProofState,
    candidate: CacheCandidate<T>,
    recomputed: T
  )
    requires CompleteProofContract(
               selectedGraph,
               selectedAncestors,
               selectedProof,
               candidate,
               recomputed
             )
    ensures ValidateCache(
              deterministicAdapter,
              dependencyScopeNonempty,
              expectedKey,
              expectedSource,
              selectedGraph,
              selectedAncestors,
              selectedProof,
              candidate
            ).CacheHit? ==>
              ValidateCache(
                deterministicAdapter,
                dependencyScopeNonempty,
                expectedKey,
                expectedSource,
                selectedGraph,
                selectedAncestors,
                selectedProof,
                candidate
              ).value == recomputed
  {
    AcceptedCacheHasCompleteProofAndForwardHistory(
      deterministicAdapter,
      dependencyScopeNonempty,
      expectedKey,
      expectedSource,
      selectedGraph,
      selectedAncestors,
      selectedProof,
      candidate
    );
  }

  ghost predicate TelemetryOnlyChange<T>(
    original: CacheCandidate<T>,
    replacement: CacheCandidate<T>
  ) {
    original.Candidate? &&
    replacement.Candidate? &&
    original.authenticated == replacement.authenticated &&
    original.semanticKey == replacement.semanticKey &&
    original.sourceIdentity == replacement.sourceIdentity &&
    original.computationGraph == replacement.computationGraph &&
    original.proof == replacement.proof &&
    original.value == replacement.value
  }

  lemma TelemetryDoesNotAffectValidation<T>(
    deterministicAdapter: bool,
    dependencyScopeNonempty: bool,
    expectedKey: string,
    expectedSource: string,
    selectedGraph: TemporalSafety.Graph,
    selectedAncestors: set<TemporalSafety.Graph>,
    selectedProof: ProofState,
    original: CacheCandidate<T>,
    replacement: CacheCandidate<T>
  )
    requires TelemetryOnlyChange(original, replacement)
    ensures ValidateCache(
              deterministicAdapter,
              dependencyScopeNonempty,
              expectedKey,
              expectedSource,
              selectedGraph,
              selectedAncestors,
              selectedProof,
              original
            ) ==
            ValidateCache(
              deterministicAdapter,
              dependencyScopeNonempty,
              expectedKey,
              expectedSource,
              selectedGraph,
              selectedAncestors,
              selectedProof,
              replacement
            )
  {
  }

  function CompareAndSetTelemetry<T(==)>(
    current: CacheCandidate<T>,
    expected: CacheCandidate<T>,
    replacement: CacheCandidate<T>
  ): CacheCandidate<T> {
    if current == expected then replacement else current
  }

  function AuthorizationValue<T>(
    decision: CacheDecision<T>,
    recomputed: T
  ): T {
    if decision.CacheHit? then decision.value else recomputed
  }

  lemma TelemetryRaceCannotChangeChosenAuthorization<T>(
    deterministicAdapter: bool,
    dependencyScopeNonempty: bool,
    expectedKey: string,
    expectedSource: string,
    selectedGraph: TemporalSafety.Graph,
    selectedAncestors: set<TemporalSafety.Graph>,
    selectedProof: ProofState,
    decision: CacheDecision<T>,
    recomputed: T,
    current: CacheCandidate<T>,
    expected: CacheCandidate<T>,
    replacement: CacheCandidate<T>
  )
    requires decision ==
             ValidateCache(
               deterministicAdapter,
               dependencyScopeNonempty,
               expectedKey,
               expectedSource,
               selectedGraph,
               selectedAncestors,
               selectedProof,
               expected
             )
    requires TelemetryOnlyChange(expected, replacement)
    ensures AuthorizationValue(decision, recomputed) ==
            AuthorizationValue(decision, recomputed)
    ensures current == expected ==>
              CompareAndSetTelemetry(
                current,
                expected,
                replacement
              ) == replacement
    ensures current != expected ==>
              CompareAndSetTelemetry(
                current,
                expected,
                replacement
              ) == current
    ensures current == expected ==>
              ValidateCache(
                deterministicAdapter,
                dependencyScopeNonempty,
                expectedKey,
                expectedSource,
                selectedGraph,
                selectedAncestors,
                selectedProof,
                CompareAndSetTelemetry(
                  current,
                  expected,
                  replacement
                )
              ) == decision
  {
    TelemetryDoesNotAffectValidation(
      deterministicAdapter,
      dependencyScopeNonempty,
      expectedKey,
      expectedSource,
      selectedGraph,
      selectedAncestors,
      selectedProof,
      expected,
      replacement
    );
  }
}

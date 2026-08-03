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

  ghost predicate AcyclicSubproblemEvaluation(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    query: Semantics.Query,
    traversalStack: set<Semantics.Grant>
  ) {
    BooleanDenotation(
      objects,
      permissions,
      definitions,
      relationships,
      query
    )
  }

  lemma AcyclicBooleanDenotationIgnoresTraversalStack(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    query: Semantics.Query,
    leftStack: set<Semantics.Grant>,
    rightStack: set<Semantics.Grant>
  )
    requires AcyclicRoot(query.node, definitions, permissions)
    ensures AcyclicSubproblemEvaluation(
              objects,
              permissions,
              definitions,
              relationships,
              query,
              leftStack
            ) ==
            AcyclicSubproblemEvaluation(
              objects,
              permissions,
              definitions,
              relationships,
              query,
              rightStack
            )
  {
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

  predicate InflightAdmissionAllowed(
    activeComputations: nat,
    maximumComputations: nat
  ) {
    0 < maximumComputations &&
    activeComputations < maximumComputations
  }

  lemma InflightAdmissionPreservesBound(
    activeComputations: nat,
    maximumComputations: nat
  )
    requires activeComputations <= maximumComputations
    requires InflightAdmissionAllowed(
               activeComputations,
               maximumComputations
             )
    ensures activeComputations + 1 <= maximumComputations
  {
  }

  lemma FullInflightStoreRejectsDistinctAdmission(
    maximumComputations: nat
  )
    requires 0 < maximumComputations
    ensures !InflightAdmissionAllowed(
              maximumComputations,
              maximumComputations
            )
  {
  }

  datatype ComponentPublication =
    | ComponentAbsent
    | ComponentRunning(grants: set<Semantics.Grant>)
    | ComponentComplete(grants: set<Semantics.Grant>)
    | ComponentLimited
    | ComponentFailed

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
}

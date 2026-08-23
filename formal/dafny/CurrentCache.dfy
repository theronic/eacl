include "CacheKernel.dfy"
include "SnapshotOracle.dfy"

module CurrentCache {
  import Semantics
  import CacheKernel
  import SnapshotOracle

  datatype BasisClass =
    | OrdinaryBasis
    | HistoricalBasis
    | InadmissibleBasis

  datatype EngineAuthority =
    | LegacyAuthority
    | VerifiedShadow
    | VerifiedAuthority

  function EvaluationAuthority(
    configured: EngineAuthority,
    basisClass: BasisClass,
    completedAnswerCacheEnabled: bool
  ): EngineAuthority
  {
    configured
  }

  lemma CacheEligibilityDoesNotSelectEngineAuthority(
    configured: EngineAuthority,
    basisClass: BasisClass,
    completedAnswerCacheEnabled: bool
  )
    ensures
      EvaluationAuthority(
        configured,
        basisClass,
        completedAnswerCacheEnabled
      ) == configured
  {
  }

  datatype CurrentCacheStage =
    | EligibilityStage
    | GenerationStage
    | ExactEntryStage
    | ExactOnlyEntryStage
    | ManagedEntryStage

  datatype CurrentCacheAction =
    | BypassCurrentCache
    | ProbeExactEntry
    | UseExactEntry
    | ProbeManagedEntry
    | UseManagedEntry
    | ComputeSelectedValue
    | ComputeExactValue

  function DecideCurrentCache(
    stage: CurrentCacheStage,
    available: bool
  ): CurrentCacheAction {
    match stage
    case EligibilityStage =>
      if available then ProbeExactEntry else BypassCurrentCache
    case GenerationStage =>
      if available then ProbeExactEntry else BypassCurrentCache
    case ExactEntryStage =>
      if available then UseExactEntry else ProbeManagedEntry
    case ExactOnlyEntryStage =>
      if available then UseExactEntry else ComputeExactValue
    case ManagedEntryStage =>
      if available then UseManagedEntry else ComputeSelectedValue
  }

  lemma CurrentCacheHitRequiresAvailableEntry(
    stage: CurrentCacheStage,
    available: bool
  )
    ensures DecideCurrentCache(stage, available).UseExactEntry? ==>
              (stage.ExactEntryStage? || stage.ExactOnlyEntryStage?) &&
              available
    ensures DecideCurrentCache(stage, available).UseManagedEntry? ==>
              stage.ManagedEntryStage? && available
  {
  }

  lemma CurrentCacheBypassRequiresIneligibleOrInactive(
    stage: CurrentCacheStage,
    available: bool
  )
    ensures DecideCurrentCache(stage, available).BypassCurrentCache? ==>
              !available &&
              (stage.EligibilityStage? || stage.GenerationStage?)
  {
  }

  lemma CacheComputationRequiresASelectedBasisMiss(
    stage: CurrentCacheStage,
    available: bool
  )
    ensures DecideCurrentCache(stage, available).ComputeSelectedValue? ==>
              stage.ManagedEntryStage? && !available
    ensures DecideCurrentCache(stage, available).ComputeExactValue? ==>
              stage.ExactOnlyEntryStage? && !available
  {
  }

  datatype CurrentCacheDecision<T> =
    | SelectedBasisMiss
    | ExactBasisHit(value: T)
    | ManagedLiftedHit(value: T)

  predicate ExactTierEligible(
    basisClass: BasisClass,
    completeBasisIdentity: bool
  ) {
    completeBasisIdentity &&
    (basisClass.OrdinaryBasis? || basisClass.HistoricalBasis?)
  }

  lemma InadmissibleValuesBypassCompletedAnswers(
    completeBasisIdentity: bool
  )
    ensures !ExactTierEligible(
              InadmissibleBasis,
              completeBasisIdentity
            )
  {
  }

  lemma EveryAdmittedBasisRequiresCompleteIdentity(
    basisClass: BasisClass
  )
    ensures !ExactTierEligible(basisClass, false)
  {
  }

  datatype LookupPageDirection =
    | ForwardLookupPage
    | BackwardLookupPage

  datatype AuthenticatedLookupPageIdentity =
    | AuthenticatedLookupPageIdentity(
        normalizedNonPageQuery: string,
        direction: LookupPageDirection,
        pageSize: nat,
        internalBound: string
      )

  function LookupPageSemanticIdentity(
    normalizedNonPageQuery: string,
    direction: LookupPageDirection,
    pageSize: nat,
    internalBound: string
  ): AuthenticatedLookupPageIdentity
  {
    AuthenticatedLookupPageIdentity(
      normalizedNonPageQuery,
      direction,
      pageSize,
      internalBound
    )
  }

  lemma CursorTransportAndRecoveryDoNotChangeLookupPageIdentity(
    normalizedNonPageQuery: string,
    direction: LookupPageDirection,
    pageSize: nat,
    internalBound: string,
    firstSignedTransport: string,
    secondSignedTransport: string,
    firstRequiresRebase: bool,
    secondRequiresRebase: bool
  )
    ensures
      LookupPageSemanticIdentity(
        normalizedNonPageQuery,
        direction,
        pageSize,
        internalBound
      ) ==
      LookupPageSemanticIdentity(
        normalizedNonPageQuery,
        direction,
        pageSize,
        internalBound
      )
  {
  }

  lemma DifferentInternalBoundsSeparateLookupPageIdentities(
    normalizedNonPageQuery: string,
    direction: LookupPageDirection,
    pageSize: nat,
    firstInternalBound: string,
    secondInternalBound: string
  )
    requires firstInternalBound != secondInternalBound
    ensures
      LookupPageSemanticIdentity(
        normalizedNonPageQuery,
        direction,
        pageSize,
        firstInternalBound
      ) !=
      LookupPageSemanticIdentity(
        normalizedNonPageQuery,
        direction,
        pageSize,
        secondInternalBound
      )
  {
  }

  datatype ExactBasisIdentity =
    | ExactBasisIdentity(
        sourceScope: string,
        sourceLifecycle: string,
        nativeRevision: string,
        exactLocator: string,
        viewKind: string,
        adapterFingerprint: string,
        identityContract: string
      )

  predicate ExactGenerationMatches(
    selectedBasis: ExactBasisIdentity,
    generationBasis: ExactBasisIdentity
  ) {
    selectedBasis == generationBasis
  }

  lemma ExactGenerationHitIsSameBasis(
    selectedBasis: ExactBasisIdentity,
    generationBasis: ExactBasisIdentity
  )
    requires ExactGenerationMatches(
               selectedBasis,
               generationBasis
             )
    ensures selectedBasis == generationBasis
  {
  }

  lemma NumericRevisionAloneCannotEstablishExactIdentity(
    selectedBasis: ExactBasisIdentity,
    generationBasis: ExactBasisIdentity
  )
    requires selectedBasis.nativeRevision ==
             generationBasis.nativeRevision
    requires selectedBasis != generationBasis
    ensures !ExactGenerationMatches(
              selectedBasis,
              generationBasis
            )
  {
  }

  predicate PublicationReachable(
    capturedLifecycle: nat,
    activeLifecycle: nat
  ) {
    capturedLifecycle == activeLifecycle
  }

  lemma LatePublicationCannotRepopulateExpiredLifecycle(
    capturedLifecycle: nat,
    activeLifecycle: nat
  )
    requires capturedLifecycle < activeLifecycle
    ensures !PublicationReachable(
              capturedLifecycle,
              activeLifecycle
            )
  {
  }

  function MaxStamp(stamps: seq<nat>): nat
    decreases |stamps|
  {
    if |stamps| == 0 then
      0
    else
      var tail := MaxStamp(stamps[1..]);
      if tail < stamps[0] then stamps[0] else tail
  }

  lemma EveryStampIsAtMostMax(
    stamps: seq<nat>,
    index: nat
  )
    requires index < |stamps|
    ensures stamps[index] <= MaxStamp(stamps)
    decreases |stamps|
  {
    if index != 0 {
      EveryStampIsAtMostMax(stamps[1..], index - 1);
    }
  }

  lemma TailMaximumIsAtMostMaximum(stamps: seq<nat>)
    requires 0 < |stamps|
    ensures MaxStamp(stamps[1..]) <= MaxStamp(stamps)
  {
  }

  lemma RaisingOneRelevantStampRaisesTheMaximum(
    before: seq<nat>,
    index: nat,
    transaction: nat
  )
    requires index < |before|
    requires MaxStamp(before) < transaction
    ensures MaxStamp(
              before[..index] +
              [transaction] +
              before[index + 1..]
            ) == transaction
    decreases |before|
  {
    var updated :=
      before[..index] +
      [transaction] +
      before[index + 1..];
    if index == 0 {
      TailMaximumIsAtMostMaximum(before);
      assert MaxStamp(before[1..]) < transaction;
      assert updated == [transaction] + before[1..];
    } else {
      var updatedTail :=
        before[1..index] +
        [transaction] +
        before[index + 1..];
      var recursiveUpdated :=
        before[1..][..index - 1] +
        [transaction] +
        before[1..][index..];
      RaisingOneRelevantStampRaisesTheMaximum(
        before[1..],
        index - 1,
        transaction
      );
      assert recursiveUpdated == updatedTail;
      assert updated == [before[0]] + updatedTail;
      assert MaxStamp(updatedTail) == transaction;
      EveryStampIsAtMostMax(before, 0);
      assert before[0] < transaction;
    }
  }

  function RuleDependencies(
    rule: Semantics.NormalizedRule,
    objectTypes: set<string>
  ): set<CacheKernel.RelationDependency> {
    match rule
    case DirectRelationRule(head, relationName, _) =>
      {
        CacheKernel.RelationDependency(
          head.resourceType,
          relationName
        )
      }
    case SelfPermissionRule(_, _) =>
      {}
    case ArrowRelationRule(
      head,
      viaRelation,
      targetRelation,
      _
      ) =>
      {
        CacheKernel.RelationDependency(
          head.resourceType,
          viaRelation
        )
      } +
      set typeName <- objectTypes ::
        CacheKernel.RelationDependency(typeName, targetRelation)
    case ArrowPermissionRule(head, viaRelation, _) =>
      {
        CacheKernel.RelationDependency(
          head.resourceType,
          viaRelation
        )
      }
  }

  function RulesDependencies(
    rules: seq<Semantics.NormalizedRule>,
    objectTypes: set<string>
  ): set<CacheKernel.RelationDependency>
    decreases |rules|
  {
    if |rules| == 0 then
      {}
    else
      RuleDependencies(rules[0], objectTypes) +
      RulesDependencies(rules[1..], objectTypes)
  }

  lemma RuleDependenciesAreIncluded(
    rules: seq<Semantics.NormalizedRule>,
    objectTypes: set<string>,
    index: nat
  )
    requires index < |rules|
    ensures RuleDependencies(rules[index], objectTypes) <=
            RulesDependencies(rules, objectTypes)
    decreases |rules|
  {
    if index != 0 {
      RuleDependenciesAreIncluded(
        rules[1..],
        objectTypes,
        index - 1
      );
    }
  }

  predicate RelationshipsUseKnownObjects(
    objects: seq<Semantics.ObjectRef>,
    relationships: seq<Semantics.Relationship>
  ) {
    forall relationship <- relationships ::
      Semantics.ContainsObject(objects, relationship.resource) &&
      Semantics.ContainsObject(objects, relationship.subject)
  }

  ghost predicate RelevantProjectionEqual(
    dependencies: set<CacheKernel.RelationDependency>,
    left: seq<Semantics.Relationship>,
    right: seq<Semantics.Relationship>
  ) {
    forall relationship: Semantics.Relationship |
      CacheKernel.RelationDependency(
        relationship.resource.typeName,
        relationship.relationName
      ) in dependencies ::
      (relationship in left <==> relationship in right)
  }

  lemma HasRelationshipFrame(
    dependencies: set<CacheKernel.RelationDependency>,
    left: seq<Semantics.Relationship>,
    right: seq<Semantics.Relationship>,
    resource: Semantics.ObjectRef,
    relationName: string,
    subject: Semantics.ObjectRef
  )
    requires RelevantProjectionEqual(dependencies, left, right)
    requires CacheKernel.RelationDependency(
               resource.typeName,
               relationName
             ) in dependencies
    ensures Semantics.HasRelationship(
              left,
              resource,
              relationName,
              subject
            ) <==>
            Semantics.HasRelationship(
              right,
              resource,
              relationName,
              subject
            )
  {
  }

  lemma RelationshipMembershipFrame(
    dependencies: set<CacheKernel.RelationDependency>,
    left: seq<Semantics.Relationship>,
    right: seq<Semantics.Relationship>,
    relationship: Semantics.Relationship
  )
    requires RelevantProjectionEqual(dependencies, left, right)
    requires CacheKernel.RelationDependency(
               relationship.resource.typeName,
               relationship.relationName
             ) in dependencies
    ensures relationship in left <==> relationship in right
  {
  }

  lemma RuleDerivationFrame(
    objects: seq<Semantics.ObjectRef>,
    dependencies: set<CacheKernel.RelationDependency>,
    left: seq<Semantics.Relationship>,
    right: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>,
    rule: Semantics.NormalizedRule,
    grant: Semantics.Grant
  )
    requires RelationshipsUseKnownObjects(objects, left)
    requires RelationshipsUseKnownObjects(objects, right)
    requires RelevantProjectionEqual(dependencies, left, right)
    requires RuleDependencies(
               rule,
               CacheKernel.ObjectTypes(objects)
             ) <= dependencies
    ensures Semantics.RuleDerives(
              rule,
              left,
              grants,
              grant
            ) <==>
            Semantics.RuleDerives(
              rule,
              right,
              grants,
              grant
            )
  {
    match rule {
      case DirectRelationRule(head, relationName, _) =>
        if Semantics.RuleDerives(rule, left, grants, grant) ||
           Semantics.RuleDerives(rule, right, grants, grant) {
          assert grant.resource.typeName == head.resourceType;
          HasRelationshipFrame(
            dependencies,
            left,
            right,
            grant.resource,
            relationName,
            grant.subject
          );
        }
      case SelfPermissionRule(_, _) =>
      case ArrowRelationRule(
        head,
        viaRelation,
        targetRelation,
        _
        ) =>
        if Semantics.RuleDerives(rule, left, grants, grant) {
          var via :| via in left &&
                     via.resource == grant.resource &&
                     via.relationName == viaRelation &&
                     Semantics.HasRelationship(
                       left,
                       via.subject,
                       targetRelation,
                       grant.subject
                     );
          assert via.resource.typeName == head.resourceType;
          RelationshipMembershipFrame(
            dependencies,
            left,
            right,
            via
          );
          assert Semantics.ContainsObject(objects, via.subject);
          assert via.subject.typeName in
                   CacheKernel.ObjectTypes(objects);
          HasRelationshipFrame(
            dependencies,
            left,
            right,
            via.subject,
            targetRelation,
            grant.subject
          );
        }
        if Semantics.RuleDerives(rule, right, grants, grant) {
          var via :| via in right &&
                     via.resource == grant.resource &&
                     via.relationName == viaRelation &&
                     Semantics.HasRelationship(
                       right,
                       via.subject,
                       targetRelation,
                       grant.subject
                     );
          assert via.resource.typeName == head.resourceType;
          RelationshipMembershipFrame(
            dependencies,
            left,
            right,
            via
          );
          assert Semantics.ContainsObject(objects, via.subject);
          assert via.subject.typeName in
                   CacheKernel.ObjectTypes(objects);
          HasRelationshipFrame(
            dependencies,
            left,
            right,
            via.subject,
            targetRelation,
            grant.subject
          );
        }
      case ArrowPermissionRule(head, viaRelation, _) =>
        if Semantics.RuleDerives(rule, left, grants, grant) {
          var via :| via in left &&
                     via.resource == grant.resource &&
                     via.relationName == viaRelation;
          assert via.resource.typeName == head.resourceType;
          RelationshipMembershipFrame(
            dependencies,
            left,
            right,
            via
          );
        }
        if Semantics.RuleDerives(rule, right, grants, grant) {
          var via :| via in right &&
                     via.resource == grant.resource &&
                     via.relationName == viaRelation;
          assert via.resource.typeName == head.resourceType;
          RelationshipMembershipFrame(
            dependencies,
            left,
            right,
            via
          );
        }
    }
  }

  lemma ImmediateConsequencesFrame(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    rules: seq<Semantics.NormalizedRule>,
    dependencies: set<CacheKernel.RelationDependency>,
    left: seq<Semantics.Relationship>,
    right: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>
  )
    requires RelationshipsUseKnownObjects(objects, left)
    requires RelationshipsUseKnownObjects(objects, right)
    requires RelevantProjectionEqual(dependencies, left, right)
    requires RulesDependencies(
               rules,
               CacheKernel.ObjectTypes(objects)
             ) <= dependencies
    ensures Semantics.ImmediateConsequences(
              objects,
              permissions,
              rules,
              left,
              grants
            ) ==
            Semantics.ImmediateConsequences(
              objects,
              permissions,
              rules,
              right,
              grants
            )
  {
    forall grant <- Semantics.GrantUniverse(objects, permissions)
      ensures Semantics.AnyRuleDerives(
                rules,
                left,
                grants,
                grant
              ) <==>
              Semantics.AnyRuleDerives(
                rules,
                right,
                grants,
                grant
              )
    {
      forall index | 0 <= index < |rules|
        ensures Semantics.RuleDerives(
                  rules[index],
                  left,
                  grants,
                  grant
                ) <==>
                Semantics.RuleDerives(
                  rules[index],
                  right,
                  grants,
                  grant
                )
      {
        RuleDependenciesAreIncluded(
          rules,
          CacheKernel.ObjectTypes(objects),
          index
        );
        RuleDerivationFrame(
          objects,
          dependencies,
          left,
          right,
          grants,
          rules[index],
          grant
        );
      }
    }
  }

  lemma ManagedFrameForCompiledRules(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    rules: seq<Semantics.NormalizedRule>,
    dependencies: set<CacheKernel.RelationDependency>,
    leftRelationships: seq<Semantics.Relationship>,
    rightRelationships: seq<Semantics.Relationship>,
    leftGrants: set<Semantics.Grant>,
    rightGrants: set<Semantics.Grant>
  )
    requires RelationshipsUseKnownObjects(objects, leftRelationships)
    requires RelationshipsUseKnownObjects(objects, rightRelationships)
    requires RelevantProjectionEqual(
               dependencies,
               leftRelationships,
               rightRelationships
             )
    requires RulesDependencies(
               rules,
               CacheKernel.ObjectTypes(objects)
             ) <= dependencies
    requires leftGrants <=
             Semantics.GrantUniverse(objects, permissions)
    requires rightGrants <=
             Semantics.GrantUniverse(objects, permissions)
    requires Semantics.ImmediateConsequences(
               objects,
               permissions,
               rules,
               leftRelationships,
               leftGrants
             ) == leftGrants
    requires Semantics.ImmediateConsequences(
               objects,
               permissions,
               rules,
               rightRelationships,
               rightGrants
             ) == rightGrants
    requires forall fixed |
               fixed <= Semantics.GrantUniverse(objects, permissions) &&
               Semantics.ImmediateConsequences(
                 objects,
                 permissions,
                 rules,
                 leftRelationships,
                 fixed
               ) == fixed ::
               leftGrants <= fixed
    requires forall fixed |
               fixed <= Semantics.GrantUniverse(objects, permissions) &&
               Semantics.ImmediateConsequences(
                 objects,
                 permissions,
                 rules,
                 rightRelationships,
                 fixed
               ) == fixed ::
               rightGrants <= fixed
    ensures leftGrants == rightGrants
  {
    ImmediateConsequencesFrame(
      objects,
      permissions,
      rules,
      dependencies,
      leftRelationships,
      rightRelationships,
      leftGrants
    );
    ImmediateConsequencesFrame(
      objects,
      permissions,
      rules,
      dependencies,
      leftRelationships,
      rightRelationships,
      rightGrants
    );
    assert leftGrants <= rightGrants;
    assert rightGrants <= leftGrants;
  }

  function RenderObjectAgainstSelectedSnapshot(
    snapshot: SnapshotOracle.SnapshotView,
    internal: SnapshotOracle.InternalObject
  ): Semantics.ObjectRef
    requires SnapshotOracle.SnapshotWellFormed(snapshot)
    requires internal in snapshot.objects.Values
    ensures RenderObjectAgainstSelectedSnapshot(
              snapshot,
              internal
            ) in snapshot.objects
    ensures snapshot.objects[
            RenderObjectAgainstSelectedSnapshot(
              snapshot,
              internal
            )
            ] == internal
  {
    var external :| external in snapshot.objects &&
                    snapshot.objects[external] == internal;
    external
  }

  function RenderResultsAgainstSelectedSnapshot(
    snapshot: SnapshotOracle.SnapshotView,
    internalResults: seq<SnapshotOracle.InternalObject>
  ): seq<Semantics.ObjectRef>
    requires SnapshotOracle.SnapshotWellFormed(snapshot)
    requires forall internal <- internalResults ::
               internal in snapshot.objects.Values
    decreases |internalResults|
  {
    if |internalResults| == 0 then
      []
    else
      [
        RenderObjectAgainstSelectedSnapshot(
          snapshot,
          internalResults[0]
        )
      ] +
      RenderResultsAgainstSelectedSnapshot(
        snapshot,
        internalResults[1..]
      )
  }

  lemma SelectedSnapshotRenderingRefinesInternalResults(
    snapshot: SnapshotOracle.SnapshotView,
    internalResults: seq<SnapshotOracle.InternalObject>
  )
    requires SnapshotOracle.SnapshotWellFormed(snapshot)
    requires forall internal <- internalResults ::
               internal in snapshot.objects.Values
    ensures |RenderResultsAgainstSelectedSnapshot(
              snapshot,
              internalResults
            )| == |internalResults|
    ensures forall index | 0 <= index < |internalResults| ::
              RenderResultsAgainstSelectedSnapshot(
                snapshot,
                internalResults
              )[index] in snapshot.objects
    ensures forall index | 0 <= index < |internalResults| ::
              var external :=
                RenderResultsAgainstSelectedSnapshot(
                  snapshot,
                  internalResults
                )[index];
              external in snapshot.objects &&
              snapshot.objects[external] == internalResults[index]
    decreases |internalResults|
  {
    if |internalResults| != 0 {
      SelectedSnapshotRenderingRefinesInternalResults(
        snapshot,
        internalResults[1..]
      );
    }
  }

  lemma EqualInternalResultsRenderEqually(
    snapshot: SnapshotOracle.SnapshotView,
    cached: seq<SnapshotOracle.InternalObject>,
    recomputed: seq<SnapshotOracle.InternalObject>
  )
    requires SnapshotOracle.SnapshotWellFormed(snapshot)
    requires cached == recomputed
    requires forall internal <- cached ::
               internal in snapshot.objects.Values
    ensures RenderResultsAgainstSelectedSnapshot(snapshot, cached) ==
            RenderResultsAgainstSelectedSnapshot(snapshot, recomputed)
  {
  }
}

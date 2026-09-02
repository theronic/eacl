include "SnapshotOracle.dfy"

module CurrentCache {
  import Semantics
  import SnapshotOracle

  datatype RelationDependency = RelationDependency(
    resourceType: string,
    relationName: string
  )

  function ObjectTypes(
    objects: seq<Semantics.ObjectRef>
  ): set<string> {
    set item <- objects :: item.typeName
  }

  datatype BasisClass =
    | OrdinaryBasis
    | HistoricalBasis
    | InadmissibleBasis

  predicate ExactTierEligible(
    basisClass: BasisClass,
    completeBasisIdentity: bool
  ) {
    completeBasisIdentity &&
    (basisClass.OrdinaryBasis? || basisClass.HistoricalBasis?)
  }

  predicate ManagedTierEligible(
    basisClass: BasisClass,
    completeBasisIdentity: bool
  ) {
    completeBasisIdentity && basisClass.OrdinaryBasis?
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

  lemma HistoricalBasisCannotUseManagedTier(
    completeBasisIdentity: bool
  )
    ensures !ManagedTierEligible(
              HistoricalBasis,
              completeBasisIdentity
            )
  {
  }

  lemma InadmissibleBasisCannotUseManagedTier(
    completeBasisIdentity: bool
  )
    ensures !ManagedTierEligible(
              InadmissibleBasis,
              completeBasisIdentity
            )
  {
  }

  // Runtime exact locators are opaque adapter tokens, but the portable cache
  // boundary admits only nil, a cross-runtime safe natural, or a bounded
  // nonempty string. Keeping that closed token domain here prevents a proof
  // over strings alone from silently omitting supported numeric/nil locators.
  const MaximumExactLocatorNatural: nat := 9007199254740991
  const MaximumExactLocatorStringUnits: nat := 4096

  datatype ExactLocator =
    | NilExactLocator
    | NaturalExactLocator(naturalValue: nat)
    | StringExactLocator(stringValue: string)

  predicate ValidExactLocator(locator: ExactLocator) {
    match locator
    case NilExactLocator => true
    case NaturalExactLocator(value) =>
      value <= MaximumExactLocatorNatural
    case StringExactLocator(value) =>
      0 < |value| <= MaximumExactLocatorStringUnits
  }

  datatype ExactBasisIdentity =
    | ExactBasisIdentity(
        sourceScope: string,
        sourceLifecycle: string,
        revision: nat,
        exactLocator: ExactLocator,
        backendSnapshotIdentity: string,
        viewKind: string,
        adapterFingerprint: string,
        identityContract: string
      )

  predicate ExactKeySelectsSnapshot(
    snapshotOrder: nat,
    keyBasis: ExactBasisIdentity
  ) {
    keyBasis.revision == snapshotOrder &&
    ValidExactLocator(keyBasis.exactLocator)
  }

  lemma EveryAdmittedExactKeyUsesPortableLocatorDomain(
    snapshotOrder: nat,
    keyBasis: ExactBasisIdentity
  )
    requires ExactKeySelectsSnapshot(snapshotOrder, keyBasis)
    ensures ValidExactLocator(keyBasis.exactLocator)
  {
  }

  predicate ExactCompositeKeyMatches(
    selectedBasis: ExactBasisIdentity,
    keyBasis: ExactBasisIdentity
  ) {
    selectedBasis == keyBasis
  }

  lemma ExactCompositeKeyHitIsSameBasis(
    selectedBasis: ExactBasisIdentity,
    keyBasis: ExactBasisIdentity
  )
    requires ExactCompositeKeyMatches(
               selectedBasis,
               keyBasis
             )
    ensures selectedBasis == keyBasis
  {
  }

  lemma NumericRevisionAloneCannotEstablishExactIdentity(
    selectedBasis: ExactBasisIdentity,
    keyBasis: ExactBasisIdentity
  )
    requires selectedBasis.revision ==
             keyBasis.revision
    requires selectedBasis != keyBasis
    ensures !ExactCompositeKeyMatches(
              selectedBasis,
              keyBasis
            )
  {
  }

  datatype CompletedAnswerV2<T> = CompletedAnswerV2(
    value: T,
    cacheBasis: string,
    computedRevision: nat,
    computedExactLocator: ExactLocator
  )

  function DirectCompletedAnswer<T>(
    value: T,
    snapshotOrder: nat,
    keyBasis: ExactBasisIdentity
  ): CompletedAnswerV2<T> {
    CompletedAnswerV2(
      value,
      keyBasis.backendSnapshotIdentity,
      snapshotOrder,
      keyBasis.exactLocator
    )
  }

  datatype ExactAnswerOrigin =
    | DirectExactComputation
    | ManagedProofPromotion

  ghost predicate AnswerRefinesSelectedSnapshot<T>(
    answer: CompletedAnswerV2<T>,
    recomputed: T
  ) {
    answer.value == recomputed
  }

  ghost predicate ExactAnswerValidForSelectedSnapshot<T>(
    basisClass: BasisClass,
    snapshotOrder: nat,
    selectedBasis: ExactBasisIdentity,
    keyBasis: ExactBasisIdentity,
    answer: CompletedAnswerV2<T>,
    origin: ExactAnswerOrigin,
    recomputed: T
  ) {
    selectedBasis.revision == snapshotOrder &&
    ExactCompositeKeyMatches(selectedBasis, keyBasis) &&
    ExactKeySelectsSnapshot(snapshotOrder, keyBasis) &&
    ValidExactLocator(answer.computedExactLocator) &&
    AnswerRefinesSelectedSnapshot(answer, recomputed) &&
    match origin
    case DirectExactComputation =>
      ExactTierEligible(basisClass, true) &&
      answer == DirectCompletedAnswer(recomputed, snapshotOrder, keyBasis)
    case ManagedProofPromotion =>
      ManagedTierEligible(basisClass, true) &&
      answer.computedRevision <= snapshotOrder
  }

  lemma ExactAnswerKeyRevisionEqualsSelectedSnapshotOrder<T>(
    basisClass: BasisClass,
    snapshotOrder: nat,
    selectedBasis: ExactBasisIdentity,
    keyBasis: ExactBasisIdentity,
    answer: CompletedAnswerV2<T>,
    origin: ExactAnswerOrigin,
    recomputed: T
  )
    requires ExactAnswerValidForSelectedSnapshot(
               basisClass,
               snapshotOrder,
               selectedBasis,
               keyBasis,
               answer,
               origin,
               recomputed
             )
    ensures keyBasis.revision == snapshotOrder
    ensures keyBasis == selectedBasis
    ensures ValidExactLocator(keyBasis.exactLocator)
  {
  }

  lemma DirectExactAnswerUsesSelectedComputationAnchor<T>(
    basisClass: BasisClass,
    snapshotOrder: nat,
    selectedBasis: ExactBasisIdentity,
    keyBasis: ExactBasisIdentity,
    answer: CompletedAnswerV2<T>,
    recomputed: T
  )
    requires ExactAnswerValidForSelectedSnapshot(
               basisClass,
               snapshotOrder,
               selectedBasis,
               keyBasis,
               answer,
               DirectExactComputation,
               recomputed
             )
    ensures answer.computedRevision == snapshotOrder
    ensures answer.computedExactLocator == keyBasis.exactLocator
    ensures ValidExactLocator(answer.computedExactLocator)
    ensures answer.cacheBasis == keyBasis.backendSnapshotIdentity
    ensures answer == DirectCompletedAnswer(
                        recomputed,
                        snapshotOrder,
                        keyBasis
                      )
  {
  }

  lemma EveryValidatedExactAnswerUsesPortableLocatorDomain<T>(
    basisClass: BasisClass,
    snapshotOrder: nat,
    selectedBasis: ExactBasisIdentity,
    keyBasis: ExactBasisIdentity,
    answer: CompletedAnswerV2<T>,
    origin: ExactAnswerOrigin,
    recomputed: T
  )
    requires ExactAnswerValidForSelectedSnapshot(
               basisClass,
               snapshotOrder,
               selectedBasis,
               keyBasis,
               answer,
               origin,
               recomputed
             )
    ensures ValidExactLocator(answer.computedExactLocator)
  {
  }

  predicate PortableExactAnswerEnvelope<T>(
    snapshotOrder: nat,
    keyBasis: ExactBasisIdentity,
    answer: CompletedAnswerV2<T>
  ) {
    ExactKeySelectsSnapshot(snapshotOrder, keyBasis) &&
    answer.cacheBasis == keyBasis.backendSnapshotIdentity &&
    answer.computedRevision == snapshotOrder &&
    answer.computedExactLocator == keyBasis.exactLocator
  }

  lemma DirectExactAnswerHasPortableAnchor<T>(
    basisClass: BasisClass,
    snapshotOrder: nat,
    selectedBasis: ExactBasisIdentity,
    keyBasis: ExactBasisIdentity,
    answer: CompletedAnswerV2<T>,
    recomputed: T
  )
    requires ExactAnswerValidForSelectedSnapshot(
               basisClass,
               snapshotOrder,
               selectedBasis,
               keyBasis,
               answer,
               DirectExactComputation,
               recomputed
             )
    ensures PortableExactAnswerEnvelope(
              snapshotOrder,
              keyBasis,
              answer
            )
  {
  }

  function PromoteManagedAnswer<T>(
    managed: CompletedAnswerV2<T>
  ): CompletedAnswerV2<T> {
    managed
  }

  lemma ManagedPromotionPreservesImmutableComputationAnchor<T>(
    managed: CompletedAnswerV2<T>
  )
    ensures PromoteManagedAnswer(managed).cacheBasis ==
            managed.cacheBasis
    ensures PromoteManagedAnswer(managed).computedRevision ==
            managed.computedRevision
    ensures PromoteManagedAnswer(managed).computedExactLocator ==
            managed.computedExactLocator
  {
  }

  lemma ValidatedManagedAnswerMayPopulateLaterExactKey<T>(
    basisClass: BasisClass,
    snapshotOrder: nat,
    selectedBasis: ExactBasisIdentity,
    keyBasis: ExactBasisIdentity,
    managed: CompletedAnswerV2<T>,
    recomputed: T
  )
    requires ManagedTierEligible(basisClass, true)
    requires selectedBasis.revision == snapshotOrder
    requires ExactCompositeKeyMatches(selectedBasis, keyBasis)
    requires ExactKeySelectsSnapshot(snapshotOrder, keyBasis)
    requires managed.computedRevision <= snapshotOrder
    requires ValidExactLocator(managed.computedExactLocator)
    requires AnswerRefinesSelectedSnapshot(managed, recomputed)
    ensures ExactAnswerValidForSelectedSnapshot(
              basisClass,
              snapshotOrder,
              selectedBasis,
              keyBasis,
              PromoteManagedAnswer(managed),
              ManagedProofPromotion,
              recomputed
            )
  {
  }

  lemma ManagedPromotionWithDifferentAnchorIsNotPortable<T>(
    basisClass: BasisClass,
    snapshotOrder: nat,
    selectedBasis: ExactBasisIdentity,
    keyBasis: ExactBasisIdentity,
    answer: CompletedAnswerV2<T>,
    recomputed: T
  )
    requires ExactAnswerValidForSelectedSnapshot(
               basisClass,
               snapshotOrder,
               selectedBasis,
               keyBasis,
               answer,
               ManagedProofPromotion,
               recomputed
             )
    requires answer.cacheBasis != keyBasis.backendSnapshotIdentity ||
             answer.computedRevision != snapshotOrder ||
             answer.computedExactLocator != keyBasis.exactLocator
    ensures !PortableExactAnswerEnvelope(
              snapshotOrder,
              keyBasis,
              answer
            )
  {
  }

  lemma ManagedPromotedExactAnswerRequiresOrdinaryBasis<T>(
    basisClass: BasisClass,
    snapshotOrder: nat,
    selectedBasis: ExactBasisIdentity,
    keyBasis: ExactBasisIdentity,
    answer: CompletedAnswerV2<T>,
    recomputed: T
  )
    requires ExactAnswerValidForSelectedSnapshot(
               basisClass,
               snapshotOrder,
               selectedBasis,
               keyBasis,
               answer,
               ManagedProofPromotion,
               recomputed
             )
    ensures basisClass.OrdinaryBasis?
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
  ): set<RelationDependency> {
    match rule
    case DirectRelationRule(head, relationName, _) =>
      {
        RelationDependency(
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
        RelationDependency(
          head.resourceType,
          viaRelation
        )
      } +
      set typeName <- objectTypes ::
        RelationDependency(typeName, targetRelation)
    case ArrowPermissionRule(head, viaRelation, _) =>
      {
        RelationDependency(
          head.resourceType,
          viaRelation
        )
      }
  }

  function RulesDependencies(
    rules: seq<Semantics.NormalizedRule>,
    objectTypes: set<string>
  ): set<RelationDependency>
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
    dependencies: set<RelationDependency>,
    left: seq<Semantics.Relationship>,
    right: seq<Semantics.Relationship>
  ) {
    forall relationship: Semantics.Relationship |
      RelationDependency(
        relationship.resource.typeName,
        relationship.relationName
      ) in dependencies ::
      (relationship in left <==> relationship in right)
  }

  lemma HasRelationshipFrame(
    dependencies: set<RelationDependency>,
    left: seq<Semantics.Relationship>,
    right: seq<Semantics.Relationship>,
    resource: Semantics.ObjectRef,
    relationName: string,
    subject: Semantics.ObjectRef
  )
    requires RelevantProjectionEqual(dependencies, left, right)
    requires RelationDependency(
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
    dependencies: set<RelationDependency>,
    left: seq<Semantics.Relationship>,
    right: seq<Semantics.Relationship>,
    relationship: Semantics.Relationship
  )
    requires RelevantProjectionEqual(dependencies, left, right)
    requires RelationDependency(
               relationship.resource.typeName,
               relationship.relationName
             ) in dependencies
    ensures relationship in left <==> relationship in right
  {
  }

  lemma RuleDerivationFrame(
    objects: seq<Semantics.ObjectRef>,
    dependencies: set<RelationDependency>,
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
               ObjectTypes(objects)
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
                   ObjectTypes(objects);
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
                   ObjectTypes(objects);
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
    dependencies: set<RelationDependency>,
    left: seq<Semantics.Relationship>,
    right: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>
  )
    requires RelationshipsUseKnownObjects(objects, left)
    requires RelationshipsUseKnownObjects(objects, right)
    requires RelevantProjectionEqual(dependencies, left, right)
    requires RulesDependencies(
               rules,
               ObjectTypes(objects)
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
          ObjectTypes(objects),
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
    dependencies: set<RelationDependency>,
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
               ObjectTypes(objects)
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

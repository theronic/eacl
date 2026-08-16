include "IndexedRefinement.dfy"
include "RootDenotation.dfy"

module IndexedRootDenotation {
  import Semantics
  import Indexed = IndexedTraversal
  import Refinement = IndexedRefinement
  import Root = RootDenotation
  import AcyclicEngine

  datatype IndexedRuleBody =
    | RelationBody(
        relationEid: int,
        subjectType: string
      )
    | SelfPermissionBody(
        targetNode: Semantics.PermissionNode
      )
    | ArrowRelationBody(
        viaRelationEid: int,
        intermediateType: string,
        targetRelationEid: int,
        targetSubjectType: string
      )
    | ArrowPermissionBody(
        viaRelationEid: int,
        intermediateType: string,
        targetNode: Semantics.PermissionNode
      )

  function RuleBody(rule: Indexed.IndexedRule): IndexedRuleBody {
    match rule
    case RelationRule(_, relationEid, subjectType) =>
      RelationBody(relationEid, subjectType)
    case SelfPermissionRule(_, targetNode) =>
      SelfPermissionBody(targetNode)
    case ArrowRelationRule(
      _,
      viaRelationEid,
      intermediateType,
      targetRelationEid,
      targetSubjectType
      ) =>
      ArrowRelationBody(
        viaRelationEid,
        intermediateType,
        targetRelationEid,
        targetSubjectType
      )
    case ArrowPermissionRule(
      _,
      viaRelationEid,
      intermediateType,
      targetNode
      ) =>
      ArrowPermissionBody(
        viaRelationEid,
        intermediateType,
        targetNode
      )
  }

  ghost predicate IndexedRootBodiesEquivalent(
    rules: seq<Indexed.IndexedRule>,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  ) {
    left.resourceType == right.resourceType &&
    (forall leftRule <- rules |
            Indexed.RuleHead(leftRule) == left ::
       exists rightRule <- rules ::
         Indexed.RuleHead(rightRule) == right &&
         RuleBody(rightRule) == RuleBody(leftRule)) &&
    (forall rightRule <- rules |
            Indexed.RuleHead(rightRule) == right ::
       exists leftRule <- rules ::
         Indexed.RuleHead(leftRule) == left &&
         RuleBody(leftRule) == RuleBody(rightRule))
  }

  ghost predicate EverySemanticRuleHasIndexedWitness(
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationBindings: seq<Refinement.RelationBinding>
  ) {
    forall normalizedRule <- normalizedRules ::
      exists indexedRule <- indexedRules ::
        Refinement.IndexedRuleRefines(
          indexedRule,
          normalizedRule,
          relationBindings
        )
  }

  ghost predicate CompleteRuleRelationBindings(
    normalizedRules: seq<Semantics.NormalizedRule>,
    relationBindings: seq<Refinement.RelationBinding>
  ) {
    (forall rule <- normalizedRules |
            rule.DirectRelationRule? ::
       exists binding <- relationBindings ::
         Refinement.RelationBindingMatches(
           binding,
           rule.head.resourceType,
           rule.relationName,
           rule.subjectType
         )) &&
    (forall rule <- normalizedRules |
            rule.ArrowRelationRule? ::
       exists viaBinding <- relationBindings,
         targetBinding <- relationBindings ::
         Refinement.RelationBindingMatches(
           viaBinding,
           rule.head.resourceType,
           rule.viaRelation,
           viaBinding.relation.subjectType
         ) &&
         Refinement.RelationBindingMatches(
           targetBinding,
           viaBinding.relation.subjectType,
           rule.targetRelation,
           rule.subjectType
         )) &&
    (forall rule <- normalizedRules |
            rule.ArrowPermissionRule? ::
       exists viaBinding <- relationBindings ::
         Refinement.RelationBindingMatches(
           viaBinding,
           rule.head.resourceType,
           rule.viaRelation,
           viaBinding.relation.subjectType
         ))
  }

  lemma IndexedRefinementPreservesHead(
    indexedRule: Indexed.IndexedRule,
    normalizedRule: Semantics.NormalizedRule,
    relationBindings: seq<Refinement.RelationBinding>
  )
    requires Refinement.IndexedRuleRefines(
               indexedRule,
               normalizedRule,
               relationBindings
             )
    ensures Indexed.RuleHead(indexedRule) ==
            Root.RuleHead(normalizedRule)
  {
  }

  lemma CompleteBindingsGiveRuleWitness(
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationBindings: seq<Refinement.RelationBinding>,
    normalizedRule: Semantics.NormalizedRule
  )
    requires Refinement.ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires CompleteRuleRelationBindings(
               normalizedRules,
               relationBindings
             )
    requires normalizedRule in normalizedRules
    ensures exists indexedRule <- indexedRules ::
              Refinement.IndexedRuleRefines(
                indexedRule,
                normalizedRule,
                relationBindings
              )
  {
    match normalizedRule
    case DirectRelationRule(head, relationName, subjectType) => {
      var binding :|
        binding in relationBindings &&
        Refinement.RelationBindingMatches(
          binding,
          head.resourceType,
          relationName,
          subjectType
        );
      assert Indexed.RelationRule(
          head,
          binding.eid,
          subjectType
        ) in indexedRules;
    }
    case SelfPermissionRule(head, sourcePermission) => {
      assert Indexed.SelfPermissionRule(
          head,
          Semantics.PermissionNode(
            head.resourceType,
            sourcePermission
          )
        ) in indexedRules;
    }
    case ArrowRelationRule(
      head,
        viaRelation,
        targetRelation,
        subjectType
        ) => {
      var viaBinding, targetBinding :|
        viaBinding in relationBindings &&
        targetBinding in relationBindings &&
        Refinement.RelationBindingMatches(
          viaBinding,
          head.resourceType,
          viaRelation,
          viaBinding.relation.subjectType
        ) &&
        Refinement.RelationBindingMatches(
          targetBinding,
          viaBinding.relation.subjectType,
          targetRelation,
          subjectType
        );
      assert Indexed.ArrowRelationRule(
          head,
          viaBinding.eid,
          viaBinding.relation.subjectType,
          targetBinding.eid,
          subjectType
        ) in indexedRules;
    }
    case ArrowPermissionRule(
      head,
        viaRelation,
        targetPermission
        ) => {
      var viaBinding :|
        viaBinding in relationBindings &&
        Refinement.RelationBindingMatches(
          viaBinding,
          head.resourceType,
          viaRelation,
          viaBinding.relation.subjectType
        );
      assert Indexed.ArrowPermissionRule(
          head,
          viaBinding.eid,
          viaBinding.relation.subjectType,
          Semantics.PermissionNode(
            viaBinding.relation.subjectType,
            targetPermission
          )
        ) in indexedRules;
    }
  }

  lemma CompleteBindingsGiveEverySemanticRuleWitness(
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationBindings: seq<Refinement.RelationBinding>
  )
    requires Refinement.ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires CompleteRuleRelationBindings(
               normalizedRules,
               relationBindings
             )
    ensures EverySemanticRuleHasIndexedWitness(
              normalizedRules,
              indexedRules,
              relationBindings
            )
  {
    forall normalizedRule <- normalizedRules
      ensures exists indexedRule <- indexedRules ::
                Refinement.IndexedRuleRefines(
                  indexedRule,
                  normalizedRule,
                  relationBindings
                )
    {
      CompleteBindingsGiveRuleWitness(
        normalizedRules,
        indexedRules,
        relationBindings,
        normalizedRule
      );
    }
  }

  lemma NormalizedRuleHasDefinitionWitness(
    definitions: seq<Semantics.RuleDefinition>,
    normalizedRule: Semantics.NormalizedRule
  )
    requires normalizedRule in Semantics.Normalize(definitions)
    ensures exists definition <- definitions ::
              Semantics.NormalizeDefinition(definition) ==
              normalizedRule
    decreases |definitions|
  {
    if |definitions| != 0 &&
       Semantics.NormalizeDefinition(definitions[0]) !=
       normalizedRule {
      NormalizedRuleHasDefinitionWitness(
        definitions[1..],
        normalizedRule
      );
    }
  }

  lemma ValidDefinitionHasCompleteRelationBindings(
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    definition: Semantics.RuleDefinition,
    relationBindings: seq<Refinement.RelationBinding>
  )
    requires Semantics.ValidRule(
               relations,
               permissions,
               definition
             )
    requires Refinement.ExactRelationCatalog(
               relations,
               relationBindings
             )
    ensures var rule :=
              Semantics.NormalizeDefinition(definition);
            rule.DirectRelationRule? ==>
              exists binding <- relationBindings ::
                Refinement.RelationBindingMatches(
                  binding,
                  rule.head.resourceType,
                  rule.relationName,
                  rule.subjectType
                )
    ensures var rule :=
              Semantics.NormalizeDefinition(definition);
            rule.ArrowRelationRule? ==>
              exists viaBinding <- relationBindings,
                targetBinding <- relationBindings ::
                Refinement.RelationBindingMatches(
                  viaBinding,
                  rule.head.resourceType,
                  rule.viaRelation,
                  viaBinding.relation.subjectType
                ) &&
                Refinement.RelationBindingMatches(
                  targetBinding,
                  viaBinding.relation.subjectType,
                  rule.targetRelation,
                  rule.subjectType
                )
    ensures var rule :=
              Semantics.NormalizeDefinition(definition);
            rule.ArrowPermissionRule? ==>
              exists viaBinding <- relationBindings ::
                Refinement.RelationBindingMatches(
                  viaBinding,
                  rule.head.resourceType,
                  rule.viaRelation,
                  viaBinding.relation.subjectType
                )
  {
    match definition
    case DirectRelation(head, relationName, subjectType) => {
      var relation :|
        relation in relations &&
        relation ==
        Semantics.RelationNode(
          head.resourceType,
          relationName,
          subjectType
        );
      var binding :|
        binding in relationBindings &&
        binding.relation == relation;
    }
    case SelfPermission(_, _) => {
    }
    case ArrowRelation(
      head,
        viaRelation,
        targetRelation,
        subjectType
        ) => {
      var via :|
        via in relations &&
        via.resourceType == head.resourceType &&
        via.relationName == viaRelation &&
        Semantics.ContainsRelation(
          relations,
          via.subjectType,
          targetRelation,
          subjectType
        );
      var target :|
        target in relations &&
        target ==
        Semantics.RelationNode(
          via.subjectType,
          targetRelation,
          subjectType
        );
      var viaBinding :|
        viaBinding in relationBindings &&
        viaBinding.relation == via;
      var targetBinding :|
        targetBinding in relationBindings &&
        targetBinding.relation == target;
    }
    case ArrowPermission(
      head,
        viaRelation,
        _
        ) => {
      var via :|
        via in relations &&
        via.resourceType == head.resourceType &&
        via.relationName == viaRelation;
      var viaBinding :|
        viaBinding in relationBindings &&
        viaBinding.relation == via;
    }
  }

  lemma WellFormedSchemaProvidesCompleteRuleBindings(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    relationBindings: seq<Refinement.RelationBinding>
  )
    requires Semantics.WellFormedSchema(
               objects,
               relations,
               permissions,
               definitions,
               relationships
             )
    requires Refinement.ExactRelationCatalog(
               relations,
               relationBindings
             )
    ensures CompleteRuleRelationBindings(
              Semantics.Normalize(definitions),
              relationBindings
            )
  {
    forall normalizedRule <- Semantics.Normalize(definitions)
      ensures normalizedRule.DirectRelationRule? ==>
                exists binding <- relationBindings ::
                  Refinement.RelationBindingMatches(
                    binding,
                    normalizedRule.head.resourceType,
                    normalizedRule.relationName,
                    normalizedRule.subjectType
                  )
      ensures normalizedRule.ArrowRelationRule? ==>
                exists viaBinding <- relationBindings,
                  targetBinding <- relationBindings ::
                  Refinement.RelationBindingMatches(
                    viaBinding,
                    normalizedRule.head.resourceType,
                    normalizedRule.viaRelation,
                    viaBinding.relation.subjectType
                  ) &&
                  Refinement.RelationBindingMatches(
                    targetBinding,
                    viaBinding.relation.subjectType,
                    normalizedRule.targetRelation,
                    normalizedRule.subjectType
                  )
      ensures normalizedRule.ArrowPermissionRule? ==>
                exists viaBinding <- relationBindings ::
                  Refinement.RelationBindingMatches(
                    viaBinding,
                    normalizedRule.head.resourceType,
                    normalizedRule.viaRelation,
                    viaBinding.relation.subjectType
                  )
    {
      NormalizedRuleHasDefinitionWitness(
        definitions,
        normalizedRule
      );
      var definition :|
        definition in definitions &&
        Semantics.NormalizeDefinition(definition) ==
        normalizedRule;
      var definitionIndex :|
        0 <= definitionIndex < |definitions| &&
        definitions[definitionIndex] == definition;
      ValidDefinitionHasCompleteRelationBindings(
        relations,
        permissions,
        definition,
        relationBindings
      );
    }
  }

  lemma {:isolate_assertions}
    EqualIndexedBodiesRefineEqualSemanticBodies(
    leftIndexed: Indexed.IndexedRule,
    rightIndexed: Indexed.IndexedRule,
    leftNormalized: Semantics.NormalizedRule,
    rightNormalized: Semantics.NormalizedRule,
    relationBindings: seq<Refinement.RelationBinding>
  )
    requires Refinement.UniqueRelationBindingEids(relationBindings)
    requires RuleBody(leftIndexed) == RuleBody(rightIndexed)
    requires Refinement.IndexedRuleRefines(
               leftIndexed,
               leftNormalized,
               relationBindings
             )
    requires Refinement.IndexedRuleRefines(
               rightIndexed,
               rightNormalized,
               relationBindings
             )
    ensures Root.RuleBody(leftNormalized) ==
            Root.RuleBody(rightNormalized)
  {
    match leftIndexed
    case RelationRule(_, _, _) => {
      var leftBinding :|
        leftBinding in relationBindings &&
        leftBinding.eid == leftIndexed.relationEid &&
        Refinement.RelationBindingMatches(
          leftBinding,
          leftNormalized.head.resourceType,
          leftNormalized.relationName,
          leftNormalized.subjectType
        );
      var rightBinding :|
        rightBinding in relationBindings &&
        rightBinding.eid == rightIndexed.relationEid &&
        Refinement.RelationBindingMatches(
          rightBinding,
          rightNormalized.head.resourceType,
          rightNormalized.relationName,
          rightNormalized.subjectType
        );
      Refinement.UniqueRelationEidIdentifiesBinding(
        relationBindings,
        leftBinding,
        rightBinding
      );
    }
    case SelfPermissionRule(_, _) => {
    }
    case ArrowRelationRule(_, _, _, _, _) => {
      var leftVia :|
        leftVia in relationBindings &&
        leftVia.eid == leftIndexed.viaRelationEid &&
        Refinement.RelationBindingMatches(
          leftVia,
          leftNormalized.head.resourceType,
          leftNormalized.viaRelation,
          leftIndexed.intermediateType
        );
      var rightVia :|
        rightVia in relationBindings &&
        rightVia.eid == rightIndexed.viaRelationEid &&
        Refinement.RelationBindingMatches(
          rightVia,
          rightNormalized.head.resourceType,
          rightNormalized.viaRelation,
          rightIndexed.intermediateType
        );
      Refinement.UniqueRelationEidIdentifiesBinding(
        relationBindings,
        leftVia,
        rightVia
      );
      var leftTarget :|
        leftTarget in relationBindings &&
        leftTarget.eid == leftIndexed.targetRelationEid &&
        Refinement.RelationBindingMatches(
          leftTarget,
          leftIndexed.intermediateType,
          leftNormalized.targetRelation,
          leftNormalized.subjectType
        );
      var rightTarget :|
        rightTarget in relationBindings &&
        rightTarget.eid == rightIndexed.targetRelationEid &&
        Refinement.RelationBindingMatches(
          rightTarget,
          rightIndexed.intermediateType,
          rightNormalized.targetRelation,
          rightNormalized.subjectType
        );
      Refinement.UniqueRelationEidIdentifiesBinding(
        relationBindings,
        leftTarget,
        rightTarget
      );
    }
    case ArrowPermissionRule(_, _, _, _) => {
      var leftVia :|
        leftVia in relationBindings &&
        leftVia.eid == leftIndexed.viaRelationEid &&
        Refinement.RelationBindingMatches(
          leftVia,
          leftNormalized.head.resourceType,
          leftNormalized.viaRelation,
          leftIndexed.intermediateType
        );
      var rightVia :|
        rightVia in relationBindings &&
        rightVia.eid == rightIndexed.viaRelationEid &&
        Refinement.RelationBindingMatches(
          rightVia,
          rightNormalized.head.resourceType,
          rightNormalized.viaRelation,
          rightIndexed.intermediateType
        );
      Refinement.UniqueRelationEidIdentifiesBinding(
        relationBindings,
        leftVia,
        rightVia
      );
    }
  }

  lemma {:isolate_assertions}
    IndexedRootEquivalenceImpliesSemanticOneWay(
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationBindings: seq<Refinement.RelationBinding>,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode,
    leftNormalized: Semantics.NormalizedRule
  )
    requires Refinement.UniqueRelationBindingEids(relationBindings)
    requires Refinement.ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires CompleteRuleRelationBindings(
               normalizedRules,
               relationBindings
             )
    requires IndexedRootBodiesEquivalent(
               indexedRules,
               left,
               right
             )
    requires leftNormalized in normalizedRules
    requires Root.RuleHead(leftNormalized) == left
    ensures exists rightNormalized <- normalizedRules ::
              Root.RuleHead(rightNormalized) == right &&
              Root.RuleBody(rightNormalized) ==
              Root.RuleBody(leftNormalized)
  {
    CompleteBindingsGiveEverySemanticRuleWitness(
      normalizedRules,
      indexedRules,
      relationBindings
    );
    var leftIndexed :|
      leftIndexed in indexedRules &&
      Refinement.IndexedRuleRefines(
        leftIndexed,
        leftNormalized,
        relationBindings
      );
    IndexedRefinementPreservesHead(
      leftIndexed,
      leftNormalized,
      relationBindings
    );
    var rightIndexed :|
      rightIndexed in indexedRules &&
      Indexed.RuleHead(rightIndexed) == right &&
      RuleBody(rightIndexed) == RuleBody(leftIndexed);
    var rightNormalized :|
      rightNormalized in normalizedRules &&
      Refinement.IndexedRuleRefines(
        rightIndexed,
        rightNormalized,
        relationBindings
      );
    IndexedRefinementPreservesHead(
      rightIndexed,
      rightNormalized,
      relationBindings
    );
    EqualIndexedBodiesRefineEqualSemanticBodies(
      leftIndexed,
      rightIndexed,
      leftNormalized,
      rightNormalized,
      relationBindings
    );
  }

  lemma IndexedRootBodiesEquivalenceIsSymmetric(
    rules: seq<Indexed.IndexedRule>,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  )
    requires IndexedRootBodiesEquivalent(rules, left, right)
    ensures IndexedRootBodiesEquivalent(rules, right, left)
  {
  }

  lemma IndexedRootIdentityImpliesSemanticRootEquivalence(
    normalizedRules: seq<Semantics.NormalizedRule>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationBindings: seq<Refinement.RelationBinding>,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  )
    requires Refinement.UniqueRelationBindingEids(relationBindings)
    requires Refinement.ExactCompiledRules(
               normalizedRules,
               indexedRules,
               relationBindings
             )
    requires CompleteRuleRelationBindings(
               normalizedRules,
               relationBindings
             )
    requires IndexedRootBodiesEquivalent(
               indexedRules,
               left,
               right
             )
    ensures Root.RootRuleBodiesEquivalent(
              normalizedRules,
              left,
              right
            )
  {
    forall leftNormalized <- normalizedRules |
           Root.RuleHead(leftNormalized) == left
      ensures exists rightNormalized <- normalizedRules ::
                Root.RuleHead(rightNormalized) == right &&
                Root.RuleBody(rightNormalized) ==
                Root.RuleBody(leftNormalized)
    {
      IndexedRootEquivalenceImpliesSemanticOneWay(
        normalizedRules,
        indexedRules,
        relationBindings,
        left,
        right,
        leftNormalized
      );
    }

    IndexedRootBodiesEquivalenceIsSymmetric(
      indexedRules,
      left,
      right
    );
    forall rightNormalized <- normalizedRules |
           Root.RuleHead(rightNormalized) == right
      ensures exists leftNormalized <- normalizedRules ::
                Root.RuleHead(leftNormalized) == left &&
                Root.RuleBody(leftNormalized) ==
                Root.RuleBody(rightNormalized)
    {
      IndexedRootEquivalenceImpliesSemanticOneWay(
        normalizedRules,
        indexedRules,
        relationBindings,
        right,
        left,
        rightNormalized
      );
    }
  }

  lemma CertifiedIndexedRootIdentityImpliesEqualSemanticDenotation(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationBindings: seq<Refinement.RelationBinding>,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  )
    requires Semantics.WellFormedSchema(
               objects,
               relations,
               permissions,
               definitions,
               relationships
             )
    requires Refinement.ExactRelationCatalog(
               relations,
               relationBindings
             )
    requires Refinement.ExactCompiledRules(
               Semantics.Normalize(definitions),
               indexedRules,
               relationBindings
             )
    requires IndexedRootBodiesEquivalent(
               indexedRules,
               left,
               right
             )
    ensures Root.RootRuleBodiesEquivalent(
              Semantics.Normalize(definitions),
              left,
              right
            )
  {
    WellFormedSchemaProvidesCompleteRuleBindings(
      objects,
      relations,
      permissions,
      definitions,
      relationships,
      relationBindings
    );
    IndexedRootIdentityImpliesSemanticRootEquivalence(
      Semantics.Normalize(definitions),
      indexedRules,
      relationBindings,
      left,
      right
    );
  }

  ghost method CertifiedEqualIndexedRootBodiesHaveEqualDenotations(
    objects: seq<Semantics.ObjectRef>,
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    indexedRules: seq<Indexed.IndexedRule>,
    relationBindings: seq<Refinement.RelationBinding>,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  ) returns (grants: set<Semantics.Grant>)
    requires Semantics.WellFormedSchema(
               objects,
               relations,
               permissions,
               definitions,
               relationships
             )
    requires Refinement.ExactRelationCatalog(
               relations,
               relationBindings
             )
    requires Refinement.ExactCompiledRules(
               Semantics.Normalize(definitions),
               indexedRules,
               relationBindings
             )
    requires IndexedRootBodiesEquivalent(
               indexedRules,
               left,
               right
             )
    requires Semantics.ContainsPermission(
               permissions,
               left.resourceType,
               left.permissionName
             )
    requires Semantics.ContainsPermission(
               permissions,
               right.resourceType,
               right.permissionName
             )
    ensures AcyclicEngine.LeastFixedPoint(
              objects,
              permissions,
              definitions,
              relationships,
              grants
            )
    ensures Root.RootGrantsEquivalent(grants, left, right)
    ensures forall subject: Semantics.ObjectRef,
              resource: Semantics.ObjectRef ::
              AcyclicEngine.SemanticallyAuthorized(
                objects,
                permissions,
                definitions,
                relationships,
                Semantics.Grant(subject, left, resource)
              )
              <==>
              AcyclicEngine.SemanticallyAuthorized(
                objects,
                permissions,
                definitions,
                relationships,
                Semantics.Grant(subject, right, resource)
              )
  {
    CertifiedIndexedRootIdentityImpliesEqualSemanticDenotation(
      objects,
      relations,
      permissions,
      definitions,
      relationships,
      indexedRules,
      relationBindings,
      left,
      right
    );
    grants :=
      Root.EquivalentRuleBodiesHaveEqualSemanticDenotations(
        objects,
        permissions,
        definitions,
        relationships,
        left,
        right
      );
  }
}

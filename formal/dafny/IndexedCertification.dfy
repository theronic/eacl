include "IndexedRefinement.dfy"

module IndexedCertification {
  import Semantics
  import Indexed = IndexedTraversal
  import Refinement = IndexedRefinement

  datatype PlanCertificationError =
    | InvalidRelationCatalog
    | InvalidIndexedRule
    | DuplicateIndexedRule
    | PermissionOpenRule
    | CompiledRuleMismatch
    | InvalidSeedRule
    | DuplicateSeedRule
    | SeedBucketMismatch

  datatype PlanCertification =
    | PlanCertified
    | PlanRejected(error: PlanCertificationError)

  function RelationMatches(
    binding: Refinement.RelationBinding,
    resourceType: string,
    relationName: string,
    subjectType: string
  ): bool {
    binding.relation ==
    Semantics.RelationNode(
      resourceType,
      relationName,
      subjectType
    )
  }

  function DirectRules(
    head: Semantics.PermissionNode,
    relationName: string,
    subjectType: string,
    bindings: seq<Refinement.RelationBinding>
  ): seq<Indexed.IndexedRule>
    decreases |bindings|
  {
    if |bindings| == 0 then
      []
    else
      var binding := bindings[0];
      var rest :=
        DirectRules(
          head,
          relationName,
          subjectType,
          bindings[1..]
        );
      if RelationMatches(
           binding,
           head.resourceType,
           relationName,
           subjectType
         )
      then
        [Indexed.RelationRule(
           head,
           binding.eid,
           subjectType
         )] + rest
      else rest
  }

  function ArrowPermissionRules(
    head: Semantics.PermissionNode,
    viaRelation: string,
    targetPermission: string,
    bindings: seq<Refinement.RelationBinding>
  ): seq<Indexed.IndexedRule>
    decreases |bindings|
  {
    if |bindings| == 0 then
      []
    else
      var binding := bindings[0];
      var rest :=
        ArrowPermissionRules(
          head,
          viaRelation,
          targetPermission,
          bindings[1..]
        );
      if binding.relation.resourceType == head.resourceType &&
         binding.relation.relationName == viaRelation
      then
        [Indexed.ArrowPermissionRule(
           head,
           binding.eid,
           binding.relation.subjectType,
           Semantics.PermissionNode(
             binding.relation.subjectType,
             targetPermission
           )
         )] + rest
      else rest
  }

  function ArrowRelationTargets(
    head: Semantics.PermissionNode,
    viaBinding: Refinement.RelationBinding,
    targetRelation: string,
    subjectType: string,
    bindings: seq<Refinement.RelationBinding>
  ): seq<Indexed.IndexedRule>
    decreases |bindings|
  {
    if |bindings| == 0 then
      []
    else
      var targetBinding := bindings[0];
      var rest :=
        ArrowRelationTargets(
          head,
          viaBinding,
          targetRelation,
          subjectType,
          bindings[1..]
        );
      if RelationMatches(
           targetBinding,
           viaBinding.relation.subjectType,
           targetRelation,
           subjectType
         )
      then
        [Indexed.ArrowRelationRule(
           head,
           viaBinding.eid,
           viaBinding.relation.subjectType,
           targetBinding.eid,
           subjectType
         )] + rest
      else rest
  }

  function ArrowRelationRules(
    head: Semantics.PermissionNode,
    viaRelation: string,
    targetRelation: string,
    subjectType: string,
    bindings: seq<Refinement.RelationBinding>,
    allBindings: seq<Refinement.RelationBinding>
  ): seq<Indexed.IndexedRule>
    decreases |bindings|
  {
    if |bindings| == 0 then
      []
    else
      var viaBinding := bindings[0];
      var rest :=
        ArrowRelationRules(
          head,
          viaRelation,
          targetRelation,
          subjectType,
          bindings[1..],
          allBindings
        );
      if viaBinding.relation.resourceType == head.resourceType &&
         viaBinding.relation.relationName == viaRelation
      then
        ArrowRelationTargets(
          head,
          viaBinding,
          targetRelation,
          subjectType,
          allBindings
        ) + rest
      else rest
  }

  function CompileNormalizedRule(
    rule: Semantics.NormalizedRule,
    bindings: seq<Refinement.RelationBinding>
  ): seq<Indexed.IndexedRule>
  {
    match rule
    case DirectRelationRule(head, relationName, subjectType) =>
      DirectRules(head, relationName, subjectType, bindings)
    case SelfPermissionRule(head, sourcePermission) =>
      [Indexed.SelfPermissionRule(
         head,
         Semantics.PermissionNode(
           head.resourceType,
           sourcePermission
         )
       )]
    case ArrowRelationRule(
      head,
      viaRelation,
      targetRelation,
      subjectType
      ) =>
      ArrowRelationRules(
        head,
        viaRelation,
        targetRelation,
        subjectType,
        bindings,
        bindings
      )
    case ArrowPermissionRule(
      head,
      viaRelation,
      targetPermission
      ) =>
      ArrowPermissionRules(
        head,
        viaRelation,
        targetPermission,
        bindings
      )
  }

  function CanonicalIndexedRules(
    definitions: seq<Semantics.RuleDefinition>,
    bindings: seq<Refinement.RelationBinding>
  ): seq<Indexed.IndexedRule>
    decreases |definitions|
  {
    if |definitions| == 0 then
      []
    else
      CompileNormalizedRule(
        Semantics.NormalizeDefinition(definitions[0]),
        bindings
      ) +
      CanonicalIndexedRules(definitions[1..], bindings)
  }

  function SequenceContainsRule(
    rules: seq<Indexed.IndexedRule>,
    candidate: Indexed.IndexedRule
  ): bool
    decreases |rules|
  {
    if |rules| == 0 then
      false
    else if rules[0] == candidate then
      true
    else SequenceContainsRule(rules[1..], candidate)
  }

  function RuleSetIncluded(
    candidates: seq<Indexed.IndexedRule>,
    expected: seq<Indexed.IndexedRule>
  ): bool
    decreases |candidates|
  {
    if |candidates| == 0 then
      true
    else
      SequenceContainsRule(expected, candidates[0]) &&
      RuleSetIncluded(candidates[1..], expected)
  }

  function SameRuleSet(
    left: seq<Indexed.IndexedRule>,
    right: seq<Indexed.IndexedRule>
  ): bool {
    RuleSetIncluded(left, right) &&
    RuleSetIncluded(right, left)
  }

  lemma SequenceContainsRuleIffMembership(
    rules: seq<Indexed.IndexedRule>,
    candidate: Indexed.IndexedRule
  )
    ensures SequenceContainsRule(rules, candidate) <==>
            candidate in rules
    decreases |rules|
  {
    if |rules| != 0 && rules[0] != candidate {
      SequenceContainsRuleIffMembership(rules[1..], candidate);
    }
  }

  lemma RuleSetIncludedIffSubset(
    candidates: seq<Indexed.IndexedRule>,
    expected: seq<Indexed.IndexedRule>
  )
    ensures RuleSetIncluded(candidates, expected) <==>
            (forall candidate <- candidates ::
               candidate in expected)
    decreases |candidates|
  {
    if |candidates| != 0 {
      SequenceContainsRuleIffMembership(
        expected,
        candidates[0]
      );
      RuleSetIncludedIffSubset(candidates[1..], expected);
    }
  }

  lemma SameRuleSetIffMembership(
    left: seq<Indexed.IndexedRule>,
    right: seq<Indexed.IndexedRule>
  )
    ensures SameRuleSet(left, right) ==>
              (forall candidate: Indexed.IndexedRule ::
                 candidate in left <==> candidate in right)
  {
    RuleSetIncludedIffSubset(left, right);
    RuleSetIncludedIffSubset(right, left);
  }

  function UniqueRules(
    rules: seq<Indexed.IndexedRule>
  ): bool
    decreases |rules|
  {
    if |rules| == 0 then
      true
    else
      !SequenceContainsRule(rules[1..], rules[0]) &&
      UniqueRules(rules[1..])
  }

  lemma UniqueRulesImpliesRefinementUniqueness(
    rules: seq<Indexed.IndexedRule>
  )
    requires UniqueRules(rules)
    ensures Refinement.UniqueIndexedRules(rules)
    decreases |rules|
  {
    if |rules| != 0 {
      SequenceContainsRuleIffMembership(
        rules[1..],
        rules[0]
      );
      UniqueRulesImpliesRefinementUniqueness(rules[1..]);
    }
  }

  function ValidRule(
    rule: Indexed.IndexedRule
  ): bool {
    Indexed.ValidIndexedRule(rule)
  }

  function ValidRules(
    rules: seq<Indexed.IndexedRule>
  ): bool
    decreases |rules|
  {
    if |rules| == 0 then
      true
    else ValidRule(rules[0]) && ValidRules(rules[1..])
  }

  function PermissionClosedRule(
    rule: Indexed.IndexedRule,
    permissions: seq<Semantics.PermissionNode>
  ): bool {
    rule.head in permissions &&
    (!rule.SelfPermissionRule? ||
     rule.targetNode in permissions) &&
    (!rule.ArrowPermissionRule? ||
     rule.targetNode in permissions)
  }

  function PermissionClosedRules(
    rules: seq<Indexed.IndexedRule>,
    permissions: seq<Semantics.PermissionNode>
  ): bool
    decreases |rules|
  {
    if |rules| == 0 then
      true
    else
      PermissionClosedRule(rules[0], permissions) &&
      PermissionClosedRules(rules[1..], permissions)
  }

  function EligibleSeedRules(
    rules: seq<Indexed.IndexedRule>,
    subjectType: string
  ): seq<Indexed.IndexedRule>
    decreases |rules|
  {
    if |rules| == 0 then
      []
    else
      var rest := EligibleSeedRules(rules[1..], subjectType);
      if Refinement.EligibleForwardSeed(rules[0], subjectType)
      then [rules[0]] + rest
      else rest
  }

  function SequenceContainsRelationBinding(
    bindings: seq<Refinement.RelationBinding>,
    candidate: Refinement.RelationBinding
  ): bool
    decreases |bindings|
  {
    if |bindings| == 0 then
      false
    else if bindings[0] == candidate then
      true
    else
      SequenceContainsRelationBinding(bindings[1..], candidate)
  }

  function UniqueRelationBindingEids(
    bindings: seq<Refinement.RelationBinding>
  ): bool
    decreases |bindings|
  {
    if |bindings| == 0 then
      true
    else
      (forall binding <- bindings[1..] ::
         binding.eid != bindings[0].eid) &&
      UniqueRelationBindingEids(bindings[1..])
  }

  function UniqueBoundRelations(
    bindings: seq<Refinement.RelationBinding>
  ): bool
    decreases |bindings|
  {
    if |bindings| == 0 then
      true
    else
      (forall binding <- bindings[1..] ::
         binding.relation != bindings[0].relation) &&
      UniqueBoundRelations(bindings[1..])
  }

  function AllBindingsInRelations(
    relations: seq<Semantics.RelationNode>,
    bindings: seq<Refinement.RelationBinding>
  ): bool
    decreases |bindings|
  {
    if |bindings| == 0 then
      true
    else
      0 <= bindings[0].eid &&
      bindings[0].relation in relations &&
      AllBindingsInRelations(relations, bindings[1..])
  }

  function AllRelationsBound(
    relations: seq<Semantics.RelationNode>,
    bindings: seq<Refinement.RelationBinding>
  ): bool
    decreases |relations|
  {
    if |relations| == 0 then
      true
    else
      (exists binding <- bindings ::
         binding.relation == relations[0]) &&
      AllRelationsBound(relations[1..], bindings)
  }

  function ExactRelationCatalog(
    relations: seq<Semantics.RelationNode>,
    bindings: seq<Refinement.RelationBinding>
  ): bool {
    UniqueRelationBindingEids(bindings) &&
    UniqueBoundRelations(bindings) &&
    AllBindingsInRelations(relations, bindings) &&
    AllRelationsBound(relations, bindings)
  }

  lemma DirectRulesHaveSemanticWitness(
    head: Semantics.PermissionNode,
    relationName: string,
    subjectType: string,
    bindings: seq<Refinement.RelationBinding>,
    candidate: Indexed.IndexedRule
  )
    requires candidate in
               DirectRules(
                 head,
                 relationName,
                 subjectType,
                 bindings
               )
    ensures Refinement.IndexedRuleRefines(
              candidate,
              Semantics.DirectRelationRule(
                head,
                relationName,
                subjectType
              ),
              bindings
            )
    decreases |bindings|
  {
    var first := bindings[0];
    if RelationMatches(
        first,
        head.resourceType,
        relationName,
        subjectType
      ) &&
       candidate ==
       Indexed.RelationRule(
         head,
         first.eid,
         subjectType
       ) {
      assert Refinement.RelationBindingMatches(
          first,
          head.resourceType,
          relationName,
          subjectType
        );
    } else {
      DirectRulesHaveSemanticWitness(
        head,
        relationName,
        subjectType,
        bindings[1..],
        candidate
      );
    }
  }

  lemma DirectExpectedRuleIsCanonical(
    head: Semantics.PermissionNode,
    relationName: string,
    subjectType: string,
    bindings: seq<Refinement.RelationBinding>,
    binding: Refinement.RelationBinding
  )
    requires binding in bindings
    requires RelationMatches(
               binding,
               head.resourceType,
               relationName,
               subjectType
             )
    ensures Indexed.RelationRule(
              head,
              binding.eid,
              subjectType
            ) in
              DirectRules(
                head,
                relationName,
                subjectType,
                bindings
              )
    decreases |bindings|
  {
    if bindings[0] != binding {
      DirectExpectedRuleIsCanonical(
        head,
        relationName,
        subjectType,
        bindings[1..],
        binding
      );
    }
  }

  lemma ArrowPermissionRulesHaveSemanticWitness(
    head: Semantics.PermissionNode,
    viaRelation: string,
    targetPermission: string,
    bindings: seq<Refinement.RelationBinding>,
    candidate: Indexed.IndexedRule
  )
    requires candidate in
               ArrowPermissionRules(
                 head,
                 viaRelation,
                 targetPermission,
                 bindings
               )
    ensures Refinement.IndexedRuleRefines(
              candidate,
              Semantics.ArrowPermissionRule(
                head,
                viaRelation,
                targetPermission
              ),
              bindings
            )
    decreases |bindings|
  {
    var first := bindings[0];
    if first.relation.resourceType == head.resourceType &&
       first.relation.relationName == viaRelation &&
       candidate ==
       Indexed.ArrowPermissionRule(
         head,
         first.eid,
         first.relation.subjectType,
         Semantics.PermissionNode(
           first.relation.subjectType,
           targetPermission
         )
       ) {
      assert Refinement.RelationBindingMatches(
          first,
          head.resourceType,
          viaRelation,
          first.relation.subjectType
        );
    } else {
      ArrowPermissionRulesHaveSemanticWitness(
        head,
        viaRelation,
        targetPermission,
        bindings[1..],
        candidate
      );
    }
  }

  lemma ArrowPermissionExpectedRuleIsCanonical(
    head: Semantics.PermissionNode,
    viaRelation: string,
    targetPermission: string,
    bindings: seq<Refinement.RelationBinding>,
    binding: Refinement.RelationBinding
  )
    requires binding in bindings
    requires RelationMatches(
               binding,
               head.resourceType,
               viaRelation,
               binding.relation.subjectType
             )
    ensures Indexed.ArrowPermissionRule(
              head,
              binding.eid,
              binding.relation.subjectType,
              Semantics.PermissionNode(
                binding.relation.subjectType,
                targetPermission
              )
            ) in
              ArrowPermissionRules(
                head,
                viaRelation,
                targetPermission,
                bindings
              )
    decreases |bindings|
  {
    if bindings[0] != binding {
      ArrowPermissionExpectedRuleIsCanonical(
        head,
        viaRelation,
        targetPermission,
        bindings[1..],
        binding
      );
    }
  }

  lemma ArrowRelationTargetsHaveSemanticWitness(
    head: Semantics.PermissionNode,
    viaBinding: Refinement.RelationBinding,
    targetRelation: string,
    subjectType: string,
    targetBindings: seq<Refinement.RelationBinding>,
    allBindings: seq<Refinement.RelationBinding>,
    candidate: Indexed.IndexedRule
  )
    requires viaBinding in allBindings
    requires viaBinding.relation.resourceType == head.resourceType
    requires forall binding <- targetBindings ::
               binding in allBindings
    requires candidate in
               ArrowRelationTargets(
                 head,
                 viaBinding,
                 targetRelation,
                 subjectType,
                 targetBindings
               )
    ensures Refinement.IndexedRuleRefines(
              candidate,
              Semantics.ArrowRelationRule(
                head,
                viaBinding.relation.relationName,
                targetRelation,
                subjectType
              ),
              allBindings
            )
    decreases |targetBindings|
  {
    var targetBinding := targetBindings[0];
    if RelationMatches(
        targetBinding,
        viaBinding.relation.subjectType,
        targetRelation,
        subjectType
      ) &&
       candidate ==
       Indexed.ArrowRelationRule(
         head,
         viaBinding.eid,
         viaBinding.relation.subjectType,
         targetBinding.eid,
         subjectType
       ) {
      assert Refinement.RelationBindingMatches(
          viaBinding,
          head.resourceType,
          viaBinding.relation.relationName,
          viaBinding.relation.subjectType
        );
      assert Refinement.RelationBindingMatches(
          targetBinding,
          viaBinding.relation.subjectType,
          targetRelation,
          subjectType
        );
    } else {
      ArrowRelationTargetsHaveSemanticWitness(
        head,
        viaBinding,
        targetRelation,
        subjectType,
        targetBindings[1..],
        allBindings,
        candidate
      );
    }
  }

  lemma ArrowRelationTargetExpectedRuleIsCanonical(
    head: Semantics.PermissionNode,
    viaBinding: Refinement.RelationBinding,
    targetRelation: string,
    subjectType: string,
    bindings: seq<Refinement.RelationBinding>,
    targetBinding: Refinement.RelationBinding
  )
    requires targetBinding in bindings
    requires RelationMatches(
               targetBinding,
               viaBinding.relation.subjectType,
               targetRelation,
               subjectType
             )
    ensures Indexed.ArrowRelationRule(
              head,
              viaBinding.eid,
              viaBinding.relation.subjectType,
              targetBinding.eid,
              subjectType
            ) in
              ArrowRelationTargets(
                head,
                viaBinding,
                targetRelation,
                subjectType,
                bindings
              )
    decreases |bindings|
  {
    if bindings[0] != targetBinding {
      ArrowRelationTargetExpectedRuleIsCanonical(
        head,
        viaBinding,
        targetRelation,
        subjectType,
        bindings[1..],
        targetBinding
      );
    }
  }

  lemma ArrowRelationRulesHaveSemanticWitness(
    head: Semantics.PermissionNode,
    viaRelation: string,
    targetRelation: string,
    subjectType: string,
    bindings: seq<Refinement.RelationBinding>,
    allBindings: seq<Refinement.RelationBinding>,
    candidate: Indexed.IndexedRule
  )
    requires forall binding <- bindings ::
               binding in allBindings
    requires candidate in
               ArrowRelationRules(
                 head,
                 viaRelation,
                 targetRelation,
                 subjectType,
                 bindings,
                 allBindings
               )
    ensures Refinement.IndexedRuleRefines(
              candidate,
              Semantics.ArrowRelationRule(
                head,
                viaRelation,
                targetRelation,
                subjectType
              ),
              allBindings
            )
    decreases |bindings|
  {
    var viaBinding := bindings[0];
    if viaBinding.relation.resourceType == head.resourceType &&
       viaBinding.relation.relationName == viaRelation &&
       candidate in
         ArrowRelationTargets(
           head,
           viaBinding,
           targetRelation,
           subjectType,
           allBindings
         ) {
      ArrowRelationTargetsHaveSemanticWitness(
        head,
        viaBinding,
        targetRelation,
        subjectType,
        allBindings,
        allBindings,
        candidate
      );
    } else {
      ArrowRelationRulesHaveSemanticWitness(
        head,
        viaRelation,
        targetRelation,
        subjectType,
        bindings[1..],
        allBindings,
        candidate
      );
    }
  }

  lemma ArrowRelationExpectedRuleIsCanonical(
    head: Semantics.PermissionNode,
    viaRelation: string,
    targetRelation: string,
    subjectType: string,
    viaBindings: seq<Refinement.RelationBinding>,
    allBindings: seq<Refinement.RelationBinding>,
    viaBinding: Refinement.RelationBinding,
    targetBinding: Refinement.RelationBinding
  )
    requires viaBinding in viaBindings
    requires targetBinding in allBindings
    requires forall binding <- viaBindings ::
               binding in allBindings
    requires RelationMatches(
               viaBinding,
               head.resourceType,
               viaRelation,
               viaBinding.relation.subjectType
             )
    requires RelationMatches(
               targetBinding,
               viaBinding.relation.subjectType,
               targetRelation,
               subjectType
             )
    ensures Indexed.ArrowRelationRule(
              head,
              viaBinding.eid,
              viaBinding.relation.subjectType,
              targetBinding.eid,
              subjectType
            ) in
              ArrowRelationRules(
                head,
                viaRelation,
                targetRelation,
                subjectType,
                viaBindings,
                allBindings
              )
    decreases |viaBindings|
  {
    if viaBindings[0] == viaBinding {
      ArrowRelationTargetExpectedRuleIsCanonical(
        head,
        viaBinding,
        targetRelation,
        subjectType,
        allBindings,
        targetBinding
      );
    } else {
      ArrowRelationExpectedRuleIsCanonical(
        head,
        viaRelation,
        targetRelation,
        subjectType,
        viaBindings[1..],
        allBindings,
        viaBinding,
        targetBinding
      );
    }
  }

  lemma CompiledRuleHasSemanticWitness(
    rule: Semantics.NormalizedRule,
    bindings: seq<Refinement.RelationBinding>,
    candidate: Indexed.IndexedRule
  )
    requires candidate in CompileNormalizedRule(rule, bindings)
    ensures Refinement.IndexedRuleRefines(
              candidate,
              rule,
              bindings
            )
  {
    match rule
    case DirectRelationRule(
      head,
        relationName,
        subjectType
        ) => {
      DirectRulesHaveSemanticWitness(
        head,
        relationName,
        subjectType,
        bindings,
        candidate
      );
    }
    case SelfPermissionRule(
      head,
        sourcePermission
        ) => {
    }
    case ArrowRelationRule(
      head,
        viaRelation,
        targetRelation,
        subjectType
        ) => {
      ArrowRelationRulesHaveSemanticWitness(
        head,
        viaRelation,
        targetRelation,
        subjectType,
        bindings,
        bindings,
        candidate
      );
    }
    case ArrowPermissionRule(
      head,
        viaRelation,
        targetPermission
        ) => {
      ArrowPermissionRulesHaveSemanticWitness(
        head,
        viaRelation,
        targetPermission,
        bindings,
        candidate
      );
    }
  }

  lemma CanonicalRuleHasSemanticWitness(
    definitions: seq<Semantics.RuleDefinition>,
    bindings: seq<Refinement.RelationBinding>,
    candidate: Indexed.IndexedRule
  )
    requires candidate in
               CanonicalIndexedRules(definitions, bindings)
    ensures exists normalizedRule <-
                     Semantics.Normalize(definitions) ::
              Refinement.IndexedRuleRefines(
                candidate,
                normalizedRule,
                bindings
              )
    decreases |definitions|
  {
    var normalized :=
      Semantics.NormalizeDefinition(definitions[0]);
    if candidate in CompileNormalizedRule(normalized, bindings) {
      CompiledRuleHasSemanticWitness(
        normalized,
        bindings,
        candidate
      );
    } else {
      CanonicalRuleHasSemanticWitness(
        definitions[1..],
        bindings,
        candidate
      );
    }
  }

  lemma CompiledRuleForDefinitionIsCanonical(
    definitions: seq<Semantics.RuleDefinition>,
    bindings: seq<Refinement.RelationBinding>,
    definition: Semantics.RuleDefinition,
    candidate: Indexed.IndexedRule
  )
    requires definition in definitions
    requires candidate in
               CompileNormalizedRule(
                 Semantics.NormalizeDefinition(definition),
                 bindings
               )
    ensures candidate in
              CanonicalIndexedRules(definitions, bindings)
    decreases |definitions|
  {
    if definitions[0] != definition {
      CompiledRuleForDefinitionIsCanonical(
        definitions[1..],
        bindings,
        definition,
        candidate
      );
    }
  }

  lemma NormalizedRuleHasSourceDefinition(
    definitions: seq<Semantics.RuleDefinition>,
    normalizedRule: Semantics.NormalizedRule
  )
    requires normalizedRule in Semantics.Normalize(definitions)
    ensures exists definition <- definitions ::
              Semantics.NormalizeDefinition(definition) ==
              normalizedRule
    decreases |definitions|
  {
    if Semantics.NormalizeDefinition(definitions[0]) !=
       normalizedRule {
      NormalizedRuleHasSourceDefinition(
        definitions[1..],
        normalizedRule
      );
    }
  }

  lemma DirectNormalizedRuleIsCanonical(
    definitions: seq<Semantics.RuleDefinition>,
    bindings: seq<Refinement.RelationBinding>,
    normalizedRule: Semantics.NormalizedRule,
    binding: Refinement.RelationBinding
  )
    requires normalizedRule in Semantics.Normalize(definitions)
    requires normalizedRule.DirectRelationRule?
    requires RelationMatches(
               binding,
               normalizedRule.head.resourceType,
               normalizedRule.relationName,
               normalizedRule.subjectType
             )
    requires binding in bindings
    ensures Indexed.RelationRule(
              normalizedRule.head,
              binding.eid,
              normalizedRule.subjectType
            ) in
              CanonicalIndexedRules(definitions, bindings)
  {
    NormalizedRuleHasSourceDefinition(
      definitions,
      normalizedRule
    );
    var definition :| definition in definitions &&
                      Semantics.NormalizeDefinition(definition) ==
                      normalizedRule;
    DirectExpectedRuleIsCanonical(
      normalizedRule.head,
      normalizedRule.relationName,
      normalizedRule.subjectType,
      bindings,
      binding
    );
    CompiledRuleForDefinitionIsCanonical(
      definitions,
      bindings,
      definition,
      Indexed.RelationRule(
        normalizedRule.head,
        binding.eid,
        normalizedRule.subjectType
      )
    );
  }

  lemma SelfNormalizedRuleIsCanonical(
    definitions: seq<Semantics.RuleDefinition>,
    bindings: seq<Refinement.RelationBinding>,
    normalizedRule: Semantics.NormalizedRule
  )
    requires normalizedRule in Semantics.Normalize(definitions)
    requires normalizedRule.SelfPermissionRule?
    ensures Indexed.SelfPermissionRule(
              normalizedRule.head,
              Semantics.PermissionNode(
                normalizedRule.head.resourceType,
                normalizedRule.sourcePermission
              )
            ) in
              CanonicalIndexedRules(definitions, bindings)
  {
    NormalizedRuleHasSourceDefinition(
      definitions,
      normalizedRule
    );
    var definition :| definition in definitions &&
                      Semantics.NormalizeDefinition(definition) ==
                      normalizedRule;
    CompiledRuleForDefinitionIsCanonical(
      definitions,
      bindings,
      definition,
      Indexed.SelfPermissionRule(
        normalizedRule.head,
        Semantics.PermissionNode(
          normalizedRule.head.resourceType,
          normalizedRule.sourcePermission
        )
      )
    );
  }

  lemma ArrowRelationNormalizedRuleIsCanonical(
    definitions: seq<Semantics.RuleDefinition>,
    bindings: seq<Refinement.RelationBinding>,
    normalizedRule: Semantics.NormalizedRule,
    viaBinding: Refinement.RelationBinding,
    targetBinding: Refinement.RelationBinding
  )
    requires normalizedRule in Semantics.Normalize(definitions)
    requires normalizedRule.ArrowRelationRule?
    requires viaBinding in bindings
    requires targetBinding in bindings
    requires RelationMatches(
               viaBinding,
               normalizedRule.head.resourceType,
               normalizedRule.viaRelation,
               viaBinding.relation.subjectType
             )
    requires RelationMatches(
               targetBinding,
               viaBinding.relation.subjectType,
               normalizedRule.targetRelation,
               normalizedRule.subjectType
             )
    ensures Indexed.ArrowRelationRule(
              normalizedRule.head,
              viaBinding.eid,
              viaBinding.relation.subjectType,
              targetBinding.eid,
              normalizedRule.subjectType
            ) in
              CanonicalIndexedRules(definitions, bindings)
  {
    NormalizedRuleHasSourceDefinition(
      definitions,
      normalizedRule
    );
    var definition :| definition in definitions &&
                      Semantics.NormalizeDefinition(definition) ==
                      normalizedRule;
    ArrowRelationExpectedRuleIsCanonical(
      normalizedRule.head,
      normalizedRule.viaRelation,
      normalizedRule.targetRelation,
      normalizedRule.subjectType,
      bindings,
      bindings,
      viaBinding,
      targetBinding
    );
    CompiledRuleForDefinitionIsCanonical(
      definitions,
      bindings,
      definition,
      Indexed.ArrowRelationRule(
        normalizedRule.head,
        viaBinding.eid,
        viaBinding.relation.subjectType,
        targetBinding.eid,
        normalizedRule.subjectType
      )
    );
  }

  lemma ArrowPermissionNormalizedRuleIsCanonical(
    definitions: seq<Semantics.RuleDefinition>,
    bindings: seq<Refinement.RelationBinding>,
    normalizedRule: Semantics.NormalizedRule,
    viaBinding: Refinement.RelationBinding
  )
    requires normalizedRule in Semantics.Normalize(definitions)
    requires normalizedRule.ArrowPermissionRule?
    requires viaBinding in bindings
    requires RelationMatches(
               viaBinding,
               normalizedRule.head.resourceType,
               normalizedRule.viaRelation,
               viaBinding.relation.subjectType
             )
    ensures Indexed.ArrowPermissionRule(
              normalizedRule.head,
              viaBinding.eid,
              viaBinding.relation.subjectType,
              Semantics.PermissionNode(
                viaBinding.relation.subjectType,
                normalizedRule.targetPermission
              )
            ) in
              CanonicalIndexedRules(definitions, bindings)
  {
    NormalizedRuleHasSourceDefinition(
      definitions,
      normalizedRule
    );
    var definition :| definition in definitions &&
                      Semantics.NormalizeDefinition(definition) ==
                      normalizedRule;
    ArrowPermissionExpectedRuleIsCanonical(
      normalizedRule.head,
      normalizedRule.viaRelation,
      normalizedRule.targetPermission,
      bindings,
      viaBinding
    );
    CompiledRuleForDefinitionIsCanonical(
      definitions,
      bindings,
      definition,
      Indexed.ArrowPermissionRule(
        normalizedRule.head,
        viaBinding.eid,
        viaBinding.relation.subjectType,
        Semantics.PermissionNode(
          viaBinding.relation.subjectType,
          normalizedRule.targetPermission
        )
      )
    );
  }

  lemma CanonicalCompilationRefinesSemantics(
    definitions: seq<Semantics.RuleDefinition>,
    bindings: seq<Refinement.RelationBinding>
  )
    ensures Refinement.ExactCompiledRules(
              Semantics.Normalize(definitions),
              CanonicalIndexedRules(definitions, bindings),
              bindings
            )
  {
    forall indexedRule: Indexed.IndexedRule |
      indexedRule in CanonicalIndexedRules(definitions, bindings)
      ensures exists normalizedRule <-
                       Semantics.Normalize(definitions) ::
                Refinement.IndexedRuleRefines(
                  indexedRule,
                  normalizedRule,
                  bindings
                )
    {
      CanonicalRuleHasSemanticWitness(
        definitions,
        bindings,
        indexedRule
      );
    }
    forall normalizedRule: Semantics.NormalizedRule,
      binding: Refinement.RelationBinding |
      normalizedRule in Semantics.Normalize(definitions) &&
      binding in bindings &&
      normalizedRule.DirectRelationRule? &&
      Refinement.RelationBindingMatches(
        binding,
        normalizedRule.head.resourceType,
        normalizedRule.relationName,
        normalizedRule.subjectType
      )
      ensures Indexed.RelationRule(
                normalizedRule.head,
                binding.eid,
                normalizedRule.subjectType
              ) in
                CanonicalIndexedRules(definitions, bindings)
    {
      DirectNormalizedRuleIsCanonical(
        definitions,
        bindings,
        normalizedRule,
        binding
      );
    }
    forall normalizedRule: Semantics.NormalizedRule |
      normalizedRule in Semantics.Normalize(definitions) &&
      normalizedRule.SelfPermissionRule?
      ensures Indexed.SelfPermissionRule(
                normalizedRule.head,
                Semantics.PermissionNode(
                  normalizedRule.head.resourceType,
                  normalizedRule.sourcePermission
                )
              ) in
                CanonicalIndexedRules(definitions, bindings)
    {
      SelfNormalizedRuleIsCanonical(
        definitions,
        bindings,
        normalizedRule
      );
    }
    forall normalizedRule: Semantics.NormalizedRule,
      viaBinding: Refinement.RelationBinding,
      targetBinding: Refinement.RelationBinding |
      normalizedRule in Semantics.Normalize(definitions) &&
      viaBinding in bindings &&
      targetBinding in bindings &&
      normalizedRule.ArrowRelationRule? &&
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
      ensures Indexed.ArrowRelationRule(
                normalizedRule.head,
                viaBinding.eid,
                viaBinding.relation.subjectType,
                targetBinding.eid,
                normalizedRule.subjectType
              ) in
                CanonicalIndexedRules(definitions, bindings)
    {
      ArrowRelationNormalizedRuleIsCanonical(
        definitions,
        bindings,
        normalizedRule,
        viaBinding,
        targetBinding
      );
    }
    forall normalizedRule: Semantics.NormalizedRule,
      viaBinding: Refinement.RelationBinding |
      normalizedRule in Semantics.Normalize(definitions) &&
      viaBinding in bindings &&
      normalizedRule.ArrowPermissionRule? &&
      Refinement.RelationBindingMatches(
        viaBinding,
        normalizedRule.head.resourceType,
        normalizedRule.viaRelation,
        viaBinding.relation.subjectType
      )
      ensures Indexed.ArrowPermissionRule(
                normalizedRule.head,
                viaBinding.eid,
                viaBinding.relation.subjectType,
                Semantics.PermissionNode(
                  viaBinding.relation.subjectType,
                  normalizedRule.targetPermission
                )
              ) in
                CanonicalIndexedRules(definitions, bindings)
    {
      ArrowPermissionNormalizedRuleIsCanonical(
        definitions,
        bindings,
        normalizedRule,
        viaBinding
      );
    }
  }

  lemma SameRuleSetPreservesExactCompilation(
    definitions: seq<Semantics.RuleDefinition>,
    bindings: seq<Refinement.RelationBinding>,
    indexedRules: seq<Indexed.IndexedRule>
  )
    requires SameRuleSet(
               indexedRules,
               CanonicalIndexedRules(definitions, bindings)
             )
    ensures Refinement.ExactCompiledRules(
              Semantics.Normalize(definitions),
              indexedRules,
              bindings
            )
  {
    CanonicalCompilationRefinesSemantics(
      definitions,
      bindings
    );
    SameRuleSetIffMembership(
      indexedRules,
      CanonicalIndexedRules(definitions, bindings)
    );
  }

  lemma EligibleSeedRulesIffMembership(
    rules: seq<Indexed.IndexedRule>,
    subjectType: string,
    candidate: Indexed.IndexedRule
  )
    ensures candidate in EligibleSeedRules(rules, subjectType) <==>
            candidate in rules &&
            Refinement.EligibleForwardSeed(candidate, subjectType)
    decreases |rules|
  {
    if |rules| != 0 {
      EligibleSeedRulesIffMembership(
        rules[1..],
        subjectType,
        candidate
      );
    }
  }

  lemma SameRuleSetPreservesExactSeedBucket(
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string
  )
    requires UniqueRules(indexedRules)
    requires UniqueRules(seedRules)
    requires SameRuleSet(
               seedRules,
               EligibleSeedRules(indexedRules, subjectType)
             )
    ensures Refinement.ExactForwardSeedBucket(
              indexedRules,
              seedRules,
              subjectType
            )
  {
    UniqueRulesImpliesRefinementUniqueness(indexedRules);
    UniqueRulesImpliesRefinementUniqueness(seedRules);
    SameRuleSetIffMembership(
      seedRules,
      EligibleSeedRules(indexedRules, subjectType)
    );
    forall candidate: Indexed.IndexedRule
      ensures candidate in seedRules <==>
              candidate in indexedRules &&
              Refinement.EligibleForwardSeed(candidate, subjectType)
    {
      EligibleSeedRulesIffMembership(
        indexedRules,
        subjectType,
        candidate
      );
    }
  }

  method CertifyIndexedRules(
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    bindings: seq<Refinement.RelationBinding>,
    indexedRules: seq<Indexed.IndexedRule>
  ) returns (result: PlanCertification)
    ensures result.PlanCertified? ==>
              ExactRelationCatalog(relations, bindings) &&
              ValidRules(indexedRules) &&
              UniqueRules(indexedRules) &&
              PermissionClosedRules(indexedRules, permissions) &&
              SameRuleSet(
                indexedRules,
                CanonicalIndexedRules(definitions, bindings)
              )
    ensures result.PlanCertified? ==>
              Refinement.ExactCompiledRules(
                Semantics.Normalize(definitions),
                indexedRules,
                bindings
              )
  {
    if !ExactRelationCatalog(relations, bindings) {
      return PlanRejected(InvalidRelationCatalog);
    }
    if !ValidRules(indexedRules) {
      return PlanRejected(InvalidIndexedRule);
    }
    if !UniqueRules(indexedRules) {
      return PlanRejected(DuplicateIndexedRule);
    }
    if !PermissionClosedRules(indexedRules, permissions) {
      return PlanRejected(PermissionOpenRule);
    }
    if !SameRuleSet(
        indexedRules,
        CanonicalIndexedRules(definitions, bindings)
      ) {
      return PlanRejected(CompiledRuleMismatch);
    }
    SameRuleSetPreservesExactCompilation(
      definitions,
      bindings,
      indexedRules
    );
    return PlanCertified;
  }

  method CertifySeedBucket(
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string
  ) returns (result: PlanCertification)
    ensures result.PlanCertified? ==>
              ValidRules(indexedRules) &&
              UniqueRules(indexedRules) &&
              ValidRules(seedRules) &&
              UniqueRules(seedRules) &&
              SameRuleSet(
                seedRules,
                EligibleSeedRules(indexedRules, subjectType)
              )
    ensures result.PlanCertified? ==>
              Refinement.ExactForwardSeedBucket(
                indexedRules,
                seedRules,
                subjectType
              )
  {
    if !ValidRules(indexedRules) {
      return PlanRejected(InvalidIndexedRule);
    }
    if !UniqueRules(indexedRules) {
      return PlanRejected(DuplicateIndexedRule);
    }
    if !ValidRules(seedRules) {
      return PlanRejected(InvalidSeedRule);
    }
    if !UniqueRules(seedRules) {
      return PlanRejected(DuplicateSeedRule);
    }
    if !SameRuleSet(
        seedRules,
        EligibleSeedRules(indexedRules, subjectType)
      ) {
      return PlanRejected(SeedBucketMismatch);
    }
    SameRuleSetPreservesExactSeedBucket(
      indexedRules,
      seedRules,
      subjectType
    );
    return PlanCertified;
  }

  method CertifyIndexedPlan(
    relations: seq<Semantics.RelationNode>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    bindings: seq<Refinement.RelationBinding>,
    indexedRules: seq<Indexed.IndexedRule>,
    seedRules: seq<Indexed.IndexedRule>,
    subjectType: string
  ) returns (result: PlanCertification)
    ensures result.PlanCertified? ==>
              ExactRelationCatalog(relations, bindings) &&
              ValidRules(indexedRules) &&
              UniqueRules(indexedRules) &&
              PermissionClosedRules(indexedRules, permissions) &&
              SameRuleSet(
                indexedRules,
                CanonicalIndexedRules(definitions, bindings)
              ) &&
              ValidRules(seedRules) &&
              UniqueRules(seedRules) &&
              SameRuleSet(
                seedRules,
                EligibleSeedRules(indexedRules, subjectType)
              )
    ensures result.PlanCertified? ==>
              Refinement.ExactCompiledRules(
                Semantics.Normalize(definitions),
                indexedRules,
                bindings
              )
    ensures result.PlanCertified? ==>
              Refinement.ExactForwardSeedBucket(
                indexedRules,
                seedRules,
                subjectType
              )
  {
    if !ExactRelationCatalog(relations, bindings) {
      return PlanRejected(InvalidRelationCatalog);
    }
    if !ValidRules(indexedRules) {
      return PlanRejected(InvalidIndexedRule);
    }
    if !UniqueRules(indexedRules) {
      return PlanRejected(DuplicateIndexedRule);
    }
    if !PermissionClosedRules(indexedRules, permissions) {
      return PlanRejected(PermissionOpenRule);
    }
    if !SameRuleSet(
        indexedRules,
        CanonicalIndexedRules(definitions, bindings)
      ) {
      return PlanRejected(CompiledRuleMismatch);
    }
    if !ValidRules(seedRules) {
      return PlanRejected(InvalidSeedRule);
    }
    if !UniqueRules(seedRules) {
      return PlanRejected(DuplicateSeedRule);
    }
    if !SameRuleSet(
        seedRules,
        EligibleSeedRules(indexedRules, subjectType)
      ) {
      return PlanRejected(SeedBucketMismatch);
    }
    SameRuleSetPreservesExactCompilation(
      definitions,
      bindings,
      indexedRules
    );
    SameRuleSetPreservesExactSeedBucket(
      indexedRules,
      seedRules,
      subjectType
    );
    return PlanCertified;
  }
}

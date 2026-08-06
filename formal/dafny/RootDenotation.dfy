include "AcyclicEngine.dfy"

module RootDenotation {
  import Semantics
  import AcyclicEngine

  datatype NormalizedRuleBody =
    | DirectRelationBody(
        relationName: string,
        subjectType: string
      )
    | SelfPermissionBody(sourcePermission: string)
    | ArrowRelationBody(
        viaRelation: string,
        targetRelation: string,
        subjectType: string
      )
    | ArrowPermissionBody(
        viaRelation: string,
        targetPermission: string
      )

  function RuleHead(
    rule: Semantics.NormalizedRule
  ): Semantics.PermissionNode {
    match rule
    case DirectRelationRule(head, _, _) => head
    case SelfPermissionRule(head, _) => head
    case ArrowRelationRule(head, _, _, _) => head
    case ArrowPermissionRule(head, _, _) => head
  }

  function RuleBody(
    rule: Semantics.NormalizedRule
  ): NormalizedRuleBody {
    match rule
    case DirectRelationRule(_, relationName, subjectType) =>
      DirectRelationBody(relationName, subjectType)
    case SelfPermissionRule(_, sourcePermission) =>
      SelfPermissionBody(sourcePermission)
    case ArrowRelationRule(
      _,
      viaRelation,
      targetRelation,
      subjectType
      ) =>
      ArrowRelationBody(
        viaRelation,
        targetRelation,
        subjectType
      )
    case ArrowPermissionRule(_, viaRelation, targetPermission) =>
      ArrowPermissionBody(viaRelation, targetPermission)
  }

  ghost predicate RootRuleBodiesEquivalent(
    rules: seq<Semantics.NormalizedRule>,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  ) {
    left.resourceType == right.resourceType &&
    (forall leftRule <- rules |
            RuleHead(leftRule) == left ::
       exists rightRule <- rules ::
         RuleHead(rightRule) == right &&
         RuleBody(rightRule) == RuleBody(leftRule)) &&
    (forall rightRule <- rules |
            RuleHead(rightRule) == right ::
       exists leftRule <- rules ::
         RuleHead(leftRule) == left &&
         RuleBody(leftRule) == RuleBody(rightRule))
  }

  ghost predicate RootGrantsEquivalent(
    grants: set<Semantics.Grant>,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  ) {
    left.resourceType == right.resourceType &&
    (forall subject: Semantics.ObjectRef,
       resource: Semantics.ObjectRef ::
       Semantics.Grant(subject, left, resource) in grants
                                                   <==>
                                                   Semantics.Grant(subject, right, resource) in grants)
  }

  lemma RuleDerivationUsesItsHead(
    rule: Semantics.NormalizedRule,
    relationships: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>,
    grant: Semantics.Grant
  )
    requires Semantics.RuleDerives(
               rule,
               relationships,
               grants,
               grant
             )
    ensures grant.node == RuleHead(rule)
  {
  }

  lemma EqualBodiesDeriveRetargetedGrantEqually(
    leftRule: Semantics.NormalizedRule,
    rightRule: Semantics.NormalizedRule,
    relationships: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>,
    subject: Semantics.ObjectRef,
    resource: Semantics.ObjectRef,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  )
    requires RuleHead(leftRule) == left
    requires RuleHead(rightRule) == right
    requires RuleBody(leftRule) == RuleBody(rightRule)
    requires left.resourceType == right.resourceType
    ensures Semantics.RuleDerives(
              leftRule,
              relationships,
              grants,
              Semantics.Grant(subject, left, resource)
            )
            <==>
            Semantics.RuleDerives(
              rightRule,
              relationships,
              grants,
              Semantics.Grant(subject, right, resource)
            )
  {
  }

  lemma RootRuleBodiesEquivalenceIsSymmetric(
    rules: seq<Semantics.NormalizedRule>,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  )
    requires RootRuleBodiesEquivalent(rules, left, right)
    ensures RootRuleBodiesEquivalent(rules, right, left)
  {
  }

  lemma {:isolate_assertions} EquivalentRootRulesDeriveOneWay(
    rules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>,
    subject: Semantics.ObjectRef,
    resource: Semantics.ObjectRef,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  )
    requires RootRuleBodiesEquivalent(rules, left, right)
    requires Semantics.AnyRuleDerives(
               rules,
               relationships,
               grants,
               Semantics.Grant(subject, left, resource)
             )
    ensures Semantics.AnyRuleDerives(
              rules,
              relationships,
              grants,
              Semantics.Grant(subject, right, resource)
            )
  {
    var leftIndex :|
      0 <= leftIndex < |rules| &&
      Semantics.RuleDerives(
        rules[leftIndex],
        relationships,
        grants,
        Semantics.Grant(subject, left, resource)
      );
    RuleDerivationUsesItsHead(
      rules[leftIndex],
      relationships,
      grants,
      Semantics.Grant(subject, left, resource)
    );
    var rightRule :|
      rightRule in rules &&
      RuleHead(rightRule) == right &&
      RuleBody(rightRule) == RuleBody(rules[leftIndex]);
    EqualBodiesDeriveRetargetedGrantEqually(
      rules[leftIndex],
      rightRule,
      relationships,
      grants,
      subject,
      resource,
      left,
      right
    );
  }

  lemma EquivalentRootRulesDeriveEqually(
    rules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>,
    subject: Semantics.ObjectRef,
    resource: Semantics.ObjectRef,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  )
    requires RootRuleBodiesEquivalent(rules, left, right)
    ensures Semantics.AnyRuleDerives(
              rules,
              relationships,
              grants,
              Semantics.Grant(subject, left, resource)
            )
            <==>
            Semantics.AnyRuleDerives(
              rules,
              relationships,
              grants,
              Semantics.Grant(subject, right, resource)
            )
  {
    if Semantics.AnyRuleDerives(
        rules,
        relationships,
        grants,
        Semantics.Grant(subject, left, resource)
      ) {
      EquivalentRootRulesDeriveOneWay(
        rules,
        relationships,
        grants,
        subject,
        resource,
        left,
        right
      );
    }

    if Semantics.AnyRuleDerives(
        rules,
        relationships,
        grants,
        Semantics.Grant(subject, right, resource)
      ) {
      RootRuleBodiesEquivalenceIsSymmetric(rules, left, right);
      EquivalentRootRulesDeriveOneWay(
        rules,
        relationships,
        grants,
        subject,
        resource,
        right,
        left
      );
    }
  }

  lemma GrantUniverseMembership(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    grant: Semantics.Grant
  )
    ensures grant in Semantics.GrantUniverse(objects, permissions)
            <==>
            Semantics.ContainsObject(objects, grant.subject) &&
            Semantics.ContainsObject(objects, grant.resource) &&
            Semantics.ContainsPermission(
              permissions,
              grant.node.resourceType,
              grant.node.permissionName
            ) &&
            grant.resource.typeName == grant.node.resourceType
    decreases |permissions|
  {
    if |permissions| != 0 {
      GrantUniverseMembership(
        objects,
        permissions[1..],
        grant
      );
    }
  }

  lemma EquivalentRootGrantUniverseMembership(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    subject: Semantics.ObjectRef,
    resource: Semantics.ObjectRef,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  )
    requires left.resourceType == right.resourceType
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
    ensures Semantics.Grant(subject, left, resource) in
              Semantics.GrantUniverse(objects, permissions)
            <==>
            Semantics.Grant(subject, right, resource) in
              Semantics.GrantUniverse(objects, permissions)
  {
    GrantUniverseMembership(
      objects,
      permissions,
      Semantics.Grant(subject, left, resource)
    );
    GrantUniverseMembership(
      objects,
      permissions,
      Semantics.Grant(subject, right, resource)
    );
  }

  lemma ImmediateConsequencesPreserveRootEquivalence(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    rules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    current: set<Semantics.Grant>,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  )
    requires RootRuleBodiesEquivalent(rules, left, right)
    requires RootGrantsEquivalent(current, left, right)
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
    ensures RootGrantsEquivalent(
              Semantics.ImmediateConsequences(
                objects,
                permissions,
                rules,
                relationships,
                current
              ),
              left,
              right
            )
  {
    forall subject: Semantics.ObjectRef,
      resource: Semantics.ObjectRef
      ensures
        Semantics.Grant(subject, left, resource) in
          Semantics.ImmediateConsequences(
            objects,
            permissions,
            rules,
            relationships,
            current
          )
          <==>
          Semantics.Grant(subject, right, resource) in
            Semantics.ImmediateConsequences(
              objects,
              permissions,
              rules,
              relationships,
              current
            )
    {
      EquivalentRootGrantUniverseMembership(
        objects,
        permissions,
        subject,
        resource,
        left,
        right
      );
      EquivalentRootRulesDeriveEqually(
        rules,
        relationships,
        current,
        subject,
        resource,
        left,
        right
      );
    }
  }

  ghost method IterateToFixedPointPreservingRootEquivalence(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    rules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    current: set<Semantics.Grant>,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  ) returns (result: set<Semantics.Grant>)
    requires current <= Semantics.GrantUniverse(objects, permissions)
    requires RootRuleBodiesEquivalent(rules, left, right)
    requires RootGrantsEquivalent(current, left, right)
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
    ensures current <= result
    ensures result <= Semantics.GrantUniverse(objects, permissions)
    ensures Semantics.ImmediateConsequences(
              objects,
              permissions,
              rules,
              relationships,
              result
            ) == result
    ensures forall fixed |
              current <= fixed &&
              fixed <= Semantics.GrantUniverse(objects, permissions) &&
              Semantics.ImmediateConsequences(
                objects,
                permissions,
                rules,
                relationships,
                fixed
              ) == fixed ::
              result <= fixed
    ensures RootGrantsEquivalent(result, left, right)
    decreases Semantics.GrantUniverse(objects, permissions) - current
  {
    var universe := Semantics.GrantUniverse(objects, permissions);
    var next := Semantics.ImmediateConsequences(
      objects,
      permissions,
      rules,
      relationships,
      current
    );

    assert current <= next;
    assert next <= universe;
    ImmediateConsequencesPreserveRootEquivalence(
      objects,
      permissions,
      rules,
      relationships,
      current,
      left,
      right
    );

    if next == current {
      return current;
    }

    assert current < next;
    assert universe - next < universe - current;

    result := IterateToFixedPointPreservingRootEquivalence(
      objects,
      permissions,
      rules,
      relationships,
      next,
      left,
      right
    );

    forall fixed |
      current <= fixed &&
      fixed <= universe &&
      Semantics.ImmediateConsequences(
        objects,
        permissions,
        rules,
        relationships,
        fixed
      ) == fixed
      ensures result <= fixed
    {
      Semantics.ImmediateConsequencesAreMonotone(
        objects,
        permissions,
        rules,
        relationships,
        current,
        fixed
      );
      assert next <= fixed;
    }
  }

  ghost method EquivalentRuleBodiesHaveEqualSemanticDenotations(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    left: Semantics.PermissionNode,
    right: Semantics.PermissionNode
  ) returns (grants: set<Semantics.Grant>)
    requires RootRuleBodiesEquivalent(
               Semantics.Normalize(definitions),
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
    ensures RootGrantsEquivalent(grants, left, right)
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
    grants := IterateToFixedPointPreservingRootEquivalence(
      objects,
      permissions,
      Semantics.Normalize(definitions),
      relationships,
      {},
      left,
      right
    );

    forall subject: Semantics.ObjectRef,
      resource: Semantics.ObjectRef
      ensures
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
      AcyclicEngine.LeastFixedPointMembership(
        objects,
        permissions,
        definitions,
        relationships,
        grants,
        Semantics.Grant(subject, left, resource)
      );
      AcyclicEngine.LeastFixedPointMembership(
        objects,
        permissions,
        definitions,
        relationships,
        grants,
        Semantics.Grant(subject, right, resource)
      );
    }
  }
}

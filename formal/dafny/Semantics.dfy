module Semantics {
  datatype ObjectRef = ObjectRef(typeName: string, objectId: string)

  datatype RelationNode = RelationNode(
    resourceType: string,
    relationName: string,
    subjectType: string
  )

  datatype PermissionNode = PermissionNode(
    resourceType: string,
    permissionName: string
  )

  datatype Relationship = Relationship(
    resource: ObjectRef,
    relationName: string,
    subject: ObjectRef
  )

  datatype RuleDefinition =
    | DirectRelation(
        head: PermissionNode,
        relationName: string,
        subjectType: string
      )
    | SelfPermission(
        head: PermissionNode,
        sourcePermission: string
      )
    | ArrowRelation(
        head: PermissionNode,
        viaRelation: string,
        targetRelation: string,
        subjectType: string
      )
    | ArrowPermission(
        head: PermissionNode,
        viaRelation: string,
        targetPermission: string
      )

  datatype NormalizedRule =
    | DirectRelationRule(
        head: PermissionNode,
        relationName: string,
        subjectType: string
      )
    | SelfPermissionRule(
        head: PermissionNode,
        sourcePermission: string
      )
    | ArrowRelationRule(
        head: PermissionNode,
        viaRelation: string,
        targetRelation: string,
        subjectType: string
      )
    | ArrowPermissionRule(
        head: PermissionNode,
        viaRelation: string,
        targetPermission: string
      )

  datatype Grant = Grant(
    subject: ObjectRef,
    node: PermissionNode,
    resource: ObjectRef
  )

  datatype Query = Query(
    subject: ObjectRef,
    node: PermissionNode,
    resource: ObjectRef
  )

  datatype AuthorizationResult =
    | Allowed
    | Denied
    | InvalidSchema(errors: seq<SchemaError>)
    | LimitExceeded(derived: nat, advanced: nat, queued: nat)

  datatype SchemaError =
    | EmptyIdentity(kind: string)
    | DuplicateRelation(relationNode: RelationNode)
    | DuplicatePermission(permissionNode: PermissionNode)
    | InvalidRule(index: nat)
    | InvalidRelationship(index: nat)

  predicate ValidIdentity(value: string) {
    0 < |value|
  }

  predicate ValidObject(candidate: ObjectRef) {
    ValidIdentity(candidate.typeName) && ValidIdentity(candidate.objectId)
  }

  predicate ContainsObject(objects: seq<ObjectRef>, candidate: ObjectRef) {
    exists i | 0 <= i < |objects| :: objects[i] == candidate
  }

  predicate ContainsRelation(
    relations: seq<RelationNode>,
    resourceType: string,
    relationName: string,
    subjectType: string
  ) {
    exists i | 0 <= i < |relations| ::
      relations[i] ==
      RelationNode(resourceType, relationName, subjectType)
  }

  predicate ContainsPermission(
    permissions: seq<PermissionNode>,
    resourceType: string,
    permissionName: string
  ) {
    exists i | 0 <= i < |permissions| ::
      permissions[i] == PermissionNode(resourceType, permissionName)
  }

  predicate UniqueRelations(relations: seq<RelationNode>) {
    forall i, j |
      0 <= i < j < |relations| ::
      relations[i] != relations[j]
  }

  predicate UniquePermissions(permissions: seq<PermissionNode>) {
    forall i, j |
      0 <= i < j < |permissions| ::
      permissions[i] != permissions[j]
  }

  predicate ValidRule(
    relations: seq<RelationNode>,
    permissions: seq<PermissionNode>,
    rule: RuleDefinition
  ) {
    match rule
    case DirectRelation(head, relationName, subjectType) =>
      ContainsPermission(
        permissions,
        head.resourceType,
        head.permissionName
      ) &&
      ContainsRelation(
        relations,
        head.resourceType,
        relationName,
        subjectType
      )
    case SelfPermission(head, sourcePermission) =>
      ContainsPermission(
        permissions,
        head.resourceType,
        head.permissionName
      ) &&
      ContainsPermission(
        permissions,
        head.resourceType,
        sourcePermission
      )
    case ArrowRelation(
      head,
      viaRelation,
      targetRelation,
      subjectType
      ) =>
      ContainsPermission(
        permissions,
        head.resourceType,
        head.permissionName
      ) &&
      exists via <- relations |
             via.resourceType == head.resourceType &&
             via.relationName == viaRelation ::
        ContainsRelation(
          relations,
          via.subjectType,
          targetRelation,
          subjectType
        )
    case ArrowPermission(
      head,
      viaRelation,
      targetPermission
      ) =>
      ContainsPermission(
        permissions,
        head.resourceType,
        head.permissionName
      ) &&
      exists via <- relations |
             via.resourceType == head.resourceType &&
             via.relationName == viaRelation ::
        ContainsPermission(
          permissions,
          via.subjectType,
          targetPermission
        )
  }

  predicate ValidRelationship(
    objects: seq<ObjectRef>,
    relations: seq<RelationNode>,
    relationship: Relationship
  ) {
    ContainsObject(objects, relationship.resource) &&
    ContainsObject(objects, relationship.subject) &&
    ContainsRelation(
      relations,
      relationship.resource.typeName,
      relationship.relationName,
      relationship.subject.typeName
    )
  }

  predicate WellFormedSchema(
    objects: seq<ObjectRef>,
    relations: seq<RelationNode>,
    permissions: seq<PermissionNode>,
    definitions: seq<RuleDefinition>,
    relationships: seq<Relationship>
  ) {
    (forall i | 0 <= i < |objects| :: ValidObject(objects[i])) &&
    (forall i, j |
       0 <= i < j < |objects| ::
       objects[i] != objects[j]) &&
    UniqueRelations(relations) &&
    UniquePermissions(permissions) &&
    (forall i | 0 <= i < |relations| ::
       ValidIdentity(relations[i].resourceType) &&
       ValidIdentity(relations[i].relationName) &&
       ValidIdentity(relations[i].subjectType)) &&
    (forall i | 0 <= i < |permissions| ::
       ValidIdentity(permissions[i].resourceType) &&
       ValidIdentity(permissions[i].permissionName)) &&
    (forall i | 0 <= i < |definitions| ::
       ValidRule(relations, permissions, definitions[i])) &&
    (forall i | 0 <= i < |relationships| ::
       ValidRelationship(objects, relations, relationships[i]))
  }

  function NormalizeDefinition(rule: RuleDefinition): NormalizedRule {
    match rule
    case DirectRelation(head, relationName, subjectType) =>
      DirectRelationRule(head, relationName, subjectType)
    case SelfPermission(head, sourcePermission) =>
      SelfPermissionRule(head, sourcePermission)
    case ArrowRelation(
      head,
      viaRelation,
      targetRelation,
      subjectType
      ) =>
      ArrowRelationRule(
        head,
        viaRelation,
        targetRelation,
        subjectType
      )
    case ArrowPermission(head, viaRelation, targetPermission) =>
      ArrowPermissionRule(head, viaRelation, targetPermission)
  }

  function Normalize(
    definitions: seq<RuleDefinition>
  ): seq<NormalizedRule>
    ensures |Normalize(definitions)| == |definitions|
    decreases |definitions|
  {
    if |definitions| == 0 then
      []
    else
      [NormalizeDefinition(definitions[0])] +
      Normalize(definitions[1..])
  }

  lemma NormalizationPreservesLength(
    definitions: seq<RuleDefinition>
  )
    ensures |Normalize(definitions)| == |definitions|
    decreases |definitions|
  {
    if |definitions| != 0 {
      NormalizationPreservesLength(definitions[1..]);
    }
  }

  lemma NormalizationPreservesEveryRule(
    definitions: seq<RuleDefinition>,
    index: nat
  )
    requires index < |definitions|
    ensures Normalize(definitions)[index] ==
            NormalizeDefinition(definitions[index])
    decreases |definitions|
  {
    if index != 0 {
      NormalizationPreservesEveryRule(definitions[1..], index - 1);
    }
  }

  predicate HasRelationship(
    relationships: seq<Relationship>,
    resource: ObjectRef,
    relationName: string,
    subject: ObjectRef
  ) {
    exists i | 0 <= i < |relationships| ::
      relationships[i] == Relationship(
        resource,
        relationName,
        subject
      )
  }

  predicate RuleDerives(
    rule: NormalizedRule,
    relationships: seq<Relationship>,
    grants: set<Grant>,
    grant: Grant
  ) {
    match rule
    case DirectRelationRule(head, relationName, subjectType) =>
      grant.node == head &&
      grant.resource.typeName == head.resourceType &&
      grant.subject.typeName == subjectType &&
      HasRelationship(
        relationships,
        grant.resource,
        relationName,
        grant.subject
      )
    case SelfPermissionRule(head, sourcePermission) =>
      grant.node == head &&
      Grant(
        grant.subject,
        PermissionNode(head.resourceType, sourcePermission),
        grant.resource
      ) in grants
    case ArrowRelationRule(
      head,
      viaRelation,
      targetRelation,
      subjectType
      ) =>
      grant.node == head &&
      grant.resource.typeName == head.resourceType &&
      grant.subject.typeName == subjectType &&
      exists via <- relationships |
             via.resource == grant.resource &&
             via.relationName == viaRelation ::
        HasRelationship(
          relationships,
          via.subject,
          targetRelation,
          grant.subject
        )
    case ArrowPermissionRule(
      head,
      viaRelation,
      targetPermission
      ) =>
      grant.node == head &&
      grant.resource.typeName == head.resourceType &&
      exists via <- relationships |
             via.resource == grant.resource &&
             via.relationName == viaRelation ::
        Grant(
          grant.subject,
          PermissionNode(via.subject.typeName, targetPermission),
          via.subject
        ) in grants
  }

  function NodeGrantUniverse(
    objects: seq<ObjectRef>,
    node: PermissionNode
  ): set<Grant> {
    set subject <- objects, resource <- objects |
        resource.typeName == node.resourceType ::
      Grant(subject, node, resource)
  }

  function GrantUniverse(
    objects: seq<ObjectRef>,
    permissions: seq<PermissionNode>
  ): set<Grant> {
    if |permissions| == 0 then
      {}
    else
      NodeGrantUniverse(objects, permissions[0]) +
      GrantUniverse(objects, permissions[1..])
  }

  predicate AnyRuleDerives(
    rules: seq<NormalizedRule>,
    relationships: seq<Relationship>,
    grants: set<Grant>,
    grant: Grant
  ) {
    exists i | 0 <= i < |rules| ::
      RuleDerives(rules[i], relationships, grants, grant)
  }

  function ImmediateConsequences(
    objects: seq<ObjectRef>,
    permissions: seq<PermissionNode>,
    rules: seq<NormalizedRule>,
    relationships: seq<Relationship>,
    grants: set<Grant>
  ): set<Grant> {
    grants +
    set grant <- GrantUniverse(objects, permissions) |
        AnyRuleDerives(rules, relationships, grants, grant)
  }

  lemma RuleDerivationIsMonotone(
    rule: NormalizedRule,
    relationships: seq<Relationship>,
    smaller: set<Grant>,
    larger: set<Grant>,
    grant: Grant
  )
    requires smaller <= larger
    requires RuleDerives(rule, relationships, smaller, grant)
    ensures RuleDerives(rule, relationships, larger, grant)
  {
  }

  lemma ImmediateConsequencesAreMonotone(
    objects: seq<ObjectRef>,
    permissions: seq<PermissionNode>,
    rules: seq<NormalizedRule>,
    relationships: seq<Relationship>,
    smaller: set<Grant>,
    larger: set<Grant>
  )
    requires smaller <= larger
    ensures ImmediateConsequences(
              objects,
              permissions,
              rules,
              relationships,
              smaller
            ) <= ImmediateConsequences(
                   objects,
                   permissions,
                   rules,
                   relationships,
                   larger
                 )
  {
    forall grant |
      grant in ImmediateConsequences(
                 objects,
                 permissions,
                 rules,
                 relationships,
                 smaller
               )
      ensures grant in ImmediateConsequences(
                         objects,
                         permissions,
                         rules,
                         relationships,
                         larger
                       )
    {
      if grant !in smaller {
        var index :| 0 <= index < |rules| &&
                     RuleDerives(
                       rules[index],
                       relationships,
                       smaller,
                       grant
                     );
        RuleDerivationIsMonotone(
          rules[index],
          relationships,
          smaller,
          larger,
          grant
        );
      }
    }
  }

  method IterateToFixedPoint(
    objects: seq<ObjectRef>,
    permissions: seq<PermissionNode>,
    rules: seq<NormalizedRule>,
    relationships: seq<Relationship>,
    current: set<Grant>
  ) returns (result: set<Grant>)
    requires current <= GrantUniverse(objects, permissions)
    ensures current <= result
    ensures result <= GrantUniverse(objects, permissions)
    ensures ImmediateConsequences(
              objects,
              permissions,
              rules,
              relationships,
              result
            ) == result
    ensures forall fixed |
              current <= fixed &&
              fixed <= GrantUniverse(objects, permissions) &&
              ImmediateConsequences(
                objects,
                permissions,
                rules,
                relationships,
                fixed
              ) == fixed ::
              result <= fixed
    decreases GrantUniverse(objects, permissions) - current
  {
    var universe := GrantUniverse(objects, permissions);
    var next := ImmediateConsequences(
      objects,
      permissions,
      rules,
      relationships,
      current
    );

    assert current <= next;
    assert next <= universe;

    if next == current {
      return current;
    }

    assert current < next;
    assert universe - next < universe - current;

    result := IterateToFixedPoint(
      objects,
      permissions,
      rules,
      relationships,
      next
    );

    forall fixed |
      current <= fixed &&
      fixed <= universe &&
      ImmediateConsequences(
        objects,
        permissions,
        rules,
        relationships,
        fixed
      ) == fixed
      ensures result <= fixed
    {
      ImmediateConsequencesAreMonotone(
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

  method AuthorizationSemantics(
    objects: seq<ObjectRef>,
    permissions: seq<PermissionNode>,
    definitions: seq<RuleDefinition>,
    relationships: seq<Relationship>
  ) returns (grants: set<Grant>)
    ensures grants <= GrantUniverse(objects, permissions)
    ensures ImmediateConsequences(
              objects,
              permissions,
              Normalize(definitions),
              relationships,
              grants
            ) == grants
    ensures forall fixed |
              fixed <= GrantUniverse(objects, permissions) &&
              ImmediateConsequences(
                objects,
                permissions,
                Normalize(definitions),
                relationships,
                fixed
              ) == fixed ::
              grants <= fixed
  {
    grants := IterateToFixedPoint(
      objects,
      permissions,
      Normalize(definitions),
      relationships,
      {}
    );
  }

  method Can(
    objects: seq<ObjectRef>,
    permissions: seq<PermissionNode>,
    definitions: seq<RuleDefinition>,
    relationships: seq<Relationship>,
    query: Query
  ) returns (allowed: bool)
  {
    var grants := AuthorizationSemantics(
      objects,
      permissions,
      definitions,
      relationships
    );
    allowed :=
      Grant(query.subject, query.node, query.resource) in grants;
  }

  lemma LeastFixedPointIsUnique(
    universe: set<Grant>,
    immediate: set<Grant> -> set<Grant>,
    left: set<Grant>,
    right: set<Grant>
  )
    requires left <= universe
    requires right <= universe
    requires immediate(left) == left
    requires immediate(right) == right
    requires forall fixed |
               fixed <= universe && immediate(fixed) == fixed ::
               left <= fixed
    requires forall fixed |
               fixed <= universe && immediate(fixed) == fixed ::
               right <= fixed
    ensures left == right
  {
    assert left <= right;
    assert right <= left;
  }

  predicate SeedlessRules(rules: seq<NormalizedRule>) {
    forall i | 0 <= i < |rules| ::
      rules[i].SelfPermissionRule? ||
      rules[i].ArrowPermissionRule?
  }

  lemma SeedlessCycleHasNoImmediateGrant(
    objects: seq<ObjectRef>,
    permissions: seq<PermissionNode>,
    rules: seq<NormalizedRule>,
    relationships: seq<Relationship>
  )
    requires SeedlessRules(rules)
    ensures ImmediateConsequences(
              objects,
              permissions,
              rules,
              relationships,
              {}
            ) == {}
  {
    assert forall grant |
        grant in GrantUniverse(objects, permissions) ::
        !AnyRuleDerives(rules, relationships, {}, grant) by
    {
      forall grant |
        grant in GrantUniverse(objects, permissions)
        ensures !AnyRuleDerives(rules, relationships, {}, grant)
      {
        if AnyRuleDerives(rules, relationships, {}, grant) {
          var index :| 0 <= index < |rules| &&
                       RuleDerives(rules[index], relationships, {}, grant);
          assert rules[index].SelfPermissionRule? ||
                 rules[index].ArrowPermissionRule?;
          assert false;
        }
      }
    }
  }
}

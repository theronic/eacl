include "Semantics.dfy"

module AcyclicEngine {
  import Semantics

  datatype RawRelationDefinition = RawRelationDefinition(
    resourceType: string,
    relationName: string,
    subjectType: string,
    relationEid: nat
  )

  datatype RawPermissionDefinition = RawPermissionDefinition(
    resourceType: string,
    permissionName: string,
    sourceIsSelf: bool,
    sourceRelationName: string,
    targetIsRelation: bool,
    targetName: string
  )

  datatype MaterializedRelationPath = MaterializedRelationPath(
    relationName: string,
    subjectType: string,
    relationEid: nat
  )

  datatype SourceMaterializedPath =
    | SourceRelationPath(
        relationName: string,
        subjectType: string,
        relationEid: nat
      )
    | SourceSelfPermissionPath(
        targetPermission: string,
        resourceType: string
      )
    | SourceArrowRelationPath(
        viaRelation: string,
        targetType: string,
        viaRelationEid: nat,
        targetRelation: string,
        subPaths: seq<MaterializedRelationPath>
      )
    | SourceArrowPermissionPath(
        viaRelation: string,
        targetType: string,
        viaRelationEid: nat,
        targetPermission: string
      )

  function MatchingRawRelations(
    relations: seq<RawRelationDefinition>,
    resourceType: string,
    relationName: string
  ): seq<RawRelationDefinition>
    decreases |relations|
  {
    if |relations| == 0 then
      []
    else
      (if relations[0].resourceType == resourceType &&
          relations[0].relationName == relationName
       then [relations[0]]
       else []) +
      MatchingRawRelations(
        relations[1..],
        resourceType,
        relationName
      )
  }

  function DirectPaths(
    relations: seq<RawRelationDefinition>
  ): seq<SourceMaterializedPath>
    decreases |relations|
  {
    if |relations| == 0 then
      []
    else
      [SourceRelationPath(
         relations[0].relationName,
         relations[0].subjectType,
         relations[0].relationEid
       )] +
      DirectPaths(relations[1..])
  }

  function RelationSubPaths(
    relations: seq<RawRelationDefinition>
  ): seq<MaterializedRelationPath>
    decreases |relations|
  {
    if |relations| == 0 then
      []
    else
      [MaterializedRelationPath(
         relations[0].relationName,
         relations[0].subjectType,
         relations[0].relationEid
       )] +
      RelationSubPaths(relations[1..])
  }

  function ExpandViaRelations(
    vias: seq<RawRelationDefinition>,
    relations: seq<RawRelationDefinition>,
    definition: RawPermissionDefinition
  ): seq<SourceMaterializedPath>
    decreases |vias|
  {
    if |vias| == 0 then
      []
    else if definition.targetIsRelation then
      var targets :=
        MatchingRawRelations(
          relations,
          vias[0].subjectType,
          definition.targetName
        );
      (if |targets| == 0
       then []
       else [SourceArrowRelationPath(
               definition.sourceRelationName,
               vias[0].subjectType,
               vias[0].relationEid,
               definition.targetName,
               RelationSubPaths(targets)
             )]) +
      ExpandViaRelations(vias[1..], relations, definition)
    else
      [SourceArrowPermissionPath(
         definition.sourceRelationName,
         vias[0].subjectType,
         vias[0].relationEid,
         definition.targetName
       )] +
      ExpandViaRelations(vias[1..], relations, definition)
  }

  function MaterializeRawDefinition(
    relations: seq<RawRelationDefinition>,
    definition: RawPermissionDefinition
  ): seq<SourceMaterializedPath>
  {
    if definition.sourceIsSelf then
      if definition.targetIsRelation then
        DirectPaths(
          MatchingRawRelations(
            relations,
            definition.resourceType,
            definition.targetName
          )
        )
      else
        [SourceSelfPermissionPath(
           definition.targetName,
           definition.resourceType
         )]
    else
      ExpandViaRelations(
        MatchingRawRelations(
          relations,
          definition.resourceType,
          definition.sourceRelationName
        ),
        relations,
        definition
      )
  }

  function MaterializeRawDefinitions(
    relations: seq<RawRelationDefinition>,
    definitions: seq<RawPermissionDefinition>
  ): seq<SourceMaterializedPath>
    decreases |definitions|
  {
    if |definitions| == 0 then
      []
    else
      MaterializeRawDefinition(relations, definitions[0]) +
      MaterializeRawDefinitions(relations, definitions[1..])
  }

  function SourcePathRank(path: SourceMaterializedPath): nat
  {
    match path
    case SourceRelationPath(_, _, _) => 0
    case SourceSelfPermissionPath(_, _) => 1
    case SourceArrowRelationPath(_, _, _, _, _) => 2
    case SourceArrowPermissionPath(_, _, _, _) => 3
  }

  function PathsAtRank(
    paths: seq<SourceMaterializedPath>,
    rank: nat
  ): seq<SourceMaterializedPath>
    decreases |paths|
  {
    if |paths| == 0 then
      []
    else
      (if SourcePathRank(paths[0]) == rank
       then [paths[0]]
       else []) +
      PathsAtRank(paths[1..], rank)
  }

  function RankMaterializedPaths(
    paths: seq<SourceMaterializedPath>
  ): seq<SourceMaterializedPath>
  {
    PathsAtRank(paths, 0) +
    PathsAtRank(paths, 1) +
    PathsAtRank(paths, 2) +
    PathsAtRank(paths, 3)
  }

  function DirectGrantRelationEids(
    paths: seq<SourceMaterializedPath>,
    subjectType: string
  ): seq<nat>
    decreases |paths|
  {
    if |paths| == 0 then
      []
    else
      (match paths[0]
       case SourceRelationPath(_, pathSubjectType, relationEid) =>
         if pathSubjectType == subjectType then [relationEid] else []
       case _ => []) +
      DirectGrantRelationEids(paths[1..], subjectType)
  }

  predicate AllSourcePathsAreRelations(
    paths: seq<SourceMaterializedPath>
  )
    decreases |paths|
  {
    |paths| == 0 ||
    (paths[0].SourceRelationPath? &&
     AllSourcePathsAreRelations(paths[1..]))
  }

  predicate RelationResultsRespectSubjectType(
    paths: seq<SourceMaterializedPath>,
    pathMatches: seq<bool>,
    subjectType: string
  )
    requires |paths| == |pathMatches|
    decreases |paths|
  {
    |paths| == 0 ||
    ((match paths[0]
      case SourceRelationPath(_, pathSubjectType, _) =>
        pathSubjectType == subjectType || !pathMatches[0]
      case _ => true) &&
     RelationResultsRespectSubjectType(
       paths[1..],
       pathMatches[1..],
       subjectType
     ))
  }

  function DirectGrantAny(
    paths: seq<SourceMaterializedPath>,
    pathMatches: seq<bool>,
    subjectType: string
  ): bool
    requires |paths| == |pathMatches|
    decreases |paths|
  {
    |paths| != 0 &&
    ((match paths[0]
      case SourceRelationPath(_, pathSubjectType, _) =>
        pathSubjectType == subjectType && pathMatches[0]
      case _ => false) ||
     DirectGrantAny(paths[1..], pathMatches[1..], subjectType))
  }

  lemma DirectGrantFactsAreSoundAndExhaustive(
    paths: seq<SourceMaterializedPath>,
    pathMatches: seq<bool>,
    subjectType: string
  )
    requires |paths| == |pathMatches|
    requires RelationResultsRespectSubjectType(
               paths,
               pathMatches,
               subjectType
             )
    ensures DirectGrantAny(paths, pathMatches, subjectType) ==>
              AnyTrue(pathMatches)
    ensures AllSourcePathsAreRelations(paths) ==>
              DirectGrantAny(paths, pathMatches, subjectType) ==
              AnyTrue(pathMatches)
    decreases |paths|
  {
    if |paths| != 0 {
      DirectGrantFactsAreSoundAndExhaustive(
        paths[1..],
        pathMatches[1..],
        subjectType
      );
    }
  }

  method MaterializePermissionPaths(
    relations: seq<RawRelationDefinition>,
    definitions: seq<RawPermissionDefinition>,
    subjectType: string
  ) returns (
      paths: seq<SourceMaterializedPath>,
      directRelationEids: seq<nat>,
      exhaustive: bool
    )
    ensures paths ==
            RankMaterializedPaths(
              MaterializeRawDefinitions(relations, definitions)
            )
    ensures directRelationEids ==
            DirectGrantRelationEids(paths, subjectType)
    ensures exhaustive == AllSourcePathsAreRelations(paths)
  {
    paths :=
      RankMaterializedPaths(
        MaterializeRawDefinitions(relations, definitions)
      );
    directRelationEids :=
      DirectGrantRelationEids(paths, subjectType);
    exhaustive := AllSourcePathsAreRelations(paths);
  }

  datatype CompiledPath =
    | RelationPath(
        head: Semantics.PermissionNode,
        relationName: string,
        subjectType: string
      )
    | PermissionPath(
        head: Semantics.PermissionNode,
        sourcePermission: string
      )
    | ArrowRelationPath(
        head: Semantics.PermissionNode,
        viaRelation: string,
        targetRelation: string,
        subjectType: string
      )
    | ArrowPermissionPath(
        head: Semantics.PermissionNode,
        viaRelation: string,
        targetPermission: string
      )

  datatype PathIdentity =
    | PathKey(
        kind: string,
        resourceType: string,
        permissionName: string,
        first: string,
        second: string,
        third: string
      )

  function PathHead(path: CompiledPath): Semantics.PermissionNode {
    match path
    case RelationPath(head, _, _) => head
    case PermissionPath(head, _) => head
    case ArrowRelationPath(head, _, _, _) => head
    case ArrowPermissionPath(head, _, _) => head
  }

  function Identity(path: CompiledPath): PathIdentity {
    match path
    case RelationPath(head, relationName, subjectType) =>
      PathKey(
        "relation",
        head.resourceType,
        head.permissionName,
        relationName,
        subjectType,
        ""
      )
    case PermissionPath(head, sourcePermission) =>
      PathKey(
        "permission",
        head.resourceType,
        head.permissionName,
        sourcePermission,
        "",
        ""
      )
    case ArrowRelationPath(
      head,
      viaRelation,
      targetRelation,
      subjectType
      ) =>
      PathKey(
        "arrow-relation",
        head.resourceType,
        head.permissionName,
        viaRelation,
        targetRelation,
        subjectType
      )
    case ArrowPermissionPath(head, viaRelation, targetPermission) =>
      PathKey(
        "arrow-permission",
        head.resourceType,
        head.permissionName,
        viaRelation,
        targetPermission,
        ""
      )
  }

  lemma PathIdentityIsInjective(left: CompiledPath, right: CompiledPath)
    ensures Identity(left) == Identity(right) <==> left == right
  {
  }

  function CompileDefinition(
    definition: Semantics.RuleDefinition
  ): CompiledPath {
    match definition
    case DirectRelation(head, relationName, subjectType) =>
      RelationPath(head, relationName, subjectType)
    case SelfPermission(head, sourcePermission) =>
      PermissionPath(head, sourcePermission)
    case ArrowRelation(
      head,
      viaRelation,
      targetRelation,
      subjectType
      ) =>
      ArrowRelationPath(
        head,
        viaRelation,
        targetRelation,
        subjectType
      )
    case ArrowPermission(head, viaRelation, targetPermission) =>
      ArrowPermissionPath(head, viaRelation, targetPermission)
  }

  function CompiledRule(
    path: CompiledPath
  ): Semantics.NormalizedRule {
    match path
    case RelationPath(head, relationName, subjectType) =>
      Semantics.DirectRelationRule(head, relationName, subjectType)
    case PermissionPath(head, sourcePermission) =>
      Semantics.SelfPermissionRule(head, sourcePermission)
    case ArrowRelationPath(
      head,
      viaRelation,
      targetRelation,
      subjectType
      ) =>
      Semantics.ArrowRelationRule(
        head,
        viaRelation,
        targetRelation,
        subjectType
      )
    case ArrowPermissionPath(head, viaRelation, targetPermission) =>
      Semantics.ArrowPermissionRule(
        head,
        viaRelation,
        targetPermission
      )
  }

  function CompilePaths(
    definitions: seq<Semantics.RuleDefinition>
  ): seq<CompiledPath>
    ensures |CompilePaths(definitions)| == |definitions|
    decreases |definitions|
  {
    if |definitions| == 0 then
      []
    else
      [CompileDefinition(definitions[0])] +
      CompilePaths(definitions[1..])
  }

  function PathDependencies(
    path: CompiledPath,
    permissions: seq<Semantics.PermissionNode>
  ): set<Semantics.PermissionNode> {
    match path
    case RelationPath(_, _, _) => {}
    case PermissionPath(head, sourcePermission) =>
      set node <- permissions |
          node.resourceType == head.resourceType &&
          node.permissionName == sourcePermission
    case ArrowRelationPath(_, _, _, _) => {}
    case ArrowPermissionPath(_, _, targetPermission) =>
      set node <- permissions |
          node.permissionName == targetPermission
  }

  function PermissionUniverse(
    permissions: seq<Semantics.PermissionNode>
  ): set<Semantics.PermissionNode> {
    set node <- permissions
  }

  function DependencyStep(
    paths: seq<CompiledPath>,
    permissions: seq<Semantics.PermissionNode>,
    current: set<Semantics.PermissionNode>
  ): set<Semantics.PermissionNode> {
    current +
    set dependency <- PermissionUniverse(permissions) |
        exists path <- paths ::
          PathHead(path) in current &&
          dependency in PathDependencies(path, permissions)
  }

  method IterateDependencyClosure(
    paths: seq<CompiledPath>,
    permissions: seq<Semantics.PermissionNode>,
    current: set<Semantics.PermissionNode>
  ) returns (result: set<Semantics.PermissionNode>)
    requires current <= PermissionUniverse(permissions)
    ensures current <= result
    ensures result <= PermissionUniverse(permissions)
    ensures DependencyStep(paths, permissions, result) == result
    ensures forall fixed |
              current <= fixed &&
              fixed <= PermissionUniverse(permissions) &&
              DependencyStep(paths, permissions, fixed) == fixed ::
              result <= fixed
    decreases PermissionUniverse(permissions) - current
  {
    var universe := PermissionUniverse(permissions);
    var next := DependencyStep(paths, permissions, current);
    assert current <= next;
    assert next <= universe;

    if next == current {
      return current;
    }

    assert current < next;
    assert universe - next < universe - current;
    result := IterateDependencyClosure(paths, permissions, next);

    forall fixed |
      current <= fixed &&
      fixed <= universe &&
      DependencyStep(paths, permissions, fixed) == fixed
      ensures result <= fixed
    {
      assert next <= fixed;
    }
  }

  method ReachablePermissionNodes(
    root: Semantics.PermissionNode,
    paths: seq<CompiledPath>,
    permissions: seq<Semantics.PermissionNode>
  ) returns (result: set<Semantics.PermissionNode>)
    ensures result <= PermissionUniverse(permissions)
    ensures DependencyStep(paths, permissions, result) == result
    ensures root in PermissionUniverse(permissions) ==> root in result
    ensures forall fixed |
              (root in PermissionUniverse(permissions) ==> root in fixed) &&
              fixed <= PermissionUniverse(permissions) &&
              DependencyStep(paths, permissions, fixed) == fixed ::
              result <= fixed
  {
    var initial :=
      if root in PermissionUniverse(permissions)
      then {root}
      else {};
    result := IterateDependencyClosure(paths, permissions, initial);
  }

  function CompiledReachableRules(
    paths: seq<CompiledPath>,
    reachable: set<Semantics.PermissionNode>
  ): seq<Semantics.NormalizedRule>
    decreases |paths|
  {
    if |paths| == 0 then
      []
    else
      (if PathHead(paths[0]) in reachable
       then [CompiledRule(paths[0])]
       else []) +
      CompiledReachableRules(paths[1..], reachable)
  }

  function NormalizedReachableRules(
    definitions: seq<Semantics.RuleDefinition>,
    reachable: set<Semantics.PermissionNode>
  ): seq<Semantics.NormalizedRule>
    decreases |definitions|
  {
    if |definitions| == 0 then
      []
    else
      (if definitions[0].head in reachable
       then [Semantics.NormalizeDefinition(definitions[0])]
       else []) +
      NormalizedReachableRules(definitions[1..], reachable)
  }

  lemma ReachableCompilationPreservesDenotation(
    definitions: seq<Semantics.RuleDefinition>,
    reachable: set<Semantics.PermissionNode>
  )
    ensures CompiledReachableRules(
              CompilePaths(definitions),
              reachable
            ) ==
            NormalizedReachableRules(definitions, reachable)
    decreases |definitions|
  {
    if |definitions| != 0 {
      CompiledDefinitionPreservesDenotation(definitions[0]);
      ReachableCompilationPreservesDenotation(
        definitions[1..],
        reachable
      );
    }
  }

  lemma CompiledDefinitionPreservesDenotation(
    definition: Semantics.RuleDefinition
  )
    ensures CompiledRule(CompileDefinition(definition)) ==
            Semantics.NormalizeDefinition(definition)
  {
  }

  lemma CompiledPathsPreserveEveryRule(
    definitions: seq<Semantics.RuleDefinition>,
    index: nat
  )
    requires index < |definitions|
    ensures CompiledRule(CompilePaths(definitions)[index]) ==
            Semantics.Normalize(definitions)[index]
    decreases |definitions|
  {
    if index == 0 {
      CompiledDefinitionPreservesDenotation(definitions[0]);
    } else {
      CompiledPathsPreserveEveryRule(
        definitions[1..],
        index - 1
      );
      Semantics.NormalizationPreservesEveryRule(
        definitions,
        index
      );
    }
  }

  predicate DirectOnly(
    definitions: seq<Semantics.RuleDefinition>
  ) {
    forall i | 0 <= i < |definitions| ::
      definitions[i].DirectRelation?
  }

  ghost predicate LeastFixedPoint(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>
  ) {
    grants <= Semantics.GrantUniverse(objects, permissions) &&
    Semantics.ImmediateConsequences(
      objects,
      permissions,
      Semantics.Normalize(definitions),
      relationships,
      grants
    ) == grants &&
    (forall fixed |
       fixed <= Semantics.GrantUniverse(objects, permissions) &&
       Semantics.ImmediateConsequences(
         objects,
         permissions,
         Semantics.Normalize(definitions),
         relationships,
         fixed
       ) == fixed ::
       grants <= fixed)
  }

  ghost predicate SemanticallyAuthorized(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    grant: Semantics.Grant
  ) {
    exists grants ::
      LeastFixedPoint(
        objects,
        permissions,
        definitions,
        relationships,
        grants
      ) &&
      grant in grants
  }

  lemma LeastFixedPointMembership(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>,
    grant: Semantics.Grant
  )
    requires LeastFixedPoint(
               objects,
               permissions,
               definitions,
               relationships,
               grants
             )
    ensures grant in grants <==>
            SemanticallyAuthorized(
              objects,
              permissions,
              definitions,
              relationships,
              grant
            )
  {
    if grant in grants {
      assert SemanticallyAuthorized(
          objects,
          permissions,
          definitions,
          relationships,
          grant
        );
    }
    if SemanticallyAuthorized(
        objects,
        permissions,
        definitions,
        relationships,
        grant
      ) {
      var other :| LeastFixedPoint(
          objects,
          permissions,
          definitions,
          relationships,
          other
        ) && grant in other;
      assert grants <= other;
      assert other <= grants;
      assert grants == other;
    }
  }

  predicate DirectDefinitionDerives(
    definition: Semantics.RuleDefinition,
    relationships: seq<Semantics.Relationship>,
    grant: Semantics.Grant
  ) {
    definition.DirectRelation? &&
    grant.node == definition.head &&
    grant.resource.typeName == definition.head.resourceType &&
    grant.subject.typeName == definition.subjectType &&
    Semantics.HasRelationship(
      relationships,
      grant.resource,
      definition.relationName,
      grant.subject
    )
  }

  predicate DirectDerives(
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    grant: Semantics.Grant
  ) {
    exists i | 0 <= i < |definitions| ::
      DirectDefinitionDerives(
        definitions[i],
        relationships,
        grant
      )
  }

  lemma DirectDefinitionEqualsNormalizedRule(
    definition: Semantics.RuleDefinition,
    relationships: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>,
    grant: Semantics.Grant
  )
    requires definition.DirectRelation?
    ensures DirectDefinitionDerives(
              definition,
              relationships,
              grant
            ) <==>
            Semantics.RuleDerives(
              Semantics.NormalizeDefinition(definition),
              relationships,
              grants,
              grant
            )
  {
  }

  lemma DirectDerivationEqualsNormalizedRules(
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>,
    grant: Semantics.Grant
  )
    requires DirectOnly(definitions)
    ensures DirectDerives(
              definitions,
              relationships,
              grant
            ) <==>
            Semantics.AnyRuleDerives(
              Semantics.Normalize(definitions),
              relationships,
              grants,
              grant
            )
  {
    if DirectDerives(definitions, relationships, grant) {
      var index :| 0 <= index < |definitions| &&
                   DirectDefinitionDerives(
                     definitions[index],
                     relationships,
                     grant
                   );
      Semantics.NormalizationPreservesEveryRule(
        definitions,
        index
      );
      DirectDefinitionEqualsNormalizedRule(
        definitions[index],
        relationships,
        grants,
        grant
      );
    }
    if Semantics.AnyRuleDerives(
        Semantics.Normalize(definitions),
        relationships,
        grants,
        grant
      ) {
      var index :| 0 <= index < |definitions| &&
                   Semantics.RuleDerives(
                     Semantics.Normalize(definitions)[index],
                     relationships,
                     grants,
                     grant
                   );
      assert index < |definitions|;
      Semantics.NormalizationPreservesEveryRule(
        definitions,
        index
      );
      DirectDefinitionEqualsNormalizedRule(
        definitions[index],
        relationships,
        grants,
        grant
      );
    }
  }

  function DirectGrantSet(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>
  ): set<Semantics.Grant> {
    set grant <-
          Semantics.GrantUniverse(objects, permissions) |
        DirectDerives(definitions, relationships, grant)
  }

  lemma DirectGrantSetIsFixed(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>
  )
    requires DirectOnly(definitions)
    ensures Semantics.ImmediateConsequences(
              objects,
              permissions,
              Semantics.Normalize(definitions),
              relationships,
              DirectGrantSet(
                objects,
                permissions,
                definitions,
                relationships
              )
            ) ==
            DirectGrantSet(
              objects,
              permissions,
              definitions,
              relationships
            )
  {
    var direct := DirectGrantSet(
      objects,
      permissions,
      definitions,
      relationships
    );
    forall grant |
      grant in Semantics.ImmediateConsequences(
                 objects,
                 permissions,
                 Semantics.Normalize(definitions),
                 relationships,
                 direct
               )
      ensures grant in direct
    {
      if grant !in direct {
        DirectDerivationEqualsNormalizedRules(
          definitions,
          relationships,
          direct,
          grant
        );
      }
    }
    forall grant |
      grant in direct
      ensures grant in Semantics.ImmediateConsequences(
                         objects,
                         permissions,
                         Semantics.Normalize(definitions),
                         relationships,
                         direct
                       )
    {
    }
  }

  lemma DirectGrantIsInEveryFixedPoint(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    fixed: set<Semantics.Grant>,
    grant: Semantics.Grant
  )
    requires DirectOnly(definitions)
    requires grant in DirectGrantSet(
                        objects,
                        permissions,
                        definitions,
                        relationships
                      )
    requires fixed <=
             Semantics.GrantUniverse(objects, permissions)
    requires Semantics.ImmediateConsequences(
               objects,
               permissions,
               Semantics.Normalize(definitions),
               relationships,
               fixed
             ) == fixed
    ensures grant in fixed
  {
    DirectDerivationEqualsNormalizedRules(
      definitions,
      relationships,
      fixed,
      grant
    );
  }

  lemma DirectGrantSetIsLeastFixedPoint(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>
  )
    requires DirectOnly(definitions)
    ensures LeastFixedPoint(
              objects,
              permissions,
              definitions,
              relationships,
              DirectGrantSet(
                objects,
                permissions,
                definitions,
                relationships
              )
            )
  {
    var direct := DirectGrantSet(
      objects,
      permissions,
      definitions,
      relationships
    );
    DirectGrantSetIsFixed(
      objects,
      permissions,
      definitions,
      relationships
    );
    forall fixed |
      fixed <= Semantics.GrantUniverse(objects, permissions) &&
      Semantics.ImmediateConsequences(
        objects,
        permissions,
        Semantics.Normalize(definitions),
        relationships,
        fixed
      ) == fixed
      ensures direct <= fixed
    {
      forall grant | grant in direct
        ensures grant in fixed
      {
        DirectGrantIsInEveryFixedPoint(
          objects,
          permissions,
          definitions,
          relationships,
          fixed,
          grant
        );
      }
    }
  }

  method DirectCan(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    query: Semantics.Query
  ) returns (allowed: bool)
    requires DirectOnly(definitions)
    ensures allowed <==>
            Semantics.Grant(
              query.subject,
              query.node,
              query.resource
            ) in Semantics.GrantUniverse(objects, permissions) &&
            DirectDerives(
              definitions,
              relationships,
              Semantics.Grant(
                query.subject,
                query.node,
                query.resource
              )
            )
    ensures allowed <==>
            SemanticallyAuthorized(
              objects,
              permissions,
              definitions,
              relationships,
              Semantics.Grant(query.subject, query.node, query.resource)
            )
  {
    var grants := Semantics.AuthorizationSemantics(
      objects,
      permissions,
      definitions,
      relationships
    );
    var target := Semantics.Grant(
      query.subject,
      query.node,
      query.resource
    );
    var direct := DirectGrantSet(
      objects,
      permissions,
      definitions,
      relationships
    );
    DirectGrantSetIsFixed(
      objects,
      permissions,
      definitions,
      relationships
    );
    DirectGrantSetIsLeastFixedPoint(
      objects,
      permissions,
      definitions,
      relationships
    );
    assert grants <= direct;
    if target in direct {
      DirectGrantIsInEveryFixedPoint(
        objects,
        permissions,
        definitions,
        relationships,
        grants,
        target
      );
    }
    assert target in grants <==> target in direct;
    allowed := target in direct;
    LeastFixedPointMembership(
      objects,
      permissions,
      definitions,
      relationships,
      direct,
      target
    );
  }

  function AnyTrue(values: seq<bool>): bool
    decreases |values|
  {
    |values| != 0 && (values[0] || AnyTrue(values[1..]))
  }

  function FirstTrueCandidateChecks(values: seq<bool>): nat
    decreases |values|
  {
    if |values| == 0
    then 0
    else if values[0]
      then 1
      else 1 + FirstTrueCandidateChecks(values[1..])
  }

  lemma FirstTrueCandidateChecksProperties(values: seq<bool>)
    ensures FirstTrueCandidateChecks(values) <= |values|
    ensures |values| == 0 ==>
              FirstTrueCandidateChecks(values) == 0
    ensures |values| != 0 ==>
              FirstTrueCandidateChecks(values) != 0
    decreases |values|
  {
    if |values| != 0 && !values[0] {
      FirstTrueCandidateChecksProperties(values[1..]);
    }
  }

  method AcyclicArrowPathDecisionWithWork(
    fullCandidateMatches: seq<bool>,
    directIntersects: bool,
    exhaustive: bool
  ) returns (
      allowed: bool,
      directIntersectionPhases: nat,
      fullCandidateChecks: nat
    )
    requires directIntersects ==> AnyTrue(fullCandidateMatches)
    requires exhaustive ==>
               directIntersects == AnyTrue(fullCandidateMatches)
    ensures allowed == AnyTrue(fullCandidateMatches)
    ensures directIntersectionPhases ==
            (if |fullCandidateMatches| <= 1 then 0 else 1)
    ensures fullCandidateChecks ==
            (if |fullCandidateMatches| <= 1 ||
                (!directIntersects && !exhaustive)
             then FirstTrueCandidateChecks(fullCandidateMatches)
             else 0)
    ensures fullCandidateChecks <= |fullCandidateMatches|
  {
    FirstTrueCandidateChecksProperties(fullCandidateMatches);
    if |fullCandidateMatches| <= 1 {
      allowed := AnyTrue(fullCandidateMatches);
      directIntersectionPhases := 0;
      fullCandidateChecks :=
        FirstTrueCandidateChecks(fullCandidateMatches);
      return;
    }

    directIntersectionPhases := 1;
    if directIntersects {
      allowed := true;
      fullCandidateChecks := 0;
      return;
    }

    if exhaustive {
      allowed := false;
      fullCandidateChecks := 0;
      return;
    }

    allowed := AnyTrue(fullCandidateMatches);
    fullCandidateChecks :=
      FirstTrueCandidateChecks(fullCandidateMatches);
  }

  function EffectiveAcyclicPathResult(
    kind: nat,
    directSubjectTypeMatches: bool,
    pathResult: bool
  ): bool
    requires kind < 3
  {
    if kind == 0
    then directSubjectTypeMatches && pathResult
    else pathResult
  }

  function AnyEffectiveAcyclicPathResult(
    kinds: seq<nat>,
    directSubjectTypeMatches: seq<bool>,
    pathResults: seq<bool>
  ): bool
    requires |kinds| == |directSubjectTypeMatches| == |pathResults|
    requires forall i | 0 <= i < |kinds| :: kinds[i] < 3
    decreases |kinds|
  {
    |kinds| != 0 &&
    (EffectiveAcyclicPathResult(
       kinds[0],
       directSubjectTypeMatches[0],
       pathResults[0]
     ) ||
     AnyEffectiveAcyclicPathResult(
       kinds[1..],
       directSubjectTypeMatches[1..],
       pathResults[1..]
     ))
  }

  function FirstEffectiveAcyclicPathChecks(
    kinds: seq<nat>,
    directSubjectTypeMatches: seq<bool>,
    pathResults: seq<bool>
  ): nat
    requires |kinds| == |directSubjectTypeMatches| == |pathResults|
    requires forall i | 0 <= i < |kinds| :: kinds[i] < 3
    decreases |kinds|
  {
    if |kinds| == 0 then
      0
    else if EffectiveAcyclicPathResult(
              kinds[0],
              directSubjectTypeMatches[0],
              pathResults[0]
            ) then
      1
    else
      1 +
      FirstEffectiveAcyclicPathChecks(
        kinds[1..],
        directSubjectTypeMatches[1..],
        pathResults[1..]
      )
  }

  function AcyclicCallbackTrace(
    kinds: seq<nat>,
    directSubjectTypeMatches: seq<bool>,
    checks: nat,
    offset: nat
  ): seq<seq<nat>>
    requires |kinds| == |directSubjectTypeMatches|
    requires forall i | 0 <= i < |kinds| :: kinds[i] < 3
    decreases checks
  {
    if checks == 0 || |kinds| == 0 then
      []
    else
      (if kinds[0] == 0 && !directSubjectTypeMatches[0]
       then []
       else [[kinds[0], offset]]) +
      AcyclicCallbackTrace(
        kinds[1..],
        directSubjectTypeMatches[1..],
        checks - 1,
        offset + 1
      )
  }

  function CallbackKindCount(
    trace: seq<seq<nat>>,
    kind: nat
  ): nat
    decreases |trace|
  {
    if |trace| == 0 then
      0
    else (if |trace[0]| != 0 && trace[0][0] == kind then 1 else 0) +
         CallbackKindCount(trace[1..], kind)
  }

  lemma FirstEffectiveAcyclicPathChecksProperties(
    kinds: seq<nat>,
    directSubjectTypeMatches: seq<bool>,
    pathResults: seq<bool>
  )
    requires |kinds| == |directSubjectTypeMatches| == |pathResults|
    requires forall i | 0 <= i < |kinds| :: kinds[i] < 3
    ensures FirstEffectiveAcyclicPathChecks(
              kinds,
              directSubjectTypeMatches,
              pathResults
            ) <= |kinds|
    decreases |kinds|
  {
    if |kinds| != 0 &&
       !EffectiveAcyclicPathResult(
         kinds[0],
         directSubjectTypeMatches[0],
         pathResults[0]
       ) {
      FirstEffectiveAcyclicPathChecksProperties(
        kinds[1..],
        directSubjectTypeMatches[1..],
        pathResults[1..]
      );
    }
  }

  lemma AcyclicCallbackTraceProperties(
    kinds: seq<nat>,
    directSubjectTypeMatches: seq<bool>,
    checks: nat,
    offset: nat
  )
    requires |kinds| == |directSubjectTypeMatches|
    requires checks <= |kinds|
    requires forall i | 0 <= i < |kinds| :: kinds[i] < 3
    ensures |AcyclicCallbackTrace(
              kinds,
              directSubjectTypeMatches,
              checks,
              offset
            )| <= checks
    ensures forall event <-
                     AcyclicCallbackTrace(
                       kinds,
                       directSubjectTypeMatches,
                       checks,
                       offset
                     ) :: |event| == 2
    decreases checks
  {
    if checks != 0 {
      AcyclicCallbackTraceProperties(
        kinds[1..],
        directSubjectTypeMatches[1..],
        checks - 1,
        offset + 1
      );
    }
  }

  lemma CallbackKindCountBound(
    trace: seq<seq<nat>>,
    kind: nat
  )
    ensures CallbackKindCount(trace, kind) <= |trace|
    decreases |trace|
  {
    if |trace| != 0 {
      CallbackKindCountBound(trace[1..], kind);
    }
  }

  method AcyclicPathFoldWithTrace(
    visited: bool,
    kinds: seq<nat>,
    directSubjectTypeMatches: seq<bool>,
    pathResults: seq<bool>
  ) returns (
      allowed: bool,
      pathChecks: nat,
      directProbeChecks: nat,
      selfPermissionChecks: nat,
      arrowChecks: nat,
      callbackTrace: seq<seq<nat>>
    )
    requires |kinds| == |directSubjectTypeMatches| == |pathResults|
    requires forall i | 0 <= i < |kinds| :: kinds[i] < 3
    ensures allowed ==
            (!visited &&
             AnyEffectiveAcyclicPathResult(
               kinds,
               directSubjectTypeMatches,
               pathResults
             ))
    ensures pathChecks ==
            (if visited
             then 0
             else FirstEffectiveAcyclicPathChecks(
                 kinds,
                 directSubjectTypeMatches,
                 pathResults
               ))
    ensures callbackTrace ==
            AcyclicCallbackTrace(
              kinds,
              directSubjectTypeMatches,
              pathChecks,
              0
            )
    ensures directProbeChecks == CallbackKindCount(callbackTrace, 0)
    ensures selfPermissionChecks == CallbackKindCount(callbackTrace, 1)
    ensures arrowChecks == CallbackKindCount(callbackTrace, 2)
    ensures pathChecks <= |kinds|
    ensures directProbeChecks <= pathChecks
    ensures selfPermissionChecks <= pathChecks
    ensures arrowChecks <= pathChecks
  {
    FirstEffectiveAcyclicPathChecksProperties(
      kinds,
      directSubjectTypeMatches,
      pathResults
    );
    if visited {
      allowed := false;
      pathChecks := 0;
    } else {
      allowed :=
        AnyEffectiveAcyclicPathResult(
          kinds,
          directSubjectTypeMatches,
          pathResults
        );
      pathChecks :=
        FirstEffectiveAcyclicPathChecks(
          kinds,
          directSubjectTypeMatches,
          pathResults
        );
    }
    callbackTrace :=
      AcyclicCallbackTrace(
        kinds,
        directSubjectTypeMatches,
        pathChecks,
        0
      );
    AcyclicCallbackTraceProperties(
      kinds,
      directSubjectTypeMatches,
      pathChecks,
      0
    );
    directProbeChecks := CallbackKindCount(callbackTrace, 0);
    selfPermissionChecks := CallbackKindCount(callbackTrace, 1);
    arrowChecks := CallbackKindCount(callbackTrace, 2);
    CallbackKindCountBound(callbackTrace, 0);
    CallbackKindCountBound(callbackTrace, 1);
    CallbackKindCountBound(callbackTrace, 2);
  }

  function ForwardProjection(
    objects: seq<Semantics.ObjectRef>,
    grants: set<Semantics.Grant>,
    subject: Semantics.ObjectRef,
    node: Semantics.PermissionNode
  ): seq<Semantics.ObjectRef>
    ensures |ForwardProjection(objects, grants, subject, node)| <=
            |objects|
    decreases |objects|
  {
    if |objects| == 0 then
      []
    else
      (if Semantics.Grant(subject, node, objects[0]) in grants
       then [objects[0]]
       else []) +
      ForwardProjection(objects[1..], grants, subject, node)
  }

  function ReverseProjection(
    objects: seq<Semantics.ObjectRef>,
    grants: set<Semantics.Grant>,
    resource: Semantics.ObjectRef,
    node: Semantics.PermissionNode
  ): seq<Semantics.ObjectRef>
    ensures |ReverseProjection(objects, grants, resource, node)| <=
            |objects|
    decreases |objects|
  {
    if |objects| == 0 then
      []
    else
      (if Semantics.Grant(objects[0], node, resource) in grants
       then [objects[0]]
       else []) +
      ReverseProjection(objects[1..], grants, resource, node)
  }

  predicate UniqueObjects(objects: seq<Semantics.ObjectRef>) {
    forall i, j | 0 <= i < j < |objects| ::
      objects[i] != objects[j]
  }

  lemma ForwardProjectionMembership(
    objects: seq<Semantics.ObjectRef>,
    grants: set<Semantics.Grant>,
    subject: Semantics.ObjectRef,
    node: Semantics.PermissionNode,
    resource: Semantics.ObjectRef
  )
    ensures resource in ForwardProjection(
                          objects,
                          grants,
                          subject,
                          node
                        ) <==>
            resource in objects &&
            Semantics.Grant(subject, node, resource) in grants
    decreases |objects|
  {
    if |objects| != 0 {
      ForwardProjectionMembership(
        objects[1..],
        grants,
        subject,
        node,
        resource
      );
    }
  }

  lemma ReverseProjectionMembership(
    objects: seq<Semantics.ObjectRef>,
    grants: set<Semantics.Grant>,
    resource: Semantics.ObjectRef,
    node: Semantics.PermissionNode,
    subject: Semantics.ObjectRef
  )
    ensures subject in ReverseProjection(
                         objects,
                         grants,
                         resource,
                         node
                       ) <==>
            subject in objects &&
            Semantics.Grant(subject, node, resource) in grants
    decreases |objects|
  {
    if |objects| != 0 {
      ReverseProjectionMembership(
        objects[1..],
        grants,
        resource,
        node,
        subject
      );
    }
  }

  lemma ForwardProjectionIsUnique(
    objects: seq<Semantics.ObjectRef>,
    grants: set<Semantics.Grant>,
    subject: Semantics.ObjectRef,
    node: Semantics.PermissionNode
  )
    requires UniqueObjects(objects)
    ensures UniqueObjects(
              ForwardProjection(objects, grants, subject, node)
            )
    decreases |objects|
  {
    if |objects| != 0 {
      assert UniqueObjects(objects[1..]);
      ForwardProjectionIsUnique(
        objects[1..],
        grants,
        subject,
        node
      );
      if Semantics.Grant(
          subject,
          node,
          objects[0]
        ) in grants {
        assert objects[0] !in objects[1..];
        ForwardProjectionMembership(
          objects[1..],
          grants,
          subject,
          node,
          objects[0]
        );
        assert objects[0] !in ForwardProjection(
            objects[1..],
            grants,
            subject,
            node
          );
      }
    }
  }

  lemma ReverseProjectionIsUnique(
    objects: seq<Semantics.ObjectRef>,
    grants: set<Semantics.Grant>,
    resource: Semantics.ObjectRef,
    node: Semantics.PermissionNode
  )
    requires UniqueObjects(objects)
    ensures UniqueObjects(
              ReverseProjection(objects, grants, resource, node)
            )
    decreases |objects|
  {
    if |objects| != 0 {
      assert UniqueObjects(objects[1..]);
      ReverseProjectionIsUnique(
        objects[1..],
        grants,
        resource,
        node
      );
      if Semantics.Grant(
          objects[0],
          node,
          resource
        ) in grants {
        assert objects[0] !in objects[1..];
        ReverseProjectionMembership(
          objects[1..],
          grants,
          resource,
          node,
          objects[0]
        );
        assert objects[0] !in ReverseProjection(
            objects[1..],
            grants,
            resource,
            node
          );
      }
    }
  }

  ghost predicate ForwardSequenceSpec(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    subject: Semantics.ObjectRef,
    node: Semantics.PermissionNode,
    result: seq<Semantics.ObjectRef>
  ) {
    UniqueObjects(result) &&
    |result| <= |objects| &&
    (forall resource ::
       resource in result <==>
                   resource in objects &&
                   SemanticallyAuthorized(
                     objects,
                     permissions,
                     definitions,
                     relationships,
                     Semantics.Grant(subject, node, resource)
                   ))
  }

  method AcyclicForward(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    subject: Semantics.ObjectRef,
    node: Semantics.PermissionNode
  ) returns (result: seq<Semantics.ObjectRef>)
    requires UniqueObjects(objects)
    ensures UniqueObjects(result)
    ensures |result| <= |objects|
    ensures forall resource ::
              resource in result <==>
                          resource in objects &&
                          SemanticallyAuthorized(
                            objects,
                            permissions,
                            definitions,
                            relationships,
                            Semantics.Grant(subject, node, resource)
                          )
    ensures ForwardSequenceSpec(
              objects,
              permissions,
              definitions,
              relationships,
              subject,
              node,
              result
            )
  {
    var grants := Semantics.AuthorizationSemantics(
      objects,
      permissions,
      definitions,
      relationships
    );
    result := ForwardProjection(objects, grants, subject, node);
    ForwardProjectionIsUnique(objects, grants, subject, node);
    assert LeastFixedPoint(
        objects,
        permissions,
        definitions,
        relationships,
        grants
      );
    forall resource
      ensures resource in result <==>
              resource in objects &&
              SemanticallyAuthorized(
                objects,
                permissions,
                definitions,
                relationships,
                Semantics.Grant(subject, node, resource)
              )
    {
      ForwardProjectionMembership(
        objects,
        grants,
        subject,
        node,
        resource
      );
      LeastFixedPointMembership(
        objects,
        permissions,
        definitions,
        relationships,
        grants,
        Semantics.Grant(subject, node, resource)
      );
    }
  }

  method AcyclicReverse(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    resource: Semantics.ObjectRef,
    node: Semantics.PermissionNode
  ) returns (result: seq<Semantics.ObjectRef>)
    requires UniqueObjects(objects)
    ensures UniqueObjects(result)
    ensures |result| <= |objects|
    ensures forall subject ::
              subject in result <==>
                         subject in objects &&
                         SemanticallyAuthorized(
                           objects,
                           permissions,
                           definitions,
                           relationships,
                           Semantics.Grant(subject, node, resource)
                         )
  {
    var grants := Semantics.AuthorizationSemantics(
      objects,
      permissions,
      definitions,
      relationships
    );
    result := ReverseProjection(objects, grants, resource, node);
    ReverseProjectionIsUnique(objects, grants, resource, node);
    assert LeastFixedPoint(
        objects,
        permissions,
        definitions,
        relationships,
        grants
      );
    forall subject
      ensures subject in result <==>
              subject in objects &&
              SemanticallyAuthorized(
                objects,
                permissions,
                definitions,
                relationships,
                Semantics.Grant(subject, node, resource)
              )
    {
      ReverseProjectionMembership(
        objects,
        grants,
        resource,
        node,
        subject
      );
      LeastFixedPointMembership(
        objects,
        permissions,
        definitions,
        relationships,
        grants,
        Semantics.Grant(subject, node, resource)
      );
    }
  }

  method CountForward(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    subject: Semantics.ObjectRef,
    node: Semantics.PermissionNode,
    countLimit: int
  ) returns (count: nat, truncated: bool)
    requires UniqueObjects(objects)
    ensures count <= |objects|
    ensures countLimit < 0 ==> !truncated
    ensures 0 <= countLimit ==> count <= countLimit
    ensures exists full ::
              ForwardSequenceSpec(
                objects,
                permissions,
                definitions,
                relationships,
                subject,
                node,
                full
              ) &&
              (if 0 <= countLimit && countLimit < |full|
               then count == countLimit && truncated
               else count == |full| && !truncated)
  {
    var values := AcyclicForward(
      objects,
      permissions,
      definitions,
      relationships,
      subject,
      node
    );
    if 0 <= countLimit && countLimit < |values| {
      count := countLimit;
      truncated := true;
    } else {
      count := |values|;
      truncated := false;
    }
    assert exists full ::
        ForwardSequenceSpec(
          objects,
          permissions,
          definitions,
          relationships,
          subject,
          node,
          full
        ) &&
        (if 0 <= countLimit && countLimit < |full|
         then count == countLimit && truncated
         else count == |full| && !truncated);
  }

  predicate StrictAscendingEids(values: seq<nat>)
  {
    forall i, j | 0 <= i < j < |values| ::
      values[i] < values[j]
  }

  const LinearProbeLimit: nat := 16

  function DropEidsBefore(
    values: seq<nat>,
    target: nat
  ): seq<nat>
    decreases |values|
  {
    if |values| == 0 || target <= values[0]
    then values
    else DropEidsBefore(values[1..], target)
  }

  lemma StrictAscendingTail(values: seq<nat>)
    requires StrictAscendingEids(values)
    requires 0 < |values|
    ensures StrictAscendingEids(values[1..])
  {
  }

  lemma DropEidsBeforeProperties(
    values: seq<nat>,
    target: nat
  )
    requires StrictAscendingEids(values)
    ensures StrictAscendingEids(DropEidsBefore(values, target))
    ensures forall value ::
              value in DropEidsBefore(values, target) <==>
                       value in values && target <= value
    ensures |values| != 0 && values[0] < target ==>
              |DropEidsBefore(values, target)| < |values|
    decreases |values|
  {
    if |values| != 0 && values[0] < target {
      StrictAscendingTail(values);
      DropEidsBeforeProperties(values[1..], target);
      forall value
        ensures value in DropEidsBefore(values, target) <==>
                value in values && target <= value
      {
        if value == values[0] {
          assert value < target;
        }
      }
    }
  }

  function AdvanceSortedEids(
    values: seq<nat>,
    target: nat,
    probes: nat
  ): seq<nat>
    requires probes <= LinearProbeLimit
    decreases |values|
  {
    if |values| == 0 || target <= values[0]
    then values
    else if probes == LinearProbeLimit
      then DropEidsBefore(values, target)
      else AdvanceSortedEids(values[1..], target, probes + 1)
  }

  function AdvanceExaminedHeads(
    values: seq<nat>,
    target: nat,
    probes: nat
  ): nat
    requires probes <= LinearProbeLimit
    decreases |values|
  {
    if |values| == 0
    then 0
    else if target <= values[0] || probes == LinearProbeLimit
      then 1
      else 1 + AdvanceExaminedHeads(values[1..], target, probes + 1)
  }

  function AdvanceReseekCalls(
    values: seq<nat>,
    target: nat,
    probes: nat
  ): nat
    requires probes <= LinearProbeLimit
    decreases |values|
  {
    if |values| == 0 || target <= values[0]
    then 0
    else if probes == LinearProbeLimit
      then 1
      else AdvanceReseekCalls(values[1..], target, probes + 1)
  }

  function AdvanceReseekTrace(
    side: nat,
    values: seq<nat>,
    target: nat,
    probes: nat
  ): seq<seq<nat>>
    requires side < 2
    requires probes <= LinearProbeLimit
    decreases |values|
  {
    if |values| == 0 || target <= values[0]
    then []
    else if probes == LinearProbeLimit
      then [[side, target]]
      else AdvanceReseekTrace(
          side,
          values[1..],
          target,
          probes + 1
        )
  }

  lemma AdvanceReseekTraceProperties(
    side: nat,
    values: seq<nat>,
    target: nat,
    probes: nat
  )
    requires side < 2
    requires probes <= LinearProbeLimit
    ensures |AdvanceReseekTrace(side, values, target, probes)| ==
            AdvanceReseekCalls(values, target, probes)
    ensures forall event
              | event in
                  AdvanceReseekTrace(side, values, target, probes) ::
              |event| == 2 &&
              event[0] == side &&
              event[1] == target
    decreases |values|
  {
    if |values| != 0 &&
       values[0] < target &&
       probes < LinearProbeLimit {
      AdvanceReseekTraceProperties(
        side,
        values[1..],
        target,
        probes + 1
      );
    }
  }

  lemma AdvanceSortedEidsProperties(
    values: seq<nat>,
    target: nat,
    probes: nat
  )
    requires StrictAscendingEids(values)
    requires probes <= LinearProbeLimit
    ensures AdvanceSortedEids(values, target, probes) ==
            DropEidsBefore(values, target)
    ensures StrictAscendingEids(
              AdvanceSortedEids(values, target, probes)
            )
    ensures forall value ::
              value in AdvanceSortedEids(values, target, probes) <==>
                       value in values && target <= value
    ensures |values| != 0 && values[0] < target ==>
              |AdvanceSortedEids(values, target, probes)| < |values|
    ensures probes +
            AdvanceExaminedHeads(values, target, probes) <=
            LinearProbeLimit + 1
    ensures AdvanceReseekCalls(values, target, probes) <= 1
    decreases |values|
  {
    DropEidsBeforeProperties(values, target);
    if |values| != 0 &&
       values[0] < target &&
       probes < LinearProbeLimit {
      StrictAscendingTail(values);
      AdvanceSortedEidsProperties(
        values[1..],
        target,
        probes + 1
      );
    }
  }

  method LeapfrogSortedEidsIntersectWithWork(
    left: seq<nat>,
    right: seq<nat>
  ) returns (
      intersects: bool,
      iterations: nat,
      reseekCalls: nat,
      examinedHeads: nat,
      reseekTrace: seq<seq<nat>>
    )
    requires StrictAscendingEids(left)
    requires StrictAscendingEids(right)
    ensures intersects <==>
            exists value :: value in left && value in right
    ensures iterations <= |left| + |right|
    ensures iterations == 0 <==> |left| == 0 || |right| == 0
    ensures reseekCalls <= iterations
    ensures |reseekTrace| == reseekCalls
    ensures forall event | event in reseekTrace ::
              |event| == 2 && event[0] < 2
    ensures examinedHeads <=
            (LinearProbeLimit + 1) * iterations
    decreases |left| + |right|
  {
    if |left| == 0 || |right| == 0 {
      return false, 0, 0, 0, [];
    }

    if left[0] == right[0] {
      assert left[0] in left;
      assert right[0] in right;
      assert exists value :: value in left && value in right;
      return true, 1, 0, 0, [];
    }

    if left[0] < right[0] {
      var next := AdvanceSortedEids(left, right[0], 0);
      AdvanceSortedEidsProperties(left, right[0], 0);
      var currentReseekTrace :=
        AdvanceReseekTrace(0, left, right[0], 0);
      AdvanceReseekTraceProperties(0, left, right[0], 0);
      assert |next| < |left|;
      var remainingIterations: nat;
      var remainingReseekCalls: nat;
      var remainingExaminedHeads: nat;
      var remainingReseekTrace: seq<seq<nat>>;
      intersects,
      remainingIterations,
      remainingReseekCalls,
      remainingExaminedHeads,
      remainingReseekTrace :=
        LeapfrogSortedEidsIntersectWithWork(next, right);
      iterations := 1 + remainingIterations;
      reseekCalls :=
        AdvanceReseekCalls(left, right[0], 0) +
        remainingReseekCalls;
      examinedHeads :=
        AdvanceExaminedHeads(left, right[0], 0) +
        remainingExaminedHeads;
      reseekTrace := currentReseekTrace + remainingReseekTrace;
      assert iterations <= |left| + |right|;
      assert reseekCalls <= iterations;
      assert |reseekTrace| == reseekCalls;
      assert examinedHeads <=
             (LinearProbeLimit + 1) * iterations;
      return;
    }

    var next := AdvanceSortedEids(right, left[0], 0);
    AdvanceSortedEidsProperties(right, left[0], 0);
    var currentReseekTrace :=
      AdvanceReseekTrace(1, right, left[0], 0);
    AdvanceReseekTraceProperties(1, right, left[0], 0);
    assert |next| < |right|;
    var remainingIterations: nat;
    var remainingReseekCalls: nat;
    var remainingExaminedHeads: nat;
    var remainingReseekTrace: seq<seq<nat>>;
    intersects,
    remainingIterations,
    remainingReseekCalls,
    remainingExaminedHeads,
    remainingReseekTrace :=
      LeapfrogSortedEidsIntersectWithWork(left, next);
    iterations := 1 + remainingIterations;
    reseekCalls :=
      AdvanceReseekCalls(right, left[0], 0) +
      remainingReseekCalls;
    examinedHeads :=
      AdvanceExaminedHeads(right, left[0], 0) +
      remainingExaminedHeads;
    reseekTrace := currentReseekTrace + remainingReseekTrace;
    assert iterations <= |left| + |right|;
    assert reseekCalls <= iterations;
    assert |reseekTrace| == reseekCalls;
    assert examinedHeads <=
           (LinearProbeLimit + 1) * iterations;
  }

  method LeapfrogSortedEidsIntersect(
    left: seq<nat>,
    right: seq<nat>
  ) returns (intersects: bool)
    requires StrictAscendingEids(left)
    requires StrictAscendingEids(right)
    ensures intersects <==>
            exists value :: value in left && value in right
  {
    var iterations: nat;
    var reseekCalls: nat;
    var examinedHeads: nat;
    var reseekTrace: seq<seq<nat>>;
    intersects,
    iterations,
    reseekCalls,
    examinedHeads,
    reseekTrace :=
      LeapfrogSortedEidsIntersectWithWork(left, right);
  }

  ghost function PublicCanBeforeRootHoist(
    hasVisibleEndpoints: bool,
    generatedAuthoritative: bool,
    rootDefined: bool,
    generatedResult: bool,
    legacyResult: bool
  ): bool
  {
    if !hasVisibleEndpoints then
      false
    else if generatedAuthoritative && rootDefined then
      generatedResult
    else
      legacyResult
  }

  ghost function PublicCanAfterRootHoist(
    hasVisibleEndpoints: bool,
    generatedAuthoritative: bool,
    rootDefined: bool,
    generatedResult: bool,
    legacyResult: bool
  ): bool
  {
    if !hasVisibleEndpoints then
      false
    else if generatedAuthoritative then
      if rootDefined then generatedResult else false
    else
      legacyResult
  }

  lemma PublicCanRootClassificationHoistPreservesResult(
    hasVisibleEndpoints: bool,
    generatedAuthoritative: bool,
    rootDefined: bool,
    generatedResult: bool,
    legacyResult: bool
  )
    requires !rootDefined ==> !legacyResult
    ensures
      PublicCanBeforeRootHoist(
        hasVisibleEndpoints,
        generatedAuthoritative,
        rootDefined,
        generatedResult,
        legacyResult
      ) ==
      PublicCanAfterRootHoist(
        hasVisibleEndpoints,
        generatedAuthoritative,
        rootDefined,
        generatedResult,
        legacyResult
      )
  {
  }

  ghost function PublicCanRootChecksBeforeHoist(
    hasVisibleEndpoints: bool
  ): nat
  {
    if hasVisibleEndpoints then 2 else 0
  }

  ghost function PublicCanRootChecksAfterHoist(
    hasVisibleEndpoints: bool,
    generatedShadow: bool
  ): nat
  {
    if !hasVisibleEndpoints then 0
    else if generatedShadow then 2
    else 1
  }

  lemma PublicCanRootClassificationHoistReducesWork(
    hasVisibleEndpoints: bool,
    generatedAuthoritative: bool,
    generatedShadow: bool
  )
    requires !(generatedAuthoritative && generatedShadow)
    ensures
      PublicCanRootChecksAfterHoist(
        hasVisibleEndpoints,
        generatedShadow
      ) <=
      PublicCanRootChecksBeforeHoist(hasVisibleEndpoints)
    ensures
      hasVisibleEndpoints && generatedAuthoritative ==>
        PublicCanRootChecksAfterHoist(
          hasVisibleEndpoints,
          generatedShadow
        ) == 1
  {
  }
}

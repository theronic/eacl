include "Semantics.dfy"

module WireFormat {
  import opened Semantics

  const WireVersion: string := "eacl.engine/v1"
  const MaximumSafeInteger: nat := 9007199254740991

  datatype Option<T> = None | Some(value: T)

  datatype WireQuery = WireQuery(
    query: Query,
    offset: int,
    pageSize: int
  )

  datatype RequestField =
    | VersionField(version: string)
    | ObjectsField(objects: seq<ObjectRef>)
    | RelationsField(relations: seq<RelationNode>)
    | PermissionsField(permissions: seq<PermissionNode>)
    | DefinitionsField(definitions: seq<RuleDefinition>)
    | RelationshipsField(relationships: seq<Relationship>)
    | QueryField(query: WireQuery)
    | UnknownField(name: string)

  datatype WireLimits = WireLimits(
    maxFields: nat,
    maxObjects: nat,
    maxRelations: nat,
    maxPermissions: nat,
    maxDefinitions: nat,
    maxRelationships: nat,
    maxIdentityLength: nat,
    maxPageSize: nat
  )

  datatype EngineInput = EngineInput(
    objects: seq<ObjectRef>,
    relations: seq<RelationNode>,
    permissions: seq<PermissionNode>,
    definitions: seq<RuleDefinition>,
    relationships: seq<Relationship>,
    query: Query,
    offset: nat,
    pageSize: nat
  )

  datatype WireError =
    | InvalidLimits
    | UnknownVersion(actualVersion: string)
    | UnknownFieldName(name: string)
    | DuplicateField(name: string)
    | MissingField(name: string)
    | TooManyFields(actualCount: nat, limit: nat)
    | OversizedCollection(name: string, actualCount: nat, limit: nat)
    | InvalidIdentity(name: string, index: nat)
    | InvalidRange(name: string, actualValue: int, limit: nat)

  datatype DecodeResult =
    | Decoded(input: EngineInput)
    | Rejected(error: WireError)

  datatype ResponseField =
    | ResponseVersion(version: string)
    | ResponseResult(result: AuthorizationResult)

  predicate ValidLimits(limits: WireLimits) {
    7 <= limits.maxFields <= MaximumSafeInteger &&
    limits.maxObjects <= MaximumSafeInteger &&
    limits.maxRelations <= MaximumSafeInteger &&
    limits.maxPermissions <= MaximumSafeInteger &&
    limits.maxDefinitions <= MaximumSafeInteger &&
    limits.maxRelationships <= MaximumSafeInteger &&
    0 < limits.maxIdentityLength <= MaximumSafeInteger &&
    0 < limits.maxPageSize <= MaximumSafeInteger
  }

  predicate ValidIdentityBound(value: string, limit: nat) {
    0 < |value| <= limit
  }

  predicate ValidObjectBounds(
    objects: seq<ObjectRef>,
    limit: nat
  ) {
    forall i | 0 <= i < |objects| ::
      ValidIdentityBound(objects[i].typeName, limit) &&
      ValidIdentityBound(objects[i].objectId, limit)
  }

  predicate ValidRelationBounds(
    relations: seq<RelationNode>,
    limit: nat
  ) {
    forall i | 0 <= i < |relations| ::
      ValidIdentityBound(relations[i].resourceType, limit) &&
      ValidIdentityBound(relations[i].relationName, limit) &&
      ValidIdentityBound(relations[i].subjectType, limit)
  }

  predicate ValidPermissionBounds(
    permissions: seq<PermissionNode>,
    limit: nat
  ) {
    forall i | 0 <= i < |permissions| ::
      ValidIdentityBound(permissions[i].resourceType, limit) &&
      ValidIdentityBound(permissions[i].permissionName, limit)
  }

  predicate ValidRuleBound(rule: RuleDefinition, limit: nat) {
    match rule
    case DirectRelation(head, relationName, subjectType) =>
      ValidIdentityBound(head.resourceType, limit) &&
      ValidIdentityBound(head.permissionName, limit) &&
      ValidIdentityBound(relationName, limit) &&
      ValidIdentityBound(subjectType, limit)
    case SelfPermission(head, sourcePermission) =>
      ValidIdentityBound(head.resourceType, limit) &&
      ValidIdentityBound(head.permissionName, limit) &&
      ValidIdentityBound(sourcePermission, limit)
    case ArrowRelation(
      head,
      viaRelation,
      targetRelation,
      subjectType
      ) =>
      ValidIdentityBound(head.resourceType, limit) &&
      ValidIdentityBound(head.permissionName, limit) &&
      ValidIdentityBound(viaRelation, limit) &&
      ValidIdentityBound(targetRelation, limit) &&
      ValidIdentityBound(subjectType, limit)
    case ArrowPermission(head, viaRelation, targetPermission) =>
      ValidIdentityBound(head.resourceType, limit) &&
      ValidIdentityBound(head.permissionName, limit) &&
      ValidIdentityBound(viaRelation, limit) &&
      ValidIdentityBound(targetPermission, limit)
  }

  predicate ValidDefinitionBounds(
    definitions: seq<RuleDefinition>,
    limit: nat
  ) {
    forall i | 0 <= i < |definitions| ::
      ValidRuleBound(definitions[i], limit)
  }

  predicate ValidRelationshipBounds(
    relationships: seq<Relationship>,
    limit: nat
  ) {
    forall i | 0 <= i < |relationships| ::
      ValidIdentityBound(
        relationships[i].resource.typeName,
        limit
      ) &&
      ValidIdentityBound(
        relationships[i].resource.objectId,
        limit
      ) &&
      ValidIdentityBound(relationships[i].relationName, limit) &&
      ValidIdentityBound(
        relationships[i].subject.typeName,
        limit
      ) &&
      ValidIdentityBound(
        relationships[i].subject.objectId,
        limit
      )
  }

  predicate ValidQueryBounds(query: WireQuery, limits: WireLimits) {
    ValidIdentityBound(query.query.subject.typeName,
                       limits.maxIdentityLength) &&
    ValidIdentityBound(query.query.subject.objectId,
                       limits.maxIdentityLength) &&
    ValidIdentityBound(query.query.node.resourceType,
                       limits.maxIdentityLength) &&
    ValidIdentityBound(query.query.node.permissionName,
                       limits.maxIdentityLength) &&
    ValidIdentityBound(query.query.resource.typeName,
                       limits.maxIdentityLength) &&
    ValidIdentityBound(query.query.resource.objectId,
                       limits.maxIdentityLength) &&
    0 <= query.offset <= MaximumSafeInteger &&
    0 < query.pageSize <= limits.maxPageSize
  }

  predicate ValidDecodedInput(input: EngineInput, limits: WireLimits) {
    ValidLimits(limits) &&
    |input.objects| <= limits.maxObjects &&
    |input.relations| <= limits.maxRelations &&
    |input.permissions| <= limits.maxPermissions &&
    |input.definitions| <= limits.maxDefinitions &&
    |input.relationships| <= limits.maxRelationships &&
    ValidObjectBounds(input.objects, limits.maxIdentityLength) &&
    ValidRelationBounds(input.relations, limits.maxIdentityLength) &&
    ValidPermissionBounds(input.permissions, limits.maxIdentityLength) &&
    ValidDefinitionBounds(input.definitions, limits.maxIdentityLength) &&
    ValidRelationshipBounds(
      input.relationships,
      limits.maxIdentityLength
    ) &&
    ValidQueryBounds(
      WireQuery(input.query, input.offset, input.pageSize),
      limits
    )
  }

  method DecodeRequest(
    fields: seq<RequestField>,
    limits: WireLimits
  ) returns (result: DecodeResult)
    ensures result.Decoded? ==> ValidDecodedInput(result.input, limits)
    ensures result.Decoded? ==> |fields| <= limits.maxFields
  {
    if !ValidLimits(limits) {
      return Rejected(InvalidLimits);
    }
    if |fields| > limits.maxFields {
      return Rejected(TooManyFields(|fields|, limits.maxFields));
    }

    var version: Option<string> := None;
    var objects: Option<seq<ObjectRef>> := None;
    var relations: Option<seq<RelationNode>> := None;
    var permissions: Option<seq<PermissionNode>> := None;
    var definitions: Option<seq<RuleDefinition>> := None;
    var relationships: Option<seq<Relationship>> := None;
    var query: Option<WireQuery> := None;
    var i := 0;

    while i < |fields|
      invariant 0 <= i <= |fields|
      invariant version.Some? ==> version.value == WireVersion
      invariant objects.Some? ==>
                  |objects.value| <= limits.maxObjects &&
                  ValidObjectBounds(objects.value, limits.maxIdentityLength)
      invariant relations.Some? ==>
                  |relations.value| <= limits.maxRelations &&
                  ValidRelationBounds(relations.value, limits.maxIdentityLength)
      invariant permissions.Some? ==>
                  |permissions.value| <= limits.maxPermissions &&
                  ValidPermissionBounds(
                    permissions.value,
                    limits.maxIdentityLength
                  )
      invariant definitions.Some? ==>
                  |definitions.value| <= limits.maxDefinitions &&
                  ValidDefinitionBounds(
                    definitions.value,
                    limits.maxIdentityLength
                  )
      invariant relationships.Some? ==>
                  |relationships.value| <= limits.maxRelationships &&
                  ValidRelationshipBounds(
                    relationships.value,
                    limits.maxIdentityLength
                  )
      invariant query.Some? ==> ValidQueryBounds(query.value, limits)
      decreases |fields| - i
    {
      var fieldIndex := i;
      i := i + 1;
      match fields[fieldIndex]
      case VersionField(actual) =>
        if version.Some? {
          return Rejected(DuplicateField("version"));
        } else if actual != WireVersion {
          return Rejected(UnknownVersion(actual));
        } else {
          version := Some(actual);
        }

      case ObjectsField(actual) =>
        if objects.Some? {
          return Rejected(DuplicateField("objects"));
        } else if |actual| > limits.maxObjects {
          return Rejected(
              OversizedCollection(
                "objects",
                |actual|,
                limits.maxObjects
              )
            );
        } else if !ValidObjectBounds(
            actual,
            limits.maxIdentityLength
          ) {
          return Rejected(InvalidIdentity("objects", fieldIndex));
        } else {
          objects := Some(actual);
        }

      case RelationsField(actual) =>
        if relations.Some? {
          return Rejected(DuplicateField("relations"));
        } else if |actual| > limits.maxRelations {
          return Rejected(
              OversizedCollection(
                "relations",
                |actual|,
                limits.maxRelations
              )
            );
        } else if !ValidRelationBounds(
            actual,
            limits.maxIdentityLength
          ) {
          return Rejected(InvalidIdentity("relations", fieldIndex));
        } else {
          relations := Some(actual);
        }

      case PermissionsField(actual) =>
        if permissions.Some? {
          return Rejected(DuplicateField("permissions"));
        } else if |actual| > limits.maxPermissions {
          return Rejected(
              OversizedCollection(
                "permissions",
                |actual|,
                limits.maxPermissions
              )
            );
        } else if !ValidPermissionBounds(
            actual,
            limits.maxIdentityLength
          ) {
          return Rejected(InvalidIdentity("permissions", fieldIndex));
        } else {
          permissions := Some(actual);
        }

      case DefinitionsField(actual) =>
        if definitions.Some? {
          return Rejected(DuplicateField("definitions"));
        } else if |actual| > limits.maxDefinitions {
          return Rejected(
              OversizedCollection(
                "definitions",
                |actual|,
                limits.maxDefinitions
              )
            );
        } else if !ValidDefinitionBounds(
            actual,
            limits.maxIdentityLength
          ) {
          return Rejected(InvalidIdentity("definitions", fieldIndex));
        } else {
          definitions := Some(actual);
        }

      case RelationshipsField(actual) =>
        if relationships.Some? {
          return Rejected(DuplicateField("relationships"));
        } else if |actual| > limits.maxRelationships {
          return Rejected(
              OversizedCollection(
                "relationships",
                |actual|,
                limits.maxRelationships
              )
            );
        } else if !ValidRelationshipBounds(
            actual,
            limits.maxIdentityLength
          ) {
          return Rejected(InvalidIdentity("relationships", fieldIndex));
        } else {
          relationships := Some(actual);
        }

      case QueryField(actual) =>
        if query.Some? {
          return Rejected(DuplicateField("query"));
        } else if !ValidQueryBounds(actual, limits) {
          return Rejected(
              InvalidRange(
                "query",
                if actual.offset < 0 then
                  actual.offset
                else
                  actual.pageSize,
                limits.maxPageSize
              )
            );
        } else {
          query := Some(actual);
        }

      case UnknownField(name) =>
        return Rejected(UnknownFieldName(name));
    }

    if version.None? {
      return Rejected(MissingField("version"));
    }
    if objects.None? {
      return Rejected(MissingField("objects"));
    }
    if relations.None? {
      return Rejected(MissingField("relations"));
    }
    if permissions.None? {
      return Rejected(MissingField("permissions"));
    }
    if definitions.None? {
      return Rejected(MissingField("definitions"));
    }
    if relationships.None? {
      return Rejected(MissingField("relationships"));
    }
    if query.None? {
      return Rejected(MissingField("query"));
    }

    return Decoded(
        EngineInput(
          objects.value,
          relations.value,
          permissions.value,
          definitions.value,
          relationships.value,
          query.value.query,
          query.value.offset,
          query.value.pageSize
        )
      );
  }

  function EncodeResponse(
    result: AuthorizationResult
  ): seq<ResponseField> {
    [ResponseVersion(WireVersion), ResponseResult(result)]
  }

  lemma EncodedResponseIsStrict(result: AuthorizationResult)
    ensures |EncodeResponse(result)| == 2
    ensures EncodeResponse(result)[0] == ResponseVersion(WireVersion)
    ensures EncodeResponse(result)[1] == ResponseResult(result)
    ensures EncodeResponse(result)[0] != EncodeResponse(result)[1]
  {
  }
}

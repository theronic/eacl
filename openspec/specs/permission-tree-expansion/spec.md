# permission-tree-expansion Specification

## Purpose
Defines EACL's snapshot-consistent, bounded shallow permission-tree introspection contract and its scoped behavioral equivalence to SpiceDB.
## Requirements
### Requirement: Expansion requests are strict and unambiguous
`expand-permission-tree` SHALL accept a map containing exactly the required `:resource` and `:permission` keys plus optional `:consistency` and `:timeout-ms`. The resource SHALL be map-like with an unqualified keyword `:type`, a non-nil `:id`, and no non-nil subject `:relation`; the permission SHALL be an unqualified keyword. EACL MUST reject unknown query keys, pagination controls, cache controls, evaluation controls, malformed resources, invalid timeout values, and unsupported consistency descriptors before schema or relationship reads.

#### Scenario: Valid minimal request
- **WHEN** a caller supplies `{:resource (spice-object :document "roadmap") :permission :view}`
- **THEN** EACL defaults consistency to `:minimize-latency` and begins expansion under the client's finite execution timeout

#### Scenario: Unknown query key
- **WHEN** a request contains an unrecognized key such as `:cache?`, `:evaluation`, or a misspelling
- **THEN** EACL throws a typed invalid-request error before snapshot selection

#### Scenario: Subject reference used as resource
- **WHEN** the supplied resource has a non-nil `:relation`
- **THEN** EACL rejects it because Authzed expansion requires an object reference rather than a subject reference

### Requirement: Success returns an explicit PermissionRelationshipTree mapping
A successful call SHALL return `{:expanded-at token :tree-root node}`. `token` SHALL be an authenticated EACL causal token for the selected snapshot. Every node SHALL contain `:expanded-object` as a `SpiceObject`, `:expanded-relation` as a keyword, and exactly one of `:intermediate` or `:leaf`. An intermediate value SHALL be `{:operation :union :children [...]}` and a leaf value SHALL be `{:subjects [...]}` containing `SpiceObject` subject references.

#### Scenario: Direct relation response
- **WHEN** a defined direct relation is expanded
- **THEN** the root is a leaf annotated with the requested resource and relation and its subjects are the direct relationships at `:expanded-at`

#### Scenario: Permission response
- **WHEN** a defined permission is expanded
- **THEN** the root is a union intermediate annotated with the requested resource and permission

#### Scenario: Node oneof invariant
- **WHEN** EACL publishes any tree node
- **THEN** that node has one and only one tree variant and contains no backend entity id or private unresolved marker

### Requirement: Expansion follows SpiceDB shallow semantics for the EACL schema subset
Expansion SHALL recursively follow normalized permission union components, same-resource relation references, same-resource permission references, and supported single-level arrows. Direct relation subjects SHALL remain terminal leaf entries. The type of every arrow intermediate SHALL come from its concrete source-relation definition, so equal backend ids under different object types remain distinct.

#### Scenario: Same-resource relation reference
- **WHEN** a permission component names a direct relation on the same resource
- **THEN** the child is that relation's direct leaf on the same expanded object

#### Scenario: Same-resource permission reference
- **WHEN** a permission component names another permission on the same resource
- **THEN** the child is the referenced permission's expansion on the same expanded object without flattening its union boundary

#### Scenario: Arrow to relation
- **WHEN** a permission component is `source_relation->target_relation`
- **THEN** the arrow branch is a union containing the target-relation leaf for every typed intermediate object directly related through `source_relation`

#### Scenario: Arrow to permission
- **WHEN** a permission component is `source_relation->target_permission`
- **THEN** the arrow branch is a union containing the target-permission expansion for every typed intermediate object directly related through `source_relation`

#### Scenario: Same internal id under different types
- **WHEN** two source-relation partitions contain the same backend entity id under different declared subject types
- **THEN** EACL expands both typed objects independently and never merges them by entity id alone

#### Scenario: Direct leaf is terminal
- **WHEN** a direct relation contains subjects
- **THEN** EACL returns those subject references without replacing them with a transitive authorization denotation

### Requirement: Empty and absent resources preserve schema topology and root identity
Valid schema roots SHALL produce structurally valid empty data-dependent branches. EACL SHALL retain the exact supplied root object independently from optional backend resolution, including numeric or custom ids that do not resolve to an entity.

#### Scenario: Empty direct relation
- **WHEN** a valid direct relation has no relationships for the requested resource
- **THEN** expansion returns a leaf with `:subjects []`

#### Scenario: Absent resource id
- **WHEN** the schema root is valid but the supplied id has no backing entity
- **THEN** EACL returns the supplied root id unchanged and builds the same schema topology with empty data-dependent branches

#### Scenario: Empty arrow
- **WHEN** an arrow's source relation has no intermediate objects
- **THEN** the arrow union remains present with `:children []`

### Requirement: One immutable snapshot governs the whole response
Request schema resolution, relationship reads, external object rendering, and `:expanded-at` issuance SHALL use one selected immutable adapter. EACL SHALL honor the selected backend's advertised consistency modes and MUST NOT weaken an unsupported or unavailable request.

#### Scenario: Concurrent mutation
- **WHEN** schema or relationships change while expansion is running
- **THEN** every node and subject reflects only the immutable snapshot named by `:expanded-at`

#### Scenario: Causal replay
- **WHEN** a caller uses `:expanded-at` with a supported at-least-as-fresh or exact-snapshot descriptor
- **THEN** EACL selects a satisfying snapshot or returns the established typed consistency error

#### Scenario: Unsupported consistency
- **WHEN** a backend cannot honor the requested consistency mode
- **THEN** EACL fails explicitly and returns no tree from a weaker snapshot

### Requirement: Union and direct-subject ordering is non-semantic
`:children` and `:subjects` SHALL be vectors but their order SHALL NOT be a public compatibility guarantee. Equality across backends and with SpiceDB SHALL compare recursively normalized unordered multisets while preserving duplicate multiplicity, node annotations, intermediate boundaries, and tree variants. EACL MUST NOT reject an otherwise valid custom external id merely to impose canonical production ordering.

#### Scenario: Backend index order differs
- **WHEN** equivalent relationships are visited in different orders by two backends
- **THEN** their trees are equivalent after unordered-multiset normalization

#### Scenario: Duplicate logical path
- **WHEN** distinct permission paths produce structurally equal children
- **THEN** normalization preserves both occurrences rather than converting children to a set

#### Scenario: Non-canonical custom id
- **WHEN** a configured codec returns a valid EACL id that `secure-format` cannot encode canonically
- **THEN** expansion returns that id and does not use canonical encoding as an additional validity requirement

### Requirement: Expansion is structurally bounded and all-or-error
All clients SHALL accept a `:permission-tree-limits` configuration map with defaults `{:max-depth 50 :max-schema-components 100000 :max-relationship-values 100000 :max-tree-nodes 100000 :max-leaf-subjects 100000}`. Partial overrides SHALL merge with the defaults; unknown keys, non-positive values, and values outside the portable exact-integer range SHALL be rejected at client construction. These limits SHALL be client configuration and MUST NOT be overridable per request. Exceeding a limit SHALL throw `:eacl.permission-tree/limit-exceeded` with the operation, exceeded dimension, configured limit, and safe consumed-work counters.

#### Scenario: Maximum depth exceeded
- **WHEN** expansion would create a node deeper than `:max-depth`
- **THEN** EACL returns the typed limit error without constructing or returning a partial tree

#### Scenario: Relationship limit exceeded
- **WHEN** consuming the next source or leaf relationship would exceed `:max-relationship-values`
- **THEN** EACL fails before publishing the accumulated tree

#### Scenario: Per-request limit override
- **WHEN** a request contains `:permission-tree-limits`
- **THEN** EACL rejects the request before snapshot selection

### Requirement: Cycles and deadlines fail closed
Expansion SHALL track only the active expansion path, allowing repeated nodes in sibling branches while rejecting a node revisited on its current path with `:eacl.permission-tree/cycle-detected`. The operation SHALL share EACL's single monotonic execution deadline and SHALL check it before and after selection, schema reads, relationship value realization, child scheduling, rendering, and token issuance. No cycle, limit, codec, adapter, or deadline failure SHALL return a partial tree.

#### Scenario: Active-path cycle
- **WHEN** same-resource or arrow permission recursion revisits an expansion active on the current path
- **THEN** EACL throws the typed cycle error with safe path metadata and no response

#### Scenario: Diamond graph
- **WHEN** sibling branches reach the same expansion but it is not active in one branch while the other is evaluated
- **THEN** both branches are legal and appear independently

#### Scenario: Deadline during adapter work
- **WHEN** the deadline expires while one synchronous adapter operation or sequence realization is already running
- **THEN** EACL starts no later work and throws `:eacl.execution/deadline-exceeded` after that operation returns or aborts, without claiming hard cancellation

### Requirement: Boundary violations never leak internal identities
Actual scanned internal ids SHALL be externalized through the selected adapter exactly once per request-local memoized identity. A missing external value, malformed schema definition, contradictory relation/permission root, or invalid adapter output SHALL fail with a typed codec or adapter-contract error. Error diagnostics MUST NOT expose backend entity ids or relationship subject values.

#### Scenario: Codec returns nil for a scanned subject
- **WHEN** the selected adapter cannot externalize an internal id returned by its own relationship scan
- **THEN** expansion fails closed with a typed boundary error and no partial tree

#### Scenario: Corrupt root definitions
- **WHEN** an adapter returns both relation and permission definitions for the same root name or malformed definition rows
- **THEN** EACL reports an adapter-contract violation instead of choosing an interpretation

### Requirement: SpiceDB compatibility is scoped and reproducible
For EACL-supported union, same-resource reference, and single-level arrow schemas using SpiceDB-valid string object ids, shallow tree topology, expanded annotations, empty branches, duplicate multiplicity, and direct-subject membership SHALL equal the response captured from a version-pinned SpiceDB Docker image after mechanical field conversion and unordered-multiset normalization. Authzed features rejected by EACL schema validation, non-string custom ids, token byte equality, incidental vector order, error-code identity, and resource-limit timing SHALL remain outside the equivalence claim.

#### Scenario: Provenance-bearing golden fixture
- **WHEN** a supported fixture records its schema, relationships, request, Docker image tag and digest, and raw protobuf JSON response
- **THEN** mechanical normalization of its tree equals every shipped backend's result

#### Scenario: Unsupported feature
- **WHEN** a schema contains intersection, exclusion, subject relations, caveats, wildcards, `.all`, multi-level arrows, or another already rejected feature
- **THEN** expansion does not weaken schema validation or claim SpiceDB equivalence for it

#### Scenario: Custom id extension
- **WHEN** EACL expands a valid numeric or custom external id
- **THEN** the EACL rendering contract applies but the SpiceDB differential claim does not


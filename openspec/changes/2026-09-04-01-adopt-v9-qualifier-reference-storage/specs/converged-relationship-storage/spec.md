## MODIFIED Requirements

### Requirement: Two-value endpoint representation
Each Relationship SHALL be represented by exactly two cardinality-many indexed endpoint values: a forward value on the subject entity and a reverse value on the resource entity. Every bundled backend SHALL use the same fixed five-component order:
`[subject-type relation-eid resource-type resource-eid qualifier-eid]` forward and
`[resource-type relation-eid subject-type subject-eid qualifier-eid]` reverse.
The endpoint owner plus the first four components SHALL identify one physical half; slot five SHALL be a replaceable qualifier reference and SHALL NOT define a second logical Relationship.

Phase 1 supported writers MUST emit `nil` in slot five. A non-`nil` value encountered before qualifier activation MUST fail closed with a typed unsupported-qualifier error.

#### Scenario: Relationship creation
- **WHEN** EACL creates an ordinary Relationship between existing subject and resource endpoints in Phase 1
- **THEN** it stores exactly one five-component forward value and one symmetric five-component reverse value
- **AND** both qualifier components are `nil`
- **AND** it stores no Relationship entity, supplemental Relationship tuple, or second traversal representation

#### Scenario: Same logical identity with another qualifier
- **WHEN** two stored endpoint values have the same owner and first four components but different qualifier components
- **THEN** EACL classifies the database as violating logical Relationship uniqueness
- **AND** authorization does not select a precedence rule between them

#### Scenario: Non-nil qualifier before activation
- **WHEN** a Phase 1 reader encounters a v9 Relationship whose qualifier component is non-`nil`
- **THEN** the operation fails closed as unsupported qualified data
- **AND** the Relationship is never interpreted as permanent or unconditional

#### Scenario: Duplicate physical value
- **WHEN** the same complete endpoint value is added more than once in one database value
- **THEN** backend set semantics retain one datom for that complete value

### Requirement: Ordered endpoint index access
Each adapter SHALL implement exact logical matching, adjacency, Relationship filtering, Relation-in-use checks, and forward/reverse pagination using guarded access to the v9 endpoint attributes. The first three tuple components SHALL remain the typed Relation scan prefix, component four SHALL remain the opposite endpoint ordering key, and component five SHALL never precede or disturb that ordering.

Every vector seek bound SHALL use the full stored arity where the backend's vector comparator requires it. A logical point match SHALL seek by owner, attribute, and first-four identity and SHALL validate that at most one qualifier variant exists.

#### Scenario: Forward endpoint scan
- **WHEN** authorization or Relationship pagination scans outward from a known subject
- **THEN** the adapter seeks only the v9 forward attribute under `[subject-type relation-eid resource-type]`
- **AND** returns opposite endpoints in component-four order without consulting a v7 or supplemental store

#### Scenario: Reverse endpoint scan
- **WHEN** authorization or Relationship pagination scans inward from a known resource
- **THEN** the adapter seeks only the v9 reverse attribute under `[resource-type relation-eid subject-type]`
- **AND** returns opposite endpoints in component-four order without consulting a v7 or supplemental store

#### Scenario: Exact logical Relationship probe
- **WHEN** EACL checks one known subject, Relation, and resource
- **THEN** it performs one bounded seek beginning at the five-component value whose qualifier is `nil`
- **AND** accepts only a value with the exact owner, attribute, and first-four identity

#### Scenario: Reverse-order continuation
- **WHEN** a descending scan resumes from a Relationship cursor
- **THEN** reverse seek starts from a full-arity bound and returns only values inside the requested endpoint prefix

#### Scenario: Adjacent prefix is absent
- **WHEN** no value exists for a requested forward or reverse prefix but an adjacent prefix does exist
- **THEN** explicit owner, attribute, and component guards prevent the adjacent value from being returned

### Requirement: Atomic pair mutation and repair
Relationship mutation through EACL SHALL add, replace, or retract both v9 endpoint values in one admitted transaction and SHALL preserve public `:create`, `:touch`, and `:delete` semantics. Conflict and delete identity SHALL be subject, Relation, and resource rather than the complete five-component value.

The `:create` conflict decision SHALL be made against the transaction-time database. `:touch` SHALL repair a missing half and, once qualifiers are activated, SHALL replace both exact old tuple values when slot five changes. `:delete` SHALL remove whichever exact qualifier value is stored without requiring the caller to name it.

#### Scenario: Complete relationship conflict
- **WHEN** `:create` targets a logical Relationship whose complete v9 pair already exists
- **THEN** EACL reports `:eacl/relationship-conflict`

#### Scenario: Racing creates of one relationship
- **WHEN** two `:create` transactions for the same first-four identity are planned against the same pre-write database value and both are committed
- **THEN** exactly one succeeds and the other reports `:eacl/relationship-conflict`
- **AND** the committed database contains one canonical forward/reverse pair

#### Scenario: Qualifier is not a duplicate identity
- **WHEN** a future `:create` uses the same subject, Relation, and resource but a different qualifier reference
- **THEN** it conflicts with the existing logical Relationship rather than creating a parallel assertion

#### Scenario: Repeated and conflicting batch updates
- **WHEN** one batch repeats the same operation for one resolved Relationship
- **THEN** the batch has the same outcome as one occurrence and `:create` still conflicts when the Relationship existed before the batch
- **WHEN** one batch contains different operations for one resolved Relationship
- **THEN** EACL rejects the batch with `:eacl/invalid-relationship-update-batch` before submission

#### Scenario: Incomplete relationship repair
- **WHEN** `:touch` targets a Relationship with either v9 endpoint half missing
- **THEN** EACL writes the canonical pair using one qualifier value on both halves

#### Scenario: Touch replaces qualifier reference
- **WHEN** `:touch` changes the qualifier reference of an existing logical Relationship after qualifier activation
- **THEN** it retracts both exact old values and asserts both exact new values atomically
- **AND** it advances the affected Relation mutation identity once

#### Scenario: Unconditional deletion
- **WHEN** `:delete` targets a complete, incomplete, or absent logical Relationship without specifying a qualifier
- **THEN** EACL retracts any exact v9 halves found for that first-four identity
- **AND** absence remains an idempotent no-op

### Requirement: Shared logical storage layer
The core workspace SHALL provide backend-neutral pure functions for five-component endpoint construction, identity-prefix construction, decoding, peer-half derivation, exact retraction, and prefix validation wherever bundled backends have identical logical behavior. Backend-specific index, transaction, schema, and migration calls SHALL remain in adapter modules.

#### Scenario: Equivalent relationship
- **WHEN** bundled adapters encode the same resolved ordinary Relationship
- **THEN** their forward and reverse values have identical five-component order, `nil` qualifier, and logical identity

#### Scenario: Peer-half derivation
- **WHEN** EACL decodes one valid forward or reverse endpoint value
- **THEN** it derives the exact symmetric peer value including the same qualifier reference

#### Scenario: Backend-specific database access
- **WHEN** an adapter reads or writes endpoint values
- **THEN** it uses its native database API without introducing backend runtime dependencies into the backend-neutral core module

### Requirement: Prerelease compatibility boundary
A serving EACL v8 client using the v9 Relationship implementation SHALL require storage ABI 9 and SHALL read only populated v9 Relationship attributes. It SHALL NOT add a v7 fallback, dual read, dual write, merged pagination stream, or automatic startup conversion.

#### Scenario: Populated v7 database
- **WHEN** client construction observes current v7 Relationship datoms or storage stamp 7
- **THEN** construction fails with `:eacl/storage-version`
- **AND** the error identifies the explicit backend v7-to-v9 migration function and documentation

#### Scenario: Interrupted mixed database
- **WHEN** client construction observes both current v7 and v9 Relationship data or a non-complete migration marker
- **THEN** construction fails before authorization or cache publication
- **AND** directs the operator to rerun the same migration

#### Scenario: Completed v9 database
- **WHEN** the database is stamped storage 9, has a complete migration/fresh-bootstrap marker, contains no current v7 Relationship datoms under bounded probes, and has compatible target attribute shapes
- **THEN** client construction succeeds and all Relationship operations use only v9 attributes
- **AND** startup does not enumerate the complete v9 graph

#### Scenario: Old cache or cursor artifact
- **WHEN** a v9 client receives an artifact whose storage/adapter/order ABI identifies the four-component layout
- **THEN** it rejects the cursor with a typed compatibility error or treats the cache artifact as a miss
- **AND** it never replays the artifact through a v7 reader

#### Scenario: Existing prerelease demo database
- **WHEN** a DataScript or other prerelease database contains the discarded Relationship-entity representation rather than v9 endpoint pairs
- **THEN** the v9 adapter does not treat those entities as active Relationships
- **AND** documentation directs the operator to recreate/reload that prerelease database rather than enabling another compatibility reader

### Requirement: Cross-runtime validation
The five-component endpoint implementation SHALL satisfy shared authorization, Relationship, mutation, pagination, proof, deletion, integrity, and migration conformance on every bundled backend and on both DataScript runtimes.

#### Scenario: JVM suite
- **WHEN** Datomic, Datahike, Datalevin, and DataScript JVM suites run against storage 9
- **THEN** direct and recursive authorization, forward/reverse lookup, count, Relationship read/write, cache proof, deletion, and integrity results match the four-component semantic baseline for ordinary Relationships

#### Scenario: ClojureScript suite
- **WHEN** the compiled DataScript ClojureScript suite runs under Node
- **THEN** fixed-length vector ordering, seek/rseek bounds, endpoint mutation, and shared authorization behavior match the JVM contract

#### Scenario: Single-source traversal measurement
- **WHEN** release benchmarks instrument endpoint access
- **THEN** each logical Relation scan uses one v9 attribute stream and zero v7 or supplemental Relationship scans
- **AND** measured budgets cover positive/negative point checks, pages, arrows, counts, allocation, and storage density

# relationship-qualifier-storage Specification

## Purpose

Defines the sparse immutable entity referenced by a v9 Relationship's trailing qualifier component and the lifecycle of Caveat context and future expiration data.

## Requirements

### Requirement: Qualified Relationships use sparse qualifier entities
A v9 Relationship MAY reference one qualifier entity from both endpoint halves. A qualifier entity SHALL exist only when at least one semantic qualifier is present and SHALL carry a versioned, bounded set of supported attributes.

#### Scenario: Ordinary Relationship
- **WHEN** a Relationship has no Caveat, bound Caveat context, or `valid-until`
- **THEN** both endpoint values contain `nil` qualifier
- **AND** no qualifier entity or qualifier datom is created

#### Scenario: Caveat-only qualifier
- **WHEN** an internal Phase 2 planner normalizes a Relationship with a Caveat and no bound context or expiry
- **THEN** it creates one qualifier entity containing the mandatory format marker and Caveat ref
- **AND** both endpoint values reference that same entity

#### Scenario: Expiry-only qualifier
- **WHEN** an internal Phase 2 planner normalizes a Relationship with only `valid-until`
- **THEN** it creates one qualifier entity containing the mandatory format marker and the exact epoch-millisecond bound
- **AND** no Caveat or context attribute is invented

### Requirement: Qualifiers are immutable and singly owned
A qualifier entity SHALL be owned by exactly one logical Relationship and referenced by exactly its two symmetric endpoint halves. Admitted updates MUST replace the qualifier entity and both tuple references rather than mutate semantic qualifier attributes in place.

#### Scenario: Qualifier content changes
- **WHEN** a future `:touch` changes Caveat, bound context, or `valid-until`
- **THEN** the planner creates a new qualifier entity, replaces both endpoint refs atomically, and retracts the old current qualifier entity

#### Scenario: Qualifier is reused by another Relationship
- **WHEN** integrity inspection finds one qualifier referenced by endpoint halves from different logical Relationships
- **THEN** it reports a shared-qualifier integrity violation
- **AND** no writer or reader treats sharing as supported deduplication

#### Scenario: Qualifier is changed in place
- **WHEN** unknown data changes a semantic attribute without replacing the tuple reference
- **THEN** integrity/proof qualification classifies the immutable-qualifier contract as violated

### Requirement: Qualifier attributes are canonical and bounded
Qualifier format version, generation, Caveat ref, bound context payload, and `valid-until-ms` SHALL use one closed normalized representation. Context without a Caveat, an empty qualifier, unknown attributes, malformed context, and out-of-range time values MUST be rejected.

#### Scenario: Empty qualifier input
- **WHEN** every optional qualifier field is absent after normalization
- **THEN** the result is `nil` qualifier rather than an empty entity

#### Scenario: Context without Caveat
- **WHEN** bound context is supplied without a Caveat definition
- **THEN** qualifier validation fails before transaction data is emitted

#### Scenario: Empty bound context
- **WHEN** a Caveat is supplied with an empty context map
- **THEN** the context attribute is omitted while the Caveat ref remains

#### Scenario: Invalid valid-until
- **WHEN** `valid-until-ms` is not an exact supported integer or lies outside the configured portable time domain
- **THEN** validation fails with a typed qualifier error

### Requirement: Qualifier version is immutable cache evidence
Each qualifier SHALL expose one immutable creation version in the backend's common revision domain. An adapter MAY derive it from the assertion `t` of the mandatory format marker, MAY persist an equivalent immutable creation-generation value when native read datoms do not expose that `t`, or MAY decline cross-snapshot qualifier caching. The version MUST NOT be advanced in place and MUST NOT be treated alone as final authorization proof.

#### Scenario: Qualifier is created
- **WHEN** an admitted transaction creates a qualifier
- **THEN** all semantic attributes are visible with one immutable creation version in the resulting snapshot

#### Scenario: Native assertion t is available
- **WHEN** the adapter can read the format marker's trustworthy assertion transaction
- **THEN** it exposes that `t` as qualifier-version without adding a universal generation datom

#### Scenario: Native assertion t is unavailable
- **WHEN** an adapter cannot expose equivalent immutable creation evidence
- **THEN** it persists certified creation evidence or restricts reuse to request-local/exact-snapshot scope

#### Scenario: Qualifier cache key is formed
- **WHEN** later code caches a decoded qualifier across requests
- **THEN** source lifecycle, qualifier eid, qualifier version, owning Relation proof, and format are available as identity/proof inputs
- **AND** the version alone is not accepted as authorization authority

### Requirement: Qualifier references are published atomically through a certified strategy
A backend SHALL advertise qualified Relationship writes only after conformance proves either native inline resolution of a newly allocated qualifier eid inside both endpoint values or a safe prepared-reference strategy. The semantic publication transaction MUST use one concrete qualifier eid, update both endpoint halves and the owning Relation mutation stamp atomically, and MUST NOT expose unresolved nested tempids, lookup refs, or one-sided qualified Relationships.

#### Scenario: Backend resolves inline qualifier allocation
- **WHEN** a backend's real transaction API is certified to create a qualifier and resolve its eid in tuple slot five
- **THEN** EACL may create the qualifier and publish both endpoint halves in one transaction

#### Scenario: Backend requires a prepared qualifier
- **WHEN** a backend cannot certify nested qualifier-eid resolution
- **THEN** EACL creates an unreferenced immutable qualifier first and obtains its concrete eid
- **AND** a later single transaction publishes both endpoint refs, the Relation stamp, and any caller-composed application datoms

#### Scenario: Prepared publication fails
- **WHEN** qualifier preparation succeeds but the endpoint publication transaction fails
- **THEN** no qualified Relationship becomes visible
- **AND** the unattached qualifier is safe orphan data eligible for bounded cleanup

#### Scenario: Composable transaction data needs preparation
- **WHEN** `eacl/tx-relationship` targets a backend using prepared references
- **THEN** it requires an opaque prepared-qualifier handle and returns one final atomic publication transaction
- **AND** it never performs a hidden non-atomic half write

#### Scenario: Backend has no certified strategy
- **WHEN** a backend supports neither inline allocation nor prepared-reference publication
- **THEN** qualified Relationship writes and Phase 3 activation remain unsupported for that backend

### Requirement: Qualifier lifecycle follows the logical Relationship
Qualifier creation, reference replacement, deletion, endpoint cleanup, and integrity repair SHALL be planned with the owning Relationship pair. A dangling or missing qualifier MUST fail closed when qualified evaluation is later active.

#### Scenario: Relationship is deleted
- **WHEN** an admitted delete removes a qualified logical Relationship
- **THEN** it retracts both exact endpoint values and the current singly owned qualifier entity in the same transaction

#### Scenario: One endpoint half is repaired
- **WHEN** repair finds one healthy half referencing a valid qualifier and its peer missing
- **THEN** it recreates the peer using the identical qualifier ref rather than creating a second qualifier

#### Scenario: Qualifier entity is missing
- **WHEN** an endpoint tuple contains a non-`nil` ref that does not resolve
- **THEN** integrity reports a dangling qualifier reference
- **AND** future authorization cannot fall back to an unconditional Relationship

#### Scenario: Qualifier is retracted out of band
- **WHEN** unknown data removes the referenced qualifier entity while endpoint values remain
- **THEN** the stored non-`nil` qualifier identity remains semantically qualified
- **AND** EACL reports a fault rather than normalizing the Relationship to the `nil` fast path

### Requirement: Phase 2 does not activate qualified traversal
Installing qualifier schemas and internal planners SHALL NOT make non-`nil` qualifier Relationships eligible for current-serving authorization. Public qualified Relationship writes and serving activation SHALL remain disabled until the Phase 3 change is applied.

#### Scenario: Public write before activation
- **WHEN** an application attempts to write a non-`nil` qualifier through the Phase 2 public writer
- **THEN** the write fails with a typed unsupported/not-activated error

#### Scenario: Serving reader sees staged qualified data
- **WHEN** a serving Phase 2 client encounters a non-`nil` qualifier from unknown or test data
- **THEN** it fails closed and never strips or ignores the qualifier

# converged-relationship-storage Specification

## Purpose
TBD - created by archiving change optimize-datascript-relationship-storage. Update Purpose after archive.
## Requirements
### Requirement: Two-value endpoint representation
Each DataScript relationship SHALL be represented by exactly two
cardinality-many indexed ordinary values: a forward value on the subject entity
and a reverse value on the resource entity. The values SHALL use the same
four-component order as the Datomic Pro and Datahike endpoint tuples:
`[subject-type relation-eid resource-type resource-eid]` forward and
`[resource-type relation-eid subject-type subject-eid]` reverse.

#### Scenario: Relationship creation
- **WHEN** EACL creates a relationship between existing subject and resource endpoints
- **THEN** DataScript stores exactly one forward endpoint datom and one reverse endpoint datom and stores no relationship entity or derived relationship tuple

#### Scenario: Duplicate physical value
- **WHEN** the same endpoint value is added more than once in one DataScript database value
- **THEN** DataScript set semantics retain one datom for that endpoint value

### Requirement: Ordered endpoint index access
The DataScript adapter SHALL implement exact matching, adjacency, relationship
filtering, relation-in-use checks, and forward and reverse pagination using
guarded EAVT or AVET access to the endpoint values. Every vector seek bound
SHALL have the full stored arity because DataScript orders vectors by length
before comparing their components.

#### Scenario: Forward endpoint scan
- **WHEN** authorization or relationship pagination scans outward from a known subject
- **THEN** the adapter seeks the subject's forward endpoint values and stops at the exact entity, attribute, and requested component prefix

#### Scenario: Reverse endpoint scan
- **WHEN** authorization or relationship pagination scans inward from a known resource
- **THEN** the adapter seeks the resource's reverse endpoint values and stops at the exact entity, attribute, and requested component prefix

#### Scenario: Reverse-order continuation
- **WHEN** a descending scan resumes from a relationship cursor
- **THEN** `rseek-datoms` starts from a full-arity bound and returns only values inside the cursor's requested endpoint prefix

#### Scenario: Adjacent prefix is absent
- **WHEN** no value exists for a requested forward or reverse prefix but an adjacent prefix does exist
- **THEN** explicit entity, attribute, and component guards prevent the adjacent value from being returned

### Requirement: Atomic pair mutation and repair
Relationship mutation through EACL SHALL add or retract both endpoint values in
one DataScript transaction and SHALL preserve the public `:create`, `:touch`,
and `:delete` semantics shared with Datomic Pro and Datahike.

#### Scenario: Complete relationship conflict
- **WHEN** `:create` targets a relationship whose forward and reverse values both exist
- **THEN** EACL reports a relationship conflict

#### Scenario: Incomplete relationship repair
- **WHEN** `:touch` targets a relationship with either endpoint value missing
- **THEN** EACL writes both values so the complete pair exists after the transaction

#### Scenario: Unconditional deletion
- **WHEN** `:delete` targets a complete, incomplete, or absent pair
- **THEN** EACL issues retractions for both endpoint values without requiring a relationship entity

### Requirement: Endpoint deletion and integrity
The DataScript adapter SHALL treat peer eids embedded in ordinary vectors as
values rather than DataScript refs. EACL object cleanup SHALL explicitly remove
every touching pair before endpoint retraction, and the module SHALL expose an
offline report for forward or reverse values whose peer half is absent.

#### Scenario: EACL object cleanup
- **WHEN** `delete-object!` is called for an endpoint
- **THEN** it removes local and peer endpoint values for every touching relationship, retains the existing DataScript object-deletion API semantics, and publishes every affected relation mutation identity

#### Scenario: Direct endpoint retraction
- **WHEN** a consumer retracts an endpoint entity without first using the EACL relationship API
- **THEN** peer endpoint values can remain as detectable ghost halves because embedded peer eids are not refs

#### Scenario: Integrity scan
- **WHEN** the offline DataScript relationship integrity report scans the database
- **THEN** it reports each endpoint value lacking its exact peer value without mutating the database

### Requirement: Database-visible proof coverage
Unknown-writer relationship content proofs SHALL commit to both physical
endpoint values and the public identities of their endpoints so that any
answer-affecting out-of-band change invalidates a cached answer.

#### Scenario: One half changes out of band
- **WHEN** a writer adds, retracts, or changes only one endpoint value for a relation in a cached permission's dependency set
- **THEN** the next content-proof validation does not reuse the previous cached answer

#### Scenario: Endpoint identity changes out of band
- **WHEN** an endpoint's public identity changes while its stored eid remains the same
- **THEN** the content proof changes and the previous cached answer is rejected

### Requirement: Shared logical storage layer
The core workspace SHALL provide backend-neutral pure functions for endpoint
value construction, decoding, and prefix validation wherever Datomic Pro,
Datahike, and DataScript have identical logical behavior. Backend-specific
index, temporal, transaction, and schema calls SHALL remain inside their
adapter modules.

#### Scenario: Equivalent relationship
- **WHEN** all three adapters encode the same resolved logical relationship
- **THEN** their forward and reverse values have identical component order and decode to the same logical endpoints

#### Scenario: Backend-specific database access
- **WHEN** an adapter reads endpoint values
- **THEN** it uses its native database API without introducing Datomic, Datahike, or DataScript dependencies into the backend-neutral core module

### Requirement: Prerelease compatibility boundary
The optimized DataScript adapter SHALL use only the endpoint-pair layout and
SHALL NOT add dual reads, rollback support, or an automatic migration for the
unreleased relationship-entity layout.

#### Scenario: Existing prerelease demo database
- **WHEN** a DataScript database created from the relationship-entity branch is opened with the optimized adapter
- **THEN** its old relationship entities are not treated as active relationships and documentation directs the user to recreate the database or reload relationships through EACL

### Requirement: Cross-runtime validation
The endpoint-pair adapter SHALL satisfy the shared v8 backend contract and its
DataScript-specific storage, pagination, consistency, mutation, and integrity
tests on both the JVM and ClojureScript runtimes.

#### Scenario: JVM suite
- **WHEN** the DataScript JVM tests run
- **THEN** direct and recursive authorization, forward and reverse lookup, count, relationship read/write, cache proof, deletion, and integrity behavior pass

#### Scenario: ClojureScript suite
- **WHEN** the compiled DataScript ClojureScript tests run under Node
- **THEN** ordinary vector ordering, `seek-datoms`, `rseek-datoms`, endpoint mutation, and the shared authorization contract pass with the same logical results as the JVM suite


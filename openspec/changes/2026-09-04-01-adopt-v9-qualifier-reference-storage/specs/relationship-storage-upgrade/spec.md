## Purpose

Defines the explicit, restartable, fail-closed upgrade from four-component v7 Relationship tuples to the single-source five-component v9 qualifier-reference representation.

## ADDED Requirements

### Requirement: Client startup requires target Relationship storage
A serving v9-storage client SHALL establish through bounded metadata, physical-schema, migration-state, and legacy-existence checks that its selected database uses Relationship storage version 9 before it permits authorization, Relationship writes, cursor issuance, or cache publication. Current v7 data, mixed data, an incomplete migration, or an incompatible target schema MUST prevent construction. Normal client startup MUST NOT enumerate all v9 Relationships or repeat migration integrity verification.

#### Scenario: Unsupported source store
- **WHEN** a client is constructed against a populated v7 database
- **THEN** construction fails with a typed storage-version error before any authorization result is returned
- **AND** the error names the supported migration entry point

#### Scenario: Legacy entity source store
- **WHEN** a client or v7-to-v9 migration is opened against current v6 Relationship entities
- **THEN** it fails before serving or conversion
- **AND** identifies the existing v6-to-v7 migration as the required prerequisite

#### Scenario: Migration is incomplete
- **WHEN** a migration marker is present in any state other than complete
- **THEN** client construction refuses to serve and directs the operator to resume migration

#### Scenario: Target store is qualified
- **WHEN** the database is stamped version 9, the migration marker is complete or the store was freshly bootstrapped, bounded legacy probes are empty, and target attribute shapes are compatible
- **THEN** the client starts with one v9 Relationship source

#### Scenario: Large target store starts without revalidation
- **WHEN** a completed version-9 database contains a large number of Relationships
- **THEN** client construction checks only bounded compatibility evidence and does not scan the complete v9 graph

### Requirement: Upgrade is an explicit side-effecting operation
Each mutable bundled backend SHALL expose a documented `v7-to-v9/migrate!` operation accepting its native connection or writer handle. Client construction MUST NOT implicitly invoke that operation.

#### Scenario: Operator starts migration
- **WHEN** an operator calls the backend migration function with valid options during a maintenance window
- **THEN** the function installs compatible target attributes, records migration state, processes bounded batches, verifies target content, cleans current source data, and stamps completion

#### Scenario: Client option attempts automatic migration
- **WHEN** a caller supplies a former or invented auto-migration client option
- **THEN** client construction rejects it as unsupported rather than starting storage conversion

### Requirement: Migration preserves the logical Relationship graph
For every healthy v7 Relationship pair, migration SHALL produce exactly one v9 pair with `nil` qualifier and the same endpoint types, Relation eid, subject eid, and resource eid. Preflight SHALL record a canonical source count and content digest before conversion. Migration MUST NOT create Relationship entities, qualifier entities, duplicate logical identities, or semantic changes.

#### Scenario: Ordinary source pair
- **WHEN** migration converts a valid v7 forward/reverse pair
- **THEN** the target contains the symmetric five-component pair with `nil` slot five
- **AND** the source pair is removed from the current database in the same bounded conversion transaction

#### Scenario: Source pair is corrupt
- **WHEN** a v7 half is missing, a Relation cannot be resolved, an endpoint is absent, or duplicate source identities disagree
- **THEN** migration stops with a bounded diagnostic report
- **AND** it does not guess, authorize, or stamp completion

#### Scenario: Conflicting target data exists
- **WHEN** the target already contains a non-equivalent value for a source logical identity
- **THEN** migration fails closed and leaves the store unbootable until the conflict is resolved

### Requirement: Migration is idempotent and resumable
Reinvoking the migration after interruption SHALL continue from recognized durable state without duplicating Relationships or discarding verified work. Completion SHALL be stamped only after a fresh full verification.

#### Scenario: Process stops between batches
- **WHEN** migration is interrupted after some source pairs were converted
- **THEN** a later invocation validates completed target pairs, continues with remaining source pairs, and converges to the same final graph

#### Scenario: Process stops before final stamp
- **WHEN** all source pairs were converted but completion was not stamped
- **THEN** rerunning performs final source-empty, pair-parity, uniqueness, and content verification before stamping version 9

#### Scenario: Completed migration is rerun
- **WHEN** the same migration function is called on a fully qualified version-9 store
- **THEN** it returns an idempotent already-complete report without rewriting Relationships

### Requirement: Migration requires a quiesced writer boundary
The supported procedure SHALL require all v7-capable Relationship writers and authorization peers to stop before conversion. Migration SHALL use available backend fencing and mutation evidence to detect interference, but SHALL NOT claim safety against an uncooperative unknown writer.

#### Scenario: Source changes during migration
- **WHEN** source mutation evidence changes outside the admitted migration transactions
- **THEN** migration aborts before completion and reports concurrent source mutation

#### Scenario: Maintenance precondition is not acknowledged
- **WHEN** required quiescence/fencing options are absent for a durable backend
- **THEN** migration refuses to start rather than treating online conversion as supported

### Requirement: Final verification is complete and fail closed
Before writing storage version 9, migration SHALL prove that no current v7 Relationship datoms remain, every current v9 half has its exact peer, first-four logical identities are unique, the target count and canonical content digest match the durable preflight source certificate, and all affected Relation mutation versions reflect conversion.

#### Scenario: Verification passes
- **WHEN** all final checks pass on one selected target snapshot
- **THEN** migration atomically records complete state and storage version 9

#### Scenario: Any final check fails
- **WHEN** source residue, a dangling target half, duplicate identity, content mismatch, or missing Relation stamp is found
- **THEN** version 9 is not stamped and serving clients remain fenced

### Requirement: Upgrade operations and rollback are documented
Documentation SHALL give backend-specific backup, rehearsal, quiescence, invocation, progress, verification, deployment, cursor/cache invalidation, and rollback instructions. Rollback SHALL be described as restore or database switch, not an in-place target-to-source migration.

#### Scenario: Production operator follows the guide
- **WHEN** an operator reads the v7-to-v9 guide
- **THEN** the complete order of operations, expected reports, typed failure modes, and post-cutover checks are explicit

#### Scenario: Rollback is required
- **WHEN** post-cutover validation fails
- **THEN** the guide directs the operator to stop v9 peers and restore/switch to the pre-migration database with the matching old EACL version

## ADDED Requirements

### Requirement: The released-v7 permission upgrade verifies semantic equivalence of its replacement

The released-v7 to v8 permission upgrade SHALL verify, before committing, that the supplied replacement schema is semantically equivalent to the permissions recorded in the stored v7 rows for every permission present in both. Verification SHALL compare the canonical expression derived from the stored v7 rows against the canonical expression derived from the supplied schema; a difference SHALL reject the upgrade with a typed error naming the divergent permission, leaving the v7 permission rows and the schema stamp active.

Permissions present only in the replacement schema are additive and SHALL be permitted. Relation identity checks remain in force and are not sufficient on their own.

#### Scenario: Replacement schema silently redefines an existing permission

- **WHEN** stored v7 rows define `permission view = reader` and the supplied replacement schema defines `permission view = writer`, with identical relation identities
- **THEN** the migration rejects the upgrade with a typed non-equivalence error naming `view`
- **AND** the stored v7 permission rows and schema stamp remain active and usable

#### Scenario: Replacement schema adds a new permission

- **WHEN** the supplied replacement schema preserves every stored v7 permission's denotation and adds a permission that did not previously exist
- **THEN** the migration proceeds and commits the additive permission

### Requirement: Migration reports reflect what was committed

A migration report SHALL describe the transaction that actually ran. When no transaction is required, the report SHALL NOT claim a storage version that was never stamped; it SHALL name the no-op outcome and the version actually recorded in the database.

#### Scenario: Migration invoked on a database that needs no change

- **WHEN** the upgrade runs against a database whose permission storage already requires no delta and whose generation stamp is absent
- **THEN** the report identifies the outcome as a no-op and reports the stamped version actually present
- **AND** it does not report a permission-storage version that no transaction wrote

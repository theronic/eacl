## ADDED Requirements

### Requirement: Datalevin lineage is durable and storage-enforced
The Datalevin basis source SHALL expose its persisted source identity and the configured persisted lifecycle as lineage, SHALL reject construction when the store's `max-tx` is below the external revision watermark, and SHALL advertise ordered generations only when the maintained fork reports commit-generation materialization, `max-tx` continuity, shared-store stale-generation recovery, and an installed write policy covering every physical EACL storage attribute except application identity `:eacl/id`. Cursors, tokens, and managed state SHALL remain valid across provider restart within one lineage. Topology declarations, connection custody, and advisory locks MUST NOT be accepted as evidence of mutation completeness.

#### Scenario: Provider restarts
- **WHEN** a Datalevin provider is closed and reopened on the same store with the same lifecycle
- **THEN** cursors and tokens issued before the restart validate and managed lifting remains enabled

#### Scenario: Store is rolled back
- **WHEN** a restored store reports `max-tx` below the external watermark
- **THEN** construction fails with `:eacl.datalevin/revision-regression` before acquisition or bootstrap mutation

#### Scenario: Fork lacks the write policy
- **WHEN** the fork artifact does not report write-policy support
- **THEN** construction fails with a typed unsupported-capability error rather than advertising exact-only behaviour silently

#### Scenario: Second writer process
- **WHEN** another process commits to the same store between a transaction's preparation and commit
- **THEN** the fork aborts the commit with `:datalevin/max-tx-continuity-violation` and the generations already committed remain consistent

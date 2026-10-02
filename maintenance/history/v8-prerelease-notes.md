# v8 pre-release notes

Historical records moved out of the consumer guides. They describe EACL v8
development builds that predate the first published v8 release, 8.0.0.

## Vars removed on 2026-09-02

These unreferenced vars were removed from the core module (`dev.eacl/eacl`):
`eacl.client.orchestration/can?` (use `eacl.core/can?`),
`eacl.engine.physical/telemetry` (the finished reducer state carries every
counter), `eacl.engine.sealed-plan/local-read-cost` (read `rank-contract`),
`eacl.operator.recursive/check-eids` (use `check-cached-eids`),
`eacl.relationships.endpoint-pair/half-identity`,
`eacl.authorization.batch/root-key`,
`eacl.schema.expression-policy/compatibility-digest` and
`eacl.schema.expression-resolver/resolve-definitions`.

The Datalevin module removed
`eacl.datalevin.impl/{find-one-relationship-id,orphaned-relationship-halves,Relation,Permission,Relationship}`,
`eacl.datalevin.db/entity-exists?` and the
`eacl.datalevin.schema/validate-schema-references` alias, which had been
unreferenced since the module's integrity namespace was retired. PR #213 later
added a new `eacl.datalevin.db/entity-exists?` for relationship-write endpoint
checks.

## Resetting unreleased v8 development databases

If source control is rolled back across the v8 expression-storage change,
dispose of and recreate development databases with the schema belonging to the
selected source revision. Do not open an expression-capable database with an
older binary. No compatibility is claimed for persisted cursors across that
rollback, and no migration or dual-write path is provided between superseded
unreleased-v8 representations. This reset contract is distinct from the
released v6-to-v7 relationship migration and released v7-to-v8 permission
migration utilities.

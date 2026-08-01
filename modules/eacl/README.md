# `eacl`

Core, backend-neutral EACL module.

Responsibilities:

- `eacl.core` protocol and public records
- cursor/token helpers and consistency semantics
- schema IR, parser, validation, and diffing
- backend SPI and shared authorization engine
- contract test fixtures and backend-neutral tests

This module must not depend on Datomic or a logging backend.

## Backend contracts

New v8 backends supply the validated operation map consumed through
`eacl.backend.v8`. It declares consistency, snapshot, cursor, transaction,
cache-proof, and runtime capabilities and implements normalized operations for
snapshot identity, object ID conversion, schema definitions, adjacency,
direct matches, recursive permission nodes, cursor frontier identity, and
schema/relation proofs.

The original six-function `eacl.backend.spi` contract remains supported for v7
third-party adapters:

- `cache-stamp`
- `relation-defs`
- `permission-defs`
- `subject->resources`
- `resource->subjects`
- `direct-match?`

Both contracts use logical types and identifiers. Datoms, attribute ids,
database values, and raw index tuples stay inside each adapter. See the
[v8 adapter boundary](../../docs/v8-backend-adapter-boundary.md) for the full
inventory.

The shared contract fixture is
`modules/eacl/test/eacl/contract_support.cljc`. It exercises the same v8 public
API and recursive/cache behavior for Datomic, DataScript, and Datahike and
compares authorization sets with the independent evaluator in
`eacl.authorization-oracle`.

Application-facing module selection and upgrade notes live in the
[v8 backend and upgrade guide](../../docs/v8-backend-modules-and-upgrade.md).

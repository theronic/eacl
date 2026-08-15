## Why

EACL's DataScript and Datahike ports still implement the older v7 authorization engine while the v8 release candidate adds recursive permissions, Relay pagination, deletion, stricter query semantics, and authorization caching. Shipping those capabilities as separate backend-specific implementations would multiply correctness risk, so the ports should converge on one shared v8 engine with small, explicit data-access adapters.

## What Changes

- Review Datahike PR #81 against the v7 DataScript behavior, fix its standalone build/test dependency gaps and empty-result cursor behavior, add a merge gate, and merge it only after the corrected v7 port passes shared and Datahike-specific tests.
- **BREAKING** Upgrade the DataScript and Datahike public behavior from the legacy v7 lookup API to the v8 protocol and Relay pagination/count contract used by the release candidate.
- Extract backend-neutral v8 permission compilation, recursive traversal, pagination, counting, deletion semantics, and error contracts from the Datomic implementation into the shared `eacl` module.
- Add DataScript and Datahike adapters for the shared v8 engine while retaining Datomic's storage-specific consistency, transaction, index, and cursor capabilities.
- Add portable authorization cache storage and validation, with exact backend-provided relation/schema proofs so cache reuse remains sound across writes, connections, and recursive continuations.
- Add shared conformance, recursive-schema, cache-correctness, and differential tests for all three adapters, plus module-isolated build/load checks and backend-specific coverage.
- Open the completed integration as a pull request targeting `release/v8.0`, the head branch of PR #84, so the release candidate contains modular Datomic, DataScript, and Datahike artifacts.

## Capabilities

### New Capabilities

- `portable-v8-authorization-engine`: Backend-neutral v8 authorization behavior, including recursive permission graphs, Relay lookups, counts, deletion, and explicit backend capability handling.
- `portable-authorization-cache`: Shared cache storage and validation driven by exact backend-provided schema and relationship change proofs.
- `cross-backend-conformance`: Common behavioral, recursive, caching, differential, and isolated-module verification across Datomic, DataScript, and Datahike.

### Modified Capabilities

- `modular-backend-workspace`: Add Datahike as an independently consumable adapter and refine the shared-core/backend-data-access boundary for the expanded v8 contract.

## Impact

- Affects the `eacl`, `eacl-datomic`, `eacl-datascript`, and new `eacl-datahike` modules, their dependency/build declarations, CI, tests, and upgrade documentation.
- Extends or replaces the current six-function backend SPI where v8 snapshot selection, deletion, cache proofs, and capability reporting require explicit adapter operations.
- Preserves Datomic v8 behavior and database compatibility while moving reusable algorithms and cache machinery into backend-neutral namespaces.
- Requires DataScript CLJ/CLJS and Datahike consumers to adopt v8 request and pagination semantics; unsupported consistency modes remain explicit rather than being simulated.
- Uses PR #81 as the reviewed v7 Datahike source of truth and PR #84's `release/v8.0` branch as the v8 integration base.

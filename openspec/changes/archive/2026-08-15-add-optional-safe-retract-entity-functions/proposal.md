## Why

EACL's endpoint-pair relationship storage embeds peer ids inside tuple/vector values, so each backend's ordinary `retractEntity` operation can leave a surviving half that still authorizes or exposes a deleted object. Consumers need an atomic, opt-in deletion path on every backend with a native transaction-function mechanism, while backends or configurations that cannot install one need an explicit compatibility answer rather than silent partial support.

## What Changes

- Add an explicitly installed transaction function named `:eacl.fn/retractEntity` for each EACL backend that supports named transaction functions, including Datomic, Datahike configurations capable of storing transaction-function values, and DataScript.
- Make the function atomically retract an existing entity and both endpoint halves of every EACL relationship touching it, using the backend's transaction-start database value.
- Keep installation optional: the function is not part of any default EACL schema and existing consumers retain current behavior until they opt in.
- Preserve each backend's cache and consistency bookkeeping for every relation changed by the transaction, without peer/client cache coordination.
- Expose a uniform capability/install contract and fail explicitly when a backend or backend configuration cannot provide a durable named transaction function; document the supported direct transaction-function fallback where one exists.
- Add cross-backend contract tests plus backend-specific correctness, concurrency, persistence/installation, compatibility, and bounded performance tests.
- Update the root and backend READMEs to explain the ghost-relationship hazard, opt-in installation and invocation, backend/configuration support, and existing portable/manual alternatives.

## Capabilities

### New Capabilities

- `safe-entity-retraction-function`: Cross-backend capability discovery, optional installation, and behavior of `:eacl.fn/retractEntity`, including atomic endpoint cleanup, consistency bookkeeping, explicit unsupported cases, and performance bounds.

### Modified Capabilities

None.

## Impact

- Affected implementation: the portable relationship/storage contract and the Datomic, Datahike, and DataScript modules; backend-specific installers remain in backend artifacts so the portable artifact gains no database dependency.
- Affected public surface: a small capability/install API per backend and the optional persisted ident `:eacl.fn/retractEntity`; existing object-deletion APIs remain available.
- Affected tests: shared backend contract coverage and backend-specific schema, storage, cache, concurrency, and operation-count/performance suites.
- Affected documentation: root `README.md` and all relevant backend module READMEs.
- Dependencies and deployment: no new third-party dependency. Datomic's installed database function must be self-contained and require no transactor classpath addition; embedded backends use their native function-value mechanism.
- Compatibility: additive and non-breaking. Ordinary backend `retractEntity`, `eacl/delete-object!`, and backend tx-data helpers retain their current semantics.

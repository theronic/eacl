## Why

EACL's completed-answer cache is coherent under ordinary forward transaction history when every supported writer atomically advances the schema generation or each affected relation version. The current v3 mutation graph adds a global head CAS, journal records, retention, and transaction-function envelopes to solve restore/fork lineage problems that are outside the cache contract, increasing write cost and coupling cache correctness to causal-token machinery it does not need.

## What Changes

- Define the cache correctness boundary as one captured immutable request snapshot under ordinary forward history. Database restore, reset, branch force, history replacement, arbitrary caller-supplied historical views, and equivalent lifecycle changes require cache expiry; automatic detection is optional.
- Retain the two-tier cache: exact-current entries are isolated by immutable snapshot generation, while managed-current entries are keyed by one schema generation and the complete sorted version vector of the query's relation dependency closure.
- Materialize every relation's initial version in the transaction that declares it, then advance each affected relation version atomically with every supported relationship addition, retraction, repair, object deletion, or safe entity retraction. Missing versions fail closed; there is no synthetic `:initial` fallback.
- Fence every client-planned relationship write with commit-time endpoint identity and schema-write guards so a transaction prepared before endpoint or relation deletion cannot resurrect identity-less data. Fence schema replacements and relation removals with schema/relation-generation guards. On backends whose old-equals-old CAS reasserts a datom, keep the schema write fence distinct from the cache generation so predicates cannot invalidate managed entries. These guards protect mutation semantics without reintroducing a mutable global head.
- Remove mutation-graph head/order, mutation anchors, relation/schema mutation-id fallbacks, anchor retention, journal pruning, mutation envelopes, and the global graph CAS from the cache and ordinary relationship-maintenance protocol. Existing persisted graph attributes may remain inert for compatibility; new installations do not require them.
- Replace graph-anchored consistency tokens with authenticated backend-native revision tokens scoped to a client/source lifecycle. Datomic uses database identity plus `t`, Datahike uses its supported branch/commit identity, and DataScript advertises only the current-connection guarantees it can provide.
- Keep exact or historical evaluation outside the completed-answer cache. Long-running API calls remain correct by retaining the one immutable database value selected at request start.
- Make lifecycle expiry explicit and safe: consumers must quiesce and expire/recreate the complete EACL client lifecycle after restore or equivalent history replacement; EACL may detect and expire automatically where a backend exposes reliable evidence.
- Simplify supported safe-retraction transaction functions to stamp affected relations without graph bookkeeping, accept the native `retractEntity`-style target argument, compose multiple invocations in one transaction, preserve native component-cascade semantics by cleaning the complete deletion closure, reject EACL schema/control entities, and repair peer ghosts when a numeric eid remains known.
- Add structural transaction-datom and concurrency benchmarks that gate linear relationship work, relation-scoped bookkeeping, elimination of the global CAS, and managed reuse after unrelated transactions.
- **BREAKING** Previously issued graph-anchor-based Zed tokens and cursors are not accepted by the simplified token format.
- **BREAKING** Managed cache authority no longer promises survival or automatic causal comparison across database restore, reset, branch force, or arbitrary historical database values.

## Capabilities

### New Capabilities

- `forward-history-cache-coherence`: Exact and managed cache correctness, database-visible schema/relation generations, complete dependency proofs, immutable-snapshot request isolation, and explicit lifecycle-expiry boundaries.
- `backend-native-revision-consistency`: Authenticated native revision tokens and backend capability behavior without a portable mutation graph or retained mutation anchors.
- `stamp-only-safe-entity-retraction`: RetractEntity-style transaction-function composition, live-target cleanup, known-retracted-eid ghost repair, and relation-stamp-only cache publication.

### Modified Capabilities

- `modular-backend-workspace`: Simplify the backend adapter contract from graph journal/head operations to native snapshot identity, revision selection, source lifecycle, schema generation, and relation-generation reads.

## Impact

- Affects shared cache and consistency orchestration, backend adapter capabilities, Datomic/Datahike/DataScript schema and mutation modules, relationship/schema writers, safe-retraction functions, cursor/token codecs, tests, benchmarks, migrations, and documentation.
- Removes mandatory cache-path use of `:eacl.graph/*`, `:eacl.mutation/*`, `:eacl.schema/mutation-id`, `:eacl.relation/mutation-id`, and `:eacl.dependency/mutation-id`. Existing data is tolerated and need not be destructively migrated.
- Preserves `:coherence-authority :unknown` as exact-current-only and keeps managed-current opt-in under the documented exclusive stamped-writer contract.
- Reduces ordinary relationship transaction size and removes global head contention while retaining per-relation invalidation, schema-wide invalidation, and endpoint-deletion safety.
- Supersedes the restore/fork-survival and graph-ancestry premises introduced by `redesign-cross-backend-freshness-cache`, and revises the graph-dependent bookkeeping in `add-optional-safe-retract-entity-functions`.

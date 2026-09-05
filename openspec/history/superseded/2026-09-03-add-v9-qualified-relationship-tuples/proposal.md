> **DEPRECATED / SUPERSEDED — 2026-09-04.** This unimplemented proposal is retained for historical review only. Its requirements and unchecked tasks are withdrawn from the implementation plan; do not apply or sync these deltas into the main specifications. Use the [replacement v9 proposal](../2026-09-04-add-v9-caveats-and-expiring-relationships/proposal.md).
>
> The replacement uses **seven-slot Caveat + expiry-only endpoint tuples**, removes scheduled activation and the mandatory expiration index, and retains time-aware cache/cursor checks. See the [review findings and preserved REPL evidence](review-2026-09-04.md). The original artifact follows unchanged.

## Why

EACL needs scheduled, expiring, and Caveat-qualified relationships without
giving up the two-datom endpoint representation introduced by v7. The current
four-slot relationship ABI cannot carry these qualifiers, and adding Caveats
after validity would otherwise force another persisted storage break.

## What Changes

- **BREAKING**: Replace v7 relationship endpoint values with one fixed
  eight-component v9 layout:
  - forward:
    `[subject-type relation-eid resource-type resource-eid caveat-eid caveat-context-eid valid-from-ms valid-until-ms]`
  - reverse:
    `[resource-type relation-eid subject-type subject-eid caveat-eid caveat-context-eid valid-from-ms valid-until-ms]`
- Preserve the owning endpoint eid plus the first four components as the
  logical relationship identity; a four-component prefix is unique only
  within one endpoint.
  Caveat definition, relationship-bound Caveat context, and validity are
  qualifiers and do not create distinct relationships.
- Keep exactly two authoritative relationship datoms. Do not restore a
  first-class entity for every relationship.
- Store a Caveat definition as a shared schema entity in component five. Store
  non-empty relationship-bound Caveat context in a sparse, immutable,
  singly-owned context entity referenced by component six. Empty bound context
  uses `nil` in component six.
- Put all Caveat components before validity components, while keeping every
  qualifier after the opposite endpoint. This preserves one endpoint-ordered
  candidate stream per graph hop and fixes the Phase 2 Caveat positions before Phase 1
  validity ships.
- Deliver v9 in two implementation phases:
  - **Phase 1 — fixed v9 storage and native validity**: install the eight-slot
    ABI, require Caveat slots to be `nil`, add trusted valid-time evaluation,
    temporal cache/cursor proofs, and sparse expiration collection.
  - **Phase 2 — SpiceDB-compatible Caveats**: parse and persist Caveat
    definitions, validate Caveat-bearing relations and writes, store
    relationship-bound context, evaluate Caveats with request context, expose
    conditional permission results, and make caches/cursors context-complete.
- Match SpiceDB relationship identity semantics: one stored relationship may
  exist for a subject/relation/resource identity regardless of Caveat or
  expiration. `:create` conflicts with any existing qualifier variant,
  `:touch` replaces qualifiers atomically, and `:delete` does not require the
  qualifiers.
- **BREAKING**: Provide no v7-to-v9 relationship migration or dual-read path.
  A v9 client accepts a fresh/rebuilt relationship store only and fails startup
  when v7 relationship datoms remain. Fence Phase 2 activation with a
  persisted semantic capability epoch so Phase 1 readers and writers cannot
  serve a Caveat-enabled store.

## Capabilities

### New Capabilities

- `temporal-relationship-validity`: Native future, expiring, and bounded
  relationship semantics, trusted valid-time snapshots, temporal proofs, and
  non-authoritative expiration collection.
- `relationship-caveats`: SpiceDB-compatible Caveat definitions, sparse
  relationship-bound context storage, write validation, request-context
  precedence, conditional permission results, and Caveat evaluation.

### Modified Capabilities

- `converged-relationship-storage`: Replace four-slot v7 endpoint values with
  fixed eight-slot v9 values while retaining two authoritative datoms and one
  ordered relationship source per traversal direction.
- `public-authorization`: Add Caveat request context and an explicit
  three-state permission result while keeping `can?` fail-closed.
- `dependency-validated-authorization-cache`: Add temporal stability and
  complete Caveat definition, bound-context, request-context, evaluator, and
  conditional-result dependencies.
- `cursor-dependency-validity`: Bind continuation to valid-time, Caveat context,
  Caveat dependencies, and conditional result semantics.

## Impact

- Affects the shared relationship codec and engine, every backend adapter
  (Datomic Pro, Datahike, Datalevin, and DataScript), schema parsing and
  persistence, relationship mutation and reads, permission APIs, object
  cleanup, integrity reports, caches, cursors, formal models, and release
  documentation.
- Changes the persisted relationship storage ABI to version 9 and invalidates
  old cache snapshots, cursor tokens, and populated databases containing v7
  relationship values.
- Ordinary and validity-only relationships remain exactly two authoritative
  endpoint datoms; finite expiration adds one derived index datom. Caveated
  relationships add no relationship entity; only a non-empty
  relationship-bound context adds one sparse context entity and payload datom.
- Requires operators upgrading a populated database to export/re-transact
  Relationships into a fresh v9 database or otherwise rebuild all relationship
  data before running v9.
- Encountered authoritative corruption or evaluator failure aborts the affected
  operation with a typed error. Dropping a faulty edge is unsafe under exclusion.
- One candidate stream does not imply one total database read or bounded page
  latency: integrity validation and inactive-prefix scanning have explicit costs
  and release gates.

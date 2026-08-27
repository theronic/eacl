# Change: Isolate public snapshots and add cache-safe speculative `with`

## Why

Datomic exposes no reliable property by which a consumer can distinguish a
committed database value from a `d/with` product. Both may have the same
database identity, basis `t`, and `:db/txInstant` while containing different
facts, so admitting caller database values can let a speculative answer poison
the cache later used by a real transaction at the same basis.

EACL must therefore control the public snapshot boundary. Consumers still need
composable prospective authorization tests, including relationship and schema
changes, without discarding committed cache proofs that are demonstrably
unaffected.

## What Changes

- **BREAKING:** Public authorization and snapshot APIs accept an EACL client or
  an EACL-created snapshot, never a caller-supplied native database value.
  Consumers that bypass public APIs receive no cache-coherence guarantee.
- Add composable `(eacl/with client-or-snapshot tx-data)` as the explicit
  provenance boundary for prospective transaction data.
- Add `(eacl/with-schema client-or-snapshot schema options)` for prospective
  permission-schema replacement using the same parser, reference checks, and
  safety guards as committed schema writes.
- Speculative snapshots may reuse committed proof-carrying cache entries only
  when their complete relationship and schema dependencies are disjoint from
  all cumulative speculative effects. They never reuse content-ambiguous
  exact-basis entries and never populate any cache tier.
- `with-schema` defaults to rejecting schema-orphaned relationships. Its
  speculative-only `:retain-inert` policy keeps those relationship datoms
  physically present but semantically invisible while the relation is absent,
  avoiding O(N) retractions and reporting the affected relations.
- Retain `:eacl.relation/version` as the committed multi-peer watermark. Cache
  coherence SHALL NOT use `d/log`, `d/tx-range`, transaction-log draining, or
  transaction listeners.

## Capabilities

### New Capabilities

- `public-authorization`: Defines the client/snapshot public boundary and the
  supported composable speculative operations.
- `cache-coherence`: Defines publication prohibition and safe committed-proof
  reuse for speculative snapshots.

### Modified Capabilities

None.

## Impact

- **Public API:** removes raw native-database snapshot construction; adds
  `eacl/tx-relationship`, `eacl/with`, `eacl/with-schema`, and speculative
  diagnostics.
- **Backends:** Datomic Pro, Datahike, and DataScript provide native
  speculative apply plus local transaction-effect reporting. Datalevin reports
  the capability as unsupported and fails closed.
- **Cache kernel:** speculative lookups require complete dependency witnesses
  and cumulative relationship/schema effect checks; speculative publication is
  forbidden.
- **Schema writers:** committed and speculative paths share a pure replacement
  planner, while `:retain-inert` remains speculative-only.
- **Documentation and tests:** cover PR #154's same-basis collision, chained
  speculation, selective proof reuse, schema speculation, retained inert
  relationships, and the unsupported raw-DB boundary.

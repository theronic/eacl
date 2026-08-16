## Why

EACL currently has two incompatible cache/consistency designs: Datomic uses
database-visible per-relation transaction stamps and authenticated basis tokens,
while DataScript and Datahike use exact-content proofs but return
connection-local listener counters that cannot express cross-connection
freshness. A global database basis alone cannot prove whether a cached answer's
authorization dependencies changed, so EACL needs one model that separates
revision selection from dependency validation and supports
`:at-least-as-fresh` correctly on every backend.

## What Changes

- Define a backend-neutral causal token and snapshot-selection contract that
  binds tokens to a backend/source scope, an append-only EACL mutation anchor,
  and an exact backend locator where available. Numeric transaction positions
  are wait hints, not standalone lineage proofs.
- Support `:at-least-as-fresh` for Datomic, Datahike, and DataScript by selecting
  one immutable snapshot at or beyond the requested revision before cache proof
  validation or authorization execution.
- Replace DataScript and Datahike listener-counter write tokens with tokens
  derived from the committed transaction report's `db-after` snapshot.
- Remove listener counters from cache correctness. Keep only optional,
  explicitly non-authoritative listeners where a backend can demonstrate a
  local performance benefit.
- Standardize database-visible, cryptographically random schema and per-relation
  mutation identities so a cached answer survives unrelated transactions but
  misses after any relevant relationship or schema mutation, even across
  cloned, restored, reset, or force-moved histories that reuse transaction
  numbers.
- Add an append-only EACL mutation journal. A token's causal floor is satisfied
  only when the selected snapshot contains its authenticated mutation anchor;
  basis `t` and `:max-tx` are bounded-wait accelerators.
- Make cache entries record both the snapshot at which the answer was computed
  and the snapshot at which its dependency proof was most recently validated.
- Add backend-specific bounded freshness waits: `d/sync` for Datomic,
  branch-head refresh/polling for distributed Datahike readers, and monotonic
  connection checks for DataScript.
- Use Datahike's stable store identity, branch, mutation anchor, commit id, and
  parent graph for causal and exact selection. Treat `:max-tx` only as a polling
  hint because branch creation and `force-branch!` make it non-global. Use
  `commit-as-db` when the commit graph retains the snapshot, with temporal
  `as-of` as a capability-gated fallback.
- Define stable pagination by graph equivalence: a cursor may continue on a
  newer snapshot only when its authenticated complete dependency proof matches.
  If the proof changed, EACL reconstructs the exact original snapshot or fails
  with a typed stale/snapshot-expired error.
- Add shared authenticated token, cursor, and authorization-cache-entry formats
  with domain-separated keys, key ids, key rotation, canonical serialization,
  and bounded decoding. Cursor confidentiality remains capability-gated so
  Datomic can preserve encrypted pagination without imposing asynchronous
  WebCrypto on synchronous ClojureScript APIs. The existing portable cursor is
  only base64-encoded and is not a correctness boundary.
- **BREAKING** Replace the unrecoverable DataScript/Datahike decimal
  `:zed/token` listener counters with versioned, database-bound opaque tokens.
- **BREAKING** Require custom and out-of-band relationship writers that opt
  into fast mutation-identity proofs to publish the mutation journal and every
  affected dependency identity atomically. Otherwise EACL uses a full-content
  proof or disables completed-answer caching; it never silently assumes writer
  compliance.
- **BREAKING** Require every authorization-relevant writer, including mutable
  object-identity or caveat data read by custom adapters, to publish mutation
  anchors before the source can issue causal read tokens or advertise
  `:at-least-as-fresh`. Full-content proof can protect a cache lookup but cannot
  manufacture causal ordering for an unjournaled write.

## Capabilities

### New Capabilities

- `cross-backend-revision-consistency`: Revision tokens, immutable snapshot
  selection, bounded freshness waits, and backend-specific exact-snapshot
  capabilities.
- `dependency-validated-authorization-cache`: Cache entry proofs, per-relation
  invalidation, proof lifting across unrelated transactions, failure behavior,
  and managed-mutation requirements.
- `snapshot-stable-pagination`: Cursor snapshot binding, continuation behavior,
  historical reconstruction, and explicit expiry for backends without a
  reconstructable snapshot.

### Modified Capabilities

- `modular-backend-workspace`: Extend the v8 adapter contract with logical
  database identity, revision comparison, token codec, freshness selection, and
  optional exact-snapshot operations while keeping algorithms backend-neutral.

## Impact

- Affects `eacl.backend.v8`, `eacl.cache`, consistency descriptors, cursor
  envelopes, all three backend adapters, all three public client modules,
  relationship/schema transaction helpers, cache entry formats, tests,
  documentation, and migration guidance.
- Adds database metadata/schema attributes for mutation anchors, global graph
  head, schema mutation identity, and relation mutation identities. Journal
  retention is tied to the maximum token lifetime.
- Reuses Datomic's current HMAC token and transaction-stamp foundations but
  moves common policy and cache semantics into `eacl`.
- Requires capability-gated authoritative-head selection, including zero-arg
  `d/sync` for Datomic `:fully-consistent`, Datahike authoritative branch-head
  access, and exact reconstruction/expiry behavior.
- Invalidates existing DataScript/Datahike tokens and completed-answer cache
  entries through explicit format-version changes; authorization data itself
  remains compatible.

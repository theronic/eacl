## Why

The prospective v7.4 cache and consistency implementation can return stale authorization results,
invalidate otherwise valid pagination, leak cache-observability failures into authorization calls,
and report inaccurate mutation outcomes even though its regular test suite passes. These defects
must be resolved before release while preserving v7.3's snapshot-stable pagination and keeping
cache acceleration optional.

## What Changes

- Capture each live authorization database value and its relationship revision proof as one
  coherent observation, including when several clients share a coordinator but use independently
  lagging Datomic connections.
- Preserve cursor snapshot semantics across unrelated transactions and when result caching is
  disabled by replaying from the cursor's historical Datomic basis when no exact cached state is
  available.
- Bind every page cursor to its Datomic database identity, and coerce cached historical lookup
  pages against the basis that produced the cached answer rather than the caller's newer live DB.
- Make every cache-provider and cache-observability operation best-effort for non-exact reads, while
  retaining typed snapshot-unavailable failures where exact state truly cannot be recovered.
- Keep recursive pages and continuations isolated by the configured cache namespace, and include
  every retained traversal structure in continuation admission estimates.
- Replace the draft unsigned `:zed/token` encoding with a versioned HMAC-authenticated format that
  detects malicious frontend modification, supports backend key rotation, rejects invalid tokens
  before synchronization or historical access, and bounds waits for valid revisions not yet
  visible locally.
- Document that authentication prevents forgery but not replay: backend policy selects the
  consistency mode, and frontend-echoed tokens SHOULD normally be used as freshness lower bounds
  rather than authority to perform historical reads.
- Return typed unsupported-operation errors for every invalid relationship update operation.
- Report the number of relationship datoms actually retracted by object deletion instead of the
  number of attempted retract operations.
- Add adversarial regression tests for multiple connections, unrelated transactions, disabled
  caching, hostile cache providers, invalid operations, partial relationship state, future
  consistency tokens, cross-database page-token replay, stale cached entity deletion, recursive
  namespace isolation, and continuation admission accounting.

## Capabilities

### New Capabilities

- `v7-4-cache-consistency-hardening`: Defines coherent snapshot capture, cache-independent
  pagination, authenticated-token boundaries, provider-failure isolation, and precise mutation
  behavior required for the v7.4 authorization cache.

### Modified Capabilities

None.

## Impact

- Affects `eacl.datomic.core`, `eacl.datomic.cache`, `eacl.datomic.consistency`,
  `eacl.datomic.impl.indexed`, their public configuration and error data, and associated tests.
- Restores pagination behavior compatible with v7.3 while retaining v7.4 cache acceleration.
- Adds Zed-token signing-key configuration, key rotation, bounded freshness waits, and frontend
  round-trip security documentation.
- Uses JCA HMAC-SHA-256 and adds no third-party cryptographic or cache-provider dependency and no
  persistent Datomic schema.

# EACL v7.4 candidate

This change may ship as a v7.3 patch while v7.3 is still recent. It preserves the public
connection-oriented authorization API and adds an optional, bounded, ephemeral cache without
adding Datomic schema attributes or permanent tuples.

## Authorization cache

- One typed store serves `can?`, both lookup directions, both counts, exact results, and recursive
  continuations.
- Recursive continuations use the bounded local store by default. Completed exact-result retention
  is opt-in with `:exact-results? true`; live result caching implies exact retention.
- Cache keys and values contain internal EIDs. Missing external IDs are resolved at the boundary
  and are not cached.
- Live reuse is opt-in with `:cache {:live-results? true ...}` and an explicit relationship
  coordinator shared by every participating EACL reader and writer.
- Relevant relationship helpers publish their exact committed Datomic `t`; unrelated application
  transactions, no-op writes, and unrelated relation changes do not invalidate hot entries.
- The built-in memory store has hard total-weight, entry-weight, entry-count, TTL, per-kind, and
  optional two-hit admission limits.
- Custom portable stores can be implemented without adding backend dependencies to EACL core.

## Recursive pagination and counts

- Recursive pages resume bounded continuation state instead of replaying every preceding prefix.
- A missing required continuation fails closed with a typed cursor/snapshot error.
- Acyclic count misses advance through bounded frontier pages; recursive counts use one explicit,
  hard-capped traversal state. Neither count direction retains a full-cardinality lazy result head.

## Consistency

- Write tokens are versioned, database-bound envelopes around the exact committed Long basis `t`.
- Authorization reads support `fully-consistent`, `minimize-latency`, `at-least-as-fresh`, and
  cache-resident `at-exact-snapshot`.
- Ordinary reads do not call zero-argument `d/sync`. Only `at-least-as-fresh` may wait for its
  explicit lower-bound `T`.
- Exact snapshots never invoke `d/as-of`; expiry, eviction, corruption, or provider failure returns
  `:eacl.consistency/snapshot-unavailable`.
- Optional bounded revision checkpoints construct age-based lower-bound tokens without arithmetic
  on `t`, background timers, retained DB values, or implicit synchronization.

## Schema and mutation lifecycle

- A client reads `:eacl/schema-version` once at construction. Hot reads do not rescan schema when
  unrelated transactions advance the connection.
- `write-schema!` rotates that client's immutable schema generation only after a successful write.
- An unstamped database remains usable but result caching stays disabled until `write-schema!`
  establishes a generation; this is not a v6 compatibility mode.
- Direct transactions of EACL relationship or schema data are outside cache coherence and are not
  detected through per-basis or transaction-log polling.
- Consumers should call `delete-relationships!` before retracting an entity.
  `eacl.datomic.integrity/dangling-relationship-report` detects reverse ghost tuples left by an
  incorrect deletion sequence.

The cache can be disabled with `{:cache false}`. Authorization remains correct and usable, with
the expected loss of cache-dependent performance and exact-snapshot retention.

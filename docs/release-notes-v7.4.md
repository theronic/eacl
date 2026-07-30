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
- Older cached lookup answers resolve those EIDs against the answer's own historical basis, so a
  later live entity deletion does not break `minimize-latency` or freshness-floor reads.
- Live reuse is opt-in with `:cache {:live-results? true ...}` and an explicit relationship
  coordinator shared by every participating EACL reader and writer.
- Every cache-enabled client has a local coordinator for its own cursor proofs; this does not
  permit cross-client live reuse without the explicit shared coordinator above.
- Relevant relationship helpers publish their exact committed Datomic `t`; unrelated application
  transactions, no-op writes, and unrelated relation changes do not invalidate hot entries.
- A reader connection behind the coordinator's published floor performs a bounded targeted catch-up
  and retries coherent capture; it cannot cache a stale DB under a newer proof.
- The built-in memory store has hard total-weight, entry-weight, entry-count, TTL, per-kind, and
  optional two-hit admission limits.
- Custom portable stores can be implemented without adding backend dependencies to EACL core.
- Cache lookup, capability, publication, metrics, and provider-error failures are contained as
  misses or rejected admissions and never replace authoritative authorization outcomes.

## Recursive pagination and counts

- Recursive pages resume bounded continuation state instead of replaying every preceding prefix.
- Continuations retain scalar scan descriptors and bounded internal-EID chunks, not Datomic DB
  values or lazy index sequences.
- Recursive state is keyed by its relationship proof rather than general Datomic basis churn.
- A cursor pins its database identity, basis, operation, query, ordering, and schema semantics.
  A missing continuation or exact page reconstructs `d/as-of` and replays its deterministic prefix,
  even when caching is disabled or live relationships/schema have changed.
- Database identity is authenticated and rejected before historical selection; shared page-token
  keys do not permit cursor replay against another logical Datomic database.
- Recursive physical keys include the configured cache namespace, and continuation admission
  accounts for retained reverse-rule graphs.
- Acyclic count misses advance through bounded frontier pages; recursive counts use one explicit,
  hard-capped traversal state. Neither count direction retains a full-cardinality lazy result head.

## Consistency

- Write tokens are bounded, database-bound v2 envelopes authenticated with a domain-separated
  HMAC-SHA-256 tag over their exact encoded claims. The unreleased unsigned v1 format is rejected.
- Authorization reads support `fully-consistent`, `minimize-latency`, `at-least-as-fresh`, and
  historical `at-exact-snapshot`.
- Ordinary reads do not call zero-argument `d/sync`. Targeted revision waits are bounded by the new
  positive `:consistency-sync-timeout-ms` option (30 seconds by default) and return typed
  freshness-unavailable diagnostics rather than using an older DB.
- Exact cache entries remain accelerators; on a miss or provider failure, exact reads evaluate
  against `d/as-of` with schema state reconstructed at the requested basis.
- Optional bounded revision checkpoints construct age-based lower-bound tokens without arithmetic
  on `t`, background timers, retained DB values, or implicit synchronization.
- `:zed-token-key`, `:zed-token-keyring`, and `:zed-token-kid` support stable multi-instance keys
  and overlap-based rotation. When omitted, domain-separated signing keys derive from the page-token
  keyring; the random default is deliberately client-instance-local.
- Token authentication prevents database/revision forgery but not replay and is not authorization.
  Backends should normally apply frontend-echoed tokens as `at-least-as-fresh`; choosing exact
  historical access must remain a backend-controlled decision.

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
- Relationship update operations are validated before endpoint resolution. `delete-object!`
  reports actual committed relationship-datom retractions across all batches.

The cache can be disabled with `{:cache false}`. Authorization remains correct and usable, with
the expected loss of cache-dependent performance; cursors and exact reads still use reconstructable
Datomic history.

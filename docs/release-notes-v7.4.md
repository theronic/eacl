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

## Fixes from the 2026-07-31 adversarial review

See [docs/reports/2026-07-31-eacl-v7.4-cache-adversarial-review.md](reports/2026-07-31-eacl-v7.4-cache-adversarial-review.md).
All of these were found in this candidate and fixed before release.

- Recursive page-cache keys are scoped by pagination direction. A `:last`/`:before` page could
  previously be served to a later `:first`/`:after` request naming the same cursor and size,
  silently returning the wrong page — or an empty one, which stops a paginating caller early.
- A relationship write that fails before submitting a transaction no longer invalidates cached
  results. A `:create` conflict or unknown object id commits nothing.
- A client constructed before the database was schema-stamped adopts the stamp when one appears,
  and cursors minted on an unstamped basis validate against that same basis. Such a client could
  previously mint page-one tokens it then rejected on page two.
- Lookup results naming objects with no external id raise `:eacl/unresolvable-object` listing every
  offending eid, instead of a cache-flavoured `:eacl.consistency/snapshot-unavailable` naming one.
- `delete-object!` takes the relationship barrier per batch, so a large deletion no longer blocks
  concurrent lookups for its whole multi-transaction run.
- `fully-consistent` reads use a matching basis-pinned exact entry, so `:exact-results? true` is an
  accelerator for the default consistency mode rather than write-only cost.
- Page tokens are length-bounded, reject hostile EDN without escaping a `StackOverflowError`, and
  report every cursor rejection as `:eacl.pagination/invalid-cursor` with a `:reason`.
- Cursor pages no longer publish live entries and latest-result pointers that nothing can read.
- The relationship barrier covers only the coordinator-snapshot/database pair and uses optimistic
  reads, and the reader catch-up loop is bounded. Under 8 threads `can?` with `:live-results? true`
  went from ~2.3x slower than `{:cache false}` to ~2.3x faster.
- Exact result entries are keyed by a log-verified cache epoch instead of Datomic `basis-t`, so an
  unrelated application write no longer invalidates them. Under one unrelated transaction per read,
  `can?` went from 320 evaluations per 300 reads to 1 (26.9us -> 12.1us, and 13.9us with no cache at
  all); `lookup-resources` 101.8us -> 49.2us. The epoch is verified against the transaction log, so
  it observes writes from another connection, another process, and raw `d/transact` of
  `tx-relationship` output — none of which a coordinator can see. A connection without a usable log
  disables exact retention rather than reverting to basis keying, which measured worse than no
  cache.
- Forward acyclic pages resume from cached per-intermediate stream heads. Every page previously
  opened an index scan for each of the subject's intermediates just to learn where each stream
  starts; a page now re-opens only the streams it actually drew from. A full walk over 4000
  intermediates: 591ms -> 199ms; on the multipath benchmark, forward pagination 1.3ms -> 0.85ms per
  page. Still super-linear — see the report for what remains — and a miss falls back to the
  existing frontier replay, so nothing depends on the cache for correctness.
- `can?` answers an arrow by intersecting two sorted intermediate streams instead of scanning the
  resource's side in full, so it no longer scales with arrow fan-out. A doc attached to N teams
  where the user belongs to one of them: 133us -> 13us at N=100, 1.34ms -> 13us at N=1000,
  7.16ms -> 16us at N=5000. An arrow with a single intermediate keeps the old point probe.
- Permission paths are ordered cheapest-to-check first, so a union short-circuits on a direct
  relation before paying for an arrow. Path order previously came out of a `clojure.set/difference`
  in `write-schema!` — `owner + team->access` versus `team->access + owner` measured 6.8ms versus
  3.4us on identical data, decided by hash order and invisible to the schema author.
- Page tokens moved from EDN to a compact binary payload (`eacl.datomic.codec`) with a reused
  AES-GCM cipher: encode ~41us -> ~2.4us, decode ~24us -> ~2.6us. A page mints two cursors and
  reads one, so `lookup-resources`/`lookup-subjects` are roughly 2x faster uncached and 3x faster
  with live results. The prefix is now `eacl4_`; `eacl3_` cursors from an earlier build are
  rejected as `:eacl.pagination/invalid-cursor`, the same way an expired cursor already is.

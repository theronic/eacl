# EACL v7.4 candidate

This change may ship as a v7.3 patch while v7.3 is still recent. It preserves the public
connection-oriented authorization API and adds an optional, bounded, ephemeral cache.

It adds exactly one Datomic schema attribute, `:eacl/relation-version`, and no permanent tuples.
The attribute holds one `:db/noHistory` datom per relation naming the transaction that last changed
a relationship using it; this is what lets a cached answer survive writes to relations it does not
read. `write-schema!` installs it when a database predates it, so there is no migration step. Until
it exists, exact-result retention is simply off.

## Authorization cache

- One typed store serves `can?`, both lookup directions, both counts, exact results, and recursive
  continuations.
- Recursive continuations use the bounded local store by default. Completed exact-result retention
  is opt-in with `:remember-answers`.
- Cache keys and values contain internal EIDs. Missing external IDs are resolved at the boundary
  and are not cached.
- Older cached lookup answers resolve those EIDs against the answer's own historical basis, so a
  later live entity deletion does not break `minimize-latency` or freshness-floor reads.
- `:cache {:remember-answers ...}` says whether EACL remembers the answer to a permission check so
  an identical later check skips evaluation: `false` (default), `true`, or `:on-repeat` (remember
  only once the same check has been asked twice). Pagination and traversal state are cached either
  way. There is no coordination to configure: invalidation rides on the `:eacl/relation-version`
  stamps EACL's own write helpers transact, so every reader of the database observes every write.
- Which value to pick depends on your traffic, not on EACL internals. When checks repeat,
  remembering took a direct permission from 4.3us to 3.7us and an arrow from 7.6us to 3.9us. When
  they never repeat it went 9.1us to 24.8us, because every read pays a store write that nothing
  reads back — hence the default and the `:on-repeat` hedge, which cut stores 3x and evictions 3.7x
  on a mixed workload.
- A single read can opt out with a per-request `:cache false` — on the map arity of `can?`, and in
  the query map for lookups, counts and `read-relationships`.
- Relationship helpers publish exactly which relations they changed; unrelated application
  transactions, no-op writes, and unrelated relation changes do not invalidate hot entries.
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
- Exact result entries are keyed by the relations they depend on, stamped by EACL's own write
  helpers, so direct transactions of EACL relationship or schema data — from this process, another
  process, or raw `d/transact` of `tx-relationship` output — DO invalidate them, and writes to
  relations an answer does not read do not.
- Every relationship-producing helper stamps `:eacl/relation-version` on the relations it touches,
  including `tx-relationship`, `tx-update-relationship`, `tx-delete-object`,
  `tx-retract-orphaned-relationships` and `eacl.datomic.integrity/repair-tx-data`. A caller that transacts one of these directly publishes the
  change without knowing the cache exists. The stamp's value is the transaction entity, so repeated
  or concatenated helper output collapses to one datom instead of conflicting.
- `delete-object!` transacts in batches and re-stamps each batch with the relations that batch
  retracts. `tx-delete-object` deduplicates its output, which keeps only the first stamp per
  relation, so any caller slicing that output into separate transactions must run
  `eacl.datomic.impl/stamp-relation-versions` over each slice.
  `integrity/repair-tx-batches` partitions by dangling half rather than by op for the same reason,
  so a retraction and the stamp that publishes it always share a transaction.
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
- `fully-consistent` reads use a matching epoch-keyed entry, so remembering answers accelerates the
  default consistency mode rather than being write-only cost.
- Page tokens are length-bounded, reject hostile EDN without escaping a `StackOverflowError`, and
  report every cursor rejection as `:eacl.pagination/invalid-cursor` with a `:reason`.
- Cursor pages no longer publish live entries and latest-result pointers that nothing can read.
- Exact result entries are keyed by per-relation change stamps instead of Datomic `basis-t`, so
  neither an unrelated application write nor a write to an EACL relation the answer does not read
  invalidates them. Under one unrelated transaction per read, `can?` went from 320 evaluations per
  300 reads to 1 (26.9us -> 12.1us, and 13.9us with no cache at all); `lookup-resources`
  101.8us -> 49.2us.

  Scanning `d/tx-range` for EACL datoms was the first design and shipped briefly. It is sound, but
  it can only ever establish THAT something changed, never WHAT, so every EACL write invalidated
  every cached answer. On a schema where `doc/view` and `folder/editor` share nothing, writing
  folder relationships between reads measured (same process, 400 reads each):

  | `can?`, arrow fan-out 400 | us/read | evaluations |
  |---|---|---|
  | no cache | 19.2 | 400/400 |
  | cache, global epoch | 102.4 | 340/400 |
  | cache, per-relation | 6.9 | 0/400 |

  | `lookup-resources {:first 50}` | us/read | evaluations |
  |---|---|---|
  | no cache | 106.2 | 400/400 |
  | cache, global epoch | 158.4 | 340/400 |
  | cache, per-relation | 53.3 | 0/400 |

  A global epoch was therefore WORSE than no cache under EACL write traffic: every read paid a
  publication it could never read back, and the store thrashed on entries nothing would match. With
  no churn at all the per-relation cache still wins — `can?` 8.9us -> 5.4us, lookups
  83.0us -> 45.7us — so the epoch's cost (one index seek per dependency, ~0.8us) is absorbed.

  Stamps are written by EACL's own tx-data helpers, so they observe writes from another connection,
  another process, and raw `d/transact` of `tx-relationship` output — none of which a coordinator
  can see — and `d/log` is no longer used at all. A database without `:eacl/relation-version`
  retains nothing rather than reverting to basis keying, which measured worse than no cache; its
  next `write-schema!` installs the attribute.
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

## Removed: `:live-results?` and the relationship coordinator

`:cache {:live-results? true}` and `:cache {:coordinator ...}` are gone, along with the
`RelationshipCoordinator` protocol, `local-coordinator`, `local-context`, the read and mutation
barriers, and the bounded reader catch-up loop. Both keys now throw `:eacl/invalid-config` rather
than being silently ignored.

Per-relation stamps made the coordinator redundant and then strictly worse. It was reachable in
exactly one consistency mode — `:fully-consistent`, where the epoch-keyed entry was already the
fallback — and it was consulted FIRST, so a coordinator that had missed a write served a stale
answer where an epoch-keyed read would correctly have missed. A coordinator can only observe writes
made through a client sharing it; a stamp is transacted with the relationship datoms, so every
reader of the database observes it.

Migration: replace `:cache (assoc (cache/local-context) :live-results? true)` with
`:cache {:remember-answers true}`, and delete any coordinator plumbing. A writer-only client
configured as `{:store false :coordinator shared}` becomes `{:cache false}` — it no longer needs to
participate in anything. `:eacl.consistency/coordinator-floor-unreachable` can no longer be raised.

`spiceomic-write-relationships!` no longer distinguishes a validation failure from a
possibly-committed throw. It does not need to: tx-data that never commits publishes nothing, so the
regression that distinction existed to prevent — one routine `:eacl/relationship-conflict` flushing
every cached result — is now structurally impossible.

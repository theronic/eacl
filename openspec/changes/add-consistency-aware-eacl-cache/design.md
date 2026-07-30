## Context

EACL authorization reads have historically evaluated against the Datomic database value available
to a connection and relied on Datomic Peer caching for speed. That remains effective for many
single checks, but recursive pagination currently replays traversal prefixes and can approach
`O(N²/page-size)`. Repeated lookups, counts, and permission checks also redo identical graph work.

A safe cache cannot use every Datomic basis change as its invalidation signal: application
transactions make a connection's database value advance frequently without changing EACL
relationships or schema. Nor should EACL scan the definition index for every new database value.
EACL already owns the supported mutation boundaries: schema changes go through `write-schema!`,
and relationship changes go through the EACL relationship helpers. Those boundaries can publish
the precise state needed to prove a cached answer valid.

The design assumes one EACL client is bound to one logical database. Consumers must route all
EACL relationship changes relevant to a cache coherence scope through the same explicit
coordinator. Direct transaction of EACL-owned relationship or schema data is unsupported. Object
entities may still be retracted after their relationships are removed through EACL; diagnostics
for reverse ghost tuples remain separate from cache prevention.

The cache is an optimization, not an authority. Disabling it must leave a correct, usable library.
No derived relationship indexes or cache tuples are written to the consumer's Datomic database.

## Goals / Non-Goals

**Goals**

- Remove recursive pagination's repeated-prefix behavior by retaining bounded traversal
  continuations.
- Accelerate recursive and non-recursive lookups, counts, and `can?` with one cache abstraction.
- Prove live-entry validity from schema and relevant relationship changes rather than database
  basis churn.
- Add explicit consistency selection using versioned `:zed/token` revisions.
- Keep exact-snapshot behavior correct without automatically invoking `d/as-of`.
- Cache only internal entity IDs and keep boundary resolution/coercion outside the cache.
- Bound memory and prevent large counts from retaining result heads.
- Permit optional local or shared cache backends without adding their dependencies to EACL core.
- Preserve the existing connection-oriented public API and pure DB-oriented internal evaluators.

**Non-Goals**

- Detect direct writes to EACL-owned Datomic data by scanning every database value or the
  transaction log.
- Make time travel a normal EACL operation or reconstruct an evicted exact snapshot automatically.
- Preserve schema caches for every historical schema version.
- Add permanent Datomic attributes, derived tuples, or terminal-resource indexes.
- Make a shared cache backend act as a relationship-mutation coordinator.
- Cache negative answers for external IDs that do not resolve to EACL entities.
- Add separate recursive, non-recursive, or snapshot-specific lookup functions.
- Add mandatory RocksDB, Apache Kvrocks, Redis, or serializer dependencies.
- Support a missing schema-version marker as a legacy-v6 compatibility mode.

## Decisions

### 1. A cache answer must carry its proof

The central invariant is:

> A cached result is usable only when EACL can prove that it is the result of the same canonical
> operation at a snapshot permitted by the requested consistency mode.

Every provider value is wrapped with a cache format version, entry kind, and the complete canonical
key. EACL validates that wrapper and the value shape before using it. A mismatch is a miss.

Live keys include:

- logical database identity;
- coordinator incarnation and uncertainty generation;
- client schema generation;
- operation and every answer-affecting option;
- canonical query expressed with internal entity IDs;
- the relevant relationship dependency revision; and
- pagination position where applicable.

Exact keys additionally identify their captured basis `t`. Entry classes are distinct even though
they share one store: completed lookup page, recursive continuation, count, permission Boolean,
and exact-snapshot metadata. Revision checkpoints remain separate explicit client-local state
because their monotonic capture times are not comparable across processes.

Internal recursive pages and continuations use the schema generation plus relationship proof
rather than general `basis-t`, so a fresh identical walk remains hot across unrelated application
transactions. Public exact-result entries remain keyed by `t`; the two notions are not conflated.

For consistency modes that permit recomputation, eviction, expiry, rejected admission, or a
provider error becomes a miss. An already-issued cursor may also recompute its deterministic
prefix while its complete schema and relationship proof still matches the current DB. After that
proof changes, falling forward would change semantics: only an already retained exact page may
answer, otherwise EACL returns typed snapshot-unavailable.

### 2. External IDs are resolved only at the boundary

The public boundary resolves external subject and resource IDs against the selected DB before
constructing a cache key. A missing input returns the normal false or empty response without
entering the result cache for ordinary live/freshness requests. An exact request whose boundary can
no longer be resolved returns snapshot-unavailable because EACL cannot prove the historical
internal query identity. This avoids a high-cardinality negative-ID cache, avoids invalidating such
entries when an object later appears, and prevents a current boundary miss from masquerading as an
exact historical denial.

Keys and results contain internal entity IDs only. Completed pages are coerced back to external
values at the response boundary. EACL object identity mappings are treated as stable for the
lifetime of an entity. If an internal ID in an exact cached page no longer resolves, EACL returns
snapshot-unavailable rather than changing the historical result.

This separation also keeps cached answers usable across harmless representation choices at the
public adapter boundary while ensuring that no public record or external ID leaks into the cache
contract.

### 3. An explicit coordinator linearizes reads with relationship mutations

Cache context is explicit client state, not a global registry. It contains at least a `CacheStore`,
a `RelationshipCoordinator`, a coordinator incarnation, an uncertainty generation, and optional
revision-checkpoint state. A cache-enabled client gets a local coordinator by default so its own
cursor proof survives unrelated Datomic basis churn. Cross-client live-result reuse remains
disabled unless one coordinator is supplied explicitly to every reader and writer in the
coherence scope.

The coordinator exposes a short read barrier and an exclusive mutation barrier:

1. A reader acquires the read side.
2. It captures `(d/db conn)`, the current schema generation, and dependency revision state.
3. It releases the barrier before cache I/O or graph traversal.
4. A writer holds the write side across the Datomic transaction and publication of the committed
   relationship revision.

Thus a read sees either a complete pre-write DB plus its old revision state or a complete
post-write DB plus its new revision state. It cannot pair a new DB with an old cache scope during
the transaction/publication window.

The coordinator is deliberately separate from the cache provider. A shared Redis-like store does
not establish ordering between EACL clients. Separate coordinators use separate incarnations and
therefore miss one another's live entries. A deployment wanting cross-process reuse would need a
coordinator implementation that provides the same barrier and publication contract; merely
sharing storage is insufficient.

### 4. Relevant relation definitions use committed `t` revisions

Schema compilation supplies the complete transitive relation-definition dependency set for an
operation, including branches that can affect a negative answer. Relationship helpers already
know which relation definitions they mutate. After a non-no-op write, the coordinator records
`d/basis-t` from `db-after` as the last-change revision of each changed definition.

For one computation, the vector of dependency revisions can be compressed to its maximum `t`.
This is safe because any relevant write occurring after the captured DB commits at a `t` greater
than that DB's basis and therefore greater than the prior maximum. The live scope also includes:

- a random or otherwise collision-resistant coordinator incarnation, preventing restart reuse; and
- an uncertainty generation, rotated when a write might have committed but EACL cannot determine
  its committed basis or changed definitions.

No-op writes publish no revision. Application transactions and relationship writes to definitions
outside the complete dependency set do not change the key. This is the key performance distinction
from invalidating on every DB basis.

An alternative was to poll Datomic's transaction log. It was rejected because supported mutations
already expose the exact interaction points, polling adds read and lifecycle cost, and it still
cannot make unsupported direct mutation safe without a distributed coordination contract.

### 5. Schema generation changes only at the schema boundary

The client reads `:eacl/schema-version` once during construction and builds one immutable schema
cache. It does not re-read the marker or scan the definition index for each DB value.

`write-schema!` builds the replacement schema cache and rotates the client schema generation after
the schema transaction succeeds. Every result key includes this generation, making older entries
unreachable; bounded eviction reclaims them later. A bulk local clear may accelerate cleanup but is
not relied upon for correctness.

A database without a marker is treated as an unstamped current installation, not as a v6
compatibility case. Authorization remains available, but result caching stays disabled until
`write-schema!` establishes a cacheable generation.

Because schema changes are rare and client-scoped, a client that does not participate in a schema
write is not polled. Consumers must recreate or explicitly refresh other clients after a schema
deployment. This makes the ownership boundary visible rather than paying an O(N) or even per-basis
marker-read tax on every hot call.

### 6. Consistency selection is explicit and precedes cache lookup

EACL supports four descriptors:

- `fully-consistent` captures the current locally observed `(d/db conn)` plus matching coordinator
  state. It may reuse a live entry proven valid for that state. It does not call zero-argument
  `d/sync`; a consumer that needs the Peer to observe the transactor head can choose to synchronize
  before calling EACL.
- `minimize-latency` may choose the newest coherent cached snapshot that answers the query, falling
  back to the current locally observed DB. Its result is explicitly allowed to be stale.
- `at-least-as-fresh T` accepts a cache or local DB revision greater than or equal to `T`. If the
  local Peer is behind and no qualifying entry exists, EACL may call the Datomic synchronization
  operation that waits specifically for `T`, because the caller explicitly requested that lower
  bound. It never returns an older answer.
- `at-exact-snapshot T` accepts only a cached answer or continuation explicitly recorded for `T`.
  A miss returns snapshot-unavailable. Core EACL does not invoke `d/as-of`.

For a paged operation, the first request selects a snapshot and the cursor pins it. Subsequent
pages use the exact continuation at that snapshot. A caller cannot combine the cursor with a
constraint that selects a different snapshot; it must start a new enumeration.

This gives callers control without forcing synchronization or time travel on ordinary requests.
It also makes the cost of exactness honest: an ephemeral cache can lose an exact snapshot, in
which case EACL fails explicitly rather than returning a subtly newer authorization answer.

### 7. A Zed token is a validated wrapper around a Long `t`

Within one database, the semantic freshness value is Datomic's monotonic basis `t`, stored as a
Long for constant-time comparison. Mutation responses can use the exact committed `db-after`
basis, and read responses can use the selected DB's basis.

The public `:zed/token` remains versioned and opaque enough to validate its format and database
binding. The token does not need to encode schema contents, dependency maps, wall-clock time, or a
DB value. Schema generation and coordinator state are cache-proof fields, while `t` is the
cross-request freshness lower bound.

Malformed, cross-database, or future-format tokens fail validation. Tokens are not arithmetic
clocks: subtracting one second from `t` has no temporal meaning.

### 8. Optional checkpoints construct age-based lower bounds

Callers may want "at least as fresh as N seconds ago" without introducing another consistency
mode. An optional bounded ring records pairs of:

- monotonic process capture time; and
- an actual basis `t` observed during an EACL read or write.

The helper selects the oldest retained observation at or after `now - N`. If none exists, it
returns the current observed `t`, which is over-fresh but still satisfies the lower bound.
Checkpoint creation is lazy, rate-limited by a configurable interval, and bounded by entry count
and age. It retains neither Datomic DB values nor traversal state and requires no timer thread.

This quantization only helps construct freshness tokens and coalesce optional exact snapshots. It
does not drive live invalidation, where per-definition committed revisions are more precise.
Checkpointing is disabled by default and never calls `d/sync`.

### 9. Recursive state and completed answers share a store, with different portability

The recursive evaluator stores a bounded continuation containing the frontier, visited state,
stable ordering state, and enough canonical metadata to resume. Pending relationship-index scans
are represented by scalar scan descriptors and at most 64 already materialized internal EIDs.
Continuations retain neither a Datomic DB value nor a lazy Datomic sequence. A continuation hit
avoids replaying earlier pages.

If the continuation is unavailable but the cursor's complete schema/relationship proof still
matches, EACL safely replays the deterministic prefix on the current equivalent DB. This preserves
correctness when entries are rejected, evicted, routed to another local store, or caching is
disabled, at the expected latency cost. If the proof changed, EACL may return an already retained
exact page but must otherwise report snapshot-unavailable. It never restarts against changed
relationship state or automatically pays for `d/as-of`.

Process-local in-memory stores may support opaque continuation objects. Portable stores must reject
functions, lazy sequences, DB values, engine objects, or other process-local state. They may store
completed pages of internal EIDs, counts, Booleans, and exact metadata using a versioned
serialization contract.

Non-recursive lookups pass through the same completed-page cache before choosing their evaluator.
There is no need for a `lookup-resources-at` API or separate recursive cache. The recursive flag
remains an execution-planning fact only.

Continuation caching is enabled by the default bounded local store. Completed exact-result
retention is opt-in so default `can?` and acyclic lookup calls perform no result-cache keying,
lookup, or publication. Enabling live results implies exact retention.

### 10. `can?` and counts use the same correctness scope

`can?` results are safe to cache after both endpoints resolve. Both `true` and `false` use the same
complete dependency set and revision proof. Missing external IDs return at the boundary and are
not cached.

`count-resources` and `count-subjects` cache only the bounded numeric response and metadata. On a
miss, counting consumes the traversal with an eager reducing loop or equivalent cursor that does
not retain the head of a lazy sequence. This OOM property is required even when caching is
disabled, because a first request, rejected admission, or provider outage must remain safe.

### 11. Capacity is weighted and aware of entry class

The built-in store is bounded by total weight, entry count, maximum entry weight, and TTL. TTL is
checked on the requested key at lookup; physical cleanup of other keys is lazy and globally
bounded, avoiding an O(entry-count) expiry scan on every hot access. Weight approximates retained
keys and graph state, not only serialized byte count.

A single logical store does not imply one undifferentiated eviction pool. Admission can use
per-class budgets, protected shares, or a small frequency gate so high-cardinality permission
checks do not flush every expensive recursive continuation. The initial implementation should keep
the policy simple:

- strict global bounds;
- configurable per-kind maximum shares;
- reject entries above the per-entry limit;
- optional two-hit admission for high-cardinality `can?` keys; and
- LRU within admitted entries.

Metrics include hits, misses, admissions, rejections, evictions, expirations, provider errors,
entry weight, and counts by kind. These metrics do not participate in decisions about correctness.

### 12. Backends implement a small capability-aware protocol

EACL core provides a built-in local store and a protocol covering lookup, conditional admission,
targeted eviction, EACL-namespace clearing, capabilities, and metrics. A provider declares whether
it supports opaque local state, portable values, atomic compare/store behavior, and TTL.

RocksDB, Apache Kvrocks, Redis, or other adapters live in optional artifacts or consumer code so
their dependencies are not pulled into EACL core. Keys include a consumer-configurable namespace,
database identity, and cache format version. `clear!` is namespace-scoped; an adapter must never
require `FLUSHDB`.

Provider errors are misses for recomputable consistency modes. They become snapshot-unavailable for
cache-resident exact requests. Values are treated as trusted only after wrapper and shape
validation; a malicious provider is outside the library's threat model.

### 13. The connection-oriented API remains intact

Public authorization and mutation APIs continue to accept the connection-oriented EACL client.
That client owns the explicit cache context and preserves compatibility with other record
adapters. Existing lookup and count names accept consistency options; no `*-at`, recursive, or
non-recursive variants are added.

Internal evaluators continue to accept an immutable DB value plus explicit schema, cache, and
coordinator snapshots. With cache arguments absent, they are ordinary pure computations over the
supplied DB. This keeps tests and non-Datomic adapters decoupled from process-global state.

## Risks / Trade-offs

- **All live-result writers must share the coordinator.** Two independent clients writing the same
  relationship data cannot safely share live entries merely because they share a cache store.
  Incarnation namespacing forces misses rather than unsafe reuse. A client's default local
  coordinator is sufficient only for its own cursor proof; documentation and configuration
  validation must make the cross-client deployment rule prominent.
- **Exact snapshots are ephemeral.** Eviction, alternate-Peer routing, cache disablement, or a
  provider outage can make `at-exact-snapshot` unavailable. Returning an explicit error is safer
  and cheaper than hidden Datomic time travel.
- **Internal EIDs require stable identity.** EID reuse is not expected, but an exact page whose EID
  no longer resolves cannot be coerced from the current DB. That case fails closed as
  snapshot-unavailable.
- **Dependency completeness is critical.** Missing a relation definition could make a negative
  answer stale. Dependency closure is therefore compiled from schema and verified with property
  tests; it is not inferred only from branches visited by one execution.
- **Coordinators add a short critical section.** The barrier surrounds DB/revision capture, not
  cache access or traversal. Benchmarks must confirm no meaningful regression to the fast acyclic
  path.
- **Portable stores cannot accelerate opaque recursion by default.** They still improve completed
  pages, counts, and permission checks. On a missing continuation, proof-equivalent cursors replay
  correctly but more slowly. A backend-specific portable continuation format can be added later
  without weakening the core protocol.
- **Class-aware admission is more configuration.** Conservative defaults and global hard bounds
  are necessary so the cache cannot become a Peer-memory hazard.
- **Unstamped schemas do not cache.** This trades startup performance for an unambiguous generation
  boundary and deliberately avoids legacy heuristics.
- **Unsupported direct writes can stale a cache.** This is an explicit ownership boundary. Ghost
  diagnostics help find mistakes but do not make bypassed mutations coherent.

## Migration Plan

1. Extend the cache and coordinator protocols behind the existing disabled-by-default or opt-in
   cache configuration. Keep cache-disabled behavior as the reference implementation.
2. Introduce database identity, coordinator incarnation, uncertainty generation, committed
   per-definition `t` revisions, and versioned internal entry wrappers. Bump the cache format so
   prior experimental entries always miss.
3. Change schema lifecycle to one construction-time marker read plus generation rotation through
   `write-schema!`; disable result caching for unstamped clients.
4. Make relationship helpers publish committed `db-after` revisions while holding the mutation
   barrier. Add ambiguity rotation and no-op detection.
5. Add the consistency resolver and versioned `:zed/token` validation. Preserve
   `fully-consistent` as the default and do not add implicit `d/sync` or `d/as-of`.
6. Move boundary resolution/coercion outside canonical internal query construction. Add `can?`,
   lookup, and count entry kinds in the single store.
7. Replace recursive prefix replay with bounded continuations and proof-pinned cursors. Replay a
   missing continuation only while the proof is unchanged; otherwise return an already retained
   exact page or typed snapshot-unavailable.
8. Make uncached counts streaming and head-safe before enabling count caching.
9. Add weighted class-aware admission, optional observed-revision checkpoints, metrics, and
   provider capability checks.
10. Verify regular behavior through nREPL tests, then run cache-disabled equivalence, randomized
    model, mutation-race, schema-rotation, provider-fault, cursor-expiry, memory-retention, and
    backend contract tests.
11. Benchmark v7.3 against cold and warm v7.4 paths for `can?`, recursive and non-recursive
    lookups, counts, pagination, relevant writes, and unrelated application transactions. Treat a
    meaningful fast-path regression or retained-memory growth as release-blocking.
12. Document cache coherence boundaries and staged rollout. Consumers can enable a small local
    cache first, monitor metrics, then supply an optional backend if their routing model benefits.

Rollback is configuration-only: disable the cache and continue using the uncached evaluators.
Because the feature writes no Datomic cache schema or data, rollback requires no database
migration.

## Open Questions

No design-blocking questions remain. Optional portable continuation formats and distributed
relationship coordinators can be proposed independently after the local correctness contract and
benchmarks are established.

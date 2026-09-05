# EACL cache

EACL caching is a bounded, client-private optimization. The selected immutable
database value and the cache-free evaluator remain authoritative. Absence,
eviction, a rejected malformed publication/restore entry, an unavailable proof,
a storage error, or a disabled cache always falls back to independent
evaluation; cache availability cannot change an authorization result.

## Storage strategy

Reusable shared EACL stores use one small private adapter with runtime-specific
implementations:

- Clojure uses Caffeine 3.2.4's manual cache with `maximumSize` and Window
  TinyLFU admission plus frequency/recency eviction; and
- ClojureScript uses the pinned `com.github.theronic/cljs-cache` LRU fork.

Caffeine is not strict LRU and the runtimes need not evict the same cold key.
Caffeine ordinary reads are nonblocking. Its buffered maintenance can use
an internal eviction lock, and `maximumSize` is an eventual bound during
concurrent writes, so EACL does not claim the whole cache is lock-free. EACL
runs that maintenance on the calling thread rather than on the common
`ForkJoinPool`: retention is then a function of the access sequence, not of
scheduler timing, and constrained hosts need no pool thread to settle a
cache. EACL adds no access queue, frequency/recency sidecar, loader,
validator, proof acquisition, rendering, or request computation to cache
operations.

There is no single-flight owner. Concurrent misses compute independently and
race best-effort publication. Each request returns its own completed result
even when another publisher wins.

## Flat stores and complete keys

Exact and proof-managed reuse are modes in complete composite keys, not nested
cache backends or retained-generation registries. A v2 key includes its domain,
source scope and lifecycle, adapter and identity contracts, authorization ABI,
semantic operation identity, and either:

- one complete immutable basis identity for exact reuse; or
- one complete schema/dependency proof descriptor for managed reuse.

Full persistent-value equality is the collision authority on both runtimes.
EACL does not shorten correctness identity to an unverified digest.
Continuation keys likewise retain the complete version, backend, lineage,
adapter, identity-contract, operation, and canonical-query scope as persistent
key material rather than storing only a digest.

Completed-answer semantic identity includes the normalized query, operation,
evaluation and demand, stable result ordering, engine/compiler/value ABI,
adapter identity, recursive traversal limits, expression limits, permission
tree limits, and normalized aggregate limits. The aggregate limits are part of
identity because limits such as `:candidate-window` can change page boundaries
and flags, not merely execution cost.

For deterministic adapters whose identity contract promises immutable,
injective external IDs, `can?`, `count-resources`, `count-subjects`, and
permission-tree expansion construct a canonical public exact key before any
backend identity internalization. This fast path accepts only bounded canonical
public IDs. Other adapters and ID shapes retain the established internal-key
path; permission-tree completed caching is disabled when no safe public key is
available.

Resolution is deliberately small:

1. Look up the exact composite key.
2. For an ordinary current basis only, look up one proof-managed key.
3. Otherwise evaluate against the selected immutable value.
4. Publish the completed value under eligible keys.

Managed values flow forward only: their computed revision must be less than or
equal to the selected revision. Historical `:as-of` bases use identical exact
keys only. A speculative snapshot is not exact-cacheable; after proving its
effects disjoint, it may read a committed managed value through a complete
managed source identity, but it never publishes reusable state.

Validated publication and validated off-side restore are the only supported
ways to install answer or subproblem entries. Exact hits are therefore ordinary
membership reads plus the runtime library's access update; they do not repeat operation,
shape, or ABI validation. Managed hits add only the computed-revision check
above. Derived-schema artifacts are likewise validated once before direct cache
publication. Every live answer, subproblem, and derived-artifact publisher
requires an explicit callable validator; there is no implicit trusting
validator overload. Direct application mutation of EACL's private runtime
records or backing cache stores is unsupported. Unknown or nonresident cursor
tokens are authenticated normally. A resident process-private transport-page
key proves that its exact raw token and request were authenticated before
publication; configuring cursor expiry disables that tier so expiry remains a
current per-request decision.

## Store inventory

| Store | Contents | Retention |
| --- | --- | --- |
| Answer | Completed point, page, count, and permission-tree results | Standard cache |
| Denotation | Completed Boolean denotations | Standard cache |
| Exact transport page | Complete public page under an exact raw request | Standard cache; nonportable |
| Continuation | Validated latest traversal/checkpoint state | Standard cache |
| Stable-page checkpoint | Request-local or standalone resumable reducer state | Standard cache |
| Cursor codec/construction | Authenticated token and construction contexts | Independent standard caches |
| Derived schema | Parsed schema, dependency closures, roots, and sealed plans | Flat standard cache |
| Scan response | Exact adapter scan prefixes per read descriptor, scoped by the scanned relation's generation | Standard cache; `:scan-cache {:max-entries :max-prefix}`; nonportable |
| Range segments | Completed plain pages of one walk as contiguous result segments with one internal edge per result, scoped like managed answers (equal proof frame over the walk's relations, else exact basis); any window inside a segment is served, a window past a segment composes its tail with one continuation, adjacent pages merge | Standard cache; `:range-reuse {:max-entries :max-results-per-walk :max-segments-per-walk}`; nonportable |

The scan-response and range tiers are physical accelerators: a served scan
reply equals the adapter's reply for the same bound and limit, a derived or
composed page equals the page traversal would produce (both public orders
are deterministic functions of plan, snapshot, and boundary, so every
completed page is a slice of one fixed sequence), and results, order,
cursors, limits, deadlines, and errors are identical with both disabled. A
recursive plan's continuation past a retained segment resumes the stored
checkpoint at the segment's end edge: the segment remembers the page series
that produced it, and the continuation resumes that series' frontier
whatever page size it requests. Every request also
memoizes its own scan replies on its immutable basis; that memo is ordinary
execution state and is not switched by `:cache?`, which governs only the
shared store. `:cache? false` and a disabled client cache bypass both shared
tiers. Neither tier is exported by cache snapshots.

Request-local schema memos, evaluator worklists, and recursion sets remain
ordinary request state. Stable-page checkpoints are semantic execution state,
but their bounded multi-key retention still uses the standard cache adapter;
only an authenticated ordinal-and-boundary match records an ordinary access. Database-
engine caches and authoritative source/basis/generation state are not EACL
cache backends.

On the JVM, one cursor key-context value contains a nonblocking idle object
pool capped at eight initialized JCA `Mac` instances. It is not keyed result
retention: concurrent borrowers never share a mutable authenticator, a full
pool discards the returned burst instance, and the owning key context remains
bounded by its standard cache.

The stable reducer's `:sidecar` is linear traversal state, not reusable cache
storage. Each entry is the unread portion of one already-fetched physical
chunk, its index is consumed exactly once by that traversal/checkpoint, and
oldest-first release is bounded execution scheduling rather than result reuse.
Replacing that immutable CLJ/CLJS checkpoint state with a shared cache
would add synchronization to every released value and change the state-machine
representation. Likewise, the saturating 256-condition schema-warning set is
diagnostic output deduplication only: it never supplies an authorization value
or changes evaluation.

The generated-Java adapter has two capacity-one volatile conversion slots for
the most recent fuel integer and the most recent traversal-limits object.
Capacity one makes FIFO and LRU identical; a race merely replaces one pure,
equivalent conversion with another. These scalar marshaling shortcuts and the
request-counter thread-local binding-frame pointer are not multi-key cache
backends.

The former `PageNavigationCache`, relationship-observation cache, and unused
Datomic `CacheStore`/`LocalStore` provider contracts were deleted. In its place,
the answer lifecycle owns one ordinary exact-basis transport-page store for
`lookup-resources`, `lookup-subjects`, and `read-relationships`. Its complete
key binds the exact basis, complete normalized raw request (including the exact
`:after`/`:before` token), full authenticated consistency descriptor including
any exact token or freshness floor, operation, cursor-key policy,
render/order ABI, and adapter identity contract. Its value is the complete
immutable public page, including the cursor tokens already minted for that
authenticated request. It never retains a clock, deadline, cancellation
object, adapter, source, or request-owned object.

The tier is enabled only for the default non-expiring cursor policy. Exact raw
token equality against a process-private entry can therefore return before
cursor decode, per-item identity conversion, proof reconstruction, or token
construction without accepting an unknown token. A configured
`:cursor-ttl-seconds` bypasses the tier and uses the normal authenticated
semantic-answer path. An authenticated input cursor with an embedded expiry
also forbids transport publication even when the receiving client mints
non-expiring cursors. Rendered key inputs and custom object IDs inside retained
values must be recursively metadata-free portable data. Operation-typed
validation permits EACL's known immutable `SpiceObject` wrapper for lookup
items and `Relationship` wrappers composed from valid SpiceObjects for
relationship reads; custom records are rejected. Metadata-bearing identities
bypass this presentation tier and use the semantic/internal path. Any object ID
carried in a cursor query scope or authenticated/emitted edge must be a bounded
canonical scalar or ordinary vector rather than a list, map entry, subvector,
alternate integer representation, map, or set. Records and JavaScript negative
zero are rejected too. Ordinary request query maps, vectors, and sets are
recursively copied into plain persistent containers so the cache does not
retain caller-owned comparators or collection implementations. EACL returns
`:eacl.pagination/unsupported-cursor-identity` instead of signing transport
that erases a codec-significant representation. Oversized or foreign raw cursor
input bypasses exact transport lookup before cache-key hashing and is rejected
by the ordinary bounded decoder. There are no routes, boundary indexes,
opposite-direction aliases, access queues, or navigation state.

Physical operator chunks and direct Boolean probes, plus Relay identity
conversion, are deliberately not shared cache artifacts. Cross-backend
measurements found their cache bookkeeping slower and more allocating
for the common one- and 16-value cases; request-local evaluator memoization and
backend execution bounds remain authoritative. The retained authorization
values are exact denotations, exact/managed completed answers, and the exact
transport page described above.

## Capacity and page retention

Capacities are positive cross-runtime safe integer entry counts:

```clojure
{:cache
 {:max-entries 2048
  :denotation-max-entries 4096}}
```

`:max-entries` sizes completed-answer, exact rendered-page, continuation, and
cursor stores.
`:denotation-max-entries` independently sizes exact Boolean denotations. The store
uses 1,024 for every `:max-entries` consumer when that single public option is
omitted. It does not claim that an entry count or an old logical weight estimate is a byte
measurement. The removed nested `:subproblem-cache` map, projection-tier, and
managed-proof-retention options fail
closed instead of preserving an unused cache surface.

A completed page with at most 1,000 result items may be retained. A page with
1,001 through the public 10,000-item maximum is returned unchanged but is not
published. This is a retention rule, not a public page-size limit. Scalar,
count, and permission-tree results are unaffected.

Removed weight, retained-generation, recency, repeat-admission, publication-
attempt, provider-store, and relationship-observation options fail closed as
typed invalid configuration. `eacl.cache/no-cache` is the only client-level
cache sentinel.

## Per-request controls

```clojure
(eacl/check-permission
 acl
 {:subject user
  :permission :view
  :resource document
  :cache? false})
```

- `:cache? false` bypasses answer, exact-denotation, exact rendered-page, and
  continuation lookup and publication for the operation. Derived-schema and
  cursor-construction caches remain independent client infrastructure.
- `:populate-cache? false` keeps lookup and request-local memoization but
  suppresses completed-answer, exact-denotation, exact rendered-page, and
  continuation publication.

Both controls are excluded from semantic identity. Invalid, partial, or
unproved authorization answers, denotations, and continuation checkpoints are
never retained. Cancellation or deadline observed before insertion skips
publication. A successful validated absent-key insertion is the publication
linearization point: a signal racing after it may still suppress the current
response, but does not retract the safe immutable value. Fully constructed
derived-schema and cursor-codec/token artifacts remain independent
infrastructure because request cancellation cannot invalidate their closed
value, identity, authentication, or expiry contract. A local cache backend
exception is treated as a miss or failed best-effort publication, not as an
authorization failure.

## Lifecycle and clearing

The runtime installs one coherent cache lifecycle alongside a private source
incarnation. Request basis selection and cache capture must observe the same
incarnation.

- `clear-answer-cache!` performs a narrow rotation of authorization answer,
  exact-denotation, exact rendered-page, and continuation children. It preserves derived schema and
  cursor state and keeps the same source incarnation. Sticky managed-proof
  distrust and per-reason reporter deduplication also remain in force; only
  full expiry resets proof health.
- `expire-cache!` performs a full rotation with a fresh source incarnation and
  fresh cache children. Supply a coordinated new public source-lifecycle token
  when multiple processes exchange cursors or cache snapshots.
- restore constructs and validates fresh stores off-side, then atomically installs
  one complete new lifecycle.

An in-flight request may finish using its detached old stores but cannot dirty
the newly installed lifecycle. A retained immutable `Snapshot` remains
evaluable after rotation; if either its public lifecycle or private source
incarnation differs, it is retired from all reusable runtime children and
cannot repopulate them. A narrow clear deliberately preserves that ability.
Every retained-Snapshot read authenticates and asserts its own consistency
descriptor, then merges that read's exact token or freshness floor into the
retained selection while preserving the creation selection's backend facts.
This prevents two descriptors that happen to select the same basis from
sharing cursor/cache consistency authority.

`cache-content-revision` is a conservative process-local dirty hint for
authorization answer/denotation content. It advances on their mapping
publication or eviction and on explicit clear, expiry, or restore, so it never
misses a portable mapping change. It may also advance when a managed hit is
promoted to a process-local exact mapping that portable export deliberately
omits. It does not advance for continuation, cursor, or derived-schema
retention, lookup metrics, database writes, or a library access update. A host that
must suppress every redundant upload can compare the deterministic exported
snapshot after observing a revision change.

## Authenticated cache snapshots (v9)

Every backend exposes `export-authenticated-cache-snapshot` and
`restore-authenticated-cache-snapshot!` with `{:max-entries n}` bounds and an
optional lower `:maximum-size` byte ceiling (maximum 16 MiB). These APIs wrap
snapshot v2 in the primary keyring's authenticated `eacl_cache1_` envelope.
The envelope provides authenticity; hosts own storage confidentiality.

A successful import retains its verifying controller and key ID privately.
Unknown or retired keys produce a cache miss; malformed artifacts also miss.
Failed restore leaves existing caches intact. Imported entries and results
that consume them cannot be re-exported or published as local cache authority.
After retirement they recompute against the selected snapshot. Independently
computed answers remain cached. Key material never enters the serialized data.
See the [security-key guide](security-keyrings.md) for the full API and trust
contract. The decoded API below remains available for host-authenticated data.

## Portable cache snapshot v2

```clojure
(def bounds {:max-entries 5000})
(def snapshot
  (eacl.datahike.core/export-cache-snapshot acl bounds))

;; Only after authenticating and size-bounding the external envelope:
(eacl.datahike.core/restore-cache-snapshot! acl snapshot bounds)
```

Snapshot v2 exports a deterministic, flat sequence of complete semantic keys
and completed values. It excludes exact rendered-page values, library-private
Caffeine admission/eviction/maintenance state, CLJS LRU priority/tick state,
database values, continuations, cursor state, metrics, and process-local
tokens. A process-local exact entry created by promoting a managed hit is also
omitted; its portable managed mapping remains and can be promoted again after
restore. Canonical ordering does not apply the ordinary secure-token byte
ceiling to already admitted semantic keys; the external authenticated envelope
is the byte-bounded boundary. Statistics and lifecycle accounting do not
serialize resident keys. Snapshot v1 is rejected.

The restore API accepts trusted, already decoded immutable data. A host that
persists or receives bytes must authenticate the envelope and enforce an
encoded-byte bound before decoding. Restore then validates the closed snapshot
shape, entry count, composite keys, operation-specific value contracts,
computed revisions, managed-answer proof keys, and tier capacities. Validation and cache
construction happen off-side; any failure leaves the visible runtime unchanged.

Portable entries may be irrelevant to a receiving client's policy. Because
expression and aggregate limits are in semantic keys, a stricter client simply
misses an entry created under looser limits and executes its own contract.

## Continuations and cursors

Public cursors authenticate query, ordering, source lineage, selected basis,
proof context, and boundary. Opaque traversal state remains in a count-bounded
continuation store. A resident value is held after its ordinary access and then
validated once for context, plan identity, progress, and replay safety. Invalid
state is evicted. Optional expiry belongs to the authenticated cursor token and
is checked during cursor decoding, not stored as a continuation-entry TTL.
Cache eviction is a performance miss: EACL replays the authenticated prefix on
the already selected basis or returns the documented stale/basis-conflict
outcome; it never silently selects another basis.

Cursor age is independent of cache retention. Cursors do not expire by default;
`:cursor-ttl-seconds` is an explicit application policy.

## Coherence and recovery

Cache coherence assumes authorization mutations use EACL APIs or intact
EACL-produced transaction data. Schema changes, relationship changes,
permissioned identity/liveness changes, repair, cleanup, and secured entity
deletion are in that contract. Unrelated application datoms are unrestricted.

After an unsupported authorization mutation, database restore/reset, branch or
history replacement, or another operation that may reuse native revision
identity:

1. Quiesce affected authorization traffic in every process.
2. Repair the data through a supported path.
3. Fully expire or recreate every affected EACL client, coordinating the public
   lifecycle token where values are exchanged.
4. Resume traffic.

Cache expiry does not repair ghost relationships, and rewriting an unchanged
schema is not a cache flush.

## Observability

Backend `cache-stats` reports actual entry counts/capacities and behavioral
counters such as exact/managed hits, misses, publications, bypasses, proof
unavailability, retention-ineligible pages, storage errors, expirations, and
restores. Counts are diagnostics, never validity evidence. With
`{:cache {:telemetry? false}}`, answer, denotation, and continuation counters do
not mutate, including cumulative clear, expiry, and restore accounting across
lifecycle rotation; library access-policy recording and mandatory semantic
work counters remain active.
JVM hit/probe counters use contention-distributed `LongAdder` values rather
than a global metrics atom; a stats read during concurrent traffic is therefore
a weakly consistent diagnostic snapshot. CLJS keeps its single-threaded atom.
`cache-stats` also carries `:scan-cache` (hits, misses, deposits, extensions,
scope-unavailable, entry count) and `:range-reuse` (hits, misses, deposits,
supersessions, entry count) when those tiers exist.

Per-request I/O is observable through the `:io-observer` client option: a
function that receives, after every public read and every lookahead
operation, the operation, its provenance (`:request` or `:lookahead`), the
outcome, elapsed nanoseconds, and the request's exact mandatory meters
(commands, fetched values, identity conversions, scan memo and shared hits,
scan misses, range derivations). Without an observer the request path
performs one reference test. A client built with
`:lookahead {:pages n :max-inflight m}` runs a served page's continuation on
a bounded daemon pool after the response, so the caller's next page is an
exact hit; the option is off by default and a no-op on ClojureScript.

`refresh-metrics!` drops cached derived structural artifacts and clears their
metrics. With `{:eager? true}` it rereads the bounded permission schema to
repopulate those artifacts; it does not scan relationships or mutate the
backend. The reset is a point-in-time observation: concurrent requests may
publish newly derived artifacts and metrics immediately around it.

# EACL cache

EACL caching is a bounded, client-private optimization. The selected immutable
database value and the cache-free evaluator remain authoritative. Absence,
eviction, a rejected malformed publication/restore entry, an unavailable proof,
a storage error, or a disabled cache always falls back to independent
evaluation; cache availability cannot change an authorization result.

## Storage strategy

All reusable shared EACL stores use standard least-recently-used retention:

- Clojure uses `org.clojure/core.cache`;
- ClojureScript uses the pinned `com.github.theronic/cljs-cache` fork; and
- EACL's small CLJC adapter supplies local atoms, explicit absence, atomic LRU
  touches, absent-key publication, eviction, clearing, and portable iteration.

The atoms are expected to change on a hit because an LRU must record use.
EACL never substitutes FIFO: a frequently used old entry remains hot while the
least recently used entry is evicted. Cache transformations contain no loader,
validator, proof acquisition, or request computation, so an atom retry can
repeat only a pure library operation.

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
membership reads plus the standard LRU touch; they do not repeat operation,
shape, or ABI validation. Managed hits add only the computed-revision check
above. Derived-schema artifacts are likewise validated once before direct LRU
publication. Every live answer, subproblem, and derived-artifact publisher
requires an explicit callable validator; there is no implicit trusting
validator overload. Direct application mutation of EACL's private runtime
records or backing cache atoms is unsupported. Cursor authentication and any
configured cursor expiry remain request-dependent and are still checked on
every use.

## Store inventory

| Store | Contents | Retention |
| --- | --- | --- |
| Answer | Completed point, page, count, and permission-tree results | Standard LRU |
| Denotation | Completed Boolean denotations | Standard LRU |
| Continuation | Validated latest traversal/checkpoint state | Standard LRU |
| Stable-page checkpoint | Request-local or standalone resumable reducer state | Standard LRU |
| Cursor codec/construction | Authenticated token and construction contexts | Independent standard LRUs |
| Derived schema | Parsed schema, dependency closures, roots, and sealed plans | Flat standard LRU |

Request-local schema memos, evaluator worklists, and recursion sets remain
ordinary request state. Stable-page checkpoints are semantic execution state,
but their bounded multi-key retention still uses the standard LRU adapter;
only an authenticated ordinal-and-boundary match touches recency. Database-
engine caches and authoritative source/basis/generation state are not EACL
cache backends.

On the JVM, one cursor key-context value contains a nonblocking idle object
pool capped at eight initialized JCA `Mac` instances. It is not keyed result
retention: concurrent borrowers never share a mutable authenticator, a full
pool discards the returned burst instance, and the owning key context remains
bounded by its standard LRU.

The stable reducer's `:sidecar` is linear traversal state, not reusable cache
storage. Each entry is the unread portion of one already-fetched physical
chunk, its index is consumed exactly once by that traversal/checkpoint, and
oldest-first release is bounded execution scheduling rather than result reuse.
Replacing that immutable CLJ/CLJS checkpoint state with an atom-backed LRU
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

The former externalized `PageNavigationCache`, relationship-observation cache,
and unused Datomic `CacheStore`/`LocalStore` provider contracts were deleted.
Relay rebuilds public identifiers and signed cursors from a completed internal
page. A first unseen reverse page may recompute; an identical retry can hit the
ordinary completed-answer key.

Physical operator chunks and direct Boolean probes, plus Relay identity
conversion, are deliberately not shared cache artifacts. Cross-backend
measurements found their standard-LRU bookkeeping slower and more allocating
for the common one- and 16-value cases; request-local evaluator memoization and
backend execution bounds remain authoritative. The retained shared tiers are
therefore exact denotations and exact/managed completed answers only.

## Capacity and page retention

Capacities are positive cross-runtime safe integer entry counts:

```clojure
{:cache
 {:max-entries 2048
  :denotation-max-entries 4096}}
```

`:max-entries` sizes the completed-answer, continuation, and cursor LRUs.
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

- `:cache? false` bypasses answer, exact-denotation, and continuation lookup and
  publication for the operation. Derived-schema and cursor-construction LRUs
  remain independent client infrastructure.
- `:populate-cache? false` keeps lookup and request-local memoization but
  suppresses completed-answer, exact-denotation, and continuation
  publication.

Both controls are excluded from semantic identity. Invalid, timed-out,
cancelled, partial, or unproved authorization answers, denotations, and
continuation checkpoints are never retained: their publication boundary
rechecks the bound execution contract before touching an LRU. Fully constructed
derived-schema and cursor-codec/token artifacts remain independent infrastructure
because request cancellation cannot invalidate their closed value, identity,
authentication, or expiry contract. A local cache backend exception is treated
as a miss or failed best-effort publication, not as an authorization failure.

## Lifecycle and clearing

The runtime installs one coherent cache lifecycle alongside a private source
incarnation. Request basis selection and cache capture must observe the same
incarnation.

- `clear-answer-cache!` performs a narrow rotation of authorization answer,
  exact-denotation, and continuation children. It preserves derived schema and
  cursor state and keeps the same source incarnation. Sticky managed-proof
  distrust and per-reason reporter deduplication also remain in force; only
  full expiry resets proof health.
- `expire-cache!` performs a full rotation with a fresh source incarnation and
  fresh cache children. Supply a coordinated new public source-lifecycle token
  when multiple processes exchange cursors or cache snapshots.
- restore constructs and validates fresh LRUs off-side, then atomically installs
  one complete new lifecycle.

An in-flight request may finish using its detached old stores but cannot dirty
the newly installed lifecycle. A retained immutable `Snapshot` remains
evaluable after rotation; if either its public lifecycle or private source
incarnation differs, it is retired from all reusable runtime children and
cannot repopulate them. A narrow clear deliberately preserves that ability.

`cache-content-revision` is a conservative process-local dirty hint for
authorization answer/denotation content. It advances on their mapping
publication or eviction and on explicit clear, expiry, or restore, so it never
misses a portable mapping change. It may also advance when a managed hit is
promoted to a process-local exact mapping that portable export deliberately
omits. It does not advance for continuation, cursor, or derived-schema
retention, lookup metrics, database writes, or an LRU hit touch. A host that
must suppress every redundant upload can compare the deterministic exported
snapshot after observing a revision change.

## Portable cache snapshot v2

```clojure
(def bounds {:max-entries 5000})
(def snapshot
  (eacl.datahike.core/export-cache-snapshot acl bounds))

;; Only after authenticating and size-bounding the external envelope:
(eacl.datahike.core/restore-cache-snapshot! acl snapshot bounds)
```

Snapshot v2 exports a deterministic, flat sequence of complete keys and
completed values. It excludes library-private priority maps and recency ticks,
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
computed revisions, managed-answer proof keys, and tier capacities. Validation and LRU
construction happen off-side; any failure leaves the visible runtime unchanged.

Portable entries may be irrelevant to a receiving client's policy. Because
expression and aggregate limits are in semantic keys, a stricter client simply
misses an entry created under looser limits and executes its own contract.

## Continuations and cursors

Public cursors authenticate query, ordering, source lineage, selected basis,
proof context, and boundary. Opaque traversal state remains in a count-bounded
continuation LRU. A resident value is held after its LRU touch and then
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
lifecycle rotation; standard LRU recency and mandatory semantic work counters
remain active.

`refresh-metrics!` drops cached derived structural artifacts and clears their
metrics. With `{:eager? true}` it rereads the bounded permission schema to
repopulate those artifacts; it does not scan relationships or mutate the
backend. The reset is a point-in-time observation: concurrent requests may
publish newly derived artifacts and metrics immediately around it.

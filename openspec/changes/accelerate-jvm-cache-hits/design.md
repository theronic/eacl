## Context

See [proposal.md](proposal.md) for motivation. Measurement isolated the current
uncontended JVM store from the public request path:

- raw `standard-lru` hit: about 0.008 ms in the instrumented Datomic fixture;
- exact completed-answer resolve: about 0.012 ms;
- local 64-item page externalization: about 0.10 ms;
- deployed Datomic/DynamoDB fixed-basis baseline: about 2 ms;
- deployed 64-item completed hit: about 8 ms.

The deployed trace performs one answer-store hit and no miss or publication,
but then calls Datomic `internal-id->object` once per item and constructs Relay
cursor proof/transport again. The fixed demo additionally issues an encrypted
exact token and selects/releases the same EACL Snapshot per request. These are
the dominant costs. The immutable `core.cache` atom/CAS store is a separate
contention problem: Caffeine is roughly an order of magnitude faster per raw
hit and about 44 times higher throughput in the eight-thread representative
stress run, but saves microseconds rather than the observed milliseconds.

The first pre-token presentation prototype made typical hits fast but still
allocated about 331 KB for a first page and 429 KB for a continued page. Two
deep equal-but-nonidentical cursor-map traversals per edge dominated that work
and produced an isolated 6.68 ms GC tail. Retaining the complete authenticated
transport page for the default non-expiring policy removes both cursor
reconstruction and repeat decode from the resident path.

The existing cache lifecycle, exact basis identity, independent miss ownership,
validated publication, 1,000-item admission ceiling, and cache-disabled
differential behavior remain constraints. CLJS/DataScript remains supported
through the portable EACL boundary and does not need the JVM data structure.

## Goals / Non-Goals

**Goals:**

- Make a resident exact page hit reuse public items before subject/resource
  internalization, proof forcing, or per-item identity reads.
- Make resident exact point, count, and permission-tree hits use canonical
  public semantic keys before subject/resource internalization when the
  adapter promises deterministic immutable/injective external identity.
- Let an exact process-private raw-request hit reuse the complete already
  authenticated transport page when cursor expiry is disabled; unknown,
  nonresident, rotated-policy, and TTL-bearing tokens keep using the ordinary
  authenticated path.
- Remove the JVM global immutable-cache CAS point while preserving independent
  miss computation and atomic no-overwrite publication.
- Make the Datomic fixed demo borrow one retained immutable Snapshot for normal
  requests and construct a request Snapshot only for historical selection.
- Enforce the sub-millisecond fully realized Core target and verify the deployed
  service boundary separately.

**Non-Goals:**

- Restoring the former page route, boundary, reverse-navigation alias, access
  queue, or digest state machine.
- Treating eviction order as authorization semantics or requiring JVM and CLJS
  to choose the same cold victim.
- Exporting process-private transport pages or treating a raw token as trusted
  unless that exact request was previously authenticated and published in the
  current exact lifecycle and cursor-key policy.
- Coalescing concurrent misses or executing application callbacks inside cache
  atomic operations.
- Solving retained-byte accounting beyond the existing entry bound and
  1,000-item page admission ceiling.

## Decisions

### 1. Optimize the measured boundary, not only the map lookup

The benchmark records raw store, exact resolve, basis acquisition, public-key
construction, public-item reuse, cursor context/transport, response assembly,
and the full public Core call separately. The production path contains no
stage timer. A library result qualifies only when the complete resident Core
operation passes the absolute target.

Alternative: replace `core.cache` and infer that the request improved. Rejected
because the raw store is already tens of microseconds or less and cannot explain
the deployed regression.

### 2. Add one exact transport-page store inside the existing cache lifecycle

For the default non-expiring cursor policy, lookup-resource, lookup-subject,
and relationship-read requests construct one flat key from the complete exact
basis, complete normalized raw request (including the exact
`:after`/`:before` token), the full authenticated consistency descriptor
including any exact token or freshness floor, cursor-key policy digest,
execution demand and limits, engine/order ABI, adapter fingerprint, and
identity contract. The lookup happens immediately after immutable basis
selection and before cursor decode, anchor internalization, schema/proof
construction, or evaluation. Its value is the complete immutable public page,
including cursor tokens minted after successful evaluation and authentication.

This is safe without reauthenticating a resident raw token: an unknown or
modified token cannot equal a key that EACL previously published; the exact
basis and every result-affecting query field are also in that key; and changing
the current key, retained decode keyring, bounds, or wire version changes the
cursor-policy identity. Configuring `:cursor-ttl-seconds` disables this tier
because wall-clock expiry is request-dependent. TTL-bearing requests continue
through the ordinary authenticated semantic-answer path. The authenticated
input token is authoritative too: a cursor carrying `expires-at` is never
published as a transport request by a non-TTL receiver, so it cannot become a
pre-decode hit after expiry.

The transport store uses the same private cache adapter, answer capacity, and
atomic lifecycle rotation, but is excluded from portable answer snapshots. Its
value contains no adapter, source, request contract, clock, deadline,
cancellation object, route, or navigation alias. Page operations do not publish
a duplicate internal exact answer when this tier is eligible; managed internal
answers remain available for dependency-valid reuse across bases. A managed hit
is rendered once against the selected basis and then published as that basis's
exact transport entry.

Rendered key inputs and custom object IDs inside the retained value must be
metadata-free portable data; operation-typed validation permits EACL's known
immutable `SpiceObject` wrapper for lookup items and `Relationship` wrappers
composed from `SpiceObject` values for relationship reads. Custom records are
rejected. Clojure metadata does not participate in value equality or canonical
cursor transport and can hide request-owned mutable objects, so a
metadata-bearing custom identity bypasses this exact presentation tier.
Cursor query scopes and emitted edges have the stronger portable-transport
boundary: EACL accepts only the representation canonical transport preserves.
It rejects records, non-vector sequentials, alternate integer/collection
representations, and signed zero instead of signing bytes that erase identity
information used by a deterministic custom codec. Map and set object IDs are
rejected outright because comparator and collection provenance are not
portable. Canonical scalar/vector IDs are depth, entry-count, and
character-count bounded before they can enter a hot key. Ordinary request
query maps, vectors, and sets are allowed only after recursive copying into
plain persistent containers, so caller comparators or collection
implementations are not retained.
The cursor token and construction-context stores retain canonical metadata-free
copies on misses, so their bounded keys and values cannot keep request-owned
metadata alive or alias later metadata variants.

Before the raw request becomes a transport key, each supplied boundary must be
a bounded string with the current cursor prefix. Oversized or obviously foreign
input skips exact lookup and reaches the ordinary decoder's typed rejection, so
unauthenticated attacker bytes are never hashed into a Caffeine key.

An exact transport hit returns the held public page directly. It performs no
token decode/encryption, permission dependency resolution, proof stamp work,
input/output identity conversion, or page reconstruction.

The same public-key rule gives `can?`, `count-resources`, `count-subjects`, and
permission-tree expansion a smaller exact semantic path. It applies only to a
deterministic adapter with the immutable/injective external-identity contract
and a canonical bounded public object ID. Other adapters and ID shapes keep the
ordinary internalized semantic path; permission-tree caching is disabled when
that safe public identity is unavailable.

Alternative: restore the deleted `PageNavigationCache`. Rejected because it
mixed rendered values with route aliases, opposite-direction boundaries, and
custom eviction metadata. The needed optimization is one opaque exact key to
one immutable transport value.

Alternative: cache individual ID conversions. Rejected as a second identity
cache with its own invalidation rules; exact transport pages avoid all item
lookups with less state.

### 3. Keep proof and schema state lazy until a semantic or cursor miss needs it

`cursor-options` carries the request's existing proof-frame delay rather than
forcing it. Exact transport and completed hits do not build schema state or a
proof frame. Miss evaluation, managed-key construction, cursor recovery, and a
cursor-context miss explicitly force the same request-owned delay.

Validated publication has one precise linearization point: the successful
absent-key insertion. Cancellation or deadline observed before insertion skips
publication. A signal racing after insertion may still suppress the current
response, but it does not retract an already validated immutable value whose
correctness is independent of that request signal.

Alternative: eagerly construct proof objects because their reads are lazy.
Rejected because this obscures the hit boundary and has already allowed cursor
dependency work to move ahead of cache lookup.

### 4. Use Caffeine 3.2.4 for JVM storage and `cljs-cache` for CLJS

The public `eacl.cache.standard-lru` namespace becomes a runtime adapter rather
than a promise of an identical algorithm. JVM stores use a manual Caffeine
cache with `maximumSize`; CLJS retains the forked standard LRU. EACL uses:

- `getIfPresent` for a resident read and policy access;
- `Policy.getIfPresentQuietly` for eligibility/compare paths;
- `ConcurrentMap.putIfAbsent` for already computed publication;
- conditional `replace` for identity-checked touch/replacement; and
- invalidation/map-copy operations for lifecycle and snapshots.

Every JVM value is held in a private wrapper so `nil`, `false`, and value
identity remain distinct from absence. Loading-cache APIs, `get(key, loader)`,
and `computeIfAbsent` are forbidden because they can serialize same-key miss
computation. Statistics recording and explicit cleanup are not on the hit path.
EACL's JVM hit telemetry uses contention-distributed `LongAdder` counters;
optional observation therefore does not put a global immutable metrics CAS
back around Caffeine reads. CLJS remains single-threaded and retains its simple
metrics atom.

Caffeine's W-TinyLFU policy deliberately combines frequency and recency. It is
not strict LRU and is not wholly lock-free: ordinary reads use a concurrent map
and buffered policy recording, while batched maintenance has an eviction lock.
This removes EACL's global atom/CAS bottleneck and better retains a 100-times-hot
key through a cold scan. `maximumSize` is eventually maintained, so settled
stats/tests call cleanup; authorization never depends on the exact victim or an
instantaneous size observation.

Alternative: retain `core.cache`. Rejected because every hit allocates a new
immutable policy value and competes on one atom, producing the measured
contention collapse even though uncontended latency is not the millisecond root
cause.

### 5. Borrow the Datomic demo's fixed Snapshot

The fixed-environment reader retains its initialization EACL Snapshot until the
reader closes. Normal consistency modes borrow that immutable value with a
no-op per-request release; historical-date requests still select and release a
dedicated historical Snapshot. Each retained-Snapshot read authenticates and
asserts its own consistency descriptor, then refines the retained selection
with that read's exact token or freshness floor while preserving the creation
selection's backend facts. Datomic declares snapshot thread ownership `:any`,
and EACL Snapshot operations construct request-local contexts, so the fixed
Snapshot is safe under the boundary's admitted concurrency.

Alternative: issue a fresh encrypted exact token and select the same basis on
every request. Rejected because it adds about 2 ms while providing no fresher
or different data in a deliberately fixed environment.

### 6. Raise the executable JVM floor, not generated-kernel bytecode policy

Caffeine 3.x requires Java 11. EACL documents Java 17 as the minimum production
runtime for JVM modules. The generated kernel may retain its independent custom
bytecode-target option, but a complete JVM EACL application cannot claim Java 8
compatibility while depending on Caffeine 3.x.

## Risks / Trade-offs

- [Transport entries duplicate managed semantic data] → Use one presentation
  store with the existing answer capacity and remove duplicate internal exact
  page publication once the differential suite proves it is unnecessary.
- [A retained public ID could be wrong on another basis] → Transport keys always
  contain the complete exact basis. Only internal semantic answers cross bases
  when their managed proof permits it.
- [A cached page could carry an expired cursor] → Disable complete transport
  retention whenever `:cursor-ttl-seconds` is configured; TTL-bearing requests
  always authenticate and apply the current clock.
- [Two consistency requests select the same basis but impose different cursor
  bounds] → Include the full authenticated descriptor, not only its mode, in
  the exact transport key and refine retained-Snapshot selection per read.
- [A removed decode key could remain implicitly trusted] → Include the complete
  cursor-key policy digest in the exact key; rotation/removal produces a miss.
- [Caffeine may temporarily exceed `maximumSize`] → Treat capacity as a cache
  retention target, invoke cleanup only for settled observation/export, and
  keep authorization independent of retention.
- [Caffeine snapshot iteration is weakly consistent] → Export complete,
  self-validating entry hints, sort them canonically, and accept a safe subset
  under concurrent mutation; restore validates every entry before publication.
- [Retaining a demo Snapshot increases lifetime] → The demo is explicitly a
  fixed immutable environment; release it exactly once before releasing the
  connection and retain request-scoped snapshots for historical reads.

## Migration Plan

1. Land stage benchmarks and zero-backend-work assertions around resident hits.
2. Replace the JVM store behind the existing private adapter and run its
   semantic/concurrency suite on JVM and CLJS.
3. Add the exact transport-page entry and move its lookup ahead of cursor decode
   and anchor internalization; retain the internal managed miss path.
4. Make proof state lazy and return the held complete transport on a hit.
5. Update the fixed Datomic demo Snapshot lifetime and boundary tests.
6. Run differential, pagination/cursor-expiry, snapshot, contention, and
   sub-millisecond benchmarks; then deploy all canonical demos and measure live
   hit/bypass pairs.

Rollback is one commit per layer: the transport-entry path can be disabled while
leaving Caffeine, and the demo can return to request-scoped Snapshot selection.
Neither rollback changes public data or stored database state.

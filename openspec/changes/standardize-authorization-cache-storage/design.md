## Context

See `proposal.md` for motivation. The implementation currently combines two
different concerns:

- semantic eligibility: exact selected-basis equality, or managed proof
  equality plus causal/lifecycle validation; and
- retention policy: generation containers, weighted LRU state, admission
  sightings, touch queues, compaction, publication retries, and snapshots of
  that policy state.

All shared reusable values are immutable and recomputable. Current misses are
already request-owned; there is no correctness need for a loader or
single-flight owner. Exact basis identity is already complete, and managed
lookups already construct a bounded proof descriptor. This permits storage to
be reduced to ordinary key membership.

The change spans CLJ and CLJS, public cache snapshot/configuration ABIs, Relay,
continuations, derived-schema registries, generated/refined formal authority,
and three published modules. Datahike also brings an upstream library that
uses the same `cljs.cache` namespace, so the duplicate provider must be
excluded.

## Goals / Non-Goals

**Goals:**

- Make one tiny storage adapter the only implementation of shared in-process
  bounded keyed retention.
- Keep cached values immutable while allowing each runtime library to update
  only private retention metadata on a hit; keep computation, cancellation,
  deadlines, proof validation, rendering, and cursor transport outside cache
  mutation callbacks.
- Preserve exact and managed reuse, historical exact reuse, cache-free
  equivalence, lifecycle isolation, continuation replay, and atomic restore.
- Remove policy-specific formal state while retaining proofs over every
  authorization-relevant cache boundary.
- Decline retention for completed pages above 1,000 result items without
  claiming that item count is retained bytes or changing public page limits.
- Qualify the chosen policy with deterministic CLJ/CLJS traces and real EACL
  workloads before deleting the old implementation.

**Non-Goals:**

- A public 1,000-item page-size limit. This change only declines completed-page
  retention above that result count; the current public maximum remains 10,000.
- A byte-, heap-, or TTL-based capacity policy. JVM admission and eviction use
  Caffeine's W-TinyLFU policy; this change does not expose policy selection.
- Remote, externally writable, distributed, or consumer-supplied cache stores.
- Application access to or mutation of private runtime records, cache values, or
  their backing stores. Supported installation paths are validated publication
  and atomic validated restore only.
- Single-flight computation, cache-owned concurrency, or service admission.
- Backend storage caches such as Datahike node caches, request-local evaluator
  memos/worklists, consistency state that is authoritative rather than
  recomputable, or metric counters.
- Snapshot v1 migration. Old snapshots fail typed validation and clients start
  with an empty new lifecycle.

## Decisions

### 1. Treat storage as a non-authoritative partial map

The semantic model is:

```text
Cache : CompositeKey ⇀ CompletedValue
```

Supported publication and restore are the only transitions that add mappings;
both validate the complete key/value contract before insertion. Every live
publisher requires an explicit callable artifact validator; no publisher has a
trusting default or overload. Therefore
`lookup(k)` for an exact key is ordinary explicit membership and returns the
already validated immutable value without repeating operation, shape, or ABI
validation. A managed lookup additionally checks its immutable computation
revision against the request's selected revision. Absence, causal rejection,
arbitrary eviction, a failed lookup, or a lost publication race takes the same
cache-independent path. A reader holds the immutable value it found, so later
eviction cannot affect that request.

This is the correct abstraction because retention only controls future work.
Proofs cover key separation, value completeness/validity, hit equivalence,
managed causal eligibility, cache bypass, and lifecycle detachment. They do not
cover eviction priority, library-private policy representation, or concurrent
maintenance interleavings. Host adapter tests cover capacity, hot-key behavior,
value identity, and the common storage contract without requiring identical
eviction victims across runtimes.

Every uncached evaluation installs an explicit empty authorization-cache
context (`store=nil`, `key-constructor=nil`, `populate?=false`) before invoking
engine code. This is required even when the caller already chose bypass:
Clojure dynamic bindings propagate through synchronous application callbacks,
and a callback may re-enter EACL while an outer cached evaluation is active.
A nested cache-enabled request instead installs its own selected lifecycle
store, complete exact-denotation key constructor, and publication control.

Alternatives considered:

- Keep generated admission/lookup actions: rejected because those actions
  encode retention mechanics as if they authorize a result.
- Prove eviction policy itself: rejected because replacing one bounded policy
  with arbitrary eviction may change hit rate but cannot change authorization
  semantics.
- Leave formal cache models untouched: rejected because they would continue
  certifying deleted generation and weight machinery rather than production.

### 2. Use standard runtime caches without an EACL policy sidecar or loader

CLJ uses Caffeine 3.2.4's manual `Cache` with `maximumSize`. Ordinary reads use
`getIfPresent`; quiet peeks use `policy().getIfPresentQuietly`; publication and
conditional replacement use the concurrent map view. Every stored payload is
boxed so absence remains distinct from valid `nil` and `false` values. EACL
does not enable loading, `computeIfAbsent`, single-flight, `recordStats`, or a
custom executor.

CLJS uses the pinned `com.github.theronic/cljs-cache` LRU fork behind the same
small EACL adapter. It distinguishes membership from valid values, records an
LRU hit, publishes only absent keys, supports conditional replacement and
eviction, and exposes portable entry enumeration. Caffeine's weakly consistent
map view and CLJS sequence order are not semantic ordering contracts.

EACL validates capacity as a positive cross-runtime safe integer. Composite
keys use ordinary persistent hash/equality values and no custom comparator.
Restore inserts validated mappings sequentially; it never depends on seed
trimming, a particular cold victim, or library-private priority state.

Caffeine uses Window TinyLFU admission and frequency/recency eviction, while
CLJS uses LRU. Both retain repeatedly used keys ahead of FIFO-style arrival
order under representative churn, but exact victims may differ. Caffeine
`maximumSize` is an eventual bound during concurrent maintenance. Ordinary
Caffeine reads are nonblocking, while buffered policy maintenance may acquire
an internal eviction lock; the design therefore does not claim that the whole
cache is lock-free. Those details affect hit rate and transient occupancy only,
not authorization semantics.

Exact-denotation, completed-answer, exact rendered-page, continuation,
cursor-codec, and derived-schema stores remain separate cache instances where
their lifetimes and workloads are separate. They share one adapter and no EACL
frequency/recency implementation. Exact and managed completed answers share
one store and differ only by key; denotations and rendered pages are exact-only.
On the JVM, a cached cursor key context may own a fixed-capacity nonblocking
pool of eight initialized JCA `Mac` objects. That is an object pool rather than
keyed result retention: excess concurrent borrowers are transient and a full
pool discards returns.

Physical operator chunks, direct Boolean leaves, the unused engine direct-match
wrapper, and Relay identity conversions are not retained. Cross-backend paired
measurements showed that caching one- and 16-value physical vectors increased
allocation and commonly latency, while the full-vector win depended on avoiding
backend work that normal demand-sized requests do not repeat. Removing the
projection tier is smaller and avoids imposing that overhead on every adapter;
request-local memoization and engine work bounds remain unchanged.

### 3. Select dependencies and prevent duplicate CLJS namespaces

Caffeine 3.2.4 is added explicitly to the root and isolated `eacl` dependency
bases and to published module metadata. Its Java 11 minimum becomes the
complete JVM module's minimum; Java 17 remains EACL's supported production
runtime floor.

Source builds pin the tested fork commit:

```clojure
com.github.theronic/cljs-cache
{:git/url "https://github.com/theronic/cljs-cache.git"
 :git/sha "4143cc036446a47f0c6dfd9f8dde90363835051c"}
```

Datahike dependencies exclude `com.github.pkpkpk/cljs-cache` in the root,
isolated Datahike module, and published POM configuration. Otherwise two
artifacts provide the same `cljs.cache` namespace and classpath order silently
chooses one. Dependency checks accept the generated exclusion shape and verify
that source/demo graphs resolve one `cljs.cache` provider.

The tested Git SHA is the supported CLJS/DataScript demo dependency for this
change.

Alternative: retain the upstream CLJS artifact. Rejected because Datahike pins
an older unmaintained release, while the fork has current dependency metadata,
optimized-test coverage, and a place to repair CLJS LRU defects found by
EACL's adapter suite.

### 4. Put exact/managed eligibility in complete composite keys

The generic private key ABI is a canonical immutable value with this logical
shape:

```text
[:eacl.cache/key-v2
 storage-domain
 complete-domain-identity]
```

Continuation, cursor-codec, and derived-schema domains define only their own
complete identity. Continuation identity is the full persistent vector of
version, backend, source lineage, adapter fingerprint, identity contract,
operation, and canonical query; it is not replaced by a digest. For answer and
subproblem domains,
`complete-domain-identity` has this shape:

```text
[artifact-tier
 reuse-mode
 source-and-lifecycle-identity
 engine/schema/adapter/value-ABI identity
 operation semantic identity
 reuse-identity]
```

For `:exact`, `reuse-identity` contains the complete canonical selected-basis
identity, including branch/view/locator and adapter identity contract. For
`:managed`, it contains schema generation and the complete canonically ordered
dependency proof. The key commits to the same unreplaced history and the
managed value retains its immutable computation revision, so lookup rejects a
candidate computed after the request's selected revision. Exact lookup remains
first. After an exact miss, managed-key construction may issue one bounded
certified proof-frame command over the canonical dependency set already
discovered by ordinary planning; it cannot initiate dependency discovery or a
relationship-projection scan solely for reuse. The cache storage path remains
local and I/O-free.

Keys use full canonical equality, not a compact digest alone. A digest may be
included as an accelerator only when the collision-checking canonical identity
is also compared. Keys and values are immutable before publication.

An authenticated exact historical request may use an exact key if the adapter
supports that exact identity. Arbitrary native database values outside the
public exact-snapshot contract bypass completed-answer caching. Managed reuse
remains ordinary-current and forward-only.

Alternative: keep exact and managed generation maps above a standard cache.
Rejected because the generation map is itself another eviction/cache backend,
multiplies capacities, and is unnecessary once basis/proof is key material.

### 5. Keep computation outside cache operations

Each tier is one library-backed store in the captured lifecycle. The algorithm
is:

```text
lookup:
  exact: get the boxed resident value and record ordinary library access
  managed: peek without recording access, compare the held computation revision
    with the request's selected revision, and reject a value from the future;
    on acceptance, use a callback-free identity-conditional access update
  return the accepted held value directly

publish completed value:
  validate the complete key, artifact shape/ABI, operation value, and page guard
  perform one atomic absent-key insertion of the boxed completed value
```

Key construction, page-result eligibility, publication-value validation, and
semantic computation happen before publication mutation. Restore performs the
same complete key/value validation off-side before atomic lifecycle install.
The generic answer/subproblem and derived-artifact publishers require their
operation validator explicitly, so a caller cannot accidentally create a new
trusted ingress by omitting it. Exact hits perform no repeated operation,
shape, or ABI validation. Managed eligibility runs outside mutation callbacks;
an ineligible future candidate does not deliberately receive an access update.
No cache operation executes I/O, validation, rendering, proof acquisition, or
an application callback. A lookup returns its held immutable value even if a
later transition evicts the mapping. A miss does not adopt a concurrent
publisher's object and is never relabeled as a hit. Same-key publication keeps
the existing valid mapping while every requester returns its own completed
value.

Ordinary resolution has one authority for each identity dimension: operation is
read from the normalized semantic key, and revision, source lineage, adapter
identity, exact locator, and public cache-basis metadata are derived from the
complete exact-basis key. There is no separate `resolve-exact!` path or duplicate
ordinary resolver option to disagree with those values. Speculative managed-only
resolution retains an explicit selected revision and managed source because it
has no admissible exact basis.

The public cache configuration is likewise flat: `:max-entries` sizes completed
answers (and the adjacent continuation/cursor stores),
`:denotation-max-entries` sizes exact Boolean denotations, and one outer
`:telemetry?` switch applies to answer, denotation, and continuation diagnostics.
Library access-policy recording remains active when observation is disabled. The former nested
`:subproblem-cache` map, answer-capacity override, nested telemetry override,
and answer-only mode are rejected migration inputs.

The continuation store has one narrow replacement requirement rather than an
absent-key publication: retained progress for a key must not regress. It first
peeks without recording ordinary access and compares immutable checkpoints in
the continuation semantic layer, then offers a callback-free expected-value
replacement. If the expected value changed, the continuation layer repeats its
outside-store comparison. Thus a concurrent older checkpoint cannot overwrite
newer progress, and neither the progress comparator nor replay work runs inside
a cache mutation callback. Only an actual retrieval or successful publication
or replacement records access; stale, losing, or failed publication is not
cache use and does not refresh the key.

This valid-store induction depends on the private ownership boundary. Direct
application mutation of a cache record, library value, or backing store is not
a supported transition and is outside the correctness contract. Cursor
authentication and configured cursor expiry remain request-dependent and are
checked during decoding on every use; managed answers retain only their causal
revision check on every use. The non-exported derived-schema store likewise
holds the validated artifact directly and has explicit-validator publication
as its sole live ingress.

This uses ordinary manual-cache operations rather than a configurable
publication retry state machine. Cache contention is a performance concern and
is measured, but it is not request computation coordination.

Authorization answer/denotation and stable-page checkpoint lookup check the
bound execution contract before any cache read, access update, eligibility predicate, or
metric. An exact miss rechecks before managed-proof acquisition, and a managed
miss rechecks before cache-bound evaluation. Their publication paths likewise
recheck immediately before retention. An observed deadline or cancellation
therefore skips cache access, validation, and cache mutation; a nil contract
preserves the standalone checkpoint API. These checks are deliberately not
threaded into derived-schema or cursor-codec/token retention.
Those stores hold fully constructed request-independent infrastructure artifacts:
cancellation cannot invalidate a parsed schema/plan or an authenticated codec
artifact, and unknown cursor authentication plus configured expiry is checked on every use. They
remain usable even when per-request authorization caching is disabled. The
process-private exact transport tier is the narrow exception: with expiry
disabled, equality with a previously authenticated complete raw-request key may
return the held page before repeat decode. Unknown/nonresident tokens still
authenticate normally, and configuring expiry disables that tier.

The pre-access availability check is the cache-read boundary. If cancellation
races after it, the runtime library may already have recorded access; a second
check could not roll that state back. The enclosing execution
boundary checks again before returning an authorization result, so the raced
cancellation is still observed. This gives the strongest factual guarantee
without adding a misleading non-atomic post-check to every hot hit.

### 6. Guard only large completed pages, not arbitrary values

The common answer-publication boundary checks the already materialized
`:data` vector for page-shaped completed answers. More than `1000` result items
produces a distinct retention-ineligible outcome for both exact and managed
publication; the page is still returned unchanged. Scalar checks, counts,
trees, denotations, continuations, and derived artifacts do not inherit this
page rule. Physical projections remain uncached request work under their
existing authoritative chunk, proof, traversal,
continuation, and checkpoint bounds remain where they are.

The adapter never recursively estimates a value and never calls item count
"bytes". The guard is absent from semantic keys because changing retention may
turn a future hit into a miss but cannot change a value. The current public page
default/maximum remain 1,000/10,000; this change does not yet adopt the future
SpiceDB-style public maximum.

Alternative: guard all collection-bearing artifacts at 1,000. Rejected because
that silently changes large-denotation and continuation reuse beyond the user's
page-size concern and duplicates their existing semantic bounds.

### 7. Replace whole lifecycles instead of clearing nested state

A client owns an atom pointing to one runtime lifecycle record that pairs the
source-incarnation identity with fresh authorization tiers, continuation store,
cursor-codec stores, derived-schema store, and metrics. Snapshot selection and
cache capture MUST observe the same outer record, or validate and retry a
source/lifecycle mismatch before lookup. Expiry or successful restore builds a
complete lifecycle off-side and performs one outer atomic replacement. Old
requests may read held values and publish only to old tier stores, which new
requests cannot reach.

`clear-answer-cache!` is narrower: it atomically replaces only the
authorization answer, exact-denotation, exact rendered-page, and continuation
child lifecycle and content revision while retaining
unrelated cursor-codec and derived-schema children. It also shares the
source-lifecycle proof-health and reported-reason atoms, so managed lifting
remains disabled and each reason remains report-once across a narrow clear,
including a violation reported late by a detached in-flight request. Full
expiry and restore install fresh proof health and other non-exported children
so late publication cannot cross either boundary.

The process-local content revision is a conservative answer/denotation dirty
hint, not a digest of portable export. It advances for every successful mapping
publication or eviction, including a managed-to-exact promotion whose
process-local exact alias is omitted from export. This keeps storage unaware of
snapshot-origin policy while guaranteeing that no portable mapping change is
missed; hosts needing exact upload deduplication compare deterministic exports.

Continuation entries carry no TTL. Optional expiry belongs to the authenticated
cursor token and is enforced during cursor decoding before continuation reuse.
A resident continuation is held after the standard hit and checked for context,
plan identity, progress, and replay safety; invalid state is evicted and treated
as a miss. Count-bounded churn and lifecycle rotation remove old entries without
tombstones or expiry queues.

Alternative: clear each tier in place. Rejected because an in-flight request
can then repopulate the cleared tier and cross the intended lifecycle boundary.

### 8. Replace page navigation with one exact transport value

`PageNavigationCache` mixes a rendered representation with page/route/boundary
maps, opposite-direction aliases, an access queue, digests, and formal
`pagePresent` state. Those navigation mechanisms are deleted together with
their source-closure roots and private benchmarks. For the default non-expiring
cursor policy, the replacement is one complete lookup-resource,
lookup-subject, or relationship-read transport-page value under a complete
exact-basis raw-request key in the existing lifecycle. The key includes the
exact cursor token, full authenticated consistency descriptor including an
exact token or freshness floor, operation, and cursor-key policy; the value is
published only after successful authentication/evaluation and contains the
immutable public page.
It contains no clock, deadline, cancellation object, adapter, source, or request
object. This is ordinary keyed retention, not navigation state.
Rendered key inputs and retained values are metadata-free portable data because
Clojure metadata is absent from value equality/canonical transport and may hide
request-owned state. Metadata-bearing custom identities use the semantic
internal-answer path instead. Cursor scopes and emitted edges reject such
nonportable identities rather than signing an aliased representation. The
token and construction-context caches canonical-copy request-derived keys and
values only on misses, preserving the ordinary hit cost while preventing
metadata retention; the separate signing-key context remains client-owned and
nonportable by design.

Operation-typed validation admits EACL `SpiceObject` lookup items and EACL
`Relationship` items composed from SpiceObjects for relationship reads; custom
records fail closed. Object IDs are bounded canonical scalars/plain vectors;
map/set IDs are rejected outright. Ordinary request maps, vectors, and sets are
recursively copied into plain persistent containers so a caller comparator or
collection implementation cannot remain resident.

The raw token and complete request are the transport key, so lookup happens
before cursor decode, object-identity internalization, public identity
conversion, or proof work. An unknown token cannot equal a previously
authenticated process-private key, and a changed keyring changes cursor-policy
identity. Cursor TTL disables this tier so current expiry remains authoritative.
Managed semantic reuse at a newer basis may still populate a new transport
value under that exact target basis; transport values never cross exact bases.

For deterministic adapters that promise immutable and injective external IDs,
point, both count, and permission-tree operations also probe a bounded
canonical public semantic key before backend ID internalization. Other
identity contracts and ID representations stay on the internal path.

Successful validated insertion is the publication linearization point. A
cancellation/deadline observed first skips it; a signal racing after it may
suppress the request response but does not roll back a valid immutable mapping.

Adjacent private continuation reuse remains. A first unseen reverse or
nonadjacent request may replay once; an end-to-end Next/Previous workload must
show identical results/cursors and quantify backend work. If that measured
regression violates existing gates, the change pauses for a semantic-keyed
answer, exact render, or continuation fix; it does not recreate aliases or a
custom page cache state machine.

### 9. Migrate only genuine recomputable shared stores

The standard adapter replaces:

- answer and exact-denotation tier state;
- exact complete transport-page retention;
- private continuation retention;
- cursor encoding/construction retention; and
- the cross-request derived-schema registry.

The Datomic `CacheStore`/`LocalStore`, dead `RevisionCheckpoints`, unused derived
fields, and opt-in relationship-observation mapping are deleted after source
closure proves they have no authorization consumer. The latter is diagnostic
only but has its own custom eviction; deleting its disabled-by-default surface
is simpler than retaining a second cache policy.

Stable-page checkpoints remain semantic execution state with their existing
per-checkpoint admission-count cap and latest-progress rules, but their bounded
multi-identity retention uses the same standard cache adapter even when the store
is request-local or standalone-API supplied. Checkpoint comparison stays
outside storage; only an ordinal-and-boundary-accepted held value receives an
identity-conditional touch. The DataScript capacity-one adapter wrapper is an
identical-DB basis optimization, not a multi-entry retention policy. Request-local
visited sets, work queues, plan memos, backend indexes, consistency selectors,
and metric counters remain outside the adapter. The stable reducer's bounded
sidecar is consumable prefetch state inside one immutable traversal checkpoint:
each chunk index advances once and cannot answer another computation, so its
oldest-first release is execution-state bounding rather than a cache policy.
The saturating schema-warning condition set similarly deduplicates diagnostics
and cannot supply or alter an authorization result. The generated-Java
adapter's fuel and traversal-limit conversion memos are capacity-one volatile
slots, for which FIFO and LRU are identical; concurrent replacement can only
cause another pure conversion. The request-counter thread-local similarly
holds one current binding-frame pointer rather than reusable keyed results.

This classification prevents a common simplification error: replacing
authoritative or request-local algorithmic state with an evictable map.

### 10. Snapshot v2 serializes mappings, not policy internals

The v2 export contains format/ABI identifiers, stable lifecycle/source
contract, capacities required by the public restore contract, and a canonical
sequence of `[composite-key validated-value]` entries by artifact tier. It
excludes cache objects, metrics, backend database values, process-local
identities, exact rendered-page values, Caffeine policy/admission state, CLJS
LRU priority/tick state, weights, tombstones, and continuations not already
allowed by the public snapshot contract.

Export sorts entries by the repository's canonical cross-runtime value order.
That ordering operates on already admitted semantic keys and does not impose
the canonical codec's ordinary token-size ceiling: this change deliberately
adds no semantic-key byte limit. The host's authenticated external envelope is
the byte-bounded boundary. Ordinary statistics and lifecycle accounting never
serialize resident keys.
Restore validates the closed shape, ABI, capacity, duplicate keys, complete
keys, operation-specific values, causal/lifecycle metadata, and the completed
page guard into fresh off-side stores by sequentially inserting canonical
sorted entries. Exact eviction priority, admission history, and preservation of
pre-export private frequency/recency order are explicitly not part of the contract.
Any failure leaves the installed lifecycle and content revision unchanged.

The API accepts a trusted decoded value. Hosts must authenticate and bound
external encoded bytes before decoding/calling restore. EACL does not retain
the removed external-provider signing-key surface.

Alternative: translate v1 generation/weight snapshots. Rejected because it
would preserve the deleted policy model, complicate validation, and create two
key ABIs in the authorization path.

### 11. Simplify formal and generated closure in lockstep

`SubproblemCache.dfy` and the consolidated TLA cache model keep complete keys,
valid completed values, optional retention eligibility, independent
computation, optional publication, arbitrary eviction, and lifecycle
replacement. Tiered weight, custom recency, generation-container, coupled-budget,
and visited-public-page models and mutants are retired. Managed proof bypass,
partial-value publication, and orphaned old-lifecycle publication remain
negative controls in the consolidated model.

`CurrentCache.dfy` no longer generates a stage/availability storage policy.
Production performs the explicit exact-key, managed-key, compute sequence; its
key/value conversions and causal checks remain mapped to proof/refinement
claims. Generated JVM/JS runtime exports and portable decision wrappers change
with the implementation. Focused differential tests and a source inventory
ensure no stale generated action remains callable as decorative authority;
release manifests and artifact digests are not product correctness gates.

## Risks / Trade-offs

- **[Policy recording adds hit overhead]** → Keep workload-isolated stores,
  benchmark hit latency/allocation/contention on CLJ and CLJS, and rely only on
  library-managed policy state rather than an EACL touch sidecar.
- **[Caffeine maintenance may briefly lag]** → Treat `maximumSize` as an
  eventual concurrent bound, avoid synchronous cleanup on the request path, and
  preserve the request-owned completed value regardless of retention outcome.
- **[Entry count does not bound heap]** → Skip completed pages above 1,000
  results, preserve existing artifact-specific semantic bounds, and add a
  measured byte strategy later rather than relabeling estimates as bytes.
- **[Composite keys are large or expensive to hash]** → Reuse canonical
  identities already computed for correctness, benchmark key construction,
  and permit digest acceleration only with full collision checking.
- **[Expired continuation entries consume slots]** → They are harmless
  misses, receive no deliberate access refresh, and are bounded by entry count; lifecycle
  rotation clears them wholesale.
- **[Deleting page navigation increases first reverse work]** → Run an
  end-to-end oscillation benchmark and fix internal answer/continuation keys if
  existing thresholds fail.
- **[Untrusted snapshot can forge a value]** → Keep restore explicitly
  trusted-value-only, require host authentication/encoded-size bounds, validate
  every key/value off-side, and install atomically.
- **[CLJ and CLJS cache policies diverge]** → Replay nil/false, equality,
  existing-key, capacity, hot-key, hit, eviction, sequence/iteration, and
  contention tests against the common contract; test CLJS tick normalization
  separately and never require identical victims or resident sets.
- **[Two `cljs.cache` artifacts coexist]** → Exclude the upstream Datahike
  dependency in every source/POM surface and assert the resolved dependency
  tree.
- **[Formal evidence overstates the new boundary]** → Mark cache/resource
  behavior as tested host obligations; retain mechanized claims only for
  semantic key/value/lifecycle properties and fail source closure on stale
  policy artifacts.

## Migration Plan

1. Add Caffeine 3.2.4, the selected CLJS dependency, and the Datahike exclusion;
   establish runtime adapter tests and verify one CLJS cache provider per source graph.
2. Introduce the private standard store and lifecycle types without routing
   production through them. Prove/verify the abstract partial-map model and
   update generated/refinement boundaries.
3. Route answer and exact-denotation lookups/publications through flat
   composite keys, with managed mode only for completed answers. Delete shared
   physical projection retention after cross-backend measurements show it is a
   net loss for common small demands. Differentially compare cache-enabled and
   disabled outcomes before deleting the nested implementation and flattening
   the two remaining public capacities.
4. Migrate continuations, stable-page checkpoints, cursor codecs, and derived-
   schema retention. Preserve semantic validation, checkpoint admission-count
   and latest-progress rules, and authoritative bounds outside standard cache
   storage.
5. Remove `PageNavigationCache`, its call sites/models/mutants/benchmarks, the
   unreachable Datomic `LocalStore`/revision checkpoints, unused derived fields,
   and the relationship-observation cache after source-closure confirmation.
   Add the single exact complete transport-page store to the same lifecycle.
6. Introduce snapshot/key ABI v2, atomic off-side restore, v1 typed rejection,
   and backend forwarding tests. Remove all old cache configuration/statistics
   fields rather than translating them.
7. Update public docs and the stale-cache source inventory. Run targeted
   semantic proofs, both CLJS optimization modes, full cross-backend suites,
   cache-disabled differential tests, and the benchmark matrix.
8. Publish the EACL feature branch for product testing with the tested CLJS Git
   SHA and test/benchmark commands.

Rollback requires deploying the prior EACL artifact and recreating clients
with empty prior-version caches. Snapshot v2 is intentionally not readable by
the prior version, and v1 is not read by the new version. No database migration
or authorization data rollback is required.

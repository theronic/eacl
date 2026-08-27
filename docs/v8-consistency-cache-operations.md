# EACL consistency and cache operations

EACL selects one immutable database value at the start of an authorization
operation. Schema resolution, normalization, traversal, proof acquisition,
result rendering, and cursor construction all use that value.

## Consistency modes

| Mode | Datomic | Datahike | DataScript | Datalevin | Completed-answer cache |
| --- | --- | --- | --- | --- | --- |
| omitted / `:minimize-latency` | reuse client pin | reuse client pin | reuse client pin | reuse/acquire the adapter's qualified owned pin | exact-first, then automatic proof-backed reuse when certified |
| `:fully-consistent` | bounded zero-argument `d/sync` | authoritative head barrier when supported | serialized connection head | new owned read snapshot under the sole-writer topology | enabled for the selected ordinary basis |
| `:at-least-as-fresh` | targeted `d/sync conn t` and revision validation | selects/waits for a sufficient native revision | selects a sufficient connection-local revision | bounded acquire/check/release retry | enabled only when selection is an ordinary basis |
| `:at-exact-snapshot` | authenticated targeted catch-up when behind, then exact `d/as-of T` | retained commit or durable temporal selection, configuration-specific | unsupported | unsupported | exact-first; managed reuse when the historical value has a readable contract-valid frame |

These modes select a basis only when the target is an `acl`. On a retained
snapshot they are assertions: omitted or minimize-latency evaluates that basis;
at-least evaluates only if the authenticated floor is satisfied; exact
evaluates only if the token names that basis; fully-consistent is rejected
because a value with no source cannot establish a new head barrier. Assertion
failure occurs before cache lookup, schema planning, or adapter evaluation.

The default performs no synchronization, branch-head request, or historical
selection after the client has a pin. Evaluation on an EACL snapshot never
refreshes or replaces it. Public operations do not accept caller-supplied
historical, filtered, or prospective native database values. Explicit
`eacl/with` and `eacl/with-schema` snapshots remain on their immutable
prospective value, never use the native exact tier, may read only a completely
validated disjoint committed proof, and publish no cache data.

For Datomic, an authenticated same-source exact token ahead of the local Peer
is a freshness barrier, not an out-of-range snapshot. EACL compares `T` with
one local database, skips synchronization when already local, otherwise waits
boundedly on `(d/sync conn T)` and cancels that future on timeout or
interruption. It verifies the caught-up basis and evaluates only
`(d/as-of caught-up-db T)`, never the possibly newer synchronized head.

EACL-created Datahike databases default to `:keep-history? true`. That durable
temporal path survives ordinary commit-record cutoff collection. A
history-disabled store with a commit graph has only conditional exact support:
collection of the named commit can make that snapshot unavailable.

## Automatic basis cache

Every client owns a bounded basis cache. Exact answers are attached to the
selected immutable database identity and are checked first without proof. On
an exact miss, deterministic requests on any admissible basis automatically
attempt a complete ordered-generation proof when that value can read one. A
proof-backed answer may serve an older or newer selected basis in the same
lineage—complete source scope plus source lifecycle—when normalized operation,
result shape, schema generation, and scalar dependency frontier are equal.
Selected revision order is not part of that equality proof.

Missing, partial, oversized, unsupported, or exceptionally unavailable proof
evidence falls back to evaluation and exact caching for the selected value. A
malformed, non-numeric, non-canonical, or above-revision frame is an adapter
contract violation: authorization and exact caching continue, while managed
lifting becomes sticky-disabled until lifecycle expiry. Neither case permits
partial-proof reuse.

Authenticated exact requests use the same bounded exact-basis tier as ordinary
selected bases. The key includes source scope and lifecycle, native revision
and locator, basis kind, adapter and identity contracts, engine ABI, result
shape, demand, and answer-affecting limits. Historical requests probe the
managed tier only when their own immutable value supplies a complete readable
frame. Public IDs, tokens, cursors, cache basis, and other metadata are rebuilt
from the selected adapter on every hit.

Disable caching:

```clojure
(require '[eacl.cache :as cache])

(datomic/make-client conn {:cache cache/no-cache})
(datahike/make-client conn {:cache cache/no-cache})
(datascript/make-client conn {:cache cache/no-cache})
(datalevin/make-client conn {:cache cache/no-cache})
```

Or pass `:cache? false` on one request. Pass `:populate-cache? false` to retain
lookups and request-local memoization while suppressing completed answers,
managed subproblems, checkpoints, and visited pages. Both options are excluded
from semantic and cursor identity; `:cache? false` dominates either populate
value. `cache-stats` reports exact and proof-backed hits, misses, bypasses,
proof-unavailable and contract-violation reasons, publications, expirations,
and bounded-store metrics.

## Mutation contract

Cache coherence assumes that every authorization-relevant mutation uses EACL
or documented EACL-produced transaction data/functions, transacted intact.
This includes schema, relationships, permissioned identity/liveness, repair,
object cleanup, and safe entity deletion. Application-domain datoms that do
not affect authorization are unrestricted.

Supported writers commit tuple changes and every affected relation generation
atomically. Raw authorization mutations can leave a proof-backed entry stale.
After one occurs, quiesce all affected callers, repair the database, expire or
recreate every client in every process, and only then resume. Preparation and
an identical schema write are not cache flushes; cache expiry is not ghost
repair.

```clojure
(eacl.datomic.core/expire-cache! acl)
(eacl.datahike.core/expire-cache! acl)
(eacl.datascript.core/expire-cache! acl)
```

Pass one shared new lifecycle as the optional second argument when processes
exchange tokens. Also rotate after reset, restore, branch replacement, or
another change that can reuse or regress native revision identities.

Datomic excision and Datahike purge/cutoff or branch-force operations are
destructive history boundaries. Quiesce all affected traffic, let the
operation complete, rotate the shared source lifecycle and every client/cache,
apply the intended token/cursor key and format retirement policy, and only then
resume. EACL does not support concurrent history destruction and authorization
under one unchanged lifecycle.

## Custom identity

Custom ID codecs are client-local and exact-only by default. Cross-snapshot
completed-answer reuse requires a portable `:adapter-fingerprint`,
`:adapter-deterministic? true`, and an application-certified deterministic,
injective round trip. Proof-equivalent and cross-client cursors additionally
require `:identity-immutable? true`: public identity must remain stable for one
internal object throughout the source lineage. Every process exchanging
cursors must use the same codec, fingerprint, and identity contract. The
built-in `:eacl/id` codec assumes immutability; set `:identity-immutable? false`
to keep cursors exact-basis-bound when the application permits ID mutation.

## Cursors

Cursors bind the normalized query, principal, permission, filters, operation,
ordering and plan ABI, adapter/identity contracts, native revision and exact
locator, boundary, and the canonical continuation triple
`[lineage frame closure-digest]`. Lineage is source scope plus lifecycle. The
frame is certified schema generation plus the scalar frontier of the complete
relation closure. Cursor validation uses the same lazy request frame as answer
and checkpoint lookup; a continued page performs at most one generation read
for each relation in that closure.

A selected basis may consume the old boundary only when lineage, frame, and
closure digest are equal. Unrelated transactions therefore continue on the
new basis with an oracle-identical forward suffix or reverse prefix. A relevant
relationship or schema mutation changes the frame and rejects current-basis
continuation. A retained `Snapshot` cannot acquire another basis and reports
`:eacl.consistency/basis-conflict` with `:source :cursor`; it never drops a
bound or silently restarts page one.

For an `acl`, a changed frame triggers exact selection only when the source
advertises it and the request's freshness floor permits the cursor revision.
The selected original basis is accepted by authenticated source scope,
lifecycle, revision, and locator identity, without reading its historical
frame. Datomic and qualifying Datahike configurations support this path.
DataScript and Datalevin are current-only and return the typed stale-cursor
outcome when their current frame changed.

A contract-violating or unavailable frame is never cross-basis equality
evidence. Its cursor context is tagged to the exact immutable basis;
continuation either reconstructs that authenticated value or returns the
ordinary typed stale/basis-conflict outcome.

Continuation state is a private performance optimization. Eviction replays the
authenticated prefix on the already selected snapshot and does not select a
different lifecycle. `:populate-cache? false` leaves validation and page data
unchanged while suppressing checkpoint and visited-page publication.

Provider restart preserves cursors only when the source identity is durable.
The same Datomic database, durable Datahike store, and Datalevin store retain
lineage across a new connection/provider. A recreated DataScript connection,
in-memory Datahike store, or independent Datomic memory database receives a
fresh live-source id and rejects the old cursor with `:source-scope` before a
generation read, even with identical data, shared keys, and the constant
default lifecycle. Destructive history replacement requires explicit lifecycle
rotation.

Cursor expiry is disabled by default and cache retention never determines
cursor lifetime. A positive `:cursor-ttl-seconds` adds an explicit application
policy expiry. Without it, elapsed wall-clock age is irrelevant; key
retirement, incompatible wire/ABI versions, lifecycle rotation, genuine
conditional-history loss, or bounded replay failure remain explicit outcomes.
An old cursor enumerates its original historical result set. Applications that
need current entitlement while consuming it must perform a current permission
check for each consumed object.

## Assurance boundary

Dafny proves the finite cache decision distinguishes exact-basis and managed
hit/miss actions; it also proves lifecycle isolation, proof completeness,
scalar-frontier preservation under globally ordered atomic relation stamps,
and that an adaptive reducer restricted to the certified plan closure has the
same transitions, emissions, order, and boundary positions at equal closure
slices. Existing pagination leaves establish the corresponding forward suffix
and reverse prefix. The production sealed-plan read-scope guard is mutation
controlled. It does not prove
Datomic I/O effects or future cancellation, Datahike history retention, or the
truthfulness of a canonical cache key. Those are explicit adapter assumptions
covered by deterministic effect tests and real-backend evidence. Database
engines, adapter conversion, cryptography, and production routing remain
trusted or empirically certified layers.

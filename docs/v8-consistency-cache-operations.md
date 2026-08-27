# EACL consistency and cache operations

EACL selects one immutable database value at the start of an authorization
operation. Schema resolution, normalization, traversal, proof acquisition,
result rendering, and cursor construction all use that value.

## Consistency modes

| Mode | Datomic | Datahike | DataScript | Datalevin | Completed-answer cache |
| --- | --- | --- | --- | --- | --- |
| omitted / `:minimize-latency` | current DB visible to the Peer | current connection DB | current connection DB | new owned read snapshot | exact-first, then automatic proof-backed reuse when certified |
| `:fully-consistent` | bounded zero-argument `d/sync` | authoritative head barrier when supported | serialized connection head | new owned read snapshot under the sole-writer topology | enabled for the selected ordinary basis |
| `:at-least-as-fresh` | targeted `d/sync conn t` and revision validation | selects/waits for a sufficient native revision | selects a sufficient connection-local revision | bounded acquire/check/release retry | enabled only when selection is an ordinary basis |
| `:at-exact-snapshot` | authenticated targeted catch-up when behind, then exact `d/as-of T` | retained commit or durable temporal selection, configuration-specific | unsupported | unsupported | matching exact-basis answers only; managed proof reuse prohibited |

These modes select a basis only when the target is an `acl`. On a retained
snapshot they are assertions: omitted or minimize-latency evaluates that basis;
at-least evaluates only if the authenticated floor is satisfied; exact
evaluates only if the token names that basis; fully-consistent is rejected
because a value with no source cannot establish a new head barrier. Assertion
failure occurs before cache lookup, schema planning, or adapter evaluation.

The default performs no synchronization or historical selection. Low-level
engine functions that accept caller-supplied historical, filtered, or
prospective database values do not have a managed-cache availability
guarantee.

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
an exact miss, deterministic ordinary current requests automatically attempt a
complete ordered-generation proof. A proof-backed answer may serve an older or
newer ordinary selected basis in the same scope and lifecycle when normalized
operation, result shape, schema generation, and scalar dependency frontier are
equal; selected revision order is not part of that equality proof.

Missing, malformed, partial, oversized, unsupported, or exceptional proof
evidence falls back to evaluation
and exact caching for the selected value. It never becomes an authorization
error and never permits partial-proof reuse.

Authenticated exact requests use the same bounded exact-basis tier as ordinary
selected bases. The key includes source scope and lifecycle, native revision
and locator, basis kind, adapter and identity contracts, engine ABI, result
shape, demand, and answer-affecting limits. Historical requests never probe the
managed tier. Public IDs, tokens, cursors, cache basis, and other metadata are
rebuilt from the selected adapter on every hit.

Disable caching:

```clojure
(require '[eacl.cache :as cache])

(datomic/make-client conn {:cache cache/no-cache})
(datahike/make-client conn {:cache cache/no-cache})
(datascript/make-client conn {:cache cache/no-cache})
```

Or pass `:cache? false` on one request. `cache-stats` reports exact and
proof-backed hits, misses, bypasses, proof-unavailable reasons, publications,
expirations, and bounded-store metrics.

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
reuse and cross-client cursors require a portable `:adapter-fingerprint`,
`:adapter-deterministic? true`, and an application-certified deterministic,
injective round trip. Every process exchanging cursors must use the same codec
and fingerprint.

## Cursors

Cursors bind the normalized query, principal, permission, filters, operation,
adapter/source lifecycle, ordering, native revision, and exact/proof identity.
The same retained snapshot continues directly with zero acquisition. An `acl`
uses source-owned exact reconstruction when necessary. A different retained
snapshot continues only when its complete proof admits the cursor basis;
otherwise EACL throws `:eacl.consistency/basis-conflict` with `:source :cursor`.
It never drops a bound, silently restarts page one, or acquires through a
retained snapshot.

Continuation state is a private performance optimization. Eviction replays the
authenticated prefix on the already selected snapshot and does not select a
different lifecycle. DataScript has no arbitrary time-travel path.

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
hit/miss actions; it also proves lifecycle isolation, proof
completeness, and scalar-frontier preservation under globally ordered atomic
relation stamps and deterministic complete dependencies. It does not prove
Datomic I/O effects or future cancellation, Datahike history retention, or the
truthfulness of a canonical cache key. Those are explicit adapter assumptions
covered by deterministic effect tests and real-backend evidence. Database
engines, adapter conversion, cryptography, and production routing remain
trusted or empirically certified layers.

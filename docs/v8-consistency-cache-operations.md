# EACL consistency and cache operations

EACL selects one immutable database value at the start of an authorization
operation. Schema resolution, normalization, traversal, proof acquisition,
result rendering, and cursor construction all use that value.

## Consistency modes

| Mode | Datomic | Datahike | DataScript | Completed-answer cache |
| --- | --- | --- | --- | --- |
| omitted / `:minimize-latency` | current DB visible to the Peer | current connection DB | current connection DB | exact-first, then automatic proof-backed reuse |
| `:fully-consistent` | bounded zero-argument `d/sync` | authoritative head barrier when supported | serialized connection head | enabled for the selected current DB |
| `:at-least-as-fresh` | targeted `d/sync conn t` and revision validation | selects/waits for a sufficient native revision | selects a sufficient connection-local revision | enabled only when selection is an ordinary current DB |
| `:at-exact-snapshot` | authenticated targeted catch-up when behind, then exact `d/as-of T` | retained commit or durable temporal selection, configuration-specific | unsupported | matching snapshot-exact answers only; managed proof reuse prohibited |

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

## Automatic current cache

Every client owns a bounded current cache. Exact answers are attached to the
selected immutable database identity and are checked first without proof. On
an exact miss, deterministic ordinary current requests automatically attempt a
complete ordered-generation proof. A proof-backed answer may survive unrelated
forward transactions when lifecycle, normalized operation, result shape,
schema generation, and scalar dependency frontier are equal.

Missing, malformed, partial, oversized, unsupported, or exceptional proof
evidence falls back to evaluation
and exact caching for the selected value. It never becomes an authorization
error and never permits partial-proof reuse.

Authenticated exact requests use a separate bounded completed-answer tier
keyed by the complete ordinary snapshot identity and semantic request. That
key includes source/lifecycle, native revision and locator, exact view kind,
adapter and identity contracts, engine/order ABI, result shape, demand, and
answer-affecting limits. Exact requests never probe the managed tier and never
bind partial traversal stores. Public IDs, tokens, cursors, cache basis, and
other metadata are rebuilt from the selected adapter on every hit.

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
On continuation EACL authenticates before traversal, continues on a
proof-equivalent current snapshot when possible, otherwise reconstructs the
authenticated exact snapshot on history-capable backends, and fails closed
when neither is possible. It never drops a bound or silently restarts page one.

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

Dafny proves the finite cache decision distinguishes current exact, managed,
and snapshot-exact hit/miss actions; it also proves lifecycle isolation, proof
completeness, and scalar-frontier preservation under globally ordered atomic
relation stamps and deterministic complete dependencies. It does not prove
Datomic I/O effects or future cancellation, Datahike history retention, or the
truthfulness of a canonical cache key. Those are explicit adapter assumptions
covered by deterministic effect tests and real-backend evidence. Database
engines, adapter conversion, cryptography, and production routing remain
trusted or empirically certified layers.

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
| `:at-exact-snapshot` | authenticated `d/as-of` | retained exact/temporal selection when supported | unsupported | proof-backed completed answers bypassed |

The default performs no synchronization or historical selection. Low-level
engine functions that accept caller-supplied historical, filtered, or
prospective database values do not have a managed-cache availability
guarantee.

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

## Assurance boundary

Dafny proves exact-first selection, lifecycle isolation, proof completeness,
and the scalar-frontier preservation theorem under globally ordered atomic
relation stamps and deterministic complete dependencies. Adapter certification
tests establish those obligations for Datomic, Datahike, and DataScript. The
database engines, adapter conversion, cryptography, and production routing
remain explicit trusted and empirically tested layers.

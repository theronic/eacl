# EACL v8 consistency and cache operations

EACL selects one immutable database value at the start of an authorization
operation. Schema resolution, query normalization, traversal, cache validation,
result rendering, and cursor construction all use that same value.

The completed-answer cache is deliberately not a historical cache. It is a
private optimization owned by one EACL client and one connection.

## Consistency modes

| Mode | Datomic | Datahike | DataScript | Completed-answer cache |
| --- | --- | --- | --- | --- |
| omitted / `:minimize-latency` | current DB visible to this Peer | current connection DB | current connection DB | enabled |
| `:fully-consistent` | bounded zero-argument `d/sync`, then selected DB | backend head barrier when supported | serialized live connection head | enabled on the selected current DB |
| `:at-least-as-fresh` | targeted `d/sync conn t`, then revision validation | waits/selects a DB at or above the native revision | selects a DB at or above the connection-local revision | enabled only if the selected DB is current |
| `:at-exact-snapshot` | authenticated `d/as-of` selection | retained exact/temporal selection | unsupported; rejected before cache access | bypassed |

The default is `:minimize-latency`. EACL does not call `d/sync` and does not call
`d/as-of` on the normal path. A consumer that requires the Peer to observe
transactor head may synchronize before calling EACL, or explicitly request
`:fully-consistent`.

`fully-consistent` cannot promise that a disconnected Peer knows about a
transaction that it has no way to observe. Its precise v8 meaning is an
authoritative synchronization barrier supported by the configured backend.
It does not claim a distributed linearizability theorem beyond that backend
barrier.

Low-level engine functions that accept an arbitrary `db` remain available for
prospective, filtered, or historical evaluation. Those functions bypass the
completed-answer cache.

## Current-generation cache

Each client owns a bounded native cache with two tiers:

1. **Exact-current.** Entries are attached to one immutable selected DB
   generation. A hit is accepted only for that same generation. Any ordinary
   transaction replaces the generation, so no dependency proof is needed.
2. **Managed-current.** Entries may survive unrelated forward transactions.
   The key contains the source lifecycle, physical schema generation, and
   complete canonical physical relation-generation vector for the permission's
   dependency closure.

The answer tiers retain completed public operation results. The traversal
cache may additionally retain exact generated-command responses and private
continuations produced before the request's stopping boundary. Demand mode
never widens a scan or continues traversal to warm a broader artifact.
Completed acyclic or recursive denotations are retained only when traversal
naturally exhausts or the caller explicitly requests
`:evaluation :complete-denotation`. Public IDs and response metadata are
rendered from the selected DB after lookup. Failed or timed-out work is never
admitted as a completed answer or denial.

The cache never changes authorization semantics. Disable it globally:

```clojure
(require '[eacl.cache :as cache])

(datomic/make-client conn {:cache cache/no-cache})
(datahike/make-client conn {:cache cache/no-cache})
(datascript/make-client conn {:cache cache/no-cache})
```

Or bypass it for one operation with `:cache? false`.

These modes branch directly to engine evaluation before semantic cache-key
construction, dependency-stamp capture, snapshot-token calculation, cache
resolution, canonicalization, and cache-envelope creation. Cache-free
reference evaluation is therefore independent of the cache strategy both
semantically and computationally.

The default private cache is mandatory in the sense that Datahike and
DataScript create it automatically when `:cache` is omitted; callers can still
explicitly choose `cache/no-cache` for diagnostics and cache-free reference
evaluation.

Capacity is bounded with `{:cache {:max-entries n}}`. Datomic still accepts the
legacy cache configuration surface, but caller-supplied portable providers are
not an authority for completed native answers. A corrupt, stale, shared, or
failing provider therefore cannot grant access. Cursor continuation state also
uses a separate bounded private store.

## Exact-current versus managed-current

Every backend defaults to `:coherence-authority :unknown`. Unknown authority
enables exact-current reuse only. It is sound with out-of-band writers because
a changed immutable DB generation cannot hit the previous generation. Managed
reuse is never a silent default: one raw backend transaction outside EACL's
writers would otherwise leave every relation stamp untouched and let a stamped
entry outlive the data it was computed from.

Opt in to managed reuse only under this explicit contract:

```clojure
(def acl
  (datomic/make-client
    conn
    {:coherence-authority :managed}))
```

`managed` means every relationship mutation that can affect EACL uses an EACL
API or documented helper that atomically updates `:eacl/relation-version`.
Schema changes use `eacl/write-schema!`. This contract may be shared by
multiple clients and processes; generations live in the database, not in a
listener. A missing generation is never replaced by a synthetic or mutation-ID
fallback and therefore disables managed reuse for that candidate.

For a dependency set `D`, EACL validates that every dependency has a stamp and
builds the complete sorted per-relation stamp vector:

```text
dependency-stamp = sort-by relation [[relation assertion-t stored-generation] ...]
                   for relation in D
```

Under ordinary forward transactions, changing any relevant relation writes a
strictly newer stamp component and therefore changes the vector. An unrelated
relation write leaves it unchanged. The complete vector (not a folded maximum)
is the key component, so distinct histories and distinct dependency sets
cannot collide.

An actual schema change changes the schema generation and expires all managed
answers and compiled plans. EACL intentionally does not attempt partial cache
retention across schema updates.

Custom object-ID codecs are exact-current-only unless their adapter supplies a
stable fingerprint, deterministic behavior, and a separate dependency-frame
contract. Future caveats or authorization-relevant attributes must likewise
declare complete dependency classes before managed reuse is enabled.

## Explicit lifecycle expiry

History manipulation is outside the ordinary forward-transaction contract.
Quiesce the client and call the backend's `expire-cache!` after reset, restore,
branch force, manual history replacement, or an unstamped bulk repair:

```clojure
(eacl.datomic.core/expire-cache! acl)
(eacl.datahike.core/expire-cache! acl)
(eacl.datascript.core/expire-cache! acl)
```

Expiry rotates source/token scope and swaps every client-private cache family.
In-flight work retains its captured old semantic lifecycle, so its late result
is unreachable from requests in the new lifecycle.

`cache-stats` on the same backend namespace reports exact hits, managed hits,
misses, bypasses, stamp failures, puts, expirations, and live entry counts.

## Cache-free reference semantics

The cache-free implementation is the reference evaluator:

```clojure
(eacl/can? acl
  {:subject subject
   :permission :view
   :resource resource
   :cache? false})
```

The formal cache theorem proves a cached internal result equals recomputation
under the selected-snapshot and managed-writer premises. Differential tests
then compare cache-enabled and cache-disabled public operations across
Datomic, Datahike, and DataScript. This separation is intentional: the cache
is an optimization/refinement of authorization, never part of the definition
of authorization.

## Cursors

Cursors are authenticated and scoped to backend/source lifecycle, operation,
query, engine version, native revision, and an exact snapshot locator. The normalized
query scope includes the principal, permission, filters, resource type, and
consistency contract. Relay direction and page size are resume controls, not
authorization scope.

A cursor walk follows its requested consistency contract:

1. decode and authenticate the cursor before it influences traversal;
2. continue on current only when its complete dependency and ordering proof
   equals the cursor proof;
3. after a changed proof, reconstruct the cursor's authenticated exact
   snapshot on history-capable backends;
4. reject when exact reconstruction is unavailable or violates a newer
   freshness floor;
5. validate ordinal and result identity before continuation; never drop the
   bound or restart page one.

EACL does not recalculate a whole-result content proof on every page. The
dependency/order proof is scoped to the semantic query. Proof equality permits
current continuation without history; proof inequality never permits current
continuation.

Recursive continuation state is an optional performance optimization.
Continuation-store eviction deterministically replays the authenticated prefix
on the already-selected immutable snapshot. It never selects another lifecycle or
changes the public walk.

DataScript has no EACL time-travel path. It continues only on a
proof-equivalent current DB and returns the typed stale/unsupported error when
that is impossible; it never retains an old DB in a hidden registry.

## Operational invariants

- One public request selects one immutable DB exactly once.
- Local consistency does no implicit network synchronization.
- Normal current reads do not call `d/as-of`.
- Exact, historical cursor, and arbitrary-DB work bypass completed answers.
- Schema changes drop the entire managed cache generation.
- Listener timing, TTL, wall clock, and a numeric “latest” pointer are not
  validity evidence.
- Cache/provider failure is a miss, never an allow.
- A recursive limit failure is never cached as a complete deny/allow/page.
- Async Datomic excision is outside the v8 cache contract.

## Assurance boundary

The verified current-cache model proves current-only admission, exact
same-snapshot hits, lifecycle isolation, forward scalar-stamp invalidation, a
least-fixed-point ReBAC frame theorem for complete compiled dependencies, and
selected-snapshot result rendering. The complete public Clojure/CLJS engine is
not yet claimed as end-to-end formally verified; adapter behavior, boundary
conversion, cryptography, and production routing remain explicit trusted or
empirically certified layers.

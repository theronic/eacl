# EACL v8 consistency and cache operations

EACL selects one immutable database value at the start of an authorization
operation. Schema resolution, query normalization, traversal, cache validation,
result rendering, and cursor construction all use that same value.

The completed-answer cache is deliberately not a historical cache. It is a
private optimization owned by one EACL client and one connection.

## Consistency modes

| Mode | Datomic | Datahike | DataScript | Completed-answer cache |
| --- | --- | --- | --- | --- |
| omitted / `:local-snapshot` | current DB visible to this Peer | current connection DB | current connection DB | enabled |
| `:minimize-latency` | same as local snapshot | same as local snapshot | same as local snapshot | enabled |
| `:synchronized-head` | bounded zero-argument `d/sync`, then selected DB | backend head barrier when supported | serialized local connection head | enabled on the selected current DB |
| `:fully-consistent` | compatibility name for `:synchronized-head` | compatibility authoritative barrier | compatibility authoritative barrier | enabled on the selected current DB |
| `:at-least-as-fresh` | targeted `d/sync conn t`, then anchor validation | waits/selects a descendant containing the token anchor | selects a known descendant containing the token anchor | enabled only if the selected DB is current |
| `:at-exact-snapshot` | authenticated `d/as-of` selection | retained exact/temporal selection | bounded exact-snapshot registry | bypassed |

The default is `:local-snapshot`. EACL does not call `d/sync` and does not call
`d/as-of` on the normal path. A consumer that requires the Peer to observe
transactor head may synchronize before calling EACL, or explicitly request
`:synchronized-head`.

`fully-consistent` cannot promise that a disconnected Peer knows about a
transaction that it has no way to observe. Its precise v8 meaning is an
authoritative synchronization barrier supported by the configured backend.
`:synchronized-head` is the preferred name because it states that contract
without implying a distributed linearizability theorem EACL cannot provide.

Low-level engine functions that accept an arbitrary `db` remain available for
prospective, filtered, or historical evaluation. Those functions bypass the
completed-answer cache.

## Current-generation cache

Each client owns a bounded native cache with two tiers:

1. **Exact-current.** Entries are attached to one immutable selected DB
   generation. A hit is accepted only for that same generation. Any ordinary
   transaction replaces the generation, so no dependency proof is needed.
2. **Managed-current.** Entries may survive unrelated forward transactions.
   The key contains the schema generation and the maximum last-change
   transaction over the permission's complete relation dependency set.

Both tiers cache complete semantic answers only: Booleans, complete internal
result sequences/sets, and complete counts. Public IDs and response metadata
are rendered from the selected DB after lookup. Partial traversal failures,
provider failures, tokens, cursors, and page-local fragments are not admitted
as complete answers.

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

The default `:coherence-authority :unknown` enables exact-current reuse only.
It is sound with out-of-band writers because a changed immutable DB generation
cannot hit the previous generation.

Use managed reuse only under this explicit contract:

```clojure
(def acl
  (datomic/make-client
    conn
    {:coherence-authority :managed
     :cache {:max-entries 4096}}))
```

`managed` means every relationship mutation that can affect EACL uses an EACL
mutation API or the documented backend transaction helper that atomically
updates the affected relation-version/mutation datoms. Schema changes use
`eacl/write-schema!`. This contract may be shared by multiple EACL clients and
processes; the stamps live in the database, not in a listener.

For Datomic, the managed relation stamp is the transaction component of the
current `:eacl/relation-version` datom, with the schema-initialized
`:eacl.relation/mutation-id` datom as the fallback for a relation that has
never been written. This supports the documented `tx-relationship` helper as
well as the public EACL writers. Datahike and DataScript use the transaction
component of the current relation mutation datom.

For a dependency set `D`, EACL validates that every dependency has a stamp and
computes:

```text
dependency-stamp = max(last-change-t(relation)) for relation in D
```

Under ordinary forward transactions, changing any relevant relation writes a
strictly newer transaction and therefore raises this maximum. An unrelated
relation write leaves it unchanged. The normalized internal query fixes `D`,
so equal maxima from different dependency sets cannot collide.

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

Expiry swaps the complete cache lifecycle atomically. In-flight work retains
only the old lifecycle and can publish only into that unreachable object, so a
late result cannot repopulate the new lifecycle.

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

Cursors are authenticated and scoped to backend/source, operation, query,
ordering, engine version, graph anchor, and an exact snapshot locator.

A cursor walk is exact-snapshot pinned:

1. decode and authenticate the cursor before it influences traversal;
2. continue on the identical current snapshot when still selected;
3. otherwise reconstruct the cursor's original exact snapshot;
4. bypass the completed-answer cache for that exact/historical work;
5. return a typed snapshot-expired or consistency-conflict error when the
   original snapshot is unavailable or violates an `at-least` floor.

EACL does not recalculate whole-graph or whole-result content proofs on every
page and does not rebase a cursor onto a newer merely proof-equivalent graph.
That removes both a mixed-snapshot loophole and the dominant proof cost
observed in the earlier v8 candidate.

Recursive continuation state is an optional performance optimization.
Continuation-store eviction causes deterministic replay against the same exact
snapshot; it cannot change the answer.

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

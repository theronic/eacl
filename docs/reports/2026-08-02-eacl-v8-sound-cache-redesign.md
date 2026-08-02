# EACL v8: sound, simpler cache and cursor architecture

Date: 2026-08-02

> **Superseded cache scope:** The later
> [single-database, current-snapshot design](2026-08-02-eacl-v8-single-db-current-cache-design.md)
> is authoritative for completed-answer caching. One cache instance is bound
> to one EACL client/database, arbitrary `db` and exact-snapshot operations
> bypass completed caching, and Datahike/DataScript both retain mandatory
> current-snapshot caches. This report remains evidence for the cache-free
> reference, formal proof gaps, algorithmic work, and measurements.

This report states the earlier base design. The later
[adversarial strategy review](2026-08-02-eacl-v8-strategy-adversarial-review.md)
is normative wherever it tightens a precondition or backend claim. In
particular, the assurance scope is ordinary forward transaction history.
Concurrent Datomic excision and other operations that rewrite history are
outside the live-operation contract; consumers must stop EACL traffic, finish
the operation, rotate the cache-and-cursor source incarnation, clear shared
state, recreate clients, and only then resume.

## Executive decision

EACL should formally specify and implement authorization **without a completed
answer cache first**, then prove each cache strategy is a refinement of that
cache-free evaluator on one selected immutable snapshot.

The current cache theorem is useful. The current production realization is not
a suitable default hot path. It applies authenticated portable envelopes,
canonicalization, causal-history checks, proof reconstruction, and validation
telemetry updates even to a client-local in-memory hit. That combines four
separate concerns:

1. selecting a snapshot;
2. evaluating authorization;
3. proving cross-snapshot observational equivalence; and
4. defending against an untrusted shared cache provider.

Those concerns should be separate layers. A trusted local cache does not need a
cryptographic wire protocol. An unknown-writer configuration does not need an
`O(G)` content proof merely to reuse an answer at the exact same immutable
revision. A stable page walk does not need to re-prove equivalence with the
latest graph on every page when it can remain on its original exact snapshot.

Confidence: **high, conditional on the explicit backend, writer, lifecycle,
and ABI assumptions in the adversarial review**. This is not an unconditional
claim about the backend runtime or operations outside the supported lifecycle.

## Evidence from the current worktree

The final heavy benchmark produced:

- ordinary warm `can?`: 412.58 µs;
- authenticated completed-cache hit: 3,035.542 µs, 7.357× slower;
- isolated repeat: 408.67 µs versus 2,815.02 µs, 6.888× slower;
- managed mutation-proof reads under unrelated churn: 2,122.708 µs versus
  269.896 µs with no completed cache;
- managed mutation-proof reads under relevant churn: 2,979.9585 µs versus
  336.979 µs with no completed cache;
- content-proof reads: approximately 2.9 ms in the small churn fixture;
- a previously recorded large content proof: approximately 670 ms per page.

The isolated hit breakdown attributed approximately:

- 421.724 µs to result-context capture;
- 332.768 µs to canonicalization;
- 8.150 µs to the underlying store lookup;
- 2,403.929 µs to the complete authorization-cache layer.

A deliberately incomplete native-key prototype on the same machine measured:

- exact-revision map hit: 0.083 µs;
- relation-epoch key hit with one dependency: 0.916 µs;
- native managed hit including `d/db`, two object resolutions, dependency
  lookup, schema generation, one relation-stamp read, key construction, and
  map lookup: 8.541 µs;
- local uncached evaluation of the same direct permission: 235.542 µs.

The prototype is not a production benchmark: it omitted provider hardening,
capacity accounting, typed validation, concurrency stress, and remote-cache
cost. It does demonstrate that the dependency-version theorem does not require
millisecond-scale machinery.

The heavy suite also shows where caching is worthwhile:

- a hot broad count took about 3.2 ms while the disabled-cache median was about
  620 ms;
- a 4,000-node recursive walk with retained continuation took about 112 ms
  while replay took about 1,781 ms;
- ordinary acyclic pagination already stayed around 1–3 ms per page.

The correct conclusion is not “remove every cache.” It is “stop treating every
cache as a completed-answer cache with the same security and consistency
protocol.”

## Consistency should be snapshot selection, not cache validation

Every operation must first select exactly one immutable database value `S`.
Every object lookup, schema read, traversal, cursor operation, and cache
decision for that request must use `S`.

EACL should expose these semantics:

| Mode | Semantics | Datomic | Datahike | DataScript |
| --- | --- | --- | --- | --- |
| `:local-snapshot` | newest complete value currently visible to this connection; no coordination | one `d/db` | one branch-head `d/db`/deref | one `ds/db` |
| `:at-least-as-fresh` | selected value contains the caller's causal floor | targeted `d/sync conn t` only when behind | not exposed initially; requires an adapter-certified ancestry/acquisition contract | only meaningful within one supplied connection and incarnation |
| `:at-exact-snapshot` | evaluate exactly the token revision | `d/as-of` at authenticated database id and `t` | retained commit id, or temporal history | bounded retained immutable DB |
| `:synchronized-head` | explicit cross-process/head barrier | zero-argument `d/sync` | only when the adapter can establish an authoritative branch head | same as local for a single in-process connection |

`:local-snapshot` should be the default read mode. It is locally coherent, not
globally freshest. Calling it `:fully-consistent` would be misleading.
`:synchronized-head` should remain explicit and relatively rare.

Datomic's `t` is safe as an order and, together with stable source identity and
externally rotated source incarnation, as an exact ordinary-history revision
within one forward database lineage. For adapter-owned current and `as-of`
values, normalize the effective revision to `d/as-of-t` when present and
otherwise `d/basis-t`. A current DB at `t` and `as-of t` can then share the same
exact key; the backing basis of the `as-of` wrapper is not semantic identity.
Arbitrary `since`, `history`, filtered, and speculative `with` values are
uncached unless the adapter certifies a complete stable identity for those
semantics. Datomic documents globally
ordered transactions, no gaps up to a peer's basis, monotonic operations on a
single peer, and targeted `sync` for cross-peer causal coordination. A random
mutation ancestry graph is not needed to prove ordinary Datomic `t` ordering.

That simplification must not be copied blindly to Datahike or DataScript:

- Datahike branch/commit identity, not `:max-tx` alone, distinguishes divergent
  histories.
- DataScript can reset a connection to a different immutable value whose
  transaction counter is not a globally unique lineage identity.

Confidence: **high** for Datomic, **moderate** for the exact Datahike API
surface because branch/commit retention varies by configuration, **high** for
the deliberately restricted DataScript claim.

## Three cache classes, three different contracts

### 1. Derived-schema cache

Always retain this cache. It stores:

- normalized permission paths;
- permission dependency closure;
- recursive SCC classification;
- deterministic path ordering;
- direct-grant relation metadata.

Key it by:

```text
[semantic-ABI
 adapter-ABI
 stable-source
 source-incarnation
 schema-generation-from-selected-snapshot]
```

Any schema write changes `schema-generation` and swaps the whole generation.
Old generations become unreachable and may be reclaimed asynchronously. EACL
should not attempt partial schema-cache retention in v8. Schema writes are rare,
and a global generation change is both cheaper and easier to prove.

Read `schema-generation` from the one immutable snapshot selected for every
request; never latch it for the lifetime of a client. For managed writers, it
is one indexed value changed atomically with the schema transaction. For
unknown writers, compiled-plan reuse is limited to the exact snapshot unless a
complete schema-generation contract is independently certified. Do not rescan
and hash the entire schema on every authorization request.

### 2. Traversal/continuation cache

Retain bounded client-private recursive continuations and completed recursive
pages. They prevent superlinear prefix replay and limit-exceeded false denials.

Key them by source incarnation, the exact selected snapshot, normalized query,
direction, semantic and adapter ABI, client capability, and deterministic
frontier identity. Single-flight/coalescing uses that same complete key. Treat
them as accelerators:

- hit: resume deterministic state;
- miss/eviction: replay against the same exact snapshot;
- malformed/untrusted value: reject and replay;
- unavailable exact snapshot: typed snapshot-expired error.

The continuation provider is trusted if it is private process memory. A remote
provider either needs authenticated state or must be treated solely as an
untrusted hint that is independently checked before it can influence emitted
grants.

### 3. Completed-answer cache

Do not enable one universal completed-answer strategy. Provide two explicit
strategies.

#### Exact-revision cache

This is the safe completed-cache strategy for unknown writers **only when the
adapter supplies a certified exact immutable-view identity**:

```text
key =
  [semantic-ABI
   adapter-ABI
   stable-source
   source-incarnation
   exact-logical-view-identity
   normalized-query
   cached-result-layer]
```

It requires no relationship content proof. Immutable snapshot identity equality
is the validity proof. An ordinary Datomic transaction changes the effective
revision; a
Datahike commit changes the branch/commit identity; and a DataScript exact key
uses a client incarnation plus an opaque retained immutable-DB handle rather
than `:max-tx`. Arbitrary filtered/speculative views and unretained snapshots
miss or return a typed unsupported/expired error. This is coarse, but its hit
path is extremely cheap.

#### Managed dependency-epoch cache

This is the optimized cross-revision strategy:

```text
key =
  [semantic-ABI
   adapter-ABI
   stable-source
   source-incarnation
   schema-generation-from-selected-snapshot
   identity-generation-from-selected-snapshot
   auxiliary-generations-from-selected-snapshot
   normalized-query
   max-relevant-relation-stamp-from-selected-snapshot
   cached-result-layer]
```

Every relationship mutation updates the version of each affected relation
definition in the same database transaction. Each new version must be unique
within the source lineage. A schema mutation changes the global schema
generation. Mutable identity conversion, caveats, and custom authorization
inputs must either be immutable by contract or contribute their own dependency
generation.

On Datomic, relation/schema versions can use the transaction reference or `t`;
a separate random mutation entity is unnecessary for cache equality. A scalar
maximum over only the permission's complete relation dependency set is sound
for current forward-linear Datomic operation because every relevant change
receives a new globally greater transaction stamp. It is not a generic backend
algorithm. Managed cross-revision caching is deferred for Datahike until
branch, merge, force/reset, garbage-collection, and distributed acquisition
semantics are proved. DataScript stays with process-local exact immutable
handles.

The cache does not need to establish that the computation snapshot is an
ancestor of the selected snapshot. It needs to establish observational
equivalence on the complete dependency set. If the selected snapshot's
schema/identity/auxiliary generations and relevant relationship stamp equal the
entry's key under the exclusive managed-writer invariant, the authorization
result is equal once the ReBAC frame theorem is proved. Restore, clone
adoption, reseed, and any out-of-scope history rewrite rotate the external
source incarnation; they do not rely on numeric version reuse being harmless.

## Trusted local and untrusted remote caches must not share a hit path

The default local in-memory cache is already part of the process trust
boundary. Authenticating its own values on every lookup adds latency without
removing a meaningful attacker.

Use:

- L1 trusted local cache: native immutable keys and typed values, no
  serialization, no HMAC, no per-hit rewrite;
- optional L2 shared/untrusted cache: fixed binary or strict canonical wire
  format plus HMAC, source/namespace binding, size limits, and typed decoding;
- optional L1 of already authenticated L2 results to avoid repeated remote
  decoding and MAC verification.

`validated-at` should not rewrite an entry on every hit. Telemetry is not part
of the authorization theorem. Sample it, keep it in process-local counters, or
update it asynchronously.

Provider failures remain misses. A cache provider may never turn failure into
allow, deny, partial page, or partial count.

## Cursor redesign

The default page cursor should pin the exact snapshot selected for page 1.
That is the simplest stable-pagination contract and matches the standard use of
exact snapshots for pagination.

A cursor needs:

```text
[format-version
 semantic-ABI
 adapter-ABI
 stable-source
 source-incarnation
 exact-logical-view-locator
 normalized-query-digest
 operation/result-kind
 direction
 deterministic frontier/offset
 issued-at/expires-at]
```

It must be authenticated at the public boundary. Datomic may continue
encrypting internal entity ids. It does not need to reconstruct and compare a
complete relationship content proof on every page.

Resume algorithm:

1. authenticate and validate cursor/query scope;
2. acquire the cursor's exact immutable snapshot;
3. resume the deterministic frontier on that snapshot;
4. use a private continuation if present, otherwise replay deterministically;
5. return a typed retention error if the exact snapshot is unavailable.

An optional `:rebase-if-equivalent` cursor mode may select a newer snapshot and
compare managed dependency epochs. It should not be the first implementation or
the default. If the caller supplies a newer `:at-least-as-fresh` floor during an
existing exact walk, either reject the incompatible combination or require a
new walk. Mixing “stable walk” and “advance to latest” semantics is the source
of avoidable cursor complexity.

## Formal verification structure

Define the cache-free reference:

```text
Authorize(S, Q) -> Result | TypedError
```

where `S` is one immutable snapshot selected before evaluation. The reference
contains no completed answer cache, no cursor rebasing, no provider, no
telemetry, and no clock-dependent optimization.

There should be two reference implementations:

1. a small executable mathematical evaluator over a finite materialized graph,
   used as the specification and differential oracle;
2. a production-capable cache-free generated kernel over the abstract snapshot
   adapter, used for real requests.

The first alone does not verify production. The complete assurance claim
requires the public operation to execute generated verified traversal code, or
requires a separately proved translation/refinement from production code to the
model.

Prove these refinements independently:

### Exact cache theorem

If a cache entry was computed by `Authorize(C,Q)` and the selected snapshot
identity equals `C`, returning the entry equals `Authorize(S,Q)`.

### Managed epoch frame theorem

Let `Deps(Schema,Q)` be complete. If:

- schema, identity, and auxiliary generations are equal;
- every dependency version in `Deps` is equal;
- the adapter obeys the atomic version-update contract;

then:

```text
Authorize(C,Q) = Authorize(S,Q)
```

This is the theorem the cache design needs. The current Dafny cache kernel
proves accepted-value equality only under `CompleteProofContract`; that
contract assumes the crucial result-equivalence fact. It is therefore a
conditional cache proof, not yet the ReBAC frame theorem itself. The remaining
proof must derive completeness of permission/relation dependencies and
least-fixed-point result equality from the cache-free semantics. It does not
require portable envelope canonicalization or a causal graph.

### Cache implementation theorem

For every store state and provider failure:

```text
Resolve(strategy, S, Q) = Authorize(S, Q)
```

The only observable differences may be cache provenance, latency, and bounded
capacity telemetry. A cross-revision entry contains only a
snapshot-independent semantic layer; rendering, freshness tokens, snapshot
metadata, and cursors are rebuilt for the selected `S`, never copied from the
entry's computation snapshot.

### Cursor theorem

Concatenating every valid page on one exact snapshot produces the deterministic
full sequence once, without omissions or duplicates. Continuation eviction and
replay are observationally equivalent.

### Temporal/race theorem

Snapshot selection happens once. Cache lookup/publication and continuation
lookup/publication races cannot change the snapshot, result, page membership,
or typed failure selected for the request. Publication and single-flight keys
must contain the same full versioned key used for lookup; coalescing merely by
query can mix snapshots.

## Backend-specific design

### Datomic

Use Datomic's strengths directly:

- `d/db` selects the locally current immutable snapshot;
- `[stable-source source-incarnation effective-t]` identifies adapter-owned
  current-unfiltered and `as-of` DBs, where `effective-t` is `as-of-t` when
  present and `basis-t` otherwise;
- signed `[stable-source source-incarnation t]` is sufficient for a Datomic
  causal floor;
- targeted `d/sync conn t` is used only when the local peer is behind;
- zero-argument `d/sync` is explicit synchronized-head behavior;
- `d/as-of` implements exact cursor walks;
- relation and schema versions use transaction identities updated atomically;
- no cache-correctness listener is required.

The managed strategy is valid only if every relationship mutation—including
retractions—atomically writes the affected relation stamp and every schema
write atomically changes the schema generation. Raw unstamped writers are
unsupported; a listener can diagnose violations after the fact but cannot
prevent the stale-serving race. Concurrent excision is outside scope. Restore,
clone adoption, reseed, or a completed history rewrite requires the
cache-and-cursor lifecycle reset described at the top of this report.

The current random mutation ancestry journal is substantially more machinery
than ordinary Datomic needs. If write-idempotency records remain useful, keep
them as a write API feature; do not make them the cache/freshness identity.

### Datahike

Use `[store-incarnation branch commit-id]` as the exact identity. Do not infer
ancestry from `:max-tx`. Exact cursors use retained commits where the deployed
Datahike version and garbage-collection configuration guarantee acquisition.
The initial v8 adapter exposes cache-free and exact-commit caching only.
Managed dependency epochs and generic at-least freshness wait for proofs of
merge, force/reset, garbage collection, and distributed branch acquisition.

### DataScript

Optimize for the real use case: one in-process demo connection.

- local snapshot is authoritative for that connection;
- exact keys use a client/connection incarnation plus an opaque retained
  immutable-DB handle; `:max-tx` does not prevent reset or lineage collisions;
- no cross-process shared answer cache by default;
- no claim that polling creates distributed freshness;
- retain CLJ/CLJS parity and deterministic exact cursors within the bounded
  snapshot registry.

Do not force Datomic and Datahike complexity into DataScript merely to present
an identical internal implementation.

## Low-hanging algorithmic work

1. **Land the linear recursive-routing compiler.** The current worktree still
   computes a reachability closure for every reachable node:
   `O(V(V+E))`, repeated per cold permission root. PR #91 commit `a2b32f3`
   replaces it with iterative Kosaraju SCC plus reverse reachability:
   `O(V+E)` graph analysis once per schema generation and constant-time root
   classification afterward.
2. **Delete hot-path full-content proofs.** DataScript currently queries the
   whole relationship set and filters it; Datomic/Datahike scan broad
   relationship storage before sorting matching records. Exact-revision caching
   makes this unnecessary for unknown writers.
3. **Stop canonicalizing native local keys.** Precompute normalized query and
   plan identities in the schema generation. Use native vectors/maps with
   fixed fields.
4. **Avoid per-hit authenticated entry rewrites.** They add allocation, crypto,
   contention, and provider I/O without changing authorization.
5. **Exploit ordered backend scans.** Datahike/DataScript adapters currently
   sort complete id collections in `apply-scan-window`. Replace this with
   ordered index/range scans where the backend supports them; certify ordering
   at the adapter boundary.
6. **Cache only when avoided work can exceed validation work.** Cheap direct
   `can?` should normally bypass completed-answer caching. Counts, deep
   recursive checks, and expensive repeated subproblems are candidates.
7. **Prefer subproblem memoization inside one request.** Exact-snapshot
   memoization of `(permission-node, subject, resource)` avoids duplicate
   recursive/diamond work without any cross-revision invalidation theorem.
8. **Retain deterministic path cost ordering.** Cheap direct relations should
   short-circuit before arrows and recursive subproblems.
9. **Stream bounded counts.** Stop as soon as `count-limit` proves truncation;
   do not materialize a complete result only to discard it.
10. **Separate result storage from boundary coercion.** Cache stable internal
    ids and coerce against the selected exact snapshot, with identity
    generation included when mappings are mutable.

## Recommended v8 public configuration

Keep the public surface small:

```clojure
{:consistency-default :local-snapshot
 :answer-cache :none} ; safest initial v8 default
```

Optional:

```clojure
{:answer-cache {:strategy :exact-revision
                :store local-store}}

{:answer-cache {:strategy :managed-epochs
                :store local-store
                :writer-contract :eacl-only}}

{:answer-cache {:strategy :remote-authenticated
                :l1 local-store
                :l2 remote-store
                :keyring ...}}
```

Schema compilation and bounded continuation caching remain internal and enabled
independently of completed-answer caching.

For a restore, clone adoption, reseed, or consumer-managed operation that can
rewrite history, cache expiration is an atomic source-incarnation rotation,
not a store clear under the same key namespace. Stop old-client publication,
provision the same new external incarnation to every peer, reject old tokens,
clear local continuations/answers, purge the old remote namespace
asynchronously, and recreate clients before traffic resumes. Ordinary requests
do not read an excision or destructive-operation generation.

Remove or internalize the current public complexity around content proof mode,
causal graph retention, checkpoint tuning, and universal authenticated local
entries. Unknown writers receive exact-revision caching, not expensive proof
lifting. Managed writers receive dependency epochs.

## Implementation order and gates

1. Freeze the cache-free snapshot semantics and make all cache-disabled public
   operations differential against the generated reference.
2. Make `:local-snapshot` the default; make synchronized head explicit.
3. Make exact-snapshot cursors the default and retain deterministic replay.
4. Land the once-per-schema linear SCC analysis.
5. Prove the exact-cache theorem and implement exact caching only on certified
   logical-view identities.
6. Prove the ReBAC dependency-closure/frame theorem; only then replace the
   mutation-graph validity path with the Datomic managed-stamp strategy.
7. Add adaptive admission by measured plan/work class; default cheap direct
   checks to bypass.
8. Add the optional authenticated remote wrapper only after the local path is
   fast and proved.
9. Shadow the complete generated engine against cache-free and cached public
   clients on each adapter strategy actually claimed above.
10. Cut over only after zero unexplained semantic divergences and ratio-based
    performance gates.

Suggested performance gates are ratios, not host-specific absolute times:

- a trusted local managed hit should be at most 20% of cache-free
  recomputation for a workload where completed caching is enabled;
- enabling a cache on a miss-dominated workload should add at most 10% p95
  latency;
- an unrelated write should preserve a managed-epoch hit;
- every relevant relationship, schema, identity, caveat, or declared custom
  dependency mutation must force a miss;
- unknown-writer hit cost must not grow with total graph size;
- cursor page cost must not grow with already emitted result count when a
  continuation is retained;
- no performance optimization may change allow/deny, membership, ordering,
  page flags, counts, selected snapshot, or typed errors.

## Sources

- Datomic synchronization:
  <https://docs.datomic.com/transactions/client-synchronization.html>
- Datomic transaction ordering and single-peer monotonicity:
  <https://docs.datomic.com/transactions/acid.html>
- Datomic consistent immutable DB values:
  <https://docs.datomic.com/reference/best.html>
- SpiceDB consistency modes:
  <https://authzed.com/docs/spicedb/concepts/consistency>
- SpiceDB read-after-write guidance:
  <https://authzed.com/docs/spicedb/concepts/read-after-write>
- Zanzibar:
  <https://www.usenix.org/system/files/atc19-pang.pdf>
- AWS verified authorization engine methodology:
  <https://www.amazon.science/publications/formally-verified-cloud-scale-authorization>
- EACL PR #91:
  <https://github.com/theronic/eacl/pull/91>

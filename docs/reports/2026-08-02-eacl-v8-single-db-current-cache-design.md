# EACL v8 single-database, current-snapshot cache design

Date: 2026-08-02

## Status

This document supersedes the completed-answer cache architecture in the two
earlier reports:

- `2026-08-02-eacl-v8-sound-cache-redesign.md`;
- `2026-08-02-eacl-v8-strategy-adversarial-review.md`.

Their formal-method findings, performance measurements, dependency-frame
analysis, cursor findings, and loophole register remain evidence. The
multi-source cache keys and time-travel answer-cache strategy do not.

The design is now implemented for Datomic, Datahike, and DataScript. Its named
current-cache and ReBAC frame obligations verify in
`formal/dafny/CurrentCache.dfy`; the remaining qualifications in this report
describe the writer/lifecycle boundary, not unfinished cache code.

## Binding constraints

1. One EACL client is bound to exactly one Datomic database, one Datahike
   connection/branch, or one DataScript connection.
2. One completed-answer cache instance/namespace belongs to that client and
   database. Sharing it with another database is unsupported.
3. Public connection-backed authorization evaluates the current immutable
   snapshot selected once at request start.
4. Calls to low-level EACL functions with an arbitrary `db` bypass the
   completed-answer cache.
5. `:at-exact-snapshot` and exact cursor replay may use `d/as-of` or a backend
   exact selector, but bypass the completed-answer cache.
6. The completed-answer cache never stores or searches historical revisions.
7. Datahike and DataScript both ship with a completed-answer cache. Datahike
   is the higher-priority optimized target.
8. Schema writes go through `eacl/write-schema!`. Relationship writers outside
   EACL either receive exact-current caching only or explicitly satisfy the
   managed-writer contract.
9. Reset, force-head, restore, history rewrite, and similar lifecycle
   operations are not ordinary cache transitions. The consumer expires/replaces
   the EACL cache at the correct quiescent point.

## Decision

Each backend has two client-local completed-cache tiers:

1. a mandatory exact-current generation cache;
2. a managed current-snapshot dependency cache for EACL-stamped writers.

The cache-free evaluator remains the semantic reference and cache-miss path.
It is not the production recommendation for Datahike or DataScript.

```text
Select current immutable S exactly once
        |
        +-- exact-current generation hit -> semantic value
        |
        +-- managed-current hit, if enabled -> semantic value
        |
        +-- cache-free evaluation on S -> semantic value -> publish
        |
        +-- render against S
```

An exact request follows a separate path:

```text
authenticate exact token
        |
select exact S (for Datomic, d/as-of)
        |
bypass completed-answer caches
        |
evaluate and render against exact S
```

## Client-bound namespace

The cache instance, rather than each hot key, binds:

```text
semantic ABI
adapter ABI
one EACL client
one connection/database
lifecycle generation
```

Therefore the local hot key does not contain:

- database id;
- source id;
- store id;
- branch;
- source incarnation;
- backend name;
- deployment id.

If a remote provider is later used, these values are bound once into the
provider namespace/domain at client construction. They are not repeatedly
allocated and compared in every native L1 key.

Construction must reject accidental reuse of one mutable local cache object by
two clients. Reuse of a physical remote service is allowed only through
distinct prebound namespaces; EACL does not attempt to compare arbitrary
databases at lookup time.

## Mandatory exact-current generation cache

### State

```text
ClientCache.current ->
  Generation {
    selected-head-token
    selected-db-reference
    entries
    single-flights
  }
```

An entry key inside one generation is only:

```text
[normalized-semantic-query cached-result-layer]
```

### Head tokens

- Datomic: current `d/basis-t`.
- Datahike: identity of the selected immutable DB object. The commit id from
  `[:meta :datahike/commit-id]` remains diagnostic/token metadata, not the
  local exact-cache discriminator.
- DataScript: a private opaque handle registered for the selected immutable DB
  object; do not use structural equality or `:max-tx`. The registry tests
  object identity and retains a bounded set for exact cursor recovery.

### Generation change

After selecting current `S`, EACL obtains its head token:

1. if the current generation names `S`, use it;
2. otherwise atomically install a fresh empty generation for `S`;
3. the request captures the generation object it used;
4. publication and single-flight completion write only to that captured
   generation;
5. a late old publication may mutate an unreachable old generation but cannot
   repopulate the new one.

This avoids placing a revision in every entry key and closes the
delete-then-late-publication race.

### Soundness

Within one generation:

```text
same immutable current S
+ same normalized semantic query
+ same semantic/adapter ABI bound by the cache instance
=> same semantic result
```

No relationship content proof, mutation graph, HMAC, canonicalization, or
listener is needed for a private L1 exact-current hit.

### DataScript reset

DataScript's immutable DB object identity automatically changes the exact
generation even if `reset-conn!` reuses a numeric `:max-tx`. This makes the
exact-current tier safe without listener timing.

Managed entries are different: a reset can reuse old semantic stamps, so a
consumer using reset/rewind with managed caching must quiesce and expire the
client cache.

## Managed current-snapshot dependency cache

### Existing storage is sufficient

Every EACL schema mutation updates:

- `:eacl.schema/mutation-id`;

in the same transaction as the logical change. Relationship mutations update a
backend-specific current relation stamp in the same transaction:

- Datomic: `:eacl/relation-version`, with
  `:eacl.relation/mutation-id` as the schema-created fallback before the first
  relationship write;
- Datahike/DataScript: `:eacl.relation/mutation-id`.

These are cardinality-one current datoms. The transaction component of the
current datom is exactly the last transaction that changed that stamp. EACL
therefore does not need another cache-specific stamp attribute and does not
need the random mutation-id string in the hot key.

For a Datahike/DataScript relation definition `r`:

```text
[r :eacl.relation/mutation-id _ tx]
                                   ^^
                                   relation last-change stamp
```

For the global schema generation:

```text
[schema-entity :eacl.schema/mutation-id _ tx]
                                             ^^
                                             schema generation
```

### Managed key

The cache namespace already binds the client, database, backend, lifecycle,
semantic ABI, and adapter ABI. A schema-generation object binds the current
schema transaction stamp and owns the managed entries. The hot managed key
inside that object is:

```text
[normalized-internal-semantic-query
 max-relevant-relation-change-t-from-S
 cached-result-layer]
```

All values are read from the same selected immutable current snapshot `S`.

There is no identity stamp in the default-codec key. Public IDs are resolved
to internal entity IDs before lookup and those internal IDs are part of the
normalized query. Cached internal results are rendered back to public IDs
against selected `S`. Renaming a query endpoint therefore either fails current
resolution or produces a different internal/public query key; renaming a
result endpoint changes current rendering, not the cached authorization set.

This simplification is valid only for the certified
`selected-internal/current-external` identity contract. A custom codec is
exact-current-only unless its maintainer supplies a complete dependency stamp
and frame theorem. Future caveats, contextual attributes, or other
authorization inputs likewise require an explicit dependency class before
managed caching is enabled for them.

### Scalar maximum

For current forward operation on all three pinned backends:

1. every ordinary transaction gets a transaction id greater than the current
   database `:max-tx`/basis;
2. every affected relation stamp is rewritten in that transaction;
3. the current stamp datom therefore carries that new transaction id;
4. changing any relevant relation raises the maximum over the dependency set;
5. an unrelated relation change does not alter that maximum.

This argument does not support reset, restore, branch force, or history rewrite.
Those operations use explicit lifecycle expiry.

### Missing stamps

Dependency stamp extraction returns both count and maximum. Managed caching is
allowed only when:

```text
number of distinct stamped relation definitions
    = number of compiled relation dependencies
```

An empty dependency set currently falls back to exact-current reuse. A missing
or malformed stamp likewise disables managed caching or returns an integrity
error; it never becomes an ordinary reusable zero.

### Writer contract

The EACL mutation transaction atomically stamps:

- additions;
- retractions;
- deletion of objects with relationships;
- both old and new relations during retargeting;
- integrity repair;
- bulk import;
- schema replacement;
- every future mutable attribute that is admitted into authorization
  semantics.

Raw relationship changes that do not update these stamp datoms receive only
exact-current caching. A listener may report violations but is not part of the
soundness argument.

## Backend implementations

### Datomic

Exact-current generation:

```text
head = d/basis-t(S)
```

Managed stamp extraction reads the current transaction component of
`:eacl/relation-version`, falling back to the initialized mutation datom.
Datomic transaction ids are the natural stamps. This exact choice is required:
the documented raw `tx-relationship` helper updates relation-version.

Normal current operations never call `d/as-of`. `d/as-of` is used only after
an authenticated exact-snapshot request/cursor selects that explicit mode, and
the completed-answer cache is bypassed.

### Datahike 0.8.1759

Exact-current generation:

```text
head = object identity of S
```

The pinned source establishes:

- ordinary transaction processing uses `current-tx = inc max-tx`;
- the transaction loop increments `:max-tx`;
- one writer serializes ordinary commits;
- the commit loop installs the committed DB, including commit id, before
  completing the transaction report and invoking listeners;
- `:db/current-tx` is accepted in entity and ref positions.

The cache still reads only immutable `S`; listener ordering is diagnostic, not
part of correctness.

An nREPL probe against the pinned version established that repeated `d/db`
calls return the identical immutable DB object while the connection is
unchanged and a successful transaction replaces it with a different object.
Reference identity is therefore both cheaper and stronger than trusting commit
id uniqueness for a private current-generation cache. If a future Datahike
version stops preserving reference identity, correctness is unchanged and the
exact tier merely misses until the adapter certification/performance test
selects another collision-free local identity.

For managed dependency extraction, the measured preferred primitive is one
scoped Datalog query returning relation entity and datom transaction pairs,
followed by a count check and scalar maximum.

Datahike branch force, merge data that bypasses EACL stamping, restore, and
connection replacement require cache expiry/recreation. An EACL client remains
bound to one configured branch during ordinary operation.

### DataScript 1.7.8

Exact-current generation stores an opaque per-immutable-DB handle. Registry
lookup tests `identical?`/JavaScript object identity; the handle, not a numeric
transaction counter or structural hash, becomes the exact locator.

For managed dependency extraction, direct EAVT reads for the small precompiled
dependency vector measured faster than a Datalog query. Each current datom's
transaction component supplies the stamp.

DataScript listeners run after `transact!` updates the connection and after
`reset-conn!` replaces it. Therefore listener-only managed invalidation has a
race and is rejected. Exact-current object identity remains safe; managed mode
requires EACL-stamped transactions and lifecycle expiry around reset.

## Schema behavior

`eacl/write-schema!` updates the schema mutation datom in the schema
transaction. Each request reads its transaction component from selected `S`
and looks up the compiled plan generation.

An actual schema change makes every old plan and managed answer unreachable.
The whole old schema generation is dropped. Partial retention is not a v8
requirement.

A semantic no-op may retain the generation only when normalized semantic IR is
equal under the same semantic ABI. Text equality alone is insufficient.

## Cursor and exact semantics

The completed-answer cache does not support time travel.

- Arbitrary low-level `db` operations bypass it.
- `:at-exact-snapshot` bypasses it.
- Historical cursor replay bypasses it.
- A cursor may use a separate bounded private continuation store. That store is
  not the completed-answer cache and is keyed by the authenticated exact cursor
  scope. On a miss it replays against the cursor snapshot.

This retains stable pagination and recursive continuation performance without
turning the answer cache into a historical database.

## Result layer

Both exact-current and managed caches store a snapshot-independent semantic
layer:

- a boolean decision;
- a complete canonical internal id set/sequence;
- a complete count.

They do not store:

- signed cursors;
- causal/exact tokens;
- selected-snapshot metadata;
- issue/expiry clocks;
- partial limit-exceeded results;
- provider errors.

Rendering, page metadata, and cursor construction use selected `S`.

## Expiry

For local caches, expiry swaps the entire `Generation`/managed-cache state
object. Old in-flight work publishes only to its captured unreachable object.

For a remote provider, expiry changes the prebound namespace before physical
deletion. Physical deletion is asynchronous capacity cleanup.

There is no database/source-incarnation field in every hot key and no
excision/history generation read on ordinary requests.

## Primitive measurements

Single-thread nREPL microprobe, in-memory stores, 16 dependencies:

| Operation | Datahike | DataScript |
| --- | ---: | ---: |
| Current-head field read | 0.076 µs | 0.041 µs |
| One scoped Datalog datom-tx query | 5.878 µs | 28.479 µs |
| Sixteen direct EAVT datom-tx reads | 18.214 µs | 4.326 µs |
| Sixteen entity mutation-value reads | 21.413 µs | 6.877 µs |

These are primitive-selection measurements, not production latency claims.
They identify the initial implementation choice:

- Datahike: scoped query;
- DataScript: direct EAVT;
- Datomic: benchmark both on representative Peer data.

The selected stamp path is orders of magnitude below the existing
authenticated content-proof cache hit measured in the earlier report.

## Production-backed stamp evidence

The existing EACL writers were exercised, rather than only a synthetic
`:db/current-tx` attribute:

- the Datahike and DataScript mutation suites read the current datom for each
  affected `:eacl.relation/mutation-id`;
- after an ordinary relationship write, each current datom's transaction
  component equalled the backend's current `:max-tx`;
- after cascading object deletion, every affected relation's replacement
  mutation datom again carried the deletion transaction's `:max-tx`;
- the combined CLJ run passed 6 tests and 47 assertions;
- focused Datahike/DataScript adapter checks passed 4 tests and 9 assertions,
  including stable/replaced immutable DB reference identity;
- the complete DataScript CLJS run passed 74 tests and 1,148 assertions;
- the current-generation/scalar-stamp adversarial model passed 7 tests and 474
  assertions in CLJ and as part of the CLJS run.
- the complete non-benchmark CLJ run passed 361 tests and 12,991 assertions;
- the heavy performance run passed 9 tests and 3,403 assertions.

These tests establish the pinned adapters' extraction premise for the exercised
writes and connect the verified frame theorem to the exercised production
boundary. They cannot make an uninstrumented writer obey the managed-writer
contract.

## Adversarial loophole audit

| Loophole | Required closure |
| --- | --- |
| A caller reuses one mutable cache object for two clients | The generation/cache owner is constructed and retained inside one client. An external provider must be wrapped in a distinct prebound namespace; a raw mutable generation object is not a public shareable option. |
| Snapshot selection races a concurrent commit | Select immutable `S` once, then run a CAS loop that returns/installs the generation for that exact `S`. Evaluation, dependency stamps, rendering, and publication all use captured `S`; never reread `conn` midway. |
| Old work publishes after a generation swap | Lookup, single-flight, and publication capture the generation object. Publication never resolves “the current generation” a second time. Old publication can only mutate the unreachable old object. |
| Numeric revision ABA after reset/restore | Datahike/DataScript exact tiers use DB object identity, not `:max-tx`. Datomic reset/restore/history manipulation and every managed-tier rewind are explicit lifecycle-expiry operations. |
| Commit-id/hash collision | Datahike's local exact tier does not use commit id as its discriminator. Authenticated cursors still rely on their documented cryptographic/locator assumptions, separately from completed-answer caching. |
| A caller supplies a historical/arbitrary `db` | Only the connection-backed public-client entry point enables completed caching. Low-level `db` entry points set `completed-cache? = false`; comparing the supplied DB to current is an optional optimization, not the security boundary. |
| Exact consistency or historical cursor replay consults current cache | The selected request context carries `completed-cache? = false`. The resolver rejects lookup and publication when false; this is tested at the orchestration boundary, not inferred from key mismatch. |
| Current request accidentally calls `as-of` | Ordinary modes have no exact locator selection branch. `as-of`/`commit-as-db` is reachable only after authenticated explicit exact selection or cursor fallback, both with completed caching disabled. |
| Listener invalidation races a reader | Listeners are not a correctness mechanism. The exact tier compares captured DB identity; the managed tier reads stamp datoms from captured `S`. |
| Relevant raw write omits a stamp | Such a client is `:unknown` writer authority and gets exact-current caching only. Managed mode is an explicit contract; missing count/stamps fail closed to exact-only or error. |
| A relation dependency is omitted by the compiler | The least-fixed-point dependency/frame proof and differential mutation controls close the supported schema forms. A new rule/caveat form must extend that proof before managed reuse; exact-current remains the fallback. |
| Two different dependency vectors have the same scalar maximum | The query key fixes the compiled dependency set. Under forward stamped writes, changing any member writes a strictly greater transaction id, so the new maximum is greater. Rewind/restore is excluded and expires the lifecycle. |
| A stamp is retracted or malformed | Extract `(distinct-count, max)` and compare the count to the compiled dependency count. Never coerce absent data to zero. |
| Multiple relevant relations change in one transaction | They may share one transaction component; the shared strictly newer maximum correctly invalidates every affected query. Uniqueness per relation is unnecessary. |
| Schema changes while old work is running | Schema mutation swaps the schema/answer generation. Old work publishes only to its captured unreachable generation. Default policy drops all managed answers and compiled plans. |
| Public identity changes without a relation write | Under the default codec, resolve query endpoints and render result endpoints against captured `S`; cache only internal semantic values. A custom codec remains exact-only unless it proves an additional dependency frame. |
| Attributes or caveats affect authorization without a relation write | They must be explicit semantic dependency classes with stamps and frame lemmas. Until implemented, the affected operation disables managed caching while exact-current caching remains valid. |
| Cached internal IDs are rendered using a newer DB | Render from the same captured `S` used for lookup/evaluation, never from a fresh `d/db`. Cache only snapshot-independent semantic values. |
| Partial/limited traversal is mistaken for a complete answer | Never cache partial limit failures, provider errors, signed cursors, or page-local fragments as complete semantic results. Cache only a proved complete boolean, canonical sequence/set, or count layer. |
| Cache provider returns malformed or cross-ABI data | Private native L1 values use typed in-process entries. A remote L2 must authenticate/validate values and bind semantic ABI, adapter ABI, client namespace, and lifecycle generation once. Provider failure is a miss, never allow. |
| Cache capacity eviction races single-flight | Eviction may cause duplicate computation but cannot change the selected result. A waiter accepts only the value published by the flight for its captured generation/query or recomputes on captured `S`. |
| Process crash occurs between logical write and stamp | Logical change and stamp are one backend transaction, so neither is visible without the other. A process-local cache disappears on crash; a remote namespace is advanced only from committed state. |

No finite audit can establish literal certainty about production composition.
The defensible closure criterion is: the seven proofs below are complete, the
adapter premises are certified on pinned versions, mutation controls kill each
listed broken implementation, and differential tests compare every public
cached operation with the cache-free evaluator. Those cache-specific criteria
are now satisfied; broader end-to-end engine verification remains separately
withheld in the release manifest.

## Discharged cache proofs

1. **Exact-current generation theorem:** a request can read only entries
   computed on its selected immutable current `S`.
2. **Late-publication theorem:** publication/single-flight against an old
   generation is unreachable after a head/lifecycle swap.
3. **Stamp extraction theorem:** returned count/maximum covers exactly the
   compiled dependency relation definitions in `S`.
4. **Forward scalar theorem:** any relevant EACL-stamped forward transaction
   raises the dependency maximum.
5. **ReBAC frame theorem:** under the certified default identity contract,
   equal schema generation, normalized internal query, and complete relevant
   relationship projections imply equal internal semantic result; rendering
   that result against selected `S` refines the public operation.
6. **Current-only theorem:** exact/arbitrary-db modes cannot consult or publish
   completed-answer entries.
7. **Public refinement theorem:** cached semantic values are rendered against
   selected `S` and produce the same complete public result as cache-free
   evaluation.

All seven are represented by verified lemmas in
`formal/dafny/CurrentCache.dfy`. They remain conditional on adapter stamp
extraction, the managed-writer contract, immutable selected snapshots, and the
default identity contract named above.

## Implemented rollout

1. Mandatory exact-current generation caches ship for all three backends.
2. Arbitrary `db`, exact consistency, and historical cursor replay bypass
   completed answers.
3. Backend-specific transaction-stamp extraction uses existing datoms.
4. Generation, publication, stamp, current-only, frame, and rendering lemmas
   verify.
5. Managed-current caching is available under explicit managed authority.
6. Direct, recursive, lookup, count, unrelated-write, and relevant-write
   workloads are covered by regression and heavy suites.
7. Content/dependency proof calculation is absent from ordinary exact hits.
8. Portable external providers are not trusted for completed native answers.

## Confidence

| Claim | Confidence |
| --- | --- |
| One client-bound cache removes per-key source identity | High |
| Completed-answer cache should be current-only | High |
| Exact/arbitrary-db requests should bypass answer caching | High |
| Mandatory exact-current Datahike cache | High |
| Mandatory exact-current DataScript cache by DB identity | High |
| Datom transaction component is a usable managed stamp | High for pinned normal-operation semantics |
| Scalar maximum across complete dependencies | High, conditional on forward stamped-writer/lifecycle contract |
| Complete managed ReBAC cache correctness | High under the stated compiled-dependency, default-identity, stamped-forward-writer, and lifecycle contracts |

## Sources

- Datomic API:
  <https://docs.datomic.com/clojure/index.html>
- Datahike current/time-varying DB values:
  <https://cljdoc.org/d/org.replikativ/datahike/0.8.1715/doc/core-features/time-variance>
- Datahike distributed single-writer architecture:
  <https://cljdoc.org/d/org.replikativ/datahike/0.8.1744/doc/core-features/distributed-architecture>
- Datahike versioning:
  <https://cljdoc.org/d/org.replikativ/datahike/0.7.1638/doc/core-features/versioning-beta->
- DataScript connection implementation:
  <https://github.com/tonsky/datascript/blob/master/src/datascript/conn.cljc>

# EACL cache

EACL's cache is a bounded, client-private optimization over one immutable
database value. The database and the cache-free evaluator remain authoritative:
a cache miss, rejected entry, provider failure, or eviction recomputes the
operation and cannot turn a deny into an allow.

## Contents

- [Consumer contract](#consumer-contract)
- [Cache layers](#cache-layers)
- [Lookup and publication](#lookup-and-publication)
- [Managed coherence](#managed-coherence)
- [Shared-subgraph reuse](#shared-subgraph-reuse)
- [Eviction and resource bounds](#eviction-and-resource-bounds)
- [Concurrency](#concurrency)
- [Configuration](#configuration)
- [Metrics](#metrics)
- [Lifecycle invalidation](#lifecycle-invalidation)
- [Cursors and historical reads](#cursors-and-historical-reads)
- [Performance and correctness gates](#performance-and-correctness-gates)

## Consumer contract

Each public operation selects one immutable database value. Schema resolution,
query normalization, traversal, cache validation, result rendering, and cursor
construction all use that selected value.

Every client owns its cache. Native authorization answers and opaque
continuation state are not shared between clients or processes. Managed
coherence uses stamps stored in the application database, so independent
clients can each retain correct local entries without a listener or cache
coordinator.

Caching does not alter the result:

- `eacl.cache/no-cache` disables it for a client;
- `:cache? false` bypasses lookup and publication for one operation;
- malformed or missing validity evidence produces a miss;
- a failed computation is not published as a deny, allow, count, or page; and
- cache data and derived authorization tuples are never written to the
  application's database.

## Cache layers

| Layer | Reuse scope | Purpose |
| --- | --- | --- |
| Exact completed answer | Identical semantic operation on the same immutable database value | Skips the complete authorization computation |
| Managed completed answer | Identical semantic operation with the same schema and complete relation-dependency stamp | Survives unrelated forward transactions |
| Exact relationship projection | Compatible traversals of the same relation/index portion on the same immutable database value | Shares bounded adjacency chunks and direct-membership probes |
| Managed relationship projection | Compatible traversals with the same relation mutation identity and schema | Shares unchanged graph portions across unrelated transactions |
| Completed denotation | Compatible operations on the same immutable database value | Reuses completed acyclic results and recursive least-fixed-point results |
| Schema plan | All operations using the same schema generation | Reuses permission paths, dependency closures, recursive routing, and immutable traversal plans |
| Navigation and continuation | One authenticated query and snapshot context | Resumes pagination without replaying work already performed |

Completed-answer keys include the complete semantic operation and normalized
query, including the principal. They deliberately do not let one principal's
answer satisfy another principal's query.

Projection keys omit the principal and top-level permission. They identify the
backend source, schema/relation identity, traversal direction, endpoint,
bound, inclusivity, and chunk width. This is what allows separate
authorization questions to reuse an overlapping graph portion.

Only completed recursive least-fixed-point denotations may be shared.
Visited-set fragments and partially processed worklists are not complete
authorization results and are never published as denotations.

## Lookup and publication

For a complete answer, EACL resolves in this order:

1. exact completed answer for the selected immutable database;
2. managed completed answer, when managed coherence is valid for the selected
   current database;
3. engine evaluation, which may itself reuse cached subproblems; and
4. publication into eligible exact and managed tiers.

For a relationship projection or direct-membership probe, resolution is:

1. exact-generation projection;
2. managed relation-stamped projection, when eligible; and
3. the backend tuple index.

A managed projection hit is promoted into the exact-generation store. Further
operations on that immutable database value avoid both another backend read and
another managed proof lookup.

Public IDs and response metadata are rendered from the selected database after
the internal result is resolved. Cached internal IDs are therefore never
externalized through a different snapshot.

## Managed coherence

The default `:coherence-authority :unknown` enables exact reuse only. This is
the correct setting if any authorization-relevant relationship or schema write
can bypass EACL.

`:coherence-authority :managed` is an explicit writer contract:

- every relationship mutation uses an EACL mutation API or the documented
  backend transaction helper;
- every schema mutation uses `eacl/write-schema!`; and
- future authorization dependencies, such as caveat inputs, participate in
  the same complete dependency protocol before they are eligible for managed
  reuse.

Managed relationship writes update database-visible relation mutation stamps
atomically with the relationship change. A managed key commits to the logical
backend/source scope, schema mutation identity, relation definition, relation
mutation identity, operation, direction, and projection bounds. Transaction
identity and mutation value are both retained where the backend can fork or
replace history, so equal numeric revisions from different histories do not
become interchangeable.

For a completed answer, the compiled permission supplies its complete relation
dependency set. EACL reads the dependency evidence from the same selected
database value and derives the managed dependency stamp. A write to any
relevant relation changes the key. An unrelated write leaves it unchanged. A
schema change installs a new managed generation and discards managed answers
and plans from the preceding schema generation.

Proof acquisition is bounded. The managed projection cache reads at most one
relation proof for each distinct relation dependency in a new exact
generation, then reuses it. `:managed-proof-max-atoms` provides a hard ceiling;
overflow or malformed evidence disables managed reuse for that candidate and
falls back to exact evaluation.

History replacement, branch forcing, restore, and unstamped repair are outside
the forward managed-writer contract. They require explicit lifecycle
invalidation.

## Shared-subgraph reuse

Suppose separate permission queries converge on the same `group#member` or
`server#team` edge. Their completed-answer keys differ, but their relationship
projection key can be identical. The first query stores a bounded projection
chunk; the second consumes it without another backend index scan.

Projection chunks are ordered and fixed-width. A small page does not
materialize an entire adjacency list. Empty terminal chunks are retained
because a shared negative probe can be as useful as a positive one.

The other cross-query network effect is schema planning. Permission paths,
dependency closures, strongly connected components, reverse reachability, and
recursive traversal plans are compiled once per schema generation and reused
by every compatible operation in the client.

Completed acyclic and recursive denotations can be reused by compatible
operations on the exact same immutable snapshot. They are not carried across
revisions because that would require a bounded proof covering every derived
dependency.

## Eviction and resource bounds

Completed-answer tiers are bounded by `:max-entries`. Admission can retain
every completed answer or wait until the same semantic key is seen twice with
`:remember-answers :on-repeat`.

Projection and denotation entries use separate weighted budgets. This prevents
one large recursive denotation from evicting every hot relationship
projection. Entry weight is a deterministic admission unit that approximates
retained key/value size; it is not a portable heap-byte measurement.

Each subproblem tier tracks recency. When publication would exceed its weight
budget, it evicts the least recently accessed completed entries until the tier
fits. The entry currently being published and in-flight entries are protected
from eviction. An entry heavier than its complete tier budget is rejected
instead of displacing the tier.

Completed answers use bounded deterministic admission. Datomic's optional
portable compatibility store uses weighted least-recently-used capacity and
optional second-sighting admission, but portable provider values are not an
authority for native completed authorization answers.

Time-to-live is optional and is not the correctness mechanism. Exact snapshot
identity and managed mutation stamps determine validity. Capacity eviction
normally gives better retention than discarding a still-hot valid entry merely
because it is old.

## Concurrency

Identical concurrent subproblem misses use single flight: one caller computes
the value and compatible callers await the same candidate. A recursive
computation that encounters its own in-flight key bypasses that candidate
instead of waiting on itself.

`:max-inflight` bounds actively executing top-level subproblem computations
across the client's cache lifecycle. JVM callers wait on a fair semaphore when
the bound is saturated. In-flight tracking is separate from evictable entries,
so clearing or replacing a generation cannot manufacture execution capacity.

Publication checks that the candidate still belongs to the active lifecycle,
is complete, validates structurally, and fits the relevant budget. A late
result from an expired lifecycle can publish only into the now-unreachable old
store.

## Configuration

The default cache is appropriate for most consumers:

```clojure
(def acl (eacl.datomic.core/make-client conn))
```

Enable cross-transaction reuse only when EACL controls every relevant writer:

```clojure
(def acl
  (eacl.datomic.core/make-client
   conn
   {:coherence-authority :managed
    :cache {:max-entries 4096}}))
```

Advanced bounds:

```clojure
{:cache
 {:max-entries 4096
  :subproblem-cache
  {:enabled? true
   :projection-max-weight (* 8 1024 1024)
   :denotation-max-weight (* 8 1024 1024)
   :max-inflight 256
   :managed-proof-max-atoms 256}}}
```

For completed-answer second-sighting admission, Datomic accepts
`{:cache {:remember-answers :on-repeat}}`; Datahike and DataScript accept
`{:cache {:admit-on-repeat? true}}`.

Disable caching for a client:

```clojure
(require '[eacl.cache :as eacl-cache])

(eacl.datomic.core/make-client conn {:cache eacl-cache/no-cache})
(eacl.datahike.core/make-client conn {:cache eacl-cache/no-cache})
(eacl.datascript.core/make-client conn {:cache eacl-cache/no-cache})
```

Bypass one call:

```clojure
(eacl/can? acl
  {:subject subject
   :permission :view
   :resource resource
   :cache? false})
```

The request option skips cache lookup and publication for `can?`,
`lookup-resources`, `lookup-subjects`, `count-resources`, `count-subjects`, and
`read-relationships`.

Use `no-cache` or `:cache? false` when representative requests rarely repeat
or when direct evaluation is cheaper than cache lookup and validity checks.
Measure the permissions and graph shapes your application actually uses.

## Metrics

Each backend exposes `cache-stats`. The top-level counters include exact and
managed hits, misses, bypasses, stamp failures, puts, expirations, live entry
counts, admission counts, and active computation counts.

The nested subproblem metrics include:

- projection and denotation hits;
- managed projection and denotation hits;
- managed proof reads, hits, failures, and overflows;
- single-flight waits and recursive self-bypasses;
- admission, oversized-entry, in-flight, and invalid-result rejections;
- evictions;
- fetched projection values; and
- avoided backend operations.

Lookups and counts also return `:cached?` and `:cache-basis`. The basis is the
database revision on which the answer was computed. A managed hit can have an
older computation basis while still equal to recomputation on the selected
database because its complete schema and relation dependency evidence remains
unchanged. `can?` returns only a Boolean.

## Lifecycle invalidation

Ordinary forward transactions require no manual cache expiry. Exact entries
are isolated by immutable database identity, and managed entries change keys
when a relevant stamp changes.

After reset, restore, branch replacement, manual history manipulation, or an
unstamped repair, quiesce the client and call:

```clojure
(eacl.datomic.core/expire-cache! acl)
(eacl.datahike.core/expire-cache! acl)
(eacl.datascript.core/expire-cache! acl)
```

Expiry atomically swaps the complete cache lifecycle. Work already in flight
retains only the detached lifecycle and cannot repopulate the new one.

## Cursors and historical reads

Cursor navigation and recursive continuation are separate bounded performance
stores. Their entries are scoped to an authenticated operation, normalized
query, backend/source identity, and snapshot context. Missing continuation
state restarts or replays according to the selected consistency mode; it does
not return an authorization answer from an unrelated query.

Explicit `at-exact-snapshot`, historical replay, filtered databases,
prospective databases, and other arbitrary database values bypass completed
answers. Ordinary cursor continuation can re-evaluate against the selected
current snapshot when retained state is unavailable.

## Performance and correctness gates

The cache-free path is the behavioral oracle. Differential tests compare
cache-enabled and cache-disabled operations across Datomic, Datahike, and
DataScript, including relevant and unrelated writes, recursive graphs,
pagination, malformed evidence, provider failure, eviction, and concurrent
publication.

The generated decision kernel checks cache lookup, admission, publication,
snapshot selection, traversal, pagination, and rendering decisions used by the
production engine. Dafny models prove the pure cache refinements and resource
bounds represented by that kernel; Clojure and ClojureScript boundary suites
verify that runtime inputs and outputs match the generated contracts.

Performance gates cover:

- reduced backend work and latency on shared-subgraph reuse;
- constant-count work on hot completed-answer hits;
- proof work bounded by distinct relevant dependencies rather than result or
  graph size;
- cache-free overhead;
- retained-entry-count scaling;
- recursive closure-size scaling; and
- configured retained-weight and in-flight limits.

See [formal verification](formal-verification.md) for the complete assurance
boundary and [the layered subproblem benchmark](v8-subproblem-cache.md) for the
checked-in workload and thresholds.

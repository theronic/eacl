# Recursive point checks and complete-denotation caching

Date: 2026-08-08
Audited EACL tree: `codex/eacl-v8-batched-formal-kernel` @ `45be4cc`
Observed consumer: `eacl-solidjs` @ `47c79f8` (nested-query bypass introduced by `152cbf3`)

Decision status: this report records the audited v8 behavior and the evidence
that motivated redesign. Its exploratory alternatives are resolved by
`openspec/changes/demand-bounded-authorization-execution`: demand-bounded work
is the default; only explicit `:evaluation :complete-denotation` permits
exhaustive work; there is no prewarm/adaptive/background completion; EACL read
paths use no schema lock or cache-computation wait; and DataScript is
current-basis-only across requests.

## Contents

1. [Executive summary](#1-executive-summary)
2. [The decision in precise terms](#2-the-decision-in-precise-terms)
3. [Why EACL computes the complete forward denotation](#3-why-eacl-computes-the-complete-forward-denotation)
4. [Why the cache-free point path is different](#4-why-the-cache-free-point-path-is-different)
5. [Implications](#5-implications)
6. [Coherence implications](#6-coherence-implications)
7. [API and telemetry implications](#7-api-and-telemetry-implications)
8. [Alternatives](#8-alternatives)
9. [Recommendation](#9-recommendation)
10. [Verification requirements for a redesign](#10-verification-requirements-for-a-redesign)
11. [Source evidence](#11-source-evidence)

---

## 1. Executive summary

For a recursive permission, EACL's current cache-enabled `can?` path does not
merely cache the Boolean answer to one `(subject, permission, resource)`
question. It resolves and publishes the complete forward authorization
denotation: the sorted set of every resource of the requested type that the
subject can access through that permission's least fixed point. The requested
Boolean is then a membership test in that set.

This is an intentional cross-operation amortization strategy. One completed
denotation can serve later point checks, pages, and counts on the same immutable
snapshot, and under managed coherence it can survive unrelated forward
transactions. It also gives the cache one complete semantic value to validate,
bound, and share; partial recursive worklists are never mistaken for completed
authorization results.

The strategy has an important cold-path consequence: the first point check for
a broad principal can cost approximately as much as enumerating everything the
principal can access. For a super-user authorized for roughly 100,000 servers,
checking one server can derive, deduplicate, sort, weigh, and attempt to retain
the entire 100,000-server denotation before returning a Boolean. This can cross
recursive traversal limits, exceed cache admission limits, hold the client's
schema read lock for a long time, and make schema writes appear hung.

With `:cache? false`, there is no denotation to publish. EACL therefore runs a
target-anchored reverse point evaluation: begin at the named resource and work
toward the requested subject. A positive check may stop when its proof is
found; a negative check exhausts the search relevant to that target. The
operation skips both cache lookup and publication.

The central design implication is that the public `:cache?` Boolean currently
controls more than cache use. For recursive point checks it also selects
between two materially different evaluation and publication strategies:

```text
:cache? true
    -> subject-anchored complete forward fixed point
    -> publish reusable denotation
    -> test target membership

:cache? false
    -> target-anchored reverse Boolean search
    -> publish nothing
    -> return the point answer
```

Both paths implement the same authorization semantics when they complete, but
their latency, memory, concurrency, failure, and telemetry behavior can differ
by orders of magnitude. The current selective bypass in `eacl-solidjs` is a
sound short-term response. Longer term, EACL should decouple "may read/write a
cache" from "must materialize a complete denotation for this point query."

## 2. The decision in precise terms

Let:

```text
D(s, p, T) = the set of resources of type T on which subject s has permission p
```

For an ordinary point query:

```clojure
(eacl/can? acl
           {:subject super-user
            :permission :view
            :resource server-42})
```

the minimum semantic output is one bit:

```text
server-42 in D(super-user, :view, :server) ?
```

The cache-enabled recursive implementation first computes the larger value
`D(super-user, :view, :server)` and then answers the membership question. If the
recursive schema propagates a platform-level grant through accounts to servers,
that set may contain every server:

```text
super-user
    -> platform grant
        -> account grants
            -> server grants
```

The cache-free implementation answers the point question directly by reversing
the search direction:

```text
server-42
    <- containing account
        <- platform
            <- super-user
```

The distinction is not "correct versus approximate." Both drivers refine the
same recursive least-fixed-point semantics. The distinction is the semantic
object being computed and retained: a reusable complete set versus a one-off
Boolean proof search.

## 3. Why EACL computes the complete forward denotation

### 3.1 One value serves several public operations

A completed sorted denotation is a common substrate for several API calls:

- `can?` and `check-permission` perform membership tests;
- `count-resources` uses the denotation's cardinality;
- `lookup-resources` renders ordered page windows; and
- later compatible point, count, and page operations avoid repeating the
  recursive fixed point.

The current recursive count path explicitly resolves and publishes the full
denotation when a subproblem store is bound so that distinct operations stop
repaying the same traversal (`engine/v8.cljc:4285-4293`). A point check follows
the same reuse policy (`engine/v8.cljc:3351-3363`). This is stronger than an
exact-query Boolean cache: a cached `true` for `server-42` cannot directly
answer a count or render page 12, while the complete set can.

### 3.2 Recursive absence is not known until the fixed point completes

Recursive permission evaluation is monotone: new grants may be discovered as
the worklist advances, and a discovered positive grant cannot later be
retracted by another iteration. This permits a one-off positive point query to
stop early.

It does not make an incomplete worklist a complete denotation. Before
exhaustion:

- absence of a target is not yet a deny;
- the total count is unknown;
- a page may be missing values discovered later;
- stable ordering cannot be finalized; and
- a partial visited set is an implementation state, not a complete semantic
  authorization result.

EACL therefore publishes only completed acyclic or recursive denotations.
Visited fragments and partially processed worklists are deliberately excluded
from the shared cache (`docs/v8-subproblem-cache.md:65-77`). This keeps cache
reuse equivalent to cache-free evaluation and avoids turning interrupted,
limited, or failed work into an authorization answer.

### 3.3 The forward direction maximizes cross-operation reuse

The reusable question behind resource pages and counts is naturally anchored
at the subject:

```text
Which resources can this subject access?
```

By contrast, a reverse point search asks:

```text
Which subjects can reach this one resource, and is the requested subject among
them?
```

The reverse search is well targeted for one resource, but separate target
resources generally produce separate searches and do not directly yield the
subject's resource page or count. EACL's cache-enabled path therefore pays the
broad forward cost in exchange for a result with a larger reuse domain.

### 3.4 Single-flight makes the broad cost shareable

Compatible concurrent misses for the same denotation can converge on one
in-flight computation. This prevents multiple threads from independently
materializing the same fixed point and makes the strategy attractive for a
workload that predictably follows a cold point check with many pages, counts,
or related checks.

Single-flight does not reduce the first computation's work or latency. It
changes duplicate work into waiter latency. All callers waiting for that cold
denotation still inherit its completion time.

## 4. Why the cache-free point path is different

`recursive-can?` branches on whether the subproblem store is bound. With the
store present, it calls `resolve-forward-denotation` and tests membership. With
no store, it invokes the generated reverse machine from the concrete resource
toward the requested subject (`engine/v8.cljc:3351-3379`).

This is a rational cache-free optimization:

1. There is no retained denotation to amortize, so completing unrelated grants
   has no future benefit.
2. The resource is already known, providing a selective traversal anchor.
3. A positive result can return as soon as the target subject is derived.
4. A negative result exhausts the reverse search required to establish absence
   for that resource, rather than enumerating every resource reachable from the
   subject.

The target-local performance gate checks that direct point-query work remains
constant as unrelated resources reachable from the subject grow, and that the
ordinary direct point path performs one target-anchored backend scan
(`modules/eacl-datomic/test/eacl/bench/pagination_test.clj:1215-1257`).

The bypass is complete by contract: `:cache? false` skips both reading and
writing completed-answer/subproblem cache state for that call. It is not a
"read-through but do not retain" mode.

## 5. Implications

### 5.1 Summary table

| Dimension | Complete forward denotation | Target-anchored point evaluation |
| --- | --- | --- |
| Cold point latency | Proportional to the subject's reachable authorization set and fixed-point work | Proportional to the graph relevant to the named resource |
| Warm repeated point latency | Very low membership lookup or completed-answer hit | Repeats work because the call publishes nothing |
| Page/count reuse | Excellent; the same denotation serves both | None |
| Memory/admission | Retains a complete sorted result if it fits | Retains nothing |
| Broad principals | Worst cold case | Often the strongest target-local improvement |
| Negative checks | Full forward set proves absence | Must exhaust the target-relevant reverse search |
| Concurrent compatible misses | Can share one in-flight denotation | Distinct calls evaluate independently |
| Schema-write interaction | One long read-lock interval can delay a writer | Shorter per-target read-lock intervals |
| Mutation-heavy workloads | Relevant mutations can repeatedly invalidate expensive work | Each point reflects its selected snapshot without publication churn |
| Telemetry | Can report exact/managed hit, miss, tier, and basis | Correctly reports bypass/disabled, but no future cache benefit |

### 5.2 Cold latency scales with authorization breadth, not query specificity

A point request appears maximally selective because it names one resource. The
cache-enabled recursive plan discards that selectivity in order to compute a
shareable subject-rooted value. Consequently the cold latency is driven by
`|D(s,p,T)|` and the graph work required to derive it, not by the fact that the
public response is one Boolean.

This asymmetry is most visible for super-users. A normal user may have tens of
reachable servers; a super-user may have roughly 100,000. The same API shape
can therefore have radically different cold costs depending on principal
breadth.

### 5.3 The cache setting can change operational success even though it does
not change authorization semantics

The default recursive traversal ceiling includes
`:max-derived-grants 100000` (`engine/v8.cljc:1680-1699`). A complete
denotation at or above that scale may reach the safety limit even when a
target-anchored point proof is small. Similarly, a completed denotation may be
computed but rejected by the cache's weight budget.

Thus the two modes should return the same allow/deny result if they complete,
but one mode may fail with a traversal-limit or resource-bound error where the
other succeeds. This is an availability and predictability consequence, not an
authorization-semantic difference.

Raising the limit is not a general remedy. It permits more heap and CPU work
and moves the cliff; it does not restore point-query selectivity.

### 5.4 Cache admission can make the broad work unrewarded

The full-denotation strategy assumes the completed value will be reused. That
assumption can fail when:

- the denotation exceeds the configured per-entry or tier weight;
- admission policy rejects it;
- a relevant mutation invalidates it before reuse;
- the workload asks each principal/root combination only once; or
- an exact-only client observes frequent unrelated transaction churn.

In these cases the caller pays enumeration, sorting, validation, and weighing
cost without receiving the intended amortization. Repeated cold requests can
repay essentially the same work.

### 5.5 Schema writes can look deadlocked even without a cyclic deadlock

The Datomic client runs authorization operations under a
`ReentrantReadWriteLock` read lock and runs `write-schema!` under its write lock
(`modules/eacl-datomic/src/eacl/datomic/core.clj:1288-1341,
2159-2204`). This ensures a query cannot mix one schema generation's compiled
plan with another generation's transaction/cache state.

A cache-enabled recursive point check may hold that read lock while resolving
the full denotation. A schema write must wait for the reader to finish. With a
large fixed point this can appear as `Writing schema...` indefinitely, and
additional readers can worsen writer latency depending on scheduling.

This is not, by itself, proof of a cyclic deadlock. It is a long critical
section and potential writer-starvation/availability problem. The nested-query
bypass fixed the observed symptom by reducing the work done within each read
operation; it did not remove or weaken the schema-generation lock.

### 5.6 Reactive seeding is a hostile workload for full-denotation misses

During active seeding, each committed batch selects a new immutable Datomic
snapshot. If the batch changes a relation in the permission's dependency
closure, the relevant managed stamp changes and an old denotation cannot be
reused for the new authorization graph. A reactive UI can therefore trigger a
sequence of cold full-denotation computations while the reachable set is
growing.

That is exactly the workload in which the application wants frequent,
low-latency evidence that queries continue to work while data is added. A
target-anchored page-sized sweep trades future reuse for bounded foreground
responsiveness and is therefore appropriate for the current nested explorer
view.

### 5.7 The best strategy depends on the expected next operation

The complete-denotation strategy is favorable when a cold request is likely to
be followed by many compatible resource pages, counts, or point checks. The
target path is favorable for one-off checks, batch sweeps over distinct
targets, and broad principals where only a small page is needed.

The engine cannot infer that future workload from the semantic query alone.
The present API uses the caller's `:cache?` choice as a proxy for reuse intent,
but "cache enabled" does not necessarily mean "I am willing to synchronously
enumerate the complete authorization set now."

## 6. Coherence implications

Coherence is orthogonal to the immediate point-evaluation strategy. It answers
whether a cached value computed on snapshot A may be reused when the database
is now at snapshot B.

### `:coherence-authority :unknown`

EACL assumes authorization-affecting writes may occur outside its instrumented
writers. Cache entries are reused only for the identical immutable database
generation. This is the fail-safe default on every backend. It prevents a raw,
unstamped relationship retraction from leaving a reusable stale allow.

The implication for complete denotations is lower amortization under database
churn. Even an unrelated transaction selects a new exact generation, so the
old denotation is not carried into it.

### `:coherence-authority :managed`

The consumer asserts that every authorization-affecting schema and
relationship mutation uses EACL's writer APIs or documented stamped
transaction builders. Managed entries commit to the schema generation and the
complete sorted mutation-stamp vector for the permission's relation dependency
closure. A relevant relation write changes the key; an unrelated write leaves
it eligible.

Managed coherence increases the potential payoff of an expensive complete
denotation because it can survive unrelated forward transactions. It does not
make the initial denotation cheaper, and it cannot preserve the value across a
write to a relevant relation or a schema replacement.

### `:cache? false`

The per-request bypass takes precedence operationally: it neither consults nor
publishes exact or managed cache state. Whether the client is `:unknown` or
`:managed` does not change that call's cache behavior.

The earlier v8 coherence change made `:unknown` the uniform default after the
DataScript default-managed assumption was shown unsafe under an out-of-contract
raw transaction. That change and the nested `:cache? false` workaround solve
different problems:

- `:unknown` prevents unproved cross-snapshot reuse;
- `:cache? false` prevents an expensive one-off query from entering the
  denotation-cache path at all.

## 7. API and telemetry implications

### 7.1 `:cache?` is an overloaded policy switch

For recursive point checks, one Boolean currently controls at least four
decisions:

1. whether to read a completed answer;
2. whether to publish the completed Boolean answer;
3. whether a subproblem store is bound and may retain projections or
   denotations; and
4. whether point evaluation chooses the reusable full-forward path or the
   target-local reverse path.

The fourth consequence is surprising to a caller who reasonably interprets
`:cache?` as a storage/reuse preference. The semantics remain stable, but the
execution complexity does not.

### 7.2 One cache badge cannot accurately describe a composed nested request

`eacl-solidjs` nested traversal currently performs:

```text
read-relationships(page, cache according to UI)
    -> N check-permission(resource, cache false)
    -> filter page
```

The `read-relationships` stage may be a cache hit while every authorization
check is deliberately bypassed. The application currently marks the combined
response `DISABLED`, so that badge means "the complete filtered operation was
not cache-served," not "no stage used cache." Its elapsed time also combines
relationship retrieval and all point checks.

This is safe but lossy telemetry. It hides the distinction between a slow
relationship miss and a slow authorization sweep and can make cache behavior
look inconsistent.

A more diagnostic response would preserve stage information, for example:

```json
{
  "relationshipPage": {"cacheStatus": "hit", "elapsedMs": 2.1},
  "authorizationFilter": {
    "cacheStatus": "bypass",
    "strategy": "target-anchored-reverse",
    "checks": 20,
    "elapsedMs": 64.9
  }
}
```

This example describes a possible application contract, not a proposed EACL
wire format. EACL currently exposes `:cached?` and `:cache-basis`; wall-clock
stage timing remains the application's responsibility.

### 7.3 `MISS` does not reveal whether publication succeeded

A cold full-denotation request may report a miss whether the resulting
denotation was admitted, rejected as oversized, or invalidated shortly after
publication. Operational analysis needs to distinguish:

- lookup outcome: exact hit, managed hit, miss, or bypass;
- evaluation strategy: forward denotation or reverse point;
- publication outcome: admitted, rejected, or not attempted; and
- retained provenance: tier and cache basis.

Without these dimensions, a user can observe repeated expensive misses without
knowing that the computed value never became reusable.

## 8. Alternatives

### 8.1 Keep selective consumer-side bypasses

Applications identify one-off or page-sized distinct-target sweeps and pass
`:cache? false`.

Advantages:

- available now with no EACL API change;
- preserves target-local point performance;
- straightforward correctness story; and
- appropriate for the current nested explorer.

Costs:

- every consumer must understand EACL's internal cost model;
- repeat checks for the same target cannot reuse a Boolean answer;
- cache telemetry becomes coarse in composed requests; and
- a seemingly harmless cache toggle can reintroduce the broad cold path.

### 8.2 Cache the target-anchored Boolean without publishing a denotation

Run the reverse point evaluator, then retain the completed Boolean answer under
the ordinary semantic key and dependency framing.

Advantages:

- cheap first point query;
- repeated identical point checks can hit; and
- no complete resource set is required.

Costs:

- different targets create different entries;
- pages and counts cannot reuse the Boolean;
- negative results require complete target-relevant search and correct full
  dependency framing; and
- the cache needs an explicit distinction between answer publication and
  denotation publication.

This is the most direct way to remove the current coupling while retaining
ordinary point-answer caching.

### 8.3 Split cache policy from evaluation/materialization policy

Introduce an explicit policy that separately controls completed-answer reuse
and full-denotation materialization. The exact public shape requires design,
but the conceptual choices are:

```text
cache lookup/publication: enabled | bypassed
point evaluation: target-local | materialize-shared-denotation
```

Advantages:

- makes the cost decision explicit;
- permits target-local evaluation with Boolean answer caching;
- preserves deliberate eager denotation warming for workloads that benefit;
  and
- makes telemetry correspond to separate decisions.

Costs:

- expands the public policy surface;
- requires a compatibility default; and
- creates more execution combinations to verify across all backends.

### 8.4 Adaptive or background denotation warming

Answer the first point query through the reverse path and materialize the
forward denotation only after repeated compatible demand, or in background.

Advantages:

- protects first-request latency;
- admits broad work only when reuse evidence exists; and
- can resemble two-hit admission at the semantic-work level.

Costs:

- a background computation must remain tied to its immutable snapshot and
  lifecycle;
- cancellation, single-flight, bounded execution, and publication races become
  more complex;
- relevant writes may make the background result obsolete before admission;
  and
- deterministic benchmarking and operational reasoning become harder.

This is rejected for v8. No repeat observation, cache admission decision, or
background task may expand a request beyond its explicit evaluation contract.

### 8.5 Add a fused authorized-relationship operation

The nested explorer could be expressed as one EACL operation that pages a
relationship scope while applying permission checks internally.

Advantages:

- avoids an application-level N+1 composition;
- could share traversal state and expose coherent stage telemetry; and
- gives the engine more information about the bounded page intent.

Costs:

- broadens EACL's public API and verification surface;
- couples relationship enumeration and authorization semantics;
- may be too consumer-specific; and
- does not by itself answer the general point-query cache-policy problem.

This is a separate API design question, not a prerequisite for fixing the
denotation-materialization coupling.

## 9. Recommendation

### Immediate consumer behavior

Keep the `eacl-solidjs` nested authorization sweep on `:cache? false`. It is a
bounded page of distinct targets, not an attempt to warm the subject's complete
resource set. Do not raise traversal limits merely to make a cache-enabled
super-user point check materialize a larger denotation.

Change the application telemetry when implementation work is next authorized:

- describe the authorization stage as `BYPASS`, not as if the entire nested
  operation had no cache activity;
- preserve the `read-relationships` cache status separately;
- measure relationship retrieval and authorization filtering independently;
  and
- include the number of point checks in the filter stage.

### EACL design direction

Decouple target-local point evaluation from complete-denotation publication.
The preferred conceptual behavior is:

1. permit cache lookup for an existing exact/managed completed Boolean or
   compatible complete denotation;
2. on a cold point miss, use the target-anchored reverse evaluator by default;
3. permit the completed Boolean to be admitted under sound dependency framing;
   and
4. materialize the complete forward denotation only when explicitly requested
   through `:evaluation :complete-denotation`.

This makes a point API cost proportional to a point query by default while
retaining an explicit exhaustive mode for enumeration-heavy workloads. It also
aligns the public mental model of "enable caching" with
ordinary cache lookup/publication instead of silently changing a selective
query into a complete enumeration.

The compatibility question must be explicit. Changing the cold strategy can
improve point latency while reducing the accidental warming that current page
and count workloads may rely on. Benchmarks must measure full request sequences,
not isolated `can?` calls.

## 10. Verification requirements for a redesign

Any change to this policy should be gated by all of the following.

### Correctness

- Differentially compare target-local cached Boolean results against the
  cache-free oracle for positive and negative recursive permissions.
- Interleave relevant and unrelated relationship mutations under both
  `:unknown` and `:managed` coherence.
- Replace the schema between queries and prove that old Boolean and denotation
  entries cannot cross schema generations.
- Exercise recursive cycles, multiple paths to the same grant, deleted
  endpoints, and traversal-limit failures.
- Verify that interrupted or failed reverse searches never publish a deny.

### Performance

Benchmark at least:

- reachable result widths of 1,000, 10,000, 100,000, and 1,000,000;
- narrow users and broad super-users;
- positive targets found near and far from the anchor, plus negative targets;
- stable databases, unrelated transaction churn, and relevant relationship
  churn;
- one point check, repeated identical checks, many distinct point checks,
  point-then-page, point-then-count, and page-then-point sequences; and
- admitted, oversized, and evicted cache values.

The primary target-local invariant should remain: unrelated resources reachable
from the subject must not increase the backend/logical work of a cold direct
point check unless the caller explicitly requested complete-denotation
evaluation.

### Concurrency and lifecycle

- Measure schema-writer wait time during cold broad-principal checks.
- Prove authorization reads acquire no EACL schema lock and neither schema
  writes nor concurrent reads form EACL-owned lock convoys.
- Prove concurrent cache misses never join, wait for, or inherit failure from
  another request, including two explicit complete-denotation requests.
- Cancel or expire in-flight work across cache lifecycle and schema-generation
  changes without late publication.
- Bound the number and total retained weight of target-specific Boolean
  entries.

### Observability

- Report evaluation strategy independently of cache lookup status.
- Distinguish exact hits, managed hits, misses, bypasses, and publication races;
  no single-flight join category remains.
- Report publication admission/rejection and retained cache tier.
- Keep `:cache-basis` as provenance; do not imply that a managed hit computed
  on an earlier basis is stale when dependency stamps prove it current.

## 11. Source evidence

- `modules/eacl/src/eacl/engine/v8.cljc:3351-3379` — recursive `can?`
  selects a complete forward denotation when the subproblem store is present
  and a target-anchored reverse Boolean render when it is absent.
- `modules/eacl/src/eacl/engine/v8.cljc:4257-4307` — a store-bound recursive
  count resolves and publishes the complete denotation for reuse by counts,
  pages, and point checks.
- `modules/eacl/src/eacl/engine/v8.cljc:1680-1699` — default recursive
  traversal limits, including 100,000 derived grants.
- `docs/v8-subproblem-cache.md:65-77` — only completed fixed-point denotations
  are publishable; partial worklists are not shared, and managed denotation
  reuse is dependency-stamped.
- `docs/cache.md:93-132` — `:unknown` versus `:managed` coherence and the
  stamped-writer contract.
- `modules/eacl-datomic/src/eacl/datomic/core.clj:1288-1341` — schema read/write
  lock boundaries.
- `modules/eacl-datomic/src/eacl/datomic/core.clj:2159-2204` — public
  authorization reads take the schema read lock while `write-schema!` takes
  the write lock and rotates schema/cache state.
- `modules/eacl-datomic/src/eacl/datomic/core.clj:2540-2572` — cache
  configuration and the per-call bypass contract.
- `modules/eacl-datomic/test/eacl/bench/pagination_test.clj:1215-1257` — the
  target-local scaling gate for direct point checks.
- `eacl-solidjs/server/src/eacl_solidjs/api.clj:166-228` at consumer commit
  `47c79f8` — nested relationship pages use cache-free per-resource permission
  checks and report the composed response as cache-disabled.

---

## Conclusion

Computing a complete forward denotation for a cache-enabled recursive point
check is not accidental wasted work. It is a deliberate decision to turn the
first query into a reusable semantic index for later points, pages, and counts.
The decision is correct and valuable for enumeration-heavy, stable workloads.

It is nevertheless the wrong default cost model for a one-off selective point
query against a broad principal, particularly during relevant write churn. The
current `:cache?` flag conflates cache permission with eager shared-result
materialization, causing surprising cold latency, traversal-limit exposure,
long schema-read critical sections, and ambiguous telemetry. Selective
consumer-side bypasses are appropriate now; a first-class separation between
point-answer caching and explicitly requested complete-denotation evaluation is
the durable EACL v8 design.

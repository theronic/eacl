## Outcome

EACL now treats shared cache storage as ordinary bounded keyed retention.
Authorization meaning lives in complete composite keys and validated immutable
values; the cache decides only whether a reusable value is currently retained.
The JVM uses Caffeine 3.2.4's W-TinyLFU frequency/recency policy, and CLJS uses
the theronic `cljs-cache` LRU.

The previous weighted stores, generation registries, repeat-admission windows,
touch queues, tombstones, compaction paths, the externalized route/boundary/
alias navigation state machine, and provider-owned cache backends have been
removed. One exact-basis complete transport-page value now avoids repeated
identity conversion, dependency-context construction, cursor decode, and token
construction for lookup-resource, lookup-subject, and relationship-read pages
by retaining the complete authenticated transport page for the default
non-expiring cursor policy. Canonical public exact keys also let point, count,
and permission-tree hits avoid backend ID internalization on deterministic
immutable/injective identity adapters. Completed pages
containing more than 1,000 results are returned normally but are not retained.
Scalar, count, Boolean, tree, and other bounded results are unaffected by that
page-only rule.

## Selected libraries

- JVM: Caffeine 3.2.4 manual `Cache` with `maximumSize` through EACL's private
  adapter.
- CLJS/DataScript demos: `theronic/cljs-cache` at Git SHA
  `4143cc036446a47f0c6dfd9f8dde90363835051c`.
- Datahike excludes its upstream `com.github.pkpkpk/cljs-cache` dependency so
  only one implementation of the `cljs.cache` namespace resolves.

The theronic fork fixes sequence iteration and near-safe-maximum recency tick
normalization. Its normal and advanced Node suites are green. The Git SHA is a
direct demo dependency; no separate artifact publication is required for EACL
product testing.

## Runtime design

The standard adapter exposes only construction, membership-aware lookup, quiet
peek, absent insertion, eviction, enumeration, clearing, and conditional
replacement. Stored values are boxed so `nil` and `false` remain distinct from
absence. Caffeine ordinary reads are nonblocking and record policy use; its
buffered maintenance may take an internal eviction lock and `maximumSize` is an
eventual concurrent bound. CLJS records strict LRU use. Validation,
computation, cancellation, deadline handling, proof checks, rendering, and
cursor transport occur outside cache callbacks. Misses remain independently
owned by each request; there is no loader, promise, or single-flight owner.

Exact denotations and exact/managed completed answers use flat v2 composite
keys. Exact keys contain the selected immutable basis. Managed keys contain
source lifecycle, schema generation, canonical dependency identity, and proof.
Exact lookup is ordinary membership; managed reuse adds the forward causal
check. Validated completed publication and validated off-side restore are the
only supported entry-installing transitions.

For `can?`, both counts, and permission-tree expansion, the exact semantic
probe uses a canonical public key before backend identity lookup only when the
adapter is deterministic, promises immutable/injective external identity, and
the public IDs satisfy the bounded canonical scalar/vector contract. Other
cases keep the internal semantic path; tree caching is disabled when no safe
public key exists. Request query maps/vectors/sets are recursively copied into
ordinary persistent containers so retained keys do not own caller comparators
or collection implementations.

The exact transport-page store handles lookup-resource, lookup-subject, and
relationship-read pages. It is keyed by the complete exact basis, complete raw
request including the exact cursor token, full authenticated consistency
descriptor including any exact token or freshness floor, operation,
cursor-key policy, and render ABI. It retains the complete immutable public
page only after successful authentication, evaluation, and operation-typed
validation: lookup items are EACL `SpiceObject` values and relationship-read
items are EACL `Relationship` values composed from valid SpiceObjects. Custom
records fail closed. A hit returns before cursor decode, identity or proof
work, row rendering, and token construction. Cursor TTL disables this tier;
transport values never cross exact bases or enter portable snapshots. An
authenticated input token carrying expiry also suppresses publication under a
non-TTL receiver. Cursor object IDs must be bounded canonical scalars or plain
vectors: metadata, records, non-vector sequentials, alternate integer
representations, every map/set ID, and signed zero fail closed before they can
alias a scope or cache key. Oversized or foreign raw boundaries bypass the
transport lookup before key construction and continue to bounded decoding.

Successful validated absent-key insertion is cache-publication's linearization
point. Cancellation or deadline observed before insertion skips publication;
a late signal may suppress the current response but does not retract an
already validated immutable value.

Lifecycle clear, expiry, and restore install fresh store instances. Work may
publish only into the instance it captured, so late work cannot become visible
after rotation. Narrow answer-cache clear preserves source proof health while
full rotation replaces every non-exported child. Snapshot restore validates a
complete candidate off-side and installs it atomically.
Portable export contains only validated semantic answer/denotation mappings;
it excludes exact rendered pages and both runtimes' private admission,
frequency, recency, maintenance, and eviction metadata.

Continuation, cursor context, derived-schema, and bounded stable-page
checkpoint retention use the same standard-cache boundary. Checkpoint admission,
latest-progress rules, cursor authentication/expiry, request-local evaluator
memos, worklists, and authoritative backend state remain outside cache policy.
The raw Datomic compatibility facade no longer exposes a caller-owned schema
cache: raw calls receive fresh request-local memos, while managed clients are
the sole owners of cross-request derived-schema retention.

Retained EACL Snapshots assert consistency per read. The read's authenticated
descriptor and exact/floor token refine the retained cursor/cache selection,
while the Snapshot creation selection continues to supply backend facts. Thus
two policies that reach the same basis do not accidentally share consistency
authority.

## Correctness model

The cache-specific Dafny model is a bounded partial map from complete keys to
validated completed values with optional publication and arbitrary eviction.
It proves the semantic properties that matter: key separation, valid ingress,
hit/fresh equality, cache bypass, independent computation, managed causal
reuse, page-retention eligibility, and lifecycle detachment.

The consolidated temporal model covers lookup, publication, eviction, expiry,
store-instance replacement, partial publication, managed-proof bypass, and
orphaned lifecycle publication. Bounded negative controls are retained for
these cache behaviors. Eviction-policy internals, dependency coordinates, artifact
digests, generated byte counts, and global exhaustive state exploration are
not authorization claims.

Fresh cache-specific verification passed for `CurrentCache.dfy`,
`SubproblemCache.dfy`, `PageWindow.dfy`, and
`ScalarFrontierCoherence.dfy` with zero errors. `EaclCacheStorage.tla` passed
SANY/Snowcat typechecking and bounded safety through length five. The retained
partial-publication, orphan-publication, fail-open, store-instance reuse,
managed-proof bypass, and managed-publication proof-drift mutants all produced
the expected counterexamples.

Portable CLJ/CLJS tests cover absence, nil/false values, hot-key churn,
existing-key updates, capacity, restore, iteration, CLJS safe recency ticks,
and contention without requiring identical eviction victims. Cross-backend differential tests compare cache-enabled and
`:cache? false` point, page, count, recursive, exact, and managed results,
including ordering, cursors, typed errors, selected snapshots, cancellation,
and mandatory work limits.

## Product evidence

The final gate covers point decisions, both counts, permission trees, and
first/continued/reverse 64-item lookup and relationship-read pages. Two fresh
isolated 501-sample runs completed all 5,010 fully realized hits below 1 ms;
operation p50s were 0.040--0.149 ms and the two run maxima were 0.530 and
0.708 ms. Runs deliberately mixed with other CPU-heavy suites exposed rare
4--8 ms wall-clock pauses while the measured thread consumed about 0.06 ms of
CPU. Those are JVM safepoint/GC or scheduler pauses, not cache lookup work, and
cannot be converted into a deterministic wall-clock guarantee on a preemptive
JVM. The uncontended Core target is met without weakening the absolute gate.

Final CI-parity JVM suites passed with zero failures/errors: core 367 tests /
6,136 assertions, Datomic 667 / 20,748, DataScript 671 / 16,115, and Datahike
442 / 10,695. The normal and advanced/elided-assert CLJS runs each passed 557
tests / 13,701 assertions. Three repeated concurrent hot-retention runs, all
four module JAR builds, the aggregate release build, the reflection gate, and
strict validation of both active cache changes also passed. The final
prepared-runtime-options simplification reduced a retained count hit from
about 88.1 KB to 76.2 KB allocated and from about 37.4 to 32.7 microseconds.

## Tester commands

```text
clojure -X:test
clojure -M:datascript-cljs-test
node target/datascript-cljs-test.js
clojure -M:test -n eacl.bench.public-cache-hit-acceptance-test
bin/reflection-gate target/reflection-gate.log
clojure -T:build-eacl jar
```

Run the focused benchmark command in the Product evidence section separately
from other CPU-heavy processes. CLJS/DataScript demos resolve the fork SHA
listed in Selected libraries.

The product-testing branch is
`codex/standardize-authorization-cache-storage` on `theronic/eacl`.

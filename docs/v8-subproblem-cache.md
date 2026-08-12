# EACL layered subproblem cache

Each EACL client owns bounded caches for completed answers, immutable
relationship projections, completed authorization denotations, and compiled
schema plans. These are private performance state; the selected database value
and cache-free evaluator remain authoritative.

## Resolution

Relationship projections and membership probes resolve in this order:

1. exact-generation state for the selected immutable snapshot;
2. proof-backed state under the request's complete ordered-generation frame;
3. the backend tuple index.

A proof-backed hit is promoted into the exact store. Later operations on that
snapshot then avoid both proof lookup and backend access. Proof-backed reuse is
automatic for deterministic completed requests, including demand-bounded
operations; cache policy never widens an adapter command or continues
traversal beyond the caller's demand.

Projection keys bind source lifecycle, schema and dependency frontier,
relation, direction, internal endpoint, bound, inclusivity, and response size.
They omit the principal and top-level permission so different queries that
converge on the same relationship command can share the projection.

Acyclic denotations and recursive least-fixed-point vectors are published only
after completion. Visited-set fragments, partial page walks, transient
continuations, and incomplete worklists are not completed denotations.

## Proof and invalidation

The request proof frame validates one schema generation and the complete
canonical relation-generation closure, then derives a scalar frontier.
Subproblem subset frontiers are derived only from relations already proved by
that frame. Missing, malformed, partial, oversized, or unavailable proof uses
the exact store or backend and does not alter authorization availability.

A supported relevant relationship mutation atomically advances its relation
generation. That invalidates every proof-backed projection depending on the
relation; unrelated relation writes leave it reusable. The relation-wide
granularity is intentionally conservative and avoids endpoint-local storage
and write amplification.

## Bounds and concurrency

Projection, denotation, and answer tiers have isolated weighted
least-recently-used budgets. Oversized values are rejected instead of
displacing an entire tier. Identical misses compute independently and race
bounded best-effort publication; one request never waits for another cache
computation.

```clojure
{:cache
 {:max-entries 4096
  :subproblem-cache
  {:enabled? true
   :projection-max-weight (* 8 1024 1024)
   :denotation-max-weight (* 8 1024 1024)
   :answer-max-weight (* 16 1024 1024)
   :managed-proof-max-atoms 256}}}
```

Weights are deterministic admission units approximating retained size, not
portable heap-byte measurements. `cache-stats` exposes exact and proof-backed
projection, denotation, and answer hits; proof reads/failures/overflows;
publication races; admission and oversize rejection; eviction; fetched values;
and avoided backend operations.

## Evidence

`formal/dafny/SubproblemCache.dfy` proves key separation, exact-hit refinement,
proof framing, completed-only publication, weighted retention, bounded
publication attempts, and lifecycle rejection. The scalar cross-snapshot
argument is proved in `formal/dafny/ScalarFrontierCoherence.dfy`. CLJ/CLJS
differential suites compare cached and bypassed results across all bundled
backends. See the [current measurements](benchmarks/results/2026-08-11-scalar-frontier-coherence.md).

The theorem is conditional on deterministic complete dependencies, truthful
adapter proof evidence, globally ordered atomic relation stamps, and the
database engines. Those are certified and adversarially tested, not
mechanically verified implementations.

# EACL answer cache and subproblem store

Each EACL client owns one bounded, client-private subproblem store per cache
generation. Since the stable-discovery engine was routed
(`adopt-stable-discovery-enumeration`), the store's live tiers are:

- the **`:answer` tier** — completed answers (`can?`, lookup pages, counts)
  keyed by the complete semantic request, first under the exact immutable
  snapshot and then under the proof-backed schema/dependency frontier;
- the **`:projection` tier**, used only for `internal-id->object` identity
  renderings while a page is externalized (`eacl.relay`).

The `:denotation` tier and the relationship-projection use of the
`:projection` tier belonged to the retired merge/indexed engines. Their
weight budgets are still accepted by the store configuration so existing
client options keep loading, but no engine path publishes into them; the
stable engine keeps exactly two cache artifacts of its own — the latest
per-query checkpoint (`eacl.engine.stable-page`) and the completed answer —
and treats fetched relationship chunks as disposable request-local buffers.
See [docs/stable-discovery-engine.md](stable-discovery-engine.md).

The selected database value and the cache-free evaluator remain
authoritative; every tier is a performance artifact.

## Resolution

A completed answer resolves in this order:

1. exact-generation state for the selected immutable snapshot;
2. proof-backed state under the request's complete ordered-generation frame
   (schema generation plus the scalar dependency frontier of the
   permission's relationship closure);
3. evaluation against the selected snapshot.

A proof-backed hit is promoted into the exact store, so later operations on
that snapshot avoid both the proof lookup and the evaluation. Proof-backed
reuse is automatic for deterministic completed requests; cache policy never
widens an adapter command or continues traversal beyond the caller's demand.
Cursor-bearing pages are answers too, but their continuation state lives in
the checkpoint store, never in the answer tier.

## Proof and invalidation

The request proof frame validates one schema generation and the complete
canonical relation-generation closure, then derives a scalar frontier.
Missing, malformed, partial, oversized, or unavailable proof uses the exact
store or the backend and does not alter authorization availability.

A supported relevant relationship mutation atomically advances its relation
generation. That invalidates every proof-backed answer depending on the
relation; unrelated relation writes leave it reusable. The relation-wide
granularity is intentionally conservative and avoids endpoint-local storage
and write amplification.

## Bounds and concurrency

Tiers have isolated weighted least-recently-used budgets. Oversized values
are rejected instead of displacing an entire tier. Identical misses compute
independently and race bounded best-effort publication; one request never
waits for another cache computation.

```clojure
{:cache
 {:max-entries 4096
  :subproblem-cache
  {:enabled? true
   :projection-max-weight (* 8 1024 1024)   ; identity renderings
   :denotation-max-weight (* 8 1024 1024)   ; accepted, unused by the stable engine
   :answer-max-weight (* 16 1024 1024)
   :managed-proof-max-atoms 256}}}
```

Weights are deterministic admission units approximating retained size, not
portable heap-byte measurements. `cache-stats` exposes exact and proof-backed
answer hits, projection hits, proof reads/failures/overflows, publication
races, admission and oversize rejection, eviction, and avoided backend
operations.

## Evidence

`formal/dafny/SubproblemCache.dfy` proves key separation, exact-hit refinement,
proof framing, completed-only publication, weighted retention, bounded
publication attempts, and lifecycle rejection. The scalar cross-snapshot
argument is proved in `formal/dafny/ScalarFrontierCoherence.dfy`. CLJ/CLJS
differential suites compare cached and bypassed results across all bundled
backends. See the [recorded measurements](benchmarks/results/2026-08-11-scalar-frontier-coherence.md).

The theorem is conditional on deterministic complete dependencies, truthful
adapter proof evidence, globally ordered atomic relation stamps, and the
database engines. Those are certified and adversarially tested, not
mechanically verified implementations.

# Proposal: Exact Scan-Response Cache (supersedes the hierarchical demand-segment draft)

> Status of this rewrite: the original proposal/design/tasks were reviewed
> adversarially on 2026-08-18 and rejected as written; see
> [`review/REVIEW.md`](review/REVIEW.md) for findings and measurements and
> `review/original-*.md` for the superseded text. The change keeps its
> directory name for continuity; its content is now the design below.
> Renaming the change to `exact-scan-response-cache` before implementation
> is recommended but not required.
>
> **Round-2 status (same day, after profiling the live 110k-server demo):**
> the two prerequisites now exist as their own changes and are implemented
> — `fix-datomic-request-overhead` (`can?` 6.0 ms → 142 µs, pages
> 5.6 ms → ~490 µs) and `membership-probe-point-check` (`can?` no longer
> scales with subject count). On a warm Datomic peer the scan-response
> cache showed **no measurable gain for lookup pages** (scans cost 2–10 µs;
> the prototype's hit path cost the same) and its `can?` benefit is
> superseded by the probe check. This change therefore stays
> **proposed and gate-decided**, primarily for Datahike/remote stores;
> the cleanup workstream (§ tasks 8) stands regardless.

## Why

EACL v8's stable-discovery reducer keeps two cache artifacts (latest
checkpoint, completed answer) and treats every adapter scan as a disposable
request-local buffer. Distinct requests that traverse the same relationship
edges — peers who share an account or group, every subject checking the same
resource (`can?` reverse-walks from the resource), sparse graphs where most
intermediate scans are empty — re-issue identical adapter scans.

Measured on this machine (REPL, in-memory stores; details in the review):

- A sparse high-sharing forward page issues 20–40 adapter scans; an exact
  scan-response cache elides 94–100 % of them with identical result
  sequences, cutting reducer time 331→178 µs/page on Datomic-mem and
  1,083→~400 µs (`:memory`) / 282→~170 µs (`:file`) per page on Datahike.
- On shallow schemas over an in-memory Datomic peer a page issues 3–5 scans
  of ~11 µs each; the whole reducer walk is ~100 µs. There the cache is
  worth ≤ 10 % of a page and only after the prerequisite below.
- The dominant cost of a Datomic cache-miss page today is not traversal at
  all: the sealed-plan cache misses on every request (random per-request
  `source-lifecycle` in `eacl.datomic.impl/with-request-engine`), so
  `seal-plan` (~1 ms) reruns per call. Fixing that alone is worth
  2.4–6.6× on miss pages and 5.7× on `can?`. Per-request `read-schema` in
  `prepare` (~20 % of the remainder) is next.

The earlier "denotation" and "hierarchical segment" designs are unsound for
this reducer: the sequence a plan node emits depends on the request's global
admission set, so context-free node segments cannot be substituted into
stable enumeration (Dafny `CacheBoundary.dfy`,
`ContextFreeDenotationIsNotAStableTrace`). The one thing that *is* context
free is the adapter's response to one read descriptor. This change caches
exactly that, and nothing above it.

## Prerequisites (separate changes — implemented 2026-08-18)

1. `fix-datomic-request-overhead`: sealed-plan cache keyed by schema
   generation (no per-call random lifecycle, no revision), `seal-plan`
   encode-once, request validation on the miss path from the per-generation
   schema cache, `kernel?` memo. Measured on the live demo: `can?` miss
   6.0 ms → 142 µs, page miss 5.6 ms → ~490 µs.
2. `membership-probe-point-check`: `can?` decided by membership probes over
   the sealed plan (O(intermediates), never O(subjects)); denied check on a
   5,000-owner resource 16 ms → 24 µs; oracle-equal on every fixture and on
   4,860 live pairs.

Only after both is a traversal-level cache measurable; the gate below is
evaluated against that baseline.

## What Changes

- Introduce an **exact scan-response cache**: a client-private, weight-bounded,
  frontier-stamped store of *ascending prefixes of adapter scan responses*,
  keyed by the read-demand descriptor (operation, anchor type + internal id,
  relation id, target type) plus a validity scope (backend id, source scope
  and lifecycle, adapter fingerprint/identity contract, scan-order ABI,
  schema generation, **the generation of the one relation the descriptor
  scans**).
- Install it at the engine's single effectful seam (`fetch-fn`, built by
  `eacl.engine.v8/stable-fetch-fn`) as `caching-fetch-fn ∘ retrying-fetch-fn
  ∘ classified-fetch-fn ∘ adapter-fetch-fn`. The reducer, its limits, its
  telemetry counters, checkpoints and cursors are untouched.
- **Elide-only semantics.** For a fetch `(descriptor, bound, limit)` the cache
  answers only when it can reproduce the adapter's exact response — at least
  `limit` cached values strictly after `bound`, or a prefix known to be
  exhausted. Otherwise it issues *exactly the command the uncached run would
  issue* and uses the response to extend the prefix. It never widens a scan,
  never fetches ahead, never changes chunk size, never issues a command absent
  from cache-disabled execution.
- Make the fetch contract explicit: a chunk shorter than `:limit` means the
  scan is exhausted after its last value. The reducer already depends on this
  (`stable_reducer.cljc/fetch-values`); it becomes a normative obligation on
  every fetch-fn layer.
- Store: lock-free (`ConcurrentHashMap` keyed by validity scope + descriptor),
  striped or sampled CLOCK eviction, `LongAdder` metrics, per-entry prefix cap,
  tier weight budget; **not** the weighted-LRU `eacl.subproblem-cache` store
  (measured 1.4 µs/hit and 0.5 M ops/s under contention).
- Scope: forward and reverse scans alike — `lookup-resources`,
  `lookup-subjects`, `count-*`, and `can?` (reverse walk from the resource,
  shared across all subjects checking it).
- Config: `:scan-cache {:enabled? bool :max-weight n :max-prefix n}` under the
  client cache options; `:cache? false` bypasses lookup and deposit for one
  request; `expire-cache!` drops the store.
- Cleanup: remove the retired `:denotation` tier and the relationship-projection
  remnants (config keys with a deprecation window, tier state, dead metric
  keys, docs, stale spec language, verification claims naming deleted vars).
- Default: **off** until the benchmark gate passes; then on when the
  subproblem cache is enabled.

## Capabilities

### New Capabilities

- `exact-scan-response-cache`: elide-only, frontier-stamped reuse of exact
  adapter scan-response prefixes at the physical read seam.

### Modified Capabilities

- `bounded-physical-execution`: "exactly two closed cache artifacts" becomes
  "two semantic artifacts plus one physical accelerator"; cross-request chunk
  retention is permitted under a validity stamp; the short-chunk rule is
  normative. (Its source requirements are currently ADDED by the in-progress
  `adopt-stable-discovery-enumeration` change; this change must archive after
  it or fold its deltas into it.)
- `verified-subproblem-cache`: retire the "complete denotation" and
  "shared-subgraph denotation" language; the projection-chunk requirement is
  replaced by the new capability.
- `demand-bounded-evaluation`: the existing "cache retains only demanded work"
  requirement gains scenarios naming the scan-response cache.

### Removed / reconciled

- `managed-reuse-certification` requirement that docs "SHALL state that
  managed reuse applies to … completed denotations": stale since the
  stable engine; removed.

## Non-Goals

- No caching of any reducer-emitted sequence, plan-node "segment", composed
  multi-hop result, or completed traversal prefix. Those remain forbidden.
- No change to cursor envelopes, checkpoint identity, or completed-answer
  keys; no page-size sharing at the answer level (a `:first 20` after
  `:first 50` already hits every scan and pays ~50 reducer transitions).
- No reuse for `as-of` / `since` / filtered / speculative / caller-supplied
  database values (same policy as managed proof).
- No background pre-warming, no inverted indexes, no cross-process sharing.

## Impact

- **Performance**: 100–700 µs per page on Datahike and sparse-sharing
  Datomic workloads after warm-up; ≤ 10 % on shallow Datomic-mem schemas;
  `can?` on hot resources elides every reverse scan for every subject.
- **Correctness**: preserved by construction — the reducer sees exactly the
  adapter's response; validity reuses the proved scalar-frontier argument
  with a singleton dependency; trace refinement (`:cache? false` oracle)
  holds because the command set is a subset with equal responses.
- **Complexity**: one fetch-fn layer, one lock-free store, one small Dafny
  leaf, mutation controls; minus the removed denotation/projection remnants.
- **Memory**: bounded by `:max-weight` and `:max-prefix`; empty-scan entries
  are the smallest and (in sparse graphs) the most valuable.

## Risks

- Overhead when hit rate is zero (one map probe + binary search per scan);
  the gate requires ≤ 1 % regression with the cache enabled and cold.
- Concurrency: entries are immutable prefixes; concurrent extensions race
  benignly (any prefix of the same sequence is valid; longest wins).
- Formal surface: `verify-fast.sh` obligation count and assurance-matrix
  claims must be updated; `SubproblemCanReuse` in
  `ScalarFrontierCoherence.dfy` is stricter than needed and needs a
  singleton-frontier lemma.
- The prerequisite change may itself remove most of the visible win on
  Datomic; the gate decides.

## Success Criteria (benchmark gate, all in one process and fixture)

1. Cache-vs-`:cache? false` oracle equality on randomized graphs, all three
   backends, forward and reverse pages, counts, `can?`, with concurrent
   readers and interleaved supported writes.
2. Sparse high-sharing forward pages (Fixture B shape): ≥ 90 % adapter
   commands elided after warm-up; ≥ 25 % lower p50 miss-page latency than
   with the cache disabled on Datahike (`:file`) and ≥ 15 % on Datomic-mem,
   measured after the prerequisite fix.
3. `can?` on a hot resource by distinct subjects: ≥ 90 % reverse-scan
   commands elided after the first check.
4. Cold/disabled overhead: p50 of any measured operation regresses ≤ 1 %
   with the cache enabled and empty.
5. Existing gates unchanged: width/retention invariance, mutation controls,
   checkpoint/cursor suites, coherence suites, verify-fast Dafny batch.

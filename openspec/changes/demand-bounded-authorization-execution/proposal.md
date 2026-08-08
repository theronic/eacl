## Why

EACL v8 currently lets cache availability change the evaluator and stopping
condition: a cold recursive point check or bounded count can perform a complete
forward fixed point solely because caching is enabled, while `:cache? false`
returns the same semantic answer with target-local or limit-bounded work. That
violates the caller's reasonable cost model, compounds into long cache waits and
schema-lock convoys, and makes a performance option capable of changing whether
an otherwise valid request meets traversal limits or deadlines.

The v8 release should make caching semantically and operationally transparent:
select one immutable snapshot, do no more foreground semantic work than the
request demands, retain only work already demanded, and require an explicit
request mode for complete-denotation materialization.

## What Changes

- Make demand-bounded evaluation the default for point checks, bounded counts,
  and pages. Cache lookup/publication MUST NOT change traversal direction,
  generated command trace, fetched values, stopping condition, or resource-limit
  outcome on a cold miss.
- Add an explicit `:evaluation :complete-denotation` request option for callers
  that intentionally accept exhaustive work. There is no prewarm API and no
  adaptive or background completion after the request stops.
- Add one end-to-end monotonic execution deadline covering consistency
  selection, cache access, schema/plan resolution, traversal, backend commands,
  rendering, and optional completion. Deadline failure is typed and can never be
  converted into a denial, count, or partial page.
- Replace cache-owned scan chunking with exact generated command/response
  caching. A cache may satisfy or retain precisely requested work but may not
  enlarge a scan, fetch ahead, or continue traversal after the caller's demand
  is satisfied.
- Bound the entire cache attempt independently of the request deadline. Cache
  eligibility MUST NOT issue backend commands that cache-disabled execution
  would not issue, and typed lookups MUST reject oversized or over-budget
  reads/decodes as misses. An unavailable, invalid, or stale candidate therefore
  cannot turn a bounded authorization into an unbounded proof-lifting exercise.
- Remove caller-waiting cache coordination from ordinary authorization. Demand
  misses do not join a broader computation or wait for a cache-only semaphore;
  concurrent misses may compute independently and race bounded, best-effort
  publication.
- Remove the Datomic client-wide schema read/write lock from authorization
  operations. Every semantic input is derived from one selected immutable
  snapshot; schema and relationship writers use commit-time optimistic guards
  captured from the same snapshot used to calculate their transaction.
- Make recursive traversal order a versioned deterministic ABI independent of
  chunk size, batching, fuel, cache state, and page size, allowing `N+1`
  demand-bounded pagination without first materializing and sorting the complete
  denotation.
- **BREAKING**: recursive bare `:last` requires explicit complete-denotation
  evaluation; bounded forward/backward pages otherwise stop at the requested
  window plus one sentinel.
- **BREAKING**: DataScript becomes current-basis-only across requests. Remove
  `:at-exact-snapshot` capability and `:exact-snapshot-registry-size`; relevant
  cursor-proof changes return a typed stale-cursor/consistency-conflict error
  rather than retaining or silently reconstructing an old DB value.
- Require cache validation after consistency selection on every backend. A
  result computed on an older snapshot may be returned only when it is proven
  observationally equivalent on the selected snapshot; `:at-least-as-fresh`
  remains a causal lower bound and never licenses a stale answer.
- Add public provenance and internal metrics that separately report evaluation
  mode, cache lookup outcome, avoided backend work, and publication outcome.
- Replace the prior isolated latency claims with deterministic work-trace,
  concurrency, mutation-race, timeout, cache-parity, and cross-runtime release
  gates at broad-principal scales.

## Capabilities

### New Capabilities

- `demand-bounded-evaluation`: Cache-transparent evaluator selection, exact
  stopping rules, explicit complete-denotation execution, and retain-only-
  demanded-work semantics for point, count, and lookup operations.
- `authorization-deadlines`: End-to-end deadline propagation, bounded overrun,
  cancellation/publication rules, and typed failure behavior.
- `snapshot-coherent-concurrency`: Lock-free immutable-snapshot authorization,
  schema-plan generation scoping, and transaction-time writer validation.
- `nonblocking-cache-coordination`: Non-waiting cache hits/misses/publication,
  exact command/response retention, cache-attempt time/byte/work budgets,
  bounded races, lifecycle safety, and honest telemetry.
- `incremental-recursive-pagination`: Versioned traversal order, bounded page
  work, cursor replay/fallback, and mutation-safe continuation without complete
  denotation sorting.
- `datascript-current-basis-consistency`: Current-only DataScript selection,
  causal freshness, cache proof lifting, removal of historical snapshot
  retention, and stale-cursor behavior.

### Modified Capabilities

None. The only main specification is `modular-backend-workspace`; this change
does not alter its workspace/module contract.

## Impact

- Shared engine and cache: `modules/eacl/src/eacl/engine/v8.cljc`, generated
  indexed traversal boundaries, `subproblem_cache.cljc`, `cache.cljc`, cursor,
  relay, continuation, consistency, and client orchestration.
- Backends: Datomic schema/client locking and transaction guards; DataScript and
  Datahike stale-delta guards; DataScript capability and client-option removal;
  adapter scan command/response boundaries on all three backends.
- Public API: new `:evaluation` and `:timeout-ms` request controls; revised
  recursive `:last`, DataScript exact-snapshot, timeout-error, cursor, and cache
  provenance contracts. `:cache?` returns to being only a reuse/publication
  control.
- Cache provider contract: finite client-configured lookup time, encoded-byte,
  decoded-weight, and local-attempt bounds; typed artifact metadata must be
  inspectable without retrieving an unbounded value.
- Verification: generated evaluator/order theorems, cache-on/off trace
  refinement, deadline state, lifecycle races, writer interleavings, CLJ/CLJS
  parity, mutation controls, and performance gates.
- Documentation and consumers: cache and consistency guides, v8 release notes,
  migration notes, explorer/demo guidance, and the analysis report at
  `docs/reports/2026-08-08-recursive-point-check-denotation-cache-implications.md`.
- Supersession: where they conflict, this change replaces the full-denotation,
  cache single-flight, sorted-recursive-keyset, route-change continuation, and
  DataScript exact-registry requirements in `add-verified-subproblem-cache`,
  `eacl-v8-root-fixes`, and `redesign-cross-backend-freshness-cache`.

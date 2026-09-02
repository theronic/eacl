# Performance amplification source closure

Base source: e137dc55512d4eeebcc31cfbe5087d61ab04465b.

This note records the task-1 source closure behind the finalized mechanism
ledger. Each retained row names the current production symbol and durable Core
requirement that controls whether the implementation is correct. Archived
reports and retired symbols are context only and are not evidence.

## Engine and planning claims

| Claim accepted for reproduction | Live production source | Current requirement |
| --- | --- | --- |
| Least-path builds positive bounded descriptors but its direct adapter invoker omits the descriptor limit. | [least_path.cljc adapter-fetch-fn](../../../../modules/eacl/src/eacl/engine/least_path.cljc#L31) and [physical.cljc realize-chunk](../../../../modules/eacl/src/eacl/engine/physical.cljc#L53) | [Physical response vectors are reused and make verifiable progress](../../../../openspec/specs/stable-engine-performance/spec.md) |
| Stable execution appends every emission to a transient result vector even for scalar exact count. | [stable_reducer.cljc emit](../../../../modules/eacl/src/eacl/engine/stable_reducer.cljc#L315) and [initial-state](../../../../modules/eacl/src/eacl/engine/stable_reducer.cljc#L460) | [Output retention is bounded by operation demand](../../../../openspec/specs/stable-engine-performance/spec.md) and [Proven traversal, lookup, and count equivalence](../../../../openspec/specs/formally-verified-authorization-engine/spec.md) |
| Stable completion rebuilds result-width uniqueness state although exact admission is the constructional authority. | [stable_reducer.cljc finish](../../../../modules/eacl/src/eacl/engine/stable_reducer.cljc#L521) | [Duplicate freedom is constructional on the production path](../../../../openspec/specs/stable-engine-performance/spec.md) and [Proven traversal, lookup, and count equivalence](../../../../openspec/specs/formally-verified-authorization-engine/spec.md) |
| Releasing one value uses repeated suffix views and retained-buffer bookkeeping rescans all retained values. | [stable_reducer.cljc retain-buffer and release-one](../../../../modules/eacl/src/eacl/engine/stable_reducer.cljc#L190) | [Reducer bookkeeping has bounded per-transition cost](../../../../openspec/specs/stable-engine-performance/spec.md) |
| Sidecar recency rebuilds a vector on each touch; continuation churn has analogous material scan candidates that must be measured independently. | [stable_reducer.cljc retain-buffer](../../../../modules/eacl/src/eacl/engine/stable_reducer.cljc#L190) and [continuation.cljc](../../../../modules/eacl/src/eacl/continuation.cljc) | [Reducer bookkeeping has bounded per-transition cost](../../../../openspec/specs/stable-engine-performance/spec.md) |
| General scheduling allocates batch-local state even for zero and one successor transitions. | [stable_reducer.cljc schedule](../../../../modules/eacl/src/eacl/engine/stable_reducer.cljc#L121) | [Reducer bookkeeping has bounded per-transition cost](../../../../openspec/specs/stable-engine-performance/spec.md) and [Proven termination and fail-closed limits](../../../../openspec/specs/formally-verified-authorization-engine/spec.md) |
| The live sealed planner has no exact arrow-target alias canonicalization, while the main spec already requires it. | [sealed_plan.cljc seal-plan](../../../../modules/eacl/src/eacl/engine/sealed_plan.cljc#L447) | [Exact semantic aliases do not duplicate traversal](../../../../openspec/specs/implementation-simplicity-and-performance/spec.md) and [Pure permission alias frontier optimization matches production](../../../../openspec/specs/formal-implementation-conformance/spec.md) |
| Rank costs exist in both order-contract and local-read-cost production values. | [sealed_plan.cljc order-contract and local-read-cost](../../../../modules/eacl/src/eacl/engine/sealed_plan.cljc#L33) | [Stable rank costs have one fingerprinted production identity](../../../../openspec/specs/stable-engine-performance/spec.md) |

## Request and cache claims

| Claim accepted for reproduction or correctness audit | Live production source | Current requirement |
| --- | --- | --- |
| Request contexts eagerly allocate proof, four memo atoms, a publication buffer, and close state before demand proves they are needed. | [request context make-context](../../../../modules/eacl/src/eacl/request/context.cljc#L144) | [Each invariant is validated at its owning boundary](../../../../openspec/specs/authorization-request-efficiency/spec.md) |
| Constant internal counter increments still validate a keyword and perform a map lookup; these counters are semantic aggregate meters despite the observation-only namespace text. | [request counters add!](../../../../modules/eacl/src/eacl/request/counters.cljc#L62) and [batch aggregate-counters](../../../../modules/eacl/src/eacl/authorization/batch.cljc#L280) | [Mandatory resource meters are exact and observation is optional](../../../../openspec/specs/authorization-request-efficiency/spec.md) and [Engine resource measures are dimensionally separate](../../../../openspec/specs/verified-subproblem-cache/spec.md) |
| Cache metadata calls adapter snapshot-id after basis semantic identity already captured it. | [orchestration adapter-semantic-identity](../../../../modules/eacl/src/eacl/client/orchestration.cljc#L187) and [cached-engine-result](../../../../modules/eacl/src/eacl/client/orchestration.cljc#L632) | [Each invariant is validated at its owning boundary](../../../../openspec/specs/authorization-request-efficiency/spec.md) |
| Direct-membership dispatch normalizes singleton probes, then grouped native/scalar batches normalize the same invariant request shape again. | [direct_membership.cljc dispatch and direct-match-many?](../../../../modules/eacl/src/eacl/backend/direct_membership.cljc#L139) | [Certified synchronous membership batches retain aligned positional results](../../../../openspec/specs/authorization-request-efficiency/spec.md) |
| Completed hits synchronously touch LRU state and several telemetry atoms. | [subproblem_cache.cljc lookup!](../../../../modules/eacl/src/eacl/subproblem_cache.cljc#L823) and [cache.cljc resolve-basis!](../../../../modules/eacl/src/eacl/cache.cljc#L1140) | [Exact cache correctness is independent of recency and telemetry mutation](../../../../openspec/specs/authorization-request-efficiency/spec.md) and [Recency-honest eviction and admission](../../../../openspec/specs/answer-cache-bounding/spec.md) |
| Resident derived and plan hits allocate candidate delays before discovering the existing value. | [engine v8 memoized-derived!](../../../../modules/eacl/src/eacl/engine/v8.cljc#L545) and [stable-plan](../../../../modules/eacl/src/eacl/engine/v8.cljc#L1068) | [Plan and request memo hits are read-first](../../../../openspec/specs/authorization-request-efficiency/spec.md) |
| Parsed schema, validation catalog, expression decode, and sealed-plan cold misses publish delays that make peers wait and can inherit another request's failure/deadline. | [orchestration request-schema](../../../../modules/eacl/src/eacl/client/orchestration.cljc#L915), [expression persistence decode](../../../../modules/eacl/src/eacl/schema/expression_persistence.cljc#L155), and [engine stable-plan](../../../../modules/eacl/src/eacl/engine/v8.cljc#L1068) | [Authorization does not wait for cache computation](../../../../openspec/specs/nonblocking-cache-coordination/spec.md) and [Plan and request memo hits are read-first](../../../../openspec/specs/authorization-request-efficiency/spec.md) |
| Completed cache misses are already independent; durable flight/join requirements contradict shipped source and the nonblocking spec. | [subproblem_cache.cljc resolve-independent!](../../../../modules/eacl/src/eacl/subproblem_cache.cljc#L706) | [Authorization does not wait for cache computation](../../../../openspec/specs/nonblocking-cache-coordination/spec.md), [single-flight-coordination](../../../../openspec/specs/single-flight-coordination/spec.md), and [Retained weight and actual callback execution are bounded](../../../../openspec/specs/verified-subproblem-cache/spec.md) |
| Restored entries are marked validated after generic snapshot validation; lookup then trusts that flag without running the operation-specific validator supplied in options. | [subproblem_cache.cljc restore-store and lookup!](../../../../modules/eacl/src/eacl/subproblem_cache.cljc#L598) | [Completed exact hits avoid only work whose compatibility is already proved](../../../../openspec/specs/authorization-request-efficiency/spec.md) and [Cache values denote complete immutable subproblems](../../../../openspec/specs/verified-subproblem-cache/spec.md) |
| Completed semantic keys do not yet expose the full compiler/order/fingerprint-algorithm/value compatibility identity required for restored values across this rollout. | [cache.cljc exact-basis-key](../../../../modules/eacl/src/eacl/cache.cljc#L106) and [orchestration cached-engine-result](../../../../modules/eacl/src/eacl/client/orchestration.cljc#L632) | [Completed exact hits avoid only work whose compatibility is already proved](../../../../openspec/specs/authorization-request-efficiency/spec.md) and [Semantic keys separate every answer-affecting input](../../../../openspec/specs/verified-subproblem-cache/spec.md) |
| Current-cache selection traverses the generated boundary repeatedly over a finite stage/availability domain. | [cache.cljc current-cache-action](../../../../modules/eacl/src/eacl/cache.cljc#L1003) | [Finite cache decisions use a mechanically checked specialization](../../../../openspec/specs/authorization-request-efficiency/spec.md) and [Formal claims match shipped implementation semantics](../../../../openspec/specs/formal-implementation-conformance/spec.md) |

## Datomic and formal-consumer claims

| Claim accepted for reproduction or correctness audit | Live production source | Current requirement |
| --- | --- | --- |
| Datomic exact acquisition unconditionally starts targeted sync before as-of, even when the locator is already locally covered. | [Datomic backend source acquire-exact](../../../../modules/eacl-datomic/src/eacl/datomic/backend.clj#L445) | [Datomic forward-history selection](../../../../openspec/specs/backend-native-revision-consistency/spec.md) and [Datomic exact acquisition synchronizes only when the captured local basis is behind](../../../../openspec/specs/authorization-request-efficiency/spec.md) |
| EACL-FORMAL-065 and SchemaPlanCost cite retired frontier-permission-paths rather than the live sealed planner. | [assurance matrix source and operation rows](../../../../formal/verification/assurance-matrix.edn#L188) and [live sealed planner](../../../../modules/eacl/src/eacl/engine/sealed_plan.cljc#L447) | [Formal claims match shipped implementation semantics](../../../../openspec/specs/formal-implementation-conformance/spec.md) and [Formal performance claims name current production consumers](../../../../openspec/specs/performance-assurance/spec.md) |

## Lore methods used

Lore code is not linked or executed as part of Core. The investigation adopts
only these methods from its current documentation:

- bind every measurement to source, dependency/classpath, runtime, environment,
  fixture, demand, and artifact identity;
- distinguish cumulative work, allocation, retained gauges, backend operations,
  and elapsed time instead of treating them as interchangeable;
- preserve proved, refuted, and unknown/unsupported outcomes instead of filling
  absent metrics with zero;
- test scaling at multiple sizes and bind source-level demand/retention claims to
  the actual runtime artifact;
- treat prior EACL PR #101 cache-flight results as historical context, not a
  theorem or current-source result.

Sources: /Users/petrus/code/lore/docs/source-artifact-workflow.md,
/Users/petrus/code/lore/docs/certified-analysis.md, and
/Users/petrus/code/lore/docs/research/eacl-pr101-resource-analysis.md.

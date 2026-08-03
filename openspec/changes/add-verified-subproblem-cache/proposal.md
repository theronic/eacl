## Why

EACL's completed-answer cache cannot reuse work between distinct top-level
authorization questions, so overlapping recursive queries repeatedly traverse
the same graph even when their shared subproblems are unchanged. EACL needs a
bounded shared-subproblem cache whose hits are observationally identical to
cache-free evaluation, whose reuse is measurable across different semantic
request keys, and whose public execution path is connected to machine-checked
semantics rather than merely tested beside them.

## What Changes

- Add request-local and client-local caching of completed authorization
  subproblems, including recursive strongly connected components, with explicit
  semantic keys, immutable-snapshot identity, schema identity, contextual
  inputs, and backend/source identity.
- Cache only completed fixed-point results; never publish partial worklists,
  partial pages, or traversal-order-dependent state.
- Reuse exact-snapshot subproblems without proof lifting and reuse current
  subproblems across forward revisions only when a complete localized
  dependency proof demonstrates that every relevant projection is unchanged.
- Bound memory, entry cost, and concurrent duplicate work with weighted
  eviction and single-flight publication while preserving the existing
  cache-disabled evaluator as the reference semantics.
- Add provenance and metrics that distinguish completed-answer hits,
  exact and managed subproblem hits, proof failures, evictions, and avoided
  backend reads.
- Add adversarial differential, cross-backend, temporal, mutation, and
  shared-subgraph performance suites. A release gate will require distinct
  top-level queries sharing a subgraph to outperform the current completed-
  answer cache while cache-free behavior does not regress beyond its recorded
  threshold.
- Extend the formal semantics with subproblem denotation, recursive fixed-point
  completion, cache-key separation, dependency framing, lifecycle races,
  bounded resource accounting, and refinement from every public EACL operation
  through its CLJ and CLJS boundary.
- Keep the verification manifest withheld unless every public operation,
  generated boundary, adapter assumption, performance gate, and required
  independent review is satisfied. No partial proof may be described as
  end-to-end formal verification.

## Capabilities

### New Capabilities

- `verified-subproblem-cache`: Defines sound shared-subgraph reuse, bounded
  lifecycle behavior, observability, cross-backend parity, performance gates,
  and the formal-refinement evidence required for the cache and public engine.

### Modified Capabilities

None.

## Impact

- Affected shared runtime: authorization compilation and traversal,
  `eacl.engine.v8`, `eacl.engine.indexed`, cache orchestration, pagination, and
  public `can?`, lookup, count, and relationship operations.
- Affected clients and adapters: Datomic Pro, Datahike, and DataScript client
  cache state, snapshot/source identities, dependency proofs, and conformance
  reports.
- Affected verification artifacts: Dafny semantics and generated kernels, TLA+
  lifecycle models, CLJ/CLJS conversion boundaries, assurance matrix,
  verification manifest, mutant corpus, and release documentation.
- Affected performance evidence: cache/no-cache baselines, distinct-query
  shared-subgraph workloads, backend-read counters, latency/throughput/heap
  thresholds, and CI regression gates.
- No new production dependency and no authorization-semantic API break are
  intended. Cache state remains private, optional, bounded, and removable
  without changing results.

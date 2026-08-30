## Why

EACL currently treats a request's shrinking execution timeout as part of several successful-result cache identities, so semantically identical page and count requests miss repeatedly even on one immutable basis with ample unused capacity. Separately, page-navigation publication and eviction rebuild or scan capacity-sized collections, making a bounded cache progressively slower as its configured capacity grows.

## What Changes

- Define successful answer and page identities in terms of result-affecting semantics, excluding per-invocation execution controls such as timeout, cancellation, and cache-publication policy while retaining all query, demand, consistency-basis, engine, ordering, and compatibility identities.
- Preserve deadline and cancellation correctness on cache hits: each invocation still establishes and checks its own absolute deadline and cancellation state, and expired or cancelled requests never turn a resident answer into an authorization value.
- Replace capacity-linear page-cache recency and eviction bookkeeping with bounded direct indexes and generation-stamped queue metadata whose normal publication and eviction work is independent of resident cache cardinality.
- Expose read-only page-cache structural/publication statistics sufficient to diagnose identity fragmentation and boundedness without introducing shared mutation on cache-hit paths.
- Add cross-runtime semantic tests, deadline/cancellation regressions, operation-count assertions, and multi-capacity benchmarks covering cache miss, exact hit, adjacent reverse/forward navigation reuse, publication, and eviction.
- Qualify the complete candidate with the repository's unit, cross-runtime, formal/conformance, and multi-size performance battery rather than a single cardinality or operation.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `authorization-request-efficiency`: Successful completed-answer and page-navigation cache identities exclude invocation-only execution controls while continuing to partition every result-affecting semantic and compatibility dimension; page-cache diagnostics remain read-only on hits.
- `authorization-deadlines`: A compatible warm answer may be reused across different timeout budgets only after the current invocation's deadline and cancellation checks, and an expired or cancelled invocation remains non-cacheable and non-authorizing.
- `stable-engine-performance`: Page-navigation cache publication, replacement, aliasing, and eviction use bounded direct/generation-stamped bookkeeping rather than rebuilding or scanning resident-capacity collections.
- `performance-assurance`: Cache identity and page-cache structural changes are qualified across multiple cache capacities, cache regimes, navigation directions, and both supported runtimes as part of the complete affected test and benchmark battery.

## Impact

- Core cache identity construction, page-request relay keys, completed count/page lookup keys, page-navigation storage, eviction metadata, cache statistics, and portable CLJ/CLJS tests and benchmarks.
- Persisted cache snapshots may contain older timeout-bearing entries that are no longer reachable by the corrected identity; restore must either reject them through the existing compatibility identity or safely leave them as bounded dead entries without ever serving them under the new key.
- No public API shape, authorization denotation, result order, cursor interpretation, consistency mode, resource limit, default timeout, or adapter ABI changes.
- Demo traffic benefits without a demo-specific workaround: requests queued by the HTTP admission layer may carry different remaining budgets yet share compatible immutable EACL results.

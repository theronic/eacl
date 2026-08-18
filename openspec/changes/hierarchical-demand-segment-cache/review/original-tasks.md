# Tasks: Hierarchical Demand-Compatible Segment Cache

## 1. Spec and contract scaffolding

- [ ] 1.1 Add capability notes / delta requirements for hierarchical segments (demand prefix rule, frontier stamp, no widening) under the cache domain
- [ ] 1.2 Document non-goals (no public cursor change, no time-travel segment reuse, no background pre-warm)
- [ ] 1.3 Define config surface: `:segment-cache {:enabled? true}`, `:segment-max-weight`, optional reuse of denotation budget keys with clear deprecation note

## 2. Data model and store

- [ ] 2.1 Implement segment key schema (tier, level, plan-node/path, start-set-fp, demand, validity)
- [ ] 2.2 Implement segment value schema (ordered-eids, count, min/max, continuation, via, summaries, weight, hits)
- [ ] 2.3 Add segment tier to the client-private subproblem cache store with weighted LRU admission
- [ ] 2.4 Implement start-set fingerprint helper (sorted canonical eids → stable hash)
- [ ] 2.5 Reject overweight segments; never displace unbounded; track admission rejects in stats

## 3. Validity and proof integration

- [ ] 3.1 Attach schema-stamp + dependency-stamp (max relation-version over segment relations) + lifecycle + plan-fingerprint on every deposit
- [ ] 3.2 Reuse existing proof-frame / frontier computation for the relations touched by a plan node
- [ ] 3.3 Ensure frontier mismatch forces miss (no partial or stale segment reuse)
- [ ] 3.4 Confirm segments are unavailable for filtered / speculative / caller-constructed / non-deterministic views (same policy as managed proof)

## 4. Sealed-plan integration

- [ ] 4.1 Expose stable plan-node ids or path vectors from the sealed-plan compiler for use as segment keys
- [ ] 4.2 Ensure plan-fingerprint changes invalidate segment keys (same as cursor/answer fingerprint rules)
- [ ] 4.3 Map single relation hops and multi-node paths to level-1 vs level-k keys consistently

## 5. Demand-bounded reducer integration

- [ ] 5.1 Before expanding a plan node, attempt segment lookup under current demand, start-set-fp, and frontier
- [ ] 5.2 On hit with demand′ ≥ request demand: take ordered prefix (and optional intermediate filter); do not expand further for those results
- [ ] 5.3 On miss: run ordinary demand-bounded expansion
- [ ] 5.4 After a successful demand-bounded walk of a node/path, deposit a segment containing only the eids actually produced under that demand
- [ ] 5.5 Enforce: deposit never writes more eids than the demand that produced them
- [ ] 5.6 Enforce: lookup never increases effective demand or issues extra backend scans

## 6. Composition

- [ ] 6.1 Implement demand-aware compose of level-1 segments (or segment + fresh hop) stopping at remaining demand
- [ ] 6.2 Record `:via` intermediate eids and segment-refs for composed entries
- [ ] 6.3 Preserve stable-discovery order and uniqueness across composition
- [ ] 6.4 Decide and document v1 policy: store composed level-k eagerly vs compose on read only (prefer one approach for first merge)

## 7. Pagination and page-size sharing

- [ ] 7.1 Confirm segment keys omit final page size; only demand is stored
- [ ] 7.2 Verify `:first 50` segment can satisfy later `:first 20` (prefix) under same frontier and start-set
- [ ] 7.3 Verify larger demand cannot be satisfied from a smaller segment (miss → evaluate)
- [ ] 7.4 Ensure public cursors and completed-answer keys still include page size / bounds; no change to cursor wire format
- [ ] 7.5 Exercise cursor continuation after a segment-prefix hit; deterministic replay still correct on miss

## 8. Metrics and observability

- [ ] 8.1 Extend `cache-stats` with segment hits, misses, prefix truncations, composition counts, admission rejects, weight used
- [ ] 8.2 Log or counter for frontier-mismatch segment rejects
- [ ] 8.3 Optional debug: expose whether a page was satisfied from segment vs full reducer (internal only)

## 9. Tests — correctness

- [ ] 9.1 Unit: prefix rule (D′ ≤ D hit, D′ > D miss)
- [ ] 9.2 Unit: frontier advance invalidates segment
- [ ] 9.3 Unit: different start-set fingerprints do not hit
- [ ] 9.4 Unit: composition preserves order and stops at demand
- [ ] 9.5 Integration: cache vs `:cache? false` oracle equality for point checks and pages
- [ ] 9.6 Integration: super-user large demand warms segments; normal-user smaller demand hits prefixes
- [ ] 9.7 Integration: same subject, different page sizes share segment when demand allows
- [ ] 9.8 Integration: unrelated transaction does not invalidate; relationship write on a dependency relation does
- [ ] 9.9 Integration: recursive schema under demand limits deposits only walked results
- [ ] 9.10 Regression: existing completed-answer, checkpoint, and cursor suites still pass

## 10. Tests — performance / overlap (benchmarks)

- [ ] 10.1 Add or extend benchmark: super-user page warm-up then normal-user pages on shared schema
- [ ] 10.2 Measure segment hit rate and latency delta vs segment-cache disabled
- [ ] 10.3 Confirm no pathological memory growth under weight budgets
- [ ] 10.4 Document expected win conditions (high intermediate sharing) and non-win cases (disjoint subjects)

## 11. Formal / coherence notes

- [ ] 11.1 Draft soundness statement: segment-derived sequence equals demand-bounded reducer sequence under same snapshot, start set, plan node, demand
- [ ] 11.2 Draft safety statement: no extra results beyond demand; no use after frontier advance
- [ ] 11.3 Align with existing Dafny/TLA+ cache theorems or add targeted properties + randomized cache-vs-bypass tests
- [ ] 11.4 Update docs/cache.md and stable-discovery notes: segments are internal, partial denotations remain non-reusable as completed answers

## 12. Documentation and rollout

- [ ] 12.1 Update cache.md: segment tier, prefix rule, page-size sharing, config keys
- [ ] 12.2 Update v8 backend / upgrade notes if any migration or default-off flag is required
- [ ] 12.3 Default: segment cache enabled only when subproblem-cache is enabled; provide explicit disable
- [ ] 12.4 Changelog entry describing non-breaking additive behavior

## 13. Out of scope (explicit non-tasks for this change)

- [ ] 13.1 Public authenticated segment continuations inside cursors
- [ ] 13.2 Background pre-warming or complete-denotation auto-fill
- [ ] 13.3 Global inverted resource-side indexes
- [ ] 13.4 Arbitrary as-of/time-travel segment reuse
- [ ] 13.5 Changing completed-answer or cursor identity schemas

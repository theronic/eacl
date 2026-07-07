# Reverse Pagination Performance Confidence Plan

Date: 2026-05-17

Related:

- [2026-05-15 reverse pagination plan](./2026-05-15-reverse-pagination-before-after-plan.md)
- [2026-05-15 reverse pagination plan critique](../reports/2026-05-15-reverse-pagination-plan-critique.md)
- [2026-03-28 recursive arrow cycle fix plan v2](./2026-03-28-eacl-recursive-arrow-cycle-fix-plan-v2.md)
- [2026-03-28 recursive arrow cycle critique](../reports/2026-03-28-eacl-recursive-arrow-cycle-fix-plan-critique.md)

## Direct Answer

No. The current reverse-pagination strategy is not factually 100% confidence-worthy.

The public API shape is right: `:first/:after`, `:last/:before`, stable encrypted page tokens, and `:page-info` are the correct interface for forward and reverse cursor pagination. The weak point is the performance proof. The current benchmark validates the acyclic indexed path, but it does not validate every list path that the public README now describes as cursor-seeked and at-rest ordered.

The main loophole is `recursive-forward-lookup` in `src/eacl/datomic/impl/indexed.clj`. It detects recursive permission SCCs, computes the full least-fixed-point result set for the subject, sorts all matching eids, and then pages from that sorted collection via `page-eids-from-sorted`. This is correct for small recursive fixtures, but it is not cursor-seek pagination:

- forward pages with `:after` can scan from the start to the cursor
- reverse pages with `:before` scan and count everything before the cursor
- every page recomputes the recursive closure before pagination begins
- stable per-page latency would not prove bounded cursor work, because a full recompute can be equally slow on every page

The new strategy must make the list engine impossible to mischaracterize: every supported paginated list operation must be backed by an ordered, bounded source that can seek directly to the cursor boundary. If a query cannot satisfy that contract from the relationship indexes alone, it must use a materialized ordered grant index or fail loudly until that index exists. It must never silently fall back to full materialization plus sorting for a paginated list API.

## Current System Examined

### Public API and Token Layer

Files examined:

- `src/eacl/core.clj`
- `src/eacl/datomic/core.clj`
- `README.md`
- `docs/index.md`

Current behavior:

- List APIs accept `:first/:after` and `:last/:before`.
- `:cursor` and `:limit` are rejected for list pagination.
- Public tokens are encrypted, authenticated, query-shaped, and stable-basis.
- `pagination-context` chooses a stable `d/as-of` db for subsequent pages.
- `internal-page-query` translates decoded public page tokens into internal `:after` or `:before` edge bounds.
- `encode-page-info` returns encrypted `:start-cursor` and `:end-cursor`.

This layer is directionally sound. It should be retained, but tests must prove that every lower-level list engine respects the decoded bound efficiently.

### Acyclic `lookup-resources` and `lookup-subjects`

Files examined:

- `src/eacl/datomic/impl/indexed.clj`
- `src/eacl/lazy_merge_sort.clj`
- `test/eacl/bench/pagination_test.clj`

Current behavior:

- Non-recursive `lookup-resources` and `lookup-subjects` call `lookup`.
- `lookup` calls `lazy-merged-lookup`.
- `lazy-merged-lookup` passes `{:direction :asc|:desc :bound-eid ...}` into each path traversal.
- Direct relation scans use `d/seek-datoms` for forward pages and `d/rseek-datoms` for reverse pages.
- Multiple sorted path results are merged and deduplicated lazily.
- The page engine realizes only `page-size + 1` merged results.
- Reverse pages normalize scan order back to canonical at-rest order by reversing only the page-sized result window.

This path is close to the desired foundational design. It still needs structural tests that prove the cursor bound is carried into every source, not only timing tests.

### Recursive `lookup-resources`

Files examined:

- `src/eacl/datomic/impl/indexed.clj`
- `test/eacl/datomic/impl/indexed_test.clj`
- `docs/plans/2026-03-28-eacl-recursive-arrow-cycle-fix-plan-v2.md`

Current behavior:

- `lookup-resources` branches on `recursive-permission-query?`.
- Recursive permission queries call `recursive-forward-lookup`.
- `recursive-forward-lookup` calls `recursive-resource-eids`.
- `recursive-resource-eids` uses a monotonic fixed-point solver over sets.
- The final result set is sorted and passed to `page-eids-from-sorted`.

This is the main correctness/performance conflict. The fixed-point set solver was a pragmatic correctness repair for recursive permission support, but it is incompatible with the stronger reverse-pagination performance claim.

### `read-relationships`

Files examined:

- `src/eacl/datomic/impl.clj`
- `src/eacl/datomic/core.clj`

Current behavior:

- `read-relationships` now pages directly over Datomic relationship datoms.
- It uses `d/seek-datoms` for forward pages and `d/rseek-datoms` for reverse pages.
- It returns relationships in Datomic at-rest order, not an application-level `sort-by`.
- It still applies some filters after the datom scan.
- The selected tuple prefix is currently conservative: subject anchored scans use subject type; resource anchored scans use resource type; global scans choose forward or reverse based on available type filters.

This path is substantially better than the previous `sort-by` version, but the plan should tighten prefix construction so relation and resource/subject type filters are pushed into the Datomic tuple seek whenever their tuple order permits it.

### Current Benchmark

Files examined:

- `test/eacl/bench/pagination_test.clj`

Current behavior:

- The benchmark schema is multi-path but acyclic.
- It exercises `server/view = account->admin + team->admin + vpc->admin + shared_admin`.
- It validates forward and reverse pages over 40 pages.
- It compares early and late page medians.
- It does not assert that the recursive branch is not used.
- It does not test a recursive permission SCC.
- It does not structurally count scanned datoms or realized source rows.

The benchmark proves that the acyclic multi-path path is currently fast on the benchmark data. It does not prove that every paginated API path is cursor-bound.

## Confidence Loop

### Loop 1 - Current Strategy

Claim: EACL v7.1 supports performant forward and reverse cursor pagination for list APIs.

Loopholes found:

- Recursive `lookup-resources` pages from a full sorted result set.
- Benchmarks do not exercise recursive `lookup-resources`.
- Timing drift does not prove bounded cursor work.
- Read relationship filters can still be applied after scanning rows that could have been excluded by a tuple prefix.
- There is no internal plan type or instrumentation that tests can assert against.
- Public docs make a broad performance claim that is stronger than the test coverage.

Verdict: not 100% confident.

### Loop 2 - Minimal Fix Strategy

Potential fix: keep recursive full-set lookup, add benchmark coverage, and document recursive pagination as slower.

Remaining loopholes:

- The public list API would still silently use a non-cursor algorithm for a supported query.
- A full recompute per page could pass a stable-latency test while violating the real-time UI goal.
- Users would still need to know which schemas are secretly outside the performance model.

Verdict: not acceptable.

### Loop 3 - Strong Strategy

Potential fix: require every supported paginated list query to compile to an ordered page source. Recursive permission SCCs must either:

- read from a materialized effective-grant index, or
- fail loudly with a typed exception until such an index exists.

Additional fix: add structural tests and benchmarks that prove:

- the selected query plan kind is the expected one
- cursor bounds are carried into source scans
- reverse pages use `rseek-datoms`
- no paginated list path calls `page-eids-from-sorted` or sorts a full result set
- recursive list pagination is either backed by grant indexes or explicitly unavailable

Remaining loopholes:

- Materialized grants add write-time cost and storage amplification.
- Fully incremental grant maintenance is complex for arbitrary schema changes.
- A first implementation could recompute recursive grants synchronously and still meet read-side guarantees, but write costs must be documented and benchmarked.

Fix:

- Treat materialized recursive grants as a read-side index, not as a general replacement for relationship traversal.
- Use it only for recursive SCC permissions.
- Start with a correct synchronous rebuild path because this branch is still in development and no migration path is required.
- Add write-side benchmarks separately from read pagination benchmarks.
- Keep the acyclic direct-index traversal as the default fast path.

Verdict: decision-level confidence. After the TDD phases below pass, the implementation can factually claim that supported paginated list reads are cursor-bound, and recursive paginated reads are no longer silently full-scan fallbacks.

## Foundational Redesign

If reverse pagination and performance verification had been foundational assumptions from the start, EACL would have separated three concerns:

1. Public pagination arguments and tokens.
2. Query planning into an explicit ordered source.
3. Page realization from that ordered source.

The implementation should be reshaped around that boundary.

### New Internal Invariants

- A paginated list function must return results in canonical at-rest order.
- Forward pages use `:first` plus optional `:after`.
- Backward pages use `:last` plus optional `:before`.
- Reverse scans may read descending internally, but only page-sized windows may be reversed before returning to the user.
- No list API may page from a full sorted collection.
- No list API may hide a non-cursor implementation behind the same public pagination contract.
- Every supported list query must expose an internal plan kind that tests can assert.
- Every ordered source must accept a cursor bound before it begins producing rows.
- Every benchmark must verify the branch it is benchmarking.

## Recommended Architecture

### 1. Introduce Explicit Page Plans

Add an internal planning layer in `src/eacl/datomic/impl/indexed.clj` or a new namespace such as `eacl.datomic.impl.indexed.plan`.

Represent list planning explicitly:

```clojure
{:kind :acyclic-permission-stream
 :op :lookup-resources
 :order [:eid :asc]
 :direction :asc
 :source source-fn}
```

```clojure
{:kind :recursive-effective-grant-stream
 :op :lookup-resources
 :order [:eid :asc]
 :direction :desc
 :source source-fn}
```

```clojure
{:kind :relationship-datom-stream
 :op :read-relationships
 :order [:relationship-datom :asc]
 :direction :asc
 :source source-fn}
```

The plan must be returned or observable in tests. A private helper is acceptable if tests can access it through `#'namespace/private-var`, but a small internal public function is cleaner:

```clojure
(defn explain-list-plan [db op query] ...)
```

TDD requirements:

- [ ] Add tests asserting that acyclic `lookup-resources` explains as `:acyclic-permission-stream`.
- [ ] Add tests asserting that `lookup-subjects` explains as `:acyclic-permission-stream`.
- [ ] Add tests asserting that `read-relationships` explains as `:relationship-datom-stream`.
- [ ] Add tests asserting that recursive `lookup-resources` explains as `:recursive-effective-grant-stream` after grant indexes exist.
- [ ] Before grant indexes exist, add a temporary failing test asserting recursive pagination does not explain as `:full-sorted-set`.

### 2. Centralize Page Realization

Create one helper that realizes pages from ordered sources:

```clojure
(defn realize-page
  [{:keys [direction size bound]} ordered-items]
  ...)
```

Rules:

- Source sequences are already exclusive of the bound.
- Source sequences are in scan order.
- Ascending scan order is canonical order.
- Descending scan order is reversed only after taking the page-sized window.
- Sentinel realization is always `size + 1`.
- `:has-next-page?` and `:has-previous-page?` are derived in one place.

Replace ad hoc pagination code in:

- `lookup`
- `relationship-page`
- any recursive grant stream implementation

TDD requirements:

- [ ] Add pure unit tests for `realize-page` using small in-memory sequences.
- [ ] Assert reverse `[:desc]` input `[9 8 7 6]` with size `3` returns `[7 8 9]`.
- [ ] Assert `:has-next-page?` and `:has-previous-page?` semantics for unbounded, `:after`, and `:before` cases.
- [ ] Assert only `size + 1` source elements are realized.

### 3. Remove `page-eids-from-sorted` from List Pagination

Delete `page-eids-from-sorted` or move it to a test-only helper if needed for comparing expected results.

No production paginated list path should call:

- `sort` over all result eids
- `take-last` over all candidates
- `drop-while` over a full result set
- `count` over page candidates to infer a sentinel

TDD requirements:

- [ ] Add a test or lint-like assertion that `lookup-resources` recursive planning cannot return a full sorted collection plan.
- [ ] Add a static regression test that scans `src/eacl/datomic/impl/indexed.clj` and fails if `page-eids-from-sorted` appears outside deleted history or test fixtures.
- [ ] Add a recursive lookup test that fails on current code because it would use the old fallback.

### 4. Keep Acyclic Traversal Indexed and Lazy

The acyclic path should remain the default. Do not replace it with materialized grants.

Keep:

- `subject->resources`
- `resource->subjects`
- `traverse-permission-path-via-subject`
- `traverse-permission-path-reverse`
- `lazy-merged-lookup`
- `lazy-fold2-merge-dedupe-sorted-by`
- `lazy-fold2-merge-dedupe-sorted-by-desc`

Refactor them to depend on a small source abstraction:

```clojure
(defn relationship-value-source
  [{:keys [db index entity attr tuple-prefix direction bound]}]
  ...)
```

The source abstraction must:

- call `d/seek-datoms` for `:asc`
- call `d/rseek-datoms` for `:desc`
- drop only the exact bound datom or bound eid
- stop at tuple prefix mismatch
- expose optional instrumentation counters in tests

TDD requirements:

- [ ] Existing acyclic lookup tests remain green.
- [ ] Add forward and reverse tests where the cursor is near the end of a large ordered relation; source counters must remain bounded.
- [ ] Add duplicate-path tests proving sentinel calculation happens after merge and dedupe.
- [ ] Add tests proving reverse pages return canonical at-rest order, not descending scan order.

### 5. Add Materialized Effective Grant Indexes for Recursive SCCs

To make recursive list pagination factually cursor-efficient, recursive permission SCCs need an ordered read index. Relationship traversal alone cannot guarantee page-sized work for arbitrary positive recursive permissions, because discovering the next result after a cursor can require exploring resources before the cursor.

Add v8 grant tuple attributes that mirror relationship tuple indexing:

```clojure
:eacl.v8.grant/subject-type+permission+resource-type+resource
;; entity = subject eid
;; value  = [subject-type permission-name resource-type resource-eid]
```

```clojure
:eacl.v8.grant/resource-type+permission+subject-type+subject
;; entity = resource eid
;; value  = [resource-type permission-name subject-type subject-eid]
```

These datoms are effective permission grants, not raw relationships.

Design constraints:

- Build grants only for permission nodes that belong to recursive SCCs.
- Preserve acyclic direct traversal for non-recursive permissions.
- Store one effective grant per subject, permission, resource tuple.
- Store the reverse grant tuple in the same transaction.
- Query recursive `lookup-resources` from the forward grant attr.
- Query recursive `lookup-subjects` from the reverse grant attr if recursive subject enumeration requires the same guarantee.
- Query recursive `can?` by direct datom lookup or keep the existing evaluator with tests proving consistency.

Initial maintenance strategy:

- On `write-schema!`, identify recursive SCCs and rebuild all recursive grants.
- On `write-relationships!`, after applying relationship mutations, rebuild recursive grants synchronously.
- Because the project is in development and no migration path is required, prefer correctness and read-side confidence over incremental maintenance complexity.
- Add a later optimization plan for incremental invalidation if write costs become unacceptable.

TDD requirements:

- [ ] Add schema tests for the new grant attributes.
- [ ] Add compiler tests identifying recursive SCC permission nodes.
- [ ] Add tests for `reader + parent->read` producing effective grants for root, child, and grandchild.
- [ ] Add tests for data cycles proving grant rebuild terminates.
- [ ] Add tests for relationship deletion proving stale effective grants are removed.
- [ ] Add tests for schema changes proving stale grants for removed recursive permissions are removed.
- [ ] Add tests proving duplicate paths produce one effective grant.
- [ ] Add recursive `lookup-resources` forward and reverse pagination tests that read from grant attrs.
- [ ] Add recursive `lookup-subjects` pagination tests if the selected query is recursive.
- [ ] Add consistency tests comparing recursive grant results with the existing fixed-point evaluator on small graphs.

### 6. Fail Loudly Until Recursive Grant Pagination Exists

During implementation, do not keep the old recursive fallback as a hidden behavior.

If the grant index is not implemented yet, recursive paginated list calls should throw a typed exception:

```clojure
(ex-info "Recursive paginated lookup requires the effective grant index."
         {:eacl/error :eacl.pagination/recursive-query-not-indexed
          :resource/type resource-type
          :permission permission})
```

This is acceptable in the development branch because no backward compatibility or migration path is required.

TDD requirements:

- [ ] Add a failing test for current behavior: recursive `lookup-resources` must not silently page from a sorted full set.
- [ ] Make it pass first by throwing the typed exception.
- [ ] Replace the exception with grant-indexed pagination only after the grant source exists.

### 7. Tighten `read-relationships` Prefix Planning

Current `read-relationships` scans are ordered and cursor-aware, but filters can still be applied after the scan. Redesign scan planning so all filters that fit the tuple order become seek prefixes.

Forward relationship attr:

```clojure
[subject-type relation-eid resource-type resource-eid]
```

Reverse relationship attr:

```clojure
[resource-type relation-eid subject-type subject-eid]
```

Rules:

- If `:subject/id` is present, use the forward `:eavt` scan anchored on subject eid.
- If `:resource/id` is present, use the reverse `:eavt` scan anchored on resource eid.
- If both anchors are absent and `:subject/type` gives the longest prefix, use forward `:avet`.
- If both anchors are absent and `:resource/type` gives the longest prefix, use reverse `:avet`.
- Include `relation-eid` in the tuple prefix whenever `:resource/relation` is present and all earlier tuple prefix components are known.
- Include the opposite object type in the tuple prefix whenever it is known and all earlier tuple prefix components are known.
- Keep post filters only for fields that cannot be represented as a contiguous tuple prefix in the chosen scan.

TDD requirements:

- [ ] Add `read-relationships` tests with many nonmatching relation rows before the matching relation.
- [ ] Assert the selected scan plan includes relation eid when possible.
- [ ] Assert forward and reverse pages remain in at-rest order.
- [ ] Assert cursor validation rejects a token produced for a different scan plan.
- [ ] Add benchmark coverage for subject-anchored, resource-anchored, and global relationship reads.

### 8. Replace Timing-Only Confidence with Structural Counters

Timing thresholds are useful regression signals, but they are not proof. Add optional instrumentation under a dynamic var, for tests and benchmarks only:

```clojure
(def ^:dynamic *pagination-stats* nil)
```

Track:

- selected plan kind
- number of `seek-datoms` calls
- number of `rseek-datoms` calls
- number of datoms pulled from each source
- number of merged result items realized
- number of page items returned
- whether any full sorted set fallback was attempted

Do not expose this as public API.

TDD requirements:

- [ ] Add tests proving forward acyclic pages use `seek-datoms`.
- [ ] Add tests proving reverse acyclic pages use `rseek-datoms`.
- [ ] Add tests proving recursive grant pages use grant attrs, not relationship fixed-point sets.
- [ ] Add tests proving realized merged rows are bounded by a documented multiple of page size, path count, and duplicate overhead.
- [ ] Add tests proving no recursive list page records a full-set fallback.

### 9. Rebuild Benchmarks Around Branch Coverage

Update `test/eacl/bench/pagination_test.clj`.

Keep the existing acyclic multi-path benchmark, but make it explicit:

- benchmark name: `acyclic-multipath-pagination-benchmark`
- assert plan kind: `:acyclic-permission-stream`
- assert no recursive fallback
- assert forward and reverse stats stay bounded

Add a recursive benchmark:

- schema: `definition account { relation parent: account relation reader: user permission read = reader + parent->read }`
- data: a large tree, not only a chain, so fanout and dedupe are exercised
- query: root reader looking up readable accounts
- assert plan kind: `:recursive-effective-grant-stream`
- assert forward and reverse pages do not slow down with page depth
- assert source counters do not grow with page depth

Add relationship-read benchmarks:

- subject anchored
- resource anchored
- global by subject type
- global by resource type
- relation-selective data with many unrelated rows

Benchmark assertions:

- page count and non-overlap correctness
- at-rest order for every page
- branch kind correctness
- stats boundedness
- timing thresholds as secondary regression checks

TDD requirements:

- [ ] Make current benchmark fail if it accidentally benchmarks the recursive path.
- [ ] Make recursive benchmark fail on current code.
- [ ] Make relationship benchmark fail if prefix planning scans unrelated rows.
- [ ] Make all benchmark failures explain whether the issue is timing, branch selection, or scan boundedness.

### 10. Update Documentation and README Claims

Update:

- `README.md`
- `docs/index.md`
- the reverse pagination plan if it remains a living design reference

Required doc changes:

- State that supported list reads are backed by ordered Datomic indexes.
- State that reverse pages use reverse index scans internally but return canonical at-rest order.
- State the recursive behavior precisely:
  - after this plan is implemented, recursive paginated lookup uses effective grant indexes
  - before then, recursive paginated lookup is not silently cursor-claimed
- Describe write-side tradeoffs for recursive effective grants.
- Keep the concise author style in README.
- Remove any broad claim that all recursive traversal is page-sized unless the grant benchmarks prove it.

TDD requirements:

- [ ] Add README examples for forward and reverse pagination over `lookup-resources`.
- [ ] Add one short note explaining recursive grant materialization if implemented.
- [ ] Ensure docs do not mention `:cursor` or `:limit` for list pagination.

## Detailed TDD Execution Plan

### Phase 0 - Baseline Failing Tests

- [ ] Start nREPL using the project instructions if none is running.
- [ ] Require all edited namespaces with `:reload`.
- [ ] Add an internal plan explanation test for current acyclic `lookup-resources`.
- [ ] Add an internal plan explanation test for current recursive `lookup-resources`.
- [ ] Add a recursive pagination benchmark fixture large enough to expose full closure work.
- [ ] Add structural stats tests that fail because stats are not implemented.
- [ ] Add static regression test asserting no production list path may use `page-eids-from-sorted`.
- [ ] Run `eacl.datomic.impl.indexed-test` through nREPL and confirm expected failures.
- [ ] Run `eacl.bench.pagination-test` through nREPL only when explicitly validating benchmark behavior.

### Phase 1 - Page Plan and Realizer

- [ ] Introduce `normalize-page-request` tests for every legal and illegal combination.
- [ ] Add `explain-list-plan`.
- [ ] Add a central `realize-page`.
- [ ] Refactor `lookup` to use the central realizer.
- [ ] Refactor `relationship-page` to use the central realizer.
- [ ] Preserve public response shape exactly.
- [ ] Run unit tests for the realizer.
- [ ] Run existing indexed tests.

### Phase 2 - Instrumented Ordered Sources

- [ ] Wrap Datomic index access in internal helpers.
- [ ] Add dynamic stats collection.
- [ ] Update relation value sources to use wrappers.
- [ ] Update relationship datom source to use wrappers.
- [ ] Add tests proving forward calls use `seek-datoms`.
- [ ] Add tests proving reverse calls use `rseek-datoms`.
- [ ] Add tests proving only the exact boundary edge is dropped.
- [ ] Run indexed tests.

### Phase 3 - Remove Full Sorted Pagination

- [ ] Delete `page-eids-from-sorted`.
- [ ] Replace recursive paginated lookup fallback with a typed exception.
- [ ] Keep recursive `can?` and non-list correctness tests green.
- [ ] Mark recursive list tests as expecting the typed exception until grant indexes land.
- [ ] Run indexed tests.

### Phase 4 - Recursive Effective Grant Index

- [ ] Add grant tuple attrs to schema.
- [ ] Add recursive SCC detection helper if the existing detection is not sufficient.
- [ ] Add grant transaction builders.
- [ ] Add synchronous grant rebuild after schema writes.
- [ ] Add synchronous grant rebuild after relationship writes.
- [ ] Add grant source scans using `seek-datoms` and `rseek-datoms`.
- [ ] Route recursive `lookup-resources` to grant source scans.
- [ ] Route recursive `lookup-subjects` to grant source scans where applicable.
- [ ] Route recursive `can?` to direct grant checks or add consistency tests if it keeps the evaluator.
- [ ] Run recursive correctness tests.
- [ ] Run indexed tests.
- [ ] Run Spice/public API tests.

### Phase 5 - Relationship Prefix Planning

- [ ] Refactor `scan-plan` to compute the longest legal tuple prefix.
- [ ] Add relation-eid prefix support.
- [ ] Add opposite type prefix support.
- [ ] Keep cursor validation tied to scan key and anchor.
- [ ] Add relationship prefix tests.
- [ ] Run indexed tests and Spice tests.

### Phase 6 - Benchmarks

- [ ] Split acyclic benchmark from recursive benchmark.
- [ ] Add branch assertions to both.
- [ ] Add stats assertions to both.
- [ ] Add relationship read benchmark.
- [ ] Run benchmark tests through nREPL.
- [ ] Record representative output in the final implementation notes.

### Phase 7 - Documentation

- [ ] Update README with exact v7.1/vNext behavior.
- [ ] Update `docs/index.md`.
- [ ] Remove overstated claims.
- [ ] Explain recursive grant materialization only if implemented.
- [ ] Keep examples concise.

### Phase 8 - Final Validation

- [ ] Run `git diff --check`.
- [ ] Run `eacl.datomic.impl.indexed-test` through nREPL.
- [ ] Run `eacl.spice-test` through nREPL.
- [ ] Run `eacl.datomic.config-test` through nREPL if config changes.
- [ ] Run schema tests if schema changes.
- [ ] Run benchmark tests through nREPL.
- [ ] Search the source for `sort`, `take-last`, `drop-while`, and `page-eids-from-sorted` in production list paths.
- [ ] Confirm every remaining use is either unrelated to list pagination or justified in comments/tests.
- [ ] Review the prompt in Appendix A and check whether the implementation satisfies the operator's requirements.

## Acceptance Criteria

The implementation is acceptable only when all of the following are true:

- [ ] Public list APIs still use `:first/:after` and `:last/:before`.
- [ ] Public list responses still use `:page-info`.
- [ ] Acyclic `lookup-resources` and `lookup-subjects` are backed by bounded ordered Datomic sources.
- [ ] Recursive paginated lookups are backed by effective grant indexes or throw a typed exception while the grant index is absent.
- [ ] No recursive paginated lookup silently materializes, sorts, and pages a full result set.
- [ ] `read-relationships` uses the longest legal tuple prefix for the selected scan direction.
- [ ] Forward and reverse pages return canonical at-rest order.
- [ ] Reverse scans use `d/rseek-datoms` in the hot paths.
- [ ] Benchmarks include acyclic, recursive, and relationship read pagination.
- [ ] Benchmarks assert plan kind and bounded source stats, not only elapsed time.
- [ ] README performance claims match what tests and benchmarks actually prove.

## Risks and Tradeoffs

### Materialized Grants Increase Write Cost

Effective grant indexes move recursive pagination cost from read time to write/schema update time. This is the correct tradeoff if EACL wants factually cursor-efficient recursive list reads, but it must be measured.

Mitigation:

- Build grants only for recursive SCC permissions.
- Start with synchronous rebuilds for correctness.
- Add write-side benchmarks.
- Plan incremental invalidation separately after correctness and read-side confidence are proven.

### Grant Explosion Is Possible

Recursive permissions can imply many subject-permission-resource grants.

Mitigation:

- Benchmark realistic recursive trees.
- Document the storage/read tradeoff.
- Keep acyclic permissions on direct traversal.
- Add guardrails if recursive grant count exceeds a configurable threshold.

### Structural Counters Can Become Brittle

Counting exact datoms can make tests implementation-specific.

Mitigation:

- Assert broad bounds and plan kinds, not exact internal call counts.
- Keep timing thresholds as secondary checks.
- Use counters to catch full scans, not to freeze every implementation detail.

### Temporary Typed Exceptions Are Breaking

Throwing for recursive paginated lists before grant indexes land is breaking behavior.

Mitigation:

- This branch is in development and no migration path is required.
- A typed exception is preferable to silently violating the performance contract.
- Remove the exception once grant-backed recursive pagination is implemented.

## Implementation Notes

- Use `clj-nrepl-eval` for all Clojure tests.
- Always require changed namespaces with `:reload`.
- Use `clj-paren-repair` if a Clojure delimiter error appears.
- Do not run benchmark tests as part of the normal suite unless explicitly validating performance/load behavior.
- Keep edits scoped to pagination, recursive grant indexing, relationship scan planning, docs, and tests.

## Appendix A - Original Prompt

```text
Are you 100% confident in this overall strategy? If not, find all possible loopholes, suggest proper fixes and run this loop until you are factually 100% confident in the new strategy

Write a detailed plan to docs/plans/ (prefix date) to resolve the issues found, including this original prompt verbatim as an appendix to retain context. No backwards-compatibility or migration path is required, as we are in development phase. For each proposed change, examine the existing system and redesign it into the most elegant solution that would have emerged if the change had been a foundational assumption from the start. Plan should follow TDD. The last step in the plan should be to review the prompt in the appendix and check if this implementation satisfies the operator's requirements.
```

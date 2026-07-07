# Recursive Pagination Effective Grants Plan

Date: 2026-05-17

Supersedes for implementation purposes:

- [2026-05-17 reverse pagination performance confidence plan](./2026-05-17-reverse-pagination-performance-confidence-plan.md)

Addresses report:

- [2026-05-17 recursive pagination effective grants plan critique](../reports/2026-05-17-recursive-pagination-effective-grants-plan-critique.md)

Builds on:

- [2026-05-15 reverse pagination plan](./2026-05-15-reverse-pagination-before-after-plan.md)
- [2026-05-15 reverse pagination critique](../reports/2026-05-15-reverse-pagination-plan-critique.md)
- [2026-03-28 recursive arrow cycle fix plan v2](./2026-03-28-eacl-recursive-arrow-cycle-fix-plan-v2.md)
- [2026-03-28 recursive arrow cycle critique](../reports/2026-03-28-eacl-recursive-arrow-cycle-fix-plan-critique.md)

## Goal

Resolve the pagination performance loopholes found after implementing EACL v7.1 reverse pagination.

The public API design remains right:

- forward pagination: `:first` with optional `:after`
- reverse pagination: `:last` with optional `:before`
- response metadata: `:page-info`
- results returned in canonical Datomic at-rest order

The implementation is not yet strong enough to claim that all supported paginated list reads are cursor-bounded. Recursive `lookup-resources` still pages from a fully materialized sorted set through `page-eids-from-sorted`.

This plan removes that loophole by making every supported paginated list query compile to an ordered seekable source. Permissions in the recursive dependency closure will use an effective-grant index. No paginated list query may silently fall back to full materialization plus sorting.

No backwards compatibility or migration path is required. The branch is still in development.

## Report Findings To Resolve

The relevant validation report was produced by running the current implementation through nREPL after the reverse pagination commit.

Validated green tests:

- `eacl.datomic.impl.indexed-test`: 21 tests, 223 assertions, 0 failures
- `eacl.spice-test`: 3 tests, 78 assertions, 0 failures
- `eacl.bench.pagination-test`: 1 test, 3213 assertions, 0 failures

Validated benchmark behavior:

- Current benchmark query is acyclic.
- It does not call `page-eids-from-sorted`.
- It proves the acyclic multi-path indexed path is fast.
- It does not prove recursive pagination performance.

Validated recursive loophole:

- The recursive fixture is classified as recursive.
- Recursive `lookup-resources` calls `page-eids-from-sorted`.
- A 500-node recursive chain sorted all 500 results for every page:
  - first page
  - forward after 100
  - forward after 400
  - reverse before 400
- A scale probe showed result-set-size work:
  - 100 recursive results: about 13.56 ms, sorted 100
  - 1000 recursive results: about 794.35 ms, sorted 1000

Conclusion:

The current acyclic reverse pagination path is healthy. Recursive paginated lookup is not cursor-bounded and must be redesigned.

## Current System Examined

### Public Pagination Layer

Files:

- `src/eacl/core.clj`
- `src/eacl/datomic/core.clj`
- `README.md`
- `docs/index.md`

Current behavior:

- `normalize-page-request` rejects legacy `:cursor` and `:limit`.
- `:first/:after` and `:last/:before` are normalized to direction, page size, and bound.
- Public page tokens are encrypted and authenticated.
- Tokens include operation, query shape, order, basis, basis-t, expiry, and edge.
- Follow-up pages use `d/as-of` to hold a stable Datomic basis.
- Public callers receive `:page-info` with encrypted `:start-cursor` and `:end-cursor`.

Assessment:

This layer should remain. It is the right boundary for real-time UIs because a UI can move forward using the current page's `:end-cursor` and move backward using the current page's `:start-cursor`.

Required change:

- Add tests proving decoded public bounds drive ordered source selection below this layer.

### Acyclic Indexed Lookup

Files:

- `src/eacl/datomic/impl/indexed.clj`
- `src/eacl/lazy_merge_sort.clj`
- `test/eacl/bench/pagination_test.clj`

Current behavior:

- Non-recursive `lookup-resources` calls `lookup`.
- `lookup` calls `lazy-merged-lookup`.
- Each permission path returns a sorted lazy eid sequence.
- Forward scans use `d/seek-datoms`.
- Reverse scans use `d/rseek-datoms`.
- Multiple path sequences are lazily merged and deduplicated.
- The page layer realizes `page-size + 1`.
- Reverse scan results are reversed only after taking the page window, so public order remains canonical.

Assessment:

This is the correct foundational shape for acyclic permissions. Preserve it, but make it explicit through a plan/source abstraction and test instrumentation.

Required change:

- Make the ordered source contract visible enough for tests to prove branch selection, bounded realization, and seek direction.

### Recursive Lookup

Files:

- `src/eacl/datomic/impl/indexed.clj`
- `test/eacl/datomic/impl/indexed_test.clj`

Current behavior:

- `lookup-resources` checks `recursive-permission-query?`.
- Recursive queries call `recursive-forward-lookup`.
- `recursive-forward-lookup` calls `recursive-resource-eids`.
- `recursive-resource-eids` computes a least fixed point over concrete resource eid sets.
- The complete result set is sorted.
- `page-eids-from-sorted` pages the sorted collection.

Assessment:

This was a reasonable correctness repair for recursive permissions, but it is not compatible with cursor pagination performance. It does page-depth work and result-set-size work.

Required change:

- Replace recursive list pagination with an ordered effective-grant source.
- Delete or isolate `page-eids-from-sorted` so production paginated list APIs cannot use it.

### Relationship Reads

Files:

- `src/eacl/datomic/impl.clj`

Current behavior:

- `read-relationships` uses Datomic datom scans.
- Forward pages use `d/seek-datoms`.
- Reverse pages use `d/rseek-datoms`.
- Results are returned in at-rest order.
- Some filters are still applied after scanning.

Assessment:

The earlier sort issue is fixed. There is still a planner improvement: every filter that can form a contiguous tuple prefix should be pushed into the Datomic seek.

Required change:

- Tighten scan planning and add prefix selectivity tests.

## Foundational Redesign

If reverse pagination and recursive permissions had both been first principles, the design would have had these layers from the start:

1. A public cursor contract.
2. An internal query planner that selects an ordered source.
3. A page realizer that consumes only `page-size + 1`.
4. A materialized effective-grant index for the recursive dependency closure, because those permission roots cannot be proven cursor-bounded from raw relationships alone.

This plan reshapes the current implementation toward that architecture.

## Core Invariants

- Every supported paginated list query must compile to an ordered source.
- Ordered sources must accept the cursor bound before producing rows.
- Forward source scans must start immediately after `:after`.
- Reverse source scans must start immediately before `:before`.
- Reverse source scan order may be descending internally, but returned page data must be canonical at-rest order.
- Page realization must consume at most `page-size + 1` merged output items.
- No supported paginated list query may materialize and sort its full result set.
- Paginated lookup for every permission in the recursive dependency closure must use an effective-grant index.
- If a recursive paginated lookup cannot use that index, it must throw a typed exception rather than silently degrade.
- Benchmarks must assert plan kind and bounded source stats, not only elapsed time.

## Design Decision: Effective Grants For The Recursive Dependency Closure

Recursive permissions need an ordered read model.

For acyclic permissions, EACL can lazily traverse raw relationship indexes from the subject or resource anchor. For recursive permissions, especially patterns such as:

```text
permission read = reader + parent->read
```

the next result after a cursor may depend on a fixed point over graph edges. Without a materialized read index, the engine may need to discover a large prefix of the graph before knowing the next page.

Therefore permissions in the recursive dependency closure should maintain effective grants:

```text
subject S has permission P on resource R
```

Those grants are persisted as Datomic tuple indexes so pagination can use the same `seek-datoms` and `rseek-datoms` strategy as raw relationships.

### Recursive Dependency Closure

Do not materialize only recursive SCC members. That is too narrow for permissions that are themselves acyclic but depend on a recursive permission through `self` or arrow paths.

Definitions:

```clojure
recursive-sccs      := SCCs with size > 1 or a self-edge
grant-indexed-nodes := every permission node that can reach any recursive-scc node
raw-indexed-nodes   := every permission node not in grant-indexed-nodes
```

Planner rule:

- if the root permission node is in `grant-indexed-nodes`, use a direct grant scan
- otherwise use the existing raw acyclic traversal

Grant builder rule:

- the builder may evaluate raw acyclic dependency nodes as input sources while rebuilding grant-indexed nodes
- the persisted grant index must include every `grant-indexed-node`, not only SCC members

This avoids public list queries that compose raw traversal with grant traversal and are difficult to prove cursor-bounded.

### New Schema Attributes

Add internal grant tuple attrs. The public feature remains EACL v7.1 on this development branch, but the schema must no longer claim "no schema changes." Use v7.1-aligned internal names unless the final docs decide to rename the whole feature to EACL v8.

```clojure
:eacl.v7.grant/subject-type+permission+resource-type+resource
;; entity = subject eid
;; value  = [subject-type permission-name resource-type resource-eid]
```

```clojure
:eacl.v7.grant/resource-type+permission+subject-type+subject
;; entity = resource eid
;; value  = [resource-type permission-name subject-type subject-eid]
```

Properties:

- both attributes are cardinality many
- both attributes are tuple values
- both attributes are indexed
- forward attr supports `lookup-resources`
- reverse attr supports `lookup-subjects`
- `can?` may use direct grant lookup for recursive SCCs

### Grant Scope

Do not materialize grants for all permissions.

Materialize grants for the recursive dependency closure. Purely acyclic permissions stay on the raw relationship traversal engine because it is already efficient and avoids storage amplification.

### Grant Maintenance

Because this branch is in development, choose correctness over incremental complexity:

- On schema writes, rebuild all recursive effective grants.
- On relationship writes, rebuild all recursive effective grants.
- Return the basis after the final grant rebuild transaction.

This is intentionally simple. It is a correctness checkpoint, not a permanent performance claim for writes. Add write-side benchmarks and guardrails before declaring the write path production-ready.

### Formal Grant Semantics

The grant builder implements positive ReBAC semantics over permission nodes `[resource-type permission-name]`.

Grant shape:

```clojure
{:subject-type subject-type
 :subject-eid subject-eid
 :permission permission-name
 :resource-type resource-type
 :resource-eid resource-eid}
```

Relation arm:

```text
permission P = relation R
relationship S --R--> X
=> grant(S, P, X)
```

Self-permission arm:

```text
permission P = permission Q
grant(S, Q, X)
=> grant(S, P, X)
```

Arrow-to-relation arm:

```text
permission P on X = via->relation R on intermediate I
relationship I --via--> X
relationship S --R--> I
=> grant(S, P, X)
```

Arrow-to-permission arm:

```text
permission P on X = via->permission Q on intermediate I
relationship I --via--> X
grant(S, Q, I)
=> grant(S, P, X)
```

Union dedupes grants by subject type, subject eid, permission, resource type, and resource eid. Recursive closure nodes iterate to a least fixed point. Non-productive cycles terminate at the empty fixed point unless direct relation or raw acyclic dependency facts seed them.

### Stale/Missing Grant Detection

Grant-backed recursive reads require a db basis after grant maintenance has run. Do not silently return false or empty pages if a grant-indexed permission has no maintenance marker.

Persist a small marker datom, for example:

```clojure
{:eacl/id "effective-grants"
 :eacl.grant/indexed-nodes [...]
 :eacl.grant/rebuilt-at-basis basis-t}
```

Grant-backed reads should validate that the marker exists whenever `grant-indexed-nodes` is non-empty. Low-level tests that bypass public write paths must explicitly call grant maintenance.

### Grant Retraction

Grants are cardinality-many tuple datoms, not standalone entities. Full rebuild retraction must scan both grant attrs and emit:

```clojure
[:db/retract e attr tuple]
```

Rebuilds must be idempotent and must not accumulate duplicate datoms.

## Proposed Changes

### 1. Add List Plan Explanation

Add an internal planning function, likely in `src/eacl/datomic/impl/indexed.clj` or a small new namespace:

```clojure
(defn explain-list-plan [db op query] ...)
```

Plan examples:

```clojure
{:op :lookup-resources
 :kind :acyclic-permission-stream
 :order [:eid :asc]
 :direction :asc
 :recursive? false}
```

```clojure
{:op :lookup-resources
 :kind :recursive-effective-grant-stream
 :order [:eid :asc]
 :direction :desc
 :recursive? true}
```

```clojure
{:op :read-relationships
 :kind :relationship-datom-stream
 :order [:relationship-datom :asc]
 :direction :asc
 :scan-key :subject-forward}
```

The plan function exists to make tests exact. It should not be part of the public EACL API.

TDD:

- [ ] Acyclic `lookup-resources` explains as `:acyclic-permission-stream`.
- [ ] Acyclic `lookup-subjects` explains as `:acyclic-permission-stream`.
- [ ] Grant-indexed `lookup-resources` explains as `:recursive-effective-grant-stream` once grant attrs exist.
- [ ] `read-relationships` explains as `:relationship-datom-stream`.
- [ ] No plan kind named `:full-sorted-set` or similar is permitted for a public paginated list call.

### 2. Add Pagination Stats Instrumentation

Add a dynamic var:

```clojure
(def ^:dynamic *pagination-stats* nil)
```

When bound to an atom, source helpers record broad counters:

- plan kind
- seek calls
- reverse seek calls
- raw datoms touched
- source rows emitted
- merged rows realized
- page rows returned
- full sorted fallback attempts

Do not expose this in public API. It is for tests and benchmarks.

TDD:

- [ ] Forward acyclic lookup records at least one `seek-datoms` call.
- [ ] Reverse acyclic lookup records at least one `rseek-datoms` call.
- [ ] Recursive lookup records grant source scans, not relationship fixed-point pagination.
- [ ] Stats fail if any public list page attempts full sorted fallback.

### 3. Centralize Page Realization

Replace ad hoc page slicing with one helper:

```clojure
(defn realize-page
  [{:keys [direction size bound]} scan-order-items]
  ...)
```

Rules:

- The source sequence is already exclusive of the cursor boundary.
- Ascending source order is canonical order.
- Descending source order is reverse scan order.
- Reverse pages are reversed after taking `size` items.
- Sentinel is always `size + 1`.
- `:has-next-page?` and `:has-previous-page?` are calculated in one place.

TDD:

- [ ] Pure unit test: ascending unbounded page returns first `n`.
- [ ] Pure unit test: ascending after-bound page sets `:has-previous-page?`.
- [ ] Pure unit test: descending page input `[9 8 7 6]` with size `3` returns `[7 8 9]`.
- [ ] Pure unit test: descending before-bound page sets `:has-next-page?`.
- [ ] Pure unit test: only `size + 1` items are realized.

### 4. Keep Acyclic Traversal, But Make Its Source Contract Explicit

Refactor direct relationship value scans behind a source helper:

```clojure
(defn relationship-value-source
  [{:keys [db entity-eid attr-eid tuple-prefix direction bound-eid]}]
  ...)
```

The helper must:

- use `d/seek-datoms` for `:asc`
- use `d/rseek-datoms` for `:desc`
- apply the bound at seek time
- drop only the exact boundary edge
- stop on tuple prefix mismatch
- report stats when instrumentation is bound

Preserve:

- `subject->resources`
- `resource->subjects`
- lazy merge dedupe
- reverse result normalization

TDD:

- [ ] Existing acyclic lookup tests stay green.
- [ ] Existing reverse pagination tests stay green.
- [ ] A cursor near the end of a large relation does not increase source rows linearly with cursor depth.
- [ ] Duplicate paths still dedupe before sentinel decisions.

### 5. Remove `page-eids-from-sorted` From Production Pagination

Delete `page-eids-from-sorted`, or move it into a test-only comparison helper.

Production list paths must not use:

- `sort` over all result eids
- `take-last` over all candidates
- `count` over candidate collections to infer sentinel
- `drop-while` over a fully materialized result set

Temporary implementation step:

- Before grant sources are complete, recursive paginated list calls should throw a typed exception.

Exception:

```clojure
(ex-info "Recursive paginated lookup requires the effective grant index."
         {:eacl/error :eacl.pagination/recursive-query-not-indexed
          :operation :lookup-resources
          :resource/type resource-type
          :permission permission})
```

TDD:

- [ ] Add a failing test proving current recursive pagination calls the sorted fallback.
- [ ] Change implementation so that test passes by rejecting recursive paginated lookup with the typed exception.
- [ ] Add a static regression test that searches production source for `page-eids-from-sorted`.
- [ ] Later replace the exception with grant-backed pagination.

### 6. Implement Recursive Effective Grant Build

Add recursive grant build functions in a dedicated namespace, for example:

```clojure
eacl.datomic.impl.grants
```

Core functions:

```clojure
(defn recursive-permission-nodes [db] ...)
(defn build-effective-grants [db] ...)
(defn grant-tx-data [db grants] ...)
(defn rebuild-effective-grants! [conn] ...)
```

Grant value:

```clojure
{:subject-type :user
 :subject-eid 17592186045418
 :permission :read
 :resource-type :account
 :resource-eid 17592186045425}
```

Build algorithm:

1. Read permission graph nodes.
2. Compute SCCs by `[resource-type permission-name]`.
3. Select recursive SCC nodes.
4. Initialize grant sets from direct relation arms.
5. Iteratively evaluate relation, self-permission, arrow-to-relation, and arrow-to-permission arms until grant sets stop changing.
6. Dedupe grants structurally.
7. Generate forward and reverse grant datoms.

Rebuild transaction strategy:

1. Retract all existing grant datoms.
2. Add rebuilt grant datoms.
3. Return final basis.

TDD:

- [ ] Schema test: grant attrs exist and are indexed.
- [ ] SCC test: `read = reader + parent->read` marks `[:account :read]` recursive.
- [ ] SCC test: the current acyclic benchmark schema marks `[:server :view]` non-recursive.
- [ ] Grant build test: root reader grants root, child, grandchild.
- [ ] Grant build test: duplicate paths produce one grant.
- [ ] Grant build test: data cycle terminates.
- [ ] Grant build test: deleting parent edge removes stale descendant grant after rebuild.
- [ ] Grant build test: deleting reader edge removes all grants after rebuild.
- [ ] Schema rewrite test: removed recursive permission retracts stale grants.
- [ ] Mixed dependency test: an acyclic root permission depending on a recursive permission is included in the grant-indexed closure.
- [ ] Semantic oracle test: small generated graphs compare grant membership with the existing evaluator.

### 7. Query Recursive Grants With Seekable Sources

Add grant source helpers:

```clojure
(defn subject->grant-resources
  [db subject-type subject-eid permission resource-type page-opts])
```

```clojure
(defn resource->grant-subjects
  [db resource-type resource-eid permission subject-type page-opts])
```

Forward `lookup-resources`:

- entity: subject eid
- attr: forward grant attr
- tuple prefix: `[subject-type permission resource-type]`
- bound: resource eid
- direction: `:asc` or `:desc`

Reverse `lookup-subjects`:

- entity: resource eid
- attr: reverse grant attr
- tuple prefix: `[resource-type permission subject-type]`
- bound: subject eid
- direction: `:asc` or `:desc`

Recursive and grant-indexed `can?`:

- Option A: use direct grant datom lookup for recursive permission nodes.
- Option B: keep existing evaluator and add consistency tests against grants.

Preferred:

- Use grant direct lookup for recursive nodes so all recursive read paths share the same semantics.

TDD:

- [ ] Recursive `lookup-resources :first` reads grant source and returns root, child, grandchild in at-rest order.
- [ ] Recursive `lookup-resources :first/:after` advances with only current `:end-cursor`.
- [ ] Recursive `lookup-resources :last` returns the final window in at-rest order.
- [ ] Recursive `lookup-resources :last/:before` goes backward with only current `:start-cursor`.
- [ ] Recursive `lookup-subjects` works through reverse grant attr where applicable.
- [ ] Recursive `can?` agrees with grant membership.
- [ ] Recursive `count-resources` and `count-subjects` count grant datoms instead of recomputing fixed points.
- [ ] Public encrypted tokens work across recursive grant pages.

### 8. Integrate Grant Rebuild With Writes

Update write paths:

- `write-schema!`
- `write-relationships!`
- helper paths used by `create-relationships!` and `delete-relationships!`

Rules:

- After schema tx, rebuild recursive grants from the new db.
- After relationship tx, rebuild recursive grants from the new db.
- Return the basis after the final grant rebuild tx.
- If no recursive SCCs exist, retract existing grants and skip rebuild additions.
- Place rebuild orchestration in a dependency-light maintenance layer so `schema`, `core`, `impl`, and grant helpers do not form namespace cycles.
- Low-level tests that use raw `d/transact` must either call maintenance explicitly or assert that grant-backed reads reject stale/missing grants.

TDD:

- [ ] `write-schema!` on recursive schema creates grant attrs and grant datoms.
- [ ] `write-relationships! :create` creates required effective grants.
- [ ] `write-relationships! :delete` retracts stale effective grants.
- [ ] Returned `:zed/token` points at a db basis where grant reads are current.
- [ ] Non-recursive write path remains correct and does not create grants.
- [ ] Repeated rebuilds are idempotent.
- [ ] Missing grant marker causes a typed stale-grant exception for grant-indexed reads.

### 9. Tighten `read-relationships` Scan Planning

Redesign `scan-plan` around longest contiguous tuple prefix.

Forward relationship tuple:

```clojure
[subject-type relation-eid resource-type resource-eid]
```

Reverse relationship tuple:

```clojure
[resource-type relation-eid subject-type subject-eid]
```

Planner rules:

- Use subject-anchored forward `:eavt` when `:subject/id` is present.
- Use resource-anchored reverse `:eavt` when `:resource/id` is present.
- Use global forward `:avet` when subject type creates the best prefix.
- Use global reverse `:avet` when resource type creates the best prefix.
- Include `relation-eid` in the prefix when it is available and all preceding tuple fields are known.
- Include opposite object type when it is available and all preceding tuple fields are known.
- Post-filter only fields that cannot be represented by the chosen contiguous prefix.
- If a relation-name filter maps to multiple relation eids, either run multiple ordered scans and merge them or choose the broader scan deliberately. Test both cases.
- Relationship order is the ascending order of the selected Datomic relationship index for that query shape.

TDD:

- [ ] Subject-anchored scan with relation filter includes relation eid in prefix when subject type is known.
- [ ] Resource-anchored scan with relation filter includes relation eid in prefix when resource type is known.
- [ ] Global scan chooses the longest available prefix.
- [ ] Cursor from one scan plan is rejected when reused with a different scan plan.
- [ ] Many unrelated relationships before the matching relation do not cause linear page work.
- [ ] Multi-relation-name queries either merge several prefix scans correctly or explicitly choose a broader scan.

### 10. Strengthen Benchmarks

Split benchmark coverage by plan kind.

Benchmarks:

1. `acyclic-multipath-pagination-benchmark`
   - current 15k server fixture
   - assert plan kind `:acyclic-permission-stream`
   - assert `page-eids-from-sorted` not used
   - assert forward and reverse stats bounded

2. `recursive-effective-grant-pagination-benchmark`
   - large recursive account tree
   - root reader can read descendants through `parent->read`
   - assert plan kind `:recursive-effective-grant-stream`
   - assert no full sorted fallback
   - assert forward and reverse page source rows do not grow with page depth

3. `recursive-effective-grant-write-benchmark`
   - measure rebuild tx size and rebuild latency for 100, 1k, and 10k effective grants
   - record relationship write latency with and without recursive grants

4. `relationship-read-pagination-benchmark`
   - subject anchored
   - resource anchored
   - global subject type
   - global resource type
   - relation-selective data

Benchmark pass criteria:

- correct page size
- no overlap across pages
- stable at-rest order
- branch kind is expected
- source stats bounded
- elapsed-time thresholds are secondary

TDD:

- [ ] Current acyclic benchmark fails if it accidentally uses recursive branch.
- [ ] Recursive benchmark fails on current implementation before grant source exists.
- [ ] Recursive benchmark passes after grant source exists.
- [ ] Relationship benchmark fails if prefix planning scans unrelated rows.
- [ ] Write benchmark records the full-rebuild cost and enforces a documented development threshold.

### 11. Update Documentation

Update:

- `README.md`
- `docs/index.md`
- reverse pagination docs if they remain canonical

Required text changes:

- Keep `:first/:after` and `:last/:before`.
- Explain that reverse scans use Datomic reverse index iteration internally.
- Explain that returned order is always canonical at-rest order.
- Explain that recursive permission pagination is backed by effective grants.
- Explain the write-side/storage tradeoff of recursive grants concisely.
- Remove any broad claim not proven by tests and benchmarks.

TDD:

- [ ] README has one forward pagination example.
- [ ] README has one reverse pagination example.
- [ ] README mentions effective grants only as an internal implementation detail.
- [ ] README does not mention `:cursor` or `:limit` as list pagination keys.

## Detailed Execution Plan

### Phase 0 - Add Failing Tests

- [ ] Start nREPL using `clj-nrepl-eval --discover-ports`, then `clojure -M:dev:nrepl` if needed.
- [ ] Add plan explanation tests.
- [ ] Add stats instrumentation tests as pending failures.
- [ ] Add recursive pagination test proving current fallback is unacceptable.
- [ ] Add recursive benchmark that fails on current implementation.
- [ ] Add static source regression around `page-eids-from-sorted`.
- [ ] Run `eacl.datomic.impl.indexed-test` through nREPL and confirm the new failures are targeted.

### Phase 1 - Plan and Page Infrastructure

- [ ] Add `explain-list-plan`.
- [ ] Add `*pagination-stats*`.
- [ ] Add `realize-page`.
- [ ] Refactor acyclic lookup to use `realize-page`.
- [ ] Refactor relationship page realization to use `realize-page`.
- [ ] Keep public response shape unchanged.
- [ ] Run indexed tests.

### Phase 2 - Eliminate Hidden Recursive Fallback

- [ ] Remove production use of `page-eids-from-sorted`.
- [ ] Make recursive paginated lookup throw the typed exception while grant index is absent.
- [ ] Keep recursive `can?` and current non-list recursive tests green.
- [ ] Run indexed tests.

### Phase 3 - Add Grant Schema

- [ ] Add forward grant attr.
- [ ] Add reverse grant attr.
- [ ] Update schema version naming consistently.
- [ ] Add schema tests.
- [ ] Run schema tests.

### Phase 4 - Build Effective Grants

- [ ] Implement recursive SCC detection tests.
- [ ] Implement recursive dependency closure tests.
- [ ] Decide and apply v7.1/v8 schema naming consistently.
- [ ] Implement grant builder over current permission semantics.
- [ ] Implement full grant rebuild tx data.
- [ ] Add deletion/staleness tests.
- [ ] Add data-cycle termination tests.
- [ ] Run grant-focused tests.

### Phase 5 - Query Effective Grants

- [ ] Implement subject-to-grant-resource source.
- [ ] Implement resource-to-grant-subject source.
- [ ] Route recursive `lookup-resources` to forward grant source.
- [ ] Route recursive `lookup-subjects` to reverse grant source where needed.
- [ ] Route recursive `can?` to grant direct lookup or prove consistency.
- [ ] Route recursive counts to grant indexes.
- [ ] Add stale/missing grant marker validation.
- [ ] Run indexed tests.
- [ ] Run Spice tests.

### Phase 6 - Integrate Grant Rebuild With Writes

- [ ] Rebuild grants after schema writes.
- [ ] Rebuild grants after relationship writes.
- [ ] Return the final post-grant basis.
- [ ] Add public client tests around create/delete relationship flows.
- [ ] Run Spice tests.

### Phase 7 - Tighten Relationship Prefix Scans

- [ ] Refactor `scan-plan`.
- [ ] Add longest-prefix tests.
- [ ] Add cursor scan-key validation tests.
- [ ] Add relationship read benchmark.
- [ ] Run indexed and Spice tests.

### Phase 8 - Benchmarks and Performance Proof

- [ ] Split benchmark names by plan kind.
- [ ] Add recursive effective-grant benchmark.
- [ ] Add recursive effective-grant write benchmark.
- [ ] Add source stats assertions.
- [ ] Run benchmark namespace through nREPL.
- [ ] Record representative benchmark output.

### Phase 9 - Docs

- [ ] Update README.
- [ ] Update `docs/index.md`.
- [ ] Update or link reverse pagination plan docs.
- [ ] Ensure docs match measured behavior.

### Phase 10 - Final Verification

- [ ] Run `git diff --check`.
- [ ] Run `eacl.datomic.impl.indexed-test`.
- [ ] Run `eacl.spice-test`.
- [ ] Run schema/config tests if touched.
- [ ] Run `eacl.bench.pagination-test`.
- [ ] Search production code for `page-eids-from-sorted`, full-list `sort`, `take-last`, and pagination-time `count` in list paths.
- [ ] Confirm any remaining use is outside production pagination.
- [ ] Review the prompt in Appendix A and check whether the implementation satisfies the operator's requirements.

## Acceptance Criteria

- [ ] Acyclic list reads remain fast and cursor-bounded.
- [ ] Recursive list reads use effective grant indexes.
- [ ] Any root permission in the recursive dependency closure uses effective grant indexes.
- [ ] No public paginated list read uses full result materialization plus sorting.
- [ ] Forward and reverse pages return canonical at-rest order.
- [ ] Public page tokens work for acyclic, recursive, and relationship pages.
- [ ] `read-relationships` uses the longest legal tuple prefix.
- [ ] Benchmarks prove branch coverage and bounded source work.
- [ ] Write-side benchmark output documents the cost of synchronous grant rebuilds.
- [ ] README claims match the implementation and benchmark proof.

## Risks

### Grant Rebuild Cost

Full grant rebuilds may be expensive on write-heavy workloads.

Mitigation:

- Scope grants to recursive SCCs only.
- Keep acyclic permissions on direct traversal.
- Measure write-side cost.
- Plan incremental invalidation later if needed.

### Grant Explosion

Recursive permissions can produce many effective grants.

Mitigation:

- Deduplicate structurally.
- Benchmark large trees and cyclic data.
- Add configurable guardrails after measuring real workloads.

### Schema Version Friction

Adding grant attrs means the earlier "no schema changes" assumption no longer holds.

Mitigation:

- This branch is development-only and no migration path is required.
- The stronger performance requirement is impossible to prove for recursive pagination without either a materialized ordered index or rejecting recursive pagination.
- Prefer the schema change because the operator's goal is easy and performant forward/reverse pagination.

### Semantic Drift Between Evaluator and Grants

The grant builder could diverge from `can?`.

Mitigation:

- Add consistency tests comparing grants with the existing evaluator on small generated graphs.
- Prefer using grants for recursive `can?` after the index is available.

## Appendix A - Original Prompt

```text
Write a detailed plan to docs/plans/ (prefix date) to resolve the issues outlined in the report, including this original prompt verbatim as an appendix to retain context. No backwards-compatibility or migration path is required, as we are in development phase. For each proposed change, examine the existing system and redesign it into the most elegant solution that would have emerged if the change had been a foundational assumption from the start. Plan should follow TDD. The last step in the plan should be to review the prompt in the appendix and check if this implementation satisfies the operator's requirements.
```

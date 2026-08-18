# Tasks: Exact Scan-Response Cache

Supersedes `review/original-tasks.md`. Nothing from the original list is
carried over unchanged; the design it implemented was rejected
(`review/REVIEW.md`).

## 0. Prerequisite change (separate change, blocks §7 gate) — `fix-datomic-plan-cache-thrash`

- [ ] 0.1 `eacl.datomic.impl/with-request-engine`: build the engine adapter with the client's `:source-lifecycle`/`:source-scope` (or reuse the selected adapter) so `eacl.engine.v8/stable-plan` keys stop varying per request; add a regression test asserting the plan cache holds one entry per (source, schema generation, root) across repeated requests
- [ ] 0.2 Key `stable-plan` by schema generation instead of native revision + lifecycle (the plan is a pure function of schema definitions); keep lifecycle in the key only if a caller-fixed lifecycle can span schema histories — decide and document
- [ ] 0.3 Move the per-request `eacl.datomic.schema/read-schema` performed by `spiceomic-lookup-resources`'s `prepare` (and siblings) into the schema-generation cache; add an op-count regression test (zero `read-relations`/`read-permissions` on a warm client)
- [ ] 0.4 Replace the per-request `verified-kernel/kernel?` reflective check with a cached predicate; avoid rebuilding the snapshot adapter more than once per request
- [ ] 0.5 Re-baseline `docs/benchmarks` for `can?` and `lookup-resources` miss/hit on all three backends after 0.1–0.4 (expected: Datomic miss page 2.4–6.6× faster, `can?` ~5×)

## 1. Spec and contract

- [ ] 1.1 Land the delta specs in `specs/` (new `exact-scan-response-cache`; MODIFIED `bounded-physical-execution`, `verified-subproblem-cache`, `managed-reuse-certification`, `demand-bounded-evaluation`) and sequence archiving after `adopt-stable-discovery-enumeration` (or fold the `bounded-physical-execution` deltas into it)
- [ ] 1.2 Write the short-chunk rule ("fewer than `:limit` ⇒ exhausted") into the fetch contract docs (`docs/stable-discovery-engine.md`) and the SPI docstrings of `eacl.engine.physical` and `eacl.engine.stable-reducer/fetch-values`
- [ ] 1.3 Define config: `:scan-cache {:enabled? false :max-weight (* 8 1024 1024) :max-prefix 1024}` under the client cache map; deprecate `:denotation-max-weight` (accepted + warning for one release, then `:eacl/invalid-config`)

## 2. Store and fetch layer (`eacl.engine.scan-cache`, CLJC)

- [ ] 2.1 Key: `{:scope {...} :descriptor {...}}` per design; canonical value equality; precomputed hash
- [ ] 2.2 Entry: immutable `{:prefix :exhausted? :weight :touched}`; `serve` with binary search on the exclusive bound; `deposit` with the contiguity rules (start-anchored, bound within prefix, bound = last)
- [ ] 2.3 Store: `ConcurrentHashMap`, `merge` with longer-prefix-wins, `LongAdder` metrics, racy `touched` tick, sampled recency eviction to weight budget, per-entry cap; `clear!`; `stats`
- [ ] 2.4 `caching-fetch-fn`: wraps the retrying/classified fetch-fn; hit path allocation-free apart from the reply vector; misses forward the identical command; failures deposit nothing
- [ ] 2.5 Property tests: served reply == `Chunk(values, offset, limit)` for random sequences, bounds, limits, prefix lengths, exhausted flags; extension preserves prefix-ness; concurrent merges keep a valid prefix

## 3. Engine and client integration

- [ ] 3.1 `eacl.engine.v8/stable-fetch-fn`: wrap with `caching-fetch-fn` when a scan-cache context `{:store :scope :proof-frame}` is bound; unchanged otherwise
- [ ] 3.2 Per-scan stamp: `proof-frame/subset-descriptor` on the relation id from the request's complete frame; nil ⇒ plain fetch for that scan; count `:scan-stamp-unavailable`
- [ ] 3.3 Datomic client (`eacl.datomic.core`): create the store per client lifecycle; bind the context only on managed-eligible current-snapshot compute paths (never in `resolve-exact!`, `:cache? false`, `no-cache`, historical/filtered/speculative selections); `expire-cache!` swaps it
- [ ] 3.4 Datahike/DataScript client (`eacl.client.orchestration`): same binding discipline; CLJS build compiles (no JVM-only classes on the CLJC path — provide a CLJS store shim or gate the tier to CLJ)
- [ ] 3.5 `can?`, `count-*`, `lookup-subjects` reach the same seam automatically; add tests that reverse scans are shared across subjects checking one resource

## 4. Metrics and observability

- [ ] 4.1 `cache-stats` gains `:scan-cache {:hits :misses :elided-commands :extensions :deposits :evictions :weight :entries :stamp-unavailable}` on all three clients
- [ ] 4.2 `*recursive-traversal-stats*`/`:adapter-attempts` keep counting only real transport attempts; document that `:advanced-datoms` counts served values too (limit semantics unchanged)

## 5. Tests — correctness

- [ ] 5.1 Differential: cache-on vs `:cache? false` on randomized graphs (forward/reverse pages, all page sizes, `:after`/`:before`, counts, `can?`), all three backends, asserting equal results, cursors, has-next, and command-multiset subset with equal replies
- [ ] 5.2 Concurrency: N threads mixing reads with interleaved supported writes (create/delete relationships, `delete-object!`, `write-schema!`); oracle equality at every step; no stale-stamp reuse
- [ ] 5.3 Mutation controls (each must fail the gate): short prefix served without exhaustion; values ≤ bound served; stale relation stamp reused; widened limit or moved bound on miss; fragment deposited without its start
- [ ] 5.4 Width/retention invariance test (`stable_reducer_test/physical-width-and-retention-invariance-test`) extended with the caching fetch-fn in the loop, chunk sizes {1,2,7,64}, caps {0,1}, cold and warm store
- [ ] 5.5 Limits: `:max-advanced-datoms` trips at the same transition with every scan served from cache
- [ ] 5.6 Bypass/eligibility: `:cache? false`, `no-cache`, `at-exact-snapshot`, `as-of`, filtered db, incomplete proof, relation outside closure ⇒ no lookup, no deposit
- [ ] 5.7 Regression: checkpoint, cursor, coherence, and cache-vs-bypass suites unchanged

## 6. Formal

- [ ] 6.1 New Dafny leaf `formal/stable-discovery/ScanResponseCache.dfy`: `Serve`/`Extend` over a prefix of one fixed sequence; lemmas `ServedReplyIsExactChunk`, `ExtendPreservesPrefix`, `ExhaustedMeansComplete`; register in `verify-fast.sh` and update the obligation count
- [ ] 6.2 `ScalarFrontierCoherence.dfy`: `SingletonFrontierIsRelationGeneration`; a covered-singleton reuse lemma not requiring equal full dependency vectors
- [ ] 6.3 Adapter certification: assert (all three adapters) that `subject->resources`/`resource->subjects` for relation `r` are unchanged by supported mutations of other relations and change only with `r`'s generation
- [ ] 6.4 Update `formal/verification/subproblem-cache.edn` (`:represented-entry-kinds` gains `:exact-physical-response-prefixes`), `assurance-matrix.edn`, `execution-contract.edn` (drop the deleted `completed-denotation-public-order` / `recursive-denotation-key` symbols), and `ASSURANCE_COVERAGE.md`
- [ ] 6.5 Certify: verified status for the tier only after 6.1–6.4 are green and 5.3 mutants are red

## 7. Benchmarks and gate

- [ ] 7.1 Promote `review/bench/seg_bench.clj` into `^:benchmark` tests: Fixture A (accounts), Fixture B (sparse shared groups), hot-resource `can?`; Datomic, Datahike `:file`, DataScript
- [ ] 7.2 Record elided-command ratio, p50/p95 miss-page latency cache-on vs off, cold-overhead delta, allocation per page; write results to `docs/benchmarks/results/`
- [ ] 7.3 Gate per `exact-scan-response-cache` adoption requirement; default-on only when it passes on every backend

## 8. Cleanup — remove retired cache mechanisms

- [ ] 8.1 `eacl.subproblem-cache`: drop `:denotation` from `known-tiers`, its budget/state/ceiling code, `:denotation-hits`, `:acyclic-denotation-hits`, `:recursive-component-hits`, `:managed-denotation-hits`, `:fetched-projection-values`; keep `:denotation-max-weight` as a warned no-op for one release
- [ ] 8.2 `eacl.engine.v8`: remove `direct-match-datoms-in-relationship-index` (no src caller) and the stale `:denotation-key-builds`/`:denotation-dependency-calcs` docstring keys; decide the fate of `:complete-denotation` evaluation mode (adopt-stable task 8.3) and either keep it as the explicit exhaustive-`:last` opt-in with new wording or replace it with a typed error
- [ ] 8.3 `eacl.datomic.core` docstrings and `docs/cache.md`, `docs/v8-subproblem-cache.md`, `docs/stable-discovery-engine.md`: remove "denotation"/"projection tier accepted but unused" language; document the scan-response cache and its config
- [ ] 8.4 Formal artefacts: retire `formal/dafny/SubproblemCache.dfy` `RecursiveDenotationKey` sections, `RootDenotation.dfy`/`IndexedRootDenotation.dfy` manifest pins, `formal/tla/EaclTieredSubproblemCache.tla` denotation budget, mutants `:partial-denotation-hit`/`:coupled-tier-budgets`, performance-gate entries asserting denotation hits; re-pin the manifest
- [ ] 8.5 Retire `eacl.engine.portable-indexed` (CLJS twin) by wrapping the decision kernel directly in `production_kernel_cljs.cljs`; remove `IndexedTraversalKernel` traversal methods with no callers
- [ ] 8.6 Reconcile stale specs: `managed-reuse-certification` (delta here), `nonblocking-cache-coordination` projection wording (already REMOVED by adopt-stable), `verified-subproblem-cache` shared-subgraph gate (delta here)
- [ ] 8.7 Remove `exploration/stable-discovery/source_benchmark.clj` or fix its references to deleted vars; move `CacheBoundary.dfy` from exploration-only status into the release leaf set if it is cited as justification

## 9. Documentation and rollout

- [ ] 9.1 `docs/cache.md`: new "Scan-response cache" section (unit, scope, elide-only rule, config, metrics, what it is not); update the cache-layers table
- [ ] 9.2 Release notes: prerequisite fix (Datomic plan-cache thrash), scan-response cache (default off → on after gate), deprecated `:denotation-max-weight`
- [ ] 9.3 Optional: rename the change directory to `exact-scan-response-cache`

## 10. Out of scope (explicit non-tasks)

- [ ] 10.1 Any reducer-emission, plan-node, composed, or hierarchical segment cache
- [ ] 10.2 Answer-level page-size prefix reuse
- [ ] 10.3 Cross-process or storage-layer caching changes
- [ ] 10.4 Time-travel reuse

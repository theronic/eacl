# EACL v8.0 exploration review — correctness, simplification, performance

Date: 2026-08-07
Audited tree: `codex/restore-v8-enumeration-performance` @ `8b17ee0` (8 commits ahead of `release/v8.0`; the freshest v8 state). Companion prompt: [docs/plans/2026-08-07-eacl-v8-correctness-optimization-exploration.md](../plans/2026-08-07-eacl-v8-correctness-optimization-exploration.md).

Method: six parallel deep-dive audits (cache, cursors, build/codegen, model↔implementation correspondence, backend sharing/Clojure quality, performance/sizing), with the highest-impact claims re-verified by hand against source. Confidence levels marked throughout. File:line references are into the audited v8 tree.

---

## 0. Verdicts up front

1. **The four-tier cache mental model (exact / projection / denotation / proof-descriptor) is wrong about the shipped system.** The proof-per-hit descriptor tier was measured (1.5–1.8 ms fixed floor per hit — slower than uncached evaluation on Datahike/DataScript) and replaced on 2026-08-02 by a current-generation design. Its code survives as **dead code** on the trusted path. The real structure is two orthogonal axes: *generation* (exact / managed) × *granularity* (answers / projections / denotations), plus continuations and schema plans.
2. **The one-hop⊂multi-hop unification hypothesis is half right, and the half that is right is already implemented — undocumented and untested.** The generation axis is already unified (`resolve-layered-bound!` serves both tiers). Managed cross-revision **denotation** reuse — the thing the docs say is disabled pending "a bounded implementation and proof" — is live in code (verified: `engine/v8.cljc` `resolve-forward-denotation` passes `recursive-denotation-dependencies` into the managed store), has zero differential-oracle coverage, and is **on by default on DataScript**. Merging projection *into* denotation is wrong: they store different semantic objects (bounded ordered prefix slice + resumption bound vs complete least fixed point), and the TLA invariant `PartialRecursiveDenotationNeverHits` is exactly what the separation buys.
3. **Kernel-from-proofs: keep it on the JVM, take it off the browser.** JVM consumes Dafny→Java classes directly at ~1.1–2.5 µs/result — acceptable. CLJS consumes a 591 KB foreign-lib (96 KB gzip) at ~31 µs/result (≈31 s per 1M-result enumeration), `:advanced` compilation is broken today (zero externs for 452 access sites; CI builds only `:none`), and the biggest cost is EACL's own `dafny-seq.js` Proxy shim (76% of per-element read cost), not Dafny.
4. **Cursors have the most serious correctness defect in v8**: the recursive route paginates an unstable order (worklist derivation order) by dense ordinal, and rebase — which fires on *every* write because the cursor proof digest commits to `basis-t` — silently skips and duplicates results. The fix is a simplification: keyset-order the recursive route and delete the ordinal cursor kind.
5. **Cursors and cache share one root defect: validity keyed on whole-DB identity (`basis-t`) instead of dependency scope.** The dependency-stamp mechanism that fixes the cache already exists; pointing the cursor proof digest at it fixes rebasing too. One mechanism, three consumers (answers, subproblems, cursors).
6. **Default cache sizes are calibrated to 10k–150k benchmark fixtures and are one to two orders of magnitude small at 1M entities**, with three hard cliffs (denotations >~174,600 eids can never be admitted; recursive roots >100,000 derived grants throw under cache-enabled checks; completed-answer tiers are unbounded in bytes because the weight function is accepted and ignored).
7. **Backend drift is real and behavioral.** The v7.3 nil-anchor fix landed only in Datomic — though adversarial verification (Part II, V7) corrected the failure mode: `{:subject/id nil}` returns a silent **empty page** on Datahike/DataScript (a downstream nil guard catches it), while the live hole is nil-valued **type/relation** anchors (`{:subject/type nil}`, `{:resource/type nil}`, `{:resource/relation nil}`), which return arbitrary relationships from every relation definition, bounded by page size (up to 10k rows). Datahike/DataScript are a 73–92% identical fork pair; ~4,700 lines of pure orchestration could collapse onto the existing 21-operation SPI.

The remainder details each area, then a ranked bug list, then a simplification roadmap. **Part II (§11–§14) records the adversarial verification wave that followed: every load-bearing finding was independently attacked against source (several with empirical repros on a live JVM); confirmations, corrections, and one outright refutation are logged there, plus verification of the 0tx populated-recursion report and four concrete fix designs with effort estimates. Where Part I and Part II disagree, Part II is authoritative.**

---

## 1. What v8 actually is

```
                         ┌─────────────────────────────────────────────┐
                         │  formal/dafny/*.dfy   (25 files, 41,566 ln) │
                         │  0 assume / 0 axiom / 0 verify-false        │
                         └───────────────┬─────────────────────────────┘
                 verify per-file         │ translate (root: EaclKernel.dfy)
                 9,802 obligations       ▼
              ┌───────────────┐   ┌────────────────┐
              │ Java: 710 src │   │ JS: 949,688 B  │
              └──────┬────────┘   └───────┬────────┘
                     │  bin/patch-generated-collections.mjs
                     │  splices ~1,066 lines of HANDWRITTEN collections
                     │  (clojure.lang.PersistentHash{Set,Map} / immutable.js)
                     ▼                    ▼
              target/formal/java/classes  target/formal/browser/EaclKernel.browser.js (591 KB)
                     │                            │  (foreign-lib, :optimizations :none only)
                     ▼                            ▼
        production_kernel.clj            production_kernel_js.cljs
                     └──────────┬─────────────────┘
                                ▼
                  verified_kernel.cljc  (re-validates every input/output in Clojure)
                                ▼
                  eacl.engine.v8 + subproblem_cache + relay + backends
                  (generated kernel OWNS the traversal loop; Clojure is a scan executor;
                   handwritten engine deleted from production — test oracle only)
```

- The generated kernel is the **only** packaged decision engine (`subproblem_cache.cljc:39-48` default; `verified_kernel.cljc:2494-2497` refuses non-generated kernels; commit `d96bc4c` moved `engine/indexed.cljc` to `formal/smoke/`).
- The proofs are real: no `assume`/`{:axiom}`/`{:verify false}` anywhere; the capstone `ExhaustedReverseTraversalRefinesLeastFixedPoint` (`IndexedReverseCompleteness.dfy:5724`) proves the indexed engine refines the `Semantics.dfy` least-fixed-point spec, conditional on adapter obligations (which are exactly what adapter certification tests).
- The spec-vs-product gap is small because **the product was shrunk to fit the model**: the parser grammatically accepts full SpiceDB (`&`, `-`, wildcards, `group#member`, caveats) and `validate-eacl-restrictions` rejects all of it with typed errors. EACL is a union+single-arrow fragment of SpiceDB, in both model and product (open issues #44, #10, #9).
- Trust inversion (moderate-high concern): after verification, unverified handwritten collections are patched **under** the verified code. The JS shim's `hashCode` hashes `toString(value)` — soundness rests on an unproven injectivity-up-to-Dafny-equality assumption (astronomically unlikely to bite, but unproven). The project's own `trusted-boundary.md` names all of this honestly.
- Historical yield calibration (from the 62-counterexample corpus): 31 found by manual review, 16 differential, 11 benchmark, 3 property, **1 by Dafny**. The single false-grant (EACL-FORMAL-012) and both fail-opens were introduced by the v8 formal-boundary work and caught by its own harness. Zero pre-existing false-grants were found in the v7 handwritten engine.

---

## 2. The cache, as it actually exists

### 2.1 Tier map (all per-client, in-process)

| Tier | Store | Key basis | Value | Bound | Eviction |
|---|---|---|---|---|---|
| Completed answers (`:can?` `:lookup-page` `:count`) | `CurrentGenerationCache` (`cache.cljc:117-138`) | exact: `[semantic-key kind]` on snapshot identity; managed: `+ dependency-stamp` | bool / count map / page | **1024 entries, no byte bound** (weight fn ignored: `core.clj:843` `_weight-fn`) | **arbitrary hash-order** (`bounded-assoc`, `cache.cljc:265-275`) |
| Projection (one-hop) | `SubproblemStore :projection` | op + endpoints + relation + direction + bound + chunk-size (32) | `{:values [≤32 eids] :terminal?}` / bool probes / managed proofs | 4 MiB weight | amortized LRU (commit `dfd3ad5`) |
| Denotation (multi-hop) | `SubproblemStore :denotation` | `[:permission-fixed-point v2 direction root-body-identity anchor result-kind limits]` | complete LFP eid vector | 4 MiB weight | amortized LRU |
| Continuations / recursive pages / frontier heads | `LocalStore` (Datomic) / `continuation.cljc` | scope digest incl. **snapshot-id (`basis-t`)** | opaque machine state, frontier+heads maps | 1024–2048 entries / 16–128 MiB | true LRU (Datomic) / **O(n) touch** (portable) |
| Schema plans | 8 atoms per client (`core.clj:2688`) | `[backend source-scope schema-proof]` | compiled paths, SCCs, plans | **unbounded** | none (write-schema! reset) |
| Proof-descriptor envelope (v3 authenticated) | `eacl.cache/resolve!` + `authenticated-store` | — | — | — | **DEAD CODE** — zero production callers; `:shared-cache-store`/`:lookup-cache-store` opts written, never read |

Also dead: `watermark.clj` (entire namespace unreferenced outside its test), zed-token v2 (`eacl_z2_`) constructors.

### 2.2 Invalidation, precisely

- `write-schema!` → resets schema plans + expires the whole completed/subproblem lifecycle atomically. Continuations are only key-shadowed, not cleared.
- Relationship writes call **no cache API**. Invalidation is key-derived: (a) `basis-t` advance rotates the whole **exact** generation; (b) `stamp-relation-versions` CAS-bumps `:eacl/relation-version` per touched relation in the same transaction; (c) managed keys embed the sorted `[relation-id tx mutation-id]` stamp vector, so a relevant write changes the key and an unrelated write leaves it valid.
- This honors the issue-#74 philosophy: invalidation is signaled by EACL's writers via in-database stamps — no content digests, no listeners, cross-peer correctness for free.
- **On stock Datomic/Datahike (default `:coherence-authority :unknown`) the managed tier is disabled, so every transaction of any kind flushes 100% of the cache.** Only DataScript defaults to `:managed`.

### 2.3 The unification question, settled

Composing `A→C = A→B + B→C` over cached *pages* does not typecheck: a projection entry is an ordered bounded prefix slice with a resumption bound; a denotation is a complete fixed point; slices do not compose into slices of the composition. The projection tier exists precisely to avoid materializing closures. Keep the granularity split.

What *should* unify — and largely already does — is **validity**: one dependency-stamp mechanism across answers, projections, denotations (and cursors, §3). The managed denotation path is live (verified: `resolve-forward-denotation` → `resolve-layered-bound!` with the relation-closure dependency, stamps capped at `managed-proof-max-atoms` 256) but:

- `docs/v8-subproblem-cache.md:66-71` and `docs/cache.md` still claim denotations are exact-generation-only pending proof — **stale docs on the highest-risk reuse path** (high confidence);
- no differential-oracle test exercises the managed tier at all: `cache_differential_test.clj` and `cache_model_test.clj` construct clients with default (`:unknown`) authority (high confidence);
- DataScript enables it by default.

Full Jane-Street-Incremental dependency graphs (per-edge traces, write-time fan-out) are the wrong trade here: at 1M anchors the write path inherits an O(dependents) walk — exactly the "every transaction pays a detection cost" objection from #74. Relation-granularity stamps with read-time comparison are the right point on the curve; the documented refinement, if hot-relation churn ever bites, is endpoint-local stamps — but measure first.

### 2.4 Cache bugs (see ranked list §6 for the full set)

Highlights: unbounded completed-answer bytes (verified), arbitrary eviction + `:on-repeat` admission degenerating to never-admit at scale, a plausible cross-thread deadlock in single-flight (delay monitor + fair untimed 256-permit semaphore, flight registration unbounded), silent denotation oversize rejection, `trim-tier` discarding live LRU records on failed eviction scans, metrics conflating single-flight joins with hits, and the doc/test gap around managed reuse.

### 2.5 TLA+ models

Honest and mutation-tested (8/8 spec mutants killed; each flag genuinely weakens a guard) — but the mutation controls are wired into **neither CI workflow**, and the mutation ledger is stale (96 recorded vs 103 registered). Coverage is the single-flight/lifecycle/stamp core only: weight is modeled as 0..1 per entry, LRU/blocking/liveness/completed-answer tier are unmodeled — exactly where the found bugs live.

---

## 3. Cursors

Three cursor kinds: `:lookup-eid` (keyset, internal EID ascending — sound), `:recursive-traversal` (dense ordinal into worklist derivation order — unsound under mutation), `:relationship-index`. Frontier/heads state lives server-side in continuation stores, keyed by snapshot-pinned digests.

**The defect chain (all high confidence):**

1. The recursive route's order is derivation order — a function of graph shape, not the result set. `IndexedRendering.dfy:438-452` (`SetEqualityDoesNotEstablishOrderedPageRefinement`) is an in-repo admission. `ValidRenderState` requires uniqueness, never sortedness.
2. Rebase resolves the bound EID's position in the **current** denotation and resumes at position+1 (`PageWindow.dfy:306-398`; `verified_kernel.cljc:2808-2853` chunk-scans the whole denotation). Items that moved earlier are never returned; items that moved later are returned twice. Only signal: `:cursor-recovery :rebased`, documented as benign. No theorem bounds the divergence — `PaginateRelationshipContinuation` proves only "the ordinal is where the eid is now."
3. Rebase fires on **every** write: `continuation-proof` hashes `proof-digest` which commits to `snapshot-id {:database-id :basis-t}`. The repo's own test asserts rebase after 20 *unrelated* transactions (`recursive_cache_test.clj:212`). Consequences: skip/dup becomes routine; the acyclic `:heads` cache misses 100% after any write (a user in 10k teams re-seeks 10k streams per page — the enumeration regression this branch fights); and the Datomic stale-schema check is **skipped whenever `:cursor-recovery` is set** (`core.clj:571-581`), i.e., effectively disabled on a write-active database. The portable path never checks schema generation at all.
4. Acyclic rebase needlessly restarts from page 1 when the *boundary* entity loses its grant — a keyset boundary needs no membership check (`rebase-acyclic-query`, `v8.cljc:4003-4019`).
5. The Dafny-proven `DecideContinuation` is fed literal constants in production (`:authenticated? true :scope-matches? true :expired? false :cursor-graph 0` — `relay.cljc:401-409`, `core.clj:1364-1381`): the facts are established elsewhere, but the proof guards are vacuous where they run.
6. `Pagination.dfy` models an exclusive-frontier algorithm production does not implement; the real frontier/heads machinery (`arrow-via-intermediates`, `surviving-heads`, inclusive resumption) — the trickiest code in the subsystem — is unmodeled. `OrderedMerge.dfy` ↔ `lazy_merge_sort.cljc`, by contrast, is a line-for-line faithful spec.

**Security posture:** portable tokens are HMAC-authenticated, MAC-then-parse, domain-separated, constant-time compare — good — but **not encrypted** (they leak `basis-t`, graph anchors, proof digests; Datomic's AES-GCM codec deliberately hides the same data — two threat models in one product). Default signing keys are process-local random (`defonce default-root-key`, per-`make-client` on Datomic) — cursors silently die across restarts and load-balanced nodes; no startup warning. Datomic GCM uses random 96-bit nonces with no invocation bound or rotation guidance (NIST 2³² cap; low-moderate).

**The fix is a deletion.** Give the recursive route keyset order (sort emitted batches by result EID; the denotation is already materialized) and delete the ordinal cursor kind: removes ~10 mechanisms (ordinal rebase chunk-scan + its 250 ms/1M-item license, `CursorBound`/`RenderError` machinery, the EACL-FORMAL-049 scaling-gate saga, direction-unscoped recursive page cache), fixes skip/dup structurally, and unifies both routes on `:lookup-eid`. Then point the cursor proof digest at the managed dependency stamps instead of `basis-t` — rebase collapses from "every write" to "relevant write", and the heads/continuation caches start surviving unrelated churn.

---

## 4. Build & codegen

- **JVM**: Dafny→Java classes imported directly (no GraalJS). Boundary cost ~1.1–2.5 µs/result; every scalar crosses as boxed `BigInteger` (4,892 occurrences across 193 files); scan chunks of 64 + drive fuel of 256 ⇒ ~15,625 FFI crossings per 1M-result enumeration. Warm `can?` ≈ 432 µs on a 100-object in-memory fixture (gate: 1000 µs); completed-cache hit ≈ 14 µs. Verdict: tolerable, not free.
- **CLJS**: foreign-lib `EaclKernel.browser.js` — 591,497 B minified (95,702 gzip / 78,649 brotli), 13.0% provably unreachable (TemporalSafety, WireFormat, SnapshotOracle, RootDenotation… survive esbuild), parse/eval ~9 ms desktop / ~45–90 ms mobile. Measured ~31 µs/result through the boundary ⇒ **~31 s of blocked main thread per 1M-result enumeration**; the engine's own 16,384 count window ⇒ ~0.5 s per window.
- **`:advanced` is broken and untested**: zero externs (264 property names / 452 access sites needed), zero `^js`, no `:infer-externs`, every CI CLJS build is `:optimizations :none`. A consumer's production build fails at runtime with silent `undefined` reads.
- **The dominant cost is EACL's own shim**: per element read — `dafny-seq.js` Proxy (string-index + regex + reconvert per access) 90.6 ns (76%), BigNumber 27.0 ns (23%), native 1.22 ns. In-kernel comparison: BigNumber `RoundTrip` 353 ns/item vs `{:nativeType}`-backed `RebaseCursorBound` **0.85 ns/item (415×)**. Only `PageWindow.dfy:10-14` uses `{:nativeType "number","long"}` — bounded at exactly `Number.MAX_SAFE_INTEGER`, which `verified_kernel.cljc` already validates for every eid at the boundary. The semantic precondition for widening nativeType across the traversal modules is already established and enforced.
- Gate gaps: the CLJS traversal gate has **no absolute ceiling** (only a 1.5× linearity ratio — 31 µs/result passes; so would 110); `javascript-with-runtime` has 0.87% size headroom (next Dafny addition breaks the build) while `browser-iife` sits 43% under a stale baseline; `explorer_enumeration_test` (the v7-comparison gate this branch exists for) is not wired into CI.

**Assessment of the inversion (generated-as-production):** the original plan (model → verify → certify handwritten impl) was abandoned in `545c905`/`d96bc4c`, but three facts undercut the inversion: (1) the historical bug yield came from the harness, not the artifact-ness of the proofs; (2) `verified_kernel.cljc` re-validates everything in handwritten Clojure anyway — the by-construction premium is partly paid twice; (3) the acyclic engine added by this very branch is **already** handwritten-Clojure-specified-by-Dafny with differential certification — the pattern supposedly abandoned, readmitted for performance. Recommended landing zone (moderate-high confidence): keep generated Java on the JVM; ship a differentially-certified CLJC kernel for CLJS (the switch point is one line, `subproblem_cache.cljc:39-48` `:cljs` branch; the oracle, 96-mutant rig, 62-counterexample corpus, and cross-runtime vectors already exist). If one engine everywhere is non-negotiable, then: widen `{:nativeType}`, replace the Proxy rope with explicit accessors, emit ESM + externs, add an absolute CLJS ceiling — credible path from 31 µs to low-single-digit µs/result.

---

## 5. Backends & code quality

- **Datahike/DataScript are a fork pair**: 73% of Datahike's non-blank lines identical to DataScript modulo renames (core 92%, impl 82%); Datomic is a separate implementation (~16% similarity) carrying AES-GCM tokens, ZedTokens, watermarks, extra cache tier.
- **Behavioral drift (worst five):** (1) nil-anchor guard only in Datomic — `{:subject/id nil}` full-scans elsewhere, and the DataScript validation test doesn't cover nil (critical); (2) three different known-filter-key sets — `:limit` is `:eacl.filters/unknown-filter` on two backends, a pagination error on Datomic; (3) `:coherence-authority` default differs (DataScript `:managed`), silently changing both advertised consistency capabilities and cache soundness assumptions; (4) relation content-proof records have different arity on DataScript (+`count`) vs Datahike under the same `v3` domain tag; (5) Datahike hand-rolls endpoint-tuple literals instead of the shared codec — any codec change silently desynchronizes it.
- **SPI**: `eacl.backend.v8` already defines 21 operations with per-op obligations mirroring `SnapshotOracle.dfy`; `subject->resources`/`resource->subjects` already are the "sorted tuples from index" primitive. ~4,700 lines of pure orchestration (the nine `<backend>-<op>` fns, filter validation, integrity, Datomic's private 170-line relationship-page reimplementation of `eacl.engine.relationships`) could collapse into core; ~2,100 genuinely backend-specific lines remain (index primitives at 2–7% similarity are the real difference).
- **Datahike gap is accidental**: it is a persistent store yet gets neither the LRU/admission `LocalStore` nor the external-provider protocol (Datomic-only), and has 7 test files total with no `impl_test` (Datomic: 25 files; DataScript: 15 contract tests).
- **CLJC platform risks**: `(sort ...)` on mixed-namespace keywords orders differently on JVM vs JS and feeds the `[:permission-scc … (vec (sort nodes))]` cache key (`engine/v8.cljc:2225`) — CLJS-minted keys can never match CLJ-minted ones; `max-entid` (5 definitions) is `Long/MAX_VALUE` on JVM, ~1000× outside the SPI's own `maximum-exact-integer` contract; `(sort (concat forward reverse))` can throw on mixed string/long external ids (all three backends).
- **Anti-patterns**: unbuffered `println` warn (with eager `pr-str` and a `binding` frame) inside schema resolution — one line per authorization check on a schema with a dangling relation reference; 20 dynamic vars rebound per-route on hot paths (`*decision-kernel*` threaded both dynamically and as an argument); `*warn-on-reflection*` never set (reflective `.nth` in the kernel decode loop); atoms where volatiles suffice in cache hot paths. Otherwise unusually clean: no `memoize`, no lazy-seq resource escapes, transducers where they matter.

---

## 6. Ranked findings

Severity × confidence. P0 = act before release.

| # | Finding | Where | Conf. |
|---|---|---|---|
| P0-1 | Recursive cursor rebase silently skips/duplicates results (ordinal over unstable derivation order) | `v8.cljc:3142-3163`, `verified_kernel.cljc:2808-2853`, `PageWindow.dfy:306-398` | high |
| P0-2 | Rebase triggers on every write (`proof-digest` ⊇ `basis-t`); makes P0-1 routine; defeats heads/continuation caches | `relay.cljc:252-268,371-410`; own test `recursive_cache_test.clj:212` | high |
| P0-3 | Stale-schema check bypassed whenever rebase is in flight; portable path has no schema binding at all | `core.clj:571-581`; `relay.cljc:70-87` | mod-high |
| P0-4 | Managed cross-revision denotation reuse live + undocumented (docs claim disabled) + zero differential coverage + default-on for DataScript | `v8.cljc:3033-3075`, `subproblem_cache.cljc:920-961`; docs `v8-subproblem-cache.md:66-71`; tests use `:unknown` | high (facts) |
| P0-5 | Nil-anchor guard only in Datomic → full index scan on Datahike/DataScript | `datomic/impl.clj:564` vs `filters.cljc:27`, `datascript/impl.cljc:362` | high |
| P0-6 | Completed-answer tiers unbounded in bytes (`_weight-fn` ignored); ~80 MB/tier at defaults, ~640 MB at recommended 4096 | `core.clj:843`, `cache.cljc:265-296` | high (verified) |
| P1-7 | Arbitrary (hash-order) eviction in completed-answer tier; `:on-repeat` admission degenerates to never-admit at scale | `cache.cljc:265-290` | high |
| P1-8 | `can?` never routes acyclic; cache-enabled checks do O(denotation) linear membership; unadmittable >174k eids; throws >100k grants | `v8.cljc:3549-3565, 3490-3495, 3023-3030` + weights | high |
| P1-9 | CLJS `:advanced` broken and untested; 591 KB foreign-lib; no absolute CLJS perf ceiling | `deps.cljs`, `production_kernel_js.cljs`, workflows | high |
| P1-10 | Single-flight deadlock window: delay monitor + fair untimed 256-permit semaphore; flight registration unbounded | `subproblem_cache.cljc:129-160, 651-661` | mod-high |
| P1-11 | Default process-local random token keys → cursors die across restarts/nodes, silently | `secure_format.cljc:376-377`, three `make-client`s | high (facts) |
| P2-12 | Platform-divergent keyword `sort` feeds SCC cache key; `max-entid` violates SPI integer bound; mixed-type external-id `sort` can throw | `v8.cljc:2225,1270…`; 5 `max-entid` defs; 3 backend.clj sites | moderate |
| P2-13 | `trim-tier` drops live LRU records on failed scan → unevictable entries / tier stops caching | `subproblem_cache.cljc:452-484` | moderate |
| P2-14 | Continuation (`O(n)` touch) and relay page cache (`O(n)` put, wholesale index rebuild) repeat the exact defect class fixed in commits 5–6 | `continuation.cljc:63-69`, `relay.cljc:105-135` | high |
| P2-15 | Verified decisions fed literal constants (`:authenticated? true` …) — proof guards vacuous in production | `relay.cljc:401-409`, `core.clj:1364-1381`, `v8.cljc:3722-3730` | high |
| P2-16 | Dead code on trusted surfaces: proof-descriptor envelope path, `watermark.clj`, zed-v2, `:shared-cache-store`/`:lookup-cache-store` opts, relay `:path-frontiers` | `cache.cljc:893`, `core.clj:2715-2724` | high |
| P2-17 | TLA mutation controls not in CI; mutation ledger stale (96 vs 103); `Pagination.dfy` models nothing that runs | `bin/formal`, workflows, registry.edn | high |
| P2-18 | GCM nonce: random 96-bit, no invocation bound/rotation guidance (NIST 2³²) | `core.clj:234-262` | low-mod |
| P3-19 | JS runtime shim hash-by-`toString` soundness assumption; wrapper-mutation/aliasing rests on Dafny 4.11 emission patterns | `dafny-set.js:15-25`, `DafnySet.java:181` | low (risk), high (fact) |
| P3-20 | `println` warn per check on dangling schema refs; 20 dynamic vars on hot paths; no `*warn-on-reflection*` | `v8.cljc:85-90,499`; various | high |

---

## 7. Sizing at 1M permissioned entities (~10M relationships)

Independent arithmetic (workload: 10k active subjects/window, 2 hot roots, Zipf):

- Denotation working set ≈ 20k subject-root denotations × ~2.7 KB median = ~53 M weight vs **4 MiB budget → ~8% resident** (top-K Zipf coverage ≈ 74% best-case under ideal LFU; lower under LRU churn).
- Cliffs: denotation admission ceiling ≈ 174,600 eids (`4 MiB / 24`); recommended tuning `:max-derived-grants 1000000` ⇒ every large denotation silently rejected; recursive cache-enabled checks throw past 100k grants; membership on a hit is a linear scan of a boxed-Long vector.
- Projection weight under-counts JVM heap ~3× (real ~0.9 KB vs 288 units); "4 MiB" ≈ 10–14 MB actual.
- Completed answers: 1024 entries with unbounded bytes (P0-6). Continuations: 2048 entries — one 20k-page API walk cycles the store ~10×; the 128 MiB weight knob is unreachable (entries bind first) and disagrees with the recorded 16 MiB default in `performance-gates.edn:1280`.
- Churn: exact everything dies per write by design; managed granularity is |relation types| (~1/4 of cache per hot-relation write vs SpiceDB's ~1/10M per-object segment). At 10 writes/s on a hot relation the managed reuse window is ~100 ms.

**Recommendations:** JVM denotation budget 64–256 MiB; denotation representation `long[]`/Roaring with O(log n)/O(1) membership (also fixes the linear scan — sorted vector + binary search is the minimum); make weights honest (measure with JOL once); collapse completed answers into the SubproblemStore (§8.4). **Adaptive sizing**: the signals already exist and nothing consumes them — `record-avoided-backend-operation!` is recompute-cost-in-backend-ops per hit, evictions/occupancy/oversized-rejections are counted per tier. Policy: grow a tier while marginal avoided-backend-ops per additional MB exceeds threshold (needs one new counter: evicted-before-reuse); GDSF/CLOCK-with-cost eviction (priority ≈ frequency × recompute-cost / size) using the commit-6 tombstone vector generalized. All orthogonal to the #74 invalidation philosophy — adaptive *capacity* adds no derived invalidation.

---

## 8. Simplification roadmap (ranked by leverage ÷ risk)

1. **Keyset-order the recursive route; delete the ordinal cursor kind.** Fixes P0-1 structurally; deletes rebase chunk-scanning, `CursorBound`/`RenderError`, the FORMAL-049 gate machinery, direction-unscoped recursive page caching; unifies both routes on `:lookup-eid`.
2. **Dependency-scoped cursor proofs.** Replace `basis-t` proof digests with the managed relation-stamp descriptor the cache already computes. Fixes P0-2/P0-3 (restore the schema check unconditionally), revives heads/continuation reuse across unrelated writes. One validity mechanism for answers, subproblems, and cursors.
3. **Certify + document + default the managed tier.** Write the dependency-proof-union Dafny obligation the docs promise, point the differential oracle at `:coherence-authority :managed` configurations (currently zero coverage), fix the stale docs, then make `:managed` the default everywhere (it is already DataScript's default — currently the *least*-tested configuration is the *most*-enabled one).
4. **Collapse completed answers into the SubproblemStore** as a third `:answer` tier: inherits weight budgets, honest LRU, exact/managed layering; deletes `bounded-assoc`/`admit-entry?`/`install-managed-generation!` and fixes P0-6/P1-7 mechanically. Do **not** merge projection into denotation.
5. **Route `can?` through the acyclic engine** when the routing certificate says acyclic (enumeration already does); binary-search membership on sorted denotations for the recursive case.
6. **CLJS engine decision** (§4): certified-CLJC kernel for CLJS (recommended), or nativeType+rope+ESM+externs if one engine must rule. Either way: add an absolute CLJS ns/result ceiling and a CI `:advanced` build.
7. **De-fork the backends**: one core client orchestration (~900 lines) parameterized by adapter; shared filter validation (fixes P0-5's class); Datahike onto the shared endpoint-pair codec; Datomic onto `eacl.engine.relationships`; port the `LocalStore`/provider tier to Datahike.
8. **One page-token codec** — AEAD everywhere (portable AES-GCM or host-crypto capability) or accept HMAC-only everywhere; today the two adapters disagree on the threat model. Emit a startup warning on defaulted keys (P1-11).
9. **Delete the dead code** (P2-16) and re-target or remove `Pagination.dfy`; model the real frontier/heads algorithm instead.
10. **Lore-style op-count gates on cache maintenance** ("LRU records appended ≤ 2× touches", "eviction probes ≤ K×evictions + stale records") asserted at two sizes in fast unit tests — would have caught commits 5–6's bug class deterministically and today flags `continuation.cljc`/`relay.cljc` (P2-14); reconcile measured counters against the Dafny cost-model functions instead of hand-picked constants; wire TLA mutation controls and `explorer_enumeration_test` into CI.
11. **Small fixes**: pass real facts to `DecideContinuation` (P2-15); `sort-by pr-str` for the SCC cache key; align `max-entid` with the SPI bound; hint the `.nth` kernel decode; demote the hot-path `println` to a rate-limited/optional reporter; `*warn-on-reflection*` in CI.

---

## 9. Lore

The repo (private; "Laplace Oracle for Resource Estimation") is a Rocq-based proof system for symbolic resource envelopes with honest `:proved`/`:refuted`/`:unknown` epistemics — and it already contains an EACL PR #101 study that proved the S·P·(P+1)/2 prefix-replay bound (the exact quadratic commit `1a5a180` eliminated) and refuted the old single-flight claim with a concrete schedule (absorbed: the `ComputationCoordinator` docstring answers it verbatim). EACL already has ~30 informal Lore-style op-count gates; the highest-payoff borrows are §8.10's cache-maintenance invariants, model↔production counter reconciliation, and a scheduled-concurrency replay harness for claims like P1-10.

---

## 10. What was checked and cleared

Late publication into expired lifecycles (lifecycle capture is sound), negative caching (keys carry dependency stamps), lazy values escaping cache scopes (all results eager via `mapv`), `:rebase?` stripped from page-cache identity (sound — divergence implies basis/stamp change), portable cursor MAC ordering (verify-then-parse, domain-separated, constant-time), concurrency fixes from the 2026-07-31 adversarial review (C1/C2/H1/H2/H3/M1 all verified as shipped), deep `concat` stacking / lazy-seq resource escapes / `memoize` / spec-assert leaks (absent).

---

# Part II — Adversarial verification & design wave (same day, ultracode)

Trigger: the [0tx raw recursive performance investigation](2026-08-07-eacl-v8-raw-recursive-performance-investigation.md) (populated `account#parent` recursion: **38.1× point `can?`, 12.1× first page, 9.1× exact count** vs v7 raw; still 4.6–7.8× with schema/plans hot). Method: 10 independent skeptic agents, each instructed to *refute* one load-bearing finding (from Part I or the 0tx report) against `codex/restore-v8-enumeration-performance @ 8b17ee0`, several running empirical repros on fresh JVMs; then 4 design agents for the fixes, gated on the verdicts.

## 11. Verification verdicts

| # | Claim | Verdict | Severity | What changed |
|---|---|---|---|---|
| V1 | Double schema proof per list/count (0tx F3) | **CONFIRMED** (empirically: instrumented `{:schema-proof 2}` per raw list/count, 0 for raw `can?`) | moderate | Worse than claimed: the **client** path does **3** proof reads per uncached list/count, and even `:managed`/`:mutation` clients pay the two **content** proofs because `impl.clj:57` wraps the db in a default content-mode adapter. |
| V2 | Double plan compile + nil-store key work (0tx F4) | **CONFIRMED** | moderate | 2 compiles is the *floor* — a `:rebase?` cursor request compiles **3×**. The eager `recursive-denotation-dependencies` closure walk before the nil-store check wastes more than the plan compile alone. 57 cold `calc-permission-paths` is structurally guaranteed (`v8.cljc:719-720`). Client path fully avoids it. |
| V3 | One-command-per-scan drive/resume protocol (0tx F5) | **CONFIRMED** (reproduced: 2,034 drive + 2,033 resume = 4,067 crossings on a minimal 2,001-star) | moderate | Decisive mechanism finding: scan granularity is **kernel-chosen, one scan per frontier stream** (per discovered grant × applicable rule) — ~98% of scans are *emptiness probes realizing zero datoms*. Measured: chunk-size 64→1024 removes only **1.5%** of crossings; fuel is a non-lever (drive exits at first NeedScan). Only batching helps. Also: the count miss path never publishes a denotation, so overlapping counts repay the full fixed point. |
| V4 | Recursive rebase skip/dup + every-write trigger + schema-check bypass | **CONFIRMED** | high | Refined: skip/dup of *pre-existing still-authorized* results requires an order-perturbing **relevant** write (multi-path first-derivation change), not any write; unrelated writes recompute a deterministic identical denotation — benign but forces a **full fixed-point recompute per page**. The bypassed stale-schema check is *effectively dead code anyway* (`:schema-version` is stamped as `basis-t`, so it could only fail exactly when it is bypassed). Compensating controls: route-kind mismatch fails closed; vanished root → `:restarted`; pages always evaluated under current schema → **no privilege escalation**, "only" pagination-window incoherence. |
| V5 | Acyclic membership-restart unnecessary (Part I §3 item 4 / cursor C2) | **PARTIAL → downgraded to low** | low | **Part I was wrong to call the check unnecessary.** It is the streaming refinement of the certified `RebaseCursorBoundChunked` contract; removing it reintroduces the FORMAL-047 counterexample including a real divergence (boundary revoked + lower-eid member granted → keyset resume permanently omits the new member; certified restart returns it). Restart is honestly flagged `:restarted`. Any change is a *spec* change (a third Dafny outcome, e.g. `CursorBoundResumedPastMissing`), not an engine edit. |
| V6 | Managed denotation live / docs stale / untested / DataScript default | **PARTIAL** | moderate | Live: confirmed (empirically: `:managed-denotation-hits` 0→1 across an unrelated write on a stock DataScript client). Docs stale: confirmed. **But the completeness fear was refuted**: `recursive-denotation-dependencies` *is* a complete closure (adversarial cross-type-arrow REPL test invalidated correctly), and the stamp is the full sorted per-relation vector, stronger than Part I claimed. Directed managed tests *do* exist (`lookup_cache_test`, `recursive_cache_test`, `contract_test.cljc:310-349` asserts cross-revision managed denotation reuse); the accurate gap is **no randomized cached-vs-uncached oracle under interleaved writes** with the managed tier active, and the empirical stale-allow on raw `ds/transact!` is outside the documented writer contract. |
| V7 | Nil-anchor full scan on DS/DH | **PARTIAL** | moderate | **Headline repro was wrong**: `{:subject/id nil}` fails *closed* on DS/DH (downstream `internal-id` nil guard → silent empty page — itself a divergence vs Datomic's throw, masking caller bugs). The **real** hole: `{:subject/type nil}` / `{:resource/type nil}` / `{:resource/relation nil}` with no id key — nil components act as wildcards (`matching-relation-def?`), returning arbitrary relationships from every relation definition, bounded by page size (≤10k), one index seek per relation def. Lookups (`can?`/lookup-resources/lookup-subjects) fail closed on all nil components — verified by six probes. |
| V8 | Answer-cache unbounded bytes / arbitrary eviction / on-repeat degeneration | **CONFIRMED** | moderate | Measured: 1024 × 1000-item pages retain **95.5 MB** (~93 KB/entry); max-page-size 10000 → ~930 KB/entry possible (10× worse than Part I claimed). Dual-put shares references, so 2× requires tier divergence. Admissions degeneration is *sharper* than claimed: eviction always removes the hash-trie-order minimum, so the sighting map **freezes** into a fixed hash-lucky set — measured 2.3% admit rate at 50k keyspace. Silver lining: the portable `LocalStore`'s identical defects are **unreachable** — no production path consumes it. |
| V9 | Single-flight deadlock | **CONFIRMED — empirically wedged** | high | Minimal repro (max-inflight 2, 3 threads, shared nested key) **deadlocked the actual worktree code**: two threads parked in `Delay.realize` on the delay lock, one parked in `Semaphore.acquire` while holding its delay's lock; zero progress; only interrupting the acquire-blocked owner unwedged it (failing all joiners). Absorbing state; `lookup!` joiners also hang. Unreachable behind fixed ≤256-thread pools at default `max-inflight` 256; reachable with virtual threads, async dispatch, or a lowered `max-inflight`. Fix: acquire the computation slot **before** dereferencing the flight delay. CLJS immune (throws instead of waiting). |
| V10 | Platform-divergent keyword sort feeding SCC cache key | **REFUTED** | none | **Part I was wrong.** The premise describes pre-2014 ClojureScript: since CLJS-777, `compare-keywords` is namespace-aware and identical to the JVM (verified in the pinned CLJS 1.12.42 source). Additionally, no sorted keyword vector ever crosses runtimes (process-local atoms; serialization surfaces canonicalize via the portable-render string comparator). The heterogeneous-type `ClassCastException` in proof-record sorts is structurally unreachable (external-id slots compare only after their eid slots matched). Retract Part I ranked finding P2-12's first two clauses; only the cosmetic `sort-by pr-str` hardening survives as a style suggestion. |

Net: 6 confirmed (2 with material aggravations), 3 partially confirmed with substantive corrections, 1 refuted. The Part I ranked table (§6) should be read with these adjustments; in particular **P0-5 is reframed** (type/relation-nil wildcard bypass + fail-closed divergence, not an id-nil full scan), **P2-12 is retracted**, and §8 item 2's "drop the membership check" sub-suggestion is withdrawn in favor of the V5 spec-change path.

## 12. The 0tx regression, fully attributed

Layered attribution for the raw path on populated recursion (0tx star, 2,001 accounts):

1. **Duplicate request-local work** (V1+V2, avoidable, no Dafny): 2 content schema proofs per list/count (~1–3 ms at real schema sizes); 2–3 recursive plan compiles+certifications; 57 cold permission-path walks; dead denotation-key construction against a nil store.
2. **Per-crossing FFI overhead** (V3, host-fixable): limits re-marshalled as 3 BigIntegers on *every* drive and *every* resume; type-name strings decoded per command; per-value validation walks duplicated host-side against an already-certified kernel validator.
3. **Crossing count** (V3, protocol-shaped): 2 crossings per frontier stream, one stream per discovered grant × rule, ~98% empty probes. Chunk/fuel tuning ceiling: 1.5%. This is the architectural layer — only a batched protocol changes it.
4. **No amortization**: count misses never publish denotations; every distinct/overlapping count repays the full fixed point; the answer cache only covers *identical* repeats on an unchanged snapshot.

## 13. Fix designs (D1–D4, full text in the workflow record)

**D1 — cut generated-boundary crossings.** Quick wins (no Dafny, 3–5 days): hoist the per-crossing `IndexedLimits` marshalling (2×4,067 avoided allocations per count), key the type catalog by raw `DafnySequence` (skip per-command string decode), intern empty responses and drop the redundant host per-value walk → est. **1.5–2×** on the 0tx count (gap 4.6–7.8× → ~3–5×). The real fix is a **wave-batched scan protocol**: `AwaitingForwardScans(seq<PendingForwardScan>)`, drive returns a bounded command batch, resume folds ordered responses; ~70 crossings instead of 4,067 (58×), projected **~1.2–2× of v7**. Dafny cost: the coverage-invariant generalization through `IndexedForwardCompleteness`/`IndexedReverseCompleteness`/`IndexedRefinement` — **6–10 weeks**, bounded by a ghost-view trick (model pending scans as virtual queue items so existing queue-cover lemmas apply verbatim). Emission order changes → version the engine order into the recursive cursor digest. Key certification fact: `IndexedRootDenotation.dfy` is order-free, so the semantic authority is untouched — order was never a commitment.

**D2 — request-local raw context (3–4.5 days, no Dafny).** (1) A `delay` on the zero-arity `:schema-proof` **in `make-adapter`** — one edit covers all three backends and every read site (including `make-schema-cache`'s own double read), collapses raw list/count to one proof and client 3→2; justified by the adapter certification suite's existing demand that repeated reads on one instance be equal. (2) A `request-schema-cache` with `:request-local? true` bound by the raw facades — activates all seven memo atoms; deliberately omits `:traversal-analysis` so raw routing keeps today's per-root classification (avoiding a silent certified-path switch and a possible large-schema regression). (3) Nil-store gates on the four denotation lookup/resolve fns, mirroring the pattern `publish-*-denotation!` already uses. Est. raw `can?` 5.68 ms → **~1.8–2.6 ms**; the residual ~0.9–1.6 ms is the irreducible per-call cold compile floor — reaching the 0.96 ms hot bound requires cross-request reuse (client or caller-bound `*schema-cache*`), by design.

**D3 — keyset-order the recursive route (Phase A: 6–9 days, zero Dafny).** The load-bearing discovery: sorting must happen at the host (streaming in eid order is impossible — a later BFS layer can contribute an arbitrarily small eid), and the sorted slice can be fed through the **existing certified `DecideAcyclicPage`**, whose `StrictlyOrderedAcyclicEids` precondition plus host revalidation already certifies every slice. So the functional fix needs *no new Dafny*: sort in the two denotation completers (permutation-guarded, strict-ascending validated at every cache read, denotation key version 2→3), rewrite both recursive page fns onto a shared certified-keyset-page helper, unify on `{:kind :lookup-eid}`, mirror the FORMAL-047 rebase contract via binary-search membership (free — the page needs the denotation anyway). **Deletes ~350 lines** of ordinal/continuation/page-cache machinery *plus the handwritten trusted rebase chunk orchestration* (`verified_kernel.cljc:2808-2854`) — a net trust reduction. Also: O(log n) `can?` membership (was O(n) per hit), counts publish denotations on miss (fixing V3's amortization gap), bare `:last` works, cursors become route-change tolerant. Fixes V4 outright (eids are immutable, so order-perturbing writes can no longer skip/dup pre-existing results). Risks: cold page 1 pays the full closure (mitigated by D1; acyclic route untouched); **all traffic for one (anchor, root) converges on one denotation key — the exact V9 wedge regime, so the V9 fix must land first**. Phase B (Dafny deletion pass: `RebaseCursorBound` family, backward-render mode, `AfterCursor` arm; 1.5–2.5 weeks) can trail indefinitely — the dead generated ops are simply never invoked.

**D4 — populated-recursion gates + op-count invariants (~5 days; 1.5-day quick-win subset).** Four new host-side counters (kernel crossings by operation, backend ops by key, denotation-key builds, cold path walks) behind the existing `inc-stat!` no-op-when-unbound pattern; star/chain/mixed/broad-union fixtures at 2k/10k; nine exact logical-work assertions (crossings = 2×scans+1 law, proofs ≤ envelope, compiles ≤ envelope, zero nil-store key work, continuation resumption, count-publishes-denotation, point-check locality, linearity) with envelopes recorded at *current truth* in an EDN and ratcheted by each fix PR; matched-v7 latency baselines recorded per host-class with the 2.0× bound; plus the wave-1 cache invariants (LRU records ≤ max(1024, 2×entries), eviction probes ≤ evictions + appends, continuation O(1)-per-page detector, rebase O(ordinal) detector). CI wiring also picks up two existing-but-never-run suites: the explorer 10k gate and **`apalache-mutation-control`** (the TLA mutation kills, currently in neither workflow).

## 14. Recommended sequencing

1. **V9 deadlock fix** (small, empirically reproduced, absorbing failure): permit-before-deref restructure + regression test. Prerequisite for D3.
2. **D2 + D4 quick wins** (~1 week combined): request-local context, adapter proof memo, nil-store gates, plan-compile/proof-count gates landed at current truth then tightened. Recovers the avoidable ~55–65% of the 0tx raw gap.
3. **D1 quick wins** (3–5 days): marshalling amortization → another ~1.5–2× on active recursion.
4. **D3 Phase A** (6–9 days): keyset recursion — kills V4 (the one high-severity correctness defect), deletes the ordinal machinery, O(log n) membership, count amortization.
5. **Cursor dependency-scoping** (from Part I §8.2, unblocked by D3): point cursor proofs at the managed relation stamps so unrelated writes stop forcing rebases/full recomputes; restore the schema check unconditionally (V4 showed it is currently dead).
6. **Doc + oracle debt** (days): fix the stale denotation docs (V6), add the randomized managed-tier differential oracle (the only remaining managed-tier gap), port the anchor validator fix per V7's corrected semantics (some?-based shared validator + contract tests on DS/DH).
7. **D1 wave batching** (6–10 weeks Dafny) only if the populated-recursion gate still fails the 2.0× bound after 2–5 — it is the only remaining architectural lever at that point.
8. **V8 answer-cache bounding** (fold completed answers into the weighted SubproblemStore per Part I §8.4) on its own track; **V5/V10: no action** (V5 is working-as-certified — any change is a Dafny spec extension; V10 retracted).


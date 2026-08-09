# Tasks: eacl-v8-root-fixes

All implementation targets the v8 lineage (`release/v8.0` / PR #104 successor). Every performance task tightens a recorded gate envelope; every correctness task adds or un-skips a pinning test. Task numbers reference design decisions (D-1…D-10) and specs.

> **PROGRESS — 54/54 primary tasks and all triggered follow-ons done (groups 1–12, except the explicitly deferred 6.4). Read `HANDOFF.md` first for full context and validation evidence.**
> - **Done:** groups 1 (gates), 2 (deadlock/R3), 3 (raw waste/R4), 4 (marshalling/R5), 5 (keyset recursion/R2 — the HIGH correctness fix), 6.1–6.3+6.5 (dependency-scoped cursors/R1), 7 (answer-cache fold-in/R6), 8 (managed cert + fail-safe default/R9), 9 (filter contract + backend de-fork + Datomic shared relationship engine and construction options/R7), 10 (portable CLJS production authority/R8), 11 (trusted-surface hygiene and Dafny cleanup/R10), 12 (trigger evaluation and wave-batched scan protocol).
> - **Deferred with cause:** 6.4 (AEAD portable codec — sync CLJS GCM; see design D-4 note); Datahike provider-store port (would copy the dead surface 11.1 deleted).
> - **Remaining only if its prerequisite changes:** deferred 6.4, when a synchronous vetted CLJS GCM exists.

## 1. Gates and counters first (D-10, recursion-performance-gates)

- [x] 1.1 Add host-side observer counters: kernel crossings by operation (`verified_kernel.cljc` choke points), backend ops by key (`backend/v8.cljc` invoke), denotation-key builds + dependency-calc counters, cold permission-path-calc counter (all no-op-when-unbound; regenerate the public-source-closure ledger)
- [x] 1.2 Create `recursive_fixture.cljc`: star/chain/mixed/broad-union populated-recursion generators at 2k/10k with deterministic ids and expected counts
- [x] 1.3 Add the populated-recursion op-count test namespaces (non-benchmark, per-push): crossing law, scan envelope, schema-proof count (record current truth: raw 2 / client 3), plan-compile count (record 2; 3 with recovery cursor), nil-store key work (record 2), continuation resumption, linearity; self-check that recursive counters are nonzero
- [x] 1.4 Record matched-v7 latency baselines (same-host harness against the pre-v8 commit) into `formal/verification/explorer-v8-recursive-performance.edn` with the 2.0× bound and variance policy; add the heavy 10k acceptance gates to the formal workflow (fresh bounded JVM, measured first)
- [x] 1.5 Add cache-maintenance invariants: LRU records ≤ max(1024, 2×entries); eviction probes ≤ evictions + consumed stale records; continuation-store puts ≤ pages+1; per-resume recovery-decision bound
- [x] 1.6 Wire the dormant gates: `apalache-mutation-control` step in the formal workflow; explorer 10k gate; regenerate the mutation ledger (103 vs 96 drift) and assert registry/ledger consistency in CI

## 2. Single-flight wedge fix (D-1, single-flight-coordination)

- [x] 2.1 Restructure `subproblem_cache.cljc` per (i-b): acquire the computation slot strictly before the owner's flight deref; run the flight delay body under `*computation-owner*` binding; ticket-guarded flight cleanup on pre-realization throw; keep CLJS saturation-throw semantics
- [x] 2.2 Add the dev-mode top-level-acquire assertion, the documented coordinator invariant (rank-function acyclicity + never-acquire-holding-a-monitor), and the `:stolen-computations` metric
- [x] 2.3 Add the deterministic wedge-schedule regression test (max-inflight 1, latched cross-join — must wedge current code, pass fixed code, with permit/active/flights leak assertions) and the 16-thread randomized soak; both in the per-push suite
- [x] 2.4 Split hit metrics from join waits (joins are not hits) and verify via the metrics test

## 3. Raw request context (D-3, raw-request-context)

- [x] 3.1 Memoize zero-arity `:schema-proof` with a per-adapter-instance delay in `make-adapter`; keep the scoped arity uncached; adapter-double test asserting one computation per instance
- [x] 3.2 Add `request-schema-cache` (memo atoms; `:request-local? true`; no `:traversal-analysis`) and the unified `derived-cache-active?` predicate across the six gate sites; hook the raw facades of all three backends via a shared with-request-engine wrapper that builds one adapter per call
- [x] 3.3 Add nil-store short-circuits to the four denotation lookup/resolve fns (key/dependency construction gated behind store presence)
- [x] 3.4 Tighten gate envelopes: schema proofs raw 2→1 and client 3→2; plan compiles 2–3→1; nil-store key work 2→0; cold path walks → once per unique node; rerun the 0tx-shape scenarios and record

## 4. Kernel boundary phase 1 (D-9, kernel-boundary-efficiency)

- [x] 4.1 Marshal `IndexedLimits` and fuel once per traversal (cache the Dafny objects from init) on JVM and CLJS
- [x] 4.2 Key the verification-identity catalog by raw `DafnySequence` (drop per-command string decode); intern the empty scan response with a no-mutation assertion on received response sequences
- [x] 4.3 Remove the host per-value response walk in favor of the certified kernel validator, JVM and CLJS in lockstep; document the boundary-contract change
- [x] 4.4 Tighten the crossing-cost envelope and rerun the populated-recursion latency scenarios; record the new truth

## 5. Keyset recursive pagination (D-2, keyset-recursive-pagination) — superseded by demand-bounded authorization

> These completed tasks record the intermediate sorted-keyset implementation.
> The later `demand-bounded-authorization-execution` change replaces it with
> route-specific public order, incremental recursive cursors, and explicit
> completion. They are historical implementation evidence, not current release
> requirements.

- [x] 5.1 Sort in the two denotation completers with permutation guard; strict-ascending `valid-recursive-denotation?`; bump the denotation key version
- [x] 5.2 Extract the shared certified-keyset-page helper (realized slice → `DecideAcyclicPage` → work gate → lookup-items) from the acyclic path
- [x] 5.3 Implement probe-then-continue for the no-store path: bounded `RenderPage(S)` probe minting no cursors; exhausted → sort ≤S items; `has-next?` → `continue-indexed-page` to closure on the same state, sort, slice; verify the continuation window validation path accepts to-closure windows (fallback: restart materialization, waste bounded by the probe)
- [x] 5.4 Rewrite recursive forward/reverse pages onto resolve-denotation → rebase/validate → certified-keyset-page; binary-search membership rebase per the FORMAL-047 contract (`:rebased`/`:restarted`)
- [x] 5.5 Unify cursors on `:lookup-eid`; delete the ordinal cursor kind, its validators, the rebase chunk orchestration in `verified_kernel.cljc` + both production kernels, the direction-unscoped recursive page cache, and the `unsupported-recursive-last` restrictions; bump the relay envelope version and the Datomic cursor-context version
- [x] 5.6 Binary-search `can?` membership on sorted denotations; store-bound counts publish denotations on miss (count→list denotation-hit gate from 1.3 goes green)
- [x] 5.7 New regression tests: order-perturbing-write skip/dup scenario (the V4 repro — must fail on old code), route-change cursor survival, rebase hit/miss, bare `:last`, `:desc` slices, sorted-parity acyclic-vs-recursive on a dual-expressible schema
- [x] 5.8 Update ordinal-order-dependent tests across the three backends and relay/kernel/smoke suites; rewrite the release-notes cursor section (order promise, recovery semantics, the >max-derived-grants raw cliff with actionable error text)

## 6. Cursor dependency validity (D-4, cursor-dependency-validity)

- [x] 6.1 Replace `basis-t` continuation-proof digests with the dependency-stamp descriptor (relay `dependency-context` + Datomic cursor proof); unrelated-churn test flips from `:rebased` to continuation hit
- [x] 6.2 Stamp the actual schema mutation identity into page tokens; validate unconditionally on acceptance (including recovery mode); cross-generation resumption test
- [x] 6.3 Thread computed values into every verified continuation/authority decision (delete the literal `true`/`false` inputs); regression observing the kernel's rejection branch for an expired token
- [ ] 6.4 (DEFERRED — see design.md D-4 status note: sync CLJS GCM not responsibly implementable in this change) Unify on the AEAD codec for portable cursors (JVM host crypto; CLJS WebCrypto/pure-JS); delete the HMAC-only path; capability sets advertise uniform `:cursor` properties
- [x] 6.5 Defaulted-key startup warning on all three `make-client`s; document the GCM nonce invocation bound and rotation guidance

## 7. Answer-cache fold-in (D-6, answer-cache-bounding) — after group 2

- [x] 7.1 Add the `:answer` tier to the SubproblemStore (weight fn honored, per-entry ceiling, oversized-rejection metric); route `can?`/pages/counts through layered resolution
- [x] 7.2 Delete `bounded-assoc`, `admit-entry?`, `install-managed-generation!`, the standalone answer maps, and the dead portable `local-store` path (recorded deviation: `install-managed-generation!` kept — the schema-stamp CAS install still rolls the managed store forward; its standalone entries map died; `:admit-on-repeat?` kept but made honest via a FIFO sighting window instead of deleted)
- [x] 7.3 Hot-key-survives-churn and byte-budget tests; repeat-admission behavior test at 50× keyspace (the frozen-set scenario must pass post-fix)
- [x] 7.4 Update `cache-stats` shape and docs; verify the exact/managed answer promotion path through the shared layering

## 8. Managed certification and authority posture (D-5, managed-reuse-certification)

- [x] 8.1 Flip the DataScript default to `:coherence-authority :unknown`; update the one asserting contract test; add the stale-ALLOW pinning regression (raw retraction on a default client must deny)
- [x] 8.2 Rewrite docs (cache.md, v8-subproblem-cache.md, v8-consistency-cache-operations.md, release notes, README quickstart): managed cross-revision reuse covers projections AND denotations; writer contract; `:managed` as explicit opt-in; remove the "denotations disabled" claims (also corrected the stale max-fold dependency-stamp description to the sorted per-relation vector)
- [x] 8.3 Add `:managed` configurations to the randomized differential oracles on all three backends (interleaved EACL-API writes, cached-vs-cache-free equality); port the cache model oracle to DataScript (and Datahike; DataScript port also runs in the CLJS suite)
- [x] 8.4 Add the dependency-closure completeness assertion to plan compilation (compiled rule relation eids ⊆ closure, typed failure) (engine-direct raw op-count envelope re-recorded 10→11 path calcs with rationale — the one closure walk per compile; memoized to zero under any schema cache)

## 9. Backend de-fork (D-7, backend-unification)

- [x] 9.1 Write the unified filter-validation and error-contract tests first (value-presence anchors incl. nil type/relation throws, `:nil-anchor-keys`, pagination-option parity) — red on current DS/DH
- [x] 9.2 Move shared orchestration into core (the nine operations, snapshot context, cursor plumbing, cache wiring, integrity) parameterized by SPI + options; convert Datahike and DataScript cores to thin construction shims (`eacl.client.orchestration`, one `ClientAuthorization` record + one `make-client`; DS core 1,098→175 lines, DH 1,074→180; api holds vars for late-bound instrumentation); move Datomic relationship pages onto `eacl.engine.relationships` with the shared `:relationship-index` cursor edge and bump the Datomic page-token version
- [x] 9.3 Unify the option map across Datomic vs DS/DH (Datomic now accepts canonical `:security-key(ring)/(kid)`, `:cursor-ttl-seconds`, and `:object-id->lookup-ref`; the old Datomic spellings are non-mixable legacy aliases; unknown-option ex-data matches the shared orchestrator; Datomic Zed-token extensions are documented). The decorative Datomic provider-store option is now rejected with `:unsupported-provider-store`, while `eacl.cache/no-cache` controls the real private stores. DROPPED with cause: "port the weighted provider-store tier to Datahike" — the Datomic provider-store path it would copy is the write-only dead `:shared-cache-store`/`:lookup-cache-store` surface that 11.1 deletes; porting it would add dead code. Datahike already consumes the shared endpoint-pair codec.
- [x] 9.4 Delete the superseded per-backend copies (both forked filter validators, both forked orchestration layers, both per-backend Authorization records); adapter certification, contract-support, cache-model, consistency, and op-count suites green on all three backends; surviving per-backend module totals recorded: eacl-datascript src 1,693 lines, eacl-datahike src 1,933 — both under the ~2,100 target

## 10. CLJS production engine (D-8, cljs-production-engine)

- [x] 10.1 Promote the CLJC oracle engine to the CLJS production kernel (the `:cljs` kernel-default branch); keep the generated JS kernel available to the differential rig as the oracle
- [x] 10.2 Run the full certification rig against the CLJS engine in CI: cross-runtime vectors, counterexample replay, mutation controls, randomized differential oracle
- [x] 10.3 Add the CI `:advanced` build job (compiles + runs the full DataScript/core and generated-oracle CLJS suites with warnings-as-errors); fix the 433-name foreign-lib property boundary with checked-in Closure externs; re-anchor all rebuilt artifact-size gate baselines (browser 591,497 bytes; JS-with-runtime 949,688 bytes)
- [x] 10.4 Add the absolute CLJS ns/result ceiling gate; record bundle-size budget and verify no BigNumber on the hot path; document the browser trust posture and the recorded nativeType alternative

## 11. Trusted-surface hygiene (trusted-surface-hygiene)

- [x] 11.1 Delete dead code: authenticated-envelope completed-cache path + `:shared-cache-store`/`:lookup-cache-store` options, `watermark.clj`, zed-v2 constructors, relay `:path-frontiers` branch, `:latest-result` kind; audit test asserting absence (`eacl.datomic.trusted-surface-audit-test`; EACL-FORMAL-003's pin retargeted to it since its defective adapter no longer exists; two lookup-cache tests re-goldened onto the live native-answer/current-cache probes)
- [x] 11.2 Enable `*warn-on-reflection*` with warnings-as-errors for core + backends in CI (`bin/reflection-gate`, wired into test.yml); hinted the surviving sites (schema-lock macros — syntax-quote drops meta on unquotes, so hints ride let-bound locals — SecureRandom nextBytes, backend digest getBytes, datahike migration sleep; the consistency.clj warnings died with zed-v2)
- [x] 11.3 Rate-limit/optionalize the schema-resolution warning (schema warn; parser prints deferred) (once per generation or via reporter); remove parser REPL prints from shipped namespaces
- [x] 11.4 Dafny cleanup pass: deleted the ordinal rebase family, backward-render mode, and `AfterCursor`; deleted `Pagination.dfy` and moved the retained window/continuation laws into `PageWindow.dfy`; mapped all 26 models to shipped or explicit spec-only consumers; regenerated Java/JavaScript/browser artifacts, cross-runtime vector gates, source closure, artifact baselines, and the release manifest

## 12. Conditional: wave-batched scan protocol (D-9 phase 2, kernel-boundary-efficiency)

- [x] 12.1 Evaluate the trigger: with groups 3–5 landed, does the populated-recursion latency gate meet 2.0×? Record the decision in the gate EDN either way (**:triggered** — recorded in `explorer-v8-recursive-performance.edn` `:batching-trigger-decision` with basis and scope; groups 6–9 do not change crossing counts, so the post-group-5 medians remain the truth: counts 3.7–5.9×, deep checks 5.7–56×)
- [x] 12.2 Implement bounded forward/reverse scan waves (`Drive*Scans`/`Resume*Scans`) with a 64-command default, ordered response folding, and Yielded-on-partial-wave rollback; prove exact pending-scan ghost views and generalized coverage invariants; regenerate both kernels; version emission order 2 into cursor digests; re-golden ordered generated/portable tests
- [x] 12.3 Enforce exact independent-stream crossings `2×⌈streams/64⌉+1` and the recursive star envelope with separately counted fuel yields; regenerated JVM differential 51 tests/16,156 assertions, CLJS portable/generated differential 45/9,971 under normal and advanced builds, focused crossing 39/8,500, counterexample replay 61/18,310, and mutation control 249/249 all pass

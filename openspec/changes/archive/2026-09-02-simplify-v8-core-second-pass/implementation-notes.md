# Implementation notes (2026-09-02)

## Baselines (pristine tree at 9e0105f2, fresh nREPL `:dev:test:cljs-test`)

- CI battery: 1138 tests, 40439 assertions, 0 failures, 0 errors.
- `clj-kondo` over the five source roots: 1 warning (an unused require left by the previous commit), 0 errors.
- `bin/formal source-closure`: passed (93 roots, 2359 reachable definitions).
- `bin/reflection-gate`: clean.
- `bin/formal verify`: 49 modules, 9384 proof efforts, 0 errors (the assurance contract still said 9385; the 2026-08-31 `ConsistencyDecision.dfy` revision retired one obligation and the ratchet was never re-run because the manifest gate did not execute in CI).

## Findings not previously recorded

- **Regression from the first pass.** Its task 5.7 unified the stable-route count option keys and dropped `:cut-point!`; `count-resources`/`count-subjects` ran to exhaustion under an expired deadline or a cancelled token. Fixed through one shared reducer option-key superset; the new `exhaustive-counts-honour-the-request-cut-point-test` produces four failures under the old key list and passes under the new one.
- **Tasks marked done without the code changing.** 5.5 (sidecar single-constructor rebuild), 5.8 (`identity` shadowing in the reducer), 4.12 (probe context) — all addressed here or explicitly re-dispositioned.
- **Observation-only bookkeeping on the production path.** Vector-evaluator masks (four int arrays per node, two bit mutations per resolved candidate) consumed only by `*vector-stats*`; the recursive checkpoint's two SHA-256 digests and three `pr-str` sorts per batch, consumed by nothing on the engine routes; a 20-key counter aggregate per scanned relationship consumed only by a throw's `ex-data`.
- **Per-datom allocation in every backend.** The shared prefix predicate allocated a subvector and a prefix copy per scanned datom; Datahike carried three private copies of the same closure.
- **Defeated fast path.** The request-counter ledger's thread-local cache was keyed to the `call-with-ledger` frame, which every request immediately nested under further `binding` forms; per-tuple increments took the dynamic-var path with a map-entry allocation each.

## Deviations from the survey ledger

- Deadline sampling on the reducer and probe paths is unchanged; only checks with no work between them were removed (evaluator pre-probe, seekable double check) or moved to bracket a bounded batch (relay identity rendering). The per-pop probe check in `stable-route` stays because fetch-free pops are possible.
- `sealed-plan/current-order-contract` stays: a test rebinds `rank-contract` and expects the sealed order contract and fingerprint to follow it.
- `relationships/execute-page` keeps its three-argument arity: the shared relationship tests call it.
- The two lineage canonicalizations in the relay dependency context stay: replacing them with `=` could change equality for record-valued lifecycle fields.
- Datomic/Datahike external-id defaults keep `d/entity`: the datom-based read throws on databases without the id attribute.
- Rewriting the sixteen answer-substitution mutation controls, the CI cadence of the retired-model Dafny verification, the triple execution of shared suites across workflows, the Datalevin per-operation read-scope seam, and least-path witness memoization across chunks are recorded as follow-ups.

## Final certification (all on freshly started JVMs against the final tree)

- CI battery (`:dev:test:cljs-test`): 1139 tests, 40437 assertions, 0 failures, 0 errors (one new regression test; the removed `half-identity` assertions account for the lower total, and the count is stable across two fresh runs).
- `clj-kondo` over the five source roots: 0 errors, 0 warnings.
- `bin/formal source-closure`: passed (93 roots, 2369 reachable definitions).
- `bin/reflection-gate`: clean.
- Parity nREPL (`:dev:test:cljs-test:formal-smoke`, strict replay): generators + cache-strategy-adversarial + mutation-control: 13 tests, 891 assertions, 0 failures; counterexample replay: 71 tests, 18228 assertions, 0 failures (67/67 entries resolved in strict mode); generated differential suites (java-round-trip, operator-decision, wire-format-bridge, semantics-bridge, page-window-bridge, cross-runtime-vector, production-kernel, state-trace-differential): 52 tests, 18280 assertions, 0 failures.
- Consistency-boundary gate: passed, median p95 261.8 ns (ceiling 15000 ns; the previous pass recorded 484.5 ns on this host). Routing-certificate gate: passed.
- DataScript ClojureScript suite: 560 tests, 13715 assertions, 0 failures, 0 errors.
- Datalevin suite (`:dev:datalevin-test`): 419 tests, 10490 assertions, 0 failures, 0 errors.
- `bin/formal verify`: 49 modules, 9384 proof efforts, 0 errors (no `.dfy` changed). `bin/formal manifest`: generated; validation exits 3 (assurance withheld by the authored contract's residual obligations), which the new CI step accepts.
- `bin/formal browser-bundle` with the widened freshness guard: reuses fresh output when nothing changed.

## Post-PR CI findings (2026-09-02)

The first CI runs of the PR failed in three places that the local batteries did not reproduce.

- **Parity job never reached its suites.** `formal.yml` placed `-J-Deacl.replay.strict=true` after `-M:…:nrepl --port 7788`; a `-J` option after the main alias is not a JVM option, and `nrepl.cmdline` then ignored `--port` and bound a random port, so the readiness probe timed out. The same step failed on both base branches on 2026-09-01. Fixed by moving the flag before `-M` (the characterization fixture pins only the temporal job text, so the reorder is safe).
- **Two Caffeine-backed tests asserted a hot entry survives churn and lost it on CI runners** (`private-cursor-construction-context-cache-is-bounded-test`, `optional-telemetry-does-not-disable-required-lru-hits-test`). Mechanism: `Caffeine.newBuilder()` without an executor runs policy maintenance asynchronously on the common `ForkJoinPool`. Reads are not recorded while the lazily initialised frequency sketch is unsettled (`skipReadBuffer`), and the sketch is initialised inside that asynchronous maintenance, so a hot key can carry frequency 0 into an admission contest against a one-hit candidate and be evicted. A single-threaded sequence of a few thousand operations settles fast enough on this host (0 failures in 200 iterations of each test) but not on a loaded 4-vCPU runner. Fixed in `eacl.cache.standard-lru/empty-lru` by running maintenance on the calling thread (`.executor` = run-in-place). This is the one runtime-profile change in the pass: the W-TinyLFU policy is unchanged, but retention is now a function of the access sequence alone, and the cache no longer depends on a pool thread (relevant to Lambda-class hosts). `docs/cache.md` records the executor choice.
- **`speculative-snapshots-are-immutable-lifecycle-values-test` compared two per-call token mints across a wall-clock second.** Decoding the two CI tokens showed identical payloads except `issued-at`/`expires-at` differing by one second. The test now pins `causal-token/now-seconds` around both mints; the assertion still compares the full authenticated token strings.

Cost of the executor change, measured through the store API on one thread with a 1024-entry cache under continuous eviction pressure (every publication evicts): about 170 ns per publish-plus-lookup with maintenance on the caller versus about 90 ns with maintenance offloaded to the pool, i.e. roughly 80 ns of policy work per publication now spent on the publishing thread instead of a pool thread. A publication happens once per computed decision, so this is below 0.1% of a request that computes anything.

Re-certification on freshly started JVMs after these three changes: CI battery 1139 tests, 40437 assertions, 0 failures; Datalevin suite (same directory set as above) 419 tests, 10490 assertions, 0 failures; generators + adversarial + mutation controls 13 tests, 891 assertions, 0 failures; strict counterexample replay 71 tests, 18228 assertions, 0 failures; the eight generated-differential suites 52 tests, 18280 assertions, 0 failures; consistency-boundary gate passed at median p95 800.5 ns on a quiet host and 423.7 ns with another suite running (the benchmark does not touch the cache; the spread is measurement noise under a 15000 ns ceiling); routing-certificate gate passed; `bin/reflection-gate` clean; `clj-kondo` reports only the pre-existing unresolved-symbol diagnostics in the Datomic test file. The ClojureScript suite was not rerun: the change is JVM-only (`#?(:clj ...)` branch, a `.clj` test, the workflow, and prose).

## Status at hand-off (2026-09-02, after commit 876b8303)

Done:

- Core PR #165 (`codex/simplify-v8-core-second-pass`, base `codex/streamline-kernel-authority-and-counts` = PR #163) carries eight commits; head `876b8303`. The Tests workflow is green for that head on both the push and the pull-request events. The Formal workflow's parity job is green on both events (it had never reached its suites before the flag fix). At hand-off the `temporal-models` job was still running on both events and `dafny-and-generated-boundaries` on the push event; the previous push run's Dafny job (run 33616935781) had also not finished. The PR description lists the findings, the CI fixes, and the certification table.
- Demo PR #70 (`codex/upgrade-eacl-second-pass` on eacl-demo) is repinned to `876b8303` (commit `85b1f8e`, 19 tracked files rewritten by `npm run upgrade:eacl`) and pushed. It is not merged; `production` is untouched.
- No session JVMs are left running. The core working tree is clean apart from the four untracked `openspec/changes/*` directories that predate this work; the demo tree has two untracked `verification/results/*` files that also predate it.

Next steps, in order:

1. Wait for the Formal workflow on `876b8303` to finish: `temporal-models` on both events and `dafny-and-generated-boundaries` on the push event (it now runs `bin/formal manifest`; exit 3 is the documented "assurance withheld" outcome and is accepted). If the Dafny job fails, inspect the manifest step first; the `.dfy` sources did not change in this pass.
2. Merge the demo: `gh pr merge 70 --merge` on eacl-demo, then `git fetch origin && git push origin origin/main:production`, then watch `deploy-demos.yml` (deploys run only on pushes to `production`). Smoke the live demos afterwards.
3. Land the stack: PR #163 first, then PR #165 (its base is #163's branch; retarget to `main` if #163 merges by squash).
4. Recorded follow-ups, unchanged: the sixteen vacuous answer-substitution mutation controls; per-push Dafny wall clock on retired-engine models; triple execution of shared suites across workflows; the Datalevin per-operation read-scope seam; least-path witness memoization across chunks; the retired indexed authority cut.

## Traps recorded for the next pass

- A warm nREPL that reloads `eacl.verified-kernel` invalidates every already-instantiated generated kernel (the protocol is redefined); a reload of `eacl.engine.stable-reducer` likewise breaks `instance?` checks against `AdmissionKey`/`ReducerState` in previously loaded tests. Certification batteries must run on a fresh JVM.
- The dev `clj-nrepl-eval` returns nil from `cognitect.test-runner.api/test` on success; use `cognitect.test-runner/test` with adapted keys to capture the summary map.
- Passing `-J-D…` after the `-M` alias does not reach the JVM; strict replay was enabled at runtime with `System/setProperty` before running the replay suite.

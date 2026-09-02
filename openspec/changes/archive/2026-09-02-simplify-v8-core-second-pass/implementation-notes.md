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

## Traps recorded for the next pass

- A warm nREPL that reloads `eacl.verified-kernel` invalidates every already-instantiated generated kernel (the protocol is redefined); a reload of `eacl.engine.stable-reducer` likewise breaks `instance?` checks against `AdmissionKey`/`ReducerState` in previously loaded tests. Certification batteries must run on a fresh JVM.
- The dev `clj-nrepl-eval` returns nil from `cognitect.test-runner.api/test` on success; use `cognitect.test-runner/test` with adapted keys to capture the summary map.
- Passing `-J-D…` after the `-M` alias does not reach the JVM; strict replay was enabled at runtime with `System/setProperty` before running the replay suite.

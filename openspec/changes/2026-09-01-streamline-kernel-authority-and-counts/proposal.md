## Why

The cleanup pass (`cleanup-and-simplify-v8-core`) deliberately excluded anything that changes an algorithm, an observable contract, or the formal-authority story. Its source survey nevertheless found several places where the *algorithm itself* does more work than the problem requires — per-request generated-kernel crossings whose results are total functions of already-validated input, counts computed by looping a paginated lookup (re-paying per-page seal/cursor machinery a count never needs), a deadline check that samples the clock on every transition, and 12 kernel operations plus a 789-line dormant kernel half kept fully wired for engines that no longer exist. These are recorded here as a separate, measurement-gated change because each one moves authority, observable granularity, or the proof surface — exactly what the cleanup was forbidden to touch.

## What Changes

- **Host-native authority for request-invariant kernel decisions.** [IMPLEMENTED in this change for the two consistency operations; `normalize-page-request` evaluation deferred] `:consistency-plan` and `:consistency-validation` cross into the generated Java kernel once per request, yet each is a small total function of validated input with a line-for-line portable Clojure implementation (`eacl.engine.portable-decisions`) that is already the production kernel on CLJS. Route these (and evaluate `normalize-page-request`'s per-page `:relationship-page` crossing against its existing host-native equivalent, `host-normalize-page-request`) through the differentially certified host implementation on the JVM as well. This removes per-request/per-page JVM↔generated marshalling; the generated kernel remains the offline differential oracle. Requires updating the recorded crossing law (the `kernel-boundary-efficiency` spec mandates the law is updated, never silently drifted), the assurance-claim wording (differential conformance, not proof-carrying crossing), and the crossing-count gates.
- **Dedicated streaming count evaluation.** [IMPLEMENTED in this change] `recursive-operator-count` resumed on the outer paged lookup — every count page paid outer cursor validation, semantic-scope digests, recursive edge construction, and page-info assembly it then discarded. The loop now resumes on the internal cover boundary with the seal, proof identity, and anchor resolved once per count; a regression test pins zero per-page digests. Measured on 1,500 recursive matches: p50 624.8 ms → 590.7 ms (−5.5%). Profiling the remainder located the true dominator: `operator-recursive/evaluate-cached-many` consumes ~96% of count wall time (~90–125 ms per 256-candidate batch, ≈380 µs per candidate on a three-scan derivation; `command-identity` 15 ms and direct dispatch 7 ms of the 617 ms total are already amortized). **Follow-up target:** the demand-solve loop's per-candidate constant in `evaluate-many` — mutation-control-dense territory needing its own differential-gated change.
- **Retire the 12 dead kernel operations end-to-end.** `:ordered-merge-step/chunk`, `:recursive-routing-certificate`, `:enumeration-route`, the four `:acyclic-*`, `:indexed-*`, and `:authorization-evaluation` are reachable only from formal suites; the `IndexedTraversalKernel` protocol and `eacl.engine.portable-indexed` (789 lines) ship in the advanced CLJS bundle for paths production never enters. Remove them from the hosts, suites, mutation registry, and CI benchmarks (one per-push benchmark gates `:recursive-routing-certificate` today), and either delete the corresponding Dafny surface or re-label it spec-only in the assurance contract per the `trusted-surface-hygiene` requirement that every model maps to shipped code. This shrinks `verified_kernel.cljc`, `production_kernel.clj`, `portable_decisions.cljc`, the CLJS bundle, and CI wall-clock.
- **Bounded-staleness deadline sampling.** `execution/check!` reaches a clock read on every reducer/operator transition. Sample the clock every N transitions (N chosen so worst-case overshoot stays within a documented bound, e.g. one physical chunk), making deadline enforcement O(1/N) per transition at the cost of bounded detection latency. This changes observable deadline granularity and the `authorization-deadlines` contract wording, which is why the cleanup only removed the allocation around the check and left sampling frequency alone.
- **Batched direct-membership/scan protocol — only under measured need.** The `kernel-boundary-efficiency` spec already defines the trigger ("Batched scan protocol under measured need"): if the populated-recursion latency gate still misses its bound after the cleanup's host-side amortizations land, extend the protocol so one drive returns a bounded batch of scan commands (crossings 2 per stream → 2 per batch), with coverage/refinement proofs re-established. This item exists to record the trigger and sequencing, not to pre-commit the work.
- **Derivable source-closure roots (enabler).** The 93 hand-maintained `ns/var` root strings in `bin/public-source-closure.mjs` are the single most refactor-hostile gate and the stated blocker for decomposing the 4,301-line `client/orchestration.cljc`. Derive the root list from code (e.g. a `^:closure-root` metadata marker or the public-API var maps the backends already build), keeping the forbidden-token list authored. Unlocks the deferred orchestration decomposition without a four-list edit tax on every rename.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

Delta specs for the implemented tranche live under `specs/`:
- `kernel-boundary-efficiency`: request-invariant decisions cross no
  runtime boundary; counting pays no page-presentation work.
- `formally-verified-authorization-engine`: the released-runtime cutover
  scenario now maps consistency decisions to the differentially certified
  portable procedure on both platforms (generated model as offline oracle).
- `formal-implementation-conformance`: consistency authority class is
  host-native; the cutover accounting requires no consistency crossings.

Deltas for the still-parked bullets (deadline sampling, dead-operation
retirement, batched protocol, derivable roots) are written when those are
picked up.

## Impact

- **Code**: `verified_kernel.cljc`, `formal/production_kernel*.{clj,cljs}`, `portable_decisions.cljc`, `portable_indexed.cljc`, `engine/v8.cljc` (count path, page normalization), `consistency.cljc`, `execution.cljc`, `formal/dafny/*`, `formal/mutations/registry.edn`, CI workflows, `bin/public-source-closure.mjs`.
- **Sequencing**: after `cleanup-and-simplify-v8-core` lands (its baselines and falsifiable gates are the measurement instrument this change depends on). Each bullet is independently shippable and independently rejectable on measurement.
- **Discipline**: every item changes observable behavior or the proof surface, so each requires: a recorded before/after measurement on the deterministic-work and latency gates, spec deltas for the affected capabilities, and a green certification sweep including regenerated crossing laws. No item proceeds on plausibility alone.
- **Artifacts**: complete for the implemented tranche (specs deltas, design, tasks); the parked bullets get their own artifacts when scheduled.

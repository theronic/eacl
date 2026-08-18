# Stable-discovery release assurance

Tracked release-assurance tree for the `adopt-stable-discovery-enumeration`
change (tasks 3.1/3.2/3.5). Contains the retained-scope formal models and
executable bridges, promoted from the read-only evidence archive at
`exploration/stable-discovery/` (which remains the provenance record and the
home of everything parked).

## Gate

```bash
sh formal/stable-discovery/verify-fast.sh
```

Requires the pinned toolchain cache under `target/formal-tools/` (see
`bin/bootstrap-formal-tools`). Budget: 10 s hard ceiling, ~8 s observed. The
gate self-checks its model/config/bridge manifests, scans for Dafny escape
hatches, pins the TLA+ assumption-boundary fingerprint, and enforces the
exact aggregate obligation count (528).

Contents: 42 Dafny leaves (`MembershipProbeCheck.dfy`, added 2026-08-18,
proves the membership-probe point check equal to reverse-denotation
membership); two TLC families (`AtomicAttempt` with 3 mutants,
`ProgressCheckpoint` with 6 mutants); the randomized refinement campaign
(18,000 checks, 22 mutation controls) and four source bridges (public
schema, sealed plan/rank, cursor codec, checkpoint publication). The former
compiler-seam bridge bound to the retired `eacl.engine.v8` rule compiler and
was removed with it; the production compiler (`eacl.engine.sealed-plan`) is
gated by the module suites (`stable-discovery-gate-test`).

Bounded exhaustive and state-space expansions are deliberately NOT in this
gate (task 3.5): the exploration-era exhaustive campaigns live in the archive
(`qualify-forward-runtime.sh`, the 400,000-transition cap campaign, the
160,000-check randomized tier). The production-source gates of tasks 5.5
(CLJ half) and 6.7 live in the module test suites
(`eacl.engine.stable-reducer-test`, `stable-discovery-gate-test`,
`stable_page_test`, `physical_route_test`); the CLJS halves are still open.

## Disposition (tasks 3.1/3.2)

**Retained here** (the width-one engine's scope): every leaf in this
directory — grounding/denotation, sealed plan + rank certificate, the
reducer family (soundness, completeness, termination, exact uniqueness,
history-free erasure, targeted driving), one-value scan normalization,
bounded chunk retention (`BoundedSidecar`), pagination/edge/checkpoint
composition including the undelivered-lookahead segment
(`RuntimeCheckpointComposition`), atomic admission and attempt outcomes,
checkpoint slot weighting, descriptor identity, count composition,
bidirectional reachability, and the representation leaves
(`RuntimeStackRefinement`, `ConcreteHistoryFreeRuntime`,
`OwnedTransientSnapshot`).

**Parked with the future concurrency change** (archive only; their
refinement obligations restart that change): `ReducerReadAhead.tla` (+5
mutant configs), `DescriptorCoalescing.tla` (+1), `ServiceLifecycle.tla`
(+4), `ReadableWorkIndex.dfy`, `WeightedResponseLease.dfy` (30 obligations
combined), and `physical_scheduler_refinement_bridge.clj`.

**Deleted at task 9.2** (models of the rejected byte-stable symmetric
candidate — `formal/dafny/StableDiscovery.dfy`, `formal/tla/EaclPureReduction.*`,
`formal/tla/EaclDiscoveryConcurrency.*`, `formal/tla/EaclServiceGovernor.*`,
`formal/tla/EaclProgressCache.*`, `formal/tla/EaclSharedReadWaiters.*`, and the
experimental `formal/verification/stable-discovery.edn` manifest entry): none
of these files exist any more; the archive under `exploration/stable-discovery/`
is the only record.

## Rules

- Models here must stay byte-faithful ports of the archived leaves until a
  production contract change forces an edit; any order-affecting edit bumps
  the order ABI (see the change's specs).
- The bridges bind to exploration-shaped fixtures; task 3.3 rebound the CLJ
  side to normalized production fixtures against the production compiler
  (`eacl.engine.sealed-plan`) and task 3.4 added the production mutation
  controls (logical-width, admission-key granularity, lookahead segment); the
  CLJS halves of 3.3/3.4 remain open.
- No proof escape hatches; the gate fails on `assume`/`axiom`/
  `verify false`/`decreases *`.

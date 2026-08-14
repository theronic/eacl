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
`bin/bootstrap-formal-tools`). Budget: 10 s hard ceiling, ~7 s observed. The
gate self-checks its model/config/bridge manifests, scans for Dafny escape
hatches, pins the TLA+ assumption-boundary fingerprint, and enforces the
exact aggregate obligation count (506).

Contents: 41 Dafny leaves; two TLC families (`AtomicAttempt` with 3 mutants,
`ProgressCheckpoint` with 6 mutants); the randomized refinement campaign
(18,000 checks, 22 mutation controls) and five source bridges (compiler seam,
public schema, sealed plan/rank, cursor codec, checkpoint publication).

Bounded exhaustive and state-space expansions are deliberately NOT in this
gate (task 3.5): the exploration-era exhaustive campaigns live in the archive
(`qualify-forward-runtime.sh`, the 400,000-transition cap campaign, the
160,000-check randomized tier). The release/nightly tier will be extended
with production-source gates as tasks 5.5 and 6.7 land.

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

**Quarantined pending deletion at task 9.2** (models of the rejected
byte-stable symmetric candidate; untracked working files, not release
assurance, kept only while the old engines remain differential oracles):
`formal/dafny/StableDiscovery.dfy`, `formal/tla/EaclPureReduction.*`,
`formal/tla/EaclDiscoveryConcurrency.*`, `formal/tla/EaclServiceGovernor.*`,
`formal/tla/EaclProgressCache.*`, `formal/tla/EaclSharedReadWaiters.*`, and
the experimental `formal/verification/stable-discovery.edn` manifest entry.

## Rules

- Models here must stay byte-faithful ports of the archived leaves until a
  production contract change forces an edit; any order-affecting edit bumps
  the order ABI (see the change's specs).
- The bridges bind to exploration-shaped fixtures today; task 3.3 rebinds
  them to normalized production fixtures in CLJ and CLJS once the production
  compiler (section 4) exists, and task 3.4 adds the production mutation
  controls (logical-width, admission-key granularity, lookahead segment).
- No proof escape hatches; the gate fails on `assume`/`axiom`/
  `verify false`/`decreases *`.

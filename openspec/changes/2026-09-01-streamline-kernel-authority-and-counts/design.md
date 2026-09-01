## Context

See proposal.md. This implementation tranche covers the first two bullets —
host-native consistency authority and streaming counts — and leaves the
other four (dead-operation retirement, deadline sampling, batched scan
protocol, derivable closure roots) parked with their own triggers.

Measured baselines on the cleanup commit (`c3e3b3ef`, same host/session as
the after-numbers):
- Consistency boundary gate: trial p50s 490–652 ns per decision,
  median-p95 1,677 ns; logical work includes the per-request generated-Java
  crossing (`:plan-decisions 1`).
- Operator-routed recursive count, 1,500 matches via one exclusion +
  `parent->view` recursion (DataScript, engine-direct, routing enabled):
  p50 624.8 ms, min 605 ms — the page loop re-enters checkpoint resume,
  cover-page assembly, outer cursor-edge/digest construction, and the
  recursive component solve once per 256-item page.
- The non-operator (union-only) recursive count on the same shape: p50
  5.2 ms — two orders of magnitude between the two count paths.

Production consistency flow: `consistency.cljc/decide` →
`verified/decide` (kernel selection → `invoke-kernel` → generated Java on
the JVM). The cutover spec pinned consistency to the generated kernel; the
authority suite requires `:consistency-plan` generated crossings per
backend; the consistency-boundary gate asserts exact logical-work
correspondence including that crossing.

## Goals / Non-Goals

**Goals:**
- Zero generated-runtime crossings for consistency decisions on the JVM,
  with byte-identical decision values (the portable procedure is already
  the CLJS production authority and is differentially certified against
  the generated model).
- Recursive operator counts stop paying page-presentation work; identical
  counts, truncation, limits, and per-page budget semantics.
- Every touched gate updated in the same commit: authority-suite required
  operations, consistency-boundary gate model, assurance-contract entry
  points, spec deltas above.

**Non-Goals:**
- No change to `:relationship-page`/`:cursor-continuation` authority (the
  crossing law for traversal stays untouched).
- No single-run exact counts: each internal count page keeps its own
  reducer budgets exactly as the paged loop did — a single run would move
  limit errors earlier on huge exact counts (observable change, rejected).
- No removal of the generated consistency model from the proof closure.

## Decisions

**D1 — `host-decide`: authority moves, boundary vocabulary stays.**
`verified/host-decide` runs `validate-input!` → `portable/decide` →
`validate-result!` with no kernel selection, no crossing recording, and no
generated dispatch. `consistency.cljc` calls it for both consistency
operations on both platforms (CLJS drops its per-decision selection
normalization too). `verified/decide` remains op-complete for the offline
differential suites. *Alternative:* raw `portable/decide` without
validation — rejected: the certified vocabulary is cheap per request and
keeps error identity stable.

**D2 — Counts resume on the cover edge through an internal count page.**
`recursive-operator-count` loops an internal variant that takes the cover
boundary directly and returns `{:count :cover-edge :more?}` — skipping
outer bound validation, semantic-scope digests, recursive edge
construction, page-info rewrap, and spice-object externalization per page.
The public paged path is unchanged. *Alternative:* count-mode deep inside
`filtered-lookup-page` — deferred; the outer layer is where the measured
waste concentrates and the blast radius stays one function.

**D3 — Gate updates are part of the same change.**
`required-generated-authority-operations` drops `:consistency-plan`; the
consistency-boundary gate keeps its latency ceiling and decision-identity
assertions but models host authority (zero crossings) instead of
generated-crossing correspondence; `assurance_contract.clj`'s consistency
operation entry-points name the portable procedure with the generated
model as oracle. The spec deltas in `specs/` carry the requirement-level
changes.

## Risks / Trade-offs

- [Portable/generated divergence after the cutover] → the differential
  suites already compare them across input classes and keep failing CI on
  divergence; the mutation controls now exercise the *production* path
  directly (portable/decide), strengthening them.
- [A consumer of the counting-kernel accounting assumed consistency
  crossings] → the authority suite and its required-operations set are
  updated in the same commit; grep for other consumers of
  `:kernel-crossing` consistency counters before landing.
- [Count refactor changes truncation/`:limit` shape] → the fixture matrix
  covers bounded, exact, empty, and single-page counts against the paged
  implementation's outputs captured before the change.

## Migration Plan

Internal; independently revertible commits on a branch stacked on the
cleanup branch. Rollback = revert.

## Open Questions

None blocking.

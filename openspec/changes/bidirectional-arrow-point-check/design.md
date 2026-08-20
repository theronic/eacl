# Design: Bidirectional Arrow Point Check

## The two-layer arm

A two-layer arrow arm asks: does some intermediate `i` carry BOTH base
tuples `(i, via, resource)` and `(subject, target-relation, i)`? Writing
`S` for the resource's via-set (reverse index) and `T` for the subject's
holdings (forward index), the arm holds iff `S ∩ T ≠ ∅`. Both sides are
finite, strictly ordered, duplicate-free, complete adapter scans, and each
side supports an exact-bound membership probe (the scan strictly after
`candidate − 1` with limit one). The resource-side-only search enumerated
`S` and probed each element in `T`: correct, but O(|S|) — and `S` is the
resource's popularity, unbounded by the query.

## The interleaved decision

`BidirectionalArrowIntersection.dfy` models the decision as consuming one
candidate from each remaining side per round (`Decide`): probe the via
candidate in the holdings index; probe the holding candidate in the via
index; stop true at the first positive probe, stop false the moment EITHER
side exhausts with all its candidates probed negative (a fully consumed
side has been completely intersected with the other universe). Proofs:

- `StrategiesAgree` — enumerating either side alone decides the same
  answer (`ArmAnswer`, nonempty intersection).
- `DecideSound` / `DecideComplete` / `DecideEqualsArmAnswer` — the
  interleaved decision equals nonempty intersection; the completeness
  invariant is that every intersection witness survives into both suffixes
  because consumed heads were probed negative.
- `RoundsBoundedByShorterSide` — rounds ≤ min(|S|, |T|); each round costs
  at most two probes and each side realizes at most one value per round,
  so a huge fan-in on one side costs only what the small side costs.
- `TwoLayerArmIsIntersection` — inside the membership-probe abstraction
  (`MembershipProbeCheck.dfy`), a node whose successor carries the target
  two layers down is exactly a nonempty intersection of the node's
  successor set with the target's predecessor set, so the runtime
  refinement decides the same arm the DFS exploration would.

The implementation (`intersect-arm?` in `eacl.engine.stable-route`)
buffers each enumeration in physical chunks (one fetch realizes up to
`physical-chunk-size` candidates) while keeping probes per candidate, so
its cost is the model's bound plus at most one chunk per side. Round
order and both exhaustion exits follow `Decide` exactly.

## Which arms qualify

- `:arrow-relation` — always (the target IS a base relation).
- `:arrow-permission` — when every rule of the target node is `:relation`
  (statically read from the sealed plan's reverse index): the arm is a
  union of two-layer intersections, one per target relation rule whose
  subject type matches the request; ∃-commutation makes the union of
  intersections equal the DFS's per-intermediate union. A target with
  zero matching relation rules decides false with zero fetches.
- Anything else (recursive or mixed targets) keeps enumerate-and-descend.

## Adapter premises

The `CompleteEnumeration` and probe premises are the existing adapter
obligations — `:subject->resources`/`:resource->subjects` `:finite
:strict-order :unique :complete :inclusive-exclusive-bounds` and
`:direct-match?`'s `:iff-forward-scan-membership` /
`:iff-reverse-scan-membership` index agreement. No new adapter operation:
both enumerations and both probes are the two existing scan operations
through the routed fetch-fn seam (classification, retry, telemetry, and
budgets apply unchanged).

## Enforcement granularity

`fetch!` now runs the caller's cut-point before every adapter command.
The probe route previously checked once per DFS pop, so one pop could
issue up to `:max-commands` reads past an expired deadline or an observed
cancellation; per-command checks match the reducer's per-transition
granularity (its transitions issue at most one fetch each).

## Sealed-plan view isolation

Plan reuse premise: a sealed plan is a pure function of the schema
definitions visible at the snapshot, and schema definitions change only
through `write-schema!`, which advances the stamped generation in the same
transaction. Views that violate the premise's observability — `d/filter`
(hides definition datoms, keeps the stamp), `d/since`, `d/history`, and
unstamped databases (where only the basis can key a plan, and a `d/with`
value shares the next committed basis) — mint a per-call lifecycle, carry
no plan schema identity, and compile without inserting into the shared
FIFO. `eacl.datomic.raw-plan-isolation-test` reproduces both aliasing
directions against the pre-change code and pins the isolation.

## Measured impact (fresh JVMs, in-memory Datomic, Apple M3 Pro)

| scenario | main (04ddd17) | this change |
|---|---|---|
| `can?`, doc shared with 10,000 orgs, grant | 31.9–36.0 ms | 0.13–0.41 ms |
| `can?`, doc shared with 10,000 orgs, deny | 31.5–34.1 ms | 0.12–0.46 ms |
| `can?`, doc shared with 100,000 orgs | typed limit failure (`:max-values`) | 0.24–0.47 ms |
| membership/union/recursive-chain/pages/counts | — | unchanged within run noise |

## Alternatives considered

- Choosing one side by a cardinality estimate: no estimate is available
  without paying a scan; interleaving needs none and its bound is the
  min either way.
- Runtime `d/with` detection for plan keying: Datomic exposes no public
  discriminator for speculative values (`isFiltered`/`isHistory`/`sinceT`
  all report plain); restricting reuse to stamped generations is sound,
  matches the write-schema!-only invalidation doctrine, and keeps the
  unsupported case (definition datoms written outside `write-schema!`)
  identical to its committed-path status.
- Making recursive-arrow descent chunk-lazy: real (the reviewer measured
  eager materialization costs on grants provable early) but orthogonal;
  deferred to keep this change's certified surface minimal.

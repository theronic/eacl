# Proposal: Bidirectional Arrow Point Check

## Why

The membership-probe point check (`membership-probe-point-check`) bounded
`can?` by the resource's reachable intermediates instead of the subjects
holding the permission — but it searches the RESOURCE side only. A
two-layer arrow arm (`doc.view = org->view` with `org.view = member`, or
any arrow to a relation) was decided by materializing the resource's
entire via-set and probing every intermediate. Measured (2026-08-20,
in-memory Datomic, fresh JVMs): a document shared with 10,000 orgs cost
**31.9 ms** to grant and **31.5 ms** to deny for a subject who belongs to
one org, and at 100,000 orgs the check **could not answer at all** — the
materialized via-set trips `:max-values` (default 100,000) with a typed
`:eacl.recursive-traversal/limit-exceeded`, including grants provable in
four index reads. The subject's own holdings are almost always the small
side; the underlying indexes answer the same question from either end.

Separately, the process-stable raw-facade lifecycle introduced by
`membership-probe-point-check` let the process-global sealed-plan cache
alias views it must never alias: a `d/filter` view (hides definition
datoms but not the schema stamp) and a `d/with` speculative value (shares
the later committed basis on unstamped databases) key identically to the
plain database. Sealed plans embed relation eids and permission arms, so
either direction of reuse yields wrong authorization answers — reproduced
live in both directions by `eacl.datomic.raw-plan-isolation-test` before
this change.

## What Changes

- `eacl.engine.stable-route/probe-check-eids` decides each two-layer
  arrow arm — an `:arrow-relation` rule, or an `:arrow-permission` rule
  whose target node's rules are all `:relation` — by an interleaved
  bidirectional intersection: the resource's via-set and the subject's
  holdings are consumed in alternating rounds (enumeration buffered in
  physical chunks, one exact-bound probe per realized candidate on the
  opposite index), stopping at the first positive probe or as soon as
  EITHER side exhausts. Arm cost is bounded by the smaller side plus one
  chunk per side, never by the via fan-in. Arrows to recursive
  permissions keep the enumerate-and-descend route.
- New Dafny leaf `formal/stable-discovery/BidirectionalArrowIntersection.dfy`
  (13 obligations; gate pin 528 → 541): the arm answer is
  strategy-independent (`StrategiesAgree`), the interleaved decision equals
  nonempty intersection (`DecideSound`, `DecideComplete`,
  `DecideEqualsArmAnswer`), consumption is bounded by the smaller side
  (`RoundsBoundedByShorterSide`), and the two-layer pattern inside the
  membership-probe abstraction is exactly such an intersection
  (`TwoLayerArmIsIntersection`).
- Deadline/cancellation enforcement moves to per-adapter-command
  granularity: `fetch!` runs the cut-point before every command, so a
  single DFS pop can no longer issue up to `:max-commands` reads past an
  expired deadline (review finding, 2026-08-20).
- The probe's rule dispatch fails closed on an unrecognized sealed rule
  kind (`:eacl.plan/unknown-rule-kind`) instead of silently
  under-deriving, matching the reducer's reverse expansion.
- Raw-facade sealed-plan view isolation: `with-request-engine` shares the
  process-stable lifecycle and plan schema identity ONLY for ordinary
  views (current, as-of) of a stamped schema generation
  (`eacl.datomic.backend/ordinary-view?`, now public). Filtered, since,
  and history views, and every unstamped database (whose basis a `d/with`
  value aliases), mint a per-call lifecycle and compile plans per call
  without touching the shared FIFO (`eacl.engine.v8/stable-plan` skips
  insertion for request-local contexts with no plan identity, so dead
  keys cannot evict live stamped plans).

## Measured Impact

Fresh-JVM A/B, identical fixtures (in-memory Datomic, M3 Pro): see
`design.md` for the full table. Headline: 10k-fan-in checks 31.9/31.5 ms →
sub-millisecond; 100k-fan-in checks unanswerable → sub-millisecond;
wide-membership and recursive-chain checks unchanged within noise.

## Non-Goals

- Recursive-arrow descent stays eager (materializes the via-set before
  pushing states); making it chunk-lazy is a follow-up.
- Counts still exhaust the reducer (an order-insensitive count route
  still requires an independent denotation-equivalence proof).
- No order-ABI, cursor, cache-tier, or public-contract change: point
  checks return Booleans and sealed plans are unchanged.

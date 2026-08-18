# Proposal: Membership-Probe Point Check

## Why

The stable-discovery engine answered `can?` by running the reverse reducer
from the resource and stopping at the subject's first admission
(`eacl.engine.stable-route/check-eids`). That is correct, but its cost is
linear in the number of subjects that hold the permission: every subject
that sorts before the checked one is enumerated, and a *denied* check
enumerates all of them. Measured (2026-08-18, in-memory Datomic, one
account with 5,000 owners): denied `can?` **16.3 ms** (5,009 admissions,
10,009 transitions, 83 scans); allowed `can?` for the highest-eid owner
**12.9 ms**; on the live 110k-server demo (`eacl-datomic-solidjs`, warm
peer) a denied check spent ~65 % of its post-plan-fix time in the reverse
walk (13 scans, 25 transitions, ~110 µs raw). Any deployment with popular
resources (a document shared with an organisation, an account with
thousands of members) pays this on the hottest path in the API.

A point check does not need stable order — only a Boolean. Membership can
be decided by *probing* the subject with one exact-bound scan (limit one,
exclusive bound `subject-eid − 1`: the first value equals the subject iff
the tuple exists), enumerating only the intermediates a resource reaches
(its account, its teams, its vpc — typically a handful). This is the
classic ReBAC check (Zanzibar/SpiceDB "check" semantics) expressed over
EACL's sealed plan.

## What Changes

- `eacl.engine.stable-route/check-eids` becomes an iterative depth-first
  membership search over the sealed plan's reverse index:
  `:relation` rules for the subject type → one exact-bound probe;
  `:self-permission` → descend on the same entity; `:arrow-permission` →
  enumerate intermediates, descend; `:arrow-relation` → enumerate
  intermediates, probe each. Visited set on `[node eid]`; base tuples first,
  arrows second; explicit stack (no recursion depth limit).
- Same read seam and budgets as the reducer: probes and enumerations go
  through the routed `fetch-fn` (classification, retry, telemetry);
  `:max-admissions` bounds visited states, `:max-transitions` visits,
  `:max-commands` fetches, `:max-values` fetched values, `:max-stack` stack
  depth, all failing with the reducer's typed `:eacl.reducer/limit-exceeded`
  shape so the public `:eacl.recursive-traversal/limit-exceeded` mapping is
  unchanged; the cut-point hook runs at every visit; observer counters
  (`:derived-grants`, `:advanced-datoms`, `:queued-work`) are reported.
- The reverse-enumeration form is retained as `enumeration-check-eids` —
  the executable oracle.
- Public behaviour: identical Booleans; the exception-based early exit is
  gone (no `ex-info` per allowed check).

## Capabilities

### Modified Capabilities

- `stable-discovery-enumeration`: "Point checks and counts keep
  operation-appropriate plans" now requires the membership-probe route and
  bounds its cost by reachable intermediates, never by subject count.

## Evidence

- Differential (probe vs enumeration): 2,680 (subject, server) pairs on the
  5,000-owner fixture, 6,000 pairs on a 300-user/400-group sparse fixture,
  4,860 pairs on the live 110k-server database — 0 disagreements; frozen
  baseline point samples (`eacl.baseline.capture`) all reproduced.
- Cost: denied check on the popular resource 14.5 ms → 24 µs; allowed
  high-eid owner 14.1 ms → 11 µs; live demo denied check 67 → 34 µs raw,
  4,860-pair batch 133 → 44 µs per check; synthetic resource with 1,000,000
  direct subjects: deny and allow each cost exactly one probe.
- Tests: `eacl.engine.point-check-test` (baseline differential across six
  fixtures, O(intermediates) property, limits, cut-point, observer stats,
  nil ids).

## Non-Goals

- No change to `lookup-subjects`/`count-subjects` (they enumerate by
  contract), to cursors, or to caching identities.
- No forward/bidirectional check heuristics in this change (the resource
  side is enumerated; the subject is always probed).

## Formal obligation (discharged 2026-08-18)

`formal/stable-discovery/MembershipProbeCheck.dfy` proves, over the
StableReducer program model with leaf result nodes, that the probe answer
(some intermediate reachable through non-result states holds the target as
a direct successor) equals membership of the target in the reverse reachable
set (`ProbeAnswerEqualsReachability`) and hence equals the exhaustive
reverse-enumeration check's answer (`ProbeCheckEqualsEnumerationCheck`, via
`ReducerCompleteness`/`ExactCountComposition`). The leaf is in the
`verify-fast.sh` gate (528 obligations); `eacl.engine.point-check-test` is
the executable oracle differential registered in `execution-contract.edn`.

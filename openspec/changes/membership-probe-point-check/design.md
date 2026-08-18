# Design: Membership-Probe Point Check

## Approach

`eacl.engine.stable-route/check-eids` = `probe-check-eids`, an iterative
depth-first search over `(get-in plan [:indexes :reverse-rules])`:

```
stack ← [[root resource-eid]]; visited ← #{}
loop: pop [node eid]; count a visit; run cut-point!; skip if visited; mark
  1. for each :relation rule at node whose subject-type = requested type:
       probe (eid, relation) — fetch {:bound-eid (dec subject-eid) :limit 1};
       hit iff first value = subject-eid → return true
  2. successors in plan order:
       :self-permission   → push [target-node eid]
       :arrow-permission  → for I in intermediates(eid, via, intermediate-type): push [target-node I]
       :arrow-relation    → for I in intermediates(...): probe (I, target-relation) → true on hit
     push successors reversed (canonical order); enforce :max-stack
stack empty → false
```

Reads go through the routed `fetch-fn` (same descriptor shape as the
reducer's `resource->subjects` scans), so classification, retry, and
`:adapter-attempts` telemetry apply unchanged; intermediates are fetched in
`physical-chunk-size` chunks until a short chunk. Counters
`{:admissions :transitions :commands :fetched-values}` mirror the reducer's
and are reported to `eacl.engine.stable-reducer/*observer-stats*` under the
public names. Limit failures use the reducer's `:eacl.reducer/limit-exceeded`
shape with the budget key so `with-public-limit-errors` maps them.

## Correctness argument

Let G be the rule graph over states (node, entity). The reducer's reverse
run emits subject U iff there is a derivation from (root, R) to a base tuple
containing U — that is, iff a path exists in G from (root, R) to a state
whose direct rule holds a tuple with U (or an arrow-relation whose
intermediate holds one). Depth-first search with a visited set is complete
for reachability in finite graphs (a state pruned as visited was, or will
be, fully explored from its first visit); base tuples are decided exactly by
the ordered-scan obligations (`:strict-order :unique :inclusive-exclusive-
bounds`): the first value strictly after `U − 1` is `U` iff `U` is present.
The reducer's admission dedup keys (target node + entity) coincide with the
visited set, so the explored state space is the same set the reducer
admits; only the leaves are probed rather than enumerated.

## Alternatives rejected

- Forward search from the subject: linear in the subject's grants (a
  super-user with 110k servers).
- Bidirectional meet-in-the-middle: unnecessary once leaves are probes;
  keep as a future heuristic for resources with very high intermediate
  fan-in.
- A dedicated `:tuple-exists?` SPI op: not needed — the existing bounded
  scan is an exact probe; may be added later for adapters where a seek is
  costlier than a point lookup.

## Follow-ups

- Dafny leaf stating probe-search membership = reverse-denotation membership
  (bridge: `BidirectionalReachability.ReverseLookupEqualsForwardAuthorization`).
- The `:raw-can` op-count envelope (`recursive-op-count-envelopes.edn`)
  still holds (probes are scans); re-record if a fixture's probe count
  exceeds the recorded `:maximum-backend-scans`.

## Why

A consumer (0tx, on Datahike, 8.0.0-RC-2026-10-02) gates its ledgers by
subscription:

```
definition subscription {
  relation everyone: user:*
}
definition ledger {
  relation owner: user
  relation subscription: subscription
  permission view = owner
  permission subscribed = subscription->everyone
  permission open = view & subscribed
}
```

`lookup-resources` of `open` for a user who owns 5 of N subscribed ledgers
returned the right 5 ledgers, but its work grew with N, the ledgers of
everybody else: 68N - 2 fetched values (33,998 at 500 ledgers, 99,958 at
1,470), and `:eacl.recursive-traversal/limit-exceeded` from 1,471 ledgers
under the default limits. Counts behaved the same; checks were unaffected.
Writing the arrow inside the intersection, or renaming `subscribed` so that
it sorts after `view`, read 5 values at every N. A wildcard relation on the
ledger itself (`relation subscriber: user:*`, `view & subscriber`) grew with
N under every name (EACL-FORMAL-100).

A lookup over an intersection generates candidates from one operand, the
anchor, and decides the others per candidate. The anchor was the operand with
the smallest structural tuple. A wildcard relationship belongs to no subject,
so an anchor whose cover reads one enumerates every resource the wildcard
reaches, for every subject, and nothing in the tuple said so.

## What Changes

- Sealing marks every expression node whose candidate cover reads a wildcard
  branch, through relations, arrows, permission references, unions and
  exclusion left operands, as a least fixed point over the closure.
- An intersection takes such an operand as its anchor only when every operand
  is one. Structural costs decide among the rest, as before.
- The mark is sealed in the plan's costs and fingerprint where it holds.
  Plans that read no wildcard branch keep their costs, anchors, fingerprints
  and cursors. `compiler-plan-compatibility` gains `:intersection-anchor`.
- `WildcardSubjects.dfy` proves that a lookup whose cover declares no
  wildcard lists every granted subject from the cover and owes no `*` entry.

## Capabilities

### New Capabilities

### Modified Capabilities

- `wildcard-subjects`: resource lookups and counts of an intersection are
  bounded by the subject's own relationships when one operand's cover reads
  no wildcard branch.

## Impact

`eacl.operator.plan` (`wildcard-covers`, `node-costs`,
`select-intersection-anchor`), `eacl.engine.v8/compiler-plan-compatibility`,
`formal/dafny/WildcardSubjects.dfy`, the mutation registry and
EACL-FORMAL-100. An operator plan that reads a wildcard branch has a new
fingerprint: a cursor of the earlier release for such a plan is refused with
`:eacl.pagination/invalid-cursor`, and completed answers and cache snapshots
of the earlier release are not reused.

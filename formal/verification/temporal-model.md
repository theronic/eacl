# Temporal snapshot, cache, cursor, and continuation model

## Scope

`formal/tla/EaclTemporalDetailed.tla` is a typed transition model of the
history-sensitive authorization boundary. It deliberately models decisions
rather than the internals of Clojure or any database.

Its 25 transitions cover:

- managed and unmanaged graph and schema writes;
- clone, reset, restore, branch, and forced-head movement;
- retained and evicted snapshots in a causal history graph;
- selected, computation, and exact snapshots;
- complete dependency scopes, structural proofs, and proof lifting;
- authenticated cache lookup, tampering, provider failure, future and sibling
  entries, storage, invalidation generations, and telemetry compare-and-set;
- cursor authentication, operation/non-page-query/result scope, positional
  direction changes, expiry, current-graph recovery, exact-snapshot selection,
  divergence, and conflict;
- recursive continuation and page publication, retry, eviction, lookup, and
  deterministic replay.

The model checks these safety properties:

1. cache reuse is exact-snapshot or forward-only from a causal ancestor with
   matching authenticated source, query, scope, and proof;
2. authenticated query-scoped cursors either continue or recover on the
   selected graph in non-exact mode, continue on their retained graph in exact
   mode, or fail closed;
3. a page walk never combines results from different computation graphs;
4. cache and continuation publication or eviction races do not change the
   authorization decision.

## Bounds and proof obligations

The compact model remains as a fast regression abstraction and is checked to
length 12. The detailed pull-request configuration has three histories, two
proofs/scopes/queries/directions/operations/result kinds/sources, three times,
and is checked to length 6. The scheduled configuration expands to four
histories, three proofs/queries/operations/result kinds, four times, and is
checked to length 3.

The bounded configurations are bug-finding evidence, not a proof for arbitrary
trace length. Therefore `bin/formal apalache-invariant` separately checks:

- initiation: `Init => InductiveInvariant`;
- consecution: `InductiveInvariant /\ Next => InductiveInvariant'`;
- implication: `InductiveInvariant => Safety`.

`formal/dafny/TemporalSafety.dfy` additionally expresses cache, cursor,
continuation, and telemetry states as typed predicates and proves each
transition constructor preserves the corresponding safety predicate without a
finite trace bound. Its cursor machine distinguishes authenticated current
recovery from retained exact continuation and proves both select only the
request-scoped graph allowed by their mode.

## Reproduction

Run:

```text
bin/formal tla-typecheck
bin/formal apalache-check
bin/formal apalache-invariant
bin/formal apalache-scheduled
bin/formal verify
```

On 2026-08-02 all configurations and all three induction obligations passed.
The detailed model exposed 65 initiation clauses and 25 transition alternatives
to the inductive checker. No counterexample was produced, so this tranche adds
no entry to the counterexample ledger.

## Claim boundary

Passing this model establishes the stated properties of the transition system,
not that a backend implements its assumptions. Backend snapshot identity,
immutability, scans, direct matches, proof completeness, and exact selection
remain adapter certification obligations. Release status must remain
`not-verified` until those obligations and the runtime correspondence gates
are complete.

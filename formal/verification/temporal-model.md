# Temporal snapshot, cache, cursor, and continuation model

## Scope

`formal/tla/EaclTemporalDetailed.tla` is a typed transition model of the
history-sensitive authorization boundary. It deliberately models decisions
rather than the internals of Clojure or any database.

`formal/tla/EaclOperatorSafety.tla` is a separate abstract Phase A model for
operator execution. It covers atomic aligned-vector publication and
cancellation, logical progress despite physical overread, exact checkpoint
resume, eviction of entries from expired cache lifecycles, cache-hit lifecycle
matching, and authorization from negative evidence only
after the lower stratum is complete. It is not connected to a production
operator implementation.

The detailed temporal model's 25 transitions cover:

- managed and unmanaged graph and schema writes;
- clone, reset, restore, branch, and forced-head movement;
- retained and evicted snapshots in a causal history graph;
- selected, computation, and exact snapshots;
- complete dependency scopes, structural proofs, and proof lifting;
- authenticated cache lookup, tampering, abstract local-read/proof failure, future and sibling
  entries, storage, invalidation generations, and telemetry compare-and-set;
- cursor authentication, operation/non-page-query/result scope, positional
  direction changes, expiry, proof-equivalent current selection, exact-snapshot
  fallback, divergence, stale rejection, and freshness conflict;
- recursive continuation and page publication, retry, eviction, lookup, and
  deterministic replay.

The model checks these safety properties:

1. within this current-head temporal submodel, cache reuse is exact-snapshot or
   from a causal ancestor with matching authenticated source, query, scope, and
   proof; direction-agnostic reuse by a retained older selected snapshot is the
   separate scalar-frontier obligation proved by
   `EqualScalarProofAlsoPreservesAnOlderSelectedSnapshot` in
   `formal/dafny/ScalarFrontierCoherence.dfy`;
2. authenticated query-scoped cursors continue on current only when its
   dependency proof equals the cursor proof, otherwise continue on the
   retained exact graph when available and freshness-compatible, or fail
   closed;
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

The operator baseline is checked to length 8. Six registered mutation
configurations negate vector cancellation, permit partial vector publication,
advance cursors through physical overread, resume from the wrong checkpoint,
reuse a stale cache lifecycle, or publish partial negative authorization. All
six are killed; the combined Apalache registry contains ten killed temporal
mutants.

The bounded configurations are bug-finding evidence, not a proof for arbitrary
trace length. Therefore `bin/formal apalache-invariant` separately checks:

- initiation: `Init => InductiveInvariant`;
- consecution: `InductiveInvariant /\ Next => InductiveInvariant'`;
- implication: `InductiveInvariant => Safety`.

`formal/dafny/TemporalSafety.dfy` additionally expresses cache, cursor,
continuation, and telemetry states as typed predicates and proves each
transition constructor preserves the corresponding safety predicate without a
finite trace bound. Its cursor machine distinguishes proof-equal current
continuation from retained exact continuation and proves that a changed proof
never selects current.

The temporal model deliberately stops at graph selection. Item-level completed
pagination is specified separately in `formal/dafny/PageWindow.dfy`: an
authenticated logical boundary is the pair of its ordinal and external result
identity in the generated logical order. The completed-denotation path emits
the exact exclusive slice only when both values match; a mismatch is stale.
Production now implements that rule in the stable engine: `eacl.engine.stable-page/edge-page`
validates the `:stable-edge` ordinal and identity by checkpoint hit or governed
replay before slicing (the former `eacl.engine.v8/complete-logical-page` branch
was retired with the merge engine).
The Dafny function matches the host branch, but the correspondence remains a
cross-checked host-control refinement rather than a mechanized Clojure source
refinement. Demand continuations validate the same ordinal and identity in the
generated indexed continuation authority and never switch graphs.

## Reproduction

Run:

```text
bin/formal tla-typecheck
bin/formal apalache-check
bin/formal apalache-invariant
bin/formal apalache-mutation-control
bin/formal apalache-scheduled
bin/formal verify
```

The checked-in release evidence records the exact toolchain, bounds, and model
digests for each run. The detailed model exposes 25 transition alternatives to
the inductive checker. No unbounded claim is inferred from the finite checks;
the separate inductive run is required for that transition system.

## Claim boundary

Passing this model establishes the stated properties of the transition system,
not that a backend implements its assumptions. Backend snapshot identity,
immutability, scans, direct matches, proof completeness, and exact selection
remain adapter certification obligations. Release status must remain
`not-verified` until those obligations and the runtime correspondence gates
are complete.

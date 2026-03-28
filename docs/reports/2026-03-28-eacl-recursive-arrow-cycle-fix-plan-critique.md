# Review Of The Recursive Arrow Cycle Fix Plan

Plan under review: [2026-03-28-eacl-recursive-arrow-cycle-fix-plan.md](../plans/2026-03-28-eacl-recursive-arrow-cycle-fix-plan.md)

## Executive Judgment

The plan is directionally correct but still underspecifies the most important architectural distinction: compile-time permission-path construction and runtime permission evaluation are two different problems, and they should be solved with different mechanisms.

The draft also does not yet give the implementer a decision-complete design for `lookup-resources`, which is the hardest part of this bug. If left vague, the implementation is likely to fix `can?` and `lookup-subjects` while leaving `lookup-resources` in a partially recursive or performance-regressive state, which would fail the 0tx integration.

## Findings

### 1. The plan does not clearly forbid schema-level rejection of valid recursive permissions

The draft says recursive permissions are supported, but it does not explicitly prohibit adding schema validation that rejects recursive permission dependencies. That would solve infinite loops by invalidating the 0tx design requirement rather than by fixing the evaluator.

**Recommendation**

- Add an explicit invariant that `parent->read`-style recursive permissions are valid schema.
- State that only degenerate non-productive cycles should terminate at runtime as false/empty.

### 2. The plan under-specifies the forward lookup design

The current forward traversal bug is not just a missing guard. `lookup-resources` has no concrete resource anchor at the top level, so a naive resource-instance visited key is not enough. The draft does not tell the implementer how to redesign recursive forward lookup.

**Recommendation**

- Define a separate design for recursive forward lookup:
  - keep the current lazy merged traversal for acyclic permission graphs
  - detect recursive permission SCCs by `(resource-type, permission-name)`
  - evaluate recursive SCCs with a monotonic fixpoint over concrete resource eid sets
  - paginate the final sorted resource set with a simple cursor contract

### 3. The plan does not separate acyclic and recursive evaluation paths

The elegant design is not to rewrite the entire evaluator into one generic slow path. The foundational assumption should be:

- acyclic permission graphs use the fast indexed lazy traversal
- recursive permission graphs use a dedicated least-fixed-point path

Without that split, the implementation may accidentally regress non-recursive performance.

**Recommendation**

- Introduce an explicit “fast acyclic path / recursive SCC path” design section.
- Define the query node abstraction as `[resource-type permission-name]`.

### 4. The plan does not specify the runtime state model tightly enough

“Exact-state recursion guards” is correct but incomplete. The implementer needs the concrete keys.

**Recommendation**

- For `can?`, use `[subject-type subject-eid permission resource-type resource-eid]`.
- For reverse lookup, use `[resource-type resource-eid permission subject-type]`.
- For recursive forward lookup, use SCC fixpoint sets keyed by query node, not ad hoc visited-path stacks.

### 5. The plan does not define how real cycles should behave

“Prevent infinite recursion” is not enough. The evaluator needs a principled semantic contract.

**Recommendation**

- Define least-fixed-point semantics for positive recursive permissions.
- State that a cycle contributes results only if some productive non-cyclic base relation makes that result reachable.
- Real cycles with no productive base must return false/empty.

### 6. The phase ordering is still slightly off

The draft places runtime guard work before the forward-lookup redesign. In practice, `lookup-resources` needs its own architectural phase before the final runtime verification phase, otherwise the work can get trapped in iterative patching.

**Recommendation**

- Reorder phases to:
  1. red baseline
  2. compact symbolic path compilation
  3. `can?` and reverse lookup exact-state recursion
  4. recursive forward lookup via SCC fixpoint
  5. termination and regression coverage
  6. EACL push and 0tx verification

### 7. The plan should use the existing system as a semantic baseline where it still works

The current indexed engine already behaves correctly for non-recursive acyclic permission graphs. That should remain the baseline for the fast path.

**Recommendation**

- State explicitly that existing acyclic indexed tests must remain green unchanged.
- Restrict structural redesign to the recursive cases rather than broadening the change surface unnecessarily.

### 8. The plan should define the 0tx verification gate more precisely

The draft says “verify in 0tx,” but not what counts as fixed. That risks stopping after EACL tests pass.

**Recommendation**

- Require all of the following:
  - `authz/can?` returns true for Petrus on Oshana descendants
  - `authz/readable-account-uuids` includes Oshana descendants
  - Chrome no longer shows `500 Unauthorized` on `open-tab` or `set-account-content`
  - Inventory journal rows render

## Recommended Upgrades To The Plan

1. Make recursive permission support an explicit schema invariant.
2. Split evaluation into fast acyclic traversal and recursive SCC fixpoint traversal.
3. Define the exact runtime state keys for `can?` and reverse lookup.
4. Define least-fixed-point semantics for productive vs non-productive cycles.
5. Move the forward recursive lookup redesign into its own explicit phase before termination coverage.
6. Preserve current acyclic indexed behavior as the baseline fast path.
7. Tighten the 0tx success gate so the implementation does not stop too early.

## Conclusion

The plan becomes implementation-safe once it stops treating “cycle prevention” as one generic concern. The elegant foundational design is:

- symbolic compile-time permission graphs
- exact-state recursion guards for concrete-resource evaluation
- SCC fixpoint evaluation only for recursive forward queries
- unchanged fast indexed traversal for acyclic permission graphs

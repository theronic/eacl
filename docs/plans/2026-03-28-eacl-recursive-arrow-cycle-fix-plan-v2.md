# EACL Recursive Arrow Cycle Fix Plan v2

Date: 2026-03-28

Supersedes: [2026-03-28-eacl-recursive-arrow-cycle-fix-plan.md](./2026-03-28-eacl-recursive-arrow-cycle-fix-plan.md)

Addresses report: [2026-03-28-eacl-recursive-arrow-cycle-fix-plan-critique.md](../reports/2026-03-28-eacl-recursive-arrow-cycle-fix-plan-critique.md)

## Summary

Fix the indexed EACL evaluator so recursive arrow-permission patterns such as `parent->read` are valid, productive, and terminating.

The foundational design is:

- recursive permissions are valid schema
- compile-time permission paths are symbolic, not greedily unfolded
- concrete-resource evaluation uses exact-state recursion guards
- recursive forward lookup uses a least-fixed-point solver over recursive permission SCCs
- acyclic permission graphs keep the current fast indexed lazy traversal

Success means:

- valid recursive trees inherit permissions correctly
- real cycles terminate without infinite recursion
- existing acyclic indexed behavior remains green
- 0tx can consume the new `eacl/v7` SHA and the Oshana subtree access issue disappears

## Architectural Invariants

- `parent->read`-style recursive permissions are valid schema and must not be rejected by schema validation.
- Non-productive cycles are handled by runtime evaluation semantics, not by deleting recursive permission edges at compile time.
- Acyclic permission graphs keep the existing indexed lazy traversal path.
- Recursive permission graphs use an explicit SCC fixpoint path for forward resource enumeration.
- The semantic contract is least-fixed-point evaluation for positive permission unions:
  - productive base relations contribute reachable results
  - revisiting the same evaluation state without new productive input contributes nothing

## Phase 0. Lock In The Red Baseline

- Keep the minimal recursive regression fixture in `test/eacl/datomic/impl/indexed_test.clj`.
- Add or preserve red tests for:
  - compiled path shape for `reader + parent->read`
  - `can?` on root, child, and grandchild
  - `lookup-subjects` on child and grandchild
  - `lookup-resources` and `count-resources` on the same recursive fixture
  - real-cycle termination with false/empty results
- Re-run the targeted EACL test namespace and capture the failing baseline through nREPL.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 1. Rebuild Permission-Path Compilation As A Symbolic Graph

- Refactor `calc-permission-paths` in `src/eacl/datomic/impl/indexed.clj` so it no longer recursively expands permission-to-permission edges.
- Compile the following path forms only:
  - direct relation path
  - self-permission symbolic reference
  - arrow-to-relation path with resolved relation metadata
  - arrow-to-permission symbolic edge with resolved `via-relation-eid`, `target-type`, and `target-permission`
- Remove permission-eid visitation from compile-time path generation.
- Keep the cache keyed by Datomic database identity plus `[resource-type permission-name]`.
- Preserve current behavior for existing non-recursive indexed tests.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 2. Fix Concrete-Resource Evaluation For `can?` And Reverse Lookup

- Introduce an internal `can*` helper with exact-state recursion tracking keyed by:
  - `[subject-type subject-eid permission resource-type resource-eid]`
- Rework `can?` to use symbolic recursive permission edges and the exact-state guard.
- Add the symmetrical reverse recursion guard for `lookup-subjects` keyed by:
  - `[resource-type resource-eid permission subject-type]`
- Keep reverse traversal resource-anchored so valid parent-chain recursion succeeds while real cycles terminate.
- Do not change public APIs or schema DSL.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 3. Redesign Recursive Forward Lookup As SCC Fixpoint Evaluation

- Keep the current lazy merged forward traversal for acyclic permission graphs.
- Add a dependency-graph helper over query nodes:
  - node = `[resource-type permission-name]`
  - edge exists for self-permission and arrow-to-permission references
- Detect whether the requested query node belongs to a recursive SCC.
- For recursive SCC queries, evaluate all SCC member nodes for the current subject with a monotonic least-fixed-point solver over concrete resource eid sets:
  - direct relation paths seed initial resources
  - self-permission paths union dependent node results
  - arrow-to-relation paths add resources from direct intermediate relation matches
  - arrow-to-permission paths follow the `via` relation from the current dependent-node resource sets
- Iterate until no SCC member set changes.
- Sort the final resource eid set ascending and expose it through a simple cursor-compatible page contract for recursive queries.
- Apply the same recursive-query path to `count-resources`.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 4. Add Termination And Regression Coverage

- Convert the existing infinite-recursion reproduction test so it asserts runtime termination and finite false/empty results.
- Add explicit productive-recursion tests:
  - root `reader` grant implies child and grandchild `read`
  - mutual recursion plus one productive base relation eventually yields true/non-empty
- Add explicit non-productive cycle tests:
  - self cycle with no base
  - mutual cycle with no base
  - cyclic relationship data with no productive base
- Keep all acyclic indexed tests green unchanged to prove the fast path was preserved.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 5. Verify EACL End-To-End

- Run the targeted indexed and schema test namespaces through nREPL until green.
- Re-run the full EACL suite.
- Confirm the recursive fixture passes across:
  - `get-permission-paths`
  - `can?`
  - `lookup-subjects`
  - `lookup-resources`
  - `count-resources`
- Confirm real-cycle tests terminate and return finite false/empty results.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 6. Push EACL And Verify 0tx

- Commit the EACL fix on `eacl/v7` and push to `origin`.
- Capture the new git SHA and update both 0tx dependency pins:
  - `deps.edn`
  - `ztx-ledger/deps.edn`
- Start a fresh 0tx `:dev:nrepl`, restart the backend on `8091`, and confirm the new SHA is on the classpath.
- Verify the Oshana case in live 0tx:
  - `authz/can?` is true for Petrus on `Assets` and `Inventory`
  - `authz/readable-account-uuids` includes Oshana descendants
  - Chrome no longer shows `500 Unauthorized` on `open-tab`
  - Chrome no longer shows `500 Unauthorized` on `set-account-content`
  - Inventory journal rows render
- If all of those pass, the EACL fix alone resolved the 0tx access issue.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Assumptions

- No schema-level rejection of recursive permissions will be introduced.
- Recursive forward lookup may use a materialized sorted eid set for recursive SCC queries only; acyclic queries keep the existing lazy indexed path.
- No backwards-compatibility shim is required.

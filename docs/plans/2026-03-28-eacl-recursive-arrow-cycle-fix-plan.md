# EACL Recursive Arrow Cycle Fix Plan

Date: 2026-03-28

## Summary

Fix the indexed EACL evaluator so recursive arrow-permission patterns such as `parent->read` work on finite trees, while still preventing infinite recursion on real cycles in schema or data.

The current failure is not that recursive permissions are unsupported in principle. The current indexed implementation drops valid recursive arrow-permission branches too early during permission-path compilation, and it also uses over-broad runtime revisit guards.

Success means:

- recursive arrow-permission paths remain part of the compiled permission model
- `can?`, `lookup-resources`, `lookup-subjects`, and `count-resources` terminate on real cycles
- valid recursive tree traversal returns the expected inherited permissions
- 0tx can consume the fixed `eacl/v7` SHA and the Oshana subtree access issue disappears without extra 0tx-side auth projection work

## Current System

- `calc-permission-paths` in `src/eacl/datomic/impl/indexed.clj` recursively expands permission dependencies and uses permission-eid visitation as a cycle check.
- That check incorrectly treats `account/read -> parent->read` as a schema cycle, even though the recursion is only productive when the runtime resource graph moves to a different concrete account.
- `traverse-permission-path` uses a runtime visited key that is too coarse for recursive forward lookup because it keys by subject, permission, and resource type, but not by concrete resource instance.
- `can?` currently recurses without a dedicated exact-state guard.
- `traverse-permission-path-reverse` does not have a symmetrical exact-state guard for recursive reverse lookup.

## Design Direction

Treat recursive permissions as a normal part of the permission language.

- Keep permission-path compilation compact and symbolic rather than greedily expanding recursive permission edges.
- Move infinite-loop prevention to runtime evaluation state so valid recursive tree climbs are allowed and only exact state revisits are blocked.
- Keep the current public schema DSL and indexed engine surface.

## Phase 0. Lock In The Red Baseline

- Preserve the new minimal recursive regression tests in `test/eacl/datomic/impl/indexed_test.clj`.
- Add any missing red assertions for:
  - compiled path shape for `reader + parent->read`
  - `can?` on root, child, and grandchild
  - `lookup-subjects` on child and grandchild
  - `lookup-resources` and `count-resources` for the same recursive fixture
- Re-run the targeted namespace through nREPL and capture the failing baseline before implementation.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 1. Stop Treating Recursive Arrow Permissions As Compile-Time Cycles

- Refactor `calc-permission-paths` so arrow-to-permission edges remain symbolic instead of recursively inlining their target permission paths.
- Keep direct relation paths resolved eagerly.
- Keep arrow-to-relation metadata resolved eagerly because that remains finite and non-recursive.
- Remove the current permission-eid cycle rejection that collapses valid recursive arrow-permission branches.
- Preserve permission-path caching and invalidate it on schema writes as today.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 2. Add Exact Runtime Cycle Guards

- Introduce exact-state recursion guards for `can?`.
- Introduce exact-state recursion guards for forward and reverse traversal helpers.
- Ensure real cycles terminate without stack overflow or infinite lazy sequences.
- Ensure the guards do not block traversal when the same permission is revisited on a different concrete resource instance.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 3. Fix Forward And Reverse Recursive Lookup

- Make `lookup-subjects` walk recursive tree permissions correctly using the new runtime guard.
- Make `lookup-resources` and `count-resources` return descendant resources for recursive permissions.
- Keep pagination behavior stable enough for existing indexed tests.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 4. Add Cycle-Termination Coverage

- Convert the old infinite-recursion reproduction test to assert termination rather than accidental compile-time pruning.
- Add tests for real cyclic schemas or cyclic relationship data to prove the evaluator returns finite false/empty results instead of looping forever.
- Keep valid recursive-tree tests green alongside real-cycle termination tests.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 5. Verify In 0tx

- Commit and push the EACL change on `eacl/v7`.
- Capture the new SHA and update both 0tx dependency pins.
- Start a fresh 0tx dev JVM and restart the backend on `8091`.
- Verify the Oshana shared-root case in Chrome:
  - Inventory is visible
  - Inventory opens
  - Inventory journal rows render

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Assumptions

- Recursive arrow permissions are a supported language feature and must not be rejected as invalid schema.
- Infinite-cycle prevention belongs in runtime evaluation state.
- No backwards-compatibility shim is required.

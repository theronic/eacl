# Recursive Stable Discovery Cursor Plan

Date: 2026-03-28
Branch: `codex/recursive-stable-cursor-max-depth` from `eacl/v7`

## Summary

EACL currently supports recursive permissions in `can?` and `lookup-subjects`, but recursive forward `lookup-resources` and `count-resources` were repaired with a full-set recursive solver that regressed performance. The fix should be redesigned around runtime recursion with stable deterministic discovery order, exact query-scope deduplication, and a hard `:max-depth` guard that defaults to `50`.

The foundational redesign is:

- Keep permission-path compilation symbolic.
- Remove compile-time cycle handling for valid recursive permissions.
- Preserve the existing fast acyclic cursor-tree path for non-recursive lookups.
- Replace recursive forward full-set solving with a runtime frontier iterator that discovers concrete streams lazily, deduplicates exactly across recursive and non-recursive branches, and serializes the recursive execution frontier into the cursor.

No backward-compatibility or migration path is required.

## Public Contract

- `lookup-resources`, `count-resources`, `lookup-subjects`, and internal `count-subjects` gain optional `:max-depth`, default `50`.
- `can?` demand-map arity gains optional `:max-depth`; convenience arities use the default.
- For recursive forward lookup, ordering changes from global eid sort to stable deterministic discovery order for a fixed DB basis, query, and cursor.
- Recursive pagination guarantees exact deduplication and set correctness across pages.
- Exceeding `:max-depth` raises a typed runtime error instead of truncating results.

## Phase 1: Baseline And TDD Harness

- Capture baseline numbers on `eacl/v7` for:
  - existing non-recursive multipath benchmark in `test/eacl/bench/pagination_test.clj`
  - recursive depth-1000 `parent->read` benchmark for `lookup-resources limit 50`, `count-resources limit 50`, and full paginated traversal
- Add failing regression tests for:
  - recursive paginated lookup returns the same set as a large-limit lookup
  - recursive paginated lookup has no duplicates across pages
  - recursive lookup order is stable across repeated runs on the same DB basis
  - duplicates across direct and recursive paths are emitted once
  - duplicates across two recursive branches are emitted once
  - real data loop `a1 -> a2 -> a1` terminates and returns `{a1 a2}`
  - depth-51 chain fails at default `:max-depth 50`
  - explicit larger `:max-depth` succeeds
- Update tests that hard-code recursive eid ordering so they assert stable set equality instead.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 2: Max-Depth Plumbing And Error Contract

- Thread optional `:max-depth` through:
  - `eacl.core` demand-map calls
  - `eacl.datomic.core`
  - `eacl.datomic.impl.indexed`
- Default all public recursive calls to `50`.
- Add one typed runtime failure shape for depth exhaustion, used consistently by forward lookup, reverse lookup, counts, and `can?`.
- Keep symbolic permission-path caching and remove any remaining schema-time cycle semantics from query execution.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 3: Recursive Forward Lookup Redesign

- Delete the current recursive forward full-set solver and routing.
- Preserve the current lazy merged static path engine for acyclic queries.
- Add a recursive frontier executor for forward lookup:
  - Seed root streams in top-level permission-path order.
  - Use stable deterministic discovery order for recursive results.
  - Discover new concrete streams only when a concrete resource is emitted.
  - Deduplicate at query scope across all active streams.
  - Serialize recursive execution state into a richer cursor version.
- Use exact runtime state tracking:
  - emitted resource ids for dedupe across pages
  - expanded concrete recursive facts so each resource is expanded once
  - remaining depth budget
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 4: Recursive Count And Reverse Semantics

- Make `count-resources` for recursive queries reuse the recursive frontier engine rather than rebuilding a full closure.
- Keep `lookup-subjects` and `can?` on runtime exact-state recursion guards and add `:max-depth`.
- Extend internal `count-subjects` to the same recursive depth/error contract.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 5: Cursor Token And Docs

- Add a new recursive cursor version capable of carrying:
  - active frontier state
  - per-stream progress
  - dedupe state
  - remaining depth budget
- Update `eacl.datomic.core` cursor conversion/tokenization for the new cursor.
- Update `README.md` and any touched docs so recursive lookup promises:
  - stable deterministic order
  - exact deduplication
  - `:max-depth` support
  - set equality across pagination
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 6: Verification

- Run all EACL tests and make them green.
- Re-run before/after benchmarks and confirm:
  - non-recursive benchmark remains within current thresholds
  - recursive lookup/count are materially faster than the current `eacl/v7` baseline
- Keep pagination acceptance criteria focused on stable order and full-result set equality, not exact recursive eid order.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Assumptions

- Exact global eid ordering is no longer required for recursive forward lookup.
- A richer opaque cursor is acceptable if it avoids recomputation and closure materialization.
- No server-side cache, no closure index, and no eager full-resource-set sorting are allowed.

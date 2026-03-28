# Recursive Stable Discovery Cursor Plan V2

Date: 2026-03-28
Branch: `codex/recursive-stable-cursor-max-depth` from `eacl/v7`
Supersedes: [2026-03-28-recursive-stable-discovery-cursor-plan.md](./2026-03-28-recursive-stable-discovery-cursor-plan.md)
Informed by: [2026-03-28-recursive-stable-discovery-cursor-plan-critique.md](../reports/2026-03-28-recursive-stable-discovery-cursor-plan-critique.md)

## Summary

Implement recursive forward pagination as resumable execution over concrete recursive facts, not as full-set closure solving.

The elegant foundational design is:

- symbolic permission-path compilation with no schema-time rejection of valid recursive permissions
- fast static cursor-tree lookup retained for acyclic queries
- dedicated recursive forward executor for recursive `lookup-resources` and `count-resources`
- deterministic depth-first discovery order for recursive results
- exact deduplication across recursive and non-recursive branches using query-local runtime state persisted in the cursor
- hard runtime `:max-depth` guard with default `50`

This design deliberately forbids:

- full reachable-set materialization
- full recursive result sorting
- approximate dedupe
- server-side cursor/session state

No backward-compatibility or migration path is required.

## Public Contract

- `lookup-resources`, `count-resources`, and `lookup-subjects` query maps accept optional `:max-depth`, default `50`.
- `can?` demand-map arity accepts optional `:max-depth`; convenience arities use the default.
- Recursive forward lookup order is:
  - stable and deterministic for a fixed DB basis, query, and cursor
  - depth-first discovery order
  - not guaranteed to be global eid sort
- Recursive pagination guarantees:
  - exact deduplication across all pages
  - no duplicates within a page or across pages
  - concatenated pages equal one large-limit query in both membership and order
- Exceeding `:max-depth` throws typed `ex-info` with `{:eacl/error :max-depth-exceeded}`.

## Recursive Executor Design

- Use a dedicated recursive cursor `{:v 3 ...}` for recursive forward lookup/count.
- Runtime/cursor state is exact and explicit:
  - `:stack`
    - a depth-first stack of pending concrete expansion frames
  - `:emitted`
    - exact root resource eids already returned to the caller
  - `:expanded`
    - exact concrete recursive facts already expanded
  - `:depth-left`
    - remaining recursive depth budget
  - `:last`
    - last emitted resource eid, for diagnostics and cursor continuity
- A pending frame represents:
  - resource type / permission node
  - concrete anchor resource eid already proven for that permission node
  - next recursive child path index to visit
  - remaining depth for that frame
  - any per-stream relation cursor state needed to continue enumeration from the anchor
- Recursive order is defined precisely:
  - seed top-level root results from static permission paths in path order
  - emit each unseen root resource in that order
  - on emission, push recursive child expansion frames onto the stack in reverse child-path order so runtime pop order matches declared child-path order
  - within each child stream, Datomic relation index order is preserved
  - the resulting traversal is deterministic pre-order depth-first discovery
- Exact dedupe rules:
  - a root resource is returned at most once because `:emitted` is checked before emission
  - a concrete recursive fact is expanded at most once because `:expanded` is checked before expansion
  - duplicates across direct, recursive, and mixed paths are suppressed by exact membership checks, not adjacency

## TDD Phases

### 1. Baseline Capture Before Code Changes

- Record current `eacl/v7` baseline on this branch before any implementation edits:
  - existing non-recursive benchmark in `test/eacl/bench/pagination_test.clj`
  - recursive depth-1000 `parent->read` ad hoc benchmark for:
    - `lookup-resources limit 50`
    - `count-resources limit 50`
    - full multi-page traversal
- Record current relevant namespace test results so post-change regressions are attributable.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### 2. Lock The New Semantics With Red Tests

- Add failing tests in `test/eacl/datomic/impl/indexed_test.clj` for:
  - recursive paginated lookup equals large-limit lookup in both order and membership
  - recursive lookup has no duplicates across pages
  - recursive lookup order is stable across repeated runs on the same DB basis
  - duplicates across direct and recursive paths emit once
  - duplicates across two recursive branches emit once
  - `a1 -> a2 -> a1` terminates and returns `{a1 a2}`
  - non-productive pure cycle returns empty/false
  - default `:max-depth 50` fails on depth-51 chain
  - explicit larger `:max-depth` succeeds
- Update tests that currently assume recursive eid order so they instead assert stable full-order equality against the large-limit query result.
- Keep non-recursive pagination tests and benchmark thresholds intact.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### 3. Introduce Max-Depth Plumbing And Error Contract

- Thread optional `:max-depth` through:
  - `eacl.core`
  - `eacl.datomic.core`
  - `eacl.datomic.impl.indexed`
- Default public calls to `50`.
- Use one typed runtime error shape:
  - `(ex-info \"EACL max depth exceeded\" {:eacl/error :max-depth-exceeded ...})`
- Apply this contract to:
  - recursive `lookup-resources`
  - recursive `count-resources`
  - recursive `lookup-subjects`
  - `can?`
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### 4. Finalize Cursor V3 Before Executor Work

- Add recursive cursor v3 support to `eacl.datomic.core` tokenization/conversion.
- Keep v2 behavior for acyclic internal callers while allowing recursive queries to return v3.
- Cursor v3 must support exact round-tripping of:
  - stack frames
  - emitted eids
  - expanded facts
  - last emitted eid
  - remaining depth
- Use exact compact encoding for eid collections before tokenization.
  - Preferred: sorted vectors with delta encoding inside the cursor payload.
- Add token round-trip tests for v3 in `test/eacl/spice_test.clj`.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### 5. Remove Full-Set Recursive Forward Logic

- Delete recursive forward routing and helpers that:
  - detect recursion only to switch into full closure solving
  - compute full recursive result sets
  - sort full recursive closures before slicing
- Retain:
  - symbolic permission-path compilation
  - static lazy merged lookup for acyclic queries
  - runtime exact-state guards already used by `can?` and reverse lookup
- Narrow the redesign to recursive forward `lookup-resources` and `count-resources`.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### 6. Implement Recursive Forward Depth-First Executor

- Add a dedicated recursive forward executor in `src/eacl/datomic/impl/indexed.clj`.
- Execution model:
  - derive top-level root-result streams from static permission paths in path order
  - emit unseen root results in deterministic path/index order
  - when a root result is emitted, create concrete recursive expansion frames for recursive arrow-permission dependencies that originate from that permission node
  - process recursive expansion with a stack for depth-first discovery
  - each expansion frame lazily scans Datomic relation tuples from its concrete anchor using direct tuple-index primitives
  - discovered root resources are emitted only if unseen
  - discovered non-root recursive facts are expanded only if unexpanded
- Preserve exact query-scope dedupe between:
  - parallel non-recursive paths
  - non-recursive and recursive paths
  - multiple recursive paths
- Ensure pagination stops as soon as `limit` results are emitted; no extra closure walk is permitted.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### 7. Rebuild Recursive Count On The Same Executor

- Make recursive `count-resources` consume the same recursive executor state machine as lookup.
- Counting must:
  - respect `limit`
  - respect `cursor`
  - respect `:max-depth`
  - share exact dedupe semantics with lookup
- Do not reintroduce closure materialization or full traversal when counting only the next page.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### 8. Keep Reverse Lookup And CheckPermission Minimal

- Add `:max-depth` support to `can?` and `lookup-subjects`.
- Keep their existing exact-state recursion guards unless new red tests prove a broader redesign is needed.
- Apply the same typed depth-exceeded error contract.
- Avoid expanding the recursive forward redesign into reverse traversal unless necessary.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### 9. Benchmark, Verify, And Document

- Re-run the full relevant test suite until all tests are green.
- Re-run before/after benchmarks and confirm:
  - non-recursive multipath benchmark remains within current thresholds
  - recursive `lookup-resources limit 50` and `count-resources limit 50` are materially faster than baseline
  - recursive full multi-page traversal completes with no duplicates and correct full result set
- Add a committed recursive benchmark namespace that covers:
  - deep chain recursion
  - recursive overlap/duplicate case
  - paginated traversal cost
- Update `README.md` so recursive forward lookup now promises:
  - stable deterministic order
  - exact dedupe across pages
  - `:max-depth` support
  - unchanged result set, but changed recursive order semantics
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Acceptance Criteria

- All tests green.
- Existing non-recursive benchmark remains green.
- Recursive forward lookup/count no longer materialize the full reachable closure.
- Recursive first-page performance is materially faster than the current baseline.
- Recursive pagination returns the same result set as one large-limit query.
- Recursive pagination order is stable across repeated runs on the same DB basis.
- Duplicate resources are never emitted twice across pages.
- Real data loops terminate cleanly before or at `:max-depth`.

# Recursive Pagination Effective Grants Plan Critique

Date: 2026-05-17

Plan reviewed: [2026-05-17-recursive-pagination-effective-grants-plan.md](../plans/2026-05-17-recursive-pagination-effective-grants-plan.md)

## Executive Summary

The plan is directionally right: the current recursive `lookup-resources` fallback cannot satisfy EACL's cursor-pagination performance claim, and an ordered effective-grant index is the right class of solution if recursive paginated reads are meant to remain supported.

However, the plan is not yet implementation-complete. It identifies the right target architecture, but it leaves several correctness and integration seams underspecified:

- "recursive SCC only" grant materialization is too narrow for permissions that are acyclic roots but transitively depend on recursive permissions.
- Grant semantics are described at a high level, but the exact joins for arrow-to-permission, arrow-to-relation, self-permission, external dependencies, and multiple subject types need to be formalized before implementation.
- Write integration is not placed cleanly in the current namespace/API structure.
- Full synchronous grant rebuild is acceptable as a development checkpoint, but it should not be treated as the final design without stronger write-side guardrails.
- `read-relationships` prefix planning needs a multi-relation and multi-type strategy, not only "longest prefix" language.
- The plan's testing strategy is good, but it needs semantic oracle tests and mixed dependency tests, not only branch/stats tests.

The main recommendation is to revise the plan around a precise "grant-indexed permission set":

1. Compute recursive SCCs.
2. Compute all permission nodes that transitively depend on those SCCs.
3. Materialize effective grants for that recursive dependency closure, not only for SCC members.
4. Route any list query whose root permission is in that closure directly to grant indexes.
5. Keep purely acyclic permissions on the current raw relationship traversal.

That keeps the acyclic fast path while avoiding mixed raw/grant traversal gaps.

## Findings

### 1. Grant Scope Is Too Narrow

The plan says:

> Only materialize permission nodes that belong to recursive SCCs.

That is insufficient.

Consider:

```text
definition folder {
  relation parent: folder
  relation reader: user
  permission read = reader + parent->read
}

definition document {
  relation folder: folder
  relation viewer: user
  permission view = viewer + folder->read
}
```

`folder/read` is recursive. `document/view` is not part of the recursive SCC, but it transitively depends on one. A `lookup-resources` query for:

```clojure
{:subject user
 :permission :view
 :resource/type :document}
```

still needs a cursor-bounded plan. If only `folder/read` has grants, the planner must either:

- compose grant-indexed intermediate folders with raw `folder` relationships, which can still require scanning many intermediates, or
- materialize `document/view` grants as well.

For a factual cursor-performance guarantee, the cleaner design is to materialize every permission node that transitively depends on a recursive SCC. Then the root query can be a direct ordered grant scan.

Recommendation:

- Replace "recursive SCC nodes" with "recursive dependency closure".
- Define:

```clojure
recursive-sccs       := SCCs with size > 1 or self-edge
grant-indexed-nodes  := all permission nodes that can reach any recursive-scc node
raw-indexed-nodes    := permission nodes not in grant-indexed-nodes
```

- Planner rule:
  - if root node is in `grant-indexed-nodes`, use grant index directly
  - otherwise use raw acyclic traversal

This avoids having to prove every mixed raw/grant traversal remains cursor-bounded.

### 2. Grant Semantics Need A Formal Join Model

The plan describes grant semantics in prose, but the grant builder is the most fragile part of the design. It needs exact relational rules.

For a permission node `[resource-type permission-name]`, an effective grant should be:

```clojure
{:subject-type subject-type
 :subject-eid subject-eid
 :permission permission-name
 :resource-type resource-type
 :resource-eid resource-eid}
```

The builder needs formal rules for each permission arm.

Recommended rules:

#### Relation Arm

For permission arm:

```text
permission P = relation R
```

and relationship:

```text
subject S --R--> resource X
```

emit:

```text
grant(S, P, X)
```

#### Self-Permission Arm

For permission arm:

```text
permission P = permission Q
```

and existing grant:

```text
grant(S, Q, X)
```

emit:

```text
grant(S, P, X)
```

#### Arrow-To-Relation Arm

For permission arm:

```text
permission P on resource X = via->relation R on intermediate I
```

and relationships:

```text
I --via--> X
S --R--> I
```

emit:

```text
grant(S, P, X)
```

#### Arrow-To-Permission Arm

For permission arm:

```text
permission P on resource X = via->permission Q on intermediate I
```

and:

```text
I --via--> X
grant(S, Q, I)
```

emit:

```text
grant(S, P, X)
```

The plan should include these rules explicitly, with one test per rule and at least one test combining them in the same SCC.

### 3. External Dependencies Into Grant-Indexed Nodes Are Underspecified

A recursive SCC can depend on acyclic permissions outside the SCC.

Example:

```text
permission read = direct_read + parent->read
permission direct_read = reader
```

If `read` is grant-indexed, `direct_read` may be raw-indexed. The grant builder still needs to use `direct_read` as an input seed.

There are two clean designs:

1. Materialize the full dependency closure of grant-indexed nodes, including acyclic dependencies needed as inputs.
2. Materialize only recursive dependents, but allow the grant builder to evaluate raw acyclic dependencies as seed sources during rebuild.

The plan currently implies direct relation arms seed SCCs, but that does not cover external self-permission or arrow dependencies.

Recommendation:

- Choose a single rule:
  - either materialize all transitive dependencies needed to compute grant-indexed nodes, or
  - keep raw dependencies raw but define them as input sources to the grant builder
- Add tests for recursive permissions seeded by:
  - direct relation
  - self-permission to an acyclic permission
  - arrow-to-relation through an acyclic permission
  - arrow-to-permission through an acyclic permission

### 4. Planner Needs A Mixed Dependency Policy

The current acyclic traversal can already compose multiple lazy path sources. The plan should say whether that engine is allowed to call grant sources for intermediate target permissions.

If the revised recommendation is adopted, root permissions in the recursive dependency closure use grant indexes directly, so mixed traversal is mostly unnecessary for public list roots.

If not adopted, the planner must support a mixed path:

```text
raw root permission -> grant-indexed target permission -> raw terminal relation
```

That mixed path can still be constant but large per page because it may enumerate many intermediates before finding page results. It also complicates source stats and cursor proofs.

Recommendation:

- Avoid mixed root pagination by materializing the recursive dependency closure.
- Keep mixed source support only as an implementation detail for grant rebuilding, not public list reads.

### 5. Namespace And Write Integration Need A Concrete Placement

Current write surfaces are split:

- `eacl.datomic.schema/write-schema!` transacts schema changes and evicts permission-path cache.
- `eacl.datomic.core/spiceomic-write-relationships!` transacts relationship updates and returns a zed token.
- `eacl.datomic.impl/tx-update-relationship` only builds relationship tx data.

The plan says to rebuild grants after `write-schema!` and `write-relationships!`, but it does not say where orchestration belongs.

This matters because:

- `schema.clj` already requires `eacl.datomic.impl.indexed`.
- A new `grants` namespace may need schema parsing, permission path logic, and relationship tx helpers.
- Placing grant rebuild directly in `schema.clj` can deepen or introduce namespace cycles.
- Many tests call `schema/write-schema!` and raw `d/transact` directly, bypassing the public client.

Recommendation:

- Add a small orchestration namespace, for example:

```clojure
eacl.datomic.maintenance
```

- Move schema graph analysis that both `schema` and `grants` need into a dependency-light namespace, for example:

```clojure
eacl.datomic.permission_graph
```

- Keep transaction data builders pure where possible:

```clojure
(grants/rebuild-tx-data db-before db-after)
(grants/retract-all-tx-data db)
(grants/add-grants-tx-data db grants)
```

- Public write paths should call maintenance orchestration.
- Low-level tests should either use the public client write path or explicitly call the maintenance helper after raw txs.

### 6. Schema Versioning Needs A Decision

The plan proposes `:eacl.v8.grant/...` attributes because grant indexes are a schema expansion. But the current branch presents the public change as EACL v7.1, and earlier README text says v7.1 has no schema changes.

Because releases are not tagged and no migration path is required, there are two reasonable choices:

1. Keep the feature as EACL v7.1 and name internal attrs under `:eacl.v7.grant/...` or `:eacl.v7_1.grant/...`.
2. Declare the final feature EACL v8 because it introduces internal effective-grant schema.

The plan should not leave this open until implementation.

Recommendation:

- Decide version naming before schema edits.
- Prefer not to introduce `v8` identifiers unless the README and docs will also call the result EACL v8.
- If the branch remains v7.1, use v7.1-aligned internal attr names and update docs to remove "no schema changes".

### 7. Full Rebuild Is Fine As A Checkpoint, But Not As The End State

The plan says:

- rebuild all recursive effective grants on schema writes
- rebuild all recursive effective grants on relationship writes

That is acceptable as a development checkpoint, but it can become worse than the current read problem on write-heavy workloads. A single relationship write could produce a transaction retracting and re-adding a large grant set.

Recommendation:

- Split the plan into:
  - `Phase A`: correctness rebuild
  - `Phase B`: measured write benchmark
  - `Phase C`: incremental maintenance or explicit write-cost acceptance
- Add acceptance criteria for write-side behavior:
  - maximum grant tx size for benchmark fixtures
  - rebuild time for 100, 1k, 10k grants
  - relationship write latency with and without recursive grants
- Add guardrails:
  - configurable max grant rebuild tx size
  - explicit error if rebuild exceeds configured threshold
  - future chunking plan if needed

Without this, the plan could fix read pagination by making relationship writes unusable.

### 8. Grant Retraction Mechanics Need To Be Specified

The plan says "retract all existing grant datoms", but not how.

Since grants are cardinality-many tuple datoms on subject/resource entities, there are no standalone grant entities to retract. Retraction must scan both grant attrs and emit:

```clojure
[:db/retract e attr tuple]
```

Recommendation:

- Add helper functions:

```clojure
(defn grant-datoms [db])
(defn retract-grant-datoms-tx [db])
```

- Add tests proving:
  - all forward grant datoms retract
  - all reverse grant datoms retract
  - rebuild is idempotent
  - no duplicate grant datoms accumulate after repeated rebuilds

### 9. Direct `can?` Through Grants Needs A Fallback Policy

The plan prefers using grant direct lookup for recursive nodes. That is good, but only if the grant index is guaranteed current for every db passed to `impl.indexed/can?`.

Direct impl tests and internal callers can pass a db value that was produced by raw `d/transact` without running grant maintenance. In that case, a grant-backed `can?` could return false even though the raw evaluator would return true.

Recommendation:

- Define the invariant:
  - grant-backed recursive reads require a db after grant maintenance
- Enforce this by:
  - adding a grant metadata marker datom containing the schema basis and relationship basis used for the rebuild, or
  - routing only public client calls through grant-backed recursive reads, while low-level impl calls can throw if grants are missing
- Add tests for stale/missing grant detection.

Do not silently return false when required grant datoms are absent.

### 10. Count APIs Should Use Grants Too

The plan focuses on paginated list APIs, but once effective grants exist, recursive `count-resources` and `count-subjects` should not continue using the old full fixed-point path by accident.

Counts are not cursor-paginated, so they may still need to count all matching grants. But using grant attrs makes them consistent with recursive list semantics and avoids recomputing the graph.

Recommendation:

- Add recursive count behavior to the plan.
- For recursive nodes:
  - `count-resources` counts forward grant datoms for subject/permission/resource-type
  - `count-subjects` counts reverse grant datoms for resource/permission/subject-type
- Add tests proving counts match paginated enumeration totals.

### 11. Relationship Prefix Planning Needs Multi-Relation Handling

The plan says to include `relation-eid` in tuple prefixes when possible. That is correct, but relation names are not globally unique. A filter such as:

```clojure
{:resource/relation :viewer}
```

can correspond to multiple relation eids across resource types and subject types.

Current `find-relations` returns matching relation definitions, then filtering happens in memory. Prefix planning needs a strategy for multiple relation eids.

Options:

1. If the type fields identify exactly one relation eid, use one prefix scan.
2. If the filter identifies several relation eids, run multiple ordered prefix scans and merge datoms in at-rest order.
3. If merging several relation scans would be more expensive than a broader type prefix scan, choose the broader scan and post-filter.

Recommendation:

- Add this decision to `scan-plan`.
- Add tests for:
  - one relation eid
  - several relation eids
  - no type fields
  - cursor reuse across scan-plan changes

### 12. Relationship "At-Rest Order" Needs A Precise Definition

For relationships, there are two physical tuple attrs:

- forward relationship attr
- reverse relationship attr

The current implementation chooses a scan based on available filters. That means the same broad logical data set can be ordered differently if the query shape changes.

This is fine, but the docs and cursor validation should define it precisely:

> Relationship pages are returned in the ascending order of the selected Datomic relationship index for that query shape.

Recommendation:

- Add this wording to the plan and README/docs updates.
- Make the selected scan key part of the internal cursor edge, as current code already does.
- Keep query-shape validation strict so a cursor from one scan plan cannot be reused with another query shape.

### 13. Static Source Scans Are Useful But Brittle

The plan includes a static regression test for `page-eids-from-sorted`, `sort`, `take-last`, and pagination-time `count`.

This is useful as a guardrail, but it can become noisy:

- `sort` is legitimate in tests and small schema metadata paths.
- `count` is legitimate in count APIs and assertions.
- `drop-while` can be legitimate for exact boundary exclusion on lazy index scans.

Recommendation:

- Keep the static check only for `page-eids-from-sorted` if that helper is deleted.
- Prefer behavioral tests and stats assertions for the rest.
- If source scanning remains, scope it to production list pagination functions and document allowed exceptions.

### 14. Stats Bounds Need To Account For Merge And Fanout

The plan says source stats should be bounded, but does not define the bound.

For lazy merge over several paths, the engine may realize more than `page-size + 1` raw datoms because:

- duplicate results across paths are deduped after source reads
- path fanout can require probing empty sources
- arrow traversal may inspect intermediate entities

Recommendation:

- Define expected bounds as formulas, not exact counts:

```text
merged rows <= page-size + 1 + duplicate-overhead
source probes <= path-count * bounded-probe-factor
grant source rows <= page-size + 1 + duplicate-overhead
```

- For recursive grant direct scans, the bound can be strict: grant datoms touched should be approximately `page-size + 1`.
- For acyclic raw traversal, define a looser but explicit bound based on path count and fixture shape.

### 15. The Plan Should Add A Semantic Oracle

Grant indexes are derived state. The highest risk is not pagination mechanics; it is semantic drift from the existing evaluator.

Recommendation:

- Add a small oracle test suite:
  - generate small schemas/data graphs
  - compute expected permissions with the existing evaluator or a simple exhaustive evaluator
  - rebuild grants
  - assert grant membership equals oracle membership
  - assert paginated grant enumeration equals sorted oracle results

Keep it small and deterministic. This will catch mistakes in arrow direction, self-permission copying, duplicate handling, and cycles.

## Recommended Plan Changes

Update the plan with these concrete edits:

1. Replace "recursive SCC nodes" with "recursive dependency closure".
2. Add formal grant derivation rules for relation, self-permission, arrow-to-relation, and arrow-to-permission.
3. Define how grant-indexed nodes consume acyclic dependencies during rebuild.
4. Add a planner rule: root node in grant-indexed set uses direct grant scan.
5. Add low-level stale/missing grant detection.
6. Decide schema/version naming before implementation.
7. Introduce a maintenance/orchestration namespace to avoid write-path cycles.
8. Specify grant datom retraction mechanics.
9. Include recursive `count-resources` and `count-subjects`.
10. Add write-side benchmark acceptance criteria.
11. Expand `read-relationships` planning for multiple relation eids.
12. Define relationship at-rest order as selected-index order.
13. Replace broad static source scans with behavioral/stats assertions except for deleted helper names.
14. Add semantic oracle tests.
15. Add final docs wording that accurately states the read/write tradeoff of effective grants.

## Suggested Revised Execution Order

1. Add targeted failing tests for the current recursive fallback.
2. Add typed exception for recursive paginated list calls while grant index is absent.
3. Add permission graph analysis:
   - SCCs
   - recursive dependency closure
   - grant-indexed node set
4. Add formal grant derivation tests using tiny fixtures.
5. Add grant schema attrs with decided version naming.
6. Implement pure grant builder and oracle tests.
7. Implement grant datom tx builders and idempotent rebuild.
8. Add maintenance orchestration for public write paths.
9. Add stale/missing grant detection.
10. Route grant-indexed root list queries to grant scans.
11. Route recursive counts and `can?` consistently.
12. Add public encrypted-token tests over grant-backed pages.
13. Tighten relationship scan planning.
14. Add branch-aware benchmarks and write-side benchmarks.
15. Update README and docs.
16. Review the original prompt and verify the implementation satisfies it.

## Final Assessment

The plan has the right architectural instinct: recursive pagination needs an ordered read index or a hard refusal. The biggest correction is scope. Materializing only recursive SCC members is too narrow; EACL needs a grant-indexed closure policy so public list roots do not fall into mixed traversal that is hard to prove cursor-bounded.

With the recommendations above, the plan becomes implementable and testable without relying on timing anecdotes or hidden assumptions.

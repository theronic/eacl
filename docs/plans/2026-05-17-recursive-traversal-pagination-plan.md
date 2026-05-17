# Recursive Traversal Pagination Plan

Date: 2026-05-17

Branch: `codex/recursive-traversal-pagination`

Addresses report:

- [2026-05-17 recursive traversal pagination plan critique](../reports/2026-05-17-recursive-traversal-pagination-plan-critique.md)

Supersedes for this branch:

- [2026-05-17 recursive pagination effective grants plan](./2026-05-17-recursive-pagination-effective-grants-plan.md)
- [2026-05-17 recursive pagination effective grants critique](../reports/2026-05-17-recursive-pagination-effective-grants-plan-critique.md)

Builds on:

- [2026-05-15 reverse pagination plan](./2026-05-15-reverse-pagination-before-after-plan.md)
- [2026-05-15 reverse pagination critique](../reports/2026-05-15-reverse-pagination-plan-critique.md)
- [2026-03-28 recursive arrow cycle fix plan v2](./2026-03-28-eacl-recursive-arrow-cycle-fix-plan-v2.md)

## Goal

Implement recursive pagination without persisted effective grants, without a server-side cache, and without scanning every candidate resource with `can?`.

Recursive lookup results will be returned in deterministic traversal order, not global Datomic eid order. Acyclic lookups and `read-relationships` keep their existing Datomic at-rest index order.

The public list API remains:

- forward: `:first` with optional `:after`
- backward: `:last` with optional `:before`
- response metadata: `:page-info` with `:start-cursor`, `:end-cursor`, `:has-next-page?`, and `:has-previous-page?`

No backwards-compatibility or migration path is required. This branch is still in development.

## Confidence Statement

Under "no persisted grants/cache" and "no `can?` candidate scan", recursive lookup must use deterministic traversal-order worklist evaluation with request-local dedupe and prefix-replay cursors.

This strategy deliberately gives up constant-time deep recursive pagination. Deep recursive pages replay the traversal prefix from the root. If constant deep pagination becomes a requirement, the correct design is a materialized grant index or a traversal-state cache.

## Existing System Examined

### Public Pagination Wrapper

Files:

- `src/eacl/datomic/core.clj`
- `src/eacl/core.clj`
- `test/eacl/spice_test.clj`

Current behavior:

- `pagination-context` decodes opaque page tokens and pins follow-up pages to a stable Datomic basis.
- `list-query-shape` binds tokens to operation and query shape.
- `internal-page-query` replaces public encrypted `:after` or `:before` with the internal edge cursor.
- `encode-page-cursor` writes a top-level `:order` of `[:eid :asc]` for lookup calls and `[:relationship-datom :asc]` for relationship reads.
- `validate-page-token!` validates that order before the internal planner knows whether a lookup is acyclic eid-order or recursive traversal-order.

Redesign:

- Bump the page-token payload version to v4.
- Remove top-level `:order`.
- Keep `:op`, `:query-shape`, `:basis`, `:basis-t`, and `:edge`.
- Let each internal list planner validate `:edge`.
- Preserve stable basis behavior.

### Acyclic Indexed Lookup

Files:

- `src/eacl/datomic/impl/indexed.clj`
- `src/eacl/lazy_merge_sort.clj`
- `test/eacl/bench/pagination_test.clj`

Current behavior:

- Acyclic `lookup-resources` and `lookup-subjects` use low-level Datomic tuple scans.
- Individual path streams are ordered by result eid.
- Multiple streams are merged and deduped by `lazy-fold2-merge-dedupe-sorted-by`.
- The page realizer consumes only `page-size + 1`.

Redesign:

- Keep the acyclic engine intact.
- Rename the acyclic cursor edge kind from `:lookup` to `:lookup-eid`.
- Validate that acyclic lookup accepts only `:lookup-eid` bounds.
- Keep acyclic benchmark assertions that late pages do not drift.

### Recursive Lookup Today

Files:

- `src/eacl/datomic/impl/indexed.clj`
- `src/eacl/datomic/schema.clj`
- `src/eacl/datomic/core.clj`
- `test/eacl/datomic/impl/indexed_test.clj`

Behavior before this implementation:

- The branch contained an effective-grants experiment:
  - grant schema attributes
  - `effective-grants`
  - `rebuild-effective-grants!`
  - recursive lookup routed through grant tuple scans
- The prior committed recursive strategy used a fixed-point solver and sorted the final result set.

Redesign:

- Remove persisted effective-grant schema and write maintenance.
- Keep "grant" as a request-local derived fact.
- Route recursive dependency closures through traversal-order worklists.
- Preserve `can?` as a boolean checker; do not use it for list pagination.

### Relationship Reads

Files:

- `src/eacl/datomic/impl.clj`

Current behavior:

- `read-relationships` reads raw relationship datoms with `seek-datoms` and `rseek-datoms`.
- Relationship pages are in at-rest datom order.

Redesign:

- Keep relationship reads at-rest ordered.
- Keep relationship cursor kind separate from lookup cursors.

## Definitions

Relationship: stored authored edge.

```clojure
{:subject {:type :user :id alice}
 :relation :reader
 :resource {:type :folder :id root}}
```

Permission node:

```clojure
[resource-type permission-name]
```

Grant: request-local derived permission fact.

```clojure
{:subject-type :user
 :subject-eid 17592186045418
 :permission :read
 :resource-type :folder
 :resource-eid 17592186045520}
```

For `lookup-resources`, subject is fixed, so the traversal may store:

```clojure
{:node [:folder :read]
 :resource-eid 17592186045520}
```

For `lookup-subjects`, resource is fixed, so the traversal stores:

```clojure
{:node [:folder :read]
 :resource-eid 17592186045520
 :subject-type :user
 :subject-eid 17592186045418}
```

## Traversal Routing

Recursive routing is based on the permission dependency graph.

```clojure
recursive-sccs := permission SCCs with size > 1 or a self-edge
traversal-nodes := every permission node that can reach a recursive-scc node
eid-order-nodes := every permission node not in traversal-nodes
```

Planner rule:

- if the root permission node is in `traversal-nodes`, use traversal-order worklist lookup
- otherwise use the existing eid-order indexed lookup

This matters for acyclic roots that transitively depend on recursion:

```text
document/view = viewer + folder->read
folder/read = reader + parent->read
```

`document/view` must use traversal order because it depends on recursive `folder/read`.

## Traversal Order Specification

Traversal order is an engine contract, not an incidental implementation detail.

Rules:

- Rules have stable IDs and are sorted by ID before use.
- Seed streams are enqueued in sorted rule-ID order.
- Relationship datoms inside each stream are consumed in Datomic tuple order.
- Work is processed by FIFO queue.
- A stream work item advances exactly one datom.
- Advancing a stream enqueues the derived work item first, then requeues the stream if it has remaining datoms.
- A new grant is deduped before its consumers run.
- Grant consumers are enqueued in sorted rule-ID order.
- Ordinals are zero-based and assigned only to emitted root results.
- Changing any rule above requires incrementing `:engine-version`.

This order is stable for the same query, schema, relationship facts, Datomic basis, and engine version.

## Cursor Semantics

### Acyclic Lookup Cursor

```clojure
{:kind :lookup-eid
 :result-eid 17592186045520}
```

### Recursive Traversal Cursor

For `lookup-resources`:

```clojure
{:kind :recursive-traversal
 :engine-version 1
 :direction :forward
 :result-kind :resource
 :ordinal 42
 :result {:type :folder
          :eid 17592186045520}}
```

For `lookup-subjects`:

```clojure
{:kind :recursive-traversal
 :engine-version 1
 :direction :reverse
 :result-kind :subject
 :ordinal 42
 :result {:type :user
          :eid 17592186045418}}
```

Boundary rules:

- `:after` drops emitted results with ordinal `<= after.ordinal`.
- `:before` returns emitted results with ordinal `< before.ordinal`.
- If replay reaches the cursor ordinal and the result differs, throw a stale cursor error.
- If replay ends before reaching the cursor ordinal, throw a stale cursor error.
- `lookup-resources` cursors cannot be used with `lookup-subjects`, and vice versa.
- Relationship cursors cannot be used with lookup APIs.

Page flag table:

```text
Query shape              has-next-page?          has-previous-page?
:first n                 sentinel exists         false
:first n :after c        sentinel exists         true
:last n :before c        true                    ring had sentinel
:last n                  unsupported initially   unsupported initially
empty after final cursor  false                   true
empty before first cursor true                    false
```

Bare recursive `:last` is rejected with:

```clojure
{:eacl/error :eacl.pagination/unsupported-recursive-last
 :reason :requires-full-traversal}
```

## Page Token v4

Payload:

```clojure
{:v 4
 :op :lookup-resources
 :query-shape "..."
 :basis :stable
 :basis-t 1234
 :edge {:kind :recursive-traversal
        :engine-version 1
        :direction :forward
        :result-kind :resource
        :ordinal 42
        :result {:type :folder
                 :eid 17592186045520}}}
```

Old v3 tokens fail as unsupported. There is no migration path.

## Recursive Rule IR

Build a small rule IR from permission and relation definitions. `get-permission-paths` may remain for the acyclic engine, but recursive traversal uses explicit rule records with stable IDs.

Rule variants:

```clojure
{:id [...]
 :rule :relation
 :node [resource-type permission]
 :relation-eid relation-eid
 :subject-type subject-type
 :resource-type resource-type}
```

```clojure
{:id [...]
 :rule :self-permission
 :node [resource-type permission]
 :target-node [resource-type target-permission]}
```

```clojure
{:id [...]
 :rule :arrow-relation
 :node [resource-type permission]
 :via-relation-eid via-relation-eid
 :intermediate-type intermediate-type
 :target-relation-eid target-relation-eid
 :target-subject-type subject-type}
```

```clojure
{:id [...]
 :rule :arrow-permission
 :node [resource-type permission]
 :via-relation-eid via-relation-eid
 :intermediate-type intermediate-type
 :target-node [intermediate-type target-permission]}
```

## Forward `lookup-resources` Worklist

For fixed subject and root node:

- Seed direct relation streams that start from the subject.
- Seed arrow-to-relation streams that start from the subject and expand through intermediates.
- When a grant is new, emit it if it matches the root node.
- Consumers of grants derive self-permission and arrow-to-permission grants.

The traversal must not enumerate all resources of the target type.

## Reverse `lookup-subjects` Worklist

For fixed resource and subject type:

Relation arm:

```text
S --R--> X
=> grant(S, P, X)
```

Self-permission arm:

```text
grant(S, Q, X)
=> grant(S, P, X)
```

Arrow-to-relation arm:

```text
I --via--> X
S --R--> I
=> grant(S, P, X)
```

Arrow-to-permission arm:

```text
I --via--> X
grant(S, Q, I)
=> grant(S, P, X)
```

The reverse engine tracks full grants keyed by `[node resource-eid subject-type subject-eid]` and emits subjects only when the root resource and root permission are proven.

`:subject/relation` is not part of recursive traversal in this phase. If supplied, it should be rejected for recursive `lookup-subjects` with a typed unsupported-filter error rather than ignored.

## Guardrails

Request-local state is not a cache, but it can grow with traversal prefix size. Add internal limits:

- max derived grants per request
- max advanced datoms per request
- max queued work items

Typed error:

```clojure
{:eacl/error :eacl.recursive-traversal/limit-exceeded
 :limit-kind :derived-grants
 :limit 100000}
```

Defaults should be high enough for normal use and documented as safety limits.

## TDD Implementation Phases

### Phase 1: Cursor Token v4 And Cursor Kind Separation

Tests first:

- Public page-token round trip preserves arbitrary `:edge` maps without `:order`.
- Old v3 tokens are rejected.
- Acyclic lookup emits `:lookup-eid` cursors.
- Wrong cursor kinds are rejected by lookup and relationship APIs.

Implementation:

- Bump page-token payload version to 4.
- Remove `:order` from token payloads and validation.
- Rename acyclic lookup cursor kind to `:lookup-eid`.
- Add internal cursor-kind validation.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 2: Recursive Routing And Rule IR

Tests first:

- Recursive SCCs are detected.
- Permission roots that transitively depend on recursive SCCs are traversal nodes.
- Purely acyclic roots remain eid-order nodes.
- Relation, self-permission, arrow-to-relation, and arrow-to-permission rules compile to stable IDs.

Implementation:

- Add traversal-node detection.
- Add recursive rule IR compiler.
- Keep existing acyclic path code intact.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 3: Forward Recursive Worklist

Tests first:

- Recursive chain emits root, child, grandchild in traversal order.
- Traversal order differs from eid order in a deliberate fixture.
- Diamond paths emit each resource once.
- Cycles terminate.
- Recursive lookup does not call `can?` per candidate.
- `:first/:after` returns consecutive traversal windows.
- `:last/:before` returns the previous traversal window.
- Empty boundary pages match the page flag table.
- Bare recursive `:last` throws the typed unsupported error.

Implementation:

- Add request-local forward worklist evaluator.
- Add recursive page replay for forward and backward windows.
- Add guardrail counters.
- Route recursive `lookup-resources` to traversal.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 4: Reverse Recursive Worklist

Tests first:

- Recursive `lookup-subjects` works for direct relation, self-permission, arrow-to-relation, and arrow-to-permission.
- Duplicate subjects from diamond paths are emitted once.
- Cycles terminate.
- Pagination uses recursive traversal cursors and page flags correctly.
- `:subject/relation` on recursive lookup-subjects throws a typed unsupported-filter error.

Implementation:

- Add reverse worklist evaluator.
- Track full grants by node, resource, subject type, and subject eid.
- Route recursive `lookup-subjects` to traversal.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 5: Remove Persisted Effective Grants

Tests first:

- Schema no longer contains `:eacl.v7.grant/*` or `:eacl.grant/indexed-node`.
- Schema writes do not rebuild grants.
- Relationship writes do not rebuild grants.
- Recursive pagination still passes after grant attrs are gone.

Implementation:

- Remove grant schema attrs.
- Remove effective-grant functions and write hooks.
- Remove tests that assert persisted grant datoms.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 6: Counts And Documentation

Tests first:

- Recursive `count-resources` exhausts traversal and returns exact count.
- Internal recursive `count-subjects` exhausts traversal and returns exact count.
- Count APIs reject pagination keys.

Implementation:

- Route recursive counts through traversal exhaustion.
- Update README and docs:
  - acyclic lookup uses at-rest order
  - recursive lookup uses deterministic traversal order
  - relationship reads use relationship index order
  - recursive deep pages replay prefixes
  - no persisted grants/cache are used
  - bare recursive `:last` is unsupported initially

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 7: Benchmarks And Verification

Tests first:

- Sparse universe benchmark proves no candidate `can?` checks.
- Deep chain benchmark proves work grows with traversal prefix rather than total candidate universe.
- Diamond benchmark proves dedupe prevents duplicate output.
- Existing acyclic benchmark still asserts stable late-page latency.

Implementation:

- Add test-only stats for advanced datoms, derived grants, deduped grants, emitted results, and candidate checks.
- Keep recursive benchmark expectations separate from acyclic constant-page expectations.
- Run focused nREPL suites and `git diff --check`.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

### Phase 8: Appendix Review

Review the prompt in the appendix and verify:

- The upgraded plan addresses every report finding.
- The implementation follows TDD.
- Phases are correctly ordered.
- The plan includes the required continuation task after each phase.
- Recursive lookup moved from global eid order to traversal order.
- Recursive lookup avoids persisted grants/cache.
- Recursive lookup avoids candidate scans through `can?`.

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Appendix: Original Prompt

```text
Upgrade the plan based on the report to address *all* issues. The plan should follow TDD.

Ensure phases are correctly ordered. After each phase of the plan, add the task:

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

Then implement the upgraded plan.
```

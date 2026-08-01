# Recursive Traversal Pagination Plan Critique

Date: 2026-05-17

Plan reviewed: [2026-05-17-recursive-traversal-pagination-plan.md](../plans/2026-05-17-recursive-traversal-pagination-plan.md)

## Executive Summary

The plan makes the right strategic pivot. Once recursive lookup is allowed to return deterministic traversal order instead of global Datomic eid order, EACL no longer needs persisted effective grants to make recursive pagination possible. A request-local worklist evaluator can avoid both bad alternatives:

- scanning every candidate resource and calling `can?`
- asking Datomic Datalog to eagerly materialize recursive child-rule result sets

The plan is directionally sound, but it is not yet implementation-complete. The main risks are all about making "deterministic traversal order" precise enough to be a public cursor contract.

The plan should be revised before implementation to:

1. Define traversal order as an exact engine specification, not just "FIFO-ish".
2. Route every permission that transitively depends on recursion to the traversal engine, not only permission nodes that are themselves cyclic.
3. Formalize reverse `lookup-subjects` rule evaluation before implementation.
4. Make recursive cursor semantics, page flags, and stale-boundary behavior exact.
5. Add execution guardrails so request-local dedupe cannot turn a malformed or huge graph into unbounded memory use.
6. Decide the page-token v4 payload shape instead of leaving `:order` removal optional.
7. Strengthen benchmarks to prove "reachable-prefix work" rather than "candidate-universe work".

## Findings

### 1. Traversal Order Is Not Yet A Deterministic Contract

The plan says recursive lookup results use deterministic traversal order, but it does not fully define that order.

The following choices affect result order:

- permission arm order
- relation definition order when a relation name accepts multiple subject types
- seed stream order
- FIFO queue insertion order
- whether a stream requeues before or after derived grants
- whether grant consumers are enqueued before or after sibling streams
- whether duplicate grants are discarded before or after consumers are enqueued

If any of these remain incidental, page cursors become brittle. A cursor with `:ordinal 42` only works if ordinal 42 is stable across process restarts, Datomic peer cache differences, Clojure map iteration differences, and harmless refactors.

Recommendation:

- Add a "Traversal Order Specification" section to the plan.
- Define all ordering inputs explicitly:
  - compiled rules sorted by a stable key
  - seed streams sorted by compiled rule key
  - relationship datoms consumed in Datomic tuple order for that stream
  - FIFO queue processing
  - stream work advances exactly one datom, then derived grant work is enqueued, then the stream is requeued if non-empty
  - grant consumers are enqueued sorted by compiled consumer key
- Use zero-based ordinals and state that ordinals are assigned only to emitted root results, not to internal grants.
- Add golden tests where the exact traversal order is asserted.
- Treat any change to this ordering as an `:engine-version` bump.

### 2. Routing Must Use Recursive Dependency Closure, Not Only Cyclic Roots

The plan says recursive lookup should use the traversal engine, but it does not restate the important closure rule from the effective-grants critique.

Example:

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

`folder/read` is recursive. `document/view` is not cyclic, but it transitively depends on a recursive permission. If `document/view` stays on the acyclic eid-order lazy merge path, the implementation will either:

- accidentally materialize intermediate recursive results, or
- compose incompatible order models, or
- miss results.

Recommendation:

- Define:

```clojure
recursive-sccs := permission SCCs with size > 1 or a self-edge
traversal-nodes := every permission node that can reach a recursive-scc node
eid-order-nodes := every permission node not in traversal-nodes
```

- Route `lookup-resources`, `lookup-subjects`, and counts through the traversal engine when the root permission node is in `traversal-nodes`.
- Keep purely acyclic roots on the current eid-order indexed engine.
- Add a mixed dependency test for `document/view = viewer + folder->read`.

### 3. Reverse `lookup-subjects` Needs Formal Rule Semantics

The plan correctly says reverse lookup is not forward traversal with output flipped, but it stops before giving implementation-grade rules.

For `lookup-subjects`, the resource is fixed and the subject varies. The engine must walk permission definitions backward without scanning all subjects.

Recommended reverse rules:

#### Relation Arm

For:

```text
permission P on X = relation R
```

Use the reverse relationship index:

```text
S --R--> X
=> grant(S, P, X)
```

Implementation source:

```clojure
(resource->subjects db resource-type resource-eid relation-eid subject-type opts)
```

#### Self-Permission Arm

For:

```text
permission P = permission Q
```

Subjects with `Q` on the same resource also have `P`:

```text
grant(S, Q, X)
=> grant(S, P, X)
```

The reverse engine should enqueue a lookup for subjects of `Q` on `X`.

#### Arrow-To-Relation Arm

For:

```text
permission P on X = via->relation R on I
```

Walk from resource `X` back to intermediates `I`, then from each intermediate to subjects:

```text
I --via--> X
S --R--> I
=> grant(S, P, X)
```

#### Arrow-To-Permission Arm

For:

```text
permission P on X = via->permission Q on I
```

Walk from resource `X` back to intermediates `I`, then recursively find subjects with `Q` on each `I`:

```text
I --via--> X
grant(S, Q, I)
=> grant(S, P, X)
```

Recommendation:

- Add these rules to the plan.
- Add one test per rule.
- Add a combined recursive arrow test where reverse traversal has multiple parent paths and duplicate subjects.
- Include `:subject/type` filtering in the rule output checks.
- Decide whether `:subject/relation` is supported for recursive `lookup-subjects`; the public protocol mentions it, but the traversal plan does not.

### 4. Worklist Dedupe Needs Memory And Termination Guardrails

Request-local `seen-grants` and `emitted-root` are not a cache, but they can still grow with traversal prefix size.

That is acceptable, but the plan should not leave it unbounded. Dense graphs, accidental broad schemas, or hostile data can derive many grants before one page fills.

Recommendation:

- Add configurable internal limits:
  - max derived grants per request
  - max advanced datoms per request
  - max queued work items
  - max traversal depth, if depth is tracked
- Throw a typed exception when a guardrail is exceeded, for example:

```clojure
{:eacl/error :eacl.recursive-traversal/limit-exceeded
 :limit-kind :derived-grants
 :limit 100000}
```

- Keep defaults high enough for normal use and document that these are safety limits, not pagination limits.
- Add tests proving cycles terminate through dedupe and pathological broad traversal fails with a typed error rather than exhausting memory.

### 5. Cursor Boundary Semantics Need Exact Edge Cases

The cursor design is mostly right, but several edge cases are not specified.

Questions the implementation must answer:

- Is `:ordinal` zero-based or one-based?
- What happens if `:after` points to the final emitted result?
- What happens if `:before` points to the first emitted result?
- What happens if replay reaches the cursor ordinal but the result differs?
- What happens if replay ends before reaching the cursor ordinal?
- Can a `lookup-resources` recursive cursor be used with `lookup-subjects` if query shape somehow matches?
- Should `:direction` in the recursive cursor mean operation direction or derivation direction?

Recommendation:

- Define cursor semantics as:
  - ordinals are zero-based emitted-result positions
  - `:after` drops results with ordinal `<= after.ordinal`
  - `:before` returns results with ordinal `< before.ordinal`
  - if replay reaches the ordinal and result differs, throw stale cursor
  - if replay ends before the ordinal, throw stale cursor
  - cursor edge includes `:op`, or internal validation must compare operation explicitly
  - cursor edge includes `:result-kind :resource` or `:result-kind :subject`
- Add empty-page boundary tests:
  - forward after last returns empty, previous true, next false
  - backward before first returns empty, next true, previous false
- Add wrong-cursor-kind tests for every list API.

### 6. Page Flag Semantics Need A Recursive-Specific Table

The plan gives general flag behavior, but recursive replay deserves an explicit truth table because backward pages are not produced by a reverse index scan.

Recommendation:

Add this table:

```text
Query shape                    has-next-page?          has-previous-page?
:first n                       sentinel exists         false
:first n :after c              sentinel exists         true
:last n :before c              true                    ring had sentinel
:last n                        unsupported initially   unsupported initially
empty after final cursor        false                   true
empty before first cursor       true                    false
```

Add tests for every row.

### 7. Bare Recursive `:last` Is A Product Decision, Not Just An Implementation Detail

The plan rejects bare recursive `:last` because it requires exhausting traversal. That is reasonable, but it means the public API is not uniformly bidirectional for recursive lists.

Recommendation:

- Keep the rejection if the priority is page-bounded behavior.
- Make the error typed and documented:

```clojure
{:eacl/error :eacl.pagination/unsupported-recursive-last
 :reason :requires-full-traversal}
```

- Add README wording that says recursive previous-page navigation is supported via `:last/:before`, while "last page from the end" is intentionally unsupported without a boundary.
- If API uniformity is more important than bounded work, support bare `:last` by deliberately exhausting traversal and clearly document that cost. Do not leave this ambiguous.

### 8. Public Token v4 Should Be Decided, Not Optional

The plan says to remove or de-emphasize top-level `:order`. That is too loose for implementation.

Current code validates `:order` in `validate-page-token!` before internal planners see the cursor. That will reject recursive traversal cursors unless it changes.

Recommendation:

- Bump `page-token-version` to 4.
- Remove top-level `:order` from lookup tokens.
- Keep top-level `:op`, `:query-shape`, `:basis`, `:basis-t`, and `:edge`.
- Put cursor kind/version inside `:edge`.
- Let each internal operation validate `:edge`.
- Update token tests to assert the new payload exactly.
- Because there is no migration path, old v3 tokens should fail with "unsupported page token version."

### 9. Rule Compiler Should Not Depend Too Much On Nested Path Maps

The plan says to use `get-permission-paths` as the first input. That is pragmatic, but path maps were designed for the current traversal implementation, not as a durable compiler IR.

Risks:

- path maps mix resolution and execution concerns
- reverse traversal needs inverse rule indexes that path maps do not directly provide
- recursion detection and traversal routing need a permission graph independent of concrete query anchors
- relation-name expansion for multiple subject types must be stable and explicit

Recommendation:

- Introduce a small permission-rule IR built from permission and relation definitions.
- `get-permission-paths` can remain for acyclic lookup compatibility, but recursive traversal should compile to the new IR.
- Define stable rule IDs, for example:

```clojure
{:id [:arrow-permission resource-type permission source-relation-name intermediate-type target-permission via-relation-eid]
 :node [resource-type permission]
 :rule :arrow-permission
 ...}
```

- Sort rule IDs to define traversal order.
- Add tests at the IR level before traversal tests.

### 10. Phase Ordering Should Avoid A Long Broken Intermediate State

Phase 3 removes persisted effective grants before the new traversal implementation is in place, while current recursive routing still uses grant-indexed paths on this branch.

That will create a large red zone where recursive tests fail for reasons unrelated to the current TDD phase.

Recommendation:

- Reorder implementation:
  1. Add cursor-kind separation.
  2. Add recursive routing detection for `traversal-nodes`.
  3. Implement forward traversal behind a private helper.
  4. Route recursive `lookup-resources` to traversal.
  5. Implement backward replay.
  6. Implement `lookup-subjects`.
  7. Remove effective-grant schema and write maintenance after traversal tests pass, or remove them in the same commit that switches routing.
- If the branch needs to be green between phases, temporarily leave effective-grant code unused until cleanup.

### 11. Benchmarks Should Prove The New Performance Claim Precisely

The plan says benchmarks should prove work grows with traversal prefix, not candidate universe size. That is correct but should be made concrete.

Recommendation:

Add three benchmark fixtures:

1. Sparse universe:
   - many resources of the target type
   - tiny reachable recursive closure
   - proves no candidate scan
2. Deep chain:
   - one long recursive path
   - proves page N replay work grows with ordinal depth
3. Diamond graph:
   - many duplicate derivation paths
   - proves hash dedupe controls output duplication

Assertions should inspect stats, not only elapsed time:

```clojure
{:candidate-can-checks 0
 :advanced-stream-datoms bounded-by-reachable-prefix
 :derived-grants bounded-by-reachable-prefix
 :emitted-results page-size-plus-sentinel}
```

Do not keep the old "late pages should not slow down" assertion for recursive traversal. Prefix replay means late recursive pages are expected to do more work. Keep that assertion only for acyclic eid-order pagination.

### 12. Counts Need Public API Clarification

The plan mentions both `count-resources` and `count-subjects`, but the public `IAuthorization` protocol currently exposes `count-resources`, not `count-subjects`.

Recommendation:

- Clarify whether `count-subjects` is an internal helper or should become public.
- If it remains internal, do not include it in public API documentation.
- For recursive counts, explicitly state:
  - exact count exhausts traversal
  - no candidate `can?` scan is used
  - request-local dedupe is used
  - count may be slower than page reads

### 13. Documentation Must Remove The "Always At-Rest Order" Claim

The current README on this branch says results are always returned in Datomic at-rest eid order. That will become false.

Recommendation:

- Update docs to distinguish:
  - acyclic lookup: Datomic at-rest eid order
  - recursive lookup: deterministic traversal order
  - relationship reads: Datomic relationship index order
- Explain why recursive traversal order exists:
  - avoids candidate scans
  - avoids Datomic recursive Datalog eager materialization
  - avoids persisted grants/cache for now
- Show one recursive pagination example where output order follows discovery, not eid.

## Recommended Plan Additions

The plan should add these sections before implementation:

1. **Traversal Order Specification**
   - exact queue, stream, grant, rule, and consumer ordering
2. **Traversal Routing**
   - recursive SCC detection and transitive dependency closure
3. **Recursive Rule IR**
   - stable rule IDs and forward/reverse indexes
4. **Reverse Lookup Formal Rules**
   - relation, self-permission, arrow-to-relation, arrow-to-permission
5. **Cursor And Page Flag Truth Table**
   - all forward/backward and empty-boundary cases
6. **Guardrails**
   - max derived grants, datoms, queue size, typed errors
7. **Benchmark Semantics**
   - no candidate checks, reachable-prefix work, separate acyclic constant-page tests

## Conclusion

The plan is worth pursuing. The central strategy is correct under the operator's constraints: deterministic traversal-order worklist evaluation is the only no-cache, no-persisted-grant, no-candidate-scan approach that can page recursive permissions without returning to Datomic recursive Datalog.

The plan should not be implemented as-is. It needs a tighter traversal-order contract and a more formal reverse-rule model before code changes begin. Without those additions, the implementation could be correct on small fixtures while still producing unstable cursors, ambiguous page flags, or accidental candidate scans in real recursive graphs.

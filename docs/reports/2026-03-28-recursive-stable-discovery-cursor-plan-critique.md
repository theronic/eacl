# Critique: Recursive Stable Discovery Cursor Plan

Date: 2026-03-28
Plan under review: [2026-03-28-recursive-stable-discovery-cursor-plan.md](../plans/2026-03-28-recursive-stable-discovery-cursor-plan.md)

## Summary

The draft points in the right direction by abandoning full-set recursion and global eid sorting for recursive forward lookup, but it is not yet decision-complete enough to implement safely. The main gaps are the lack of a precise recursive execution model, insufficient cursor-state specification, and incomplete phase ordering around benchmarks, cursor token format, and result-order semantics.

The foundational rule should be:

- treat recursive forward lookup as resumable execution of concrete discovered facts
- keep the existing lazy merged static path engine for acyclic queries
- make recursive order and dedupe semantics explicit and testable

## Findings

### 1. The draft does not specify the recursive execution order precisely enough

The plan says "stable deterministic discovery order" but does not define the order strongly enough for implementation or tests. A stable order must be derivable from the code without ambiguity.

Recommendation:

- Define recursive forward lookup order exactly as:
  - top-level root streams seeded in static permission-path order
  - within each stream, Datomic at-rest terminal-index order
  - each emitted resource expands recursive child streams in declared recursive-path order
  - newly discovered child streams are pushed onto a stack immediately after the emitting stream so recursive lookup uses deterministic depth-first discovery order
- State that repeated queries on the same DB basis and cursor must return the same ordered vector.

### 2. The draft does not separate "emitted" from "expanded" state

Recursive lookup needs at least two exact-state sets:

- emitted root results for cross-page dedupe
- expanded concrete recursive facts so expansion work only happens once

If these are conflated, the implementation can accidentally suppress valid work or re-expand already visited nodes.

Recommendation:

- Define cursor/runtime state as:
  - `:emitted` for root resources already returned to the caller
  - `:expanded` for concrete recursive facts already expanded
  - `:stack` or `:frontier` for pending work
  - `:depth-left` for remaining recursive budget

### 3. The plan does not decide the recursive executor shape

"Frontier executor" is still too vague. The implementer needs a concrete evaluator model.

Recommendation:

- Use a recursive fact stack, not a generic queue.
- Each pending frame should represent:
  - permission node
  - concrete anchor resource eid
  - remaining recursive depth
  - next static child-path index to visit
  - terminal relation stream cursor state where needed
- Prefer depth-first discovery because it minimizes frontier size and cursor growth relative to breadth-first interleaving.

### 4. The cursor-token phase is ordered too late

Recursive lookup and count cannot be implemented safely without knowing the final cursor shape. Leaving cursor design until after recursive execution risks backtracking across multiple phases.

Recommendation:

- Move cursor format and tokenization design ahead of implementation.
- Make recursive cursor v3 an explicit design phase before coding the recursive executor.

### 5. The plan does not define exact acceptance criteria for changed pagination order

The user explicitly allows order to change, but not the set of results. The plan should lock this into tests.

Recommendation:

- For recursive pagination, assert:
  - same ordered vector on repeated evaluation at a fixed DB basis
  - concatenated pages equal one large-limit query in both order and membership
  - concatenated pages contain no duplicates
- For existing non-recursive tests, keep current behavior and performance expectations.

### 6. The benchmark phase needs stronger ordering

The draft says benchmark before and after, but it does not treat baseline capture as a hard dependency for implementation.

Recommendation:

- Put baseline capture before any code edits.
- Add a committed recursive benchmark namespace after the runtime design is stable.
- Compare branch results explicitly against the recorded `eacl/v7` baseline numbers.

### 7. The reverse-lookup phase is too broad

The performance regression is in recursive forward lookup. Expanding reverse lookup too much increases risk.

Recommendation:

- Limit structural redesign to recursive forward `lookup-resources` and `count-resources`.
- Keep `can?` and `lookup-subjects` on exact-state runtime guards plus `:max-depth`, unless red tests prove additional redesign is required.

### 8. The plan should explicitly reject full-closure and approximate dedupe approaches

The user has set hard architectural constraints. The plan should codify them as non-goals.

Recommendation:

- State explicitly that the implementation must not:
  - materialize a full reachable resource set
  - sort a full recursive closure
  - use approximate dedupe
  - use server-side cursor state

## Upgrades Required

1. Define recursive order precisely as deterministic depth-first discovery order.
2. Specify recursive runtime state as `:stack`, `:emitted`, `:expanded`, and `:depth-left`.
3. Move cursor v3 design before executor implementation.
4. Narrow the redesign to recursive forward lookup/count and minimal depth plumbing for the rest.
5. Make before/after benchmark capture an explicit first phase.
6. Add non-goals that forbid closure materialization, full recursive sorting, approximate dedupe, and server-side cursor state.

## Conclusion

The draft becomes implementation-safe once it treats recursive lookup as a resumable depth-first traversal over concrete recursive facts, with exact emitted/expanded state persisted in the cursor. That is the elegant design that would have emerged if recursive stable pagination had been a foundational assumption from the start: static cursor-tree for acyclic queries, explicit recursive execution state for recursive queries, and no full closure anywhere.

# Faster qualified and recursive operators

## Why

The delegation change (PR #199) made an intersection or exclusion over
union-only recursive operands cost about what its operands cost. Three
shapes that EACL consumers write still pay far more than their operands,
measured warm on the 636- and 2,077-account account charts:

- **Recursion through an operator itself** runs on the tabled recursive
  evaluator. For example, `inherited = reader + (parent->inherited & eligible)`
  takes 200 ms (636 accounts) and 687 ms (2,077 accounts) for the owner's
  lookup, against 3.6 ms and 24 ms for its union twin
  `reachable = reader + parent->reachable` (28–56×). Its point check is 12×
  slower. Printed question keys account for 42% of that time and repeated
  question sorting for another 11%.
- **Expiring shares.** Any conditional or temporal evidence sends the
  delegated membership search to one exact point check per candidate.
  0tx grants time-limited shares on every plan. A user whose only access is
  an expiring share on the whole chart pays 62.8 ms for the
  `delete = delete_granted & read_account` lookup, against 11.8 ms and
  11.5 ms for its operands (2.7×, over the 2× budget).
- **A union at the root of an operator permission**, such as
  `delete_top = deleter + (delete_granted & read_account)`, or a union as an
  intersection's anchor, has no delegated generator. Such a permission walks
  the per-node synthetic operator cover: 27.4 ms against 17.8 ms for `delete`
  at 636 accounts, and 60.8 ms against 39.4 ms at 2,077.

0tx builds a fresh read-only client per request, so no answer may depend on
state shared across clients. The fixes must stay inside one request.

## What Changes

- **Expiring evidence in the memoized membership search.** For one subject,
  a resource's operand permission holds while some decisive witness path
  (every edge plain or unexpired) exists. The search finds the widest such
  witness level by level, one level per distinct expiry it meets, with a memo
  per level. It returns plain `true` for a permanent witness and a
  time-limited `true` whose certificate ends at the widest witness's first
  expiry otherwise. That certificate is the exact end of the grant.
  Conditional qualifiers, faults and evidence that could make access appear
  later still route that resource to the exact point check, which retains
  nothing.
- **Flattened operator generators.** An operator plan generates candidates
  from one synthetic union-only permission per operator-reaching permission
  on its anchor chains. The permission is sealed by the ordinary union
  engine:
  - union nodes, intersection anchors and exclusion left operands flatten
    into its terms;
  - delegated operands stay references to their own union plans.

  Union roots and union anchors stop falling back to the per-node synthetic
  cover. A plan whose generator is a single delegated operand keeps that
  operand's own plan, and its cursors, unchanged.
- **Guarded recursion through operators.** A recursive component is
  *linearly guarded* when each of its intersection and exclusion nodes has
  exactly one child inside the component, and its other children are
  lower-stratum leaves: relations, permissions outside the component, or
  arrows to either.
  - Such a component is decided by a guarded memoized search. Each
    lower-stratum child becomes a condition on the edge through its operator
    node, and that condition is decided exactly for the request's subject.
  - Its lookups use the flattened generator.
  - Other recursive shapes keep the tabled evaluator. It builds each printed
    question key once per evaluation and stops re-sorting questions whose
    order no output observes.
- **No answer changes.**
  - Every lookup, count, check and reverse lookup returns the same set and
    permissionship as before.
  - A plan whose generator changes gets a new cover fingerprint. Recursive
    operator cursors already authenticate that fingerprint, so outstanding
    cursors for affected plans are rejected once.
  - A delegated operand now reports the exact end of an expiring grant as its
    certificate. That can be a later deadline than the point check's
    order-dependent choice, and it is never an unsound one.
- **Certification.**
  - **Dafny.** The membership model gains expiry levels and guarded edges,
    with proofs that the level sequence finds the widest witness and that
    guarded reachability equals the stratified semantics.
  - **Executable refinement.** The existing refinement campaigns grow to
    cover expiring edges, union-rooted and guarded plans.
  - **Controls and budgets.** New mutation controls are registered. New
    `^:benchmark` budgets are written before sampling.

## Capabilities

### New Capabilities

- `operand-bounded-set-algebra`: set-algebra permissions cost about what
  their operands cost. It covers:
  - delegated union-only operands;
  - expiring evidence in the memoized search;
  - flattened generators for union roots and anchors;
  - guarded recursion through operators;
  - the answer, order and cursor guarantees these routes keep;
  - the certification and budget gates that pin them.

### Modified Capabilities

None. The new routes keep the recursive order and cursor requirements of
`incremental-recursive-pagination`, including explicit cursor invalidation
when a plan's generator changes. They also keep the temporal certificate
requirements of qualified relationship evaluation.

## Impact

- **Code:**
  - `eacl.engine.stable-route`: the leveled search and guarded edges.
  - `eacl.operator.plan`: flattened generator terms and the guard analysis.
  - `eacl.operator.cover-plan`, or a new generator namespace: sealing the
    flattened synthetic plan.
  - `eacl.engine.v8`: routing and cursor scope.
  - `eacl.operator.vector-evaluator` and `eacl.operator.evaluator`: guarded
    delegation.
  - `eacl.operator.recursive`: question keys.
- **Formal:** `formal/dafny/MemoizedMembership.dfy`, or new leaves beside it,
  and the `formal/assurance_contract.clj` entries.
- **Tests:**
  - the refinement campaigns and mutation controls in `modules/eacl/test`;
  - the differential and delegated-recursion tests;
  - a new `^:benchmark` gate in `modules/eacl-datahike/test/eacl/bench`.
- **Behaviour:** no public API change and no shared or cross-client cache.
  Recursive operator cursors for plans whose generator changes are rejected
  once with `:eacl.pagination/invalid-cursor`.
- **Delivery:** three pull requests stacked on #199, one per workstream:
  1. expiring evidence, carrying this change;
  2. flattened generators;
  3. guarded recursion and tabled keys.

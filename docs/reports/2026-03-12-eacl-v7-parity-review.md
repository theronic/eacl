# Review Of The v7 Parity Plan

Plan under review: [2026-03-12-eacl-v7-parity-plan.md](../plans/2026-03-12-eacl-v7-parity-plan.md)

## Executive Judgment

The plan has the right end state but is still too merge-oriented in its execution details. The target architecture should be treated as a clean storage-model redesign: public EACL data and APIs remain stable, while the Datomic implementation becomes relation-metadata-driven and tuple-native from the bottom up.

The current draft is directionally correct, but it needs a stricter execution contract so the work does not collapse into a mixed v6/v7 state during implementation.

## Findings

### 1. The plan does not yet prohibit mixed storage semantics strongly enough

The largest delivery risk is landing in a hybrid model where some code paths still assume relationship entities while others use v7 tuples. The draft says tuple encoding should be private, but it does not define an explicit no-dual-write and no-entity-read rule. Without that constraint, read paths, orphan detection, and tests will drift and create subtle correctness failures.

**Recommendation**

- Add a storage invariant section to the plan:
  - relationships are never Datomic entities in the final system
  - no read path may query `:eacl.relationship/...` entity attributes for persisted relationships
  - no dual-write compatibility layer is allowed beyond the private tuple codec

### 2. The plan starts too late in the pipeline

The first phase begins with code changes, but this migration needs an explicit baseline phase that locks in verification and branch-derived source material before storage is disturbed. Without that phase, it becomes difficult to distinguish newly introduced regressions from already-existing ones and easy to port the wrong branch behavior.

**Recommendation**

- Add a Phase 0 for:
  - confirming the current failing baseline through nREPL
  - capturing the exact source files to port from `eacl/v7` and `feature/cursor-tree`
  - identifying tests that assert obsolete storage internals and must be rewritten, not removed

### 3. The plan under-specifies the foundational abstraction that should replace the current ad hoc relation handling

The clean design is not just “carry relation eid on paths.” The elegant shape is a single resolved relation descriptor used across schema writes, relationship writes, reads, orphan detection, and permission traversal. That object should have emerged naturally if v7 had existed from day one.

**Recommendation**

- Introduce one internal resolved relation map with:
  - relation eid
  - relation name
  - resource type
  - subject type
- Route tuple encoding, tuple decoding, permission path construction, and schema-orphan checks through that shared abstraction.

### 4. Verification is too back-loaded

The draft has a final verification phase, but this migration has multiple fault lines: storage writes, schema retractions, permission traversal, and cursor semantics. If verification happens only at the end, failures compound and root-cause isolation becomes expensive.

**Recommendation**

- Add verification gates after each phase:
  - tuple lifecycle tests after storage work
  - schema DSL and orphan checks after schema port
  - nested permission and reverse lookup tests after traversal work
  - opaque cursor and lazy-merge tests after cursor-tree port

### 5. The cursor-tree phase needs a clearer dependency order

The draft correctly groups cursor-tree work into one phase, but it does not specify the internal dependency chain. Cursor-tree depends on stable traversal primitives. Lazy-merge optimization depends on stable result sequence semantics. Reverse pagination depends on the same cursor representation as forward pagination.

**Recommendation**

- Expand the phase into an ordered sequence:
  1. port v2 cursor data model and tokenization
  2. port direction-parameterized lookup/count shell
  3. adapt traversal primitives to v7 tuple helpers
  4. port reverse pagination
  5. swap in specialized lazy-merge fast path

### 6. The test migration section is still too tolerant of implementation-coupled tests

The phrase “can be rewritten against the public contract” is correct but too permissive. Some tests should move from public-contract assertions to v7 invariant assertions, because v7’s design premise is now foundational, not incidental.

**Recommendation**

- Split test work into:
  - public API compatibility tests
  - v7 storage invariant tests
  - cursor-tree parity tests
  - performance-oriented merge tests

### 7. The plan should define explicit success criteria for performance changes

The user asked for the latest performance enhancements and features, but the draft mostly describes structure, not measurable exits. Performance work should not be reduced to “ported code exists.”

**Recommendation**

- Add completion checks that confirm:
  - lazy-merge fast path is the active implementation for identity-key merges
  - pagination benchmark namespace still passes
  - cursor-tree pagination avoids post-filter scans by using exclusive start tuples

### 8. The plan does not account for database-scoped caching of resolved permission paths

Once permission paths carry resolved relation eids, the cache can no longer be keyed only by logical permission coordinates like `[resource-type permission-name]`. Relation eids are database-local identities. A global cache keyed too coarsely will leak one database's path descriptors into another database and cause cross-suite or cross-tenant correctness failures.

**Recommendation**

- Treat cache scope as part of the v7 foundational design:
  - cache logical permission paths globally only if they do not embed eids
  - otherwise scope resolved-path cache entries to Datomic database identity
  - keep schema writes responsible for invalidating same-database cached paths after relation or permission changes
- Add a regression test that exercises permission queries across two databases in one process so cache leakage is caught early

## Recommended Upgrades To The Plan

1. Add a Phase 0 baseline and source-lock step before any implementation.
2. Add explicit storage invariants forbidding mixed relationship-entity semantics.
3. Introduce a single resolved relation descriptor as the foundational internal abstraction.
4. Insert verification gates after every phase rather than relying on a final sweep.
5. Expand the cursor-tree phase into a strict dependency order.
6. Split tests into API compatibility, v7 invariants, cursor-tree parity, and merge/performance categories.
7. Add explicit performance exit criteria so “latest optimizations” is testable rather than aspirational.
8. Scope resolved permission-path caches to Datomic database identity, and verify that multi-database test runs do not leak relation-eid state across databases.

## Conclusion

The plan is viable once it is tightened around one rule: redesign every touched subsystem as though v7 tuple storage and cursor-tree pagination had been the original architectural assumptions. That removes most of the accidental complexity introduced by branch drift and makes the implementation sequence testable at each boundary.

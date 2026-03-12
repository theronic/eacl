# EACL v7 Parity Plan

## Goal

Bring `eacl/v7` up to the current feature/performance tip represented by `feature/cursor-tree`, while keeping the v7 relationship storage model as the final architecture. Success means:

- all existing tests pass without deleting tests
- the branch exposes the latest schema DSL, pagination, reverse lookup, and lazy-merge behavior
- v7 tuple-backed Datomic storage is the end state, not a temporary compatibility shim

## Branch Reality

- `feature/write-schema-dsl` is the current working tip, but it still uses the pre-v7 relationship entity model
- `eacl/v7` replaced relationship entities with subject-owned and resource-owned tuple indexes for better write and read locality
- `feature/cursor-tree` contains the latest committed pagination and merge-sort optimizations and should be treated as the parity target for behavior

## Design Direction

The clean design is not “merge old storage with new features.” It is:

1. relationship values stay plain EACL data at the public boundary
2. Datomic tuple encoding stays private to the Datomic implementation
3. one internal resolved relation descriptor carries relation eid, relation name, resource type, and subject type everywhere that needs relation identity
4. permission traversal consumes resolved relation metadata plus tuple helpers, not relationship entities
4. cursor pagination is expressed as one opaque cursor-tree model for forward lookup, reverse lookup, and counts
5. schema writes are the sole authority for relation and permission metadata, including validation and cache invalidation
6. any cached permission-path structure that embeds relation eids must be scoped to Datomic database identity, not just resource type and permission name

## Storage Invariants

- relationships are never persisted as Datomic entities in the final system
- no final read path may depend on persisted `:eacl.relationship/...` entity attributes
- no dual-write compatibility layer is allowed beyond private tuple codec helpers inside the Datomic implementation
- every place that needs relation identity must use the same resolved relation descriptor instead of re-deriving relation eids ad hoc

## Phase 0. Baseline And Source Lock

- Reproduce the current failing baseline through nREPL so regression fixes are measured from a known starting point.
- Capture the exact source files that define the parity target:
  - v7 storage/schema internals from `eacl/v7`
  - cursor-tree pagination and lazy-merge behavior from `feature/cursor-tree`
- Classify current tests into:
  - public API compatibility tests
  - storage-invariant tests that must be rewritten for v7
  - parity tests to port from the later branches
- Confirm the implementation order before intrusive edits:
  1. storage boundary
  2. schema and relationship IO
  3. traversal correctness
  4. cursor-tree and merge optimization
  5. test parity and final verification

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 1. Stabilize The v7 Storage Boundary

- Reintroduce `impl/Relationship` as a plain data constructor rather than a transaction encoder.
- Move v7 tuple encoding into private write-path helpers in `src/eacl/datomic/impl.clj`.
- Add one internal resolved relation descriptor used by writes and reads:
  - relation name for human-readable behavior
  - relation eid for tuple access
  - subject/resource type for schema identity
- Centralize tuple add, tuple retract, tuple existence, and tuple decode logic behind helper functions instead of scattering raw tuple construction across call sites.
- Make `:create` reject duplicates, `:touch` idempotent, and `:delete` retract both forward and reverse tuple entries.
- Remove all remaining assumptions that a relationship is a first-class Datomic entity.
- Verification gate:
  - tuple lifecycle tests pass for create, touch, duplicate create, and delete

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 2. Rebuild Schema And Relationship IO Around v7 Tuples

- Replace `schema/v6-schema` with a v7 bootstrap schema that keeps schema-string storage and the write-schema DSL attributes.
- Retain relation and permission schema entities, but move relationship persistence fully to:
  - `:eacl.v7.relationship/subject-type+relation+resource-type+resource`
  - `:eacl.v7.relationship/resource-type+relation+subject-type+subject`
- Rewrite `read-relationships` so it enumerates tuple datoms from the narrowest anchor available and reconstructs `Relationship` values from relation metadata.
- Replace `find-one-relationship-id` with tuple-native existence and lookup helpers keyed by subject eid, relation eid, and resource eid.
- Redesign orphan detection during schema retraction to count tuple datoms that reference the relation eid instead of counting relationship entities.
- Route schema retraction, relationship reads, and write-path validation through the same resolved relation descriptor so relation identity is not reimplemented three times.
- Verification gate:
  - schema DSL tests pass
  - invalid schema writes remain atomic
  - relation removal fails when tuple-backed relationships still reference the relation

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 3. Port Permission Graph Evaluation To v7 Internals

- Keep the multi-subject relation discovery already introduced on the write-schema branch.
- Replace old relationship-entity traversal in `impl.indexed` with v7-native helpers such as `subject->resources` and `resource->subjects`.
- Make relation metadata the foundational traversal input:
  - relation datoms enumerate valid subject-type variants
  - permission paths carry resolved relation eids
  - traversal never needs to rediscover the same relation identity inside hot loops
- Preserve permission-path caching and invalidate it on every successful schema write.
- Scope permission-path cache keys to Datomic database identity because cached path descriptors carry DB-specific relation eids.
- Ensure `can?`, `lookup-resources`, `lookup-subjects`, and counts all share the same permission-path model so correctness fixes land once.
- Verification gate:
  - current nested permission/path failures are eliminated
  - reverse lookup correctness passes before pagination work begins
  - multi-subject relation tests pass on the v7 schema
  - cross-database test execution cannot poison later permission checks through shared cache state

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 4. Port Cursor-Tree And Lazy-Merge Optimizations

- Port cursor-tree in strict dependency order:
  1. bring over the v2 cursor structure `{:v 2 :e ... :p {...}}`
  2. keep external cursors opaque tokens, with backward compatibility for raw cursor maps
  3. port the direction-parameterized lookup/count shell
  4. adapt forward and reverse traversal to v7 tuple helpers
  5. port reverse pagination and count-from-cursor semantics
  6. swap in the specialized lazy-merge fast path
- Carry over the later performance behavior, not just the data structure:
  - exclusive start tuples
  - reverse cursor seeking
  - intermediate-cursor propagation for arrow paths
  - `keep`-based extraction in traversal
  - specialized lazy merge sort fast path
- Adapt `count-subjects` to the same cursor-tree mechanics instead of leaving v7 as a forward-only special case.
- Verification gate:
  - opaque cursor token tests pass
  - forward and reverse pagination tests pass
  - count-from-cursor semantics match the later branch
  - identity-key lazy merge uses the specialized fast path behavior

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 5. Port And Redesign The Tests For v7 As A Foundational Model

- Update schema, indexed, and API tests to initialize with the v7 schema.
- Port cursor-tree tests and lazy-merge tests from `feature/cursor-tree`.
- Split the migrated test surface explicitly into:
  - public API compatibility tests
  - v7 tuple-storage invariant tests
  - cursor-tree parity tests
  - lazy-merge and benchmark-oriented performance tests
- Rewrite tests that currently assert relationship-entity internals so they instead assert public behavior and tuple-backed invariants.
- Add explicit regression coverage for:
  - duplicate relationship creates
  - delete retracting both tuple copies
  - tuple-backed orphan detection
  - multi-subject relation expansion
  - opaque cursor token round-tripping
  - reverse lookup pagination and count-from-cursor behavior
- Keep all existing tests unless they only encode obsolete storage internals and can be rewritten against the public contract.
- Verification gate:
  - all migrated tests pass on v7 storage without deleting any committed tests

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 6. Verify, Benchmark, And Finish

- Run the full test suite through nREPL, fixing failures without deleting tests.
- Run the benchmark namespace explicitly once functional parity is restored.
- Sanity-check tuple storage directly for one create/touch/delete lifecycle and one schema-orphan rejection case.
- Confirm the final branch behavior matches `feature/cursor-tree` for:
  - schema DSL writes
  - forward pagination
  - reverse pagination
  - count-from-cursor semantics
  - lazy merged result ordering and deduplication
- Confirm the final optimization exits:
  - exclusive start tuples eliminate post-filter cursor scans
  - the identity-key lazy merge fast path is active
  - benchmark pagination tests still pass under the v7 storage model

- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

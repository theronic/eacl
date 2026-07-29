## 1. Cache Infrastructure

- [x] 1.1 Implement the configurable bounded cache-store protocol and local weighted TTL/LRU store.
- [x] 1.2 Implement process-shared relationship coordinators keyed by captured Datomic database identity.
- [x] 1.3 Add validated `make-client` cache configuration, including the fully disabled mode.

## 2. Coherent Relationship Dependency Epochs

- [x] 2.1 Put all relationship write helpers behind the shared coordinator mutation barrier.
- [x] 2.2 Advance only the relation-definition epochs named by changed v7 relationship tuple datoms.
- [x] 2.3 Add concurrency and no-op tests proving there is no stale-generation publication window.

## 3. Lookup Caching

- [x] 3.1 Cache internal non-recursive `lookup-resources` pages by logical EACL generation and resolved query.
- [x] 3.2 Cache internal non-recursive `lookup-subjects` pages by logical EACL generation and resolved query.
- [x] 3.3 Add unrelated-transaction, relationship-invalidation, ID-coercion, eviction, provider-failure, and disabled-cache tests.

## 4. Recursive Continuations

- [x] 4.1 Capture and admit forward recursive traversal state at returned cursor boundaries.
- [x] 4.2 Capture and admit reverse recursive traversal state at returned cursor boundaries.
- [x] 4.3 Resume valid continuations and preserve exact historical ordinal replay on every miss or rejection.
- [x] 4.4 Add sequential, eviction, disabled-cache, changed-live-DB, retry, and alternate-client correctness tests.

## 5. Documentation and Verification

- [x] 5.1 Replace the permanent effective-grant recommendation with the ephemeral cache design in reports/plans and document configuration.
- [x] 5.2 Run the full EACL test suite and heavy pagination/load tests through nREPL.
- [x] 5.3 Benchmark cached hits, misses, unrelated moving DBs, recursive complete pagination, and disabled cache against v7.3.
- [x] 5.4 Verify no Datomic schema or persistent cache/grant datoms were added.

## 1. Baseline and Shared Representation

- [x] 1.1 Branch from the latest green head of PR #92, record the base SHA, and run the existing DataScript JVM/CLJS and shared contract suites through nREPL/CI to establish the pre-change baseline.
- [x] 1.2 Add a backend-neutral CLJC endpoint-pair namespace with pure forward/reverse value construction, decoding, peer-half identity, arity validation, and prefix predicates.
- [x] 1.3 Add shared unit/property cases covering round-trip encoding, forward/reverse symmetry, malformed ordinary values, adjacent prefixes, and JVM/CLJS equality.
- [x] 1.4 Replace duplicate pure tuple construction/decoding in Datomic and Datahike with the shared helpers without changing either backend's schema or index-access behavior.
- [x] 1.5 Run the focused Datomic and Datahike endpoint storage, authorization, deletion, integrity, and content-proof tests to prove the shared-helper refactor is behavior-neutral.

## 2. DataScript Schema and Index Primitives

- [x] 2.1 Replace the DataScript relationship component, full-key, and four derived scan tuple definitions with the two Datomic/Datahike attribute idents configured as cardinality-many indexed ordinary values.
- [x] 2.2 Update schema constants and tests to assert that the active relationship layout has only the forward and reverse endpoint attributes and no relationship entity/full-key derivation.
- [x] 2.3 Implement DataScript EAVT/AVET helpers for exact endpoint values and guarded full-arity `seek-datoms`/`rseek-datoms` prefix traversal.
- [x] 2.4 Add JVM and ClojureScript boundary tests proving equal-length vector ordering, ascending/descending scans, exact prefix termination, missing-prefix behavior, and attribute/entity isolation.

## 3. Authorization and Relationship Reads

- [x] 3.1 Port `subject->resources`, `resource->subjects`, and `direct-match?` to endpoint-local values using the shared encoding and bounded index helpers.
- [x] 3.2 Port exact, forward-anchored, reverse-anchored, forward-partial, and reverse-partial relationship scan plans to the two attributes while preserving portable cursor order.
- [x] 3.3 Replace relation-in-use counting with a guarded AVET prefix scan over forward endpoint values.
- [x] 3.4 Replace remaining relationship-entity queries in schema catalogs, reads, counts, and helper functions, and verify that anchored reads never perform a graph-wide query.
- [x] 3.5 Run shared direct, arrow, recursive, lookup, count, read-relationships, and cursor continuation tests on both ascending and descending paths.

## 4. Mutation, Deletion, and Integrity

- [x] 4.1 Rework relationship resolution and existence checks so a complete relationship requires its exact forward and reverse endpoint values.
- [x] 4.2 Implement pair transactions for `:create`, `:touch`, and `:delete`, including complete-pair conflicts, incomplete-pair repair, unconditional two-half deletion, unknown endpoint errors, and affected relation mutation identities.
- [x] 4.3 Rework `delete-object!` to enumerate touching endpoint values, retract local and peer halves, preserve DataScript's existing object-retention behavior, report committed relationship retractions, and stamp every affected relation.
- [x] 4.4 Add `eacl.datascript.integrity/dangling-relationship-report` with deterministic records for missing forward or reverse peer halves and no database mutation.
- [x] 4.5 Add regression tests for direct endpoint retraction, ghost detection, touch repair, complete and partial deletion, raw cleanup inputs where supported, batch writes, and concurrent/idempotent datom set behavior.

## 5. Consistency and Cache Proofs

- [x] 5.1 Replace DataScript relationship-entity content queries with deterministic physical-half proof records over both endpoint attributes.
- [x] 5.2 Include relation identity, endpoint types/eids, endpoint public identities, half direction, and value arity in the normalized content proof so one-half or identity changes invalidate cached answers.
- [x] 5.3 Verify managed relation mutation proofs remain dependency-sized and unchanged by the physical storage refactor.
- [x] 5.4 Add current, at-least-as-fresh, exact-snapshot, cursor proof-equivalence, out-of-band half mutation, and endpoint identity mutation regressions.

## 6. Cost and Compatibility Validation

- [x] 6.1 Add storage assertions showing exactly two relationship datoms per logical relationship and no active relationship entity or derived relationship indexes.
- [x] 6.2 Build a reproducible JVM benchmark comparing PR #92's DataScript entity layout with the endpoint-pair layout for direct authorization, forward/reverse adjacency, relationship pagination, create/delete batches, and content-proof construction at representative graph sizes.
- [x] 6.3 Record hardware, runtime, warmup, sample, graph-size, correctness, storage-count, latency, and allocation methodology, and publish measured regressions as plainly as improvements.
- [x] 6.4 Update DataScript module, backend extension, release, explorer/demo, deletion, and cache-proof documentation with the ordinary-vector layout, mandatory EACL cleanup contract, ghost tooling, and prerelease database recreation instructions.

## 7. Final Verification and Stacked PR

- [x] 7.1 Run all changed Clojure and ClojureScript namespaces through clj-kondo and resolve every new warning or error; run `git diff --check`.
- [x] 7.2 Run the full non-benchmark JVM suite and the DataScript ClojureScript suite, plus isolated `eacl`, `eacl-datomic`, `eacl-datahike`, and `eacl-datascript` module verification.
- [x] 7.3 Run strict OpenSpec validation and mark each task complete only after its implementation and verification evidence exists.
- [x] 7.4 Commit and push a `codex/` feature branch, open a new PR targeting PR #92's head branch, and document the exact storage reduction, benchmark results, compatibility boundary, ghost-half contract, shared-code convergence, and validation counts without unsupported performance claims.

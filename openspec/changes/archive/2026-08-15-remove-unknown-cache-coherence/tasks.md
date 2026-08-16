## 1. Formal Model Before Runtime Changes

- [x] 1.1 Add a Dafny counterexample model proving that a maximum over independently monotone per-relation generations is unsound, including the `{A 10, B 5}` to `{A 10, B 7}` case.
- [x] 1.2 Strengthen the native-generation model with immutable forward snapshots, initialized relation generations, atomic current-transaction stamping, and the obligation that each supported committed transaction is strictly later than every previously visible relation generation.
- [x] 1.3 Define deterministic complete dependency extraction and the scalar frontier, then prove that equal lifecycle, semantic identity, schema generation, and frontier preserve every relevant relationship slice and authorization denotation across forward snapshots.
- [x] 1.4 Prove empty dependency, unrelated transaction, relevant single- and multi-relation mutation, repeated same-transaction mutation, schema replacement, no-op over-invalidation, late publication, lifecycle rotation, incomplete proof, and future-unproved-dependency cases.
- [x] 1.5 Model one adapter-scoped immutable proof frame and prove that partial, malformed, cross-adapter, cross-lifecycle, cross-snapshot, or out-of-frame subproblem evidence cannot admit a managed hit.
- [x] 1.6 Model completed demand-bounded answers and prove reuse only when normalized demand and every semantic key component match and only a completed response is published.
- [x] 1.7 Run the complete local Dafny verification suite to zero errors, retain the counterexample as a regression witness, and reconcile the design before any runtime task begins.

## 2. Shared Cache and Configuration Contract

- [x] 2.1 Remove `:coherence-authority` and `:proof-mode` from accepted options, destructuring, normalized and selection options, fingerprints, comments, validators, public docstrings, and tests so either key reports the stable unknown-option error.
- [x] 2.2 Preserve exact-first lookup and replace authority/proof-mode eligibility with automatic proof-backed reuse for deterministic cacheable ordinary current requests, including default demand evaluation.
- [x] 2.3 Implement one lazily acquired immutable dependency proof frame shared by completed answers, schema planning, managed subproblems, and cursor validation, with exact fallback for every unavailable or incomplete case.
- [x] 2.4 Replace full-vector managed and cursor identities with schema generation plus scalar dependency frontier while retaining validated per-relation evidence inside the request frame for completeness and subset derivation.
- [x] 2.5 Prevent managed publication of incomplete traversal, partial page, transient continuation, and partially proved subproblem state; include normalized demand and result shape in semantic keys.
- [x] 2.6 Generate client-local opaque fingerprints for unfingerprinted custom codecs and enable managed reuse only for explicitly stable deterministic codecs with certified round-trip behavior.
- [x] 2.7 Remove duplicate managed-descriptor and per-consumer proof acquisition paths, and add typed proof-unavailable diagnostics without turning safe exact fallback into an availability error.
- [x] 2.8 Remove the unused generated `:cache-validation` runtime operation and its host validators, fixtures, production inventory entries, and JVM/JavaScript/browser artifacts while retaining useful formal lemmas.

## 3. Formal and Generated Live Boundaries

- [x] 3.1 Remove authority and proof-mode inputs from `ConsistencyDecision.dfy` and any other live formal boundary, proving native consistency selection from requested consistency and advertised adapter capability alone.
- [x] 3.2 Update formal EDN schemas and host validators, regenerate checked-in Clojure and JavaScript kernels, and verify generated artifacts contain no dead authority, alternate proof-mode, vector-identity, or cache-validation runtime input.
- [x] 3.3 Update formal smoke, differential, exhaustive, mutation-registry, assurance-matrix, production-inventory, and trusted-boundary artifacts for the smaller live boundary and ordered-generation adapter obligation.
- [x] 3.4 Run local generation-drift, formal smoke, JVM/JavaScript differential, mutation-control, production-inventory, and full Dafny verification successfully.

## 4. Bundled Backend Proof Integration and Certification

- [x] 4.1 Implement the ordered-generation proof-frame capability for Datomic and remove its duplicate authority, proof-mode, schema-proof, relation-proof, and managed-descriptor propagation.
- [x] 4.2 Implement the ordered-generation proof-frame capability for Datahike and remove its duplicate authority, proof-mode, schema-proof, relation-proof, and managed-descriptor propagation.
- [x] 4.3 Implement the ordered-generation proof-frame capability for DataScript on JVM and ClojureScript and remove its duplicate authority, proof-mode, schema-proof, relation-proof, and managed-descriptor propagation.
- [x] 4.4 Certify on every bundled backend that supported writes initialize relation generations, stamp every affected relation with the committed native transaction, exceed every prior visible stamp, and publish tuples and stamps atomically.
- [x] 4.5 Cover multiple transaction-function invocations, multi-relation batches, repeated relations, additions, retractions, object deletion, safe retraction, repair, and no-op/over-invalidation transaction shapes in certification tests.
- [x] 4.6 Update third-party adapter certification so adapters advertise ordered-generation proof support explicitly and otherwise remain safe exact-current adapters without authority configuration.

## 5. Adversarial Correctness Tests

- [x] 5.1 Add constructor tests on Datomic, Datahike, DataScript JVM, and DataScript ClojureScript proving every former authority and proof-mode value fails as unknown configuration.
- [x] 5.2 Prove default clients obtain managed hits after unrelated transactions and invalidate after every relevant relationship and schema mutation shape for both complete-denotation and demand-bounded requests.
- [x] 5.3 Add missing, malformed, duplicate, non-canonical, oversized, throwing, cross-adapter, cross-lifecycle, and partial proof tests demonstrating exact-only fallback with no unproved managed hit.
- [x] 5.4 Add arbitrary historical, filtered, speculative, caller-supplied database, long-running immutable request, late publication, and lifecycle replacement tests.
- [x] 5.5 Add built-in, deterministic fingerprinted custom-codec, unfingerprinted codec, shared-key cursor, cross-client, restart, and selected-snapshot re-render property tests.
- [x] 5.6 Add cross-backend randomized cached-versus-bypassed oracle tests under interleaved supported schema and relationship mutations, including empty, overlapping, and large dependency closures.
- [x] 5.7 Add recovery tests proving quiesced multi-client rotation removes stale out-of-band results, no-op `write-schema!` is not a flush, preparation does not repair unstamped mutations, and cache operations never repair ghost tuples.
- [x] 5.8 Run all non-benchmark core, Datomic, Datahike, DataScript JVM, and DataScript ClojureScript suites locally and resolve every regression.

## 6. Performance Evidence

- [x] 6.1 Replace the order-sensitive proof benchmark with randomized or interleaved A/B sampling, adequate warm-up, sample counts, p50/p95, allocation/key-size metrics, cache counters, and backend operation counts.
- [x] 6.2 Benchmark exact same-snapshot hits and verify zero managed proof reads and no material latency or allocation regression.
- [x] 6.3 Benchmark managed hits after unrelated commits, relevant-proof misses, and proof acquisition over increasing dependency cardinalities against the full-vector baseline.
- [x] 6.4 Verify request proof acquisition remains `O(d)`, managed/cursor proof identity is constant-size, write bookkeeping remains `O(r)`, and no content scan, listener, transaction-log scan, or database-global coordination appears.
- [x] 6.5 Record reproducible local correctness and performance results and make only claims supported by the measurements.

## 7. Current Consumer Documentation Only

- [x] 7.1 Rewrite the root README cache sections for the current automatic exact-first/proof-backed behavior, proof availability and exact fallback, supported mutation boundary, and precise multi-process recovery procedure.
- [x] 7.2 Remove obsolete `:unknown`, `:managed`, `:coherence-authority`, `:proof-mode`, legacy token/version, migration, release-history, and change-log language from active consumer documentation and examples.
- [x] 7.3 Remove or relocate internal definition-record descriptions, obsolete backend-SPI counts, maintainer release procedures, and test-only material that consumers do not need from the root README.
- [x] 7.4 Update the cache guide, adapter-boundary guide, and Datomic/Datahike/DataScript READMEs with only current options, safe ghost repair, transaction-data atomicity, custom-codec fingerprinting, proof-unavailable behavior, and exact backend `expire-cache!` calls.
- [x] 7.5 Search all active public documentation and constructor docstrings for stale alternate-coherence, alternate-proof, migration, and historical-current-version claims.

## 8. Final Local Validation and Review

- [x] 8.1 Run strict OpenSpec validation and reconcile proposal, design, delta specs, tasks, implementation, formal model, documentation, and measured results.
- [x] 8.2 Run the complete local formal, generated-drift, JVM, ClojureScript, randomized, backend certification, and benchmark gates without waiting for CI.
- [x] 8.3 Inspect the final diff against concurrent work, commit only this change's files, push the existing PR branch, and update the PR with theorem, trusted-boundary, test, and performance evidence.
- [ ] 8.4 Inspect CI after publication and fix CI-specific failures without weakening locally proved correctness or tests.

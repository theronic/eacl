## 1. Correct and Merge the V7 Datahike Port

- [x] 1.1 Fetch the latest PR #81 head, its current DataScript base, and `release/v8.0`; record the reviewed head SHAs and rebase or retarget #81 onto the latest intended DataScript v7 branch without importing unrelated history.
- [x] 1.2 Add shared v7 regression cases for unknown forward and reverse anchors, empty lookup page shapes, empty counts, and the absence of meaningless continuation cursors.
- [x] 1.3 Fix the v7 shared engine or adapter result normalization so DataScript and Datahike both satisfy the canonical empty-result contract.
- [x] 1.4 Add the missing `eacl-datahike` build implementation, declare every directly required runtime/build dependency, remove unused declarations, and include shared contract support in the module's own test basis.
- [x] 1.5 Verify the Datahike module loads and runs its shared and adapter-specific tests from an isolated module nREPL, and verify its artifact build entry point succeeds from the module dependency basis.
- [x] 1.6 Run the complete non-benchmark v7 suite via nREPL, including Datahike keyword-attribute and numeric `:attribute-refs?` configurations, and correct any discovered behavior differences.
- [x] 1.7 Update PR #81 with the reviewed fixes and CI checks, wait for required checks to pass, confirm its diff still targets only the v7 DataScript line, and merge it.

## 2. Establish the V8 Integration Baseline

- [x] 2.1 Create the integration branch from the latest `release/v8.0` head used by PR #84 and document the exact merged PR #81 source commit being carried forward.
- [x] 2.2 Import the merged `eacl-datahike` module without merging obsolete v7 core history, then wire its source, test, build, and dependency paths into the modular v8 workspace.
- [x] 2.3 Run the existing modular v8 non-benchmark suite via nREPL and all module-isolated load/build checks to establish a clean pre-extraction baseline.
- [x] 2.4 Add Datomic characterization tests for consistency selection, recursive permissions, forward/reverse Relay pages, counts, filters, deletion, cursor validation, and cached versus uncached results before moving shared logic.

## 3. Define the Shared V8 Adapter Contract

- [x] 3.1 Inventory each Datomic v8 engine operation and classify it as shared authorization behavior or backend-specific snapshot, index, entity, transaction, cursor, or proof behavior.
- [x] 3.2 Define and document a validated v8 adapter protocol or operation map with explicit consistency, snapshot, cursor, transaction, and cache-proof capability declarations.
- [x] 3.3 Preserve the existing six-function SPI for supported v7 third-party adapters and add compatibility tests proving that the v8 contract does not silently change those function shapes.
- [x] 3.4 Wrap Datomic's existing storage-specific operations behind the v8 adapter contract while keeping its public namespaces and characterization suite green.
- [x] 3.5 Add typed validation for unsupported adapter capabilities so unavailable consistency or historical guarantees fail explicitly before authorization execution.

## 4. Extract the Portable V8 Authorization Engine

- [x] 4.1 Move backend-neutral permission schema compilation and dependency-set calculation into `eacl`, retaining recursive edges and computing strongly connected components.
- [x] 4.2 Implement deterministic fixed-point traversal for self-recursive and mutually recursive permissions with semantic de-duplication and explicit depth, work, and result ceilings.
- [x] 4.3 Move shared direct, arrow, forward, and reverse authorization traversal onto adapter adjacency operations without exposing backend records or raw index tuple layouts.
- [x] 4.4 Extract backend-neutral Relay windowing, count/truncation, filter validation, common error categories, and canonical empty-result behavior.
- [x] 4.5 Define versioned resumable traversal state for acyclic and recursive forward/reverse pages, leaving opaque cursor protection and backend metadata behind adapter/runtime boundaries.
- [x] 4.6 Route Datomic permission checks, lookups, counts, filters, and deletion semantics through the shared engine while preserving its release-candidate public API, encrypted cursor behavior, database data, and regression results.
- [x] 4.7 Add shared tests for self cycles, mutual cycles, deep chains, duplicate paths, recursive denial, forward/reverse pagination, invalid cursors, and every configured safety ceiling.

## 5. Extract and Generalize Authorization Caching

- [x] 5.1 Move the cache store contract, entry versioning, dependency metadata, serialization boundary, miss handling, and validation workflow into backend-neutral `eacl` namespaces.
- [x] 5.2 Extend the adapter contract with opaque, snapshot-bound schema and per-relation proof operations, and ensure validation uses the same logical snapshot as authorization execution.
- [x] 5.3 Adapt Datomic's transaction-based proof and cache implementation to the shared contracts without changing its v8 invalidation or fallback behavior.
- [x] 5.4 Implement database-visible DataScript schema/relation proofs that change atomically with EACL-managed mutations and remain exact for the selected immutable database value.
- [x] 5.5 Implement database-visible Datahike schema/relation proofs that remain visible across distinct connections and change atomically with EACL-managed mutations.
- [x] 5.6 Integrate proof-validated cached results and recursive Relay continuations with the shared engine, including rejection or recomputation after any relevant dependency change.
- [x] 5.7 Implement fail-closed behavior for unavailable stores, corrupt entries, decoding errors, and proof failures, with safe uncached fallback where possible.
- [x] 5.8 Document and test the invalidation hook or cache-disable behavior required when consumers perform relationship or schema writes outside EACL mutation entry points.

## 6. Upgrade the DataScript Adapter to V8

- [x] 6.1 Implement DataScript snapshot selection, entity/reference resolution, ordered adjacency scans, schema operations, relationship transactions, and deletion through the v8 adapter contract.
- [x] 6.2 Upgrade DataScript public entry points from legacy limit/cursor requests to the v8 Relay lookup, bounded-count, filter, error, and `delete-object!` contract.
- [x] 6.3 Add a synchronous opaque cursor implementation for DataScript CLJ and CLJS that preserves v8 ordering, validation, and resumption semantics without pretending to provide Datomic-specific encryption.
- [x] 6.4 Enable DataScript recursive permission schemas and portable authorization caching through the shared engine, and remove its duplicated v7 traversal implementation where compatibility no longer requires it.
- [x] 6.5 Run the shared v8 conformance, recursive, cache, empty-anchor, and differential suites for DataScript in both supported Clojure and ClojureScript runtimes.

## 7. Upgrade the Datahike Adapter to V8

- [x] 7.1 Adapt PR #81's reviewed `eacl.datahike.db` data-access layer to the v8 snapshot, ordered adjacency, entity/reference, transaction, deletion, and proof operations without copying shared engine algorithms.
- [x] 7.2 Upgrade Datahike public entry points to the v8 Relay lookup, bounded-count, filter, typed-error, consistency-capability, and `delete-object!` contract.
- [x] 7.3 Enable Datahike recursive permission schemas, resumable pagination, and portable authorization caching through the shared engine.
- [x] 7.4 Run the shared v8 conformance, recursive, cache, empty-anchor, and differential suites under both keyword attributes and numeric `:attribute-refs?` representations.
- [x] 7.5 Verify relevant cache invalidation across distinct Datahike connections, tuple derivation in both attribute modes, isolated module loading/tests, and the `eacl-datahike` artifact build.

## 8. Complete Cross-Backend Verification

- [x] 8.1 Build a deliberately simple independent authorization oracle and curated or generated schema/relationship cases with reproducible seeds and inspectable failure fixtures.
- [x] 8.2 Run the shared public API matrix against equivalent Datomic, DataScript, and Datahike fixtures and compare authorization sets with the independent oracle.
- [x] 8.3 Run the shared cache matrix covering hits, misses, relevant and unrelated relation/schema writes, deletion, store failure, corrupt entries, and recursive continuation invalidation.
- [x] 8.4 Run Datomic-specific consistency, historical basis, encrypted cursor, transaction-proof, and database-compatibility regression tests after all extraction work.
- [x] 8.5 Run the complete non-benchmark workspace test suite via nREPL and verify `eacl`, `eacl-datomic`, `eacl-datascript`, and `eacl-datahike` load, test, and build from their isolated dependency bases.

## 9. CI, Documentation, and Delivery

- [x] 9.1 Update root aliases, module build entry points, and CI so all four isolated modules plus the combined non-benchmark nREPL suite are required checks.
- [x] 9.2 Document module selection, supported backend capabilities, cache proof and out-of-band mutation rules, recursive safety controls, and the breaking DataScript/Datahike v7-to-v8 pagination/count migration.
- [x] 9.3 Confirm no shared engine namespace imports Datomic, DataScript, or Datahike implementation namespaces and no adapter contains a copied recursive traversal or cache-validation engine.
- [x] 9.4 Run OpenSpec strict validation, reconcile every requirement scenario with an automated test or documented verification, and mark all completed tasks.
- [x] 9.5 Review the final diff and commit history for obsolete v7 core imports, unintended Datomic storage/cursor changes, undeclared dependencies, generated artifacts, and unrelated changes.
- [ ] 9.6 Push the integration branch, open a pull request targeting `release/v8.0` (the head branch of PR #84), include PR #81 provenance and the cross-backend test evidence, and wait for required checks to pass.

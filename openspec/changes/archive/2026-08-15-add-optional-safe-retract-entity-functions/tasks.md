## 1. Baseline and Contract Fixtures

- [x] 1.1 Refresh issue #87 and PR #103/target-branch state, record the implementation base, and re-check the `core` worktree so concurrent or unrelated changes are preserved.
- [x] 1.2 Add a shared backend-contract fixture covering subject, resource, bidirectional, multi-relation, self, and unrelated endpoint pairs before implementing the new path.
- [x] 1.3 Add failing contract assertions for resolved and unresolved eid/lookup-ref safe retraction, resulting endpoint parity, ordinary entity removal, and post-hoc ghost non-repair.
- [x] 1.4 Add failing contract assertions that default backend schemas do not contain `:eacl.fn/retractEntity` and that support descriptors use only `:named`, `:direct`, or `:unsupported` with structured reasons.

## 2. Portable Planning and Mutation Envelope

- [x] 2.1 Add portable CLJC constants and exact forward/reverse endpoint decoders for safe-retraction planning without introducing a backend dependency into `dev.eacl/eacl`.
- [x] 2.2 Implement the pure local-half planner that emits peer-half retractions, skips redundant self-peer work, deduplicates affected relation ids, and rejects malformed tuple values safely.
- [x] 2.3 Implement mutation-envelope construction using the existing v3 mutation id, fingerprint, issuance, and retention primitives with canonical target data.
- [x] 2.4 Implement portable mutation-envelope validation suitable for reuse by embedded backends and as the oracle for the self-contained Datomic body.
- [x] 2.5 Unit-test the planner and envelope across CLJ and CLJS, including adversarial/malformed values, duplicate halves, self-relationships, deterministic validation failures, and bounded output counts.

## 3. Datomic Named Database Function

- [x] 3.1 Add a dedicated Datomic safe-retraction namespace with the support descriptor, version/digest marker, tx-data constructor, and public installer/preparer API.
- [x] 3.2 Define a self-contained `d/function` body for `:eacl.fn/retractEntity` that resolves the target from transaction-start `db`, performs two target-scoped endpoint reads, validates the envelope, and emits peer cleanup plus ordinary `:db.fn/retractEntity`.
- [x] 3.3 Extend the Datomic expansion with one relation-version/current-tx stamp and one v3 relation-mutation stamp per affected relation plus graph-head/order/anchor retention updates from the envelope.
- [x] 3.4 Make installation idempotent for the current marker, explicitly upgrade recognized EACL versions, and reject an unrecognized occupant of `:eacl.fn/retractEntity` without overwriting it.
- [x] 3.5 Verify installation does not add EACL Vars to the transactor dependency surface and does not change `schema/v7-schema` or require `DATOMIC_EXT_CLASSPATH` changes.
- [x] 3.6 Add direct `d/invoke`/`d/with` differential tests comparing the stored function expansion with the portable oracle over generated endpoint fixtures.
- [x] 3.7 Add Datomic integration tests for resource, subject, both-direction, multi-relation, self, lookup-ref/eid, missing target, inbound refs/components, malformed storage, and existing-ghost behavior.
- [x] 3.8 Add Datomic concurrency tests for both serialization orders against the certified relationship writer and assert that no surviving half can commit after deletion.
- [x] 3.9 Add Datomic managed-cache and consistency tests proving affected relation stamps and graph anchors advance atomically while unrelated relation proofs remain stable.

## 4. DataScript Installed Function

- [x] 4.1 Add a CLJC DataScript safe-retraction namespace with support discovery, named installation, tx-data construction, version/conflict handling, and reinstall guidance metadata.
- [x] 4.2 Implement the installed/direct IFn using the portable planner and DataScript transaction-start db APIs, including ordinary `:db.fn/retractEntity` and v3 relation/graph mutation data.
- [x] 4.3 Add JVM DataScript installation and integration tests matching the shared correctness fixture, idempotent/conflict behavior, missing targets, and no default installation.
- [x] 4.4 Add ClojureScript/Node installation and integration tests for the same public contract and verify the function executes without JVM-only code.
- [x] 4.5 Add DataScript concurrency and every-supported-proof-mode cache tests, including stable proofs for unrelated relations.
- [x] 4.6 Add a serialization/reload characterization test and ensure support metadata/documentation requires reinstallation when function values are not preserved.

## 5. Datahike Named and Direct Modes

- [x] 5.1 Add a Datahike safe-retraction namespace whose support probe inspects actual schema flexibility, function-value round-trip behavior, attribute representation, and writer topology.
- [x] 5.2 Implement named installation for configurations that can store/read/invoke `:db/fn`, with version/conflict behavior matching Datomic and DataScript.
- [x] 5.3 Implement the in-process `:db.fn/call` tx-data fallback using the same function body for strict `:schema-flexibility :write` databases without changing their configuration.
- [x] 5.4 Return structured `:unsupported` results and installation errors when neither named nor transport-safe direct invocation is available.
- [x] 5.5 Implement Datahike endpoint planning, ordinary `:db.fn/retractEntity`, v3 relation/graph mutation data, and native attribute representation handling for both keyword and `:attribute-refs?` modes.
- [x] 5.6 Add Datahike integration tests for named `:read` mode, direct default `:write` mode, both attribute representations, repeated installation, conflicts, missing targets, and the full shared correctness fixture.
- [x] 5.7 Add Datahike persistence/writer-boundary characterization tests so support reporting is based on observed capability rather than version assumptions.
- [x] 5.8 Add Datahike concurrency and every-supported-proof-mode cache tests, including stable proofs for unrelated relations.

## 6. Cross-Backend Correctness and Performance Gates

- [x] 6.1 Run the shared safe-retraction contract against Datomic, DataScript JVM, DataScript CLJS, Datahike named mode, and Datahike direct mode; assert SpiceDB reports unsupported with the portable fallback.
- [x] 6.2 Add a regression that prewarms every installed function and separates first-use compilation from steady-state expansion measurements.
- [x] 6.3 Instrument backend index access and add CI assertions for exactly two target-scoped endpoint reads plus fixed mutation-bookkeeping reads, with no schema-wide or relationship-wide scan.
- [x] 6.4 Add operation-count fixtures proving output is linear in target-local degree plus distinct affected relations and invariant under increasing unrelated database size.
- [x] 6.5 Add warmed expansion and commit benchmarks at representative degrees, compare the atomic path with current tx-data generation, and save host/runtime-qualified evidence under `docs/benchmarks/results`.
- [x] 6.6 Verify high-degree benchmark results and document the measured crossover/operational guidance for choosing atomic safe retraction versus batched `delete-object!` without adding a flaky absolute-latency CI gate.

## 7. Documentation and Public API Review

- [x] 7.1 Update the root `README.md` entity-deletion section with the ghost explanation, opt-in support matrix, tested Datomic/DataScript/Datahike examples, and one-transaction mutation-envelope constructor usage.
- [x] 7.2 Document that missing targets do not repair old ghosts and link the integrity audit/raw-eid repair workflow.
- [x] 7.3 Document high-degree transactor risk, the batched `delete-object!` alternative, unsupported same-transaction relationship additions, and the one-safe-retraction-invocation-per-transaction boundary.
- [x] 7.4 Update `modules/eacl-datomic/README.md`, `modules/eacl-datahike/README.md`, and `modules/eacl-datascript/README.md` with deployment, persistence, writer-topology, rollback, and security details specific to each mode.
- [x] 7.5 Review all new public names, docstrings, structured error data, and examples for consistent terminology without exposing backend implementation details through the portable artifact.

## 8. Final Verification and Integration

- [x] 8.1 Run focused namespaces through the active nREPL when available, requiring changed namespaces with `:reload`, then run the isolated module test aliases.
- [x] 8.2 Run the repository-wide Clojure suite, DataScript ClojureScript suite, trusted-surface/reflection gates, and modular build/install smoke tests.
- [x] 8.3 Verify generated jars contain each backend's safe-retraction namespace only in its owning artifact and introduce no new dependency or default schema datum.
- [x] 8.4 Re-check issue #87 acceptance against the implementation and README, run `openspec validate add-optional-safe-retract-entity-functions --strict`, and update every completed checkbox with evidence.
- [x] 8.5 Reconcile the final diff with the latest PR #103 target branch, preserving concurrent work and reporting any benchmark/environment qualification before preparing the follow-on PR.

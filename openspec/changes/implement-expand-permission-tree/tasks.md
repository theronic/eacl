## 1. Reference Contract and Failure-First Tests

- [x] 1.1 Record the pinned Docker image tag and digest, fixture schema/relationships/request, raw protobuf JSON, and order-insensitive normalization rules with duplicate multiplicity preserved.
- [x] 1.2 Add portable request/response contract tests for exact query keys, malformed resources, non-nil resource relations, timeout validation, unknown roots, node oneof invariants, and internal-id non-disclosure.
- [x] 1.3 Add pure reference-evaluator fixtures for direct and empty relations, same-resource relations and permissions, arrows to relations and permissions, absent roots, multi-subject types, same-id/different-type intermediates, duplicate paths, diamonds, cycles, and every limit dimension.
- [x] 1.4 Add property generators for bounded schemas and relationships plus permutation checks proving production vector order is non-semantic and non-canonical custom ids remain valid.

## 2. Dafny Semantic Model and Assurance Boundary

- [x] 2.1 Add `formal/dafny/PermissionTree.dfy` with snapshot, schema, relationship, typed-object, permission-component, annotated-tree, active-path, budget, and outcome datatypes.
- [x] 2.2 Define executable shallow expansion and denotation functions for direct relations, permission unions, same-resource references, arrows, empty resources, cycles, and all-or-error limits.
- [x] 2.3 Prove node oneof/annotation well-formedness, exact direct leaves including sum-typed relation declarations, union-denotation and permutation invariance, absent-resource topology, active-path rejection, emitted-child depth accounting, budget monotonicity, and successful-limit preservation.
- [x] 2.4 Add executable direct/union/arrow/empty/diamond/cycle/limit witnesses and mutation controls that demonstrate the proofs reject unsound flattening, global-visited cycle detection, type-erasing identity, partial success, and over-limit success.
- [x] 2.5 Register the model in the assurance matrix and manifest with exact trusted boundaries and no claim of mechanical Clojure extraction.
- [x] 2.6 Run locked Dafny formatting and verification until the module has zero verification errors and its proof-effort report is recorded.

## 3. Portable Expansion Kernel

- [x] 3.1 Add strict `:permission-tree-limits` defaults/normalization to shared and Datomic clients and forbid per-request overrides.
- [x] 3.2 Add an incremental guarded scan consumer that meters realized values without eagerly materializing the complete adapter sequence and preserves existing adapter arities.
- [x] 3.3 Add `eacl.permission-tree` request validation, typed errors, definition validation, root resolution, typed identity descriptors, and request-local budgets.
- [x] 3.4 Implement explicit-stack direct relation and permission-union construction for same-resource relation/permission references and arrow targets while preserving empty branches, nested boundaries, multiplicity, and active-path diamonds.
- [x] 3.5 Implement iterative selected-adapter rendering with request-local codec memoization, exact supplied-root rendering, typed nil/invalid codec failures, and no production canonical sorting.
- [x] 3.6 Add deadline checks around every modelled definition read, scan realization, work-frame transition, render conversion, and completion boundary; verify no failure returns a lazy or partial tree.
- [x] 3.7 Compare the portable kernel against the independent reference evaluator across exhaustive bounded fixtures and property-generated cases in CLJ and CLJS.

## 4. Snapshot, Token, and Client Wiring

- [x] 4.1 Add and test a selected-adapter causal-token helper that never re-reads a live connection.
- [x] 4.2 Add shared orchestration validation, execution normalization, consistency selection, expansion, rendering, token issuance, and response assembly for DataScript and Datahike.
- [x] 4.3 Wire Datomic `Spiceomic` through its existing `execute-request` and selected-snapshot path into the same portable kernel.
- [x] 4.4 Update the protocol documentation and replace all shipped `:eacl/not-implemented` branches while preserving third-party protocol compatibility.
- [x] 4.5 Add concurrent schema/relationship mutation and causal replay tests proving tree data, rendering, and `:expanded-at` use one snapshot for every supported backend mode.

## 5. Cross-Backend and SpiceDB Verification

- [x] 5.1 Extend `eacl.contract-support` and run the expansion contract against Datomic, DataScript, Datahike with both Datahike attribute modes, and the DataScript CLJS runner.
- [x] 5.2 Add adapter instrumentation and hostile lazy-sequence tests for partition scope, incremental realization, runtime guards, deadline overrun honesty, and structural work ceilings.
- [x] 5.3 Capture every golden tree through the version-pinned SpiceDB Docker service and compare normalized topology and leaf multisets against all shipped backends.
- [x] 5.4 Replace the Datomic protocol test expecting not-implemented and the ignored historical expansion examples with the explicit documented response.
- [x] 5.5 Run codec tests covering strings, numbers, unresolved numeric ids, deterministic custom ids, non-canonical values, nil conversion, and equal internal ids under different types.

## 6. Documentation and Compatibility

- [x] 6.1 Update the root and module READMEs with request/response examples, unordered semantics, shallow-versus-denotation guidance, limits, consistency-token replay, typed errors, and backend differences.
- [x] 6.2 Update release/upgrade notes with the additive behavior, new client option, scoped SpiceDB claim, formal assurance boundary, absence of migration, and rollback procedure.
- [x] 6.3 Update formal-verification navigation and trusted-boundary documentation with the PermissionTree theorem map, proof commands, residual assumptions, and correspondence-test locations.

## 7. Final Verification

- [x] 7.1 Re-read concurrent work state and inspect every changed file for unrelated edits, unresolved not-implemented paths, accidental internal-id exposure, and new dependencies or persisted attributes.
- [x] 7.2 Through the discovered nREPL and `:reload`, run focused permission-tree, execution, configuration, shared contract, Datomic, DataScript, Datahike, and formal correspondence test namespaces with zero failures or errors.
- [x] 7.3 Run the DataScript CLJS suite and the repository's complete non-benchmark EACL test suite through nREPL with zero regressions.
- [x] 7.4 Run locked Dafny format/verify, formal mutation controls and manifest generation as applicable, formatting/static/source-closure checks, and inspect exact verification claims.
- [x] 7.5 Run `openspec validate implement-expand-permission-tree --strict`, reconcile artifacts/tasks with the implemented behavior, and perform a final adversarial audit documenting any residual trusted boundary rather than claiming literal universal certainty.

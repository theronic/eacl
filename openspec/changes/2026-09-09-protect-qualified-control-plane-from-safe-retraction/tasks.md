## 1. Reproduce the native defect
- [x] Load `regressions/eacl/datomic/adversarial_v8_review_test.clj` in the test-enabled nREPL.
- [x] Confirm the direct Caveat deletion is admitted on the pinned code and record the fresh false-to-true permission result.
- [x] Confirm the qualifier-target and component-closure guard regressions fail for the intended reason, not fixture admission.
- [x] Retain the second named alternative and absent bound context; do not simplify away the witness.

## 2. Implement the mutation boundary
- [x] Inventory current Caveat/qualifier owned fields from their declarations.
- [x] Extend the portable guard and its native-entity field projection.
- [x] Extend the self-contained Datomic transaction-function predicate.
- [x] Reject protected members anywhere in the native component closure before any mutation commits.
- [x] Preserve ordinary object deletion, typed errors, and explicit admitted qualifier cleanup.

## 3. Cross-backend and installation coverage
- [x] Exercise actual installed Datomic, DataScript CLJ/CLJS named/direct, and Datahike supported modes.
- [x] Inspect Datalevin's actual capability before deciding whether additional coverage applies.
- [x] Coordinate a function compatibility/version update with `2026-09-09-stamp-datomic-self-loop-retractions`; verify an old installed body is replaced.
- [x] Verify conflict-safe installation, idempotent reinstallation, and ordinary application-object regressions.
- [x] Add partial-control-record, qualifier-target, parent-component, missing-target, and unknown-ident cases.

## 4. Refine and release
- [x] Add a model transition or native refinement check for incoming Caveat-ref removal and protected control roles.
- [x] Add a killed production mutation that removes the protection and recreates the native witness.
- [x] Run relevant JVM/CLJS/native suites and the applicable formal gates via the repository's prescribed workflow.
- [x] Run `bin/formal source-closure` after public source changes and validate this OpenSpec change with the installed CLI.
- [x] Document the required installed-function upgrade; keep storage ABI v8 and generated verification output out of Git.

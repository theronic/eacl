## MODIFIED Requirements

### Requirement: Counterexample-driven bug discovery
The repository SHALL provide coherent generators and temporal models that search direct, recursive, forward, reverse, cursor, cache, restore, reset, branch, retention, tampering, and provider-failure behavior. Every discovered mismatch or invariant violation SHALL be minimized, reproducible, classified, and retained as a regression before it is considered resolved. A retained regression SHALL execute the code that carried the defect, or the behavior that replaced it, and SHALL fail if the defect returns. A test that asserts only that a name is absent, that a file contains or lacks literal text, or that a model defined in the test file behaves as written is not a regression.

#### Scenario: Differential mismatch
- **WHEN** the formal semantics, verified kernel, legacy engine, public API, or supported runtime disagree
- **THEN** the run fails with a reproducible seed and emits or records the smallest available schema, graph, request, and state trace

#### Scenario: Model-checker violation
- **WHEN** temporal exploration violates a cache, cursor, or causal-history invariant
- **THEN** the counterexample trace is translated into a failing executable regression test before the implementation is corrected

#### Scenario: Security-significant defect
- **WHEN** a counterexample permits a false grant, stale authorization, cross-scope cursor, or mixed-snapshot result
- **THEN** the bug ledger records affected versions/backends, security impact, root cause, fix, and closing proof/test

#### Scenario: The defective path was deleted
- **WHEN** the code path that carried a recorded defect no longer exists
- **THEN** the ledger entry's regression is a test of the public behavior that replaced that path

#### Scenario: The defect was in tooling
- **WHEN** a ledger entry records a defect in CI or test tooling rather than in EACL
- **THEN** the entry names the gate that failed as its closing evidence
- **AND** the entry has no product regression

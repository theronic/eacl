## MODIFIED Requirements

### Requirement: Simplicity and performance evidence is certification-linked

Every structural inventory and non-regression result MUST be mapped into the
implementation-conformance ledger and reproducible certification bundle with
source closure and digests. Measured results MUST carry the exact production
artifact digest they test. A deleted path MUST NOT be pinned by a test that
asserts only that a name is absent, or that a file contains or lacks literal
text. The behavior that replaced the path SHALL be asserted through the public
API.

#### Scenario: Evidence targets a different binary or source closure

- **WHEN** simplicity or performance evidence does not match the candidate
  production artifact and source-closure digest
- **THEN** the conformance gate rejects the evidence as stale
- **AND** prospective external certification remains unavailable

#### Scenario: A path is deleted

- **WHEN** a code path, option, or storage attribute is deleted
- **THEN** no test asserts its absence by name or by searching source text
- **AND** a test of the public behavior that replaced it fails if that
  behavior regresses

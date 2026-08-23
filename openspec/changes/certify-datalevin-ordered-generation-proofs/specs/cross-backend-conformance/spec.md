## ADDED Requirements

### Requirement: Datalevin joins ordered-generation conformance
The shared conformance suite SHALL run its ordered-generation cases — exact-hit precedence, unrelated-write reuse, relevant-write invalidation, lineage isolation, unavailable versus contract-violation classification, retained-basis reuse, and cursor continuation across unrelated writes — against Datalevin with the same expected results as the other ordered-generation backends, while exact historical selection remains reported as unsupported.

#### Scenario: Equivalent fixtures
- **WHEN** the suite runs the ordered-generation matrix on Datalevin
- **THEN** every shared expectation passes without a Datalevin-specific acceptance path

#### Scenario: Exact history remains unsupported
- **WHEN** a changed-frame cursor needs exact reconstruction on Datalevin
- **THEN** the suite expects the typed stale-cursor outcome, not a substituted snapshot

## ADDED Requirements

### Requirement: Cursor continuation conformance
The shared conformance suite SHALL compare concatenated pages with one deterministic cache-free stream oracle for every backend: continuation across unrelated writes (forward and reverse), rejection on relevant writes with exact fallback only where the source advertises exact selection, source recreation for non-durable sources and provider restart for durable sources, boundary tampering, and `:populate-cache? false`. Expected results SHALL be defined once; no backend-specific acceptance logic is permitted.

#### Scenario: Accepted current continuation
- **WHEN** any backend accepts a cursor on a later basis
- **THEN** concatenated pages equal the exact suffix or prefix of the oracle with no duplicates or omissions

#### Scenario: Relevant stream input changes
- **WHEN** schema, a closure relation, the plan fingerprint, the identity contract, direction, or a semantics-affecting limit changes
- **THEN** every backend rejects current-basis continuation

#### Scenario: Exact fallback differs by capability
- **WHEN** current continuation is rejected
- **THEN** only sources advertising exact selection continue on the original basis, and the others return the typed stale outcome

#### Scenario: Population disabled
- **WHEN** a continued page is requested with `:populate-cache? false`
- **THEN** cursor validation and the page contents are unchanged and no checkpoint or visited page is published

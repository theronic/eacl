## RENAMED Requirements

- FROM: `### Requirement: Cache lifting is forward-only`
- TO: `### Requirement: Cache lifting is lineage-scoped`

## MODIFIED Requirements

### Requirement: Cache lifting is lineage-scoped
EACL SHALL return a cross-revision managed candidate only within one configured source lifecycle and source scope, for an ordinary-class selected basis, and when complete schema and dependency proofs match. The rule SHALL NOT compare the candidate's revision with the selected revision: within one lineage, equal proofs at two bases establish equal answers whichever basis is older. The rule SHALL apply identically to a transient snapshot selected by an `acl`, a captured snapshot, a directly constructed snapshot, and an ordinary basis loaded by exact locator. Numeric equality alone MUST NOT lift an answer across replacement or sibling history.

#### Scenario: Candidate precedes the selected basis
- **WHEN** the selected basis is later in the same unreplaced history and has an equal complete dependency proof
- **THEN** EACL may proof-lift the answer

#### Scenario: Candidate follows a retained snapshot
- **WHEN** a retained snapshot's basis is older than the candidate's committed generation and the proofs compare equal in the same lineage
- **THEN** EACL may proof-lift the answer

#### Scenario: Candidate is from another lifecycle or scope
- **WHEN** a candidate belongs to a different lifecycle, source, or branch
- **THEN** EACL treats it as a miss

#### Scenario: Validation telemetry is reused
- **WHEN** an entry records a prior `validated-at` value newer than its computation point
- **THEN** a later request still validates the selected basis proof
- **AND** MUST NOT treat `validated-at` as a lease

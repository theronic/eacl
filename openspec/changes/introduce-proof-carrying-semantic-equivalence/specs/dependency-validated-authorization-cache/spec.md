## MODIFIED Requirements

### Requirement: Cache lifting is lineage-scoped
EACL SHALL return a managed candidate computed at another basis only when the candidate and the selected basis share one lineage — equal source scope and equal source lifecycle — the selected basis has a readable, contract-valid frame, and the candidate's complete frame (certified schema generation and scalar dependency frontier over the complete canonical closure) equals the frame read at the selected basis. The rule SHALL NOT compare the two revisions or exclude a basis by kind: a candidate computed at a newer basis is as reusable at an older admissible basis of the same lineage as the reverse. Numeric revision equality, lifecycle equality alone, or raw adapter stamps MUST NOT lift an answer across replacement or sibling history.

#### Scenario: Candidate precedes the selected basis
- **WHEN** the selected basis is later in the same lineage and the frames are equal
- **THEN** EACL may return the candidate

#### Scenario: Candidate follows the selected basis
- **WHEN** a retained snapshot at an older basis reads a frame equal to that of a candidate computed at a newer basis in the same lineage
- **THEN** EACL may return the candidate

#### Scenario: Candidate is from another lineage
- **WHEN** a candidate belongs to a different source scope or source lifecycle
- **THEN** EACL treats it as a miss even if revision numbers and frames compare equal

#### Scenario: Validation telemetry is reused
- **WHEN** an entry records a prior `validated-at` value newer than its computation point
- **THEN** a later request still compares the frame read at the selected basis
- **AND** MUST NOT treat `validated-at` as a lease or a lineage witness

### Requirement: Semantic cache keys are complete
The cache lookup key SHALL distinguish cache/engine/adapter versions, lineage (source scope and source lifecycle), operation, complete canonical internal query, pagination state, result kind, and every recursion, traversal, count, object-codec, caveat, or adapter option capable of changing the answer. The managed completed-answer tier SHALL be keyed by lineage and certified schema generation, then by semantic key, result kind, and dependency frontier; managed subproblem and continuation keys SHALL carry the same lineage.

#### Scenario: Two sources share a runtime
- **WHEN** bases from different source scopes reach one runtime
- **THEN** their managed entries cannot collide or validate across scopes

#### Scenario: Engine configuration changes
- **WHEN** an answer-affecting traversal limit, codec contract, adapter implementation, or algorithm version changes
- **THEN** the new request cannot reuse the old entry

#### Scenario: Hash collision is attempted
- **WHEN** two canonical queries have the same compact hash
- **THEN** embedded full-key equality prevents substitution

### Requirement: Cache failures degrade only to selected-snapshot evaluation
Provider errors, absent proofs, and transient proof-computation failures SHALL become misses and fall back to uncached evaluation on the selected basis. A proof-frame contract violation SHALL also fall back to exact evaluation for the request, and SHALL additionally disable managed lifting for the runtime until `expire-cache!`, count the violation by reason, and report it through the optional diagnostic reporter. Token, causal freshness, source-scope, and exact-snapshot failures MUST remain request errors.

#### Scenario: Cache provider throws
- **WHEN** lookup or storage fails
- **THEN** EACL computes and returns the selected-basis answer

#### Scenario: Proof frame violates its contract
- **WHEN** an adapter returns a duplicate, non-canonical, non-integer, or above-revision generation
- **THEN** EACL evaluates exactly, publishes no managed entry, disables managed lifting for the runtime, and records the reason

#### Scenario: Token anchor is missing
- **WHEN** consistency selection cannot prove the requested mutation is present
- **THEN** EACL rejects the request and MUST NOT hide the failure behind an uncached current read

## ADDED Requirements

### Requirement: Cache reads and cache population are independently controlled
Every cache-capable authorization read SHALL accept `:populate-cache?`, defaulting to `true`. When `false`, EACL SHALL perform every lookup, proof acquisition, and request-local memoization the request would otherwise perform and SHALL publish no completed answer, managed subproblem, checkpoint, or visited page across requests. When `:cache? false` is also supplied, no lookup or publication occurs and `:populate-cache?` has no further effect. Neither option SHALL be part of any cache, cursor, or continuation identity.

#### Scenario: Read-only hit
- **WHEN** a request with `:populate-cache? false` matches an existing exact or managed entry
- **THEN** EACL returns the entry and writes nothing

#### Scenario: Read-only miss
- **WHEN** a request with `:populate-cache? false` misses
- **THEN** EACL evaluates on the selected basis and publishes nothing

#### Scenario: Pagination needs evidence
- **WHEN** `:populate-cache? false` is used for a continued page
- **THEN** cursor validation still acquires the frame it needs and checkpoint publication is suppressed

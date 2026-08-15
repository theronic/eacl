## ADDED Requirements

### Requirement: Uniform automatic cache configuration
Every bundled backend SHALL expose one automatic proof-backed completed-cache behavior and SHALL reject `:coherence-authority` and `:proof-mode` as unknown client configuration. Third-party adapters SHALL NOT expose authority selection; an adapter without a complete certified proof capability SHALL fail closed to exact evaluation or no caching for that request.

#### Scenario: Removed coherence option
- **WHEN** a consumer constructs a bundled backend client with either former `:coherence-authority` value
- **THEN** construction fails with the stable invalid-configuration error identifying `:coherence-authority` as an unknown key

#### Scenario: Removed proof option
- **WHEN** a consumer constructs a bundled backend client with any former `:proof-mode` value
- **THEN** construction fails with the stable invalid-configuration error identifying `:proof-mode` as an unknown key

#### Scenario: Third-party adapter lacks proof support
- **WHEN** a third-party adapter cannot supply a complete certified proof for an otherwise cache-enabled request
- **THEN** shared orchestration evaluates the selected exact snapshot without reusing an unproved managed answer

### Requirement: One immutable request proof frame
The backend-neutral adapter contract SHALL expose one immutable proof context for the exact selected adapter, lifecycle, and database value. Completed answers, managed subproblems, schema planning, and cursor validation SHALL share that request context rather than acquiring semantically duplicate proof evidence independently.

#### Scenario: Exact cache hits first
- **WHEN** exact-snapshot lookup succeeds before a proof context is needed
- **THEN** the adapter performs no managed relation-generation reads for that completed answer

#### Scenario: Several request consumers need proof
- **WHEN** schema planning, a completed-answer lookup, managed subproblems, and cursor validation require the same schema and relation evidence
- **THEN** they share the lazily acquired immutable context scoped to that request

#### Scenario: Subproblem adds an unproved relation
- **WHEN** a managed subproblem declares a dependency outside the complete relation set established by its request proof context
- **THEN** that subproblem is not admitted as a managed hit or publication from partial evidence

#### Scenario: Proof provider fails
- **WHEN** the adapter proof operation throws or returns malformed or incomplete evidence
- **THEN** the request remains exact-only and no partial proof context is retained

### Requirement: Certified ordered-generation adapter capability
A cache-capable adapter SHALL certify immutable snapshot identity, source lifecycle, complete canonical dependency generation reads, schema generation, and native transaction generations that are globally ordered across supported commits. Adapters without the ordered-generation capability SHALL remain valid exact-current adapters.

#### Scenario: Bundled backend certification
- **WHEN** Datomic, Datahike, or DataScript executes a supported relationship mutation
- **THEN** adapter certification observes that every affected relation stamp equals the committed transaction generation and exceeds every prior relation stamp

#### Scenario: Exact-current-only adapter
- **WHEN** an adapter supplies stable immutable snapshot identity but not certified ordered generations
- **THEN** the shared engine may use exact-current caching without cross-snapshot managed reuse

## MODIFIED Requirements

### Requirement: Graph-independent coherence adapter contract
The backend-neutral adapter contract SHALL expose native immutable snapshot identity, source lifecycle, revision selection capabilities, one complete ordered-generation proof context, and explicit lifecycle expiry without requiring mutation-graph head, anchor-membership, journal-retention, cache-authority, alternate proof-mode, or duplicate managed-descriptor operations.

#### Scenario: Cache-capable adapter
- **WHEN** an adapter supplies stable current-snapshot identity and a complete certified ordered-generation proof while its mutations obey the supported-writer contract
- **THEN** the shared engine can provide exact-current and managed-current caching without graph metadata or configuration authority

#### Scenario: Managed proof unavailable
- **WHEN** an adapter cannot provide complete proof evidence for one selected request
- **THEN** the shared engine evaluates that request exactly without treating the adapter as a separate coherence mode

#### Scenario: Native consistency capability
- **WHEN** an adapter advertises at-least, fully-consistent, or exact selection
- **THEN** it supplies the corresponding native selection operation and source-lifecycle validation independently of completed-answer proof availability

#### Scenario: History replacement
- **WHEN** an adapter or operator replaces source history
- **THEN** it rotates the source lifecycle before the client resumes cached authorization requests

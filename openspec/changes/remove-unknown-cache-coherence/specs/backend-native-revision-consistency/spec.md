## ADDED Requirements

### Requirement: Adapter-scoped proof and cursor identity
Every proof, completed answer, cursor, and revision token SHALL be bound to the exact backend adapter identity and configured source lifecycle that selected its immutable database value. Selecting a different adapter or recovering a retained revision SHALL create a new proof context rather than reuse evidence captured from another adapter.

#### Scenario: Cursor recovers a retained revision
- **WHEN** cursor validation selects a retained immutable database value through a different adapter instance or selection context
- **THEN** EACL constructs and validates a proof scoped to that selected adapter and value before admitting reusable state

#### Scenario: Adapter identity differs
- **WHEN** an otherwise matching proof or cursor was produced by a different adapter identity or source lifecycle
- **THEN** EACL rejects it before cache reuse or authorization evaluation

#### Scenario: Client-local custom codec identity
- **WHEN** a cursor was issued using an unfingerprinted custom identity codec
- **THEN** another client lifecycle does not accept that cursor even when signing keys are shared

## MODIFIED Requirements

### Requirement: Backend capability honesty
Each adapter SHALL advertise only the native revision, authoritative-head, exact-snapshot, and ordered-generation proof operations supported by its configured backend and SHALL reject unsupported consistency modes before cache access. Native revision capability SHALL remain independent of completed-answer caching and SHALL NOT depend on a cache-authority or proof-mode option.

#### Scenario: Datahike retained commit capability
- **WHEN** a Datahike configuration exposes a stable branch and retained native commit selection
- **THEN** its adapter may advertise the corresponding at-least or exact capability using those native identities

#### Scenario: Datahike capability is not certified for a configuration
- **WHEN** EACL cannot prove stable commit acquisition and branch selection for the active Datahike store configuration
- **THEN** the adapter advertises the smaller current-only capability instead of inferring support from implementation details

#### Scenario: DataScript current-only source
- **WHEN** a DataScript connection has no retained historical selection mechanism
- **THEN** its adapter does not advertise arbitrary exact-snapshot selection and never retains hidden historical database values to emulate it

#### Scenario: Ordered-generation proof is not certified
- **WHEN** an adapter cannot certify complete dependency reads and globally ordered native relation generations
- **THEN** native revision operations may remain available while cross-snapshot managed answer reuse fails closed to exact evaluation

#### Scenario: Completed-answer cache is disabled
- **WHEN** a client disables completed-answer caching but its adapter and lifecycle support a native revision operation
- **THEN** EACL may still issue and select authenticated native revision tokens

### Requirement: Graph-independent token protocol
Native revision token issuance and validation SHALL NOT require graph-head writes, mutation anchors, mutation-journal retention, mutation fingerprints, anchor pruning, cache-authority configuration, or proof-mode configuration.

#### Scenario: Relationship write returns a token
- **WHEN** an EACL relationship writer commits successfully
- **THEN** any returned token is derived from the committed backend revision without adding graph or journal datoms to the relationship transaction

#### Scenario: No-op write
- **WHEN** a supported writer performs no database mutation
- **THEN** it may return a token representing the selected current revision without creating a synthetic mutation record

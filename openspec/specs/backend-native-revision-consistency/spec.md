# backend-native-revision-consistency Specification

## Purpose
Define authenticated freshness and exact-snapshot behavior using each backend's native forward-history revision identities without a portable mutation graph.
## Requirements
### Requirement: Authenticated native revision tokens
EACL SHALL encode revision tokens with a version, backend identity, configured source lifecycle, native revision or exact locator, issuance time, and expiry, and SHALL authenticate the complete payload before using it for selection.

#### Scenario: Valid token from the same lifecycle
- **WHEN** a client supplies an unexpired authenticated token for the configured backend and source lifecycle
- **THEN** EACL may use its native revision fields to select the requested freshness level

#### Scenario: Wrong source lifecycle
- **WHEN** a token belongs to another backend or source lifecycle
- **THEN** EACL rejects it before cache access or authorization evaluation

#### Scenario: Legacy graph-anchor token
- **WHEN** a client supplies a token in the removed graph-anchor format
- **THEN** EACL rejects it with a stable token-format or upgrade error

### Requirement: Datomic forward-history selection
Within one unreplaced Datomic database history, EACL SHALL use authenticated database scope and transaction `t` as the native causal floor, targeted synchronization for at-least selection, and retained `d/as-of` selection only for explicit exact-snapshot behavior.

#### Scenario: Peer is behind requested floor
- **WHEN** a Datomic Peer receives an at-least token whose `t` is ahead of its locally observed database
- **THEN** EACL performs a bounded targeted synchronization and selects a database with basis at least that `t` or returns a typed timeout or unavailable error

#### Scenario: Explicit exact request
- **WHEN** a caller explicitly requests an exact retained Datomic revision
- **THEN** EACL selects that revision if available and bypasses the completed-answer cache

#### Scenario: Ordinary current request
- **WHEN** a caller requests minimize-latency or fully-consistent current behavior
- **THEN** EACL does not call `d/as-of` merely to validate or reuse a completed answer

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

### Requirement: Lifecycle replacement invalidates revisions
Restore, reset, branch force, history replacement, or equivalent source replacement SHALL be outside native revision comparability until the source lifecycle and affected EACL clients are rotated.

#### Scenario: Planned restore
- **WHEN** an operator restores a database
- **THEN** the operator rotates or recreates the configured EACL source lifecycle, token context, cache, and cursor state before accepting traffic

#### Scenario: Lifecycle was not rotated
- **WHEN** a consumer replaces history without performing the required lifecycle operation
- **THEN** EACL makes no cache, token, cursor, or monotonic-revision correctness guarantee across that replacement

#### Scenario: Cross-process tokens
- **WHEN** several processes exchange EACL tokens or cursors
- **THEN** they use one explicitly configured shared lifecycle identity, rotate it under quiescence on source replacement, and do not treat process-local cache expiry as cluster-wide coordination

### Requirement: Immutable request retention
EACL SHALL allow an in-flight operation to retain and use the immutable snapshot selected at request start even after a newer native revision becomes current.

#### Scenario: New transaction during traversal
- **WHEN** a newer transaction commits during a multi-stage or recursive authorization operation
- **THEN** every stage of that operation continues against its originally selected snapshot

### Requirement: Graph-independent token protocol
Native revision token issuance and validation SHALL NOT require graph-head writes, mutation anchors, mutation-journal retention, mutation fingerprints, anchor pruning, cache-authority configuration, or proof-mode configuration.

#### Scenario: Relationship write returns a token
- **WHEN** an EACL relationship writer commits successfully
- **THEN** any returned token is derived from the committed backend revision without adding graph or journal datoms to the relationship transaction

#### Scenario: No-op write
- **WHEN** a supported writer performs no database mutation
- **THEN** it may return a token representing the selected current revision without creating a synthetic mutation record

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


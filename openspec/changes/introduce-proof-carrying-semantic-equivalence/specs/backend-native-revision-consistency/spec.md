## MODIFIED Requirements

### Requirement: Adapter-scoped proof and cursor identity
Every proof frame, completed answer, managed subproblem, checkpoint, cursor, and revision token SHALL be bound to the lineage — source scope and configured source lifecycle — and the adapter identity that selected its basis. Selecting a basis of another lineage or recovering a retained revision SHALL construct a new frame rather than reuse evidence captured elsewhere.

#### Scenario: Cursor recovers a retained revision
- **WHEN** cursor validation selects a retained immutable value through a different adapter instance or selection context
- **THEN** EACL constructs and validates a frame scoped to that selected adapter and value before admitting reusable state

#### Scenario: Adapter identity differs
- **WHEN** an otherwise matching proof or cursor was produced by a different adapter identity, source scope, or source lifecycle
- **THEN** EACL rejects it before cache reuse or authorization evaluation

#### Scenario: Client-local custom codec identity
- **WHEN** a cursor was issued using an unfingerprinted custom identity codec
- **THEN** another client lifecycle does not accept that cursor even when signing keys are shared

## ADDED Requirements

### Requirement: Source scope is the lineage witness
Two bases SHALL be treated as comparable — for revision ordering, generation comparison, and every cross-basis reuse — only when their source scope and source lifecycle are equal. A basis source whose backend persists a stable source identity SHALL expose that identity in its source scope. A basis source whose backend cannot persist one, or whose configured store is not durable, SHALL mint a fresh random source identity once per live source and SHALL NOT reuse a configured or previous identity across reconnection or restart. Configuration labels and operator declarations MUST NOT substitute for either component.

#### Scenario: Durable source
- **WHEN** a Datomic database, a durable Datahike store, or a Datalevin store is reopened
- **THEN** its source scope carries the same persisted identity and forward revisions remain comparable under the same lifecycle

#### Scenario: In-memory source restarts
- **WHEN** a DataScript connection, a Datahike memory store, or a Datomic memory database is recreated with equal content and repeated revision numbers under the constant default lifecycle and a shared keyring
- **THEN** tokens and cursors from the previous live source are rejected for scope mismatch before any proof comparison

#### Scenario: Fixed memory-store id
- **WHEN** a Datahike memory store is configured with a caller-supplied fixed store id
- **THEN** the basis source still mints a per-connection source identity and does not expose the fixed id as lineage

#### Scenario: History replacement
- **WHEN** an operator restores, resets, or replaces a durable source
- **THEN** lifecycle rotation remains the required boundary and unrotated siblings receive no comparability guarantee

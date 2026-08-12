## ADDED Requirements

### Requirement: Graph-independent coherence adapter contract
The backend-neutral adapter contract SHALL expose native immutable snapshot identity, source lifecycle, revision selection capabilities, schema generation, relation-generation proofs, and explicit lifecycle expiry without requiring mutation-graph head, anchor-membership, or journal-retention operations.

#### Scenario: Cache-capable adapter
- **WHEN** an adapter supplies stable current-snapshot identity, schema generation, complete relation-generation reads, and the managed stamped-writer contract
- **THEN** the shared engine can provide exact-current and managed-current caching without graph metadata

#### Scenario: Exact-current-only adapter
- **WHEN** an adapter cannot provide complete managed dependency generations
- **THEN** the shared engine can still use exact-current caching against its immutable snapshot identity

#### Scenario: Native consistency capability
- **WHEN** an adapter advertises at-least, fully-consistent, or exact selection
- **THEN** it supplies the corresponding native selection operation and source-lifecycle validation rather than a portable graph-anchor operation

#### Scenario: History replacement
- **WHEN** an adapter or operator replaces the source history
- **THEN** it exposes or invokes lifecycle expiry before the client resumes cached authorization requests

### Requirement: Backend dependency isolation after graph removal
Removing the portable mutation graph SHALL preserve module dependency isolation and SHALL keep all backend-native revision and transaction-function code in the corresponding adapter module.

#### Scenario: Core-only consumer
- **WHEN** a consumer loads only the backend-neutral module
- **THEN** graph-independent cache orchestration and adapter contracts load without Datomic, Datahike, or DataScript dependencies

#### Scenario: Backend-specific token selection
- **WHEN** an adapter implements its native revision token fields and selection operations
- **THEN** those runtime dependencies remain confined to that adapter artifact

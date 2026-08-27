## MODIFIED Requirements

### Requirement: Immutable request retention
EACL SHALL resolve each read request against an `acl` into exactly one immutable snapshot and SHALL use that snapshot for the complete operation. A caller MAY retain a snapshot across many operations; those operations SHALL continue to use its original native revision after newer revisions become current. A snapshot constructed directly from an application-owned database value SHALL have the same retention semantics without any source acquisition.

#### Scenario: New transaction during traversal
- **WHEN** a newer transaction commits during a multi-stage or recursive operation
- **THEN** every stage continues against the originally selected snapshot
- **AND** a later request against the `acl` selects according to its own descriptor

#### Scenario: Retained snapshot after source advance
- **WHEN** a retained snapshot is read after the source advances
- **THEN** the read uses the retained revision without synchronizing, polling, or selecting current

#### Scenario: Application-owned value
- **WHEN** a snapshot is constructed from an admissible immutable database value
- **THEN** revision identity, token issuance, cache proofs, and evaluation derive from that value
- **AND** no current-source qualification occurs

### Requirement: Backend capability honesty
Each basis source SHALL advertise only the current, authoritative-head, at-least, exact-by-locator, and durable-history selection it supports for its configured connection, and each basis adapter SHALL advertise only the native revision, basis kind, and ordered-generation proof operations supported for its value. Unsupported selection modes SHALL be rejected before cache access. Exact selection from conditionally retained commits SHALL remain distinguishable from durable temporal-history reconstruction. Native revision capability SHALL remain independent of completed-answer caching.

#### Scenario: Datahike temporal history is enabled
- **WHEN** a Datahike source has `:keep-history? true`
- **THEN** EACL advertises durable exact reconstruction by native revision even when a named commit record is absent

#### Scenario: Datahike relies only on retained commits
- **WHEN** Datahike temporal history is disabled but its commit graph supports exact commit loading
- **THEN** EACL advertises conditional exact selection
- **AND** a collected named commit returns exact-snapshot unavailable rather than a substituted snapshot

#### Scenario: DataScript current-only source
- **WHEN** a DataScript connection has no retained historical selection mechanism
- **THEN** its source does not advertise exact selection and never retains hidden historical values to emulate it

#### Scenario: Ordered-generation proof is not certified
- **WHEN** an adapter cannot certify complete dependency reads and globally ordered relation generations
- **THEN** native revision operations may remain available while managed lifting fails closed to exact evaluation

#### Scenario: Completed-answer cache is disabled
- **WHEN** an `acl` disables completed-answer caching but its adapter supports native revisions
- **THEN** EACL may still issue and select authenticated native revision tokens

### Requirement: Lifecycle replacement invalidates revisions
The default source lifecycle SHALL be the documented constant `"eacl/initial"` on Datomic, Datahike, and DataScript. Datalevin SHALL require an explicitly supplied persisted lifecycle and SHALL NOT synthesize a universal or process-local default. Restore, reset, branch force, history replacement, or equivalent source replacement SHALL be outside native revision comparability until the operator rotates the lifecycle with `expire-cache!` in every affected process. Processes sharing one source SHALL share one lifecycle value, by the built-in default or by explicit persisted configuration.

#### Scenario: Planned restore
- **WHEN** an operator restores a database
- **THEN** the operator rotates the lifecycle in every process serving that source before accepting traffic

#### Scenario: Lifecycle was not rotated
- **WHEN** a consumer replaces history without rotating
- **THEN** EACL makes no cache, token, cursor, or monotonic-revision guarantee across that replacement

#### Scenario: Default cross-process interoperability
- **WHEN** a reader process and a writer process open the same Datomic, Datahike, or DataScript source with default configuration and a shared keyring
- **THEN** tokens and cursors issued by one are accepted by the other

#### Scenario: Datalevin lifecycle is explicit
- **WHEN** a process constructs a Datalevin source without a persisted lifecycle value
- **THEN** construction fails with the typed configuration error before acquisition

## ADDED Requirements

### Requirement: Selection belongs to sources
Consistency descriptors SHALL be resolved by a basis source — never by a basis adapter or a snapshot — and the result SHALL be an immutable snapshot that carries no authority to refresh itself.

#### Scenario: Fully consistent acl request
- **WHEN** an `acl` receives a fully-consistent read
- **THEN** its source establishes the authoritative basis once and the resulting snapshot evaluates the whole request without another head barrier

### Requirement: Retained snapshots validate but do not select
A retained snapshot SHALL authenticate and compare any exact or at-least token against its own scope, lifecycle, revision, and exact locator before cache access, SHALL evaluate when its basis satisfies the constraint, SHALL throw the typed freshness or basis-conflict error otherwise, and SHALL reject fully-consistent behaviour with the typed selection-required error.

#### Scenario: Exact token matches
- **WHEN** an authenticated exact token names the snapshot's revision and locator
- **THEN** the snapshot evaluates without invoking any source

#### Scenario: Newer floor
- **WHEN** an authenticated at-least token is newer than the snapshot
- **THEN** the snapshot throws `:eacl.consistency/freshness-unavailable` with `:reason :snapshot-behind` and does not substitute a newer basis

### Requirement: Exact selection by locator reads no current head
A source that supports exact selection SHALL load the basis named by an authenticated exact locator directly and SHALL NOT acquire the current head to qualify the request. Datomic MAY perform the documented bounded targeted synchronization when the local Peer is behind the token; Datahike SHALL load the named commit and SHALL report unavailability when it is collected.

#### Scenario: Datahike exact by commit
- **WHEN** a Datahike `acl` over a remote store selects `at-exact-snapshot` with a token carrying a commit locator
- **THEN** the source loads that commit
- **AND** instrumentation observes zero branch-head reads

#### Scenario: Datomic exact already local
- **WHEN** a Datomic token names a basis at or below the local Peer's basis
- **THEN** the source selects `d/as-of` without `d/sync`

### Requirement: Construction acquires no basis
Constructing an `acl` SHALL read the source's static profile only and SHALL perform no current, authoritative, at-least, or exact acquisition.

#### Scenario: Reader Peer cold start
- **WHEN** a reader process constructs an `acl` over a remote-store connection
- **THEN** no branch-head read is attributable to EACL until the first read or snapshot selection

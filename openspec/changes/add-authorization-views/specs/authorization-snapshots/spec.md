## Purpose

Make an immutable authorization snapshot the primary public read target so any EACL Peer can evaluate against a database basis it already holds, with no hidden connection reads, while the live `acl` remains the one-call source of snapshots and the only mutation target.

## ADDED Requirements

### Requirement: Unified public read surface
EACL SHALL expose one set of public `eacl/*` read functions that accept either a live `acl` or an immutable snapshot. Each public function SHALL normalize its documented convenience arities into one canonical request map and dispatch to the matching `IAuthorizationReader` method without target-specific branching.

#### Scenario: Same function for acl and snapshot
- **WHEN** a caller invokes `eacl/can?` with equivalent inputs against an `acl` and against a snapshot selected from that `acl`
- **THEN** both calls use the same documented arities and return the boolean projection of the same canonical detailed decision

#### Scenario: Complete read surface
- **WHEN** a snapshot is passed to schema read, relationship read, permission check, resource lookup or count, subject lookup or count, pagination, or permission-tree expansion
- **THEN** the operation executes through the same public function and response contract as an `acl`

#### Scenario: Invalid target
- **WHEN** a public function receives a value implementing neither `IAuthorizationReader` nor `ISnapshotSource`
- **THEN** it throws `:eacl/invalid-authorization-target` instead of a protocol linkage error

### Requirement: Canonical detailed decision
`check-permission` SHALL be the canonical permission operation returning `{:allowed? :cached? :cache-basis :evaluation}`, and `can?` SHALL be exactly its `:allowed?` projection. No optional fallback protocol SHALL exist.

#### Scenario: Projection equality
- **WHEN** `check-permission` and `can?` evaluate the same demand against the same target
- **THEN** `can?` returns the `:allowed?` value of the `check-permission` result

### Requirement: Immutable snapshot value
A snapshot SHALL be bound to exactly one selected immutable backend database value for its whole lifetime and SHALL consist of that basis plus its owning `acl`'s runtime. It SHALL contain no connection, basis source, writer, synchronization function, history loader, or any other value able to select or mutate a basis.

#### Scenario: Source advances after creation
- **WHEN** the backend commits newer schema or relationship transactions after a snapshot was created
- **THEN** every later read through that snapshot evaluates against the original basis
- **AND** performs no current, authoritative, at-least, or exact acquisition

#### Scenario: Nested and paginated work
- **WHEN** a snapshot read performs validation, nested permission evaluation, proof resolution, cache access, cursor handling, pagination, or token rendering
- **THEN** every sub-operation derives from that snapshot's basis

#### Scenario: Structural invariant
- **WHEN** the snapshot record and its reachable runtime are inspected
- **THEN** no field holds a connection, basis source, writer, or selection callback

### Requirement: Snapshot construction
EACL SHALL provide `(eacl/snapshot acl)` capturing the source's current basis exactly once, `(eacl/snapshot acl consistency)` resolving one explicit consistency descriptor, a per-backend direct constructor pairing an application-owned immutable database value with an `acl`'s runtime, and `eacl/with-snapshot` scoping a snapshot to a body and releasing it in `finally`.

#### Scenario: Capture current once
- **WHEN** a caller invokes `(eacl/snapshot acl)`
- **THEN** the source performs exactly one current acquisition
- **AND** the returned snapshot's basis, token, revision, and cache generation derive from that acquisition

#### Scenario: Explicit selection
- **WHEN** a caller invokes `(eacl/snapshot acl descriptor)` with omitted, minimize-latency, fully-consistent, at-least-as-fresh, or at-exact-snapshot consistency
- **THEN** EACL resolves the descriptor through the source exactly once and returns a snapshot bound to the selected basis, or throws the source's typed unsupported-capability or selection error

#### Scenario: Direct construction
- **WHEN** a caller passes an `acl` and an admissible immutable database value to the backend's `snapshot` constructor
- **THEN** EACL returns a snapshot over that exact value without any source acquisition
- **AND** the snapshot's lifecycle and source scope are the `acl` runtime's

#### Scenario: Scoped snapshot
- **WHEN** `eacl/with-snapshot` binds a snapshot around a body that returns or throws
- **THEN** the snapshot is released exactly once after the body completes

### Requirement: Admissible database values
Every backend SHALL classify a database value's basis kind. Public snapshot constructors SHALL admit only values whose identity is complete for their visible content — ordinary values and exact as-of values — and SHALL refuse filtered, since, history, speculative, foreign-backend, and foreign-source values with a typed error before any runtime state is touched.

#### Scenario: Ordinary value
- **WHEN** a current, captured, or commit-loaded database value is supplied
- **THEN** the snapshot records basis kind `:ordinary`

#### Scenario: As-of value
- **WHEN** a backend-native exact as-of value is supplied
- **THEN** the snapshot records basis kind `:as-of` and the historical cache class

#### Scenario: Inadmissible value
- **WHEN** a filtered, since, history, or speculative value, or a value from another backend or source, is supplied
- **THEN** the constructor throws `:eacl/unsupported-database-value` naming the basis kind
- **AND** no cache generation, plan, or token is created for it

### Requirement: Basis metadata and tokens
A snapshot SHALL expose its bounded non-secret basis identity through `eacl/basis`, an authenticated opaque basis token through `eacl/basis-token`, and its native database value through a backend-specific accessor. Callers SHALL NOT need record-field access.

#### Scenario: Metadata names the evaluated database
- **WHEN** a caller reads a snapshot's basis, token, and native value
- **THEN** all three identify the database used by every read through that snapshot

#### Scenario: Token selects the same basis elsewhere
- **WHEN** a basis token is passed to `(eacl/snapshot acl (at-exact-snapshot token))` on any `acl` sharing the keyring, source, and lifecycle
- **THEN** the selected snapshot has the same basis identity
- **AND** the caller never decodes or modifies the token

### Requirement: Request-scoped consistency on acl
Consistency SHALL be a property of each read request against an `acl`. The `acl` SHALL resolve the descriptor into exactly one transient snapshot, execute the complete operation against it, and release it afterwards. Creating or retaining a snapshot SHALL NOT change any later read against the `acl`.

#### Scenario: Ordinary acl read
- **WHEN** a read is invoked against an `acl`
- **THEN** the source performs exactly one acquisition satisfying the request's descriptor
- **AND** the whole operation, including nested evaluation and pagination, runs on that transient snapshot

#### Scenario: Retained snapshot does not pin the acl
- **WHEN** an application retains a snapshot and later reads through the original `acl`
- **THEN** the later read resolves its own descriptor independently

### Requirement: Consistency assertions on snapshots
A read against a retained snapshot SHALL never select another basis. Omitted or minimize-latency consistency SHALL evaluate. An authenticated same-scope at-least token naming a revision no newer than the snapshot SHALL evaluate. An authenticated exact token naming the snapshot's basis SHALL evaluate. Every other descriptor SHALL fail with a typed error before cache access.

#### Scenario: Floor satisfied
- **WHEN** an at-least token names a revision less than or equal to the snapshot's revision in the same source scope and lifecycle
- **THEN** EACL evaluates without any source operation

#### Scenario: Snapshot behind floor
- **WHEN** an at-least token names a revision newer than the snapshot
- **THEN** EACL throws `:eacl.consistency/freshness-unavailable` with `:reason :snapshot-behind` and bounded requested and actual revisions
- **AND** does not advance the snapshot or consult any source

#### Scenario: Exact token names another basis
- **WHEN** an exact token names a different revision or exact locator
- **THEN** EACL throws `:eacl.consistency/basis-conflict` with `:source :token`

#### Scenario: Fully consistent assertion
- **WHEN** `:fully-consistent` is requested from a snapshot
- **THEN** EACL throws `:eacl.consistency/selection-required`
- **AND** performs no cache lookup, evaluation, or source operation

### Requirement: Read and write capability separation
Snapshots SHALL implement only read and snapshot-metadata capabilities. An `acl` SHALL implement reads, snapshot selection, and — only when constructed with a writer — mutations. An `acl` constructed with `:read-only? true` SHALL have no writer. Mutation functions invoked on a target without a writer SHALL throw `:eacl/unsupported-capability` with `:capability :write` before any planning or submission.

#### Scenario: Mutation through a writable acl
- **WHEN** a schema or relationship mutation is invoked on a writable `acl`
- **THEN** the shared write pipeline and token contract apply

#### Scenario: Mutation through a snapshot
- **WHEN** any mutation function receives a snapshot
- **THEN** it throws `:eacl/unsupported-capability` with `:capability :write`
- **AND** no planning basis is acquired and no transaction is submitted

#### Scenario: Read-only acl
- **WHEN** an `acl` is constructed with `:read-only? true`
- **THEN** reads and snapshot selection work
- **AND** mutations throw the same `:eacl/unsupported-capability` error

### Requirement: Runtime sharing by cache class
Snapshots created from one `acl` SHALL share its runtime registries only through keys that include complete basis identity or a valid proof. Ordinary-class snapshots MAY use the exact-basis tier and managed proof-backed lifting under the lineage-scoped frame rule; historical-class snapshots MAY use only exact-basis entries; subproblem and projection state SHALL be isolated per basis generation.

#### Scenario: Two snapshots at different bases
- **WHEN** two snapshots of different revisions share one runtime
- **THEN** each observes only its own exact-basis entries, managed entries admitted by the lifting rule, and its own subproblem store
- **AND** neither receives an answer, projection, or continuation valid only for the other basis

#### Scenario: Historical snapshot
- **WHEN** a snapshot has basis kind `:as-of`
- **THEN** its reads probe and publish only the exact-basis tier

#### Scenario: Evicted generation
- **WHEN** a retained snapshot's basis generation has been evicted from the bounded retained set
- **THEN** its next read recomputes and republishes without error

### Requirement: Cursor continuation across targets
A cursor SHALL be bound to the basis that minted it. Presenting it to the same snapshot SHALL continue directly; presenting it to an `acl` SHALL use the documented continuation decision, including exact reconstruction through the source where supported; presenting it to a different snapshot SHALL continue only when the managed lifting rule admits its proof at that basis and otherwise throw `:eacl.consistency/basis-conflict` with `:source :cursor`, without any source acquisition.

#### Scenario: Same snapshot
- **WHEN** a cursor minted by a snapshot is consumed on that snapshot
- **THEN** pagination continues with no source operation and no proof read beyond the cursor's own validation

#### Scenario: Different snapshot without admissible proof
- **WHEN** a cursor bound to another basis is consumed on a snapshot whose basis does not admit the cursor's proof
- **THEN** EACL throws `:eacl.consistency/basis-conflict` before evaluation
- **AND** does not reconstruct or switch bases

### Requirement: Snapshot lifecycle
Every snapshot SHALL expose idempotent `eacl/release!` and `eacl/released?`. Releasing a snapshot over an owned native basis SHALL release the native resource at most once; releasing a borrowed basis SHALL release EACL state only. Any read or metadata access after release SHALL throw `:eacl/snapshot-released` before adapter or runtime access. A snapshot over a source that declares thread affinity SHALL be readable and releasable only on its acquiring thread.

#### Scenario: Owned snapshot released twice
- **WHEN** `eacl/release!` is called twice on a snapshot over an owned native read transaction
- **THEN** the native resource is released exactly once and the second call returns without error

#### Scenario: Borrowed snapshot released
- **WHEN** a Datomic, Datahike, or DataScript snapshot is released
- **THEN** no connection, database value, or shared runtime is closed

#### Scenario: Use after release
- **WHEN** any read is invoked on a released snapshot
- **THEN** EACL throws `:eacl/snapshot-released` before backend work

#### Scenario: Thread affinity
- **WHEN** a snapshot over a thread-affine owned basis is read from another thread
- **THEN** EACL throws the source's typed execution-constraint error before backend work

#### Scenario: Optional owned-snapshot retention bound
- **WHEN** a Datalevin `acl` is configured with `:maximum-snapshot-retention-ms` and a snapshot exceeds that age
- **THEN** its next read or metadata access releases the owned native reader on the acquiring thread
- **AND** fails with dual-classified `:eacl/snapshot-retention-exceeded` before adapter, cache, or evaluation work

### Requirement: Lifecycle rotation and retained snapshots
Rotating an `acl`'s source lifecycle SHALL NOT invalidate a retained snapshot's ability to evaluate at its immutable basis, SHALL make that snapshot's basis generation unreachable from the runtime, and SHALL scope its tokens and cursors to the pre-rotation lifecycle.

#### Scenario: Rotation after capture
- **WHEN** `expire-cache!` rotates the lifecycle while a snapshot is retained
- **THEN** reads through the snapshot remain correct for its basis
- **AND** tokens it issued are rejected by post-rotation selection as belonging to another lifecycle

### Requirement: Cross-process basis exchange
Basis tokens SHALL be authenticated, source-scoped, branch-scoped, lifecycle-scoped, and sufficient for any `acl` sharing the keyring, source, and lifecycle to validate the named revision without process-local state. A source with exact reconstruction SHALL select the identified basis. A current-only source SHALL accept the token as an at-least floor when its current basis satisfies it and SHALL return a typed capability or freshness failure when it cannot reconstruct the exact historical basis. Datomic, Datahike, and DataScript SHALL use a documented default source lifecycle so reader and writer processes interoperate without configuration. Datalevin SHALL require an explicitly supplied persisted lifecycle shared by every process for the source.

#### Scenario: Writer token selects a reader snapshot
- **WHEN** a token returned by a write in one process is presented as `at-exact-snapshot` to `eacl/snapshot` in another process sharing keyring, source, and lifecycle
- **AND** the source advertises exact reconstruction
- **THEN** the reader selects the identical basis
- **AND** performs no current-head acquisition on a backend that supports selection by exact locator

#### Scenario: Writer token reaches a current-only reader
- **WHEN** a token returned by a write in one runtime is presented as `at-least-as-fresh` to a current-only reader runtime sharing keyring, source, and lifecycle
- **THEN** the reader validates and satisfies the portable floor without any process-local token registry

#### Scenario: Foreign or tampered token
- **WHEN** a token is malformed, fails authentication, or names another source, branch, or lifecycle
- **THEN** EACL throws the typed token or scope error before any selection

### Requirement: Simple acl-first experience
The documented quickstart SHALL require only a backend connection, one `make-client` call, and the public `eacl/*` functions. Snapshot use SHALL add at most one construction expression before the same read calls.

#### Scenario: Quickstart
- **WHEN** a new consumer follows the documented setup for any supported backend
- **THEN** it writes and reads through the `acl` without learning the runtime, source, or writer decomposition

#### Scenario: Optional snapshot workflow
- **WHEN** that consumer wants several reads at one basis
- **THEN** it creates one snapshot with `eacl/snapshot`, `eacl/with-snapshot`, or the backend constructor and passes it to the same read functions

### Requirement: SpiceDB-shaped operations
The reader and writer capabilities SHALL keep SpiceDB-shaped request and response maps and request-scoped consistency for `check-permission`, `read-schema`, `read-relationships`, `lookup-resources`, `lookup-subjects`, `expand-permission-tree`, `write-schema!`, and `write-relationships!`. Counts, backward pagination, `delete-object!`, snapshots, basis metadata, and native accessors SHALL be documented as EACL extensions that a remote adapter MAY emulate or MUST refuse with `:eacl/unsupported-capability`.

#### Scenario: Remote adapter
- **WHEN** a remote adapter implementing the reader and writer protocols receives a SpiceDB-shaped public read
- **THEN** the same normalized request semantics apply and the adapter performs the corresponding RPC

#### Scenario: Extension refused remotely
- **WHEN** a remote adapter receives an EACL-only operation it does not emulate
- **THEN** it throws `:eacl/unsupported-capability` rather than an untyped failure

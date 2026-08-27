## MODIFIED Requirements

### Requirement: One shared client orchestration
The public authorization-target orchestration SHALL be implemented once in the core module as the shared `Acl` and `Snapshot` implementations, parameterized by three backend roles — basis adapter, basis source, and writer — and per-backend construction options. It SHALL normalize each public request once, resolve request-scoped consistency on `acl` into one transient snapshot, route both transient and retained snapshots through `eacl.request.context/make-context`, execute retained-snapshot reads with no acquisition, run one write pipeline with writer-declared retry and batching, and share cursor, cache, validation, deadline, cancellation, lifecycle, and integrity behaviour across backends. Backend modules SHALL contain only genuinely backend-specific code: immutable query primitives, basis-kind classification, basis selection and native lifecycle, schema and attribute installation, and transaction submission.

#### Scenario: Fork elimination
- **WHEN** the Datomic, Datahike, DataScript, and Datalevin modules are compared after unification
- **THEN** none contains a per-backend authorization target, consistency resolution, cursor codec, page cache, result-context capture, filter validation, integrity walking, or endpoint-pair encoding

#### Scenario: Acl and snapshot share one semantic path
- **WHEN** an `acl` selects a transient snapshot and a retained snapshot evaluates the equivalent request at the same basis
- **THEN** both execute the same engine, cache, cursor, validation, deadline, and error pipeline after selection

#### Scenario: Datomic relationship pages on the shared engine
- **WHEN** Datomic serves a relationship page through an `acl` or a snapshot
- **THEN** it executes through the shared relationship planner/executor, not a private reimplementation

### Requirement: Uniform construction surface
`make-client` SHALL accept one documented option map across backends, SHALL return the ordinary `acl`, SHALL accept `:read-only? true` to omit the writer, and SHALL perform no basis acquisition during construction. Per-backend extensions SHALL be explicitly namespaced and documented, equivalent options SHALL share names and semantics, unknown-option errors SHALL be uniform, and each backend supporting application-owned immutable database values SHALL expose a `snapshot` constructor and a native database accessor with the same names and semantics.

#### Scenario: Switching backends
- **WHEN** a consumer moves a valid Datomic `acl` configuration to Datahike or DataScript, changing only the connection argument and any documented per-backend extension
- **THEN** construction succeeds without renaming semantically identical options

#### Scenario: Construction acquires nothing
- **WHEN** `make-client` runs with source acquisition instrumented
- **THEN** zero current, authoritative, at-least, or exact acquisitions are observed

#### Scenario: Direct snapshot construction
- **WHEN** equivalent admissible database values are passed to each backend's `snapshot` constructor with corresponding `acl` values
- **THEN** each returns the common public snapshot capability without acquiring the `acl`'s current basis

## ADDED Requirements

### Requirement: Backend roles are separately certified
The backend contract SHALL consist of a basis adapter bound to one immutable database value, a basis source bound to a connection or store, and an optional writer bound to a connection. Each role SHALL be validated at construction for its complete declared operation set, and a missing declared operation SHALL fail deterministically at construction rather than at first invocation.

#### Scenario: Adapter reaches no connection
- **WHEN** the basis adapter's operation inventory is examined
- **THEN** it contains identity, basis kind, revision, schema, relationship scan, identity conversion, and proof operations for one value
- **AND** contains no operation able to select another basis or submit a transaction, and no captured connection

#### Scenario: Source selects, adapter reads
- **WHEN** an `acl` resolves request-scoped consistency
- **THEN** its basis source returns one native value and ownership
- **AND** all authorization work after that boundary uses only the basis adapter over that value

#### Scenario: Role validation
- **WHEN** a backend module registers an adapter, source, or writer missing a declared operation
- **THEN** `make-client` throws a typed construction error naming the role and operation

### Requirement: Basis sources own selection and native lifecycle
A basis source SHALL declare its capabilities, ownership policy, and execution constraints from configuration, SHALL implement current, authoritative, at-least, and exact acquisition where supported, SHALL return each acquisition as one native value with ownership and a release token, and SHALL release owned resources idempotently.

#### Scenario: Static profile without acquisition
- **WHEN** a source's capabilities, scope, or constraints are read
- **THEN** no acquisition occurs

#### Scenario: Datalevin acquisition reads revision bounds only
- **WHEN** the Datalevin source wraps a newly opened owned read snapshot
- **THEN** it obtains `:max-tx` and `:max-eid` through the maintained fork's revision-only snapshot API
- **AND** it does not request full snapshot metadata or inspect, copy, compare, or fingerprint the physical schema

#### Scenario: Unsupported mode
- **WHEN** an unsupported acquisition mode is requested
- **THEN** the source throws `:eacl/unsupported-capability` before any native operation

### Requirement: Shared write pipeline
The core module SHALL implement one write pipeline: acquire one current planning basis, plan against it, submit through the writer, derive the response token from the committed value, and release the planning basis. The writer SHALL declare contention classification, `:max-attempts`, and `:max-transaction-size`; the pipeline SHALL re-plan on writer-classified contention up to the declared attempts and SHALL batch object deletion to the declared size.

#### Scenario: Contention retry
- **WHEN** the writer classifies a submission failure as contention
- **THEN** the pipeline re-plans on a fresh current basis and retries, throwing `:eacl/relationship-contention` after the declared attempts

#### Scenario: Batched deletion
- **WHEN** an object deletion exceeds the writer's declared transaction size
- **THEN** the pipeline submits successive batches, each re-planned on the then-current basis, and reports the total retraction count

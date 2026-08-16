## ADDED Requirements

### Requirement: Shared v8 authorization semantics
The `eacl` module SHALL provide one backend-neutral implementation of the v8 authorization algorithms used by the Datomic, DataScript, and Datahike adapters.

#### Scenario: Equivalent seeded databases
- **WHEN** equivalent schema and relationships are seeded in each supported backend
- **THEN** permission checks, lookups, counts, filters, and error results are behaviorally equivalent for every consistency mode that the adapter declares as supported

#### Scenario: Datomic extraction
- **WHEN** reusable v8 logic is moved from the Datomic module into `eacl`
- **THEN** the Datomic public API, persisted application data, and v8 behavior remain compatible with the `release/v8.0` baseline

### Requirement: Recursive permission schemas
The shared engine SHALL compile and evaluate permission graphs containing self-recursive and mutually recursive permission references without discarding cycle edges.

#### Scenario: Self-recursive hierarchy
- **WHEN** a permission reaches itself through a finite hierarchy of relationship tuples
- **THEN** the engine returns every reachable authorized object exactly once

#### Scenario: Mutually recursive permissions
- **WHEN** two or more permissions form a strongly connected component
- **THEN** the engine evaluates the component to a fixed point without infinite recursion

#### Scenario: Recursive denial
- **WHEN** a recursive graph contains no path from the subject to the requested object
- **THEN** the authorization result is false and traversal terminates within the configured safety limits

### Requirement: Deterministic resumable traversal
The shared engine SHALL produce deterministic traversal order and resumable continuation state for both acyclic and recursive authorization graphs.

#### Scenario: Duplicate recursive paths
- **WHEN** an object is reached by multiple direct, arrow, or recursive paths
- **THEN** it appears once in the result sequence and pagination does not skip or repeat it

#### Scenario: Safety ceiling reached
- **WHEN** configured depth, work, or result safety limits are exhausted
- **THEN** the engine returns the specified typed limit outcome rather than hanging, overflowing the stack, or silently authorizing

### Requirement: Relay lookup contract
DataScript and Datahike SHALL implement the v8 forward and reverse Relay lookup contract, including `first`, `last`, `after`, `before`, page information, and cursor validation.

#### Scenario: Forward page
- **WHEN** a caller requests a bounded page with `first` and an optional `after` cursor
- **THEN** the adapter returns the next deterministic page and accurate page-boundary information

#### Scenario: Reverse page
- **WHEN** a caller requests a bounded page with `last` and an optional `before` cursor
- **THEN** the adapter returns the preceding deterministic page in canonical result order

#### Scenario: Empty or unknown anchor
- **WHEN** the subject, resource, relation, or permission anchor yields no authorized objects
- **THEN** the result data is empty, page boundaries are false, and no meaningful continuation cursor is emitted

#### Scenario: Invalid pagination arguments
- **WHEN** a caller supplies an invalid cursor or incompatible Relay arguments
- **THEN** the adapter returns the same typed v8 error category as the Datomic implementation

### Requirement: V8 count and mutation behavior
DataScript and Datahike SHALL implement v8 count limits, filter validation, relationship mutations, and object deletion with the same public semantics as Datomic.

#### Scenario: Bounded count
- **WHEN** an authorized count exceeds the requested count limit
- **THEN** the result reports the bounded count and truncation state required by the v8 protocol

#### Scenario: Object deletion
- **WHEN** `delete-object!` removes an object
- **THEN** its owned relationships and authorization visibility are removed according to the v8 deletion contract

#### Scenario: Invalid filter
- **WHEN** a lookup receives a filter that is invalid for the requested resource type
- **THEN** it fails with the corresponding typed v8 validation error before returning data

### Requirement: Explicit backend capabilities
Each adapter SHALL declare the consistency, snapshot, cursor, transaction, and cache-proof capabilities it actually supports, and the shared engine SHALL reject unsupported operations explicitly.

#### Scenario: Fully consistent in-memory snapshot
- **WHEN** DataScript or Datahike handles an operation against a supported current immutable database value
- **THEN** the complete operation observes that selected snapshot

#### Scenario: Unsupported historical consistency
- **WHEN** a caller requests a historical or other consistency mode that an adapter cannot guarantee
- **THEN** the adapter raises a typed unsupported-capability error rather than approximating the requested guarantee

### Requirement: Backend data-access boundary
Shared authorization code SHALL depend on documented backend operations and SHALL NOT inspect Datomic, DataScript, or Datahike implementation records or index tuple layouts directly.

#### Scenario: Backend-specific seek
- **WHEN** traversal requires an index seek, reference resolution, snapshot selection, or transaction
- **THEN** the shared engine invokes the adapter operation and the backend module owns the storage-specific implementation

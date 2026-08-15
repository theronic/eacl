## ADDED Requirements

### Requirement: Backend-neutral cache store
The `eacl` module SHALL define the cache store contract, entry lifecycle, serialization boundary, and validation flow without depending on a database implementation.

#### Scenario: Alternate cache store
- **WHEN** a consumer supplies an implementation of the shared cache store contract
- **THEN** any supported adapter can use it without importing Datomic cache namespaces

#### Scenario: Cache disabled
- **WHEN** no cache store is configured
- **THEN** authorization behavior remains correct and equivalent to an uncached execution

### Requirement: Exact cache validity proof
Every reusable authorization cache entry SHALL be associated with a schema proof and relationship proofs sufficient to demonstrate that all dependencies used by the result are unchanged in the selected backend snapshot.

#### Scenario: Cache hit
- **WHEN** the schema proof and every relevant relationship proof still match
- **THEN** the cached authorization result or continuation may be reused

#### Scenario: Relevant relationship write
- **WHEN** a relationship on which the cached result depends is created, deleted, or replaced
- **THEN** its proof changes atomically with the mutation and the stale entry is not reused

#### Scenario: Schema write
- **WHEN** relation or permission schema affecting a cached result changes
- **THEN** the schema proof changes and the stale entry is not reused

### Requirement: Dependency-scoped invalidation
The cache SHALL retain reusable entries across writes that cannot affect the entry's compiled schema or relationship dependency set.

#### Scenario: Unrelated relationship write
- **WHEN** a relationship outside a cached entry's dependency set changes
- **THEN** the entry remains valid if all recorded proofs still match

#### Scenario: Unrelated schema
- **WHEN** schema for an unrelated resource type changes
- **THEN** entries whose compiled dependency set excludes that schema remain reusable

### Requirement: Multi-connection proof visibility
Datahike and DataScript cache proofs SHALL be derived from database-visible state or another exact snapshot-bound mechanism, not only from a process-local listener or counter.

#### Scenario: Datahike write from another connection
- **WHEN** one Datahike connection mutates a relevant relationship and another connection evaluates a cached request against the updated database
- **THEN** the second connection observes a changed proof and does not reuse the stale entry

#### Scenario: Snapshot-bound validation
- **WHEN** an operation validates an entry against a selected immutable database value
- **THEN** all proof comparisons and the authorization execution refer to that same logical snapshot

### Requirement: Recursive and paginated cache correctness
Cache entries SHALL preserve enough deterministic dependency and continuation information to resume recursive Relay traversal without changing authorization results.

#### Scenario: Cached recursive continuation
- **WHEN** a subsequent page resumes a recursive lookup from a valid cached continuation
- **THEN** it returns the same objects and page boundaries as an uncached traversal from the same snapshot

#### Scenario: Recursive dependency changes
- **WHEN** any relation used by a cached recursive traversal changes
- **THEN** the continuation is rejected or recomputed before returning another page

### Requirement: Fail-closed cache operation
Cache store, decoding, proof, or validation failures SHALL never grant authorization or return unvalidated cached data.

#### Scenario: Cache store unavailable
- **WHEN** the configured cache store throws or is unavailable
- **THEN** the request falls back to uncached authorization when safe, or fails with a typed operational error

#### Scenario: Corrupt cache entry
- **WHEN** a cached entry cannot be decoded or validated
- **THEN** it is treated as a miss and is not used to authorize or paginate

### Requirement: Adapter-specific proof optimization
The shared cache contract SHALL permit an adapter to use a backend-native proof representation only when it satisfies the shared exactness contract and remains opaque to the shared engine.

#### Scenario: Datomic transaction proof
- **WHEN** Datomic uses its v8 transaction-based relation proof
- **THEN** the shared cache validator compares the opaque proof without requiring other adapters to reproduce Datomic transaction semantics

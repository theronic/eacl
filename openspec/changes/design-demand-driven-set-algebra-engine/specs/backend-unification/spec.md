## ADDED Requirements

### Requirement: Snapshots expose canonical permission expressions by capability
The backend contract SHALL expose the authoritative identity and canonical versioned payload for one permission on the selected immutable snapshot. Core SHALL derive metadata and any runtime fingerprint locally. Every v8 permission uses that representation, including union-only permissions, so adapter construction or schema access MUST fail closed when the selected adapter lacks the capability.

#### Scenario: Expression capability is missing
- **WHEN** a selected adapter cannot read canonical v8 expression definitions
- **THEN** adapter construction or schema access returns a typed missing-capability failure before authorization

### Requirement: Optional batched direct membership is exact and aligned
An adapter MAY advertise bounded batched direct membership. For one immutable snapshot and one normalized physical descriptor, it SHALL accept a bounded vector of distinct typed candidates and return an aligned Boolean vector exactly equal to certified scalar direct membership for each candidate.

#### Scenario: Batched and scalar differential
- **WHEN** the same candidate vector is evaluated through batched and scalar direct membership
- **THEN** every aligned Boolean and typed failure classification agrees

#### Scenario: Adapter omits batching
- **WHEN** an expression-capable adapter does not advertise batched membership
- **THEN** the engine uses exact scalar fallback without changing semantics, order, limits, or cursor boundaries

### Requirement: Batched operations are basis-stable and atomic
A batched membership operation SHALL use the adapter instance's already selected immutable basis. Cancellation, malformed cardinality, out-of-order response metadata, or provider failure MUST return one typed failure and MUST NOT publish or cache a partial aligned result.

#### Scenario: Basis head advances concurrently
- **WHEN** the backend head changes while a batch is evaluated against an earlier selected snapshot
- **THEN** every Boolean is evaluated against the earlier snapshot and the batch performs no implicit reselection

### Requirement: Durable expression storage excludes derived metrics
Built-in and third-party expression capabilities SHALL persist only authoritative permission identity and canonical versioned payload. Separate expression-format, expression-digest, policy-digest, admission-limit, source/DAG dimension, aggregate-count, relationship-cardinality, and physical-cost fields MUST NOT be required durable expression fields. Built-in writers SHALL never assert them.

#### Scenario: Adapter reads an expression after cache eviction
- **WHEN** the structural cache has no entry for the selected schema generation
- **THEN** core recomputes and validates dimensions from the bounded payload without requiring adapter metric attributes

### Requirement: Observation telemetry is optional and normalized
An adapter MAY attach bounded operation telemetry to completed scans, probes, or batches. Core SHALL normalize supported telemetry into advisory cache observations and SHALL remain correct when telemetry is missing, partial, or cache-state dependent.

#### Scenario: Adapter has no native statistics API
- **WHEN** a built-in or third-party adapter returns only ordinary values and EACL counters
- **THEN** the same public operations and deterministic baseline physical policy remain available

## ADDED Requirements

### Requirement: Schema generation is certified independently of relationship proofs

Every backend adapter SHALL expose the transactionally persisted EACL schema generation of its selected snapshot through one cheap operation that performs at most one index probe and does not depend on ordered relationship generations, proof frames, or historical reconstruction. The value SHALL advance in the same transaction as every managed schema write and SHALL be unchanged by relationship-only commits. EACL SHALL key every schema-derived artifact by backend, source scope, source lifecycle, engine ABI, and this generation, and SHALL reuse those artifacts across requests on every backend that certifies it, including Datalevin. When a snapshot cannot certify a generation, derived artifacts SHALL be memoized for the request only. Schema-generation equality MUST NOT authorize answer reuse, cursor continuation, cache lifting, snapshot ordering, or historical reconstruction. When an ordered-generation proof also carries a schema stamp, the two MUST agree or EACL SHALL fail with a typed backend-integrity error.

#### Scenario: Relationship-only commits on Datalevin

- **WHEN** two Datalevin snapshots differ only by relationship transactions
- **THEN** the second request reuses the first request's sealed plans and validation catalog
- **AND** instrumentation observes zero definition reads and zero plan seals for the second request

#### Scenario: Managed schema write

- **WHEN** a relation, permission, or arrow definition changes through the managed schema write
- **THEN** the later snapshot certifies a different generation
- **AND** no derived artifact from the earlier generation is eligible

#### Scenario: Uncertified snapshot

- **WHEN** a raw, speculative, filtered, or unstamped database value cannot certify a generation
- **THEN** a request against it still seals each root at most once
- **AND** nothing derived from it is published to a cross-request registry

### Requirement: Basis identity carries no physical schema fingerprint

Complete basis identity SHALL consist of backend, source scope, source lifecycle, native revision, exact locator, and backend snapshot identity. Snapshot acquisition MUST NOT read, copy, or structurally compare the backend's physical attribute schema, and no adapter SHALL advertise a physical schema fingerprint as an EACL identity dimension.

#### Scenario: Datalevin acquisition

- **WHEN** a Datalevin snapshot is acquired
- **THEN** the adapter reads revision metadata only
- **AND** the acquisition allocation gate of `implementation-simplicity-and-performance` applies

### Requirement: Direct-match probes are certified for filtered enumeration

Every built-in adapter SHALL certify that its direct-match operation answers exactly relationship membership for a `(subject, relation, resource)` triple on the selected snapshot, so that the enumerate route of authorized relationship pagination decides a candidate with one probe. An adapter that cannot certify the probe SHALL fail construction or pre-execution validation with the uniform unsupported-capability error.

#### Scenario: Probe agrees with scan

- **WHEN** the certification fixture compares the probe with a bounded scan for every triple in the fixture
- **THEN** the probe answers true exactly for the triples present in the scan

### Requirement: Aggregate authorization orchestration is shared

Validation, snapshot ownership, batch scheduling, windowed filtering for both routes, deadline and cancellation propagation, cache decoration, cursor construction, result ordering, and typed errors for aggregate operations SHALL be implemented once in the core module. Backend modules SHALL expose only certified bounded primitives and metadata and MUST NOT provide private batch or authorized-page evaluators.

#### Scenario: Backend module inventory

- **WHEN** Datomic, Datahike, DataScript, and Datalevin module sources are inspected
- **THEN** none contains a backend-private implementation of `check-permissions` or of either authorized pagination route
- **AND** each reaches the same core orchestration through its declared primitives

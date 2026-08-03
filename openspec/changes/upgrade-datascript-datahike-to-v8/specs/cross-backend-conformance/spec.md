## ADDED Requirements

### Requirement: Corrected v7 Datahike merge gate
Datahike PR #81 SHALL be merged only after its v7 behavior and standalone module are verified against the current DataScript port and all discovered correctness defects are fixed.

#### Scenario: Shared v7 behavior
- **WHEN** the Datahike adapter runs the shared v7 contract against keyword attributes and numeric attribute references
- **THEN** permission checks, schema invalidation, lookups, counts, and relationship mutations pass without failures

#### Scenario: Standalone Datahike module
- **WHEN** the Datahike module is resolved, loaded, tested, and built using only its own dependency basis
- **THEN** its build entry point and contract-test paths resolve without root-workspace dependencies masking omissions

#### Scenario: Empty reverse lookup
- **WHEN** a v7 reverse lookup or count uses an unknown resource anchor
- **THEN** it returns the canonical empty shape without a non-resumable or meaningless cursor

#### Scenario: Merge readiness
- **WHEN** the corrected PR has shared tests, Datahike-specific tests, isolated build verification, and required CI checks passing
- **THEN** it is eligible to merge into the latest DataScript branch before v8 integration begins

### Requirement: Shared public API conformance
The repository SHALL provide a backend-neutral v8 conformance suite that can be invoked by Datomic, DataScript, and Datahike adapter tests over equivalent fixtures.

#### Scenario: Core operation matrix
- **WHEN** an adapter runs the suite
- **THEN** schema round trips, relationship CRUD, deletion, permission checks, lookups, counts, Relay pagination, filters, unknown anchors, and typed errors are verified

#### Scenario: No backend-only expected results
- **WHEN** a behavior is part of the common v8 capability contract
- **THEN** its expected result is defined once in shared test support rather than copied into three adapter-specific suites

### Requirement: Recursive conformance
The shared suite SHALL verify recursive authorization independently of any one backend's traversal implementation.

#### Scenario: Recursive fixture matrix
- **WHEN** the recursive suite runs
- **THEN** it covers self cycles, mutual cycles, deep chains, duplicate paths, denials, forward pages, reverse pages, and safety ceilings

### Requirement: Cache conformance
The shared suite SHALL exercise cache hits, misses, proof validation, invalidation, fallback, and recursive continuation behavior for every cache-capable adapter.

#### Scenario: Relevant and unrelated writes
- **WHEN** the cache matrix alternates relevant and unrelated relationship and schema writes
- **THEN** stale entries are rejected while entries with unchanged dependency proofs remain reusable

#### Scenario: Store failure
- **WHEN** a test cache store throws during read, write, or decoding
- **THEN** the adapter demonstrates the fail-closed behavior required by the cache contract

### Requirement: Independent correctness oracle
Cross-backend verification SHALL include a small reference evaluator, property-based model, or otherwise independent oracle so that agreement between adapters is not the sole evidence of correctness.

#### Scenario: Seeded differential cases
- **WHEN** generated or curated schemas and relationship graphs are evaluated
- **THEN** each adapter's authorization set is compared with the independent expected set

#### Scenario: Reproducible failure
- **WHEN** a generated case disagrees with the oracle
- **THEN** the test reports a reproducible seed and minimized or inspectable fixture

### Requirement: Adapter-specific verification
The common suite SHALL be complemented by tests for capabilities and failure modes unique to each backend and runtime.

#### Scenario: Datahike representations
- **WHEN** Datahike-specific tests run
- **THEN** keyword and numeric attribute references, tuple derivation, and multi-connection cache proof visibility are covered

#### Scenario: DataScript runtimes
- **WHEN** DataScript-specific tests run
- **THEN** both supported Clojure and ClojureScript runtime paths are verified

#### Scenario: Datomic guarantees
- **WHEN** Datomic-specific tests run
- **THEN** consistency selection, historical bases, encrypted cursors, transaction proofs, and database compatibility remain covered

### Requirement: Module and integration CI
CI SHALL verify each module in isolation and the combined non-benchmark workspace before the v8 integration pull request is considered ready.

#### Scenario: Isolated module checks
- **WHEN** CI evaluates `eacl`, `eacl-datomic`, `eacl-datascript`, and `eacl-datahike`
- **THEN** each module loads, tests, and builds using its declared source paths and direct dependencies

#### Scenario: Release candidate integration
- **WHEN** all implementation and verification tasks are complete
- **THEN** the integration branch is proposed to `release/v8.0`, the head branch of PR #84, with all three adapters and the shared core in one reviewable pull request

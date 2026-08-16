## MODIFIED Requirements

### Requirement: Independently consumable modules
The repository SHALL provide backend-neutral `eacl`, Datomic `eacl-datomic`, DataScript `eacl-datascript`, and Datahike `eacl-datahike` modules, each with its own source root, dependency declaration, build entry point, and documentation.

#### Scenario: Core-only consumer
- **WHEN** a consumer resolves only the `eacl` module
- **THEN** the public protocol, records, schema model, parser, consistency descriptors, shared engine, and cache contracts load without Datomic, DataScript, or Datahike on the classpath

#### Scenario: Adapter consumer
- **WHEN** a consumer resolves one adapter module
- **THEN** that adapter declares the backend-neutral module and only its adapter-specific direct runtime dependencies

### Requirement: Backend-neutral six-function SPI
The core module SHALL preserve compatibility with the map-based backend SPI functions `cache-stamp`, `relation-defs`, `permission-defs`, `subject->resources`, `resource->subjects`, and `direct-match?`, while documenting the additional adapter operations and capability declarations required by v8 snapshots, Relay traversal, deletion, and cache proofs.

#### Scenario: Existing third-party backend implementation
- **WHEN** a backend supplies only the established six SPI functions with their existing argument shapes
- **THEN** the shared engine can continue to perform the supported v7 permission checks and traversals without depending on that backend's datom or index APIs

#### Scenario: V8 adapter implementation
- **WHEN** a backend opts into the v8 shared engine
- **THEN** it supplies the documented v8 operations and capability declarations without exposing implementation records or index tuple layouts to shared code

#### Scenario: Datahike PR upgrade
- **WHEN** the corrected and merged Datahike PR #81 module is brought onto `release/v8.0`
- **THEN** its data-access layer is adapted incrementally rather than replaced with a backend-specific copy of the v8 authorization algorithms

### Requirement: Shared backend contract
The workspace SHALL provide backend-neutral v7 compatibility and v8 conformance support that adapter tests can invoke against seeded schema and relationship data.

#### Scenario: DataScript and Datahike contract
- **WHEN** the DataScript or Datahike adapter seeds a shared contract fixture
- **THEN** schema round-trip, direct, arrow, recursive permission checks, lookup/count operations, Relay behavior, caching, and relationship writes are verified for its declared capabilities

#### Scenario: Datomic contract and v8-specific tests
- **WHEN** the Datomic adapter is validated
- **THEN** the shared v8 behavioral contract runs alongside Datomic-specific consistency, cursor, cache-proof, and regression tests

### Requirement: Workspace build and test entry points
The root workspace SHALL expose build and nREPL test paths for all four modules, and CI SHALL exercise their isolated builds plus the combined non-benchmark suite.

#### Scenario: Root development workflow
- **WHEN** a developer starts the configured nREPL from the root workspace
- **THEN** all shared, Datomic, DataScript, and Datahike source and test namespaces are available

#### Scenario: Isolated module workflow
- **WHEN** a module's build or tests run from that module's own dependency basis
- **THEN** all direct source, test-support, and build dependencies resolve without relying on the root basis

#### Scenario: CI validation
- **WHEN** the repository test workflow runs
- **THEN** it exercises the shared contract and module suites and excludes only tests explicitly marked as benchmarks

### Requirement: Upgrade documentation
Documentation SHALL explain v8 module selection for Datomic, DataScript, Datahike, core-only, and third-party backend consumers, including supported consistency capabilities and the v7-to-v8 API migration.

#### Scenario: Existing monolithic consumer
- **WHEN** a current EACL v8 Datomic consumer reads the upgrade instructions
- **THEN** it can identify the Datomic module dependency without changing its existing public namespace usage

#### Scenario: DataScript or Datahike consumer
- **WHEN** a v7 DataScript or Datahike consumer reads the upgrade instructions
- **THEN** it can migrate legacy pagination, count, cache, recursive schema, and consistency usage to the adapter's declared v8 contract

#### Scenario: Third-party adapter author
- **WHEN** a backend author reads the extension guidance
- **THEN** the legacy SPI, v8 adapter operations, capability declarations, cache proof contract, and conformance-test entry points are explicit

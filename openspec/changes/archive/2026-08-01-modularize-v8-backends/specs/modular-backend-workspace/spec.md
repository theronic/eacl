## ADDED Requirements

### Requirement: Independently consumable modules
The repository SHALL provide backend-neutral `eacl`, Datomic `eacl-datomic`, and DataScript `eacl-datascript` modules, each with its own source root, dependency declaration, build entry point, and documentation.

#### Scenario: Core-only consumer
- **WHEN** a consumer resolves only the `eacl` module
- **THEN** the public protocol, records, schema model, parser, consistency descriptors, shared engine, and backend SPI load without Datomic or DataScript on the classpath

#### Scenario: Adapter consumer
- **WHEN** a consumer resolves one adapter module
- **THEN** that adapter declares the backend-neutral module and only its adapter-specific runtime dependencies

### Requirement: Stable public namespaces
The modular workspace SHALL preserve the existing public `eacl.*` namespace names and public Datomic client entry points while changing their physical source roots.

#### Scenario: Existing Datomic require forms
- **WHEN** an existing v8 Datomic application changes its dependency selection to `eacl-datomic`
- **THEN** its `eacl.core`, `eacl.datomic.core`, `eacl.datomic.schema`, and related require forms continue to resolve

### Requirement: V8 Datomic behavior parity
The Datomic module SHALL retain the behavior of the `release/v8.0` implementation, including consistency selection, encrypted pagination, forward and reverse lookup, count limits, object deletion, schema and relationship safety, and authorization caching.

#### Scenario: Full v8 regression suite
- **WHEN** the non-benchmark Datomic and migration tests run from module paths
- **THEN** they complete without failures or errors

#### Scenario: No storage migration from modularization
- **WHEN** an existing v8 Datomic database is used with the modular Datomic artifact
- **THEN** the modularization introduces no new persisted attribute, migration step, or cursor format change

### Requirement: Backend-neutral six-function SPI
The core module SHALL expose the map-based backend SPI functions `cache-stamp`, `relation-defs`, `permission-defs`, `subject->resources`, `resource->subjects`, and `direct-match?` with the argument shapes used by the DataScript module and Datahike PR #81.

#### Scenario: Existing third-party backend implementation
- **WHEN** a backend supplies the six SPI functions with the established argument shapes
- **THEN** the shared engine can perform permission checks and resource/subject traversal without depending on that backend's datom or index APIs

#### Scenario: Datahike PR upgrade
- **WHEN** PR #81 rebases its `modules/eacl-datahike` tree onto this change
- **THEN** it does not need to redesign its backend primitive layer or replace the six-function SPI

### Requirement: Shared backend contract
The workspace SHALL provide backend-neutral contract support that adapter tests can invoke against seeded schema and relationship data.

#### Scenario: DataScript contract
- **WHEN** the DataScript adapter seeds the shared contract fixture
- **THEN** schema round-trip, direct and arrow permission checks, lookup/count operations, and relationship writes are verified

#### Scenario: Datomic contract and v8-specific tests
- **WHEN** the Datomic adapter is validated
- **THEN** the shared behavioral contract or equivalent public API coverage runs alongside Datomic-specific v8 regression tests

### Requirement: Dependency isolation
Each published module SHALL declare all dependencies it directly requires and SHALL NOT rely on the root workspace to mask missing dependencies.

#### Scenario: Isolated module load
- **WHEN** each module's namespaces are loaded using that module's own dependency basis
- **THEN** namespace loading succeeds without undeclared root-only dependencies

#### Scenario: Core dependency graph
- **WHEN** the backend-neutral module dependency graph is inspected
- **THEN** it contains neither Datomic nor DataScript

### Requirement: Consumer-owned logging
Published EACL module dependencies SHALL NOT select Logback or another logging backend for consuming applications.

#### Scenario: Application logging choice
- **WHEN** an application depends on an EACL module
- **THEN** EACL does not add a direct logging implementation or repository-owned logging configuration to the application's runtime

### Requirement: Workspace build and test entry points
The root workspace SHALL expose build and test paths for all modules, and CI SHALL exercise the non-benchmark module suite.

#### Scenario: Root development workflow
- **WHEN** a developer starts the configured nREPL from the root workspace
- **THEN** all module source and test namespaces are available

#### Scenario: CI validation
- **WHEN** the repository test workflow runs
- **THEN** it discovers module tests and excludes only tests explicitly marked as benchmarks

### Requirement: Upgrade documentation
Documentation SHALL explain module selection for Datomic, DataScript, core-only, and third-party backend consumers and SHALL identify the rebase path for Datahike PR #81.

#### Scenario: Existing monolithic consumer
- **WHEN** a current EACL v8 Datomic consumer reads the upgrade instructions
- **THEN** it can identify the new module dependency without changing its public namespace usage

#### Scenario: Datahike contributor
- **WHEN** the PR #81 author reads the backend extension guidance
- **THEN** the expected module path, core dependency, SPI surface, and contract-test entry point are explicit

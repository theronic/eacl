## 1. Establish the Module Workspace

- [x] 1.1 Integrate the latest `origin/eacl/datascript` module layout and preserve the six-function backend SPI
- [x] 1.2 Configure root source, test, nREPL, build, and CI paths for all modules
- [x] 1.3 Define independent runtime dependencies and tools.build entry points for each module

## 2. Preserve the V8 Datomic Backend

- [x] 2.1 Relocate all authoritative v8 Datomic, migration, and supporting source namespaces into `modules/eacl-datomic`
- [x] 2.2 Relocate the complete non-benchmark v8 Datomic and migration tests into the Datomic module
- [x] 2.3 Preserve v8 public namespaces, storage schema, consistency, cursor, object-deletion, and cache behavior

## 3. Extract the Backend-Neutral Core

- [x] 3.1 Update the core protocol, records, shared consistency descriptors, and lazy merge helpers to the v8 public contract
- [x] 3.2 Make schema model and SpiceDB parsing backend-neutral and update Datomic compatibility constructors
- [x] 3.3 Verify the core module has no Datomic or DataScript source/dependency coupling

## 4. Bring DataScript and Third-Party Extension Forward

- [x] 4.1 Keep the DataScript adapter compiling against the v8 core protocol and established SPI
- [x] 4.2 Update shared contract support and adapter contract tests for the supported v8 public behavior
- [x] 4.3 Document the stable SPI and concrete rebase path for Datahike PR #81

## 5. Remove Bundled Logging Selection

- [x] 5.1 Remove direct Logback/logging implementation dependencies and repository-owned Logback configuration
- [x] 5.2 Remove or replace runtime and test logging calls that would force a published logging dependency

## 6. Documentation and Packaging

- [x] 6.1 Update README dependency examples and module-specific usage guidance
- [x] 6.2 Update v8 release notes with modularization and migration guidance
- [x] 6.3 Verify each module's isolated dependency basis and build metadata

## 7. Verification and Delivery

- [x] 7.1 Run focused core, DataScript contract, Datomic contract, cache, pagination, schema, and migration tests through nREPL
- [x] 7.2 Run the complete non-benchmark workspace test suite through nREPL
- [x] 7.3 Review the final diff for v8 parity and PR #81 compatibility, then validate the OpenSpec change
- [x] 7.4 Commit, push, and open a draft PR into `release/v8.0` referencing issue #82 and PR #81

## Why

EACL v8.0 currently publishes a single artifact that couples the public API to Datomic and a logging backend, while the existing DataScript work and Datahike PR #81 already depend on a backend-neutral module and a stable six-function backend SPI. Modularizing on the v8 release branch now lets consumers select only their backend and gives PR #81 a small, explicit upgrade target instead of forcing it to rediscover the package boundary while rebasing across the v8 cache work.

## What Changes

- Split the repository into independently consumable `eacl`, `eacl-datomic`, and `eacl-datascript` modules, with the root `deps.edn` acting as a development workspace.
- Preserve the backend-neutral six-function SPI and shared contract-test location used by Datahike PR #81.
- Move the complete current v8.0 Datomic implementation, including consistency semantics, encrypted pagination, object deletion, and authorization caching, into `eacl-datomic` without changing its public namespaces.
- Bring the DataScript adapter and shared backend engine forward into the v8 workspace, and verify backend-neutral behavior through a shared contract suite.
- Publish each module with dependency metadata that contains only its own runtime requirements.
- Remove EACL's direct logging implementation dependencies and repository-owned Logback configuration so applications remain responsible for selecting and configuring logging.
- Update documentation, build aliases, and CI/test paths for the module workspace and the Datahike extension point.

## Capabilities

### New Capabilities

- `modular-backend-workspace`: Defines independently consumable backend-neutral, Datomic, and DataScript modules; backend contract compatibility; v8 Datomic parity; dependency isolation; and third-party backend extension requirements.

### Modified Capabilities

None.

## Impact

- Source and tests move from top-level `src/` and `test/` paths into module-specific roots while public `eacl.*` namespaces remain stable.
- Root and module `deps.edn` files, tools.build entry points, CI, README guidance, and v8 release notes change.
- Existing Datomic consumers select `modules/eacl-datomic`; DataScript consumers select `modules/eacl-datascript`; backend authors such as PR #81 depend on `modules/eacl` and implement the existing SPI.
- EACL no longer selects a logging implementation for consuming applications.

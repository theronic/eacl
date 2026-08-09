# `eacl`

Core, backend-neutral EACL module.

Responsibilities:

- `eacl.core` protocol and public records
- cursor/token helpers and consistency semantics
- schema IR, parser, validation, and diffing
- validated backend boundary and shared generated authorization engine
- contract test fixtures and backend-neutral tests

This module must not depend on Datomic or a logging backend.

## Dependency and runtime

```clojure
{:deps {dev.eacl/eacl {:mvn/version "8.0.0-SNAPSHOT"}}}
```

The published JAR includes all generated JVM kernel/Dafny runtime classes,
`deps.cljs`, and `EaclKernel.browser.js`. EACL targets Java 26 by default, while
an explicit source/custom build can target Java 8 through 26. The same
platform-neutral class files run on the selected Java release and newer JVMs
without per-JVM artifacts.

Build this module in isolation with `clojure -T:build jar`. Git and
`:local/root` consumers must first follow the explicitly opt-in
[source preparation instructions](../../README.md#source-dependencies-and-formal-tooling).
Maven consumers neither install formal tools nor run verification.

## Backend contract

Backends supply the validated operation map consumed through
`eacl.backend.v8`. It declares consistency, snapshot, cursor, transaction,
cache-proof, and runtime capabilities and implements normalized operations for
snapshot identity, object ID conversion, schema definitions, adjacency,
direct matches, recursive permission nodes, cursor frontier identity, and
schema/relation proofs.

The contract uses logical types and identifiers. Datoms, attribute ids,
database values, and raw index tuples stay inside each adapter. See the
[v8 adapter boundary](../../docs/v8-backend-adapter-boundary.md) for the full
inventory.

The shared contract fixture is
`modules/eacl/test/eacl/contract_support.cljc`. It exercises the same v8 public
API and recursive/cache behavior for Datomic, DataScript, and Datahike and
compares authorization sets with independent semantic oracles. Those oracles
are test code, not selectable production engines.

Application-facing module selection and upgrade notes live in the
[v8 backend and upgrade guide](../../docs/v8-backend-modules-and-upgrade.md).

# `eacl`

Core, backend-neutral EACL module.

Responsibilities:

- `eacl.core` protocol and public records
- cursor/token helpers and consistency semantics
- schema IR, parser, validation, and diffing
- validated backend boundary and the shared stable-discovery authorization engine (hand-written CLJC; the generated Dafny kernel supplies the surrounding pure decisions)
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
snapshot identity, source scope/lifecycle, native revision and order hint,
snapshot selection, exact locators, object ID conversion, schema definitions,
ordered adjacency scans, direct matches, permission nodes, and
ordered-generation proof frames. An adapter without certified proof support
remains a correct exact-current adapter.

The contract uses logical types and identifiers. Datoms, attribute ids,
database values, and raw index tuples stay inside each adapter. See the
[v8 adapter boundary](../../docs/v8-backend-adapter-boundary.md) for the full
inventory and the
[snapshot-provider migration guide](../../docs/v8-snapshot-provider-migration.md)
for owned/borrowed lifecycle and third-party adapter requirements.

The shared contract fixture is
`modules/eacl/test/eacl/contract_support.cljc`. It exercises the same v8 public
API and recursive/cache behavior for Datomic, DataScript, Datahike, and
Datalevin, and compares authorization sets with independent semantic oracles. Those oracles
are test code, not selectable production engines.

## Permission-tree expansion

`eacl.permission-tree` is the portable CLJ/CLJS implementation behind
`IAuthorization/expand-permission-tree`. A request contains `:resource` and
`:permission`, with optional `:consistency`, `:timeout-ms`, and
`:cancellation-token`; a successful response is
`{:expanded-at token :tree-root node}`. Nodes contain exactly one of `:leaf` or
`:intermediate`. Expansion preserves permission/arrow boundaries, empty
branches, duplicate paths, and typed object identity. Vector order is not a
semantic contract.

The kernel consumes definitions, relation values, and ID conversions from one
already-selected immutable adapter, then issues the causal token from that
same adapter. It realizes scans incrementally and fails atomically on a typed
deadline, cycle, codec, adapter-contract, unknown-root, or structural-limit
error. Configure positive `:permission-tree-limits` on the client; per-request
limit overrides are rejected. The five dimensions are `:max-depth`,
`:max-schema-components`, `:max-relationship-values`, `:max-tree-nodes`, and
`:max-leaf-subjects`.

The Dafny file `formal/dafny/PermissionTree.dfy` is a proof-only mathematical
model. The handwritten portable source is covered by reference-evaluator,
CLJ/CLJS property, pinned-SpiceDB-fixture, and cross-backend tests; mechanical
source refinement is not claimed.

All bounded public reads accept an `eacl.core/cancellation-token` through the
request `:cancellation-token` key. Calling `eacl.core/cancel!` causes the next
deadline/traversal/adapter-boundary check to throw
`:eacl.execution/cancelled` without returning a partial answer. Cancellation
is best-effort: it cannot preempt a synchronous adapter call already in
progress, and a completed result may win a race with a late signal. The token
is excluded from semantic cache, continuation, and cursor identity.

Application-facing module selection lives in the
[backend guide](../../docs/v8-backend-modules-and-upgrade.md).

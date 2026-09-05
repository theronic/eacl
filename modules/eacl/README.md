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
{:deps {dev.eacl/eacl {:mvn/version "9.0.0-SNAPSHOT"}}}
```

The published JAR includes all generated JVM kernel/Dafny runtime classes,
`deps.cljs`, and `EaclKernel.browser.js`. Generated kernel classes target Java
25 by default and an explicit source/custom build may compile those classes for
Java 8 through 26. The complete JVM module requires Java 11 or newer because
Caffeine 3.2.4 is its manual cache implementation; Java 17 is the supported
production runtime floor. CLJS/DataScript uses the separate theronic
`cljs-cache` LRU adapter.

Build this module in isolation with `clojure -T:build jar`. Git and
`:local/root` consumers must first follow the explicitly opt-in
[source preparation instructions](../../README.md#source-dependencies-and-formal-tooling).
Maven consumers neither install formal tools nor run verification.

## Backend contract

Backends supply three validated roles. `eacl.backend.v8` is an immutable
basis adapter for identity, basis kind, native revision/locator, object
conversion, schema definitions, ordered adjacency, direct matches, permission
nodes, the memoized `:schema-generation` read, and optional proof frames.
`eacl.backend.source` owns source scope/lifecycle, consistency selection, native
ownership, and release. `eacl.backend.writer` owns planning, submission,
contention classification, retry, and transaction-size bounds. An adapter
without certified proof support remains a correct exact-basis adapter; a nil
schema generation limits derived-plan reuse to one request.

The contract uses logical types and identifiers. Datoms, attribute ids,
database values, and raw index tuples stay inside each adapter. See the
[v8 adapter boundary](../../docs/v8-backend-adapter-boundary.md) for the full
inventory and the
[basis-source migration guide](../../docs/v8-snapshot-provider-migration.md)
for owned/borrowed lifecycle and third-party adapter requirements.

The shared contract fixture is
`modules/eacl/test/eacl/contract_support.cljc`. It exercises the same v8 public
API and recursive/cache behavior for Datomic, DataScript, Datahike, and
Datalevin, and compares authorization sets with independent semantic oracles. Those oracles
are test code, not selectable production engines.

## Aggregate reads

Shared orchestration implements ordered `eacl/check-permissions` batches,
`read-relationships` with an `:authorization` clause, and relationship-filtered
`lookup-resources`/`lookup-subjects`. One request context owns the snapshot,
deadline, cancellation token, schema/root memos, cumulative limits, cursor
state, and release. Backend modules supply only their ordinary certified
operations, including `:schema-generation` and exact direct relationship
membership; they must not add private aggregate evaluators. See [aggregate
authorization](../../docs/aggregate-authorization.md).

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

On deterministic adapters with immutable/injective external identity, exact
public cache probes for point, count, tree, lookup-page, and relationship-page
requests occur before backend ID internalization. Object IDs used by that path
must be bounded canonical scalars/vectors; map/set IDs and custom records fail
closed or use the internal path as appropriate. Query collections are copied
into plain persistent containers. Exact rendered lookup pages contain validated
`SpiceObject` values and relationship pages contain validated `Relationship`
values, and their keys include the complete authenticated consistency
descriptor as well as the exact basis and raw cursor request.

For retained `Snapshot` reads, consistency is asserted per call. The
authenticated exact token or freshness floor for that call refines cursor/cache
context while the Snapshot's backend selection facts remain fixed.

Application-facing module selection lives in the
[backend guide](../../docs/v8-backend-modules-and-upgrade.md).

## Removed (2026-09-02)

Unreferenced vars removed from the core module: `eacl.client.orchestration/can?`
(use `eacl.core/can?`), `eacl.engine.physical/telemetry` (the finished reducer
state carries every counter), `eacl.engine.sealed-plan/local-read-cost` (read
`rank-contract`), `eacl.operator.recursive/check-eids` (use
`check-cached-eids`), `eacl.relationships.endpoint-pair/half-identity`,
`eacl.authorization.batch/root-key`,
`eacl.schema.expression-policy/compatibility-digest` and
`eacl.schema.expression-resolver/resolve-definitions`.

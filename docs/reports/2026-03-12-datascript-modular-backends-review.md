# Review: Critique of the Initial DataScript Modularization Plan

Reviewed plan:
[`docs/plans/2026-03-12-datascript-modular-backends-plan.md`](../plans/2026-03-12-datascript-modular-backends-plan.md)

## Executive Summary

The initial plan is directionally correct, but it is still too close to the
current Datomic-shaped codebase. If DataScript support and optional backends had
been foundational assumptions from the start, EACL would not be modelled as
"a Datomic implementation with shared pieces extracted later." It would be
modelled as:

1. a backend-neutral authorization model and protocol,
2. a backend-neutral execution engine over a narrow storage SPI, and
3. concrete persistence adapters for Datomic and DataScript.

The improved plan should therefore shift from "split the current code" to
"re-found the system around the correct abstractions, then port Datomic and add
DataScript."

## Findings

### 1. The module split is underspecified

The initial plan names three modules, but it does not define the repository
layout, ownership boundaries, or how development will work across them.

Why this matters:

- The current repo has one `deps.edn`, one `build.clj`, and one `src` tree.
- Without a concrete workspace structure, the implementer still has to decide
  whether the root remains publishable, whether modules live under subdirs, and
  how tests are discovered.

Recommendation:

- Convert the repository into a workspace-style monorepo.
- Put each publishable artifact in its own module directory with its own
  `deps.edn`, `src`, `test`, and build entrypoint.
- Make the root `deps.edn` a development aggregator, not the published library.

### 2. Logical authorization model and physical schema are not separated cleanly enough

The initial plan says to move parser and validation into core, but it does not
draw a hard line between:

- logical EACL schema model,
- backend-specific physical storage schema,
- schema persistence and migration semantics.

Why this matters:

- Today `eacl.datomic.schema` mixes all three.
- DataScript explicitly does not have queryable schema or schema migrations in
  the Datomic sense, so the same namespace shape cannot simply be copied.
- A clean redesign would make logical schema a first-class EACL concern and let
  each backend decide how to store it physically.

Recommendation:

- Introduce a backend-neutral schema model package in core:
  relation/permission constructors, parser, validation, diffing, and canonical
  schema serialization.
- Let Datomic and DataScript adapters translate that model into backend-specific
  installation and persistence behavior.

### 3. The shared engine is still described as a refactor of Datomic code

The initial plan frames the engine as an extraction from
`eacl.datomic.impl.indexed`. That is correct historically, but not elegant
architecturally.

Why this matters:

- The current engine is shaped by Datomic names, Datomic tuple attrs, and
  Datomic db identity assumptions.
- If this had been designed from the start, the engine would expose its own
  storage contract and backend adapters would implement it.

Recommendation:

- Define an explicit storage SPI first.
- Rewrite the indexed traversal layer against the SPI, even if much of the code
  is copy-adapted from the Datomic implementation.
- Treat Datomic as the first adapter, not as the source of truth.

### 4. Compatibility strategy is too thin

The initial plan says to keep Datomic namespaces thin, but it does not define
compatibility promises for:

- public namespaces,
- public constructors,
- existing require sites,
- docs and examples,
- migration path for consumers.

Why this matters:

- The repo and consumer app already require `eacl.datomic.core`,
  `eacl.datomic.impl`, and `eacl.datomic.schema`.
- A good redesign should preserve namespace-level compatibility while changing
  dependency coordinates.

Recommendation:

- Keep the public Datomic namespace names stable.
- Move implementation, not public naming.
- Add a migration note that existing consumers only need to add the Datomic
  adapter dependency; their `require` forms should remain valid.

### 5. DataScript write semantics need a real design, not just "implement the protocol"

The initial plan says the DataScript adapter will satisfy the full protocol, but
it does not specify how write operations, tokens, and schema persistence will
work.

Why this matters:

- `read-schema` and `write-schema!` currently rely on Datomic storage of schema
  metadata and on Datomic-specific orphan checks.
- `write-relationships!` returns a token today.
- DataScript has `transact!`, listeners, tuple attrs, `datoms`, `seek-datoms`,
  and `index-range`, but it is not Datomic and does not provide the same
  migration model.

Recommendation:

- Store canonical schema data inside ordinary EACL-owned entities in DataScript,
  not in backend-specific metadata expectations.
- Define a DataScript token model explicitly, preferably a monotonic tx stamp
  managed by the adapter.
- Define orphan checking over relationship tuples in backend-neutral terms, then
  implement it separately for Datomic and DataScript.

### 6. Contract testing is missing as the primary verification unit

The initial plan includes contract tests, but they appear late and are not the
organizing principle.

Why this matters:

- Once EACL supports multiple backends, the primary unit of correctness is not
  a backend-specific implementation test, but a shared behavioral contract.
- The current Datomic tests are rich but Datomic-shaped.

Recommendation:

- Build a backend-neutral contract suite early.
- Port existing Datomic expectations into shared fixtures and contract tests.
- Let each backend add only storage-specific tests on top.

### 7. `eacl-datastar` should not be used as the architecture driver

The initial plan correctly avoids rewriting the whole explorer, but it still
places too much integration weight on the existing consumer app.

Why this matters:

- `eacl-datastar` currently has direct Datomic reads in explorer/seed/support
  namespaces.
- Forcing the whole app to become backend-agnostic would expand scope
  unnecessarily and entangle the library redesign with a UI app refactor.

Recommendation:

- Create a focused backend smoke/demo harness inside `eacl-datastar`.
- Use that harness to verify the success criteria.
- Treat the existing explorer as out of scope except where its tests already
  cover the Datomic path.

### 8. Phase ordering can be improved

The initial ordering is workable, but not optimal.

Problems:

- It splits modules before it fully defines the shared domain and SPI.
- It introduces DataScript before the core contract suite is promoted to the
  main verification surface.
- It leaves packaging and build details until after substantial code movement.

Recommendation:

- First establish workspace/module topology and the shared domain package.
- Next define the storage SPI and contract tests.
- Then port Datomic to the new shape.
- Then implement DataScript.
- Then wire packaging/build/test matrix.
- Finally verify the consumer smoke harness.

## Redesign Recommendations by Change Area

### Module split

Most elegant redesign:

- Root workspace for development only.
- `modules/eacl` for core.
- `modules/eacl-datomic` for Datomic.
- `modules/eacl-datascript` for DataScript.

### Protocol and model

Most elegant redesign:

- `IAuthorization` and all public records live only in core.
- `count-subjects` is designed in from the start and implemented by every
  backend adapter.
- `Relation` and `Permission` are part of a backend-neutral schema model API.

### Schema system

Most elegant redesign:

- Parser and validation produce a canonical EACL schema IR.
- Backends persist the IR in their own physical representation.
- Orphan checks and deltas are expressed in logical terms, then lowered to each
  backend.

### Query engine

Most elegant redesign:

- Shared engine operates on a narrow storage SPI.
- Datomic and DataScript implement the SPI.
- Cursor and ordering semantics are defined once, in core.

### Logging

Most elegant redesign:

- Library depends on no logging backend.
- If logging remains, keep only the facade in modules that actually emit logs.
- No `logback.xml` ships with the library.

### Consumer verification

Most elegant redesign:

- `eacl-datastar` gets a narrow verification harness for backend parity.
- The broader app remains Datomic-shaped until a separate UI refactor is
  justified.

## Required Improvements for the Revised Plan

- Make the repository layout and artifact boundaries explicit.
- Introduce a distinct logical schema/model layer in core.
- Define a storage SPI before discussing backend extraction.
- Promote contract tests to a first-class workstream.
- Specify DataScript write/token/schema behavior.
- Strengthen compatibility guarantees for current Datomic consumers.
- Reduce `eacl-datastar` scope to a dedicated parity harness.
- Reorder phases to minimize churn and decision leakage.

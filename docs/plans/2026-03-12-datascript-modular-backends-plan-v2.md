# Plan v2: Foundational Redesign for Multi-Backend EACL

## Summary

EACL should be redesigned as a backend-neutral authorization system with two
concrete adapters: Datomic and DataScript. The correct foundational shape is a
workspace-style monorepo with:

- a core module that owns the protocol, public model, schema IR, cursor/token
  behavior, consistency semantics, and backend-neutral execution engine,
- a Datomic adapter that implements the storage SPI and preserves the current
  Datomic public namespace surface, and
- a DataScript adapter that implements the same SPI for both CLJ and CLJS.

This plan intentionally treats the current Datomic implementation as one adapter
to be ported, not as the architectural center of the system.

## Design Principles

- Public API first. `IAuthorization` and the public records live only in core.
- Logical model before storage. Parser, validation, schema diffing, and
  permission semantics are EACL concerns, not Datomic concerns.
- Engine over SPI. Traversal, lookup, counting, and cursor logic execute against
  a narrow backend interface.
- Adapters stay concrete. Datomic and DataScript own physical storage concerns,
  tokens, transaction execution, and backend-specific tests.
- Contract tests define correctness. Backend-specific tests only cover storage
  deltas, not authorization semantics already captured in the shared suite.
- Consumer compatibility matters. Existing Datomic namespaces stay available,
  while dependency coordinates become modular.

## Existing System Baseline

- `src/eacl/core.clj` defines `IAuthorization` and public records, but omits the
  documented `count-subjects` API.
- `src/eacl/spicedb/parser.clj` depends on `eacl.datomic.impl`, which proves the
  logical schema layer is still Datomic-owned.
- `src/eacl/datomic/schema.clj` currently combines:
  logical schema modelling,
  Datomic attribute schema,
  schema diffing and validation,
  orphan detection, and
  schema persistence.
- `src/eacl/datomic/impl/indexed.clj` contains the effective query engine, but
  hard-codes Datomic APIs and Datomic database identity assumptions.
- `deps.edn` makes the root library transitively Datomic-specific and ships
  `logback-classic`.
- Current green baseline:
  `eacl.spice-test` passes,
  `eacl.datomic.impl.indexed-test` passes.

## Target Repository Layout

Adopt a workspace-style monorepo:

- `modules/eacl/`
  publishes the core artifact and contains:
  `eacl.core`,
  public records and helpers,
  consistency/cursor helpers,
  schema IR constructors,
  SpiceDB DSL parser and validation,
  backend SPI,
  shared authorization engine,
  backend-neutral contract tests and shared fixtures.
- `modules/eacl-datomic/`
  publishes the Datomic adapter and contains:
  `eacl.datomic.core`,
  `eacl.datomic.impl`,
  `eacl.datomic.schema`,
  Datomic SPI implementation,
  Datomic physical schema,
  Datomic transaction helpers,
  Datomic compatibility tests.
- `modules/eacl-datascript/`
  publishes the DataScript adapter and contains:
  `eacl.datascript.core`,
  `eacl.datascript.impl`,
  `eacl.datascript.schema`,
  DataScript SPI implementation in `cljc`,
  DataScript conn/schema helpers,
  CLJ and CLJS adapter tests.
- repository root
  remains a development workspace with shared docs, a root `deps.edn` that
  aggregates module source paths for local work, and helper build aliases.

## Public API and Compatibility

The core public surface becomes:

- `eacl.core/IAuthorization`
- `eacl.core/can?`
- `eacl.core/lookup-resources`
- `eacl.core/count-resources`
- `eacl.core/lookup-subjects`
- `eacl.core/count-subjects`
- `eacl.core/read-schema`
- `eacl.core/write-schema!`
- relationship write/read helpers
- `SpiceObject`, `Relationship`, `RelationshipUpdate`

Compatibility rules:

- `eacl.datomic.core`, `eacl.datomic.impl`, and `eacl.datomic.schema` remain
  public namespaces with compatible names.
- Current Datomic consumers should only need to add the Datomic adapter
  dependency; they should not need to change `require` forms.
- `Relation` and `Permission` move into a backend-neutral schema/model namespace
  in core, but Datomic should re-export them during the transition.
- `count-subjects` becomes a mandatory public method in `IAuthorization` and is
  implemented by both adapters.
- `expand-permission-tree` remains unsupported unless separately planned.

## Foundational Redesign by Subsystem

### 1. Schema and Model Layer

Design this as if EACL had never been Datomic-owned:

- Core owns the canonical EACL schema IR:
  relations,
  permissions,
  canonical IDs,
  parser output,
  validation rules,
  schema diffing.
- Parser depends only on core constructors, never on adapter namespaces.
- Backends persist schema IR and any derived indexes in backend-specific ways.

Concrete implication:

- Split today’s `eacl.datomic.schema` into:
  core schema model and validation,
  Datomic physical schema/install/mutation,
  DataScript physical schema/install/mutation.

### 2. Shared Engine and Storage SPI

Design this as if backends were always optional:

- Core defines a minimal storage SPI that exposes only the operations the engine
  truly needs.
- The shared engine performs:
  permission-path expansion,
  recursive traversal,
  forward and reverse lookup,
  counting,
  cursor construction,
  stable merged ordering.

SPI responsibilities should include:

- resolve object id to internal entity id,
- resolve internal entity id back to public id,
- enumerate relation definitions,
- enumerate permission definitions,
- exact datom lookup,
- seek-datoms,
- index-range,
- relationship tuple write planning,
- apply transaction/update batch,
- fetch a backend-specific cache stamp or basis value.

### 3. Datomic Adapter

Design this as one adapter, not as the core system:

- Datomic owns physical tuple attrs and Datomic-only schema install logic.
- Datomic adapter lowers schema IR into Datomic entities and tuple attrs.
- Datomic adapter exposes the existing public namespaces and reuses the shared
  engine via the SPI.
- Datomic-specific orphan detection remains, but its policy is defined in core
  and only its implementation remains adapter-specific.

### 4. DataScript Adapter

Design this as a first-class peer to Datomic:

- Implement in `cljc` wherever possible.
- Use DataScript conn/db primitives and tuple attrs so the same logical storage
  model works in CLJ and CLJS.
- Store canonical EACL schema metadata in ordinary EACL-owned entities rather
  than relying on backend-queryable schema features.
- Define adapter-local tx stamps for tokens and cache invalidation.
- Expose helper entry points to:
  merge/install the DataScript schema,
  create a conn,
  wrap a conn as an `IAuthorization` client.

### 5. Logging

Design this as a library, not an application:

- No logging backend dependency in any published module.
- Remove `logback-classic` and shipped `logback.xml`.
- If logs remain valuable, keep only `clojure.tools.logging` in the modules that
  emit logs, and let applications choose the backend.

### 6. Consumer Verification

Design `eacl-datastar` verification as a narrow parity harness:

- Do not force the existing explorer to become backend-agnostic in this work.
- Add a dedicated smoke harness that can stand up:
  Datomic,
  DataScript CLJ,
  DataScript CLJS.
- Reuse the same logical schema and fixture topology for all three.
- Assert the five required query operations and the creation of each client.

## Ordered Phases

## Phase 1: Create the workspace/module skeleton before moving logic

- [ ] Create `modules/eacl`, `modules/eacl-datomic`, and
      `modules/eacl-datascript`, each with its own `deps.edn`, `src`, `test`,
      and build entrypoint.
- [ ] Convert the repository root into a development workspace with a root
      `deps.edn` that aggregates module paths and dev/test aliases.
- [ ] Move no behavior yet beyond what is required to establish the new module
      boundaries and make all modules loadable from the workspace.
- [ ] Record artifact responsibilities in module READMEs or module-level docs so
      future work does not leak responsibilities back into the wrong layer.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 2: Establish the backend-neutral domain and public API

- [ ] Move `IAuthorization`, `SpiceObject`, `Relationship`,
      `RelationshipUpdate`, consistency helpers, and cursor/token helpers into
      the core module.
- [ ] Add `count-subjects` to `IAuthorization` and any associated public helper
      entry points.
- [ ] Create a backend-neutral schema/model namespace in core for `Relation`,
      `Permission`, canonical schema IDs, and shared schema utilities.
- [ ] Update parser code to depend only on the new schema/model namespace.
- [ ] Add compatibility re-exports in the Datomic adapter so existing code that
      imports Datomic constructors still works during migration.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 3: Separate logical schema semantics from physical backend schema

- [ ] Move schema parsing, validation, canonical serialization, and diffing into
      the core module as backend-neutral logic.
- [ ] Define a backend-neutral orphan-check policy in terms of relations and
      live relationship usage.
- [ ] Rebuild `eacl.datomic.schema` as a Datomic adapter namespace that lowers
      schema IR into Datomic physical schema and executes Datomic-specific
      orphan detection and persistence.
- [ ] Design and implement the DataScript schema namespace to store canonical
      EACL schema metadata in EACL-owned entities and to install any required
      tuple/index attributes in DataScript.
- [ ] Ensure `read-schema` returns the same logical shape from both adapters.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 4: Define the storage SPI and promote contract tests

- [ ] Define the smallest practical storage SPI in core, with protocol or plain
      function map boundaries explicit enough that both Datomic and DataScript
      can implement it cleanly.
- [ ] Build a backend-neutral contract suite for:
      schema read/write,
      relationship read/write,
      `can?`,
      `lookup-resources`,
      `count-resources`,
      `lookup-subjects`,
      `count-subjects`,
      cursor behavior,
      missing entity behavior,
      permission graph cycles and caching.
- [ ] Move reusable fixtures into core test support so both backends execute the
      same logical test scenarios.
- [ ] Keep backend-specific test namespaces only for storage mechanics not
      covered by the contract suite.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 5: Port the shared engine onto the SPI and then port Datomic

- [ ] Rewrite the indexed authorization engine in core so traversal and counting
      depend only on the SPI, not on Datomic namespaces or Datomic db identity.
- [ ] Replace the current DB-specific cache key strategy with a backend-neutral
      cache stamp supplied by the adapter.
- [ ] Preserve existing cursor tokens and ordering semantics while moving the
      implementation.
- [ ] Implement the Datomic SPI adapter and reconnect the Datomic public
      namespaces to the shared engine.
- [ ] Keep the existing Datomic green suite passing throughout this phase,
      especially `eacl.spice-test` and `eacl.datomic.impl.indexed-test`.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 6: Implement the DataScript adapter for CLJ and CLJS

- [ ] Implement the DataScript SPI adapter in `cljc` using DataScript’s
      `datoms`, `seek-datoms`, `index-range`, tuple attrs, transactions, and
      listeners where appropriate.
- [ ] Implement the full `IAuthorization` surface for DataScript, including
      `count-subjects`, schema writes, relationship writes, and token returns.
- [ ] Define a DataScript token/basis strategy explicitly:
      maintain a monotonic adapter-local stamp so write responses and cache
      invalidation are deterministic across CLJ and CLJS.
- [ ] Add CLJ tests and CLJS tests against the shared contract suite, with any
      DataScript-only edge cases covered in adapter-local tests.
- [ ] Keep browser-oriented fixtures smoke-sized and exclude heavy benchmark
      profiles from the CLJS path.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 7: Remove bundled logging backend and finish packaging

- [ ] Remove `logback-classic` from module dependencies and delete shipped
      logback configuration from library modules.
- [ ] Retain only logging facade dependencies where they provide value.
- [ ] Create per-module build/install/deploy tasks so each artifact is
      publishable independently.
- [ ] Update README and examples so dependency instructions clearly show:
      core only,
      Datomic adapter,
      DataScript adapter.
- [ ] Add a migration section that explains the new coordinates and guarantees
      namespace continuity for Datomic users.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 8: Verify the consumer story in `eacl-datastar`

- [ ] Point `~/Code/eacl-datastar` at the dedicated EACL worktree during the
      implementation period.
- [ ] Add a narrow parity harness in `eacl-datastar` that can instantiate:
      a Datomic ACL client,
      a server-side DataScript ACL client,
      a client-side DataScript ACL client.
- [ ] Seed the same smoke schema and relationship topology into each backend.
- [ ] Assert parity for:
      `eacl/can?`,
      `eacl/lookup-subjects`,
      `eacl/lookup-resources`,
      `eacl/count-resources`,
      `eacl/count-subjects`.
- [ ] Keep the existing Datomic-centric explorer out of scope unless a minimal
      change is required for the parity harness to coexist with it.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Acceptance Criteria

- The repo builds as three publishable artifacts with a root development
  workspace.
- `eacl` has no Datomic peer dependency and no bundled logging backend.
- `eacl-datomic` preserves the current Datomic public namespace surface.
- `eacl-datascript` works in both CLJ and CLJS.
- `count-subjects` is public, implemented, tested, and documented.
- Shared contract tests pass for Datomic and DataScript CLJ.
- Shared contract tests pass for DataScript CLJS where runtime-appropriate.
- Existing Datomic regression suites remain green.
- `~/Code/eacl-datastar` can instantiate all three client variants and pass the
  required parity checks.

## Appendix A: Original Prompt

> Problem: EACL only supports Datomic Pro or Datomic Cloud.
> Goal: EACL needs to support DataScript (client or server-side), to support
> in-browser demos. Should satisfy the same IAuthorization protocol, but
> obviously for client-side, the memory will be limited for benchmarks, but
> that's fine.
> Success criteria: the eacl-datastar project at ~/code/eacl-datastar should be
> available to instantiate a client-side or server-side DataScript connection
> and do eacl/can?, eacl/lookup-subjects, eacl/lookup-resources,
> eacl/count-resources, eacl/count-subjects with all tests passing for each
> implementation.
>
> As part of this, the Datomic & DataScript implementations should become
> concrete implementations in their own modules, so that EACL library consumers
> don't have to include dependencies they don't need. And while you're at it,
> strip out logback, let users defined their own logging library.
>
> Clarifications captured during planning:
> - DataScript support must cover both CLJ and CLJS.
> - The first implementation must satisfy the full protocol, not just the query
>   subset.
> - `count-subjects` should become a real public API.
> - The module split should be core plus backend adapters, without CloudAfrica
>   branding assumptions.
> - The work should continue in `/Users/petrus/.codex/worktrees/e264/eacl` to
>   avoid disturbing the stable `eacl/v7` checkout.

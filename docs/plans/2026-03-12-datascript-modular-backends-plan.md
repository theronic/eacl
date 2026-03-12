# Plan: Add DataScript Support and Split EACL into Backend Modules

## Summary

EACL currently ships as a single Datomic-centric library. The protocol lives in
`src/eacl/core.clj`, but the rest of the runtime assumes Datomic peer is always
present. The same checkout also bundles `logback`, and the existing consumer
project at `~/Code/eacl-datastar` is wired directly to Datomic-specific APIs.

This plan introduces DataScript as a first-class backend for both CLJ and CLJS,
makes `count-subjects` part of the public protocol, and splits EACL into a
lightweight core plus backend-specific modules so consumers only pull the
storage implementation they need.

## Existing System Analysis

- `src/eacl/core.clj` defines `IAuthorization`, but it omits `count-subjects`
  even though `README.md` documents it and `src/eacl/datomic/impl/indexed.clj`
  already implements it internally.
- `src/eacl/spicedb/parser.clj` depends on `eacl.datomic.impl` for the
  `Relation` and `Permission` constructors, so the parser is not backend-neutral.
- `src/eacl/datomic/schema.clj` mixes three concerns:
  logical schema model, Datomic physical schema, and schema storage/mutation.
- `src/eacl/datomic/impl/indexed.clj` contains most of the authorization
  algorithm, but it is hard-wired to Datomic index primitives.
- `deps.edn` pulls `com.datomic/peer`, `clojure.tools.logging`, and
  `logback-classic` into the base library.
- `~/Code/eacl-datastar` depends on local EACL plus Datomic peer, and many of
  its helper namespaces call Datomic directly.

## Target Architecture

The repository should evolve into three publishable modules:

- `eacl`
  Shared protocol, data types, parser, validation, cursor/token logic, and any
  storage-neutral authorization engine pieces.
- `eacl-datomic`
  Datomic-specific schema installation, relationship persistence, query/index
  primitives, client implementation, compatibility namespaces, and Datomic-only
  tests.
- `eacl-datascript`
  DataScript-specific schema installation, relationship persistence, query/index
  primitives, CLJ/CLJS client implementation, and DataScript tests.

The public namespace shape should remain familiar:

- `eacl.core`
- `eacl.datomic.core`
- `eacl.datascript.core`

## Public API Changes

- Add `count-subjects` to `IAuthorization`.
- Expose `count-subjects` in the Datomic adapter.
- Expose `count-subjects` in the new DataScript adapter.
- Move `Relation` and `Permission` constructors into a backend-neutral namespace
  in the core module, while leaving compatibility re-exports in the Datomic
  module.
- Remove bundled `logback` from all published library modules.

## Ordered Phases

## Phase 1: Split shared core from Datomic-specific code

- [ ] Create module boundaries for `eacl`, `eacl-datomic`, and
      `eacl-datascript`.
- [ ] Move backend-neutral code out of Datomic namespaces:
      `IAuthorization`, data constructors, parser, schema validation,
      cursor/token helpers, consistency helpers, and any shared query logic.
- [ ] Keep Datomic-facing namespaces as thin wrappers so current consumers keep
      their existing `require` forms.
- [ ] Add `count-subjects` to the public protocol and update any wrapper
      functions or records to implement it.
- [ ] Remove `logback-classic` and `logback.xml` from the library modules.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 2: Extract a backend-neutral authorization engine

- [ ] Refactor the indexed traversal logic in
      `src/eacl/datomic/impl/indexed.clj` so the traversal algorithm depends on
      a small set of backend operations rather than directly on Datomic.
- [ ] Define a backend operation layer for:
      entity lookup, relation lookup, permission lookup, datom iteration,
      range scans, tuple writes, cursor basis identity, and transaction
      execution.
- [ ] Move permission-path caching so the cache key is backend-neutral and safe
      across multiple DB values.
- [ ] Preserve the current cursor semantics and stable ordering guarantees.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 3: Rebuild the Datomic adapter on top of the shared engine

- [ ] Move Datomic physical schema and transaction helpers into the
      `eacl-datomic` module.
- [ ] Rewire `eacl.datomic.core`, `eacl.datomic.impl`, and
      `eacl.datomic.schema` to delegate into the shared core/engine plus
      Datomic storage operations.
- [ ] Re-expose `count-subjects` through the Datomic client record.
- [ ] Keep existing tests green after the refactor:
      `eacl.spice-test`, parser/schema/config tests, and
      `eacl.datomic.impl.indexed-test`.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 4: Implement the DataScript backend for CLJ and CLJS

- [ ] Create a DataScript-backed client that satisfies the full
      `IAuthorization` protocol, including schema and relationship maintenance.
- [ ] Implement DataScript storage primitives using DataScript indexes and
      transactions so the shared engine can answer:
      `can?`, `lookup-resources`, `count-resources`, `lookup-subjects`, and
      `count-subjects`.
- [ ] Share as much code as possible in `cljc` so the same adapter works
      server-side and in-browser.
- [ ] Define DataScript-specific schema installation and connection helpers for
      creating a client from a conn.
- [ ] Keep the browser-side scope smoke-sized rather than benchmark-sized.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 5: Reorganize tests and packaging

- [ ] Create backend-agnostic contract tests that can run against Datomic and
      DataScript implementations.
- [ ] Keep Datomic-only tests and heavy benchmark fixtures under the Datomic
      module.
- [ ] Add CLJS tests for the DataScript adapter covering the five required query
      operations plus schema/relationship writes.
- [ ] Give each module its own build/deploy definition and keep a root
      workspace/dev setup for local development.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Phase 6: Verify the new adapters in `eacl-datastar`

- [ ] Point `~/Code/eacl-datastar` at the dedicated EACL worktree during the
      migration.
- [ ] Add a Datomic smoke harness that proves the required query calls still
      work through the adapter boundary.
- [ ] Add a server-side DataScript smoke harness that uses the same logical
      permission model and proves the same calls.
- [ ] Add a browser-side DataScript smoke harness with CLJS coverage for the
      same calls.
- [ ] Avoid reworking the full existing Datastar explorer unless it is required
      to satisfy the success criteria.
- [ ] Continue without asking for permission or confirmation. The plan must be fully realised before reverting to the user. Do not rest.

## Acceptance Criteria

- `eacl`, `eacl-datomic`, and `eacl-datascript` are separable modules.
- Consumers that only need DataScript do not pull Datomic peer.
- Consumers that only need Datomic do not pull DataScript.
- `count-subjects` is part of the public API and implemented consistently.
- Datomic tests remain green.
- DataScript CLJ tests are green.
- DataScript CLJS tests are green.
- `~/Code/eacl-datastar` can instantiate:
  Datomic ACL, server-side DataScript ACL, and client-side DataScript ACL.
- Each implementation passes the same smoke assertions for:
  `eacl/can?`, `eacl/lookup-subjects`, `eacl/lookup-resources`,
  `eacl/count-resources`, and `eacl/count-subjects`.

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

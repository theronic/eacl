## Context

The `release/v8.0` branch contains the authoritative Datomic implementation: Relay-style pagination, encrypted cursor envelopes, consistency descriptors and Zed tokens, relation-scoped cache invalidation, configurable cache stores, migration support, and object-deletion safety. Its source and dependency graph are still monolithic.

The `origin/eacl/datascript` branch established a useful but older workspace layout with three modules, a backend-neutral engine, a six-function map-based SPI, and shared backend contract tests. Datahike PR #81 is deliberately shaped as a fourth module against that SPI. Replacing the SPI or changing module paths would turn its v8 upgrade into a rewrite.

The implementation must therefore combine two lines without regressing either one: the module/SPI seam from the DataScript line and the runtime behavior from `release/v8.0`. Public `eacl.*` namespace names are already consumed in production and must remain stable.

## Goals / Non-Goals

**Goals:**

- Make `modules/eacl`, `modules/eacl-datomic`, and `modules/eacl-datascript` independently consumable and buildable.
- Keep `eacl.backend.spi` source-compatible with PR #81's six functions.
- Keep the complete `release/v8.0` Datomic implementation and test suite authoritative while relocating it into `eacl-datomic`.
- Keep shared schema/parser data backend-neutral so DataScript, Datahike, and future adapters do not depend on Datomic.
- Prove dependency isolation and backend behavior through module-level load checks and shared/backend-specific tests.
- Stop selecting a logging implementation in EACL dependency metadata.

**Non-Goals:**

- Merging Datahike PR #81 in this change.
- Moving Datomic's basis-aware authorization result cache into the generic SPI. That cache relies on Datomic transaction bases, logs, and relation-version stamps.
- Renaming existing public namespaces or changing persisted v7/v8 Datomic schema.
- Guaranteeing that every backend implements Datomic-specific consistency or cache capabilities.

## Decisions

### Use the existing module names and six-function SPI

The workspace will retain `modules/eacl`, `modules/eacl-datomic`, and `modules/eacl-datascript`, plus the map-based SPI functions `cache-stamp`, `relation-defs`, `permission-defs`, `subject->resources`, `resource->subjects`, and `direct-match?`.

This is the narrowest stable boundary already exercised by two non-Datomic adapters. It also lets PR #81 move onto the v8 branch primarily by rebasing its new `modules/eacl-datahike` tree and addressing explicit public-contract changes.

Alternative considered: define a new protocol around all v8 operations. Rejected because it would mix storage primitives with Datomic-only cache and consistency behavior, and would invalidate the working DataScript/Datahike adapters.

### Treat `release/v8.0` Datomic source as authoritative

The current top-level Datomic namespaces, migrations, tests, and benchmark harness will be relocated into `modules/eacl-datomic` before any structural refactoring. Public namespace names and behavior remain unchanged. The backend-neutral engine remains available to DataScript and third-party adapters, but the Datomic adapter may retain its optimized Datomic-specific engine.

Alternative considered: replace v8 Datomic code with the older generic-engine adapter from `origin/eacl/datascript`. Rejected because that would discard or require reimplementing the cache, reverse pagination, cursor security, consistency, and audit fixes already accepted for v8.

### Put shared schema values and parsing in the core module

`eacl.schema.model`, `eacl.spicedb.parser`, `eacl.spicedb.consistency`, the public protocol/records, cursor helpers, shared engine, and SPI live in `modules/eacl`. Datomic compatibility constructors delegate to the shared schema model, preserving the maps and identifiers callers already observe.

This prevents a duplicate `eacl.spicedb.parser` namespace across adapter modules and removes the parser's dependency on `eacl.datomic.impl`.

Alternative considered: keep one parser per adapter. Rejected because loading two adapters would put duplicate namespace resources on the classpath and make behavior depend on path order.

### Keep Datomic cache and consistency implementation adapter-local

The core module exposes consistency descriptor data, while the Datomic module owns token verification, basis selection, watermarks, cache-store protocols, invalidation, and result caching. Other backends can support the common descriptors where their storage semantics allow it without expanding the six-function SPI.

### Make the root configuration a workspace, not a published artifact boundary

Root `deps.edn` includes all module source/test paths and dependencies needed for development. Each module has its own `deps.edn` and tools.build entry point. Module dependency checks load each module from its own basis, so undeclared dependencies cannot be masked by the root workspace.

### Remove direct logging selection

Published module dependency maps will not contain Logback or another SLF4J binding. Runtime code will not require a logging facade solely for a handful of diagnostic calls; those calls will be removed or replaced with explicit errors where correctness depends on them. Test-only diagnostics must not leak a runtime logging dependency.

## Risks / Trade-offs

- [The two implementation lines have diverged substantially] → Import the module structure first, then overwrite Datomic code with the v8-authoritative source and run the full v8 suite from module paths.
- [A shared namespace can accidentally retain a Datomic require] → Validate `modules/eacl` in an isolated basis with no Datomic dependency and scan its dependency tree.
- [The root workspace can hide missing module dependencies] → Load and test each module using its own `deps.edn`, in addition to the combined workspace suite.
- [DataScript and Datahike do not automatically inherit Datomic-specific caching] → Keep the SPI storage-focused and document adapter capability differences rather than pretending Datomic transaction semantics are portable.
- [Relocating files can obscure functional diffs] → Preserve namespace names, use mechanical moves where possible, and keep behavior changes limited to backend-neutral extraction and dependency isolation.
- [Removing the bundled logging backend can expose noisy transitive logs in development] → Applications and CI may supply their own test/runtime logging configuration without making it a published EACL dependency.

## Migration Plan

1. Create the feature branch from `origin/release/v8.0`.
2. Integrate the latest DataScript module line as the structural baseline.
3. Relocate the current v8 Datomic source and tests into `modules/eacl-datomic`, then extract shared schema/parser values into `modules/eacl`.
4. Update workspace/module dependency files, build aliases, CI, shared contracts, and documentation.
5. Validate isolated module loads, DataScript contracts, the focused Datomic namespaces, and the complete non-benchmark v8 suite through nREPL.
6. Open a PR into `release/v8.0`. PR #81 can then rebase its `modules/eacl-datahike` commits onto the merge result.

Rollback is a normal revert of the modularization PR; no persisted data migration or runtime schema change is introduced.

## Open Questions

None. Datahike-specific v8 capability work remains in PR #81 after it rebases onto the stable module boundary.

## Why

After Phase 1 creates a single trailing qualifier reference, EACL needs a sparse, shared-data representation for SpiceDB-style Caveats and future expiration without yet destabilizing the traversal engine. Caveat expressions must be stored once per named definition rather than copied into every Relationship, while Relationship-bound context and `valid-until` remain specific to one logical Relationship.

This phase builds and formally specifies that foundation before any non-`nil` qualifier can affect production authorization. It uses `com.exoscale/cel-parser` behind a bounded EACL compatibility layer instead of making the library's raw parser/evaluator behavior part of EACL's durable or public contract.

## What Changes

- Require completion of `2026-09-04-01-adopt-v9-qualifier-reference-storage` and storage ABI 9.
- Qualify the exact `cel-parser` behavior and target SpiceDB/CEL corpus in read-only exploration first, then apply a **FORMAL GATE**: define and verify qualifier lifecycle, Caveat typing, stored/request context precedence, complete/partial/error outcomes, and the selected CEL profile before editing production qualifier/Caveat source.
- Add sparse immutable Relationship qualifier entities referenced by slot five. Qualifiers may carry a shared Caveat-definition ref, canonical Relationship-bound Caveat context, and optional epoch-millisecond `valid-until` for Phase 3.
- Keep qualifiers singly owned and immutable. Updating any qualifier creates a new entity and later replaces both endpoint tuple refs; sharing or mutating a qualifier in place is unsupported and detectable.
- Treat publication of a concrete qualifier ref as an explicit backend capability. A backend may create and attach the qualifier in one native transaction only after conformance proves nested ref allocation; otherwise it must prepare an unreferenced inert qualifier first and atomically publish both endpoint refs from its resolved eid. No backend may expose one tuple half or an unresolved tempid/lookup ref as serving state.
- Canonicalize an empty qualifier to `nil`; ordinary Relationships continue to use two endpoint datoms and no qualifier datoms.
- Add top-level typed Caveat schema declarations and `with <caveat>` Relation subject branches. Store one expression and parameter declaration set per named Caveat definition; Relationships reference the definition rather than duplicate source.
- Pin and qualify `com.exoscale/cel-parser` 0.1.8 on the JVM. Use it for CEL parsing and complete-context evaluation through an EACL adapter that handles returned error values, bounds resources, and exposes a versioned evaluator profile.
- Define EACL-owned partial/unknown semantics needed for SpiceDB-compatible conditional results; parser/evaluator exceptions or unsupported constructs are errors, not missing context.
- Keep the persisted representation portable. Parsed ANTLR programs remain bounded client runtime cache entries and are never durable authorization data.
- Keep the default CEL implementation JVM-only. ClojureScript adapters preserve storage/schema portability but cannot activate Caveated authorization without a separately supplied, fingerprinted, conformance-certified evaluator.
- Add qualifier construction, validation, lifecycle, integrity, administrative decoding, and cross-backend publication-capability tests, but keep current-serving Relationship writes with non-`nil` qualifiers and traversal evaluation disabled until Phase 3.
- Add no qualifier lookup, clock read, Caveat branch, or formal shadow execution to the ordinary authorization hot path in this phase.

## Capabilities

### New Capabilities

- `relationship-qualifier-storage`: sparse immutable qualifier entities, Caveat/context/`valid-until` attributes, ownership, canonicalization, lifecycle, integrity, and staged activation.
- `relationship-caveats`: shared typed Caveat definitions, Relation declarations, bounded CEL profile, stored/request context semantics, portable outcome model, evaluator capability boundary, and SpiceDB compatibility fixtures.

### Modified Capabilities

## Impact

The change affects schema parsing and persistence, qualifier attribute schemas on all backends, internal Relationship write planning, integrity tooling, shared canonical encoding, the core evaluator SPI, JVM module dependencies, build isolation, formal models, differential tests, documentation, and future public API shapes.

It deliberately does not change the traversal engine, permission algebra, current `can?`/lookup/count behavior, result caches, cursors, or trusted clock. A database containing non-`nil` qualifier refs remains ineligible for current-serving authorization until Phase 3. This keeps the formal model and storage/evaluator foundation reviewable without shipping a half-evaluated authorization graph.

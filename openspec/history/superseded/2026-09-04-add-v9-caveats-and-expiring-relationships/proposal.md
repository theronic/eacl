## Why

EACL needs Caveats and expiring Relationships without making every Relationship a separate entity or requiring a transaction exactly at expiry. The preceding eight-slot scheduling proposal added future activation, interval bookkeeping, and a mandatory collection index that are unnecessary when consumers can create Relationships when they should begin.

This proposal supersedes [2026-09-03-add-v9-qualified-relationship-tuples](../2026-09-03-add-v9-qualified-relationship-tuples/proposal.md), which remains deprecated and unimplemented. Its [review and REPL evidence](../2026-09-03-add-v9-qualified-relationship-tuples/review-2026-09-04.md) inform this replacement; the old deltas must not be applied first.

## What Changes

- **BREAKING:** replace the four-slot v7 persisted Relationship representation used by the v8 engine with exactly two authoritative seven-slot endpoint values: forward `[subject-type relation-eid resource-type resource-eid caveat-eid caveat-context-eid valid-until-ms]`, and its reverse counterpart. Owner plus the first four components remains identity; the three trailing values are qualifiers.
- Support optional, exclusive `valid-until` in both directions. A stored Relationship is expiry-active when the bound is absent or the selected evaluation time is earlier than the bound. Expiration needs no write, callback, cache eviction job, or collection pass.
- Exclude `valid-from`, schedule entities, transaction-metadata validity, and native backend valid-time integration. Consumers perform future creation as an ordinary guarded Relationship write; delayed insertion is acceptable to this contract. No background activation service is supplied.
- Preserve `:create` conflicts against all stored qualifier states, atomic qualifier replacement through `:touch`, and identity-only `:delete`. Relationships with different deadlines can share a transaction. Renewing a Relationship changes both tuple values and its affected relation mutation identity.
- Retain the Caveat design: shared typed schema definitions, zero or one Caveat per Relationship, sparse immutable context payloads only for non-empty bound context, bound-over-request precedence, bounded deterministic evaluation, conditional permissionship, and a pinned, tested SpiceDB compatibility profile.
- Use [exoscale/cel-parser](https://github.com/exoscale/cel-parser) (`com.exoscale/cel-parser`) for JVM CEL parsing and interpretation behind the EACL adapter. Pin its version and semantic profile; qualify EACL's type validation, partial results, execution limits, and ClojureScript path explicitly rather than assuming the library supplies them.
- Intern immutable expression entities containing canonical source, typed parameter declarations, and profile identity. Named Caveat definitions reference these shared entities; Relationship tuple slots remain unchanged. Equivalence means equal canonical expression content, not proof that different CEL formulas are logically equivalent.
- Lazily build programs in the EACL client when traversal first evaluates an expiry-active Caveated edge, then reuse them across identical expressions and different bindings. Keep the bounded program cache separate from authorization results, with concurrent miss coordination and complete expression/evaluator identity. Qualify warm macro execution as well as top-level program reuse.
- Deliver the seven-slot ABI and expiry first, reserving Caveat slots as nil; enable Caveats in a second phase without another tuple change. Fence obsolete clients at semantic capability activation.
- Add expiry deadlines to cached grants, denials, residuals, and continuations. Expiring negative evidence can grant permission under exclusion; expiry-only does not make version-only caches coherent. Snapshot cursors freeze evaluation time; live cursors restart when their complete temporal proof expires.
- Make expired-Relationship collection optional, bounded maintenance over the authoritative forward tuples. Do not require a third per-Relationship expiration-index datom. Collector deletions use the normal atomic pair/context lifecycle and bump `:eacl/relation-version` for every affected Relation; passing time does not.
- **BREAKING:** accept only fresh/rebuilt v9 stores, reject incompatible or mixed Relationship data and old cache/cursor artifacts, and provide rebuild guidance without automatic migration or dual reads.

## Capabilities

### New Capabilities

- `expiring-relationships`: expiry-only semantics, trusted evaluation-time snapshots, consumer-owned activation, stored versus expiry-active reads, renewal, optional guarded collection, and history limits.
- `relationship-caveats`: typed named Caveat declarations, shared immutable expression entities, lazy client program compilation, immutable bound context, request context, conditional algebra, encoding/profile conformance, and lifecycle safety.

### Modified Capabilities

- `converged-relationship-storage`: fixed seven-slot ABI across all backends, identity guards, ordered scans, atomic pair mutations, proofs, and rebuild boundary.
- `public-authorization`: evaluation-time and Caveat request boundaries, three-state checks, definite/conditional enumeration, and explicit limit/restart errors.
- `dependency-validated-authorization-cache`: expiry certificates for each reusable artifact, complete Caveat identity/dependencies, and ABI fencing.
- `cursor-dependency-validity`: pinned/live time modes, expiry-safe continuation, context authentication, and seven-slot ordering.

## Impact

Changes span the shared Relationship codec/planner, all four backend schemas and adapters, public snapshot/read/write/check/lookup/count APIs, schema grammar and safety checks, cache/proof/cursor formats, bounded maintenance, formal models, documentation, and CLJ/CLJS conformance suites. The permission representation is not redesigned by the Relationship storage version bump.

Phase 2 introduces the pinned `com.exoscale/cel-parser` JVM dependency and its tested transitive dependency set, including its Java/ANTLR boundary. Parsed programs remain rebuildable client runtime state; they do not become the persisted or portable Caveat format. Schema admission still rejects invalid definitions before commit, independently of lazy runtime compilation. Library size alone does not establish lower total integration cost: static validation, conditional evaluation, macro-body reuse, execution limits, and cross-runtime behavior remain explicit qualification work.

The retained million-Relationship experiments establish Datomic filtering and mutation behavior, not seven-slot production performance. Before release, benchmark the actual expiry-only implementation with 0%, 1%, and 5% expiring workloads plus concentrated expired prefixes, publish workload and transaction diversity, and establish numerical latency/space budgets before qualification. No CEL/SpiceDB compatibility or performance target is declared satisfied by this proposal.

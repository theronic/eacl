## Context

PR #84's `release/v8.0` branch contains the modularized v8 Datomic implementation, including authorization caching, Relay pagination, object deletion, consistency selection, and algorithmic improvements. The current DataScript module and Datahike PR #81 instead share a v7-era six-function backend SPI and engine. That engine deliberately cuts permission cycles, uses the legacy pagination contract, and has no v8 result cache.

Reviewing PR #81 found that its Datahike-specific data-access layer is a sound port of the DataScript behavior: the existing focused and non-benchmark v7 suites pass, and it handles both keyword attributes and Datahike's numeric attribute-reference mode. It is not merge-ready unchanged, however. Its module has no build implementation, its isolated test basis omits shared contract support, its dependency declarations are inconsistent, and an unknown reverse-lookup anchor can return an empty sequence with a meaningless cursor. The first stage therefore corrects and merges the v7 port before any v8 extraction work.

The primary architectural constraint is correctness across three databases and two host runtimes without turning the shared module into a lowest-common-denominator abstraction. Shared code must own authorization algorithms and public behavior. Adapters must own snapshot selection, index access, entity/reference representation, transactions, proof generation, and any guarantee that cannot be implemented portably.

## Goals / Non-Goals

**Goals:**

- Merge a verified, independently buildable v7 Datahike adapter before using it as the v8 upgrade source.
- Preserve the complete Datomic behavior and database compatibility already present on `release/v8.0`.
- Run DataScript and Datahike through the same v8 permission compiler, recursive traversal, pagination, counting, deletion semantics, and cache validation as Datomic wherever the behavior is backend-neutral.
- Keep backend-specific code small, named by capability, and free of copied authorization graph algorithms.
- Make cache reuse demonstrably sound across relevant writes, schema changes, snapshots, and Datahike connections.
- Define one shared conformance matrix plus targeted Datomic, DataScript CLJ/CLJS, and Datahike tests.
- Deliver the integration as a pull request based on `release/v8.0`, so it composes directly with PR #84.

**Non-Goals:**

- Providing byte-identical cursor encodings or identical storage representations across databases.
- Claiming historical, cross-process, or consistency guarantees that a backend cannot provide.
- Replacing backend-native index and transaction optimizations with a generic datastore abstraction.
- Merging the old DataScript or Datahike branches wholesale into `release/v8.0`.
- Changing Datomic application data or requiring a Datomic migration solely to support the other adapters.

## Decisions

### 1. Use a two-stage Git integration

First, rebase or retarget PR #81 onto the latest DataScript v7 branch, correct the discovered module and empty-result defects, add required checks, and merge it. Second, create the v8 integration branch from the latest `release/v8.0` and bring the merged Datahike module forward as a reviewed source tree.

This keeps the user's two outcomes independently auditable: PR #81 proves that Datahike correctly implements the v7 contract; the later PR proves the v8 upgrade. It also avoids importing the older branches' copies of core files over the release candidate.

Alternative considered: close PR #81 and port its code directly into v8. This is faster mechanically but loses a clear correctness checkpoint and does not satisfy the requested correct v7 Datahike merge.

### 2. Make authorization algorithms shared and datastore mechanics explicit

The shared `eacl` module will own permission graph compilation, strongly connected component analysis, acyclic and recursive traversal, deterministic de-duplication, Relay windowing, count/truncation semantics, dependency-set calculation, and common error validation. Backend adapters will own:

- selecting and retaining an immutable operation snapshot;
- entity, attribute, and reference resolution;
- ordered adjacency/index scans;
- schema and relationship transactions, including deletion;
- schema and relation change proofs;
- declared consistency and cursor capabilities.

The existing six functions remain a compatibility surface for v7-style third-party adapters. The v8 path adds an explicit adapter protocol or validated operation map rather than overloading function behavior by arity or inspecting datastore records.

Alternative considered: keep the Datomic engine intact and independently upgrade the DataScript/Datahike engine. That preserves short-term isolation but would create multiple recursive traversal and cache implementations whose edge cases will drift.

### 3. Preserve capability differences instead of emulating them

The adapter contract will report supported consistency, historical snapshot, transaction, cursor, and proof capabilities. Shared entry points validate requested behavior before execution. Datomic keeps its current v8 consistency modes, historical bases, and encrypted cursors. DataScript and Datahike initially expose the modes they can guarantee against a selected immutable database value and reject the rest with typed errors.

Relay cursors must have equivalent public ordering, validation, and resumption behavior, but their protected encoding may differ by runtime. In particular, synchronous ClojureScript entry points will not depend on asynchronous Web Crypto merely to mimic Datomic's AES-GCM representation.

Alternative considered: silently map unsupported modes to fully consistent/current. This would make APIs look uniform while weakening their guarantees.

### 4. Split portable cache mechanics from backend proof generation

The cache store contract, cache-entry schema/version, dependency metadata, failure handling, and validation workflow will move from the Datomic namespace into backend-neutral cache namespaces. The shared engine treats schema and relationship proofs as opaque comparable values returned for the same selected snapshot used by the authorization operation.

Datomic may retain its transaction-based proof optimization behind that interface. DataScript and Datahike will use database-visible, snapshot-bound proof state. Where a backend cannot compute an exact relation proof efficiently, EACL-managed relationship mutations will update an opaque per-relation generation in the same transaction as the relationship write. Schema mutations will update an analogous scoped schema generation. Datahike proof tests must use distinct connections so a process-local listener or counter cannot accidentally pass.

Out-of-band writes that bypass EACL's mutation entry points must either update the documented proof attributes through an adapter hook or disable cache reuse for affected data. The implementation must document this constraint rather than allow unsound cache hits.

Alternative considered: one global cache generation. It is safe but invalidates all results after unrelated writes and loses a central v8 cache benefit. Process-local listeners were also rejected because they cannot establish validity across connections or processes.

### 5. Treat recursive pagination state as shared engine state

Recursive permission references are compiled into strongly connected components instead of being cut as cycles. Traversal uses deterministic work queues and visited keys scoped to the semantic state needed for correctness, producing each authorized object once. Page continuations record versioned traversal/window state and the dependency proofs needed to validate resumption. Configured depth, work, and result ceilings yield typed limit outcomes.

The backend supplies ordered adjacency batches; it does not implement recursive graph evaluation. Cached and uncached continuations are run through the same validator so invalidated state is recomputed or rejected before data is returned.

Alternative considered: recursively call each adapter's existing traversal functions. That makes termination, de-duplication, reverse pagination, and cache continuation behavior backend-dependent.

### 6. Build conformance around an independent oracle

Shared fixtures cover the full public v8 behavior, recursive schemas, cache invalidation, unknown anchors, and Relay page boundaries. A small deliberately simple reference evaluator or property model computes authorization sets independently of the optimized shared engine. Seeded/generated cases compare each adapter to that oracle and report reproducible seeds.

Adapter suites remain responsible for datastore-specific guarantees: Datahike keyword/numeric attributes and multi-connection proofs; DataScript CLJ and CLJS; Datomic consistency, historical bases, encrypted cursor behavior, and transaction proof optimizations. Each module must also load, test, and build from its own dependency basis.

Alternative considered: assert only that all three adapters agree. A shared bug can make three implementations agree on the wrong answer, especially after the engine is centralized.

### 7. Extract in parity-preserving slices

Work proceeds from the v8 Datomic baseline in small slices: establish characterization tests, introduce the adapter boundary around existing behavior, move one algorithm/cache concern at a time, and keep Datomic green after every slice. Upgrade DataScript against the shared contract before Datahike because PR #81 already mirrors its storage layer; then adapt Datahike and remove any duplicated engine code.

This sequence minimizes simultaneous changes and makes regressions attributable. The final pull request targets `release/v8.0` and documents which DataScript/Datahike APIs are breaking from v7.

## Risks / Trade-offs

- [The Datomic implementation may combine algorithms with index-specific assumptions] → Add characterization tests first and introduce adapter seams before moving code; retain proven native operations behind the seam.
- [A compact SPI may become a leaky datastore abstraction] → Define operations around authorization needs and declared capabilities, not generic database CRUD or raw tuple layouts.
- [Per-relation proof writes add storage and transaction cost] → Prefer exact backend-native proofs where available, scope generations narrowly, and benchmark separately after correctness is established.
- [Direct datastore writes can bypass proof maintenance] → Document the mutation boundary, expose an adapter invalidation/proof hook, and disable caching when exact proof maintenance cannot be established.
- [Recursive continuations can become large] → Version and bound continuation state, store larger state in the configured cache when appropriate, and enforce explicit safety ceilings.
- [CLJ and CLJS cursor/security primitives differ] → Standardize observable Relay behavior and validation categories while allowing runtime-specific opaque encoding.
- [The final change is large] → Land the v7 PR gate separately and organize v8 commits by shared seam, engine concern, adapter, and tests so reviewers can evaluate parity incrementally.

## Migration Plan

1. Correct PR #81 on its v7 base: add the module build, direct dependencies and shared test paths; repair canonical empty results; run shared and Datahike representation tests in isolation; add CI; merge into the latest DataScript branch.
2. Branch from the current `release/v8.0` head and import the merged Datahike module without merging obsolete shared-core history.
3. Add shared v8 characterization/conformance tests around Datomic and define the extended adapter capability contract.
4. Extract the portable engine and cache in parity-preserving slices while retaining Datomic-native snapshot, cursor, transaction, and proof behavior.
5. Upgrade DataScript, including CLJ/CLJS verification, recursive schemas, Relay behavior, deletion, and caching.
6. Upgrade Datahike using its reviewed data-access layer, including both attribute representations and multi-connection proof tests.
7. Run isolated module builds and the combined non-benchmark suite via nREPL, document migration and capability differences, and open a PR targeting `release/v8.0`.

If extraction causes a Datomic regression, revert the failing slice while keeping the adapter characterization tests. If a port cannot provide an exact cache proof, ship that adapter with caching disabled rather than weaken correctness.

## Open Questions

- Which current Datomic cursor fields are genuinely shared traversal state versus Datomic-only protected metadata? Resolve this during characterization before freezing the portable continuation record.
- Can Datahike expose an efficient exact maximum transaction proof per relation across all supported stores, or should its first v8 implementation use transactional relation generations?
- Which DataScript ClojureScript cache stores and cursor encodings should be supported initially beyond the in-memory reference implementations?

## Implementation Provenance

- Initial PR #81 head reviewed: `adf9cbcc2dd7e0104c1e93f0e19235e0d933ac36`.
- PR #81's former base: `fix/audit-root-causes-datascript` at `e47f403e8ce761bb400e6644d8152e015820a3a1`.
- Latest intended v7 DataScript base: `eacl/datascript` at `457b137bad63ae248728885d611421f3227aa75c`; PR #81 was retargeted to this branch before fixes.
- Corrected PR #81 head: `a40df8045a9a2580c01a12e99ea6a5ee44332d55`; merged into `eacl/datascript` as `8e7e464306a2c96dade8b0124d337bcb1cb14ab7`.
- Initial PR #84 / `release/v8.0` head observed: `00b19c090e1482a373a7efaa7aedf5eb7ac0777c`.

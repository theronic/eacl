# Permission-tree expansion final adversarial audit

Date: 2026-08-11

Scope: GitHub issue 111, `IAuthorization/expand-permission-tree`, its portable
CLJ/CLJS kernel, shipped adapter wiring, SpiceDB compatibility fixture, and
`formal/dafny/PermissionTree.dfy`.

## Closed loopholes

The implementation/proof review loop found and fixed the following concrete
gaps before final verification:

1. Adapter errors could spoof an EACL error namespace and carry internal ids.
   The boundary now passes through only the identical exception captured from
   a guarded kernel callback; all adapter-originated lookalikes are redacted.
2. Lazy schema-definition sequences were eagerly realized outside deadline and
   structural metering. Definition consumption is now incremental, checked
   before and after each realization, and bounded by
   `:max-schema-components`.
3. Token-metadata adapter failures could escape after a safe tree was built.
   Selected-adapter token issuance now uses the same redacting boundary.
4. Limit addition could construct `maximum-exact-integer + 1` in
   ClojureScript. Counters now compare against remaining capacity before
   addition and report only accepted portable-exact work.
5. Over-depth or over-node arrow branches could scan their source before their
   already-failing structural check. Depth and arrow-node capacity are now
   charged before definition or relationship work.
6. The initial Dafny relation model erased declared subject types and counted
   one definition for a sum-typed relation. It now models every declared
   subject type, rejects duplicates, filters direct leaves by declaration, and
   meters every normalized definition.
7. Dafny fuel did not charge same-relation children consistently. Component
   fuel now represents remaining emitted levels, with direct and arrow-target
   witnesses locking the boundary.
8. Dafny's successful-limit predicate omitted depth. A separate recursive
   `TreeDepthWithin` predicate is now part of the all-limits success gate.
9. Dafny arrow sources and permission lookup could accept contradictory,
   duplicate, or empty normalized permission definitions. Those states now
   return `InvalidSchema`.
10. The static dispatch/source-closure ledgers did not include the new path.
    They were reviewed and regenerated to 43 literal invoke sites per runtime,
    63 public roots, 58 source files, and 1,404 reachable definitions.
11. The reflection gate referenced the already-removed `eacl.mutation`
    namespace and could not run. The dead require was removed and
    `eacl.permission-tree` was added explicitly; the gate is clean.

## Final evidence

- Locked Dafny: 30 modules, 8,785 proof efforts, zero errors; PermissionTree
  contributes 62.
- Registered formal mutation controls: 2 tests, 215 assertions, zero failures
  or errors.
- Focused fresh JVM: 83 tests, 1,147 assertions, zero failures or errors.
- Complete CI non-benchmark JVM selection: 617 tests, 25,542 assertions, zero
  failures or errors.
- Fresh DataScript CLJS compilation/runtime: 210 tests, 7,424 assertions, zero
  failures or errors.
- Black-box SpiceDB Docker check: v1.56.0 at digest
  `sha256:c8a558a6cc1f9379fcdcab0171b623d65e7e5f95c998ebb7f937ca00a7c1598c`;
  normalized live topology equals the pinned expected tree.
- Dafny formatting, reflection, clj-kondo source closure, dispatch closure,
  JSON parsing, whitespace, and strict OpenSpec validation pass.

## Residual trusted boundary

Literal universal certainty is not a factual software claim. Within the
modelled and tested scope there is no known defect after this loop. The
following remain deliberately outside the proof and therefore prevent a claim
of mathematically complete end-to-end verification:

- the handwritten Clojure/ClojureScript source has bounded differential and
  cross-backend correspondence evidence, not mechanical Dafny extraction or a
  Clojure operational-semantics refinement proof;
- adapters and storage engines must supply immutable snapshots, complete and
  well-formed definitions/scans, and correct identity round trips;
- host collection/integer semantics, monotonic clocks, compilers, runtimes,
  cryptography, key management, and causal-token authentication are trusted or
  separately tested boundaries;
- one already-running synchronous adapter call or sequence realization cannot
  be hard-cancelled; EACL detects deadline overrun on return and starts no later
  work;
- SpiceDB equivalence is limited to EACL's supported union, same-resource, and
  single-arrow shallow subset after documented unordered-multiset
  normalization, not unsupported SpiceDB features, token bytes, or incidental
  order;
- independent security/formal-methods review remains an unmet repository-wide
  release obligation, so the assurance manifest correctly remains
  `:conditionally-verified`.

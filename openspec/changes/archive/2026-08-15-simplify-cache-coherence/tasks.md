## 1. Baseline and Contract

- [x] 1.1 Inventory every production read and write of `:eacl.graph/*`, `:eacl.mutation/*`, schema/relation/dependency mutation IDs, mutation envelopes, graph anchors, and journal pruning across all modules.
- [x] 1.2 Map every public schema, relationship, repair, object-deletion, and safe-retraction writer to the schema or distinct relation generations it must publish.
- [x] 1.2a Inventory every semantic dependency currently protected by graph-head CAS, including stale endpoint writes, retry idempotency, schema races, and safe deletion, before removing it.
- [x] 1.3 Add a shared graph-independent cache descriptor contract containing one schema generation and a canonical complete relation-generation vector.
- [x] 1.4 Update the backend adapter capability contract to replace graph-head and anchor-membership requirements with native snapshot identity, revision selection, source lifecycle, and generation reads.
- [x] 1.5 Update the verified cache decision inputs and assurance documentation to state the forward-history dependency-equivalence theorem without an ancestry premise.
- [x] 1.6 Add contract tests proving an incomplete dependency closure, missing generation, malformed generation, or unsupported adapter capability fails to exact evaluation or a typed pre-cache error.
- [x] 1.7 Amend the formal frame to allow an empty complete relation closure and unrelated object churn while proving built-in query-local identity/result equivalence; keep mutable custom identity exact-only.

## 2. Shared Cache and Lifecycle

- [x] 2.1 Refactor managed completed-answer keys to use source lifecycle, schema generation, semantic identity, result kind, and the complete sorted relation-generation vector.
- [x] 2.2 Refactor managed subproblem keys and proof memoization to use the same graph-independent source and generation contract.
- [x] 2.3 Preserve exact-generation atomic installation, monotone replacement, and late-publication detachment while removing any graph-derived snapshot fields.
- [x] 2.4 Keep `:coherence-authority :unknown` exact-only and redefine `:managed` solely as the exclusive stamped-writer/cache-frame assertion.
- [x] 2.5 Make `expire-cache!` rotate the complete client lifecycle, including exact, managed, continuation, cursor, and native-token source context.
- [x] 2.5a Capture the atomically replaceable completed-answer/subproblem lifecycle at the public request boundary; key every auxiliary retained namespace by the captured source lifecycle (and clear it on expiry); prove late old publication is unreachable even when expiry precedes cache lookup.
- [x] 2.6 Add configurable shared source-lifecycle identity for deployments that exchange tokens across processes and validate it as bounded canonical data.
- [x] 2.7 Ensure arbitrary historical, filtered, `since`, speculative, and caller-supplied database paths bypass completed answers before semantic key or generation work.
- [x] 2.8 Add race tests for current generation replacement, delayed older requests, late answer/subproblem publication, and lifecycle rotation during in-flight work.
- [x] 2.9 Rebuild public cache basis, external IDs, cursor/token context, and result metadata from the selected snapshot rather than a dependency-equivalent managed candidate.

## 3. Native Schema and Relation Generations

- [x] 3.1 Change Datomic managed descriptors to use the existing schema-version assertion and `:eacl/relation-version` exclusively, with no mutation-ID fallback.
- [x] 3.2 Initialize `:eacl/relation-version` for every new and existing Datomic relation and verify repeated current-transaction stamps compose.
- [x] 3.2a Represent relation proofs with physical assertion identity plus validated stored generation; never synthesize `[schema-generation relation-id :initial]` for a missing datom.
- [x] 3.3 Add native schema and relation generation attributes/values to Datahike using `:db/current-tx` or the proven equivalent for its configured schema mode.
- [x] 3.4 Add native schema and relation generation attributes/values to DataScript using `:db/current-tx` in both Clojure and ClojureScript.
- [x] 3.5 Update Datahike and DataScript managed descriptors to read complete native generation vectors and fail closed when any relation is unstamped.
- [x] 3.6 Make every public relationship writer atomically publish one generation per distinct affected relation on all three backends.
- [x] 3.7 Make schema writers publish the global schema generation and initialize all added relation generations atomically.
- [x] 3.8 Make object deletion, low-level tx-data helpers, integrity repair, and every batch publish the relations changed by that transaction slice.
- [x] 3.9 Retain relation-local and schema CAS guards only where they protect mutation semantics, and add tests showing cache correctness does not depend on CAS.
- [x] 3.9a Fence Datahike/DataScript schema replacement, relation removal, and stale client-planned relationship transactions with native old-equals-old predicates; keep reasserted schema write fences distinct from physical cache generations; test concurrent replacements, both schema/removal race orders, reverse-only ghosts, and cursor/cache stability after relationship writes.
- [x] 3.10 Add a migration/preparation operation that initializes missing native generations and returns diagnostics suitable for gating managed v4 mode.
- [x] 3.11 Add deduplicated commit-time endpoint identity guards to every client-calculated relationship writer/helper on every backend and reproduce the prepare/delete/commit stale-writer race as a failing-guard test.

## 4. Backend-Native Revision Tokens and Selection

- [x] 4.1 Define and implement the authenticated v4 native revision token payload without `graph-anchor`, including strict field, range, size, source-lifecycle, issuance, and expiry validation.
- [x] 4.2 Return a stable upgrade error for v3 graph-anchor tokens and prevent fallback to their numeric order hint.
- [x] 4.3 Implement Datomic token issuance from `db-after` basis `t`, targeted at-least synchronization, fully-consistent head synchronization, and explicit exact `d/as-of` selection with cache bypass.
- [x] 4.4 Implement Datahike token issuance and capability-gated branch/commit selection using its native retained commit model.
- [x] 4.4a Certify Datahike revision capabilities per supported store/schema configuration and advertise current-only behavior where stable commit/branch acquisition is not proven.
- [x] 4.5 Implement DataScript current-lifecycle revision comparison and remove unsupported exact-history claims or hidden retained-DB behavior.
- [x] 4.6 Update cursor envelopes and continuation decisions to use native source lifecycle, exact locator, schema generation, and relation proof without graph-head equality.
- [x] 4.7 Add cross-backend token/cursor tests for authentication, wrong lifecycle, expiry, at-least selection, exact capability, no-op writes, and legacy-token rejection.
- [x] 4.8 Add operator tests proving explicit lifecycle rotation invalidates prior cache, cursor, continuation, and native-token state.
- [x] 4.9 Decouple native token capability from `:coherence-authority`; test supported native tokens with exact-only `:unknown` cache authority.

## 5. Remove Graph and Journal from Ordinary Writes

- [x] 5.1 Stop requiring mutation-journal preparation when constructing clients, installing safe retraction, or performing ordinary schema and relationship writes.
- [x] 5.2 Remove graph-head CAS, graph-order replacement, mutation-record creation, previous-anchor expiry, and relation/schema mutation-ID updates from ordinary writer transaction data.
- [x] 5.3 Remove graph-state, anchor-membership, and pruning operations from the required shared backend adapter surface.
- [x] 5.4 Stop installing graph/journal schema on new Datomic, Datahike, and DataScript databases while tolerating legacy persisted attributes and datoms.
- [x] 5.5 Retain retry-idempotency/audit journal entry points only as an explicitly installed compatibility feature, and ensure enabling them does not alter cache keys or validity.
- [x] 5.6 Remove graph/journal dependencies from ordinary production namespaces and schema; keep the isolated compatibility implementation covered by compatibility tests.
- [x] 5.7 Add transaction-shape tests asserting ordinary writes contain no graph/journal datoms and no database-global graph CAS.

## 6. Stamp-Only Safe Entity Retraction

- [x] 6.1 Replace the shared safe-retraction envelope API with a target-only planning contract and remove random ID, fingerprint, clock, expiry, and canonical-envelope code.
- [x] 6.2 Rewrite the Datomic installed function as a self-contained `[db target]` function that emits peer cleanup, affected relation stamps, and native entity retraction only.
- [x] 6.2a Compute and clean the complete native component-deletion closure, and reject EACL schema/control entities anywhere in that closure.
- [x] 6.3 Add the numeric already-retracted-eid fallback using relation-schema enumeration and exact peer-index probes in both tuple directions.
- [x] 6.4 Preserve missing lookup-ref no-op behavior while accepting a raw numeric eid with no local entity datoms as a valid repair key.
- [x] 6.5 Rewrite Datahike named/direct safe-retraction functions to the target-only stamp contract across supported schema and writer configurations.
- [x] 6.6 Rewrite DataScript CLJ/CLJS named/direct safe-retraction functions to the target-only stamp contract.
- [x] 6.7 Bump installed function versions/digests and implement explicit, idempotent upgrade behavior for previously installed envelope-based definitions.
- [x] 6.8 Add tests for multiple invocations in one transaction, repeated targets, same-relation targets, different relations, self-relationships, and relationships between two deleted targets.
- [x] 6.8a Add component-cascade tests, protected-control-entity tests, and explicit tests/documentation rejecting same-transaction relationship additions involving a retracted target.
- [x] 6.9 Add cache differential tests showing live deletion and known-retracted-eid ghost repair invalidate affected grants without touching unrelated managed entries.
- [x] 6.10 Add operation-count tests proving live work is linear in component-closure size plus closure-local degree and stale-eid repair is bounded by relation schema plus matching ghosts.

## 7. Migration and Mixed-Version Safety

- [x] 7.1 Select and document a quiesced cutover; do not add an unreleased dual-write bridge whose intermediate protocol would enlarge the correctness surface.
- [x] 7.2 Prevent managed v4 cache/token mode from starting until all stored native generations are prepared and the operator has quiesced every writer that can omit native stamps or endpoint guards.
- [x] 7.2a State explicitly that database diagnostics prove stored preparation but cannot prove the absence of an old active process; require operator attestation before managed traffic resumes.
- [x] 7.3 Document the deployment sequence: quiesce writers/readers, deploy v4 everywhere, replace safe functions, prepare and verify generations, rotate lifecycle/token state, attest writer exclusivity, then resume managed traffic.
- [x] 7.3a Require a quiesced cutover between legacy three-argument and target-only two-argument safe-function invocations.
- [x] 7.4 Leave legacy graph and journal datoms inert and verify upgrade requires no destructive Datomic/Datahike schema operation.
- [x] 7.5 Add rollback tooling or documentation that rebaselines legacy graph state and rotates cache/token lifecycles before older binaries resume traffic.
- [x] 7.6 Reconcile or supersede graph-dependent artifacts and documentation from `redesign-cross-backend-freshness-cache` and `add-optional-safe-retract-entity-functions`.

## 8. Correctness and Differential Verification

- [x] 8.1 Add randomized cache-enabled versus cache-disabled differential tests covering relevant/unrelated additions, retractions, schema mutations, repairs, and recursive permissions on every backend.
- [x] 8.2 Add deterministic tests for empty initialized relations, first writes, multiple relations, missing stamps, malformed stamps, and custom identity exact-only fallback.
- [x] 8.3 Add concurrent read/write tests proving each request observes one complete pre- or post-transaction snapshot and never a hybrid.
- [x] 8.4 Add tests proving an older retained immutable DB remains valid for one in-flight request while its exact generation cannot replace a newer generation.
- [x] 8.5 Add explicit lifecycle tests for restore/reset/force simulation and verify correctness resumes only after expiry or client recreation.
- [x] 8.6 Update formal models, generated bindings, assurance matrices, and proof-route tests affected by removal of managed ancestry and graph authority.
- [x] 8.6a Model and verify stale endpoint-writer exclusion, component-closure cleanup/stamping, selected-snapshot metadata rendering, and lifecycle-object publication isolation.
- [x] 8.7 Run focused suites through nREPL with changed namespaces required using `:reload`, then run isolated module CLJ and CLJS suites.

## 9. Performance and Documentation

- [x] 9.1 Add structural datom-count benchmarks comparing stamp-only and legacy graph transaction shapes for single writes, batches, multiple relations, and safe retraction.
- [x] 9.2 Add unrelated-relation concurrency benchmarks proving no global graph-head CAS retries and recording remaining relation-local contention.
- [x] 9.3 Benchmark exact hits, first managed proof after basis rotation, managed promotion, and subsequent exact hits without listener or transaction-log work.
- [x] 9.3a Report the measured break-even terms `p(managed-hit) * evaluation-cost` versus dependency-proof plus cache overhead; do not assume a fast proof is automatically faster.
- [x] 9.4 Benchmark live-target and known-retracted-eid safe retraction across degree, relation-schema size, and unrelated database size.
- [x] 9.5 Update cache, consistency, backend, release, migration, and formal-assurance documentation with the forward-history theorem and explicit restore-expiry obligation.
- [x] 9.6 Update root and backend READMEs with native token compatibility, lifecycle rotation, managed writer authority, simplified safe-retraction syntax, multiple invocation examples, and ghost repair behavior.
- [x] 9.7 Record reproducible benchmark environment and results while keeping CI gates structural or ratio-based rather than absolute latency thresholds.
- [x] 9.8 Validate the OpenSpec change strictly and run repository formatting, lint, generated-code drift, module-isolation, and release-guard checks.

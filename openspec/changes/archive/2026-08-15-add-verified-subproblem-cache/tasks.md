## 1. Baseline and observability

> Archived 2026-08-15 with tasks 8.6–8.13 open. 8.6–8.11 (generated-authority cutover for traversal) were superseded when `adopt-stable-discovery-enumeration` replaced the generated indexed traversal with the CLJC stable reducer; 8.12 was carried out by later manifest/assurance-matrix updates; 8.13 is the same standing external-review gate as `formally-verify-eacl-engine` 13.10 (manifest `:conditionally-verified`).


- [x] 1.1 Add backend scan/probe counters that distinguish executed work from cache-avoided work without changing production results.
- [x] 1.2 Add cache statistics for projection, acyclic-denotation, recursive-component, continuation, and completed-answer tiers.
- [x] 1.3 Record same-process cache-free, completed-answer-only, and current memory/latency baselines with raw samples for direct, acyclic, recursive, forward, reverse, count, and cursor workloads.
- [x] 1.4 Add a distinct-top-level-query shared-subgraph fixture and assert that its completed-answer hit count remains zero.

## 2. Bounded exact-generation store

- [x] 2.1 Write failing CLJ and CLJS tests for weighted admission, tier isolation, eviction, expiry races, invalid values, exceptions, and concurrent identical misses.
- [x] 2.2 Implement a backend-neutral CLJC weighted subproblem store with separate projection and denotation budgets.
- [x] 2.3 Implement single-flight exact-key computation without caching failures, cancellation, partial values, or recursive self-waits.
- [x] 2.4 Attach one subproblem store to each private exact graph generation and make lifecycle expiry render delayed publication unreachable.
- [x] 2.5 Ensure `:cache? false` binds no subproblem store and invokes no admission, lookup, single-flight, or proof code.
- [x] 2.6 Replay Lore's `A, B, A` resource counterexample; separate non-evictable lifecycle-qualified flight ownership from cache entries; and enforce one shared bound on actual top-level callbacks, including unadmitted-to-cache work and generation replacement.

## 3. Shared projection chunks

- [x] 3.1 Write characterization and differential tests for ascending, descending, inclusive, exclusive, empty, terminal, bounded, and multi-chunk scans.
- [x] 3.2 Define versioned projection and direct-probe semantic keys over every answer-affecting adapter input.
- [x] 3.3 Implement lazy bounded cached chunks for `subject->resources` without realizing the full adjacency on a small page.
- [x] 3.4 Implement the direction-dual cached chunks for `resource->subjects`.
- [x] 3.5 Cache direct membership probes and verify agreement with both ordered scan directions.
- [x] 3.6 Add cache provenance and fetched/avoided value counters to projection resolution.
- [x] 3.7 Differentially compare cached and uncached projections over generated certified backend traces.

## 4. Authorization denotations

- [x] 4.1 Define the context-free concrete permission-node subproblem IR and its semantic key.
- [x] 4.2 Replace acyclic recursive-call memoization with request-local denotation resolution and publish only complete results.
- [x] 4.3 Prove and test that acyclic Boolean subproblems are independent of every admissible ancestor stack disjoint from the subproblem's reachable permission nodes.
- [x] 4.4 Extend recursive plan compilation with explicit SCC identities, dependencies, and deterministic component order.
- [x] 4.5 Implement request-local monotone recursive fixed-point evaluation for point checks.
- [x] 4.6 Publish anchored recursive root denotations only after queue exhaustion and fixed-point completion.
- [x] 4.7 Reuse completed direction-compatible anchored denotations in lookup/count rendering without caching partial page walks.
- [x] 4.8 Add cycle, diamond, mutual-recursion, multiple-seed, negative, deep-chain, wide-fanout, limit, and cursor differential tests.

## 5. Localized managed proofs

- [x] 5.1 Define portable relation dependency identities and an O(1) atomic projection-proof representation.
- [x] 5.2 Reuse the existing atomic Datomic, Datahike, and DataScript managed-writer relation mutation identities without a storage migration.
- [x] 5.3 Verify that relationship create, touch, delete, and object-deletion transactions stamp every affected relation atomically.
- [x] 5.4 Read and exact-generation-cache each complete relation projection proof from the selected immutable snapshot.
- [x] 5.5 Implement bounded proof composition for derived denotations and make over-bound or missing proofs exact-only.
- [x] 5.6 Implement managed atomic-projection reuse across forward revisions and reject source, schema, relation, direction, key, or proof mismatches.
- [x] 5.7 Add out-of-band-writer, missing-stamp, delete/recreate, unrelated-write, same-endpoint-different-relation, branch, restore, and reset adversarial tests.
- [x] 5.8 Benchmark relationship write amplification and managed hit proof cost on all backends.

## 6. Backend and runtime integration

- [x] 6.1 Route Datomic modular operations through the layered subproblem context while preserving exact/historical selection semantics.
- [x] 6.2 Route Datahike operations through the identical shared engine behavior and certify its relation-proof adapter.
- [x] 6.3 Route DataScript CLJ and CLJS operations through the identical shared engine behavior and certify portable key/proof values.
- [x] 6.4 Preserve raw/arbitrary DB evaluation as exact-only and prevent it from publishing into connection-owned generations.
- [x] 6.5 Verify cache expiry, stats, configuration limits, and cache-disabled behavior through every public client API.

## 7. Formal cache semantics

- [x] 7.1 Extend Dafny semantics with projection chunks, subproblem denotations, SCC completion, relation-proof atoms, tier budgets, and publication states.
- [x] 7.2 Prove projection chunk concatenation refines each finite ordered backend projection.
- [x] 7.3 Prove semantic-key separation over every answer-affecting input.
- [x] 7.4 Prove request-local and shared acyclic memoization observationally refine cache-free evaluation for admissible acyclic traversal contexts.
- [x] 7.5 Prove completed anchored recursive publication equals its least-fixed-point denotation and excludes partial traversal or SCC state.
- [x] 7.6 Prove exact-generation lifecycle, expiry, delayed publication, failure, and single-flight safety.
- [x] 7.7 Prove the localized managed frame theorem from relation mutation identities and complete atomic projection dependencies.
- [x] 7.8 Prove configured bounds on proof validation operations, admitted weight, chunks fetched per demanded prefix, and concurrent computations per key.
- [x] 7.9 Generate the cache decision kernel for Java and JavaScript and route production hit/admission/publication decisions through it.
- [x] 7.10 Extend TLA+ lifecycle exploration and mutation controls for projection, SCC, proof, eviction, expiry, lifecycle-qualified flights, actual callback execution, and concurrent publication defects.

## 8. Complete public-engine refinement

- [x] 8.1 Define a generated indexed command/response traversal state machine that owns data-valued continuations, queues, seen sets, reverse consumers, output order/ordinals, pagination/count state, limits, and typed failures.
- [x] 8.2 Prove the indexed state machine refines least-fixed-point authorization and deterministic public rendering under a certified immutable ordered-chunk adapter contract, with dimensionally separate resource measures.
- [x] 8.3 Generate the indexed state machine for Java and JavaScript and reject malformed, mismatched, unordered, duplicate, oversized, or non-progressing backend responses.
- [x] 8.4 Finish strict CLJ-to-generated-Java conversions for schema plans, scan commands/responses, state, queries, caches, cursors, results, limits, counters, and typed errors.
- [x] 8.5 Finish equivalent CLJS-to-generated-JavaScript conversions without duplicating authorization decisions.
- [ ] 8.6 Map every authorization-affecting branch of `can?`, lookup, count, relationship pagination, consistency, cursor, cache, and rendering to a generated theorem-backed decision.
- [ ] 8.7 Compare shadow value, ordering, page flags, counts, typed errors, provenance, graph identity, limits, and dimensionally matching resource counters with redacted diagnostics.
- [ ] 8.8 Classify every divergence against the formal semantics and retain each minimized witness before changing either implementation.
- [ ] 8.9 Make the generated verified path authoritative only after every proof, adapter, cross-runtime, mutation, differential, and performance gate passes.
- [ ] 8.10 Run all normal and heavy suites with generated authority on Datomic, Datahike, DataScript CLJ, and DataScript CLJS.
- [ ] 8.11 Remove duplicate legacy authorization decisions after authoritative cutover while retaining the cache-free formal evaluator and characterization corpus.
- [ ] 8.12 Update the assurance matrix, trusted boundary, assumption inventory, source/generated digests, and verification manifest for every public operation.
- [ ] 8.13 Keep the end-to-end verification claim withheld until an independent security/formal-methods review is recorded.

## 9. Adversarial and mutation verification

- [x] 9.1 Add coherent state-command generators mixing graph/schema writes, exact/history/branch selection, cache operations, cursors, limits, failures, and concurrency.
- [x] 9.2 Run cached, cache-free, formal, generated, public-client, CLJ, CLJS, Datomic, Datahike, and DataScript paths through one differential runner.
- [x] 9.3 Register mutants for omitted key fields, inclusive-bound errors, partial SCC publication, incomplete proof atoms, stale endpoint stamps, over-budget admission, exception poisoning, and lifecycle resurrection.
- [x] 9.4 Require every registered cache and engine mutant to be killed by proof, model check, certification, or differential regression.
- [x] 9.5 Replay all prior EACL formal counterexamples and confirm the layered cache introduces no regression.

## 10. Performance and release gates

- [x] 10.1 Benchmark shared arrows, shared negative probes, recursive SCCs, deep chains, wide fan-out, mixed principals, forward/reverse lookup, counts, and pagination on all backends.
- [x] 10.2 Demonstrate at least 50 percent fewer backend scan/probe operations and 25 percent lower p50 latency on the zero-final-hit shared-subgraph workload.
- [x] 10.3 Confirm identical completed-answer hot-hit and cache-free p50 latency regress by no more than 5 percent.
- [x] 10.4 Enforce configured heap, entry-weight, proof-operation, throughput, verification-time, generated-artifact-size, and benchmark-noise gates.
- [x] 10.5 Run the complete JVM, CLJS, backend-isolation, heavy, generated-boundary, formal, temporal, mutation, and strict OpenSpec validation suites.
- [x] 10.6 Publish the architecture, cache semantics, operational metrics, benchmark methodology/raw results, write-cost trade-offs, trusted assumptions, and exact verification status.

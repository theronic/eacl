## 1. Baseline, Dependencies, and the Standard Cache Boundary

- [x] 1.1 Run the targeted JVM cache, snapshot/lifecycle, and current CLJS suites before production edits; record any pre-existing functional failures in the change notes
- [x] 1.2 Verify Caffeine 3.2.4 and `theronic/cljs-cache` satisfy the common storage contract (explicit absence, nil/false values, hot-key churn, existing keys, equality edge cases, safe capacities, restore, iteration, and optimized CLJS) without requiring identical eviction victims; fix the fork's iterable and tick-normalization defects and verify normal/advanced CI is green
- [x] 1.3 Add the selected JVM and CLJS source dependencies to the root and isolated core bases, exclude upstream `com.github.pkpkpk/cljs-cache` from every Datahike source/build surface, and verify dependency trees resolve exactly one `cljs.cache` provider
- [x] 1.4 Verify isolated source dependency graphs resolve one `cljs.cache` provider and the Datahike exclusion without adding cache-policy or publication machinery to EACL
- [x] 1.5 Implement the private standard-cache adapter with Caffeine manual-cache operations on CLJ and theronic LRU operations on CLJS; verify safe-integer capacities, explicit absence, held hits, hot-key retention, restore, clear/entries, conditional replacement, and failures without a loader
- [x] 1.6 Verify hit/publication operations never execute a supplied validator or computation callback; add deterministic contention/instrumentation tests on CLJ and CLJS and document Caffeine's eventual eviction maintenance

## 2. Composite Keys and Formal Storage Abstraction

- [x] 2.1 Add the generic flat domain-key constructor plus complete exact-denotation and exact/managed-answer constructors; verify collision, lifecycle, basis, proof, semantic-input, and cross-runtime equality tests
- [x] 2.2 Extend managed proof descriptors with canonical dependency identity while preserving same-snapshot/forward-only causal validation; verify relevant writes miss, unrelated writes hit, empty proofs follow the existing contract, and future/sibling candidates fail closed
- [x] 2.3 Simplify the Dafny cache model to a bounded partial map of complete keys to validated completed values with arbitrary eviction, independent computation, page retention eligibility, and lifecycle detachment; verify Dafny proves the updated cache obligations
- [x] 2.4 Consolidate the TLA cache models around partial-map lookup/publication/eviction/expiry, retain managed-proof-bypass, partial-publication, and orphaned-lifecycle negative controls, and verify bounded cache-focused model checks accept the model while the retained functional mutants are killed
- [x] 2.5 Remove the generated current-cache stage/availability policy and host specialization while retaining exact-first/managed-second key/value and causal refinement evidence; regenerate JVM/JS artifacts and verify generated-runtime, specialization-removal, and portable differential tests

## 3. Flatten the Authorization and Subproblem Caches

- [x] 3.1 Replace `SubproblemStore` weighted/custom LRU state with independent standard-cache stores for exact denotations and completed answers, with exact/managed answer mode only in keys; delete the measured-negative physical projection tier and verify tier isolation, hot-key retention, count capacity, invalid-value rejection, and independently computed request-owned misses
- [x] 3.2 Preserve physical projection chunk, denotation, traversal, and service-admission bounds outside storage while deleting logical weight estimators/budgets and managed-subproblem proof retention; verify oversized semantic artifacts follow their existing authoritative contracts and no metric labels item count as bytes
- [x] 3.3 Replace `BasisCache` exact/managed generation registries with flat tier lookups and exact-first/managed-second resolution, deleting retained-bases, generation recency, promotion sidecars, repeat sightings, and policy aliases; verify exact, historical exact, managed promotion, and cache-disabled differential tests
- [x] 3.4 Put the 1,000-result page eligibility check in the common completed-answer publication boundary so exact and managed paths cannot diverge; verify 1,000-item pages may cache, 1,001/10,000-item pages return unchanged but never publish, and scalar/count/tree answers are unaffected
- [x] 3.5 Introduce a coherent outer runtime/cache lifecycle paired with source incarnation, make request snapshot selection capture or validate the same lifecycle, and verify expiry/restore races cannot publish old work into new stores
- [x] 3.6 Make `clear-answer-cache!` atomically rotate authorization answer/denotation, exact rendered-page, and continuation storage while preserving source-lifecycle proof health; full expiry/restore installs fresh non-exported children; verify late publication, sticky proof distrust, derived/cursor preservation on narrow clear, and complete detachment on full rotation
- [x] 3.7 Remove weight, retained-generation, repeat-admission, publication-attempt, custom-recency, and nested subproblem configuration; use flat positive `:max-entries` and `:denotation-max-entries` capacities with one telemetry switch and verify removed fields fail with typed configuration errors
- [x] 3.8 Replace the legacy cache snapshot/key layout with flat entries, off-side cache reconstruction, operation-specific validation, atomic lifecycle installation, and typed rejection of semantically incompatible input; exclude exact rendered pages and library-private policy metadata; verify all core, Datomic, Datahike, and DataScript lifecycle snapshot tests
- [x] 3.9 Run cache-enabled versus `:cache? false` differential/model tests across point, forward/reverse page, count, recursion, exact, and managed paths; verify values, order, cursors, errors, selected snapshots, and mandatory work limits are identical
- [x] 3.10 Make validated publication and atomic restore the only supported authorization/subproblem ingress, require an explicit callable validator on every low-level live publisher, store derived-schema artifacts directly, remove repeated exact-hit artifact/ABI validation while retaining managed causal checks and cursor authentication/expiry; verify omitted/invalid-validator ingress rejection and ordinary-hit regressions on CLJ and CLJS

## 4. Migrate or Delete the Remaining Shared Stores

- [x] 4.1 Replace the continuation store's weight/family/tombstone/dual-order machinery with standard keyed retention while keeping context, lineage, latest-progress, and deterministic replay validation outside storage; keep optional expiry in authenticated cursor decoding rather than continuation entries, and verify continuation unit and cross-backend reuse tests
- [x] 4.2 Replace cursor token/reverse-token, construction-context, and key-context handwritten caches with independent standard stores; canonical-copy request-derived token/context keys and values on misses; admit only bounded canonical scalar/vector cursor object IDs while rejecting metadata, records, non-vector sequentials, alternate integers, every map/set ID, and signed zero; cap the nonblocking JVM authenticator idle object pool nested in each key context so concurrency bursts are not retained; validate each held value once after an ordinary access update, evict invalid or expired entries, and verify secure-format, encryption-context, growth, concurrency, and Relay cursor tests
- [x] 4.3 Flatten cross-request derived-schema/plan entries into standard-cache domain keys, keep request-local schema memos plain, delete derived fields with no production reads, and verify schema-generation, expression-persistence, indexed, and request-context suites
- [x] 4.4 Delete the disabled relationship-observation cache and its option/runtime/stats/instrumentation surfaces after source-closure confirmation; verify authorization metrics and backend work counters used by product gates remain intact
- [x] 4.5 Delete the unreachable Datomic provider `CacheStore`/`LocalStore` contracts and dead `RevisionCheckpoints`, retain the core no-cache sentinel, and verify provider rejection, trusted-surface, consistency, and Datomic cache tests
- [x] 4.6 Migrate bounded stable-page checkpoint identities to the standard cache boundary while retaining the per-checkpoint admission-count cap and latest-progress semantics outside storage; verify accepted hot checkpoints survive churn and rejected boundaries receive no deliberate access update on CLJ/CLJS. Document the stable reducer's consumable checkpoint sidecar, saturating diagnostic-warning set, DataScript capacity-one adapter wrapper, evaluator memos/worklists, backend caches, and authoritative consistency state as intentional non-migrations

## 5. Remove the Duplicate Page-Navigation Cache

- [x] 5.1 Delete `PageNavigationCache` and its route/boundary/alias/digest/access-queue machinery; add one exact-basis operation-typed transport-page value for complete lookup-resource, lookup-subject, and relationship-read raw requests when cursor expiry is disabled, keyed by the full authenticated consistency descriptor, so repeated hits skip cursor decode, identity/row rendering, dependency/proof work, and token construction
- [x] 5.2 Remove visited-public-page/current-page-cache decisions from Dafny/TLA temporal models, generated wrappers, mutations, and public source-closure roots; regenerate artifacts and verify temporal/formal gates
- [x] 5.3 Replace private navigation-cache tests/benchmarks with end-to-end Next/Previous and exact transport-hit coverage for lookup and relationship-read pages; verify identical objects, ordering, cursors, page flags, exact/floor consistency separation, permitted first reverse recomputation, TTL and malformed-input bypass, operation-typed values, bounded canonical scalar/vector IDs, rejected map/set IDs, plain copied query containers, and zero repeat cursor decode/backend identity work
- [x] 5.4 Run reverse-first-miss, rendered-hit, and pagination workload gates; if a threshold fails, fix exact render identity or continuation reuse without reintroducing routes, boundary indexes, or aliases, then rerun to green

## 6. Documentation and Simplification Closure

- [x] 6.1 Update README and cache/subproblem/continuation docs for Caffeine on JVM, theronic LRU on CLJS, flat keys, exact rendered-page reuse, page-only >1,000 retention ineligibility, count capacities, removed options/stats/providers, and trusted snapshot input; verify documentation examples execute or parse
- [x] 6.2 Run the production source inventory after all deletions and verify no custom weighted store, generation registry, repeat window, touch sidecar, tombstone/compaction path, provider backend, relationship-observation cache, or page-navigation implementation remains reachable
- [x] 6.3 Compile the core, Datomic, DataScript, and Datahike modules; verify one cache provider per runtime, the Datahike exclusion, CLJC sources, and snapshot public APIs

## 7. Correctness and Performance Gates

- [x] 7.1 Run targeted JVM Caffeine adapter, cache, subproblem, continuation, cursor/secure-format, Relay, rendered-hit, snapshot/lifecycle, and build tests after the final adversarial fixes; verify zero failures/errors
- [x] 7.2 Compile and run DataScript CLJS tests with normal and advanced/elided-assert optimizations, including the theronic LRU contract trace; verify both runners propagate failures and finish green
- [x] 7.3 Run Datomic, Datahike, and DataScript contract/cache-model/snapshot suites plus isolated module tests; verify dependency resolution and public behavior are cross-backend equivalent
- [x] 7.4 Run the cache-specific Dafny proofs, bounded temporal checks and negative controls, generated-runtime differential tests, and source inventory; verify semantic keys, lifecycle detachment, optional retention, and cache-free equivalence
- [x] 7.5 Run focused hot-hit, shared-subgraph, managed-proof, continuation, pagination/oscillation, cache-free, allocation, and cache-contention benchmarks; verify existing correctness/work thresholds and accepted latency/allocation gates
- [x] 7.6 Run the ordinary full JVM/CLJS product suites and focused cache/authorization benchmarks after the final adversarial fixes; verify zero functional failures and no unexplained performance regression

## 8. Publish for Product Testing

- [x] 8.1 Publish the repaired `theronic/cljs-cache` Git SHA with green normal/advanced CI so CLJS/DataScript demos can consume it directly
- [ ] 8.2 Publish the EACL feature branch for product testing after the functional and performance gates pass; include tester commands and the pinned CLJS Git SHA

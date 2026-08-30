## 1. Baseline, Dependencies, and the Standard LRU Boundary

- [x] 1.1 Run the targeted JVM cache, snapshot/lifecycle, and current CLJS suites before production edits; record any pre-existing functional failures in the change notes
- [x] 1.2 Verify the selected `core.cache` and `theronic/cljs-cache` implementations replay equivalent LRU traces (explicit absence, nil/false values, hot-key churn, existing keys, equality edge cases, safe capacities, sequential restore, iteration, near-max-safe ticks, and optimized CLJS); fix the fork's iterable and tick-normalization defects and verify normal/advanced CI is green
- [x] 1.3 Add the selected JVM and CLJS source dependencies to the root and isolated core bases, exclude upstream `com.github.pkpkpk/cljs-cache` from every Datahike source/build surface, and verify dependency trees resolve exactly one `cljs.cache` provider
- [x] 1.4 Verify isolated source dependency graphs resolve one `cljs.cache` provider and the Datahike exclusion without adding cache-policy or publication machinery to EACL
- [x] 1.5 Implement the private CLJC standard-LRU adapter using only empty-seed `lru-cache-factory`, `has?`, `lookup`, `hit`, `miss`, `evict`, portable sequence enumeration, and local atoms; verify tests cover safe-integer capacities, explicit absence, atomic held hits, LRU refresh/eviction, sequential restore, clear/entries, and failures without a loader
- [x] 1.6 Verify atomic hit/publication retry functions contain only pure library transformations and never repeat a supplied validator or computation callback; add deterministic contention/instrumentation tests on CLJ and CLJS

## 2. Composite Keys and Formal Storage Abstraction

- [x] 2.1 Add the generic key-v2 domain constructor plus complete exact-denotation and exact/managed-answer constructors; verify collision, lifecycle, basis, proof, ABI, semantic-input, and cross-runtime equality tests
- [x] 2.2 Extend managed proof descriptors with canonical dependency identity while preserving same-snapshot/forward-only causal validation; verify relevant writes miss, unrelated writes hit, empty proofs follow the existing contract, and future/sibling candidates fail closed
- [x] 2.3 Simplify the Dafny cache model to a bounded partial map of complete keys to validated completed values with arbitrary eviction, independent computation, page retention eligibility, and lifecycle detachment; verify Dafny proves the updated cache obligations
- [x] 2.4 Consolidate the TLA cache models around partial-map lookup/publication/eviction/expiry, retain managed-proof-bypass, partial-publication, and orphaned-lifecycle negative controls, and verify bounded cache-focused model checks accept the model while the retained functional mutants are killed
- [x] 2.5 Remove the generated current-cache stage/availability policy and host specialization while retaining exact-first/managed-second key/value and causal refinement evidence; regenerate JVM/JS artifacts and verify generated-runtime, specialization-removal, and portable differential tests

## 3. Flatten the Authorization and Subproblem Caches

- [x] 3.1 Replace `SubproblemStore` weighted/custom LRU state with independent standard LRU stores for exact denotations and completed answers, with exact/managed answer mode only in keys; delete the measured-negative physical projection tier and verify tier isolation, LRU hot-key retention, count capacity, invalid-value rejection, and independently computed request-owned misses
- [x] 3.2 Preserve physical projection chunk, denotation, traversal, and service-admission bounds outside storage while deleting logical weight estimators/budgets and managed-subproblem proof retention; verify oversized semantic artifacts follow their existing authoritative contracts and no metric labels item count as bytes
- [x] 3.3 Replace `BasisCache` exact/managed generation registries with flat tier lookups and exact-first/managed-second resolution, deleting retained-bases, generation recency, promotion sidecars, repeat sightings, and policy aliases; verify exact, historical exact, managed promotion, and cache-disabled differential tests
- [x] 3.4 Put the 1,000-result page eligibility check in the common completed-answer publication boundary so exact and managed paths cannot diverge; verify 1,000-item pages may cache, 1,001/10,000-item pages return unchanged but never publish, and scalar/count/tree answers are unaffected
- [x] 3.5 Introduce a coherent outer runtime/cache lifecycle paired with source incarnation, make request snapshot selection capture or validate the same lifecycle, and verify expiry/restore races cannot publish old work into new stores
- [x] 3.6 Make `clear-answer-cache!` atomically rotate only authorization answer/denotation and continuation storage while preserving source-lifecycle proof health; full expiry/restore installs fresh non-exported children; verify late publication, sticky proof distrust, derived/cursor preservation on narrow clear, and complete detachment on full rotation
- [x] 3.7 Remove weight, retained-generation, repeat-admission, publication-attempt, custom-recency, and nested subproblem configuration; use flat positive `:max-entries` and `:denotation-max-entries` capacities with one telemetry switch and verify removed fields fail with typed configuration errors
- [x] 3.8 Replace cache snapshot/key ABI v1 with deterministic flat v2 entries, canonical sorted export, off-side LRU reconstruction, operation-specific validation, atomic lifecycle installation, and typed v1 rejection; verify all core, Datomic, Datahike, and DataScript lifecycle snapshot tests
- [x] 3.9 Run cache-enabled versus `:cache? false` differential/model tests across point, forward/reverse page, count, recursion, exact, and managed paths; verify values, order, cursors, errors, selected snapshots, and mandatory work limits are identical
- [x] 3.10 Make validated publication and atomic restore the only supported authorization/subproblem ingress, require an explicit callable validator on every low-level live publisher, store derived-schema artifacts directly, remove repeated exact-hit artifact/ABI validation while retaining managed causal checks and cursor authentication/expiry; verify omitted/invalid-validator ingress rejection and ordinary-hit regressions on CLJ and CLJS

## 4. Migrate or Delete the Remaining Shared Stores

- [x] 4.1 Replace the continuation store's weight/family/tombstone/dual-order machinery with standard LRU keyed retention while keeping context, lineage, latest-progress, and deterministic replay validation outside storage; keep optional expiry in authenticated cursor decoding rather than continuation entries, and verify continuation unit and cross-backend reuse tests
- [x] 4.2 Replace cursor token/reverse-token, construction-context, and key-context handwritten caches with independent standard LRU stores; cap the nonblocking JVM authenticator idle object pool nested in each key context so concurrency bursts are not retained; validate each held value once after a transient LRU touch, evict invalid or expired entries, and verify secure-format, encryption-context, growth, concurrency, and Relay cursor tests
- [x] 4.3 Flatten cross-request derived-schema/plan entries into standard LRU domain keys, keep request-local schema memos plain, delete derived fields with no production reads, and verify schema-generation, expression-persistence, indexed, and request-context suites
- [x] 4.4 Delete the disabled relationship-observation cache and its option/runtime/stats/instrumentation surfaces after source-closure confirmation; verify authorization metrics and backend work counters used by product gates remain intact
- [x] 4.5 Delete the unreachable Datomic provider `CacheStore`/`LocalStore` contracts and dead `RevisionCheckpoints`, retain the core no-cache sentinel, and verify provider rejection, trusted-surface, consistency, and Datomic cache tests
- [x] 4.6 Migrate bounded stable-page checkpoint identities to standard LRU while retaining the per-checkpoint admission-count cap and latest-progress semantics outside storage; verify accepted hot checkpoints survive churn and rejected boundaries do not touch recency on CLJ/CLJS. Document the stable reducer's consumable checkpoint sidecar, saturating diagnostic-warning set, DataScript capacity-one adapter wrapper, evaluator memos/worklists, backend caches, and authoritative consistency state as intentional non-migrations

## 5. Remove the Duplicate Page-Navigation Cache

- [x] 5.1 Delete `PageNavigationCache`, page/route/boundary/alias/digest/access-queue functions, runtime construction/clear/stats wiring, and all Relay/orchestration lookup/remember branches; verify compilation and Relay tests use only internal completed answers plus current externalization
- [x] 5.2 Remove visited-public-page/current-page-cache decisions from Dafny/TLA temporal models, generated wrappers, mutations, and public source-closure roots; regenerate artifacts and verify temporal/formal gates
- [x] 5.3 Replace private page-cache tests/benchmarks with end-to-end Next/Previous oscillation coverage; verify identical objects, ordering, cursors, page flags, exact snapshots, permitted first reverse recomputation, and subsequent internal-answer/continuation reuse
- [x] 5.4 Run the reverse-first-miss and pagination workload gates; if an existing threshold fails, fix internal semantic keys or continuation reuse without reintroducing externalized page aliases, then rerun to green

## 6. Documentation and Simplification Closure

- [x] 6.1 Update README and cache/subproblem/continuation docs for standard LRU, flat keys, page-only >1,000 retention ineligibility, count capacities, removed options/stats/providers, trusted snapshot bytes, and snapshot v2; verify documentation examples execute or parse
- [x] 6.2 Run the production source inventory after all deletions and verify no custom weighted store, generation registry, repeat window, touch sidecar, tombstone/compaction path, provider backend, relationship-observation cache, or page-navigation implementation remains reachable
- [x] 6.3 Compile the core, Datomic, DataScript, and Datahike modules; verify one cache provider per runtime, the Datahike exclusion, CLJC sources, and snapshot public APIs

## 7. Correctness and Performance Gates

- [x] 7.1 Run targeted JVM standard-store, cache, subproblem, continuation, cursor/secure-format, Relay, snapshot/lifecycle, and build tests; verify zero failures/errors
- [x] 7.2 Compile and run DataScript CLJS tests with normal and advanced/elided-assert optimizations, including the portable LRU trace; verify both runners propagate failures and finish green
- [x] 7.3 Run Datomic, Datahike, and DataScript contract/cache-model/snapshot suites plus isolated module tests; verify dependency resolution and public behavior are cross-backend equivalent
- [x] 7.4 Run the cache-specific Dafny proofs, bounded temporal checks and negative controls, generated-runtime differential tests, and source inventory; verify semantic keys, lifecycle detachment, optional retention, and cache-free equivalence
- [x] 7.5 Run focused hot-hit, shared-subgraph, managed-proof, continuation, pagination/oscillation, cache-free, allocation, and atom-contention benchmarks; verify existing correctness/work thresholds and accepted latency/allocation gates
- [x] 7.6 Run the ordinary full JVM/CLJS product suites and focused cache/authorization benchmarks; verify zero functional failures and no unexplained performance regression

## 8. Publish for Product Testing

- [x] 8.1 Publish the repaired `theronic/cljs-cache` Git SHA with green normal/advanced CI so CLJS/DataScript demos can consume it directly
- [ ] 8.2 Publish the EACL feature branch for product testing after the functional and performance gates pass; include exact tester commands and the pinned CLJS Git SHA

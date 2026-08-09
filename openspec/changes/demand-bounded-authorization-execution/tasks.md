## 1. Characterize the Existing Cost and Correctness Boundaries

- [x] 1.1 Convert the recursive point/count findings in the 2026-08-08 analysis report into deterministic operation-count fixtures for shallow, deep, negative, cyclic, diamond, mutual-recursion, and broad-union graphs.
- [x] 1.2 Add cold `:cache? true` versus `:cache? false` trace tests that record evaluator direction, generated commands, adapter responses, fetched values, stopping reason, and resource-limit outcome.
- [ ] 1.3 Add adversarial cache-candidate tests proving stale, malformed, oversized, and proof-incomplete entries cannot cause extra backend commands before the ordinary demand trace.
- [x] 1.4 Add characterization tests for projection-cache overfetch, full-denotation point/count misses, recursive first-page ordering, and prove the retired single-flight/semaphore paths cannot make callers wait.
- [x] 1.5 Add deterministic concurrency fixtures that expose Datomic schema-lock convoys, late old-generation publication, stale DataScript/Datahike mutation heads, and relation-removal races.
- [x] 1.6 Record checked-in client-private cache-bookkeeping and latency baselines for point, bounded count, first page, and continued page workloads without treating wall-clock measurements as semantic proofs.

## 2. Normalize Public Execution and Deadline Contracts

- [x] 2.1 Add a shared execution-contract normalizer for operation kind, demand shape, `:evaluation`, effective `:timeout-ms`, absolute monotonic deadline, traversal limits, and cache-attempt envelope.
- [x] 2.2 Validate `:evaluation` as `:demand` or `:complete-denotation` and reject invalid values before consistency selection, cache access, or traversal.
- [x] 2.3 Add a finite client execution-timeout default and positive per-request `:timeout-ms` override across public point, lookup, and count entry points in CLJ and CLJS.
- [x] 2.4 Define finite client evaluation-reserve and atomic-publication-attempt defaults with strict option validation and no dead or decorative stage/provider/decode controls.
- [x] 2.5 Thread one absolute monotonic deadline and remaining budget through consistency selection, schema/plan work, client-private cache access, generated evaluation, rendering, token work, and publication.
- [x] 2.6 Implement `:eacl.execution/deadline-exceeded` diagnostics with safe operation, stage, configured timeout, and bounded consumed-work fields; prohibit conversion to authorization values.
- [ ] 2.7 Add deterministic fake-monotonic-clock and bounded-blocking-adapter seams shared by CLJ and CLJS deadline tests.
- [x] 2.8 Normalize partial traversal-limit configuration to one complete map and bind that identical map through every public/backend facade, semantic cache identity, and generated traversal boundary; add cross-backend strict-limit regressions.

## 3. Make the Generated Evaluator Own Demand and Logical Order

- [ ] 3.1 Extend the formal/generated request model with operation demand, evaluation mode, logical result ordinal, stopping reason, and deadline checks at quantum and adapter-command boundaries.
- [x] 3.2 Implement target-anchored demand evaluation for positive and negative recursive point checks without constructing an unrelated subject-rooted denotation.
- [x] 3.3 Implement bounded count stopping at graph exhaustion or `L+1` distinct ordered results and exact count exhaustion when no `:count-limit` is supplied.
- [x] 3.4 Implement forward page stopping at graph exhaustion or `N+1` ordered distinct results beyond the authenticated boundary.
- [x] 3.5 Define canonical rule/component order, endpoint order, queue discipline, duplicate handling, and a complete tie-breaker as one versioned logical ordering ABI.
- [x] 3.6 Make logical order invariant under adapter chunk size, generated fuel, host wave batching, cache hit pattern, page size, map/set iteration, and runtime.
- [x] 3.7 Emit exact validated adapter commands whose direction, bounds, inclusivity, maximum response size, and continuation are chosen only by the generated evaluator.
- [x] 3.8 Regenerate and review CLJ/CLJS authorities and generated-boundary manifests without hand-maintained semantic forks.

## 4. Replace Cache-Directed Traversal with Exact Bounded Reuse

- [x] 4.1 Move projection reuse to the generated adapter command/response boundary and key entries by the complete command, selected snapshot/source identity, schema proof, and adapter/engine ABI.
- [x] 4.2 Remove cache-owned scan chunking, lazy projection widening, fetch-ahead, and post-stopping traversal from all backend adapters.
- [x] 4.3 Introduce disjoint artifact types for completed Booleans, exact bounded responses, exact pages, exact command responses, private continuations, and completed denotations.
- [x] 4.4 Require complete dependency framing and completion provenance before any artifact can answer a semantic request; keep partial SCC/worklist/prefix/timeout state non-answering.
- [x] 4.5 Implement typed exact-key artifact indexes with validated native-weight metadata and construction-time per-tier/per-entry ceilings.
- [x] 4.6 Select only a sufficient compatible client-private artifact and reject caller-supplied provider stores before request execution.
- [x] 4.7 Make cache eligibility and proof lifting use zero cache-only backend commands; treat unavailable complete proof material as a miss.
- [x] 4.8 Enforce the cache trace refinement law: every cache-enabled semantic trace is the corresponding cache-disabled trace with only matching commands removed, and a total miss has an equal trace.
- [x] 4.9 Preserve naturally exhausted denotations and exact already-demanded command responses without performing additional completion or publication work after the stopping decision.

## 5. Remove Caller-Waiting Cache Coordination

- [x] 5.1 Remove cache-owned blocking single-flight joins and the fair cache computation semaphore from point, lookup, and count authorization paths.
- [x] 5.2 Let identical misses evaluate independently and implement bounded best-effort CAS publication where a compatible winner is retained and losing candidates are discarded.
- [x] 5.3 Skip client-private cache work when the remaining request budget cannot preserve the normalized evaluation reserve.
- [x] 5.4 Make malformed local entries, lookup failure, capacity rejection, eviction, and publication contention cache misses without swallowing consistency, cursor, or deadline errors.
- [x] 5.5 Replace expiry, reset, restore, branch, and schema lifecycle mutation with atomic generation detachment that prevents late old-generation publication from reaching the new generation.
- [x] 5.6 Ensure request cancellation/deadline completion owns all cache computation and leaves no background warming or detached traversal after the request stops.
- [ ] 5.7 Move overload control, if enabled, to a cache-neutral pre-selection admission boundary with identical policy for `:cache? true` and false.

## 6. Integrate Demand Semantics into Public Operations

- [x] 6.1 Route cache-enabled and cache-disabled point checks through the same normalized demand evaluator and preserve identical Boolean/error/resource-limit behavior on misses.
- [x] 6.2 Route `count-resources` and `count-subjects` through `L+1` bounded demand when `:count-limit` is present and exact exhaustion only when the caller requests an exact count.
- [x] 6.3 Route first and continued recursive lookups through `N+1` demand while retaining at most the exact page response and private continuation authorized by that request.
- [x] 6.4 Implement explicit `:complete-denotation` execution for point, count, and lookup operations without changing their public semantic value or consistency contract.
- [x] 6.5 Reject recursive bare `:last N` in demand mode with a typed complete-evaluation-required error and allow it only under explicit complete-denotation evaluation.
- [x] 6.6 Implement `:last N :before cursor` through deterministic prefix replay with an `N`-sized retained window, request deadline, and traversal limits.
- [x] 6.7 Remove any prewarm, adaptive completion, repeated-demand promotion, or post-response traversal surface introduced by earlier v8 cache changes.

## 7. Version Recursive Cursors and Bounded Replay

- [x] 7.1 Define a new authenticated cursor envelope binding source/branch/incarnation, graph/dependency proof, query/operation digest, schema/engine/adapter/identity/order ABI, direction, ordinal, boundary, evaluation mode, limits, expiry, and consistency constraints.
- [x] 7.2 Mint page boundaries from external logical result identities rather than unstable internal EID discovery or a complete-denotation sort.
- [x] 7.3 Resume retained private continuation state only for the identical snapshot, query, execution contract, and cursor position.
- [x] 7.4 On continuation-cache miss, deterministically replay to the authenticated ordinal/boundary and demand only the next `N+1`, charging replay to the same deadline and traversal limits.
- [x] 7.5 Permit proof-equivalent continuation on a newer current snapshot only when complete dependency and order proofs establish no duplicate, omission, or reorder.
- [x] 7.6 Add verified exact-snapshot fallback for history-capable backends when it does not violate a newer freshness floor; otherwise return typed stale-cursor or consistency-conflict errors.
- [x] 7.7 Reject old or mismatched ordering ABI cursors before traversal and publish an explicit v8 cursor migration boundary.

## 8. Derive All Read Semantics from One Immutable Snapshot

- [x] 8.1 Refactor each public request to select one immutable database value before schema, plan, identity, dependency, cache-generation, traversal, rendering, and response-token work.
- [x] 8.2 Key schema catalogs, recursive plans, dependency closures, and derived memos by source identity, selected-snapshot schema proof, and every answer-affecting engine/adapter ABI.
- [x] 8.3 Replace the Datomic client `schema-state` read/write latch with bounded immutable generation entries and nonblocking atomic generation installation.
- [x] 8.4 Remove every EACL-owned schema lock/monitor/semaphore acquisition from authorization and schema/relationship read paths while preserving backend-native snapshot/transaction behavior.
- [x] 8.5 Prove with concurrent tests that a long `S0` read and an `S1` schema commit neither block each other in EACL nor mix semantic inputs.
- [x] 8.6 Test out-of-band schema commits and missed/late invalidation callbacks to prove generation keys, not callback timing, determine correctness.

## 9. Make Cross-Backend Writers Optimistically Atomic

- [x] 9.1 Capture schema generation, graph head, relation identities, and endpoint identities from the same `S0` used to calculate every schema or relationship transaction.
- [x] 9.2 Preserve Datomic transactor-side schema CAS and relation-unused guards while removing any correctness dependency on client-local locking.
- [x] 9.3 Change DataScript mutation construction to pass the original calculation head into the serialized transaction instead of rereading a later head during journal submission.
- [x] 9.4 Change Datahike mutation construction to assert the original calculation head through backend-atomic transaction preconditions rather than adopting a newer head at submission.
- [x] 9.5 Add relation-removal versus tuple-creation guards so no committed snapshot can contain a tuple whose required relation definition was removed.
- [ ] 9.6 Implement typed concurrent-write conflicts and idempotent retries that retain mutation identity, reselect a snapshot when allowed, and remain inside the request deadline.
- [ ] 9.7 Run deterministic interleavings for same-base schema replacements, delayed stale submissions, relation removal, endpoint changes, ambiguous outcomes, and cross-client writers.

## 10. Make DataScript Current-Basis-Only

- [x] 10.1 Capture exactly one current `ds/db` per DataScript request and use it for schema, cache, traversal, cursor, rendering, and token work until completion.
- [x] 10.2 Remove DataScript `:at-exact-snapshot` capability advertisement, exact locator/handle creation, DB-value registry retention, and `:exact-snapshot-registry-size` construction option.
- [x] 10.3 Implement DataScript `:minimize-latency`, `:fully-consistent`, and deadline-bounded `:at-least-as-fresh` selection over current immutable DB values and authenticated mutation anchors.
- [x] 10.4 Reject DataScript exact requests before cache access with the normalized unsupported-capability error and migration data.
- [x] 10.5 Validate older cache candidates only after current snapshot selection, keep `:cache-basis` as the computation basis rather than relabeling it as selected `S`, and miss on every relevant proof change.
- [x] 10.6 Continue DataScript cursors only on proof-equivalent current DB values; reject relevant schema, relationship, identity, source, or order changes without silent restart.
- [x] 10.7 Run one shared CLJ/CLJS corpus covering current selection, at-least floors, raw/managed writes, cache hits/misses, unsupported exact requests, and cursor mutation outcomes.

## 11. Expose Honest Provenance, Metrics, and Diagnostics

- [x] 11.1 Preserve `:cached?`/`:cache-basis`, expose evaluation mode on detailed point responses, and bind evaluation/snapshot identity into count/page cache and cursor identities without inventing unimplemented public provenance fields.
- [x] 11.2 Separate exact/proof-lifted hits, misses, bypasses, local failures, publication admissions/rejections/races/contention/detachment, and oversized rejections in metrics.
- [x] 11.3 Count the shipped observable dimensions independently: generated traversal work through the stats seam; cache-avoided commands and fetched values; continuation/page-store outcomes; and mutation conflicts in typed errors.
- [ ] 11.4 Report deadline stage and bounded in-flight-command overrun without claiming cancellation of uninterruptible backend/runtime work.
- [x] 11.5 Ensure a request that misses and loses a concurrent publication race remains a miss in telemetry and never inherits another request's latency or failure label.

## 12. Prove, Benchmark, Document, and Release the Contract

- [x] 12.1 Prove generated point/count/page stopping soundness, incomplete-artifact non-answering, logical-order totality, and cache trace refinement for the supported recursive fragment.
- [x] 12.2 Extend formal cache, consistency, temporal, pagination, and snapshot models with bounded cache attempts, lifecycle detachment, deadline boundaries, and optimistic writer schedules.
- [x] 12.3 Run differential matrices across cache states, evaluation modes, limits, deadlines, chunk/fuel/batch/page permutations, graph shapes, mutation authority, and all supported backends.
- [ ] 12.4 Run 1k, 10k, 100k, and acceptance-gated larger broad-principal fixtures proving point, bounded count, and first-page work is independent of unrelated denotation width after the demand sentinel.
- [x] 12.5 Add latency gates for client-private cache bookkeeping using checked-in fixed and ratio thresholds, while keeping semantic trace equality as the primary deterministic release gate.
- [x] 12.6 Run CLJ, CLJS advanced, adapter certification, generated-boundary, Dafny, TLA+, mutation, reflection, cache differential, concurrency, and strict OpenSpec verification suites.
- [x] 12.7 Update cache, consistency, cursor, timeout, DataScript, and v8 migration documentation with explicit defaults, error contracts, removed options, and complete-denotation examples.
- [ ] 12.8 Update `eacl-solidjs` and `eacl-explorer` examples to label exact unbounded demo counts and show bounded production count/page patterns without making consumer changes part of the library's correctness.
- [x] 12.9 Update the v8 assurance manifest and release notes to distinguish proved semantic/work bounds from measured heap, CPU, GC, backend-latency, and wall-clock properties.
- [x] 12.10 Remove temporary differential switches and superseded full-denotation/single-flight/DataScript-registry paths only after every replacement gate passes.
- [ ] 12.11 Assign stable claim identifiers and create a bidirectional ledger from every changed OpenSpec requirement/scenario through public operations, production branches, formal inputs/preconditions/theorems, generated/refinement artifacts, tests, and release-manifest digests.
- [ ] 12.12 Make each certification-relevant production target either execute the verified artifact or pass a mechanized source/control refinement to the modeled transition relation; classify differential-only and proof-only rows without overstating them.
- [ ] 12.13 Extend source-closure and manifest validation to fail on unmapped or drifted production branches, model fields, errors, limits, transitions, theorem preconditions, target artifacts, trusted adapters, or public assurance claims.
- [ ] 12.14 Add targeted negative controls that mutate one production branch, model transition, adapter conversion, generated artifact, and claim row and prove every mismatch closes the certification gate.
- [ ] 12.15 Produce a clean-checkout certification bundle with pinned toolchain/runtime/solver versions, reproducible commands, source/generated/evidence hashes, proof logs, residual assumptions, exclusions, and an independently verifiable bundle digest.
- [x] 12.16 Add the external-certifier review procedure and require independent sign-off evidence before the manifest can move from conditionally verified to an externally certified status.
- [x] 12.17 Align explicit completion's formal route, public-order, artifact-version, cache-key, admission, and immutable-hit contracts with production; add a controlled acyclic-order mutant and CLJ/CLJS schema-acyclic plus data-acyclic regressions.

## 13. Simplify the Architecture Without Regressing Performance

- [ ] 13.1 Record a source-derived pre-change inventory of semantic execution paths, answer-affecting branches, mutable coordinators, locks/semaphores, background tasks, cache artifact kinds, and generated/host semantic forks.
- [x] 13.2 Converge cache hit, cold miss, and bypass handling onto one normalized request/snapshot/evaluator pipeline with cache substitution only at typed command or completed-artifact boundaries.
- [x] 13.3 Delete superseded full-denotation defaults, projection widening, caller-waiting coordination, schema read locks, DataScript history retention, and temporary semantic switches; add negative source assertions preventing their return.
- [x] 13.4 Add checked deterministic non-regression baselines for executed commands, fetched/consumed values, generated transitions, allocation proxies, and retained logical units across the operation/shape corpus.
- [x] 13.5 Add pinned measured gates for latency, throughput, allocation, and retained memory with raw samples, environment/variance disclosure, absolute ceilings, baseline ratios, and a deterministic rerun protocol.
- [ ] 13.6 Bind the structural and performance evidence to the exact production artifact and source-closure digest in the implementation-conformance ledger and reproducible certification bundle.
- [x] 13.7 Reuse the canonical completed acyclic vector for binary-search point membership while preserving linear equality membership for recursive logical-order artifacts.

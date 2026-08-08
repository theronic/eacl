## 1. Characterize the Existing Cost and Correctness Boundaries

- [ ] 1.1 Convert the recursive point/count findings in the 2026-08-08 analysis report into deterministic operation-count fixtures for shallow, deep, negative, cyclic, diamond, mutual-recursion, and broad-union graphs.
- [ ] 1.2 Add cold `:cache? true` versus `:cache? false` trace tests that record evaluator direction, generated commands, adapter responses, fetched values, stopping reason, and resource-limit outcome.
- [ ] 1.3 Add adversarial cache-candidate tests proving stale, malformed, oversized, and proof-incomplete entries cannot cause extra backend commands before the ordinary demand trace.
- [ ] 1.4 Add characterization tests for projection-cache overfetch, full-denotation point/count misses, recursive first-page sorting, single-flight waiting, and cache-computation semaphore waiting.
- [ ] 1.5 Add deterministic concurrency fixtures that expose Datomic schema-lock convoys, late old-generation publication, stale DataScript/Datahike mutation heads, and relation-removal races.
- [ ] 1.6 Record checked-in cache bookkeeping and provider-latency baselines for point, bounded count, first page, and continued page workloads without treating wall-clock measurements as semantic proofs.

## 2. Normalize Public Execution and Deadline Contracts

- [ ] 2.1 Add a shared execution-contract normalizer for operation kind, demand shape, `:evaluation`, effective `:timeout-ms`, absolute monotonic deadline, traversal limits, and cache-attempt envelope.
- [ ] 2.2 Validate `:evaluation` as `:demand` or `:complete-denotation` and reject invalid values before consistency selection, cache access, or traversal.
- [ ] 2.3 Add a finite client execution-timeout default and positive per-request `:timeout-ms` override across public point, lookup, and count entry points in CLJ and CLJS.
- [ ] 2.4 Define finite client cache-stage time, evaluation-reserve, encoded-byte, decoded-weight, candidate-count, and atomic-attempt defaults with strict option validation and no per-request semantic-demand expansion.
- [ ] 2.5 Thread one absolute monotonic deadline and remaining budget through consistency selection, schema/plan work, cache/provider access, generated evaluation, rendering, token work, and publication.
- [ ] 2.6 Implement `:eacl.execution/deadline-exceeded` diagnostics with safe operation, stage, configured timeout, and bounded consumed-work fields; prohibit conversion to authorization values.
- [ ] 2.7 Add deterministic fake-monotonic-clock and bounded-blocking-adapter seams shared by CLJ and CLJS deadline tests.

## 3. Make the Generated Evaluator Own Demand and Logical Order

- [ ] 3.1 Extend the formal/generated request model with operation demand, evaluation mode, logical result ordinal, stopping reason, and deadline checks at quantum and adapter-command boundaries.
- [ ] 3.2 Implement target-anchored demand evaluation for positive and negative recursive point checks without constructing an unrelated subject-rooted denotation.
- [ ] 3.3 Implement bounded count stopping at graph exhaustion or `L+1` distinct ordered results and exact count exhaustion when no `:count-limit` is supplied.
- [ ] 3.4 Implement forward page stopping at graph exhaustion or `N+1` ordered distinct results beyond the authenticated boundary.
- [ ] 3.5 Define canonical rule/component order, endpoint order, queue discipline, duplicate handling, and a complete tie-breaker as one versioned logical ordering ABI.
- [ ] 3.6 Make logical order invariant under adapter chunk size, generated fuel, host wave batching, cache hit pattern, page size, map/set iteration, and runtime.
- [ ] 3.7 Emit exact validated adapter commands whose direction, bounds, inclusivity, maximum response size, and continuation are chosen only by the generated evaluator.
- [ ] 3.8 Regenerate and review CLJ/CLJS authorities and generated-boundary manifests without hand-maintained semantic forks.

## 4. Replace Cache-Directed Traversal with Exact Bounded Reuse

- [ ] 4.1 Move projection reuse to the generated adapter command/response boundary and key entries by the complete command, selected snapshot/source identity, schema proof, and adapter/engine ABI.
- [ ] 4.2 Remove cache-owned scan chunking, lazy projection widening, fetch-ahead, and post-stopping traversal from all backend adapters.
- [ ] 4.3 Introduce disjoint artifact types for completed Booleans, exact bounded responses, exact pages, exact command responses, private continuations, and completed denotations.
- [ ] 4.4 Require complete dependency framing and completion provenance before any artifact can answer a semantic request; keep partial SCC/worklist/prefix/timeout state non-answering.
- [ ] 4.5 Implement typed artifact indexes with trustworthy encoded-size and decoded-weight metadata that can be checked before retrieving an unbounded value.
- [ ] 4.6 Select the smallest sufficient compatible artifact and reject a provider read/decode that exceeds the operation's normalized cache-attempt envelope.
- [ ] 4.7 Make cache eligibility and proof lifting use zero cache-only backend commands; treat unavailable complete proof material as a miss.
- [ ] 4.8 Enforce the cache trace refinement law: every cache-enabled semantic trace is the corresponding cache-disabled trace with only matching commands removed, and a total miss has an equal trace.
- [ ] 4.9 Preserve naturally exhausted denotations and exact already-demanded command responses without performing additional completion or publication work after the stopping decision.

## 5. Remove Caller-Waiting Cache Coordination

- [ ] 5.1 Remove cache-owned blocking single-flight joins and the fair cache computation semaphore from point, lookup, and count authorization paths.
- [ ] 5.2 Let identical misses evaluate independently and implement bounded best-effort CAS publication where a compatible winner is retained and losing candidates are discarded.
- [ ] 5.3 Apply the cache-stage deadline and provider cancellation/abandonment contract so a slow lookup fails open while preserving request budget for selected-snapshot evaluation.
- [ ] 5.4 Make provider corruption, lookup failure, capacity rejection, eviction, and publication contention cache misses without swallowing consistency, cursor, or deadline errors.
- [ ] 5.5 Replace expiry, reset, restore, branch, and schema lifecycle mutation with atomic generation detachment that prevents late old-generation publication from reaching the new generation.
- [ ] 5.6 Ensure request cancellation/deadline completion owns all cache computation and leaves no background warming or detached traversal after the request stops.
- [ ] 5.7 Move overload control, if enabled, to a cache-neutral pre-selection admission boundary with identical policy for `:cache? true` and false.

## 6. Integrate Demand Semantics into Public Operations

- [ ] 6.1 Route cache-enabled and cache-disabled point checks through the same normalized demand evaluator and preserve identical Boolean/error/resource-limit behavior on misses.
- [ ] 6.2 Route `count-resources` and `count-subjects` through `L+1` bounded demand when `:count-limit` is present and exact exhaustion only when the caller requests an exact count.
- [ ] 6.3 Route first and continued recursive lookups through `N+1` demand while retaining at most the exact page response and private continuation authorized by that request.
- [ ] 6.4 Implement explicit `:complete-denotation` execution for point, count, and lookup operations without changing their public semantic value or consistency contract.
- [ ] 6.5 Reject recursive bare `:last N` in demand mode with a typed complete-evaluation-required error and allow it only under explicit complete-denotation evaluation.
- [ ] 6.6 Implement `:last N :before cursor` through deterministic prefix replay with an `N`-sized retained window, request deadline, and traversal limits.
- [ ] 6.7 Remove any prewarm, adaptive completion, repeated-demand promotion, or post-response traversal surface introduced by earlier v8 cache changes.

## 7. Version Recursive Cursors and Bounded Replay

- [ ] 7.1 Define a new authenticated cursor envelope binding source/branch/incarnation, graph/dependency proof, query/operation digest, schema/engine/adapter/identity/order ABI, direction, ordinal, boundary, evaluation mode, limits, expiry, and consistency constraints.
- [ ] 7.2 Mint page boundaries from external logical result identities rather than unstable internal EID discovery or a complete-denotation sort.
- [ ] 7.3 Resume retained private continuation state only for the identical snapshot, query, execution contract, and cursor position.
- [ ] 7.4 On continuation-cache miss, deterministically replay to the authenticated ordinal/boundary and demand only the next `N+1`, charging replay to the same deadline and traversal limits.
- [ ] 7.5 Permit proof-equivalent continuation on a newer current snapshot only when complete dependency and order proofs establish no duplicate, omission, or reorder.
- [ ] 7.6 Add verified exact-snapshot fallback for history-capable backends when it does not violate a newer freshness floor; otherwise return typed stale-cursor or consistency-conflict errors.
- [ ] 7.7 Reject old or mismatched ordering ABI cursors before traversal and publish an explicit v8 cursor migration boundary.

## 8. Derive All Read Semantics from One Immutable Snapshot

- [ ] 8.1 Refactor each public request to select one immutable database value before schema, plan, identity, dependency, cache-generation, traversal, rendering, and response-token work.
- [ ] 8.2 Key schema catalogs, recursive plans, dependency closures, and derived memos by source identity, selected-snapshot schema proof, and every answer-affecting engine/adapter ABI.
- [ ] 8.3 Replace the Datomic client `schema-state` read/write latch with bounded immutable generation entries and nonblocking atomic generation installation.
- [ ] 8.4 Remove every EACL-owned schema lock/monitor/semaphore acquisition from authorization and schema/relationship read paths while preserving backend-native snapshot/transaction behavior.
- [ ] 8.5 Prove with concurrent tests that a long `S0` read and an `S1` schema commit neither block each other in EACL nor mix semantic inputs.
- [ ] 8.6 Test out-of-band schema commits and missed/late invalidation callbacks to prove generation keys, not callback timing, determine correctness.

## 9. Make Cross-Backend Writers Optimistically Atomic

- [ ] 9.1 Capture schema generation, graph head, relation identities, and endpoint identities from the same `S0` used to calculate every schema or relationship transaction.
- [ ] 9.2 Preserve Datomic transactor-side schema CAS and relation-unused guards while removing any correctness dependency on client-local locking.
- [ ] 9.3 Change DataScript mutation construction to pass the original calculation head into the serialized transaction instead of rereading a later head during journal submission.
- [ ] 9.4 Change Datahike mutation construction to assert the original calculation head through backend-atomic transaction preconditions rather than adopting a newer head at submission.
- [ ] 9.5 Add relation-removal versus tuple-creation guards so no committed snapshot can contain a tuple whose required relation definition was removed.
- [ ] 9.6 Implement typed concurrent-write conflicts and idempotent retries that retain mutation identity, reselect a snapshot when allowed, and remain inside the request deadline.
- [ ] 9.7 Run deterministic interleavings for same-base schema replacements, delayed stale submissions, relation removal, endpoint changes, ambiguous outcomes, and cross-client writers.

## 10. Make DataScript Current-Basis-Only

- [ ] 10.1 Capture exactly one current `ds/db` per DataScript request and use it for schema, cache, traversal, cursor, rendering, and token work until completion.
- [ ] 10.2 Remove DataScript `:at-exact-snapshot` capability advertisement, exact locator/handle creation, DB-value registry retention, and `:exact-snapshot-registry-size` construction option.
- [ ] 10.3 Implement DataScript `:minimize-latency`, `:fully-consistent`, and deadline-bounded `:at-least-as-fresh` selection over current immutable DB values and authenticated mutation anchors.
- [ ] 10.4 Reject DataScript exact requests before cache access with the normalized unsupported-capability error and migration data.
- [ ] 10.5 Validate older cache candidates only after current snapshot selection, preserve separate `computed-at` and `validated-at` provenance, and miss on every relevant proof change.
- [ ] 10.6 Continue DataScript cursors only on proof-equivalent current DB values; reject relevant schema, relationship, identity, source, or order changes without silent restart.
- [ ] 10.7 Run one shared CLJ/CLJS corpus covering current selection, at-least floors, raw/managed writes, cache hits/misses, unsupported exact requests, and cursor mutation outcomes.

## 11. Expose Honest Provenance, Metrics, and Diagnostics

- [ ] 11.1 Add stable response provenance for selected evaluation mode, selected snapshot, cache basis, computed-at/validated-at basis, completion cause, and publication outcome.
- [ ] 11.2 Separate exact/proof-lifted hits, misses, bypasses, provider failures, publication admissions/rejections/races/detachment, and cache-envelope rejections in metrics.
- [ ] 11.3 Count evaluator direction, generated commands, cache-avoided commands, fetched values, replayed values, demand sentinels, natural/explicit completion, and writer conflicts independently.
- [ ] 11.4 Report deadline stage and bounded in-flight-command overrun without claiming cancellation of uninterruptible backend/runtime work.
- [ ] 11.5 Ensure a request that misses and loses a concurrent publication race remains a miss in telemetry and never inherits another request's latency or failure label.

## 12. Prove, Benchmark, Document, and Release the Contract

- [ ] 12.1 Prove generated point/count/page stopping soundness, incomplete-artifact non-answering, logical-order totality, and cache trace refinement for the supported recursive fragment.
- [ ] 12.2 Extend formal cache, consistency, temporal, pagination, and snapshot models with bounded cache attempts, lifecycle detachment, deadline boundaries, and optimistic writer schedules.
- [ ] 12.3 Run differential matrices across cache states, evaluation modes, limits, deadlines, chunk/fuel/batch/page permutations, graph shapes, mutation authority, and all supported backends.
- [ ] 12.4 Run 1k, 10k, 100k, and acceptance-gated larger broad-principal fixtures proving point, bounded count, and first-page work is independent of unrelated denotation width after the demand sentinel.
- [ ] 12.5 Add latency gates for cache bookkeeping/provider envelopes using checked-in fixed and ratio thresholds, while keeping semantic trace equality as the primary deterministic release gate.
- [ ] 12.6 Run CLJ, CLJS advanced, adapter certification, generated-boundary, Dafny, TLA+, mutation, reflection, cache differential, concurrency, and strict OpenSpec verification suites.
- [ ] 12.7 Update cache, consistency, cursor, timeout, DataScript, and v8 migration documentation with explicit defaults, error contracts, removed options, and complete-denotation examples.
- [ ] 12.8 Update `eacl-solidjs` and `eacl-explorer` examples to label exact unbounded demo counts and show bounded production count/page patterns without making consumer changes part of the library's correctness.
- [ ] 12.9 Update the v8 assurance manifest and release notes to distinguish proved semantic/work bounds from measured heap, CPU, GC, provider, backend-latency, and wall-clock properties.
- [ ] 12.10 Remove temporary differential switches and superseded full-denotation/single-flight/DataScript-registry paths only after every replacement gate passes.

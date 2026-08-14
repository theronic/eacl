## 1. Exploration and decision record (complete)

- [x] 1.1 Reproduce the global entity-ID merge first-page barrier and separate logical scans, Datahike node restores, and S3 operations.
- [x] 1.2 Audit Datahike, Konserve, tiered LMDB/S3, JDBC, DynamoDB-emulation, and Datomic-facing execution paths for immutable scans, retries, caching, and failure classification.
- [x] 1.3 Refute eager whole-chunk semantic admission with a chunk-width order counterexample.
- [x] 1.4 Refute the symmetric fixed-point/byte-stable candidate through state-complexity and allocation benchmarks.
- [x] 1.5 Establish the minimum direction-specific design, exact-admission key structure, one-value normalization, and result-edge cursor contract.
- [x] 1.6 Run the abstract assurance suite (43 Dafny leaves/536 obligations, five TLA+ families, 19 killed mutants, 18,000 randomized checks, 22 executable controls, six source bridges).
- [x] 1.7 Benchmark the minimum forward and reverse prototypes against legacy and rejected engines.
- [x] 1.8 Record the accepted architecture, assurance coverage, loophole audit, alternatives, backend evidence, and cleanup scope.
- [x] 1.9 Revise this change: one generic reducer, uniqueness by construction, width-one-only physical execution, two cache artifacts, three-outcome adapter results, consistency-aware continuation, scalar checkpoint ordinals with the lookahead segment, and re-sequenced delivery. (This document set.)

## 2. Preserve evidence and freeze baselines

- [x] 2.1 Archive the complete `target/exploration/stable-discovery/` tree (accepted prototype, Dafny/TLA models, benchmark protocol, audits) into tracked storage. Archived at `exploration/stable-discovery/`; regenerable run state and dependency caches are excluded and documented in its `ARCHIVE.md`.
- [ ] 2.2 Capture reproducible public-API denotation fixtures for direct, union-overlap, deep arrow, cyclic, recursive chain/star/mixed, broad union, and reverse lookup schemas against the current engines.
- [ ] 2.3 Capture cancellation, timeout, exact-basis, cursor fork/idempotence, point-check, and exact-count baselines without treating legacy page order as authoritative.
- [ ] 2.4 Capture DataScript CLJ/CLJS latency and allocation baselines with completed-answer caching disabled, and controlled MinIO/JDBC/DynamoDB-local operation baselines.
- [ ] 2.5 Make every benchmark command, fixture seed, JVM/JS setting, and warm/cold condition reproducible from the repository.

## 3. Promote retained assurance

- [ ] 3.1 Move only retained-scope models into the tracked release-assurance tree: grounding/denotation, sealed plan and rank certificate, generic reducer (soundness, completeness, termination, exact uniqueness), one-value normalization, pagination/edge/checkpoint composition (including the lookahead segment), atomic admission and attempt outcomes, cancellation, and the representation leaves (`RuntimeStackRefinement`, `ConcreteHistoryFreeRuntime`, `OwnedTransientSnapshot`).
- [ ] 3.2 Park the concurrency models (read-ahead, coalescing, response leases, service lifecycle) with the future concurrency change; delete or quarantine symmetric-join, emitted-set, rolling-commitment, byte-order, cross-request-flight, and async-publication models.
- [ ] 3.3 Bind formal codecs, ordinals, rank certificates, logical identities, root projection, and scan contracts to normalized production fixtures in CLJ and CLJS.
- [ ] 3.4 Add mutation controls: logical-release-width not one, dynamic ordering input, eager chunk admission, integrate-on-completion, incomplete failure integration, stale-basis continuation, checkpoint regression, missing lookahead segment, entity-only interior admission key, producing-edge root key.
- [ ] 3.5 Keep the fast proof gate under the agreed local budget; bounded exhaustive expansions run in a separate release/nightly gate.

## 4. Implement sealed planning

- [ ] 4.1 Define the versioned sealed-plan record, transition-descriptor interface, per-kind admission-key codecs, and the single composite fingerprint (plan + order contract + transition interface + admission-key granularity + adapter scan-order contract).
- [ ] 4.2 Compile forward consumers indexed by granted node and reverse rules indexed by head node; validate unique dense ordinals.
- [ ] 4.3 Compute and check the static 0/1 shortest-remaining-storage-read rank certificate; sort every alternative vector by `(rank, ordinal)` with no host-map or printed-form order.
- [ ] 4.4 Emit the single root emission point keyed by emitted-entity identity in both directions.
- [ ] 4.5 Differentially verify sealed-plan denotations against the retained engines across normalized schemas, CLJ and CLJS.

## 5. Implement the generic reducer

- [ ] 5.1 Port the accepted prototype (`forward_runtime_prototype.clj`) to one production CLJC reducer over the sealed-plan transition interface, with logical release hard-fixed at one value. The in-tree `portable_indexed.cljc` `:stable-discovery` mode is the rejected candidate and is not a porting source.
- [ ] 5.2 Implement specialized per-kind admission keys, right-edge stack, request-owned transients with freeze/fork discipline, compact scan frames, scalar counts, and checked limits.
- [ ] 5.3 Implement pure stepping with exact read-demand suspension and atomic staged integration; no physical state in reducer values.
- [ ] 5.4 Keep the cheap structural invariants always-on and fail-closed at the engine boundary; expensive per-value adapter guards stay opt-in.
- [ ] 5.5 Prove/test soundness, completeness on exhaustion, termination, exact uniqueness, stable sequence, chunk invariance, and stack bounds via the CLJ/CLJS bridges and mutation controls.

## 6. Implement pagination and continuation

- [ ] 6.1 Implement bounded HMAC result-edge cursors binding basis, lifecycle, query/principal, composite fingerprint, fixed page size, ordinal, external identity, expiry, and the format, order-ABI, and key versions.
- [ ] 6.2 Implement `after` and `before` navigation with page plus one private lookahead.
- [ ] 6.3 Implement latest-only checkpoints containing history-free reducer state plus the undelivered lookahead/boundary segment, with distinct delivered/discovered counts, keyed by execution identity including the composite fingerprint.
- [ ] 6.4 Reshape the continuation store: scalar-ordinal nonregressing replacement, synchronous `O(1)` publication, entry-count and byte-weight caps exposed as client options, replay admission ledger rehomed to the service edge.
- [ ] 6.5 Implement governed deterministic replay with exact boundary validation, the typed resource-exhaustion failure for unreachable pages, and the cap/budget coherence check.
- [ ] 6.6 Implement consistency-aware continuation: cursor-consistency-conflict on unsatisfiable freshness, certified full-read-scope dependency-proof continuation for current-only topologies, explicit stale rejection otherwise.
- [ ] 6.7 Prove/test page composition, no duplicates, repeated/concurrent cursor idempotence, eviction/replay equivalence, lookahead survival across checkpoints, and rejection of page-size, plan, ABI, basis, or identity mismatch.

## 7. Implement the width-one physical layer

- [ ] 7.1 Define the logical-occurrence, equality-complete physical-descriptor, and transport-attempt identity types, and implement the three-outcome adapter result classification with cause codes and partial-discard accounting at the reduce-scan seam; typed failures replace raw exception leakage.
- [ ] 7.2 Repair `select-exact` classification per backend: Datomic narrows its catch (nothing in its body legitimately throws for trimmed history); Datahike maps genuine absence (GC'd commit, foreign locator format) to unavailable and propagates every other Throwable as a classified retryable/terminal failure.
- [ ] 7.3 Implement retry semantics: same exact descriptor and basis, original absolute deadline, partial output discarded, attempts counted separately from logical occurrences, nested driver/SDK retry inventory per topology.
- [ ] 7.4 Implement bounded per-request chunk retention with eviction-to-logical-bound and the demand-`P` policy.
- [ ] 7.5 Implement the service-edge admission component: bounded concurrent enumerations, slot hold until physical return of cancelled synchronous reads, replay ledger, per-request budgets.
- [ ] 7.6 Complete cooperative cancellation checks through reducer stepping, backend issue/integration, rendering, page/cursor publication, and checkpoint publication.
- [ ] 7.7 Define the closed SPI capability record (immutable basis, scan order/uniqueness, replayability, atomic response, failure-classification fidelity, cancellation and termination behavior, nested retry exposure; semantic concurrent-read safety separate from deployment width) with conservative defaults, per-adapter declarations, and identity-mapping certification.
- [ ] 7.8 Implement per-layer cost telemetry: reducer transitions, logical scan occurrences, adapter attempts, values fetched/consumed, node-cache hits/misses, driver attempts, and remote operations where observable.

## 8. Entry-point routes and local gates

- [ ] 8.1 Implement the goal-anchored point-check route (early termination; no root-universe enumeration) on the new engine.
- [ ] 8.2 Implement exact count by reducer exhaustion for both directions; retain the order-insensitive specialization allowance behind an independent denotation-equivalence proof.
- [ ] 8.3 Decide and implement the fate of the public `:complete-denotation` evaluation mode (replace, degrade, or remove with a typed error); document that `expand-permission-tree` is engine-independent.
- [ ] 8.4 Add the composite fingerprint to the completed-answer cache key; exclude cursor-bearing answers from cross-basis reuse on topologies that cannot reselect the embedded basis.
- [ ] 8.5 Pass the binding local gates through the public API on CLJ and CLJS: denotation equality with the frozen oracles, order stability, pagination/replay suites, cancellation, allocation ≤ 1.5x legacy, median latency within 0.25 ms or 25% of legacy, no linear scaling with unvisited branches.
- [ ] 8.6 Pass local adapter contract gates with fault injection on MinIO, JDBC, and DynamoDB-local: failure classification (scoped to the EACL-side seam on DynamoDB-local — the upstream Konserve nil-collapse keeps Datahike/DynamoDB unqualified until task 10.3), missing-node, latency/jitter, cancellation under latency, and the tiered LMDB/S3 branch-head freshness and writer-synchronization correctness checks.

## 9. Route and delete

- [ ] 9.1 Route lookup-resources, lookup-subjects, can?, count-resources, and count-subjects through the new engine on all backends at width one; rerun the full local gate.
- [ ] 9.2 Delete the acyclic merge route and `lazy_merge_sort`, the symmetric/byte-stable candidate (dual-mode branches, prefix commitments, byte-order contract, join buckets, dual limit ABIs, separate emitted sets), the physical scheduler entirely (including cross-request shared-read machinery), the service governor (its replay ledger rehomed per 6.4/7.5), the projection and denotation cache tiers, obsolete cursor branches and re-minting, and obsolete formal models — gated on the frozen baselines (2.x) and the evidence archive (2.1).
- [ ] 9.3 Remove the generated-kernel runtime authority and host-side recomputation for deleted operations; surviving finite decision tables get exhaustive whole-domain test bridges.
- [ ] 9.4 Publish the order ABI, cursor trust boundary, failure semantics, and cache metrics documentation.

## 10. Follow-on remote performance qualification

- [ ] 10.1 Qualify Datahike direct S3 and tiered LMDB/S3 (restart warmth, disk lifecycle, exact-basis behavior at scale) with reproducible fixtures; publish performance envelopes including evicted-checkpoint replay p95/p99.
- [ ] 10.2 Qualify Datahike/JDBC against real pool behavior and database latency.
- [ ] 10.3 Repair or wrap Konserve DynamoDB failure classification; verify strongly consistent read/publication on real DynamoDB; only then enable the topology.
- [ ] 10.4 Qualify Datomic Pro/DynamoDB basis retention, cancellation, and cost on an approved topology.
- [ ] 10.5 Measure million-identity admission/checkpoint retained heap and concurrent-request capacity in production source.
- [ ] 10.6 Publish topology-specific deployment guidance (storage tiering, cache sizing, prewarm) and the operational qualification report; open the separate concurrency change only if these measurements justify it.
- [ ] 10.7 Archive this change after implementation, cleanup, and release evidence are complete; update `HANDOVER.md` if ownership changes before then.

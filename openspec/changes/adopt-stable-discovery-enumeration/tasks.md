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
- [x] 2.2 Capture reproducible public-API denotation fixtures for direct, union-overlap, deep arrow, cyclic, recursive chain/star/mixed, broad union, and reverse lookup schemas against the current engines. Seven fixtures under `exploration/baselines/`, captured by `eacl.baseline.capture` and verified by `eacl.baseline.baseline-test`.
- [x] 2.3 Capture cancellation, timeout, exact-basis, cursor fork/idempotence, point-check, and exact-count baselines without treating legacy page order as authoritative. Captured per fixture (`:cursor-behavior`, `:stale-basis`, `:points`, counts); deadline expiry itself is timing-dependent and deliberately excluded from frozen snapshots (typed contract validation and cancellation are frozen).
- [ ] 2.4 Capture DataScript CLJ/CLJS latency and allocation baselines with completed-answer caching disabled, and controlled MinIO/JDBC/DynamoDB-local operation baselines. CLJ captured (`eacl.baseline.perf`, warm-repeat medians + authoritative first-run scan counts); CLJS and containerized remote-op baselines remain (see `exploration/baselines/README.md` open items).
- [x] 2.5 Make every benchmark command, fixture seed, JVM/JS setting, and warm/cold condition reproducible from the repository. `exploration/baselines/README.md` documents commands, determinism, environment stamping, and warm/cold definitions.

## 3. Promote retained assurance

- [x] 3.1 Move only retained-scope models into the tracked release-assurance tree: grounding/denotation, sealed plan and rank certificate, generic reducer (soundness, completeness, termination, exact uniqueness), one-value normalization, pagination/edge/checkpoint composition (including the lookahead segment), atomic admission and attempt outcomes, cancellation, and the representation leaves (`RuntimeStackRefinement`, `ConcreteHistoryFreeRuntime`, `OwnedTransientSnapshot`). Promoted to `formal/stable-discovery/` (41 leaves, 506 obligations, 2 TLC families, 5 bridges + randomized campaign); gate green at ~7 s.
- [x] 3.2 Park the concurrency models (read-ahead, coalescing, response leases, service lifecycle) with the future concurrency change; delete or quarantine symmetric-join, emitted-set, rolling-commitment, byte-order, cross-request-flight, and async-publication models. Parked set stays archive-only; the rejected candidate's untracked `formal/dafny/StableDiscovery.dfy` and `formal/tla/Eacl*` models are quarantined (excluded from release assurance, recorded in `formal/stable-discovery/README.md`) pending physical deletion at 9.2.
- [ ] 3.3 Bind formal codecs, ordinals, rank certificates, logical identities, root projection, and scan contracts to normalized production fixtures in CLJ and CLJS. CLJ done in `eacl.engine.stable-discovery-gate-test`: clause-order-invariant semantic rules/ordinals/ranks, certified ranks equal an independent Bellman-Ford oracle, and the linear checker kills mutated certificates. CLJS parity pending.
- [x] 3.4 Add mutation controls: logical-release-width not one, dynamic ordering input, eager chunk admission, integrate-on-completion, incomplete failure integration, stale-basis continuation, checkpoint regression, missing lookahead segment, entity-only interior admission key, producing-edge root key. All in-scope controls landed and killed across the gate suites: eager whole-chunk release, host push/bucket order, fetched-end resume, entity-only interior key, per-rule root key, stale-basis rejection, checkpoint nonregression, missing lookahead segment, and incomplete failure integration (atomic partial discard). Integrate-on-completion is parked with the concurrency change by design.
- [x] 3.5 Keep the fast proof gate under the agreed local budget; bounded exhaustive expansions run in a separate release/nightly gate. `verify-fast.sh` runs at ~7 s against the 10 s ceiling; exhaustive campaigns stay in the archive tier and the nightly gate extends with production gates at 5.5/6.7.

## 4. Implement sealed planning

- [x] 4.1 Define the versioned sealed-plan record, transition-descriptor interface, per-kind admission-key codecs, and the single composite fingerprint (plan + order contract + transition interface + admission-key granularity + adapter scan-order contract). `eacl.engine.sealed-plan`: versioned plan record, sealed work-kind vocabulary with per-kind admission keys, order contract (including logical-release-width 1) folded into the `canonical-records-digest` fingerprint. Adapter scan-contract digest joins at cursor-binding time (task 6.1).
- [x] 4.2 Compile forward consumers indexed by granted node and reverse rules indexed by head node; validate unique dense ordinals. Rules compile fail-closed directly from adapter definition ops (independent of the legacy compiler); indexes `:forward-seeds`/`:forward-consumers`/`:reverse-rules`.
- [x] 4.3 Compute and check the static 0/1 shortest-remaining-storage-read rank certificate; sort every alternative vector by `(rank, ordinal)` with no host-map or printed-form order. Untrusted fixpoint generator + linear trusted checker (`valid-certificate?`, fail-closed).
- [x] 4.4 Emit the single root emission point keyed by emitted-entity identity in both directions. Forward: first admission of `[:grant root-node eid]`; reverse: first admission of `[:reverse-subject type eid]`; alias-cycle counterexample test guards node-qualified interior keys.
- [ ] 4.5 Differentially verify sealed-plan denotations against the retained engines across normalized schemas, CLJ and CLJS. CLJ done: `eacl.engine.stable-reducer-test` matches the frozen current-engine baselines on all seven fixtures, both directions (99 assertions). CLJS parity pending.

## 5. Implement the generic reducer

- [x] 5.1 Port the accepted prototype (`forward_runtime_prototype.clj`) to one production CLJC reducer over the sealed-plan transition interface, with logical release hard-fixed at one value. The in-tree `portable_indexed.cljc` `:stable-discovery` mode is the rejected candidate and is not a porting source. `eacl.engine.stable-reducer`: one unified step over all work kinds, logical release fixed at one, bounded evictable buffers, physical-width/retention invariance verified.
- [x] 5.2 Implement specialized per-kind admission keys, right-edge stack, request-owned transients with freeze/fork discipline, compact scan frames, scalar counts, and checked limits. `AdmissionKey` deftype with cached hash (CLJS: vectors); transient admitted/results owned linearly by the loop, frozen in `finish`; typed `:eacl.reducer/limit-exceeded` for admissions/commands/transitions checked before their transition commits.
- [x] 5.3 Implement pure stepping with exact read-demand suspension and atomic staged integration; no physical state in reducer values. Scans are equality-complete read-demand descriptors realized through one injectable `fetch-fn` seam (`adapter-fetch-fn` is the direct width-one path); semantic state is untouched until the complete chunk realizes; staged admission commits whole transitions or nothing.
- [x] 5.4 Keep the cheap structural invariants always-on and fail-closed at the engine boundary; expensive per-value adapter guards stay opt-in. `finish` enforces discovered-count/duplicate-free invariants unconditionally; per-value scan-contract guards remain the adapter's opt-in `:runtime-guards?`.
- [ ] 5.5 Prove/test soundness, completeness on exhaustion, termination, exact uniqueness, stable sequence, chunk invariance, and stack bounds via the CLJ/CLJS bridges and mutation controls. CLJ done: independent naive-fixpoint oracle equality on all eight fixtures both directions (soundness + completeness on exhaustion + termination through cycles), frozen-baseline differentials, width/retention invariance, always-on uniqueness invariants, six killed mutants. CLJS parity pending.

## 6. Implement pagination and continuation

- [x] 6.1 Implement bounded HMAC result-edge cursors binding basis, lifecycle, query/principal, composite fingerprint, fixed page size, ordinal, external identity, expiry, and the format, order-ABI, and key versions. `eacl.engine.stable-page` edge tokens (domain-separated HMAC, canonical payload, constant-time compare); key-ring/rotation integration joins the public splice at 9.1.
- [x] 6.2 Implement `after` and `before` navigation with page plus one private lookahead. Both modes slice one canonical sequence; `before` clamps at the sequence start and validates the supplied edge as its lookahead.
- [x] 6.3 Implement latest-only checkpoints containing history-free reducer state plus the undelivered lookahead/boundary segment, with distinct delivered/discovered counts, keyed by execution identity including the composite fingerprint. `reducer/history-free` + `reducer/resume` with `:base-discovered`; checkpoints carry `{:state :pending :boundary :ordinal}`.
- [x] 6.4 Reshape the continuation store: scalar-ordinal nonregressing replacement, synchronous `O(1)` publication, entry-count and byte-weight caps exposed as client options, replay admission ledger rehomed to the service edge. `make-checkpoint-store` (`:max-entries`/`:max-entry-admissions`, overweight drop, strictly-greater-transitions replacement); the replay ledger lands with the 7.5 admission component.
- [x] 6.5 Implement governed deterministic replay with exact boundary validation, the typed resource-exhaustion failure for unreachable pages, and the cap/budget coherence check. Replay validates ordinal + boundary identity before any page publishes; reducer budget failures surface as `:eacl.page/resource-exhausted`, distinct from stale-cursor.
- [x] 6.6 Implement consistency-aware continuation: cursor-consistency-conflict on unsatisfiable freshness, certified full-read-scope dependency-proof continuation for current-only topologies, explicit stale rejection otherwise. Basis mismatch rejects `:eacl.page/stale-cursor`, or `:eacl.page/cursor-consistency-conflict` under `:fully-consistent`/`:at-least-as-fresh`; the dependency-proof continuation path remains conservative explicit rejection (spec-compliant) until certified.
- [x] 6.7 Prove/test page composition, no duplicates, repeated/concurrent cursor idempotence, eviction/replay equivalence, lookahead survival across checkpoints, and rejection of page-size, plan, ABI, basis, or identity mismatch. `eacl.engine.stable-page-test`: 56 assertions incl. both directions and four page sizes.

## 7. Implement the width-one physical layer

- [x] 7.1 Define the logical-occurrence, equality-complete physical-descriptor, and transport-attempt identity types, and implement the three-outcome adapter result classification with cause codes and partial-discard accounting at the reduce-scan seam; typed failures replace raw exception leakage. Read-demand descriptors + `eacl.engine.physical/classified-fetch-fn` (complete | classified failure with cause | cancelled; realization inside the boundary discards partials atomically).
- [x] 7.2 Repair `select-exact` classification per backend: Datomic narrows its catch (nothing in its body legitimately throws for trimmed history); Datahike maps genuine absence (GC'd commit, foreign locator format) to unavailable and propagates every other Throwable as a classified retryable/terminal failure. Both backends now classify (`:eacl.basis/selection-failure` with `:retryable`/`:cancelled`); interrupts no longer surface as expired snapshots.
- [x] 7.3 Implement retry semantics: same exact descriptor and basis, original absolute deadline, partial output discarded, attempts counted separately from logical occurrences, nested driver/SDK retry inventory per topology. `retrying-fetch-fn`: retryable-only, exact descriptor, absolute deadline, attempt counter; nested-retry inventory is a per-topology qualification datum (capability record `:nested-retry-exposure`).
- [x] 7.4 Implement bounded per-request chunk retention with eviction-to-logical-bound and the demand-`P` policy. In the reducer since 5.1 (capped newest-retained buffers, eviction re-reads from the residual bound); physical demand is exactly the chunk limit and semantic lookahead is the reducer's one-result target.
- [x] 7.5 Implement the service-edge admission component: bounded concurrent enumerations, slot hold until physical return of cancelled synchronous reads, replay ledger, per-request budgets. `make-service-admission`/`with-admission`/`with-replay-admission`; at width one the slot is structurally held until the synchronous call chain returns.
- [x] 7.6 Complete cooperative cancellation checks through reducer stepping, backend issue/integration, rendering, page/cursor publication, and checkpoint publication. `execution-cut-point` wires `execution/check!` at every reducer transition (covering issue/integration at width one); the cancellation gate proves no page or checkpoint publishes after the signal and the parent cursor stays reusable.
- [x] 7.7 Define the closed SPI capability record (immutable basis, scan order/uniqueness, replayability, atomic response, failure-classification fidelity, cancellation and termination behavior, nested retry exposure; semantic concurrent-read safety separate from deployment width) with conservative defaults, per-adapter declarations, and identity-mapping certification. `topology-capabilities` (closed keys, conservative defaults, width pinned to one) + `stable-discovery-qualified?`; per-adapter declarations join the 9.1 splice.
- [x] 7.8 Implement per-layer cost telemetry: reducer transitions, logical scan occurrences, adapter attempts, values fetched/consumed, node-cache hits/misses, driver attempts, and remote operations where observable. `physical/telemetry` separates reducer/logical/fetch/attempt layers; node-cache and remote-operation counters remain storage-layer observations, never inferred.

## 8. Entry-point routes and local gates

- [x] 8.1 Implement the goal-anchored point-check route (early termination; no root-universe enumeration) on the new engine. `eacl.engine.stable-route/check`: reverse traversal anchored at the known resource with first-admission early exit; verified against every frozen point sample.
- [x] 8.2 Implement exact count by reducer exhaustion for both directions; retain the order-insensitive specialization allowance behind an independent denotation-equivalence proof. `stable-route/count-resources`/`count-subjects` with explicit `:count-limit` truncation, verified against frozen counts.
- [ ] 8.3 Decide and implement the fate of the public `:complete-denotation` evaluation mode (replace, degrade, or remove with a typed error); document that `expand-permission-tree` is engine-independent.
- [ ] 8.4 Add the composite fingerprint to the completed-answer cache key; exclude cursor-bearing answers from cross-basis reuse on topologies that cannot reselect the embedded basis.
- [ ] 8.5 Pass the binding local gates through the public API on CLJ and CLJS: denotation equality with the frozen oracles, order stability, pagination/replay suites, cancellation, allocation ≤ 1.5x legacy, median latency within 0.25 ms or 25% of legacy, no linear scaling with unvisited branches. CLJ engine-level gates pass (`local-perf-gate-test`: median within legacy warm + 0.25 ms, allocation within envelope, no linear branch scaling; full battery 353 assertions); the public-API and CLJS halves depend on the 9.1 splice.
- [ ] 8.6 Pass local adapter contract gates with fault injection on MinIO, JDBC, and DynamoDB-local: failure classification (scoped to the EACL-side seam on DynamoDB-local — the upstream Konserve nil-collapse keeps Datahike/DynamoDB unqualified until task 10.3), missing-node, latency/jitter, cancellation under latency, and the tiered LMDB/S3 branch-head freshness and writer-synchronization correctness checks. In-process fault injection at the EACL seam passes (mid-stream failure, retry, cancellation); containerized MinIO/JDBC/DynamoDB-local runs remain.

## 9. Route and delete

- [x] 9.1 Route lookup-resources, lookup-subjects, can?, count-resources, and count-subjects through the new engine on all backends at width one; rerun the full local gate. All five entry points on stable discovery via `:stable-edge` cursors through the authenticated relay envelope; public limits and error keys preserved; ~6,000 assertions green across Datomic/DataScript/Datahike/relay/baseline/stable suites plus the formal gate. The cross-engine differential caught and fixed a plan-cache key collision during the flip.
- [ ] 9.2 Delete the acyclic merge route and `lazy_merge_sort`, the symmetric/byte-stable candidate (dual-mode branches, prefix commitments, byte-order contract, join buckets, dual limit ABIs, separate emitted sets), the physical scheduler entirely (including cross-request shared-read machinery), the service governor (its replay ledger rehomed per 6.4/7.5), the projection and denotation cache tiers, obsolete cursor branches and re-minting, and obsolete formal models — gated on the frozen baselines (2.x) and the evidence archive (2.1). The rejected candidate (uncommitted experiment: scheduler, governor, dual-mode branches, prefix commitments, its formal models) and the old routing test suites are deleted; the now-unreachable acyclic-merge and generated-kernel internals inside engine/v8.cljc plus `lazy_merge_sort` remain as dead code pending mechanical excision.
- [ ] 9.3 Remove the generated-kernel runtime authority and host-side recomputation for deleted operations; surviving finite decision tables get exhaustive whole-domain test bridges. Partially done by replacement: the stable routes bypass the kernel's page/count/route decisions; `normalize-page-request` still uses the kernel `:relationship-page` decision and the dead machinery still loads (see 9.2).
- [x] 9.4 Publish the order ABI, cursor trust boundary, failure semantics, and cache metrics documentation. `docs/stable-discovery-engine.md` (consumer/operator summary over the normative specs).

### Release engineering status (2026-08-14)

- `v8.0.0-SNAPSHOT` pushed at the routed engine (`0effa0c`); the Clojars
  release workflow gates on exact-SHA Tests + Formal verification (first
  attempt failed on the isolated-module test layout and stale
  counterexample evidence, both fixed; second run in flight). On green,
  Clojars publishes `dev.eacl/* 8.0.0-SNAPSHOT` automatically.
- [PR #116](https://github.com/theronic/eacl/pull/116) open against
  `release/v8.0` (PR #115 is the pre-existing cooperative-cancellation PR
  on the same base).
- `eacl-datomic-solidjs` upgraded (pre-release `:coherence-authority`
  option dropped, commit `bcf67b2` there) and verified running against the
  local stable engine (`:local-eacl` alias, server on 8088, client on
  5273) for operator testing.
- `eacl-datahike-demo` already pins `8.0.0-SNAPSHOT`; deploy is blocked on
  (a) the Clojars publish landing and (b) the operator-local
  `infra/deployment.env` (instance host + SSH key are not in the repo).
  Then: clear `~/.m2/repository/dev/eacl`, build the server uberjar, run
  `infra/scripts/deploy-artifact.sh` — no S3 reseed (engine-only change).

### Cache-reuse and limit-semantics repair (2026-08-14, operator-reported)

- Repeated pagination replayed every page: the engine rejected the
  client's continuation context (no checkpoints ever), the plan cache
  keyed on JVM object identity (missed every fresh Datomic snapshot),
  and the Datomic client's page validator did not recognize
  `:stable-edge` cursors (the whole answer-cache layer silently never
  remembered stable pages). All three fixed (`41be8dd`); the plan cache
  now keys on the adapter source-identity contract, which distinct
  stores must honor with distinct lifecycles (fixtures fixed).
- Execution enforcement was inert on the routed engine: `:timeout-ms`
  and cancellation tokens now reach the reducer through
  `stable-cut-point`; the bare-`:last` complete-evaluation guard is
  restored, scoped to recursive plans (sealed plans carry `:recursive?`).
- Public limits were mapped onto the wrong reducer budgets (`52ff683`):
  `:max-queued-work` now bounds instantaneous stack depth (new
  `:max-stack`), `:max-advanced-datoms` bounds consumed values (new
  `:max-values`); internal transition/command ceilings scale with the
  authorized work. A 24k-result count under default limits and a
  million-result count under raised limits both complete.
- The reducer reports per-run `:derived-grants`/`:advanced-datoms`/
  `:queued-work` deltas plus `:continuation-hits` into the public stats
  hook; the aggregate-job test suites (which the isolated-module pattern
  does not cover) are re-enveloped to stable accounting, and the retired
  op-count suite's cancellation/deadline contracts are ported to
  `physical-route-test`. Verify locally with
  `clojure -X:dev:test :excludes '[:benchmark :formal-artifact]'`
  (622+ tests, ~26k assertions, green).
- Datomic peer aligned to the 1.0.7705 transactor. `eacl-datomic-solidjs`
  now runs against `datomic:dev://localhost:4334/eacl-solidjs` (the
  million-server test database; `install-demo!` is marker-guarded, no
  reseed; `EACL_SOLIDJS_SECURITY_KEY` required): million-server exhaustive
  count 23 s cold / 2.4 ms cached, first pages 14 ms, cursor pages flat
  6–8 ms, repeats 2–3 ms cached. Remaining UI nit: the demo client reuses
  stale count responses when switching subjects (demo repo, not engine).
- `^:benchmark` suites still assert old-engine counters; rebase or retire
  them with 9.2 (they are CI-excluded).

### Dead-path excision (2026-08-14, task 9.2 executed)

- `v8.cljc` 4,956 -> 1,354 lines across two waves (kondo unused-private
  fixpoint + zero-reference public sweep + the mutually recursive acyclic
  cluster + the analysis router). Retained: the permission-path
  derivation (feeds the relationship dependency sets that drive
  answer-cache invalidation) and the live schema-cache generations.
- `:relation-populated?` left the adapter contract (all three backends,
  certification, dispatch evidence at 57 sites / 19 ops).
- `eacl.lazy-merge-sort` moved to test scope; dead-counter benchmark
  deftests retired from `pagination_test`/`subproblem_cache_test`; the
  verified-authority cutover suite now pins the four LIVE generated
  decisions (:consistency-plan :current-cache-decision
  :cursor-continuation :relationship-page), and the Datomic client's
  cursor-continuation decision routes through the client kernel (the
  request-time global was the one bypass seam).
- REMAINING (next commit after the release lands): the retired Dafny
  leaves (`AcyclicEngine`, `Indexed*` x10, `OrderedMerge`, `CursorCost`)
  plus their generated indexed runtime, `IndexedTraversalKernel`
  protocol + implementations (`production_kernel` indexed half,
  `portable_indexed.cljc`), indexed bridge-test sections, and
  `manifest.edn` theorem-count re-pins.

### Local MinIO S3-read qualification (2026-08-14, 100k servers)

Measured on the datahike demo against loopback MinIO (fresh store,
100,048 servers, node cache 8,192, GETs counted at MinIO's own metrics
endpoint; the endpoint caches ~15 s, so scrape with settle delays):

- Process boot: 8 GETs. Cold first page over the full 100k denotation:
  **5 GETs / 126 ms** (recorded old engine: 3,935 GETs / 148.4 s at 1M —
  the old first page realized every stream head).
- Cursor pages 2–50: ~0 incremental GETs, ~60 ms each.
- Exhaustive count of 100,048: **3,062 GETs / 8.6 s** cold (~33 tuples
  per node read — the full-index walk only an exhaustive count pays);
  repeat 0 GETs / 16 ms (answer-cache hit).
- Evicted-checkpoint replay (cold process, continuation at ordinal 200
  with a stable `SECURITY_KEY`): **13 GETs / 178 ms**.
- The demo's boot-time cache prewarm was REMOVED (it existed to mask the
  old engine's cold page and would distort remote measurements); demo
  client opts into raised traversal limits for platform-wide counts.
- Rig gotchas are recorded in the demo's `docs/local-minio-runbook.md`
  (MinIO metrics staleness; `-Dhttp.keepAlive=false` for sustained
  seeding until Datahike's writer stops dying on one transient reset —
  the writer-shutdown-on-first-IOException fragility is the write-path
  sibling of the read-side failure-cause collapse in task 10.3).

### Known issues (2026-08-14, end of session)

- **Datahike writer dies permanently on one transient S3 fault**: a
  single keep-alive connection reset (`Unexpected end of file`) shuts
  the writer down instead of retrying; only an application restart
  recovers (the durable store resumes cleanly). Workaround for
  sustained writes: `-Dhttp.keepAlive=false`. This is the write-path
  sibling of the 10.3 failure-classification repair and should be fixed
  at the storage layer, not in EACL.
- **Retired formal corpus still verified on CI**: `AcyclicEngine.dfy`,
  the ten `Indexed*.dfy` leaves, `OrderedMerge.dfy`, and `CursorCost.dfy`
  prove the retired engines; their generated indexed runtime,
  `IndexedTraversalKernel` protocol + implementations
  (`production_kernel` indexed half, `portable_indexed.cljc`), the
  indexed bridge-test sections, and `manifest.edn` theorem pins all go
  together as one scoped commit. `bin/formal` auto-enumerates
  `formal/dafny/*.dfy`, so file deletion shrinks the verify set.
- **Remaining `^:benchmark` suites** still reference retired machinery
  (loadable, CI-excluded; a `record-generated-stats!` `ns-resolve` in
  `bench/pagination_test` is nil-safe but dead). Rebase or retire with
  the formal cut.
- **Checkpoints pin the exact basis**: proof-equivalent basis churn
  (unrelated writes) misses the checkpoint and pays governed replay —
  correct but costlier than necessary.
- Production S3 has no request-metrics filter enabled, so deployed GET
  counts cannot be read from CloudWatch; the local MinIO rig (metrics
  cached ~15 s — scrape with settle delays) is the counting instrument.

### Suggested future optimizations

1. **Checkpoint keying by dependency-proof lineage** instead of raw
   native-revision: a continuation under an unchanged full-read-scope
   proof could resume the retained checkpoint across basis churn
   instead of replaying (the spec already licenses this; the churn test
   documents today's replay cost).
2. **Datahike writer resilience**: retry transient storage IOExceptions
   with backoff before writer shutdown; classify causes like the read
   path does.
3. **Width > 1**: the parked concurrency change (`ReducerReadAhead`,
   `DescriptorCoalescing`, `ServiceLifecycle` models and the
   `physical_scheduler` bridge restart there); the reducer's fetch seam
   is the attach point and stayed single-purpose for exactly this.
4. **Commit-graph exact-basis selection** on Datahike (spec'd
   preference) before enabling tiered/LMDB serving topologies at scale.
5. CLJS parity halves (2.4/3.3/4.5/5.5/8.5) via the formal-cljs-smoke
   pipeline, and 8.6 containerized MinIO/JDBC/DynamoDB-local fault
   injection.

## 10. Follow-on remote performance qualification

- [ ] 10.1 Qualify Datahike direct S3 and tiered LMDB/S3 (restart warmth, disk lifecycle, exact-basis behavior at scale) with reproducible fixtures; publish performance envelopes including evicted-checkpoint replay p95/p99.
- [ ] 10.2 Qualify Datahike/JDBC against real pool behavior and database latency.
- [ ] 10.3 Repair or wrap Konserve DynamoDB failure classification; verify strongly consistent read/publication on real DynamoDB; only then enable the topology.
- [ ] 10.4 Qualify Datomic Pro/DynamoDB basis retention, cancellation, and cost on an approved topology.
- [ ] 10.5 Measure million-identity admission/checkpoint retained heap and concurrent-request capacity in production source.
- [ ] 10.6 Publish topology-specific deployment guidance (storage tiering, cache sizing, prewarm) and the operational qualification report; open the separate concurrency change only if these measurements justify it.
- [ ] 10.7 Archive this change after implementation, cleanup, and release evidence are complete; update `HANDOVER.md` if ownership changes before then.

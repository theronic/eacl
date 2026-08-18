# Stable Discovery Assurance Coverage

## How to read this ledger

This ledger maps every requirement in the revised change to the strongest evidence
available. It prevents aggregate proof or benchmark counts from being mistaken
for end-to-end release assurance.

Evidence classes:

- **proved-model** — discharged for the abstract model and its explicit assumptions;
- **checked-executable** — an independent executable oracle or mutation control passed;
- **checked-source-local** — existing adapter source passed a local contract harness;
- **measured** — a named workload was measured; not a theorem;
- **open-source** — the accepted production CLJ/CLJS path does not yet refine the contract;
- **open-remote** — a real storage topology is not yet qualified.

Status values: `Design-closed` means no unresolved abstract design choice is known;
`Partial` means some evidence exists but named gaps remain in the evidence itself;
`Open-source` means the requirement is specified but the accepted production path
does not yet exist. No status means release-complete: nothing below is
release-complete until the production source and applicable adapters pass their
transfer gates. All proved-model
and measured evidence currently lives in the gitignored exploration tree; task 2.1
(archive it) precedes everything.

Evidence produced against the accepted prototype is valid only with logical release
width pinned to one (the prototype's default logical chunk of 64 is a rejected
configuration); the width mutant control in task 3.4 guards this.

## stable-discovery-enumeration

| Requirement | Current evidence | Status | Required transfer gate |
|---|---|---|---|
| Declarative answer and operational order are separate | `GroundedPositiveProgram.dfy`, `StableReducer.dfy`, `ReducerCompleteness.dfy`, `OrderIrrelevance.dfy` **proved-model**; cyclic randomized oracle **checked-executable**. | Design-closed | Production normalization, identities, root projection, and public traces must match the model in CLJ and CLJS. |
| Sealed plans define every semantic ordering input | `ReadRankCertificate.dfy`, `StaticDirectionIndex.dfy`, `SealedVectorOrder.dfy`, `SealedPlanReducerComposition.dfy` **proved-model**; sealed-plan oracle and ordering mutants **checked-executable**. | Design-closed | Production compiler, composite fingerprint (now including the transition interface and admission-key granularity), rank checker, CLJ/CLJS equivalence. |
| One generic reducer with exact per-kind admission | Machine-level leaves (`HistoryFreeReducer`, `ConcreteHistoryFreeRuntime`, `RuntimeStackRefinement`) are direction-agnostic **proved-model**; direction producers (`EaclForwardProducer`, `EaclReverseProducer`) prove the two plan constructions; prototype state shapes are field-identical **checked-executable**. | Design-closed | Implement the unified transition interface; prove per-kind key granularity (entity-only interior key and producing-edge root key mutants). |
| Result uniqueness holds by construction | `ConcreteOutputIdentity.dfy` proves injectivity by construction (empty-body lemmas) **proved-model**. | Design-closed | Single root emission point in both production plan compilers; adapter identity-mapping certification. |
| The canonical reducer is pure and head-driven | `HistoryFreeReducer.dfy`, `AtomicLogicalAdmission.dfy`, `TargetedResultDriver.dfy` **proved-model**; wrong-stack and post-check controls killed. | Design-closed | Bounded pure step/read-demand protocol traced against the model with checked arithmetic and atomic staging. |
| Logical release width is fixed at one value | `ChunkedScan.dfy`, `LogicalScanCursor.dfy`, `OneValueScanNormalization.dfy` **proved-model**; 153 retained/dropped-buffer comparisons **checked-executable**; eager-width order divergence **refuted** by fixture. | Design-closed | Hard-fix logical release in production; land the logical-width mutant control. |
| Pagination uses authenticated result edges | `EdgeBoundaryAuthentication.dfy`, `RelayEdgePagination.dfy`, `RelayCheckpointExecution.dfy` **proved-model**; prototype codec passed tamper/expiry/rotation/bounds controls. | Design-closed | Final bounded codec, key lifecycle, composite-fingerprint binding, Relay integration. |
| Pages compose without gaps or duplicates | `StablePagination.dfy`, `LookaheadPagination.dfy`, `BoundedPageBuffer.dfy`, `PaginationComposition.dfy` **proved-model**; randomized forward/backward traversal **checked-executable**. | Design-closed | Public entry points, repeated/concurrent forks, short terminal pages, CLJS parity. |
| Exact continuation state is retained or reconstructed | `ReducerCheckpoint.dfy`, `RuntimeCheckpointComposition.dfy` (checkpoint carries the pending boundary segment) **proved-model**; publication/replay controls **checked-executable**. | Design-closed | Checkpoint representation including the lookahead segment and delivered/discovered counts; typed resource-exhaustion failure; retained-heap accounting. |
| Continuation respects consistency and basis rules | Cursor-consistency conflict machinery exists in current source (**checked-source-local**); full-read-scope dependency-proof continuation is specified but unproved. | Open-source | Specify and test the read-scope proof obligation per topology; DataScript and current-only Datahike behavior; conflict tests across all four consistency modes (`:minimize-latency`, `:fully-consistent`, `:at-least-as-fresh`, `:at-exact-snapshot`; design Decision 8). |
| Point checks and counts keep operation-appropriate plans | `BidirectionalReachability.dfy`, `EaclBidirectionalReachability.dfy`, `ExactCountComposition.dfy`, `MembershipProbeCheck.dfy` (probe answer = reverse-denotation membership) **proved-model**; `eacl.engine.point-check-test` (probe vs enumeration oracle on six frozen baselines, O(intermediates) property) **checked-executable**; counting admitted work **refuted**. | Design-closed | The point-check route is the membership-probe search (`membership-probe-point-check`); the enumeration form is retained as its oracle; any optimized count route needs an independent denotation-equivalence proof. |

## bounded-physical-execution

| Requirement | Current evidence | Status | Required transfer gate |
|---|---|---|---|
| Logical occurrence and physical read identities are distinct | `DescriptorIdentity.dfy` **proved-model**; descriptor mutants **checked-executable**. | Design-closed | Collision-safe production value types; adapter descriptor completeness. |
| Physical execution is width one and serially integrated | Serial refinement is the base case of `ReducerReadAhead.tla` **proved-model**; every adapter certifies sequential execution **checked-source-local**; concurrency models parked for the future change. | Design-closed | Direct-path implementation with no executor/future/index allocation; seam documentation. |
| Adapter reads have exactly three classified outcomes | `AtomicAttempt.tla` (attempt atomicity, partial-integration mutants killed) **proved-model**; Konserve DynamoDB nil-collapse and JDBC single-attempt escape **measured**. | Open-source | Implement the classified result algebra with cause codes at the reduce-scan seam; per-adapter fault-injection tests. |
| Retries preserve the semantic read | `AtomicAttempt.tla` **proved-model**. | Design-closed | Original-deadline propagation, descriptor/basis reuse, attempt telemetry, nested retry inventory. |
| Chunk retention is bounded and disposable | `BoundedSidecar.dfy` **proved-model**; retention/eviction campaigns **measured**/**checked-executable** (dropping all buffers ~doubled commands; ~630 B per width-64 buffer). | Design-closed | Per-request cap and eviction in production; heap verification. |
| Cancellation is cooperative, nonpublishing, and physically accounted | Semantic freeze and complete-artifact publication **proved-model**; physical interruption **unknown/not supported** on reviewed synchronous stores. | Design-closed | Trace cancellation through stepping, I/O, rendering, publication; slot-hold-until-return in the admission component. |
| Service-edge admission bounds aggregate exposure | Replay-ledger behavior exists in current source (**checked-source-local**); the slim admission component is new. | Open-source | Rehome the ledger; bounded concurrent enumerations; stampede tests. |
| Progress checkpoints are latest, exact, and bounded | `ReducerCheckpoint.dfy`, `WeightedCheckpointSlot.dfy`, `ProgressCheckpoint.tla` **proved-model**; replace-older mutant killed; latest-only retention **measured** (5.04 vs 6.39 MB). | Design-closed | Scalar-ordinal nonregressing replacement (current store replaces unconditionally); fingerprint in the key; byte-weight client option. |
| The engine keeps exactly two closed cache artifacts | `CacheBoundary.dfy` proves projection substitution order-safe and denotation substitution order-unsafe **proved-model**; production tier occupancy **measured** (trivial). | Design-closed | Two-artifact consolidation; answer-key fingerprint binding; cursor-bearing-answer basis rule. |
| Retained state is compact and owned | `OwnedTransientSnapshot.dfy`, `RuntimeStackRefinement.dfy`, `ConcreteHistoryFreeRuntime.dfy` **proved-model**; key/frame/transient wins **measured** (42.7 vs 138.6 MB per million; 79% allocation cut). | Design-closed | Production representations on CLJ and CLJS; retained-heap gates. |

## remote-backend-enumeration-efficiency

| Requirement | Current evidence | Status | Required transfer gate |
|---|---|---|---|
| Backend capabilities are explicit and semantic | Capability vocabulary specified; local scan contracts **checked-source-local**. | Open-source | Closed SPI capability record with the safety/width split and failure-classification fidelity. |
| Qualification counts every cost layer separately | Datahike/S3, MinIO, JDBC, Dynamo-local, LMDB probes **measured**. | Partial | Accepted-engine production telemetry with reproducible collection. |
| Remote qualification is follow-on with local gates first | Local fault-injection feasibility demonstrated in probes **measured**; sequencing is a process decision of this revision. | Design-closed | Execute tasks 8.5–8.6 before routing; publish qualification per topology afterward, including evicted-replay p95/p99. |
| Benchmark matrix covers realistic and adversarial schemas | Adversarial and recursive fixtures explored; prototypes benchmarked **measured**. | Partial | Freeze the complete matrix through public APIs, including the deep-chain serial-cost report. |
| DataScript and local execution avoid remote machinery overhead | Prototype kernel numbers **measured** (6.8–120 KB, 0.003–0.045 ms, kernel-only); end-to-end public path never measured. | Open-source | The binding public-API gates (0.25 ms/25%, ≤1.5x allocation, no linear branch scaling) on CLJ and CLJS. |
| Memory qualification measures retained structures directly | Key/frame/checkpoint fixtures **measured** at million scale. | Partial | Production representations, CLJS, concurrent capacity. |
| Datahike storage topology qualified independently | Root fusion, caches, tiering, GC, bucket growth investigated **measured**; temporal-seek degradation identified. | Partial | Re-run on the accepted engine; commit-graph preference; byte-weighted cache observability. |

## Highest-risk seams

1. Production compilation could fail to refine the modeled rules, ranks, vectors, or the single-root construction.
2. The optimized CLJ/CLJS reducer could diverge from the pure trace or retain delivered history; the always-on invariants and bridges are the guard, not a runtime twin.
3. Cursor/checkpoint codecs, consistency-aware continuation, and exact-basis selection could violate replay or edge authentication; the dependency-proof path is specified but unbuilt.
4. Deletion (task 9.2) is irreversible except by git revert; it is gated on the frozen baselines and evidence archive.
5. Remote behavior may force different budgets and defaults; that risk is deliberately isolated in section 10 and cannot block the engine swap.

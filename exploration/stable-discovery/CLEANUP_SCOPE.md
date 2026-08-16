# Replacement cleanup scope

Status: exploration deletion plan. Nothing listed here is deleted yet. The
legacy paths remain useful only as benchmark and differential oracles until the
replacement passes `BENCHMARK_PROTOCOL.md` and `SOURCE_REFINEMENT.md`.

## End state

The release tree must contain:

- one positive-fragment compiler and sealed-plan representation;
- one stable-discovery semantic reducer per direction, sharing common work
  identity/admission utilities but not paying for the other direction's state;
- one discovery-order ABI based on certified static read cost, sealed canonical
  rule ordinals, certified backend scan order, and exactly one scan value per
  logical reducer transition;
- one bounded physical scheduler/governor outside the reducer;
- one current formal model set matching those runtime interfaces;
- distinct completed-answer, exact progress-checkpoint, and projection-cache
  artifacts;
- legacy point authorization/count specializations only where they remain
  semantically independent and benchmarked.

There must not be a release switch between global-EID enumeration, legacy
FIFO recursive enumeration, byte-ordered stable discovery, and cost-ranked
stable discovery. EACL v8 is unreleased, so compatibility does not justify
multiple semantic authorities or cursor ABIs.

## Source deletion/replacement map

### Legacy global ordered enumeration

After final differential testing, remove the acyclic list route rooted at
these functions in `modules/eacl/src/eacl/engine/v8.cljc`:

- `merge-eid-seqs`;
- `traverse-acyclic-forward-path` / `traverse-acyclic-forward`;
- `traverse-acyclic-reverse-path` / `lookup-acyclic-subject-eids`;
- `lazy-merged-acyclic-lookup`, `acyclic-lookup`, and
  `count-acyclic-pages` where their only remaining purpose is enumeration;
- `:certified-acyclic-eid-order`, `:lookup-eid`, and the corresponding
  global-order pagination path;
- acyclic merge counters such as `:merge-advances` and the claims that bind
  them to enumeration performance.

Do not blindly remove point-check helpers or relation-path compilation used by
`can?`/specialized counts. First use the source call graph and tests to split
shared helpers from list-only merge machinery.

If no non-enumeration caller remains, also remove:

- `eacl.lazy-merge-sort` from the engine;
- generated `:ordered-merge-step` / `:ordered-merge-chunk` decisions;
- their portable decision implementations, boundary validators, smoke tests,
  benchmarks, conversion records, source-specialization claims, and mutants.

### Current dual-mode portable candidate

Replace, rather than incrementally preserve, the `:legacy-fifo` versus
`:stable-discovery` branches in
`modules/eacl/src/eacl/engine/portable_indexed.cljc`:

- remove the scheduling discriminator and byte-only
  `:portable-canonical-encoding-order` contract;
- remove generic emitted-result identity storage; retain only the scalar result
  ordinal state required by pagination;
- remove forward grant buckets, consumer buckets, and generic
  consumer/grant-pair history or snapshots;
- remove reverse grant buckets, consumer buckets, peer snapshots, Cartesian
  pair history, and join cursors; retain only exact admitted concrete goals;
- replace runtime canonical-byte ordering with sealed static vectors ordered
  once by certified read-cost rank and canonical rule ordinal; preserve backend
  scan and reducer admission order without runtime sorting;
- remove `:consumer-grant-joins` from both direction-specific runtimes;
- remove validation and preflight limits for state fields the replacement no
  longer owns.

The current candidate's tests remain characterization oracles until the new
source bridge and benchmark gates pass. Then replace expectations about
symmetric joins, byte order, old retained-unit formulas, and emitted sets with
the minimum direction-specific model.

### Cursor and progress paths

- bump the unreleased discovery/cursor ABI once;
- remove old global-EID and byte-stable cursor decode/continuation branches;
- remove any “same last EID” rebasing/reminting path;
- keep only exact-basis context, semantic/order/plan ABI, fixed positive page
  size, represented edge ordinal and canonical boundary identity, and one
  cursor MAC/AEAD; page-navigation mode remains request syntax, not cursor
  identity;
- derive `after` checkpoint boundary from edge ordinal and `before` checkpoint
  boundary from the exact previous-page start; never resume a history-free
  reducer at a backward page's exclusive end;
- keep completed answer, progress checkpoint, and projection chunk types and
  cache namespaces disjoint;
- remove any enumeration route that treats a flat subproblem denotation as a
  successor/result batch; projection hits retain exact order and pass through
  normal request-local admission;
- remove claim overhead for cursor versions no release accepts.

### Physical execution

Retain the service governor and physical scheduler only after reconciling them
to the minimum lifecycle models:

- permanent service-wide capacity with canonical reserve;
- request-local equality-complete descriptor coalescing;
- complete validated response integration only at canonical head;
- physical charge held until actual backend return;
- exact live epoch required for cache publication;
- no cross-request shared in-flight futures in the first implementation.

Authoritative scan frames retain only logical occurrence identity and the exact
exclusive resume bound. Fetch end, response offset, and bounded response
vectors live in a detachable request sidecar. Progress checkpoints exclude the
sidecar. Delete any checkpoint-buffer ownership machinery or cursor/order ABI
field for physical fetch width; governed live/cache buffers remain a physical
optimization and may be dematerialized under pressure.

The current `EaclSharedReadWaiters` path and source support for cross-request
flight sharing should be deleted unless a separate later change reintroduces
it with waiter/refcount/cancellation/epoch/fairness proofs. It is not part of
the minimum design.

## Formal-model replacement map

The current untracked monolithic/candidate set is obsolete as the final model:

- `formal/dafny/StableDiscovery.dfy`;
- `formal/tla/EaclPureReduction.*`;
- `formal/tla/EaclDiscoveryConcurrency.*`;
- `formal/tla/EaclServiceGovernor.*`;
- `formal/tla/EaclProgressCache.*`;
- `formal/tla/EaclSharedReadWaiters.*`;
- their candidate mutation configurations.

Before release assurance is rebuilt, replace them with the small model set now
under `target/exploration/stable-discovery`:

- pure reducer: `StableReducer` as observation, `HistoryFreeReducer` as runtime
  erasure, `TargetedResultDriver` as bounded response driver,
  `ConcreteHistoryFreeRuntime` as hot-loop representation,
  `OwnedTransientSnapshot` as full-state representation ownership,
  `ReducerCompleteness`, `ReducerCost`;
- EACL grounding: `GroundedPositiveProgram`, `EaclForwardGrounding`,
  `ConcreteOutputIdentity`, `OrderIrrelevance`;
- direction-specific work: `EaclForwardProducer`, `EaclReverseProducer`,
  `StaticReverseFrontier`, `BidirectionalReachability`,
  `EaclBidirectionalReachability`, `StaticDirectionIndex`,
  `ReadableWorkIndex`, `RuntimeStackRefinement`, `ChunkedScan`,
  `LogicalScanCursor`, `OneValueScanNormalization`, `BoundedSidecar`;
- order/cursors: `ReadRankCertificate`, `SealedVectorOrder`,
  `SealedPlanReducerComposition`, `StablePagination`,
  `RelayEdgePagination`, `EdgeBoundaryAuthentication`,
  `RelayCheckpointExecution`, `LookaheadPagination`,
  `PaginationComposition`, `BoundedPageBuffer`,
  `RuntimeCheckpointComposition`, `ReducerCheckpoint`;
- lifecycle: `ReducerReadAhead`, `ServiceLifecycle`,
  `DescriptorCoalescing`, `DescriptorIdentity`, `WeightedResponseLease`,
  `ProgressCheckpoint`, `AtomicAttempt`, `AtomicLogicalAdmission`,
  `CacheBoundary`, `ExactDedupLowerBound`;
- all focused mutation configurations in the fast gate.

Do not copy exploration models mechanically into assurance. First rename their
abstract types to the final source contract, add source-reference comments and
manifest digests, run the independent source-refinement bridge, and verify the
full release gate. Old model files and claims are deleted in the same change so
there is no ambiguity about which theorem authorizes production.

## Claim and documentation cleanup

Audit and rewrite at least:

- `formal/verification/stable-discovery.edn`;
- `formal/verification/indexed-traversal.edn`;
- `formal/verification/performance-gates.edn`;
- `formal/verification/manifest.edn`;
- `formal/verification/assurance-matrix.edn`;
- `formal/verification/generated-boundary.edn`;
- `formal/verification/conversion-boundary.edn`;
- `formal/verification/source-specializations.edn`;
- `formal/verification/acyclic-kernel.edn`;
- `formal/verification/temporal-model.md`;
- `formal/README.md` and `docs/formal-verification.md`.

Remove claims and envelopes tied solely to:

- global ordered merge/list EID order;
- `:legacy-fifo` or byte-order scheduling;
- generic emitted-result sets;
- `consumer-grant-joins` in either direction;
- cross-request shared-read waiter behavior;
- old cursor versions and old progress-cache semantics;
- model/state fields absent from the replacement.

Keep historical measurements only in a clearly labeled benchmark-baseline
section. Historical numbers must not remain active release thresholds after
their counters or algorithms disappear.

## OpenSpec rewrite

The current change artifacts are internally complete but describe the older
candidate in 1,975 lines, including symmetric G/C/E state, byte-order DFS,
cross-request sharing, old progress semantics, and already-completed candidate
implementation tasks. Once exploration confidence closes, use the
`openspec-update-change` workflow to rewrite:

- `proposal.md` around the minimum direction-specific engine;
- all three delta specs around exact stable discovery, certified read-cost
  order, and bounded physical execution;
- `design.md` from the current 874-line candidate narrative to the formal
  refinement chain and explicit trust boundaries;
- `tasks.md` so old completed candidate work becomes deletion/benchmark-oracle
  work, and no implementation task is marked complete merely because the
  obsolete candidate exists.

## Deletion gate

Legacy code and models may be deleted only after all of the following are
captured in one reproducible report:

1. old and replacement complete denotations match the independent oracle;
2. cursor/page invariants pass across page and chunk sizes;
3. all source-refinement and lifecycle mutants are killed;
4. the frozen source benchmark passes, including the small DataScript
   allocation envelope;
5. at least one primary remote topology records physical GET/PUT and latency
   improvement without violating capacity/cancellation accounting;
6. repository-wide search finds no live reference to removed operations,
   counters, cursor kinds, model IDs, or claim IDs;
7. the full formal and backend suite passes after deletion.

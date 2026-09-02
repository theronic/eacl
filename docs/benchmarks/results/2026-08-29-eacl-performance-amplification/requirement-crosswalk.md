# Durable requirement crosswalk

This table is the compact implementation index for every retained scenario in
the workspace change. Each scenario is now normative in the named Core main
spec; this document does not create a second requirement authority.

## Stable engine performance

Durable spec: `openspec/specs/stable-engine-performance/spec.md`.

| Requirement | Retained scenarios | Production owner | Test/evidence target | Owning tasks |
| --- | --- | --- | --- | --- |
| Exact permission aliases do not duplicate acyclic traversal | Two arrows differ only by a pure alias; Alias chain is composite or cyclic; Recursive stable plan contains an alias; Provider returns schema rows in another encounter order | `eacl.engine.sealed-plan/seal-plan` and least-path frontier consumers | alias plan/property/differential tests; EACL-FORMAL-065; alias reproduction | 3.1-3.7 |
| Corrected plan-bound identity fails closed | Cursor crosses the corrected-plan rollout; Private plan state crosses the rollout; Sink layout changes without changing plan semantics; Unaffected plan is rebuilt; Scalar completed answer crosses a compatibility rollout; Work-only adapter capability changes | sealed-plan fingerprinting, cursor/checkpoint codecs, completed-cache keys | compatibility matrix and old/new cursor/cache fixtures | 2.6, 3.4, 5.5-5.6 |
| Output retention is bounded by operation demand | Exact count discovers a large result set; Forward page requests N results; Last or backward page follows a deep prefix; Point authorization uses its specialized route; Stable page resumes across its lookahead boundary; Acyclic page uses least-path evaluation | `eacl.engine.stable-reducer`, stable page routes, least-path and point-route selectors | sink retention/differential/checkpoint tests | 4.1-4.5 |
| Duplicate freedom is constructional on the production path | Several derivations reach one result; Admission uniqueness is mutated | stable admission and completion | duplicate oracle, structural allocation assertion, mutation control | 4.6 |
| Reducer bookkeeping has bounded per-transition cost | Long physical chunk releases one value at a time; One sidecar is touched repeatedly; Continuations churn at fixed live capacity; Transition has zero or one certified successor; Successor uniqueness is not certified | stable reducer and continuation store | deterministic state traces, capacity checks, staged-limit mutants | 4.7, 4.9-4.12 |
| Physical response vectors are reused and make verifiable progress | Adapter returns a full-width bounded response; Routed boundary returns a realized vector; Least-path requests a narrow eager scan | physical route, stable/least-path/point consumers | vector identity, width-observer, conservative-lookahead differentials | 4.7-4.8 |
| Resource limits are staged, exact, and coherent with public demand | Successor batch would exceed a limit; Public page demand exceeds a fixed internal default; Schema-dependent work exhausts a limit; Effective public maximum is tightened | execution contract, stable admission, public demand normalization | boundary/resource differentials and staged-commit mutants | 4.13 |
| Deadline and cancellation checks retain semantic-quantum boundaries | Cancellation occurs during pure transitions; Deadline expires around a physical read | generated evaluator and physical callers | injected cancellation/deadline traces | 4.15 |
| Stable rank costs have one fingerprinted production identity | Production rank cost changes; Production and formal rank identities diverge | sealed-plan rank contract and fingerprint | independent formal rank comparison and mutation control | 3.8 |
| Stable-engine behavior remains cross-runtime and cross-backend conformant | Alias-rich fixture runs on CLJ and CLJS; Optimized path changes a limit outcome; Proved duplicate work is removed | all changed portable engine paths | CLJ/CLJS/cross-backend differential and logical-work gates | 4.16, 6.3, 6.6 |

## Authorization request efficiency

Durable spec: `openspec/specs/authorization-request-efficiency/spec.md`.

| Requirement | Retained scenarios | Production owner | Test/evidence target | Owning tasks |
| --- | --- | --- | --- | --- |
| Each invariant is validated at its owning boundary | Valid public request enters internal recursion; Custom backend violates an applicable output guard; Selected basis already captured the backend snapshot identity | public orchestration, backend guard, selected-basis/request context | boundary call ledger and malformed custom-adapter tests | 5.1 |
| Certified synchronous membership batches retain aligned positional results | One descriptor has one thousand distinct misses; Native result has wrong cardinality or type; Adapter does not advertise native batching; Transport can reorder detached responses; Batched probes reject a long candidate prefix; Batched probes find accepted lookahead | direct-membership dispatcher and filtered authorization scanning | scalar/native differential, malformed vector, rejection-heavy pagination | 5.2-5.3 |
| Completed exact hits avoid only work whose compatibility is already proved | Compatible exact generation serves a page hit; Trusted restored page has no resident plan; Two requests first-use the same restored entry; Live current request has a cache entry; Entry predates the compatibility rollout | orchestration completed semantic key, basis cache, subproblem restore | compatibility matrix, restored-value red test, zero-derived-work hit trace | 5.5-5.6 |
| Exact cache correctness is independent of recency and telemetry mutation | Exact entry is evicted during a read; Optional telemetry is disabled | subproblem cache hit/eviction and cache metrics | held-entry race and zero-observer-mutation tests | 5.7 |
| Result-cache computations never join another request | Concurrent requests miss the same key; Another computation is in flight | answer/subproblem/continuation and derived-cache miss paths | promise-controlled independent miss/failure tests | 5.8-5.9 |
| Cache semantics remain independent of retention policy | A validated completed mapping is present, absent, or arbitrarily evicted; Domain or source changes | generated partial-map cache model and standard-LRU host boundary | flat cache refinement, library conformance, and stale-policy mutant | Superseded by `standardize-authorization-cache-storage` 2.5, 7.4 |
| Plan and request memo hits are read-first | Stable plan is already resident; Concurrent plan miss races; Installed generation was evicted | engine memo/plan and schema-derived registries | allocation/read-first and concurrent install/failure tests | 5.9 |
| Mandatory resource meters are exact and observation is optional | Diagnostics are disabled during a limited request; A constant hot-path counter uses a preindexed slot; Optional metric is unavailable | request counters and optional observers | exact meter differential, overflow/unknown-key tests, telemetry toggles | 5.11 |
| Datomic exact acquisition synchronizes only when the captured local basis is behind | Requested locator is already local; Requested locator is ahead locally; Token is invalid or belongs to another source; Synchronization cannot reach the locator; Source lifecycle changes | `eacl.datomic.backend` exact source acquisition | live controlled Datomic operation counts and classified failure tests | 5.12-5.13 |

## Performance assurance

Durable spec: `openspec/specs/performance-assurance/spec.md`.

| Requirement | Retained scenarios | Code/tool owner | Test/evidence target | Owning tasks |
| --- | --- | --- | --- | --- |
| Every performance result is bound to the Core source it measures | Candidate source changes after measurement; Dirty worktree is measured | evidence envelope/analyzer | identity mismatch and dirty-tree rejection | 2.8, 2.10, 2.13 |
| Instrumentation is validated before its metric is used | Runtime does not expose exact allocation; Instrumentation changes measured latency materially; Unit or counter schema is malformed | metric capability/calibration | unsupported, calibration-control, schema mutants | 2.9 |
| Evidence separates semantic work, allocation, retention, and elapsed time | Allocation falls but retained state grows; Backend calls fall but elapsed time regresses; Work moves outside the measured thread | analyzer/comparison | dimension-specific acceptance and deferred-work attribution | 2.8-2.9 |
| Benchmark schemas and evaluators are smoke-tested in ordinary CI | Full benchmarks are excluded from pull-request CI; Evidence lane has no samples | benchmark fixtures and analyzer golden test | small ordinary-CI fixture and empty-sample rejection | 2.10 |
| Formal performance claims name current production consumers | Alias theorem names retired frontier code; Output sink is mutated to retain every result | assurance matrix and formal mutation controls | source-closure resolution and structural mutant | 3.6-3.7 |
| Core qualification uses multiple shapes and scales | One-point allocation result looks linear; Million-result label is unverified; Shape improves while another changed shape regresses | fixture coverage/analyzer | three-scale model checks and verified Datomic counts | 2.11, 6.6 |
| Each accepted optimization has a reproduced benefit or a separate correctness reason | Suspected hot-path validation is not measurable; Datomic exact selection violates an existing contract | mechanism ledger | zero-pending disposition audit | 1.9, 6.7 |
| Core release qualification includes one predeclared material speed win | Candidate only reduces allocation; Candidate improves the predefined release lane; Fast result changes semantics | frozen acceptance/analyzer | `releaseWin` confidence decision plus semantic safety lanes | 2.12, 6.5 |
| Tuning observations and final confirmation are distinct | Fastest pilot run is reported as final evidence; Final candidate changes after confirmation begins | acceptance record and evidence phases | phase/source mismatch rejection | 2.12, 6.5 |
| Thresholds and evidence are immutable to the candidate they judge | Candidate edits a failed threshold; Old threshold used a different source or instrument | frozen acceptance record | digest/source/instrument mismatch rejection | 2.12 |
| CLJ and CLJS share semantic gates but use runtime-appropriate performance metrics | JVM fast path lacks CLJS implementation; CLJS cannot report exact allocated bytes | portable implementation and runtime metric registry | CLJ/CLJS semantic parity; unsupported allocation classification | 4.16, 6.3 |
| Prior research is source-closure-aware | Old report names removed production code; REPL observation supports a finding | source-closure inventory and ledger | live-symbol resolver and source-bound deterministic reproducer | 1.2, 6.7 |

## Mechanical completeness rule

For each of the three capability names, the set of `#### Scenario:` headings in
the workspace delta spec must equal the set in the durable Core main spec until
the workspace change is archived. A mismatch fails task 2.1; later normative
edits are made to both copies during this implementation.

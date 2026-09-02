## Purpose

Define reproducible Core-only evidence that distinguishes real authorization speed and memory improvements from stale source, invalid instrumentation, semantic drift, or one-point benchmark noise.

## Requirements

### Requirement: Every performance result is bound to the Core source it measures

Baseline and candidate evidence SHALL identify the exact Core commit and complete source-tree digest; the dependency lock when one exists, otherwise a digest of the resolved Clojure basis or classpath; runtime and target; backend and adapter identity; fixture definition and immutable basis; configuration; command; harness version; cache state; raw sample digest; and the environment fields that can change the result. The minimum environment fingerprint is operating system and architecture, available CPU allocation, JVM or Node version and flags, garbage collector, process limits, and relevant Core configuration or environment variables. Dirty-source evidence MUST also record a dirty-file inventory and patch or content digest covering tracked, untracked, and generated measured inputs, and MUST NOT be represented as evidence for an unmodified commit. Each sample MUST match the artifact identity it claims. A baseline/candidate comparison intentionally binds different declared source-role identities, but every comparison-controlled harness, fixture, runtime, backend, configuration, and environment field MUST match or have a predeclared source-matched comparison design; otherwise the samples are incomparable rather than a pass or failure.

#### Scenario: Candidate source changes after measurement
- **WHEN** any measured Core source, dependency, harness, formal mapping, fixture, or configuration changes
- **THEN** the prior result cannot qualify the changed candidate

#### Scenario: Dirty worktree is measured
- **WHEN** a benchmark runs from a dirty Core worktree
- **THEN** evidence records the dirty tree identity and cannot be attributed only to `HEAD`

### Requirement: Instrumentation is validated before its metric is used

Every reported metric SHALL define its unit, clock or counter source, supported runtime, measured scope, operation start and end, warmup/cache regime, missing-value behavior, and estimator. The harness SHALL validate availability, monotonicity where applicable, dimensional units, and material instrumentation overhead against deterministic controls. A calibration MAY be reused across lanes only for the same instrument, runtime, and version tuple. Each lane SHALL require only the metrics applicable to its runtime and claim; mandatory Core counters and elapsed time remain required. Unsupported metrics MUST be recorded as unavailable rather than zero and SHALL block only claims that depend on them.

#### Scenario: Runtime does not expose exact allocation
- **WHEN** a runtime cannot measure exact request-attributable allocated bytes
- **THEN** the report marks that metric unsupported and uses declared logical-work or structural-allocation proxies where appropriate
- **AND** makes no exact allocated-byte claim for that runtime

#### Scenario: Instrumentation changes measured latency materially
- **WHEN** a diagnostic instrument's calibrated overhead exceeds its declared tolerance
- **THEN** its samples cannot qualify product-path latency and are reported in a separate diagnostic lane

#### Scenario: Unit or counter schema is malformed
- **WHEN** a sample has an unknown unit, missing operation boundary, unsupported-as-zero value, or non-finite measurement
- **THEN** the evidence validator rejects it

### Requirement: Evidence separates semantic work, allocation, retention, and elapsed time

Core evidence SHALL report mandatory limit counters and elapsed time plus the logical-work, physical-command, allocation, retention, garbage-collection, or process-memory metrics applicable to the changed mechanism and supported by the runtime. A reduction in one dimension MUST NOT be described as a reduction in another without direct evidence. Work moved to a worker thread or deferred until after the measured operation MUST remain attributable when the chosen metric claims total request work.

#### Scenario: Allocation falls but retained state grows
- **WHEN** a candidate allocates fewer temporary bytes but retains more live state
- **THEN** the report shows both changes and cannot call the memory outcome an unqualified improvement

#### Scenario: Backend calls fall but elapsed time regresses
- **WHEN** a candidate issues fewer physical commands but has worse elapsed time outside the declared tolerance
- **THEN** command reduction does not satisfy a latency improvement gate

#### Scenario: Work moves outside the measured thread
- **WHEN** an optimization moves request work to another thread or after nominal result construction
- **THEN** any total-work claim uses a scope that includes that work or explicitly limits the claim to the measured thread/span

### Requirement: Benchmark schemas and evaluators are smoke-tested in ordinary CI

Ordinary non-benchmark CI SHALL load every evidence schema and gate used for qualification, validate a small deterministic fixture, reproduce the golden report's canonical decision fields, and reject malformed or missing inputs. Volatile timestamps, paths, process identifiers, and equivalent provenance fields SHALL NOT be compared as golden decisions. Full performance runs MAY remain outside pull-request CI, but their analyzer MUST NOT first execute only during release qualification.

#### Scenario: Full benchmarks are excluded from pull-request CI
- **WHEN** ordinary CI omits long-running scale measurements
- **THEN** it still exercises the same parser, schema, metric calculations, comparison logic, and fail-closed malformed cases on a small fixture

#### Scenario: Evidence lane has no samples
- **WHEN** a required lane is missing, empty, or contains only invalid samples
- **THEN** the gate fails rather than treating the lane as zero work or zero latency

### Requirement: Formal performance claims name current production consumers

Every formal or generated claim used to justify removal of production work SHALL map to the live production decision or state transition it constrains, with source-closure and artifact identity. A mapping to a retired function, model-only route, or test oracle is stale and cannot justify an optimization. For every affected formal workstream, performance-oriented mutations SHALL fail when each applicable mapped output-history, alias-duplication, rank-identity, staged-limit, or progress invariant is deliberately broken. Mutation controls SHALL assert structural or logical work rather than noisy wall-clock latency.

#### Scenario: Alias theorem names retired frontier code
- **WHEN** the EACL-FORMAL-065 mapping resolves only to a removed frontier implementation rather than the live sealed planner
- **THEN** the formal conformance gate fails until the mapping and executable refinement target current production

#### Scenario: Output sink is mutated to retain every result
- **WHEN** a qualification mutant makes exact count retain the complete emitted vector or restores a result-width uniqueness pass
- **THEN** the performance/formal gate detects the regression

### Requirement: Core qualification uses multiple shapes and scales

The evidence record SHALL map each retained mechanism to its current-source reproducer, at least one adversarial fixture, measured operations, and scale points. Across the changed paths, applicable fixtures SHALL cover flat grants, exact pure-alias chains, dense overlap, sparse sharing, deep arrows, recursive/cyclic plans, rejection-heavy membership, continuation replay, cache hit/miss/bypass, and physical nonprogress. Every growth claim SHALL use at least three predeclared independently measured cardinalities and name the fitted or ratio model and acceptance tolerance; it is evidence over those sizes, not a proof of asymptotic complexity. The pinned Datomic fixture SHALL run 30,000-, 100,000-, and verified 1,000,000-result lanes where the public operation semantically supports those cardinalities. A combination is unsupported only when the public contract makes it inapplicable; timeout, out-of-memory, resource exhaustion, or slowness on a baseline-supported lane is a failure, not an omission.

#### Scenario: One-point allocation result looks linear
- **WHEN** a candidate measures only one result cardinality
- **THEN** it cannot claim a growth rate or removal of result-width amplification

#### Scenario: Million-result label is unverified
- **WHEN** a database or fixture is described as containing one million relevant results without an independently recorded basis and cardinality check
- **THEN** the lane is invalid until the fixture identity and count are verified

#### Scenario: Shape improves while another changed shape regresses
- **WHEN** an optimization improves a flat fixture but regresses an alias-rich, recursive, or rejection-heavy fixture governed by the same changed path beyond its declared tolerance
- **THEN** the workstream does not qualify

### Requirement: Each accepted optimization has a reproduced benefit or a separate correctness reason

Before a mechanism is retained in the final candidate, it SHALL be linked to a current-source reproducer and primary metric, or be identified as independently required for correctness or contract reconciliation by naming the violated requirement and its executable test. A reproduced mechanism retained in the candidate SHALL improve its linked metric by the predeclared mechanism-level tolerance or be removed. A mechanism whose claimed cost is refuted and which has no separate correctness reason SHALL be removed from this change rather than implemented speculatively.

#### Scenario: Suspected hot-path validation is not measurable
- **WHEN** profiling shows a proposed validation removal has no material work, allocation, or latency effect and it fixes no correctness defect
- **THEN** that refactor is dropped from the candidate

#### Scenario: Datomic exact selection violates an existing contract
- **WHEN** current production unconditionally synchronizes despite a valid local basis already covering `T`
- **THEN** the correction may proceed as a contract fix even if one local benchmark shows negligible latency

### Requirement: Core release qualification includes one predeclared material speed win

Before candidate-specific pilots, the evidence record SHALL name exactly one `releaseWin` lane that exercises at least one retained reproduced mechanism: one public end-to-end Core authorization operation, workload, runtime, cache regime, latency-or-throughput metric, estimator, direction-normalized effect formula, and practically meaningful improvement margin derived from a product budget or candidate-independent rule. The effect SHALL be positive for improvement, for example latency `(baseline - candidate) / baseline` or throughput `(candidate - baseline) / baseline`. The response-latency boundary begins at public Core API entry after fixture setup and ends when the returned result, page, count, cursor, or typed error is fully realized; it includes normalization, consistency-basis selection, plan, and cache work on that response path. Request-attributable work continuing after response completion SHALL be charged separately to a declared total-work metric and safety lane rather than extending response latency. Fixture construction and declared warmup occur outside both boundaries. The final candidate qualifies only when the lower bound of the effect's predeclared one-sided confidence interval clears the practical improvement margin. Every affected safety lane SHALL likewise use a predeclared direction-normalized degradation formula and non-regression tolerance, passing only when its upper confidence bound remains within that tolerance. Allocation, retained-state, or command-count improvements are additional gates and cannot substitute for the `releaseWin`. Correctness, order, consistency, deadline, and resource-limit behavior remain mandatory regardless of speed.

#### Scenario: Candidate only reduces allocation
- **WHEN** a candidate reduces allocated bytes but does not clear the predefined `releaseWin` latency or throughput margin
- **THEN** the overall change has not yet satisfied the requirement to make Core faster

#### Scenario: Candidate improves the predefined release lane
- **WHEN** the final candidate's confidence bound clears the predefined practical speed margin on fresh comparable `releaseWin` samples and affected safety-lane bounds remain within tolerance
- **THEN** the speed requirement is satisfied for the named workload envelope

#### Scenario: Fast result changes semantics
- **WHEN** a faster candidate changes denotation, result order, count/truncation, cursor composition, a non-resource typed failure, consistency selection, or introduces an earlier resource failure
- **THEN** qualification fails

### Requirement: Tuning observations and final confirmation are distinct

Candidate-specific profiling and pilot runs MAY guide implementation and choose bounded configuration values after the `releaseWin`, safety lanes, and acceptance rules are fixed. Final qualification SHALL use fresh samples after the candidate and acceptance record are frozen. Baseline and candidate SHALL run in randomized or counterbalanced interleaved independent blocks unless the evidence record justifies a different source-matched design. Before sampling, the record SHALL fix the sampling unit, block order rule, sample or block count, confidence level and interval method, estimator, outlier policy, and stopping rule. Pilot samples MUST NOT be silently reused as final confirmation.

#### Scenario: Fastest pilot run is reported as final evidence
- **WHEN** several implementations or configurations are explored and the fastest observed pilot is selected
- **THEN** that pilot result cannot qualify the winner without fresh confirmation

#### Scenario: Final candidate changes after confirmation begins
- **WHEN** source, configuration, fixture, harness, or acceptance margins change after final sampling begins
- **THEN** confirmation restarts for the changed identity

### Requirement: Thresholds and evidence are immutable to the candidate they judge

The final acceptance record SHALL bind the `releaseWin` and each safety lane to its baseline evidence, sampling unit and count, confidence level and interval method, block order and stopping rule, metric, direction-normalized effect/degradation formula, estimator, practical margin or ceiling, tolerance, fixture, runtime, and command before final candidate samples are inspected. The candidate MUST NOT pass by editing, omitting, substituting, or rebaselining a failed required lane. Historical thresholds unsupported by current comparable evidence SHALL be removed or marked inactive rather than copied forward.

#### Scenario: Candidate edits a failed threshold
- **WHEN** a required candidate lane fails and the same candidate changes its margin, tolerance, or metric afterward
- **THEN** the evidence gate rejects the modified record; any new qualification attempt requires a new versioned acceptance record with rationale fixed before new candidate sampling

#### Scenario: Old threshold used a different source or instrument
- **WHEN** a historical threshold cannot be reproduced under the current source, fixture, runtime, and validated instrument
- **THEN** it cannot serve as a release gate for this change

### Requirement: CLJ and CLJS share semantic gates but use runtime-appropriate performance metrics

CLJ and CLJS SHALL execute the same portable semantic fixtures and compare denotation, order, counts, cursors, and typed failures while applying the declared work-refinement relation to counters. Isolated JVM Core lanes SHALL report allocated bytes and process/thread metrics when validated instruments are available. For every changed portable path, CLJS SHALL run a wall-time non-regression lane plus the applicable logical-work or structural-allocation assertion. It MAY use additional object/allocation proxies and retained-state measures appropriate to its runtime; lack of a JVM-only metric SHALL NOT excuse semantic drift or block an unrelated CLJS conformance gate.

#### Scenario: JVM fast path lacks CLJS implementation
- **WHEN** portable Core behavior changes but the corresponding CLJS path retains old semantics or fails the shared fixture
- **THEN** the change cannot qualify as cross-runtime Core behavior

#### Scenario: CLJS cannot report exact allocated bytes
- **WHEN** exact allocated-byte instrumentation is unavailable for the CLJS runtime
- **THEN** CLJS qualification uses its declared supported metrics and the report makes no exact byte-allocation comparison

### Requirement: Prior research is source-closure-aware

README statements, formal models, archived reports, Lore techniques, REPL observations, and old benchmark results SHALL be treated as investigation inputs rather than current production facts. An accepted finding MUST resolve to live Core source and a reproducible current-source test or be explicitly justified as an independent correctness requirement. Any REPL or auxiliary process started by the qualification harness MUST be stopped after its observations are captured.

#### Scenario: Old report names removed production code
- **WHEN** prior research identifies a cost in a path that no longer serves the public operation
- **THEN** the finding is refuted or redirected to the live path before implementation

#### Scenario: REPL observation supports a finding
- **WHEN** an interactive experiment reveals a repeatable amplification mechanism
- **THEN** the accepted observation is converted into a deterministic test or benchmark with source and fixture identity
- **AND** the REPL started by this work is stopped afterward

# remote-backend-enumeration-efficiency Specification

## Purpose

Define measurable backend capability certification, cost telemetry, and performance qualification for stable-discovery enumeration across DataScript, Datomic, and Datahike storage topologies at width one.

## ADDED Requirements

### Requirement: Backend capabilities are explicit and semantic

Each adapter topology MUST declare immutable-basis support, strict scan order and uniqueness, replayability, strict continuation progress, atomic response realization, failure classification fidelity, physical cancellation behavior, termination-on-return behavior, and nested retry exposure. The capability record MUST keep semantic concurrent-read safety separate from deployment width; in this change every deployment width is one, and a backend name alone MUST NOT enable concurrency or historical-cursor promises. Missing capabilities default to the most conservative safe policy.

#### Scenario: Uncertified failure classification

- **WHEN** an adapter cannot distinguish an authoritative empty scan from storage failure
- **THEN** the topology fails qualification and is not enabled for stable discovery

#### Scenario: Current-only topology

- **WHEN** an adapter cannot select the exact cursor basis after a relevant database change
- **THEN** it rejects continuation or requires the certified full-read-scope dependency proof
- **AND** never falls forward silently

### Requirement: Qualification counts every cost layer separately

Benchmarks and telemetry MUST distinguish canonical reducer transitions, logical scan occurrences, adapter attempts, values fetched, values consumed, storage node-cache hits and misses, driver or SDK attempts, and physical remote operations where observable. No fixed ratio between logical scans, node restores, and object-store operations may be assumed.

#### Scenario: Datahike cold read

- **WHEN** one logical scan restores several persistent nodes
- **THEN** telemetry reports one logical occurrence and the separately observed node and remote-operation counts

### Requirement: Remote qualification is follow-on performance work with local gates first

Engine routing is gated on local correctness and performance only: DataScript CLJ/CLJS gates, in-memory adapters, and controlled MinIO, JDBC, and DynamoDB-local contract tests with injected latency, jitter, failure, and missing-node faults. Real remote topologies (production S3, tiered LMDB/S3 at scale, production JDBC pools, real DynamoDB, Datomic Pro) are qualified afterward; that qualification gates topology performance claims, deployment defaults, and Datahike/DynamoDB enablement — not the engine swap. Datahike/DynamoDB MUST remain unqualified until its storage layer's failure-cause collapse is repaired or wrapped.

Remote qualification MUST measure, per topology: cold and warm first page, continuation hit, evicted-checkpoint replay (p95/p99 — the headline number), full enumeration, exact count, and remote operation counts, using reproducible fixtures, seeds, and commands from the repository.

#### Scenario: Engine routed before remote benchmarks

- **WHEN** local gates pass and remote performance qualification has not run
- **THEN** the engine may route all public entry points at width one
- **AND** no remote-topology performance claim or deployment default is published without qualification

#### Scenario: Evicted-checkpoint replay measured

- **WHEN** a topology is qualified
- **THEN** the report includes evicted-checkpoint continuation latency distributions, not only first pages

### Requirement: The benchmark matrix covers realistic and adversarial schemas

Qualification MUST cover acyclic direct, overlapping union, deep arrow, high fanout, recursive chain, star, mixed, broad union, and cyclic schemas; all-authorized, narrowly authorized, unauthorized, and late-productive principals; first result, first page, continuation hit, replay, full enumeration, exact count, and point check; cold and warm storage states. Deep dependent chains MUST be reported as depth-proportional serial cost explicitly rather than averaged away.

#### Scenario: First-page regression gate

- **WHEN** a candidate engine opens substantially more logical scans than the certified productive prefix
- **THEN** the regression gate fails even if a warm completed-answer cache hides elapsed latency

#### Scenario: Full-enumeration trade-off

- **WHEN** one-value release adds warm CPU to a complete traversal
- **THEN** the cost is reported separately from first-page work
- **AND** adoption requires it to remain within the agreed local regression budget

### Requirement: DataScript and local execution avoid remote machinery overhead

The accepted implementation MUST benchmark the direct width-one path against the previous EACL v8 local engine with completed-answer caching disabled, through the public API. The binding gates: median latency within 0.25 ms or 25% of legacy on trivial queries, allocation at most 1.5x legacy, no linear scaling with unvisited branches, result equality, order stability, and recursive stack depth. Scheduler, future, and symmetric-join overhead MUST be absent from the hot path.

#### Scenario: Simple local query

- **WHEN** the query performs zero or one storage scan
- **THEN** the hot path allocates no executor, future, descriptor-index, or join structures

### Requirement: Memory qualification measures retained structures directly

Qualification MUST measure exact-admission key size, scan-frame size, checkpoint retained heap, retained chunk weight, allocation rate, and peak live set for representative page prefixes and complete traversals, in the production representations on both CLJ and CLJS. Logical weight counters MUST NOT be presented as JVM bytes.

#### Scenario: Million-identity checkpoint fixture

- **WHEN** a continuation admits approximately one million exact identities
- **THEN** the report includes measured retained heap for the selected specialized keys and frames
- **AND** compares it to available service heap and concurrent-request capacity

### Requirement: Datahike storage topology is qualified independently from EACL traversal

Datahike root fusion, diff buffering, history, commit graph, node cache sizing, LMDB tiering, S3, DynamoDB, and JDBC settings MUST be measured as storage and cache choices and MUST NOT be represented as a substitute for reducing EACL's canonical first-page work. Exact-basis reconstruction SHOULD prefer commit-graph selection over temporal wrappers where the storage version degrades temporal seeks to full-attribute filtering. Byte-weighted observability of the storage node caches is a qualification deliverable.

#### Scenario: Tiered reader restart

- **WHEN** a reader restarts with a retained LMDB frontend
- **THEN** qualification verifies branch-head freshness, immutable-node fallback, and exact-basis behavior before claiming restart-warm performance

#### Scenario: History disabled

- **WHEN** both commit-graph and temporal history are disabled and no exact checkpoint exists
- **THEN** the topology does not advertise exact historical cursor continuation

## ADDED Requirements

### Requirement: Every public read runs inside one request execution context

Scalar and aggregate reads SHALL enter validation, consistency selection, execution-contract construction, snapshot ownership, schema-derived resolution, cursor proof construction, and publication once per public request through one context. Aggregation MUST NOT be implemented by invoking a public scalar operation per demand or candidate. The internal point kernel MAY evaluate distinct demands only when it accepts the existing context and cannot reacquire, renew, or republish request-wide state.

#### Scenario: Production branch inventory

- **WHEN** the source-derived public-to-engine branch inventory is generated
- **THEN** scalar and aggregate routes share one context constructor and no route contains a nested public entry
- **AND** the point kernel is context-parameterized rather than client-parameterized

### Requirement: Schema-derived work is bounded by distinct roots per generation

Plan seals, permission-definition reads, relation-definition reads, and validation-catalog construction SHALL be bounded by the number of distinct normalized permission roots not already present in the derived cache for the certified schema generation, never by the number of demands, candidates, or requests. On a snapshot without a certified generation the bound is distinct roots per request. Dependency and cursor proof derivations SHALL be shared when their complete semantic keys are equal. Backend scan commands and fetched values SHALL remain bounded by actual traversal, window, and sentinel needs.

#### Scenario: Ten-row page, one root

- **WHEN** a ten-row scan-route page requires eleven authorization decisions sharing one root
- **THEN** plan sealing and definition derivation are charged at most once for that root
- **AND** no request-wide setup counter grows from one to eleven

#### Scenario: Repeated scalar checks on Datalevin

- **WHEN** one hundred cache-bypass scalar checks for one root run against Datalevin snapshots of one schema generation
- **THEN** at most one plan seal and one set of definition reads occur in the process

### Requirement: Scalar fixed cost is gated

A profile of one cache-hit scalar check, one cache-bypass scalar check, one ten-row relationship page, and one acquisition SHALL be recorded before any fix. Thereafter, on the retained fixture: a completed-answer cache hit MUST cost less in p50 latency and allocation than a cache-bypass evaluation of the same direct-relation demand with a memoized plan; snapshot acquisition allocation MUST be at most one quarter of the pre-change value; per-call allocation for contract normalization, identity conversion, cache-key construction, result rendering, and cursor minting MUST stay at or below ceilings ratcheted from the accepted implementation; and every ceiling MUST be paired with a deterministic counter so that a regression is attributable.

#### Scenario: Cache hit slower than evaluation

- **WHEN** the paired series shows a cache hit at or above the cost of the memoized cache-bypass evaluation
- **THEN** the scalar fixed-cost gate fails naming the hit-path counters that exceed the bypass trace

#### Scenario: Acquisition reads physical schema

- **WHEN** acquisition instrumentation observes a physical schema read or comparison
- **THEN** the gate fails regardless of wall-clock time

### Requirement: Performance gains are attributed and release-gated honestly

Paired same-process series SHALL show at least a 30 % p50 latency reduction and a 40 % allocation reduction for the dense ten-row scan route versus the scalar loop, at least 40 % for both on the sparse fixture, and at least 90 % for both on the all-rejected fixture using the enumerate route versus the scalar loop. The release gate SHALL additionally require, against the pre-change baseline on a matching host class, at least a 70 % p50 and allocation reduction for the dense ten-row page and at least 90 % for the all-rejected page. Core and HTTP series MUST be reported separately; HTTP is reported with framework overhead isolated and is not ratio-gated; no portable sub-millisecond claim may be inferred from one host.

#### Scenario: Fixed-cost fix shrinks the fusion margin

- **WHEN** scalar fixed-cost fixes reduce the scalar loop so that the paired scan-route margin falls below its threshold
- **THEN** the attribution gate fails and the release gate is evaluated independently against the pre-change baseline
- **AND** neither gate is satisfied by the other's evidence

#### Scenario: Fast core but slow HTTP endpoint

- **WHEN** core series pass and the HTTP endpoint remains dominated by framework overhead
- **THEN** the HTTP series is reported with the isolated framework share
- **AND** the release gate is decided on the core series alone

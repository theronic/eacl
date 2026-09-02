# single-flight-coordination Specification

## Purpose

Record the replacement of shared cache-computation coordination with
independent request-owned computation and bounded nonblocking publication.

## Requirements

### Requirement: Cache misses retain independent request contracts

Every authorization cache miss SHALL compute under its own request deadline,
cancellation, counters, and failure contract. A request MUST NOT acquire
ownership of a shared computation, join another request's result, inherit its
failure, or wait for cache computation. Identical successful values MAY race a
bounded atomic publication attempt.

#### Scenario: Concurrent identical misses
- **WHEN** several requests miss the same lifecycle-qualified key concurrently
- **THEN** each may compute and return its own valid value without waiting for
  another request
- **AND** at most one compatible bounded publication wins

#### Scenario: One request fails
- **WHEN** one concurrent miss is cancelled, expires, or fails
- **THEN** no peer inherits that outcome and later requests remain able to
  compute and publish

### Requirement: Cache bounds govern retained state and publication attempts

Cache configuration SHALL bound retained tier weight, per-entry weight,
admission metadata, and local atomic publication attempts. It SHALL NOT claim
to bound application callback concurrency. Any optional service-edge overload
policy is a separate routed-execution control and MUST NOT be represented as
cache state or change cache-hit eligibility.

#### Scenario: Cache capacity is saturated
- **WHEN** a valid computed value cannot fit its cache tier
- **THEN** the request returns its own value without shared admission or wait

#### Scenario: Optional service admission is saturated
- **WHEN** the separate routed-execution policy rejects new semantic execution
- **THEN** it returns its documented typed outcome at that boundary
- **AND** cache metrics do not report a cache wait, hit, or publication

### Requirement: Metrics describe completed state and bounded attempts

Hit counters SHALL count only values read from completed compatible entries.
Metrics MAY report misses, bypasses, publication attempts, wins, races,
rejections, contention, eviction, and detached publications. They MUST NOT
expose nonexistent computation-owner, join, slot-wait, or waiter metrics.

#### Scenario: Miss loses publication race
- **WHEN** a request misses, computes, and another request publishes first
- **THEN** the initiating request remains a miss and the bounded publication
  race is reported separately from completed hits

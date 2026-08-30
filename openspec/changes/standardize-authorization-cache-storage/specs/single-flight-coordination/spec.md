# single-flight-coordination Specification

## MODIFIED Requirements

### Requirement: Cache bounds govern retained state and publication attempts

Cache configuration SHALL bound each shared tier by positive standard LRU entry
count, and each completed miss MAY make one local atomic absent-key insertion.
Atom contention may retry the pure library state transformation but MUST NOT
rerun application computation or validation.
The cache contract SHALL NOT claim to bound retained bytes, application
callback concurrency, semantic work, or service admission. Artifact-specific
semantic bounds and the 1,000-result completed-page retention guard remain
separate from LRU capacity. Any optional service-edge overload policy is a
separate routed-execution control and MUST NOT become cache state or change
cache-hit eligibility.

#### Scenario: Cache capacity is saturated

- **WHEN** a valid computed value is offered to a full cache tier
- **THEN** standard LRU insertion may evict the least recently used resident mapping
- **AND** the request returns its own value without shared wait

#### Scenario: Optional service admission is saturated

- **WHEN** the separate routed-execution policy rejects new semantic execution
- **THEN** it returns its documented typed outcome at that boundary
- **AND** cache metrics do not report a cache wait, hit, or publication

### Requirement: Metrics describe completed state and bounded attempts

Hit counters SHALL count only values read from completed compatible entries.
Metrics MAY report misses, bypasses, publication calls, insertions, same-key
races, rejections, eviction when exposed, occupancy, and detached publication.
They MUST NOT expose nonexistent computation-owner, join, slot-wait, waiter,
recency, repeat-admission, logical-byte, or retry metrics.

#### Scenario: Miss loses publication race

- **WHEN** a request misses, computes, and finds that another request inserted the same key
- **THEN** the initiating request remains a miss and the same-key race is reported separately from completed hits

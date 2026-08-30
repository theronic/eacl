# answer-cache-bounding Specification

## ADDED Requirements

### Requirement: Count-bounded completed answers

Completed-answer storage SHALL enforce a validated positive standard LRU entry
capacity. It SHALL admit only complete immutable validated answers, and SHALL
skip shared admission for a completed page containing more than 1,000 result
items. Capacity and eligibility affect retention only: oversized or evicted
answers remain correct and recomputable.

#### Scenario: Page-heavy workload reaches capacity

- **WHEN** distinct eligible lookup pages fill a completed-answer tier
- **THEN** resident entry count never exceeds configured capacity
- **AND** further insertions evict the least recently used resident entries

#### Scenario: Page exceeds retention eligibility

- **WHEN** a valid completed page contains 1,001 or more result items
- **THEN** the page is returned unchanged and is not admitted

## MODIFIED Requirements

### Requirement: One store implementation for answers and subproblems

Completed answers SHALL use the same private standard LRU storage boundary as
exact denotation subproblems, with separate count-bounded tiers for workload
isolation. Exact and managed answer eligibility SHALL be represented by flat
opaque composite keys, not nested generation stores; denotations SHALL be
exact-only. Physical projection chunks and direct Boolean probes SHALL remain
request work rather than shared cache artifacts. Miss computation remains
request-owned; atomic state transforms may update LRU recency or insert an
already completed validated value but may not execute application callbacks.

#### Scenario: Unified layering

- **WHEN** a managed completed answer is eligible after an exact-key miss for the same semantic request
- **THEN** the managed value is found through the same explicit keyed storage operation used by exact answers and exact denotations
- **AND** duplicate generation, weight, recency, and admission implementations do not exist

#### Scenario: Entry predates the compatibility rollout

- **WHEN** an old completed entry lacks the current cache-value, key, or engine ABI
- **THEN** it misses and recomputes without being externalized or freshly signed

### Requirement: Recency-honest eviction and admission

Completed-answer eviction SHALL use the standard cache library's least-recently
used policy, not hash iteration or a separate EACL access queue. A successful
resident lookup SHALL atomically install the library's hit transition so a
frequently used key is retained ahead of less recently used keys. EACL SHALL
not add repeat-admission sightings, recency sidecars, stamped queues,
tombstones, or compaction around the library cache.

#### Scenario: Hot key survives churn

- **WHEN** one key is accessed repeatedly while a stream of distinct cold keys fills the tier
- **THEN** the hot key remains resident and less recently used cold keys evict

#### Scenario: Managed candidate is causally ineligible

- **WHEN** an older request peeks a future managed answer and rejects it by the request-relative revision check
- **THEN** that attempted lookup does not apply an LRU hit or keep the unusable mapping hot

#### Scenario: Repeat admission at scale

- **WHEN** a client supplies the removed repeat-admission option at any keyspace size
- **THEN** construction rejects it instead of combining a second admission policy with standard LRU

## REMOVED Requirements

### Requirement: Byte-bounded completed answers

**Reason**: The old logical weight estimate was not retained heap bytes and its
custom accounting substantially enlarged the cache state machine. This change
deliberately adopts entry-count capacity and a 1,000-result page admission
guard; true byte-level retention may be added later from measured heap data.

**Migration**: Replace completed-answer weight, per-entry byte settings, and the
nested answer-capacity override with the flat positive `:max-entries` capacity.
Pages above 1,000 results compute
normally but are not retained.

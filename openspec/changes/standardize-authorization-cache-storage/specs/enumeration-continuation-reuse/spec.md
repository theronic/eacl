# enumeration-continuation-reuse Specification

## MODIFIED Requirements

### Requirement: Cache miss and eviction preserve correctness

A missing, expired, invalidated, or LRU-evicted continuation SHALL fall back
to authenticated deterministic replay and MUST return the same ordered page as
a continuation hit. Continuation storage SHALL use the shared count-bounded
LRU boundary and be observable through hit, miss, occupancy, and available
eviction telemetry. Snapshot, lineage, authentication, lifetime, and semantic
validation MUST occur outside storage and MUST NOT require tombstones, family
indexes, custom recency queues, or compaction machinery.

The continuation storage key SHALL retain its full persistent version, backend,
source lineage, adapter fingerprint, identity contract, operation, and
canonical-query scope. A compact digest alone MUST NOT replace that
collision-checking identity.

For one continuation identity, concurrent publication SHALL retain
nonregressing progress. The semantic layer MUST compare immutable checkpoint
progress outside the cache atom and MAY retry a callback-free expected-value
replacement when another writer changed the mapping. The atomic replacement
MUST use only standard cache membership, lookup, eviction, and miss
transformations and MUST NOT invoke the progress comparator or replay work. A
publication comparison MUST peek without applying a hit. Only actual retrieval
and successful insertion/replacement may record recency; stale, losing, or
failed publication MUST NOT refresh the retained key.

#### Scenario: Bounded cache evicts an old frontier

- **WHEN** LRU capacity or lifetime eligibility evicts a valid but old continuation
- **THEN** requesting its next page records a miss, replays safely, and returns the same page as an unexpired continuation

#### Scenario: Snapshot is invalidated

- **WHEN** the public snapshot or rebasing policy makes a saved continuation ineligible
- **THEN** EACL follows the existing snapshot contract and never resumes state from the incompatible snapshot

#### Scenario: Older and newer checkpoints race

- **WHEN** concurrent requests offer different progress for the same continuation identity
- **THEN** an older checkpoint cannot replace already observed newer progress
- **AND** a changed expected value causes an outside-atom re-read and comparison rather than an application callback inside the CAS transform
- **AND** the stale or losing offer does not refresh LRU recency

#### Scenario: Checkpoint exceeds the semantic admission-count cap

- **WHEN** either the client continuation context or standalone checkpoint API offers a checkpoint above the retained admitted-identity bound
- **THEN** publication is skipped without probing, replacing, or mutating LRU telemetry
- **AND** a checkpoint at the bound remains eligible for ordinary retention

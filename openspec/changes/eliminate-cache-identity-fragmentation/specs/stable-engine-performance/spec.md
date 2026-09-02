## MODIFIED Requirements

### Requirement: Reducer bookkeeping has bounded per-transition cost

Releasing one value from a realized physical chunk SHALL advance an index or equivalent constant-state cursor without creating a suffix collection per value. Retained-value and retained-buffer maxima SHALL be maintained incrementally. Recency bookkeeping SHALL use bounded indexed or generation-stamped metadata whose size is a documented function of live capacity, not touch count. Continuation and tombstone key lookup SHALL remain direct; touch, removal, and publication-order bookkeeping SHALL avoid filtering or rebuilding the whole bounded order vector per event when that mechanism is retained, using a direct index or equivalent amortized-constant-time update. Page-navigation lookup by exact request, start boundary, and end boundary SHALL remain direct; ordinary publication, replacement, alias maintenance, and eviction MUST NOT scan or rebuild collections proportional to resident page capacity, and any periodic stale-metadata compaction MUST preserve an amortized constant bound with a documented capacity-derived space ceiling. Live and tombstone metadata SHALL keep independent enforced capacity bounds. Zero- and one-successor fast paths MAY be used only when they preserve staged all-or-error limit decisions, canonical scheduling, admission identity, and counters.

#### Scenario: Long physical chunk releases one value at a time
- **WHEN** a long realized chunk is consumed through single-value semantic transitions
- **THEN** each release advances bounded cursor state without allocating a new suffix view or rescanning all retained buffers

#### Scenario: One sidecar is touched repeatedly
- **WHEN** a retained sidecar is touched many times while live capacity remains fixed
- **THEN** recency metadata remains bounded by its documented capacity-derived ceiling

#### Scenario: Continuations churn at fixed live capacity
- **WHEN** continuations are repeatedly published, resumed, removed, and tombstoned while live capacity remains fixed
- **THEN** key lookup remains direct and touch/removal avoids a whole-capacity vector filter or rebuild per event
- **AND** live and tombstone metadata remain within their independent configured bounds

#### Scenario: Page navigation churns at fixed capacity
- **WHEN** pages are repeatedly published, replaced, aliased in both navigation directions, and evicted while live capacity remains fixed
- **THEN** exact and boundary lookup remain direct
- **AND** ordinary publication and eviction perform a capacity-independent amortized number of indexed bookkeeping operations
- **AND** all page entries, boundary indexes, current-generation metadata, and queued stale metadata remain within documented capacity-derived ceilings

#### Scenario: Transition has zero or one certified successor
- **WHEN** a transition has zero or one successor and the plan certificate makes duplicate-in-batch staging impossible
- **THEN** the specialized path produces the same stack, admission, limit, and counter outcome as the general staged path

#### Scenario: Successor uniqueness is not certified
- **WHEN** a transition lacks the required zero/one-successor certificate
- **THEN** EACL uses the general staged admission path

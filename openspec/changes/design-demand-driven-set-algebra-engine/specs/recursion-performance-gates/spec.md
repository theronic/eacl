## ADDED Requirements

### Requirement: Recursive operator fixtures exercise real multi-premise work
The recursion gate suite SHALL include positive self and mutual conjunction, seeded and unseeded cycles, star and chain graphs, duplicate derivations, non-anchor-before-anchor arrival, typed-ID collision, supported arrows, and multiple strict exclusion strata. A fixture whose operator counters show no multi-premise or stratum work MUST fail its self-check.

#### Scenario: Late anchor completion
- **WHEN** a recursive conjunction's anchor arrives after other premises
- **THEN** the fixture derives the exact result and records anchor-state initialization from prior facts

### Requirement: Join-state retention is anchor-bounded
Qualification SHALL separately measure admitted facts, entities satisfying each premise, entities satisfying the anchor, live join states, join slots, derived parents, transitions, queue depth, checkpoint weight, and allocation. Live parent join states MUST NOT exceed distinct typed entities with an admitted anchor fact.

#### Scenario: Sparse anchor and broad non-anchor
- **WHEN** non-anchor premises cover many entities and the anchor covers few
- **THEN** live parent join-state cardinality is bounded by the sparse anchor rather than the union of premise entities

### Requirement: Recursive operator failure occurs before unbounded retention
Configured fact, transition, queue, join-state, slot, portable-byte, checkpoint, deadline, and cancellation limits SHALL be checked before the corresponding state is irreversibly admitted or published. Exceeding a limit SHALL return the established typed resource failure and no partial exact answer.

#### Scenario: Broad recursive conjunction exceeds state limit
- **WHEN** the next anchor-gated allocation would exceed the configured operator-state budget
- **THEN** the operation fails before allocating that state or publishing a partial page/count as complete

### Requirement: Recursive union-only traces remain unchanged
Union-only recursive programs SHALL retain their existing generated scheduling order, transition sequence, counters, checkpoint identity, and performance envelope.

#### Scenario: Existing recursive corpus
- **WHEN** the pre-operator recursive corpus runs after the upgrade
- **THEN** its deterministic traces and output remain unchanged


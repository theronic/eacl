## ADDED Requirements

### Requirement: Adjacent pages resume private traversal state

On the first visit to an adjacent forward or reverse page, Datomic, DataScript, and Datahike MUST resume matching authenticated client-private continuation state when it is available. Resumed work SHALL begin at the saved traversal frontier rather than replaying all preceding pages.

#### Scenario: DataScript advances through server pages

- **WHEN** one client requests successive DataScript pages for an unchanged server query and snapshot
- **THEN** every page after the first records a continuation hit and does not re-consume prior page traversal work

#### Scenario: Datahike advances through reverse pages

- **WHEN** one client requests successive reverse Datahike pages for an unchanged query and snapshot
- **THEN** every page after the first resumes the saved reverse frontier and returns the same results as deterministic replay

#### Scenario: Datomic remains the reference behavior

- **WHEN** the same page sequence is evaluated on Datomic, DataScript, and Datahike
- **THEN** continuation hit semantics and page results are equivalent across the three backends

### Requirement: Continuation state remains authenticated and client-private

Continuation entries MUST be owned by the requesting client context and keyed by every value that can change enumeration semantics, including backend/source identity, normalized schema or routing-certificate identity, operation and direction, subject, resource type, permission, constraints, snapshot, and authenticated query lineage. Internal traversal state MUST NOT be serialized into public cursors or stored in provider-owned global state.

#### Scenario: Another client presents a valid cursor

- **WHEN** a different client context presents an otherwise valid cursor without the originating private continuation
- **THEN** it cannot read the originating client's internal state and obtains the correct page through deterministic replay

#### Scenario: Query semantics change

- **WHEN** subject, permission, constraints, direction, schema identity, or snapshot differs from a cached continuation key
- **THEN** EACL does not resume that continuation

#### Scenario: Public cursor is inspected

- **WHEN** an authenticated cursor is decoded according to the public cursor contract
- **THEN** it contains no traversal stack, visited set, merge frontier, backend handle, or other private continuation payload

### Requirement: Cache miss and eviction preserve correctness

A missing, expired, invalidated, or evicted continuation SHALL fall back to authenticated deterministic replay and MUST return the same ordered page as a continuation hit. Continuation storage SHALL be bounded and observable through hit, miss, eviction, and occupancy telemetry.

#### Scenario: Bounded cache evicts an old frontier

- **WHEN** cache capacity or lifetime policy evicts a valid but old continuation
- **THEN** requesting its next page records a miss, replays safely, and returns the same page as an unexpired continuation

#### Scenario: Snapshot is invalidated

- **WHEN** the public snapshot or rebasing policy makes a saved continuation ineligible
- **THEN** EACL follows the existing snapshot contract and never resumes state from the incompatible snapshot

### Requirement: Resumed page work is independent of page ordinal

For a continuation hit, logical traversal work MUST be bounded by the requested page window, bounded lookahead, and newly encountered duplicate/path work. It SHALL NOT include a replay term proportional to the number of preceding pages.

#### Scenario: Ten successive pages at ten-thousand scale

- **WHEN** a client traverses ten successive 20-resource pages in the 10,000-server Explorer dataset
- **THEN** per-page continuation work does not grow with the page number and no page reconsumes the complete prefix

## MODIFIED Requirements

### Requirement: Adjacent pages resume private traversal state
On the first visit to an adjacent forward or reverse page, an adapter advertising ordered generations MUST resume matching client-private checkpoint state when it is available, after the public cursor has been accepted on the selected basis (equal frame in one lineage, or exact fallback by identity) and the boundary ordinal and identity match. Resumed work SHALL begin at the saved frontier rather than replaying preceding pages, on the same basis or on any basis of the same lineage with an equal frame.

#### Scenario: DataScript advances through server pages
- **WHEN** one client requests successive DataScript pages for an unchanged query across unrelated writes
- **THEN** every page after the first records a checkpoint hit and does not re-consume prior page traversal work

#### Scenario: Datahike advances through reverse pages
- **WHEN** one client requests successive reverse Datahike pages for an unchanged query across unrelated writes
- **THEN** every page after the first resumes the saved reverse frontier and returns the same results as deterministic replay

#### Scenario: Retained older basis
- **WHEN** a retained snapshot at an older basis of the same lineage requests the next page with an equal frame
- **THEN** the checkpoint published from the newer basis is resumed and the page equals replay at the older basis

#### Scenario: Backends preserve reference behaviour
- **WHEN** the same page sequence is evaluated on Datomic, DataScript, Datahike, and Datalevin
- **THEN** checkpoint-hit semantics and page results are equivalent

### Requirement: Continuation state remains authenticated and client-private
Checkpoint entries MUST be owned by the requesting client context and keyed by lineage, frame, sealed plan fingerprint, traversal direction, subject type, anchor, page size, and the authenticated boundary ordinal and identity. They SHALL NOT be keyed by native revision. Internal traversal state MUST NOT be serialized into public cursors or stored in provider-owned global state. Visited public pages SHALL remain keyed by exact basis because rendered public identity is outside the frame.

#### Scenario: Another client presents a valid cursor
- **WHEN** a different client context presents an otherwise valid cursor without the originating private checkpoint
- **THEN** it obtains the correct page through deterministic replay

#### Scenario: Revision changes but frame is equal
- **WHEN** the selected native revision differs from the checkpoint's but lineage and frame are equal
- **THEN** native-revision inequality alone does not prevent resumption

#### Scenario: Query or plan semantics change
- **WHEN** anchor, query, direction, plan fingerprint, page size, lineage, or frame differs from the checkpoint key
- **THEN** EACL does not resume that checkpoint

#### Scenario: Public cursor is inspected
- **WHEN** an authenticated cursor is decoded according to the public cursor contract
- **THEN** it contains no traversal stack, admitted set, lookahead, backend handle, or other private payload

### Requirement: Cache miss and eviction preserve correctness
A missing, evicted, over-weight, plan-mismatched, or boundary-mismatched checkpoint SHALL fall back to deterministic replay from the authenticated public boundary and MUST return the same ordered page as a hit. Storage SHALL be bounded and observable through hit, miss-reason, publication, replacement, and occupancy telemetry. A checkpoint failure MUST NOT restart pagination, relax cursor validation, or change a typed cursor outcome; replay exhaustion keeps its resource classification.

#### Scenario: Bounded cache evicts an old frontier
- **WHEN** capacity evicts a valid but old checkpoint
- **THEN** the next page records `:evicted`, replays from the boundary, and returns the same page as an unexpired checkpoint

#### Scenario: Cursor is not accepted
- **WHEN** the public cursor is invalid, stale, of another lineage, or has a changed frame
- **THEN** no checkpoint store access occurs and the public cursor outcome is returned

#### Scenario: Population disabled
- **WHEN** a page is served with `:populate-cache? false`
- **THEN** no checkpoint is published and the next page replays correctly

### Requirement: Resumed page work is independent of page ordinal
For a checkpoint hit, logical traversal work MUST be bounded by the requested page window, the one-element lookahead, and newly encountered duplicate or path work, and SHALL NOT include a replay term proportional to preceding pages. Accumulated admissions, transitions, commands, fetched values, discovered results, and maximum stack SHALL carry across resume so configured ceilings are enforced cumulatively, exactly as replay enforces them.

#### Scenario: Ten successive pages at ten-thousand scale
- **WHEN** a client traverses ten successive 20-resource pages in the 10,000-server Explorer dataset with unrelated writes between pages
- **THEN** per-page continuation work does not grow with the page number and no page reconsumes the complete prefix

#### Scenario: Resumption approaches a ceiling
- **WHEN** a checkpoint records accumulated work near a configured limit
- **THEN** resumed evaluation enforces the remaining allowance exactly as deterministic replay would

## ADDED Requirements

### Requirement: Checkpoint state is closed history-free data
Checkpoint state SHALL consist exactly of the reducer's history-free semantic keys — stack, admitted set, counters, discovered count, and maximum stack — plus the undelivered lookahead of internal ids and the boundary. It MUST NOT contain functions, database values, readers, lazy sequences, delivered results, or configuration. Its independence from the basis SHALL be asserted by a structural test on both runtimes.

#### Scenario: State is inspected
- **WHEN** the structural test examines a published checkpoint
- **THEN** every value is closed data and no basis-bound object is present

#### Scenario: Runtime parity
- **WHEN** the same fixture publishes and resumes a checkpoint on CLJ and CLJS
- **THEN** both produce the same pages and the same next boundary

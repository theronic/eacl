## MODIFIED Requirements

### Requirement: Adjacent pages resume private traversal state

On the first visit to an adjacent forward or reverse page, Datomic, DataScript, and Datahike MUST resume matching authenticated client-private continuation state when it is available. Resumed work SHALL begin at the saved traversal frontier rather than replaying all preceding pages. A saved frontier belongs to a page series (the walk and the page size that produced it) at its latest delivered boundary. Because the history-free reducer state at a delivered boundary does not depend on how earlier pages were cut, a continuation that leaves a retained page segment SHALL resume the frontier of the series that produced that segment whatever page size the continuation requests; a continuation without a retained segment resumes its own series' frontier.

#### Scenario: DataScript advances through server pages

- **WHEN** one client requests successive DataScript pages for an unchanged server query and snapshot
- **THEN** every page after the first records a continuation hit and does not re-consume prior page traversal work

#### Scenario: Datahike advances through reverse pages

- **WHEN** one client requests successive reverse Datahike pages for an unchanged query and snapshot
- **THEN** every page after the first resumes the saved reverse frontier and returns the same results as deterministic replay

#### Scenario: Datomic remains the reference behavior

- **WHEN** the same page sequence is evaluated on Datomic, DataScript, and Datahike
- **THEN** continuation hit semantics and page results are equivalent across the three backends

#### Scenario: Page size changes mid-walk

- **WHEN** a client serves a `:first 20` page of a recursive plan, the page's segment is retained, and the client continues from its end cursor with `:first 7`
- **THEN** the continuation records a continuation hit at the twentieth result and returns the same seven results as deterministic replay

## ADDED Requirements

### Requirement: Every public authorization operation uses one expression semantics
The shared v8 engine SHALL evaluate expression-aware point checks, detailed checks, forward and reverse lookups, filters, bounded and exact counts, and completed denotations against the same selected immutable snapshot and denotation. Built-in backends and CLJ/CLJS runtimes MUST return equivalent values and typed failures.

#### Scenario: Nested operator operation matrix
- **WHEN** a valid nested operator fixture is evaluated through every operation on every built-in backend
- **THEN** all values agree with the independent expression oracle

### Requirement: Point evaluation short-circuits only decisive completed values
Union MAY stop after a completed true child. Intersection SHALL stop after a completed false child and SHALL require every child true before granting. Exclusion SHALL grant only after completed left true and completed right false. A selected branch error or incomplete value MUST propagate unless a sound result had already been decided before that branch was demanded.

#### Scenario: Intersection early denial
- **WHEN** the first sealed child evaluation completes false
- **THEN** the point check denies without demanding later children while retaining their static dependencies in the proof

#### Scenario: Exclusion cannot infer absence
- **WHEN** the right operand reaches a timeout, cancellation, resource limit, or unfinished recursive state
- **THEN** exclusion returns the typed failure rather than granting

### Requirement: Recursive intersections use anchor-gated join state
For every positive recursive intersection, the engine SHALL select one deterministic anchor premise. It SHALL retain complete child fact sets, allocate per-entity parent join state only after the anchor fact exists, initialize late anchor state from already admitted child facts, and derive the parent exactly once after all distinct premises hold.

#### Scenario: Non-anchor fact arrives first
- **WHEN** a non-anchor premise for an entity is admitted before its anchor premise
- **THEN** later anchor admission observes that fact and can complete the intersection

#### Scenario: Entity never satisfies anchor
- **WHEN** an entity satisfies non-anchor premises but never the anchor
- **THEN** no parent join-state entry is retained for that entity

### Requirement: Union-only production behavior remains isolated
When a reachable permission graph contains no intersection or exclusion, sealing and execution SHALL use the existing union-only plan domain, evaluator, order ABI, counters, and cache keys without entering operator state.

#### Scenario: Union-only workload after upgrade
- **WHEN** the existing union-only conformance corpus runs on an expression-capable build
- **THEN** deterministic values, order, cursor payload interpretation, and work counters remain unchanged

### Requirement: Structural and relationship metrics are cache-only
The shared engine SHALL derive expression/DAG dimensions from canonical payloads and SHALL retain any completed structural result only in a generation-scoped cache. Mutable relationship cardinality, selectivity, and physical-cost observations SHALL be retained only in high-watermark-scoped cache entries and SHALL be optional to authorization execution.

#### Scenario: Application relationships change after schema write
- **WHEN** relationship data advances while the permission expression remains unchanged
- **THEN** structural expression metrics remain valid for the schema generation while relationship observations from the old high-watermark are not reused as current

### Requirement: Structural admission policy is client-owned
The shared engine SHALL accept a checked expression-limit profile at client construction, retain it immutably for that client's lifetime, and apply it to schema decoding, normalization, sealing, and schema writes. The profile SHALL have calibrated defaults, SHALL remain within hard portable codec and allocation ceilings, and SHALL NOT be loaded from or published to backend storage.

#### Scenario: Client starts with a custom checked profile
- **WHEN** a library consumer constructs one client with tighter aggregate expression limits
- **THEN** only that client applies the tighter admission boundary and no schema or metric transaction is emitted

### Requirement: Metric refresh is explicit and bounded
The public cache-management boundary SHALL permit forced structural recomputation and relationship-observation refresh. Structural refresh SHALL read only bounded permission payloads. Relationship refresh SHALL be non-exhaustive unless the caller explicitly selects exact mode and supplies ordinary exhaustive work limits.

#### Scenario: Forced bounded refresh on a large remote relation
- **WHEN** a caller requests the default relationship-stat refresh
- **THEN** EACL clears or replaces observations using bounded demanded reads and does not scan the whole relation merely to count it

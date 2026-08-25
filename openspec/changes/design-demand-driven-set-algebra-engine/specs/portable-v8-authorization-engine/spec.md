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


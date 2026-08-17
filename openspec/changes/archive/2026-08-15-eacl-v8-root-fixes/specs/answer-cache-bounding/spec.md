# answer-cache-bounding

## ADDED Requirements

### Requirement: Byte-bounded completed answers
Completed-answer caching SHALL enforce a weight budget approximating retained bytes, with a per-entry ceiling. Oversized values SHALL be rejected with a dedicated metric rather than admitted unbounded. Entry-count limits alone SHALL NOT be the only bound.

#### Scenario: Page-heavy workload stays within budget
- **WHEN** a workload caches maximum-size lookup pages until the completed-answer tier is saturated
- **THEN** retained weight never exceeds the configured budget, oversized rejections are counted, and no configuration exists in which completed answers are byte-unbounded

### Requirement: Recency-honest eviction and admission
Completed-answer eviction SHALL be recency-based (least-recently-used or an equivalent documented policy), not hash-iteration-order. If repeat-based admission is offered, its sighting state SHALL NOT converge to a fixed key subset independent of access frequency.

#### Scenario: Hot key survives churn
- **WHEN** one key is accessed repeatedly while a stream of distinct cold keys fills the tier
- **THEN** the hot key remains resident and the cold keys evict; under the prior arbitrary-eviction behavior this scenario fails

#### Scenario: Repeat admission at scale
- **WHEN** repeat-based admission is enabled and the keyspace exceeds the tier capacity by 50×
- **THEN** a key seen twice in close succession is admitted with high probability (not the frozen-set behavior measured at 2.3%)

### Requirement: One store implementation for answers and subproblems
Completed answers SHALL be a tier of the weighted subproblem store, inheriting its exact/managed generation layering, single-flight coordination, weight accounting, and metrics — one implementation of generation logic, not two.

#### Scenario: Unified layering
- **WHEN** a managed completed answer is valid for the current stamps and an exact-generation miss occurs for the same semantic key
- **THEN** the layered resolution serves the managed value through the same code path the projection and denotation tiers use, and duplicate generation-management code (`bounded-assoc`, standalone admission maps, separate exact/managed branches) no longer exists

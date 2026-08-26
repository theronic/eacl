## Purpose

Defines observable remote-storage efficiency and measurement requirements for operator execution, with explicit Datahike node-cache and S3 behavior.

## ADDED Requirements

### Requirement: Remote work dimensions are reported separately
Operator qualification SHALL separately report logical candidates, completed scalar-equivalent predicates, physical probe groups, adapter commands, adapter-fetched values, Datahike index-node accesses, Datahike node-cache misses, direct metadata operations, physical S3 GETs, retained logical state, allocation, and latency. One measure MUST NOT be presented as another without a checked refinement.

#### Scenario: Datahike cache miss accounting
- **WHEN** a Datahike index node is absent from its in-process store cache and restored through Konserve S3
- **THEN** qualification counts the resulting object GET as one node-cache miss and separately records any non-index branch or metadata GET

### Requirement: A bounded page never opens all operands by default
A cold bounded operator page SHALL open only its sealed candidate generator and the exact predicate subproblems demanded for examined candidates. It MUST NOT read a prefix from every operand for plan selection, collect a complete operand, enumerate a global object catalog, or open streams proportional to data fan-out merely to choose a plan.

#### Scenario: High-fanout first page
- **WHEN** an operator permission has a high-fanout unused operand and the selected generator supplies the page early
- **THEN** the unused operand is touched only for exact membership of demanded candidates and is not enumerated from its root

### Requirement: Datahike batching is locality-aware
For one Datahike immutable basis and normalized physical descriptor, dense candidate spans SHALL be eligible for endpoint-local ordered prefix merge, while sparse spans SHALL be eligible for exact or galloping seeks. The adapter MUST NOT implement batching by unconditionally scanning the complete range between the first and last candidate, and MUST NOT select a fresh database basis per candidate or subgroup.

#### Scenario: Dense adjacent batch
- **WHEN** a candidate batch occupies a compact ordered tuple span
- **THEN** the adapter may realize that bounded span once and align all membership decisions from it

#### Scenario: Sparse wide batch
- **WHEN** a small candidate batch spans a large relationship range
- **THEN** the adapter avoids linearly realizing every intervening tuple

### Requirement: Cold, warm, and exhaustive S3 gates are independent
The checked-in Datahike/MinIO qualification SHALL define fixed reproducible fixtures and accepted ceilings for cold first page, warm repeat, adjacent continuation, candidate-window progress, and exact count. A working set demonstrated to fit the configured node cache SHALL produce zero incremental index-node GETs on an immediate warm repeat; direct basis-selection metadata reads SHALL be reported separately.

#### Scenario: Warm repeat fits cache
- **WHEN** the complete index-node working set of an operator page remains resident and no relevant write or explicit expiry occurs
- **THEN** an immediate repeat performs zero incremental index-node S3 GETs

#### Scenario: Exhaustive count is expensive
- **WHEN** an exact count must walk the full candidate cover
- **THEN** its GET count cannot be used to fail the bounded first-page gate and is evaluated against its own ceiling

### Requirement: Cache-enabled remote execution is elide-only
Eligible exact scan-response, point-decision, and completed-answer cache hits MAY remove matching physical work. Cache lookup or population MUST NOT widen a scan, prefetch an undemanded operand, select another generator, advance a cursor, or change the cache-disabled result, error, or logical stopping boundary.

#### Scenario: Cold cache parity
- **WHEN** cache-enabled and cache-disabled requests begin without compatible entries
- **THEN** they demand the same candidates and semantic subproblems even if physical grouping differs by a sealed certified rule

### Requirement: Organic statistics never widen remote demand
Relationship observations SHALL be updated from values, probes, batches, exhaustion, and physical telemetry produced by already demanded work. An ordinary statistics miss or bounded refresh MUST NOT trigger a full relation count, widen a tuple range, enumerate every operand, or open an otherwise-undemanded index stream. Exact refresh SHALL require an explicit exhaustive mode and SHALL be reported and limited as exact-count work.

#### Scenario: Cold observation cache on S3-backed Datahike
- **WHEN** a bounded page has no relationship statistics
- **THEN** EACL executes the deterministic bounded plan and records only the work it naturally performs, without extra S3 GETs for statistics collection

### Requirement: Adapter I/O statistics are optional cost telemetry
Adapters MAY report normalized operation I/O statistics. Datomic I/O stats SHALL be interpreted only as cache/storage-tier work for the measured operation, not as an exact relationship cardinality or stable selectivity estimate. Adapters with no native telemetry SHALL remain fully conformant using EACL logical and physical counters.

#### Scenario: Datomic cache state changes I/O stats
- **WHEN** identical logical reads report different Datomic I/O because cache residency differs
- **THEN** both remain observations for physical cost and neither changes the authorization denotation or public plan identity

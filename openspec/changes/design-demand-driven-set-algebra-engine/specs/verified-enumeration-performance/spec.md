## ADDED Requirements

### Requirement: Union-only performance remains a release gate
Matched-host union-only benchmarks SHALL retain identical deterministic work counters and SHALL not regress median latency or allocation by more than five percent for point checks, first and adjacent pages, reverse lookups, bounded counts, exact counts, and recursive fixtures. Any accepted exception MUST identify measurement noise or an independently approved baseline change rather than hiding it in operator results.

#### Scenario: Operator support slows union page
- **WHEN** a union-only page exceeds the frozen five-percent latency or allocation envelope
- **THEN** operator release is blocked even if operator benchmarks improve

### Requirement: Operator strategies are tested under adversarial selectivity
Reproducible benchmarks SHALL cover high and low overlap intersections, dense and sparse exclusion, empty and late-result operands, skewed generator cardinality, duplicate-heavy unions, direct and nested arrows, increasing operand count, both directions, page sizes, candidate-window progress, cold and warm caches, and exact count.

#### Scenario: Poor general generator
- **WHEN** a sealed compound generator has low acceptance
- **THEN** observed candidates and probes remain within configured bounds and continuation is correct, while the result is not presented as exhausted

### Requirement: Direct specializations beat or reject pathological generic work
For fixtures that certify direct ordered specialization, qualification SHALL compare eager collection, linear merge, scalar generator/probe, adaptive vector predicate, and seekable specialization using dimensionally equal counters. The selected specialization MUST avoid complete-operand collection and MUST NOT perform more logical candidate work than its proved bound.

#### Scenario: Dense versus sparse batch
- **WHEN** dense and sparse Datahike candidate vectors are benchmarked
- **THEN** the density-aware selector uses bounded prefix work for the dense case and avoids the wide range scan for the sparse case

### Requirement: Bounded and exhaustive benchmarks are not blended
First-page, adjacent-page, candidate-window, replay, bounded-count, complete-enumeration, and exact-count measurements SHALL be reported separately. A fast page MUST NOT conceal an exhaustive regression, and an exhaustive GET count MUST NOT be attributed to lazy page execution.

#### Scenario: Exact count reads thousands of nodes
- **WHEN** an exact count exhausts a large Datahike cover while first-page work stays bounded
- **THEN** both results are reported against separate accepted ceilings


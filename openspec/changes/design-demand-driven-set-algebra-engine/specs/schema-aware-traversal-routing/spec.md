## ADDED Requirements

### Requirement: Routing certificates cover signed expression dependencies
The generated routing certificate SHALL cover every reachable expression node, positive and negative edge, positive strongly connected component, exclusion stratum, candidate generator, and recursive anchor. Missing, stale, malformed, cyclic-negative, or fingerprint-incompatible evidence MUST fail closed.

#### Scenario: Acyclic operator root
- **WHEN** an intersection or exclusion root reaches no positive recursive component
- **THEN** routing selects the acyclic witness-carrying evaluator

#### Scenario: Positive recursive operator root
- **WHEN** a root reaches a positive recursive intersection component
- **THEN** routing selects the generated multi-premise fixed-point evaluator

### Requirement: Lower-stratum questions cannot route through incomplete upper state
An exclusion-right dependency SHALL be evaluated only through a certified lower-stratum route. Upper-stratum provisional state, active recursion markers, candidate pages, and bounded prefixes MUST NOT be presented as the lower-stratum answer.

#### Scenario: Lower recursive component exhausts
- **WHEN** a lower positive-recursive component completes for an exclusion candidate
- **THEN** its exact Boolean may be consumed by the upper stratum

### Requirement: Routing preserves one denotation and order contract
Changing among certified trace-equivalent physical leaf kernels MUST NOT change set membership, duplicate suppression, public generator order, page composition, count results, progress boundaries, or typed failures.

#### Scenario: Dense leaf kernel changes
- **WHEN** a direct leaf batch qualifies for prefix merge instead of exact seeks
- **THEN** the public operator sequence and cursor boundary remain identical


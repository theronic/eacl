## ADDED Requirements

### Requirement: Operator planning performs no discarded semantic reads
A bounded operator request SHALL use its sealed candidate generator and certified leaf-kernel rules without reading operand values solely to choose a generator or public order. Any backend value read by execution SHALL belong to the selected generator, an exact predicate demanded for an examined candidate, a certified witness/duplicate check, or the caller's requested exhaustive operation.

#### Scenario: No selectivity pilot
- **WHEN** static shape cannot predict which intersection child is smallest
- **THEN** EACL uses the sealed deterministic generator and performs no equal-prefix pilot over every child

### Requirement: Operator lookup stops at exact bounded demand
An acyclic operator lookup SHALL stop at generator exhaustion, the accepted `N+1` lookahead sentinel, the candidate-window boundary, or another configured work/deadline/cancellation limit. It MUST NOT materialize complete operands or the global result type merely to return a bounded page.

#### Scenario: First page from broad operands
- **WHEN** the selected generator and predicates produce `N+1` accepted candidates from an early prefix
- **THEN** no later candidate or complete operand is demanded except bounded physical batch overread charged to the request

### Requirement: Exclusion lookup is left-driven
An exclusion lookup SHALL enumerate only its sealed left cover as the public candidate domain and SHALL decide right membership exactly by witness-aware predicate or sequence-compatible anti-join. It MUST NOT collect the right denotation wholesale for a bounded page.

#### Scenario: Dense right operand
- **WHEN** most left candidates occur in the right operand
- **THEN** evaluation advances monotonically until it fills the page, exhausts the left, or reports bounded progress without treating rejected-prefix completion as graph exhaustion

### Requirement: Point operations use deterministic decisive order
Point union, intersection, and exclusion SHALL use a sealed deterministic child order based only on versioned plan inputs. Evaluation SHALL stop on the first completed value that soundly decides the operator and SHALL fail on a demanded incomplete/error outcome that has not already been rendered irrelevant by an earlier decisive result.

#### Scenario: Cache does not reorder point children
- **WHEN** a later child has a compatible cached Boolean and an earlier selected child does not
- **THEN** the earlier child remains first and the cache only elides work if and when the later child is demanded

### Requirement: Every batch and specialization obeys request cut points
Cancellation, deadline, candidate, command, fetched-value, probe, transition, allocation, retained-state, and output limits SHALL be checked at the corresponding semantic or physical boundary. Limit failure SHALL abort all-or-error and MUST NOT publish an incomplete page, count, vector, or absence as complete.

#### Scenario: Cancellation between physical subgroups
- **WHEN** cancellation is observed after one subgroup and before the next subgroup of a vector predicate
- **THEN** the entire vector fails and no candidate decision from it is released

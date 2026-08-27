## ADDED Requirements

### Requirement: Batched direct membership holds its realization bound on every admissible basis kind

A backend that selects a density-bounded range kernel for batched direct membership SHALL realize no more values than its certified bound on every basis kind the adapter admits, including as-of and other temporally reconstructed bases. Where a basis kind cannot honor the kernel's seek bound, the adapter SHALL select the exact-probe kernel rather than realize an unbounded prefix.

The certified physical-policy identity a backend reports SHALL describe the kernel behavior that actually executes on the selected basis.

#### Scenario: Dense batch on an as-of snapshot

- **WHEN** a dense candidate batch is evaluated against an as-of snapshot whose endpoint holds many tuples below the batch's first candidate
- **THEN** realized values remain within the certified bound for that candidate count
- **AND** the decisions are identical to repeated certified scalar membership

#### Scenario: Basis kind cannot honor the seek bound

- **WHEN** the selected basis cannot apply the kernel's lower bound
- **THEN** the adapter selects the exact-probe kernel
- **AND** no full-prefix realization or sort is performed for that batch

### Requirement: Conformance covers the density-mode selection boundary

Cross-backend conformance SHALL exercise both physical modes of a density-bounded batch kernel, including candidate batches positioned exactly at the mode-selection boundary and immediately on either side of it, in both directions.

#### Scenario: Batch positioned exactly at the selection boundary

- **WHEN** a batch whose span equals the selection threshold for its candidate count is evaluated in forward and reverse directions
- **THEN** the selected mode matches the certified policy identity
- **AND** decisions are aligned to input order and equal to repeated scalar membership

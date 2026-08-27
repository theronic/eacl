## ADDED Requirements

### Requirement: Aggregate operations consume one budget

A batch or authorized page SHALL derive one absolute monotonic deadline and one cancellation scope at its public boundary. Every sub-demand, candidate window, point decision, direct-match probe, lookahead, cache action, rendering step, and publication MUST consume the remaining portion of that budget. Scheduling another demand, opening another window, or invoking the internal point kernel MUST NOT create a fresh relative timeout, cancellation token, or resource-limit scope. The per-page candidate budget is a work bound, not a time bound: reaching it ends a page, while reaching the deadline throws.

#### Scenario: Deadline inside a candidate window

- **WHEN** the deadline expires while examining rejected candidates inside a window
- **THEN** EACL starts no later candidate or probe
- **AND** throws `:eacl.execution/deadline-exceeded` rather than returning a short page

#### Scenario: Nested kernel receives remaining budget

- **WHEN** aggregate orchestration delegates a bounded semantic quantum to the point kernel
- **THEN** the quantum observes the original absolute deadline and only its remaining time
- **AND** instrumentation observes no deadline-construction event below the public boundary

### Requirement: Aggregate cancellation is fail-closed and complete

Cancellation of a batch or authorized page SHALL stop newly scheduled demands, windows, and probes at the same modeled boundaries as scalar execution, request cancellation of a running backend command where supported, release an owned snapshot exactly once, and throw the typed cancellation error. Completed prefix decisions or rows MUST NOT be returned or published as an aggregate answer.

#### Scenario: Cancellation after a completed prefix

- **WHEN** cancellation is observed after several decisions or rows are internally complete
- **THEN** EACL publishes no batch or page artifact
- **AND** does not convert the unfinished suffix into denials, omissions, `:bounded? true`, or `:has-next-page? false`

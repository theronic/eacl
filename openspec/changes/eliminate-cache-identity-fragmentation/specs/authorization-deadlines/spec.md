## ADDED Requirements

### Requirement: Compatible cache reuse obeys the current invocation contract

Excluding `:timeout-ms` and `:cancellation-token` from a successful-result compatibility identity MUST NOT exclude them from request execution. Before cache lookup and again at the existing bounded stages through validation and externalization, EACL SHALL check the current invocation's absolute monotonic deadline and cancellation token. A compatible immutable value MAY be reused across different positive timeout budgets or different non-cancelled token instances only while the current invocation remains live. Deadline expiry or cancellation MUST retain its existing typed failure and MUST NOT be converted into a cached authorization value. Optional publication SHALL still begin only while the publishing invocation is live.

#### Scenario: Warm result is requested with a different live timeout
- **WHEN** a compatible immutable result is resident and a new request supplies a different positive timeout that remains live through externalization
- **THEN** the request may reuse the result under its own absolute deadline
- **AND** returns the same answer as fresh evaluation on the selected basis

#### Scenario: Deadline is expired before warm-cache lookup
- **WHEN** a compatible result is resident but the current invocation's deadline expires before cache lookup
- **THEN** EACL throws `:eacl.execution/deadline-exceeded`
- **AND** does not return, republish, or externalize the resident result for that invocation

#### Scenario: Cancellation is observed around a warm-cache hit
- **WHEN** the current invocation is cancelled before lookup or at a bounded check after obtaining a compatible immutable value
- **THEN** EACL throws `:eacl.execution/cancelled`
- **AND** the resident value is not converted into a successful response for that invocation

#### Scenario: Warm value outlives a shorter request budget
- **WHEN** a completed value was produced under a longer timeout and a later compatible request has a shorter timeout
- **THEN** only the later request's absolute deadline governs its execution
- **AND** the producing request's timeout or token state is neither inherited nor consulted

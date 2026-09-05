## Purpose

Defines native exclusive `valid-until` behavior for v9 qualified Relationships, including trusted time capture, positive and negative evidence, renewal, and collection-independent correctness.

## ADDED Requirements

### Requirement: Relationship expiry is exclusive
A qualifier MAY carry `valid-until-ms`. The Relationship SHALL be expiry-active exactly when the captured evaluation time is strictly less than that value. An absent value SHALL be unbounded.

#### Scenario: Before expiry
- **WHEN** evaluation time is one millisecond before `valid-until`
- **THEN** the Relationship may participate subject to its Caveat

#### Scenario: At expiry
- **WHEN** evaluation time equals `valid-until`
- **THEN** the Relationship is inactive

#### Scenario: After expiry
- **WHEN** evaluation time is later than `valid-until`
- **THEN** the Relationship is inactive

#### Scenario: No expiry
- **WHEN** the qualifier has no `valid-until`
- **THEN** expiry alone never removes the Relationship

### Requirement: Evaluation time is trusted and captured once
Each top-level authorization operation, batch, or explicit temporal snapshot SHALL use one trusted evaluation-time sample for every edge, recursive step, cache decision, and result. Callers MUST NOT provide an untrusted `now` value as ordinary request context.

#### Scenario: Clock crosses a deadline during evaluation
- **WHEN** wall time passes an expiry while one request is executing
- **THEN** every decision in that request uses the originally captured time

#### Scenario: Two later requests share one database basis
- **WHEN** one request occurs before expiry and another after expiry without a database transaction
- **THEN** they may return different authorization results based on their distinct captured times

#### Scenario: Test clock
- **WHEN** a test client injects a deterministic trusted clock
- **THEN** boundary results are reproducible without changing database state

#### Scenario: Raw clock moves backward
- **WHEN** a client's underlying wall clock reports a value below its last accepted evaluation time
- **THEN** EACL does not accept the lower value as a new live authorization time
- **AND** the client applies its documented non-decreasing or strict-failure policy rather than reviving expired access

### Requirement: Expiry applies to granting and subtracting evidence
Expiry SHALL apply uniformly to direct grants, group membership, arrows, recursion, exclusions, and deny/subtracting Relationships. The implementation MUST NOT assume that passing time can only remove permission.

#### Scenario: Grant expires
- **WHEN** the only true grant witness expires
- **THEN** permission may change from has-permission to no-permission

#### Scenario: Ban expires
- **WHEN** an active subtracting Relationship was the reason an exclusion was false and it expires
- **THEN** permission may change from no-permission to has-permission

#### Scenario: Intermediate edge expires
- **WHEN** a Relationship used to traverse an arrow expires
- **THEN** downstream reachability is recomputed without that edge

### Requirement: Expiry is independent of physical collection
An expired Relationship SHALL stop affecting authorization without any write, callback, timer, listener, or garbage-collection transaction. It MAY remain visible in stored Relationship inspection and conflict checks.

#### Scenario: No transaction occurs at expiry
- **WHEN** wall time reaches `valid-until` and no database write occurs
- **THEN** a new authorization operation treats the Relationship inactive

#### Scenario: Expired data remains stored
- **WHEN** an administrative stored-state read includes inactive data
- **THEN** it can return the Relationship, qualifier, and expiry while marking it inactive at the requested time

#### Scenario: Collector is absent
- **WHEN** no expired-data maintenance process runs
- **THEN** authorization remains correct and only retained storage/scan density may grow

### Requirement: Renewal atomically replaces expiry
Renewing, shortening, or removing an expiry SHALL use `:touch` to replace the immutable qualifier and both endpoint references in one admitted transaction. `:create` SHALL still conflict while an expired stored Relationship exists.

#### Scenario: Renew expired Relationship
- **WHEN** `:touch` assigns a later `valid-until` to a stored expired Relationship
- **THEN** a new qualifier and tuple pair become active according to the new bound atomically

#### Scenario: Create after expiry
- **WHEN** `:create` targets an expired but still stored first-four identity
- **THEN** it reports a Relationship conflict and directs the caller to touch or delete

#### Scenario: Remove expiry
- **WHEN** `:touch` removes the final expiry/Caveat data
- **THEN** the Relationship canonicalizes to `nil` qualifier and the old qualifier entity is removed

### Requirement: Stored and active Relationship reads are distinct
Public Relationship inspection SHALL distinguish physical stored state from expiry-active state and from full Caveat authorization. A caller MUST explicitly select or observe the mode.

#### Scenario: Stored read
- **WHEN** a caller asks for stored Relationships
- **THEN** expired rows are included with qualifier metadata

#### Scenario: Active-at read
- **WHEN** a caller asks for expiry-active Relationships at a trusted/explicit time
- **THEN** expired rows are excluded but Caveat-conditional rows are not mislabeled as authorized

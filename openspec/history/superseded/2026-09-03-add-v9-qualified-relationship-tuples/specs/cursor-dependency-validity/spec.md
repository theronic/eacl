> **DEPRECATED / SUPERSEDED — 2026-09-04.** This unimplemented proposal is retained for historical review only. Its requirements and unchecked tasks are withdrawn from the implementation plan; do not apply or sync these deltas into the main specifications. Use the [replacement v9 proposal](../../../2026-09-04-add-v9-caveats-and-expiring-relationships/proposal.md).
>
> The replacement uses **seven-slot Caveat + expiry-only endpoint tuples**, removes scheduled activation and the mandatory expiration index, and retains time-aware cache/cursor checks. See the [review findings and preserved REPL evidence](../../review-2026-09-04.md). The original artifact follows unchanged.

## ADDED Requirements

### Requirement: Temporal continuations bind valid-at and stability horizon

Every validity-aware continuation SHALL authenticate its temporal mode,
selected `valid-at`, and full temporal stability interval. A continuation
created from an explicit EACL snapshot SHALL use pinned mode. A client-targeted
query SHALL use live mode unless it explicitly selects a pinned snapshot.
Token expiry and snapshot-retention limits SHALL be checked independently from
relationship valid-time stability.

Pinned resumption SHALL preserve the exact original valid-at and validate the
ordinary database dependencies. Live resumption SHALL capture one new trusted
valid-at and reuse retained state only when that value lies inside its certified
interval. Crossing either bound SHALL return a typed temporal-restart error and
no resumed page. The caller starts a new query; EACL MUST NOT silently reuse the
old position and skip newly active earlier candidates.

Certificates SHALL cover retained frontier, skipped candidates, negative
evidence, lookahead, and residual state. Certifying only emitted items is
insufficient. Uncertified temporal reuse SHALL fall back to exact valid-at
identity or fail with a restart requirement.

#### Scenario: Resume before temporal boundary

- **WHEN** a live cursor resumes with a new valid-at inside its complete certified interval and equivalent database dependencies
- **THEN** EACL may reuse its continuation state at that new valid-at

#### Scenario: Resume at temporal boundary

- **WHEN** a live cursor's newly selected valid-at reaches or leaves either interval bound
- **THEN** EACL returns a typed temporal-restart error and no page
- **AND** a restarted query does not silently continue from the old position

#### Scenario: Explicit administrative valid-at

- **WHEN** a pinned cursor was issued at valid-at 90 with interval ending at 100 and resumes when wall-clock time is 110
- **THEN** its temporal certificate remains valid at 90
- **AND** separate token expiry, retention, and database-proof checks still apply

#### Scenario: Unrelated database churn

- **WHEN** unrelated transactions occur while valid-time and dependency certificates remain valid
- **THEN** those transactions do not by themselves invalidate continuation

#### Scenario: Skipped future edge activates before the cursor position

- **WHEN** a live page skipped a future relationship before its last emitted identity
- **THEN** the continuation horizon ends no later than that activation
- **AND** resumption after activation requires a new query rather than omitting the newly active identity

### Requirement: Caveat request context is authenticated continuation scope

A Caveat-aware cursor SHALL authenticate the complete canonical request context,
the Caveat compatibility profile/evaluator identity, and the conditional result
semantics needed to reproduce its page. Context supplied on resumption SHALL
match the authenticated scope or cause a typed mismatch or restart.

Relationship-bound context remains database dependency data: the cursor proof
SHALL cover each relevant context eid/payload and Caveat definition through the
selected snapshot proof.

#### Scenario: Request context changes between pages

- **WHEN** a caller resumes a cursor with different Caveat request context
- **THEN** EACL rejects the continuation or starts a separately scoped query
- **AND** does not reuse the prior traversal frontier

#### Scenario: Bound context changes between pages

- **WHEN** `:touch` replaces a relationship's Caveat context before resumption
- **THEN** relation and content proof validation prevent old continuation reuse

#### Scenario: Caveat definition changes

- **WHEN** schema replacement changes a referenced Caveat before resumption
- **THEN** schema validation rejects or recovers the continuation according to
  policy

#### Scenario: Evaluator identity changes

- **WHEN** the Caveat evaluator compatibility fingerprint changes
- **THEN** old Caveat-aware cursors are incompatible

### Requirement: Conditional lookup state is stable across pages

Caveat-aware lookup cursors SHALL preserve whether each returned membership is
definite or conditional and SHALL authenticate retained missing-context or
residual-condition data. A continuation MUST NOT turn a conditional candidate
into a definite grant or omit a definite candidate because condition state was
lost.

#### Scenario: Conditional candidate crosses page boundary

- **WHEN** a conditional lookup candidate is the last item on one page or first
  item on the next
- **THEN** its permissionship and missing-context fields remain reproducible

#### Scenario: Definite and conditional candidates share an object id

- **WHEN** multiple permission paths reach one object with different condition
  states
- **THEN** pagination applies the permission algebra before emitting one
  correctly classified result
- **AND** does not emit duplicate object identities

#### Scenario: Boolean compatibility lookup

- **WHEN** a compatibility lookup omits conditional results
- **THEN** its cursor scope records that result policy
- **AND** it cannot resume through a Caveat-aware result policy

### Requirement: v9 cursors commit to fixed eight-slot relationship ordering

Relationship-read and authorization cursors SHALL identify the v9 endpoint ABI
and the ordering boundary derived from endpoint component four. Trailing Caveat,
context, and validity qualifiers SHALL be authenticated where needed for exact
stored-relationship enumeration, while effective authorization pagination
remains ordered by the opposite endpoint identity.

#### Scenario: Old cursor reaches v9

- **WHEN** a cursor was minted for a four-slot or seven-slot experimental
  relationship ABI
- **THEN** the v9 client rejects it as incompatible

#### Scenario: Qualifiers change at the boundary relationship

- **WHEN** `:touch` changes qualifiers on a relationship used as a stored-read
  continuation boundary
- **THEN** dependency and cursor validation prevent the old physical boundary
  from being mistaken for its replacement

#### Scenario: Full-arity vector bound

- **WHEN** DataScript resumes a stored relationship scan
- **THEN** its comparator boundary uses the complete eight-element vector shape
- **AND** qualifier values cannot shift the endpoint ordering contract

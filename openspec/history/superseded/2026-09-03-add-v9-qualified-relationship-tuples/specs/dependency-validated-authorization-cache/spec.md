> **DEPRECATED / SUPERSEDED — 2026-09-04.** This unimplemented proposal is retained for historical review only. Its requirements and unchecked tasks are withdrawn from the implementation plan; do not apply or sync these deltas into the main specifications. Use the [replacement v9 proposal](../../../2026-09-04-add-v9-caveats-and-expiring-relationships/proposal.md).
>
> The replacement uses **seven-slot Caveat + expiry-only endpoint tuples**, removes scheduled activation and the mandatory expiration index, and retains time-aware cache/cursor checks. See the [review findings and preserved REPL evidence](../../review-2026-09-04.md). The original artifact follows unchanged.

## ADDED Requirements

### Requirement: Temporal cache reuse is bounded by certified valid-time stability

Every completed answer, reusable denotation, projection, and continuation that
depends on relationship validity SHALL include a conservative valid-time
stability interval containing the `valid-at` used for computation. Reuse SHALL
require both the existing complete database dependency proof and membership of
the selected `valid-at` in that certified interval.

Including exact `valid-at` in a non-reusable key or bypassing publication is an
acceptable conservative fallback. An entry MUST NOT remain reusable merely
because the database basis and relation versions are unchanged when time can
change the answer.

#### Scenario: Time changes without a transaction

- **WHEN** a future relationship reaches `valid-from` or an active relationship
  reaches `valid-until` without a database transaction
- **THEN** an entry whose stability interval ended at that boundary is not
  reused

#### Scenario: Database dependency changes inside a stable interval

- **WHEN** selected `valid-at` remains inside the entry's stability interval but
  a relation in the complete dependency closure changes
- **THEN** ordinary dependency validation rejects the entry

#### Scenario: Permanent graph

- **WHEN** every answer-affecting relationship is permanent and all database
  dependency proofs remain valid
- **THEN** temporal stability MAY be unbounded

#### Scenario: Incomplete temporal proof

- **WHEN** the engine cannot prove a safe interval for a cacheable value
- **THEN** it computes the selected-snapshot result
- **AND** does not publish or lift it across valid-time instants

### Requirement: Caveat cache identity is context-complete

For every cache entry or subproblem influenced by a possible Caveat path,
lookup scope, dependency certificates, and authenticated value data together
SHALL distinguish:

- the complete canonical request context admitted for the operation;
- each relevant Caveat definition and schema generation;
- each relationship Caveat context eid and authoritative payload;
- the Caveat compatibility profile and evaluator fingerprint;
- permissionship, residual condition, and missing-context result data; and
- the ordinary complete relation dependency proof.

The lookup key SHALL be computable before evaluation. Permissionship, residual
condition, and missing fields SHALL be authenticated value data, not inputs
that require knowing the answer before a lookup. Definition and bound-context
content SHALL be dependency-certificate data.

A relation-version proof alone SHALL NOT authorize reuse after context payload,
Caveat definition, request context, or evaluator semantics change.

#### Scenario: Same graph and different request context

- **WHEN** two requests select the same database and valid-time but differ in a
  context value visible to a possible Caveat path
- **THEN** their semantic cache identities differ

#### Scenario: Bound context overrides request context

- **WHEN** relationship-bound context fixes a value also present in request
  context
- **THEN** lookup identity includes the admitted request context and dependency
  validation covers the authoritative bound values
- **AND** a caller-supplied replacement cannot alias the bound result

#### Scenario: Caveat definition changes

- **WHEN** schema replacement changes a referenced Caveat definition
- **THEN** the changed schema generation rejects entries computed under the old
  definition

#### Scenario: Caveat context changes out of band

- **WHEN** a context payload changes while its eid and relation version remain
  unchanged
- **THEN** database-visible proof validation rejects the prior entry

#### Scenario: Evaluator profile changes

- **WHEN** the Caveat compatibility profile or evaluator fingerprint changes
- **THEN** entries produced by the old evaluator are cache misses

### Requirement: Conditional results cannot alias definite results

The completed-answer and subproblem value formats SHALL distinguish
`:has-permission`, `:no-permission`, and `:conditional-permission`.
Conditional entries SHALL authenticate their residual condition and missing
context fields where retained. Cache lookup MUST NOT substitute one
permissionship state for another.

#### Scenario: Conditional value is replayed as a grant

- **WHEN** an external provider returns a validly shaped conditional value under
  a key for a definite grant
- **THEN** complete key/value authentication rejects it

#### Scenario: Missing field set changes

- **WHEN** two conditional evaluations require different context fields
- **THEN** their retained result identities differ

#### Scenario: Boolean can? uses a conditional cache entry

- **WHEN** `can?` obtains a valid cached conditional permissionship
- **THEN** it returns false rather than treating the entry as a grant

### Requirement: Caveat algebra caching preserves possible paths

Caveat-aware cache proofs SHALL cover all positive, negative, and conditional
paths that can affect the result. Runtime short-circuiting MAY retain a smaller
witness only when the proof establishes that omitted paths cannot alter the
permissionship or residual condition.

#### Scenario: Unconditional union witness

- **WHEN** one union branch grants unconditionally and another is conditional
- **THEN** a cached has-permission result need not retain the irrelevant
  conditional branch as a result dependency
- **AND** its ordinary static relationship dependency closure remains complete

#### Scenario: Conditional union

- **WHEN** every possible granting branch is false or conditional
- **THEN** a cached conditional result commits to all residual alternatives
  needed to reproduce it

#### Scenario: Conditional exclusion

- **WHEN** a true positive branch is reduced by a conditional subtracting branch
- **THEN** the cached result remains conditional
- **AND** commits to the subtracting residual condition

#### Scenario: Missing proof

- **WHEN** EACL cannot prove that a cached residual condition is complete
- **THEN** it evaluates without reusable Caveat result publication

### Requirement: Temporal and Caveat ABI changes invalidate portable caches

The authorization ABI used by exact and managed keys, exported cache snapshots,
subproblem values, and restored entries SHALL change for the v9 relationship
representation and Phase 2 Caveat semantics. Restoring an artifact whose ABI
does not commit to eight-slot qualifiers, valid-time, request context, and
conditional result semantics SHALL fail closed.

#### Scenario: Restore old cache snapshot

- **WHEN** a v9 client restores a cache snapshot produced by the old
  relationship ABI
- **THEN** it rejects the snapshot before any entry influences authorization

#### Scenario: Restore Phase 1 artifact in Phase 2

- **WHEN** Phase 2 enables Caveats and encounters a Phase 1 cache artifact whose
  ABI cannot encode conditional semantics
- **THEN** it rejects or treats the artifact as a miss

#### Scenario: Shared provider contains old entries

- **WHEN** a shared provider returns an authenticated entry carrying an old
  authorization ABI
- **THEN** the v9 client treats it as a miss


### Requirement: Temporal certificates belong to the exact reusable artifact

A reusable answer, subproblem denotation, and continuation SHALL each carry a
certificate for its own contents. A stable final answer does not certify that
an intermediate denotation or traversal frontier is stable. Certificates SHALL
include both lower and upper bounds so administrative evaluation can move in
either direction. Authoritative faults SHALL NOT be cached as ordinary denials.

#### Scenario: Stable union and unstable subproblem

- **GIVEN** a permanent owner path makes a union always true while another relation activates later
- **WHEN** the engine caches the union answer and the other relation's denotation
- **THEN** the answer may be unbounded but the denotation certificate ends at activation
- **AND** another permission cannot reuse the stale denotation beyond that boundary

#### Scenario: Historical query precedes certificate

- **WHEN** an administrative valid-at is earlier than the cached interval's lower bound
- **THEN** the entry is a miss even if the database is unchanged

#### Scenario: Maintenance-only expiration repair

- **WHEN** only a derived expiration-index value changes
- **THEN** that change alone does not alter the authorization content certificate
- **AND** collection/reconciliation diagnostics remain independently correct

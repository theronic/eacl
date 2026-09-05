## ADDED Requirements

### Requirement: Expiry-aware continuations bind mode and deadline

Every continuation affected by expiry SHALL authenticate its temporal mode, captured evaluation time, and its own exclusive reuse deadline. An explicit EACL snapshot SHALL produce a pinned continuation; a client-targeted query SHALL produce a live continuation unless it explicitly chooses a pinned snapshot. Token expiration and history retention SHALL remain separate checks.

Pinned resumption SHALL preserve the exact database basis and evaluation time. Live resumption SHALL capture one fresh trusted time and require it to be at or after the certificate start and before any finite deadline, along with ordinary dependency validation. Leaving the certified range SHALL return a typed restart requirement and no resumed page. It SHALL NOT silently reuse the previous position.

The certificate SHALL cover skipped candidates, subtracting evidence, frontier, lookahead, and residual state as well as emitted results. Incomplete proof SHALL prevent cross-time continuation. Changes to request context or temporal mode SHALL require a separately scoped query rather than reinterpret the cursor.

#### Scenario: Resume before expiry

- **WHEN** a live cursor resumes within its certified range with equal complete dependencies
- **THEN** its retained state can be reused at the new selected time

#### Scenario: Resume at expiry

- **WHEN** a live cursor resumes exactly at its exclusive deadline
- **THEN** EACL returns a typed restart requirement and no page

#### Scenario: Pinned snapshot after wall-clock expiry

- **WHEN** a cursor was pinned at evaluation time 90 with a deadline of 100 and wall time reaches 110
- **THEN** its time certificate remains valid at 90
- **AND** exact basis availability, token lifetime, and the other proof checks still apply

#### Scenario: Unrelated churn

- **WHEN** unrelated transactions occur while live time and all dependency certificates remain valid
- **THEN** that churn alone does not invalidate continuation

#### Scenario: Expired ban authorizes an earlier identity

- **GIVEN** a lookup skipped identity 10 because banned was active and emitted through identity 20
- **WHEN** that ban expires before live resumption
- **THEN** the cursor cannot resume after identity 20 and silently omit newly authorized identity 10
- **AND** its complete expiry proof forces a restart at that boundary

#### Scenario: Earlier frozen time

- **WHEN** a caller attempts to reuse live state at a time before its certificate start
- **THEN** EACL rejects the reuse rather than reviving state under an unproved temporal view

### Requirement: Caveat request context is authenticated continuation scope

A Caveat-aware cursor SHALL authenticate the complete canonical request context,
the Caveat compatibility profile/evaluator identity (including the pinned
cel-parser build, EACL adapter/plan format and partial/error semantics, semantic options, and extension identities for JVM execution),
and the conditional result
semantics needed to reproduce its page. Context supplied on resumption SHALL
match the authenticated scope or cause a typed mismatch or restart.

Relationship-bound context remains database dependency data: the cursor proof
SHALL cover each relevant context eid/payload, named Caveat definition, and its referenced canonical expression content through the
selected snapshot proof. A resident program or matching expression eid SHALL NOT
substitute for this proof. Programs themselves SHALL NOT be serialized in cursors.

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

### Requirement: v9 cursors commit to fixed seven-slot relationship ordering

Relationship-read and authorization cursors SHALL identify the v9 endpoint ABI
and the ordering boundary derived from endpoint component four. Trailing Caveat,
context, and expiry qualifiers SHALL be authenticated where needed for exact
stored-relationship enumeration, while effective authorization pagination
remains ordered by the opposite endpoint identity.

#### Scenario: Old cursor reaches v9

- **WHEN** a cursor was minted for a four-slot, discarded eight-slot, or differently defined experimental
  seven-slot relationship ABI
- **THEN** the v9 client rejects it as incompatible

#### Scenario: Qualifiers change at the boundary relationship

- **WHEN** `:touch` changes qualifiers on a relationship used as a stored-read
  continuation boundary
- **THEN** dependency and cursor validation prevent the old physical boundary
  from being mistaken for its replacement

#### Scenario: Full-arity vector bound

- **WHEN** DataScript resumes a stored relationship scan
- **THEN** its comparator boundary uses the complete seven-element vector shape
- **AND** qualifier values cannot shift the endpoint ordering contract

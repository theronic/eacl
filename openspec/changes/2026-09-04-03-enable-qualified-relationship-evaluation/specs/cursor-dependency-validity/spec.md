## ADDED Requirements

### Requirement: Expiry-aware continuations bind temporal mode and deadline
Every qualified continuation SHALL authenticate its temporal mode, original evaluation time, and complete exclusive temporal reuse interval. An explicit EACL snapshot SHALL produce a pinned continuation. A client-targeted lookup SHALL produce a live continuation unless it explicitly evaluates a pinned snapshot. Cursor token expiry and security-key availability SHALL remain separate checks.

Pinned resumption SHALL preserve the exact database basis and evaluation time. Live resumption SHALL capture one fresh trusted time and MAY reuse retained state only when that time is at or after the certificate start and strictly before every finite deadline, with ordinary dependency validation still succeeding. Leaving the certified interval MUST return a typed restart requirement and no resumed page; it MUST NOT silently reuse the previous position against a new temporal graph.

#### Scenario: Live resume before expiry
- **WHEN** a live cursor resumes within its certified interval with equal complete dependencies
- **THEN** retained traversal state may be reused at the newly captured time

#### Scenario: Live resume at expiry
- **WHEN** a live cursor resumes exactly at its exclusive deadline
- **THEN** EACL returns a typed restart requirement and no page

#### Scenario: Pinned snapshot after wall-clock expiry
- **WHEN** a cursor was created from an explicit snapshot at time 90 with an evidence deadline of 100 and wall time reaches 110
- **THEN** its temporal evaluation remains pinned at 90
- **AND** exact-basis availability, cursor TTL, key availability, and ordinary proof checks still apply

#### Scenario: Clock regresses before certificate start
- **WHEN** a live resume captures a time earlier than the cursor certificate start
- **THEN** EACL rejects reuse rather than reviving retained state under an unproved view

### Requirement: Continuation proofs include qualified skipped evidence
A live cursor's temporal certificate SHALL cover examined emitted and skipped candidates before the boundary, subtracting evidence, conditional residuals, retained frontier, and lookahead needed to justify continuation. Incomplete proof MUST prevent cross-time reuse rather than trigger an unbounded proof-building traversal.

#### Scenario: Expired ban authorizes an earlier identity
- **GIVEN** a lookup skipped identity 10 because a ban was active and emitted through identity 20
- **WHEN** that ban expires before live resumption
- **THEN** the cursor cannot resume after identity 20 and silently omit newly authorized identity 10
- **AND** its temporal deadline forces a restart

#### Scenario: Conditional candidate precedes boundary
- **WHEN** a candidate before the boundary was conditional under the original request context
- **THEN** the certificate and result-policy scope preserve that classification rather than replaying it as definite

#### Scenario: Complete temporal proof is unavailable
- **WHEN** retained state cannot be assigned a complete safe interval within existing work bounds
- **THEN** EACL does not permit cross-time continuation and does not scan the remaining graph solely to create a certificate

### Requirement: Caveat request context is authenticated continuation scope
A Caveat-aware cursor SHALL authenticate the complete canonical request context, evaluator/profile identity, and conditional-result policy needed to reproduce its page. Context supplied on resumption MUST match the authenticated scope. Relationship-bound context and named Caveat definitions SHALL remain database dependency data covered by the selected snapshot proof; parsed evaluator programs MUST NOT be serialized in cursors.

#### Scenario: Request context changes between pages
- **WHEN** a caller resumes a cursor with different Caveat request context
- **THEN** EACL rejects the continuation and requires a separately scoped lookup

#### Scenario: Bound context changes between pages
- **WHEN** `:touch` replaces a Relationship's bound Caveat context before resumption
- **THEN** Relation and qualifier proof validation prevent old continuation reuse

#### Scenario: Evaluator identity changes
- **WHEN** the Caveat evaluator/profile fingerprint differs from the cursor scope
- **THEN** EACL rejects or exact-recovers only under an explicitly compatible identity and never silently adopts new semantics

### Requirement: Conditional lookup state is stable across pages
Caveat-aware lookup cursors SHALL preserve whether emitted membership is definite or conditional and SHALL authenticate retained missing-context or residual-condition state. A continuation MUST NOT turn a conditional candidate into a definite grant, lose a definite candidate, or mix detailed and definite-only result policies.

#### Scenario: Conditional candidate crosses a page boundary
- **WHEN** a conditional lookup candidate is the last item on one page or the first item on the next
- **THEN** its permissionship and missing-context fields remain reproducible

#### Scenario: Multiple paths reach one resource
- **WHEN** definite and conditional permission paths reach the same resource
- **THEN** pagination applies the permission algebra before emitting one correctly classified resource identity

#### Scenario: Boolean compatibility lookup
- **WHEN** a definite-only lookup omits conditional results
- **THEN** its cursor authenticates that result policy and cannot resume as a detailed conditional lookup

### Requirement: A current-time or new-context view restarts pagination
A caller requesting a new Caveat context or a current temporal view after a live cursor leaves its certified interval SHALL start a new lookup from the beginning. EACL SHALL NOT rebase an existing public boundary onto that changed semantic view.

#### Scenario: Caller requests current authorization after restart requirement
- **WHEN** a live cursor reaches its temporal deadline
- **THEN** the caller starts a new lookup without the old cursor and receives a newly captured time, result set, and boundary series

## MODIFIED Requirements

### Requirement: Datomic forward-history selection
Within one unreplaced Datomic database history, EACL SHALL use authenticated database scope and transaction `t` as the native causal floor, targeted synchronization for at-least selection and for an exact token basis ahead of the local Peer, and `d/as-of` selection only for explicit exact-snapshot behavior. An authentic same-source exact basis that is ahead of one Peer SHALL be treated as propagation lag, not as an expired or out-of-range snapshot.

#### Scenario: Peer is behind requested floor
- **WHEN** a Datomic Peer receives an at-least token whose `t` is ahead of its locally observed database
- **THEN** EACL performs bounded targeted synchronization and selects a database with basis at least that `t` or returns a typed freshness failure

#### Scenario: Peer is behind requested exact basis
- **WHEN** a Datomic Peer receives an authenticated same-source exact token whose `t` is ahead of its locally observed database
- **THEN** EACL performs bounded two-argument `d/sync` to `t`
- **AND** verifies that the synchronized database has basis at least `t`
- **AND** applies `d/as-of` so authorization evaluates exactly at `t`

#### Scenario: Exact basis is already local
- **WHEN** an authenticated same-source exact token names `t` at or below the locally observed Datomic basis
- **THEN** EACL does not invoke `d/sync`
- **AND** selects the exact `d/as-of` database at `t`

#### Scenario: Exact catch-up times out
- **WHEN** a Datomic Peer does not observe an authenticated exact token basis before the bounded consistency deadline
- **THEN** EACL cancels the targeted-sync future
- **AND** throws `:eacl.consistency/freshness-unavailable` with reason `:freshness-timeout`
- **AND** does not report the exact snapshot as expired or evaluate a different basis

#### Scenario: Exact catch-up is interrupted
- **WHEN** the request thread is interrupted while waiting for an exact basis
- **THEN** EACL cancels the targeted-sync future and preserves interruption
- **AND** throws a classified cancellation rather than snapshot expiry

#### Scenario: Exact synchronization returns behind the token
- **WHEN** targeted synchronization completes with a database whose basis is below the authenticated exact token basis
- **THEN** EACL throws `:eacl.consistency/freshness-unavailable` with reason `:head-behind` and the requested and observed bases
- **AND** does not call `d/as-of` on the insufficient database

#### Scenario: Datomic token fields contradict one another
- **WHEN** an authenticated payload supplied to Datomic has a non-integer locator or its native revision differs from its exact locator
- **THEN** EACL rejects it as an invalid or contradictory token before synchronization

#### Scenario: Exact selection provider fails
- **WHEN** synchronization, storage access, or exact reconstruction fails unexpectedly
- **THEN** EACL preserves a typed freshness or retryable selection failure with its phase and cause
- **AND** does not return `nil` or misreport the failure as snapshot expiry

#### Scenario: Ordinary Datomic history grows older
- **WHEN** an authenticated unexpired token names an older `t` in the same unreplaced ordinary Datomic history
- **THEN** age alone does not make the exact snapshot unavailable
- **AND** EACL selects `d/as-of` at that `t`

#### Scenario: Datomic history is destructively rewritten
- **WHEN** restore, reset, excision, or another operation can change the meaning of an old exact locator
- **THEN** operators quiesce affected traffic and rotate the configured source lifecycle before resuming
- **AND** old tokens/cursors fail lifecycle comparison rather than being reinterpreted or reported as age-expired

#### Scenario: Ordinary current request
- **WHEN** a caller requests minimize-latency or fully-consistent current behavior
- **THEN** EACL does not call `d/as-of` merely to validate or reuse an answer

### Requirement: Backend capability honesty
Each adapter SHALL advertise only the native revision, authoritative-head, exact-snapshot, durable-history, and ordered-generation proof operations supported by its configured backend. Exact selection from conditionally retained commits SHALL remain distinguishable from durable temporal-history reconstruction. Unsupported consistency modes SHALL be rejected before cache access.

#### Scenario: Datahike temporal history is enabled
- **WHEN** a Datahike source has `:keep-history? true`
- **THEN** EACL may advertise durable exact reconstruction by native revision even when a named commit record is absent
- **AND** cutoff collection of commit records does not expire an exact cursor whose temporal history remains available

#### Scenario: Datahike relies only on retained commits
- **WHEN** Datahike temporal history is disabled but its commit graph supports exact commit loading
- **THEN** EACL may advertise conditional exact selection
- **AND** a genuinely collected named commit returns exact-snapshot unavailable rather than a provider-failure or substituted snapshot

#### Scenario: Datahike cannot reconstruct exact history
- **WHEN** neither temporal history nor retained exact commits are available
- **THEN** the adapter does not advertise `:at-exact-snapshot`

#### Scenario: DataScript current-only source
- **WHEN** a DataScript connection cannot reconstruct arbitrary prior immutable values
- **THEN** its adapter does not advertise `:at-exact-snapshot` and never retains hidden historical values to emulate it

#### Scenario: Ordered-generation proof is not certified
- **WHEN** an adapter cannot certify complete dependency reads and globally ordered native relation generations
- **THEN** native revision and snapshot-exact operations may remain available while cross-snapshot managed answer reuse fails closed

#### Scenario: Completed-answer cache is disabled
- **WHEN** a client disables completed-answer caching but its adapter and lifecycle support native revision operations
- **THEN** EACL may still issue and select authenticated native revision tokens and reconstruct exact cursors

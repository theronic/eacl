## ADDED Requirements

### Requirement: Public authorization captures temporal and Caveat request context
Each public check, lookup, count, or batch SHALL select one immutable database snapshot, capture one trusted evaluation time, canonicalize one bounded request Caveat context, and use that request context throughout evaluation. Request Caveat context SHALL never be persisted as Relationship data.

#### Scenario: Same database before and after expiry
- **WHEN** two client-targeted checks select the same database basis on opposite sides of an expiry
- **THEN** each uses its own captured time and may return a different result

#### Scenario: Context changes between checks
- **WHEN** two checks use equal database/time inputs but different Caveat request context
- **THEN** each evaluates and caches under its own canonical context identity

#### Scenario: Batch operation
- **WHEN** one batch crosses wall-clock expiry while executing
- **THEN** every item uses the batch's single captured time

#### Scenario: Explicit snapshot pins evaluation time
- **WHEN** a caller explicitly creates an EACL snapshot and later evaluates it after wall time advances
- **THEN** the snapshot continues to use its captured database basis and evaluation time
- **AND** a client-targeted operation is required for a new current-time decision

### Requirement: Public checks expose detailed permissionship
EACL SHALL provide a detailed check result distinguishing has-permission, no-permission, conditional-permission with missing fields/residual, and authoritative evaluation failure. Existing Boolean `can?` SHALL return true only for has-permission.

#### Scenario: Conditional check
- **WHEN** a Caveat lacks context that could change its result
- **THEN** the detailed API returns conditional-permission and `can?` returns false

#### Scenario: Authoritative qualifier fault
- **WHEN** qualified evidence is corrupt or evaluator execution fails
- **THEN** the detailed API reports a typed failure and no public Boolean grant is returned

### Requirement: Lookup and count policies distinguish conditional results
Caveat-aware lookup and count operations SHALL expose or explicitly filter definite and conditional results according to a declared result policy. They MUST NOT silently count a conditional resource as definitely authorized.

#### Scenario: Detailed lookup
- **WHEN** one resource is definite and another conditional
- **THEN** detailed lookup preserves each result state and any missing fields

#### Scenario: Boolean compatibility lookup
- **WHEN** a caller selects definite-only compatibility behavior
- **THEN** only has-permission resources are emitted and conditional resources are excluded without being counted as grants

#### Scenario: Conditional-only count
- **WHEN** all reachable candidates are conditional
- **THEN** a definite count does not report them as authorized and a detailed count reports the conditional category explicitly

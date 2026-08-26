## Purpose

Defines which application-supplied immutable database values a public snapshot constructor may accept, and what a backend basis classifier must be able to prove about a value before that value is allowed to read authorization state or participate in any cache tier shared with committed bases.

## ADDED Requirements

### Requirement: Speculative database values are refused by public snapshot constructors

A public snapshot constructor that accepts an application-owned database value SHALL refuse any value that was produced by an uncommitted speculative transaction (Datomic `d/with`, Datahike/DataScript `db-with`, or an equivalent backend construction). Refusal SHALL be typed and SHALL occur before the value can create a basis identity, read schema or relationship state, or publish into any cache tier.

A backend basis classifier that cannot distinguish a speculative value from a committed one SHALL NOT report an admissible basis kind for values it cannot classify; it SHALL either prove committedness against its source or report the value as inadmissible.

#### Scenario: Speculative Datomic value offered to the direct snapshot accessor

- **WHEN** an application calls the backend's direct snapshot accessor with `(:db-after (d/with db tx))`
- **THEN** the call fails with `:eacl/unsupported-database-value` naming basis kind `:speculative`
- **AND** no basis identity, cache entry, or proof frame is created for that value

#### Scenario: Speculative basis identity collides with a committed revision

- **WHEN** a speculative value whose basis revision equals a later committed revision is captured, and a subsequent authorization check is evaluated at that committed revision
- **THEN** the committed check returns the answer derived from committed state only
- **AND** no answer derived from the speculative value is reachable from any shared exact-basis or managed cache tier

#### Scenario: Committed value remains admissible

- **WHEN** an application supplies an ordinary committed database value, or an as-of value derived from committed state, from the acl's own source
- **THEN** the snapshot is constructed and its cache participation is unchanged

### Requirement: Cache identity distinguishes bases that are not provably committed

An exact-basis or managed cache entry SHALL be keyed such that two database values that are not provably the same committed state cannot share an entry. Where a backend cannot supply a witness of committedness for an application-supplied value, that value's reads SHALL be confined to request-local cache context and SHALL NOT publish into tiers shared with committed bases.

#### Scenario: Backend cannot witness committedness

- **WHEN** a backend admits an application-supplied value whose committedness it cannot witness
- **THEN** reads on that value use request-local cache context only
- **AND** no entry it produces is visible to a snapshot or client reading committed state

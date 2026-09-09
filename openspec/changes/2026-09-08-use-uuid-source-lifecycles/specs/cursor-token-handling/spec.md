## MODIFIED Requirements

### Requirement: Invalid cursor tokens throw typed errors

`token->cursor` SHALL return `nil` only for a `nil` input (meaning "first page"). All raw cursor maps SHALL remain rejected by the public decoder, including maps carrying current versions and native UUIDs; they provide no authenticated transport authority. Any non-nil string token that cannot be decoded because of an unknown prefix or corrupt encoding SHALL throw `ex-info` with `:type :eacl.pagination/invalid-cursor`. A recognized superseded lifecycle-bearing cursor format SHALL return a typed cursor upgrade-required outcome. Pagination SHALL NOT silently restart from the first page on an invalid or obsolete cursor.

#### Scenario: Garbage token fails loudly
- **WHEN** `lookup-resources` is called with `:cursor "eacl1_not-valid"` or `:cursor "garbage"`
- **THEN** an `ex-info` with `:type :eacl.pagination/invalid-cursor` is thrown, and the first page is not silently returned

#### Scenario: nil cursor still means first page
- **WHEN** `lookup-resources` is called with `:cursor nil`
- **THEN** the first page is returned

#### Scenario: Legacy authenticated cursor version
- **WHEN** a caller submits a recognized pre-upgrade cursor format
- **THEN** a typed upgrade-required outcome requests a fresh cursor without historical selection or silent page restart

#### Scenario: Raw map carries a legacy lifecycle
- **WHEN** a raw cursor map contains any lifecycle representation, including a native UUID
- **THEN** it is rejected as an invalid cursor before selection

#### Scenario: New cursor is tampered
- **WHEN** the upgraded cursor's authentication fails
- **THEN** it is rejected as invalid rather than treated as a trusted upgrade request or resumed cursor

### Requirement: Cursors detect permission-path changes between pages

Cursors SHALL retain the current sealed-plan, dependency-proof, complete-lineage and exact-basis semantics after supported-format and authentication validation. UUID representation SHALL not change the authorized result stream. When current proofs cannot justify continuation, a backend capable of exact history MAY resume at the authenticated original snapshot subject to the request consistency contract. A current-only backend SHALL fail closed when it cannot establish continuation. An exact fallback SHALL never violate a newer freshness floor. Superseded pre-fingerprint formats SHALL require upgrade without warning-only acceptance.

#### Scenario: Relevant schema change with exact history
- **WHEN** a Datomic cursor is resumed after a relevant schema change and its original authenticated snapshot remains selectable under the requested consistency
- **THEN** continuation uses that original snapshot without reinterpreting the cursor against the new schema or silently restarting

#### Scenario: Schema change affecting the query fails loudly
- **WHEN** a current-only backend cannot prove continuation after a relevant change
- **THEN** it rejects the cursor with its existing typed consistency/staleness outcome

#### Scenario: Unrelated schema change does not invalidate the cursor
- **WHEN** existing complete proofs establish equivalent continuation across unrelated changes
- **THEN** pagination resumes without duplicates or gaps

#### Scenario: Freshness floor excludes old snapshot
- **WHEN** the requested freshness floor is newer than the cursor snapshot and current continuation is not justified
- **THEN** exact fallback is rejected with the existing consistency-conflict outcome

#### Scenario: Pre-fingerprint cursor is obsolete
- **WHEN** a cursor from the former warning-only compatibility period is supplied
- **THEN** EACL reports upgrade required instead of logging a warning and accepting it

#### Scenario: Unchanged schema resumes normally
- **WHEN** a supported cursor is resumed with unchanged schema and valid continuation proofs
- **THEN** pagination resumes exactly without duplicates or gaps

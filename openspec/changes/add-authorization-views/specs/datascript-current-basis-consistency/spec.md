## MODIFIED Requirements

### Requirement: DataScript selects only current DB values across requests
Each DataScript read through an `acl` SHALL capture one current immutable `ds/db` value after normalizing consistency and SHALL remain on that value for all schema, cache, traversal, cursor, rendering, and token work in the request. A caller-retained DataScript snapshot SHALL evaluate at its own immutable value without capturing current. DataScript MUST NOT retain an old DB value itself so a later `acl` request can select it as historical state, and MUST NOT advertise exact selection.

#### Scenario: Concurrent local transaction
- **WHEN** an `acl` request captures `S0` and the connection commits `S1` during evaluation
- **THEN** the request remains correct for `S0`
- **AND** a subsequent `acl` request captures `S1`

#### Scenario: Retained snapshot
- **WHEN** a caller holds a DataScript snapshot at `S0` and the connection commits `S1`
- **THEN** reads through the snapshot remain at `S0` and perform no capture
- **AND** `(eacl/snapshot acl (at-exact-snapshot token))` still throws `:eacl/unsupported-capability`

#### Scenario: Request completes
- **WHEN** an `acl` request using `S0` returns or fails
- **THEN** EACL holds no lease or registry obligation for `S0` beyond the bounded retained-basis cache

### Requirement: DataScript cursors use bounded current-basis proof
A DataScript cursor SHALL continue only on the basis selected for the continuing request: the current DB for an `acl` request, or the snapshot's own value for a snapshot request. In default content, unknown-authority, or no-cache-proof modes the cursor SHALL bind the exact immutable basis and SHALL NOT compute a relationship-content proof; any other basis SHALL return a typed stale-cursor, basis-conflict, or newer-floor error. Only explicit managed mutation-stamp mode MAY continue on a newer ordinary basis whose complete dependency and ordering stamps are equal. DataScript MUST NOT silently restart, reconstruct an old DB, scan relationship content to mint a cursor, or return a cached old page.

#### Scenario: Unrelated current transaction
- **WHEN** default content or no-cache-proof mode selects `S1` on an `acl` after a cursor was minted on `S0`
- **THEN** EACL rejects the cursor even when the transaction was unrelated

#### Scenario: Same snapshot continues
- **WHEN** a cursor minted on a snapshot at `S0` is consumed on that snapshot
- **THEN** pagination continues without proof computation or capture

#### Scenario: Managed unrelated current transaction
- **WHEN** explicit managed mutation-stamp mode selects `S1` and every complete cursor dependency and ordering stamp equals the cursor minted on `S0`
- **THEN** EACL may resume deterministically on `S1` without duplicates or omissions

#### Scenario: Relevant relationship change
- **WHEN** managed mutation-stamp mode observes a changed relationship in the cursor dependency closure between pages
- **THEN** EACL rejects continuation as stale

#### Scenario: Schema or ordering change
- **WHEN** schema generation, identity mapping, adapter fingerprint, or ordering ABI differs
- **THEN** EACL rejects the cursor before traversal

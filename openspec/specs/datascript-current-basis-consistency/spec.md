# datascript-current-basis-consistency Specification

## Purpose
TBD - created by archiving change demand-bounded-authorization-execution. Update Purpose after archive.
## Requirements
### Requirement: DataScript selects only current DB values across requests
Each DataScript public request SHALL capture one current immutable `ds/db`
value after normalizing consistency and SHALL remain on that value for all
schema, cache, traversal, cursor, rendering, and token work in the request.
DataScript MUST NOT retain an old DB value solely so a later request can select
it as historical state.

#### Scenario: Concurrent local transaction
- **WHEN** a DataScript request captures `S0` and the connection commits `S1` during evaluation
- **THEN** the request remains correct for `S0`
- **AND** a subsequent request captures `S1`

#### Scenario: Current request completes
- **WHEN** a request using `S0` returns or fails
- **THEN** EACL has no exact-snapshot lease or registry obligation for `S0`

### Requirement: DataScript exact-snapshot support is removed
The DataScript adapter SHALL NOT advertise `:at-exact-snapshot`, issue an exact
snapshot locator, retain an exact DB registry, or accept the client option
`:exact-snapshot-registry-size`. An exact request MUST fail before cache lookup
or authorization evaluation with the typed unsupported capability error.

#### Scenario: Removed client option
- **WHEN** DataScript client construction supplies `:exact-snapshot-registry-size`
- **THEN** construction rejects it as an unknown or removed option with migration guidance

#### Scenario: Exact consistency request
- **WHEN** a caller presents `:at-exact-snapshot` to DataScript
- **THEN** EACL returns `:eacl/unsupported-capability` or the normalized exact-snapshot-unavailable error
- **AND** does not consult a cache as a historical store

#### Scenario: Graph head token
- **WHEN** DataScript emits a causal token for managed current state
- **THEN** the token carries source scope, mutation anchor, and order hint
- **AND** carries no claim that an old immutable DB can later be reconstructed

### Requirement: DataScript consistency modes preserve their guarantees
`:minimize-latency` SHALL select the current complete local DB without an
authoritative wait. `:fully-consistent` SHALL select the current head of the
serialized local connection. `:at-least-as-fresh T` SHALL select a current DB
containing authenticated mutation anchor `T` or wait within the request's
execution deadline and fail typed if the floor cannot be established.

#### Scenario: Minimize latency
- **WHEN** a caller requests `:minimize-latency`
- **THEN** EACL captures the currently available complete `ds/db` once
- **AND** does not return an older retained cache snapshot

#### Scenario: Fully consistent
- **WHEN** a caller requests `:fully-consistent`
- **THEN** EACL captures the current head of the serialized connection as the request linearization point

#### Scenario: At-least floor already present
- **WHEN** the current DB contains token `T`'s same-scope mutation anchor
- **THEN** EACL selects that current DB without waiting

#### Scenario: At-least floor unavailable
- **WHEN** the connection does not expose a current DB containing `T` before deadline
- **THEN** EACL returns the typed freshness/deadline error
- **AND** never answers from an older cache entry

### Requirement: DataScript cache never weakens consistency
DataScript cache lookup SHALL occur only after current snapshot `S` satisfies
the requested consistency mode. A candidate computed at older snapshot `C` MAY
serve `S` only when `C` is a causal ancestor of `S` and complete schema,
relationship, identity, ordering, semantic-key, and ABI validation proves the
answer observationally equivalent on `S`.

#### Scenario: Older proof-equivalent candidate
- **WHEN** candidate `C` predates an at-least floor but selected `S` contains the floor and all complete dependency proofs match
- **THEN** EACL may return the candidate as an answer validated on `S`
- **AND** `:cache-basis` remains `C` while validation is performed against request-local selected `S`

#### Scenario: Relevant raw write under unknown authority
- **WHEN** a raw DataScript transaction changes relevant authorization content without mutation stamps
- **THEN** full-content validation on selected `S` changes or reuse is disabled
- **AND** EACL evaluates on `S` rather than returning the stale candidate

#### Scenario: Relevant managed write
- **WHEN** a managed relationship or schema mutation changes a complete dependency identity
- **THEN** the older candidate misses on the selected current DB

#### Scenario: Cache disabled
- **WHEN** `:cache? false` is supplied with any supported consistency mode
- **THEN** EACL evaluates on the same selected `S` without cache work

### Requirement: DataScript cursors use bounded current-basis proof
A DataScript cursor SHALL continue only on the selected current DB. In default
content, unknown-authority, or no-cache-proof modes, the cursor SHALL bind the
exact current immutable basis and SHALL NOT compute a relationship-content
proof. Any later basis SHALL return a typed stale-cursor or newer-floor
conflict. Only explicit managed mutation-stamp mode MAY use a dependency-scoped
schema/relationship stamp and continue on a newer current basis whose complete
dependency and ordering stamps are equal. DataScript MUST NOT silently restart,
reconstruct an old DB, scan relationship content to mint a cursor, or return a
cached old page.

#### Scenario: Unrelated current transaction
- **WHEN** default content or no-cache-proof mode selects `S1` after a cursor was minted on `S0`
- **THEN** EACL rejects the cursor even when the transaction was unrelated
- **AND** performs no relationship-content scan to attempt cross-basis continuation

#### Scenario: Managed unrelated current transaction
- **WHEN** explicit managed mutation-stamp mode selects `S1` and every complete cursor dependency and ordering stamp equals the cursor minted on `S0`
- **THEN** EACL may resume deterministically on current `S1` without duplicates or omissions
- **AND** the authorization answer remains validated on `S1`

#### Scenario: Relevant relationship change
- **WHEN** managed mutation-stamp mode observes a changed relationship in the cursor dependency closure between pages
- **THEN** EACL rejects continuation as stale
- **AND** the caller may explicitly begin a new enumeration

#### Scenario: Schema or ordering change
- **WHEN** schema generation, identity mapping, adapter fingerprint, or ordering ABI differs
- **THEN** EACL rejects the cursor before traversal

#### Scenario: Newer causal floor
- **WHEN** a cursor is resumed with an at-least token and the selected qualifying current DB has an equal cursor proof
- **THEN** continuation succeeds on that current DB
- **AND** a changed proof returns a typed consistency conflict

### Requirement: DataScript current-only behavior is cross-runtime equivalent
DataScript CLJ and CLJS SHALL expose the same capabilities, option validation,
snapshot selection, cache proof rules, cursor failures, and typed consistency
errors.

#### Scenario: Shared conformance corpus
- **WHEN** CLJ and CLJS replay current, at-least, cache-hit/miss, raw-write, and cursor-mutation traces
- **THEN** public values, provenance, selected graph identity, and typed failures are equivalent


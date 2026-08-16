## MODIFIED Requirements

### Requirement: First page binds graph and execution identity
Every paginated lookup SHALL select one immutable snapshot before scanning. Each cursor SHALL bind under mandatory authentication, and optional advertised encryption, the backend source/branch scope and lifecycle, native revision and exact locator, canonical query scope, engine/adapter/identity/configuration fingerprints, complete schema/relation dependency scope and proof, deterministic direction/position, format version, and optional configured expiry.

#### Scenario: First page uses at-least freshness
- **WHEN** a first-page request selects a snapshot satisfying an at-least token
- **THEN** every emitted cursor identifies that selected snapshot, proof, and stable position

#### Scenario: Cursor is exposed to the caller
- **WHEN** a portable cursor is serialized
- **THEN** its complete query scope, stable external/opaque position, snapshot identity, and proof metadata are authenticated
- **AND** base64 encoding alone is not considered protection

#### Scenario: Dependency proof is too large
- **WHEN** complete dependency evidence would exceed the cursor size bound
- **THEN** the cursor carries a domain-separated digest and rederives the complete closure on continuation
- **AND** does not truncate the dependency set

### Requirement: Exact reconstruction is a fallback
When the selected current/fresh snapshot proof differs from the cursor, EACL SHALL attempt the original exact locator if the request does not require a causally newer floor. On an unreplaced full-history source, cursor age and ordinary forward mutations SHALL NOT make that locator unavailable. If the configured backend cannot reconstruct the exact value, continuation MUST fail rather than use a changed graph.

#### Scenario: Datomic exact fallback after arbitrary forward history
- **WHEN** a cursor proof changed and its original Datomic basis belongs to the same unreplaced source lifecycle
- **THEN** EACL reconstructs the verified `d/as-of` value, synchronizing to the basis first when the local Peer is behind
- **AND** cursor age alone does not reject it

#### Scenario: Datahike temporal-history fallback
- **WHEN** a cursor proof changed and the Datahike source has `:keep-history? true`
- **THEN** EACL reconstructs the verified temporal snapshot by native revision even if a commit record was collected

#### Scenario: Datahike retained-commit fallback
- **WHEN** temporal history is disabled and the cursor's exact commit remains retained
- **THEN** EACL may continue on that exact commit

#### Scenario: Conditionally retained snapshot is unavailable
- **WHEN** a history-disabled backend configuration no longer retains the cursor's exact handle
- **THEN** EACL returns typed exact-snapshot unavailable/stale-cursor behavior
- **AND** does not silently restart or continue on current data

#### Scenario: History replacement invalidates old locators
- **WHEN** source lifecycle rotation records restore, reset, excision, purge, branch replacement, or destructive history replacement
- **THEN** old cursors are rejected for lifecycle mismatch before exact reconstruction

### Requirement: Cursor failures are distinguishable
EACL SHALL distinguish authentication/query-scope failure, configured envelope expiry, source-lifecycle replacement, conditionally retained exact-snapshot absence, changed dependency proof, incompatible newer freshness, and replay resource/deadline failure.

#### Scenario: Cursor authentication fails
- **WHEN** encrypted cursor contents, key id, or authentication tag are invalid
- **THEN** EACL returns `:eacl.pagination/invalid-cursor`

#### Scenario: Configured cursor lifetime expires
- **WHEN** a cursor carrying an explicit expiry is resumed at or after that expiry
- **THEN** EACL returns `:eacl.pagination/expired-cursor`

#### Scenario: Non-expiring cursor grows older
- **WHEN** a cursor carries no expiry and its exact full-history source remains in the same lifecycle
- **THEN** elapsed wall-clock time is not a rejection condition

#### Scenario: Exact storage is conditionally unavailable
- **WHEN** a valid changed-proof cursor depends on a retained handle that its history-disabled backend has genuinely collected
- **THEN** EACL returns the typed exact-snapshot unavailable/stale-cursor outcome

#### Scenario: Replay is bounded
- **WHEN** checkpoint-free exact replay exceeds the configured deadline or resource ceiling
- **THEN** EACL returns the corresponding deadline/resource error rather than cursor expiry

### Requirement: Pagination is differential-tested against an oracle
The shared suite SHALL compare concatenated cursor pages with deterministic uncached enumeration on the original exact graph or a graph having an equal complete dependency proof, including recovery after wall-clock delay, process-local checkpoint eviction, schema/relationship mutation, and backend catch-up.

#### Scenario: Authorization revocation after page one
- **WHEN** a recursive dependency revokes a result after page one
- **THEN** current-proof continuation is rejected
- **AND** a full-history exact fallback preserves the original enumeration without combining old and new results

#### Scenario: Client-local checkpoint is lost
- **WHEN** page continuation runs after client restart or checkpoint eviction with stable key and source lifecycle configuration
- **THEN** exact replay yields the same remaining sequence and boundary validation as the uninterrupted enumeration

#### Scenario: Lagging Datomic Peer receives cursor
- **WHEN** a cursor minted on one Peer names a committed basis ahead of the receiving Peer
- **THEN** bounded exact catch-up followed by `d/as-of` yields the same original enumeration

## RENAMED Requirements

- FROM: `### Requirement: Continuation accepts an equal complete dependency proof`
- TO: `### Requirement: Continuation accepts an equal frame in one lineage`

## MODIFIED Requirements

### Requirement: First page binds graph and execution identity
Every paginated lookup SHALL select one immutable basis before scanning. Each cursor SHALL bind under mandatory authentication and encryption the lineage (source scope and lifecycle), native revision and exact locator, canonical query scope, engine/adapter/identity/configuration fingerprints, the canonical relation closure digest, the frame (certified schema generation and scalar dependency frontier), the sealed plan fingerprint and order ABI, deterministic direction and boundary, format version, and optional configured expiry. The cursor SHALL carry no separately issued equivalence certificate.

#### Scenario: First page uses at-least freshness
- **WHEN** a first-page request selects a basis satisfying an at-least token
- **THEN** every emitted cursor identifies that basis, its lineage and frame, and a stable boundary

#### Scenario: Cursor is exposed to the caller
- **WHEN** a portable cursor is serialized
- **THEN** its scope, boundary, lineage, and frame are authenticated and encrypted
- **AND** base64 encoding alone is not considered protection

#### Scenario: Query or configuration changes
- **WHEN** a cursor is presented with a different operation, query scope, direction, plan fingerprint, engine version, codec, or answer-affecting configuration
- **THEN** EACL rejects it as an invalid cursor

#### Scenario: Relation closure is large
- **WHEN** the canonical relation closure would exceed the cursor size bound
- **THEN** the cursor carries its domain-separated digest and the frame, and continuation rederives the closure from the selected basis's schema
- **AND** MUST NOT truncate the closure

### Requirement: Continuation accepts an equal frame in one lineage
A continuation MAY run on a basis other than the one that minted the cursor only when the selected basis shares the cursor's lineage and its frame equals the cursor's frame. Equal frames SHALL establish, through the scalar-frontier theorem and the reducer read-scope bridge, that the complete deterministic ordered stream for the sealed plan is identical at both bases; the authenticated boundary then selects exactly the remaining suffix or preceding prefix. The decision SHALL be the generated continuation kernel over the canonical encoding of lineage, frame, and closure digest.

#### Scenario: Unrelated transaction occurs between pages
- **WHEN** the selected basis differs from the minting basis only in data outside the cursor's closure and schema
- **THEN** EACL continues on the selected basis without historical reconstruction
- **AND** subsequent cursors bind the selected basis and its frame

#### Scenario: Relevant mutation occurs
- **WHEN** any relation in the cursor's closure or the schema changes
- **THEN** the frames differ and EACL MUST NOT continue on the changed basis with the old boundary

#### Scenario: Relevant content changes away and back
- **WHEN** a relation in the closure is changed and restored
- **THEN** its generation has advanced, the frames differ, and continuation proceeds only through exact fallback

#### Scenario: Frame is a contract violation
- **WHEN** the selected basis's frame violates the adapter contract
- **THEN** continuation treats it as unavailable and proceeds to exact fallback or the typed stale outcome

### Requirement: Exact reconstruction is a fallback
When the selected basis's frame differs from or is unavailable relative to the cursor, EACL SHALL attempt the cursor's original basis only if the request does not require a causally newer floor and the source supports exact selection. Continuation at the original basis SHALL be accepted by identity — equal lineage, revision, and exact locator — without reading a frame there. On an unreplaced full-history source, cursor age and ordinary forward mutations SHALL NOT make that locator unavailable. If the source cannot reconstruct the value, continuation MUST fail rather than use a changed basis.

#### Scenario: Datomic exact fallback after arbitrary forward history
- **WHEN** a cursor's frame changed and its original basis belongs to the same lineage
- **THEN** EACL catches the Peer up when necessary and continues on the verified `d/as-of` value by the cursor's original source scope, lifecycle, revision, and locator identity, without requiring a redundant proof-frame read at that same immutable basis

#### Scenario: Datahike temporal-history fallback
- **WHEN** a cursor's frame changed and the Datahike source has `:keep-history? true`
- **THEN** EACL reconstructs the verified temporal snapshot even if the named commit record was collected

#### Scenario: Datahike retained-commit fallback
- **WHEN** temporal history is disabled and the cursor's named commit remains retained
- **THEN** EACL may continue on that exact commit

#### Scenario: Current-only source
- **WHEN** a DataScript or Datalevin cursor's frame changes after the basis advances
- **THEN** EACL returns a typed stale-cursor result and does not consult a hidden historical registry

#### Scenario: Original basis is unavailable
- **WHEN** the frame changed and a history-disabled conditional backend no longer retains the exact handle
- **THEN** EACL returns typed stale-cursor or exact-snapshot-unavailable
- **AND** MUST NOT silently restart or continue on current data

#### Scenario: History replacement invalidates old locators
- **WHEN** lifecycle rotation records restore, reset, excision, purge, branch replacement, or destructive history replacement
- **THEN** old cursors are rejected for lineage mismatch before exact reconstruction

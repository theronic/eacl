## ADDED Requirements

### Requirement: First page binds graph and execution identity
Every paginated lookup SHALL select one immutable snapshot before scanning. Each cursor SHALL bind under mandatory authentication, and optional advertised encryption, the backend source/branch scope, graph mutation anchor, exact locator, canonical query scope, engine/adapter/configuration fingerprints, complete schema/relation/identity dependency scope and proof, deterministic direction/position, format version, and expiry.

#### Scenario: First page uses at-least freshness
- **WHEN** a first-page request selects a snapshot satisfying an at-least token
- **THEN** every emitted cursor identifies that selected graph, proof, and stable position

#### Scenario: Cursor is exposed to the caller
- **WHEN** a portable cursor is serialized
- **THEN** its scope, stable external/opaque position, and proof metadata are authenticated
- **AND** base64 encoding alone is not considered protection

#### Scenario: Cursor confidentiality is advertised
- **WHEN** an adapter/runtime advertises confidential cursors
- **THEN** the cursor uses authenticated encryption and exposes no plaintext proof or position

#### Scenario: Query or configuration changes
- **WHEN** a cursor is presented with a different operation, query scope, direction, engine version, codec, or answer-affecting configuration
- **THEN** EACL rejects it as an invalid cursor

#### Scenario: Dependency proof is too large
- **WHEN** a complete dependency proof map would exceed the cursor size bound
- **THEN** the cursor carries a domain-separated digest and rederives the complete closure on continuation
- **AND** MUST NOT truncate the dependency set

#### Scenario: Complete proof cannot be recomputed
- **WHEN** continuation cannot recompute the full proof within configured resource bounds
- **THEN** graph-equivalent continuation is unavailable
- **AND** EACL uses exact fallback or returns a typed stale/resource error

### Requirement: Continuation accepts an equal complete dependency proof
A continuation MAY run on a different immutable snapshot when that snapshot has the same complete schema and relationship dependency proof as the cursor. Equal proof SHALL establish that the deterministically ordered authorization result set and cursor position remain observationally equivalent.

#### Scenario: Unrelated transaction occurs between pages
- **WHEN** the current selected snapshot differs from page one only in data outside the cursor's complete authorization dependencies
- **THEN** EACL continues on the current snapshot without historical reconstruction
- **AND** rebases subsequent cursors to the selected snapshot's graph anchor and proof

#### Scenario: Relevant mutation occurs
- **WHEN** any schema or relation dependency in the cursor proof changes
- **THEN** EACL MUST NOT continue on that changed graph using the old position

#### Scenario: Relevant content changes away and back
- **WHEN** mutation-identity proof records intervening relevant churn even though final tuples match
- **THEN** continuation conservatively treats the proof as changed
- **AND** MAY continue only through an available exact snapshot

### Requirement: Exact reconstruction is a fallback
When the selected current/fresh snapshot proof differs from the cursor, EACL SHALL attempt the original exact locator only if the request does not require a causally newer floor. If exact reconstruction is unavailable, continuation MUST fail rather than use a changed graph.

#### Scenario: Datomic exact fallback
- **WHEN** a cursor proof changed and its original Datomic basis remains available
- **THEN** EACL may continue on the verified `d/as-of` value

#### Scenario: Datahike exact fallback
- **WHEN** a cursor proof changed and its original commit or temporal snapshot remains available
- **THEN** EACL may continue on the verified exact Datahike DB

#### Scenario: DataScript retained value
- **WHEN** a cursor proof changed and the original immutable DB remains in the bounded registry
- **THEN** EACL may continue on that retained DB

#### Scenario: Original snapshot is unavailable
- **WHEN** the proof changed and no exact mechanism can recover the original value
- **THEN** EACL returns stale-cursor or snapshot-expired
- **AND** MUST NOT silently restart or continue on current data

### Requirement: Newer freshness and cursor stability are jointly evaluated
A continuation carrying an additional `:at-least-as-fresh` token SHALL first select a snapshot satisfying that token and then compare its complete dependency proof with the cursor proof. A newer floor is compatible when the proofs match and incompatible when they differ.

#### Scenario: Newer token but unchanged graph portion
- **WHEN** the selected snapshot contains the newer token anchor and its cursor dependency proof is equal
- **THEN** EACL continues on that selected snapshot

#### Scenario: Newer token and changed graph portion
- **WHEN** every snapshot satisfying the newer floor has a different cursor dependency proof
- **THEN** EACL returns a typed consistency conflict requiring pagination restart
- **AND** MUST NOT fall back to the older exact snapshot

### Requirement: Cursor ordering is total and deterministic
The pagination engine SHALL define a stable total order and complete tie-breaker over results for one proof-equivalent graph. Backend iteration order, hash-map order, or non-unique positions MUST NOT determine continuation.

#### Scenario: Two results share a primary ordering field
- **WHEN** two authorized results compare equal on the primary field
- **THEN** the cursor order uses deterministic additional fields to distinguish them

#### Scenario: Backend index iteration differs
- **WHEN** two adapters enumerate equivalent relationship tuples in different incidental orders
- **THEN** their shared contract result and continuation order remain canonical

#### Scenario: External object identity changes
- **WHEN** a result or ordering object's public identity changes between pages
- **THEN** identity-boundary proof changes and current-snapshot continuation is rejected
- **AND** the cursor does not reinterpret an old internal position as a different public object

### Requirement: Cursor failures are distinguishable
EACL SHALL distinguish authentication/scope failure, envelope expiry, missing exact snapshot, changed dependency proof, and incompatible newer freshness.

#### Scenario: Cursor authentication fails
- **WHEN** encrypted cursor contents, key id, or tag are invalid
- **THEN** EACL returns `:eacl.pagination/invalid-cursor`

#### Scenario: Cursor lifetime expires
- **WHEN** the authenticated cursor expiry has elapsed
- **THEN** EACL returns `:eacl.pagination/expired-cursor`

#### Scenario: Exact storage retention expires
- **WHEN** a valid changed-proof cursor needs its original snapshot but storage no longer retains it
- **THEN** EACL returns `:eacl.consistency/snapshot-expired`

#### Scenario: Proof changed without exact fallback
- **WHEN** a valid cursor's current proof differs and the backend never supported exact history
- **THEN** EACL returns a typed stale-cursor error

### Requirement: Pagination is differential-tested against an oracle
The shared suite SHALL compare concatenated cursor pages with a deterministic uncached enumeration on the original exact graph or a graph having an equal complete dependency proof.

#### Scenario: Insert before cursor boundary
- **WHEN** a relevant result is inserted before continuation
- **THEN** current-proof continuation is rejected or exact fallback preserves the original enumeration

#### Scenario: Delete after cursor boundary
- **WHEN** a relevant result is deleted after page one
- **THEN** current-proof continuation is rejected or exact fallback preserves the original enumeration

#### Scenario: Unrelated mutation
- **WHEN** arbitrary unrelated schema-external or relation-external data changes
- **THEN** proof-equivalent continuation yields neither duplicates nor omissions

#### Scenario: Authorization revocation
- **WHEN** a recursive dependency revokes a result after page one
- **THEN** EACL never emits a hybrid enumeration spanning the old and new proofs

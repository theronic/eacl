# dependency-validated-authorization-cache Specification

## Purpose
TBD - created by archiving change redesign-cross-backend-freshness-cache. Update Purpose after archive.
## Requirements
### Requirement: Cache validation follows consistency selection
EACL SHALL select one immutable snapshot satisfying the request before cross-revision cache validation. Query internalization, dependency discovery, schema proof, relationship proof, authorization evaluation, result externalization, and response-token issuance MUST use that selected snapshot or explicitly fingerprint any deterministic boundary transformation.

#### Scenario: At-least request finds an old candidate
- **WHEN** a candidate was computed before the requested causal floor
- **THEN** EACL first selects a snapshot containing the floor mutation
- **AND** validates the candidate's complete proof on that snapshot

#### Scenario: Snapshot advances during lookup
- **WHEN** another transaction commits after selection
- **THEN** every read for the in-flight request remains on the selected immutable value

### Requirement: Cache lifting is forward-only
EACL SHALL return a cross-revision candidate only when the selected snapshot contains the candidate's computation mutation anchor and the complete proofs match. Numeric ordering or proof equality alone MUST NOT lift an answer backward from a future or sibling history.

#### Scenario: Candidate causally precedes selected snapshot
- **WHEN** the selected snapshot contains the candidate computation anchor and has an equal complete dependency proof
- **THEN** EACL may proof-lift the answer

#### Scenario: Candidate is from a future sibling
- **WHEN** a shared cache returns a candidate whose computation anchor is absent from the selected snapshot
- **THEN** EACL treats it as a miss even if numeric revisions and dependency proof values compare equal

#### Scenario: Validation telemetry is reused
- **WHEN** an entry records a prior `validated-at` value newer than its computation point
- **THEN** a later cross-revision request still validates the selected snapshot proof
- **AND** MUST NOT treat `validated-at` as a lease

### Requirement: Dependency closure covers possible positive and negative paths
The relationship dependency scope SHALL be the complete static transitive closure of relation definitions that could affect the semantic request under the selected schema. Runtime short-circuiting, current data absence, denial, recursion, cycles, page boundaries, and observed traversal paths MUST NOT narrow that scope.

#### Scenario: Denied answer gains a relationship
- **WHEN** a negative answer was cached and a relation on an unvisited alternative path gains a granting tuple
- **THEN** that relation is present in the candidate dependency scope and invalidates it

#### Scenario: Recursive dependency changes
- **WHEN** any relation reachable through recursive or cyclic permission definitions changes
- **THEN** the recursive answer's proof changes

#### Scenario: Dependency closure is unknown or empty
- **WHEN** EACL cannot establish a complete non-empty closure for a cacheable authorization operation
- **THEN** it evaluates without completed-answer retention

### Requirement: Derived schema caches are snapshot-scoped
Schema catalogs, permission paths, recursive dependency closures, and direct-grant memos SHALL be keyed by selected source scope and schema mutation identity or full-content schema proof. Listener counters and client-lifetime latching MUST NOT authorize reuse across a different selected schema.

#### Scenario: Another client changes schema
- **WHEN** a different connection commits a schema mutation
- **THEN** the next selected snapshot uses a derived cache keyed by the new schema proof
- **AND** does not require the local listener to have fired

#### Scenario: Listener clears a memo
- **WHEN** an adapter retains a listener for eager eviction
- **THEN** missed, duplicate, or delayed callbacks affect latency only

### Requirement: Semantic cache keys are complete
The cache lookup key and embedded authenticated entry SHALL distinguish cache/engine/adapter versions, backend source and branch scope, operation, complete canonical internal query, pagination state, result kind, and every recursion, traversal, count, object-codec, caveat, or adapter option capable of changing the answer.

#### Scenario: Two databases share a cache
- **WHEN** clients for different source scopes use one provider
- **THEN** their entries cannot collide or validate across scopes

#### Scenario: Engine configuration changes
- **WHEN** an answer-affecting traversal limit, codec contract, adapter implementation, or algorithm version changes
- **THEN** the new request cannot reuse the old entry

#### Scenario: Hash collision is attempted
- **WHEN** two canonical queries have the same compact hash
- **THEN** embedded full-key equality prevents substitution

### Requirement: Completed entries are authenticated
Every portable completed-answer entry accepted from a shared or externally writable cache SHALL carry a domain-separated authenticator over its canonical complete key, proof metadata, causal anchors, and value. Authentication failure, unknown key id, malformed fields, or excessive encoded size MUST be a cache miss.

#### Scenario: Provider forges a Boolean grant
- **WHEN** a provider returns a fabricated `true` value with copied proof fields but no valid entry tag
- **THEN** EACL rejects it and evaluates on the selected snapshot

#### Scenario: Provider replays a valid old entry
- **WHEN** a provider replays an authenticated entry
- **THEN** source scope, computation-anchor dominance, semantic key, and current proof validation still apply

#### Scenario: Cache signing key rotates
- **WHEN** an entry uses a retained read key id
- **THEN** EACL may authenticate it during the rotation window
- **AND** all new entries use the current key id

### Requirement: Authorization intermediates are authenticated or recomputed
Any recursive continuation, traversal frontier, schema/path materialization, or pointer read from a shared or externally writable provider SHALL be authenticated and completely scoped before it can influence authorization. Missing, evicted, unauthenticated, or malformed intermediates MUST be recomputed from the selected snapshot.

#### Scenario: Provider forges a recursive frontier
- **WHEN** a provider returns an unauthenticated frontier that would skip part of traversal
- **THEN** EACL ignores it and reconstructs traversal from the selected snapshot

#### Scenario: Continuation cache is evicted
- **WHEN** a valid cursor's optional intermediate cache state is absent
- **THEN** the cursor and selected snapshot contain enough state to recompute continuation correctly
- **AND** pagination does not silently restart

### Requirement: Cache metadata distinguishes computation and validation
A cache entry SHALL preserve `computed-at` as its computation causal anchor and exact locator, and MAY update `validated-at` for telemetry after a proof-valid hit. The response token SHALL identify the selected snapshot's graph head, not the computation point.

#### Scenario: Answer lifts across unrelated changes
- **WHEN** an answer computed at `C` is validated on causally later snapshot `S` with equal proof
- **THEN** `computed-at` remains `C`, `validated-at` becomes `S`, and the response token identifies `S`

#### Scenario: Older concurrent updater wins a provider race
- **WHEN** an older request overwrites newer validation telemetry
- **THEN** correctness remains unchanged because every later cross-revision hit revalidates

### Requirement: Adapter operations are deterministic and dependency-declared
An adapter eligible for completed-answer caching SHALL make authorization primitives and object-id boundary transformations deterministic for one immutable snapshot. Any external mutable input MUST appear in the semantic/configuration fingerprint and dependency proof; otherwise caching and proof-equivalent cursor continuation are disabled.

#### Scenario: Object codec reads external mutable state
- **WHEN** an object-id codec can return different values for the same immutable snapshot without a declared dependency
- **THEN** the adapter is ineligible for completed-answer caching

#### Scenario: Database-backed object identity is mutable
- **WHEN** public-to-internal identity mappings or result identities can change inside the database
- **THEN** the semantic key contains canonical public and selected internal query identities
- **AND** cache validation covers identity proofs for query, result, and ordering objects

#### Scenario: Object identity is declared immutable
- **WHEN** an adapter enforces immutable public identity for the lifetime of an internal object
- **THEN** the immutable-identity contract MAY replace per-entry identity proof

#### Scenario: Adapter is snapshot-pure
- **WHEN** all primitive results are determined by the selected immutable DB and fingerprinted configuration
- **THEN** equal complete dependency proofs may establish observational equivalence

### Requirement: Historical exact entries do not use current-only proof shortcuts
Exact-snapshot reads SHALL bind cache entries to the verified exact locator and source graph identity. EACL MUST NOT validate historical entries by reading current-only or no-history stamps through an historical view.

#### Scenario: Historical no-history stamp is unavailable
- **WHEN** an exact historical DB cannot expose a superseded current-only stamp
- **THEN** EACL uses exact identity plus historical content/mutation state or bypasses completed-answer caching

### Requirement: Cache failures degrade only to selected-snapshot evaluation
Provider errors, malformed entries, absent proofs, and proof-computation failures SHALL become misses and fall back to uncached evaluation on the selected snapshot. Token, causal freshness, source-scope, and exact-snapshot failures MUST remain request errors.

#### Scenario: Cache provider throws
- **WHEN** lookup or storage fails
- **THEN** EACL computes and returns the selected-snapshot answer

#### Scenario: Token anchor is missing
- **WHEN** consistency selection cannot prove the requested mutation is present
- **THEN** EACL rejects the request and MUST NOT hide the failure behind an uncached current read


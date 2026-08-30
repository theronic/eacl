# dependency-validated-authorization-cache Specification

## MODIFIED Requirements

### Requirement: Semantic cache keys are complete

The flat composite lookup key and validated entry SHALL distinguish key,
cache-value, engine, and adapter ABIs; backend source, lifecycle, and branch;
exact basis or complete managed proof; operation; complete canonical internal
query; pagination state; result kind; and every recursion, traversal, count,
object-codec, caveat, or adapter option capable of changing the answer.

#### Scenario: Two databases share a cache

- **WHEN** clients for different source scopes use private caches in one process
- **THEN** their composite keys cannot collide or validate across scopes

#### Scenario: Engine configuration changes

- **WHEN** an answer-affecting traversal limit, codec contract, adapter implementation, or algorithm version changes
- **THEN** the new request cannot reuse the old entry

#### Scenario: Hash collision is attempted

- **WHEN** two canonical queries have the same compact hash
- **THEN** canonical identity equality in the key or validated entry prevents substitution

### Requirement: Cache metadata distinguishes computation and validation

A managed cache entry SHALL preserve its immutable `computed-at` causal anchor
and exact locator. Validation against a later selected snapshot MAY emit
request-local telemetry and a successful lookup MAY update library-managed LRU
metadata, but neither may rewrite the resident entry payload. The response
token SHALL identify the selected snapshot's graph head, not the computation
point.

#### Scenario: Answer lifts across unrelated changes

- **WHEN** an answer computed at `C` is validated on causally later snapshot `S` with equal proof
- **THEN** the resident entry remains unchanged
- **AND** the response token identifies `S`

#### Scenario: Older concurrent updater wins a provider race

- **WHEN** two requests would previously have raced shared `validated-at` updates for one resident value
- **THEN** neither overwrites shared validation telemetry
- **AND** each response describes its own selected snapshot

### Requirement: Cache failures degrade only to selected-snapshot evaluation

Private store errors, absent mappings, managed causal rejection, absent proofs,
and proof-computation failures SHALL become misses and fall back to uncached
evaluation on the selected snapshot when safe. Malformed or operation-invalid
values presented through supported publication or restore SHALL be rejected
before insertion. Direct application mutation of private resident storage is
outside the supported contract. Token, causal freshness, source-scope, and
exact-snapshot failures MUST remain request errors.

#### Scenario: Cache provider throws

- **WHEN** the private store lookup or publication throws before the request deadline
- **THEN** EACL computes or returns the selected-snapshot answer without trusting cache state

#### Scenario: Token anchor is missing

- **WHEN** consistency selection cannot prove the requested mutation is present
- **THEN** EACL rejects the request and MUST NOT hide the failure behind an uncached current read

## REMOVED Requirements

### Requirement: Completed entries are authenticated

**Reason**: Caller-supplied and externally writable cache providers are not a
shipped authorization path. The private in-memory store retains already
validated values. Export/restore accepts an already trusted decoded value and
keeps authentication and encoded-size limits at the host's external-byte
boundary.

**Migration**: Remove provider signing-key and entry-envelope configuration.
Authenticate and size-bound any external snapshot bytes before calling EACL's
typed restore API; restore still validates the complete ABI, key, and value.

### Requirement: Authorization intermediates are authenticated or recomputed

**Reason**: No shared externally writable provider can supply intermediates.
Private continuation and derived values remain fully scoped and validated, and
absence or eviction still triggers deterministic replay.

**Migration**: Use authenticated public cursors plus the client-private
continuation store. External cache-provider frontier formats are unsupported.

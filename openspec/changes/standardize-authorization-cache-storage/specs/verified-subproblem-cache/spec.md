# verified-subproblem-cache Specification

## ADDED Requirements

### Requirement: Subproblem retention is count-bounded and request-independent

Authorization-denotation and completed-answer stores SHALL each have a positive
standard-cache entry capacity and SHALL contain only completely computed,
validated immutable values. Denotation entries SHALL be exact-basis only;
completed answers MAY use exact or managed keys. Physical projection chunks and
direct Boolean probes SHALL remain request-owned work and MUST NOT create a
third shared tier. Each miss SHALL compute under its own request contract and
MAY atomically insert its already completed value. Cache contention may repeat
only library-private mutation, never request work. No cache structure may own, join, wait
for, retry, or bound application callback execution. Failed, cancelled,
invalid, or partial computations MUST NOT publish or poison later requests.
Completed pages above the shared 1,000-result eligibility guard skip both exact
and managed answer publication; denotation artifacts retain their existing
authoritative semantic bounds.

Publication and validated restore SHALL be the only supported entry-installing
transitions. Publication SHALL require an explicit callable value validator and
SHALL NOT default an omitted validator to acceptance. Once either ingress path
establishes the complete key/value invariant, exact subproblem hits SHALL use
ordinary membership without repeating artifact or ABI validation. Managed
completed-answer hits SHALL retain the per-request causal revision comparison
required for forward-only reuse.

Entry capacity, executing semantic work, fetched values, and service admission
SHALL remain distinct measures. Entry count MUST NOT be described as retained
heap bytes, CPU time, backend operations, callback concurrency, or wall time.
Optional service-edge admission remains separate cache-independent control.

#### Scenario: Tier reaches capacity

- **WHEN** an absent valid subproblem is published into a full tier
- **THEN** the runtime policy makes cold mappings eligible for eviction and settled resident count remains at capacity

#### Scenario: Concurrent identical misses

- **WHEN** multiple requests miss the same exact subproblem concurrently
- **THEN** each request independently computes and returns its own valid value
- **AND** no atomic retry re-executes request computation or adopts a peer's request contract

#### Scenario: Generation expires during a computation

- **WHEN** an old-lifecycle computation is still executing and the same tier/key is requested in a new lifecycle
- **THEN** both requests remain independent and the old result cannot publish into the new lifecycle

#### Scenario: An accepted exact subproblem is reused

- **WHEN** validated publication installed an exact subproblem under its complete key
- **THEN** a later exact hit returns the immutable value without invoking its publication validator again

## MODIFIED Requirements

### Requirement: Semantic keys separate every answer-affecting input

Every retained subproblem key SHALL be one flat immutable versioned exact
composite value that commits to the denotation tier, source lifecycle, selected
graph identity, schema and engine identity, identity contract, internal
candidate identity, relation or permission node, bounds, and all contextual
inputs that can alter the denotation. Managed proof identity belongs only to
completed-answer keys. Storage SHALL use ordinary key equality and MUST NOT
inspect proofs, scan nested generations, or match aliases.

#### Scenario: Equal exact denotation identities share a point result

- **WHEN** two evaluations on the same selected graph require the same sealed-plan node, scope, and internal candidate
- **THEN** they construct the same exact denotation composite key even though their completed-answer keys differ

#### Scenario: Distinct principals share an atomic projection

- **WHEN** distinct top-level queries traverse the same physical relationship projection and converge on the same complete exact Boolean-denotation identity
- **THEN** no physical projection artifact is retained, while the completed Boolean point may be reused only through that identical exact denotation key and the completed-answer keys remain distinct

#### Scenario: Context changes

- **WHEN** an identity codec, caveat context, source lifecycle, selected graph, proof, schema, endpoint, direction, or bound changes
- **THEN** the composite key changes and an entry under the prior semantic input is not eligible

### Requirement: Projection chunks preserve backend scan semantics

The engine SHALL keep physical projection chunks request-owned. Their lazy
concatenation SHALL remain the finite, strictly ordered, unique result required
by the backend contract, and evaluation MUST NOT realize the complete
projection beyond the configured chunk boundary merely to populate shared
storage. Physical projection chunks, terminal empty chunks, and direct Boolean
probes MUST NOT be admitted to the shared denotation or answer stores.

#### Scenario: Small page over a wide adjacency list

- **WHEN** a page consumes fewer values than a high-degree relationship projection contains
- **THEN** evaluation realizes only the request-owned bounded chunks demanded by that page and does not materialize the full adjacency list for cache admission

#### Scenario: Negative projection

- **WHEN** a certified backend scan returns no values for a physical projection
- **THEN** the terminal empty result remains request-owned and no shared projection entry is created

### Requirement: Managed reuse has a complete localized frame

Forward-revision reuse SHALL apply only to completed answers and SHALL be
disabled unless the selected backend provides a complete managed-writer proof
for every relationship on which that answer depends. EACL-managed writes SHALL
atomically advance the affected relation and schema identities. Managed-answer
lookup SHALL compare the resident computed revision with the selected request
revision; exact denotation entries SHALL remain bound to one immutable selected
graph and MUST NOT retain managed subproblem proof descriptors.

#### Scenario: Write touches a cached relation

- **WHEN** an EACL relationship write creates, deletes, or changes an edge in a relation used by a managed completed answer
- **THEN** the relation identity changes in the same transaction and the old managed-answer key is not eligible

#### Scenario: Write is outside the proof

- **WHEN** a forward transaction changes only relations absent from a complete managed completed-answer proof
- **THEN** that managed answer remains eligible subject to its request-relative causal revision check and equals recomputation on the selected graph

#### Scenario: Writer authority is unknown

- **WHEN** relationship data may have changed outside the certified EACL writer path
- **THEN** managed completed-answer reuse is disabled while exact answer and exact denotation reuse remain available

## REMOVED Requirements

### Requirement: Retained weight and publication attempts are bounded

**Reason**: Custom logical-weight budgets and generated admission state are
replaced by standard-cache entry capacity and direct completed-value insertion.
The old weight did not establish a byte or whole-process resource bound.

**Migration**: Configure flat positive `:max-entries` for answers and
`:denotation-max-entries` for exact Boolean denotations; remove the nested
`:subproblem-cache` map, answer-only switch, nested capacity/telemetry overrides,
projection-tier, and managed-subproblem-proof settings. Keep physical projection
chunk, page-result, deadline, and semantic work bounds at their existing
authoritative layers. Add measured byte retention later as a distinct strategy
if needed.

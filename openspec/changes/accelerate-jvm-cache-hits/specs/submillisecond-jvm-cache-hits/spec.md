## Purpose

Define the observable performance and work contract for serving already
validated, resident JVM authorization results without repeating miss-path work.

## ADDED Requirements

### Requirement: Resident JVM hits are sub-millisecond Core operations

An uncontended warmed JVM request that finds a compatible, already validated
completed answer and can capture its immutable local basis without provider
synchronization MUST complete the EACL Core operation in less than 1 ms at the
enforced maximum sampled steady-state ceiling. The result MUST be fully
realized at the Core API boundary and include the same page items, relationship
items, page flags, count, decision, permission tree, and cursor inputs as
cache-disabled evaluation. Network
transport, JSON encoding, and provider synchronization MUST be measured
separately and MUST NOT be
reported as cache lookup time.

#### Scenario: Warm 64-result page hit

- **WHEN** an uncontended warmed JVM client serves a compatible resident page
  containing 64 results from a locally available immutable basis
- **THEN** the enforced Core hit latency is below 1 ms
- **AND** the returned ordered page equals cache-disabled evaluation
- **AND** no relationship scan, schema read, plan construction, proof forcing,
  or evaluator traversal occurs
- **AND** no per-item backend identity resolution occurs

#### Scenario: Warm completed count hit

- **WHEN** the same environment serves a compatible resident exact count
- **THEN** the enforced Core hit latency is below 1 ms
- **AND** the count and exactness metadata equal cache-disabled evaluation

#### Scenario: Warm point and permission-tree hits

- **WHEN** the same environment serves a resident point decision or permission
  tree through a canonical public key
- **THEN** the enforced Core hit latency is below 1 ms
- **AND** no backend object-ID lookup, schema construction, or authorization
  traversal occurs

#### Scenario: Warm 64-relationship page hit

- **WHEN** an uncontended warmed JVM client serves a compatible resident
  `read-relationships` page containing 64 results from a locally available
  immutable basis
- **THEN** the enforced Core hit latency is below 1 ms
- **AND** no relationship scan, row rendering, backend object-ID lookup,
  cursor decode, or token construction occurs

### Requirement: Hit latency is attributed by stage

JVM performance evidence MUST separately measure public request normalization,
local basis capture, semantic-key construction, raw store lookup and recency,
eligibility validation, result materialization, cursor construction, mandatory
metrics, optional telemetry, and the complete Core response. Stage measurements
MUST be removable from the production hot path and MUST disclose allocation as
well as elapsed time.

#### Scenario: Full hit exceeds its target while raw lookup is fast

- **WHEN** a completed Core hit exceeds 1 ms but raw resident lookup does not
- **THEN** the evidence identifies the responsible non-storage stages and their
  allocations
- **AND** replacing the storage library alone cannot qualify the change

#### Scenario: Instrumentation is disabled

- **WHEN** production stage profiling is disabled
- **THEN** it performs no observer mutation or per-hit stage allocation
- **AND** authorization results and mandatory resource limits are unchanged

### Requirement: Cache hits do no miss-path semantic work

After compatible key and lifecycle selection, a trusted completed hit MUST NOT
reconstruct or validate data already fixed by the key and publication boundary.
It MUST NOT rebuild schema, seal a plan, resolve proof frames, traverse cached
items, copy an immutable result solely for validation, regenerate item maps, or
invoke the backend. Work required only to externalize an opaque cursor MUST be
bounded independently from the number of authorization paths evaluated on the
original miss.

#### Scenario: Trusted completed page is reused

- **WHEN** a validated completed page is resident under its complete compatible
  key
- **THEN** the hit returns the held immutable page value without item-by-item
  authorization or shape validation
- **AND** only response fields that are intentionally request-specific may be
  constructed

### Requirement: Exact page hits reuse complete authenticated transport

When cursor expiry is disabled, lookup-resource, lookup-subject, and
relationship-read paths MUST retain the complete immutable public page under
the complete exact basis, normalized raw request including its exact boundary
token, full authenticated consistency descriptor including any exact token or
freshness floor, operation, and cursor-key policy. A hit MUST return that value
before cursor decode, identity resolution, proof work, or token construction.
Only a successfully authenticated/evaluated request may publish the
process-private entry. Publication validation MUST enforce the operation's
item type: EACL `SpiceObject` for lookup pages and EACL `Relationship` composed
of valid SpiceObjects for relationship pages; custom records MUST fail closed.
The retained entry MUST rotate with the existing cache lifecycle and MUST NOT
introduce route, boundary, opposite-direction alias, or page-navigation state.

#### Scenario: Sixty-four-item Datomic page hits

- **WHEN** a warmed exact-basis page contains 64 Datomic-backed resources
- **THEN** the hit performs zero `object-id->internal` or
  `internal-id->object` backend calls
- **AND** the returned public item vector equals cache-disabled rendering
- **AND** any returned cursor authenticates and resumes the same next page

#### Scenario: Consistency bounds share a selected basis

- **WHEN** two authenticated consistency descriptors select the same exact
  basis but carry different exact tokens or freshness floors
- **THEN** their transport keys remain distinct
- **AND** neither request can bypass its own ordinary cursor-consistency check

#### Scenario: Retained Snapshot receives a new consistency assertion

- **WHEN** a read through a retained Snapshot supplies an authenticated exact
  token or freshness floor different from the Snapshot creation descriptor
- **THEN** EACL asserts that descriptor against the retained basis and refines
  the read's cursor/cache selection with its authenticated token
- **AND** backend selection facts from Snapshot creation remain preserved

#### Scenario: Cursor expiry policy is enabled

- **WHEN** `:cursor-ttl-seconds` is configured
- **THEN** complete transport-page reuse is bypassed
- **AND** every continued request authenticates its token and applies the
  current request clock before returning a page

#### Scenario: Cursor key policy changes

- **WHEN** the current signing key, retained decode keyring, codec bounds, or
  cursor wire version changes
- **THEN** the former transport entry cannot hit under the new policy identity
- **AND** the raw token follows the ordinary authenticated path

#### Scenario: Custom identity depends on nonportable metadata

- **WHEN** a cursor query scope or emitted boundary identity contains Clojure
  metadata or another value that canonical cursor transport cannot preserve
- **THEN** EACL rejects cursor construction with a typed portable-identity
  error instead of signing an aliased scope or edge
- **AND** cursor token and construction-context caches retain no request-owned
  metadata from a failed or successful publication

#### Scenario: Canonical transport would erase an identity representation

- **WHEN** a cursor object ID is a custom record, list, subvector, map entry,
  alternate integer class, any map/set, JavaScript negative zero, or exceeds
  the depth, entry-count, or character-count cache envelope
- **THEN** EACL rejects the page with a typed portable-identity error
- **AND** an equal vector, integer, map, set, or zero used by another principal
  cannot share its cursor scope or exact transport key

#### Scenario: Safe query collections are retained plainly

- **WHEN** an eligible query uses metadata-free maps, vectors, or sets whose
  collection implementation or comparator is caller-owned
- **THEN** EACL recursively copies them into ordinary persistent containers
  before exact-key publication
- **AND** the cache retains no caller comparator or custom container

#### Scenario: Oversized malformed cursor input

- **WHEN** an unauthenticated raw boundary exceeds the cursor codec's maximum
  size or lacks the current cursor prefix
- **THEN** no exact transport cache lookup or key hash is attempted
- **AND** ordinary bounded decoding preserves the public invalid-cursor result

### Requirement: JVM storage selection is evidence-driven

The JVM cache implementation MUST satisfy bounded recency, non-waiting reads,
safe concurrent eviction, lifecycle replacement, snapshot restore, explicit
absence, and hot-key retention. Selection between implementations MUST compare
representative EACL keys and values under warmed uncontended hits, churn, and
contention; the selected implementation MUST materially improve the complete
Core hit or remove a demonstrated scalability risk, not merely win an isolated
synthetic lookup. Optional hit telemetry MUST NOT reintroduce one global
immutable-state CAS on every JVM hit.

#### Scenario: Alternative store has faster isolated lookup only

- **WHEN** an alternative store improves raw lookup but the complete Core hit
  remains above its target
- **THEN** the storage benchmark alone is insufficient to select it
- **AND** the non-storage hot path must be corrected

#### Scenario: Concurrent eviction follows a held read

- **WHEN** one request obtains a resident immutable value and another request
  evicts the mapping
- **THEN** the first request completes from its held value without blocking or
  changing its authorization result

#### Scenario: Concurrent JVM hits record telemetry

- **WHEN** independent JVM threads read resident answers with telemetry enabled
- **THEN** hit counters use contention-distributed concurrent accumulation
- **AND** the cache value read and its telemetry do not serialize through a
  shared immutable metrics atom

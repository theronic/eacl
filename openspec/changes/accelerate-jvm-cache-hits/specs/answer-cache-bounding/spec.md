## MODIFIED Requirements

### Requirement: Recency-honest eviction and admission

Completed-answer eviction SHALL use a documented bounded policy that combines
frequency and recency, not hash iteration, FIFO, or a separate EACL access
queue. A successful resident lookup SHALL notify the selected cache library so
a repeatedly used key survives a scan of one-use keys. EACL SHALL NOT require
JVM and CLJS libraries to choose the same victim, serialize private policy
state, or reproduce a strict LRU trace. EACL SHALL NOT add repeat-admission
sightings, recency sidecars, stamped queues, tombstones, or compaction around
the library cache.

#### Scenario: Hot key survives churn

- **WHEN** one key is accessed repeatedly while a stream of distinct cold keys
  exceeds the tier's configured capacity
- **THEN** the hot key remains resident after policy maintenance
- **AND** cold one-use mappings are preferred eviction candidates

#### Scenario: Runtime policies choose different cold victims

- **WHEN** JVM and CLJS replay the same portable trace and return the same hit
  and miss outcomes for every key used by the authorization requests under test
- **THEN** a difference in an unobserved cold eviction victim is not a semantic
  mismatch
- **AND** neither runtime exports its private frequency or recency state

#### Scenario: Managed candidate is causally ineligible

- **WHEN** an older request peeks a future managed answer and rejects it by the
  request-relative revision check
- **THEN** that attempted lookup does not deliberately refresh the unusable
  mapping

#### Scenario: Repeat admission at scale

- **WHEN** the keyspace exceeds the tier capacity by 50 times and some keys are
  repeatedly accessed while most are seen once
- **THEN** the selected library's frequency/recency policy admits and retains
  repeated keys without a fixed EACL-managed sighting set

### Requirement: One store implementation for answers and subproblems

Completed internal answers, exact transport-page data, and exact denotation
subproblems SHALL use the same private bounded cache-storage boundary, with
workload-isolated capacities only where configured. Exact and managed answer
eligibility SHALL be represented by flat opaque composite keys, not nested
generation stores. An exact transport-page entry SHALL contain only the
already-validated complete public lookup or relationship-read page for a
non-expiring cursor policy. Its complete key SHALL include the exact basis,
normalized raw request including the exact boundary token, full authenticated
consistency descriptor including any exact token or freshness floor,
cursor-key policy, operation, and every result-affecting ABI and limit. It
SHALL NOT retain a request clock, deadline, cancellation token, mutable
adapter, route alias, or navigation index. Managed semantic answers remain
renderable against the selected basis when identity immutability is not part
of their contract. The process-local rendered tier SHALL rotate with the same
cache lifecycle but SHALL NOT enter portable semantic answer snapshots.
Miss computation remains request-owned and publication contains no application
callback. Rendered key inputs and object IDs inside retained values SHALL be
metadata-free so metadata-hidden mutable/request objects and
metadata-sensitive custom identity semantics cannot alias an exact
presentation entry. The retained page MAY use EACL's known immutable
`SpiceObject` wrapper for lookup items and its known immutable `Relationship`
wrapper, composed from valid `SpiceObject` values, for relationship reads; any
custom record MUST be rejected. Every cursor object ID SHALL already use the
representation produced by canonical transport: bounded canonical scalars and
ordinary vectors rather than lists, map entries, or subvectors, plus the
canonical integer representation. Map and set IDs SHALL be rejected outright
because comparator and implementation provenance are not portable. Ordinary
request query maps, vectors, and sets MAY participate only after recursive
copying into plain persistent containers. EACL SHALL reject an alternate or
oversized representation that compares equal or encodes identically but a
deterministic custom codec can distinguish.

For `can?`, both count operations, and permission-tree expansion, EACL SHALL
probe an exact public semantic key before backend ID internalization only when
the adapter is deterministic, its identity contract promises immutable and
injective external identities, and every public object ID is canonical and
bounded as above. Other identity contracts and ID representations SHALL use
the ordinary internal path; permission-tree completed caching SHALL be
disabled when no safe public key is available.

A successful validated absent-key insertion SHALL be the cache-publication
linearization point. Cancellation or deadline observed before insertion SHALL
skip publication; a signal racing after insertion MAY suppress the current
response under its execution contract but SHALL NOT retract the validated
immutable mapping.

#### Scenario: Exact transport page is resident

- **WHEN** the same complete raw page request selects the same exact immutable
  basis and cursor-key policy after a completed page has been published
- **THEN** EACL returns the resident complete public page before cursor decode
- **AND** it performs no input/output identity resolution, proof work, or token
  construction

#### Scenario: Exact relationship page is resident

- **WHEN** the same complete raw `read-relationships` request selects the same
  exact immutable basis and cursor-key policy after its page was published
- **THEN** EACL returns the operation-typed resident `Relationship` page before
  cursor decode
- **AND** it performs no backend identity resolution, proof/schema work, row
  rendering, or token construction

#### Scenario: Public point or aggregate answer is resident

- **WHEN** a point, count, or permission-tree request uses a deterministic
  immutable/injective identity adapter and bounded canonical public IDs
- **THEN** EACL probes the complete public semantic key before backend ID
  internalization
- **AND** a hit performs zero identity lookups

#### Scenario: Cursor expiry is configured

- **WHEN** `:cursor-ttl-seconds` is positive
- **THEN** EACL bypasses exact transport-page lookup and publication
- **AND** the ordinary authenticated path applies the current expiry decision

#### Scenario: A foreign input cursor carries expiry

- **WHEN** a non-TTL client authenticates a still-live cursor whose payload has
  an expiry
- **THEN** EACL does not publish that raw cursor request into the transport tier
- **AND** the identical request is decoded and rejected after its expiry

#### Scenario: Equal host values have different codec authority

- **WHEN** two object IDs compare equal as Clojure keys or encode to the same
  canonical bytes but differ by record, sequential, integer, map/set, or signed
  zero representation, or exceed the bounded ID envelope
- **THEN** only a bounded canonical scalar/vector representation may enter a
  cursor scope, emitted edge, or exact transport key; all map/set IDs fail
  closed
- **AND** an alternate representation fails with
  `:eacl.pagination/unsupported-cursor-identity` before a token is signed

#### Scenario: Caller query uses a custom collection implementation

- **WHEN** an otherwise eligible request supplies sorted or custom query
  maps/sets whose comparator or implementation is caller-owned
- **THEN** EACL recursively copies the successful-query identity into ordinary
  persistent containers before retaining the key
- **AND** no caller comparator or collection implementation remains resident

#### Scenario: Late cancellation follows publication

- **WHEN** a validated immutable mapping has been inserted and cancellation or
  deadline is observed before the response completes
- **THEN** the request follows its normal cancellation/deadline outcome
- **AND** the already linearized cache mapping remains safe for later requests

#### Scenario: An oversized raw boundary is unauthenticated

- **WHEN** `:after` or `:before` is not a plausible bounded cursor token
- **THEN** EACL bypasses exact transport lookup without hashing the raw value
  into a cache key
- **AND** the ordinary decoder returns the established typed cursor error

#### Scenario: Managed answer crosses a mutable external-identity basis

- **WHEN** a managed semantic answer is reusable but the identity contract does
  not promise immutable external identities
- **THEN** EACL renders that semantic answer against the selected basis before
  publishing an exact transport entry
- **AND** no transport entry is reused under a different exact basis

#### Scenario: Unified layering

- **WHEN** a managed completed answer is eligible after an exact transport-page
  miss for the same semantic request
- **THEN** managed lookup and exact transport publication use the same explicit
  keyed storage operations used by exact answers and denotations
- **AND** no second eviction, generation, or admission implementation exists

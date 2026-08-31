# authorization-request-efficiency Specification

## ADDED Requirements

### Requirement: Completed pages have one semantic tier and one exact transport tier

For an enabled, eligible lookup-resource, lookup-subject, or relationship-read
page request, EACL SHALL retain semantic answers and MAY retain one complete
transport-page value under its full exact-basis raw request identity through
the same bounded lifecycle when cursor expiry is disabled. The key SHALL
include the exact boundary token, full authenticated consistency descriptor
including any exact token or freshness floor, operation, and cursor-key policy;
the value SHALL contain the complete immutable public page published only after
authentication/evaluation and operation-typed validation. Lookup pages MAY use
EACL's known immutable `SpiceObject` wrapper; relationship-read pages MAY use
EACL's known immutable `Relationship` wrapper composed from valid
SpiceObjects. Custom records MUST fail closed. The value MUST NOT contain a
clock, deadline, cancellation object, adapter, source, or request object.
Lookup SHALL occur before cursor decode and any miss-only object-identity
internalization. Transport key inputs and object IDs inside retained values
SHALL be metadata-free portable data.
Metadata-bearing custom identities MUST bypass this tier so hidden
mutable/request state cannot alias or be retained. Cursor query scopes and
emitted edges MUST themselves use canonical portable object-ID
representations. EACL MUST reject metadata, custom records, non-vector
sequentials, alternate integer representations, all map/set IDs, signed zero,
and IDs outside the hot-key depth/entry/character envelope instead of signing a
canonical value that omits codec-significant identity. Ordinary request query
maps, vectors, and sets MUST be copied recursively into plain persistent
containers before they enter a retained key.
Cursor token and
construction-context stores MUST retain canonical metadata-free copies of
request-derived keys and values. A configured cursor TTL MUST bypass complete
transport-page lookup and publication. A cursor carrying an authenticated
expiry MUST suppress transport publication under a non-TTL receiving policy,
and noncanonical custom identities MUST be rejected before canonicalization can
erase their representation. An implausible or oversized unauthenticated raw
boundary MUST bypass transport lookup before cache-key hashing and continue to
the ordinary bounded decoder.
EACL SHALL NOT create a page cache state machine containing routes, page
boundaries, opposite-direction aliases, access queues, or digest records. An
unseen opposite-direction or nonadjacent page MAY therefore recompute once; the
result and cursor contract MUST equal deterministic cache-free execution.

#### Scenario: Repeated identical exact page

- **WHEN** a transport page remains resident and the identical exact-basis raw request and cursor-key policy are requested again
- **THEN** EACL returns its complete public page before cursor decode
- **AND** performs no input/output identity conversion, proof/dependency reconstruction, or token construction

#### Scenario: Repeated identical relationship page

- **WHEN** an operation-typed relationship page remains resident and the
  identical exact-basis raw request, consistency descriptor, and cursor-key
  policy are requested again
- **THEN** EACL returns its complete `Relationship` page before cursor decode
- **AND** performs no row rendering, backend ID conversion, proof/schema work,
  or token construction

#### Scenario: Public semantic exact hit

- **WHEN** a point, count, or permission-tree request uses bounded canonical
  public IDs with a deterministic immutable/injective identity adapter
- **THEN** EACL probes its exact semantic key before backend ID internalization
- **AND** a hit performs zero backend identity calls

#### Scenario: First unseen reverse route

- **WHEN** no completed internal answer or private continuation exists for the first reverse request
- **THEN** EACL recomputes without consulting a shared page-navigation cache
- **AND** returns the same ordered page and authenticated cursor as cache-free evaluation

## MODIFIED Requirements

### Requirement: Exact cache correctness is independent of recency and telemetry mutation

A reader that has obtained an immutable exact entry installed by validated
ingress SHALL be able
to complete even if the store mapping is concurrently evicted. Obtaining a
valid immutable exact hit MAY notify the selected library's frequency/recency
policy, but that mutation MUST affect retention only.
Recency, optional diagnostics, and occupancy counters MUST NOT decide semantic
eligibility, basis identity, limit outcomes, or publication correctness. The
hit transition MUST NOT invoke semantic computation or mutate the held value.
Disabling optional observation SHALL perform zero observer mutation while
leaving the required library access update and mandatory resource counters active.

#### Scenario: Exact entry is evicted during a read

- **WHEN** one request holds an immutable exact entry and another request concurrently evicts its store mapping
- **THEN** the first request completes from its held value with the same result as fresh evaluation

#### Scenario: Optional telemetry is disabled

- **WHEN** optional diagnostics are disabled
- **THEN** the hit performs no observer mutation
- **AND** answer, denotation, and continuation diagnostic counters remain unchanged while required library access policy still updates
- **AND** cache eligibility, exact answers, deadlines, and mandatory resource limits remain unchanged

## REMOVED Requirements

### Requirement: Finite cache decisions use a mechanically checked specialization

**Reason**: Storage is now an ordinary bounded partial map. There is no
authorization-bearing stage/availability policy decision to generate or
specialize; key construction and artifact validation remain the semantic
boundaries and are covered directly by conformance evidence.

**Migration**: Delete the generated current-cache policy artifact and host
specialization. Retain differential and formal evidence for composite keys,
value validity, cache-free equivalence, and lifecycle detachment.

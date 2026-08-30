# authorization-request-efficiency Specification

## ADDED Requirements

### Requirement: Completed internal pages are the only shared page-result cache

EACL SHALL retain at most the completed internal authorization page keyed by
its full semantic request identity. Cursor or Relay externalization SHALL be
performed for the current response and SHALL NOT create a second shared cache
of externalized pages, routes, page boundaries, aliases, or digest records.
An unseen opposite-direction or nonadjacent page MAY therefore recompute once;
the result and cursor contract MUST equal deterministic cache-free execution.

#### Scenario: Repeated identical internal page

- **WHEN** a completed internal page remains resident and the identical semantic page is requested again
- **THEN** EACL may reuse that one internal value and externalize it for the current response

#### Scenario: First unseen reverse route

- **WHEN** no completed internal answer or private continuation exists for the first reverse request
- **THEN** EACL recomputes without consulting a shared page-navigation cache
- **AND** returns the same ordered page and authenticated cursor as cache-free evaluation

## MODIFIED Requirements

### Requirement: Exact cache correctness is independent of recency and telemetry mutation

A reader that has obtained an immutable exact entry installed by validated
ingress SHALL be able
to complete even if the store mapping is concurrently evicted. Obtaining a
valid immutable exact hit MAY atomically install the standard library's LRU hit
state in the local cache atom, but that mutation MUST affect retention only.
Recency, optional diagnostics, and occupancy counters MUST NOT decide semantic
eligibility, basis identity, limit outcomes, or publication correctness. The
hit transition MUST NOT invoke semantic computation or mutate the held value.
Disabling optional observation SHALL perform zero observer mutation while
leaving the required LRU update and mandatory resource counters active.

#### Scenario: Exact entry is evicted during a read

- **WHEN** one request holds an immutable exact entry and another request concurrently evicts its store mapping
- **THEN** the first request completes from its held value with the same result as fresh evaluation

#### Scenario: Optional telemetry is disabled

- **WHEN** optional diagnostics are disabled
- **THEN** the hit performs no observer mutation
- **AND** answer, denotation, and continuation diagnostic counters remain unchanged while required LRU recency still updates
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

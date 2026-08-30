# nonblocking-cache-coordination Specification

## MODIFIED Requirements

### Requirement: Cache attempts have an independent finite envelope

Every enabled shared storage lookup SHALL consist of finite composite-key
construction and explicit map membership; after its managed key is constructed,
managed storage lookup additionally performs one finite causal-revision
comparison.
Artifact, operation, and ABI validation SHALL occur on supported publication
or restore ingress, not repeatedly on exact hits. The LRU storage and its atomic
transitions MUST have zero authority to issue backend commands. Request-scoped
managed-key construction MAY acquire one bounded certified proof frame over
already discovered canonical relation dependencies; it MUST NOT discover new
dependencies or scan relationship projections solely for reuse. A candidate
requiring such additional discovery or scanning SHALL be a miss. Every
completed publication MUST receive an explicit callable validator and MAY
invoke one local atomic absent-key update. No publication entry point may
silently substitute an accept-all validator. The atom
primitive MAY retry a pure standard-cache hit or miss transformation under
contention, but MUST NOT invoke semantic computation, validation, I/O, a
request callback, or another request's result.

LRU capacities SHALL be validated positive integer entry counts at client
construction. Caller-supplied cache providers SHALL be rejected. There SHALL
be no public weight, recency, repeat-admission, publication-attempt, remote
candidate, decompression, or loader configuration. No cache setting may enlarge
semantic demand or the request deadline.

#### Scenario: Caller supplies a provider store

- **WHEN** client construction supplies a cache provider or nested provider store
- **THEN** EACL rejects it with the typed unsupported-provider configuration error
- **AND** no decorative or unbounded provider path enters the authorization contract

#### Scenario: Insufficient cache headroom

- **WHEN** cancellation or the absolute request deadline is already observed before cache access
- **THEN** EACL skips cache access and follows the ordinary selected-snapshot request outcome

#### Scenario: Candidate proof is unavailable

- **WHEN** a candidate can be validated only by discovering dependencies or scanning relationship projections beyond the request's already discovered bounded dependency set
- **THEN** EACL performs no such discovery or scan for cache eligibility
- **AND** evaluates through the cache-independent semantic command trace

#### Scenario: Managed key uses a certified proof frame

- **WHEN** an exact answer misses and the request already has a complete bounded canonical relation-dependency set
- **THEN** managed-key construction may issue one certified proof-frame command over that set
- **AND** LRU membership, causal eligibility, recency, and publication remain local and I/O-free

#### Scenario: Invalid LRU capacity

- **WHEN** an enabled shared tier is configured with a non-positive, fractional, non-finite, or cross-runtime-unsafe integer capacity
- **THEN** client construction rejects the configuration before serving requests

#### Scenario: Cache envelope configuration

- **WHEN** a client supplies removed evaluation-reserve, publication-attempt, weight, recency, or repeat-admission fields
- **THEN** construction rejects the unsupported fields instead of carrying two cache-policy models
- **AND** positive LRU capacities remain the only cache-attempt configuration

#### Scenario: Publication state changed

- **WHEN** publishing an already computed artifact observes that the captured tier state is no longer current
- **THEN** only the pure absent-key LRU transformation may be retried against current state
- **AND** EACL returns the completed public result without rerunning computation

#### Scenario: Publication would exceed the remaining envelope

- **WHEN** cancellation, deadline, lifecycle validity, or the completed-page result-count guard makes an already completed authorization answer, exact denotation, or continuation checkpoint ineligible at publication
- **THEN** EACL skips publication and returns the completed public result when its request contract permits
- **AND** request-independent derived-schema and cursor-codec artifacts remain governed by their own closed value, identity, authentication, and expiry contracts

### Requirement: Publication is bounded and best effort

Cache publication SHALL validate the artifact type, ABI, key agreement,
page-result eligibility, and captured lifecycle before constructing the next
LRU value. It MAY then invoke one local atomic absent-key swap whose pure state
function can be retried by the atom primitive. A same-key winner, validation,
capacity configuration, or lifecycle failure SHALL skip or reject insertion
without changing the authorization result; ordinary capacity pressure MAY
evict the least recently used mapping.

A latest-progress continuation MAY instead use a callback-free expected-value
replacement composed only from standard cache membership, lookup, eviction,
and miss transformations. Progress comparison SHALL remain outside the atom;
if the expected mapping changed, the semantic layer may re-read and compare it
again without rerunning replay or other request computation. Publication peeks
and failed/stale offers SHALL NOT record LRU use; only actual retrieval and a
successful insertion or replacement may update recency.

#### Scenario: Concurrent publication race

- **WHEN** two requests publish valid values for the same exact semantic key
- **THEN** at most one mapping is retained and the other already completed value is not installed
- **AND** neither request adopts the other request's computation, deadline, failure, or result object

#### Scenario: Publication contention bound

- **WHEN** the cache-tier reference changes during publication
- **THEN** the atom primitive may retry only its finite pure LRU transform
- **AND** no retry repeats request computation and the already computed authorization result remains held

#### Scenario: Oversized artifact

- **WHEN** a completed page contains more than 1,000 public result items
- **THEN** EACL skips publication without additional semantic computation

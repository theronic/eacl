# nonblocking-cache-coordination Specification

## MODIFIED Requirements

### Requirement: Cache attempts have an independent finite envelope

Every enabled shared storage lookup SHALL consist of finite composite-key
construction and explicit map membership; after its managed key is constructed,
managed storage lookup additionally performs one finite causal-revision
comparison.
Artifact, operation, and ABI validation SHALL occur on supported publication
or restore ingress, not repeatedly on exact hits. Cache storage and its local
transitions MUST have zero authority to issue backend commands. Request-scoped
managed-key construction MAY acquire one bounded certified proof frame over
already discovered canonical relation dependencies; it MUST NOT discover new
dependencies or scan relationship projections solely for reuse. A candidate
requiring such additional discovery or scanning SHALL be a miss. Every
completed publication MUST receive an explicit callable validator and MAY
invoke one local atomic absent-key update. No publication entry point may
silently substitute an accept-all validator. A runtime cache operation under
contention MUST NOT invoke semantic computation, validation, I/O, a request
callback, or another request's result. JVM `getIfPresent` reads SHALL be
nonblocking; the contract does not classify Caffeine's buffered maintenance or
eviction as wholly lock-free.

Cache capacities SHALL be validated positive integer entry counts at client
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
- **AND** cache membership, causal eligibility, access-policy recording, and publication remain local and I/O-free

#### Scenario: Invalid cache capacity

- **WHEN** an enabled shared tier is configured with a non-positive, fractional, non-finite, or cross-runtime-unsafe integer capacity
- **THEN** client construction rejects the configuration before serving requests

#### Scenario: Cache envelope configuration

- **WHEN** a client supplies removed evaluation-reserve, publication-attempt, weight, recency, or repeat-admission fields
- **THEN** construction rejects the unsupported fields instead of carrying two cache-policy models
- **AND** positive entry capacities remain the only cache-attempt configuration

#### Scenario: Publication state changed

- **WHEN** publishing an already computed artifact observes a concurrent mapping change
- **THEN** only the library's atomic absent-key operation may retry or lose the race
- **AND** EACL returns the completed public result without rerunning computation

#### Scenario: Publication would exceed the remaining envelope

- **WHEN** cancellation or deadline is observed before insertion, or lifecycle validity or the completed-page result-count guard makes an already completed authorization answer, exact denotation, or continuation checkpoint ineligible at publication
- **THEN** EACL skips publication and returns the completed public result when its request contract permits
- **AND** request-independent derived-schema and cursor-codec artifacts remain governed by their own closed value, identity, authentication, and expiry contracts

### Requirement: Publication is bounded and best effort

Cache publication SHALL validate the artifact type, ABI, key agreement,
page-result eligibility, and captured lifecycle before invoking the cache
operation. It MAY then invoke one local atomic absent-key insertion. A same-key winner, validation,
capacity configuration, or lifecycle failure SHALL skip or reject insertion
without changing the authorization result; ordinary capacity pressure MAY
make cold mappings eligible for the runtime library's eviction policy.
The successful validated absent-key insertion SHALL be the publication
linearization point. A cancellation or deadline signal racing after that point
MAY still suppress the current response under the request execution contract,
but SHALL NOT retract the already validated immutable mapping.

A latest-progress continuation MAY instead use a callback-free expected-value
replacement composed only from standard cache membership, lookup, eviction,
and conditional replacement. Progress comparison SHALL remain outside cache mutation;
if the expected mapping changed, the semantic layer may re-read and compare it
again without rerunning replay or other request computation. Publication peeks
and failed/stale offers SHALL NOT deliberately record access; only actual
retrieval and a successful insertion or replacement may update library policy.

#### Scenario: Concurrent publication race

- **WHEN** two requests publish valid values for the same exact semantic key
- **THEN** at most one mapping is retained and the other already completed value is not installed
- **AND** neither request adopts the other request's computation, deadline, failure, or result object

#### Scenario: Publication contention bound

- **WHEN** the mapping changes during publication
- **THEN** the library may retry or fail only its finite local conditional operation
- **AND** no retry repeats request computation and the already computed authorization result remains held

#### Scenario: Late response cancellation

- **WHEN** publication has linearized and a cancellation or deadline signal is
  observed before the response is returned
- **THEN** the request follows its normal cancellation/deadline outcome
- **AND** the safe immutable mapping remains eligible for later requests

#### Scenario: Oversized artifact

- **WHEN** a completed page contains more than 1,000 public result items
- **THEN** EACL skips publication without additional semantic computation

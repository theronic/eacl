# nonblocking-cache-coordination Specification

## Purpose
TBD - created by archiving change demand-bounded-authorization-execution. Update Purpose after archive.
## Requirements
### Requirement: Authorization does not wait for cache computation
No authorization request SHALL wait for another request to compute, admit,
publish, evict, expire, or invalidate a cache artifact. EACL MUST NOT use a
cache-only blocking semaphore, single-flight result join, or monitor spanning
backend/user computation on the authorization path.

#### Scenario: Concurrent identical cold misses
- **WHEN** multiple demand requests miss the same exact key concurrently
- **THEN** each request may evaluate independently
- **AND** none waits for another request's result or error

#### Scenario: Broad and narrow compatible requests
- **WHEN** a narrow point or bounded count overlaps a complete-denotation computation
- **THEN** the narrow request does not join or wait for the broader work
- **AND** stops at its own demand boundary

#### Scenario: Complete-mode collision
- **WHEN** two explicit complete-denotation requests miss concurrently
- **THEN** EACL may duplicate their computation and race publication
- **AND** does not couple either request's deadline or failure to the other

### Requirement: Projection cache stores exact command responses
The generated evaluator SHALL be the only authority that chooses adapter scan
direction, bound, inclusivity, maximum response size, and continuation. A
projection-cache entry SHALL be keyed by the complete validated command and
return exactly one previously validated response to that command.

#### Scenario: Exact command hit
- **WHEN** a cache contains response `R` for the complete selected-snapshot command `C`
- **THEN** the evaluator receives exactly `R` without invoking the backend
- **AND** metrics count the exact avoided backend command and values

#### Scenario: Command differs by bound
- **WHEN** direction, endpoint, cursor, inclusivity, limit, schema, source, or adapter ABI differs
- **THEN** the prior response cannot satisfy the new command

#### Scenario: Cold command miss
- **WHEN** command `C` misses
- **THEN** EACL invokes the backend exactly with `C`
- **AND** cache code neither widens `C` nor recursively requests another command

### Requirement: Cache attempts have an independent finite envelope
Every cache-enabled request SHALL use the finite cache controls consumed by the
shipped client-private implementation: a positive evaluation reserve and a
bounded number of local atomic publication attempts.
When the remaining request budget cannot preserve the evaluation reserve, EACL
SHALL skip cache access. Per-tier and per-entry native-weight ceilings SHALL be
validated at client construction and publication. Exhausting any of these
bounds SHALL stop cache work and continue with cache-disabled evaluation on the
already selected snapshot when the request deadline and consistency contract
still permit it.

Caller-supplied cache providers SHALL be rejected at construction. The v8
certification target SHALL NOT claim provider cancellation, remote candidate
enumeration, streaming byte limits, decompression limits, or decoded-weight
limits because no shipped authorization path implements those behaviors.

Cache eligibility and proof lifting MUST have zero authority to issue backend
commands solely for cache reuse. A candidate requiring proof work not already
available from snapshot selection, ordinary cache-free schema/plan work, the
demand trace, or bounded cache metadata SHALL be a miss.

#### Scenario: Insufficient cache headroom
- **WHEN** the request's remaining deadline cannot preserve the documented evaluation reserve
- **THEN** EACL skips cache access and begins ordinary selected-snapshot evaluation

#### Scenario: Caller supplies a provider store
- **WHEN** client construction supplies a cache provider or a nested provider store
- **THEN** EACL rejects it with the typed unsupported-provider configuration error
- **AND** no decorative or unbounded provider path enters the authorization contract

#### Scenario: Candidate proof is unavailable
- **WHEN** a candidate can be validated only by an additional dependency scan
- **THEN** EACL performs no scan for cache eligibility
- **AND** evaluates through the cache-independent semantic command trace

#### Scenario: Cache envelope configuration
- **WHEN** a client configures evaluation reserve or publication attempts
- **THEN** every supported cache-attempt bound has a finite documented default and validated positive range
- **AND** no per-request cache setting can enlarge semantic demand

#### Scenario: Publication would exceed the remaining envelope
- **WHEN** publishing an already computed artifact would exceed its native-weight, local-attempt, lifecycle, or deadline bound
- **THEN** EACL skips publication and returns the completed public result

### Requirement: Publication is bounded and best effort
Cache publication SHALL validate artifact type, key, retained weight, and
lifecycle before making a bounded non-waiting atomic publication attempt.
Contention, capacity, eviction, validation, or lifecycle failure SHALL skip or
reject publication without changing the authorization result.

#### Scenario: Concurrent publication race
- **WHEN** two requests publish valid values for the same exact semantic key
- **THEN** one compatible value may win and the loser is discarded
- **AND** neither request waits for cache ownership

#### Scenario: Publication contention bound
- **WHEN** the cache-generation reference changes repeatedly during publication
- **THEN** EACL abandons publication after the configured bounded attempt envelope
- **AND** returns the already computed authorization result

#### Scenario: Oversized artifact
- **WHEN** an artifact exceeds its per-entry or tier budget
- **THEN** EACL rejects publication without additional semantic computation

### Requirement: Lifecycle replacement detaches old work
Cache expiry or schema/source lifecycle replacement SHALL atomically install a
new generation. In-flight requests MAY retain the old immutable generation,
but late publication MUST remain unreachable from the new generation.

#### Scenario: Expiry during evaluation
- **WHEN** cache expiry installs generation `G1` while a request holds `G0`
- **THEN** the request may finish against its selected snapshot
- **AND** any later `G0` publication cannot appear in `G1`

#### Scenario: Source incarnation changes
- **WHEN** restore, reset, excision, branch replacement, or destructive source replacement changes source incarnation
- **THEN** new cache and cursor keys use the new incarnation
- **AND** old publications cannot cross into it

### Requirement: Cache failures fall back only within the selected snapshot
EACL SHALL isolate cache failures without changing the request's selected
snapshot or consistency outcome.
Lookup corruption, invalid entries, eviction, capacity rejection, and local
cache errors SHALL become cache misses and fall back to the same selected-snapshot
evaluation. Consistency selection, token, source-scope, cursor-authentication,
and deadline failures MUST remain request errors.

#### Scenario: Private cache lookup fails before deadline
- **WHEN** a client-private cache lookup detects a malformed entry or throws while the request still has execution budget
- **THEN** EACL records the cache failure and evaluates on the already selected snapshot

#### Scenario: Freshness failure
- **WHEN** the selected source cannot satisfy an at-least token before deadline
- **THEN** EACL returns the typed consistency/deadline error
- **AND** does not hide it behind current cache or cache-free evaluation

### Requirement: Request admission is cache-neutral
Any concurrency or overload admission limit SHALL apply before cache selection
with the same policy for cache-enabled and cache-disabled requests. Admission
MUST NOT be described as cache single-flight or alter evaluator demand.

#### Scenario: Saturated admission
- **WHEN** a request cannot obtain uniform execution admission within its documented policy
- **THEN** EACL returns the typed overload or deadline error before cache access
- **AND** the outcome is the same for `:cache? true` and false

### Requirement: Cache telemetry is honest
Metrics SHALL distinguish completed hits, misses, bypasses, local failures,
publication admissions/rejections/races/contention, detached publications,
oversized rejections, exact avoided commands, and fetched values. A concurrent
publication observed after a miss
MUST NOT be reported as if the initiating request began with a completed hit.

#### Scenario: Miss races another publication
- **WHEN** a request misses and computes while another request publishes first
- **THEN** telemetry records the initiating request's miss and publication race
- **AND** does not count a completed cache hit


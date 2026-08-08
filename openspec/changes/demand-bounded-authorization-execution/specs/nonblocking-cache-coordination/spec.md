## ADDED Requirements

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
Every cache-enabled request SHALL apply finite client-configured bounds to cache
stage duration, provider bytes read, decoded artifact weight, candidates
inspected, serialization/compression/hash/eviction work, and local atomic
attempts. The cache-stage deadline MUST be no later than the request deadline.
The effective cache-stage budget MUST leave a positive documented evaluation
reserve; when the remaining request budget cannot fund both, EACL SHALL skip
cache access. Exhausting an envelope SHALL stop cache work and continue with
cache-disabled evaluation on the already selected snapshot when the request
deadline and consistency contract still permit it.

Cache eligibility and proof lifting MUST have zero authority to issue backend
commands solely for cache reuse. A candidate requiring proof work not already
available from snapshot selection, ordinary cache-free schema/plan work, the
demand trace, or bounded cache metadata SHALL be a miss.

#### Scenario: Slow provider
- **WHEN** a provider does not complete lookup within the cache-stage deadline
- **THEN** EACL cancels or abandons the lookup within the provider contract
- **AND** preserves the remaining request budget for ordinary evaluation

#### Scenario: Insufficient cache headroom
- **WHEN** the request's remaining deadline cannot provide both the cache-stage bound and the documented evaluation reserve
- **THEN** EACL skips cache access and begins ordinary selected-snapshot evaluation

#### Scenario: Oversized complete denotation for a point
- **WHEN** a provider advertises a complete denotation whose encoded or decoded weight exceeds the point operation's cache-read envelope
- **THEN** EACL does not retrieve or decode that denotation
- **AND** uses a smaller sufficient typed artifact or treats the lookup as a miss

#### Scenario: Provider lies about artifact size
- **WHEN** an artifact stream exceeds its authenticated encoded-size claim or enforced byte cap, or incremental decoding exceeds the decoded-weight cap
- **THEN** EACL aborts and records bounded cache corruption/provider failure
- **AND** never allocates, decodes, hashes, or traverses the remaining unbounded value

#### Scenario: Candidate proof is unavailable
- **WHEN** a candidate can be validated only by an additional dependency scan
- **THEN** EACL performs no scan for cache eligibility
- **AND** evaluates through the cache-independent semantic command trace

#### Scenario: Cache envelope configuration
- **WHEN** a client is created with a cache provider
- **THEN** every cache-attempt bound has a finite documented default and validated supported range
- **AND** no per-request cache setting can enlarge semantic demand

#### Scenario: Publication would exceed the remaining envelope
- **WHEN** serializing, compressing, hashing, evicting for, or publishing an already computed artifact would exceed a cache-attempt bound or deadline
- **THEN** EACL skips publication and returns the completed public result

### Requirement: Publication is bounded and best effort
Cache publication SHALL validate artifact type, key, retained weight, and
lifecycle before making a bounded non-waiting atomic publication attempt.
Contention, capacity, eviction, provider, or lifecycle failure SHALL skip or
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
Lookup corruption, invalid entries, eviction, capacity rejection, and provider
errors SHALL become cache misses and fall back to the same selected-snapshot
evaluation. Consistency selection, token, source-scope, cursor-authentication,
and deadline failures MUST remain request errors.

#### Scenario: Provider throws before deadline
- **WHEN** a cache provider throws during lookup while the request still has execution budget
- **THEN** EACL records the provider failure and evaluates on the already selected snapshot

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
Metrics SHALL distinguish completed hits, misses, bypasses, provider failures,
publication admissions/rejections/races, detached publications, exact avoided
commands, fetched values, and cache-stage time/byte/decoded-weight envelope
rejections. A concurrent publication observed after a miss
MUST NOT be reported as if the initiating request began with a completed hit.

#### Scenario: Miss races another publication
- **WHEN** a request misses and computes while another request publishes first
- **THEN** telemetry records the initiating request's miss and publication race
- **AND** does not count a completed cache hit

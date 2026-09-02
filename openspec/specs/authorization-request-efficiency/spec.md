## Purpose

Define Core request, cache, membership, and exact-basis contracts that remove repeated validation and coordination work without weakening authorization or consistency boundaries.

## Requirements

### Requirement: Each invariant is validated at its owning boundary

Public requests, adapter configuration and every operation-specific output shape or enabled runtime guard required by the existing adapter contract, restored cache entries, and authenticated tokens SHALL be validated at their owning boundaries. Certified adapter invariants for which runtime guards are disabled remain governed by the existing certification policy; this change MUST NOT add a universal per-item scan guard. After the applicable boundary validation or certification contract is established, EACL MAY carry a private immutable representation through internal recursion and batching without reconstructing or revalidating the public shape. Internal representations MUST NOT be constructible through the public API or accepted from an adapter, cache restore, or token decoder without satisfying that producer's applicable boundary and certification contract.

#### Scenario: Valid public request enters internal recursion
- **WHEN** a valid normalized request passes the public boundary and creates many internal probes
- **THEN** request fields whose values cannot change are validated once
- **AND** internal recursion does not rebuild the public request map for every probe

#### Scenario: Custom backend violates an applicable output guard
- **WHEN** a custom backend returns a value that violates a mandatory operation-specific shape or an enabled runtime guard
- **THEN** EACL rejects it before it becomes a trusted internal value or affects authorization state
- **AND** a certified guard-disabled scan does not acquire an unrelated universal per-item pass

#### Scenario: Selected basis already captured the backend snapshot identity
- **WHEN** exact-cache setup needs the backend snapshot identity after selected-basis acquisition validated and stored it
- **THEN** EACL reuses that immutable captured identity without invoking the adapter's snapshot operation again

### Requirement: Certified synchronous membership batches retain aligned positional results

A certified synchronous in-process membership adapter SHALL accept the existing normalized native membership request containing one validated descriptor, direction, and ordered vector of distinct candidates, and SHALL return the existing aligned vector with one Boolean at each corresponding input position. The already-selected adapter SHALL bind the selected immutable basis; this change MUST NOT add an argument to or otherwise alter the native membership ABI. EACL SHALL validate result cardinality and Boolean types before scatter. Adapter certification and differential tests SHALL establish positional alignment; echoing candidate labels is not required and SHALL NOT be treated as proof of answer correctness. An adapter that does not advertise the certified native capability SHALL use the checked scalar path. A remote or asynchronous transport in which responses can detach or reorder MUST declare and validate a separate correlation contract before it can use batching.

#### Scenario: One descriptor has one thousand distinct misses
- **WHEN** exactly 1,000 distinct cache-miss probes share one direction and descriptor and the certified maximum native width is 256
- **THEN** EACL issues exactly four native batches
- **AND** scatters the validated aligned Boolean results to the original probe positions

#### Scenario: Native result has wrong cardinality or type
- **WHEN** a certified native batch returns a short, long, or non-Boolean result vector
- **THEN** EACL throws the typed backend-contract failure before scatter, cache publication, or partial return

#### Scenario: Adapter does not advertise native batching
- **WHEN** a custom adapter supplies only checked scalar membership
- **THEN** EACL evaluates the candidates through that scalar operation without treating an unlabeled vector as a native result

#### Scenario: Transport can reorder detached responses
- **WHEN** a remote or asynchronous transport permits requests and responses to detach or reorder
- **THEN** it cannot advertise the in-process aligned-vector capability
- **AND** must satisfy a separately versioned request/response correlation contract before batching

#### Scenario: Batched probes reject a long candidate prefix
- **WHEN** the filtered authorization path batches membership probes and rejects many ordered candidates before accepting one
- **THEN** batching preserves the existing monotonic candidate progress and returns the later accepted result without replaying an arbitrary rejected prefix

#### Scenario: Batched probes find accepted lookahead
- **WHEN** a filtered page examines `N+1` accepted candidates and returns the first `N`
- **THEN** batching preserves the existing continuation rule that carries or inclusively resumes at the accepted lookahead
- **AND** concatenated pages contain no gap or duplicate

### Requirement: Completed exact hits avoid only work whose compatibility is already proved

Every request that consults a completed exact cache SHALL first acquire and validate its required immutable basis; an empty or otherwise cache-ineligible operation does not acquire a basis merely for this requirement. A completed exact hit SHALL come from the active captured lifecycle and an exact generation whose key validates source lifecycle, complete basis identity, adapter fingerprint, and identity contract. Its semantic key SHALL validate normalized operation/query/options, evaluation and demand, resource limits, engine and public-order identity, compiler/plan compatibility identity, and cache-value ABI. The compiler/plan identity SHALL include the fingerprint algorithm and every order, rank, frontier/rule, and cursor-interpretation contract that can change the producing plan. An in-process value SHALL become visible only through atomic publication after complete computation and validation; this requirement does not add a per-entry TTL or completion-marker field. An externally restored snapshot MUST first pass the existing authentication, encoded-size, structural, and compatibility boundaries, then pass the operation-specific internal cached-value shape validator before typed cache use; this is distinct from later public externalization. A caller that did not observe an already validated immutable entry SHALL validate its own held value, MAY race one bounded best-effort publication of successful validation state, SHALL NOT wait for another validator, and MAY use its own successfully validated value regardless of the publication winner. Ordinary hits on an already validated immutable entry do not repeat validation. Once all of those identities match, the exact basis plus compiler identity fixes the plan that produced the trusted entry, so the hit SHALL NOT force schema generation, schema reads, plan sealing, relationship reads, or proof resolution merely to recompute its fingerprint. A cursor or checkpoint is still checked against the current sealed plan when later consumed. No pre-rollout completed value, including a scalar value, survives unless it matches every newly required compatibility identity.

#### Scenario: Compatible exact generation serves a page hit
- **WHEN** the selected exact generation, semantic key, compiler/plan ABI, cache-value ABI, and completed page shape all match
- **THEN** EACL returns the validated entry without schema reads, plan reconstruction, relationship reads, or forcing/resolving its lazy proof frame

#### Scenario: Trusted restored page has no resident plan
- **WHEN** an authenticated, structurally valid completed page is restored under matching exact-generation, semantic-key, compiler/plan, and cache-value identities but no plan is resident
- **THEN** EACL validates the internal cached page shape and may serve the hit without forcing schema, plan construction, or public encoding during cache validation
- **AND** any later cursor consumption still seals or obtains the current plan and validates the embedded fingerprint

#### Scenario: Two requests first-use the same restored entry
- **WHEN** neither request observes completed operation-specific validation
- **THEN** each may validate its held immutable value independently and race bounded validation-state publication
- **AND** neither waits for the other or loses its own successfully validated result because it lost publication

#### Scenario: Live current request has a cache entry
- **WHEN** a current-consistency request might hit a completed answer
- **THEN** EACL still acquires the current immutable basis before accepting the hit

#### Scenario: Entry predates the compatibility rollout
- **WHEN** any old completed entry lacks the current cache-value or engine compatibility identity
- **THEN** it misses and recomputes without being externalized or freshly signed

### Requirement: Exact cache correctness is independent of recency and telemetry mutation

A reader that has obtained and validated an immutable exact entry SHALL be able to complete even if the store mapping is concurrently evicted. Obtaining a valid immutable exact hit SHALL require no shared mutation for authorization correctness. Exact-generation and entry-tier recency plus optional diagnostic updates MUST NOT decide semantic eligibility, basis identity, limit outcomes, or publication correctness. Recency maintenance SHALL be bounded and non-serializing and MUST continue to satisfy the durable recency-honest policy and hot-key-survives-churn scenario; a sampling scheme that can discard every material hot-key touch is insufficient unless that durable policy is separately revised. Disabling optional observation SHALL perform zero observer mutation, allocate no per-request observer state, and leave mandatory resource counters and the documented eviction policy active.

#### Scenario: Exact entry is evicted during a read
- **WHEN** one request validates an immutable exact entry and another request concurrently evicts its store mapping
- **THEN** the first request completes from its held value with the same result as fresh evaluation

#### Scenario: Optional telemetry is disabled
- **WHEN** optional diagnostics are disabled
- **THEN** the hit performs no observer mutation
- **AND** cache eligibility, exact answers, deadlines, and mandatory resource limits remain unchanged

### Requirement: Result-cache computations never join another request

An authorization request SHALL NOT wait for another request to compute, admit, publish, evict, expire, or invalidate an answer, subproblem, continuation, schema-derived value, sealed plan, or other cache artifact. Concurrent misses MAY compute independently under their own request contracts and race bounded immutable publication. Cache metrics SHALL count only values that were already completed when lookup began as hits; publication races and independent misses are not hits or single-flight joins.

#### Scenario: Concurrent requests miss the same key
- **WHEN** several authorization requests concurrently miss the same exact key
- **THEN** none waits for another request's computation or inherits its deadline or failure
- **AND** their completed immutable values may race bounded publication

#### Scenario: Another computation is in flight
- **WHEN** a lookup observes that another request is already computing a compatible request-result value
- **THEN** it evaluates under its own request contract rather than joining that computation

### Requirement: Finite cache decisions use a mechanically checked specialization

A generated decision over a finite, completely enumerated input domain MAY be replaced on the production path by a host specialization only when a mechanically checked extensional-equivalence artifact proves every input partition returns the same action. The generated source, specialization source, domain definition, mapping, and artifact digests SHALL form one certification identity. A changed or incomplete identity MUST disable or reject the specialization rather than executing it with stale evidence.

#### Scenario: Every current-cache partition is certified
- **WHEN** all valid stage and availability combinations are exhaustively checked against the generated authority
- **THEN** the host specialization may select the production action without also running a handwritten oracle on each request

#### Scenario: Domain or source changes
- **WHEN** the generated decision, specialization, finite domain, or mapping changes
- **THEN** the old equivalence artifact is rejected and production cannot claim the specialization is certified

### Requirement: Plan and request memo hits are read-first

Cross-request plan, schema-derived, and request-result lookup SHALL read the resident value before allocating publication state. On a miss, each request SHALL build under its own deadline, cancellation, counters, and failure contract without forcing or awaiting another request's candidate. A successful immutable value MAY race one bounded compare-and-install publication; the request MAY use its own compatible value regardless of which value is installed. Failure MUST NOT be published. Request-local memoization MAY coordinate work only within that same request. Rebuilding is permitted after bounded eviction, expiry, or lifecycle replacement.

#### Scenario: Stable plan is already resident
- **WHEN** a compatible sealed plan is already installed for the selected generation and root
- **THEN** lookup returns it without allocating a candidate delay or mutating the registry

#### Scenario: Concurrent plan miss races
- **WHEN** several requests concurrently miss the same generation and root
- **THEN** each may build under its own request contract without waiting for or inheriting another build
- **AND** at most one compatible immutable value is installed while every successful request may use its own value

#### Scenario: Installed generation was evicted
- **WHEN** a bounded generation registry evicts a formerly resident generation and that generation is later requested again
- **THEN** EACL may rebuild and race bounded publication under the same non-joining contract

### Requirement: Mandatory resource meters are exact and observation is optional

Candidates, probes, adapter commands, fetched values, admissions, transitions, response units, and publication attempts governed by a limit SHALL be charged by mandatory request-owned counters before the corresponding semantic commit. A constant internal counter key MAY use a private preindexed slot only after the key and amount invariants are established at its construction boundary; that path SHALL preserve the checked path's exact value, non-negative amount, and overflow behavior. Dynamic or externally supplied counter input SHALL retain full validation. Optional diagnostic observation MAY sample or aggregate events that do not govern a limit but MUST NOT be the source of any limit decision. Unsupported diagnostic metrics MUST remain unavailable rather than being recorded as zero.

#### Scenario: Diagnostics are disabled during a limited request
- **WHEN** a request runs with optional observation disabled and reaches a governed limit
- **THEN** it returns the same typed limit outcome and safe mandatory counters as an observed request

#### Scenario: A constant hot-path counter uses a preindexed slot
- **WHEN** profiling retains a private preindexed increment for a compile-time counter key
- **THEN** its accumulated value and overflow failure equal the checked increment path
- **AND** an unknown key or invalid dynamic amount cannot enter that private path

#### Scenario: Optional metric is unavailable
- **WHEN** a runtime cannot measure an optional diagnostic metric correctly
- **THEN** evidence records it as unsupported and no authorization or resource decision depends on it

### Requirement: Datomic exact acquisition synchronizes only when the captured local basis is behind

Exact-token shape, authentication, source, lifecycle, and locator `T` SHALL be validated before Datomic access. Exact acquisition SHALL capture one current local database value from the selected connection closure and read its basis. If that basis is at least `T`, EACL SHALL skip synchronization and select exactly `as-of T` from that value. Otherwise EACL SHALL perform one bounded targeted synchronization to `T`, verify the returned basis is at least `T`, and select exactly `as-of T` from the returned value. The operation SHALL rely on the captured connection closure under the unchanged source lifecycle; it SHALL NOT require an unverifiable database-value connection-generation field or retain a cross-request observed-head watermark.

#### Scenario: Requested locator is already local
- **WHEN** the captured local database basis is at least authenticated locator `T`
- **THEN** EACL performs no `d/sync` and evaluates exactly `as-of T`

#### Scenario: Requested locator is ahead locally
- **WHEN** the captured local database basis is below authenticated locator `T`
- **THEN** EACL performs one bounded targeted synchronization, verifies coverage, and evaluates exactly `as-of T`

#### Scenario: Token is invalid or belongs to another source
- **WHEN** token validation fails before exact acquisition
- **THEN** EACL performs neither current database acquisition nor synchronization

#### Scenario: Synchronization cannot reach the locator
- **WHEN** targeted synchronization times out, is cancelled, fails, or returns below `T`
- **THEN** sync timeout returns the existing `:eacl.consistency/freshness-unavailable` outcome with freshness-timeout reason; interruption returns the existing `:eacl.basis/selection-failure` cancellation classification; provider failure retains its existing selection-failure classification; and a successful response below `T` returns the existing freshness-unavailable head-behind outcome
- **AND** no `as-of`, authorization, or cache publication occurs

#### Scenario: Source lifecycle changes
- **WHEN** restore, reset, reconnect, excision, or history replacement rotates the selected source lifecycle
- **THEN** prior tokens and cached state cannot authorize exact evaluation under the new lifecycle

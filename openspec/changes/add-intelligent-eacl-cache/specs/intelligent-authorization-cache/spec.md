## ADDED Requirements

### Requirement: Unrelated Datomic transactions preserve live cache generations
The system SHALL derive live cache validity from EACL schema generations and mutation epochs for
the permission's relationship dependencies, not from the current Datomic `basis-t`.

#### Scenario: Unrelated transaction
- **WHEN** the Datomic connection advances because a transaction changes no EACL schema definition or relationship tuple
- **THEN** the EACL relationship epochs and eligible live lookup cache entries remain unchanged

### Requirement: Relationship writes are coherently published
The system SHALL coordinate every supported relationship write helper with cache readers so that no
reader can use a pre-write cache generation with a post-write database value.

#### Scenario: Successful relationship change
- **WHEN** an EACL relationship helper commits an actual relationship tuple change
- **THEN** subsequent live cached reads that depend on its relation definition use a new epoch

#### Scenario: Relationship no-op
- **WHEN** an EACL relationship helper commits no relationship tuple change
- **THEN** all relationship dependency epochs remain reusable

### Requirement: Recursive continuations are optional accelerators
The system SHALL preserve the complete recursive traversal result independently of continuation
cache availability.

#### Scenario: Continuation hit
- **WHEN** a valid continuation exists for the token's database, schema, query, engine, basis, and edge
- **THEN** the next page resumes from the cached traversal state without replaying the preceding prefix

#### Scenario: Continuation miss
- **WHEN** the continuation is absent, evicted, disabled, unavailable, or rejected as oversized
- **THEN** the system reconstructs the token's historical database value, replays to and verifies the cursor boundary, and returns the same page

#### Scenario: Relationships change after page one
- **WHEN** relationships change after a cursor was issued
- **THEN** continuation hit and replay miss paths both return results from the cursor's original historical basis

### Requirement: Non-recursive lookup caching is generation coherent
The system SHALL cache non-recursive lookup pages only when all relationship writers relevant to
the cache share its coordinator.

#### Scenario: Repeated lookup without EACL changes
- **WHEN** an identical resolved non-recursive lookup is executed after unrelated Datomic transactions
- **THEN** the cached internal page may be reused

#### Scenario: Relationship change
- **WHEN** a relationship change can affect a previously cached non-recursive lookup
- **THEN** the old page is unreachable from subsequent live requests

#### Scenario: Unrelated relation change
- **WHEN** a relationship changes on a relation definition outside a cached permission's dependency set
- **THEN** that cached page remains reusable

#### Scenario: No coherent coordinator
- **WHEN** a deployment does not provide coherence across its EACL writers
- **THEN** cross-request live result memoization is disabled and the indexed lookup executes normally

#### Scenario: Unstamped client schema
- **WHEN** a client has no `:eacl/schema-version` because no supported schema write has established one
- **THEN** lookup pages and recursive continuations are not cached until `write-schema!` establishes the client generation

### Requirement: Cache storage is bounded and optional
The system SHALL permit consumers to disable caching and SHALL enforce configured cache capacity,
entry size, and lifetime limits when enabled.

#### Scenario: Cache disabled
- **WHEN** a client is constructed with caching disabled
- **THEN** all authorization and lookup results remain identical to enabled-cache results

#### Scenario: Cache capacity reached
- **WHEN** admitting an entry would exceed a configured cache bound
- **THEN** entries are evicted or the new entry is rejected without changing the authorization result

#### Scenario: Cache provider failure
- **WHEN** a cache provider throws or becomes unavailable
- **THEN** EACL computes the answer through the uncached path and never treats the failure as an authorization result

### Requirement: Cache data is not stored in the consumer database
The system SHALL add no Datomic schema attributes or persistent datoms for effective grants, cache
entries, generations, markers, or continuation state.

#### Scenario: Schema comparison
- **WHEN** the intelligent cache is enabled
- **THEN** the consumer's Datomic schema and persistent EACL relationship representation are unchanged

### Requirement: Existing lookup APIs remain unchanged
The system SHALL accelerate the existing `lookup-resources` and `lookup-subjects` calls without
introducing alternate public lookup functions.

#### Scenario: Cached and uncached invocation
- **WHEN** a consumer invokes an existing lookup API with caching enabled or disabled
- **THEN** the request and response shapes are identical

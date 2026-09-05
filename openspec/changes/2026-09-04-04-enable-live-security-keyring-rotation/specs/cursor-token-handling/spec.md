## MODIFIED Requirements

### Requirement: Invalid cursor tokens throw typed errors
`token->cursor` SHALL return `nil` only for a `nil` input meaning first page and SHALL pass supported raw cursor maps through unchanged where the existing backward-compatibility contract allows them. Every non-nil encoded cursor MUST name exactly one authenticated security key identifier. A malformed cursor, authentication failure, unknown or retired key identifier, mismatched cursor-cache key identifier, or unsupported format SHALL throw `ex-info` with `:type :eacl.pagination/invalid-cursor` and MUST NOT silently restart pagination from the first page.

#### Scenario: Garbage token fails loudly
- **WHEN** `lookup-resources` is called with `:after "eacl1_not-valid"` or `:after "garbage"`
- **THEN** an `ex-info` with `:type :eacl.pagination/invalid-cursor` is thrown and the first page is not silently returned

#### Scenario: nil cursor still means first page
- **WHEN** `lookup-resources` is called with `:cursor nil`
- **THEN** the first page is returned

#### Scenario: Retired key names an outstanding cursor
- **WHEN** a cursor names a key identifier that has been retired from the live ring
- **THEN** resume fails with `:eacl.pagination/invalid-cursor` and reason `:security-key-unavailable`
- **AND** EACL does not try another key, rebase, replay unauthenticated state, or restart at page one

#### Scenario: Cursor cache entry names another key
- **WHEN** an authenticated cursor names key K1 but a process-local continuation or rendered-page entry under its identity names K2
- **THEN** EACL ignores or rejects that entry and reconstructs only from authenticated cursor state under the existing continuation contract

### Requirement: Cursor TTL is configurable and defaults to no expiry
Cursor expiry SHALL remain off by default for every backend. Tokens minted without `:cursor-ttl-seconds` SHALL carry no age expiry and SHALL remain age-valid only while their named security key and source lifecycle remain accepted. When a positive TTL is configured, minted tokens SHALL embed an expiry and decoding at or after that instant SHALL throw `ex-info` with `:type :eacl.pagination/expired-cursor` and `:reason :expired` without restarting pagination. Cache/checkpoint retention SHALL NOT determine cursor lifetime. Key retirement is independent of age expiry and MUST fail as unavailable authenticated key material.

#### Scenario: Slow batch pagination does not restart
- **WHEN** a client without `:cursor-ttl-seconds` resumes pagination with a token minted more than 5 minutes ago whose key remains accepted
- **THEN** the next page is returned normally and age alone does not invalidate the cursor

#### Scenario: Configured TTL is enforced loudly
- **WHEN** a client configured with `{:cursor-ttl-seconds 60}` decodes a token at or after its expiry
- **THEN** an `:eacl.pagination/expired-cursor` error with `:reason :expired` is thrown and pagination does not restart

#### Scenario: Key is deliberately retired
- **WHEN** a non-expiring cursor names a key id absent from the live keyring
- **THEN** EACL rejects it as an invalid authenticated cursor with reason `:security-key-unavailable` rather than age expiry

#### Scenario: Source lifecycle changes
- **WHEN** restore, reset, excision, purge, branch replacement, or destructive history replacement rotates the source lifecycle
- **THEN** a prior non-expiring cursor is rejected for lifecycle mismatch and never interpreted against replacement history

#### Scenario: Non-expiring cursor must survive rotation losslessly
- **WHEN** an operator requires every non-expiring cursor minted under an old key to remain resumable
- **THEN** documentation requires retaining that key indefinitely
- **AND** retiring it is documented as intentional invalidation rather than a lossless finite-overlap rotation

## ADDED Requirements

### Requirement: Cursor state is key-affine and retireable
Every process-local cursor, continuation, visited-page, rendered-page, and equivalent replay entry SHALL retain the security key identifier of the authenticated cursor series that created it. Retiring a key SHOULD eagerly evict entries naming it, while current-ring authentication MUST remain the correctness boundary even if eager eviction is delayed or incomplete.

#### Scenario: Retired cursor state remains in memory briefly
- **WHEN** an entry naming a retired key survives an eviction race
- **THEN** a new resume request fails at live-ring cursor authentication before that entry can influence pagination

#### Scenario: New key activates without global cursor eviction
- **WHEN** a new key becomes active while the old key remains accepted
- **THEN** cursor series under both keys can resume and entries for unrelated retained keys are not globally cleared

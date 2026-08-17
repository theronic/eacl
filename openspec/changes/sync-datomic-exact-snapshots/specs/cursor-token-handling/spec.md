## MODIFIED Requirements

### Requirement: Cursor TTL is configurable and defaults to no expiry
Cursor expiry SHALL be off by default for every backend. Tokens minted without `:cursor-ttl-seconds` SHALL carry no expiry and SHALL remain age-valid. When a positive TTL is configured, minted tokens SHALL embed an expiry and decoding at or after that instant SHALL throw `ex-info` with `:type :eacl.pagination/expired-cursor` and `:reason :expired` without restarting pagination. Cache and checkpoint retention SHALL NOT determine cursor lifetime.

#### Scenario: Slow batch pagination does not restart
- **WHEN** a client without `:cursor-ttl-seconds` resumes pagination with a token minted more than 5 minutes ago
- **THEN** the next page is returned normally

#### Scenario: Configured TTL is enforced loudly
- **WHEN** a client configured with `{:cursor-ttl-seconds 60}` decodes a token older than 60 seconds
- **THEN** an `:eacl.pagination/expired-cursor` error with `:reason :expired` is thrown — not a silent first page

#### Scenario: Key is deliberately retired
- **WHEN** a non-expiring cursor names a key id absent from the configured keyring
- **THEN** EACL rejects it as an invalid authenticated cursor rather than age expiry

#### Scenario: Source lifecycle changes
- **WHEN** restore, reset, excision, purge, branch replacement, or destructive history replacement rotates the source lifecycle
- **THEN** a prior non-expiring cursor is rejected for lifecycle mismatch and never interpreted against replacement history

## ADDED Requirements

### Requirement: Cursor query scope permits exact historical recovery
Cursor authentication SHALL bind the complete normalized operation/query/principal and answer-affecting configuration independently from the selected snapshot's schema/dependency proof. A change to the current schema or relationship graph SHALL be allowed to reach proof comparison and exact fallback rather than being rejected prematurely as a different query.

#### Scenario: Relevant schema changes after page one
- **WHEN** current schema generation differs from the cursor's generation but operation, normalized query, principal, and configuration are unchanged
- **THEN** EACL does not reject the cursor merely because current schema generation differs
- **AND** a history-capable backend may reconstruct and validate the original exact schema and graph

#### Scenario: Query actually changes
- **WHEN** operation, principal, permission, resource/subject filter, direction, page shape, ordering ABI, or answer-affecting configuration differs
- **THEN** EACL rejects it as an invalid cursor before authorization traversal

#### Scenario: Checkpoint was evicted
- **WHEN** a non-expiring cursor's private continuation checkpoint is missing or evicted
- **THEN** EACL deterministically replays and validates its authenticated boundary on the selected exact snapshot

#### Scenario: Exact replay exceeds a bound
- **WHEN** exact replay cannot reach the authenticated boundary within the request deadline or resource limit
- **THEN** EACL returns the typed deadline/resource outcome rather than expiring, rebasing, or restarting the cursor

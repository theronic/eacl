## MODIFIED Requirements

### Requirement: Cursor TTL is configurable and defaults to no expiry
Cursor expiry SHALL be off by default for every backend. Tokens minted without `:cursor-ttl-seconds` SHALL carry no expiry and SHALL remain age-valid. When a positive TTL is configured, minted tokens SHALL embed an expiry and decoding after that instant SHALL throw the established typed expired-cursor error without restarting pagination.

#### Scenario: Datomic cursor exceeds the former five-minute default
- **WHEN** a Datomic client without `:cursor-ttl-seconds` resumes an authenticated cursor more than five minutes after minting
- **THEN** age alone does not reject the cursor
- **AND** EACL continues on a proof-equivalent snapshot or reconstructs its exact historical basis

#### Scenario: Datahike cursor has no configured TTL
- **WHEN** a full-history Datahike client resumes an authenticated cursor of arbitrary age within the same source lifecycle
- **THEN** age alone does not reject the cursor

#### Scenario: Configured TTL is enforced
- **WHEN** a client configured with `{:cursor-ttl-seconds 60}` decodes a cursor at or after its expiry
- **THEN** EACL throws `:eacl.pagination/expired-cursor` with reason `:expired`
- **AND** does not silently return page one or classify storage as unavailable

#### Scenario: Key is deliberately retired
- **WHEN** a non-expiring cursor names a key id no longer present in the configured keyring
- **THEN** EACL rejects it as an invalid authenticated cursor rather than age expiry

#### Scenario: Source lifecycle changes
- **WHEN** restore, reset, excision, purge, branch replacement, or destructive history replacement rotates the source lifecycle
- **THEN** a prior non-expiring cursor is rejected for lifecycle mismatch and is never interpreted against the replacement history

## ADDED Requirements

### Requirement: Cursor query scope permits exact historical recovery
Cursor authentication SHALL bind the complete normalized operation/query/principal and answer-affecting configuration independently from the selected snapshot's schema/dependency proof. A change to the current schema or relationship graph SHALL be allowed to reach proof comparison and exact fallback rather than being rejected prematurely as a different query.

#### Scenario: Relevant schema changes after page one
- **WHEN** the current schema generation differs from the cursor's generation but the operation, normalized query, principal, and configuration are unchanged
- **THEN** EACL does not reject the cursor merely because current schema generation participates in query scope
- **AND** a history-capable backend reconstructs and validates the cursor's original exact schema and graph

#### Scenario: Query actually changes
- **WHEN** operation, principal, permission, resource/subject filter, direction, page shape, ordering ABI, or answer-affecting configuration differs from the authenticated cursor
- **THEN** EACL rejects it as an invalid cursor before authorization traversal

#### Scenario: Checkpoint was evicted
- **WHEN** a non-expiring cursor's client-private continuation checkpoint is missing or evicted
- **THEN** EACL deterministically replays and validates the authenticated boundary on the selected exact snapshot
- **AND** checkpoint retention does not become cursor lifetime

#### Scenario: Exact replay exceeds an execution bound
- **WHEN** exact replay cannot reach the authenticated boundary within the configured request deadline or resource limit
- **THEN** EACL returns the typed deadline/resource outcome
- **AND** does not expire, rebase, or silently restart the cursor

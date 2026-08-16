# cursor-token-handling Specification

## Purpose
TBD - created by archiving change fix-audit-root-causes. Update Purpose after archive.
## Requirements
### Requirement: Invalid cursor tokens throw typed errors
`token->cursor` SHALL return `nil` only for a `nil` input (meaning "first page") and SHALL pass raw cursor maps through unchanged (backward compatibility). Any non-nil string token that cannot be decoded — wrong prefix, corrupt base64/EDN — SHALL throw `ex-info` with `:type :eacl/invalid-cursor`. Pagination SHALL NOT silently restart from the first page on a bad cursor.

#### Scenario: Garbage token fails loudly
- **WHEN** `lookup-resources` is called with `:cursor "eacl1_not-valid"` or `:cursor "garbage"`
- **THEN** an `ex-info` with `:type :eacl/invalid-cursor` is thrown, and the first page is not silently returned

#### Scenario: nil cursor still means first page
- **WHEN** `lookup-resources` is called with `:cursor nil`
- **THEN** the first page is returned

### Requirement: Cursor TTL is configurable and defaults to no expiry
Cursor expiry SHALL be off by default: tokens minted without a configured TTL SHALL carry no expiry and SHALL decode regardless of age. `make-client` SHALL accept `:cursor-ttl-seconds`; when set, minted tokens embed expiry and decoding an expired token SHALL throw `ex-info` with `:type :eacl/invalid-cursor` and `:reason :expired`.

#### Scenario: Slow batch pagination does not restart
- **WHEN** a client without `:cursor-ttl-seconds` resumes pagination with a token minted more than 5 minutes ago
- **THEN** the next page is returned normally

#### Scenario: Configured TTL is enforced loudly
- **WHEN** a client configured with `{:cursor-ttl-seconds 60}` decodes a token older than 60 seconds
- **THEN** an `:eacl/invalid-cursor` error with `:reason :expired` is thrown — not a silent first page

### Requirement: Cursors detect permission-path changes between pages
Cursors SHALL embed a two-part fingerprint at mint time: the schema-history digest of the minting db value, and a content digest of the query's resolved permission paths (v2) or recursive query plan (v3). On resume: an identical schema digest SHALL proceed (identical schema history implies identical paths); a differing schema digest SHALL trigger recomputation of this query's paths — if their digest matches the cursor's, pagination proceeds (the schema change did not affect this query); if it differs, `ex-info` with `:type :eacl/stale-cursor` SHALL be thrown instead of silently mis-skipping results. Tokens minted before fingerprints existed (no fingerprint field) SHALL be accepted with a logged warning for one release.

#### Scenario: Schema change affecting the query fails loudly
- **WHEN** page 1 is fetched, `write-schema!` changes the paths of the queried permission, and page 2 is requested with page 1's cursor
- **THEN** an `:eacl/stale-cursor` error is thrown

#### Scenario: Unrelated schema change does not invalidate the cursor
- **WHEN** page 1 is fetched, a schema change lands that does not alter the queried permission's resolved paths, and page 2 is requested with page 1's cursor
- **THEN** pagination resumes normally

#### Scenario: Unchanged schema resumes normally
- **WHEN** pages are fetched across an unchanged schema (including unrelated relationship writes in between)
- **THEN** pagination resumes exactly where it left off, with no duplicates or gaps


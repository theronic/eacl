# cursor-dependency-validity Specification

## Purpose
TBD - created by archiving change eacl-v8-root-fixes. Update Purpose after archive.
## Requirements
### Requirement: Dependency-scoped continuation proofs
Cursor continuation proofs SHALL be scoped to the query's compiled relation dependency stamps (schema stamp plus the sorted per-relation stamp vector), not to whole-database identity (`basis-t`). A transaction touching no relation in the cursor's dependency set SHALL NOT invalidate the continuation.

#### Scenario: Unrelated churn preserves continuation
- **WHEN** a cursor is issued, twenty transactions touching only relations outside the query's dependency closure commit, and the cursor is resumed under recover-current mode
- **THEN** the continuation decision is `:current` (not `:rebase-current`), server-side continuation/heads state is reused, and no fixed-point recomputation occurs

#### Scenario: Relevant write triggers recovery
- **WHEN** a transaction touches a relation inside the cursor's dependency closure before resumption
- **THEN** the continuation decision is recovery (rebase or restart per the certified contract), never silent reuse of stale traversal state

### Requirement: Unconditional schema-generation validation
Cursor acceptance SHALL validate the cursor's schema generation against the selected snapshot's schema generation on every resumption, including resumptions in recovery mode. The schema-generation stamp SHALL be the actual schema mutation identity, not a proxy derived from `basis-t`.

#### Scenario: Cursor across a schema change
- **WHEN** a cursor minted under schema generation G1 is resumed after `write-schema!` produced generation G2
- **THEN** the request fails with the typed stale-schema error or restarts per the documented contract; it SHALL NOT resume the walk as if the schema were unchanged

### Requirement: One authenticated-and-confidential token codec
All backends SHALL use one page-token codec providing both authenticity and confidentiality (AEAD). Backend capability sets SHALL advertise identical `:cursor` properties for the built-in adapters.

#### Scenario: Portable token confidentiality
- **WHEN** a Datahike or DataScript page token is issued
- **THEN** its payload (snapshot identifiers, graph anchors, proof digests, result ids) is not recoverable from the token without the key, and tampering is rejected before any payload parse

### Requirement: Key-management transparency
Constructing a client without explicit token key material SHALL emit a startup warning stating that cursors and tokens will not survive process restart or load-balanced deployment. Documentation SHALL state the AEAD nonce-per-key invocation bound and rotation guidance.

#### Scenario: Defaulted key warning
- **WHEN** `make-client` is called with no token key option
- **THEN** a one-time warning is emitted naming the option to set and the operational consequence of not setting it


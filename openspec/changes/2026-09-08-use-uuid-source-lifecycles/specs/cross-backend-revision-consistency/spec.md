## ADDED Requirements

### Requirement: UUID lifecycle scopes every backend revision

Source profiles, public basis identities and causal/exact tokens SHALL carry a native UUID lifecycle independently from backend/source identity, branch, revision and exact locator. EACL SHALL compare complete scope and UUID before selecting from an authenticated token and SHALL validate selected-basis postconditions. A shared lifecycle UUID SHALL not make two stores, branches or connection-local sources equivalent. Backends SHALL preserve their existing history, barrier, watermark and lifecycle-persistence obligations.

#### Scenario: Same UUID with different source
- **WHEN** two stores or branches use the same explicit or initial UUID and their revision numbers coincide
- **THEN** their tokens and proof-backed artifacts remain noninterchangeable because full source scope differs

#### Scenario: Different UUID with same source and revision
- **WHEN** a restored source reuses a native revision under a fresh lifecycle UUID
- **THEN** an old-lifecycle token is rejected and cannot select the replacement history

#### Scenario: Routine restart preserves identity
- **WHEN** a durable source is reopened with the same authoritative UUID, native source identity and keys
- **THEN** its otherwise valid upgraded tokens remain eligible for the same supported consistency selections

#### Scenario: Connection-local backend is recreated
- **WHEN** a backend without durable source identity creates a new source using the same lifecycle UUID
- **THEN** its required fresh native source identity prevents reuse of the old source's artifacts

#### Scenario: Cache disabled
- **WHEN** completed-answer caching is disabled
- **THEN** UUID lifecycle validation and authenticated revision selection still apply

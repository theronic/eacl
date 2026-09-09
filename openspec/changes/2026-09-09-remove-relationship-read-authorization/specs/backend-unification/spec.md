## MODIFIED Requirements

### Requirement: Uniform filter validation
Relationship-read filter validation SHALL be one shared implementation with value-presence anchor semantics: an anchor key present with a nil value SHALL throw `:eacl.filters/missing-anchor` with `:nil-anchor-keys` ex-data on every backend; nil-valued type/relation filters SHALL NOT act as match-everything wildcards; unknown keys SHALL produce the same typed error with the same known-key set (modulo documented per-backend pagination capabilities). `:authorization` SHALL NOT be a supported relationship-read filter. Its presence SHALL produce `:eacl.filters/unknown-filter` with `:authorization` in `:unknown-keys`, before snapshot acquisition, relationship traversal or cache lookup/publication, regardless of the supplied value. EACL MUST NOT silently discard the clause or provide a compatibility authorization path.

#### Scenario: Nil id anchor
- **WHEN** `read-relationships` is called with `{:subject/id nil :first 5}` on any backend
- **THEN** the call throws `:eacl.filters/missing-anchor` naming `:subject/id` in `:nil-anchor-keys`

#### Scenario: Nil type anchor
- **WHEN** `read-relationships` is called with `{:resource/type nil :first 5}` on any backend
- **THEN** the call throws the same typed error instead of returning relationships from every relation definition

#### Scenario: Pagination option parity
- **WHEN** the same pagination option (for example `:limit`) is passed to each backend
- **THEN** every backend produces the same `:eacl/error` classification for it

#### Scenario: Previously valid authorized read
- **WHEN** a valid anchored request includes `:authorization {:subject viewer :permission :view :on :resource}`
- **THEN** every bundled backend rejects it with `:eacl.filters/unknown-filter` naming `:authorization`
- **AND** no snapshot acquisition, scan, permission decision or cache lookup/publication occurs

#### Scenario: Empty or malformed removed option
- **WHEN** an otherwise valid relationship read contains `:authorization` set to nil, false, an empty map or a malformed clause
- **THEN** it receives the same removed-key error and returns no relationships

## ADDED Requirements

### Requirement: Relationship reads do not authorize endpoints
An admitted `read-relationships` request SHALL return the matching stored relationship page under its supported filters and relationship-state semantics, without checking a viewing subject's permission on either endpoint. It SHALL retain ordinary ordering, opaque cursors, bounded demand, consistency, cache controls, qualified inspection and typed failure behavior. Anchored reads MUST NOT materialize the complete matching relationship collection to produce a page or compute its count.

#### Scenario: Related resource would fail a permission check
- **WHEN** a matching stored relationship is in the requested page but its resource would fail a separate user's view check
- **THEN** the relationship read returns it without making that check
- **AND** a separate permission operation still returns the correct denial

#### Scenario: Large anchored unqualified page
- **WHEN** a caller requests 25 relationships at a non-final position in a large single-relation anchored stream with background lookahead disabled
- **THEN** the call returns the 25 matching rows and the ordinary continuation information using demand-bounded index work
- **AND** performs zero endpoint permission evaluations and no full collection realization

#### Scenario: Qualified relationship inspection
- **WHEN** stored or expiry-active relationship inspection is requested through its supported controls
- **THEN** qualifier metadata, expiry selection and pagination retain their documented semantics without endpoint authorization

### Requirement: Standalone authorization remains independent of relationship reads
`can?`, `check-permission`, `check-permissions`, ordinary resource/subject lookups and their counts SHALL retain their existing permission semantics and consistency/cache behavior. In particular, exclusion, union, intersection, arrows, recursion, caveats and expiration SHALL NOT depend on the removed relationship-read clause. Applications SHALL be able to compose relationship reads with the existing point or batch APIs on an explicitly selected snapshot; no replacement combined API SHALL be introduced.

#### Scenario: Banned subject is excluded
- **WHEN** a schema defines `view = member - banned` and a subject has both stored relations
- **THEN** point and bulk checks deny view and ordinary permission lookups exclude the subject/resource as appropriate
- **AND** a direct relationship read can still inspect both stored relations

#### Scenario: Caller filters a relationship page
- **WHEN** an application reads a page and explicitly checks each endpoint on the same snapshot
- **THEN** the existing point or batch result contracts apply
- **AND** filling an authorized page and continuing across rejected rows remain application responsibilities

### Requirement: Removed scan artifacts are never reinterpreted as direct reads
Previously issued authorized-read continuations and cached pages SHALL NOT be accepted as unfiltered relationship-read continuations or answers by dropping their authorization identity. They SHALL fail existing compatibility/scope checks or miss and require a new direct read, as appropriate. Supported plain-read compatibility SHALL remain unchanged unless an actual shared ABI change requires its documented invalidation.

#### Scenario: Old authorized cursor submitted without clause
- **WHEN** a caller submits an old authorized-read cursor with a plain relationship-read request
- **THEN** continuation is rejected with a typed compatibility or scope error and no cursor is silently restarted

#### Scenario: Old authorized cached page is available
- **WHEN** a plain read runs while a previously authorized page exists in a restored cache
- **THEN** that page cannot satisfy the plain read by normalization that discards the former authorization clause

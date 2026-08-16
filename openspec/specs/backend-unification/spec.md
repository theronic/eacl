# backend-unification Specification

## Purpose
TBD - created by archiving change eacl-v8-root-fixes. Update Purpose after archive.
## Requirements
### Requirement: One shared client orchestration
The public operation orchestration (the nine read/write/check/lookup/count operations, snapshot-context assembly, cursor plumbing, cache wiring, filter validation, and integrity reporting) SHALL be implemented once in the core module, parameterized by the backend SPI and per-backend construction options. Backend modules SHALL contain only genuinely backend-specific code: index-scan primitives, schema/attribute installation, transaction submission, and consistency selection.

#### Scenario: Fork elimination
- **WHEN** the Datahike and DataScript modules are compared after unification
- **THEN** neither contains a per-backend copy of operation orchestration, filter validation, integrity walking, or endpoint-pair encoding; the near-identical ~900-line orchestration layers and duplicated impl logic are gone

#### Scenario: Datomic relationship pages on the shared engine
- **WHEN** Datomic serves a relationship page
- **THEN** it executes through the shared `eacl.engine.relationships` planner/executor, not a private reimplementation

### Requirement: Uniform filter validation
Relationship-read filter validation SHALL be one shared implementation with value-presence anchor semantics: an anchor key present with a nil value SHALL throw `:eacl.filters/missing-anchor` with `:nil-anchor-keys` ex-data on every backend; nil-valued type/relation filters SHALL NOT act as match-everything wildcards; unknown keys SHALL produce the same typed error with the same known-key set (modulo documented per-backend pagination capabilities).

#### Scenario: Nil id anchor
- **WHEN** `read-relationships` is called with `{:subject/id nil :first 5}` on any backend
- **THEN** the call throws `:eacl.filters/missing-anchor` naming `:subject/id` in `:nil-anchor-keys`

#### Scenario: Nil type anchor
- **WHEN** `read-relationships` is called with `{:resource/type nil :first 5}` on any backend
- **THEN** the call throws the same typed error instead of returning relationships from every relation definition

#### Scenario: Pagination option parity
- **WHEN** the same pagination option (for example `:limit`) is passed to each backend
- **THEN** every backend produces the same `:eacl/error` classification for it

### Requirement: Uniform construction surface
`make-client` SHALL accept one documented option map across backends, with per-backend extensions explicitly namespaced and documented; equivalent options SHALL share names and semantics (one token-key option family, one cursor-TTL name), and unknown-option errors SHALL be uniform.

#### Scenario: Switching backends
- **WHEN** a consumer moves a valid Datomic client configuration to Datahike or DataScript, changing only the connection/database argument and any documented per-backend extension
- **THEN** construction succeeds without renaming semantically identical options

### Requirement: Shared codecs everywhere
Endpoint-pair encoding/decoding and token codecs SHALL be consumed from the shared core by every backend; no backend SHALL inline its own copy of a shared encoding.

#### Scenario: Codec change propagation
- **WHEN** the shared endpoint-pair encoding changes
- **THEN** all three backends observe the change by construction, with no hand-rolled tuple literals to drift


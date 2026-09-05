## MODIFIED Requirements

### Requirement: Uniform construction surface
`make-client` SHALL accept one documented option map across backends, with per-backend extensions explicitly namespaced and documented; equivalent options SHALL share names and semantics, including one token-key option family, one cursor-TTL name, one backend-neutral primary live security-keyring controller, and one optional dedicated Zed-token controller with existing primary-ring fallback. Unknown-option errors SHALL be uniform. Supplying a live controller together with static security-key material MUST fail as ambiguous configuration.

#### Scenario: Switching backends
- **WHEN** a consumer moves a valid Datomic client configuration to Datahike or DataScript, changing only the connection/database argument and any documented per-backend extension
- **THEN** construction succeeds without renaming semantically identical options

#### Scenario: Switching backends with a live controller
- **WHEN** the same valid controller is supplied to clients for two bundled backends supported in one runtime
- **THEN** both observe equivalent keyring generation, install, activate, retire, encode, and decode semantics

#### Scenario: Static and live key sources conflict
- **WHEN** a client supplies a primary or dedicated Zed-token controller together with static key/keyring options for the same scope
- **THEN** every bundled backend rejects construction with the same typed invalid-configuration error

### Requirement: Shared codecs everywhere
Endpoint-pair encoding/decoding and protected token/cache codecs SHALL be consumed from the shared core by every backend; no backend SHALL inline its own copy of a shared encoding. Every protected envelope MUST carry an authenticated key identifier and perform a direct lookup in one captured live-ring snapshot; no backend MAY retain an implicit singleton-key format or ring-wide trial-decryption loop.

#### Scenario: Codec change propagation
- **WHEN** the shared endpoint-pair or protected-envelope encoding changes
- **THEN** all bundled backends observe the change by construction, with no hand-rolled tuple or token literals to drift

#### Scenario: Key id is tampered with
- **WHEN** an attacker changes the visible key identifier on a protected artifact
- **THEN** authentication fails before the protected payload is accepted

#### Scenario: Key is absent
- **WHEN** an artifact names a key unavailable in the captured controller state
- **THEN** each bundled backend produces the same artifact-specific typed error or cache-miss behavior

## ADDED Requirements

### Requirement: Shared live keyring orchestration
Public operation orchestration SHALL integrate the live keyring controller once in the core module and SHALL pass immutable per-operation cryptographic snapshots to shared codecs. Backend modules MUST NOT implement independent live-ring state machines or read mutable keyring state more than once per protected encode/decode operation.

#### Scenario: Backend codec receives captured state
- **WHEN** a backend emits or decodes a protected cursor through shared orchestration
- **THEN** it receives the key identifier and derived/key material selected from one captured controller generation
- **AND** does not query a backend-local ring during the same operation

#### Scenario: Shared controller changes
- **WHEN** an application updates one controller shared by multiple backend clients on a Peer
- **THEN** subsequent protected operations by each sharing client observe the same complete new generation

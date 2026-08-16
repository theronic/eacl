## ADDED Requirements

### Requirement: Backend-neutral causal snapshot contract
The core module SHALL extend the adapter contract with operations for source scope, immutable snapshot identity, graph head and mutation-anchor membership, order hints, authoritative/current selection, bounded causal selection, exact location, optional exact reconstruction, schema proof, and relation proof. Storage-specific APIs and objects MUST remain inside adapter modules.

#### Scenario: Shared consistency orchestration
- **WHEN** a Datomic, Datahike, or DataScript client receives a normalized consistency descriptor
- **THEN** shared code performs token validation, capability checks, snapshot-selection orchestration, causal postcondition validation, cache ordering, and typed-error normalization through adapter operations

#### Scenario: Engine receives one immutable adapter
- **WHEN** selection succeeds
- **THEN** shared authorization code receives an adapter bound to exactly that immutable snapshot
- **AND** cannot accidentally dereference a later connection value during evaluation

#### Scenario: Order relation is partial
- **WHEN** a backend such as Datahike exposes branching or force-moved histories
- **THEN** the contract uses causal dominance/anchor membership rather than requiring one numeric total order

### Requirement: Existing engine primitive compatibility is capability-limited
The six map-based engine primitives `cache-stamp`, `relation-defs`, `permission-defs`, `subject->resources`, `resource->subjects`, and `direct-match?` SHALL remain available to legacy third-party adapters. A legacy adapter without causal snapshot and complete proof operations SHALL be limited to uncached operation on an explicitly supplied immutable snapshot and MUST NOT advertise version-3 tokens, proof lifting, or proof-equivalent cursors.

#### Scenario: Legacy immutable adapter is used
- **WHEN** a third-party adapter implements only the existing engine SPI
- **THEN** the shared engine may evaluate directly on its supplied snapshot

#### Scenario: Legacy adapter requests a new guarantee
- **WHEN** a legacy adapter requests causal freshness, completed-answer lifting, authoritative-head selection, or graph-equivalent continuation
- **THEN** EACL returns `:eacl/unsupported-capability` before evaluation

### Requirement: Shared cryptographic format service
The core module SHALL own versioned, bounded, canonical cryptographic formats for Zed tokens, cursor envelopes, and authorization-affecting cache entries. It SHALL use distinct signing/encryption domains and derived keys, key ids, rotation keyrings, strict field allowlists, constant-time authentication checks, and exact portable numeric representations. Cursor authentication is mandatory; cursor confidentiality SHALL be an independently advertised capability.

#### Scenario: Portable cursor is created
- **WHEN** Datahike or DataScript emits a cursor
- **THEN** shared authentication protects its scope, stable position, and dependency proof
- **AND** the cursor contains no unserializable backend object

#### Scenario: Datomic encrypted cursor is created
- **WHEN** the Datomic adapter advertises confidential cursors
- **THEN** it uses authenticated encryption in addition to mandatory authenticity

#### Scenario: Synchronous browser client has no synchronous AEAD
- **WHEN** DataScript runs behind a synchronous ClojureScript API without compatible synchronous encryption
- **THEN** it may emit an authenticated non-confidential cursor using stable external identities/digests
- **AND** MUST NOT advertise cursor confidentiality

#### Scenario: Cache entry is read from a shared provider
- **WHEN** a provider returns a completed authorization value
- **THEN** shared code authenticates the embedded complete key, causal metadata, proof, and value before considering it

#### Scenario: Decoder receives hostile input
- **WHEN** a token exceeds size/depth limits, contains unknown fields, has an unsupported numeric representation, or fails authentication
- **THEN** decoding fails with a bounded typed error

### Requirement: Shared conformance and reference-model suite
The core module SHALL provide a backend contract suite and deterministic full-content reference model covering causal tokens, source scope, authoritative selection, dependency completeness, proof lifting, cursor continuation, exact expiry, and cache integrity. Every bundled adapter MUST run applicable scenarios with real backend transaction and snapshot APIs.

#### Scenario: Bundled backend validation
- **WHEN** the non-benchmark module suite runs
- **THEN** Datomic, Datahike, and DataScript pass all shared guarantees they advertise
- **AND** unsupported configuration variants fail before authorization

#### Scenario: Generated divergence trace
- **WHEN** a generated trace clones, restores, resets, branches, force-moves, or reuses transaction numbers for different content
- **THEN** no bundled adapter accepts numeric equality as causal or dependency equality

#### Scenario: Differential cache oracle
- **WHEN** any generated cached request returns a result
- **THEN** it equals uncached deterministic evaluation on the graph identified by the response token

#### Scenario: Differential cursor oracle
- **WHEN** generated mutations occur between pages
- **THEN** concatenated pages equal enumeration of the original exact graph or a graph with an equal complete dependency proof

### Requirement: Portable cache and query scopes include configuration identity
The core contract SHALL require deterministic fingerprints for adapter implementation, object-id codecs, recursion/traversal limits, caveat configuration, and every option capable of changing authorization or ordering. Mutable identity, caveat, and adapter data SHALL additionally provide snapshot dependency proofs; a function/configuration fingerprint alone is insufficient. A backend unable to provide complete stable fingerprints and proofs MUST disable completed-answer caching and graph-equivalent cursors.

#### Scenario: Adapter configuration changes
- **WHEN** two clients share a cache but use different answer-affecting configurations
- **THEN** their semantic keys and cursor scopes cannot validate against each other

#### Scenario: Adapter reads undeclared mutable state
- **WHEN** a primitive or codec depends on external mutable state absent from the fingerprint/proof
- **THEN** adapter validation rejects the cache/cursor capability claim

### Requirement: Old portable formats fail closed
The core module SHALL reject pre-change listener-counter tokens, unauthenticated base64 cursors, and cache entries lacking causal, proof, complete-key, or authentication fields.

#### Scenario: Decimal listener token is supplied
- **WHEN** a caller supplies an old DataScript or Datahike listener counter
- **THEN** EACL returns a typed unsupported-token-version error

#### Scenario: Old cursor is supplied
- **WHEN** a caller supplies an unauthenticated portable cursor
- **THEN** EACL rejects it as unsupported/invalid and requires pagination restart

#### Scenario: Old cache entry is returned
- **WHEN** a provider returns a prior entry format
- **THEN** EACL treats it as a miss and never interprets missing security fields

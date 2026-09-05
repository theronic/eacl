## MODIFIED Requirements

### Requirement: Shared cryptographic format service
The core module SHALL own versioned, bounded, canonical cryptographic formats for Zed tokens, cursor envelopes, and authorization-affecting cache entries. It SHALL use distinct signing/encryption domains and derived keys, authenticated key identifiers, a live rotation keyring, strict field allowlists, constant-time authentication checks, and exact portable numeric representations. Every encode/decode operation SHALL use one immutable snapshot supplied by the selected primary or optional dedicated Zed-token controller and MUST NOT read key material from a backend database. Cursor authentication is mandatory; cursor confidentiality SHALL remain an independently advertised capability.

#### Scenario: Portable cursor is created
- **WHEN** Datahike or DataScript emits a cursor
- **THEN** shared authentication protects its scope, stable position, dependency proof, and key identifier
- **AND** the cursor contains no unserializable backend object or secret key material

#### Scenario: Datomic encrypted cursor is created
- **WHEN** the Datomic adapter advertises confidential cursors
- **THEN** it uses authenticated encryption in addition to mandatory authenticity and names the key used by that envelope

#### Scenario: Synchronous browser client has no synchronous AEAD
- **WHEN** DataScript runs behind a synchronous ClojureScript API without compatible synchronous encryption
- **THEN** it may emit an authenticated non-confidential cursor using stable external identities/digests
- **AND** MUST NOT advertise cursor confidentiality

#### Scenario: Cache entry is read from a shared provider
- **WHEN** a provider returns a completed authorization value
- **THEN** shared code authenticates its key identifier, complete key, causal metadata, proof, and value before considering it

#### Scenario: Decoder receives hostile input
- **WHEN** a token exceeds size/depth limits, contains unknown fields, has an unsupported numeric representation, names no accepted key, or fails authentication
- **THEN** decoding fails with a bounded typed error before protected payload interpretation

#### Scenario: Artifact is minted during overlap
- **WHEN** old and new keys are accepted and the new key is active
- **THEN** every newly minted artifact names and uses the new key
- **AND** artifacts under the old key remain decodable until that key is retired

#### Scenario: Portable cache key is retired
- **WHEN** a shared provider returns an otherwise well-formed authenticated cache entry whose key id is no longer accepted
- **THEN** EACL treats the entry as a miss and recomputes from the selected snapshot
- **AND** does not turn optional cache unavailability into a permission grant or request failure

#### Scenario: Cursor key is retired
- **WHEN** a caller presents a cursor whose key id is no longer accepted
- **THEN** EACL fails the cursor contract loudly and never treats it as a cache miss or first-page request

### Requirement: Portable cache and query scopes include configuration identity
The core contract SHALL require deterministic fingerprints for adapter implementation, object-id codecs, recursion/traversal limits, Caveat evaluator configuration, and every option capable of changing authorization or ordering. Mutable identity, Caveat, and adapter data SHALL additionally provide snapshot dependency proofs; a function/configuration fingerprint alone is insufficient. A backend unable to provide complete stable fingerprints and proofs MUST disable completed-answer caching and graph-equivalent cursors.

Cryptographic keyring generation and active key selection SHALL NOT become authorization semantic-key or dependency-proof fields because they do not change denotation. Protected artifacts SHALL instead carry their authenticated key identifier and validate it against the current ring before use.

#### Scenario: Adapter configuration changes
- **WHEN** two clients share a cache but use different answer-affecting configurations
- **THEN** their semantic keys and cursor scopes cannot validate against each other

#### Scenario: Adapter reads undeclared mutable state
- **WHEN** a primitive or codec depends on external mutable state absent from the fingerprint/proof
- **THEN** adapter validation rejects the cache/cursor capability claim

#### Scenario: Only active security key changes
- **WHEN** two otherwise identical authorization checks straddle key activation
- **THEN** their semantic authorization cache identity remains compatible
- **AND** any newly serialized protected artifact uses the key active at its own mint operation

## ADDED Requirements

### Requirement: Live keyring remains backend-neutral and non-durable
The backend-neutral module SHALL contain the reusable live keyring controller abstraction, state validation, safe status, primary/dedicated-scope selection, and shared protected-format integration. Adapter modules SHALL consume that API without provider-specific secret-manager clients or duplicated rotation implementations. Resolving, updating, or using the controller MUST NOT persist its keys or make a per-authorization remote secret lookup.

#### Scenario: Core-only keyring use
- **WHEN** a third-party adapter or core-only consumer constructs and updates a keyring controller
- **THEN** the controller loads without Datomic, Datahike, DataScript, Datalevin, Vault, Kubernetes, or cloud secret-manager dependencies

#### Scenario: Backend client uses external secret delivery
- **WHEN** an application watcher obtains in-memory key material and updates a shared controller
- **THEN** every attached backend client can use the new generation without a database transaction or adapter-specific keyring implementation

### Requirement: Security key rotation is operationally documented
Documentation SHALL explain static keys, live controllers, unique key identifiers, external secret distribution, cluster acknowledgement, activation, overlap, retirement, non-expiring cursor consequences, partial-rollout recovery, rollback, and safe observability. Examples MUST avoid embedding production secrets in source and MUST distinguish cache misses from cursor/token errors after retirement.

#### Scenario: Operator follows the runbook
- **WHEN** an operator rotates keys across multiple live Peers using the documented sequence
- **THEN** the operator can identify which API updates each Peer, which status proves distribution, how long old keys must remain, and which artifacts will fail after retirement

#### Scenario: Key material source is chosen
- **WHEN** a consumer uses an external secret manager or configuration watcher
- **THEN** documentation shows how to pass obtained in-memory material to EACL without storing it in EACL databases or requiring EACL to contact that provider on authorization requests

## Purpose

Enable externally orchestrated, zero-downtime rotation of EACL security keys on running Peers while keeping secret material non-durable and every local transition atomic and fail closed.

## ADDED Requirements

### Requirement: One live non-durable keyring controller abstraction

EACL SHALL provide one backend-neutral live security-keyring controller abstraction whose instances contain a monotonically increasing generation, one active key identifier, and one or more accepted keys. A primary instance SHALL protect cursors and portable cache artifacts. An optional dedicated instance MAY protect Zed tokens; when absent, Zed tokens SHALL use the primary instance through their distinct derivation domain. Controllers and all secret key material MUST remain in process memory and MUST NOT be persisted in an EACL database or portable cache snapshot.

#### Scenario: Shared controller initializes clients

- **WHEN** multiple EACL clients on one Peer are constructed with the same valid controller
- **THEN** they use the same current keyring generation and active key without copying an independently mutable configuration

#### Scenario: Static configuration remains usable

- **WHEN** a client is constructed with existing static primary or dedicated Zed-token key options and no controller for that scope
- **THEN** EACL creates private controller instances with equivalent keyring, fallback, and active-key semantics

#### Scenario: Zed tokens use primary fallback

- **WHEN** a client configures no dedicated Zed-token controller or static Zed-token keyring
- **THEN** Zed tokens use the primary controller with their separate cryptographic derivation domain

#### Scenario: Ambiguous construction is rejected

- **WHEN** a client supplies both a live controller and static key material for the same primary or Zed-token scope
- **THEN** construction fails with a typed invalid-configuration error before retaining either source

### Requirement: Keyring replacement is atomic and generation-guarded

The controller SHALL support atomic replacement of the complete accepted-key map and active key id guarded by an expected generation. A successful replacement MUST expose either the complete old state or the complete new state to concurrent operations and MUST advance the generation exactly once.

#### Scenario: Valid desired state replaces atomically

- **WHEN** a caller supplies the current generation, a non-empty valid key map, and an active id present in that map
- **THEN** the complete new state becomes visible atomically and the returned status names the next generation

#### Scenario: Stale update conflicts

- **WHEN** a caller supplies an expected generation older than the controller's current generation
- **THEN** EACL rejects the replacement with a typed conflict that exposes safe current status but no key material

#### Scenario: Active key cannot disappear accidentally

- **WHEN** a desired state removes the current active key without selecting another retained key in the same replacement
- **THEN** validation rejects the update and the old state remains unchanged

### Requirement: Install, activate, and retire have separate semantics

EACL SHALL provide convenience operations to install an accepted inactive key, activate an installed key for new artifacts, and retire a non-active key. Each convenience operation MUST linearize through the same atomic state validator as complete replacement.

#### Scenario: Key is distributed before activation

- **WHEN** a new key is installed without activation
- **THEN** the Peer can verify/decrypt artifacts naming that key but continues minting with the prior active key

#### Scenario: Installed key becomes active

- **WHEN** an accepted key is activated
- **THEN** subsequently started mint operations name and use that key while previously accepted keys remain available for decode

#### Scenario: Active key retirement is rejected

- **WHEN** a caller attempts to retire the active key without atomically activating another installed key
- **THEN** EACL rejects the operation and preserves the current ring

#### Scenario: Old key retires after overlap

- **WHEN** an inactive accepted key is retired
- **THEN** subsequently started decode operations cannot obtain it and safe status no longer lists it as accepted

### Requirement: Key identifiers are stable epochs

Every key identifier SHALL be canonical, bounded, and unique to one key epoch. Accepted-key and retired-id collections MUST obey explicit resource ceilings. A running controller MUST reject every reintroduction of a retired identifier, even with the same material, because revival could make old artifacts valid again. Public status and error data MUST NOT expose secret fingerprints.

#### Scenario: Retired id is reintroduced

- **WHEN** a caller tries to install any material under an identifier retired earlier in the controller lifetime
- **THEN** EACL rejects the update as key-id reuse without logging or returning either key

#### Scenario: Idempotent distribution repeats while accepted

- **WHEN** the same currently accepted identifier and equivalent normalized key are installed again
- **THEN** the operation is an idempotent no-op or returns unchanged status rather than advancing to an ambiguous state

#### Scenario: Ring resource bound is exceeded

- **WHEN** a replacement exceeds the accepted-key or retired-id ceiling
- **THEN** EACL rejects it before allocating an unbounded controller state

### Requirement: Encode and decode use one immutable state snapshot

Each protected-format encode or decode operation SHALL capture one immutable controller state exactly once. Encoding SHALL use only the captured active key. Decoding SHALL select one key directly by the artifact's authenticated key identifier and MUST NOT try every key in the ring.

#### Scenario: Rotation overlaps an encode

- **WHEN** activation races with an encode operation
- **THEN** the artifact is produced wholly under either the old or new captured active key and never combines an id from one state with material from another

#### Scenario: Retirement overlaps a decode

- **WHEN** a decode captured the old state before retirement linearized
- **THEN** it MAY complete using that state
- **AND** every decode started after retirement returns cannot obtain the retired key

#### Scenario: Unknown key id is supplied

- **WHEN** a bounded artifact names an id absent from the captured accepted ring
- **THEN** EACL fails before protected payload interpretation and does not fall back to another key

### Requirement: Key updates are externally driven and non-secretly observable

EACL SHALL expose safe keyring status and MAY emit safe rotation events containing only operation category, key identifiers, generation, counts, and success/failure category. EACL MUST NOT implement provider-specific secret fetching or include key material in logs, metrics, exceptions, status, cursor payloads, cache snapshots, or databases.

#### Scenario: Consumer updates every Peer

- **WHEN** an application obtains a new secret from its external control plane and invokes the EACL update API on each Peer
- **THEN** EACL applies each local transition without making a database write or remote secret-manager request

#### Scenario: Status is inspected

- **WHEN** a caller reads controller status
- **THEN** it can observe generation, active id, accepted ids, retired ids, and safe counts but cannot recover key bytes or derived cryptographic keys

### Requirement: Rotation follows a documented overlap protocol

Documentation SHALL define the safe order generate externally, install on all Peers, observe acknowledgement, activate on all Peers, retain overlap, and retire on all Peers. It MUST explain that activation before distribution can make cross-Peer artifacts unreadable.

#### Scenario: Two-Peer zero-downtime rotation

- **WHEN** both Peers install the new key, both activate it while retaining the old key, and the old key is retired only after the overlap
- **THEN** each Peer can decode artifacts minted by either Peer throughout the rollout

#### Scenario: Peer misses distribution

- **WHEN** one Peer activates a key another Peer has not installed
- **THEN** the stale Peer rejects artifacts naming the unknown key with a typed error rather than trying the old key or silently restarting an operation

### Requirement: Rotation does not alter authorization semantics

Adding, activating, or retiring a security key SHALL NOT alter an authorization graph, source lifecycle, schema generation, Relation generation, qualifier generation, query ordering, or permission result. Optional cryptographically protected cache data that becomes unverifiable MUST be recomputed rather than treated as authorization evidence.

#### Scenario: Active key changes between identical checks

- **WHEN** the active key changes but the selected snapshot, query, context, time, and dependency proofs remain equal
- **THEN** uncached authorization returns the same semantic result
- **AND** newly emitted protected artifacts use the new id

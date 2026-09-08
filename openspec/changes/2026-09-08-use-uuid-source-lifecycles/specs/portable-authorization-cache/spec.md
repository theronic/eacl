## ADDED Requirements

### Requirement: Persisted cache restore validates UUID lineage before installation

Every reusable entry in an exported authorization-cache snapshot SHALL bind the upgraded format, complete native source scope, UUID lifecycle and required proof/ABI. The existing flat envelope SHALL remain; empty snapshots carry no reusable authority. Raw decoded snapshot APIs retain host-owned authentication; authenticated snapshot APIs verify the existing keyring envelope. Restore SHALL validate that contract before atomically installing any reusable state. Explicit restore of an obsolete lifecycle format SHALL return a typed upgrade-required outcome; opportunistic loading SHALL be allowed to treat this rejection as a miss and use valid source evaluation. Failed or raced restore SHALL leave the installed runtime unchanged and SHALL never rewrite old artifacts into accepted authority.

#### Scenario: Legacy snapshot contains a UUID-shaped string
- **WHEN** explicit restore receives an old-format snapshot even though its lifecycle text matches the current UUID
- **THEN** it requests a new snapshot and does not install any old entry

#### Scenario: Another source has the same lifecycle UUID
- **WHEN** an authenticated snapshot matches the lifecycle UUID but names a different source or branch
- **THEN** restore rejects it without changing installed caches

#### Scenario: Lifecycle rotates during restore preparation
- **WHEN** a snapshot passed initial validation but the runtime lifecycle changes before installation
- **THEN** the old candidate cannot install into the new lifecycle

#### Scenario: Compatible cross-process snapshot
- **WHEN** a newly started process loads an upgraded snapshot with matching durable native source scope, shared UUID, authentication and required proofs
- **THEN** restore preserves native UUID identity and the existing cache correctness contract

#### Scenario: Empty compatible snapshot
- **WHEN** a supported empty snapshot is restored
- **THEN** it installs no authority and requires no invented source metadata

#### Scenario: Mutable UUID in trusted decoded input
- **WHEN** a host supplies an authenticated decoded snapshot containing mutable CLJS UUID wrappers
- **THEN** EACL captures immutable UUID values before installation so later caller mutation cannot change imported keys

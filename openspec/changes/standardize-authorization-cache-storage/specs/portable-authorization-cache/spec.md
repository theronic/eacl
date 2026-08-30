# portable-authorization-cache Specification

## ADDED Requirements

### Requirement: Portable cache snapshots contain only public cache state

Cache export SHALL serialize a versioned deterministic sequence of validated
composite keys and operation-specific immutable values plus the minimum
lifecycle metadata required by the public snapshot contract. It MUST NOT
serialize library-private priority maps, recency ticks, weight estimates,
tombstones, access samples, atom-retry state, or atoms.

The restore API SHALL accept an already trusted decoded value. A host that
loads external bytes MUST authenticate and encoded-size-bound them before
calling restore; this host boundary MUST remain explicit in API documentation.
Canonical ordering of trusted live entries MUST NOT impose the secure token
codec's ordinary byte ceiling on an otherwise valid semantic cache key.
Ordinary statistics and lifecycle accounting MUST enumerate resident mappings
without serializing their keys.

Restore SHALL decode and validate all entries into fresh off-side LRU tiers
before atomically installing a new lifecycle. A typed snapshot from an older
key or value ABI MUST be rejected with the documented incompatibility outcome;
it MUST NOT be partially restored or silently upgraded.

#### Scenario: Snapshot order is deterministic

- **WHEN** two equivalent cache lifecycles are exported
- **THEN** their serialized public entry sequence and snapshot digest are equal regardless of library-private LRU representation

#### Scenario: A valid semantic key exceeds the ordinary token bound

- **WHEN** a resident mapping has an otherwise valid semantic key larger than the secure token codec's ordinary byte ceiling
- **THEN** statistics, export, restore, clear, and expiry remain defined for that mapping
- **AND** the host still authenticates and size-bounds any external encoded snapshot envelope

#### Scenario: Prior typed snapshot is restored

- **WHEN** restore receives a snapshot with the superseded key or cache-value ABI
- **THEN** restore returns the typed incompatibility outcome
- **AND** the currently installed lifecycle remains unchanged

#### Scenario: Validation fails midway

- **WHEN** one decoded entry fails operation-specific validation
- **THEN** none of the candidate lifecycle is installed

## MODIFIED Requirements

### Requirement: Backend-neutral cache store

The `eacl` module SHALL own one private database-neutral cache boundary and
entry lifecycle without depending on Datomic, DataScript, or Datahike. Its JVM
implementation SHALL use `org.clojure/core.cache` LRU and its CLJS
implementation SHALL use the selected `theronic/cljs-cache` LRU.
Database adapters and consumers MUST NOT supply alternate authorization cache
providers or depend on either library's private state.

#### Scenario: Supported runtimes use the private boundary

- **WHEN** a supported Datomic, DataScript, or Datahike client enables caching
- **THEN** it uses the same EACL storage operations and semantic key/value contract
- **AND** imports no database-specific cache implementation

#### Scenario: Alternate cache store

- **WHEN** a consumer supplies an alternate authorization cache store
- **THEN** client construction rejects the unsupported provider before serving requests

#### Scenario: Cache disabled

- **WHEN** caching is disabled for a client or request
- **THEN** authorization behavior remains correct and equivalent to uncached execution
- **AND** answer, subproblem, and continuation shared-store lookup/publication is bypassed
- **AND** a synchronous nested request clears any inherited answer/subproblem cache bindings before evaluation
- **AND** independent derived-schema and cursor-construction LRUs MAY still serve their internal non-authorization-cache roles

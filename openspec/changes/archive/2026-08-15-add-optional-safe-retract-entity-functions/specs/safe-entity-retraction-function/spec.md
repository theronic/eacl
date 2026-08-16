## Purpose

Provide an opt-in transaction-time entity deletion operation that prevents EACL endpoint-pair tuples from surviving on peer entities, with explicit deployment and consistency guarantees across supported storage backends.

## ADDED Requirements

### Requirement: Backend support is explicit and opt-in
Each EACL backend SHALL report whether safe entity retraction is available as a named installed transaction function, as a direct native transaction-function invocation, or is unsupported. No default EACL schema SHALL install `:eacl.fn/retractEntity`, and requesting an unavailable installation mode MUST fail with a structured error that identifies the backend, configuration, and supported alternative, if any.

#### Scenario: Default schemas remain unchanged
- **WHEN** a consumer installs a backend's default EACL schema but does not request the optional function
- **THEN** `:eacl.fn/retractEntity` is not installed and the backend's ordinary entity-retraction behavior is unchanged

#### Scenario: Named installation is supported
- **WHEN** a consumer requests installation on a backend and configuration that supports named transaction functions
- **THEN** the backend installs an invocable `:eacl.fn/retractEntity` definition without requiring a new third-party dependency

#### Scenario: Repeated installation is safe
- **WHEN** installation is requested more than once for the same compatible definition
- **THEN** the operation succeeds idempotently without changing application data

#### Scenario: Named installation is unavailable but direct invocation exists
- **WHEN** a backend supports native transaction-function values but its current configuration cannot store a named function
- **THEN** installation fails explicitly and EACL exposes and documents the equivalent direct transaction-function invocation

#### Scenario: Backend has no transaction-function mechanism
- **WHEN** a consumer requests this capability from a backend that cannot run transaction functions against transaction-start state
- **THEN** EACL reports the capability as unsupported and directs the consumer to the portable `delete-object!` workflow

### Requirement: Safe retraction uses transaction-start state
For a supported invocation targeting an entity that exists at transaction start, the transaction function SHALL derive its cleanup from that transaction's authoritative starting database value and SHALL atomically retract the entity under the backend's ordinary entity-retraction semantics together with every EACL forward and reverse relationship half touching that entity.

#### Scenario: Resource deletion removes both endpoint halves
- **WHEN** an existing resource is safely retracted while subjects have relationships to it
- **THEN** the resource is absent after commit, every corresponding forward half is absent from each subject, and permission and resource-lookup APIs no longer return those relationships

#### Scenario: Subject deletion removes both endpoint halves
- **WHEN** an existing subject is safely retracted while resources have relationships from it
- **THEN** the subject is absent after commit, every corresponding reverse half is absent from each resource, and subject-lookup APIs no longer return it

#### Scenario: Mixed directions, relations, and self-relationships
- **WHEN** one entity participates as subject and resource across multiple relation definitions, including a relationship to itself
- **THEN** the commit removes every touching endpoint half exactly once in the resulting database and leaves unrelated relationships unchanged

#### Scenario: Ordinary entity-retraction behavior is preserved
- **WHEN** the target has non-EACL attributes, inbound reference attributes, or component entities handled by the backend's ordinary entity-retraction operation
- **THEN** safe retraction preserves that backend-native cleanup in addition to removing EACL endpoint pairs

#### Scenario: Unresolved target is a no-op
- **WHEN** the target eid or lookup ref does not resolve at transaction start
- **THEN** the invocation commits no EACL relationship or consistency changes, matching ordinary entity-retraction no-op semantics

#### Scenario: Existing ghosts use the repair workflow
- **WHEN** the target was previously retracted and only peer-side orphan halves remain
- **THEN** safe retraction does not perform an unbounded database scan and documentation directs the consumer to the existing integrity audit and repair workflow

### Requirement: Certified concurrent writes cannot recreate a ghost
Safe retraction SHALL compose with EACL's certified relationship-write path so that serialization either includes a relationship committed before deletion in the same cleanup or prevents a relationship based on the deleted endpoint from committing afterward.

#### Scenario: Relationship commits before deletion
- **WHEN** a certified relationship write wins serialization before safe retraction
- **THEN** the transaction function observes the committed relationship and retracts both halves with the entity

#### Scenario: Deletion commits before relationship
- **WHEN** safe retraction wins serialization before a competing certified relationship write based on an older snapshot
- **THEN** the relationship write cannot commit a surviving half for the deleted endpoint and fails or retries through the backend's existing concurrency contract

### Requirement: Cache and consistency proofs advance atomically
The same transaction that retracts relationship halves SHALL advance every backend-visible relation dependency and graph-order value required by EACL's managed caches, cursors, and consistency tokens. A successful invocation MUST NOT require an out-of-band cache eviction or coordinator barrier.

#### Scenario: Cached grant is revoked
- **WHEN** an authorization result is cached before safe retraction removes a relationship on which it depends
- **THEN** the first read after the committed deletion does not reuse the stale grant under any supported cache proof mode

#### Scenario: Unaffected relation cache remains reusable
- **WHEN** safe retraction changes relationships for one set of relation definitions
- **THEN** dependency proofs for relation definitions not touched by the deleted entity remain unchanged

#### Scenario: Consistency state observes deletion
- **WHEN** a client captures backend graph or ordering state after safe retraction commits
- **THEN** that state is strictly later than the pre-deletion state and names a database-visible committed mutation anchor where the backend supports mutation anchors

### Requirement: Expansion work is degree-bounded
Safe-retraction discovery SHALL use point/range access scoped to the target entity's two endpoint attributes. Expansion work and emitted cleanup data SHALL be linear in the number of relationship halves stored on the target plus the number of distinct affected relations, and MUST NOT scan all relationships, all objects, or all schema relations.

#### Scenario: Unrelated database size does not increase discovery work
- **WHEN** the same target degree is tested in databases with increasing numbers of unrelated entities and relationships
- **THEN** the measured target-attribute reads and emitted cleanup operations remain constant within backend-defined fixed overhead

#### Scenario: High-degree deletion scales linearly
- **WHEN** target degree is increased across the maintained performance fixtures
- **THEN** operation counts scale linearly, no peer/client cache coordinator is acquired, and benchmark evidence records transaction expansion and commit cost separately from first-use function compilation

### Requirement: Documentation prevents unsafe substitution
The root README and each affected backend README SHALL explain why ordinary entity retraction can create ghost relationships, how to opt into and invoke safe retraction on that backend, its deployment/configuration limits, and when to use `delete-object!`, explicit relationship deletion, batching, or integrity repair instead.

#### Scenario: Consumer chooses a deletion path
- **WHEN** a consumer reads the entity-deletion documentation
- **THEN** the documentation provides tested examples for supported installed/direct transaction-function modes and clearly distinguishes atomic safe retraction from portable batched deletion and post-hoc ghost repair
